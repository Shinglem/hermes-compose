package dev.shinglem.hermes.feature.shell

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** 单栏退化的分界：低于此宽度只显示一个窗格（M3 adaptive 的 Expanded 下界）。 */
private val SinglePaneBreakpoint = 840.dp

/** 宽窗分界：高于此宽度 pane 用更大的首选宽度（M3 adaptive 的 Large 下界）。 */
private val WideBreakpoint = 1200.dp

/**
 * 两窗格 list-detail 骨架（列表 + 详情）。
 *
 * 左侧 rail 留在 scaffold **外面**：`extraPane` 的可见性由 `maxHorizontalPartitions` 决定，
 * 窄窗时它会被 scaffold 收起，而 rail 是导航（切 profile / 切模式），不能消失。见 ADR-037。
 *
 * CMP-8735（desktop 上不随窗口 resize 重算 directive）用 `key(宽度档位)` 绕开：
 * 跨 840 / 1200 两个断点时重建 navigator，中间 resize 不重排。
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AdaptiveListDetail(
    list: @Composable (onNavigateToDetail: () -> Unit) -> Unit,
    detail: @Composable (singlePane: Boolean, onBack: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    listPaneWidth: Dp? = null,
) {
    val density = LocalDensity.current
    val width = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val singlePane = width < SinglePaneBreakpoint
    val wide = width >= WideBreakpoint

    key(singlePane, wide, listPaneWidth) {
        val base = calculatePaneScaffoldDirective(adaptiveInfo)
        val directive = if (listPaneWidth == null) base else PaneScaffoldDirective(
            maxHorizontalPartitions = base.maxHorizontalPartitions,
            horizontalPartitionSpacerSize = base.horizontalPartitionSpacerSize,
            maxVerticalPartitions = base.maxVerticalPartitions,
            verticalPartitionSpacerSize = base.verticalPartitionSpacerSize,
            defaultPanePreferredWidth = listPaneWidth,
            defaultPanePreferredHeight = base.defaultPanePreferredHeight,
            excludedBounds = base.excludedBounds,
            shouldAutoFocusCurrentDestination = base.shouldAutoFocusCurrentDestination,
        )
        val navigator = rememberListDetailPaneScaffoldNavigator<Unit>(
            scaffoldDirective = directive,
        )
        val scope = rememberCoroutineScope()

        ListDetailPaneScaffold(
            directive = navigator.scaffoldDirective,
            scaffoldState = navigator.scaffoldState,
            modifier = modifier,
            listPane = {
                list {
                    scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail) }
                }
            },
            detailPane = {
                detail(singlePane) {
                    scope.launch { navigator.navigateBack() }
                }
            },
        )
    }
}
