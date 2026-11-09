package dev.shinglem.hermes.platform

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier

/**
 * 让列表支持**触屏拖动**滚动。
 *
 * 为什么需要：Compose Desktop 的 `scrollable` 用
 * `CanDragCalculation = { type != PointerType.Mouse }` 拒绝鼠标类型拖动，
 * 而 Windows 会把触屏报成鼠标拖动（`MOUSE_PRESSED/DRAGGED`）—— 手指滑动因此完全无效。
 * `draggable` 不按指针类型过滤，这里用它接管位移，再转给 [LazyListState]。
 *
 * 前提：触摸必须被运行时合成成**鼠标按下/移动**。JBR 会把它合成成滚轮（离散事件），
 * 那种情况下本修饰符收不到 `Press`，因此成品基线用 Temurin。
 * 背景见 `.design/详细设计/工程与代码/10-Windows触屏输入.md`。
 *
 * 不吞按下 / 抬起，点击、长按、按钮仍走 Compose 默认通路。
 * 只做跟手位移，不做惯性：松手即停，行为可预测。
 */
@Composable
fun Modifier.touchDragScroll(
    state: LazyListState,
    /** 每次拖动回调一次手指位移（正 = 手指向下 = 内容往回翻）。 */
    onUserDrag: (delta: Float) -> Unit = {},
): Modifier {
    val currentOnDrag = rememberUpdatedState(onUserDrag)
    val draggableState = rememberDraggableState { delta ->
        currentOnDrag.value(delta)
        state.dispatchRawDelta(-delta)
    }
    // **不要** startDragImmediately：它会立刻抢占手势，把点击 / 长按 / 文本选择一起吞掉
    // （实测：ChatPaneTest 里 4 个"展开工具卡片"类用例直接失败）。
    // 默认行为要超过 slop 才进入拖动，而鼠标类型的 slop 只有 0.125dp（DragGestureDetector
    // 的 mouseSlop），所以仍然近似跟手。
    return this.draggable(
        state = draggableState,
        orientation = Orientation.Vertical,
    )
}