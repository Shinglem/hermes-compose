package dev.shinglem.hermes.feature.session

import dev.shinglem.hermes.core.time.SessionGroup
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionRowsTest {
    private val zone = TimeZone.of("Asia/Shanghai")
    private val now = LocalDateTime(2026, 10, 26, 15, 0).toInstant(zone)

    private fun session(
        id: String,
        title: String,
        at: LocalDateTime,
        preview: String = "",
    ) = SessionSummary(
        id = id,
        title = title,
        preview = preview,
        startedAt = at.toInstant(zone),
        messageCount = 3,
        source = "desktop",
    )

    @Test
    fun `按今天 昨天 7 天内 更早分组并保持组内倒序`() {
        val sessions = listOf(
            session("a", "今天早上", LocalDateTime(2026, 10, 26, 9, 0)),
            session("b", "今天中午", LocalDateTime(2026, 10, 26, 12, 0)),
            session("c", "昨天", LocalDateTime(2026, 10, 25, 20, 0)),
            session("d", "三天前", LocalDateTime(2026, 10, 23, 10, 0)),
            session("e", "上个月", LocalDateTime(2026, 9, 1, 8, 0)),
        )

        val rows = buildSessionRows(sessions, query = "", collapsedGroups = emptySet(), now = now)

        val headers = rows.filterIsInstance<SessionRow.Header>().map { it.group }
        assertEquals(
            listOf(
                SessionGroup.TODAY,
                SessionGroup.YESTERDAY,
                SessionGroup.LAST_7_DAYS,
                SessionGroup.EARLIER,
            ),
            headers,
        )

        val todayItems = rows
            .dropWhile { it !is SessionRow.Header || it.group != SessionGroup.TODAY }
            .drop(1)
            .takeWhile { it is SessionRow.Item }
            .map { (it as SessionRow.Item).session.id }
        assertEquals(listOf("b", "a"), todayItems)
    }

    @Test
    fun `折叠分组只保留标题行`() {
        val sessions = listOf(
            session("a", "今天", LocalDateTime(2026, 10, 26, 9, 0)),
            session("c", "昨天", LocalDateTime(2026, 10, 25, 20, 0)),
        )
        val rows = buildSessionRows(sessions, "", setOf(SessionGroup.TODAY), now)

        val todayHeader = rows.first()
        assertIs<SessionRow.Header>(todayHeader)
        assertTrue(todayHeader.collapsed)
        assertEquals(listOf("c"), rows.filterIsInstance<SessionRow.Item>().map { it.session.id })
    }

    @Test
    fun `搜索同时匹配标题与预览`() {
        val sessions = listOf(
            session("a", "部署脚本", LocalDateTime(2026, 10, 26, 9, 0), preview = "关于 nginx"),
            session("b", "别的话题", LocalDateTime(2026, 10, 26, 10, 0), preview = "nginx 配置"),
        )
        val byTitle = buildSessionRows(sessions, "部署", emptySet(), now)
        assertEquals(listOf("a"), byTitle.filterIsInstance<SessionRow.Item>().map { it.session.id })

        val byPreview = buildSessionRows(sessions, "NGINX", emptySet(), now)
        assertEquals(listOf("b", "a"), byPreview.filterIsInstance<SessionRow.Item>().map { it.session.id })
    }

    @Test
    fun `没有标题时回落到 preview 再回落到 id`() {
        val blank = SessionSummary(
            id = "2026_x",
            title = "",
            preview = "第一句话",
            startedAt = Instant.fromEpochSeconds(0),
            messageCount = 0,
            source = "desktop",
        )
        assertEquals("第一句话", blank.displayTitle)
        assertEquals("2026_x", blank.copy(preview = "").displayTitle)
    }

    @Test
    fun `置顶的会话单独成组并排在最前`() {
        val sessions = listOf(
            session("a", "今天", LocalDateTime(2026, 10, 26, 9, 0)),
            session("old", "上个月", LocalDateTime(2026, 9, 1, 8, 0)),
            session("b", "今天稍晚", LocalDateTime(2026, 10, 26, 12, 0)),
        )

        val rows = buildSessionRows(
            sessions,
            query = "",
            collapsedGroups = emptySet(),
            now = now,
            pinned = setOf("old"),
        )

        val first = rows.first()
        assertIs<SessionRow.Header>(first)
        assertEquals(null, first.group)
        assertEquals(
            listOf("old", "b", "a"),
            rows.filterIsInstance<SessionRow.Item>().map { it.session.id },
        )
        // 置顶项从时间分组里移除，不再出现第二次
        assertEquals(1, rows.count { it is SessionRow.Item && it.session.id == "old" })
    }

    @Test
    fun `置顶分组折叠后仍保留标题行`() {
        val sessions = listOf(session("a", "今天", LocalDateTime(2026, 10, 26, 9, 0)))

        val rows = buildSessionRows(
            sessions,
            query = "",
            collapsedGroups = emptySet(),
            now = now,
            pinned = setOf("a"),
            pinnedCollapsed = true,
        )

        val header = rows.first()
        assertIs<SessionRow.Header>(header)
        assertEquals(null, header.group)
        assertTrue(header.collapsed)
        assertTrue(rows.none { it is SessionRow.Item })
    }
}
