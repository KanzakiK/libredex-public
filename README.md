# LibreDeX Public

LibreDeX 让没有官方 DeX 支持的 Galaxy Z Flip 5（SM-F731B）也能使用桌面模式。本仓库是公开构建版，包含 Moonlight 串流与 USB-C DP/HDMI 有线输出。

## 功能

- fake DeX 桌面：1920x1080@60 虚拟显示，系统 SecondaryLauncher 全屏 home，可重启/释放会话、换壁纸。
- Moonlight 串流：H.264 1080P60，鼠标/键盘/触摸输入回流，手机本机可静音。
- DP/HDMI 有线输出：外接屏显示 DeX 桌面或镜像，拔线自动停止；支持读取外接屏分辨率列表与自定义分辨率/刷新率（高通 `vendor.display.hdmi_cfg_idx`，应用后需插拔 DP 线生效）。
- 屏幕管理：真实息屏、黑色图片模拟息屏、防自动锁屏、假熄屏（投屏会话中电源键只熄屏不锁屏）、阻止休眠。
- 镜像适配：自动匹配宽高比、自动旋转，识别内屏/外屏（Flip 5 cover）并按当前屏执行。

## 发布件

- `libredex-public-release.apk`：0.1.2 release 构建（arm64-v8a）。

## 环境要求

- Galaxy Z Flip 5 / SM-F731B，One UI 8 基线（当前真机验证环境）。
- Root（Magisk 或 KernelSU）。
- Shizuku 授权。
- LSPosed（含 Vector/LSPosed），启用 LibreDeX 模块并重启。
- 仅 arm64-v8a。

## 安装与使用

1. 安装 `libredex-public-release.apk`。
2. 在 LSPosed 中勾选 LibreDeX 模块，作用域覆盖 `android` 与 `system_server` 相关进程，重启手机。
3. 打开 LibreDeX，授予 Shizuku 权限与录屏权限。
4. Moonlight：首页/连接页启动服务，在 Moonlight 客户端添加手机 IP 并配对。
5. DP：插入 USB-C DP 线后进入 DP 页，点击“开始 DP 输出”；改分辨率后按提示插拔一次线。

## 构建

```powershell
.\gradlew.bat :app:assembleDebug
```

发布构建与签名：

```powershell
.\scripts\gen-keystore.ps1
# 按输出设置 DEXANYWHERE_KEYSTORE / DEXANYWHERE_KEYSTORE_PASSWORD /
# DEXANYWHERE_KEY_ALIAS / DEXANYWHERE_KEY_PASSWORD
.\scripts\build.ps1 -Configuration Release
```

详见 `scripts/signing/README.md`。keystore 不入库，密码走本机环境变量。

## 授权

- 本仓库整体按 AGPL-3.0 分发，见 `LICENSE`；GPL-3.0 上游（TNT-Anywhere / Sunshine）与 AGPL-3.0 兼容。
- 上游来源与修改说明见 `NOTICE.md`。
- 当前产品需要 root；DRM 受保护内容未测试。

## 文档

- 更新记录：`CHANGELOG.md`
