package dev.shinglem.hermes.feature.editors

import dev.shinglem.hermes.core.log.Log
import java.io.File

/**
 * 起外部编辑器。
 *
 * 三条都踩过：
 * - **不经过 shell**：`ProcessBuilder` 直接收参数列表，路径里的空格/引号/`&` 都不会被解释，
 *   也杜绝了参数注入（模板层的 `-` 前缀检查是第二道闸）。
 * - **`.cmd` / `.bat` 必须包一层 `cmd /c`**：VSCode 的 `bin/code.cmd` 是批处理，
 *   JVM 直接 `ProcessBuilder("code.cmd")` 会 `CreateProcess error=193`（不是有效的 Win32 程序）。
 * - **不等它退出**：编辑器是独立进程，`waitFor()` 会把 UI 线程挂住直到用户关掉编辑器。
 */
actual fun launchEditorProcess(exePath: String, args: List<String>): Boolean {
    val exe = File(exePath)
    if (!exe.isFile) {
        Log.warn("外部编辑器不存在：$exePath")
        return false
    }
    return runCatching {
        val command = buildList {
            if (exe.extension.lowercase() in BATCH_EXTENSIONS) {
                add(System.getenv("ComSpec") ?: "cmd.exe")
                add("/c")
            }
            add(exe.absolutePath)
            addAll(args)
        }
        ProcessBuilder(command)
            .redirectErrorStream(false)
            // 编辑器自己会开窗口，这里把输出丢掉即可（管道没人读会写满 64 KiB 卡住子进程）
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        Log.info("已用外部编辑器打开：${exe.name} ${args.joinToString(" ")}")
        true
    }.getOrElse { error ->
        Log.warn("启动外部编辑器失败：$exePath — ${error.message}")
        false
    }
}

private val BATCH_EXTENSIONS = setOf("cmd", "bat")
