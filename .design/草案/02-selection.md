# 02 · 技术选型

> 原则：**官方优先 → 成熟第三方 → 自研**。只有生态里确实没有的那一层才自己写（本项目只有协议层）。
> 每个候选项都经过：注册表可获取性 → 许可证 → 平台已知缺陷 三项检查。
> 新增/更换依赖必须更新本文件。
>
> **具体版本号与 `libs.versions.toml` 骨架以
> [`../详细设计/工程与代码/02-依赖与版本.md`](../详细设计/工程与代码/02-依赖与版本.md) 为准**；
> 本篇只保留选型与坑，不重复维护精确版本。

---

## 1. 布局与骨架

| 需求 | 选型 | 坐标 | 备注 |
|---|---|---|---|
| 两栏响应式骨架 | `ListDetailPaneScaffold` | `org.jetbrains.compose.material3.adaptive:adaptive-layout:1.3.0` + `…:adaptive-navigation:1.3.0` | M3 canonical list-detail；两个 artifact 都不依赖 material3 |
| 窄窗单栏退化 | `rememberListDetailPaneScaffoldNavigator` | 同上 | `< 840dp` 单栏，`navigateTo(Detail)` / `navigateBack()` 由代码驱动 |
| （不采用）`NavigableListDetailPaneScaffold` | — | 只有 Android 侧有 | CMP desktop 未移植（核对 1.3.0 与 1.4.0-alpha01 的 jar 均无此类），用 navigator 等价实现 |
| 窗口尺寸分类 | `currentWindowAdaptiveInfo()` / `WindowSizeClass` | `org.jetbrains.compose.material3:material3-window-size-class` | 判定窄/中/宽 |
| 底部状态条 / 脚手架 | `Scaffold(bottomBar = …)` | CMP 自带 material3 | — |
| 左侧窄 rail（头像条） | **自绘** Surface + Column + IconButton（~80 行） | — | 不用 `NavigationRail`：它是"导航目的地"语义，固定 80dp 且布局约束不合适 |
| 终端折叠面板 | **自绘** AnimatedVisibility + 拖拽把手（~100 行） | — | M3 无此组件 |
| 拖拽调宽 | **不做**（用 scaffold 就没有） | — | 若要拖宽需自写 Row（~150 行） |

### 已知坑

- **CMP-8735**：`ListDetailPaneScaffold` 在 Desktop/iOS 上**不随窗口 resize 自适应**（CMP 1.8.2 + adaptive 1.1.2 报告，未修复）。
  **已决定接受**。如需重排：把 `PaneScaffoldDirective` 显式绑到窗口宽度档位（`LocalWindowInfo.containerSize` 转 dp），并用 `key(档位)` 包裹强制重组（见 `AdaptiveListDetail.kt`，ADR-037）。
- 用 scaffold 后 **list pane 宽度不可任意拖拽**（由 directive 按窗口尺寸算，约 360dp）。

---

## 2. 列表与条目（全部 M3 原生）

| 需求 | 组件 |
|---|---|
| 会话 / 条目列表 | `LazyColumn`（foundation） |
| 行 | `ListItem` |
| 开关 | `Switch` |
| 分段切换 | `SingleChoiceSegmentedButtonRow` |
| 状态标签 | `AssistChip` / `FilterChip` |
| 搜索框 | `OutlinedTextField` + 清空按钮 |
| 下拉选择 | `ExposedDropdownMenuBox` |
| 菜单 | `DropdownMenu` |
| 右键菜单 | `ContextMenuArea`（Compose Desktop 专有） |
| 对话框 | `AlertDialog` / `BasicAlertDialog` |
| 轻提示 | `SnackbarHost` |
| 卡片 | `Card` / `ElevatedCard` |
| 滚动条 | `VerticalScrollbar`（desktop） |
| 加载 | `LinearProgressIndicator` |

### 已知坑

- **stickyHeader + VerticalScrollbar 闪烁**（CMP #3016）。分组标题**不要吸顶**，用普通标题行。
- 单行 list item 默认 56dp，比官方客户端的 32dp 松。这是"用原生密度"的自觉取舍。

---

## 3. 内容渲染

| 需求 | 选型 | 坐标 | 版本 |
|---|---|---|---|
| Markdown 渲染 | **自建公共库 `markdown-one`**（git submodule，Apache-2.0） | `:modules:markdown-one`（解析/块路由/高亮/mermaid/视口） | 见其 CHANGELOG |
| 流式 Markdown | markdown-one 解析层（未闭合栅栏补齐 + 流式跳过浏览器类适配器） | 同上 | — |
| 代码高亮 | markdown-one 的 `builtin.code`（Highlights + 正则补丁） | 同上（库内自带） | — |
| 图片 / SVG | Coil 3 | `io.coil-kt.coil3:coil-compose` `coil-svg` `coil-network-ktor3` | 3.6.x |
| 图标 | Material Icons | `org.jetbrains.compose.material:material-icons-extended` | 随 CMP |
| 等宽字体 | 系统字体族（Consolas / JetBrains Mono） | — | — |

### 为什么用这个 Markdown 库

> **2026-10-31 更新（ADR-052）**：原本选定 mikepenz 作为底板，现在渲染实现已抽成独立公共库
> [`Shinglem/markdown-one`](https://github.com/Shinglem/markdown-one)（Apache-2.0），
> 以 git submodule 挂在 `modules/markdown-one`。下面这段 mikepenz 的评价仍然成立 ——
> 它是 markdown-one 内部 `builtin.markdown` 的实现底座，只是不再由本仓库直接依赖。

- 底层是 JetBrains Markdown（Kotlin 多平台解析器），GFM 全支持（表格 / 任务列表 / 删除线 / autolink / GitHub alerts）。
- 每个 AST 节点可覆盖（`MarkdownComponents`），支持 `annotator` 与 extended spans。
- markdown-one 在此之上加了**块级适配器链**（`BlockAdapter` + `AdapterRegistry`）：按格式路由、
  可替换、链尾源码兜底；流式补齐与图表降级也在库里。

### 已知坑与应对

| 坑 | 应对 |
|---|---|
| `annotator` **不能凭空创造块级元素** | @ 引用 chip 走"预处理成链接 + annotator 染色"（ADR-006）；块级元素才用自定义 `MarkdownComponents` |
| 高亮语言覆盖不全 | markdown-one 内置 8 组正则补丁；缺的在库里补，不在本仓库补 |
| 表格无横向滚动策略 | **已解决**：`BlockViewportConfig` 给表格限高 + 双向滚动 + 缩放 |
| 图（Mermaid / PlantUML / DOT） | `diagram-render` 在 Compose 内绘制，失败回落源码（ADR-035 的边界已迁到 markdown-one） |
| 数学 / 富嵌入 | 公式走 `ratex`、原始 HTML 走 ksoup 原生渲染（ADR-004 的"不引 WebView"仍然有效） |

---

## 4. 输入框

| 需求 | 方案 |
|---|---|
| 多行输入 + IME | `BasicTextField` + `BasicTextFieldState` |
| chip（@ 引用、/ 指令） | `InlineTextContent` + 自定义 Placeholder |
| Markdown 语法高亮 | 可选，`AnnotatedString` + 正则（后置） |
| @ / 补全浮层 | 自研（光标位置 + `Popup` + 键盘导航） |
| 粘贴图片 / 文件拖入 | AWT 剪贴板 + Compose `dragAndDropTarget` |
| 自动高度 | `BasicTextField` + 最大行数约束 |
| Enter 发送 / Shift+Enter 换行 | `onPreviewKeyEvent` |
| 历史（上下箭头） | 自研 ring buffer |

**不要用编辑器内核**（kodemirror / SweetEditor）做输入框：它们要自己接管 IME 组合态，
中文输入是硬需求，这条不可赌。编辑器内核留给"文件/代码编辑"场景（本期不做）。

---

## 5. 终端

| 需求 | 选型 | 坐标 |
|---|---|---|
| 终端组件 | **BossTerm 裁剪 fork**（Compose Desktop 原生，自维护） | git submodule `modules/terminal-bossterm` → `Shinglem/BossTerm@hermes`，composite build 接入 |
| PTY | BossTerm 内部的 pty4j（**传递依赖**，本仓库不单独声明） | `org.jetbrains.pty4j:pty4j`（随 fork 走） |

> **2026-10-31 落地情况（ADR-054）**：不再从 JitPack 拉 `bossterm-core-mpp` / `compose-ui`。
> 原因见下面「选型经过」的历史记录——JitPack 现场构建经常超时；而整包引入还带进了
> supabase / ktor-server / MCP SDK / Android target 等用不到的东西。
> 改为 fork 后裁剪到终端核心子集（`compose-ui` 101,163 行 → 23,869 行），
> 并补齐宿主需要的读屏接口（`selectedText` / `tailText` / `snapshot`）。

### 选型经过（三个候选）

| 项目 | UI | 发布渠道 | 成熟度 | 接入成本 |
|---|---|---|---|---|
| **BossTerm（选中）** | Compose 原生 | JitPack（v1.0.7…v1.2.155 构建 ok；Maven Central **无** `com.risaboss`） | 2025-11 起 | 4–6 天 |
| KetraTerm（备选） | Swing | Maven Central + BOM | 2026-02 起 | 6–8 天 |
| JediTerm（兜底） | Swing | **无 Maven 发布**，JitPack 需锁 commit（最新几次 Error） | 13 年，JetBrains 全系 IDE 在用 | 8–12 天 |

### 风险与许可证

- BossTerm 上游是 **LGPL-3.0**；它同时提供 Apache-2.0 双许可，**fork 取 Apache-2.0**（ADR-054）。
- 项目提交密度异常高（2,401 commits / 约 1 年），实现质量需 spike 验证（已按 ADR-054 实测）。
- 备选切换顺序：BossTerm（已选中并裁剪）→ KetraTerm（Apache 2.0）→ JediTerm（已否决，见 ADR-040/054）。

---

## 6. 数据与基础设施

| 需求 | 选型 | 坐标 |
|---|---|---|
| HTTP + WebSocket 客户端 | Ktor client | `io.ktor:ktor-client-core`、`ktor-client-cio`、`ktor-client-websockets`、`ktor-client-content-negotiation` |
| JSON | kotlinx-serialization | `org.jetbrains.kotlinx:kotlinx-serialization-json` |
| 协程 | kotlinx-coroutines | 随 Kotlin |
| 时间 / 相对时间 | kotlinx-datetime | `org.jetbrains.kotlinx:kotlinx-datetime` |
| 偏好持久化 | multiplatform-settings | `com.russhwolf:multiplatform-settings`（desktop 用 `PreferencesSettings`/`JvmPreferencesSettings`） |
| 日志（可选） | Kermit | `co.touchlab:kermit` |
| 拖拽排序（可选） | Reorderable | `sh.calvin.reorderable:reorderable` |
| Windows 原生通知（可选） | Nucleus notification-windows | `dev.nucleusframework:nucleus.notification-windows`（MIT，2.5.18）。**只在用户打开「使用系统原生通知」时启用**；默认走应用内自绘窗。为什么不自己写：WinRT toast 的 JNI/COM 绑定 + AUMID/开始菜单快捷方式是一整套系统集成，见 [`详细设计/工程与代码/12-通知窗.md`](../详细设计/工程与代码/12-通知窗.md) |

### 不引入

- **不用 SQLDelight / Room**：本期只需存布局、草稿、最近会话等少量 key-value，JSON 或 settings 足够。
- **不用 DI 框架**（Koin 等）：手动构造 + 一个 AppContainer 即可，减少编译期与心智负担。
- **不用导航库**（Decompose / Navigation 3）：单窗口 + 一个顶层状态（`AppMode`）足够。
- **不用 react-query 式缓存框架**：`StateFlow` + Repository 手写失效。

---

## 7. 协议层（**自研**，唯一例外）

生态里没有可用库，原因：需要一个 WebSocket 上的 JSON-RPC 2.0 客户端，且必须支持

- 双向请求（server→client 请求，需应答且幂等）
- 心跳 + deadline 判死
- 断线重连 + 事件重放（`session.events.since`）
- 请求超时分级（默认 30s / 启动期 60s / `prompt.submit` 1800s）

现成库（jsonrpc4j 等）都不覆盖这些组合。实现约 400–600 行，见 `03-architecture.md`。

---

## 8. 打包与发布

| 需求 | 方案 |
|---|---|
| 安装包 | `packageDistributionForCurrentOS`（MSI / exe） |
| 代码签名 | 本期不做（自用） |
| 自动更新 | 自研极简版：启动时查版本文件 → 下载 → 校验 → 提示替换 |
| 崩溃/日志 | 本地日志文件 + 设置页"打开日志目录" |

---

## 9. 版本管理

所有版本集中在 `gradle/libs.versions.toml`。引入新依赖的顺序：

1. `search.maven.org` 按 group 查可用性（**不要只看 README**）
2. JitPack 项查 `https://jitpack.io/api/builds/<owner>/<repo>`
3. GitHub `api.github.com/repos/<owner>/<repo>/license` 查许可证
4. 搜 `<组件> <目标平台> issue` 查已知缺陷
5. 写入本文件 + `libs.versions.toml`
