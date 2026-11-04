package dev.shinglem.hermes.core.log

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `startup.log` 是"配置坏了也要留痕"的最后一道保险，所以它自己必须够笨、够稳：
 * 目录不存在要能建、写多行要能追加、旧的大文件要能截断。
 */
class StartupLogTest {

    private fun newHome(): java.io.File = Files.createTempDirectory("hstartup").toFile()

    @Test
    fun `logs 目录不存在时会自己建出来`() {
        val home = newHome()
        val log = StartupLog(home)

        log.info("start", "hello")

        assertTrue(log.file.isFile, "startup.log 没被创建：${log.file}")
        assertTrue(log.file.readText(Charsets.UTF_8).contains("hello"))
    }

    @Test
    fun `多次写入是追加而不是覆盖`() {
        val home = newHome()
        StartupLog(home).apply {
            info("start", "first")
            error("config", "second")
        }
        // 重新构造（模拟下一次启动）不应清掉上一次的内容
        StartupLog(home).info("start", "third")

        val lines = StartupLog(home).file.readLines()
        assertEquals(3, lines.size, "应当追加三行：$lines")
        assertTrue(lines[0].contains("first"))
        assertTrue(lines[1].contains("ERROR"))
        assertTrue(lines[2].contains("third"))
    }

    @Test
    fun `超过阈值时整体截断`() {
        val home = newHome()
        val file = StartupLog(home).file
        file.parentFile.mkdirs()
        file.writeText("x".repeat(300 * 1024), Charsets.UTF_8)

        val log = StartupLog(home)
        log.info("start", "after rotate")

        val text = file.readText(Charsets.UTF_8)
        assertTrue(text.length < 1024, "截断没生效，长度=${text.length}")
        assertTrue(text.contains("rotated"))
        assertTrue(text.contains("after rotate"))
    }

    @Test
    fun `每行都带时间戳与级别`() {
        val home = newHome()
        val log = StartupLog(home)
        log.info("home", "D:\\Hermes")

        val line = log.file.readLines().single()
        // 形如 2026-10-29T11:20:31.412  INFO   home        D:\Hermes
        assertTrue(
            Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}\s+INFO\s+home\s+""").containsMatchIn(line),
            line,
        )
    }
}
