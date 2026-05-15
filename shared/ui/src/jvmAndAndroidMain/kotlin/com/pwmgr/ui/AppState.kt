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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
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

    private var syncEngine: SyncEngine? = null
    private var pendingSyncJob: Job? = null
    private var lastEtag: String? = null
    private var cachedAccount: OAuthAccount? = null

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
            screen = Screen.VaultList
            unlockError = null
            evaluateSyncState()
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
            evaluateSyncState()
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

    fun lock() {
        pendingSyncJob?.cancel()
        pendingSyncJob = null
        syncEngine = null
        cachedAccount = null
        lastEtag = null
        syncStatus = if (syncAvailable) SyncStatus.NotConfigured else SyncStatus.NotConfigured
        session?.lock()
        session = null
        payload = null
        screen = if (storage.exists()) Screen.Unlock else Screen.CreateVault
        unlockError = null
    }

    // ── Navigation ──────────────────────────────────────────────────────────

    fun openEditor(entryId: String?) { screen = Screen.EntryEditor(entryId) }
    fun closeEditor() { screen = Screen.VaultList }
    fun openDriveSetup() { screen = Screen.DriveSetup }
    fun closeDriveSetup() { screen = Screen.VaultList }

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
