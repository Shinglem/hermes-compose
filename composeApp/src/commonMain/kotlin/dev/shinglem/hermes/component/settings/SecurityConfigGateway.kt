package dev.shinglem.hermes.component.settings

import kotlinx.serialization.json.JsonObject

/**
 * 安全页需要的那两个后端配置读写动作。
 *
 * 为什么单独抽一个接口，而不是直接依赖 `ConfigRepository`：
 * `ConfigRepository` 的构造依赖 `RestClient`（具体类，内部持 Ktor client），
 * 在纯 JVM 单测里造不出来 —— 于是整个 Component 都无法测。这个接口是
 * `13 篇` §2「component 只依赖 domain 接口」的最小落地：Security 需要的面越小越好，
 * 后面各域也照这个做法，只声明自己用到的那几个方法，不引整份 Repository。
 *
 * 生产实现在 `SecurityRepositoryBinding`（`di/` 里一行适配），
 * 测试用 `SecurityRepositoryFake`。
 */
interface SecurityConfigGateway {
    suspend fun read(profile: String?): JsonObject

    suspend fun patch(profile: String?, patch: JsonObject)
}
