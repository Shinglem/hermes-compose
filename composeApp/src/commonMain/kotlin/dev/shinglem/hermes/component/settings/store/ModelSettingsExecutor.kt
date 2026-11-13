package dev.shinglem.hermes.component.settings.store

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import dev.shinglem.hermes.component.settings.ModelSettingsEffect
import dev.shinglem.hermes.component.settings.ModelSettingsGateway
import dev.shinglem.hermes.component.settings.ModelSettingsIntent
import dev.shinglem.hermes.component.settings.ModelSettingsResult
import dev.shinglem.hermes.component.settings.ModelSettingsState
import dev.shinglem.hermes.component.settings.PendingModelConfirm
import dev.shinglem.hermes.core.error.asAppError
import dev.shinglem.hermes.core.error.toUserNotice
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import dev.shinglem.hermes.feature.settings.FallbackEntry
import dev.shinglem.hermes.feature.settings.contextLengthPatch
import dev.shinglem.hermes.feature.settings.fallbackPatch
import dev.shinglem.hermes.feature.settings.readFallbackProviders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * 模型配置页的 IO 边界。
 *
 * 四条独立的数据线（info / 目录 / 辅助模型 / 备用模型）各自有自己的 loading 与失败态，
 * 所以四个 `load*` 方法各自展开，不做统一抽象 —— 统一化会让"某个失败不该拖垮其它三个"
 * 这件事变得难读。
 */
internal class ModelSettingsExecutor(
    private val gateway: ModelSettingsGateway,
    private val profile: String?,
    executorContext: CoroutineContext,
) : CoroutineExecutor<
    ModelSettingsIntent,
    Nothing,
    ModelSettingsState,
    ModelSettingsResult,
    ModelSettingsEffect,
    >(executorContext) {

    private var infoJob: Job? = null
    private var catalogJob: Job? = null
    private var auxJob: Job? = null
    private var fallbackJob: Job? = null

    override fun executeIntent(intent: ModelSettingsIntent) {
        when (intent) {
            is ModelSettingsIntent.Appear -> if (intent.connected) {
                // 四条线并发拉：互不依赖，串行只会让首屏更慢。
                if (shouldLoadInfo()) loadInfo()
                loadCatalog()
                loadAux()
                loadFallback()
            }

            ModelSettingsIntent.ReloadInfo -> loadInfo()
            ModelSettingsIntent.ReloadCatalog -> loadCatalog()
            ModelSettingsIntent.ReloadAux -> loadAux()
            ModelSettingsIntent.ReloadFallback -> loadFallback()

            is ModelSettingsIntent.SelectProvider -> {
                // 换 provider 时把模型重置成该 provider 的第一个（原行为）。
                val first = state().modelsOf(intent.provider).firstOrNull()?.model.orEmpty()
                dispatch(ModelSettingsResult.SelectionChanged(intent.provider, first))
            }

            is ModelSettingsIntent.SelectModel -> dispatch(
                ModelSettingsResult.SelectionChanged(state().provider, intent.model),
            )

            is ModelSettingsIntent.EditContext -> dispatch(
                ModelSettingsResult.ContextEdited(intent.text.filter { ch -> ch.isDigit() }),
            )

            ModelSettingsIntent.ApplyMain -> applyMain()
            ModelSettingsIntent.ConfirmPending -> confirmPending()
            ModelSettingsIntent.DismissPending -> dispatch(ModelSettingsResult.PendingDismissed)
            ModelSettingsIntent.SaveContextLength -> saveContextLength()
            is ModelSettingsIntent.AssignAuxTask -> assignAux(intent.task)
            ModelSettingsIntent.ResetAux -> resetAux()
            ModelSettingsIntent.AddFallback -> addFallback()
            is ModelSettingsIntent.RemoveFallback -> removeFallback(intent.index)
            ModelSettingsIntent.SaveFallback -> saveFallback()
            ModelSettingsIntent.DismissBanner -> dispatch(ModelSettingsResult.BannersCleared)
        }
    }

    private fun shouldLoadInfo(): Boolean =
        state().profile != profile || state().info is Loadable.Idle

    private fun loadInfo() {
        infoJob?.cancel()
        val stale = state().info.valueOrNull
        dispatch(ModelSettingsResult.InfoLoading(stale))
        infoJob = scope.launch {
            try {
                dispatch(ModelSettingsResult.InfoLoaded(gateway.info(profile), profile))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(ModelSettingsResult.InfoFailed(throwable.asAppError(), stale))
            }
        }
    }

    private fun loadCatalog() {
        catalogJob?.cancel()
        val stale = state().catalog.valueOrNull
        dispatch(ModelSettingsResult.CatalogLoading(stale))
        catalogJob = scope.launch {
            try {
                dispatch(ModelSettingsResult.CatalogLoaded(gateway.options(profile, refresh = false)))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(ModelSettingsResult.CatalogFailed(throwable.asAppError(), stale))
            }
        }
    }

    private fun loadAux() {
        auxJob?.cancel()
        val stale = state().aux.valueOrNull
        dispatch(ModelSettingsResult.AuxLoading(stale))
        auxJob = scope.launch {
            try {
                dispatch(ModelSettingsResult.AuxLoaded(gateway.auxiliary(profile)))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(ModelSettingsResult.AuxFailed(throwable.asAppError(), stale))
            }
        }
    }

    private fun loadFallback() {
        fallbackJob?.cancel()
        val stale = state().fallback.valueOrNull
        dispatch(ModelSettingsResult.FallbackLoading(stale))
        fallbackJob = scope.launch {
            try {
                val (entries, keepArray) = readFallbackProviders(gateway.readConfig(profile))
                dispatch(ModelSettingsResult.FallbackLoaded(entries, keepArray))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(ModelSettingsResult.FallbackFailed(throwable.asAppError(), stale))
            }
        }
    }

    private fun applyMain() {
        val state = state()
        if (state.busyKey != null) return
        val provider = state.effectiveProvider
        val model = state.effectiveModel
        if (provider.isBlank() && model.isBlank()) return
        assign("main", provider, model, task = null)
    }

    private fun confirmPending() {
        val pending = state().pending ?: return
        assign(pending.scope, pending.provider, pending.model, pending.task, confirmExpensive = true)
    }

    private fun assignAux(task: dev.shinglem.hermes.core.contract.AuxTaskDto) {
        val state = state()
        val model = state.effectiveModel
        if (model.isBlank()) {
            dispatch(ModelSettingsResult.Invalid("先在主模型页选一个模型，再钉到辅助任务"))
            return
        }
        assign("auxiliary", state.effectiveProvider, model, task.task)
    }

    private fun assign(
        assignScope: String,
        provider: String,
        model: String,
        task: String?,
        confirmExpensive: Boolean = false,
    ) {
        if (state().busyKey != null) return
        val key = task?.let { "aux:$it" } ?: "main"
        dispatch(ModelSettingsResult.Busy(key))
        scope.launch {
            try {
                val result = gateway.assignModel(profile, assignScope, provider, model, task, confirmExpensive)
                if (result.confirmRequired && !confirmExpensive) {
                    dispatch(
                        ModelSettingsResult.Confirmed(
                            PendingModelConfirm(
                                scope = assignScope,
                                provider = provider,
                                model = model,
                                task = task,
                                message = result.confirmMessage.ifBlank { "该模型价格较高，确认使用？" },
                            ),
                        ),
                    )
                    return@launch
                }
                Log.info("模型指派已保存：scope=$assignScope task=$task provider=$provider model=$model")
                val notice = buildString {
                    append(if (assignScope == "main") "主模型已切换" else "辅助任务已更新")
                    if (result.staleAux.isNotEmpty()) {
                        append("（这些辅助槽仍指向旧模型：${result.staleAux.joinToString("、")}）")
                    }
                }
                dispatch(ModelSettingsResult.Succeeded(notice))
                publish(
                    ModelSettingsEffect.Toast(
                        if (assignScope == "main") "主模型已切换" else "辅助任务已更新",
                    ),
                )
                refreshAfterAssign(assignScope)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(ModelSettingsResult.Failed("保存失败：${throwable.toUserNotice()}"))
            }
        }
    }

    /** 指派成功后要刷新的那几条线（与原实现一致：主模型刷 info，辅助刷 aux）。 */
    private fun refreshAfterAssign(assignScope: String) {
        loadInfo()
        if (assignScope == "auxiliary") loadAux()
    }

    private fun saveContextLength() {
        val state = state()
        if (state.busyKey != null) return
        val length = state.contextDraft.trim().toLongOrNull()
        dispatch(ModelSettingsResult.Busy("context"))
        scope.launch {
            try {
                gateway.patchConfig(profile, contextLengthPatch(length))
                dispatch(ModelSettingsResult.Quiet("上下文长度已保存"))
                loadInfo()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(ModelSettingsResult.Failed("保存失败：${throwable.toUserNotice()}"))
            }
        }
    }

    private fun resetAux() {
        if (state().busyKey != null) return
        dispatch(ModelSettingsResult.Busy("aux-reset"))
        scope.launch {
            try {
                gateway.resetAuxiliary(profile)
                dispatch(ModelSettingsResult.Quiet("已把辅助模型全部重置为跟随主模型"))
                loadAux()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(ModelSettingsResult.Failed("重置失败：${throwable.toUserNotice()}"))
            }
        }
    }

    private fun addFallback() {
        val state = state()
        val provider = state.effectiveProvider
        val model = state.effectiveModel
        if (provider.isBlank() || model.isBlank()) {
            dispatch(ModelSettingsResult.Invalid("先在主模型页选好 provider 与模型"))
            return
        }
        val current = state.fallback.valueOrNull.orEmpty()
        if (current.any { it.provider == provider && it.model == model }) {
            dispatch(ModelSettingsResult.Invalid("这条已经在回退链里了"))
            return
        }
        dispatch(ModelSettingsResult.FallbackEdited((current + FallbackEntry(provider, model))))
    }

    private fun removeFallback(index: Int) {
        val current = state().fallback.valueOrNull.orEmpty()
        if (index !in current.indices) return
        dispatch(
            ModelSettingsResult.FallbackEdited(
                current.filterIndexed { i, _ -> i != index },
            ),
        )
    }

    private fun saveFallback() {
        val state = state()
        if (state.busyKey != null) return
        val entries = state.fallback.valueOrNull ?: return
        dispatch(ModelSettingsResult.Busy("fallback"))
        scope.launch {
            try {
                gateway.patchConfig(profile, fallbackPatch(entries, state.fallbackKeepArray))
                Log.info("备用模型已保存：${entries.joinToString { it.label }}")
                dispatch(
                    ModelSettingsResult.Quiet(
                        if (entries.isEmpty()) "已清空回退链" else "备用模型已保存（${entries.size} 条）",
                    ),
                )
                loadFallback()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(ModelSettingsResult.Failed("保存失败：${throwable.toUserNotice()}"))
            }
        }
    }
}
