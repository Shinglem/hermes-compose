package dev.shinglem.hermes.feature.composer

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.shinglem.hermes.core.contract.AttachedImageDto
import dev.shinglem.hermes.core.prefs.AppPreferences
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.platform.Clipboard
import dev.shinglem.hermes.platform.isImagePath
import dev.shinglem.hermes.platform.isSubmittableImagePath
import dev.shinglem.hermes.platform.pickFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 会话上下文：补全要 `cwd`，附件要活会话的 runtime id。 */
interface ComposerSessionContext {
    val profile: String?
    val storedSessionId: String?

    /** 只读缓存，不触发 `session.resume`。 */
    fun runtimeInfo(): RuntimeHandle?

    /** 附件类调用前的保险：必要时 `session.resume` 换 runtime id。 */
    suspend fun ensureRuntime(): RuntimeHandle?
}

data class RuntimeHandle(val runtimeId: String, val cwd: String?)

/**
 * composer 的状态持有者：补全浮层、附件、草稿、发送历史。
 *
 * 发送本身仍由 `ChatViewModel` 负责（它管事件流）；这里只把"用户想发什么"准备好。
 */
class ComposerViewModel(
    private val repository: ComposerRepository,
    private val sessions: ComposerSessionContext,
    private val preferences: AppPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(ComposerUiState())
    val state: StateFlow<ComposerUiState> = _state.asStateFlow()

    private var completionJob: Job? = null
    private var draftJob: Job? = null
    private var draftSessionId: String? = null

    /** ↑/↓ 历史游标的落点：`null` 表示没在翻历史。 */
    private var historyCursor: Int? = null

    /** 切会话：先落盘旧草稿，再载入新草稿。 */
    fun openSession(storedSessionId: String?) {
        if (storedSessionId == draftSessionId) return
        flushDraft()
        draftSessionId = storedSessionId
        completionJob?.cancel()
        historyCursor = null
        val draft = storedSessionId?.let(preferences::draft).orEmpty()
        _state.value = ComposerUiState(
            value = TextFieldValue(draft, selection = TextRange(draft.length)),
            history = _state.value.history,
        )
    }

    fun onValueChange(value: TextFieldValue) {
        _state.update { it.copy(value = value, notice = null) }
        scheduleCompletion(value)
        scheduleDraft(value.text)
    }

    fun requestCompletion() {
        scheduleCompletion(_state.value.value, immediate = true)
    }

    fun moveSelection(delta: Int) {
        _state.update { current ->
            val completion = current.completion ?: return@update current
            current.copy(completion = completion.moved(delta))
        }
    }

    /**
     * 接受选中项。返回 `false` 表示没有可接受的项（浮层已过期）；
     * 调用方据此决定是否继续把这次按键交给后面的逻辑（例如 Enter 发送）。
     */
    fun acceptCompletion(index: Int? = null): Boolean {
        val current = _state.value
        val completion = current.completion ?: return false
        val target = if (index == null) completion else completion.copy(selected = index)
        val updated = target.accepted(current.value) ?: return false
        historyCursor = null
        _state.update { it.copy(value = updated, completion = null) }
        scheduleDraft(updated.text)
        // 不重新触发补全：接受结果本身又匹配同一条目，会立刻再弹一层，
        // 导致 Enter 永远停在"接受"上。下一次用户输入时自然会重新请求。
        return true
    }

    fun dismissCompletion() {
        completionJob?.cancel()
        _state.update { if (it.completion == null) it else it.copy(completion = null) }
    }

    fun dismissNotice() {
        _state.update { if (it.notice == null) it else it.copy(notice = null) }
    }

    /**
     * 把一条消息引用到输入框（消息 hover 的「引用」按钮）。
     *
     * 以 `>` 逐行前缀的形式**追加**在现有草稿之后（不覆盖用户已写的内容）；
     * 每行超过 [QUOTE_LINE_LIMIT] 字符截断，整体超过 [QUOTE_MAX_LINES] 行只取前面几行 ——
     * 别让一条长回答把输入框撑成几千行。
     */
    fun quote(text: String) {
        val quoted = quoteBlock(text)
        if (quoted.isEmpty()) return
        val current = _state.value.value
        val prefix = if (current.text.isBlank()) "" else current.text.trimEnd() + "\n\n"
        val merged = prefix + quoted + "\n\n"
        val value = TextFieldValue(merged, selection = TextRange(merged.length))
        _state.update { it.copy(value = value, completion = null, notice = null) }
        scheduleDraft(merged)
    }

    /** ↑/↓ 取发送历史；返回 true 表示这次按键被历史接管（调用方不要吞掉 Enter 等）。 */
    fun stepHistory(direction: Int): Boolean {
        val current = _state.value
        if (current.completion != null) return false
        val history = current.history
        if (history.isEmpty()) return false
        // 只在单行且光标在末尾时接管，避免破坏多行编辑。
        if (current.value.text.contains('\n')) return false
        if (current.value.selection.start != current.value.text.length) return false

        val from = historyCursor ?: history.size
        val next = (from + direction).coerceIn(0, history.size)
        if (next == from) return false
        historyCursor = if (next == history.size) null else next
        val text = if (next == history.size) "" else history[next]
        _state.update {
            it.copy(value = TextFieldValue(text, selection = TextRange(text.length)), completion = null)
        }
        return true
    }

    /** 发送成功后调用：记入历史、清空输入与附件，清掉该会话草稿。 */
    fun markSent(text: String) {
        completionJob?.cancel()
        // 必须一起取消：否则防抖中的 draftJob 会在 clearDraft 之后把已发送的文本写回草稿，
        // 表现为"发出去的话在重启后又回到输入框"。
        draftJob?.cancel()
        historyCursor = null
        _state.update { current ->
            current.copy(
                value = TextFieldValue(""),
                completion = null,
                attachments = emptyList(),
                history = (current.history + text).takeLast(HISTORY_LIMIT),
            )
        }
        draftSessionId?.let(preferences::clearDraft)
    }

    /**
     * Ctrl+V 的统一入口（`Composer` 已判断"需要特殊处理"）：
     * 图片 → `clipboard.paste`；大段 → `paste.collapse`；路径 → 附件或 `@file:` 引用；
     * 都不成立就把原文原样插进光标处（不让用户的粘贴丢内容）。
     */
    suspend fun handlePasteSpecial(text: String, selection: TextRange) {
        when {
            text == PASTE_IMAGE -> attachClipboardImage()
            isLargePaste(text) -> if (!collapsePasteIfLarge(text, selection)) insertAtCursor(text)
            else -> if (!handleDroppedPath(text)) insertAtCursor(text)
        }
    }

    /** 大段粘贴折叠：占位符替换当前选区，原文由后端落盘。 */
    suspend fun collapsePasteIfLarge(text: String, selection: TextRange): Boolean {        if (!isLargePaste(text)) return false
        val collapsed = repository.collapsePaste(text) ?: return false
        val current = _state.value.value
        val start = selection.start.coerceIn(0, current.text.length)
        val end = selection.end.coerceIn(start, current.text.length)
        val raw = current.text
        val inserted = raw.substring(0, start) + collapsed.placeholder + raw.substring(end)
        val cursor = start + collapsed.placeholder.length
        val updated = TextFieldValue(inserted, selection = TextRange(cursor))
        _state.update {
            it.copy(
                value = updated,
                completion = null,
                notice = "${collapsed.lines} 行已收进 ${collapsed.path}",
            )
        }
        scheduleDraft(inserted)
        return true
    }

    /**
     * 粘贴路径文本：是拖放路径就转成附件/引用。
     * 返回 true 表示已消费这次粘贴。
     */
    suspend fun handleDroppedPath(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.contains('\n')) return false
        val runtime = sessions.ensureRuntime() ?: return false
        val drop = repository.detectDrop(trimmed) ?: return false
        val path = drop.path ?: return false
        return if (drop.isImage == true) {
            attachImagePath(runtime, path)
        } else {
            attachFileRef(runtime, path)
        }
    }

    /** 把图片附加进会话（来自拖入或剪贴板重定向）；会话里会随下一轮带给模型。 */
    suspend fun attachImagePath(path: String): Boolean {
        val runtime = sessions.ensureRuntime() ?: return notice("还没有活会话，先发一条消息再插图")
        return attachImagePath(runtime, path)
    }

    /**
     * 拖入或文件选择器给出的**本地路径**：图片走 `image.attach`，其它走 `file.attach`。
     *
     * 与 [handleDroppedPath] 的分工：那条处理"粘贴进来的路径**文本**"（要先问
     * `input.detect_drop`），这里拿到的已经是确定的文件路径，不需要再探测。
     */
    suspend fun attachPaths(paths: List<String>) {
        if (paths.isEmpty()) return
        val runtime = sessions.ensureRuntime()
        if (runtime == null) {
            notice("还没有活会话，先发一条消息再附加文件")
            return
        }
        var attached = 0
        for (path in paths) {
            val ok = if (isImagePath(path)) attachImagePath(runtime, path) else attachFileRef(runtime, path)
            if (ok) attached++
        }
        if (attached > 0 && paths.size > 1) notice("已附加 $attached/${paths.size} 个文件")
    }

    /** 打开系统文件选择器，选中后按扩展名分派到图片或普通文件。 */
    suspend fun pickAndAttach() {
        val picked = try {
            pickFiles(title = "选择要附加的文件", imagesOnly = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            notice("打开文件选择器失败：${throwable.message ?: "未知错误"}")
            return
        }
        attachPaths(picked)
    }

    /** 剪贴板里有图时调用（`Clipboard.hasImage()` 为真再进来）。 */
    suspend fun attachClipboardImage(): Boolean {
        val runtime = sessions.ensureRuntime() ?: return notice("还没有活会话，先发一条消息再粘贴图片")
        // 先试后端 clipboard.paste（同会话运行时最省事），失败再自己落盘走 image.attach：
        // gateway 可能跑在 Session 0（服务会话），那里的剪贴板是孤立的，永远读不到用户复制的图。
        val pasted = try {
            repository.pasteClipboardImage(sessions.profile, runtime.runtimeId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            Log.debug("clipboard.paste failed: ${throwable.message}")
            null
        }
        if (pasted?.attached == true) {
            addAttachment(pasted)
            return true
        }
        val path = Clipboard.saveImageToTemp()
            ?: return notice(pasted?.message?.let { "剪贴板里的图片取不出来：$it" } ?: "剪贴板里没有可用图片")
        return attachImagePath(runtime, path)
    }

    private suspend fun attachImagePath(runtime: RuntimeHandle, path: String): Boolean {
        // 附加阶段就拦住不支持的格式：`attach_bytes` 会把它落盘，但 admission 只收
        // png/jpeg/gif/webp，拖到发送时才 `invalid_params` 等于静默丢图（#19）。
        if (!isSubmittableImagePath(path)) {
            notice(
                "不支持的图片格式：${path.substringAfterLast('\\').substringAfterLast('/')}" +
                    "（只支持 png / jpeg / gif / webp）",
            )
            return false
        }
        return try {
            val result = repository.attachImage(sessions.profile, runtime.runtimeId, path)
            if (!result.attached) notice(result.message ?: "图片附加失败") else addAttachment(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            notice("图片附加失败：${throwable.message ?: "未知错误"}")
        }
    }

    /** 普通文件：后端把它收进会话工作区，回到 composer 的是一段 `@file:` 引用。 */
    private suspend fun attachFileRef(runtime: RuntimeHandle, path: String): Boolean = try {
        val result = repository.attachFile(sessions.profile, runtime.runtimeId, path)
        if (!result.attached || result.refText.isBlank()) {
            notice("文件附加失败")
        } else {
            insertAtCursor(result.refText + " ")
            true
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (throwable: Throwable) {
        notice("文件附加失败：${throwable.message ?: "未知错误"}")
    }

    private fun addAttachment(result: AttachedImageDto): Boolean {
        val path = result.path?.takeIf { it.isNotBlank() }
        val id = path ?: result.filename ?: "image"
        val label = result.filename?.takeIf { it.isNotBlank() }
            ?: id.substringAfterLast('\\').substringAfterLast('/')
        // mime 优先用后端回执（`gateway/session_images.py` 有 `mime`），
        // 拿不到就按扩展名推断；都不成立时留空，随提交时会被过滤掉。
        val mime = result.mime?.takeIf { it.isNotBlank() } ?: mimeOfPath(path.orEmpty())
        if (path == null) {
            // 没有真实路径就没法随 `prompt.submit` 提交 —— 与其"看着附加成功、发出去丢了"，
            // 不如当场说清楚（#19 的失败可见性要求）。
            notice("图片已处理但没拿到可提交的路径，无法随本轮发送")
            return false
        }
        if (mime.isBlank()) {
            notice("不支持的图片格式：$label（只支持 png / jpeg / gif / webp）")
            return false
        }
        _state.update {
            it.copy(
                attachments = it.attachments + ComposerAttachment(
                    id = id,
                    label = label,
                    detail = result.text.orEmpty(),
                    path = path,
                    mime = mime,
                ),
            )
        }
        return true
    }

    private fun insertAtCursor(text: String) {
        val current = _state.value.value
        val start = current.selection.start.coerceIn(0, current.text.length)
        val end = current.selection.end.coerceIn(start, current.text.length)
        val raw = current.text
        val inserted = raw.substring(0, start) + text + raw.substring(end)
        val cursor = start + text.length
        _state.update { it.copy(value = TextFieldValue(inserted, selection = TextRange(cursor))) }
        scheduleDraft(inserted)
    }

    private fun scheduleCompletion(value: TextFieldValue, immediate: Boolean = false) {
        completionJob?.cancel()
        // 输入法组合态里不要抢浮层：这时 token 还没上屏。
        if (value.composition != null) {
            dismissCompletion()
            return
        }
        val cursor = value.selection.end
        val token = activeToken(value.text, cursor)
        if (token == null) {
            dismissCompletion()
            return
        }
        val cached = sessions.runtimeInfo()
        completionJob = viewModelScope.launch {
            if (!immediate) delay(COMPLETION_DEBOUNCE_MS)
            // 补全想拿到会话 cwd（列项目文件）；没有就 resume 一次，失败也能继续（退回默认目录）。
            val runtime = cached ?: sessions.ensureRuntime()
            var start = token.start
            val entries: List<CompletionEntry> = try {
                if (token.trigger == CompletionTrigger.REFERENCE) {
                    repository.completeReferences(
                        profile = sessions.profile,
                        word = token.text,
                        cwd = runtime?.cwd,
                        runtimeSessionId = runtime?.runtimeId,
                    )
                } else {
                    if (runtime == null) {
                        emptyList()
                    } else {
                        val slash = repository.completeSlash(
                            profile = sessions.profile,
                            text = token.text,
                            runtimeSessionId = runtime.runtimeId,
                        )
                        // 行首命令的替换起点以后端为准。
                        start = slash.startIndex
                        slash.entries
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                Log.debug("composer completion failed: ${throwable.message}")
                dismissCompletion()
                return@launch
            }
            publish(token.copy(start = start), entries, cursor)
        }
    }

    private fun publish(token: ActiveToken, entries: List<CompletionEntry>, cursor: Int) {
        if (entries.isEmpty()) {
            dismissCompletion()
            return
        }
        _state.update {
            it.copy(
                completion = CompletionState(
                    trigger = token.trigger,
                    entries = entries,
                    selected = 0,
                    start = token.start,
                    end = cursor,
                ),
            )
        }
    }

    private fun scheduleDraft(text: String) {
        draftJob?.cancel()
        val sessionId = draftSessionId ?: return
        draftJob = viewModelScope.launch {
            delay(DRAFT_DEBOUNCE_MS)
            preferences.setDraft(sessionId, text)
        }
    }

    private fun flushDraft() {
        draftJob?.cancel()
        val sessionId = draftSessionId ?: return
        preferences.setDraft(sessionId, _state.value.text)
    }

    private fun notice(message: String): Boolean {
        _state.update { it.copy(notice = message) }
        return true
    }

    override fun onCleared() {
        flushDraft()
        super.onCleared()
    }

    private companion object {
        const val COMPLETION_DEBOUNCE_MS = 150L
        const val DRAFT_DEBOUNCE_MS = 800L
        const val HISTORY_LIMIT = 50
    }
}

/** 大段粘贴的粗判：行多或字多都算（真正折叠由后端决定）。 */
internal fun isLargePaste(text: String): Boolean {
    val lines = text.count { it == '\n' } + 1
    return text.length >= ComposerRepository.PASTE_COLLAPSE_MIN_CHARS ||
        lines >= ComposerRepository.PASTE_COLLAPSE_MIN_LINES
}

/** 引用块的最大行数与单行字符数（超出截断，别让一条长回答撑爆输入框）。 */
internal const val QUOTE_MAX_LINES = 20
internal const val QUOTE_LINE_LIMIT = 500

/**
 * 把一条消息变成 Markdown 引用块（每行加 `> `）。
 *
 * 空行丢弃、超长行截断加省略号、超出行数的部分不取；返回空串表示没有可引用内容。
 */
internal fun quoteBlock(text: String): String = text.lineSequence()
    .map(String::trimEnd)
    .filter(String::isNotEmpty)
    .take(QUOTE_MAX_LINES)
    .joinToString("\n") { line ->
        val clipped = if (line.length > QUOTE_LINE_LIMIT) line.take(QUOTE_LINE_LIMIT) + "…" else line
        "> $clipped"
    }
