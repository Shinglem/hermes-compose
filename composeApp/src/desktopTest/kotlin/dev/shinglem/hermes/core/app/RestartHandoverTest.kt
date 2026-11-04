package dev.shinglem.hermes.core.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestartHandoverTest {

    @Test
    fun `argument 与 parse 互逆`() {
        assertEquals("--successor-pid=4321", RestartHandover.argument(4321))
        assertEquals(4321L, RestartHandover.parse(listOf("--successor-pid=4321")))
    }

    @Test
    fun `没有交接参数时 parse 返回 null`() {
        assertNull(RestartHandover.parse(emptyList()))
        assertNull(RestartHandover.parse(listOf("--silent", "junk")))
    }

    @Test
    fun `非法 PID 不被当成交接`() {
        assertNull(RestartHandover.parse(listOf("--successor-pid=abc")))
        assertNull(RestartHandover.parse(listOf("--successor-pid=0")))
        assertNull(RestartHandover.parse(listOf("--successor-pid=-3")))
    }

    @Test
    fun `strip 去掉残留的交接参数且不动其它参数`() {
        val args = listOf("--silent", "--successor-pid=99", "extra")
        assertEquals(listOf("--silent", "extra"), RestartHandover.strip(args))
    }

    @Test
    fun `对自己调用立即返回 true`() {
        assertTrue(RestartHandover.awaitExit(ProcessHandle.current().pid(), timeoutMs = 0))
    }

    @Test
    fun `不存在的进程视为已退出`() {
        // 取一个几乎不可能存在的 PID：只要本机进程数没有十万级就不会撞上。
        val ghost = ProcessHandle.current().pid() + 100_000
        if (ProcessHandle.of(ghost).isEmpty) {
            assertTrue(RestartHandover.awaitExit(ghost, timeoutMs = 0))
        }
    }

    @Test
    fun `活着的进程在超时后返回 false`() {
        // 用自己当"还活着的旧进程"的替身：awaitExit 对自己短路，所以这里换成子进程。
        val child = ProcessBuilder("cmd.exe", "/c", "ping -n 6 127.0.0.1 > NUL").start()
        try {
            assertFalse(RestartHandover.awaitExit(child.pid(), timeoutMs = 300))
        } finally {
            child.destroyForcibly()
        }
    }
}
