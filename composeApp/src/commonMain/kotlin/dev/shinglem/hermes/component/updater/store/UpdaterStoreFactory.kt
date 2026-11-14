package dev.shinglem.hermes.component.updater.store

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import dev.shinglem.hermes.component.updater.UpdaterGateway
import dev.shinglem.hermes.component.updater.UpdaterIntent
import dev.shinglem.hermes.component.updater.UpdaterResult
import dev.shinglem.hermes.component.updater.UpdaterState
import kotlin.coroutines.CoroutineContext

/**
 * 造一个检查更新的 Store。
 *
 * 没有 Effect（Label）：这一页只有"状态"，没有"一次性事件"（不需要弹 toast /
 * 跳页面）。MVIKotlin 允许 `Nothing` 作为 Label 类型，Component 侧
 * 用 `emptyFlow()` 填充对外的 `effects`。
 *
 * [factory] 做成参数：测试用 `TestStoreFactory`（记录 Intent/Result 以便断言），
 * 生产用 `DefaultStoreFactory`。
 */
internal object UpdaterStoreFactory {

    fun create(
        gateway: UpdaterGateway,
        executorContext: CoroutineContext,
        reducer: Reducer<UpdaterState, UpdaterResult> = UpdaterReducer(),
        factory: StoreFactory = DefaultStoreFactory(),
    ): Store<UpdaterIntent, UpdaterState, Nothing> =
        factory.create(
            initialState = UpdaterState(),
            bootstrapper = null,
            executorFactory = { UpdaterExecutor(gateway, executorContext) },
            reducer = reducer,
        )
}