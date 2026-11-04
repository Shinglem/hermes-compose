package dev.shinglem.hermes.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.mode_capabilities
import dev.shinglem.hermes.resources.rail_default_profile
import dev.shinglem.hermes.resources.rail_settings
import dev.shinglem.hermes.ui.icon.AppIcons
import org.jetbrains.compose.resources.stringResource

/**
 * 左 rail：profile 竖列 + 底部模式切换。
 *
 * 自绘 `Surface + Column`，**不用 `NavigationRail`**（`AGENTS.md` 硬约束 9：
 * 它是导航目的地语义，布局约束不合适）。
 *
 * 每项是「头像 + 文字标签」，选中项用整块圆角填充（视觉稿的胶囊观感）；
 * 窄窗（< 960dp）收成纯头像列，标签隐藏。
 */
@Composable
fun ProfileRail(
    state: ShellState,
    compact: Boolean,
    onSelectProfile: (String?) -> Unit,
    onOpenCapabilities: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxHeight().width(if (compact) 56.dp else 108.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 8.dp, horizontal = if (compact) 4.dp else 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RailItem(
                label = stringResource(Res.string.rail_default_profile),
                selected = state.activeProfile == null,
                compact = compact,
                onClick = { onSelectProfile(null) },
            )
            Spacer(Modifier.height(4.dp))
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.profilesOrEmpty
                    .filterNot { it.isDefault || it.name == ShellViewModel.DEFAULT_PROFILE }
                    .forEach { profile ->
                        RailItem(
                            label = profile.label,
                            selected = state.activeProfile == profile.name,
                            compact = compact,
                            onClick = { onSelectProfile(profile.name) },
                        )
                    }
            }
            Spacer(Modifier.height(4.dp))
            // 「能力」是导航目的地，不是模式开关：进来之后这一项始终保持「能力」的标签与图标，
            // 不变成「会话」按钮 —— 回会话走点 profile 头像（ADR-044）。
            RailItem(
                label = stringResource(Res.string.mode_capabilities),
                selected = state.mode == ShellMode.CAPABILITIES,
                compact = compact,
                icon = AppIcons.Capabilities,
                iconDescription = stringResource(Res.string.mode_capabilities),
                onClick = onOpenCapabilities,
            )
            Spacer(Modifier.height(4.dp))
            // 设置入口紧跟「能力」，与它同一列；用独立按钮而不是 mode 切换的第二态。
            RailItem(
                label = stringResource(Res.string.rail_settings),
                selected = state.mode == ShellMode.SETTINGS,
                compact = compact,
                icon = Icons.Filled.Settings,
                iconDescription = stringResource(Res.string.rail_settings),
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun RailItem(
    label: String,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    iconDescription: String? = null,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = container,
        contentColor = content,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = iconDescription,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(content.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label.take(1).uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            if (!compact) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}