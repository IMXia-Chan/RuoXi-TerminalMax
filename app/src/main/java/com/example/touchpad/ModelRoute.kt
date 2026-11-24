package com.example.touchpad

/**
 * **一家模型**：地址、模型名、key、能干什么。
 *
 * 他 2026-11-06 提的方向：不只接一家，而是按**模态**接几家 —— 能看图的走一家、
 * 写代码的走另一家、听声音的（实际上做不了，见下）再走一家，拆活的时候自动认出来。
 *
 * ★ **音频这一档不存在**，别在这里给它留位置：他定过红线「我的声音不要传上去」。
 *   声音要么在本机转成文字再出门（那是 [Ear] 那条路），要么是云端合成好发下来 —
 *   两者都不需要「把音频上传给模型」。所以 [can] 里只有文字 / 看图 / 代码。
 *
 * @param can 这一家能干的事。取 [RouteMath.TEXT] / [RouteMath.VISION] / [RouteMath.CODE]。
 */
data class Endpoint(
    val label: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val can: Set<String> = setOf(RouteMath.TEXT),
)

/**
 * **哪件事该交给哪一家** —— 纯规则，不碰网络。
 *
 * ★ 判据放在这里的原因和 [SkillMath] 一样：它错了**不崩**。挑错了端点的症状是
 *   「回来的东西看着挺像那么回事，但不对」—— 比如让一个纯文字模型去描述一张图，
 *   它会编一段听起来完全合理的描述，而账上写着一切正常。
 */
internal object RouteMath {

    const val TEXT = "text"
    const val VISION = "vision"
    const val CODE = "code"
    /**
     * 生图：文字进、图片出。
     *
     * ★ 和 [VISION] 是**两个方向**，别合并：「看图」是把图发给模型，「生图」是把图拿回来。
     *   混成一个的话，一家只会看图的模型会被派去画图，而它不会说做不到 ——
     *   它可能给你回一段文字描述，或者干脆 400。
     */
    const val IMAGE = "image"

    /**
     * 拆活时那一行的第三列能写什么 → 规范键。
     *
     * ★ 认不出来的一律当**文字**：这是保守的那一边 —— 文字每一家都能做，
     *   写错最坏是多花一次请求；而把文字活误判成「要看图」的代价是**没人接**。
     */
    fun needOf(raw: String, purposes: List<Pair<String, String>> = PURPOSES): String {
        val s = raw.trim()
        if (s.isEmpty()) return TEXT
        // ★ 他自己加的那些（「视频」「语音」）先认：名字对得上就是它，
        //   不能让下面的「含『图』字」把它抢走。
        purposes.firstOrNull { it.second == s }?.let { return it.first }
        return when {
            s.contains("图") || s.contains("看") || s.contains("vision", ignoreCase = true) -> VISION
            s.contains("代码") || s.contains("编程") || s.contains("code", ignoreCase = true) -> CODE
            else -> TEXT
        }
    }

    /** 那一列**是不是**一个「需要」。认不出来的不能当需要 —— 否则任务正文里的最后一截会被吃掉。 */
    private val NEED_WORDS = setOf(
        "文字", "文本", "看图", "看图片", "图片", "视觉", "代码", "编程",
        "text", "vision", "code",
    )

    fun isNeedWord(raw: String, purposes: List<Pair<String, String>> = PURPOSES): Boolean {
        val s = raw.trim()
        if (s.lowercase() in NEED_WORDS) return true
        // ★ 他自己起的那几类（「音频」「视频」）也算：加了这一类，
        //   拆活那一行的第三列写「|音频」就该被认出来。不认的话
        //   「音频」两个字会被当成任务正文的一部分，而这一类永远轮不到活。
        return purposes.any { it.second == s }
    }

    /**
     * 发得出去吗：地址和模型得有。
     *
     * ★ **key 允许空** —— 手机本机那台 llama-server（127.0.0.1）就没有 key。
     *   以前这里把 key 也算进去，于是本地模型永远「配置不完整」，而报错长得像填错了。
     */
    fun viable(ep: Endpoint): Boolean = ep.baseUrl.isNotBlank() && ep.model.isNotBlank()

    /**
     * 挑一家能干这件事的。**null = 没有。**
     *
     * ★ 只挑**自己声明了能干**的，绝不「拿一家凑合」：
     *   让文字模型去看图，它不会说「我看不到」，它会编。
     * ★ 唯一的例外是 [CODE]：写代码是文字，能干文字的就一定能干它 ——
     *   只是没有专门声明的那么强。所以它降级到文字，而看图**不降级**。
     */
    fun pick(all: List<Endpoint>, need: String): Endpoint? {
        val pool = all.filter { viable(it) }
        pool.firstOrNull { need in it.can }?.let { return it }
        return if (need == CODE) pool.firstOrNull { TEXT in it.can } else null
    }

    /**
     * 完整接口地址。
     *
     * ★ 不能一律拼 `/v1/chat/completions`：豆包方舟是 `/api/v3/chat/completions`，
     *   通义兼容模式是 `/compatible-mode/v1/chat/completions`。所以设置里**整条填**，
     *   这一行只负责「没写全的路由就补上最常见的那一种」。
     */
    fun url(ep: Endpoint): String {
        val b = ep.baseUrl.trim().trimEnd('/')
        // ★ 写全了的路径原样用 —— 现在有两种：OpenAI 兼容的 `/chat/completions`，
        //   和 Anthropic 的 `/messages`（见 [AnthropicProto]）。
        if (b.endsWith("/chat/completions") || b.endsWith("/messages")) return b
        if (b.contains("anthropic.com")) return "$b/v1/messages"
        return "$b/v1/chat/completions"
    }

    /**
     * 界面上「一类活」的清单：键 → 给人看的名字。
     *
     * ★ 做成一张表而不是三个常量：以后要加「视频」「语音」这类用途，加一行就行。
     *   但**加之前先想清楚有没有活会用到它** —— 加了一个没人用的用途，
     *   结构上就是那句「我们支持，但用不起来」，比没有更糟。
     */
    val PURPOSES: List<Pair<String, String>> = listOf(
        TEXT to "文字",
        VISION to "看图",
        CODE to "写代码",
        // ★ 2026-11-07 加。它必须排在 [needOf] 的「含图字就算看图」那条规律**之前**被认出来 ——
        //   `needOf` 是先查这张表、再走关键字，所以「生图」不会被抢成「看图」。
        IMAGE to "生图",
    )

    /**
     * 他自己加的用途（视频、语音……），盘上存成一行一类的名字。
     *
     * ★ 和 [PURPOSES] 合起来才是界面上的整张清单：前三样是内置的，
     *   后面那些是他加的。**键就用名字本身** —— 他自己起的中文名
     *   在盘上、在账上都要认得出是同一件事，再编一套英文键只会两处对不上。
     */
    fun purposes(custom: String): List<Pair<String, String>> {
        val out = ArrayList(PURPOSES)
        for (line in custom.split(',', '，', '\n')) {
            val s = line.trim()
            if (s.isEmpty() || s.length > 12) continue
            if (out.none { it.second == s }) out.add(s to s)
        }
        return out
    }

    /** 用途的键 → 名字。认不出来就当文字。 */
    fun purposeLabel(key: String, purposes: List<Pair<String, String>> = PURPOSES): String =
        purposes.firstOrNull { it.first == key }?.second ?: "文字"

    /**
     * 端点表 → 设置里那份文本。**和 [parse] 是一对**：来回走两趟必须一模一样。
     *
     * ★ 一个端点能干几样就写几样。界面上一家填一行、只填一个用途，
     *   所以大多数时候这里出来的就是「一行一家、一样」。
     */
    fun format(list: List<Endpoint>, purposes: List<Pair<String, String>> = PURPOSES): String =
        list.joinToString("\n") { ep ->
        // ★ 写**中文名**不写键：「看图」「写代码」人一眼看得懂，盘上那份万一要手改也不会错。
        val can = if (ep.can.isEmpty()) {
            purposeLabel(TEXT, purposes)
        } else {
            purposes.filter { it.first in ep.can }.map { it.second }
                .ifEmpty { listOf(purposeLabel(TEXT, purposes)) }
                .joinToString(",")
        }
        listOf(ep.label, ep.baseUrl, ep.model, ep.apiKey, can).joinToString("|")
    }

    /**
     * 设置里那一栏的文本 → 端点表。**一行一家**：
     *
     * ```
     * # 名字|地址|模型|key|能干什么(文字/看图/代码，逗号分开)
     * 通义|https://dashscope.aliyuncs.com/compatible-mode|qwen-vl-max|sk-xxx|看图,文字
     * ```
     *
     * ★ 注释行和写不全的行**跳过**，不报错：这一栏是手填的，写到一半是常态，
     *   而拦住整个保存的代价是「他以为存上了、其实没有」。
     */
    fun parse(text: String, purposes: List<Pair<String, String>> = PURPOSES): List<Endpoint> {
        val out = ArrayList<Endpoint>()
        for (line in text.lines()) {
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#")) continue
            val p = s.split('|').map { it.trim() }
            // ★★ 2026-11-08:`< 5`,不是 `< 4`。下一行无条件读 `p[4]`,只挡 4 段的话,
            //   最自然的一种写法「名字|地址|模型|key」(漏了「能干什么」)正好落在 4 段上
            //   → 抛 IndexOutOfBoundsException。保存时这份原文是**不校验直接落盘**的,
            //   从此每次进设置页 `refresh()` 在 UI 线程求值 endpoints 都会崩,重启也躲不掉。
            //   写不全的行按上面的约定**跳过**,不报错。
            if (p.size < 5) continue
            val can = HashSet<String>()
            for (token in p[4].split(',', '，', '、', '/' , ' ')) {
                val t = token.trim()
                if (t.isEmpty()) continue
                can.add(needOf(t, purposes))
            }
            if (can.isEmpty()) can.add(TEXT)
            out.add(Endpoint(label = p[0].ifEmpty { p[2] }, baseUrl = p[1], model = p[2], apiKey = p[3], can = can))
        }
        return out
    }

    /**
     * 一段活的「需要」写成给他的那句话里该有的样子。
     *
     * ★ 没端点的时候必须**说出来**：不然那一份回来的东西看着正常，
     *   而它其实是在没有任何依据的情况下编的。
     */
    fun missing(need: String): String = when (need) {
        VISION -> "这一段本来要看图，但现在没有能看图的模型（设置 → AI 设置 → 模型清单里没填）——" +
            "只能凭文字做。**说清楚你没看到图**，别描述你没见过的东西。"
        CODE -> "这一段要写代码，先按文字做。"
        IMAGE -> "这一段要画图，但现在没有能画图的模型（设置 → AI 设置 → 模型清单里加一行，能干什么写「生图」）。" +
            "**说清楚你画不了**，不要用文字假装是图。"
        else -> "这一段没有能用的模型。"
    }
}
