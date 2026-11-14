package dev.shinglem.hermes.component.updater.store

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import dev.shinglem.hermes.component.updater.UpdaterGateway
import dev.shinglem.hermes.component.updater.UpdaterIntent
import dev.shinglem.hermes.component.updater.UpdaterResult
import dev.shinglem.hermes.component.updater.UpdaterState
import dev.shinglem.hermes.core.error.asAppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * 检查更新的**执行器**：唯一做 IO 的地方。
 *
 * 下游依赖收窄成 [UpdaterGateway] 而不是 `RestClient` —— 这样 Reducer/Executor 可以
 * 在纯 JVM 测试里跑（不用起 HTTP）。这是 S2 定下的模板，7 个域一致。
 */
internal class UpdaterExecutor(
    private val gateway: UpdaterGateway,
    executorContext: CoroutineContext,
) : CoroutineExecutor<UpdaterIntent, Nothing, UpdaterState, UpdaterResult, Nothing>(executorContext) {

    override fun executeIntent(intent: UpdaterIntent) {
        when (intent) {
            is UpdaterIntent.Check -> check(intent.force)
            UpdaterIntent.DismissError -> dispatch(UpdaterResult.Cleared)
        }
    }

    private fun check(force: Boolean) {
        // 已经在查时直接忽略：迁移前用 `if (_state.value.checking) return` 防重入，
        // 这里读 store 的当前状态（Reducer 已经把 checking 置 true）。
        if (state().checking) return
        dispatch(UpdaterResult.Started(force))
        scope.launch {
            try {
                dispatch(UpdaterResult.Loaded(gateway.checkBackendUpdate(force)))
            } catch (cancelled: CancellationException) {
                // 取消必须原样重抛，否则协程的取消语义会被吞掉（13 篇 §3.2）。
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(UpdaterResult.Failed(throwable.asAppError("hermes.update.check")))
            }
        }
    }
}