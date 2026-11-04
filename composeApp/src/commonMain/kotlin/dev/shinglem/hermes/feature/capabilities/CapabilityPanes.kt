package dev.shinglem.hermes.feature.capabilities

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.capabilities_cron
import dev.shinglem.hermes.resources.capabilities_platforms
import dev.shinglem.hermes.resources.capabilities_skills
import dev.shinglem.hermes.resources.capabilities_title
import dev.shinglem.hermes.ui.component.NavRow
import dev.shinglem.hermes.ui.icon.AppIcons
import org.jetbrains.compose.resources.stringResource

/**
 * 能力模式的两个窗格：分类导航（list）+ 设置页（detail）。
 *
 * 拆成两个 composable 是为了能分别放进 `AdaptiveListDetail` 的两个槽，
 * 从而在窄窗下退化单栏、宽窗下并排。三页各自独立装载，一页失败不影响其它页。
 */

@Composable
fun CapabilityNavPane(
    state: CapabilityState,
    onSelect: (CapabilitySection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxHeight().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = stringResource(Res.string.capabilities_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
        )
        CapabilitySection.entries.forEach { section ->
            CapabilityRow(
                section = section,
                selected = section == state.section,
                onClick = { onSelect(section) },
            )
        }
    }
}

@Composable
fun CapabilityDetailPane(
    state: CapabilityState,
    onSkillsTab: (SkillsTab) -> Unit,
    onSkillQuery: (String) -> Unit,
    onToggleSkill: (String, Boolean) -> Unit,
    onToggleToolset: (String, Boolean) -> Unit,
    onToggleSkillDetail: (String) -> Unit,
    onCatalogCategory: (String?) -> Unit = {},
    onToggleCatalogItem: (String) -> Unit = {},
    onInstallCatalog: (String) -> Unit = {},
    onTogglePlatformDetail: (String) -> Unit,
    onPlatformField: (String, String, String) -> Unit,
    onSavePlatform: (String, Boolean?) -> Unit,
    onTestPlatform: (String) -> Unit,
    onCreateCron: () -> Unit,
    onEditCron: (String) -> Unit,
    onStartBlueprint: (String) -> Unit,
    onBlueprintValue: (String, String) -> Unit,
    onSaveBlueprint: (String, Map<String, String>) -> Unit,
    onCancelBlueprint: () -> Unit,
    onCancelCronDraft: () -> Unit,
    onCronDraftChange: ((CronDraft) -> CronDraft) -> Unit,
    onSaveCronDraft: () -> Unit,
    onSetCronPaused: (String, Boolean) -> Unit,
    onTriggerCron: (String) -> Unit,
    onDeleteCron: (String) -> Unit,
    onToggleCronRuns: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    Box(modifier = modifier.fillMaxSize()) {
        when (state.section) {
            CapabilitySection.SKILLS -> SkillsPane(
                state = state,
                onTabChange = onSkillsTab,
                onQueryChange = onSkillQuery,
                onToggleSkill = onToggleSkill,
                onToggleToolset = onToggleToolset,
                onToggleDetail = onToggleSkillDetail,
                onRefresh = onRefresh,
                onCatalogCategory = onCatalogCategory,
                onToggleCatalogItem = onToggleCatalogItem,
                onInstallCatalog = onInstallCatalog,
            )

            CapabilitySection.PLATFORMS -> PlatformsPane(
                state = state,
                onToggleDetail = onTogglePlatformDetail,
                onFieldChange = onPlatformField,
                onSave = onSavePlatform,
                onTest = onTestPlatform,
                onRefresh = onRefresh,
            )

            CapabilitySection.CRON -> CronPane(
                state = state,
                onCreate = onCreateCron,
                onEdit = onEditCron,
                onStartBlueprint = onStartBlueprint,
                onBlueprintValue = onBlueprintValue,
                onSaveBlueprint = onSaveBlueprint,
                onCancelBlueprint = onCancelBlueprint,
                onCancelDraft = onCancelCronDraft,
                onDraftChange = onCronDraftChange,
                onSaveDraft = onSaveCronDraft,
                onSetPaused = onSetCronPaused,
                onTrigger = onTriggerCron,
                onDelete = onDeleteCron,
                onToggleRuns = onToggleCronRuns,
                onRefresh = onRefresh,
            )
        }
        // 提示用 Snackbar 浮在内容上：早先用顶部横条会挤动列表，
        // 刚点完操作紧接着点下一项时必然点空。
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }

    // notice 是一次性的：转成 Snackbar 展示完就清掉，避免下次进页面又弹一遍。
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = notice,
            withDismissAction = true,
            duration = if (state.noticeIsError) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        onDismissNotice()
    }
}

@Composable
private fun CapabilityRow(
    section: CapabilitySection,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavRow(
        label = section.label(),
        selected = selected,
        onClick = onClick,
        icon = section.icon(),
    )
}

@Composable
private fun CapabilitySection.label(): String = when (this) {
    CapabilitySection.SKILLS -> stringResource(Res.string.capabilities_skills)
    CapabilitySection.PLATFORMS -> stringResource(Res.string.capabilities_platforms)
    CapabilitySection.CRON -> stringResource(Res.string.capabilities_cron)
}

private fun CapabilitySection.icon(): ImageVector = when (this) {
    CapabilitySection.SKILLS -> AppIcons.Skills
    CapabilitySection.PLATFORMS -> AppIcons.Platforms
    CapabilitySection.CRON -> AppIcons.Cron
}
