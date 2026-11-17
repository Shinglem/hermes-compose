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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import dev.shinglem.hermes.component.browser.BrowserEffect
import dev.shinglem.hermes.component.browser.BrowserIntent
import dev.shinglem.hermes.component.browser.Phase
import dev.shinglem.hermes.component.browser.store.isBrowserTool
import dev.shinglem.hermes.feature.browser.BrowserSidePanel
import dev.shinglem.hermes.feature.browser.BrowserSlot
import dev.shinglem.hermes.platform.openExternalUrl
import dev.shinglem.hermes.component.capability.DefaultCapabilityComponent
import dev.shinglem.hermes.component.composer.ComposerComponent
import dev.shinglem.hermes.component.composer.DefaultComposerComponent
import dev.shinglem.hermes.feature.composer.ComposerViewModel
import dev.shinglem.hermes.feature.composer.ModelPill
import dev.shinglem.hermes.feature.composer.ReasoningPill
import com.arkivanov.decompose.ComponentContext

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

import dev.shinglem.hermes.component.shell.RealShellGateway
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
import kotlinx.coroutines.delay
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
    val deps = rememberAppShellDeps(container, context)
    val shellComponent = deps.shellComponent
    val shellState = deps.shellState
    val sessionListComponent = deps.sessionListComponent
    val sessionViewModel = deps.sessionViewModel
    val sessionState = deps.sessionState
    val chatComponent = deps.chatComponent
    val chatState = deps.chatState
    val composerComponent = deps.composerComponent
    val composerViewModel = deps.composerViewModel
    val composerState = deps.composerState
    val backendComponent = deps.backendComponent
    val capabilityComponent = deps.capabilityComponent
    val capabilityViewModel = deps.capabilityViewModel
    val capabilityState = deps.capabilityState
    val componentScope = deps.componentScope
    val settingsViewModel = deps.settingsViewModel
    val settingsState = deps.settingsState
    val subagentComponent = deps.subagentComponent
    val subagentState = deps.subagentState
    val updaterComponent = deps.updaterComponent
    val updateState = deps.updateState
    val maintenanceComponent = deps.maintenanceComponent
    val maintenance = deps.maintenance
    val maintenanceScopeKey = deps.maintenanceScopeKey
    val maintenanceProfileChoices = deps.maintenanceProfileChoices
    val securityComponent = deps.securityComponent
    val providerKeysComponent = deps.providerKeysComponent
    val customEndpointComponent = deps.customEndpointComponent
    val moaComponent = deps.moaComponent
    val modelSettingsComponent = deps.modelSettingsComponent
    val profilesComponent = deps.profilesComponent
    val browserState = deps.browserState
    val browserComponent = deps.browserComponent

    // RootComponent 接管导航（S5）：mode 的事实源仍是 ShellComponent，这里只做投影。
    LaunchedEffect(deps.mode) { deps.rootComponent.syncFromMode(deps.mode) }

    var showBackendDetail by remember { mutableStateOf(false) }
    // 后端起好再读：与迁移前 `LaunchedEffect(profile, isConnected, mode)` 的把关一致
    // （local 后端 ready 之前请求一定失败）。六个设置域的处理完全一样，合并成一个 effect。
    LaunchedEffect(shellState.isConnected, shellState.mode) {
        if (shellState.mode == ShellMode.SETTINGS && shellState.isConnected) {
            securityComponent.onConnectedChanged(true)
            providerKeysComponent.onConnectedChanged(true)
            customEndpointComponent.onConnectedChanged(true)
            profilesComponent.onConnectedChanged(true)
            modelSettingsComponent.onConnectedChanged(true)
            moaComponent.onConnectedChanged(true)
        }
    }
    var paletteOpen by remember { mutableStateOf(false) }
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

    // agent 开始操作浏览器 → **纳管**那个窗口（收进本进程），但**不打开抽屉**。
    // 用户要求：抽屉默认关闭，只有当用户点侧栏时才展示画面。
    // 也**不要**改回"启动时就纳管"：那时浏览器通常还不存在，而且不该抢别的程序留下的浏览器。
    LaunchedEffect(container) {
        container.gateway.events
            .filter { it.type == GatewayEventTypes.TOOL_START }
            .collect { event ->
                val name = event.payload
                    ?.let { it as? JsonObject }
                    ?.get("name")
                    ?.let { (it as? JsonPrimitive)?.content }
                    .orEmpty()
                if (isBrowserTool(name)) {
                    Log.info("browser: agent 开始操作浏览器（$name），纳管窗口")
                    browserComponent.onIntent(BrowserIntent.TakeOver)
                }
            }
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

    // `remind` / `remindApproval` 已搬到 ShellNotifier（判据可测）；这里只保留"实时值"读取。
    val notifier = ShellNotifier(
        // 传 lambda 而不是取值：`LaunchedEffect` 不因偏好变化重启，捕获成字段会让开关失效。
        enabled = { container.preferences.notificationsEnabled },
        supported = SystemNotifications.supported,
        gate = reminders,
        selectedSessionId = { sessionViewModel.state.value.selectedId },
        onJump = { key -> notifyScope.launch { jumpToSession(key) } },
        onResolveApproval = { requestId, choice ->
            chatComponent.onIntent(ChatIntent.ResolveApproval(requestId, choice))
        },
    )
    fun remind(
        id: String,
        target: SessionKey?,
        title: String,
        body: String,
        actions: List<NotificationAction> = emptyList(),
        foreground: Boolean = AppFocus.isForeground,
        onScreen: Boolean = false,
    ) = notifier.remind(id, target, title, body, actions, foreground, onScreen)
    fun remindApproval(
        approval: PendingApproval,
        target: SessionKey?,
        foreground: Boolean = AppFocus.isForeground,
        onScreen: Boolean = chatComponent.state.value.visibleApprovals.any {
            it.requestId == approval.requestId
        },
    ) = notifier.remindApproval(approval, target, foreground, onScreen)

    // Chat 域的一次性事件统一在这里消费（S4：5 个 `var onXxx` → 一个 effects 流）。
    //
    // 为什么合并成一个收集器：`effects` 是单条 `Flow`，多个 `LaunchedEffect` 各自
    // `collect` 会**抢事件**（每个消费者只拿到一部分）。迁移前的 5 个 `var` 之所以
    // 没出问题，是因为它们本来就是 5 条独立的回调槽。
    //
    // **key 必须带 `shellState.activeProfile`**：`shellState` 是组合期读出的不可变快照
    // （不是 `by collectAsStateWithLifecycle()` 委托），而本 effect 一旦启动就不会重启。
    // 不加 key 的话 `SessionPersisted` / `Branched` 里的 `refresh(shellState.activeProfile)`
    // 永远用**旧 profile** 刷新 —— `SessionListState.profile` 会被改回旧值、列表显示另一个
    // 档案的会话、当前对话被下面那个 effect 踢掉（迁移前读的是实时值，没这问题）。
    LaunchedEffect(chatComponent, sessionViewModel, shellState.activeProfile) {
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

    // 浏览器侧栏的一次性事件：目前只有「在系统浏览器打开」。
    LaunchedEffect(deps.browserComponent) {
        deps.browserComponent.effects.collect { effect ->
            when (effect) {
                is BrowserEffect.OpenExternal -> openExternalUrl(effect.url)
            }
        }
    }

    // 浏览器槽位的心跳：每 5s 跑一次完整发现。
    //
    // **不要**在这里加 `if (!takenOver) return` —— 那样"还没纳管"的时候就没有任何兜底了。
    // 实测踩过：TakeOver 的重试窗口（5s）用完仍没找到（后端 daemon 故障时 Chrome 起来的
    // 但窗口异常），之后心跳也不跑 → 用户看到任务栏有浏览器图标，抽屉里却一直找不到，
    // 只能手动点「重新连接」。
    //
    // 现在心跳无条件下跑：discoverOnce 内部按 pid 去重（已纳管的不重复挂），
    // 同时把已关闭的摘掉 —— 一趟扫描同时覆盖"发现新的"与"清掉死的"。
    LaunchedEffect(deps.browserComponent) {
        while (true) {
            delay(BROWSER_HEARTBEAT_MS)
            deps.browserComponent.onIntent(BrowserIntent.Heartbeat)
        }
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
                ShellMode.SETTINGS -> SettingsModeContent(
                    deps = deps,
                    container = container,
                    terminalSettings = terminalSettings,
                    autostartInfo = autostartInfo,
                    orphanTasks = orphanTasks,
                    composerScope = composerScope,
                    modifier = Modifier.weight(1f),
                )
                ShellMode.SESSIONS -> SessionsModeContent(
                    deps = deps,
                    container = container,
                    selectedSession = selectedSession,
                    terminalState = terminalState,
                    terminalContent = terminalContent,
                    onQuoteTerminalSelection = onQuoteTerminalSelection,
                    composerScope = composerScope,
                    modifier = Modifier.weight(1f),
                )

                ShellMode.CAPABILITIES -> CapabilitiesModeContent(deps = deps, modifier = Modifier.weight(1f))
            }

            // 浏览器抽屉（v0.6.1，ADR-082）：挂在内容区右侧、与 rail 平级。
            // 窄窗时自动收起（`railCompact` 同一个断点）——不给一个挤死对话区的抽屉。
            if (!railCompact) {
                BrowserSidePanel(
                    state = browserState,
                    onTogglePanel = { browserComponent.onIntent(BrowserIntent.TogglePanel) },
                    onRefresh = { browserComponent.onIntent(BrowserIntent.Refresh) },
                    onOpenInSystemBrowser = { browserComponent.onIntent(BrowserIntent.OpenInSystemBrowser) },
                    onDismissError = { browserComponent.onIntent(BrowserIntent.DismissError) },
                    onOpenBrowserSettings = {
                        shellComponent.onIntent(dev.shinglem.hermes.component.shell.ShellIntent.OpenSettings)
                        settingsViewModel.select(SettingsPage.About)
                    },
                    onResize = { widthDp -> browserComponent.onIntent(BrowserIntent.ResizePanel(widthDp)) },
                    onSelectTarget = { pid -> browserComponent.onIntent(BrowserIntent.SelectTarget(pid)) },
                    content = { pid, slotModifier ->
                        val hwnd = browserState.targets.firstOrNull { it.pid == pid }?.hwnd ?: 0L
                        BrowserSlot(hwnd = hwnd, modifier = slotModifier)
                    },
                )
            }
        }
    }
}

/** rail 收成纯图标列的宽度分界。 */
internal val RailCompactBreakpoint = 960.dp

/** 浏览器抽屉的存活心跳间隔：只在抽屉展开期间跑。 */
private const val BROWSER_HEARTBEAT_MS = 5_000L