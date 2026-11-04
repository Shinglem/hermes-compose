package dev.shinglem.hermes.core.config

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 覆盖层落盘：读现有 `*.custom.json` → 深合并 patch → 写回。
 *
 * 保留用户手工加的其它键；写坏（顶层不是对象）时按空覆盖层处理并告警。
 * 写盘失败**抛出**（由调用方显示原因）—— 不能"提示已保存却没有文件"。
 */
class FileSettingsStore(
    private val paths: ConfigPaths,
    private val warn: (String) -> Unit = {},
) : SettingsStore {

    override fun saveConfigPatch(patch: JsonObject) = mergeInto(paths.customFile, patch)

    override fun saveOptionsPatch(patch: JsonObject) = mergeInto(paths.optionsCustomFile, patch)

    private fun mergeInto(file: File, patch: JsonObject) {
        if (patch.isEmpty()) return
        val merged = deepMergeJson(readObject(file), patch)
        // 写盘失败**不吞**：交给上层显示原因（"已保存"却什么都没写是最坏的失败方式）。
        file.parentFile?.mkdirs()
        file.writeText(
            PrettyJson.encodeToString(JsonObject.serializer(), merged) + "\n",
            Charsets.UTF_8,
        )
    }

    private fun readObject(file: File): JsonObject {
        if (!file.isFile) return JsonObject(emptyMap())
        return runCatching {
            ConfigJson.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject
        }.getOrNull() ?: run {
            warn("${file.name} 的顶层不是 JSON 对象，按空覆盖层处理")
            JsonObject(emptyMap())
        }
    }

    private companion object {
        /** 人读的覆盖层文件：缩进两格、键顺序保持插入序。 */
        val PrettyJson = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
    }
}
