package dev.shinglem.hermes.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileDropTest {
    @Test
    fun `按扩展名判定图片且忽略大小写`() {
        assertTrue(isImagePath("C:\\shots\\a.PNG"))
        assertTrue(isImagePath("/tmp/x.jpeg"))
        assertTrue(isImagePath("d.webp"))
        assertFalse(isImagePath("C:\\notes\\a.txt"))
        assertFalse(isImagePath("noext"))
        assertFalse(isImagePath("a."))
    }
}