package dev.shinglem.hermes.feature.browser

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 把一只**外部进程**的顶层窗口嵌进来。
 *
 * `commonMain` 不认识 `HWND`，所以这里只暴露"给我一个窗口标识"的槽位；真正的
 * `NativeView` + `nucleusHwndPlatformView` 在 `desktopMain`（ADR-082）。
 *
 * ### 实现方必须遵守
 *
 * 1. `key(hwnd) { … }` 包一层 —— 句柄变了必须重建，否则嵌的是旧窗口；
 * 2. **不拥有进程**：销毁槽位不得调用任何 kill / terminate（窗口是 agent 的）；
 * 3. 嵌不进去（UIPI / 非 Windows）时不要崩，画一个说明块即可。
 *
 * `empty` 为 true 时只画占位（供预览 / 非桌面平台编译期使用）。
 */
@Composable
expect fun BrowserSlot(
    hwnd: Long,
    modifier: Modifier,
    empty: Boolean = false,
)
