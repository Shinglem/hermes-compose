package dev.shinglem.hermes.di

import kotlinx.coroutines.CoroutineScope
import org.koin.dsl.module

/**
 * 应用依赖图（ADR-076）。
 *
 * ### 为什么是"包一层"而不是重写
 *
 * `AppContainer`（33 个字段）是 v0.5.x 以来的手写 DI，装配顺序有明确语义
 * （`DesktopContainer.build()` 里 `OptionsWatcher` → `backend` → `gateway` → 各 Repository），
 * 而 S5 需要的只是"Component 能从图里取依赖"。
 *
 * 所以这里**不重排装配**：`AppContainer` 原样构造，再把它的每个字段注册成单例。
 * 这样做的好处是改动可逆 —— 哪一步出问题，把 `startKoin` 去掉就回到原状。
 *
 * ### 为什么不重复注册
 *
 * `AppContainer.x` 的静态类型已经是具体类型（例如 `GatewayClient`），所以
 * `single { container.gateway }` 就同时满足"按 `GatewayClient` 取"。
 * 额外写 `single<GatewayClient> { ... }` 会得到**两个定义**（Koin 默认在
 * `allowOverride = false` 时直接抛 `DefinitionOverrideException`）。
 *
 * 同一个实例被 ViewModel / Component / UI 同时持有（例如 `gateway` 至少有 5 个持有者），
 * 用 `single` 保证图里只有一份 —— 重造会得到**多个 WebSocket 连接**。
 */
fun appModule(container: AppContainer) = module {

    // ---- 传输与基础设施 ----
    single { container.config }
    single { container.logger }
    single { container.preferences }
    single { container.gateway }
    single { container.restClient }
    single { container.backend }
    single { container.settingsStore }
    single<CoroutineScope> { container.scope }

    // ---- Repository（每个 feature 一个，见 AGENTS.md §6）----
    single { container.sessionRepository }
    single { container.profileRepository }
    single { container.chatRepository }
    single { container.composerRepository }
    single { container.modelRepository }
    single { container.capabilityRepository }
    single { container.providerRepository }
    single { container.customEndpointRepository }
    single { container.profilesAdminRepository }
    single { container.modelAdminRepository }
    single { container.configRepository }
    single { container.maintenanceRepository }
    single { container.browserArgsReconciler }

    // ---- 终端（宿主模块，非 Component 化，按原样注入）----
    single { container.terminalViewModel }
    single { container.terminalRepository }

    // ---- 平台能力 ----
    single { container.restarter }
    single { container.officialDesktop }

    // ---- 容器自身：少数地方需要 `hermesHome` / 自启状态这类"容器级"字段 ----
    single { container }
}