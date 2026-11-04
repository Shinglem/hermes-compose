package dev.shinglem.hermes.core.backend

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Job Object 兜底：**关闭 Job 句柄 = 内核杀光 Job 内所有进程**。
 *
 * 这条不是锦上添花 —— 后端自带的 parent-death watchdog 在 Windows 上失效
 * （只比进程创建时间，进程退出后 `GetProcessTimes` 仍返回原值），强杀客户端必留孤儿。
 * 所以必须真的验证一次，而不是"绑上了就当它能用"。
 */
class WindowsJobObjectTest {

    private fun longRunningProcess(): Process =
        ProcessBuilder("cmd.exe", "/c", "ping -n 60 127.0.0.1 > nul").redirectErrorStream(true).start()

    @Test
    fun `关闭 Job 句柄后进程被内核杀掉`() {
        val job = WindowsJobObject.create()
        assertTrue(job != null, "Job Object 创建失败：本机 kernel32 绑定有问题")

        val process = longRunningProcess()
        try {
            assertTrue(job.assign(process), "子进程没进 Job")
            assertTrue(process.isAlive, "子进程还没起来就退出了")

            job.close()

            val exited = process.waitFor(15, TimeUnit.SECONDS)
            assertTrue(exited, "关闭 Job 后子进程仍然存活 —— 兜底没生效")
        } finally {
            runCatching { job.close() }
            runCatching { process.destroyForcibly() }
        }
    }

    @Test
    fun `Job 内进程的孙进程也一起被清掉`() {
        val job = WindowsJobObject.create()
        assertTrue(job != null)

        // cmd 再拉一个孙进程：Job 成员资格默认继承，两层都要被收掉。
        val process = ProcessBuilder(
            "cmd.exe",
            "/c",
            "cmd.exe /c ping -n 60 127.0.0.1 > nul",
        ).redirectErrorStream(true).start()
        try {
            assertTrue(job.assign(process))
            val deadline = System.currentTimeMillis() + 5_000
            var grandchild: ProcessHandle? = null
            while (System.currentTimeMillis() < deadline && grandchild == null) {
                grandchild = process.descendants().findFirst().orElse(null)
                if (grandchild == null) Thread.sleep(50)
            }
            val child = grandchild
            assertTrue(child != null, "没观察到孙进程")

            job.close()

            val exited = process.waitFor(15, TimeUnit.SECONDS) && child.let { handle ->
                val until = System.currentTimeMillis() + 15_000
                while (handle.isAlive && System.currentTimeMillis() < until) Thread.sleep(50)
                !handle.isAlive
            }
            assertTrue(exited, "Job 关闭后仍有进程存活（父=${process.isAlive}，孙=${child.isAlive}）")
        } finally {
            runCatching { job.close() }
            runCatching { process.destroyForcibly() }
        }
    }

    @Test
    fun `未加入 Job 的进程不受 Job 关闭影响`() {
        val job = WindowsJobObject.create()
        assertTrue(job != null)
        val outsider = longRunningProcess()
        try {
            job.close()
            Thread.sleep(1_000)
            // 这条是"不会误伤别的 Hermes"的最小证明：Job 只作用于加进去的进程。
            assertEquals(true, outsider.isAlive, "Job 之外的进程被误杀了")
        } finally {
            runCatching { job.close() }
            runCatching { outsider.destroyForcibly() }
        }
    }
}
