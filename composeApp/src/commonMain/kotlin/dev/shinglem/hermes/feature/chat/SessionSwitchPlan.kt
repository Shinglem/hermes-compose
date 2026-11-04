package dev.shinglem.hermes.feature.chat

/**
 * 切到某个会话时该怎么出内容。
 *
 * 这是 `待修复问题.md` 第 3 条（多会话并行时回复流丢失、看起来卡死）的核心决策表：
 * 切走期间别的会话拿到了事件 → 该会话被标记为 [stale]，切回时必须**先恢复快照**再补拉，
 * 否则界面会停在切走前那一帧、看着像卡死。
 *
 * 抽成纯函数是为了能逐条断言；原来这段判断埋在 `selectSession` 的分支里，
 * 改一个条件只能靠多开会话手点。
 */
internal enum class SessionSwitchPlan {
    /** 已经在看这个会话且内容就绪：什么都不用做。 */
    KEEP,

    /** 恢复切走时的快照（含流式段落）—— 切回瞬间就有内容。 */
    RESTORE_SNAPSHOT,

    /** 用已读过的 transcript 缓存重新投影。 */
    USE_CACHE,

    /** 真正去后端读。 */
    RELOAD,
}

internal fun switchPlan(
    force: Boolean,
    sameKeyReady: Boolean,
    stale: Boolean,
    cachedStreaming: Boolean,
    hasSnapshot: Boolean,
    hasCache: Boolean,
): SessionSwitchPlan {
    // force 是"用户主动刷新"（下拉 / 重试），必须真的重读，不能拿缓存糊过去。
    if (force) return SessionSwitchPlan.RELOAD
    if (sameKeyReady) return SessionSwitchPlan.KEEP
    // stale：切走期间有新事件；cachedStreaming：切走时还在流式。两种都要先摆回快照。
    if ((stale || cachedStreaming) && hasSnapshot) return SessionSwitchPlan.RESTORE_SNAPSHOT
    if (hasCache) return SessionSwitchPlan.USE_CACHE
    return SessionSwitchPlan.RELOAD
}
