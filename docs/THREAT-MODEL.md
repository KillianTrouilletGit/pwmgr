# Threat model

PwMgr is a self-hosted, zero-knowledge password manager. This document enumerates the threats it defends against, the threats it explicitly does not, and where each line is drawn. Pair this with [CRYPTO.md](CRYPTO.md), which is the normative specification for everything cryptographic.

The reader should leave this document able to answer: "Should I trust PwMgr with my passwords?" — with full visibility into the trade-offs.

---

## 1. Assets

What PwMgr protects, in priority order:

| # | Asset | Why it matters | Where it lives |
|---|---|---|---|
| A1 | **Master password** | Decrypts everything. | Only in user memory + transiently in RAM during unlock. |
| A2 | **Vault Key (VK)** | 256-bit AES key that decrypts the entries blob. | RAM while unlocked; wrapped in `wrap.ct` on disk; never logged. |
| A3 | **Entry secrets** (passwords, TOTP seeds, secure-note contents) | The actual things the user came here for. | Encrypted under VK in `payload.ct`; decrypted into Kotlin `String`s in RAM while unlocked. |
| A4 | **Entry metadata** (titles, usernames, URLs) | Lower stakes than passwords but still sensitive; URL list reveals which services the user has accounts on. | Same encrypted payload as A3. |
| A5 | **Refresh token** for Google Drive | Lets an attacker write/read the vault file in Drive. (They still can't decrypt it without A1/A2.) | Encrypted under VK in `oauth.tok` next to the vault. |

Assets A1 and A2 are non-recoverable. There is no password reset, no recovery email, no support ticket. Lose A1 with no backup of A2 → vault is permanently inaccessible. This is documented loudly in the create-vault screen and in README.

---

## 2. Adversaries

Modeled in increasing capability. Each row narrows the gap between attacker and asset.

| Tier | Adversary | Capabilities |
|---|---|---|
| T1 | Passive cloud snooper | Reads `vault.enc` and `oauth.tok` from Google Drive (assume Drive is compromised or the user's Google account is breached). No code execution anywhere. |
| T2 | Active cloud attacker | T1 + can tamper with the Drive file (substitute, rewind to old version, edit headers). |
| T3 | Network attacker on the wire | Sees and can modify HTTPS to `googleapis.com` and to the OAuth endpoints. (TLS terminates inside the OS, so this is mostly theoretical with valid CAs — but consider it for the purposes of resistance.) |
| T4 | Same-LAN attacker | T3 + can talk to ports bound to `0.0.0.0` on the user's machine. |
| T5 | Other Windows user on the same machine | Reads files in another user's `%LOCALAPPDATA%` (admin-equivalent). |
| T6 | Same-user malware | Code execution as the logged-in user. Can read all of the user's files, read clipboard, dump memory of running processes. |
| T7 | Physical access, device unlocked | Sits at the unlocked user's keyboard. |
| T8 | Physical access, device locked + biometric enrolled | Has the user's locked phone or laptop; can attempt biometric, but not the master password. |
| T9 | Coerced master password | Adversary obtains A1 directly. |

---

## 3. Threats and mitigations

Each threat lists the tier it applies at, what we do about it, and the residual risk.

### T1 — Passive cloud snooper reads `vault.enc`

**Mitigation.** The file is AES-256-GCM ciphertext under VK; VK is itself encrypted under MK; MK is derived from the master password by Argon2id with OWASP-floor parameters. To extract entries, the attacker must either:
- guess the master password and run Argon2id per guess (memory-hard → expensive on GPUs/ASICs), OR
- break AES-256-GCM (no known attacks).

We bind `version` and the full `kdf` block as AEAD associated data, so an attacker can't substitute a weaker KDF profile.

**Residual.** If the user picks a weak password ("password123"), the Argon2id memory hardness slows the attacker, but doesn't stop them. The create-vault screen enforces a 12-character minimum; in a future polish pass we'll add a zxcvbn-style strength meter.

### T1 — Passive cloud snooper reads `oauth.tok`

**Mitigation.** Encrypted under VK with a domain-separated AAD (`"pwmgr-token-v1"`) so it can't be replayed as vault payload ciphertext. Without VK, it's opaque.

**Residual.** A2 (the refresh token) leaks only if A1 (master password) is also broken.

### T2 — Active cloud attacker tampers with the vault file

**Mitigation.** Every header field that influences key derivation (`version`, `kdf.salt`, `kdf.memKiB`, `kdf.iter`, `kdf.par`) is bound to both ciphertexts via the AEAD AAD. Any modification of any of those fields causes decryption to fail with `AEADBadTagException` BEFORE any plaintext is produced. The crypto test suite covers all of these positions.

**Residual.** Rollback to a previous valid version of the file (a captured older `vault.enc`) IS possible at the crypto layer — old ciphertexts remain valid forever. We mitigate this at the **sync layer**: every push uses Drive's `If-Match` with the prior ETag, and the merge engine rejects entry timestamps that go backwards inside a single entry id. A determined attacker who can also forge ETags can still serve an old version; the user would notice missing recent entries the next time they sync.

### T2 — Active cloud attacker deletes the vault file

**Mitigation.** Out of scope from confidentiality. PwMgr keeps a local copy at `%LOCALAPPDATA%\PwMgr\vault.enc`; loss of the Drive copy is a sync inconvenience, not a data loss.

**Residual.** If the user's local copy is also wiped (T6 or T7) and the cloud copy is deleted, the vault is gone. The export feature exists for this exact case — a backup the attacker doesn't know about.

### T3 / T4 — Network attacker between PwMgr and `googleapis.com`

**Mitigation.** All Drive API calls go over HTTPS via the JDK's `HttpURLConnection`, which uses the OS trust store. Certificate validation is the JDK default (no overrides). PKCE protects against authorization-code interception on the loopback redirect.

**Residual.** A compromised CA could MITM and serve a malicious response from `oauth2.googleapis.com`. Realistic only at nation-state level. Pinning the Google CA chain would help; not done in v1.

### T4 — Same-LAN attacker probes the local IPC server

**Mitigation.** The IPC server binds explicitly to `127.0.0.1`, never to `0.0.0.0`. The OS kernel rejects connections from non-loopback interfaces before they reach our code. Confirmed via the `ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))` call site.

**Residual.** None at this layer.

### T5 — Other Windows user on the same machine

**Mitigation.**
- `vault.enc`, `oauth.tok`, `biometric.wrap`, `settings.json`, `ipc.handshake`, and the native-host manifest all live under `%LOCALAPPDATA%`, which NTFS ACLs restrict to the current Windows user by default.
- DPAPI convenience-unlock encrypts VK under a per-user key. A different Windows user cannot call `CryptUnprotectData` to recover it.

**Residual.** A user with admin rights can take ownership of any file and read it. Admin-tier attackers are out of scope at T5; they belong to T6.

### T6 — Same-user malware

**This is the hardest tier and where most of the residual risk lives.**

Mitigations are best-effort, layered:
- **Memory hygiene.** Master password as `CharArray`, MK zeroed immediately after VK unwrap, VK zeroed on lock, password `ByteArray` zeroed after Argon2. Plaintext payload zeroed after JSON parse. Implementation in `VaultFile.kt` and `Aead.kt`.
- **Auto-lock timer.** Default 5 minutes. Reduces the window VK is in RAM.
- **Clipboard auto-clear.** Default 20 s, only if the clipboard contents are still what we copied.
- **DPAPI convenience-unlock isn't biometric-gated.** On Windows v1, malware running as the same user can call `CryptUnprotectData` and decrypt the biometric wrap silently. The Settings screen labels this option "Convenience unlock" rather than "Biometric unlock" to avoid claiming protection we don't deliver. Promoting to Windows Hello via WinRT would close this gap; deferred to a future phase.
- **Android Keystore is biometric-gated.** The hardware-bound key cannot be released without a successful biometric match. Even malware with full app-data access can ask for the key, but the OS prompts the user — visible attack.

**Residual.** A patient T6 attacker who can wait for the user to unlock the vault can:
- read VK out of process memory while it's resident (no DEP/ASLR-style memory protection at the JVM level);
- read decrypted entries the moment the user navigates to them;
- on Windows, decrypt the biometric wrap any time;
- read the clipboard immediately after a copy (auto-clear doesn't help if malware reads first).

**We accept this residual.** Same-user malware is the kill-everything attacker tier — no consumer password manager survives it. The countermeasure is "don't get malware", which is outside PwMgr's scope.

### T6 — Malware tampers with the desktop binary

**Mitigation.** None at runtime. Mitigation is at install time: signed releases (see [RELEASE.md](RELEASE.md)) let the user verify the binary they're running matches the one we built.

**Residual.** Signature verification protects against tampering on the wire / on disk, but doesn't protect against malware that replaces the binary post-install. The OS-level Authenticode check helps only at first launch.

### T7 — Physical access, device unlocked

**Mitigation.**
- Auto-lock timer (default 5 min idle).
- "Lock now" button in Settings.
- Reveal-to-show password fields (passwords are masked by default).
- On Android, locking the device immediately triggers `MainActivity.onStop` which calls `state.lock()`, dropping VK.

**Residual.** A determined attacker who reaches the unlocked device while a session is fresh can browse the vault list and read entries until the auto-lock fires. The "5 minute" default is a trade-off; the user can drop it lower.

### T8 — Physical access, device locked, biometric enrolled

**Mitigation.**
- Biometric unlock on Android requires a successful fingerprint/face match through the OS's `BiometricPrompt`. The Keystore key is invalidated when a new biometric is enrolled (`setInvalidatedByBiometricEnrollment(true)`).
- On Windows, DPAPI-only "convenience unlock" is not gated — see T6 / Windows. An attacker who knows the user's Windows account password (or has the device unlocked at the OS level) can unlock the vault silently.

**Residual.** Windows-side, the same critique as T6 applies. Document the asymmetry explicitly in the UI labelling.

### T9 — Coerced master password

**Out of scope.** PwMgr doesn't help against the rubber-hose attack. We don't implement plausible deniability (decoy vaults), nor duress codes that wipe the vault. The user is expected to manage this themselves with physical-security practices (don't put the vault on a phone you're forced to unlock at a border, etc.).

---

## 4. Side channels and implementation footguns

### Timing
We do not constant-time-compare anywhere it matters. AES-GCM's authenticator IS verified in constant time by the JCA provider. Master password equality is never tested — wrong-password detection is a side effect of GCM auth failure on `wrap.ct`, which is constant-time by construction.

### Random numbers
`java.security.SecureRandom` on both JVM and Android. On Android, this is backed by `/dev/urandom`; on JVM, by the platform's CSPRNG. We do not seed it manually. The CSPRNG smoke test (10 000 distinct nonces) catches gross misconfiguration.

### Default-locale operations
Some `String.lowercase()` calls (in host matching) use the default locale. For ASCII URLs this is fine. For internationalized domain names, behavior is locale-dependent — could cause matching false-negatives but never false-positives. Acceptable.

### Logging
Kermit's `Logger` is configured to NEVER log values that could contain secrets. Entry-edit code paths use object identity hashes for diagnostics, never the entry payload itself. Audit pass scheduled for the polish phase.

---

## 5. What we explicitly punt on

- **Browser extension save-on-submit capture.** Users add entries manually for v1. Reduces attack surface (we don't read every form submission).
- **Plausible deniability / duress mode.** No decoy vaults, no panic-wipe codes.
- **Recovery codes.** Lose the master password → vault is gone.
- **Forward secrecy across vault versions.** An attacker who captured an old ciphertext can decrypt it once they learn the master password.
- **Memory encryption / scrubbing inside the JVM.** Best-effort `zeroize()` on `ByteArray`/`CharArray`, no SecureMemory equivalent.
- **Anti-tamper / anti-debug on the binary.** Detection of JVM debugger, anti-rooting, anti-MagiskHide — none of it.
- **Sandboxing of the renderer.** Compose runs in-process; an XSS-style bug in our rendering code (extremely unlikely in Compose) would have full process access. Not a meaningful attack surface for a password manager UI.

---

## 6. How to verify the claims here

- **Crypto layer**: read [CRYPTO.md](CRYPTO.md), then read `shared/crypto/src/jvmAndAndroidMain/kotlin/com/pwmgr/crypto/` (~250 LOC). Tests in `shared/crypto/src/jvmTest/`.
- **Vault format binding**: `shared/core/src/commonMain/kotlin/com/pwmgr/core/vault/VaultFile.kt`. Tamper tests in `shared/core/src/jvmTest/kotlin/com/pwmgr/core/vault/VaultFileTest.kt`.
- **Sync behavior**: `shared/storage/src/`. Merge logic tests in `shared/storage/src/jvmTest/kotlin/com/pwmgr/storage/MergeEngineTest.kt`.
- **IPC server**: `desktopApp/src/jvmMain/kotlin/com/pwmgr/desktop/ipc/LocalIpcServer.kt` — note the `127.0.0.1` bind, the auth-token first-frame check, and the locked-state refusal of `match`/`reveal`.
- **Android autofill matching**: `androidApp/src/main/kotlin/com/pwmgr/android/autofill/EntryMatcher.kt`. Cross-domain refusal tests in `androidApp/src/test/`.

Run the full test suite with:

```powershell
.\gradlew.bat :shared:crypto:jvmTest :shared:core:jvmTest :shared:storage:jvmTest :androidApp:testDebugUnitTest
```

If anything in this document doesn't match what the code does, the code is what ships — open an issue.
