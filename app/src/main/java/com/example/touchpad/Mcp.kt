package com.example.touchpad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * **MCP 服务器** —— 一个地址，它自己报出「我有哪些工具」。
 *
 * 他 2026-11-06 在「API 宿主系统」那四项里全选了，这一块是第二项。
 *
 * ## 和「自己加 API」不是一回事
 *
 * | | 自己加 API | MCP |
 * |---|---|---|
 * | 工具清单从哪来 | **你贴**一份 openapi.json / curl | **它自己报**（`tools/list`） |
 * | 协议 | 普通 HTTP + JSON | JSON-RPC 2.0，走同一个地址 |
 * | 地址管什么 | 一个接口一条路径 | 一个地址管全部工具 |
 *
 * 所以它不是「另一种 API」，是**另一种问法**：我们问它「你会什么」，它列出来；
 * 我们问它「跑这个」，它回结果。
 *
 * ## ★ 这一版只做 **Streamable HTTP**（远程 MCP）
 *
 * MCP 还有 stdio 那一种（本机起一个进程，通过管道说话）。手机上做不到：
 * 要打包一个 Node/Python 运行时，体积和权限都不是这一版能付的。
 * 所以面板上只收**地址**，不收「命令」。这一点要写在界面上，不能让人填了命令再来问为什么没用。
 */

/** 一台 MCP 服务器。 */
data class McpServer(
    val id: String,
    val label: String,
    /** 完整地址，比如 `https://mcp.example.com/mcp`。 */
    val url: String,
    /** 写死的请求头（鉴权放这儿，比如 `Authorization: Bearer …`）。 */
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    /** 上次拉工具清单的时刻。0 = 从没拉成过。 */
    val fetchedAtMs: Long = 0,
)

/** 它自己报上来的一个工具。 */
data class McpTool(
    val name: String,
    val desc: String,
    /** `inputSchema` 的原文。留着是因为参数结构可能比我们拆出来的深。 */
    val schema: String,
)

/**
 * 纯函数：拼 JSON-RPC、解析 SSE、把它的工具表变成我们的 [HandTool]。
 *
 * ★ 放在这里（而不是散在 [McpClient] 里）的理由和其他 `*Math` 一样：这些形状
 *   错了**不会崩**，只会让她调到一个参数缺失的工具 —— 单测是唯一能拦住它的东西。
 */
internal object McpMath {

    const val ID_PREFIX = "mcp:"
    const val PROTOCOL = "2024-11-05"

    fun isMcp(handId: String): Boolean = handId.startsWith(ID_PREFIX)

    fun serverIdOf(handId: String): String = handId.removePrefix(ID_PREFIX)

    /** 一条 JSON-RPC 请求。`id` 用调用方给的序号 —— 响应要按它配对。 */
    fun rpc(id: Int, method: String, params: JSONObject? = null): String {
        val o = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
        params?.let { o.put("params", it) }
        return o.toString()
    }

    /** `notifications/initialized` 是**通知**：没有 id，对方也不回。 */
    fun notify(method: String): String =
        JSONObject().put("jsonrpc", "2.0").put("method", method).toString()

    /**
     * 从一段可能带 SSE 的正文里，挑出 `id` 对上的那条响应。
     *
     * ★ 服务端可能回 `application/json`，也可能回 `text/event-stream`，
     *   而且流里会夹着别的通知。只按「第一个 JSON」读会永久卡在握手 ——
     *   而症状是「连上了，但没有工具」，看不出是解析的问题。
     */
    fun pickResult(body: String, id: Int): JSONObject? {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            val o = runCatching { JSONObject(trimmed) }.getOrNull()
            if (o != null) return if (matches(o, id)) o else null
        }
        for (line in body.lines()) {
            val s = line.trim()
            if (!s.startsWith("data:")) continue
            val data = s.substring(5).trim()
            if (!data.startsWith("{")) continue
            val o = runCatching { JSONObject(data) }.getOrNull() ?: continue
            if (matches(o, id)) return o
        }
        return null
    }

    private fun matches(o: JSONObject, id: Int): Boolean =
        o.optInt("id", Int.MIN_VALUE) == id && (o.has("result") || o.has("error"))

    /** 一条响应里的 `result`。没有就抛 —— 调用方要把 `error` 的原文带出去。 */
    fun resultOf(o: JSONObject): JSONObject {
        o.optJSONObject("error")?.let {
            throw IllegalStateException("MCP 报错：" + it.optString("message"))
        }
        return o.optJSONObject("result") ?: JSONObject()
    }

    /** `tools/list` 的一条 → [McpTool]。没有名字的跳过（它就是不能用）。 */
    fun toolOf(o: JSONObject): McpTool? {
        val name = o.optString("name").trim()
        if (name.isEmpty()) return null
        return McpTool(
            name = name,
            desc = o.optString("description").trim(),
            schema = (o.optJSONObject("inputSchema") ?: JSONObject()).toString(),
        )
    }

    fun toolsOf(result: JSONObject): List<McpTool> {
        val arr = result.optJSONArray("tools") ?: return emptyList()
        val out = ArrayList<McpTool>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            toolOf(o)?.let { out.add(it) }
        }
        return out
    }

    /** 它的工具 → 我们菜单上的一行。 */
    fun handTool(t: McpTool): HandTool {
        val (params, required) = paramsOf(runCatching { JSONObject(t.schema) }.getOrNull())
        return HandTool(
            name = t.name,
            desc = t.desc.ifBlank { t.name },
            params = params,
            required = required,
            // MCP 的执行不是「拼一个 URL」，是发一条 JSON-RPC。所以这里不留 exec。
            exec = null,
        )
    }

    /** `inputSchema.properties` → 参数表。只收一层，和 [ApiDraftMath] 一个尺度。 */
    fun paramsOf(schema: JSONObject?): Pair<List<HandParam>, List<String>> {
        val props = schema?.optJSONObject("properties") ?: return emptyList<HandParam>() to emptyList()
        val params = ArrayList<HandParam>()
        props.keys().forEach { k ->
            params.add(HandParam(k, props.optJSONObject(k)?.optString("description").orEmpty()))
        }
        val req = ArrayList<String>()
        schema.optJSONArray("required")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotEmpty() }?.let { req.add(it) }
        }
        return params to req
    }

    /**
     * `tools/call` 的结果 → 给模型看的一段文字。
     *
     * ★ 它的形状是 `content: [{type:"text", text:"…"}]`，不是纯字符串。直接
     *   `toString()` 给她会带一层 JSON 壳，而她会在回答里把这层壳念出来。
     * ★ `isError: true` 时要**当失败**报（返回 null 之外的原因由调用方决定）。
     */
    fun contentText(result: JSONObject): String {
        val arr = result.optJSONArray("content")
            ?: return result.toString()
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            when (b.optString("type")) {
                "text" -> sb.append(b.optString("text"))
                // 图片/音频这类块**不往上下文里塞**：她的上下文是按字算钱的，
                // 而且这一版也没有能把它们读明白的地方。
                else -> sb.append("[${b.optString("type")}]")
            }
        }
        return sb.toString().ifBlank { result.toString() }
    }

    fun isError(result: JSONObject): Boolean = result.optBoolean("isError", false)

    /**
     * 面板上那一栏「请求头」→ 表。**一行一条，`键: 值`**。
     *
     * ★ 只按**第一个**冒号切：值里出现冒号是常态（`Bearer a:b`、URL 里的 `https:`），
     *   按最后一个切或全切都会把值弄坏，而弄坏之后连不上的表现和「key 填错了」一模一样。
     * ★ 空行和没有冒号的行跳过，不报错 —— 写了半行是常态。
     */
    fun parseHeaders(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in text.lines()) {
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#")) continue
            val at = s.indexOf(':')
            if (at <= 0) continue
            val k = s.substring(0, at).trim()
            val v = s.substring(at + 1).trim()
            if (k.isNotEmpty()) out[k] = v
        }
        return out
    }

    /** 和 [parseHeaders] 一对：来回走两趟必须一模一样。 */
    fun formatHeaders(h: Map<String, String>): String =
        h.entries.joinToString("\n") { "${it.key}: ${it.value}" }
}

/** 落盘：`filesDir/mcp/servers.json` 和 `tools.json`。 */
internal object McpStore {

    private var dir: File? = null
    private val lock = Any()
    private var servers: List<McpServer> = emptyList()
    private var tools: Map<String, List<McpTool>> = emptyMap()

    fun init(ctx: Context) {
        if (dir != null) return
        synchronized(lock) {
            if (dir != null) return
            val d = File(ctx.filesDir, "mcp").apply { mkdirs() }
            dir = d
            runCatching { servers = readServers(File(d, "servers.json")) }
            runCatching { tools = readTools(File(d, "tools.json")) }
        }
    }

    fun servers(): List<McpServer> = synchronized(lock) { servers }

    fun server(id: String): McpServer? = servers().firstOrNull { it.id == id }

    fun toolsOf(id: String): List<McpTool> = synchronized(lock) { tools[id].orEmpty() }

    fun upsert(s: McpServer): Boolean = synchronized(lock) {
        val next = servers.filterNot { it.id == s.id } + s
        servers = next
        write(File(dir ?: return false, "servers.json"), serversToJson(next))
    }

    fun remove(id: String): Boolean = synchronized(lock) {
        servers = servers.filterNot { it.id == id }
        tools = tools - id
        val d = dir ?: return false
        write(File(d, "servers.json"), serversToJson(servers))
        write(File(d, "tools.json"), toolsToJson(tools))
    }

    fun saveTools(id: String, list: List<McpTool>): Boolean = synchronized(lock) {
        tools = tools + (id to list)
        write(File(dir ?: return false, "tools.json"), toolsToJson(tools))
    }

    private fun write(f: File, text: String): Boolean = runCatching {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
        true
    }.getOrDefault(false)

    private fun serversToJson(list: List<McpServer>): String {
        val arr = JSONArray()
        for (s in list) {
            val h = JSONObject()
            s.headers.forEach { (k, v) -> h.put(k, v) }
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("label", s.label)
                    .put("url", s.url)
                    .put("headers", h)
                    .put("enabled", s.enabled)
                    .put("fetchedAtMs", s.fetchedAtMs)
            )
        }
        return arr.toString()
    }

    private fun readServers(f: File): List<McpServer> {
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        val out = ArrayList<McpServer>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            val url = o.optString("url").trim()
            if (id.isEmpty() || url.isEmpty()) continue
            val h = LinkedHashMap<String, String>()
            o.optJSONObject("headers")?.let { hObj -> hObj.keys().forEach { k -> h[k] = hObj.optString(k) } }
            out.add(
                McpServer(
                    id = id,
                    label = o.optString("label").ifBlank { id },
                    url = url,
                    headers = h,
                    enabled = o.optBoolean("enabled", true),
                    fetchedAtMs = o.optLong("fetchedAtMs", 0L),
                )
            )
        }
        return out
    }

    private fun toolsToJson(m: Map<String, List<McpTool>>): String {
        val o = JSONObject()
        m.forEach { (id, list) ->
            val arr = JSONArray()
            list.forEach { t ->
                arr.put(
                    JSONObject().put("name", t.name).put("desc", t.desc).put("schema", t.schema)
                )
            }
            o.put(id, arr)
        }
        return o.toString()
    }

    private fun readTools(f: File): Map<String, List<McpTool>> {
        if (!f.exists()) return emptyMap()
        val o = JSONObject(f.readText())
        val out = HashMap<String, List<McpTool>>()
        o.keys().forEach { id ->
            val arr = o.optJSONArray(id) ?: return@forEach
            val list = ArrayList<McpTool>()
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                list.add(McpTool(t.optString("name"), t.optString("desc"), t.optString("schema")))
            }
            out[id] = list
        }
        return out
    }
}
