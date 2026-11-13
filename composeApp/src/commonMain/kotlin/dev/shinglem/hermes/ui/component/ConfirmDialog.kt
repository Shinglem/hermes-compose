package dev.shinglem.hermes.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 统一的二次确认对话框。
 *
 * v0.5.x 有 14 处手拼 `AlertDialog(onDismissRequest, title, text, confirmButton, dismissButton)`，
 * 结构与间距完全同构，只是文案不同。抽出来保证：
 *  - 「取消」永远在左边、「确认」永远在右边（Material 的按钮顺序约定）；
 *  - 破坏性操作（删除 / 归档 / 重发截断）走 [danger] 语义，确认按钮用 error 色；
 *  - 确认按钮有 [confirmEnabled]，避免"提交中还能再点一次"。
 *
 * 不适用于表单型对话框（输入框 + 校验）—— 那些用 Material 原生 `AlertDialog` 更直接。
 * 也不适用于只读详情（`BackendDetailDialog` 那类），它们没有 confirm/dismiss 语义。
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    dismissText: String? = null,
    /** 破坏性操作（删除 / 清空）：确认按钮用 error 色。 */
    danger: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(
                    text = confirmText,
                    color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
        },
        dismissButton = dismissText?.let {
            { TextButton(onClick = onDismiss) { Text(it) } }
        },
    )
}
