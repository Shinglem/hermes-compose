package dev.shinglem.hermes.core.prefs

/**
 * 窗口几何：`x,y,width,height`（Dp 取整）。
 *
 * 存成**一个**字符串而不是四个键：`Settings` 没有批量原子写，四个键要落盘四次，
 * 还可能读到"只更新了一半"的状态（位置变了尺寸没变）。
 */
data class WindowBounds(val x: Int, val y: Int, val width: Int, val height: Int) {

    fun encode(): String = "$x,$y,$width,$height"

    companion object {
        /** 解析失败一律返回 null —— 窗口位置丢了就用配置里的默认尺寸，不值得报错。 */
        fun parse(raw: String?): WindowBounds? {
            val parts = raw?.split(',') ?: return null
            if (parts.size != 4) return null
            val numbers = parts.map { it.trim().toIntOrNull() ?: return null }
            val (x, y, width, height) = numbers
            if (width <= 0 || height <= 0) return null
            return WindowBounds(x, y, width, height)
        }
    }
}
