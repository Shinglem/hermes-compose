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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    updateState: UpdateState = UpdateState(),
    onCheckUpdate: () -> Unit = {},
    backendVersion: String = "",
    dataDir: String = "",
    logDir: String = "",
    modifier: Modifier = Modifier,
) {
    when (state.selected) {
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
                officialDesktop = officialDesktop,
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
