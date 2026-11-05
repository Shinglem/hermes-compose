package dev.shinglem.hermes.feature.editors

import dev.shinglem.hermes.core.config.EditorsOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.shinglem.hermes.core.config.EditorAction
import dev.shinglem.hermes.core.config.EditorProfile

/** 「编辑器 × 动作 → 命令行」的规划与回退规则。 */
class EditorPlannerTest {

    private val vscode = EditorProfile(
        id = "vscode",
        name = "VS Code",
        exePath = "C:/VSCode/Code.exe",
        openFile = EditorAction(args = "{path}"),
        openFileAtLine = EditorAction(args = "--goto {path}:{line}"),
        openFolder = EditorAction(args = "{dir}"),
        openProject = EditorAction(args = "{dir}"),
        openDiff = EditorAction(args = "--diff {file1} {file2}"),
    )

    @Test
    fun `打开文件`() {
        val req = planEditorOpen(vscode, dev.shinglem.hermes.core.config.EditorActionKind.OPEN_FILE, "C:/a/b.kt", "C:/a", null)
        assertEquals(listOf("C:/a/b.kt"), req?.args)
    }

    @Test
    fun `有行号时用到了行号的模板`() {
        val req = planEditorOpen(
            vscode,
            dev.shinglem.hermes.core.config.EditorActionKind.OPEN_FILE_AT_LINE,
            "C:/a/b.kt",
            "C:/a",
            null,
            line = 12,
        )
        assertEquals(listOf("--goto", "C:/a/b.kt:12"), req?.args)
    }

    @Test
    fun `打开所在文件夹`() {
        val req = planEditorOpen(
            vscode,
            dev.shinglem.hermes.core.config.EditorActionKind.OPEN_FOLDER,
            "C:/a/b.kt",
            "C:/a",
            null,
        )
        assertEquals(listOf("C:/a"), req?.args)
    }

    @Test
    fun `打开项目用会话 cwd`() {
        val req = planEditorOpen(
            vscode,
            dev.shinglem.hermes.core.config.EditorActionKind.OPEN_PROJECT,
            "C:/a/b.kt",
            "C:/a",
            cwd = "S:/proj",
        )
        assertEquals(listOf("S:/proj"), req?.args)
    }

    @Test
    fun `没有 cwd 时打不开项目`() {
        assertNull(
            planEditorOpen(
                vscode,
                dev.shinglem.hermes.core.config.EditorActionKind.OPEN_PROJECT,
                "C:/a/b.kt",
                "C:/a",
                cwd = null,
            ),
        )
    }

    @Test
    fun `对比两个文件`() {
        val req = planEditorOpen(
            vscode,
            dev.shinglem.hermes.core.config.EditorActionKind.OPEN_DIFF,
            "C:/a/b.kt",
            "C:/a",
            null,
            file1 = "C:/a/b.kt",
            file2 = "C:/a/c.kt",
        )
        assertEquals(listOf("--diff", "C:/a/b.kt", "C:/a/c.kt"), req?.args)
    }

    @Test
    fun `关闭某动作时回退到打开文件`() {
        val noFolder = vscode.copy(openFolder = EditorAction(enabled = false, args = "{dir}"))
        val req = planEditorOpen(
            noFolder,
            dev.shinglem.hermes.core.config.EditorActionKind.OPEN_FOLDER,
            "C:/a/b.kt",
            "C:/a",
            null,
        )
        assertEquals(listOf("C:/a/b.kt"), req?.args, "关闭「打开文件夹」应回退成打开文件")
        assertEquals(dev.shinglem.hermes.core.config.EditorActionKind.OPEN_FILE, req?.kind)
    }

    @Test
    fun `打开文件也关掉时没有任何请求`() {
        val none = vscode.copy(
            openFile = EditorAction(enabled = false),
            openFolder = EditorAction(enabled = false),
        )
        assertNull(
            planEditorOpen(none, dev.shinglem.hermes.core.config.EditorActionKind.OPEN_FOLDER, "C:/a/b.kt", "C:/a", null),
        )
    }

    @Test
    fun `exe 路径为空时不出请求`() {
        assertNull(
            planEditorOpen(vscode.copy(exePath = ""), dev.shinglem.hermes.core.config.EditorActionKind.OPEN_FILE, "C:/a", "C:/a", null),
        )
    }

    @Test
    fun `菜单只列出可用的组合`() {
        val editors = EditorsOptions(profiles = listOf(vscode))
        val items = editorMenuItems(editors, "C:/a/b.kt", "C:/a", cwd = null)
        val kinds = items.map { it.kind }.toSet()
        assertTrue(dev.shinglem.hermes.core.config.EditorActionKind.OPEN_FILE in kinds)
        assertTrue(dev.shinglem.hermes.core.config.EditorActionKind.OPEN_FOLDER in kinds)
        // 没有 cwd / file1 file2，这两项不该出现
        assertTrue(dev.shinglem.hermes.core.config.EditorActionKind.OPEN_PROJECT !in kinds)
        assertTrue(dev.shinglem.hermes.core.config.EditorActionKind.OPEN_DIFF !in kinds)
    }

    @Test
    fun `只有一个配置时直开`() {
        val editors = EditorsOptions(profiles = listOf(vscode))
        assertNotNull(preferredEditorOpen(editors, "C:/a/b.kt", "C:/a", null))
    }

    @Test
    fun `多个配置且没指定默认时不直开`() {
        val editors = EditorsOptions(
            profiles = listOf(vscode, vscode.copy(id = "idea", name = "IDEA")),
        )
        assertNull(preferredEditorOpen(editors, "C:/a/b.kt", "C:/a", null))
    }

    @Test
    fun `指定默认后按默认直开`() {
        val editors = EditorsOptions(
            defaultId = "idea",
            profiles = listOf(vscode, vscode.copy(id = "idea", name = "IDEA", exePath = "C:/IDEA/idea64.exe")),
        )
        assertEquals("IDEA", preferredEditorOpen(editors, "C:/a/b.kt", "C:/a", null)?.profile?.name)
    }
}
