package dev.shinglem.hermes.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import dev.shinglem.hermes.core.backend.BackendKind

/**
 * 桌面 → 后端（#15）：手动启停 / 重启后端、看状态与端口、看日志尾、复制诊断。
 *
 * 这一页存在的理由：后端出问题时，用户原来只能"重启整个客户端"甚至手工杀进程。
 * 端口跟随不是这里的按钮 —— 传输层连续失败会自动重新解析地址（#14）。
 * **全程普通权限**，不需要管理员。
 */
@Composable
fun BackendPage(
    viewModel: BackendAdminViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("后端", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "本地后端是机器级常驻进程（`gateway` 路线），客户端只是附着者。" +
                "这里可以单独启停它，不必退出客户端。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        InfoLine("后端来源", when (state.kind) {
            BackendKind.LOCAL -> "本地（自带 payload）"
            BackendKind.REMOTE -> "远端"
            null -> "—"
        })
        InfoLine("启动状态", describeBoot(state.boot))
        InfoLine("连接状态", describeConnection(state.connection))
        InfoLine("API 地址", state.apiOrigin ?: "（未就绪）")
        state.instanceId?.let { InfoLine("实例 id", it) }
        state.supervisor?.let { InfoLine("supervisor", it) }
        state.profileId?.let { InfoLine("profile", it) }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = viewModel::start, enabled = !state.busy && !state.running) {
                Text("启动")
            }
            OutlinedButton(onClick = viewModel::stop, enabled = !state.busy && state.running) {
                Text("停止")
            }
            OutlinedButton(onClick = viewModel::restart, enabled = !state.busy) {
                Text("重启")
            }
            OutlinedButton(onClick = viewModel::revalidate, enabled = !state.busy) {
                Text("重新解析地址")
            }
            OutlinedButton(onClick = viewModel::reconnect, enabled = !state.busy) {
                Text("重连")
            }
            if (state.busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }

        state.notice?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::dismissNotice) { Text("关闭") }
            }
        }

        Text(
            text = "停止只作用于本客户端使用的数据目录（`gateway stop -y`），不会影响别的 profile " +
                "或官方客户端正在用的后端。全程不需要管理员权限。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "后端输出（最近 ${BackendAdminState.DIAGNOSTIC_LOG_LINES} 行）",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(viewModel.diagnostics())) }) {
                Text("复制诊断信息")
            }
        }
        BackendLogTail(lines = state.tail())

        Text(
            text = "端口由后端自己分配（`backend.local.port=0` 时由系统选），所以每次重启都可能变；" +
                "上方的 API 地址永远是当前实际值。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BackendLogTail(lines: List<dev.shinglem.hermes.core.backend.BackendLogLine>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(6.dp))
            .padding(8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (lines.isEmpty()) {
            Text(
                text = "（无输出）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            lines.forEach { line ->
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
