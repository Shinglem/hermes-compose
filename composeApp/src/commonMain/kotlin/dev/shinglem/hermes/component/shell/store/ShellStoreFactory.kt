package dev.shinglem.hermes.component.shell.store

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import dev.shinglem.hermes.component.shell.ShellGateway
import dev.shinglem.hermes.component.shell.ShellIntent
import dev.shinglem.hermes.component.shell.ShellResult
import dev.shinglem.hermes.component.shell.ShellState
import kotlin.coroutines.CoroutineContext

/** 造一个 Shell 的 Store。没有 Effect（这一层不发一次性事件）。 */
internal object ShellStoreFactory {

    fun create(
        gateway: ShellGateway,
        executorContext: CoroutineContext,
        initial: ShellState,
        reducer: Reducer<ShellState, ShellResult> = ShellReducer(),
        factory: StoreFactory = DefaultStoreFactory(),
    ): Store<ShellIntent, ShellState, Nothing> =
        factory.create(
            initialState = initial,
            bootstrapper = null,
            executorFactory = { ShellExecutor(gateway, executorContext) },
            reducer = reducer,
        )
}