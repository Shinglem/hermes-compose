package dev.shinglem.hermes.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.shinglem.hermes.core.contract.RpcMethods
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.error.ErrorMapper
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.prefs.AppPreferences
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import dev.shinglem.hermes.core.transport.GatewayClient
import dev.shinglem.hermes.core.transport.GatewayEvent
import dev.shinglem.hermes.core.transport.GatewayEventTypes
import dev.shinglem.hermes.feature.composer.ComposerSessionContext
import dev.shinglem.hermes.feature.composer.RuntimeHandle
import dev.shinglem.hermes.feature.model.ModelCatalog
import dev.shinglem.hermes.feature.model.ModelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.time.Clock

/**
 * 会话内容的状态持有者：只读历史（REST）+ 发送与流式（WebSocket）。
 *
 * 关键约束：
 * - 流式增量按 24ms 合并后再写状态（`08-data-contract` §2.4 / `03-architecture` §7）。
 * - 事件按 runtime session id 过滤，别的 profile/会话的事件不得写进当前 UI。
 * - `prompt.submit` 只等 ack（可能很久）；正文靠 `message.delta` / `message.complete` 推进。
 */
class ChatViewModel(
    private val repository: ChatRepository,
    private val gateway: GatewayClient,
    private val models: ModelRepository,
    private val preferences: AppPreferences,
) : ViewModel(), ComposerSessionContext {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    /**
     * 一条 prompt 被后端接受后的回调（用于刷新会话列表）。
     *
     * 放在这里而不是 UI 层轮询：后端要到第一条 prompt 才给会话生成列表行，
     * 这是唯一准确的时机。
     */
    var onSessionPersisted: (() -> Unit)? = null

    /** 一轮结束的回调（通知中心接线用；`failed` 为真表示这轮失败）。 */
    var onTurnFinished: ((failed: Boolean, text: String) -> Unit)? = null

    /** 收到审批请求（系统通知接线用；同一 `requestId` 只会回调一次）。 */
    var onApprovalRequested: ((PendingApproval, SessionKey?) -> Unit)? = null

    /** 收到 `clarify` 请求（系统通知接线用）。 */
    var onClarifyRequested: ((PendingClarify, SessionKey?) -> Unit)? = null

    /** 分支/复制成功后回调：外层据此刷新列表并选中新会话。 */
    var onBranched: ((BranchFrom) -> Unit)? = null

    /** 已读过的 transcript（含向上翻页的结果）；切回不重复请求。 */
    private val cache = mutableMapOf<SessionKey, LoadedTranscript>()

    private data class LoadedTranscript(val rows: List<ChatRow>, val hasMore: Boolean)

    /** (profile, storedId) → runtime id（`session.resume` 的返回值）。 */
    private val runtimeIds = mutableMapOf<SessionKey, String>()

    /**
     * (profile, storedId) → `execution_generation`。
     *
     * canonical gateway 把它当"这一轮的执行身份"：`session.interrupt` / `session.steer` /
     * `approval.respond` 都是**必填**（缺了就是 `4001 invalid_params`）。每次 resume 刷新。
     */
    private val generations = mutableMapOf<SessionKey, Int?>()

    /** (profile, storedId) → `revision`：`session.mutate` 的 `expected_revision`。 */
    private val revisions = mutableMapOf<SessionKey, Int?>()

    /** (profile, storedId) → 会话工作目录，供 composer 的 `@` 补全使用。 */
    private val cwds = mutableMapOf<SessionKey, String?>()

    /** 切走时保存的会话状态（含流式段落），切回先恢复，避免"内容消失 / 看起来卡死"。 */
    private val savedStates = mutableMapOf<SessionKey, ChatState>()

    /** 切走期间有新内容或已完成一轮：切回必须重读一次 transcript。 */
    private val dirtyKeys = mutableSetOf<SessionKey>()

    /** 已经弹过系统通知的审批（补拉 / 重连会重复拿到同一条）。 */
    private val notifiedApprovals = mutableSetOf<String>()

    /** `clarify` 的作答结果：null = 取消（回空结果），map 为空 = 全部跳过。 */
    private val clarifyDecisions = mutableMapOf<String, CompletableDeferred<Map<String, String>?>>()

    /** clarify 的帧 id → 业务 `request_id`（`request.cancel` 带的是前者）。 */
    private val clarifyFrameIds = mutableMapOf<String, String>()

    private val approvalDecisions = mutableMapOf<String, CompletableDeferred<String>>()

    /** JSON-RPC 帧 id → 队列里的 `request_id`：`request.cancel` 带的是前者。 */
    private val approvalFrameIds = mutableMapOf<String, String>()

    /** 用户手动关掉的后台任务行：直到 registry 也不再报告该 id 才忘记。 */
    private val dismissedTasks = mutableSetOf<String>()

    private var backgroundJob: Job? = null
    private val deltaBuffer = StringBuilder()

    private var loadJob: Job? = null
    private var flushJob: Job? = null
    private var localSeq = 0

    init {
        viewModelScope.launch {
            gateway.events.collect(::handleGatewayEvent)
        }
        gateway.onServerRequest(APPROVAL_REQUEST) { frameId, params ->
            awaitApprovalDecision(frameId, params)
        }
        gateway.onServerRequest(CLARIFY_REQUEST) { frameId, params ->
            awaitClarifyDecision(frameId, params)
        }
    }

    fun open(profile: String?, sessionId: String?, force: Boolean = false) {
        if (sessionId.isNullOrBlank()) {
            stashCurrentState()
            loadJob?.cancel()
            backgroundJob?.cancel()
            dismissedTasks.clear()
            _state.value = ChatState()
            return
        }
        val key = SessionKey(profile, sessionId)
        if (_state.value.key != null && _state.value.key != key) stashCurrentState()
        backgroundJob?.cancel()
        if (_state.value.key != key) dismissedTasks.clear()
        // 已经有 runtime 的会话（切走再切回）不会再 resume，审批 / 后台任务 / 用量要靠这里恢复。
        runtimeIds[key]?.let { runtimeId ->
            refreshPendingApprovals(key, runtimeId)
            startBackgroundPolling(key, runtimeId)
            refreshUsage(key, runtimeId)
        }
        val stale = dirtyKeys.remove(key)
        // 决策表见 SessionSwitchPlan：快照 → 缓存 → 真读，force 时跳过前两步。
        when (switchPlanFor(key, force = force, stale = stale)) {
            SessionSwitchPlan.KEEP -> return
            // 先恢复切走时的快照（含流式段落）：切回瞬间就有内容，不会"看起来卡死"。
            SessionSwitchPlan.RESTORE_SNAPSHOT -> {
                val saved = savedStates.remove(key)
                if (saved != null) {
                    restore(key, saved)
                    return
                }
            }
            SessionSwitchPlan.USE_CACHE -> {
                val loaded = cache[key]
                if (loaded != null) {
                    publish(key, loaded)
                    resumeInBackground(key)
                    return
                }
            }
            SessionSwitchPlan.RELOAD -> Unit
        }
        savedStates.remove(key)

        cache.remove(key)
        loadJob?.cancel()
        // 先填上 pill 的初始值（不阻塞历史读取）：会话级 override 要等 `session.info`，
        // 这里给的是 profile 默认值。
        viewModelScope.launch { seedModelInfo(profile) }
        loadJob = viewModelScope.launch {
            _state.value = ChatState(key = key, load = Loadable.Loading)
            try {
                val page = repository.loadTranscript(profile, sessionId)
                val loaded = LoadedTranscript(page.rows, page.hasMore)
                cache[key] = loaded
                publish(key, loaded)
                resumeInBackground(key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.value = ChatState(
                    key = key,
                    load = Loadable.Failed(ErrorMapper.from(throwable, "chat.transcript")),
                )
            }
        }
    }

    /**
     * 向上加载更早的一页。
     *
     * `offset` 是"已加载的行数"：后端 `order=latest` 下它表示跳过末尾多少条，
     * 正好接着上一次的页头往前翻。
     */
    fun loadOlder() {
        val current = _state.value
        val key = current.key ?: return
        if (!current.canLoadOlder) return
        val loaded = cache[key] ?: return
        _state.update { it.copy(loadingOlder = true, olderError = null) }
        viewModelScope.launch {
            try {
                val page = repository.loadTranscript(
                    profile = key.profile,
                    sessionId = key.sessionId,
                    offset = loaded.rows.size,
                )
                val merged = LoadedTranscript(page.rows + loaded.rows, page.hasMore)
                cache[key] = merged
                if (_state.value.key == key) publish(key, merged)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(
                        loadingOlder = false,
                        olderError = ErrorMapper.from(throwable, "chat.transcript.older"),
                    )
                }
            }
        }
    }

    /**
     * 直接打开一个**刚新建、还没有 transcript** 的会话。
     *
     * 后端在第一条 prompt 之前不给它落库，`session.history` 会返回 404
     * （`{"detail":"Session not found"}`）。这不是错误，所以不请求历史、直接置空。
     */
    fun adoptEmpty(profile: String?, sessionId: String) {
        loadJob?.cancel()
        loadJob = null
        backgroundJob?.cancel()
        backgroundJob = null
        dismissedTasks.clear()
        val key = SessionKey(profile, sessionId)
        cache.remove(key)
        _state.value = ChatState(
            key = key,
            load = Loadable.Ready(emptyList()),
            modelCatalog = _state.value.modelCatalog,
        )
        viewModelScope.launch { seedModelInfo(profile) }
    }

    /**
     * 用 profile 默认模型填 pill 的初始值。
     *
     * 只在当前还没有模型时生效：`session.info` / `session.resume` 拿到的是**会话级**真值，
     * 那两条路径优先。接口失败就静默——pill 会停在"选择模型"，点开菜单仍可用。
     */
    private suspend fun seedModelInfo(profile: String?) {
        if (_state.value.model != null) return
        try {
            val info = models.current(profile) ?: return
            _state.update { current ->
                if (current.model != null) current
                else current.copy(model = info.model, provider = info.provider)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            Log.debug("model.info failed: ${throwable.message}")
        }
    }

    fun reload(profile: String?, sessionId: String?) = open(profile, sessionId, force = true)

    /** 发送一条用户消息（Markdown 源码原样提交，不做任何改写）。 */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val current = _state.value
        val key = current.key ?: return
        // 生成中不禁用发送：后端按 `display.busy_input_mode` 把这条当纠偏/排队处理
        // （见 08-data-contract §2.2 的易错口径）。
        if (current.isBusy) {
            steer(trimmed)
            return
        }
        if (current.sending) return

        viewModelScope.launch {
            _state.update { it.copy(sending = true, sendError = null) }
            try {
                val runtimeId = ensureRuntime(key)
                _state.update { state -> state.appendLocalUser(nextLocalId("user"), trimmed) }
                _state.update { it.copy(streaming = StreamingState(startedAt = Clock.System.now())) }
                val ack = repository.submitPrompt(runtimeId, trimmed)
                Log.info("prompt.submit ack status=${ack.status} userRowId=${ack.userRowId}")
                // 后端在第一条 prompt 之后才给会话生成列表行 —— 这时刷新列表才看得到它。
                onSessionPersisted?.invoke()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                flushDeltaBuffer()
                _state.update {
                    it.copy(
                        sendError = ErrorMapper.from(throwable, "prompt.submit"),
                        streaming = null,
                    )
                }
            } finally {
                _state.update { it.copy(sending = false) }
            }
        }
    }

    /**
     * 生成中纠偏：把这条消息注入当前轮。
     *
     * 不打断正在生成的回答；对不支持的 agent 会抛错并提示用户改用「停止」。
     */
    private fun steer(text: String) {
        val current = _state.value
        val key = current.key ?: return
        if (current.steering) return
        viewModelScope.launch {
            _state.update { it.copy(steering = true, sendError = null) }
            try {
                val runtimeId = ensureRuntime(key)
                val status = repository.steer(key.profile, runtimeId, text, generations[key])
                Log.info("session.steer accepted status=$status")
                _state.update { state -> state.appendLocalUser(nextLocalId("user"), text) }
                onSessionPersisted?.invoke()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(sendError = ErrorMapper.from(throwable, RpcMethods.SESSION_STEER))
                }
            } finally {
                _state.update { it.copy(steering = false) }
            }
        }
    }

    // ── 重发 / 回退 ──────────────────────────────────────────────────────

    /** 用户点了消息上的「重发」：先弹确认（截断是破坏性的）。 */
    fun beginRewind(messageId: String, text: String) {
        val items = _state.value.items
        val index = items.indexOfFirst { it.id == messageId }
        if (index < 0) return
        // 截断到这条 user 消息之前；它是第一条 user 消息时历史会变空。
        val priorUserCount = items.take(index).count { it is ChatItem.UserMessage }
        _state.update {
            it.copy(
                rewind = RewindDraft(
                    messageId = messageId,
                    text = text,
                    emptyAfterTruncate = priorUserCount == 0,
                ),
            )
        }
    }

    fun cancelRewind() = _state.update { it.copy(rewind = null) }

    /**
     * 确认重发：截断该消息之前的历史，把原文重新提交。
     *
     * 忙时后端返回 `4009 session busy` —— 先 interrupt，等这一轮收尾后再重提
     * （与官方 `runRewindSubmit` 同构）。
     */
    fun confirmRewind() {
        val draft = _state.value.rewind ?: return
        val key = _state.value.key ?: return
        _state.update { it.copy(rewind = null, sending = true, sendError = null) }
        viewModelScope.launch {
            try {
                val runtimeId = ensureRuntime(key)
                val loaded = repository.loadTranscript(key.profile, key.sessionId)
                val cutIndex = loaded.rows.indexOfFirst { it.id == draft.messageId }
                if (cutIndex >= 0) {
                    val survivorRows = loaded.rows.take(cutIndex)
                    cache[key] = LoadedTranscript(survivorRows, hasMore = false)
                    _state.update { state ->
                        state.copy(
                            load = Loadable.Ready(projectChat(survivorRows)),
                            truncated = false,
                            loadedRows = survivorRows.size,
                            rewind = null,
                        )
                    }
                } else {
                    // 目标不在最近一页（更早的历史）：不在本地做假预览，交给后端截断后再重载。
                    cache.remove(key)
                    _state.update { it.copy(rewind = null) }
                }
                submitWithRewind(key, runtimeId, draft)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(sendError = ErrorMapper.from(throwable, "prompt.submit.rewind"))
                }
            } finally {
                _state.update { it.copy(sending = false) }
            }
        }
    }

    private suspend fun submitWithRewind(key: SessionKey, runtimeId: String, draft: RewindDraft) {
        // canonical gateway 的 `prompt.submit` 不接受截断参数，回退是**两次**调用：
        // 先 `session.mutate {operation:"rewind", payload:{target_message_id}}`，再提交新消息。
        val rowId = draft.messageId.toLongOrNull()
            ?: throw AppError.Protocol("只能从已落库的消息回退（当前 id=${draft.messageId}）")
        try {
            _state.update { it.copy(streaming = StreamingState(startedAt = Clock.System.now())) }
            repository.rewindTo(runtimeId, rowId, revisions[key], generations[key])
            _state.update { state -> state.appendLocalUser(nextLocalId("user"), draft.text) }
            onSessionPersisted?.invoke()
            repository.submitPrompt(
                runtimeSessionId = runtimeId,
                text = draft.text,
            )
        } catch (throwable: Throwable) {
            val busy = (throwable as? AppError.Server)?.code == BUSY_CODE
            if (!busy) throw throwable
            Log.info("rewind hit busy session; interrupting then retrying")
            repository.interrupt(key.profile, runtimeId, generations[key])
            waitUntilIdle()
            repository.rewindTo(runtimeId, rowId, revisions[key], generations[key])
            repository.submitPrompt(
                runtimeSessionId = runtimeId,
                text = draft.text,
            )
            _state.update { state -> state.appendLocalUser(nextLocalId("user"), draft.text) }
            onSessionPersisted?.invoke()
        }
    }

    /** 等本轮真正结束（`running` 清掉）再重提截断请求。 */
    private suspend fun waitUntilIdle() {
        var waited = 0L
        while (_state.value.streaming != null && waited < REWIND_WAIT_LIMIT_MS) {
            delay(REWIND_POLL_MS)
            waited += REWIND_POLL_MS
        }
    }

    // ── 分支 ────────────────────────────────────────────────────────────

    /** 消息 hover 的「从这里分支」：确认后调 `session.branch`。 */
    fun beginBranch(messageId: String, title: String) {
        val key = _state.value.key ?: return
        val rows = cache[key]?.rows ?: return
        // `count` 对应后端的"可见历史"：只有 user/assistant 且有文本的行会被复制
        // （tool 行、hidden 行都不算），所以必须按同一口径算序号，不能拿 UI 条目下标。
        val visible = rows.filter {
            (it.role == ROLE_USER || it.role == ROLE_ASSISTANT) && it.text.isNotBlank()
        }
        val index = visible.indexOfFirst { it.id == messageId }
        if (index < 0) return
        _state.update {
            it.copy(
                branch = BranchDraft(
                    messageId = messageId,
                    // `count` 是"保留前 N 条"（含这一条）。
                    count = index + 1,
                    title = title.ifBlank { "分支" },
                ),
            )
        }
    }

    fun cancelBranch() = _state.update { it.copy(branch = null) }

    fun confirmBranch() {
        val draft = _state.value.branch ?: return
        val key = _state.value.key ?: return
        if (_state.value.branching) return
        _state.update { it.copy(branch = null, branching = true) }
        viewModelScope.launch {
            try {
                val runtimeId = ensureRuntime(key)
                val result = repository.branch(
                    runtimeSessionId = runtimeId,
                    throughMessageId = draft.messageId.toLongOrNull(),
                    revision = revisions[key],
                    executionGeneration = generations[key],
                )
                onBranched?.invoke(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(sendError = ErrorMapper.from(throwable, RpcMethods.SESSION_MUTATE))
                }
            } finally {
                _state.update { it.copy(branching = false) }
            }
        }
    }

    // ── 用量 ────────────────────────────────────────────────────────────

    /** 刷新上下文占用（每轮结束调一次，不要放进流式路径）。 */
    fun refreshUsage() {
        val key = _state.value.key ?: return
        val runtimeId = runtimeIds[key] ?: return
        refreshUsage(key, runtimeId)
    }

    /**
     * 按指定会话刷新用量。
     *
     * 恢复 / 切回会话时也要拉一次：否则顶栏那条用量一直是空的 ——
     * 它只在轮结束时刷新过，重新打开历史会话就什么都看不到。
     */
    private fun refreshUsage(key: SessionKey, runtimeId: String) {
        viewModelScope.launch {
            try {
                val detail = repository.loadContextBreakdown(key.profile, runtimeId)
                if (_state.value.key != key) return@launch
                _state.update {
                    it.copy(
                        usage = SessionUsage(
                            contextUsed = detail.contextUsed,
                            contextMax = detail.contextMax,
                            contextPercent = detail.contextPercent,
                            totalTokens = detail.estimatedTotal,
                            // `session.context_breakdown` 不给调用次数；0 表示"未知"，
                            // 用量条那边就不会拼「· N 次」。
                            calls = 0,
                            estimated = detail.contextEstimated,
                        ),
                        usageError = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                Log.debug("session.context_breakdown failed: ${throwable.message}")
                if (_state.value.key != key) return@launch
                _state.update {
                    it.copy(usageError = ErrorMapper.from(throwable, RpcMethods.SESSION_CONTEXT_BREAKDOWN))
                }
            }
        }
    }

    /** 点开用量条时才拉明细（`session.context_breakdown`）。 */
    fun loadUsageDetail() {
        val key = _state.value.key ?: return
        val runtimeId = runtimeIds[key] ?: return
        viewModelScope.launch {
            try {
                val detail = repository.loadContextBreakdown(key.profile, runtimeId)
                if (_state.value.key != key) return@launch
                _state.update {
                    val text = buildString {
                        append("上下文：")
                        append(if (detail.contextUsed > 0) formatTokens(detail.contextUsed) else "—")
                        append(" / ")
                        append(if (detail.contextMax > 0) formatTokens(detail.contextMax) else "—")
                        if (detail.contextPercent > 0) {
                            append("（").append(kotlin.math.round(detail.contextPercent)).append("%）")
                        }
                        if (detail.contextEstimated) append(" · 估算值")
                        if (detail.model.isNotBlank()) append("\n模型：").append(detail.model)
                    }
                    it.copy(usageDetail = text, usageError = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(usageError = ErrorMapper.from(throwable, RpcMethods.SESSION_CONTEXT_BREAKDOWN))
                }
            }
        }
    }

    fun dismissUsageError() = _state.update { it.copy(usageError = null, usageDetail = null) }

    /** 停止生成；收尾由 `message.complete(status=interrupted)` 带回。 */
    fun stop() {
        val key = _state.value.key ?: return
        val runtimeId = runtimeIds[key] ?: return
        viewModelScope.launch {
            _state.update { it.copy(stopping = true) }
            try {
                repository.interrupt(key.profile, runtimeId, generations[key])
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(sendError = ErrorMapper.from(throwable, "session.interrupt")) }
            } finally {
                _state.update { it.copy(stopping = false) }
            }
        }
    }

    /** 审批卡片点击。 */
    fun resolveApproval(requestId: String, choice: String) {
        // 本连接收到的请求：有等待中的响应帧，直接交付决策即可。
        approvalDecisions[requestId]?.let { decision ->
            decision.complete(choice)
            return
        }
        // 补拉回来的卡片（请求发出时本端不在线）：响应帧早就不存在，只能走 `approval.respond`。
        val pending = _state.value.pendingApprovals.firstOrNull { it.requestId == requestId } ?: return
        val key = _state.value.key ?: return
        val runtimeId = pending.runtimeSessionId ?: runtimeIds[key] ?: return
        _state.update { current ->
            current.copy(pendingApprovals = current.pendingApprovals.filterNot { it.requestId == requestId })
        }
        viewModelScope.launch {
            try {
                repository.respondApproval(key.profile, runtimeId, requestId, choice, generations[key])
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                Log.info("approval.respond failed: ${throwable.message}")
                _state.update {
                    it.copy(sendError = ErrorMapper.from(throwable, RpcMethods.APPROVAL_RESPOND))
                }
            }
        }
    }

    /**
     * 后端撤回了审批（超时 / 中断 / 别的界面已答）：卡片消失，挂起的响应帧按拒绝收尾。
     *
     * 不按拒绝收尾的话响应帧永远不回来，handler 会一直挂到超时；
     * 而对已撤回的请求 `resolve_gateway_approval` 返回 0，是空操作。
     */
    private fun cancelApproval(cancelled: ChatStreamEvent.ApprovalCancelled) {
        // `request.cancel` 带的是 JSON-RPC 帧 id；映射不到就当它已经是队列 id。
        val requestId = approvalFrameIds.remove(cancelled.requestId) ?: cancelled.requestId
        approvalDecisions[requestId]?.complete(CHOICE_DENY)
        _state.update { current ->
            current.copy(pendingApprovals = current.pendingApprovals.filterNot { it.requestId == requestId })
        }
    }

    // ── 后台任务 ─────────────────────────────────────────────────────────

    /**
     * 轮询本会话的后台进程（`process.list`）。
     *
     * 5s 一次与官方 composer 状态栈一致：进程状态变化没有事件推送（只有输出增量），
     * 结束 / 退出码只能靠轮询发现。
     */
    private fun startBackgroundPolling(key: SessionKey, runtimeId: String) {
        backgroundJob?.cancel()
        backgroundJob = viewModelScope.launch {
            while (true) {
                val alive = refreshBackgroundTasks(key, runtimeId)
                if (!alive) break
                delay(BACKGROUND_POLL_MS)
            }
        }
    }

    /** @return false 表示会话已经不在（停止轮询，别一直撞 4001）。 */
    private suspend fun refreshBackgroundTasks(key: SessionKey, runtimeId: String): Boolean {
        val tasks = try {
            repository.loadProcesses(key.profile, runtimeId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            Log.debug("process.list failed: ${throwable.message}")
            // 会话被回收（4044 / 4001）时继续轮询没有意义；其它错误下一轮再试。
            return !looksLikeGone(throwable)
        }
        if (_state.value.key != key) return false
        _state.update { current ->
            current.copy(backgroundTasks = reconcileBackgroundTasks(current.backgroundTasks, tasks, dismissedTasks))
        }
        return true
    }

    private fun looksLikeGone(throwable: Throwable): Boolean {
        val error = throwable as? AppError.Server ?: return false
        return error.code in GONE_CODES
    }

    /** 终止一个后台任务；结果通过下一轮轮询体现（后端返回的快照只用于判定状态）。 */
    fun killBackgroundTask(processId: String) {
        val key = _state.value.key ?: return
        val runtimeId = runtimeIds[key] ?: return
        if (_state.value.killingTaskId != null) return
        _state.update { it.copy(killingTaskId = processId) }
        viewModelScope.launch {
            try {
                repository.killProcess(key.profile, runtimeId, processId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                Log.info("process.kill failed: ${throwable.message}")
            } finally {
                _state.update { it.copy(killingTaskId = null) }
            }
            if (_state.value.key == key) refreshBackgroundTasks(key, runtimeId)
        }
    }

    /** 收起 / 忽略一条已结束的后台任务（只是本地不再显示，不影响进程）。 */
    fun dismissBackgroundTask(processId: String) {
        dismissedTasks += processId
        _state.update { current ->
            current.copy(backgroundTasks = current.backgroundTasks.filterNot { it.id == processId })
        }
    }

    /** `agent.terminal.output`：把增量接到对应任务上（详情里能实时看）。 */
    private fun appendBackgroundOutput(processId: String, chunk: String) {
        if (processId.isBlank() || chunk.isEmpty()) return
        _state.update { current ->
            val next = current.backgroundTasks.appendOutput(processId, chunk)
            if (next === current.backgroundTasks) current else current.copy(backgroundTasks = next)
        }
    }

    fun dismissSendError() {
        _state.update { it.copy(sendError = null) }
    }

    // ── 模型 / 推理强度 ──────────────────────────────────────────────────────

    /**
     * 拉模型目录（懒加载：只在用户第一次打开菜单时调）。
     *
     * `refresh` 会强制后端重探所有 provider，正常打开不要传 true。
     */
    fun loadModelCatalog(refresh: Boolean = false) {
        val current = _state.value
        if (current.modelCatalogLoading) return
        if (!refresh && current.modelCatalog?.isEmpty == false) return
        _state.update { it.copy(modelCatalogLoading = true, modelError = null) }
        viewModelScope.launch {
            try {
                val catalog = models.options(_state.value.key?.profile, refresh)
                _state.update { it.copy(modelCatalog = catalog, modelCatalogLoading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                Log.info("model.options failed: ${throwable.message}")
                _state.update {
                    it.copy(
                        modelCatalogLoading = false,
                        modelError = ErrorMapper.from(throwable, RpcMethods.MODEL_OPTIONS),
                    )
                }
            }
        }
    }

    /** 切模型：有 live session 走 `config.set`，草稿态只落本地偏好（建会话时带过去）。 */
    fun switchModel(provider: String, model: String, confirmExpensive: Boolean = false) {
        val current = _state.value
        val key = current.key
        preferences.draftProvider = provider
        preferences.draftModel = model

        if (key == null) {
            // 还没会话：`config.set model` 会返回 4001（requires a live session），
            // 所以先只更新本地显示，新建会话时通过 `session.create` 生效。
            _state.update {
                it.copy(model = model, provider = provider, modelSwitching = false, modelConfirm = null)
            }
            return
        }

        _state.update { it.copy(modelSwitching = true, modelError = null, modelConfirm = null) }
        viewModelScope.launch {
            try {
                val runtimeId = ensureRuntime(key)
                val outcome = models.setModel(
                    profile = key.profile,
                    runtimeSessionId = runtimeId,
                    provider = provider,
                    model = model,
                    revision = revisions[key],
                    executionGeneration = generations[key],
                    confirmExpensive = confirmExpensive,
                )
                if (outcome.confirmRequired) {
                    _state.update {
                        it.copy(
                            modelSwitching = false,
                            modelConfirm = ModelConfirm(
                                provider = provider,
                                model = model,
                                message = outcome.confirmMessage.orEmpty(),
                            ),
                        )
                    }
                    return@launch
                }
                // 不在这里回读模型：切换成功后后端会推 `session.info`（deferred 时也可能延后），
                // 那才是显示的唯一真相源。mutate 回执带着新鲜 fence，写回去让下一次编辑不用试错。
                adoptFence(key, outcome.revision, outcome.executionGeneration)
                _state.update { it.copy(modelSwitching = false, model = model, provider = provider) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                Log.info("config.set model failed: ${throwable.message}")
                _state.update {
                    it.copy(
                        modelSwitching = false,
                        modelError = ErrorMapper.from(throwable, RpcMethods.CONFIG_SET),
                    )
                }
            }
        }
    }

    /** 贵模型确认框点了"继续"。 */
    fun confirmModelSwitch() {
        val confirm = _state.value.modelConfirm ?: return
        switchModel(confirm.provider, confirm.model, confirmExpensive = true)
    }

    fun dismissModelConfirm() {
        _state.update { it.copy(modelConfirm = null) }
    }

    fun dismissModelError() {
        _state.update { it.copy(modelError = null) }
    }

    /** 切推理强度（会话级，不改全局 `agent.reasoning_effort`）。 */
    fun setReasoning(effort: String) {
        val current = _state.value
        val key = current.key
        preferences.draftReasoning = effort

        if (key == null) {
            _state.update { it.copy(reasoningEffort = effort, modelSwitching = false) }
            return
        }

        _state.update { it.copy(modelSwitching = true, modelError = null, reasoningEffort = effort) }
        viewModelScope.launch {
            try {
                val runtimeId = ensureRuntime(key)
                val outcome = models.setReasoning(
                    key.profile,
                    runtimeId,
                    effort,
                    revisions[key],
                    generations[key],
                )
                adoptFence(key, outcome.revision, outcome.executionGeneration)
                _state.update { it.copy(modelSwitching = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                Log.info("config.set reasoning failed: ${throwable.message}")
                _state.update {
                    it.copy(
                        modelSwitching = false,
                        modelError = ErrorMapper.from(throwable, RpcMethods.CONFIG_SET),
                    )
                }
            }
        }
    }

    /** 草稿态（还没有会话）的选择：新建会话时由 `session.create` 带过去。 */
    fun draftSelection(): Triple<String?, String?, String?> =
        Triple(preferences.draftProvider, preferences.draftModel, preferences.draftReasoning)

    override fun onCleared() {
        gateway.removeServerRequestHandler(APPROVAL_REQUEST)
        approvalDecisions.values.forEach { it.cancel() }
        approvalDecisions.clear()
        approvalFrameIds.clear()
    }

    // ── 切走 / 切回：快照与脏标记 ────────────────────────────────────────

    /** 切走前把当前会话状态存起来（含流式段落），切回时可以原样恢复。 */
    private fun stashCurrentState() {
        val key = _state.value.key ?: return
        savedStates[key] = _state.value
        // 快照里有整段消息列表，别无限攒：保留最近 12 个会话足够覆盖来回切换。
        if (savedStates.size > 12) savedStates.remove(savedStates.keys.first())
    }

    private fun cachedStreaming(key: SessionKey): Boolean = savedStates[key]?.streaming != null

    /**
     * 切走期间在**影子状态**上继续处理该会话的流式事件。
     *
     * 只标记 dirty、等切回补拉是不够的：切回时那轮往往还在跑（审批就是在等它），
     * `restore` 看到 `isSessionLive` 为真就跳过补拉，而切走期间的增量早已被丢弃 ——
     * 界面于是永久停在切走前那一帧，必须手动点"刷新"（实测复现）。
     * 这里把事件累积到快照上，切回时快照本身就是最新的。
     */
    private fun trackShadow(key: SessionKey, event: GatewayEvent) {
        val snapshot = savedStates[key] ?: return
        savedStates[key] = snapshot.applyShadowEvent(event.toChatStreamEvent(), nextId = { kind -> nextLocalId(kind) })
    }


    /** 把当前状态翻译成 [SessionSwitchPlan]（纯决策，见该文件的判据表）。 */
    private fun switchPlanFor(key: SessionKey, force: Boolean, stale: Boolean): SessionSwitchPlan = switchPlan(
        force = force,
        sameKeyReady = _state.value.key == key && _state.value.load is Loadable.Ready,
        stale = stale,
        cachedStreaming = cachedStreaming(key),
        hasSnapshot = savedStates.containsKey(key),
        hasCache = cache.containsKey(key),
    )

    /**
     * 恢复切走时的会话快照。
     *
     * 审批 / clarify 是"后端正在等"的全局事实：快照里的要和本端当前持有的合并，不能丢。
     * 切走期间的事件由 [trackShadow] 累积进快照，所以这里恢复出来的就是最新一帧。
     * 恢复后再查一次活跃集合：那轮若已结束，静默补拉一次把完整 transcript（含工具结果等
     * 影子没跟踪到的字段）拿回来；还在跑就等后续增量，不覆盖用户当前正在看的内容。
     */
    private fun restore(key: SessionKey, saved: ChatState) {
        _state.update { current ->
            saved.copy(
                pendingApprovals = (saved.pendingApprovals + current.pendingApprovals)
                    .distinctBy { it.requestId },
                pendingClarify = (saved.pendingClarify + current.pendingClarify)
                    .distinctBy { it.requestId },
            )
        }
        resumeInBackground(key)
        viewModelScope.launch {
            if (_state.value.key != key) return@launch
            // 还在跑：后续 delta / complete 会把内容补齐，别覆盖流式段落。
            if (repository.isSessionLive(key.profile, key.sessionId)) return@launch
            reloadQuietly(key)
        }
    }

    /** 不置 Loading 的重新读取：恢复快照后发现那轮已结束，用它把内容补齐。 */
    private fun reloadQuietly(key: SessionKey) {
        viewModelScope.launch {
            val loaded = try {
                val page = repository.loadTranscript(key.profile, key.sessionId)
                LoadedTranscript(page.rows, page.hasMore)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                Log.debug("chat quiet reload failed: ${throwable.message}")
                return@launch
            }
            if (_state.value.key != key) return@launch
            cache[key] = loaded
            publish(key, loaded)
            runtimeIds[key]?.let { refreshUsage(key, it) }
        }
    }

    /**
     * 打开会话后在后台补一次 `session.resume`。
     *
     * 没有 runtime id 就拿不到 `session.usage`（上下文 / token 用量），也收不到该会话的事件；
     * 以前要等用户发第一条消息才 resume，所以刚打开的历史会话顶栏一直是空的。
     * best-effort：失败只记日志，发消息时还会再试一次。
     */
    private fun resumeInBackground(key: SessionKey) {
        if (runtimeIds[key] != null) return
        viewModelScope.launch {
            try {
                ensureRuntime(key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                Log.debug("chat.resume (background) failed: ${throwable.message}")
            }
        }
    }

    private suspend fun ensureRuntime(key: SessionKey): String {
        runtimeIds[key]?.let { return it }
        val resumed = repository.resumeSession(key.profile, key.sessionId)
        runtimeIds[key] = resumed.runtimeId
        cwds[key] = resumed.cwd
        // canonical gateway 的「执行代」与 mutation revision：interrupt / steer / approval.respond
        // 都要求带 `execution_generation`，session.mutate 要求 `expected_revision`。
        // 两者都随每一轮推进，所以每次 resume 都刷新一遍。
        generations[key] = resumed.executionGeneration
        revisions[key] = resumed.revision
        // resume 的 info 就带着当前模型/推理强度：拿到就写进 UI，不用等下一次 session.info。
        // 期间切走了就别写 —— 那是另一个会话的 pill。
        _state.update { current ->
            if (current.key != key) return@update current
            current.copy(
                cwd = resumed.cwd ?: current.cwd,
                model = resumed.model ?: current.model,
                provider = resumed.provider ?: current.provider,
                reasoningEffort = resumed.reasoningEffort ?: current.reasoningEffort,
            )
        }
        Log.info("chat.resume stored=${key.sessionId} runtime=${resumed.runtimeId}")
        // 重连 / 重建 runtime 后补拉挂着的审批：那些请求的响应帧本端已经收不到了。
        refreshPendingApprovals(key, resumed.runtimeId)
        startBackgroundPolling(key, resumed.runtimeId)
        // 打开历史会话也要有用量：这条之前只在轮结束时刷新，重开会话看到的是空的。
        refreshUsage(key, resumed.runtimeId)
        return resumed.runtimeId
    }

    override val profile: String? get() = _state.value.key?.profile

    /**
     * 写回后端回执里的新鲜 fence。
     *
     * `session.mutate` 每成功一次，`revision`（以及 model/reasoning 这类 `_POLICY` 操作的
     * `execution_generation`）都会变；不写回就等于下一次编辑带着过期的 fence 去撞 4001。
     */
    private fun adoptFence(key: SessionKey, revision: Int?, generation: Int?) {
        revision?.let { revisions[key] = it }
        generation?.let { generations[key] = it }
    }

    override val storedSessionId: String? get() = _state.value.key?.sessionId

    override fun runtimeInfo(): RuntimeHandle? {
        val key = _state.value.key ?: return null
        val runtimeId = runtimeIds[key] ?: return null
        return RuntimeHandle(runtimeId, cwds[key])
    }

    override suspend fun ensureRuntime(): RuntimeHandle? {
        val key = _state.value.key ?: return null
        val runtimeId = try {
            ensureRuntime(key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            Log.info("composer resume failed: ${throwable.message}")
            return null
        }
        return RuntimeHandle(runtimeId, cwds[key])
    }

    private suspend fun handleGatewayEvent(event: GatewayEvent) {
        val key = _state.value.key ?: return
        // 别的会话 / 别的 profile：只标记"有新内容"，切回时补拉；绝不写当前 UI。
        val otherKey = event.sessionId
            ?.let { sid -> runtimeIds.entries.firstOrNull { it.value == sid }?.key }
            ?.takeIf { it != key }
        if (otherKey != null) {
            if (isContentEvent(event.type)) {
                dirtyKeys += otherKey
                trackShadow(otherKey, event)
                Log.debug("chat event tracked ${event.type} for ${otherKey.sessionId}")
            }
            return
        }
        val runtimeId = runtimeIds[key] ?: return
        // 认不出归属的事件（sessionId 为空，或不是本端已知的 runtime）按原规则过滤。
        if (event.sessionId != null && event.sessionId != runtimeId) {
            Log.debug("chat event ignored ${event.type} sid=${event.sessionId} (current=$runtimeId)")
            return
        }
        Log.debug("chat event ${event.type} sid=${event.sessionId}")

        // 后台进程的输出增量不进对话流，直接接到任务行上（详情里能实时看）。
        if (event.type == GatewayEventTypes.AGENT_TERMINAL_OUTPUT) {
            val payload = event.payload as? JsonObject
            appendBackgroundOutput(
                processId = (payload?.get("process_id") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                chunk = (payload?.get("chunk") as? JsonPrimitive)?.contentOrNull.orEmpty(),
            )
            return
        }

        when (val parsed = event.toChatStreamEvent()) {
            is ChatStreamEvent.Delta -> appendDelta(parsed.text)

            is ChatStreamEvent.ReasoningDelta -> appendReasoning(parsed.text)

            ChatStreamEvent.TurnStarted -> _state.update { current ->
                if (current.streaming != null) {
                    current
                } else {
                    current.copy(streaming = StreamingState(startedAt = Clock.System.now()))
                }
            }

            is ChatStreamEvent.Interim -> sealInterim(parsed)

            is ChatStreamEvent.ToolStarted -> upsertTool(parsed)
            is ChatStreamEvent.ToolCompleted -> finishTool(parsed)
            is ChatStreamEvent.Completed -> finishTurn(parsed)
            is ChatStreamEvent.ApprovalCancelled -> cancelApproval(parsed)
            is ChatStreamEvent.ClarifyCancelled -> cancelClarify(parsed.requestId)
            is ChatStreamEvent.UsageUpdated -> _state.update { it.copy(lastUsageTotal = parsed.totalTokens) }
            is ChatStreamEvent.Failure -> _state.update {
                it.copy(sendError = AppError.Server(0, parsed.message))
            }

            is ChatStreamEvent.SessionInfoChanged -> _state.update { current ->
                current.copy(
                    model = parsed.model?.takeIf { it.isNotBlank() } ?: current.model,
                    provider = parsed.provider?.takeIf { it.isNotBlank() } ?: current.provider,
                    reasoningEffort = parsed.reasoningEffort?.takeIf { it.isNotBlank() }
                        ?: current.reasoningEffort,
                    modelSwitching = false,
                )
            }

            is ChatStreamEvent.TitleChanged,
            ChatStreamEvent.Ignored,
            -> Unit
        }
    }

    private fun appendDelta(text: String) {
        if (text.isEmpty()) return
        deltaBuffer.append(text)
        if (flushJob?.isActive == true) return
        flushJob = viewModelScope.launch {
            delay(STREAM_FLUSH_INTERVAL_MS)
            flushDeltaBuffer()
        }
    }

    private fun flushDeltaBuffer() {
        if (deltaBuffer.isEmpty()) return
        val chunk = deltaBuffer.toString()
        deltaBuffer.setLength(0)
        _state.update { current ->
            val streaming = current.streaming ?: StreamingState(startedAt = Clock.System.now())
            current.copy(streaming = streaming.appendAnswer(chunk))
        }
    }

    /** 思考增量直接合并（量小、不必和正文一样做 40ms 缓冲）。 */
    private fun appendReasoning(text: String) {
        if (text.isEmpty()) return
        _state.update { current ->
            val streaming = current.streaming ?: StreamingState(startedAt = Clock.System.now())
            current.copy(streaming = streaming.appendReasoning(text))
        }
    }

    /** `message.interim`：当前这段旁白封段，后续正文另起一段。 */
    private fun sealInterim(interim: ChatStreamEvent.Interim) {
        flushDeltaBuffer()
        _state.update { current ->
            val streaming = current.streaming ?: StreamingState(startedAt = Clock.System.now())
            current.copy(streaming = streaming.sealInterim(interim.text))
        }
    }

    private fun upsertTool(started: ChatStreamEvent.ToolStarted) {
        // 先把缓冲里的正文落盘，工具段才会排在它后面而不是把它吞掉。
        flushDeltaBuffer()
        val id = LIVE_TOOL_PREFIX + started.toolId.ifBlank { nextLocalId("tool") }
        val item = ChatItem.Tool(
            id = id,
            timestamp = Clock.System.now(),
            name = started.name,
            label = started.label,
            arguments = started.arguments,
            result = null,
            failed = false,
            pairedWithCall = true,
        )
        _state.update { current ->
            val streaming = current.streaming ?: StreamingState(startedAt = Clock.System.now())
            current.copy(streaming = streaming.startTool(item))
        }
    }

    private fun finishTool(completed: ChatStreamEvent.ToolCompleted) {
        _state.update { current ->
            val streaming = current.streaming ?: return@update current
            current.copy(
                streaming = streaming.finishTool(
                    toolId = LIVE_TOOL_PREFIX + completed.toolId,
                    result = completed.result,
                    failed = completed.failed,
                ),
            )
        }
    }

    private fun finishTurn(completed: ChatStreamEvent.Completed) {
        flushDeltaBuffer()
        val streaming = _state.value.streaming
        val timestamp = Clock.System.now()
        val segments = streaming?.segments.orEmpty().withFinalText(completed.text)
        val additions = buildList {
            addAll(segments.toChatItems(timestamp) { kind -> nextLocalId(kind) })
            // 失败的一轮要在对话里留痕：只放顶部横幅的话，滚动回来看历史会以为"什么都没发生"。
            if (completed.failed) {
                val reason = completed.error?.takeIf { it.isNotBlank() }
                    ?: completed.status?.takeIf { it.isNotBlank() }
                    ?: "这一轮没有正常结束"
                add(ChatItem.Notice(nextLocalId("notice"), timestamp, "本轮失败：$reason"))
            }
        }
        _state.update { current ->
            val merged = current.load.valueOrNull.orEmpty() + additions
            current.copy(
                load = Loadable.Ready(merged),
                streaming = null,
                sendError = if (completed.failed) {
                    AppError.Server(0, completed.error ?: completed.status.orEmpty().ifBlank { "turn failed" })
                } else {
                    current.sendError
                },
            )
        }
        // 一轮结束后刷新上下文占用（不在流式路径里刷）。
        refreshUsage()
        val closingText = segments.filterIsInstance<StreamSegment.Answer>().lastOrNull()?.text.orEmpty()
        onTurnFinished?.invoke(completed.failed, closingText.ifBlank { completed.text })
    }

    private suspend fun awaitApprovalDecision(frameId: String, params: JsonElement?): JsonElement? {
        val obj = params as? JsonObject ?: return null
        val parsed = obj.toPendingApproval(fromRequest = true) ?: return null
        val request = parsed.copy(sessionKey = keyFor(parsed.runtimeSessionId))
        val decision = CompletableDeferred<String>()
        approvalDecisions[request.requestId] = decision
        approvalFrameIds[frameId] = request.requestId
        _state.update { it.copy(pendingApprovals = it.pendingApprovals + request) }
        // 非前台时的系统通知只发一次（补拉 / 重连会重复拿到同一条）。
        if (notifiedApprovals.add(request.requestId)) {
            onApprovalRequested?.invoke(request, keyFor(request.runtimeSessionId))
        }
        // 告诉后端卡片已上屏：它的超时时钟从这里才开始算（先前的时钟只在等客户端上屏）。
        acknowledgeApproval(
            requestId = request.requestId,
            runtimeId = request.runtimeSessionId ?: _state.value.key?.let { runtimeIds[it] },
        )
        val choice = try {
            // 后端自己的审批超时是 300s；这里必须比它长，否则会在后端还在等的时候先替用户拒绝。
            withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { decision.await() } ?: CHOICE_DENY
        } finally {
            approvalDecisions.remove(request.requestId)
            approvalFrameIds.remove(frameId)
            _state.update { current ->
                current.copy(pendingApprovals = current.pendingApprovals.filterNot { it.requestId == request.requestId })
            }
        }
        // 撤回（`request.cancel`）也让等待结束；此时回什么都不重要，空操作。
        return buildJsonObject { put("choice", choice) }
    }

    /**
     * 处理 server→client 的 `clarify`：一个问题或一批问题，等用户作答。
     *
     * 返回 `{answer}`（单问）/ `{answers}`（批量）；**两者都不给 = 取消全部**
     * （契约里没有别的"放弃"语义，不要自造字段）。
     */
    private suspend fun awaitClarifyDecision(frameId: String, params: JsonElement?): JsonElement? {
        val obj = params as? JsonObject ?: return buildJsonObject { }
        val parsed = obj.toPendingClarify(frameId) ?: return buildJsonObject { }
        val request = parsed.copy(sessionKey = keyFor(parsed.runtimeSessionId))
        val decision = CompletableDeferred<Map<String, String>?>()
        clarifyDecisions[request.requestId] = decision
        clarifyFrameIds[frameId] = request.requestId
        _state.update { it.copy(pendingClarify = it.pendingClarify + request) }
        onClarifyRequested?.invoke(request, keyFor(request.runtimeSessionId))
        val answers = try {
            withTimeoutOrNull(CLARIFY_TIMEOUT_MS) { decision.await() }
        } finally {
            clarifyDecisions.remove(request.requestId)
            clarifyFrameIds.remove(frameId)
            _state.update { current ->
                current.copy(
                    pendingClarify = current.pendingClarify.filterNot { it.requestId == request.requestId },
                )
            }
        }
        return if (answers.isNullOrEmpty()) {
            buildJsonObject { }
        } else if (request.isBatch) {
            buildJsonObject {
                put("answers", buildJsonObject { answers.forEach { (qid, text) -> put(qid, text) } })
            }
        } else {
            buildJsonObject { put("answer", answers.values.first()) }
        }
    }

    /** 作答一个 `clarify`；批量请求的 [answers] 键是 `qid`。 */
    fun answerClarify(requestId: String, answers: Map<String, String>) {
        clarifyDecisions[requestId]?.complete(answers)
    }

    /** 取消 `clarify`：回空结果，后端按"用户放弃"处理。 */
    fun cancelClarify(requestId: String) {
        val businessId = clarifyFrameIds.remove(requestId) ?: requestId
        clarifyDecisions[businessId]?.complete(null)
    }

    private fun isContentEvent(type: String): Boolean = type in CONTENT_EVENT_TYPES

    /**
     * 事件 / 请求属于哪个会话。
     *
     * 优先按 runtime id 反查（多会话场景）；查不到时退回当前打开的会话（单会话场景）。
     * 通知点击要跳转，所以必须给 stored id 而不是 runtime id。
     */
    private fun keyFor(runtimeSessionId: String?): SessionKey? =
        runtimeSessionId
            ?.let { sid -> runtimeIds.entries.firstOrNull { it.value == sid }?.key }
            ?: _state.value.key

    private fun acknowledgeApproval(requestId: String, runtimeId: String?) {
        val key = _state.value.key ?: return
        if (runtimeId.isNullOrBlank()) return
        viewModelScope.launch {
            try {
                repository.acknowledgeApproval(key.profile, runtimeId, requestId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                Log.debug("approval.received failed: ${throwable.message}")
            }
        }
    }

    /**
     * 补拉本会话挂着的审批：请求发出时本端可能不在线（断线 / 刚重连），
     * 那个响应帧永远不会到达本端，只能靠 `approval.pending` 恢复卡片。
     */
    private fun refreshPendingApprovals(key: SessionKey, runtimeId: String) {
        viewModelScope.launch {
            val pending = try {
                repository.pendingApprovals(key.profile, runtimeId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                Log.debug("approval.pending failed: ${throwable.message}")
                return@launch
            }
            if (pending.isEmpty() || _state.value.key != key) return@launch
            val targeted = pending.map { it.copy(sessionKey = key) }
            var added: List<PendingApproval> = emptyList()
            _state.update { current ->
                val known = current.pendingApprovals.map { it.requestId }.toSet()
                added = targeted.filterNot { it.requestId in known }
                current.copy(pendingApprovals = current.pendingApprovals + added)
            }
            // 补拉的条目同样要弹通知（"请求发出时本端不在线"是最容易错过的场景）。
            added.forEach { approval ->
                if (notifiedApprovals.add(approval.requestId)) {
                    onApprovalRequested?.invoke(approval, key)
                }
            }
            // 补拉的条目不带 session_id（是队列原始数据），用这次的 runtime 直接确认。
            pending.forEach { approval -> acknowledgeApproval(approval.requestId, runtimeId) }
        }
    }

    private fun publish(key: SessionKey, loaded: LoadedTranscript) {
        val previous = _state.value
        val sameSession = previous.key == key
        val previousCount = if (sameSession) previous.items.size else 0
        val items = projectChat(loaded.rows)
        val inserted = (items.size - previousCount).coerceAtLeast(0)
        _state.value = ChatState(
            key = key,
            load = Loadable.Ready(items),
            truncated = loaded.hasMore,
            loadedRows = loaded.rows.size,
            loadingOlder = false,
            olderInserted = inserted,
            olderGeneration = if (inserted > 0) previous.olderGeneration + 1 else previous.olderGeneration,
            olderError = null,
            // 模型/用量都是会话级状态：同一会话的重新加载要保留，切换会话才清空。
            model = if (sameSession) previous.model else null,
            provider = if (sameSession) previous.provider else null,
            reasoningEffort = if (sameSession) previous.reasoningEffort else null,
            usage = if (sameSession) previous.usage else null,
            usageDetail = if (sameSession) previous.usageDetail else null,
            // 目录是 profile 级的，切会话不必重新拉。
            modelCatalog = previous.modelCatalog,
            // 审批是"后端正在等这个 agent"的全局事实，切会话不该把卡片弄丢
            // （弄丢的代价是后端白等到超时、命令被判拒绝）。
            pendingApprovals = previous.pendingApprovals,
            // clarify 同理：卡片没了后端会一直等到超时。
            pendingClarify = previous.pendingClarify,
            // 后台任务同理：重新读一次历史不该把已跑起来的进程列表清空。
            backgroundTasks = if (sameSession) previous.backgroundTasks else emptyList(),
        )
    }

    private fun ChatState.appendLocalUser(id: String, text: String): ChatState {
        val item = ChatItem.UserMessage(id, Clock.System.now(), text)
        return copy(load = Loadable.Ready(load.valueOrNull.orEmpty() + item))
    }

    private fun nextLocalId(kind: String): String = "${LOCAL_ID_PREFIX}$kind:${localSeq++}"

    private companion object {
        const val APPROVAL_REQUEST = "approval"
        const val CLARIFY_REQUEST = "clarify"
        const val CHOICE_DENY = "deny"
        const val STREAM_FLUSH_INTERVAL_MS = 40L

        /** 后端 clarify 的超时与审批同量级；本端兜底必须更长，先把等待结束掉。 */
        const val CLARIFY_TIMEOUT_MS = 330_000L

        /** 会把对话内容写进 transcript 的事件：别的会话收到这些就标记 dirty。 */
        val CONTENT_EVENT_TYPES = setOf(
            GatewayEventTypes.MESSAGE_START,
            GatewayEventTypes.MESSAGE_DELTA,
            GatewayEventTypes.MESSAGE_INTERIM,
            GatewayEventTypes.REASONING_DELTA,
            GatewayEventTypes.MESSAGE_COMPLETE,
            GatewayEventTypes.TOOL_START,
            GatewayEventTypes.TOOL_COMPLETE,
            GatewayEventTypes.ERROR,
        )

        /** 后端审批超时是 300s（`run.py::_APPROVAL_TIMEOUT_SECONDS`），本端兜底必须更长。 */
        const val APPROVAL_TIMEOUT_MS = 330_000L

        /** 后台进程状态没有事件推送，只能轮询；与官方 composer 状态栈一致 5s 一次。 */
        const val BACKGROUND_POLL_MS = 5_000L

        /** 会话已被回收的错误码：再轮询只会一直撞同一个错。 */
        val GONE_CODES = setOf(4001, 4044)
        const val LOCAL_ID_PREFIX = "local:"

        /** 后端"会话忙"的错误码（`methods_prompt.py`）。 */
        const val BUSY_CODE = 4009
        const val REWIND_POLL_MS = 500L
        const val REWIND_WAIT_LIMIT_MS = 90_000L
    }
}
