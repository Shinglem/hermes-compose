package dev.shinglem.hermes.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.config.EditorsOptions
import dev.shinglem.hermes.core.app.AutostartInfo
import dev.shinglem.hermes.core.app.OrphanTaskInfo
import dev.shinglem.hermes.feature.terminal.TerminalSettings
import dev.shinglem.hermes.ui.component.NavRow
import dev.shinglem.hermes.ui.component.NavSectionHeader

/**
 * 设置页的两个窗格：一级分组导航（list）+ 页面内容（detail）。
 *
 * 拆成两个 composable 是为了和「能力」「会话」共用同一套 [dev.shinglem.hermes.feature.shell.AdaptiveListDetail] ——
 * 中栏底色、选中块、窄窗退化三处表现由此统一。
 */

/**
 * 左栏：一级分组（可折叠）+ 组内二级页面。
 *
 * 所有入口都必须挂在某个分组下 —— 没有「裸在根上」的行。
 * 字段组由 [SettingsCatalog] 生成，自绘页（如终端外观）在 [SettingsCatalog.sections] 里登记。
 */
@Composable
fun SettingsNavPane(
    state: SettingsState,
    onSelect: (SettingsPage) -> Unit,
    onToggleSection: (String) -> Unit,
    onNavigateToDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        state.sections.forEach { section ->
            item(key = "section-${section.key}") {
                NavSectionHeader(
                    label = section.title,
                    expanded = section.key !in state.collapsedSections,
                    onToggle = { onToggleSection(section.key) },
                )
            }
            if (section.key !in state.collapsedSections) {
                items(section.pages.size, key = { index -> "${section.key}-$index" }) { index ->
                    val (label, page) = section.pages[index]
                    NavRow(
                        label = label,
                        selected = state.selected == page,
                        onClick = {
                            onSelect(page)
                            onNavigateToDetail()
                        },
                    )
                }
            }
        }
    }
}

/**
 * 右栏：按选中页渲染。
 *
 * 字段组由 [SettingsField.kind] 自动生成控件并带「保存」条；终端外观页自己即时落盘，不需要保存条。
 */
@Composable
fun SettingsDetailPane(
    state: SettingsState,
    onEdit: (String, String) -> Unit,
    onSave: () -> Unit,
    onRestart: () -> Unit,
    onDismissSaved: () -> Unit,
    terminalSettings: TerminalSettings = TerminalSettings(),
    onTerminalSettingsChange: ((TerminalSettings) -> TerminalSettings) -> Unit = {},
    providerState: ProviderKeysState = ProviderKeysState(),
    onProviderEdit: (String, String) -> Unit = { _, _ -> },
    onProviderSave: (String) -> Unit = {},
    onProviderRefresh: () -> Unit = {},
    onProviderDismissSaved: () -> Unit = {},
    onProviderQueryChange: (String) -> Unit = {},
    endpointsState: CustomEndpointState = CustomEndpointState(),
    endpointActions: EndpointPageCallbacks = EndpointPageCallbacks(),
    profilesState: ProfilesState = ProfilesState(),
    onProfileSelect: (String) -> Unit = {},
    onProfilesRefresh: () -> Unit = {},
    onProfileSoulChange: (String) -> Unit = {},
    onProfileSaveSoul: () -> Unit = {},
    onProfileStartCreate: () -> Unit = {},
    onProfileCancelCreate: () -> Unit = {},
    onProfileDraftName: (String) -> Unit = {},
    onProfileDraftClone: (String?) -> Unit = {},
    onProfileDraftSoul: (String) -> Unit = {},
    onProfileSubmitCreate: () -> Unit = {},
    onProfileDelete: (String) -> Unit = {},
    onProfileRename: (String, String) -> Unit = { _, _ -> },
    onProfileImport: (String) -> Unit = {},
    onProfileExport: (String, String) -> Unit = { _, _ -> },
    onProfileDismissNotice: () -> Unit = {},
    modelState: ModelSettingsState = ModelSettingsState(),
    modelActions: ModelPageCallbacks = ModelPageCallbacks(),
    moaState: MoaSettingsState = MoaSettingsState(),
    moaActions: MoaPageCallbacks = MoaPageCallbacks(),
    securityState: SecurityState = SecurityState(),
    onSecurityEdit: (String, String) -> Unit = { _, _ -> },
    onSecuritySave: (List<ConfigFieldSpec>) -> Unit = {},
    onSecurityRefresh: () -> Unit = {},
    officialDesktop: dev.shinglem.hermes.core.app.OfficialDesktopLauncher? = null,
    autostart: AutostartInfo = AutostartInfo.DISABLED,
    orphanTasks: List<OrphanTaskInfo> = emptyList(),
    onDeleteOrphanTask: (String) -> Unit = {},
    // ── v0.3.0 桌面组 ──
    notificationsEnabled: Boolean = true,
    onNotificationsToggle: (Boolean) -> Unit = {},
    nativeNotifications: Boolean = false,
    onNativeNotificationsToggle: (Boolean) -> Unit = {},
    browserHeaded: Boolean = false,
    onBrowserHeadedToggle: (Boolean) -> Unit = {},
    browserArgs: String = "",
    onBrowserArgsChange: (String) -> Unit = {},
    editors: EditorsOptions = EditorsOptions(),
    onEditorsSave: (EditorsOptions) -> Unit = {},
    updateState: UpdateState = UpdateState(),
    onCheckUpdate: () -> Unit = {},
    backendVersion: String = "",
    dataDir: String = "",
    logDir: String = "",
    maintenance: MaintenanceViewModel,
    /** 后端管理页（#15）：由桌面容器注入，缺省时该页留空。 */
    backendAdmin: BackendAdminViewModel? = null,
    /**
     * 维护类页面顶部的「应用到」档案清单（纯数据，来自 shell 的档案列表）。
     *
     * 切换只改本组页面的配置目标，**不**动会话页当前使用的档案。
     */
    profileChoices: List<ProfileChoice> = emptyList(),
    onSelectScopeProfile: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val maintenanceMemory by maintenance.memory.collectAsState()
    val maintenanceOps by maintenance.ops.collectAsState()
    val maintenanceCredentials by maintenance.credentials.collectAsState()
    val maintenanceUsage by maintenance.usage.collectAsState()
    val maintenanceSessions by maintenance.sessions.collectAsState()
    val maintenanceProfile by maintenance.profileKey.collectAsState()
    val maintenanceIntegrations by maintenance.integrations.collectAsState()

    val scopeProfile = if (profileChoices.isEmpty()) {
        null
    } else {
        ProfileScope(
            choices = profileChoices,
            activeKey = maintenanceProfile ?: DEFAULT_PROFILE_KEY,
            onSelect = onSelectScopeProfile,
        )
    }

    when (state.selected) {
        SettingsPage.Memory -> {
            MemoryPage(
                state = maintenanceMemory,
                profileKey = maintenanceProfile,
                profileScope = scopeProfile,
                onRefresh = maintenance::refreshMemory,
                onAskReset = maintenance::askResetMemory,
                onConfirmReset = maintenance::confirmResetMemory,
                onDismissReset = maintenance::dismissResetMemory,
                onConfigEdit = maintenance::editMemoryConfig,
                onConfigSave = maintenance::saveMemoryConfig,
                onConfigDiscard = maintenance::discardMemoryConfig,
                onOpenFile = maintenance::openMemoryFile,
                onFileEdit = maintenance::editMemoryFileText,
                onFileSave = maintenance::saveMemoryFile,
                onFileClose = maintenance::closeMemoryFile,
                onDismissStatus = maintenance::dismissMemoryNotice,
                modifier = modifier,
            )
            return
        }

        SettingsPage.Maintenance -> {
            MaintenancePage(
                state = maintenanceOps,
                profileKey = maintenanceProfile,
                profileScope = scopeProfile,
                onRefresh = maintenance::refreshOps,
                onRun = maintenance::runAction,
                onAskPrune = maintenance::askPruneCheckpoints,
                onConfirmPrune = maintenance::confirmPruneCheckpoints,
                onDismissPrune = maintenance::dismissPruneCheckpoints,
                onToggleCuratorPaused = maintenance::toggleCuratorPaused,
                onDownloadBackup = maintenance::downloadBackup,
                onImportArchiveChange = maintenance::editImportArchive,
                onImportBackup = maintenance::importBackup,
                onDebugShare = maintenance::runDebugShare,
                onDismissDebugUrls = maintenance::dismissDebugUrls,
                onDismissStatus = maintenance::dismissOpsNotice,
                modifier = modifier,
            )
            return
        }

        SettingsPage.Usage -> {
            UsagePage(
                state = maintenanceUsage,
                profileKey = maintenanceProfile,
                profileScope = scopeProfile,
                onRefresh = maintenance::refreshUsage,
                modifier = modifier,
            )
            return
        }

        SettingsPage.SessionMaintenance -> {
            SessionMaintenancePage(
                state = maintenanceSessions,
                profileKey = maintenanceProfile,
                profileScope = scopeProfile,
                onRefresh = maintenance::refreshSessionMaintenance,
                onAskDeleteEmpty = maintenance::askDeleteEmptySessions,
                onConfirmDeleteEmpty = maintenance::confirmDeleteEmptySessions,
                onDismissDeleteEmpty = maintenance::dismissDeleteEmptySessions,
                onPruneDaysChange = maintenance::editPruneDays,
                onPreviewPrune = maintenance::previewPrune,
                onConfirmPrune = maintenance::confirmPrune,
                onDismissPruneReport = maintenance::dismissPruneReport,
                onImportFile = maintenance::importSessionsFromFile,
                onDismissStatus = maintenance::dismissSessionNotice,
                modifier = modifier,
            )
            return
        }

        SettingsPage.CredentialPool -> {            CredentialPoolPage(
                state = maintenanceCredentials,
                profileKey = maintenanceProfile,
                profileScope = scopeProfile,
                onRefresh = maintenance::refreshCredentials,
                onProviderChange = maintenance::editCredentialProvider,
                onKeyChange = maintenance::editCredentialKey,
                onLabelChange = maintenance::editCredentialLabel,
                onAdd = maintenance::addCredential,
                onAskDelete = maintenance::askDeleteCredential,
                onConfirmDelete = maintenance::confirmDeleteCredential,
                onDismissDelete = maintenance::dismissDeleteCredential,
                onDismissStatus = maintenance::dismissCredentialNotice,
                modifier = modifier,
            )
            return
        }

        SettingsPage.Integrations -> {
            IntegrationsPage(
                state = maintenanceIntegrations,
                profileScope = scopeProfile,
                onRefresh = maintenance::refreshIntegrations,
                onEventChange = maintenance::editHookEvent,
                onCommandChange = maintenance::editHookCommand,
                onMatcherChange = maintenance::editHookMatcher,
                onAddHook = maintenance::addHook,
                onDeleteHook = maintenance::deleteHook,
                onApprove = maintenance::approvePairing,
                onRevoke = maintenance::revokePairing,
                onDismissStatus = maintenance::dismissIntegrationNotice,
                modifier = modifier,
            )
            return
        }

        SettingsPage.Notifications -> {
            NotificationsPage(
                enabled = notificationsEnabled,
                onToggle = onNotificationsToggle,
                nativeEnabled = nativeNotifications,
                onNativeToggle = onNativeNotificationsToggle,
                modifier = modifier,
            )
            return
        }

        SettingsPage.Shortcuts -> {
            ShortcutsPage(modifier = modifier)
            return
        }

        SettingsPage.Browser -> {
            BrowserPage(
                headed = browserHeaded,
                onHeadedToggle = onBrowserHeadedToggle,
                args = browserArgs,
                onArgsChange = onBrowserArgsChange,
                modifier = modifier,
            )
            return
        }

        SettingsPage.Backend -> {
            // 后端管理页需要真实的 provider / transport：没注入时不画假界面。
            backendAdmin?.let { BackendPage(viewModel = it, modifier = modifier) }
            return
        }

        SettingsPage.Editors -> {
            EditorsPage(
                editors = editors,
                onSave = onEditorsSave,
                modifier = modifier,
            )
            return
        }

        SettingsPage.About -> {
            AboutPage(
                backendVersion = backendVersion,
                dataDir = dataDir,
                logDir = logDir,
                update = updateState,
                onCheckUpdate = onCheckUpdate,
                modifier = modifier,
            )
            return
        }

        else -> Unit
    }

    if (state.selected == SettingsPage.Debug) {
        DebugPage(launcher = officialDesktop, modifier = modifier)
        return
    }
    if (state.selected == SettingsPage.Approvals || state.selected == SettingsPage.Privacy ||
        state.selected == SettingsPage.Checkpoints
    ) {
        val tab = when (state.selected) {
            SettingsPage.Approvals -> SecurityTab.APPROVALS
            SettingsPage.Privacy -> SecurityTab.PRIVACY
            else -> SecurityTab.CHECKPOINTS
        }
        SecurityFormPage(
            title = when (tab) {
                SecurityTab.APPROVALS -> "审批"
                SecurityTab.PRIVACY -> "隐私与网络"
                SecurityTab.CHECKPOINTS -> "检查点"
            },
            subtitle = when (tab) {
                SecurityTab.APPROVALS -> "危险动作需要确认的策略；改完立即对后端生效（审批模式会热广播给运行中的会话）。"
                SecurityTab.PRIVACY -> "日志脱敏与内网访问限制；改完对后续请求生效。"
                SecurityTab.CHECKPOINTS -> "文件改动快照；开关对**新会话**生效。"
            },
            specs = securityFieldsOf(tab),
            state = securityState,
            onEdit = onSecurityEdit,
            onSave = onSecuritySave,
            onReload = onSecurityRefresh,
            modifier = modifier,
        )
        return
    }
    if (state.selected == SettingsPage.MainModel) {
        MainModelPage(
            state = modelState,
            onProvider = modelActions.onProvider,
            onModel = modelActions.onModel,
            onContextChange = modelActions.onContextChange,
            onSaveContext = modelActions.onSaveContext,
            onApply = modelActions.onApply,
            onConfirmPending = modelActions.onConfirmPending,
            onDismissPending = modelActions.onDismissPending,
            onReloadCatalog = modelActions.onReloadCatalog,
            modifier = modifier,
        )
        return
    }
    if (state.selected == SettingsPage.AuxModels) {
        AuxModelPage(
            state = modelState,
            onAssign = modelActions.onAssignAux,
            onReset = modelActions.onResetAux,
            onRefresh = modelActions.onRefreshAux,
            modifier = modifier,
        )
        return
    }
    if (state.selected == SettingsPage.FallbackModels) {
        FallbackModelPage(
            state = modelState,
            onAdd = modelActions.onAddFallback,
            onRemove = modelActions.onRemoveFallback,
            onSave = modelActions.onSaveFallback,
            onRefresh = modelActions.onRefreshFallback,
            modifier = modifier,
        )
        return
    }
    if (state.selected == SettingsPage.MoaModels) {
        MoaModelsPage(
            state = moaState,
            modelState = modelState,
            onSelectPreset = moaActions.onSelectPreset,
            onAddPreset = moaActions.onAddPreset,
            onDeletePreset = moaActions.onDeletePreset,
            onSetDefault = moaActions.onSetDefault,
            onSetActive = moaActions.onSetActive,
            onPresetChange = moaActions.onPresetChange,
            onAddReference = moaActions.onAddReference,
            onRemoveReference = moaActions.onRemoveReference,
            onReferenceChange = moaActions.onReferenceChange,
            onSave = moaActions.onSave,
            onRefresh = moaActions.onRefresh,
            modifier = modifier,
        )
        return
    }
    if (state.selected == SettingsPage.Profiles) {
        ProfilesPage(
            state = profilesState,
            onSelect = onProfileSelect,
            onRefresh = onProfilesRefresh,
            onSoulChange = onProfileSoulChange,
            onSaveSoul = onProfileSaveSoul,
            onStartCreate = onProfileStartCreate,
            onCancelCreate = onProfileCancelCreate,
            onDraftName = onProfileDraftName,
            onDraftClone = onProfileDraftClone,
            onDraftSoul = onProfileDraftSoul,
            onSubmitCreate = onProfileSubmitCreate,
            onDelete = onProfileDelete,
            onRename = onProfileRename,
            onImport = onProfileImport,
            onExport = onProfileExport,
            onDismissNotice = onProfileDismissNotice,
            modifier = modifier,
        )
        return
    }
    if (state.selected == SettingsPage.ProviderKeys) {
        ProviderKeysPage(
            state = providerState,
            onEdit = onProviderEdit,
            onSave = onProviderSave,
            onRefresh = onProviderRefresh,
            onDismissSaved = onProviderDismissSaved,
            onQueryChange = onProviderQueryChange,
            modifier = modifier,
        )
        return
    }
    if (state.selected == SettingsPage.CustomEndpoints) {
        CustomEndpointPage(
            state = endpointsState,
            onCreate = endpointActions.onCreate,
            onEditEndpoint = endpointActions.onEdit,
            onDraftChange = endpointActions.onDraftChange,
            onValidate = endpointActions.onValidate,
            onSave = endpointActions.onSave,
            onCancel = endpointActions.onCancel,
            onActivate = endpointActions.onActivate,
            onAskDelete = endpointActions.onAskDelete,
            onConfirmDelete = endpointActions.onConfirmDelete,
            onDismissDelete = endpointActions.onDismissDelete,
            onRefresh = endpointActions.onRefresh,
            modifier = modifier,
        )
        return
    }
    if (state.selected == SettingsPage.TerminalAppearance) {
        TerminalSettingsPage(
            settings = terminalSettings,
            onChange = onTerminalSettingsChange,
            modifier = modifier.fillMaxWidth(),
        )
        return
    }

    val group = state.currentGroup ?: return

    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            Text(group.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                if (group.fields.any { it.scope == SettingsScope.LIVE }) {
                    "带「即时」标记的项保存后立即生效，其余需要重启客户端"
                } else {
                    "本组配置保存后需要重启客户端才生效"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            group.fields.forEach { field ->
                FieldRow(field = field, value = state.values[field.key].orEmpty(), onEdit = onEdit)
                if (field.key == SettingsCatalog.KEY_AUTOSTART) {
                    autostartHint(autostart)?.let { hint ->
                        Text(
                            hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (autostart.pending) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (orphanTasks.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        OrphanTaskList(
                            tasks = orphanTasks,
                            canDelete = autostart.elevated,
                            onDelete = onDeleteOrphanTask,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                state.error?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                state.saved?.let { saved ->
                    Text(
                        if (saved.restartRequired) {
                            "已保存到 custom 覆盖层，重启客户端后生效"
                        } else {
                            "已保存，立即生效"
                        },
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable(onClick = onDismissSaved),
                    )
                }
            }
            if (state.saved?.restartRequired == true) {
                OutlinedButton(onClick = onRestart, enabled = !state.restarting) {
                    Text(if (state.restarting) "正在重启…" else "重启客户端")
                }
                Spacer(Modifier.width(8.dp))
            }
            Button(onClick = onSave, enabled = !state.restarting) { Text("保存") }
        }
    }
}
/**
 * 开机自启的落地状态。三类不一致都要说清楚 —— 否则用户会看到"勾了却没反应"。
 */
private fun autostartHint(info: AutostartInfo): String? = when {
    info.configured && info.installed -> "已启用：计划任务 ${info.taskName}"
    info.configured && info.pending && !info.elevated ->
        "尚未生效：写计划任务需要管理员权限。请把上面的「要求管理员权限」设为 ask 并重启客户端"
    info.configured && info.pending -> "尚未生效：任务写入失败，详见 logs/startup.log"
    info.configured -> "正在启用：任务 ${info.taskName}"
    info.installed -> "配置已关闭，但系统里仍有任务 ${info.taskName}；以管理员运行后会自动删除"
    else -> null
}

/**
 * 失效的自启任务（便携包挪过位置留下的）。
 *
 * 只读列出 + 逐条删除：本机有个名字带 `Hermes` 前缀但指向 `Netcatty.exe` 的任务，
 * 任何"按前缀批量删"的实现都会误伤，所以这里连多选都不做。
 */
@Composable
private fun OrphanTaskList(
    tasks: List<OrphanTaskInfo>,
    canDelete: Boolean,
    onDelete: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            if (canDelete) {
                "发现 ${tasks.size} 个失效的自启任务（指向已不存在的程序）"
            } else {
                "发现 ${tasks.size} 个失效的自启任务；删除需要管理员权限（以管理员运行后这里会出现删除按钮）"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        tasks.forEach { task ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${task.name} → ${task.command}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onDelete(task.name) }, enabled = canDelete) { Text("删除") }
            }
        }
    }
}

/** 模型页的回调打包：设置详情窗格参数已经很多，别再平铺十几个函数进来。 */
data class ModelPageCallbacks(
    val onProvider: (String) -> Unit = {},
    val onModel: (String) -> Unit = {},
    val onContextChange: (String) -> Unit = {},
    val onSaveContext: () -> Unit = {},
    val onApply: () -> Unit = {},
    val onConfirmPending: () -> Unit = {},
    val onDismissPending: () -> Unit = {},
    val onReloadCatalog: () -> Unit = {},
    val onAssignAux: (dev.shinglem.hermes.core.contract.AuxTaskDto) -> Unit = {},
    val onResetAux: () -> Unit = {},
    val onRefreshAux: () -> Unit = {},
    val onAddFallback: () -> Unit = {},
    val onRemoveFallback: (Int) -> Unit = {},
    val onSaveFallback: () -> Unit = {},
    val onRefreshFallback: () -> Unit = {},
)

/** 自定义端点页的回调打包（同 [ModelPageCallbacks]，避免详情窗格参数再加十来个）。 */
data class EndpointPageCallbacks(
    val onCreate: () -> Unit = {},
    val onEdit: (dev.shinglem.hermes.core.contract.CustomEndpointDto) -> Unit = {},
    val onDraftChange: (EndpointDraft) -> Unit = {},
    val onValidate: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onActivate: (String) -> Unit = {},
    val onAskDelete: (String) -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onDismissDelete: () -> Unit = {},
    val onRefresh: () -> Unit = {},
)

/** MoA 页的回调打包。 */
data class MoaPageCallbacks(
    val onSelectPreset: (String) -> Unit = {},
    val onAddPreset: () -> Unit = {},
    val onDeletePreset: () -> Unit = {},
    val onSetDefault: () -> Unit = {},
    val onSetActive: () -> Unit = {},
    val onPresetChange: (dev.shinglem.hermes.core.contract.MoaPresetDto) -> Unit = {},
    val onAddReference: () -> Unit = {},
    val onRemoveReference: (Int) -> Unit = {},
    val onReferenceChange: (Int, dev.shinglem.hermes.core.contract.MoaSlotDto) -> Unit = { _, _ -> },
    val onSave: () -> Unit = {},
    val onRefresh: () -> Unit = {},
)

@Composable
private fun FieldRow(field: SettingsField, value: String, onEdit: (String, String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(field.label, style = MaterialTheme.typography.bodyMedium)
            if (field.scope == SettingsScope.LIVE) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "即时",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        field.hint?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))

        when (field.kind) {
            SettingsKind.BOOLEAN -> Switch(
                checked = value.equals("true", ignoreCase = true),
                onCheckedChange = { onEdit(field.key, it.toString()) },
            )

            SettingsKind.ENUM -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                field.enumValues.forEach { option ->
                    FilterChip(
                        selected = value.equals(option, ignoreCase = true),
                        onClick = { onEdit(field.key, option) },
                        label = { Text(field.enumLabels[option] ?: option) },
                    )
                }
            }

            SettingsKind.NUMBER, SettingsKind.TEXT -> OutlinedTextField(
                value = value,
                onValueChange = { onEdit(field.key, it) },
                singleLine = true,
                modifier = Modifier.widthIn(min = 260.dp, max = 480.dp).fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
