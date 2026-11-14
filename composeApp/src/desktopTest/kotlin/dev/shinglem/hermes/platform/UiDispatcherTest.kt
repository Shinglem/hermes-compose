package dev.shinglem.hermes.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * 钉住 [UiDispatcher] 的取值 —— 这是 S6 迁 Nucleus Tao 之后最容易悄悄写错的一行。
 *
 * 背景（实测，见 `taoSwingProbe` 与 `build/swing-probe.txt`）：Tao 通过
 * `META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory` 注册了
 * `TaoMainDispatcherFactory`（loadPriority 100），压过 `kotlinx-coroutines-swing`
 * 的 `SwingDispatcherFactory`（0）。所以 Tao 进程里：
 *
 * - `Dispatchers.Main` → `main`（Compose 组合、UI 事件回调都在这里）
 * - `Dispatchers.Swing` → `AWT-EventQueue-0`（**另一条**线程）
 *
 * 一旦有人把 [UiDispatcher.context] 改回 `Dispatchers.Swing`，`DesktopContainer` 的
 * scope 就会与各个 ViewModel 的 `viewModelScope` 分居两条线程，而它们共享大量
 * 无同步的裸 map —— 这个断言就是为了让那次改动在 CI 里立刻失败。
 */
class UiDispatcherTest {

    @Test
    fun `UI dispatcher 必须是 Dispatchers Main`() {
        assertSame(
            Dispatchers.Main,
            UiDispatcher.context,
            "Tao 迁移后 Dispatchers.Main 才是 UI 线程；Dispatchers.Swing 是另一条 EDT",
        )
    }

    @Test
    fun `UI dispatcher 不能是 Swing`() {
        assertNotSame(
            Dispatchers.Swing,
            UiDispatcher.context,
            "Swing EDT 与 Tao 的 main 是两条线程，用它会让 UI 回调与状态变更跨线程竞争",
        )
    }
}
