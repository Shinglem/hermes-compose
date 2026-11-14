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
import kotlinx.coroutines.launch
import dev.shinglem.hermes.core.app.ProductInfo
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.transport.GatewayEventTypes
import dev.shinglem.hermes.di.AppContainer
import dev.shinglem.hermes.feature.capabilities.CapabilityDetailPane
import dev.shinglem.hermes.feature.capabilities.CapabilityNavPane
import dev.shinglem.hermes.feature.capabilities.CapabilityViewModel
import dev.shinglem.hermes.feature.chat.ChatPane
import dev.shinglem.hermes.feature.chat.PendingApproval
import dev.shinglem.hermes.feature.chat.SessionKey
import dev.shinglem.hermes.feature.editors.EditorLaunchContext
import dev.shinglem.hermes.feature.editors.LocalEditorLaunchContext
import dev.shinglem.hermes.feature.composer.ComposerController
import dev.shinglem.hermes.component.sessionlist.DefaultSessionListComponent
import dev.shinglem.hermes.component.root.DefaultRootComponent
import dev.shinglem.hermes.component.sessionlist.SessionListComponent
import dev.shinglem.hermes.component.capability.CapabilityComponent
import dev.shinglem.hermes.component.capability.DefaultCapabilityComponent
import dev.shinglem.hermes.component.composer.ComposerComponent
import dev.shinglem.hermes.component.composer.DefaultComposerComponent
import dev.shinglem.hermes.feature.composer.ComposerViewModel
import dev.shinglem.hermes.feature.composer.ModelPill
import dev.shinglem.hermes.feature.composer.ReasoningPill
import com.arkivanov.decompose.ComponentContext
import dev.shinglem.hermes.component.rememberDefaultComponentContext
import dev.shinglem.hermes.component.chat.ChatEffect
import dev.shinglem.hermes.component.chat.ChatIntent
import dev.shinglem.hermes.component.chat.DefaultChatComponent
import dev.shinglem.hermes.component.settings.ConfigCustomEndpointGateway
import dev.shinglem.hermes.component.settings.ConfigModelSettingsGateway
import dev.shinglem.hermes.component.settings.MaintenanceIntent
import dev.shinglem.hermes.component.settings.DefaultMaintenanceComponent
import dev.shinglem.hermes.component.settings.ConfigProfilesGateway
import dev.shinglem.hermes.component.settings.DefaultProfilesComponent
import dev.shinglem.hermes.component.settings.DefaultModelSettingsComponent
import dev.shinglem.hermes.component.settings.ConfigMoaGateway
import dev.shinglem.hermes.component.settings.ConfigProviderKeysGateway
import dev.shinglem.hermes.component.settings.ConfigSecurityGateway
import dev.shinglem.hermes.component.settings.DefaultCustomEndpointComponent
import dev.shinglem.hermes.component.settings.DefaultMoaComponent
import dev.shinglem.hermes.component.settings.DefaultProviderKeysComponent
import dev.shinglem.hermes.component.settings.DefaultSecurityComponent
import dev.shinglem.hermes.component.shell.ShellComponent
import dev.shinglem.hermes.component.shell.ShellState as NewShellState
import dev.shinglem.hermes.component.shell.DefaultShellComponent
import dev.shinglem.hermes.component.shell.toLegacy
import dev.shinglem.hermes.component.shell.RealShellGateway
import dev.shinglem.hermes.component.shell.toShellModeForShellComponent
import dev.shinglem.hermes.component.backend.BackendIntent
import dev.shinglem.hermes.component.backend.DefaultBackendComponent
import dev.shinglem.hermes.component.backend.ProviderBackendGateway
import dev.shinglem.hermes.feature.settings.DEFAULT_PROFILE_KEY
import dev.shinglem.hermes.feature.settings.ProfileChoice
import dev.shinglem.hermes.feature.settings.ProfileScope
import dev.shinglem.hermes.feature.settings.ProfileScopeChips
import dev.shinglem.hermes.feature.settings.hasOwnProfileScope
import dev.shinglem.hermes.feature.settings.isProfileScoped
import dev.shinglem.hermes.feature.settings.SettingsDetailPane
import dev.shinglem.hermes.feature.settings.SettingsNavPane
import dev.shinglem.hermes.feature.settings.SettingsPage
import dev.shinglem.hermes.feature.settings.SettingsViewModel
import dev.shinglem.hermes.component.updater.DefaultUpdaterComponent
import dev.shinglem.hermes.component.updater.RestUpdaterGateway
import dev.shinglem.hermes.component.updater.UpdaterIntent
import dev.shinglem.hermes.feature.subagent.SubagentPanel
import dev.shinglem.hermes.component.subagent.DefaultSubagentComponent
import dev.shinglem.hermes.component.subagent.RpcSubagentGateway
import dev.shinglem.hermes.component.subagent.SubagentIntent
import dev.shinglem.hermes.platform.AppFocus
import dev.shinglem.hermes.platform.NotificationAction
import dev.shinglem.hermes.platform.SystemNotifications
import dev.shinglem.hermes.platform.UiDispatcher
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
    /**
     * S2 过渡参数：settings 各域改用 Decompose Component 后需要 `ComponentContext`。
     * S5 落 `RootComponent` 时会由它统一持有，届时这个参数删除（`13 篇` §6.2）。
     *
     * 默认值 `null` 表示"由本函数自己造一个"（见函数体首行）；这样调用方不必关心，
     * 又能保证 `remember` 一定在 composition 里执行（默认参数里的 `@Composable` 调用
     * 在部分调用形态下不被允许）。
     */
    componentContext: ComponentContext? = null,
) {
    val context = componentContext ?: rememberDefaultComponentContext()
    val shellComponent: ShellComponent = remember(context) {
        DefaultShellComponent(
            componentContext = context,
            gateway = RealShellGateway(
                gateway = container.gateway,
                profileRepository = container.profileRepository,
                restClient = container.restClient,
                preferences = container.preferences,
                backend = container.backend,
                // 只有管理员运行才需要那条开关；非提权时传 null，对账完全不做（不动用户配置）。
                browserArgsReconciler = container.browserArgsReconciler.takeIf { container.elevated },
            ),
            initial = NewShellState(
                mode = container.preferences.mode.toShellModeForShellComponent(),
                activeProfile = container.preferences.activeProfile,
                backendKind = container.backend.kind,
                backend = container.backend.state.value,
                backendLogs = container.backend.logs.value,
            ),
        )
    }
    // 会话列表：S5 起由 Component 持有（**过渡形态** —— 内部仍是 SessionListViewModel，
    // 30+ 个动作逐个搬进 Reducer 是纯机械劳动，留到 S8；见 DefaultSessionListComponent 注释）。
    val sessionListComponent: SessionListComponent = remember(context) {
        DefaultSessionListComponent(
            componentContext = context,
            repository = container.sessionRepository,
            preferences = container.preferences,
            chat = container.chatRepository,
        )
    }
    val sessionViewModel: SessionListViewModel = sessionListComponent.model
    // Chat 域：S4 起由 Component 持有（内部仍是原 ChatViewModel 的状态机，
    // 只是 scope 与 5 个回调交给 Component —— 见 DefaultChatComponent 的类注释）。
    val chatComponent = remember(context) {
        DefaultChatComponent(
            componentContext = context,
            repository = container.chatRepository,
            gateway = container.gateway,
            models = container.modelRepository,
            preferences = container.preferences,
            // **必须**是 UI 线程：ChatViewModel 与 GatewayClient 共享大量非线程安全
            // 的可变状态，靠"同一条线程"做互斥（见 DefaultChatComponent 的类注释）。
            executorContext = UiDispatcher.context,
        )
    }
    // Composer：S5 起由 Component 持有（**过渡形态** —— 内部仍是 ComposerViewModel，
    // 只是 scope 从 viewModelScope 换成注入的 UI 线程 scope，见 DefaultComposerComponent 的类注释）。
    val composerComponent: ComposerComponent = remember(context) {
        DefaultComposerComponent(
            componentContext = context,
            repository = container.composerRepository,
            // Composer 的 `@` / `/` 补全要用 chat 的运行时（profile + runtimeId），
            // 走 `ComposerSessionContext` 接口。
            sessions = chatComponent.model,
            preferences = container.preferences,
        )
    }
    val composerViewModel: ComposerViewModel = composerComponent.model
    val backendComponent = remember(context) {
        DefaultBackendComponent(
            componentContext = context,
            gateway = ProviderBackendGateway(container.backend, container.gateway),
        )
    }
    // 能力页：S5 起由 Component 持有（**过渡形态** —— 内部仍是 CapabilityViewModel，
    // 20+ 个动作逐个搬进 Reducer 是纯机械劳动，留到 S8；见 DefaultCapabilityComponent 注释）。
    val capabilityComponent: CapabilityComponent = remember(context) {
        DefaultCapabilityComponent(
            componentContext = context,
            repository = container.capabilityRepository,
        )
    }
    val capabilityViewModel: CapabilityViewModel = capabilityComponent.model
    // 设置页：S5 起 scope 由 componentScope 提供（去掉 viewModelScope 兜底）。
    val componentScope = remember(context) {
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + dev.shinglem.hermes.platform.UiDispatcher.context,
        )
    }
    val settingsViewModel: SettingsViewModel = remember(context) {
        SettingsViewModel(
            store = container.settingsStore,
            config = container.config,
            restarter = container.restarter,
            preferences = container.preferences,
            debugAvailable = container.officialDesktop != null,
            externalScope = componentScope,
        )
    }
    val subagentComponent: dev.shinglem.hermes.component.subagent.SubagentComponent = remember(context) {
        DefaultSubagentComponent(
            componentContext = context,
            gateway = RpcSubagentGateway(container.gateway) {
                chatComponent.runtimeId?.let {
                    dev.shinglem.hermes.component.subagent.RuntimeRef(chatComponent.profile, it)
                }
            },
        )
    }
    val updaterComponent = remember(context) {
        DefaultUpdaterComponent(
            componentContext = context,
            gateway = RestUpdaterGateway(container.restClient),
        )
    }
    // 维护页组：S2 起由 Component 持有（内部仍是原 MaintenanceViewModel 的状态机，
    // 只是 scope 由 Component 管 —— 见 DefaultMaintenanceComponent 的类注释）。
    //
    // maintenanceState 供 6 个页面直接读；动作仍走 ViewModel 的方法引用
    // （60 多个动作逐条包成 Intent 只会增加噪音，见 MaintenanceComponent 的类注释）。
    val maintenanceComponent = remember(context) {
        DefaultMaintenanceComponent(
            componentContext = context,
            repository = container.maintenanceRepository,
            configRepository = container.configRepository,
            hermesHome = container.hermesHome,
        )
    }
    val maintenance = maintenanceComponent.model

    // 旧 ShellState 的消费方（6 个 settings Component 与 200+ 处 UI）仍按旧类型工作；
    // 这里用一个轻量投影把新 state 映射回旧形状，避免这一轮改上千行 UI（S8 统一改）。
    val newShellState: NewShellState by shellComponent.state.collectAsStateWithLifecycle()
    // RootComponent 接管导航（S5）：mode 的事实源仍是 ShellComponent，这里只做投影。
    val rootComponent = remember(context) {
        DefaultRootComponent(componentContext = context, initialProfile = null)
    }
    LaunchedEffect(newShellState.mode) { rootComponent.syncFromMode(newShellState.mode.name) }
    val shellState: ShellState = remember(newShellState) { newShellState.toLegacy() }
    // `remember` 而不是 `viewModel` —— Component 有自己的生命周期，不是 androidx ViewModel。
    // profile 变化时按 key 重建：与旧 `setProfile` 的"彻底重置"语义一致，但不可能忘。
    val securityComponent = remember(context, shellState.activeProfile) {
        DefaultSecurityComponent(
            componentContext = context,
            repository = ConfigSecurityGateway(container.configRepository),
            profile = shellState.activeProfile,
        )
    }
    val providerKeysComponent = remember(context, shellState.activeProfile) {
        DefaultProviderKeysComponent(
            componentContext = context,
            gateway = ConfigProviderKeysGateway(container.providerRepository),
            profile = shellState.activeProfile,
        )
    }
    val customEndpointComponent = remember(context, shellState.activeProfile) {
        DefaultCustomEndpointComponent(
            componentContext = context,
            gateway = ConfigCustomEndpointGateway(container.customEndpointRepository),
            profile = shellState.activeProfile,
        )
    }
    val moaComponent = remember(context, shellState.activeProfile) {
        DefaultMoaComponent(
            componentContext = context,
            gateway = ConfigMoaGateway(container.modelAdminRepository),
            profile = shellState.activeProfile,
        )
    }
    val modelSettingsComponent = remember(context, shellState.activeProfile) {
        DefaultModelSettingsComponent(
            componentContext = context,
            gateway = ConfigModelSettingsGateway(
                models = container.modelRepository,
                admin = container.modelAdminRepository,
                config = container.configRepository,
            ),
            profile = shellState.activeProfile,
        )
    }
    // 配置档案页管理的是"所有档案"，不随 activeProfile 重建。
    val profilesComponent = remember(context) {
        DefaultProfilesComponent(
            componentContext = context,
            gateway = ConfigProfilesGateway(container.profilesAdminRepository),
        )
    }
    var showBackendDetail by remember { mutableStateOf(false) }
    // 后端起好再读：与迁移前 `LaunchedEffect(profile, isConnected, mode)` 的把关一致
    // （local 后端 ready 之前请求一定失败）。
    LaunchedEffect(securityComponent, shellState.isConnected, shellState.mode) {
        if (shellState.mode == ShellMode.SETTINGS && shellState.isConnected) {
            securityComponent.onConnectedChanged(true)
        }
    }
    LaunchedEffect(providerKeysComponent, shellState.isConnected, shellState.mode) {
        if (shellState.mode == ShellMode.SETTINGS && shellState.isConnected) {
            providerKeysComponent.onConnectedChanged(true)
        }
    }
    LaunchedEffect(customEndpointComponent, shellState.isConnected, shellState.mode) {
        if (shellState.mode == ShellMode.SETTINGS && shellState.isConnected) {
            customEndpointComponent.onConnectedChanged(true)
        }
    }
    LaunchedEffect(profilesComponent, shellState.isConnected, shellState.mode) {
        if (shellState.mode == ShellMode.SETTINGS && shellState.isConnected) {
            profilesComponent.onConnectedChanged(true)
        }
    }
    LaunchedEffect(modelSettingsComponent, shellState.isConnected, shellState.mode) {
        if (shellState.mode == ShellMode.SETTINGS && shellState.isConnected) {
            modelSettingsComponent.onConnectedChanged(true)
        }
    }
    LaunchedEffect(moaComponent, shellState.isConnected, shellState.mode) {
        if (shellState.mode == ShellMode.SETTINGS && shellState.isConnected) {
            moaComponent.onConnectedChanged(true)
        }
    }
    var paletteOpen by remember { mutableStateOf(false) }
    val sessionState by sessionViewModel.state.collectAsStateWithLifecycle()
    val chatState by chatComponent.state.collectAsStateWithLifecycle()
    val composerState by composerViewModel.state.collectAsStateWithLifecycle()
    val capabilityState by capabilityViewModel.state.collectAsStateWithLifecycle()
    val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
    val subagentState by subagentComponent.state.collectAsStateWithLifecycle()
    // UI 层仍按旧形状消费（SubagentEntry → SubagentItem 只是改名）。
    val updateState by updaterComponent.state.collectAsStateWithLifecycle()
    val terminalState by container.terminalViewModel.state.collectAsStateWithLifecycle()
    val terminalSettings by container.terminalViewModel.settings.collectAsStateWithLifecycle()
    val maintenanceScopeKey by maintenance.profileKey.collectAsStateWithLifecycle()

    val maintenanceProfileChoices = remember(shellState.profilesOrEmpty) {
        val defaultKey = NewShellState.DEFAULT_PROFILE
        val defaultLabel = shellState.profilesOrEmpty
            .firstOrNull { it.isDefault || it.name == defaultKey }
            ?.label
            ?: defaultKey
        buildList {
            add(ProfileChoice(defaultKey, defaultLabel))
            shellState.profilesOrEmpty
                .filterNot { it.isDefault || it.name == defaultKey }
                .forEach { add(ProfileChoice(it.name, it.label)) }
        }
    }
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
        if (shellState.mode == ShellMode.SETTINGS && shellState.isConnected) {
            maintenance.setProfile(shellState.activeProfile)
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
            chatComponent.onIntent(ChatIntent.Open(shellState.activeProfile, null))
            composerViewModel.openSession(null)
            return@LaunchedEffect
        }
        // 刚新建的会话还没有 transcript（history 会 404），直接置空而不是当错误处理。
        if (selectedId != null && selectedId == sessionState.justCreatedId) {
            chatComponent.onIntent(ChatIntent.AdoptEmpty(shellState.activeProfile, selectedId))
        } else {
            chatComponent.onIntent(ChatIntent.Open(shellState.activeProfile, selectedId))
        }
        composerViewModel.openSession(selectedId)
    }


    // 一轮结束 / 审批 / clarify 的系统通知：用户没看着对应会话时才弹，点击直达该会话。
    val reminders = remember { ReminderGate() }
    val notifyScope = rememberCoroutineScope()
    var pendingJump by remember { mutableStateOf<SessionKey?>(null) }

    fun jumpToSession(key: SessionKey) {
        if (key.profile != null && key.profile != shellComponent.state.value.activeProfile) {
            // 跨 profile：先记下目标，等列表刷新到新 profile 再选中。
            pendingJump = key
            shellComponent.onIntent(dev.shinglem.hermes.component.shell.ShellIntent.SelectProfile(key.profile))
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
        foreground: Boolean = AppFocus.isForeground,
        /** 这张卡片是不是已经渲染在用户眼前的会话里（判据见 NotificationRules）。 */
        onScreen: Boolean = false,
    ) {
        if (!container.preferences.notificationsEnabled || !SystemNotifications.supported) {
            Log.info("remind skipped id=$id (enabled=${container.preferences.notificationsEnabled} supported=${SystemNotifications.supported})")
            return
        }
        val selected = sessionViewModel.state.value.selectedId
        val remind = shouldRemindForSession(
            target = target,
            foreground = foreground,
            selectedSessionId = selected,
            onScreen = onScreen,
        )
        if (!remind) {
            Log.info("remind skipped id=$id (foreground=$foreground onScreen=$onScreen target=${target?.sessionId} selected=$selected)")
            return
        }
        if (!reminders.shouldRemind(id)) {
            Log.info("remind skipped id=$id (already reminded)")
            return
        }
        Log.info("remind show id=$id actions=${actions.size} foreground=$foreground onScreen=$onScreen target=${target?.sessionId} selected=$selected")
        SystemNotifications.setPendingAction(target?.let { key -> { notifyScope.launch { jumpToSession(key) } } })
        SystemNotifications.show(title, body.take(180), actions)
    }

    /** 审批提醒：带上「允许一次 / 拒绝」按钮，通知里就能就地处理（不必先切窗口）。 */
    fun remindApproval(
        approval: PendingApproval,
        target: SessionKey?,
        foreground: Boolean = AppFocus.isForeground,
        /**
         * 卡片已经在界面上（当前会话的审批）——此时再弹系统通知就是纯噪音。
         *
         * **读实时值**（`chatComponent.state.value`）而不是组合期的 `chatState`：
         * 后者是 `by` 委托，在 `LaunchedEffect` 里会被冻结成首帧快照，判定恒为 false，
         * 这条兜底就失效了（迁移前这里读的是 `viewModel.state.value`）。
         */
        onScreen: Boolean = chatComponent.state.value.visibleApprovals.any {
            it.requestId == approval.requestId
        },
    ) {
        remind(
            id = "approval:${approval.requestId}",
            target = target,
            title = "${ProductInfo.DISPLAY_NAME} · 等待确认",
            body = approval.command.trim()
                .ifBlank { approval.description.trim() }
                .ifBlank { "有一个命令等待确认" },
            actions = listOf(
                NotificationAction("允许一次") { chatComponent.onIntent(ChatIntent.ResolveApproval(approval.requestId, "once")) },
                NotificationAction("拒绝") { chatComponent.onIntent(ChatIntent.ResolveApproval(approval.requestId, "deny")) },
            ),
            foreground = foreground,
            onScreen = onScreen,
        )
    }

    // Chat 域的一次性事件统一在这里消费（S4：5 个 `var onXxx` → 一个 effects 流）。
    //
    // 为什么合并成一个收集器：`effects` 是单条 `Flow`，多个 `LaunchedEffect` 各自
    // `collect` 会**抢事件**（每个消费者只拿到一部分）。迁移前的 5 个 `var` 之所以
    // 没出问题，是因为它们本来就是 5 条独立的回调槽。
    LaunchedEffect(chatComponent, sessionViewModel) {
        chatComponent.effects.collect { effect ->
            when (effect) {
                // 新建会话在第一条 prompt 之前不在 session.list 里，等后端接受后再刷新。
                ChatEffect.SessionPersisted -> sessionViewModel.refresh(shellState.activeProfile)

                is ChatEffect.TurnFinished -> {
                    val enabled = container.preferences.notificationsEnabled
                    if (enabled && !AppFocus.isForeground && SystemNotifications.supported) {
                        val title = if (effect.failed) {
                            "${ProductInfo.DISPLAY_NAME} · 本轮失败"
                        } else {
                            "${ProductInfo.DISPLAY_NAME} · 已完成"
                        }
                        val body = effect.text.trim()
                            .ifBlank { if (effect.failed) "这一轮没有正常结束" else "回答已生成" }
                        SystemNotifications.setPendingAction(null)
                        SystemNotifications.show(title, body.take(180))
                    }
                }

                // 审批超时会按拒绝处理（命令直接失败），所以用户没看着这个会话时必须提醒。
                is ChatEffect.ApprovalRequested -> remindApproval(
                    effect.approval,
                    effect.key ?: effect.approval.sessionKey,
                )

                is ChatEffect.ClarifyRequested -> remind(
                    id = "clarify:${effect.clarify.requestId}",
                    target = effect.key ?: effect.clarify.sessionKey,
                    title = "${ProductInfo.DISPLAY_NAME} · 等待回答",
                    body = effect.clarify.questions.firstOrNull()?.question?.trim().orEmpty()
                        .ifBlank { "agent 正在等你回答一个问题" },
                    // **必须读实时值**：`chatState` 是组合期的 `by` 委托，在这个
                    // `LaunchedEffect` 里会被冻结成首帧的 ChatState（key 不变就不重组）。
                    // 迁移前这里读的是 `viewModel.state.value`，是调用时的实时值。
                    onScreen = chatComponent.state.value.visibleClarify.any {
                        it.requestId == effect.clarify.requestId
                    },
                )

                // 分支出的新会话：刷新列表并选中它（分支结果是 live 的，列表里马上能看到）。
                is ChatEffect.Branched -> {
                    sessionViewModel.refresh(shellState.activeProfile)
                    sessionViewModel.select(effect.from.storedSessionId)
                }
            }
        }
    }


    // 用户切走会话 / 切 profile / **把窗口切到后台**之后，仍未处理的审批与提问再判断一次：
    // 到达时可能正看着（没弹），切走后就该提醒了。被跳过的那条没有进 ReminderGate，
    // 所以这里重新评估就能补上 —— 少了焦点这一项，切后台前到的那几条永远不会补提醒。
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    // 把主窗口焦点推给平台层：非 Composable 的回调（onApprovalRequested / onTurnFinished）
    // 也要用同一个判据，否则两边结论可能不一致 —— 那正是"提醒被吞"的来源。
    LaunchedEffect(windowFocused) { AppFocus.update(windowFocused) }
    LaunchedEffect(
        chatState.pendingApprovals,
        chatState.pendingClarify,
        sessionState.selectedId,
        shellState.activeProfile,
        windowFocused,
    ) {
        chatState.pendingApprovals.forEach { approval ->
            remindApproval(approval, approval.sessionKey, windowFocused)
        }
        chatState.pendingClarify.forEach { clarify ->
            remind(
                id = "clarify:${clarify.requestId}",
                target = clarify.sessionKey,
                title = "${ProductInfo.DISPLAY_NAME} · 等待回答",
                body = clarify.questions.firstOrNull()?.question?.trim().orEmpty()
                    .ifBlank { "agent 正在等你回答一个问题" },
                foreground = windowFocused,
                onScreen = chatState.visibleClarify.any { it.requestId == clarify.requestId },
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
                        AppShortcut.OPEN_SETTINGS -> shellComponent.onIntent(dev.shinglem.hermes.component.shell.ShellIntent.OpenSettings)
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
                onRetryConnection = { shellComponent.onIntent(dev.shinglem.hermes.component.shell.ShellIntent.RetryConnection) },
                onShowBackendDetail = { showBackendDetail = true },
                showUnelevatedHint = container.elevateRequested && !container.elevated,
            )
        },
    ) { innerPadding ->
        if (showBackendDetail) {
            BackendDetailDialog(
                state = shellState.backend,
                logs = shellState.backendLogs,
                onRetry = { shellComponent.onIntent(dev.shinglem.hermes.component.shell.ShellIntent.RetryBackend) },
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
                        shellComponent.onIntent(dev.shinglem.hermes.component.shell.ShellIntent.OpenSettings)
                    },
                )
                add(
                    CommandItem("open-capabilities", "打开能力页", null, AppIcons.Capabilities) {
                        shellComponent.onIntent(dev.shinglem.hermes.component.shell.ShellIntent.OpenCapabilities)
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
                        subagentComponent.onIntent(SubagentIntent.Open)
                    },
                )
                add(
                    CommandItem("check-update", "检查后端更新", null, AppIcons.Update) {
                        shellComponent.onIntent(dev.shinglem.hermes.component.shell.ShellIntent.OpenSettings)
                        settingsViewModel.select(SettingsPage.About)
                        updaterComponent.onIntent(UpdaterIntent.Check())
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
                onClose = { subagentComponent.onIntent(SubagentIntent.Close) },
                onRefresh = { subagentComponent.onIntent(SubagentIntent.Refresh) },
                onLoadTail = { id -> subagentComponent.onIntent(SubagentIntent.LoadTail(id)) },
                onSteer = { id, text -> subagentComponent.onIntent(SubagentIntent.Steer(id, text)) },
                onInterrupt = { id -> subagentComponent.onIntent(SubagentIntent.Interrupt(id)) },
                onDismissNotice = { subagentComponent.onIntent(SubagentIntent.DismissNotice) },
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
                onSelectProfile = { name -> shellComponent.onIntent(dev.shinglem.hermes.component.shell.ShellIntent.SelectProfile(name)) },
                onOpenCapabilities = { shellComponent.onIntent(dev.shinglem.hermes.component.shell.ShellIntent.OpenCapabilities) },
                onOpenSettings = { shellComponent.onIntent(dev.shinglem.hermes.component.shell.ShellIntent.OpenSettings) },
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
                                        chatComponent.onIntent(ChatIntent.AdoptEmpty(shellState.activeProfile, id))
                                    } else {
                                        chatComponent.onIntent(ChatIntent.Reload(shellState.activeProfile, id))
                                    }
                                },
                                onReload = {
                                    val id = sessionState.selectedId
                                    if (id != null && id == sessionState.justCreatedId) {
                                        chatComponent.onIntent(ChatIntent.AdoptEmpty(shellState.activeProfile, id))
                                    } else {
                                        chatComponent.onIntent(ChatIntent.Reload(shellState.activeProfile, id))
                                    }
                                },
                                onSend = { text ->
                                    // 附件必须随这一轮提交：`image.attach_bytes` 在 gateway 路线下
                                    // 只把字节落进 <profile>/cache/images，不建队列，
                                    // 不回传就永远到不了模型（待修复问题 #19）。
                                    val attachments = composerViewModel.state.value.attachments
                                        .mapNotNull { it.toPromptAttachment() }
                                    chatComponent.onIntent(ChatIntent.Send(text, attachments))
                                    composerViewModel.markSent(text)
                                },
                                onStop = { chatComponent.onIntent(ChatIntent.Stop) },
                                onApprove = { id, choice -> chatComponent.onIntent(ChatIntent.ResolveApproval(id, choice)) },
                                onClarifyAnswer = { id, answers -> chatComponent.onIntent(ChatIntent.AnswerClarify(id, answers)) },
                                onClarifyCancel = { id -> chatComponent.onIntent(ChatIntent.CancelClarify(id)) },
                                onQuote = composerViewModel::quote,
                                modifier = Modifier.weight(1f),
                                onConfirmModelSwitch = { chatComponent.onIntent(ChatIntent.ConfirmModelSwitch) },
                                onDismissModelConfirm = { chatComponent.onIntent(ChatIntent.DismissModelConfirm) },
                                onDismissModelError = { chatComponent.onIntent(ChatIntent.DismissModelError) },
                                onLoadOlder = { chatComponent.onIntent(ChatIntent.LoadOlder) },
                                onRewind = { item ->
                                    // 历史消息的 id 是后端 row id；本地刚发的 `local:` 不能拿去截断。
                                    if (!item.id.startsWith("local:")) {
                                        chatComponent.onIntent(ChatIntent.BeginRewind(item.id, item.text))
                                    }
                                },
                                onBranch = { item ->
                                    if (!item.id.startsWith("local:")) {
                                        val title = selectedSession?.displayTitle.orEmpty()
                                        chatComponent.onIntent(ChatIntent.BeginBranch(item.id, title))
                                    }
                                },
                                onConfirmRewind = { chatComponent.onIntent(ChatIntent.ConfirmRewind) },
                                onCancelRewind = { chatComponent.onIntent(ChatIntent.CancelRewind) },
                                onConfirmBranch = { chatComponent.onIntent(ChatIntent.ConfirmBranch) },
                                onCancelBranch = { chatComponent.onIntent(ChatIntent.CancelBranch) },
                                onShowUsageDetail = { chatComponent.onIntent(ChatIntent.LoadUsageDetail) },
                                onDismissUsageError = { chatComponent.onIntent(ChatIntent.DismissUsageError) },
                                onOpenSubagents = { subagentComponent.onIntent(SubagentIntent.Open) },
                                onKillBackgroundTask = { id -> chatComponent.onIntent(ChatIntent.KillBackgroundTask(id)) },
                                onSteerQueued = { id -> chatComponent.onIntent(ChatIntent.SteerQueued(id)) },
                                onCancelQueued = { id -> chatComponent.onIntent(ChatIntent.CancelQueued(id)) },
                                onReconnect = { chatComponent.onIntent(ChatIntent.Reconnect) },
                                onDismissBackgroundTask = { id -> chatComponent.onIntent(ChatIntent.DismissBackgroundTask(id)) },
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
                                            onRequestCatalog = { chatComponent.onIntent(ChatIntent.LoadModelCatalog) },
                                            onSelect = { entry ->
                                                chatComponent.onIntent(ChatIntent.SwitchModel(entry.provider, entry.model))
                                            },
                                        )
                                        ReasoningPill(
                                            effort = chatState.reasoningEffort,
                                            supported = chatState.modelCatalog
                                                ?.reasoningSupported(chatState.provider, chatState.model)
                                                ?: true,
                                            busy = chatState.modelSwitching,
                                            enabled = true,
                                            onSelect = { effort -> chatComponent.onIntent(ChatIntent.SetReasoning(effort)) },
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
