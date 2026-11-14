package dev.shinglem.hermes.platform

import kotlin.coroutines.CoroutineContext

/**
 * UI / 主线程的 [CoroutineContext]。
 *
 * ### 为什么需要它
 *
 * `ChatViewModel` 与 `GatewayClient` 都持有大量**非线程安全**的可变状态
 * （裸 `mutableMapOf`、`StringBuilder`、`var` 计数器），它们靠"全部跑在同一条线程"
 * 这条**隐含契约**做互斥。迁移前那条线程是 `viewModelScope`（desktop 下 =
 * `Dispatchers.Main.immediate` = Swing EDT）。
 *
 * 迁移到 Component 后 scope 由我们自己建，而 `Dispatchers.Swing` 是
 * `kotlinx-coroutines-swing` 的 **JVM 扩展**，`commonMain` 里引用不到。
 * 所以在 `platform/` 开一个 expect/actual 出口 —— 与 `Clipboard` / `AppFocus` 同一套约定。
 *
 * ### 纪律
 *
 * - **凡是持有非线程安全状态的 Component，都用 `Dispatchers.Main` 而不是
 *   `Dispatchers.Default`**。`Default` 是多线程 pool，会把"隐式串行"变成真并发。
 * - 反过来，纯 IO / 无共享状态的工作（例如 settings 各域的 REST 调用）可以用
 *   `Dispatchers.Default`，它们的 State 走 `StateFlow`，本身线程安全。
 */
expect object UiDispatcher {
    val context: CoroutineContext
}
