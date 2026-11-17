package dev.shinglem.hermes.component.browser

import dev.shinglem.hermes.component.browser.store.BrowserReducer
import dev.shinglem.hermes.component.browser.store.isBrowserTool
import dev.shinglem.hermes.core.error.AppError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `BrowserReducer` 的纯状态迁移 + 工具名判据（v0.6.1，ADR-082）。
 *
 * ### 钉住的核心语义
 *
 * 1. **纳管与展示正交**：`TakenOver` 不动 `panelOpen`；`PanelToggled` 不动 `phase`。
 * 2. **列表只增不覆**：`TakenOver` 是追加（多实例），只有 `Replaced`/`Detached` 会缩。
 * 3. **已有实例时 `NotFound` 不清列表**（那一轮只是没发现新的）。
 * 4. `Headless` / `Unsupported` / `NoBrowser` 三种"没画面"要分开（处置完全不同）。
 */
class BrowserReducerTest {

    private val reducer = BrowserReducer()

    private fun BrowserState.apply(result: BrowserResult): BrowserState = reducer.run { reduce(result) }

    private fun target(pid: Long = 100L, hwnd: Long = 200L, port: Int = 9222) =
        BrowserTarget(pid = pid, hwnd = hwnd, port = port)

    @Test
    fun `初始状态是收起且未纳管`() {
        val state = BrowserState()
        assertFalse(state.panelOpen)
        assertEquals(Phase.Idle, state.phase)
        assertTrue(state.targets.isEmpty())
        assertNull(state.activeTarget)
        assertFalse(state.takenOver)
        assertFalse(state.showing)
    }

    // ── 轴一：纳管 ────────────────────────────────────────────────────────────

    @Test
    fun `纳管后 phase 变 TakenOver 但抽屉仍然关着`() {
        val state = BrowserState().apply(BrowserResult.TakenOver(listOf(target())))

        assertEquals(Phase.TakenOver, state.phase)
        assertEquals(1, state.targets.size)
        assertEquals(100L, state.activeTarget?.pid)
        assertTrue(state.takenOver)
        // **本次需求的核心**：agent 调浏览器工具不该弹抽屉。
        assertFalse(state.panelOpen, "纳管不得改变 panelOpen")
        assertFalse(state.showing, "抽屉没开就不算在展示")
    }

    @Test
    fun `多实例：第二次纳管是追加而不是覆盖`() {
        val one = BrowserState().apply(BrowserResult.TakenOver(listOf(target(pid = 1L, hwnd = 11L))))
        val two = one.apply(BrowserResult.TakenOver(listOf(target(pid = 2L, hwnd = 22L))))

        assertEquals(2, two.targets.size, "新实例要追加，不能替换掉旧的")
        assertEquals(listOf(1L, 2L), two.targets.map { it.pid })
        assertEquals(Phase.TakenOver, two.phase)
        // 首次纳管的那只保持选中（用户没要求切换就别动）。
        assertEquals(1L, two.activeTarget?.pid)
    }

    @Test
    fun `多实例：同一 pid 重复纳管只更新不重复`() {
        val one = BrowserState().apply(BrowserResult.TakenOver(listOf(target(pid = 1L, hwnd = 11L))))
        val again = one.apply(BrowserResult.TakenOver(listOf(target(pid = 1L, hwnd = 99L))))

        assertEquals(1, again.targets.size)
        assertEquals(99L, again.targets.single().hwnd, "句柄变了要更新")
    }

    @Test
    fun `多实例：一次纳管多只`() {
        val state = BrowserState().apply(
            BrowserResult.TakenOver(listOf(target(1L, 11L), target(2L, 22L), target(3L, 33L))),
        )
        assertEquals(3, state.targets.size)
        assertEquals(1L, state.activeTarget?.pid, "第一只默认选中")
    }

    // ── 轴二：展示 ────────────────────────────────────────────────────────────

    @Test
    fun `展开抽屉不解挂`() {
        val taken = BrowserState().apply(BrowserResult.TakenOver(listOf(target())))
        val opened = taken.apply(BrowserResult.PanelToggled(open = true))

        assertTrue(opened.panelOpen)
        assertTrue(opened.takenOver, "展开只切展示，纳管状态保留")
        assertTrue(opened.showing)
    }

    @Test
    fun `收起抽屉也不解挂 —— 否则窗口会被还原成独立窗口`() {
        val taken = BrowserState(panelOpen = true).apply(BrowserResult.TakenOver(listOf(target())))
        val closed = taken.apply(BrowserResult.PanelToggled(open = false))

        assertFalse(closed.panelOpen)
        assertEquals(Phase.TakenOver, closed.phase, "收起抽屉不得解挂")
        assertEquals(1, closed.targets.size)
        assertFalse(closed.showing)
    }

    @Test
    fun `切换实例只改 activePid`() {
        val two = BrowserState().apply(
            BrowserResult.TakenOver(listOf(target(1L, 11L), target(2L, 22L))),
        )
        val switched = two.apply(BrowserResult.Selected(2L))
        assertEquals(2L, switched.activeTarget?.pid)
        assertEquals(2, switched.targets.size, "切换不影响列表")
    }

    @Test
    fun `切换到不存在的 pid 被忽略`() {
        val one = BrowserState().apply(BrowserResult.TakenOver(listOf(target(1L, 11L))))
        val unchanged = one.apply(BrowserResult.Selected(999L))
        assertEquals(1L, unchanged.activeTarget?.pid)
    }

    // ── 未纳管的四种状态 ──────────────────────────────────────────────────────

    @Test
    fun `没有实例时 NotFound 落到三个不同 Phase`() {
        assertEquals(
            Phase.NoBrowser,
            BrowserState().apply(BrowserResult.NotFound(null, unsupported = false)).phase,
        )
        assertEquals(
            Phase.Headless(pid = 42L),
            BrowserState().apply(BrowserResult.NotFound(42L, unsupported = false)).phase,
        )
        assertEquals(
            Phase.Unsupported,
            BrowserState().apply(BrowserResult.NotFound(null, unsupported = true)).phase,
        )
    }

    @Test
    fun `已有实例时 NotFound 不清列表`() {
        // 这一条很关键：纳管着 A，agent 又开了一轮但新实例还没起来，
        // 此时 discover 返回"没有新的" —— 不能把 A 从列表里抹掉。
        val one = BrowserState(panelOpen = true).apply(BrowserResult.TakenOver(listOf(target(1L, 11L))))
        val after = one.apply(BrowserResult.NotFound(headlessPid = null, unsupported = false))

        assertEquals(1, after.targets.size, "已有实例时不该清列表")
        assertEquals(Phase.TakenOver, after.phase)
    }

    @Test
    fun `Failed 与 NotFound 不会互相覆盖`() {
        val failed = BrowserState().apply(BrowserResult.Failed(AppError.Timeout("读目录超时")))
        assertTrue(failed.phase is Phase.Failed, "IO 失败是 Failed，不是 NoBrowser")
        assertTrue(failed.lastError.orEmpty().contains("读目录超时"))

        val notFound = failed.apply(BrowserResult.NotFound(null, unsupported = false))
        assertEquals(Phase.NoBrowser, notFound.phase)
        assertNull(notFound.lastError)
    }

    @Test
    fun `SearchStarted 清掉上次错误`() {
        val searching = BrowserState(lastError = "x", phase = Phase.Failed("x"))
            .apply(BrowserResult.SearchStarted)
        assertEquals(Phase.Searching, searching.phase)
        assertNull(searching.lastError)
    }

    // ── Searching 必须有出口（实测 bug：界面永远转圈）────────────────────────

    @Test
    fun `已有实例时 SearchFinished 把 Searching 收回 TakenOver`() {
        // 这条是实测 bug 的直接回归：日志显示纳管成功，但界面一直停在"正在查找…"，
        // 因为 refreshAll 先置 Searching，而"没发现新实例"那条路径什么都不 dispatch。
        val taken = BrowserState(panelOpen = true).apply(BrowserResult.TakenOver(listOf(target())))
        val searching = taken.apply(BrowserResult.SearchStarted)
        assertEquals(Phase.Searching, searching.phase)

        val finished = searching.apply(BrowserResult.SearchFinished(found = true))
        assertEquals(Phase.TakenOver, finished.phase, "有实例就必须回到 TakenOver，不能卡在 Searching")
        assertTrue(finished.showing)
    }

    @Test
    fun `没有实例时 SearchFinished 收回 NoBrowser`() {
        val searching = BrowserState(panelOpen = true).apply(BrowserResult.SearchStarted)
        val finished = searching.apply(BrowserResult.SearchFinished(found = false))
        assertEquals(Phase.NoBrowser, finished.phase)
    }

    @Test
    fun `SearchFinished 不覆盖 Failed`() {
        val failed = BrowserState().apply(BrowserResult.Failed(AppError.Timeout("x")))
        val after = failed.apply(BrowserResult.SearchFinished(found = false))
        assertTrue(after.phase is Phase.Failed, "失败态不该被 SearchFinished 抹掉")
    }

    @Test
    fun `SearchFinished 不动 panelOpen`() {
        val searching = BrowserState(panelOpen = false).apply(BrowserResult.SearchStarted)
        val finished = searching.apply(BrowserResult.SearchFinished(found = false))
        assertFalse(finished.panelOpen)
    }

    // ── 心跳兜底：空列表时也能纳管（实测 bug）────────────────────────────────
    //
    // 实测反馈：「第一次 browser_exec 失败，重试才成功，但点按钮还是找不到浏览器，
    // 任务栏上能看到浏览器图标。点刷新后才正常看到内嵌浏览器。」
    //
    // 根因有两层：
    // 1. AppShell 的心跳条件 `if (!takenOver) return` —— 未纳管时根本不跑；
    // 2. Executor 的 heartbeat 也有 `if (targets.isEmpty()) return`。
    // 两者叠加 → TakeOver 的 5s 重试窗口用完后没有任何兜底。
    //
    // 下面这几条钉住"列表为空时也能被填上"这条语义。

    @Test
    fun `空列表时收到 TakenOver 能正常纳管`() {
        val state = BrowserState(panelOpen = true, phase = Phase.NoBrowser)
            .apply(BrowserResult.TakenOver(listOf(target(pid = 7L, hwnd = 77L))))

        assertEquals(1, state.targets.size, "心跳兜底要把实例填进空列表")
        assertEquals(Phase.TakenOver, state.phase)
        assertTrue(state.showing)
    }

    @Test
    fun `空列表时 SearchFinished 不留下 Searching`() {
        // 心跳从 Searching 进来、又没找到东西时，必须收回到 NoBrowser。
        val searching = BrowserState().apply(BrowserResult.SearchStarted)
        assertEquals(Phase.NoBrowser, searching.apply(BrowserResult.SearchFinished(found = false)).phase)
    }

    @Test
    fun `已纳管时 SearchFinished 不会退化成 NoBrowser`() {
        val two = BrowserState().apply(
            BrowserResult.TakenOver(listOf(target(1L, 11L), target(2L, 22L))),
        ).apply(BrowserResult.SearchStarted)

        val finished = two.apply(BrowserResult.SearchFinished(found = true))
        assertEquals(Phase.TakenOver, finished.phase)
        assertEquals(2, finished.targets.size, "SearchFinished 不得清列表")
    }

    // ── 其它 ──────────────────────────────────────────────────────────────────

    @Test
    fun `宽度被夹到合法区间`() {
        assertEquals(
            BrowserState.MAX_WIDTH_DP,
            BrowserState().apply(BrowserResult.PanelResized(10_000)).panelWidthDp,
        )
        assertEquals(
            BrowserState.MIN_WIDTH_DP,
            BrowserState().apply(BrowserResult.PanelResized(1)).panelWidthDp,
        )
    }

    @Test
    fun `Detached 清空全部`() {
        val two = BrowserState(panelOpen = true).apply(
            BrowserResult.TakenOver(listOf(target(1L, 11L), target(2L, 22L))),
        )
        val lost = two.apply(BrowserResult.Detached)
        assertTrue(lost.targets.isEmpty())
        assertNull(lost.activePid)
        assertEquals(Phase.NoBrowser, lost.phase)
    }

    @Test
    fun `Replaced 收缩列表并修正选中项`() {
        val two = BrowserState().apply(
            BrowserResult.TakenOver(listOf(target(1L, 11L), target(2L, 22L))),
        ).apply(BrowserResult.Selected(2L))

        val pruned = two.apply(BrowserResult.Replaced(listOf(target(1L, 11L))))
        assertEquals(1, pruned.targets.size)
        assertEquals(1L, pruned.activeTarget?.pid, "选中的那只被摘掉后要退回第一只")
        assertEquals(Phase.TakenOver, pruned.phase, "摘掉部分实例不该改变纳管状态")
    }

    @Test
    fun `Replaced 清空时退回 NoBrowser`() {
        val one = BrowserState(panelOpen = true).apply(BrowserResult.TakenOver(listOf(target())))
        val empty = one.apply(BrowserResult.Replaced(emptyList()))
        assertTrue(empty.targets.isEmpty())
        assertEquals(Phase.NoBrowser, empty.phase)
    }

    @Test
    fun `PageUrl 只补对应那一只`() {
        val two = BrowserState().apply(
            BrowserResult.TakenOver(listOf(target(1L, 11L), target(2L, 22L))),
        )
        val withUrl = two.apply(BrowserResult.PageUrl(pid = 2L, url = "https://example.com/x"))
        assertNull(withUrl.targets.first { it.pid == 1L }.pageUrl)
        assertEquals("https://example.com/x", withUrl.targets.first { it.pid == 2L }.pageUrl)
    }

    @Test
    fun `DismissError 只清错误`() {
        val errored = BrowserState(panelOpen = true, lastError = "x", phase = Phase.NoBrowser)
        val cleared = errored.apply(BrowserResult.ErrorDismissed)
        assertNull(cleared.lastError)
        assertEquals(Phase.NoBrowser, cleared.phase)
        assertTrue(cleared.panelOpen)
    }

    @Test
    fun `浏览器工具名判定`() {
        assertTrue(isBrowserTool("browser_navigate"))
        assertTrue(isBrowserTool("browser_click"))
        assertTrue(isBrowserTool("browser_exec"))
        assertTrue(isBrowserTool("Browser_Navigate"))
        assertTrue(isBrowserTool("web_search"))

        assertFalse(isBrowserTool("write_file"))
        assertFalse(isBrowserTool("bash"))
        assertFalse(isBrowserTool(""))
    }
}
