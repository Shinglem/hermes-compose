package dev.shinglem.hermes.component.settings

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import dev.shinglem.hermes.feature.settings.ConfigRepository
import dev.shinglem.hermes.feature.settings.MaintenanceRepository
import dev.shinglem.hermes.feature.settings.MaintenanceViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlin.coroutines.CoroutineContext

/**
 * 维护页组（记忆 / 诊断维护 / 凭据池 / 用量 / 会话维护 / 集成）。
 *
 * **S2 过渡形态**（与 [SecurityComponent] 等"真模板"不同，这里说明白为什么）：
 * 这一页是 6 个领域、60 多个动作的巨型状态机。逐个搬进 Executor + Reducer 需要重写
 * 全部取消语义，风险远大于收益 —— 而且它与 settings 其它域不一样，**没有一条
 * "进页面就拉的单一数据线"**（六块各有自己的刷新时机）。
 *
 * 所以这轮的做法是：把原 `MaintenanceViewModel` 的 `viewModelScope` 换成 Component 的
 * scope、包成 Component 暴露 `state` + `onIntent`。UI 侧的统一收益（不再散落 60 个回调）
 * 拿到了，逻辑零改动。`13 篇` S2 的"拆 7 个 Store"排到 S8 收尾。
 */
interface MaintenanceComponent {
    val state: StateFlow<MaintenanceState>

    /** 页面内切换「配置目标档案」的当前值（UI 用它做 `LaunchedEffect` 的 key）。 */
    val profileKey: StateFlow<String?>

    fun onIntent(intent: MaintenanceIntent)
}

sealed interface MaintenanceIntent {
    /** 全局档案变化时跟随。 */
    data class SetProfile(val profile: String?) : MaintenanceIntent

    /** 页面内切换配置目标档案（不改 shell 的 activeProfile）。 */
    data class SetScopeProfile(val key: String) : MaintenanceIntent

    // ── 记忆 ──
    data object RefreshMemory : MaintenanceIntent
    data class EditMemoryConfig(val path: String, val text: String) : MaintenanceIntent
    data object SaveMemoryConfig : MaintenanceIntent
    data object DiscardMemoryConfig : MaintenanceIntent
    data class AskResetMemory(val target: String) : MaintenanceIntent
    data object DismissResetMemory : MaintenanceIntent
    data object ConfirmResetMemory : MaintenanceIntent
    data object DismissMemoryNotice : MaintenanceIntent
    data class OpenMemoryFile(val which: String) : MaintenanceIntent
    data class EditMemoryFileText(val text: String) : MaintenanceIntent
    data object SaveMemoryFile : MaintenanceIntent
    data object CloseMemoryFile : MaintenanceIntent

    // ── 诊断与维护 ──
    data object RefreshOps : MaintenanceIntent
    data class RunAction(val action: MaintenanceAction) : MaintenanceIntent
    data object AskPruneCheckpoints : MaintenanceIntent
    data object DismissPruneCheckpoints : MaintenanceIntent
    data object ConfirmPruneCheckpoints : MaintenanceIntent
    data class ToggleCuratorPaused(val paused: Boolean) : MaintenanceIntent
    data object DismissOpsNotice : MaintenanceIntent
    data object DownloadBackup : MaintenanceIntent
    data class EditImportArchive(val path: String) : MaintenanceIntent
    data object ImportBackup : MaintenanceIntent
    data object RunDebugShare : MaintenanceIntent
    data object DismissDebugUrls : MaintenanceIntent

    // ── 凭据池 ──
    data object RefreshCredentials : MaintenanceIntent
    data class EditCredentialProvider(val value: String) : MaintenanceIntent
    data class EditCredentialKey(val value: String) : MaintenanceIntent
    data class EditCredentialLabel(val value: String) : MaintenanceIntent
    data object AddCredential : MaintenanceIntent
    data class AskDeleteCredential(val provider: String, val index: Int) : MaintenanceIntent
    data object DismissDeleteCredential : MaintenanceIntent
    data object ConfirmDeleteCredential : MaintenanceIntent
    data object DismissCredentialNotice : MaintenanceIntent

    // ── 用量 ──
    data class RefreshUsage(val days: Int) : MaintenanceIntent

    // ── 会话维护 ──
    data object RefreshSessionMaintenance : MaintenanceIntent
    data object AskDeleteEmptySessions : MaintenanceIntent
    data object DismissDeleteEmptySessions : MaintenanceIntent
    data object ConfirmDeleteEmptySessions : MaintenanceIntent
    data object DismissSessionNotice : MaintenanceIntent
    data class EditPruneDays(val value: String) : MaintenanceIntent
    data object PreviewPrune : MaintenanceIntent
    data object ConfirmPrune : MaintenanceIntent
    data object DismissPruneReport : MaintenanceIntent
    data class ImportSessionsFromFile(val path: String) : MaintenanceIntent

    // ── 集成 ──
    data object RefreshIntegrations : MaintenanceIntent
    data class EditHookEvent(val value: String) : MaintenanceIntent
    data class EditHookCommand(val value: String) : MaintenanceIntent
    data class EditHookMatcher(val value: String) : MaintenanceIntent
    data object AddHook : MaintenanceIntent
    data class DeleteHook(val event: String, val command: String) : MaintenanceIntent
    data class ApprovePairing(val platform: String, val requestId: String) : MaintenanceIntent
    data class RevokePairing(val platform: String, val userId: String) : MaintenanceIntent
    data object DismissIntegrationNotice : MaintenanceIntent
}

class DefaultMaintenanceComponent(
    private val componentContext: ComponentContext,
    repository: MaintenanceRepository,
    configRepository: ConfigRepository,
    hermesHome: String = "",
    executorContext: CoroutineContext = Dispatchers.Default,
) : MaintenanceComponent, ComponentContext by componentContext {

    private val holder: Holder = componentContext.instanceKeeper.getOrCreate(key = STORE_KEY) {
        val scope = CoroutineScope(SupervisorJob() + executorContext)
        val model = MaintenanceViewModel(
            repository = repository,
            configRepository = configRepository,
            hermesHome = hermesHome,
            scope = scope,
        )
        Holder(model, scope)
    }

    /**
     * 底层状态机。
     *
     * 暴露它是**有意的过渡设计**：Maintenance 有 60 多个动作，逐条包成 Intent 只会让
     * SettingsPane 里多 60 行 { model.onIntent(Xxx) }。所以 UI 直接用它的方法引用，
     * 而 state/onIntent 仍按模板提供（将来 S8 拆 Store 时 UI 无需改签名）。
     */
    val model: MaintenanceViewModel get() = holder.model

    // 六条流合成一份 State：UI 只订阅一个 StateFlow，而六块各自的 loading/busy
    // 仍是独立的小状态机（互不干扰的语义没变）。
    //
    // 为什么要嵌套一次 `combine`：标准库的 `combine` 多参数重载最多 5 个，
    // 而 `combine(vararg)` 要求元素同型（这里是六种不同类型）。5 + 1 两段拼是最直白的解法。
    private val fiveBlocks: Flow<List<Any>> = combine(
        model.memory,
        model.ops,
        model.credentials,
        model.usage,
        model.sessions,
    ) { memory, ops, credentials, usage, sessions ->
        listOf<Any>(memory, ops, credentials, usage, sessions)
    }

    override val state: StateFlow<MaintenanceState> = combine(
        fiveBlocks,
        model.integrations,
    ) { head, integrations ->
        MaintenanceState(
            memory = head[0] as MemoryUiState,
            ops = head[1] as OpsUiState,
            credentials = head[2] as CredentialPoolUiState,
            usage = head[3] as UsageUiState,
            sessions = head[4] as SessionMaintenanceUiState,
            integrations = integrations,
        )
    }.stateIn(
        scope = holder.scope,
        started = SharingStarted.Eagerly,
        initialValue = MaintenanceState(),
    )

    override val profileKey: StateFlow<String?> = model.profileKey

    override fun onIntent(intent: MaintenanceIntent) {
        when (intent) {
            is MaintenanceIntent.SetProfile -> model.setProfile(intent.profile)
            is MaintenanceIntent.SetScopeProfile -> model.setScopeProfile(intent.key)

            MaintenanceIntent.RefreshMemory -> model.refreshMemory()
            is MaintenanceIntent.EditMemoryConfig -> model.editMemoryConfig(intent.path, intent.text)
            MaintenanceIntent.SaveMemoryConfig -> model.saveMemoryConfig()
            MaintenanceIntent.DiscardMemoryConfig -> model.discardMemoryConfig()
            is MaintenanceIntent.AskResetMemory -> model.askResetMemory(intent.target)
            MaintenanceIntent.DismissResetMemory -> model.dismissResetMemory()
            MaintenanceIntent.ConfirmResetMemory -> model.confirmResetMemory()
            MaintenanceIntent.DismissMemoryNotice -> model.dismissMemoryNotice()
            is MaintenanceIntent.OpenMemoryFile -> model.openMemoryFile(intent.which)
            is MaintenanceIntent.EditMemoryFileText -> model.editMemoryFileText(intent.text)
            MaintenanceIntent.SaveMemoryFile -> model.saveMemoryFile()
            MaintenanceIntent.CloseMemoryFile -> model.closeMemoryFile()

            MaintenanceIntent.RefreshOps -> model.refreshOps()
            is MaintenanceIntent.RunAction -> model.runAction(intent.action)
            MaintenanceIntent.AskPruneCheckpoints -> model.askPruneCheckpoints()
            MaintenanceIntent.DismissPruneCheckpoints -> model.dismissPruneCheckpoints()
            MaintenanceIntent.ConfirmPruneCheckpoints -> model.confirmPruneCheckpoints()
            is MaintenanceIntent.ToggleCuratorPaused -> model.toggleCuratorPaused(intent.paused)
            MaintenanceIntent.DismissOpsNotice -> model.dismissOpsNotice()
            MaintenanceIntent.DownloadBackup -> model.downloadBackup()
            is MaintenanceIntent.EditImportArchive -> model.editImportArchive(intent.path)
            MaintenanceIntent.ImportBackup -> model.importBackup()
            MaintenanceIntent.RunDebugShare -> model.runDebugShare()
            MaintenanceIntent.DismissDebugUrls -> model.dismissDebugUrls()

            MaintenanceIntent.RefreshCredentials -> model.refreshCredentials()
            is MaintenanceIntent.EditCredentialProvider -> model.editCredentialProvider(intent.value)
            is MaintenanceIntent.EditCredentialKey -> model.editCredentialKey(intent.value)
            is MaintenanceIntent.EditCredentialLabel -> model.editCredentialLabel(intent.value)
            MaintenanceIntent.AddCredential -> model.addCredential()
            is MaintenanceIntent.AskDeleteCredential -> model.askDeleteCredential(intent.provider, intent.index)
            MaintenanceIntent.DismissDeleteCredential -> model.dismissDeleteCredential()
            MaintenanceIntent.ConfirmDeleteCredential -> model.confirmDeleteCredential()
            MaintenanceIntent.DismissCredentialNotice -> model.dismissCredentialNotice()

            is MaintenanceIntent.RefreshUsage -> model.refreshUsage(intent.days)

            MaintenanceIntent.RefreshSessionMaintenance -> model.refreshSessionMaintenance()
            MaintenanceIntent.AskDeleteEmptySessions -> model.askDeleteEmptySessions()
            MaintenanceIntent.DismissDeleteEmptySessions -> model.dismissDeleteEmptySessions()
            MaintenanceIntent.ConfirmDeleteEmptySessions -> model.confirmDeleteEmptySessions()
            MaintenanceIntent.DismissSessionNotice -> model.dismissSessionNotice()
            is MaintenanceIntent.EditPruneDays -> model.editPruneDays(intent.value)
            MaintenanceIntent.PreviewPrune -> model.previewPrune()
            MaintenanceIntent.ConfirmPrune -> model.confirmPrune()
            MaintenanceIntent.DismissPruneReport -> model.dismissPruneReport()
            is MaintenanceIntent.ImportSessionsFromFile -> model.importSessionsFromFile(intent.path)

            MaintenanceIntent.RefreshIntegrations -> model.refreshIntegrations()
            is MaintenanceIntent.EditHookEvent -> model.editHookEvent(intent.value)
            is MaintenanceIntent.EditHookCommand -> model.editHookCommand(intent.value)
            is MaintenanceIntent.EditHookMatcher -> model.editHookMatcher(intent.value)
            MaintenanceIntent.AddHook -> model.addHook()
            is MaintenanceIntent.DeleteHook -> model.deleteHook(intent.event, intent.command)
            is MaintenanceIntent.ApprovePairing -> model.approvePairing(intent.platform, intent.requestId)
            is MaintenanceIntent.RevokePairing -> model.revokePairing(intent.platform, intent.userId)
            MaintenanceIntent.DismissIntegrationNotice -> model.dismissIntegrationNotice()
        }
    }

    /** ViewModel 与它的 scope 一起装进 `InstanceKeeper`：Component 销毁时一并收。 */
    private class Holder(
        val model: MaintenanceViewModel,
        val scope: CoroutineScope,
    ) : InstanceKeeper.Instance {
        override fun onDestroy() {
            scope.cancel()
        }
    }

    private companion object {
        const val STORE_KEY = "maintenance"
    }
}
