package dev.shinglem.hermes.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.app.OfficialDesktopLauncher
import dev.shinglem.hermes.core.app.ProductInfo
import dev.shinglem.hermes.feature.shell.AppShortcut
import dev.shinglem.hermes.platform.NativeNotifications
import dev.shinglem.hermes.platform.clientVersion
import dev.shinglem.hermes.ui.component.ErrorBanner

/** 「桌面 → 通知」：总开关 + 系统原生通知开关（默认关 = 应用内自绘窗）。 */
@Composable
fun NotificationsPage(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    nativeEnabled: Boolean = false,
    onNativeToggle: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val nativeStatus by NativeNotifications.status.collectAsState()
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("通知", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "任务跑完、失败，或有等待审批的请求时，在窗口不在前台的情况下弹一条系统通知。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("启用桌面通知", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (enabled) "当前：开启" else "当前：关闭",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("使用系统原生通知", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = when {
                        !nativeEnabled -> "当前：关闭（应用内通知窗）"
                        nativeStatus.ready -> "当前：开启"
                        else -> "当前：已开启但不可用 —— ${nativeStatus.message ?: "未知原因"}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (nativeStatus.degraded) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                // ready 时也可能有话要说（"已有快捷方式补写失败，已新建"），别静默吞掉。
                nativeStatus.message?.takeIf { nativeStatus.ready && it.isNotBlank() }?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = nativeEnabled, onCheckedChange = onNativeToggle)
        }
        if (nativeEnabled) {
            Text(
                text = buildString {
                    append("系统通知需要开始菜单里有一个快捷方式（Windows 的归属要求）：打开开关时会优先")
                    append("复用指向本程序的已有快捷方式（必要时把通知身份写进去），没有才新建；删掉它通知就会失效。")
                    nativeStatus.shortcutPath?.let { append("\n\n快捷方式：$it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = "通知走系统托盘的弹出消息，因此需要托盘图标可用；托盘被系统策略禁用时不会有通知。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 「桌面 → 浏览器」：让 agent 操作的浏览器可见。
 *
 * 开关最终变成后端子进程的 `AGENT_BROWSER_HEADED` 环境变量，所以**改完要重启后端**。
 * 后端的浏览器可执行文件不用配：payload 里已随包带 PM 管理的 Chromium
 * （`hermes/tools/chromium-1208/`），后端每次调 agent-browser 都显式传
 * `AGENT_BROWSER_EXECUTABLE_PATH`。
 */
@Composable
fun BrowserPage(
    headed: Boolean,
    onHeadedToggle: (Boolean) -> Unit,
    args: String,
    onArgsChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("浏览器", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "agent 用内置浏览器访问网页、点击页面元素、截图。默认跑在后台（无窗口），" +
                "打开下面的开关就能看到它在做什么。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("显示浏览器窗口", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (headed) "当前：开启（可在任务栏看到浏览器）" else "当前：关闭（后台运行）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = headed, onCheckedChange = onHeadedToggle)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ChromiumArgsEditor(args = args, onArgsChange = onArgsChange)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = buildString {
                append("改动需要**重启后端**才生效（走环境变量注入）。")
                append("\n\n")
                append("浏览器用的是随包附带的 Chromium，不需要单独安装，也不会往用户目录写文件。")
                append("空闲超过两分钟后浏览器仍会被自动回收。")
                append("\n\n")
                append("已知问题：以管理员运行本程序时，agent 启动 Chromium 可能失败")
                append("（Chrome exited early (exit code: 0) without writing DevToolsActivePort），")
                append("上游尚未修复，与上面的开关无关。")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Chromium 启动参数编辑框。
 *
 * 留空 = 只走自动判断（提权运行时补 `--no-sandbox,--disable-dev-shm-usage`）。
 * 逐字落盘会把半个参数写进配置，所以用「保存」提交，失焦不提交。
 */
@Composable
private fun ChromiumArgsEditor(args: String, onArgsChange: (String) -> Unit) {
    var draft by remember(args) { mutableStateOf(args) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Chromium 启动参数", style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            placeholder = { Text("留空 = 自动", style = MaterialTheme.typography.bodySmall) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onArgsChange(draft) }, enabled = draft != args) { Text("保存") }
            OutlinedButton(onClick = { draft = "" }, enabled = draft.isNotEmpty()) { Text("清空") }
        }
        Text(
            text = buildString {
                append("逗号分隔，会追加到自动参数之后（不是替换）。")
                append("\n\n")
                append("以管理员运行时自动加 ")
                append("--no-sandbox,--disable-dev-shm-usage")
                append("：Chromium 在管理员令牌下拒绝启用自身沙箱，不加就")
                append("「Chrome exited early (exit code: 0) without writing DevToolsActivePort」。")
                append("非提权运行时不加，保持默认隔离。")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 「桌面 → 快捷键」：只读一览（快捷键目前是固定的，不开放自定义）。 */
@Composable
fun ShortcutsPage(modifier: Modifier = Modifier) {
    val entries = listOf(
        "Ctrl+N" to "新建会话",
        "Ctrl+[ / Ctrl+]" to "上一个 / 下一个会话",
        "Ctrl+," to "打开设置",
        "Ctrl+K" to "命令面板",
        "Ctrl+`" to "终端开合",
        "Ctrl+Shift+`" to "终端新标签",
        "Ctrl+Tab / Ctrl+Shift+Tab" to "终端标签切换",
        "Enter / Shift+Enter" to "发送 / 换行",
        "↑ / ↓" to "输入历史",
        "Esc" to "关闭浮层 / 取消编辑",
    )
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("快捷键", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "当前版本为固定键位，暂不支持自定义。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        entries.forEach { (keys, action) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = keys,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(200.dp),
                )
                Text(text = action, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            text = "共 ${AppShortcut.entries.size} 个全局快捷键（其余为输入框内键位）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 「桌面 → 关于」。
 *
 * 版本号分两行：客户端（`jpackage.app-version`）与后端（`/api/status`）。
 * 「检查更新」查的是**后端**更新，客户端自身更新还没做。
 */
@Composable
fun AboutPage(
    backendVersion: String,
    dataDir: String,
    logDir: String,
    update: UpdateState,
    onCheckUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("关于", style = MaterialTheme.typography.titleMedium)
        InfoRow("客户端", ProductInfo.DISPLAY_NAME)
        InfoRow("客户端版本", clientVersion)
        InfoRow("后端版本", backendVersion.ifBlank { "—" })
        InfoRow("数据目录", dataDir)
        InfoRow("日志目录", logDir)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onCheckUpdate, enabled = !update.checking) {
                if (update.checking) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Text("检查后端更新")
                }
            }
        }

        update.error?.let { error ->
            ErrorBanner(error = error, onRetry = onCheckUpdate)
        }
        update.result?.let { result ->
            val text = when {
                result.updateAvailable && result.behind != null ->
                    "有新版本：落后 ${result.behind} 个提交（当前 ${result.currentVersion}）"

                result.updateAvailable ->
                    "有新版本可用（当前 ${result.currentVersion}）"

                result.currentVersion.isNotBlank() ->
                    "已是最新（${result.currentVersion}）"

                else -> result.message ?: "后端没有返回更新信息"
            }
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
            result.updateCommand?.takeIf { it.isNotBlank() }?.let { command ->
                SelectionContainer {
                    Text(
                        text = command,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            result.message?.takeIf { it.isNotBlank() && !result.updateAvailable }?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (update.result == null && !update.checking) {
            Text(
                text = "客户端自身更新（绿色版 zip）尚未接入，这里只检查随包后端。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        SelectionContainer {
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
