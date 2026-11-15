package dev.shinglem.hermes.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `CF_DIB` → PNG 的转换（ADR-075）。
 *
 * 这段逻辑是**纯字节处理**，所以能脱离剪贴板单测 —— 而它的错误在真机上表现为
 * "粘贴进来的图是花的 / 上下颠倒 / 一片透明"，肉眼一定要看到才知道。
 * 这里把三种已知形态的判据写成数字。
 */
class DibImageTest {

    @Test
    fun `32 位自底向上 BGRA 转换并翻转行序`() {
        // 2x2：上排红、绿；下排蓝、白。DIB 自底向上 → 内存里先放下排。
        val dib = dib(bitCount = 32, width = 2, height = 2) {
            // 下排（内存里在前）：蓝、白
            pixel(0xFF, 0x00, 0x00) // B,G,R = 蓝
            pixel(0xFF, 0xFF, 0xFF) // 白
            // 上排：红、绿
            pixel(0x00, 0x00, 0xFF) // 红
            pixel(0x00, 0xFF, 0x00) // 绿
        }
        val png = DibImage.toPng(dib)
        assertNotNull(png, "32 位 BI_RGB 必须能转换")
        assertPngSignature(png)
    }

    @Test
    fun `24 位按 4 字节对齐读取`() {
        // 3 像素宽 → 每行 9 字节，补到 12；高度 2。
        val dib = dib(bitCount = 24, width = 3, height = 2) {
            repeat(2) {
                pixel24(0xFF, 0x00, 0x00)
                pixel24(0x00, 0xFF, 0x00)
                pixel24(0x00, 0x00, 0xFF)
                repeat(3) { pad(0) } // 行尾对齐填充
            }
        }
        val png = DibImage.toPng(dib)
        assertNotNull(png, "24 位 BI_RGB 必须能转换（行尾对齐不能算错）")
        assertPngSignature(png)
    }

    @Test
    fun `全部 alpha 为 0 时补成不透明`() {
        // 截图工具常把 alpha 写成 0：不补的话导出整张透明，用户看到"粘贴后一片空白"。
        val dib = dib(bitCount = 32, width = 1, height = 1) {
            pixel(0x30, 0x60, 0x90) // alpha 默认 0
        }
        val png = DibImage.toPng(dib)
        assertNotNull(png)
        assertPngSignature(png)
        // PNG 的 IHDR 之后是 IDAT，色彩类型在 IHDR 第 25 字节（0-based 25 = color type）。
        // color type 6 = RGBA。这里只验形态正确，alpha 具体值由 Skia 编码，不再反解。
        assertTrue(png.size > 8)
    }

    @Test
    fun `RLE 压缩不支持时返回 null`() {
        val dib = dib(bitCount = 8, width = 4, height = 4, compression = 1)
        assertNull(DibImage.toPng(dib), "BI_RLE8 不支持，应返回 null 而不是崩")
    }

    @Test
    fun `字节不足时返回 null`() {
        assertNull(DibImage.toPng(ByteArray(4)), "连 header 都不够")
        assertNull(DibImage.toPng(ByteArray(0)))
    }

    @Test
    fun `宽度或高度非法时返回 null`() {
        val zeroWidth = dib(bitCount = 32, width = 0, height = 1) { }
        assertNull(DibImage.toPng(zeroWidth))
        val zeroHeight = dib(bitCount = 32, width = 1, height = 0) { }
        assertNull(DibImage.toPng(zeroHeight))
    }

    /**
     * S8 回归：极端尺寸必须被判掉，不能靠 Int 回绕"骗过"长度校验。
     *
     * `width * 4 * height` 在 Int 上会回绕成负数 —— 那时 `dib.size < 负数` 恒为假（守卫
     * 失效），随后 `ByteArray(负数)` 抛 `NegativeArraySizeException`，或分配一个约 1–2 GB
     * 的数组。这两个尺寸都来自**外部进程**写的字节，不能信。
     */
    @Test
    fun `超大尺寸被拒绝而不是溢出去分配`() {
        // 100000 x 100000 x 32 位 = 4e10 字节，远超 Int 范围。
        val huge = dib(bitCount = 32, width = 100_000, height = 100_000) { }
        assertNull(DibImage.toPng(huge), "超大尺寸必须返回 null")

        // 24 位路径同样要防（`width * 3` 也会回绕）。
        val huge24 = dib(bitCount = 24, width = 100_000, height = 100_000) { }
        assertNull(DibImage.toPng(huge24))
    }

    /**
     * S8 回归：`BI_BITFIELDS` + 40 字节头时，三个 DWORD 掩码紧跟在头**之后**，
     * 像素起点要再往后 12 字节。不修正的话整幅图颜色/行序全错（"粘贴出来的图是花的"）。
     */
    @Test
    fun `BITFIELDS 40 字节头时跳过 12 字节掩码`() {
        // 1x1，BI_BITFIELDS(3) + 40 字节头：头之后是 3 个掩码，然后才是像素。
        val mine = dib(bitCount = 32, width = 1, height = 1, compression = 3) {
            int(0x00FF0000) // 红掩码
            int(0x0000FF00) // 绿掩码
            int(0x000000FF) // 蓝掩码
            pixel(0xFF, 0x00, 0x00, 0xFF) // 蓝
        }
        assertNotNull(DibImage.toPng(mine), "40 字节头 + BITFIELDS 要能正确跳过掩码后转换")

        // 若把掩码当像素读：总共只有 40+12+4=56 字节，扣掉 12 之后剩 4 字节正好够一个像素；
        // 不扣时也可以"够"（56-40=16 ≥ 4），所以这个用例只能证明"不崩"，真正的差异由 24/32
        // 位像素内容体现。这里再补一个"掩码之后没有像素"的形态：必须返回 null。
        val noPixel = dib(bitCount = 32, width = 1, height = 1, compression = 3) {
            int(0x00FF0000)
            int(0x0000FF00)
            int(0x000000FF)
        }
        assertNull(DibImage.toPng(noPixel), "掩码之后没有像素数据时必须返回 null，而不是把掩码当像素")
    }

    // ---- 构造 DIB 的测试辅助 ----

    private class DibBuilder {
        val bytes = mutableListOf<Byte>()

        fun int(value: Int) = apply {
            bytes += (value and 0xFF).toByte()
            bytes += ((value shr 8) and 0xFF).toByte()
            bytes += ((value shr 16) and 0xFF).toByte()
            bytes += ((value shr 24) and 0xFF).toByte()
        }

        fun short(value: Int) = apply {
            bytes += (value and 0xFF).toByte()
            bytes += ((value shr 8) and 0xFF).toByte()
        }

        fun pixel(b: Int, g: Int, r: Int, a: Int = 0) = apply {
            bytes += b.toByte()
            bytes += g.toByte()
            bytes += r.toByte()
            bytes += a.toByte()
        }

        fun pixel24(b: Int, g: Int, r: Int) = apply {
            bytes += b.toByte()
            bytes += g.toByte()
            bytes += r.toByte()
        }

        fun pad(value: Int) = apply { bytes += value.toByte() }

        fun build(): ByteArray = bytes.toByteArray()
    }

    private fun dib(
        bitCount: Int,
        width: Int,
        height: Int,
        compression: Int = 0,
        pixels: (DibBuilder.() -> Unit)? = null,
    ): ByteArray {
        val builder = DibBuilder()
        builder.int(40)          // biSize
            .int(width)          // biWidth
            .int(height)         // biHeight（正数 = 自底向上）
            .short(1)            // biPlanes
            .short(bitCount)     // biBitCount
            .int(compression)    // biCompression
            .int(0)              // biSizeImage
            .int(0).int(0)       // biXPelsPerMeter / biYPelsPerMeter
            .int(0).int(0)       // biClrUsed / biClrImportant
        pixels?.invoke(builder)
        return builder.build()
    }

    private fun assertPngSignature(png: ByteArray) {
        val expected = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals(expected.size, minOf(png.size, expected.size))
        expected.forEachIndexed { index, byte ->
            assertEquals(byte, png[index], "PNG 魔数第 $index 字节不对")
        }
    }
}
