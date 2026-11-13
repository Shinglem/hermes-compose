package dev.shinglem.hermes.component.settings.store

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import dev.shinglem.hermes.component.settings.SecurityEffect
import dev.shinglem.hermes.component.settings.SecurityIntent
import dev.shinglem.hermes.component.settings.SecurityResult
import dev.shinglem.hermes.component.settings.SecurityState
import dev.shinglem.hermes.component.settings.SecurityConfigGateway
import kotlin.coroutines.CoroutineContext

/**
 * 造一个安全页的 Store。
 *
 * **[profile] 是构造参数，不是可变字段** —— profile 变化时由 Component 重建 Store。
 * 这与迁移前的行为等价（`setProfile` 原本就是 `loaded = false` + `_state = SecurityState()`，
 * 即彻底重置），而且省掉了"忘记重置"这类 bug：不可能出现"拿着 A 的 edits 往 B 写"。
 *
 * 直接返回 `Store<SecurityIntent, SecurityState, SecurityEffect>`，不套 `XxxStore` 包装类 ——
 * 迁移前的 `XxxViewModel` 主要就是给 `state` 取个别名，那层现在由
 * [dev.shinglem.hermes.component.StoreStateSource] 承担。
 *
 * [factory] 做成参数：测试用 `TestStoreFactory`（记录每个 Intent / Result 以便断言），
 * 生产用 `DefaultStoreFactory`。
 */
internal object SecurityStoreFactory {

    fun create(
        repository: SecurityConfigGateway,
        profile: String?,
        executorContext: CoroutineContext,
        reducer: Reducer<SecurityState, SecurityResult> = SecurityReducer(),
        factory: StoreFactory = DefaultStoreFactory(),
    ): Store<SecurityIntent, SecurityState, SecurityEffect> =
        factory.create(
            initialState = SecurityState(),
            bootstrapper = null,
            executorFactory = { SecurityExecutor(repository, profile, executorContext) },
            reducer = reducer,
        )
}
