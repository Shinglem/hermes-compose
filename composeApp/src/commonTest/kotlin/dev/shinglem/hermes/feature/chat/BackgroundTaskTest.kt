package dev.shinglem.hermes.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BackgroundTaskTest {
    private fun task(
        id: String,
        command: String = "npm run dev",
        status: String = "running",
        exitCode: Int? = null,
        output: String = "",
        uptime: Long? = null,
    ) = BackgroundTask(
        id = id,
        command = command,
        status = status,
        exitCode = exitCode,
        uptimeSeconds = uptime,
        output = output,
    )

    @Test
    fun `标题取命令首行`() {
        val task = task("p1", command = "\n  npm run dev -- --port 3000\n| tee log")
        assertEquals("npm run dev -- --port 3000", task.title)
    }

    @Test
    fun `状态与退出码决定运行中与失败`() {
        assertTrue(task("p1").running)
        assertFalse(task("p1").failed)

        val ok = task("p2", status = BackgroundTask.STATUS_EXITED, exitCode = 0)
        assertFalse(ok.running)
        assertFalse(ok.failed)

        val bad = task("p3", status = BackgroundTask.STATUS_EXITED, exitCode = 2)
        assertTrue(bad.failed)
    }

    @Test
    fun `合入快照时已有行保持位置`() {
        val previous = listOf(task("a"), task("b"))
        val fresh = listOf(task("b", status = BackgroundTask.STATUS_EXITED, exitCode = 0), task("a"))

        val merged = reconcileBackgroundTasks(previous, fresh, mutableSetOf())

        assertEquals(listOf("a", "b"), merged.map { it.id })
        assertTrue(merged[1].running.not())
    }

    @Test
    fun `新进程追加到末尾`() {
        val merged = reconcileBackgroundTasks(listOf(task("a")), listOf(task("a"), task("c")), mutableSetOf())
        assertEquals(listOf("a", "c"), merged.map { it.id })
    }

    @Test
    fun `被忽略的行不再出现 直到 registry 也不再报告`() {
        val dismissed = mutableSetOf("b")
        val first = reconcileBackgroundTasks(listOf(task("a"), task("b")), listOf(task("a"), task("b")), dismissed)
        assertEquals(listOf("a"), first.map { it.id })
        assertTrue("b" in dismissed)

        val second = reconcileBackgroundTasks(first, listOf(task("a")), dismissed)
        assertEquals(listOf("a"), second.map { it.id })
        assertTrue(dismissed.isEmpty(), "registry 不再报告时应该忘记这个 id")
    }

    @Test
    fun `轮询快照不覆盖本地累积的实时输出`() {
        val local = task("a", output = "line1\nline2\n")
        val snapshot = task("a", output = "line2\n")

        val merged = reconcileBackgroundTasks(listOf(local), listOf(snapshot), mutableSetOf())

        assertEquals("line1\nline2\n", merged.single().output)
    }

    @Test
    fun `首次出现时用快照初始化输出`() {
        val merged = reconcileBackgroundTasks(emptyList(), listOf(task("a", output = "hello")), mutableSetOf())
        assertEquals("hello", merged.single().output)
    }

    @Test
    fun `输出增量接到对应任务`() {
        val tasks = listOf(task("a"), task("b"))
        val next = tasks.appendOutput("b", "build ok\n")
        assertEquals("build ok\n", next[1].output)
        assertEquals("", next[0].output)
    }

    @Test
    fun `找不到任务时原样返回同一实例`() {
        val tasks = listOf(task("a"))
        assertSame(tasks, tasks.appendOutput("zzz", "chunk"))
    }

    @Test
    fun `输出超上限时只留尾部`() {
        val big = "x".repeat(BACKGROUND_OUTPUT_LIMIT)
        val next = listOf(task("a", output = big)).appendOutput("a", "tail")
        assertEquals(BACKGROUND_OUTPUT_LIMIT, next.single().output.length)
        assertTrue(next.single().output.endsWith("tail"))
    }

    @Test
    fun `运行时长格式化`() {
        assertEquals("9s", formatUptime(9))
        assertEquals("3m 05s", formatUptime(185))
        assertEquals("1h 12m", formatUptime(4_320))
    }
}
