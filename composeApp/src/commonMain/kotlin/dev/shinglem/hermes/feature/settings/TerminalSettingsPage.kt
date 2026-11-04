package dev.shinglem.hermes.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.feature.terminal.TerminalSettings

/**
 * 设置中心 → 终端：字体 / 字号 / 滚动行数 / 配色。
 *
 * 全部**即时落盘、即时生效**（没有「保存」按钮）：每次改动都写 `config/state.json`，
 * 已展开的终端会按新字体 / 字号重新测量，并按需回传 `terminal.resize`。
 * 只有「滚动回看行数」受缓冲区创建时机限制，对之后新建的标签才完整生效（ADR-042）。
 */
@Composable
fun TerminalSettingsPage(
    settings: TerminalSettings,
    onChange: ((TerminalSettings) -> TerminalSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        Text("终端", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "改动即时生效并已保存，不需要点保存。字体与字号对已打开的标签同样生效；" +
                "「滚动回看行数」只对之后新建的标签完整生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        SectionTitle("字体")
        OutlinedTextField(
            value = settings.fontFamily.orEmpty(),
            onValueChange = { value ->
                onChange { it.copy(fontFamily = value.ifBlank { null }) }
            },
            label = { Text("字体族（留空用自带的等宽 Nerd Font）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        FONT_CANDIDATES.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { family ->
                    FilterChip(
                        selected = settings.fontFamily == family,
                        onClick = { onChange { it.copy(fontFamily = family) } },
                        label = { Text(family) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("字号")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FONT_SIZES.forEach { size ->
                FilterChip(
                    selected = settings.fontSize == size,
                    onClick = { onChange { it.copy(fontSize = size) } },
                    label = { Text(size.toInt().toString()) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("滚动回看行数")
        OutlinedTextField(
            value = settings.scrollbackLines.toString(),
            onValueChange = { value ->
                value.toIntOrNull()?.let { parsed -> onChange { it.copy(scrollbackLines = parsed) } }
            },
            label = { Text("${TerminalSettings.MIN_SCROLLBACK}–${TerminalSettings.MAX_SCROLLBACK}") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        SectionTitle("配色")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            COLOR_PRESETS.forEach { preset ->
                FilterChip(
                    selected = settings.foregroundArgb == preset.foreground &&
                        settings.backgroundArgb == preset.background,
                    onClick = {
                        onChange { it.copy(foregroundArgb = preset.foreground, backgroundArgb = preset.background) }
                    },
                    label = { Text(preset.name) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ColorField(
                label = "前景色",
                argb = settings.foregroundArgb,
                onColor = { argb -> onChange { it.copy(foregroundArgb = argb) } },
                modifier = Modifier.weight(1f),
            )
            ColorField(
                label = "背景色",
                argb = settings.backgroundArgb,
                onColor = { argb -> onChange { it.copy(backgroundArgb = argb) } },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun ColorField(
    label: String,
    argb: Int,
    onColor: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = TerminalSettings.formatColor(argb),
        onValueChange = { text -> TerminalSettings.parseColor(text)?.let(onColor) },
        label = { Text("$label（#RRGGBB）") },
        singleLine = true,
        modifier = modifier,
    )
}

private data class ColorPreset(val name: String, val foreground: Int, val background: Int)

private val FONT_CANDIDATES = listOf(
    "MesloLGM Nerd Font",
    "JetBrains Mono",
    "Cascadia Mono",
    "Cascadia Code",
    "Consolas",
    "Sarasa Mono SC",
    "Microsoft YaHei UI",
)

private val FONT_SIZES = listOf(12f, 13f, 14f, 16f, 18f, 20f)

private val COLOR_PRESETS = listOf(
    ColorPreset("默认", 0xFFD5DBE5.toInt(), 0xFF05070B.toInt()),
    ColorPreset("VS Code", 0xFFD4D4D4.toInt(), 0xFF1E1E1E.toInt()),
    ColorPreset("Solarized", 0xFF839496.toInt(), 0xFF002B36.toInt()),
    ColorPreset("高对比", 0xFFFFFFFF.toInt(), 0xFF000000.toInt()),
)
