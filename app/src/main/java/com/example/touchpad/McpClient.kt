package com.example.touchpad

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * **MCP 服务器的执行层**：握手、拉工具清单、调工具。
 *
 * ## 它在这套东西里的位置
 *
 * [HandRegistry.all] 里多出一种手（`id = "mcp:<服务器>"`，kind 仍是 [HandMath.KIND_CLOUD]），
 * 于是 `list_hands` 会把它报出来、`use_hand` 会走到这里 —— **上层的工具循环一个字都不用改**。
 * 「云端兜底:关」那个总闸也照样管着它（见 [AiAgent.useHand] 里 KIND_CLOUD 那一段）。
 *
 * ## ★ 工具清单从缓存读，不在 `list_hands` 时联网
 *
 * `list_hands` 是每一轮都可能被调的东西，而 MCP 的一次往返要几百毫秒到几秒。
 * 把网络塞进去，症状是「她每轮都先卡一下」—— 而且看起来像模型慢。
 * 所以：**面板上点「刷新工具」才联网**，`list_hands` 只读上次拉到的。
 * [Hand.fetchedAtMs] 用**真实拉取时间**，这样「这份清单是 N 小时前的」是真话。
 *
 * ## ★ 每次调用都重新握手
 *
 * MCP 的 Streamable HTTP 允许 `Mcp-Session-Id` 续会话，但也允许无状态。
 * 这一版选**每次都 initialize**：多两次往返，换来「不依赖服务器记不记得我」。
 * 会话 id 一旦过期，症状是「有时能用、有时说找不到工具」，而那种错很难查。
 */
object McpClient {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_TEXT = 4000

    private fun fail(why: String): JSONObject = JSONObject().put("ok", false).put("error", why)

    /** 现在能摆出来的 MCP 手。工具清单为空的不摆 —— 空菜单只会让她白点一次。 */
    fun hands(): List<Hand> {
        val now = System.currentTimeMillis()
        return McpStore.servers().filter { it.enabled }.mapNotNull { s ->
            val ts = McpStore.toolsOf(s.id)
            if (ts.isEmpty()) return@mapNotNull null
            Hand(
                id = McpMath.ID_PREFIX + s.id,
                kind = HandMath.KIND_CLOUD,
                name = s.label,
                version = "1",
                fetchedAtMs = s.fetchedAtMs.takeIf { it > 0 } ?: now,
                tools = ts.map { McpMath.handTool(it) },
                // 什么都不占 —— 和 [CloudHand] 一样，天生可并发。
                locks = ts.associate { it.name to emptyList<String>() },
            )
        }
    }

    /**
     * 面板上的「刷新工具」。返回一句人话，直接给他看。
     *
     * ★ 失败要**分得清是哪一步**：连不上 / 它拒了 / 它没有工具。三种原因的下一步完全不同
     *   （查网络 / 查 key / 换一台），混成一句「刷新失败」等于什么都没说。
     */
    fun refresh(server: McpServer): String {
        return try {
            initialize(server)
            val list = rpc(server, 2, "tools/list", JSONObject())
            val tools = McpMath.toolsOf(McpMath.resultOf(list))
            if (tools.isEmpty()) return "连上了，但它一个工具都没报。"
            if (!McpStore.saveTools(server.id, tools)) return "拉到了 ${tools.size} 个工具，但没存下来。"
            McpStore.upsert(server.copy(fetchedAtMs = System.currentTimeMillis()))
            "拉到 ${tools.size} 个工具。"
        } catch (e: Exception) {
            "刷新失败：${why(e, server)}"
        }
    }

    /** 先连上试试（不拉工具）。面板上的「测一测」。 */
    fun probe(server: McpServer): String {
        return try {
            val init = initialize(server)
            val name = init.optJSONObject("serverInfo")?.optString("name").orEmpty()
            val ver = init.optJSONObject("serverInfo")?.optString("version").orEmpty()
            if (name.isBlank()) "连上了。" else "连上了：$name $ver"
        } catch (e: Exception) {
            "连不上：${why(e, server)}"
        }
    }

    /**
     * 调一个工具。
     *
     * 回执形状和 [CloudHand] 一致（`ok` + `data`／人话错误），
     * 这样 `use_hand` 那一层不需要知道这是谁。
     */
    fun exec(hand: Hand, toolName: String, args: JSONObject, task: String = ""): JSONObject {
        val server = McpStore.server(McpMath.serverIdOf(hand.id))
            ?: return fail("这台 MCP 服务器不在了（可能刚被删掉）。")
        if (!server.enabled) return fail("「${server.label}」现在是关着的。")
        return try {
            initialize(server)
            val r = rpc(
                server, 3, "tools/call",
                JSONObject().put("name", toolName).put("arguments", args),
            )
            val res = McpMath.resultOf(r)
            val text = McpMath.contentText(res)
            if (McpMath.isError(res)) {
                fail(text.ifBlank { "「${server.label}」的「$toolName」报错了。" })
            } else {
                JSONObject().put("ok", true).put("data", ApiMath.ellipsis(text, MAX_TEXT))
            }
        } catch (e: Exception) {
            fail("「${server.label}」没做成：${why(e, server)}")
        }
    }

    // ------------------------------------------------------------------

    /** `initialize` + `notifications/initialized`。返回 `result`。 */
    private fun initialize(server: McpServer): JSONObject {
        val init = rpc(
            server, 1, "initialize",
            JSONObject()
                .put("protocolVersion", McpMath.PROTOCOL)
                .put("capabilities", JSONObject())
                .put(
                    "clientInfo",
                    JSONObject().put("name", "ConMarn").put("version", "1"),
                ),
        )
        // 通知没有 id，对方不回 —— 但它不发的话，不少服务器会拒绝后面的 tools/list。
        post(server, McpMath.notify("notifications/initialized"), 0)
        return McpMath.resultOf(init)
    }

    private fun rpc(server: McpServer, id: Int, method: String, params: JSONObject?): JSONObject {
        val text = post(server, McpMath.rpc(id, method, params), id)
        return McpMath.pickResult(text, id)
            ?: throw IOException("没有收到第 $id 条响应")
    }

    /**
     * 发一条 JSON-RPC，返回正文。
     *
     * @param wantId 0 = 通知（不等响应）；其它 = 读到这一条就可以停。
     */
    private fun post(server: McpServer, body: String, wantId: Int): String {
        val conn = CloudNet.conn(server.url).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            // ★ 两个都要收：有的服务器回 JSON，有的回 SSE。
            setRequestProperty("Accept", "application/json, text/event-stream")
            server.headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (stream == null) {
                if (code in 200..299) "" else throw IOException("HTTP $code")
            } else if (code !in 200..299) {
                val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                throw IOException("HTTP $code：${ApiMath.ellipsis(text.trim(), 200)}")
            } else if (conn.contentType.orEmpty().contains("event-stream", ignoreCase = true)) {
                // ★ SSE 是一条流，读完可能永远不结束。读到目标 id 就 break。
                val sb = StringBuilder()
                stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        sb.append(line).append('\n')
                        if (wantId != 0 && McpMath.pickResult(sb.toString(), wantId) != null) break
                    }
                }
                sb.toString()
            } else {
                stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 失败原因分档。
     *
     * ★ 401/403 是**它不收这个 key**，不是网络问题 —— 混成「连不上」会让人去查网络。
     */
    private fun why(e: Exception, server: McpServer): String {
        val raw = ApiMath.redact("${e.javaClass.simpleName}: ${e.message}", server.headers.values.toList())
        return when {
            raw.contains("401") || raw.contains("403") ->
                "它不收这个凭据（$raw）。请求头那一栏再核一遍。"
            raw.contains("404") -> "地址不对（$raw）。它要的是 MCP 那个端点，不是首页。"
            raw.contains("UnknownHost") || raw.contains("ConnectException") ->
                "连不上（$raw）。先确认网络和地址。"
            else -> raw
        }
    }

    /** 一台服务器的 id：从名字来，重的加序号。 */
    fun newId(label: String, existing: List<McpServer>): String {
        val base = label.lowercase().map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("").trim('-').ifBlank { "mcp" }.take(20)
        if (existing.none { it.id == base }) return base
        var n = 2
        while (existing.any { it.id == "$base-$n" }) n++
        return "$base-$n"
    }
}
