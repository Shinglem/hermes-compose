package dev.shinglem.hermes.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import dev.shinglem.hermes.core.config.AppOptionsHolder
import dev.shinglem.hermes.core.config.MarkdownStyle
import dev.shinglem.hermes.core.config.ThemeStyle
import io.github.shinglem.markdown.MarkdownConfig
import io.github.shinglem.markdown.markdownMaterialTheme
import io.github.shinglem.markdown.MarkdownText as MarkdownOneText

/**
 * Markdown 渲染入口：`feature` 各包只用这个函数，不直接碰 markdown-one 的 `MarkdownConfig`。
 *
 * 这里是唯一把「宿主设置」翻译成「渲染参数」的地方：
 * - 配色由 `options.ui.markdown_style` 单独控制（`follow` 才跟随界面配色风格）；
 * - 明暗跟随 MaterialTheme 的实际底色，所以 `ui.theme = system` 时也能正确切换。
 *
 * `streaming` 为 true 时（正在接收的回复），未闭合的代码栅栏由渲染层补齐，图表不绘制。
 * `selectable` 控制渲染层内部那层 SelectionContainer：流式时关掉可以避免选区闪烁；
 * 调用方不要再在外面套一层，否则选择链会断（硬约束 6）。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    mermaidFallbackText: String = MarkdownStrings.mermaidFallback,
    selectable: Boolean = true,
    onLinkClick: (String) -> Unit = {},
) {
    val options by AppOptionsHolder.state.collectAsState()
    // 用实际底色判明暗：ui.theme = system 时不重复算一遍 isSystemInDarkTheme。
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    // 两个主题工厂都是 @Composable（要读 MaterialTheme），没法塞进 remember；
    // 产出的 MarkdownTheme 是 data class，直接当下面 remember 的 key 即可。
    // `follow` 在 paper 风格下用加深过的纸面主题，其余风格直接跟随当前 M3 色板。
    val theme = when (options.ui.markdownStyle) {
        MarkdownStyle.MATERIAL -> markdownMaterialTheme()
        MarkdownStyle.PAPER -> paperMarkdownTheme(dark)
        MarkdownStyle.FOLLOW ->
            if (options.ui.style == ThemeStyle.PAPER) paperMarkdownTheme(dark) else markdownMaterialTheme()
    }

    // config 是渲染层重建适配器链的 key，必须 remember，否则每次重组都重建。
    val config = remember(mermaidFallbackText, theme, onLinkClick) {
        MarkdownConfig(
            mermaidFallbackText = mermaidFallbackText,
            theme = theme,
            onLinkClick = onLinkClick,
        )
    }
    MarkdownOneText(
        markdown = markdown,
        modifier = modifier.fillMaxWidth(),
        streaming = streaming,
        config = config,
        selectable = selectable,
    )
}

/** 模块内唯一持有的文案（调用方可覆盖；其余文案一律由调用方注入）。 */
object MarkdownStrings {
    const val mermaidFallback: String = "图表语法暂不支持，已显示源码"
}
