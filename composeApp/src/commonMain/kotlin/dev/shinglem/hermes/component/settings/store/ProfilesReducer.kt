package dev.shinglem.hermes.component.settings.store

import com.arkivanov.mvikotlin.core.store.Reducer
import dev.shinglem.hermes.component.settings.ProfilesResult
import dev.shinglem.hermes.component.settings.ProfilesState
import dev.shinglem.hermes.core.result.Loadable

/** 配置档案页的纯状态迁移。 */
internal class ProfilesReducer : Reducer<ProfilesState, ProfilesResult> {

    override fun ProfilesState.reduce(msg: ProfilesResult): ProfilesState = when (msg) {
        is ProfilesResult.Loading -> if (msg.stale == null) {
            copy(list = Loadable.Loading, error = null)
        } else {
            copy(error = null)
        }

        is ProfilesResult.Loaded -> {
            // 「尽力保住选中」的三级回落，与原实现一致：
            // preferred（刚新建/重命名的）→ 原选中（还在的话）→ 默认档 → 第一个。
            val next = msg.preferred?.takeIf { name -> msg.profiles.any { it.name == name } }
                ?: selected?.takeIf { name -> msg.profiles.any { it.name == name } }
                ?: msg.profiles.firstOrNull { it.isDefault }?.name
                ?: msg.profiles.firstOrNull()?.name
            // 选中项变了（典型：删掉了当前档案）→ 右侧的 SOUL 必须一起清掉，
            // 否则详情区会显示一个已经不存在的档案的内容（实测踩过）。
            if (next != selected) {
                copy(
                    list = Loadable.Ready(msg.profiles),
                    selected = next,
                    soul = Loadable.Idle,
                    soulDraft = "",
                    error = null,
                )
            } else {
                copy(list = Loadable.Ready(msg.profiles), error = null)
            }
        }

        is ProfilesResult.Failed -> copy(list = Loadable.Failed(msg.error, msg.stale), busyKey = null)

        is ProfilesResult.SoulLoading -> copy(soul = Loadable.Loading)

        // 结果回来时选中项可能已经变了（用户快速切档案）：只认当前选中。
        is ProfilesResult.SoulLoaded -> if (msg.name == selected) {
            copy(soul = Loadable.Ready(msg.soul), soulDraft = msg.soul.content)
        } else {
            this
        }

        is ProfilesResult.SoulFailed -> if (msg.name == selected) {
            copy(soul = Loadable.Failed(msg.error))
        } else {
            this
        }

        is ProfilesResult.Selected -> copy(selected = msg.name, soul = Loadable.Idle, soulDraft = "")

        is ProfilesResult.SoulEdited -> copy(soulDraft = msg.text, notice = null)

        is ProfilesResult.DraftOpened -> copy(draft = msg.draft, error = null, notice = null)

        ProfilesResult.DraftClosed -> copy(draft = null)

        is ProfilesResult.DraftChanged -> copy(draft = msg.draft)

        is ProfilesResult.Busy -> copy(busyKey = msg.key, error = null, notice = null)

        is ProfilesResult.Succeeded -> copy(busyKey = null, notice = msg.notice)

        is ProfilesResult.SucceededWithSelection -> copy(
            busyKey = null,
            selected = msg.selected,
            soul = Loadable.Idle,
            soulDraft = "",
            notice = msg.notice,
        )

        is ProfilesResult.FailedWithMessage -> copy(busyKey = null, error = msg.message)

        is ProfilesResult.SelectedReset -> copy(
            selected = msg.selected,
            soul = Loadable.Idle,
            soulDraft = "",
        )

        ProfilesResult.BannersCleared -> copy(notice = null, error = null)
    }
}
