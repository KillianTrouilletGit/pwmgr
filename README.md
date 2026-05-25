# PwMgr

**A zero-knowledge password manager for Windows + Android. Free, self-hosted, BYO Google Drive.**

[![Tests](https://github.com/YOUR_USER/YOUR_REPO/actions/workflows/test.yml/badge.svg)](https://github.com/YOUR_USER/YOUR_REPO/actions/workflows/test.yml)
[![Release](https://img.shields.io/github/v/release/YOUR_USER/YOUR_REPO?include_prereleases&sort=semver)](https://github.com/YOUR_USER/YOUR_REPO/releases)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

Single master password → Argon2id → AES-256-GCM. No accounts, no servers, no telemetry. Your encrypted vault lives in **your own** Google Drive `appDataFolder` (or just on disk — sync is optional).

![Vault list](docs/images/03-vault-list.png)

---

## 30-second pitch

- **Master password is the only thing between you and your data.** Lose it AND your recovery code → vault is gone. No reset, no "contact support".
- **Zero-knowledge.** Encryption happens on your device. Google sees an opaque blob.
- **Multi-platform.** Windows desktop (Compose), Android, Chromium/Edge browser extension. Same vault file across all three.
- **Autofill** on Android (system AutofillService) and on desktop (browser extension via native messaging).
- **No subscription, no accounts, no telemetry.** You bring your own Google Cloud project (~10 min one-time setup).

Read [THREAT-MODEL.md](docs/THREAT-MODEL.md) before trusting this with your data. Read [CRYPTO.md](docs/CRYPTO.md) before reading the code.

---

## Quick start

### Download

Grab the latest from the [Releases page](https://github.com/YOUR_USER/YOUR_REPO/releases). Each release ships:

- `PwMgr-X.Y.Z.msi` — Windows installer (~80 MB, includes a bundled JRE)
- `androidApp-release.apk` — Android sideload APK
- `pwmgr-extension.zip` — browser extension (unpacked install in `chrome://extensions`)
- `pwmgr-native-host-windows.zip` — bridges the browser extension to the desktop app
- `SHA256SUMS.txt` — verify your downloads with `Get-FileHash <file> -Algorithm SHA256`

> Artifacts are **unsigned**. Windows SmartScreen will warn you on first launch (More info → Run anyway). See [docs/RELEASE.md](docs/RELEASE.md) for the rationale and how to build your own signed copies.

### Run it (Windows desktop)

1. Run the MSI installer.
2. Launch **PwMgr** from the Start menu.
3. Pick a master password (≥ 12 chars, strength meter must be at least "OK"). Confirm it.
4. **Save the recovery code shown next.** Print it, paste it into another password manager, email it to yourself — anywhere but the same machine.
5. You're in. Use **Add entry** to create your first login.

### Build from source

```powershell
# Prereq: JDK 21 (Temurin), Android SDK 34, Node 20+.
# Bootstrap the Gradle wrapper once — see docs/BUILD.md.

# Run the test suite
.\gradlew.bat :shared:crypto:jvmTest :shared:core:jvmTest :shared:storage:jvmTest :androidApp:testDebugUnitTest

# Desktop
.\gradlew.bat :desktopApp:run

# Android (device or emulator with USB debugging)
.\gradlew.bat :androidApp:installDebug

# Browser extension (Chromium / Edge — load `dist/` unpacked)
cd browser-extension && npm install && npm run build
```

### Optional setup

Each of these is a one-time configuration step the user does themselves — PwMgr is BYO infrastructure.

| Feature | Setup time | Doc |
|---|---|---|
| Drive sync (desktop + Android) | ~10 min in Google Cloud Console | [docs/DRIVE-SETUP.md](docs/DRIVE-SETUP.md) |
| Android autofill | <1 min in Settings | [docs/AUTOFILL.md](docs/AUTOFILL.md) |
| Browser extension + native messaging | ~5 min | [docs/EXTENSION-SETUP.md](docs/EXTENSION-SETUP.md) |
| Building signed releases | ~10 min one-time | [docs/RELEASE.md](docs/RELEASE.md) |

---

## Screenshots

| | |
|---|---|
| ![Create vault](docs/images/01-create-vault.png) | ![Recovery code](docs/images/02-recovery-code.png) |
| _Master password + zxcvbn strength meter_ | _Recovery code shown once — save it_ |
| ![Vault list](docs/images/03-vault-list.png) | ![Entry editor](docs/images/04-entry-editor.png) |
| _Searchable vault list with sync status_ | _Entry editor with TOTP code rotating_ |
| ![Settings](docs/images/05-settings.png) | ![Drive setup](docs/images/06-drive-setup.png) |
| _Settings: biometric, auto-lock, clipboard, export_ | _Drive sync setup_ |
| ![Browser popup](docs/images/07-browser-extension.png) | ![Inline autofill](docs/images/08-extension-content.png) |
| _Browser extension popup_ | _Inline 🔐 icon next to password fields_ |

> Capturing these is straightforward — see [docs/images/README.md](docs/images/README.md) for the exact list and recommended dimensions.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                  Kotlin Multiplatform monorepo                      │
│                                                                     │
│  :shared:crypto      Argon2id, AES-256-GCM, HMAC-SHA1, CSPRNG       │
│  :shared:core        Vault format, canonical JSON, TOTP, models     │
│  :shared:storage     Drive REST, OAuth, sync engine, 3-way merge    │
│  :shared:ui          Compose screens + AppState + settings          │
│                                                                     │
│  :androidApp         MainActivity, AutofillService, BiometricGate   │
│  :desktopApp         Compose Desktop, IPC server, extension setup   │
│  :nativeHost         Browser stdio ↔ TCP relay                      │
└─────────────────────────────────────────────────────────────────────┘
                              ▲
                              │ chrome native messaging
                              │
                  ┌───────────────────────────┐
                  │   browser-extension/      │
                  │   TypeScript MV3          │
                  └───────────────────────────┘
```

---

## Documentation

| Doc | What it covers |
|---|---|
| [docs/CRYPTO.md](docs/CRYPTO.md) | Normative cryptographic spec. The code conforms to this; if they disagree, the doc is correct. |
| [docs/THREAT-MODEL.md](docs/THREAT-MODEL.md) | Adversary tiers T1-T9, what we mitigate, what we explicitly don't. |
| [docs/BUILD.md](docs/BUILD.md) | Toolchain prerequisites, Gradle wrapper bootstrap. |
| [docs/DRIVE-SETUP.md](docs/DRIVE-SETUP.md) | Google Cloud project + OAuth setup (one-time). |
| [docs/AUTOFILL.md](docs/AUTOFILL.md) | Enable Android autofill, matcher behavior, limitations. |
| [docs/EXTENSION-SETUP.md](docs/EXTENSION-SETUP.md) | Browser extension install + native messaging registration. |
| [docs/RELEASE.md](docs/RELEASE.md) | Build signed APK / MSI. |

---

## What's intentionally missing

This is a **self-hostable, FOSS, no-budget** project. We deliberately do not have:

- Apple ecosystem (iOS, macOS app store) — $99/year + audience overlap with self-hosters is small
- Code-signing certificates — $200-400/year for a CA-issued one
- A backend server — would mean GDPR responsibilities, hosting cost, audit burden
- Verified Google OAuth app — each user runs their own GCP project
- Localization — English only
- A bug bounty program

The flip side: PwMgr is **auditable end-to-end** in a single afternoon. Everything from key derivation to the byte-level IPC protocol is in this repo and documented.

---

## Roadmap

What's still ahead, ordered roughly by impact:

- **Imports** from Bitwarden / 1Password / KeePass — switching cost is real
- **Capture-on-save** for Android autofill + browser extension — auto-add new credentials
- **Firefox extension** — same code, different registry/manifest path
- **Linux desktop build** — Compose Desktop already supports it, just need to test + ship `.deb` / `.rpm`
- **Windows Hello** via WinRT (replace the DPAPI-only convenience unlock with real biometric gating)
- **Local backup history** — keep N last vault snapshots on disk for emergency recovery
- **iOS** — only if Compose Multiplatform iOS matures enough

---

## Contributing

Issues + PRs welcome on [github.com/YOUR_USER/YOUR_REPO](https://github.com/YOUR_USER/YOUR_REPO). Read the threat model and crypto spec first — security issues should follow the [SECURITY.md](SECURITY.md) disclosure path, not the public issue tracker.

## License

MIT. See [LICENSE](LICENSE).

## Acknowledgements

Standing on the shoulders of: BouncyCastle (Argon2), JetBrains (Kotlin Multiplatform + Compose), Google (Drive API + Android Autofill + Material 3), Nulab (zxcvbn4j), and the open-source password managers that came before — Bitwarden's threat model in particular is the standard everyone else is measured against.
