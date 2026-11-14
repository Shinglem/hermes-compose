package dev.shinglem.hermes.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlin.coroutines.CoroutineContext

/**
 * desktop 侧的 UI 线程 = Swing EDT。
 *
 * `Dispatchers.Swing` 由 `kotlinx-coroutines-swing` 提供（`build.gradle.kts` 已加）。
 * 它与 `viewModelScope` 在 desktop 下用的 `Dispatchers.Main.immediate` 是**同一个**
 * EDT —— 这正是 `ChatViewModel` / `GatewayClient` 迁移前依赖的那条线程，
 * 换成 `Dispatchers.Default` 会让它们持有的裸 map / StringBuilder 真正并发。
 * 详见 `commonMain/platform/UiDispatcher.kt` 的注释。
 */
actual object UiDispatcher {
    actual val context: CoroutineContext = Dispatchers.Swing
}
