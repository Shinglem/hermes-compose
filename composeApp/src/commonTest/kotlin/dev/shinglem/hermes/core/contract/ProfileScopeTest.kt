package dev.shinglem.hermes.core.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S3 · `ProfileScope` 的归一化规则。
 *
 * 这个类存在的意义就是**消灭"同一个 null 有两种含义"**，所以测试的重点全在
 * "什么输入落成哪个值"上 —— 一旦某条归一化错了，就会出现"界面看着是 A 档案、
 * 实际改的是默认档"这类难查的问题（0.5.4 的 `4001 not_found` 家族）。
 */
class ProfileScopeTest {

    @Test
    fun `null 与空串归一成 None`() {
        assertTrue(ProfileScope.of(null).isNone)
        assertTrue(ProfileScope.of("").isNone)
        assertTrue(ProfileScope.of("   ").isNone)
    }

    /**
     * `default` 归一成 None 而不是显式作用域。
     *
     * 后端把"不传"和"传 default"当同一件事，而 `default` 是后端自己派生的名字；
     * 若当成显式作用域，会拼出 `?profile=default`——在某些接口上与"不传"的行为并不一致。
     */
    @Test
    fun `default 归一成 None`() {
        assertTrue(ProfileScope.of("default").isNone)
        assertTrue(ProfileScope.of(" default ").isNone)
    }

    @Test
    fun `all 是独立的值不是 None`() {
        val all = ProfileScope.of("all")
        assertEquals("all", all.query)
        assertTrue(!all.isNone, "all 必须与 None 区分：前者要拼进 query，后者不拼")
        assertNotEquals(ProfileScope.None, all)
    }

    @Test
    fun `普通档案名原样保留`() {
        assertEquals("butler", ProfileScope.of("butler").query)
        assertEquals("ordia-dev", ProfileScope.of(" ordia-dev ").query)
    }

    /**
     * query 为 null 表示"调用方不该加 `?profile=`"。
     *
     * 这条是 `RestRepository.withProfile` 的分支依据，写错会让所有"与档案无关"的
     * 接口都带上 `?profile=`，而后端对未知参数是**报错**还是**忽略**取决于具体接口。
     */
    @Test
    fun `None 的 query 是 null`() {
        assertNull(ProfileScope.None.query)
        assertNull(ProfileScope.of(null).query)
        assertNull(ProfileScope.of("default").query)
    }

    @Test
    fun `All 的 query 是 all`() {
        assertEquals("all", ProfileScope.All.query)
    }
}
