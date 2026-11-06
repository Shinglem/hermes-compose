package dev.shinglem.hermes.core.backend

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 用 [ProcessBuilder] 拉起后端子进程，**前台**运行并收集控制台输出。
 *
 * 三条纪律：
 * 1. `redirectErrorStream(true)`：stdout/stderr 并成一个管道，单线程读 —— 两个管道都要读，
 *    漏一个就会因缓冲区满把子进程憋死。
 * 2. 环境变量只给白名单（见 `LocalBackendPlanner`），不整份继承，免得 `HDC_*`、代理、云凭据漏进去。
 * 3. 关停只用本句柄（含 `descendants()`）—— 不扫进程表、不按名字杀，物理上碰不到别的 Hermes。
 * 4. 进程树同时绑进 Windows Job Object（[BackendProcessJob]）：客户端被强杀时后端不会留孤儿。
 */
class DesktopBackendLauncher : BackendLauncher {
    override fun launch(spec: BackendLaunchSpec): BackendProcess = DesktopBackendProcess(spec)
}

class DesktopBackendProcess(private val spec: BackendLaunchSpec) : BackendProcess {

    private val builder = ProcessBuilder(listOf(spec.exe) + spec.args).apply {
        directory(File(spec.workDir))
        redirectErrorStream(true)
        environment().clear()
        environment().putAll(spec.env)
    }

    private val process: Process = builder.start().also { started ->
        // 内核级兜底：客户端被强杀/崩溃时，后端自带的 watchdog 在 Windows 上判不出来
        // （它只看父进程创建时间），Job Object 会替我们杀掉整棵子树。失败只影响兜底。
        BackendProcessJob.assign(started)
    }

    /** 子进程 PID，日志里带上便于排查；父进程 watchdog 由后端自己按 env 里的 PID 判定。 */
    val pid: Long get() = process.pid()

    override fun lines(): Flow<BackendLogLine> = callbackFlow {
        val pump = thread(isDaemon = true, name = "hermes-backend-out") {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { trySend(BackendLogLine("out", it)) }
                }
            } catch (_: Throwable) {
                // 进程被强杀时 readLine 会抛 —— 关流即可，不当错误上报。
            } finally {
                close()
            }
        }
        awaitClose { pump.interrupt() }
    }

    override fun isAlive(): Boolean = process.isAlive

    override fun terminate(graceMs: Long) {
        if (!process.isAlive) return
        // 先请子进程树一起退出：后端会拉工具子进程，只杀根节点会留孤儿。
        process.descendants().forEach { handle -> runCatching { handle.destroy() } }
        process.destroy()
        if (runCatching { process.waitFor(graceMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)) return

        process.descendants().forEach { handle -> runCatching { handle.destroyForcibly() } }
        process.destroyForcibly()
        runCatching { process.waitFor(graceMs, TimeUnit.MILLISECONDS) }
    }
}

/**
 * 一次性执行（`gateway ensure` / `gateway stop`）。
 *
 * **刻意不把进程绑进 Job Object**：`ensure` 拉起的 gateway daemon 用
 * `CREATE_BREAKAWAY_FROM_JOB` 脱离父作业，而 Windows 只允许在作业显式设置
 * `JOB_OBJECT_LIMIT_BREAKAWAY_OK` 时 breakaway。把 ensure 塞进我们那个不允许 breakaway 的作业，
 * 就等于让 daemon 永远起不来 —— 这是这条路线最隐蔽的坑。
 *
 * stdout / stderr 分两路各起一个读线程：只读一路会让另一路的管道缓冲区写满，把子进程憋死。
 */
object DesktopOneShotRunner : OneShotRunner {

    override fun run(spec: BackendLaunchSpec): OneShotResult {
        val builder = ProcessBuilder(listOf(spec.exe) + spec.args).apply {
            directory(File(spec.workDir))
            redirectErrorStream(false)
            environment().clear()
            environment().putAll(spec.env)
        }
        val process = builder.start()

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outPump = thread(isDaemon = true, name = "hermes-oneshot-out") {
            runCatching {
                process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { stdout.appendLine(it) }
            }
        }
        val errPump = thread(isDaemon = true, name = "hermes-oneshot-err") {
            runCatching {
                process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { stderr.appendLine(it) }
            }
        }

        val finished = runCatching {
            process.waitFor(spec.timeoutMs, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!finished) {
            process.descendants().forEach { runCatching { it.destroyForcibly() } }
            process.destroyForcibly()
            runCatching { process.waitFor(3, TimeUnit.SECONDS) }
        }
        // 读线程是 daemon：进程结束后它们会自行收尾，但这里等一下才能拿到完整输出。
        outPump.join(2_000)
        errPump.join(2_000)

        return OneShotResult(
            exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1,
            stdout = stdout.toString(),
            stderr = stderr.toString(),
        )
    }
}

/** ready 文件与 payload 探测走 `java.io.File`；只在 desktop 目标使用。 */object DesktopFsProbe : FsProbe {
    override fun exists(path: String): Boolean = path.isNotBlank() && File(path).exists()

    override fun readTextOrNull(path: String): String? = runCatching {
        val file = File(path)
        if (!file.isFile) null else file.readText(Charsets.UTF_8)
    }.getOrNull()

    override fun deleteIfExists(path: String) {
        if (path.isBlank()) return
        runCatching { File(path).delete() }
    }

    override fun writeText(path: String, text: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    override fun appendText(path: String, text: String) {
        val file = File(path)
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(text + System.lineSeparator(), Charsets.UTF_8)
        }
    }

    override fun mkdirs(path: String) {
        if (path.isBlank()) return
        File(path).mkdirs()
    }
}
