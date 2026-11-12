package dev.shinglem.hermes.core.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * composer 相关 RPC 的最小映射（来源：`contracts/profiles_vault_complete_foreign_subagents.py`
 * 与 `contracts/prompt_voice.py`）。同样是手抄子集，字段名与 wire 名一致。
 */

/** `complete.path` / `complete.slash` 的 `items[]`（`CompletionItem`）。 */
@Serializable
data class CompletionItemDto(
    val text: String,
    val display: String = "",
    val meta: String = "",
    val kind: String? = null,
)

@Serializable
data class CompletionItemsDto(
    val items: List<CompletionItemDto> = emptyList(),
)

/** `complete.slash` 结果：`replace_from` 是接受时替换的起始列（1-based）。 */
@Serializable
data class CompleteSlashDto(
    val items: List<CompletionItemDto> = emptyList(),
    @SerialName("replace_from") val replaceFrom: Int? = null,
)

/** `paste.collapse`：大段粘贴折叠成占位符，原文落到 `path`。 */
@Serializable
data class PasteCollapseDto(
    val placeholder: String = "",
    val path: String = "",
    val lines: Int = 0,
)

/** `file.attach`：文件进会话工作区，返回 `@file:` 引用。 */
@Serializable
data class FileAttachDto(
    val attached: Boolean = false,
    val name: String = "",
    val path: String = "",
    @SerialName("ref_path") val refPath: String = "",
    @SerialName("ref_text") val refText: String = "",
    val uploaded: Boolean = false,
)

/** `image.attach_bytes` 的附件结果（gateway 侧 `gateway/session_images.py`）。 */
@Serializable
data class AttachedImageDto(
    val attached: Boolean = false,
    val path: String? = null,
    val count: Int? = null,
    val text: String? = null,
    val bytes: Long? = null,
    val message: String? = null,
    val filename: String? = null,
    val name: String? = null,
    /** `image/png` 之类；缺了就按扩展名推断（admission 只认 png/jpeg/gif/webp）。 */
    val mime: String? = null,
    @SerialName("pages_attached") val pagesAttached: Int? = null,
)

/** `input.detect_drop`：粘贴进来的路径文本是不是文件拖放。 */
@Serializable
data class DetectDropDto(
    val matched: Boolean = false,
    @SerialName("is_image") val isImage: Boolean? = null,
    val path: String? = null,
    val count: Int? = null,
    val text: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)
