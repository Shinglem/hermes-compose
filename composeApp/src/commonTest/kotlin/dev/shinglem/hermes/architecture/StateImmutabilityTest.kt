package dev.shinglem.hermes.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 验收口径 #10：`@Immutable` 覆盖全部域 State（S8）。
 *
 * ### 为什么值得一条测试
 *
 * `@Immutable` 只影响 Compose 的重组决策，**漏标不会报错也不会崩** —— 表现是"这一页
 * 每次父级重组都跟着重画一遍"。这种退化没人会在代码评审里看出来，只能靠规则钉住。
 *
 * ### 判据
 *
 * 源码里 `data class XxxState(` 的声明，其上一行必须是 `@Immutable`。
 * 覆盖 `commonMain` 下全部 `*State.kt` 与 `component` 目录里的 Contract —— 也就是
 * 所有会作为 `StateFlow` 内容被 UI 收集的类型。
 *
 * 反向不成立（`@Immutable` 可以标在非 `*State` 的类型上，如 `SessionRuntime`），
 * 所以只做单向前提，同时断言总数 ≥ State 数（口径 #10 的字面要求）。
 */
class StateImmutabilityTest {

    private fun roots(): List<File> = listOf(
        File("src/commonMain/kotlin/dev/shinglem/hermes"),
        File("composeApp/src/commonMain/kotlin/dev/shinglem/hermes"),
    ).filter { it.isDirectory }

    private fun kotlinFiles(): List<File> =
        roots().flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    /** `data class XxxState(` 的声明点（文件 + 行号 + 是否带注解）。 */
    private fun stateDeclarations(): List<Triple<File, Int, Boolean>> {
        val out = mutableListOf<Triple<File, Int, Boolean>>()
        for (file in kotlinFiles()) {
            val lines = file.readText().replace("\r\n", "\n").lines()
            lines.forEachIndexed { i, line ->
                if (Regex("^data class \\w*State\\(").containsMatchIn(line)) {
                    val annotated = i > 0 && lines[i - 1].trim() == "@Immutable"
                    out += Triple(file, i + 1, annotated)
                }
            }
        }
        return out
    }

    @Test
    fun `每个 State 都标了 Immutable`() {
        val missing = stateDeclarations().filterNot { it.third }
        assertTrue(
            missing.isEmpty(),
            "这些 State 缺 @Immutable（不会报错，但父级每次重组都会连带重画）：\n" +
                missing.joinToString("\n") { "  ${it.first.path}:${it.second}" },
        )
    }

    @Test
    fun `Immutable 标注数不少于 State 数`() {
        val annotated = kotlinFiles().sumOf { file ->
            file.readText().replace("\r\n", "\n").lines().count { it.trim() == "@Immutable" }
        }
        val states = stateDeclarations().size
        assertTrue(
            annotated >= states,
            "@Immutable 只标了 $annotated 处，State 有 $states 个（口径 #10 要求 ≥）",
        )
        assertTrue(states > 0, "一个 State 都没解析到：测试工作目录不对？")
    }
}
