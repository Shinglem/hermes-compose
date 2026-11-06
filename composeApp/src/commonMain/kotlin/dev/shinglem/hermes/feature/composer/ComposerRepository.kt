package dev.shinglem.hermes.feature.composer

import dev.shinglem.hermes.core.contract.AttachedImageDto
import dev.shinglem.hermes.core.contract.CompleteSlashDto
import dev.shinglem.hermes.core.contract.CompletionItemDto
import dev.shinglem.hermes.core.contract.CompletionItemsDto
import dev.shinglem.hermes.core.contract.DetectDropDto
import dev.shinglem.hermes.core.contract.FileAttachDto
import dev.shinglem.hermes.core.contract.PasteCollapseDto
import dev.shinglem.hermes.core.contract.RpcMethods
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.error.ErrorMapper
import dev.shinglem.hermes.core.transport.GatewayClient
import dev.shinglem.hermes.core.transport.RpcJson
import dev.shinglem.hermes.core.transport.Timeouts
import dev.shinglem.hermes.platform.Clipboard
import dev.shinglem.hermes.platform.readFileBytes
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * composer 的数据源：补全、附件、粘贴折叠。
 *
 * 全部走 WebSocket RPC（契约见 `contracts/prompt_voice.py` / `..._complete_foreign_subagents.py`）。
 * 附件类方法要求**活的会话**（runtime id）；`complete.path` 只要有 `cwd` 就能用。
 */
class ComposerRepository(private val gateway: GatewayClient) {
    /** `@` 上下文引用补全：`@file:` / `@folder:` / `@url:` / `@diff` 与 profile 名。 */
    suspend fun completeReferences(
        profile: String?,
        word: String,
        cwd: String?,
        runtimeSessionId: String?,
    ): List<CompletionEntry> {
        val params = buildJsonObject {
            put("word", word)
            cwd?.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
            runtimeSessionId?.takeIf { it.isNotBlank() }?.let { put("session_id", it) }
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
        }
        val element = call(RpcMethods.COMPLETE_PATH, params)
        val dto = RpcJson.json.decodeFromJsonElement(CompletionItemsDto.serializer(), element)
        return dto.items.map(CompletionItemDto::toEntry)
    }

    /** `/` 命令与技能补全；`replace_from` 是接受时要替换的起始列（1-based）。 */
    suspend fun completeSlash(
        profile: String?,
        text: String,
        runtimeSessionId: String?,
    ): SlashCompletion {
        val params = buildJsonObject {
            put("text", text)
            runtimeSessionId?.takeIf { it.isNotBlank() }?.let { put("session_id", it) }
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
        }
        val element = call(RpcMethods.COMPLETE_SLASH, params)
        val dto = RpcJson.json.decodeFromJsonElement(CompleteSlashDto.serializer(), element)
        return SlashCompletion(
            entries = dto.items.map(CompletionItemDto::toEntry),
            replaceFrom = dto.replaceFrom ?: 1,
        )
    }

    /** 大段粘贴折叠：返回后端给的占位符（原文由后端落盘）。 */
    suspend fun collapsePaste(text: String): PasteCollapseDto? = runCatching {
        val params = buildJsonObject { put("text", text) }
        RpcJson.json.decodeFromJsonElement(
            PasteCollapseDto.serializer(),
            call(RpcMethods.PASTE_COLLAPSE, params),
        )
    }.getOrNull()

    /** 普通文件进会话工作区，返回可直接插进 composer 的 `@file:` 引用。 */
    suspend fun attachFile(profile: String?, runtimeSessionId: String, path: String): FileAttachDto {
        val params = buildJsonObject {
            put("session_id", runtimeSessionId)
            put("path", path)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
        }
        return RpcJson.json.decodeFromJsonElement(
            FileAttachDto.serializer(),
            call(RpcMethods.FILE_ATTACH, params),
        )
    }

    /** 图片按路径附加：canonical gateway 只认 base64（`image.attach_bytes`），不接受路径。 */
    suspend fun attachImage(profile: String?, runtimeSessionId: String, path: String): AttachedImageDto {
        val bytes = readFileBytes(path) ?: throw AppError.Protocol("读不到图片文件：$path")
        return attachImageBytes(profile, runtimeSessionId, bytes, path.substringAfterLast('\\').substringAfterLast('/'))
    }

    /**
     * 把宿主剪贴板里的图片存进会话。
     *
     * 0.23.0 的 canonical gateway 没有 `clipboard.paste`（-32601），而且 gateway 可能跑在
     * Session 0（独立剪贴板，永远读不到用户复制的内容），所以**由前端读剪贴板**、
     * 落成临时 PNG 再走 `image.attach_bytes`。
     */
    suspend fun pasteClipboardImage(profile: String?, runtimeSessionId: String): AttachedImageDto? {
        if (!Clipboard.hasImage()) return null
        val path = Clipboard.saveImageToTemp() ?: return null
        return attachImage(profile, runtimeSessionId, path)
    }

    /** `image.attach_bytes`：内容走 base64，`filename` / `ext` 只用来提示扩展名。 */
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun attachImageBytes(
        profile: String?,
        runtimeSessionId: String,
        bytes: ByteArray,
        filename: String,
    ): AttachedImageDto {
        val params = buildJsonObject {
            put("session_id", runtimeSessionId)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            put("content_base64", Base64.Default.encode(bytes))
            put("filename", filename)
        }
        return RpcJson.json.decodeFromJsonElement(
            AttachedImageDto.serializer(),
            call(RpcMethods.IMAGE_ATTACH_BYTES, params),
        )
    }

    /**
     * 粘贴进来的路径文本是不是文件/图片拖放。
     *
     * canonical gateway 没有 `input.detect_drop`（-32601），这件事本来也不需要后端：
     * 判"多行文本里单独一行是图片/文件扩展名"就够，真正的附加由 [attachImage] / [attachFile] 做。
     */
    fun detectDrop(text: String): DetectDropDto? {
        val candidate = text.trim().trim('"', '\'')
        if (candidate.isEmpty() || candidate.contains('\n')) return null
        val lower = candidate.lowercase()
        val isImage = IMAGE_EXTENSIONS.any { lower.endsWith(it) }
        val isFile = isImage || FILE_EXTENSIONS.any { lower.endsWith(it) }
        if (!isFile) return null
        return DetectDropDto(isImage = isImage, path = candidate, count = 1)
    }

    private suspend fun call(method: String, params: JsonObject): JsonElement = try {
        gateway.call(method, params, Timeouts.DEFAULT_MS)
    } catch (throwable: Throwable) {
        throw ErrorMapper.from(throwable, method)
    }

    companion object {
        /** 后端把粘贴折叠的触发阈值放在自己那边，这里只做"是否值得问"的粗判。 */
        const val PASTE_COLLAPSE_MIN_CHARS = 2_000
        const val PASTE_COLLAPSE_MIN_LINES = 30

        private val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp")
        private val FILE_EXTENSIONS = listOf(
            ".txt", ".md", ".json", ".yaml", ".yml", ".toml", ".csv", ".log", ".pdf",
            ".kt", ".py", ".ts", ".js", ".java", ".go", ".rs", ".c", ".h", ".cpp", ".sh", ".ps1",
        )
    }
}

/** `/` 补全结果，`replaceFrom` 已转成 0-based 列号。 */
data class SlashCompletion(val entries: List<CompletionEntry>, val replaceFrom: Int) {
    val startIndex: Int get() = (replaceFrom - 1).coerceAtLeast(0)
}

private fun CompletionItemDto.toEntry(): CompletionEntry = CompletionEntry(
    text = text,
    display = display.ifBlank { text },
    meta = meta,
    kind = kind,
)
