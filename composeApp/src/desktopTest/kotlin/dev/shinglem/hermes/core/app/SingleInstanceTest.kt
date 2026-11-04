package dev.shinglem.hermes.core.app

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 单实例的命名对象行为。
 *
 * 这些断言对应 `.design/详细设计/工程与代码/08-单实例与启动时序.md` 的 W1/W6/W7：
 * 第二份拿不到实例、第一份关掉后可重新拿到、不同 `<home>` 互不影响。
 */
class SingleInstanceTest {

    private fun newHome(): java.io.File = Files.createTempDirectory("hinstance").toFile()

    @Test
    fun `没有实例时 isRunning 为假`() {
        val home = newHome()
        assertFalse(SingleInstance.isRunning(home))
    }

    @Test
    fun `占位后 isRunning 为真，第二份拿不到实例`() {
        val home = newHome()
        val first = SingleInstance.acquire(home)
        assertNotNull(first, "第一份应当拿到实例")
        try {
            assertTrue(SingleInstance.isRunning(home))
            assertNull(SingleInstance.acquire(home), "第二份不该拿到实例")
        } finally {
            first.close()
        }
    }

    @Test
    fun `关掉后能重新占位（强杀不留僵尸锁的核心判据）`() {
        val home = newHome()
        val first = SingleInstance.acquire(home)
        assertNotNull(first)
        first.close()

        assertFalse(SingleInstance.isRunning(home), "句柄释放后不该还报已有实例")
        val second = SingleInstance.acquire(home)
        assertNotNull(second, "释放后应当能重新占位")
        second.close()
    }

    @Test
    fun `不同 home 互不影响`() {
        val a = newHome()
        val b = newHome()
        val first = SingleInstance.acquire(a)
        val second = SingleInstance.acquire(b)
        try {
            assertNotNull(first)
            assertNotNull(second, "两份便携包必须能同时运行")
        } finally {
            first?.close()
            second?.close()
        }
    }

    @Test
    fun `activate 事件能唤醒第一实例`() {
        val home = newHome()
        val instance = assertNotNull(SingleInstance.acquire(home))
        val latch = CountDownLatch(1)
        try {
            instance.onActivate { latch.countDown() }
            assertTrue(SingleInstance.requestActivate(home), "activate 事件应当可写")
            assertTrue(latch.await(5, TimeUnit.SECONDS), "等待线程没收到 activate")
        } finally {
            instance.close()
        }
    }
}
