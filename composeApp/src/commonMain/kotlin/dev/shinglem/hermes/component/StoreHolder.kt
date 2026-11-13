package dev.shinglem.hermes.component

import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.mvikotlin.core.store.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext

/**
 * 把 MVIKotlin `Store` 装进 `InstanceKeeper`。
 *
 * 为什么需要这一层包装（**不能直接 `getOrCreate { store }`**）：
 * `InstanceKeeper.getOrCreate<T>` 的 `T` 必须实现
 * `InstanceKeeper.Instance`（它要调 `onDestroy()` 做清理），而 `Store` 只是
 * `isDisposed()` + `dispose()`。直接放进去编译器会报
 * `TYPE_INTERSECTION_AS_REIFIED_ERROR`（推断成 `InstanceKeeper.Instance & Store<…>` 的交集）。
 *
 * 顺带解决第二件事：**Store 自己的 `CoroutineScope` 要有归属**。Executor 里
 * `execute { }` 的协程跑在这个 scope 上，Component 销毁时必须取消它，
 * 否则在途请求会在 Store `dispose()` 之后才回来并 `dispatch` 到已销毁的 Store。
 *
 * 所以：一个 holder = 一个 Store + 一个相伴的 scope，`onDestroy` 里一并收掉。
 * 所有域的 `DefaultXxxComponent` 都用这个类，不要各自发明。
 */
internal class StoreHolder<S : Any, I : Any, L : Any>(
    val store: Store<I, S, L>,
    /** Executor 用的上下文；`onDestroy` 时取消它。 */
    executorContext: CoroutineContext = CoroutineScope(SupervisorJob()).coroutineContext,
) : InstanceKeeper.Instance {

    private val scope = CoroutineScope(executorContext)

    val state: StateFlow<S> = StoreStateSource(store).state

    val labels: Flow<L> = store.labelsAsFlow()

    fun dispatch(intent: I) = store.accept(intent)

    init {
        // DefaultStore 必须在 accept 之前 init()：它在这里挂上 executor，
        // 未 init 时 accept 会被静默丢弃（表现为"点了没反应"，极难查）。
        store.init()
    }

    override fun onDestroy() {
        scope.cancel()
        store.dispose()
    }
}
