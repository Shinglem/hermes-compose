package dev.shinglem.hermes.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.component.chat.ChatIntent
import dev.shinglem.hermes.component.subagent.SubagentIntent
import dev.shinglem.hermes.di.AppContainer
import dev.shinglem.hermes.domain.model.SessionSummary
import dev.shinglem.hermes.feature.chat.ChatPane
import dev.shinglem.hermes.feature.chat.ChatState
import dev.shinglem.hermes.feature.composer.ComposerController
import dev.shinglem.hermes.feature.composer.ModelPill
import dev.shinglem.hermes.feature.composer.ReasoningPill
import dev.shinglem.hermes.feature.editors.EditorLaunchContext
import dev.shinglem.hermes.feature.editors.LocalEditorLaunchContext
import dev.shinglem.hermes.feature.session.SessionListCallbacks
import dev.shinglem.hermes.feature.session.SessionListPane
import dev.shinglem.hermes.feature.terminal.TerminalPanel
import dev.shinglem.hermes.feature.terminal.TerminalPanelState
import dev.shinglem.hermes.feature.terminal.TerminalTabVm
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.chat_untitled
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** 会话模式的中栏 + 对话 + 终端（原 `AppShell` 的 `ShellMode.SESSIONS` 分支）。 */
@Composable
fun SessionsModeContent(
    deps: AppShellDeps,
    container: AppContainer,
    selectedSession: SessionSummary?,
    terminalState: TerminalPanelState,
    terminalContent: @Composable (TerminalTabVm, Modifier) -> Unit,
    onQuoteTerminalSelection: (() -> Unit)?,
    composerScope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val shellState = deps.shellState
    val sessionState = deps.sessionState
    val sessionViewModel = deps.sessionViewModel
    val chatComponent = deps.chatComponent
    val chatState = deps.chatState
    val composerViewModel = deps.composerViewModel
    val composerState = deps.composerState
    val settingsState = deps.settingsState
    val subagentComponent = deps.subagentComponent
AdaptiveListDetail(
        modifier = modifier,
                    list = { onNavigateToDetail ->
                        SessionListPane(
                            state = sessionState,
                            callbacks = SessionListCallbacks(
                                onQueryChange = sessionViewModel::setQuery,
                                onSelect = { id ->
                                    sessionViewModel.select(id)
                                    onNavigateToDetail()
                                },
                                onToggleGroup = sessionViewModel::toggleGroup,
                                onTogglePinnedGroup = sessionViewModel::togglePinnedGroup,
                                onRetry = { sessionViewModel.refresh(shellState.activeProfile) },
                                onCreate = { sessionViewModel.create(shellState.activeProfile) },
                                onDismissCreateError = sessionViewModel::dismissCreateError,
                                onDismissRowError = sessionViewModel::dismissRowError,
                                onStartRename = sessionViewModel::startRename,
                                onRenameTitleChange = sessionViewModel::updateRenameDraft,
                                onCommitRename = sessionViewModel::commitRename,
                                onCancelRename = sessionViewModel::cancelRename,
                                onTogglePin = sessionViewModel::togglePin,
                                onAskArchive = sessionViewModel::askArchive,
                                onCommitArchive = sessionViewModel::commitArchive,
                                onCancelArchive = sessionViewModel::cancelArchive,
                                onToggleArchivedSection = sessionViewModel::toggleArchivedSection,
                                onRestoreArchived = sessionViewModel::restoreArchived,
                                onAskDelete = sessionViewModel::askDelete,
                                onCommitDelete = sessionViewModel::commitDelete,
                                onCancelDelete = sessionViewModel::cancelDelete,
                                onDuplicate = sessionViewModel::duplicate,
                                onExportJson = { id -> sessionViewModel.exportSession(id, markdown = false) },
                                onExportMarkdown = { id -> sessionViewModel.exportSession(id, markdown = true) },
                                onContentQueryChange = sessionViewModel::setContentQuery,
                                onDismissNotice = sessionViewModel::dismissNotice,
                            ),
                        )
                    },
                    detail = { singlePane, onBack ->
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (singlePane) {
                                DetailBackBar(
                                    title = selectedSession?.displayTitle
                                        ?: stringResource(Res.string.chat_untitled),
                                    onBack = onBack,
                                )
                            }
                            CompositionLocalProvider(
                                LocalEditorLaunchContext provides EditorLaunchContext(
                                    editors = settingsState.editors,
                                    cwd = chatState.cwd,
                                    userHome = remember { System.getProperty("user.home").orEmpty() },
                                ),
                            ) {
                            ChatPane(
                                summary = selectedSession,
                                state = chatState,
                                composer = remember(composerState) {
                                    ComposerController(
                                        state = composerState,
                                        onValueChange = composerViewModel::onValueChange,
                                        onAcceptCompletion = composerViewModel::acceptCompletion,
                                        onMoveCompletion = composerViewModel::moveSelection,
                                        onDismissCompletion = composerViewModel::dismissCompletion,
                                        onStepHistory = composerViewModel::stepHistory,
                                        onPasteSpecial = { text, selection ->
                                            composerScope.launch {
                                                composerViewModel.handlePasteSpecial(text, selection)
                                            }
                                        },
                                        onPickFiles = {
                                            composerScope.launch { composerViewModel.pickAndAttach() }
                                        },
                                        onDropFiles = { paths ->
                                            composerScope.launch { composerViewModel.attachPaths(paths) }
                                        },
                                    )
                                },
                                onRetry = {
                                    val id = sessionState.selectedId
                                    // 刚新建的会话还没有 transcript，重新加载只会再拿一次 404。
                                    if (id != null && id == sessionState.justCreatedId) {
                                        chatComponent.onIntent(ChatIntent.AdoptEmpty(shellState.activeProfile, id))
                                    } else {
                                        chatComponent.onIntent(ChatIntent.Reload(shellState.activeProfile, id))
                                    }
                                },
                                onReload = {
                                    val id = sessionState.selectedId
                                    if (id != null && id == sessionState.justCreatedId) {
                                        chatComponent.onIntent(ChatIntent.AdoptEmpty(shellState.activeProfile, id))
                                    } else {
                                        chatComponent.onIntent(ChatIntent.Reload(shellState.activeProfile, id))
                                    }
                                },
                                onSend = { text ->
                                    // 附件必须随这一轮提交：`image.attach_bytes` 在 gateway 路线下
                                    // 只把字节落进 <profile>/cache/images，不建队列，
                                    // 不回传就永远到不了模型（待修复问题 #19）。
                                    val attachments = composerViewModel.state.value.attachments
                                        .mapNotNull { it.toPromptAttachment() }
                                    chatComponent.onIntent(ChatIntent.Send(text, attachments))
                                    composerViewModel.markSent(text)
                                },
                                onStop = { chatComponent.onIntent(ChatIntent.Stop) },
                                onApprove = { id, choice -> chatComponent.onIntent(ChatIntent.ResolveApproval(id, choice)) },
                                onClarifyAnswer = { id, answers -> chatComponent.onIntent(ChatIntent.AnswerClarify(id, answers)) },
                                onClarifyCancel = { id -> chatComponent.onIntent(ChatIntent.CancelClarify(id)) },
                                onQuote = composerViewModel::quote,
                                modifier = Modifier.weight(1f),
                                onConfirmModelSwitch = { chatComponent.onIntent(ChatIntent.ConfirmModelSwitch) },
                                onDismissModelConfirm = { chatComponent.onIntent(ChatIntent.DismissModelConfirm) },
                                onDismissModelError = { chatComponent.onIntent(ChatIntent.DismissModelError) },
                                onLoadOlder = { chatComponent.onIntent(ChatIntent.LoadOlder) },
                                onRewind = { item ->
                                    // 历史消息的 id 是后端 row id；本地刚发的 `local:` 不能拿去截断。
                                    if (!item.id.startsWith("local:")) {
                                        chatComponent.onIntent(ChatIntent.BeginRewind(item.id, item.text))
                                    }
                                },
                                onBranch = { item ->
                                    if (!item.id.startsWith("local:")) {
                                        val title = selectedSession?.displayTitle.orEmpty()
                                        chatComponent.onIntent(ChatIntent.BeginBranch(item.id, title))
                                    }
                                },
                                onConfirmRewind = { chatComponent.onIntent(ChatIntent.ConfirmRewind) },
                                onCancelRewind = { chatComponent.onIntent(ChatIntent.CancelRewind) },
                                onConfirmBranch = { chatComponent.onIntent(ChatIntent.ConfirmBranch) },
                                onCancelBranch = { chatComponent.onIntent(ChatIntent.CancelBranch) },
                                onShowUsageDetail = { chatComponent.onIntent(ChatIntent.LoadUsageDetail) },
                                onDismissUsageError = { chatComponent.onIntent(ChatIntent.DismissUsageError) },
                                onOpenSubagents = { subagentComponent.onIntent(SubagentIntent.Open) },
                                onKillBackgroundTask = { id -> chatComponent.onIntent(ChatIntent.KillBackgroundTask(id)) },
                                onSteerQueued = { id -> chatComponent.onIntent(ChatIntent.SteerQueued(id)) },
                                onCancelQueued = { id -> chatComponent.onIntent(ChatIntent.CancelQueued(id)) },
                                onReconnect = { chatComponent.onIntent(ChatIntent.Reconnect) },
                                onDismissBackgroundTask = { id -> chatComponent.onIntent(ChatIntent.DismissBackgroundTask(id)) },
                                modelBar = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        ModelPill(
                                            catalog = chatState.modelCatalog,
                                            model = chatState.model,
                                            provider = chatState.provider,
                                            loading = chatState.modelCatalogLoading,
                                            busy = chatState.modelSwitching,
                                            enabled = true,
                                            onRequestCatalog = { chatComponent.onIntent(ChatIntent.LoadModelCatalog) },
                                            onSelect = { entry ->
                                                chatComponent.onIntent(ChatIntent.SwitchModel(entry.provider, entry.model))
                                            },
                                        )
                                        ReasoningPill(
                                            effort = chatState.reasoningEffort,
                                            supported = chatState.modelCatalog
                                                ?.reasoningSupported(chatState.provider, chatState.model)
                                                ?: true,
                                            busy = chatState.modelSwitching,
                                            enabled = true,
                                            onSelect = { effort -> chatComponent.onIntent(ChatIntent.SetReasoning(effort)) },
                                        )
                                    }
                                },
                            )
                            }
                            TerminalPanel(
                                state = terminalState,
                                onToggle = container.terminalViewModel::toggle,
                                onNewTab = { container.terminalViewModel.newTab(null) },
                                onSelectTab = container.terminalViewModel::selectTab,
                                onCloseTab = container.terminalViewModel::closeTab,
                                onHeightFraction = container.terminalViewModel::setHeightFraction,
                                content = terminalContent,
                                onRenameTab = container.terminalViewModel::renameTab,
                                onQuoteSelection = onQuoteTerminalSelection,
                            )
                        }
                    },
                )
}
