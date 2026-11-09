package dev.shinglem.hermes.feature.chat

import androidx.compose.foundation.lazy.LazyListState

/**
 * 流式输出时的「自动跟随底部」判定。
 *
 * 规则（来自实测需求）：默认一直跟随最新输出；**只有用户真的向上翻走**才暂停，
 * 滚回接近底部就自动恢复。三处细节都是为了不误判：
 *
 * 1. 上翻要超过 [PAUSE_SLOP_PX] 才算「离开底部」—— 触摸板的惯性余量、滚轮一格
 *    都会产生小幅负 delta，按「方向」判定会让跟随永久失效（旧实现的症状：
 *    流式回答不动了，用户以为自己没滚）。
 * 2. 向下滚**不**立即恢复跟随 —— 离底部还很远时方向向下不代表想追最新，
 *    恢复交给「是否贴底」判定。
 * 3. 「贴底」留 [STICK_SLOP_PX] 余量：布局取整常常差几个像素，严格等于 0 会导致
 *    恢复条件永远不成立。
 */
internal object ScrollFollow {
    /** 距底部小于该值视为「在底部」，恢复跟随。 */
    const val STICK_SLOP_PX = 8

    /** 上翻离开底部超过该值才暂停跟随。 */
    const val PAUSE_SLOP_PX = 24
}

/**
 * 距底部的像素数：最后一项底边 - 视口有效底边。
 *
 * 用 `afterContentPadding` 扣掉列表尾部留白，否则加了 bottom padding 的列表会
 * 永远差那一段距离、判定成「没贴底」。
 */
internal fun LazyListState.distanceFromBottomPx(): Int {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0
    return contentEndGapPx(
        lastVisibleIndex = last.index,
        totalItemsCount = info.totalItemsCount,
        lastVisibleBottomPx = last.offset + last.size,
        viewportBottomPx = info.viewportEndOffset - info.afterContentPadding,
    )
}

/**
 * 「离内容末尾还有多远」，纯函数（[LazyListState.distanceFromBottomPx] 的判定本体）。
 *
 * 关键一条：**最后一项还没进视口时不能说「贴底」**。只量「最后可见项的底部超出视口多少」
 * 时，列表停在中间（最后可见项正好贴着视口底、后面还有好几屏内容）也会算出 0，
 * 跟随逻辑于是认为已经到了末尾、不再补滚 —— 表现为「点了回到最新之后再也不跟随」。
 * 这种情形按「至少还有一屏」处理（用视口高度当下界），既能让跟随继续补滚，
 * 也让「上翻离开底部」的暂停判定成立。
 */
internal fun contentEndGapPx(
    lastVisibleIndex: Int,
    totalItemsCount: Int,
    lastVisibleBottomPx: Int,
    viewportBottomPx: Int,
): Int {
    if (totalItemsCount <= 0) return 0
    val gap = lastVisibleBottomPx - viewportBottomPx
    if (lastVisibleIndex < totalItemsCount - 1) return maxOf(gap, viewportBottomPx)
    return gap.coerceAtLeast(0)
}

/** 用户滚动后是否要暂停跟随：只有真的向上翻离底部才暂停。 */
internal fun shouldPauseFollow(
    deltaY: Float,
    distanceFromBottomPx: Int,
    pauseSlopPx: Int = ScrollFollow.PAUSE_SLOP_PX,
): Boolean = deltaY < 0f && distanceFromBottomPx > pauseSlopPx

/** 布局变化后是否要恢复跟随：贴底（含少量余量）就恢复。 */
internal fun shouldResumeFollow(
    distanceFromBottomPx: Int,
    stickSlopPx: Int = ScrollFollow.STICK_SLOP_PX,
): Boolean = distanceFromBottomPx <= stickSlopPx
