package dev.shinglem.hermes.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.shinglem.hermes.core.app.AppRestarter
import dev.shinglem.hermes.core.app.RestartSupport
import dev.shinglem.hermes.core.config.AppConfig
import dev.shinglem.hermes.core.config.AppOptionsHolder
import dev.shinglem.hermes.core.config.SettingsStore
import dev.shinglem.hermes.core.config.setJsonAt
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.prefs.AppPreferences
import dev.shinglem.hermes.platform.NativeNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 保存后的提示：`restartRequired` 决定是「重启生效」还是「已即时生效」。 */
data class SettingsSaved(val restartRequired: Boolean)

data class SettingsState(
    val sections: List<SettingsSection> = SettingsCatalog.sections(debugAvailable = false),
    /** 当前选中的二级页面。 */
    val selected: SettingsPage = SettingsCatalog.defaultPage,
    /** 已折叠的一级分组 key。 */
    val collapsedSections: Set<String> = emptySet(),
    /** key → 表单里的文本。 */
    val values: Map<String, String> = emptyMap(),
    val error: String? = null,
    val saved: SettingsSaved? = null,
    /** 正在重启：按钮转圈、禁用重复点击。 */
    val restarting: Boolean = false,
    /** 新进程已经拉起来了，UI 收到后退出当前进程。 */
    val exitRequested: Boolean = false,
    val restartSupport: RestartSupport = RestartSupport.SUPPORTED,
    /** 桌面通知开关（客户端行为，不写 config；存 `state.json`）。 */
    val notificationsEnabled: Boolean = true,
    /** 用系统原生通知（Windows toast）；默认关 = 应用内自绘窗。 */
    val nativeNotifications: Boolean = false,
) {
    /** 选中项对应的字段组；选中的是自绘页时为 `null`。 */
    val currentGroup: SettingsGroup?
        get() = (selected as? SettingsPage.Catalog)?.let { groups.getOrNull(it.index) }

    val groups: List<SettingsGroup> get() = SettingsCatalog.groups
}

/**
 * 设置页状态：字段目录来自 [SettingsCatalog]，值来自当前生效的 config/options。
 *
 * 保存只写 `*.custom.json`（[SettingsStore]），默认配置永远不动 —— 这样升级时覆盖模板文件
 * 不会冲掉用户的改动，用户也能一眼看出「哪些值是我改过的」。
 */
class SettingsViewModel(
    private val store: SettingsStore,
    private val config: AppConfig,
    private val restarter: AppRestarter,
    /** 折叠状态等 UI 偏好写 `state.json`。 */
    private val preferences: AppPreferences? = null,
    /** 是否检测到官方桌面端（决定左栏是否出现「调试」分组）。 */
    private val debugAvailable: Boolean = false,
    /** 重启走异步；单测里注入 `Dispatchers.Unconfined` 的 scope，不必拉起 Main。 */
    externalScope: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = externalScope ?: viewModelScope

    private val _state = MutableStateFlow(
        SettingsState(
            sections = SettingsCatalog.sections(debugAvailable),
            values = SettingsCatalog.snapshot(config, AppOptionsHolder.current),
            restartSupport = restarter.support,
            collapsedSections = parseCollapsed(preferences?.settingsCollapsed),
            notificationsEnabled = preferences?.notificationsEnabled ?: true,
            nativeNotifications = preferences?.nativeNotifications ?: false,
        ),
    )
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    /** 用户动过的键。只写这些 —— 否则保存一次就会把全部默认值抄进 `*.custom.json`。 */
    private val dirty = mutableSetOf<String>()

    fun select(page: SettingsPage) {
        _state.update { it.copy(selected = page, saved = null) }
    }

    fun toggleSection(key: String) {
        val collapsed = _state.value.collapsedSections
        val next = if (key in collapsed) collapsed - key else collapsed + key
        _state.update { it.copy(collapsedSections = next) }
        preferences?.settingsCollapsed = next.sorted().joinToString(",").ifBlank { null }
    }

    fun edit(key: String, text: String) {
        dirty += key
        _state.update { it.copy(values = it.values + (key to text), error = null, saved = null) }
    }

    /** 桌面通知开关：写 `state.json`，立即生效（不需要重启）。 */
    fun setNotificationsEnabled(enabled: Boolean) {
        preferences?.notificationsEnabled = enabled
        _state.update { it.copy(notificationsEnabled = enabled) }
    }

    /**
     * 系统原生通知开关：写 `state.json` 并立即 `sync`。
     *
     * 打开时由 `NativeNotifications` 创建/更新开始菜单快捷方式（AUMID 归属的硬要求），
     * 关掉则反初始化、不留后台状态。
     */
    fun setNativeNotifications(enabled: Boolean) {
        preferences?.nativeNotifications = enabled
        _state.update { it.copy(nativeNotifications = enabled) }
        NativeNotifications.sync(enabled)
    }

    fun dismissSaved() {
        _state.update { it.copy(saved = null, error = null) }
    }

    /**
     * 重启客户端：先停后端再起新进程，成功后由 UI 退出当前进程。
     *
     * 不直接 `exitProcess`：窗口与托盘要正常收尾（偏好落盘、Job 句柄关闭），
     * 退出这一步交给 `application` 作用域。
     */
    fun restart() {
        if (_state.value.restarting) return
        _state.update { it.copy(restarting = true, error = null) }
        scope.launch {
            val started = runCatching { restarter.restart() }.getOrElse { failure ->
                Log.error("重启客户端失败", failure)
                false
            }
            _state.update {
                if (started) {
                    it.copy(restarting = false, exitRequested = true)
                } else {
                    it.copy(
                        restarting = false,
                        error = when (it.restartSupport) {
                            RestartSupport.DEV_MODE -> "开发态不支持自动重启，请用 Gradle 重新运行"
                            RestartSupport.UNSUPPORTED -> "当前启动方式不支持自动重启，请手动关闭后重新打开"
                            RestartSupport.SUPPORTED -> "重启失败，请手动关闭后重新打开（详见日志）"
                        },
                    )
                }
            }
        }
    }

    fun save() {
        var configPatch = JsonObject(emptyMap())
        var optionsPatch = JsonObject(emptyMap())
        var restartRequired = false

        for (field in SettingsCatalog.allFields) {
            if (field.key !in dirty) continue
            val text = _state.value.values[field.key].orEmpty().trim()
            // 空串 + 不可为空 = 这个字段还没填好，先不动它（由它保持原值）。
            if (text.isEmpty() && !field.nullable) continue
            val element = toElement(field, text)
            if (element == null) {
                _state.update {
                    it.copy(
                        error = "${field.label}：需要${if (field.kind == SettingsKind.NUMBER) "数字" else "有效值"}",
                        saved = null,
                    )
                }
                return
            }
            when (field.scope) {
                SettingsScope.RESTART -> {
                    configPatch = setJsonAt(configPatch, field.jsonPath, element)
                    restartRequired = true
                }

                SettingsScope.LIVE -> optionsPatch = setJsonAt(optionsPatch, field.jsonPath, element)
            }
        }

        runCatching {
            if (configPatch.isNotEmpty()) store.saveConfigPatch(configPatch)
            if (optionsPatch.isNotEmpty()) store.saveOptionsPatch(optionsPatch)
        }.onFailure { failure ->
            Log.error("保存设置失败", failure)
            _state.update { it.copy(error = "保存失败：${failure.message}", saved = null) }
            return
        }

        dirty.clear()
        Log.info(
            "设置已保存：config ${configPatch.size} 项、options ${optionsPatch.size} 项" +
                if (restartRequired) "（部分需要重启生效）" else "（即时生效）",
        )
        _state.update { it.copy(saved = SettingsSaved(restartRequired), error = null) }
    }

    /** 返回 `null` 表示这个输入不能写进配置（校验失败）。 */
    private fun toElement(field: SettingsField, text: String): JsonElement? = when (field.kind) {
        SettingsKind.BOOLEAN -> JsonPrimitive(text.equals("true", ignoreCase = true))
        SettingsKind.NUMBER -> text.toLongOrNull()?.let { JsonPrimitive(it) }
        SettingsKind.ENUM -> field.enumValues.firstOrNull { it.equals(text, ignoreCase = true) }
            ?.let { JsonPrimitive(it) }

        SettingsKind.TEXT -> if (text.isEmpty() && field.nullable) JsonNull else JsonPrimitive(text)
    }
}

/**
 * `state.json` 里的折叠状态：逗号分隔的分组 key。
 *
 * 老版本只存过一个 `options`（当时只有一组），解析时忽略空串即可兼容。
 */
private fun parseCollapsed(raw: String?): Set<String> =
    raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
