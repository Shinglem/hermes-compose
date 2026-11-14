package dev.shinglem.hermes.component.root

import kotlinx.serialization.Serializable

/**
 * 顶层导航目的地（`ChildStack` 的 configuration）。
 *
 * 三个值与左侧头像条上的三个入口一一对应（`13 篇` §9 S5）。用 `@Serializable`
 * 是为了让 Decompose 能在进程恢复时重建栈 —— 现在没用 `stateKeeper`，
 * 但序列化能力是 `ChildStack` 的必备约束，先满足它。
 *
 * **为什么没有"设置"的第 4 个值**：设置是"能力"页里的详情面板（`ChildPanels` 的
 * Details 槽），不是独立 destination —— 迁移前的 UI 也是这样（rail 只有三格）。
 */
@Serializable
sealed interface RootDestination {

    /** 会话：左列表 + 右聊天。 */
    @Serializable
    data object Sessions : RootDestination

    /** 能力：技能与工具 / 消息平台 / 定时任务。 */
    @Serializable
    data object Capabilities : RootDestination

    /** 设置：左侧页组 + 右侧详情。 */
    @Serializable
    data object Settings : RootDestination

    companion object {
        /** 按顺序展示在 rail 上（与迁移前一致：会话 → 能力 → 设置）。 */
        val railOrder: List<RootDestination> = listOf(Sessions, Capabilities, Settings)
    }
}

/**
 * 详情面板的 configuration（`ChildPanels` 的 details 槽）。
 *
 * 迁移前的 `SettingsPage` 带参数，无法 `@Serializable`（见 `13 篇` 的 `SettingsPageKey`
 * 说明）。这里沿用枚举化后的 key：导航只负责"显示哪一页"，参数（例如
 * `Catalog(index)` 的下标）由 SettingsComponent 自己持有。
 */
@Serializable
sealed interface DetailsDestination {

    /** 会话详情。 */
    @Serializable
    data class Session(val sessionId: String, val profile: String?) : DetailsDestination

    /** 设置页：只带 key。 */
    @Serializable
    data class Settings(val key: String) : DetailsDestination
}
