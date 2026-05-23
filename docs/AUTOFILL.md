# Android autofill

PwMgr exposes an `AutofillService` to Android. Once enabled, the system shows PwMgr's "Unlock to autofill" suggestion above any login form — in apps and in browsers — and routes the fill through a biometric prompt + entry picker.

---

## Enable PwMgr as your autofill provider

There's no programmatic way for an app to make itself the system autofill provider — only the user can pick. Once:

1. Open **Settings** on your phone.
2. **Passwords, passkeys & autofill** (Pixel/AOSP wording) or **Passwords & accounts → Autofill service** (Samsung / some OEMs) or **System → Languages & input → Advanced → Autofill service**.
3. Tap **Default autofill service** (or **Add service** depending on OEM).
4. Pick **PwMgr**.
5. Accept the system warning that PwMgr "will be able to see anything that's currently on the screen". This is how Android phrases the autofill capability — PwMgr only inspects login fields and never logs anything off-device.

You can confirm it's active by long-pressing inside a login field in any app: the keyboard's overlay menu should show "Autofill → PwMgr".

## How a fill works

1. Android detects a login form on screen and calls `PwMgrAutofillService.onFillRequest`.
2. We parse the form (autofill hints first, then input types, then HTML attributes, then resource ids/hints) to find the username + password fields and the web domain (for browsers).
3. We return a single suggestion: **"Unlock PwMgr to autofill"**.
4. Tapping it opens `AutofillUnlockActivity`, which:
   - Triggers `BiometricPrompt` automatically if biometric unlock is enrolled (Phase 6).
   - Otherwise shows a master-password field.
5. Once the vault is unlocked, we match entries against the form's web domain (for browsers, suffix-aware: `accounts.google.com` matches an entry saved as `google.com`) or against the calling app's package name (token-based: `com.github.android` matches an entry titled "GitHub").
6. The matched entries are returned to the system as `Dataset`s. Tapping one fills both fields.

## What's intentionally NOT in v1

- **Save on submit.** PwMgr's `onSaveRequest` ack's silently. You add entries manually from the app. Capture-on-save lands in a future polish pass.
- **In-app "Enable autofill" banner.** The Settings screen will host this in Phase 9. For now the user navigates through system settings as described above.
- **Inline suggestions over the keyboard.** Supported via `InlinePresentation` since Android 11 but adds a layer of complexity (theming, sizing). v1 uses the older drop-down suggestions only.

## Cross-domain safety

The matcher refuses suffix-without-dot collisions: a saved `google.com` entry will **not** match a form on `evilgoogle.com`. See `EntryMatcherTest.cross_domain_does_not_match` for the regression test.

## Testing

```powershell
.\gradlew.bat :androidApp:testDebugUnitTest
```

Runs `EntryMatcherTest` on the JVM (no device required). End-to-end testing on a device or emulator is manual: install, enable in system settings, open a browser, sign in to a known site.
