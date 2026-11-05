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
    /** 是否**指向当前程序**（按字节判定，见 [pointsTo]）。 */
    val pointsToTarget: Boolean,
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
 * 扫描 + 判定之后该做什么（纯数据，真正的副作用在 `NativeNotifications` 里）。
 *
 * [reason] 只用于日志，取值固定为 `filename` / `target` / `created` / `patching-failed`
 * （`note` 补充细节，例如 `nested`、`scan-failed`）。
 */
internal sealed interface ShortcutAction {
    val reason: String
    val note: String?

    /** 用我们自己的名字 + 自己的 AUMID；[create] 决定 `REQUIRE_CREATE` 还是 `REQUIRE_NO_CREATE`。 */
    data class UseOwn(
        val appName: String,
        val aumid: String,
        val create: Boolean,
        override val reason: String,
        override val note: String? = null,
    ) : ShortcutAction

    /** 复用一条已经带 AUMID 的已有条目（`REQUIRE_NO_CREATE`）。 */
    data class Reuse(
        val appName: String,
        val aumid: String,
        override val reason: String,
        override val note: String? = null,
    ) : ShortcutAction

    /**
     * 条目指向本程序但**没有 AUMID**：先把 [targetAumid] 补写进它的 `.lnk`，成功后按 Reuse 处理。
     * 补写失败 → 退回新建（`reason = patching-failed`）。
     */
    data class AddAumid(
        val entry: ShortcutEntry,
        val targetAumid: String,
        override val reason: String = "target",
        override val note: String? = null,
    ) : ShortcutAction
}

/**
 * 判定（纯函数）。
 *
 * 老逻辑只认文件名 `<产品名> (<home 哈希>).lnk`，于是「用户自己建过指向同一 exe 的快捷方式」
 * 「旧版本用固定名建过」都会再建一个。现口径按「**是否已有指向本程序的快捷方式**」：
 *
 * 1. 自己那条名字还在 → 沿用（`filename`，最省事，也不读任何 `.lnk`）；
 * 2. 名字不在就扫开始菜单（[entries]）：
 *    - 直接子项 + 指向本程序 + 带 AUMID → 复用它的名字与 AUMID（`target`）；
 *    - 直接子项 + 指向本程序 + **不带 AUMID** → 把 AUMID 补写进它，再复用它（`target`），
 *      **不再新建第二个入口**；
 *    - 只有嵌套子目录里的命中 → 没法可靠复用（库自己拼 `Programs\<appName>.lnk`），新建（`note=nested`）；
 *    - 一条都没命中 → 新建；
 * 3. 扫描失败（[entries] 为 null）→ 退回老逻辑新建（`note=scan-failed`）—— **不能因此不发通知**。
 *
 * @param ownName 我们自己那套命名（带 `<home>` 哈希）。
 * @param ownFileExists `<ownName>.lnk` 是否已经存在。
 * @param ownAumid 我们自己算出来的 AUMID（新建时用，补写时也写这个）。
 * @param entries 扫描结果；null 表示扫描失败。
 */
internal fun planShortcut(
    ownName: String,
    ownFileExists: Boolean,
    ownAumid: String,
    entries: List<ShortcutEntry>?,
): ShortcutAction {
    if (ownFileExists) {
        return ShortcutAction.UseOwn(ownName, ownAumid, create = false, reason = "filename")
    }
    if (entries == null) {
        return ShortcutAction.UseOwn(ownName, ownAumid, create = true, reason = "created", note = "scan-failed")
    }
    val matches = entries.filter { it.pointsToTarget }
    if (matches.isEmpty()) {
        return ShortcutAction.UseOwn(ownName, ownAumid, create = true, reason = "created")
    }
    val direct = matches.filter { it.isDirectChild }
    if (direct.isEmpty()) {
        // 嵌套项：名字里带路径分隔符，`appName` 的语义就不再是"文件名"，不赌。
        return ShortcutAction.UseOwn(ownName, ownAumid, create = true, reason = "created", note = "nested")
    }
    direct.firstOrNull { !it.aumid.isNullOrBlank() }?.let { entry ->
        return ShortcutAction.Reuse(entry.relativeName, entry.aumid!!, reason = "target")
    }
    // 有直接子项但都没 AUMID：补写进第一条（排序保证确定性）。
    val target = direct.sortedBy { it.relativeName }.first()
    return ShortcutAction.AddAumid(target, ownAumid)
}

/** `Programs` 根目录下的 `.lnk` 相对名去掉后缀。 */
private fun lnkBaseName(path: String): String = path.removeSuffix(".lnk").removeSuffix(".LNK").removeSuffix(".Lnk")

/**
 * 归一化后的执行计划（[ShortcutAction] 解决完之后的结果）。
 *
 * [appName] 直接喂给 `WindowsNotificationCenter.initialize`；
 * [create] 为 true 表示允许库新建（`ShortcutPolicy.REQUIRE_CREATE`）。
 */
internal data class ShortcutPlan(
    val appName: String,
    val aumid: String,
    val create: Boolean,
    /** 日志用：`filename` / `target` / `created` / `patching-failed`。 */
    val reason: String,
    /** 日志用的细节：`nested` / `scan-failed`。 */
    val note: String? = null,
    /** 复用了哪条（相对 `Programs\` 的名字）；null = 没复用。 */
    val reused: String? = null,
)

/**
 * 扫 `Programs\` 下的全部 `.lnk`（递归）。
 *
 * @param exePath 当前进程的 exe；用于判定"是否指向本程序"。
 * @return null 表示扫描失败（目录读不了 / 权限不足）—— 调用方按老逻辑新建，**不要抛到启动路径上**。
 */
internal fun scanStartMenuShortcuts(programsDir: File, exePath: String?): List<ShortcutEntry>? = runCatching {
    if (!programsDir.isDirectory) return emptyList()
    val root = programsDir.toPath()
    programsDir.walkTopDown()
        .onEnter { it.isDirectory }
        .filter { it.isFile && it.extension.equals("lnk", ignoreCase = true) }
        .mapNotNull { file ->
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@mapNotNull null
            val parsed = runCatching { parseShortcut(bytes) }.getOrNull()
            val relative = runCatching { root.relativize(file.toPath()).toString() }.getOrNull()
                ?: return@mapNotNull null
            ShortcutEntry(
                relativeName = lnkBaseName(relative),
                targetPath = parsed?.targetPath,
                aumid = parsed?.aumid,
                pointsToTarget = pointsTo(bytes, exePath),
            )
        }
        .toList()
}.getOrNull()

/** 从 `.lnk` 里读出来的两样东西。 */
internal data class ParsedShortcut(val targetPath: String?, val aumid: String?)

/**
 * "这条 `.lnk` 是否指向 [exePath]"（纯字节判定）。
 *
 * 目标路径在 `.lnk` 里可能出现两次、两种编码：LinkInfo 的 ANSI 路径与 IDList / unicode 路径。
 * 所以把字节按 `Latin1`（ANSI 侧）与 `UTF-16LE`（unicode 侧，奇偶两种对齐各试一次）各解一次，
 * 用大小写不敏感的 `contains` 判断 —— 不依赖任何字段偏移，也不引 COM / 子进程。
 */
internal fun pointsTo(bytes: ByteArray, exePath: String?): Boolean {
    if (exePath.isNullOrBlank()) return false
    return decodeVariants(bytes).any { it.contains(exePath, ignoreCase = true) }
}

/** 三种解码视角：ANSI / UTF-16LE 偶数对齐 / UTF-16LE 奇数对齐。 */
private fun decodeVariants(bytes: ByteArray): List<String> = buildList {
    add(String(bytes, Charsets.ISO_8859_1))
    add(String(bytes, Charsets.UTF_16LE))
    if (bytes.size > 3) add(String(bytes, 1, bytes.size - 1, Charsets.UTF_16LE))
}

/**
 * 解析 `.lnk`（MS-SHLLINK）。
 *
 * 只取两样：TargetPath（LinkInfo 的 LocalBasePath，日志与排查用）与 AUMID
 * （property store 里的 `PKEY_AppUserModel_ID`）。**不引 COM**：两者在文件格式里都有明确位置。
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
    internal const val SIG_PROPERTY_STORE = 0xA0000009.toInt()

    /** "1SPS"（序列化 property store 的格式标识）。 */
    internal const val SERIALIZED_PROPERTY_STORE = 0x53505331

    /** ExtraData 终止块（4 个 0 字节）。 */
    internal const val SIG_TERMINAL = 0x00000000

    fun parse(bytes: ByteArray): ParsedShortcut? {
        val extraStart = extraDataStart(bytes) ?: return null
        val targetPath = linkInfoPath(bytes)
        return ParsedShortcut(targetPath = targetPath, aumid = readAumid(bytes, extraStart))
    }

    /**
     * ExtraData 的起始偏移（按 flags 逐段跳过 header / IDList / LinkInfo / StringData）。
     *
     * 解析与补写共用这一个函数：补写只往 ExtraData 里插块，前面的字节一个都不动。
     */
    internal fun extraDataStart(bytes: ByteArray): Int? {
        if (bytes.size < HEADER_SIZE) return null
        if (u32(bytes, 0) != HEADER_SIZE) return null
        val flags = u32(bytes, 20)
        var offset = HEADER_SIZE
        if (flags and FLAG_HAS_ID_LIST != 0) {
            if (offset + 2 > bytes.size) return null
            offset += 2 + u16(bytes, offset)
        }
        if (offset > bytes.size) return null
        if (flags and FLAG_HAS_LINK_INFO != 0) {
            if (offset + 4 > bytes.size) return null
            offset += u32(bytes, offset)
        }
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
        return offset.takeIf { it <= bytes.size }
    }

    private fun linkInfoPath(bytes: ByteArray): String? {
        val flags = u32(bytes, 20)
        if (flags and FLAG_HAS_LINK_INFO == 0) return null
        var offset = HEADER_SIZE
        if (flags and FLAG_HAS_ID_LIST != 0) offset += 2 + u16(bytes, offset)
        if (offset + 0x1C > bytes.size) return null
        val headerSize = u32(bytes, offset + 4)
        val linkFlags = u32(bytes, offset + 8)
        if (linkFlags and 0x1 == 0) return null // 没有本地路径（网络 / 仅 IDList）
        // 扩展头（>= 0x24）里才有 LocalBasePathUnicode 偏移。
        if (headerSize >= 0x24) {
            val unicodeOffset = u32(bytes, offset + 28)
            if (unicodeOffset > 0) {
                readUtf16Z(bytes, offset + unicodeOffset)?.let { return it }
            }
        }
        val ansiOffset = u32(bytes, offset + 16)
        if (ansiOffset == 0) return null
        val end = indexOfZero(bytes, offset + ansiOffset) ?: return null
        val text = String(bytes, offset + ansiOffset, end - offset - ansiOffset, Charsets.ISO_8859_1)
        return text.takeIf { it.isNotBlank() && !it.contains('?') }
    }

    /** ExtraData 里找 property store 块，再从里面取 AUMID。 */
    internal fun readAumid(bytes: ByteArray, extraStart: Int): String? =
        propertyStoreBlock(bytes, extraStart)?.let { block ->
            readAumidFromStores(bytes, block.contentStart, block.contentEnd)
        }

    internal class Block(val start: Int, val size: Int, val signature: Int, val contentStart: Int, val contentEnd: Int)

    /** 按 ExtraData 的块链表找 `0xA0000009`；没有返回 null。 */
    internal fun propertyStoreBlock(bytes: ByteArray, extraStart: Int): Block? {
        var offset = extraStart
        while (offset + 8 <= bytes.size) {
            val size = u32(bytes, offset)
            if (size < 8 || offset + size > bytes.size) return null
            val signature = u32(bytes, offset + 4)
            if (signature == SIG_PROPERTY_STORE) {
                return Block(offset, size, signature, offset + 8, offset + size)
            }
            offset += size
        }
        return null
    }

    /**
     * 一个 property store 块里可能有**多个**序列化 store（实测：nucleus 自己写的那个就有两个，
     * 第一个是 AUMID、第二个是别的 shell 属性）。逐个找 AUMID 的 format id。
     */
    private fun readAumidFromStores(bytes: ByteArray, from: Int, to: Int): String? {
        var cursor = from
        while (cursor + 8 <= to) {
            val size = u32(bytes, cursor)
            // 序列化 store：`[size]"1SPS"...`，size 含自身 4 字节。
            if (size < 8 || cursor + size > to) return null
            if (u32(bytes, cursor + 4) != SERIALIZED_PROPERTY_STORE) return null
            val fmtIndex = indexOf(bytes, AUMID_FMTID, cursor, cursor + size)
            if (fmtIndex != null) {
                readAumidProperty(bytes, fmtIndex + AUMID_FMTID.size, cursor + size)?.let { return it }
            }
            cursor += size
        }
        return null
    }

    /**
     * AUMID 属性值：`[valueSize][pid = 5][type 区 8 字节][字符数][UTF-16LE 字符串]`。
     *
     * 不硬编码各字段偏移（不同写入方对 type / 字符数差 1-2 字节），而是从 pid 之后
     * 向后找第一个"像 AUMID"的 UTF-16 字符串 —— 对"同一条记录写法不同"是稳的。
     */
    private fun readAumidProperty(bytes: ByteArray, from: Int, to: Int): String? {
        var cursor = from
        while (cursor + 4 <= to) {
            if (u32(bytes, cursor) == 5) {
                readAumidValue(bytes, cursor + 4, to)?.let { return it }
            }
            cursor++
        }
        return null
    }

    private fun readAumidValue(bytes: ByteArray, from: Int, to: Int): String? {
        var position = from
        val limit = minOf(to, from + 96)
        while (position + 2 <= limit) {
            val text = readUtf16Z(bytes, position, limit)
            if (text != null && isLikelyAumid(text)) return text
            position++
        }
        return null
    }

    private fun isLikelyAumid(text: String): Boolean =
        text.length in 3..256 && AUMID_PATTERN.matches(text)

    internal fun readUtf16Z(bytes: ByteArray, start: Int, to: Int = bytes.size): String? {
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

    internal fun u32(bytes: ByteArray, at: Int): Int = u16(bytes, at) or (u16(bytes, at + 2) shl 16)

    /** AUMID 的字符集：包标识风格（`Company.Product.Suffix`）。 */
    private val AUMID_PATTERN = Regex("""^[A-Za-z0-9][A-Za-z0-9._\-]{1,254}$""")
}

/** 便捷入口：解析单个 `.lnk` 字节。 */
internal fun parseShortcut(bytes: ByteArray): ParsedShortcut? = ShortcutParser.parse(bytes)

