package dev.shinglem.hermes.core.log

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import org.slf4j.LoggerFactory

/**
 * 桌面端日志：**kotlin-logging 门面 + logback 后端**。
 *
 * 三个作用域各写一个滚动文件（`logback.xml` 里配置：10 MB × 10 份）：
 * `desktop.log` / `gateway.log` / `dashboard.log`。
 */
actual object Logging {
    private const val LOG_DIR_PROPERTY = "hermes.log.dir"

    private var logDir: File = File("logs").absoluteFile
    private var configured: Boolean = false
    private var stderrEcho: Boolean = false

    private val loggers = mutableMapOf<LogScope, AppLogger>()

    actual fun init(logDir: String, minLevel: LogLevel, alsoStderr: Boolean) {
        this.logDir = File(logDir).absoluteFile
        this.configured = true
        runCatching { this.logDir.mkdirs() }
        this.stderrEcho = alsoStderr

        // logback.xml 在类路径上，这里只把运行期目录喂给它（配置里用 ${hermes.log.dir}）。
        System.setProperty(LOG_DIR_PROPERTY, this.logDir.path)

        val context = LoggerFactory.getILoggerFactory() as? ch.qos.logback.classic.LoggerContext
        if (context != null) {
            val resource = javaClass.classLoader.getResource("logback.xml")
            if (resource != null) {
                val joran = ch.qos.logback.classic.joran.JoranConfigurator()
                joran.context = context
                runCatching { joran.doConfigure(resource) }
            }

            val level = minLevel.toLogback()
            // 只收紧客户端自身的 root 级别；gateway / dashboard 是后端子进程的原始输出，
            // 与客户端日志级别无关 —— 用户把 log_level 调到 WARN 也不该丢掉后端控制台。
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).level = level
        }
        loggers.clear()
    }

    actual val desktop: AppLogger get() = logger(LogScope.DESKTOP)

    actual val gateway: AppLogger get() = logger(LogScope.GATEWAY)

    actual val dashboard: AppLogger get() = logger(LogScope.DASHBOARD)

    private fun logger(scope: LogScope): AppLogger = loggers.getOrPut(scope) {
        // 未 init 就有人写日志（单测、极早期装配）：吞掉而不是崩，也别偷偷建文件。
        if (!configured) return@getOrPut NullLogger(scope)
        LogbackLogger(
            scope = scope,
            delegate = KotlinLogging.logger(scope.loggerName),
            logFilePath = File(logDir, "${scope.loggerName}.log").path,
            echoToStderr = { stderrEcho },
        )
    }

    private fun LogLevel.toLogback(): ch.qos.logback.classic.Level = when (this) {
        LogLevel.DEBUG -> ch.qos.logback.classic.Level.DEBUG
        LogLevel.INFO -> ch.qos.logback.classic.Level.INFO
        LogLevel.WARN -> ch.qos.logback.classic.Level.WARN
        LogLevel.ERROR -> ch.qos.logback.classic.Level.ERROR
    }
}

/** 未初始化时的空实现：日志装配之前发生的写入不该把程序带崩。 */
private class NullLogger(private val scope: LogScope) : AppLogger {
    override val logFilePath: String = ""

    override fun log(level: LogLevel, message: String, throwable: Throwable?) = Unit
}

/**
 * 把 [LogLevel] 映射到 kotlin-logging；`alsoStderr` 时额外回显到 stderr。
 *
 * 日志文件才是事实来源，控制台只是排障时的即时回显（开发用的 `Hermes-console.exe` /
 * `App-console.bat` 能看到它；成品只带 GUI 启动器，看不到控制台）。
 */
private class LogbackLogger(
    private val scope: LogScope,
    private val delegate: KLogger,
    override val logFilePath: String,
    private val echoToStderr: () -> Boolean,
) : AppLogger {

    override fun log(level: LogLevel, message: String, throwable: Throwable?) {
        when (level) {
            LogLevel.DEBUG -> delegate.debug(throwable) { message }
            LogLevel.INFO -> delegate.info(throwable) { message }
            LogLevel.WARN -> delegate.warn(throwable) { message }
            LogLevel.ERROR -> delegate.error(throwable) { message }
        }
        if (echoToStderr()) {
            val suffix = throwable?.let { " | ${it::class.java.name}: ${it.message}" }.orEmpty()
            System.err.println("${level.name.padEnd(5)} [$scope] $message$suffix")
        }
    }
}
