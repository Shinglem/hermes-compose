package dev.shinglem.hermes.core.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * `hermes gateway ensure --json` 给出的后端端点（`hermes_cli/gateway_runtime.py::GatewayEndpoint`）。
 *
 * 只保留客户端真正要用的字段：`capabilities` 与 `code_sha` 用来判断「这个 owner 够不够新」，
 * 不在解析阶段做决策（决策留给 [GatewayEnsure] 的守卫）。
 */
data class GatewayEndpointInfo(
    val profileId: String,
    val instanceId: String,
    val authorityEpoch: Long,
    val runtimeProtocol: Int,
    val apiOrigin: String,
    val supervisor: String,
    val capabilities: List<String>,
    val controlHome: String? = null,
    val codeSha: String? = null,
)

/** ensure 的判定结果。三种结局对应三种完全不同的用户动作，所以不做成布尔。 */
sealed interface EnsureVerdict {

    /** 可以附着。 */
    data class Ready(val endpoint: GatewayEndpointInfo, val clientCodeSha: String?) : EnsureVerdict

    /**
     * ensure 正常退出但判不了 ready（`absent` / `starting` / `deadline` / `authorization` / …）。
     *
     * [reasonCode] 是上游的稳定标识，[detail] 是可读原因（上游已做脱敏），UI 用前者选文案、
     * 用后者做诊断详情。
     */
    data class Unavailable(
        val state: String,
        val reasonCode: String?,
        val detail: String? = null,
    ) : EnsureVerdict

    /** 连协议本身都没对上：没有可解析的 JSON，或端点看起来不安全。 */
    data class Unparseable(val exitCode: Int, val detail: String) : EnsureVerdict
}

/**
 * 解析并**校验** `gateway ensure --json` 的输出。
 *
 * 校验不是形式主义：`api_origin` 会被拼成 REST baseUrl 与 WS URL，`profile_id` 决定后续
 * `gateway stop` 的作用域 —— 一个被篡改/错版本的输出足以让我们连到别人的后端，或者在关停时
 * 碰到不属于我们的 gateway。判据与上游客户端一致：
 *
 *  - `runtime_protocol` 必须等于 [RUNTIME_PROTOCOL]（上游 `gateway_runtime_cli` 的内嵌脚本同样只认 1）；
 *  - `api_origin` 必须是 `http(s)://host:port`，**不允许** path / query / fragment / 凭据；
 *  - `profile_id` 必须是绝对路径（上游用 `Path(...).resolve()` 后比对）。
 */
object GatewayEnsure {
    const val RUNTIME_PROTOCOL = 1

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun parse(stdout: String, exitCode: Int): EnsureVerdict {
        val payload = lastJsonLine(stdout)
            ?: return EnsureVerdict.Unparseable(
                exitCode,
                "ensure 没有输出 JSON（exit=$exitCode）",
            )
        val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: return EnsureVerdict.Unparseable(exitCode, "ensure 输出的 JSON 无法解析")

        val state = root.string("state")
            ?: return EnsureVerdict.Unparseable(exitCode, "ensure 输出缺少 state")
        val reasonCode = root.string("reason_code")
        val detail = root.string("detail")
        val endpoint = root["endpoint"] as? JsonObject

        if (state != "ready") {
            return EnsureVerdict.Unavailable(state, reasonCode, detail)
        }
        if (endpoint == null) {
            // 上游只有在 state=ready 时才带 endpoint；缺了就是协议对不上，不能猜。
            return EnsureVerdict.Unparseable(exitCode, "state=ready 但缺少 endpoint")
        }

        val runtimeProtocol = endpoint["runtime_protocol"]?.let { (it as? JsonPrimitive)?.longOrNull }?.toInt()
            ?: return EnsureVerdict.Unparseable(exitCode, "endpoint 缺少 runtime_protocol")
        if (runtimeProtocol != RUNTIME_PROTOCOL) {
            return EnsureVerdict.Unparseable(
                exitCode,
                "endpoint 的 runtime_protocol=$runtimeProtocol，本客户端只支持 $RUNTIME_PROTOCOL",
            )
        }

        val apiOrigin = endpoint.string("api_origin")
            ?: return EnsureVerdict.Unparseable(exitCode, "endpoint 缺少 api_origin")
        apiOriginProblem(apiOrigin)?.let { return EnsureVerdict.Unparseable(exitCode, it) }

        val profileId = endpoint.string("profile_id")
            ?: return EnsureVerdict.Unparseable(exitCode, "endpoint 缺少 profile_id")
        if (!isAbsolutePath(profileId)) {
            return EnsureVerdict.Unparseable(exitCode, "endpoint 的 profile_id 不是绝对路径：$profileId")
        }

        val capabilities = (endpoint["capabilities"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()

        return EnsureVerdict.Ready(
            endpoint = GatewayEndpointInfo(
                profileId = profileId,
                instanceId = endpoint.string("instance_id").orEmpty(),
                authorityEpoch = endpoint.long("authority_epoch") ?: 0L,
                runtimeProtocol = runtimeProtocol,
                apiOrigin = apiOrigin.trimEnd('/'),
                supervisor = endpoint.string("supervisor").orEmpty(),
                capabilities = capabilities,
                controlHome = endpoint.string("control_home"),
                codeSha = endpoint.string("code_sha"),
            ),
            clientCodeSha = root.string("client_code_sha"),
        )
    }

    /**
     * `api_origin` 的合法性。返回 null 表示通过，否则返回问题描述。
     *
     * 只看字符串不解析 URL 类型：commonMain 没有 `java.net.URI`，而且规则本身就是固定的三段式。
     */
    fun apiOriginProblem(origin: String): String? {
        val scheme = when {
            origin.startsWith("http://", ignoreCase = true) -> "http://"
            origin.startsWith("https://", ignoreCase = true) -> "https://"
            else -> return "api_origin 不是 http(s) 地址：$origin"
        }
        val rest = origin.removePrefix(scheme).removePrefix(scheme.uppercase())
        if (rest.isEmpty()) return "api_origin 缺少主机：$origin"
        if (rest.any { it == '/' || it == '?' || it == '#' }) {
            return "api_origin 不允许带 path/query/fragment：$origin"
        }
        if (rest.contains('@')) return "api_origin 不允许带凭据：$origin"
        val port = rest.substringAfterLast(':', missingDelimiterValue = "")
        if (port.toIntOrNull() == null) return "api_origin 缺少端口：$origin"
        return null
    }

    /**
     * 上游协议只保证 stdout 上一行 JSON；stdout 里其它行是历史遗留的 print（`redirect_stdout`
     * 只在 `cmd_gateway_ensure` 里生效）。取最后一个以 `{` 开头的行比取第一行稳。
     */
    private fun lastJsonLine(stdout: String): String? =
        stdout.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") && it.endsWith("}") }
            .lastOrNull()

    private fun isAbsolutePath(value: String): Boolean =
        value.startsWith("/") || value.startsWith("\\\\") || Regex("""^[A-Za-z]:[\\/]""").containsMatchIn(value)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull
}
