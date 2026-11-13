package dev.shinglem.hermes.component.settings.store

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import dev.shinglem.hermes.component.settings.ProviderKeysEffect
import dev.shinglem.hermes.component.settings.ProviderKeysGateway
import dev.shinglem.hermes.component.settings.ProviderKeysIntent
import dev.shinglem.hermes.component.settings.ProviderKeysResult
import dev.shinglem.hermes.component.settings.ProviderKeysState
import dev.shinglem.hermes.core.error.asAppError
import dev.shinglem.hermes.core.error.toNotice
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** 提供方密钥页的 IO 边界。 */
internal class ProviderKeysExecutor(
    private val gateway: ProviderKeysGateway,
    private val profile: String?,
    executorContext: CoroutineContext,
) : CoroutineExecutor<ProviderKeysIntent, Nothing, ProviderKeysState, ProviderKeysResult, ProviderKeysEffect>(
    executorContext,
) {

    private var loadJob: Job? = null

    override fun executeIntent(intent: ProviderKeysIntent) {
        when (intent) {
            is ProviderKeysIntent.Appear -> if (intent.connected) loadIfNeeded()
            ProviderKeysIntent.Reload -> load()
            is ProviderKeysIntent.SetQuery -> dispatch(ProviderKeysResult.QueryChanged(intent.query))
            is ProviderKeysIntent.Edit -> dispatch(ProviderKeysResult.Edited(intent.key, intent.value))
            is ProviderKeysIntent.Save -> save(intent.key)
            ProviderKeysIntent.DismissBanner -> dispatch(ProviderKeysResult.BannersCleared)
        }
    }

    private fun loadIfNeeded() {
        val state = state()
        if (state.profile == profile && state.env !is Loadable.Idle) return
        load()
    }

    private fun load() {
        loadJob?.cancel()
        val stale = state().env.valueOrNull
        dispatch(ProviderKeysResult.Loading(stale))
        loadJob = scope.launch {
            try {
                dispatch(ProviderKeysResult.Loaded(gateway.envVars(profile), profile))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(ProviderKeysResult.Failed(throwable.asAppError(), stale))
            }
        }
    }

    private fun save(key: String) {
        val value = state().drafts[key]?.trim().orEmpty()
        if (value.isEmpty() || state().busyKey != null) return
        dispatch(ProviderKeysResult.Saving(key))
        scope.launch {
            try {
                gateway.setEnvVar(key, value, profile)
                // 回读：后端写完会清 `config.yaml` 里的旧镜像值，不回读的话 redacted 预览还是旧的。
                val env = gateway.envVars(profile)
                Log.info("提供方密钥已保存：$key")
                dispatch(ProviderKeysResult.Saved(env, key))
                publish(ProviderKeysEffect.Toast("$key 已保存"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                Log.error("保存提供方密钥失败：$key", throwable)
                dispatch(ProviderKeysResult.SaveFailed(key, throwable.asAppError().toNotice()))
            }
        }
    }
}
