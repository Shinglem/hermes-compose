package dev.shinglem.hermes.core.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class GatewayEnsureTest {

    private val ready =
        """{"client_code_sha":"2bd378ed","detail":null,"endpoint":{"api_origin":"http://127.0.0.1:59739",""" +
            """"authority_epoch":1,"capabilities":["durable-admission-v1","event-replay-v1"],""" +
            """"code_sha":"2bd378ed","control_home":null,"instance_id":"97cbe1c6",""" +
            """"profile_id":"S:\\proj\\release-test\\data\\agent","runtime_protocol":1,"supervisor":"none"},""" +
            """"reason_code":null,"state":"ready"}"""

    @Test
    fun `ready 输出解析出端点与能力`() {
        val verdict = assertIs<EnsureVerdict.Ready>(GatewayEnsure.parse(ready, 0))
        assertEquals("http://127.0.0.1:59739", verdict.endpoint.apiOrigin)
        assertEquals("97cbe1c6", verdict.endpoint.instanceId)
        assertEquals(1, verdict.endpoint.runtimeProtocol)
        assertEquals(listOf("durable-admission-v1", "event-replay-v1"), verdict.endpoint.capabilities)
        assertEquals("2bd378ed", verdict.clientCodeSha)
    }

    @Test
    fun `stderr 混进 stdout 时取最后一行 JSON`() {
        val noisy = "warming providers\n$ready\n"
        assertIs<EnsureVerdict.Ready>(GatewayEnsure.parse(noisy, 0))
    }

    @Test
    fun `非 ready 状态带出 reason_code 与 detail`() {
        val verdict = assertIs<EnsureVerdict.Unavailable>(
            GatewayEnsure.parse("""{"state":"starting","reason_code":"deadline","detail":"cold boot","endpoint":null}""", 5),
        )
        assertEquals("starting", verdict.state)
        assertEquals("deadline", verdict.reasonCode)
        assertEquals("cold boot", verdict.detail)
    }

    @Test
    fun `authorization 归到不可用而不是解析失败`() {
        val verdict = assertIs<EnsureVerdict.Unavailable>(
            GatewayEnsure.parse("""{"state":"inaccessible","reason_code":"authorization","endpoint":null}""", 4),
        )
        assertEquals("authorization", verdict.reasonCode)
    }

    @Test
    fun `没有 JSON 时判为解析失败`() {
        assertIs<EnsureVerdict.Unparseable>(GatewayEnsure.parse("not json at all", 2))
    }

    @Test
    fun `runtime_protocol 不匹配就拒绝`() {
        val wrong = ready.replace("\"runtime_protocol\":1", "\"runtime_protocol\":2")
        val verdict = assertIs<EnsureVerdict.Unparseable>(GatewayEnsure.parse(wrong, 0))
        assertEquals(true, verdict.detail.contains("runtime_protocol"))
    }

    @Test
    fun `api_origin 不允许 path query 或凭据`() {
        assertNull(GatewayEnsure.apiOriginProblem("http://127.0.0.1:9119"))
        assertNull(GatewayEnsure.apiOriginProblem("https://host.example:443"))
        assertEquals(true, GatewayEnsure.apiOriginProblem("http://127.0.0.1:9119/api")!!.contains("path"))
        assertEquals(true, GatewayEnsure.apiOriginProblem("http://127.0.0.1:9119?x=1")!!.contains("path"))
        assertEquals(true, GatewayEnsure.apiOriginProblem("http://user:pw@127.0.0.1:9119")!!.contains("凭据"))
        assertEquals(true, GatewayEnsure.apiOriginProblem("http://127.0.0.1")!!.contains("端口"))
        assertEquals(true, GatewayEnsure.apiOriginProblem("ftp://127.0.0.1:21")!!.contains("http"))
    }

    @Test
    fun `profile_id 不是绝对路径就拒绝`() {
        val relative = ready.replace("""S:\\proj\\release-test\\data\\agent""", "data/agent")
        assertEquals(false, relative == ready, "替换未生效，测试本身失效")
        assertIs<EnsureVerdict.Unparseable>(GatewayEnsure.parse(relative, 0))
    }
}
