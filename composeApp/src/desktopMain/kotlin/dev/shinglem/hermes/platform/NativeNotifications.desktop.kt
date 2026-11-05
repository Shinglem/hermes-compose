package dev.shinglem.hermes.platform

import dev.nucleusframework.notification.windows.DismissalReason
import dev.nucleusframework.notification.windows.ShortcutPolicy
import dev.nucleusframework.notification.windows.ToastDuration
import dev.nucleusframework.notification.windows.ToastNotificationListener
import dev.nucleusframework.notification.windows.WindowsNotificationCenter
import dev.nucleusframework.notification.windows.toast
import dev.shinglem.hermes.core.app.ProductInfo
import dev.shinglem.hermes.core.log.Log
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Windows 原生通知（WinRT toast + 按钮）。
 *
 * **前置条件（实测，无法绕过）**：AUMID 必须在**开始菜单**里有一个带 `PKEY_AppUserModel_ID`
 * 的 `.lnk`。桌面 / 程序目录 / 仅注册表键都不行（见 `12-通知窗.md` 的对照实验）。
 * `REQUIRE_CREATE` 策略下由库创建/更新，不需要管理员（写用户级开始菜单）。
 *
 * **多实例共存**：快捷方式文件名与 AUMID 都带 `<home>` 哈希（与单实例命名同一套算法），
 * 两份便携包各有独立的通知身份，互不覆盖。
 *
 * 线程：库把 WinRT 调用放在自己的 MTA 线程，`onActivated` 从 COM RPC 线程回来 ——
 * 动作必须切回 Main 再执行（会改 Compose 状态、发 RPC）。
 */
actual object NativeNotifications {

    private val _status = MutableStateFlow(NativeNotificationStatus())
    actual val status: StateFlow<NativeNotificationStatus> = _status.asStateFlow()

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tokens = ConcurrentHashMap<String, () -> Unit>()
    private val seq = AtomicLong(0)

    private var homeDir: File? = null
    private var appName: String = ""
    private var initialized = false
    private var listenerInstalled = false

    /** 上一次初始化用的快捷方式基名：关掉开关时用它清掉开始菜单里的条目。 */
    private var lastAppName: String? = null

    /**
     * 开始菜单里那条 `.lnk` 是**我们建的**吗。
     *
     * 复用用户自己建的快捷方式时不能删它 —— 关掉开关只该清掉我们的痕迹（AUMID 注册键），
     * 不该顺手把用户的入口删了。
     */
    private var shortcutOwned = false

    /** 这次是否写过 AUMID 注册键（复用的外来 AUMID 不动它的键，免得覆盖用户配置）。 */
    private var aumidRegistered = false

    /** 这次实际在用的 AUMID（关开关时用来判断要不要连带清键）。 */
    private var aumidInUse: String? = null

    /** 由 `Main` 在解析出 `<home>` 后调用一次。 */
    fun attachHome(home: File) {
        homeDir = home
    }

    /** 当前是否真的能发原生 toast（设置页与 `SystemNotifications` 都用它判断）。 */
    val active: Boolean get() = _status.value.ready

    /** 每次 `sync` 递增：异步补写回来时用它判断"这次结果还算不算数"。 */
    private var syncGeneration = 0

    actual fun sync(requested: Boolean) {
        syncGeneration++
        val generation = syncGeneration
        val home = homeDir
        if (!requested || home == null) {
            if (initialized) {
                runCatching { WindowsNotificationCenter.uninitialize() }
                initialized = false
            }
            // 关掉开关就把痕迹收干净：删掉开始菜单里的 .lnk 与 AUMID 注册键。
            // 用 home 现算名字而不是依赖 lastAppName —— 关掉后重启的进程里它是空的。
            // 但**复用来的用户快捷方式不删**（`shortcutOwned == false` 且已知复用过）。
            home?.let { dir ->
                if (shortcutOwned || lastAppName == null) {
                    deleteShortcut(lastAppName ?: appNameFor(dir))
                } else {
                    Log.info("native notifications: shortcut '${lastAppName}' kept (reused, not created by us)")
                }
                // 自己那个 AUMID 的键始终删（只针对本实例 id，删不存在的是无操作）；
                // 复用了旧版本自己留下的 AUMID 时连带删掉它；外来的键绝不动。
                deleteAumidRegistration(aumidFor(dir))
                aumidInUse?.takeIf { it != aumidFor(dir) && aumidRegistered }?.let { deleteAumidRegistration(it) }
                shortcutOwned = false
                aumidRegistered = false
                aumidInUse = null
            }
            tokens.clear()
            _status.value = NativeNotificationStatus(requested = requested)
            return
        }

        val id = instanceId(home)
        val ownName = "${ProductInfo.DISPLAY_NAME} ($id)"
        val ownAumid = AUMID_PREFIX + id
        val ownFile = shortcutFile(ownName)

        // 复用判定：自己那条在就沿用（老行为，零 IO）；不在就看开始菜单里有没有**指向本程序**的快捷方式。
        val entries = if (ownFile.isFile) null else scanShortcutEntries()
        val action = planShortcut(
            ownName = ownName,
            ownFileExists = ownFile.isFile,
            ownAumid = ownAumid,
            entries = entries,
        )

        if (action is ShortcutAction.AddAumid) {
            // 一次性修复：指向本程序但没有 AUMID（典型就是用户手工建的 `Hermes.lnk`）。
            // 补写走 PowerShell + COM，实测 2.0–2.6 s —— **不能放在启动关键路径上**：
            // 先回一个"不可用"状态（通知自动回退自绘窗），后台跑完再置为可用。
            publishPendingPatch(action, generation)
            return
        }

        val plan = when (action) {
            is ShortcutAction.UseOwn -> ShortcutPlan(
                appName = action.appName,
                aumid = action.aumid,
                create = action.create,
                reason = action.reason,
                note = action.note,
                reused = if (action.create) null else action.appName,
            )

            is ShortcutAction.Reuse -> ShortcutPlan(
                appName = action.appName,
                aumid = action.aumid,
                create = false,
                reason = action.reason,
                note = action.note,
                reused = action.appName,
            )

            is ShortcutAction.AddAumid -> error("AddAumid 已在上面处理")
        }
        val reusedEntry = (entries ?: emptyList()).firstOrNull { it.relativeName == plan.reused }
        applyPlan(plan, generation, reusedEntry?.targetPath)
    }

    /**
     * 后台把 AUMID 补写进已有的 `.lnk`，完后按结果继续初始化。
     *
     * 期间状态是 `ready=false`（消息写"正在写入通知身份"）—— `show()` 因此返回 false，
     * 通知自动走应用内自绘窗，不会出现"没有通知"。
     */
    private fun publishPendingPatch(action: ShortcutAction.AddAumid, generation: Int) {
        val lnk = shortcutFile(action.entry.relativeName)
        appName = action.entry.relativeName
        lastAppName = appName
        aumidInUse = action.targetAumid
        shortcutOwned = false
        aumidRegistered = false
        _status.value = NativeNotificationStatus(
            requested = true,
            ready = false,
            shortcutPath = lnk.absolutePath,
            message = "正在把通知身份写入已有快捷方式（首次一次性修复，约 2-3 秒）",
        )
        Log.info(
            "native notifications: patching aumid into '${action.entry.relativeName}' in background " +
                "(path=${lnk.absolutePath})",
        )
        mainScope.launch {
            val home = homeDir ?: return@launch
            val id = instanceId(home)
            val ownName = "${ProductInfo.DISPLAY_NAME} ($id)"
            val ownAumid = AUMID_PREFIX + id
            val result = withContext(Dispatchers.IO) { ShortcutAumidWriter.patch(lnk, action.targetAumid) }
            if (generation != syncGeneration) {
                Log.info("native notifications: patch result ignored (sync 已重新执行)")
                return@launch
            }
            val plan = if (result.ok) {
                Log.info("native notifications: shortcut '${action.entry.relativeName}' aumid patched (backup .bak)")
                ShortcutPlan(
                    appName = action.entry.relativeName,
                    aumid = action.targetAumid,
                    create = false,
                    reason = "target",
                    reused = action.entry.relativeName,
                )
            } else {
                Log.warn(
                    "native notifications: aumid patch failed (${result.detail}); " +
                        "creating a new shortcut instead",
                )
                ShortcutPlan(
                    appName = ownName,
                    aumid = ownAumid,
                    create = true,
                    reason = "patching-failed",
                )
            }
            applyPlan(plan, generation, if (result.ok) action.entry.targetPath else null)
        }
    }

    /** 真正初始化通知身份并落状态。补写成功 / 失败都从这里收口。 */
    private fun applyPlan(plan: ShortcutPlan, generation: Int, reusedTarget: String?) {
        if (generation != syncGeneration) return
        appName = plan.appName
        lastAppName = appName
        val aumid = plan.aumid
        aumidInUse = aumid
        // 复用的用户快捷方式不能删；自己新建的、或沿用旧版本自己留下的都能删。
        shortcutOwned = plan.create || (plan.reused?.startsWith(ProductInfo.DISPLAY_NAME) == true)

        installListener()
        // 先注册 AUMID 的显示名与图标：`initialize` 会改进程的 AUMID，任务栏随后按它找图标，
        // 不注册就会退回 java.exe 的咖啡杯（实测）。
        // 复用的外来 AUMID 不动它的键（那是用户自己配的），免得覆盖 DisplayName / IconUri。
        // 只要 AUMID 是我们的命名前缀（新建的 / 旧版本留下的 / 补写进去的）就注册显示名与图标；
        // 用户自己配的其它 AUMID 不动它的键。
        val shouldRegisterAumid = aumid.startsWith(AUMID_PREFIX)
        if (shouldRegisterAumid) {
            registerAumid(aumid, appName)
            aumidRegistered = true
        }

        val shortcut = shortcutFile(appName)
        // `REQUIRE_CREATE` 每次启动都会重写 `.lnk`；`REQUIRE_NO_CREATE` 只沿用已有的
        // （实测 2026-11-04：mtime 不变、未重写）。
        // 注意 `REQUIRE_NO_CREATE` 在文件缺失时**也返回 true**（只表示"不创建"），
        // 所以可用性仍由下面的 `exists` 判定。
        val policy = if (plan.create) ShortcutPolicy.REQUIRE_CREATE else ShortcutPolicy.REQUIRE_NO_CREATE

        val ok = runCatching {
            WindowsNotificationCenter.initialize(
                aumid = aumid,
                appName = appName,
                shortcutPolicy = policy,
            )
        }.getOrDefault(false)
        val available = WindowsNotificationCenter.isAvailable
        initialized = ok && available

        val exists = shortcut.isFile

        // 关键：initialize 把**进程** AUMID 改成了通知用的那个，任务栏会因此多出一个图标
        // （实测：固定项 + 运行窗口各一个）。toast 靠显式 AUMID 发送，不依赖进程这个值，
        // 所以立刻清掉，让任务栏分组回到原样。
        ProcessAumid.clear()

        _status.value = NativeNotificationStatus(
            requested = true,
            ready = initialized && exists,
            shortcutPath = shortcut.absolutePath,
            message = when {
                !available -> "系统不支持通知（native 库不可用）"
                !ok -> "通知子系统初始化失败"
                !exists -> "开始菜单快捷方式未创建成功"
                // ready=true 时也把补写失败这类"降级处理"写出来（设置页会显示非空 message）。
                plan.reason == "patching-failed" ->
                    "已有快捷方式写不进通知身份（只读 / 策略限制 / 超时），已新建一个带通知身份的条目"

                else -> null
            },
        )
        Log.info(
            "native notifications: aumid=$aumid ready=${_status.value.ready} " +
                "shortcut=${shortcut.absolutePath} exists=$exists " +
                "policy=${if (plan.create) "create" else "reuse"} " +
                "reused=${plan.reused ?: "none"} reason=${plan.reason}" +
                (plan.note?.let { " note=$it" } ?: ""),
        )
        reusedTarget?.let { Log.info("native notifications: reused shortcut target=$it") }
    }

    /**
     * 发一条带按钮的原生 toast。
     *
     * @return false 表示没发出去（未启用 / 调用失败），调用方应回退到应用内自绘窗。
     */
    fun show(
        title: String,
        body: String,
        buttons: List<NotificationAction>,
        onBodyClick: (() -> Unit)?,
    ): Boolean {
        if (!active) return false
        // 用户可能在运行中把开始菜单快捷方式删了。这时 nucleus 的 show 会**静默失败**
        // （实测 2026-11-04：callback error=null、toast 不出现、.lnk 也不重建），
        // 所以自己先查一次，让它退到自绘窗，别变成"没有通知"。
        if (!shortcutFile(appName).isFile) {
            _status.value = _status.value.copy(
                ready = false,
                message = "开始菜单快捷方式已被删除，已回退应用内通知（重新打开开关可重建）",
            )
            Log.warn("native notifications: shortcut missing, fall back to in-app window")
            return false
        }
        // 上一轮没被点的动作先清掉，别让 token 表无限长。
        tokens.clear()
        onBodyClick?.let { tokens[BODY_TOKEN] = it }

        var failed = false
        val content = toast {
            duration = ToastDuration.LONG
            visual {
                text(title)
                text(body.take(240))
            }
            if (buttons.isNotEmpty()) {
                actions {
                    // 每个按钮一个 token：点击时能精确找回对应的 lambda（一条通知可能有 4 个按钮）。
                    buttons.forEach { action ->
                        val token = "a${seq.incrementAndGet()}"
                        tokens[token] = action.onClick
                        button(action.label, arguments = token)
                    }
                }
            }
        }
        WindowsNotificationCenter.show(
            content = content,
            tag = TAG,
            group = GROUP,
            callback = { error -> if (error != null) failed = true },
        )
        return !failed
    }

    private fun installListener() {
        if (listenerInstalled) return
        listenerInstalled = true
        WindowsNotificationCenter.addListener(
            object : ToastNotificationListener {
                override fun onActivated(
                    tag: String,
                    group: String,
                    arguments: String,
                    userInputs: Map<String, String>,
                ) {
                    // 空 arguments = 点了通知主体（没有按钮的 arguments 时）。
                    val action = tokens.remove(arguments.ifBlank { BODY_TOKEN }) ?: return
                    mainScope.launch { runCatching { action() } }
                }

                override fun onDismissed(tag: String, group: String, reason: DismissalReason) {
                    // 用户手动关掉 / 超时：主体动作不会再触发，清掉免得下次误用。
                    if (reason != DismissalReason.APPLICATION_HIDDEN) tokens.remove(BODY_TOKEN)
                }

                override fun onFailed(tag: String, group: String, errorCode: Int) {
                    _status.value = _status.value.copy(message = "通知发送失败（code=$errorCode）")
                }
            },
        )
    }

    /** `%APPDATA%\Microsoft\Windows\Start Menu\Programs\<appName>.lnk`（库写的就是这里）。 */
    private fun shortcutFile(name: String): File = File(programsDir(), "$name.lnk")

    /** `%APPDATA%\Microsoft\Windows\Start Menu\Programs`（用户级开始菜单）。 */
    private fun programsDir(): File {
        val root = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.home")
        return File(root, "Microsoft\\Windows\\Start Menu\\Programs")
    }

    /** 扫描失败返回 null，由 [planShortcut] 退回"按文件名判定"的老逻辑。 */
    private fun scanShortcutEntries(): List<ShortcutEntry>? =
        scanStartMenuShortcuts(programsDir(), currentExePath())?.also { entries ->
            Log.info(
                "native notifications: scanned ${entries.size} start menu shortcuts " +
                    "(matches exe: ${entries.count { it.pointsToTarget }})",
            )
        }

    /**
     * 当前进程的 exe —— 库写进 `.lnk` 的 target 就是它（`GetModuleFileNameW`）。
     *
     * 开发态是宿主 JDK 的 `java.exe`，成品是 `hermes-desktop-compose.exe`，两边都比得中。
     */
    private fun currentExePath(): String? =
        ProcessHandle.current().info().command().orElse(null)

    private fun deleteShortcut(name: String) {
        val removed = runCatching { shortcutFile(name).delete() }.getOrDefault(false)
        Log.info(
            "native notifications: shortcut ${if (removed) "removed" else "already absent"} for '$name'",
        )
    }

    /**
     * 注册 AUMID 的显示名与图标。
     *
     * **必须做**：`initialize` 会调 `SetCurrentProcessExplicitAppUserModelID`，Windows 之后按这个
     * AUMID 找任务栏 / 通知图标；不注册就退回宿主进程（`java.exe`）的咖啡杯 —— 实测如此。
     * `IconUri` 指向运行时解压出来的 `.ico`（开发态没有 exe 可指）。
     *
     * 实现用 `reg.exe` 而不是 FFM：一次启动三次调用（各 ~30ms），比调通 `RegCreateKeyExW`
     * 的签名风险低；这段只在"开关打开"的启动路径上跑一次。
     */
    private fun registerAumid(aumid: String, displayName: String) {
        val key = "HKCU\\$AUMID_KEY_ROOT\\$aumid"
        reg("add", key, "/v", "DisplayName", "/t", "REG_SZ", "/d", displayName, "/f")
        iconFile()?.let { icon ->
            reg("add", key, "/v", "IconUri", "/t", "REG_SZ", "/d", icon.absolutePath, "/f")
        }
        // 让它在"设置 → 系统 → 通知"里有一行（便于用户自己关掉）。
        reg("add", key, "/v", "ShowInSettings", "/t", "REG_SZ", "/d", "1", "/f")
    }

    private fun deleteAumidRegistration(aumid: String) {
        reg("delete", "HKCU\\$AUMID_KEY_ROOT\\$aumid", "/f")
    }

    private fun reg(vararg args: String) {
        runCatching {
            val process = ProcessBuilder(listOf("reg.exe") + args)
                .redirectErrorStream(true)
                .start()
            process.inputStream.readBytes()
            process.waitFor()
        }.onFailure { Log.warn("native notifications: reg ${args.firstOrNull()} failed: ${it.message}") }
    }

    /** 把打进 jar 的 `app-icon.ico` 解压到数据目录（`IconUri` 要真实文件路径）。 */
    private fun iconFile(): File? = runCatching {
        val target = File(appDataDir, "app-icon.ico")
        if (!target.isFile) {
            val stream = NativeNotifications::class.java.classLoader.getResourceAsStream("app-icon.ico")
            if (stream == null) {
                Log.warn("native notifications: app-icon.ico not found in classpath")
                return null
            }
            val bytes = stream.use { it.readBytes() }
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            Log.info("native notifications: icon extracted to ${target.absolutePath} (${bytes.size} bytes)")
        }
        target
    }.onFailure { Log.warn("native notifications: icon extract failed: ${it.message}") }.getOrNull()

    private fun appNameFor(home: File): String = "${ProductInfo.DISPLAY_NAME} (${instanceId(home)})"

    private fun aumidFor(home: File): String = AUMID_PREFIX + instanceId(home)

    /** 与 `SingleInstance` 的命名同源：同一个 home → 同一个 id。 */
    private fun instanceId(home: File): String {
        val canonical = runCatching { home.canonicalPath }.getOrDefault(home.absolutePath)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    /** tag / group 各限 16 字符（WinRT 限制）。 */
    private const val TAG = "hermes"
    private const val GROUP = "approval"

    /** 点通知主体（不是按钮）时用的 token。 */
    private const val BODY_TOKEN = "body"

    private const val AUMID_KEY_ROOT = "Software\\Classes\\AppUserModelId"

    /** AUMID 命名空间：`Shinglem.HermesDesktop.<home 哈希>`（与快捷方式同源，多实例互不覆盖）。 */
    private const val AUMID_PREFIX = "Shinglem.HermesDesktop."
}
