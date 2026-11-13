package dev.shinglem.hermes.component

import com.arkivanov.mvikotlin.core.rx.Disposable
import com.arkivanov.mvikotlin.core.rx.Observer
import com.arkivanov.mvikotlin.core.store.Store
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * MVIKotlin `Store` → 面向 UI 的 `StateFlow<S>` 适配。
 *
 * 为什么需要这一层：
 * - MVIKotlin 的 `Store` 用自家 `Disposable` + `Observer` 回调，**不是** Flow。
 *   Component 的对外契约按项目约定是 `StateFlow<State>` + `Flow<Label>`（`13 篇` §3.1），
 *   所以 `store.states(...)` 必须包一层。
 * - `Store.state` 是同步快照，直接读会漏掉两次 recomposition 之间的中间值；
 *   必须订阅才能在 UI 上看到流式增量。
 *
 * 订阅在构造时建立，随 Store 一起 `dispose()`（Store 的生命周期由 Component 的
 * `instanceKeeper` 管）。不额外维护"订阅开关"—— 那会在重进页面时引入漏值。
 */
internal class StoreStateSource<S : Any>(
    private val store: Store<*, S, *>,
) {
    private val _state = MutableStateFlow(store.state)

    val state: StateFlow<S> = _state.asStateFlow()

    init {
        store.states(
            object : Observer<S> {
                override fun onNext(value: S) {
                    _state.value = value
                }

                override fun onComplete() = Unit
            },
        )
    }
}

/**
 * Store 的 Label 流：一次性事件（滚动到底部、弹提示）。
 *
 * 用 `Channel` 而不是 `SharedFlow`：Label **不该重放** —— 重进页面时补发一遍
 * "滚动到底部"会直接把用户弹回底部，补发"已保存"会闪一个不该出现的提示。
 * `Channel` 天然只被消费一次。
 */
internal fun <L : Any> Store<*, *, L>.labelsAsFlow(): Flow<L> {
    val channel = Channel<L>(Channel.BUFFERED)
    labels(
        object : Observer<L> {
            override fun onNext(value: L) {
                channel.trySend(value)
            }

            override fun onComplete() {
                channel.close()
            }
        },
    )
    return channel.receiveAsFlow()
}

/** 供 Component 释放时取消订阅。 */
internal fun Disposable.disposeQuietly() {
    runCatching { dispose() }
}
