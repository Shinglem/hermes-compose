package dev.shinglem.hermes.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.time.TimeFormat
import dev.shinglem.hermes.feature.composer.ComposerController
import dev.shinglem.hermes.feature.composer.ComposerUiState
import dev.shinglem.hermes.feature.session.SessionSummary
import dev.shinglem.hermes.markdown.MarkdownText
import dev.shinglem.hermes.ui.theme.HermesTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ChatPaneTest {
    private val summary = SessionSummary(
        id = "20261026_104822_7008be",
        title = "测试会话",
        preview = "预览",
        startedAt = TimeFormat.fromEpochSeconds(1_790_000_000.0),
        messageCount = 2,
        source = "desktop",
    )

    private fun pane(
        state: ChatState,
        session: SessionSummary? = summary,
        onKillBackgroundTask: (String) -> Unit = {},
    ): @Composable () -> Unit = paneOf({ state }, session, onKillBackgroundTask)

    /** 同一份界面但状态是**每次组合重新读**的：用来模拟流式输出一段段到达。 */
    private fun paneOf(
        state: () -> ChatState,
        session: SessionSummary? = summary,
        onKillBackgroundTask: (String) -> Unit = {},
    ): @Composable () -> Unit = {
        val composer = ComposerController(
            state = ComposerUiState(),
            onValueChange = {},
            onAcceptCompletion = { false },
            onMoveCompletion = {},
            onDismissCompletion = {},
            onStepHistory = { false },
            onPasteSpecial = { _, _ -> },
            onPickFiles = {},
            onDropFiles = {},
        )
        HermesTheme {
            ChatPane(
                summary = session,
                state = state(),
                composer = composer,
                onRetry = {},
                onReload = {},
                onSend = {},
                onStop = {},
                onApprove = { _, _ -> },
                onKillBackgroundTask = onKillBackgroundTask,
            )
        }
    }

    @Test
    fun `未选会话显示空态`() = runComposeUiTest {
        setContent(pane(ChatState(), session = null))
        onNodeWithText("选择一个会话").assertIsDisplayed()
    }

    @Test
    fun `加载中显示提示`() = runComposeUiTest {
        setContent(pane(ChatState(load = Loadable.Loading)))
        onNodeWithText("加载消息…").assertIsDisplayed()
    }

    @Test
    fun `空会话显示空态`() = runComposeUiTest {
        setContent(pane(ChatState(load = Loadable.Ready(emptyList()))))
        onNodeWithText("这个会话还没有消息").assertIsDisplayed()
    }

    @Test
    fun `加载失败显示错误与重试`() = runComposeUiTest {
        setContent(pane(ChatState(load = Loadable.Failed(AppError.BackendUnreachable))))
        onNodeWithText("连不上后端，请确认 Hermes 已在运行。").assertIsDisplayed()
        onNodeWithText("重试").assertIsDisplayed()
    }

    @Test
    fun `渲染用户与助手消息`() = runComposeUiTest {
        val items = listOf(
            ChatItem.UserMessage("u1", null, "你好，世界"),
            ChatItem.AssistantMessage("a1", null, "收到，马上处理"),
        )
        setContent(pane(ChatState(load = Loadable.Ready(items))))
        // Markdown 解析在后台线程完成，等它落地再断言。
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("你好，世界", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("你好，世界", substring = true).assertIsDisplayed()
        onNodeWithText("收到，马上处理", substring = true).assertIsDisplayed()
    }

    @Test
    fun `工具卡片显示标签与状态`() = runComposeUiTest {
        val items = listOf(
            ChatItem.Tool(
                id = "call:1",
                timestamp = null,
                name = "terminal",
                label = "Running command · ls",
                arguments = "{}",
                result = "a.txt",
                failed = false,
                pairedWithCall = true,
            ),
        )
        setContent(pane(ChatState(load = Loadable.Ready(items))))
        onNodeWithText("Running command · ls").assertIsDisplayed()
        onNodeWithText("已完成").assertIsDisplayed()
    }

    @Test
    fun `展开工具卡片显示参数与结果`() = runComposeUiTest {
        val items = listOf(
            ChatItem.Tool(
                id = "call:1",
                timestamp = null,
                name = "terminal",
                label = "Running command · ls",
                arguments = "{\"cmd\":\"ls\"}",
                result = "a.txt",
                failed = false,
                pairedWithCall = true,
            ),
        )
        setContent(pane(ChatState(load = Loadable.Ready(items))))
        onNodeWithText("Running command · ls").performClick()
        onNodeWithText("参数").assertIsDisplayed()
        onNodeWithText("结果").assertIsDisplayed()
        onNodeWithText("a.txt", substring = true).assertIsDisplayed()
    }

    @Test
    fun `超长工具结果可显示全部`() = runComposeUiTest {
        val longResult = "x".repeat(25_000)
        val items = listOf(
            ChatItem.Tool(
                id = "call:1",
                timestamp = null,
                name = "terminal",
                label = "Running command · cat big.log",
                arguments = "{}",
                result = longResult,
                failed = false,
                pairedWithCall = true,
            ),
        )
        setContent(pane(ChatState(load = Loadable.Ready(items))))
        onNodeWithText("Running command · cat big.log").performClick()
        // 列表现在让**最后一项的底部**贴视口底边（长回答要能看到最新内容），
        // 所以展开后的长结果顶部可能在视口外。这里直接触发语义动作，
        // 不依赖节点当前是否落在视口里。
        onNodeWithText("结果过长，仅显示前 20000 个字符").assertExists()
        onNodeWithText("显示全部（25000 字符）").performSemanticsAction(SemanticsActions.OnClick)
        onNodeWithText("收起").assertExists()
    }

    @Test
    fun `代码块带语言标签与复制按钮`() = runComposeUiTest {
        setContent {
            HermesTheme {
                MarkdownText("```kotlin\nval answer = 42\n```")
            }
        }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("KOTLIN").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("KOTLIN").assertIsDisplayed()
    }

    @Test
    fun `流式时显示生成提示`() = runComposeUiTest {
        setContent(
            pane(
                ChatState(
                    load = Loadable.Ready(emptyList()),
                    streaming = StreamingState(),
                ),
            ),
        )
        onNodeWithText("正在生成…").assertIsDisplayed()
    }

    @Test
    fun `流式文本实时显示`() = runComposeUiTest {
        setContent(
            pane(
                ChatState(
                    load = Loadable.Ready(emptyList()),
                    streaming = StreamingState(
                        segments = listOf(StreamSegment.Answer("半句话")),
                    ),
                ),
            ),
        )
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("半句话", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("半句话", substring = true).assertIsDisplayed()
    }

    @Test
    fun `流式段落按到达顺序渲染`() = runComposeUiTest {
        setContent(
            pane(
                ChatState(
                    load = Loadable.Ready(emptyList()),
                    streaming = StreamingState(
                        segments = listOf(
                            StreamSegment.Reasoning("先看一眼目录"),
                            StreamSegment.Tool(
                                ChatItem.Tool(
                                    id = "live:t1",
                                    timestamp = null,
                                    name = "terminal",
                                    label = "Running command · ls",
                                    arguments = "{}",
                                    result = "a.txt",
                                    failed = false,
                                    pairedWithCall = true,
                                ),
                            ),
                            StreamSegment.Answer("目录里只有一个文件"),
                        ),
                    ),
                ),
            ),
        )
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("目录里只有一个文件", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        val reasoningTop = onNodeWithText("思考过程").fetchSemanticsNode().boundsInRoot.top
        val toolTop = onNodeWithText("Running command · ls", substring = true).fetchSemanticsNode().boundsInRoot.top
        val answerTop = onNodeWithText("目录里只有一个文件", substring = true).fetchSemanticsNode().boundsInRoot.top
        assertTrue(reasoningTop < toolTop, "思考应在工具之前")
        assertTrue(toolTop < answerTop, "工具应在正文之前")
    }

    @Test
    fun `流式输出继续增长时仍跟随底部`() = runComposeUiTest {
        val items = listOf(
            ChatItem.UserMessage("u1", null, "帮我写一篇长文"),
            ChatItem.AssistantMessage("a1", null, "好的"),
        )
        var state by mutableStateOf(ChatState(load = Loadable.Ready(items)))
        setContent(paneOf(state = { state }))
        waitForIdle()

        // 新的一轮输出开始：此刻只有一句话，列表贴底。
        state = state.copy(
            streaming = StreamingState(segments = listOf(StreamSegment.Answer("开始写"))),
        )
        waitForIdle()

        // 继续增长到远超一屏。跟随没断的话，末尾那一段应该仍落在视口里
        // （v0.5.2 的缺陷：跟随逻辑读到的是首次组合时的 ChatState，streaming 永远为 null）。
        val longAnswer = buildString {
            repeat(60) { index ->
                append("第 $index 段：这一段用来把内容撑到一屏之外，方便验证滚动跟随。")
                append("\n\n")
            }
            append("末行-END")
        }
        state = state.copy(
            streaming = StreamingState(segments = listOf(StreamSegment.Answer(longAnswer))),
        )
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("末行-END", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("末行-END", substring = true).assertIsDisplayed()
    }

    @Test
    fun `后台任务条显示命令与运行时长`() = runComposeUiTest {
        val tasks = listOf(
            BackgroundTask(
                id = "p1",
                command = "npm run dev -- --port 3000",
                status = "running",
                uptimeSeconds = 65,
                output = "ready on :3000",
            ),
        )
        setContent(pane(ChatState(load = Loadable.Ready(emptyList()), backgroundTasks = tasks)))
        onNodeWithText("1 个后台任务").assertIsDisplayed()
        onNodeWithText("npm run dev -- --port 3000", substring = true).assertIsDisplayed()
        onNodeWithText("1m 05s").assertIsDisplayed()
    }

    @Test
    fun `点终止触发 kill 回调`() = runComposeUiTest {
        var killed: String? = null
        val tasks = listOf(BackgroundTask(id = "p1", command = "npm run dev", status = "running"))
        setContent(
            pane(
                ChatState(load = Loadable.Ready(emptyList()), backgroundTasks = tasks),
                onKillBackgroundTask = { killed = it },
            ),
        )
        onNodeWithContentDescription("终止").performClick()
        assertEquals("p1", killed)
    }

    @Test
    fun `思考段默认展开并按 Markdown 渲染`() = runComposeUiTest {
        val items = listOf(
            ChatItem.Reasoning("r1", null, "先看这段：\n\n```kotlin\nval answer = 42\n```\n"),
        )
        setContent(pane(ChatState(load = Loadable.Ready(items))))
        // 默认展开：不再需要点标题。
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("KOTLIN").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("KOTLIN").assertExists()
    }

    @Test
    fun `流式思考段默认展开且保持纯文本`() = runComposeUiTest {
        setContent(
            pane(
                ChatState(
                    load = Loadable.Ready(emptyList()),
                    streaming = StreamingState(
                        segments = listOf(StreamSegment.Reasoning("还**没**写完的草稿")),
                    ),
                ),
            ),
        )
        // 流式期间不做 Markdown 解析：原文（含 **）应原样可见。
        onNodeWithText("还**没**写完的草稿", substring = true).assertExists()
    }

    @Test
    fun `顶栏显示上下文占用与累计 token`() = runComposeUiTest {
        setContent(
            pane(
                ChatState(
                    load = Loadable.Ready(emptyList()),
                    usage = SessionUsage(
                        contextUsed = 600,
                        contextMax = 1_000,
                        contextPercent = 60.0,
                        totalTokens = 3_400,
                        calls = 7,
                    ),
                ),
            ),
        )
        onNodeWithText("600 / 1.0k", substring = true).assertIsDisplayed()
        onNodeWithText("累计 3.4k", substring = true).assertIsDisplayed()
    }

    @Test
    fun `审批卡片显示命令与选项`() = runComposeUiTest {
        val approval = PendingApproval(
            requestId = "r1",
            command = "rm -rf /tmp/x",
            description = "删除临时目录",
            choices = listOf("once", "deny"),
        )
        setContent(
            pane(
                ChatState(
                    load = Loadable.Ready(emptyList()),
                    pendingApprovals = listOf(approval),
                ),
            ),
        )
        onNodeWithText("需要你确认").assertIsDisplayed()
        onNodeWithText("rm -rf /tmp/x", substring = true).assertIsDisplayed()
        onNodeWithText("允许一次").assertIsDisplayed()
        onNodeWithText("拒绝").assertIsDisplayed()
    }
}
