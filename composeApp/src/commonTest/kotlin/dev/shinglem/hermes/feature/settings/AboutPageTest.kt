package dev.shinglem.hermes.feature.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.shinglem.hermes.core.app.ProductInfo
import dev.shinglem.hermes.ui.theme.HermesTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 产品名的显示口径：界面上一律 [ProductInfo.DISPLAY_NAME]，
 * 而不是后端/agent 的「Hermes」。
 */
@OptIn(ExperimentalTestApi::class)
class AboutPageTest {

    @Test
    fun `关于页显示产品名`() = runComposeUiTest {
        setContent {
            HermesTheme {
                AboutPage(
                    backendVersion = "0.22.5",
                    dataDir = "C:/tmp/data",
                    logDir = "C:/tmp/logs",
                    update = UpdateState(),
                    onCheckUpdate = {},
                )
            }
        }
        onNodeWithText(ProductInfo.DISPLAY_NAME).assertIsDisplayed()
        onNodeWithText("客户端版本").assertIsDisplayed()
    }

    @Test
    fun `产品名不是裸的 Hermes`() {
        assertTrue(ProductInfo.DISPLAY_NAME.contains("Hermes"), "产品名应保留 Hermes 前缀")
        assertTrue(
            ProductInfo.DISPLAY_NAME != "Hermes",
            "标题里要显示完整名称，裸 Hermes 指的是后端 agent",
        )
    }
}
