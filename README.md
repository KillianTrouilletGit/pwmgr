<div align="center">
  <img src="https://raw.githubusercontent.com/KillianTrouilletGit/pwmgr/main/androidApp/src/main/res/mipmap-xxhdpi/ic_launcher.png" alt="BlackHole Logo" width="200"/>
  <h1>BlackHole</h1>
  <p><b>A modern, open-source, cross-platform Password Manager.</b></p>
</div>

---

**BlackHole** is a sleek and highly secure password manager built entirely in Kotlin with **Compose Multiplatform**. It features zero-knowledge local encryption, seamless cross-device synchronization via Google Drive, native Android Autofill, and Windows Biometric/DPAPI unlocking.

## ✨ Features

- **🛡️ True Local Security**: Your master vault is encrypted locally with AES-GCM-256 before it ever touches the cloud.
- **☁️ Silent Cloud Sync**: Uses your personal Google Drive to seamlessly sync passwords between your phone and your PC. No third-party servers hold your data.
- **📱 Native Android Autofill**: Instantly logs you into apps and websites directly from your Android keyboard. Includes intelligent multi-language form parsing.
- **💻 Desktop Excellence**: A native Windows Desktop application with system-level DPAPI integration for seamless unlocking.
- **🎨 BlackHole Theme**: A gorgeous, true-dark immersive UI with neon red accents.

## 🚀 Installation

You can download the pre-compiled, signed binaries directly from our [Releases](../../releases) page!

### 🟢 Android
1. Go to the **Releases** tab.
2. Download `BlackHole-vX.Y.Z.apk`.
3. Open the downloaded file on your Android device to install.
4. Go to `Android Settings > Passwords & Accounts > Autofill Service` and select **BlackHole Autofill**.

### 🔵 Windows
1. Go to the **Releases** tab.
2. Download `BlackHole-vX.Y.Z.exe`.
3. Run the installer.
4. Open BlackHole from your Start Menu.

## 🛠️ Architecture

BlackHole is built with **Compose Multiplatform**.

- **`:shared:ui`**: Common Compose UI for Android and Desktop.
- **`:shared:crypto`**: Cross-platform AES-GCM encryption and PBKDF2 key derivation.
- **`:shared:storage`**: Google Drive REST API integration using Ktor for silent vault syncing.
- **`:androidApp`**: Android-specific lifecycle, BiometricPrompt, and `AutofillService`.
- **`:desktopApp`**: Desktop-specific DPAPI integration, FlatLaf dark mode title bars, and window management.

## 🤝 Contributing

Contributions are welcome! Please open an issue or submit a pull request if you have ideas for improvements.

## 📜 License

This project is licensed under the MIT License.
