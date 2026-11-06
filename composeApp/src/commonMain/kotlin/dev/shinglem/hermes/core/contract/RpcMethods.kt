package dev.shinglem.hermes.core.contract

/**
 * 本期用到的 RPC 方法名。
 *
 * **只列 0.23.0 canonical gateway（`gateway ensure` 起的 daemon）真的提供的方法**。
 * 那条路线只服务 `gateway/session_controls.py::handlers` 里的动词，参数还是**闭集**
 * （`tui_gateway/contracts/canonical_*.py`）—— 多一个键就是 `4001 invalid_params`，
 * 少一个必填键（如 `execution_generation`）也是。
 *
 * 已从这张表删掉的旧方法（调用会 `-32601`，改走别的通道）：
 *
 * | 旧方法 | 0.23.0 的替代 |
 * |---|---|
 * | `session.title` / `session.archive` / `session.delete` | REST `PATCH`/`DELETE /api/sessions/{id}` |
 * | `session.branch` / `session.branch_whole` / `session.branch_stored` | `session.mutate {operation:"branch"}` |
 * | `session.usage` | `session.context_breakdown`（+ `session.usage` **事件**） |
 * | `session.cwd.set` | `session.mutate {operation:"workspace"}` |
 * | `session.list` | REST `GET /api/sessions`（RPC 只给 `scope=live`） |
 * | `session.history` | REST `GET /api/sessions/{id}/messages` |
 *
 * 完整清单见后端 `contracts/registry.py`（ADR-010 的生成器落地前先手工维护这个最小集）。
 */
object RpcMethods {
    /** 只返回 live 会话（默认 `scope=live`）；会话列表走 REST，不要用这个。 */
    const val SESSION_LIST = "session.list"
    const val SESSION_RESUME = "session.resume"
    const val SESSION_CREATE = "session.create"

    /** `session.info`：live 会话的 `revision` / `execution_generation` 来源。 */
    const val SESSION_INFO = "session.info"

    /**
     * 0.23.0 canonical gateway 上**所有"改会话"操作的唯一入口**：
     * rename / archive / delete / branch / rewind / workspace / reasoning / model / compress。
     *
     * 参数集是精确匹配：`session_id` + `request_id` + `expected_revision` + `operation` +
     * `payload`（+ 可选 `expected_generation`），**不能带 `profile`**。
     */
    const val SESSION_MUTATE = "session.mutate"

    /** 断线重连后补事件（`last_sequence` / `replay_epoch` 对齐）。 */
    const val SESSION_EVENTS_SINCE = "session.events.since"

    /** 停止当前轮；**必须**带 `execution_generation`（`CanonicalControlParams`）。 */
    const val SESSION_INTERRUPT = "session.interrupt"

    /** 生成中纠偏；**必须**带 `execution_generation`（`CanonicalCorrectionParams`）。 */
    const val SESSION_STEER = "session.steer"

    const val PROMPT_SUBMIT = "prompt.submit"

    /** 撤销一条 admission（停止生成的正规路径；`session.interrupt` 是另一条）。 */
    const val PROMPT_CANCEL = "prompt.cancel"

    /** 把**排队中**的 admission 立刻交给正在跑的那一轮（插队）。 */
    const val PROMPT_STEER_QUEUED = "prompt.steer_queued"

    /** 取消排队项并提交编辑后的文本（替换，不插队）。 */
    const val PROMPT_REPLACE_QUEUED = "prompt.replace_queued"

    /** clarify 的作答（与 `approval.respond` 同一套 `execution_generation` + `prompt_id`）。 */
    const val CLARIFY_RESPOND = "clarify.respond"

    const val PROFILES_LIST = "profiles.list"
    const val GATEWAY_PING = "gateway.ping"

    /** `session.stats`：token 总量与上下文长度（`session.usage` 已被移除）。 */
    const val SESSION_STATS = "session.stats"

    /** `config.get` / `config.set`：非会话级配置读取与写入。 */
    const val CONFIG_GET = "config.get"

    /**
     * 连接级能力声明（`contracts/liveness.py`）：必须在收到 `gateway.ready` 后、每一条连接各发一次，
     * `server_requests=true` 才表示本端能应答 server→client 请求（审批 / 澄清）。
     * 不发的话后端认为本端是老客户端，审批请求**根本不会发出来** ——
     * 危险命令直接被撤回并判 BLOCKED。
     */
    const val CLIENT_CAPABILITIES = "client.capabilities"

    // ── 审批 ─────────────────────────────────────────────────────────────

    /** 补拉本会话还没解决的审批（重连 / 请求发出时本端不在）。 */
    const val APPROVAL_PENDING = "approval.pending"

    /** 告知后端卡片已上屏，它的超时时钟从这里开始算。 */
    const val APPROVAL_RECEIVED = "approval.received"

    /** 交付决策：canonical 契约要 `prompt_id` + `choice` + `execution_generation`。 */
    const val APPROVAL_RESPOND = "approval.respond"

    // ── composer：`@` / `/` 补全与附件 ─────────────────────────────────────

    const val COMPLETE_PATH = "complete.path"
    const val COMPLETE_SLASH = "complete.slash"
    const val PASTE_COLLAPSE = "paste.collapse"
    const val FILE_ATTACH = "file.attach"

    /**
     * 图片附加：canonical gateway **只有** `image.attach_bytes`（内容走 base64）。
     * 路径版的 `image.attach`、后端读剪贴板的 `clipboard.paste`、拖放检测
     * `input.detect_drop` 都只在 direct 后端上有，gateway 路线会 `-32601` ——
     * 那三件事改由客户端自己做（读文件 / 读剪贴板 / 判扩展名）。
     */
    const val IMAGE_ATTACH_BYTES = "image.attach_bytes"

    /**
     * 模型选择（`tui_gateway/methods_complete.py` 与 `methods_config_set.py`）。
     *
     * - `model.options`：provider + 模型目录（与 REST `/api/model/options` 同一 payload）
     * - `config.set`：`key="model"` 切当前会话模型；`key="reasoning"` 切推理强度。
     *   canonical gateway 对会话级切换走 `session.mutate`（operation `model` / `reasoning`），
     *   `config.set` 只在 direct 后端上保证可用。
     */
    const val MODEL_OPTIONS = "model.options"
    const val CONFIG_SET = "config.set"

    // ── 运行态 / 用量 ─────────────────────────────────────────────────────

    /** 当前活跃会话 id（会话列表的运行态点）。 */
    const val SESSION_ACTIVE_LIST = "session.active_list"

    /** 上下文占用（用量条与明细的唯一数据源）。 */
    const val SESSION_CONTEXT_BREAKDOWN = "session.context_breakdown"

    // ── 子代理 ───────────────────────────────────────────────────────────

    const val SUBAGENT_LIST = "subagent.list"
    const val SUBAGENT_TAIL = "subagent.tail"
    const val SUBAGENT_STEER = "subagent.steer"
    const val SUBAGENT_INTERRUPT = "subagent.interrupt"

    // ── 后台任务（`contracts/tools_commands.py`）────────────────────────────

    /** 本会话拥有的后台进程快照（composer 上方状态栈轮询）。 */
    const val PROCESS_LIST = "process.list"

    /** 终止单个后台进程（后端按 `session_key` 校验归属）。 */
    const val PROCESS_KILL = "process.kill"
}