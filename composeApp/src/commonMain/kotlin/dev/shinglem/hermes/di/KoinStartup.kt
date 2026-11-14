package dev.shinglem.hermes.di

import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.transport.GatewayClient
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.KoinAppDeclaration

/**
 * Koin 启动与关停（ADR-076）。
 *
 * `AppContainer` 仍然是**唯一装配点**（`DesktopContainer.build()`），Koin 只是在它之上
 * 建一层可查询的图 —— 这样 S5 的 Component 树可以按类型取依赖，而不必把容器逐层传参。
 *
 * 全局单例（`startKoin` 用的是 `KoinPlatform` 的全局上下文），所以：
 * - 只允许调用一次（重复调用会抛 `KoinApplicationAlreadyStartedException`）；
 * - 应用退出时用它取消注册，避免测试之间互相污染。
 */
object KoinStartup {

    private var started = false

    fun start(container: AppContainer, declaration: KoinAppDeclaration? = null) {
        if (started) return
        startKoin {
            declaration?.invoke(this)
            modules(appModule(container))
        }
        started = true
        // 解析一次网关证明图是可用的：拿不到会直接抛，崩在启动阶段比静默降级好。
        val resolved = org.koin.core.context.GlobalContext.get().get<GatewayClient>()
        Log.info("koin: started, gateway resolved=${resolved.hashCode()}")
    }

    /** 关停（退出与测试清理共用）。没启动过时是 no-op。 */
    fun stop() {
        if (!started) return
        runCatching { stopKoin() }
        started = false
    }
}
