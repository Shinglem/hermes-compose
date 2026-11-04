package dev.shinglem.hermes.core.log

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 极早期日志：`<home>/logs/startup.log`。
 *
 * 存在的唯一理由：**配置解析失败时也要留下痕迹**。实测（2026-10-29）把 `config.json`
 * 写成坏 JSON 后用 `--silent` 启动，结果是「进程 0 个、日志目录一字节未变、home 下什么都没有」——
 * 因为 [dev.shinglem.hermes.MainKt] 的 `showFatal` 只写 `System.err`，而 `javaw` 没有控制台。
 * 开机自启（计划任务 + `--silent`）一旦出这种问题，用户看不到窗口、我们也无从查起。
 *
 * 三条纪律：
 * 1. **不经过 logback**：这时它还没初始化（日志目录都可能还没建）。
 * 2. **不依赖配置解析**：路径只用 `<home>` 推算，而 `<home>` 由 `-Dhdc.home` / exe 位置决定。
 * 3. **写失败就闭嘴**：磁盘只读之类的问题不能把启动搞失败。
 *
 * 并发说明：第二个实例也会先写几行再退出，所以这里有多个写者。都是「一次 append 一行」，
 * Windows 上 append 模式的小块写入是原子的，不会互相截断 —— 与 logback 的 rename 轮转不同，
 * 它没有"轮转期间文件被占用"的问题（本文件不轮转，只在超过阈值时整体覆写）。
 */
class StartupLog(home: File) {

    val file: File = File(File(home, "logs"), STARTUP_LOG)

    /** 记录 `<home>` 本身，便于在日志里直接看出这份安装在哪。 */
    val homePath: String = home.absolutePath

    private var usable = true

    init {
        rotateIfOversized()
    }

    fun info(tag: String, message: String) = write("INFO", tag, message)

    fun warn(tag: String, message: String) = write("WARN", tag, message)

    fun error(tag: String, message: String) = write("ERROR", tag, message)

    private fun write(level: String, tag: String, message: String) {
        if (!usable) return
        val line = "${TIMESTAMP.format(ZonedDateTime.now())}  ${level.padEnd(5)}  ${tag.padEnd(10)}  $message\n"
        val ok = runCatching {
            file.parentFile?.mkdirs()
            Files.writeString(
                file.toPath(),
                line,
                Charsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }.isSuccess
        if (!ok) usable = false
    }

    /** 超过 [MAX_BYTES] 就整体覆写：一次启动十几行，留太久的历史没有价值，但"最早那几行"要能一眼找到。 */
    private fun rotateIfOversized() {
        runCatching {
            if (file.isFile && file.length() > MAX_BYTES) {
                Files.writeString(
                    file.toPath(),
                    "${TIMESTAMP.format(ZonedDateTime.now())}  INFO   rotate      startup.log rotated\n",
                    Charsets.UTF_8,
                )
            }
        }
    }

    companion object {
        const val STARTUP_LOG = "startup.log"
        private const val MAX_BYTES = 256L * 1024
        private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    }
}
