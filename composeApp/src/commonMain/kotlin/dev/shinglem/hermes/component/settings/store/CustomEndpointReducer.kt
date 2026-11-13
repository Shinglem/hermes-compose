package dev.shinglem.hermes.component.settings.store

import com.arkivanov.mvikotlin.core.store.Reducer
import dev.shinglem.hermes.component.settings.CustomEndpointResult
import dev.shinglem.hermes.component.settings.CustomEndpointState
import dev.shinglem.hermes.core.result.Loadable

/** 自定义端点页的纯状态迁移。 */
internal class CustomEndpointReducer : Reducer<CustomEndpointState, CustomEndpointResult> {

    override fun CustomEndpointState.reduce(msg: CustomEndpointResult): CustomEndpointState = when (msg) {
        is CustomEndpointResult.Loading -> if (msg.stale == null) {
            copy(endpoints = Loadable.Loading, error = null)
        } else {
            copy(error = null)
        }

        is CustomEndpointResult.Loaded ->
            // 换 profile 时正在编辑的表单必须丢掉：否则会把 A 仓库的端点写进 B。
            if (msg.profile != profile) {
                copy(
                    endpoints = Loadable.Ready(msg.endpoints),
                    current = msg.current,
                    profile = msg.profile,
                    draft = null,
                    probe = null,
                    probeOk = null,
                    pendingDelete = null,
                    busyKey = null,
                    error = null,
                    notice = null,
                )
            } else {
                copy(endpoints = Loadable.Ready(msg.endpoints), current = msg.current, error = null)
            }

        is CustomEndpointResult.Failed -> copy(
            endpoints = Loadable.Failed(msg.error, msg.stale),
            busyKey = null,
        )

        is CustomEndpointResult.DraftOpened -> copy(
            draft = msg.draft,
            probe = null,
            probeOk = null,
            error = null,
            notice = null,
        )

        is CustomEndpointResult.DraftChanged -> copy(draft = msg.draft, error = null)

        CustomEndpointResult.DraftClosed -> copy(draft = null, probe = null, probeOk = null, error = null)

        CustomEndpointResult.Validating -> copy(validating = true, probe = null, probeOk = null, error = null)

        is CustomEndpointResult.Probed -> copy(
            validating = false,
            probe = msg.message,
            probeOk = msg.ok,
            draft = msg.resolvedDraft ?: draft,
        )

        is CustomEndpointResult.ProbeFailed -> copy(validating = false, probe = msg.message, probeOk = false)

        is CustomEndpointResult.Saving -> copy(
            busyKey = msg.key,
            // 删除/激活都从"已确认"的状态出发，一进入忙态就收起确认框。
            pendingDelete = null,
            error = null,
            notice = null,
        )

        is CustomEndpointResult.Saved -> copy(
            busyKey = null,
            draft = null,
            probe = null,
            probeOk = null,
            endpoints = Loadable.Ready(msg.endpoints),
            current = msg.current,
            notice = msg.notice,
            error = null,
        )

        is CustomEndpointResult.BusyFailed -> copy(busyKey = null, error = msg.message)

        is CustomEndpointResult.NoticeOnly -> copy(busyKey = null, notice = msg.message)

        is CustomEndpointResult.AskedDelete -> copy(pendingDelete = msg.endpointId)

        CustomEndpointResult.DeleteDismissed -> copy(pendingDelete = null)

        CustomEndpointResult.BannersCleared -> copy(notice = null, error = null)
    }
}
