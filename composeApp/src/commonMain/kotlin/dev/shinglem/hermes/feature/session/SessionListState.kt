package dev.shinglem.hermes.feature.session

import dev.shinglem.hermes.core.contract.RpcMethods
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.error.ErrorMapper
import dev.shinglem.hermes.core.prefs.AppPreferences
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import dev.shinglem.hermes.core.time.SessionGroup
import dev.shinglem.hermes.core.time.TimeFormat
import dev.shinglem.hermes.core.transport.RpcJson
import dev.shinglem.hermes.feature.chat.ChatRepository
import dev.shinglem.hermes.feature.chat.projectChat
import dev.shinglem.hermes.platform.pickSaveFile
import dev.shinglem.hermes.platform.writeTextFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

/** 中栏的一行：分组标题或会话。 */
sealed interface SessionRow {
    /** `pinned` 为 true 时是「置顶」小分组（`group` 为 null），否则是时间分组标题。 */
    data class Header(val group: SessionGroup?, val collapsed: Boolean) : SessionRow
    data class Item(val session: SessionSummary) : SessionRow
}

/** 重命名对话框的草稿：非空表示对话框打开。 */
data class RenameDraft(val sessionId: String, val title: String)

/**
 * 归档过的会话快照。
 *
 * 后端 `session.list` 不返回归档行，所以"已归档"列表只能靠自己记住；
 * 恢复（`archived=false`）成功后从快照里删掉。
 */
@Serializable
data class ArchivedSessionRef(
    val id: String,
    val title: String,
    @SerialName("started_at") val startedAt: Double = 0.0,
)

/** 内容搜索结果的一行（`GET /api/sessions/search`）。 */
data class SessionSearchHit(
    val sessionId: String,
    val title: String,
    val snippet: String,
    val matchedRole: String?,
)

data class SessionListState(
    val load: Loadable<List<SessionSummary>> = Loadable.Idle,
    /**
     * 列表所属 profile。
     *
     * `selectedId` 只有在本字段 == 当前 profile 时才有意义：切 profile 后旧 id
     * 在新 profile 里不存在，拿它去读历史会 404（`{"detail":"Session not found"}`）。
     */
    val profile: String? = null,
    val query: String = "",
    val collapsedGroups: Set<SessionGroup> = emptySet(),
    val selectedId: String? = null,
    /** 刚由「新建会话」造出来、后端尚未落库的 id（它的 history 是 404，不是错误）。 */
    val justCreatedId: String? = null,
    /** 正在新建会话（按钮转圈 + 防重复点击）。 */
    val creating: Boolean = false,
    val createError: AppError? = null,
    /** 置顶的会话 id（本地偏好，排序时优先）。 */
    val pinned: Set<String> = emptySet(),
    /** 置顶分组的折叠状态（与时间分组分开记）。 */
    val pinnedCollapsed: Boolean = false,
    /** 行级写操作（重命名 / 删除）正在进行的那一行。 */
    val rowBusyId: String? = null,
    /** 重命名对话框；null = 关闭。 */
    val rename: RenameDraft? = null,
    /** 待确认删除的会话 id；null = 关闭。 */
    val confirmDeleteId: String? = null,
    /** 行级操作失败（重命名 / 删除），展示后由 UI 调 dismissRowError 清掉。 */
    val rowError: AppError? = null,
    /** 归档过的会话快照（本地记住，供「已归档」区恢复）。 */
    val archived: List<ArchivedSessionRef> = emptyList(),
    /** 「已归档」区的展开状态。 */
    val archivedExpanded: Boolean = false,
    /** 待确认归档的会话 id；null = 关闭。 */
    val confirmArchiveId: String? = null,
    /** 正在跑（后端 live）的会话 stored id：列表行上画运行态点。 */
    val activeIds: Set<String> = emptySet(),
    /** 正在复制为新会话的那一行。 */
    val duplicatingId: String? = null,
    /** 正在导出（拉全量消息可能有点久）。 */
    val exporting: Boolean = false,
    /** 一次性提示（导出到哪里、复制成功等）。 */
    val notice: String? = null,
    /** 内容搜索词（与标题过滤分开：一个查本地标题，一个查后端全文）。 */
    val contentQuery: String = "",
    val contentResults: Loadable<List<SessionSearchHit>> = Loadable.Idle,
) {
    val sessions: List<SessionSummary> get() = load.valueOrNull.orEmpty()

    val error: AppError? get() = (load as? Loadable.Failed)?.error

    /**
     * 当前选中的会话。新建出来的会话在第一条 prompt 之前不在 `session.list` 里，
     * 这时返回一个**占位**（标题为空 = 未命名），让右栏能显示空白对话而不是"未选择会话"。
     */
    fun selectedSession(): SessionSummary? {
        val id = selectedId ?: return null
        return sessions.firstOrNull { it.id == id }
            ?: SessionSummary(
                id = id,
                title = "",
                preview = "",
                startedAt = Clock.System.now(),
                messageCount = 0,
                source = "desktop",
            )
    }

    /** 占位会话：标题与预览都为空，说明后端还没给它生成名字。 */
    val SessionSummary.isUntitled: Boolean get() = title.isBlank() && preview.isBlank()

    /**
     * 切到 [target] profile：选择与「刚新建」标记一起作废，列表交给调用方重新加载。
     * profile 没变时原样返回（刷新列表不该把用户的选择清掉）。
     */
    fun switchedTo(target: String?): SessionListState =
        if (profile == target) this
        else copy(profile = target, selectedId = null, justCreatedId = null)
}

/**
 * 分组 + 搜索 + 折叠后的行序列。
 *
 * 置顶的会话先出一个「置顶」分组（`group = null`），其余按时间分组；
 * 置顶本身是**本地偏好**（后端没有 pin 概念），只影响排序。
 *
 * 用 `key` 区分 Header / Item，LazyColumn 才能正确回收（`03-编码规范` §5）。
 * `now` 由调用方传入并在 `remember` 里固定，避免每帧重算分组。
 */
fun buildSessionRows(
    sessions: List<SessionSummary>,
    query: String,
    collapsedGroups: Set<SessionGroup>,
    now: Instant = Clock.System.now(),
    pinned: Set<String> = emptySet(),
    pinnedCollapsed: Boolean = false,
): List<SessionRow> {
    val needle = query.trim()
    val filtered = if (needle.isEmpty()) {
        sessions
    } else {
        sessions.filter {
            it.displayTitle.contains(needle, ignoreCase = true) ||
                it.preview.contains(needle, ignoreCase = true)
        }
    }
    val (pinnedRows, restRows) = filtered.partition { it.id in pinned }
    val grouped = restRows
        .sortedByDescending { it.startedAt }
        .groupBy { TimeFormat.groupOf(it.startedAt, now) }

    return buildList {
        if (pinnedRows.isNotEmpty()) {
            add(SessionRow.Header(group = null, collapsed = pinnedCollapsed))
            if (!pinnedCollapsed) {
                pinnedRows.sortedByDescending { it.startedAt }.forEach { add(SessionRow.Item(it)) }
            }
        }
        SessionGroup.entries.forEach { group ->
            val items = grouped[group] ?: return@forEach
            val collapsed = group in collapsedGroups
            add(SessionRow.Header(group, collapsed))
            if (!collapsed) items.forEach { add(SessionRow.Item(it)) }
        }
    }
}

class SessionListViewModel(
    private val repository: SessionRepository,
    private val preferences: AppPreferences,
    /** 导出 / 复制要读全量历史，所以会话列表也依赖 chat 域的数据入口。 */
    private val chat: ChatRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SessionListState())
    val state: StateFlow<SessionListState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    /** 内容搜索的防抖 job。 */
    private var searchJob: Job? = null

    /** `sessions.changed` 的去抖 job：一轮对话里后端会连推多条，合并成一次刷新。 */
    private var changedJob: Job? = null

    /**
     * profile 切换：**同步**作废上一轮的选择与列表。
     *
     * 关键是 `selectedId` 必须立刻清掉：否则 UI 会拿旧 profile 的会话 id 去新 profile 读历史，
     * 后端返回 404 `{"detail":"Session not found"}`，首屏直接弹错误条（实测踩到）。
     */
    fun onProfileChanged(profile: String?) {
        refreshJob?.cancel()
        _state.update { it.switchedTo(profile).copy(load = Loadable.Idle) }
    }

    /** 切 profile 时取消上一轮请求，避免旧 profile 的结果覆盖新列表。 */
    fun refresh(profile: String?) {
        refreshJob?.cancel()
        val profileChanged = _state.value.profile != profile
        // 直接从 refresh 进来（未经 onProfileChanged）时也要保证选择与 profile 一致。
        _state.update { it.switchedTo(profile) }
        refreshJob = viewModelScope.launch {
            // 已经有列表时**静默刷新**：置 Loading 会让 `sessions` 变空、整片列表被换成骨架行，
            // 而 `sessions.changed` 在流式期间会连着来 —— 表现就是列表不停闪。
            _state.update { current ->
                if (profileChanged || current.load.valueOrNull == null) {
                    current.copy(load = Loadable.Loading)
                } else {
                    current
                }
            }
            try {
                val sessions = repository.listSessions(profile)
                _state.update { it.copy(load = Loadable.Ready(sessions)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                val error = ErrorMapper.from(throwable, "session.list")
                _state.update { current ->
                    current.copy(load = Loadable.Failed(error, current.load.valueOrNull))
                }
            }
        }
    }

    fun select(sessionId: String?) = _state.update {
        it.copy(selectedId = sessionId, justCreatedId = null)
    }

    /**
     * 后端推了 `sessions.changed`（新建 / 归档 / 标题变化 / 别的客户端删了会话）。
     *
     * 一轮对话里这个事件会连着来好几条，所以去抖 400ms 再刷新一次；
     * 正在新建会话时不刷（会把刚造出来还没落库的那条"闪掉"）。
     */
    fun onSessionsChanged() {
        if (_state.value.creating) return
        val profile = _state.value.profile
        changedJob?.cancel()
        changedJob = viewModelScope.launch {
            delay(SESSIONS_CHANGED_DEBOUNCE_MS)
            refresh(profile)
        }
    }

    /**
     * 循环切到相邻会话（`Ctrl+Shift+[` / `]`）。
     *
     * 只动选择，不刷新列表 —— 与点击列表项等价。
     */
    fun cycleSelection(delta: Int) {
        val current = _state.value
        val list = current.sessions
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.id == current.selectedId }
        val next = ((if (index < 0) 0 else index) + delta).mod(list.size)
        select(list[next].id)
    }

    /**
     * 新建空会话并选中它。
     *
     * 新建出的会话在第一条 prompt 之前不会出现在 `session.list` 里（后端行为），
     * 所以这里**只**设置选中态，不刷新列表 —— 刷新反而会让用户刚建的会话"凭空消失"。
     * 第一次发送成功后再由调用方触发刷新。
     */
    fun create(profile: String?) {
        if (_state.value.creating) return
        viewModelScope.launch {
            _state.update { it.copy(creating = true, createError = null) }
            try {
                // 草稿态在 composer 里选过的模型，建会话时一起带过去（per-session override）。
                val sessionId = repository.createSession(
                    profile = profile,
                    provider = preferences.draftProvider,
                    model = preferences.draftModel,
                    reasoningEffort = preferences.draftReasoning,
                )
                _state.update {
                    it.copy(
                        creating = false,
                        profile = profile,
                        selectedId = sessionId,
                        justCreatedId = sessionId,
                        query = "",
                        createError = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(
                        creating = false,
                        createError = ErrorMapper.from(throwable, RpcMethods.SESSION_CREATE),
                    )
                }
            }
        }
    }

    fun dismissCreateError() = _state.update { it.copy(createError = null) }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun toggleGroup(group: SessionGroup) = _state.update { current ->
        val collapsed = if (group in current.collapsedGroups) {
            current.collapsedGroups - group
        } else {
            current.collapsedGroups + group
        }
        current.copy(collapsedGroups = collapsed)
    }

    fun togglePinnedGroup() = _state.update { it.copy(pinnedCollapsed = !it.pinnedCollapsed) }

    /** 载入本地置顶集合（启动 / 切 profile 时各读一次）。 */
    fun loadPinned() = _state.update { it.copy(pinned = preferences.pinnedSessions) }

    /** 置顶 / 取消置顶：只写本地偏好，不动后端（见 `PrefKeys.PINNED_SESSIONS` 的说明）。 */
    fun togglePin(sessionId: String) {
        val next = _state.value.pinned.let { if (sessionId in it) it - sessionId else it + sessionId }
        preferences.pinnedSessions = next
        _state.update { it.copy(pinned = preferences.pinnedSessions) }
    }

    fun startRename(sessionId: String) {
        val current = _state.value.sessions.firstOrNull { it.id == sessionId }
        _state.update {
            it.copy(rename = RenameDraft(sessionId, current?.title.orEmpty()), rowError = null)
        }
    }

    fun updateRenameDraft(title: String) = _state.update { current ->
        current.copy(rename = current.rename?.copy(title = title))
    }

    fun cancelRename() = _state.update { it.copy(rename = null) }

    /**
     * 提交重命名。
     *
     * 两条路分工（后端两个通道的适用面不同）：
     * - 已落库的会话 → REST `PATCH /api/sessions/{id}`；
     * - 刚新建、还没落库的会话 → RPC `session.title`（它在内存里是 live 的，标题会排队到首轮生效）。
     */
    fun commitRename() {
        val draft = _state.value.rename ?: return
        val title = draft.title.trim()
        if (title.isEmpty()) return
        val profile = _state.value.profile
        val live = _state.value.justCreatedId == draft.sessionId
        _state.update { it.copy(rowBusyId = draft.sessionId) }
        viewModelScope.launch {
            try {
                if (live) {
                    repository.renameLiveSession(profile, draft.sessionId, title)
                } else {
                    repository.renameSession(profile, draft.sessionId, title)
                }
                _state.update { current ->
                    val sessions = current.sessions.map {
                        if (it.id == draft.sessionId) it.copy(title = title) else it
                    }
                    current.copy(load = Loadable.Ready(sessions), rowBusyId = null, rename = null)
                }
                if (!live) refresh(profile)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(
                        rowBusyId = null,
                        rename = null,
                        rowError = ErrorMapper.from(throwable, "session.rename"),
                    )
                }
            }
        }
    }

    fun askDelete(sessionId: String) = _state.update { it.copy(confirmDeleteId = sessionId, rowError = null) }

    fun cancelDelete() = _state.update { it.copy(confirmDeleteId = null) }

    /** 删除会话：成功后回读；删的是当前选中的会话时清选择，避免右栏挂着已删 id。 */
    fun commitDelete() {
        val sessionId = _state.value.confirmDeleteId ?: return
        val profile = _state.value.profile
        _state.update { it.copy(rowBusyId = sessionId) }
        viewModelScope.launch {
            try {
                repository.deleteSession(profile, sessionId)
                _state.update { current ->
                    current.copy(
                        confirmDeleteId = null,
                        rowBusyId = null,
                        selectedId = if (current.selectedId == sessionId) null else current.selectedId,
                        justCreatedId = if (current.justCreatedId == sessionId) null else current.justCreatedId,
                        pinned = current.pinned - sessionId,
                    )
                }
                refresh(profile)
                loadPinned()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(
                        confirmDeleteId = null,
                        rowBusyId = null,
                        rowError = ErrorMapper.from(throwable, "session.delete"),
                    )
                }
            }
        }
    }

    fun dismissRowError() = _state.update { it.copy(rowError = null) }

    /** 载入本地归档快照（启动 / 切 profile 时各读一次）。 */
    fun loadArchived() = _state.update { it.copy(archived = readArchived()) }

    fun toggleArchivedSection() = _state.update { it.copy(archivedExpanded = !it.archivedExpanded) }

    fun askArchive(sessionId: String) =
        _state.update { it.copy(confirmArchiveId = sessionId, rowError = null) }

    fun cancelArchive() = _state.update { it.copy(confirmArchiveId = null) }

    /** 归档：RPC 成功后把快照记到本地，再从列表移除（后端本来就不再返回它）。 */
    fun commitArchive() {
        val sessionId = _state.value.confirmArchiveId ?: return
        val profile = _state.value.profile
        val target = _state.value.sessions.firstOrNull { it.id == sessionId }
        _state.update { it.copy(rowBusyId = sessionId) }
        viewModelScope.launch {
            try {
                repository.setArchived(profile, sessionId, archived = true)
                val ref = ArchivedSessionRef(
                    id = sessionId,
                    title = target?.displayTitle.orEmpty(),
                    startedAt = target?.startedAt?.epochSeconds?.toDouble() ?: 0.0,
                )
                val next = (_state.value.archived.filterNot { it.id == sessionId } + ref)
                writeArchived(next)
                _state.update { current ->
                    current.copy(
                        confirmArchiveId = null,
                        rowBusyId = null,
                        archived = next,
                        selectedId = if (current.selectedId == sessionId) null else current.selectedId,
                        justCreatedId = if (current.justCreatedId == sessionId) null else current.justCreatedId,
                        pinned = current.pinned - sessionId,
                        load = Loadable.Ready(current.sessions.filterNot { it.id == sessionId }),
                    )
                }
                preferences.pinnedSessions = preferences.pinnedSessions - sessionId
                refresh(profile)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(
                        confirmArchiveId = null,
                        rowBusyId = null,
                        rowError = ErrorMapper.from(throwable, RpcMethods.SESSION_ARCHIVE),
                    )
                }
            }
        }
    }

    /** 从「已归档」区恢复：`archived=false` 后从本地快照移除并回读列表。 */
    fun restoreArchived(sessionId: String) {
        val profile = _state.value.profile
        _state.update { it.copy(rowBusyId = sessionId) }
        viewModelScope.launch {
            try {
                repository.setArchived(profile, sessionId, archived = false)
                val next = _state.value.archived.filterNot { it.id == sessionId }
                writeArchived(next)
                _state.update { it.copy(archived = next, rowBusyId = null) }
                refresh(profile)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(
                        rowBusyId = null,
                        rowError = ErrorMapper.from(throwable, "session.unarchive"),
                    )
                }
            }
        }
    }

    private fun readArchived(): List<ArchivedSessionRef> {
        val raw = preferences.archivedSessions ?: return emptyList()
        return runCatching {
            RpcJson.json.decodeFromString(ListSerializer(ArchivedSessionRef.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    // ── v0.3.0：运行态 / 复制 / 导出 / 内容搜索 ──────────────────────────

    /** 刷新"哪些会话在跑"。失败静默：运行态点只是装饰，不该弹错误。 */
    fun refreshActive() {
        val profile = _state.value.profile
        viewModelScope.launch {
            val ids = runCatching { repository.activeSessionIds(profile) }.getOrDefault(emptySet())
            _state.update { if (it.profile == profile) it.copy(activeIds = ids) else it }
        }
    }

    /**
     * 复制为新会话：整份历史克隆（`session.branch_stored`），原会话不动。
     *
     * 父会话不必活跃，所以可以直接从会话行调；成功后刷新列表并选中新会话。
     */
    fun duplicate(sessionId: String) {
        if (_state.value.duplicatingId != null) return
        val profile = _state.value.profile
        _state.update { it.copy(duplicatingId = sessionId, rowError = null) }
        viewModelScope.launch {
            try {
                val newId = repository.duplicateSession(profile, sessionId)
                _state.update {
                    it.copy(
                        duplicatingId = null,
                        selectedId = newId,
                        justCreatedId = null,
                        query = "",
                        notice = "已复制为新会话",
                    )
                }
                refresh(profile)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(
                        duplicatingId = null,
                        rowError = ErrorMapper.from(throwable, RpcMethods.SESSION_BRANCH_STORED),
                    )
                }
            }
        }
    }

    /**
     * 导出会话。
     *
     * 先拉全量消息（`fetchAllMessages` 循环翻页），再让用户选保存位置 ——
     * 顺序不能反：用户点了保存却发现没有内容才是更糟的体验。
     */
    fun exportSession(sessionId: String, markdown: Boolean) {
        if (_state.value.exporting) return
        val profile = _state.value.profile
        val title = _state.value.sessions.firstOrNull { it.id == sessionId }?.displayTitle
        _state.update { it.copy(exporting = true, rowError = null, notice = null) }
        viewModelScope.launch {
            try {
                val rows = chat.fetchAllMessages(profile, sessionId)
                val items = projectChat(rows)
                if (items.isEmpty()) {
                    _state.update {
                        it.copy(exporting = false, notice = "这个会话还没有可导出的内容")
                    }
                    return@launch
                }
                val now = Clock.System.now()
                val defaultName = if (markdown) {
                    SessionExport.markdownFileName(title, sessionId)
                } else {
                    SessionExport.jsonFileName(title, sessionId)
                }
                val target = pickSaveFile(title = "导出会话", defaultName = defaultName)
                if (target == null) {
                    _state.update { it.copy(exporting = false) }
                    return@launch
                }
                val text = if (markdown) {
                    SessionExport.toMarkdown(sessionId, title, items, now)
                } else {
                    SessionExport.toJson(sessionId, title, items, now)
                }
                val ok = writeTextFile(target, text)
                _state.update {
                    it.copy(
                        exporting = false,
                        notice = if (ok) "已导出到 $target" else "写入文件失败：$target",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(exporting = false, rowError = ErrorMapper.from(throwable, "session.export"))
                }
            }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /** 内容搜索（后端全文检索，防抖 300ms）。 */
    fun setContentQuery(query: String) {
        _state.update { it.copy(contentQuery = query) }
        searchJob?.cancel()
        val profile = _state.value.profile
        if (query.isBlank()) {
            _state.update { it.copy(contentResults = Loadable.Idle) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(CONTENT_SEARCH_DEBOUNCE_MS)
            _state.update { it.copy(contentResults = Loadable.Loading) }
            try {
                val hits = repository.searchSessions(profile, query).map { hit ->
                    SessionSearchHit(
                        sessionId = hit.sessionId,
                        // 标题用后端给的 title；snippet 只作辅助，不能拿来当标题。
                        title = hit.title?.takeIf { it.isNotBlank() } ?: hit.sessionId,
                        snippet = hit.snippet,
                        matchedRole = hit.role,
                    )
                }
                _state.update { it.copy(contentResults = Loadable.Ready(hits)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(contentResults = Loadable.Failed(ErrorMapper.from(throwable, "sessions.search")))
                }
            }
        }
    }

    private fun writeArchived(list: List<ArchivedSessionRef>) {
        preferences.archivedSessions = if (list.isEmpty()) {
            null
        } else {
            RpcJson.json.encodeToString(ListSerializer(ArchivedSessionRef.serializer()), list)
        }
    }
}

/** `sessions.changed` 的合并窗口：一轮对话 / 一次操作会连推多条。 */
private const val SESSIONS_CHANGED_DEBOUNCE_MS = 400L

/** 内容搜索防抖：后端全文检索比标题过滤贵，按键停下再发。 */
private const val CONTENT_SEARCH_DEBOUNCE_MS = 300L
