# 06 · 分期计划

> 每一期都有**退出条件**（能演示的东西），不做"完成了 80%"这种表述。
> 估时为单人，含自测，不含返工。

---

## P0 · 骨架与连接（10 天）

**目标**：窗口能起来，连上后端，拿到真实数据。

交付：

- Gradle 工程骨架（`composeApp`、`libs.versions.toml`、打包配置）
- `GatewayClient`：WebSocket + JSON-RPC 2.0 + 心跳 + 重连 + 事件分发（不含 server→client 请求）
- `RestClient`：HTTP + token
- 契约生成器（首版，只生成 sessions/profiles 两个域）
- 三栏空壳布局（rail + list + detail + 状态条），数据写死
- 连接状态显示在状态条

退出条件：

1. 启动后状态条显示"已连接"；
2. 断开后端 → 1 秒内变红并自动重连；
3. 中栏能列出真实会话标题（哪怕还不能打开）。

---

## P1 · 会话与只读对话（12 天）

**目标**：能点开一个会话，看到历史消息（还不能发）。

交付：

- 会话列表（置顶 / 今天-昨天-7天-更早 / 折叠 / 搜索）
- 消息列表（`LazyColumn` + 单条 `Column`）
- Markdown 渲染接入（`multiplatform-markdown-renderer`）+ 代码高亮（Highlights）
- 工具调用通用卡片
- 逐条"复制 / 引用"按钮
- profile rail 与切换

退出条件：

1. 打开 500 条消息的会话不卡；滚动稳定；
2. 代码块有高亮、能复制；
3. 切换 profile 后列表刷新正确，切回不重新加载；
4. 未闭合 Markdown 不闪烁。

---

## P2 · 发送与流式（8 天）

**目标**：能提问、能看到流式回答。

交付：

- `prompt.submit`（1800s 超时）+ 事件流渲染
- 流式增量按帧合并（16–32ms）
- 停/重试、错误提示
- server→client 请求的路由与应答（审批/澄清卡片的最小可用版）

退出条件：

1. 1,000 token 回答全程不重排、不抖动；
2. 滚动到底部吸底，上滚时暂停跟随；
3. 后端重启后能继续发送；
4. 审批卡片能应答一次，重复到达无副作用。

---

## P3 · composer 完整（16 天）

**目标**：输入体验达到可用。

交付：

- `BasicTextField` + 自动高度 + Enter/Shift+Enter
- chip（`InlineTextContent`）+ 序列化/回填
- @ 补全浮层（键盘导航）
- 粘贴图片 / 文件拖入
- 输入历史、草稿
- 中文 IME 全部场景验证（P2 已加 `composition != null` 时不吞 Enter，
  见 `feature/composer/Composer.kt`；仍需在真实输入法下实测）

退出条件：

1. 中文输入法全流程无异常（组合态、候选框、上屏）；
2. 组合态下按 Enter 是上屏而不是发送；
3. chip 可插入、可整块删除、序列化后一致；
4. 切换会话草稿保留，重启仍在。

---

## P4 · 能力页（9 天）

**目标**：技能、消息平台、定时任务能在客户端里改。

交付：

- 能力模式（中栏分类 + 右栏设置页）
- 技能与工具页（列表 / 开关 / 详情）
- 消息平台页（状态 / 配置表单 / 测试连接）
- 定时任务页（列表 / 新建 / 编辑 / 立即运行 / 历史）

退出条件：

1. 三类配置改完能在后端生效（用 CLI 或官方客户端回读验证）；
2. 错误路径有可读提示；
3. 能力 ↔ 会话切换不丢会话状态。

---

## P5 · 终端（6 天）

**目标**：应用内能开一个真正可用的 shell。

交付：

- BossTerm 接入（JitPack 锁 tag）
- 折叠面板（收起 32dp / 展开 40% / 可拖）
- 焦点与快捷键隔离
- 配色映射、字体选择

退出条件：见 `05-acceptance.md` §5 的 T1–T8 全部通过。

---

## P6 · 打包与发布（5 天）

**目标**：能装到别的机器上用。

交付：

- **绿色版安装树**（`rt/` + `hermes/` + `config/` + `lib/` + `bin/App.jar` + `hermes-desktop-compose.exe`）；
  结构与配置模型见 [`详细设计/工程与代码/05-打包与配置.md`](../详细设计/工程与代码/05-打包与配置.md)，
  后端拉起见 [`详细设计/工程与代码/06-本地后端与双模式.md`](../详细设计/工程与代码/06-本地后端与双模式.md)，
  取舍见 ADR-026 / ADR-027 / ADR-029 / ADR-030
- **Local / Remote 双模式**（**本期只做 Remote**）：Local 用内嵌 sealed payload 起
  `hermes serve` 子进程，Remote 连已有后端；两者对上层透明。Local 因 payload 5.8 GB 推迟，
  设计已备齐（见 06 篇）
- MSI/exe 安装版（在绿色版基础上加壳，两者并行）
- 图标、版本号
- 极简自动更新（查版本 → 下载 → 校验 → 提示）；绿色版按"下载 zip + 解压覆盖 + 保留 `config/`"做
- 日志落盘 + 设置页入口
- 首次启动引导（填后端地址 / token；绿色版可由 `config.json` 模板承担一半）

> 打包结构可以**提前到 P5 之前做**（不依赖设置页），收益是尽早拿到可双击运行的产物；
> 05-打包与配置.md §11 给了 S1–S6 的切分。

退出条件：见 `05-acceptance.md` §7 的 P1–P5。

---

## 总计

| 期 | 天 |
|---|---:|
| P0 | 10 |
| P1 | 12 |
| P2 | 8 |
| P3 | 16 |
| P4 | 9 |
| P5 | 6 |
| P6 | 5 |
| **合计** | **66 天** |

> 另加 `01-feasibility.md` 里的其他项（设置页 4 天、数据层剩余部分等）后总计约 108 人天 ≈ 5 人月。
> 建议按 +30% 缓冲排期 ≈ 7 人月。

---

## 里程碑门槛（不是时间门槛，是能力门槛）

| 里程碑 | 门槛 | 未达则 |
|---|---|---|
| M1 可日常用（替代官方客户端看会话） | P0 + P1 通过，连续用 3 天不出阻断问题 | 停下来修，不往下推 |
| M2 可替代（能完整对话） | + P2 + P3 | 同上 |
| M3 可持续（配置与终端齐备） | + P4 + P5 | 同上 |
| M4 可分发 | + P6 | — |

**每个里程碑都要有"连续使用 N 天"的观察期**，这是唯一能发现手感问题的方式。

---

## v0.2.0 · 终端 + 设置中心（里程碑；终端遗留项结转）

> 详细计划见会话计划文件；这里只记与本 roadmap 的对应关系。

**已吸收 P5（终端）**，并把设置页扩成「设置中心」。

| 任务组 | 内容 | 状态 |
|---|---|---|
| M0 模块化 | `modules/terminal`（宿主）+ `modules/terminal-bossterm`（BossTerm 裁剪 fork，submodule + composite build）、`modules/markdown-one`（渲染实现，ADR-052；原 `modules/ui-markdown` 门面已按 ADR-053 删除）；终端依赖**始终参与构建**，`hermes.terminal` 开关已移除（ADR-044） | ✅ 已完成 |
| T0 终端 spike | 先验 JediTerm 3.76 + SwingPanel 互操作（结论：死路）→ 改 BossTerm 源码裁剪 + Compose 原生渲染（ADR-054）；PTY 通路、真彩色、中文 IME 实测；JediTerm 与 Pty4J 已随 ADR-054 删除，读屏改走 BossTerm 导出接口 | ✅ 已完成 |
| T1 终端骨架 | 收起条 32dp / 展开 40% / 拖拽高度 / 单标签可用 / `Ctrl+`` | ✅ 已完成 |
| T2 多标签 | rail、新建/切换/关闭、exit 自动收、其余快捷键、**双击标签重命名**（`ui.terminalLayout` 持久化） | ✅ 已完成 |
| T3 重启恢复 | 标签 + cwd + 展开态与高度已恢复（`preferences.terminalLayout`）；**文本回放未做**（进程本身不复活，回放一段死掉的输出没有实际价值） | 🚧 |
| T4 gateway | `terminal.read` / `agent.terminal.output` 镜像标签 / `terminal.resize`（可见列数变化时上报，best-effort） | ✅ 已完成 |
| T5 终端体验 | 设置中心「终端 → 外观」组（字体 / 字号 / 配色实时生效 ✅；**滚动回看行数只对新开标签生效** —— `bufferMaxLines` 在起 PTY 时定，改已有的不追溯）、切/新建标签后焦点落回终端 ✅、链接 Ctrl+Click、右键菜单、选中加入对话 ✅（BossTerm 自带 + `terminalSelectedText`）、默认 shell 自动降级 `pwsh → powershell 5.1 → cmd` ✅、终端配置根落 `%LOCALAPPDATA%\hermes-compose\terminal`（不再创建 `~/.bossterm`）✅；**剩余**：文件拖放未接线 | 🚧 |
| T6 打包 | 体积核算、release-test 实测 | ⬜ |
| C7 技能发现页 | 技能与工具页加「发现」分段：`/api/skills/hub/official` 目录 + 分类筛选 + 详情 + 安装（ADR-049） | ✅ 已完成 |
| SE1 设置页骨架 | 左栏两级分组（选项 / 终端 / 提供方）、统一 `NavRow`、改用 `AdaptiveListDetail`、rail 能力入口语义修正（ADR-046~048） | ✅ 已完成 |
| SE4a 提供方密钥 | 设置 → 提供方 → 密钥：`/api/env` 按 provider 分组、搜索、逐个填值保存（写 `.env` 并回读；明文不落客户端） | ✅ 已完成 |
| SE1b 配置档案 | 设置 → 配置档案 → 档案：列表 + 详情 + SOUL.md 编辑 + 新建（可克隆）/ 重命名 / 删除 / 导入导出；写后回读 | ✅ 已完成 |
| SE2a 模型（主 + 辅助） | 主模型页（`/api/model/info` + 目录 + `POST /api/model/set` 含贵模型二次确认 + 上下文覆盖稀疏 diff）；辅助模型页（11 个任务槽位、设为当前模型、全部重置） | ✅ 已完成 |
| SE2b 模型（备用 + MoA） | 备用模型页：`fallback_providers` 读写（保持磁盘 dict / list 形状）；MoA 页：命名预设 + 参考模型组 + 聚合模型 + fanout / 失败披露 / 温度超时，保存提交整份预设表并先做半填自检（后端 422 不修复） | ✅ 已完成 |
| SE3 安全三页 | 审批 / 隐私与网络 / 检查点（共用 `SecurityFormPage`，点分路径 + 稀疏 patch，非法值不发） | ✅ 已完成 |
| SE4b 提供方自定义端点 | 列表 + 表单 + `/validate` 探测（探测出的 base 回填表单）+ 激活 / 删除；编辑不重填 key 时传 null 以保留密钥 | ✅ 已完成 |
| D 官方对照组 | payload 附官方 Electron 桌面端（debug-only，v1.0.0 移除）；发布说明与 Release 刷新、产物校验、启动器隔离 userData / HERMES_HOME + 输出落文件（ADR-045） | ✅ 已完成 |
| S9 会话行操作 | hover `⋯` → 重命名（REST PATCH + mutation fence）/ 置顶（本地偏好，`sessions.pinned`）/ 归档（RPC `session.archive` + 本地快照形成「已归档」恢复口）/ 删除（RPC，需先点开会话）；均回读校验 | ✅ 已完成 |
| S10 列表实时刷新 | 订阅 `sessions.changed`，400ms 去抖后重拉列表（新建中不刷，避免闪掉未落库的那条） | ✅ 已完成 |
| S11 失败留痕 | 实时失败（`message.complete(failed)`）与历史 `finish_reason` 异常（length/content_filter/incomplete）都在对话里补中文提示，不再只靠顶部横幅 | ✅ 已完成 |

**范围外**（本版不做）：提供方「账号 OAuth」与「本地模型」（**改排 v0.4**，见下节）、SSH 终端、外部终端启动器。

---

## v0.3.0 · 对话深度 + 桌面集成（已验收，待发布）

> 选题依据：与官方桌面端（`C:\ai\hermes\app\apps\desktop\src`，12 条路由 + 完整设置清单）逐域对照，
> 缺口集中在**对话主链路的深度**与**系统集成**两处，而不是缺页面。
> 本版不新增功能域，只把已有域做到「日常一整天不别扭」。

### 任务组

| 任务组 | 内容 | 后端接口（本次已核实） | 状态 |
|---|---|---|---|
| V1 看得全 | 历史分页（向上加载更早）；导出会话（JSON + Markdown）；复制为新会话；上下文用量条；链接点击走外部浏览器；推理块折叠 | `GET /api/sessions/{id}/messages?limit=&offset=&order=`；`session.branch_stored`；`session.usage`、`session.context_breakdown` | ✅ 除附件图（见下） |
| V2 控得住 | 生成中纠偏（steer）；忙碌时直接发第二条（后端排队）；重发 / 回退到某条消息；从某条消息分支；会话运行态点 | `session.steer`；`prompt.submit` + `truncate_before_message_id` + `confirm_truncate`；`session.branch`；`session.active_list` | ✅ |
| V3 看得见 | 子代理面板：roster + 实时 tail 流 + steer / interrupt | `subagent.list` / `tail` / `steer` / `interrupt`；事件 `subagent.spawn_requested` · `start` · `progress` · `thinking` · `tool` · `complete` | ✅ |
| V4 通知与更新 | 托盘气泡通知（完成 / 失败）+ 设置页开关；后端更新检查；关于页 | 托盘 `TrayIcon.displayMessage`；`GET /api/hermes/update/check?force=true` | ✅ |
| V5 捡漏 | 会话内容搜索；Ctrl+K 命令面板；设置补页（通知 / 快捷键 / 关于）；终端「选中加入对话」 | `GET /api/sessions/search?q=`；`terminalSelectedText` | ✅ 除终端 T3（见下） |

### 本版明确没做（别在文档里假装有）

| 缺口 | 原因 |
|---|---|
| 消息里的**附件图片**缩略显示 | composer 附加的图片走 `image.attach`，只作为下一轮的模型输入；历史行里没有稳定的图片引用可渲染。Markdown 里的 `![](url)` 由 markdown-one 的 Coil 适配器正常显示 |
| 终端 **T3 文本回放** | 进程不复活，"灌历史"只回放一段死掉的输出，没有实际价值。标签/cwd/展开态已恢复（T3 原已实现部分） |
| 终端 **文件拖放** | BossTerm 留了钩子但未接线；拖文件到终端当前无反应（链接点击、右键菜单、选中加入对话已可用） |
| 忙碌队列的**可见列表** | 后端自己排队（`display.busy_input_mode`），没有查询队列的 RPC；客户端只显示"已发送" |

### 关键口径（实现时踩过的坑，均由后端源码核实）

**1. 历史分页与全量读取**

- `GET /api/sessions/{id}/messages` 的 `limit` 后端钳到 500，`order=latest` + `offset` 是"从末尾往前翻"：
  `offset = 已加载行数` 才能接着往更早翻；**不要**和 `order=oldest` 混用（方向相反）。
- 向上插入时用「首项下标 + 偏移」做锚点补偿（`ChatPane` 的 `olderAnchor`），插入完成后
  `scrollToItem(anchor.first + 新增条数, anchor.offset)`，不要直接 `scrollToItem(0)`。
- 导出与分支都要「全量消息」：`ChatRepository.fetchAllMessages` 循环翻页到不足一页
  （上限 20000 条），导出 / 复制 / 分支三处共用。

**2. 上下文用量**

- `session.usage` 在**每轮结束后**刷一次（`message.complete` 触发）；`session.context_breakdown`
  只在点开用量条时拉。不要按帧刷新，也不要放进流式渲染路径。

**3. steering 与忙碌提交**

- `session.steer` 返回 `status=queued`；不支持 steer 的 agent 会报错，界面提示"先停止"。
- **发送按钮在生成中不再禁用**：后端 `display.busy_input_mode` 默认 `interrupt`，
  会依次尝试 redirect → steer → 中断+排队。
- `prompt.submit` 的 `queued=true` 只用于「客户端队列 drain」，普通忙时提交**不要**带。

**4. 重发 / 回退**

- 走 `prompt.submit` + `truncate_before_message_id` + `confirm_truncate=true`；
  截断后为空时再加 `confirm_empty_truncate=true`。
- 忙时返回 `4009 session busy`：先 `session.interrupt`，等这一轮收尾后再重提。
- 截断是**破坏性**的，界面必须二次确认；本地只做"乐观预览"，目标消息不在最近一页时
  不做假预览，交给后端截断后重载。

**5. 分支与复制**

- `session.branch`（`count=N` 截断克隆，**要求会话活跃**）、`session.branch_whole`（整段、不回传 messages）、
  `session.branch_stored`（存储态，父会话不必活跃）。
- `count` 按后端的**可见历史**口径算：只有 `user` / `assistant` 且有文本的行计入，
  tool 行与 hidden 行不算；不能拿 UI 条目下标。
- 语义区分：**复制** = `branch_stored`（整份克隆）；**分支** = `branch`（截到该条）。

**6. 导出**

- 没有后端导出端点，客户端拉全量后本地落文件。结构对齐官方：
  `{exported_at, session_id, title, session, message_count, messages}`（`session` 为 `null`）。
- 文件名 `<title>-<id 前 8 位>.<ext>`；清洗：小写、非 `[a-z0-9._-]` 折叠为 `-`、去首尾 `-`、截 48 字符；
  纯中文标题清洗后为空时退回 `session`。

**7. 子代理**

- 只吃事件流（`subagent.*`）+ 首屏 `subagent.list` + `subagent.tail`（最近 16 KB），**不要轮询列表**。
- 控制复用 `subagent.steer` / `subagent.interrupt`，禁止另建 steering 通道。

**8. 通知**

- 触发点：轮次完成/失败。只在**窗口非前台**（`AppFocus.isForeground == false`）且开关打开时发。
- 实现复用主窗口那份 `TrayIcon.displayMessage`（`SystemNotifications.attach` 由 `DesktopTray` 注册），
  再建第二个托盘图标会让通知区出现两个 Hermes。

**9. 更新与关于**

- `/api/hermes/update/check` 是**后端**更新，不是客户端自身更新。关于页写清这一点；
  绿色版 zip 自更新仍未做。

**10. 会话内容搜索**

- `GET /api/sessions/search?q=`，结果带 snippet；命中行标题用后端给的 `title`，
  **不要**拿 snippet 当标题（官方 #91068）。侧栏「搜消息」与标题过滤是两个模式，不叠加。

### 范围外（v0.3 不做）

- MCP 服务器管理（`/api/mcp/servers`）、本地模型（`/api/local-models/*`）、第三方账号 OAuth → **v0.4**。
- 插件宿主、文件树 / 编辑器 / diff、右栏预览、语音（转写/TTS/唤醒）、多 gateway / 多连接、
  多标签分屏、pet / HUD / starmap / learning / quick-entry、计费与订阅。
- 跨消息文本选择（硬约束 6，不修）。

### 退出条件

1. 2000 条消息的会话能一路翻到第一条，翻页过程中视口不跳位；导出 JSON 的 `message_count` 与实际条数一致。
2. 状态条能显示上下文占用；接近上限时有提示。
3. 生成中直接说话能纠偏且不打断当前轮；忙时连发两条按顺序执行。
4. 重发/回退有二次确认，失败后可恢复；分支出的新会话历史完整且可立即对话。
5. delegate 出去的子代理在面板里能看到实时 tail，并能 steer / interrupt。
6. 窗口最小化时任务完成能收到系统通知，点击回到对应会话。
7. 更新检查能区分「有新版本 / 已是最新 / 检查失败」，关于页信息可复制。
