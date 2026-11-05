package dev.shinglem.hermes.core.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 外部编辑器配置（`options.json`，热生效）。
 *
 * 为什么放 options 而不是 config：它不影响后端与窗口，改完下一次点击就按新配置走，
 * 不需要重启客户端。
 *
 * 为什么是「多个 profile」而不是一个：用户可能同时装了 VSCode 与 IDEA，
 * 看代码用 VSCode、看 Java 项目用 IDEA。点「打开」时按 [defaultId] 直开，
 * 或弹菜单让用户挑。
 */
@Serializable
data class EditorsOptions(
    /** 默认用哪个 profile；null = 只有一个配置时直开，多个时弹菜单。 */
    @SerialName("default_id") val defaultId: String? = null,
    val profiles: List<EditorProfile> = emptyList(),
) {
    fun byId(id: String?): EditorProfile? = profiles.firstOrNull { it.id == id }

    /** 真正用于直开的 profile：显式默认 > 唯一配置 > null（需要询问）。 */
    fun preferred(): EditorProfile? = byId(defaultId) ?: profiles.singleOrNull()

    fun action(profile: EditorProfile, kind: EditorActionKind): EditorAction = when (kind) {
        EditorActionKind.OPEN_FILE -> profile.openFile
        EditorActionKind.OPEN_FILE_AT_LINE -> profile.openFileAtLine
        EditorActionKind.OPEN_FOLDER -> profile.openFolder
        EditorActionKind.OPEN_PROJECT -> profile.openProject
        EditorActionKind.OPEN_DIFF -> profile.openDiff
    }
}

/**
 * 一个外部编辑器。
 *
 * 五种动作各一份「开关 + 参数模板」——不同编辑器的命令行差异很大，写死没法兼顾。
 * 用**具名字段**而不是 `Map<动作, 配置>`：加新动作时旧的 `options.json` 仍然可读
 * （缺字段走默认），也不用给 kotlinx-serialization 配 enum map key。
 */
@Serializable
data class EditorProfile(
    val id: String,
    val name: String,
    /** 可执行文件路径（`.exe` / `.cmd` 都行，后者会自动经 `cmd /c` 包装）。 */
    @SerialName("exe_path") val exePath: String,
    @SerialName("open_file") val openFile: EditorAction = EditorAction(),
    @SerialName("open_file_at_line") val openFileAtLine: EditorAction =
        EditorAction(args = EditorActionKind.OPEN_FILE_AT_LINE.defaultTemplate),
    @SerialName("open_folder") val openFolder: EditorAction =
        EditorAction(args = EditorActionKind.OPEN_FOLDER.defaultTemplate),
    @SerialName("open_project") val openProject: EditorAction =
        EditorAction(args = EditorActionKind.OPEN_PROJECT.defaultTemplate),
    @SerialName("open_diff") val openDiff: EditorAction =
        EditorAction(args = EditorActionKind.OPEN_DIFF.defaultTemplate),
) {
    /** 是否至少有一个动作可用（全关掉的配置不出现在菜单里）。 */
    val usable: Boolean
        get() = exePath.isNotBlank() &&
            listOf(openFile, openFileAtLine, openFolder, openProject, openDiff).any { it.enabled }
}

/** 单个动作：开关 + 参数模板（空串 = 用内置默认）。 */
@Serializable
data class EditorAction(
    val enabled: Boolean = true,
    /** 参数模板。占位符见 [EditorActionKind.placeholders]。 */
    val args: String = "",
)

/** 打开方式。 */
enum class EditorActionKind(val label: String) {
    /** 打开文件本身。 */
    OPEN_FILE("打开文件"),

    /** 打开文件并跳到指定行（`code --goto file:line`）。 */
    OPEN_FILE_AT_LINE("打开文件（定位到行）"),

    /** 打开文件所在文件夹。 */
    OPEN_FOLDER("打开所在文件夹"),

    /** 打开项目根目录（会话工作目录）。 */
    OPEN_PROJECT("打开项目"),

    /** 对比两个文件（`code --diff a b`）。 */
    OPEN_DIFF("对比两个文件"),
    ;

    /** 该动作可用的占位符（给设置页做提示用）。 */
    val placeholders: List<String>
        get() = when (this) {
            OPEN_FILE -> listOf("{path}")
            OPEN_FILE_AT_LINE -> listOf("{path}", "{line}")
            OPEN_FOLDER, OPEN_PROJECT -> listOf("{dir}")
            OPEN_DIFF -> listOf("{file1}", "{file2}")
        }

    /** 内置默认模板（VSCode 可直接用；其它编辑器由用户改）。 */
    val defaultTemplate: String
        get() = when (this) {
            OPEN_FILE -> "{path}"
            OPEN_FILE_AT_LINE -> "--goto {path}:{line}"
            OPEN_FOLDER, OPEN_PROJECT -> "{dir}"
            OPEN_DIFF -> "--diff {file1} {file2}"
        }
}
