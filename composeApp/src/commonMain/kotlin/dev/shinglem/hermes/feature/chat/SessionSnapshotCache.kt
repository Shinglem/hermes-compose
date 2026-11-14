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

/**
 * 切走会话的状态快照缓存（S8：原 `ChatViewModel.savedStates` 一张裸 map）。
 *
 * ### 为什么必须有快照
 *
 * 切走时只标记 dirty、等切回来补拉**并不够**：切回那一刻那轮往往还在跑（审批就是在等它），
 * `isSessionLive` 为真就跳过补拉，而切走期间的 `message.delta` 早已被丢弃 ——
 * 界面永久停在切走前那一帧，必须手动点"刷新"（用户实测复现过两次）。
 * 所以切走时存一份完整 `ChatState`，切走期间的事件由 [track] 继续往里累积。
 *
 * ### 上限
 *
 * 最多留 [MAX_ENTRIES] 份（超了淘汰最早的那份）：每份含完整消息列表，
 * 不设上限的话开着二十个会话就是二十份 ChatState 常驻。
 */
internal class SessionSnapshotCache {

    private val snapshots = mutableMapOf<SessionKey, ChatState>()

    /** 插入顺序（`LinkedHashMap` 的迭代序 = 插入序，淘汰时取第一个）。 */
    val size: Int get() = snapshots.size

    operator fun get(key: SessionKey): ChatState? = snapshots[key]

    fun containsKey(key: SessionKey): Boolean = snapshots.containsKey(key)

    /** 存一份快照；超过上限时淘汰最早的那份。 */
    fun put(key: SessionKey, snapshot: ChatState) {
        snapshots[key] = snapshot
        if (snapshots.size > MAX_ENTRIES) snapshots.remove(snapshots.keys.first())
    }

    /** 取出并移除（恢复快照时用：恢复后它就不再需要了）。 */
    fun remove(key: SessionKey): ChatState? = snapshots.remove(key)

    /** 丢弃快照但不返回（切到别的会话 / 重新真读时）。 */
    fun discard(key: SessionKey) {
        snapshots.remove(key)
    }

    /** 该会话的快照里是否还在流式（决定要不要先恢复再说）。 */
    fun hasStreaming(key: SessionKey): Boolean = snapshots[key]?.streaming != null

    /**
     * 把一条事件累积进该会话的快照（切走期间调用）。
     *
     * 快照不存在就忽略：没有快照意味着这一轮不曾在本地跑过，累积一个只有半截流式的
     * 状态反而会在切回时把它当成"有内容"从而跳过补拉。
     */
    fun track(key: SessionKey, parsed: ChatStreamEvent, nextId: (String) -> String) {
        val snapshot = snapshots[key] ?: return
        snapshots[key] = snapshot.reduceStreamEvent(parsed, nextId)
    }

    /** 对每份快照做一次变换（`dispose` 时把在途审批清掉）。 */
    fun updateAll(block: (ChatState) -> ChatState) {
        for (key in snapshots.keys.toList()) {
            val snapshot = snapshots[key] ?: continue
            snapshots[key] = block(snapshot)
        }
    }

    fun clear() {
        snapshots.clear()
    }

    private companion object {
        const val MAX_ENTRIES = 12
    }
}

/**
 * 决策：这次切换该走哪条路。
 *
 * @param force 用户主动刷新（下拉 / 重试）：必须真读，不许拿缓存糊过去。
 * @param sameKeyReady 已经在看这个会话且内容就绪。
 * @param stale 切走期间有新事件（`SessionRuntime.dirty`）。
 * @param cachedStreaming 切走时还在流式。
 */
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
