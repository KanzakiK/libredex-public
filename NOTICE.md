# NOTICE

LibreDeX (application id `com.libredex`) is a combined and modified work
distributed under GPL-3.0. The full license text is in `LICENSE`. The
upstream components (connect-screen.com, TNT-Anywhere, Sunshine) are also
GPL-3.0 and are compatible with this combined distribution.

## Upstreams

- connect-screen.com
  - Source: https://github.com/taowen/connect-screen.com
  - License: GPL-3.0
  - The root upstream of this project: the original "connect screen to
    external display" project by taowen. TNT-Anywhere (below) derives from
    it; LibreDeX inherits its architecture and screen-projection approach.

- TNT-Anywhere
  - Source: https://github.com/KanzakiK/TNT-Anywhere.git
  - License: GPL-3.0
  - Used as the base for the Android app, Sunshine host integration,
    `UserService`, input injection, audio capture, and screen management.
  - Modifications: monorepo consolidation, package rename to `com.libredex`,
    removal of Smartisan/DisplayLink/TNT/USB/XR paths, One UI 8 style
    frontend, 1080P60 frame pacing, audio loopback regression fixes, and
    residual `UserService`/`AudioPolicy` cleanup.

- Sunshine
  - Upstream: LizardByte Sunshine (GPL-3.0) and the Android fork carried by
    TNT-Anywhere.
  - Prebuilt native libraries under `app/src/main/jniLibs`:
    `libsunshine.so`, `libssl.so`, `libcrypto.so`, `libXlorie.so`.
  - The prebuilt `.so` files come from the archived TNT-Anywhere
    `prototype/dex-anywhere-sunshine` tree; no local source build is
    maintained in this repository. See `native/README.md`.
  - Modifications: the binaries are unmodified; the integration contract is
    implemented by `com.connect_screen.mirror.job.SunshineServer`.

- android-change-resolution
  - Source: https://github.com/taowen/android-change-resolution.git
  - License: MIT
  - Used for the Qualcomm external display mode probe and the
    `vendor.display.hdmi_cfg_idx` mode override flow (selector from DRM mode
    flags, cable replug required).
  - Bundled artifacts:
    - `app/src/main/assets/native/arm64-v8a/qti-display-probe` (prebuilt CLI,
      verified on Galaxy Z Flip 5)
    - `third_party/android-change-resolution/qti_display_probe.cpp` (source)

## Prebuilt library checksums

`arm64-v8a`:

- `libsunshine.so`: `AD6ED8C317AC8E26F7AA937CCC1EB0867F22492C82DDAD6905EF88E11689E08B`
- `libssl.so`: `17928D4992DB1606F5A016DCF4933CBC1D74C1C1B8F370C9AC9F018DF5CF524D`
- `libcrypto.so`: `84284CE9A6BACAE004AA727F817AFB50236478B8CAEF85DB4501173E7286FBB7`
- `libXlorie.so`: `93D13CF746A6576066360BD40DB4EB131E2D96F84D129FD0B7E97940BED3477A`

`armeabi-v7a`:

- `libsunshine.so`: `FB93E4911EBD0F5AA1F47B74B6075982493FA60671821BB82DEE872237B4F607`
- `libssl.so`: `E1D39B5246C60597075E50AFD5AA58853AA3DA5CA36032FB0ED0172A5C559C20`
- `libcrypto.so`: `94BD64141C7BB983996D3C4D20CB72A7AEFFA49C3058ECBEA75507AF68E6703C`
- `libXlorie.so`: `8950636DBC4630A2879BA38C0456D8F9A9D0ADAE3427352EB927D72CD7FE8E92`

Prebuilt tool (`arm64-v8a`):

- `assets/native/arm64-v8a/qti-display-probe`:
  `36A31E95D9CA16A845F2FDCDAC9C878D5F279F614141D8D70C16AD8EB3E4BC63`

## Third-party Java/Kotlin dependencies

Dependency coordinates and versions are declared in `app/build.gradle`,
`gradle/libs.versions.toml`, and the Gradle dependency metadata. Each library
is used under its own license as declared by its upstream.

## Source availability

The complete source of this modified work is this repository. Upstream
sources are linked above. Per GPL-3.0, anyone who receives the program or a
derived binary may request the corresponding source, which is offered here.
