# 07 · 决策记录（ADR）

> 格式：**决策 / 理由 / 替代方案 / 后果**。改动一条要新增编号，不要改写历史。

---

## ADR-001 · 只重写前端，后端完全不动

**决策**：本项目只消费 Hermes 后端（`127.0.0.1:9119`）的公开契约，不修改任何 Python / gateway / dashboard 代码。

**理由**：改后端会与官方客户端分叉，失去"跟随上游"的能力；且后端是 agent 能力所在，改动风险远大于收益。

**后果**：契约变更时本项目需跟随（靠生成器缓解）；不能绕过后端实现"更快"的私有通道。

---

## ADR-002 · 放弃前端插件生态

**决策**：不实现 `@hermes/plugin-sdk` 的插件宿主，不加载任何 `plugin.js`。

**理由**：插件贡献项是 `render?: () => ReactNode` —— 渲染需要 React 运行时与 DOM。要在 Compose 里跑，只能内嵌浏览器引擎（= 自造 WebView），+1～1.5 人年。
分母量化：plugin-catalog 共 337 条，`category: desktop` 的 86 条（约 1/4）才是前端插件；其余约 250 条是后端插件（工具/记忆/模型提供者/平台适配器），**与前端语言无关，零成本保留**。

**替代方案**：内嵌 WebView + React 运行时（成本过高）；支持 `data` 型贡献（主题/命令）暂缓。

**后果**：丢掉 UI 扩展；后端能力（工具、技能、cron、平台适配器）不受影响。

---

## ADR-003 · 不做 JS 引擎宿主

**决策**：不引入 QuickJS / J2V8。

**理由**：即使有了 JS 引擎，`render()` 返回的仍是用 React 描述的元素树，需要 React + DOM 渲染层才能落地。引擎本身解决不了渲染问题。

**后果**：插件体系整体不做（见 ADR-002）。

---

## ADR-004 · 富内容渲染降级

**决策**：数学公式（KaTeX）、mermaid、社交/HTML 嵌入**不做渲染**，显示为源码文本或可点击链接。

**理由**：Kotlin 侧无等价物；纯 Kotlin 的 LaTeX 渲染器很新且不完整；mermaid 只有 WebView 路径。

**替代方案**：将来需要时，用 1 个 WebView 岛统一承载（mermaid + HTML 预览）。

**后果**：AI 回答里的数学与图表显示为源码。验收口径明确接受（R10）。

> **2026-10-31 修订（ADR-052 补充）**：本条已作废 —— 数学公式改由
> `io.github.huarangmeng:latex-renderer`（纯 Compose 矢量绘制 + KaTeX 字体）在
> `modules/markdown-one` 内排版，块级与行内都渲染；mermaid 由纯 Kotlin 引擎
> `mermaid-compose` 绘制。**仍然不引 WebView/JCEF**（JCEF 只作为原始 HTML 的可选回落）。

---

## ADR-005 · 不做文件浏览与编辑器，跳转外部编辑器

**决策**：放弃文件树、代码编辑器、diff 视图。聊天里的文件路径渲染成可点击链接，点击在 VSCode / IDEA 中打开到指定行。

**理由**：原版这三块约 3,388 行生产代码（files 1,637 / review 1,158 / code-editor 392 / 其余），
而外部编辑器在本机已具备（`C:\app\VSCode\bin\code.cmd`、`C:\app\IDEA\current\bin\idea64.exe`）。

**实现**：`code.cmd -g "路径:行:列"` / `idea64.exe --line N "路径"`；探测顺序 = 用户配置的命令模板 → PATH → 常见安装路径 → 注册表 → 降级（默认程序 / 资源管理器定位）。
相对路径需用会话的 cwd 拼成绝对路径。

**后果**：省约 15–20 人天与整套编辑器依赖。

---

## ADR-006 · chip 用"预处理 + annotator"

**决策**：消息里的 `@blob:xxx` 这类引用，在解析前替换为 `[标签](hermes://blob/xxx)`，再用 `markdownAnnotator` 把这类链接染成 chip 样式（配合 extended spans 的 `RoundedCornerSpanPainter`）。

**理由**：`annotator` **只能消费/替换已有 AST 节点**，不能凭空创造块级元素。预处理是唯一低成本路径。

**替代方案**：自定义 `MarkdownComponents`（成本更高，留待需要"带图标的真组件"时）。

**后果**：chip 是"看起来像 chip 的行内元素"，不能带下拉菜单。

---

## ADR-007 · 输入框用 BasicTextField，不用编辑器内核

**决策**：composer 用 `BasicTextField` + `InlineTextContent`，不引入 kodemirror / SweetEditor 等编辑器内核。

**理由**：自绘编辑器要自己接管 IME 组合态，而中文输入是硬需求（Compose Desktop 的中文 IME 历史上问题集中）。`BasicTextField` 的 IME 由 JetBrains 维护。
另外 chip 是"不可编辑的内联块"，`InlineTextContent` 原生支持，编辑器内核里要写 atomic decoration + 光标跨越规则。

**后果**：输入框没有完整的编辑功能（多光标、折叠等），也不需要。

---

## ADR-008 · 布局用 ListDetailPaneScaffold，接受 CMP-8735，放弃拖拽调宽

**决策**：主布局用 M3 canonical list-detail（`ListDetailPaneScaffold`）；接受它在 desktop 上不随窗口 resize 自适应的已知缺陷（CMP-8735）；不提供拖拽调宽。

**理由**：官方组件、语义正确（list + detail 正好对应"会话列表 + 对话"和"能力分类 + 设置页"）；自行实现 Row 布局要多写约 150 行且失去响应式退化。

**替代方案**：自写 Row + `windowSizeClass`（成本略高但可控）—— 若 CMP-8735 影响体验，作为兜底。

**后果**：中栏宽度由 scaffold 决定（约 360dp），不可拖。

**阶段性说明**：P0 阶段先用显式 `Row` 落地三栏，接入时点与理由见 ADR-014。

---

## ADR-009 · 终端用 BossTerm

> **演进注记（2026-10-31 补）**：本 ADR 的结论**最终仍然成立**，但中间换过一次实现 ——
> 先被 [ADR-040](#adr-040--终端用-jediterm否决-bossterm--termora--mosaic) 换成 JediTerm + SwingPanel，
> 再由 [ADR-054](#adr-054--终端改用-compose-原生渲染自维护-bossterm-裁剪版删除-jediterm--swingpanel)
> 换回**自维护的 BossTerm 裁剪版**（Compose 原生渲染，submodule + composite build）。
> 因此下文的「许可证 LGPL-3.0」已不适用：fork 取 **Apache-2.0**；
> 「PTY 用 Pty4J」也改为「PTY 由 BossTerm 内部的 pty4j 负责，本仓库不单独声明」。

**决策**：终端组件选 BossTerm（Compose Desktop 原生），PTY 用 Pty4J。

**理由**：三个候选里唯一 Compose 原生的，省掉整个 SwingPanel 互操作层（焦点/DPI/重绘）。
JediTerm 质量最高但**没有 Maven 发布**（`search.maven.org` numFound=0，JitPack 最新提交构建 Error），且 UI 是 Swing。

**替代方案**：KetraTerm（Apache 2.0、Maven Central、Swing UI）→ JediTerm（兜底）。

**后果**：接受 LGPL-3.0 许可证（闭源商业分发需合规评估）；接受项目较新（2025-11 起）的风险，需 spike 验证。

---

## ADR-010 · 数据层双通道 + 协议层自研

**决策**：WebSocket JSON-RPC（gateway）与 HTTP REST（`/api/*`）两套客户端并存；JSON-RPC 协议层自己实现（约 400–600 行）。

**理由**：后端本身就是双通道（管理类操作走 REST，会话/流式走 WS）。
JSON-RPC 那层必须支持双向请求 + 心跳判死 + 重连事件重放 + 分级超时，生态里没有库覆盖这个组合。

**后果**：多写约 500 行协议代码，但可控且好测；换来的是不依赖任何不成熟库。

---

## ADR-011 · 本期不做 Bot Mode / 群聊

**决策**：只实现"会话"主窗口；不做 bot roster、群聊、canonical bot chat、cron 之外的面板。

**理由**：原版 `hermes-bots` 生产 30,062 行，其中群聊 8,783、roster 2,439、头像 1,606、远程屏幕 1,604。
用户明确"目前只实现会话窗口"。

**后果**：省约 27 人天与约 4,000 行 Kotlin。IM 风格聊天窗口留作后续（架构上已预留：连接单例）。

---

## ADR-012 · 单中文 + M3 原生视觉

**决策**：只做简体中文；配色、密度、字号全部使用 Material 3 默认值，不做品牌化主题，不做视觉复刻。

**理由**：原版 i18n 51,875 行 × 10 语言无复用价值；视觉复刻的成本高且收益低。

**后果**：观感与官方客户端不同（更"标准 Material"）；将来加语言需引入 Compose Resources。

---

## ADR-013 · 不引入 DI / 导航 / 数据库框架

**决策**：不引入 Koin、Decompose、SQLDelight。

**理由**：单窗口 + 少量 key-value 持久化 + 一个顶层状态（`AppMode`），引入框架的收益小于成本。

**后果**：手动构造 `AppContainer`；将来多窗口或复杂导航时再评估。

---

## ADR-014 · P0 三栏用显式 Row，暂不接 `ListDetailPaneScaffold`

> **已被 ADR-037 取代**（2026-10-28）：P1 的接入条件早已满足，本次已换成 `ListDetailPaneScaffold`。
> 注意其中「rail 在 scaffold 之外」的判断仍然成立，ADR-037 沿用。

**决策**：P0 的骨架用 `Row { ProfileRail | 中栏 360dp | 右栏 weight(1f) }` 实现，
**不**引入 `material3-adaptive-navigation-suite`，也暂不使用 `ListDetailPaneScaffold`。

**理由**：

1. rail 在 scaffold **之外**（`04-ui-spec.md` §1），scaffold 只覆盖 list + detail；
   当前窗口固定三栏时，显式 `Row` 约束更直观，少一层 navigator 状态。
2. 窄窗退化（< 840dp → 单栏 + 返回）属于 P1 的交互细化，且按 `AGENTS.md` 硬约束 11，
   接 `ListDetailPaneScaffold` 必须把 `ThreePaneScaffoldDirective` 绑到 `windowSizeClass` 并 `key(sizeClass)`。
   P0 先不引这个依赖，避免"引入了但没正确绑窗宽"（CMP-8735）的半成品。

**后果**：P0 的窗口在窄宽度下不退化（中栏固定 360dp，右栏被压缩）。
P1 接会话详情时同时引入 adaptive 依赖并补上 `windowSizeClass` 绑定。

---

## ADR-015 · backend 凭据来自环境变量 / `.env`，不落偏好文件

**决策**：WS / REST 的 `token` 按以下顺序解析，**不**写入本地偏好：

1. 环境变量 `HERMES_DASHBOARD_SESSION_TOKEN`；
2. `$HERMES_HOME/.env` 中的同名键；
3. `~/.hermes/.env` 中的同名键。

**理由**：桌面端连的是**已在运行**的本机 backend（P6 才由桌面自己 spawn 并 mint token）。
官方 dashboard 用的就是这几处来源，跟着用可避免与官方客户端分叉，也避免把 token 明文存进
`java.util.prefs`（注册表明文）。

**后果**：backend 使用非默认 `HERMES_HOME` 且没设该环境变量时读不到 token，
WS 会握手 `403` → 状态条显示"未连接"；P6 的设置页需要提供手填 token 的入口。

---

## ADR-016 · 契约类型先手写最小集，生成器延后

**决策**：P0/P1 用到的契约类型（`session.list` / `profiles.list` / `session.history` 的 DTO）
先在 `core/contract/GeneratedDto.kt` **手写**，字段名与后端 wire 名逐一核对
（来源 `tui_gateway/contracts/sessions.py`、`common.py`），文件头标明"手抄子集"。

**理由**：契约生成器（读 Python 契约出 Kotlin DTO）是本项目最大的一笔省，但它本身是一个
独立工程；P0 只需要 6 个类型，先手写能把"能不能连通、能不能列出真实会话"这件事提前验证。
`03-architecture.md` §2.3 已把"手写最小集"列为生成器成本超预期时的退路。

**后果**：手抄子集有漂移风险，必须靠字段核对与集成验证兜住；生成器落地后整包替换该文件，
调用方（Repository）不受影响。当前手写量 6 个类型，仍在上限（30–50）之内。

---

## ADR-017 · 读历史走 REST 分页，不走 `session.resume`

**决策**：打开会话读历史用 `GET /api/sessions/{id}/messages?limit=500&order=latest`，
`session.resume` 留给 P2 的"发送消息"（真正需要 live agent 时）。

**理由**：

1. `session.resume` 默认走 `_resume_cold`：读 transcript 的同时**注册 live session** 并异步预热 agent
   （`_schedule_agent_build`）。只是翻历史也占一个会话槽、拉高内存，且 `session.close` 要自己管生命周期。
2. 后端注释明确写着桌面端的可见 transcript 由 REST 分页负责
   （`methods_session.py::_resume_deferred`：*Desktop owns the visible transcript through bounded REST pages*）。
3. 实测确认 `session.history` 只对 live session 有效（对仅有落库行的会话返回 `4007 session not found`），
   所以"先 resume 再 history"是多余的两步。

**后果**：历史读取与 agent 生命周期解耦，`ChatRepository` 不碰 WebSocket。
P2 发送消息时仍需 `session.resume`，届时由 `ChatViewModel` 显式调用并把返回的 runtime `session_id`
与落库 id 分开保存（resume 的 `session_id` 是 runtime id，`session_key` 才是落库 id）。

---

## ADR-018 · 工具调用卡片默认折叠、结果截断 20k 字符

**决策**：工具卡片默认只显示标题 + 状态，展开后才渲染参数（≤4k）与结果（≤20k），超出部分加提示。

**理由**：真实会话里单条工具结果常见 30–50 KB（实测会话有 6 万字符级的 `session_search` 输出）。
全部展开会让 `LazyColumn` 的可见项一次进入布局几十万字符，滚动直接卡住；而默认折叠时这些内容根本不组合。

**后果**：看完整输出需要复制整条到外部查看（或后续做"打开原始结果"）。
"取全部结果"属于 P2/P4 的候选功能，届时用按需请求而不是一次性全渲染。

---

## ADR-019 · 引用与命令用"上色"表示，不用 `InlineTextContent` chip

**决策**：`@引用` 与 `/命令` 在输入框里按区间上色（`AnnotatedString` 的 span），
不插入 `InlineTextContent` 实体 chip。

**理由**：

1. `InlineTextContent` 需要自己维护占位符与文本的映射，删除、跨行选中、粘贴合并时都要重建，
   一旦漏掉某个编辑路径就会出现"chip 变成乱码字符"或"光标跳走"。
2. 用户的编辑方式是直接改文本（补全插入的是 `@file:...` 明文），chip 的"整块删除"语义
   与后端契约（`prompt.submit` 收 Markdown 源码）不一致。
3. 上色只依赖 `TextFieldValue` 的当前文本 + 光标位置，是纯函数，没有需要同步的第二份状态。

**后果**：chip 的"整块删除"退化为普通文本编辑（按字符删）。
`05-acceptance.md` 的"chip 可整块删除"按此实现解释；序列化后仍然是同一段文本，回填天然一致。

---

## ADR-020 · 补全的替换区间在**接受时刻**重算，不留请求时的旧光标

**决策**：`CompletionState.accepted` 的替换终点取 `TextFieldValue.selection.end`（当前光标），
不回退到请求补全时记录的 `end`；`start` 仍用后端/请求时给出的起点。

**理由**：补全是 150ms 防抖 + 异步 RPC，返回时文本可能已经更长。
实测：输入 `/sk` 时发请求，返回前已输到 `/skill`，接受后得到 `/skinill`
（`/skin` 替换掉 `start..end` 的旧区间，`ill` 残留）。

**后果**：补全永远替换到当前光标位置，符合"我就是想替换正在输入的这个词"的直觉。
光标在起点之前（用户把光标移走了）时才退回旧的 `end`。

---

## ADR-021 · 补全浮层不接受时不要把 Enter 吞掉

**决策**：`acceptCompletion` 返回 `Boolean`；`Composer` 的按键分派只在**确实接受**时 `return true`，
否则继续走后面的 Enter 发送逻辑。

**理由**：浮层可能"还在但已无有效项"（文本在补全返回后又变化，`entries` 为空）。
按旧实现 Enter 会被直接吞掉，表现为"按回车没反应"，且用户完全无法察觉原因。

**后果**：Enter 在任何情况下都能发送（除非浮层真有可接受项，或处于 IME 组合态）。
同时接受补全后**不再**立刻对接受结果重新请求补全 —— 否则同一条目会再次匹配自己，
浮层永远弹着，Enter 永远停在"接受"上。

---

## ADR-022 · 图片粘贴由前端自己读剪贴板，不只依赖 `clipboard.paste`

**决策**：`Ctrl+V` 发现剪贴板有图时，**先**调后端 `clipboard.paste`；失败则前端自己把图存成
临时 PNG，再走 `image.attach(path)`。

**理由**：gateway 可能以服务方式运行在 **Session 0**（本机实测：python 进程 `SessionId=0`）。
Session 0 是独立窗口站，**有自己孤立的剪贴板**，永远读不到用户会话里复制的内容 ——
后端的 `save_clipboard_image()` 用 PowerShell 读 `[Clipboard]::GetImage()` 必然拿到空，
于是 `clipboard.paste` 对这类部署**永久返回** `{"attached": false, "message": "No image found in clipboard"}`。
而 Compose 前端跑在用户会话里，AWT 能读到真实图片。

**后果**：

- 图片粘贴不再依赖后端所在会话，也不依赖后端找到 PowerShell（那套探测同样会失败）。
- 多一条兜底路径：同会话部署仍优先用 `clipboard.paste`（省一次落盘与一次 `image.attach`）。
- 后端返回的英文 `message` 不再原样展示给用户。
- 前端落盘的临时文件留在系统临时目录（`hermes-clip-*.png`），由系统清理；要长期保留的图走
  `image.attach`，后端会收进会话 `attachments/`。

**验证**：橙色测试图 → 剪贴板 → `Ctrl+V` → 消息里出现 `@image:...png`，
模型 `vision_analyze` 准确描述"纯橙背景 / 白色粗体 HERMES / 约 300×167"，说明图片字节真的送达。
---

## ADR-023 · 能力页的写操作一律"回读校验"，不信任写接口回执

**决策**：`capabilities` 的每个写操作（技能/工具集开关、平台配置、定时任务生命周期）
成功之后**重新 GET 一次列表**，用后端返回的真值刷新 UI；失败时对表单类操作也回读一次。

**理由**：

1. 写接口的回执只有"我收到了"级别的信息（`{ok:true, name, enabled}`），而 UI 要显示的是
   派生状态：工具集的 `available` / `configured`、平台的 `state` / `error_message`、
   任务的 `state` / `latest_execution`。这些只有列表接口给。
2. 平台配置的写入会同时改 `.env` 与 `config.yaml`；失败时可能"密钥写进去了、开关没落盘"。
   只回滚 UI 会让界面与磁盘长期不一致，回读才是唯一可靠的对账手段。
3. 乐观更新与回读可以共存：开关类先乐观切、失败回滚快照、成功仍回读（成本是一次 GET）。

**后果**：每次写操作多一次 GET（本机 < 50ms，可接受）；换来的是不会出现"界面上是开着的、
后端其实是关的"这类最难排查的问题。开关类若回读失败，保留乐观值 + 错误提示，不回滚 ——
因为此时无法判断真实状态，让用户再点一次比显示一个猜测值更诚实。

---

## ADR-024 · 能力页的 profile 作用域：跟随全局切换、切了就作废

**决策**：所有 `/api/...` 读写都带 `?profile=<当前 profile>`；`setProfile` 变化时清空三页的
`Loadable` 与展开状态，回到 `Idle` 让下一次进页重新拉。

**理由**：`profile` 在 Hermes 里是"另一套配置与另一批会话"，技能禁用列表、消息平台密钥、
定时任务全都按 profile 隔离。若切 profile 后沿用旧列表，用户点开关会写到错误的 profile 上 ——
这是会造成真实数据污染的错，必须靠"作废重拉"来避免。

**后果**：切 profile 有一次性加载（三页分别拉，不是齐拉）；换来的是不会跨 profile 写错。
搜索词（`skillQuery`）与分类选择（`section`）故意**不**清空 —— 那是用户的浏览位置，不是数据。

---

## ADR-025 · 能力页不用 `NavigationRail`，中栏分类自绘

**决策**：能力模式的中栏（技能与工具 / 消息平台 / 定时任务）用 `Surface + Column` 自绘行，
不用 `NavigationRail`，也不把三个分类做成 Tab。

**理由**：`NavigationRail` 是"应用级导航目的地"语义，它的宽度、图标密度、选中指示器都为
4–7 个目的地设计；能力分类只有 3 个、且与会话模式的会话列表**同一位置**，需要视觉连续。
用 Tab 又会让"右栏是设置页"的关系变模糊（Tab 通常意味着"切换同一区域的内容"）。

**后果**：约 40 行自绘代码；换来的是与会话模式的中栏宽度、分隔线、行高完全一致，
切模式时视觉不跳。
---

## ADR-026 · 成品用自拼安装树（绿色版），jpackage 目录模板不用

> **2026-11-01 注记**：产物 `appName` 已从 `Hermes` 改为 `hermes-desktop-compose`，
> 下文提到的 `Hermes.cfg` / `Hermes.exe` 现在分别是 `hermes-desktop-compose.cfg` / `.exe`；
> 结论（不用 CMP 的目录模板、只取它生成的启动器）不变。

**决策**：成品目录自己拼（`rt/` + `config/` + `lib/` + `bin/App.jar` + `App.bat`），
构建走一个 `Sync` 任务；`compose.desktop.nativeDistributions` 保留给将来的 MSI 安装版，两者并行。

**理由**：`createDistributable` 的产出模板（实测 2026-10-27，CMP 1.12.2）是
`app/*.jar + Hermes.cfg` / `runtime/` / `Hermes.exe` 三件套，目录名被**编译进 exe 的 launcher 镜像**，
`--app-content` 只能往 `app/` 加文件。想在它基础上得到 `lib/ + bin/ + rt/` 必须后处理重命名，
一重命名 exe 立刻找不到 `app\Hermes.cfg`。此外 `app/` 里的 jar 全被追加 hash 后缀，
"`bin/App.jar` 是主 jar"这个语义无法表达。

**替代方案**：① 接受 jpackage 目录结构（放弃绿色版的可移植性与自带 JDK 的可替换性）；
② 只做 `App.bat` 不做 exe（未来无任务栏图标、会带黑框）。

**后果**：
- 结构完全受控，`rt/` 可整体换成 JBR 而不动任何脚本
- 打包脚本自己维护（约 40 行 Gradle），不会随 CMP 版本变化而失效
- `App.exe` 需要额外的壳工具（launch4j 或 jpackage 只出 exe）；不自动更新

---

## ADR-027 · 配置按「改完要不要重启」切分，`state.json` 用 `Settings` 的 JSON 文件后端

**决策**：四个文件各归其位——`config.json`（进程级，改了重启）、`options.json`（用户可调，热生效）、
`jvm.args`（JVM 参数，bat 读）、`state.json`（运行态，只有程序写）。
`options.json` / `config.json` 用 `@Serializable` data class；`state.json` 保留 `Settings` 抽象，
后端从 `PreferencesSettings`（注册表）换成自写的 `JsonFileSettings`（约 100 行）。

**理由**：
1. 「改完要不要重启」这条线恰好与「谁能改」完全重合（进程级配置只有用户改、运行态只有程序改），
   所以不需要第二套规则，划分没有歧义。
2. `multiplatform-settings` 实测（jvm 1.3.0）：`PropertiesSettings` 存在但落 `.properties` 格式、
   不是 JSON；`ObservableSettings` 只能监听经由 API 的写入，**监听不到外部编辑器改文件**；
   `Settings` 接口是扁平 key-value，不支持嵌套。所以结构化配置不能套 `Settings`，
   而扁平运行态恰好适合它。
3. `jvm.args` 必须独立成文件：`App.bat` 没有 JSON 解析能力，`for /f` 抠 JSON 脆弱到不该进设计。

**替代方案**：① 全部塞进 `options.json`（草稿逐字写会让文件一直在变，且手改易坏）；
② 保留注册表只放运行态（绿色版"拷走目录"带不走状态，卸载留残留）；
③ 全量改用 `.properties`（与嵌套配置需求冲突，且失去 JSON 的可读性）。

**后果**：
- 注册表彻底不用，`state.json` 随目录走；首次启动需一次性从注册表迁移（约 20 行），否则用户"设置全丢"
- `Timeouts` / `Log.level` 从常量改为从 `AppOptionsHolder.current` 现取，
  业务调用点零改动（`object` 保留）
- `options.json` 需要 WatchService + 内容 hash 回音消除，比纯内存状态多约 150 行
---

## ADR-028 · 基线运行时定为 JBR 25（字节码 25），`rt/` 可整体替换

**决策**：`jvmTarget = JVM_25`，成品自带 JetBrains Runtime 25（`rt/`），开发态也指向同一份。
`rt/` 是**可整体替换目录**：将来要用 JCEF 就换成 JBR with-JCEF，脚本与代码不用改。

**理由**：
1. 成品既然自带运行时，就没有"向下兼容旧 JDK"的必要——兼容 21 只会锁死语言与库的能力。
2. JBR 对 Windows 中文 IME 候选框定位与字体渲染有上游没有的修补，而中文输入是硬需求
   （`AGENTS.md` 硬约束 4）；Compose Desktop 官方也推荐用 JBR 运行。
3. 本机实测（2026-10-27，JBR 25.0.4.1+1-635.79-nomod）：`jvmTarget = JVM_25` 编译通过、
   88 用例全绿、产物 major=69、`:composeApp:run` 起窗与渲染正常、gateway 连接正常。

**替代方案**：继续以 21 为字节码目标（用 27 或 25 编译）——代价是没有收益，且成品 JDK 与字节码
目标长期不一致，反而多一层"到底哪个版本在跑"的困惑。

**后果**：
- **任何人用 JDK 21 及更低版本都无法构建/运行**，`AGENTS.md` 与 `README.md` 的门槛已同步改为 25
- JDK 24+ 起 `System.load` 属受限方法，Skiko 会打印 JEP 472 警告；默认 JVM 参数（`packaging/jvm.args`
  → 成品 `app/Hermes.cfg`）必须带 `--enable-native-access=ALL-UNNAMED`，否则未来版本会直接阻止
- 该 JBR 未被 Gradle toolchain 自动探测到（`installations.paths` 只列了 `jdk-25.0.4.1`），
  所以 `rt/` 来源必须走显式路径（`local.properties` 的 `hermes.rt.dir`），不能指望自动发现
- 中文 IME 候选框定位尚未实测，列为 S5 的验证项
---

## ADR-029 · 双模式（Local 内嵌 / Remote 连接），Local 只接受 sealed payload

**决策**：客户端支持 `local` 与 `remote` 两种后端来源，对上层完全透明（只看
`GatewayTarget(baseUrl, token)`）。Local 模式用随包携带的 **Hermes sealed payload**
（`<home>/hermes/`），启动时 spawn `hermes serve --host 127.0.0.1 --port 0`。
payload **不在运行时下载**，只做检测与引导。

**理由**：
1. payload 是唯一可搬迁形态。实测：官方桌面安装包内的 `app/resources/agent-payload/`
   根目录有 `manifest.json`（`repo`/`store`/`venv` 是相对名），而本机
   `C:\ai\hermes` 这类 self-managed 源码安装把依赖目录命名为 `sha256(app 绝对路径)[:16]`、
   `bin/hermes.exe` 内嵌绝对路径——搬走即 `RuntimeError: no dependency environment is committed`。
2. `--port 0` 让 OS 分配端口，天然解决多实例与端口占用；写死 9119 会与用户已跑实例打架。
3. 两种模式共用同一条 WS/REST 契约，Repository 层不需要第二套代码。

**替代方案**：① 只支持 Remote（要求用户先自己装 Hermes，失去"开箱可用"）；
② 运行时下载 MSIX 并抽取 payload（实测 4.33 GB 下载、5.83 GB 解压、峰值 12 GB 磁盘，
   还要绕 `makeappx` 与 `%2B` 文件名编码陷阱——失败面比客户端其余部分加起来还大）。

**后果**：
- 成品体积 +5.8 GB；`release-test/hermes/` 由发布流程放入，不进 git
- 必须处理冷启动 30–60s（就绪超时默认 90s、下限 45s，不能给 30s）
- payload 升级策略留待 P6；`config/` `skills/` `sessions/` 必须跨版本保留
- **分期**：`remote` 先做（无需 payload），`local` 待拿到 sealed payload 再落地。
  本文 05/06 两篇保留了 Local 的完整调研结论，后续不需重新查证

---

## ADR-030 · Local 后端的拉起与关停规格照抄官方 Desktop

**决策**：命令行、环境变量、就绪握手、关停全部按官方 `electron/` 的实现来，不自己发明。

**理由**：这些值是踩过坑的产物，偏离任何一条都会以难查的形式失败：

| 项 | 若不照做 |
|---|---|
| `HERMES_DASHBOARD_SESSION_TOKEN` 自铸并注入 env | 21 个敏感端点全部 401，且后端自己生成的 token 客户端拿不到 |
| `PYTHONUTF8=1` | Windows GBK locale 下后端 stdio 乱码（中文项目的直接故障） |
| `PYTHONPATH`/`PYTHONHOME` 置空 | 继承其它 checkout 的模块路径 |
| `HERMES_DESKTOP_READY_FILE` + stdout 哨兵双通道 | stdout 哨兵会被拼到 stderr 块末尾（无换行），行首锚定必然漏 |
| `HERMES_PARENT_PID`/`START_MARKER`/`NONCE` | 客户端被强杀后后端变孤儿；marker 与 nonce 必须成对，否则 watchdog 自动 disarm |
| 自己按 PID 关停，不用 `hermes serve --stop` | 后者扫描全机进程表，会杀掉用户自己的实例 |

**后果**：
- `HERMES_DESKTOP=1` 让后端 tick cron——即**替用户执行他自己配置的定时任务**。
  这是用户可感知的副作用，必须给开关（`backend.local.runCron`）并在设置页说明
- 后端无 HTTP shutdown 端点、Windows 无 SIGTERM 语义 → 关停必须三段式
  （断 WS → `destroy()` → 3s → `destroyForcibly()`）+ watchdog 兜底
---

## ADR-031 · 配置：一个结构化底座 + `_custom` 深合并 + 环境变量单点覆盖（前缀 `HDC_`）

**决策**：

1. `config.json` 是**唯一的定义处**（`@Serializable data class`，有类型、可嵌套、带默认值）。
2. 覆盖分两层，**都只覆盖不定义**：
   - **文件层**：同目录的 `config_custom.json`，加载时**深合并**到基础值上
   - **运行层**：`-Dhdc.*` > `HDC_*`
   优先级：`-Dhdc.*` > `HDC_*` > `config_custom.json` > `config.json` > 内置默认。
3. 字段名一律 `snake_case`，于是「JSON 路径 ↔ 环境变量名 ↔ `-D` 键」是唯一且零歧义的机械映射
   （`backend.remote.base_url` ↔ `HDC_BACKEND_REMOTE_BASE_URL`）。
4. 客户端变量前缀 **`HDC_`**（Hermes Desktop Client）。
5. 所有相对路径以**安装目录 `<home>` 为唯一基准**。
6. 文件与 `HDC_` 里出现 schema 外的键：**只 WARN，不阻断**。

**理由**：

1. **不做第二种格式**（`override.properties`）：那要多一套解析、一套类型约定、一套错误信息；
   改一个嵌套值还得凭空知道扁平键怎么拼。`_custom` 同类型文件深合并只有一套解析器，
   且是"升级不丢用户设置"的标准做法（同 `appsettings.Development.json`、
   `docker-compose.override.yml`）。
2. **不搞两份完整配置**（两个文件都能定义全部项）：两个定义源必然漂移。`_custom` 只表达
   "这些键我要改"，不会形成第二套默认值。
3. **深合并而不是整体替换文件**：用户只想改一个 `base_url` 时不必复制整份文件，
   新版默认值也能随升级生效。
4. **`snake_case` 是映射成立的前提**：若字段是 `camelCase`，`baseUrl` 变成 `BASE_URL` 还是
   `BASEURL` 必然出错，而**拼错的变量名只会静默无效**。
5. **前缀 `HDC_`**：`HERMES_*` 已被后端占用（Local 模式下子进程继承环境，会互相污染）；
   `APP_` 又太通用（`APP_HOME`/`APP_DATA` 很多软件在用）。
6. **未知键只 WARN**：多一个不认识的名字不该让程序起不来——它可能只是给新版本预留的键。
   但类型错误必须大声失败，两者性质不同。

**替代方案**：① `override.properties` 扁平覆盖（第二种格式）；② 两份完整配置（漂移）；
③ 只 JSON 无覆盖（部署改一个地址要动基础文件、升级会覆盖）；④ 只环境变量不落文件。

**后果**：
- **数组是整体替换、对象是递归合并、`null` 是覆盖成 null（不是删键）**——这三条必须写进文档，
  否则会出现"写了空数组却删不掉元素"的困惑
- 未知键只 WARN 的代价：**`_custom` 里拼错的键会静默无效**。补偿手段是 WARN 带原文
  （`WARN 未知配置键: backend.remote.base_urll`）+ 已知键集合由
  `AppConfig.serializer().descriptor` 自动生成（不手写维护）
- 已移除曾考虑的 `HDC_CONFIG_JSON`（整段 JSON 环境变量）：容器场景挂载 `config_custom.json`
  更可靠（实测 cmd 传含 `&` 的 JSON 会被截断），少一条路径就少一套优先级与失败模式
- Local 模式 spawn 子进程**按白名单传环境变量**，自己的 `HDC_*` 一个都不传
- Windows 上 `set X=1` 只影响当前控制台，双击启动需 `setx` 或用 `-D`

---

## ADR-032 · 日常启动用 `javaw`（无控制台），另配 `App-console.bat`；编码三层统一 UTF-8

**决策**：
1. `App.bat` 用 `javaw.exe` + `start "" …`（不等待），日常使用不留黑框；
   另提供 `App-console.bat`（`java.exe`，同步、不重定向、出错 `pause`）专供排障。
2. 不把 stdout/stderr 重定向到文件，改由应用自己写日志 + `-XX:ErrorFile` 兜住 JVM 崩溃。
3. 编码统一：控制台 `chcp 65001`、bat 文件 UTF-8 无 BOM + CRLF、
   JVM `-Dstdout.encoding/-Dstderr.encoding/-Dstdin.encoding=UTF-8`、应用内文件读写显式 `Charsets.UTF_8`。
4. **bat 的注释一律 ASCII**，中文只出现在 `echo` 行。

**理由**（全部有实测支撑）：
- `java.exe` 的 PE Subsystem = 3（CUI，必带控制台），`javaw.exe` = 2（GUI）。不加 `start` 时 bat
  会同步等 javaw 退出 → 黑框照样留着，等于白用 javaw。实测加 `start` 后 bat 在 197–210ms 返回。
- `start` + `>>` 会让 GUI 子进程**独占**目标文件句柄，日志连记事本都打不开
  （`The process cannot access the file`）。所以放弃重定向。
- JVM 的 `stdout.encoding` 默认取**系统 ANSI**（实测 `GBK`），不是 `file.encoding`（JDK 18+ 已是 UTF-8）。
  不显式覆盖，重定向/管道拿到的字节就是 GBK，按 UTF-8 读是乱码（已实测对照）。
- cmd **逐行**读批处理且按**字节偏移**定位：`chcp` 之前的行按 GBK 解码 UTF-8 字节；即使 `chcp` 在前，
  含多字节字符的长注释行也会让偏移错位，把注释后半截当命令执行（实测 `'��则' is not recognized`）。
  所以注释必须 ASCII。

**替代方案**：① 一直用 `java.exe`（黑框，且关闭控制台会杀进程）；② `javaw` + 重定向（句柄独占）；
③ 用 VBS/wscript 包一层隐藏窗口（多一个文件要维护，且杀软敏感）；④ bat 全英文（连 `echo` 也不用中文，
对中文用户不友好）。

**后果**：
- javaw 下未捕获异常的堆栈**看不到**（`AppLogger` 只记它自己产生的行）→ 列为 S2 小项：
  `main()` 早期用 `System.setErr` 接管 stderr 到日志文件
- 排障多一步"先换 `App-console.bat`"，需要在 README 与错误提示里说清
- bat 里的中文只保留在 `echo` 行，改文案时不要顺手往注释里写中文
- **不要**设 `sun.jnu.encoding=UTF-8`：Windows 上它决定文件名在 Java 与 Win32 API 之间的编码桥接，
  改了会让中文路径打不开（实测默认 `GBK`，保持不动）
---

## ADR-033 · `options.json` 宽松失败：坏文件只 WARN 并沿用旧值，模板只列已实现字段

**背景**：`config.json` 与 `options.json` 都是用户可编辑的 JSON，但加载时机与语义完全不同：
前者决定进程能否连上后端（缺 `base_url` 就等于残废），后者只是"可选优化"。若两者共用一套
"非法即拒绝启动"的策略，用户改个主题写错逗号就打不开程序，代价与收益不成比例。

**决策**：
1. **`config.json` 严格**：解析失败 / 校验失败 → 弹原生对话框 + 退出码 2（已有）。
2. **`options.json` 宽松**：解析失败或校验失败 → `WARN` 说明字段与原因，**沿用上次成功应用的选项**，
   进程继续跑；文件改回合法后自动恢复应用，不用重启。
3. **`options.json` 模板只列已实现的字段**。解析器可以认识 `font_scale`、`chat.*`，
   但在 UI 真正消费之前不写进 `packaging/config/options.json`。

**理由**：
- 热生效的失败窗口比启动期小得多：用户此刻就在界面前，改错了立刻能看到"没反应"，
  比"程序打不开、去翻日志找哪一行"轻得多。
- 沿用旧值而不是回落默认值：用户可能只改错一个字段，其余 5 个改对的字段不该被一起丢掉。
- 假开关比缺开关更伤信任：`options.json` 里写 `"font_scale": 1.2` 却毫无反应，用户会认为功能坏了；
  模板里不出现该键，用户就不会去改。

**替代方案**：① 与 `config.json` 同样严格（写错即退出 —— 对可选配置过重）；
② 失败即回落默认值（用户多项修改全丢，且界面上突然变回默认像 bug）；
③ 校验失败就删掉文件重建（用户改动无声消失，最糟）。

**后果**：
- 用户改错后"没反应"，需要日志才能知道原因 → 已把 `options.json` 的 WARN 写成
  `options.json 无效，沿用上次选项：<字段> 不能小于 <下限>，当前是 <值>`，可直接照读
- 两套错误策略并存，新人容易搞混 → 本文与 `05-打包与配置.md` §3.4 都写明
- `ThemeMode` 之类的枚举若将来重命名，旧值会变成"校验失败 → 沿用旧值"，界面停在上一主题而非报错，
  排障要多看一段日志
---

## ADR-034 · 运行态存 `config/state.json`，自写 `Settings` 后端 + 一次性注册表迁移

**背景**：绿色版的目标是"整个目录拷走就能在另一台机器跑，不写注册表、不留系统残留"。
但 `multiplatform-settings` 的 `PreferencesSettings` 落 `HKCU\Software\JavaSoft\Prefs\dev\shinglem\hermes`，
既是系统残留，也让「拷目录」丢掉全部偏好（上次模式、草稿、窗口位置）。

**决策**：
1. 自写 `JsonFileSettings : Settings`，落 `config/state.json`；`AppPreferences` 及全部调用点**一行不改**
   （它们看到的仍是 `Settings` 接口）。
2. 保留 `multiplatform-settings` 依赖：`Settings` 接口本身、`MapSettings`（测试）、`getStringOrNull`
   等扩展仍有价值，只是不再用 `PreferencesSettings`。
3. 首次启动若 `state.json` 不存在且注册表节点有键，**一次性**搬过去，然后删掉**自己那一层**节点。
4. `state.json` 的损坏策略与 `config.json` 相反：备份成 `.corrupt` + 用默认值 + WARN，**不阻断启动**。

**理由**：
- `PropertiesSettings` 存在但格式是 `key=value`，不是 JSON —— 与 `config.json` / `options.json`
  放在同一个目录里、格式却不一样，用户几乎必然会手改坏。
- `ObservableSettings` 只监听"经由 API 的写入"，监听不到外部改文件；而 `state.json` 只有程序写，
  根本不需要监听 —— 所以这里不需要 `OptionsWatcher` 那套。
- 延迟 200ms 落盘：会话草稿是逐字写的，同步 IO 会卡输入法；`AppContainer.close()` 里补一次
  `flush()` 覆盖"关窗口前刚改"的窗口。
- 迁移不做的话，已有用户升级后会看到"设置全丢、草稿消失"，这会被当成数据损坏 bug 报上来。

**替代方案**：① 继续用 `PreferencesSettings`（保留注册表残留，与绿色版目标冲突）；
② 用 `PropertiesSettings`（格式不统一）；③ 迁移但不删注册表（两处真相，改了一边另一边变旧）；
④ `state.json` 也严格拒绝启动（丢窗口位置就启动不了，代价与收益不成比例）。

**后果**：
- `state.json` 出现在 `config/` 里，用户可能手改 → 损坏分支已覆盖，且键名清单写在本文 §3.6
- 注册表迁移只跑一次；若用户后来又用旧版启动过，旧版会重新写注册表，但这些值不再是真相来源
- 窗口最大化状态不单独存，记的是还原后的 bounds —— 想"上次最大化、下次还最大化"需要再加一个键

---

## ADR-035 · mermaid 用纯 Kotlin 引擎在 Compose 内出图（修订 ADR-004）

**决策**：mermaid **渲染**，由纯 Kotlin 引擎在 Compose 内直接绘制，**不引 WebView**；
KaTeX 数学、HTML/社交嵌入仍按 ADR-004 降级。画不出来的图族回落为「源码 + 一行提示」。
引擎边界只允许存在于 `feature/chat/MermaidDiagram.kt` 一个文件。

**理由**：
- ADR-004 的前提「mermaid 只有 WebView 路径」已不成立。现成的纯 Kotlin 实现至少两个：
  当前接的 `botiverse/mermaid-native`（Apache-2.0），以及 `swithun-liu/cmp-mermaid`
  （MIT、33/33 图族、Maven Central 直取、**不依赖 Material3**）。
- 走 WebView 岛会让绿色版多一整个 Chromium/CEF 面，并与非目标「不做 JS 宿主」自相矛盾。
- 图是 AI 回答里信息密度最高的一类内容，降级成源码的阅读成本远高于实现成本；
  实测里 Hermes 确实会主动输出流程图与时序图。

**实现**：`MermaidDiagram.kt` = 「源码 → SceneGraph → Canvas」+ LRU 缓存（24）+ 异常/不支持回落；
流式期间**不画图**（每 40ms 全量重排会把滚动抖掉），回答结束后再出图；
文本测量注入 Compose `TextMeasurer`，避免中文标签被引擎自带的 AWT 字体度量裁切。

**替代方案**：① 维持 ADR-004 降级（放弃一个高频内容类型）；
② WebView 岛（违反非目标，且体积与安全面都变大）；
③ `Latias94/merman`（Rust headless，要额外原生可执行文件，与「只带 JVM」冲突）。

**后果**：依赖体积 +8.9 MB；引擎是 0.x 个人项目，靠单文件边界保住可替换性（见 ADR-036）。

> **2026-10-31 修订（ADR-052）**：引擎与边界都已迁出本仓库 ——
> mermaid 渲染现在由 `modules/markdown-one` 提供（`builtin.mermaid` 适配器，
> 接 `io.github.swithun-liu:mermaid-compose`），`feature/chat/MermaidDiagram.kt` 已删除。
> 本 ADR 的**决策本身仍然有效**（纯 Kotlin、不引 WebView、画不出回落源码 + 提示），
> 只是「边界文件」这句话作废，改为「渲染实现只在 `modules/markdown-one`」。

---

## ADR-036 · 延后：可插拔块渲染层（`BlockRenderer` + 注册表）

**决策**：本期**不重构** Markdown 渲染层。保持现有形态：
`multiplatform-markdown-renderer` 的 `MarkdownComponents` 覆盖 + 两个自写适配文件
（`CodeHighlight.kt` / `MermaidDiagram.kt`）。等触发条件满足后，再抽出「块渲染器合同 + 有序注册表」。

**背景（9 个候选的调研结论）**：它们的差别不在功能多少，而在**把「解析 / 布局 / 绘制 / 交互」四层切在哪**——
每一个成熟实现都只让第三方占其中一层：

| 实现 | 它切出来的东西 | 对我们的价值 |
|---|---|---|
| `mikepenz/multiplatform-markdown-renderer` | 组件覆盖点（`MarkdownComponents` 每类块一个 Composable） | 已是我们的底板；覆盖点固定在编译期、类型是它自己的 AST |
| `huarangmeng/Markdown` | `codeBlockHeader` / `tableToolbar` **slot** + `directivePlugins`（宿主注册自定义语法并自己渲染） | 「块级扩展 + 语法扩展」两级思路值得抄；但全家桶钉 `material3:1.10.0-alpha05` |
| `swithun-liu/cmp-mermaid` | `core`（只产出 SceneGraph）与 `compose`（只负责画）分离、`UnsupportedFeature` 显式类型、逐图族 compatibility 门禁 | 引擎边界的样板：**失败是类型，不是异常** |
| `huarangmeng/diagram` | 流式增量（只重算不稳定尾块） | 将来若要「边流边出图」才有用 |
| `huarangmeng/codehigh` | `codehighlight-parser`（21 KB 分词）与 `codehighlight-render`（UI）分离 | 印证「切词」该独立于 UI |
| `soupslurpr/BeauTyXT` | 产品策略：明确子集 + 明确回落 | 我们 R10 的口径已经这么写了 |

**触发条件（任一满足才动手）**：
1. 出现第 3 类需要自定义渲染的块类型（现在只有 2 类：代码块、mermaid）；
2. 换解析器或图引擎需要改动 > 2 个文件；
3. 要渲染 mermaid 之外的富块（KaTeX / PlantUML / HTML 子集）。

**预留设计（现在就定死，避免将来变成到处 `if`）**：
- `BlockNode`：自持中间表示，**不把第三方 AST 类型泄漏进渲染器合同**；
- 合同 = `canRender(node): Boolean` + `Content(node, RenderContext)`；
  `RenderContext` 注入选择 / 滚动 / 流式 / 高亮 / 测量能力，渲染器不得直接摸 chat 域状态；
- 同一 kind 可注册多个，按优先级取首个 `canRender == true`，**链尾永远是源码回落**；
- 渲染器可声明 `measure` 占位高度，喂给滚动策略，避免图块出现时列表跳动；
- 异步渲染器必须可取消、可缓存，缓存键 = 内容 hash + 主题；
- **解析层不自己写**：CommonMark + GFM 的解析交给库（mikepenz 或 huarangmeng 的 parser），
  `BlockNode` 只做一次薄适配。要解耦的是**渲染层**，不是解析器 —— 追求「解析器也能随便插」
  会让我们去维护一份自己的 CommonMark 实现，那是本项目明确不该干的活。
- 解析器本身也不写死：`MarkdownText.kt` 最终应退化为「组装注册表的地方」，让 chat 域不认识任何 Markdown 库。

**在此之前的纪律**：不在 chat 域里继续堆 `language == "mermaid"` 这类判断；新能力一律进适配文件。

> **2026-10-31 落地（ADR-052）**：本文预留的「块渲染器合同 + 有序注册表 + 链尾源码回落」
> 已经在 `modules/markdown-one` 里实现并接入（`BlockAdapter` + `AdapterRegistry.select()`
> + `builtin.source` 兜底）。本仓库不再自建渲染层，也不要再按这段设计在本地重做一遍。

---

## ADR-037 · 主布局接入 `ListDetailPaneScaffold`（两窗格 + rail 外置），并落定视觉规格

**决策**：
1. 主布局用 `ListDetailPaneScaffold`（`adaptive-layout:1.3.0`）+ `rememberListDetailPaneScaffoldNavigator`
   （`adaptive-navigation:1.3.0`），取代 ADR-014 的显式 `Row`。
2. **rail 留在 scaffold 外面**，不进 `extraPane`。
3. 会话模式的中栏用 directive 默认宽度（≥1200dp 约 430dp，840–1200dp 约 360dp）；
   能力模式显式传 `listPaneWidth = 280.dp`（只有三项分类，用 430dp 会留大片空白）。
4. 视觉规格：rail 108dp（< 960dp 收成 56dp 纯图标列），每项「头像 + 文字标签」，选中项整块圆角填充；
   搜索框 40dp（自绘 `SearchField`，M3 `OutlinedTextField` 最小 56dp 太重）；「新建会话」移到列表底部常驻。

**理由**：
- 两个 artifact 都**不依赖 material3**（只 `foundation` / `ui` / `animation` / `window-core` / `collection`），
  与 material3 1.12.2 完全解耦。反而 `material3-adaptive-navigation-suite` 与 material3 对齐的只有
  `1.12.0-alpha03`，是 alpha。
- `NavigableListDetailPaneScaffold`（官方文档推荐的那个）**CMP desktop 没有移植**：核对
  `adaptive-navigation` 1.3.0 与 1.4.0-alpha01 的 desktop jar，只有 `ThreePaneScaffoldNavigator` /
  `BackNavigationBehavior`，desktop jar 的字节流里连 `NavigableListDetailPaneScaffold` 字符串都不存在；
  Android 变体是 6 KB 空壳。它相对 `ListDetailPaneScaffold` 只多做两件事——串 navigator、`AnimatedPane`
  切换动画——两者用现有 API 都能实现；默认的预测性返回动画是 Android 返回手势联动，桌面无意义。
- rail 进 `extraPane` 会被 `maxHorizontalPartitions` 在窄窗收起，导致无法切 profile / 切模式。
  ADR-014 当年「rail 在 scaffold 外面」的判断是对的，这次沿用。
- CMP-8735（desktop 不随 resize 重算 directive）用 `key(singlePane, wide)` 绕开：只在跨 840 / 1200
  断点时重建 navigator，中间 resize 不重排（验收口径见 `05-acceptance.md` L2）。

**替代方案**：① 维持显式 `Row`（窄窗不退化、中栏固定宽）；② 用 `material3-adaptive-navigation-suite:1.12.0-alpha03`
（引入 alpha 且其 `material3` 依赖会被提升到 1.12.2）；③ 自写三窗格（约 150 行，失去官方过渡动画与 back 语义）。

**后果**：
- 依赖 +2 个稳定 artifact；`settings.gradle.kts` 无需新增仓库
- 窗口 ≥ 840dp 恒为两栏；< 840dp 单栏 + 详情顶部返回条（受 `app.window.min_width` 默认 900 限制，
  默认配置下不触发，改小 `min_width` 才会看到）
- 能力页拆成 `CapabilityNavPane` + `CapabilityDetailPane`（`CapabilityPanes.kt`），`CapabilitiesWorkspace.kt` 删除
- 已知遗留：聊天区里的宽 gantt 图会横向溢出（消息区宽度比改造前少约 60dp），属 mermaid 图宽度问题，未在本 ADR 处理

---

## ADR-038 · 模型 / 推理强度是「会话级状态」，走 `config.set`；草稿态靠偏好 + `session.create`

**决策**：
1. composer 底部放两个 pill（模型、推理强度），值来自 **`session.info`**（和 `session.resume` 返回的 `info`），
   **不从 `config.set` 的返回里回读**。
2. 切换走 RPC `config.set`：模型用 `key="model"`、`value="<model> --provider <slug>"`；
   推理强度用 `key="reasoning"`、`scope="session"`。
3. **不加 `--session` 后缀**：后端 `model.persist_switch_by_default` 默认 false，
   普通选择本来就只影响当前会话；加后缀会覆盖用户显式打开的"记住选择"。
4. 草稿态（还没有 live session）的选择落 `config/state.json`
   （`model.draftProvider` / `draftModel` / `draftReasoning`），
   新建会话时由 `session.create` 的 `model` / `provider` / `reasoning_effort` 带过去。
5. 目录懒加载：只有第一次点开菜单才发 `model.options`（那次会探 provider，冷启动可到十几秒）。

**理由**：
- 官方桌面端就是这么做的：`config.set` 带 `session_id`，pill 读 SessionView 的会话状态；
  文档明确 `POST /api/model/set` 只对新会话生效、运行中的会话要用 `/model` 走另一条路。
- `config.set model` 在没有 live session 时返回 **4001**（"requires a live session"），
  所以草稿态不能直接调它 —— 用 `session.create` 的 per-session override 是唯一干净路径
  （`methods_session.py::_create_overrides`，注释里写明"never a global config write"）。
- `deferred` 只表示"排到下一轮开始"，此时回读会拿到旧模型并把它画回 pill 上，
  所以刷新一律等 `session.info`。

**替代方案**：① 用 REST `POST /api/model/set`（写 profile 默认，与"只影响当前会话"不符）；
② 加 `--session` 强制作用域（覆盖用户配置）；③ 切换后立刻回读（`deferred` 时会把旧值画回去）。

**后果**：
- 需要 `session.resume` 拿 runtime id 才能切（首次切换会触发一次 resume，几百毫秒到几秒）
- 打开会话时先用 REST `/api/model/info` 填 profile 默认值，`session.info` 到了再覆盖为会话级真值
- `ChatState` 增加 `model` / `provider` / `reasoningEffort` / `modelCatalog` / `modelConfirm` / `modelError`；
  `publish()` 改为**保留**同会话的模型字段（否则重载历史会把 pill 清空）
- **底部状态条不再显示模型**：composer 的模型 pill 常驻可见，状态条只留后端版本（避免两处重复）
- 模型菜单按 provider 分组且**可折叠**：默认只展开当前模型所在组，折叠态在组名旁打一个小圆点 + 模型数

---

## ADR-039 · 托盘右键菜单不用 CMP `Tray`，改自建 `TrayIcon` + Swing `JPopupMenu`

**决策**：
1. 托盘图标、tooltip、左/双击行为仍走 AWT `SystemTray` / `TrayIcon`，
   但**右键菜单**不再用 `androidx.compose.ui.window.Tray` 的 `menu { Item(...) }`，
   改为 `core/app/DesktopTray.kt`：`TrayIcon.addMouseListener` 收右键 → 弹 Swing `JPopupMenu`。
2. 菜单项只支持「普通条目 + 分隔线 + 禁用态」，够托盘用；不引第三方菜单库。
3. `main()` 最早期 `UIManager.setLookAndFeel(system)`：只影响这个 Swing 菜单与 `JOptionPane`，
   Compose 自绘内容不受 LAF 影响。

**理由**：
- CMP 的 `Tray` 菜单底层是 AWT `PopupMenu`（原生 Win32 菜单）。菜单文字交给 Win32 之前会按
  **默认字符集**（`file.encoding`，本项目为 UTF-8）转成 ANSI，中文因此丢字形、全部变成方框；
  `MenuItem.setFont` 对原生菜单无效，换字体也绕不过去。
- 实测（JBR 25 / Temurin 25、完整 JDK 与 jlink 裁剪运行时结果一致，与打包裁剪无关）：
  - `-Dfile.encoding=UTF-8`（默认）→ 中文全是方框；
  - `-Dfile.encoding=GBK` → 中文恢复正常；
  - `-Dsun.jnu.encoding=GBK`（`file.encoding` 仍 UTF-8）→ **无效**。
- 上游问题：`CMP-1936`「Using Chinese characters in tray will generate garbled code」仍 Open；
  `CMP-4486`「Can not display Chinese with a Tray in Windows.」2024-09 以 `wait for reply` 关闭，
  未修复。CMP 1.12.2 仍可复现。
- 用 `-Dfile.encoding=GBK` 换中文菜单，等于把整个应用的默认编码改回 ANSI，会波及日志、
  文件读写与后端 JSON（`Hermes.cfg` 已显式 `-Dfile.encoding=UTF-8`），不可取。
- Swing `JPopupMenu` 用 Java2D 渲染，与默认字符集无关，字体走 LAF（Windows LAF 直接是
  「Microsoft YaHei UI」），中文正常；边缘翻转、失焦关闭、Esc 关闭、键盘导航都是 Swing 自带，
  不需要自己写弹窗生命周期。

**替代方案**：① 保留 CMP `Tray`、菜单文案改英文（不可接受）；
② 保留 CMP `Tray` + `-Dfile.encoding=GBK`（实测能修中文，但把全局默认编码改回 ANSI，波及日志/文件/后端 JSON）；
③ Compose 自绘无边框 `Window` 当菜单（`ComposeWindow` 会在任务栏留一个按钮，且要自己写
定位/失焦关闭/Esc）；④ JNA 调 Win32 `TrackPopupMenu`（原生观感最好，但要新增 JNA 依赖 +
拿 HWND + `TPM_RETURNCMD` 细节）。

**后果**：
- 托盘菜单视觉是 Windows LAF 的 Swing 菜单（浅色，不跟系统深色模式），与 M3 深色界面不同色系
- `--silent` / 关窗到托盘的行为不变：菜单回调仍改 `windowVisible`、退出仍走 `quit()`
- 托盘区满 / 策略禁用导致 `SystemTray.add` 失败时只打 stderr，不阻塞启动
---

## ADR-040 · 终端用 JediTerm（否决 BossTerm / Termora / Mosaic）

> **2026-10-31 已被 [ADR-054](#adr-054--终端改用-compose-原生渲染自维护-bossterm-裁剪版删除-jediterm--swingpanel) 取代**：
> JediTerm 的渲染层只能落在 Swing 里，SwingPanel 的互操作成本兑现成了阻塞问题（Compose 弹层被
> heavyweight 遮挡 / IME 组合态失真）。终端的语义结论（ADR-041 全局多标签、`terminal.read` 字段）
> 不变，只换了渲染实现。

**决策**：终端 = **JediTerm `org.jetbrains.jediterm:jediterm-core/ui:3.76`**（JetBrains 官方 maven），
PTY = Pty4J 0.14.0，自写约 50 行 `PtyProcessTtyConnector` 把 `PtyProcess`（本身就是 `Process`）接上去。

**理由**（均为 2026-10-30 实测）：
- JediTerm 有**正式发布**（`packages.jetbrains.team`，匿名 200，本机直连），体积 282 KB + 116 KB，依赖仅 slf4j + annotations；
- **双许可 LGPLv3 / Apache-2.0，可自选 Apache**；
- `getTerminalTextBuffer()` 是公开 API → `terminal.read` 与重启回放直接可做，不需要反射。

**否决记录**：
- **BossTerm**（Compose 原生）：真优势是省掉 Swing 互操作，但它是"整应用打包"
  （desktop jar 9.7 MB + material-icons-extended 36 MB + icu4j 14.5 MB + skiko-linux ~30 MB），
  `EmbeddableTerminalState.session` 是 internal（读 buffer 要反射），设置写 `~/.bossterm` 需 `-Dbossterm.settings.dir` 重定向；
- **Termora**：Kotlin/JVM 的 Swing 应用 + 自研 VT 引擎，**AGPL-3.0 双许可**且未发布任何 Maven 坐标 → 只能当自研成本参照（其 `terminal` 包 50+ 文件）；
- **Mosaic**：Compose → ANSI 输出（TUI），方向相反，`mosaic-terminal` 无屏幕模型。

**后果**：接受 SwingPanel 互操作成本（焦点 / IME / DPI / 剪贴板跨边界），这是唯一硬指标，
在 T0 spike 里逐项验；失败时降级回 BossTerm。

---

## ADR-041 · 终端是全局多标签，进程不随重启复活

**决策**：
- 终端**不属于会话**：标签只在创建时快照一次 cwd；切会话/切 profile 不重建 shell；
- 所有终端标签的 **PTY 与文本缓冲区常驻**（`BossTermSessions`），切标签只换渲染的活动 tab —— 不销毁、不重建；
- 重启只恢复**标签列表 + 工作目录 + 文本回放**，**不复活进程**（与官方一致）；
- `terminal.read` 应答字段与官方一致：`{total_lines, start, end, viewport_rows, cursor_row, text}`，
  行号为绝对行号（0 = 最老的滚动历史行），尾部空行裁掉，同一 id 重复请求幂等。

**理由**：官方语义就是"终端是我操作的窗口，不是会话的属性"；用户切会话时终端不应消失。

**后果**：终端状态放在 `AppContainer.terminalViewModel`（跨模式保留）；能力/设置模式下隐藏但不杀进程；
`rail` 与面板高度等 UI 状态落 `config/state.json`。

---

## ADR-042 · 拆 `modules/terminal` 与 `modules/ui-markdown`

**决策**：`composeApp` 仍用包分层；只把满足以下判据的子系统拆到 `modules/`：
① 引入主模块其它代码完全不用到的重依赖；② 实现只落在单一平台；③ 有独立测试面（或明确为将来替换预留边界）。

- `modules/terminal`：`kotlin("jvm")` + Compose；终端依赖与渲染全锁在模块内，宿主只暴露读屏接口；
- `modules/ui-markdown`：KMP `jvm("desktop")`；暴露 `MarkdownRenderer` / `CodeHighlighter` / `DiagramRenderer`
  三个接口 + 当前 mikepenz 实现，**为 v0.3 自研渲染器预留边界**（届时只改模块内部，调用方零改动）。

**理由**：按 `feature/*` 再拆没有收益（1.8 万行规模，包边界足够）；
终端与渲染拆分的价值在"依赖隔离 + 独立测试 + 未来替换点"，不在编译速度。

**后果**：依赖方向 `composeApp → modules` 且禁止反向；`syncReleaseTest` 按 `desktopRuntimeClasspath`
收 jar，新模块自动进 `lib/`（需在 T6 实测一次）。

> **2026-10-31 修订（ADR-052）**：`modules/ui-markdown` 的「替换点」已经用掉 ——
> 实现迁到 `modules/markdown-one`（submodule），本模块只剩 `MarkdownText` 门面与文案注入；
> `CodeHighlighter` / `DiagramRenderer` 两个接口随实现一起删除。
>
> **2026-10-31 再修订（ADR-053）**：`modules/ui-markdown` **整个模块已删除** ——
> 门面只剩死抽象（接口只有一个实现），接线移入 `composeApp/.../markdown/`，
> `PaperColorScheme` 一并迁入，`composeApp` 直接依赖 `:modules:markdown-one`。
>
> **2026-10-31 三修（ADR-054）**：`modules/terminal` 的渲染层换成 Compose 原生的 BossTerm 裁剪版，
> 新增 submodule `modules/terminal-bossterm`（composite build）；模块内不再有 JediTerm 与 Swing。

---

## ADR-043 · 设置中心的数据归属：后端 config/.env，桌面只存 UI 状态

**决策**：官方设置中心几乎全是 **REST**（不是 WS RPC，唯一例外是审批模式的会话级 `config.get/set`）：
模型 `/api/model/*` + `PUT /api/config?profile=`、安全 `PUT /api/config`（deep-merge，只发 diff）、
提供方 `/api/env`、`/api/providers/custom-endpoints`、配置档案 `/api/profiles/*`（含 `/soul`、`/import`、`/export`）。
落盘一律在**后端**（`~/.hermes/config.yaml` / `.env` / `auth.json` / `profiles/<id>/`）。

**桌面端只存**：设置页「应用于」的 profile、各页草稿与展开/折叠态、终端字体与面板高度
（写 `config/state.json`）。**不得**把后端配置镜像到本地，避免两份真相。

**后果**：`RestClient` 要支持 `?profile=`、PATCH/DELETE 与错误体直译；
`/api/profiles/import` 收的是**文件路径**（不是字节流），远端模式下应禁用该操作并说明原因。

---

## ADR-044 · rail 三态 + 点 profile 直达会话

**决策**：rail 底部改成**三个平级入口**（会话 / 能力 / 设置），各自有选中态；
`ShellViewModel` 新增 `setMode(mode)`；**点 profile 时自动切回 `SESSIONS`**。

**理由**：现状是能力入口 `selected` 写死 false、`toggleMode()` 只有两态、`selectProfile()` 只改档不改模式，
导致"在设置页点 profile 看起来没反应"，用户必须先回会话页。

**后果**：设置页与能力页共用同一个 `NavRow`（圆角 10dp）与可折叠分组标题；
窄窗都走 `AdaptiveListDetail` 退化。

> **部分被取代**：rail 仍是"每个入口平级、各自有选中态"，但「能力」不再兼作「回会话」的模式开关，
> 见 ADR-048；设置页左栏改为两级分组，见 ADR-046 / ADR-047。

---

## ADR-045 · payload 附带官方桌面端（debug-only，v1.0.0 移除）

**决策**：自 v0.2.0 起，`hermes-bundle` 的 payload 里附一份**官方 Electron 桌面端**，
路径 `payload/official-desktop/`，用于"同一个后端、两套 UI"的对照调试；**v1.0.0 移除**。

**取法**：上游 release 没有桌面端资产（`v0.22.5` 只有源码 zip），官方 Windows 分发又是 **MSIX**（需签名 + Win11 22H2 工具链），
所以改为在 bundle CI 里从源码构建 **`--dir` 未打包目录**（免安装、免签名、可直接跑）：
`npm ci`（monorepo 根）→ `npm run build` → `electron-builder --dir`（即 `npm run pack`，不含 payload 步骤 = remote/light 形态）。
实测本机产物 `release/win-unpacked/` = 386.9 MB / 1,186 文件。

**开关与兜底**：workflow 级 `INCLUDE_OFFICIAL_DESKTOP`（默认 true，dispatch 可关）；该步骤 **fail-open**——
构建失败只警告并跳过，payload 照常发。压缩后预计 +130~150 MB，总包 ~1.6 GB，仍在 1900 MiB 单卷内。

**客户端**：设置中心 → 选项 → 「调试」（仅当检测到 `official-desktop/Hermes.exe` 时出现），
一键启动；`config.json` 的 `app.debug.official_desktop` 可覆盖路径。

> **落地补充（2026-10-30）**：CI 用 `install.ps1 -IncludeDesktop` 顺带构建（它内部就是
> `npm ci` + `electron-builder --dir`），产物 `apps/desktop/release/win-unpacked` 复制到
> `payload/official-desktop/`；该步骤 fail-open。客户端实现是 `OfficialDesktopLauncherImpl`
> （desktopMain）—— 查找顺序 config 覆盖 → payload 自动探测；检测不到时「调试」分组**不出现**。
> 配置键是 `app.debug.official_desktop`（仓库其它键都是 snake_case，ADR 初稿写的 camelCase 是笔误）。

> **首次产物验证（2026-10-30，run 41039530408）**：`payload/official-desktop/` = 426.9 MB / 1,187 个文件；
> 分卷 `hermes-payload-v0.22.5-win32-x64.7z.001` 单卷 1,609 MB（对照包压缩后约 +150 MB，仍在 1900 MiB 内），
> SHA256 与 `SHA256SUMS.txt` 一致，解压后目录结构与文件数吻合。
>
> 并排起实例有三个实测硬点（启动器已全部处理，见 `OfficialDesktopLauncher.desktop.kt`）：
> 1. **单实例锁按 userData 判重**：官方客户端调 `app.requestSingleInstanceLock()`，与用户本机官方客户端
>    共用 userData 时，对照包打印 `another Hermes Desktop instance holds the single-instance lock; exiting`
>    后 **exit 0**（点了"启动"像是没反应，实际把生产窗口带到前台）。必须设 `HERMES_DESKTOP_USER_DATA_DIR`
>    （官方 app 自己认的覆盖变量，命令行 `--user-data-dir` 会被 `setPath("userData")` 覆盖掉，无效）。
> 2. **`HERMES_HOME` 也必须隔离**：官方客户端的 `resolveDesktopHermesHome()` 先看进程 env，共用时会拉起
>    生产 home 的后端（`<生产 home>/bin/hermes.exe`），污染真实数据。对照实例落在 `<home>/data/official-desktop/`。
> 3. **输出必须重定向到文件**：`ProcessBuilder` 默认管道没人读，官方客户端日志写满 64 KiB 后主进程卡住
>    （窗口出现但 `Responding=False`）。改为 append 到 `data/official-desktop/official-desktop.log`。
>
> 对照包落在独立 home 后走**首次设置向导**，选 **Connect to existing Hermes** 填同一个 dashboard 地址与
> session token，即可与自研客户端并排连同一个后端。

**v1.0.0 移除清单（4 处）**：workflow 步骤与开关、payload 目录、客户端「调试」分组、`app.debug.official_desktop` 字段。

---

## ADR-046 · 设置页左栏一律「一级分组 → 二级页面」

**决策**：设置页左栏只允许两级结构：一级分组用 `NavSectionHeader`（可折叠，状态落 `state.json` 的
`ui.settingsCollapsed`，逗号分隔多组），组内是二级页面 `NavRow`。**任何入口都必须挂在某个分组下**，
不允许裸在根上的行。当前分组：「选项」（后端连接 / 应用 / 日志 / 窗口 / 界面与网络）、「终端」（外观）；
后续「配置档案」「模型」「安全」「提供方」同样是一级分组。

**理由**：此前「终端」直接裸在「选项」组下面，与组内五项不属于同一层级；用户无法判断哪些是分组、
哪些是页面。二级化之后，v0.2.0 要加的四个新域（profiles / 模型 / 安全 / 提供方）有明确的落位规则。

**后果**：`SettingsState` 的 `selectedGroup: Int` + `selectedTerminal: Boolean` 换成
`selected: SettingsPage`（`Catalog(index)` / `TerminalAppearance`）；折叠状态从布尔 `optionsExpanded`
换成 `collapsedSections: Set<String>`。设置页同时改用 `AdaptiveListDetail`（见 ADR-047）。

---

## ADR-047 · 设置页与另两页共用 `AdaptiveListDetail`，中栏底色由它统一

**决策**：设置页不再自绘 `Row`（左 210dp `LazyColumn` + 右表单），改成
`AdaptiveListDetail(list = SettingsNavPane, detail = SettingsDetailPane)`。

**理由**：设置页中栏曾显式 `background(surfaceContainerLow)`，而会话列表、能力页中栏是外层 `Row` 的
`surface` —— 同样位置的"选中栏"底色不同。用户一眼看出不一致。换组件后底色、**选中块圆角**、
**窄窗单栏退化**三处自动统一，且不必再各写一遍。

**后果**：设置页在 < 840dp 时退化单栏，顶部出现返回条（与其他两页一致）；保存条留在 detail 窗格底部。

---

## ADR-048 · rail 的「能力」是导航目的地，不再兼作「回会话」的模式开关

**决策**：`ProfileRail` 的「能力」项标签与图标**固定**，`onClick` = `openCapabilities()`
（`ShellViewModel.toggleMode()` 删除）。回会话只走「点任一 profile 头像」。

**理由**：此前该项在能力页会变成「会话」按钮 + `AppIcons.Sessions` 图标。同一位置两种语义（导航 / 模式开关）
让用户在设置页找不到"回会话"，也让"能力"这个入口看起来会消失。rail 三项各有固定身份更直观。

**后果**：`mode_sessions` / `rail_switch_mode` 两个字符串资源不再使用。

---

## ADR-049 · 技能页「发现」只用 `/api/skills/hub/official`

**决策**：技能与工具页加第三个分段「发现」，数据源固定为 `GET /api/skills/hub/official`
（后端本地扫描 payload 的 `optional-skills`，本机实测 152 项 / 0.6s / 54 KB），
支持分类筛选、搜索、展开详情（repo / identifier / tags）与**安装**（`POST /api/skills/hub/install`）。

**否决的三条路**：
- 官方桌面端的「发现 276」是前端直连 `https://nousresearch.github.io/hermes-agent/docs/api/skills.json`：
  该文件 **64 MB**，本机直连 10s 只下 1 MB（走代理 4s 才下完），Ktor 客户端不走系统代理 → 不可用。
- `GET /api/skills/hub/sources`、`/hub/search`、`/hub/preview`：后端要联网探测各 registry，
  本机实测 40s+ 超时（`hermes-index` 需 6h 缓存的远端索引）。
- 自己拼 multi-registry 搜索：属于后端职责，客户端不做。

**后果**：能看、能筛、能装官方可选技能；"联网搜索第三方源"留给 v0.3 —— 那时若后端把
`hub/sources` 做成有本地缓存 + 快速返回，再开。`hub/install` 是**异步**起 CLI 的，回执只代表"已提交"，
所以安装后延 1.5s 回读目录与已安装列表，失败不报错（最终以列表为准）。

---

## ADR-050 · 本地后端固定加 `--isolated`

**决策**：`LocalBackendPlanner` 起的 `dashboard` 固定带 `--isolated`。

**理由**：实测踩坑 —— 机器上已有官方桌面端的 dashboard（占着 9119）时，不带 `--isolated` 的新进程会
**attach 到那台 host 后端并 `exit 0`**（`Hermes dashboard already running on this host: PID 18148, port 9119.`）。
我们的就绪判定只看 stdout 哨兵/ready 文件，于是只能看到"进程立刻退出"，
本地后端永远起不来（日志：`local backend: backend 进程已退出`）。

**代价**：`--isolated` 同时跳过 `_route_named_profile_dashboard` 的机器级重定向 —— 对我们是想要的
（每个实例自己一个后端、自己一个端口、自己的 token）。

**验证口径**：本机同时跑官方 Electron 桌面端 + 开发版 `./gradlew :composeApp:run`，
`desktop.log` 必须出现 `local backend ready on 127.0.0.1:<port>`。


---

## ADR-051 · 设置中心「模型 / 提供方」新增页一律按后端形状读写，不做归一化

**决策**：备用模型、MoA、自定义端点三页都**按后端原样形状**读写，客户端不改造数据：

| 页 | 端点 | 保持的形状 |
|---|---|---|
| 备用模型 | `GET/PUT /api/config`（`fallback_providers`） | 磁盘上是单个 dict 就写回 dict，是 list 才写 list；空链写 `[]` |
| MoA | `GET/PUT /api/model/moa` | 命名 `presets` map **整份**提交（后端以提交的 map 为权威，未列出的预设会被删） |
| 自定义端点 | `/api/providers/custom-endpoints` 系列 | 只按 `id` 操作；`api_key` 传 `null`（保留）而不是 `""`（清空） |

**理由（都是实测）**：
- `fallback_providers` 后端 `_fallback_entries` 同时认 dict 与 list。若统一成 list 写回，
  老配置会变形；用户下次用官方 CLI 读到的形状就变了。
- MoA 的读路径 `normalize_moa_config` 会把**半填预设静默替换成默认值**，写路径却用
  `validate_moa_payload` 返回 **422**（上游 #64156：桌面端自动保存半个槽位会清掉整个预设）。
  所以客户端必须**先自检**（每个预设至少一个完整参考模型 + 完整聚合模型）再提交，
  不能指望后端修复。
- 自定义端点编辑时用户通常不重填 key。后端把**空串**当"清空密钥"，`null` 当"保留"，
  所以 DTO 的 `apiKey` 必须是 `String?`，空输入转 `null`。

**MoA 的扁平镜像**：`GET /api/model/moa` 除 `presets` 外还回一份默认预设的扁平副本
（`reference_models` / `aggregator` / …）。桌面端**只用 `presets`**，另一份只读不写，
避免两份数据互相覆盖。

**探测回填**：`POST /api/providers/custom-endpoints/validate` 会返回 `resolved_base_url`
（真正供出 `/models` 的那个 base）。后端保存时也这么改（运行时在保存的 URL 后直接拼
`/chat/completions`），所以表单要跟着回填，否则用户看到的是会被改写的地址。

---

## ADR-052 · Markdown 渲染改用 `modules/markdown-one`（git submodule），本仓库只留门面

**决策**：Markdown 的**解析、块路由、代码高亮、图表、大块视口**全部由
[`Shinglem/markdown-one`](https://github.com/Shinglem/markdown-one)（Apache-2.0）提供，
以 **git submodule** 挂在 `modules/markdown-one`；`modules/ui-markdown` 退化为**门面**：

- 保留 `MarkdownText()` 与 `MarkdownRenderer` → 调 markdown-one 的 `MarkdownText`；
- 删除本仓库自建的实现：`CodeHighlight.kt`、`CodeHighlighter.kt`、`DiagramRenderer.kt`、
  `MermaidDiagram.kt`、`MarkdownText.kt` 里的 mikepenz 实现，以及对应测试；
- `libs.versions.toml` 移除 mikepenz / Highlights / `build.raft.mermaid` 的坐标，
  `settings.gradle.kts` 移除只为它服务的 botiverse 仓库。

**背景**：ADR-036 预留的「可插拔块渲染层」与 ADR-042 的「替换点留在模块内」已经具备条件，
但本仓库自建的那套（mikepenz 的 `MarkdownComponents` 覆盖 + 两个适配文件）已经在做
别人也在做的事：视口限高滚动缩放、流式栅栏补齐、图表降级策略、可替换的块渲染器 ——
这些在 markdown-one 里已经是产品化的公共 API（`BlockAdapter` + `AdapterRegistry` + `BlockViewportConfig`），
本仓库再维护一份就是双份分叉。

**为什么是 submodule 而不是 maven 依赖**：markdown-one 尚未发布到 Maven Central；
submodule 让「改渲染能力」和「改客户端」在同一个构建里编译，改动立刻可见，
又不必先搭一条发布流水线。发布后再切换成坐标依赖（只改 `ui-markdown` 的依赖行）。

**接线细节（都踩过）**：
- markdown-one 的构建脚本按 **version catalog alias** 取插件、依赖写**字面量**，
  因为它挂进来后 `libs` 指向本仓库的 catalog；本仓库的三个 alias
  （`kotlin.multiplatform` / `compose.compiler` / `compose`）必须保持存在；
- target 名必须对齐：两边都用 `jvm("desktop")`，否则 KMP 变体不匹配；
- `MarkdownConfig` 是 markdown-one 重建适配器链的 key，**必须 `remember`**，
  不能在每次组合里 new；
- 主题走 markdown-one 的默认（= Material 3 默认），不套它自带的纸面主题，与 App 的 `HermesTheme` 保持一致。

**后果**：
- 克隆后必须 `git submodule update --init --recursive`，否则编译失败（已写进 AGENTS.md §5）；
- 改渲染行为要去 markdown-one 仓库提交推送，再回主仓库更新指针 —— 主仓库提交不会带走子模块的工作区改动；
- 硬约束 8 的「引擎边界只允许出现在 `feature/chat/MermaidDiagram.kt`」作废（该文件已删除），
  改为「渲染实现只在 `modules/markdown-one`」，见 ADR-035 的修订说明；
- CI 需要加一步 `submodules: recursive`（否则 checkout 后缺源码）。

**验证**：`MarkdownRenderTest`（标题/列表/表格/代码块 + 流式未闭合栅栏）+ 原 `ChatPaneTest`
的代码块标签断言全绿；另用一次性截图用例渲染综合样例比对，与替换前一致。

> **2026-10-31 补充**：能力补齐分两批在 markdown-one 侧落地（本仓库只更新了指针）——
> `0af0b53` 图片与主题（`-coil3` + `Coil3ImageTransformerImpl`、`markdownMaterialTheme()`）、
> `858a357` 公式与 HTML、`d4deb47` 容器块自递归修复、`ed10a9c` 散装行内公式与 HTML 表格。
> 几个实现口径，改动前先读：
> - **行内公式**走 GFM 原生的 `INLINE_MATH` 节点（不是文本切分），tex 从 AST 收集，
>   收集与渲染必须是同一份字符串，否则 `InlineTextContent` 的 key 对不上会退化成源码；
> - **行内公式的识别口径与上游一致：`$` 前必须是空白或行首**。这是 0.7.17 的 `canOpenMath`
>   规则（#196 为修 #179 的 `**$0.85 EPS**` 货币误判引入，#225 只放开了 `$$` 块级的空白，
>   行内规则明确保留）。**我们不做补偿**：`时，$\bar{x}$`、`a$x$b` 这类紧贴标点/汉字的写法
>   不渲染公式，按源码显示。历史上 `parse/InlineMathRepair` 曾把它们补成 `INLINE_MATH`，
>   已随本条删除。
> - **`GFMTokenTypes.DOLLAR` 必须原样输出**（构建器里显式 `append`）：未成公式的 `$`
>   不输出就会被静默丢弃（`时，$x$` → `时，x`）。
> - **构建器不要在 `MarkdownTokenTypes.TEXT` 上兜底扫描 `$…$`**：`CODE_SPAN` 的子节点
>   同样是 `TEXT`，兜底会把行内代码 `` `$x$` `` 渲染成公式而不是等宽文本。
> - **版本口径**：0.7.1–0.7.3 **不要求**前导空白（`时，$x$`、`a$x$b` 都是 `INLINE_MATH`），
>   限制是 0.7.17 才加的；`build.gradle.kts` 原先声明 0.7.3 但被传递升级到
>   0.7.17，现已改成 0.7.17，声明与实效一致。判断行为前先看实效版本，别按声明版本推断。
> - **公式排版引擎已换成 `io.github.huarangmeng:latex-renderer:1.6.3`**（MIT，纯 Compose
>   矢量绘制 + KaTeX 字体），JLaTeXMath 与自建位图缓存（`MathImage` / `MathRenderCache`）已删除。
>   两个入口：行内走 `LatexMeasurerState.inlineContent()`（同步测量），块级走 `Latex()`
>   （异步解析，首帧可能空白）；颜色/字号统一由 `rememberLatexConfig(color, fontSize)` 提供，
>   参数显式传入是因为 `mathInlineContents` 在铺 `LocalMdColors` 之前就要算。
>   换引擎的直接收益：`\Omega` 正常（JLaTeXMath 在 JDK 25+ 渲染空白，opencollab/jlatexmath#111）、
>   高分屏矢量清晰、`pmatrix` / `align` / `mhchem` 等语法覆盖更全。
> - **行内文本由自研构建器生成**（`text/MdInline.kt` 的 `buildMdInline`），一次走完 AST：
>   不再依赖任何第三方 annotator。两条必须遵守的口径：
>   ① `CODE_SPAN` 的子节点也是 `TEXT`，**不要**在里面做 `$…$` 扫描（行内代码里的公式会被误判）；
>   ② `GFMTokenTypes.DOLLAR` 必须原样输出（未成公式的 `$` 若不输出就会被静默丢掉，
>   `时，$x$` 会变成 `时，x`）。
> - **`HTML_INLINE` 在解析前改写**成等价 Markdown 标记（成对标签才改，避免吃掉 `a<b>c`）；
>   容器块（`<div align=…>`）保持 `HTML_BLOCK`，由 `HtmlContainerAdapter` 剥壳后
>   用 `MarkdownText(textAlign = …, fillWidth = false)` 居中；`<table>` 在解析前整块转成
>   Markdown 表格（跨行处理，围栏代码块内跳过）；
> - `<details>` 由 `mergeDetails` 合并成一个 `Custom("details")` 块，body 递归 `MarkdownText`；
> - **`HtmlContainerAdapter` 递归渲染时必须禁用自身**（`disabledAdapterIds`），
>   否则裸 HTML 块会自递归 —— 症状是组合期 `StackOverflowError`，栈里是层层嵌套的 `BlockViewport` 测量；
>   `DetailsAdapter` 的 body 每次都比 raw 短，天然收敛，不需要这道保险；
> - **嵌套块走同一条适配器链**：`text/MdFlow` 只按「行内片段 / 块级节点」分块，块级节点交给
>   `MdRoutedBlock` → `parse/MdBlockFactory` → `AdapterRegistry.select()`，与顶层共用一份
>   AST → `MdBlock` 映射和同一个注册表。所以引用里的 `$$…$$`、列表里的 mermaid、嵌套表格
>   都复用已有适配器。只有标题/段落/列表/引用留在本地处理（它们要接父级行内样式）。
>   注意 `BLOCK_QUOTE > PARAGRAPH > BLOCK_MATH` 这层嵌套：段落分支必须分块，否则公式被当行内文本；
>   `MdBlockFactory.mathTex` 还要剥掉行首的 `>`（`BLOCK_MATH` 的 offset 会把块引用标记圈进来）。

---

---

## ADR-053 · 删除 `modules/ui-markdown` 门面模块，Markdown 主题独立设置

**决策**：删掉 `modules/ui-markdown`，`composeApp` 直接依赖 `:modules:markdown-one`；
Markdown 配色从「跟随界面配色风格」改为**独立设置**（`options.ui.markdown_style`）。

**背景**：ADR-042 拆出的门面模块只剩 180 行 4 个文件，其中两件是死抽象 ——
`MarkdownRenderer` 接口**只有一个实现**且从未被替换（`LocalMarkdownRenderer` 只有一处读取），
`MarkdownText` 只做参数转接。代价是真实的：加一个 `selectable` 参数要同时改接口、实现、门面三处。

**为什么不是「全删了事」**：`PaperColorScheme` 必须能看见 markdown-one 的 `PaperPalette`
才能生成界面的 `ColorScheme`。它随模块删除一起迁进 `composeApp`
（`dev.shinglem.hermes.markdown.PaperColorScheme`），因此 `composeApp` 现在直接依赖 submodule。

**主题归属**：
- 主题**定义**留在 markdown-one（`outsidePaperTheme()` / `markdownMaterialTheme()`），宿主不持有色值；
  ⚠️ 界面侧已于 **ADR-055** 改为宿主自有色板（`ui/theme/AppPalette.kt`），纸面 Markdown 的颜色也改由宿主注入。
- 主题**选择**是宿主设置：`options.ui.markdown_style` = `follow` / `material` / `paper`，
  `follow` 才跟随界面配色风格（`options.ui.style`）。两者解耦后，
  「界面用 M3、消息用纸面」这种组合可以直接选。

**接缝只剩一处**：`composeApp/.../markdown/HermesMarkdown.kt` 的 `MarkdownText(...)` ——
它把设置翻译成 `MarkdownConfig` 并透传 `streaming` / `selectable` / `onLinkClick`。
不叫 `MarkdownText.kt` 是有原因的：与已删除模块的同名文件同路径会让增量编译缓存错乱（见下）。

**踩坑记录**：迁移时 `MarkdownText.kt` 一直报「找不到自己刚写的 `MarkdownText`」，
真实原因是 KDoc 里写了 `feature/*` —— **Kotlin 的块注释可以嵌套**，`/*` 打开了第二层注释，
把后面的代码整段吞掉；而编译器只报消费方那条「Unresolved reference」，把真实原因藏在后面。
排查手法：只报第一条错误时，把可疑文件逐步替换成最小桩，直到错误变成预期的那条。

**后果**：
- `settings.gradle.kts` 少一个 include，构建少一个项目；
- 「换渲染引擎只改模块内部」这句话作废 —— 现在要改 `HermesMarkdown.kt`（一处，约 60 行）；
- 硬约束 6 的口径同步为「选区由 markdown-one 在 `MarkdownText` 内包一层」，
  宿主不再自己包 `SelectionContainer`（嵌套会让选择链断开）。

---

## ADR-054 · 终端改用 Compose 原生渲染（自维护 BossTerm 裁剪版），删除 JediTerm + SwingPanel

**决策**：终端渲染层从 `JediTerm + SwingPanel` 换成**自维护的 BossTerm 裁剪版**：

- fork `kshivang/BossTerm` → `Shinglem/BossTerm`（分支 `hermes`），只保留
  `bossterm-core-mpp` + `compose-ui` 的**终端核心子集**，作为 git submodule
  `modules/terminal-bossterm` 挂在主仓库；
- 主构建用 **composite build**（`includeBuild` + `dependencySubstitution`）引用，不并入模块图；
- 主仓库 `modules/terminal` 只留宿主适配（`TerminalHost.kt`），对外暴露
  `terminalSelectedText` / `terminalTailText` / `terminalRead`；
- 删除 `jediterm-core` / `jediterm-ui` / 直接依赖的 `pty4j`（PTY 由 BossTerm 内部的 pty4j 负责）。

**理由**（均为本机实测）：

- **Swing 互操作是死路**：JediTerm 的 `Terminal` 接口 70 个方法的唯一实现是 Swing 的
  `TerminalPanel`；`TerminalTextBuffer` 虽可离屏，但"Compose 自绘 + JediTerm 模型"要自己重写
  整个渲染层（选区、滚动、超链接、光标形状），成本远高于裁剪一个已有的 Compose 实现。
- **heavyweight 遮挡是真的**：`SwingPanel` 会盖住主窗口内一切 Compose 内容，之前只能靠
  「弹框前先收起终端、关掉再展开」绕（`AppShell` 的 `modalOpen`、`TerminalPanel` 的
  `reopenedAfterRename`）。换 Compose 渲染后两处绕法**均已删除**，弹框时终端保持展开。
- **IME 在 Swing 边界上不可控**：终端里中文输入的组合态要自己接管，Swing 侧拿不到 Compose
  的 `TextFieldValue.composition` 语义。
- **ADR-040 当年否决 BossTerm 的三条理由，在"源码裁剪"后都不成立**：
  ① 整应用打包（9.7 MB jar + material-icons 36 MB + icu4j 14.5 MB + skiko-linux 30 MB）
  —— 裁掉非终端功能后 `compose-ui` 从 **101,163 行 / 370 文件**降到 **23,869 行 / 86 文件**
  （删 284 文件），并移除 Android target、ktor-server、supabase、MCP SDK、zxing、jna 直接依赖；
  ② `EmbeddableTerminalState.session` 是 internal —— 改为在 `TerminalFactory` 上暴露
  `columns()` / `selectedText()` / `tailText()` / `snapshot()` 四个只读接口，宿主不碰 internals；
  ③ 设置只写 `~/.bossterm` —— 加 `installInMemory(settings)` 内存模式（`SettingsManager` 不再
  落盘），并把**所有**外部路径统一收敛到 `BossTermPaths.dir()`（原先 `ThemeManager` /
  `ColorPaletteManager` / `SettingsLoader` / `ShellIntegrationInjector` / `FrameLatencyProbe`
  五处硬编码 `user.home`，会偷偷重建 `~/.bossterm`）。
- **许可证**：BossTerm 是 **LGPLv3 / Apache-2.0 双许可**，fork 取 Apache-2.0。

**裁剪与修复记录**（fork `hermes` 分支）：

| 项 | 内容 |
|---|---|
| 保留 | `rendering/`（Compose Canvas + `TextMeasurer` 渲染）、`ui/ProperTerminal`、`session/`、`actions/`、`settings/`、`ime/` |
| 删除 | 应用外壳、示例、Supabase / KtorServer / MCP / zxing / Android target / 旧测试 |
| 新增 API | `createTerminalTab` / `startTerminalProcess` / `columns()` / `selectedText()` / `tailText()` / `snapshot()` |
| IME 修复 | `TextFieldValue.composition` 区分组合态与提交；`requestFocus` 延后一帧；同一次提交去重；切换输入法快捷键 `Ctrl+Space`（被微软拼音抢占）改 `Ctrl+Alt+I` |

**验证**（本机 GUI 实测，脚本驱动真实窗口）：

- 渲染 / PTY / 键盘输入 / 粘贴 / 300 行 CJK 对齐 / 2000 行滚动性能 / resize 全部通过；
- 终端展开时 `AlertDialog`（重命名会话）与 `DropdownMenu` 均正常**压在终端之上**，终端不再收起；
- `~/.bossterm` 不再被重建（此前 5 处 `user.home` 硬编码会创建空目录），终端配置只在
  `-Dbossterm.settings.dir` 指定的目录（默认 `/` 内存模式）；
- 中文 IME：拼音 → 候选框 → 选词 commit 全链路通过。

**后果**：

- 新增 submodule，克隆后需 `git submodule update --init --recursive`；BossTerm 的改动走 fork 的
  `hermes` 分支，**不要**在主仓库里改 `modules/terminal-bossterm`（改了就丢）。
- `settings.gradle.kts` 少两个仓库（JitPack、`packages.jetbrains.team`）—— JediTerm 走后者的依赖已删。
- 终端设置由宿主 `options.terminal` 注入并走内存模式：**字体 / 字号 / 配色实时生效，回看行数需新开标签**。
  字体名要由宿主用 `loadTerminalFont()` 翻译成 `FontFamily`（`ProperTerminal` 不读 `fontName`）。
- `ProperTerminal` 仍隐式依赖 `SettingsManager.instance`，宿主通过 `installInMemory` 提供；
  将来若要多终端实例用不同设置，需要把它参数化（当前不做）。

---
---

## ADR-055 · 界面配色改为宿主自有色板（AppPalette），paper 加深并新增四套色调

**决策**：界面配色不再借用 markdown-one 的 `PaperPalette`。宿主新增
`composeApp/.../ui/theme/AppPalette.kt`，自己持有 paper / ocean / sage / plum / amber
五套色板（各含亮暗两版）并映射到 M3 语义角色；`options.ui.style` 的取值从 2 个扩到 6 个。
纸面 Markdown 也改用宿主色值（`markdown/PaperMarkdownTheme.kt`），排版仍照 Outside Paper。

**背景**：用户反馈「paper 主题的灰色和黑色字体太淡」。量化后确实不合格：

| 角色 | 旧值 | 对比度 | 新值 | 对比度 |
|---|---|---|---|---|
| 正文 `text` | `#5A524A` | 7.4:1 | `#2A2521` | 14.8:1 |
| 次要 `textMuted` | `#9A9080` | **3.0:1** | `#6A6055` | 6.0:1 |

`textMuted` 3.0:1 连 WCAG AA（4.5:1）都不到，而它正好是分组标题、时间戳、输入框提示、
skill 说明这些「一眼扫过去」的文字。暗色版同理（`#8D8378` → `#B3A797`）。

**为什么不再借 markdown-one 的色板**：
- 那套色值是 Typora Outside 主题的原始取值，服务于长文阅读；桌面客户端里同一套色要
  同时承担列表、表单、状态栏，观感就是「一片灰」。
- 想加深就得改 submodule，而它还有 demo 与其他使用方；改宿主侧更轻。
- 「其他色调主题」根本无从借用 —— 需要的是 5×2 套色板，不是再造一个 markdown 主题。

**代价与约束**：
- 宿主第一次持有界面色值（ADR-053 曾写「宿主不持有色值」），这条被本 ADR 取代；
  Markdown 的**排版**仍在 markdown-one，只有颜色改由宿主注入。
- 对比度门槛写进 `AppPaletteContrastTest`（正文 ≥ 11:1、次要 ≥ 4.5:1、`onAccent`/`accent` ≥ 4.5:1）。
  新增风格或调色必须先让这个测试绿 —— 否则又会「改着改着就淡了」。
- `markdown_style = follow` 时：paper 风格走宿主加深的纸面主题，其余风格直接跟随当前 M3 色板。
  显式选 `paper` 的 Markdown 也用加深版（不再是 markdown-one 的原始色）。

---
