package dev.shinglem.hermes.shell.spike

import dev.shinglem.hermes.platform.Clipboard
import java.io.File
import kotlin.system.exitProcess

/**
 * 验收探针：生产 `Clipboard` 实现（ADR-075）。
 *
 * ⚠️ 顺序很重要：`setText` 内部会 `EmptyClipboard()`，**先把图验完再写文本**，
 * 否则会把待验的图片清掉（第一版就踩了这个坑，得到假的 "hasImage=false"）。
 *
 * 用法：
 *   `./gradlew :composeApp:putDibToClipboard` → 放一张真实 CF_DIB
 *   `./gradlew :composeApp:clipboardAcceptance` → 验收
 */
fun main() {
    val out = File("build/clipboard-acceptance.txt")
    out.parentFile?.mkdirs()
    val lines = mutableListOf<String>()

    fun record(text: String) {
        lines += text
        synchronized(lines) { out.writeText(lines.joinToString("\n")) }
    }

    record("=== Clipboard acceptance (production impl) ===")

    // ---- 1) 图片优先（写文本会清空剪贴板）----
    val hasImage = Clipboard.hasImage()
    record("[hasImage] $hasImage")
    if (hasImage) {
        val started = System.nanoTime()
        val path = Clipboard.saveImageToTemp()
        val ms = (System.nanoTime() - started) / 1_000_000
        if (path == null) {
            record("[saveImageToTemp] null（有图但转换失败）")
        } else {
            val bytes = File(path).readBytes()
            val header = bytes.take(8).joinToString(",") { (it.toInt() and 0xFF).toString(16) }
            val isPng = header == "89,50,4e,47,d,a,1a,a"
            record("[saveImageToTemp] bytes=${bytes.size} elapsed=${ms}ms pngHeader=$header isPng=$isPng")
        }
    } else {
        record("[saveImageToTemp] skipped（剪贴板里没有图片）")
    }

    // ---- 2) 文本 ----
    val existing = Clipboard.getText()
    record("[getText] len=${existing?.length} value=${existing?.take(60)}")
    val sample = "生产 Clipboard 写入验证-中文-${System.currentTimeMillis()}"
    Clipboard.setText(sample)
    record("[setText->getText] same=${Clipboard.getText() == sample}")

    record("=== done ===")
    out.writeText(lines.joinToString("\n"))
    exitProcess(0)
}