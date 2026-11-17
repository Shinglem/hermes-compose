package dev.shinglem.hermes.component.browser.store

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import dev.shinglem.hermes.component.browser.BrowserEffect
import dev.shinglem.hermes.component.browser.BrowserGateway
import dev.shinglem.hermes.component.browser.BrowserIntent
import dev.shinglem.hermes.component.browser.BrowserResult
import dev.shinglem.hermes.component.browser.BrowserState
import kotlin.coroutines.CoroutineContext

/**
 * 造一个浏览器侧栏的 Store。
 *
 * [factory] 做成参数：测试用 `TestStoreFactory`（记录 Intent/Result 以便断言），
 * 生产用 `DefaultStoreFactory`。
 */
internal object BrowserStoreFactory {

    fun create(
        gateway: BrowserGateway,
        executorContext: CoroutineContext,
        reducer: Reducer<BrowserState, BrowserResult> = BrowserReducer(),
        factory: StoreFactory = DefaultStoreFactory(),
    ): Store<BrowserIntent, BrowserState, BrowserEffect> =
        factory.create(
            initialState = BrowserState(),
            bootstrapper = null,
            executorFactory = { BrowserExecutor(gateway, executorContext) },
            reducer = reducer,
        )
}
