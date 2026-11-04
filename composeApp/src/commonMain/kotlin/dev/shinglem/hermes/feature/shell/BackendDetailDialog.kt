package dev.shinglem.hermes.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.backend.BackendBootState
import dev.shinglem.hermes.core.backend.BackendLogLine

/**
 * 后端详情对话框：状态 + 失败原因 + 控制台输出尾 + 重试。
 *
 * 主界面永远先渲染，后端在后台拉起；出问题时用户在这里看原因、重试，
 * 或者继续用界面上的其它功能（不会因为后端没起来就卡在启动页）。
 */
@Composable
fun BackendDetailDialog(
    state: BackendBootState,
    logs: List<BackendLogLine>,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val failed = state as? BackendBootState.Failed
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (failed != null) "后端启动失败" else "本地后端") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = when (state) {
                        is BackendBootState.Ready ->
                            "后端已就绪（${state.target.baseUrl.trimEnd('/')}）"
                        else -> BackendStatusText.failureMessage(state) ?: "正在启动…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                failed?.let { failure ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        failure.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    failure.detail?.let { detail ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "可继续使用界面其它功能；也可以把 config.json 的 backend.mode 改成 remote 连既有服务。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (logs.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "后端输出",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    LogTail(logs)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRetry) { Text("重试启动") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

@Composable
private fun LogTail(logs: List<BackendLogLine>) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            items(logs) { line ->
                Text(
                    line.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
