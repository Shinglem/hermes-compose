package dev.shinglem.hermes.feature.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkivanov.decompose.ComponentContext
import dev.shinglem.hermes.component.backend.DefaultBackendComponent
import dev.shinglem.hermes.component.backend.ProviderBackendGateway
import dev.shinglem.hermes.component.capability.CapabilityComponent
import dev.shinglem.hermes.component.capability.DefaultCapabilityComponent
import dev.shinglem.hermes.component.chat.DefaultChatComponent
import dev.shinglem.hermes.component.composer.ComposerComponent
import dev.shinglem.hermes.component.composer.DefaultComposerComponent
import dev.shinglem.hermes.component.settings.ConfigCustomEndpointGateway
import dev.shinglem.hermes.component.settings.ConfigModelSettingsGateway
import dev.shinglem.hermes.component.settings.ConfigMoaGateway
import dev.shinglem.hermes.component.settings.ConfigProfilesGateway
import dev.shinglem.hermes.component.settings.ConfigProviderKeysGateway
import dev.shinglem.hermes.component.settings.ConfigSecurityGateway
import dev.shinglem.hermes.component.settings.DefaultCustomEndpointComponent
import dev.shinglem.hermes.component.settings.DefaultMaintenanceComponent
import dev.shinglem.hermes.component.settings.DefaultModelSettingsComponent
import dev.shinglem.hermes.component.settings.DefaultMoaComponent
import dev.shinglem.hermes.component.settings.DefaultProfilesComponent
import dev.shinglem.hermes.component.settings.DefaultProviderKeysComponent
import dev.shinglem.hermes.component.settings.DefaultSecurityComponent
import dev.shinglem.hermes.component.shell.DefaultShellComponent
import dev.shinglem.hermes.component.shell.RealShellGateway
import dev.shinglem.hermes.component.shell.ShellComponent
import dev.shinglem.hermes.component.shell.ShellState as NewShellState

import dev.shinglem.hermes.component.settings.MaintenanceComponent
import dev.shinglem.hermes.component.subagent.DefaultSubagentComponent
import dev.shinglem.hermes.component.subagent.RpcSubagentGateway
import dev.shinglem.hermes.component.subagent.SubagentComponent
import dev.shinglem.hermes.component.updater.DefaultUpdaterComponent
import dev.shinglem.hermes.component.updater.RestUpdaterGateway
import dev.shinglem.hermes.component.updater.UpdaterComponent
import dev.shinglem.hermes.di.AppContainer
import dev.shinglem.hermes.feature.capabilities.CapabilityViewModel
import dev.shinglem.hermes.feature.chat.ChatState
import dev.shinglem.hermes.feature.composer.ComposerViewModel
import dev.shinglem.hermes.feature.session.SessionListViewModel
import dev.shinglem.hermes.feature.settings.MaintenanceViewModel
import dev.shinglem.hermes.feature.settings.ProfileChoice
import dev.shinglem.hermes.feature.settings.SettingsViewModel
import dev.shinglem.hermes.component.subagent.SubagentState
import dev.shinglem.hermes.platform.UiDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * `AppShell` 用到的全部 Component / ViewModel / 派生状态。
 *
 * 从 `AppShell` 里搬出来的原因只有一个：那个函数原本 1,100 行，同时承担装配、
 * 副作用编排、导航与三段布局。装配是其中最"机械"的一块，搬出来之后 `AppShell`
 * 只剩布局与必须留在组合期的小状态。
 *
 * 这里**不持有任何 UI 状态**（`showBackendDetail` / `paletteOpen` 之类仍归 `AppShell`），
 * 只有容器与从容器派生出来的域对象。
 */
@Suppress("LongParameterList")
class AppShellDeps(
    val shellComponent: ShellComponent,
    val shellState: dev.shinglem.hermes.component.shell.ShellState,
    val rootComponent: dev.shinglem.hermes.component.root.DefaultRootComponent,
    val sessionListComponent: dev.shinglem.hermes.component.sessionlist.SessionListComponent,
    val sessionViewModel: SessionListViewModel,
    val sessionState: dev.shinglem.hermes.feature.session.SessionListState,
    val chatComponent: dev.shinglem.hermes.component.chat.ChatComponent,
    val chatState: ChatState,
    val composerComponent: ComposerComponent,
    val composerViewModel: ComposerViewModel,
    val composerState: dev.shinglem.hermes.component.composer.ComposerState,
    val backendComponent: dev.shinglem.hermes.component.backend.BackendComponent,
    val capabilityComponent: CapabilityComponent,
    val capabilityViewModel: CapabilityViewModel,
    val capabilityState: dev.shinglem.hermes.feature.capabilities.CapabilityState,
    val settingsViewModel: SettingsViewModel,
    val settingsState: dev.shinglem.hermes.feature.settings.SettingsState,
    val componentScope: CoroutineScope,
    val subagentComponent: DefaultSubagentComponent,
    val subagentState: SubagentState,
    val updaterComponent: UpdaterComponent,
    val updateState: dev.shinglem.hermes.component.updater.UpdaterState,
    val maintenanceComponent: MaintenanceComponent,
    val maintenance: MaintenanceViewModel,
    val maintenanceScopeKey: String?,
    val maintenanceProfileChoices: List<ProfileChoice>,
    val securityComponent: dev.shinglem.hermes.component.settings.DefaultSecurityComponent,
    val providerKeysComponent: dev.shinglem.hermes.component.settings.DefaultProviderKeysComponent,
    val customEndpointComponent: dev.shinglem.hermes.component.settings.DefaultCustomEndpointComponent,
    val moaComponent: dev.shinglem.hermes.component.settings.DefaultMoaComponent,
    val modelSettingsComponent: dev.shinglem.hermes.component.settings.DefaultModelSettingsComponent,
    val profilesComponent: dev.shinglem.hermes.component.settings.DefaultProfilesComponent,
    /** 当前 mode 的名字（`NewShellState.mode.name`）：`AppShell` 用它做导航投影的 key。 */
    val mode: String,
)

/** 装配（原 `AppShell` 函数体前半段）。 */
@Composable
fun rememberAppShellDeps(container: AppContainer, context: ComponentContext): AppShellDeps {
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
    val sessionListComponent: dev.shinglem.hermes.component.sessionlist.SessionListComponent =
        remember(context) {
            dev.shinglem.hermes.component.sessionlist.DefaultSessionListComponent(
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
        CoroutineScope(SupervisorJob() + UiDispatcher.context)
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
    val subagentComponent: DefaultSubagentComponent = remember(context) {
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
    val shellState: NewShellState = newShellState
    // RootComponent 接管导航（S5）：mode 的事实源仍是 ShellComponent，这里只做投影。
    val rootComponent = remember(context) {
        dev.shinglem.hermes.component.root.DefaultRootComponent(
            componentContext = context,
            initialProfile = null,
        )
    }

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

    // 派生状态。`collectAsStateWithLifecycle` 是唯一必须在组合期做的部分。
    val sessionState by sessionViewModel.state.collectAsStateWithLifecycle()
    val chatState by chatComponent.state.collectAsStateWithLifecycle()
    val composerState by composerViewModel.state.collectAsStateWithLifecycle()
    val capabilityState by capabilityViewModel.state.collectAsStateWithLifecycle()
    val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
    val subagentState by subagentComponent.state.collectAsStateWithLifecycle()
    val updateState by updaterComponent.state.collectAsStateWithLifecycle()
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

    return AppShellDeps(
        shellComponent = shellComponent,
        shellState = shellState,
        rootComponent = rootComponent,
        sessionListComponent = sessionListComponent,
        sessionViewModel = sessionViewModel,
        sessionState = sessionState,
        chatComponent = chatComponent,
        chatState = chatState,
        composerComponent = composerComponent,
        composerViewModel = composerViewModel,
        composerState = composerState,
        backendComponent = backendComponent,
        capabilityComponent = capabilityComponent,
        capabilityViewModel = capabilityViewModel,
        capabilityState = capabilityState,
        settingsViewModel = settingsViewModel,
        settingsState = settingsState,
        componentScope = componentScope,
        subagentComponent = subagentComponent,
        subagentState = subagentState,
        updaterComponent = updaterComponent,
        updateState = updateState,
        maintenanceComponent = maintenanceComponent,
        maintenance = maintenance,
        maintenanceScopeKey = maintenanceScopeKey,
        maintenanceProfileChoices = maintenanceProfileChoices,
        securityComponent = securityComponent,
        providerKeysComponent = providerKeysComponent,
        customEndpointComponent = customEndpointComponent,
        moaComponent = moaComponent,
        modelSettingsComponent = modelSettingsComponent,
        profilesComponent = profilesComponent,
        mode = newShellState.mode.name,
    )
}
