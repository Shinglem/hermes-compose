package dev.shinglem.hermes.platform

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * Windows `CF_DIB` / `CF_DIBV5` → PNG。
 *
 * ### 为什么需要它
 *
 * `CF_DIB` 是 Windows 的"与设备无关位图"（`BITMAPINFO` + 像素数组），不是文件格式：
 * 没有文件头、行序可能自底向上、行宽按 4 字节对齐、24 位没有 alpha。要交给外面的
 * 世界（后端 `image.attach_bytes` 只收 PNG/JPEG/WebP/GIF）必须先转成 PNG。
 *
 * ### 为什么用 Skia 编码而不是 `ImageIO`
 *
 * `javax.imageio.ImageIO` 属于 `java.desktop` —— 用它等于没脱离 AWT 模块。
 * Skiko（Skia）本来就是 Compose Desktop 的传递依赖，已在 classpath 上，
 * `Image.encodeToData(EncodedImageFormat.PNG)` 是完整的 PNG 编码器。
 *
 * ### 支持的形态（其余返回 null，由调用方退化成"没有图片"）
 *
 * | 位深 | 压缩 | 说明 |
 * |---|---|---|
 * | 32 | `BI_RGB`(0) | 像素 BGRA；alpha 字节常被截图工具写成 0，这里按需置 255 |
 * | 24 | `BI_RGB`(0) | 像素 BGR，行尾 4 字节对齐，补 alpha=255 |
 * | 32 | `BI_BITFIELDS`(3) | 掩码按标准 BGRA 处理（Windows 截图工具的实际输出） |
 *
 * `BI_RLE8` / `BI_RLE4` / 索引色不支持 —— 它们只出现在很老的软件里，
 * 而且解析复杂度与收益不成比例。
 */
internal object DibImage {

    /** `BITMAPINFOHEADER` 固定部分长度：biSize(4) + 宽高(8) + planes/bitcount(4) + 压缩等(16)。 */
    private const val BITMAPINFOHEADER_SIZE = 40

    private const val BI_RGB = 0
    private const val BI_BITFIELDS = 3

    /**
     * @param dib `CF_DIB` / `CF_DIBV5` 的完整字节（从 `biSize` 开始）
     * @return PNG 字节；不支持的形态返回 `null`
     */
    fun toPng(dib: ByteArray): ByteArray? {
        if (dib.size < BITMAPINFOHEADER_SIZE) return null

        val headerSize = readInt(dib, 0)
        if (headerSize < BITMAPINFOHEADER_SIZE || headerSize > dib.size) return null
        val width = readInt(dib, 4)
        val rawHeight = readInt(dib, 8)
        val bitCount = readShort(dib, 14)
        val compression = readInt(dib, 16)
        // biSizeImage 可能是 0（BI_RGB 时允许），所以不用它算长度。
        if (width <= 0 || rawHeight == 0) return null
        if (compression != BI_RGB && compression != BI_BITFIELDS) return null

        val height = kotlin.math.abs(rawHeight)
        val bottomUp = rawHeight > 0

        // `CF_DIBV5` 的 header 更长，像素起点跟着 headerSize 走；BITFIELDS 的掩码已在 header 内。
        val dataOffset = headerSize
        val rgba = when (bitCount) {
            32 -> toRgba32(dib, dataOffset, width, height, bottomUp)
            24 -> toRgba24(dib, dataOffset, width, height, bottomUp)
            else -> null
        } ?: return null

        return encodePng(width, height, rgba)
    }

    private fun toRgba32(
        dib: ByteArray,
        offset: Int,
        width: Int,
        height: Int,
        bottomUp: Boolean,
    ): ByteArray? {
        val stride = width * 4
        if (dib.size < offset + stride * height) return null
        val out = ByteArray(stride * height)
        var anyAlpha = false
        for (y in 0 until height) {
            val srcY = if (bottomUp) height - 1 - y else y
            val srcRow = offset + srcY * stride
            val dstRow = y * stride
            for (x in 0 until width) {
                val s = srcRow + x * 4
                val d = dstRow + x * 4
                // DIB 是 BGRA
                out[d] = dib[s + 2]
                out[d + 1] = dib[s + 1]
                out[d + 2] = dib[s]
                out[d + 3] = dib[s + 3]
                if (dib[s + 3] != 0.toByte()) anyAlpha = true
            }
        }
        // 截图工具常把 alpha 全写成 0。全 0 说明不是真的想表达透明，补成不透明，
        // 否则导出的 PNG 会整张透明（用户看到的是"粘贴后一片空白"）。
        if (!anyAlpha) {
            for (i in 3 until out.size step 4) out[i] = 0xFF.toByte()
        }
        return out
    }

    private fun toRgba24(
        dib: ByteArray,
        offset: Int,
        width: Int,
        height: Int,
        bottomUp: Boolean,
    ): ByteArray? {
        // 行按 4 字节对齐：每行占 ((width*3 + 3) / 4) * 4 字节。
        val stride = ((width * 3 + 3) / 4) * 4
        if (dib.size < offset + stride * height) return null
        val outStride = width * 4
        val out = ByteArray(outStride * height)
        for (y in 0 until height) {
            val srcY = if (bottomUp) height - 1 - y else y
            val srcRow = offset + srcY * stride
            val dstRow = y * outStride
            for (x in 0 until width) {
                val s = srcRow + x * 3
                val d = dstRow + x * 4
                out[d] = dib[s + 2] // R
                out[d + 1] = dib[s + 1] // G
                out[d + 2] = dib[s] // B
                out[d + 3] = 0xFF.toByte()
            }
        }
        return out
    }

    private fun encodePng(width: Int, height: Int, rgba: ByteArray): ByteArray? = runCatching {
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        val bitmap = Bitmap()
        try {
            if (!bitmap.installPixels(info, rgba, width * 4)) return null
            val image = Image.makeFromBitmap(bitmap)
            image.encodeToData(EncodedImageFormat.PNG)?.bytes
        } finally {
            bitmap.close()
        }
    }.getOrNull()

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
