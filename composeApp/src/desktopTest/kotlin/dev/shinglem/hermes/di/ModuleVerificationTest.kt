package dev.shinglem.hermes.di

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Koin 图语义校验（ADR-076）。
 *
 * **为什么不直接测 `appModule(container)`**：它的输入要真实 `AppContainer`
 * （`GatewayClient` 会开 WebSocket、`BackendProvider` 会起子进程）。为一组静态断言
 * 去启动真实网络栈，失败原因会被网络噪声盖住。
 *
 * 所以这里分两层：
 * 1. **语义**（本文件的测试）：`single` 是否真的单例、重复注册是否报错 —— 用轻量替身验；
 * 2. **装配**（运行期）：`KoinStartup.start` 解析失败会**直接崩在启动阶段**，
 *    比静默降级好；真机启动验证覆盖这一点。
 *
 * 另有一条静态校验（`appModule` 的注册数），防止有人删注册行后
 * 某个 `get<T>()` 在运行时才崩。
 */
class ModuleVerificationTest {

    @Test
    fun `single 在同一图里返回同一实例`() {
        val app = koinApplication {
            modules(
                module {
                    single { StringBuilder("shared") }
                },
            )
        }
        try {
            assertSame(
                app.koin.get<StringBuilder>(),
                app.koin.get<StringBuilder>(),
                "single 必须是单例 —— gateway 被重造会多开一条 WebSocket",
            )
        } finally {
            app.close()
        }
    }

    @Test
    fun `重复注册同一类型是静默覆盖而不是报错`() {
        // 实测（Koin 4.2.2）：重复 `single` 不抛异常，**后者静默覆盖前者**。
        // 这比抛异常更危险 —— 所以 `Modules.kt` 的注册纪律只能靠静态检查
        // （`重复注册检测` 那条测试）与 code review 保证，不能指望 Koin 拦住。
        val app = koinApplication {
            modules(
                module {
                    single { StringBuilder("a") }
                    single { StringBuilder("b") }
                },
            )
        }
        try {
            assertEquals(
                "b",
                app.koin.get<StringBuilder>().toString(),
                "后注册的定义覆盖了先注册的 —— 这正是必须避免重复注册的原因",
            )
        } finally {
            app.close()
        }
    }

    @Test
    fun `appModule 里没有重复注册同一容器的字段`() {
        val source = java.io.File("src/commonMain/kotlin/dev/shinglem/hermes/di/Modules.kt")
        check(source.isFile) { "找不到 Modules.kt，测试工作目录不对：${source.absolutePath}" }
        val fields = source.readLines()
            .map { it.trim() }
            // 排除 KDoc / 行注释：文档里会出现示例代码，不能算注册。
            .filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }
            .mapNotNull { Regex("""single(?:\s*<[^>]+>)?\s*\{\s*container\.(\w+)\s*\}""").find(it)?.groupValues?.get(1) }
        assertEquals(
            fields.size,
            fields.toSet().size,
            "同一字段被注册两次会静默覆盖（Koin 4.x 不报错）：$fields",
        )
    }

    @Test
    fun `appModule 的注册数与容器字段一一对应`() {
        val source = java.io.File("src/commonMain/kotlin/dev/shinglem/hermes/di/Modules.kt")
        check(source.isFile) { "找不到 Modules.kt，测试工作目录不对：${source.absolutePath}" }
        val registrations = source.readLines()
            .map { it.trim() }
            .filter { it.startsWith("single") }
        // 26 = AppContainer 的注册项数（见 Modules.kt）。字段增删时同步更新。
        assertEquals(
            26,
            registrations.size,
            "注册数变了就要同步更新这里，并确认没有漏注册新字段：$registrations",
        )
    }
}