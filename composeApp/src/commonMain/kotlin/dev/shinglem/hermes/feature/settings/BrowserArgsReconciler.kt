package dev.shinglem.hermes.feature.settings

import dev.shinglem.hermes.core.backend.LocalBackendPlanner
import dev.shinglem.hermes.core.log.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 把 `--do-not-de-elevate` 幂等地钉进后端各个 profile 的 `browser.chromium_args`。
 *
 * **为什么需要它**：以管理员运行时 Chromium 会「自动去提权」—— 主进程取 `explorer.exe` 的令牌
 * 拉起一个中完整性副本后自己 `exit 0`。后端 `_wait_for_devtools()` 先读 `DevToolsActivePort`
 * 再 `poll()` 判生死，父进程先死就直接报
 * `Chrome exited early (exit code: 0) without writing DevToolsActivePort`，
 * 尽管浏览器其实已经起来了。加上该开关就不再走这条自举路径。
 *
 * **为什么写配置而不是环境变量**：客户端原来注入的 `AGENT_BROWSER_ARGS` 只在后端
 * config 版本 51 → 52 的**迁移**里被读过一次，运行期真正读的是 `browser.chromium_args`
 * （`tools/browser_session_cloud.browser_cfg`）。实测：profile 已有该项时
 * `move_agent_browser_env` 返回空，环境变量被完全忽略。
 *
 * **为什么不直接写 `config.yaml`**：后端没有 YAML 库可用，而 `PUT /api/config/raw` 是整篇替换
 * （写坏就毁掉用户整份配置）。`PUT /api/config` 是深合并，也是后端设置页自己用的通道。
 *
 * **为什么不需要重启后端**：`browser_cfg` 不走 memo，只依赖 `config.yaml` 的文件签名缓存；
 * 实测改完下一次 `browser_exec` 就生效。
 */
class BrowserArgsReconciler(
    private val config: ConfigRepository,
    private val profiles: ProfilesAdminRepository,
    /**
     * 探测某个 CDP 端点是否可达（HTTP `HEAD`/`GET`）。
     *
     * 由 desktop 侧注入：commonMain 里没有可用的 HTTP 探活原语，
     * 而这里只需要一个布尔值，没必要为此引依赖。
     */
    private val cdpAlive: suspend (String) -> Boolean = { false },
) {
    /**
     * 逐 profile 检查并补齐；返回真正写入的 profile 数与失败原因（空 = 全部无需改动或已补好）。
     *
     * 只在该进程以管理员令牌运行时调用 —— 非提权下不需要这个开关，也就不去动用户的配置。
     */
    suspend fun reconcile(): Result {
        val names = runCatching { profiles.list().map { it.name } }
            .getOrElse { error ->
                Log.warn("浏览器参数对账：读取 profile 列表失败：${error.message}")
                return Result(written = emptyList(), failures = listOf("读取 profile 列表失败：${error.message}"))
            }
        // `default` 可能不在列表里（它永远是隐含存在的那一个），补上以免漏掉主 profile。
        val targets = (names + DEFAULT_PROFILE).distinct()

        val written = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for (name in targets) {
            when (val outcome = reconcileOne(name)) {
                is One.Skipped -> Unit
                is One.Written -> written += name
                is One.Failed -> failures += "$name：${outcome.reason}"
            }
        }
        if (written.isNotEmpty()) Log.info("浏览器参数对账：已为 ${written.joinToString(",")} 追加 --do-not-de-elevate")
        for (failure in failures) Log.warn("浏览器参数对账：$failure")
        return Result(written = written, failures = failures)
    }

    private suspend fun reconcileOne(profile: String?): One {
        val current = runCatching { config.read(profile) }
            .getOrElse { error -> return One.Failed("读取 /api/config 失败：${error.message}") }

        val existing = current.deElevateCandidates()
        val patch = buildJsonObject {
            if (!LocalBackendPlanner.hasDeElevateBypass(existing)) {
                // 稀疏 patch：只带这一项。后端对 dict 深合并，同 section 的 headed / use_real_profile 不受影响；
                // 但**数组是整体替换**，所以发的是合成后的完整数组。
                put(
                    "chromium_args",
                    JsonArray(LocalBackendPlanner.withDeElevateBypass(existing).map { JsonPrimitive(it) }),
                )
            }
            // `cdp_url` 优先级高于自动拉起：留着它，上面那个开关就完全没机会生效。
            // 只有当它指向**本机且没人监听**时才清（别替用户改指向别的机器的地址）。
            val deadCdp = current.deadLoopbackCdpUrl()
            if (deadCdp != null) {
                Log.warn(
                    "浏览器对账：$profile 的 browser.cdp_url=$deadCdp 无人监听，已清空改为自动拉起" +
                        "（该地址优先级高于自动启动，留着会让 agent 一直连不上）",
                )
                put("cdp_url", JsonPrimitive(""))
            }
        }
        if (patch.isEmpty()) return One.Skipped

        return runCatching { config.patch(profile, buildJsonObject { put("browser", patch) }) }
            .fold(
                onSuccess = { One.Written },
                onFailure = { error -> One.Failed("PUT /api/config 失败：${error.message}") },
            )
    }

    /**
     * `browser.cdp_url` 指向本机、但那个地址没人监听时返回它（供清空），否则 null。
     *
     * 只认 loopback，且必须先真正探测一次 —— 用户有意接一个本地运行中的调试浏览器时，
     * 它必须原样保留。
     */
    private suspend fun JsonObject.deadLoopbackCdpUrl(): String? {
        val browser = get("browser") as? JsonObject ?: return null
        val raw = (browser.get("cdp_url") as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) ?: return null
        val alive = runCatching { cdpAlive(raw) }.getOrDefault(true)
        val dead = LocalBackendPlanner.isDeadLoopbackCdp(
            cdpUrl = raw,
            hostOf = ::hostOf,
            isAlive = { alive },
        )
        return raw.takeIf { dead }
    }

    /** 从 `http://127.0.0.1:9222` / `ws://[::1]:9222/...` 里取主机名；取不到返回 null。 */
    private fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (afterScheme.isEmpty()) return null
        val authority = afterScheme.substringBefore('/').substringBefore('?')
        val hostPort = authority.substringAfter('@')
        return when {
            hostPort.startsWith("[") -> hostPort.substringAfter('[').substringBefore(']').takeIf { it.isNotEmpty() }
            else -> hostPort.substringBefore(':').takeIf { it.isNotEmpty() }
        }
    }

    /** `browser.chromium_args` 的现值；缺失 / 类型不对时当作空数组（补一条也安全）。 */
    private fun JsonObject.deElevateCandidates(): List<String> {
        val browser = get("browser") as? JsonObject ?: return emptyList()
        val args = browser.get("chromium_args") as? JsonArray ?: return emptyList()
        return args.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
    }

    data class Result(
        val written: List<String>,
        val failures: List<String>,
    ) {
        val isClean: Boolean get() = failures.isEmpty()
    }

    private sealed interface One {
        data object Skipped : One
        data object Written : One
        data class Failed(val reason: String) : One
    }

    private companion object {
        /** 与 `ShellViewModel.DEFAULT_PROFILE` 一致；不直接引用是为了不把 settings 包耦合到 shell。 */
        const val DEFAULT_PROFILE = "default"
    }
}
