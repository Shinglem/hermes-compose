package dev.shinglem.hermes.shell.entry

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * S6-1 · 单实例锁目录解析（ADR-066）。
 *
 * 验的是**换到 Nucleus 后唯一需要我们自己保证的那件事**：锁目录与权限、与安装位置无关，
 * 且不可写时能明确退回，而不是让 Nucleus 静默 fail-open 双开。
 */
class LockDirTest {

    private val temp: File = File(System.getProperty("java.io.tmpdir"), "lockdir-test-${System.nanoTime()}")

    @AfterTest
    fun cleanup() {
        temp.deleteRecursively()
    }

    @Test
    fun `home 可写时用 home 下的 locks`() {
        val home = temp.resolve("home").apply { mkdirs() }
        val dir = LockDir.resolve(home)

        assertNotNull(dir)
        assertEquals(File(home, "locks").absolutePath, dir.absolutePath)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `locks 目录被创建且探测文件不残留`() {
        val home = temp.resolve("home2").apply { mkdirs() }
        val dir = LockDir.resolve(home)
        assertNotNull(dir)

        // 探测留下的临时文件必须清掉，否则下次启动看到陌生文件会误判
        assertEquals(0, dir.listFiles()?.size ?: 0, "锁目录应为空，探测文件未清理")

        val probe = File(dir, "manual")
        probe.writeText("x")
        assertTrue(probe.exists(), "锁目录必须真的可写")
        probe.delete()
    }

    @Test
    fun `home 不可写时不选它`() {
        // 「父路径是文件」构造一个 mkdirs 必然失败的 home
        val blocker = temp.apply { mkdirs() }.resolve("blocker").apply { writeText("x") }
        val unwritableHome = File(blocker, "home")

        val dir = LockDir.resolve(unwritableHome)

        // 两种可接受结果：退回用户目录（LOCALAPPDATA 可用），或 null（环境无 LOCALAPPDATA）
        if (dir != null) {
            assertFalse(
                dir.absolutePath.startsWith(unwritableHome.absolutePath),
                "不可写的 home 下不该选出锁目录：${dir.absolutePath}",
            )
            assertTrue(
                dir.absolutePath.contains("HermesCompose"),
                "退回目标应是 HermesCompose/locks：${dir.absolutePath}",
            )
        }
    }

    @Test
    fun `同一 home 两次解析结果一致`() {
        val home = temp.resolve("home3").apply { mkdirs() }
        val first = LockDir.resolve(home)
        val second = LockDir.resolve(home)
        assertNotNull(first)
        assertEquals(first.absolutePath, second?.absolutePath)
    }

    @Test
    fun `不同 home 解析到不同锁目录`() {
        val a = temp.resolve("packA").apply { mkdirs() }
        val b = temp.resolve("packB").apply { mkdirs() }
        val dirA = LockDir.resolve(a)
        val dirB = LockDir.resolve(b)
        assertNotNull(dirA)
        assertNotNull(dirB)
        assertTrue(
            dirA.absolutePath != dirB.absolutePath,
            "两份便携包必须落在不同锁目录，否则会互相拦截",
        )
    }
}
