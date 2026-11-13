package dev.shinglem.hermes.component.settings.store

import com.arkivanov.mvikotlin.core.store.Reducer
import dev.shinglem.hermes.component.settings.ProviderKeysResult
import dev.shinglem.hermes.component.settings.ProviderKeysState
import dev.shinglem.hermes.core.result.Loadable
import kotlinx.collections.immutable.persistentMapOf

/**
 * 提供方密钥页的纯状态迁移。
 *
 * 与 Security 的差别只有一处需要留意：**drafts 里是明文**。
 * 所以 `Loaded` 带回的 profile 与当前不一致时必须立刻清空（否则切 profile 后
 * 残留的明文可能被提交到另一个仓库），这条在 [Loaded] 分支里显式处理。
 */
internal class ProviderKeysReducer : Reducer<ProviderKeysState, ProviderKeysResult> {

    override fun ProviderKeysState.reduce(msg: ProviderKeysResult): ProviderKeysState = when (msg) {
        // 已有数据时不要退回 Loading（否则列表在刷新时闪一片空白）。
        is ProviderKeysResult.Loading -> if (msg.stale == null) {
            copy(env = Loadable.Loading, error = null, savedKey = null)
        } else {
            copy(error = null, savedKey = null)
        }

        is ProviderKeysResult.Loaded ->
            if (msg.profile != profile) {
                copy(
                    env = Loadable.Ready(msg.env),
                    profile = msg.profile,
                    drafts = persistentMapOf(),
                    busyKey = null,
                    error = null,
                    savedKey = null,
                )
            } else {
                copy(env = Loadable.Ready(msg.env), error = null)
            }

        is ProviderKeysResult.Failed -> copy(
            env = Loadable.Failed(msg.error, msg.stale),
            busyKey = null,
        )

        is ProviderKeysResult.QueryChanged -> copy(query = msg.query)

        is ProviderKeysResult.Edited -> copy(
            drafts = drafts.putting(msg.key, msg.value),
            error = null,
            savedKey = null,
        )

        is ProviderKeysResult.Saving -> copy(busyKey = msg.key, error = null, savedKey = null)

        // 保存成功：换新列表（redacted 预览要跟着更新）、丢掉这一条的明文、标出"已保存"。
        is ProviderKeysResult.Saved -> copy(
            env = Loadable.Ready(msg.env),
            drafts = drafts.removing(msg.key),
            busyKey = null,
            savedKey = msg.key,
            error = null,
        )

        is ProviderKeysResult.SaveFailed -> copy(busyKey = null, error = "${msg.key}：${msg.message}")

        ProviderKeysResult.BannersCleared -> copy(savedKey = null, error = null)
    }
}
