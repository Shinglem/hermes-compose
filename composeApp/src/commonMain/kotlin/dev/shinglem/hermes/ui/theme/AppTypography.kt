package dev.shinglem.hermes.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import dev.shinglem.hermes.resources.Inter_Regular
import dev.shinglem.hermes.resources.Inter_SemiBold
import dev.shinglem.hermes.resources.JetBrainsMono_Bold
import dev.shinglem.hermes.resources.JetBrainsMono_Regular
import dev.shinglem.hermes.resources.Res
import org.jetbrains.compose.resources.Font as ResourceFont

/**
 * 界面字体族（内置 Inter）。
 *
 * 为什么内置而不是用系统默认：成品基线从 JBR 换到 Temurin 后，JBR 自带的 43 个字体
 * （Inter / Roboto / JetBrains Mono…）不复存在，界面观感会变。这里把 JBR 上实际用到的
 * 两族随应用分发（SIL OFL 1.1，可分发，见 composeResources/files/LICENSE-*.txt），
 * 保证跨 JDK 一致。
 *
 * **中文不在这些字体里**：Inter / JetBrains Mono 都没有 CJK 字形，中文由 Skia 的平台
 * 回退（Windows 上是微软雅黑）渲染 —— 这是刻意的，避免为中文再背 10 MB+ 字体。
 *
 * 必须是 @Composable：`org.jetbrains.compose.resources.Font` 在渲染前要加载资源文件。
 */
@Composable
fun appFontFamily(): FontFamily {
    val regular = ResourceFont(Res.font.Inter_Regular, FontWeight.Normal, FontStyle.Normal)
    val semibold = ResourceFont(Res.font.Inter_SemiBold, FontWeight.SemiBold, FontStyle.Normal)
    return remember(regular, semibold) { FontFamily(regular, semibold) }
}

/** 等宽族：工具参数、代码、时间戳这类需要定宽的地方。 */
@Composable
fun appMonoFontFamily(): FontFamily {
    val regular = ResourceFont(Res.font.JetBrainsMono_Regular, FontWeight.Normal, FontStyle.Normal)
    val bold = ResourceFont(Res.font.JetBrainsMono_Bold, FontWeight.Bold, FontStyle.Normal)
    return remember(regular, bold) { FontFamily(regular, bold) }
}

private fun TextStyle.withFont(family: FontFamily): TextStyle = copy(fontFamily = family)

/**
 * 用 [appFontFamily] 覆盖 M3 默认排版。
 *
 * 只换 `fontFamily`，字号 / 行高 / 字距仍用 M3 默认值（`AGENTS.md` 硬约束 12：
 * 不覆盖 `MaterialTheme` 的默认尺寸）。
 */
@Composable
fun appTypography(): Typography {
    val family = appFontFamily()
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.withFont(family),
        displayMedium = base.displayMedium.withFont(family),
        displaySmall = base.displaySmall.withFont(family),
        headlineLarge = base.headlineLarge.withFont(family),
        headlineMedium = base.headlineMedium.withFont(family),
        headlineSmall = base.headlineSmall.withFont(family),
        titleLarge = base.titleLarge.withFont(family),
        titleMedium = base.titleMedium.withFont(family),
        titleSmall = base.titleSmall.withFont(family),
        bodyLarge = base.bodyLarge.withFont(family),
        bodyMedium = base.bodyMedium.withFont(family),
        bodySmall = base.bodySmall.withFont(family),
        labelLarge = base.labelLarge.withFont(family),
        labelMedium = base.labelMedium.withFont(family),
        labelSmall = base.labelSmall.withFont(family),
    )
}
