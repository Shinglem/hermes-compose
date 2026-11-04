package dev.shinglem.hermes.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.draganddrop.DragAndDropTarget

/**
 * 拖放接收目标。
 *
 * common 侧只声明"拿到一个 [DragAndDropTarget]"；怎么从平台事件里读文件/文本在 actual 里做
 * （desktop 用 `androidx.compose.ui.draganddrop.dragData()`）。
 *
 * @param onFiles 拖入的本地文件绝对路径。
 * @param onText 拖入的文本（可能是路径，也可能是选中的文字）。
 * @param onHover 进入 / 离开 / 结束时切换高亮。
 */
@Composable
expect fun rememberFileDropTarget(
    onFiles: (paths: List<String>) -> Unit,
    onText: (text: String) -> Unit,
    onHover: (hovering: Boolean) -> Unit,
): DragAndDropTarget

/** 系统文件选择器（多选）。用户取消返回空列表。 */
expect suspend fun pickFiles(title: String, imagesOnly: Boolean): List<String>

/** 系统文件选择器（单选）。用户取消返回 `null`。 */
expect suspend fun pickFile(title: String): String?

/** 系统保存对话框。用户取消返回 `null`。 */
expect suspend fun pickSaveFile(title: String, defaultName: String): String?

/** 按扩展名判断是否图片 —— 与后端 `image.attach` 的判定口径保持一致。 */
val IMAGE_EXTENSIONS: Set<String> = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic",
)

fun isImagePath(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS