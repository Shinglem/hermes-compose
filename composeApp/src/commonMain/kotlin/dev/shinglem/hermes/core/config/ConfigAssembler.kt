package dev.shinglem.hermes.core.config

import dev.shinglem.hermes.core.log.LogLevel
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 把「基础文件 + 用户覆盖文件 + `-D` / 环境变量」合成一个不可变的 [AppConfig]。
 *
 * 全部在 `JsonObject` 层完成合并与覆盖，只在最后 decode 一次。这样一条错误路径
 * 就能覆盖所有来源，而不是为每个来源各写一套解析。
 */
class ConfigAssembler(
    private val source: ConfigSource = ConfigSource.EMPTY,
    private val warn: (String) -> Unit = {},
) {
    private val knownKeys: Set<String> = knownConfigKeyPaths(serializerDescriptorOfConfig())

    fun assemble(base: JsonObject, custom: JsonObject?): AppConfig {
        warnUnknownKeys(base, BASE_FILE)
        if (custom != null) warnUnknownKeys(custom, CUSTOM_FILE)
        warnUnknownEnvNames()

        val merged = if (custom == null) base else deepMergeJson(base, custom)
        val (withOverrides, applied) = applyOverrides(merged)
        val config = decode(withOverrides, applied)
        ConfigValidator.validate(config)
        return config
    }

    private fun warnUnknownKeys(node: JsonObject, fileName: String) {
        val (retired, unknown) = flattenJsonKeys(node).partition { it in RETIRED_KEYS }
        retired.forEach { warn("已废弃的配置键: $it（${RETIRED_KEYS[it]}；$fileName）") }
        unknown
            .filter { it !in knownKeys }
            .forEach { warn("未知配置键: $it（$fileName，已忽略）") }
    }

    /**
     * 拼错的 `HDC_*` 会被静默忽略（我们只按已知键去取环境变量），所以这里主动扫一遍，
     * 让"设了变量却没生效"至少留下一条痕迹。
     */
    private fun warnUnknownEnvNames() {
        val knownEnvNames: Set<String> = knownKeys.mapTo(mutableSetOf()) { keyPathToEnvName(it) } +
            RETIRED_KEYS.keys.map { keyPathToEnvName(it) } +
            setOf(ConfigEnvNames.HOME, ConfigEnvNames.CONFIG_DIR, ConfigEnvNames.NO_GUI_DIALOG)
        source.envNames()
            .filter { it.startsWith(ConfigEnvNames.PREFIX) && it !in knownEnvNames }
            .sorted()
            .forEach { warn("未知环境变量: $it（已忽略，检查是否拼错）") }
    }

    private fun applyOverrides(root: JsonObject): Pair<JsonObject, List<ConfigOverride>> {
        var current = root
        val applied = mutableListOf<ConfigOverride>()
        val rootDescriptor = serializerDescriptorOfConfig()

        for (keyPath in knownKeys) {
            val propertyName = keyPathToPropertyName(keyPath)
            val envName = keyPathToEnvName(keyPath)
            val fromProperty = source.property(propertyName)
            val fromEnv = source.env(envName)
            val raw = fromProperty ?: fromEnv ?: continue
            val origin = if (fromProperty != null) "-D$propertyName" else envName

            val descriptor = descriptorAt(rootDescriptor, keyPath)
                ?: throw ConfigError("$origin 对应的键 $keyPath 不在配置模型里")

            current = setJsonAt(current, keyPath, parseConfigValue(raw, descriptor, origin))
            applied += ConfigOverride(keyPath, origin, raw)
        }
        return current to applied
    }

    private fun decode(root: JsonObject, applied: List<ConfigOverride>): AppConfig =
        try {
            ConfigJson.decodeFromJsonElement(AppConfig.serializer(), root)
        } catch (failure: SerializationException) {
            throw ConfigError(buildString {
                append("配置解析失败：").append(failure.message ?: failure::class.java.simpleName)
                if (applied.isNotEmpty()) {
                    append("\n以下项来自启动参数 / 环境变量，请先核对它们：")
                    applied.forEach { append("\n  ").append(it.origin).append(" = ").append(it.rawValue).append("（→ ").append(it.keyPath).append("）") }
                }
            }, failure)
        }

    companion object {
        const val BASE_FILE = "config.json"
        const val CUSTOM_FILE = "config_custom.json"

        /**
         * 曾是配置项、后来从 schema 移除的键。残留时给一句明确的说法，
         * 不要混在「未知配置键（拼错了？）」里 —— 那会让人以为是自己写错了。
         */
        val RETIRED_KEYS: Map<String, String> = mapOf(
            "app.single_instance" to "桌面端强制单实例，已不可关闭，见 08 篇 §13",
        )
    }
}

/**
 * 语义校验。解析成功不等于配置合理 —— 「能解析」和「能用」之间还有范围与前提。
 *
 * 这里**一律抛 [ConfigError]**，不做静默兜底：配置是最该大声失败的地方
 * （与能力页那次「0 个技能」同一教训）。
 */
object ConfigValidator {
    /** 冷启动下限。低于它必然误判超时（实测 30–60s 是正常冷启动）。 */
    const val MIN_READY_TIMEOUT_MS = 45_000

    /** 固定后端凭据的最短长度。太短的 token 在局域网里等于没有鉴权。 */
    const val MIN_LOCAL_TOKEN_LENGTH = 16

    /** 交互式请求下限。给太小会把正常慢响应误判成失败。 */
    const val MIN_REQUEST_TIMEOUT_MS = 5_000L

    /** `prompt.submit` 的 ack 下限 —— 一轮对话靠 event 通知完成，短了会杀掉长回答。 */
    const val MIN_PROMPT_ACK_TIMEOUT_MS = 60_000L

    fun validate(config: AppConfig) {
        if (config.version > AppConfig.CURRENT_VERSION) {
            throw ConfigError(
                "配置版本 ${config.version} 高于本程序支持的 ${AppConfig.CURRENT_VERSION}，请升级程序或改用兼容的 config.json",
            )
        }

        // local 模式已实现（2026-10-28）：payload 与 home 的存在性/归属在启动时校验，
        // 不在这里拦 —— 配置合法但目录缺失时，用户需要看到的是启动页的具体原因，而不是一句「配置错误」。
        if (config.backend.mode == BackendMode.LOCAL) {
            val local = config.backend.local
            if (local.hermesDir.isBlank()) {
                throw ConfigError("backend.local.hermes_dir 不能为空（默认 \"hermes\"，相对 <home>）")
            }
            if (local.homeDir.isBlank()) {
                throw ConfigError("backend.local.home_dir 不能为空（默认 \"hermes-home\"，相对 <home>）")
            }
            if (local.host.isBlank()) {
                throw ConfigError("backend.local.host 不能为空（默认 127.0.0.1，只接受本机连接）")
            }
            if (local.port !in 0..65535) {
                throw ConfigError("backend.local.port 必须在 0–65535 之间（0 = 由系统分配），当前是 ${local.port}")
            }
            local.token?.takeIf { it.isNotBlank() }?.let { token ->
                if (token.length < MIN_LOCAL_TOKEN_LENGTH) {
                    throw ConfigError(
                        "backend.local.token 至少 $MIN_LOCAL_TOKEN_LENGTH 个字符（留空则每次启动随机生成）",
                    )
                }
                if (token.any { it.isWhitespace() }) {
                    throw ConfigError("backend.local.token 不能包含空白字符")
                }
            }
        }

        val remote = config.backend.remote
        if (!remote.baseUrl.startsWith("http://") && !remote.baseUrl.startsWith("https://")) {
            throw ConfigError("backend.remote.base_url 必须是 http:// 或 https:// 开头的地址，当前是 \"${remote.baseUrl}\"")
        }
        if (remote.connectTimeoutMs <= 0) {
            throw ConfigError("backend.remote.connect_timeout_ms 必须大于 0，当前是 ${remote.connectTimeoutMs}")
        }
        // ENV_FILE 的 value 允许为空：表示按「环境变量 → $HERMES_HOME/.env → ~/.hermes/.env」的
        // 内置顺序找，这也是本机开发态的常见情形。INLINE / ENV 则必须写明。
        when (remote.token.source) {
            TokenSource.INLINE -> if (remote.token.value.isNullOrBlank()) {
                throw ConfigError("backend.remote.token.source = \"inline\" 时必须提供 backend.remote.token.value")
            }

            TokenSource.ENV -> if (remote.token.value.isNullOrBlank()) {
                throw ConfigError(
                    "backend.remote.token.source = \"env\" 时 backend.remote.token.value 必须是环境变量名",
                )
            }

            TokenSource.ENV_FILE -> Unit
        }

        if (config.backend.local.readyTimeoutMs < MIN_READY_TIMEOUT_MS) {
            throw ConfigError(
                "backend.local.ready_timeout_ms 不能小于 $MIN_READY_TIMEOUT_MS（冷启动 30–60s 是正常的），当前是 ${config.backend.local.readyTimeoutMs}",
            )
        }

        val level = config.app.logLevel.trim().uppercase()
        if (LogLevel.entries.none { it.name == level }) {
            throw ConfigError(
                "app.log_level = \"${config.app.logLevel}\" 无效，可选：${LogLevel.entries.joinToString(" / ") { it.name }}",
            )
        }

        if (config.app.log.keepDays <= 0) {
            throw ConfigError("app.log.keep_days 必须大于 0，当前是 ${config.app.log.keepDays}")
        }
        if (config.app.log.maxFiles <= 0) {
            throw ConfigError("app.log.max_files 必须大于 0，当前是 ${config.app.log.maxFiles}")
        }

        val window = config.app.window
        if (window.width <= 0 || window.height <= 0) {
            throw ConfigError("app.window.width / height 必须大于 0，当前是 ${window.width}x${window.height}")
        }
        if (window.minWidth <= 0 || window.minHeight <= 0) {
            throw ConfigError("app.window.min_width / min_height 必须大于 0")
        }
        if (window.minWidth > window.width || window.minHeight > window.height) {
            throw ConfigError(
                "app.window 的最小尺寸不能大于初始尺寸（min ${window.minWidth}x${window.minHeight} > ${window.width}x${window.height}）",
            )
        }
    }

    /** `options.json` 的语义校验。 */
    fun validateOptions(options: AppOptions) {
        if (options.version > AppOptions.CURRENT_VERSION) {
            throw ConfigError(
                "选项版本 ${options.version} 高于本程序支持的 ${AppOptions.CURRENT_VERSION}，请升级程序",
            )
        }
        val network = options.network
        if (network.requestTimeoutMs < MIN_REQUEST_TIMEOUT_MS) {
            throw ConfigError(
                "network.request_timeout_ms 不能小于 $MIN_REQUEST_TIMEOUT_MS，当前是 ${network.requestTimeoutMs}",
            )
        }
        if (network.writeTimeoutMs < MIN_REQUEST_TIMEOUT_MS) {
            throw ConfigError(
                "network.write_timeout_ms 不能小于 $MIN_REQUEST_TIMEOUT_MS，当前是 ${network.writeTimeoutMs}",
            )
        }
        if (network.promptAckTimeoutMs < MIN_PROMPT_ACK_TIMEOUT_MS) {
            throw ConfigError(
                "network.prompt_ack_timeout_ms 不能小于 $MIN_PROMPT_ACK_TIMEOUT_MS" +
                    "（一轮对话完成靠 event 通知，短超时会把长回答误判为失败），当前是 ${network.promptAckTimeoutMs}",
            )
        }
    }
}
