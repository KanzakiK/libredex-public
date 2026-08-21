# LibreDeX 更新记录 / Changelog

## 0.2.6（2026-08-21）

> 从本版本起支持多语言，以下分中英两段 / Bilingual since this release.

### English

**New Features**

- **Multi-language support**: UI follows the system language — Simplified Chinese, English, others fall back to English. All UI strings are resource-based with English translations.
- **In-app language switcher**: Settings → Appearance → Language (Follow system / 简体中文 / English); applies instantly and persists.
- **i18n regression gate**: HardcodedText / SetTextI18n are lint errors; `scripts/check_i18n_consistency.py` validates en/zh resource parity.
- **English logs**: all runtime logs are now in English.
- **Setup wizard redesign**: card-based design matching the main UI, rebalanced pages; fixed "Open LSPosed" failing on fresh installs (UserService not bound); hook-active detection now reads a self-reported system property.

**Bug Fixes**

- **Fix random freezes & auto-reboots**: the refresh-rate hook queried display info synchronously on system_server hot paths, deadlocking with system locks and tripping the watchdog; probing now runs on background threads so hook callbacks never hold or wait for system locks.
- Fixed wizard crash (uninitialized LSPosed status view).
- Fixed English text truncation on fixed-width buttons.

### 简体中文

**新功能**

- **多语言支持**：界面语言跟随系统——中文设备显示简体中文，英文设备显示 English，其他语言回退英文。全部 UI 字符串（布局、按钮、对话框、提示、通知、引导向导、关于页）已资源化并附带英文翻译。
- **App 内语言切换**：设置 → 外观 → 语言，可手动选择 跟随系统 / 简体中文 / English，即时生效并持久化。
- **国际化防回归**：新增 lint 门禁（HardcodedText / SetTextI18n 为 error），以后新增界面文案必须走字符串资源，硬编码无法通过构建检查；`scripts/check_i18n_consistency.py` 可校验中英资源一致性。
- **日志英文化**：全部运行日志（任务/授权/音频/输入/投屏状态）改为英文，方便海外极客用户反馈问题。
- **设置向导重构**：改为与主界面一致的卡片式设计，四个页面内容重新平衡；修复全新安装时「打开 LSPosed」无法拉起（UserService 未绑定）的问题；LSPosed 生效检测改为读取 hook 自报的系统属性，结果准确且不再依赖日志文件。

**问题修复**

- **修复手机偶发卡死自动重启**：高刷新率 hook 在 system_server 热路径中同步查询显示信息，与系统锁形成交叉死锁导致 watchdog 杀 system_server；已改为后台线程异步探测，hook 回调不再持有/等待任何系统锁。
- 修复设置向导闪退（LSPosed 状态控件未初始化）。
- 修复个别英文文案在固定宽度按钮上被截断的问题（编码器设置页的「恢复默认 / 保存」按钮改为自适应宽度）。

## 0.2.5（2026-08-21）

### 新功能

- **串流高刷新率**：Moonlight 投屏（DeX 和镜像模式）现在可以跑到 120fps，画面更跟手更流畅；帧率跟随客户端请求（2K120 / 2K144 等均可）。
- **外接屏（DP / HDMI）输出**：刷新率跟随外接屏信号，支持高刷新率输出（如 2K144），DeX 桌面在外接屏上同样流畅。
- **插拓展坞时手机屏幕保持 120Hz**：不再被系统压到 60Hz。
- **镜像模式页面补全**：镜像投屏时也能看到「输入帧率 / 输出帧率」等实时参数卡片，以及客户端、传输、查看日志等设置入口；启动按钮统一为「启动服务」。
- **屏幕设置（实验）入口**：镜像模式下也能进入外接屏分辨率 / DPI / 刷新率设置。
- **「以 root 重启 Shizuku」修复**：现在能真正以 root 运行 Shizuku，外接屏模式设置不再失败。
- **版本号带构建标识**：关于页与日志会显示版本 + 构建号（commit），反馈问题时更容易定位版本。
- **日志自动清理**：日志不会再无限积累，超过保留规则自动清理。

### 问题修复

- 修复 DeX 投屏切到镜像后鼠标无法操控的问题。
- 修复「外接屏模式应用失败」的问题（高通的显示探测工具现在能正常工作）。
- 修复 DP 输出 DeX 时打开 App 变成全屏（应为 DeX 窗口）的问题。
- 修复安装新版本后旧代码残留（Shizuku 服务进程不重启）导致功能异常的问题。
- 修复镜像模式下打开「编码与传输 / 音频与输入」设置时页面跳转位置不对的问题。

## 0.2.0（2026-08-19）

### 新功能

- 新增「首次使用引导向导」：打开 App 会用 4 步引导帮你把运行所需的环境一次性配好——自动检测并跳转到 LSPosed 模块、申请 Root / Shizuku 权限、配置音频、以及文件与录屏授权。向导是非阻塞的，不强制你立刻完成；打开 App 时会自动尝试获取 Shizuku，设置页也会实时显示各项权限的状态。
- 把原来散落在设置页里的一堆「授权」按钮（Shizuku、音频、悬浮窗、文件访问、录屏）统一收进了引导向导，设置页现在只保留状态显示和 Root 相关入口，界面更清爽。

### 问题修复

- 修好了引导向导里「Shizuku」那一栏会闪退的问题（授权按钮被重复放置导致崩溃）。
- 改进了 LSPosed 模块的检测与跳转：现在能匹配多个入口；如果没检测到安装，会明确提示你从桌面或通知栏手动打开 LSPosed / Vector 框架。
- 改进了 LSPosed / Vector 框架入口的识别逻辑：改为根据实际能否启动来判断，不再误报「未安装」；后台启动流程改用 Root（su）自动探测手机是否已 Root，不再依赖 Shizuku 是否在线。

### 其他改进

- 「以 Root 身份重启 Shizuku」的提示已做本地化（中文）。

## 0.1.13（2026-08-19）

### 问题修复

- 切换深色 / 浅色主题后，界面现在会立刻跟着变化，不用退出 App 再重新打开。

## 0.1.12（2026-08-19）

### 问题修复（针对最新系统 One UI 8.5 的卡死问题）

- 修好了在最新系统（One UI 8.5 / Android 16）上桌面模式会卡死、手机发烫甚至整机卡顿的问题：现在能正确识别桌面环境，不再拖垮手机性能。
- 修好了壁纸加载异常时，手机会陷入无限重试、CPU 占满导致桌面卡死的问题。
- 提升了桌面窗口层级调整的兼容性，减少在新型号 / 新系统上意外崩溃的情况。

## 0.1.11（2026-08-19）

- 0.1.11 只是一个过渡版本号，没有单独的功能改动，实际修复已包含在 0.1.12 中。

## 0.1.10（2026-08-19）

### 新功能

- 日志导出更完善了：每一份日志都会自动带上你的设备型号、系统版本和 LibreDeX 版本号，排查问题时一眼就能对应；同时把 LSPosed 的运行日志也一起打包，方便定位模块加载问题。

### 其他改进

- 外接屏（DP / HDMI）现在默认按你选择的外接屏模式来渲染；如果没有手动选择，则统一按 1080P 渲染，避免接 4K 屏时手机被拖累而卡顿。之后仍可以自定义分辨率和刷新率。
- 统一了正式版的签名，保证已经安装旧版的用户可以无缝覆盖升级，不会丢失数据。

## 0.1.9（2026-08-17）

### 问题修复

- 更新了「关于」页面：项目地址改为公开仓库，许可证说明更正为 GPL-3.0，更新记录的措辞也更规范了。

## 0.1.8（2026-08-16）

### 问题修复

- 修好了用「可选投屏」方式启动后，应用停留在后台、桌面抢走前台的问题：现在启动后会自动把 LibreDeX 拉回手机屏幕。
- 统一了「可选投屏」「Moonlight」「DP」三个入口的按钮样式（高度、字号、大小写保持一致），界面更整齐。

## 0.1.6（2026-08-16）

### 问题修复

- 统一了各投屏入口的按钮样式，看起来更一致。

## 0.1.5（2026-08-16）

> 0.1.3 / 0.1.4 为中间测试版本，改动已合并到 0.1.5。

### 新功能

- 同一时间只跑一种输出方式（无线串流 / 有线外接屏）：当一种正在用时，另一种会显示「停止服务」或不可选择，避免多个画面源冲突导致闪屏或黑屏。
- 日志导出改成 ZIP 压缩包：一键导出会自动附带设备信息和运行环境摘要，发给开发者排查更方便；本机还会自动保留最近 5 份。
- 设置页新增了后台服务的状态显示和「一键重启」入口，遇到服务异常时可以自己恢复，不用重装 App。

### 问题修复

- 修好了停止有线输出后，外接屏卡在最后一帧、黑屏或切不回手机画面的问题，现在会正常回到实时手机镜像。
- 修好了在桌面模式和纯镜像之间切换时，画面叠加、镜像层变黑的问题。
- 修好了停止输出时外接屏一直闪烁、像在不断重试的问题。
- 修好了结束输出、手机分辨率恢复时，主界面闪一下、被重新打开的问题。
- 修好了日志导不出来、提示「无法获取日志目录」的问题。
- 修好了串流服务（Sunshine）停止后状态卡住、按钮点不动的问题。

### 其他改进

- 去掉了「开机自启」选项，安装或重启手机后不会自动启动串流服务，更省心也更安全。

---

注：0.1.7 未在更新记录中单独列出（为内部构建版本）。

## 0.1.2（2026-08-15）

### 新增

- 输出链路统一抽象：`DEX / 纯镜像 × Moonlight / DP` 输出通道共用同一份画面源、镜像变换与会话生命周期逻辑；运行时按当前手机屏（内屏 / Flip 5 cover）适配。
- 纯镜像输出统一应用自动旋转、自动缩放去黑边、自动匹配宽高比。
- DP DeX 输出支持应用自由窗口（freeform），并按“DeX / 纯镜像”源选择正确启动对应会话。
- HEVC 编码器初始化失败时自动回退到 H.264/AVC，仅影响当前会话，下次服务启动重新尝试 HEVC。
- Sunshine 服务增加重复启动与初始化竞争守卫，降低“无法配置编码器”概率。
- fake screen 会话标记跨 UserService 重连保持，避免会话中按电源键退化为系统锁屏。

### 修复

- 修复纯镜像 + DP 未应用自动旋转、自动缩放去黑边、自动匹配宽高比的问题。
- 修复 DP 输出忽略画面源选择、纯镜像变成 DeX 的问题。
- 修复 DP 停止后外接屏 layerStack/投影未恢复的问题。
- 修复重复启动 Sunshine 导致编码器冲突的问题。
- 修复 UserService 重连把 `session_active` 清零导致假熄屏失效的问题。

### 其他

- 公开仓库发布流程整理。
