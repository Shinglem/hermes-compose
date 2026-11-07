package dev.shinglem.hermes.feature.settings

import dev.shinglem.hermes.core.config.AppConfig
import dev.shinglem.hermes.core.config.AppOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 生效方式：`RESTART` 写 `config_custom.json`，`LIVE` 写 `options_custom.json`。 */
enum class SettingsScope { RESTART, LIVE }

enum class SettingsKind { TEXT, NUMBER, BOOLEAN, ENUM }

/**
 * 设置页的一项。
 *
 * `key` 是 JSON 路径；`options.*` 前缀表示它属于 `options.json`（立即生效），
 * 其余属于 `config.json`（需重启）。这个前缀只在本层用，写文件时会去掉。
 */
data class SettingsField(
    val key: String,
    val label: String,
    val hint: String? = null,
    val kind: SettingsKind,
    val enumValues: List<String> = emptyList(),
    /** 允许留空（写入 `null`）；否则空串表示「不覆盖」。 */
    val nullable: Boolean = false,
    /** 枚举值的中文显示名；缺省直接显示原值。写进文件的仍是 [enumValues] 里的英文值。 */
    val enumLabels: Map<String, String> = emptyMap(),
) {
    val scope: SettingsScope get() = if (key.startsWith(OPTIONS_PREFIX)) SettingsScope.LIVE else SettingsScope.RESTART

    /** 写进 `*.custom.json` 时的键路径（去掉 `options.` 前缀）。 */
    val jsonPath: String get() = key.removePrefix(OPTIONS_PREFIX)

    companion object {
        const val OPTIONS_PREFIX = "options."
    }
}

data class SettingsGroup(val title: String, val fields: List<SettingsField>)

/**
 * 二级页面的定位：要么是 [SettingsCatalog] 里的一组字段，要么是自绘的独立页。
 *
 * 设置页左栏是「一级分组 → 二级页面」两级结构，任何入口都必须挂在某个分组下
 * （不再有直接裸在根上的行）。
 */
sealed interface SettingsPage {
    /** 目录里的字段组，[index] 指向 [SettingsCatalog.groups]。 */
    data class Catalog(val index: Int) : SettingsPage

    /** 终端外观（字体 / 字号 / 滚动行数 / 配色）。 */
    data object TerminalAppearance : SettingsPage

    /** 提供方密钥（读 `/api/env`，按 provider 分组填值）。 */
    data object ProviderKeys : SettingsPage

    /** 提供方 · 自定义端点（OpenAI 兼容服务）。 */
    data object CustomEndpoints : SettingsPage

    /** 配置档案（列表 / SOUL.md / 新建 / 导入导出）。 */
    data object Profiles : SettingsPage

    /** 模型 · 主模型（profile 默认模型 + 上下文覆盖）。 */
    data object MainModel : SettingsPage

    /** 模型 · 辅助模型（后台任务槽位）。 */
    data object AuxModels : SettingsPage

    /** 模型 · 备用模型（主模型不可用时的回退链）。 */
    data object FallbackModels : SettingsPage

    /** 模型 · MoA（多顾问 + 聚合模型）。 */
    data object MoaModels : SettingsPage

    /** 安全 · 审批。 */
    data object Approvals : SettingsPage

    /** 安全 · 隐私与网络。 */
    data object Privacy : SettingsPage

    /** 安全 · 检查点。 */
    data object Checkpoints : SettingsPage

    /** 调试（官方桌面端对照包；v1.0.0 随 ADR-045 一起移除）。 */
    data object Debug : SettingsPage

    /** 桌面通知开关（客户端行为，存 `state.json`）。 */
    data object Notifications : SettingsPage

    /** 内置后端的浏览器行为（`app.browser.headed`）。 */
    data object Browser : SettingsPage

    /** 外部编辑器（点工具卡片旁的「打开」用哪个程序）。 */
    data object Editors : SettingsPage

    /** 快捷键一览（只读）。 */
    data object Shortcuts : SettingsPage

    /** 关于 / 检查后端更新。 */
    data object About : SettingsPage

    /** 智能体 · 记忆（provider 切换与内置记忆文件重置）。 */
    data object Memory : SettingsPage

    /** 应用 · 诊断与维护（doctor / 安全审计 / 备份 / 检查点 / curator）。 */
    data object Maintenance : SettingsPage

    /** 应用 · 用量与成本（跨会话统计）。 */
    data object Usage : SettingsPage

    /** 应用 · 会话维护（整库统计与空会话清理）。 */
    data object SessionMaintenance : SettingsPage

    /** 提供方 · 凭据池（同一 provider 多把 key 轮换）。 */
    data object CredentialPool : SettingsPage

    /** 集成 · 外部集成（shell 钩子 + 配对白名单）。 */
    data object Integrations : SettingsPage
}

/** 设置页一级分组：可折叠，组内是二级页面。 */
data class SettingsSection(
    val key: String,
    val title: String,
    val pages: List<Pair<String, SettingsPage>>,
)

/**
 * 设置页的字段目录 —— **当前所有可配置项**都在这里，不散写在页面里。
 *
 * 新增配置项时只改这里 + 对应 data class；页面按 kind 自动生成控件。
 */
object SettingsCatalog {
    /** 枚举值 → 中文显示名。只影响界面文案，写进 `options_custom.json` 的仍是英文值。 */
    private val THEME_MODE_LABELS = mapOf(
        "system" to "跟随系统",
        "light" to "浅色",
        "dark" to "深色",
    )

    private val THEME_STYLE_LABELS = mapOf(
        "material" to "默认",
        "paper" to "纸面",
        "ocean" to "海洋",
        "sage" to "灰绿",
        "plum" to "梅紫",
        "amber" to "琥珀",
    )

    private val MARKDOWN_STYLE_LABELS = mapOf(
        "follow" to "跟随界面",
        "material" to "默认",
        "paper" to "纸面",
    )

    val groups: List<SettingsGroup> = listOf(
        SettingsGroup(
            title = "后端",
            fields = listOf(
                SettingsField("backend.mode", "后端模式", "local = 拉起随包 payload，remote = 连既有服务", SettingsKind.ENUM, listOf("local", "remote")),
                SettingsField(
                    "backend.local.host",
                    "监听地址",
                    "isolated 路线生效；gateway 路线的地址由 daemon 决定（看启动日志里的 api_origin）",
                    SettingsKind.TEXT,
                ),
                SettingsField(
                    "backend.local.port",
                    "监听端口",
                    "isolated 路线生效：0 = 随机分配（默认），固定端口才能被隧道稳定指向；gateway 路线的端口由 daemon 决定",
                    SettingsKind.NUMBER,
                ),
                SettingsField(
                    "backend.local.token",
                    "固定凭据",
                    "留空 = 每次启动随机（更安全）。填了跨重启不变，别人的 remote 模式就用它；明文存在 config.json，等同 API key",
                    SettingsKind.TEXT,
                    nullable = true,
                ),
                SettingsField("backend.local.hermes_dir", "内嵌 payload 目录", "相对 <home>，或绝对路径", SettingsKind.TEXT),
                SettingsField("backend.local.home_dir", "本地数据目录", "后端 HERMES_HOME，相对 <home>；gateway 路线会注入给 daemon", SettingsKind.TEXT),
                SettingsField(
                    "backend.local.engine",
                    "后端路线",
                    "gateway = 让上游机器级 gateway 起后端再 attach（0.23+ 的官方路线）；isolated = 自起 dashboard 子进程（回退）",
                    SettingsKind.ENUM,
                    listOf("gateway", "isolated"),
                ),
                SettingsField("backend.local.ready_timeout_ms", "启动超时（毫秒）", "下限 45000", SettingsKind.NUMBER),
                SettingsField("backend.local.run_cron", "本地模式执行 cron", "= 是否给后端子进程设 HERMES_DESKTOP=1；改完要重启后端", SettingsKind.BOOLEAN),
                SettingsField("backend.remote.base_url", "远端地址", "remote 模式使用", SettingsKind.TEXT),
                SettingsField("backend.remote.connect_timeout_ms", "连接超时（毫秒）", null, SettingsKind.NUMBER),
            ),
        ),
        SettingsGroup(
            title = "网络",
            fields = listOf(
                SettingsField("options.network.request_timeout_ms", "请求超时（毫秒）", null, SettingsKind.NUMBER),
                SettingsField("options.network.write_timeout_ms", "写请求超时（毫秒）", null, SettingsKind.NUMBER),
                SettingsField("options.network.prompt_ack_timeout_ms", "对话 ack 超时（毫秒）", "下限 60000", SettingsKind.NUMBER),
            ),
        ),
        SettingsGroup(
            title = "界面",
            fields = listOf(
                SettingsField(
                    "options.ui.theme",
                    "主题",
                    "system = 跟随系统；light / dark = 强制明暗",
                    SettingsKind.ENUM,
                    listOf("system", "light", "dark"),
                    enumLabels = THEME_MODE_LABELS,
                ),
                SettingsField(
                    "options.ui.style",
                    "配色风格",
                    "六套色板，改完立即生效；明暗仍由上面的「主题」决定",
                    SettingsKind.ENUM,
                    listOf("material", "paper", "ocean", "sage", "plum", "amber"),
                    enumLabels = THEME_STYLE_LABELS,
                ),
                SettingsField(
                    "options.ui.markdown_style",
                    "Markdown 配色",
                    "follow = 跟随界面配色风格；material / paper = 单独指定",
                    SettingsKind.ENUM,
                    listOf("follow", "material", "paper"),
                    enumLabels = MARKDOWN_STYLE_LABELS,
                ),
            ),
        ),
        SettingsGroup(
            title = "窗口",
            fields = listOf(
                SettingsField("app.window.width", "初始宽度", null, SettingsKind.NUMBER),
                SettingsField("app.window.height", "初始高度", null, SettingsKind.NUMBER),
                SettingsField("app.window.min_width", "最小宽度", null, SettingsKind.NUMBER),
                SettingsField("app.window.min_height", "最小高度", null, SettingsKind.NUMBER),
            ),
        ),
        SettingsGroup(
            title = "常规",
            fields = listOf(
                SettingsField("app.data_dir", "数据目录", "留空 = %LOCALAPPDATA%\\hermes-compose；便携模式填 data", SettingsKind.TEXT, nullable = true),
                SettingsField("app.profile", "固定 profile", "留空 = 跟随上次选择", SettingsKind.TEXT, nullable = true),
                SettingsField("app.close_to_tray", "关闭时收进托盘", null, SettingsKind.BOOLEAN),
            ),
        ),
        SettingsGroup(
            title = "启动与权限",
            fields = listOf(
                SettingsField(
                    "app.elevate",
                    "要求管理员权限",
                    "ask = 每次手动启动弹一次 UAC；开机自启走计划任务，不受此影响",
                    SettingsKind.ENUM,
                    listOf("never", "ask"),
                ),
                SettingsField(
                    SettingsCatalog.KEY_AUTOSTART,
                    "开机自启",
                    "task = 登录时由计划任务以最高权限启动（不弹 UAC）；需要管理员权限才能落地",
                    SettingsKind.ENUM,
                    listOf("off", "task"),
                ),
            ),
        ),
        SettingsGroup(
            title = "日志",
            fields = listOf(
                SettingsField("app.log_level", "日志级别", null, SettingsKind.ENUM, listOf("DEBUG", "INFO", "WARN", "ERROR")),
                SettingsField("app.log.dir", "日志目录", "相对数据目录", SettingsKind.TEXT),
                SettingsField("app.log.keep_days", "保留天数", null, SettingsKind.NUMBER),
                SettingsField("app.log.max_files", "最多文件数", null, SettingsKind.NUMBER),
                SettingsField("app.log.also_stderr", "同时输出到控制台", null, SettingsKind.BOOLEAN),
            ),
        ),
    )

    val allFields: List<SettingsField> = groups.flatMap { it.fields }

    /** 左栏一级分组 —— 所有页面都挂在这里，不留裸行。 */
    val sections: List<SettingsSection> get() = sections(debugAvailable = false)

    /**
     * 按可用性生成左栏分组。
     *
     * 一级分组按「用户要解决什么问题」切：连接（能不能连上）、外观（看起来怎样）、
     * 智能体（它怎么思考）、提供方（用谁的模型）、安全（敢不敢放它跑）、
     * 桌面（这台机器上怎么用）、应用（程序本身）、集成（外部程序）。
     *
     * 「集成」只在检测到官方桌面端时出现（[debugAvailable]）—— 不做置灰占位，
     * 否则用户会以为点了没反应。
     */
    fun sections(debugAvailable: Boolean): List<SettingsSection> = buildList {
        add(
            SettingsSection(
                key = "connection",
                title = "连接",
                pages = listOf(
                    "后端" to SettingsPage.Catalog(0),
                    "网络" to SettingsPage.Catalog(1),
                ),
            ),
        )
        add(
            SettingsSection(
                key = "appearance",
                title = "外观",
                pages = listOf(
                    "界面" to SettingsPage.Catalog(2),
                    "窗口" to SettingsPage.Catalog(3),
                    "终端外观" to SettingsPage.TerminalAppearance,
                ),
            ),
        )
        add(
            SettingsSection(
                key = "agent",
                title = "智能体",
                pages = listOf(
                    "主模型" to SettingsPage.MainModel,
                    "辅助模型" to SettingsPage.AuxModels,
                    "备用模型" to SettingsPage.FallbackModels,
                    "混合代理 MoA" to SettingsPage.MoaModels,
                    "配置档案" to SettingsPage.Profiles,
                    "记忆" to SettingsPage.Memory,
                ),
            ),
        )
        add(
            SettingsSection(
                key = "providers",
                title = "提供方",
                pages = listOf(
                    "密钥" to SettingsPage.ProviderKeys,
                    "凭据池" to SettingsPage.CredentialPool,
                    "自定义端点" to SettingsPage.CustomEndpoints,
                ),
            ),
        )
        add(
            SettingsSection(
                key = "security",
                title = "安全",
                pages = listOf(
                    "审批" to SettingsPage.Approvals,
                    "隐私与网络" to SettingsPage.Privacy,
                    "检查点" to SettingsPage.Checkpoints,
                ),
            ),
        )
        add(
            SettingsSection(
                key = "desktop",
                title = "桌面",
                pages = listOf(
                    "通知" to SettingsPage.Notifications,
                    "浏览器" to SettingsPage.Browser,
                    "外部编辑器" to SettingsPage.Editors,
                    "快捷键" to SettingsPage.Shortcuts,
                ),
            ),
        )
        add(
            SettingsSection(
                key = "app",
                title = "应用",
                pages = listOf(
                    "常规" to SettingsPage.Catalog(4),
                    "启动与权限" to SettingsPage.Catalog(5),
                    "日志" to SettingsPage.Catalog(6),
                    "用量与成本" to SettingsPage.Usage,
                    "会话维护" to SettingsPage.SessionMaintenance,
                    "诊断与维护" to SettingsPage.Maintenance,
                    "关于" to SettingsPage.About,
                ),
            ),
        )
        add(
            SettingsSection(
                key = "integration",
                title = "集成",
                pages = buildList {
                    add("外部集成" to SettingsPage.Integrations)
                    // 只有 payload 里带了官方桌面端时才出现对照入口（ADR-045）。
                    if (debugAvailable) add("官方桌面端" to SettingsPage.Debug)
                },
            ),
        )
    }

    /** 默认选中的页面：第一组的第一个二级项。 */
    val defaultPage: SettingsPage = SettingsPage.Catalog(0)

    /** 关机自启的字段键：页面要用它挂"落地状态"提示，避免在 UI 里写魔法字符串。 */
    const val KEY_AUTOSTART = "app.autostart"

    /** 把 config/options 展开成「key → 文本值」，供表单直接显示。 */
    fun snapshot(config: AppConfig, options: AppOptions): Map<String, String> {
        // 必须 encodeDefaults：ConfigJson 为了「只写改过的键」关掉了默认值输出，
        // 用它做快照会得到一个空对象，表单全空。
        val configJson = SnapshotJson.encodeToJsonElement(AppConfig.serializer(), config) as JsonObject
        val optionsJson = SnapshotJson.encodeToJsonElement(AppOptions.serializer(), options) as JsonObject
        return allFields.associate { field ->
            val source = if (field.scope == SettingsScope.LIVE) optionsJson else configJson
            field.key to (readAt(source, field.jsonPath)?.toText().orEmpty())
        }
    }

    private val SnapshotJson = Json { encodeDefaults = true }

    private fun readAt(root: JsonObject, path: String): JsonElement? {
        var node: JsonElement = root
        for (segment in path.split('.')) {
            node = (node as? JsonObject)?.get(segment) ?: return null
        }
        return node
    }

    private fun JsonElement.toText(): String {
        if (this is JsonNull) return ""
        val primitive = runCatching { jsonPrimitive }.getOrNull() ?: return toString()
        return primitive.content
    }
}
