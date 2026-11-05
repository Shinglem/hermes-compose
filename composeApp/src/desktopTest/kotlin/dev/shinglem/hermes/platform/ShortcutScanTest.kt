package dev.shinglem.hermes.platform

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 开始菜单快捷方式的扫描与复用判定。
 *
 * 两件事分开测：
 * - [ShortcutParser] 只认字节（MS-SHLLINK），用**按真实文件结构构造的**字节数组断言 ——
 *   本机实测过 4 个真实 `.lnk`（含 nucleus 自己写的那个），结构见 `ShortcutScan.kt` 的注释；
 * - [planShortcut] 是纯函数：复用/新建/回退的所有分支都能直接摆出来。
 */
class ShortcutScanTest {

    // ------------------------------------------------------------------
    //  .lnk 解析
    // ------------------------------------------------------------------

    @Test
    fun `解析出 target 与 AUMID`() {
        val bytes = buildLnk(target = "C:\\app\\hermes\\hermes.exe", aumid = "Shinglem.HermesDesktop.abcd1234")
        val parsed = parseShortcut(bytes)
        assertEquals("C:\\app\\hermes\\hermes.exe", parsed?.targetPath)
        assertEquals("Shinglem.HermesDesktop.abcd1234", parsed?.aumid)
    }

    @Test
    fun `没有 property store 时 aumid 为 null`() {
        val parsed = parseShortcut(buildLnk(target = "C:\\a\\b.exe", aumid = null))
        assertEquals("C:\\a\\b.exe", parsed?.targetPath)
        assertNull(parsed?.aumid)
    }

    @Test
    fun `只有 ANSI 路径的旧格式也能读出 target`() {
        val parsed = parseShortcut(buildLnk(target = "C:\\legacy\\app.exe", aumid = null, unicodePaths = false))
        assertEquals("C:\\legacy\\app.exe", parsed?.targetPath)
    }

    @Test
    fun `截断或垃圾数据返回 null 而不是抛异常`() {
        assertNull(parseShortcut(ByteArray(0)))
        assertNull(parseShortcut(ByteArray(40)))
        assertNull(parseShortcut(ByteArray(200) { 0x7F }))
        val valid = buildLnk(target = "C:\\a\\b.exe", aumid = "A.B.C")
        assertNull(parseShortcut(valid.copyOf(valid.size / 2)))
    }

    // ------------------------------------------------------------------
    //  复用判定
    // ------------------------------------------------------------------

    private val exe = "C:\\app\\hermes\\hermes-desktop-compose.exe"
    private val ownName = "Hermes Desktop Compose (abcd1234)"
    private val ownAumid = "Shinglem.HermesDesktop.abcd1234"

    private fun entry(
        name: String,
        target: String?,
        aumid: String?,
        pointsToTarget: Boolean = target != null && target.equals(exe, ignoreCase = true),
    ) = ShortcutEntry(
        relativeName = name,
        targetPath = target,
        aumid = aumid,
        pointsToTarget = pointsToTarget,
    )

    @Test
    fun `自己那条名字还在就按文件名沿用`() {
        val action = planShortcut(ownName, ownFileExists = true, ownAumid = ownAumid, entries = null)
        assertEquals(
            ShortcutAction.UseOwn(ownName, ownAumid, create = false, reason = "filename"),
            action,
        )
    }

    @Test
    fun `已有指向本程序且带 AUMID 的快捷方式就复用它`() {
        val action = planShortcut(
            ownName = ownName,
            ownFileExists = false,
            ownAumid = ownAumid,
            entries = listOf(
                entry("我的 Hermes", exe, "Shinglem.HermesDesktop.deadbeef"),
                entry("别的程序", "C:\\other\\other.exe", "Other.App"),
            ),
        )
        assertEquals(
            ShortcutAction.Reuse("我的 Hermes", "Shinglem.HermesDesktop.deadbeef", reason = "target"),
            action,
        )
    }

    @Test
    fun `指向本程序但没有 AUMID 时补写进这条而不是新建`() {
        val action = planShortcut(
            ownName = ownName,
            ownFileExists = false,
            ownAumid = ownAumid,
            entries = listOf(entry("Hermes", exe, aumid = null)),
        )
        val patch = assertIs<ShortcutAction.AddAumid>(action)
        assertEquals("Hermes", patch.entry.relativeName)
        assertEquals(ownAumid, patch.targetAumid)
        assertEquals("target", patch.reason)
    }

    @Test
    fun `多条命中时优先用已经带 AUMID 的那条`() {
        val action = planShortcut(
            ownName = ownName,
            ownFileExists = false,
            ownAumid = ownAumid,
            entries = listOf(
                entry("a 用户自己建的", exe, aumid = null),
                entry("b 旧版本留下的", exe, "Shinglem.HermesDesktop.oldhash"),
            ),
        )
        assertEquals(
            ShortcutAction.Reuse("b 旧版本留下的", "Shinglem.HermesDesktop.oldhash", reason = "target"),
            action,
        )
    }

    @Test
    fun `多条都没 AUMID 时按名字排序取第一条补写`() {
        val action = planShortcut(
            ownName = ownName,
            ownFileExists = false,
            ownAumid = ownAumid,
            entries = listOf(
                entry("z 后来的", exe, aumid = null),
                entry("a 先建的", exe, aumid = null),
            ),
        )
        assertEquals("a 先建的", assertIs<ShortcutAction.AddAumid>(action).entry.relativeName)
    }

    @Test
    fun `只有嵌套目录里的命中时不复用`() {
        val action = planShortcut(
            ownName = ownName,
            ownFileExists = false,
            ownAumid = ownAumid,
            entries = listOf(entry("hermes\\我的 Hermes", exe, "Shinglem.HermesDesktop.1")),
        )
        assertEquals(
            ShortcutAction.UseOwn(ownName, ownAumid, create = true, reason = "created", note = "nested"),
            action,
        )
    }

    @Test
    fun `一条都没有时新建`() {
        val action = planShortcut(
            ownName = ownName,
            ownFileExists = false,
            ownAumid = ownAumid,
            entries = listOf(entry("别的", "C:\\other\\other.exe", "Other.App")),
        )
        assertEquals(ShortcutAction.UseOwn(ownName, ownAumid, create = true, reason = "created"), action)
    }

    @Test
    fun `扫描失败退回新建而不是不发通知`() {
        val action = planShortcut(ownName, ownFileExists = false, ownAumid = ownAumid, entries = null)
        assertEquals(
            ShortcutAction.UseOwn(ownName, ownAumid, create = true, reason = "created", note = "scan-failed"),
            action,
        )
    }

    @Test
    fun `直接子项判定`() {
        assertTrue(entry("Hermes", null, null).isDirectChild)
        assertTrue(!entry("hermes\\\\Hermes", null, null).isDirectChild)
        assertTrue(!entry("hermes/Hermes", null, null).isDirectChild)
    }

    // ------------------------------------------------------------------
    //  "这条快捷方式是否指向本程序"的字节判定
    // ------------------------------------------------------------------

    @Test
    fun `指向本程序按字节判定且忽略大小写`() {
        assertTrue(pointsTo(buildLnk(target = exe, aumid = null), exe.uppercase()))
        assertTrue(pointsTo(buildLnk(target = exe, aumid = null, unicodePaths = false), exe))
        assertTrue(!pointsTo(buildLnk(target = "C:\\other\\other.exe", aumid = null), exe))
    }

    @Test
    fun `拿不到 exe 路径时不认任何快捷方式`() {
        val bytes = buildLnk(target = exe, aumid = null)
        assertTrue(!pointsTo(bytes, null))
        assertTrue(!pointsTo(bytes, "  "))
    }

    // ------------------------------------------------------------------
    //  开始菜单扫描
    // ------------------------------------------------------------------

    @Test
    fun `扫描开始菜单递归标记相对名与目标`() {
        val root = Files.createTempDirectory("hermes-shortcuts").toFile()
        try {
            File(root, "Hermes.lnk").writeBytes(buildLnk(target = exe, aumid = null))
            File(root, "Tools").mkdirs()
            File(root, "Tools").resolve("Idea.lnk")
                .writeBytes(buildLnk(target = "C:\\idea\\idea64.exe", aumid = "JetBrains.Idea"))
            File(root, "notes.txt").writeText("不是快捷方式")

            val entries = assertNotNull(scanStartMenuShortcuts(root, exe)).sortedBy { it.relativeName }
            assertEquals(2, entries.size)
            assertEquals("Hermes", entries[0].relativeName)
            assertTrue(entries[0].pointsToTarget)
            assertEquals("Tools\\Idea", entries[1].relativeName)
            assertTrue(!entries[1].pointsToTarget)
            assertTrue(!entries[1].isDirectChild)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `开始菜单目录不存在时返回空表而不是失败`() {
        val missing = File(Files.createTempDirectory("hermes-shortcuts").toFile(), "nope")
        try {
            assertTrue(scanStartMenuShortcuts(missing, exe)?.isEmpty() == true)
        } finally {
            missing.parentFile?.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    //  构造 .lnk 字节（MS-SHLLINK，只构造解析器认的那几段）
    // ------------------------------------------------------------------

    private fun buildLnk(target: String, aumid: String?, unicodePaths: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        val flags = FLAG_HAS_ID_LIST or FLAG_HAS_LINK_INFO or FLAG_HAS_RELATIVE_PATH or
            (if (unicodePaths) FLAG_IS_UNICODE else 0)

        // --- header（76 字节）---
        out.writeU32(0x4C)
        out.write(ByteArray(16)) // LinkCLSID：解析不依赖，填零
        out.writeU32(flags)
        out.write(ByteArray(0x4C - 20 - 4)) // 其余 header 字段解析不用

        // --- LinkTargetIDList（空列表）---
        out.writeU16(0)

        // --- LinkInfo ---
        val ansi = target.toByteArray(Charsets.ISO_8859_1)
        val uni = target.toByteArray(Charsets.UTF_16LE)
        val headerSize = if (unicodePaths) 0x24 else 0x1C
        val ansiOffset = headerSize
        val ansiLength = ansi.size + 1
        val uniOffset = ansiOffset + ansiLength
        val uniLength = if (unicodePaths) uni.size + 2 else 0
        val linkInfoSize = headerSize + ansiLength + uniLength
        out.writeU32(linkInfoSize)
        out.writeU32(headerSize)
        out.writeU32(0x1) // VolumeIDAndLocalBasePath
        out.writeU32(0) // VolumeIDOffset：解析不看
        out.writeU32(ansiOffset)
        out.writeU32(0) // CommonNetworkRelativeLinkOffset
        out.writeU32(0) // CommonPathSuffixOffset
        if (unicodePaths) {
            out.writeU32(uniOffset)
            out.writeU32(0) // CommonPathSuffixOffsetUnicode
        }
        out.write(ansi)
        out.write(0)
        if (unicodePaths) {
            out.write(uni)
            out.write(0)
            out.write(0)
        }

        // --- StringData：只有一个 RELATIVE_PATH ---
        val relative = "..\\hermes-desktop-compose.exe"
        out.writeU16(relative.length)
        if (unicodePaths) {
            out.write(relative.toByteArray(Charsets.UTF_16LE))
        } else {
            out.write(relative.toByteArray(Charsets.ISO_8859_1))
        }

        // --- ExtraData：一个无关块 + property store ---
        out.writeU32(8 + 8)
        out.writeU32(0xA0000003.toInt())
        out.write(ByteArray(8))

        if (aumid != null) {
            val store = propertyStore(aumid)
            out.writeU32(8 + store.size)
            out.writeU32(0xA0000009.toInt())
            out.write(store)
        }
        return out.toByteArray()
    }

    /**
     * 序列化 property store：`[storeSize]["1SPS"][formatId][81][pid=5][type][0][aumid UTF-16Z]`。
     *
     * 这份布局照着本机实测的 `.lnk`（nucleus 自己写的那个）构造，包含那个"前面多一个长度
     * DWORD"的写法 —— 解析器必须两种都认。
     */
    private fun propertyStore(aumid: String): ByteArray {
        val value = aumid.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)
        val body = ByteArrayOutputStream()
        body.writeU32(SERIALIZED_PROPERTY_STORE)
        body.write(AUMID_FMTID)
        body.writeU32(81) // valueSize
        body.writeU32(5) // PKEY_AppUserModel_ID
        body.writeU32(0x1F00) // 真实文件里 type 的位置差 1 字节，故意按原样构造
        body.writeU32(0)
        body.write(value)
        val payload = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.writeU32(payload.size + 4) // 序列化 store 自带的长度（含这 4 字节）
        out.write(payload)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeU32(value: Int) {
        writeU16(value and 0xFFFF)
        writeU16((value ushr 16) and 0xFFFF)
    }

    private companion object {
        const val FLAG_HAS_ID_LIST = 0x1
        const val FLAG_HAS_LINK_INFO = 0x2
        const val FLAG_HAS_RELATIVE_PATH = 0x8
        const val FLAG_IS_UNICODE = 0x80
        const val SERIALIZED_PROPERTY_STORE = 0x53505331

        /** {9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3} 的小端字节。 */
        val AUMID_FMTID = byteArrayOf(
            0x55, 0x28, 0x4C, 0x9F.toByte(),
            0x79, 0x9F.toByte(),
            0x39, 0x4B,
            0xA8.toByte(), 0xD0.toByte(), 0xE1.toByte(), 0xD4.toByte(),
            0x2D, 0xE1.toByte(), 0xD5.toByte(), 0xF3.toByte(),
        )
    }
}
