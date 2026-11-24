package com.example.touchpad

/**
 * 「一个用途 = 一只手」的数据层 —— 以及关于它的**纯逻辑**。
 *
 * **这个文件里不许出现 org.json / Context / 网络。**(项目惯例,同 [HandModel] /
 * MoodMath / LexiconMath。)理由不是洁癖:这一层的错**不崩**,它错的表现是
 * 「面板里明明开了,她说没这个能力」「她调了个不存在的接口」——
 * 真机上极难复现,所以值得在纯 JVM 上钉死。
 *
 * ---
 *
 * ## ★★ 一条架构主张(用户 2026-10-05 点名要的「API 控制面板」的地基)
 *
 * **面板里管的东西,和 `use_hand` 执行的东西,必须是同一份数据。**
 *
 * 文生图、3D 建模、天气、地图、云端兜底、TTS —— 每一个「用途」在数据结构上就是
 * 一只手([Hand] with `kind = HandMath.KIND_CLOUD`),在面板上就是二级页的一行。
 * **不许有第二张表。** 否则「面板里开了、`use_hand` 认不得」是**必然**会发生的事,
 * 而且症状是「她说没这个能力」—— 极难查。
 *
 * 这个项目已经在同一件事上吃过一次亏:工具表**抄了三遍**(PC 的 `ai_tools.py`、
 * 手机的 `TOOL_SCHEMA`、`SYSTEM_PROMPT` 的散文里各一份),加一个能力要改三处。
 * 别再犯。(见 [HandModel] 文件头那段。)
 *
 * ## 用户要的是什么(2026-10-05 原话)
 *
 * > 「她最好有一个**强大的大脑**,可以去**调用一些 api**,比如**地图,GPS,天气**,
 * >  然后把这些信息**汇总**……我说下周末想去上海玩,她就能自己知道,需要
 * >  **天气,12306 火车票查询,出行方式**……从上海站到人民广场应该怎么走,
 * >  她会给我提供方案,说是**地铁,还是公交,还是打车**,等等方案,都要有!」
 *
 * ★ **这不是「让她变聪明」,是「给她接上外部事实」。** 她说不出上海下周末的天气,
 * 不是因为模型笨 —— 那是**全世界没有任何模型知道的事**。所以这条需求
 * **不需要更强的模型、不需要微调、不需要 RAG**,需要的是一条能出去拿数据、
 * 再汇总成人话的路。别把它做成提示词工程,那方向从一开始就是错的。
 */

// ---------------------------------------------------------------------------
// 配置档:一个 endpoint + 一个 key + 一个模型名
// ---------------------------------------------------------------------------

/**
 * 一条配置档。**「这个用途现在用谁」的答案就是它。**
 *
 * [apiKey] 在**进程内**是明文(不然没法发请求),但**落盘前必须加密**
 * (见 [ApiStore])。★ 而且它**绝不许进日志** —— 高德那类接口把 key 放在
 * **query 参数**里,于是「记一条请求 URL」就等于把 key 写进日志。
 * 所以日志一律走 [ApiMath.redact],不靠自己记得。
 */
data class ApiProfile(
    val id: String,
    val label: String,
    val baseUrl: String,
    val apiKey: String = "",
    val model: String = "",
    val enabled: Boolean = true,
    /**
     * 盘上那份**解不开**了(Keystore 密钥没了:换机、清数据、抹掉凭据)。
     *
     * ★ 有了这一位,面板才能说「key 解不开了,请重填」。
     * 没有它的话,[apiKey] 只能变成空串,而空串在界面上显示成
     * **「(不需要 key)」—— 那是在撒谎**:用户明明填过,是我们弄丢了。
     * 这个项目对「静默失败」的规矩是:**不许骗,哪怕真相难看。**
     */
    val keyUnreadable: Boolean = false,
    /**
     * 这一档走 **OAuth 授权**（不是填 key）。空 = 传统那种填一个 key。
     *
     * ★ 两种共用一个 `apiKey` 字段：OAuth 拿到的 access_token 就存在那儿。
     *   于是加密落盘、日志抹除、`{key}` 注入、请求头模板这些**一个字都不用重写**。
     *   见 [OAuthConfig] 文件头。
     * ★ 有它的时候，[keyless] 那一句「不需要 key」就是**错的** —— 面板要走
     *   [OAuthClient.statusLine]，不然「还没授权」会被显示成「不需要 key」。
     */
    val oauth: OAuthConfig? = null,
) {
    /** 不需要 key 的公开数据源(比如天气用的 wttr.in)。面板上要如实标出来。 */
    val keyless: Boolean get() = apiKey.isBlank() && !keyUnreadable && oauth == null
}

/**
 * 一条云端工具的声明 —— 装在本地文件里,启动时读成 [HandTool]。
 *
 * ★ **URL 模板、方法、参数白名单全在这里,全在本地文件里。**
 * 模型能给的只有参数值 —— 它没有机会改地址,也就没有机会把 API key
 * 发到别的地方去。
 */
data class ApiToolDecl(
    /** 绑到哪个用途 —— [ApiPurpose.id]。baseUrl 和 key 都从那一档取。 */
    val purpose: String,
    val name: String,
    val desc: String,
    val params: List<HandParam> = emptyList(),
    val required: List<String> = emptyList(),
    val exec: ApiExec,
    val sends: List<String> = listOf(ApiMath.SENDS_TEXT),
) {
    fun toHandTool(): HandTool =
        HandTool(name, desc, params, required, exec = exec, sends = sends)
}

/**
 * 一个「用途」= 面板二级的一行。
 *
 * ★ 二级那行**必须直接显示当前生效的是哪一档** —— 答案不该藏在三级页里。
 */
data class ApiPurpose(
    val id: String,
    val label: String,
    val note: String,
    val profiles: List<ApiProfile> = emptyList(),
    val activeId: String = "",
    val usage: ApiUsage = ApiUsage(),
)

/** 本地核算的用量。★ **这不是服务商的账单**,界面上必须写明白(见 [ApiMath.usageLine])。 */
data class ApiUsage(
    val calls: Int = 0,
    val ok: Int = 0,
    val fail: Int = 0,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val lastAtMs: Long = 0,
    /** 最近一次的耗时 / HTTP 状态 / 人话错误(值里**不含** key)。 */
    val lastMs: Int = 0,
    val lastStatus: Int = 0,
    val lastError: String = "",
    /** 最近的几次调用,新的在前。见 [ApiCall] 为什么只记这些。 */
    val recent: List<ApiCall> = emptyList(),
)

/**
 * 「调试」页上的一行:这一次调用**发生了什么**。
 *
 * ★★ **只记字段名、耗时、HTTP 状态、对方的人话 —— 绝不记载荷的值。**
 *    值里装的是他的城市、住址、要查的路线,以后还可能是别的东西。调试要回答的是
 *    「请求发出去了没有、对方认不认这个 key」,**不是「他说了什么」**。
 *    要复现问题,字段名加上状态码就够了 —— 值一个字都不需要。
 *    (要复现到值那一层,他自己当场再发一次就行,不需要我们替他存一份。)
 */
data class ApiCall(
    val atMs: Long,
    val ms: Int,
    val status: Int,
    val ok: Boolean,
    /** 要么是错误的人话,要么是成功时的摘要;**两者都只含字段名和数字,不含值**。 */
    val note: String,
)

// ---------------------------------------------------------------------------
// 声明式执行:一只手的一个工具怎么变成一次请求
// ---------------------------------------------------------------------------

/**
 * 一个云端工具怎么执行。**URL 模板写死在本地文件,模型只能填参数、改不了地址。**
 *
 * 这是防「她把你 API key 发到别的地方去」的**唯一闸门** —— 模型给的是
 * `{"city":"上海"}`,地址是我们自己拼的,它没有机会塞第二个主机名进来。
 *
 * ★ [query] / [bodyParams] 里出现、而模型没给的参数:**整个请求不发**
 * (见 [ApiMath.fillTemplate])。缺参数就发 = 发一个带 `{city}` 字面量的请求出去,
 * 对方要么报错(白跑一轮),要么**当成城市名叫「{city}」**(更糟,静默拿到垃圾数据)。
 */
data class ApiExec(
    val method: String,
    /** 相对 [ApiPurpose] 那档的 `baseUrl` 的路径,如 `/v3/weather/weatherInfo`。 */
    val path: String,
    /** 走 URL `?a={a}&b={b}` 的参数名。**这里是「允许模型填」的白名单。** */
    val query: List<String> = emptyList(),
    /**
     * 服务端写死的 query(如 wttr.in 的 `format=j1`、高德的 `extensions=all`)。
     *
     * ★ 和 URL 模板同理,**模型改不了它们**。凡是「我们自己定的、不该让模型碰」的
     * 参数都放这儿 —— 否则为了给模型开一个 `format`,就得把 `format` 也加进
     * [query] 白名单,于是它也能填了。
     */
    val fixedQuery: Map<String, String> = emptyMap(),
    /** 走 JSON body 的参数名(仅 POST)。 */
    val bodyParams: List<String> = emptyList(),
    /**
     * 写死的请求头。**和 [fixedQuery] 同一套规矩:这里是「我们自己定的」,模型改不了。**
     *
     * 值里可以写 `{key}`,由调用方从当前那一档注入 —— 于是
     * `Authorization = "Bearer {key}"` 这种最常见的鉴权方式不用另开一条路,
     * 而且「用户还没填 key」会**自动**变成请求不发(见 [ApiMath.buildHeaders])。
     *
     * ★ 为什么必须有这一栏:2026-11-06 之前 key 只能进 query(靠 `fixedQuery` 的
     *   `{key}` 模板)。可绝大多数第三方接口要的是 `Authorization: Bearer …` 或
     *   `X-API-Key: …` —— 没有这一栏,「自己加 API」加进来的东西只能是一半能用的。
     */
    val headers: Map<String, String> = emptyMap(),
    /** 从响应里摘哪一段给模型看;点号路径,支持 `a.b` 和 `a[0].b`。空 = 整份(截断到 [maxChars])。 */
    val pick: String = "",
    /**
     * 拿到的东西怎么变成文字:
     *  - `"json"`(默认)—— 按 [pick] 当点号路径取一段。
     *  - `"text"` —— 对方回的是**网页**,按 [ApiMath.htmlToText] 去标签取正文。
     *
     * ★★ 为什么必须有这一档:**「没有公开 API」不等于「查不了」。**
     *    火车票、航班、政策、百科、论坛 —— 这些东西绝大多数**只在网页上**,
     *    没有一个 JSON 接口。只会解 JSON 的云端手,等于把整个互联网关在门外。
     *    加了这一档,她才有「上网查」这件事本身。
     *
     * ★ 安全边界**一个字都没松**:地址模板仍然写死在本地文件,
     *   模型能填的仍然只有 [query]/[bodyParams] 里点名的那几个参数。
     *   变的只是「拿回来的东西怎么读」。
     */
    val extract: String = ApiMath.EXTRACT_JSON,
    /** ★ 必须截断 —— 天气接口一份原始响应 39 KB,整个塞进上下文会把它撑爆。 */
    val maxChars: Int = 1500,
    /** 提交 + 轮询(3D 建模/文生图那类**不是一问一答**的接口)。null = 同步。 */
    val async: ApiAsync? = null,
)

/** 「提交任务 → 拿 id → 轮询 → 拿结果」的形状。现在留形状,别等接了那家再改执行器。 */
data class ApiAsync(
    val idPath: String,
    val pollPath: String,
    val statusPath: String,
    val doneValue: String,
    val intervalMs: Int = 3000,
    val timeoutMs: Int = 180_000,
)

// ---------------------------------------------------------------------------
// 纯逻辑
// ---------------------------------------------------------------------------

/**
 * 关于 API 用途的**纯逻辑**。没有任何 Android 依赖,所以能在纯 JVM 上钉住
 * (见 [ApiMathTest])。
 */
internal object ApiMath {

    /**
     * 允许送出去的东西。**只有 text。**
     *
     * ★ **「截图永不上云」这条红线在这儿从一句注释变成一个数据结构。**
     * 照抄 [AiAgent.teacherBodyIsClean] 的白名单思路:**不在名单上就不发**
     * (fail closed),而不是「在名单上就拦」。
     */
    const val SENDS_TEXT = "text"
    val ALLOWED_SENDS = setOf(SENDS_TEXT)

    /** 「调试」页上留最近几次。5 条够看出「刚才那下到底成没成」,又不至于让文件长胖。 */
    const val RECENT_MAX = 5

    // ------------------------------------------------------------------
    // 网页 → 正文(喂给「上网查」那一档)
    // ------------------------------------------------------------------

    /**
     * 整块丢掉的标签。
     *
     * ★ `script`/`style` 必须丢:实测一个搜索页 100 KB,正文只有 **1.7 KB** ——
     *   留下来的全是脚本和样式。不丢 = 模型认真地去「理解」一坨 JS 变量名,
     *   而且真正的结果被挤出 [ApiExec.maxChars] 之外(等于查了但没看见)。
     */
    private val DROP_BLOCKS = setOf("script", "style", "noscript", "svg", "head", "template", "iframe")

    /** 遇到这些标签换行 —— 不换的话「标题正文链接」会粘成一片,分不清哪句是标题。 */
    private val BLOCK_TAGS = setOf(
        "p", "div", "br", "li", "tr", "td", "th", "ul", "ol", "table",
        "h1", "h2", "h3", "h4", "h5", "h6", "section", "article", "header",
        "footer", "nav", "aside", "blockquote", "pre", "hr", "form", "dl", "dt", "dd",
    )

    /** 折成一个空格的空白。★ 含 ` `(nbsp)—— 网页里到处都是,不折掉会一串串留着。 */
    private val WS = Regex("[ \\t\\u000B\\u000C\\r\\u00A0\\u2000-\\u200B\\u2028\\u2029]+")

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
        "hellip" to "…", "mdash" to "—", "ndash" to "–", "middot" to "·",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’",
        "times" to "×", "divide" to "÷", "deg" to "°", "laquo" to "«", "raquo" to "»",
        "copy" to "©", "reg" to "®", "trade" to "™", "sect" to "§", "para" to "¶",
        "yen" to "¥", "pound" to "£", "euro" to "€", "cent" to "¢", "permil" to "‰",
    )

    /**
     * 网页 → 给模型看的正文。
     *
     * ★ **手写扫描器,不是一串正则。** 正则版在「`<script>` 没有闭合标签」时会
     *   把剩下的整份文档当成脚本文本留下来,而那些内容会**混进正文**喂给模型
     *   —— 它不会报错,只会认真地读一堆 JS。扫描器里那条 `close < 0 → 丢到末尾`
     *   就是为这个写的,而且它可测。
     *
     * ★ 这只是**取正文**,不是浏览器:不执行 JS,不跟重定向,不管 Cookie。
     *   所以它只对**服务端就把内容渲染好**的页面有效(实测 `cn.bing.com/search`
     *   就是这样)。整页靠 JS 画出来的站点它拿回来的是一片空白 ——
     *   [shape] 那条「摘不到就说摘不到」的规矩在这儿同样成立,不许假装查到了。
     */
    fun htmlToText(html: String): String {
        val sb = StringBuilder(html.length)
        var i = 0
        while (i < html.length) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                sb.append(html, i, html.length)
                break
            }
            sb.append(html, i, lt)
            // 注释:里面可能有 `<script>` 之类,不先跳过去会被当成真标签。
            if (html.startsWith("<!--", lt)) {
                val end = html.indexOf("-->", lt + 4)
                i = if (end < 0) html.length else end + 3
                continue
            }
            var j = lt + 1
            val closing = j < html.length && html[j] == '/'
            if (closing) j++
            val nameStart = j
            while (j < html.length && html[j].isLetterOrDigit()) j++
            val name = html.substring(nameStart, j).lowercase()
            val gt = html.indexOf('>', lt)
            // 尖括号没闭合:后面全是残缺,丢掉比猜好。
            if (gt < 0) break
            if (!closing && name in DROP_BLOCKS) {
                val close = html.indexOf("</$name", gt)
                if (close < 0) break          // ★ 见上面那条说明
                val closeGt = html.indexOf('>', close)
                i = if (closeGt < 0) html.length else closeGt + 1
                sb.append('\n')
            } else {
                sb.append(if (name in BLOCK_TAGS) '\n' else ' ')
                i = gt + 1
            }
        }
        return decodeEntities(sb.toString())
            .lines()
            .map { it.replace(WS, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /**
     * HTML 实体 → 字符。`&amp;` `&#160;` `&#x27;` 都要认。
     *
     * ★ **分号离得太远就当成普通的 `&`。** 网页正文里 `&` 本来就是常见字符
     *   (`A&B`、`R&D`),而「从 & 往后找分号」在 `A&B 里的字` 这种句子上会一路找到
     *   很远的地方,把中间整段吞掉 —— 那是**静默丢字**,谁也看不出来。
     */
    fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') {
                sb.append(c); i++; continue
            }
            val semi = s.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 12) {
                sb.append(c); i++; continue
            }
            val body = s.substring(i + 1, semi)
            val rep = when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.substring(2).toIntOrNull(16)?.let { codePoint(it) }
                body.startsWith("#") -> body.substring(1).toIntOrNull()?.let { codePoint(it) }
                else -> NAMED_ENTITIES[body]
            }
            if (rep == null) {
                // 认不出来的实体:**原样留着**。悄悄吃掉它 = 少字,而少字查不出来。
                sb.append(c); i++; continue
            }
            sb.append(rep); i = semi + 1
        }
        return sb.toString()
    }

    private fun codePoint(n: Int): String? = when {
        n <= 0 || n > 0x10FFFF -> null
        // 孤立代理项:塞进去会造出**非法的 UTF-16**,后面所有字符串操作都可能出怪事。
        n in 0xD800..0xDFFF -> null
        else -> String(Character.toChars(n))
    }

    /** [ApiExec.extract] 的两个取值。 */
    const val EXTRACT_JSON = "json"
    const val EXTRACT_TEXT = "text"

    /**
     * 载荷正文里出现这些字样就**不发**。和 `teacherBodyIsClean` 的第二道闸同一份名单。
     *
     * ★ 为什么白名单之外还要查正文:声明是我们写的,但**拼进去的内容不是** ——
     * 用户说「看看这个截图」，那张图的 base64 会出现在参数值里,
     * 而参数名可能老老实实叫 `q`。声明拦不住它,**只有查正文拦得住**。
     */
    val BANNED_IN_BODY = listOf("base64", "image/png", "image/jpeg", "screenshot", "data:image")

    /** [sends] 声明本身干不干净。 */
    fun sendsOk(sends: Collection<String>): Boolean = ALLOWED_SENDS.containsAll(sends)

    // ---- 升级补种:哪些预置项该补进这台手机 ----

    /** [planSeedMerge] 的结果。 */
    data class SeedPlan(
        /** 该补进盘里的键。**空是正常结果**,不是失败。 */
        val toAdd: List<String>,
        /** 新的「给过哪些」。下次拿它当输入。 */
        val nextOffered: Set<String>,
    )

    /**
     * ★★★ 升级时该补哪些预置项 —— 两条规矩的**全部**逻辑,纯函数。
     *
     * | 情况 | 想要的行为 | 这里怎么做到 |
     * |---|---|---|
     * | 用户**删掉**的 | 永远别回来 | 它在 [offered] 里 → 不算 fresh → 永不补 |
     * | 升级**新加**的 | 必须补上 | 它不在 [offered] 里 → 是 fresh → 补 |
     * | 已经给过、还在盘上 | 别动它(用户可能改过) | 它在 [existing] 里 → 不算 toAdd |
     *
     * ★ 判据是「**我们给过**哪些」,不是「盘上现在有哪些」。这两者差的就是
     *   「用户删掉的那些」—— 拿后者当判据,他删一次我们补一次。
     *
     * ★ 为什么值得单独抽出来做成纯函数:这条逻辑错起来**完全静默**。
     *   真机上 2026-10-05 抓到过一次 —— 新做的「上网查」那只手**根本没上手机**,
     *   而编译、单测、装机全都好好的(详见 `ApiStore.mergeNewSeeds` 的注释)。
     *   它是**唯一**能把这套判据钉在 JVM 上跑的形式。
     *
     * @param offered 以前给过哪些键
     * @param seedKeys 这一版**全部**预置项的键
     * @param existing 盘上现在有哪些键
     */
    fun planSeedMerge(
        offered: Set<String>,
        seedKeys: List<String>,
        existing: Set<String>,
    ): SeedPlan {
        val fresh = seedKeys.filter { it !in offered }
        return SeedPlan(toAdd = fresh.filter { it !in existing }, nextOffered = offered + fresh)
    }

    /** 拼好的载荷正文干不干净。→ null = 干净;非 null = **人话原因**(要回给用户看)。 */
    fun payloadDirtyReason(text: String): String? {
        val low = text.lowercase()
        BANNED_IN_BODY.forEach { if (low.contains(it)) return "载荷里混进了「$it」,不发。" }
        return null
    }

    // ---- 档位的选择 ----

    /**
     * 当前生效那一档。**只认同时满足「id 对得上」和「启用」的那一档。**
     *
     * ★ 禁用一档**不能**悄悄退回同用途的另一档:用户点「禁用」的意思是
     * 「这个用途先别用」,不是「换个 key 继续」。静默换档 = 他以为关了,
     * 实际在花另一个 key 的钱。
     */
    fun active(p: ApiPurpose): ApiProfile? {
        val a = p.profiles.firstOrNull { it.id == p.activeId } ?: return null
        return if (a.enabled) a else null
    }

    /**
     * 这一档能不能用。→ null = 能用;非 null = **人话原因**。
     *
     * ★ 返回人话而不是布尔,是因为这句话要**原样显示给用户**(面板上 / 回执里)。
     * 「校验失败」这四个字对他是零信息。
     */
    fun checkProfile(p: ApiProfile): String? {
        if (p.label.isBlank()) return "这一档没名字。"
        val u = p.baseUrl.trim()
        if (u.isEmpty()) return "还没填地址。"
        if (!u.startsWith("https://") && !u.startsWith("http://")) {
            return "地址要以 https:// 开头(现在是「${ApiMath.ellipsis(u, 24)}」)。"
        }
        return null
    }

    /** 这个用途现在能不能用。→ null = 能用;非 null = 人话原因。 */
    fun checkPurpose(p: ApiPurpose): String? {
        if (p.profiles.isEmpty()) return "还没加过配置档。"
        val a = active(p) ?: return "现在这一档被关掉了。"
        return checkProfile(a)
    }

    // ---- key 的显示与脱敏 ----

    /**
     * 面板上显示用的样子。**永远不返回完整的 key。**
     *
     * 短 key 连头尾都不给 —— 6 位的 key 露 4 位等于露了。
     */
    fun maskKey(k: String): String {
        val s = k.trim()
        if (s.isEmpty()) return "(不需要 key)"
        if (s.length <= 12) return "•".repeat(s.length.coerceAtLeast(4))
        return s.take(4) + "…" + s.takeLast(4)
    }

    /**
     * ★★ 把串里出现的任何 key 换成 `***`。**日志和回执一律走这儿。**
     *
     * 为什么必须有这个函数(不是「记得别打印」):**高德那类接口把 key 放在
     * query 参数里**,于是 URL 本身就含 key。而「出错时把请求 URL 记下来」
     * 是调试时最自然的动作 —— 那一刻 key 就进日志了,而且**没有任何报错**。
     * 靠人记得,迟早会漏;靠一个必经的函数,漏不掉。
     *
     * 从长到短替换,防止短 key 是长 key 的子串时把长的那段切坏。
     */
    fun redact(text: String, keys: Collection<String>): String {
        var out = text
        keys.filter { it.length >= 6 }.sortedByDescending { it.length }.forEach {
            out = out.replace(it, "***")
        }
        return out
    }

    fun ellipsis(s: String, n: Int): String =
        if (s.length <= n) s else s.take(n) + "…"

    // ---- URL 模板 ----

    /** 模板里所有 `{名字}` 占位符。 */
    fun placeholders(tpl: String): Set<String> {
        val out = LinkedHashSet<String>()
        var i = 0
        while (i < tpl.length) {
            val a = tpl.indexOf('{', i)
            if (a < 0) break
            val b = tpl.indexOf('}', a + 1)
            if (b < 0) break
            val name = tpl.substring(a + 1, b).trim()
            if (name.isNotEmpty()) out.add(name)
            i = b + 1
        }
        return out
    }

    /**
     * 把 `{名字}` 填上。**任何一个占位符没填上就返回 null(整个请求不发)。**
     *
     * ★ 这是这个文件里最要紧的一条规则。留一个没填的占位符发出去,
     * 结果只有两种,**两种都比「不发」坏**:
     *   - 对方报参数错 → 白跑一轮,而模型多半会再猜一次、再白跑一轮
     *   - 对方**当成真值收下** → 我们拿到一份关于城市「{city}」的天气,
     *     然后**当成上海的报给用户**。这条没有任何报错。
     *
     * 空串也算没填:`city=` 和 `city={city}` 一样是垃圾输入。
     */
    fun fillTemplate(tpl: String, args: Map<String, String>): String? {
        val need = placeholders(tpl)
        val miss = need.filter { args[it].isNullOrBlank() }
        if (miss.isNotEmpty()) return null
        var out = tpl
        need.forEach { out = out.replace("{$it}", urlEncode(args.getValue(it))) }
        return out
    }

    /**
     * 哪些必填参数没给。**回执要用它说清楚缺什么**,不能只说「参数不全」。
     */
    fun missingArgs(required: Collection<String>, args: Map<String, String>): List<String> =
        required.filter { args[it].isNullOrBlank() }

    /**
     * 拼出真正要请求的 URL。**这是全项目唯一拼 URL 的地方。**
     *
     * → null = **这个请求不能发**(路径里有占位符没填)。
     *
     * 三条规则,分别挡住三种静默故障:
     *
     * 1. **路径里的占位符必须全填** —— 见 [fillTemplate]。留一个 `{city}` 发出去,
     *    对方可能当成真值收下,我们就会拿到一份关于城市「{city}」的天气,
     *    然后当成上海的报给用户。**没有任何报错。**
     * 2. **query 里空值的直接跳过**(那是「可选参数没给」的正常情况),
     *    但**必填**由调用方先用 [missingArgs] 挡住 —— 两件事分开,别混。
     * 3. **`/` 的拼接要归一**:`baseUrl` 可能带尾斜杠、`path` 可能不带前斜杠,
     *    四种组合都要拼成一个斜杠。拼漏了就是 `https://x.comv3/...`
     *    —— 那是个**看起来像域名**的东西,DNS 直接失败,而错误信息
     *    指向一个根本不存在的域名,极难看出是自己拼错的。
     */
    /**
     * 按 [pathSegments] 从响应里走一段，摘出要交给模型的那一块。
     *
     * ★ 放在这里（而不是留在 [CloudHand] 里当私有的）：它和 [pathSegments] 是一对，
     *   而 `pick` 路径写错**不会报错** —— 摘不到时调用方只能回一句「里面没有这一块」。
     *   所以它必须能被离线量具直接钉住（见 `ConnectorCatalogTest` 和现场的
     *   `ConnectorLiveTest`），而 `CloudHand` 里那层是安卓依赖，测不动。
     */
    fun walk(root: Any?, segs: List<Seg>): Any? {
        var cur: Any? = root
        for (s in segs) {
            cur = when (s) {
                is Seg.Key -> (cur as? org.json.JSONObject)?.opt(s.name)
                is Seg.Idx -> (cur as? org.json.JSONArray)?.opt(s.i)
            }
            if (cur == null || cur === org.json.JSONObject.NULL) return null
        }
        return cur
    }

    fun buildUrl(
        baseUrl: String,
        path: String,
        fixedQuery: Map<String, String> = emptyMap(),
        queryNames: List<String> = emptyList(),
        args: Map<String, String> = emptyMap(),
    ): String? {
        val filledPath = fillTemplate(path, args) ?: return null
        val base = baseUrl.trim().trimEnd('/')
        // ★ 先 trim 再 trimStart('/') —— 少了那个 trim,`"  /v3/w"` 会原样带进去,
        //   拼出 `https://x.com/  /v3/w`。这个地址**看起来是对的**(域名、路径都在),
        //   但服务端会 404 或者把它当成另一条路径,而错误信息指着一条根本不存在的 URL。
        val tail = filledPath.trim().trimStart('/')
        val joined = if (tail.isEmpty()) base else "$base/$tail"

        val parts = ArrayList<String>()
        // ★ 写死的那些**也要过一遍模板** —— 因为 API key 就是这么进来的
        //   (`fixedQuery = {"key": "{key}"}`,而 `{key}` 由调用方从当前那一档注入)。
        //   于是「用户还没填 key」会**自动**变成 buildUrl 返回 null = 请求不发,
        //   而不是发一个 `key=` 的空请求出去。**fail closed 是从这儿白来的。**
        fixedQuery.forEach { (k, v) ->
            // ★ 这里必须用**不转义**的换填，再整段转一次。
            //   [fillTemplate] 会把每个值先转义，再交给 urlEncode 就是**两次转义**：
            //   `q=android bluetooth` 会变成 `q=android%2520bluetooth`，
            //   对面按字面量去搜「android%20bluetooth」，**返回 0 条且 HTTP 200**
            //   —— 没有报错，只表现成「这个工具查不到东西」。
            //   2026-11-07 就是靠 ConnectorLiveTest 才看出这一条的。
            parts.add(urlEncode(k) + "=" + urlEncode(fillPlain(v, args) ?: return null))
        }
        queryNames.forEach { n ->
            val v = args[n]
            if (!v.isNullOrBlank()) parts.add(urlEncode(n) + "=" + urlEncode(v))
        }
        // path 里可能自己带了 `?`(比如 `/w?days=3`),那就接 `&` 而不是再来一个 `?`。
        // 两个 `?` 的 URL 有的服务端收得下、有的把后面整段丢掉 —— 后者不报错,
        // 只是「参数丢了」,表现成结果不对。
        if (parts.isEmpty()) return joined
        return joined + (if (joined.contains('?')) "&" else "?") + parts.joinToString("&")
    }

    /**
     * 拼请求头。
     *
     * → null = **这个请求不能发**:某个头的值里有 `{key}` 之类没填上的占位符。
     *
     * ★ 和 [buildUrl] 是同一道 fail-closed,只是位置不同 —— 「key 还没填」在
     *   query 那条路上会变成 url = null,在 header 这条路上会变成 headers = null。
     *   两处都返回 null,调用方的处理就只有一处。
     *
     * ★ 头里的值**不做 URL 转义**。base64 的 key 里带 `+`、`/`、`=`,
     *   编一下就直接坏了,而服务端会回 401 —— 看起来像 key 不对,其实是自己编坏的。
     */
    fun buildHeaders(headers: Map<String, String>, args: Map<String, String>): Map<String, String>? {
        if (headers.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in headers) {
            val name = k.trim()
            if (name.isEmpty()) continue
            out[name] = fillPlain(v, args) ?: return null
        }
        return out
    }

    /** 和 [fillTemplate] 一样查占位符,但**不做 URL 转义**(给请求头用)。 */
    private fun fillPlain(tpl: String, args: Map<String, String>): String? {
        val need = placeholders(tpl)
        if (need.any { args[it].isNullOrBlank() }) return null
        var out = tpl
        need.forEach { out = out.replace("{$it}", args.getValue(it)) }
        return out
    }

    /**
     * query 值的转义。
     *
     * ★ 中文地名**必须**转义 —— 不转义的话 `上海` 会以原始 UTF-8 字节进 URL,
     * 有的服务端收得下、有的收成乱码,而**失败的那边不报错**,
     * 只是返回一个查不到的城市。这是「看起来通了但结果是错的」那一类。
     */
    fun urlEncode(s: String): String {
        val sb = StringBuilder()
        // ★ 一律按**字节**判,不按 Char 判。中文是 3 个字节,而 `Byte.toInt()` 会
        //   符号扩展(0xE5 → -27),再 `toChar()` 出来是个看着像字母的怪字符
        //   (0xFFE5 是全角￥)—— 拿 `isLetterOrDigit()` 去判会**放它过去**,
        //   于是中文原样进 URL。先 `and 0xFF` 回到 0..255 就没有这个歧义了。
        s.toByteArray(Charsets.UTF_8).forEach { byte ->
            val v = byte.toInt() and 0xFF
            val c = v.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                c == '-' || c == '_' || c == '.' || c == '~'
            ) {
                sb.append(c)
            } else {
                sb.append('%').append("%02X".format(v))
            }
        }
        return sb.toString()
    }

    // ---- 响应里的取数路径 ----

    /** 路径的一段:[ApiMath.Seg.Key] 取字段,[ApiMath.Seg.Idx] 取数组第几个。 */
    sealed interface Seg {
        data class Key(val name: String) : Seg
        data class Idx(val i: Int) : Seg
    }

    /**
     * 把 `current_condition[0].temp_C` 切成 `[Key(current_condition), Idx(0), Key(temp_C)]`。
     *
     * ★ 拆成纯逻辑是有意的:路径解析错了**不报错**,它只是安静地返回 null,
     * 表现成「她查到了天气但什么也没说」。这种错只有单测抓得住。
     * 真正的遍历在 [CloudHand](那边才碰 org.json)。
     */
    fun pathSegments(path: String): List<Seg> {
        val out = ArrayList<Seg>()
        path.split('.').forEach { raw ->
            var s = raw.trim()
            if (s.isEmpty()) return@forEach
            // a[0][1] —— 下标写成 `i`,和 `Index` 都认
            while (true) {
                val a = s.indexOf('[')
                if (a < 0) break
                val b = s.indexOf(']', a + 1)
                if (b < 0) break
                if (a > 0) out.add(Seg.Key(s.substring(0, a)))
                val inner = s.substring(a + 1, b).trim()
                val i = if (inner.equals("i", true) || inner.equals("index", true)) 0 else inner.toIntOrNull()
                // ★ 下标解析不出来就整体放弃,不要当成 0 静默取第一个 ——
                //   「路径写错了」和「取第一个」在结果上完全不同。
                out.add(Seg.Idx(i ?: return emptyList()))
                s = s.substring(b + 1)
            }
            if (s.isNotEmpty()) out.add(Seg.Key(s))
        }
        return out
    }

    // ---- 用量 ----

    /**
     * 用量那一行。
     *
     * ★★ **必须带上「这是本机记的账」这句。** 我们记的和服务商账单**一定会有差**
     * (重试、失败、并发、对方侧计费口径),用户拿它去对钱对不上,是我们的错。
     * 这句不是免责声明,是**诚实边界**。
     */
    fun usageLine(u: ApiUsage): String {
        if (u.calls == 0) return "还没调过。"
        val sb = StringBuilder("${u.calls} 次")
        if (u.fail > 0) sb.append("(成功 ${u.ok} / 失败 ${u.fail})")
        if (u.tokensIn + u.tokensOut > 0) sb.append(",token ${u.tokensIn}+${u.tokensOut}")
        if (u.lastMs > 0) sb.append(",上次 ${u.lastMs}ms")
        if (u.lastStatus > 0) sb.append(",HTTP ${u.lastStatus}")
        sb.append("  · 本机记的账,不是服务商账单")
        if (u.lastError.isNotBlank()) sb.append("\n上次出错:${ellipsis(u.lastError, 80)}")
        return sb.toString()
    }

    /**
     * 「调试」页的正文:最近几次调用,一行一次。
     *
     * ★ 一行里**没有载荷的值** —— 见 [ApiCall]。这一层能看到的是
     *   「什么时候、多久、对方回了什么码、它自己说的人话」,看不到他查了什么。
     */
    fun debugLines(u: ApiUsage, nowMs: Long): List<String> {
        if (u.recent.isEmpty()) return listOf("还没有调用记录。")
        return u.recent.map { c ->
            val st = if (c.status > 0) "HTTP ${c.status}" else "没连上"
            val tail = if (c.note.isBlank()) "好" else ellipsis(c.note, 90)
            "${ago(c.atMs, nowMs)} · $st · ${c.ms}ms · $tail"
        }
    }

    /**
     * 「刚刚 / 3 分钟前 / 2 小时前」。
     *
     * ★ 刻意**不**显示绝对时刻:面板上要回答的是「这是不是刚才那下」,
     *   而「09:41」要用户自己在脑子里做减法。日期横跨时空指针还会骗人。
     */
    fun ago(atMs: Long, nowMs: Long): String {
        if (atMs <= 0) return "?"
        val d = (nowMs - atMs).coerceAtLeast(0)
        return when {
            d < 60_000 -> "刚刚"
            d < 3_600_000 -> "${d / 60_000} 分钟前"
            d < 86_400_000 -> "${d / 3_600_000} 小时前"
            else -> "${d / 86_400_000} 天前"
        }
    }

    /** 一次调用之后的新用量。纯函数,所以能测。 */
    fun after(u: ApiUsage, ok: Boolean, ms: Int, status: Int, err: String, nowMs: Long): ApiUsage =
        u.copy(
            calls = u.calls + 1,
            ok = u.ok + if (ok) 1 else 0,
            fail = u.fail + if (ok) 0 else 1,
            lastAtMs = nowMs,
            lastMs = ms,
            lastStatus = status,
            lastError = err,
            // 新的排在最前面:面板上第一眼要看的是「刚刚那一次」。
            recent = (listOf(ApiCall(nowMs, ms, status, ok, ellipsis(err, 140))) + u.recent)
                .take(RECENT_MAX),
        )

    // ---- 面板那一行要显示什么 ----

    /** 二级页那一行的副标题:**当前生效的是谁**,不用点进去就能看见。 */
    fun summary(p: ApiPurpose): String {
        val bad = checkPurpose(p)
        if (bad != null) return bad
        val a = active(p)!!
        val who = if (p.profiles.size == 1) a.label else "${a.label}(共 ${p.profiles.size} 档)"
        val k = if (a.keyless) "不需要 key" else "key ${maskKey(a.apiKey)}"
        return "$who · $k"
    }
}
