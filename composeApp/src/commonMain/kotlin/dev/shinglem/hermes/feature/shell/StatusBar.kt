package dev.shinglem.hermes.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.backend.ProbeState
import dev.shinglem.hermes.core.transport.ConnectionState
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.action_retry
import dev.shinglem.hermes.resources.status_backend_version
import dev.shinglem.hermes.resources.status_closed
import dev.shinglem.hermes.resources.status_connected
import dev.shinglem.hermes.resources.status_connecting
import dev.shinglem.hermes.resources.status_disconnected
import dev.shinglem.hermes.resources.status_idle
import dev.shinglem.hermes.resources.status_reconnecting
import dev.shinglem.hermes.resources.status_unelevated
import dev.shinglem.hermes.ui.icon.AppIcons

/** 通栏状态条（28dp）：profile · 后端启动状态 · 连接状态 · 后端版本。 */
@Composable
fun StatusBar(
    state: ShellState,
    onRetryConnection: () -> Unit,
    onShowBackendDetail: () -> Unit = {},
    showUnelevatedHint: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().height(StatusBarHeight),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(state.connection)
            Spacer(Modifier.width(6.dp))
            Text(
                text = state.activeProfileLabel,
                style = MaterialTheme.typography.labelMedium,
            )
            // 配置要求管理员但这次没拿到（用户点了"否"，或者 runas 失败）：必须让用户看见，
            // 否则"为什么有些操作点不动"会变成玄学。
            if (showUnelevatedHint) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.status_unelevated),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // 后端只有一项（远端模式也显示）：可点击看详情与后端输出。
            // 界面先渲染，后端没起来也不该让用户无处可点。
            val gatewayLabel = BackendStatusText.gatewayLabel(
                state.backendKind,
                state.backend,
                state.gatewayProbe,
            )
            Spacer(Modifier.width(8.dp))
            StatusChip(
                text = gatewayLabel,
                failing = BackendStatusText.isGatewayFailing(state.backend, state.gatewayProbe),
                onClick = onShowBackendDetail,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = state.connection.text(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 端口由后端自己分配（`backend.local.port=0` 时随机），每次重启都可能变。
            // chip 只放心跳（保持宽度），实际端口放这里 —— 排障时"连的是哪个后端"必须一眼可见。
            BackendStatusText.portOf(state.backend)?.let { port ->
                Text(
                    text = " :$port",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.connection is ConnectionState.Disconnected) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = AppIcons.Refresh,
                    contentDescription = stringResource(Res.string.action_retry),
                    modifier = Modifier.size(14.dp).clickable(onClick = onRetryConnection),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            // 模型不在这里显示：composer 底部的模型 pill 已经常驻可见（ADR-038）。
            state.health?.version?.takeIf { it.isNotBlank() }?.let { version ->
                Text(
                    text = stringResource(Res.string.status_backend_version, version),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 状态栏上的可点击项：`网关 12ms` / `服务 无响应`。 */
@Composable
private fun StatusChip(text: String, failing: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (failing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun StatusDot(connection: ConnectionState) {
    val color = when (connection) {
        is ConnectionState.Connected -> ConnectedGreen
        is ConnectionState.Reconnecting, ConnectionState.Connecting -> MaterialTheme.colorScheme.tertiary
        is ConnectionState.Disconnected -> MaterialTheme.colorScheme.error
        ConnectionState.Idle, ConnectionState.Closed -> MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, CircleShape),
    )
}

@Composable
private fun ConnectionState.text(): String = when (this) {
    ConnectionState.Idle -> stringResource(Res.string.status_idle)
    ConnectionState.Connecting -> stringResource(Res.string.status_connecting)
    is ConnectionState.Connected -> stringResource(Res.string.status_connected)
    is ConnectionState.Reconnecting -> stringResource(Res.string.status_reconnecting, attempt)
    is ConnectionState.Disconnected -> stringResource(Res.string.status_disconnected)
    ConnectionState.Closed -> stringResource(Res.string.status_closed)
}

private val StatusBarHeight = 28.dp

private val ConnectedGreen = Color(0xFF2E7D32)
