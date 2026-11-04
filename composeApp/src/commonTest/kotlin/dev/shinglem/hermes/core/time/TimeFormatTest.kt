package dev.shinglem.hermes.core.time

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TimeFormatTest {
    private val zone = TimeZone.of("Asia/Shanghai")
    private val now = LocalDateTime(2026, 10, 26, 15, 30).toInstant(zone)

    @Test
    fun `同一自然日归到 TODAY`() {
        val morning = LocalDateTime(2026, 10, 26, 8, 0).toInstant(zone)
        assertEquals(SessionGroup.TODAY, TimeFormat.groupOf(morning, now, zone))
        assertIs<TimeLabel.Clock>(TimeFormat.labelOf(morning, now, zone))
    }

    @Test
    fun `前一天归到 YESTERDAY`() {
        val yesterday = LocalDateTime(2026, 10, 25, 23, 59).toInstant(zone)
        assertEquals(SessionGroup.YESTERDAY, TimeFormat.groupOf(yesterday, now, zone))
        assertEquals(TimeLabel.Yesterday, TimeFormat.labelOf(yesterday, now, zone))
    }

    @Test
    fun `一周内归到 LAST_7_DAYS`() {
        val sixDaysAgo = LocalDateTime(2026, 10, 20, 10, 0).toInstant(zone)
        assertEquals(SessionGroup.LAST_7_DAYS, TimeFormat.groupOf(sixDaysAgo, now, zone))
        assertEquals(TimeLabel.Date(10, 20), TimeFormat.labelOf(sixDaysAgo, now, zone))
    }

    @Test
    fun `超过一周归到 EARLIER`() {
        val old = LocalDateTime(2026, 9, 1, 10, 0).toInstant(zone)
        assertEquals(SessionGroup.EARLIER, TimeFormat.groupOf(old, now, zone))
    }

    @Test
    fun `epoch 秒转换为本地时间文本`() {
        val instant = LocalDateTime(2026, 3, 4, 9, 5).toInstant(zone)
        assertEquals(
            "2026-03-04 09:05",
            TimeFormat.dateTimeText(instant, zone),
        )
        assertEquals(instant, TimeFormat.fromEpochSeconds(instant.epochSeconds.toDouble()))
    }
}
