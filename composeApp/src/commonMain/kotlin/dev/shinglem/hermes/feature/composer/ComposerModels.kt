package dev.shinglem.hermes.feature.composer

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** 补全触发符：`@` 是上下文引用（文件/文件夹/URL/profile），`/` 是行首命令与技能。 */
enum class CompletionTrigger { REFERENCE, SLASH }

/** 补全浮层的一行（`complete.path` / `complete.slash` 的 `items[]`）。 */
data class CompletionEntry(
    val text: String,
    val display: String,
    val meta: String = "",
    val kind: String? = null,
)

/** 浮层状态；`start`/`end` 是接受时被替换的文本区间（左闭右开）。 */
data class CompletionState(
    val trigger: CompletionTrigger,
    val entries: List<CompletionEntry>,
    val selected: Int = 0,
    val start: Int = 0,
    val end: Int = 0,
) {
    val isEmpty: Boolean get() = entries.isEmpty()

    /** ↑/↓ 循环移动选中项。 */
    fun moved(delta: Int): CompletionState {
        if (entries.isEmpty()) return this
        val next = ((selected + delta) % entries.size + entries.size) % entries.size
        return copy(selected = next)
    }

    /** 把选中项写进文本，返回新的 `TextFieldValue`（光标落到替换内容之后）。 */
    fun accepted(text: TextFieldValue): TextFieldValue? {
        val entry = entries.getOrNull(selected) ?: return null
        val replacement = when (trigger) {
            CompletionTrigger.REFERENCE -> entry.text
            CompletionTrigger.SLASH -> "/" + entry.text.removePrefix("/")
        }
        val raw = text.text
        val safeStart = start.coerceIn(0, raw.length)
        // 终点以接受时刻的光标为准：补全请求是防抖 + 异步的，返回前文本可能又变了，
        // 沿用请求时的 end 会把用户后来输入的字留下来（`/skill` → `/skinill`）。
        val cursor = text.selection.end.coerceIn(0, raw.length)
        val safeEnd = if (cursor >= safeStart) cursor else end.coerceIn(safeStart, raw.length)
        // 引用后补一个空格，方便继续输入；命令直接结束。
        val suffix = if (trigger == CompletionTrigger.REFERENCE) " " else ""
        val inserted = raw.substring(0, safeStart) + replacement + suffix + raw.substring(safeEnd)
        return text.copy(
            text = inserted,
            selection = TextRange(safeStart + replacement.length + suffix.length),
            composition = null,
        )
    }
}

/** cursor 之前正在编辑的那个"词"：由它决定是否弹补全。 */
data class ActiveToken(
    val start: Int,
    val text: String,
    val trigger: CompletionTrigger,
)

/**
 * 找出光标前的触发 token。
 *
 * 规则（与 CLI 一致）：`@` 前面必须是行首或空白，`/` 必须在行首；token 内部不含空白。
 * 这样 `a@b.com` 这类邮箱不会误触发。
 */
fun activeToken(text: CharSequence, cursor: Int): ActiveToken? {
    if (cursor <= 0 || cursor > text.length) return null
    var i = cursor - 1
    while (i >= 0) {
        val c = text[i]
        if (c.isWhitespace()) return null
        val atHead = i == 0 || text[i - 1].isWhitespace()
        if (c == '@' && atHead) {
            return ActiveToken(i, text.substring(i, cursor), CompletionTrigger.REFERENCE)
        }
        if (c == '/' && i == 0) {
            return ActiveToken(i, text.substring(i, cursor), CompletionTrigger.SLASH)
        }
        i--
    }
    return null
}

/** composer 的可见状态。 */
data class ComposerUiState(
    val value: TextFieldValue = TextFieldValue(""),
    val completion: CompletionState? = null,
    /** 已附加到会话的图片（随下一轮带给模型）。 */
    val attachments: List<ComposerAttachment> = emptyList(),
    val notice: String? = null,
    /** 本次运行内的发送历史，供 ↑/↓ 取用（最新的在末尾）。 */
    val history: List<String> = emptyList(),
) {
    val text: String get() = value.text

    val canSend: Boolean get() = text.isNotBlank()
}

/**
 * 随 `prompt.submit` 一起提交的图片附件。
 *
 * 只有 `engine=gateway` 需要它：那条路线下 `image.attach_bytes` **只把字节落进**
 * `<profile>/cache/images/`，不建会话队列，必须由客户端回传引用
 * （`gateway/session_images.py` + `gateway/session_ingress_media.admit_attachments`）。
 * `isolated` 路线有自己的 `attached_images` 队列，传了也无害（闭集允许，只是白传）。
 *
 * `path` 必须是后端 `get_image_cache_dir()` 下的绝对路径；`mime` 只能是
 * `image/{png,jpeg,gif,webp}` —— admission 会逐项校验，不合格整条 `invalid_params`。
 */
data class PromptAttachment(
    val path: String,
    val mime: String,
)

/**
 * 已附加的图片：`image.attach` / `clipboard.paste` 的产物。
 *
 * [path] 与 [mime] 是**提交时要用的**真实引用，[label] / [detail] 只用于界面显示 ——
 * 之前只有后两者，导致"界面上看着附加成功、发出去时什么都没带"。
 */
data class ComposerAttachment(
    val id: String,
    val label: String,
    val detail: String = "",
    /** 后端的 `upload_*` 绝对路径；为空表示拿不到（此时不随提交发送）。 */
    val path: String = "",
    /** `image/png` 之类；为空时按扩展名推断。 */
    val mime: String = "",
) {
    /** 能随 `prompt.submit` 提交的形态；缺 `path` 时返回 null。 */
    fun toPromptAttachment(): PromptAttachment? =
        path.takeIf { it.isNotBlank() }?.let { PromptAttachment(it, mime.ifBlank { mimeOfPath(it) }) }
}

/** 按扩展名推断 mime；admission 只收这四种。 */
fun mimeOfPath(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    else -> ""
}

/**
 * ChatPane 与 composer 之间的绑定：状态 + 五个回调。
 *
 * 打包成一个对象，避免 ChatPane 的签名再长五列。
 */
data class ComposerController(
    val state: ComposerUiState,
    val onValueChange: (TextFieldValue) -> Unit,
    val onAcceptCompletion: (Int?) -> Boolean,
    val onMoveCompletion: (Int) -> Unit,
    val onDismissCompletion: () -> Unit,
    val onStepHistory: (Int) -> Boolean,
    val onPasteSpecial: (String, TextRange) -> Unit,
    val onPickFiles: () -> Unit,
    val onDropFiles: (List<String>) -> Unit,
)
