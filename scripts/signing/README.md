# 签名与版本

- release 使用自有 keystore；keystore 不入库，密码走本机环境变量。
- 生成：`.\scripts\gen-keystore.ps1`，按提示设置环境变量：
  `DEXANYWHERE_KEYSTORE`、`DEXANYWHERE_KEYSTORE_PASSWORD`、`DEXANYWHERE_KEY_ALIAS`、`DEXANYWHERE_KEY_PASSWORD`。
- 默认生成 `%LOCALAPPDATA%\libredex\libredex-release.keystore`，alias `libredex`，DN `CN=LibreDeX,O=LibreDeX,C=CN`；可用 `-KeyAlias` / `-OutDir` / `-DName` 覆盖。
- 版本号统一入口：`scripts/build.ps1` 的 `-VersionName` / `-VersionCode` 默认值，一次传入 Gradle，APK 与 BuildConfig 自动同步。
- 构建：`.\scripts\build.ps1 -Configuration Debug` 或 `-Configuration Release`；`-Install` 可选 adb 安装。

## 正式发布的签名 key（务必确认）

**当前线上/历史 release（0.1.1 → 0.1.9）以及 0.1.10 都使用同一个签名 key，保证老用户能覆盖升级、不丢数据。** 该 key 位于：

```
%LOCALAPPDATA%\libredex\libredex-release-0.1.1.keystore   (alias=libredex)
```

构建发布前，把 `DEXANYWHERE_KEYSTORE` 指向它（不要指向下面那个 unused 文件）：

```powershell
$env:DEXANYWHERE_KEYSTORE="$env:LOCALAPPDATA\libredex\libredex-release-0.1.1.keystore"
$env:DEXANYWHERE_KEYSTORE_PASSWORD="<见本目录/环境快照中的值>"
$env:DEXANYWHERE_KEY_ALIAS="libredex"
$env:DEXANYWHERE_KEY_PASSWORD="<见本目录/环境快照中的值>"
```

> 密码不写进仓库；仅通过环境变量注入。构建机本地 `%LOCALAPPDATA%\libredex\signing-0.1.1.env.txt` 保留过一次明文快照，注意用完后清理。

### 为什么不要换 keystore

- `%LOCALAPPDATA%\libredex\libredex-release.keystore`（生成于 2026-08-03）是一个**不同的 key、不同的密码**，从未用于正式发布。
- 如果用它签名 0.1.10，签名与已装用户的 0.1.x 不一致 → 用户必须**卸载重装**、丢失应用数据，甚至被判为不同应用。
- 因此除非你明确要“换新 key 并接受老用户卸载”，否则**正式发布一律用 `libredex-release-0.1.1.keystore`**，那个 08-03 的已改名归档为 `libredex-release.unused-2026-08-03.keystore` 以避免误用。

