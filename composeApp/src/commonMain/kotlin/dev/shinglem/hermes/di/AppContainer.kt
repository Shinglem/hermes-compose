package dev.shinglem.hermes.di

import dev.shinglem.hermes.core.app.AppRestarter
import dev.shinglem.hermes.core.app.AutostartInfo
import dev.shinglem.hermes.core.app.OrphanTaskInfo
import dev.shinglem.hermes.core.app.OfficialDesktopLauncher
import dev.shinglem.hermes.core.backend.BackendProvider
import dev.shinglem.hermes.core.config.AppConfig
import dev.shinglem.hermes.core.config.SettingsStore
import dev.shinglem.hermes.core.log.AppLogger
import dev.shinglem.hermes.core.prefs.AppPreferences
import dev.shinglem.hermes.core.transport.GatewayClient
import dev.shinglem.hermes.core.transport.RestClient
import dev.shinglem.hermes.feature.capabilities.CapabilityRepository
import dev.shinglem.hermes.feature.chat.ChatRepository
import dev.shinglem.hermes.feature.composer.ComposerRepository
import dev.shinglem.hermes.feature.model.ModelRepository
import dev.shinglem.hermes.feature.settings.BrowserArgsReconciler
import dev.shinglem.hermes.feature.settings.ConfigRepository
import dev.shinglem.hermes.feature.settings.MaintenanceRepository
import dev.shinglem.hermes.feature.settings.CustomEndpointRepository
import dev.shinglem.hermes.feature.settings.ModelAdminRepository
import dev.shinglem.hermes.feature.settings.ProfilesAdminRepository
import dev.shinglem.hermes.feature.settings.ProviderRepository
import dev.shinglem.hermes.feature.session.SessionRepository
import dev.shinglem.hermes.feature.shell.ProfileRepository
import dev.shinglem.hermes.feature.terminal.TerminalRepository
import dev.shinglem.hermes.feature.terminal.TerminalViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 手工装配（`.design/草案/07-decisions.md` ADR-013）：进程级唯一。
 *
 * `GatewayClient` / `RestClient` 必须是应用级单例——后续多窗口复用同一条
 * WebSocket，各窗口各建一条会导致事件分叉。
 */
class AppContainer(
    val gateway: GatewayClient,
    val restClient: RestClient,
    val preferences: AppPreferences,
    val sessionRepository: SessionRepository,
    val profileRepository: ProfileRepository,
    val chatRepository: ChatRepository,
    val composerRepository: ComposerRepository,
    val modelRepository: ModelRepository,
    val capabilityRepository: CapabilityRepository,
    /** 提供方凭据（`/api/env`）：设置中心「提供方 → 密钥」。 */
    val providerRepository: ProviderRepository,
    /** 自定义端点（`/api/providers/custom-endpoints`）：设置中心「提供方 → 自定义端点」。 */
    val customEndpointRepository: CustomEndpointRepository,
    /** 配置档案管理（`/api/profiles` 系列）：设置中心「配置档案」。 */
    val profilesAdminRepository: ProfilesAdminRepository,
    /** profile 默认模型与辅助模型（`/api/model` 与 `PUT /api/config`）：设置中心「模型」。 */
    val modelAdminRepository: ModelAdminRepository,
    /** 后端 config.yaml 的读改（`/api/config`）：模型上下文覆盖与「安全」三页共用。 */
    val configRepository: ConfigRepository,
    /**
     * 把 `--do-not-de-elevate` 钉进后端 `browser.chromium_args`（仅管理员运行时有效）。
     *
     * 以管理员运行时 Chromium 会「自动去提权」，导致后端误判 `Chrome exited early`；
     * 详见 [dev.shinglem.hermes.feature.settings.BrowserArgsReconciler]。
     */
    val browserArgsReconciler: BrowserArgsReconciler,
    /** 设置中心「维护」页组：记忆、后台动作、凭据池、用量、会话统计（`/api/{memory,ops,analytics,sessions}`）。 */
    val maintenanceRepository: MaintenanceRepository,
    /** 官方桌面端（debug-only，v1.0.0 移除）：检测到才让设置页出现「调试」入口。 */
    val officialDesktop: OfficialDesktopLauncher? = null,
    /** 终端面板状态（全局，不属于会话；见 ADR-041）。 */
    val terminalViewModel: TerminalViewModel,
    /** `terminal.read` 应答与 agent 镜像标签的事件订阅。 */
    val terminalRepository: TerminalRepository,
    val logger: AppLogger,
    private val scope: CoroutineScope,
    /** 启动时解析出的配置（不可变，全程只读）。 */
    val config: AppConfig,
    /** 后端数据根（`HERMES_HOME`）的绝对路径：维护页要用它拼记忆文件路径。 */
    val hermesHome: String = "",
    /** 后端来源：`local` 拉起随包 payload 的子进程，`remote` 连既有服务。 */
    val backend: BackendProvider,
    /** 设置页的落盘入口：只写 `*.custom.json`。 */
    val settingsStore: SettingsStore,
    /** 「重启客户端」：停后端 → 用同一条命令行起新进程。 */
    val restarter: AppRestarter,
    /** 当前进程是否以管理员运行（状态栏用来提示"配置要求管理员但这次没拿到"）。 */
    val elevated: Boolean,
    /** 配置里是否要求管理员（`app.elevate = ask`）。 */
    val elevateRequested: Boolean,
    /** 开机自启的落地状态（设置页显示）。容器建好时还不知道结果 —— reconcile 发生在提权之后。 */
    val autostart: MutableStateFlow<AutostartInfo> = MutableStateFlow(AutostartInfo.DISABLED),
    /** 便携包挪过位置后留下的失效任务（指向已不存在的程序）；只展示，删除需逐条确认。 */
    val orphanTasks: MutableStateFlow<List<OrphanTaskInfo>> = MutableStateFlow(emptyList()),
    /** 删除一条失效任务（需要管理员）。返回 false = 失败，UI 保留条目。 */
    val deleteOrphanTask: (String) -> Boolean = { false },
) {
    fun close() {
        // 顺序：先断 WS（避免后端写事件时对端消失），再停子进程，最后收尾。
        gateway.close()
        restClient.close()
        backend.shutdown()
        scope.cancel()
        // 偏好写入是延迟落盘的，退出前必须补一次，否则最后 200ms 内的改动会丢。
        preferences.flush()
    }
}
