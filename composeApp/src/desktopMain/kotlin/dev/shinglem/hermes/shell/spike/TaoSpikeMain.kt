package dev.shinglem.hermes.shell.spike

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication

/**
 * S0 · Tao 空壳 spike（`09-refactor.md` §3 S0 第 4 步 / 13 篇 §8.5）。
 *
 * **不是产品代码**，是一个可手动跑起来的探针：确认 `nucleusApplication` + `DecoratedWindow`
 * 能在本机起窗口、Compose 能画、`BasicTextField` 能收中文、AWT 工具类能共存。
 *
 * 跑法（临时任务，S6 会删掉）：
 * ```
 * ./gradlew :composeApp:taoSpike
 * ```
 *
 * 手工判据（每项记 通过 / 失败 进 `09-refactor.md` §3 S0 第 4 步）：
 *   1. 窗口起得来、关得掉，无 AWT / native 崩溃
 *   2. 微软拼音在输入框里能打中文（候选窗跟随光标、上屏、退格、撤销）
 *   3. `enableSingleInstance = false` 生效：再起一个进程不会把自己转发出去
 *   4. AWT 工具类可用：剪贴板读写、`Desktop.browse`、`Robot` 不抛
 *   5. 暗色主题下自绘标题栏可读
 */
fun main() {
    // 关键：`enableSingleInstance = false` 让位给自研 CreateMutexW（ADR-062 / 13 篇 §8.2）。
    // 默认值在 dev 运行下本来就是 false；产品代码也照此显式写。
    nucleusApplication(
        enableSingleInstance = false,
        initializeFileKit = false,
    ) {
        val state = rememberWindowState()
        DecoratedWindow(
            onCloseRequest = ::exitApplication,
            state = state,
            title = "Tao S0 Spike",
        ) {
            MaterialTheme {
                TaoSpikeContent()
            }
        }
    }
}

@Composable
private fun TaoSpikeContent() {
    var text by remember { mutableStateOf("") }
    val probe = remember { runAwtProbe() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("S0 · Tao 空壳探针", style = MaterialTheme.typography.titleLarge)
        Text("1) 在下面输入框用微软拼音打「你好世界」，确认候选窗跟随、上屏、退格、撤销都正常。")
        Text("2) AWT 剪贴板：$probe")
        Text("3) 屏幕几何由 :composeApp:taoAwtProbe 单独验（放这里会白屏）")
        Box(
            Modifier.fillMaxWidth().padding(vertical = 12.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            )
        }
        Text("当前输入：$text")
    }
}

/**
 * 在 Tao 进程里跑一次 AWT 工具类调用 —— 这是 13 篇 §8.6「保留剪贴板 / 打开链接」的前提。
 */
private fun runAwtProbe(): String = runCatching {
    val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
    clipboard.setContents(java.awt.datatransfer.StringSelection("hermes-tao-probe"), null)
    val back = clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as String
    "clipboard ok=$back"
}.getOrElse { "clipboard FAILED: ${it::class.simpleName}: ${it.message}" }
