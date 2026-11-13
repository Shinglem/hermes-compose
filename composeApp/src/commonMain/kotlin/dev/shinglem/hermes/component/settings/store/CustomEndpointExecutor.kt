package dev.shinglem.hermes.component.settings.store

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import dev.shinglem.hermes.component.settings.CustomEndpointEffect
import dev.shinglem.hermes.component.settings.CustomEndpointGateway
import dev.shinglem.hermes.component.settings.CustomEndpointIntent
import dev.shinglem.hermes.component.settings.CustomEndpointResult
import dev.shinglem.hermes.component.settings.CustomEndpointState
import dev.shinglem.hermes.core.error.asAppError
import dev.shinglem.hermes.core.error.toUserNotice
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import dev.shinglem.hermes.feature.settings.EndpointDraft
import dev.shinglem.hermes.feature.settings.probeMessage
import dev.shinglem.hermes.component.settings.resolveProbeUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * 自定义端点页的 IO 边界。
 *
 * 与 Security/ProviderKeys 的差别：这里有多条**互斥的忙状态**（保存 / 激活 / 删除），
 * 所以忙标记是带前缀的字符串（`save` / `activate:<id>` / `delete:<id>`），
 * 而不是一个布尔 —— 原实现就是这么做的，保留（UI 要按行显示 spinner）。
 */
internal class CustomEndpointExecutor(
    private val gateway: CustomEndpointGateway,
    private val profile: String?,
    executorContext: CoroutineContext,
) : CoroutineExecutor<
    CustomEndpointIntent,
    Nothing,
    CustomEndpointState,
    CustomEndpointResult,
    CustomEndpointEffect,
    >(executorContext) {

    private var loadJob: Job? = null

    override fun executeIntent(intent: CustomEndpointIntent) {
        when (intent) {
            is CustomEndpointIntent.Appear -> if (intent.connected) loadIfNeeded()
            CustomEndpointIntent.Reload -> load()
            CustomEndpointIntent.StartCreate -> dispatch(
                CustomEndpointResult.DraftOpened(EndpointDraft(apiMode = "")),
            )

            is CustomEndpointIntent.StartEdit -> dispatch(
                CustomEndpointResult.DraftOpened(
                    EndpointDraft(
                        id = intent.endpoint.id,
                        name = intent.endpoint.name,
                        baseUrl = intent.endpoint.baseUrl,
                        model = intent.endpoint.model,
                        // 明文不回显：编辑时密码框留空，不填就保持原值（后端语义）。
                        apiKey = "",
                        apiMode = intent.endpoint.apiMode,
                        contextDraft = intent.endpoint.contextLength?.toString().orEmpty(),
                        discoverModels = intent.endpoint.discoverModels,
                        makeDefault = false,
                    ),
                ),
            )

            is CustomEndpointIntent.EditDraft -> dispatch(CustomEndpointResult.DraftChanged(intent.draft))
            CustomEndpointIntent.CancelEdit -> dispatch(CustomEndpointResult.DraftClosed)
            CustomEndpointIntent.Validate -> validate()
            CustomEndpointIntent.Save -> save()
            is CustomEndpointIntent.Activate -> activate(intent.endpointId)
            is CustomEndpointIntent.AskDelete -> dispatch(CustomEndpointResult.AskedDelete(intent.endpointId))
            CustomEndpointIntent.DismissDelete -> dispatch(CustomEndpointResult.DeleteDismissed)
            CustomEndpointIntent.ConfirmDelete -> confirmDelete()
            CustomEndpointIntent.DismissBanner -> dispatch(CustomEndpointResult.BannersCleared)
        }
    }

    private fun loadIfNeeded() {
        val state = state()
        if (state.profile == profile && state.endpoints !is Loadable.Idle) return
        load()
    }

    private fun load() {
        loadJob?.cancel()
        val stale = state().endpoints.valueOrNull
        dispatch(CustomEndpointResult.Loading(stale))
        loadJob = scope.launch {
            try {
                val response = gateway.list(profile)
                dispatch(CustomEndpointResult.Loaded(response.endpoints, response.current, profile))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(CustomEndpointResult.Failed(throwable.asAppError(), stale))
            }
        }
    }

    private fun validate() {
        val draft = state().draft ?: return
        if (state().validating || !draft.canSubmit) return
        dispatch(CustomEndpointResult.Validating)
        scope.launch {
            try {
                val probe = gateway.validate(profile, draft.toUpdate())
                dispatch(
                    CustomEndpointResult.Probed(
                        message = probeMessage(probe),
                        ok = probe.ok,
                        resolvedDraft = resolveProbeUrl(state().draft, probe.resolvedBaseUrl),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(CustomEndpointResult.ProbeFailed("探测失败：${throwable.toUserNotice()}"))
            }
        }
    }

    private fun save() {
        val state = state()
        val draft = state.draft ?: return
        if (state.busyKey != null || !draft.canSubmit) return
        dispatch(CustomEndpointResult.Saving("save"))
        scope.launch {
            try {
                val saved = gateway.upsert(profile, draft.toUpdate())
                Log.info("自定义端点已保存：${draft.name} → ${saved.id}")
                dispatch(
                    CustomEndpointResult.Saved(
                        endpoints = saved.endpoints,
                        current = saved.current,
                        notice = if (draft.isNew) "已添加 ${draft.name}" else "已更新 ${draft.name}",
                    ),
                )
                publish(CustomEndpointEffect.Toast(if (draft.isNew) "已添加" else "已更新"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(CustomEndpointResult.BusyFailed("保存失败：${throwable.toUserNotice()}"))
            }
        }
    }

    private fun activate(endpointId: String) {
        if (state().busyKey != null) return
        dispatch(CustomEndpointResult.Saving("activate:$endpointId"))
        scope.launch {
            try {
                val result = gateway.activate(profile, endpointId)
                dispatch(
                    CustomEndpointResult.NoticeOnly(
                        "主模型已指向 ${result.provider} / ${result.model}（对新会话生效）",
                    ),
                )
                load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(CustomEndpointResult.BusyFailed("激活失败：${throwable.toUserNotice()}"))
            }
        }
    }

    private fun confirmDelete() {
        val endpointId = state().pendingDelete ?: return
        if (state().busyKey != null) return
        dispatch(CustomEndpointResult.Saving("delete:$endpointId"))
        scope.launch {
            try {
                gateway.delete(profile, endpointId)
                Log.info("自定义端点已删除：$endpointId")
                dispatch(CustomEndpointResult.NoticeOnly("已删除 $endpointId"))
                load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(CustomEndpointResult.BusyFailed("删除失败：${throwable.toUserNotice()}"))
            }
        }
    }
}
