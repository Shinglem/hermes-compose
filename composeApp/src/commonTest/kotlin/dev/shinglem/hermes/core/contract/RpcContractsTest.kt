package dev.shinglem.hermes.core.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RpcContractsTest {

    /**
     * 服务端会剥离 `profile` 的方法，本地闭集表里必须**显式**列出来。
     *
     * `problems()` 有 `profileAllowed` 兜底，所以漏写不会在本地报错 —— 代价是调用方看不到
     * 「这里必须带 profile」，于是漏传，跨 profile 时后端回 `4001 not_found`
     * （v0.5.3 的 `session.mutate` 就是这么挂的）。
     */
    @Test
    fun `PROFILE_IMPLICIT 的方法在闭集表里显式允许 profile`() {
        val missing = RpcContracts.PROFILE_IMPLICIT.filter { method ->
            val spec = RpcContracts.canonicalRpcContractSpec(method) ?: return@filter false
            "profile" !in spec.allowed
        }
        assertEquals(emptyList(), missing, "这些方法要在 allowed 里写出 profile")
    }

    @Test
    fun `session mutate 带 profile 时通过本地自检`() {
        val keys = setOf(
            "profile", "session_id", "request_id", "expected_revision", "operation", "payload",
        )
        assertNull(RpcContracts.problems(RpcMethods.SESSION_MUTATE, keys))
    }

    @Test
    fun `session mutate 缺 fence 时报缺少必填`() {
        val problem = RpcContracts.problems(
            RpcMethods.SESSION_MUTATE,
            setOf("profile", "session_id", "request_id", "operation", "payload"),
        )
        assertTrue(problem != null && "expected_revision" in problem, "实际：$problem")
    }
}
