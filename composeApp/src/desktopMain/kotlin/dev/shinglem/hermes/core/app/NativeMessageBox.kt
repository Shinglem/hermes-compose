package dev.shinglem.hermes.core.app

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * 原生错误对话框（Win32 `MessageBoxW`）。
 *
 * ### 为什么不用 `JOptionPane`
 *
 * `javax.swing.JOptionPane` 属于 `java.desktop`。这里**只在配置解析失败、Compose 还没
 * 起来**的场景用（`Main` 的 `showFatal`），换掉之后业务代码对 `java.desktop` 的依赖归零。
 *
 * `MessageBoxW` 是"真的正在报错"时最不该依赖任何框架的选择：不初始化 AWT/Swing、
 * 不需要事件循环、不占用 UI 线程模型，弹完就返回。
 *
 * ### 阻塞语义
 *
 * `MessageBoxW` 是模态阻塞的（用户不点就返回不了）。调用方必须满足：
 * - 已经先把错误写进 `System.err` / `startup.log`（否则 `--silent` 或自动化测试读不到原因）；
 * - `--silent`（开机自启）时**不要调**（会卡住会话）。
 *
 * 非 Windows 或 FFM 不可用时静默降级为"没有任何弹窗" —— 调用方本来就写完了日志。
 */
internal object NativeMessageBox {

    private const val MB_OK = 0x0000
    private const val MB_ICONERROR = 0x0010
    private const val MB_TOPMOST = 0x00040000
    private const val MB_SETFOREGROUND = 0x00010000

    /**
     * 弹一个只有"确定"的错误框。
     *
     * @return true 表示确实弹出来了
     */
    fun error(title: String, message: String): Boolean {
        val api = Api.instance ?: return false
        return runCatching {
            Arena.ofConfined().use { arena ->
                val titleText = arena.allocateFrom(title + "\u0000", Charsets.UTF_16LE)
                val messageText = arena.allocateFrom(message + "\u0000", Charsets.UTF_16LE)
                api.messageBox(
                    hwnd = MemorySegment.NULL,
                    text = messageText,
                    caption = titleText,
                    type = MB_OK or MB_ICONERROR or MB_TOPMOST or MB_SETFOREGROUND,
                )
            }
            true
        }.getOrDefault(false)
    }

    private class Api(private val messageBoxW: MethodHandle) {
        fun messageBox(hwnd: MemorySegment, text: MemorySegment, caption: MemorySegment, type: Int) {
            messageBoxW.invokeWithArguments(hwnd, text, caption, type)
        }

        companion object {
            val instance: Api? by lazy {
                if (!System.getProperty("os.name").orEmpty().lowercase().contains("win")) {
                    null
                } else {
                    runCatching {
                        val linker = Linker.nativeLinker()
                        // 与 WindowsJobObject / Elevation 同一个坑：`defaultLookup()` 在 Windows 上
                        // 找不到 user32 的导出，必须按库名显式查。
                        val user32 = SymbolLookup.libraryLookup("user32", Arena.global())
                        val symbol = user32.find("MessageBoxW").orElseThrow {
                            IllegalStateException("missing MessageBoxW")
                        }
                        Api(
                            linker.downcallHandle(
                                symbol,
                                FunctionDescriptor.of(
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT,
                                ),
                            ),
                        )
                    }.getOrNull()
                }
            }
        }
    }
}
