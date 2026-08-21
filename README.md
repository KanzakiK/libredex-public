# LibreDeX

**English** · [简体中文](README.zh-CN.md)

**Native DeX desktop mode for the Galaxy Flip series — Samsung never shipped wired DeX on any Flip (the Flip 7 got wireless only).**

LibreDeX brings a full desktop experience to One UI 8 Flip phones: the DeX desktop — powered by the DeX components Samsung left embedded in the One UI firmware and completed with LSPosed hooks — plus Moonlight streaming at up to 120 fps and USB-C DP/HDMI wired output with high refresh rates. Samsung never enabled wired DeX on the Flip line — LibreDeX does. Verified on the Z Flip 5 (SM-F731B).

---

## Features

### Beyond stock DeX — what stock DeX can't do

- **Up to 4K120 Moonlight streaming** — HEVC (auto-fallback to H.264), frame rate follows the client's request; full mouse / keyboard / touch input relay, local speaker mute, live session stats. ⚠️ Runs hot at 4K120, watch thermals.
- **High refresh on external displays** — up to 2K144 over DP/HDMI, while the phone screen holds 120 Hz when docked (stock DeX caps the external panel at 60 Hz).
- **Screen-off extras** — real / black-image simulated screen-off, fake screen-off (power key never locks mid-session), sleep blocking.
- **Refresh-rate unlock hooks** — the phone stays at 120 Hz even when a DeX session would drag it down to 60.

### Stock DeX parity — the full DeX experience on the Flip 5

- **Desktop mode** — virtual display with the system SecondaryLauncher as home; resolution configurable in-app (1920×1080 default, **4K verified**); wallpaper picker & virtual touchpad included (the stock entries are dead / missing on this device).
- **Wireless DeX (Smart View / Miracast)** — the same screen-casting path stock DeX uses, dormant in the firmware and unlocked: cast the desktop to Miracast displays / Smart TVs.
- **Wired output (DP / HDMI)** — DeX desktop or phone mirror on an external display; 4K output with custom resolution / DPI / refresh rate (Qualcomm `vendor.display.hdmi_cfg_idx`, re-plug once after applying); auto-stops on unplug.
- **DeX settings page unlocked in system Settings** — resolution, font size & scaling, IME-on-DeX-screen toggle; just search "DeX".
- **Screen settings (experimental)** — per-display resolution / DPI / refresh-mode / rotation controls (built-in screen is view-only).
- **Mirror adaptation** — auto aspect-ratio matching & auto-rotation; inner / outer (cover) display aware.
- **Multi-language** — zh-CN / English UI with in-app language switcher; English runtime logs.

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
