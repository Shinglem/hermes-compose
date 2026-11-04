package dev.shinglem.hermes.core.app

import dev.shinglem.hermes.core.config.AutostartMode
import dev.shinglem.hermes.core.log.Log
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 开机自启：登录触发的 Windows 计划任务。
 *
 * 为什么只能是计划任务：登录时由 Task Scheduler 服务创建进程，任务已声明"以最高权限运行"，
 * 所以**不会**走 `consent.exe`（UAC 提示）。manifest `requireAdministrator` 与注册表 `Run` 键
 * 都会在登录时弹 UAC —— 详见 `.design/详细设计/工程与代码/07-提权与自启动.md` §2。
 *
 * 三条纪律：
 * 1. 只操作**自己算出的那一个任务名**（含 `<home>` 哈希）。本机就有一个
 *    `Hermes-Netcatty-Launch` 指向 `Netcatty.exe`，按前缀批量删会误伤。
 * 2. 增删都需要管理员权限（实测：非管理员连 `/RL LIMITED` 都 `Access is denied`），
 *    所以未提权时只**报告**状态，不尝试写。
 * 3. 不写 `HKLM`、不注册服务、不改 `HKCU\...\Run`。
 */
internal object ScheduledAutostart {

    /** 任务名：`HermesCompose-<sha256(<home> 规范化路径) 前 8 位>`，与单实例用同一套哈希。 */
    fun taskName(home: File): String {
        val canonical = runCatching { home.canonicalPath }.getOrDefault(home.absolutePath)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "HermesCompose-" + digest.take(4).joinToString("") { "%02x".format(it) }
    }

    /**
     * 当前实际状态。
     *
     * `matchesExeAndArgs` 读回 XML 里的 `<Command>` / `<Arguments>` 比对 —— **两者都要对**：
     * 只比路径的话，早期版本写下的"没有 `--silent`"任务会被当成健康，从此每次登录都弹窗口。
     * 读不到（编码/解析问题）一律当作**不匹配** —— 那只会导致已提权时多写一次任务，
     * 永远坏不到"用着旧路径还自以为没问题"。
     */
    data class Status(
        val taskName: String,
        val installed: Boolean,
        val matchesExeAndArgs: Boolean,
        /** 落地需要管理员权限，而当前不是管理员。 */
        val needsElevation: Boolean,
        /** 配置里有意图、但系统里还没有（或指向旧路径）。 */
        val pending: Boolean,
    ) {
        val healthy: Boolean get() = installed && matchesExeAndArgs
    }

    fun query(home: File, exe: File?, elevated: Boolean): Status {
        val name = taskName(home)
        val definition = taskDefinition(name)
        val installed = definition.command != null || taskExists(name)
        val matches = exe != null &&
            definition.command != null &&
            samePath(definition.command, exe) &&
            hasSilentArgument(definition.arguments)
        return Status(
            taskName = name,
            installed = installed,
            matchesExeAndArgs = matches,
            needsElevation = !elevated,
            pending = false,
        )
    }

    /**
     * 让系统状态贴合配置意图：`task` 就把任务写成"当前 exe + `--silent`"，`off` 就删掉。
     *
     * 未提权时**不动系统**，只把 `pending` 标出来让 UI 提示（静默失败是最难查的那种错）。
     */
    fun reconcile(mode: AutostartMode, home: File, exe: File?, elevated: Boolean): Status {
        val name = taskName(home)
        val current = query(home, exe, elevated)
        if (mode == AutostartMode.OFF) {
            if (!elevated) return current.copy(pending = current.installed)
            var stillThere = current.installed
            if (current.installed) {
                val removed = remove(name)
                Log.info("自启动已关闭（删除任务 $name）：success=$removed")
                stillThere = !removed
            }
            // 早期版本的固定名任务指向同一个 exe：配置关了就得一起删，否则登录时它照样拉起
            // 一个**带窗口**的实例（见 [parseDuplicateTasks]）。
            val leftovers = sweepDuplicates(name, exe).isNotEmpty()
            val installed = stillThere || leftovers
            return current.copy(installed = installed, matchesExeAndArgs = false, pending = installed)
        }

        if (exe == null) {
            Log.warn("自启动未启用：找不到启动器 exe（jpackage.app-path 与 <home>/hermes-desktop-compose.exe 都不存在）")
            return current.copy(pending = true)
        }
        if (!current.healthy) {
            if (!elevated) {
                Log.warn("自启动需要管理员权限才能写计划任务（任务名 $name），本次跳过")
                return current.copy(pending = true)
            }
            val ok = install(name, exe, home)
            Log.info("自启动任务已写入 $name -> ${exe.absolutePath}：success=$ok")
            if (!ok) return query(home, exe, elevated).copy(pending = true)
        }
        // 新任务就位后再清多余任务：反过来的话，写任务失败会让这份安装彻底没有自启。
        if (elevated) sweepDuplicates(name, exe)
        return query(home, exe, elevated)
    }

    // ------------------------------------------------------------------ schtasks

    /**
     * 扫描"指向已不存在程序"的本产品任务（便携包挪过位置后留下的旧任务）。
     *
     * 一次 `schtasks /Query /XML`（不带 `/TN`）就能拿到全部任务的定义 —— 本机 283 个任务、
     * 约 1 MB，比逐个查询快一个数量级。只挑名字完全匹配 `HermesCompose[-<8 hex>]` 的，
     * 且**排除当前任务名**、**要求 Command 指向的文件确实不存在**。
     */
    fun scanOrphans(currentTaskName: String): List<OrphanTaskInfo> {
        val text = queryAllXml() ?: return emptyList()
        return parseOrphans(text, currentTaskName) { path -> File(path).isFile }
    }

    /**
     * 从 `schtasks /Query /XML` 的全量输出里挑出失效任务。抽成纯函数是为了能单测 ——
     * 真实场景（便携包换盘符 + 旧任务残留）没法在单测里造出来。
     */
    internal fun parseOrphans(
        xml: String,
        currentTaskName: String,
        exists: (String) -> Boolean,
    ): List<OrphanTaskInfo> {
        return TASK_BLOCK.findAll(xml).mapNotNull { block ->
            val body = block.groupValues[1]
            val uri = URI_PATTERN.find(body)?.groupValues?.get(1)?.trim()?.removePrefix("\\")
                ?: return@mapNotNull null
            if (!PRODUCT_TASK_NAME.matches(uri)) return@mapNotNull null
            if (uri == currentTaskName) return@mapNotNull null
            val command = COMMAND_PATTERN.find(body)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            // 路径仍存在 → 可能是另一份**正在用**的安装，绝不列进"失效"
            if (command.isNotBlank() && exists(command)) return@mapNotNull null
            OrphanTaskInfo(uri, command)
        }.toList()
    }

    /** 删除一条任务（需要管理员权限）。 */
    fun deleteTask(name: String): Boolean = remove(name)

    /**
     * 删掉同一份安装里**多余**的自启任务（见 [parseDuplicateTasks]）。返回没删成功的任务名。
     *
     * 只是收尾清理，失败不该影响对账结果 —— 只记日志。
     */
    private fun sweepDuplicates(currentTaskName: String, exe: File?): List<String> {
        if (exe == null) return emptyList()
        val xml = queryAllXml() ?: return emptyList()
        val duplicates = parseDuplicateTasks(xml, currentTaskName, exe.absolutePath)
        if (duplicates.isEmpty()) return emptyList()
        return duplicates.filterNot { name ->
            val deleted = remove(name)
            Log.info("清理多余的自启动任务 $name（与当前任务 $currentTaskName 指向同一 exe）：success=$deleted")
            deleted
        }
    }

    /**
     * 找出"同一份安装里多余的自启任务"：名字落在本产品命名空间、`<Command>` 与当前启动器完全一致、
     * 但不是 [currentTaskName]。
     *
     * 真实来源：早期版本用的是固定名 `HermesCompose`，而且**不带 `--silent`**。升级后新任务名
     * （`HermesCompose-<hash>`）与它对不上，于是两条同时触发 —— 旧那条拉起**带窗口**的实例，
     * 新那条随后被单实例挡掉，用户看到的就是"开机自启会弹窗、还会白弹一次 UAC"。
     *
     * 指向别处 exe 的任务一律不碰（可能是另一份正在用的安装）。
     */
    internal fun parseDuplicateTasks(xml: String, currentTaskName: String, exePath: String): List<String> {
        val target = normalizePath(exePath)
        return TASK_BLOCK.findAll(xml).mapNotNull { block ->
            val body = block.groupValues[1]
            val uri = URI_PATTERN.find(body)?.groupValues?.get(1)?.trim()?.removePrefix("\\")
                ?: return@mapNotNull null
            if (!PRODUCT_TASK_NAME.matches(uri)) return@mapNotNull null
            if (uri == currentTaskName) return@mapNotNull null
            val command = COMMAND_PATTERN.find(body)?.groupValues?.get(1)?.trim().orEmpty()
            if (command.isEmpty() || normalizePath(command) != target) return@mapNotNull null
            uri
        }.toList()
    }

    /**
     * `<Arguments>` 必须真的带 `--silent`（或它的别名 `--tray`），否则登录时会直接弹主窗口。
     * 按 token 比对而不是子串匹配：`--silent-profile` 不是你想要的。
     */
    internal fun hasSilentArgument(arguments: String?): Boolean =
        arguments.orEmpty()
            .split(' ', '\t')
            .any { it.equals("--silent", ignoreCase = true) || it.equals("--tray", ignoreCase = true) }

    private fun normalizePath(path: String): String =
        path.trim().trim('"').replace('/', '\\').trimEnd('\\').lowercase()

    private fun queryAllXml(): String? {
        val output = runCapture("schtasks", "/Query", "/XML") ?: return null
        return runCatching { String(output, charset("GBK")) }
            .getOrElse { String(output, Charsets.UTF_8) }
    }

    private fun install(name: String, exe: File, home: File): Boolean {
        val xml = taskXml(name, exe, home)
        val file = File(home, "logs/autostart-task.xml")
        return runCatching {
            file.parentFile?.mkdirs()
            // `schtasks /Create /XML` 对编码敏感：必须是 UTF-16LE **带 BOM**。
            // 用 UTF-8 会报"任务 XML 包含错误格式"（已实测）。
            val body = xml.toByteArray(Charsets.UTF_16LE)
            Files.write(file.toPath(), byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + body)
            run("schtasks", "/Create", "/TN", name, "/XML", file.absolutePath, "/F")
        }.getOrElse { failure ->
            Log.error("写自启动任务失败", failure)
            false
        }
    }

    private fun remove(name: String): Boolean = run("schtasks", "/Delete", "/TN", name, "/F")

    private fun taskExists(name: String): Boolean = run("schtasks", "/Query", "/TN", name)

    /** 读回任务里的 `<Command>` / `<Arguments>`；读不到返回两个 null（见 [Status.matchesExeAndArgs]）。 */
    private fun taskDefinition(name: String): Definition {
        val output = runCapture("schtasks", "/Query", "/TN", name, "/XML") ?: return Definition(null, null)
        // schtasks 的 stdout 是控制台编码（中文系统上是 GBK）。GBK 解 ASCII 也是对的，
        // 所以非中文系统上同样安全。
        val text = runCatching { String(output, charset("GBK")) }
            .getOrElse { String(output, Charsets.UTF_8) }
        return Definition(
            command = COMMAND_PATTERN.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() },
            arguments = ARGUMENTS_PATTERN.find(text)?.groupValues?.get(1)?.trim(),
        )
    }

    private data class Definition(val command: String?, val arguments: String?)

    private fun samePath(command: String, exe: File): Boolean {
        val left = runCatching { File(command).canonicalPath }.getOrDefault(command).lowercase()
        val right = runCatching { exe.canonicalPath }.getOrDefault(exe.absolutePath).lowercase()
        return left == right
    }

    private fun run(vararg command: String): Boolean = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        // drain 一下，避免输出量大时子进程被管道憋住
        process.inputStream.readBytes()
        process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    private fun runCapture(vararg command: String): ByteArray? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val bytes = process.inputStream.readBytes()
        if (process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0) bytes else null
    }.getOrNull()

    // ------------------------------------------------------------------ XML

    /**
     * 任务定义。字段取值理由见 `07` 篇 §3.4 的表格，这里只记最容易被改错的几个：
     * - `RunLevel=HighestAvailable`（不是 `RequireHighest`，否则非管理员用户会直接失败）
     * - `LogonType=InteractiveToken`（`S4U` 会落到无 UI 的会话，托盘图标加不上）
     * - `ExecutionTimeLimit=PT0S`（默认 `PT72H` 会到点强杀）
     * - `Delay=PT20S`（登录瞬间托盘区可能还没就绪）
     * - `Arguments=--silent`（登录后进托盘，不弹窗）
     */
    private fun taskXml(name: String, exe: File, home: File): String {
        val userId = currentUser()
        val command = xmlEscape(exe.absolutePath)
        val workingDir = xmlEscape(home.absolutePath)
        return """
            <?xml version="1.0" encoding="UTF-16"?>
            <Task version="1.4" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
              <RegistrationInfo>
                <Description>Hermes desktop client · 登录时以最高权限启动（无 UAC）· home=${xmlEscape(home.absolutePath)}</Description>
                <URI>\$name</URI>
              </RegistrationInfo>
              <Triggers>
                <LogonTrigger>
                  <Enabled>true</Enabled>
                  <UserId>$userId</UserId>
                  <Delay>PT20S</Delay>
                </LogonTrigger>
              </Triggers>
              <Principals>
                <Principal id="Author">
                  <UserId>$userId</UserId>
                  <LogonType>InteractiveToken</LogonType>
                  <RunLevel>HighestAvailable</RunLevel>
                </Principal>
              </Principals>
              <Settings>
                <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
                <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
                <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
                <AllowHardTerminate>true</AllowHardTerminate>
                <StartWhenAvailable>true</StartWhenAvailable>
                <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
                <Enabled>true</Enabled>
              </Settings>
              <Actions Context="Author">
                <Exec>
                  <Command>$command</Command>
                  <Arguments>--silent</Arguments>
                  <WorkingDirectory>$workingDir</WorkingDirectory>
                </Exec>
              </Actions>
            </Task>
        """.trimIndent()
    }

    /** `DOMAIN\user`：`USERDOMAIN` 在本地账户时等于计算机名，正是 Task Scheduler 认的写法。 */
    private fun currentUser(): String {
        val domain = System.getenv("USERDOMAIN").orEmpty().ifBlank {
            System.getenv("COMPUTERNAME").orEmpty()
        }
        val user = System.getProperty("user.name").orEmpty()
        return if (domain.isBlank()) user else "$domain\\$user"
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private val TASK_BLOCK = Regex("<Task[ >](.*?)</Task>", RegexOption.DOT_MATCHES_ALL)
    private val URI_PATTERN = Regex("<URI>(.*?)</URI>", RegexOption.DOT_MATCHES_ALL)
    private val COMMAND_PATTERN = Regex("<Command>(.*?)</Command>", RegexOption.DOT_MATCHES_ALL)
    private val ARGUMENTS_PATTERN = Regex("<Arguments>(.*?)</Arguments>", RegexOption.DOT_MATCHES_ALL)

    /**
     * 本产品的自启任务名：现行 [taskName] 算出的是 `HermesCompose-<8 hex>`；
     * 早期版本是**固定名** `HermesCompose`（没有哈希、也没有 `--silent`）——
     * 这两种都算我们的命名空间，其余名字（`Hermes-Netcatty-Launch`、`HermesDashboard`）一律不认。
     */
    private val PRODUCT_TASK_NAME = Regex("""^HermesCompose(-[0-9a-f]{8})?$""")
}
