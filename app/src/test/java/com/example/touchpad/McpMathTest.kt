package com.example.touchpad

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 的纯逻辑。
 *
 * ★ 钉的是**不报错但结果错**的三处：请求头被第二个冒号切开、SSE 里挑错响应、
 *   工具参数漏一个。它们的共同点是「连得上、有工具、就是不对」。
 */
class McpMathTest {

    @Test
    fun `请求头按第一个冒号切，值里的冒号保留`() {
        val h = McpMath.parseHeaders(
            """
            Authorization: Bearer a:b:c
            X-Env: prod

            没冒号的行
            """.trimIndent()
        )
        assertEquals("Bearer a:b:c", h["Authorization"])
        assertEquals("prod", h["X-Env"])
        assertEquals(2, h.size)
    }

    @Test
    fun `请求头来回走两趟一模一样`() {
        val h = linkedMapOf("Authorization" to "Bearer x", "X-Env" to "prod")
        assertEquals(h, McpMath.parseHeaders(McpMath.formatHeaders(h)))
    }

    @Test
    fun `从普通 JSON 里挑出对应那条`() {
        val body = """{"jsonrpc":"2.0","id":2,"result":{"tools":[]}}"""
        assertEquals(2, McpMath.pickResult(body, 2)!!.optInt("id"))
        assertNull(McpMath.pickResult(body, 3))
    }

    @Test
    fun `从 SSE 里跳过通知，挑出对应那条`() {
        val body = buildString {
            append("event: message\n")
            append("""data: {"jsonrpc":"2.0","method":"notifications/progress"}""").append('\n')
            append('\n')
            append("event: message\n")
            append("""data: {"jsonrpc":"2.0","id":7,"result":{"ok":1}}""").append('\n')
        }
        assertEquals(7, McpMath.pickResult(body, 7)!!.optInt("id"))
        assertNull(McpMath.pickResult(body, 8))
    }

    @Test
    fun `报错要抛出原文`() {
        val o = JSONObject("""{"jsonrpc":"2.0","id":1,"error":{"message":"未授权"}}""")
        var msg = ""
        try {
            McpMath.resultOf(o)
        } catch (e: IllegalStateException) {
            msg = e.message.orEmpty()
        }
        assertTrue("错误原文必须带出来，否则只能看到「失败」两个字", msg.contains("未授权"))
    }

    @Test
    fun `工具清单里有名字的才算`() {
        val result = JSONObject().put(
            "tools",
            JSONArray()
                .put(JSONObject().put("name", "get_weather").put("description", "查天气"))
                .put(JSONObject().put("description", "没有名字的"))
        )
        val tools = McpMath.toolsOf(result)
        assertEquals(1, tools.size)
        assertEquals("get_weather", tools[0].name)
    }

    @Test
    fun `工具的参数表从 inputSchema 拆出来`() {
        val t = McpTool(
            "get_weather", "查天气",
            JSONObject().put("type", "object").put(
                "properties",
                JSONObject()
                    .put("city", JSONObject().put("type", "string").put("description", "城市"))
                    .put("days", JSONObject().put("type", "integer"))
            ).put("required", JSONArray().put("city")).toString()
        )
        val ht = McpMath.handTool(t)
        assertEquals(2, ht.params.size)
        assertEquals(listOf("city"), ht.required)
        assertEquals("城市", ht.params.first { it.name == "city" }.desc)
        // MCP 的执行是 JSON-RPC，不是拼 URL —— 不能带一个假的 exec 出去。
        assertNull(ht.exec)
    }

    @Test
    fun `工具返回的是 content 数组，要拼成一段文字`() {
        val result = JSONObject().put(
            "content",
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", "晴，"))
                .put(JSONObject().put("type", "text").put("text", "26 度"))
        )
        assertEquals("晴，26 度", McpMath.contentText(result))
        assertTrue(McpMath.isError(JSONObject().put("isError", true)))
    }
}
