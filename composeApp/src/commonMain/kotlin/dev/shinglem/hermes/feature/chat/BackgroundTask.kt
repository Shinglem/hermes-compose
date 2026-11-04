package dev.shinglem.hermes.feature.chat

/**
 * 一个 agent 后台进程（`process.list` 的一行）。
 *
 * 注意 `process.list` 把**进程 id 放在 `session_id` 字段里**（registry 的命名），
 * `process.kill` 收的却是 `process_id` —— 这两个是同一个值，别按字段名理解成会话。
 */
data class BackgroundTask(
    val id: String,
    val command: String,
    val status: String,
    val exitCode: Int? = null,
    val uptimeSeconds: Long? = null,
    /** 后端给的输出快照（`output_tail`，最多 4000 字符；增量靠 `agent.terminal.output`）。 */
    val output: String = "",
    val cwd: String? = null,
) {
    val running: Boolean get() = status != STATUS_EXITED

    val failed: Boolean get() = !running && (exitCode ?: 0) != 0

    /** 列表里显示的标题：命令的**第一个非空行**（命令常以换行 / 缩进开头）。 */
    val title: String
        get() = command.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
            .ifBlank { "后台进程" }

    companion object {
        const val STATUS_EXITED = "exited"
    }
}

/** 本地保留的输出上限：轮询只给 4000 字符，实时增量可能更长。 */
internal const val BACKGROUND_OUTPUT_LIMIT = 64 * 1024

/**
 * 把 registry 快照合进当前列表。
 *
 * 规则（与官方 composer 状态栈一致）：
 * - 已有的行**保持位置**：状态原地翻转，不因为顺序变化而重排（重排会让列表抽动）；
 * - 新进程追加到末尾；
 * - [dismissed] 里的 id 不再出现，直到 registry 也不再报告它（那时从集合里清掉）；
 * - 输出以本地累积为准：只有新出现的任务才用快照初始化，避免轮询把实时增量覆盖成 4000 字符。
 */
internal fun reconcileBackgroundTasks(
    previous: List<BackgroundTask>,
    fresh: List<BackgroundTask>,
    dismissed: MutableSet<String>,
): List<BackgroundTask> {
    val reported = fresh.map { it.id }.toSet()
    dismissed.retainAll(reported)

    val freshById = fresh.associateBy { it.id }
    val kept = ArrayList<BackgroundTask>(previous.size + fresh.size)
    val seen = HashSet<String>(previous.size)

    previous.forEach { old ->
        val next = freshById[old.id] ?: return@forEach
        seen += old.id
        if (old.id in dismissed) return@forEach
        kept += if (old.output.isNotEmpty() && next.output != old.output) {
            next.copy(output = old.output)
        } else {
            next
        }
    }
    fresh.forEach { task ->
        if (task.id in seen || task.id in dismissed) return@forEach
        kept += task
    }
    return kept
}

/** 把一段实时输出追加到对应任务（没有该任务时原样返回）。 */
internal fun List<BackgroundTask>.appendOutput(processId: String, chunk: String): List<BackgroundTask> {
    if (chunk.isEmpty()) return this
    var changed = false
    val next = map { task ->
        if (task.id != processId) {
            task
        } else {
            changed = true
            task.copy(output = (task.output + chunk).takeLast(BACKGROUND_OUTPUT_LIMIT))
        }
    }
    return if (changed) next else this
}

/** 运行时长：`12s` / `3m 05s` / `1h 12m`。 */
fun formatUptime(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${(seconds % 60).toString().padStart(2, '0')}s"
    else -> "${seconds / 3600}h ${(seconds % 3600 / 60).toString().padStart(2, '0')}m"
}
