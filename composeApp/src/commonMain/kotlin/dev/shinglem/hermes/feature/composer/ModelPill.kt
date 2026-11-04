package dev.shinglem.hermes.feature.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.feature.model.ModelCatalog
import dev.shinglem.hermes.feature.model.ModelEntry
import dev.shinglem.hermes.feature.model.REASONING_EFFORTS
import dev.shinglem.hermes.feature.model.reasoningEffortLabel
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.model_empty
import dev.shinglem.hermes.resources.model_loading
import dev.shinglem.hermes.resources.model_pill_select
import dev.shinglem.hermes.resources.model_retry
import dev.shinglem.hermes.ui.icon.AppIcons
import org.jetbrains.compose.resources.stringResource

/** 菜单最大高度：模型多时自己滚动，不把整屏占满。 */
private val MenuMaxHeight = 420.dp

/**
 * composer 底部的小控件（模型 / 推理强度）。
 *
 * 自绘而不是用 `TextButton`：M3 按钮有 40dp 最小高度与内置水平 padding，
 * 放在输入框下方这一行会显得笨重。点击热区靠 `Surface(onClick)` 保证。
 */
@Composable
internal fun ComposerPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    maxWidth: androidx.compose.ui.unit.Dp = 200.dp,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.height(28.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = maxWidth),
            )
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Icon(
                    imageVector = AppIcons.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * 模型选择 pill：显示当前会话的模型，点开按 provider 分组的目录。
 *
 * 目录是懒加载的 —— 只有第一次点开时才发 `model.options`（后端那次调用会探 provider，
 * 冷启动可到十几秒，不能挂在启动路径上）。
 */
@Composable
fun ModelPill(
    catalog: ModelCatalog?,
    model: String?,
    provider: String?,
    loading: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onRequestCatalog: () -> Unit,
    onSelect: (ModelEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    // 组的展开状态：进菜单时默认只展开当前模型所在组，其余收起。
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    Box(modifier) {
        ComposerPill(
            text = model?.takeIf { it.isNotBlank() }
                ?: stringResource(Res.string.model_pill_select),
            enabled = enabled,
            busy = busy,
            maxWidth = 180.dp,
            onClick = {
                open = true
                if (catalog == null) onRequestCatalog()
            },
        )

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            when {
                catalog == null && loading -> DropdownMenuItem(
                    text = { Text(stringResource(Res.string.model_loading)) },
                    onClick = {},
                    enabled = false,
                )

                catalog == null -> DropdownMenuItem(
                    text = { Text(stringResource(Res.string.model_retry)) },
                    onClick = { onRequestCatalog() },
                )

                catalog.isEmpty -> DropdownMenuItem(
                    text = { Text(stringResource(Res.string.model_empty)) },
                    onClick = {},
                    enabled = false,
                )

                else -> Column(
                    modifier = Modifier
                        // 必须给下限：DropdownMenu 是 wrap content，只设 max 时菜单宽度等于最长
                        // 那一行的文本（provider 名），分组头里的数量与分组名会被裁掉。
                        .widthIn(min = 260.dp, max = 420.dp)
                        .heightIn(max = MenuMaxHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // 每个 provider 组可折叠：默认只展开「当前模型所在组」，大组不展开时
                    // 只占一行标题，减少滚动。
                    catalog.groups.forEachIndexed { index, group ->
                        if (group.models.isEmpty()) return@forEachIndexed
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 2.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                        val currentInGroup = group.models.any {
                            it.model == model && it.provider == provider
                        }
                        // 还没选过模型（新会话 / 刚配好 key）时默认展开第一组 ——
                        // 否则菜单里只有一行折叠标题，用户会以为"没有模型可选"。
                        val expanded = expandedGroups[group.slug]
                            ?: (currentInGroup || (model.isNullOrBlank() && index == firstNonEmptyIndex(catalog)))
                        ModelGroupHeader(
                            name = group.name,
                            count = group.models.size,
                            expanded = expanded,
                            currentInGroup = currentInGroup,
                            onToggle = {
                                expandedGroups[group.slug] = !expanded
                            },
                        )
                        if (!expanded) return@forEachIndexed

                        group.warning?.let { warning ->
                            Text(
                                text = warning,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                            )
                        }
                        group.models.forEach { entry ->
                            ModelMenuRow(
                                entry = entry,
                                current = entry.model == model && entry.provider == provider,
                                onClick = {
                                    open = false
                                    onSelect(entry)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** `catalog.groups` 里第一个有模型的分组下标（没有则 -1）。 */
private fun firstNonEmptyIndex(catalog: ModelCatalog): Int =
    catalog.groups.indexOfFirst { it.models.isNotEmpty() }

/** provider 分组头：点一下折叠/展开；折叠时用一个小圆点标出「当前模型在这组」。 */@Composable
private fun ModelGroupHeader(
    name: String,
    count: Int,
    expanded: Boolean,
    currentInGroup: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (currentInGroup && !expanded) {
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(6.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Icon(
            imageVector = if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 菜单里的一行模型：缩进 + 圆角选中块，与上面的分组标题拉开层次。 */
@Composable
private fun ModelMenuRow(
    entry: ModelEntry,
    current: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .background(
                color = if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.model,
            style = MaterialTheme.typography.bodySmall,
            color = if (current) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (current) {
            Icon(
                imageVector = AppIcons.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/**
 * 推理强度 pill。
 *
 * 当前模型不支持推理（`capabilities[model].reasoning == false`）时整个控件不显示 ——
 * 官方桌面端也是这个行为，避免给出一个点了没效果的按钮。
 */
@Composable
fun ReasoningPill(
    effort: String?,
    supported: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!supported) return
    var open by remember { mutableStateOf(false) }

    Box(modifier) {
        ComposerPill(
            text = reasoningEffortLabel(effort),
            enabled = enabled,
            busy = busy,
            maxWidth = 72.dp,
            onClick = { open = true },
        )

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            REASONING_EFFORTS.asReversed().forEach { level ->
                val current = level == effort?.lowercase()
                DropdownMenuItem(
                    text = {
                        Text(
                            text = reasoningEffortLabel(level),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingIcon = {
                        if (current) {
                            Icon(
                                imageVector = AppIcons.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    onClick = {
                        open = false
                        onSelect(level)
                    },
                )
            }
        }
    }
}
