package dev.shinglem.hermes.feature.chat

/**
 * 思考块内滚动的跟随判定（纯函数，便于单测）。
 *
 * @property follow 下一个「是否跟随」状态
 * @property requestScroll 是否需要把块内视口滚到末尾
 */
internal data class ReasoningFollow(
    val follow: Boolean,
    val requestScroll: Boolean,
)

/**
 * 一帧滚动/内容变化后的跟随状态。
 *
 * - `scrollValue < previousScrollValue`：视口往回了，说明用户在往上翻（滚轮 / 拖滚动条 /
 *   触摸拖动都算），**立刻交还滚动权**，不再自动追；
 * - 贴底（含 [stickSlopPx] 余量）：恢复跟随 —— 内容增长只会让间距变大，
 *   所以「贴底」不会因为新 token 而误判成用户回来；
 * - 其余情况：跟随开着就请求补滚，关着就什么都不做。
 */
internal fun nextReasoningFollow(
    previousScrollValue: Int,
    scrollValue: Int,
    maxValue: Int,
    follow: Boolean,
    stickSlopPx: Int = ScrollFollow.STICK_SLOP_PX,
): ReasoningFollow {
    if (scrollValue < previousScrollValue) return ReasoningFollow(follow = false, requestScroll = false)
    if (maxValue - scrollValue <= stickSlopPx) return ReasoningFollow(follow = true, requestScroll = false)
    return ReasoningFollow(follow = follow, requestScroll = follow)
}
