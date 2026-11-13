package dev.shinglem.hermes.core.transport

import dev.shinglem.hermes.core.contract.AckDto
import dev.shinglem.hermes.core.contract.ProfileScope
import dev.shinglem.hermes.core.error.AppError
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject

/**
 * REST Repository 的公共基类（S3）。
 *
 * 收拢的是**每个 Repository 本来都要手写一遍**的四件事 —— 之前它们散在
 * `CapabilityRepository` 里当私有方法，其它域各抄一份变体：
 *
 * | 方法 | 收掉的重复 |
 * |---|---|
 * | [withProfile] | 14 个 Repository 各自的 `?profile=` 拼装（外加 `all` 这类特值） |
 * | [decodeList] | 8 处 `runCatching {}.getOrDefault(emptyList())`（**吞异常**的元凶） |
 * | [sendAck] | 12 处 `POST/PUT/DELETE` + 解 `AckDto` + 兜底 |
 * | [buildJson] | 30+ 处 `buildJsonObject {}` 的全限定名 |
 *
 * ### 为什么 `decodeList` 不吞异常
 *
 * 曾经用 `runCatching {}.getOrDefault(emptyList())`，结果后端字段一变就**静默**显示
 * "0 个技能"——排查时完全看不出是解析失败还是真的没数据。现在解析问题一律冒泡成
 * `AppError.Protocol`，UI 上表现为 `Loadable.Failed`，看得见。
 *
 * ### 为什么不做 `getAs` 的包装
 *
 * `RestClient.getAs` / `sendAs` 已经够用（S1 落地的），再包一层只会多一次转发。
 * 基类只补"客户端没有的"那几件。
 */
abstract class RestRepository(protected val rest: RestClient) {

    /** 加 `?profile=`（以及额外 query）；[profile] 为空时不加。 */
    protected fun withProfile(
        path: String,
        profile: String?,
        vararg extra: Pair<String, String>,
    ): String = withProfileQuery(path, profile, *extra)

    /**
     * [ProfileScope] 版本（S3）。
     *
     * 与 `String?` 版本**行为等价**，区别只在类型检查：新代码用它，
     * 就不可能出现"把档案名和其它 String 参数写反"。
     * `None` → 不加 `?profile=`；`All` → `?profile=all`。
     */
    protected fun withProfile(
        path: String,
        scope: ProfileScope,
        vararg extra: Pair<String, String>,
    ): String = withProfileQuery(path, scope.query, *extra)

    /**
     * 列表解码：**不吞异常**。
     *
     * 后端有的接口把列表包在对象里（`{skills: [...]}`），有的直接给数组 ——
     * 后者走这里，前者用 `getAs(...).xxx`。
     */
    protected fun <T> decodeList(serializer: KSerializer<T>, element: JsonElement): List<T> {
        val array = element as? JsonArray
            ?: throw AppError.Protocol("期望数组，收到 ${element::class.simpleName}")
        return array.map { item ->
            runCatching { RpcJson.json.decodeFromJsonElement(serializer, item) }
                .getOrElse { error ->
                    throw AppError.Protocol("列表项解析失败：${error.message?.take(160)}")
                }
        }
    }

    /**
     * 写请求 + 解 `AckDto`。
     *
     * 解不出来时**当作成功**（`AckDto(ok = true)`）：后端有些写接口直接返回 204 或
     * 空对象，而 HTTP 状态码已经表达成功。这与 `decodeList` 的"不吞"不同 ——
     * 列表是**数据**，错了要让人看见；ack 只是"收到了"，解不出来不影响判断。
     */
    protected suspend fun sendAck(
        method: RestMethod,
        path: String,
        body: JsonObject? = null,
        timeoutMs: Long = Timeouts.WRITE_MS,
    ): AckDto {
        val element = rest.send(method, path, body, timeoutMs)
        return runCatching {
            RpcJson.json.decodeFromJsonElement(AckDto.serializer(), element)
        }.getOrElse { AckDto(ok = true) }
    }

    /** 少写一次全限定名的 `buildJsonObject`。 */
    protected fun buildJson(block: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)
}
