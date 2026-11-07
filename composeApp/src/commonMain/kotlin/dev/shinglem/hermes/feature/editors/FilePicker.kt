package dev.shinglem.hermes.feature.editors

/**
 * 弹系统文件选择框选可执行文件；取消返回 null。
 *
 * AWT 的 `FileDialog` 会阻塞调用线程，所以**必须**在主线程之外调用
 * （desktop 实现里已经用 `withContext(Dispatchers.IO)` 包住）。
 */
expect suspend fun pickExecutableFile(): String?

/** 弹系统文件选择框选一个 JSON（会话导入用）；取消返回 null。 */
expect suspend fun pickJsonFile(): String?
