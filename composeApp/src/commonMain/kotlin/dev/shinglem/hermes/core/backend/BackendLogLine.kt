package dev.shinglem.hermes.core.backend

/**
 * 后端输出的一行。`stream` 是 `out` / `err`（两者都合并进了同一管道，见 desktop 实现），
 * 保留来源只为日志可读性。
 */
data class BackendLogLine(
    val stream: String,
    val text: String,
)

/** 只保留最后 [capacity] 行，避免长跑把内存吃满。 */
class BackendLogBuffer(private val capacity: Int = 500) {
    private val ring = ArrayDeque<BackendLogLine>(capacity)

    fun append(line: BackendLogLine): List<BackendLogLine> {
        while (ring.size >= capacity) ring.removeFirst()
        ring.addLast(line)
        return snapshot()
    }

    fun snapshot(): List<BackendLogLine> = ring.toList()

    fun clear(): List<BackendLogLine> {
        ring.clear()
        return emptyList()
    }
}
