package dev.shinglem.hermes.ui.notification

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.platform.NotificationRequest
import dev.shinglem.hermes.ui.icon.AppIcons
import dev.shinglem.hermes.ui.theme.appMonoFontFamily

/** 卡片本体尺寸（不含阴影留白）。窗口按这个 + [NOTIFICATION_SHADOW_MARGIN] 定尺寸。 */
val NOTIFICATION_CARD_WIDTH = 360.dp
val NOTIFICATION_SHADOW_MARGIN = 12.dp

/** 有按钮的卡片更高：标题 + 正文 2 行 + 按钮行；纯提示只需要标题 + 正文。 */
fun notificationCardHeight(hasActions: Boolean) = if (hasActions) 152.dp else 104.dp

/**
 * 通知卡片（M3 卡片：圆角 + 阴影 + 容器色）。
 *
 * 为什么自绘而不是系统 Toast，见 `.design/详细设计/工程与代码/12-通知窗.md`：
 * Win32 托盘气泡不支持按钮，而 WinRT Toast 的按钮要 AUMID + 开始菜单快捷方式 + 激活协议，
 * 绿色版每次移动安装目录都得重新对账。
 *
 * 整张卡片可点（点空白处 = 跳到发起它的会话），按钮自己会吃掉点击。
 */
@Composable
fun NotificationCard(
    request: NotificationRequest,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val mono = appMonoFontFamily()
    Surface(
        modifier = modifier
            .shadow(10.dp, shape)
            .clickable(enabled = request.onBodyClick != null) { request.onBodyClick?.invoke() },
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = request.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = AppIcons.Close,
                        contentDescription = "关闭通知",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = request.body,
                // 命令 / 路径用等宽：一眼能看出是不是那个 `rm -rf`。
                fontFamily = mono,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (request.actions.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    request.actions.forEachIndexed { index, action ->
                        val onClick: () -> Unit = {
                            onDismiss()
                            action.onClick()
                        }
                        if (index == 0) {
                            Button(
                                onClick = onClick,
                                modifier = Modifier.weight(1f).height(32.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                            ) {
                                Text(action.label, style = MaterialTheme.typography.labelLarge)
                            }
                        } else {
                            OutlinedButton(
                                onClick = onClick,
                                modifier = Modifier.weight(1f).height(32.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                            ) {
                                Text(action.label, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}
