package dev.shinglem.hermes.core.log

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** 日志作用域 → 三个独立文件（桌面端 / 后端 gateway / 后端 dashboard）。 */
enum class LogScope(val loggerName: String) {
    DESKTOP("desktop"),
    GATEWAY("gateway"),
    DASHBOARD("dashboard"),
}

/** 单个作用域的写入口（桌面端由 kotlin-logging + logback 实现）。 */
interface AppLogger {
    fun log(level: LogLevel, message: String, throwable: Throwable? = null)

    /** 设置页「打开日志目录」用；无文件输出时为空串。 */
    val logFilePath: String
}

/**
 * 日志装配：`desktop` / `gateway` / `dashboard` 三份 logger，各自的滚动文件。
 *
 * 为什么要分开：后端两个进程（serve / dashboard）的输出与客户端自身的问题混在一个文件里，
 * 排查时永远要人肉过滤；分开后「后端崩了」和「客户端逻辑错了」一眼可辨。
 */
expect object Logging {
    /** 必须在任何 logger 使用前调用一次。`logDir` 由 `config/app.log.dir` 决定。 */
    fun init(logDir: String, minLevel: LogLevel, alsoStderr: Boolean)

    val desktop: AppLogger

    val gateway: AppLogger

    val dashboard: AppLogger
}

/** 客户端自身日志的便捷门面（保持既有调用点不变）。 */
object Log {
    fun debug(message: String) = Logging.desktop.log(LogLevel.DEBUG, message, null)

    fun info(message: String) = Logging.desktop.log(LogLevel.INFO, message, null)

    fun warn(message: String, throwable: Throwable? = null) =
        Logging.desktop.log(LogLevel.WARN, message, throwable)

    fun error(message: String, throwable: Throwable? = null) =
        Logging.desktop.log(LogLevel.ERROR, message, throwable)

    /** 后端子进程（`serve`）的控制台行。 */
    fun gateway(line: String) = Logging.gateway.log(LogLevel.INFO, line, null)

    /** 后端子进程（`dashboard`）的控制台行。 */
    fun dashboard(line: String) = Logging.dashboard.log(LogLevel.INFO, line, null)
}
