package dev.shinglem.hermes.core.app

import dev.shinglem.hermes.core.log.Log
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * 单实例：`Local\` 下的命名内核对象。
 *
 * ```
 * Local\HermesCompose-<home hash>-instance   互斥量（存在 = 已有一份在跑）
 * Local\HermesCompose-<home hash>-activate   自动重置事件（第二实例 SetEvent → 第一实例显示窗口）
 * ```
 *
 * 为什么用内核对象而不是锁文件 / PID 文件：**句柄随进程回收，强杀不留僵尸锁**。
 * 锁文件方案最难的恰恰是"文件还在但进程已死"，而 PID 复用会让判别本身出错。
 *
 * 为什么是 `Local\` 而不是 `Global\`：`Global\` 需要 `SeCreateGlobalPrivilege`（管理员有、
 * 普通用户没有）→ 提权实例与非提权实例会**看不到彼此**，单实例在最需要它的场景直接失效。
 *
 * **调用顺序有硬约束**（见 `.design/详细设计/工程与代码/08-单实例与启动时序.md` §5）：
 * 提权重启之前**不能**持有互斥量，否则提权后的新进程会把正在退出的旧进程当成"已有实例"，
 * 双方互相唤醒 → 活锁。所以探测用 [isRunning]（只读），占位用 [acquire]。
 */
internal class SingleInstance private constructor(
    private val api: NativeObjects,
    private val mutex: MemorySegment,
    private val event: MemorySegment,
    private val names: Names,
) : AutoCloseable {

    @Volatile
    private var running = true

    private var watcher: Thread? = null

    /**
     * 起一个等待线程：每次收到 activate 事件就回调一次。
     *
     * 用 1 s 超时轮询而不是 `INFINITE`，这样 [close] 能及时让线程退出（关闭时不需要
     * `CancelSynchronousIo` 这种重手段）。
     */
    fun onActivate(action: () -> Unit) {
        if (watcher != null) return
        watcher = thread(isDaemon = true, name = "hermes-activate-watcher") {
            while (running) {
                val result = api.waitForSingleObject(event, WAIT_SLICE_MS)
                if (!running) break
                if (result == WAIT_OBJECT_0) runCatching(action)
            }
        }
    }

    override fun close() {
        running = false
        watcher?.interrupt()
        watcher = null
        api.closeHandle(event)
        api.closeHandle(mutex)
    }

    companion object {
        private const val WAIT_OBJECT_0 = 0
        private const val WAIT_SLICE_MS = 1_000L

        /** 只读探测：已有一份在跑吗？不解锁、不创建、不占位。 */
        fun isRunning(home: File): Boolean {
            val api = NativeObjects.instance ?: return false
            val handle = api.openMutex(namesFor(home).mutex)
            if (handle == null) return false
            api.closeHandle(handle)
            return true
        }

        /**
         * 正式占位。返回 `null` 表示**已经有实例**（或本机不支持命名对象）——
         * 调用方应当尝试 [requestActivate] 然后退出。
         */
        fun acquire(home: File): SingleInstance? {
            val api = NativeObjects.instance ?: return null
            val names = namesFor(home)
            val mutex = api.createMutex(names.mutex) ?: return null
            if (mutex.alreadyExisted) {
                api.closeHandle(mutex.handle)
                return null
            }
            val event = api.createEvent(names.activate)
            if (event == null) {
                api.closeHandle(mutex.handle)
                return null
            }
            Log.info("单实例已占位：${names.mutex}")
            return SingleInstance(api, mutex.handle, event, names)
        }

        /** 请已有实例把窗口显示出来。返回 false = 送不到（跨权限被拒或对方没建事件）。 */
        fun requestActivate(home: File): Boolean {
            val api = NativeObjects.instance ?: return false
            val handle = api.openEvent(namesFor(home).activate, EVENT_MODIFY_STATE) ?: return false
            return try {
                api.setEvent(handle)
            } finally {
                api.closeHandle(handle)
            }
        }

        private const val EVENT_MODIFY_STATE = 0x0002

        /**
         * 名字按 `<home>` 哈希：两份便携包各自的实例互不影响（这是设计目标，不是缺陷）。
         *
         * 必须用 `canonicalPath`：大小写、`PROGRA~1` 短路径、符号链接都会算成不同名字。
         * 用 4 字节（8 个十六进制位）而不是整串：够避碰撞，且肉眼能读、能贴进日志。
         */
        private fun namesFor(home: File): Names {
            val canonical = runCatching { home.canonicalPath }.getOrDefault(home.absolutePath)
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            val hash = digest.take(4).joinToString("") { "%02x".format(it) }
            return Names(
                mutex = "Local\\HermesCompose-$hash-instance",
                activate = "Local\\HermesCompose-$hash-activate",
            )
        }
    }

    private data class Names(val mutex: String, val activate: String)

    private class MutexHandle(val handle: MemorySegment, val alreadyExisted: Boolean)
}

/** `kernel32` 的命名对象绑定（FFM，无第三方依赖）。 */
internal class NativeObjects(
    private val createMutexHandle: MethodHandle,
    private val openMutexHandle: MethodHandle,
    private val createEventHandle: MethodHandle,
    private val openEventHandle: MethodHandle,
    private val setEventHandle: MethodHandle,
    private val waitHandle: MethodHandle,
    private val closeHandleHandle: MethodHandle,
) {
    companion object {
        private val linker = Linker.nativeLinker()
        private const val ERROR_ALREADY_EXISTS = 183
        private const val SYNCHRONIZE = 0x00100000

        val instance: NativeObjects? by lazy {
            runCatching {
                // `defaultLookup()` 在 Windows 上找不到 kernel32 导出（与 Job Object 同一个坑）。
                val kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global())
                val capture = Linker.Option.captureCallState("GetLastError")
                fun downcall(name: String, descriptor: FunctionDescriptor): MethodHandle? =
                    kernel32.find(name).orElse(null)?.let { linker.downcallHandle(it, descriptor, capture) }

                val createMutex = downcall(
                    "CreateMutexW",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                ) ?: return@runCatching null
                val openMutex = downcall(
                    "OpenMutexW",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                ) ?: return@runCatching null
                val createEvent = downcall(
                    "CreateEventW",
                    FunctionDescriptor.of(
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                    ),
                ) ?: return@runCatching null
                val openEvent = downcall(
                    "OpenEventW",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                ) ?: return@runCatching null
                val setEvent = downcall("SetEvent", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
                    ?: return@runCatching null
                val wait = downcall(
                    "WaitForSingleObject",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                ) ?: return@runCatching null
                val close = downcall("CloseHandle", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
                    ?: return@runCatching null

                NativeObjects(createMutex, openMutex, createEvent, openEvent, setEvent, wait, close)
            }.getOrNull()
        }
    }

    private val captureLayout = Linker.Option.captureStateLayout()
    private val errorPath = java.lang.foreign.MemoryLayout.PathElement.groupElement("GetLastError")

    /** 名字必须 UTF-16：`allocateFrom(String)` 默认写 UTF-8，宽字符 API 会读到乱码（已实测踩过）。 */
    private fun wideName(name: String): MemorySegment = Arena.ofAuto().allocateFrom(name, Charsets.UTF_16LE)

    private fun lastError(state: MemorySegment): Int = state.get(ValueLayout.JAVA_INT, captureLayout.byteOffset(errorPath))

    fun createMutex(name: String): MutexResult? {
        val state = Arena.ofAuto().allocate(captureLayout)
        val handle = createMutexHandle.invokeWithArguments(state, MemorySegment.NULL, 0, wideName(name)) as MemorySegment
        val error = lastError(state)
        if (handle.address() == 0L) return null
        return MutexResult(handle, error == ERROR_ALREADY_EXISTS)
    }

    fun openMutex(name: String): MemorySegment? {
        val state = Arena.ofAuto().allocate(captureLayout)
        val handle = openMutexHandle.invokeWithArguments(state, SYNCHRONIZE, 0, wideName(name)) as MemorySegment
        return handle.takeIf { it.address() != 0L }
    }

    /** 自动重置事件（`bManualReset = 0`）：SetEvent 唤醒一次等待者后自动复位。 */
    fun createEvent(name: String): MemorySegment? {
        val state = Arena.ofAuto().allocate(captureLayout)
        val handle = createEventHandle.invokeWithArguments(state, MemorySegment.NULL, 0, 0, wideName(name)) as MemorySegment
        return handle.takeIf { it.address() != 0L }
    }

    fun openEvent(name: String, desiredAccess: Int): MemorySegment? {
        val state = Arena.ofAuto().allocate(captureLayout)
        val handle = openEventHandle.invokeWithArguments(state, desiredAccess, 0, wideName(name)) as MemorySegment
        return handle.takeIf { it.address() != 0L }
    }

    fun setEvent(handle: MemorySegment): Boolean =
        (setEventHandle.invokeWithArguments(Arena.ofAuto().allocate(captureLayout), handle) as Int) != 0

    fun waitForSingleObject(handle: MemorySegment, timeoutMs: Long): Int =
        waitHandle.invokeWithArguments(
            Arena.ofAuto().allocate(captureLayout),
            handle,
            timeoutMs.toInt(),
        ) as Int

    fun closeHandle(handle: MemorySegment) {
        if (handle.address() == 0L) return
        runCatching { closeHandleHandle.invokeWithArguments(Arena.ofAuto().allocate(captureLayout), handle) }
    }
}

/** `CreateMutexW` 的结果：句柄 + "这个名字已经存在"（`ERROR_ALREADY_EXISTS`）。 */
internal class MutexResult(val handle: MemorySegment, val alreadyExisted: Boolean)
