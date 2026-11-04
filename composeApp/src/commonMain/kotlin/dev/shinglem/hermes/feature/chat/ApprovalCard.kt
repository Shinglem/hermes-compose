package dev.shinglem.hermes.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.approval_choose_always
import dev.shinglem.hermes.resources.approval_choose_deny
import dev.shinglem.hermes.resources.approval_choose_once
import dev.shinglem.hermes.resources.approval_choose_session
import dev.shinglem.hermes.resources.approval_title
import dev.shinglem.hermes.ui.icon.AppIcons

/**
 * 审批卡片：应答 server→client 的 `approval` 请求（`{choice: once|session|always|deny}`）。
 *
 * 卡片必须应答，否则服务端会一直等；`ChatViewModel` 侧有 4 分钟兜底（超时按拒绝）。
 */
@Composable
fun ApprovalCard(
    approval: PendingApproval,
    onDecide: (choice: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(Res.string.approval_title),
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            if (approval.description.isNotBlank()) {
                Text(
                    text = approval.description,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (approval.command.isNotBlank()) {
                SelectionContainer {
                    Text(
                        text = approval.command,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    )
                }
            }

            val choices = approval.choices.ifEmpty { listOf(CHOICE_ONCE, CHOICE_DENY) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { choice ->
                    val label = choice.label()
                    if (choice == CHOICE_DENY) {
                        OutlinedButton(onClick = { onDecide(choice) }) { Text(label) }
                    } else {
                        Button(onClick = { onDecide(choice) }) { Text(label) }
                    }
                }
            }
        }
    }
}

@Composable
private fun String.label(): String = when (this) {
    CHOICE_ONCE -> stringResource(Res.string.approval_choose_once)
    CHOICE_SESSION -> stringResource(Res.string.approval_choose_session)
    CHOICE_ALWAYS -> stringResource(Res.string.approval_choose_always)
    CHOICE_DENY -> stringResource(Res.string.approval_choose_deny)
    else -> this
}

private const val CHOICE_ONCE = "once"
private const val CHOICE_SESSION = "session"
private const val CHOICE_ALWAYS = "always"
private const val CHOICE_DENY = "deny"
