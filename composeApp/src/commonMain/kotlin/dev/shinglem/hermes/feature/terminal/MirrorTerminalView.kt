package dev.shinglem.hermes.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * agent 后台进程的只读镜像视图。
 *
 * 为什么不用 `:modules:terminal` 的 `TerminalHost`：镜像**没有 PTY**，也不需要 VT 解析
 * （契约给的是纯文本 chunk）。用 Compose 直接渲染更轻，也避免为它多开一个 shell。
 */
@Composable
fun MirrorTerminalView(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    // agent 输出是追加式的：始终贴底，和真实终端一致。
    LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF05070B)),
    ) {
        BasicText(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFD5DBE5),
                lineHeight = 18.sp,
            ),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(8.dp),
            overflow = TextOverflow.Clip,
        )
        if (text.isBlank()) {
            Text(
                text = "$title · 等待输出…",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF8892A4),
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}
