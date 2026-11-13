package dev.shinglem.hermes.feature.settings

import dev.shinglem.hermes.core.contract.MoaPresetDto
import dev.shinglem.hermes.core.contract.MoaSlotDto

/**
 * MoA 预设的**纯逻辑**（保存前自检、可选项清单）。
 *
 * 这一份留在 `feature/settings`：它不依赖任何状态容器，被 `component/settings/store/MoaExecutor`
 * 与 UI 共用。迁移前它是 `MoaSettingsState.kt` 的一部分（同一个文件里还有 `MoaViewModel`），
 * S2 把那部分搬走之后，剩下的就只是这些纯函数。
 */
internal fun moaSlotProblem(slot: MoaSlotDto, label: String): String? = when {
    slot.provider.isBlank() -> "$label 缺少 provider"
    slot.model.isBlank() -> "$label 缺少模型"
    else -> null
}

/**
 * 单预设的保存前自检。后端 `validate_moa_payload` 会 422 拒绝半填预设，
 * 这里先拦一道，别让用户点了保存才看到错误。
 */
internal fun moaPresetProblem(preset: MoaPresetDto): String? {
    preset.referenceModels.forEachIndexed { index, slot ->
        moaSlotProblem(slot, "参考模型 #${index + 1}")?.let { return it }
    }
    if (preset.referenceModels.isEmpty()) return "至少需要一个参考模型"
    moaSlotProblem(preset.aggregator, "聚合模型")?.let { return it }
    return null
}

/** 整份预设表的问题（保存前查全部，不只是当前选中那个）。 */
internal fun moaConfigProblem(presets: Map<String, MoaPresetDto>): String? {
    if (presets.isEmpty()) return "至少要有一个预设"
    presets.forEach { (name, preset) ->
        moaPresetProblem(preset)?.let { return "预设「$name」：$it" }
    }
    return null
}

/** fan-out 节奏的可选项（`every_n:N` 由 UI 单独生成）。 */
val MOA_FANOUTS: List<Pair<String, String>> = listOf(
    "user_turn" to "每个用户回合一次（默认，最省）",
    "per_iteration" to "每次工具迭代（最贵）",
)

/** 失败顾问的披露策略。 */
val MOA_POLICIES: List<Pair<String, String>> = listOf(
    "loud" to "明确说明哪个顾问失败",
    "silent" to "静默降级",
)

/**
 * MoA 页（`/api/model/moa`）。
 *
 * 只操作命名预设形状；后端返回的扁平镜像（默认预设的副本）不参与编辑，
 * 免得两份数据互相覆盖。`privacy_filter` 由后端维护，这里不提交。
 */
