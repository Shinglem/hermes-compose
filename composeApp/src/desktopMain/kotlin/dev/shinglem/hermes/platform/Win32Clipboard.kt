package dev.shinglem.hermes.platform

import dev.shinglem.hermes.core.log.Log
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Win32 系统剪贴板（FFM）。
 *
 * ### 为什么不用 AWT
 *
 * `java.awt.Toolkit.systemClipboard` 属于 `java.desktop` 的桌面集成层，而本项目 S6 之后
 * 的窗口模型是 Nucleus Tao 的、不是 AWT 的（见 ADR-072/073）。剪贴板是本进程最后一个
 * 还需要 AWT 的能力，换成直接调 Win32 之后 `java.desktop` 在业务代码里的引用归零。
 *
 * Compose 的 `ClipboardManager` **不能**替代：它的 desktop 实现
 * （`AwtClipboardManager`）底层就是 `java.awt.datatransfer.Clipboard`，换过去只是换个
 * 入口；而且它只支持文本，没有图片（`hasImage` / `saveImageToTemp` 无对应能力）。
 *
 * ### 线程约束（Win32 硬要求）
 *
 * `OpenClipboard` → 读写 → `CloseClipboard` 必须在**同一个线程**完成，剪贴板是
 * 每线程打开的。这里的每个公开方法都自成一对 Open/Close，调用方不需要关心线程 ——
 * 但**不要**在另一个线程持有句柄期间调用。
 *
 * 实测（`clipboardProbe`）：在 Tao 的 `main` 线程上 `OpenClipboard` 一次成功，
 * `lastError=0`，没有与任何东西争用。
 *
 * ### 与 AWT 的互操作性（也实测过）
 *
 * Win32 写 → AWT 读得到，AWT 写 → Win32 读得到，是同一个系统剪贴板。
 * 所以换成这个实现不影响"从别的应用复制"这条主要路径。
 */
internal class Win32Clipboard private constructor(
    private val openClipboard: MethodHandle,
    private val closeClipboard: MethodHandle,
    private val getClipboardData: MethodHandle,
    private val setClipboardData: MethodHandle,
    private val emptyClipboard: MethodHandle,
    private val isClipboardFormatAvailable: MethodHandle,
    private val globalLock: MethodHandle,
    private val globalUnlock: MethodHandle,
    private val globalAlloc: MethodHandle,
    private val globalFree: MethodHandle,
    private val globalSize: MethodHandle,
) {

    /** 读写文本。失败返回 null / false —— 剪贴板是不可靠的外部资源，不该抛异常。 */
    fun readText(): String? = withOpen {
        val data = getClipboardData.invokeWithArguments(CF_UNICODETEXT) as MemorySegment
        if (data.address() == 0L) return@withOpen null
        val ptr = globalLock.invokeWithArguments(data) as MemorySegment
        if (ptr.address() == 0L) return@withOpen null
        try {
            val size = globalSize.invokeWithArguments(data) as Long
            if (size <= 0L) return@withOpen null
            val view = ptr.reinterpret(size)
            val bytes = ByteArray(size.toInt())
            for (i in bytes.indices) bytes[i] = view.get(ValueLayout.JAVA_BYTE, i.toLong())
            // Win32 的 CF_UNICODETEXT 是 UTF-16LE，尾部带一个 NUL。
            String(bytes, Charsets.UTF_16LE).trimEnd('\u0000')
        } finally {
            globalUnlock.invokeWithArguments(data)
        }
    }

    fun writeText(value: String): Boolean = withOpen {
        emptyClipboard.invokeWithArguments()
        val bytes = (value + "\u0000").toByteArray(Charsets.UTF_16LE)
        val handle = globalAlloc.invokeWithArguments(GMEM_MOVEABLE, bytes.size.toLong()) as MemorySegment
        if (handle.address() == 0L) return@withOpen false
        var ownershipTransferred = false
        try {
            val ptr = globalLock.invokeWithArguments(handle) as MemorySegment
            if (ptr.address() == 0L) return@withOpen false
            val view = ptr.reinterpret(bytes.size.toLong())
            for (i in bytes.indices) view.set(ValueLayout.JAVA_BYTE, i.toLong(), bytes[i])
            globalUnlock.invokeWithArguments(handle)
            val ok = (setClipboardData.invokeWithArguments(CF_UNICODETEXT, handle) as MemorySegment).address() != 0L
            // SetClipboardData 成功之后内存所有权归系统，不能再 GlobalFree。
            ownershipTransferred = ok
            ok
        } finally {
            if (!ownershipTransferred && handle.address() != 0L) {
                globalFree.invokeWithArguments(handle)
            }
        }
    } ?: false

    /** 剪贴板里有没有位图。 */
    fun hasImage(): Boolean = withOpen {
        formatAvailable(CF_DIBV5) || formatAvailable(CF_DIB)
    } ?: false

    /** 读剪贴板位图并编码成 PNG 字节；没有位图或不支持的形态返回 null。 */
    fun readImageAsPng(): ByteArray? = withOpen {
        val format = when {
            formatAvailable(CF_DIBV5) -> CF_DIBV5
            formatAvailable(CF_DIB) -> CF_DIB
            else -> return@withOpen null
        }
        val data = getClipboardData.invokeWithArguments(format) as MemorySegment
        if (data.address() == 0L) return@withOpen null
        val ptr = globalLock.invokeWithArguments(data) as MemorySegment
        if (ptr.address() == 0L) return@withOpen null
        try {
            val size = globalSize.invokeWithArguments(data) as Long
            if (size <= 0L || size > MAX_IMAGE_BYTES) return@withOpen null
            val view = ptr.reinterpret(size)
            val bytes = ByteArray(size.toInt())
            for (i in bytes.indices) bytes[i] = view.get(ValueLayout.JAVA_BYTE, i.toLong())
            DibImage.toPng(bytes)
        } finally {
            globalUnlock.invokeWithArguments(data)
        }
    }

    private fun formatAvailable(format: Int): Boolean =
        (isClipboardFormatAvailable.invokeWithArguments(format) as Int) != 0

    /**
     * 打开 → 执行 → 关闭。打不开返回 null（另一个进程正占着剪贴板是这个 API 的常态，
     * 不该让粘贴功能报错）。
     */
    private inline fun <T> withOpen(block: () -> T): T? {
        val opened = (openClipboard.invokeWithArguments(MemorySegment.NULL) as Int) != 0
        if (!opened) return null
        return try {
            block()
        } catch (failure: Throwable) {
            Log.warn("clipboard: operation failed: ${failure.message}")
            null
        } finally {
            runCatching { closeClipboard.invokeWithArguments() }
        }
    }

    companion object {
        const val CF_DIB = 8
        const val CF_UNICODETEXT = 13
        const val CF_DIBV5 = 17

        private const val GMEM_MOVEABLE = 0x0002

        /**
         * 位图上限：4K 屏的 32 位截图约 33 MB，留一倍余量。
         * 超过这个体积的多半不是截图，读它只会让 UI 卡住。
         */
        private const val MAX_IMAGE_BYTES = 64L * 1024 * 1024

        /** 非 Windows 或 FFM 不可用时为 null，调用方退化成"什么都没有"。 */
        val instance: Win32Clipboard? by lazy {
            if (!System.getProperty("os.name").orEmpty().lowercase().contains("win")) {
                null
            } else {
                runCatching { create() }
                    .onFailure { Log.warn("clipboard: Win32 unavailable: ${it.message}") }
                    .getOrNull()
            }
        }

        private fun create(): Win32Clipboard {
            val linker = Linker.nativeLinker()
            // 与 WindowsJobObject / Elevation 同一个坑：`defaultLookup()` 在 Windows 上
            // 找不到 kernel32 的导出，必须按库名显式查。
            val kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global())
            val user32 = SymbolLookup.libraryLookup("user32", Arena.global())

            fun find(lib: SymbolLookup, name: String): MethodHandle {
                val symbol = lib.find(name).orElseThrow {
                    IllegalStateException("missing native symbol: $name")
                }
                return linker.downcallHandle(symbol, descriptorFor(name))
            }

            return Win32Clipboard(
                openClipboard = find(user32, "OpenClipboard"),
                closeClipboard = find(user32, "CloseClipboard"),
                getClipboardData = find(user32, "GetClipboardData"),
                setClipboardData = find(user32, "SetClipboardData"),
                emptyClipboard = find(user32, "EmptyClipboard"),
                isClipboardFormatAvailable = find(user32, "IsClipboardFormatAvailable"),
                globalLock = find(kernel32, "GlobalLock"),
                globalUnlock = find(kernel32, "GlobalUnlock"),
                globalAlloc = find(kernel32, "GlobalAlloc"),
                globalFree = find(kernel32, "GlobalFree"),
                globalSize = find(kernel32, "GlobalSize"),
            )
        }

        private fun descriptorFor(name: String): FunctionDescriptor = when (name) {
            "OpenClipboard" -> FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
            "CloseClipboard", "EmptyClipboard" -> FunctionDescriptor.of(ValueLayout.JAVA_INT)
            "GetClipboardData" -> FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
            "SetClipboardData" ->
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
            "IsClipboardFormatAvailable" ->
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
            "GlobalLock" -> FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            "GlobalUnlock" -> FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
            "GlobalAlloc" ->
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)
            "GlobalFree" -> FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            "GlobalSize" -> FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
            else -> error("no descriptor for $name")
        }
    }
}
