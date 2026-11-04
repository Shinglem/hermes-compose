package dev.shinglem.hermes.platform

import java.awt.Window

actual object AppFocus {
    actual val isForeground: Boolean
        get() = runCatching {
            Window.getWindows().any { window -> window.isVisible && window.isFocused }
        }.getOrDefault(true)
}
