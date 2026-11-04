package dev.shinglem.hermes.core.app

/**
 * 重启时的实例交接。
 *
 * 单实例是强制的，而「重启客户端」是**旧进程还活着**的时候拉起新进程的
 * （它必须确认新进程起来了才能关自己的窗口）。这段窗口里旧进程仍持有
 * `Local\HermesCompose-<hash>-instance` 互斥量 —— 新进程直接探测就会被当成第二实例，
 * 去唤醒那个正要退出的旧进程，然后自己也退出，结果是**程序消失**。
 *
 * 所以旧进程把自己的 PID 作为 `--successor-pid=` 传给新进程，新进程先等它退出
 * （退出即释放互斥量）再占位。参数只在这条路径上出现，用户手动双击永远没有。
 */
internal object RestartHandover {

    const val ARG_PREFIX = "--successor-pid="

    /** 旧进程最多等这么久：覆盖它"确认新进程存活 2.5 s"的窗口还有富余。 */
    const val DEFAULT_TIMEOUT_MS = 15_000L

    private const val POLL_MS = 100L

    fun argument(pid: Long): String = "$ARG_PREFIX$pid"

    fun parse(args: List<String>): Long? = args
        .firstOrNull { it.startsWith(ARG_PREFIX, ignoreCase = true) }
        ?.substring(ARG_PREFIX.length)
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it > 0 }

    /** 去掉可能残留的交接参数：重复重启时不累积，提权重启时也不必再等一次。 */
    fun strip(args: List<String>): List<String> =
        args.filterNot { it.startsWith(ARG_PREFIX, ignoreCase = true) }

    /**
     * 等 [pid] 退出。返回 `true` 表示可以安全占位（进程已不在，或从来就不在）。
     * 对自己调用直接返回 `true`，否则会自锁到超时。
     */
    fun awaitExit(pid: Long, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        if (pid <= 0 || pid == currentPid()) return true
        val handle = ProcessHandle.of(pid).orElse(null) ?: return true
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (!handle.isAlive) return true
            Thread.sleep(POLL_MS)
        }
        return !handle.isAlive
    }

    private fun currentPid(): Long = ProcessHandle.current().pid()
}
