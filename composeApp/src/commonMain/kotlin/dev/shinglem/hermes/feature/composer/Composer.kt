package dev.shinglem.hermes.feature.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.platform.Clipboard
import dev.shinglem.hermes.platform.rememberFileDropTarget
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.composer_attach
import dev.shinglem.hermes.resources.composer_attachment_prefix
import dev.shinglem.hermes.resources.composer_drop_hint
import dev.shinglem.hermes.resources.composer_placeholder
import dev.shinglem.hermes.resources.composer_send
import dev.shinglem.hermes.resources.composer_stop
import dev.shinglem.hermes.ui.icon.AppIcons
import org.jetbrains.compose.resources.stringResource

/**
 * 最小输入框：`BasicTextField`（硬约束 4，不引编辑器内核）。
 *
 * - Enter 发送、Shift+Enter 换行；提交内容原样送出（Markdown 源码，硬约束 7）。
 * - `@` / `/` 补全浮层由外部 `ComposerViewModel` 驱动，本组件只管按键分派与呈现。
 * - Ctrl+V 先看有没有图片、是不是大段文本或拖放路径，都不是才交给默认粘贴。
 */
@Composable
fun Composer(
    controller: ComposerController,
    canSubmit: Boolean,
    busy: Boolean,
    stopping: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    /** 当前会话的模型/推理强度；为 null 时底部只显示发送按钮。 */
    modelBar: (@Composable () -> Unit)? = null,
) {
    val state = controller.state
    var hovering by remember { mutableStateOf(false) }
    val dropTarget = rememberFileDropTarget(
        onFiles = controller.onDropFiles,
        onText = { text -> controller.onPasteSpecial(text, state.value.selection) },
        onHover = { hovering = it },
    )

    fun submit() {
        val text = state.text
        if (text.isNotBlank()) onSend(text)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
                .padding(vertical = 6.dp),
        ) {
            if (hovering) {
                Text(
                    text = stringResource(Res.string.composer_drop_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                )
            }

            if (state.attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.attachments.forEach { attachment ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    Res.string.composer_attachment_prefix,
                                    attachment.label,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }

            state.notice?.let { notice ->
                Text(
                    text = notice,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                )
            }

            val referenceStyle = SpanStyle(color = MaterialTheme.colorScheme.primary)
            val commandStyle = SpanStyle(
                color = MaterialTheme.colorScheme.tertiary,
                fontFamily = FontFamily.Monospace,
            )
            BasicTextField(
                value = state.value,
                onValueChange = controller.onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    // 自动高度约 1–10 行，再多就在框内滚动。
                    .heightIn(min = 40.dp, max = 224.dp)
                    .onPreviewKeyEvent { event ->
                        handleComposerKey(
                            event = event,
                            canSubmit = canSubmit,
                            state = state,
                            submit = ::submit,
                            onAcceptCompletion = controller.onAcceptCompletion,
                            onMoveCompletion = controller.onMoveCompletion,
                            onDismissCompletion = controller.onDismissCompletion,
                            onStepHistory = controller.onStepHistory,
                            onPasteSpecial = controller.onPasteSpecial,
                        )
                    },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = remember(referenceStyle, commandStyle) {
                    ComposerHighlight(referenceStyle, commandStyle)
                },
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        if (state.text.isEmpty()) {
                            Text(
                                text = stringResource(Res.string.composer_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )

            // 底部操作行：附件 / 模型 / 推理强度 / 发送。输入框独占上面一行，
            // 长文本换行不会把按钮挤走。
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                FilledTonalIconButton(
                    onClick = controller.onPickFiles,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        imageVector = AppIcons.AttachFile,
                        contentDescription = stringResource(Res.string.composer_attach),
                        modifier = Modifier.size(18.dp),
                    )
                }

                modelBar?.invoke()

                Spacer(Modifier.weight(1f))

                if (busy) {
                    FilledTonalIconButton(
                        onClick = onStop,
                        enabled = !stopping,
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            imageVector = AppIcons.Stop,
                            contentDescription = stringResource(Res.string.composer_stop),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    FilledIconButton(
                        onClick = ::submit,
                        enabled = canSubmit && state.canSend,
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            imageVector = AppIcons.Send,
                            contentDescription = stringResource(Res.string.composer_send),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 按键分派：
 * - 浮层打开时 ↑/↓/Enter/Tab/Esc 归浮层；
 * - Ctrl+V 先看图片 / 大段粘贴 / 拖放路径；
 * - ↑/↓ 无浮层时取发送历史；
 * - Enter 发送、Shift+Enter 换行；组合态（输入法未上屏）的 Enter 交回输入法。
 *
 * [canSubmit] 只约束"发送"：生成中仍可编辑、取历史、翻补全（`ChatViewModel.send` 自己防重）。
 */
private fun handleComposerKey(
    event: KeyEvent,
    canSubmit: Boolean,
    state: ComposerUiState,
    submit: () -> Unit,
    onAcceptCompletion: (Int?) -> Boolean,
    onMoveCompletion: (Int) -> Unit,
    onDismissCompletion: () -> Unit,
    onStepHistory: (Int) -> Boolean,
    onPasteSpecial: (String, TextRange) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false

    if (event.key == Key.V && event.isCtrlPressed) {
        if (Clipboard.hasImage()) {
            onPasteSpecial(PASTE_IMAGE, state.value.selection)
            return true
        }
        val text = Clipboard.getText() ?: return false
        if (isLargePaste(text) || looksLikePath(text)) {
            onPasteSpecial(text, state.value.selection)
            return true
        }
        return false
    }

    val completion = state.completion
    if (completion != null) {
        when (event.key) {
            Key.DirectionDown -> {
                onMoveCompletion(1)
                return true
            }

            Key.DirectionUp -> {
                onMoveCompletion(-1)
                return true
            }

            Key.Enter, Key.NumPadEnter -> {
                // 浮层已过期（文本在补全返回后又变了）时不要吞掉 Enter，否则发送会失效。
                if (onAcceptCompletion(null)) return true
            }

            Key.Tab -> {
                if (onAcceptCompletion(null)) return true
                return false
            }

            Key.Escape -> {
                onDismissCompletion()
                return true
            }
        }
    }

    if (event.key == Key.DirectionUp && onStepHistory(-1)) return true
    if (event.key == Key.DirectionDown && onStepHistory(1)) return true

    if (event.key != Key.Enter && event.key != Key.NumPadEnter) return false
    // Shift+Enter 换行；组合态里的 Enter 属于输入法（`05-acceptance` C2）。
    if (event.isShiftPressed || state.value.composition != null) return false
    // 生成中按 Enter 什么也不做（吞掉，避免插入换行）；`send` 本身也防重。
    if (!canSubmit) return true
    submit()
    return true
}

/** Ctrl+V 命中图片时的哨兵：由调用方转成 `clipboard.paste`。 */
const val PASTE_IMAGE = "\u0000image"

/** 单行、带路径分隔符、无空白 —— 才值得问后端"这是不是一个拖放路径"。 */
internal fun looksLikePath(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.length < 4) return false
    if (trimmed.any { it.isWhitespace() || it == '\n' }) return false
    return trimmed.contains('\\') || trimmed.contains('/')
}

/**
 * `@引用` 与 `/命令` 上色（朴素 chip 替代）。
 *
 * 不用 `InlineTextContent`：`BasicTextField` 对 inline content 的编辑与删除支持不稳定，
 * 上色能保证"看得见、删得掉、发出去还是原文"，见 ADR-019。
 */
private class ComposerHighlight(
    private val referenceStyle: SpanStyle,
    private val commandStyle: SpanStyle,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val styled = buildAnnotatedString {
            append(text)
            TOKEN.findAll(text.text).forEach { match ->
                val token = match.value
                val start = match.range.first
                val style = if (token.startsWith("/") && start == 0) commandStyle else referenceStyle
                addStyle(style, start, start + token.length)
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }

    private companion object {
        /** 行首 `/命令` 或任意位置以空白隔开的 `@引用`。 */
        val TOKEN = Regex("""(^|(?<=\s))(?:@[^\s]*|/[^\s]*)""")
    }
}
