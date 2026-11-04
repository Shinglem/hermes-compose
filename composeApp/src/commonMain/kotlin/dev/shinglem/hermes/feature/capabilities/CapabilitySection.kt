package dev.shinglem.hermes.feature.capabilities

/**
 * 能力模式的中栏分类（`04-ui-spec.md` §4）。
 *
 * 用枚举 + 表驱动渲染：将来加「插件 / MCP」只加一项，不改布局。
 * 三页的实质内容见 `SkillsPane` / `PlatformsPane` / `CronPane`（P4 已实现）。
 */
enum class CapabilitySection { SKILLS, PLATFORMS, CRON }
