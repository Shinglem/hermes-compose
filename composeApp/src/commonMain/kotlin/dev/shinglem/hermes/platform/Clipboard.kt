package dev.shinglem.hermes.platform

/**
 * 系统剪贴板。
 */
expect object Clipboard {
    fun setText(text: String)

    fun getText(): String?

    /** 剪贴板里是否有图片。 */
    fun hasImage(): Boolean

    /**
     * 把剪贴板里的图片存成临时 PNG，返回绝对路径；没有图片时返回 null。
     *
     * 不能只依赖后端 `clipboard.paste`：gateway 可能跑在 **Session 0**（服务会话），
     * 那里是独立剪贴板，永远读不到用户复制的内容。前端在交互会话里读才可靠。
     */
    fun saveImageToTemp(): String?
}