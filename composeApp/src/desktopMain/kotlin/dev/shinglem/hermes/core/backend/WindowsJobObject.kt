package dev.shinglem.hermes.core.backend

import dev.shinglem.hermes.core.log.Log
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Windows Job Object 兜底：把后端子进程树绑进一个 Job，**客户端一消失（含被强杀 / 崩溃）
 * 内核立刻杀掉 Job 内的进程**。
 *
 * 为什么必须自己兜：后端自带的 parent-death watchdog 只看父进程的**创建时间**
 * （`hermes_cli/web_server_lifecycle.py::_is_serve_orphaned` → `_process_start_marker`）。
 * Windows 上进程退出后，只要还有别的句柄指着它，`OpenProcess` 仍会成功、`GetProcessTimes`
 * 仍返回原创建时间 —— 于是"创建时间相同"被判成"父进程还活着"，watchdog 永不触发。
 * 实测强杀客户端 46 秒后后端（serve + dashboard）仍在跑，且这正是唯一会留孤儿的情形
 * （正常关窗走 `serve --stop`，按 `HERMES_HOME` 作用域清干净）。
 *
 * 为什么安全：Job 只包含**我们自己 spawn 的进程**（`hermes.exe` shim → python → MCP 工具
 * 子进程全都在内，Job 成员资格默认继承）。不扫进程表、不按名字杀，
 * 碰不到机器上别的 Hermes 安装（`hermes-core/15-关停安全规格.md` R1–R5）。
 *
 * 为什么是单例：`JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` 是**最后一个句柄关闭**时触发。
 * 句柄若被 GC 回收，后端会被当场杀掉 —— 所以句柄必须活到 JVM 结束，不能挂在进程对象上。
 */
internal class WindowsJobObject private constructor(
    private val api: Win32,
    private val handle: MemorySegment,
    private val arena: Arena,
) : AutoCloseable {

    /** 把已启动的子进程放进 Job。失败只记日志：兜底失效时行为退回改动前（watchdog）。 */
    fun assign(process: Process): Boolean {
        val pid = process.pid().toInt()
        val processHandle = api.openProcess(pid)
        if (processHandle.address() == 0L) {
            Log.warn("Job Object：打不开子进程 $pid 的句柄（可能已退出），本次不纳入 Job")
            return false
        }
        return try {
            if (api.assignProcessToJobObject(handle, processHandle)) {
                true
            } else {
                Log.warn("Job Object：子进程 $pid 加入 Job 失败（父进程可能已在别的 Job 里），退回 watchdog")
                false
            }
        } finally {
            api.closeHandle(processHandle)
        }
    }

    /** 关闭后内核会杀掉 Job 里所有进程 —— 测试用它验证兜底真的生效。 */
    override fun close() {
        api.closeHandle(handle)
        arena.close()
    }

    companion object {
        private const val JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000

        /** `JOBOBJECT_EXTENDED_LIMIT_INFORMATION` 在 x64 上是 144 字节。 */
        private const val EXTENDED_LIMIT_SIZE = 144L

        /** `BasicLimitInformation.LimitFlags` 的偏移（两个 LARGE_INTEGER 之后）。 */
        private const val LIMIT_FLAGS_OFFSET = 16L

        private val isWindows: Boolean =
            System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

        fun create(): WindowsJobObject? {
            if (!isWindows) return null
            val api = Win32.api ?: return null
            val arena = Arena.ofShared()
            return try {
                val job = api.createJobObject(arena)
                if (job.address() == 0L) {
                    Log.warn("Job Object：CreateJobObject 失败（error=${api.lastError()}）")
                    arena.close()
                    return null
                }
                val limits = arena.allocate(EXTENDED_LIMIT_SIZE)
                limits.set(ValueLayout.JAVA_INT, LIMIT_FLAGS_OFFSET, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE)
                if (!api.setExtendedLimits(job, limits)) {
                    Log.warn("Job Object：设置 KILL_ON_JOB_CLOSE 失败（error=${api.lastError()}）")
                    api.closeHandle(job)
                    arena.close()
                    return null
                }
                WindowsJobObject(api, job, arena)
            } catch (failure: Throwable) {
                Log.warn("Job Object：初始化失败，本机不做进程树兜底：${failure.message}")
                arena.close()
                null
            }
        }
    }
}

/**
 * 进程级单例 Job：所有后端子进程共享一个 Job，句柄活到 JVM 结束。
 *
 * 一个 Job 而不是每个进程一个：句柄数量少，且"客户端进程消失"这一个事件就能覆盖整批后端。
 */
internal object BackendProcessJob {

    private val job: WindowsJobObject? by lazy { WindowsJobObject.create() }

    fun assign(process: Process): Boolean = job?.assign(process) ?: false
}

/** `kernel32` 的最小绑定：只用 FFM（JDK 25 正式 API），不引 JNA。 */
internal class Win32(
    private val createJob: MethodHandle,
    private val setInfo: MethodHandle,
    private val assignToJob: MethodHandle,
    private val open: MethodHandle,
    private val close: MethodHandle,
    private val captureLayout: MemoryLayout,
) {
    companion object {
        private val linker: Linker = Linker.nativeLinker()
        private val capture = Linker.Option.captureCallState("GetLastError")

        val api: Win32? by lazy {
            try {
                // `defaultLookup()` 在 Windows 上找不到 kernel32 的导出（实测 find 全为 false），
                // 必须显式按库名加载。
                val lookup = SymbolLookup.libraryLookup("kernel32", Arena.global())
                fun handle(name: String, descriptor: FunctionDescriptor): MethodHandle? =
                    lookup.find(name).orElse(null)?.let { linker.downcallHandle(it, descriptor, capture) }

                val createJob = handle(
                    "CreateJobObjectW",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                )
                val setInfo = handle(
                    "SetInformationJobObject",
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                    ),
                )
                val assignToJob = handle(
                    "AssignProcessToJobObject",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                )
                val open = handle(
                    "OpenProcess",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                )
                val close = handle("CloseHandle", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
                if (createJob == null || setInfo == null || assignToJob == null || open == null || close == null) {
                    null
                } else {
                    Win32(createJob, setInfo, assignToJob, open, close, Linker.Option.captureStateLayout())
                }
            } catch (failure: Throwable) {
                Log.warn("Job Object：kernel32 绑定失败：${failure.message}")
                null
            }
        }
    }

    private fun stateSegment(): MemorySegment = Arena.ofAuto().allocate(captureLayout)

    private var lastErrorValue = 0

    fun lastError(): Int = lastErrorValue

    fun createJobObject(arena: Arena): MemorySegment {
        val state = stateSegment()
        val result = createJob.invokeWithArguments(state, MemorySegment.NULL, MemorySegment.NULL) as MemorySegment
        lastErrorValue = state.get(ValueLayout.JAVA_INT, captureLayout.byteOffset(errorPath))
        return result
    }

    fun setExtendedLimits(job: MemorySegment, limits: MemorySegment): Boolean {
        val state = stateSegment()
        val result = setInfo.invokeWithArguments(
            state,
            job,
            9, // JobObjectExtendedLimitInformation
            limits,
            limits.byteSize().toInt(),
        ) as Int
        lastErrorValue = state.get(ValueLayout.JAVA_INT, captureLayout.byteOffset(errorPath))
        return result != 0
    }

    fun assignProcessToJobObject(job: MemorySegment, process: MemorySegment): Boolean {
        val state = stateSegment()
        val result = assignToJob.invokeWithArguments(state, job, process) as Int
        lastErrorValue = state.get(ValueLayout.JAVA_INT, captureLayout.byteOffset(errorPath))
        return result != 0
    }

    fun openProcess(pid: Int): MemorySegment {
        val state = stateSegment()
        val result = open.invokeWithArguments(
            state,
            0x0100 or 0x0001, // PROCESS_SET_QUOTA | PROCESS_TERMINATE
            0,
            pid,
        ) as MemorySegment
        lastErrorValue = state.get(ValueLayout.JAVA_INT, captureLayout.byteOffset(errorPath))
        return result
    }

    fun closeHandle(handle: MemorySegment) {
        if (handle.address() == 0L) return
        runCatching { close.invokeWithArguments(stateSegment(), handle) }
    }

    private val errorPath: MemoryLayout.PathElement = MemoryLayout.PathElement.groupElement("GetLastError")
}
