package dev.shinglem.hermes.feature.chat

import androidx.compose.foundation.lazy.LazyListState

/**
 * 流式输出时的「自动跟随底部」判定。
 *
 * 规则（来自实测需求）：默认一直跟随最新输出；**用户向上滚一下就把滚动权交还给他**，
 * 滚回接近底部（或点「回到最新」）再恢复跟随。
 *
 * 1. 上翻**立刻**暂停跟随，不看「离开底部多远」—— 曾经用 24px 阈值想过滤触摸板惯性，
 *    代价是流式期间每一帧向上滚都会被补滚拉回去：用户和自动跟随互抢，
 *    表现为滚轮向上时画面闪烁跳变。
 * 2. 向下滚**不**立即恢复跟随 —— 离底部还很远时方向向下不代表想追最新，
 *    恢复交给「是否贴底」判定。
 * 3. 「贴底」留 [STICK_SLOP_PX] 余量：布局取整常常差几个像素，严格等于 0 会导致
 *    恢复条件永远不成立。
 */
internal object ScrollFollow {
    /** 距底部小于该值视为「在底部」，恢复跟随。 */
    const val STICK_SLOP_PX = 8
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

/** 用户滚动后是否要暂停跟随：向上滚就是接管，立刻停。 */
internal fun shouldPauseFollow(deltaY: Float): Boolean = deltaY < 0f

/** 布局变化后是否要恢复跟随：贴底（含少量余量）就恢复。 */
internal fun shouldResumeFollow(
    distanceFromBottomPx: Int,
    stickSlopPx: Int = ScrollFollow.STICK_SLOP_PX,
): Boolean = distanceFromBottomPx <= stickSlopPx
