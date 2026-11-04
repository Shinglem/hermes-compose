package dev.shinglem.hermes.feature.session

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.time.TimeFormat
import dev.shinglem.hermes.ui.theme.HermesTheme
import kotlin.test.Test
import kotlin.time.Clock

@OptIn(ExperimentalTestApi::class)
class SessionListPaneTest {
    private fun session(id: String, title: String) = SessionSummary(
        id = id,
        title = title,
        preview = "",
        startedAt = Clock.System.now(),
        messageCount = 1,
        source = "desktop",
    )

    @Test
    fun `加载中显示骨架行`() = runComposeUiTest {
        setContent {
            HermesTheme {
                SessionListPane(
                    state = SessionListState(load = Loadable.Loading),
                    callbacks = SessionListCallbacks(
                        onQueryChange = {},
                        onSelect = {},
                        onToggleGroup = {},
                        onTogglePinnedGroup = {},
                        onRetry = {},
                        onCreate = {},
                        onDismissCreateError = {},
                        onDismissRowError = {},
                        onStartRename = {},
                        onRenameTitleChange = {},
                        onCommitRename = {},
                        onCancelRename = {},
                        onTogglePin = {},
                        onAskArchive = {},
                        onCommitArchive = {},
                        onCancelArchive = {},
                        onToggleArchivedSection = {},
                        onRestoreArchived = {},
                        onAskDelete = {},
                        onCommitDelete = {},
                        onCancelDelete = {},
                    ),
                )
            }
        }
        onNodeWithText("加载会话…").assertIsDisplayed()
    }

    @Test
    fun `空列表显示空态`() = runComposeUiTest {
        setContent {
            HermesTheme {
                SessionListPane(
                    state = SessionListState(load = Loadable.Ready(emptyList())),
                    callbacks = SessionListCallbacks(
                        onQueryChange = {},
                        onSelect = {},
                        onToggleGroup = {},
                        onTogglePinnedGroup = {},
                        onRetry = {},
                        onCreate = {},
                        onDismissCreateError = {},
                        onDismissRowError = {},
                        onStartRename = {},
                        onRenameTitleChange = {},
                        onCommitRename = {},
                        onCancelRename = {},
                        onTogglePin = {},
                        onAskArchive = {},
                        onCommitArchive = {},
                        onCancelArchive = {},
                        onToggleArchivedSection = {},
                        onRestoreArchived = {},
                        onAskDelete = {},
                        onCommitDelete = {},
                        onCancelDelete = {},
                    ),
                )
            }
        }
        onNodeWithText("还没有会话").assertIsDisplayed()
    }

    @Test
    fun `加载失败且无缓存时显示错误与重试`() = runComposeUiTest {
        setContent {
            HermesTheme {
                SessionListPane(
                    state = SessionListState(load = Loadable.Failed(AppError.BackendUnreachable)),
                    callbacks = SessionListCallbacks(
                        onQueryChange = {},
                        onSelect = {},
                        onToggleGroup = {},
                        onTogglePinnedGroup = {},
                        onRetry = {},
                        onCreate = {},
                        onDismissCreateError = {},
                        onDismissRowError = {},
                        onStartRename = {},
                        onRenameTitleChange = {},
                        onCommitRename = {},
                        onCancelRename = {},
                        onTogglePin = {},
                        onAskArchive = {},
                        onCommitArchive = {},
                        onCancelArchive = {},
                        onToggleArchivedSection = {},
                        onRestoreArchived = {},
                        onAskDelete = {},
                        onCommitDelete = {},
                        onCancelDelete = {},
                    ),
                )
            }
        }
        onNodeWithText("连不上后端，请确认 Hermes 已在运行。").assertIsDisplayed()
        onNodeWithText("重试").assertIsDisplayed()
    }

    @Test
    fun `有数据时显示分组标题与会话标题`() = runComposeUiTest {
        setContent {
            HermesTheme {
                SessionListPane(
                    state = SessionListState(load = Loadable.Ready(listOf(session("a", "真实会话标题")))),
                    callbacks = SessionListCallbacks(
                        onQueryChange = {},
                        onSelect = {},
                        onToggleGroup = {},
                        onTogglePinnedGroup = {},
                        onRetry = {},
                        onCreate = {},
                        onDismissCreateError = {},
                        onDismissRowError = {},
                        onStartRename = {},
                        onRenameTitleChange = {},
                        onCommitRename = {},
                        onCancelRename = {},
                        onTogglePin = {},
                        onAskArchive = {},
                        onCommitArchive = {},
                        onCancelArchive = {},
                        onToggleArchivedSection = {},
                        onRestoreArchived = {},
                        onAskDelete = {},
                        onCommitDelete = {},
                        onCancelDelete = {},
                    ),
                )
            }
        }
        onNodeWithText("今天").assertIsDisplayed()
        onNodeWithText("真实会话标题").assertIsDisplayed()
    }
}
