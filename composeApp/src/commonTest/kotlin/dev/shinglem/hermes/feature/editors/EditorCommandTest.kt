package dev.shinglem.hermes.feature.editors

import dev.shinglem.hermes.core.config.EditorActionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 参数模板解析：占位符替换、缺失即丢弃、参数注入防护。 */
class EditorCommandTest {

    @Test
    fun `简单模板替换`() {
        assertEquals(
            listOf("C:/a/b.kt"),
            buildEditorArgs("{path}", mapOf("path" to "C:/a/b.kt")),
        )
    }

    @Test
    fun `一行里多个占位符`() {
        assertEquals(
            listOf("--goto", "C:/a/b.kt:42"),
            buildEditorArgs("--goto {path}:{line}", mapOf("path" to "C:/a/b.kt", "line" to "42")),
        )
    }

    @Test
    fun `缺值的参数整体丢弃`() {
        // 没有行号时 `--goto p:` 会变成垃圾参数，VSCode 会报「找不到文件」
        assertEquals(
            listOf("C:/a/b.kt"),
            buildEditorArgs("--goto {path}:{line} {path}", mapOf("path" to "C:/a/b.kt")),
        )
    }

    @Test
    fun `有行号时两个参数都在`() {
        assertEquals(
            listOf("--goto", "C:/a/b.kt:7", "C:/a/b.kt"),
            buildEditorArgs("--goto {path}:{line} {path}", mapOf("path" to "C:/a/b.kt", "line" to "7")),
        )
    }

    @Test
    fun `双引号包裹的一段算一个参数且去掉引号`() {
        assertEquals(
            listOf("--goto", "C:/with space/b.kt:3"),
            buildEditorArgs("""--goto "{path}:{line}"""", mapOf("path" to "C:/with space/b.kt", "line" to "3")),
        )
    }

    @Test
    fun `值像开关时丢弃该参数（防参数注入）`() {
        assertEquals(
            emptyList(),
            buildEditorArgs("{path}", mapOf("path" to "--user-data-dir=C:/evil")),
        )
        // 同一行里的布尔开关也会被连带丢弃：留下一个等值的开关比什么都没有更糟
        assertEquals(
            emptyList(),
            buildEditorArgs("--new-window {path}", mapOf("path" to "--dangerous")),
        )
    }

    @Test
    fun `未闭合花括号当普通字符`() {
        assertEquals(listOf("{path"), buildEditorArgs("{path", mapOf("path" to "x")))
    }

    @Test
    fun `空模板不产生参数`() {
        assertTrue(buildEditorArgs("", mapOf("path" to "x")).isEmpty())
        assertTrue(buildEditorArgs("   ", mapOf("path" to "x")).isEmpty())
    }

    @Test
    fun `动作关闭时返回 null`() {
        assertEquals(
            null,
            resolveActionArgs(EditorActionKind.OPEN_FILE, enabled = false, template = "{path}", vars = mapOf("path" to "x")),
        )
    }

    @Test
    fun `模板为空时用内置默认`() {
        assertEquals(
            listOf("--diff", "a.kt", "b.kt"),
            resolveActionArgs(
                EditorActionKind.OPEN_DIFF,
                enabled = true,
                template = "",
                vars = mapOf("file1" to "a.kt", "file2" to "b.kt"),
            ),
        )
    }

    @Test
    fun `默认模板覆盖五种动作`() {
        EditorActionKind.entries.forEach { kind ->
            assertTrue(kind.defaultTemplate.isNotBlank(), "${kind.label} 缺默认模板")
            assertTrue(kind.placeholders.isNotEmpty(), "${kind.label} 缺占位符说明")
        }
    }
}
