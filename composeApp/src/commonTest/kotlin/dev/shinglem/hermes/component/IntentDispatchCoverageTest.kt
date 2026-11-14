package dev.shinglem.hermes.component

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Intent 分派的完备性**（S8 验收 #11 的 Component 覆盖）。
 *
 * ### 为什么用结构断言而不是构造 Component
 *
 * `DefaultChatComponent` 需要 `GatewayClient` / `ModelRepository` / 真实 `AppPreferences`，
 * `DefaultComposerComponent` 也要 Repository + Preferences —— 造齐它们的成本远大于收益，
 * 而这两个 `onIntent` 本身**只是转发**（每个分支一行 `model.xxx(...)`），
 * 真正的逻辑在各自的 ViewModel（流式变换已有 `ChatReducerTest` 覆盖）。
 *
 * 这里挡住的是一个真实风险：**新增 Intent 却忘了在 `onIntent` 里加分支**。
 * Kotlin 的 `when` 在这里是**语句**而不是表达式，所以 `sealed interface` 的穷尽性
 * 检查不生效 —— 编译照过，界面上表现为"点了没反应"。
 *
 * （composer / capability / sessionlist 三个过渡形态域没有 Intent 层，直接暴露 `model`，
 * 所以这里只检查有 `onIntent` 的两个域。）
 */
class IntentDispatchCoverageTest {

    private data class Subject(
        val name: String,
        val intentFile: String,
        val componentFile: String,
        val intentType: String,
        val minBranches: Int,
    )

    private val subjects = listOf(
        Subject(
            name = "chat",
            intentFile = "component/chat/ChatIntent.kt",
            componentFile = "component/chat/DefaultChatComponent.kt",
            intentType = "ChatIntent",
            minBranches = 25,
        ),
        Subject(
            name = "composer",
            intentFile = "component/composer/ComposerContract.kt",
            componentFile = "component/composer/ComposerComponent.kt",
            intentType = "ComposerIntent",
            minBranches = 9,
        ),
    )

    private fun locate(relative: String): File {
        val roots = listOf("src/commonMain/kotlin/dev/shinglem/hermes", "composeApp/src/commonMain/kotlin/dev/shinglem/hermes")
        return roots.map { File(it, relative) }.firstOrNull { it.isFile }
            ?: error("找不到 $relative（测试工作目录不对）")
    }

    /** `onIntent` 的函数体（从签名到下一个顶格 `}` 的近似切片）。 */
    private fun onIntentBody(subject: Subject): String {
        val text = locate(subject.componentFile).readText().replace("\r\n", "\n")
        val start = text.indexOf("override fun onIntent(intent: ${subject.intentType})")
        check(start >= 0) { "${subject.name} 的 Component 里没有 onIntent" }
        // 切片到 onIntent 自己的闭合行：签名后第一个缩进恰为 4 空格的 `    }`。
        val body = text.substring(start)
        val end = Regex("""\n    }\n""").find(body)?.range?.first
        check(end != null && end > 0) { "${subject.name} 的 onIntent 切片失败（找不到结尾）" }
        return body.substring(0, end)
    }

    /**
     * 声明的 Intent 子类型名。
     *
     * **只看 `sealed interface <IntentType>` 的块内**：`ComposerContract.kt` 里同时有
     * `ComposerIntent` 与 `ComposerEffect` 两个 sealed interface，不限定范围会把
     * Effect 的子类型当成 Intent。
     */
    private fun declared(subject: Subject): Set<String> {
        val text = locate(subject.intentFile).readText().replace("\r\n", "\n")
        val start = text.indexOf("sealed interface ${subject.intentType}")
        check(start >= 0) { "${subject.name}: 找不到 sealed interface ${subject.intentType}" }
        val tail = text.substring(start)
        // 块结束 = 第一个缩进 4 空格的 `}`（子类型声明是 8 空格缩进）。
        val end = Regex("""\n}\n""").find(tail)?.range?.first ?: tail.length
        return Regex("""    (?:data class|data object|object) (\w+)""")
            .findAll(tail.substring(0, end))
            .map { it.groupValues[1] }
            .toSet()
    }

    @Test
    fun `每个 Intent 都在 onIntent 里有分支`() {
        for (subject in subjects) {
            val body = onIntentBody(subject)
            val missing = declared(subject).filterNot { body.contains("${subject.intentType}.$it") }
            assertTrue(
                missing.isEmpty(),
                "${subject.name}: 这些 Intent 在 onIntent 里没有分支（点了会没反应）：$missing",
            )
        }
    }

    @Test
    fun `onIntent 只提到已声明的 Intent`() {
        for (subject in subjects) {
            val orphans = Regex("""${subject.intentType}\.(\w+)""")
                .findAll(onIntentBody(subject))
                .map { it.groupValues[1] }
                .toSet() - declared(subject)
            assertTrue(orphans.isEmpty(), "${subject.name}: onIntent 里出现了未声明的 Intent：$orphans")
        }
    }

    /** 防解析规则失配：分支数明显变少说明正则没匹配到真实声明。 */
    @Test
    fun `Intent 声明可被解析且数量合理`() {
        for (subject in subjects) {
            val count = declared(subject).size
            assertTrue(
                count >= subject.minBranches,
                "${subject.name}: 解析到 $count 个 Intent（应 ≥ ${subject.minBranches}），解析规则可能失配",
            )
        }
    }
}