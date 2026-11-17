package dev.shinglem.hermes.feature.browser

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import java.awt.Cursor

/**
 * 桌面实现：鼠标悬停在拖拽手柄上时显示 `↔`。
 *
 * `PointerIcon(Cursor)` 是 Compose Desktop 提供 AWT 互操作的唯一入口 ——
 * 这里集中这一处 `java.awt` import，业务代码不直接碰（ADR-075 的纪律）。
 *
 * `java.desktop` 本就在 jlink 模块清单里（Compose Desktop 自身依赖），
 * 所以不增加运行时成本；`Cursor.getPredefinedCursor` 走的是 JDK 内置光标表，没有额外资源。
 */
actual fun Modifier.resizeCursor(): Modifier = pointerHoverIcon(RESIZE_ICON)

private val RESIZE_ICON: PointerIcon by lazy {
    // `W_RESIZE_CURSOR` = 西向（←→），是水平拖宽最贴切的系统光标。
    PointerIcon(Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR))
}
