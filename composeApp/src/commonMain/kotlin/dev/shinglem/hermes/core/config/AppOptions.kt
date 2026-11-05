package dev.shinglem.hermes.core.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * `config/options.json` —— 用户可感、**改完立即生效**的选项。
 *
 * 与 `config.json` 的分界见 `05-打包与配置.md` §3：改了要重启的进 `config.json`，
 * 能热生效的进这里。
 *
 * ⚠️ **只放当前真能生效的字段**。解析了但不消费，等于给用户一个"改了没反应"的假开关
 * —— 那正是我们一直在避免的坑。`font_scale` / `density` / `chat.*` 等 UI 项
 * 要等 P6 设置页真正实现后再加进来（加字段容易，去掉难）。
 */
@Serializable
data class AppOptions(
    val version: Int = CURRENT_VERSION,
    val ui: UiOptions = UiOptions(),
    val network: NetworkOptions = NetworkOptions(),
    /** 外部编辑器（点工具卡片旁的「打开」用哪个程序）。 */
    val editors: EditorsOptions = EditorsOptions(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class UiOptions(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** 界面配色风格：material = M3 默认；paper = Outside Paper（暖白纸面 + 砖红强调）。 */
    val style: ThemeStyle = ThemeStyle.MATERIAL,
    /** Markdown 配色：follow = 跟随 [style]；material / paper = 独立指定。 */
    @SerialName("markdown_style") val markdownStyle: MarkdownStyle = MarkdownStyle.FOLLOW,
)

@Serializable
enum class ThemeMode {
    @SerialName("system") SYSTEM,
    @SerialName("light") LIGHT,
    @SerialName("dark") DARK,
}

/**
 * 界面配色风格。与 Markdown 配色解耦：Markdown 由 `options.ui.markdown_style` 单独选择
 * （`follow` = 跟随本风格），见 `dev.shinglem.hermes.markdown.MarkdownText`。
 *
 * 除 `material` 外，每种风格都有自己的亮 / 暗色板（`ui.theme` 决定明暗）。
 */
@Serializable
enum class ThemeStyle {
    @SerialName("material") MATERIAL,
    @SerialName("paper") PAPER,
    @SerialName("ocean") OCEAN,
    @SerialName("sage") SAGE,
    @SerialName("plum") PLUM,
    @SerialName("amber") AMBER,
}

/** Markdown 配色。`follow` 跟随 [ThemeStyle]，其余两项把两者解耦。 */
@Serializable
enum class MarkdownStyle {
    @SerialName("follow") FOLLOW,
    @SerialName("material") MATERIAL,
    @SerialName("paper") PAPER,
}

/** 超时可热改：下一次请求就按新值走（已在飞的那次不重算）。 */
@Serializable
data class NetworkOptions(
    @SerialName("request_timeout_ms") val requestTimeoutMs: Long = 30_000,
    @SerialName("write_timeout_ms") val writeTimeoutMs: Long = 45_000,
    /** `prompt.submit` 的 ack 超时；下限 60s（AGENTS.md 硬约束 3 的由来）。 */
    @SerialName("prompt_ack_timeout_ms") val promptAckTimeoutMs: Long = 1_800_000,
)

/**
 * 进程级单例，供"最新一次请求"现取。
 *
 * `Timeouts` 之类的调用点保持原样（`Timeouts.DEFAULT_MS`），只是从常量变成 getter
 * —— 业务代码零改动，却能在文件改动后立刻生效。
 */
object AppOptionsHolder {
    private val _state = MutableStateFlow(AppOptions())
    val state: StateFlow<AppOptions> = _state.asStateFlow()

    val current: AppOptions get() = _state.value

    fun set(options: AppOptions) {
        _state.value = options
    }

    /** 局部更新（改一个字段不用整份重建）。 */
    fun update(transform: (AppOptions) -> AppOptions) {
        _state.value = transform(_state.value)
    }

    /** 测试用：恢复默认。 */
    fun reset() {
        _state.value = AppOptions()
    }
}

/**
 * 解析并校验 `options.json`。与 [ConfigAssembler] 用同一套 `ConfigJson` 与未知键策略，
 * 但**不做 `-D`/env 覆盖** —— options 是用户在界面上调的东西，不是部署面配置。
 */
fun parseAppOptions(text: String, warn: (String) -> Unit = {}): AppOptions {
    val root = ConfigJson.parseToJsonElement(text) as? JsonObject
        ?: throw ConfigError("options.json 的顶层必须是 JSON 对象")
    return parseAppOptions(root, warn)
}

/** 已合并（`options.json` + `options_custom.json`）之后的解析入口。 */
fun parseAppOptions(root: JsonObject, warn: (String) -> Unit = {}): AppOptions {
    val known = knownConfigKeyPaths(AppOptions.serializer().descriptor)
    flattenJsonKeys(root)
        .filter { it !in known }
        .forEach { warn("未知选项: $it（options.json，已忽略）") }
    val options = ConfigJson.decodeFromJsonElement(AppOptions.serializer(), root)
    ConfigValidator.validateOptions(options)
    return options
}
