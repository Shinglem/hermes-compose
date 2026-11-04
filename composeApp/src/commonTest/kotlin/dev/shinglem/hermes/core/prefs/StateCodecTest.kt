package dev.shinglem.hermes.core.prefs

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `state.json` 的编解码与窗口几何的容错。属 S4，见 `05-打包与配置.md` §3.6。 */
class StateCodecTest {

    @Test
    fun `编码后能原样解回`() {
        val values = mapOf(
            PrefKeys.UI_MODE to JsonPrimitive("capabilities"),
            PrefKeys.WINDOW_BOUNDS to JsonPrimitive("10,20,1440,900"),
            "drafts.abc" to JsonPrimitive("草稿正文"),
        )
        assertEquals(values, decodeState(encodeState(values)))
    }

    @Test
    fun `键排序后输出，内容不变则字节不变`() {
        val a = mapOf("b" to JsonPrimitive(1), "a" to JsonPrimitive(2))
        val b = mapOf("a" to JsonPrimitive(2), "b" to JsonPrimitive(1))
        assertEquals(encodeState(a), encodeState(b))
    }

    @Test
    fun `值为 null 的键被丢弃`() {
        val decoded = decodeState("""{"a":"x","b":null}""")
        assertEquals(setOf("a"), decoded.keys)
    }

    @Test
    fun `顶层不是对象时抛错`() {
        assertNotNull(runCatching { decodeState("[1,2,3]") }.exceptionOrNull())
    }

    @Test
    fun `坏 JSON 抛错`() {
        assertTrue(runCatching { decodeState("{ broken") }.isFailure)
    }
}

class WindowBoundsTest {

    @Test
    fun `编解码往返`() {
        val bounds = WindowBounds(10, 20, 1440, 900)
        assertEquals(bounds, WindowBounds.parse(bounds.encode()))
    }

    @Test
    fun `负坐标合法（副屏在主屏左侧）`() {
        assertEquals(WindowBounds(-1920, 0, 1280, 800), WindowBounds.parse("-1920,0,1280,800"))
    }

    @Test
    fun `尺寸为零或负数视为无效`() {
        assertNull(WindowBounds.parse("0,0,0,900"))
        assertNull(WindowBounds.parse("0,0,1440,-1"))
    }

    @Test
    fun `段数不对或含非数字视为无效`() {
        assertNull(WindowBounds.parse(null))
        assertNull(WindowBounds.parse(""))
        assertNull(WindowBounds.parse("1,2,3"))
        assertNull(WindowBounds.parse("1,2,3,4,5"))
        assertNull(WindowBounds.parse("1,2,a,4"))
    }
}
