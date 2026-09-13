# Android 16 (API 36) WMS 反射实战记录

> 设备：**三星 Galaxy Z Flip5**（折叠态，Cover Screen=display 1 活跃，内屏=display 0 休眠）
> ROM：OneUI 8.0（基于 Android 16）
> 背景：Flip5 官方不支持 Dex 模式，本项目（LibreDeX）是补全模块，通过 Xposed hook WMS + 创建 mirror display 来实现 Dex 功能
> 模块：`DexLayerStackHook.java` — Dex 模式 DP 输出的 WMS 层级操作

---

## 1. 问题背景

停止 Dex 模式的 DP 输出后，**Dex 相关的 Task 和壁纸根本没被清理掉**——SecondaryLauncher RootTask、子 Task、ActivityRecord、WallpaperWindowToken 全都还活着，只是因为 z-order 比镜像画面低所以暂时看不见。

**为什么这是 bug**：
- 消耗 WMS 资源（每个 Task/ActivityRecord/WindowState 都有对象 + Surface 内存）
- WMS 状态不一致：display 已经销毁但上面的 Task 还在
- 切换场景时可能冒出来（比如镜像 display 被销毁时 z-order 重新计算）
- 下次启动 Dex 可能出现两层 Task 叠加

**根因**：Dex 使用的是 **物理 DP/HDMI 输出屏幕**（displayId ≥ 6），停止 DP 时 WMS 不会自动清理这些 display 上的 Task，它们会迁移到下一个存活的 display 但保持 invisible=true。

### 本设备的 display ID 分布（fix35 测试快照，**不固定**！）

⚠️ **重要**：Android 的 displayId 是 WMS 按创建顺序**动态分配**的，不是固定映射！每次 DP 插拔、重启、display 销毁重建后 ID 都会变。以下仅为 fix35 那次测试时的快照：

| 那次的 displayId | DisplayInfo 名称 | 类型 | 识别方式 |
|-----------|-----------------|------|---------|
| 0 | 主屏/内屏 | 物理 | 固定：永远是默认 display |
| 1 | "内置屏幕" (Cover Screen) | 物理 | 固定：折叠屏副屏 |
| 6 | "HDMI 屏幕" | 物理 DP 输出 | **按名称识别**，ID 不固定 |
| 7, 8 | "LibreDeXDP" | 虚拟 | **按名称/uniqueId 识别**，ID 不固定 |

**我们代码里用 `contextDisplayId >= 6` 作为 Dex display 的筛选条件**——这是基于：
1. Android 16 上物理 display 通常分配在低 ID（0, 1, 2...）
2. Dex 的 DP 输出 + 我们创建的 mirror display 总是分配在较高 ID

但更健壮的做法是**不依赖 ID 数字，而是看 DisplayInfo 名称**（"HDMI" 或 "LibreDeXDP"），或者看 TaskDisplayArea 的 `mCanHostHomeTask` 是否被我们设过 true/强制设过 home。

---

## 2. Android 16 WMS 树结构（OneUI 8.0）

```
RootWindowContainer ctx=-1
  ├── DisplayContent ctx=0 (主屏/内屏)
  │   └── DisplayArea$Dimmable
  │       └── TaskDisplayArea ctx=0 kids=5
  │           ├── DisplayContent$ImeContainer
  │           ├── Task (RootTask)  ← home
  │           └── Task (freeform)  ← app
  ├── DisplayContent ctx=1 (Cover Screen)
  │   └── DisplayArea$Dimmable
  │       └── TaskDisplayArea ctx=1 kids=1
  │           └── Task (RootTask)  ← SubHomeActivity
  └── DisplayContent ctx=N ("HDMI 屏幕" 物理 DP 输出，N 是动态分配的 ID)
      └── DisplayArea$Dimmable
          ├── DisplayArea$Tokens ctx=N
          │   └── WallpaperWindowToken ctx=N    ← 壁纸（我们的 hook 创建）
          │       └── WindowState
          ├── TaskDisplayArea ctx=N kids=1
          │   └── Task ctx=N kids=1        ← 这就是 RootTask！
          │       └── Task ctx=N kids=1     ← 子 Task
          │           └── ActivityRecord ctx=N
          │               └── WindowState
          └── DisplayArea$Tokens ctx=N
              └── WindowToken (SystemUI etc.)
```

**关键发现**：
- **Android 16 没有 `RootTask` 类了**——RootTask 的类名就是 `Task`
- RootTask/Task 通过 `mChildren` 链表持有子节点（TaskFragment、ActivityRecord）
- Task 的 displayId **不在自己身上**，在祖先 DisplayContent 的 `mDisplayId` 字段

---

## 3. 字段名对照表（OneUI 8.0）

### DisplayContent
| 字段名 | 类型 | 存在？ | 说明 |
|--------|------|--------|------|
| `mDisplayId` | int | ✅ | **这个是 displayId 的真实来源！** |
| `mChildren` | WindowContainer<?> | ✅ | 子节点列表 |

### Task / RootTask（Android 16 统一为 Task 类）
| 字段名 | 类型 | 存在？ | 说明 |
|--------|------|--------|------|
| `mTaskId` | int | ✅ | task ID，可识别 |
| `mDisplayId` | int | ❌ **不存在！** | 我们之前在这里踩了巨坑 |
| `mChildren` | WindowContainer<?> | ✅ | 子节点列表（TaskFragment 等） |

### TaskDisplayArea
| 字段名 | 类型 | 存在？ | 说明 |
|--------|------|--------|------|
| `mCanHostHomeTask` | boolean | ✅ | 标记该 TDA 是否可以容纳 HOME task |
| `mRootHomeTask` | Task | ✅ | **HOME task 模板**（WMS 用它 respawn SecondaryLauncher） |
| `mTmpHomeChildren` | ArrayList | ✅ | HOME task 过渡期间的临时子节点缓存 |
| `mDisplayId` | int | ✅ | 自身 displayId |
| `mChildren` | WindowContainer<?> | ✅ | 子节点（Task、WindowContainer 等） |

### DisplayArea$Dimmable / DisplayArea
| 字段名 | 类型 | 存在？ | 说明 |
|--------|------|--------|------|
| `mChildren` | WindowContainer<?> | ✅ | 子节点 |

---

## 4. 方法名对照表（OneUI 8.0 / Android 16）

### ❌ 已删除/不可靠的方法

| 方法 | 原来的用途 | Android 16 替代方案 |
|------|-----------|-------------------|
| `TaskDisplayArea.getRootTasks()` | 获取该 TDA 下的所有 RootTask | 递归遍历 `mChildren` 找 class 含 "Task" 且不含 "TaskDisplayArea/TaskFragment/ActivityRecord" 的节点 |
| `WindowContainer.getChildren()` | 获取子节点 | 直接读字段 `XposedHelpers.getObjectField(container, "mChildren")` |
| `Task.getActivities()` | 获取 Task 里的 ActivityRecord 列表 | 递归遍历 `mChildren` 找 class 含 "ActivityRecord" 的节点 |
| `ActivityRecord.getComponentName()` | 获取 ComponentName | Android 16 保留，但**不要在 cleanup 过程中调用**（会触发 binder 调用导致 WMS 并发问题） |
| `Task.getDisplayId()` | 获取 Task 所在 display | Task 上没有这个方法！从祖先 DisplayContent 的 `mDisplayId` 继承 |
| `TaskDisplayArea.getMode()` | 获取窗口模式 | 用 `getWindowingMode()` 或者读字段 |

### ✅ 可用方法

| 方法 | 类 | 说明 |
|------|-----|------|
| `removeImmediately()` | WindowContainer | 官方移除路径，优先用 |
| `removeIfPossible()` | WindowContainer | fallback |
| `finishIfPossible()` | ActivityRecord | 结束 Activity |
| `getWindowingMode()` | TaskDisplayArea | 获取窗口模式 |
| `setWindowingMode(int)` | TaskDisplayArea | 设置窗口模式 |
| `setHomeSupportedOnDisplay(String, int, boolean)` | WindowManagerInternal | 标记某 display 是否支持 HOME |
| `getOrCreateRootHomeTask(boolean)` | TaskDisplayArea | 获取/创建 HOME task 模板 |

---

## 5. 完整修复三步走（fix35）

### STEP 1：收集 Dex display 上的所有 Task

```java
// 关键：Task.displayId 不在自己身上，要从祖先 DisplayContent 继承
collectAllChildrenWithContext(root, contextDisplayId=-1, depth=0, out, contextDisplayIds):
    if (当前节点是 DisplayContent):
        contextDisplayId = 读它的 mDisplayId 字段
    把当前节点加入 out，contextDisplayIds 存继承来的 contextDisplayId
    对 mChildren 里的每个子节点递归

// 然后遍历所有后代，找 Task：
for i in descendants:
    wc = descendants[i]
    displayId = contextDisplayIds[i]   // ← 从祖先继承来的！
    cn = wc.getClass().getName()
    
// 过滤：只保留 Dex display 上的 Task
// 注意：displayId 是动态分配的，>= 6 是 heuristic（假设低 ID 是主屏/Cover）
// 更健壮：检查 DisplayInfo 名称含 "HDMI" 或 "LibreDeXDP"
if (displayId == 0 || displayId == 1) continue;  // 安全：绝对不能碰主屏和 Cover Screen（这两个 ID 固定）
if (displayId < 0) continue;
    
    // 识别 Task：类名含 "Task" 但排除下面这些
    if (cn.contains("TaskDisplayArea")) continue;
    if (cn.contains("TaskFragment")) continue;
    if (cn.contains("ActivityRecord")) continue;
    if (cn.contains("WindowState")) continue;
    
    // 读 taskId（字段名 mTaskId 还在）
    taskId = wc.mTaskId  // 通过反射
```

### STEP 2：移除 Task

```java
for task in dexTasks:
    try:
        task.removeImmediately()    // 优先
        if 不行: task.removeIfPossible()  // fallback
```

### STEP 3：干掉 HOME task 模板（阻止 WMS respawn SecondaryLauncher）

```java
// WMS 的 HOME task respawn 机制：
// TaskDisplayArea 上有个 mRootHomeTask 字段存着 HOME task 模板
// 移除 HOME Task 后，WMS 会从这个模板重新 spawn 新的
// 必须同时干掉这三个：

for tda in dexDisplayTaskDisplayAreas:
    tda.mCanHostHomeTask = false      // 告诉 WMS：这个 display 不能有 HOME
    tda.mRootHomeTask = null          // 干掉 HOME task 模板
    tda.mTmpHomeChildren.clear()      // 清临时子节点缓存
```

**为什么光 remove Task 不够**：Android WMS 有个机制——每个 display 的 TaskDisplayArea 如果标记了 canHostHomeTask=true，当它没有 HOME task 时，会自动从 `mRootHomeTask` 模板 spawn 一个。我们之前只 remove Task，没清模板，所以 SecondaryLauncher 复活了。

---

## 6. SecondaryLauncher 壁纸创建与清理

### 壁纸创建（我们自己的 hook）

```java
// forceWallpaperAttach(displayId):
//  L2505-2536 in DexLayerStackHook.java
wmService.setHomeSupportedOnDisplay("com.sec.android.app.launcher", displayId, true);
wallpaperService.onDisplayAddSystemDecorations(displayId);   // 创建 WallpaperWindowToken！
```

### 壁纸清理（目前缺失）

对称应该调：
```java
wallpaperService.onDisplayRemoveSystemDecorations(displayId);
```

但实际上在 fix35 里没做这个，壁纸也跟着消失了——因为 `mCanHostHomeTask=false` + display 销毁时 WMS 会自动清理 WallpaperWindowToken。

### WallpaperWindowToken 在 WMS 树里的位置（fix33 树日志确认）

```
DisplayContent ctx=N   ← N 是动态分配的 Dex display ID
  └── DisplayArea$Dimmable
      ├── DisplayArea$Tokens          ← 壁纸在这里
      │   └── WallpaperWindowToken
      │       ├── WindowState (壁纸主窗口)
      │       └── WindowState (壁纸子窗口)
      ├── TaskDisplayArea
      │   └── Task → Task → ActivityRecord → WindowState
      ├── DisplayArea (ImeContainer)
      └── DisplayArea$Tokens (SystemUI window tokens)
```

---

## 7. STEP 3 的实际效果（fix35 日志证据）

```
STEP3 mCanHostHomeTask=false on TaskDisplayArea
STEP3 mRootHomeTask set null (was null)      ← 移除 Task 后模板已经是空的
STEP3 mTmpHomeChildren cleared

// WMS 彻底销毁 SecondaryLauncher Surface：
SurfaceFlinger: SecondaryLauncher#381 Destroyed
SurfaceFlinger: SecondaryLauncher#382 Destroyed  
SurfaceFlinger: ActivityRecord t6570 Destroyed
SurfaceFlinger: WallpaperWindowToken hidden!!

// 没有任何 SecondaryLauncher respawn / created / start 日志！
```

---

## 8. 死代码清单（DexLayerStackHook.java）

以下方法**从未被调用**，且内部全是 Android 16 不存在的方法，应该删掉：

| 方法 | 行号 | 内部使用的已删除方法 |
|------|------|-------------------|
| `killSecondaryLauncherIfNeeded(Object wms)` | ~L1259 | `getRootTasks()`, `getChildren()`, `getActivities()`, `getComponentName()` |
| `collectDexRootTasks(Object tda, List out)` | ~L1191 | `getRootTasks()`, `getRootType()` |
| `collectAllTaskDisplayAreas(Object rootWC, List out)` | ~L1173 | 内部用 mChildren 反射是对的，但唯一调用者是 killSecondaryLauncherIfNeeded |

---

## 9. 弯路复盘（fix1 → fix35）

| fix | 尝试 | 失败原因 |
|-----|------|---------|
| 早期 | `getRootTasks()` + `getChildren()` 遍历 | Android 16 这两个方法都没了，NoSuchMethodException |
| 中期 | 直接读 Task 的 `mDisplayId` 字段 | **Task 类上没有 mDisplayId 字段！** 所有 Task 都返回 -1 → 全被跳过 |
| 中期 | 加探针在 cleanup 中读 ActivityRecord 方法 | 触发 WMS 并发修改 → 手机崩溃 |
| fix31-probe | 全字段反射 + 日志 | 发现了 mDisplayId 在 Task 上不存在的真相 |
| fix32 | 递归传递 contextDisplayId | ✅ 终于正确收集到 Dex display 上的 Task！ |
| fix33-tree | 完整树结构探针 | ✅ 确认 Android 16 没有 RootTask 类、Task 嵌套结构是 Task→Task→ActivityRecord |
| fix34-homefield | 探针 TaskDisplayArea 上的 home 字段 | ✅ 发现了 mRootHomeTask / mCanHostHomeTask / mTmpHomeChildren |
| fix35-killhome | STEP 1 + STEP 2 + STEP 3 | ✅ **最终修复！** |

---

## 10. 调试技巧

### 拉 WMS 树结构日志（fix33/34 的方法）

在 `collectAllChildrenWithContext` 递归里加：
```java
XposedBridge.log(TAG + ": TREE" + indent + shortCn + " ctx=" + currentDisplayId + " kids=" + childCount);
if (cn.contains("TaskDisplayArea")) {
    for (Field f : container.getClass().getDeclaredFields()) {
        if (f.getName().toLowerCase().contains("home")) {
            XposedBridge.log(TAG + ": FIELD " + f.getName() + "=" + f.get(container));
        }
    }
}
```

### `am stack list` 快速确认 Task 状态

```bash
# 注意：displayId 动态分配！先看有哪些 display：
adb shell am stack list | grep "displayId="
# 然后针对 Dex 物理屏（名称含 HDMI）或 mirror 屏（名称含 LibreDeXDP）查 Task：
adb shell am stack list | grep -E "SecondaryLauncher|HDMI|LibreDeXDP"
```

### SurfaceFlinger 层日志

```bash
adb logcat -d -s SurfaceFlinger | grep SecondaryLauncher
```

### 确认 WMS 类名和字段的终极探针

```java
// 在某个 Task 对象上打印所有含 "Task"/"Display" 的字段
for (Field f : task.getClass().getDeclaredFields()) {
    if (f.getName().contains("Task") || f.getName().contains("Display")) {
        f.setAccessible(true);
        log(f.getName() + "=" + f.get(task));
    }
}
```

---

## 11. 安全红线

1. **绝对不能碰 display 0（内屏）和 display 1（Cover Screen）**
2. **cleanup 过程中不能调任何可能触发 WMS 并发操作的方法**（如 `getComponentName()`、`getActivities()`）
3. **所有反射必须包在 try-catch 里**——OneUI 8.0 可能和 AOSP 有差异
4. **先探针再改代码**——直接猜方法名/字段名是 fix1-fix30 失败的主因
