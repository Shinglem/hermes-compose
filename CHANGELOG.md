# 变更日志

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，用中文表述。
版本号口径见文末「版本号说明」——桌面 Release tag 形如 `v<桌面版本>-<hermes版本>`。

---

## [0.5.3] · 2026-11-09（hermes 0.23.0）

> **完整包版本**：tag `v0.5.3-0.23.0`，由 CI `desktop-release` 构建。
> 纯桌面外壳（不含 payload）；payload 与工具链不变：hermes 0.23.0 / `toolchain-v1`。

### 修复

- **流式回答的滚动跟随**（本轮集中修的一串问题）：
  - 「回到最新」之后不再停止跟随：跟随判定读的是首次组合时捕获的旧状态，后续输出
    永远被当成「没有流式输出」。
  - 列表停在中途时不再误判「已贴底」：距底距离只量最后可见项，后面还有整屏内容时算出来是 0。
  - 向上滚一帧就**立刻**交还滚动权（不再按"离开底部 24px"判定），也不会被补滚反复拉回；
    恢复跟随只认用户动作（向下滚回底部 / 点「回到最新」/ 发新消息）。
  - 补滚从「一帧内瞬移」改为**逐帧平滑滑动**（实测旧实现单帧跳 53–224px，是"输出时上下跳跃"
    的主因）；补滚不再按条目下标重锚定、不再强制重排。
  - 「回到最新」按钮不再跟着补滚动画一帧显示一帧隐藏。
- **正在看的会话不再同时弹系统通知**：判据改为「审批 / 提问卡片是否已渲染在眼前」，
  会话匹配只比全局唯一的 sessionId（此前 profile 的 `null` 与 `"default"` 两种写法不一致，
  会把正在看的会话判成别的会话）。
- **关到托盘再唤起后，第一个字绕过输入法**：窗口关到托盘时不再销毁重建（每次重建的新窗口
  输入法上下文是空的，敲下的第一个键会直接落进输入框），改为常驻组合、只切换可见性。

### 升级

1. 下载 `hermes-desktop-compose-0.5.3-0.23.0-windows-x64.7z.001`（+ 后续卷）与 `SHA256SUMS.txt`，
   核对 SHA-256；
2. 解压覆盖安装目录；`config/`、`data/`、`hermes/`、`rt/` 不动；
3. 重启客户端。只换 `bin/App.jar` 也等价（`lib/` 未变）。

---

## [0.5.2] · 2026-11-07（hermes 0.23.0）

> **完整包版本（小版本，按新约定也发全量）**：tag `v0.5.2-0.23.0`，由 CI `desktop-release` 构建。
> 纯桌面外壳（不含 payload）；payload 与工具链不变：hermes 0.23.0 / `toolchain-v1`。

### 修复

- **流式回答不自动跟到底**：列表定位末尾一项时用了默认 `offset=0`，把**这一项的顶部**对齐视口
  顶部 —— 长回答时表现为「一直停在回答开头、看不到最新输出」。现在让最后一项的**底部**贴视口底边。
- **跟随状态被轻微滚动永久关掉**：旧实现按滚动方向判定，任何负 delta（触摸板惯性、滚轮余量）
  都会把 `followTail` 置 false 且只有「向下滚」才恢复。现在：
  - 上翻离开底部超过 24px 才暂停；
  - 滚回底部 8px 内自动恢复跟随；
  - 向下滚不再直接恢复（离底部远时方向向下 ≠ 想追最新）。
- **吸底按钮在流式中后段消失**：条件从 `sending` 改为 `streaming != null || sending`。

### 变更

- **思考过程默认展开**（点标题折叠）；流式期间仍是纯文本，一轮结束后按 Markdown 渲染，
  展开态限高 320dp 内部滚动。
- **补滚只在有流式输出时做**：展开/折叠历史卡片不会再被自动滚到底部（不抢滚动）。

### 升级

1. 下载 `hermes-desktop-compose-0.5.2-0.23.0-windows-x64.7z.001`（+ 后续卷）与 `SHA256SUMS.txt`，核对 SHA-256；
2. 解压覆盖安装目录；`config/`、`data/`、`hermes/`、`rt/` 不动；
3. 重启客户端。只换 `bin/App.jar` 也等价（`lib/` 未变）。

---

## [0.5.1] · 2026-11-07（hermes 0.23.0）

> **完整包版本（小版本，但按新约定也发全量）**：tag `v0.5.1-0.23.0`，由 CI `desktop-release` 构建。
> 产物是**纯桌面外壳**（不含 payload），所以体量与构建时间都很小；首次使用仍需自己补一份 payload。
> payload 与工具链都不变：hermes 0.23.0 / `toolchain-v1`。

### 新增

- **记忆**（设置 → 智能体 → 记忆）：本版**只用内置记忆** —— 页面顶部「应用到」可切换配置目标档案
  （只影响这一组设置页，不改会话页当前档案）、内置层四项配置（持久记忆 / 用户画像开关、
  记忆预算 2200 / 画像预算 1375，稀疏 diff + 回读）、`MEMORY.md` / `USER.md` 体积、重置（二次确认）。
  外部记忆 provider（byterover / retaindb）只做**只读状态展示**（名称 + 就绪/待配置/缺依赖 + 缺什么），
  不提供启用、依赖安装或填 key 的入口：它们要额外装 `brv` CLI 或订阅 RetainDB 云服务，
  随「插件功能」统一规划。
- **凭据池**（设置 → 提供方 → 凭据池）：同一 provider 的多把 key 增删与状态查看；
  后端只回脱敏预览，明文不回显。
- **用量与成本**（设置 → 应用）：跨会话 token / 调用数 / 估算成本，按模型与按天汇总，7 / 30 / 90 天切换；
  另有模型卡片（工具调用数、均值 tok/会话）与辅助任务成本拆分。
- **会话维护**（设置 → 应用）：整库统计（总数 / 活跃 / 归档 / 消息数 / 来源）、空会话清理、
  按条件清理（先 dry-run 预览再执行）、从 JSON 文件导入会话。
- **诊断与维护**（设置 → 应用）：doctor、安全审计、备份（服务端生成 zip）、检查点清理、
  curator 暂停与立即运行、dump、debug-share。这些是后端**后台动作**，页面轮询
  `/api/actions/{name}/status` 取日志尾部，不必再开官方客户端。
- **备份闭环**：产物可「下载到本机」（`%LOCALAPPDATA%\hermes-compose\backups`），
  也可按服务端路径**恢复**（`POST /api/ops/import`，二次确认）。
- **外部集成**（设置 → 集成）：shell 钩子的新增/删除（同时写同意白名单）与配对白名单的批准/撤销。
- **记忆文件查看/编辑**：`MEMORY.md` / `USER.md` 在页面内直接读写
  （`/api/fs/read-text` + `/api/fs/write-text`，绝对路径由 `HERMES_HOME` + 档案推导），保存后重读体积。
- **档案作用域条**：所有档案级设置页顶部统一显示「应用到」（维护页自带，其余由设置详情区渲染）。

### 说明

- 五块共用 `MaintenanceRepository` / `MaintenanceViewModel`；请求都带 `?profile=`，切 profile 时整体作废。
- 会话导入要求后端 `{sessions: [...]}`（会话行）；本客户端导出的消息列表**不能直接回灌**，页面文案已写清。
- 凭据池的「优先级 / 单条启停」后端没有对应接口（只有整池增删），客户端不做假入口。
- 外部记忆 provider 的启用、依赖安装与配置表单随「插件功能」统一规划。

### 升级

1. 下载 `hermes-desktop-compose-0.5.1-0.23.0-windows-x64.7z.001`（+ 后续卷）与 `SHA256SUMS.txt`，
   先核对 SHA-256；
2. 解压覆盖到既有安装目录（保持相对结构）。**`config/`、`data/`、`hermes/`、`rt/` 都不要动** ——
   本版只改客户端外壳，payload 与运行时与 0.5.0 完全一致；
3. 重启客户端（托盘图标 → 退出 → 重新打开）。`logs/startup.log` 应出现
   `instance acquired` / `formula ratex=ok` / `autostart … pending=false` 三行；
4. 设置左栏新增：智能体 →「记忆」，提供方 →「凭据池」，应用 →「用量与成本 / 会话维护 / 诊断与维护」，
   集成 →「外部集成」。

> 只想要最小改动的话，单换 `bin/App.jar` 也等价（本版 `lib/` 未变）；完整包只是省得自己挑文件。

---


## [0.5.0] · 2026-11-06（hermes 0.23.0）

> **完整包版本（桌面版本升位）**：tag `v0.5.0-0.23.0`，由 `desktop-release` 走 CI 出完整包。
> payload 换到 hermes **0.23.0**（`Shinglem/hermes-bundle` 的 `v0.23.0`），
> 工具链仍是 `toolchain-v1`（JDK 基线没变）。
>
> 首次构建的完整包在 `gateway` 路线下**发不出消息**（会话列表、令牌、RPC 契约三处缺陷），
> 已在本版内全部修掉并**重新出包**；因此 0.5.0 本身不另出修复补丁（后续 0.5.1 是独立的功能版本，
> 不是 0.5.0 的修复包），早期下载的 `v0.5.0-0.23.0` 应当作废重装。
>
> **升级方式**：下载完整包解压即可；旧的 `hermes/` 目录请**整目录替换**成 0.23.0 的 payload
> （`pm bundle` 产物不能增量覆盖）。`config/` 与 `data/` 可以保留 —— 但 0.23.0 会升级后端
> 配置格式，首次启动会自行迁移。

### 新增

- **排队区 + 插队**：生成中再发的那条消息现在挂在 composer 上方的「排队中」区域，
  不再插进当前轮（以前它会跑到正在流式的思考过程前面）。每条排队项两个动作：
  - 「立刻插入」→ `prompt.steer_queued`：把这条文本交给正在跑的那一轮（当前轮还没起
    agent 时会回 `steer_unavailable`，等它跑起来再点）
  - 「取消」→ `prompt.cancel`

  归属判据是「**发送时是否正在生成**」，不是 ack 的 `status` —— 后端对新会话的首条消息
  也回 `queued`（agent 还没起来），那条显然属于这一轮。队列本身是后端状态
  （`session.resume` 的 `pending[]`），重连/切回后会对齐。

- **跟随官方 canonical 路线接入后端**：`local` 模式默认改成
  `hermes gateway ensure --json` → attach 上游**机器级 gateway**，与官方 Desktop / TUI / webapp
  走同一条路。收益是 owner 侧统一的队列、history、steer/rewind 语义，不再和机器上其他入口分叉。
- **新增配置 `backend.local.engine`**：`gateway`（默认）/ `isolated`（回退到旧的自起
  `dashboard --isolated` 路线）。设置页「连接 → 后端」里可直接切。
- 新增 `GatewayAttachPlanner` / `GatewayEnsure` / `GatewayAttachBackendProvider`，
  以及 `OneShotRunner`（跑完即退的一次性命令）。
- **「启动官方桌面端」改成共用同一后端**：设置页「集成 → 官方桌面端」会显示当前要共用的地址
  （`http://127.0.0.1:<port>`），后端未就绪时按钮禁用。启动前会先 `GET /api/status` 探活，
  不可用就**拒绝启动**而不是静默省略 `HERMES_DESKTOP_REMOTE_URL/TOKEN` ——
  后者会让官方桌面端另起一套后端，两边的会话直接分叉。
- **设置页重新分组**：左栏从「选项（打包 5 组）+ 终端 + 提供方 + 配置档案 + 模型 + 安全 + 桌面 + 调试」
  改为 8 个语义分组：连接 · 外观 · 智能体 · 提供方 · 安全 · 桌面 · 应用 · 集成。
  `界面与网络` 拆成「界面」「网络」；`窗口` 与 `终端外观` 归入「外观」；`关于` 归入「应用」；
  组标题加了间距、字号与分隔线，与页行的层级一眼可分。

### 变更

- **Web UI 入口整体移除**：状态栏不再有「服务 xxms」那一项（它原本表示 dashboard 的 SPA
  能否打开），托盘菜单删除「打开 Web 界面」。客户端只对接官方 gateway，一个「网关」指标足够。
  连带删除 `Ready.dashboardUrl` 字段、`probeWeb()`、`hasWebDist` 自检与相关提示。
  payload 里的 `hermes_cli/web_dist` 仍由 bundle 提供，只是本客户端不再使用。

- **依赖升级**：`ktor 3.6.0 → 3.6.1`、`logback 1.5.18 → 1.6.6`、`kotlin-logging 7.0.7 → 8.0.4`。
  Kotlin 2.4.21 / Compose 1.12.2 已经是最新稳定，未动。
- **`backend.local.host` / `port` / `extra_args` 在 `gateway` 路线下不生效**：端口由 daemon 决定，
  通过 ensure 输出的 `endpoint.api_origin` 告知（每次重启都可能变）。要给隧道固定地址就切
  `isolated`。
- **`app.browser.headed` / `app.browser.args` / `backend.local.run_cron` 在 `gateway` 路线下改完要重启后端**
  （它们是 daemon 的启动环境变量）；设置页的保存提示已经写明。

### 修复

- **老 home 升级后「网关 xxms 但一直重连中」**：`gateway` 路线下，daemon 启动时走上游
  `served_profile_child_env(inherit_credentials=True)`，它会用 `HERMES_HOME/.env` 里的
  `HERMES_DASHBOARD_SESSION_TOKEN` **覆盖**客户端注入的值。于是客户端拿着自己随机生成的令牌去连
  daemon，WebSocket 握手 403、无限重连（HTTP `/api/status` 却是 200，所以状态栏显示延迟正常）。
  只在 `.env` 里已有该键的老 home 上复现；全新的空 home 不会。
  现在会话令牌按**四段优先级**取值：`home/.env` 的 `HERMES_DASHBOARD_SESSION_TOKEN` → 配置里的
  `backend.local.token` → 上次持久化的 `<data>/gateway-token` → 新生成并落盘。
  daemon 是常驻进程，重启客户端时令牌必须不变。

- **会话列表一条都不显示**：列表读取从 RPC `session.list` 改为 REST `GET /api/sessions`
  （0.23.0 的 RPC 默认 `scope=live`，只返回运行中的会话），但 `limit` 沿用了旧 RPC 时代的 **200**，
  而上游该接口的校验是 `Query(le=100)` —— 每次请求都 `422 less_than_equal`，异常被吞成空列表。
  现在上限统一 clamp 到 100，`RestClient.listSessions` 内部兜底，调用方不再各自记这个数。
  同时补了可观测日志（`sessions refresh requested` / `sessions loaded: … count=` /
  `sessions load failed …`）。

- **发消息一律 `4001 invalid_params`**：0.23.0 的 canonical gateway 对 `prompt.submit` 有两条硬要求
  —— 必须带 `submission_id`（这次 admission 的幂等身份），且键集合是闭集、**不接受 `profile`**。
  旧实现两条都不满足，所以每一次发送都被判成参数错误。

- **「停止 / 纠偏 / 审批」`4001 invalid_params`**：`session.interrupt` / `session.steer` /
  `approval.respond` 在 canonical 契约里都**必须**带 `execution_generation`，审批还要 `prompt_id`
  而不是 `request_id`。现在 `session.resume` 会把 `execution_generation` 与 `revision` 一起带回来，
  `ChatViewModel` 保存它们并在每次 mutate 后写回后端回执里的新鲜值。

- **会话编辑（改名 / 归档 / 删除 / 分支）全部 `-32601 unknown method`**：0.23.0 的 gateway 路线删掉了
  `session.title` / `session.archive` / `session.delete` / `session.branch*` / `session.cwd.set`
  / `session.usage`。替代通道：

  | 功能 | 现在走 |
  |---|---|
  | 改名 / 归档 / 置顶（已落库会话） | REST `PATCH /api/sessions/{id}` + `request_id` + `expected_revision` |
  | 改名（刚新建、还没落库） | `session.mutate {operation:"rename"}` |
  | 删除 | REST `DELETE /api/sessions/{id}`（同一套 fence） |
  | 分支 / 复制为新会话 | `session.mutate {operation:"branch"}`（`through_message_id` 是物理行 id） |
  | 回退重发 | `session.mutate {operation:"rewind"}` + 单独一次 `prompt.submit` |
  | 上下文用量 | `session.context_breakdown`（`session.usage` 只剩事件） |
  | 切模型 / 推理强度 | `session.mutate {operation:"model"\|"reasoning"}`（`config.set` 会回 `use_session_mutation_model`） |
  | 图片附加 | `image.attach_bytes`（base64）；路径版与 `clipboard.paste` 只在 direct 后端有 |
  | 拖放探测 | 客户端本地判扩展名（`input.detect_drop` 只在 direct 后端有） |

  `session.mutate` 的参数集是**精确匹配**（`gateway/session_mutations.py::_FIELDS`）：
  `session_id` + `request_id` + `expected_revision` + `operation` + `payload`（+ 可选
  `expected_generation`）；`profile` 由 dispatch 层先用于跨 profile 路由、再被
  `_PROFILE_IMPLICIT` 剥离。fence 过期时用拒绝里带回的 `data.revision` /
  `data.execution_generation` 重试一次。

- **「发消息没反应」/「一直正在生成」**：后端会停下来等人（`kind=approval` 的危险命令、
  `kind=clarify` 的提问），它有两种到达方式 —— 实时的 `server→client` 请求帧，以及
  `session.resume` 快照里的 `prompts[]`。以前只实现了前者，于是**重连 / 切回会话后**
  后端一直在 `waiting`、界面上却什么都没有（日志里连错误都没有）。
  现在 `session.resume` 之后会把快照里的 `approval` / `clarify` 全部补成卡片，
  作答走 `approval.respond` / `clarify.respond`（要 `prompt_id` + `execution_generation`）。
- **提交后长期静默**：加了看门狗（90s 无该会话任何事件就对账一次），
  `running=false` 才收尾并提示，`running=true` 继续等（长工具调用不该被打断）。
- **系统通知「3 个审批只收到 1 个」**：根因不是通知层，而是**「用户是否正在看」这一判定错了**。
  `AppFocus.isForeground` 用的是 AWT 的 `window.isFocused`（**进程内**焦点），而
  `Window.getWindows()` 里除主窗口还有 tooltip / 弹层等辅助窗口 —— 任意一个持有进程内焦点，
  `any { isFocused }` 就为真，于是「人在别的应用里」被判成「正在看我」，提醒被静默跳过
  （实测日志：三行 `remind skipped … (foreground and viewing this session)`，只有一条漏网弹出）。
  改成 `isActive`（AWT 语义 = **系统级**活动窗口）并只认 `Frame` / `Dialog`；
  再进一步：**权威值改为界面层推送**（Compose `LocalWindowInfo.isWindowFocused`，只盯主窗口），
  AWT 判定降级为"未收到推送前的兜底"；
  同时把窗口焦点状态加进补提醒的 `LaunchedEffect` key —— **切到后台本身也是一次「用户切走了」**，
  之前因前台被跳过的那几条现在会重新评估、补弹。
  附带：同时到达的多条 toast，Windows 只弹一条横幅（其余静默进通知中心），
  所以 `NativeNotifications.show` 按 1.5s 间隔逐条发，让每条都有自己的横幅。
  （顺带排除并记录了另一个假设：同 `group` **不会**折叠 —— `nucleus 2.5.18` 实测连发
  3 条带按钮的 toast，通知中心里 3 条都在。）
- **切到别的会话后，那边的审批不再"静默"**：`handleGatewayEvent` 的「非当前会话」分支
  把所有事件直接 `return`，而审批 / 提问的处理写在它后面 —— 你发完请求切走，审批到达时
  既不弹通知也不进任何队列，只有切回来才从快照看到。现在该分支会先登记并弹提醒
  （**不写当前界面**，避免你在会话 B 看到会话 A 的卡片）；切回该会话时由 `session.resume` 补成真卡片。
- **点系统通知里的「允许一次」报 `4001 stale_generation`**：应答用的是**当前会话**的
  `key` 与 `execution_generation` 现查（你在会话 B 点会话 A 的审批，回传的是 B 的值）。
  现在 `PendingApproval` 自带 `executionGeneration`，应答一律用**卡片自己记的**会话与 fence；
  `clarify` 同样处理。跨会话那条也登记进 `crossSessionApprovals` —— 否则点通知时找不到它，
  点击等于没反应。
- **已答过的审批卡片残留、再点报错**：三处没跟后端对账 ——
  ① `savedStates`（切走时的快照）也持有 `pendingApprovals`，而应答只清了当前 `_state`；
  ② `adoptSnapshotPrompts` 只补不删（`resume` 的 `prompts[]` 才是权威）；
  ③ `RESTORE_SNAPSHOT` 分支恢复快照后直接 `return`，完全不核。
  现在统一走 `forgetPendingRequest()`（一次清 `_state` + 所有快照 + 跨会话表）、
  快照对账会 drop 掉后端已不认的条目、恢复快照后异步 `ensureRuntime(force)` 复查。
  另外 `stale_generation` / `*_not_found` 这类**"已被别处处理"**的回执不再弹红条，静默收掉。
- **`data/logs/desktop.log` 每行写两遍**：`Logging.init` 里在 `doConfigure` 之前没 `reset`，
  而 logback 在第一次访问 `LoggerFactory` 时已按 classpath 自动配置过一次 ——
  等于给 root 挂了两个同名 appender。副作用不只是体积：排查时会把「同一事件出现两次」
  误判成事件被重复分发。

### 变更

- **RPC 调用层按契约重建**（0.23.0 的 canonical 契约是闭集，散在各处的参数靠试错维护不住）：
  - `RpcContracts`：从 payload 的 pydantic 模型导出的**方法契约表**（允许键 + 必填键），
    `GatewayClient.call` 发送前自检 —— 多传/漏传在本地就报出来，不用等后端 4001。
  - `SessionMutator`：`session.mutate` 与控制类调用（`interrupt` / `steer` / `approval.respond` /
    `clarify.respond`）的**唯一实现**，fence（`expected_revision` / `expected_generation`）
    自动携带、被拒时用回执里的新鲜值重试一次。以前这段逻辑在两个 Repository 各写一份。
  - fence 单一来源：`session.resume` 回执 + 每个事件帧顶层的 `execution_generation` +
    写入/拒绝回执。
  - `profile` 规则明确：**会话级调用统一带**，因为它要么在契约里，要么在
    `_PROFILE_IMPLICIT` 里被后端剥离；不带的话兄弟 profile 的会话会命中错误的 authority
    （`permission_denied`）。

### 已知行为变化

- **客户端退出时显式停后端**：daemon 用 `CREATE_BREAKAWAY_FROM_JOB` 主动脱离了我们的 Job Object，
  所以不再是「客户端死则后端死」。正常退出会执行 `hermes gateway stop -y`（作用域只到本 home）；
  客户端被强杀时 daemon 会残留，下次启动由 `ensure` 直接复用 —— 这是上游的设计，不是 bug。
- **`gateway stop` 绝不带 `--all`**：那会作用于 host gateway，可能停掉别的 profile（包括官方客户端）。

---

## [0.4.2] · 2026-11-06（hermes 0.22.5）

> **patch 版本**：相对 `v0.4.1` 只改客户端外壳（`bin/App.jar` + 2 个 `lib/*.jar` + `app/*.cfg`），
> 覆盖式升级，`config/` 与 `data/` 不动。payload 与工具链都不变，仍是 hermes 0.22.5 / `toolchain-v1`。

### 新增

- **外部编辑器唤起**（设置 → 桌面 → 外部编辑器）：工具卡片（`write_file` / `read_file` / `patch`）
  标题行出现「打开」按钮，用配置好的编辑器打开文件、文件所在文件夹、会话项目根，或对比两个文件。
  支持多个编辑器配置、五种动作各自开关与参数模板（`{path}` / `{line}` / `{dir}` / `{file1}` / `{file2}`），
  某动作关闭时回退到「打开文件」。默认模板走新窗口（`--new-window {path}`），不顶掉你正在用的窗口。
- **代码高亮引擎换成 SyntaxMP**：54 个语言标签，另外用手写 extension 补上 bat / cmd / vue / xaml /
  svg / cfg / jsonc / scala / lua 等 GFM 词法器没有的语言。
- **代码块行号 + 整行高亮**：围栏信息串可写 ```` ```kotlin {3,5-7} ````，左侧独立行号列（右对齐），
  高亮行是整行通栏底色 + 左侧强调条。
- **文内锚点跳转**：标题按 GitHub slug 生成锚点，`[文字](#标题)` 在应用内滚动而不打开浏览器；
  脚注引用渲染成上标且可点，脚注定义区每条末尾加「↩」回跳。
- **图片块级化 + 点击放大**：独占一行的图片升级为块级（受视口限高），点击全屏预览。
- **长公式横向滚动**：块级公式溢出时横向滚动，并显示滚动条（桌面端默认不画，溢出了也看不出来）。
- **浏览器相关两个设置项**（设置 → 桌面 → 浏览器）：「显示浏览器窗口」（`app.browser.headed`）与
  Chromium 启动参数（`app.browser.args`，以管理员运行时自动补 `--no-sandbox`）。
  **当前受上游阻塞不可用**，见「已知问题」。

### 变更

- **会话创建时注入渲染能力说明**：通过 `session.create` 的 `client_env`
  （`HERMES_EPHEMERAL_SYSTEM_PROMPT`）告诉 agent 本客户端真实的渲染能力，**不写任何后端配置文件**。
  起因见下面「修复」的第 2 条 —— 会话面改成 `tui` 后，agent 把整个会话当成终端自我审查
  （说过「这是 TUI，没有附件通道」「终端里公式不渲染」，对本客户端都是错的）。
  只对**新建**会话生效；`resume` 与「复制为新会话」不接受这个字段。

### 修复

- **多个审批只弹出一条系统通知**：`tag` 原本是常量，WinRT 语义下同 `tag`+`group` 是「更新同一条」，
  后发的直接替换先发的；token 表也在每次 `show()` 里整体清空，导致前一条的按钮点了没反应。
  现在 `tag` 按请求唯一（16 字符上限内），token 表按 `tag` 分桶。
- **回答退化成命令行风格（丢 Markdown 排版）**：我们拉起后端时设了 `HERMES_DESKTOP=1`，后端据此把会话
  判成 `desktop`，下发 `desktop_ui` 工具集；那套工具要求客户端有 Electron 渲染进程里的预览面板应答
  `preview.read` 这类阻塞请求，我们没有，于是 agent 用 `desktop_preview` 一律 45 秒超时。
  改成显式传会话面后工具集变干净，但一开始选的是 `cli`，而后端 `cli` 的提示词明确要求「不许用
  Markdown」。现在统一用 `tui`（工具集与 `cli` 相同，提示词不禁止 Markdown）。
- **块级公式显示成源码**：GFM 只认「整段 `$$…$$`」，块内某行像别的块语法时（真机案例：续行以
  ` + \left(…` 开头被当成无序列表）整段会被拆成「段落 + 列表」，公式再也拼不回来，
  还连带把 `\sum_{i=1}` 里的 `_` 当强调吃掉。现在解析前把独占一行的 `$$…$$` 改写成 ` ```math ` 围栏
  （围栏内部不参与块语法），渲染时再还原成公式块。
- **在公式区横向滚动会把整篇文档弹到底部**：滚动监听用 `scrollDelta.y >= 0` 判定「向下滚 → 恢复跟随」，
  而横向滚事件的 `y` 恒为 0，`0 >= 0` 成立，于是立刻补滚到底。现在只认垂直主导向的滚动。
- **外部编辑器菜单出现 3 条一模一样的「打开文件」**：菜单项复用了「动作不可用时回退到打开文件」
  的逻辑，于是「打开所在文件夹」「对比两个文件」「定位到行」全塌成同一条。现在菜单不回退并按命令去重。
- **公式引擎失效时无任何提示**：ratex 把 native dll 解压到 `%LOCALAPPDATA%\RaTeX`，解压是「先删后拷」，
  目标被别的进程占用时整条公式渲染会静默退化成源码。现在 `startup.log` 启动时记一行
  `formula ratex=ok` / `ratex=FAILED <路径>`。
- **从桌面端启动官方对照客户端后停在「配置后端」向导**：缺 `HERMES_DESKTOP_REMOTE_URL` 与
  `HERMES_DESKTOP_REMOTE_TOKEN`（两者必须同时给，只给 URL 会直接抛错），于是它走「自己拉起后端」
  的本地路径。现在把正在跑的后端地址与会话 token 一起传过去，启动后直接进会话列表。

### 已知问题

- **`browser_exec` 在管理员权限下起不来**（`Chrome exited early (exit code: 0) without writing
  DevToolsActivePort`）。后端 `_needs_chromium_sandbox_bypass()` 只判 Linux root / Docker / AppArmor，
  Windows 恒 false；我们已按提权状态注入 `AGENT_BROWSER_ARGS=--no-sandbox,--disable-dev-shm-usage`，
  但上游修复（#1430）未合并，稳定版仍拿不到。**本版不承诺浏览器工具可用**；
  设置页的两个开关会保留，等上游修好。
  同一台机器上管理员运行的后端也解释了为什么官方对照客户端没这个问题 —— 它用 Electron 内置的
  渲染进程，不起外部 Chrome。

### 升级

1. 下载 `hermes-desktop-compose-0.4.2-patch.zip` 与 `SHA256SUMS.txt`，先核对 SHA-256；
2. 退出正在运行的客户端（托盘图标 → 退出）；
3. 把 zip 里的 `bin/`、`lib/`、`app/` 覆盖到安装目录（保持相对结构，`config/` 与 `data/` 不要动）；
4. 确认 `lib/syntaxmp-tokenizer-jvm-0.3.0.jar` 已就位 —— 它是本版**新增**的依赖，
   缺了会在打开带代码块的消息时 `NoClassDefFoundError`；
5. 重新启动客户端。`logs/startup.log` 里应出现 `formula ratex=ok`。

## [0.4.1] · 2026-11-05（hermes 0.22.5）

> **patch 版本**：相对 `v0.4.0-0.22.5` 只改客户端外壳（`bin/App.jar` + 若干 `lib/*.jar` + `app/*.cfg`），
> 升级方式是**下载 patch 包覆盖安装目录里的同名文件**，`config/` 与 `data/` 不动。
> payload 与工具链都不变，仍是 hermes 0.22.5 / `toolchain-v1`。

### 新增

- **GitHub Alerts**：`> [!NOTE]` / `[!TIP]` / `[!IMPORTANT]` / `[!WARNING]` / `[!CAUTION]`
  五种提示块，图标 + M3 语义色（蓝 / 绿 / 紫 / 琥珀 / 红）。GFM 解析器本来就产出 `ALERT` 节点，
  这里只是补了块类型与渲染。
- **脚注**：`正文[^1]` 渲染成上标，文末自动生成「脚注」列表。注意这不是 CommonMark / GFM
  规范能力（GitHub 的产品特性），所以由解析前的预处理实现：抽取 `[^id]: …` 定义（支持缩进续行）、
  正文改成行内标记、定义区从原文移除。
- **原始 HTML 块的原生渲染**：用 **ksoup** 解析 + GitHub `html-pipeline` 的元素/属性白名单
  （60 个元素；**没有 `style` / `class` / `on*`**），块级标签映射成 Compose 块
  （标题 / 段落 / 引用 / 列表 / 表格 / 代码 / 图片 / `details`），**布局级 CSS 一律忽略**
  （`display` / `flex` / `grid` / `position` / `float` 等），`style` 只认 6 个能映射到
  `TextStyle` 的属性（颜色 / 背景 / 字号 / 字重 / 斜体 / 对齐）。防护：限深 32、限节点 5000，
  `script` / `style` / `noscript` 整棵子树丢弃。
- **PlantUML 与 Graphviz DOT**：图表引擎换成 `diagram-render`，与 mermaid 走同一个适配器，
  另外补齐 gantt / mindmap / ER / pie / gitGraph 等 20+ mermaid 图族。

### 变更

- **公式引擎换成 RaTeX**（`io.github.darriousliu:ratex`，Rust 核心 + Compose 矢量绘制）。
  14 个高级用例对比：`\mathbb` / `\mathcal`、`\left\{ \begin{matrix}`、`\operatorname` 正体、
  `\xrightarrow` 长度、`align` 逐行编号都比原引擎正确。代价是随包多一份 1.1 MB 的 native dll
  （运行时解压到 `%LOCALAPPDATA%\RaTeX`）。
- **图表引擎换成 `diagram-render`**：跟随 M3 主题（旧引擎硬编码白底，暗色下刺眼），
  体积净减约 0.4 MB。`mermaid-compose` 与 `latex-renderer` 依赖已移除。
- **移除 JCEF**：`cef/`（约 310 MB）不再随包，也不再有任何内嵌浏览器链路。
  `MarkdownConfig` 的 `webFallback` / `cefNativeDir` / `jcefPoolSize` 等 5 个字段删除，
  `AdapterKind.Jcef` 删除 —— 这是**破坏性变更**，但目前只有本仓库在用。

### 修复

- **同一会话多个审批只弹出一个通知**：右下角通知窗原来是单槽（后一条直接顶掉前一条，
  且该请求已被记住"提醒过"，于是永远不再出现）。改成队列 + 最多 3 条同屏堆叠，
  每条独立自动收起、独立关闭。
- **开始菜单快捷方式重复**：原生通知打开时只按文件名判断"是否已存在"，用户手动建的
  （或旧版本用固定名建的）指向本程序的快捷方式会被忽略，于是开始菜单多出一个入口。
  现在扫描 `Programs\` 下的 `.lnk`（纯字节解析目标路径与 AUMID）：
  已带 AUMID 的直接复用；指向本程序但没有 AUMID 的，用 PowerShell + COM（`IPropertyStore`）
  把 AUMID 补写进去再复用（写前备份 `.lnk.bak`）；补写失败才新建，并在设置页说明原因。
  为不拖慢 `--silent` 启动，补写在后台线程执行，期间通知自动回退应用内窗口。
- **HTML 块里的行内格式丢失**：`<b>` / `<i>` / `<code>` / `<a>` 在解析前被改写成 Markdown
  标记，而 HTML 块的文本节点按纯文本输出，界面上会看到裸露的 `**粗体**`、`` `行内码` ``、
  `[链接](url)`。现在文本节点按标记还原样式（含图片）。
- **块级 `style` 与 `align` 被丢掉**：`<div align="center" style="font-size:28px">` 既不居中
  也不放大。现在清洗阶段把 `align` 并入 `textAlign`，块级颜色 / 字号 / 字重 / 斜体 / 对齐
  统一合并进 `TextStyle`。

### 升级

1. 下载 `hermes-desktop-compose-0.4.1-patch.zip` 与 `SHA256SUMS.txt`，先核对 SHA-256；
2. 退出正在运行的客户端（托盘图标 → 退出）；
3. 把 zip 里的 `bin/`、`lib/`、`app/` 覆盖到安装目录（保持相对结构，`config/` 与 `data/` 不要动）；
4. 可选：删除 patch 说明里列出的旧 jar（`mermaid-compose-*`、`latex-renderer-*`、
   `latex-fonts-katex-*`、`jcefmaven`、`jcef-api-*`、`jcef-natives-*`）——
   patch 是覆盖式升级，删不掉旧文件，留着不影响运行，只是多占约 9 MB；
5. 重新启动客户端。若安装目录里还有旧的 `cef/`（约 310 MB），现在已完全不用，可手工删除。

## [0.4.0] · 2026-11-04（hermes 0.22.5）

> 首个按新规则发布的版本：tag `v0.4.0-0.22.5`，产物是**纯外壳**（不含 payload），
> 默认 `local` 模式，payload 自己从 `Shinglem/hermes-bundle` 的 `v0.22.5` 下载后放进安装目录。
> 运行时基线换成官方 **Temurin 27**。

### 新增

- **四套新配色风格**：`options.ui.style` 从 2 个扩到 6 个 ——
  `material`（默认）/ `paper`（纸面）/ `ocean`（海洋蓝）/ `sage`（灰绿）/ `plum`（梅紫）/ `amber`（琥珀）。
  每套都有亮暗两版（明暗仍由 `options.ui.theme` 决定），改完立即生效；设置页的选项直接显示中文名。
- **内置 Inter / JetBrains Mono 字体**：换到 Temurin 后 JBR 自带的 43 个字体不复存在，
  界面观感会变。把 JBR 上实际用到的两族随应用分发（SIL OFL 1.1，见
  `composeResources/files/LICENSE-*.txt`），`MaterialTheme(typography = appTypography())` 统一接管；
  中文仍由系统字体回退（Inter 没有 CJK 字形）。
- **JCEF 自备 Chromium**：Temurin 没有 `jcef` 模块，改由 `me.friwi:jcefmaven` 提供 `org.cef.*`、
  `me.friwi:jcef-natives-windows-amd64` 提供 native bundle（构建期解压到 `build/cef/bin`，
  成品放 `<安装目录>/cef`）。CEF 的加载器被换成按**绝对路径**加载，所以与 `java.home`、
  `java.library.path` 都无关；`MARKDOWN_CEF_DIR` / `-Dmarkdown.cef.dir` /
  `MarkdownConfig.cefNativeDir` 三个口子任选。详见 `.design/详细设计/工程与代码/11-JCEF集成.md`。

### 变更

- **审批 / 提问只显示在发起它的会话里**：后端是"全局在等"，但界面之前把所有待处理请求都画在当前
  会话，切到别的对话也会看到别人的审批，容易误当成自己要处理的事情。现在按会话过滤
  （`ChatState.visibleApprovals` / `visibleClarify`），归属不明的仍显示（否则永远没人处理）。
- **系统通知可带按钮**：审批通知改成右下角的**通知窗**，直接点「允许一次 / 拒绝」就地处理，
  不必先把窗口拉出来再找卡片（系统气泡不支持按钮）。纯提示（一轮完成等）仍走托盘气泡。
- **可选接入 Windows 原生通知（toast）**：设置 → 桌面 → 通知里多了「使用系统原生通知」开关，
  **默认关**（用应用内 M3 卡片，零系统痕迹）。打开后由 `nucleus.notification-windows` 发原生 toast
  （进通知中心、带按钮），并创建/更新开始菜单快捷方式 —— 这是 Windows 的 AUMID 归属硬要求
  （桌面、其它目录、纯注册表键都不行，四组对照见 `.design/详细设计/工程与代码/12-通知窗.md`）。
  快捷方式文件名与 AUMID 都带 `<home>` 哈希，多份便携包互不覆盖；快捷方式被删时设置页会显示
  原因并回退到自绘窗，不静默失效。
- **已存在的开始菜单快捷方式不再每次重写**：打开开关时先看 `<产品名> (<home 哈希>).lnk` 在不在，
  在就沿用（`REQUIRE_NO_CREATE`，实测 mtime 前后一致），不在才创建。判据只认文件名 ——
  名字里带 `<home>` 哈希，同名即同一实例。快捷方式的 target 由库取**当前进程的 exe**
  （`GetModuleFileNameW`），没有写死路径：成品指向自己的 `hermes-desktop-compose.exe`，
  开发态指向跑它的那个 `java.exe`。

- **打开原生通知后任务栏图标变成 JVM 咖啡杯**：`initialize` 会改进程的 AUMID，Windows 随后按它找图标，而该 AUMID 没注册图标就会退回 `java.exe` 的图标。现在打开时注册 `HKCU\Software\Classes\AppUserModelId\<aumid>` 的 `DisplayName` / `IconUri` / `ShowInSettings`，关掉开关时连同开始菜单快捷方式一起删掉。
- **运行时基线从 JBR 25 换成 Temurin 27**（`local.properties` 的 `hermes.rt.dir`）。
  根因：JBR 有意把触摸事件合成为**滚轮**（实测同一份代码 Temurin 是 41 个 `Move`、0 个 `Scroll`，
  JBR 是 0 个 `Press`、23 个 `Scroll`），Compose 的 `scrollable` 只认拖动、滚轮又跳行，
  怎么调都「不跟手」。换官方 JDK 后触摸变成正常的 press/move，全部 native workaround 删除
  （`:modules:win-touch`、`TouchScrollRegistry`、`WM_TOUCH` 子类化都不再需要）。
  代价：JBR 自带的中文字体观感与 JCEF 需要自己补（见下两条）。详见
  `.design/详细设计/工程与代码/10-Windows触屏输入.md`。

### 修复

- **触屏无法滑动会话列表**：Compose Desktop 在 Windows 上把触屏合成为鼠标拖动，而
  `LazyColumn` 的 `scrollable` 主动拒绝鼠标拖动（`CanDragCalculation = { type != Mouse }`），
  手指滑动完全无效；同时吸底只认滚轮事件，`followTail` 恒为 `true`，列表被不断拽到底部
  （表现为「迅速翻很多行然后不动」）。新增 `Modifier.touchDragScroll`（`draggable` +
  `dispatchRawDelta`，**不**用 `startDragImmediately`，否则会吞掉点击与长按），
  拖动即暂停跟随，发新消息时恢复。
- **审批 / 提问请求在别的会话或窗口不前台时没有提示**：`approval` 与 `clarify` 到达时判断
  用户是否正看着该会话；没看着就弹系统通知（含重连后 `approval.pending` 补拉），同一请求只提醒
  一次；**点击通知或托盘图标直接跳到发起它的会话**（跨 profile 也会等列表就绪后选中）。
- **`clarify` 工具无法使用**：之前未注册 `clarify` handler，后端只能收到 `-32601`。
  现在弹出可作答卡片（单选 / 多选 / 自由输入 / 批量），支持取消，并处理 `request.cancel` 撤回。
- **切到别的会话再切回来，回复停在半截、看起来卡死**：切走时保存会话快照（含流式段落），
  切回先恢复再判断；切走期间有新内容会在切回时补拉完整 transcript，若那轮已结束则静默重读。
  **切走期间的事件现在继续累积到该会话的快照上**（影子状态）：只标记"脏"再等补拉不够 ——
  切回时那轮往往还在跑（审批就是在等它），补拉会被跳过，而丢弃的增量再也补不回来，
  界面就停在切走前那一帧（用户实测必须点刷新）。
- **界面产品名统一为 `Hermes Desktop Compose`**：窗口标题、托盘提示、系统通知标题、
  「已在运行」提示框、关于页都改用同一常量（`core/app/ProductInfo.kt`），
  空态文案里的产品名也用占位符取它。裸的 `Hermes` 保留给后端 agent / payload 进程
  （消息里的助手署名、给 Hermes 发消息、连不上后端）。
- **paper 主题的文字太淡**：正文 `#5A524A`（7.4:1）→ `#2A2521`（14.8:1），
  次要文字 `#9A9080`（**3.0:1**）→ `#6A6055`（6.0:1）；暗色版同步加深。
  次要文字用在分组标题、时间戳、输入框提示、skill 说明这些地方，之前低于 WCAG AA 的 4.5:1。
- **纸面 Markdown 跟着变淡**：`markdown_style = paper`（或 `follow` + paper 风格）改用宿主色板，
  排版仍是 Outside Paper（标题小一档、行高 1.6），只是正文/次要文字取加深后的色值；
  `follow` + 其他风格时 Markdown 直接跟随当前 M3 色板。
- 对比度门槛固化为单测 `AppPaletteContrastTest`（正文 ≥ 11:1、次要 ≥ 4.5:1），改色先过它。
- **消息区两侧留白过窄，触屏容易误选文本**：原来固定 `horizontal = 20.dp`，手指落在文字上会被
  `SelectionContainer` 接走变成**选中**而不是滚动。改成随窗口宽度自适应（取可用宽度的 6%，
  夹在 24–96 dp），留白区仍属于外层 `touchDragScroll` 的拖动区域，滑动行为不变。
- **打开原生通知后任务栏多出一个图标**：`initialize` 会把**进程**的 AUMID 换成通知用的那个，
  而任务栏按 AUMID 分组，于是同一个应用变成「固定项 + 运行窗口」两项。toast 靠显式 AUMID 发送、
  不依赖进程这个值，所以初始化后立刻用 `SetCurrentProcessExplicitAppUserModelID("")` 清掉
  （传 `NULL` 会返回 `E_INVALIDARG`，Windows 只认空串）。
- **运行中删掉开始菜单快捷方式后通知静默消失**：库的 `show` 在 `.lnk` 缺失时不报错也不弹，
  而 `ready` 仍是 `true`，于是既不提醒也不回退。现在 `show` 先自查快捷方式，缺失就置为不可用
  （设置页显示原因）并回退到应用内通知窗。重新打开开关会重建快捷方式。
- **`createDistributable` / MSI 装出来的应用一启动就 `NoClassDefFoundError: javax/naming/NamingException`**：
  compose 插件默认只按 jdeps 静态分析带 7 个模块，缺 `java.naming`。给 `nativeDistributions`
  显式声明 `modules(...)`（与绿色版 `rt/` 同一套 16 个模块）。绿色版本来就有，只在 MSI /
  本地 `createDistributable` 上暴露。

---

## [0.3.2] · 2026-11-02

> 本版是 **patch 发布**：只提供 `bin/App.jar`，覆盖旧文件后重启客户端即可，
> 配置与数据目录不受影响。

### 修复

- **开机自启会弹出主窗口**：系统里可能同时存在两条本产品的自启任务 —— 早期版本的固定名
  `HermesCompose`（`Task version="1.3"`，**没有** `--silent`）与现行的 `HermesCompose-<home hash>`。
  名字对不上，谁也不会去清理，于是旧任务先拉起一个**带窗口**的实例（还白弹一次 UAC），
  带 `--silent` 的新任务晚 20 秒起来后被单实例挡掉、静默退出。
  现在对账同时校验 `<Command>` 与 `<Arguments>`（之前只比路径，无参任务永远被判为健康、从不重写），
  并在已提权时删掉指向**同一个 exe** 的冗余任务。路径比对仍是必须项：指向别处 exe 的任务
  可能是另一份正在用的安装，一律不碰；写当前任务失败时不删任何东西。
- **设置页的「失效自启任务」列表**也认早期固定名，便携包挪位后的残留不再漏列。

### 升级

- 覆盖 `bin/App.jar` 后重启客户端。
- 若系统里还留着 `\HermesCompose`（查看：`schtasks /Query /TN "\HermesCompose"`），
  新版第一次**提权**启动会自动删掉它；也可以手动
  `schtasks /Delete /TN "\HermesCompose" /F`（需管理员）。删完可确认只剩
  `HermesCompose-<hash>`，且它的 `Arguments` 是 `--silent`。

---

## [0.3.1] · 2026-11-01

> 本版是 **patch 发布**：不重新打包完整安装包，只提供可直接覆盖升级的文件
> （`bin/App.jar` 与一个模块 jar）。已在 0.3.0 上的用户按 Release 说明替换目录里的文件即可，
> 配置与数据目录不受影响。

### 新增

- **本地后端的监听地址 / 端口 / 凭据可配置**（设置 → 后端连接，沿用现有设置项，改完重启生效）：
  `backend.local.host`（默认 `127.0.0.1`）、`backend.local.port`（默认 `0` = OS 分配）、
  `backend.local.token`（默认空 = 每次启动随机；填了就跨重启固定）。
  固定端口 + 固定 token 后，别的机器可以 `ssh -L` 或 Tailscale 隧道过来、用 `remote` 模式连；
  浏览器也能直接开同一个端口。注意 `0.0.0.0` 会让后端进入 gated 模式（要求口令/OAuth），
  本客户端只发 session token，那条路连不上 —— 官方建议就是「回环 + 隧道」。

- **思考过程按 Markdown 渲染（仅限已写完的段）**：流式期间的 `reasoning.delta` 仍是纯文本
  （逐 token 重解析会把 CPU 峰值翻倍，而且折叠态看不见内容），一轮结束后落地的
  `ChatItem.Reasoning` 用 `MarkdownText` 渲染，代码块 / 列表 / 表格不再显示裸语法。
  展开态同时补上限高（320dp）与内部滚动 —— 之前的注释承诺了限高但代码里没有。

- **后台任务的显示与操作**：composer 上方新增状态条，`process.list` 每 5s 轮询本会话的后台进程
  （`terminal(background=true)` 起的命令），显示命令、运行时长 / 退出码，可就地点开输出
  （`agent.terminal.output` 增量实时追加）、用 `process.kill` 终止单个进程、或隐藏已结束的行。
  终止刻意不走 `process.stop` —— 那是 registry 全局 kill_all，会连别的会话的进程一起杀。

### 修复

- **用量条（上下文占用 / token 消耗）重新可见**：两个原因叠在一起 ——
  ① `session.usage` 之前只在轮结束时刷新，而 `session.usage` 需要 runtime id，打开历史会话根本不会
  `session.resume`，所以顶栏那条一直是空的（切回已 resume 的会话也不会补）。现在打开会话后在后台补一次
  resume，并同步刷新用量、审批与后台任务（切走期间会用 key 校验丢弃过期结果）。
  ② 采集到的累计 token（`totalTokens` / `calls`）从来没进过界面：现在标签显示
  `已用 / 上限（百分比） · 累计 X`，点开的明细里再给 `累计 X tokens · N 次调用`。

- **PTY 初始窗口尺寸**：spawn 时补上 `setInitialColumns` / `setInitialRows`（未布局时默认 120×30，
  resize 钳制到 ≥40×10）。子进程读的是**创建时**的窗口宽度，PowerShell 7 启动横幅按它算
  `PadRight(width - N)`，宽度异常时会拿到负数并直接 FailFast 崩掉整个 shell。

- **会话列表闪烁**：`session.list` 刷新时不再无条件置 `Loading`（那会让 `sessions` 变空、
  整片列表被换成骨架行）。`sessions.changed` 在流式期间会连推多条，叠加 400ms 去抖后
  就成了"列表不停闪"。现在只有首次加载 / 切 profile 才显示骨架，同 profile 的刷新是静默替换，
  失败时保留旧列表 + 错误条。

- **流式分段顺序**：思考 / 工具 / 正文改为按到达顺序排列的有序段落
  （`StreamingState.segments` + `StreamSegment`）。此前分字段堆放，一轮里混了工具调用时
  会变成"所有思考在最上面、所有正文在中间、工具全在最后"，要切走再切回来才显示正确；
  `message.interim` 现在会封段，`message.complete` 只替换尾部未封段的正文，不再整轮覆盖。
- **审批应答恢复**：连接建立后补发 `client.capabilities {server_requests:true}` ——
  缺这一步后端认为本端不支持 server→client 请求，审批请求根本不会发出，
  危险命令（如 `Get-CimInstance`）直接被撤回并判 BLOCKED。同时补上
  `approval.received`（卡片上屏确认）、`approval.pending`（重连补拉）、
  `approval.respond`（补拉卡片的应答路径）与 `request.cancel`（撤回时移除卡片）。
  本端兜底超时从 240s 提到 330s（后端是 300s）。

---

## [0.3.0] · 2026-11-01（待发布）

> 本节可直接作为 GitHub Release 正文使用（去掉本行，标题层级按需调整）。
> 一句话：**终端换成 Compose 原生渲染，对话主链路补上「看得全 / 控得住 / 看得见」**。

### 重点

- **终端渲染重写**：终端从 JediTerm + `SwingPanel` 换成自维护的 BossTerm 裁剪版，改为 Compose Canvas 原生绘制。
  Swing 互操作带来的 heavyweight 遮挡（弹框被盖住）、IME 组合态失控、焦点跨边界三类问题随之消失，
  中文输入的组合态在 Compose 侧接管。
- **终端默认 shell 改为 `pwsh`**：装了 PowerShell 7 就用 7；没装按 `powershell`（5.1）→ `cmd` 逐档降级。
- **对话深度**：历史分页、导出、分支 / 复制、生成中纠偏（steer）、重发 / 回退、子代理实时面板、上下文用量条、会话内容搜索。
- **桌面集成**：托盘气泡通知、后端更新检查、关于页、`Ctrl+K` 命令面板、链接走外部浏览器。

### 新增

**对话**

- 历史分页：向上滚动按需加载更早的消息，插入时用锚点补偿，视口不跳位。
- 导出会话为 **JSON** 与 **Markdown** 两种格式（含全量消息，文件名按标题清洗后落地）。
- **复制为新会话**（整份克隆）与**从某条消息分支**（截到该条）两种语义分开。
- **生成中纠偏**：回答还在输出时直接说话会走 `session.steer`，不再必须停下来等。
- **忙时直接发第二条**：后端排队，发送按钮在生成中不再禁用。
- **重发 / 回退到某条消息**：破坏性操作，界面二次确认。
- **上下文用量条**：状态条显示当前上下文占用，接近上限时有提示；明细按需拉取。
- **推理块折叠**：思考过程默认收起，不占主视野。
- **子代理面板**：roster + 实时 tail 流，可直接 steer / interrupt。
- **会话内容搜索**：命中行显示后端给的标题与片段，与标题过滤是两个独立模式。
- 消息里的链接点击走系统外部浏览器。

**桌面与设置**

- **托盘气泡通知**：轮次完成 / 失败时提示（仅在窗口非前台且开关打开时发），点击回到对应会话。
- **命令面板**：`Ctrl+K`。
- **关于页与更新检查**：`/api/hermes/update/check?force=true`，区分「有新版本 / 已是最新 / 检查失败」。
  ⚠️ 这是**后端**更新检查，不是客户端自身更新。
- 设置补页：通知 / 快捷键 / 关于。
- 终端「选中加入对话」：选中文本可直接引用进输入框。

### 变更

- **审批 / 提问只显示在发起它的会话里**：后端是"全局在等"，但界面之前把所有待处理请求都画在当前
  会话，切到别的对话也会看到别人的审批，容易误当成自己要处理的事情。现在按会话过滤
  （`ChatState.visibleApprovals` / `visibleClarify`），归属不明的仍显示（否则永远没人处理）。
- **系统通知可带按钮**：审批通知改成右下角的**通知窗**，直接点「允许一次 / 拒绝」就地处理，
  不必先把窗口拉出来再找卡片（系统气泡不支持按钮）。纯提示（一轮完成等）仍走托盘气泡。
- **可选接入 Windows 原生通知（toast）**：设置 → 桌面 → 通知里多了「使用系统原生通知」开关，
  **默认关**（用应用内 M3 卡片，零系统痕迹）。打开后由 `nucleus.notification-windows` 发原生 toast
  （进通知中心、带按钮），并创建/更新开始菜单快捷方式 —— 这是 Windows 的 AUMID 归属硬要求
  （桌面、其它目录、纯注册表键都不行，四组对照见 `.design/详细设计/工程与代码/12-通知窗.md`）。
  快捷方式文件名与 AUMID 都带 `<home>` 哈希，多份便携包互不覆盖；快捷方式被删时设置页会显示
  原因并回退到自绘窗，不静默失效。

- **终端渲染实现**：JediTerm 3.76 + Pty4J + `SwingPanel` → BossTerm 裁剪版（Compose 原生绘制）。
  读屏改走 BossTerm 的导出接口（`snapshot` / `tailText` / `selectedText`），`terminal.read` 字段保持不变。
- **终端默认 shell**：原来固定 `powershell.exe`，现在按 `pwsh.exe` → `powershell.exe` → `cmd.exe` 探测降级。
- **终端默认字体**：字体名留空时回落 BossTerm 自带的 MesloLGS Nerd Font，
  oh-my-posh 之类提示符里的 Nerd 图标私有区字形不再显示成方块；填了系统字体名就用系统的，缺字形由 Skia 回落。
- **终端配置位置**：BossTerm 的存储根从 `~/.bossterm` 改到 `%LOCALAPPDATA%\hermes-compose\terminal`，
  终端外观设置本身由 `config/state.json` 注入，不再读写 `settings.json`。
- **弹框不再让位**：删掉「终端展开时先收起面板再弹框」的绕法（Swing heavyweight 遮挡已不存在）。
- **Markdown 渲染**：渲染实现整体迁到 `modules/markdown-one`（submodule），移除 mikepenz 渲染器的直接依赖；
  公式引擎换 latex-renderer；嵌套块走适配器链；Markdown 主题改为独立设置。

### 修复

- 点终端「+」新建标签后一片空白（标签对账的 `LaunchedEffect` key 漏了 `tabs`，新标签从未起 PTY）。
- 终端字体设置改了没反应（渲染路径没读 `fontName`），顺带修好 Nerd 图标显示成方块。
- 终端中文输入：用 `TextFieldValue.composition` 区分组合态与提交，同一次提交去重；
  切换输入法的快捷键从被微软拼音抢占的 `Ctrl+Space` 改为 `Ctrl+Alt+I`。
- 自适应布局：选区开关下沉到 `MarkdownText`，修自研渲染层下选区功能失效。
- 更正行内公式的解析器版本口径（markdown 0.7.17）。

### 内部与性能

- 终端不再有 `hermes.terminal` 构建开关：`modules/terminal`（宿主）+ `modules/terminal-bossterm`（git submodule + composite build）始终参与构建。
- 删除 JediTerm / Pty4J 依赖，终端相关依赖全部收敛在 `modules/terminal` 内。
- 删除 `modules/ui-markdown` 门面模块，Markdown 主题设置独立（ADR-053）。
- 字体解析（含 bundled 字体落盘）从组合线程挪到 IO 线程，首帧先用平台等宽字体占位，避免第一次进终端卡顿。
- 终端列数 / 存活进程数改 1s 轮询 + 去重，不再依赖缓冲区回调。
- `modules/markdown-one` 多次跟随升级（`00fca0b` → `8c250c5` → `707f3c8` → `ad5ef40`）。
- BossTerm 改为自维护 fork（Apache-2.0），只保留终端核心子集，
  不再依赖上游的整应用打包（Android target / ktor-server / supabase / MCP SDK / zxing / jna 均未引入）。
- 决策与口径同步：新增 ADR-054（终端改 Compose 原生渲染，取代 ADR-040）；
  全仓库文档把「JediTerm + SwingPanel」的旧口径改为实现现状（`.design/` 的选型、依赖、工程结构、终端域篇）。

### 已知缺口（本版明确没做）

| 缺口 | 说明 |
|---|---|
| **附件图片缩略** | composer 附加的图片只作为下一轮模型输入，历史行里没有稳定的图片引用可渲染；Markdown 里的 `![](url)` 正常显示 |
| **终端文本回放** | 重启只恢复标签 / cwd / 展开态与高度，**不复活进程**，因此不回放历史输出 |
| **文件拖入终端** | BossTerm 留了钩子但未接线，拖文件到终端当前无反应（链接点击、右键菜单、选中加入对话均已可用） |
| **回看行数只对新标签生效** | 改「滚动回看行数」不会影响已经打开的标签，需新开标签才生效（字号 / 字体 / 配色是实时生效的） |
| **忙碌队列可见列表** | 后端自己排队（`display.busy_input_mode`），没有查询队列的 RPC，客户端只显示「已发送」 |
| **客户端自更新** | 关于页的更新检查针对后端；绿色版 zip 自更新仍未做 |

### 升级注意

1. **终端不再是 Swing 组件**：如果之前用过旧版终端，`~/.bossterm` 目录不会再被创建 / 读取，
   可以手动删除。终端相关文件现在在 `%LOCALAPPDATA%\hermes-compose\terminal`；
   终端外观设置（字体 / 字号 / 回看行数 / 配色）在 `config/state.json` 里，
   从旧版升上来的外观设置会被沿用，但**回看行数**只在新建标签时生效（见上表）。
2. **默认 shell 变了**：机器上装了 PowerShell 7 的话，终端默认起的是 `pwsh` 而不是 `powershell.exe`（5.1）。
   依赖 5.1 行为的脚本，请在终端里手动 `powershell.exe` 或调整配置。
3. **产物改名**：可执行文件名从 `Hermes.exe` 改为 **`hermes-desktop-compose.exe`**（配置在
   `app\hermes-desktop-compose.cfg`）。资产名相应变成
   `hermes-desktop-compose-0.3.0-win32-x64-{local,remote}.7z.001`；`local` / `remote` 两个变体、
   `.7z.001` 分卷、`SHA256SUMS.txt` 的形态不变。
   ⚠️ 仓库里 `HermesCompose-<hash>` 这个前缀（单实例互斥量 / 自启动计划任务名）**没有改**，
   它和产物名不是一回事；用户数据目录仍是 `%LOCALAPPDATA%\hermes-compose`。
4. **payload 未变**：本版的 `local` 变体内置的上游 payload 仍是 `v0.22.5`，
   终端与对话的改动全部在客户端壳里。两种变体的选择方式与旧版一致
   （`config/config.json` 的 `backend.mode`）。

---

## [0.2.0] · 2026-10-31

终端面板（`modules/terminal`）、设置中心分组（配置档案 / 模型 / 安全 / 提供方 / 调试）、技能发现、
会话行操作与实时刷新、消息引用、全局快捷键与终端标签重命名；
Markdown 渲染改用 `modules/markdown-one`（git submodule）。

## [0.1.0] · 2026-10-29

首个可运行版本：Compose 桌面客户端外壳 + 内置 Hermes 本地后端（sealed payload）与远程模式、
jlink 裁剪运行时、原生启动器打包、单实例与自启任务清理。

---

## 版本号说明

仓库里只有两套数字，含义不同，改版本前先分清：

| 数字 | 在哪 | 谁生成 | 谁消费 |
|---|---|---|---|
| `appVersion = "0.4.2"` | `composeApp/build.gradle.kts` | 手工 | jpackage 的 `packageVersion` / `-Djpackage.app-version`、启动器 cfg、发布资产文件名 |
| `v<桌面版本>-<hermes版本>` | GitHub Release tag | `desktop-release` 按输入拼 | 发布资产入口、`gh release download` |

**tag 就是唯一版本号**，形如 `v0.4.0-0.22.5`：前半是桌面端版本（对应 `appVersion`），
后半是配套的 Hermes payload 版本（`Shinglem/hermes-bundle` 的 tag）。
`appVersion` 只影响 exe 的属性与 cfg，不回写 tag。

工具链（启动器 + 裁剪运行时）在同一个 Release 里，tag 是 `toolchain-v<N>`
（`v1`、`v2` ……**只增不改**），和桌面版本完全解耦：换一次 JDK / 改一次模块列表才需要 +1。

| Release | tag 形式 | 资产 |
|---|---|---|
| 桌面端 | `v0.5.3-0.23.0` | `hermes-desktop-compose-0.5.3-0.23.0-windows-x64.7z.001`（+ 后续卷）+ `SHA256SUMS.txt` |
| 工具链 | `toolchain-v1` | `launcher-windows-x64.zip` / `runtime-windows-x64.zip` / `SHA256SUMS.txt` |
| payload | 另一个仓库 `Shinglem/hermes-bundle` 的 `v0.23.0` | payload 压缩分卷 |

**发布约定（2026-11-07 起）**：**所有版本都发全量包**，由 CI `desktop-release` 构建
（`workflow_dispatch`，tag 由 CI 按 `v<desktop>-<hermes>` 生成）。全量包是**纯桌面外壳**，
不含 payload —— 体量与构建时间都很小，用户首次使用自己补一份 payload（或复用安装目录里已有的 `hermes/`）。
同一位数内的小修仍然只 +0.0.1（例如 0.5.1 → 0.5.2），但同样走全量发版。

> 历史：0.3.1 ~ 0.5.0 期间小版本走的是「只上传替换文件」的 patch zip（`hermes-desktop-compose-<版本>-patch.zip`），
> 现已废止；那些 Release 资产仍可下载，但不再新发这种形状的包。
