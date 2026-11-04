package dev.shinglem.hermes.feature.capabilities

import dev.shinglem.hermes.core.contract.CronJobDto
import dev.shinglem.hermes.core.contract.SkillDto
import dev.shinglem.hermes.core.contract.ToolsetDto
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.transport.encodeQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilityLogicTest {
    @Test
    fun `cron 草稿校验 5 段且只允许 cron 字符`() {
        assertTrue(CronDraft(schedule = "0 9 * * *", prompt = "x").scheduleLooksValid)
        assertTrue(CronDraft(schedule = "*/15 8-18 * * 1-5", prompt = "x").scheduleLooksValid)
        assertFalse(CronDraft(schedule = "9 * * *", prompt = "x").scheduleLooksValid)
        assertFalse(CronDraft(schedule = "0 9 * * * *", prompt = "x").scheduleLooksValid)
        assertFalse(CronDraft(schedule = "0 9 * * MON", prompt = "x").scheduleLooksValid)
        assertFalse(CronDraft(schedule = "", prompt = "x").scheduleLooksValid)
    }

    @Test
    fun `草稿必须同时有 schedule 与 prompt 才能保存`() {
        assertTrue(CronDraft(schedule = "0 9 * * *", prompt = "跑一下").canSave)
        assertFalse(CronDraft(schedule = "0 9 * * *", prompt = " ").canSave)
        assertFalse(CronDraft(schedule = "  ", prompt = "跑一下").canSave)
    }

    @Test
    fun `技能搜索匹配名称 说明与分类且忽略大小写`() {
        val skills = listOf(
            SkillDto(name = "postgres", description = "查询数据库", category = "dev"),
            SkillDto(name = "weather", description = "查天气", category = "life"),
            SkillDto(name = "pdf", description = "PDF 处理", category = "Dev"),
        )
        assertEquals(3, filterSkills(skills, "").size)
        assertEquals(listOf("postgres", "pdf"), filterSkills(skills, "dev").map { it.name })
        assertEquals(listOf("weather"), filterSkills(skills, "天气").map { it.name })
        assertEquals(listOf("pdf"), filterSkills(skills, "PDF").map { it.name })
        assertTrue(filterSkills(skills, "不存在的关键词").isEmpty())
    }

    @Test
    fun `工具集搜索匹配展示名 说明与平台`() {
        val toolsets = listOf(
            ToolsetDto(name = "web", label = "Web Search", platformLabel = "CLI"),
            ToolsetDto(name = "discord", label = "Discord", platformLabel = "Discord"),
        )
        assertEquals(listOf("web"), filterToolsets(toolsets, "search").map { it.name })
        assertEquals(listOf("discord"), filterToolsets(toolsets, "discord").map { it.name })
        assertEquals(2, filterToolsets(toolsets, "").size)
    }

    @Test
    fun `查询串百分号编码保留 unreserved 并转义其余`() {
        assertEquals("default", encodeQuery("default"))
        assertEquals("a%20b", encodeQuery("a b"))
        assertEquals("%E4%B8%AD%E6%96%87", encodeQuery("中文"))
        assertEquals("a%2Fb", encodeQuery("a/b"))
        assertEquals("a%26b%3Dc", encodeQuery("a&b=c"))
    }

    @Test
    fun `任务暂停状态以 enabled 为准并兼容 state`() {
        assertTrue(CronJobDto(enabled = false).isPaused)
        assertTrue(CronJobDto(enabled = true, state = "paused").isPaused)
        assertFalse(CronJobDto(enabled = true, state = "running").isPaused)
    }

    @Test
    fun `过滤在搜索结果里统计已启用数量`() {
        val state = CapabilityState(
            section = CapabilitySection.SKILLS,
            skills = Loadable.Ready(
                listOf(
                    SkillDto(name = "a", enabled = true),
                    SkillDto(name = "b", enabled = false),
                ),
            ),
        )
        assertEquals(2, state.filteredSkills().size)
        assertEquals(1, state.filteredSkills().count { it.enabled })
    }

    @Test
    fun `平台草稿随输入累积 清空即移除该键`() {
        val empty = CapabilityState.initial()
        assertTrue(empty.platformDrafts.isEmpty())
    }
}
