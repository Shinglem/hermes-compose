package dev.shinglem.hermes.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import dev.shinglem.hermes.core.app.ProductInfo
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.transport.GatewayEventTypes
import dev.shinglem.hermes.di.AppContainer
import dev.shinglem.hermes.feature.capabilities.CapabilityDetailPane
import dev.shinglem.hermes.feature.capabilities.CapabilityNavPane
import dev.shinglem.hermes.feature.capabilities.CapabilityViewModel
import dev.shinglem.hermes.feature.chat.ChatPane
import dev.shinglem.hermes.feature.chat.ChatViewModel
import dev.shinglem.hermes.feature.chat.PendingApproval
import dev.shinglem.hermes.feature.chat.SessionKey
import dev.shinglem.hermes.feature.editors.EditorLaunchContext
import dev.shinglem.hermes.feature.editors.LocalEditorLaunchContext
import dev.shinglem.hermes.feature.composer.ComposerController
import dev.shinglem.hermes.feature.composer.ComposerViewModel
import dev.shinglem.hermes.feature.composer.ModelPill
import dev.shinglem.hermes.feature.composer.ReasoningPill
import dev.shinglem.hermes.feature.settings.CustomEndpointViewModel
import dev.shinglem.hermes.feature.settings.EndpointPageCallbacks
import dev.shinglem.hermes.feature.settings.ModelPageCallbacks
import dev.shinglem.hermes.feature.settings.MoaPageCallbacks
import dev.shinglem.hermes.feature.settings.MoaViewModel
import dev.shinglem.hermes.feature.settings.ModelSettingsViewModel
import dev.shinglem.hermes.feature.settings.ProfilesViewModel
import dev.shinglem.hermes.feature.settings.SecurityViewModel
import dev.shinglem.hermes.feature.settings.ProviderKeysViewModel
import dev.shinglem.hermes.feature.settings.SettingsDetailPane
import dev.shinglem.hermes.feature.settings.SettingsNavPane
import dev.shinglem.hermes.feature.settings.SettingsPage
import dev.shinglem.hermes.feature.settings.SettingsViewModel
import dev.shinglem.hermes.feature.settings.UpdaterViewModel
import dev.shinglem.hermes.feature.subagent.SubagentPanel
import dev.shinglem.hermes.feature.subagent.SubagentViewModel
import dev.shinglem.hermes.platform.AppFocus
import dev.shinglem.hermes.platform.NotificationAction
import dev.shinglem.hermes.platform.SystemNotifications
import dev.shinglem.hermes.platform.TerminalQuoteBridge
import dev.shinglem.hermes.platform.appDataDir
import dev.shinglem.hermes.feature.session.SessionListCallbacks
import dev.shinglem.hermes.feature.session.SessionListPane
import dev.shinglem.hermes.feature.session.SessionListViewModel
import dev.shinglem.hermes.feature.terminal.TerminalPanel
import dev.shinglem.hermes.feature.terminal.TerminalTabVm
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.capabilities_title
import dev.shinglem.hermes.resources.chat_untitled
import dev.shinglem.hermes.resources.common_back
import dev.shinglem.hermes.resources.rail_settings
import dev.shinglem.hermes.ui.icon.AppIcons
import org.jetbrains.compose.resources.stringResource

/**
 * 根骨架：左 rail + 中栏 + 右栏 + 底部状态条。
 *
 * rail 在 scaffold 之外（它是导航，不是 pane）。窄窗退化留给 P1，
 * 那时接 `ListDetailPaneScaffold` 的自适应 directive（`AGENTS.md` 硬约束 11）。
 */
@Composable
fun AppShell(
    container: AppContainer,
    onExitRequest: () -> Unit = {},
    /**
     * 终端内容插槽：跨平台核心不依赖 Swing/AWT，真正的终端由 desktop 侧注入
     * （`TerminalHost` 来自 `:modules:terminal`）。缺省时面板只显示空壳。
     */
    terminalContent: @Composable (TerminalTabVm, Modifier) -> Unit = { _, _ -> },
    /** 「引用终端选中内容」：读取 Swing 侧的选中文本，由 desktop 侧实现。 */
    onQuoteTerminalSelection: (() -> Unit)? = null,
) {
    val shellViewModel: ShellViewModel = viewModel {
        ShellViewModel(
            gateway = container.gateway,
            profileRepository = container.profileRepository,
            restClient = container.restClient,
            preferences = container.preferences,
            backend = container.backend,
        )
    }
    val sessionViewModel: SessionListViewModel = viewModel {
        SessionListViewModel(container.sessionRepository, container.preferences, container.chatRepository)
    }
    val chatViewModel: ChatViewModel = viewModel {
        ChatViewModel(
            container.chatRepository,
            container.gateway,
            container.modelRepository,
            container.preferences,
        )
    }
    val composerViewModel: ComposerViewModel = viewModel {
        ComposerViewModel(
            repository = container.composerRepository,
            sessions = chatViewModel,
            preferences = container.preferences,
        )
    }
    val capabilityViewModel: CapabilityViewModel = viewModel {
        CapabilityViewModel(container.capabilityRepository)
    }
    val providerKeysViewModel: ProviderKeysViewModel = viewModel {
        ProviderKeysViewModel(container.providerRepository)
    }
    val profilesViewModel: ProfilesViewModel = viewModel {
        ProfilesViewModel(container.profilesAdminRepository)
    }
    val modelSettingsViewModel: ModelSettingsViewModel = viewModel {
        ModelSettingsViewModel(
            container.modelRepository,
            container.modelAdminRepository,
            container.configRepository,
        )
    }
    val securityViewModel: SecurityViewModel = viewModel {
        SecurityViewModel(container.configRepository)
    }
    val customEndpointViewModel: CustomEndpointViewModel = viewModel {
        CustomEndpointViewModel(container.customEndpointRepository)
    }
    val moaViewModel: MoaViewModel = viewModel {
        MoaViewModel(container.modelAdminRepository)
    }
    val settingsViewModel: SettingsViewModel = viewModel {
        SettingsViewModel(
            store = container.settingsStore,
            config = container.config,
            restarter = container.restarter,
            preferences = container.preferences,
            debugAvailable = container.officialDesktop != null,
        )
    }
    val subagentViewModel: SubagentViewModel = viewModel {
        SubagentViewModel(container.gateway) {
            val info = chatViewModel.runtimeInfo()
            info?.let { SubagentViewModel.RuntimeContext(chatViewModel.profile, it.runtimeId) }
        }
    }
    val updaterViewModel: UpdaterViewModel = viewModel {
        UpdaterViewModel(container.restClient)
    }

    val shellState by shellViewModel.state.collectAsStateWithLifecycle()
    var showBackendDetail by remember { mutableStateOf(false) }
    var paletteOpen by remember { mutableStateOf(false) }
    val sessionState by sessionViewModel.state.collectAsStateWithLifecycle()
    val chatState by chatViewModel.state.collectAsStateWithLifecycle()
    val composerState by composerViewModel.state.collectAsStateWithLifecycle()
    val capabilityState by capabilityViewModel.state.collectAsStateWithLifecycle()
    val providerKeysState by providerKeysViewModel.state.collectAsStateWithLifecycle()
    val profilesState by profilesViewModel.state.collectAsStateWithLifecycle()
    val modelSettingsState by modelSettingsViewModel.state.collectAsStateWithLifecycle()
    val securityState by securityViewModel.state.collectAsStateWithLifecycle()
    val endpointState by customEndpointViewModel.state.collectAsStateWithLifecycle()
    val moaState by moaViewModel.state.collectAsStateWithLifecycle()
    val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
    val subagentState by subagentViewModel.state.collectAsStateWithLifecycle()
    val updateState by updaterViewModel.state.collectAsStateWithLifecycle()
    val terminalState by container.terminalViewModel.state.collectAsStateWithLifecycle()
    val terminalSettings by container.terminalViewModel.settings.collectAsStateWithLifecycle()
    val autostartInfo by container.autostart.collectAsStateWithLifecycle()
    val orphanTasks by container.orphanTasks.collectAsStateWithLifecycle()
    val composerScope = rememberCoroutineScope()

    // 设置页点了「重启客户端」：新进程已经起来，这里退出旧进程（走正常退出路径，
    // 偏好落盘、Job 句柄关闭都由 application 作用域收尾）。
    LaunchedEffect(settingsState.exitRequested) {
        if (settingsState.exitRequested) onExitRequest()
    }

    // profile 切换 = 会话上下文作废：同步清掉「选中会话」，否则 UI 会拿旧 profile 的 id
    // 去新 profile 读历史 → 404 `Session not found`（首屏就弹错误条）。
    LaunchedEffect(shellState.activeProfile) {
        sessionViewModel.onProfileChanged(shellState.activeProfile)
        sessionViewModel.loadPinned()
        sessionViewModel.loadArchived()
    }

    LaunchedEffect(shellState.activeProfile, shellState.isConnected) {
        if (shellState.isConnected) {
            sessionViewModel.refresh(shellState.activeProfile)
        }
    }

    // 后端推 `sessions.changed`（新建 / 删除 / 改标题 / 别的客户端操作）→ 去抖刷新列表。
    // 以前这个事件被整条忽略，导致"agent 侧新建的会话 / 另一端的改名"要手动切换 profile 才看得到。
    LaunchedEffect(container) {
        container.gateway.events
            .filter { it.type == GatewayEventTypes.SESSIONS_CHANGED }
            .collect { sessionViewModel.onSessionsChanged() }
    }

    // profile 是全局上下文：切了就作废能力页的数据（跨域约定 §3.1）。
    LaunchedEffect(shellState.activeProfile) {
        capabilityViewModel.setProfile(shellState.activeProfile)
    }

    // 进能力模式才拉数据（别在会话模式白跑三个 REST）；
    // 同时要等连接或后端就绪 —— 首屏就是能力页时会先于本地后端 ready 发请求，那种失败是假的。
    LaunchedEffect(shellState.mode, shellState.isConnected) {
        if (shellState.mode != ShellMode.CAPABILITIES) {
            capabilityViewModel.onHidden()
        } else if (shellState.isConnected) {
            capabilityViewModel.onVisible()
        }
    }

    // 提供方密钥页：profile 变了要重读；只有连上后端才拉（local 后端 ready 之前一定失败）。
    LaunchedEffect(shellState.activeProfile, shellState.isConnected, shellState.mode) {
        providerKeysViewModel.setProfile(shellState.activeProfile)
        if (shellState.mode == ShellMode.SETTINGS && shellState.isConnected) {
            providerKeysViewModel.onVisible()
            profilesViewModel.onVisible()
            modelSettingsViewModel.setProfile(shellState.activeProfile)
            modelSettingsViewModel.onVisible()
            securityViewModel.setProfile(shellState.activeProfile)
            securityViewModel.onVisible()
            customEndpointViewModel.setProfile(shellState.activeProfile)
            customEndpointViewModel.onVisible()
            moaViewModel.setProfile(shellState.activeProfile)
            moaViewModel.onVisible()
        }
    }

    LaunchedEffect(sessionState.selectedId, sessionState.profile, shellState.activeProfile) {
        val selectedId = sessionState.selectedId
        container.terminalViewModel.onSessionChanged(
            selectedId?.takeIf { sessionState.profile == shellState.activeProfile },
        )
        // 选中项属于上一个 profile（刚切换、列表还没刷回来）：这一帧不要去读历史，
        // 那个 id 在新 profile 里不存在，后端会返回 404。等列表刷新后本 effect 会再跑一次。
        if (sessionState.profile != shellState.activeProfile) {
            chatViewModel.open(shellState.activeProfile, null)
            composerViewModel.openSession(null)
            return@LaunchedEffect
        }
        // 刚新建的会话还没有 transcript（history 会 404），直接置空而不是当错误处理。
        if (selectedId != null && selectedId == sessionState.justCreatedId) {
            chatViewModel.adoptEmpty(shellState.activeProfile, selectedId)
        } else {
            chatViewModel.open(shellState.activeProfile, selectedId)
        }
        composerViewModel.openSession(selectedId)
    }

    // 新建会话在第一条 prompt 之前不在 session.list 里，等后端接受后再刷新列表。
    DisposableEffect(chatViewModel, sessionViewModel) {
        chatViewModel.onSessionPersisted = {
            sessionViewModel.refresh(shellState.activeProfile)
        }
        onDispose { chatViewModel.onSessionPersisted = null }
    }

    // 一轮结束 / 审批 / clarify 的系统通知：用户没看着对应会话时才弹，点击直达该会话。
    val reminders = remember { ReminderGate() }
    val notifyScope = rememberCoroutineScope()
    var pendingJump by remember { mutableStateOf<SessionKey?>(null) }

    fun jumpToSession(key: SessionKey) {
        if (key.profile != null && key.profile != shellViewModel.state.value.activeProfile) {
            // 跨 profile：先记下目标，等列表刷新到新 profile 再选中。
            pendingJump = key
            shellViewModel.selectProfile(key.profile)
        } else {
            sessionViewModel.select(key.sessionId)
        }
    }

    fun remind(
        id: String,
        target: SessionKey?,
        title: String,
        body: String,
        actions: List<NotificationAction> = emptyList(),
    ) {
        if (!container.preferences.notificationsEnabled || !SystemNotifications.supported) {
            Log.info("remind skipped id=$id (enabled=${container.preferences.notificationsEnabled} supported=${SystemNotifications.supported})")
            return
        }
        // 正在看这个会话且窗口在前台：不打扰（判据见 NotificationRules）。
        val remind = shouldRemindForSession(
            target = target,
            foreground = AppFocus.isForeground,
            activeProfile = shellViewModel.state.value.activeProfile,
            selectedSessionId = sessionViewModel.state.value.selectedId,
        )
        if (!remind) {
            Log.info("remind skipped id=$id (foreground and viewing this session)")
            return
        }
        if (!reminders.shouldRemind(id)) {
            Log.info("remind skipped id=$id (already reminded)")
            return
        }
        Log.info("remind show id=$id actions=${actions.size} foreground=${AppFocus.isForeground}")
        SystemNotifications.setPendingAction(target?.let { key -> { notifyScope.launch { jumpToSession(key) } } })
        SystemNotifications.show(title, body.take(180), actions)
    }

    /** 审批提醒：带上「允许一次 / 拒绝」按钮，通知里就能就地处理（不必先切窗口）。 */
    fun remindApproval(approval: PendingApproval, target: SessionKey?) {
        remind(
            id = "approval:${approval.requestId}",
            target = target,
            title = "${ProductInfo.DISPLAY_NAME} · 等待确认",
            body = approval.command.trim()
                .ifBlank { approval.description.trim() }
                .ifBlank { "有一个命令等待确认" },
            actions = listOf(
                NotificationAction("允许一次") { chatViewModel.resolveApproval(approval.requestId, "once") },
                NotificationAction("拒绝") { chatViewModel.resolveApproval(approval.requestId, "deny") },
            ),
        )
    }

    DisposableEffect(chatViewModel, container) {
        chatViewModel.onTurnFinished = { failed, text ->
            val enabled = container.preferences.notificationsEnabled
            if (enabled && !AppFocus.isForeground && SystemNotifications.supported) {
                val title = if (failed) {
                    "${ProductInfo.DISPLAY_NAME} · 本轮失败"
                } else {
                    "${ProductInfo.DISPLAY_NAME} · 已完成"
                }
                val body = text.trim().ifBlank { if (failed) "这一轮没有正常结束" else "回答已生成" }
                SystemNotifications.setPendingAction(null)
                SystemNotifications.show(title, body.take(180))
            }
        }
        // 审批超时会按拒绝处理（命令直接失败），所以用户没看着这个会话时必须提醒。
        chatViewModel.onApprovalRequested = { approval, target ->
            remindApproval(approval, target ?: approval.sessionKey)
        }
        chatViewModel.onClarifyRequested = { clarify, target ->
            remind(
                id = "clarify:${clarify.requestId}",
                target = target ?: clarify.sessionKey,
                title = "${ProductInfo.DISPLAY_NAME} · 等待回答",
                body = clarify.questions.firstOrNull()?.question?.trim().orEmpty()
                    .ifBlank { "agent 正在等你回答一个问题" },
            )
        }
        onDispose {
            chatViewModel.onTurnFinished = null
            chatViewModel.onApprovalRequested = null
            chatViewModel.onClarifyRequested = null
        }
    }

    // 用户切走会话 / 切 profile 之后，仍未处理的审批与提问再判断一次：
    // 到达时可能正看着（没弹），切走后就该提醒了。
    LaunchedEffect(
        chatState.pendingApprovals,
        chatState.pendingClarify,
        sessionState.selectedId,
        shellState.activeProfile,
    ) {
        chatState.pendingApprovals.forEach { approval -> remindApproval(approval, approval.sessionKey) }
        chatState.pendingClarify.forEach { clarify ->
            remind(
                id = "clarify:${clarify.requestId}",
                target = clarify.sessionKey,
                title = "${ProductInfo.DISPLAY_NAME} · 等待回答",
                body = clarify.questions.firstOrNull()?.question?.trim().orEmpty()
                    .ifBlank { "agent 正在等你回答一个问题" },
            )
        }
    }

    // 跨 profile 跳转：等目标 profile 的列表就绪后再选中会话。
    LaunchedEffect(pendingJump, sessionState.profile, sessionState.sessions) {
        val target = pendingJump ?: return@LaunchedEffect
        if (sessionState.profile != shellState.activeProfile) return@LaunchedEffect
        if (sessionState.sessions.none { it.id == target.sessionId }) return@LaunchedEffect
        sessionViewModel.select(target.sessionId)
        pendingJump = null
    }

    // 终端「引用选中内容」：desktop 侧读到文本后经小总线送进来，接到 composer 的引用。
    LaunchedEffect(Unit) {
        TerminalQuoteBridge.quotes.collect { text -> composerViewModel.quote(text) }
    }

    // 分支出的新会话：刷新列表并选中它（分支结果是 live 的，列表里马上能看到）。
    DisposableEffect(chatViewModel, sessionViewModel) {
        chatViewModel.onBranched = { branch ->
            sessionViewModel.refresh(shellState.activeProfile)
            sessionViewModel.select(branch.storedSessionId)
        }
        onDispose { chatViewModel.onBranched = null }
    }

    // 运行态点：连上后端后每 5s 刷一次活跃会话集合（失败静默）。
    LaunchedEffect(shellState.isConnected) {
        if (!shellState.isConnected) return@LaunchedEffect
        while (true) {
            sessionViewModel.refreshActive()
            kotlinx.coroutines.delay(5_000)
        }
    }

    val selectedSession = remember(sessionState.selectedId, sessionState.sessions) {
        sessionState.selectedSession()
    }

    val density = LocalDensity.current
    val width = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    // 窄窗时 rail 收成纯图标列（`AGENTS.md` 验收 L6）。
    val railCompact = width < RailCompactBreakpoint

    Scaffold(
        modifier = Modifier
            .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) {
                false
            } else {
                val shortcut = appShortcutOf(
                    ctrl = event.isCtrlPressed,
                    shift = event.isShiftPressed,
                    codePoint = event.utf16CodePoint,
                )
                // 终端相关的快捷键只在会话模式有意义（能力/设置页不渲染终端面板）。
                if (shortcut == null || (shortcut.requiresSessionMode() && shellState.mode != ShellMode.SESSIONS)) {
                    false
                } else {
                    when (shortcut) {
                        AppShortcut.NEW_SESSION -> sessionViewModel.create(shellState.activeProfile)
                        AppShortcut.PREV_SESSION -> sessionViewModel.cycleSelection(-1)
                        AppShortcut.NEXT_SESSION -> sessionViewModel.cycleSelection(1)
                        AppShortcut.OPEN_SETTINGS -> shellViewModel.openSettings()
                        AppShortcut.COMMAND_PALETTE -> paletteOpen = !paletteOpen
                        AppShortcut.TERMINAL_TOGGLE -> container.terminalViewModel.toggle()
                        AppShortcut.TERMINAL_NEW_TAB -> container.terminalViewModel.newTab()
                        AppShortcut.TERMINAL_PREV_TAB -> container.terminalViewModel.cycleTab(-1)
                        AppShortcut.TERMINAL_NEXT_TAB -> container.terminalViewModel.cycleTab(1)
                    }
                    true
                }
            }
        },
        bottomBar = {
            StatusBar(
                state = shellState,
                onRetryConnection = shellViewModel::retryConnection,
                onShowBackendDetail = { showBackendDetail = true },
                showUnelevatedHint = container.elevateRequested && !container.elevated,
            )
        },
    ) { innerPadding ->
        if (showBackendDetail) {
            BackendDetailDialog(
                state = shellState.backend,
                logs = shellState.backendLogs,
                onRetry = shellViewModel::retryBackend,
                onDismiss = { showBackendDetail = false },
            )
        }

        val paletteCommands = remember(
            shellState.activeProfile,
            sessionState.selectedId,
            subagentState.open,
        ) {
            val selected = sessionState.selectedId
            buildList {
                add(
                    CommandItem("new-session", "新建会话", "Ctrl+N", AppIcons.Add) {
                        sessionViewModel.create(shellState.activeProfile)
                    },
                )
                add(
                    CommandItem("open-settings", "打开设置", "Ctrl+,", AppIcons.Tune) {
                        shellViewModel.openSettings()
                    },
                )
                add(
                    CommandItem("open-capabilities", "打开能力页", null, AppIcons.Capabilities) {
                        shellViewModel.openCapabilities()
                    },
                )
                add(
                    CommandItem("toggle-terminal", "终端开合", "Ctrl+`", AppIcons.Bolt) {
                        container.terminalViewModel.toggle()
                    },
                )
                if (selected != null) {
                    add(
                        CommandItem("duplicate-session", "复制为新会话", null, AppIcons.Copy) {
                            sessionViewModel.duplicate(selected)
                        },
                    )
                    add(
                        CommandItem("export-session", "导出当前会话（JSON）", null, AppIcons.Export) {
                            sessionViewModel.exportSession(selected, markdown = false)
                        },
                    )
                    add(
                        CommandItem("export-session-md", "导出当前会话（Markdown）", null, AppIcons.Export) {
                            sessionViewModel.exportSession(selected, markdown = true)
                        },
                    )
                }
                add(
                    CommandItem("subagents", "子代理面板", null, AppIcons.Subagent) {
                        subagentViewModel.open()
                    },
                )
                add(
                    CommandItem("check-update", "检查后端更新", null, AppIcons.Update) {
                        shellViewModel.openSettings()
                        settingsViewModel.select(SettingsPage.About)
                        updaterViewModel.check()
                    },
                )
            }
        }

        CommandPalette(
            visible = paletteOpen,
            sessions = sessionState.sessions,
            commands = paletteCommands,
            onSelectSession = { sessionViewModel.select(it) },
            onDismiss = { paletteOpen = false },
        )

        if (subagentState.open) {
            SubagentPanel(
                state = subagentState,
                onClose = subagentViewModel::close,
                onRefresh = subagentViewModel::refresh,
                onLoadTail = subagentViewModel::loadTail,
                onSteer = subagentViewModel::steer,
                onInterrupt = subagentViewModel::interrupt,
                onDismissNotice = subagentViewModel::dismissNotice,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            ProfileRail(
                state = shellState,
                compact = railCompact,
                onSelectProfile = shellViewModel::selectProfile,
                onOpenCapabilities = shellViewModel::openCapabilities,
                onOpenSettings = shellViewModel::openSettings,
            )

            when (shellState.mode) {
                ShellMode.SETTINGS -> AdaptiveListDetail(
                    modifier = Modifier.weight(1f),
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
                            SettingsDetailPane(
                                state = settingsState,
                                onEdit = settingsViewModel::edit,
                                onSave = settingsViewModel::save,
                                onRestart = settingsViewModel::restart,
                                onDismissSaved = settingsViewModel::dismissSaved,
                                terminalSettings = terminalSettings,
                                onTerminalSettingsChange = container.terminalViewModel::updateSettings,
                                providerState = providerKeysState,
                                onProviderEdit = providerKeysViewModel::edit,
                                onProviderSave = providerKeysViewModel::save,
                                onProviderRefresh = providerKeysViewModel::refresh,
                                onProviderDismissSaved = providerKeysViewModel::dismissSaved,
                                onProviderQueryChange = providerKeysViewModel::setQuery,
                                endpointsState = endpointState,
                                endpointActions = EndpointPageCallbacks(
                                    onCreate = customEndpointViewModel::startCreate,
                                    onEdit = customEndpointViewModel::startEdit,
                                    onDraftChange = customEndpointViewModel::editDraft,
                                    onValidate = customEndpointViewModel::validate,
                                    onSave = customEndpointViewModel::save,
                                    onCancel = customEndpointViewModel::cancelEdit,
                                    onActivate = customEndpointViewModel::activate,
                                    onAskDelete = customEndpointViewModel::askDelete,
                                    onConfirmDelete = customEndpointViewModel::confirmDelete,
                                    onDismissDelete = customEndpointViewModel::dismissDelete,
                                    onRefresh = customEndpointViewModel::refresh,
                                ),
                                profilesState = profilesState,
                                onProfileSelect = profilesViewModel::select,
                                onProfilesRefresh = profilesViewModel::refresh,
                                onProfileSoulChange = profilesViewModel::editSoul,
                                onProfileSaveSoul = profilesViewModel::saveSoul,
                                onProfileStartCreate = profilesViewModel::startCreate,
                                onProfileCancelCreate = profilesViewModel::cancelCreate,
                                onProfileDraftName = profilesViewModel::editDraftName,
                                onProfileDraftClone = profilesViewModel::editDraftClone,
                                onProfileDraftSoul = profilesViewModel::editDraftSoul,
                                onProfileSubmitCreate = profilesViewModel::submitCreate,
                                onProfileDelete = profilesViewModel::delete,
                                onProfileRename = profilesViewModel::rename,
                                onProfileImport = profilesViewModel::import,
                                onProfileExport = profilesViewModel::export,
                                onProfileDismissNotice = profilesViewModel::dismissNotice,
                                modelState = modelSettingsState,
                                moaState = moaState,
                                moaActions = MoaPageCallbacks(
                                    onSelectPreset = moaViewModel::select,
                                    onAddPreset = moaViewModel::addPreset,
                                    onDeletePreset = moaViewModel::deletePreset,
                                    onSetDefault = moaViewModel::setDefault,
                                    onSetActive = moaViewModel::setActive,
                                    onPresetChange = moaViewModel::setPreset,
                                    onAddReference = moaViewModel::addReference,
                                    onRemoveReference = moaViewModel::removeReference,
                                    onReferenceChange = moaViewModel::updateReference,
                                    onSave = moaViewModel::save,
                                    onRefresh = moaViewModel::refresh,
                                ),
                                modelActions = ModelPageCallbacks(
                                    onProvider = modelSettingsViewModel::selectProvider,
                                    onModel = modelSettingsViewModel::selectModel,
                                    onContextChange = modelSettingsViewModel::editContext,
                                    onSaveContext = modelSettingsViewModel::saveContextLength,
                                    onApply = modelSettingsViewModel::applyMain,
                                    onConfirmPending = modelSettingsViewModel::confirmPending,
                                    onDismissPending = modelSettingsViewModel::dismissPending,
                                    onReloadCatalog = modelSettingsViewModel::loadCatalog,
                                    onAssignAux = modelSettingsViewModel::assignAuxTask,
                                    onResetAux = modelSettingsViewModel::resetAux,
                                    onRefreshAux = modelSettingsViewModel::loadAux,
                                    onAddFallback = modelSettingsViewModel::addFallback,
                                    onRemoveFallback = modelSettingsViewModel::removeFallback,
                                    onSaveFallback = modelSettingsViewModel::saveFallback,
                                    onRefreshFallback = modelSettingsViewModel::loadFallback,
                                ),
                                securityState = securityState,
                                onSecurityEdit = securityViewModel::edit,
                                onSecuritySave = securityViewModel::save,
                                onSecurityRefresh = securityViewModel::refresh,
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
                                onCheckUpdate = { updaterViewModel.check() },
                                backendVersion = shellState.health?.version.orEmpty(),
                                dataDir = appDataDir,
                                logDir = container.config.app.log.dir,
                                onDeleteOrphanTask = { name ->
                                    composerScope.launch(Dispatchers.IO) {
                                        if (container.deleteOrphanTask(name)) {
                                            container.orphanTasks.update { list -> list.filterNot { it.name == name } }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    },
                )

                ShellMode.SESSIONS -> AdaptiveListDetail(
                    modifier = Modifier.weight(1f),
                    list = { onNavigateToDetail ->
                        SessionListPane(
                            state = sessionState,
                            callbacks = SessionListCallbacks(
                                onQueryChange = sessionViewModel::setQuery,
                                onSelect = { id ->
                                    sessionViewModel.select(id)
                                    onNavigateToDetail()
                                },
                                onToggleGroup = sessionViewModel::toggleGroup,
                                onTogglePinnedGroup = sessionViewModel::togglePinnedGroup,
                                onRetry = { sessionViewModel.refresh(shellState.activeProfile) },
                                onCreate = { sessionViewModel.create(shellState.activeProfile) },
                                onDismissCreateError = sessionViewModel::dismissCreateError,
                                onDismissRowError = sessionViewModel::dismissRowError,
                                onStartRename = sessionViewModel::startRename,
                                onRenameTitleChange = sessionViewModel::updateRenameDraft,
                                onCommitRename = sessionViewModel::commitRename,
                                onCancelRename = sessionViewModel::cancelRename,
                                onTogglePin = sessionViewModel::togglePin,
                                onAskArchive = sessionViewModel::askArchive,
                                onCommitArchive = sessionViewModel::commitArchive,
                                onCancelArchive = sessionViewModel::cancelArchive,
                                onToggleArchivedSection = sessionViewModel::toggleArchivedSection,
                                onRestoreArchived = sessionViewModel::restoreArchived,
                                onAskDelete = sessionViewModel::askDelete,
                                onCommitDelete = sessionViewModel::commitDelete,
                                onCancelDelete = sessionViewModel::cancelDelete,
                                onDuplicate = sessionViewModel::duplicate,
                                onExportJson = { id -> sessionViewModel.exportSession(id, markdown = false) },
                                onExportMarkdown = { id -> sessionViewModel.exportSession(id, markdown = true) },
                                onContentQueryChange = sessionViewModel::setContentQuery,
                                onDismissNotice = sessionViewModel::dismissNotice,
                            ),
                        )
                    },
                    detail = { singlePane, onBack ->
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (singlePane) {
                                DetailBackBar(
                                    title = selectedSession?.displayTitle
                                        ?: stringResource(Res.string.chat_untitled),
                                    onBack = onBack,
                                )
                            }
                            CompositionLocalProvider(
                                LocalEditorLaunchContext provides EditorLaunchContext(
                                    editors = settingsState.editors,
                                    cwd = chatState.cwd,
                                    userHome = remember { System.getProperty("user.home").orEmpty() },
                                ),
                            ) {
                            ChatPane(
                                summary = selectedSession,
                                state = chatState,
                                composer = remember(composerState) {
                                    ComposerController(
                                        state = composerState,
                                        onValueChange = composerViewModel::onValueChange,
                                        onAcceptCompletion = composerViewModel::acceptCompletion,
                                        onMoveCompletion = composerViewModel::moveSelection,
                                        onDismissCompletion = composerViewModel::dismissCompletion,
                                        onStepHistory = composerViewModel::stepHistory,
                                        onPasteSpecial = { text, selection ->
                                            composerScope.launch {
                                                composerViewModel.handlePasteSpecial(text, selection)
                                            }
                                        },
                                        onPickFiles = {
                                            composerScope.launch { composerViewModel.pickAndAttach() }
                                        },
                                        onDropFiles = { paths ->
                                            composerScope.launch { composerViewModel.attachPaths(paths) }
                                        },
                                    )
                                },
                                onRetry = {
                                    val id = sessionState.selectedId
                                    // 刚新建的会话还没有 transcript，重新加载只会再拿一次 404。
                                    if (id != null && id == sessionState.justCreatedId) {
                                        chatViewModel.adoptEmpty(shellState.activeProfile, id)
                                    } else {
                                        chatViewModel.reload(shellState.activeProfile, id)
                                    }
                                },
                                onReload = {
                                    val id = sessionState.selectedId
                                    if (id != null && id == sessionState.justCreatedId) {
                                        chatViewModel.adoptEmpty(shellState.activeProfile, id)
                                    } else {
                                        chatViewModel.reload(shellState.activeProfile, id)
                                    }
                                },
                                onSend = { text ->
                                    chatViewModel.send(text)
                                    composerViewModel.markSent(text)
                                },
                                onStop = chatViewModel::stop,
                                onApprove = chatViewModel::resolveApproval,
                                onClarifyAnswer = chatViewModel::answerClarify,
                                onClarifyCancel = chatViewModel::cancelClarify,
                                onQuote = composerViewModel::quote,
                                modifier = Modifier.weight(1f),
                                onConfirmModelSwitch = chatViewModel::confirmModelSwitch,
                                onDismissModelConfirm = chatViewModel::dismissModelConfirm,
                                onDismissModelError = chatViewModel::dismissModelError,
                                onLoadOlder = chatViewModel::loadOlder,
                                onRewind = { item ->
                                    // 历史消息的 id 是后端 row id；本地刚发的 `local:` 不能拿去截断。
                                    if (!item.id.startsWith("local:")) {
                                        chatViewModel.beginRewind(item.id, item.text)
                                    }
                                },
                                onBranch = { item ->
                                    if (!item.id.startsWith("local:")) {
                                        val title = selectedSession?.displayTitle.orEmpty()
                                        chatViewModel.beginBranch(item.id, title)
                                    }
                                },
                                onConfirmRewind = chatViewModel::confirmRewind,
                                onCancelRewind = chatViewModel::cancelRewind,
                                onConfirmBranch = chatViewModel::confirmBranch,
                                onCancelBranch = chatViewModel::cancelBranch,
                                onShowUsageDetail = chatViewModel::loadUsageDetail,
                                onDismissUsageError = chatViewModel::dismissUsageError,
                                onOpenSubagents = { subagentViewModel.open() },
                                onKillBackgroundTask = chatViewModel::killBackgroundTask,
                                onSteerQueued = chatViewModel::steerQueuedPrompt,
                                onCancelQueued = chatViewModel::cancelQueuedPrompt,
                                onDismissBackgroundTask = chatViewModel::dismissBackgroundTask,
                                modelBar = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        ModelPill(
                                            catalog = chatState.modelCatalog,
                                            model = chatState.model,
                                            provider = chatState.provider,
                                            loading = chatState.modelCatalogLoading,
                                            busy = chatState.modelSwitching,
                                            enabled = true,
                                            onRequestCatalog = { chatViewModel.loadModelCatalog() },
                                            onSelect = { entry ->
                                                chatViewModel.switchModel(entry.provider, entry.model)
                                            },
                                        )
                                        ReasoningPill(
                                            effort = chatState.reasoningEffort,
                                            supported = chatState.modelCatalog
                                                ?.reasoningSupported(chatState.provider, chatState.model)
                                                ?: true,
                                            busy = chatState.modelSwitching,
                                            enabled = true,
                                            onSelect = chatViewModel::setReasoning,
                                        )
                                    }
                                },
                            )
                            }
                            TerminalPanel(
                                state = terminalState,
                                onToggle = container.terminalViewModel::toggle,
                                onNewTab = { container.terminalViewModel.newTab(null) },
                                onSelectTab = container.terminalViewModel::selectTab,
                                onCloseTab = container.terminalViewModel::closeTab,
                                onHeightFraction = container.terminalViewModel::setHeightFraction,
                                content = terminalContent,
                                onRenameTab = container.terminalViewModel::renameTab,
                                onQuoteSelection = onQuoteTerminalSelection,
                            )
                        }
                    },
                )

                ShellMode.CAPABILITIES -> AdaptiveListDetail(
                    modifier = Modifier.weight(1f),
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
        }
    }
}

/** 单栏退化时，详情窗格顶部的返回条。 */
@Composable
private fun DetailBackBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = AppIcons.ArrowBack,
                contentDescription = stringResource(Res.string.common_back),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** rail 收成纯图标列的宽度分界。 */
private val RailCompactBreakpoint = 960.dp
