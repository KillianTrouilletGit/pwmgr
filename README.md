# PasswordManagerMultiplatform

A multiplatform password manager for Windows and Android. Zero-knowledge encryption with a master password, optional biometric unlock, and an encrypted vault synced via Google Drive. Portfolio project — code is open for review.

> **Status:** Phase 5a — Desktop Google Drive sync (OAuth PKCE, encrypted-blob upload, 3-way merge with tombstones). Android Drive sync = Phase 5b.

## Documentation

- **[docs/CRYPTO.md](docs/CRYPTO.md)** — the normative cryptographic specification. Read this before reading the code.
- **[docs/BUILD.md](docs/BUILD.md)** — toolchain prerequisites and how to bootstrap the Gradle wrapper.
- **[docs/DRIVE-SETUP.md](docs/DRIVE-SETUP.md)** — Google Cloud project + OAuth credentials setup (~10 min, one-time).

## Modules

| Module | Purpose | Phase 1 |
|---|---|---|
| `:shared:crypto` | Argon2id KDF, AES-256-GCM AEAD, CSPRNG — expect/actual API. | ✅ |
| `:shared:core`   | Vault file format, canonical JSON for AEAD AAD, vault open/save logic, domain model (`VaultEntry`). | ✅ |
| `:shared:storage` | Google Drive REST client, OAuth, token storage, merge engine. | ✅ desktop · ⏳ Android OAuth (Phase 5b) |
| `:shared:ui`     | Compose Multiplatform screens + AppState shared across Android + desktop. | ✅ |
| `:androidApp`    | Android entry (`MainActivity`); `AutofillService`, `BiometricPrompt` later. | ✅ shell · ⏳ biometric (Phase 6), autofill (Phase 7) |
| `:desktopApp`    | Windows entry (Compose Desktop); system tray, Windows Hello, native messaging host later. | ✅ shell · ⏳ Win Hello (Phase 6), native messaging (Phase 8) |
| `browser-extension/` | MV3 extension (Chromium + Firefox) — fills login forms via native messaging. | ⏳ Phase 8 |

Phase ordering and full architecture: see the plan at `~/.claude/plans/i-want-to-do-nested-fountain.md` (local to this development environment).

## Quickstart

```powershell
# Prereq: JDK 17 or 21, plus Android SDK 34 for the Android app. See docs/BUILD.md.

# Run the test suite (Phase 1)
.\gradlew.bat :shared:crypto:jvmTest :shared:core:jvmTest

# Launch the desktop app
.\gradlew.bat :desktopApp:run

# Build + install the Android app on a connected device or emulator
.\gradlew.bat :androidApp:installDebug
```

First launch shows the create-vault screen (no `vault.enc` on disk yet). After creating a vault you'll land on the empty vault list. Use **Add entry** to create logins / secure notes / cards / identities, with an inline password generator (configurable length and character classes). Clicking a row opens the editor with **Delete** in the top bar. Search filters by title, username, and URL. Subsequent launches go straight to the unlock prompt. The vault file lives at `%LOCALAPPDATA%\PwMgr\vault.enc` on Windows, or `~/PwMgr/vault.enc` elsewhere.

Five consecutive wrong-password attempts trigger exponential backoff (1s, 2s, 4s, 8s, … capped at 30s).

Every CRUD operation re-encrypts the payload under the existing vault key (fresh GCM nonce per save) and atomically replaces the on-disk file. The `revision` counter in the header increments on every save — visible in the list top bar.

The test suite covers:
- AES-GCM round-trip + tamper detection (every byte position).
- Argon2id parameter floor enforcement + determinism.
- Canonical JSON golden vectors (used for AEAD AAD; must be byte-stable).
- Full vault create → save → unlock round-trip.
- Tamper rejection on every header field that's bound to the ciphertext: salt, memKiB, iter, par, wrap.ct, payload.ct.
- Wrong-password rejection.
- Unsupported version + below-floor params rejected at parse time.

## What "secure" means here

See [CRYPTO.md §1](docs/CRYPTO.md#1-threat-model). Short version: the vault file is treated as fully public. An attacker who steals it should learn nothing without the master password. Tampering of any header field that affects the key derivation invalidates decryption.

## What's intentionally NOT in v1

- **Recovery code.** Forget the master password → vault is gone. Documented in the spec.
- **Auto-lock by timer.** Recommended for a security tool; will be added in Phase 9 (polish). Tracking issue: TBD.
- **Forward secrecy across vault revisions.** The vault is a mutable blob.
- **Padding to hide vault size.** Not meaningfully exploitable at expected sizes; could revisit in format v2.
