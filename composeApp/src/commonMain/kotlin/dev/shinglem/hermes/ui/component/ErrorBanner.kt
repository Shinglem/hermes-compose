package dev.shinglem.hermes.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.action_retry
import dev.shinglem.hermes.resources.error_backend_unreachable
import dev.shinglem.hermes.resources.error_not_connected
import dev.shinglem.hermes.resources.error_protocol
import dev.shinglem.hermes.resources.error_server
import dev.shinglem.hermes.resources.error_timeout
import dev.shinglem.hermes.resources.error_unauthorized
import dev.shinglem.hermes.resources.error_unknown
import dev.shinglem.hermes.ui.icon.AppIcons

/** [AppError] → 用户可见文案。中文只出现在资源文件里。 */
@Composable
fun AppError.text(): String = when (this) {
    AppError.BackendUnreachable -> stringResource(Res.string.error_backend_unreachable)
    AppError.Unauthorized -> stringResource(Res.string.error_unauthorized)
    AppError.NotConnected -> stringResource(Res.string.error_not_connected)
    is AppError.Timeout -> stringResource(Res.string.error_timeout, operation)
    is AppError.Protocol -> stringResource(Res.string.error_protocol, detail)
    is AppError.Server -> stringResource(Res.string.error_server, code, reason)
    is AppError.Notice -> text
    is AppError.Unknown -> stringResource(Res.string.error_unknown, summary)
}

/**
 * 错误横幅：不阻塞操作，列表有缓存时继续显示旧数据。
 *
 * [onRetry] 是右侧那个动作按钮的回调，[actionLabel] 覆盖按钮文案（默认「重试」）——
 * 单纯用于消掉提示的场景要传「关闭」，否则名不副实。
 */
@Composable
fun ErrorBanner(
    error: AppError,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    actionLabel: String? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = AppIcons.Error,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = error.text(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text(actionLabel ?: stringResource(Res.string.action_retry))
                }
            }
        }
    }
}
