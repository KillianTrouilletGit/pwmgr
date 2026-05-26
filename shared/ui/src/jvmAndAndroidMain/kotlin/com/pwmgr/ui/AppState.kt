package com.pwmgr.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pwmgr.core.model.VaultEntry
import com.pwmgr.core.model.VaultPayload
import com.pwmgr.core.vault.CorruptVaultException
import com.pwmgr.core.vault.InvalidVaultFormatException
import com.pwmgr.core.vault.VaultException
import com.pwmgr.core.vault.VaultFile
import com.pwmgr.core.vault.VaultSession
import com.pwmgr.core.vault.WrongPasswordException
import com.pwmgr.crypto.zeroize
import com.pwmgr.storage.CloudStorage
import com.pwmgr.storage.GoogleDriveClient
import com.pwmgr.storage.OAuthAccount
import com.pwmgr.storage.OAuthProvider
import com.pwmgr.storage.SyncEngine
import com.pwmgr.storage.SyncOutcome
import com.pwmgr.storage.TokenStore
import com.pwmgr.ui.settings.AppSettings
import com.pwmgr.ui.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Top-level navigation state. */
sealed interface Screen {
    data object CreateVault : Screen
    data object Unlock : Screen
    data object VaultList : Screen
    data class EntryEditor(val entryId: String?) : Screen
    data object DriveSetup : Screen
    data object Settings : Screen
    /** Shown once after vault creation OR after recovery to display the recovery code. */
    data object RecoveryCodeDisplay : Screen
    /** Forgot-master-password flow: accepts recovery code + new master password. */
    data object Recovery : Screen
    /** Windows-only — rendered by the platform via [PwMgrApp]'s `extraRoute` slot. */
    data object BrowserExtension : Screen
}

sealed interface SyncStatus {
    data object NotConfigured : SyncStatus
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Ready(val lastSyncEpochMs: Long, val email: String?) : SyncStatus
    data class Failed(val message: String, val email: String?) : SyncStatus
}

/**
 * Cross-platform app state. Holds the unlocked session and payload, drives navigation,
 * exposes CRUD + persistence, and coordinates Google Drive sync when an [OAuthProvider]
 * and [TokenStore] are wired in.
 *
 * Sync is opportunistic: an initial pass runs right after unlock if Drive is configured,
 * and a debounced sync (~2 s) follows every successful save.
 */
@OptIn(ExperimentalUuidApi::class)
class AppState(
    val storage: VaultStorage,
    private val clock: Clock = Clock.System,
    private val oauthProvider: OAuthProvider? = null,
    private val tokenStore: TokenStore? = null,
    private val biometricGate: BiometricGate? = null,
    private val settingsStore: SettingsStore? = null,
    private val exportSink: ExportSink? = null,
    /** Surface the Browser-Extension nav button — Windows-only feature. */
    val browserExtensionAvailable: Boolean = false,
    val saveDriveConfig: ((clientId: String, clientSecret: String) -> Unit)? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    /** True if the platform wired an [ExportSink] (file picker available). */
    val exportAvailable: Boolean get() = exportSink != null
    var screen by mutableStateOf<Screen>(if (storage.exists()) Screen.Unlock else Screen.CreateVault)
        private set

    var session: VaultSession? by mutableStateOf(null)
        private set

    var payload: VaultPayload? by mutableStateOf(null)
        private set

    var unlockError: String? by mutableStateOf(null)
        private set

    var busy by mutableStateOf(false)
        private set

    var lockedOutUntilEpochMs: Long by mutableStateOf(0L)
        private set

    var consecutiveFailures: Int by mutableStateOf(0)
        private set

    /** Current Drive sync state. */
    var syncStatus: SyncStatus by mutableStateOf(SyncStatus.NotConfigured)
        private set

    /** True if Drive setup is even possible (the platform provided an OAuthProvider). */
    val syncAvailable: Boolean get() = oauthProvider != null && tokenStore != null

    /** True if the platform supports biometric/convenience unlock at all. */
    var biometricAvailable: Boolean by mutableStateOf(false)
        private set

    /** True if a biometric wrap has been enrolled for this vault. */
    var biometricEnrolled: Boolean by mutableStateOf(biometricGate?.isEnrolled() == true)
        private set

    /** Error message from the most recent biometric op, or null if all good. */
    var biometricError: String? by mutableStateOf(null)
        private set

    /** User-facing preferences (auto-lock timeout, clipboard clear, etc.). Always non-null. */
    var settings: AppSettings by mutableStateOf(settingsStore?.load() ?: AppSettings.DEFAULT)
        private set

    private var syncEngine: SyncEngine? = null
    private var pendingSyncJob: Job? = null
    private var lastEtag: String? = null
    private var cachedAccount: OAuthAccount? = null

    /** Wall-clock ms of the last user-initiated action (input/nav). Drives [settings.autoLockMs]. */
    private var lastActivityMs: Long = clock.now().toEpochMilliseconds()
    private var autoLockJob: Job? = null

    /**
     * The recovery code freshly generated at vault creation OR after a successful recovery.
     * Shown to the user once via [Screen.RecoveryCodeDisplay], then cleared. Never persisted
     * anywhere — losing this BEFORE acknowledging it = losing it forever.
     */
    var pendingRecoveryCode: String? by mutableStateOf(null)
        private set

    /** Temporarily holds the OAuth token after a Drive import until the vault is unlocked. */
    private var pendingImportToken: OAuthAccount? = null

    fun acknowledgeRecoveryCode() {
        pendingRecoveryCode = null
        screen = Screen.VaultList
    }

    /**
     * Recovers a forgotten master password using the recovery code shown at vault creation.
     * On success: VK is in memory, vault list is shown, a NEW recovery code is generated
     * and displayed via [Screen.RecoveryCodeDisplay] (the old one is invalidated by the
     * re-wrap). Returns failure if the recovery code is wrong or the vault has no recovery
     * block (older vault from before recovery was added).
     */
    fun recoverWithCode(recoveryCode: String, newPassword: CharArray): Result<Unit> {
        busy = true
        return try {
            val bytes = storage.read()
            val res = VaultFile.recoverWithCode(
                fileBytes = bytes,
                recoveryCode = recoveryCode.trim().uppercase(),
                newPassword = newPassword,
                deviceId = Uuid.random().toString(),
                nowIso = nowIso(),
            )
            storage.write(res.fileBytes)
            session = res.session
            payload = VaultFile.decryptWithVaultKey(res.fileBytes, res.session.vaultKey)
            pendingRecoveryCode = res.recoveryCode
            screen = Screen.RecoveryCodeDisplay
            unlockError = null
            consecutiveFailures = 0
            lockedOutUntilEpochMs = 0
            requireExplicitUnlock = false
            evaluateSyncState()
            startAutoLockWatcher()
            Result.success(Unit)
        } catch (e: WrongPasswordException) {
            unlockError = "Recovery code is invalid."
            Result.failure(e)
        } catch (e: InvalidVaultFormatException) {
            unlockError = "This vault was created before recovery codes existed and cannot be recovered."
            Result.failure(e)
        } catch (e: Throwable) {
            unlockError = e.message ?: "Recovery failed."
            Result.failure(e)
        } finally {
            newPassword.zeroize()
            busy = false
        }
    }

    fun openRecovery() { screen = Screen.Recovery }
    fun closeRecovery() { screen = Screen.Unlock }

    init {
        // Probe biometric hardware availability asynchronously — the call may touch the OS
        // crypto stack (DPAPI on Windows, BiometricManager on Android) which we don't want
        // to block on at construction time.
        scope.launch {
            biometricAvailable = biometricGate?.isAvailable() == true
        }
    }

    // ── Vault lifecycle ─────────────────────────────────────────────────────

    fun createVault(password: CharArray): Result<Unit> {
        busy = true
        return try {
            val res = VaultFile.create(
                password = password,
                deviceId = Uuid.random().toString(),
                nowIso = nowIso(),
            )
            storage.write(res.fileBytes)
            session = res.session
            payload = VaultPayload()
            pendingRecoveryCode = res.recoveryCode
            screen = Screen.RecoveryCodeDisplay
            unlockError = null
            evaluateSyncState()
            startAutoLockWatcher()
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            password.zeroize()
            busy = false
        }
    }

    fun unlock(password: CharArray): Result<Unit> {
        val now = clock.now().toEpochMilliseconds()
        if (now < lockedOutUntilEpochMs) {
            password.zeroize()
            val remainSec = ((lockedOutUntilEpochMs - now) + 999) / 1000
            unlockError = "Too many failed attempts. Try again in ${remainSec}s."
            return Result.failure(IllegalStateException(unlockError))
        }
        busy = true
        return try {
            val bytes = storage.read()
            val result = VaultFile.unlock(bytes, password)
            session = result.session
            payload = result.payload
            screen = Screen.VaultList
            unlockError = null
            consecutiveFailures = 0
            lockedOutUntilEpochMs = 0
            requireExplicitUnlock = false
            
            // If the user just imported from Drive, save the token now that we have the vaultKey.
            if (pendingImportToken != null) {
                tokenStore?.write(result.session.vaultKey, pendingImportToken!!)
                pendingImportToken = null
            }
            
            evaluateSyncState()
            startAutoLockWatcher()
            Result.success(Unit)
        } catch (e: WrongPasswordException) {
            registerFailure()
            unlockError = "Wrong master password."
            Result.failure(e)
        } catch (e: InvalidVaultFormatException) {
            unlockError = "Vault file is invalid: ${e.message}"
            Result.failure(e)
        } catch (e: CorruptVaultException) {
            unlockError = "Vault file is corrupted: ${e.message}"
            Result.failure(e)
        } catch (e: VaultException) {
            unlockError = e.message
            Result.failure(e)
        } finally {
            password.zeroize()
            busy = false
        }
    }

    /**
     * Manual lock from the UI (Lock now button, window close). Allows biometric auto-unlock
     * to fire on the next UnlockScreen composition — user intent was "lock briefly".
     */
    fun lock() {
        lockInternal(requireExplicit = false)
    }

    /**
     * Auto-lock from the idle watcher. Sets [requireExplicitUnlock] so the UnlockScreen
     * does NOT auto-fire biometric — the user must consciously click "Use biometric" or
     * type the master password. Without this, DPAPI's silent unlock on Windows makes auto-
     * lock invisible (lock → instant biometric unwrap → user sees nothing changed).
     */
    private fun autoLock() {
        lockInternal(requireExplicit = true)
    }

    private fun lockInternal(requireExplicit: Boolean) {
        pendingSyncJob?.cancel()
        pendingSyncJob = null
        autoLockJob?.cancel()
        autoLockJob = null
        syncEngine = null
        cachedAccount = null
        lastEtag = null
        syncStatus = if (syncAvailable) SyncStatus.NotConfigured else SyncStatus.NotConfigured
        session?.lock()
        session = null
        payload = null
        screen = if (storage.exists()) Screen.Unlock else Screen.CreateVault
        unlockError = null
        requireExplicitUnlock = requireExplicit
    }

    /**
     * True when an idle auto-lock just fired — UnlockScreen skips its automatic biometric
     * prompt until the user makes an explicit move (click biometric button, type password).
     */
    var requireExplicitUnlock: Boolean by mutableStateOf(false)
        private set

    /**
     * Called by screens on user activity (key press, click, scroll). Resets the auto-lock
     * countdown. Cheap — just touches a Long.
     */
    fun recordActivity() {
        lastActivityMs = clock.now().toEpochMilliseconds()
    }

    fun updateSettings(updated: AppSettings) {
        settings = updated
        settingsStore?.save(updated)
        // Restart the auto-lock watcher so the new timeout takes effect immediately.
        if (session != null) startAutoLockWatcher()
    }

    /**
     * Writes the on-disk vault bytes verbatim to a user-chosen destination via the platform
     * [ExportSink]. The exported file IS a valid vault — same master password unlocks it.
     * Returns the destination path (for the "exported to …" toast) or null on cancel/error.
     */
    suspend fun exportVault(): String? {
        val sink = exportSink ?: return null
        val bytes = try {
            storage.read()
        } catch (_: Throwable) {
            return null
        }
        val stamp = clock.now().toString().replace(':', '-').take(19) // "2026-05-15T12-34-56"
        return sink.saveBytes(suggestedFilename = "pwmgr-export-$stamp.enc", bytes = bytes)
    }

    private fun startAutoLockWatcher() {
        autoLockJob?.cancel()
        if (settings.autoLockMs <= 0L) return  // never lock
        recordActivity()
        autoLockJob = scope.launch {
            while (isActive) {
                val timeout = settings.autoLockMs
                if (timeout <= 0L) return@launch
                val now = clock.now().toEpochMilliseconds()
                val elapsed = now - lastActivityMs
                if (elapsed >= timeout) {
                    autoLock()
                    return@launch
                }
                // Wake up either at the projected lock time, or once a second to handle
                // recordActivity() bumps that push the deadline forward.
                delay(minOf(timeout - elapsed, 1_000L))
            }
        }
    }

    // ── Navigation ──────────────────────────────────────────────────────────

    fun openEditor(entryId: String?) { screen = Screen.EntryEditor(entryId) }
    fun closeEditor() { screen = Screen.VaultList }
    fun openDriveSetup() { screen = Screen.DriveSetup }
    fun closeDriveSetup() { screen = Screen.VaultList }
    fun openBrowserExtension() { screen = Screen.BrowserExtension }
    fun closeBrowserExtension() { screen = Screen.VaultList }
    fun openSettings() { screen = Screen.Settings }
    fun closeSettings() { screen = Screen.VaultList }

    // ── CRUD ────────────────────────────────────────────────────────────────

    fun findEntry(entryId: String): VaultEntry? =
        payload?.entries?.firstOrNull { it.id == entryId }

    fun upsertEntry(entry: VaultEntry): Result<Unit> {
        val current = payload ?: return Result.failure(IllegalStateException("vault is locked"))
        val s = session ?: return Result.failure(IllegalStateException("vault is locked"))
        val without = current.entries.filterNot { it.id == entry.id }
        val updated = current.copy(entries = without + entry)
        return persist(s, updated)
    }

    fun deleteEntry(entryId: String): Result<Unit> {
        val current = payload ?: return Result.failure(IllegalStateException("vault is locked"))
        val s = session ?: return Result.failure(IllegalStateException("vault is locked"))
        val target = current.entries.firstOrNull { it.id == entryId }
            ?: return Result.failure(NoSuchElementException("entry $entryId not found"))
        val nowInstant = clock.now()
        val tombstoned = target.copy(deletedAt = nowInstant, updatedAt = nowInstant)
        val updated = current.copy(entries = current.entries.filterNot { it.id == entryId } + tombstoned)
        return persist(s, updated)
    }

    private fun persist(s: VaultSession, newPayload: VaultPayload): Result<Unit> {
        val previous = payload
        return try {
            payload = newPayload
            val bytes = VaultFile.save(s, newPayload, nowIso())
            storage.write(bytes)
            scheduleSyncAfterSave()
            Result.success(Unit)
        } catch (t: Throwable) {
            payload = previous
            Result.failure(t)
        }
    }

    // ── Drive sync ──────────────────────────────────────────────────────────

    /**
     * Starts the OAuth flow with Google, persists the resulting refresh token, and runs
     * an initial sync. Should be called from a coroutine.
     */
    suspend fun setupDrive(): Result<Unit> {
        val oauth = oauthProvider ?: return Result.failure(IllegalStateException("OAuth not provisioned on this platform"))
        val store = tokenStore ?: return Result.failure(IllegalStateException("Token store not provisioned"))
        val s = session ?: return Result.failure(IllegalStateException("vault is locked"))

        syncStatus = SyncStatus.Syncing
        return try {
            val account = oauth.authorize() ?: return Result.failure(IllegalStateException("Authorization cancelled"))
            store.write(s.vaultKey, account)
            cachedAccount = account
            buildSyncEngine(oauth, account.refreshToken)
            runSync(s)
            Result.success(Unit)
        } catch (t: Throwable) {
            syncStatus = SyncStatus.Failed(t.message ?: t::class.simpleName.orEmpty(), cachedAccount?.email)
            Result.failure(t)
        }
    }

    /**
     * Connects to Google Drive to download an existing vault *before* a local vault exists.
     * The token is kept in memory and persisted automatically during the next `unlock()`.
     */
    suspend fun importFromDrive(): Result<Unit> {
        val oauth = oauthProvider ?: return Result.failure(IllegalStateException("OAuth not provisioned on this platform"))
        busy = true
        return try {
            val account = oauth.authorize() ?: return Result.failure(IllegalStateException("Authorization cancelled"))
            val cloud = GoogleDriveClient(getAccessToken = { oauth.refreshAccessToken(account.refreshToken) })
            val remote = cloud.get()
            if (remote == null) {
                Result.failure(IllegalStateException("No vault found on Google Drive."))
            } else {
                storage.write(remote.bytes)
                pendingImportToken = account
                screen = Screen.Unlock
                Result.success(Unit)
            }
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            busy = false
        }
    }

    /** Removes the persisted refresh token. */
    fun disconnectDrive() {
        scope.launch {
            tokenStore?.clear()
            cachedAccount = null
            syncEngine = null
            lastEtag = null
            syncStatus = SyncStatus.NotConfigured
        }
    }

    /** Manually trigger a sync round (e.g., user tapped the refresh icon). */
    fun syncNow() {
        val s = session ?: return
        scope.launch { runSync(s) }
    }

    private fun scheduleSyncAfterSave() {
        val s = session ?: return
        if (syncEngine == null) return
        pendingSyncJob?.cancel()
        pendingSyncJob = scope.launch {
            delay(SYNC_DEBOUNCE_MS)
            runSync(s)
        }
    }

    /**
     * After unlock or vault creation, see if Drive was previously configured. If so,
     * load the token, build the sync engine, and trigger an initial pull/push.
     */
    private fun evaluateSyncState() {
        val s = session ?: return
        val oauth = oauthProvider
        val store = tokenStore
        if (oauth == null || store == null) {
            syncStatus = SyncStatus.NotConfigured
            return
        }
        if (!store.isConfigured()) {
            syncStatus = SyncStatus.NotConfigured
            return
        }
        scope.launch {
            try {
                val account = store.read(s.vaultKey)
                if (account == null) {
                    syncStatus = SyncStatus.NotConfigured
                    return@launch
                }
                cachedAccount = account
                buildSyncEngine(oauth, account.refreshToken)
                runSync(s)
            } catch (t: Throwable) {
                syncStatus = SyncStatus.Failed("Failed to load Drive token: ${t.message}", null)
            }
        }
    }

    private fun buildSyncEngine(oauth: OAuthProvider, refreshToken: String) {
        // Per-sync access-token cache: refreshes once at the start of each Drive call burst.
        val cloud: CloudStorage = GoogleDriveClient(
            getAccessToken = { oauth.refreshAccessToken(refreshToken) },
        )
        syncEngine = SyncEngine(cloud)
    }

    private suspend fun runSync(s: VaultSession) {
        val engine = syncEngine ?: return
        val currentPayload = payload ?: return
        val email = cachedAccount?.email
        syncStatus = SyncStatus.Syncing
        try {
            val localBytes = storage.read()
            val outcome = engine.sync(
                localPayload = currentPayload,
                localBytes = localBytes,
                decryptRemote = { remoteBytes -> VaultFile.decryptWithVaultKey(remoteBytes, s.vaultKey) },
                encryptMerged = { merged -> VaultFile.save(s, merged, nowIso()) },
                now = clock.now(),
            )
            when (outcome) {
                is SyncOutcome.CreatedRemote -> {
                    lastEtag = outcome.etag
                }
                is SyncOutcome.UpToDate -> {
                    lastEtag = outcome.etag
                    payload = outcome.merged
                }
                is SyncOutcome.Synced -> {
                    lastEtag = outcome.etag
                    payload = outcome.merged
                    storage.write(outcome.newLocalBytes)
                }
            }
            syncStatus = SyncStatus.Ready(clock.now().toEpochMilliseconds(), email)
        } catch (t: Throwable) {
            syncStatus = SyncStatus.Failed(t.message ?: t::class.simpleName.orEmpty(), email)
        }
    }

    // ── Biometric unlock ────────────────────────────────────────────────────

    /**
     * Enrolls biometric unlock for this vault. The vault must be unlocked (VK in memory).
     * On Android, this prompts the biometric sensor. On Windows, it's silent (DPAPI).
     */
    suspend fun enableBiometric(): Result<Unit> {
        val gate = biometricGate ?: return Result.failure(IllegalStateException("biometric not available"))
        val s = session ?: return Result.failure(IllegalStateException("vault is locked"))
        biometricError = null
        return gate.enroll(s.vaultKey).also { result ->
            result.onSuccess { biometricEnrolled = true }
            result.onFailure { biometricError = it.message }
        }
    }

    /**
     * Authenticates the user, decrypts the wrapped VK, and opens the vault. Used from the
     * unlock screen when biometric is enrolled. Returns null on cancel; a Throwable on error.
     */
    suspend fun unlockWithBiometric(): Result<Unit> {
        val gate = biometricGate ?: return Result.failure(IllegalStateException("biometric not available"))
        if (!storage.exists()) return Result.failure(IllegalStateException("no vault on disk"))
        busy = true
        biometricError = null
        return try {
            val vk = gate.unlock() ?: return Result.failure(BiometricCancelledException())
            try {
                val fileBytes = storage.read()
                val result = VaultFile.unlockWithVaultKey(fileBytes, vk)
                session = result.session
                payload = result.payload
                screen = Screen.VaultList
                unlockError = null
                consecutiveFailures = 0
                lockedOutUntilEpochMs = 0
                requireExplicitUnlock = false
                evaluateSyncState()
                startAutoLockWatcher()
                Result.success(Unit)
            } finally {
                vk.zeroize()
            }
        } catch (t: Throwable) {
            biometricError = t.message
            Result.failure(t)
        } finally {
            busy = false
        }
    }

    suspend fun disableBiometric(): Result<Unit> {
        val gate = biometricGate ?: return Result.success(Unit)
        return try {
            gate.disable()
            biometricEnrolled = false
            biometricError = null
            Result.success(Unit)
        } catch (t: Throwable) {
            biometricError = t.message
            Result.failure(t)
        }
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private fun registerFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= BACKOFF_THRESHOLD) {
            val attemptsOverThreshold = consecutiveFailures - BACKOFF_THRESHOLD
            val delayMs = (BACKOFF_BASE_MS shl attemptsOverThreshold).coerceAtMost(BACKOFF_CAP_MS)
            lockedOutUntilEpochMs = clock.now().toEpochMilliseconds() + delayMs
        }
    }

    private fun nowIso(): String = clock.now().toString()

    companion object {
        const val BACKOFF_THRESHOLD = 5
        const val BACKOFF_BASE_MS = 1_000L
        const val BACKOFF_CAP_MS = 30_000L
        const val SYNC_DEBOUNCE_MS = 2_000L
    }
}
