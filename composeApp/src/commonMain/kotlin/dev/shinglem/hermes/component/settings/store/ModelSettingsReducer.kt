package dev.shinglem.hermes.component.settings.store

import com.arkivanov.mvikotlin.core.store.Reducer
import dev.shinglem.hermes.component.settings.ModelSettingsResult
import dev.shinglem.hermes.component.settings.ModelSettingsState
import dev.shinglem.hermes.core.result.Loadable

/** 模型配置页的纯状态迁移。 */
internal class ModelSettingsReducer : Reducer<ModelSettingsState, ModelSettingsResult> {

    override fun ModelSettingsState.reduce(msg: ModelSettingsResult): ModelSettingsState = when (msg) {
        // ── info（主模型当前值）──
        is ModelSettingsResult.InfoLoading -> if (msg.stale == null) {
            copy(info = Loadable.Loading, error = null)
        } else {
            copy(error = null)
        }

        is ModelSettingsResult.InfoLoaded -> {
            val switched = msg.profile != profile
            copy(
                info = Loadable.Ready(msg.info),
                // 选择框只在"还没选过"时回填后端值：用户正在改时不要覆盖他的输入。
                provider = if (switched || provider.isBlank()) msg.info.provider else provider,
                model = if (switched || model.isBlank()) msg.info.model else model,
                contextDraft = when {
                    !switched && contextDraft.isNotBlank() -> contextDraft
                    msg.info.configContextLength > 0 -> msg.info.configContextLength.toString()
                    else -> ""
                },
                profile = msg.profile,
                busyKey = null,
                error = null,
            )
        }

        is ModelSettingsResult.InfoFailed -> copy(
            info = Loadable.Failed(msg.error, msg.stale),
            busyKey = null,
        )

        // ── 目录 ──
        is ModelSettingsResult.CatalogLoading -> if (msg.stale == null) {
            copy(catalog = Loadable.Loading)
        } else {
            this
        }

        is ModelSettingsResult.CatalogLoaded -> copy(catalog = Loadable.Ready(msg.catalog))

        is ModelSettingsResult.CatalogFailed -> copy(catalog = Loadable.Failed(msg.error, msg.stale))

        // ── 辅助模型 ──
        is ModelSettingsResult.AuxLoading -> if (msg.stale == null) {
            copy(aux = Loadable.Loading)
        } else {
            this
        }

        is ModelSettingsResult.AuxLoaded -> copy(aux = Loadable.Ready(msg.aux))

        is ModelSettingsResult.AuxFailed -> copy(aux = Loadable.Failed(msg.error, msg.stale))

        // ── 备用模型链 ──
        is ModelSettingsResult.FallbackLoading -> if (msg.stale == null) {
            copy(fallback = Loadable.Loading)
        } else {
            this
        }

        is ModelSettingsResult.FallbackLoaded -> copy(
            fallback = Loadable.Ready(msg.entries),
            fallbackKeepArray = msg.keepArray,
        )

        is ModelSettingsResult.FallbackFailed -> copy(fallback = Loadable.Failed(msg.error, msg.stale))

        is ModelSettingsResult.FallbackEdited -> copy(
            fallback = Loadable.Ready(msg.entries),
            error = null,
            notice = null,
        )

        // ── 编辑 ──
        is ModelSettingsResult.SelectionChanged -> copy(
            provider = msg.provider,
            model = msg.model,
            notice = null,
        )

        is ModelSettingsResult.ContextEdited -> copy(contextDraft = msg.text, notice = null)

        // ── 忙 / 结果 ──
        is ModelSettingsResult.Busy -> copy(busyKey = msg.key, error = null, notice = null)

        is ModelSettingsResult.Confirmed -> copy(busyKey = null, pending = msg.pending)

        ModelSettingsResult.PendingDismissed -> copy(pending = null)

        is ModelSettingsResult.Succeeded -> copy(busyKey = null, pending = null, notice = msg.notice)

        is ModelSettingsResult.Failed -> copy(busyKey = null, error = msg.message)

        is ModelSettingsResult.Invalid -> copy(error = msg.message)

        // 成功但不需要清 pending（例如"已重置辅助模型"）。
        is ModelSettingsResult.Quiet -> copy(busyKey = null, notice = msg.notice)

        ModelSettingsResult.BannersCleared -> copy(notice = null, error = null)
    }
}
