package dev.shinglem.hermes.shell.entry

import dev.nucleusframework.core.runtime.SingleInstanceManager
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S6 · 配置解析之前的第二实例探测。
 *
 * 起因（S6 实测）：第二实例原来要等 `nucleusApplication` 才判定，把「解析配置 → 起后端 →
 * 自启对账」整套跑完才退出，用户感知「点了要等好几秒，然后什么都没发生」。提前探测把它压到
 * **0.07s**（2026-11-13 实测：`start` → `already running; exited early` 跨 73ms）。
 *
 * 三条判据：
 * 1. 没人持锁 ⇒ [SingleInstanceProbe.Verdict.FIRST]（本进程继续启动）；
 * 2. 有人持锁 ⇒ [SingleInstanceProbe.Verdict.SECOND]（本进程写唤醒请求后退出）；
 * 3. **探测成功后必须已经把锁放开** —— 否则紧随其后的 Nucleus 判定会把本进程当成第二实例，
 *    结果是"永远开不起来"（这条漏了最难查，因为两个行为单独看都正常）。
 */
class SingleInstanceProbeTest {

    private val tempDir = createTempDirectory("hermes-probe-test")

    @AfterTest
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    private fun configuration() = SingleInstanceManager.Configuration(
        lockFilesDir = tempDir,
        lockIdentifier = "ProbeTest",
    )

    @Test
    fun `无人持锁时判为首实例`() {
        assertEquals(
            SingleInstanceProbe.Verdict.FIRST,
            SingleInstanceProbe.probe(configuration()),
        )
    }

    /**
     * 最关键的一条：`probe` 报 FIRST 之后，锁必须是**可再次取得**的。
     *
     * 这里用同一套 `RandomAccessFile.tryLock()` 复现 Nucleus 的行为 —— 它一会就要做同样的事。
     */
    @Test
    fun `探测成功后锁已释放 后续仍能取得`() {
        val config = configuration()
        assertEquals(SingleInstanceProbe.Verdict.FIRST, SingleInstanceProbe.probe(config))

        RandomAccessFile(config.lockFilePath.toFile(), "rw").use { raf ->
            val lock = raf.channel.tryLock()
            assertTrue(lock != null, "探测把锁留下了 —— Nucleus 稍后会判成本进程是第二实例")
            lock.release()
        }
    }

    @Test
    fun `已有人持锁时判为第二实例`() {
        val config = configuration()
        RandomAccessFile(config.lockFilePath.toFile(), "rw").use { raf ->
            val held = raf.channel.tryLock()
            try {
                assertEquals(
                    SingleInstanceProbe.Verdict.SECOND,
                    SingleInstanceProbe.probe(config),
                )
            } finally {
                held?.release()
            }
        }
    }

    /** 探测完把锁放掉后，同一进程里再探还是 FIRST（不会因为"探过"就自我判定为第二实例）。 */
    @Test
    fun `连续探测结果稳定`() {
        val config = configuration()
        repeat(3) {
            assertEquals(SingleInstanceProbe.Verdict.FIRST, SingleInstanceProbe.probe(config))
        }
    }

    @Test
    fun `锁目录不存在时探测会建目录`() {
        val nested = tempDir.resolve("a/b/c")
        val config = SingleInstanceManager.Configuration(
            lockFilesDir = nested,
            lockIdentifier = "ProbeTest",
        )
        assertEquals(SingleInstanceProbe.Verdict.FIRST, SingleInstanceProbe.probe(config))
        assertTrue(Files.isDirectory(nested), "探测应自行建出锁目录，实际不存在：$nested")
    }

    // ── 唤醒请求 ────────────────────────────────────────────────────────────────

    /**
     * `requestRestore` 复刻 Nucleus `sendRestoreRequest` 的「无回调」分支：删旧建新。
     *
     * 首实例是用 `WatchService` 监听**目录 CREATE 事件**的，所以已经存在同名文件时
     * 必须先删 —— 只 `createFile` 会抛 `FileAlreadyExistsException`，唤醒静默失效。
     */
    @Test
    fun `唤醒请求会建出文件`() {
        val config = configuration()
        assertTrue(SingleInstanceProbe.requestRestore(config))
        assertTrue(Files.exists(config.restoreRequestFilePath))
    }

    @Test
    fun `已有残留的唤醒请求时会先删再造`() {
        val config = configuration()
        Files.createFile(config.restoreRequestFilePath)
        assertTrue(SingleInstanceProbe.requestRestore(config), "残留文件让唤醒抛异常了")
        assertTrue(Files.exists(config.restoreRequestFilePath))
    }

    @Test
    fun `唤醒请求文件名与 Nucleus 期望一致`() {
        // 首实例的 watcher 靠这个名字过滤事件（`onFileCreated` 里 areEqual 判断），
        // 自己拼字符串很容易写成 ".restore" / "restore-request" 这类对不上的名字。
        val config = configuration()
        SingleInstanceProbe.requestRestore(config)
        assertEquals(
            config.restoreRequestFileName,
            config.restoreRequestFilePath.fileName.toString(),
        )
        assertTrue(Files.exists(config.restoreRequestFilePath))
    }
}
