# PasswordManagerMultiplatform

A zero-knowledge password manager for Windows and Android. Single master password, optional biometric unlock, encrypted vault synced via Google Drive, autofill on both platforms. Built as a portfolio project — the code is open for review and every architectural decision is documented.

> **Status:** Phase 10 — Release-ready. Signed builds, threat model, export, polish. See [Roadmap](#roadmap) for what's intentionally still ahead.

---

## Why this exists

I wanted a password manager I could trust without paying a subscription, that worked on the two devices I actually use (Windows desktop, Android phone), and that I could audit end to end. Building one was a forcing function to learn Kotlin Multiplatform, Compose Multiplatform, Android autofill, browser-extension native messaging, and the OAuth + Drive API stack.

Every primitive choice is justified in [docs/CRYPTO.md](docs/CRYPTO.md). Every trade-off is enumerated in [docs/THREAT-MODEL.md](docs/THREAT-MODEL.md). Nothing is hand-waved.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                   Kotlin Multiplatform monorepo                     │
│                                                                     │
│  :shared:crypto        Argon2id, AES-256-GCM, HMAC-SHA1, CSPRNG     │
│  :shared:core          Vault format, canonical JSON, TOTP, models   │
│  :shared:storage       Drive REST, OAuth, sync engine, merge        │
│  :shared:ui            Compose screens + AppState + settings        │
│                                                                     │
│  :androidApp           MainActivity, AutofillService, BiometricGate │
│  :desktopApp           Compose Desktop, IPC server, ExtensionInst.  │
│  :nativeHost           Browser stdio ↔ TCP relay (tiny jar)         │
└─────────────────────────────────────────────────────────────────────┘
                              ▲
                              │ chrome native messaging (per-user)
                              │
                  ┌───────────────────────────┐
                  │   browser-extension/      │
                  │   MV3, Chromium + Edge    │
                  └───────────────────────────┘

                  Sync                         Autofill
              ┌───────────┐              ┌─────────────────┐
   Vault ←──→ │   Drive   │ ←──→ Vault   │  Android system │ ←──── Android
              │  (appdata)│              │   AutofillSvc   │       app/browser
              └───────────┘              └─────────────────┘
                                                  +
                                         ┌─────────────────┐
                                         │ Chromium / Edge │ ←──── PC browser
                                         │   extension     │
                                         └─────────────────┘
```

| Module | Purpose |
|---|---|
| `:shared:crypto` | Argon2id KDF (BouncyCastle), AES-256-GCM AEAD (JCA), HMAC-SHA1 (TOTP only), CSPRNG. |
| `:shared:core` | Vault file format (CRYPTO.md §4), canonical JSON for AAD, vault open/save logic, TOTP (RFC 6238), domain model (`VaultEntry`). |
| `:shared:storage` | Drive REST client (`HttpURLConnection`), OAuth PKCE provider, encrypted token storage, 3-way merge engine. |
| `:shared:ui` | Compose Multiplatform screens, `AppState`, settings persistence, biometric gate, clipboard auto-clear. |
| `:androidApp` | Android entry, `AutofillService`, `BiometricPrompt` via Keystore. R8-shrunk release. |
| `:desktopApp` | Compose Desktop window, local IPC server (127.0.0.1, HMAC-token-authed), DPAPI convenience unlock, extension installer. |
| `:nativeHost` | ~150 LOC bridge: browser length-prefixed stdio ↔ desktop TCP. |
| `browser-extension/` | MV3 extension: content-script icon, popup, native-messaging client (TypeScript + Vite). |

---

## Documentation

Read these in order:

1. **[docs/CRYPTO.md](docs/CRYPTO.md)** — normative cryptographic specification. The code conforms to this document; if they disagree, the document is correct.
2. **[docs/THREAT-MODEL.md](docs/THREAT-MODEL.md)** — what we defend against, what we don't, and where each line is drawn.
3. **[docs/BUILD.md](docs/BUILD.md)** — toolchain prerequisites and wrapper bootstrap.

Operational guides:

- **[docs/DRIVE-SETUP.md](docs/DRIVE-SETUP.md)** — Google Cloud + OAuth credentials (~10 min, one-time).
- **[docs/AUTOFILL.md](docs/AUTOFILL.md)** — enable PwMgr's Android autofill, how matching works.
- **[docs/EXTENSION-SETUP.md](docs/EXTENSION-SETUP.md)** — build and install the browser extension.
- **[docs/RELEASE.md](docs/RELEASE.md)** — sign releases (APK, MSI, packed extension zip).

---

## Quickstart

```powershell
# Prereq: JDK 17 or 21, Android SDK 34, Node 20+. See docs/BUILD.md.

# Run the full test suite (~1 minute on a warm cache)
.\gradlew.bat :shared:crypto:jvmTest :shared:core:jvmTest :shared:storage:jvmTest :androidApp:testDebugUnitTest

# Launch the desktop app
.\gradlew.bat :desktopApp:run

# Build + install the Android app
.\gradlew.bat :androidApp:installDebug

# Build the browser extension
cd browser-extension && npm install && npm run build
```

First desktop launch: create-vault screen. Master password is 12 characters minimum, no recovery. Subsequent launches go to the unlock prompt.

---

## Features

- **Zero-knowledge encryption.** Master password → Argon2id (64 MiB, t=3) → MK; MK unwraps a fresh random VK; VK encrypts payload with AES-256-GCM. See [CRYPTO.md §3](docs/CRYPTO.md#3-key-hierarchy).
- **Tamper-evident.** Every header field that influences key derivation is AEAD-bound. Tests flip every byte position to prove rejection.
- **Cross-device sync.** Drive `appDataFolder` (hidden private folder). Optimistic concurrency via ETags; conflicts resolved by per-entry LWW with 30-day tombstones.
- **Biometric unlock.** Android: `BiometricPrompt` + Keystore (`setInvalidatedByBiometricEnrollment`). Windows: DPAPI convenience unlock (clearly labeled — see [CRYPTO.md §3.1](docs/CRYPTO.md#31-biometric--convenience-unlock-optional-per-device) for the honest comparison).
- **Autofill.** Android `AutofillService` for apps + browsers; Chromium/Edge extension for desktop browsers. Cross-domain matching refuses suffix-without-dot collisions (regression test: `EntryMatcher.cross_domain_does_not_match`).
- **TOTP codes.** Stored seed → rotating 6-digit codes, RFC 6238 vectors covered.
- **Auto-lock + clipboard auto-clear.** Configurable timers; clipboard clear is conditional (won't overwrite a fresh user copy).
- **Encrypted export.** Local `.enc` copy via the system file picker (desktop). Format is identical to the live vault — drop it back to restore.

---

## Test coverage at a glance

| Module | What's tested |
|---|---|
| `:shared:crypto` | AES-GCM round-trip + every-byte-position tamper; Argon2 parameter floors; SecureRandom uniqueness smoke. |
| `:shared:core`   | Vault create→save→unlock; tamper on `salt`, `memKiB`, `iter`, `par`, `wrap.ct`, `payload.ct`, `version`; CanonicalJson golden vectors; TOTP RFC 6238 Appendix B vectors. |
| `:shared:storage` | Merge: LWW, tombstones (TTL + purge), disjoint sets, simultaneous edits, deterministic output ordering. |
| `:androidApp`    | EntryMatcher: cross-domain rejection, subdomain matching both ways, package-token fallback, tombstone/login-type filters. |

---

## Roadmap

What's still intentionally ahead, by likely effort:

- **Phase 5b — Android Drive sync.** Replace the `AndroidOAuth` stub with Credential Manager + `AuthorizationClient`. Same `SyncEngine` runs unchanged.
- **Windows Hello.** Replace DPAPI with WinRT `KeyCredentialManager` for true biometric gating. WinRT JVM bindings are the gnarly part.
- **Firefox extension.** Same code, different registry path + manifest extension. ~half a day.
- **Autofill save-on-submit.** Capture new credentials from form submissions. Both Android (`onSaveRequest`) and the browser extension currently no-op.
- **Android export.** `ActivityResultContracts.CreateDocument` flow + UI plumbing.
- **Inline suggestions on Android 11+.** `InlinePresentation` keyboard chips.
- **zxcvbn password strength meter** on create / generator.
- **Recovery code (optional)** — emergency printable code that wraps VK with a separate KDF lineage.

---

## Screenshots

> Capture targets: create-vault, vault list, entry editor (with TOTP), unlock with biometric prompt, settings, Drive setup, browser extension popup. Add under `docs/images/` referenced from here once captured.

---

## License

MIT (or substitute your preferred license).

---

## Acknowledgements

Stands on the shoulders of: BouncyCastle (Argon2), JetBrains (Kotlin Multiplatform + Compose), Google (Drive API + Android Autofill + Material 3), and every open-source password manager that came before — Bitwarden's threat model in particular is the standard everyone is measured against.
