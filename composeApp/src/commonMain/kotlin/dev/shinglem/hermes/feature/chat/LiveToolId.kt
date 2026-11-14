package dev.shinglem.hermes.feature.chat

/**
 * 流式工具条目的 id 前缀（与历史里落库的区分开，见 `ChatItem.Tool`）。
 *
 * 前台与后台（切走会话的影子状态）**共用**这个前缀：两条路径生成的条目最终会落进
 * 同一个 `load`，前缀不同会让同一次工具调用在切回后变成两段。
 */
internal const val LIVE_TOOL_PREFIX = "live:"
