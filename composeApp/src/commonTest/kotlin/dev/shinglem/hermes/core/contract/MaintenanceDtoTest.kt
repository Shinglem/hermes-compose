package dev.shinglem.hermes.core.contract

import dev.shinglem.hermes.core.transport.RpcJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 维护面 DTO 与后端真实返回的对账。
 *
 * 样本来自 hermes 0.23.0 本机 payload 的实际响应（`GET /api/memory`、`/api/credentials/pool`、
 * `/api/sessions/stats`、`/api/analytics/usage`、`/api/actions/doctor/status`、`/api/curator`）。
 * `ignoreUnknownKeys = true` 会把拼错的字段名**静默**变成默认值，页面就显示 0，
 * 所以这里逐个钉住关键字段。
 */
class MaintenanceDtoTest {

    private fun <T> decode(serializer: DeserializationStrategy<T>, json: String): T =
        RpcJson.json.decodeFromJsonElement(serializer, RpcJson.json.parseToJsonElement(json))

    @Test
    fun memoryStatusKeepsFilesAndProviderFlags() {
        val dto = decode(
            MemoryStatusDto.serializer(),
            """
            {"active":"","providers":[
              {"name":"byterover","description":"ByteRover","available":false,
               "configured":true,"status":"unavailable",
               "setup":{"pip_dependencies":[],"python_dependencies_declared":false,
                        "external_dependencies":[{"name":"brv",
                          "install":"curl -fsSL https://byterover.dev/install.sh | sh",
                          "check":"brv --version"}],
                        "required_env":[],"dependencies_installed":false}},
              {"name":"retaindb","description":"RetainDB","available":false,
               "configured":false,"status":"needs_config",
               "setup":{"pip_dependencies":[],"python_dependencies_declared":false,
                        "external_dependencies":[],
                        "required_env":["RETAINDB_API_KEY"],"dependencies_installed":true}}
            ],"builtin_files":{"memory":4096,"user":0}}
            """.trimIndent(),
        )
        assertEquals("", dto.active)
        assertEquals(listOf("byterover", "retaindb"), dto.providers.map { it.name })
        assertEquals(4096L, dto.builtinFiles["memory"])
        assertEquals(0L, dto.builtinFiles["user"])

        val byterover = dto.providers.first()
        assertEquals("unavailable", byterover.status)
        assertFalse(byterover.ready)
        assertFalse(byterover.setup.dependenciesInstalled)
        assertEquals("brv", byterover.setup.externalDependencies.single().name)
        assertEquals("brv --version", byterover.setup.externalDependencies.single().check)

        val retaindb = dto.providers.last()
        assertEquals("needs_config", retaindb.status)
        assertTrue(retaindb.setup.dependenciesInstalled)
        assertEquals(listOf("RETAINDB_API_KEY"), retaindb.setup.requiredEnv)
    }

    @Test
    fun credentialPoolKeepsRedactedEntries() {
        val dto = decode(
            CredentialPoolDto.serializer(),
            """
            {"providers":[{"provider":"deepseek","entries":[
              {"index":1,"id":"486665","label":"DEEPSEEK_API_KEY","auth_type":"api_key",
               "source":"env:DEEPSEEK_API_KEY","priority":0,"last_status":"ok",
               "request_count":7,"token_preview":"sk-7...5433","has_refresh":false}
            ]}]}
            """.trimIndent(),
        )
        val entry = dto.providers.single().entries.single()
        assertEquals("deepseek", dto.providers.single().provider)
        assertEquals(1, entry.index)
        assertEquals("sk-7...5433", entry.tokenPreview)
        assertEquals(7, entry.requestCount)
        assertFalse(entry.hasRefresh)
        assertEquals("ok", entry.lastStatus)
    }

    @Test
    fun sessionStatsAndEmptyCount() {
        val stats = decode(
            SessionStatsDto.serializer(),
            """{"total":89,"active_store":88,"archived":1,"messages":14432,"by_source":{"desktop":38,"tui":10}}""",
        )
        assertEquals(89L, stats.total)
        assertEquals(88L, stats.activeStore)
        assertEquals(1L, stats.archived)
        assertEquals(14432L, stats.messages)
        assertEquals(38L, stats.bySource["desktop"])

        assertEquals(7L, decode(SessionEmptyCountDto.serializer(), """{"count":7}""").count)
    }

    @Test
    fun usageAnalyticsKeepsDailyAndTotals() {
        val dto = decode(
            UsageAnalyticsDto.serializer(),
            """
            {"daily":[{"day":"2026-11-01","input_tokens":829311,"output_tokens":337863,
                       "cache_read_tokens":105937920,"reasoning_tokens":170273,
                       "estimated_cost":0.6449,"actual_cost":0,"sessions":3,"api_calls":554}],
             "totals":{"total_input":100,"total_output":20,"total_cache_read":5,"total_reasoning":1,
                       "total_estimated_cost":0.5,"total_actual_cost":0,"total_sessions":4,"total_api_calls":9},
             "by_model":[{"model":"deepseek-chat","input_tokens":80,"output_tokens":10,
                          "estimated_cost":0.3,"sessions":2,"api_calls":6}],
             "period_days":7,"skills":{},"tools":{}}
            """.trimIndent(),
        )
        assertEquals(7, dto.periodDays)
        assertEquals(100L, dto.totals.totalInput)
        assertEquals(4L, dto.totals.totalSessions)
        assertEquals("2026-11-01", dto.daily.single().day)
        assertEquals(829311L, dto.daily.single().inputTokens)
        assertEquals("deepseek-chat", dto.byModel.single().model)
        assertEquals(0.3, dto.byModel.single().estimatedCost)
    }

    @Test
    fun actionStatusKeepsTailAndExitCode() {
        val running = decode(
            ActionStatusDto.serializer(),
            """{"name":"doctor","running":true,"exit_code":null,"pid":5860,"lines":["a","b"]}""",
        )
        assertTrue(running.running)
        assertEquals(null, running.exitCode)
        assertEquals(listOf("a", "b"), running.lines)

        val finished = decode(
            ActionStatusDto.serializer(),
            """{"name":"doctor","running":false,"exit_code":2,"pid":5860,"lines":[]}""",
        )
        assertEquals(2, finished.exitCode)
    }

    @Test
    fun curatorStatusKeepsIsoTimestamp() {
        val dto = decode(
            CuratorStatusDto.serializer(),
            """
            {"enabled":true,"paused":false,"interval_hours":168,
             "last_run_at":"2026-11-02T10:28:55.248120+00:00",
             "min_idle_hours":2.0,"stale_after_days":14,"archive_after_days":30}
            """.trimIndent(),
        )
        assertTrue(dto.enabled)
        assertFalse(dto.paused)
        assertEquals(168.0, dto.intervalHours)
        assertEquals("2026-11-02T10:28:55.248120+00:00", dto.lastRunAt)
    }

    @Test
    fun checkpointsSumBytes() {
        val dto = decode(
            OpsCheckpointsDto.serializer(),
            """{"sessions":[{"session":"abc","files":3,"bytes":2048}],"total_bytes":2048}""",
        )
        assertEquals(2048L, dto.totalBytes)
        assertEquals(3, dto.sessions.single().files)
    }
}
