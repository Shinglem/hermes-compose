package dev.shinglem.hermes.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import dev.shinglem.hermes.component.browser.BrowserState
import dev.shinglem.hermes.component.browser.Phase
import dev.shinglem.hermes.ui.icon.AppIcons

/**
 * 浏览器抽屉（v0.6.1，ADR-082；设计见 `详细设计/功能/08-浏览器侧栏.md`）。
 *
 * ### 最关键的结构约束：**槽位常驻，只改尺寸**
 *
 * 抽屉收起时**不能**把 `content` 从组合树里摘掉 —— 那会销毁 `NativeView`，窗口被还原成
 * 独立顶层窗口；下次展开又重新 `SetParent` 收进来。表现就是「一会儿被纳管、一会儿又成独立窗口」。
 *
 * 布局是 `Box` 叠加：
 *
 * ```
 * Box(宽 = panelOpen ? panelWidth : 40dp)      ← 外层只负责占位
 *   ├─ 抽屉主体（仅 panelOpen 时；手柄 + 标题栏 + 空态/内容）
 *   └─ 槽位容器（永远在树里；宽高 = panelOpen ? 填满内容区 : 0）
 * ```
 *
 * 收起时槽位容器被约束成 0 尺寸：窗口被挪到不可见位置，但**仍归属本进程**。
 *
 * ### 为什么是"容器 + 内容槽"
 *
 * 现在的唯一内容是浏览器槽位，但抽屉本身是个通用位置 —— 以后要放别的侧栏内容
 * （文件树、diff、子代理详情）时只需要换 `content`，不必再动布局。
 *
 * ### 四种"没有画面"必须给不同的话
 *
 * `NoBrowser`（让 agent 访问网页）/ `Headless`（改后端 `browser.headed`）/
 * `Unsupported`（非 Windows）/ `Failed`（重试）。笼统的"未找到"会让用户没法处置。
 *
 * 不做地址栏：给用户一个能自己导航的框会和 agent 的页面状态打架。要自己浏览请用
 * "在系统浏览器打开"。
 */
@Composable
fun BrowserSidePanel(
    state: BrowserState,
    onTogglePanel: () -> Unit,
    onRefresh: () -> Unit,
    onOpenInSystemBrowser: () -> Unit,
    onDismissError: () -> Unit,
    onOpenBrowserSettings: () -> Unit,
    onResize: (Int) -> Unit,
    onSelectTarget: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** 抽屉内容槽：现在放浏览器槽位，以后可放别的侧栏内容。[pid] 区分多实例。 */
    content: @Composable (pid: Long, modifier: Modifier) -> Unit,
) {
    /**
     * ### 关键结构约束：槽位的**父链在收起/展开时不能变**
     *
     * 这是踩过两次坑的地方。错误写法是：
     *
     * ```kotlin
     * if (!panelOpen) { Box(...) { content() } ; return }   // 父链 A
     * Surface { ... Box(...) { content() } }                // 父链 B
     * ```
     *
     * 两种状态下槽位的**父节点不同**，Compose 会认为这是两棵不同的子树 → 销毁重建
     * `NativeView` → 窗口被 `SetParent(NULL)` 还原成独立顶层窗口（任务栏冒出 chrome），
     * 再重新 `SetParent` 收进来（界面转圈几秒）。
     *
     * 现在：根 Box 与槽位层**永远存在**，只有各自的 modifier 随状态变化。
     * 节点不重建 → 窗口只是被 `setBounds` 挪位置。
     */
    Box(
        modifier = modifier
            .width(if (state.panelOpen) state.panelWidthDp.dp else COLLAPSED_WIDTH)
            .fillMaxHeight(),
    ) {
        // ── 骨架（收起时是窄条，展开时是抽屉外壳）────────────────────────────
        if (!state.panelOpen) {
            CollapsedRail(onTogglePanel = onTogglePanel, state = state)
            HorizontalDivider(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(1.dp),
            )
        } else {
            // 宽度必须**先**定下来：写成 `fillMaxSize().width(...)` 会让 fillMaxSize 先占满，
            // width 被无声忽略，抽屉吃满整个内容区（实测过）。
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                Row(Modifier.fillMaxSize()) {
                    ResizeHandle(widthDp = state.panelWidthDp, onResize = onResize)
                    Column(Modifier.fillMaxSize()) {
                        // 标题行 = 「浏览器N」页签 + 右侧操作按钮。**没有单独的标题文字**，
                        // 也不再有独立的一行页签 —— 两只合一行，省 44dp 垂直空间。
                        Header(
                            state = state,
                            onSelectTarget = onSelectTarget,
                            onRefresh = onRefresh,
                            onOpenInSystemBrowser = onOpenInSystemBrowser,
                            onCollapse = onTogglePanel,
                        )
                        HorizontalDivider()
                        if (state.lastError != null && state.phase !is Phase.Failed) {
                            ErrorStrip(state.lastError, onDismissError)
                        }
                        // 内容区：已纳管时留给槽位（原生子窗口覆盖在上面，这块 Compose 只撑位置）。
                        if (!state.takenOver) {
                            // **必须有不透明背景**：phase 变化时（如 Searching → NoBrowser，
                            // 转圈消失导致整块文字下移），Skiko 只重绘**新**文字的区域，
                            // 旧文字的像素会留在原位，与新文字叠成"残缺"字样（实测）。
                            // 铺一层 surface 底色可以保证每次重绘都覆盖旧内容。
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(8.dp),
                            ) {
                                Placeholder(
                                    phase = state.phase,
                                    onRefresh = onRefresh,
                                    onOpenBrowserSettings = onOpenBrowserSettings,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 槽位层：**永远存在**，父链恒定，只改每个槽位的 modifier ──────────
        //
        // 收起时整体挪出可视区（不是 0 尺寸：0 尺寸会让 Chromium 的边框在 (0,0)
        // 露一条竖线，实测过）；展开时当前那只铺满内容区，其余仍挪在屏幕外。
        //
        // **顶部要让开所有 Compose 覆盖层**（标题栏 + 页签行 + 错误条）：
        // 原生子窗口永远画在 Compose 之上，padding 少算一行就会被它整个盖住
        // （实测：页签行明明渲染了，却被 Chrome 窗口压住看不见）。
        //
        // 用**固定常量**而不是按实际显示的行数动态计算：`NativeView` 的 bounds 由
        // Compose 布局位置决定，padding 一变就会 `setBounds` 重排；动态值会让
        // 「有无错误条」这类无关状态触发画面跳动。
        state.targets.forEach { target ->
            key(target.pid) {
                val visible = state.panelOpen && target.pid == state.activeTarget?.pid
                Box(
                    modifier = if (visible) {
                        Modifier
                            .padding(start = HANDLE_WIDTH, top = SLOT_TOP_INSET)
                            .fillMaxSize()
                    } else {
                        Modifier.size(MIN_SLOT_SIZE).offset(x = HIDDEN_SLOT_OFFSET)
                    },
                ) {
                    content(target.pid, Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** 收起态：一条窄竖条，点它展开。纳管中时点一个小圆点表示"有画面待看"。 */
@Composable
private fun CollapsedRail(
    onTogglePanel: () -> Unit,
    state: BrowserState,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onTogglePanel, modifier = Modifier.padding(top = 8.dp)) {
                Icon(AppIcons.Sessions, contentDescription = "展开浏览器面板")
            }
            if (state.takenOver) {
                // 纳管中但抽屉关着：一个状态点，暗示"这里有东西可看"。
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
                )
            }
        }
    }
}

/**
 * 左边缘的拖拽手柄：往左拖 = 变宽（抽屉在右侧）。
 *
 * 用手柄而不是让整个抽屉边缘可拖：`NativeView` 的子窗口在自己的矩形内接管鼠标，
 * Compose 收不到那里的事件；只有在**槽位之外**（标题栏 / 手柄）才拿得到。
 *
 * ### 两个必须的细节
 *
 * 1. **`pointerInput` 的 key 不能是 `widthDp`**。它一变，整个拖拽手势识别器会重建，
 *    正在进行的拖拽被中断 —— 表现为"一次只能拖一点点，松手再拖"（实测踩过）。
 *    改为 `Unit`，并用 `rememberUpdatedState` 取最新的宽度。
 * 2. 起始宽度只在 `onDragStart` 读一次，之后靠累积位移算目标宽度，
 *    避免 `widthDp` 变化反过来影响正在进行的拖拽。
 */
@Composable
private fun ResizeHandle(
    widthDp: Int,
    onResize: (Int) -> Unit,
) {
    val density = LocalDensity.current
    // 拖拽期间不变的两个值：起始宽度（onDragStart 读一次）与累积位移。
    val startWidth = remember { mutableIntStateOf(widthDp) }
    val accumulated = remember { mutableFloatStateOf(0f) }
    // 手势协程里要读到最新的回调，但不该因为它变化而重启手势。
    val latestOnResize by rememberUpdatedState(onResize)

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(HANDLE_WIDTH)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .resizeCursor()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        startWidth.intValue = widthDp
                        accumulated.floatValue = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        accumulated.floatValue += dragAmount.x
                        // 抽屉在右侧：鼠标往左移（负方向）= 变宽。
                        val deltaDp = with(density) { -accumulated.floatValue.toDp().value }
                        val target = startWidth.intValue + deltaDp
                        latestOnResize(
                            target.roundToInt().coerceIn(BrowserState.MIN_WIDTH_DP, BrowserState.MAX_WIDTH_DP),
                        )
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        VerticalDivider()
    }
}

/**
 * 标题行：左边是「浏览器 N」页签，右边是操作按钮。
 *
 * **没有独立的标题文字**，也没有单独一行页签 —— 两者合成一行，省 44dp 垂直空间
 * （抽屉本来就窄，垂直空间更值钱）。页签固定叫「浏览器1」「浏览器2」，不显示域名：
 * 抽屉窄，域名会被截成没有信息量的前缀，而"第几只"才是切换时真正需要的区分。
 *
 * 页签用 `ScrollableTabRow`：只养一两只时看不出差别，但多开几个时能横向滚动，
 * 不会像 `TabRow` 那样把每个压到看不清。
 */
@Composable
private fun Header(
    state: BrowserState,
    onSelectTarget: (Long) -> Unit,
    onRefresh: () -> Unit,
    onOpenInSystemBrowser: () -> Unit,
    onCollapse: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(HEADER_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 页签区：占满左侧可用宽度（`weight(1f)`），按钮固定在右边。
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (state.targets.isEmpty()) {
                // 没有实例时显示状态文字，保持这一行不塌陷（面板结构稳定）。
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(state.phase)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = when (state.phase) {
                            Phase.Idle, Phase.Searching -> "正在查找…"
                            Phase.NoBrowser -> "浏览器"
                            is Phase.Headless -> "headless 模式"
                            Phase.Unsupported -> "不支持"
                            is Phase.Failed -> "发现失败"
                            Phase.TakenOver -> "浏览器"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                BrowserTabs(
                    targets = state.targets,
                    activePid = state.activeTarget?.pid,
                    onSelect = onSelectTarget,
                )
            }
        }

        IconButton(onClick = onRefresh) {
            Icon(AppIcons.Refresh, contentDescription = "重新连接")
        }
        IconButton(onClick = onOpenInSystemBrowser, enabled = state.takenOver) {
            Icon(AppIcons.Export, contentDescription = "在系统浏览器打开")
        }
        IconButton(onClick = onCollapse) {
            Icon(AppIcons.ExpandLess, contentDescription = "收起")
        }
    }
}

/**
 * 页签：**「浏览器1」「浏览器2」**，不显示域名。
 *
 * 为什么不用域名：抽屉窄，`https://example.com/a/b?c=d` 截下来只剩 `https://…`，
 * 没有信息量；而"第几只"是切换时唯一定位手段。序号按 `targets` 顺序（= 纳管顺序）。
 */
@Composable
private fun BrowserTabs(
    targets: List<dev.shinglem.hermes.component.browser.BrowserTarget>,
    activePid: Long?,
    onSelect: (Long) -> Unit,
) {
    val selectedIndex = targets.indexOfFirst { it.pid == activePid }.coerceAtLeast(0)

    ScrollableTabRow(
        selectedTabIndex = selectedIndex,
        edgePadding = 4.dp,
        divider = {},
        indicator = { tabPositions ->
            if (selectedIndex < tabPositions.size) {
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
    ) {
        targets.forEachIndexed { index, target ->
            Tab(
                selected = index == selectedIndex,
                onClick = { onSelect(target.pid) },
                // 页签高度撑满标题行（默认 Tab 有 48dp 最小高度约束，这里正好）。
                text = {
                    Text(
                        text = "浏览器${index + 1}",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

@Composable
private fun StatusDot(phase: Phase) {
    val color = when (phase) {
        is Phase.TakenOver -> MaterialTheme.colorScheme.primary
        Phase.Searching, Phase.Idle -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.error
    }
    Box(
        Modifier
            .padding(start = 8.dp)
            .size(8.dp)
            .background(color, RoundedCornerShape(50)),
    )
}

@Composable
private fun ErrorStrip(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(AppIcons.Close, contentDescription = "关闭")
        }
    }
}

/** 未纳管时的说明（四种原因给四种话）。 */
@Composable
private fun Placeholder(
    phase: Phase,
    onRefresh: () -> Unit,
    onOpenBrowserSettings: () -> Unit,
) {
    when (phase) {
        Phase.Idle, Phase.Searching -> CenteredMessage(
            title = "正在查找 agent 的浏览器…",
            detail = null,
            action = null,
            loading = true,
        )

        Phase.NoBrowser -> CenteredMessage(
            title = "agent 还没打开浏览器",
            detail = "它开始访问网页时，这里会自动接上画面。",
            action = "重新连接" to onRefresh,
            loading = false,
        )

        is Phase.Headless -> CenteredMessage(
            title = "agent 浏览器是 headless 模式，看不到画面",
            detail = "要看到画面，需要让后端以 headed 模式启动浏览器。",
            action = "打开浏览器设置" to onOpenBrowserSettings,
            loading = false,
        )

        Phase.Unsupported -> CenteredMessage(
            title = "当前平台不支持内嵌浏览器画面",
            detail = "该能力依赖 Windows 的原生子窗口。",
            action = null,
            loading = false,
        )

        is Phase.Failed -> CenteredMessage(
            title = "无法显示浏览器画面",
            detail = phase.reason,
            action = "重试" to onRefresh,
            loading = false,
        )

        // 已纳管时不会走到这里（内容区留给槽位）。
        is Phase.TakenOver -> CenteredMessage(
            title = "画面已纳管",
            detail = null,
            action = null,
            loading = false,
        )
    }
}

@Composable
private fun CenteredMessage(
    title: String,
    detail: String?,
    action: Pair<String, () -> Unit>?,
    loading: Boolean,
) {
    // **布局必须稳定**：转圈出现/消失不能改变文字位置。
    //
    // 之前是 `if (loading) { 转圈 + Spacer }` 然后文字，`Arrangement.Center` 会让整块内容
    // 随内容高度变化上下移动 —— Searching → NoBrowser 时文字整体下移约 40dp，
    // Skiko 只重绘新区域，旧文字的像素留在原地，叠成"残缺"字样（实测反馈）。
    //
    // 现在转圈区域**始终占位**（不加载时用等高的空白），文字位置恒定。
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = action.second) { Text(action.first) }
        }
    }
}

private val HEADER_HEIGHT = 48.dp

/** 错误条（单行）的高度：槽位顶部要让开它。 */
private val ERROR_STRIP_HEIGHT = 32.dp
private val COLLAPSED_WIDTH = 40.dp

/** 拖拽手柄宽度。 */
private val HANDLE_WIDTH = 6.dp


/**
 * 槽位顶部要让开的高度 = 标题行（含页签）+ 错误条（按**最大**情况取固定值）。
 *
 * 页签已经合进标题行，所以不再额外算一行。
 *
 * 不能按当前实际显示的行数动态算：`NativeView` 的 bounds 跟着 Compose 布局走，
 * 值一变就 `setBounds` 重排，会让「有没有错误条」这种无关状态引起画面跳动。
 */
private val SLOT_TOP_INSET = HEADER_HEIGHT + ERROR_STRIP_HEIGHT

/**
 * 收起时把槽位挪到屏幕外。
 *
 * `NativeView` 会把原生子窗口按 Compose 布局的位置 `setBounds`，所以「尺寸 0」不够 ——
 * Chromium 的窗口带着边框，会在 (0,0) 露出一条竖线。用一个大负偏移让它彻底离开可视区。
 */
private val HIDDEN_SLOT_OFFSET = (-20000).dp

/** 收起时给槽位的最小合法尺寸（0 尺寸会让某些原生窗口计算异常）。 */
private val MIN_SLOT_SIZE = 1.dp