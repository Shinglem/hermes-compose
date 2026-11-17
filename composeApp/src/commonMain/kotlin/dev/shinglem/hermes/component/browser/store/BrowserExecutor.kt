package dev.shinglem.hermes.component.browser.store

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import dev.shinglem.hermes.component.browser.BrowserEffect
import dev.shinglem.hermes.component.browser.BrowserGateway
import dev.shinglem.hermes.component.browser.BrowserIntent
import dev.shinglem.hermes.component.browser.BrowserResult
import dev.shinglem.hermes.component.browser.BrowserState
import dev.shinglem.hermes.core.error.asAppError
import dev.shinglem.hermes.core.log.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** 浏览器工具的判定：后端那批工具名都以 `browser` 开头（`browser_navigate` / `browser_click` / `browser_exec`…）。 */
internal fun isBrowserTool(name: String): Boolean =
    name.startsWith("browser", ignoreCase = true) || name.startsWith("web_", ignoreCase = true)

/**
 * 浏览器侧栏的**执行器**：唯一做 IO 的地方。
 *
 * ### 两条**正交**的轴
 *
 * | 轴 | 由什么驱动 | 效果 |
 * |---|---|---|
 * | **纳管** | agent 调 `browser_*` 工具 | 窗口收进本进程，**但不显示** |
 * | **展示** | 用户点侧栏按钮 | 打开抽屉；直接复用已纳管的窗口 |
 *
 * 纳管**绝不能**顺手把 `panelOpen` 置 true（用户明确要求抽屉默认关闭）。
 *
 * ### 纳管要"等窗口出现"，不能只探一次
 *
 * 实测时序：`tool.start` 在 t≈1s 到达，但后端把 Chromium 拉起来、写出 launch record、
 * 真实顶层窗口出现要 t≈4–5s。第一次发现必然扑空；只探一次就要等 5s 心跳，
 * 用户看到的就是"要等 5 秒才纳管"。
 *
 * 所以 [BrowserIntent.TakeOver] 走**短间隔重试**（[TAKEOVER_RETRY_MS] × [TAKEOVER_MAX_ATTEMPTS]）：
 * 窗口一出现立刻纳管，通常 1 秒内完成。
 *
 * ### 多实例
 *
 * 后端每个 launcher key 一只浏览器（不同会话 / 不同 profile 各一只）。
 * [BrowserState.targets] 是**列表**：已纳管的都留在里面，新的 `tool.start` 继续往列表里加。
 * 抽屉按列表顺序展示，可切换。
 *
 * ### 判活不能用 `discover()`
 *
 * `NativeView` 把窗口收成 child window 后，顶层枚举链里就没有它了；`discover()` 会返回
 * "没找到" → 误判消失 → 销毁槽位 → 窗口恢复独立 → 下一轮又找到 → **无限重挂**。
 * 判活只问 `isSlotAlive(hwnd, pid)`。
 */
internal class BrowserExecutor(
    private val gateway: BrowserGateway,
    executorContext: CoroutineContext,
) : CoroutineExecutor<BrowserIntent, Nothing, BrowserState, BrowserResult, BrowserEffect>(executorContext) {

    /** 同一时刻只允许一个发现在飞。 */
    private var probing = false

    /** 正在重试的纳管任务；新的 `TakeOver` 复用它而不是叠加。 */
    private var takeOverJob: Job? = null

    override fun executeIntent(intent: BrowserIntent) {
        when (intent) {
            BrowserIntent.TogglePanel -> toggle()
            is BrowserIntent.ResizePanel -> dispatch(BrowserResult.PanelResized(intent.widthDp))
            BrowserIntent.Refresh -> refreshAll(manual = true)
            BrowserIntent.Heartbeat -> heartbeat()
            BrowserIntent.OpenInSystemBrowser -> openExternal()
            BrowserIntent.SlotLost -> dispatch(BrowserResult.Detached)
            BrowserIntent.TakeOver -> takeOver()
            is BrowserIntent.SelectTarget -> dispatch(BrowserResult.Selected(intent.pid))
            BrowserIntent.DismissError -> dispatch(BrowserResult.ErrorDismissed)
        }
    }

    /**
     * 展开 / 收起抽屉。展开时总是重扫一遍（可能又冒出新实例）；已纳管的不会因此重挂。
     */
    private fun toggle() {
        val open = !state().panelOpen
        dispatch(BrowserResult.PanelToggled(open))
        if (open) refreshAll(manual = true)
    }

    /**
     * agent 开始操作浏览器 → 纳管（**不打开抽屉**）。
     *
     * 短间隔重试等窗口出现 —— 见类注释的时序说明。
     */
    private fun takeOver() {
        if (takeOverJob?.isActive == true) return

        takeOverJob = scope.launch {
            repeat(TAKEOVER_MAX_ATTEMPTS) {
                if (!isActive) return@launch
                if (discoverOnce(manual = false)) return@launch
                delay(TAKEOVER_RETRY_MS)
            }
            Log.info("browser: 重试 $TAKEOVER_MAX_ATTEMPTS 次仍未找到可纳管的窗口，交给心跳兜底")
        }
    }

    /** 全量扫描：把当前所有可见的 agent 浏览器都纳管。 */
    private fun refreshAll(manual: Boolean) {
        if (manual) dispatch(BrowserResult.SearchStarted)
        scope.launch { discoverOnce(manual = manual) }
    }

    /**
     * 发现一次，纳管**所有**还没纳管的可见实例。
     *
     * @return 是否至少纳管了一只（`TakeOver` 的重试靠它决定要不要再试）。
     */
    private suspend fun discoverOnce(manual: Boolean): Boolean {
        if (probing) return false

        // 廉价短路：还没有任何 launch record（agent 从没起过浏览器）时直接返回，
        // 不去枚举窗口。心跳每 5s 跑一次，这个短路让"空闲时"的开销降到一次目录列举。
        if (gateway.hasAnyRecord().not() && state().targets.isEmpty()) {
            if (manual) dispatch(BrowserResult.NotFound(headlessPid = null, unsupported = false))
            return false
        }

        probing = true
        try {
            val result = gateway.discoverAll()
            if (result.unsupported) {
                dispatch(BrowserResult.NotFound(headlessPid = null, unsupported = true))
                return false
            }
            if (result.targets.isEmpty()) {
                // 一个可见实例都没有：先把已关闭的摘掉，再定状态。
                // （不摘的话，浏览器被关掉后列表里会一直留着它。）
                pruneDead()
                if (manual) {
                    dispatch(BrowserResult.NotFound(headlessPid = result.headlessPid, unsupported = false))
                } else {
                    // 非手动（TakeOver 重试 / 心跳）也要收尾，否则 Searching 会一直挂着。
                    dispatch(BrowserResult.SearchFinished(found = state().targets.isNotEmpty()))
                }
                return false
            }

            val known = state().targets.map { it.pid }.toSet()
            val fresh = result.targets.filterNot { it.pid in known }
            if (fresh.isNotEmpty()) {
                Log.info("browser: 纳管 ${fresh.size} 只（pid=${fresh.joinToString { it.pid.toString() }}）")
                dispatch(BrowserResult.TakenOver(fresh))
                val active = fresh.first()
                gateway.activePageUrl(active.port)?.let { dispatch(BrowserResult.PageUrl(active.pid, it)) }
                return true
            }

            // 没有新实例：把已死的摘掉，并结束 Searching 态。
            // **必须**发 SearchFinished：`refreshAll(manual = true)` 先把状态置成 Searching，
            // 这里如果不收尾，界面就会永远停在"正在查找…"的转圈上 —— 即使实例已经纳管好了
            // （实测踩过：日志显示纳管成功，界面却一直是 spinner）。
            pruneDead()
            dispatch(BrowserResult.SearchFinished(found = state().targets.isNotEmpty()))
            return false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            dispatch(BrowserResult.Failed(throwable.asAppError("browser.discover")))
            return false
        } finally {
            probing = false
        }
    }

    /** 把已经关掉的实例从列表里摘掉。 */
    private fun pruneDead() {
        val targets = state().targets
        if (targets.isEmpty()) return
        val alive = targets.filter { gateway.isSlotAlive(it.hwnd, it.pid) }
        if (alive.size != targets.size) {
            Log.info("browser: 摘掉 ${targets.size - alive.size} 只已关闭的实例")
            dispatch(BrowserResult.Replaced(alive))
        }
    }

    /**
     * 心跳：**始终**跑一次完整发现（不管当前有没有纳管任何实例）。
     *
     * ### 为什么不能只在"已纳管"时跑
     *
     * `TakeOver` 的重试窗口只有几秒。实测会碰上这种时序：
     * 第一次 `browser_exec` 因后端 daemon 故障失败 → Chrome 进程起来了但窗口状态异常
     * → 重试窗口用完放弃 → **此时 targets 还是空的**。
     * 如果心跳也因 `targets.isEmpty()` 直接 return，就再也没有人去发现那只浏览器了，
     * 用户只能手动点「重新连接」（实测反馈：「任务栏能看到浏览器图标，但抽屉里找不到」）。
     *
     * ### 为什么这里可以直接调 `discoverOnce`
     *
     * `discoverOnce` 内部按 pid 去重（`fresh` 只留未纳管的），已纳管的不会被重复挂；
     * 它同时会 `pruneDead()` 摘掉已关闭的。所以「判活」与「发现新实例」是同一趟扫描。
     *
     * 已纳管的窗口不在顶层枚举链里，不会出现在 `result.targets` 里，因此不会被误判成"新的"。
     */
    private fun heartbeat() {
        scope.launch {
            try {
                discoverOnce(manual = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // 心跳失败不弹错误条：下一轮还会试。
            }
        }
    }

    private fun openExternal() {
        val port = state().activeTarget?.port ?: return
        scope.launch {
            val url = try {
                gateway.activePageUrl(port)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            if (url != null) publish(BrowserEffect.OpenExternal(url))
        }
    }

    private companion object {
        /** 重试间隔：后端通常 1–2s 就绪，250ms 让首次窗口出现就能赶上。 */
        const val TAKEOVER_RETRY_MS = 250L

        /** 重试上限：20 × 250ms = 5s，覆盖实测最慢的 4–5s 冷启。 */
        const val TAKEOVER_MAX_ATTEMPTS = 20
    }
}
