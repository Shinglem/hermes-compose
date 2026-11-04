package dev.shinglem.hermes.feature.capabilities

import dev.shinglem.hermes.core.contract.BlueprintDto
import dev.shinglem.hermes.core.contract.CronJobDto
import dev.shinglem.hermes.core.contract.CronRunDto
import dev.shinglem.hermes.core.contract.DeliveryTargetDto
import dev.shinglem.hermes.core.contract.MessagingPlatformDto
import dev.shinglem.hermes.core.contract.SkillCatalogEntryDto
import dev.shinglem.hermes.core.contract.SkillDto
import dev.shinglem.hermes.core.contract.ToolsetDto
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull

/** 技能页的三段：已安装技能、工具集、技能库（官方可选技能，可安装）。 */
enum class SkillsTab { SKILLS, TOOLSETS, DISCOVER }

/** 定时任务新建 / 编辑表单的草稿。 */
data class CronDraft(
    val name: String = "",
    val schedule: String = "0 9 * * *",
    val prompt: String = "",
    val deliver: String = "local",
    /** 非空表示在编辑这个任务；空表示新建。 */
    val editingJobId: String? = null,
) {
    val isEditing: Boolean get() = editingJobId != null

    val canSave: Boolean get() = schedule.isNotBlank() && prompt.isNotBlank()

    /** 极简 cron 校验：5 段、每段只允许数字 * , - /。够挡住明显写错的输入，不做语义校验。 */
    val scheduleLooksValid: Boolean
        get() {
            val parts = schedule.trim().split(Regex("\\s+"))
            if (parts.size != 5) return false
            return parts.all { part -> part.isNotEmpty() && part.all { it.isDigit() || it in "*,-/" } }
        }

    companion object {
        /** 从已有任务生成编辑草稿（cron 用后端给的 `schedule_display` 回填）。 */
        fun from(job: CronJobDto): CronDraft = CronDraft(
            name = job.name,
            schedule = job.scheduleDisplay,
            prompt = job.prompt,
            deliver = job.deliver.ifBlank { "local" },
            editingJobId = job.id,
        )
    }
}

/**
 * 能力三页的状态。
 *
 * 每页一个独立 `Loadable`：一页失败不影响其它两页（功能域总览 §2.6 的要求）。
 * `busy` 记录正在执行写操作的 key（技能名 / 平台 id / 任务 id），用于行内禁用与转圈。
 */
data class CapabilityState(
    val section: CapabilitySection,
    val skillsTab: SkillsTab = SkillsTab.SKILLS,
    val skillQuery: String = "",
    val skills: Loadable<List<SkillDto>> = Loadable.Idle,
    val toolsets: Loadable<List<ToolsetDto>> = Loadable.Idle,
    val platforms: Loadable<List<MessagingPlatformDto>> = Loadable.Idle,
    val cronJobs: Loadable<List<CronJobDto>> = Loadable.Idle,
    val blueprints: List<BlueprintDto> = emptyList(),
    val deliveryTargets: List<DeliveryTargetDto> = emptyList(),
    /** 正在写的那一项（技能名 / toolset 名 / 平台 id / 任务 id）。 */
    val busyKey: String? = null,
    /** 展开详情的技能名（拉 SKILL.md 正文）。 */
    val expandedSkill: String? = null,
    val skillContent: Loadable<String> = Loadable.Idle,
    /** 官方可选技能库（`/api/skills/hub/official`）：离线可用的完整目录。 */
    val catalog: Loadable<List<SkillCatalogEntryDto>> = Loadable.Idle,
    /** 目录里选中的分类；null = 全部。 */
    val catalogCategory: String? = null,
    /** 目录里展开详情的那一项（identifier）。 */
    val expandedCatalogItem: String? = null,
    /** 展开配置表单的平台 id。 */
    val expandedPlatform: String? = null,
    /** 平台表单里用户已填但未保存的值：`platformId -> (env key -> value)`。 */
    val platformDrafts: Map<String, Map<String, String>> = emptyMap(),
    val cronDraft: CronDraft? = null,
    /** 蓝图实例化表单（选中蓝图后才有）。 */
    val blueprintDraft: BlueprintDraft? = null,
    /** 展开运行历史的任务 id 与其记录。 */
    val expandedJob: String? = null,
    val cronRuns: Loadable<List<CronRunDto>> = Loadable.Idle,
    /** 一次性提示（保存成功 / 失败原因），展示后由 UI 调 [CapabilityViewModel.dismissNotice]。 */
    val notice: String? = null,
    val noticeIsError: Boolean = false,
) {
    /** 搜索结果：按 name / description / category 匹配，忽略大小写。 */
    fun filteredSkills(): List<SkillDto> {
        val all = skills.valueOrNull.orEmpty()
        val query = skillQuery.trim()
        if (query.isEmpty()) return all
        return all.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.description.contains(query, ignoreCase = true) ||
                it.category.contains(query, ignoreCase = true)
        }
    }

    fun filteredToolsets(): List<ToolsetDto> {
        val all = toolsets.valueOrNull.orEmpty()
        val query = skillQuery.trim()
        if (query.isEmpty()) return all
        return all.filter {
            it.displayName.contains(query, ignoreCase = true) ||
                it.description.contains(query, ignoreCase = true) ||
                it.platformLabel.contains(query, ignoreCase = true)
        }
    }

    /** 目录分类清单（按数量降序），供筛选 chip 使用。 */
    fun catalogCategories(): List<Pair<String, Int>> {
        val all = catalog.valueOrNull.orEmpty()
        return all.groupingBy { it.category }.eachCount().toList().sortedByDescending { it.second }
    }

    companion object {
        fun initial(): CapabilityState = CapabilityState(section = CapabilitySection.SKILLS)
    }
}

/** 蓝图实例化草稿：选中的蓝图 + 已填的槽位值。 */
data class BlueprintDraft(
    val key: String,
    val title: String,
    val values: Map<String, String> = emptyMap(),
) {
    val canSave: Boolean get() = key.isNotBlank()
}

/** 平台卡片上正在进行的写操作。 */
enum class PlatformAction { SAVE, TEST }

/** `busyKey` 的构造：一眼能看出是"哪个平台的哪个动作"。 */
internal fun platformBusyKey(platformId: String, action: PlatformAction): String =
    "platform:$platformId:${action.name.lowercase()}"

/** 把异常转成一句可读的中文提示（实现在 `core/error/AppErrorText.kt`，跨功能域共用）。 */
