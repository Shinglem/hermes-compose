package dev.shinglem.hermes.component.settings

import dev.shinglem.hermes.core.contract.EnvVarDto

/**
 * 提供方密钥页需要的那两个后端动作。
 *
 * 与 [SecurityConfigGateway] 同样理由：`ProviderRepository` 依赖 `RestClient`（具体类，
 * 内部持 Ktor client），纯 JVM 单测里造不出来。每域只声明自己用到的面。
 * S3 抽 `domain/repository` 时统一删掉。
 */
interface ProviderKeysGateway {
    suspend fun envVars(profile: String?): Map<String, EnvVarDto>

    /**
     * 写一个环境变量。
     *
     * 后端写完会同时清理 `config.yaml` 里的旧镜像值（它比 `.env` 优先级高），
     * 所以调用方成功后必须**回读** —— 否则列表里的 redacted 预览还是旧值。
     */
    suspend fun setEnvVar(key: String, value: String, profile: String?)
}

/** 生产适配：接既有的 `ProviderRepository`。S3 后删除。 */
class ConfigProviderKeysGateway(
    private val repository: dev.shinglem.hermes.feature.settings.ProviderRepository,
) : ProviderKeysGateway {

    override suspend fun envVars(profile: String?): Map<String, EnvVarDto> = repository.envVars(profile)

    override suspend fun setEnvVar(key: String, value: String, profile: String?) =
        repository.setEnvVar(key, value, profile)
}
