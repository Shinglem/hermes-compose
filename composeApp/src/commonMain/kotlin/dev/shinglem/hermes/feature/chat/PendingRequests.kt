package dev.shinglem.hermes.feature.chat

import kotlinx.coroutines.CompletableDeferred

/**
 * 一条等待客户端作答的 server→client 请求的登记项。
 *
 * 审批与澄清**共用**这一个形状：它们的生命周期完全一致（登记 → 等用户点 → 应答 / 撤回 /
 * 超时 → 摘除），差别只在等待结果的类型（`String` vs `Map<String,String>?`）。
 */
internal class PendingRequest<T>(
    /** JSON-RPC 帧 id：`request.cancel` 带的是它，不是业务 id。 */
    val frameId: String,
    /** 业务 `request_id`：卡片按钮与 `approval.respond` / `clarify.respond` 用它。 */
    val requestId: String,
    /**
     * 等待用户决定。
     *
     * 值的可空性由调用方决定（`approval` 是 `String`，`clarify` 用 `null` 表示"取消"），
     * 句柄本身不该为 null —— 它由登记方创建、由应答方完成。
     */
    val decision: CompletableDeferred<T>,
)

/**
 * 「等客户端应答」的请求表（S8：原 `approvalDecisions` + `approvalFrameIds` +
 * `clarifyDecisions` + `clarifyFrameIds` 四张平行表）。
 *
 * ### 为什么要合并
 *
 * 迁移前每次登记都要**同时**写两张表（`decisions[requestId]` 与 `frameIds[frameId]`），
 * 摘除时也要同时删两张。少写一处就是"卡片点了没反应"或"`request.cancel` 撤不掉"，
 * 而这两种症状都不报错、只能靠用户复现。合成一条记录后"成对"由类型保证。
 *
 * ### 为什么按业务 `requestId` 索引、帧 id 单独一条索引
 *
 * 两条路径各自需要 O(1)：
 * - 卡片按钮给的是 `requestId` → 直接取 [PendingRequest.decision]；
 * - `request.cancel` 给的是 `frameId` → 先反查到记录才能取业务 id。
 *
 * **线程约束**：与 `ChatViewModel` 的其余簿记一样，只在单一协程上下文里读写。
 */
internal class PendingRequests<T> {

    private val byRequestId = mutableMapOf<String, PendingRequest<T>>()

    /** `frameId → requestId`：`request.cancel` 只带帧 id。 */
    private val requestIdByFrame = mutableMapOf<String, String>()

    val size: Int get() = byRequestId.size

    operator fun get(requestId: String): PendingRequest<T>? = byRequestId[requestId]

    /** 登记一条；同 `requestId` 重复登记时后者覆盖（重连补拉会重复拿到同一条）。 */
    fun register(request: PendingRequest<T>) {
        byRequestId[request.requestId] = request
        requestIdByFrame[request.frameId] = request.requestId
    }

    /** 按业务 id 摘除，返回被摘掉的那条（没有则 `null`）。 */
    fun removeByRequestId(requestId: String): PendingRequest<T>? {
        val removed = byRequestId.remove(requestId) ?: return null
        requestIdByFrame.remove(removed.frameId)
        return removed
    }

    /** 按帧 id 摘除：`request.cancel` 走这条。 */
    fun removeByFrameId(frameId: String): PendingRequest<T>? {
        val requestId = requestIdByFrame[frameId] ?: return null
        return removeByRequestId(requestId)
    }

    /** 按业务 id 取等待句柄（不应答，只 peek）。 */
    fun decisionOf(requestId: String): CompletableDeferred<T>? = byRequestId[requestId]?.decision

    /**
     * 按帧 id 找到业务 id 并取走等待句柄（`request.cancel` 用）。
     *
     * 与 [removeByFrameId] 的区别：这里**不移除记录** —— 撤回后等待方自己会走 `finally`
     * 摘除，提前摘会让它的 `resolve` 变成空操作（真出过"撤回后按钮还在"）。
     */
    fun takeDecisionByFrameId(frameId: String): CompletableDeferred<T>? =
        requestIdByFrame[frameId]?.let { byRequestId[it]?.decision }

    fun businessIdOf(frameId: String): String? = requestIdByFrame[frameId]

    fun clear() {
        byRequestId.clear()
        requestIdByFrame.clear()
    }

    /**
     * 取消全部等待并清空（`dispose()` 用）。
     *
     * 漏掉这一步的后果是 `CompletableDeferred` 永远挂着：销毁后既没人应答、也不会超时，
     * 后端那边的等待队列同样残留。
     */
    fun cancelAll() {
        byRequestId.values.forEach { it.decision.cancel() }
        clear()
    }

    /** 按业务 id 摘下等待句柄并完成（`request.cancel` 的审批路径）。 */
    fun completeByRequestId(requestId: String, value: T): Boolean {
        val record = removeByRequestId(requestId) ?: return false
        return record.decision.complete(value)
    }

    /** 诊断用：当前登记的业务 id（日志里能看出"后端在等谁没被答"）。 */
    fun requestIds(): Set<String> = byRequestId.keys.toSet()
}
