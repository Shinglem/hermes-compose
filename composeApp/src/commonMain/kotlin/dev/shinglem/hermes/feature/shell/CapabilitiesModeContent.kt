package dev.shinglem.hermes.feature.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.feature.capabilities.CapabilityDetailPane
import dev.shinglem.hermes.feature.capabilities.CapabilityNavPane
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.capabilities_title
import org.jetbrains.compose.resources.stringResource

/** 能力模式的中栏 + 详情（原 `AppShell` 的 `ShellMode.CAPABILITIES` 分支）。 */
@Composable
fun CapabilitiesModeContent(deps: AppShellDeps, modifier: Modifier = Modifier) {
    val capabilityState = deps.capabilityState
    val capabilityViewModel = deps.capabilityViewModel
AdaptiveListDetail(
        modifier = modifier,
                    // 能力分类只有三项，用会话列表那套 430dp 会留大片空白。
                    listPaneWidth = 280.dp,
                    list = { onNavigateToDetail ->
                        CapabilityNavPane(
                            state = capabilityState,
                            onSelect = { section ->
                                capabilityViewModel.selectSection(section)
                                onNavigateToDetail()
                            },
                        )
                    },
                    detail = { singlePane, onBack ->
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (singlePane) {
                                DetailBackBar(
                                    title = stringResource(Res.string.capabilities_title),
                                    onBack = onBack,
                                )
                            }
                            CapabilityDetailPane(
                                state = capabilityState,
                                onSkillsTab = capabilityViewModel::selectSkillsTab,
                                onSkillQuery = capabilityViewModel::setSkillQuery,
                                onToggleSkill = capabilityViewModel::toggleSkill,
                                onToggleToolset = capabilityViewModel::toggleToolset,
                                onToggleSkillDetail = capabilityViewModel::toggleSkillDetail,
                                onCatalogCategory = capabilityViewModel::setCatalogCategory,
                                onToggleCatalogItem = capabilityViewModel::toggleCatalogItem,
                                onInstallCatalog = capabilityViewModel::installCatalogSkill,
                                onTogglePlatformDetail = capabilityViewModel::togglePlatformDetail,
                                onPlatformField = capabilityViewModel::setPlatformField,
                                onSavePlatform = capabilityViewModel::savePlatform,
                                onTestPlatform = capabilityViewModel::testPlatform,
                                onCreateCron = capabilityViewModel::startCreateCron,
                                onEditCron = capabilityViewModel::startEditCron,
                                onStartBlueprint = capabilityViewModel::startBlueprint,
                                onBlueprintValue = capabilityViewModel::updateBlueprintValue,
                                onSaveBlueprint = { key, values ->
                                    capabilityViewModel.instantiateBlueprint(key, values)
                                },
                                onCancelBlueprint = capabilityViewModel::cancelBlueprint,
                                onCancelCronDraft = capabilityViewModel::cancelCronDraft,
                                onCronDraftChange = capabilityViewModel::updateCronDraft,
                                onSaveCronDraft = capabilityViewModel::saveCronDraft,
                                onSetCronPaused = capabilityViewModel::setCronPaused,
                                onTriggerCron = capabilityViewModel::triggerCron,
                                onDeleteCron = capabilityViewModel::deleteCron,
                                onToggleCronRuns = capabilityViewModel::toggleCronRuns,
                                onRefresh = {
                                    capabilityViewModel.selectSection(capabilityState.section)
                                },
                                onDismissNotice = capabilityViewModel::dismissNotice,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    },
                )
}
