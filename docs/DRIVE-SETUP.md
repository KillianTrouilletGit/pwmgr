# Drive sync setup

PwMgr stores your vault file in Google Drive's hidden **application data folder**. Google never sees the file's contents — it's still encrypted under your master password — but you do need to create a Google Cloud project to authorize PwMgr to write there.

This is a one-time setup, ~10 minutes.

---

## 1. Create a Google Cloud project

1. Open <https://console.cloud.google.com/projectcreate>.
2. Project name: anything (e.g., `pwmgr-personal`). Leave organization at "No organization" if you're using a personal Google account.
3. Click **Create**, wait ~10 s for the project to be ready.

## 2. Enable the Drive API

1. With the new project selected, go to <https://console.cloud.google.com/apis/library/drive.googleapis.com>.
2. Click **Enable**.

## 3. Configure the OAuth consent screen

1. Go to <https://console.cloud.google.com/apis/credentials/consent>.
2. User type: **External**. Click **Create**.
3. App name: `PwMgr` (or whatever you like). User support email: your email. Developer contact email: your email. Save and continue.
4. **Scopes** step: click **Add or remove scopes**, search for "drive.appdata", check the box for `https://www.googleapis.com/auth/drive.appdata`, click **Update**, then **Save and continue**.
5. **Test users** step: click **Add users** and add your own Gmail address. This is critical — without it, sign-in will fail with `Error 403: access_denied`. Save and continue.
6. Review, click **Back to dashboard**.

> Your app will stay in **Testing** mode. That's fine for personal use. Google requires verification only if you want to add other people as users. For a personal-use portfolio project, leave it in Testing.

## 4. Create OAuth credentials

1. Go to <https://console.cloud.google.com/apis/credentials>.
2. Click **Create credentials → OAuth client ID**.
3. Application type: **Desktop app**.
4. Name: `PwMgr Desktop`.
5. Click **Create**.
6. A dialog shows your **Client ID** and **Client secret**. Copy both.

## 5. Drop the credentials into PwMgr

PwMgr reads OAuth credentials from a JSON file alongside your vault.

**Windows path:** `%LOCALAPPDATA%\PwMgr\drive-config.json` (typically `C:\Users\<YOU>\AppData\Local\PwMgr\drive-config.json`).

Create that file with these contents (paste in your real values):

```json
{
  "clientId": "1234567890-abcdefghijklmnop.apps.googleusercontent.com",
  "clientSecret": "GOCSPX-xxxxxxxxxxxxxxxxxxxxxx"
}
```

> "Client secret" for Desktop OAuth clients isn't actually secret — it's embedded in every distributed copy of an open-source desktop app. Google's spec calls it that anyway. The real security against authorization code interception comes from **PKCE**, which PwMgr always uses. Don't worry about checking this file into a public repo by mistake (it's already in `.gitignore`), but also don't go out of your way to protect it.

## 6. Connect from PwMgr

1. Launch PwMgr (`.\gradlew.bat :desktopApp:run`).
2. Unlock your vault.
3. Click the **cloud** icon in the top bar → **Connect Google Drive**.
4. Your default browser opens with the Google sign-in prompt. Pick the same email you added as a test user in step 3.
5. Google warns "Google hasn't verified this app" — that's expected; click **Continue**, then **Allow** to grant the `drive.appdata` permission.
6. The browser shows "You can close this tab". Back in PwMgr, the sync icon turns into a green checkmark.

From now on, every save triggers a sync (debounced 2 s). The vault is uploaded as a single opaque file named `vault.enc` in Drive's `appDataFolder`. You won't see it in your Drive web UI — that's intentional.

---

## 7. Set up Drive sync on Android (same OAuth client)

Android reuses the **same Desktop OAuth client** you created above. No new GCP credentials needed — Google accepts `http://127.0.0.1:PORT` as the redirect URI for Desktop clients, and Android's Chrome can follow a redirect to a loopback port bound by PwMgr.

To enable sync on the Android app, you need to drop the same `drive-config.json` into the app's private files directory: `/data/data/com.pwmgr.android/files/drive-config.json`.

### Via adb (the techie way)

```powershell
# 1. Connect your device with USB debugging enabled.
adb push "$env:LOCALAPPDATA\PwMgr\drive-config.json" /sdcard/Download/drive-config.json

# 2. Push it into the app's private dir (needs run-as for non-rooted devices):
adb shell run-as com.pwmgr.android cp /sdcard/Download/drive-config.json files/drive-config.json
adb shell rm /sdcard/Download/drive-config.json

# 3. Force-stop and relaunch the app so it picks up the config.
adb shell am force-stop com.pwmgr.android
adb shell am start -n com.pwmgr.android/.MainActivity
```

`run-as` only works on **debuggable** builds. For a release APK signed with your own keystore, the same approach works as long as `android:debuggable="true"` was set OR you use a rooted device with `adb root`.

### After setup

1. Open PwMgr on Android, unlock your vault.
2. A cloud icon appears in the top bar (same as desktop).
3. Tap → **Connect Google Drive**.
4. Chrome Custom Tab opens with the Google sign-in. Pick the same email you added as a test user.
5. Approve `drive.appdata` scope.
6. The tab redirects to `http://127.0.0.1:NNNN/callback?code=…` and Chrome displays "You can close this tab" — return to PwMgr.

Your phone and desktop now share the same encrypted vault via Drive's hidden `appDataFolder`. Edits on one device sync to the other within ~2 seconds of save (the same debounced sync as desktop).

### Android limitations

- **Chrome required.** Custom Tabs need Chrome (or another Chromium-based browser) as the default browser. If only Firefox is installed, the auth URL opens in a regular browser tab — still works, just less seamless.
- **Loopback redirect on locked-down networks.** Some corporate WiFi / public APs block HTTP→localhost redirects. If the auth flow hangs at "you can close this tab" but PwMgr never gets the code, switch to mobile data and try again.
- **Same master password required.** Phase 5b sync assumes both devices use the same master password. If you change it on desktop, you'll need to re-enter it on Android the next time it pulls.

## Troubleshooting

**"Error 403: access_denied"** — your Gmail isn't in the test-users list. Go back to step 3.5.

**The browser opens but the callback never returns** — your firewall is blocking the local HTTP loopback. Allow incoming connections on a random localhost port for `java.exe`. Once-only prompt on most Windows firewalls.

**"Sync failed: HTTP 401"** — the refresh token was revoked. Disconnect Drive in PwMgr and reconnect.

**Vault on device A doesn't show on device B** — both devices need to use the **same master password**. Sync uses the vault key in memory, which only matches if MK→VK derivation matches. Different passwords → "remote vault is corrupted" type errors. Phase 6 (biometric) won't change this; Phase 9 polish will surface a clearer error.
