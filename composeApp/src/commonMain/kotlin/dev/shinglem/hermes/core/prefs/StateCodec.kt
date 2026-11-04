package dev.shinglem.hermes.core.prefs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * `state.json` 的编解码。
 *
 * 与 `config.json` / `options.json` 不同，这里是**扁平 key-value**（`Settings` 的语义）：
 * 不做深合并、不做覆盖链，文件只有一个写者——本程序自己。
 */
private val StateJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    isLenient = false
}

/** 键排序后输出，保证"内容没变 → 字节没变"，便于肉眼 diff。 */
fun encodeState(values: Map<String, JsonElement>): String =
    StateJson.encodeToString(JsonObject.serializer(), JsonObject(values.toSortedMap()))

/** 顶层必须是对象；值为 `null` 的键丢弃（`Settings` 没有 null 语义，null 等价于不存在）。 */
fun decodeState(text: String): Map<String, JsonElement> {
    val root = StateJson.parseToJsonElement(text) as? JsonObject
        ?: throw IllegalArgumentException("state.json 的顶层必须是 JSON 对象")
    return root.filterValues { it !is JsonNull }
}
