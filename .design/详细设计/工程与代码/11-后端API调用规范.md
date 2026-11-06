# 11 · 后端 API 调用规范（hermes 0.23.0 / canonical gateway）

> 这份文档解决一个具体问题：0.23.0 起桌面端不再是"想调什么 RPC 就调什么"，
> 而是一套**闭集契约 + fence + 双通道**。规则散落在 payload 源码里，靠试错摸一遍要重编译重部署
> 十几次（本项目已经为此返工多轮）。这里把规则一次性写清楚，新增调用前先对照第 9 节的清单。

## 0. 一句话

**读走 REST，写走 `session.mutate`，控制走带 generation 的 RPC，等待用户的请求必须能从快照补拉。**

---

## 1. 两条通道，谁负责什么

`backend.local.engine = gateway`（默认）时，我们 attach 的是上游**机器级 gateway**
（`hermes gateway ensure` 起的 daemon，协议名 `hermes-gateway-v1`）。
它的 WebSocket 只服务 `gateway/session_controls.py::handlers` 里的动词，
另有 335 个 REST 端点由同一个 listener 提供（`/api/*`，与 WS 同端口）。

| 要做的事 | 走哪条 | 理由 |
|---|---|---|
| 会话列表 / 搜索 | REST `GET /api/sessions?...` `GET /api/sessions/search` | RPC `session.list` 默认 `scope=live`，只给运行中的会话；`limit` 上限 100 |
| 会话历史 | REST `GET /api/sessions/{id}/messages` | RPC `session.history` 在 gateway 路线下 `-32601` |
| 打开会话 | RPC `session.resume` | 只有它拿得到 `execution_generation` / `revision` / `subscription_id` |
| 发消息 | RPC `prompt.submit` | |
| 生成中纠偏 | RPC `session.steer` | 需要 `execution_generation` |
| 停止 | RPC `session.interrupt` | 需要 `execution_generation` |
| 改名 / 归档 / 置顶 | REST `PATCH /api/sessions/{id}`（已落库）<br>`session.mutate{rename,archive}`（未落库的 live 会话） | REST 需要 `mutation-snapshot` 拿 fence；live 会话库里还没有行 |
| 删除 | REST `DELETE /api/sessions/{id}` | **fence 在 query string**，不在 body |
| 分支 / 复制 / 回退 | `session.mutate{operation:branch\|rewind}` | 需要完整的 revision + generation fence |
| 切模型 / 推理强度 | `session.mutate{operation:model\|reasoning}` | `config.set` 会回 `4001 use_session_mutation_model` |
| 上下文用量 | `session.context_breakdown` | `session.usage` 已被移除，只剩同名**事件** |
| 图片 | `image.attach_bytes`（base64） | 路径版 `image.attach` 只在 direct 后端有 |
| 文件 | `file.attach`（`path` 或 `data_url`） | 路径必须对 gateway 可见（会话 cwd 内） |
| 剪贴板图片 / 拖放探测 | **客户端自己做** | `clipboard.paste` / `input.detect_drop` 在 gateway 路线下 `-32601` |

---

## 2. 契约是闭集（最容易踩的一条）

`tui_gateway/contracts/registry.py::canonical_param_problems` 对 `CANONICAL_METHODS` 里的 **49 个方法**
做闭集校验：**多一个键、少一个必填键都是 `4001 invalid_params`**（`data.fields` 会指出是哪个键）。

不在那 49 个里的方法（`subagent.*` / `complete.*` / `process.*` / `approval.pending` /
`session.context_breakdown` / `model.options` / `file.attach` / `paste.collapse` / `client.capabilities`）
不做闭集拒绝，但它们的 `METHODS` 契约一样是固定字段集 —— **不要因为"能过"就多传**。

客户端侧由 [`RpcContracts`](../../../composeApp/src/commonMain/kotlin/dev/shinglem/hermes/core/contract/RpcContracts.kt)
在 `GatewayClient.call` 发送前自检，参数不合契约会在本地抛 `AppError.Protocol` 并写 WARN 日志。

导出的权威表（payload 内自带 python + venv 的 site-packages）：

```powershell
$env:PYTHONPATH = '<payload>\venv\Lib\site-packages'
& '<payload>\tools\python-3.14.9*\python.exe' -c @"
import sys; sys.path.insert(0, '<payload>\hermes-agent')
from tui_gateway.contracts import CANONICAL_METHODS, METHODS
for n, c in sorted(CANONICAL_METHODS.items()):
    print(n, [f for f in c.params.model_fields], 'req=', [f for f,i in c.params.model_fields.items() if i.is_required()])
"@
```

---

## 3. `profile` 必须带（多 profile 复用）

daemon 是 multiplex：一条 WebSocket 连接属于**启动 home**，而会话可能属于
`butler` / `mine` / `ops` 等兄弟 profile。

`gateway/session_controls.py::dispatch` 的规则：

```
params.profile 存在 → 路由到对应 authority（兄弟 profile）
                   → 到自家 home 时，若方法在 _PROFILE_IMPLICIT 里，剥掉这个键
```

`_PROFILE_IMPLICIT` 含：`session.create/list/info/resume/detach`、`prompt.*`、
`session.interrupt`、`session.events.since`、`session.mutate`、`session.stats`、
`approval.respond`、`clarify.respond`。

**结论：会话级调用统一带 `profile`。**
不带的话，兄弟 profile 的会话会命中错误的 authority →
`session_id not in subscriptions` → `4001 permission_denied`（实测踩过，一次）。

---

## 4. 四个 fence，别混

| 字段 | 谁需要 | 从哪拿 | 什么时候变 |
|---|---|---|---|
| `expected_revision` | `session.mutate`（**必填**） | `session.resume` / `session.create` 回执的 `revision`；REST `mutation-snapshot` 的 `runtime_revision` | 每次成功 mutate +1 |
| `expected_generation` | `session.mutate` 的 model / reasoning / workspace / compress / rewind / delete / branch | 同上回执的 `execution_generation` | 每轮对话开始时 +1 |
| `execution_generation` | `session.interrupt` / `session.steer` / `approval.respond` / `clarify.respond`（**必填**） | 事件帧顶层的 `execution_generation`（最可靠）、resume 回执 | 每轮 +1 |
| `submission_id` | `prompt.submit`（handler **强制**要） | 客户端生成，一次提交一个 | 不变（幂等键） |

**拒绝里带新鲜值。** `revision_conflict` / `stale_generation` 的 `error.data` 里有
`revision` / `execution_generation`，用**同一个 `request_id`** 重试一次即可（幂等）。
这条逻辑只有一处实现：[`SessionMutator`](../../../composeApp/src/commonMain/kotlin/dev/shinglem/hermes/core/transport/SessionMutator.kt)。

**fence 的单一来源**（`ChatViewModel`）：
- `session.resume` 回执 → `revisions[key]` / `generations[key]`
- 每个事件帧顶层 `execution_generation` → `generations[key]`
- mutate / control 回执 → 写回

---

## 5. "等待用户"的两条路径都必须能补拉（本项目最大的坑）

后端会停下来等人：`kind = approval`（危险命令）或 `clarify`（提问）。
它有两种到达方式，**两条都要实现**：

| 来源 | 携带 | 应答通道 |
|---|---|---|
| 实时 `server→client` 请求帧 | JSON-RPC 帧 id | 回响应帧（`awaitApprovalDecision` / `awaitClarifyDecision`） |
| `session.resume` 快照的 `prompts[]` | `prompt_id` + `execution_generation` | `approval.respond` / `clarify.respond` |

**只实现实时帧 = 重连后永久卡死。** 表现是：界面上"一直正在生成"或"发消息没反应"，
后端 `running=true` 且 `status=waiting`，日志里没有任何错误。
排查命令（见第 8 节）能一轮定位。

客户端侧统一入口：`ChatViewModel.adoptSnapshotPrompts(key, resumed)`，
它在每次 `ensureRuntime` 之后把快照里的 `approval` / `clarify` 补成卡片。
新增"等待用户"的类型时**必须**在这个 `when` 里加分支，否则又会出现静默卡死。

---

## 6. 事件与状态

- `message.start` / `message.delta` / `message.complete`：一轮的生命周期，**ack 不代表开始跑**
- `prompt.submit` 的 ack `status=queued` 只表示"已接受/已排队"；真正开跑看事件
- 每帧顶层的 `execution_generation` 要跟（第 4 节）
- 长时间没有该会话的任何事件 → 看门狗对账（`TURN_SILENCE_TIMEOUT_MS`）：
  `running=false` 才收尾，`running=true` 继续等（长工具调用不该被打断）

---

### 2.3 排队与"插队"

`prompt.submit` 的 ack 是 `AdmissionStatus`：`{admission_id, sequence, status, outcome, authority_epoch, execution_generation}`，
`status` 取 `queued | started | unknown | terminal`。**`queued` 不代表"不属于这一轮"** ——
新会话的首条消息在 agent 起来前也是 `queued`，判断归属要看"发送时是否正在生成"。

| 想做 | 方法 | 参数要点 |
|---|---|---|
| 看还有几条在等 | `session.resume` 的 `pending[]` | 每条有 `admission_id` / `text` / `status` |
| 让排队那条**立刻**给当前轮用 | `prompt.steer_queued` | `session_id` + `admission_id`（**不要** generation） |
| 取消排队那条 | `prompt.cancel` | `session_id` + `admission_id` |
| 改写排队那条 | `prompt.replace_queued` | 再加 `submission_id` + `text` |
| 更强的整轮转向 | `session.redirect` | 要 `execution_generation`；agent 不支持时报错 |

`prompt.steer_queued` 的拒绝都要当"当前接不了"处理，不是参数错误：

| reason | 含义 | 客户端动作 |
|---|---|---|
| `steer_unavailable` | 这一轮还没起 agent | 提示"等它跑起来再点" |
| `unsupported_control` | 该模型不支持 mid-turn steer | 提示"会排在这一轮结束后自动发送" |
| `admission_started` | 这条已经在跑了 | 从队列摘掉 |
| `session_idle` | 这一轮已结束 | 从队列摘掉 |

**没有** admission 状态推送事件（`EVENTS` 里没有 `admission.*`），所以队列只能靠
`pending[]` + submit 的 ack + `prompt.receipt` 对齐。

---

## 7. 错误码 → 处置

| code / message | 含义 | 处置 |
|---|---|---|
| `4001 invalid_params`（带 `data.fields`） | 闭集契约不匹配 | 修参数；`RpcContracts` 应已在本地拦住 |
| `4001 permission_denied` | 跨 profile 没带 `profile`，或没订阅该会话 | 带 `profile`；必要时先 `session.resume` |
| `4001 stale_generation` | generation 过期 | 用 `data.execution_generation` 重试一次 |
| `4001 revision_conflict` | revision 过期 | 用 `data.revision` 重试一次 |
| `4001 use_session_mutation_model` | `config.set` 不该用于会话级模型/推理 | 改 `session.mutate` |
| `-32601 unknown method` | 该动词不在 gateway 路线 | 查第 1 节的通道对照表 |
| `409 client_upgrade_required` | REST 写请求没带 fence | DELETE 放 **query**，PATCH 放 **body** |
| `422` + `less_than_equal` | `limit` 超上限（`/api/sessions` 是 100） | clamp |

---

## 8. 排查卡死（一次性命令）

```powershell
# 会话运行态 + 待应答请求（最关键）
$env:PYTHONPATH = '<payload>\venv\Lib\site-packages'
& '<payload>\tools\python-3.14.9*\python.exe' -c @"
import asyncio, json, websockets
async def m():
    async with websockets.connect('ws://127.0.0.1:65099/api/ws?token=<token>') as ws:
        await asyncio.sleep(1)
        await ws.send(json.dumps({'jsonrpc':'2.0','id':1,'method':'session.resume',
          'params':{'session_id':'<sid>','profile':'butler','omit_messages':True}}))
        while True:
            r=json.loads(await ws.recv())
            if r.get('id')==1: break
        res=r['result']
        print('running', res['running'], 'gen', res['execution_generation'], 'rev', res['revision'])
        print('prompts', json.dumps(res['prompts'], ensure_ascii=False))
        print('pending', json.dumps(res['pending'], ensure_ascii=False))
asyncio.run(m())
"@
```

- `prompts` 非空 → 后端在等人：补卡片 or 直接 `*.respond`
- `pending` 里有 `status: started` → 消息已被接受，只是前面卡住了
- `running=false` 且都为空 → 这一轮真的结束了，界面状态是本地残留

---

## 9. 新增一个调用的检查清单

1. **它属于第 1 节哪一类？** 读 → REST；写 → `session.mutate`；控制 → 带 generation 的 RPC。
2. **在 `RpcContracts` 里登记**允许键与必填键（canonical 方法）。
3. **带 `profile`**（会话级调用）。
4. **确认 fence 来源**：revision 从 resume/snapshot 来；generation 从事件帧来。
5. **是否会等待用户应答？** 是 → 实时帧路径 + 快照 `prompts[]` 路径**两条都要**写。
6. **ack 与真实开始的区分**：`queued` / `submitted` 都不是"开跑"。
7. 加一条 `Log.info`：方法名 + 关键参数 + 结果状态（否则界面只剩一句 `4001`）。
8. 实测一遍：`session.resume` 打印 `prompts` / `pending` / `running`（第 8 节）。

---

## 10. 与官方 Desktop 对齐

官方 bundle（`hermes/official-desktop`）里有一层 wire 适配（`prepareMutation` / `wire`），
把 `session.title` / `session.archive` / `session.branch*` / `session.cwd.set` 映射成
`session.mutate`，并维护 `revisions` / `generations` 两个 map。我们的
[`SessionMutator`](../../../composeApp/src/commonMain/kotlin/dev/shinglem/hermes/core/transport/SessionMutator.kt)
就是这层的等价物。**遇到不确定的调用形态，先去官方 bundle 里搜方法名**，
它一定已经按当前后端版本写好了一遍。
