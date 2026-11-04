# 03 · 编码规范与 Compose 约定

> 本篇把**官方 Compose 指南**（单向数据流、状态提升、副作用、稳定性、列表、测试）落成本项目的
> 可执行规则，并把 [`AGENTS.md`](../../../AGENTS.md) §4 的硬约束翻译成"代码里该长什么样"。
> 冲突时以 `AGENTS.md` 为准。

---

## 1. 单向数据流（UDF）

```
用户意图 (onXxx)  →  StateHolder 改状态  →  StateFlow<UiState>  →  Composable 重组
```

三条规则：

1. **Composable 不持有业务状态**。`remember { mutableStateOf(...) }` 只允许用于**纯 UI 瞬时态**
   （展开、hover、输入框焦点、滚动位置）。
2. **状态只从上面传下来**，事件只往上抛（lambda），不在子组件里直接调 Repository。
3. **`UiState` 是 `data class` + 不可变集合**（`List` 用 `kotlinx.collections.immutable` 或只读 `List`），
   便于比较与快照。

```kotlin
// ✅
@Composable
fun SessionListPane(state: SessionListState, onSelect: (SessionId) -> Unit, onPin: (SessionId) -> Unit)

// ❌ 子组件里发请求 / 自己 new Repository
@Composable
fun SessionListPane() { val repo = remember { SessionRepository(...) } /* ... */ }
```

---

## 2. 副作用 API 选择表

| 需求 | 用 | 不要用 |
|---|---|---|
| 进入/参数变化时拉数据 | `LaunchedEffect(key)` | 在 Composable 体里直接调用挂起函数 |
| 订阅 Flow 到 State | `collectAsStateWithLifecycle()`（需 lifecycle-runtime-compose）或 `collectAsState()` | 手动 `launch { collect }` |
| 注册/注销监听（连接事件） | `DisposableEffect(key)` | `remember` 里注册 |
| 每次成功重组后做事（少用） | `SideEffect` | — |
| Flow → State（带初值） | `produceState(initial, key) { }` | — |
| Compose 状态 → Flow（防抖/节流） | `snapshotFlow { }` + `debounce` | 手写 `LaunchedEffect` + delay 循环 |
| 滚动到末尾 | `LaunchedEffect(lastId) { listState.animateScrollToItem(...) }` | 在布局回调里滚动 |

**纪律**：`LaunchedEffect` 的 key 必须**显式**且稳定（如 `sessionId`），不要用 `Unit` 掩盖依赖变化。

---

## 3. 状态持有者（ViewModel / StateHolder）

### 3.1 形态

```kotlin
class ChatViewModel(
    private val sessionId: SessionId,
    private val repo: ChatRepository,
    private val scope: CoroutineScope = viewModelScope,   // 便于测试注入
) : ViewModel() {
    private val _state = MutableStateFlow(ChatState(sessionId = sessionId))
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val _effects = Channel<ChatEffect>(Channel.BUFFERED)   // 一次性动作
    val effects: Flow<ChatEffect> = _effects.receiveAsFlow()

    init { collectEvents() }
    fun submit(text: String) { /* 只改状态 + 起协程 */ }
}
```

规则：

- **构造参数注入依赖**（Repository、id），不用全局单例。
- 对外只暴露 `StateFlow` / `Flow`；`MutableStateFlow` 私有。
- **一次性事件**（滚动到新消息、弹 Snackbar、复制成功）走 `Channel` → `Flow` 或"状态里的 `consumed` 标记"，
  **不要**塞进 `UiState` 的布尔字段。
- 不做全局单一 store（ADR-013 / 03-architecture §4.1）。

### 3.2 官方约束：`viewModel()` 必须带 initializer

```kotlin
// ✅ common 代码（无反射）
val vm: ChatViewModel = viewModel(key = sessionId.value) {
    ChatViewModel(sessionId, container.chatRepository)
}
```

`key` 用会话 id：切换会话时得到新实例，避免状态串台。

### 3.3 `Loadable` 统一装载态

```kotlin
sealed interface Loadable<out T> {
    data object Idle : Loadable<Nothing>
    data object Loading : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
    data class Failed(val error: AppError, val stale: T? = null) : Loadable<Nothing>
}
```

**刷新失败但已有缓存**时用 `Failed(stale = 旧值)`，UI 顶部出横幅、列表继续显示旧数据（对应 04-ui-spec §8）。

---

## 4. 稳定性与重组

| 规则 | 说明 |
|---|---|
| 传给 Composable 的参数尽量 `immutable` | `data class` + `List` → 用 `ImmutableList` 或标注 `@Immutable`（需论证） |
| lambda 用 `remember` 包裹再下传 | 列表项每帧新建 lambda 会导致整列失效 |
| 避免在 Composable 里创建 `SimpleDateFormat` / 正则 | 移到 `object` 或 `remember` |
| 大对象不进 `UiState` 的 equals 链 | 例如 AST、图片位图：用 id + 外部缓存 |
| `derivedStateOf` 用于高频推导 | 如"是否显示滚动到底部按钮" |

```kotlin
// ✅ 稳定 lambda
val onSelect = remember { { id: SessionId -> vm.select(id) } }
```

**不要**为了"优化"加一堆 `remember`——先用 Compose 编译器的稳定性报告（强跳过模式）看真实问题。

---

## 5. 列表（对应 `AGENTS.md` 硬约束 5、10）

```kotlin
LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
    items(items = rows, key = { it.key }, contentType = { it.kind }) { row -> ... }
}
```

- **必须有 `key`**：会话用 `sessionId`，消息用 `messageId`（流式消息用稳定占位 id，不要用 index）。
- **必须给 `contentType`**：分组标题 / 会话行 / 骨架行分开，回收复用才正确。
- **禁止 LazyColumn 内嵌 LazyColumn**：消息内部用 `Column` + `forEach`。
- **禁止 `stickyHeader`**：分组标题用普通 `item { SectionLabel(...) }`（CMP #3016 闪烁）。
- 长列表（500 条）不一次性 `submitList`：Repository 用分页窗口（`LazyPagingItems` 不引入，手写窗口即可）。

---

## 6. 硬约束在代码里的样貌

| 硬约束 | 代码要求 |
|---|---|
| 4 · 输入框用 `BasicTextField` | `composer/Composer.kt` 只用 `BasicTextField` + `BasicTextFieldState` + `InlineTextContent`；**不得**出现任何编辑器内核依赖 |
| 5 · 外层 Lazy + 内层 Column | `MessageList` 的 item 内容不得含 `Lazy*`；需要局部滚动时用 `Row` + 横向 `Modifier.horizontalScroll` |
| 6 · 跨消息选择不可用 | 选区由 markdown-one 在 `MarkdownText` 内部包一层 `SelectionContainer`（`selectable` 开关）+ 消息级操作条（复制整条 / 引用）；调用方不要在外面再套一层 |
| 7 · 发 Markdown 源码 | `ComposerState.text` 原样提交；**不做**任何转义、不加 `<br>`、不修剪缩进 |
| 8 · 富内容降级 | mermaid 由纯 Kotlin 引擎出图、失败回落源码；数学公式走 latex-renderer 原生排版；HTML 块按源码/容器剥壳渲染，**不引 WebView** |
| 9 · 不用 `NavigationRail` | `ProfileRail.kt` 自绘 `Surface` + `Column` + `IconButton` |
| 10 · 不用 `stickyHeader` | 见 §5 |
| 11 · CMP-8735 | `AdaptiveListDetail` 把 `PaneScaffoldDirective` 绑到窗口宽度档位（`LocalWindowInfo`），并 `key(singlePane, wide) { ... }`；rail 留在 scaffold 外 |

---

## 7. 协程与线程

| 场景 | 规则 |
|---|---|
| 网络 / 文件 IO | Repository 内 `withContext(Dispatchers.IO)`；`Dispatchers` 通过构造注入以便测试替身 |
| 状态更新 | 在 ViewModel 的 `viewModelScope`（Main）里改 `MutableStateFlow` |
| 流式 token 合并 | `event` 到达 → `Channel` → 16–32ms 窗口合并后一次性 `append`，不要每 token 重组 |
| 终端 PTY | BossTerm / Pty4J 自带线程；Compose 侧只做 `LaunchedEffect` 的读写桥接 |
| 关闭清理 | ViewModel `onCleared()` 取消订阅；`DisposableEffect` 注销监听 |

**禁止**：在 Composable 里 `GlobalScope.launch`；在 Repository 里 `runBlocking`。

---

## 8. 错误处理链

```
Ktor / WS 异常 ──ErrorMapper──▶ AppError ──▶ Loadable.Failed / Effect.ShowError ──▶ 横幅 / Snackbar
```

```kotlin
sealed interface AppError {
    data object BackendUnreachable : AppError        // 连不上 127.0.0.1:9119
    data object Unauthorized : AppError              // 401
    data class Timeout(val operation: String) : AppError
    data class Protocol(val detail: String) : AppError
    data class Server(val code: Int, val message: String) : AppError
    data class Unknown(val cause: String) : AppError
}
```

规则：

- **连接类错误给横幅**（可重试），**操作类错误给 Snackbar**，不弹阻塞对话框（03-architecture §7）。
- 启动期失败走整页诊断（04-ui-spec §8），并把错误摘要 + 后端地址 + 日志路径一起展示。
- **不吞异常**：`catch` 后必须转成 `AppError` 或记录日志；空 `catch` 一律视为缺陷。

---

## 9. 字符串与资源

- 文案进 `composeApp/src/commonMain/composeResources/values/strings.xml`，代码用 `Res.string.xxx`。
- **禁止**在深层组件里写中文字面量（`AGENTS.md` §6）；临时文案先集中在 `ShellStrings`/`Res` 再替换。
- 需要格式化时用 `stringResource(Res.string.x, arg)`；不要手拼 `"共" + n + "条"`。
- 图标集中到 `ui/icon/AppIcons.kt`，避免各处分散 import `material-icons-extended`（减小二进制与编译面）。

---

## 10. 测试

| 层 | 方式 | 必测 |
|---|---|---|
| 协议层 | 纯 Kotlin 单测（假 WS 帧） | 帧编解码、id 匹配、超时、重放去重 |
| Repository | 注入 fake transport | 错误映射、缓存失效、分页窗口 |
| StateHolder | `runTest` + `StateFlow` 断言 | 状态迁移（Loading→Ready→Failed(stale)） |
| UI | `runComposeUiTest { }` | 空态/错误态/列表渲染/键盘发送 |
| 快照（可选） | 暂不做 | — |

```kotlin
@Test
fun 空会话显示空状态() = runComposeUiTest {
    setContent { HermesTheme { SessionListPane(state = SessionListState.Empty, …) } }
    onNodeWithText("还没有会话").assertIsDisplayed()
}
```

- 测试名可用中文（Kotlin 允许反引号），与文档里的验收条目对应。
- **不做端到端连真后端**的自动化测试；连真后端的验证归 `草案/05-acceptance.md` 的手工条目。

---

## 11. 命名与文件

| 类别 | 规则 | 例 |
|---|---|---|
| 包 | 全小写，`feature.<域>` / `core.<能力>` | `feature.chat` |
| Composable | 大驼峰，名词 | `MessageItem`、`ProfileRail` |
| 状态类 | `<域>State` / `<域>ViewModel` | `ChatState`、`ChatViewModel` |
| 事件 | 动词过去式/命令式 sealed | `ChatEffect.ScrollToBottom` |
| Repository 方法 | 动词，返回 `Loadable` 或 `Result` | `suspend fun loadSessions()` |
| 常量 | 全大写下划线，集中在 `Timeouts` / `PrefKeys` | `PROMPT_SUBMIT_ACK` |
| 文件 | 一文件一主声明 | `SessionListPane.kt` |

---

## 12. 反模式清单（Code Review 必查）

- ❌ Composable 里 new Repository / 直接 `HttpClient(...)`
- ❌ `LaunchedEffect(Unit)` 里读会变的参数
- ❌ `LazyColumn` 嵌套；item 里 `key` 用 index
- ❌ 在 `UiState` 里放 `CoroutineScope`、`Context`、`ImageBitmap`
- ❌ `viewModel()` 不带 initializer
- ❌ `runBlocking` / `GlobalScope`
- ❌ `catch (e: Exception) {}`（空 catch）
- ❌ 中文字面量散落在组件深处
- ❌ 为对齐 HTML 视觉稿写死 `dp` 尺寸或自绘基础控件（`AGENTS.md` 硬约束 12）
- ❌ 把 `prompt.submit` 的 ack 超时改成 30s（硬约束 3）
