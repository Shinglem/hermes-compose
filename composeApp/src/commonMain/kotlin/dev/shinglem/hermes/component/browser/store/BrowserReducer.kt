package dev.shinglem.hermes.component.browser.store

import com.arkivanov.mvikotlin.core.store.Reducer
import dev.shinglem.hermes.component.browser.BrowserResult
import dev.shinglem.hermes.component.browser.BrowserState
import dev.shinglem.hermes.component.browser.Phase

/**
 * 浏览器侧栏的**纯**状态迁移：没有协程、没有 IO。
 *
 * ### 四条容易被改坏的判据（都有测试钉住）
 *
 * 1. **纳管与展示独立**：`TakenOver` 只改 `phase`/`targets`，**绝不动 `panelOpen`**。
 * 2. **收起抽屉不影响纳管**：`panelOpen = false` 时 `phase` 保持 `TakenOver`
 *    （槽位不解挂，只是不显示）。
 * 3. **列表只增不覆**：`TakenOver` 是**追加**（多实例）；只有 `Replaced`/`Detached` 会缩。
 * 4. **`NotFound` 与 `Failed` 不能互相覆盖**：前者是"没有浏览器"，后者是"发现出错"。
 */
internal class BrowserReducer : Reducer<BrowserState, BrowserResult> {

    override fun BrowserState.reduce(msg: BrowserResult): BrowserState = when (msg) {
        BrowserResult.SearchStarted -> copy(phase = Phase.Searching, lastError = null)

        is BrowserResult.SearchFinished -> when {
            // 有实例：回到 TakenOver（这是 `Searching → 已有实例` 这条路径的出口）。
            targets.isNotEmpty() -> if (phase is Phase.Searching) copy(phase = Phase.TakenOver) else this
            // 没有实例：只有"确实没找到"才退回 NoBrowser；Failed/Unsupported 保持原样。
            msg.found -> this
            phase is Phase.Searching -> copy(phase = Phase.NoBrowser)
            else -> this
        }

        // 追加：多实例靠这一条累积。已存在同 pid 的用新的替换（hwnd 可能变了）。
        is BrowserResult.TakenOver -> {
            val byPid = targets.associateBy { it.pid }.toMutableMap()
            msg.targets.forEach { byPid[it.pid] = it }
            val merged = targets.map { byPid[it.pid] ?: it } + msg.targets.filterNot { t -> targets.any { it.pid == t.pid } }
            copy(
                targets = merged,
                activePid = activePid ?: msg.targets.firstOrNull()?.pid,
                phase = Phase.TakenOver,
                lastError = null,
                // panelOpen **不动**：纳管 ≠ 展示。
            )
        }

        is BrowserResult.Replaced -> if (msg.targets.isEmpty()) {
            copy(targets = emptyList(), activePid = null, phase = Phase.NoBrowser)
        } else {
            copy(
                targets = msg.targets,
                activePid = activePid?.takeIf { pid -> msg.targets.any { it.pid == pid } }
                    ?: msg.targets.first().pid,
                // 摘掉部分实例不影响 phase。
            )
        }

        is BrowserResult.Selected ->
            if (targets.any { it.pid == msg.pid }) copy(activePid = msg.pid) else this

        is BrowserResult.NotFound -> {
            if (targets.isNotEmpty()) {
                // 已经纳管着东西时，这条只是"这一轮没发现新的"，不该清掉列表。
                this
            } else {
                val phase = when {
                    msg.unsupported -> Phase.Unsupported
                    msg.headlessPid != null -> Phase.Headless(pid = msg.headlessPid)
                    else -> Phase.NoBrowser
                }
                copy(phase = phase, lastError = null)
            }
        }

        is BrowserResult.Failed -> copy(
            phase = Phase.Failed(msg.error.message.orEmpty()),
            lastError = msg.error.message.orEmpty(),
        )

        // 只切展示。**不要**顺手改 phase：纳管状态与抽屉开关是两件事。
        is BrowserResult.PanelToggled -> copy(panelOpen = msg.open)

        is BrowserResult.PanelResized ->
            copy(panelWidthDp = msg.widthDp.coerceIn(BrowserState.MIN_WIDTH_DP, BrowserState.MAX_WIDTH_DP))

        // 全部失效（被关掉 / 进程死了）。
        BrowserResult.Detached -> copy(targets = emptyList(), activePid = null, phase = Phase.NoBrowser)

        // 页面 URL 取到时补进对应那只。取不到就什么都不改。
        is BrowserResult.PageUrl -> if (msg.url == null) this else copy(
            targets = targets.map { if (it.pid == msg.pid) it.copy(pageUrl = msg.url) else it },
        )

        BrowserResult.ErrorDismissed -> copy(lastError = null)
    }
}
