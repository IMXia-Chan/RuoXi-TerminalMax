package com.example.touchpad

import org.json.JSONArray
import org.json.JSONObject

/**
 * **Anthropic 那套协议** —— 它和 OpenAI 兼容的那一套**不是同一件事**。
 *
 * 他 2026-11-06 说「然后再加 anthenrpic」。而在此之前 [LlmCatalog] 的文件头里已经写死过一句：
 * 「把 Anthropic 的 `/v1/messages` 混进来不是『多支持一家』，是**多一套协议**」。现在补的就是这一套。
 *
 * ## 差在哪儿（只列会**静默出错**的那几处）
 *
 * | | OpenAI 兼容 | Anthropic |
 * |---|---|---|
 * | 路径 | `/v1/chat/completions` | `/v1/messages` |
 * | 鉴权 | `Authorization: Bearer sk-…` | `x-api-key: sk-…` + `anthropic-version` |
 * | 系统提示 | `messages` 里的一条 `role=system` | **顶层 `system` 字段** |
 * | 工具调用 | `assistant.tool_calls[].function.arguments`（字符串） | 内容块 `{type:"tool_use", input}`（对象） |
 * | 工具回执 | 一条 `role=tool` 的消息 | `role=user` 里的 `{type:"tool_result"}` 块 |
 * | 响应 | `choices[0].message` | `content[]` 数组 + `stop_reason` |
 * | 流 | `choices[0].delta` | `content_block_delta` 等事件 |
 * | `max_tokens` | 可选 | **必填** |
 *
 * ★★ 这里**只做翻译，不改上层**：上面每一处差异都在这一层被抹平，
 *   [AiAgent.chatOnce] / [AiAgent.readSseFull] / `runLoop` 拿到的仍然是 OpenAI 形状。
 *   理由很实际 —— 那几段代码里藏着本地模型、前缀缓存、取消、首字窗口几十条踩过的坑，
 *   为了多接一家去动它们，等于把那些坑重新踩一遍。
 *
 * ★ 唯一的例外是**它自己的地址和鉴权头**：那两个没法在正文里抹平，只能在
 *   [AiAgent.chatOnce] 里分支。
 */
internal object AnthropicProto {

    /** Anthropic 要求带上这个头，不带会被拒。写死在这一版用的值上。 */
    const val VERSION = "2023-06-01"

    /** 它**必填** `max_tokens`；我们这边不带。给一个够用又不至于失控的值。 */
    private const val DEFAULT_MAX_TOKENS = 4096

    /**
     * 这个地址是不是 Anthropic 那套。
     *
     * ★ 判据只能是**地址**：模型清单里存的是「一行一家」，没有「协议」这一栏，
     *   加一栏就要动存档格式。而地址是用户填的时候就看得到的，认错了他自己改得回来。
     */
    fun isAnthropic(endpoint: String): Boolean {
        val e = endpoint.trim().lowercase()
        return e.contains("anthropic.com") || e.endsWith("/v1/messages") || e.endsWith("/messages")
    }

    // ------------------------------------------------------------------
    // 出门：OpenAI 请求体 → Anthropic 请求体
    // ------------------------------------------------------------------

    fun requestBody(openAi: JSONObject): JSONObject {
        val out = JSONObject().put("model", openAi.optString("model"))

        val sys = StringBuilder()
        val msgs = JSONArray()
        // ★ 工具回执必须**连续几条并进同一条 user 消息**。拆成几条 user，
        //   Anthropic 会回 400（tool_result 必须紧跟在带 tool_use 的 assistant 后面）。
        var toolResults: JSONArray? = null

        fun flushTools() {
            val p = toolResults ?: return
            if (p.length() > 0) msgs.put(JSONObject().put("role", "user").put("content", p))
            toolResults = null
        }

        val src = openAi.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until src.length()) {
            val m = src.optJSONObject(i) ?: continue
            when (m.optString("role")) {
                "system" -> {
                    flushTools()
                    val t = textOf(m.opt("content"))
                    if (t.isNotBlank()) {
                        if (sys.isNotEmpty()) sys.append("\n\n")
                        sys.append(t)
                    }
                }
                "user" -> {
                    flushTools()
                    msgs.put(JSONObject().put("role", "user").put("content", blocksOf(m.opt("content"))))
                }
                "assistant" -> {
                    flushTools()
                    val blocks = JSONArray()
                    val t = textOf(m.opt("content"))
                    if (t.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", t))
                    m.optJSONArray("tool_calls")?.let { tcs ->
                        for (j in 0 until tcs.length()) {
                            val tc = tcs.optJSONObject(j) ?: continue
                            val fn = tc.optJSONObject("function") ?: continue
                            blocks.put(
                                JSONObject()
                                    .put("type", "tool_use")
                                    .put("id", tc.optString("id"))
                                    .put("name", fn.optString("name"))
                                    .put("input", argsOf(fn.optString("arguments")))
                            )
                        }
                    }
                    // 空 assistant 消息 Anthropic 不收 —— 而它在我们这边是可能出现的。
                    if (blocks.length() > 0) {
                        msgs.put(JSONObject().put("role", "assistant").put("content", blocks))
                    }
                }
                "tool" -> {
                    if (toolResults == null) toolResults = JSONArray()
                    toolResults!!.put(
                        JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", m.optString("tool_call_id"))
                            .put("content", textOf(m.opt("content")))
                    )
                }
            }
        }
        flushTools()

        if (sys.isNotEmpty()) out.put("system", sys.toString())
        out.put("messages", msgs)
        out.put("max_tokens", openAi.optInt("max_tokens", DEFAULT_MAX_TOKENS).coerceAtLeast(1))
        if (openAi.has("temperature")) out.put("temperature", openAi.optDouble("temperature", 0.7))
        if (openAi.has("top_p")) out.put("top_p", openAi.optDouble("top_p", 1.0))
        if (openAi.optBoolean("stream", false)) out.put("stream", true)

        openAi.optJSONArray("tools")?.let { arr ->
            val tools = JSONArray()
            for (i in 0 until arr.length()) {
                val fn = arr.optJSONObject(i)?.optJSONObject("function") ?: continue
                tools.put(
                    JSONObject()
                        .put("name", fn.optString("name"))
                        .put("description", fn.optString("description"))
                        .put(
                            "input_schema",
                            fn.optJSONObject("parameters")
                                ?: JSONObject().put("type", "object").put("properties", JSONObject())
                        )
                )
            }
            if (tools.length() > 0) {
                out.put("tools", tools)
                out.put(
                    "tool_choice",
                    when (openAi.optString("tool_choice")) {
                        "required" -> JSONObject().put("type", "any")
                        "none" -> JSONObject().put("type", "none")
                        else -> JSONObject().put("type", "auto")
                    }
                )
            }
        }
        return out
    }

    /**
     * 一条消息的内容 → Anthropic 的 content。
     *
     * 字符串原样；数组要挑出文字块和图片块（OpenAI 的 `image_url` 里是一个
     * `data:image/jpeg;base64,…` 的地址，Anthropic 要的是拆开的 base64 源）。
     */
    private fun blocksOf(v: Any?): Any {
        if (v !is JSONArray) return textOf(v)
        val out = JSONArray()
        for (i in 0 until v.length()) {
            val b = v.optJSONObject(i) ?: continue
            when (b.optString("type")) {
                "text" -> out.put(JSONObject().put("type", "text").put("text", b.optString("text")))
                "image_url" -> imageBlock(b.optJSONObject("image_url")?.optString("url").orEmpty())
                    ?.let { out.put(it) }
            }
        }
        return if (out.length() > 0) out else ""
    }

    private fun imageBlock(url: String): JSONObject? {
        val marker = ";base64,"
        val at = url.indexOf(marker)
        // 不是 base64 内联的（http 图片地址）：Anthropic 另有 `type:"url"`,
        // 而那要求它能从公网抓到 —— 截图那条路本来就不出手机，这里不做。
        if (at < 0) return null
        val media = url.removePrefix("data:").substringBefore(";").ifBlank { "image/jpeg" }
        return JSONObject()
            .put("type", "image")
            .put(
                "source",
                JSONObject()
                    .put("type", "base64")
                    .put("media_type", media)
                    .put("data", url.substring(at + marker.length))
            )
    }

    private fun argsOf(raw: String): JSONObject {
        val s = raw.trim()
        if (s.isEmpty()) return JSONObject()
        return try {
            JSONObject(s)
        } catch (_: Exception) {
            // ★ 模型拼坏了参数也照样发出去：换成「发不出去」等于把一次可以自纠的错误
            //   变成一次没有回音的失败。原样塞进一个字段里，至少它自己能看见。
            JSONObject().put("_raw", s)
        }
    }

    /** 内容取文字。字符串、数组（块）、null 三种都要能读。 */
    fun textOf(v: Any?): String = when (v) {
        null, JSONObject.NULL -> ""
        is String -> v
        is JSONArray -> buildString {
            for (i in 0 until v.length()) {
                val b = v.optJSONObject(i) ?: continue
                if (b.optString("type") == "text") append(b.optString("text"))
            }
        }
        else -> v.toString()
    }

    // ------------------------------------------------------------------
    // 回来：Anthropic 响应 → OpenAI 响应
    // ------------------------------------------------------------------

    fun responseBody(text: String): String {
        val src = try {
            JSONObject(text)
        } catch (_: Exception) {
            // 不是 JSON 就原样交回去 —— 上层的报错里会带上原文，那才有用。
            return text
        }
        val content = StringBuilder()
        val calls = JSONArray()
        src.optJSONArray("content")?.let { arr ->
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                when (b.optString("type")) {
                    "text" -> content.append(b.optString("text"))
                    "tool_use" -> calls.put(
                        JSONObject()
                            .put("id", b.optString("id"))
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", b.optString("name"))
                                    .put("arguments", (b.opt("input") ?: JSONObject()).toString())
                            )
                    )
                }
            }
        }
        val msg = JSONObject().put("role", "assistant").put("content", content.toString())
        if (calls.length() > 0) msg.put("tool_calls", calls)
        val out = JSONObject().put(
            "choices",
            JSONArray().put(
                JSONObject()
                    .put("message", msg)
                    .put("finish_reason", finishOf(src.optString("stop_reason")))
            )
        )
        src.optJSONObject("usage")?.let { u ->
            out.put(
                "usage",
                JSONObject()
                    .put("prompt_tokens", u.optInt("input_tokens"))
                    .put("completion_tokens", u.optInt("output_tokens"))
            )
        }
        return out.toString()
    }

    fun finishOf(reason: String): String = when (reason) {
        "tool_use" -> "tool_calls"
        "max_tokens" -> "length"
        else -> "stop"
    }
}

/**
 * **把 Anthropic 的流一条一条翻成 OpenAI 的 chunk。**
 *
 * 它是**有状态的**：工具的编号在 Anthropic 那边是「内容块的序号」（文字块也占一个），
 * 而 [AiAgent.readSseFull] 按连续序号归组 —— 两个编号体系必须在这里对上，
 * 否则第二个工具的参数字符串会接到第一个工具的 id 上，而那种错**不会报错**，
 * 只会让她调一个参数莫名其妙缺失的工具。
 *
 * ★ 用类而不是 object：状态会串。一次请求一个新实例。
 */
internal class AnthropicStream {

    private var nextTool = 0
    private val blockTool = HashMap<Int, Int>()
    private var promptTokens = 0
    private var completionTokens = 0
    private var stopReason = ""

    /** 收到 `message_stop`。调用方看到它就可以不读了。 */
    var finished = false
        private set

    /** → null = 这一条事件不产生 chunk（ping / content_block_stop 之类）。 */
    fun feed(data: String): JSONObject? {
        val ev = try {
            JSONObject(data)
        } catch (_: Exception) {
            return null
        }
        when (ev.optString("type")) {
            "message_start" -> {
                ev.optJSONObject("message")?.optJSONObject("usage")
                    ?.let { promptTokens = it.optInt("input_tokens") }
                return null
            }

            "content_block_start" -> {
                val b = ev.optJSONObject("content_block") ?: return null
                if (b.optString("type") != "tool_use") return null
                val mine = nextTool++
                blockTool[ev.optInt("index")] = mine
                return chunk(
                    toolCalls = JSONArray().put(
                        JSONObject()
                            .put("index", mine)
                            .put("id", b.optString("id"))
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject().put("name", b.optString("name")).put("arguments", "")
                            )
                    )
                )
            }

            "content_block_delta" -> {
                val d = ev.optJSONObject("delta") ?: return null
                when (d.optString("type")) {
                    "text_delta" -> {
                        val t = d.optString("text")
                        return if (t.isEmpty()) null else chunk(text = t)
                    }
                    "input_json_delta" -> {
                        val mine = blockTool[ev.optInt("index")] ?: return null
                        val pj = d.optString("partial_json")
                        return if (pj.isEmpty()) null else chunk(
                            toolCalls = JSONArray().put(
                                JSONObject()
                                    .put("index", mine)
                                    .put("function", JSONObject().put("arguments", pj))
                            )
                        )
                    }
                }
                return null
            }

            "message_delta" -> {
                ev.optJSONObject("delta")?.optString("stop_reason")
                    ?.takeIf { it.isNotEmpty() }?.let { stopReason = it }
                ev.optJSONObject("usage")?.let {
                    completionTokens = it.optInt("output_tokens", completionTokens)
                }
                return chunk().apply {
                    put("finish_reason", AnthropicProto.finishOf(stopReason))
                    put(
                        "usage",
                        JSONObject()
                            .put("prompt_tokens", promptTokens)
                            .put("completion_tokens", completionTokens)
                    )
                }
            }

            "message_stop" -> {
                finished = true
                return null
            }

            // ★ 流里报错**必须抛**。静默结束的话，上层会把它读成「她这一轮什么都没说」，
            //   而他看到的是一个不吭声的助手，日志里一个字都没有。
            "error" -> throw java.io.IOException(
                "模型那边报错:" + ev.optJSONObject("error")?.optString("message").orEmpty()
            )

            else -> return null
        }
    }

    private fun chunk(toolCalls: JSONArray? = null, text: String = ""): JSONObject {
        val delta = JSONObject()
        if (text.isNotEmpty()) delta.put("content", text)
        if (toolCalls != null) delta.put("tool_calls", toolCalls)
        return JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta)))
    }
}
