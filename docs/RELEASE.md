# Building signed releases

Both platforms produce reproducible release artifacts the user can install without going through Gradle. This document walks through:

- generating signing material (Android keystore, Windows certificate)
- wiring credentials into the build via gradle.properties / env vars
- the gradle commands that produce shippable artifacts
- where the outputs land

Nothing in `RELEASE.md` is required to run the app in dev mode. `:desktopApp:run` and `:androidApp:installDebug` work without any signing material.

---

## Android — signed APK / AAB

### 1. Generate the keystore (one-time)

```powershell
keytool -genkeypair `
    -alias pwmgr `
    -keyalg RSA -keysize 4096 `
    -validity 36500 `
    -keystore pwmgr-release.jks `
    -storetype PKCS12 `
    -dname "CN=PwMgr, O=Personal, C=FR"
```

`keytool` prompts for two passwords: a **store** password (for the keystore file) and a **key** password (for the private key inside it). Many people use the same for both. Either way: **back up the keystore file AND remember those passwords**. If you ever publish this app to the Play Store, you cannot rotate the key — losing it means uploading under a new app id forever.

Move the resulting `pwmgr-release.jks` somewhere outside the repo (e.g., `%USERPROFILE%\.android\pwmgr-release.jks`). The `.gitignore` excludes `*.jks` but it's still safer outside the project tree.

### 2. Provide credentials to Gradle

Two options:

**Option A — gradle.properties (per-user, recommended for dev):**

Edit `%USERPROFILE%\.gradle\gradle.properties` (NOT the one in the project!) and add:

```
pwmgr.android.keystore=C:/Users/<YOU>/.android/pwmgr-release.jks
pwmgr.android.keystorePassword=<store password>
pwmgr.android.keyAlias=pwmgr
pwmgr.android.keyPassword=<key password>
```

**Option B — environment variables (CI):**

```
PWMGR_KEYSTORE=/path/to/pwmgr-release.jks
PWMGR_KEYSTORE_PASSWORD=<store password>
PWMGR_KEY_ALIAS=pwmgr
PWMGR_KEY_PASSWORD=<key password>
```

If neither is set, the release build still succeeds but produces an **unsigned** APK, useful only for local testing.

### 3. Build

```powershell
.\gradlew.bat :androidApp:assembleRelease            # APK
.\gradlew.bat :androidApp:bundleRelease              # AAB (Play Store format)
```

Output:
- `androidApp/build/outputs/apk/release/androidApp-release.apk` (~5-10 MB after R8)
- `androidApp/build/outputs/bundle/release/androidApp-release.aab`

Verify the signature:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\34.0.0\apksigner.bat" verify --verbose `
    androidApp\build\outputs\apk\release\androidApp-release.apk
```

You should see `Verifies` and signer `CN=PwMgr, ...`.

### 4. Install on a device

```powershell
adb install -r androidApp\build\outputs\apk\release\androidApp-release.apk
```

The first install of a `release` build over a `debug` build is rejected because the signing certificates differ. Uninstall first:

```powershell
adb uninstall com.pwmgr.android
adb install androidApp\build\outputs\apk\release\androidApp-release.apk
```

---

## Windows — signed MSI / EXE

Compose Desktop's `jpackage`-based pipeline produces native installers. Signing requires a code-signing certificate; for personal use, a self-signed cert is sufficient (Windows will still warn about the unverified publisher, but the file's integrity is verifiable).

### 1. Generate a self-signed cert (one-time)

In PowerShell **as administrator**:

```powershell
$cert = New-SelfSignedCertificate `
    -Subject "CN=PwMgr Personal" `
    -Type CodeSigning `
    -CertStoreLocation "Cert:\CurrentUser\My" `
    -KeyAlgorithm RSA -KeyLength 4096 `
    -NotAfter (Get-Date).AddYears(10)

# Export to a .pfx file with a password
$pw = ConvertTo-SecureString -String "<your-password>" -Force -AsPlainText
Export-PfxCertificate -Cert $cert -FilePath "$env:USERPROFILE\pwmgr-codesign.pfx" -Password $pw
```

The `.pfx` contains both the private key and the certificate. Store it like the Android keystore — outside the repo, backed up.

For a CA-issued cert (DigiCert, SSL.com, etc.), the same `.pfx` shape applies; skip step 1 and import the issued cert into Cert:\CurrentUser\My.

### 2. Build the MSI

```powershell
.\gradlew.bat :desktopApp:packageReleaseMsi
```

Output: `desktopApp/build/compose/binaries/main-release/msi/PwMgr-0.1.0.msi` (~80 MB — bundles a trimmed JRE).

> Compose Desktop's `nativeDistributions` block currently emits unsigned installers. To sign, run `signtool` on the produced file as a post-build step:

```powershell
& "${env:ProgramFiles(x86)}\Windows Kits\10\bin\10.0.22621.0\x64\signtool.exe" sign `
    /f $env:USERPROFILE\pwmgr-codesign.pfx `
    /p "<your-password>" `
    /fd SHA256 `
    /tr "http://timestamp.digicert.com" `
    /td SHA256 `
    desktopApp\build\compose\binaries\main-release\msi\PwMgr-0.1.0.msi
```

The `/tr` (RFC 3161 timestamp server) ensures the signature stays valid after the cert expires.

Verify:

```powershell
signtool verify /pa /v desktopApp\build\compose\binaries\main-release\msi\PwMgr-0.1.0.msi
```

### 3. Install

Double-click the `.msi`. Windows SmartScreen will warn that the publisher is unverified (expected with a self-signed cert). Click "More info → Run anyway" for personal installs; for a wider audience, get an EV code-signing cert from a real CA.

---

## Browser extension — packed `.zip`

Chromium developer mode loads unpacked extensions during development. To distribute the extension you produce a `.zip`:

```powershell
cd browser-extension
npm run build
Compress-Archive -Path dist\* -DestinationPath pwmgr-extension-0.1.0.zip -Force
```

For Chrome Web Store publication you'd upload this zip plus a `manifest.json` with a Web Store-issued extension ID — out of scope for personal use.

---

## Native messaging host

The native host is produced by `:nativeHost:installDist` and lives at `nativeHost/build/install/pwmgr-native-host/`. To ship it alongside a signed MSI, add the directory to Compose Desktop's `app` resources — currently NOT done (dev-mode only). Phase polish.

---

## Reproducibility

We do not pin every transitive Gradle dependency. The version catalog (`gradle/libs.versions.toml`) pins the direct ones. For bit-for-bit reproducibility you'd also need:

- a frozen Gradle wrapper (we ship `gradle-wrapper.jar` at the committed SHA)
- a fixed JDK build (Temurin 21.0.5 is the one we test against)
- frozen AGP / Compose / Kotlin (all pinned in the version catalog)
- `org.gradle.caching=true` and `org.gradle.parallel=true` produce the same outputs given the same inputs in our experience

This is good enough for "two builds on the same machine produce identical bytes." Cross-machine reproducibility hits Compose's stamp-the-build-date behavior; not addressed in v1.
