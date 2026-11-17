package dev.shinglem.hermes.core.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * launch record 与 `DevToolsActivePort` 的解析（v0.6.1，ADR-082）。
 *
 * 样本是**实测抓到的真实内容**（不是编的）：
 * - record：后端 `launch_chromium(ephemeral=True, headed=True)` 写出来的；
 * - 端口文件：同一只浏览器 profile 下的 `DevToolsActivePort`。
 *
 * 这两份合起来才等价于完整的 `cdp_url`，所以两边的解析都要钉住。
 */
class ChromiumRecordTest {

    private val realRecord = """
        {"owner_pid": 27900, "owner_start_time": 179483178320, "pid": 24976,
         "start_time": 179483178395,
         "user_data_dir": "c:\\app\\vscode\\data\\tmp\\hermes-disc\\home\\cache\\browser\\chromium\\b784c2567dedeb55-27900-179483178320",
         "home_key": "c:\\app\\vscode\\data\\tmp\\hermes-disc\\home",
         "ephemeral": true, "ns": null,
         "devtools": "/devtools/browser/97e2d58d-7c2c-433c-8762-2004798de52b"}
    """.trimIndent()

    private val realPortFile = """
        50619
        /devtools/browser/97e2d58d-7c2c-433c-8762-2004798de52b
    """.trimIndent()

    @Test
    fun `解析真实的 ephemeral record`() {
        val parsed = ChromiumRecord.parse(realRecord)
        assertNotNull(parsed)
        assertEquals(27900L, parsed.ownerPid)
        assertEquals(24976L, parsed.pid)
        assertEquals(179483178395L, parsed.startTimeEpochMs)
        assertTrue(parsed.ephemeral)
        assertTrue(parsed.userDataDir.contains("home"))
        assertNotNull(parsed.devtoolsPath)
    }

    @Test
    fun `缺关键字段返回 null 而不抛`() {
        assertNull(ChromiumRecord.parse("""{"pid": 1}"""), "缺 owner_pid 应跳过")
        assertNull(ChromiumRecord.parse("""{"owner_pid": 1, "pid": 2}"""), "缺 user_data_dir 应跳过")
        assertNull(ChromiumRecord.parse("""not json"""), "非法 JSON 不该抛")
        assertNull(ChromiumRecord.parse("""[]"""), "不是对象时不该抛")
    }

    @Test
    fun `未知字段被忽略`() {
        // 后端已经加过 `ns`，以后还会加；多字段不能让整条失效。
        val withExtra = realRecord.replace("\"ns\": null", "\"ns\": null, \"future_field\": {\"a\": 1}")
        assertNotNull(ChromiumRecord.parse(withExtra))
    }

    @Test
    fun `解析真实的 DevToolsActivePort`() {
        val content = DevToolsPortFile.parse(realPortFile)
        assertNotNull(content)
        assertEquals(50619, content.port)
        assertEquals("/devtools/browser/97e2d58d-7c2c-433c-8762-2004798de52b", content.browserPath)
    }

    @Test
    fun `端口文件容错`() {
        assertNull(DevToolsPortFile.parse(""), "空文件")
        assertNull(DevToolsPortFile.parse("abc"), "第一行不是数字")
        assertNull(DevToolsPortFile.parse("0"), "端口下界外的值")
        assertNull(DevToolsPortFile.parse("70000"), "端口上界外的值")

        // 只有端口没有第二行也是合法的（老版本或写了一半）。
        val justPort = DevToolsPortFile.parse("50619")
        assertNotNull(justPort)
        assertEquals(50619, justPort.port)
        assertNull(justPort.browserPath)
    }

    @Test
    fun `端口文件的空白与换行被容忍`() {
        val messy = "  50619 \r\n  /devtools/browser/x  \r\n\r\n"
        val content = DevToolsPortFile.parse(messy)
        assertNotNull(content)
        assertEquals(50619, content.port)
        assertEquals("/devtools/browser/x", content.browserPath)
    }
}
