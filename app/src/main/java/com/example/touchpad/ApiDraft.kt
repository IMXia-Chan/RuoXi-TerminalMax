package com.example.touchpad

import org.json.JSONArray
import org.json.JSONObject

/**
 * **一次「新建 API」的草稿** —— 还没落盘,先给用户在面板上审一遍。
 *
 * ★ 为什么要有独立的草稿层:解析接口描述这件事**一定会出错**(OpenAPI 有几百种写法,
 *   curl 有一百种引号)。出错时最坏的结局不是解析失败,而是**解析出一个看起来对的
 *   错误工具**:名字对、路径对、参数少一个 —— 她调它的时候拿回一份别的城市的天气。
 *   所以中间必须有一步「给你看、你点确认」,而不是直接写进 [ApiStore]。
 */
data class ApiDraft(
    val purpose: ApiPurpose,
    val tools: List<ApiToolDecl>,
    /** 解析时跳过了什么、为什么。**不许静默丢** —— 用户要能看出「少了三个接口」。 */
    val notes: List<String> = emptyList(),
)

/**
 * 把「一段接口描述」变成 [ApiDraft] 的纯逻辑。
 *
 * 支持三种来源,优先级从高到低:
 * 1. **OpenAPI / Swagger**(`openapi.json` 的内容)—— 最准,行业标准,几十万个 API 都是这个形状;
 * 2. **curl** —— 从浏览器「复制为 cURL」直接贴,一个请求 = 一个工具;
 * 3. 都不像 → 返回 null,让调用方去问模型(或让用户手填)。
 *
 * ★ 这个文件**只用 org.json**,不碰 Android 和网络:`openapi.json` 可能来自
 *   用户贴的文本,也可能是从地址下载的,但解析本身是纯函数。
 */
object ApiDraftMath {

    /** 一次最多接多少个工具。再多,她在工具表里就找不到重点了。 */
    const val MAX_TOOLS = 24

    /** 一个 API 最多几个字符的说明。 */
    const val DESC_MAX = 200

    /** 用途 id 最长多少。 */
    const val ID_MAX = 24

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /**
     * 自动识别。→ null = 认不出(不是报错,是「换一种方式」)。
     *
     * @param label 用户给这个名字(面板上填的),解析结果会用它做用途名。
     */
    fun parse(text: String, label: String): ApiDraft? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("{") || t.startsWith("[")) {
            parseOpenApi(t, label)?.let { return it }
        }
        if (t.contains("curl ")) return parseCurl(t, label)
        return null
    }

    // ------------------------------------------------------------------
    // OpenAPI / Swagger
    // ------------------------------------------------------------------

    /**
     * 解析 OpenAPI 3.x / Swagger 2.0。
     *
     * 两条都认,因为用户从网上下载的 `openapi.json` 有一半还是 Swagger 2 的形状
     * (`swagger: "2.0"` + `host` + `basePath`,没有 `servers`)。
     */
    fun parseOpenApi(text: String, label: String): ApiDraft? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val isV3 = root.has("openapi")
        val isV2 = root.optString("swagger").startsWith("2")
        if (!isV3 && !isV2) return null

        val baseUrl = if (isV3) {
            root.optJSONArray("servers")?.optJSONObject(0)?.optString("url").orEmpty()
        } else {
            val host = root.optString("host")
            val scheme = root.optJSONArray("schemes")?.optString(0).orEmpty().ifEmpty { "https" }
            val base = root.optString("basePath")
            if (host.isBlank()) "" else "$scheme://$host$base"
        }
        if (baseUrl.isBlank()) return null

        val paths = root.optJSONObject("paths") ?: return null
        val notes = ArrayList<String>()
        val tools = ArrayList<ApiToolDecl>()
        val purposeId = slug(label.ifBlank { root.optJSONObject("info")?.optString("title").orEmpty() })
        val title = root.optJSONObject("info")?.optString("title").orEmpty()
        val apiDesc = root.optJSONObject("info")?.optString("description").orEmpty()

        val keys = paths.keys()
        outer@ while (keys.hasNext()) {
            val path = keys.next()
            val item = paths.optJSONObject(path) ?: continue
            for (m in METHODS) {
                val op = item.optJSONObject(m) ?: continue
                if (tools.size >= MAX_TOOLS) {
                    notes.add("接口太多,只收了前 $MAX_TOOLS 个。")
                    break@outer
                }
                val decl = toolOf(purposeId, path, m, op, baseUrl, notes) ?: continue
                // 同名就加个后缀 —— 工具_name 在 list_hands 里必须唯一。
                val name = uniqueName(decl.name, tools.map { it.name }.toSet())
                tools.add(decl.copy(name = name))
            }
        }
        if (tools.isEmpty()) return null

        val note = buildString {
            append(apiDesc.take(120).ifBlank { "从 OpenAPI 描述导入。" })
            append("\n共 ${tools.size} 个接口。")
            if (notes.isNotEmpty()) append("\n").append(notes.joinToString("\n"))
        }
        return ApiDraft(purpose = purposeOf(purposeId, label.ifBlank { title }, note, baseUrl), tools = tools, notes = notes)
    }

    private val METHODS = listOf("get", "post", "put", "patch", "delete")

    private fun toolOf(
        purposeId: String,
        path: String,
        method: String,
        op: JSONObject,
        baseUrl: String,
        notes: MutableList<String>,
    ): ApiToolDecl? {
        val rawName = op.optString("operationId")
            .ifEmpty { method + "_" + path.trim('/').replace('/', '_').replace(Regex("[{}]"), "") }
        val name = slug(rawName).take(48)
        if (name.isBlank()) return null

        val desc = op.optString("summary").ifBlank { op.optString("description") }
            .take(DESC_MAX).ifBlank { "$method $path" }

        // ---- 参数 ----
        val params = ArrayList<HandParam>()
        val required = ArrayList<String>()
        val query = ArrayList<String>()
        val heads = LinkedHashMap<String, String>()
        val body = ArrayList<String>()
        // ★ 同一个参数名可能在 path 和 query 里各出现一次(OpenAPI 允许)。
        //   按名字去重,不要让模型看到两个「city」。
        val seen = HashSet<String>()

        fun addParam(n: String, d: String, req: Boolean) {
            if (n.isBlank() || !seen.add(n)) return
            params.add(HandParam(n, d.take(120)))
            if (req) required.add(n)
        }

        val arr = op.optJSONArray("parameters")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val n = p.optString("name")
                val where = p.optString("in")
                val req = p.optBoolean("required") || where == "path"
                val d = p.optString("description").ifBlank { n }
                when (where) {
                    "query" -> { query.add(n); addParam(n, d, req) }
                    "path" -> addParam(n, d, req)          // 模板里已有 {n},不用再登记
                    "header" -> {
                        // ★ 声明里的 header 参数(如 X-Request-Id)不进 headers:
                        //   headers 那一栏是「写死的」,不是「模型能填的」。
                        //   混进去等于给模型开了一个能发任意请求头的口子。
                        if (!n.equals("Authorization", true) && !n.lowercase().contains("key"))
                            notes.add("${op.optString("operationId", "$method $path")} 的请求头参数「$n」没有收 —— 请求头只能写死。")
                    }
                }
            }
        }

        // ---- 请求体 ----
        val rb = op.optJSONObject("requestBody")
        if (rb != null) {
            val schema = rb.optJSONObject("content")
                ?.optJSONObject("application/json")
                ?.optJSONObject("schema")
            collectSchema(schema, body, params, required, seen)
        } else if (method == "post" || method == "put" || method == "patch") {
            // Swagger 2:body 参数是一个特殊条目。
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    if (p.optString("in") != "body") continue
                    collectSchema(p.optJSONObject("schema"), body, params, required, seen)
                }
            }
        }

        val exec = ApiExec(
            method = method.uppercase(),
            path = path,
            query = query,
            fixedQuery = emptyMap(),
            bodyParams = body,
            headers = heads,
            pick = "",
            maxChars = 1500,
        )
        return ApiToolDecl(
            purpose = purposeId,
            name = name,
            desc = desc,
            params = params,
            required = required,
            exec = exec,
        )
    }

    /** 把一个 schema 的顶层属性收成参数。**只收一层** —— 嵌套对象交给模型直接拼 JSON 更危险。 */
    private fun collectSchema(
        schema: JSONObject?,
        body: MutableList<String>,
        params: MutableList<HandParam>,
        required: MutableList<String>,
        seen: MutableSet<String>,
    ) {
        val props = schema?.optJSONObject("properties") ?: return
        val req = schema.optJSONArray("required") ?: JSONArray()
        val reqSet = (0 until req.length()).map { req.optString(it) }.toSet()
        val keys = props.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (!seen.add(k)) continue
            val p = props.optJSONObject(k)
            val d = p?.optString("description").orEmpty().ifBlank { k }
            body.add(k)
            params.add(HandParam(k, d.take(120)))
            if (k in reqSet) required.add(k)
        }
    }

    // ------------------------------------------------------------------
    // curl
    // ------------------------------------------------------------------

    /**
     * 从「复制为 cURL」里解析出**一个**工具。
     *
     * ★ 值一律丢掉。curl 里带着用户自己的 key / cookie / 一次真实的查询,
     *   照抄进声明等于把它们写进 `tools.json` 的明文。**只要名字和形状。**
     */
    fun parseCurl(text: String, label: String): ApiDraft? {
        val t = text.replace("\\\r\n", " ").replace("\\\n", " ").trim()
        val url = Regex("""['"]?(https?://[^\s'"]+)['"]?""").find(t)?.groupValues?.get(1) ?: return null
        val method = Regex("""(?:-X|--request)\s+['"]?([A-Za-z]+)""").find(t)?.groupValues?.get(1)
            ?.uppercase()
            ?: if (Regex("""(?:-d|--data|--data-raw|--data-binary)\s""").containsMatchIn(t)) "POST" else "GET"

        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val baseUrl = "${u.scheme}://${u.authority}"
        val path = u.rawPath.ifBlank { "/" }

        val notes = ArrayList<String>()
        val query = ArrayList<String>()
        u.rawQuery?.split('&')?.forEach { pair ->
            val n = pair.substringBefore('=').trim()
            if (n.isEmpty()) return@forEach
            // ★ 带 key/token/sign/secret 字样的参数名不进白名单 —— 那是鉴权,
            //   该由用户在「key 放哪」里填,不该让模型填。
            if (AUTH_HINT.any { n.lowercase().contains(it) }) {
                notes.add("参数「$n」看起来是鉴权用的,没有收成可填参数。")
            } else {
                query.add(n)
            }
        }

        val params = ArrayList<HandParam>()
        val required = ArrayList<String>()
        query.forEach { params.add(HandParam(it, it)); required.add(it) }

        val body = ArrayList<String>()
        val bodyText = bodyAfter(t, listOf("--data-binary", "--data-raw", "--data", "-d"))
        if (!bodyText.isNullOrBlank()) {
            runCatching { JSONObject(bodyText) }.getOrNull()?.let { o ->
                val ks = o.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    body.add(k)
                    params.add(HandParam(k, k))
                    required.add(k)
                }
            }
        }

        val heads = LinkedHashMap<String, String>()
        var sawAuth = false
        Regex("""(?:-H|--header)\s+['"]([^:'"]+):\s*([^'"]*)['"]""").findAll(t).forEach { m ->
            val n = m.groupValues[1].trim()
            if (n.equals("Authorization", true) ||
                n.equals("X-API-Key", true) ||
                AUTH_HINT.any { n.lowercase().contains(it) }
            ) {
                sawAuth = true
                // ★ 只留「要 key」这个事实,不留值。
                heads[n] = if (n.equals("Authorization", true)) "Bearer {key}" else "{key}"
            } else if (!n.equals("Accept", true) && !n.equals("Content-Type", true)) {
                notes.add("请求头「$n」没有收 —— 请求头只能写死,不能填值。")
            }
        }
        if (sawAuth) notes.add("识别到鉴权头,已写成 {key};把 key 填在下面那一栏。")

        val purposeId = slug(label.ifBlank { u.host })
        val name = slug(u.host.substringBefore('.')) + "_" + method.lowercase()
        return ApiDraft(
            purpose = purposeOf(purposeId, label.ifBlank { u.host }, "从 curl 导入的一个接口。", baseUrl),
            tools = listOf(
                ApiToolDecl(
                    purpose = purposeId,
                    name = name,
                    desc = "$method $path",
                    params = params,
                    required = required,
                    exec = ApiExec(
                        method = method,
                        path = path,
                        query = query,
                        bodyParams = body,
                        headers = heads,
                    ),
                )
            ),
            notes = notes,
        )
    }

    /** 看起来是鉴权的参数名/头名。 */
    private val AUTH_HINT = listOf("key", "token", "sign", "secret", "auth", "credential")

    /**
     * 找 `-d` / `--data` 后面被引号包住的**一整段**。
     *
     * ★ 不能用 `(['"])(.+?)\1` 那种非贪婪正则:body 是 **JSON**,里面全是引号 ——
     *   `{"q":"你好"}` 会被截成 `{`,于是草稿里一个参数都没有,而面板上看着
     *   「导入成功、一个接口」。必须按**开引号那个字符**去找配对的闭引号。
     */
    private fun bodyAfter(t: String, flags: List<String>): String? {
        for (flag in flags) {
            // 长出这个词前面得有空白,免得 `-d` 撞上 `--data` 里的一截。
            val m = Regex("""(?:^|\s)${Regex.escape(flag)}(?:=|\s+)""").find(t) ?: continue
            val idx = m.range.last + 1
            if (idx >= t.length) continue
            val q = t[idx]
            if (q != '\'' && q != '"') continue
            val end = t.indexOf(q, idx + 1)
            if (end < 0) continue
            return t.substring(idx + 1, end)
        }
        return null
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private fun purposeOf(id: String, label: String, note: String, baseUrl: String): ApiPurpose {
        val lid = id.ifBlank { "api" }
        return ApiPurpose(
            id = lid,
            label = label.ifBlank { lid }.take(20),
            note = note,
            profiles = listOf(
                ApiProfile(id = "default", label = runCatching { java.net.URI(baseUrl).host }.getOrNull() ?: lid, baseUrl = baseUrl)
            ),
            activeId = "default",
        )
    }

    /** 名字 → 合法标识符。和 [SkillMath.idOf] 同一套。 */
    fun slug(s: String): String {
        val sb = StringBuilder()
        for (c in s.trim()) {
            when {
                c.isLetterOrDigit() -> sb.append(c.lowercaseChar())
                else -> sb.append('_')
            }
        }
        val out = sb.toString().trim('_')
        return if (out.isEmpty()) "" else out.take(ID_MAX)
    }

    private fun uniqueName(base: String, used: Set<String>): String {
        if (base !in used) return base
        var i = 2
        while ("${base}_$i" in used) i++
        return "${base}_$i"
    }

    /**
     * 草稿能不能存。→ null = 能存;否则是一句人话。
     *
     * ★ 检查放在这里而不是面板里:存一次新 API 会同时写三份文件
     *   (`purposes.json` / `tools.json`),任何一份的形状不对都应该**在点确认之前**说。
     */
    fun problem(d: ApiDraft): String? {
        if (d.purpose.label.isBlank()) return "得给它起个名字。"
        if (d.purpose.profiles.none { it.baseUrl.isNotBlank() }) return "没有地址 —— 它不知道该请求哪儿。"
        if (d.tools.isEmpty()) return "一个接口都没有,加上也用不了。"
        if (d.tools.size > MAX_TOOLS) return "接口太多(最多 $MAX_TOOLS 个)。先挑最常用的几条。"
        val bad = d.tools.firstOrNull { it.name.isBlank() || it.name.length > 48 }
        if (bad != null) return "有个接口的名字不合规(空或太长),没法给她调用。"
        val dup = d.tools.groupBy { it.name }.filter { it.value.size > 1 }.keys.firstOrNull()
        if (dup != null) return "有两个接口都叫「$dup」,她分不清该调哪个。"
        return null
    }
}
