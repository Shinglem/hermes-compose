package dev.shinglem.hermes.feature.chat

/**
 * 会话级与 profile 级的模型真值缓存（S8：从 `ChatViewModel` 里整块搬出）。
 *
 * ### 为什么它必须单独按 key 存
 *
 * `ChatState` 会被**整块替换**（`open()` 置 Loading、`publish()` 重建）。任何只写在
 * state 上的模型字段都会在那一刻丢，表现为"切走再切回，模型 pill 变回未知"（#16）。
 * 所以真值放在这里，写 state 时用 [applyTo] 补进去。
 *
 * ### 三级优先
 *
 * 会话级（`session.resume` / `session.info` 事件 / `session.mutate` 回执）
 * → profile 默认（`GET /api/model/info`）
 * → null（真的还没读到，不是"没有可用模型"）。
 *
 * 分两个 map 而不是一个，是因为 profile 默认值的有效范围是"该 profile 下所有会话"，
 * 混在一起会让某个会话的 override 顺带变成别的会话的默认值。
 */
internal class SessionModelCache {

    private val bySession = mutableMapOf<SessionKey, SessionModel>()
    private val byProfile = mutableMapOf<String?, SessionModel>()

    /** 某个会话该显示的模型：会话级真值 → profile 默认 → null。 */
    fun forSession(key: SessionKey): SessionModel? = bySession[key] ?: byProfile[key.profile]

    /** 该会话是否已有会话级真值（`resumeInBackground` 据此决定要不要补拉 profile 默认）。 */
    fun hasSessionModel(key: SessionKey): Boolean = bySession[key] != null

    fun putProfile(profile: String?, model: SessionModel) {
        byProfile[profile] = model
    }

    fun profileModel(profile: String?): SessionModel? = byProfile[profile]

    /**
     * 记录会话级真值。
     *
     * 所有来源（`session.resume` / `session.info` 事件 / `session.mutate` 回执）都写这里。
     * **传 null 的字段保留旧值** —— 回执往往只带改动的那个（改模型时不带 reasoning）。
     */
    fun putSession(key: SessionKey, model: String?, provider: String?, reasoningEffort: String?) {
        val previous = bySession[key]
        val resolvedModel = model?.takeIf { it.isNotBlank() } ?: previous?.model ?: return
        bySession[key] = SessionModel(
            model = resolvedModel,
            provider = provider?.takeIf { it.isNotBlank() } ?: previous?.provider,
            reasoningEffort = reasoningEffort?.takeIf { it.isNotBlank() } ?: previous?.reasoningEffort,
        )
    }

    fun clear() {
        bySession.clear()
        byProfile.clear()
    }
}

/** 一个会话的模型三件套。 */
internal data class SessionModel(
    val model: String,
    val provider: String?,
    val reasoningEffort: String?,
)

/** 给一份（可能刚被整块替换的）状态补上该会话已知的模型；已带值的不覆盖。 */
internal fun ChatState.withModelOf(known: SessionModel?): ChatState {
    if (model != null || known == null) return this
    return copy(model = known.model, provider = known.provider, reasoningEffort = known.reasoningEffort)
}
