package dev.shinglem.hermes.core.app

import dev.shinglem.hermes.core.backend.BackendProvider
import dev.shinglem.hermes.core.log.Log
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 用**当前进程自己的命令行**重启：`ProcessHandle.current().info()` 给出的
 * `command()` + `arguments()` 里已经包含 JVM 路径、`-D` 系统属性、`-cp`、主类，
 * 比手工拼「启动器 + 参数」更保真，也不依赖用户是双击 `Hermes.exe` 还是 `App.bat`。
 *
 * 顺序是先停后端、再起新进程：新旧两个进程的后端都以同一个 `HERMES_HOME` 为根，
 * 旧进程的 Job Object 也会在它退出时杀掉自己的后端 —— 让新进程先起后端再被打断，
 * 只会白等一轮就绪超时。
 */
class DesktopAppRestarter(
    private val backend: BackendProvider,
    /** 新进程的输出落这里：秒退时能直接看到原因，不用靠猜。 */
    private val logDir: File,
) : AppRestarter {

    override val support: RestartSupport = detectSupport()

    override suspend fun restart(): Boolean {
        if (support != RestartSupport.SUPPORTED) return false

        val info = ProcessHandle.current().info()
        val commandLine = buildCurrentCommandLine(info)
        if (commandLine.isEmpty()) {
            Log.warn("重启失败：拿不到当前进程的命令行")
            return false
        }
        // `--silent` / `--tray` 是"开机自启不弹窗"，用户此刻正对着设置页点按钮，
        // 重启后必须能看到窗口，所以剥掉。`--successor-pid` 也要剥：这次的交接目标是我自己。
        val filtered = RestartHandover.strip(commandLine).filterNot {
            it.equals("--silent", ignoreCase = true) || it.equals("--tray", ignoreCase = true)
        }
        // 单实例是强制的，而我现在还持有互斥量：把 PID 交给新进程，让它等我退出再占位。
        val launchArgs = filtered + RestartHandover.argument(ProcessHandle.current().pid())

        backend.shutdown()

        val outFile = File(logDir, "restart.log").apply { parentFile?.mkdirs() }
        Log.info("重启命令行（${launchArgs.size} 项）：" + launchArgs.joinToString(" | ") { it.take(70) })
        val started = runCatching {
            ProcessBuilder(launchArgs)
                .directory(File(System.getProperty("user.dir").orEmpty()))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(outFile))
                .start()
        }.getOrElse { failure ->
            Log.error("重启失败：拉起新进程时出错", failure)
            return false
        }

        // 必须确认新进程真的活着再退出：否则类路径/参数有问题时，旧窗口关了、新窗口没起来，
        // 用户面对的是"程序消失了"。
        withContext(Dispatchers.IO) {
            runCatching { started.waitFor(NEW_PROCESS_GRACE_MS, TimeUnit.MILLISECONDS) }
        }
        if (started.isAlive) {
            Log.info("已拉起新进程（pid=${started.pid()}），当前进程准备退出")
            return true
        }
        Log.error("重启失败：新进程启动后立即退出（exit=${started.exitValue()}），详见 ${outFile.absolutePath}")
        return false
    }

    /** 当前进程的完整命令行（exe 在最前）。优先 `ProcessHandle`，为空时回落到 Win32。 */
    private fun buildCurrentCommandLine(info: ProcessHandle.Info): List<String> {
        val command = info.command().orElse(null)
        val args = info.arguments().orElse(emptyArray())
        if (command != null && args.isNotEmpty()) return listOf(command) + args
        WindowsCommandLine.current()?.let { argv ->
            if (argv.isNotEmpty()) return argv
        }
        return command?.let { listOf(it) } ?: emptyList()
    }

    private fun detectSupport(): RestartSupport {
        val location = runCatching {
            DesktopAppRestarter::class.java.protectionDomain?.codeSource?.location
        }.getOrNull() ?: return RestartSupport.UNSUPPORTED
        val file = runCatching { File(location.toURI()) }.getOrNull() ?: return RestartSupport.UNSUPPORTED
        // 成品里是 `bin/App.jar`；`gradlew run` 是 build/classes/... 目录，命令行属于 Gradle，
        // 照抄会启动一个 Gradle 任务而不是客户端。
        return if (file.isFile && file.name.endsWith(".jar")) RestartSupport.SUPPORTED else RestartSupport.DEV_MODE
    }

    private companion object {
        /** 新进程至少活这么久才认为它起来了（够耗过类加载与配置解析）。 */
        const val NEW_PROCESS_GRACE_MS = 2_500L
    }
}
