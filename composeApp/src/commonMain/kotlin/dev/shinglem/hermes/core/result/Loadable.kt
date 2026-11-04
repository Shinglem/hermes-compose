package dev.shinglem.hermes.core.result

import dev.shinglem.hermes.core.error.AppError

/** 统一装载态。刷新失败但已有缓存时用 `Failed(stale = 旧值)`，UI 保留旧数据 + 顶部横幅。 */
sealed interface Loadable<out T> {
    data object Idle : Loadable<Nothing>
    data object Loading : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
    data class Failed<out T>(val error: AppError, val stale: T? = null) : Loadable<T>
}

/** 已就绪的值；`Failed` 带回缓存时也返回缓存。 */
val <T> Loadable<T>.valueOrNull: T?
    get() = when (this) {
        is Loadable.Ready -> value
        is Loadable.Failed -> stale
        else -> null
    }
