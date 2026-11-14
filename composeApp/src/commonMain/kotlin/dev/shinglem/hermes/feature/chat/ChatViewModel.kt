package dev.shinglem.hermes.feature.chat

import dev.shinglem.hermes.component.chat.ChatEffect
import dev.shinglem.hermes.core.contract.RpcMethods
import dev.shinglem.hermes.core.transport.ConnectionState
import dev.shinglem.hermes.core.transport.SessionFence
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
import dev.shinglem.hermes.feature.composer.PromptAttachment
import dev.shinglem.hermes.feature.composer.RuntimeHandle
import dev.shinglem.hermes.feature.model.ModelCatalog
import dev.shinglem.hermes.feature.model.ModelRepository
import dev.shinglem.hermes.feature.model.ModelSwitchOutcome
import kotlinx.coroutines.CoroutineScope
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
/**
 * Chat 域的状态持有者。
 *
 * **S4 过渡形态**（见 ADR-071）：这类 2147 行、六条线交织的状态机，整块重写为
 * 单份 `ChatReducer` 是 3000+ 行的替换，风险远超本轮可承受。本轮只做两件事：
 * 1. `viewModelScope` → 注入的 [scope]（生命周期交给 `DefaultChatComponent`）；
 * 2. 由 Component 包住它，对外只暴露 `state` / `effects` / `onIntent`。
 *
 * `13 篇` 的"`ChatReducer` 吃三份流式实现 + 删 `SessionShadow`"排到 S8。
 */
class ChatViewModel(
    private val repository: ChatRepository,
    private val gateway: GatewayClient,
    private val models: ModelRepository,
    private val preferences: AppPreferences,
    private val scope: CoroutineScope,
    /**
     * 一次性事件出口（S5：5 个 `var onXxx` 收成一条通道）。
     *
     * 迁移前是 5 个可空回调 —— 它们的问题是**后写覆盖前写**：UI 与通知中心都赋值时，
     * 后赋的那个静默盖掉前一个（这是迁移前的真实 bug 来源）。收成一个通道后，
     * 广播语义由 `DefaultChatComponent` 的 SharedFlow 保证。
     */
    private val emit: (ChatEffect) -> Unit,
) : ComposerSessionContext {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    /**
     * 会话运行时身份的唯一持有者（S8：原 `runtimeIds` / `generations` / `revisions` /
     * `lastEventAt` / `cwds` / `dirtyKeys` 六个平行 map）。
     *
     * 它们**永远同生同灭**（resume 时一起写、重连时一起清），分成六个 map 时漏写一处就是
     * `4001 stale_generation` 家族（CHANGELOG 0.5.4 / 0.5.5 连续两版都在修）。
     * 合成值对象后反向索引也只有 [SessionRuntimeStore.byRuntimeId] 一个维护点。
     */
    private val runtimes = SessionRuntimeStore()

    /** 已读过的 transcript（含向上翻页的结果）；切回不重复请求。 */
    private val cache = mutableMapOf<SessionKey, LoadedTranscript>()

    private data class LoadedTranscript(val rows: List<ChatRow>, val hasMore: Boolean)

    /** 会话级 / profile 级的模型真值（`ChatState` 会被整块替换，所以必须单独按 key 存）。 */
    private val modelsCache = SessionModelCache()

    /** 切走时的状态快照（含流式段落）：切回先恢复，避免"内容消失 / 看起来卡死"。 */
    private val snapshots = SessionSnapshotCache()

    /** 已经弹过系统通知的审批（补拉 / 重连会重复拿到同一条）。 */
    private val notifiedApprovals = mutableSetOf<String>()

    /**
     * 属于**别的会话**的等待项（后端在等人，但用户此刻在看另一个会话）。
     *
     * 不写进 `_state` —— 那会让当前会话的界面冒出别人的卡片；但必须登记，
     * 否则系统通知里点「允许一次」时找不到这条，应答直接 `return`，点击等于没反应。
     * 切回该会话时 `session.resume` 会把它补成真正的卡片。
     */
    private val crossSessionApprovals = mutableMapOf<String, PendingApproval>()
    private val crossSessionClarify = mutableMapOf<String, PendingClarify>()

    /**
     * 等待用户决定的 `approval` / `clarify` 请求（S8：原 4 张平行表合并成 2 张）。
     *
     * 原来每次登记要同时写 `decisions[requestId]` 与 `frameIds[frameId]`，摘除要同时删两张；
     * 少写一处就是"卡片点了没反应"或"`request.cancel` 撤不掉"，且两者都不报错。
     * 现在成对关系由 [PendingRequests] 保证。
     */
    private val approvalRequests = PendingRequests<String>()
    private val clarifyRequests = PendingRequests<Map<String, String>?>()

    /** 用户手动关掉的后台任务行：直到 registry 也不再报告该 id 才忘记。 */
    private val dismissedTasks = mutableSetOf<String>()

    private var backgroundJob: Job? = null
    private val deltaBuffer = StringBuilder()

    private var loadJob: Job? = null
    private var flushJob: Job? = null
    private var localSeq = 0

    init {
        scope.launch {
            gateway.events.collect(::handleGatewayEvent)
        }
        // 重连后必须重新 resume：订阅与"本端会应答 server→client 请求"的声明都绑在**连接**上，
        // 断线重连后旧订阅作废。不重建的话，重连窗口内后端发出的审批 / clarify 我们永远收不到，
        // 只能靠用户手动刷新（触发 resume）从快照里补出来 —— 实测就是"审批不弹、刷新才有"。
        scope.launch {
            var wasConnected = false
            gateway.connection.collect { connection ->
                when (reconnectDecision(wasConnected, connection)) {
                    ReconnectDecision.Ignore -> return@collect
                    ReconnectDecision.Reset -> wasConnected = false
                    ReconnectDecision.ReResume -> {
                        wasConnected = true
                        // 只清"与连接绑定"的字段：runtimeId / cwd / dirty 都是会话事实，与连接无关。
                        // 迁移前这里是 runtimeIds.clear() + 三个 map.clear() —— 把 runtimeId 也清掉
                        // 会让事件反查失效（ADR 里记过这条）。
                        runtimes.clearConnectionScoped()
                        val key = _state.value.key
                        if (key == null) {
                            // 还没选会话：连接是新的，但仍要留下明确的 marker，
                            // 否则排查时无法区分"没恢复"和"没有会话可恢复"。
                            Log.info("gateway reconnected; no active session to re-resume")
                            return@collect
                        }
                        Log.info("gateway reconnected; re-resuming ${key.sessionId}")
                        runCatching { ensureRuntime(key) }
                            .onFailure { Log.warn("re-resume after reconnect failed: ${it.message}") }
                    }
                }
            }
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
        runtimes[key]?.runtimeId?.let { runtimeId ->
            refreshPendingApprovals(key, runtimeId)
            startBackgroundPolling(key, runtimeId)
            refreshUsage(key, runtimeId)
        }
        val stale = runtimes[key]?.dirty?.also { if (it) runtimes.markDirty(key, false) } ?: false
        // 决策表见 SessionSwitchPlan：快照 → 缓存 → 真读，force 时跳过前两步。
        when (switchPlanFor(key, force = force, stale = stale)) {
            SessionSwitchPlan.KEEP -> return
            // 先恢复切走时的快照（含流式段落）：切回瞬间就有内容，不会"看起来卡死"。
            SessionSwitchPlan.RESTORE_SNAPSHOT -> {
                val saved = snapshots.remove(key)
                if (saved != null) {
                    restore(key, saved)
                    // 快照只保证"切回瞬间有内容"。卡片 / 队列是**后端状态**，必须对账：
                    // 别处（系统通知 / TUI）可能已经把它答掉了，照着快照显示就是一张点不动的卡片。
                    scope.launch {
                        runCatching { ensureRuntime(key, force = true) }
                            .onFailure { Log.debug("revalidate after restore failed: ${it.message}") }
                    }
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
        snapshots.discard(key)

        cache.remove(key)
        loadJob?.cancel()
        // 先填上 pill 的初始值（不阻塞历史读取）：会话级 override 要等 `session.info`，
        // 这里给的是 profile 默认值。
        scope.launch { seedModelInfo(profile) }
        loadJob = scope.launch {
            // 置 Loading 时也带上模型：否则 seed / 之前读到的值会被这次整块替换冲掉（#16）。
            _state.value = ChatState(key = key, load = Loadable.Loading).withModelOf(key)
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
        scope.launch {
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
        ).withModelOf(key)
        scope.launch { seedModelInfo(profile) }
    }

    /**
     * 用 profile 默认模型填 pill 的初始值。
     *
     * 只在当前还没有模型时生效：`session.info` / `session.resume` 拿到的是**会话级**真值，
     * 那两条路径优先。接口失败就静默 —— pill 会停在"模型未知"（未读到，不是没有可用），
     * 点开菜单仍可用。
     */
    private suspend fun seedModelInfo(profile: String?) {
        // 已经读到该 profile 的默认值就不再请求；界面可能被整块替换过，补写一次即可。
        if (modelsCache.profileModel(profile) != null) {
            applyProfileModelIfMissing(profile)
            return
        }
        try {
            val info = models.current(profile) ?: return
            modelsCache.putProfile(profile, SessionModel(info.model, info.provider, reasoningEffort = null))
            applyProfileModelIfMissing(profile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            Log.debug("model.info failed: ${throwable.message}")
        }
    }

    /**
     * 记录某会话的模型真值（会话级）。传 null 的字段保留旧值（回执往往只带改动的那个）。
     */
    private fun rememberSessionModel(key: SessionKey, model: String?, provider: String?, reasoningEffort: String?) {
        modelsCache.putSession(key, model, provider, reasoningEffort)
    }

    /** 给一份新建的状态补上该会话已知的模型（不覆盖已带值的那份）。 */
    private fun ChatState.withModelOf(key: SessionKey): ChatState = withModelOf(modelsCache.forSession(key))

    /** profile 默认值读到了就补进当前 state（仅当还没有会话级真值时）。 */
    private fun applyProfileModelIfMissing(profile: String?) {
        val key = _state.value.key ?: return
        if (key.profile != profile) return
        if (modelsCache.hasSessionModel(key)) return
        val seeded = modelsCache.profileModel(profile) ?: return
        _state.update { current ->
            if (current.key != key || current.model != null) {
                current
            } else {
                current.copy(model = seeded.model, provider = seeded.provider)
            }
        }
    }
    fun reload(profile: String?, sessionId: String?) = open(profile, sessionId, force = true)

    /** 发送一条用户消息（Markdown 源码原样提交，不做任何改写）。 */
    fun send(text: String, attachments: List<PromptAttachment> = emptyList()) {
        val trimmed = text.trim()
        // 只有附件没有文字也要能发：后端接受空 text + attachments。
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        val current = _state.value
        val key = current.key ?: return
        if (current.sending) return

        scope.launch {
            _state.update { it.copy(sending = true, sendError = null) }
            try {
                val runtimeId = ensureRuntime(key)
                // 归属由"发送时是否正在生成"决定，而不是 ack 的 status：
                // 后端对新会话的首条消息也回 `queued`（agent 还没起来），那条显然属于这一轮。
                val wasBusy = current.isBusy
                if (attachments.isNotEmpty()) {
                    // 附件路径与 mime 是 admission 的硬约束；先记一条，失败时能和后端的
                    // invalid_params 对上号（见 `.design/待修复问题.md` #19）。
                    Log.info(
                        "prompt.submit 带附件 ${attachments.size} 个：" +
                            attachments.joinToString(", ") { "${it.path}(${it.mime})" },
                    )
                }
                val ack = repository.submitPrompt(key.profile, runtimeId, trimmed, attachments)
                Log.info(
                    "prompt.submit ack status=${ack.status} admission=${ack.admissionId} " +
                        "userRowId=${ack.userRowId} wasBusy=$wasBusy",
                )
                if (wasBusy && ack.isQueued && ack.admissionId != null) {
                    // 生成中发的那条：不进当前轮的 transcript，挂到排队区。
                    _state.update { state ->
                        state.copy(
                            queuedPrompts = state.queuedPrompts + QueuedPrompt(
                                admissionId = ack.admissionId,
                                text = trimmed,
                                sequence = ack.sequence,
                            ),
                        )
                    }
                } else {
                    _state.update { state -> state.appendLocalUser(nextLocalId("user"), trimmed, attachments) }
                    _state.update {
                        it.copy(streaming = it.streaming ?: StreamingState(startedAt = Clock.System.now()))
                    }
                    startTurnWatchdog(key, runtimeId)
                }
                // 后端在第一条 prompt 之后才给会话生成列表行 —— 这时刷新列表才看得到它。
                emit(ChatEffect.SessionPersisted)
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

    /** 队列里的某条「立刻插入」：把它的文本交给正在跑的那一轮（`prompt.steer_queued`）。 */
    fun steerQueuedPrompt(admissionId: String) {
        val key = _state.value.key ?: return
        val item = _state.value.queuedPrompts.firstOrNull { it.admissionId == admissionId } ?: return
        scope.launch {
            try {
                val runtimeId = ensureRuntime(key)
                repository.steerQueued(key.profile, runtimeId, admissionId)
                Log.info("prompt.steer_queued accepted admission=$admissionId")
                _state.update { state ->
                    state.copy(
                        queuedPrompts = state.queuedPrompts - item,
                        streaming = state.streaming ?: StreamingState(startedAt = Clock.System.now()),
                    )
                }
                emit(ChatEffect.SessionPersisted)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                // `steer_unavailable` / `unsupported_control` 是"当前这一轮接不了中途插入"
                // （agent 还没起来，或该模型不支持 mid-turn steer），不是参数错误 ——
                // 直接甩 4001 只会让人以为程序坏了。
                val reason = (throwable as? AppError.Server)?.reason
                val notice = when {
                    reason == null -> null
                    "steer_unavailable" in reason ->
                        "这一轮还没开始跑，暂时插不进去；等它跑起来再点，或点「取消」重新发"
                    "unsupported_control" in reason ->
                        "当前模型不支持中途插入；它会排在这一轮结束后自动发送（也可以点「取消」）"
                    "admission_started" in reason -> "这条已经在跑了，不用再插入"
                    "session_idle" in reason -> "这一轮已经结束，正在发送"
                    else -> null
                }
                Log.info("prompt.steer_queued refused: ${throwable.message}")
                if (notice != null) {
                    _state.update { it.copy(sendError = AppError.Notice(notice)) }
                    if ("session_idle" in notice || "admission_started" in notice) {
                        _state.update { state -> state.copy(queuedPrompts = state.queuedPrompts - item) }
                    }
                } else {
                    _state.update {
                        it.copy(sendError = ErrorMapper.from(throwable, RpcMethods.PROMPT_STEER_QUEUED))
                    }
                }
            }
        }
    }

    /** 取消队列里的某条（`prompt.cancel`）。 */
    fun cancelQueuedPrompt(admissionId: String) {
        val key = _state.value.key ?: return
        val item = _state.value.queuedPrompts.firstOrNull { it.admissionId == admissionId } ?: return
        scope.launch {
            try {
                val runtimeId = ensureRuntime(key)
                repository.cancelQueued(key.profile, runtimeId, admissionId)
                _state.update { state -> state.copy(queuedPrompts = state.queuedPrompts - item) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(sendError = ErrorMapper.from(throwable, RpcMethods.PROMPT_CANCEL))
                }
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
        scope.launch {
            _state.update { it.copy(steering = true, sendError = null) }
            try {
                val runtimeId = ensureRuntime(key)
                val status = repository.steer(key.profile, runtimeId, text, runtimes[key]?.fence)
                Log.info("session.steer accepted status=$status")
                _state.update { state -> state.appendLocalUser(nextLocalId("user"), text) }
                emit(ChatEffect.SessionPersisted)
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
        scope.launch {
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
            val rewound = repository.rewindTo(key.profile, runtimeId, rowId, fence(key))
            adoptFence(key, rewound.revision, rewound.generation)
            _state.update { state -> state.appendLocalUser(nextLocalId("user"), draft.text) }
            emit(ChatEffect.SessionPersisted)
            repository.submitPrompt(
                profile = key.profile,
                runtimeSessionId = runtimeId,
                text = draft.text,
            )
        } catch (throwable: Throwable) {
            val busy = (throwable as? AppError.Server)?.code == BUSY_CODE
            if (!busy) throw throwable
            Log.info("rewind hit busy session; interrupting then retrying")
            repository.interrupt(key.profile, runtimeId, runtimes[key]?.fence)
            waitUntilIdle()
            val rewound = repository.rewindTo(key.profile, runtimeId, rowId, fence(key))
            adoptFence(key, rewound.revision, rewound.generation)
            repository.submitPrompt(
                profile = key.profile,
                runtimeSessionId = runtimeId,
                text = draft.text,
            )
            _state.update { state -> state.appendLocalUser(nextLocalId("user"), draft.text) }
            emit(ChatEffect.SessionPersisted)
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
        scope.launch {
            try {
                val runtimeId = ensureRuntime(key)
                val result = repository.branch(
                    profile = key.profile,
                    runtimeSessionId = runtimeId,
                    throughMessageId = draft.messageId.toLongOrNull(),
                    fence = fence(key),
                )
                emit(ChatEffect.Branched(result))
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
        val runtimeId = runtimes[key]?.runtimeId ?: return
        refreshUsage(key, runtimeId)
    }

    /**
     * 按指定会话刷新用量。
     *
     * 恢复 / 切回会话时也要拉一次：否则顶栏那条用量一直是空的 ——
     * 它只在轮结束时刷新过，重新打开历史会话就什么都看不到。
     */
    private fun refreshUsage(key: SessionKey, runtimeId: String) {
        scope.launch {
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
        val runtimeId = runtimes[key]?.runtimeId ?: return
        scope.launch {
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
        val runtimeId = runtimes[key]?.runtimeId ?: return
        scope.launch {
            _state.update { it.copy(stopping = true) }
            try {
                repository.interrupt(key.profile, runtimeId, runtimes[key]?.fence)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(sendError = ErrorMapper.from(throwable, "session.interrupt")) }
            } finally {
                _state.update { it.copy(stopping = false) }
            }
        }
    }

    /**
     * 一条等待项已经不需要了：本端应答、后端撤回、或已被别处处理。
     *
     * 必须**同时**清当前状态和切走时保存的快照 —— `snapshots` 里的快照同样持有
     * `pendingApprovals`，切回该会话时会把它恢复出来；用户看到的是一张已经答过的卡片，
     * 再点一次就撞 `4001 stale_generation`（实测）。
     */
    private fun forgetPendingRequest(requestId: String) {
        _state.update { current ->
            current.copy(
                pendingApprovals = current.pendingApprovals.filterNot { it.requestId == requestId },
                pendingClarify = current.pendingClarify.filterNot { it.requestId == requestId },
            )
        }
        snapshots.updateAll { snapshot ->
            if (snapshot.pendingApprovals.none { it.requestId == requestId } &&
                snapshot.pendingClarify.none { it.requestId == requestId }
            ) {
                return@updateAll snapshot
            }
            snapshot.copy(
                pendingApprovals = snapshot.pendingApprovals.filterNot { it.requestId == requestId },
                pendingClarify = snapshot.pendingClarify.filterNot { it.requestId == requestId },
            )
        }
        crossSessionApprovals.remove(requestId)
        crossSessionClarify.remove(requestId)
    }

    /**
     * 后端说这条等待项已经不成立（代已推进 / 队列里没有它）。
     *
     * 同一件事可能被**别处先处理掉**：系统通知里点过、TUI 里答过、超时被撤回。
     * 这时再应答必然是 `stale_generation` —— 这不是故障，不该给用户弹红条。
     */
    private fun isAlreadySettled(throwable: Throwable): Boolean {
        val server = throwable as? AppError.Server ?: return false
        return server.reason == "stale_generation" ||
            server.reason == "approval_not_found" ||
            server.reason == "clarify_not_found" ||
            server.reason == "no_pending_prompt"
    }

    /** 审批卡片点击。 */
    fun resolveApproval(requestId: String, choice: String) {
        // 本连接收到的请求：有等待中的响应帧，直接交付决策即可。
        approvalRequests[requestId]?.decision?.let { decision ->
            decision.complete(choice)
            return
        }
        // 补拉回来的卡片（请求发出时本端不在线）：响应帧早就不存在，只能走 `approval.respond`。
        // 先看当前会话，再看不属于当前会话但已登记的那些（系统通知就是从这里点进来的）。
        val pending = _state.value.pendingApprovals.firstOrNull { it.requestId == requestId }
            ?: crossSessionApprovals[requestId]
            ?: return
        // **必须用卡片自己记的会话与 fence**：用户可能在别的会话里点系统通知，
        // 现查 `_state.value.key` / `generations[当前会话]` 拿到的是另一个会话的值，
        // 后端回 4001 stale_generation（实测：切会话后点通知必报）。
        val key = pending.sessionKey ?: _state.value.key ?: return
        val runtimeId = pending.runtimeSessionId ?: runtimes[key]?.runtimeId ?: return
        val generation = pending.executionGeneration ?: runtimes[key]?.fence
        forgetPendingRequest(requestId)
        scope.launch {
            try {
                repository.respondApproval(key.profile, runtimeId, requestId, choice, generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                if (isAlreadySettled(throwable)) {
                    // 别处已经处理过（系统通知 / TUI / 超时撤回）：静默收掉，不打扰。
                    Log.info("approval.respond ignored: $requestId already settled (${throwable.message})")
                    forgetPendingRequest(requestId)
                    return@launch
                }
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
        val requestId = approvalRequests.businessIdOf(cancelled.requestId) ?: cancelled.requestId
        approvalRequests.completeByRequestId(requestId, CHOICE_DENY)
        forgetPendingRequest(requestId)
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
        backgroundJob = scope.launch {
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
        val runtimeId = runtimes[key]?.runtimeId ?: return
        if (_state.value.killingTaskId != null) return
        _state.update { it.copy(killingTaskId = processId) }
        scope.launch {
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
        scope.launch {
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

    /** 切模型：有 live session 走 `session.mutate {operation:"model"}`，草稿态只落本地偏好（建会话时带过去）。 */
    fun switchModel(provider: String, model: String, confirmToken: String? = null) {
        val current = _state.value
        val key = current.key
        preferences.draftProvider = provider
        preferences.draftModel = model

        if (key == null) {
            // 还没会话：`session.mutate` 需要活会话（否则 4001），
            // 所以先只更新本地显示，新建会话时通过 `session.create` 生效。
            _state.update {
                it.copy(model = model, provider = provider, modelSwitching = false, modelConfirm = null)
            }
            return
        }

        _state.update { it.copy(modelSwitching = true, modelError = null, modelConfirm = null) }
        scope.launch {
            try {
                val outcome = switchModelRemote(key, provider, model, confirmToken)
                // 守卫拒绝是**成功回执**，里面的 revision/generation 也可能是新的：不采纳的话，
                // 用户点「继续」时带的是旧 fence，直接判 revision_conflict（v0.5.4 实测）。
                adoptFence(key, outcome.revision, outcome.executionGeneration)
                if (outcome.confirmRequired) {
                    _state.update {
                        it.copy(
                            modelSwitching = false,
                            modelConfirm = ModelConfirm(
                                provider = provider,
                                model = model,
                                message = outcome.confirmMessage.orEmpty(),
                                token = outcome.confirmToken.orEmpty(),
                            ),
                        )
                    }
                    return@launch
                }
                // 不在这里回读模型：切换成功后后端会推 `session.info`（deferred 时也可能延后），
                // 那才是显示的唯一真相源。mutate 回执带着新鲜 fence，写回去让下一次编辑不用试错。
                rememberSessionModel(key, model, provider, reasoningEffort = null)
                _state.update { it.copy(modelSwitching = false, model = model, provider = provider) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                Log.info("session.mutate model failed: ${throwable.message}")
                _state.update {
                    it.copy(
                        modelSwitching = false,
                        modelError = ErrorMapper.from(throwable, RpcMethods.SESSION_MUTATE),
                    )
                }
            }
        }
    }

    /**
     * 切模型的写入，fence 过期时自愈。
     *
     * `expected_revision` 每轮对话都会推进，而本地只在 resume / mutate 回执里更新 ——
     * 发过一轮消息后再切模型，本地值就是旧的，后端回 `revision_conflict`
     * （v0.5.4 实测：守卫确认框点「继续」必失败）。`mutateSession` 已用拒绝回执里的新鲜值
     * 重试过一次；这里再补一次 resume 兜底，覆盖"重试时又推进了"的情况。
     */
    private suspend fun switchModelRemote(
        key: SessionKey,
        provider: String,
        model: String,
        confirmToken: String?,
    ): ModelSwitchOutcome = retryOnStaleFence(key) {
        models.setModel(
            profile = key.profile,
            runtimeSessionId = ensureRuntime(key),
            provider = provider,
            model = model,
            revision = runtimes[key]?.revision,
            executionGeneration = runtimes[key]?.fence,
            confirmToken = confirmToken,
        )
    }

    private suspend fun retryOnStaleFence(
        key: SessionKey,
        action: suspend () -> ModelSwitchOutcome,
    ): ModelSwitchOutcome = try {
        action()
    } catch (refused: AppError.Server) {
        if (refused.reason !in STALE_FENCE_REASONS) throw refused
        Log.info("session.mutate refused (${refused.reason}); re-resuming ${key.sessionId} and retrying")
        refreshFence(key)
        action()
    }

    /** 只刷新 fence：不重跑 [ensureRuntime] 的整套副作用（轮询 / 审批补拉 / 用量刷新）。 */
    private suspend fun refreshFence(key: SessionKey) {
        val resumed = repository.resumeSession(key.profile, key.sessionId)
        runtimes.put(
            SessionRuntime(
                key = key,
                runtimeId = resumed.runtimeId,
                fence = resumed.executionGeneration,
                revision = resumed.revision,
                cwd = resumed.cwd ?: runtimes[key]?.cwd,
                dirty = runtimes[key]?.dirty ?: false,
                lastEventAt = runtimes[key]?.lastEventAt ?: 0L,
            ),
        )
    }

    /** 守卫确认框点了"继续"：把后端下发的 token 原样带回。 */
    fun confirmModelSwitch() {
        val confirm = _state.value.modelConfirm ?: return
        if (confirm.token.isBlank()) return
        switchModel(confirm.provider, confirm.model, confirmToken = confirm.token)
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

        rememberSessionModel(key, model = null, provider = null, reasoningEffort = effort)
        _state.update { it.copy(modelSwitching = true, modelError = null, reasoningEffort = effort) }
        scope.launch {
            try {
                val outcome = retryOnStaleFence(key) {
                    models.setReasoning(
                        key.profile,
                        ensureRuntime(key),
                        effort,
                        runtimes[key]?.revision,
                        runtimes[key]?.fence,
                    )
                }
                adoptFence(key, outcome.revision, outcome.executionGeneration)
                _state.update { it.copy(modelSwitching = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                Log.info("session.mutate reasoning failed: ${throwable.message}")
                _state.update {
                    it.copy(
                        modelSwitching = false,
                        modelError = ErrorMapper.from(throwable, RpcMethods.SESSION_MUTATE),
                    )
                }
            }
        }
    }

    /** 草稿态（还没有会话）的选择：新建会话时由 `session.create` 带过去。 */
    fun draftSelection(): Triple<String?, String?, String?> =
        Triple(preferences.draftProvider, preferences.draftModel, preferences.draftReasoning)

    /**
     * 释放：由 [dev.shinglem.hermes.component.chat.DefaultChatComponent] 的
     * `Holder.onDestroy` 调用（原 `onCleared()` 钩子，去 ViewModel 继承后要显式调）。
     */
    fun dispose() {
        gateway.removeServerRequestHandler(APPROVAL_REQUEST)
        gateway.removeServerRequestHandler(CLARIFY_REQUEST)
        // 两个待决表都要取消：漏掉 clarify 的那些 `CompletableDeferred` 会永远挂着，
        // 而 `awaitClarifyDecision` 的协程也就永远不结束（原 `onCleared()` 只清了审批那一半）。
        approvalRequests.cancelAll()
        clarifyRequests.cancelAll()
    }

    // ── 切走 / 切回：快照与脏标记 ────────────────────────────────────────

    /** 切走前把当前会话状态存起来（含流式段落），切回时可以原样恢复。 */
    private fun stashCurrentState() {
        val key = _state.value.key ?: return
        // 快照里有整段消息列表，`SessionSnapshotCache` 内部按上限淘汰（保留最近 12 个）。
        snapshots.put(key, _state.value)
    }

    private fun cachedStreaming(key: SessionKey): Boolean = snapshots.hasStreaming(key)

    /**
     * 切走期间在**影子状态**上继续处理该会话的流式事件。
     *
     * 只标记 dirty、等切回补拉是不够的：切回时那轮往往还在跑（审批就是在等它），
     * `restore` 看到 `isSessionLive` 为真就跳过补拉，而切走期间的增量早已被丢弃 ——
     * 界面于是永久停在切走前那一帧，必须手动点"刷新"（实测复现）。
     * 这里把事件累积到快照上，切回时快照本身就是最新的。
     */
    private fun trackShadow(key: SessionKey, event: GatewayEvent) {
        snapshots.track(key, event.toChatStreamEvent(), ::nextLocalId)
    }


    /** 把当前状态翻译成 [SessionSwitchPlan]（纯决策，见该文件的判据表）。 */
    private fun switchPlanFor(key: SessionKey, force: Boolean, stale: Boolean): SessionSwitchPlan = switchPlan(
        force = force,
        sameKeyReady = _state.value.key == key && _state.value.load is Loadable.Ready,
        stale = stale,
        cachedStreaming = cachedStreaming(key),
        hasSnapshot = snapshots.containsKey(key),
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
        scope.launch {
            if (_state.value.key != key) return@launch
            // 还在跑：后续 delta / complete 会把内容补齐，别覆盖流式段落。
            if (repository.isSessionLive(key.profile, key.sessionId)) return@launch
            reloadQuietly(key)
        }
    }

    /** 不置 Loading 的重新读取：恢复快照后发现那轮已结束，用它把内容补齐。 */
    private fun reloadQuietly(key: SessionKey) {
        scope.launch {
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
            runtimes[key]?.runtimeId?.let { refreshUsage(key, it) }
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
        if (runtimes[key] != null) {
            // runtime 可复用，但模型可能还没读到（#16）：补一次 profile 默认。
            // 这里不 force resume —— 那会重新注册 live session，代价远大于收益。
            if (!modelsCache.hasSessionModel(key)) {
                scope.launch { seedModelInfo(key.profile) }
            }
            return
        }
        scope.launch {
            try {
                ensureRuntime(key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                Log.debug("chat.resume (background) failed: ${throwable.message}")
            }
        }
    }

    private suspend fun ensureRuntime(key: SessionKey, force: Boolean = false): String {
        if (!force) runtimes[key]?.runtimeId?.let { return it }
        val resumed = repository.resumeSession(key.profile, key.sessionId)
        // canonical gateway 的「执行代」与 mutation revision：interrupt / steer / approval.respond
        // 都要求带 `execution_generation`，session.mutate 要求 `expected_revision`。
        // 两者都随每一轮推进，所以每次 resume 都刷新一遍。
        runtimes.put(
            SessionRuntime(
                key = key,
                runtimeId = resumed.runtimeId,
                fence = resumed.executionGeneration,
                revision = resumed.revision,
                cwd = resumed.cwd,
                dirty = runtimes[key]?.dirty ?: false,
                lastEventAt = runtimes[key]?.lastEventAt ?: 0L,
            ),
        )
        // resume 的 info 就带着当前模型/推理强度：先记进按 key 的 map（状态被整块替换也不丢），
        // 再刷新界面。期间切走了就别写 —— 那是另一个会话的 pill。
        rememberSessionModel(key, resumed.model, resumed.provider, resumed.reasoningEffort)
        _state.update { current ->
            if (current.key != key) return@update current
            current.copy(cwd = resumed.cwd ?: current.cwd).withModelOf(key)
        }
        Log.info("chat.resume stored=${key.sessionId} runtime=${resumed.runtimeId} running=${resumed.running} prompts=${resumed.prompts.size}")
        // 快照里挂着的 clarify 要补成卡片：它只能靠 `clarify.respond` 解开，
        // 不补的话 turn 会一直停在 `waiting`，界面上就是"一直正在生成"。
        adoptSnapshotPrompts(key, resumed)
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
        runtimes.update(key) {
            it.copy(revision = revision ?: it.revision, fence = generation ?: it.fence)
        }
    }

    /**
     * 用快照里的 `pending[]` 重建排队区。
     *
     * 队列是**后端的状态**，客户端只是镜像：重连、切回会话、以及每轮结束时都对齐一次，
     * 才能保证"生成中再发一条"的那条不会既不在队列里、又没进 transcript。
     */
    private fun adoptSnapshotQueue(key: SessionKey, resumed: ResumedSession) {
        if (_state.value.key != key) return
        val queued = resumed.pending.filter { it.isQueued }.map { item ->
            QueuedPrompt(admissionId = item.admissionId, text = item.text, sequence = item.sequence)
        }
        val current = _state.value.queuedPrompts
        if (queued == current) return
        if (queued.isEmpty() && current.isEmpty()) return
        Log.info("queued prompts aligned from snapshot: ${current.size} -> ${queued.size}")
        _state.update { it.copy(queuedPrompts = queued) }
    }

    /** 当前会话的 fence 快照（`session.mutate` / 控制类调用统一从这里取）。 */
    private fun fence(key: SessionKey): SessionFence =
        SessionFence(revision = runtimes[key]?.revision, generation = runtimes[key]?.fence)

    /**
     * 把 `session.resume` 快照里还没应答的**审批 / 澄清**补成卡片。
     *
     * 这条路径以前完全缺失：实时帧走 `awaitClarifyDecision` / `awaitApprovalDecision`，
     * 而**重连 / 切回会话**时那些请求帧早就不存在了。快照里的 `prompts[]` 只能靠
     * `approval.respond` / `clarify.respond` 作答 —— 不补卡片就是"后端在等、界面上什么都没有"，
     * turn 会一直停在 `waiting`（实测：一个 pwsh 审批让整轮卡住，界面上只看到转圈）。
     */
    private fun adoptSnapshotPrompts(key: SessionKey, resumed: ResumedSession) {
        adoptSnapshotQueue(key, resumed)
        // 快照是「后端此刻在等谁」的权威答案：本地有、快照里没有的，说明别处已经答掉了
        // （系统通知 / TUI / 超时撤回）。留着它只会让人再点一次撞 `4001 stale_generation`
        // —— 实测就是这样：通知里点掉的审批，对话里的卡片还在。
        if (_state.value.key == key) {
            val live = resumed.prompts.map { it.promptId }.toSet()
            val stale = _state.value.pendingApprovals.map { it.requestId }
                .filterNot { it in live } +
                _state.value.pendingClarify.map { it.requestId }.filterNot { it in live }
            stale.forEach { requestId ->
                Log.info("pending prompt $requestId no longer in snapshot; dropped")
                forgetPendingRequest(requestId)
            }
        }
        if (resumed.prompts.isEmpty()) return
        val added = resumed.prompts.sumOf { adoptOnePrompt(key, it, resumed.runtimeId) }
        if (added == 0) return
        Log.info("pending prompts restored from snapshot: total=${resumed.prompts.size} added=$added")
    }

    /**
     * 把一条待应答请求补成卡片；返回是否真的加进去了（已存在则跳过）。
     *
     * **两条来源共用**（快照 `prompts[]` 与事件 `approval.request` / `clarify.request`）：
     * 0.23.0 的 gateway 路线只发事件、不发 server→client 请求帧，只做后者卡片永远不弹。
     */
    private fun adoptOnePrompt(key: SessionKey, prompt: ResumedPrompt, runtimeId: String?): Int {
        val sessionId = runtimeId ?: runtimes[key]?.runtimeId
        return when (prompt.kind) {
            "approval" -> {
                if (_state.value.pendingApprovals.any { it.requestId == prompt.promptId }) return 0
                val approval = PendingApproval(
                    requestId = prompt.promptId,
                    command = prompt.command,
                    description = prompt.description,
                    choices = prompt.choices,
                    runtimeSessionId = sessionId,
                    // 没有请求帧可回，只能走 `approval.respond`。
                    fromRequest = false,
                    sessionKey = key,
                    executionGeneration = prompt.executionGeneration ?: runtimes[key]?.fence,
                )
                _state.update { it.copy(pendingApprovals = it.pendingApprovals + approval) }
                // 切回该会话后快照会把它补成真正的卡片，跨会话登记表里的那条就过期了。
                crossSessionApprovals.remove(approval.requestId)
                emit(ChatEffect.ApprovalRequested(approval, key))
                1
            }
            "clarify" -> {
                if (_state.value.pendingClarify.any { it.requestId == prompt.promptId }) return 0
                val clarify = PendingClarify(
                    requestId = prompt.promptId,
                    frameId = "",
                    questions = listOf(
                        ClarifyQuestion(
                            qid = prompt.promptId,
                            question = prompt.question,
                            choices = prompt.choices,
                            multiSelect = prompt.multiSelect,
                        ),
                    ),
                    runtimeSessionId = sessionId,
                    sessionKey = key,
                    promptId = prompt.promptId,
                    executionGeneration = prompt.executionGeneration ?: runtimes[key]?.fence,
                )
                _state.update { it.copy(pendingClarify = it.pendingClarify + clarify) }
                emit(ChatEffect.ClarifyRequested(clarify, key))
                1
            }
            // 新增"等待用户"的类型必须在这里显式加分支（见 11-后端API调用规范.md §5）：
            // 漏一个就是"后端在等、界面上什么都没有"，静默卡死且没有任何错误日志。
            else -> {
                Log.warn("snapshot/event prompt kind=${prompt.kind} 未处理（可能会卡住这一轮）")
                0
            }
        }
    }

    /**
     * 别的会话里"后端在等人"：登记 + 弹提醒，但**不写当前 UI**。
     *
     * 之前这类事件被 `otherKey` 分支整条丢掉：用户发了审批请求后立刻切到别的对话，
     * 审批到达时既没弹通知、也没进任何队列，只有切回来才从 `session.resume` 快照看到。
     */
    private fun notifyCrossSessionPending(otherKey: SessionKey, event: GatewayEvent) {
        if (event.type != GatewayEventTypes.APPROVAL_REQUEST &&
            event.type != GatewayEventTypes.CLARIFY_REQUEST
        ) {
            return
        }
        val prompt = (event.payload as? JsonObject)?.toResumedPrompt() ?: return
        val runtimeId = event.sessionId
        when (prompt.kind) {
            "approval" -> {
                val approval = PendingApproval(
                    requestId = prompt.promptId,
                    command = prompt.command,
                    description = prompt.description,
                    choices = prompt.choices,
                    runtimeSessionId = runtimeId,
                    fromRequest = false,
                    sessionKey = otherKey,
                    executionGeneration = prompt.executionGeneration ?: runtimes[otherKey]?.fence,
                )
                crossSessionApprovals[approval.requestId] = approval
                if (notifiedApprovals.add(approval.requestId)) {
                    Log.info("pending approval for other session ${otherKey.sessionId}: ${prompt.promptId}")
                    emit(ChatEffect.ApprovalRequested(approval, otherKey))
                }
            }

            "clarify" -> {
                val clarify = PendingClarify(
                    requestId = prompt.promptId,
                    frameId = "",
                    questions = listOf(
                        ClarifyQuestion(
                            qid = prompt.promptId,
                            question = prompt.question,
                            choices = prompt.choices,
                            multiSelect = prompt.multiSelect,
                        ),
                    ),
                    runtimeSessionId = runtimeId,
                    sessionKey = otherKey,
                    promptId = prompt.promptId,
                    executionGeneration = prompt.executionGeneration ?: runtimes[otherKey]?.fence,
                )
                crossSessionClarify[clarify.requestId] = clarify
                Log.info("pending clarify for other session ${otherKey.sessionId}: ${prompt.promptId}")
                emit(ChatEffect.ClarifyRequested(clarify, otherKey))
            }
        }
    }

    override val storedSessionId: String? get() = _state.value.key?.sessionId

    override fun runtimeInfo(): RuntimeHandle? {
        val key = _state.value.key ?: return null
        val runtimeId = runtimes[key]?.runtimeId ?: return null
        return RuntimeHandle(runtimeId, runtimes[key]?.cwd)
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
        return RuntimeHandle(runtimeId, runtimes[key]?.cwd)
    }

    private suspend fun handleGatewayEvent(event: GatewayEvent) {
        // 每一帧都带着当前执行代：先跟上它，再决定这帧要不要渲染。
        // 不更新的话，"停止 / 纠偏 / 审批"会拿着上一代的凭据去撞 `4001 stale_generation`。
        event.executionGeneration?.let { gen ->
            event.sessionId
                ?.let { sid -> runtimes.byRuntimeId(sid) }
                ?.let { owner -> runtimes.update(owner) { it.copy(fence = gen) } }
        }
        // 看门狗按"最近一次该会话的事件"判断是否静默。
        event.sessionId
            ?.let { sid -> runtimes.byRuntimeId(sid) }
            ?.let { owner ->
                runtimes.touch(owner, Clock.System.now().toEpochMilliseconds())
                // 事件恢复了就撤掉看门狗留下的"后端仍在执行"提示（#18），
                // 否则它会把已经重新流动的一轮继续描述成"静默中"。
                if (owner == _state.value.key) {
                    _state.update { current ->
                        if (current.streaming?.stalledSeconds == null) {
                            current
                        } else {
                            current.copy(streaming = current.streaming?.copy(stalledSeconds = null))
                        }
                    }
                }
            }
        val key = _state.value.key ?: return
        // 别的会话 / 别的 profile：只标记"有新内容"，切回时补拉；绝不写当前 UI。
        val otherKey = event.sessionId
            ?.let { sid -> runtimes.byRuntimeId(sid) }
            ?.takeIf { it != key }
        if (otherKey != null) {
            // 「后端在等人」不是"这个会话有新内容"：用户此刻可能在别的会话里，
            // 也必须弹提醒，否则要切回来才知道（实测：切走后就再也不弹）。
            notifyCrossSessionPending(otherKey, event)
            if (isContentEvent(event.type)) {
                runtimes.markDirty(otherKey)
                trackShadow(otherKey, event)
                Log.debug("chat event tracked ${event.type} for ${otherKey.sessionId}")
            }
            return
        }
        val runtimeId = runtimes[key]?.runtimeId ?: return
        // 认不出归属的事件（sessionId 为空，或不是本端已知的 runtime）按原规则过滤。
        if (event.sessionId != null && event.sessionId != runtimeId) {
            // 重连时会清空 runtimeIds，别的会话的 runtime 映射随之丢失 —— 这时它的审批事件
            // 会落到这里被丢掉。审批 / 提问是"有人在等"，漏掉就是静默卡死，至少留下可查的记录。
            if (event.type == GatewayEventTypes.APPROVAL_REQUEST ||
                event.type == GatewayEventTypes.CLARIFY_REQUEST
            ) {
                Log.warn("pending ${event.type} dropped: unknown runtime ${event.sessionId} (current=$runtimeId)")
            } else {
                Log.debug("chat event ignored ${event.type} sid=${event.sessionId} (current=$runtimeId)")
            }
            return
        }
        // 后端在等人：gateway 路线**只发事件**，不发 server→client 请求帧。
        // 漏了这两条，卡片永远不弹（只有刷新才从快照里补出来 —— 实测踩过）。
        if (event.type == GatewayEventTypes.APPROVAL_REQUEST ||
            event.type == GatewayEventTypes.CLARIFY_REQUEST
        ) {
            (event.payload as? JsonObject)?.toResumedPrompt()?.let { prompt ->
                if (adoptOnePrompt(key, prompt, runtimeId) > 0) {
                    Log.info("pending prompt from event: kind=${prompt.kind} id=${prompt.promptId}")
                }
            }
            return
        }
        if (event.type == GatewayEventTypes.REQUEST_CANCEL) {
            val payload = event.payload as? JsonObject
            val id = (payload?.get("id") as? JsonPrimitive)?.contentOrNull
            val method = (payload?.get("method") as? JsonPrimitive)?.contentOrNull
            if (id != null) {
                forgetPendingRequest(id)
                approvalRequests.completeByRequestId(id, CHOICE_DENY)
                Log.info("request.cancel method=$method id=$id")
            }
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

            is ChatStreamEvent.SessionInfoChanged -> {
                // 后端推的 session.info 是会话级真值（模型 fallback 后也会走这里）：
                // 先记进 map，再刷新界面。
                _state.value.key?.let {
                    rememberSessionModel(it, parsed.model, parsed.provider, parsed.reasoningEffort)
                }
                _state.update { current ->
                    current.copy(
                        model = parsed.model?.takeIf { it.isNotBlank() } ?: current.model,
                        provider = parsed.provider?.takeIf { it.isNotBlank() } ?: current.provider,
                        reasoningEffort = parsed.reasoningEffort?.takeIf { it.isNotBlank() }
                            ?: current.reasoningEffort,
                        modelSwitching = false,
                    )
                }
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
        flushJob = scope.launch {
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
        reduceInPlace(ChatStreamEvent.ReasoningDelta(text))
    }

    /** `message.interim`：当前这段旁白封段，后续正文另起一段。 */
    private fun sealInterim(interim: ChatStreamEvent.Interim) {
        flushDeltaBuffer()
        reduceInPlace(interim)
    }

    private fun upsertTool(started: ChatStreamEvent.ToolStarted) {
        // 先把缓冲里的正文落盘，工具段才会排在它后面而不是把它吞掉。
        flushDeltaBuffer()
        reduceInPlace(started)
    }

    private fun finishTool(completed: ChatStreamEvent.ToolCompleted) {
        reduceInPlace(completed)
    }

    /**
     * 把一条事件交给 [reduceStreamEvent] 应用到自己身上。
     *
     * 迁移前这里是 5 个各自手写变换的私有方法，与 `SessionShadow` 里那份平行实现漂移过
     * （失败的提示只在一边补了）。现在变换只有一份，这里只负责"把它写进 state"。
     */
    private fun reduceInPlace(event: ChatStreamEvent) {
        _state.update { current -> current.reduceStreamEvent(event, ::nextLocalId) }
    }

    private fun finishTurn(completed: ChatStreamEvent.Completed) {
        flushDeltaBuffer()
        // 变换统一走 ChatReducer（与后台影子路径同一份实现）；这里只做副作用：
        // 刷新上下文占用、发一次性事件。
        val closingText = _state.value.streaming?.segments
            ?.filterIsInstance<StreamSegment.Answer>()
            ?.lastOrNull()
            ?.text
            .orEmpty()
        reduceInPlace(completed)
        // 一轮结束后刷新上下文占用（不在流式路径里刷）。
        refreshUsage()
        emit(ChatEffect.TurnFinished(completed.failed, closingText.ifBlank { completed.text }))
    }

    private suspend fun awaitApprovalDecision(frameId: String, params: JsonElement?): JsonElement? {
        val obj = params as? JsonObject ?: return null
        val parsed = obj.toPendingApproval(fromRequest = true) ?: return null
        val request = parsed.copy(sessionKey = keyFor(parsed.runtimeSessionId))
        val decision = CompletableDeferred<String>()
        approvalRequests.register(PendingRequest<String>(frameId, request.requestId, decision))
        _state.update { it.copy(pendingApprovals = it.pendingApprovals + request) }
        // 非前台时的系统通知只发一次（补拉 / 重连会重复拿到同一条）。
        if (notifiedApprovals.add(request.requestId)) {
            emit(ChatEffect.ApprovalRequested(request, keyFor(request.runtimeSessionId)))
        }
        // 告诉后端卡片已上屏：它的超时时钟从这里才开始算（先前的时钟只在等客户端上屏）。
        acknowledgeApproval(
            requestId = request.requestId,
            runtimeId = request.runtimeSessionId ?: _state.value.key?.let { runtimes[it]?.runtimeId },
        )
        val choice = try {
            // 后端自己的审批超时是 300s；这里必须比它长，否则会在后端还在等的时候先替用户拒绝。
            withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { decision.await() } ?: CHOICE_DENY
        } finally {
            approvalRequests.removeByRequestId(request.requestId)
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
        clarifyRequests.register(PendingRequest(frameId, request.requestId, decision))
        _state.update { it.copy(pendingClarify = it.pendingClarify + request) }
        emit(ChatEffect.ClarifyRequested(request, keyFor(request.runtimeSessionId)))
        val answers = try {
            withTimeoutOrNull(CLARIFY_TIMEOUT_MS) { decision.await() }
        } finally {
            clarifyRequests.removeByRequestId(request.requestId)
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

    /** `clarify.respond` 的 `answer`（单问回文本，多问回 JSON 对象字符串）。 */
    private fun clarifyAnswer(answers: Map<String, String>): String {
        if (answers.isEmpty()) return ""
        if (answers.size == 1) return answers.values.first()
        return buildJsonObject { answers.forEach { (qid, text) -> put(qid, text) } }.toString()
    }

    /** 作答一个 `clarify`；批量请求的 [answers] 键是 `qid`。 */
    fun answerClarify(requestId: String, answers: Map<String, String>) {
        // 实时路径：响应帧还挂着，直接交付。
        clarifyRequests[requestId]?.decision?.let { decision ->
            decision.complete(answers)
            return
        }
        // 补拉路径：帧早就不存在了，只能发 `clarify.respond`（要 prompt_id + execution_generation）。
        val pending = _state.value.pendingClarify.firstOrNull { it.requestId == requestId }
            ?: crossSessionClarify[requestId]
            ?: return
        val promptId = pending.promptId ?: return
        val key = pending.sessionKey ?: _state.value.key ?: return
        val runtimeId = pending.runtimeSessionId ?: runtimes[key]?.runtimeId ?: return
        forgetPendingRequest(requestId)
        scope.launch {
            try {
                repository.respondClarify(
                    profile = key.profile,
                    runtimeSessionId = runtimeId,
                    promptId = promptId,
                    answer = clarifyAnswer(answers),
                    executionGeneration = pending.executionGeneration ?: runtimes[key]?.fence,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                if (isAlreadySettled(throwable)) {
                    Log.info("clarify.respond ignored: $requestId already settled (${throwable.message})")
                    forgetPendingRequest(requestId)
                    return@launch
                }
                Log.info("clarify.respond failed: ${throwable.message}")
                _state.update {
                    it.copy(sendError = ErrorMapper.from(throwable, RpcMethods.CLARIFY_RESPOND))
                }
            }
        }
    }

    /** 取消 `clarify`：回空结果，后端按"用户放弃"处理。 */
    fun cancelClarify(requestId: String) {
        clarifyRequests.takeDecisionByFrameId(requestId)?.complete(null)
    }

    /**
     * 提交后的看门狗：ack 只代表"已被接受"，真正开跑靠事件。
     *
     * 实测踩到过两种"一直正在生成"：后端把 admission 排进队列后没开跑（上一轮卡在
     * `clarify` 等应答），以及 runtime 被重建后这条 admission 丢了。两种情况后端都不会
     * 再发事件，界面就会永远转圈。这里在**长时间静默**时主动对账一次：
     * `running=false` 才收尾（`running=true` 说明真的在跑，长工具调用不该被打断）。
     */
    /**
     * 手动重连传输层。
     *
     * 重连成功后 `Connected` 会触发 init 里的 re-resume（见 [reconnectDecision]），
     * 会话订阅与审批通道都会重建，所以这里只要重启 socket。
     */
    fun reconnect() {
        Log.info("manual reconnect requested")
        gateway.stop()
        gateway.start()
    }

    private fun startTurnWatchdog(key: SessionKey, runtimeId: String) {
        scope.launch {
            delay(TURN_SILENCE_TIMEOUT_MS)
            if (_state.value.key != key || _state.value.streaming == null) return@launch
            val silentFor = Clock.System.now().toEpochMilliseconds() - (runtimes[key]?.lastEventAt ?: 0L)
            if (silentFor < TURN_SILENCE_TIMEOUT_MS) return@launch
            Log.info("turn watchdog: ${silentFor}ms 静默，开始对账")
            val resumed = runCatching { repository.resumeSession(key.profile, key.sessionId) }.getOrNull()
                ?: return@launch
            adoptFence(key, resumed.revision, resumed.executionGeneration)
            adoptSnapshotPrompts(key, resumed)
            if (resumed.running) {
                Log.info("turn watchdog: 后端仍在跑，保持等待")
                // 让用户看到"后端确实还在跑"而不是一片静默（长工具调用期间没有事件）。
                _state.update { current ->
                    if (current.key != key) {
                        current
                    } else {
                        current.copy(streaming = current.streaming?.copy(stalledSeconds = silentFor / 1000))
                    }
                }
                return@launch
            }
            Log.info("turn watchdog: 后端已空闲，收尾并重读历史")
            flushDeltaBuffer()
            _state.update { current ->
                current.copy(
                    streaming = null,
                    sendError = AppError.Notice("这一轮没有开始执行（后端已空闲），请重发或检查后端日志"),
                )
            }
            // 重新打开一次，把后端侧可能已经落库的内容拉回来。
            open(key.profile, key.sessionId, force = true)
        }
    }

    private fun isContentEvent(type: String): Boolean = type in CONTENT_EVENT_TYPES

    /**
     * 事件 / 请求属于哪个会话。
     *
     * 优先按 runtime id 反查（多会话场景）；查不到时退回当前打开的会话（单会话场景）。
     * 通知点击要跳转，所以必须给 stored id 而不是 runtime id。
     *
     * **当前会话的 runtime 命中时优先用它**：反查表里同一个 runtime 可能残留过别的
     * stored key（重连 / 重开同一会话），拿到旧 key 会把「正在看的会话」判成别的会话，
     * 表现为审批卡片就在眼前却又弹一条系统通知。
     */
    private fun keyFor(runtimeSessionId: String?): SessionKey? {
        val current = _state.value.key
        if (runtimeSessionId == null) return current
        if (current != null && runtimes[current]?.runtimeId == runtimeSessionId) return current
        return runtimes.byRuntimeId(runtimeSessionId) ?: current
    }

    private fun acknowledgeApproval(requestId: String, runtimeId: String?) {
        val key = _state.value.key ?: return
        if (runtimeId.isNullOrBlank()) return
        scope.launch {
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
        scope.launch {
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
                    emit(ChatEffect.ApprovalRequested(approval, key))
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
            // 模型/用量都是会话级状态：从 map 取（不是从上一份状态取），
            // 这样切走再切回、以及状态被整块替换后都还能显示上次读到的模型（#16）。
            model = modelsCache.forSession(key)?.model,
            provider = modelsCache.forSession(key)?.provider,
            reasoningEffort = modelsCache.forSession(key)?.reasoningEffort,
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

    private fun ChatState.appendLocalUser(
        id: String,
        text: String,
        attachments: List<PromptAttachment> = emptyList(),
    ): ChatState {
        val item = ChatItem.UserMessage(
            id = id,
            timestamp = Clock.System.now(),
            text = text,
            // 乐观行也要带上附件，否则"刚发出去的那条"看不到图，要等重新拉历史才出现（#20）。
            attachments = attachments.map { UserAttachment(path = it.path, name = it.path.fileNameOrPath()) },
        )
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

        /**
         * 提交后多久没有任何该会话的事件就去看门狗对账一次。
         *
         * 取 90s：正常一轮里 `agent.step` / `thinking.delta` 的间隔远小于它，
         * 而遇到 clarify / 重启这类"后端在等、不会推事件"的情况又不至于让用户干等太久。
         */
        const val TURN_SILENCE_TIMEOUT_MS = 90_000L

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

        /** fence 过期的拒绝原因：重新 resume 拿新鲜值后重试一次（`gateway/session_controls.py`）。 */
        val STALE_FENCE_REASONS = setOf("revision_conflict", "stale_generation")

        /** 后端"会话忙"的错误码（`methods_prompt.py`）。 */
        const val BUSY_CODE = 4009
        const val REWIND_POLL_MS = 500L
        const val REWIND_WAIT_LIMIT_MS = 90_000L
    }
}
