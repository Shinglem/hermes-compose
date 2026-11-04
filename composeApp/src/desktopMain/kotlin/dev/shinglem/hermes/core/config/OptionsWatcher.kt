package dev.shinglem.hermes.core.config

import dev.shinglem.hermes.core.log.Log
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 监听 `config/options.json`，改了就立即应用到 [AppOptionsHolder]。
 *
 * 设计要点（每条都对应一个会踩的坑，见 `05-打包与配置.md` §5.2）：
 * - **监听目录不是文件**：编辑器保存常走"写临时文件 + rename"，
 *   只盯文件会漏掉 `ENTRY_DELETE` + `ENTRY_CREATE` 这一路。
 * - **debounce 300ms**：一次保存会连发多个事件。
 * - **内容 hash 比对**：将来设置页写这个文件时也会触发自己，哈希相同就当回音忽略。
 * - **解析失败只告警不阻断**：`options.json` 是热更选项，写坏了不该让程序崩，
 *   沿用上一次生效的值即可（与 `config.json` 的"拒绝启动"形成对照）。
 */
class OptionsWatcher(
    private val paths: ConfigPaths,
    private val scope: CoroutineScope,
    private val warn: (String) -> Unit,
) {
    private var loopJob: Job? = null
    private var reloadJob: Job? = null
    private var lastAppliedHash: Int = Int.MIN_VALUE

    fun start() {
        if (loopJob != null) return
        loopJob = scope.launch(Dispatchers.IO) {
            val dir = runCatching { paths.configDir.apply { mkdirs() } }.getOrNull() ?: return@launch
            val service = runCatching { FileSystems.getDefault().newWatchService() }.getOrNull() ?: return@launch
            try {
                dir.toPath().register(service, ENTRY_MODIFY, ENTRY_CREATE)
                // 启动时先应用一次，避免"上次改过但没重启"的选项被忽略。
                reload()
                while (isActive) {
                    val key = runCatching { service.poll(WATCH_POLL_MS, TimeUnit.MILLISECONDS) }.getOrNull()
                        ?: continue
                    var touched = false
                    for (event in key.pollEvents()) {
                        val name = (event.context() as? Path)?.fileName?.toString()
                        // 设置页写的是 options_custom.json，也要触发重载。
                        if (name == ConfigPaths.FILE_OPTIONS || name == ConfigPaths.FILE_OPTIONS_CUSTOM) touched = true
                    }
                    key.reset()
                    if (!touched) continue
                    reloadJob?.cancel()
                    reloadJob = scope.launch {
                        delay(DEBOUNCE_MS)
                        reload()
                    }
                }
            } finally {
                runCatching { service.close() }
            }
        }
    }

    fun stop() {
        reloadJob?.cancel()
        loopJob?.cancel()
        reloadJob = null
        loopJob = null
    }

    /** 读一次（`options.json` + `options_custom.json` 深合并）并应用。失败时保留旧值。 */
    private fun reload() {
        val file = paths.optionsFile
        if (!file.isFile) return
        val baseText = runCatching { file.readText(Charsets.UTF_8) }.getOrElse { failure ->
            warn("读取 ${file.absolutePath} 失败，沿用上次选项：${failure.message}")
            return
        }
        // 用户覆盖层：设置页只写它，默认 options.json 保持原样。
        val customFile = paths.optionsCustomFile
        val customText = if (customFile.isFile) {
            runCatching { customFile.readText(Charsets.UTF_8) }.getOrElse { failure ->
                warn("读取 ${customFile.absolutePath} 失败，忽略覆盖层：${failure.message}")
                null
            }
        } else {
            null
        }

        val hash = baseText.hashCode() * 31 + (customText?.hashCode() ?: 0)
        if (hash == lastAppliedHash) return
        try {
            val base = ConfigJson.parseToJsonElement(baseText) as? JsonObject
                ?: throw ConfigError("options.json 的顶层必须是 JSON 对象")
            val custom = customText?.let { text ->
                ConfigJson.parseToJsonElement(text) as? JsonObject
                    ?: throw ConfigError("options_custom.json 的顶层必须是 JSON 对象")
            }
            val merged = if (custom == null) base else deepMergeJson(base, custom)
            val options = parseAppOptions(merged, warn)
            lastAppliedHash = hash
            AppOptionsHolder.set(options)
            Log.info(
                "options 已应用：theme=${options.ui.theme.name.lowercase()}，" +
                    "request=${options.network.requestTimeoutMs}ms，" +
                    "write=${options.network.writeTimeoutMs}ms，" +
                    "promptAck=${options.network.promptAckTimeoutMs}ms",
            )
        } catch (failure: ConfigError) {
            warn("${file.name} 无效，沿用上次选项：${failure.message}")
        } catch (failure: Throwable) {
            warn("${file.name} 解析失败，沿用上次选项：${failure.message}")
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
        const val WATCH_POLL_MS = 500L
    }
}
