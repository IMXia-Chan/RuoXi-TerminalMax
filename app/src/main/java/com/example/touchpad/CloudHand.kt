package com.example.touchpad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云端那只手:把 [ApiStore] 里的**声明**变成 [Hand](给模型看的菜单),
 * 再把 `use_hand` 的调用变成一次真正的 HTTP 请求。
 *
 * ## 一只用途 = 一只手
 *
 * 天气是一只手,地图路线是一只手。`list_hands` 报出来就是
 * 「云服务「天气」,2 个工具」。**面板里管的和这里执行的是同一份数据**
 * —— 不是两张表(见 [ApiModel] 文件头那条架构主张)。
 *
 * ## ★★ 三条不变量,全在这里落成代码
 *
 * 1. **模型改不了地址。** 它给的只有参数值;URL 是 [ApiMath.buildUrl] 用本地
 *    文件里的模板拼的。它没有机会让请求发到第二个主机名上。
 * 2. **只发文本,fail closed。** 两处拦:声明([HandTool.sends])和正文
 *    ([ApiMath.payloadDirtyReason])。**默认是不发**,不是「发出去看看」。
 * 3. **绝不撒谎。** 每次调用要么给出真数据,要么给出**人话原因**
 *    (没填 key / 缺哪个参数 / 接口回了但没有那一块)。不返回空数据假装成功。
 *
 * ## 和电脑那只手的区别(为什么它不该排队)
 *
 * 电脑那只手要抢鼠标、抢前台窗口 —— 那是**物理约束**,必须串行。
 * 云端这只手**什么都不占**,同时发十个请求也不会互相踩。
 * 所以它的 `locks` 是**空的**:天生可以并发,不该被排进队列。
 */
object CloudHand {

    const val ID_PREFIX = "api:"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    private var appContext: Context? = null

    /**
     * 上下文由自己持有 —— 和 [SelfHand] 一样,**不**要求调用方传进来。
     *
     * 理由是 `use_hand` 那条路在 [AiAgent] 里,而 AiAgent 本身**不持有 Context**
     * (它是被 `AiAgentHolder.get(app)` 造出来的)。让每个调用点各自想办法拿
     * Context,迟早有人拿错一个(Activity 泄漏 / 拿到已经销毁的那个)。
     * 一个 `applicationContext` 存在这儿,谁都拿不错。
     */
    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        ApiStore.init(ctx)
    }

    private fun trace(msg: String) {
        try {
            appContext?.let { ModelManager.get(it).trace("[api] $msg") }
        } catch (_: Exception) {
        }
    }

    fun idOf(purposeId: String): String = ID_PREFIX + purposeId

    fun purposeIdOf(handId: String): String = handId.removePrefix(ID_PREFIX)

    /**
     * 组装给模型看的手。
     *
     * ★ [Hand.fetchedAtMs] 填「现在」,不是问到的时刻 —— 因为这份菜单是
     * 从**我们自己的文件**里读的,不是问别的机器要的。它**永远不过期**,
     * 所以不能让 [HandMath.staleNote] 跳出来说「这份清单是 N 小时前的」——
     * 那是**假警告**,模型会学会无视它,真过期那天也照样无视。
     */
    fun hands(): List<Hand> {
        val purposes = ApiStore.purposes()
        val decls = ApiStore.tools()
        val now = System.currentTimeMillis()
        return purposes.mapNotNull { p ->
            val mine = decls.filter { it.purpose == p.id }
            // 一个工具都没有的用途不摆出来 —— 空菜单只会让模型白点一次。
            if (mine.isEmpty()) return@mapNotNull null
            Hand(
                id = idOf(p.id),
                kind = HandMath.KIND_CLOUD,
                name = p.label,
                version = "1",
                fetchedAtMs = now,
                tools = mine.map { it.toHandTool() },
                // ★ 空 = 不占任何排他资源 = 天生可并发。理由见文件头。
                locks = mine.associate { it.name to emptyList<String>() },
            )
        }
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    /**
     * 跑一次。
     *
     * @param task 这一路属于哪件活。★ 空 = 老行为（记到「当前这一轮」[AgentBus.emitHere]）。
     *   工作台同时挂三件时必须点名 —— 不然一件活里的失败会落到另一件（或她）的账上。
     */
    fun exec(hand: Hand, toolName: String, args: JSONObject, task: String = ""): JSONObject {
        // ★ MCP 的手也是 kind=CLOUD（见 [McpClient.hands]），但它不是「拼一条 URL」，
        //   是发一条 JSON-RPC。在这里分道，而不是在 [AiAgent.useHand] 里再加一个分支 ——
        //   上面那道「云端兜底:关」的总闸、日志、回执形状就都是同一份。
        if (McpMath.isMcp(hand.id)) return McpClient.exec(hand, toolName, args, task)
        // ★ MQTT 也一样走这条总闸。它和 MCP 的区别只在「底下是 TCP 不是 HTTP」，
        //   在上面这一层看，两边都是「一次调用、一个结果」。
        if (MqttMath.isMqtt(hand.id)) return MqttHand.exec(hand, toolName, args)
        // ★ 蓝牙：底下是本地的无线电，连网络都不走，但它同样「一次调用、一个结果」，
        //   所以还是在这道闸后面 —— 「云端兜底:关」应当连蓝牙一起关掉，理由是
        //   用户关的是「她自己去碰外面的东西」，而蓝牙也会碰到真实世界的设备。
        if (BleMath.isBle(hand.id)) return BleHand.exec(hand, toolName, args)

        val purposeId = purposeIdOf(hand.id)
        val purpose = ApiStore.purpose(purposeId)
            ?: return fail("找不到用途「$purposeId」(它可能被删掉了)。")

        var profile = ApiMath.active(purpose)
            ?: return fail("「${purpose.label}」现在用不了:${ApiMath.checkPurpose(purpose) ?: "这一档被关掉了"}")
        ApiMath.checkProfile(profile)?.let {
            return fail("「${purpose.label}」还差一步:$it")
        }
        // ★ OAuth 那一档：用之前先看凭证过期没有。续期是**一次网络请求**，
        //   放在这儿而不是放在面板里 —— 面板不一定开过，而调用一定会经过这里。
        //   续不上时原样返回，下面那一发会拿到 401，回执里会说出「授权过期」。
        if (profile.oauth != null) profile = OAuthClient.ensureFresh(purposeId, profile)

        val decl = ApiStore.tools().firstOrNull { it.purpose == purposeId && it.name == toolName }
            ?: return fail("「${purpose.label}」上没有「$toolName」这个工具。先 list_hands 看一眼。")

        // ---- 红线第一道:声明 ----
        if (!ApiMath.sendsOk(decl.sends)) {
            val bad = decl.sends.filter { it !in ApiMath.ALLOWED_SENDS }
            ApiStore.recordCall(purposeId, false, 0, 0, "声明里含 ${bad.joinToString("/")}")
            return fail("这条接口声明过会送「${bad.joinToString("/")}」出去,按规矩不发。")
        }

        val exec = decl.exec
        val given = toStringMap(args)
        ApiMath.missingArgs(decl.required, given).let {
            if (it.isNotEmpty()) return fail("还缺参数:${it.joinToString("、")}。")
        }

        // ★ 走 OAuth 但还没授权过：不能沿用「还没填 key」。填 key 这个动作在
        //   OAuth 这条路上根本不存在，让他去「填 key」等于指错路。
        //   只在**这条声明真的要 key**时才这么说 —— 用 OAuth 令牌调一个不收令牌的
        //   接口是正常的，那时候不该拦。
        val needsKey = exec.path.contains("{key}") ||
            exec.fixedQuery.values.any { it.contains("{key}") } ||
            exec.headers.values.any { it.contains("{key}") }
        if (profile.oauth != null && profile.apiKey.isBlank() && needsKey) {
            ApiStore.recordCall(purposeId, false, 0, 0, "尚未授权")
            return fail("「${purpose.label}」还没授权。去面板这一档里点「开始授权」。")
        }

        // ★ 当前档的 key 从这儿注入成 `{key}`。于是「用户还没填 key」会**自动**
        //   变成 buildUrl 返回 null = 请求不发 —— 而不是发一个 `key=` 的空请求。
        //   **fail closed 是从数据结构里白来的,不是靠记得检查。**
        val withKey = given + ("key" to profile.apiKey)
        val url = ApiMath.buildUrl(profile.baseUrl, exec.path, exec.fixedQuery, exec.query, withKey)
            ?: return fail(
                if (profile.apiKey.isBlank() && profile.oauth != null)
                    "「${purpose.label}」还没授权。去面板这一档里点「开始授权」。"
                else if (profile.apiKey.isBlank()) "「${purpose.label}」还没填 key,去 API 控制中心填一个。"
                else "地址里有没填上的参数,这一条不能发。"
            )

        val body = buildBody(exec, withKey)

        // ★ key 也可以放在请求头里(`Authorization: Bearer {key}`)。和 URL 那边
        //   同一道闸:头里有没填上的占位符 ⇒ **整个请求不发**,不是发一个空头出去。
        val headers = ApiMath.buildHeaders(exec.headers, withKey)
            ?: return fail(
                if (profile.apiKey.isBlank() && profile.oauth != null)
                    "「${purpose.label}」还没授权。去面板这一档里点「开始授权」。"
                else if (profile.apiKey.isBlank()) "「${purpose.label}」还没填 key,去 API 控制中心填一个。"
                else "请求头里有没填上的参数,这一条不能发。"
            )

        // ---- 红线第二道:正文 ----
        // 声明是我们写的,但**拼进去的内容不是** —— 用户说「看看这个截图」,
        // 那张图的 base64 会出现在**参数值**里。这道只有查正文拦得住。
        ApiMath.payloadDirtyReason(url + (body ?: "")).let {
            if (it != null) {
                ApiStore.recordCall(purposeId, false, 0, 0, it)
                return fail(it)
            }
        }

        // ---- 红线第三道:号码 ----
        //
        // ★★ 2026-11-04 补的,原因是查出口时**真查出来一个洞**:
        //   上面两道只管「声明」和「图」。用户一句「13812345678 是哪儿的」,
        //   那串号码会原样落进 query,而这一发是发到**第三方服务商**的 ——
        //   既不是我们的云,也不在「保持能读、不要发云」那条规矩的覆盖里。
        //
        // ★ 处置是**不发**,不是「遮了再发」:遮成 `[手机号]` 之后,
        //   高德/必应只会查到一个查不到的东西 —— 那次请求花了钱还拿回一坨废话。
        //   宁可当场说清楚,让他自己决定要不要上手查。
        //
        // ★ 只查**他给的那几个参数值**,不查整条 url:url 里有我们自己的 key,
        //   拿它去撞号码判据是白撞,撞上了还得反过来解释。
        val payload = given.values.joinToString(" ") + (body ?: "")
        val hits = SecretMath.foundKinds(payload)
        if (hits.isNotEmpty()) {
            val why = "这次要查的东西里有${hits.joinToString("、")},按规矩不发给第三方。" +
                "你可以自己打开网页查,或者把这串数字去掉再问我。"
            ApiStore.recordCall(purposeId, false, 0, 0, why)
            if (task.isEmpty()) {
                AgentBus.emitHere(AgentBusModel.LOCAL, purpose.label, AgentBusModel.KIND_ERROR, why)
            } else {
                AgentBus.emit(task, AgentBusModel.LOCAL, purpose.label, AgentBusModel.KIND_ERROR, why)
            }
            return fail(why)
        }

        val safeUrl = ApiMath.redact(url, ApiStore.knownKeys())
        val t0 = System.currentTimeMillis()
        return try {
            val (status, text) = send(exec.method, url, body, headers)
            val ms = (System.currentTimeMillis() - t0).toInt()
            if (status !in 200..299) {
                val why = "接口回了 HTTP $status:${ApiMath.ellipsis(text.trim(), 200)}"
                ApiStore.recordCall(purposeId, false, ms, status, why)
                trace("← $status ${ApiMath.ellipsis(safeUrl, 160)}")
                return fail("${purpose.label} 那边没给数据($why)")
            }
            val data = shape(text, exec)
            if (data == null) {
                // ★ 两种取法的「摘不到」是**两件不同的事**,不能共用一句话 ——
                //   网页那条里 exec.pick 是空的,照原话会拼出「里面没有「」这一块」,
                //   等于什么都没说,而模型只会原样转述这句废话给用户。
                val why = if (exec.extract == ApiMath.EXTRACT_TEXT)
                    "网页打开是打开了,但里面**没有能读的文字**(这个网站的正文是脚本现场画出来的)。换个能直接搜到答案的说法再试,或者告诉他这个我读不到。"
                else "接口回了 200,但里面没有「${exec.pick}」这一块 —— 它的返回格式可能变了。"
                ApiStore.recordCall(purposeId, false, ms, status, why)
                return fail(why)
            }
            // 成功也留一句 —— 但**只留形状,不留内容**(`pick` 的路径 + 摘出来多少字)。
            // 这也是 [ApiCall] 那条规矩:调试看的是「这一块取到没有」,不是他查了什么。
            ApiStore.recordCall(purposeId, true, ms, status, "「${exec.pick}」拿到 ${data.length} 字")
            trace("← 200 ${data.length}字 $ms ms ${ApiMath.ellipsis(safeUrl, 160)}")
            JSONObject().put("ok", true).put("data", data)
        } catch (e: Exception) {
            val ms = (System.currentTimeMillis() - t0).toInt()
            // ★ 异常信息里**可能带着 key**(高德把 key 放 query,而有些异常会回显 URL)。
            val why = ApiMath.redact("${e.javaClass.simpleName}: ${e.message}", ApiStore.knownKeys())
            ApiStore.recordCall(purposeId, false, ms, 0, why)
            trace("✗ $why")
            fail("连不上 ${purpose.label}:$why")
        }
    }

    /**
     * 面板上的「测连通」。
     *
     * ★ 只打一下 baseUrl,**不碰任何工具** —— 它回答的是「这个地址和这个 key 通不通」,
     * 不是「某个具体接口对不对」。混在一起的话,用户会以为「连通了 = 能用」。
     */
    fun ping(profile: ApiProfile): String {
        ApiMath.checkProfile(profile)?.let { return it }
        return try {
            val (status, _) = send("GET", profile.baseUrl, null)
            // 403/401 说明**通了但 key 不对** —— 这跟「连不上」是两件事,
            // 混成一句会让用户去查网络,而问题其实在 key 上。
            when (status) {
                in 200..299 -> "通了(HTTP $status)"
                401, 403 -> "连得上,但它不收这个 key(HTTP $status)"
                else -> "连得上,但它回了 HTTP $status"
            }
        } catch (e: Exception) {
            "连不上:" + ApiMath.redact("${e.javaClass.simpleName}: ${e.message}", listOf(profile.apiKey))
        }
    }

    /**
     * 测**一家模型**能不能用 —— 发一条两个字的请求（`hi`），只花最小的一次。
     *
     * ★ 和 [ping] 不是一回事:ping 只打 `baseUrl`,回答的是「这个域名通不通」;
     *   这里打的是真正的 `/chat/completions`,回答的是「这个 key、这个模型名,
     *   此刻能不能用」。**前者通了,后者照样会 404** —— 模型名拼错就是这样,
     *   而界面上看不出任何异常。
     *
     * ★ 请求体里**只有 `hi`**,不夹带任何用户内容。截图和声音不上云那条红线
     *   在这里同样成立:测速不是「先随便发点什么」的理由。
     */
    fun probeLlm(endpoint: String, model: String, apiKey: String): String {
        if (endpoint.isBlank()) return "地址还没填。"
        if (model.isBlank()) return "模型还没填。"
        val url = RouteMath.url(Endpoint("probe", endpoint, model, apiKey))
        // ★ 这一条也必须分协议：Anthropic 收的是另一套正文和另一个头。
        //   不分的话，「测一测」永远报失败，而那个失败是**我们发错了**，不是 key 不对。
        val anthropic = AnthropicProto.isAnthropic(url)
        val openAiBody = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", "hi")),
            )
            .put("max_tokens", 1)
        val body = if (anthropic) AnthropicProto.requestBody(openAiBody).toString()
        else openAiBody.toString()
        val heads = when {
            anthropic -> mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to AnthropicProto.VERSION,
            )
            apiKey.isBlank() -> emptyMap()
            else -> mapOf("Authorization" to "Bearer $apiKey")
        }
        val t0 = System.currentTimeMillis()
        return try {
            val (status, text) = send("POST", url, body, heads)
            val ms = System.currentTimeMillis() - t0
            when {
                status in 200..299 -> "通了 · ${ms} 毫秒"
                status == 401 || status == 403 -> "连得上,但它不收这个 key(HTTP $status)"
                status == 404 -> "地址或模型名不对(HTTP 404)。模型那一栏再核一遍。"
                else -> "它回了 HTTP $status:" + ApiMath.ellipsis(text.trim(), 120)
            }
        } catch (e: Exception) {
            "连不上:" + ApiMath.redact("${e.javaClass.simpleName}: ${e.message}", listOf(apiKey))
        }
    }

    // ------------------------------------------------------------------

    private fun send(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val conn = CloudNet.conn(url).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            // ★ 先设我们自己写的,再让声明里的头覆盖 —— 声明是用户为这个接口写的,
            //   它比我们的默认值更清楚该怎么说。`Accept` 也允许被覆盖。
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return status to text
        } finally {
            conn.disconnect()
        }
    }

    private fun buildBody(exec: ApiExec, args: Map<String, String>): String? {
        if (exec.bodyParams.isEmpty()) return null
        val o = JSONObject()
        // ★ 空值也发 —— body 里「没给」和「给了空串」在 JSON API 里常常是两回事,
        //   静默丢掉一个参数比发一个空值更难查。
        exec.bodyParams.forEach { o.put(it, args[it] ?: "") }
        return o.toString()
    }

    /**
     * 从响应里摘出给模型看的那一块。
     *
     * → null = **摘不到**(接口形状变了 / pick 路径写错了 / 网页是 JS 画的)。
     * ★ 这里绝不能「摘不到就整份给它」—— 天气一份原始响应 39 KB,
     * 塞进上下文会把它撑爆,而她会在最后一句话里突然失忆。
     */
    private fun shape(text: String, exec: ApiExec): String? {
        // ---- 网页:去标签取正文 ----
        if (exec.extract == ApiMath.EXTRACT_TEXT) {
            val body = ApiMath.htmlToText(text)
            // ★ 空正文**必须报错,不能当成功**。整页靠 JS 画出来的站点抓回来就是
            //   一片空白 —— 而「查到了但什么都没有」在模型那边和「这页是空的」
            //   长得一样,它会照自己的记忆编一个答案,我们这边一切正常。
            if (body.isBlank()) return null
            return ApiMath.ellipsis(body, exec.maxChars)
        }

        if (exec.pick.isBlank()) return ApiMath.ellipsis(text.trim(), exec.maxChars)
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: runCatching { JSONArray(text) as Any }.getOrNull()
            ?: return null
        val picked = ApiMath.walk(root, ApiMath.pathSegments(exec.pick)) ?: return null
        return ApiMath.ellipsis(picked.toString(), exec.maxChars)
    }

    private fun toStringMap(args: JSONObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        args.keys().forEach { k ->
            val v = args.opt(k)
            if (v != null && v !== JSONObject.NULL) out[k] = v.toString()
        }
        return out
    }

    /** 回执的形状和电脑那只手保持一致(`ok` + 人话)。 */
    private fun fail(why: String): JSONObject = JSONObject().put("ok", false).put("error", why)
}
