package dev.shinglem.hermes.core.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 配置专用的 `Json` 实例 —— **不要复用 `RpcJson.json`**。
 *
 * | 选项 | 值 | 理由 |
 * |---|---|---|
 * | `ignoreUnknownKeys` | `true` | 多一个不认识的名字不该让程序起不来（它可能只是给新版本预留的键）。未知键另行 WARN。 |
 * | `explicitNulls` | `true` | `"profile": null` 是有效值（= 跟随上次选中），不能被当成"缺失"丢掉。 |
 * | `isLenient` | `false` | 配置要严格 JSON。`RpcJson` 的宽松是为兼容后端响应，不该传染到自己的文件。 |
 * | `coerceInputValues` | `false` | 类型错就报错。开了它 `"ready_timeout_ms": null` 会被静默变成默认值，掩盖配置损坏。 |
 */
val ConfigJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = true
    isLenient = false
    coerceInputValues = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

/** 配置相关的错误：**必须带上键名与原值**，否则用户对着 `SerializationException` 无从下手。 */
class ConfigError(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 深合并两组 JSON。四条规则（见设计文档 §3.3.2）：
 *
 * | 值类型 | 规则 |
 * |---|---|
 * | 对象 × 对象 | 递归合并 |
 * | 数组 | **整体替换**（不拼接 —— 拼接会造成"删不掉"的元素） |
 * | 标量 | 覆盖 |
 * | `null` | 覆盖成 null（不是删键） |
 *
 * 一行 `else over[key]` 同时实现了后三条。
 */
fun deepMergeJson(base: JsonObject, over: JsonObject): JsonObject {
    val out = base.toMutableMap()
    for ((key, value) in over) {
        val current = out[key]
        out[key] = if (current is JsonObject && value is JsonObject) {
            deepMergeJson(current, value)
        } else {
            value
        }
    }
    return JsonObject(out)
}

/** 按 `a.b.c` 的路径写入（中间层不存在则建对象），返回新对象。 */
fun setJsonAt(root: JsonObject, keyPath: String, value: JsonElement): JsonObject {
    val segments = keyPath.split('.')
    fun put(node: JsonObject, index: Int): JsonObject {
        val key = segments[index]
        return if (index == segments.lastIndex) {
            JsonObject(node + (key to value))
        } else {
            val child = node[key] as? JsonObject ?: JsonObject(emptyMap())
            JsonObject(node + (key to put(child, index + 1)))
        }
    }
    return put(root, 0)
}

/** 把所有叶子键展开成 `a.b.c` 形式（数组与标量视作叶子）。 */
fun flattenJsonKeys(node: JsonObject, prefix: String = ""): Set<String> {
    val out = mutableSetOf<String>()
    for ((key, value) in node) {
        val path = if (prefix.isEmpty()) key else "$prefix.$key"
        if (value is JsonObject) {
            out += flattenJsonKeys(value, path)
        } else {
            out += path
        }
    }
    return out
}

/**
 * 把 `HDC_` 环境变量 / `-Dhdc.*` 的值按目标字段类型转成 [JsonElement]。
 *
 * 关键点：环境变量给的是字符串（`"90000"`），而目标字段是 `Int`。直接塞
 * `JsonPrimitive("90000")` 会在 decode 时报"期望 Int 得到 String"。所以先按
 * descriptor 的 kind 做类型化。
 */
fun parseConfigValue(raw: String, descriptor: kotlinx.serialization.descriptors.SerialDescriptor, origin: String): JsonElement {
    val trimmed = raw.trim()
    return when (descriptor.kind) {
        kotlinx.serialization.descriptors.PrimitiveKind.INT -> JsonPrimitive(
            trimmed.toIntOrNull() ?: throw ConfigError("$origin 的值 \"$raw\" 不是整数"),
        )

        kotlinx.serialization.descriptors.PrimitiveKind.LONG -> JsonPrimitive(
            trimmed.toLongOrNull() ?: throw ConfigError("$origin 的值 \"$raw\" 不是整数"),
        )

        kotlinx.serialization.descriptors.PrimitiveKind.BOOLEAN -> JsonPrimitive(
            trimmed.toBooleanStrictOrNull()
                ?: throw ConfigError("$origin 的值 \"$raw\" 应为 true / false"),
        )

        kotlinx.serialization.descriptors.PrimitiveKind.STRING -> JsonPrimitive(raw)

        kotlinx.serialization.descriptors.SerialKind.ENUM -> {
            val candidates = (0 until descriptor.elementsCount).map(descriptor::getElementName)
            val hit = candidates.firstOrNull { it.equals(trimmed, ignoreCase = true) }
                ?: throw ConfigError(
                    "$origin 的值 \"$raw\" 不在允许范围（${candidates.joinToString(" / ")}）内",
                )
            JsonPrimitive(hit)
        }

        kotlinx.serialization.descriptors.StructureKind.LIST -> {
            val parsed = runCatching { ConfigJson.parseToJsonElement(raw) }.getOrNull()
            parsed as? JsonArray
                ?: throw ConfigError("$origin 的值必须是 JSON 数组，例如 [\"--isolated\"]")
        }

        kotlinx.serialization.descriptors.StructureKind.CLASS -> {
            val parsed = runCatching { ConfigJson.parseToJsonElement(raw) }.getOrNull()
            parsed as? JsonObject
                ?: throw ConfigError("$origin 的值必须是 JSON 对象，例如 {\"source\":\"inline\",\"value\":\"…\"}")
        }

        else -> throw ConfigError("$origin 对应的字段类型 ${descriptor.kind} 不支持通过环境变量设置")
    }
}
