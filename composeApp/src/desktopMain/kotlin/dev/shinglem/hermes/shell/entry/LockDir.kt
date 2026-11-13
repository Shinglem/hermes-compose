package dev.shinglem.hermes.shell.entry

import dev.shinglem.hermes.core.log.Log
import java.io.File

/**
 * 单实例锁文件目录（ADR-066）。
 *
 * 为什么不用 Nucleus 的默认值（`java.io.tmpdir`）：
 * - **权限相关**。`TEMP` 在普通会话是 `C:\Users\<u>\AppData\Local\Temp`，
 *   在提权会话可能是 `C:\Windows\Temp` —— 默认值会让两个权限级别落在**不同目录**，
 *   于是互相看不见、允许双开。这正是我们当初把内核对象从 `Global\` 换成 `Local\` 要解决的问题，
 *   换到 Nucleus 后必须用**显式、与权限无关**的目录补回来。
 * - **可预测**。锁文件放在 `<home>/locks` 下，用户能在安装目录里看到它，排障时不用猜。
 *
 * 解析顺序（第一个可写者胜）：
 * 1. `<home>/locks` —— 绿色版的正常路径（home 由 jar 位置反推，与权限无关）
 * 2. `%LOCALAPPDATA%\HermesCompose\locks` —— 装在 `Program Files` 之类只读目录时的兜底
 * 3. 都不行 → 返回 null，**调用方必须明确警告**，不能静默退回 tmpdir
 *
 * **第 3 条很重要**：Nucleus 的 `SingleInstanceManager.retryLock` 在拿不到锁时
 * `fail-open` 返回 `true`（源码：`errorLog { "Cannot acquire lock after retry" }; true`），
 * 也就是**静默允许双开**。我们不能让它悄悄退化成那样 —— 所以宁愿自己先判可写性。
 */
internal object LockDir {

    /**
     * 选出锁目录；返回 null 表示两处都不可写。
     *
     * 会在目录里做一次真实的写入-删除探测，而不是只看 `canWrite()`：
     * 后者在 NTFS ACL 与只读卷上不可靠。
     */
    fun resolve(home: File): File? {
        val candidates = buildList {
            add(File(home, "locks"))
            localAppDataLockDir()?.let { add(it) }
        }
        for (dir in candidates) {
            if (probeWritable(dir)) {
                if (dir.parentFile != home) {
                    Log.warn("单实例锁目录退回用户目录：${dir.absolutePath}（${home.absolutePath} 不可写）")
                }
                return dir
            }
        }
        Log.error(
            "单实例锁目录不可写，将无法阻止双开：" +
                candidates.joinToString(", ") { it.absolutePath },
        )
        return null
    }

    private fun localAppDataLockDir(): File? {
        val root = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
            ?: return null
        return File(root, "HermesCompose/locks")
    }

    /** 建目录 + 写一个临时文件 + 删掉。任何一步失败都算不可写。 */
    private fun probeWritable(dir: File): Boolean = runCatching {
        if (!dir.exists() && !dir.mkdirs()) return false
        if (!dir.isDirectory) return false
        val probe = File(dir, ".probe-${System.nanoTime()}")
        probe.writeText("")
        probe.delete()
        true
    }.getOrDefault(false)
}
