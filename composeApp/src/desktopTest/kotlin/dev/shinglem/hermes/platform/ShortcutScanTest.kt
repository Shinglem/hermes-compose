package dev.shinglem.hermes.platform

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun entry(name: String, target: String?, aumid: String?) =
        ShortcutEntry(relativeName = name, targetPath = target, aumid = aumid)

    @Test
    fun `自己那条名字还在就按文件名复用`() {
        val plan = planShortcut(
            ownName = "Hermes Desktop Compose (abcd1234)",
            ownFileExists = true,
            exePath = exe,
            entries = listOf(entry("别人的", "C:\\other.exe", "X.Y")),
        )
        assertEquals("Hermes Desktop Compose (abcd1234)", plan.appName)
        assertTrue(!plan.create)
        assertEquals("filename", plan.reason)
        assertNull(plan.aumid)
    }

    @Test
    fun `已有指向本程序且带 AUMID 的快捷方式就复用`() {
        val plan = planShortcut(
            ownName = "Hermes Desktop Compose (abcd1234)",
            ownFileExists = false,
            exePath = exe,
            entries = listOf(
                entry("我的 Hermes", exe, "Shinglem.HermesDesktop.deadbeef"),
                entry("别的程序", "C:\\other.exe", "Other.App"),
            ),
        )
        assertEquals("我的 Hermes", plan.appName)
        assertEquals("Shinglem.HermesDesktop.deadbeef", plan.aumid)
        assertTrue(!plan.create)
        assertEquals("target", plan.reason)
        assertEquals("我的 Hermes", plan.reused)
    }

    @Test
    fun `路径比较忽略大小写与斜杠方向`() {
        val plan = planShortcut(
            ownName = "own",
            ownFileExists = false,
            exePath = "c:/app/hermes/hermes-desktop-compose.exe",
            entries = listOf(entry("Hermes", "C:\\APP\\Hermes\\hermes-desktop-compose.exe", "Shinglem.HermesDesktop.1")),
        )
        assertEquals("target", plan.reason)
    }

    @Test
    fun `命中的快捷方式没有 AUMID 时仍按老规矩新建`() {
        val plan = planShortcut(
            ownName = "Hermes Desktop Compose (abcd1234)",
            ownFileExists = false,
            exePath = exe,
            entries = listOf(entry("我的 Hermes", exe, aumid = null)),
        )
        assertEquals("Hermes Desktop Compose (abcd1234)", plan.appName)
        assertTrue(plan.create)
        assertEquals("no-aumid", plan.reason)
    }

    @Test
    fun `只有嵌套目录里的命中时不复用`() {
        val plan = planShortcut(
            ownName = "own",
            ownFileExists = false,
            exePath = exe,
            entries = listOf(entry("hermes\\我的 Hermes", exe, "Shinglem.HermesDesktop.1")),
        )
        assertTrue(plan.create)
        assertEquals("nested", plan.reason)
    }

    @Test
    fun `一条都没有时新建`() {
        val plan = planShortcut(
            ownName = "own",
            ownFileExists = false,
            exePath = exe,
            entries = listOf(entry("别的", "C:\\other.exe", "Other.App")),
        )
        assertTrue(plan.create)
        assertEquals("created", plan.reason)
        assertNull(plan.reused)
    }

    @Test
    fun `扫描失败退回按文件名判定`() {
        val plan = planShortcut(ownName = "own", ownFileExists = false, exePath = exe, entries = null)
        assertTrue(plan.create)
        assertEquals("fallback", plan.reason)
    }

    @Test
    fun `多条命中时优先用旧版本自己留下的那条`() {
        val plan = planShortcut(
            ownName = "own",
            ownFileExists = false,
            exePath = exe,
            entries = listOf(
                entry("a 用户自己建的", exe, "User.Made.This"),
                entry("b 旧版本留下的", exe, "Shinglem.HermesDesktop.oldhash"),
            ),
        )
        assertEquals("b 旧版本留下的", plan.appName)
        assertEquals("Shinglem.HermesDesktop.oldhash", plan.aumid)
    }

    @Test
    fun `拿不到 exe 路径时不猜`() {
        val plan = planShortcut(
            ownName = "own",
            ownFileExists = false,
            exePath = null,
            entries = listOf(entry("Hermes", exe, "Shinglem.HermesDesktop.1")),
        )
        assertTrue(plan.create)
        assertEquals("created", plan.reason)
    }

    @Test
    fun `直接子项判定`() {
        assertTrue(entry("Hermes", null, null).isDirectChild)
        assertTrue(!entry("hermes\\\\Hermes", null, null).isDirectChild)
        assertTrue(!entry("hermes/Hermes", null, null).isDirectChild)
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
        val out = ByteArrayOutputStream()
        out.writeU32(0x6D) // 序列化块自报的长度（解析不用，但要还原真实结构）
        out.writeU32(SERIALIZED_PROPERTY_STORE)
        out.write(AUMID_FMTID)
        out.writeU32(81) // valueSize
        out.writeU32(5) // PKEY_AppUserModel_ID
        out.writeU32(0x1F00) // 真实文件里 type 的位置差 1 字节，故意按原样构造
        out.writeU32(0)
        out.write(value)
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
