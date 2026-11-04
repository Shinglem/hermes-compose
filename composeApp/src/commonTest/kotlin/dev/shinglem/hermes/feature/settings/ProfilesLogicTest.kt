package dev.shinglem.hermes.feature.settings

import dev.shinglem.hermes.core.contract.ProfileAdminDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 配置档案的名字规则与展示字段（纯函数，不碰网络）。 */
class ProfilesLogicTest {

    @Test
    fun `slug 把大写与空格换成合法档案名`() {
        assertEquals("dev-work", slugifyProfileName("Dev Work"))
        assertEquals("a-b", slugifyProfileName("  A B  "))
        assertEquals("zh-1", slugifyProfileName("zh_1".replace('_', '-')))
    }

    @Test
    fun `slug 去掉首尾分隔符并截断到 64`() {
        assertEquals("abc", slugifyProfileName("--abc--"))
        assertEquals(64, slugifyProfileName("a".repeat(100)).length)
    }

    @Test
    fun `档案名校验`() {
        assertTrue(isProfileNameValid("dev-work"))
        assertTrue(isProfileNameValid("a1"))
        assertFalse(isProfileNameValid("Dev-Work"))  // 大写不合法
        assertFalse(isProfileNameValid("-dev"))       // 不能以分隔符开头
        assertFalse(isProfileNameValid(""))           // 空
        assertFalse(isProfileNameValid("dev work"))   // 空格
        assertFalse(isProfileNameValid("a".repeat(65))) // 超长
    }

    @Test
    fun `modelLabel 兼容只配了其中一半的情况`() {
        assertEquals("deepseek / deepseek-chat", ProfileAdminDto(provider = "deepseek", model = "deepseek-chat").modelLabel)
        assertEquals("deepseek-chat", ProfileAdminDto(model = "deepseek-chat").modelLabel)
        assertEquals("", ProfileAdminDto().modelLabel)
    }

    @Test
    fun `新建草稿的 cloneFrom 默认克隆 default，可切成不克隆`() {
        assertEquals("default", ProfileCreateDraft().cloneFrom)
        assertFalse(ProfileCreateDraft(name = "").canSubmit)
        assertTrue(ProfileCreateDraft(name = "dev-work").canSubmit)
        assertFalse(ProfileCreateDraft(name = "Dev").canSubmit)
    }
}
