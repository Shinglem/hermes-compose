# 09 · v0.6.0 结构性重构（活文档）

> 本篇是 **v0.6.0 的权威可迭代版本**：现状实测问题 → 目标结构 → 阶段与退出条件 → 不做清单 →
> 风险 → 验收口径 → 变更记录。**只留结论与可执行项，不写决策过程**；取舍理由一律进
> [`07-decisions.md`](07-decisions.md)（ADR-057 ~ ADR-064）。
>
> **怎么落地、怎么测**见详细设计两篇：
> [`13-重构落地设计（v0.6.0）`](../详细设计/工程与代码/13-重构落地设计（v0.6.0）.md)（逐文件的改法）与
> [`14-测试计划（v0.6.0）`](../详细设计/工程与代码/14-测试计划（v0.6.0）.md)（逐测试的处置）。
>
> 基线：`main` @ `3b180ac`（v0.5.5 之后）。实测口径（2026-11-13，分支 `feature/v0.6.0-refactor`）：
> `composeApp/src` **305 个源文件 / 49,935 行 / 573 个 `@Test` / 83 个测试文件**，
> **16 个 androidx `ViewModel` 子类**、**11 个单文件 > 600 行**。
>
> **怎么"方便后续调整"**：§3 每个阶段带**状态列**与退出条件；每推进一段就改状态 + 在 §7 追加一行。
> 阶段内的细项用会话内的 todo 跟踪，不进本文正文（避免两处维护）。

---

## 0. 决策前提（已与用户确认）

### 0.1 版本切分（已定）

| 版本 | 范围 | 说明 |
|---|---|---|
| **v0.6.0** | **S0–S6 + S8** | **完整对齐 v0.5.x 全部功能**，重新设计与规划代码。**不含 S7、不含编辑器** |
| **v0.6.1** | **S7 + 内嵌编辑器槽位 + 编辑器选型定稿** | S7.1 浏览器面板 / S7.2 插件宿主骨架 / 编辑器槽位与选型 |
| 分支 | **`feature/v0.6.0-refactor`** | 整个 v0.6.0 在该分支开发，**不直接动 `main`** |

### 0.2 技术前提

| 项 | 结论 |
|---|---|
| **整体架构** | **Decompose 3.6.1 + MVIKotlin 4.4.0 + Koin 4.2.2**（ADR-057） |
| **桌面窗口壳** | **迁 Nucleus Tao 3.0 dev**（`nucleusApplication` + `DecoratedWindow`），接受 dev 版（ADR-062） |
| 允许 experimental | `ChildPanels`（Main/Details/Extra + SINGLE/DUAL/TRIPLE）可用；`ChildItems` 不用 |
| 时间旅行调试 | 0.6.0 **不接** `mvikotlin-timetravel`，0.7 再评估 |
| 重构力度 | 结构性重写：16 个 androidx `ViewModel` → `Component` + `Store`，状态逻辑平移 |
| 状态稳定性 | UiState / Store State 用 `@Immutable` + **kotlinx-collections-immutable 0.5.2** |
| **浏览器面板** | **v0.6.1** 用 `NativeView` 嵌**我们自己拉起**的 payload Chromium（ADR-063） |
| **插件宿主** | **v0.6.1 只搭骨架**；全部插件运行在 WebView 内，不与 Compose UI 融合；**不重写插件机制**（ADR-064） |
| **内置编辑器** | **不内嵌 VSCode**；v0.6.1 定选型（倾向单进程原生轻量编辑器）；ADR-005 不变（ADR-064） |
| #13 / #14 / #15 | 不进 v0.6.0，在 0.5.x 线解决 |
| 通知 / AUMID | 不进 v0.6.0，登记为 [`待修复问题.md`](../待修复问题.md) **#21** |
| CI 门禁 | 本轮**不加** `desktopTest` 到 `desktop-release`（保持只编译）；但分支上每次提交须本地 T1–T5 全绿 |

---

## 1. 现状实测问题清单

### 1.1 规模与职责错配（高内聚失败）

| 位置 | 现状 | 判定 |
|---|---|---|
| `feature/shell/AppShell.kt` | **1,071 行**；98 条 import；15 个 `viewModel {}`；19 个 `collectAsStateWithLifecycle`；13 个 `LaunchedEffect`；`Scaffold` 内 **192 条 `onXxx = ` 回调接线** | 一个文件同时是：容器装配、副作用编排、导航、通知分发、三模式布局、面板拼装 |
| `feature/chat/ChatViewModel.kt` | **2,147 行**、78 个方法；持有 **8 个 `Map<SessionKey, *>`**（`cache` / `runtimeIds` / `generations` / `revisions` / `lastEventAt` / `cwds` / `savedStates` / `dirtyKeys`）+ 6 个待决表 | 传输、会话生命周期、fence 管理、流式状态机、模型切换、审批/澄清、后台任务、用量、重发/分支 —— 9 个关注点挤在一个类 |
| `feature/settings/SettingsDetailPane` | **62 个参数**、24 个 `SettingsPage` 分支 | 参数膨胀本身就是"这个函数不该存在"的信号 |
| `feature/settings/MaintenanceState.kt` | **915 行**、单个 ViewModel 管 memory / ops / credentials / usage / sessions / integrations / debug **7 个不相关领域** | settings 域总计 9,157 行 / 34 文件，是全仓库最重的一块 |
| `feature/capabilities/CapabilityViewModel.kt` | 535 行管 skills / toolsets / platforms / cron / blueprints / catalog 六块 | 同上 |

### 1.2 分层违约（低耦合失败）

| 位置 | 问题 |
|---|---|
| `core/transport/RestClient.kt:7` | **`import dev.shinglem.hermes.feature.session.SessionSummary`** —— core 反向依赖 feature |
| `ChatViewModel` / `ShellViewModel` / `SubagentViewModel` / `UpdaterViewModel` | 5 处直接 `import core.transport.GatewayClient/RestClient`，**跳过 Repository 层** |
| `core/time/TimeFormat.kt:76` 与 `feature/session/SessionListPane.kt:836` | `Int.pad2()` 各写一份 |
| 7 个 State.kt | 各自定义私有 `Throwable.describe()` / `notice()`（`CapabilityViewModel:583`、`CustomEndpointState:251`、`MaintenanceState:907`、`MoaSettingsState:295`、`ModelSettingsState:367`、`ProfilesState:269`、`SecurityState:111`）—— 而 `core/error/AppErrorText.kt` 已有 `AppError.toNotice()` |
| `feature/session/SessionListViewModel` | 为"导出"依赖 `ChatRepository` + `projectChat`，session 与 chat 双向耦合 |
| `feature/shell/ProfileRepository` vs `feature/settings/ProfilesAdminRepository` | 两个 profile 仓库打同一批后端接口，一个走 RPC 一个走 REST，`ProfilesState` 只认识后者 |

### 1.3 重复样板（复用性失败）

| 模式 | 命中次数 |
|---|---|
| `fun setProfile(next: String?)` + `fun onVisible()` + `private var loaded = false` 三件套 | **7 组**，逐字同构 |
| `catch (cancelled: CancellationException) { throw cancelled }` | **114 处** |
| `_state.update { it.copy(...) }` | **242 处** |
| `val error: String?` / `val notice: String?` / `val busyKey: String?` 各页各一套 | 16 / 14 / 7 |
| `profile: String?` 作为方法首参 | **143 处** |
| 手写 JSON 取值 `(x as? JsonPrimitive)?.intOrNull` | **54 处** |
| `runCatching { }`（有吞掉 `CancellationException` 的风险） | **57 处** |
| 手写 `Job? = null` 式防抖 | **9 处** |
| `AlertDialog(` 手工拼装 | 14 处（`ChatPane` 3 / `SessionListPane` 3 / 其余 8） |
| 硬编码中文 UI 文案 | **100 处**（对照：`Res.string.*` 247 处） |

### 1.4 闲置 / 误声明依赖

| 依赖 | 实际状态 |
|---|---|
| `ktor-client-content-negotiation` + `ktor-serialization-json` | **声明了但 0 处 `install(ContentNegotiation)`** → 全仓库 54 处手写 `JsonElement` 取值 |
| `coil-compose` / `coil-svg` / `coil-network-ktor3` | 在 `composeApp` 内 **0 引用**；真正使用者是 `modules/markdown-one`（它自己已声明同版本） |
| `@Immutable` / `@Stable` | 全仓库 **仅 1 处**。UiState 里全是 `List<T>`（不稳定类型）→ 每次 state 变更整棵子树重组 |

### 1.5 可测性缺口

- **8 个 ViewModel 的测试覆盖为 0**（`ChatViewModel` / `ShellViewModel` / `SessionListViewModel` /
  `ComposerViewModel` / `CapabilityViewModel` / `MaintenanceViewModel` / `SecurityViewModel` 等）
- 原因：构造参数全是 `final class`（`ChatRepository` 等），无法注入 fake；唯一替身是
  `SettingsCatalogTest.kt:143` 的 `FakeRestarter`
- 现有 573 个测试大多是**纯函数/纯逻辑**（`ScrollFollow` / `SessionSwitchPlan` / `SessionShadow` /
  `ChatProjection` / `buildSessionRows`）—— **这是本仓库最好的资产**，继续加测试而不是替换
- `@Preview` **0 处**，截图测试 **0 处**

### 1.6 逻辑路径问题（迭代中长出来的）

1. **`ChatViewModel` 每帧事件做 3 次线性反查**：`handleGatewayEvent` 里
   `runtimeIds.entries.firstOrNull { it.value == sid }` 出现 3 次（另 1 处在 `keyFor`），
   事件热路径上 O(会话数) 扫描 → 应维护反向索引 `runtimeId → SessionKey`。
2. **8 个平行 map 没有原子性**：`runtimeIds` / `generations` / `revisions` 必须同步更新，
   漏一处就是 `4001 stale_generation`（CHANGELOG 0.5.4 / 0.5.5 连续两版都在修这个家族）。
   → 收敛成**一个** `SessionRuntime` 值对象，`Map<SessionKey, SessionRuntime>` 单点维护。
3. **流式状态机写了两遍**：`StreamSegments.kt` 的 `appendAnswer/appendReasoning/startTool/finishTool/sealInterim`
   与 `ChatViewModel` 的 1586–1640 行、以及 `SessionShadow.applyShadowEvent` 三处走同一套变换
   → 统一为 `ChatState.reduce(event)`。
4. **`AppShell` 里 3 个 `LaunchedEffect` 都 key 到 `shellState.activeProfile`**（243 / 247 / 262 行），
   可合成一次，减少首帧重复触发。
5. **通知去重分散三处**：`ReminderGate`（时间窗）、`notifiedApprovals`（VM 内）、`NotificationInbox`
   队列（平台层）→ 判定"这条该不该弹"应单点化。
6. **`pendingJump`（跨 profile 跳转）是 UI 层临时状态**，profile 切换失败时会悬挂。

### 1.7 桌面壳的现状约束（v0.6.0 新增）

- 窗口壳是 Compose Desktop 自带（AWT）：`Main.kt` 393 行里用了
  `application {}` / `Window` / `rememberWindowState` / `Tray` / `UIManager` / `JOptionPane`。
- **AWT 依赖面只有 10 个文件**（实测 `import java.awt|javax.swing`）：

  | 文件 | 用途 | 归层 |
  |---|---|---|
  | `Main.kt` | 窗口 / 应用入口 / 错误弹窗 | **窗口层** |
  | `core/app/DesktopTray.kt` | 整套 Swing 托盘（`SystemTray` / `TrayIcon` / `JPopupMenu` / `JWindow` / `MouseInfo`） | **窗口层** |
  | `platform/Clipboard.desktop.kt` | `Toolkit.systemClipboard` | 工具类 |
  | `platform/Links.desktop.kt` | `Desktop.browse` | 工具类 |
  | `platform/AppFocus.desktop.kt` | `Window.getWindows()` 兜底判前台 | 半窗口层（需替换） |
  | `platform/FileDrop.desktop.kt` | `java.awt.FileDialog` | 工具类（FileKit 接管） |
  | `platform/Notifications.desktop.kt` | `SystemTray` / `TrayIcon` 气泡 | 工具类 |
  | `ui/notification/NotificationOverlay.kt` | 独立 `Window` | 窗口层 |

- **运行时镜像现状**：`rt/release` 的 `MODULES` 含 `java.desktop`。即使迁 Tao，
  上面这些工具类仍需要它 → **不要指望靠迁 Tao 裁掉 `java.desktop`**。
- 触摸走 Temurin 27 + `Modifier.touchDragScroll`（见
  [`详细设计/工程与代码/10-Windows触屏输入.md`](../详细设计/工程与代码/10-Windows触屏输入.md) §14），
  迁 Tao 后由 DirectManipulation 接管，**必须重测**。
- 单实例是自研 `CreateMutexW` + `--silent` 计划任务激活（`nucleus` 的文件锁语义更弱，
  已定论不换）。`nucleusApplication` **自带单实例锁**，必须验能关掉。

---

## 2. 目标结构

### 2.1 分层（改动后的硬边界）

Y 方案的杀手特性是「业务逻辑不依赖 UI」，所以分层比现在更硬：

```
component/     业务逻辑组件（纯 Kotlin，不 import Compose）
  <域>/          XxxComponent + DefaultXxxComponent(ComponentContext by ...)
  store/         Store<Intent, State, Label>：Reducer / Executor / Bootstrapper
ui/            纯 Composable，只接 (State, callbacks)；唯一 import Compose 的层
  <域>/          XxxContent(component) + 子组件
domain/        Repository 接口 + 领域模型（ChatItem / SessionKey / SessionRuntime …）
data/          Repository 实现（RPC/REST），唯一持有 GatewayClient / RestClient
core/          transport / contract / error / result / time / config / log（不知道 component）
platform/      expect/actual（不变）
shell/         桌面壳适配：Tao 窗口 / 托盘 / 单实例 / 提权 / 自启（唯一接触 Nucleus 的层）
di/            Koin 模块（appModule / storeModule / componentModule）
```

**强制项**（架构测试钉死，`commonTest` 扫描源码 import）：

1. `component/` 与 `domain/` 下不得出现 `androidx.compose.*`
   —— 这是 Decompose 的核心承诺"业务逻辑可用纯 JVM 单测"
2. `core/` 与 `domain/` 下不得出现 `import dev.shinglem.hermes.(component|ui|shell).*`
3. `ui/` 下不得出现 `import ...data.`
4. `shell/` 是**唯一**允许 `import dev.nucleusframework.*` 的包 —— Tao 可替换
   （失败代价只在这一层，不在 `component` / `domain`）

### 2.2 每域标准形状（Component + Store）

```kotlin
// component/<域>/XxxComponent.kt —— 业务逻辑，无 Compose
interface ChatComponent {
    val state: StateFlow<ChatState>          // 经 StoreExtKt 从 Store 转来
    val effects: Flow<ChatEffect>            // Store 的 Label 转来
    fun onIntent(intent: ChatIntent)
}

class DefaultChatComponent(
    componentContext: ComponentContext,
    private val repository: ChatRepository,
    private val runtime: SessionRuntimeStore,
) : ChatComponent, ComponentContext by componentContext {

    // Store 存在 InstanceKeeper 里（≈ retainedInstance / ViewModel），
    // Component 因 UI 重组而重建时，Store 与已积累的 transcript 不丢。
    private val store: ChatStore = instanceKeeper.getStore {
        ChatStoreFactory(
            reducer = ChatReducer(),
            executorFactory = { ChatExecutor(repository, runtime, it) },
            bootstrapper = ChatBootstrapper(runtime),
        )
    }
    override val state: StateFlow<ChatState> = store.state
    override val effects: Flow<ChatEffect> = store.labels
    override fun onIntent(intent: ChatIntent) = store.dispatch(intent)
}

// component/<域>/store/ChatReducer.kt —— 纯函数，无需任何框架就能测
internal class ChatReducer : Reducer<ChatState, ChatResult> {
    override fun ChatState.reduce(result: ChatResult): ChatState = when (result) {
        is ChatResult.Delta -> copy(streaming = streaming.appendAnswer(result.text))
        // …只此一份流式状态机，吃掉 StreamSegments / ChatViewModel / SessionShadow 三份实现
    }
}
```

- **「7 组 `setProfile/onVisible/loaded` 样板」的替代**：`Component` 生命周期天然给了
  `onCreate` / `onResume` / `onDestroy`，profile 变化走 `Intent.ProfileChanged`，不再需要手写 `loaded`。
- **「5 个 `var onXxx`」的替代**：`Label`（`ChatEffect`），官方语义就是一次性事件。
- **UI 层心智不变**：仍是 `StateFlow` + `collectAsState()`，只换"谁持有状态机"。

### 2.3 导航（Decompose `ChildStack` + `ChildPanels`）

```
component/root/
├── RootComponent           持有全局：SessionRuntimeStore / 三个子路由
│                            val panels: Value<ChildPanels>   ← Main=列表, Details=详情
│                            val tabs:   Value<ChildStack<*, TabChild>>  ← 会话/能力/设置
└── Configs.kt              @Serializable sealed interface RootConfig : Parcelable
                            (SessionsConfig / CapabilitiesConfig / SettingsConfig(page))
```

- **模式切换**用 `ChildStack`（稳定 API）：会话 → 能力 → 设置可后退，`BackHandler` 内建
- **两栏/三栏**用 `ChildPanels`（experimental，已获准）：`mode = SINGLE | DUAL | TRIPLE`
  由窗口宽度档位驱动，**每 panel 的 Lifecycle 自动管** → 「切到设置时列表应该 PAUSE」不再手写
- **rail 在 panels 外**（`AGENTS.md` 硬约束 11）：`Row { ProfileRail; ChildPanelsView }`
- **尺寸分配仍自己写**：`ChildPanels` 只管生命周期不管布局 →
  `Row { List(weight = 0.34f); Detail(weight = 0.66f) }` 按宽度档位切换
- **`ChildPanels` 收在一层薄封装**（`ui/shell/PanelsHost.kt`），API 变时只改一处
- **删掉的依赖**：`adaptive-layout` + `adaptive-navigation`（含 CMP-8735 的 `key(宽度档位)` 绕法）
- **路由持久化**：`Config` 是 `@Serializable` + Decompose 自动用 `StateKeeper` 持久化
  → 「重启后回到上次的页面」不再需要 `preferences.mode`（String）手工管

### 2.4 DI：Koin 4.2.2

解决三件事：① 终结 15 个 `viewModel {}` 手工 lambda（`AppShell` 瘦身的必要条件）；
② Component 是普通类，`get()` 注入即可；③ 给 8 个零覆盖的 VM 等价物提供测试替身。

| 坐标 | 版本 | 体量 | 用途 |
|---|---|---|---|
| `io.insert-koin:koin-bom` | 4.2.2 | — | 统一版本 |
| `koin-core` | 4.2.2 | 426 KB | 容器 |
| `koin-compose` | 4.2.2 | 39 KB | `KoinApplication` / `KoinScope` |
| `koin-test`（testImplementation） | 4.2.2 | 75 KB | `verify()` / `checkModules()` / `declareMock` |

> **不再需要** `koin-compose-viewmodel` 与 `koin-compose-navigation3`：Y 方案没有 androidx
> `ViewModel` 也没有 Nav3。

```kotlin
val appModule = module {
    single<AppLogger> { Logging.desktop }
    single { GatewayClient(get(), get(), get()) }      // 必须应用级单例（ADR-013 理由不变）
    single<ChatRepository> { ChatRepositoryImpl(get()) }
    single { SessionRuntimeStore() }                   // 跨会话的运行时状态池
}

val componentModule = module {
    factory { (ctx: ComponentContext) -> DefaultRootComponent(ctx, get()) }
    factory { (ctx: ComponentContext, key: SessionKey) -> DefaultChatComponent(ctx, get(), get(), key) }
}
```

- 进程启动：`Main.kt` 里 `startKoin { modules(appModule, componentModule) }`；
  `AppContainer` **保留**为非 Composable 侧（`Main` / 托盘 / 自启）的门面，内部从 Koin 解析
- `commonTest` 加 `ModuleVerificationTest` 跑 `verify()`
- **不用 Koin Annotations**：项目零 KSP（实测 `rg ksp` = 0）

### 2.5 状态稳定性

- `ChatState` / 各 `UiState` 一律 `@Immutable`，集合字段用 `ImmutableList` / `ImmutableMap`
- 转换集中在 Reducer 边界：`List<T> → ImmutableList<T>`
- 收益可测：用现有 `runComposeUiTest` 加重组计数器断言

### 2.6 复用与替代

| 事项 | 处置 |
|---|---|
| `ktor ContentNegotiation` | **真装上**，`RestClient` 增加 `suspend inline fun <reified T> getAs<T>()`；54 处手写解析收敛到 DTO 反序列化 |
| `coil-*` 三个依赖 | 从 `composeApp` 移除（`markdown-one` 自己声明同版本，传递可用） |
| `AlertDialog` 14 处 | 抽 `ui/component/ConfirmDialog` / `PromptDialog` |
| `Throwable.describe()` 7 份 | 全删，统一 `AppError.toNotice()`；非 `AppError` 走 `ErrorMapper.from` |
| `Int.pad2()` 2 份 | 删 `SessionListPane` 那份；时间格式化统一走 `TimeFormat` |
| 手写防抖 9 处 | 抽 `core/util/Debouncer`（`Job` 封装 + 测试） |
| `runCatching` 57 处 | 逐处审：吞异常的地方改为显式 `catch`，`CancellationException` 必须重抛 |
| 100 处硬编码文案 | 迁进 `strings.xml`（`Res.string.*`） |
| `ScrollFollow` / `SessionSwitchPlan` / `ChatProjection` 等纯函数 | **保留**，继续加测试而不是替换 |

### 2.7 采纳 / 不采纳

#### 采纳（本轮引入）

| 自研内容 | 替代 | 版本 | 理由 |
|---|---|---|---|
| 15 个 `viewModel {}` + 手工单例装配 | **Koin** | 4.2.2 | §2.4；附 `verify()` 依赖图校验与测试替身 |
| `enum ShellMode` 三模式 + `SettingsState.selected` + `preferences.mode` | **Decompose `ChildStack`** | 3.6.1 | 真回退栈 + `BackHandler` 内建 + `@Serializable` Config 自动持久化 |
| `AdaptiveListDetail`（70 行）+ CMP-8735 `key(宽度档位)` 绕法 | **Decompose `ChildPanels`** | 3.6.1 | Main/Details + SINGLE/DUAL/TRIPLE 与单栏/双栏一一对应，每 panel 独立 Lifecycle |
| **`SessionShadow`（133 行）+ `SessionSwitchPlan`（60 行）** | **Decompose 的 back stack 语义** | 3.6.1 | 官方承诺「back stack 里 Component 不销毁，继续在后台跑、只是无 UI」 |
| 8 个平行 `Map<SessionKey, *>` + 手工生命周期 | **`Component` + `InstanceKeeper`** | 3.6.1 / Essenty 2.7.1 | 每会话一个 Component，作用域一等公民 |
| `preferences.draft` / `savedStates` 手工序列化 | **Essenty `StateKeeper`** | 2.7.1 | 自动保存草稿/路由/滚动位置 |
| 3 份流式状态机 | **MVIKotlin `Reducer`** | 4.4.0 | 单一纯函数归约；现有纯函数测试可直接平移 |
| 5 个 `var onXxx` 可变回调 | **MVIKotlin `Label`** | 4.4.0 | 官方语义就是一次性事件 |
| `viewModelScope` + 手工 `try/catch` 114 处 | **`CoroutineExecutor`（suspend）+ `componentScope`** | 4.4.0 | Executor 内直接 `suspend` 调 Repository，取消随 Component 生命周期传播 |
| 手工 `TestScope` + `launch { collect }` 验证状态序列 | **Turbine** | 1.2.1 | `awaitItem()` 才能可靠捕获"多发/少发" |
| 5 个 `expect` + 5 个 `actual` 文件选择器 | **FileKit** | 0.17.0 | 支持 JVM/Windows，MIT |
| 54 处手写 JSON 取值 | **ktor ContentNegotiation** | 已在依赖里 | 白捡 |
| `List` 型 State 的稳定性 | **kotlinx-collections-immutable** | 0.5.2 | `@Immutable` 目前仅 1 处 |
| 9 处手写防抖 | **`FlowPreview.debounce`** | 已在依赖里 | 不用新依赖 |
| Compose Desktop 自带 AWT 窗口壳 | **Nucleus Tao 3.0 dev** | 见 §2.10 | ADR-062 |
| 浏览器新弹窗（现状） | **`NativeView` 嵌自有 Chromium** | 同上 | ADR-063 |
| （不做）插件宿主 | **WebView 内宿主框架** | composewebview 1.0.3 | ADR-064，本期只搭骨架 |

#### 明确不采纳（附理由）

| 候选 | 版本 / 许可 | 不采纳理由 |
|---|---|---|
| **navigation3** | 1.2.0 / Apache-2.0 | ① 关键能力（导航项级 ViewModel 作用域）在 **desktop 上是空壳**（实测 jar 5,029 字节、仅 `META-INF`；Google Maven 无 desktop 变体）；② 与 Decompose 的 Component 生命周期模型重叠且更弱 |
| **KStateMachine** | 0.38.1 / **BSL-1.0** | 我们的状态是**累积式**；FSM 表达"同一状态内数据累积"别扭；MVIKotlin 的 `Reducer` 已覆盖。唯一候选是审批/澄清的 `pending→decided→cancelled→timeout`，记入 S3 备注 |
| **Circuit** | 0.39.0 / Apache-2.0 | 0.x；`Screen`/`Presenter` 与 `Store` 同层级竞争，已选后者 |
| **Voyager** | 1.1.0-beta03 / Apache-2.0 | 纯导航库，主版本仍 beta 性质；作用域能力已被 `Component` 覆盖 |
| **compose-dnd** | 1.0.0-alpha02 | 实测三处拖拽都不属它范畴：分隔条 / OS 文件拖入 / Windows 触屏 |
| **`mvikotlin-timetravel`** | 4.4.0 | 需录制器 + proto + 调试 UI，独立工作面。**0.7 评估** |
| **Koin Annotations**（KSP） | 4.2.2 | 零 KSP；收益只是少写几行 DSL |
| **kotlinx-serialization 契约生成器** | — | ADR-010 曾计划；生成器本身几百行且需跟后端 schema。本轮把 DTO 集中到 `domain/contract`，为将来留位 |
| **Kotest / MockK** | — | 现有 `kotlin.test` + 573 测试已够 |
| **Detekt / ktlint** | — | 仓库当前无任何静态分析；全量 lint 会一次报上千条历史问题。关键约束靠架构测试覆盖，留 v0.7 |
| **multiplatform-settings** | 1.3.0（已用） | **保留**，只有 `AppPreferences` / `JsonFileSettings` 两处用 |
| **`nucleus.screen-capture`** | 3.0 dev | 只有 dev 版，且是**单帧截图**不是串流 → 浏览器面板不用它（ADR-063） |
| **`composewebview` 用于浏览器面板** | 1.0.3 | 它是 WebView（WebView2），**不能显示 payload Chromium 的页面**；面板要嵌真实 Chromium 窗口（ADR-063） |
| **内嵌 VSCode 作为编辑器** | — | Electron 多进程 IDE，外壳/快捷键/体重都去不掉；功能齐全的代价就是那套壳（ADR-064） |
| **自研插件系统** | — | 官方 `plugin.js` 契约已有；重写一遍等于分叉（ADR-064） |

#### 版本 / 许可核实记录（`AGENTS.md` §2 要求）

| 坐标 | 版本 | jvm/desktop 变体 | 许可证 | 备注 |
|---|---|---|---|---|
| `com.arkivanov.decompose:decompose` | 3.6.1 | ✓（414 KB） | Apache-2.0 | 依赖 Essenty **2.7.1**、Compose foundation **1.9.3**、Kotlin **2.3.21** |
| `com.arkivanov.decompose:extensions-compose` | 3.6.1 | ✓ | Apache-2.0 | `Children` / `ChildPanels` 渲染 + `LifecycleController` |
| `com.arkivanov.mvikotlin:mvikotlin` | 4.4.0 | `-jvm` ✓ | Apache-2.0 | 依赖 Essenty **2.5.0**、Kotlin 2.2.21 |
| `com.arkivanov.mvikotlin:mvikotlin-main` | 4.4.0 | `-jvm` ✓ | Apache-2.0 | Main 线程断言 |
| `com.arkivanov.mvikotlin:mvikotlin-extensions-coroutines` | 4.4.0 | `-jvm` ✓ | Apache-2.0 | `CoroutineExecutor` / `CoroutineBootstrapper` / `Store.state: StateFlow` |
| `com.arkivanov.essenty:lifecycle` / `instance-keeper` / `state-keeper` | 2.7.1 | `-jvm` ✓ | Apache-2.0 | 22.7 / 23.0 / 44.6 KB |
| `io.insert-koin:koin-core` / `koin-compose` / `koin-test` | 4.2.2 | ✓ | Apache-2.0 | 426 / 39 / 75 KB |
| `dev.nucleusframework:nucleus.decorated-window-tao` | `3.0.0-dev-202611061347` | ✓（6.0 MB jar） | 待核（GitHub 识别 `NOASSERTION`，仓库有 LICENSE） | 依赖 `compose.desktop 1.12.1` / `kotlin-stdlib 2.4.20` / `angle-natives 8037.1` / `core-runtime` / `energy-manager` / `decorated-window-core`，**均同版本号** |
| `dev.nucleusframework:nucleus.nucleus-application` | 同上 | ✓ | 待核 | `nucleusApplication` / `DecoratedWindow` |
| `dev.nucleusframework:composenativetray` | 2.1.7 | ✓ | 待核 | 2.1.0 起与 `-app` 拆分，只引本体 |
| `dev.nucleusframework:composewebview` | 1.0.3 | ✓ | **MIT** | Desktop **Tao-only**，WebView2 + DComp；POM 依赖 compose **1.11.1**（需实测） |
| `dev.nucleusframework:nucleus.angle-natives` | 8037.1（稳定） | — | 待核 | 4.5 MB jar，Tao 的 GL 后端传递依赖 |
| `app.cash.turbine:turbine` | 1.2.1 | ✓ | Apache-2.0 | testImplementation |
| `io.github.vinceglb:filekit-dialogs-compose` | 0.17.0（pre-1.0） | ✓ | MIT | Kotlin Foundation Grants 资助 |
| `org.jetbrains.kotlinx:kotlinx-collections-immutable` | 0.5.2 | ✓ | Apache-2.0 | 0.5.x 有 `add→adding` 等重命名，见技能 `kotlin-tooling-immutable-collections-0-5-x-migration` |

> **版本冲突（S0 必须先验的第一件事）**：Decompose 3.6.1 依赖 Essenty **2.7.1**，
> MVIKotlin 4.4.0 依赖 **2.5.0**。Gradle 解析到 2.7.1，而 MVIKotlin 编译于 2.5.0
> （`StoreInstance` 用到 `instance-keeper` 的 API）。2.5 → 2.7 是 minor 跨越，通常二进制兼容，
> **但必须由 spike 实证**。
>
> 另有 Compose foundation 1.9.3 → 我们 1.12.2、Kotlin stdlib 2.3.21 → 2.4.21、
> kotlinx-serialization 1.6.3 → 1.12.0、coroutines 1.10.2 → 1.11.0，均为"取高"，同样需编译验证。
>
> 坐标易错点：`KStateMachine` 是 `io.github.nsk90`（不是 `ru.nsk.kstatemachine`），
> `compose-dnd` 是 `com.mohamedrejeb.dnd`。

### 2.8 `terrakok/kmp-awesome` 全量扫描结论

扫了该列表全部 **22 个分类**。**结论：只有 2 个库值得进 v0.6.0**，其余要么已被架构覆盖，
要么属"有需求时再说"。

**① Turbine 1.2.1**（`app.cash.turbine:turbine`，Apache-2.0）— `testImplementation`
- 用于验证 `Store.state`（StateFlow）与 `Store.labels`（Label 流）的**发射序列**。
- 为什么本轮必须有：S4 拆出 `ChatStore` 后，核心正确性判据就是「dispatch 一串 Intent →
  state 序列正确 + label 恰好发一次」。手写 `TestScope + launch { collect }` 抓不住"多发/少发"。
- 与现有资产互补：573 个**同步纯函数**测试补的是逻辑，Turbine 补的是**异步流序列**，不替换。

**② FileKit 0.17.0**（`io.github.vinceglb:filekit-dialogs-compose`，**MIT**）— `implementation`
- 删掉 5 个 `expect` + 5 个 `actual`：`FilePicker.kt`（`pickExecutableFile` / `pickJsonFile`）、
  `FileDrop.kt`（`pickFiles` / `pickFile` / `pickSaveFile`）
- 必须验三点（进 S0）：① JSON / 图片 / `.tar.gz` 三类 filter 行为；
  ② 取消返回 `null` 与现有 `String?` 约定一致；③ **提权进程里对话框能弹出**
- 若 ③ 不过 → 回退 `expect/actual`（代码留在 git 历史里）

#### 不采纳（逐项理由，避免下次重复讨论）

| 候选 | 分类 | 不采纳理由 |
|---|---|---|
| **Store 5 / store4 / cache4**（Dropbox） | Storage | 与 `CoroutineExecutor` **职责重叠**；两套并存会让"谁负责加载"模糊。Fetch→Store 分层**思想**借鉴进 `TranscriptCache` 即可 |
| **multiplatform-paging** 3.3.0-alpha02-0.5.1 | Architecture | alpha；我们的分页只有"向前插一页 + 保滚动锚点"，已被 `ScrollFollow` / `contentEndGapPx` 覆盖 |
| **compose-stable-marker** | Compose UI | desktop 变体只到 1.0.6，且本质是 `@Immutable` workaround；我们直接用 `androidx.compose.runtime.Immutable` |
| **Alert-KMP** | Device | **不能消除快捷方式依赖，且收益为 0**：desktop 实现只有 `java.awt.SystemTray` 气泡（我们已有 `DesktopTray`）与 `JOptionPane`（模态阻塞），并失去通知按钮与通知中心持久化 |
| **Calf 0.14.0** | Compose UI | 自适应 UI 与 `ChildPanels` 重叠；文件选择已被 FileKit 覆盖 |
| **kmpEssentials 2.8.8** | Architecture | 40+ 模块"一站式"，不如按需选单点库 |
| **Compose WebView Multiplatform** | Compose UI | 与已选的 `composewebview`（Nucleus 自家）重复；且面板不用 WebView（ADR-063） |
| **Voyager / Precompose / Tiamat / Appyx** | 导航 | 已定 Decompose |
| **Kotest / Mokkery / Mockative / MocKMP / Assertk** | Test | 0 KSP；`kotlin.test` + Koin `declareMock` + 手工 fake 已够 |
| **Realm / SQLDelight / KStore / KSafe / KVault / Kotbase / SQLiter / SQLlin** | Storage | 不需数据库或加密 KV：UI 状态在 `state.json`，token 在 `config_custom.json` |
| **Ketch** | Network | 备份下载是一次性 `ByteArray`，无进度/断点续传需求 |
| **Ktorfit / Apollo GraphQL / RSocket / MOKO SocketIo** | Network | 契约是 WebSocket JSON-RPC + REST，ktor 已覆盖 |
| **Uri KMP / kotlinx-io / Okio / Kompress** | Utils / File | desktop 有 `java.net.URI`；`java.io` + 后端 7z 已够 |
| **Napier / Kermit / KmLogging** | Log | 已有 `kotlin-logging` + `logback` |
| **Compose Rich Editor / cascade-editor / hyphen / Compose Text Editor** | Compose UI | ADR-005 不做内置编辑器；composer 硬约束用 `BasicTextField` |
| **MaterialKolor** | Compose UI | 已有 `AppPalette`（ADR-055） |
| **Compose Cupertino / miuix / RikkaUi / Carbon / compose-fluent-ui / Compose Unstyled** | Compose UI | M3 原生组件路线（硬约束 12） |
| **Window Size Class** | Compose UI | 宽度判据就是 `width < 840.dp` 两行，且 `ChildPanels` 的 mode 由它驱动 |
| **ComposeShield / KMP Maps / KMP Sharing / KMPAuth / MediaPlayer-KMP / MediaMP / Kottie / ZoomImage / Crayfish / Landscapist / Kamel / Sketch 等** | Compose UI / Device | 无需求（桌面端 / 非目标领域） |
| **LazySurface / DrawBox / Haze / KMPLiquidGlass / reveal / peeking / BottomSheet 类** | Compose UI | 纯装饰或非目标交互；M3 有原生对应物 |

#### 留作 0.7 / 有产品需求时再评估

| 候选 | 版本 | 触发条件 |
|---|---|---|
| **Vico 2.5.2** / **Koala Plot** / **charts** | 2.5.2 | 「用量与成本」页要做趋势图时 |
| **Table-KMP** | — | 用量 / 凭据池 / 会话统计要真数据表格时 |
| **QRose 1.3.1** | 1.3.1 | 远程配对页要展示二维码时 |
| **colorpicker-compose** | — | 终端配色要色轮时 |
| **Calendar** | — | cron 新建任务要日历选时间时 |
| **rebugger** | — | 做重组性能优化时 |
| **Kontrol** | — | 要「隐藏手势打开调试菜单」时 |
| **Kotlin Multiplatform AppDirs** | — | 需要按平台惯例分 config/cache/data 三目录时 |
| **KLIP 0.4.1** | 0.4.1 | 想给 `ChatState` / `SessionRuntime` 做 `toString` 快照断言时 |
| **`mvikotlin-timetravel`** | 4.4.0 | 时序 bug 排查成本压不下来时（已定 0.7） |
| **`nucleus.screen-capture`** | 3.0 稳定版 | 需要「窗口/桌面截图」功能且它出了稳定版时（不是浏览器面板） |

### 2.9 快捷方式依赖的真实边界（回应「Alert-KMP 能否消除对快捷方式的强依赖」）

**结论：Alert-KMP 不能，而且当前的依赖已经不算「强」。**

```
WinRT Toast（带按钮 / 进通知中心）
  └─ 要求 AUMID 注册在「开始菜单」的 .lnk 里（桌面 / 其它目录 / 纯注册表键都不行）
       └─ NativeNotifications.desktop.kt（502 行，nucleus ShortcutPolicy.REQUIRE_CREATE）
            └─ ShortcutScan.kt（369 行，扫 Programs\ 下全部 .lnk）
                 └─ ShortcutAumidWriter.kt（197 行，PowerShell+COM 补写 PKEY_AppUserModel_ID）
                      + ProcessAumid.kt（41 行）
```

三个已成立的缓解事实：

1. **默认不创建**：`AppPreferences.nativeNotifications` 只有存了 `"on"` 才为真。
2. **默认路径零快捷方式**：`NotificationInbox` + `NotificationOverlay`（Compose 自绘卡片，
   支持按钮）完全不碰 AUMID。
3. **自绘窗已覆盖「最小化/托盘」场景**：`NotificationOverlay.kt` 是**独立窗口**，由 `Main`
   在应用作用域里调用。**迁 Tao 后这条要重新验证**（不再是 `application {}` 的第二个 `Window`）。

免快捷方式只有两条路，都**不在 v0.6.0**：
- **旧 WinRT + 只做 COM 注册**（Toolkit 做法去掉快捷方式）——**待实测**，成立则收益最大
  （可删 607 行 + 381 行测试）
- **Windows App SDK `AppNotificationManager`** —— 官方免快捷方式，但**提权应用不支持**，
  且 Maven 无 JVM 封装（要自写 JNI）+ 运行时 30–40 MB

→ 登记为 [`待修复问题.md`](../待修复问题.md) **#21**。

### 2.10 桌面壳：Nucleus Tao（ADR-062）

#### 为什么必须一次迁干净

Nucleus **3.0 移除了全部 AWT 窗口后端**（`decorated-window-jni` / `-jbr` / `-awt` 已删），
只剩 `decorated-window-tao`。要 `NativeView`（浏览器面板的唯一实现路径）就必须站在这条路上。

实测事实（2026-11-13）：

| 项 | 结论 |
|---|---|
| 版本 | `nucleus.decorated-window-tao` 稳定版只到 **2.5.18**；3.0 只有 `3.0.0-dev-*`（末版 `202611061347`） |
| 仓库 | 441★ / 32 fork / 19 贡献者（主力 `kdroidFilter` 3,235 提交）/ 2026-02 建仓 / 末次 push 2026-11-07 |
| jar 体积 | 6.0 MB，1,149 条目 |
| Windows 原生库 | **仅 629 KB**：`nucleus_tao.dll` 540 + `nucleus_tao_windows_native_view.dll` 30 + `_gl` 23 + `_windows_deco` 24 + `_dnd` 12 |
| ANGLE | `nucleus.angle-natives:8037.1`，jar 4.5 MB（传递依赖） |
| 依赖 | `compose.desktop 1.12.1` / `kotlin-stdlib 2.4.20` / `core-runtime` / `energy-manager` / `decorated-window-core`（同版本号） |
| IME | Tao 自带 `TaoImeSession`（preedit / commit 路由，#558） |
| 触摸 | Windows 走 DirectManipulation（#706），触控板/捏合有独立处理 |

**与我们的版本兼容**：我们是 `compose 1.12.2` / `kotlin 2.4.21`，取高即可，S6 实测。

#### 迁移边界（重要）

**只迁「窗口层」，不迁「工具类」。**

| 层 | 现状 | 处置 |
|---|---|---|
| 应用入口 | `application {}` | → `nucleusApplication(backend = Tao) { … }` |
| 主窗口 | `Window(onCloseRequest, state)` | → `DecoratedWindow` + `nucleusWindow` |
| 窗口状态持久化 | `rememberWindowState` + `preferences.windowBounds` | 保留偏好读写，换成 Tao 的几何 API |
| 托盘 | 自研 Swing `DesktopTray`（10 个 AWT 类） | → `composenativetray 2.1.7`（**已解决 CMP-1936 中文乱码**） |
| 错误弹窗 | `JOptionPane.showMessageDialog` | → 自绘 Compose 对话框 |
| 通知浮窗 | 独立 `Window` | → `HostedWindow` 或 Tao 的 popup host |
| 剪贴板 | `Toolkit.systemClipboard` | **保留**（无窗口工具类） |
| 打开链接 | `Desktop.browse` | **保留** |
| 文件选择 | `java.awt.FileDialog` | → **FileKit**（S0 已验证则一并替换） |
| 前台判定 | `Window.getWindows()` | **必须换**（依赖 AWT 窗口集合） |
| 托盘气泡通知 | `TrayIcon.displayMessage` | **必须换**（`composenativetray` 或自绘窗） |

→ 结论：**AWT 从 10 个文件降到 3 个（剪贴板 / 打开链接 / 通知），且 `java.desktop` 仍需保留**。

#### 必须保住的既有能力（不许回归）

| 能力 | 现状实现 | 迁 Tao 后的要求 |
|---|---|---|
| 单实例 | 自研 `CreateMutexW` + `--silent` 激活 | **改用 Nucleus**（ADR-066）：`enableSingleInstance = true` 含静默实例；删自研 333 行 |
| 提权重启 | `ShellExecute runas` + `--elevated` | 入口换壳后仍工作 |
| 开机自启 | 计划任务 `RunLevel=HighestAvailable` + `<Arguments>--silent</Arguments>` | **不变**（不归 Tao 管） |
| 托盘常驻 | `closeToTray` + `windowVisible` | 行为不变 |
| 触摸滚动 | `Modifier.touchDragScroll` | 改由 DirectManipulation 接管后**重测**；保留退路 |
| 中文输入 | BasicTextField + 系统 IME | `TaoImeSession` 端到端验 |
| 标题栏/主题 | M3 + `AppPalette` | `DecoratedWindow` 的原生标题栏要与暗色协调；必要时用自绘标题栏 |

#### 单实例：采用 Nucleus（ADR-066）

> **结论**：`enableSingleInstance = true`（**静默也占锁**）；`DecoratedWindow(visible = !silent)`
> 控制首启不显示；`SingleInstanceManager.configuration` 按 `<home>` 隔离便携包。
> **删掉自研 `SingleInstance.kt`（250 行）+ `SingleInstanceTest.kt`（83 行）。**
>
> 初版判断"Nucleus 做不到"有两条是错的：`DecoratedWindow(visible=)` 与
> `SingleInstanceManager.configuration` 都是**公开 API**。属实的只有"`--silent` 二次触发会抢前台"
> （场景不可达：任务只有 `<LogonTrigger>` 无重复）与"唤醒失败无反馈"（**用户已接受**）。
>
> **待验**：提权与非提权实例是否能看到彼此（Nucleus 用用户目录锁文件，提权进程的
> `AppData` 可能是 `systemprofile` → 路径不同 → 允许双开）。**不过就退回自研。**

#### 原判定方法（留档）

S6 第一件事，不看它能关掉就**不要继续**：

1. 读 `nucleusApplication` 的签名与参数（`nucleus.nucleus-application` 3.0 dev 的源码）；
2. 若无开关 → 看它是否用命名 mutex/文件锁；能否用同名句柄预占使其"以为已有实例"；
3. 两条都不行 → **保留自研单实例并让 `nucleusApplication` 的单实例失效**（社区 issue / fork 补丁），
   或退回 Compose Desktop 窗口壳（此时 S7 的浏览器面板改用 L0 的 CDP 像素流兜底）。

### 2.11 内嵌槽位（ADR-063 / ADR-064）

三个「外部内容进窗口」的需求，共用同一个机制 `NativeView`，但**边界完全不同**。

#### 共同机制

```
NativeView(factory = { nucleusHwndPlatformView(handle = { hwnd }) }) {
    // content 槽：Compose 画的覆盖层（工具栏 / 加载遮罩 / 关闭按钮）
}
```

Windows 实现 = `SetParent` 转 `WS_CHILD` + `SetWindowPos` 定尺寸 + `SetWindowRgn` 圆角 +
**`WindowsNativeViewBlendingOverlay`（DirectComposition）让 Compose 画在子窗口之上**。

> 这一层是 Nucleus 的核心价值：Win32 子窗口**永远**画在父窗口之上，没有 DComp overlay
> 就压不住。自己写 `SetParent` 只能做到"嵌进去"，做不到"上面还能叠 UI"（实测见 ADR-063）。

#### ① 浏览器面板（v0.6.0 做）

- **对象**：payload 自带的 Chromium（`hermes/tools/chromium-1208/chrome-win64/chrome.exe`），
  **我们自己拉起**，不是用户的 Chrome，也不是 agent 的临时实例
- **形态**：headed（有窗口）→ 拿到顶层 `HWND` → `NativeView` 嵌进面板
- **同时**：启动参数带 `--remote-debugging-port=0` → 读 `DevToolsActivePort` → 写
  `browser.cdp_url`，让 agent 操作的就是**这一块**（所见即所操作）
- **为什么不用 WebView / 不用像素流**：见 ADR-063
- **安全**：只绑 `127.0.0.1`、随机端口、临时 `--user-data-dir`、退出杀进程树；
  提权时必带 `--do-not-de-elevate`（否则 `Chrome exited early`，见
  [`详细设计/工程与代码/07-提权与自启动.md`](../详细设计/工程与代码/07-提权与自启动.md) §13）

#### ② 插件宿主（v0.6.0 只搭骨架，不做插件功能）

**形态**：一整块 WebView 占据一个 pane，里面跑**我们自己的宿主页面**（HTML/JS）。

```
┌─ Compose（我们的窗口） ─────────────────────┐
│  rail │ 列表 │ 详情 │  [WebView 槽位]        │
└──────────────────────────┬─────────────────┘
                           └─ WebView（Tao NativeView + WebView2）
                               └─ 宿主页面（我们写的 shell）
                                   ├─ 布局容器（pane 分割）
                                   └─ 插件（官方 plugin.js，后续接入）
```

- **所有插件只在 WebView 内渲染，不与 Compose UI 融合**（用户已定稿）
- **不重写插件机制**：插件仍是官方的 `plugin.js` / manifest，由宿主页面按官方机制加载；
  我们只提供**容器 + 桥**（`window.hermes.*` 的 RPC / 主题 / 通知等）
- **本期交付**：槽位能加载本地宿主页面 + 双向桥打通。
  **不做**：插件发现、manifest 解析、生命周期、市场、权限模型
- 底层二选一（S7 spike 定）：
  - `composewebview 1.0.3`（MIT，Tao-only，WebView2 `CompositionController` + DComp）
  - 自己用 `nucleusHwndPlatformView` 塞 WebView2 controller（composewebview 内部就是这么做的）
- 前提：本机 **WebView2 Runtime 157.0.4341.46 已装**（实测）；发布时要检测并引导安装

#### ③ 轻量编辑器（v0.6.0 不做，只留位）

- **不内嵌 VSCode**：Electron 多进程 IDE，标题栏/活动栏/侧边栏去不掉，`Ctrl+P` 等快捷键战争，
  数百 MB 体重，单实例抓不到用户的窗口。**功能齐全的代价就是那套壳**（ADR-064）
- **候选方向**：单进程原生小编辑器（Notepad4 / SciTE / Notepad-- 一类），几 MB、纯 Win32 窗口
- 若将来做，**机制已就绪**：它同样是 `HWND` → `NativeView` 一个槽位，无需新架构
- 在此之前，[`platform/Editors`](../../composeApp/src/desktopMain/kotlin/dev/shinglem/hermes/feature/editors/)
  的「跳外部编辑器」**保持不变**（ADR-005 的落地）

---

## 3. 阶段（每段带状态与退出条件）

| 阶段 | 内容 | 状态 | 退出条件 | 版本 |
|---|---|---|---|---|
| **S0** | 依赖与基座验证 | 🟡 **部分完成**（见下方「S0 实测记录」） | 编译 + 测试绿；4 项 spike 全通过并录 ADR | v0.6.0 |
| **S1** | 清理违约与闲置 | ✅ **完成** | 编译 + 测试绿（578 全绿）；coil 只剩 `markdown-one` 一份 | v0.6.0 |
| **S2** | 状态层基座（Component + Store 模板） | 🟡 模板已验证（Security 完成，余 6 域照抄） | `rg 'private var loaded = false'` 为 0；7 域能在纯 JVM 测 `dispatch` 并断言 state | v0.6.0 |
| **S3** | 传输与契约收敛 | ⏳ 未开始 | 回归绿；`4001` 家族有单测钉住 | v0.6.0 |
| **S4** | Chat 域重写 | ⏳ 未开始 | `ChatViewModel.kt` 删除；最长文件 ≤ 400 行；`SessionShadow`/`SessionSwitchPlan` 删除 | v0.6.0 |
| **S5** | Shell 与导航重写 | ⏳ 未开始 | `AppShell.kt` 删除；`SettingsDetailPane` 参数 ≤ 3；`viewModel {` 全仓库为 0 | v0.6.0 |
| **S6** | 桌面壳迁 Tao | ✅ **完成**（触摸无法验证，IME 粗测通过） | 六项回归（单实例/提权/自启/托盘/触摸/IME）全过 | v0.6.0 |
| **S8** | 收尾 | ⏳ 未开始 | §6 验收口径 1–19 全过 | v0.6.0 |
| **S7** | 内嵌槽位（浏览器面板 + 插件宿主骨架）+ 编辑器槽位与选型 | ⏳ 未开始 | 浏览器面板可用；宿主页面能加载且桥通；编辑器选定并留槽 | **v0.6.1** |

> **v0.6.0 的范围就是 S0–S6 + S8**（完整对齐 v0.5.x 功能）。
> **S7 与编辑器明确推后到 v0.6.1**，v0.6.0 不为其预留半成品代码。
>
> **可切割**：S6 若在 S0 的第 4 步（Tao 可行性 / 单实例锁 / 中文 IME / 触摸 / AWT 共存）不过，
> **整段推到 v0.6.1**，v0.6.0 只交付 S0–S5 + S8（此时桌面壳仍为 Compose Desktop 自带窗口）。

### S0 · 依赖与基座验证

**状态（2026-11-13）**：`⏳ 进行中` —— 第 1、2、3 步与部分第 4 步已有**实测结论**（见下「S0 实测记录」）；
第 4 步的**中文 IME** 与 **FileKit 提权对话框**仍需人工在真机点一遍。

1. **加依赖**（`gradle/libs.versions.toml` + `composeApp/build.gradle.kts`）：
   - 加 `decompose` + `extensions-compose` 3.6.2、`mvikotlin` + `-main` + `-extensions-coroutines` 4.4.0、
     `koin-*` 4.2.2、`kotlinx-collections-immutable` 0.5.2、`filekit-dialogs-compose` 0.17.0、
     `turbine` 1.2.1（testImplementation）
   - **`mvikotlin-main` 必须放 `commonMain`**：核心 jar 只有接口，`DefaultStore` /
     `DefaultStoreFactory` 在 `-main` 里（javap 实证）
   - **不要在本步移除 `adaptive-*` / `coil-*`**：`AdaptiveListDetail.kt` 还在用前者（S5 才删），
     后者属 S1 —— 本步只加不改
   - **验 `./gradlew :composeApp:compileKotlinDesktop`**：已通过（见实测记录）
2. **最小可运行 spike**（`ChildStack` + `ChildPanels`）
   - 已落成 `commonTest/architecture/RefactorBaseSpikeTest.kt`（5 个用例全绿）
3. **Store 接线 spike**
   - 同上，`Store 放在 instanceKeeper 里，重复获取返回同一实例` 与
     `Component 被压栈到后台后 Store 仍在推进` 两条即为其判据
4. **Tao 可行性 spike**
   - 已落成 `desktopMain/shell/spike/TaoSpikeMain.kt` + 临时 Gradle 任务 `:composeApp:taoSpike`
   - 窗口起得来、中文渲染正常、AWT 剪贴板可用（已实测，见下）
   - **剩下**：微软拼音端到端、触摸滚动、FileKit 提权对话框
5. `test_net`：给 13 个 Repository 抽 `interface`（实现类改名 `XxxRepositoryImpl`）
6. `di/Modules.kt`（`appModule` / `componentModule`），`Main.kt` `startKoin`；
   `AppContainer` 保留为非 Composable 侧门面、`close()` 顺序不动
7. `commonTest` 加两个测试：`ModuleVerificationTest`（`verify()`）、
   `ImportBoundaryTest`（R1–R4）
8. **FileKit 三点验证**（不过就回退 `expect/actual`）：filter 行为 / 取消返回 `null` /
   提权进程里对话框能弹出

### S0 实测记录（2026-11-13，本机）

| # | 项 | 结论 | 证据 |
|---|---|---|---|
| 1 | 依赖可解析、可编译 | ✅ **通过** | `:composeApp:compileKotlinDesktop` BUILD SUCCESSFUL |
| 2 | **Essenty 2.5.0 → 2.7.1 二进制兼容** | ✅ **通过** | `dependencyInsight` 显示 `mvikotlin-jvm:4.4.0` 的 `essenty 2.5.0 -> 2.7.1`（取高）；`RefactorBaseSpikeTest` 在真实 2.7.1 上跑通 `instanceKeeper.getStore` |
| 3 | **压栈后 Store 仍推进**（Y 方案立论基础） | ✅ **通过** | `ChildStack` 压栈后：Main 实例 `assertSame` 不变、Store `assertSame` 不变、后台 `accept()` 仍生效；生命周期 `RESUMED → CREATED → RESUMED` |
| 4a | **`nucleusApplication` 单实例锁可关** | ✅ **通过（源码级）** | `NucleusApplication.kt` 公开参数 `enableSingleInstance: Boolean = !ExecutableRuntime.isDev()` —— 传 `false` 即让位给自研 `CreateMutexW` |
| 4b | Tao 空壳窗口能起、能渲染中文 | ✅ **通过** | `:composeApp:taoSpike` 起窗口，`PrintWindow` 抓到完整中文界面（14 色，非黑屏）；进程 188 MB |
| 4c | **AWT 工具类在 Tao 进程里可用** | ✅ **通过** | 探针内 `Toolkit.systemClipboard` 写入读回一致（`clipboard ok=hermes-tao-probe`） |
| 4d | 微软拼音在 Tao `BasicTextField` 里 | ⏳ **待人工** | 需真人在窗口里打「你好世界」；本机无自动化手段（UIPI + 焦点抢占限制） |
| 4e | Tao 触摸滚动 | ⏳ **待人工** | 需触屏或触摸板手动试 |
| 5 | 现有 578 个测试 | ✅ **全绿** | `tests=578 failures=0 errors=0`（573 原有 + 5 新增 spike） |

**测试耗时基线**：`desktopTest --rerun` **64.7 秒**（v0.5.8 基线）→ 增量 48.7 秒 / 全量 90.8 秒（含编译）。

**S0 结论**：**Y 方案（Decompose + MVIKotlin + Koin）与 Tao 路线均成立**。
两个原本被列为"可能让整段推后"的阻塞项（Essenty 版本交叉、`nucleusApplication` 单实例锁）
都已解除。剩下的人工项（中文 IME / 触摸）**只影响 S6 的体验验收，不影响 S1–S5 的开工**。

### S1 · 清理违约与闲置（低风险、高收益，与 S0 可并行）

1. `RestClient` 去掉 `feature.session.SessionSummary`：`SessionSummary` 下沉 `domain/model`
2. 移除 `coil-*` 三依赖；`MarkdownRenderTest` 兜底确认图片仍渲染
3. 装上 `ContentNegotiation`；`RestClient` 提供 `getAs<T>` / `sendAs<T>`，先迁 3 个 Repository 验证形状
4. 删 7 份私有 `Throwable.describe()`，统一 `AppError.toNotice()`
5. 删 `SessionListPane.pad2`，统一 `TimeFormat`
6. `AlertDialog` 14 处换 `ConfirmDialog` / `PromptDialog`
7. `MessageItem` / `ChatPane` 加 `@Preview`（当前 0 处）

### S2 · 状态层基座（Component + Store 模板）

> **实施状态（2026-11-14）**：🟡 模板已验证 —— **Security 域完成**（其余 6 域照抄）。
>
> 已落地的基座文件（后续各域直接用，不要各自发明）：
>
> | 文件 | 作用 |
> |---|---|
> | `component/StoreAdapters.kt` | `StoreStateSource`（Store→StateFlow）、`labelsAsFlow()`（Label→Channel 流） |
> | `component/StoreHolder.kt` | Store + 它的 `CoroutineScope` 装进 `InstanceKeeper`，`onDestroy` 一并收 |
> | `component/RememberComponentContext.kt` | S2 过渡用根 `ComponentContext`（S5 由 RootComponent 接管后删除） |
>
> **四条踩出来的规矩**（详见 ADR-069）：
> 1. `Store` 不能直接进 `InstanceKeeper`（`TYPE_INTERSECTION_AS_REIFIED_ERROR`），必须包 `StoreHolder`；
> 2. `Store.init()` 必须在 `accept` 之前（未 init 时 accept 被**静默丢弃**）；
> 3. `CoroutineExecutor` **没有** `execute { }`，只有 `scope` —— 写 `scope.launch { }`；
> 4. `Reducer.reduce` 的参数名必须是 `msg`。
>
> **`Appear` 必须带 `connected: Boolean`** —— 第一版在 `init` 里直接 `Appear`，实测打开设置页会先闪
> 一条红色的「连不上后端服务」（`REST /api/config failed: Connection refused` 早于
> `gateway connected` 3 秒）。迁移前 UI 的 `LaunchedEffect(profile, isConnected, mode)` 就是干这个的，
> 接管生命周期时**不能把这个约束丢掉**。

1. 落一套可复制的模板（先用最简单的域验证）：
   ```
   component/<域>/XxxComponent.kt        接口 + DefaultXxxComponent(ComponentContext by …)
   component/<域>/store/XxxStore*.kt     State / Intent / Result / Label / Reducer / Executor
   ui/<域>/XxxContent.kt                 只接 Component（自己 collectAsState）
   ```
2. 迁移顺序（从简到繁）：`Security` → `ProviderKeys` → `CustomEndpoint` → `Moa` →
   `ModelSettings` → `Profiles` → `Maintenance`
   （`Maintenance` 915 行管 7 个领域，**先拆成 7 个 Store 再套模板**）
3. 每迁一个域：删掉手写 `setProfile` / `onVisible` / `loaded`，改用生命周期 + `Intent`
4. `@Immutable` + `ImmutableList` 铺到所有已迁移 State
5. 每个域补 1 个 `Reducer` 纯函数单测 + 1 个 `Component` 单测
   （`DefaultComponentContext` 可在纯 JVM 造出来，无需 Compose）

### S3 · 传输与契约收敛

1. 全部 Repository 走 `getAs<T>` / `sendAs<T>`；54 处手写 `JsonElement` 降到 < 10
2. `CapabilityRepository` 的私有 `withProfile` / `decodeList` / `sendAck` 收进 `RestRepository` 基类
3. `profile: String?` 143 处首参 → `ProfileScope` 值类型（`@JvmInline value class`），
   在类型上堵住 0.5.4 修的 `4001 not_found` 家族
4. 审批/澄清的 6 个待决表收敛成 `PendingRequests`（`Map<RequestId, PendingRequest>` + `PendingState`）。
   **若此处仍难维护，才是 KStateMachine 的评估时机**（不在本轮引入）

### S4 · Chat 域重写（本版最大的一块）

```
component/chat/
├── ChatComponent                接口：state: StateFlow<ChatState>, effects: Flow<ChatEffect>, onIntent()
├── DefaultChatComponent         ComponentContext by → instanceKeeper 里取 Store
├── ChatStoreFactory             reducer / executorFactory / bootstrapper
├── ChatContract.kt              Intent / Result / Label(=ChatEffect) 三个 sealed interface
├── ChatReducer.kt               纯函数；吃掉 StreamSegments + ChatViewModel 内联 + SessionShadow 三份
├── ChatExecutor.kt              suspend 调 Repository；用 ChatRepository + SessionRuntimeStore
├── SessionRuntimeStore.kt       Map<SessionKey, SessionRuntime>（含 runtimeId 反向索引）
│                                SessionRuntime{runtimeId, fence, cwd, lastEventAt, dirty}
└── ChatBootstrapper.kt          resume / 补拉审批 / 订阅 gateway 事件
```

要点：
- **`SessionRuntimeStore` 内部维护 `runtimeId → SessionKey` 反向索引**，消灭 4 处 O(n) 反查
- **8 个平行 map 收敛成 1 个 `SessionRuntime` 值对象**；`cache`/`savedStates`/`dirtyKeys` 归 Component 层
  （Decompose 生命周期已管住"切走不销毁"，`SessionShadow` 整个文件删除）
- **流式状态机只留 1 份**（`ChatReducer`）；`SessionShadow.kt` 与 `SessionSwitchPlan.kt` 删除
- **5 个 `var onXxx` → `ChatEffect`（Label）**
- **拆出独立 Store**：`ChatModelStore`（模型目录/切换/推理强度/fence 重试）、
  `ChatUsageStore`（用量 + breakdown）、`ChatBackgroundStore`（后台任务轮询）
- `ChatState` 用 `ImmutableList`；`ChatItem` / `StreamSegment` 加 `@Immutable`

### S5 · Shell 与导航重写

1. `RootComponent` + `ChildStack`（会话/能力/设置）+ `ChildPanels`（Main/Details）
2. `AppShell`（1,071 行）拆成：
   - `AppRoot`（≤80 行）：`KoinApplication` + `RootContent`
   - `RootContent`：`Row { ProfileRail; ChildStackView }`
   - `SessionsContent` / `CapabilitiesContent` / `SettingsContent` 各自持有子 Component
3. 消除大部分 `LaunchedEffect`（Component 生命周期 + Store Executor 接住原来在 Composable 里做的事）
   - 通知分发搬到 `component/notification/NotificationComponent`
4. `SettingsDetailPane` 62 参数 + 24 分支 → `Map<SettingsPageKey, @Composable (SettingsContentScope) -> Unit>`

### S6 · 桌面壳迁 Tao

> **实施状态（2026-11-13）**：✅ 主体完成 + 两项实测修正。实际做法与下面原计划有差异，逐条标注。
>
> | 步骤 | 状态 |
> |---|---|
> | 1. Main.kt 拆 `shell/` | 🟡 部分（`shell/entry` + `shell/tray` + `shell/window` 已建，`Main.kt` 仍偏大，S8 再拆） |
> | 2. `application{}` → `nucleusApplication{}` / `Window` → `DecoratedWindow` | ✅ |
> | 3. 关掉核内单实例锁、保留自研 | ❌ **改为采用 Nucleus 单实例**（ADR-066 初版被推翻） |
> | 4. Swing 托盘 → `composenativetray` | ✅（用户确认托盘正常） |
> | 5. `JOptionPane` → 自绘对话框 | ⬜ 仍用 `JOptionPane`（只在致命错误时弹，可留） |
> | 6. `NotificationOverlay` → Tao | ✅ `DecoratedWindow(transparent)`（ADR-067；`SatelliteWindow` 无 `transparent` 参数） |
> | 7. `AppFocus` 窗口焦点 | ⬜ 未动 |
> | 8. 六项回归 | 🟡 单实例/提权自启/托盘 ✅、IME 粗测 ✅、触摸 ❌ 无设备 |
>
> **两项实测修正（原计划没有）**：
> - **标题栏必须自绘**（ADR-068）：Tao 的 `DecoratedWindow` 在 Windows 上无边框，不调 `TitleBar`
>   就没有标题栏也不能拖动；且 `TitleBar` 不读 `MaterialTheme`，配色要显式传 `TitleBarStyle`。
> - ~~第二实例在配置解析前就退出~~ **已回退（2026-11-14）**：先加过 `SingleInstanceProbe` 提前探测，
>   后来证实"启动慢"是**首实例冷启 daemon**（4 次实测 30.9/32.4/32.6/33.5s；热复用 ~1.5s），
>   与单实例判定无关。现在只设 `configuration`，判定全交给 `nucleusApplication`。
>   **不要再往这个方向优化**（绕过了 `watchForRestoreRequests` 装配，会让唤醒失效）。

1. `Main.kt` 拆成 `shell/` 包：`DesktopEntry`（参数/单实例/提权自启）+ `HermesApplication`（窗口与组合）
2. `application {}` → `nucleusApplication(backend = Tao)`；`Window` → `DecoratedWindow`
3. **关掉核内单实例锁**（§2.10 判定法），保留自研 `CreateMutexW` + `--silent` 语义
4. `DesktopTray`（Swing）→ `composenativetray 2.1.7`；保持对外形状
   （`install()` / `dispose()` / `menu()`），`SystemNotifications.consumePendingAction()` 顺序不变
5. `JOptionPane` → 自绘 Compose 错误对话框
6. `NotificationOverlay`（独立 `Window`）→ Tao 的 popup host 或 `HostedWindow`
7. `AppFocus.desktop.kt` 的 `Window.getWindows()` 兜底 → Tao 的窗口焦点 API
8. **六项回归**：单实例（含 `--silent` 激活）/ 提权重启 / 开机自启 / 托盘常驻 /
   触摸滚动 / 中文 IME

### S7 · 内嵌槽位（**v0.6.1**，不在 v0.6.0 范围）

> v0.6.0 **不写 S7 的任何代码**。本节留作交接：v0.6.0 合入 `main` 后，
> 在 v0.6.1 分支上按此实施。详细落地见
> [`13-重构落地设计 §10`](../详细设计/工程与代码/13-重构落地设计（v0.6.0）.md)。

**7.1 浏览器面板**

1. `shell/browser/BrowserProcess.kt`：起 payload Chromium
   （`--remote-debugging-port=0` / `--do-not-de-elevate` / 临时 `--user-data-dir`），
   读 `DevToolsActivePort` 拿 port + `webSocketDebuggerUrl`，轮询等 `MainWindowHandle` 非 0
2. `shell/browser/BrowserPanel.kt`：`NativeView` + `nucleusHwndPlatformView { hwnd }`，
   `content` 槽放工具栏（地址 / 刷新 / 关闭）与加载遮罩
3. `cdp_url` 写后端（复用现有 `BrowserArgsReconciler` 的 `PUT /api/config` 通道）；
   关闭面板时杀进程树 + 清 `cdp_url`（`CdpProbe` 自愈已就绪）
4. **验点**：agent 每次点击实时可见；resize / 最小化 / DPI 100·150·200% 不裂；
   提权下可用；关面板后无残留进程；Compose 工具栏能压在浏览器之上

**7.2 插件宿主骨架（只搭架子）**

1. `shell/plugins/PluginHostView.kt`：`NativeView` + WebView2 槽位（S7 spike 定用
   `composewebview` 还是自写 controller）
2. 宿主页面：本地 HTML/JS（`resources/plugin-host/`），只实现「pane 容器 + 与原生桥」
3. 桥：`window.hermes.*` 最小面（`invoke(method, params)` / 主题 / 版本），
   原生侧由 `PluginHostBridge` 接 RPC
4. **验点**：宿主页面能加载并显示；JS → Kotlin → JS 一个来回通；WebView2 缺失时降级为提示卡片
5. **不做**：插件发现、manifest、生命周期、市场、权限

### S8 · 收尾

1. 删掉 `androidx.lifecycle.viewmodel.*` 依赖（若还有引用）
2. 硬编码文案 100 处 → `strings.xml`
3. `runCatching` 逐处审（57 处），`CancellationException` 全部重抛
4. 单测：8 个域各至少 1 个 Component 测试 + 1 个 Reducer 测试
5. 文档同步：`03-architecture.md` 分层图、`02-selection.md` 依赖表、`07-decisions.md` ADR 状态
6. `CHANGELOG.md` 写 0.6.0 条目（重构为主，行为变化逐条列）
7. **v0.6.0 合入 `main`**（`feature/v0.6.0-refactor` → PR）

---

## 4. 明确不做（避免范围失控）

- 不引 **navigation3 / Voyager / Circuit / KStateMachine**（理由见 §2.7）
- 不引 **compose-dnd**（三处拖拽都不属它范畴）
- 不引 **`mvikotlin-timetravel`**（0.7 评估）
- 不引 **Koin Annotations / KSP**（项目零 KSP）
- 不引 **Detekt / ktlint**（会一次报上千条历史问题；关键约束靠架构测试覆盖）
- 不用 Decompose 的 **`ChildItems`**（alpha；"会话并行"用 `SessionRuntimeStore` 更直接）
- 不把 `markdown-one` / `terminal` 拆成独立发布模块
- 不动 `modules/terminal-bossterm` 上游 fork
- **v0.6.0 不做 S7**（浏览器面板 / 插件宿主骨架）—— 全部推后到 v0.6.1
- **v0.6.0 不引入任何编辑器**，也不为编辑器留半成品槽位
- **不做插件功能**（发现/加载/市场/权限）—— 且**不自研插件机制**（插件仍走官方 `plugin.js`）
- **不内嵌 VSCode**
- **不改通知栈**（`ShortcutScan` / `ShortcutAumidWriter` / `ProcessAumid` / `NativeNotifications`
  逻辑不动，只允许搬到新包）；AUMID 免快捷方式见 `待修复问题.md` #21
- 不动**单实例 / 提权 / 自启**语义（S6 只换窗口壳，这三条一行不改）
- 不做视觉重设计（ADR-055 的色板不动）

---

## 5. 风险

| 风险 | 缓解 |
|---|---|
| **Essenty 版本冲突**：Decompose 3.6.1 要 2.7.1，MVIKotlin 4.4.0 编译于 2.5.0 | S0 第 1 步先只加依赖编译；不通过则调版本配对，最差退回方案 X |
| **Decompose 编译基线 Compose foundation 1.9.3 → 我们 1.12.2** | S0 编译验证；`Children`/`ChildPanels` API 面窄，风险可控 |
| **`ChildPanels` 是 experimental** | 已获准；**收在一层薄封装**（`ui/shell/PanelsHost.kt`），变 API 时只改一处 |
| **Y 方案的立论基础未实测**：「back stack 里 Component 不销毁、继续跑」 | S0 第 3 步专门验证；不成立则整个 Y 的前提塌了，退回 X |
| **Nucleus 3.0 无稳定版 + 单人主力** | ① 版本在 `libs.versions.toml` 钉死 dev 号；② 全部接触面收在 `shell/`，必要时 vendor / fork 自己打补丁；③ S0 第 4 步不过就整段推后 |
| ~~`nucleusApplication` 的核内单实例锁与自研锁冲突~~ | **已消除**：改用 Nucleus 单实例（ADR-066），自研 333 行删除 |
| **提权/非提权实例可能互相看不见**（新增） | ~~Nucleus 用用户目录锁文件，提权进程的 `AppData` 可能是 `systemprofile` → 路径不同 → 允许双开~~ **已排除**：`ConfigPaths.resolveHome()` 用 `jarDerivedHome()` 从 jar 位置反推，与进程权限无关，两者算出同一 `<home>` → 同一锁文件 |
| **Tao 触摸/IME 回归** | S0 第 4 步 + S6 第 8 步各验一次；保留 `Modifier.touchDragScroll` 退路 |
| **Nucleus 许可证待核**（GitHub 识别 `NOASSERTION`） | 引入前读 LICENSE 原文；不合规则不引入（`composenativetray` 同批） |
| **`NativeView` 子窗口在 resize/DPI 切换下裂** | **v0.6.1** 的验点；不通过则浏览器面板退 L0（CDP 像素流） |
| **composewebview POM 依赖 compose 1.11.1（我们 1.12.2）** | **v0.6.1** spike 实测；不通过改自写 WebView2 controller |
| **WebView2 Runtime 依赖** | **v0.6.1** 事项；本机已装（157.0.4341.46），发布时检测 + 引导安装 |
| MVIKotlin「数据流只在 Main thread」与现有 `Dispatchers.Swing` scope | `CoroutineExecutor` 用 UI scope；`mvikotlin-main` 的断言在测试里暴露问题 |
| 16 个 Component 重写引入行为回归 | 573 现有测试 + 每域新增 Reducer/Component 测试；按域分 16 次小提交 |
| Decompose / Nucleus 均非 JetBrains 官方 | 单点封装：Decompose 只在 `component/` + `ui/`；Nucleus 只在 `shell/`；`domain`/`data`/`core` 完全不依赖它们 |
| 返回键语义变化 | 保留 `Shortcuts.kt` 的 `appShortcutOf` 纯函数与 `ShortcutsTest`，键位行为钉住 |

---

## 6. 验收口径

1. `./gradlew :composeApp:compileKotlinDesktop :composeApp:desktopTest` 全绿
2. 单文件 > 600 行的 `.kt`：从 **11 个降到 0**（含 `ChatRepository` 750 行也要拆）
3. `rg 'import androidx.compose' composeApp/src/commonMain/kotlin/**/component` 为 0
4. `rg 'import dev.shinglem.hermes.(component|ui|shell)' composeApp/src/commonMain/kotlin/**/{core,domain}` 为 0
5. `rg 'import dev.nucleusframework' composeApp/src/**` 只命中 `shell/` 与 `di/`
6. `rg 'private fun Throwable\.(describe|notice)'` 为 0
7. `rg 'private var loaded = false'` 为 0
8. `rg 'var on[A-Z]'`（ViewModel 可变回调）为 0
9. `rg 'viewModel \{'` 与 `rg 'androidx.lifecycle.viewmodel'` 为 0
10. `@Immutable` / `ImmutableList` 覆盖全部 State；`@Immutable` 标注数 ≥ State 数
11. 每个域至少 1 个 Reducer 纯函数测试 + 1 个 Component 测试；`ModuleVerificationTest.verify()` 通过
12. `Store.labels` 的发射序列用 **Turbine `awaitItem()`** 断言（每个含 Label 的 Store 至少 1 例）
13. `rg 'expect (suspend )?fun pick'` 与 `rg 'pickSaveFile|pickExecutableFile'` 为 0（FileKit 接管）
14. **真机过一遍 0.5.5 的 5 条修复**（切模型 / 思考块跟随 / 滚动吸附 / 会话切换 / 审批通知）不回归
15. **真机六项**：单实例（含 `--silent` 激活）/ 提权重启 / 开机自启 / 托盘常驻 /
    触摸滚动 / 中文 IME 全过
16. **通知栈零改动**：`ShortcutScan` / `ShortcutAumidWriter` / `ProcessAumid` /
    `NativeNotifications.desktop.kt` 的公开行为不变（仅允许搬家，逻辑不改）
17. 提权进程下 FileKit 对话框能弹出（若不过则回退 `expect/actual`，并在 §2.8 记录）
18. **测试数不减**：`@Test` ≥ **573**；减少的用例必须逐条说明是搬家而非删除（见 14 篇 §2）
19. **功能对齐**：v0.5.8 的全部功能域手工清单全过（14 篇 §6.3，共 16 行覆盖会话/对话/输入/终端/能力/设置/通知/子代理/后端/命令面板/快捷键）

> 原 S7 相关的验收（浏览器面板 / 插件宿主骨架）随 S7 移到 **v0.6.1**。

---

## 7. 变更记录

| 日期 | 改动 | 原因 |
|---|---|---|
| 2026-11-13 | 初稿：合并 v0.6.0 重构计划（15 VM → Component + Store、Decompose/MVIKotlin/Koin）+ 桌面壳迁 Tao + 内嵌槽位 | 架构重排定稿；`NativeView` 是浏览器面板的唯一可行路径，而它要求 Tao |
| 2026-11-13 | **范围切分**：v0.6.0 = **S0–S6 + S8**（完整对齐 v0.5.x）；**S7 + 编辑器槽位与选型 → v0.6.1**。基线更新为 305 文件 / 49,935 行 / 573 测试 / 16 个 androidx ViewModel / 11 个 >600 行文件。验收口径重排为 19 条（原 S7 两条移出）。开发分支 `feature/v0.6.0-refactor` | 用户要求 v0.6.0 完整实现 v0.5.x 全部功能，S7 与编辑器另立版本 |
| 2026-11-13 | 新增落地与测试两篇：[13-重构落地设计](../详细设计/工程与代码/13-重构落地设计（v0.6.0）.md)、[14-测试计划](../详细设计/工程与代码/14-测试计划（v0.6.0）.md) | 计划本身要能被照着实施与验证 |
| 2026-11-13 | **S0 实测完成（大部分）**：Essenty 2.7.1 兼容 ✅、压栈后 Store 仍推进 ✅、`nucleusApplication(enableSingleInstance=false)` 可关 ✅、Tao 窗口 + 中文渲染 + AWT 剪贴板共存 ✅；578 测试全绿。剩中文 IME 与触摸待人工。同时修正 S0 步骤：**不在本步移除 `adaptive-*`/`coil-*`**（会编译失败）、`mvikotlin-main` 必须进 `commonMain` | 实测发现原计划两处会直接失败 |
| 2026-11-13 | **S1 完成**：`SessionSummary` 下沉 `domain/model`（core 不再依赖 feature）、删 coil 三依赖、`ContentNegotiation` 真装上 + `getAs/sendAs`、7 份 `Throwable.describe` 收敛成 `toUserNotice`/`asAppError`、`pad2` 提为 `core/time` 内部函数、3 个 `@Preview`、`ConfirmDialog` 迁 5 处（余 9 处是表单/详情型，随 S5 各页重写）。578 测试全绿 | S1 排期 |
| 2026-11-13 | **调整阶段顺序：S1 之后先做 S6，再 S2–S5**。理由：① S6 只动 8 个文件、S2–S5 是 300+ 文件的量级 —— 用最小工验证最大风险；② S6 只碰 `shell/`，与 `component/`+`ui/` 正交，不返工；③ S6 之后 S2–S5 每步都能在真实产品壳里目视验证。**触摸仍无法验证**（用户环境限制），S6 该条记为"未验证 + 保留退路"，不计通过 | 用户提出、评估后采纳 |
| 2026-11-13 | **ADR-066：单实例与 `--silent` 保留自研实现**。读双方源码后确认 Nucleus 缺三条已定论语义（`--silent` 不唤醒 / `--silent` 不建主窗口 / 按 home 隔离便携包），且 `ObserveSingleInstanceRestore` 是 internal 无法从外部拦。因此 `enableSingleInstance = false`，S6 的单实例部分是**纯搬家**（工作量反而变小） | 用户提问「单实例和 silent 能否靠 Nucleus 实现」，逐条对比后否决 |
| 2026-11-13 | **ADR-066 修正（推翻初版）**：改为**采用** Nucleus 单实例。复核发现初版两条判断是错的（`DecoratedWindow(visible=)` 与 `SingleInstanceManager.configuration` 都是公开 API）；属实的"二次触发抢前台"场景不可达（任务只有 LogonTrigger 无重复），"唤醒失败无反馈"用户已接受。代价：删自研 250 行 + 83 行测试；新增待验项：提权/非提权实例可能互相看不见 | 用户追问"静默实例能否按 Nucleus 方式占锁"，重新核查后推翻 |
| 2026-11-13 | **S6 主体完成**：`application{}`→`nucleusApplication{}`、`Window`→`DecoratedWindow`、Swing 托盘→`NucleusTray`、通知窗→`DecoratedWindow(transparent)`；删除自研 `SingleInstance.kt`(250)+`SingleInstanceTest.kt`(83)。真机验证：锁文件 `HermesCompose-28ee0fc6.lock` 已建、第二实例立即退出只剩 1 进程、主窗口正常（gateway 已连、sessions count=1）。新增 ADR-067（通知窗保留 AWT 屏幕几何，`taoAwtProbe` 实测可用但 `MouseInfo` 返回垃圾值）。**遗留**：托盘图标需人工看系统托盘确认；`DeepLinkHandler` 偶发 restore_request 读取 ERROR（Nucleus 内部，无深链时是噪音） | S6 实施 |
| 2026-11-13 | **S6 两项用户反馈修正**（用户确认托盘与 `--silent` 均正常）：① **标题栏自绘**（ADR-068）—— Tao 的 `DecoratedWindow` 在 Windows 上无边框，不调 `TitleBar` 就没有标题栏也不能拖动；且它不读 `MaterialTheme`，必须显式传 `TitleBarStyle`（否则深灰底 + 深色字看不清）。`App.kt` 拆出 `AppContent` 让标题栏与内容共享同一主题。实测：双击最大化 / 拖动 / 亮暗主题切换均正常。② ~~第二实例提前退出~~ **已回退**（见下条）。591 测试全绿 | 用户反馈"窗口没有标题栏" + "第二次启动后端启动有点慢" |
| 2026-11-14 | **回退"第二实例提前探测"**（ADR-066 补充）。用户指出"启动慢"不是第二实例的问题 —— 核实日志后确认：慢在**首实例冷启 daemon**（4 次实测 30.9/32.4/32.6/33.5s，波动 ±1.3s；热复用只要 ~1.5s），与单实例判定无关。而探测绕过了 Nucleus 的 `watchForRestoreRequests` 装配路径，属实质风险。已删 `SingleInstanceProbe.kt` + 其 8 个测试，恢复成只设 `configuration`、判定交给 `nucleusApplication`。**另外两项确认不改**：① 冷启额外耗时有本机另一个官方实例占着 `8642`（`api_server_port_in_use`）的影响，用户已确认那是自己开的；② 退出时 `gateway stop` 杀 daemon 是**预期行为**。583 测试全绿（回到探测前基线） | 用户指出「你绕过的部分无效，考虑回退，用回原生写法。应该和第二实例无关」 |
| 2026-11-14 | **S2 模板落地**（ADR-069）：Security 域迁成 Component + Store 样板。新增基座 `StoreAdapters`（Store→StateFlow / Label→Channel）、`StoreHolder`（Store + scope 装进 InstanceKeeper）、`RememberComponentContext`（S2 过渡）。Security 拆成 Contract / Reducer / Executor / StoreFactory / Component + `ui/settings/SecurityContent`，删旧 `SecurityState.kt` + `SecurityPage.kt`。**关键修正**：`Appear` 必须带 `connected` —— 第一版在 Component init 里直接读，实测先闪「连不上后端服务」（`REST /api/config failed` 早于 `gateway connected` 3 秒），迁移前 UI 的 `LaunchedEffect(isConnected)` 就是这个约束。真机验证审批页数据正确回填。604 测试全绿（+21） | S2 排期 |

---

## 附：与其它文档的关系

| 文档 | 关系 |
|---|---|
| [`07-decisions.md`](07-decisions.md) | **ADR-057 ~ ADR-064** 记录本计划的全部取舍理由，本篇不复述 |
| [`03-architecture.md`](03-architecture.md) | §1 分层图与 §9 桌面壳按本计划更新 |
| [`02-selection.md`](02-selection.md) | 依赖表按本计划更新（增删项与版本核实记录） |
| [`06-roadmap.md`](06-roadmap.md) | 只留指针，P0–P6 总路线不变 |
| [`../待修复问题.md`](../待修复问题.md) | `#21` = 通知 AUMID 免快捷方式（明确不进 v0.6.0）；`#3` = 托盘/浏览器面板（已并入本篇 S6/S7） |
| [`../详细设计/工程与代码/07-提权与自启动.md`](../详细设计/工程与代码/07-提权与自启动.md) | 提权 + `--do-not-de-elevate` 的既有结论，S7 直接引用 |
| [`../详细设计/工程与代码/10-Windows触屏输入.md`](../详细设计/工程与代码/10-Windows触屏输入.md) | 触摸方案的既有结论，S6 需重测 |