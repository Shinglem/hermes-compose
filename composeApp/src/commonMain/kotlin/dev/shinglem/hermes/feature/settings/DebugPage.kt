package dev.shinglem.hermes.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.app.LaunchResult
import dev.shinglem.hermes.core.app.OfficialDesktopLauncher
import dev.shinglem.hermes.core.app.SharedBackendState

/**
 * 调试页（v0.2.0~，v1.0.0 移除；见 ADR-045）。
 *
 * 只做一件事：把官方 Electron 桌面端拉起来，好和本客户端连同一个后端做对照。
 * 路径来自 payload 的 `official-desktop/Hermes.exe`，可用 `app.debug.official_desktop` 覆盖。
 */
@Composable
fun DebugPage(
    launcher: OfficialDesktopLauncher?,
    modifier: Modifier = Modifier,
) {
    var result by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text("官方桌面端（对照调试）", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "本页只在 payload 里带了官方 Electron 桌面端时出现，用于「同一个后端、两套 UI」对照。" +
                "v1.0.0 会随对照包一起移除。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        if (launcher == null) {
            Text(
                "没有检测到官方桌面端。检查 payload 下是否存在 official-desktop/Hermes.exe。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }

        Text("路径", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            launcher.path,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        Spacer(Modifier.height(8.dp))
        Text("对照数据目录", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            launcher.dataDir,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        Spacer(Modifier.height(8.dp))
        Text("共用后端", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (val shared = launcher.sharedBackend) {
            is SharedBackendState.Ready -> Text(
                shared.origin,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )

            is SharedBackendState.Unavailable -> Text(
                shared.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "官方桌面端会以 remote 客户端身份连到上面这个地址，不会再起自己的后端。" +
                "后端未就绪时按钮不可用 —— 否则它会另起一套后端，两边的会话就分叉了。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    result = when (val outcome = launcher.launch()) {
                        is LaunchResult.Started -> "已启动，共用后端 ${outcome.origin}"
                        is LaunchResult.Refused -> "已拒绝：${outcome.reason}"
                        is LaunchResult.Failed -> "启动失败：${outcome.reason}"
                    }
                },
                enabled = launcher.sharedBackend is SharedBackendState.Ready,
            ) {
                Text("启动官方桌面端")
            }
            OutlinedButton(onClick = { result = null }) { Text("清除提示") }
        }
        result?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Text(
            "提示：对照实例有自己的数据目录（上面那行），不会碰你本机官方客户端的会话与配置，" +
                "也不会被它的单实例锁挡掉。首次启动它会就地准备自己的后端（`hermes-home/`），" +
                "日志在 `official-desktop.log`。要两边连同一个 dashboard，起好后在官方客户端的连接设置里改成同一地址。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
