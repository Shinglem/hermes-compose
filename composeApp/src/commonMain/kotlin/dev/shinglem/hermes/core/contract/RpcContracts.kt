package dev.shinglem.hermes.core.contract

/**
 * 0.23.0 canonical gateway 的**方法契约表**（从 payload 的 pydantic 模型导出，见下）。
 *
 * 为什么要有这张表：`gateway/session_controls.py::dispatch` 对
 * `tui_gateway/contracts/registry.py::CANONICAL_METHODS` 里的方法做**闭集**校验 ——
 * 多一个键就 `4001 invalid_params`（`canonical_param_problems`），少一个必填键同样。
 * 以前这类错误只能靠发出去再读后端报错来发现，一轮排查要重编译重部署。
 * 现在在发送前本地校验一遍，错在编译/单元测试阶段就暴露。
 *
 * **不在表里的方法没有闭集校验**（它们走 `METHODS` 契约，`dispatch` 只对 canonical 表检查），
 * 但它们的契约字段一样不许多传 —— 由各 Repository 自己保证，见 [NON_CANONICAL]。
 *
 * 导出命令（payload 内自带 python + venv 的 site-packages）：
 * ```
 * $env:PYTHONPATH = '<payload>\venv\Lib\site-packages'
 * & '<payload>\tools\python-3.14.9*\python.exe' -c "
 *   import sys; sys.path.insert(0, '<payload>\hermes-agent')
 *   from tui_gateway.contracts import CANONICAL_METHODS
 *   for n, c in sorted(CANONICAL_METHODS.items()): print(n, list(c.params.model_fields))"
 * ```
 */
object RpcContracts {
    /** 一个方法的参数契约：允许的键 + 必填的键。 */
    data class RpcContractSpec(val allowed: Set<String>, val required: Set<String>)

    /** server 端会**剥离** `profile` 的方法（路由到自家 home 之后），跨 profile 时必须带。 */
    val PROFILE_IMPLICIT: Set<String> = setOf(
        "session.create", "session.list", "session.info", "session.resume", "session.detach",
        "prompt.submit", "prompt.receipt", "prompt.cancel", "prompt.resolve_unknown",
        "prompt.steer_queued", "prompt.replace_queued",
        "session.interrupt", "session.events.since", "session.mutate", "session.stats",
        "approval.respond", "clarify.respond",
    )

    /**
     * canonical 方法（闭集）。只列本客户端用到的；完整 49 个见类注释里的导出命令。
     */
    private val CANONICAL: Map<String, RpcContractSpec> = mapOf<String, RpcContractSpec>(
        RpcMethods.SESSION_CREATE to RpcContractSpec(
            allowed = setOf(
                "profile", "request_id", "source", "cwd", "row_cwd", "model", "provider", "base_url",
                "api_key", "toolsets", "reasoning", "max_turns", "ignore_rules", "yolo", "safe_mode",
                "ignore_user_config", "skills", "checkpoints", "accept_hooks", "pass_session_id",
                "editor", "title", "hidden", "follow_profile_config", "service_tier", "cwd_explicit",
                "run_budget", "verbose", "client_env",
            ),
            required = emptySet<String>(),
        ),
        RpcMethods.SESSION_RESUME to RpcContractSpec(
            allowed = setOf(
                "profile", "session_id", "title", "latest", "workspace", "source", "editor", "api_key",
                "defer_history", "omit_messages", "cols", "display_projection",
            ),
            required = emptySet<String>(),
        ),
        RpcMethods.SESSION_INFO to RpcContractSpec(
            allowed = setOf("session_id"),
            required = setOf("session_id"),
        ),
        RpcMethods.SESSION_LIST to RpcContractSpec(
            allowed = setOf("profile", "scope", "limit", "title", "include_hidden"),
            required = emptySet<String>(),
        ),
        RpcMethods.SESSION_MUTATE to RpcContractSpec(
            allowed = setOf(
                "session_id", "request_id", "expected_revision", "operation", "payload",
                "expected_generation",
            ),
            required = setOf("session_id", "request_id", "expected_revision", "operation", "payload"),
        ),
        RpcMethods.SESSION_INTERRUPT to RpcContractSpec(
            allowed = setOf("profile", "session_id", "execution_generation"),
            required = setOf("session_id", "execution_generation"),
        ),
        RpcMethods.SESSION_STEER to RpcContractSpec(
            allowed = setOf("profile", "session_id", "execution_generation", "text"),
            required = setOf("session_id", "execution_generation", "text"),
        ),
        RpcMethods.SESSION_EVENTS_SINCE to RpcContractSpec(
            allowed = setOf("profile", "session_id", "last_sequence", "last_seen", "replay_epoch"),
            required = setOf("session_id"),
        ),
        RpcMethods.PROMPT_SUBMIT to RpcContractSpec(
            allowed = setOf(
                "profile", "session_id", "text", "submission_id", "input_id", "queued", "attachments",
                "finite", "unattended", "surface", "voice_context", "interrupted", "voice_turn",
                "display_kind", "title_preview", "turn_author", "api_text", "model_override",
                "clarify_batch",
            ),
            required = setOf("session_id", "text"),
        ),
        RpcMethods.PROMPT_CANCEL to RpcContractSpec(
            allowed = setOf("session_id", "admission_id"),
            required = setOf("session_id", "admission_id"),
        ),
        RpcMethods.APPROVAL_RESPOND to RpcContractSpec(
            allowed = setOf("profile", "session_id", "execution_generation", "prompt_id", "choice"),
            required = setOf("session_id", "execution_generation", "prompt_id", "choice"),
        ),
        RpcMethods.CLARIFY_RESPOND to RpcContractSpec(
            allowed = setOf("session_id", "execution_generation", "prompt_id", "answer"),
            required = setOf("session_id", "execution_generation", "prompt_id", "answer"),
        ),
        RpcMethods.IMAGE_ATTACH_BYTES to RpcContractSpec(
            allowed = setOf("session_id", "profile", "content_base64", "data", "filename", "ext"),
            required = setOf("session_id"),
        ),
        RpcMethods.CONFIG_SET to RpcContractSpec(
            allowed = setOf("profile", "session_id", "key", "value", "confirm_expensive_model", "scope"),
            required = setOf("key"),
        ),
        RpcMethods.CONFIG_GET to RpcContractSpec(
            allowed = setOf("profile", "key", "cwd", "session_id"),
            required = setOf("key"),
        ),
        RpcMethods.SESSION_STATS to RpcContractSpec(
            allowed = setOf("session_id"),
            required = setOf("session_id"),
        ),
        RpcMethods.PROFILES_LIST to RpcContractSpec(
            allowed = setOf("profile", "include_sessions"),
            required = emptySet<String>(),
        ),
    )

    /**
     * 不走闭集校验的方法（`dispatch` 只对 canonical 表检查）。
     *
     * 仍然**不要**多传键：它们的 `METHODS` 契约一样是固定字段集，
     * 只是后端不会在 dispatch 层拒绝而已（多余键会被 handler 忽略或引发别的拒绝）。
     */
    val NON_CANONICAL: Set<String> = setOf(
        RpcMethods.GATEWAY_PING, RpcMethods.CLIENT_CAPABILITIES,
        RpcMethods.SESSION_ACTIVE_LIST, RpcMethods.SESSION_CONTEXT_BREAKDOWN,
        RpcMethods.PROCESS_LIST, RpcMethods.PROCESS_KILL,
        RpcMethods.APPROVAL_PENDING, RpcMethods.APPROVAL_RECEIVED,
        RpcMethods.SUBAGENT_LIST, RpcMethods.SUBAGENT_TAIL,
        RpcMethods.SUBAGENT_STEER, RpcMethods.SUBAGENT_INTERRUPT,
        RpcMethods.COMPLETE_PATH, RpcMethods.COMPLETE_SLASH,
        RpcMethods.PASTE_COLLAPSE, RpcMethods.FILE_ATTACH, RpcMethods.MODEL_OPTIONS,
    )

    fun canonicalRpcContractSpec(method: String): RpcContractSpec? = CANONICAL[method]

    /**
     * 发送前自检：canonical 方法必须落在闭集内且带齐必填键。
     *
     * 返回 `null` 表示通过；否则返回可直接写进日志/异常的说明（含非法键名，和 backend 的
     * `data.fields` 一个语义）。
     */
    fun problems(method: String, keys: Set<String>): String? {
        val spec = CANONICAL[method] ?: return null
        val extra = keys - spec.allowed
        val missing = spec.required - keys
        if (extra.isEmpty() && missing.isEmpty()) return null
        return buildString {
            append("$method 参数不符合 canonical 契约")
            if (extra.isNotEmpty()) append("，多余键=$extra")
            if (missing.isNotEmpty()) append("，缺少必填=$missing")
        }
    }
}
