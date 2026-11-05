package dev.shinglem.hermes.platform

import java.io.File

/**
 * 开始菜单里一条快捷方式的解析结果。
 *
 * [relativeName] 是相对 `Programs\` 的路径去掉 `.lnk` 后缀 —— 也就是 `initialize(appName = …)`
 * 会拿它去拼 `Programs\<appName>.lnk` 的那个字符串。
 */
internal data class ShortcutEntry(
    val relativeName: String,
    val targetPath: String?,
    val aumid: String?,
) {
    /**
     * 是否直接躺在 `Programs\` 下。
     *
     * 只有直接子项能保证 `appName` → 路径的映射唯一：嵌套项要传 `子目录\名字`，
     * 而那是"库怎么理解 appName"的推测，不能拿执行结果赌。
     */
    val isDirectChild: Boolean
        get() = relativeName.isNotEmpty() &&
            !relativeName.contains('\\') &&
            !relativeName.contains('/')
}

/**
 * 启动时该拿哪条 `.lnk` 去做通知身份。
 *
 * [appName] 直接喂给 `WindowsNotificationCenter.initialize`；[aumid] 为 null 表示用自己算的那个；
 * [create] 为 true 表示允许库新建（`ShortcutPolicy.REQUIRE_CREATE`）。
 * [reason] 只用于日志与排查，取值：`filename` / `target` / `created` / `no-aumid` / `nested` / `fallback`。
 */
internal data class ShortcutPlan(
    val appName: String,
    val aumid: String?,
    val create: Boolean,
    val reason: String,
    /** 复用了哪条（相对 `Programs\` 的名字）；null = 没复用。 */
    val reused: String? = null,
)

/**
 * 复用判定（纯函数）。
 *
 * 老逻辑只认文件名 `<产品名> (<home 哈希>).lnk`，于是「用户自己建过指向同一 exe 的快捷方式」
 * 「旧版本用固定名建过」这些情况都会再建一个。这里的判据改成**是否已有指向本程序的快捷方式**：
 *
 * 1. 自己那条名字还在 → 沿用（`filename`，与老行为一致，最省事）；
 * 2. 名字不在就扫开始菜单：
 *    - 有直接子项、指向本程序、且带 AUMID → 用它（`target`）；
 *    - 有直接子项、指向本程序、但不带 AUMID → 它发不了 toast，仍按老规矩新建（`no-aumid`）；
 *    - 只有嵌套项指向本程序 → 没法可靠复用，新建（`nested`）；
 *    - 一条都没有 → 新建（`created`）；
 * 3. 扫描失败（[entries] 为 null）→ 退回老逻辑新建（`fallback`）—— **不能因此不发通知**。
 *
 * @param ownName 我们自己那套命名（带 `<home>` 哈希）。
 * @param ownFileExists `<ownName>.lnk` 是否已经存在。
 * @param exePath 当前进程的 exe（开发态是宿主 JDK 的 `java.exe`，与库写进 `.lnk` 的 target 一致）。
 * @param entries 扫描结果；null 表示扫描失败。
 * @param ownAumidPrefix 我们认为"自己人"的 AUMID 前缀，用于在多条命中里优先选旧版本留下的那条。
 */
internal fun planShortcut(
    ownName: String,
    ownFileExists: Boolean,
    exePath: String?,
    entries: List<ShortcutEntry>?,
    ownAumidPrefix: String = DEFAULT_AUMID_PREFIX,
): ShortcutPlan {
    if (ownFileExists) {
        return ShortcutPlan(appName = ownName, aumid = null, create = false, reason = "filename", reused = ownName)
    }
    if (entries == null) {
        return ShortcutPlan(appName = ownName, aumid = null, create = true, reason = "fallback")
    }
    val matches = if (exePath.isNullOrBlank()) {
        emptyList()
    } else {
        entries.filter { samePath(it.targetPath, exePath) }
    }
    if (matches.isEmpty()) {
        return ShortcutPlan(appName = ownName, aumid = null, create = true, reason = "created")
    }
    val reusable = matches
        .filter { it.isDirectChild && !it.aumid.isNullOrBlank() }
        // 优先用旧版本自己留下的那条（AUMID 前缀相同），其余按名字排序保证确定性。
        .sortedWith(compareByDescending<ShortcutEntry> { it.aumid!!.startsWith(ownAumidPrefix) }.thenBy { it.relativeName })
        .firstOrNull()
    if (reusable != null) {
        return ShortcutPlan(
            appName = reusable.relativeName,
            aumid = reusable.aumid,
            create = false,
            reason = "target",
            reused = reusable.relativeName,
        )
    }
    val hasDirectWithoutAumid = matches.any { it.isDirectChild }
    return ShortcutPlan(
        appName = ownName,
        aumid = null,
        create = true,
        reason = if (hasDirectWithoutAumid) "no-aumid" else "nested",
    )
}

/** 我们自己的 AUMID 前缀（`Shinglem.HermesDesktop.<hash>`）。 */
internal const val DEFAULT_AUMID_PREFIX = "Shinglem.HermesDesktop."

/** Windows 路径比较：大小写不敏感，分隔符不敏感。 */
internal fun samePath(a: String?, b: String?): Boolean {
    if (a.isNullOrBlank() || b.isNullOrBlank()) return false
    return a.replace('/', '\\').trimEnd('\\').equals(b.replace('/', '\\').trimEnd('\\'), ignoreCase = true)
}

/**
 * 扫 `Programs\` 下的全部 `.lnk`。
 *
 * @return null 表示扫描失败（目录读不了 / 权限不足）—— 调用方按老逻辑新建，**不要抛到启动路径上**。
 */
internal fun scanStartMenuShortcuts(programsDir: File): List<ShortcutEntry>? = runCatching {
    if (!programsDir.isDirectory) return emptyList()
    val root = programsDir.toPath()
    programsDir.walkTopDown()
        .onEnter { it.isDirectory }
        .filter { it.isFile && it.extension.equals("lnk", ignoreCase = true) }
        .mapNotNull { file ->
            val parsed = runCatching { parseShortcut(file.readBytes()) }.getOrNull() ?: return@mapNotNull null
            val relative = runCatching { root.relativize(file.toPath()).toString() }.getOrNull()
                ?: return@mapNotNull null
            ShortcutEntry(
                relativeName = relative.removeSuffix(".lnk").removeSuffix(".LNK").removeSuffix(".Lnk"),
                targetPath = parsed.targetPath,
                aumid = parsed.aumid,
            )
        }
        .toList()
}.getOrNull()

/** 从 `.lnk` 里读出来的两样东西。 */
internal data class ParsedShortcut(val targetPath: String?, val aumid: String?)

/**
 * 解析 `.lnk`（MS-SHLLINK）。
 *
 * 只取两样：TargetPath（LinkInfo 的 LocalBasePath）与 AUMID（property store 里的
 * `PKEY_AppUserModel_ID`）。**不引 COM**：`WScript.Shell` / `Shell.Application` 要
 * CoInitialize + 跨进程，而这两项在文件格式里都有明确位置，纯字节解析更快也更好测。
 *
 * 解析失败一律返回 null（未知结构 / 被截断），调用方当作"这条不认识"跳过。
 */
internal object ShortcutParser {

    /** `PKEY_AppUserModel_ID` 的 format id：{9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3}，pid = 5。 */
    private val AUMID_FMTID = byteArrayOf(
        0x55, 0x28, 0x4C, 0x9F.toByte(),
        0x79, 0x9F.toByte(),
        0x39, 0x4B,
        0xA8.toByte(), 0xD0.toByte(), 0xE1.toByte(), 0xD4.toByte(),
        0x2D, 0xE1.toByte(), 0xD5.toByte(), 0xF3.toByte(),
    )

    private const val HEADER_SIZE = 0x4C
    private const val FLAG_HAS_ID_LIST = 0x1
    private const val FLAG_HAS_LINK_INFO = 0x2
    private const val FLAG_HAS_NAME = 0x4
    private const val FLAG_HAS_RELATIVE_PATH = 0x8
    private const val FLAG_HAS_WORKING_DIR = 0x10
    private const val FLAG_HAS_ARGUMENTS = 0x20
    private const val FLAG_HAS_ICON_LOCATION = 0x40
    private const val FLAG_IS_UNICODE = 0x80

    /** property store 数据块（ExtraData 里的 signature）。 */
    private const val SIG_PROPERTY_STORE = 0xA0000009.toInt()

    /** "1SPS"（序列化 property store 的格式标识）。 */
    private const val SERIALIZED_PROPERTY_STORE = 0x53505331

    fun parse(bytes: ByteArray): ParsedShortcut? {
        if (bytes.size < HEADER_SIZE) return null
        if (u32(bytes, 0) != HEADER_SIZE) return null
        val flags = u32(bytes, 20)
        var offset = HEADER_SIZE

        if (flags and FLAG_HAS_ID_LIST != 0) {
            if (offset + 2 > bytes.size) return null
            val idListSize = u16(bytes, offset)
            offset += 2 + idListSize
        }
        if (offset > bytes.size) return null

        var targetPath: String? = null
        if (flags and FLAG_HAS_LINK_INFO != 0) {
            targetPath = readLinkInfo(bytes, offset)
            val linkInfoSize = u32(bytes, offset)
            offset += linkInfoSize
        }

        // StringData（按 flags 顺序）—— 只有算准它的长度才能找到后面的 ExtraData。
        val unicode = flags and FLAG_IS_UNICODE != 0
        for (bit in listOf(
            FLAG_HAS_NAME,
            FLAG_HAS_RELATIVE_PATH,
            FLAG_HAS_WORKING_DIR,
            FLAG_HAS_ARGUMENTS,
            FLAG_HAS_ICON_LOCATION,
        )) {
            if (flags and bit == 0) continue
            if (offset + 2 > bytes.size) return null
            val count = if (unicode) u16(bytes, offset) * 2 else u16(bytes, offset)
            offset += 2 + count
        }
        if (offset > bytes.size) return null

        return ParsedShortcut(targetPath = targetPath, aumid = readAumid(bytes, offset))
    }

    /** LinkInfo 里的本地路径（优先 unicode 版）。 */
    private fun readLinkInfo(bytes: ByteArray, start: Int): String? {
        if (start + 0x1C > bytes.size) return null
        val headerSize = u32(bytes, start + 4)
        val linkFlags = u32(bytes, start + 8)
        if (linkFlags and 0x1 == 0) return null // 没有本地路径（网络 / 仅 IDList）
        // 扩展头（>= 0x24）里才有 LocalBasePathUnicode 偏移。
        if (headerSize >= 0x24) {
            val unicodeOffset = u32(bytes, start + 28)
            if (unicodeOffset > 0) {
                readUtf16Z(bytes, start + unicodeOffset)?.let { return it }
            }
        }
        val ansiOffset = u32(bytes, start + 16)
        if (ansiOffset == 0) return null
        val end = indexOfZero(bytes, start + ansiOffset) ?: return null
        // 非 unicode 的 LocalBasePath 是 ANSI：用 ISO-8859-1 兜底读 ASCII 部分，
        // 中文路径读不对时返回 null（这类 .lnk 交给"扫描失败"路径处理，不影响发通知）。
        val text = String(bytes, start + ansiOffset, end - start - ansiOffset, Charsets.ISO_8859_1)
        return text.takeIf { it.isNotBlank() && !it.contains('?') }
    }

    /** ExtraData 里找 property store 块，再从里面取 AUMID。 */
    private fun readAumid(bytes: ByteArray, extraStart: Int): String? {
        var offset = extraStart
        while (offset + 8 <= bytes.size) {
            val blockSize = u32(bytes, offset)
            if (blockSize < 8 || offset + blockSize > bytes.size) return null
            val signature = u32(bytes, offset + 4)
            if (signature == SIG_PROPERTY_STORE) {
                return readAumidFromPropertyStore(bytes, offset + 8, offset + blockSize)
            }
            offset += blockSize
        }
        return null
    }

    /**
     * 序列化 property store：`"1SPS"` + format id + 一串 `[size][pid][type][value]`。
     *
     * 这里不硬编码各字段的偏移（不同写入方对 `type` 的对齐差 1-2 字节），
     * 而是先定位 AUMID 的 format id，再向后找 `pid == 5` 之后的第一个合法 UTF-16 字符串 ——
     * 对"同一条记录写法不同"是稳的，对"完全不是这个结构"则返回 null。
     */
    private fun readAumidFromPropertyStore(bytes: ByteArray, from: Int, to: Int): String? {
        // 真实文件的 property store 内容有两种开头：直接的 `"1SPS"`，或先有一个
        // DWORD 长度再跟 `"1SPS"`（实测 2026-11-05：nucleus 写的 .lnk 是后者）。
        val storeStart = when {
            matchesU32(bytes, from, to, SERIALIZED_PROPERTY_STORE) -> from
            matchesU32(bytes, from + 4, to, SERIALIZED_PROPERTY_STORE) -> from + 4
            else -> return null
        }
        val fmtIndex = indexOf(bytes, AUMID_FMTID, storeStart, to) ?: return null
        var cursor = fmtIndex + AUMID_FMTID.size
        while (cursor + 4 <= to) {
            if (u32(bytes, cursor) == 5) {
                readAumidValue(bytes, cursor + 4, to)?.let { return it }
            }
            cursor++
        }
        return null
    }

    private fun matchesU32(bytes: ByteArray, at: Int, to: Int, expected: Int): Boolean =
        at >= 0 && at + 4 <= minOf(to, bytes.size) && u32(bytes, at) == expected

    /** 从 [from, to) 里找第一个"像 AUMID"的 UTF-16LE 字符串。 */
    private fun readAumidValue(bytes: ByteArray, from: Int, to: Int): String? {
        var position = from
        val limit = minOf(to, from + 96)
        while (position + 2 <= limit) {
            val text = readUtf16Z(bytes, position, limit) ?: run {
                position++
                continue
            }
            if (isLikelyAumid(text)) return text
            position++
        }
        return null
    }

    private fun isLikelyAumid(text: String): Boolean =
        text.length in 3..256 && AUMID_PATTERN.matches(text)

    private fun readUtf16Z(bytes: ByteArray, start: Int, to: Int = bytes.size): String? {
        val bound = minOf(to, bytes.size)
        var end = start
        while (end + 1 < bound) {
            if (bytes[end] == 0.toByte() && bytes[end + 1] == 0.toByte()) break
            end += 2
        }
        if (end + 1 >= bound) return null
        if (end == start) return null
        val text = String(bytes, start, end - start, Charsets.UTF_16LE)
        return text.takeIf { it.all { ch -> ch.code in 0x20..0x7E } }
    }

    private fun indexOf(bytes: ByteArray, needle: ByteArray, from: Int, to: Int): Int? {
        outer@ for (i in from..(to - needle.size)) {
            for (j in needle.indices) {
                if (bytes[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return null
    }

    private fun indexOfZero(bytes: ByteArray, from: Int): Int? {
        if (from >= bytes.size) return null
        for (i in from until bytes.size) {
            if (bytes[i] == 0.toByte()) return i
        }
        return null
    }

    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, at: Int): Int =
        u16(bytes, at) or (u16(bytes, at + 2) shl 16)

    /** AUMID 的字符集：包标识风格（`Company.Product.Suffix`），也允许空格以外的常见符号。 */
    private val AUMID_PATTERN = Regex("""^[A-Za-z0-9][A-Za-z0-9._\-]{1,254}$""")
}

/** 便捷入口：解析单个 `.lnk` 文件；读不了时返回 null。 */
internal fun parseShortcut(bytes: ByteArray): ParsedShortcut? = ShortcutParser.parse(bytes)
