# LibreDeX

[English](README.md) · **简体中文**

**原生 DeX 桌面模式，给 Galaxy Z Flip 5——三星没有给这台手机官方 DeX 支持。**

LibreDeX 为 SM-F731B 带来完整的桌面体验：基于 One UI 固件中预埋的 DeX 组件（经 LSPosed Hook 补全）实现的 DeX 桌面，加上最高 120fps 的 Moonlight 串流、支持高刷新率的 USB-C DP/HDMI 有线输出。三星没有为这台手机开启 DeX——LibreDeX 来开。

---

## 功能

### 超越官方 DeX——官方 DeX 做不到的

- **最高 4K120 Moonlight 串流**——HEVC 编码（失败自动回退 H.264），帧率跟随客户端请求；完整鼠标 / 键盘 / 触摸输入回流、本机静音、实时会话统计。⚠️ 4K120 下发热明显，注意散热。
- **外接屏高刷新率**——DP/HDMI 输出最高 2K144，插拓展坞时手机屏幕保持 120Hz（官方 DeX 外接屏锁 60Hz）。
- **熄屏增强**——真实息屏 / 黑色图片模拟息屏、假熄屏（会话中电源键永不锁屏）、阻止休眠。
- **高刷新率解锁 hook**——即使 DeX 会话想把屏幕拖到 60Hz，手机依然保持 120Hz。
- **诊断与日志**——应用内日志面板一键导出（自动附带设备型号 / 系统版本 / App 版本与 LSPosed 日志）、Moonlight 握手与控制输入统计、日志自动清理。

### 官方 DeX 同级——在 Flip 5 上补齐完整 DeX 体验

- **桌面模式**——虚拟显示，系统 SecondaryLauncher 作为桌面；分辨率可在 App 内配置（默认 1920×1080，**4K 已验证**）；内置壁纸选择器与虚拟触控板（本机上官方入口已失效 / 缺失）。
- **无线 DeX（Miracast）**——固件中休眠的组件被解锁：无线投屏到支持 Miracast 的显示器 / 智能电视。
- **有线输出（DP / HDMI）**——外接屏显示 DeX 桌面或手机镜像；支持 4K 输出与自定义分辨率 / DPI / 刷新率（高通 `vendor.display.hdmi_cfg_idx`，应用后插拔一次线）；拔线自动停止。
- **系统设置中的 DeX 设置页被解锁**——分辨率、字体大小与缩放、输入法是否显示在 DeX 屏幕；搜索「DeX」即可。
- **屏幕设置（实验性）**——每个屏幕的分辨率 / DPI / 刷新模式 / 旋转控制（内置屏幕仅允许查看）。
- **镜像适配**——自动匹配宽高比、自动旋转；识别内屏 / 外屏（Flip 5 外屏）。
- **多语言**——简体中文 / English 界面，App 内可切换语言；运行日志为英文。

---

## 环境要求

- **Galaxy Z Flip 5 / SM-F731B**
- **One UI 8（Android 16，编译版本 F731BXXS5FZA1）** 基线——当前真机验证环境；**One UI 8.5（Android 16 QPR2）** 也已有用户验证。基于 One UI 8 固件研究开发，理论上 **One UI 8+ / Android 16+** 可用，目前仅 SM-F731B 经过验证。
- **Root**（Magisk 或 KernelSU）
- **Shizuku** 授权
- **LSPosed**（含 Vector/LSPosed）；启用 LibreDeX 模块并重启

---

## 安装与使用

1. **安装**：从 [Releases 页面](https://github.com/KanzakiK/libredex-public/releases) 下载 `libredex-public-release.apk` 安装。
2. **启用模块**：打开 LSPosed，启用 LibreDeX 模块，作用域勾选 `android`（system_server）、`com.android.settings`、`com.sec.android.app.launcher`（One UI 桌面），然后**重启手机**。
3. **打开 LibreDeX**：设置向导会带你完成 Shizuku / root、悬浮窗、录音、文件访问、录屏等授权，按提示授予即可。
4. **Moonlight 串流**：打开连接页启动服务，在 Moonlight 客户端添加手机 IP 并配对。
5. **DP / HDMI 输出**：插入 USB-C DP 线后进入 DP 页，点击「开始 DP 输出」；修改分辨率 / 刷新率后按提示插拔一次线。

> **首次运行自检**：设置向导第一页显示「已检测到框架活跃」才表示模块真正注入到 `system_server`。如果显示「Hook 尚未生效」，说明模块未启用或启用后没重启。

---

## 常见问题

| 现象 | 解决 |
|---|---|
| 向导显示「Hook 尚未生效」 | 模块没启用，或启用后没重启。检查 LSPosed 作用域后重启。 |
| DP 无信号 | 确认线材 / 扩展坞支持 DP Alt Mode；修改输出设置后重新插拔。 |
| 串流卡在 60fps | 客户端请求了高刷新率但会话以 1080P60 启动；改用 2K120/2K144 会话，或重建 DeX 会话。 |
| 手机卡死 / 自动重启 | 请从调试面板导出日志（logcat），反馈时一并附上。 |

---

## 构建

调试包：

```powershell
.\gradlew.bat :app:assembleDebug
```

签名发布包：

```powershell
.\scripts\gen-keystore.ps1
# 按输出设置 DEXANYWHERE_KEYSTORE / DEXANYWHERE_KEYSTORE_PASSWORD /
# DEXANYWHERE_KEY_ALIAS / DEXANYWHERE_KEY_PASSWORD
.\scripts\build.ps1 -Configuration Release
```

详见 `scripts/signing/README.md`。keystore 不入库，密码走本机环境变量。

---

## 授权

- 本仓库整体按 **GPL-3.0** 分发，见 `LICENSE`；上游 connect-screen.com、TNT-Anywhere 与 Sunshine 同为 GPL-3.0。
- 上游来源与修改说明见 `NOTICE.md`。
- 当前产品需要 root；DRM 受保护内容未测试。

## 免责声明

- **DeX、One UI 与 Samsung 均为三星电子有限公司的商标。** DeX 组件与系统固件归其各自权利人所有；LibreDeX 仅在你自己的设备上解锁并扩展它们。
- 超出设备设计热规格使用（如持续 4K120 串流）可能导致高温，存在硬件损坏风险。**请自行承担使用风险。**
- Root 与使用本工具可能使保修失效，并带有固有安全风险。请自行对设备负责。

---

## 文档

- 更新记录：`CHANGELOG.md`
- 开发说明：`DEVELOPMENT.md`

---

*本项目使用 AI 辅助工具开发（GitHub Copilot / Codex 等）。*
