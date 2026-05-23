# Browser extension setup (Windows)

The PwMgr browser extension fills credentials on web logins by talking to the desktop app over a local secure channel — your vault never leaves your machine.

```
Chromium/Edge ── chrome.runtime.connectNative ──▶ pwmgr-native-host.bat ──▶ TCP 127.0.0.1 ──▶ PwMgr desktop
```

Setup is a one-time ~5-minute flow.

---

## Prerequisites

- PwMgr desktop installed and running (`./gradlew :desktopApp:run` in dev mode).
- Node.js 20+ (`winget install OpenJS.NodeJS.LTS` if needed) — only required to build the extension.
- Chrome, Edge, or any other Chromium-based browser. Firefox isn't supported yet (uses a different native-messaging registration path).

## 1. Build the native messaging host

The host is a tiny Kotlin program that bridges the browser's stdio framing to the desktop app's TCP socket. It's built from the `:nativeHost` Gradle module:

```powershell
.\gradlew.bat :nativeHost:installDist
```

This produces `nativeHost/build/install/pwmgr-native-host/bin/pwmgr-native-host.bat` — the file the browser will launch.

> The desktop's `:desktopApp:run` task depends on this, so the host is rebuilt automatically every time you run the app.

## 2. Build the extension

```powershell
cd browser-extension
npm install
npm run build
```

Output: `browser-extension/dist/` with `manifest.json`, `background.js`, `content.js`, `popup.js`, `popup.html`.

## 3. Load the extension into your browser

1. Open `chrome://extensions` (or `edge://extensions`).
2. Enable **Developer mode** (top-right).
3. Click **Load unpacked** and pick `browser-extension/dist/`.
4. The extension card appears with an **ID** like `abcdefghijklmnopqrstuvwxyzabcdef` (32 lowercase letters). Copy it.

## 4. Register the native host

1. Open the PwMgr desktop app, unlock your vault.
2. Click the **extension icon** in the top bar of the vault list (next to the cloud and lock icons).
3. Paste the extension ID into the field.
4. Click **Install native host**.

What happens behind the scenes:

- A native-host JSON manifest is written to `%LOCALAPPDATA%\PwMgr\com.pwmgr.host.json` declaring the path to `pwmgr-native-host.bat` and the allowed extension origin (`chrome-extension://<your-id>/`).
- The Windows registry gets a per-user value at `HKCU\Software\Google\Chrome\NativeMessagingHosts\com.pwmgr.host` pointing to that manifest. Same for Edge.

No admin rights required — everything is under `HKCU`.

## 5. Test

1. Make sure PwMgr desktop is running and unlocked.
2. Visit a site you have credentials for in your vault.
3. A small **🔐** button appears next to the password field. Click it.
4. A dropdown shows matching entries. Click one — username + password are filled.

You can also click the PwMgr toolbar icon to see all credentials for the current host and copy the password manually.

---

## Security model

- **The extension never holds credentials.** Each fill is a fresh request → response. No background sync of secrets into browser storage.
- **The handshake token rotates on every PwMgr launch.** A stale native-host process from a previous run can't authenticate.
- **The TCP socket binds to 127.0.0.1.** Localhost only — no LAN exposure.
- **`allowed_origins` is checked by the browser.** Only the specific extension ID you registered can launch the native host.
- **Vault lock state is honored.** If you lock PwMgr, the IPC server returns `code: "locked"` and the extension shows "Vault is locked — unlock PwMgr desktop". Credentials never cross the wire while locked.

What this does NOT protect against:
- Malware running as the same Windows user can read `%LOCALAPPDATA%\PwMgr\ipc.handshake` and impersonate the extension. Same threat model as `CryptUnprotectData` — a malicious process at your user level already has full access. Defense-in-depth via a hardware-gated unlock (Windows Hello) is on the roadmap.

## Troubleshooting

**"PwMgr desktop is not running" in the popup**
The native host couldn't read `%LOCALAPPDATA%\PwMgr\ipc.handshake`. Make sure the desktop app is open. If it is, check that the file exists — if not, the app failed to start the IPC server; check the desktop console for errors.

**"Handshake failed — restart PwMgr"**
The handshake token in the file doesn't match what the server expects. Happens if PwMgr crashed without cleaning up. Restart the desktop app to regenerate.

**"No saved credentials for this site"** but you have an entry for it
The extension uses suffix-aware host matching (`accounts.google.com` matches `google.com`). If your saved URL is wrong (e.g., `https://www.google.com/path/?q=...`), the host extraction should still work. If not, edit the entry and set the URL to the bare domain.

**Extension icon doesn't appear next to password fields**
The content script detects `<input type="password">` only. If the site uses a custom component (sometimes the case with React/Vue libraries), detection fails. Click the toolbar icon and use the popup to copy the password manually.

**"reg add" fails when installing the native host**
The PwMgr app shell launches `reg.exe` via ProcessBuilder. If `reg` isn't on PATH (rare on Windows), the install errors. Manual fallback: open `regedit`, navigate to `HKCU\Software\Google\Chrome\NativeMessagingHosts`, create a key named `com.pwmgr.host`, set its default value to the path of `%LOCALAPPDATA%\PwMgr\com.pwmgr.host.json`.
