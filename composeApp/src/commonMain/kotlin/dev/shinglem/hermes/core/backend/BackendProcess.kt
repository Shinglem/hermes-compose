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
