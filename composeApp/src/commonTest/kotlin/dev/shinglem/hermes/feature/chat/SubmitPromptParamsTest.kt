package dev.shinglem.hermes.feature.chat

import dev.shinglem.hermes.feature.composer.PromptAttachment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `prompt.submit` 的 wire 形状（`.design/待修复问题.md` #19）。
 *
 * 后端的闭集里有 `attachments: [{path, mime}]`，admission 会逐项校验；
 * 我们此前从不发这个键，所以图片永远到不了模型。
 */
class SubmitPromptParamsTest {

    private fun params(attachments: List<PromptAttachment>) = ChatRepository.submitPromptParams(
        profile = "butler",
        runtimeSessionId = "runtime-1",
        text = "看看这张图",
        attachments = attachments,
        submissionId = "sid-1",
    )

    @Test
    fun `没有附件时不出现 attachments 键`() {
        val json = params(emptyList())
        assertFalse("attachments" in json, "空列表不该发这个键（闭集保持最小）")
        // 三个必需键照旧
        assertEquals("runtime-1", json["session_id"]!!.jsonPrimitive.content)
        assertEquals("butler", json["profile"]!!.jsonPrimitive.content)
        assertEquals("sid-1", json["submission_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `有附件时按 path 与 mime 序列化`() {
        val json = params(
            listOf(
                PromptAttachment("/p/cache/images/upload_a", "image/png"),
                PromptAttachment("/p/cache/images/upload_b", "image/jpeg"),
            ),
        )
        val array = json["attachments"] as JsonArray
        assertEquals(2, array.size)

        val first = array[0].jsonObject
        assertEquals("/p/cache/images/upload_a", first["path"]!!.jsonPrimitive.content)
        assertEquals("image/png", first["mime"]!!.jsonPrimitive.content)
        // 只带这两个键，别把客户端内部字段漏出去
        assertEquals(setOf("path", "mime"), first.keys)

        val second = array[1].jsonObject
        assertEquals("/p/cache/images/upload_b", second["path"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", second["mime"]!!.jsonPrimitive.content)
    }

    @Test
    fun `profile 为空时不出现该键`() {
        val json = ChatRepository.submitPromptParams(
            profile = null,
            runtimeSessionId = "r",
            text = "hi",
            attachments = emptyList(),
            submissionId = "s",
        )
        assertNull(json["profile"])
        assertTrue("profile" !in json)
    }
}
