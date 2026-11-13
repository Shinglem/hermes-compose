package dev.shinglem.hermes.shell.entry

import dev.nucleusframework.core.runtime.SingleInstanceManager
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files

/**
 * 在配置解析之前**探测**本进程是不是第二个实例（S6 实测优化）。
 *
 * ### 为什么需要这一步
 *
 * `nucleusApplication` 里的 `acquireSingleInstanceLock` 要等 Compose 起来才执行。原来的顺序是
 * 「解析配置 → 起后端 → 自启对账 → 进 `nucleusApplication`」，所以第二实例会把整套启动流程
 * 完整跑一遍才被判定退出 —— 实测从进程启动到写 `autostart` 日志就 ~2s，用户感知是
 * 「点了图标要等好几秒，然后什么都没发生」。
 *
 * ### 为什么不直接提前占锁
 *
 * 直接调 `SingleInstanceManager.isSingleInstance(...)` 看似可以，但它那两个回调**不是装饰**：
 * 首实例拿到锁时，`onLockAcquired` 会用 `onRestoreRequest` 去 `watchForRestoreRequests()` ——
 * 那是**唯一**能让「第二实例请求 → 把窗口拉出来」生效的地方。而 Nucleus 配套的两个动作
 * （`SingleInstanceRestoreBus.fire()` / `ObserveSingleInstanceRestore`）都是 `internal`，
 * 我们抄不全。S6 实测踩过：传空 Lambda 提前判定，第二实例正常退出，但最小化的窗口再也不动了。
 *
 * 所以这里**只读探测**（`tryLock` 成功后立刻释放、绝不留给后续），判定结果：
 * - [Verdict.FIRST]：没人持锁 → 本进程是首实例 → 走 Nucleus 原路径（回调由它自己装）；
 * - [Verdict.SECOND]：已有实例持锁 → 自己写唤醒请求后退出；
 * - [Verdict.UNKNOWN]：探测本身出错 → 一律交回 Nucleus，宁可慢也不出错。
 */
internal object SingleInstanceProbe {

    enum class Verdict {
        FIRST,
        SECOND,
        UNKNOWN,
    }

    /**
     * 试锁一次。**成功也要立刻释放**：真正的锁必须由 Nucleus 在 `nucleusApplication` 里占，
     * 这里多占一秒都会让紧随其后的 `acquireSingleInstanceLock` 把自己的进程判成第二实例。
     */
    fun probe(configuration: SingleInstanceManager.Configuration): Verdict {
        val file = configuration.lockFilePath.toFile()
        runCatching { file.parentFile?.mkdirs() }
        return try {
            // 与 Nucleus 一样用 rw 打开（会顺手创建文件）。打开本身不加锁，不影响已有实例。
            RandomAccessFile(file, "rw").use { raf ->
                val lock = raf.channel.tryLock()
                if (lock == null) {
                    Verdict.SECOND
                } else {
                    lock.release()
                    Verdict.FIRST
                }
            }
        } catch (overlapping: OverlappingFileLockException) {
            // 同一 JVM 内已持有同一文件的锁 —— 对独立进程不可能，出现了就按"已有实例"处理，
            // 反正退出是安全方向（真的要双开，用户会看到窗口没出来再点一次，Nucleus 仍会兜底）。
            Verdict.SECOND
        } catch (io: IOException) {
            Verdict.UNKNOWN
        } catch (security: SecurityException) {
            Verdict.UNKNOWN
        }
    }

    /**
     * 让已经在跑的实例把窗口拉出来。复刻 Nucleus `sendRestoreRequest` 的「无回调」分支：
     * 删掉旧文件再建一个空文件 —— 对方是用 `WatchService` 监听该目录的 CREATE 事件，
     * 文件名与 `Configuration.restoreRequestFileName` 一致即会触发
     * `onRestoreRequest` → `SingleInstanceRestoreBus.fire()` → 窗口显示。
     *
     * 失败不抛：唤醒失败是**已确认接受**的降级（见 ADR-066），这个进程接下来就退出了。
     */
    fun requestRestore(configuration: SingleInstanceManager.Configuration): Boolean = runCatching {
        val path = configuration.restoreRequestFilePath
        Files.deleteIfExists(path)
        Files.createFile(path)
        true
    }.getOrDefault(false)
}
