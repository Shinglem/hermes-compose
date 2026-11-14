package dev.shinglem.hermes.feature.session

import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import dev.shinglem.hermes.core.time.SessionGroup
import dev.shinglem.hermes.core.time.TimeFormat
import dev.shinglem.hermes.domain.model.SessionSummary
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant
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
