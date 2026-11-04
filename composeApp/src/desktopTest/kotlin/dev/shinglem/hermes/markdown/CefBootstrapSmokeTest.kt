package dev.shinglem.hermes.markdown

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * CEF 冒烟测试：验证「给定一个任意目录里的 CEF native，能把 CefApp 推到 INITIALIZED」。
 *
 * 默认**跳过**（机器上没有 CEF bundle 时 300 MB 不可能随测试下载）。要真跑：
 *
 * ```bash
 * # 先 gradlew :composeApp:extractCefBundle 把 bundle 解到 composeApp/build/cef/bin
 * MARKDOWN_CEF_DIR=composeApp/build/cef/bin ./gradlew :composeApp:desktopTest --tests '*CefBootstrapSmokeTest*'
 * ```
 *
 * 覆盖的就是那条最难在 CI 上暴露的链：CEF 目录不在 `java.home` 下、`java.library.path`
 * 里没有它 —— 靠 `SystemBootstrap.setLoader` 按绝对路径加载，且 `createClient()` 触发
 * `N_Initialize`。回归时最先坏的就是这里。
 */
class CefBootstrapSmokeTest {

    @Test
    fun `cef reaches initialized from an arbitrary native dir`() {
        val dir = (System.getenv("MARKDOWN_CEF_DIR") ?: System.getProperty("markdown.cef.dir"))
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
        if (dir == null || !File(dir, "jcef_helper.exe").isFile) {
            println("跳过 CEF 冒烟：未指定 MARKDOWN_CEF_DIR，或目录里没有 jcef_helper.exe")
            return
        }

        // CefBootstrap 是 markdown-one 的 internal object，只能反射（composeApp 不是它的友元）。
        val bootstrap = Class.forName("io.github.shinglem.markdown.web.CefBootstrap")
        val app = bootstrap.getMethod("app").invoke(bootstrap.getField("INSTANCE").get(null))
        assertNotNull(app)

        val state = Class.forName("org.cef.CefApp").getMethod("getState").invoke(null).toString()
        assertEquals("INITIALIZED", state)

        // 收尾：CEF 的线程不是 daemon，不 dispose 会让测试 worker 退不出去。
        runCatching { app.javaClass.getMethod("dispose").invoke(app) }
    }
}
