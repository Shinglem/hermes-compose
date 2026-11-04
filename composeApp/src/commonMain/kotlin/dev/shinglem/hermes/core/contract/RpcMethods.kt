package dev.shinglem.hermes.core.contract

/**
 * 本期用到的 RPC 方法名。完整清单（约 120 个）由后端 `contracts/registry.py` 生成；
 * 生成器落地前先维护这个最小集（见 `.design/草案/07-decisions.md` ADR-010）。
 */
object RpcMethods {
    const val SESSION_LIST = "session.list"
    const val SESSION_HISTORY = "session.history"
    const val SESSION_RESUME = "session.resume"
    const val SESSION_CREATE = "session.create"
    const val SESSION_DELETE = "session.delete"
    const val SESSION_ARCHIVE = "session.archive"
    const val SESSION_TITLE = "session.title"
    const val SESSION_STATUS = "session.status"
    const val SESSION_EVENTS_SINCE = "session.events.since"
    const val SESSION_INTERRUPT = "session.interrupt"
    const val PROMPT_SUBMIT = "prompt.submit"
    const val PROFILES_LIST = "profiles.list"
    const val GATEWAY_PING = "gateway.ping"

    /**
     * 连接级能力声明（`contracts/liveness.py`）：必须在收到 `gateway.ready` 后、每一条连接各发一次，
     * `server_requests=true` 才表示本端能应答 server→client 请求（审批 / 澄清）。
     * 不发的话后端认为本端是老客户端，审批请求**根本不会发出来** ——
     * 危险命令直接被撤回并判 BLOCKED。
     */
    const val CLIENT_CAPABILITIES = "client.capabilities"

    // ── 审批（`tui_gateway/methods_prompt.py`）────────────────────────────

    /** 补拉本会话还没解决的审批（重连 / 请求发出时本端不在）。 */
    const val APPROVAL_PENDING = "approval.pending"

    /** 告知后端卡片已上屏，它的超时时钟从这里开始算。 */
    const val APPROVAL_RECEIVED = "approval.received"

    /** 交付决策：`choice` + `request_id`。响应帧路径失效时（重连补拉）走它。 */
    const val APPROVAL_RESPOND = "approval.respond"

    /** composer 侧：`@` / `/` 补全与附件（`contracts/profiles_vault_complete_foreign_subagents.py`
     *  与 `contracts/prompt_voice.py`）。 */
    const val COMPLETE_PATH = "complete.path"
    const val COMPLETE_SLASH = "complete.slash"
    const val PASTE_COLLAPSE = "paste.collapse"
    const val FILE_ATTACH = "file.attach"
    const val IMAGE_ATTACH = "image.attach"
    const val CLIPBOARD_PASTE = "clipboard.paste"
    const val INPUT_DETECT_DROP = "input.detect_drop"
    const val SESSION_CWD_SET = "session.cwd.set"

    /**
     * 模型选择（`tui_gateway/methods_complete.py` 与 `methods_config_set.py`）。
     *
     * - `model.options`：provider + 模型目录（与 REST `/api/model/options` 同一 payload）
     * - `config.set`：`key="model"` 切当前会话模型（value 形如 `"<model> --provider <slug>"`），
     *   `key="reasoning"` 切推理强度（`scope="session"`）
     */
    const val MODEL_OPTIONS = "model.options"
    const val CONFIG_SET = "config.set"

    // ── v0.3.0：会话控制 / 分支 / 子代理 ─────────────────────────────────────

    /** 当前活跃会话 id（会话列表的运行态点）。 */
    const val SESSION_ACTIVE_LIST = "session.active_list"

    /** 上下文 / token 用量；明细（分类）走 `session.context_breakdown`。 */
    const val SESSION_USAGE = "session.usage"
    const val SESSION_CONTEXT_BREAKDOWN = "session.context_breakdown"

    /** 生成中纠偏：重定向当前轮而不打断它。 */
    const val SESSION_STEER = "session.steer"

    /** 截至某条消息的分支（要求会话活跃）；`branch_whole` 整段克隆且不回传 transcript。 */
    const val SESSION_BRANCH = "session.branch"
    const val SESSION_BRANCH_WHOLE = "session.branch_whole"

    /** 存储态整份克隆（父会话不必活跃）——「复制为新会话」用它。 */
    const val SESSION_BRANCH_STORED = "session.branch_stored"

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
