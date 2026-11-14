package dev.shinglem.hermes.platform

import kotlin.coroutines.CoroutineContext

/**
 * UI / 主线程的 [CoroutineContext]。
 *
 * ### 为什么需要它
 *
 * `ChatViewModel` 与 `GatewayClient` 都持有大量**非线程安全**的可变状态
 * （裸 `mutableMapOf`、`StringBuilder`、`var` 计数器），它们靠"全部跑在同一条线程"
 * 这条**隐含契约**做互斥。迁移到 Component 后 scope 由我们自己建，所以要有一个
 * 显式的、跨平台可引用的出口 —— 与 `Clipboard` / `AppFocus` 同一套约定。
 *
 * ### 纪律
 *
 * - **凡是持有非线程安全状态的 Component，都用 [UiDispatcher.context] 而不是
 *   `Dispatchers.Default`**。`Default` 是多线程 pool，会把"隐式串行"变成真并发。
 * - **不要用 `Dispatchers.Swing`**。S6 迁 Nucleus Tao 之后它**不是** UI 线程：
 *   Tao 通过 `TaoMainDispatcherFactory`（loadPriority 100 > swing 的 0）接管了
 *   `Dispatchers.Main`，`Dispatchers.Main` 才是 `main`（Compose 组合所在），
 *   而 `Dispatchers.Swing` 是 `kotlinx-coroutines-swing` 另起的 `AWT-EventQueue-0`。
 *   实测见 desktop 侧 actual 的注释与 `build/swing-probe.txt`。
 * - 反过来，纯 IO / 无共享状态的工作（例如 settings 各域的 REST 调用）可以用
 *   `Dispatchers.Default`，它们的 State 走 `StateFlow`，本身线程安全。
 */
expect object UiDispatcher {
    val context: CoroutineContext
}
