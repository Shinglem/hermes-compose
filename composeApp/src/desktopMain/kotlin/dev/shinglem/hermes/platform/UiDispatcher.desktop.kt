package dev.shinglem.hermes.platform

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * desktop 侧的 UI 线程 = `Dispatchers.Main`。
 *
 * ### 为什么是 `Main` 而不是 `Dispatchers.Swing`（实测推翻了早期判断）
 *
 * S6 把窗口壳换成 Nucleus Tao 之后，`Dispatchers.Main` 的提供者变了：Nucleus 通过
 * `META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory` 注册了
 * `TaoMainDispatcherFactory`（loadPriority = 100），而 `kotlinx-coroutines-swing` 的
 * `SwingDispatcherFactory` 是 0 —— **优先级更高的赢**。所以：
 *
 * | dispatcher | Tao 进程里的实际线程 |
 * |---|---|
 * | `Dispatchers.Main` / `.Main.immediate` | `main`（Tao 主线程，Compose 组合也在这里） |
 * | `Dispatchers.Swing` | `AWT-EventQueue-0`（`kotlinx-coroutines-swing` 自己拉起的 EDT） |
 *
 * 两者**不是同一条线程**（`taoSwingProbe` 实测，见 `build/swing-probe.txt`）。
 *
 * 因此 `Dispatchers.Swing` 不能当 UI 线程用：Compose 的重组、UI 事件回调（`onIntent`）
 * 全在 `main` 上，而 Swing EDT 是另一条 —— 用它等于把"UI 线程"和"状态线程"拆开。
 *
 * ### 启动期的行为（也是实测的）
 *
 * `DesktopContainer.build()` 跑在 `nucleusApplication` **之前**。此时向 `Dispatchers.Main`
 * 立即派发的任务会落到 `Nucleus-Tao-Main-Fallback`（单线程池）。但 **Tao 起来之后**
 * 同一个 scope 派发/恢复的任务都会回到 `main`（`taoSwingProbe` 场景 B/C/E 验证）。
 * 也就是说：启动期只有"同步立即派发"那一小段走 fallback，不会长期分叉。
 * 详见 `commonMain/platform/UiDispatcher.kt` 的纪律说明。
 */
actual object UiDispatcher {
    actual val context: CoroutineContext = Dispatchers.Main
}
