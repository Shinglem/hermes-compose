package dev.shinglem.hermes.feature.composer

import dev.shinglem.hermes.platform.isSubmittableImagePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 附件必须能随 `prompt.submit` 提交（`.design/待修复问题.md` #19）。
 *
 * 这里只覆盖纯逻辑：`ComposerAttachment` → `PromptAttachment` 的转换、mime 推断、
 * 以及附加阶段的格式闸门。真正的 `attachments` JSON 由 `ChatRepository` 拼装。
 */
class ComposerAttachmentTest {

    private fun attachment(path: String, mime: String = "") = ComposerAttachment(
        id = path,
        label = path.substringAfterLast('/'),
        path = path,
        mime = mime,
    )

    @Test
    fun `带路径的附件能转成提交用的引用`() {
        val converted = attachment("/home/u/.hermes/cache/images/upload_abc", mime = "image/png")
            .toPromptAttachment()
        assertEquals(PromptAttachment("/home/u/.hermes/cache/images/upload_abc", "image/png"), converted)
    }

    @Test
    fun `没有路径的附件不随提交发送`() {
        // 只有显示信息（label/detail）时不能假装发出去 —— 那正是 #19 的静默丢图。
        val onlyLabel = ComposerAttachment(id = "x", label = "photo.png")
        assertNull(onlyLabel.toPromptAttachment())
    }

    @Test
    fun `mime 缺失时按扩展名推断`() {
        assertEquals("image/png", attachment("/tmp/a.png").toPromptAttachment()?.mime)
        assertEquals("image/jpeg", attachment("/tmp/a.jpg").toPromptAttachment()?.mime)
        assertEquals("image/jpeg", attachment("/tmp/a.JPEG").toPromptAttachment()?.mime)
        assertEquals("image/gif", attachment("/tmp/a.gif").toPromptAttachment()?.mime)
        assertEquals("image/webp", attachment("/tmp/a.webp").toPromptAttachment()?.mime)
    }

    @Test
    fun `推断不出 mime 的扩展名给出空串`() {
        // 调用方据此拒绝提交，而不是发一个 admission 一定拒的 mime。
        assertEquals("", mimeOfPath("/tmp/a.bmp"))
        assertEquals("", mimeOfPath("/tmp/a.heic"))
        assertEquals("", mimeOfPath("/tmp/noext"))
    }

    @Test
    fun `只有 admission 接受的四种图片格式能提交`() {
        assertTrue(isSubmittableImagePath("C:/x/a.png"))
        assertTrue(isSubmittableImagePath("C:/x/a.jpg"))
        assertTrue(isSubmittableImagePath("C:/x/a.jpeg"))
        assertTrue(isSubmittableImagePath("C:/x/a.gif"))
        assertTrue(isSubmittableImagePath("C:/x/a.webp"))
        assertTrue(isSubmittableImagePath("C:/x/a.PNG"))

        // 后端 admit_attachments 只收 png/jpeg/gif/webp，bmp 会在提交时 invalid_params。
        assertFalse(isSubmittableImagePath("C:/x/a.bmp"))
        assertFalse(isSubmittableImagePath("C:/x/a.heic"))
        assertFalse(isSubmittableImagePath("C:/x/a.txt"))
    }
}
