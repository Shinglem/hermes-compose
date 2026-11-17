package dev.shinglem.hermes.core.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 后端给每只它拉起的 Chromium 写的 launch record
 * （`<HERMES_HOME>/cache/browser/chromium-records 目录下的 json`）。
 *
 * 字段取自后端 `tools/browser_launcher.py::_write_record`，实测样本（ephemeral 形态）：
 *
 * ```json
 * {"owner_pid": 27900, "owner_start_time": 179483178320,
 *  "pid": 24976, "start_time": 179483178395,
 *  "user_data_dir": "…/cache/browser/chromium/<digest>",
 *  "home_key": "…/hermes-home",
 *  "ephemeral": true, "ns": null,
 *  "devtools": "/devtools/browser/97e2d58d-…"}
 * ```
 *
 * **这是发现 agent 浏览器的主数据源**（ADR-082）。不用 `browser.cdp_url`：那只在用户
 * 手配或 `/browser connect` 时才有值，agent 默认的临时实例不写它。
 *
 * 为什么逐个字段容错而不用 `@Serializable`：后端可能加字段（它已经加过 `ns`），
 * 而我们要的是"**缺关键字段就跳过这一条**"而不是"整次发现失败"。
 */
internal object ChromiumRecord {

    /** 解析出来的一条 record。关键字段非空才算有效。 */
    data class Parsed(
        val ownerPid: Long,
        val ownerStartTimeEpochMs: Long?,
        val pid: Long,
        val startTimeEpochMs: Long?,
        val userDataDir: String,
        val homeKey: String,
        val ephemeral: Boolean,
        val devtoolsPath: String?,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 解析一条 record 的 JSON 文本；缺关键字段（`owner_pid` / `pid` / `user_data_dir` /
     * `home_key`）返回 null。
     *
     * 不抛异常：目录里可能有后端写了一半的文件、或其他工具放的东西。发现失败应当表现为
     * "没找到浏览器"，而不是让面板崩掉。
     */
    fun parse(text: String): Parsed? = runCatching {
        val obj = json.parseToJsonElement(text) as? JsonObject ?: return null

        fun long(name: String): Long? = (obj[name] as? JsonPrimitive)?.content?.toLongOrNull()
        fun str(name: String): String? = (obj[name] as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
        fun bool(name: String): Boolean? = (obj[name] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

        val ownerPid = long("owner_pid") ?: return null
        val pid = long("pid") ?: return null
        val userDataDir = str("user_data_dir") ?: return null
        val homeKey = str("home_key") ?: return null

        Parsed(
            ownerPid = ownerPid,
            ownerStartTimeEpochMs = long("owner_start_time"),
            pid = pid,
            startTimeEpochMs = long("start_time"),
            userDataDir = userDataDir,
            homeKey = homeKey,
            ephemeral = bool("ephemeral") ?: false,
            devtoolsPath = str("devtools"),
        )
    }.getOrNull()
}

/**
 * `DevToolsActivePort` 的解析：Chromium 在 `--remote-debugging-port=0` 启动时写进
 * `<user-data-dir>/DevToolsActivePort`，第一行是端口，第二行是 `/devtools/browser/<id>`。
 *
 * 实测样本：
 * ```
 * 50619
 * /devtools/browser/97e2d58d-7c2c-433c-8762-2004798de52b
 * ```
 *
 * 为什么不用 record 里的 `devtools`：它只有路径没有端口，而端口只在这份文件里。
 * 两份合起来才等价于完整的 `cdp_url`。
 */
internal object DevToolsPortFile {
    data class Content(val port: Int, val browserPath: String?)

    fun parse(text: String): Content? {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val port = lines.firstOrNull()?.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return Content(port = port, browserPath = lines.getOrNull(1))
    }

    /** 读 `<userDataDir>/DevToolsActivePort`；文件不在/读不动时返回 null。 */
    fun read(userDataDir: String): Content? =
        runCatching { parse(java.io.File(userDataDir, "DevToolsActivePort").readText()) }.getOrNull()
}
