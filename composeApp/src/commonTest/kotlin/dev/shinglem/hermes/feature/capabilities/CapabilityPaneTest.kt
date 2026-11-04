package dev.shinglem.hermes.feature.capabilities

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.shinglem.hermes.core.contract.CronJobDto
import dev.shinglem.hermes.core.contract.MessagingPlatformDto
import dev.shinglem.hermes.core.contract.PlatformEnvVarDto
import dev.shinglem.hermes.core.contract.SkillDto
import dev.shinglem.hermes.core.contract.ToolsetDto
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.ui.theme.HermesTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CapabilityPaneTest {
    private val skills = listOf(
        SkillDto(name = "postgres", description = "查询数据库", category = "dev", enabled = true, usage = 3),
        SkillDto(name = "weather", description = "查天气", category = "life", enabled = false),
    )

    @Test
    fun `技能页列出技能并显示来源与用量`() = runComposeUiTest {
        val state = CapabilityState(section = CapabilitySection.SKILLS, skills = Loadable.Ready(skills))
        setContent {
            HermesTheme {
                SkillsPane(
                    state = state,
                    onTabChange = {},
                    onQueryChange = {},
                    onToggleSkill = { _, _ -> },
                    onToggleToolset = { _, _ -> },
                    onToggleDetail = {},
                    onRefresh = {},
                )
            }
        }

        onNodeWithText("postgres").assertIsDisplayed()
        onNodeWithText("查询数据库").assertIsDisplayed()
        onNodeWithText("weather").assertIsDisplayed()
        // 第二个开关是关的（第一个是开的）
        onAllNodesWithText("weather").assertCountEquals(1)
    }

    @Test
    fun `切换技能开关回传名称与目标状态`() = runComposeUiTest {
        var toggled: Pair<String, Boolean>? = null
        val state = CapabilityState(
            section = CapabilitySection.SKILLS,
            skills = Loadable.Ready(listOf(skills.first())),
        )
        setContent {
            HermesTheme {
                SkillsPane(
                    state = state,
                    onTabChange = {},
                    onQueryChange = {},
                    onToggleSkill = { name, enabled -> toggled = name to enabled },
                    onToggleToolset = { _, _ -> },
                    onToggleDetail = {},
                    onRefresh = {},
                )
            }
        }

        // 第一个开关是开的 → 点一下应变 false
        onNode(isToggleable()).assertIsOn().performClick()
        assertEquals("postgres" to false, toggled)
    }

    @Test
    fun `搜索框过滤技能列表`() = runComposeUiTest {
        var state by mutableStateOf(
            CapabilityState(section = CapabilitySection.SKILLS, skills = Loadable.Ready(skills)),
        )
        setContent {
            HermesTheme {
                SkillsPane(
                    state = state,
                    onTabChange = {},
                    onQueryChange = { state = state.copy(skillQuery = it) },
                    onToggleSkill = { _, _ -> },
                    onToggleToolset = { _, _ -> },
                    onToggleDetail = {},
                    onRefresh = {},
                )
            }
        }

        onNode(hasSetTextAction()).performTextInput("天气")

        onNodeWithText("weather").assertIsDisplayed()
        assertTrue(onAllNodesWithText("postgres").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `工具集分段显示平台与工具数`() = runComposeUiTest {
        val state = CapabilityState(
            section = CapabilitySection.SKILLS,
            skillsTab = SkillsTab.TOOLSETS,
            toolsets = Loadable.Ready(
                listOf(
                    ToolsetDto(
                        name = "web",
                        label = "Web Search",
                        description = "web_search, web_extract",
                        platformLabel = "CLI",
                        enabled = true,
                        available = true,
                        configured = true,
                        tools = listOf("web_search", "web_extract"),
                    ),
                ),
            ),
        )
        setContent {
            HermesTheme {
                SkillsPane(
                    state = state,
                    onTabChange = {},
                    onQueryChange = {},
                    onToggleSkill = { _, _ -> },
                    onToggleToolset = { _, _ -> },
                    onToggleDetail = {},
                    onRefresh = {},
                )
            }
        }

        onNodeWithText("Web Search").assertIsDisplayed()
        onNodeWithText("CLI").assertIsDisplayed()
        onNodeWithText("2 个工具").assertIsDisplayed()
    }

    @Test
    fun `加载失败且无缓存时显示错误与重试`() = runComposeUiTest {
        var retried = false
        val state = CapabilityState(
            section = CapabilitySection.SKILLS,
            skills = Loadable.Failed(AppError.Timeout("/api/skills")),
        )
        setContent {
            HermesTheme {
                SkillsPane(
                    state = state,
                    onTabChange = {},
                    onQueryChange = {},
                    onToggleSkill = { _, _ -> },
                    onToggleToolset = { _, _ -> },
                    onToggleDetail = {},
                    onRefresh = { retried = true },
                )
            }
        }

        onNodeWithText("加载失败").assertIsDisplayed()
        onNodeWithText("重试").performClick()
        assertTrue(retried)
    }

    @Test
    fun `平台页展示状态与已配置标记`() = runComposeUiTest {
        val state = CapabilityState(
            section = CapabilitySection.PLATFORMS,
            platforms = Loadable.Ready(
                listOf(
                    MessagingPlatformDto(
                        id = "telegram",
                        name = "Telegram",
                        description = "Run Hermes from Telegram DMs",
                        enabled = false,
                        state = "disabled",
                        envVars = listOf(
                            PlatformEnvVarDto(key = "TELEGRAM_BOT_TOKEN", required = true, isPassword = true),
                        ),
                    ),
                    MessagingPlatformDto(id = "api", name = "API server", enabled = true, state = "connected"),
                ),
            ),
        )
        setContent {
            HermesTheme {
                PlatformsPane(
                    state = state,
                    onToggleDetail = {},
                    onFieldChange = { _, _, _ -> },
                    onSave = { _, _ -> },
                    onTest = {},
                    onRefresh = {},
                )
            }
        }

        onNodeWithText("Telegram").assertIsDisplayed()
        onNodeWithText("未启用").assertIsDisplayed()
        onNodeWithText("已连接").assertIsDisplayed()
        // 两个平台各一个开关：第一个（Telegram）未启用
        onAllNodes(isToggleable()).onFirst().assertIsOff()
        onAllNodes(isToggleable()).onLast().assertIsOn()
    }

    @Test
    fun `定时任务页空态给出新建入口`() = runComposeUiTest {
        var created = false
        val state = CapabilityState(
            section = CapabilitySection.CRON,
            cronJobs = Loadable.Ready(emptyList()),
        )
        setContent {
            HermesTheme {
                CronPane(
                    state = state,
                    onCreate = { created = true },
                    onEdit = {},
                    onStartBlueprint = {},
                    onBlueprintValue = { _, _ -> },
                    onSaveBlueprint = { _, _ -> },
                    onCancelBlueprint = {},
                    onCancelDraft = {},
                    onDraftChange = {},
                    onSaveDraft = {},
                    onSetPaused = { _, _ -> },
                    onTrigger = {},
                    onDelete = {},
                    onToggleRuns = {},
                    onRefresh = {},
                )
            }
        }

        onNodeWithText("还没有定时任务").assertIsDisplayed()
        onNodeWithText("新建任务").performClick()
        assertTrue(created)
    }

    @Test
    fun `任务行显示暂停标记与投递计划`() = runComposeUiTest {
        val state = CapabilityState(
            section = CapabilitySection.CRON,
            cronJobs = Loadable.Ready(
                listOf(
                    CronJobDto(
                        id = "j1",
                        name = "早报",
                        prompt = "汇总今天的日程",
                        scheduleDisplay = "每天 08:00",
                        deliver = "local",
                        enabled = false,
                        state = "paused",
                    ),
                ),
            ),
        )
        setContent {
            HermesTheme {
                CronPane(
                    state = state,
                    onCreate = {},
                    onEdit = {},
                    onStartBlueprint = {},
                    onBlueprintValue = { _, _ -> },
                    onSaveBlueprint = { _, _ -> },
                    onCancelBlueprint = {},
                    onCancelDraft = {},
                    onDraftChange = {},
                    onSaveDraft = {},
                    onSetPaused = { _, _ -> },
                    onTrigger = {},
                    onDelete = {},
                    onToggleRuns = {},
                    onRefresh = {},
                )
            }
        }

        onNodeWithText("早报").assertIsDisplayed()
        onNodeWithText("已暂停").assertIsDisplayed()
        onNodeWithText("每天 08:00").assertIsDisplayed()
        onNodeWithText("立即运行").assertIsDisplayed()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals(expected: Int) {
    assertEquals(expected, fetchSemanticsNodes().size)
}
