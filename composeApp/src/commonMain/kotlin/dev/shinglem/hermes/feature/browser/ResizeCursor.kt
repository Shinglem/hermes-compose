package dev.shinglem.hermes.feature.browser

import androidx.compose.ui.Modifier

/**
 * 在进入时把鼠标指针换成「水平调整大小」样式（`↔`）的修饰符。
 *
 * ### 为什么需要 expect/actual
 *
 * Compose Desktop 的 `PointerIcon` 只能由 `java.awt.Cursor` 构造
 * （`PointerIcon(Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR))`），
 * 而本项目业务代码**不 import `java.awt`**（ADR-075）。
 *
 * 但 `java.desktop` 本来就在 jlink 模块清单里（Compose Desktop 自己就要它），
 * 所以用 AWT Cursor **不增加运行时成本**，只是不该把 import 散落在业务代码里。
 * 这一层把那个 import 收进 `desktopMain` 的单点。
 *
 * 非桌面平台返回原 [Modifier]（没有鼠标，不需要）。
 */
expect fun Modifier.resizeCursor(): Modifier
