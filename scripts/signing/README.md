# 签名与版本

- release 使用自有 keystore；keystore 不入库，密码走本机环境变量。
- 生成：`.\scripts\gen-keystore.ps1`，按提示设置环境变量：
  `DEXANYWHERE_KEYSTORE`、`DEXANYWHERE_KEYSTORE_PASSWORD`、`DEXANYWHERE_KEY_ALIAS`、`DEXANYWHERE_KEY_PASSWORD`。
- 默认生成 `%LOCALAPPDATA%\libredex\libredex-release.keystore`，alias `libredex`，DN `CN=LibreDeX,O=LibreDeX,C=CN`；可用 `-KeyAlias` / `-OutDir` / `-DName` 覆盖。
- 版本号统一入口：`app/build.gradle` 的 `versionCode` / `versionName`（当前 1 / 0.1.0）。
- 构建：`.\scripts\build.ps1 -Configuration Debug` 或 `-Configuration Release`；`-Install` 可选 adb 安装。
