package dev.shinglem.hermes.feature.capabilities

import dev.shinglem.hermes.core.error.toNotice
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.contract.SkillCatalogEntryDto
import dev.shinglem.hermes.core.contract.SkillDto
import dev.shinglem.hermes.core.contract.ToolsetDto
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.capability_detail_hide
import dev.shinglem.hermes.resources.capability_detail_show
import dev.shinglem.hermes.resources.capability_provenance_agent
import dev.shinglem.hermes.resources.capability_provenance_bundled
import dev.shinglem.hermes.resources.capability_search_hint
import dev.shinglem.hermes.resources.capability_catalog_all
import dev.shinglem.hermes.resources.capability_catalog_empty
import dev.shinglem.hermes.resources.capability_catalog_empty_body
import dev.shinglem.hermes.resources.capability_catalog_install
import dev.shinglem.hermes.resources.capability_catalog_installed
import dev.shinglem.hermes.resources.capability_catalog_subtitle
import dev.shinglem.hermes.resources.capability_tab_discover
import dev.shinglem.hermes.resources.capability_skills_empty
import dev.shinglem.hermes.resources.capability_skills_empty_body
import dev.shinglem.hermes.resources.capability_skills_subtitle
import dev.shinglem.hermes.resources.capability_tab_skills
import dev.shinglem.hermes.resources.capability_tab_toolsets
import dev.shinglem.hermes.resources.capability_toolset_tools
import dev.shinglem.hermes.resources.capability_toolset_unavailable
import dev.shinglem.hermes.resources.capability_toolset_unconfigured
import dev.shinglem.hermes.resources.capability_toolsets_empty
import dev.shinglem.hermes.resources.capability_toolsets_subtitle
import dev.shinglem.hermes.resources.capability_usage
import dev.shinglem.hermes.resources.capabilities_load_failed
import dev.shinglem.hermes.resources.capabilities_retry
import dev.shinglem.hermes.resources.capabilities_skills
import dev.shinglem.hermes.ui.component.EmptyState
import dev.shinglem.hermes.ui.icon.AppIcons
import org.jetbrains.compose.resources.stringResource

/**
 * 技能与工具页：搜索 + 分段（技能 / 工具集）+ 每行开关 + 展开 SKILL.md。
 *
 * 外层 `LazyColumn`，单行内部普通 `Column`（硬约束 5：不嵌套 LazyColumn）。
 */
@Composable
fun SkillsPane(
    state: CapabilityState,
    onTabChange: (SkillsTab) -> Unit,
    onQueryChange: (String) -> Unit,
    onToggleSkill: (String, Boolean) -> Unit,
    onToggleToolset: (String, Boolean) -> Unit,
    onToggleDetail: (String) -> Unit,
    onRefresh: () -> Unit,
    onCatalogCategory: (String?) -> Unit = {},
    onToggleCatalogItem: (String) -> Unit = {},
    onInstallCatalog: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val skills = state.filteredSkills()
    val toolsets = state.filteredToolsets()
    val skillTotal = (state.skills as? Loadable.Ready)?.value
    val toolsetTotal = (state.toolsets as? Loadable.Ready)?.value
    val catalogTotal = (state.catalog as? Loadable.Ready)?.value

    Column(modifier = modifier.fillMaxSize()) {
        PaneHeader(
            title = stringResource(Res.string.capabilities_skills),
            subtitle = when (state.skillsTab) {
                SkillsTab.SKILLS -> skillTotal?.let {
                    stringResource(
                        Res.string.capability_skills_subtitle,
                        it.size,
                        it.count { skill -> skill.enabled },
                    )
                }.orEmpty()

                SkillsTab.TOOLSETS -> toolsetTotal?.let {
                    stringResource(
                        Res.string.capability_toolsets_subtitle,
                        it.size,
                        it.count { toolset -> toolset.enabled },
                    )
                }.orEmpty()

                SkillsTab.DISCOVER -> catalogTotal?.let {
                    stringResource(
                        Res.string.capability_catalog_subtitle,
                        it.size,
                        it.count { entry -> entry.installed },
                    )
                }.orEmpty()
            },
            onRefresh = onRefresh,
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SegmentButton(
                text = stringResource(Res.string.capability_tab_skills),
                selected = state.skillsTab == SkillsTab.SKILLS,
                onClick = { onTabChange(SkillsTab.SKILLS) },
            )
            SegmentButton(
                text = stringResource(Res.string.capability_tab_toolsets),
                selected = state.skillsTab == SkillsTab.TOOLSETS,
                onClick = { onTabChange(SkillsTab.TOOLSETS) },
            )
            SegmentButton(
                text = stringResource(Res.string.capability_tab_discover),
                selected = state.skillsTab == SkillsTab.DISCOVER,
                onClick = { onTabChange(SkillsTab.DISCOVER) },
            )
        }

        SearchField(
            value = state.skillQuery,
            onValueChange = onQueryChange,
            placeholder = stringResource(Res.string.capability_search_hint),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when (state.skillsTab) {
            SkillsTab.SKILLS -> PaneList(
                loadable = state.skills,
                filter = { list -> filterSkills(list, state.skillQuery) },
                emptyTitle = stringResource(Res.string.capability_skills_empty),
                emptyBody = stringResource(Res.string.capability_skills_empty_body),
                onRetry = onRefresh,
            ) { skill ->
                SkillRow(
                    skill = skill,
                    busy = state.busyKey == skill.name,
                    expanded = state.expandedSkill == skill.name,
                    content = state.skillContent,
                    onToggle = { onToggleSkill(skill.name, it) },
                    onToggleDetail = { onToggleDetail(skill.name) },
                    onRetryContent = { onToggleDetail(skill.name) },
                )
            }

            SkillsTab.TOOLSETS -> PaneList(
                loadable = state.toolsets,
                filter = { list -> filterToolsets(list, state.skillQuery) },
                emptyTitle = stringResource(Res.string.capability_toolsets_empty),
                emptyBody = stringResource(Res.string.capability_skills_empty_body),
                onRetry = onRefresh,
            ) { toolset ->
                ToolsetRow(
                    toolset = toolset,
                    busy = state.busyKey == toolset.name,
                    onToggle = { onToggleToolset(toolset.name, it) },
                )
            }

            SkillsTab.DISCOVER -> {
                CatalogCategoryRow(
                    categories = state.catalogCategories(),
                    selected = state.catalogCategory,
                    onSelect = onCatalogCategory,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PaneList(
                    loadable = state.catalog,
                    filter = { list -> filterCatalog(list, state.catalogCategory, state.skillQuery) },
                    emptyTitle = stringResource(Res.string.capability_catalog_empty),
                    emptyBody = stringResource(Res.string.capability_catalog_empty_body),
                    onRetry = onRefresh,
                ) { entry ->
                    CatalogRow(
                        entry = entry,
                        busy = state.busyKey == entry.identifier,
                        expanded = state.expandedCatalogItem == entry.identifier,
                        onToggleDetail = { onToggleCatalogItem(entry.identifier) },
                        onInstall = { onInstallCatalog(entry.identifier) },
                    )
                }
            }
        }
    }
}

/** 目录页顶部的分类筛选：一行横向滚动（分类有十几个，换行会吃掉半个屏幕）。 */
@Composable
private fun CatalogCategoryRow(
    categories: List<Pair<String, Int>>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    if (categories.isEmpty()) return
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "__all__") {
            SegmentButton(
                text = stringResource(Res.string.capability_catalog_all),
                selected = selected == null,
                onClick = { onSelect(null) },
            )
        }
        items(categories.size, key = { index -> categories[index].first }) { index ->
            val (category, count) = categories[index]
            SegmentButton(
                text = "$category $count",
                selected = selected == category,
                onClick = { onSelect(if (selected == category) null else category) },
            )
        }
    }
}

/**
 * 目录条目：名称 + 分类/来源标签 + 描述；展开显示 repo / identifier / tags 与安装按钮。
 *
 * 已安装的项不给安装按钮 —— 后端 `installed` 是按 profile 判定的，前端不自行推断。
 */
@Composable
private fun CatalogRow(
    entry: SkillCatalogEntryDto,
    busy: Boolean,
    expanded: Boolean,
    onToggleDetail: () -> Unit,
    onInstall: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleDetail).padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = entry.name, style = MaterialTheme.typography.bodyMedium)
            if (entry.category.isNotBlank()) Tag(text = entry.category)
            if (entry.source.isNotBlank()) Tag(text = entry.source)
            if (entry.installed) Tag(text = stringResource(Res.string.capability_catalog_installed))
        }
        if (entry.description.isNotBlank()) {
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            if (entry.repo.isNotBlank()) {
                Text(
                    text = entry.repo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.identifier.isNotBlank()) {
                Text(
                    text = entry.identifier,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.tags.isNotEmpty()) {
                Text(
                    text = entry.tags.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else if (!entry.installed) {
                    TextButton(onClick = onInstall) {
                        Text(stringResource(Res.string.capability_catalog_install))
                    }
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * 列表容器：装载态 + 过滤 + 空态一次处理。
 *
 * 过滤放在拿到数据之后做，装载中（Loading）不会被误判成"没有匹配结果"。
 */
@Composable
private fun <T> PaneList(
    loadable: Loadable<List<T>>,
    filter: (List<T>) -> List<T>,
    emptyTitle: String,
    emptyBody: String,
    onRetry: () -> Unit,
    row: @Composable (T) -> Unit,
) {
    when (loadable) {
        is Loadable.Idle, is Loadable.Loading -> LoadingBlock()

        is Loadable.Failed -> {
            val stale = loadable.stale
            if (stale == null) {
                Column(modifier = Modifier.fillMaxSize()) {
                    EmptyState(
                        title = stringResource(Res.string.capabilities_load_failed),
                        body = loadable.error.toNotice(),
                        icon = AppIcons.Error,
                        action = {
                            TextButton(onClick = onRetry) {
                                Text(stringResource(Res.string.capabilities_retry))
                            }
                        },
                    )
                }
            } else {
                RetryBanner(message = loadable.error.toNotice(), onRetry = onRetry)
                Rows(items = filter(stale), emptyTitle = emptyTitle, emptyBody = emptyBody, row = row)
            }
        }

        is Loadable.Ready -> Rows(
            items = filter(loadable.value),
            emptyTitle = emptyTitle,
            emptyBody = emptyBody,
            row = row,
        )
    }
}

@Composable
private fun <T> Rows(
    items: List<T>,
    emptyTitle: String,
    emptyBody: String,
    row: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize()) {
            EmptyState(title = emptyTitle, body = emptyBody)
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items) { item -> row(item) }
    }
}

internal fun filterSkills(skills: List<SkillDto>, query: String): List<SkillDto> {
    val text = query.trim()
    if (text.isEmpty()) return skills
    return skills.filter {
        it.name.contains(text, ignoreCase = true) ||
            it.description.contains(text, ignoreCase = true) ||
            it.category.contains(text, ignoreCase = true)
    }
}

internal fun filterToolsets(toolsets: List<ToolsetDto>, query: String): List<ToolsetDto> {
    val text = query.trim()
    if (text.isEmpty()) return toolsets
    return toolsets.filter {
        it.displayName.contains(text, ignoreCase = true) ||
            it.description.contains(text, ignoreCase = true) ||
            it.platformLabel.contains(text, ignoreCase = true)
    }
}

/** 目录过滤：分类（null = 全部）+ 搜索（名称 / 描述 / 分类 / 标签）。 */
internal fun filterCatalog(
    entries: List<SkillCatalogEntryDto>,
    category: String?,
    query: String,
): List<SkillCatalogEntryDto> {
    val text = query.trim()
    return entries.filter { entry ->
        (category == null || entry.category == category) &&
            (
                text.isEmpty() ||
                    entry.name.contains(text, ignoreCase = true) ||
                    entry.description.contains(text, ignoreCase = true) ||
                    entry.category.contains(text, ignoreCase = true) ||
                    entry.tags.any { tag -> tag.contains(text, ignoreCase = true) }
                )
    }
}

@Composable
private fun SkillRow(
    skill: SkillDto,
    busy: Boolean,
    expanded: Boolean,
    content: Loadable<String>,
    onToggle: (Boolean) -> Unit,
    onToggleDetail: () -> Unit,
    onRetryContent: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(text = skill.name, style = MaterialTheme.typography.bodyMedium)
                    Tag(
                        text = when (skill.provenance) {
                            "bundled" -> stringResource(Res.string.capability_provenance_bundled)
                            "agent" -> stringResource(Res.string.capability_provenance_agent)
                            else -> skill.provenance
                        },
                    )
                    if (skill.category.isNotBlank()) Tag(text = skill.category)
                    if (skill.usage > 0) Tag(text = stringResource(Res.string.capability_usage, skill.usage))
                }
                if (skill.description.isNotBlank()) {
                    Text(
                        text = skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            Switch(checked = skill.enabled, onCheckedChange = onToggle, enabled = !busy)
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            TextButton(onClick = onToggleDetail) {
                Text(
                    stringResource(
                        if (expanded) Res.string.capability_detail_hide else Res.string.capability_detail_show,
                    ),
                )
            }
        }

        if (expanded) {
            when (content) {
                is Loadable.Loading, is Loadable.Idle -> LoadingBlock()
                is Loadable.Failed -> RetryBanner(message = content.error.toNotice(), onRetry = onRetryContent)
                is Loadable.Ready -> SkillContentBlock(content.value)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** SKILL.md 原文：等宽、只读、限高滚动（内容可能几百行）。 */
@Composable
private fun SkillContentBlock(content: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = content.ifBlank { "（空）" },
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        )
    }
}

@Composable
private fun ToolsetRow(
    toolset: ToolsetDto,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = toolset.displayName, style = MaterialTheme.typography.bodyMedium)
                Tag(text = toolset.platformLabel.ifBlank { toolset.platform })
                if (!toolset.available) Tag(text = stringResource(Res.string.capability_toolset_unavailable))
                if (!toolset.configured) Tag(text = stringResource(Res.string.capability_toolset_unconfigured))
                Tag(text = stringResource(Res.string.capability_toolset_tools, toolset.tools.size))
            }
            if (toolset.description.isNotBlank()) {
                Text(
                    text = toolset.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        Switch(checked = toolset.enabled, onCheckedChange = onToggle, enabled = !busy)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** M3 没有分段按钮的桌面版式，这里用两个同形状按钮自绘（不用 NavigationRail）。 */
@Composable
internal fun SegmentButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/** 轻量搜索框：`BasicTextField`（硬约束 4 只针对聊天输入，这里同样不引组件库）。 */
@Composable
internal fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = dev.shinglem.hermes.ui.icon.AppIcons.Search,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            },
        )
    }
}
