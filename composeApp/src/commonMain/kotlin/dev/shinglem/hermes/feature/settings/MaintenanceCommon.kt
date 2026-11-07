package dev.shinglem.hermes.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.contract.MemoryProviderStatusDto

/** 维护类页面的标题 + 说明。 */
@Composable
internal fun MaintenanceHeader(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 默认档案的 key（与 `ShellViewModel.DEFAULT_PROFILE` 同值；这里不 import shell 的类型）。 */
internal const val DEFAULT_PROFILE_KEY = "default"

/** 档案级页面：数据按 profile 隔离，切换后要重拉。 */
fun SettingsPage.isProfileScoped(): Boolean = when (this) {
    is SettingsPage.Catalog,
    SettingsPage.TerminalAppearance,
    SettingsPage.Notifications,
    SettingsPage.Browser,
    SettingsPage.Editors,
    SettingsPage.Shortcuts,
    SettingsPage.About,
    SettingsPage.Debug,
    -> false

    else -> true
}

/** 维护页组自带「应用到」条，避免顶部再出现一条重复的。 */
fun SettingsPage.hasOwnProfileScope(): Boolean = when (this) {
    SettingsPage.Memory,
    SettingsPage.Maintenance,
    SettingsPage.Usage,
    SettingsPage.SessionMaintenance,
    SettingsPage.CredentialPool,
    SettingsPage.Integrations,
    -> true

    else -> false
}

/** 页面上「应用到」的一个档案选项。 */
data class ProfileChoice(val key: String, val label: String)

/**
 * 设置页顶部的作用域选择器（照官方桌面端：一行档案 chips）。
 *
 * 它只是**本组设置页的配置目标**，不改会话页当前使用的档案 —— [onSelect] 走
 * `MaintenanceViewModel.setScopeProfile`，只重拉本组数据。
 */
data class ProfileScope(
    val choices: List<ProfileChoice>,
    val activeKey: String,
    val onSelect: (String) -> Unit,
)

/**
 * 「应用到」档案 chips。
 *
 * 用横向滚动而不是换行：档案数量不定（default + N），换行会把下面正文顶下去；
 * 官方也是一行 chips。
 */
@Composable
fun ProfileScopeChips(scope: ProfileScope?) {
    if (scope == null || scope.choices.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    Text("应用到", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        scope.choices.forEach { choice ->
            FilterChip(
                selected = choice.key == scope.activeKey,
                onClick = { scope.onSelect(choice.key) },
                label = { Text(choice.label) },
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "只改变这一组设置页的配置目标；会话页当前使用的档案不受影响。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 结果提示（成功 / 失败）；点一下收起。 */
@Composable
internal fun MaintenanceStatus(notice: String?, error: String?, onDismiss: () -> Unit) {
    if (notice == null && error == null) return
    Spacer(Modifier.height(10.dp))
    Column(modifier = Modifier.fillMaxWidth()) {
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onDismiss),
            )
        }
        notice?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onDismiss),
            )
        }
    }
}

/** 破坏性操作的二次确认。 */
@Composable
internal fun MaintenanceConfirm(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 等宽小字，用于展示路径、日志与退出码。 */
@Composable
internal fun MonoLine(text: String, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 字节数的人类可读形式（1024 进制，保留一位小数）。 */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val rounded = kotlin.math.round(value * 10) / 10
    return "$rounded ${units[unit]}"
}

/** 千分位，避免 1234567 这种数字在窄窗里读不出来。 */internal fun formatCount(value: Long): String {
    val text = value.toString()
    return text.reversed().chunked(3).joinToString(",").reversed()
}

/** 带一位小数的金额。 */
internal fun formatCost(value: Double): String {
    val rounded = kotlin.math.round(value * 100) / 100
    return "$$rounded"
}

/** 记忆 provider 的状态徽章文案（后端 `status` 是英文枚举）。 */
internal fun memoryStatusLabel(status: String): String = when (status) {
    "ready" -> "就绪"
    "needs_config" -> "待配置"
    "unavailable" -> "缺依赖"
    "missing" -> "未找到"
    else -> status.ifBlank { "未知" }
}

/**
 * 「为什么不能启用」的逐条说明。
 *
 * 后端的 `status` 只给一个词，但用户要知道**具体缺什么**：
 * - `unavailable` 分两种：依赖没装（列出命令 / 包），或插件加载失败（被停用）；
 * - `needs_config` 列出必需的环境变量，并指向设置里的密钥页。
 *
 * `ready` 返回空列表。
 */
internal fun memoryProviderHints(provider: MemoryProviderStatusDto): List<String> = buildList {
    val setup = provider.setup
    when (provider.status) {
        "missing" -> add("配置里引用的插件已不存在（可能被删除或改名）")

        "needs_config" -> {
            if (setup.requiredEnv.isNotEmpty()) {
                add("需要先配置：${setup.requiredEnv.joinToString("、")}（本版不提供入口，写在对应档案的 .env 里）")
            } else {
                add("插件需要额外配置，先在官方 dashboard 里完成")
            }
        }

        "unavailable" -> {
            if (!setup.dependenciesInstalled) {
                setup.externalDependencies.forEach { dependency ->
                    add("缺少命令 ${dependency.name}" + if (dependency.install.isNotBlank()) "，安装：${dependency.install}" else "")
                    if (dependency.check.isNotBlank()) add("检查：${dependency.check}")
                }
                if (setup.pipDependencies.isNotEmpty()) {
                    add("缺少 Python 包：${setup.pipDependencies.joinToString("、")}")
                }
                if (setup.externalDependencies.isEmpty() && setup.pipDependencies.isEmpty()) {
                    add("依赖未安装（插件声明了依赖，但探测没通过）")
                }
            } else {
                add("插件加载失败：可能已在 plugins.disabled 中停用，或接口不完整")
            }
        }
    }
}
