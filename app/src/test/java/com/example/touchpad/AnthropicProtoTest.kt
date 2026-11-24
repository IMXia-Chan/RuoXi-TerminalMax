package com.example.touchpad

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anthropic 那套协议的翻译层。
 *
 * ★ 这些用例钉的是**会静默出错**的地方：system 没搬到顶层（她就当没看见那段规矩）、
 *   工具回执拆成几条（对方回 400，而他只看到「失败」）、两个工具的编号撞在一起
 *   （参数字符串接到别的工具上，不报错）。
 */
class AnthropicProtoTest {

    private fun user(text: String) = JSONObject().put("role", "user").put("content", text)
    private fun sys(text: String) = JSONObject().put("role", "system").put("content", text)

    // ---------------------------------------------------------------- 判据

    @Test
    fun `认得 anthropic 的地址`() {
        assertTrue(AnthropicProto.isAnthropic("https://api.anthropic.com/v1/messages"))
        assertTrue(AnthropicProto.isAnthropic("https://api.anthropic.com"))
        assertTrue(AnthropicProto.isAnthropic("https://my-gateway.example.com/v1/messages"))
        assertFalse(AnthropicProto.isAnthropic("https://api.deepseek.com/v1/chat/completions"))
        assertFalse(AnthropicProto.isAnthropic("http://127.0.0.1:8080"))
    }

    // ---------------------------------------------------------------- 请求

    @Test
    fun `system 搬到顶层，多段按顺序拼起来`() {
        val body = JSONObject()
            .put("model", "claude-sonnet-5-5")
            .put(
                "messages",
                JSONArray().put(sys("第一段")).put(sys("第二段"))
                    .put(user("你好"))
            )
        val out = AnthropicProto.requestBody(body)

        assertEquals("第一段\n\n第二段", out.optString("system"))
        // 顶层 system 之后，messages 里**不能再有** system —— 留着会被拒。
        assertEquals(1, out.optJSONArray("messages")!!.length())
        assertEquals("user", out.optJSONArray("messages")!!.optJSONObject(0)!!.optString("role"))
    }

    @Test
    fun `max_tokens 必须有，没给就补一个`() {
        val body = JSONObject().put("model", "m")
            .put("messages", JSONArray().put(user("hi")))
        assertTrue(AnthropicProto.requestBody(body).optInt("max_tokens") > 0)

        val withOne = JSONObject(body.toString()).put("max_tokens", 7)
        assertEquals(7, AnthropicProto.requestBody(withOne).optInt("max_tokens"))
    }

    @Test
    fun `工具定义摊平成 name、description、input_schema`() {
        val tools = JSONArray().put(
            JSONObject().put("type", "function").put(
                "function",
                JSONObject()
                    .put("name", "get_weather")
                    .put("description", "查天气")
                    .put("parameters", JSONObject().put("type", "object"))
            )
        )
        val body = JSONObject().put("model", "m")
            .put("messages", JSONArray().put(user("hi")))
            .put("tools", tools)
            .put("tool_choice", "auto")
        val out = AnthropicProto.requestBody(body)
        val t = out.optJSONArray("tools")!!.optJSONObject(0)!!

        assertEquals("get_weather", t.optString("name"))
        assertEquals("查天气", t.optString("description"))
        assertEquals("object", t.optJSONObject("input_schema")!!.optString("type"))
        // OpenAI 的 `type/function` 外壳不能留在 Anthropic 的请求里。
        assertFalse(t.has("function"))
        assertEquals("auto", out.optJSONObject("tool_choice")!!.optString("type"))
    }

    @Test
    fun `assistant 的 tool_calls 变成 tool_use 内容块`() {
        val assistant = JSONObject()
            .put("role", "assistant")
            .put("content", "我看一下")
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject().put("id", "call_1").put("type", "function").put(
                        "function",
                        JSONObject().put("name", "get_weather")
                            .put("arguments", """{"city":"上海"}""")
                    )
                )
            )
        val body = JSONObject().put("model", "m")
            .put("messages", JSONArray().put(user("天气")).put(assistant))
        val blocks = AnthropicProto.requestBody(body)
            .optJSONArray("messages")!!.optJSONObject(1)!!.optJSONArray("content")!!

        assertEquals("text", blocks.optJSONObject(0)!!.optString("type"))
        assertEquals("tool_use", blocks.optJSONObject(1)!!.optString("type"))
        assertEquals("call_1", blocks.optJSONObject(1)!!.optString("id"))
        // ★ arguments 是字符串，input 必须是**对象** —— 否则对方会拒。
        assertEquals("上海", blocks.optJSONObject(1)!!.optJSONObject("input")!!.optString("city"))
    }

    @Test
    fun `连续几条工具回执并进同一条 user 消息`() {
        val asst = JSONObject().put("role", "assistant").put("content", "").put(
            "tool_calls",
            JSONArray()
                .put(
                    JSONObject().put("id", "c1").put(
                        "function",
                        JSONObject().put("name", "a").put("arguments", "{}")
                    )
                )
                .put(
                    JSONObject().put("id", "c2").put(
                        "function",
                        JSONObject().put("name", "b").put("arguments", "{}")
                    )
                )
        )
        val body = JSONObject().put("model", "m").put(
            "messages",
            JSONArray().put(user("做两件事")).put(asst)
                .put(JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", "{}"))
                .put(JSONObject().put("role", "tool").put("tool_call_id", "c2").put("content", "{}"))
        )
        val msgs = AnthropicProto.requestBody(body).optJSONArray("messages")!!

        // user / assistant / user(两条 tool_result 在里面)
        assertEquals(3, msgs.length())
        val results = msgs.optJSONObject(2)!!.optJSONArray("content")!!
        assertEquals(2, results.length())
        assertEquals("tool_result", results.optJSONObject(0)!!.optString("type"))
        assertEquals("c2", results.optJSONObject(1)!!.optString("tool_use_id"))
    }

    // ---------------------------------------------------------------- 响应

    @Test
    fun `响应里的文字和 stop_reason 换成 OpenAI 形状`() {
        val raw = JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "你好")))
            .put("stop_reason", "end_turn")
            .put("usage", JSONObject().put("input_tokens", 11).put("output_tokens", 2))
            .toString()
        val out = JSONObject(AnthropicProto.responseBody(raw))
        val choice = out.optJSONArray("choices")!!.optJSONObject(0)!!

        assertEquals("你好", choice.optJSONObject("message")!!.optString("content"))
        assertEquals("stop", choice.optString("finish_reason"))
        assertEquals(11, out.optJSONObject("usage")!!.optInt("prompt_tokens"))
        assertEquals(2, out.optJSONObject("usage")!!.optInt("completion_tokens"))
    }

    @Test
    fun `响应里的 tool_use 换成 tool_calls`() {
        val raw = JSONObject()
            .put(
                "content",
                JSONArray().put(
                    JSONObject().put("type", "tool_use")
                        .put("id", "toolu_1").put("name", "get_weather")
                        .put("input", JSONObject().put("city", "上海"))
                )
            )
            .put("stop_reason", "tool_use")
            .toString()
        val msg = JSONObject(AnthropicProto.responseBody(raw))
            .optJSONArray("choices")!!.optJSONObject(0)!!.optJSONObject("message")!!

        val call = msg.optJSONArray("tool_calls")!!.optJSONObject(0)!!
        assertEquals("toolu_1", call.optString("id"))
        assertEquals("get_weather", call.optJSONObject("function")!!.optString("name"))
        // arguments 必须还原成**字符串** —— runLoop 那边是按字符串解析的。
        assertEquals(
            "上海",
            JSONObject(call.optJSONObject("function")!!.optString("arguments")).optString("city")
        )
    }

    @Test
    fun `不是 JSON 的响应原样交回去`() {
        assertEquals("<html>bad gateway</html>", AnthropicProto.responseBody("<html>bad gateway</html>"))
    }

    // ---------------------------------------------------------------- 流

    private fun ev(vararg pairs: Pair<String, Any?>): String {
        val o = JSONObject()
        pairs.forEach { (k, v) -> if (v != null) o.put(k, v) }
        return o.toString()
    }

    @Test
    fun `文字增量逐条变成 content`() {
        val s = AnthropicStream()
        assertNull(s.feed(ev("type" to "message_start")))
        assertEquals(
            "你",
            s.feed(
                ev(
                    "type" to "content_block_delta", "index" to 0,
                    "delta" to JSONObject().put("type", "text_delta").put("text", "你")
                )
            )!!.optJSONArray("choices")!!.optJSONObject(0)!!
                .optJSONObject("delta")!!.optString("content")
        )
        assertNull(s.feed(ev("type" to "message_stop")))
        assertTrue(s.finished)
    }

    @Test
    fun `两个工具拿到连续编号，不和文字块的序号混`() {
        val s = AnthropicStream()
        s.feed(ev("type" to "message_start"))
        // 文字块占 index 0，工具块分别是 1、2 —— 我们这边必须变成 0、1。
        s.feed(
            ev(
                "type" to "content_block_start", "index" to 1,
                "content_block" to JSONObject().put("type", "tool_use")
                    .put("id", "t1").put("name", "a")
            )
        )
        val second = s.feed(
            ev(
                "type" to "content_block_start", "index" to 2,
                "content_block" to JSONObject().put("type", "tool_use")
                    .put("id", "t2").put("name", "b")
            )
        )!!
        val call = second.optJSONArray("choices")!!.optJSONObject(0)!!
            .optJSONObject("delta")!!.optJSONArray("tool_calls")!!.optJSONObject(0)!!
        assertEquals(1, call.optInt("index"))

        // 第二个工具的参数必须挂到 index 1，不能挂到 0。
        val args = s.feed(
            ev(
                "type" to "content_block_delta", "index" to 2,
                "delta" to JSONObject().put("type", "input_json_delta")
                    .put("partial_json", """{"x":1}""")
            )
        )!!
        val tc = args.optJSONArray("choices")!!.optJSONObject(0)!!
            .optJSONObject("delta")!!.optJSONArray("tool_calls")!!.optJSONObject(0)!!
        assertEquals(1, tc.optInt("index"))
    }

    @Test
    fun `message_delta 带上 usage 和 finish_reason`() {
        val s = AnthropicStream()
        s.feed(
            ev(
                "type" to "message_start",
                "message" to JSONObject().put(
                    "usage", JSONObject().put("input_tokens", 30)
                )
            )
        )
        val out = s.feed(
            ev(
                "type" to "message_delta",
                "delta" to JSONObject().put("stop_reason", "tool_use"),
                "usage" to JSONObject().put("output_tokens", 9)
            )
        )!!
        assertEquals("tool_calls", out.optString("finish_reason"))
        assertEquals(30, out.optJSONObject("usage")!!.optInt("prompt_tokens"))
        assertEquals(9, out.optJSONObject("usage")!!.optInt("completion_tokens"))
    }

    @Test
    fun `流里报错要抛出去，不能静默结束`() {
        val s = AnthropicStream()
        var thrown = false
        try {
            s.feed(
                ev(
                    "type" to "error",
                    "error" to JSONObject().put("type", "overloaded_error")
                        .put("message", "服务繁忙")
                )
            )
        } catch (_: java.io.IOException) {
            thrown = true
        }
        assertTrue("流里的 error 必须抛出来 —— 静默结束会被读成「她什么都没说」", thrown)
    }
}
