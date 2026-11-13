package dev.shinglem.hermes.component.settings

import androidx.compose.runtime.Immutable
import dev.shinglem.hermes.core.contract.EnvVarDto
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import dev.shinglem.hermes.feature.settings.ProviderGroup
import dev.shinglem.hermes.feature.settings.groupProviderEnvVars
import dev.shinglem.hermes.feature.settings.hasValue
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf

/** 提供方密钥页状态。 */
@Immutable
data class ProviderKeysState(
    val env: Loadable<Map<String, EnvVarDto>> = Loadable.Idle,
    /** 当前数据属于哪个 profile：换 profile 时要丢掉 drafts（明文绝不能串仓库）。 */
    val profile: String? = null,
    /** 用户正在输入的新值（key → 明文）。只在内存，保存后立刻清掉。 */
    val drafts: PersistentMap<String, String> = persistentMapOf(),
    val busyKey: String? = null,
    val error: String? = null,
    /** 刚保存成功的键，用于行内"已保存"提示。 */
    val savedKey: String? = null,
    /** 搜索词：匹配键名 / 描述 / provider 名（provider 有几十个，没有搜索就得滚很久）。 */
    val query: String = "",
) {
    val saving: Boolean get() = busyKey != null

    /**
     * 分组后的可见清单（已按 [query] 过滤）。
     *
     * 仍然放在 State 上：它是**纯派生值**，无副作用、无需缓存，
     * UI 每次重组算一遍的成本远低于维护一份平行的过滤结果（后者会有一致性问题）。
     */
    fun groups(): List<ProviderGroup> {
        val all = groupProviderEnvVars(env.valueOrNull.orEmpty())
        val text = query.trim()
        if (text.isEmpty()) return all
        return all.mapNotNull { group ->
            val entries = group.entries.filter { (key, info) ->
                key.contains(text, ignoreCase = true) ||
                    info.description.contains(text, ignoreCase = true) ||
                    group.label.contains(text, ignoreCase = true)
            }
            if (entries.isEmpty()) null else group.copy(entries = entries)
        }
    }

    /** 已配置的数量（含 custom）。 */
    fun configuredCount(): Int = env.valueOrNull.orEmpty().values.count { it.hasValue() }
}

sealed interface ProviderKeysIntent {
    /** 页面可见 / 后端就绪：[connected] 为 false 时是空操作（同 Security 的理由）。 */
    data class Appear(val connected: Boolean = true) : ProviderKeysIntent

    data object Reload : ProviderKeysIntent

    data class SetQuery(val query: String) : ProviderKeysIntent

    data class Edit(val key: String, val value: String) : ProviderKeysIntent

    data class Save(val key: String) : ProviderKeysIntent

    data object DismissBanner : ProviderKeysIntent
}

internal sealed interface ProviderKeysResult {
    data class Loading(val stale: Map<String, EnvVarDto>?) : ProviderKeysResult
    data class Loaded(val env: Map<String, EnvVarDto>, val profile: String?) : ProviderKeysResult
    data class Failed(val error: AppError, val stale: Map<String, EnvVarDto>?) : ProviderKeysResult
    data class QueryChanged(val query: String) : ProviderKeysResult
    data class Edited(val key: String, val value: String) : ProviderKeysResult
    data class Saving(val key: String) : ProviderKeysResult
    data class Saved(val env: Map<String, EnvVarDto>, val key: String) : ProviderKeysResult
    data class SaveFailed(val key: String, val message: String) : ProviderKeysResult
    data object BannersCleared : ProviderKeysResult
}

sealed interface ProviderKeysEffect {
    data class Toast(val text: String) : ProviderKeysEffect
}
