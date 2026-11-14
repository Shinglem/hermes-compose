package dev.shinglem.hermes.component.chat

import androidx.compose.runtime.Immutable
import dev.shinglem.hermes.feature.chat.SessionKey
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf

/**
 * 一个会话的**运行时身份**：resume 拿回来的那几个值 + 活跃度。
 *
 * 迁移前这些是 `ChatViewModel` 里五个平行的 map（`runtimeIds` / `generations` /
 * `revisions` / `lastEventAt` / `cwds`），每个都要单独 `get`/`put`/`remove`，
 * 而它们**永远同生同灭**（resume 时一起写、重连时一起清）。合成一个值对象后：
 * - 不可能再出现"清了 runtimeId 但忘了清 generation"这类半残状态；
 * - 反向索引（`runtimeId → SessionKey`）有了唯一的维护点。
 *
 * [fence] 就是 `execution_generation` —— canonical gateway 把它当"这一轮的执行身份"，
 * `session.interrupt` / `session.steer` / `approval.respond` 都**必填**
 * （缺了就是 `4001 invalid_params`）。
 */
@Immutable
data class SessionRuntime(
    val key: SessionKey,
    val runtimeId: String,
    /** `session.resume` 的 `execution_generation`。 */
    val fence: Int? = null,
    /** `session.mutate` 的 `expected_revision`。 */
    val revision: Int? = null,
    /** 会话工作目录：相对路径的工具参数按它解析，也供 composer 的 `@` 补全。 */
    val cwd: String? = null,
    /** 最近一次收到该会话事件的时刻（毫秒）；看门狗据此判断静默。 */
    val lastEventAt: Long = 0L,
    /** 切走期间有新内容或已完成一轮：切回必须重读一次 transcript。 */
    val dirty: Boolean = false,
)

/**
 * `SessionKey → SessionRuntime` 的唯一持有者。
 *
 * **不是** Store，也不是 Flow —— 它是 Executor 内部的可变簿记，理由：
 * 这些值**不参与渲染**（UI 只要有内容、有没有东西在跑），变成 StateFlow 只会
 * 让每次事件都触发一次重组。UI 需要的那部分（当前会话的模型、cwd）走 `ChatState`。
 *
 * 唯一对外提供的是 [byRuntimeId] 反向索引：迁移前有 4 处 `entries.firstOrNull { it.value == id }`
 * 的 O(n) 反查（每次事件都跑一遍），现在 O(1)。
 *
 * **线程约束**：只在 Executor 的单一协程上下文里读写，不加锁。
 */
internal class SessionRuntimeStore {

    private var runtimes: PersistentMap<SessionKey, SessionRuntime> = persistentMapOf()

    /** `runtimeId → SessionKey`：事件只带 runtime id，回查会话靠它。 */
    private var byRuntime: PersistentMap<String, SessionKey> = persistentMapOf()

    val size: Int get() = runtimes.size

    val keys: Set<SessionKey> get() = runtimes.keys

    operator fun get(key: SessionKey): SessionRuntime? = runtimes[key]

    /** 反向索引：`runtime session id` → 会话。迁移前是 4 处 O(n) 反查。 */
    fun byRuntimeId(runtimeId: String?): SessionKey? {
        val id = runtimeId?.takeIf { it.isNotBlank() } ?: return null
        return byRuntime[id]
    }

    /** 登记 / 覆盖一个会话的运行时。会同步维护反向索引。 */
    fun put(runtime: SessionRuntime) {
        // 同一个会话换了 runtimeId（重连后必然发生）：旧的反向索引必须摘掉，
        // 否则旧 id 会一直指向这个会话，事件被错误归属。
        runtimes[runtime.key]?.runtimeId?.takeIf { it != runtime.runtimeId }?.let { stale ->
            byRuntime = byRuntime.removing(stale)
        }
        runtimes = runtimes.putting(runtime.key, runtime)
        byRuntime = byRuntime.putting(runtime.runtimeId, runtime.key)
    }

    /** 原地更新（不存在则忽略）。**不**碰反向索引 —— runtimeId 不该在这里变。 */
    fun update(key: SessionKey, block: (SessionRuntime) -> SessionRuntime) {
        val current = runtimes[key] ?: return
        runtimes = runtimes.putting(key, block(current))
    }

    fun remove(key: SessionKey) {
        val current = runtimes[key] ?: return
        byRuntime = byRuntime.removing(current.runtimeId)
        runtimes = runtimes.removing(key)
    }

    /**
     * 全部清空。
     *
     * 断线重连时用：订阅与"本端会应答 server→client 请求"的声明都绑在**连接**上，
     * 旧 runtime 的 fence 在新连接上无效。**不**清 [SessionRuntime.dirty]
     * （那是内容事实，与连接无关）—— 所以重连用 [clearConnectionScoped] 而不是这个方法。
     */
    fun clear() {
        runtimes = persistentMapOf()
        byRuntime = persistentMapOf()
    }

    /**
     * 只清"与连接绑定"的字段（fence / revision / lastEventAt），保留 key、runtimeId 与 cwd。
     *
     * 这是迁移前 `generations.clear() + revisions.clear() + lastEventAt.clear()` 三行的语义：
     * 重连后旧的 fence 在新连接上无效，但会话本身还开着、cwd 也没变。
     */
    fun clearConnectionScoped() {
        var next = persistentMapOf<SessionKey, SessionRuntime>()
        for ((key, runtime) in runtimes) {
            next = next.putting(key, runtime.copy(fence = null, revision = null, lastEventAt = 0L))
        }
        runtimes = next
    }

    fun markDirty(key: SessionKey, dirty: Boolean = true) {
        update(key) { it.copy(dirty = dirty) }
    }

    fun touch(key: SessionKey, atMillis: Long) {
        update(key) { it.copy(lastEventAt = atMillis) }
    }
}
