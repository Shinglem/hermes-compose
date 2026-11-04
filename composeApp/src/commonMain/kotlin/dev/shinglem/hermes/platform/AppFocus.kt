package dev.shinglem.hermes.platform

/**
 * 主窗口是否在前台。
 *
 * 通知只在"用户没看着"的时候发：正盯着界面时再弹一条系统提示是噪音。
 * common 侧拿不到窗口焦点，只能由平台层回答。
 */
expect object AppFocus {
    val isForeground: Boolean
}
