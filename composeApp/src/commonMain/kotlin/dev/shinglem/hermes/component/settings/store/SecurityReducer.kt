package dev.shinglem.hermes.component.settings.store

import com.arkivanov.mvikotlin.core.store.Reducer
import dev.shinglem.hermes.component.settings.SecurityResult
import dev.shinglem.hermes.component.settings.SecurityState
import dev.shinglem.hermes.core.error.toNotice
import dev.shinglem.hermes.core.result.Loadable
import kotlinx.collections.immutable.persistentMapOf

/**
 * 安全页的**纯**状态迁移：没有协程、没有 IO、没有框架副作用。
 *
 * 这样可以只用 `SecurityReducer().run { SecurityState().reduce(...) }` 断言行为
 * （`SecurityReducerTest`），不需要造 ComponentContext。
 */
internal class SecurityReducer : Reducer<SecurityState, SecurityResult> {

    override fun SecurityState.reduce(msg: SecurityResult): SecurityState = when (msg) {
        // 已有数据时不要退回 Loading：会让表单在刷新时闪一下空白（现网观感问题）。
        is SecurityResult.Loading -> if (msg.stale == null) {
            copy(config = Loadable.Loading, error = null, notice = null)
        } else {
            copy(error = null, notice = null)
        }

        is SecurityResult.Loaded ->
            // profile 变了要丢掉上一份 edits：否则会把 A 仓库的改动写进 B 仓库。
            if (msg.profile != profile) {
                copy(
                    config = Loadable.Ready(msg.config),
                    profile = msg.profile,
                    edits = persistentMapOf(),
                    busyKey = null,
                    error = null,
                    notice = null,
                )
            } else {
                copy(config = Loadable.Ready(msg.config), error = null)
            }

        is SecurityResult.Edited -> copy(
            edits = edits.putting(msg.path, msg.text),
            notice = null,
            error = null,
        )

        is SecurityResult.Failed -> copy(
            // 失败要带上旧值：后端连不上时用户仍能看到并编辑最后一次读到的配置。
            config = Loadable.Failed(msg.error, msg.stale),
            busyKey = null,
        )

        SecurityResult.Saving -> copy(busyKey = "config", error = null, notice = null)

        // 保存成功后回读的配置：同时清掉 edits，否则表单会停留在"已改"状态。
        is SecurityResult.EditsCleared -> copy(
            config = Loadable.Ready(msg.config),
            edits = persistentMapOf(),
            busyKey = null,
            notice = "已保存",
            error = null,
        )

        is SecurityResult.SaveFailed -> copy(
            busyKey = null,
            error = "保存失败：${msg.error.toNotice()}",
        )

        SecurityResult.NothingToSave -> copy(
            busyKey = null,
            error = "没有可保存的改动（数值需要是数字）",
        )

        SecurityResult.BannersCleared -> copy(error = null, notice = null)
    }
}
