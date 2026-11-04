package dev.shinglem.hermes.platform

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 终端 → 输入框的单向桥。
 *
 * 终端内容在 desktop 侧的 Swing 里（`TerminalSessions`），而输入框在 common 的
 * `ComposerViewModel`；两者之间隔着一层 `TerminalPanel` 插槽，硬把回调穿过去会让
 * 每个中间组件都多一个只为传话的参数。这里用一个只装文本的小总线，两端各接一次。
 *
 * 只用于「把选中内容引用到输入框」这一件事，不要往里塞别的东西。
 */
object TerminalQuoteBridge {
    private val _quotes = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val quotes: SharedFlow<String> = _quotes.asSharedFlow()

    fun publish(text: String) {
        if (text.isBlank()) return
        _quotes.tryEmit(text)
    }
}
