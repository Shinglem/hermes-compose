package dev.shinglem.hermes.component.composer

import androidx.compose.ui.text.input.TextFieldValue
import dev.shinglem.hermes.feature.composer.ComposerAttachment
import dev.shinglem.hermes.feature.composer.ComposerUiState
import dev.shinglem.hermes.feature.composer.CompletionEntry

/**
 * Composer（输入框）的对外契约（S5：从 `ComposerViewModel` 迁来）。
 *
 * ### 为什么 state 直接复用 `ComposerUiState`
 *
 * 它有 30+ 个消费点（`Composer.kt` 的补全浮层、附件 chips、历史、提示）。
 * 另造一个同构的 `ComposerState` 只会让这一轮 diff 多出几百行纯改名，
 * 而**补全时序**才是这一域真正的风险 —— 见 [ComposerIntent] 的注释。
 */
typealias ComposerState = ComposerUiState

/**
 * 输入框的动作。
 *
 * ⚠️ 补全有三条**时序敏感**的路径（迁移前都在 `ComposerViewModel` 里）：
 * 1. `scheduleCompletion` 是防抖的，且**应用时刻要重算区间** —— 用请求发出时的光标会
 *    在异步返回后错位（S8 之前的实际 bug：`/skin` 变成 `/skinill`）；
 * 2. 接受补全后**不要**立刻再请求一次同一条（会自己匹配自己，浮层不消失）；
 * 3. `accept` 返回 Boolean：浮层过期时要放行 Enter，不能吞掉发送。
 */
sealed interface ComposerIntent {
    /** 打开/切换会话：重置输入框并把草稿换成该会话的。 */
    data class OpenSession(val storedSessionId: String?) : ComposerIntent

    data class ValueChange(val value: TextFieldValue) : ComposerIntent

    /** 用户在光标处触发补全（`@` / `/`）。 */
    data object RequestCompletion : ComposerIntent

    data class MoveSelection(val delta: Int) : ComposerIntent

    /** 接受补全。[index] 为 null 时用 `selected`。返回值由 UI 直接读 state 判断。 */
    data class AcceptCompletion(val index: Int?) : ComposerIntent

    data object DismissCompletion : ComposerIntent

    data object DismissNotice : ComposerIntent

    data class Quote(val text: String) : ComposerIntent

    data class StepHistory(val direction: Int) : ComposerIntent

    data class MarkSent(val text: String) : ComposerIntent

}

/** 一次性事件（UI 侧的副作用）。 */
sealed interface ComposerEffect {
    /** 请求把这段文本写进剪贴板（例如"复制大段粘贴的原文"）。 */
    data class CopyToClipboard(val text: String) : ComposerEffect
}

/** 补全条目的展示模型（与 UI 共享）。 */
typealias ComposerCompletionEntry = CompletionEntry