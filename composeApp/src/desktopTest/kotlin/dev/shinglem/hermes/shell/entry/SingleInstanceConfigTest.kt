package dev.shinglem.hermes.shell.entry

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * S6-1 · `<home>` → 锁标识（ADR-066）。
 *
 * 判据只有两条：**同一份安装必须得到同一个标识**，**不同安装必须得到不同标识**。
 * 第一条错了会"自己和自己抢不到锁"（一份包只能开一次就再也开不了），
 * 第二条错了会让两份便携包互相拦截。
 */
class SingleInstanceConfigTest {

    @Test
    fun `同一路径得到同一标识`() {
        val home = File("C:/ai/hermes-desktop-compose")
        assertEquals(
            SingleInstanceConfig.identifierFor(home),
            SingleInstanceConfig.identifierFor(home),
        )
    }

    /**
     * 大小写不同但指向同一目录时，标识必须一致。
     *
     * Windows 路径大小写不敏感；如果按字面量哈希，用户从不同入口（快捷方式 / 命令行 / 计划任务）
     * 启动会得到不同标识 → 允许双开。canonicalPath 消掉了这个差异。
     */
    @Test
    fun `大小写差异不影响标识`() {
        val lower = File("c:/ai/hermes-desktop-compose")
        val upper = File("C:/AI/hermes-desktop-compose")
        assertEquals(
            SingleInstanceConfig.identifierFor(lower),
            SingleInstanceConfig.identifierFor(upper),
        )
    }

    @Test
    fun `不同安装目录得到不同标识`() {
        assertNotEquals(
            SingleInstanceConfig.identifierFor(File("C:/ai/hermes-desktop-compose")),
            SingleInstanceConfig.identifierFor(File("D:/backup/hermes")),
        )
    }

    @Test
    fun `标识带 HermesCompose 前缀且是 8 位十六进制`() {
        val id = SingleInstanceConfig.identifierFor(File("C:/ai/hermes-desktop-compose"))
        assertTrue(id.startsWith("HermesCompose-"), "前缀应保持 HermesCompose，实际：$id")
        val hash = id.removePrefix("HermesCompose-")
        assertEquals(8, hash.length, "哈希应为 8 个十六进制位（4 字节）：$hash")
        assertTrue(hash.all { it in "0123456789abcdef" }, "应为小写十六进制：$hash")
    }

    @Test
    fun `与 v0_5_x 自研实现的哈希算法一致`() {
        // v0.5.x 的自研实现：SHA-256 前 4 字节 → "%02x" 拼接。升级后标识不变，
        // 用户日志里的后缀能延续，支持记录不用重新对照。
        val home = File("C:/ai/hermes-desktop-compose").canonicalPath
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(home.toByteArray(Charsets.UTF_8))
        val expected = digest.take(4).joinToString("") { "%02x".format(it) }
        assertEquals(
            "HermesCompose-$expected",
            SingleInstanceConfig.identifierFor(File("C:/ai/hermes-desktop-compose")),
        )
    }
}
