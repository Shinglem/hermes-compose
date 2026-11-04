package dev.shinglem.hermes.feature.terminal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 终端面板：收起时 32dp 标题条，展开时上方是内容区（高度可拖拽）+ 左侧标签 rail。
 *
 * 面板本身不持有 PTY，内容由 [content] 插槽提供（desktop 侧注入 `:modules:terminal`
 * 的 `TerminalHost`），这样 commonMain 不依赖 Swing/AWT。
 */
@Composable
fun TerminalPanel(
    state: TerminalPanelState,
    onToggle: () -> Unit,
    onNewTab: () -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onHeightFraction: (Float) -> Unit,
    content: @Composable (TerminalTabVm, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    /** 双击标签改名；空标题表示恢复默认（显示序号）。 */
    onRenameTab: (String, String) -> Unit = { _, _ -> },
    /** 「引用选中内容到输入框」：null = 不显示这个入口。 */
    onQuoteSelection: (() -> Unit)? = null,
) {
    val layout = state.layout
    val activeTab = state.activeTab
    var renaming by remember { mutableStateOf<TerminalTabVm?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    val closeRename = {
        renaming = null
    }
    Column(modifier = modifier.fillMaxWidth()) {
        if (layout.expanded && activeTab != null) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val maxHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(maxHeight * layout.heightFraction),
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        TerminalRail(
                            tabs = layout.tabs,
                            activeTabId = activeTab.id,
                            running = state.running,
                            onSelect = onSelectTab,
                            onClose = onCloseTab,
                            onNewTab = onNewTab,
                            onRename = { tab ->
                                renaming = tab
                                renameDraft = tab.title
                            },
                        )
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            content(activeTab, Modifier.fillMaxSize())
                        }
                    }
                }
                // 上边缘拖拽：向上拖变大（在 detail 列里就是"往上占"）。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .align(Alignment.TopCenter)
                        .pointerInput(maxHeightPx) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                onHeightFraction(layout.heightFraction - drag.y / maxHeightPx)
                            }
                        },
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TerminalBar(
            running = state.running,
            expanded = layout.expanded,
            tabCount = layout.tabs.size,
            onToggle = onToggle,
            onNewTab = onNewTab,
            onQuoteSelection = onQuoteSelection?.takeIf { layout.expanded && activeTab != null },
        )
    }

    renaming?.let { tab ->
        AlertDialog(
            onDismissRequest = closeRename,
            title = { Text("重命名终端标签") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    singleLine = true,
                    label = { Text("标签名（留空恢复序号）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameTab(tab.id, renameDraft.trim())
                        closeRename()
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = closeRename) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TerminalBar(
    running: Int,
    expanded: Boolean,
    tabCount: Int,
    onToggle: () -> Unit,
    onNewTab: () -> Unit,
    onQuoteSelection: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth().height(32.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (running > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = CircleShape,
                    ),
            )
            Text(
                text = "终端",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (running > 0) {
                Text(
                    text = "$running",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            if (onQuoteSelection != null) {
                IconButton(onClick = onQuoteSelection, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = dev.shinglem.hermes.ui.icon.AppIcons.Quote,
                        contentDescription = "引用选中内容到输入框",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            IconButton(onClick = onNewTab, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "新建终端",
                    modifier = Modifier.size(16.dp),
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                contentDescription = if (expanded) "收起终端" else "展开终端",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TerminalRail(
    tabs: List<TerminalTabVm>,
    activeTabId: String,
    running: Int,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onRename: (TerminalTabVm) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            modifier = Modifier.width(40.dp).fillMaxHeight().padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = tab.id == activeTabId
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    modifier = Modifier
                        .size(width = 32.dp, height = 28.dp)
                        .combinedClickable(
                            onClick = { onSelect(tab.id) },
                            onDoubleClick = { onRename(tab) },
                        ),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = tab.title.take(1).ifBlank { "${index + 1}" },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
            if (tabs.size > 1) {
                IconButton(onClick = { onClose(activeTabId) }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭终端",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (running > 0) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
    }
}
