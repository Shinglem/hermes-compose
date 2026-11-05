package dev.shinglem.hermes.feature.editors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 从工具参数里取文件路径（只认后端真实存在的文件类工具）。 */
class ToolFilePathsTest {

    private val home = "C:/Users/u"
    private val cwd = "S:/proj/app"

    @Test
    fun `write_file 的绝对路径`() {
        val refs = toolFileRefs("write_file", """{"path":"C:\\proj\\a.kt","content":"x"}""", cwd, home)
        assertEquals(listOf("C:/proj/a.kt"), refs.map { it.path })
    }

    @Test
    fun `相对路径按会话 cwd 拼`() {
        val refs = toolFileRefs("read_file", """{"path":"src/a.kt"}""", cwd, home)
        assertEquals(listOf("S:/proj/app/src/a.kt"), refs.map { it.path })
    }

    @Test
    fun `没有 cwd 时相对路径不猜`() {
        val refs = toolFileRefs("read_file", """{"path":"src/a.kt"}""", cwd = null, userHome = home)
        assertTrue(refs.isEmpty(), "没有 cwd 就不该显示按钮，实际=$refs")
    }

    @Test
    fun `波浪线展开成用户目录`() {
        val refs = toolFileRefs("write_file", """{"path":"~/notes/a.md"}""", cwd, home)
        assertEquals(listOf("C:/Users/u/notes/a.md"), refs.map { it.path })
    }

    @Test
    fun `patch 的 replace 模式取 path`() {
        val refs = toolFileRefs(
            "patch",
            """{"path":"src/a.kt","old_string":"a","new_string":"b"}""",
            cwd,
            home,
        )
        assertEquals(listOf("S:/proj/app/src/a.kt"), refs.map { it.path })
    }

    @Test
    fun `patch 的 V4A 模式取出多个文件`() {
        val patch = """
            *** Begin Patch
            *** Update File: src/a.kt
            @@ x @@
            -old
            +new
            *** Add File: src/b.kt
            +new file
            *** Delete File: src/c.kt
            *** End Patch
        """.trimIndent()
        // 先确认纯函数认得这些行
        assertEquals(
            listOf("src/a.kt", "src/b.kt", "src/c.kt"),
            v4aPaths(patch),
        )
        val args = """{"mode":"patch","patch":"${escape(patch)}"}"""
        val refs = toolFileRefs("patch", args, cwd, home)
        assertEquals(
            listOf("S:/proj/app/src/a.kt", "S:/proj/app/src/b.kt", "S:/proj/app/src/c.kt"),
            refs.map { it.path },
            "args=$args",
        )
    }

    @Test
    fun `非文件类工具不产生路径`() {
        assertTrue(toolFileRefs("terminal", """{"command":"ls"}""", cwd, home).isEmpty())
        assertTrue(toolFileRefs("browser_navigate", """{"url":"https://x"}""", cwd, home).isEmpty())
    }

    @Test
    fun `参数不是合法 JSON 时静默返回空`() {
        assertTrue(toolFileRefs("write_file", "not json", cwd, home).isEmpty())
        assertTrue(toolFileRefs("write_file", "", cwd, home).isEmpty())
    }

    @Test
    fun `父目录计算`() {
        assertEquals("C:/proj", parentDirOf("C:/proj/a.kt"))
        assertEquals("C:/", parentDirOf("C:/a.kt"))
        assertNull(parentDirOf("a.kt"))
        assertNull(parentDirOf("C:/"))
    }

    @Test
    fun `Windows 反斜杠统一成正斜杠`() {
        val refs = toolFileRefs("write_file", """{"path":"C:\\a\\b\\c.kt"}""", cwd, home)
        assertEquals(listOf("C:/a/b/c.kt"), refs.map { it.path })
    }

    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
