package dev.shinglem.hermes.feature.editors

import dev.shinglem.hermes.core.config.EditorActionKind
import dev.shinglem.hermes.core.config.EditorProfile
import dev.shinglem.hermes.core.config.EditorsOptions

/** 一次「用外部编辑器打开」的请求。 */
data class EditorOpenRequest(
    val profile: EditorProfile,
    val kind: EditorActionKind,
    val args: List<String>,
)

/**
 * 计算某次打开实际要执行的命令；不可执行时返回 null。
 *
 * 「某类型关闭 → 回退到单文件打开」就在这里：`open_folder` 被关掉时改用 `open_file`，
 * 若 `open_file` 也关了则什么也不做（菜单项本来就不会出现）。
 */
fun planEditorOpen(
    profile: EditorProfile,
    kind: EditorActionKind,
    path: String,
    dir: String?,
    cwd: String?,
    line: Int? = null,
    file1: String? = null,
    file2: String? = null,
): EditorOpenRequest? {
    if (profile.exePath.isBlank()) return null
    val vars = buildMap {
        put("path", path)
        dir?.let { put("dir", it) }
        cwd?.let { put("cwd", it) }
        line?.let { put("line", it.toString()) }
        file1?.let { put("file1", it) }
        file2?.let { put("file2", it) }
    }
    // `open_project` 用会话工作目录；`open_folder` 用文件所在目录
    val effectiveVars = when (kind) {
        EditorActionKind.OPEN_PROJECT -> vars + ("dir" to (cwd ?: return null))
        else -> vars
    }

    fun argsFor(action: EditorActionKind): List<String>? = when (action) {
        EditorActionKind.OPEN_FILE -> resolveActionArgs(
            action,
            profile.openFile.enabled,
            profile.openFile.args,
            effectiveVars,
        )

        EditorActionKind.OPEN_FILE_AT_LINE -> resolveActionArgs(
            action,
            profile.openFileAtLine.enabled,
            profile.openFileAtLine.args,
            effectiveVars,
        )

        EditorActionKind.OPEN_FOLDER -> resolveActionArgs(
            action,
            profile.openFolder.enabled,
            profile.openFolder.args,
            effectiveVars,
        )

        EditorActionKind.OPEN_PROJECT -> resolveActionArgs(
            action,
            profile.openProject.enabled,
            profile.openProject.args,
            effectiveVars,
        )

        EditorActionKind.OPEN_DIFF -> resolveActionArgs(
            action,
            profile.openDiff.enabled,
            profile.openDiff.args,
            effectiveVars,
        )
    }

    // 首选动作拿不到参数就回退到「打开文件」
    val args = argsFor(kind) ?: if (kind != EditorActionKind.OPEN_FILE) argsFor(EditorActionKind.OPEN_FILE) else null
    val actualKind = if (argsFor(kind) != null) kind else EditorActionKind.OPEN_FILE
    return args?.let { EditorOpenRequest(profile, actualKind, it) }
}

/**
 * 菜单里该显示哪些「编辑器 × 动作」项。
 *
 * 只有可用的组合才出现：配置被关掉的动作、算不出参数的组合（例如没有 cwd 时的「打开项目」）都不显示。
 */
fun editorMenuItems(
    editors: EditorsOptions,
    path: String,
    dir: String?,
    cwd: String?,
    line: Int? = null,
): List<EditorOpenRequest> = editors.profiles
    .filter { it.usable }
    .flatMap { profile ->
        EditorActionKind.entries.mapNotNull { kind ->
            planEditorOpen(profile, kind, path, dir, cwd, line)
        }
    }

/** 直开（有默认配置时）：只算一个请求，避免弹菜单。 */
fun preferredEditorOpen(
    editors: EditorsOptions,
    path: String,
    dir: String?,
    cwd: String?,
    line: Int? = null,
): EditorOpenRequest? {
    val profile = editors.preferred()?.takeIf { it.usable } ?: return null
    val kind = if (line != null && profile.openFileAtLine.enabled) {
        EditorActionKind.OPEN_FILE_AT_LINE
    } else {
        EditorActionKind.OPEN_FILE
    }
    return planEditorOpen(profile, kind, path, dir, cwd, line)
}
