package dev.shinglem.hermes.shell.entry

import dev.nucleusframework.core.runtime.SingleInstanceManager
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
/**
 * 把 `<home>` 映射成 Nucleus 的单实例配置（ADR-066）。
 *
 * **必须在第一次 `isSingleInstance()` 之前调用** —— `SingleInstanceManager.configuration`
 * 的 setter 里有 `check(fileChannel == null)`，第一次判定之后再改会直接抛
 * `Configuration can be changed only before first call to isSingleInstance()!`。
 * 所以在 `DesktopEntry.main` 里它是**第一件事**，早于任何窗口、任何 Koin、任何后端启动。
 *
 * 两处自定义：
 * 1. `lockFilesDir` —— 用 [LockDir] 选出的目录。**不能吃默认值 `java.io.tmpdir`**：
 *    `TEMP` 在提权/非提权会话可能不同，会让两个权限级别互相看不见（详见 [LockDir] 的注释）。
 * 2. `lockIdentifier` —— 按 `<home>` 的 canonicalPath 哈希。两份便携包各有各的标识，
 *    互不拦截；同一份安装无论以什么权限运行都是同一个标识。
 *
 * 哈希算法与已删除的自研实现保持一致（SHA-256 前 4 字节 → 8 个十六进制位）：
 * 这样从 v0.5.x 升级上来的用户看到的是同一串后缀，日志与支持记录能对上。
 */
internal object SingleInstanceConfig {

    /** 锁目录不可写时的结果：调用方必须明确警告，不能静默继续（Nucleus 会 fail-open 双开）。 */
    sealed interface Outcome {
        data class Ready(val configuration: SingleInstanceManager.Configuration) : Outcome

        data class Unavailable(val reason: String) : Outcome
    }

    fun prepare(home: File): Outcome {
        val lockDir = LockDir.resolve(home)
            ?: return Outcome.Unavailable(
                "无法创建单实例锁目录（<home>/locks 与 %LOCALAPPDATA% 均不可写）",
            )
        val identifier = identifierFor(home)
        return Outcome.Ready(
            SingleInstanceManager.Configuration(
                lockFilesDir = lockDir.toPath(),
                lockIdentifier = identifier,
            ),
        )
    }

    /**
     * `<home>` → 稳定的锁标识。
     *
     * 必须用 `canonicalPath`：大小写、`PROGRA~1` 短路径、符号链接都会算成不同名字，
     * 而它们指向同一份安装 —— 用 absolutePath 会让同一份包"自己和自己抢不到锁"。
     */
    fun identifierFor(home: File): String {
        val canonical = runCatching { home.canonicalPath }.getOrDefault(home.absolutePath)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        val hash = digest.take(4).joinToString("") { "%02x".format(it) }
        return "$PREFIX-$hash"
    }

    /**
     * 标识前缀。**不要改名**：`HermesCompose` 是 v0.5.x 自研锁用的前缀，
     * 保持一致才能让升级用户的日志/支持记录对得上（虽然换了机制，标识没换）。
     */
    const val PREFIX = "HermesCompose"

    /**
     * **只读**探测：是否已有别的实例持有这把锁（不改变"判定交给 Nucleus"的原则）。
     *
     * ### 为什么需要这个探测
     *
     * 真正的单实例判定在 `nucleusApplication` 内部（`acquireSingleInstanceLock`），
     * 而**提权重启**必须发生在它之前（要先决定"这一进程要不要 runas 后退出"）。
     * 没有探测时，只要已有一份在跑，每次双击都会先吃一个 UAC 弹窗才被判为第二实例 ——
     * 旧实现是先 `isRunning()` 再提权，顺序是对的。
     *
     * ### 为什么探测结果**只用来决定提权**，不用来提前退出
     *
     * 提前退出会绕过 Nucleus 的 `onRestoreRequest` —— 那是唯一能把已有窗口拉到前台的路径
     * （ADR-066 踩过）。所以探测为"已占用"时：**跳过提权**、照常往下走，
     * 让 Nucleus 判第二实例并发出 restore 请求。
     *
     * 实现上就是试 `tryLock`：拿得到 ⇒ 没人持有（立刻释放，交给 Nucleus 正式拿）；
     * 拿不到（返回 null 或 `OverlappingFileLockException`）⇒ 已有实例。
     * 任何异常都按"未占用"处理 —— 探测失败不该阻止应用启动。
     */
    fun isHeldElsewhere(configuration: SingleInstanceManager.Configuration): Boolean = runCatching {
        FileChannel.open(
            configuration.lockFilePath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val lock = channel.tryLock() ?: return@use true
            runCatching { lock.release() }
            false
        }
    }.getOrDefault(false)
}
