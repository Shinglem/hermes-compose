package dev.shinglem.hermes.core.contract

/**
 * 会话面：`session.create` / `session.branch_stored` / `session.resume` 的 `source`。
 *
 * 后端的合法值见 `gateway/session_policy.py` 的 `SURFACES`
 * （`cli` / `tui` / `gui` / `acp` / `tool` / `oneshot`），`tui_gateway/methods_session.py`
 * 把 `source` 直接当作 `platform_override` 传给 agent —— 所以这个值同时决定
 * **工具集**与**系统提示里的输出格式说明**。
 *
 * ## 为什么必须是 `tui`，三个值都不能用
 *
 * | source | platform | 工具集 | 后果 |
 * |---|---|---|---|
 * | 不传 | `desktop` | `{project, desktop_ui, catalog}` | 回归：见下 "desktop" |
 * | `gui` | `desktop` | 同上 | 同上 |
 * | `cli` | `cli` | `{project}` | 提示词写明 "Markdown does NOT render … write plain text"，回答失去全部排版 |
 * | **`tui`** | `tui` | `{project}` | 提示词不限制 markdown ✅ |
 *
 * 不传会被判成 `desktop`：我们拉起后端时设了 `HERMES_DESKTOP=1`（进程级语义，见
 * `LocalBackendPlanner`），后端 `_resolve_session_platform()` 据此返回 `"desktop"`
 * （`HERMES_DESKTOP=1` 且无 `HERMES_DESKTOP_TERMINAL`）。
 *
 * ### `desktop` 为什么不行
 *
 * `desktop` 会塞进 `CLIENT_SURFACE_TOOLSETS`（含 `desktop_ui`），而 `desktop_ui` 在服务端
 * **无法禁用**（`fold_in -= disabled - {"desktop_ui"}`）。那套工具要求客户端有 Electron
 * 渲染进程里的 `<webview>` 应答 `preview.read` / `preview.act` 这类阻塞请求，我们没有，
 * 于是 agent 优先用 `desktop_preview` 而不是 `browser_*`，每次 45 秒后超时
 * （实测报 "No preview tab answered … the desktop app may be older than this backend"）。
 *
 * ### `cli` 为什么不行
 *
 * `agent/prompt_builder.py` 的 `PLATFORM_HINTS["cli"]` 明确要求输出纯文本
 * （"asterisks, headers, and fences appear as literal characters"），
 * 于是对话退化成命令行风格的缩进文本。
 *
 * ### `tui` 为什么安全
 *
 * 工具集与 `cli` 完全一样（`_gui_surface_toolsets(platform)` 只在 `platform == "desktop"`
 * 时给 `CLIENT_SURFACE_TOOLSETS`，其余一律 `{"project"}`）；全仓 `platform == "tui"` 的分支
 * 只有两处，都不影响我们：`system_prompt.py::_tui_embedded_pane_clarifier`（需要
 * `HERMES_DESKTOP_TERMINAL` 才追加，我们没设）与 `tools/kanban_tools.py`（只改通知的 key）。
 *
 * 顺带说明：`HERMES_DESKTOP=1` **不能去掉** —— 它还是进程随应用退出
 * （`hosted_room_peer.py`）、进程身份（`process_identity.py`）、以及 loopback + token
 * 算受信任的桌面端本地连接（`web_server_boundary.py`）的判据。
 */
object SessionSurface {
    const val CLIENT: String = "tui"
}

/**
 * 随 `session.create` 的 `client_env` 传给后端的会话级环境，**不写任何后端配置文件**。
 *
 * 后端契约（`tui_gateway/contracts/canonical_sessions.py::CanonicalCreateParams.client_env`）：
 * "a shell-launched client's allowlisted environment … applied to this session only".
 * 白名单在 `agent/session_client_env.py::BEHAVIOUR_ENV`，其中
 * `HERMES_EPHEMERAL_SYSTEM_PROMPT` 就是给我们用的，语义是**追加在系统提示末尾**
 * （`agent/chat_completion_helpers.py` / `turn_context.py` / `codex_runtime.py` 三处一致）。
 *
 * ## 为什么需要它
 *
 * `SessionSurface.CLIENT` 取 `tui` 是为了拿干净的工具集（`desktop` 会下发我们无法应答的
 * `desktop_ui`），代价是后端给的 `PLATFORM_HINTS["tui"]` 只说「文件没有附件通道」，
 * agent 于是把整个会话当成终端，回答会自我审查（真机里它说过「这是 TUI，没有附件通道」
 * 「终端里公式不渲染」—— 对我们的客户端是错的）。这里把真实渲染能力补回去。
 *
 * ## 为什么用追加而不是替换
 *
 * 原 hint 里「`MEDIA:` 不会被拦截，别用」对我们**是准确的**（客户端确实不处理 `MEDIA:`），
 * 必须保留；要纠正的只是「所以别用表格公式」这个推论。
 */
object ClientPrompt {
    /** 注入用的环境变量名（后端白名单里的键，写错会被静默丢弃）。 */
    const val ENV_KEY: String = "HERMES_EPHEMERAL_SYSTEM_PROMPT"

    /** 追加在系统提示末尾的一段：如实描述本客户端的渲染能力。 */
    val RENDERING: String = """
        This session runs in the Hermes Compose desktop client, not a plain terminal. The client
        renders: GitHub-flavored Markdown (headings, tables, task lists, callouts, footnotes with
        clickable references and back-links, in-text anchors), syntax-highlighted code with line
        numbers and line highlighting, LaTeX via ${'$'}...${'$'} inline and ${'$'}${'$'}...${'$'}${'$'} blocks, and images with
        click-to-zoom. Prefer these over flat plain-text layouts — do not avoid tables, math or
        code fences just because this is not a GUI chat surface.
        The user CAN attach images to a message: attachments arrive as image input in the same
        turn as the prompt text, so you can see and describe them directly. Do not claim there is
        no attachment channel when the current message already carries an image.
        MEDIA: tags and ::preview{} widgets are NOT intercepted: they would appear as literal
        text. Never emit them. To hand a file over, state its absolute path — the tool card
        shows an open button that launches the user's configured editor.
    """.trimIndent()
}

