package dev.shinglem.hermes.feature.chat

import dev.shinglem.hermes.core.transport.GatewayEvent
import dev.shinglem.hermes.core.transport.GatewayEventTypes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatEventsTest {
    private fun event(type: String, payload: JsonObject) = GatewayEvent(
        type = type,
        sessionId = "runtime-1",
        payload = payload,
    )

    @Test
    fun `delta 取 text 字段`() {
        val parsed = event(GatewayEventTypes.MESSAGE_DELTA, buildJsonObject { put("text", "你") })
            .toChatStreamEvent()
        assertEquals("你", assertIs<ChatStreamEvent.Delta>(parsed).text)
    }

    @Test
    fun `complete 带文本与状态`() {
        val parsed = event(
            GatewayEventTypes.MESSAGE_COMPLETE,
            buildJsonObject {
                put("text", "完成")
                put("status", "complete")
            },
        ).toChatStreamEvent()

        val completed = assertIs<ChatStreamEvent.Completed>(parsed)
        assertEquals("完成", completed.text)
        assertEquals("complete", completed.status)
        assertFalse(completed.failed)
    }

    @Test
    fun `complete 出错时标记失败并带原因`() {
        val parsed = event(
            GatewayEventTypes.MESSAGE_COMPLETE,
            buildJsonObject {
                put("text", "")
                put("status", "error")
                put("error", "provider 500")
            },
        ).toChatStreamEvent()

        val completed = assertIs<ChatStreamEvent.Completed>(parsed)
        assertTrue(completed.failed)
        assertEquals("provider 500", completed.error)
    }

    @Test
    fun `tool start 取 id 名称 标签与参数`() {
        val parsed = event(
            GatewayEventTypes.TOOL_START,
            buildJsonObject {
                put("tool_id", "t1")
                put("name", "terminal")
                put("args", buildJsonObject { put("cmd", "ls") })
                put(
                    "labels",
                    kotlinx.serialization.json.buildJsonArray {
                        add(buildJsonObject { put("text", "Running command · ls") })
                    },
                )
            },
        ).toChatStreamEvent()

        val started = assertIs<ChatStreamEvent.ToolStarted>(parsed)
        assertEquals("t1", started.toolId)
        assertEquals("terminal", started.name)
        assertEquals("Running command · ls", started.label)
        assertTrue("ls" in started.arguments)
    }

    @Test
    fun `tool complete 优先用 result_text`() {
        val parsed = event(
            GatewayEventTypes.TOOL_COMPLETE,
            buildJsonObject {
                put("tool_id", "t1")
                put("name", "terminal")
                put("result_text", "a.txt")
                put("result", buildJsonObject { put("ignored", true) })
            },
        ).toChatStreamEvent()

        val completed = assertIs<ChatStreamEvent.ToolCompleted>(parsed)
        assertEquals("a.txt", completed.result)
        assertFalse(completed.failed)
    }

    @Test
    fun `usage 取 total`() {
        val parsed = event(
            GatewayEventTypes.SESSION_USAGE,
            buildJsonObject {
                put("usage", buildJsonObject { put("total", 24645) })
            },
        ).toChatStreamEvent()
        assertEquals(24645, assertIs<ChatStreamEvent.UsageUpdated>(parsed).totalTokens)
    }

    @Test
    fun `error 事件变成失败提示`() {
        val parsed = event(GatewayEventTypes.ERROR, buildJsonObject { put("message", "boom") })
            .toChatStreamEvent()
        assertEquals("boom", assertIs<ChatStreamEvent.Failure>(parsed).message)
    }

    @Test
    fun `无关事件被忽略`() {
        assertEquals(
            ChatStreamEvent.Ignored,
            event("sessions.changed", buildJsonObject { }).toChatStreamEvent(),
        )
    }

    @Test
    fun `interim 带上 already_streamed`() {
        val parsed = event(
            GatewayEventTypes.MESSAGE_INTERIM,
            buildJsonObject {
                put("text", "我先把目录列出来")
                put("already_streamed", true)
            },
        ).toChatStreamEvent()

        val interim = assertIs<ChatStreamEvent.Interim>(parsed)
        assertEquals("我先把目录列出来", interim.text)
        assertTrue(interim.alreadyStreamed)
    }

    @Test
    fun `request_cancel 只对审批生效`() {
        val approval = event(
            GatewayEventTypes.REQUEST_CANCEL,
            buildJsonObject {
                put("id", "srq-abc123")
                put("method", "approval")
                put("reason", "timeout")
            },
        ).toChatStreamEvent()
        assertEquals(
            "srq-abc123",
            assertIs<ChatStreamEvent.ApprovalCancelled>(approval).requestId,
        )

        val clarify = event(
            GatewayEventTypes.REQUEST_CANCEL,
            buildJsonObject {
                put("id", "srq-def456")
                put("method", "clarify")
            },
        ).toChatStreamEvent()
        assertEquals(
            "srq-def456",
            assertIs<ChatStreamEvent.ClarifyCancelled>(clarify).requestId,
        )
    }

    @Test
    fun `审批 payload 解析出命令 选项与会话`() {
        val pending = buildJsonObject {
            put("session_id", "runtime-1")
            put("request_id", "srq-abc123")
            put("command", "Get-CimInstance Win32_Process")
            put("description", "查询进程")
            put(
                "choices",
                buildJsonArray {
                    add(JsonPrimitive("once"))
                    add(JsonPrimitive("deny"))
                },
            )
        }.toPendingApproval(fromRequest = false)

        requireNotNull(pending)
        assertEquals("srq-abc123", pending.requestId)
        assertEquals("Get-CimInstance Win32_Process", pending.command)
        assertEquals(listOf("once", "deny"), pending.choices)
        assertEquals("runtime-1", pending.runtimeSessionId)
        assertFalse(pending.fromRequest)
    }

    @Test
    fun `没有 request_id 的审批 payload 被丢弃`() {
        assertNull(buildJsonObject { put("command", "ls") }.toPendingApproval(true))
    }

    @Test
    fun `clarify 单问解析出题干与选项`() {
        val payload = buildJsonObject {
            put("session_id", "runtime-1")
            put("request_id", "srq-clarify-1")
            put("question", "用哪个分支？")
            put(
                "choices",
                buildJsonArray {
                    add(JsonPrimitive("main"))
                    add(JsonPrimitive("dev"))
                },
            )
        }

        val pending = requireNotNull(payload.toPendingClarify("srq-clarify-1"))
        assertEquals("srq-clarify-1", pending.requestId)
        assertEquals("runtime-1", pending.runtimeSessionId)
        assertFalse(pending.isBatch)
        val question = pending.questions.single()
        assertEquals("q0", question.qid)
        assertEquals("用哪个分支？", question.question)
        assertEquals(listOf("main", "dev"), question.choices)
        assertFalse(question.multiSelect)
    }

    @Test
    fun `clarify 批量解析出多个 qid 与多选标记`() {
        val payload = buildJsonObject {
            put(
                "questions",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("qid", "q0")
                            put("question", "选哪些平台？")
                            put("multi_select", true)
                            put(
                                "choices",
                                buildJsonArray {
                                    add(JsonPrimitive("web"))
                                    add(JsonPrimitive("desktop"))
                                },
                            )
                        },
                    )
                    add(
                        buildJsonObject {
                            put("question", "备注？")
                        },
                    )
                },
            )
        }

        val pending = requireNotNull(payload.toPendingClarify("srq-clarify-2"))
        assertTrue(pending.isBatch)
        assertEquals(2, pending.questions.size)
        assertEquals("q0", pending.questions[0].qid)
        assertTrue(pending.questions[0].multiSelect)
        assertEquals(listOf("web", "desktop"), pending.questions[0].choices)
        // 没有 qid 时按位置补：第二题是 q1，且没有选项 = 自由文本
        assertEquals("q1", pending.questions[1].qid)
        assertTrue(pending.questions[1].choices.isEmpty())
    }

    @Test
    fun `没有题干的 clarify 被丢弃`() {
        assertNull(buildJsonObject { put("request_id", "srq-clarify-3") }.toPendingClarify("srq-clarify-3"))
    }
}
