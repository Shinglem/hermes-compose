package dev.shinglem.hermes.core.config

/** 客户端自己的环境变量/属性前缀与几个引导变量名（**不带 `hermes` 字样**，避免污染后端子进程）。 */
object ConfigEnvNames {
    const val PREFIX = "HDC_"
    const val HOME = "HDC_HOME"
    const val CONFIG_DIR = "HDC_CONFIG_DIR"

    /** 自动化/CI 里设它跳过阻塞式错误对话框（见 `Main.kt`）。 */
    const val NO_GUI_DIALOG = "HDC_NO_GUI_DIALOG"
}

/**
 * 配置的两个覆盖通道（`-D` 与进程环境变量）。
 *
 * 做成接口是为了测试能注入固定输入 —— 直接读 `System.getenv` 的代码没法单测，
 * 而配置层恰恰是最需要单测的地方（GUI 与 IME 在这台机器上都不可靠）。
 */
interface ConfigSource {
    /** JVM 系统属性，键形如 `hdc.backend.remote.base_url`。 */
    fun property(key: String): String?

    /** 环境变量，键形如 `HDC_BACKEND_REMOTE_BASE_URL`。 */
    fun env(key: String): String?

    /** 现有环境变量的名字集合，用于发现拼错的 `HDC_*`。默认空（不支持枚举的来源）。 */
    fun envNames(): Set<String> = emptySet()

    /** 现有系统属性的名字集合。 */
    fun propertyNames(): Set<String> = emptySet()

    companion object {
        /** 什么都不提供的空实现（测试默认值解析时用）。 */
        val EMPTY: ConfigSource = object : ConfigSource {
            override fun property(key: String): String? = null
            override fun env(key: String): String? = null
        }
    }
}

/** 测试与嵌入场景用：直接喂两张表。 */
class MapConfigSource(
    private val properties: Map<String, String> = emptyMap(),
    private val envs: Map<String, String> = emptyMap(),
) : ConfigSource {
    override fun property(key: String): String? = properties[key]
    override fun env(key: String): String? = envs[key]
    override fun envNames(): Set<String> = envs.keys
    override fun propertyNames(): Set<String> = properties.keys
}

/** 一次单点覆盖的记录：错误提示要能指出「这个值来自哪个变量」。 */
data class ConfigOverride(
    val keyPath: String,
    val origin: String,
    val rawValue: String,
)
