# AGENTS.md — hermes-compose

> 本项目是 **Hermes 桌面客户端的 Kotlin / Compose Multiplatform 重写**。
> 面向所有在此仓库工作的 AI agent（Claude Code / Codex / Copilot / 其他）。
> 规则冲突时，以本文档为准；本文档未覆盖的，见 `.design/` 与 `.ai/skill/`。

---

## 0. 首要规则

- **语言**：对话与**思考过程**一律用中文；代码标识符、命令、路径、配置键名、报错原文保持英文原样。
- **读不到被引用的文件 → 立即停止并报告缺失的文件名**，不要凭记忆或同类经验补全。

## 1. 项目定位

用 Kotlin + Compose Multiplatform 重写 Hermes 桌面客户端（Electron + React，位于
`C:\ai\hermes\app\apps\desktop`），**目标平台 Windows 优先**，后端完全不动。

一句话：**只换前端外壳，不动 agent 内核。**

### 非目标（明确不做，不要"顺手实现"）

| 不做 | 原因 |
|---|---|
| 修改后端（`C:\ai\hermes\app` 下的 Python / gateway / dashboard） | 契约只读，改了会与官方客户端分叉 |
| JS 插件宿主（QuickJS / J2V8 / 内嵌 React） | 前端插件返回 `ReactNode`，保留生态 = 自造 WebView；详见 `.design/草案/07-decisions.md` ADR-003 |
| 文件树 / 内置代码编辑器 / diff 视图 | 跳转外部 VSCode / IDEA 打开，见 ADR-005 |
| 远程屏幕（noVNC / RFB） | 用 ToDesk / AweSun 等外部工具 |
| 机器人聊天窗口 / 群聊窗口 | 本期只做"会话"主窗口；IM 风格聊天留作后续独立窗口 |
| 视觉 1:1 复刻 | 用 Material 3 原生组件与配色，功能对等即可 |
| 多语言 | 只做简体中文 |
| WebView 兜底渲染（HTML 预览） | 不渲染；HTML 块由 ksoup 白名单原生渲染，mermaid/PlantUML/DOT 走 `diagram-render`、数学公式走 `ratex`，见硬约束 8 |

---

## 2. 技术栈（版本以 `gradle/libs.versions.toml` 为准）

| 层 | 选型 |
|---|---|
| 语言 / 运行时 | Kotlin（最新稳定）+ **JDK 27（Temurin，带 jmods）**；基线从 JBR 换到官方 JDK 的原因见 `.design/详细设计/工程与代码/10-Windows触屏输入.md` §14 |
| UI | Compose Multiplatform **1.12.x** + Material 3 |
| 自适应布局 | `adaptive-layout:1.3.0`（`ListDetailPaneScaffold`，含 `extraPane`）+ `adaptive-navigation:1.3.0`（`rememberListDetailPaneScaffoldNavigator`）|
| 网络 | Ktor client（CIO 引擎）+ WebSockets 插件 |
| 序列化 | kotlinx-serialization-json |
| 时间 | kotlinx-datetime |
| 本地偏好 | multiplatform-settings |
| Markdown 渲染 | `:modules:markdown-one`（git submodule，Apache-2.0）—— 解析/块路由/高亮/mermaid/视口全在那边，本仓库只接线 |
| 语法高亮 | markdown-one 内置（Highlights + 少数格式补正则），本仓库不再持有实现 |
| 图片 / SVG | Coil 3（`coil-compose` `coil-svg` `coil-network-ktor`） |
| 终端 | BossTerm（JitPack 锁 tag）+ Pty4J |
| 图标 | `material-icons-extended`（按需）+ 系统等宽字体 |

依赖坐标与逐项对比见 `.design/草案/02-selection.md`；**新增依赖前必须先查注册表与许可证**
（`search.maven.org` 按 group 查、`jitpack.io/api/builds` 查 ref、GitHub license API），
CMP 生态里"README 写了坐标、Maven Central 查不到"是常态。

---

## 3. 目录约定

```
hermes-compose/
├── AGENTS.md              ← 本文件（agent 主入口，规则唯一权威源）
├── README.md              ← 人读的项目说明
├── .ai/
│   ├── README.md          ← 技能清单、来源与同步方式（唯一处）
│   └── skill/<技能名>/    ← Agent Skills 技能包（按需加载，目录名 = name）
├── .github/               ← Copilot 接入层（指针文件 + skill 链接，不存规则内容）
│   └── workflows/         ← CI：生成启动器/运行时、组装桌面端发行版
├── .design/               ← 设计事实源（两层：草案 / 详细设计）
│   ├── README.md          ← 设计文档索引（唯一处）
│   ├── 草案/               ← 做什么、为什么：范围、选型、架构、UI 规格、验收、路线、ADR
│   └── 详细设计/           ← 怎么做：工程与代码、功能域设计、UI 视觉稿
├── .gitattributes         ← 行尾策略（文本一律 LF）
├── gradle/libs.versions.toml
├── composeApp/            ← 应用模块（commonMain / desktopMain）
├── modules/               ← 旁路模块（依赖方向：composeApp → modules，禁止反向）
│   ├── terminal/          ← 终端宿主：BossTerm 裁剪版（Compose 原生渲染，JVM-only）
│   ├── terminal-bossterm/ ← git submodule：BossTerm 的裁剪 fork（Apache-2.0；composite build 接入）
│   └── markdown-one/      ← git submodule：Markdown 渲染库本体（Apache-2.0；composeApp 直接依赖）
└── build.gradle.kts
```

- 源码放 `composeApp/src/`（按 `feature/<域>/`（session, chat, composer, terminal, capabilities, settings）分包）
  或 `modules/<模块>/src/`；**跨模块只用公开门面**，不 import 模块内部实现。
- **`.design/` 是设计事实源**：实现与文档冲突时，先改文档再改代码，或明确记录偏差到 `.design/草案/07-decisions.md`。
- 不在仓库内放构建产物、日志、`.jdk`、本地 SDK。
- **技能单一事实源**：`.ai/skill/<技能名>/SKILL.md`，目录名 = frontmatter `name` = 技能 ID，
  且必须是 `.ai/skill/` 的**一级**子目录（VS Code 只扫一层，分组嵌套会被静默忽略）。清单只在 `.ai/README.md` 登记一次。
- **接入层不存内容**：`.github/` 除 `workflows/`（CI 构建流程）之外，只放指向 `AGENTS.md` 与 `.ai/skill/` 的指针 / 符号链接，
  不复制规则或技能正文。
- **同一内容只写一处**：规则写本文件，方法 / 判据 / 坑写技能，设计与决策写 `.design/`；别处只允许链接指向。

---

## 4. 硬约束（每条都对应一次实测或明确决策）

1. **契约只读**：`ws://` JSON-RPC（gateway）与 `http://` REST `/api/*` 都在 `127.0.0.1:9119`
   的 dashboard 上；`8642` 是 OpenAI 兼容 API，**不是桌面端接口**。见 `.design/草案/08-data-contract.md`。
2. **传输是双通道**：会话/聊天/事件走 WebSocket JSON-RPC；技能/工具/cron/配置/profiles 走 REST。
   不要试图把管理类调用也塞进 RPC。
3. **`prompt.submit` 的 ack 超时给 1800s**：一轮对话完成靠 `event` 通知，不靠 RPC 返回。
   默认 30s 会把长回答误判为超时。
4. **聊天输入框一律 `BasicTextField`**（`BasicTextFieldState` + `AnnotatedString` + `InlineTextContent` 做 chip）。
   不要引入自绘编辑器内核 —— 它要自己接管 IME 组合态，中文输入是硬需求。
5. **外层 `LazyColumn` + 单条消息内部普通 `Column`**。LazyColumn 内嵌 LazyColumn 会在无界高度约束下崩掉。
6. **跨消息文本选择不可用**（LazyColumn 回收 item 导致选择链断裂）。
   实现为：选区由 markdown-one 的渲染层在 `MarkdownText` 内部包**一层** `SelectionContainer`
   （`selectable` 开关控制）+「复制整条 / 引用」按钮。**调用方不要再在外面套一层**，
   嵌套会让选择链断开。不要试图修这个机制。
7. **composer 发送的是 Markdown 源码**：不做 WYSIWYG 转换、不自动转义、不改写用户输入。
8. **CSS/React 生态的能力不做等价复刻**：社交卡片降级（显示源码或链接）。
   HTML 块**按标签语义原生渲染**（`HtmlNativeAdapter` + ksoup，白名单照 GitHub sanitizer），
   布局级 CSS、脚本、iframe 一律忽略 —— 全部是**纯 Kotlin/Compose 实现，不引 WebView**：
   - 图表 —— `diagram-render` 在 Compose 内直接绘制（Mermaid / PlantUML / DOT），
     画不出的图族回落「源码 + 一行提示」；
   - 数学公式 —— `ratex` 原生排版（块级 `$$…$$` + 行内 `$…$`，行内走 `InlineTextContent`）；
   - 原始 HTML —— ksoup 解析成 DOM，再按标签语义拼 Compose 块树（`<script>` 子树整棵丢弃）。
   `HTML_INLINE`（`<br>` / `<b>` / `<code>` / `<a>` / `<img>`）在解析前被改写成等价 Markdown 标记，
   `<div align=…>` 这类容器块剥壳后递归渲染 —— 都在 markdown-one 里，本仓库不改写用户的 Markdown。
   **渲染实现全部在 `modules/markdown-one`（git submodule）**：
   不要在 `composeApp` 里新增 markdown / 高亮 / 图表 / 视口的实现，需要换行为就改那边（或加 `BlockAdapter`）。
   `composeApp` 里只有 `dev.shinglem.hermes.markdown.MarkdownText` 这一层薄接线（主题选择 + 文案注入），
   不再有独立的门面模块。见 ADR-053。
   （历史：引擎边界曾限定在 `feature/chat/MermaidDiagram.kt`，见 ADR-035 —— 该文件已删除。）
9. **不要用 `NavigationRail`** 做左侧头像条：它是"导航目的地"语义，布局约束不合适；自绘 Surface + Column。
10. **列表分组标题不要用 `stickyHeader` + `VerticalScrollbar`**（CMP #3016 已知闪烁）；用普通标题行。
11. **CMP-8735 已接受**：`ListDetailPaneScaffold` 在 desktop 上不随窗口 resize 自适应。
    如需重排，显式把 `PaneScaffoldDirective` 绑到窗口宽度档位 + `key(档位) { ... }`（实现见
    `feature/shell/AdaptiveListDetail.kt`）。**rail 必须留在 scaffold 外面**：它进 `extraPane`
    后会被窄窗的 `maxHorizontalPartitions` 收起，而 rail 是导航（切 profile / 切模式），不能消失。
12. **UI 视觉稿不是像素基准**：`.design/详细设计/UI设计/index.html` 是**参考稿**，本身**不严格遵循 M3**。
    实现一律用 **M3 组件 + Compose 原生写法**，间距、圆角、密度**允许与稿子不同**。
    不要为对齐稿子而自绘控件、写死尺寸或覆盖 `MaterialTheme` 默认值；差异判定顺序为
    **功能对等 > 原生观感 > 视觉接近**。

---

## 5. 构建与运行

```bash
# 本机需要 JDK 27+（Temurin，自带 jmods；与 CI 同一基线）。
# 路径写在 .local/local.properties（整个 .local/ 不入库）：
#   hermes.rt.dir=<JDK 目录>
#   hermes.jmods.dir=<同一份 JDK 目录>
./gradlew :composeApp:run          # 运行桌面端
./gradlew test                      # 单元测试
```

**克隆后先初始化 submodule**（Markdown 渲染实现在 `modules/markdown-one`，缺了会编译失败）：

```bash
git -c http.proxy=http://127.0.0.1:7890 submodule update --init --recursive
```

拉新代码后若 submodule 指针变了，同样跑一次上面的命令（或 `git submodule update`）。
改 markdown-one 的代码要在**它自己的仓库**里提交推送，再回主仓库 `git add modules/markdown-one`
更新指针 —— 不要假设主仓库的提交能带走子模块的工作区改动。

**本机网络注意**（Windows / MSYS bash）：

- GitHub 直连 443 不可达，**必须显式走代理**：`git -c http.proxy=http://127.0.0.1:7890 clone ...`
- Gradle 拉依赖同理，`gradle.properties` 里配 `systemProp.https.proxyHost=127.0.0.1` / `Port=7890`（本机已验证）。
- 跑 PowerShell 用 `pwsh`（7.x），不要用 `powershell`（5.1）。

---

## 6. 编码规范

- Kotlin 官方风格；4 空格缩进；文件名 = 顶层声明名。
- UI 状态用 `StateFlow` / `MutableStateFlow`，**不引入** react-query / nanostores 式的缓存框架。
- 每个 feature 一个 `Repository`（负责 RPC/REST 调用与缓存）+ 一个 `ViewModel`（Compose `ViewModel` 或普通类 + `StateFlow`）。
- 可组合函数：`@Composable` + 小写驼峰；公开组件带 `@Preview` 或截图测试。
- 不得在 Composable 里直接发网络请求；副作用走 `LaunchedEffect` + Repository。
- 字符串：中文直接写在 `object Strings` 或 Compose Resources，**不要散落硬编码**在深层组件里。
- 新增依赖必须同时更新 `.design/草案/02-selection.md` 与 `gradle/libs.versions.toml`。
- **新写文件一律 LF**；仓库 `.gitattributes` 强制 `eol=lf`（`*.bat` 保持 CRLF）。
  用脚本批量改写时显式写 LF + 无 BOM：`[IO.File]::WriteAllText($p, ($t -replace "\r\n","\n"), (New-Object Text.UTF8Encoding($false)))`
  —— `Set-Content` / `Out-File` 在 Windows 上默认会引入 CRLF 或 BOM。

---

## 7. 提交规范

- 提交信息用中文，格式：`<类型>: <简述>`，类型取 `feat` / `fix` / `refactor` / `docs` / `chore` / `test`。
- 一次提交一件事；不要混合"重构 + 功能"。
- 涉及设计变更的提交，必须同时更新 `.design/` 对应文档。
- 不提交：`build/`、`.gradle/`、`*.log`、本地路径嗅探文件、任何 token。

---

## 8. 文档与技能索引

| 路径 | 内容 |
|---|---|
| `.design/草案/00-overview.md` | 项目目标、范围、非目标 |
| `.design/草案/01-feasibility.md` | 可行性评估与成本基线（含数据口径） |
| `.design/草案/02-selection.md` | 逐模块技术选型与已知坑 |
| `.design/草案/03-architecture.md` | 分层架构、数据层设计、状态管理 |
| `.design/草案/04-ui-spec.md` | 布局与交互细化（两种模式、三栏、设置页） |
| `.design/草案/05-acceptance.md` | 可量化的验收口径 |
| `.design/草案/06-roadmap.md` | 分期计划与阶段门槛 |
| `.design/草案/07-decisions.md` | 决策记录（ADR） |
| `.design/草案/08-data-contract.md` | REST + WebSocket 契约要点 |
| `.ai/README.md` | 技能清单、来源与同步方式（唯一处） |
| `.ai/skill/` | 按需加载的技能包（目录名 = 技能 ID，必须一级） |
| `.design/详细设计/` | 落地设计（工程结构、依赖、Compose 约定、功能域、UI 视觉稿） |
| `modules/markdown-one/` | Markdown 渲染库本体（submodule，Apache-2.0）：`README.md` 与 `docs/` 是它自己的文档 |
| `modules/markdown-one/docs/api.md` | 渲染库的 API（`MarkdownText` / `MarkdownConfig` / `BlockAdapter` / 视口） |

**工作前先读**：`00` → `03` → 与你任务相关的专题文档。

`.design/` 分两层：**`草案/`** 写「做什么、为什么」，**`详细设计/`** 写「怎么做」（如
`详细设计/UI设计/`）。索引与写作规则见 `.design/README.md`；两层不重复同一内容。

---

## 9. 项目技能与多 agent 接入

- **技能单一事实源**：`.ai/skill/<技能名>/SKILL.md`（目录名 = frontmatter `name` = 技能 ID）。
  命名前缀：`dev-` 技术栈 / `global-` 跨模块流程与纪律 / `server-` 服务器；从外部技能库同步的技能
  **保留上游 `name`**（`compose-multiplatform-desktop`、`kotlin-tooling-*` 等），不要改名。
- **规则只有一个家**：规则内容只写在本文件，其余文件（含各 agent 的配置）**只指向、不复制**。
- 新增技能按 [`global-repo-layout`](.ai/skill/global-repo-layout/SKILL.md) 的步骤建目录并登记清单；
  **不需要**改任何链接或 agent 配置。

各 agent 用**自己的机制**指向这两个中心（技能目录 + 本文件）：

| Agent | 技能接入 | 规则接入 |
|---|---|---|
| **VS Code / Copilot** | `.github/skills` → 符号链接 `../.ai/skill` | `.github/copilot-instructions.md` → 指针文件 |
| Codex / 其它支持 Agent Skills 的工具 | 需要时加 `.agents/skills` → `../.ai/skill` | 原生读本文件 |

**克隆机器的前置条件**：符号链接需要 Windows 开发者模式（或管理员）+ `core.symlinks=true`，
否则 `.github/skills` 会落成**文本文件**（内容就是那行相对路径），技能静默失效且不报错。自检与修复：

```powershell
git config core.symlinks true
Get-Item .github\skills | Select-Object Name,LinkType,Target   # LinkType 必须是 SymbolicLink

# 落成普通文件 / 目录时重建
Remove-Item .github\skills -Recurse -Force
New-Item -ItemType SymbolicLink -Path .github\skills -Target '../.ai/skill'
```

> 链接目标是**正斜杠**相对路径 `../.ai/skill`，保证 Linux / macOS 克隆也能解析。

### 任务路由

| 任务 | 先读 |
|---|---|
| 写 / 改 Compose 桌面代码、选组件、踩坑 | 技能 [`compose-multiplatform-desktop`](.ai/skill/compose-multiplatform-desktop/SKILL.md) |
| 评估「某能力该用哪套栈 / 要不要 WebView」 | 技能 [`desktop-stack-rewrite-assessment`](.ai/skill/desktop-stack-rewrite-assessment/SKILL.md) |
| Gradle / Kotlin 工具链、JDK 版本锁定 | 技能 [`kotlin-tooling-kotlin-toolchain`](.ai/skill/kotlin-tooling-kotlin-toolchain/SKILL.md) |
| 内容该放哪、新建技能怎么命名 | 技能 [`global-repo-layout`](.ai/skill/global-repo-layout/SKILL.md) |
| 拉取 / 推送 / git 卡住 / 提交自查 | 技能 [`global-git-ops`](.ai/skill/global-git-ops/SKILL.md) |
| 脚本驱动真实窗口做端到端验证（键鼠 / 截图） | 技能 [`dev-desktop-gui-e2e-testing`](.ai/skill/dev-desktop-gui-e2e-testing/SKILL.md) |
| 布局与交互 / 验收口径 | `.design/草案/04-ui-spec.md`、`.design/草案/05-acceptance.md` |
| 界面的样子 | `.design/详细设计/UI设计/index.html`（参考稿：非严格 M3、非像素基准） |
| 建工程 / 加依赖 / 写 Compose 代码 | `.design/详细设计/工程与代码/`（01 结构 · 02 依赖 · 03 编码规范） |
| 做某个功能域 | `.design/详细设计/功能/00-功能域总览.md` → 对应域篇 |
| 管理员权限 / 开机自启 / UAC | `.design/详细设计/工程与代码/07-提权与自启动.md` |
| 自启 / 单实例 / 提权「不工作」的排障 | 技能 [`dev-windows-integration-debug`](.ai/skill/dev-windows-integration-debug/SKILL.md)（先查系统真实状态） |
| 后端走 `gateway`（ensure+attach）还是 `isolated` / attach 契约 | `.design/详细设计/工程与代码/06-本地后端与双模式.md` §3.3 |
| 发版 / 本机升级 / patch 包上传 | 技能 [`global-release-patch`](.ai/skill/global-release-patch/SKILL.md) |
| 单实例 / 启动时序 / 早期日志 | `.design/详细设计/工程与代码/08-单实例与启动时序.md` |
| 内置 Hermes 后端 / payload / 运行时绿色化 | `.design/详细设计/工程与代码/06-本地后端与双模式.md`（payload 契约与双模式）· [`Shinglem/hermes-bundle`](https://github.com/Shinglem/hermes-bundle)（CI 构建流程与已知坑） |
| RPC / REST 契约字段 | `.design/草案/08-data-contract.md` |

---

## 10. 变更验证与落地（每条都对应一次返工）

1. **改完立刻编**：先 `./gradlew :composeApp:compileKotlinDesktop`，再 `./gradlew :composeApp:desktopTest`。
   报错只看**第一条**（后面多为连带错误）。同一文件多处编辑后，用 `grep` 复核关键符号
   **只出现一次** —— 重复插入的声明、多余的逗号都只在编译时才暴露，别等发版前才发现。
2. **系统集成问题先取证，再改代码**：自启 / 单实例 / 提权 / 托盘的故障，先枚举**系统真实状态**
   （`schtasks /Query /XML`、进程 `MainWindowHandle`、`logs/startup.log`），确认根因在系统还是代码。
   见技能 [`dev-windows-integration-debug`](.ai/skill/dev-windows-integration-debug/SKILL.md)。
3. **本机升级只覆盖改动文件**：先停进程 → 只拷 `bin/App.jar`（`lib/` 变了才拷对应 jar）→
   hash 核对 → `schtasks /Run` 以托盘态拉起 → 读 `startup.log` 确认。
   **禁止**整目录覆盖（会连带 `config/`、`data/`、`hermes/` 一起冲掉）。
4. **发版前先读 `CHANGELOG.md` 文末「版本号说明」**：桌面 Release 的 tag 是
   `v<桌面版本>-<hermes版本>`（例如 `v0.4.0-0.22.5`），由 `desktop-release` 出完整包；
   同一位数内的小修只发**差异文件**、手工上传、覆盖式更新、**不走 CI**。
   工具链（启动器 + 裁剪运行时）在 `toolchain-v<N>` 里，**只增不改**。
   流程见技能 [`global-release-patch`](.ai/skill/global-release-patch/SKILL.md)。
5. **视觉反馈先量化**：用户说"太淡 / 太小 / 看不清"时，先把判据写成可断言的数字与测试
   （如 `AppPaletteContrastTest` 的对比度门槛），再调数值；改配色要**找齐所有持有色值的地方**
   （界面色板与 Markdown 主题是两套，只改一处必然不一致）。
6. **验证手段按成本排序**：Compose UI 测试 > 日志 / 进程状态 > GUI 键鼠注入。
   elevated 进程的窗口**无法被普通进程注入或关闭**（UIPI），硬试只会浪费轮次 —— 见
   [`dev-desktop-gui-e2e-testing`](.ai/skill/dev-desktop-gui-e2e-testing/SKILL.md) §3.5。
7. **改过用户环境就要还原**：临时改 `config_custom.json`（提权开关、主题）先备份、验完还原；
   临时脚本、临时截图、临时置顶窗口一并清理。报告里写清"被测环境动过什么"。
