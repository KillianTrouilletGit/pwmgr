# Screenshots

Place captured screenshots here following the names used by the main README and per-feature docs.

## Naming convention

| File | What to capture | Crop ratio |
|---|---|---|
| `01-create-vault.png` | First-run create-vault screen with the strength meter showing on a half-typed password | 1280×800 max |
| `02-recovery-code.png` | The recovery-code display screen — blur the actual code with a paint tool | 1280×800 |
| `03-vault-list.png` | Vault list with ~6 entries of varying types, sync icon green, "Synced 2 min ago" visible | 1280×800 |
| `04-entry-editor.png` | Entry editor for a login with the TOTP code panel visible (use the test seed `JBSWY3DPEHPK3PXP`) | 1280×800 |
| `05-settings.png` | Settings screen with biometric toggle ON, auto-lock at 5 min | 1280×800 |
| `06-drive-setup.png` | DriveSetupScreen in the Connected state with the test email visible | 1280×800 |
| `07-browser-extension.png` | Toolbar popup with 2-3 matched credentials for the current site | 320×480 |
| `08-extension-content.png` | Login page with the inline 🔐 icon next to the password field | 1280×800 |
| `09-android-vault.png` | Android phone screenshot of the vault list | device default (1080×2400 or similar) |
| `10-android-autofill.png` | Android Chrome login page showing the "Unlock PwMgr to autofill" suggestion above the keyboard | device default |

## Capture process

**Windows**: Win+Shift+S → rectangular selection → save as PNG.
Cleanest backgrounds: light-mode theme. If using dark mode, crop tight so the surrounding desktop doesn't bleed in.

**Android**: power + volume-down screenshot, then pull via `adb pull /sdcard/Pictures/Screenshots/...`.

**Privacy hygiene**: blur any real credentials, emails, or recovery codes you captured by accident. ImageMagick one-liner if you don't have a photo editor handy:

```powershell
magick 02-recovery-code.png -region 600x80+300+400 -blur 0x12 02-recovery-code.png
```

## Resize / compression

Keep PNGs reasonable (< 300 KB each). If a capture is bigger, use:

```powershell
magick input.png -resize 1280x -strip -quality 85 output.png
```

Or [TinyPNG](https://tinypng.com/) drag-and-drop.

## Once captured

Update the main README's Screenshots section to point at `docs/images/01-create-vault.png` etc. They're referenced relatively so they work both on GitHub and on a local checkout.
