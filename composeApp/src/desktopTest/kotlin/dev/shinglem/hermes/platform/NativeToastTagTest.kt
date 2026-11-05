package dev.shinglem.hermes.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 原生 toast 的 tag 生成。
 *
 * 这里是 v0.4.1 的缺陷根因：`tag` 曾是常量，而 WinRT 把「同 tag + 同 group」
 * 当成对**同一条**通知的更新，于是连续两个审批只有最后一个还在（且前一个的按钮回调被抹掉）。
 */
class NativeToastTagTest {

    @Test
    fun `逐条递增时 tag 各不相同`() {
        val tags: List<String> = (1L..200L).map { seq -> nativeToastTag(seq) }
        assertEquals(tags.size, tags.toSet().size, "同一次运行内 tag 必须唯一")
    }

    @Test
    fun `tag 不超过 WinRT 的 16 字符上限`() {
        // 极端值也要在限制内，否则 WindowsNotificationCenter 会拒绝或截断
        listOf(1L, 255L, 65_535L, Int.MAX_VALUE.toLong(), Long.MAX_VALUE, -1L).forEach { seq ->
            val tag = nativeToastTag(seq)
            assertTrue(tag.length <= 16, "tag '$tag' 长度 ${tag.length} 超过 16")
            assertTrue(tag.isNotEmpty())
        }
    }

    @Test
    fun `相邻序号不会因为取模而撞车`() {
        // 取模只在序号超过 2^52 时才可能重复，正常会话不可能达到；
        // 这里确认常见区间是严格不同的。
        for (seq in listOf(1L, 2L, 4096L, 1_000_000L)) {
            assertNotEquals(nativeToastTag(seq), nativeToastTag(seq + 1))
        }
    }
}
