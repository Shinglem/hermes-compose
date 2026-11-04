package dev.shinglem.hermes.core.app

/**
 * 用户可见的产品名。
 *
 * 只用于**界面文案**：窗口标题、托盘提示、系统通知、关于页。
 * 可执行文件名（`hermes-desktop-compose.exe`）、数据目录（`hermes-compose`）、
 * 自启任务前缀（`HermesCompose-`）都是**内部标识**，改动会让既有安装对不上，一律不跟随这里。
 *
 * `strings.xml` 的 `app_name` 需要与 [DISPLAY_NAME] 保持一致（compose resources 里没法引用 Kotlin 常量）。
 */
object ProductInfo {
    const val DISPLAY_NAME: String = "Hermes Desktop Compose"
}
