# LibreDeX

**English** · [简体中文](README.zh-CN.md)

**Native DeX desktop mode for the Galaxy Z Flip 5 — the phone Samsung left without official DeX support.**

LibreDeX brings a full desktop experience to the SM-F731B: the DeX desktop — powered by the DeX components Samsung left embedded in the One UI firmware and completed with LSPosed hooks — plus Moonlight streaming at up to 120 fps and USB-C DP/HDMI wired output with high refresh rates. Samsung never enabled DeX on this phone — LibreDeX does.

---

## Features

### Desktop mode (native DeX)
- Virtual display with the system SecondaryLauncher as a fullscreen home
- Resolution is configurable in-app — 1920×1080 by default, **4K verified** (higher untested)
- Start / restart / release desktop sessions at any time
- Change the DeX wallpaper from the app — the stock wallpaper setting inside the system DeX settings is dead, so LibreDeX ships its own
- Quick virtual touchpad — the stock DeX touchpad entry is not exposed in system settings, so a dedicated shortcut lives on the app's home page

### DeX settings (unlocked in system Settings)
- Unlocks Samsung's hidden DeX settings page in the system Settings app
- Adjust DeX resolution, font size & scaling, and whether the IME shows on the DeX screen
- **Official wireless DeX via Miracast** — cast the desktop to Miracast displays / Smart TVs wirelessly
- No in-app entry needed — just open the system Settings app and search "DeX"

### Moonlight streaming
- HEVC encoding (auto-fallback to H.264), up to **4K120** — frame rate follows the client's request
- High resolutions work but push the phone hard: expect noticeable heat at 4K120, keep an eye on thermals
- Full input relay: mouse, keyboard, and touch come back to the device
- Mute the phone speaker locally during a stream; audio stays on the client
- Live session stats (input fps / output fps) and encoder/transport settings in-app

### USB-C DP / HDMI wired output
- External display shows the DeX desktop or a mirror of the phone screen
- **High refresh rate on the external display** (e.g. 2K144) with the phone screen staying at 120 Hz while docked
- Read the external display's supported mode list; set custom resolution / DPI / refresh rate (Qualcomm `vendor.display.hdmi_cfg_idx`; re-plug the DP cable once after applying)
- Auto-stops the output when the cable is unplugged

### Screen management
- Real screen-off and black-image simulated screen-off
- Fake screen-off: during a projection session the power key only blanks the screen, it does not lock it
- Prevent auto-lock and block sleep while a session is running

### Mirror adaptation
- Auto aspect-ratio matching and auto-rotation
- Detects the inner and outer (Flip 5 cover) displays and acts on the active one

### Screen settings (experimental)
- Per-display resolution / DPI / refresh-mode / rotation controls
- The built-in screen is view-only to protect system display parameters

### Multi-language
- UI follows the system language — Simplified Chinese and English, other languages fall back to English
- In-app language switcher (Settings → Appearance → Language): Follow system / 简体中文 / English
- All UI strings are resource-based; lint gates (`HardcodedText` / `SetTextI18n`) prevent regressions

### Diagnostics & logs
- In-app log panel with one-tap export (bundles device model, OS version, app version, and LSPosed logs)
- Recent Moonlight handshake / control-input stats for remote debugging
- Logs auto-clean so they never grow unbounded

---

## Requirements

- **Galaxy Z Flip 5 / SM-F731B**
- **One UI 8 (Android 16, build F731BXXS5FZA1)** baseline — the current device-verified environment; **One UI 8.5 (Android 16 QPR2)** has also been user-verified. Developed against One UI 8 firmware; in theory works on **One UI 8+ / Android 16+**, only SM-F731B verified so far.
- **Root** (Magisk or KernelSU)
- **Shizuku** authorization
- **LSPosed** (including Vector/LSPosed); enable the LibreDeX module and reboot

---

## Installation & Usage

1. **Install** `libredex-public-release.apk` from the [Releases page](https://github.com/KanzakiK/libredex-public/releases).
2. **Enable the module**: open LSPosed, enable the LibreDeX module, scope it to `android` (system_server), `com.android.settings`, and `com.sec.android.app.launcher` (One UI Home), then **reboot the phone**.
3. **Open LibreDeX**: the setup wizard walks through Shizuku / root, overlay, audio recording, file access, and screen capture. Grant the requested permissions.
4. **Moonlight streaming**: open the connection page, start the service, then add the phone's IP in a Moonlight client and pair.
5. **DP / HDMI output**: plug in a USB-C DP cable, open the DP page and tap **Start DP output**. After changing resolution/refresh rate, re-plug the cable once as prompted.

> **First run check**: the setup wizard's first page shows *"Hook active"* only when the module is actually injected into `system_server`. If it says *"Hook not active"*, the module isn't enabled or the phone hasn't been rebooted since enabling it.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| Wizard says "Hook not active" | Module not enabled, or no reboot since enabling. Re-check LSPosed scope and reboot. |
| DP shows no signal | Make sure the cable/adapter supports DP Alt Mode; re-plug after changing output settings. |
| Stream is stuck at 60 fps | Client requested a high refresh rate but the session started at 1080P60; start a 2K120/2K144 session, or re-create the DeX session. |
| Phone freezes / reboots | Export logs from the debug panel (logcat export) and report them with the issue. |

---

## Building

Debug build:

```powershell
.\gradlew.bat :app:assembleDebug
```

Signed release build:

```powershell
.\scripts\gen-keystore.ps1
# set DEXANYWHERE_KEYSTORE / DEXANYWHERE_KEYSTORE_PASSWORD /
# DEXANYWHERE_KEY_ALIAS / DEXANYWHERE_KEY_PASSWORD from the output
.\scripts\build.ps1 -Configuration Release
```

See `scripts/signing/README.md`. The keystore is not committed; passwords come from local environment variables.

---

## License

- Distributed under **GPL-3.0**, see `LICENSE`; upstreams connect-screen.com, TNT-Anywhere and Sunshine are also GPL-3.0.
- Upstream sources and modifications are described in `NOTICE.md`.
- The current product requires root; DRM-protected content is untested.

## Disclaimer

- **DeX, One UI and Samsung are trademarks of Samsung Electronics Co., Ltd.** The DeX components and system firmware remain the property of their respective owners; LibreDeX only unlocks and extends them on your own device.
- Running the device beyond its designed thermal envelope (e.g. sustained 4K120 streaming) may cause high temperatures and potential hardware damage. **Use at your own risk.**
- Rooting and using this tool may void your warranty and carries inherent security risks. You are responsible for your own device.

---

## Docs

- Changelog: `CHANGELOG.md`
- Developer notes: `DEVELOPMENT.md`

---

*Developed with AI-assisted tooling (GitHub Copilot / Codex / DeepSeek etc.).*
