package dev.shinglem.hermes.component.settings.store

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import dev.shinglem.hermes.component.settings.SecurityEffect
import dev.shinglem.hermes.component.settings.SecurityIntent
import dev.shinglem.hermes.component.settings.SecurityResult
import dev.shinglem.hermes.component.settings.SecurityState
import dev.shinglem.hermes.core.error.asAppError
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import dev.shinglem.hermes.feature.settings.ConfigFieldSpec
import dev.shinglem.hermes.component.settings.SecurityConfigGateway
import dev.shinglem.hermes.feature.settings.buildConfigPatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * 安全页的 IO 边界：唯一做 suspend / 取消的地方。
 *
 * 与迁移前的 `SecurityViewModel` 相比少了两类样板：
 * - `loaded` 布尔 → [SecurityIntent.Appear] 的幂等判断（`config` 仍是 `Idle` 才读）；
 * - `viewModelScope.launch` + `try/catch(CancellationException){throw}` →
 *   `execute { }` + 只写必要的那两个 catch（取消必须原样重抛，见 `13 篇` §3.2）。
 *
 * [profile] 用 `var` 而不是构造参数：profile 切换时**不重建 Store**（重建会丢 edits），
 * 只换目标并重读 —— 与 Decompose"切走不销毁"的语义一致。
 */
internal class SecurityExecutor(
    private val repository: SecurityConfigGateway,
    private val profile: String?,
    executorContext: CoroutineContext,
) : CoroutineExecutor<SecurityIntent, Nothing, SecurityState, SecurityResult, SecurityEffect>(executorContext) {

    private var loadJob: Job? = null

    override fun executeIntent(intent: SecurityIntent) {
        when (intent) {
            is SecurityIntent.Appear -> {
                // 后端没就绪时什么都不做：迁移前那句注释写得很清楚
                // ——"local 后端 ready 之前一定失败"。等下一次已连接的 Appear。
                if (intent.connected) loadIfNeeded()
            }

            SecurityIntent.Reload -> load()

            is SecurityIntent.Edit -> dispatch(SecurityResult.Edited(intent.path, intent.text))

            is SecurityIntent.Save -> save(intent.specs)

            SecurityIntent.DismissBanner -> dispatch(SecurityResult.BannersCleared)
        }
    }

    /**
     * 只在「还没有数据」时读。
     *
     * 判断依据是 `state.profile`，不是一个额外的 `loaded` 布尔 —— 后者是迁移前每个域都要
     * 手写一份的样板，也是"切了 profile 但忘了重置"这个 bug 的来源。现在 profile 是构造参数，
     * 这个比较只用来兜底"Store 复用了但 profile 变了"（理论上不会发生，成本为零）。
     */
    private fun loadIfNeeded() {
        val state = state()
        if (state.profile == profile && state.config !is Loadable.Idle) return
        load()
    }

    private fun load() {
        // 并发刷新只保留最后一次：连点「重新读取」不该排出 N 个请求。
        loadJob?.cancel()
        val stale = state().config.valueOrNull
        dispatch(SecurityResult.Loading(stale))
        loadJob = scope.launch {
            try {
                dispatch(SecurityResult.Loaded(repository.read(profile), profile))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(SecurityResult.Failed(throwable.asAppError(), stale))
            }
        }
    }

    private fun save(specs: List<ConfigFieldSpec>) {
        val current = state()
        if (current.busyKey != null || current.edits.isEmpty()) return
        val patch = buildConfigPatch(current.edits, specs)
        if (patch.isEmpty()) {
            dispatch(SecurityResult.NothingToSave)
            return
        }
        dispatch(SecurityResult.Saving)
        scope.launch {
            try {
                repository.patch(profile, patch)
                // 回读：后端的归一化视图会补默认值，只有回读才看到真值。
                dispatch(SecurityResult.EditsCleared(repository.read(profile)))
                publish(SecurityEffect.Toast("已保存，对后续对话生效"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(SecurityResult.SaveFailed(throwable.asAppError()))
            }
        }
    }
}
