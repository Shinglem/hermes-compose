package dev.shinglem.hermes.core.app

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * 当前进程是否以管理员身份运行。
 *
 * 用令牌的 elevation 类型判断，而不是 `IsUserAnAdmin()`：
 * - `IsUserAnAdmin()` 只答"这个用户在不在管理员组"，开了 UAC 之后即使没提权也返回 true；
 * - 令牌判据答的是"**当前进程**是否用了提升后的令牌"，正是我们要的。
 *
 * 只在 desktop 目标可用；调用方（`Main.kt`）本来就只在桌面端运行。
 */
internal object Elevation {

    private const val TOKEN_QUERY = 0x0008
    private const val TOKEN_ELEVATION = 20 // TOKEN_INFORMATION_CLASS.TokenElevation

    fun isElevated(): Boolean = runCatching { probe() }.getOrDefault(false)

    private fun probe(): Boolean {
        val api = Api.instance ?: return false
        val process = api.getCurrentProcess()
        val tokenSlot = Arena.ofAuto().allocate(ValueLayout.ADDRESS)
        if (!api.openProcessToken(process, TOKEN_QUERY, tokenSlot)) return false
        val token = tokenSlot.get(ValueLayout.ADDRESS, 0)
        if (token.address() == 0L) return false
        return try {
            val buffer = Arena.ofAuto().allocate(4)
            val sizeSlot = Arena.ofAuto().allocate(4)
            sizeSlot.set(ValueLayout.JAVA_INT, 0, 4)
            val ok = api.getTokenInformation(token, TOKEN_ELEVATION, buffer, 4, sizeSlot)
            ok && buffer.get(ValueLayout.JAVA_INT, 0) != 0
        } finally {
            api.closeHandle(token)
        }
    }

    private class Api(
        private val getCurrentProcessHandle: MethodHandle,
        private val openProcessTokenHandle: MethodHandle,
        private val getTokenInformationHandle: MethodHandle,
        private val closeHandleHandle: MethodHandle,
    ) {
        companion object {
            private val linker = Linker.nativeLinker()

            val instance: Api? by lazy {
                runCatching {
                    // 与 WindowsJobObject 同一个坑：defaultLookup() 在 Windows 上找不到 kernel32 导出。
                    val kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global())
                    val advapi32 = SymbolLookup.libraryLookup("advapi32", Arena.global())
                    val getCurrentProcess = kernel32.find("GetCurrentProcess").orElse(null)
                        ?: return@runCatching null
                    val openProcessToken = advapi32.find("OpenProcessToken").orElse(null)
                        ?: return@runCatching null
                    val getTokenInformation = advapi32.find("GetTokenInformation").orElse(null)
                        ?: return@runCatching null
                    val closeHandle = kernel32.find("CloseHandle").orElse(null) ?: return@runCatching null
                    Api(
                        getCurrentProcessHandle = linker.downcallHandle(
                            getCurrentProcess,
                            FunctionDescriptor.of(ValueLayout.ADDRESS),
                        ),
                        openProcessTokenHandle = linker.downcallHandle(
                            openProcessToken,
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                            ),
                        ),
                        getTokenInformationHandle = linker.downcallHandle(
                            getTokenInformation,
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                            ),
                        ),
                        closeHandleHandle = linker.downcallHandle(
                            closeHandle,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                        ),
                    )
                }.getOrNull()
            }
        }

        fun getCurrentProcess(): MemorySegment = getCurrentProcessHandle.invokeWithArguments() as MemorySegment

        fun openProcessToken(process: MemorySegment, access: Int, tokenOut: MemorySegment): Boolean =
            (openProcessTokenHandle.invokeWithArguments(process, access, tokenOut) as Int) != 0

        fun getTokenInformation(
            token: MemorySegment,
            infoClass: Int,
            buffer: MemorySegment,
            bufferSize: Int,
            sizeOut: MemorySegment,
        ): Boolean = (getTokenInformationHandle.invokeWithArguments(
            token,
            infoClass,
            buffer,
            bufferSize,
            sizeOut,
        ) as Int) != 0

        fun closeHandle(handle: MemorySegment) {
            if (handle.address() == 0L) return
            runCatching { closeHandleHandle.invokeWithArguments(handle) }
        }
    }
}
