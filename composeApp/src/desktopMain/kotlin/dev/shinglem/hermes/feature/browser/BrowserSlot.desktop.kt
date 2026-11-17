package dev.shinglem.hermes.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.browser.Win32WindowQuery
import dev.nucleusframework.window.tao.NativeView
import dev.nucleusframework.window.tao.nucleusHwndPlatformView

/**
 * `BrowserSlot` 的桌面实现：`NativeView` + `nucleusHwndPlatformView`（ADR-082）。
 *
 * 嵌的是**另一个进程**的顶层窗口 —— 实测通过（跨进程嵌入 / DComp overlay 压住工具栏 /
 * resize 跟随 / 销毁不影响外部进程，见 ADR-082 的 A1–A4）。
 *
 * ### 三条纪律
 *
 * 1. `key(hwnd)` 包一层：句柄变了必须重建，否则嵌的是旧窗口；
 * 2. **不调任何 kill / terminate**：那只浏览器是 agent 的，面板关掉它要继续跑；
 * 3. 窗口已失效（`IsWindow == false`）时画占位，不硬挂 —— `nucleusHwndPlatformView`
 * 的 factory 必须返回非空，硬挂一个死句柄会得到一块黑区。
 */
@Composable
actual fun BrowserSlot(
    hwnd: Long,
    modifier: Modifier,
    empty: Boolean,
) {
    if (empty || hwnd == 0L || !Win32WindowQuery.isWindow(hwnd)) {
        SlotPlaceholder(modifier)
        return
    }

    key(hwnd) {
        NativeView(
            factory = { nucleusHwndPlatformView(handle = { hwnd }) },
            modifier = modifier,
            // 覆盖层留空：本版不在浏览器上叠 Compose（抽屉的工具栏在槽位外）。
            // 撑满的覆盖层会吃掉浏览器的鼠标事件。
        )
    }
}

@Composable
private fun SlotPlaceholder(modifier: Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "浏览器画面不可用",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp),
        )
    }
}
