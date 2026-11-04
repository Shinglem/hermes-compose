package dev.shinglem.hermes.core.time

import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** 会话列表的时间分组（`04-ui-spec.md` §3.1）。 */
enum class SessionGroup { TODAY, YESTERDAY, LAST_7_DAYS, EARLIER }

/** 列表右侧的时间标记；文案由 UI 层按资源渲染，这里只给结构化数据。 */
sealed interface TimeLabel {
    data class Clock(val hour: Int, val minute: Int) : TimeLabel
    data object Yesterday : TimeLabel

    /** 更早：`MM-dd`。 */
    data class Date(val month: Int, val day: Int) : TimeLabel
}

object TimeFormat {
    /** 后端 `started_at` / `timestamp` 是 epoch 秒（float）。 */
    fun fromEpochSeconds(seconds: Double): Instant = Instant.fromEpochSeconds(seconds.toLong())

    fun groupOf(
        startedAt: Instant,
        now: Instant = Clock.System.now(),
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): SessionGroup {
        val days = daysBetween(startedAt, now, zone)
        return when {
            days <= 0 -> SessionGroup.TODAY
            days == 1 -> SessionGroup.YESTERDAY
            days <= 7 -> SessionGroup.LAST_7_DAYS
            else -> SessionGroup.EARLIER
        }
    }

    fun labelOf(
        startedAt: Instant,
        now: Instant = Clock.System.now(),
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): TimeLabel {
        val local = startedAt.toLocalDateTime(zone)
        return when (daysBetween(startedAt, now, zone)) {
            0 -> TimeLabel.Clock(local.hour, local.minute)
            1 -> TimeLabel.Yesterday
            else -> TimeLabel.Date(local.month.ordinal + 1, local.day)
        }
    }

    /** 会话详情用的完整时间：`YYYY-MM-DD HH:mm`。 */
    fun dateTimeText(
        instant: Instant,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val local = instant.toLocalDateTime(zone)
        return buildString {
            append(local.year)
            append('-')
            append((local.month.ordinal + 1).pad2())
            append('-')
            append(local.day.pad2())
            append(' ')
            append(local.hour.pad2())
            append(':')
            append(local.minute.pad2())
        }
    }

    private fun daysBetween(from: Instant, to: Instant, zone: TimeZone): Int {
        val fromDate = from.toLocalDateTime(zone).date
        val toDate = to.toLocalDateTime(zone).date
        return (toDate.toEpochDays() - fromDate.toEpochDays()).toInt()
    }

    private fun Int.pad2(): String = toString().padStart(2, '0')
}
