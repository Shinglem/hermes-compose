package dev.shinglem.hermes.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 排队区：后端已接受、但这一轮还没轮到执行的消息。
 *
 * 为什么要单独一块：生成中再发一条时后端会把它排成**新的 admission**，
 * 直接把他塞进 transcript 会让它排到正在流式的思考过程前面（实测踩到）。
 * 这里给两条动作：
 * - 立刻插入 → `prompt.steer_queued`：把这条文本交给正在跑的那一轮
 * - 取消 → `prompt.cancel`
 */
@Composable
fun QueuedPromptBar(
    items: List<QueuedPrompt>,
    onSteer: (String) -> Unit,
    onCancel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "排队中 · ${items.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.text.replace('\n', ' '),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onSteer(item.admissionId) }) { Text("立刻插入") }
                    TextButton(onClick = { onCancel(item.admissionId) }) { Text("取消") }
                }
            }
        }
    }
}
