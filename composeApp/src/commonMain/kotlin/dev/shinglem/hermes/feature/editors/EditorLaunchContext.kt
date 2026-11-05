package dev.shinglem.hermes.feature.editors

import androidx.compose.runtime.compositionLocalOf
import dev.shinglem.hermes.core.config.EditorsOptions

/**
 * 工具卡片「用外部编辑器打开」需要的一切。
 *
 * 用 CompositionLocal 而不是逐层传参：`ToolCallCard` 埋在
 * `MessageItem → ChatPane → AppShell` 下面，为了一个按钮把三层签名都改掉不划算。
 */
data class EditorLaunchContext(
    val editors: EditorsOptions,
    /** 当前会话的工作目录（相对路径按它解析）。 */
    val cwd: String?,
    /** 用户主目录（`~/x` 展开）。 */
    val userHome: String,
)

/** 没有宿主提供时为 null —— 此时卡片不显示「打开」按钮。 */
val LocalEditorLaunchContext = compositionLocalOf<EditorLaunchContext?> { null }
