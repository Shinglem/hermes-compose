# 08 · 数据契约

> 契约事实源：`C:\ai\hermes\app\tui_gateway\contracts\`（Python 单源）
> → `apps/shared/src/gateway-contract.generated.ts`（5,691 行，有防陈旧测试）。
> **本项目只读消费，不改契约。**

---

## 1. 端点

| 通道 | 地址 | 说明 |
|---|---|---|
| gateway | `ws://127.0.0.1:9119/...`（WebSocket，text 帧） | 会话、聊天、流式事件、server→client 请求 |
| REST | `http://127.0.0.1:9119/api/*` | 技能、工具、cron、配置、profiles、模型、MCP、消息平台 |

- **8642 不是桌面端接口**（那是 OpenAI 兼容 API + 多 profile multiplex，服务第三方客户端与 CLI）。
- `/api/status` 是唯一免鉴权端点；其余未带 token 返回 **401**。
- 远端部署时同一 base URL 换成 `https://` / `wss://`。

---

## 2. WebSocket JSON-RPC 2.0

### 2.1 帧格式

```jsonc
// client → server 请求
{"jsonrpc":"2.0","id":7,"method":"session.list","params":{}}

// server → client 响应
{"jsonrpc":"2.0","id":7,"result":{...}}
{"jsonrpc":"2.0","id":7,"error":{"code":-32601,"message":"..."}}

// server → client 通知（事件）
{"jsonrpc":"2.0","method":"event","params":{...}}

// server → client 请求（需要 client 应答）
{"jsonrpc":"2.0","id":"srq-6f1a2b3c4d5e","method":"approval","params":{"session_id":"...","request_id":"...","command":"...","choices":["once","session","always","deny"]}}
// client 应答（id 原样带回）
{"jsonrpc":"2.0","id":"srq-6f1a2b3c4d5e","result":{"choice":"once"}}
{"jsonrpc":"2.0","id":"srq-6f1a2b3c4d5e","error":{"code":-32603,"message":"..."}}
```

**注意**：server→client 请求的 `id` 与 client 请求的 `id` 是不同命名空间，
实现时 pending 表要按"方向 + id"分别管理（或给 client 的 id 加前缀）。

**能力声明（硬性前置）**：每条连接收到 `gateway.ready` 后必须发一次
`client.capabilities {"server_requests": true}`，否则后端认为本端不支持 server→client 请求，
这些请求**不会发出**（审批直接被撤回，命令判 BLOCKED）。
返回的 `server_requests` 是本后端可能发来的方法名列表。

### 2.2 方法清单（由 `RpcMethods` 导出）

按本期需要的域分组：

**会话**
`session.list` · `session.history` · `session.events.since` · `session.create` · `session.resume` ·
`session.activate` · `session.close` · `session.delete` · `session.archive` · `session.title` ·
`session.interrupt`（停止生成）· `session.usage` · `session.status` · `session.most_recent` ·
`session.active_list` · `session.context_breakdown` · `session.cwd.set`

**分支 / 复制 / 纠偏 / 会话控制**（v0.3.0 起用）

| 方法 | 入参要点 | 返回 / 语义 |
|---|---|---|
| `session.branch` | `session_id` + `count`（截前 N 条）+ 可选 `name` | **要求会话活跃**；返回 `{session_id, stored_session_id, title, parent, message_count, info, messages}` |
| `session.branch_whole` | 同上，忽略 `count` | 整段克隆，**不回传 `messages`**（`messages_omitted: true`） |
| `session.branch_stored` | `session.create` 的参数 + 父 `session_id`（`copy_parent_history`） | **父会话不必活跃**；整份历史克隆，原会话不动 |
| `session.steer` | `session_id` + `text` | 生成中纠偏；返回 `status=queued` |
| `session.redirect` | 同上 | 更强的活跃轮重定向；需 agent 支持 `_supports_active_turn_redirect`，不支持时报错 |
| `session.control.read` | `session_id` | goal / loop / heartbeat 快照 |
| `session.control` | `session_id` + `action`（`goal.*` / `loop.*` / `subgoal.*` / `heartbeat.*`） | 成功时推一条 `session.control.update` |
| `subagent.list` | `session_id` | 只读 roster（`subagent_id` `parent_id` `depth` `goal` `status` `tool_count` `last_tool` `accepting_steer` …） |
| `subagent.tail` | `session_id` + `subagent_id` | 最近 16 KB 输出尾 |
| `subagent.steer` / `subagent.interrupt` | 同上 | 控制子代理 |

**对话**

`prompt.submit`（**超时 1800s**）· `prompt.background` · `prompt.btw` · `request.answer`

> **`prompt.submit` 的两个易错口径**（v0.3.0 会用，来自后端 `methods_prompt.py`）：
>
> 1. **回退 / 重发 / 编辑**：带 `truncate_before_user_ordinal` / `truncate_before_row_id` /
>    `truncate_before_message_id`（三者互斥）。截断是破坏性操作，后端只信客户端显式声明；
>    忙时返回 `4009 session busy`，必须先 `session.interrupt` 再重提。
> 2. **忙碌提交**：普通忙时提交**不要**带 `queued=true`，那是「客户端队列 drain」专用；
>    后端按 `display.busy_input_mode` 自行 redirect → steer → 中断+排队。客户端不应在发送中禁用发送按钮。

**模型**

| 方法 | 入参要点 | 返回要点 |
|---|---|---|
| `model.options` | `refresh` / `explicit_only` / `include_unconfigured`（可空） | `providers[]`（`slug` `name` `models[]` `is_current` `authenticated` `featured_models[]` `capabilities{model:{fast,reasoning}}` `pricing{model:{input,output,free}}` `warning`）+ `model` `provider` |
| `config.set` | `key="model"`，`value="<model> --provider <slug>"`，`session_id` **必填**，`confirm_expensive_model` 见下 | `value` `warning` `confirm_required` `confirm_message` `scope` `deferred` |
| `config.set` | `key="reasoning"`，`value=<none\|minimal\|low\|medium\|high\|xhigh\|max>`，`scope="session"` | `value` |

> - `config.set model` **必须有活会话**，否则 4001；草稿态的选择存 `config/state.json`，由
>   `session.create` 的 `model` / `provider` / `reasoning_effort` 带过去（per-session override）。
> - 返回 `confirm_required=true` 表示目标模型较贵，需带 `confirm_expensive_model=true` 重发。
> - 正在生成时返回 `deferred=true`（排到下一轮开始），**不要立刻回读模型** —— 切成功后后端发 `session.info`。
> - 当前模型/推理强度的**唯一真相源**是 `session.info`（`model` / `provider` / `reasoning_effort`）
>   与 `session.resume` 返回的 `info`；不在 `config.set` 的返回里回读（ADR-038）。

**输入框（composer）**

| 方法 | 入参要点 | 返回要点 |
|---|---|---|
| `complete.path` | `word` = 光标前 token（含 `@`）；`cwd` / `session_id` 可选（不给 `cwd` 列不到真实文件） | `items[]`（`@diff` `@staged` `@file:` `@folder:` `@url:` `@git:` 与 agent profile 名） |
| `complete.slash` | `text` 为当前行；**必须活会话** | `items[]`（commands + skills）+ `replace_from`（**1-based** 替换起点） |
| `paste.collapse` | 大段文本 | 占位符 + 落盘 `path` + `lines`（原文由后端保存） |
| `file.attach` | `session_id` + `path`（需活会话） | `ref_text`（形如 `@file:<绝对路径>`） |
| `image.attach` | `path` | 已附加图片（id / 尺寸等） |
| `clipboard.paste` | 无参（取系统剪贴板图片） | 已附加图片。⚠️ **剪贴板无图时会话连接被 Aborted**，客户端必须先 `Clipboard.hasImage()` |
| `input.detect_drop` | 纯文本 | `matched` / `is_image` / `path` / `text`：判断粘贴内容是否为拖放路径 |

> 补全的替换区间：`start` 用后端给的 `replace_from`（slash）或 token 起点（path），
> **`end` 必须在接受时刻取当前光标**（ADR-020）。

**profile**
`profiles.list` · `profiles.describe` · `profiles.create` · `profiles.configure` ·
`profiles.get_asset` · `profiles.set_asset`

**连接能力 / 审批**
`client.capabilities`（每条连接一次，见 §2.1）· `approval.pending` · `approval.received` · `approval.respond`

**后台任务**
`process.list`（本会话拥有的后台进程快照，行里的 `session_id` 是**进程 id**）· `process.kill`（单个，
后端按 `session_key` 校验归属）· `process.stop`（registry 全局 kill_all，桌面端不用）

**技能 / 工具**
`skills.manage` · `skills.reload` · `tools.list` · `tools.configure` · `tools.show` · `toolsets.list`

**其他（本期不接）**
`plugins.*` · `projects.*` · `rollback.*` · `vault.*` · `wake.*` · `voice.*` · `subagent.*` ·
`spawn_tree.*` · `usage.*` · `subscription.*` · `local-models`（走 REST）· `mcp`（走 REST）

> 完整清单见生成契约的 `RpcMethods`（约 120 个方法）。**cron 与消息平台不在 RPC 里** —— 它们走 REST。

### 2.3 server → client 请求（必须处理或明确拒绝）

`terminal.read` · `terminal.resize` · `vault.code` · `vault.save_login` · `vault.unlock_prompt` ·
`window.read` · `preview.act` · `preview.read` · `display.install.sudo` · **`approval`**

**规则**：

- 必须先发 `client.capabilities {server_requests: true}`（见 §2.1），否则这些请求根本不会发来。
- 必须应答（`result` 或 `error`），否则服务端会挂住。
- **必须幂等**：同一个 id 重复到达时，第二次直接返回相同结果或空操作。
- 本期只需实现 `approval`（审批卡片）。其余返回"不支持"错误。
- 后端撤回时发 `request.cancel {id, method, reason}` 事件，卡片要随之移除。

审批的**配套 RPC**（client→server，与上面的响应帧互补）：

| 方法 | 入参 | 返回 / 语义 |
|---|---|---|
| `approval.pending` | `session_id`, `profile?` | `{approvals: [...]}`：本会话还没解决的审批（重连 / 断线补偿） |
| `approval.received` | `session_id`, `request_id` | `{acknowledged}`：卡片已上屏，后端超时时钟起点 |
| `approval.respond` | `session_id`, `request_id?`, `choice`, `all?` | `{resolved}`：交付决策；**补拉的卡片只能走这条**（响应帧早已不存在） |

### 2.4 事件

服务端通过 `method: "event"` 通知推送。关键类型（本期）：

| 事件 | 用途 |
|---|---|
| 消息增量 | 流式 token delta（`prompt.submit` 之后） |
| 消息完成 | 一轮回答结束 |
| 会话状态变化 | 标题更新、运行中标记 |
| 工具调用 | 开始 / 参数 / 结果 / 失败 |
| 请求撤回 | `request.cancel {id, method, reason}`：某个等待中的 server→client 请求作废（超时 / 中断） |
| 用量 / 计费 | 状态条显示 |
| 子代理 | `subagent.spawn_requested` / `subagent.start` / `subagent.progress` / `subagent.thinking` / `subagent.tool` / `subagent.complete`（同一 payload 形状，身份字段可缺） |
| 旁路任务 | `background.complete` / `btw.complete`（`{task_id, text, question?}`） |
| 会话控制 | `session.control.update`（goal / loop / heartbeat 变更） |

> 具体事件 `type` 字段见 `gateway-contract.generated.ts` 的事件联合类型；实现时按类型分派到 `SharedFlow`。

### 2.5 超时

| 场景 | 超时 |
|---|---|
| 默认请求 | 30s |
| 启动期批量读取（profiles / config / models / cron） | 60s |
| `prompt.submit` | **1800s** |
| 健康探测 | 5–10s |

### 2.6 心跳与重连

```
每 N 秒 ping（N 建议 15–20s）
  └─ 超过 deadline（建议 2×N）无 pong → 判定死连接（处理半开 TCP）
       └─ 关闭 socket → 指数退避重连（1s, 2s, 4s… 上限 30s）
            └─ 重连成功 → session.events.since(lastEventId) 拉补齐
                 └─ 补齐的事件带 replayed 标记 → UI 不再做动画
```

- **重放可能重复投递**：事件处理必须幂等（按 message id + 序号去重）。
- 重连期间 UI 显示"重连中（第 N 次）"，不阻塞已渲染内容。

### 2.7 本地合成事件（非服务端发送）

| 事件 | 触发 |
|---|---|
| `gateway.reconnecting` | 重连开始（携带次数） |
| `gateway.start_timeout` | 启动期等待超时 |
| `gateway.stderr` | 后端进程 stderr 输出（远程模式下可能为空） |
| `gateway.protocol_error` | 收到无法解析的帧 |

---

## 3. REST

### 3.1 约定

- 全部为 `GET` / `POST` + JSON body。
- 鉴权：header 带 token（`/api/status` 除外）。
- 错误：非 2xx + `{"error": "..."}`，需映射成可读提示。
- 所有路径相对 base URL（`http://127.0.0.1:9119`）。

### 3.2 本期需要的端点

| 分类 | 端点 |
|---|---|
| 健康 | `GET /api/status` |
| 会话历史 | `GET /api/sessions/{id}/messages?limit=&offset=&order=oldest\|latest&profile=&inline_images=`（`limit` 后端钳到 **500**，向上翻页靠 `offset`） |
| 会话搜索 | `GET /api/sessions/search?q=`（命中带 snippet；标题用后端 title，不要用 snippet 当标题） |
| 技能 | `GET /api/skills`、`PUT /api/skills/toggle`（**PUT 不是 POST**）、`GET /api/skills/content?name=` |
| 工具集 | `GET /api/tools/toolsets`、`PUT /api/tools/toolsets/{name}` |
| 定时任务 | `GET /api/cron/jobs`、`POST /api/cron/jobs`（新建）、`PUT /api/cron/jobs/{id}`（body 是 `{updates:{…}}`）、`DELETE /api/cron/jobs/{id}`、`POST /api/cron/jobs/{id}/pause|resume|trigger`、`GET /api/cron/jobs/{id}/runs?limit=`（⚠️ 返回的是 **session 行**：`started_at`/`ended_at` 为 epoch 浮点、**没有 `status`**）、`GET /api/cron/blueprints`、`POST /api/cron/blueprints/instantiate`、`GET /api/cron/delivery-targets` |
| 消息平台 | `GET /api/messaging/platforms`、`PUT /api/messaging/platforms/{id}`（body `{enabled?, env{}, clear_env[], profile?}`，返回 `{ok, platform, hot_served}`）、`POST /api/messaging/platforms/{id}/test`（返回 `{ok, state, message}`） |
| profile | `GET /api/profiles` |
| 配置 | `GET /api/config`、`GET /api/config/schema`、`GET /api/config/defaults` |
| 模型 | `GET /api/model/info`、`GET /api/model/options`、`POST /api/model/set` |
| 日志 | `GET /api/logs` |
| 更新 | `GET /api/hermes/update/check?force=1`（**后端**更新检查，不是客户端自身更新） |

> 完整端点约 60 个（含 audio / local-models / mcp / webhooks / ops）。本期只接上表。

---

## 4. 实现优先级

| 顺序 | 内容 | 期 |
|---|---|---|
| 1 | WS 连接 + 请求/响应 + 心跳 + 重连 | P0 |
| 2 | `session.list` + `profiles.list`（经 WS） | P0 |
| 3 | `session.history` + 事件流渲染 | P1 |
| 4 | `prompt.submit` + 增量事件 | P2 |
| 5 | server→client 请求（仅审批类） | P2 |
| 6 | REST 客户端 + `/api/skills` `/api/tools/toolsets` `/api/cron/jobs` `/api/messaging/platforms` | P4 |
| 7 | 会话分页 / 导出 / 复制 / 分支、`session.steer`、`subagent.*`、更新检查 | v0.3.0 |

---

## 5. 反例（不要这么做）

- ❌ 把所有调用塞进 WebSocket RPC（REST 端点不在 RPC 表里）
- ❌ 给 `prompt.submit` 用默认 30s 超时
- ❌ 对 server→client 请求不应答（服务端会等待/超时）
- ❌ 假设重连后事件不会重复（必须幂等）
- ❌ 把 8642 当桌面端接口
- ❌ 手抄契约类型（用生成器；退路见 ADR-010）
- ❌ 生成中禁用发送按钮，或普通忙时提交带 `queued=true`（后端自己会 redirect / steer / 排队）
- ❌ 带 `truncate_before_*` 却不做显式二次确认（这是破坏性截断）
- ❌ 用 `subagent.list` 轮询代替 `subagent.*` 事件流
