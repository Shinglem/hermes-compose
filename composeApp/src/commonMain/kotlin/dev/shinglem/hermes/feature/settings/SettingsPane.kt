package dev.shinglem.hermes.feature.settings

import dev.shinglem.hermes.component.settings.CustomEndpointEffect
import dev.shinglem.hermes.component.settings.ModelSettingsEffect
import dev.shinglem.hermes.component.settings.MoaEffect
import dev.shinglem.hermes.component.settings.ProviderKeysEffect
import dev.shinglem.hermes.component.settings.ProfilesEffect
import dev.shinglem.hermes.component.settings.SecurityEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import dev.shinglem.hermes.component.updater.UpdaterState as UpdateState
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
import dev.shinglem.hermes.component.settings.CustomEndpointComponent
import dev.shinglem.hermes.component.settings.ProfilesComponent
import dev.shinglem.hermes.ui.settings.ProfilesContent
import dev.shinglem.hermes.component.settings.ModelSettingsComponent
import dev.shinglem.hermes.component.settings.ModelSettingsIntent
import dev.shinglem.hermes.component.settings.MoaComponent
import dev.shinglem.hermes.component.settings.ProviderKeysComponent
import dev.shinglem.hermes.component.settings.SecurityComponent
import dev.shinglem.hermes.ui.settings.CustomEndpointContent
import dev.shinglem.hermes.ui.settings.MoaContent
import dev.shinglem.hermes.ui.settings.ProviderKeysContent
import dev.shinglem.hermes.ui.settings.SecurityContent
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
    scope: SettingsDetailScope,
) {
    // 过渡期解构：函数体里 200+ 处直接引用这些局部名。
    // 全部改成 scope.xxx 是纯机械改名，留到 S8；这里先让签名达标（38 → 3 参数）。
    val maintenanceMemory by scope.maintenance.memory.collectAsState()
    val maintenanceOps by scope.maintenance.ops.collectAsState()
    val maintenanceCredentials by scope.maintenance.credentials.collectAsState()
    val maintenanceUsage by scope.maintenance.usage.collectAsState()
    val maintenanceSessions by scope.maintenance.sessions.collectAsState()
    val maintenanceProfile by scope.maintenance.profileKey.collectAsState()
    val maintenanceIntegrations by scope.maintenance.integrations.collectAsState()

    val scopeProfile = if (scope.profileChoices.isEmpty()) {
        null
    } else {
        ProfileScope(
            choices = scope.profileChoices,
            activeKey = maintenanceProfile ?: DEFAULT_PROFILE_KEY,
            onSelect = scope.onSelectScopeProfile,
        )
    }

    // 设置页各域的一次性提示（Toast）统一走这里的 Snackbar：
    // 6 个 Component 各自 publish，但它们的 UI 页面从来没接过 effects —— 结果是
    // "保存成功"只有 scope.state 里的 notice，切走再回来就没了。这里收一次，全局生效。
    val snackbarHostState = remember { SnackbarHostState() }
    val toastScope = rememberCoroutineScope()
    LaunchedEffect(scope.security, scope.profiles, scope.providerKeys, scope.moa, scope.modelSettings, scope.customEndpoints) {
        launch {
            scope.security?.effects?.collect { effect ->
                if (effect is SecurityEffect.Toast) toastScope.launch { snackbarHostState.showSnackbar(effect.text) }
            }
        }
        launch {
            scope.profiles?.effects?.collect { effect ->
                if (effect is ProfilesEffect.Toast) toastScope.launch { snackbarHostState.showSnackbar(effect.text) }
            }
        }
        launch {
            scope.providerKeys?.effects?.collect { effect ->
                if (effect is ProviderKeysEffect.Toast) toastScope.launch { snackbarHostState.showSnackbar(effect.text) }
            }
        }
        launch {
            scope.moa?.effects?.collect { effect ->
                if (effect is MoaEffect.Toast) toastScope.launch { snackbarHostState.showSnackbar(effect.text) }
            }
        }
        launch {
            scope.modelSettings?.effects?.collect { effect ->
                if (effect is ModelSettingsEffect.Toast) toastScope.launch { snackbarHostState.showSnackbar(effect.text) }
            }
        }
        launch {
            scope.customEndpoints?.effects?.collect { effect ->
                if (effect is CustomEndpointEffect.Toast) toastScope.launch { snackbarHostState.showSnackbar(effect.text) }
            }
        }
    }

    Box(scope.modifier) {
    when (scope.state.selected) {
        SettingsPage.Memory -> {
            MemoryPage(
                state = maintenanceMemory,
                profileKey = maintenanceProfile,
                profileScope = scopeProfile,
                onRefresh = scope.maintenance::refreshMemory,
                onAskReset = scope.maintenance::askResetMemory,
                onConfirmReset = scope.maintenance::confirmResetMemory,
                onDismissReset = scope.maintenance::dismissResetMemory,
                onConfigEdit = scope.maintenance::editMemoryConfig,
                onConfigSave = scope.maintenance::saveMemoryConfig,
                onConfigDiscard = scope.maintenance::discardMemoryConfig,
                onOpenFile = scope.maintenance::openMemoryFile,
                onFileEdit = scope.maintenance::editMemoryFileText,
                onFileSave = scope.maintenance::saveMemoryFile,
                onFileClose = scope.maintenance::closeMemoryFile,
                onDismissStatus = scope.maintenance::dismissMemoryNotice,
                modifier = scope.modifier,
            )
            return
        }

        SettingsPage.Maintenance -> {
            MaintenancePage(
                state = maintenanceOps,
                profileKey = maintenanceProfile,
                profileScope = scopeProfile,
                onRefresh = scope.maintenance::refreshOps,
                onRun = scope.maintenance::runAction,
                onAskPrune = scope.maintenance::askPruneCheckpoints,
                onConfirmPrune = scope.maintenance::confirmPruneCheckpoints,
                onDismissPrune = scope.maintenance::dismissPruneCheckpoints,
                onToggleCuratorPaused = scope.maintenance::toggleCuratorPaused,
                onDownloadBackup = scope.maintenance::downloadBackup,
                onImportArchiveChange = scope.maintenance::editImportArchive,
                onImportBackup = scope.maintenance::importBackup,
                onDebugShare = scope.maintenance::runDebugShare,
                onDismissDebugUrls = scope.maintenance::dismissDebugUrls,
                onDismissStatus = scope.maintenance::dismissOpsNotice,
                modifier = scope.modifier,
            )
            return
        }

        SettingsPage.Usage -> {
            UsagePage(
                state = maintenanceUsage,
                profileKey = maintenanceProfile,
                profileScope = scopeProfile,
                onRefresh = scope.maintenance::refreshUsage,
                modifier = scope.modifier,
            )
            return
        }

        SettingsPage.SessionMaintenance -> {
            SessionMaintenancePage(
                state = maintenanceSessions,
                profileKey = maintenanceProfile,
                profileScope = scopeProfile,
                onRefresh = scope.maintenance::refreshSessionMaintenance,
                onAskDeleteEmpty = scope.maintenance::askDeleteEmptySessions,
                onConfirmDeleteEmpty = scope.maintenance::confirmDeleteEmptySessions,
                onDismissDeleteEmpty = scope.maintenance::dismissDeleteEmptySessions,
                onPruneDaysChange = scope.maintenance::editPruneDays,
                onPreviewPrune = scope.maintenance::previewPrune,
                onConfirmPrune = scope.maintenance::confirmPrune,
                onDismissPruneReport = scope.maintenance::dismissPruneReport,
                onImportFile = scope.maintenance::importSessionsFromFile,
                onDismissStatus = scope.maintenance::dismissSessionNotice,
                modifier = scope.modifier,
            )
            return
        }

        SettingsPage.CredentialPool -> {            CredentialPoolPage(
                state = maintenanceCredentials,
                profileKey = maintenanceProfile,
                profileScope = scopeProfile,
                onRefresh = scope.maintenance::refreshCredentials,
                onProviderChange = scope.maintenance::editCredentialProvider,
                onKeyChange = scope.maintenance::editCredentialKey,
                onLabelChange = scope.maintenance::editCredentialLabel,
                onAdd = scope.maintenance::addCredential,
                onAskDelete = scope.maintenance::askDeleteCredential,
                onConfirmDelete = scope.maintenance::confirmDeleteCredential,
                onDismissDelete = scope.maintenance::dismissDeleteCredential,
                onDismissStatus = scope.maintenance::dismissCredentialNotice,
                modifier = scope.modifier,
            )
            return
        }

        SettingsPage.Integrations -> {
            IntegrationsPage(
                state = maintenanceIntegrations,
                profileScope = scopeProfile,
                onRefresh = scope.maintenance::refreshIntegrations,
                onEventChange = scope.maintenance::editHookEvent,
                onCommandChange = scope.maintenance::editHookCommand,
                onMatcherChange = scope.maintenance::editHookMatcher,
                onAddHook = scope.maintenance::addHook,
                onDeleteHook = scope.maintenance::deleteHook,
                onApprove = scope.maintenance::approvePairing,
                onRevoke = scope.maintenance::revokePairing,
                onDismissStatus = scope.maintenance::dismissIntegrationNotice,
                modifier = scope.modifier,
            )
            return
        }

        SettingsPage.Notifications -> {
            NotificationsPage(
                enabled = scope.notificationsEnabled,
                onToggle = scope.onNotificationsToggle,
                nativeEnabled = scope.nativeNotifications,
                onNativeToggle = scope.onNativeNotificationsToggle,
                modifier = scope.modifier,
            )
            return
        }

        SettingsPage.Shortcuts -> {
            ShortcutsPage(modifier = scope.modifier)
            return
        }

        SettingsPage.Browser -> {
            BrowserPage(
                headed = scope.browserHeaded,
                onHeadedToggle = scope.onBrowserHeadedToggle,
                args = scope.browserArgs,
                onArgsChange = scope.onBrowserArgsChange,
                modifier = scope.modifier,
            )
            return
        }

        SettingsPage.Backend -> {
            // 后端管理页需要真实的 provider / transport：没注入时不画假界面。
            scope.backendAdmin?.let { BackendPage(component = it, modifier = scope.modifier) }
            return
        }

        SettingsPage.Editors -> {
            EditorsPage(
                editors = scope.editors,
                onSave = scope.onEditorsSave,
                modifier = scope.modifier,
            )
            return
        }

        SettingsPage.About -> {
            AboutPage(
                backendVersion = scope.backendVersion,
                dataDir = scope.dataDir,
                logDir = scope.logDir,
                update = scope.updateState,
                onCheckUpdate = scope.onCheckUpdate,
                modifier = scope.modifier,
            )
            return
        }

        else -> Unit
    }

    if (scope.state.selected == SettingsPage.Debug) {
        DebugPage(launcher = scope.officialDesktop, modifier = scope.modifier)
        return
    }
    if (scope.state.selected == SettingsPage.Approvals || scope.state.selected == SettingsPage.Privacy ||
        scope.state.selected == SettingsPage.Checkpoints
    ) {
        val tab = when (scope.state.selected) {
            SettingsPage.Approvals -> SecurityTab.APPROVALS
            SettingsPage.Privacy -> SecurityTab.PRIVACY
            else -> SecurityTab.CHECKPOINTS
        }
        val subject = scope.security
        if (subject == null) {
            Text(
                "安全设置暂不可用（缺少组件）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = scope.modifier.padding(20.dp),
            )
        } else {
            SecurityContent(component = subject, tab = tab, modifier = scope.modifier)
        }
        return
    }
    // 模型三页（主 / 辅助 / 备用）共用一份 Component 状态：它们的"钉一个"动作
    // 都取同一个"当前选择"，拆开就得跨 Store 同步。
    if (scope.state.selected == SettingsPage.MainModel || scope.state.selected == SettingsPage.AuxModels ||
        scope.state.selected == SettingsPage.FallbackModels
    ) {
        val subject = scope.modelSettings
        if (subject == null) {
            Text(
                "模型设置暂不可用（缺少组件）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = scope.modifier.padding(20.dp),
            )
            return
        }
        val ms by subject.state.collectAsState()
        when (scope.state.selected) {
            SettingsPage.MainModel -> MainModelPage(
                state = ms,
                onProvider = { subject.onIntent(ModelSettingsIntent.SelectProvider(it)) },
                onModel = { subject.onIntent(ModelSettingsIntent.SelectModel(it)) },
                onContextChange = { subject.onIntent(ModelSettingsIntent.EditContext(it)) },
                onSaveContext = { subject.onIntent(ModelSettingsIntent.SaveContextLength) },
                onApply = { subject.onIntent(ModelSettingsIntent.ApplyMain) },
                onConfirmPending = { subject.onIntent(ModelSettingsIntent.ConfirmPending) },
                onDismissPending = { subject.onIntent(ModelSettingsIntent.DismissPending) },
                onReloadCatalog = { subject.onIntent(ModelSettingsIntent.ReloadCatalog) },
                modifier = scope.modifier,
            )

            SettingsPage.AuxModels -> AuxModelPage(
                state = ms,
                onAssign = { subject.onIntent(ModelSettingsIntent.AssignAuxTask(it)) },
                onReset = { subject.onIntent(ModelSettingsIntent.ResetAux) },
                onRefresh = { subject.onIntent(ModelSettingsIntent.ReloadAux) },
                modifier = scope.modifier,
            )

            else -> FallbackModelPage(
                state = ms,
                onAdd = { subject.onIntent(ModelSettingsIntent.AddFallback) },
                onRemove = { subject.onIntent(ModelSettingsIntent.RemoveFallback(it)) },
                onSave = { subject.onIntent(ModelSettingsIntent.SaveFallback) },
                onRefresh = { subject.onIntent(ModelSettingsIntent.ReloadFallback) },
                modifier = scope.modifier,
            )
        }
        return
    }
    if (scope.state.selected == SettingsPage.MoaModels) {
        val subject = scope.moa
        if (subject == null) {
            Text(
                "MoA 设置暂不可用（缺少组件）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = scope.modifier.padding(20.dp),
            )
        } else {
            val catalog = scope.modelSettings
        MoaContent(
            component = subject,
            modelState = catalog?.state?.value ?: dev.shinglem.hermes.component.settings.ModelSettingsState(),
            modifier = scope.modifier,
        )
        }
        return
    }
    if (scope.state.selected == SettingsPage.Profiles) {
        val subject = scope.profiles
        if (subject == null) {
            Text(
                "配置档案暂不可用（缺少组件）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = scope.modifier.padding(20.dp),
            )
        } else {
            ProfilesContent(component = subject, modifier = scope.modifier)
        }
        return
    }
    if (scope.state.selected == SettingsPage.ProviderKeys) {
        val subject = scope.providerKeys
        if (subject == null) {
            Text(
                "提供方密钥暂不可用（缺少组件）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = scope.modifier.padding(20.dp),
            )
        } else {
            ProviderKeysContent(component = subject, modifier = scope.modifier)
        }
        return
    }
    if (scope.state.selected == SettingsPage.CustomEndpoints) {
        val subject = scope.customEndpoints
        if (subject == null) {
            Text(
                "自定义端点暂不可用（缺少组件）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = scope.modifier.padding(20.dp),
            )
        } else {
            CustomEndpointContent(component = subject, modifier = scope.modifier)
        }
        return
    }
    if (scope.state.selected == SettingsPage.TerminalAppearance) {
        TerminalSettingsPage(
            settings = scope.terminalSettings,
            onChange = scope.onTerminalSettingsChange,
            modifier = scope.modifier.fillMaxWidth(),
        )
        return
    }

    val group = scope.state.currentGroup ?: return

    Column(modifier = scope.modifier.fillMaxWidth()) {
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
                FieldRow(field = field, value = scope.state.values[field.key].orEmpty(), onEdit = scope.onEdit)
                if (field.key == SettingsCatalog.KEY_AUTOSTART) {
                    autostartHint(scope.autostart)?.let { hint ->
                        Text(
                            hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (scope.autostart.pending) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (scope.orphanTasks.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        OrphanTaskList(
                            tasks = scope.orphanTasks,
                            canDelete = scope.autostart.elevated,
                            onDelete = scope.onDeleteOrphanTask,
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
                scope.state.error?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                scope.state.saved?.let { saved ->
                    Text(
                        if (saved.restartRequired) {
                            "已保存到 custom 覆盖层，重启客户端后生效"
                        } else {
                            "已保存，立即生效"
                        },
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable(onClick = scope.onDismissSaved),
                    )
                }
            }
            if (scope.state.saved?.restartRequired == true) {
                OutlinedButton(onClick = scope.onRestart, enabled = !scope.state.restarting) {
                    Text(if (scope.state.restarting) "正在重启…" else "重启客户端")
                }
                Spacer(Modifier.width(8.dp))
            }
            Button(onClick = scope.onSave, enabled = !scope.state.restarting) { Text("保存") }
        }
    }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}
