package dev.shinglem.hermes.core.backend

import kotlinx.coroutines.flow.Flow

/**
 * 一个已拉起的后端子进程。实现放 `desktopMain`（只有 `jvm("desktop")` 目标，
 * 用接口注入而非 `expect/actual`，测试可直接塞假实现）。
 */
interface BackendProcess {
    /** 合并后的 stdout+stderr（`redirectErrorStream`），逐行回调。 */
    fun lines(): Flow<BackendLogLine>

    fun isAlive(): Boolean

    /** 三层关停：子进程树 → 优雅 → 强杀；**只作用于本句柄**，不扫进程表。 */
    fun terminate(graceMs: Long = 3_000)
}

interface BackendLauncher {
    fun launch(spec: BackendLaunchSpec): BackendProcess
}

/** 一次性命令的结果（`gateway ensure` / `gateway stop` 这类「跑完就退」的调用）。 */
data class OneShotResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    /** 上游把协议数据放 stdout、把日志放 stderr，所以诊断只取 stderr 尾部。 */
    fun stderrTail(lines: Int = 8): String? =
        stderr.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            .takeLast(lines).joinToString("\n").ifBlank { null }
}

/**
 * 阻塞式一次性执行。与 [BackendLauncher] 分开：那个返回长期存活的进程句柄，
 * 这个只关心「跑完、退出码、输出」，且必须在 [BackendLaunchSpec.timeoutMs] 内结束。
 */
interface OneShotRunner {
    fun run(spec: BackendLaunchSpec): OneShotResult
}

/** ready 文件与 payload 的存在性探测（desktop 用 `java.io.File` 实现）。 */
interface FsProbe {
    fun exists(path: String): Boolean

    fun readTextOrNull(path: String): String?

    fun deleteIfExists(path: String)

    fun writeText(path: String, text: String)

    /** 追加一行（后端控制台日志落盘）。 */
    fun appendText(path: String, text: String)

    /** 写归属标记之类需要先建目录的场景。 */
    fun mkdirs(path: String)
}
