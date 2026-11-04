package dev.shinglem.hermes.core.prefs

import com.russhwolf.settings.Settings
import dev.shinglem.hermes.core.log.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 把 `Settings` 的扁平 key-value 落到 `config/state.json`（绿色版要求：不写注册表）。
 *
 * 与 `PreferencesSettings` 的差别只有落盘位置；`AppPreferences` 及其调用点看到的仍是
 * [Settings] 接口，所以一行不用改。
 *
 * 三个实现约束：
 * - **写操作只改内存，落盘推迟 [flushDelayMs]**：会话草稿是逐字写的，
 *   每写一个字就同步落盘会把 UI 线程拖住。
 * - **落盘走"写临时文件 + 原子替换"**：直接覆盖时进程被杀会留下半个 JSON，
 *   下次启动就得走损坏分支。
 * - **文件坏了不让程序崩**：备份成 `state.json.corrupt` 后从空状态开始 ——
 *   丢的是"上次选中哪个会话"，不值得拒绝启动（对比 `config.json` 的严格策略）。
 */
class JsonFileSettings(
    private val file: File,
    private val flushDelayMs: Long = DEFAULT_FLUSH_DELAY_MS,
) : Settings {

    private val lock = Any()
    private val values = LinkedHashMap<String, JsonElement>()

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "state-writer").apply { isDaemon = true }
        }
    private var pending: ScheduledFuture<*>? = null

    init {
        load()
    }

    // ---- Settings 实现 ----

    override val keys: Set<String>
        get() = synchronized(lock) { values.keys.toSet() }

    override val size: Int
        get() = synchronized(lock) { values.size }

    override fun clear() {
        synchronized(lock) { values.clear() }
        scheduleFlush()
    }

    override fun remove(key: String) {
        synchronized(lock) { values.remove(key) }
        scheduleFlush()
    }

    override fun hasKey(key: String): Boolean = synchronized(lock) { values.containsKey(key) }

    override fun putInt(key: String, value: Int) = put(key, JsonPrimitive(value))

    override fun getInt(key: String, defaultValue: Int): Int = getIntOrNull(key) ?: defaultValue

    override fun getIntOrNull(key: String): Int? = number(key)?.intOrNull

    override fun putLong(key: String, value: Long) = put(key, JsonPrimitive(value))

    override fun getLong(key: String, defaultValue: Long): Long = getLongOrNull(key) ?: defaultValue

    override fun getLongOrNull(key: String): Long? = number(key)?.longOrNull

    override fun putString(key: String, value: String) = put(key, JsonPrimitive(value))

    override fun getString(key: String, defaultValue: String): String = getStringOrNull(key) ?: defaultValue

    override fun getStringOrNull(key: String): String? =
        (synchronized(lock) { values[key] } as? JsonPrimitive)?.takeIf { it.isString }?.content

    override fun putFloat(key: String, value: Float) = put(key, JsonPrimitive(value))

    override fun getFloat(key: String, defaultValue: Float): Float = getFloatOrNull(key) ?: defaultValue

    override fun getFloatOrNull(key: String): Float? = number(key)?.floatOrNull

    override fun putDouble(key: String, value: Double) = put(key, JsonPrimitive(value))

    override fun getDouble(key: String, defaultValue: Double): Double = getDoubleOrNull(key) ?: defaultValue

    override fun getDoubleOrNull(key: String): Double? = number(key)?.doubleOrNull

    override fun putBoolean(key: String, value: Boolean) = put(key, JsonPrimitive(value))

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = getBooleanOrNull(key) ?: defaultValue

    override fun getBooleanOrNull(key: String): Boolean? =
        (synchronized(lock) { values[key] } as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    // ---- 落盘 ----

    /** 立即同步落盘。退出前必须调用，否则 [flushDelayMs] 内的改动会丢。 */
    fun flush() {
        val text = synchronized(lock) { encodeState(values) }
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(text, Charsets.UTF_8)
            runCatching {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.onFailure {
                // 部分网络盘/被占位的文件不支持 ATOMIC_MOVE，退回普通覆盖。
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { failure ->
            Log.warn("写入 ${file.name} 失败：${failure.message}")
        }
    }

    /** 停掉后台线程；调用前应先 [flush]。 */
    fun close() {
        pending?.cancel(false)
        pending = null
        scheduler.shutdownNow()
    }

    private fun put(key: String, value: JsonElement) {
        synchronized(lock) { values[key] = value }
        scheduleFlush()
    }

    /** 非字符串标量才算数字：`"42"`（字符串）不该被当成 42 读出来。 */
    private fun number(key: String): JsonPrimitive? =
        (synchronized(lock) { values[key] } as? JsonPrimitive)?.takeIf { !it.isString }

    private fun scheduleFlush() {
        synchronized(lock) {
            if (pending?.isDone == false) return
            pending = runCatching {
                scheduler.schedule({ runCatching { flush() } }, flushDelayMs, TimeUnit.MILLISECONDS)
            }.getOrNull()
        }
    }

    private fun load() {
        if (!file.isFile) return
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return
        try {
            values.putAll(decodeState(text))
        } catch (failure: Throwable) {
            val backup = File(file.parentFile, "${file.name}.corrupt")
            runCatching { file.copyTo(backup, overwrite = true) }
            Log.warn("${file.name} 解析失败，已备份为 ${backup.name} 并用默认值：${failure.message}")
            values.clear()
        }
    }

    private companion object {
        /** 200ms 足以合并"逐字写草稿"，又不至于让用户在关窗口前丢东西。 */
        const val DEFAULT_FLUSH_DELAY_MS = 200L
    }
}
