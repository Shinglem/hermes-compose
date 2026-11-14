package dev.shinglem.hermes.feature.settings

import androidx.compose.ui.Modifier
import dev.shinglem.hermes.component.backend.BackendComponent
import dev.shinglem.hermes.component.settings.CustomEndpointComponent
import dev.shinglem.hermes.component.settings.ModelSettingsComponent
import dev.shinglem.hermes.component.settings.MoaComponent
import dev.shinglem.hermes.component.settings.ProfilesComponent
import dev.shinglem.hermes.component.settings.ProviderKeysComponent
import dev.shinglem.hermes.component.settings.SecurityComponent
import dev.shinglem.hermes.component.updater.UpdaterState as UpdateState
import dev.shinglem.hermes.core.app.AutostartInfo
import dev.shinglem.hermes.core.app.OrphanTaskInfo
import dev.shinglem.hermes.core.config.EditorsOptions
import dev.shinglem.hermes.core.app.OfficialDesktopLauncher
import dev.shinglem.hermes.feature.terminal.TerminalSettings
/**
 * 设置页详情面板需要的**全部外部依赖**（S5）。
 *
 * 原来它们是 `SettingsDetailPane` 的 38 个平铺参数。那个形状的问题是：
 * 新增一个设置项就要改函数签名，而调用点（`AppShell` 里 60 行的调用）也跟着改，
 * 很容易漏传默认值变成静默失效。收成一个对象后签名只剩 3 个参数。
 */
@androidx.compose.runtime.Immutable
class SettingsDetailScope(
    val state: SettingsState,
    val onEdit: (String, String) -> Unit,
    val onSave: () -> Unit,
    val onRestart: () -> Unit,
    val onDismissSaved: () -> Unit,
    val terminalSettings: TerminalSettings = TerminalSettings(),
    val onTerminalSettingsChange: ((TerminalSettings) -> TerminalSettings) -> Unit = {},
    val providerKeys: ProviderKeysComponent? = null,
    val customEndpoints: CustomEndpointComponent? = null,
    val profiles: ProfilesComponent? = null,
    val modelSettings: ModelSettingsComponent? = null,
    val moa: MoaComponent? = null,
    val security: SecurityComponent? = null,
    val officialDesktop: OfficialDesktopLauncher? = null,
    val autostart: AutostartInfo = AutostartInfo.DISABLED,
    val orphanTasks: List<OrphanTaskInfo> = emptyList(),
    val onDeleteOrphanTask: (String) -> Unit = {},
    val notificationsEnabled: Boolean = true,
    val onNotificationsToggle: (Boolean) -> Unit = {},
    val nativeNotifications: Boolean = false,
    val onNativeNotificationsToggle: (Boolean) -> Unit = {},
    val browserHeaded: Boolean = false,
    val onBrowserHeadedToggle: (Boolean) -> Unit = {},
    val browserArgs: String = "",
    val onBrowserArgsChange: (String) -> Unit = {},
    val editors: EditorsOptions = EditorsOptions(),
    val onEditorsSave: (EditorsOptions) -> Unit = {},
    val updateState: UpdateState = UpdateState(),
    val onCheckUpdate: () -> Unit = {},
    val backendVersion: String = "",
    val dataDir: String = "",
    val logDir: String = "",
    val maintenance: MaintenanceViewModel,
    val backendAdmin: BackendComponent? = null,
    val profileChoices: List<ProfileChoice> = emptyList(),
    val onSelectScopeProfile: (String) -> Unit = {},
    val modifier: Modifier = Modifier,
)
