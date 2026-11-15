# 03 · 架构

> **基线 v0.6.0。** 本篇描述**已落地**的架构（不是计划）。
> 逐层文件清单见 [`详细设计/工程与代码/01-工程结构.md`](../详细设计/工程与代码/01-工程结构.md)；
> 取舍理由见 [`07-decisions.md`](07-decisions.md) 的 ADR-057 ~ ADR-077。
> v0.6.0 之前的架构（15 个 `ViewModel` + 1130 行 `AppShell`）已归档到
> [`归档/v0.6.0-重构/09-refactor.md`](../归档/v0.6.0-重构/09-refactor.md)。

---

## 1. 分层与依赖方向

```
┌──────────────────────────────────────────────────────────────┐
│ ui/          纯 Composable：只接 (State, callbacks)           │
│              唯一 import Compose 的层                          │
├──────────────────────────────────────────────────────────────┤
│ component/   业务逻辑：Component（生命周期）+ Store            │
│              Reducer 纯函数；不出现 UI 组合代码                │
├──────────────────────────────────────────────────────────────┤
│ domain/      Repository 接口 + 领域模型（ChatItem / SessionKey）│
├──────────────────────────────────────────────────────────────┤
│ data/        Repository 实现：唯一持有 GatewayClient / RestClient│
├──────────────────────────────────────────────────────────────┤
│ core/        transport / contract / error / result / time / log│
├──────────────────────────────────────────────────────────────┤
│ platform/    expect/actual（剪贴板 / 外链 / 文件对话框 / 通知）  │
├──────────────────────────────────────────────────────────────┤
│ shell/       桌面壳：Tao 窗口 / 托盘 / 单实例 / 提权 / 自启     │
│              **唯一允许 import dev.nucleusframework.* 的层**   │
└──────────────────────────────────────────────────────────────┘
```

### 1.1 四条硬边界（架构测试钉死）

| # | 规则 | 测试 |
|---|---|---|
| 1 | `component/` 与 `domain/` 不出现 UI 组合代码；只允许 `@Immutable` 与 `TextFieldValue` | `ArchitectureBoundaryTest` |
| 2 | `core/` 与 `domain/` 不得 import `component/` / `ui/` / `shell/` | 同上 |
| 3 | `ui/` 不得 import `data/` | 同上 |
| 4 | 只有 `shell/`（与 `di/`）可 import `dev.nucleusframework.*` | 同上 |

> 第 1 条曾要求「零 `androidx.compose` import」，与「每个 State 标 `@Immutable`」自相矛盾
> （`@Immutable` 就是 compose 注解）。按业主决定放宽为「不出现 UI 组合代码」。

### 1.2 为什么把业务逻辑摘出 UI

`component/` 与 `domain/` 不依赖 Compose，意味着：

- **Reducer 是纯函数** —— `ChatReducer.reduceStreamEvent` 这类流式状态机可脱离框架逐条断言；
- **Component 可用纯 JVM 测** —— `DefaultComponentContext(LifecycleRegistry())` + Fake Gateway
  即可，不需要起窗口或组合树；
- 换 UI 库时只改 `ui/`，换窗口壳时只改 `shell/`。

---

## 2. 每域的标准形状：Component + Store

采用 MVIKotlin 的 Store 与 Decompose 的 ComponentContext（ADR-057）。

```kotlin
// component/<域>/XxxComponent.kt —— 业务逻辑，无 Compose
interface XxxComponent {
    val state: StateFlow<XxxState>     // 由 Store 转来（StoreStateSource）
    val effects: Flow<XxxEffect>       // 由 Store 的 Label 转来（Channel，不重放）
    fun onIntent(intent: XxxIntent)
}

class DefaultXxxComponent(
    private val componentContext: ComponentContext,
    gateway: XxxGateway,
    private val profile: String?,
    executorContext: CoroutineContext = Dispatchers.Default,
) : XxxComponent, ComponentContext by componentContext {

    private val executorScope = CoroutineScope(SupervisorJob() + executorContext)

    private val holder = componentContext.instanceKeeper.getOrCreate(key = STORE_KEY) {
        StoreHolder(
            store = XxxStoreFactory.create(gateway, profile, executorScope.coroutineContext),
            executorContext = executorScope.coroutineContext,
        )
    }
    // …state / effects / onIntent 三个投影
}
```

| 角色 | 职责 | 能否脱离框架测 |
|---|---|---|
| `XxxIntent` | UI → 业务的动作 | — |
| `XxxResult` | Executor → Reducer 的结果 | — |
| `XxxEffect`（MVIKotlin Label） | 一次性事件（Toast / 导航） | ✅ |
| `Reducer` | `(State, Result) -> State` 纯函数 | ✅ |
| `Executor` | 唯一做 suspend / 取消 / dispatch 的地方 | ✅（Fake Gateway） |
| `StoreHolder` | 把 Store + scope 装进 InstanceKeeper，`onDestroy` 一并收 | — |
| `Component` | 生命周期 + 三投影 | ✅（`DefaultComponentContext`） |

### 2.1 五条从实践中得出的纪律

1. **Executor 不能在 `init` 里 dispatch** —— MVIKotlin 此时 `Value was not initialized`。
   用 `Intent.Observe` 触发懒订阅。
2. **`executorContext` 必须是单线程**（持裸 `mutableMapOf` / `StringBuilder` 的域如 Chat）。
   它们靠「全部路径串行在一条线程」做互斥；换成 `Dispatchers.Default` 会真并发。
   desktop 侧传 `UiDispatcher.context`（= `Dispatchers.Main`，见 §7.1）。
3. **`onConnectedChanged(connected)` 显式传入**，不要在 Component `init` 里读后端 ——
   组件创建时后端往往还没 Ready，立刻请求必然失败并留下一条红色的「连不上后端服务」。
4. **`InstanceKeeper` 按 key 复用且不校验 factory**。多个 Component 共用同一个
   `ComponentContext` 时，换 profile 只会重建外壳、取回旧 Store。**每域必须用自己的子上下文**
   （`rememberPerProfileComponent`）。详见 §5.2。
5. **`Store.labels` 用 `Channel` 而不是 `SharedFlow`** —— Label 不该重放，重进页面补发
   「滚动到底部」会把用户弹回底部。

---

## 3. 传输层：两条通道

契约只读。两条通道都指向同一个后端（dashboard 端口，**动态**）：

| 通道 | 协议 | 用途 | 承载对象 |
|---|---|---|---|
| **gateway** | WebSocket + JSON-RPC 2.0（text 帧） | 会话、聊天、流式事件、server→client 请求（审批/澄清） | `GatewayClient` |
| **REST** | HTTP + JSON，`/api/*` | 技能、工具集、cron、profiles、配置、模型、MCP、消息平台 | `RestClient` |

**不要把管理类调用塞进 gateway RPC**，也不要指望 gateway 支持 REST 语义。
`8642` 是 OpenAI 兼容 API，**不是桌面端接口**。

### 3.1 超时分级（照搬官方语义，不要统一成 30s）

| 场景 | 超时 | 理由 |
|---|---|---|
| 默认请求 | 30s | 交互式调用 |
| 启动期批量读（config / profiles / models / cron） | 60s | `/api/profiles` 递归扫每个 profile 的 skill 树 |
| `prompt.submit` | **1800s** | 一轮对话完成靠 `event` 通知，不靠 RPC 返回；短超时会误报 |
| `/api/status` 健康探测 | 5–10s | 要快速发现后端死了 |

### 3.2 GatewayClient 必须实现的六件事

```
1. 连接状态机：idle → connecting → open → closed/error
2. 请求：id 自增 → 写帧 → pending map 挂 CompletableDeferred → 超时/结果/错误
3. 心跳：定时 ping + deadline（超时判死，处理半开 TCP）
4. 重连：指数退避 → 重连成功后 re-resume（旧 runtime id 在新连接上无效，必须清）
5. server→client 请求：按 method 路由到 handler，respond/fail **幂等**
6. 事件分发：method == 'event' 的帧 → SharedFlow<GatewayEvent>
```

### 3.3 心跳与 health 探活

`RealShellGateway` 负责两个轮询（**不在 Executor 里** —— 见 §7.2）：

| 循环 | 间隔 | 作用 |
|---|---|---|
| `probeOnce` | 5s | 探 `/api/status` 往返，写 `ProbeState`。**探 HTTP 而不是只看 WS**：WS 半死时 socket 可能还在，HTTP 已不通 |
| `healthOnce` | 30s | 读 `/api/health`（后端版本号等），失败保留上一次的值 |

---

## 4. 应用作用域与 DI

```
DesktopEntry.main
└── DesktopContainer.build()          ← 唯一装配点（含 GatewayClient / RestClient 单例）
    ├── KoinStartup.start(container)  ← 在它之上建可查询的图
    └── nucleusApplication { … }
        └── AppContent → AppShell → rememberAppShellDeps(container, context)
```

- **`GatewayClient` 与 `RestClient` 必须是应用级单例**：将来要支持独立聊天窗口，
  新窗口只能复用同一连接；每窗口各建 WebSocket 会导致事件分叉。
- **Koin 只做查询**，装配逻辑仍在 `AppContainer` —— 这样才能纯 JVM 构造容器做测试。
- 已删除 `androidx.lifecycle.ViewModel` 体系：`rg 'viewModel \{'` 与
  `rg 'androidx.lifecycle.viewmodel'` 均为 0。

---

## 5. 状态隔离

### 5.1 profile 切换

| 对象 | 行为 |
|---|---|
| 会话列表 | 切换时**同步清 `selectedId`**（否则拿旧 profile 的 id 读历史 → 404 `Session not found`） |
| 设置域 Component | 按 `(域, profile)` 派生**子 ComponentContext** 重建，旧 Store 随之销毁 |
| 事件订阅 | 按 profile 过滤 |
| `sessions.changed` | 去抖 400ms 后刷新；`creating` 时不刷（会把刚造出来的会话"闪掉"） |

### 5.2 跨 profile 串数据（v0.6.0 实测事故，已修）

**现象**：切档案后设置页仍读写上一个档案的配置，保存也写进旧档案。

**根因**：`remember(context, activeProfile)` 看起来"profile 变了就重建"，但
`InstanceKeeper.getOrCreate(key = "<域常量>")` **按 key 复用且不校验 factory**，
而 `componentContext` 始终是同一个根 —— 于是新 Component 取回旧 Store，构造参数
`profile` 被完全忽略。Executor 里的 `profile` 是 `val`，所以连"重读"都不会发生。

**修法**：`rememberPerProfileComponent(parent, domain, profile) { own -> … }`，
内部用 `parent.childContext("<domain>:<profile>")` 得到**独立的 InstanceKeeper**。

**护栏**：`ComponentContextIsolationTest` 同时钉住「共用根上下文会串数据」这个反例本身
—— 否则下次有人"简化"掉子上下文时测试仍是绿的。

### 5.3 Chat 域的四类簿记（各自成类，不再用平行 map）

| 类 | 替代掉的平行结构 |
|---|---|
| `SessionRuntimeStore` | `runtimeIds` / `generations` / `revisions` / `lastEventAt` / `cwds` 五个 map |
| `PendingRequests<T>` | `approvalDecisions` + `approvalFrameIds` + `clarifyDecisions` + `clarifyFrameIds` |
| `SessionSnapshotCache` | `savedStates` 一张裸 map（含 `switchPlan` 决策） |
| `SessionModelCache` | 模型真值 |

外加三个从 suspend 方法抽出的纯函数：`ApprovalTarget`（跨会话审批目标）、
`RewindAndBranch`（回退/分支目标）、`StaleFence`（fence 过期重试判据）。

> **`SessionRuntimeStore.clearConnectionScoped()` 必须清 `runtimeId`。**
> 留着会让 `ensureRuntime` 短路返回旧 id —— 既不重新 `session.resume`、也不重建订阅，
> 表现为「重连后审批/clarify 永远收不到」。`dirty` / `cwd` 与连接无关，要保留。

---

## 6. 本地持久化

用 `multiplatform-settings`（桌面端落文件）。`AppPreferences` 是唯一入口。

| 键 | 内容 |
|---|---|
| `ui.mode` | `sessions` / `capabilities`（**设置页不持久化**：一次性去处，重启回会话） |
| `ui.theme` / `ui.style` | 外观 |
| `sessions.pinned` | 置顶会话 id 列表（只写本地，不动后端） |
| `groups.collapsed` / `sessions.archived` | 折叠态与归档快照 |
| `editor(s)` | 外链编辑器命令模板 |
| `conn.baseUrl` / `conn.token` | 连接参数 |
| `window.bounds` | 窗口尺寸位置（最大化时记还原后的） |
| `notifications.*` | 通知开关 |

**不上数据库**。等真需要全文检索历史 transcript 时再评估 SQLite。

---

## 7. 线程与协程

### 7.1 UI 线程 = `Dispatchers.Main`（**不是** `Dispatchers.Swing`）

S6 迁 Nucleus Tao 后，`Dispatchers.Main` 的提供者变了：Nucleus 注册了
`TaoMainDispatcherFactory`（loadPriority 100），压过 `kotlinx-coroutines-swing` 的 0。

| dispatcher | Tao 进程里的实际线程 |
|---|---|
| `Dispatchers.Main` / `.Main.immediate` | `main`（Tao 主线程，Compose 组合也在这里） |
| `Dispatchers.Swing` | `AWT-EventQueue-0`（另一条线程） |

所以 `UiDispatcher.context = Dispatchers.Main`。用错等于把「UI 事件回调」与「状态变更」
拆到两条线程上。判据与实测见 `platform/UiDispatcher.kt`；`UiDispatcherTest` 钉住。

### 7.2 其它纪律

- 网络与 IO 走 `Dispatchers.IO`；状态更新在 UI 线程。
- 流式渲染：token 级 delta 按 **40ms** 合并一次再写 state（`STREAM_FLUSH_INTERVAL_MS`），
  避免每 token 重组。合并缓冲留在调用方，变换本身在 `ChatReducer`。
- 终端 PTY 读写由 BossTerm 内部线程负责，不要放进 UI 协程。
- **`while(true) { delay() }` 不要放 MVIKotlin Executor** —— 会让 `runTest` 的
  `advanceUntilIdle()` 永不 idle，测试直接挂住（实测挂 8 分钟）。轮询放 Gateway，Executor 只合并流。

---

## 8. 错误处理

| 层 | 做法 |
|---|---|
| 传输 | 连接错误 → 状态机 + 重连；请求错误 → `AppError` 带回错误码与 reason |
| 领域 | `ErrorMapper.from(throwable, operation)` 收敛成 `AppError` 子类型 |
| UI | 顶部横幅（连接类）+ Snackbar（操作类），不弹阻塞对话框 |
| 致命 | `NativeMessageBox.error`（Win32 `MessageBoxW`）+ `startup.log` 留痕 |

统一入口：`Throwable.toUserNotice()` / `toUserNotice(operation)` / `asAppError(operation)`
（v0.5.x 有 7 份逐字同构的私有实现，v0.6.0 收敛）。

**`CancellationException` 必须原样重抛** —— 当成普通错误吞掉会让协程取消语义失效。

---

## 9. 已知边界与偏差

### 9.1 `ChatViewModel` 仍是单文件（1951 行）

验收口径要求「单文件 > 600 行降到 0」，实际从 11 个降到 **1 个**。

已拆出去的：六类平行簿记（§5.3）、三份流式实现合并成 `ChatReducer`、
`handleGatewayEvent` 137 → 30 行（拆成 5 个方法）。

**剩下的 1951 行是「单实例多会话状态机」本身的复杂度**，继续拆需要 per-session Component
（S4 的完整形态）—— 涉及 `RootComponent` 的 `ChildStack`、`InstanceKeeper` 作用域，
是独立一轮的量级。已记录为**已知偏差**，不是遗漏。

### 9.2 未验证项（环境限制，不是缺实现）

| 项 | 状态 | 原因 |
|---|---|---|
| 触摸滚动 | ❌ 未验证 | 本机无触摸设备 |
| 中文 IME 完整链路 | ❌ 未验证 | 注入的按键被 IME 在 AWT/TSF 层消费；候选框→上屏只能人工验 |
| 功能对齐的真机手工项 | 判据 25/25 有测试覆盖 | 测试锁定的是状态与判据，不是"点下去界面反应正确" |

### 9.3 明确不做（延续，避免重新提议）

| 不做 | 原因 |
|---|---|
| 自研插件机制 / JS 插件宿主 | 前端插件返回 `ReactNode`，保留生态 = 自造 WebView（ADR-003、ADR-064） |
| 文件树 / 内置代码编辑器 / diff 视图 | 跳转外部 VSCode / IDEA（ADR-005） |
| 远程屏幕（noVNC / RFB） | 用 ToDesk / AweSun |
| 机器人聊天窗口 / 群聊窗口 | 本期只做「会话」主窗口 |
| 视觉 1:1 复刻 | 用 M3 原生组件；稿子是参考稿 |
| 多语言 | 只做简体中文 |
| WebView 兜底渲染 HTML | ksoup 原生渲染 + diagram-render + ratex（ADR-053） |
| navigation3 / Voyager / Circuit / KStateMachine | 选型已定 Decompose（ADR-057） |
| Detekt / ktlint | 会一次报上千条历史问题；关键约束靠架构测试 |
| 内嵌 VSCode | Electron 多进程 IDE，体重与单实例问题都去不掉 |

> 完整清单与理由见归档的 09-refactor §2.7 / §4。

---

## 10. 相关文档

| 文档 | 关系 |
|---|---|
| [`07-decisions.md`](07-decisions.md) | ADR-057 ~ ADR-077 记录本架构的全部取舍理由，本篇不复述 |
| [`详细设计/工程与代码/01-工程结构.md`](../详细设计/工程与代码/01-工程结构.md) | 逐包逐文件的目录清单 |
| [`归档/v0.6.0-重构/`](../归档/v0.6.0-重构/) | 已归档：重构的计划、文件级任务清单、测试计划 |
| [`08-data-contract.md`](08-data-contract.md) | WS / REST 的实际形状与字段 |
| `AGENTS.md` | 硬约束（规则唯一权威源），本篇只解释"为什么" |
