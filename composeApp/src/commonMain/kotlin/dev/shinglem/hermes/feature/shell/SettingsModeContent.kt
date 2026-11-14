package dev.shinglem.hermes.feature.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.app.AutostartInfo
import dev.shinglem.hermes.core.app.OrphanTaskInfo
import dev.shinglem.hermes.di.AppContainer
import dev.shinglem.hermes.feature.settings.DEFAULT_PROFILE_KEY
import dev.shinglem.hermes.feature.settings.ProfileScope
import dev.shinglem.hermes.feature.settings.ProfileScopeChips
import dev.shinglem.hermes.feature.settings.SettingsDetailPane
import dev.shinglem.hermes.feature.settings.SettingsDetailScope
import dev.shinglem.hermes.feature.settings.SettingsNavPane
import dev.shinglem.hermes.feature.settings.hasOwnProfileScope
import dev.shinglem.hermes.feature.settings.isProfileScoped
import dev.shinglem.hermes.feature.terminal.TerminalSettings
import dev.shinglem.hermes.platform.appDataDir
import dev.shinglem.hermes.component.updater.UpdaterIntent
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.rail_settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** 设置模式的中栏 + 详情（原 `AppShell` 的 `ShellMode.SETTINGS` 分支）。 */
@Composable
fun SettingsModeContent(
    deps: AppShellDeps,
    container: AppContainer,
    terminalSettings: TerminalSettings,
    autostartInfo: AutostartInfo,
    orphanTasks: List<OrphanTaskInfo>,
    composerScope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val shellState = deps.shellState
    val settingsState = deps.settingsState
    val settingsViewModel = deps.settingsViewModel
    val maintenance = deps.maintenance
    val maintenanceProfileChoices = deps.maintenanceProfileChoices
    val maintenanceScopeKey = deps.maintenanceScopeKey
    val providerKeysComponent = deps.providerKeysComponent
    val customEndpointComponent = deps.customEndpointComponent
    val profilesComponent = deps.profilesComponent
    val modelSettingsComponent = deps.modelSettingsComponent
    val moaComponent = deps.moaComponent
    val securityComponent = deps.securityComponent
    val updaterComponent = deps.updaterComponent
    val updateState = deps.updateState
    val backendComponent = deps.backendComponent
AdaptiveListDetail(
        modifier = modifier,
        listPaneWidth = 240.dp,
                    list = { onNavigateToDetail ->
                        SettingsNavPane(
                            state = settingsState,
                            onSelect = settingsViewModel::select,
                            onToggleSection = settingsViewModel::toggleSection,
                            onNavigateToDetail = onNavigateToDetail,
                        )
                    },
                    detail = { singlePane, onBack ->
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (singlePane) {
                                DetailBackBar(
                                    title = stringResource(Res.string.rail_settings),
                                    onBack = onBack,
                                )
                            }
                            val showScopeBar = settingsState.selected.isProfileScoped() &&
                                !settingsState.selected.hasOwnProfileScope()
                            if (showScopeBar) {
                                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                                    ProfileScopeChips(
                                        ProfileScope(
                                            choices = maintenanceProfileChoices,
                                            activeKey = maintenanceScopeKey ?: DEFAULT_PROFILE_KEY,
                                            onSelect = maintenance::setScopeProfile,
                                        ),
                                    )
                                }
                            }
                            SettingsDetailPane(
                                scope = dev.shinglem.hermes.feature.settings.SettingsDetailScope(
                                    state = settingsState,
                                    onEdit = settingsViewModel::edit,
                                    onSave = settingsViewModel::save,
                                    onRestart = settingsViewModel::restart,
                                    onDismissSaved = settingsViewModel::dismissSaved,
                                    terminalSettings = terminalSettings,
                                    onTerminalSettingsChange = container.terminalViewModel::updateSettings,
                                    providerKeys = providerKeysComponent,
                                    customEndpoints = customEndpointComponent,
    
                                    profiles = profilesComponent,
    
                                    modelSettings = modelSettingsComponent,
                                    moa = moaComponent,
                                    security = securityComponent,
                                    officialDesktop = container.officialDesktop,
                                    autostart = autostartInfo,
                                    orphanTasks = orphanTasks,
                                    notificationsEnabled = settingsState.notificationsEnabled,
                                    onNotificationsToggle = settingsViewModel::setNotificationsEnabled,
                                    nativeNotifications = settingsState.nativeNotifications,
                                    onNativeNotificationsToggle = settingsViewModel::setNativeNotifications,
                                    browserHeaded = settingsState.browserHeaded,
                                    onBrowserHeadedToggle = settingsViewModel::setBrowserHeaded,
                                    browserArgs = settingsState.browserArgs,
                                    onBrowserArgsChange = settingsViewModel::setBrowserArgs,
                                    editors = settingsState.editors,
                                    onEditorsSave = settingsViewModel::setEditors,
                                    updateState = updateState,
                                    onCheckUpdate = { updaterComponent.onIntent(UpdaterIntent.Check()) },
                                    backendVersion = shellState.health?.version.orEmpty(),
                                    dataDir = appDataDir,
                                    logDir = container.config.app.log.dir,
                                    maintenance = maintenance,
                                    backendAdmin = backendComponent,
                                    profileChoices = maintenanceProfileChoices,
                                    onSelectScopeProfile = maintenance::setScopeProfile,
                                    onDeleteOrphanTask = { name ->
                                        composerScope.launch(Dispatchers.IO) {
                                            if (container.deleteOrphanTask(name)) {
                                                container.orphanTasks.update { list -> list.filterNot { it.name == name } }
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                ),
                            )
                        }
                    },
                )
}
