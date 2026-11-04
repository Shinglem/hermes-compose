# 10 · Windows 触屏输入

> 回答"怎么做"：本机触屏用手指滑动列表时**跟手滚动**的输入源设计。
> 现象与缺陷记录见 [`../../待修复问题.md`](../../待修复问题.md) 第 1 条；本篇只写结论与实现结构。
> 状态：**已收敛** —— `WM_TOUCH` 方案已弃用，改用 **Temurin 27 + 纯 Compose
> `Modifier.touchDragScroll`**（见 §14）。§0–§13 是得出该结论的过程与判据，保留备查。

---

## 0. 这是一段 workaround —— 历史判据（结论已由 §14 取代）

> **2026-11-04 更新**：本条判断已落地 —— 走了"更换运行时"这条路，`WM_TOUCH` 相关代码全部删除。
> 下面保留，用于说明**当时为什么被迫写 workaround**，以及将来如果又回到 JBR 该怎么判。

本方案之所以存在，是因为**上游两个缺陷叠加**：

1. Compose Desktop 没有触摸通路（`PointerType.Touch` 从不被投递）；
2. JBR 把触屏手势转成离散的 `MouseWheelEvent`，`scrollable` 无法按位移滚动。

**满足任一条就应删掉 `:modules:win-touch` 与 `TouchScroll*`，回归 Compose 原生滚动：**

| 触发条件 | 跟踪位置 | 删除范围 |
|---|---|---|
| CMP 提供桌面触摸通路 | compose-multiplatform#1555 | `modules/win-touch/`、`platform/TouchScroll*.kt`、`AppShell` 里的滚轮过滤、`Main.kt` 的安装代码、本模块在 `settings.gradle.kts` 与 `composeApp/build.gradle.kts` 的引用 |
| JBR 修正触摸→滚轮的合成 | JetBrainsRuntime#655 | 同上 |
| 更换为原生支持触摸的 UI 栈 | — | 同上 |

验收方式：删掉后触屏仍能跟手滚动，且 `pointerInput` 里能看到 `PointerType.Touch`（或 `LazyColumn` 自己能滚）。

---

## 1. 结论先行

| 事实 | 证据（2026-11-03 本机实测） |
|---|---|
| Compose Desktop **没有**触摸通路 | `ui-desktop` 里 `TouchEvent` / `AWTEvent.TOUCH` 零命中；`scrollable` 的 `CanDragCalculation = { type != PointerType.Mouse }` 主动拒绝鼠标拖动 |
| 官方立场：依赖 OS 把触摸转成鼠标事件 | [compose-multiplatform#1555](https://github.com/JetBrains/compose-multiplatform/issues/1555)（closed），维护者原文 *"There is no explicit touch screen support, Compose relies on OS level conversion"* |
| **JBR 与 Temurin 的转换方式不同** | 同一 demo：Temurin 27 → `Press` + 37 个 `Move`（drag，跟手）；JBR 25 → **21 个 `Scroll`（wheel，`precise=1/6/2…`），0 个 Press**（不跟手） |
| JBR 把触摸转成 `MouseWheelEvent` 是已知行为 | [JetBrainsRuntime#655](https://github.com/JetBrains/JetBrainsRuntime/issues/655)（open，2026-10-29）：*touch begin/end are sent as wheel events with rotation 1; touch updates are also sent as wheel events with movement deltas*；Windows 与 Linux 同源实现 |
| 窗口过程能拿到原始触点 | 子类化 `SunAwtCanvas` 后可收到 `WM_TOUCH`（`msg=0x240`），`GetTouchInputInfo` 返回坐标（1/100 px） |
| 用原始触点驱动后跟手 | 吞掉 `WM_TOUCH` 并按 `dy` 调 `dispatchRawDelta`，真机确认"正常了" |

**要点：在 JBR 上不可能靠 `scrollable` / `draggable` / 滚轮换算做到跟手** —— 系统交给 AWT
的只有少量离散 wheel 事件。必须自己拿 `WM_TOUCH`。

---

## 2. 为什么不选其他方案

| 方案 | 结论 | 原因 |
|---|---|---|
| `Modifier.draggable` / 自建 `pointerInput` 拖动 | ❌ | 只在 Temurin 下有效；JBR 下根本没有 `Press`/`Move`（实测 0 个），拿不到拖动 |
| 接管 `PointerEventType.Scroll` 自己换算 | ❌ | 一次甩动只有 4–6 个离散事件，放大只是"跳得更远"，不可能跟手 |
| 改 `LocalScrollConfig` | ❌ | `internal`，应用层不可覆盖 |
| 换回 Temurin 运行 | ⚠️ 不可选 | 成品基线是 JBR 25（见 [05-打包与配置](05-打包与配置.md)），JBR 是 jpackage 与 JCEF 的前提 |
| **子类化窗口过程接收 `WM_TOUCH`** | ✅ | 唯一能拿到逐帧触点的路径 |

---

## 3. 结构

```
modules/win-touch/                                  # 独立模块，JVM-only，不依赖 Compose
└── src/main/kotlin/dev/shinglem/hermes/wintouch/
    ├── TouchInputSource.kt     # 接口 + TouchDrag + Noop 实现
    └── Win32TouchSource.kt     # FFM 实现：子类化 SunAwtCanvas、收 WM_TOUCH、产出位移

composeApp/src/commonMain/kotlin/dev/shinglem/hermes/platform/
├── TouchScrollRegistry.kt      # 区域登记表 + 按坐标命中分发
└── TouchScrollArea.kt          # `Modifier.touchScrollArea` + 进程级单例 `TouchScroll`

composeApp/src/desktopMain/kotlin/dev/shinglem/hermes/Main.kt
└── 窗口内 DisposableEffect 安装 TouchInputSource，位移 → SwingUtilities.invokeLater → registry.dispatch
```

### 3.1 接口（desktop 实现，common 只依赖接口）

```kotlin
/** 触摸纵向位移（像素，Compose 逻辑坐标）。 */
interface TouchInputSource {
    /** 是否可用（非 Windows、无触屏时 false —— 调用方退回滚轮行为）。 */
    val available: Boolean
    /** 订阅位移；返回取消订阅。必须回到 UI 线程再调 dispatchRawDelta。 */
    fun subscribe(onVerticalDrag: (dy: Float) -> Unit): DisposableHandle
}
```

### 3.2 Windows 实现要点

1. **等到窗口出现再挂**：`User32.FindWindow(null, windowTitle)` 找不到就重试（最多 ~2s）；
   托盘恢复 / 窗口重建后要重新挂载（`Window(hwnd)` 变化）。
2. **必须子类化子窗口**：AWT 的 `SunAwtCanvas` 是独立 HWND，`WM_TOUCH` 发到 **它**而不是
   `SunAwtFrame`。对顶层窗口子类化收不到触摸（实测只收到 `0x7f` / `0x86` 等）。
   → `EnumChildWindows` 遍历，对每个子窗口都挂。
3. **`RegisterTouchWindow` 返回 false 不代表失败**：JBR 已经注册过（实测 `false`，但触点照收）。
4. **解析**：`GetTouchInputInfo` 填 `TOUCHINPUT[]`，每项 40 字节（x64）：
   - `x`/`y` 为**相对窗口客户区**的 1/100 px（`dwID` 在 +16、`dwFlags` 在 +20）；
   - `dwFlags`：`TOUCHEVENTF_DOWN=0x1`、`MOVE=0x2`、`UP=0x4`、`INRANGE=0x8`、`PRIMARY=0x10`；
   - 实测一次按下：`flags=0x1a`（`PRIMARY|INRANGE|MOVE`）。
5. **坐标换到 Compose 逻辑坐标**：除以窗口 `graphicsConfiguration.defaultTransform.scaleX`
   （高 DPI 时 = 1.25/1.5…），否则滑动距离偏大/偏小。
6. **分发（关键）**：触点坐标 → 找到**命中的可滚动区域**，只有它滚。见 §3.3。
7. **线程**：`WndProc` 在 AWT 原生线程回调；位移先入队，由 UI 侧协程消费
   （`withContext(Dispatchers.Swing) { state.dispatchRawDelta(-dy) }`），不要直接在回调里碰 Compose 状态。

### 3.3 区域分发（当前 demo 的缺陷）

demo 把**所有** `WM_TOUCH` 无条件喂给第一个列表，于是"在任何一栏滑动，A 栏都跟着动"。
正式实现必须按坐标命中分发：

```kotlin
// common 侧：可滚动容器登记自己的窗口矩形
@Composable
fun Modifier.touchScrollArea(state: LazyListState, onUserDrag: () -> Unit): Modifier =
    this.onGloballyPositioned { coords ->
        val rect = coords.boundsInWindow()   // 逻辑坐标，与触点换算后同一坐标系
        registry.register(rect) { dy -> state.dispatchRawDelta(-dy) }
    }
```

- 命中规则：取**最内层**命中的区域（后注册 / 矩形更小者优先），避免外层把内层吞掉；
- 没有命中任何区域时**不消费**该触摸（见 §3.4），交给 AWT 默认处理；
- 区域在 `onDispose` 时注销；列表被回收后不得残留。

> **实测确认（2026-11-03）**：demo 把触点无条件喂给第一个列表，结果"在任何一栏滑动，A 栏都跟着动"。
> 说明分发不是可选项 —— 不做命中分发就是全局串味。

### 3.4 tap / 点击必须放行

`WM_TOUCH` 一旦被吞，AWT 不会再把它合成鼠标事件 —— **触屏点击按钮、切会话、拖选文本都会失效**。
这与"能不能滚动"是两件事，必须分开处理：

| 手势 | 期望 | 做法 |
|---|---|---|
| 位移 < touchSlop 就抬起（tap） | 交给 AWT，正常点击 | 手势开始时**先缓存**，超过 slop 才 `return 0` 吞掉；未超过则调 `CallWindowProcW` 放行 |
| 位移 ≥ slop | 由本实现滚动 | 吞掉并只喂位移 |
| 双指 / 三指 | 暂不支持（见 §5） | 吞掉但只取第一根手指，或整体放行（二选一，需实测） |

> ⚠️ 这条**必须实测**：demo 当前是无条件吞，尚未验证 tap 是否仍能点中按钮。

### 3.5 与吸底、与滚轮的关系

- 拖动开始要 `followTail = false`（用户在看历史），发新消息时恢复 —— 与现有
  [`ChatPane`](../../../composeApp/src/commonMain/kotlin/dev/shinglem/hermes/feature/chat/ChatPane.kt) 的语义一致。
- **同时**要吞掉 AWT 由同一次触摸合成的 `PointerEventType.Scroll`（实测一次 3 秒手势会额外产生
  189 个 wheel 事件），否则手动位移与滚轮换算叠加成"滚一段跳一下"。做法：手势期间与松手后
  250ms 内在 Compose 层 `consume()`。
- 鼠标滚轮 / 触控板走的是 `WM_MOUSEWHEEL`，**不受影响**，保持现状。

---

## 4. 用 FFM 还是 JNA

| | JNA（demo 现状） | FFM（`java.lang.foreign`） |
|---|---|---|
| 额外依赖 | 需要 `net.java.dev.jna:jna-platform`（本仓库当前是传递依赖，非直接依赖） | **无**，JDK 25 内置（JDK 22 起正式） |
| 子类化窗口过程 | `SetWindowLongPtrW` + `CallbackReference.getFunctionPointer` | `Linker.nativeLinker().upcallStub(...)` + `Arena` 管理生命周期 |
| 结构体数组解析 | `Structure` 映射，Kotlin 可见性有坑（需手算偏移） | `MemoryLayout` 显式声明，偏移在编译期校验 |
| 长期维护 | 第三方二进制 | 随 JDK 走（JBR 25 含 FFM） |
| 风险 | — | upcall stub 必须由 `Arena` 保活，否则回调崩溃；需 `--enable-native-access=UNNAMED-MODULE`（已有） |

**结论：用 FFM，已实测通过**（2026-11-03，JBR 25，`FfmTouchProbe`）：
`GetWindow` 遍历到 `SunAwtCanvas` → `GetWindowLongPtr` 存原过程 → `SetWindowLongPtr` 换成
`upcallStub` → 收到 `WM_TOUCH` → 驱动 `dispatchRawDelta`，与 JNA 版行为一致，且**去掉了 JNA 依赖**。

FFM 落地时踩到的五个坑（都已在 demo 里修掉，写下来省一轮返工）：

| 坑 | 现象 | 处理 |
|---|---|---|
| `arena.allocateFrom(String)` 默认 **UTF-8** | `FindWindow` 永远返回 0（标题里的 `·` 被按 ANSI 送进 `W` API，变成乱码） | 显式 `allocateFrom(title, StandardCharsets.UTF_16LE)`；`GetString` 同理 |
| 原窗口过程不能全局存 | 一个 `prev` 被后一个窗口覆盖 → 转发到错误的过程，进程 `-1` 崩溃 | `ConcurrentHashMap<Long, Long>`（hwnd → prev） |
| 用 `SetWindowLongPtr(hwnd, GWLP_WNDPROC, 0)` 读旧值 | 副作用是把过程设成 `NULL` | 用 `GetWindowLongPtrW` 读，再 `Set` |
| 异常穿过 upcall 帧 | JVM 直接 abort，无栈可看 | `wndProc` 整体 `try/catch`，失败回落 `CallWindowProcW` |
| 用 `EnumChildWindows` + upcall 枚举子窗口 | JBR 25 上回调不进（静默无结果） | 改用 `GetWindow(GW_CHILD)` / `GetWindow(GW_HWNDNEXT)` 循环（上限 32 个） |

> `RegisterTouchWindow` 在 JBR 上返回 `false` 属正常（JBR 自己注册过），不影响接收。

---

## 5. 已知限制（写进用户可见说明，别当 bug 再报）

1. **双指缩放 / 旋转不支持**：本实现只取单指纵向位移。CMP 桌面本身也不支持
   （[compose-multiplatform#5278](https://github.com/JetBrains/compose-multiplatform/issues/5278) 同类问题，已 closed 未修）。
2. **惯性（fling）不做**：松手即停。要惯性需自己实现衰减并接入 `scrollBy`，
   代价是"轻划翻过头"的老问题会回来。
3. **横向滚动不支持**：只取 `dy`。
4. **非 Windows / 无触屏**：`available = false`，退回滚轮行为（与现在一致）。
5. **UIPI**：若本进程未提权而窗口以管理员运行（不应发生），注入/子类化仍可用，但触屏由系统投递，
   不受影响。

---

## 6. 待办（按顺序）

1. **tap 放行验证**：demo 里改成"超 slop 才吞"，确认触屏能点中按钮、能切会话。
2. ~~**FFM 版本验证**~~：✅ 已完成（见 §4，`FfmTouchProbe` 收到触点并与 JNA 版一致）。
3. **区域分发**：按 §3.3 实现注册表，验证"在哪一栏滑就哪一栏动"（demo 已暴露反例）。
4. **DPI 换算**：在 125% / 150% 缩放下验证位移比例（当前 demo 未做，可能偏大）。
5. **接入项目**：`ChatPane` 消息区 + `SessionListPane` 会话列表 + 搜索结果 + 设置页各列表。
6. **删掉 `touchDragScroll`**：接入后它只剩"Temurin 开发态"的价值，开发态可保留，
   但要标注"成品（JBR）走 WM_TOUCH"。

---

## 7. 验收

- 触屏任意方向滑动：位移与手指大致 1:1，中途不停顿；
- 只在手指所在的那一栏滚动，其他栏不动；
- 触屏点击按钮 / 切会话 / 展开工具卡片正常；
- 鼠标滚轮与触控板行为不回归（滚 3 格 ≈ 3 行）；
- 拔掉触屏（或非 Windows）时退化为现有滚轮行为，不报错；
- 125% / 150% DPI 下位移比例正确。

---

## 8. JBR 为什么把触摸转成滚轮（调查）

**这是 JBR 有意为之的适配，不是 bug。**

证据链：

1. JBR issue [#655](https://github.com/JetBrains/JetBrainsRuntime/issues/655) 的 code investigation 直接点名源码位置：
   `src/java.desktop/unix/classes/sun/awt/X11/XWindow.java` 的 `handleTouchEvent` ——
   *touch begin/end are sent as wheel events with `rotation 1`; touch updates are also sent as
   wheel events with movement deltas*。
2. 本机实测同源：**同一份 demo** 换 JDK 行为就不同 ——
   Temurin 27 下触摸是 `MOUSE_PRESSED/MOUSE_DRAGGED`（press + 37 moves）；
   JBR 25 下是 `MouseWheelEvent`（21 个 `Scroll`，`preciseWheelRotation=1/6/2/…`，0 个 press）。
   同一台机器、同一系统设置 → 差异来自运行时，不是 Windows。
3. `#655` 的报告人明确写道："The lifecycle values appear to be **JBR-specific**"。

目的（合理推断，也符合 JetBrains 的产品需求）：IntelliJ 平台与 Swing 组件只认滚轮/滚动条，
JBR 把触摸平移翻译成滚轮，让**触摸滚动开箱可用**。代价正好是我们遇到的两条：

| 代价 | 表现 |
|---|---|
| 丢失拖动语义 | 一次甩动只有 4–6 个离散事件，只能"跳"，无法按位移跟手 |
| 生命周期被硬塞进滚轮 | `touch down` / `touch up` 各发一个 `rotation=1` 的 wheel → **静止点击也会滚动**（#655 实测） |

所以：**在 JBR 上，"触摸能滚"与"触摸跟手"不可兼得** —— 除非绕开 AWT 直接拿 `WM_TOUCH`（本方案）。

---

## 9. 要不要切回官方 JDK（Temurin）

**结论：换。已用同版本严格对照证实。**（2026-11-03）

| 运行时（**同为 25.0.4.1**） | 手指直接划（`draggable` 栏） | 结果 |
|---|---|---|
| JBR 25.0.4.1 | `Scroll` 23 个、`Move` 6 个、**无 `Press`** | 不跟手 |
| **Temurin 25.0.4.1** | `Press` 1 + `Move` 41 + `Release` 1、**0 个 `Scroll`** | **跟手**（`first` 0→9 连续） |

同一台机器、同一系统设置、**同一 JDK 版本** → 差异来自厂商补丁，不是版本差异。
在 Temurin 上触摸就是 `press/move`，`Modifier.draggable` / 自定义拖动即可跟手，
**可以整段删掉 `:modules:win-touch`（native 代码）**，也不需要按坐标分发（Compose 自己知道事件落在哪）。

### 收益

| 项 | 说明 |
|---|---|
| 删掉 native workaround | 不再子类化窗口过程、不再用 FFM、不再担心 tap 被吞（见 §10 两个已知缺陷） |
| 开发态与成品一致 | 现在开发跑 Temurin 27、成品跑 JBR 25（05-打包与配置 里已提示这种偏差最难查） |
| 去掉 JCEF 运行时 | `jbrsdk_jcef` 带 JCEF；本项目的非目标已明确"不引 WebView"，用不到 |
| 触摸行为是"标准 AWT" | 触摸被当鼠标拖动，语义与鼠标一致，第三方组件也一致 |

### 风险 / 待验证（按优先级）

| # | 风险 | 为什么担心 | 怎么验 |
|---|---|---|---|
| 1 | **中文 IME** | JBR 对 Windows IME 有补丁；AGENTS 硬约束 4 要求中文输入必须可用 | 输入法打中文、组合态、候选框位置 |
| 2 | **字体渲染** | JBR 有 LCD 子像素 / hinting 改动；项目有 `AppPaletteContrastTest` 与多套配色 | 目视对比 6 套主题的正文清晰度 |
| 3 | **jlink / jpackage** | 成品用 `rt/`（jlink 裁剪）+ 原生启动器 | 用 Temurin 的 jmods 跑一次 `packageDistributionForCurrentOS` 与 `syncReleaseRt`，确认启动器能拉起 |
| 4 | 触控板 / 高 DPI | 两者都走 JEP 263，行为应一致 | 150% 缩放下滚动距离与窗口尺寸 |
| 5 | 未来若真要内嵌浏览器 | Temurin 无 JCEF | 与"不引 WebView"的 ADR 冲突时再回到 JBR |

### 决策建议

1. 先在**打包流程**上验证 3（最硬的阻塞项）；
2. 再验 1、2（用户体验项）；
3. 三项都过 → 切 Temurin，删掉 `:modules:win-touch` 与 `TouchScroll*`，回到 `Modifier.draggable` 方案；
4. 任一项不过 → 保留本模块，但按 §10 修掉两个已知缺陷。

> 注意：切 JDK 会同时改 `local.properties`（`hermes.rt.dir`）、`packaging/jvm.args` 的适用版本说明、
> `05-打包与配置` 的基线（JBR 25 → Temurin），属于设计变更，需要一条 ADR。

---

## 10. 当前接入的两个已知缺陷（未修，记录在此）

| 缺陷 | 现象 | 根因 | 修法 |
|---|---|---|---|
| 命中区域与显示对不上 | 在右侧滑也滚左侧会话列表 | `boundsInWindow()` 返回的已经是 **Compose 像素（含 density）**，而 `TouchScrollRegistry` 又除了一次 `density` → 区域被缩小、命中偏移 | 去掉 `dispatch` 里的 `/ density`，直接用原始像素比较（并用 150% 缩放实测确认） |
| 触屏点击像被吞 | 触屏点按钮/会话无反应 | ① 若 `prevProcs` 里没有该 hwnd（窗口重建后未重新子类化）会 `return 0` 吞掉消息；② **JBR 本身把 touch 转成 wheel**（§8），AWT 可能根本不合成 `MOUSE_PRESSED` —— 需与本改动前对比才能定性 | 先对比"未装 hook 时触屏点击是否可用"；若本来就不可用，则属于 §8 的 JBR 缺陷，只能靠切 Temurin 解决 |

---

## 11. 自己改 JBR 把触摸行为改回官方，是否可行？

**结论：技术上可行，但不划算；而且现在已无必要** —— 同版本严格对照（§9）已证明
Temurin 25.0.4.1 的触摸就是 `press/move`，换运行时即可，不需要改 JBR。

### 改动落在哪

| 平台 | 位置 | 证据 |
|---|---|---|
| Linux / X11 | `sun/awt/X11/XWindow.java::handleTouchEvent`（把 touch down/up/update 转成 wheel） | JBR #655 直接点名 |
| Windows | `java.desktop/windows/native/libawt/windows/` 的触摸处理（`awt_Component.cpp` / `awt_Window.cpp` 一类的 native 代码）—— **不在 `lib/src.zip` 里**，JDK 发行版只带 Java 源码 | 本机核对：JBR 与 Temurin 的 `src.zip` 里 `WComponentPeer.java` 都不含 `touch`；两者 `awt.dll` 都带 `RegisterTouchWindow` / `GetTouchInputInfo` 字符串 |

也就是说：要改的是 **native 层**，必须从 JBR 的 OpenJDK 源码树构建。

### 代价

| 项 | 量化 |
|---|---|
| 构建环境 | Windows 上编 JDK 需要 VS 2022 + Cygwin/WSL + Boot JDK，JBR 还叠了自有构建脚本 |
| 构建时间 | 首次全量数小时；增量也要十几分钟（无法像普通 Gradle 项目那样迭代） |
| 产物 | 完整 JDK ≈ 300 MB；我们只需要 `rt/` 那一份 jlink 产物，但**必须每次重建** |
| 维护 | JBR 跟随 OpenJDK 每季更新（jbr25 → jbr27…），每次都要把这处 patch 重新 rebase；JBR 自身的 JCEF/Vulkan/D3D 补丁一直在动，冲突概率不低 |
| 风险 | 触摸语义没有上游测试覆盖；改回来还会顺带影响 `MouseWheelEvent` 的 touch 生命周期（#655 那类"tap 也滚动"的行为消失，但其它依赖 wheel 的触摸逻辑可能退化） |
| 分发 | `rt/` 支持整体替换成别的 JBR 变体（见 05-打包与配置），塞自建 JDK 没问题 —— 难的是**持续产出** |

### 与换 Temurin 的对比

| | 改 JBR | 换 Temurin |
|---|---|---|
| 能否让触摸变成 `press/move` | 能 | 能（已是官方行为，实测） |
| 能否删掉 `:modules:win-touch` | 能 | 能 |
| 一次性成本 | 高（自建 JDK） | 低（换 `hermes.rt.dir` + 打包验证） |
| 持续成本 | **每季 rebase + 重建** | 跟随上游发行版 |
| 附带风险 | 自建分叉，行为只在本机验证过 | 丢掉 JBR 的字体 / IME 补丁（需实测，见 §9） |
| JCEF | 保留（用不到） | 丢失（非目标，需不到） |

### 建议顺序

1. 先按 §9 验 Temurin（打包链路 → 中文 IME → 字体）；
2. 三项全过 → 换 Temurin，删掉 native 模块，`Modifier.draggable` 就够；
3. 若**只有** IME 或字体不过 → 再评估"改 JBR"，并且只做最小 patch（在 native 里跳过 touch→wheel 转换），
   同时把 patch 记进 ADR 与 `hermes-bundle` 的构建说明，避免下次升级时丢失。

> 另注意：**改 JBR 与我们现在的 `WM_TOUCH` 方案收益相同**（都是让触摸可跟手），区别只是
> "谁来做这段转换"。既然本模块已经跑通，改 JBR 的边际收益只有"少一份 native 代码"，
> 不值得为此维护一个 JDK 分叉。

---

## 12. 保留 JBR 的前提下，能不能"只用一个 modifier"？

### 先答：**纯 Compose modifier 在 JBR 上不行**

实测（JBR 25.0.4.1，同一次手势）：

| 拿到的东西 | 数量 | 能否驱动跟手滚动 |
|---|---|---|
| `PointerEventType.Scroll`（`scrollDelta` 离散） | 4–6 个 / 次手势 | 不能。一次甩动只有几个点，放大只会"跳得更远" |
| `PointerEventType.Move`（未按下） | 3–6 个 | 不能。没有 `pressed`，不构成拖动序列 |
| `PointerEventType.Press` | **0** | — |

所以「只写一个 modifier、不碰 native」在 JBR 上做不到跟手 —— 这不是实现取舍，是事件里没有位移。

### 但可以做成**modifier 形态**：把命中判定交回 Compose

现在的脆弱点不是"要有 native"，而是"我自己算坐标命中"（也就是刚才那个区域偏移的 bug）。
可以彻底去掉坐标计算 —— **利用 JBR 已经做过的命中测试**：

JBR 把触摸合成为 `MouseWheelEvent` 后，AWT/Compose 会把这个 wheel **投递给手指下的那个组件**。
也就是说，"哪个列表正在被触摸"这件事，Compose 自己知道，不需要我们从 `TOUCHINPUT` 的坐标去猜。

于是结构变成：

```
手势开始
  ├─ WM_TOUCH 到达（我们的 hook，有 dy，但不知道落在哪）
  └─ AWT 合成 wheel → Compose 把 Scroll 投递给手指下的 modifier
                              ↓
                    该 modifier 认领"本次手势的目标"
                              ↓
              后续 WM_TOUCH 的 dy 只喂给它，并 consume 掉 Scroll（避免二次滚动）
```

对调用方暴露的仍然只是一个 modifier：

```kotlin
LazyColumn(
    state = listState,
    modifier = Modifier.fillMaxSize().touchDrag(listState),
)
```

- **不需要**登记矩形、**不需要** `boundsInWindow()`、**不需要** density 换算 —— 那三个 bug 源一起消失；
- 每个手势只依赖一个信号："第一个 `Scroll` 落在哪个 modifier"；
- 跨栏串味不再可能：没有命中的手势不认领目标，位移直接丢弃。

### 待验证（实现前必须实测）

1. **顺序**：`WM_TOUCH` 与合成的 `Scroll` 谁先到？若 `Scroll` 晚于前几个 `WM_TOUCH`，
   开头会丢几十毫秒的位移（可接受，但要确认不会整段丢弃）。
2. **认领时机**：若手指落在列表边缘/空白处（没有可滚动组件），`Scroll` 不会产生 →
   该手势无人认领（正确行为，但也意味着"落在空白处滑动不滚"，需产品确认是否可接受）。
3. **手势结束**：`WM_TOUCH` 的 `UP` 与最后一个 `Scroll` 谁后到？决定何时清空认领。
4. **多指**：只取 `PRIMARY`，其余忽略。

### 与另外两条路线的对比

| 方案 | 跟手 | 依赖 native | 需要坐标命中 | 保留 JBR 优化 |
|---|---|---|---|---|
| 纯 Compose modifier | ❌（JBR 无位移） | 否 | 否 | ✅ |
| **modifier + WM_TOUCH 认领**（本节） | ✅ | 是（仅取位移，不做命中） | **否** | ✅ |
| 现在的 registry + 矩形命中 | ✅ | 是 | **是**（bug 源） | ✅ |
| 换 Temurin + 纯 modifier | ✅ | 否 | 否 | ❌（丢字体/IME/渲染补丁） |

**建议：走第二行。** 它同时满足"保留 JBR 优化"与"只有 modifier 的心智负担"，
且删掉了唯一会算错的东西（坐标）。

---

## 13. 切官方 JDK（Temurin）会丢掉什么 —— 本机实测对比

对比对象：本机 **JBR 25.0.4.1（jbrsdk_jcef）** vs **Temurin 25.0.4.1**（同版本，消除变量）。

### 13.1 文件级差异

| 类别 | JBR 独有 | 我们是否用到 |
|---|---|---|
| **JCEF**（内嵌 Chromium） | `libcef.dll`、`jcef.dll`、`jcef_helper.exe/.dll`、`cef_server.exe`、`chrome_elf.dll`、`libEGL/libGLESv2`、`vk_swiftshader.dll`、`vulkan-1.dll`、`shared_mem_helper.dll` | ❌ 非目标（ADR 明确不引 WebView） |
| **JOGL**（OpenGL 绑定） | `jogl_desktop.dll`、`jogl_mobile.dll`、`gluegen_rt.dll`、`nativewindow_*.dll`、`newt_head.dll` | ❌ 未使用 |
| **Java2D Vulkan/D3D 管线** | `d3dcompiler_47.dll`、`dxcompiler.dll`、`dxil.dll`；模块内独有类 `Win32VKWindowSurfaceData`、`BufferedSurfaceDataExt` | ⚠️ 只影响 **Swing**（托盘菜单 / `JOptionPane` / 文件选择器）；Compose 走 Skiko/Skia，不经 Java2D |
| **无障碍** | `nvdaControllerClient64.dll` | ❌ 未使用 |
| **内置字体 43 个** | Inter、JetBrains Mono、Fira Code、Roboto、Source Code Pro、Droid、Inconsolata + `font.conf` | ⚠️ Temurin **一个都没有**（`lib/fonts` 为空）；影响 Java2D/Swing 的字体回退；Compose 用系统字体 |
| **额外模块** | `jcef`、`jogl.all`、`gluegen.rt` | ❌ 未使用 |

模块级验证（直接读 `lib/modules` 二进制里的类名/模块名）：

| 类 / 模块 | JBR | Temurin |
|---|---|---|
| `Win32VKWindowSurfaceData`（Vulkan 窗口表面） | ✅ | ❌ |
| `BufferedSurfaceDataExt`（JBR 自定义缓冲表面） | ✅ | ❌ |
| `D3DWindowSurfaceData` | ✅ | ✅（OpenJDK 本来就有） |
| `jcef` / `jogl` 模块 | ✅ | ❌ |

### 13.2 会**真实影响用户**的三项

1. **中文 IME**：JBR 对 Windows 输入法有专门补丁；Temurin 走原生 OpenJDK 逻辑。
   项目硬约束要求"中文输入是硬需求"，**必须实测**（组合态、候选框位置、Enter / 空格行为）。
2. **字体渲染**：JBR 带 43 个字体 + 自有 fontconfig，Temurin 的 `lib/fonts` 是空的。
   Windows 上两者都回落 DirectWrite，但渲染细节（LCD 子像素、hinting）可能不同 ——
   项目有 6 套配色与对比度测试，要目视对比。
3. **Swing 渲染**：托盘菜单、`JOptionPane`、`JFileChooser` 走 Java2D。JBR 的 Vulkan/D3D 管线对它们有改进，
   Temurin 只有 D3D。**影响面小**（都是低频、简单组件）。

### 13.3 基本无感的项

- Compose 全部 UI（Skiko/Skia 直接走 GDI/D3D，不经 Java2D）；
- JCEF / JOGL / 无障碍（未使用）；
- 网络、协程、序列化（与运行时补丁无关）。

### 13.4 落地阻塞（必须先解决）

本机这套 Temurin 25.0.4.1 **没有 `jmods` 目录**（`lib/modules` 有，`jmods/` 无）→ **无法 jlink**。
而成品 `rt/` 正是 jlink 裁剪产物（见 [05-打包与配置](05-打包与配置.md)）。
切换前必须：换一份**带 `jmods` 的完整 JDK 发行包**，并重跑 `syncReleaseRt` /
`packageDistributionForCurrentOS` 验证能拉起。

> 另一个已实测的对照：`jdk-25.0.4.1`（Temurin）`bin` 下 126 个文件，JBR 225 个 —— 差额主要是
> JCEF / JOGL / Vulkan 相关二进制。

### 13.5 结论

| 我们实际依赖 JBR 的东西 | 重要性 |
|---|---|
| 中文 IME | **高**（必须实测） |
| 字体 / 渲染观感 | 中（可目视对比） |
| Swing 渲染加速 | 低 |
| JCEF / JOGL / 无障碍 | 无 |

**"会丢很多优化"这个担心，实际落到我们身上的只有 IME 与字体观感两项**，其余都没用到。
但正因为 IME 是硬需求，切换前必须实测，不能只看清单。

---

## 14. 最终采用：Temurin + 纯 Compose 拖动（2026-11-04）

### 14.1 结论

**弃用 `WM_TOUCH` 方案，运行时基线改为 Temurin（带 jmods 的完整 JDK）。**

依据：同版本严格对照（§9）证明 Temurin 把触摸合成成 `press/move`，`Modifier.draggable`
即可跟手；而 JBR 合成成滚轮，任何应用层方案都要绕开 AWT 自建输入层。

### 14.2 已删除的东西（`WM_TOUCH` 路线的全部痕迹）

| 删除项 | 说明 |
|---|---|
| `modules/win-touch/` 整个模块 | FFM 子类化窗口过程、`WM_TOUCH` 解析、位移输出 |
| `platform/TouchScrollRegistry.kt` | 按坐标命中分发（两次踩坑：density 换算、跨栏串味） |
| `AppShell` 的全局滚轮过滤 | 手势期间 `consume` 掉 AWT 合成滚轮 |
| `Main.kt` 的触摸源安装 | `DisposableEffect` + 重试 `FindWindow` |
| `settings.gradle.kts` / `composeApp/build.gradle.kts` 的模块引用 | — |

### 14.3 现在的实现（唯一一处）

`composeApp/.../platform/TouchScrollArea.kt`：

```kotlin
@Composable
fun Modifier.touchDragScroll(state: LazyListState, onUserDrag: () -> Unit = {}): Modifier {
    val currentOnDrag = rememberUpdatedState(onUserDrag)
    val draggableState = rememberDraggableState { delta ->
        currentOnDrag.value()
        state.dispatchRawDelta(-delta)
    }
    return this.draggable(state = draggableState, orientation = Orientation.Vertical)
}
```

用到的地方：`ChatPane` 消息区、`SessionListPane` 会话列表与搜索结果列表。

**不要加 `startDragImmediately = true`**：它会立刻抢占手势，把点击 / 长按 / 文本选择一起吞掉
（实测 `ChatPaneTest` 4 个用例直接失败）。默认行为要超过 slop 才拖动，而鼠标类型的 slop
只有 `0.125dp`（`DragGestureDetector.mouseSlop`），所以仍近似跟手。

### 14.4 `WM_TOUCH` 路线踩过的坑（留档，避免有人重走）

| # | 坑 | 现象 |
|---|---|---|
| 1 | 提前 `CloseTouchInputHandle` | **所有触摸失效**（点击/长按/拖动全没反应）——消息还要转发给 AWT |
| 2 | 用 `SetWindowLongPtr(..., 0)` 读旧过程 | 副作用把窗口过程设成 NULL |
| 3 | `arena.allocateFrom(String)` 默认 UTF-8 | `FindWindow` 永远返回 0 |
| 4 | 原窗口过程存全局变量 | 多窗口互相覆盖 → 进程 `-1` 崩溃 |
| 5 | upcall 里漏 try/catch | 异常穿过 native 帧，JVM 直接 abort |
| 6 | `EnumChildWindows` + upcall | JBR 25 上回调不进 |
| 7 | 按 `boundsInWindow()` 做命中 | 已含 density，再除一次 → 区域缩小、跨栏串味 |
| 8 | 认领在 `Main` pass | `scrollable` 先消费并滚动 → "一开始滚很多行再跟手" |