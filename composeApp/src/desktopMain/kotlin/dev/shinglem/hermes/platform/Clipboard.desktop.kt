package dev.shinglem.hermes.platform

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempFile

actual object Clipboard {
    actual fun setText(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard
            .setContents(StringSelection(text), null)
    }

    actual fun getText(): String? = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) return null
        clipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()

    actual fun hasImage(): Boolean = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)
    }.getOrDefault(false)

    actual fun saveImageToTemp(): String? = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null
        val image = clipboard.getData(DataFlavor.imageFlavor) as? java.awt.Image ?: return null
        val width = image.getWidth(null)
        val height = image.getHeight(null)
        if (width <= 0 || height <= 0) return null
        // AWT 的 Image 可能是延迟绘制的，先落到 BufferedImage 再编码。
        val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = buffered.createGraphics()
        graphics.drawImage(image, 0, 0, null)
        graphics.dispose()
        val file = createTempFile("hermes-clip-", ".png").toFile()
        if (!ImageIO.write(buffered, "png", file)) {
            file.delete()
            return null
        }
        file.absolutePath
    }.getOrNull()
}