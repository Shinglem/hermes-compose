package dev.shinglem.hermes.core.transport

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class JsonRpcTest {
    @Test
    fun `请求帧包含 jsonrpc id method 与 params`() {
        val frame = JsonRpc.encodeRequest(
            id = 7,
            method = "session.list",
            params = buildJsonObject { put("limit", 5) },
        )
        assertEquals(
            """{"jsonrpc":"2.0","id":7,"method":"session.list","params":{"limit":5}}""",
            frame,
        )
    }

    @Test
    fun `响应帧解析出 result`() {
        val frame = JsonRpc.parse("""{"jsonrpc":"2.0","id":7,"result":{"sessions":[]}}""")
        val response = assertIs<IncomingFrame.Response>(frame)
        assertEquals(JsonPrimitive(7), response.id)
        assertNull(response.error)
        assertEquals("""{"sessions":[]}""", response.result.toString())
    }

    @Test
    fun `响应帧带上服务端错误`() {
        val frame = JsonRpc.parse(
            """{"jsonrpc":"2.0","id":7,"error":{"code":4007,"message":"session not found"}}""",
        )
        val response = assertIs<IncomingFrame.Response>(frame)
        assertEquals(4007, response.error?.code)
        assertEquals("session not found", response.error?.message)
    }

    @Test
    fun `事件帧解析 type payload session_id 与 seq`() {
        val frame = JsonRpc.parse(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"s1","seq":12,"payload":{"text":"hi"}}}""",
        )
        val event = assertIs<IncomingFrame.Event>(frame).event
        assertEquals("message.delta", event.type)
        assertEquals("s1", event.sessionId)
        assertEquals(12L, event.seq)
        assertEquals("""{"text":"hi"}""", event.payload.toString())
    }

    @Test
    fun `server 到 client 的请求被单独识别`() {
        val frame = JsonRpc.parse(
            """{"jsonrpc":"2.0","id":"srv-1","method":"approval.pending","params":{"request_id":"r1"}}""",
        )
        val request = assertIs<IncomingFrame.ServerRequest>(frame)
        assertEquals("approval.pending", request.method)
        assertEquals(""""srv-1"""", request.id.toString())
    }

    @Test
    fun `非 JSON 对象帧返回 Malformed`() {
        assertIs<IncomingFrame.Malformed>(JsonRpc.parse("not json"))
        assertIs<IncomingFrame.Malformed>(JsonRpc.parse("""{"jsonrpc":"2.0"}"""))
    }

    @Test
    fun `应答帧保留原始 id`() {
        val result = JsonRpc.encodeResult(JsonPrimitive("srv-1"), JsonPrimitive(true))
        assertEquals("""{"jsonrpc":"2.0","id":"srv-1","result":true}""", result)

        val error = JsonRpc.encodeError(JsonPrimitive("srv-2"), -32601, "method not supported")
        assertEquals(
            """{"jsonrpc":"2.0","id":"srv-2","error":{"code":-32601,"message":"method not supported"}}""",
            error,
        )
    }
}
