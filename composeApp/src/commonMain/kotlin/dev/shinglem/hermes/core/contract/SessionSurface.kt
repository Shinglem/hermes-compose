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
