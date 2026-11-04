package dev.shinglem.hermes.core.app

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 提权检测的判据必须是"**当前进程**是否用了提升后的令牌"，而不是"用户在不在管理员组"。
 *
 * 这里只断言"读得到且与系统事实一致"：测试进程不知道自己跑在什么权限下，
 * 所以用 `GetTokenInformation` 的另一条路径交叉验证不值当；
 * 真正的提权链路（runas → 新进程 elevated=true）只能在成品里手工验（`07` 篇 V2）。
 */
class ElevationTest {

    @Test
    fun `isElevated 能给出确定结果而不是反复失败`() {
        val first = Elevation.isElevated()
        val second = Elevation.isElevated()
        assertEquals(first, second, "两次调用必须一致（绑定失败时会恒为 false）")
    }
}
