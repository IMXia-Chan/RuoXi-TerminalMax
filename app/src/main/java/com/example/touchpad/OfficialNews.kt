package com.example.touchpad

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 央媒那一路：**新华网、人民网的当日要闻**。
 *
 * ## 为什么单开一路
 *
 * 他 2026-11-07 说「新闻来源也要多，特别是新华网，人民日报那种肯定得多点」。
 * 而今日热榜那套里**一个央媒节点都没有** —— 2026-11-07 把 tophub 的 `c/news`、`c/tech`
 * 两个分区页全解析了一遍，十二个节点里没有一个对应新华网或人民日报。
 * 所以央媒只能自己抓。
 *
 * ## 为什么不走 RSS
 *
 * 两家的 RSS **都已经停更**，而且是停了好几年的那种，接口却照样回 200：
 *
 * | 源 | 最后一条 |
 * |---|---|
 * | `people.com.cn/rss/politics.xml` | 2025-06-05 |
 * | `xinhuanet.com/politics/news_politics.xml` | 疫情期（2022 年以前） |
 * | `news.cn/politics/` 那个老页面 | 2022-07 |
 *
 * ★ 这比「取不到」更坏：**回 200、有内容、但全是旧闻**。一块写着两年前新闻却不说自己旧的屏，
 *   比空屏还坏。所以宁可抓首页 HTML —— 首页至少是编辑当天排的。
 *
 * 抓首页这件事本身是脆的，所以：
 *   · 只认**链接里带日期**的那种（`/20261107/...`、`/n1/2026/1107/...`），
 *     并且日期必须是今天或昨天 —— 首页上滞留着几个月前的头条链接，不筛就会混进来；
 *   · 解析出零条就当这一路失败，用别的来源，不写一块空白；
 *   · 标题形态奇怪的（「更多」「专题」「首页」这种导航文字）直接丢掉。
 *
 * ## 和「云端总闸」的关系
 *
 * 和热点、天气、语录一样归那道总闸管：关着就不发。
 */
internal object OfficialNews {

    /** 新华网首页。2026-11-07 实测：回 200，首页链到当天的稿件。 */
    const val XINHUA = "xinhua"

    /** 人民网首页。2026-11-07 实测：回 200，首页链到当天的稿件。 */
    const val PEOPLE = "people"

    private const val XINHUA_URL = "https://www.news.cn/"
    /** 人民网首页。**用 https**：2026-11-07 实测两个协议都给同一份页面，https 少一道明文。 */
    private const val PEOPLE_URL = "https://www.people.com.cn/"

    private const val TIMEOUT = 10_000

    /** 每一路最多收这么多条。首页上的要闻本来也就这个量级。 */
    const val MAX = 40

    /**
     * 取两家的当日要闻。**必须在后台线程调**（内部同步阻塞，最长两次 [TIMEOUT]）。
     *
     * @return 合成后的条目；两家都失败返回空表（调用方据此只说「央媒没取到」，不影响其它来源）。
     */
    fun fetchAll(ctx: Context): List<NewsItem> {
        val app = ctx.applicationContext
        val today = ymd(System.currentTimeMillis())
        val out = ArrayList<NewsItem>(MAX * 2)

        val xh = get(app, XINHUA_URL, Charsets.UTF_8)
        if (xh != null) {
            val one = OfficialMath.fromXinhua(xh, today, MAX)
            if (one.isEmpty()) trace(app, "热点:新华网首页解析出 0 条（${xh.length} 字节）")
            out.addAll(NewsMath.withBoard(one, XINHUA))
        } else {
            trace(app, "热点:新华网首页取不到")
        }

        val ppl = get(app, PEOPLE_URL, Charsets.UTF_8)
        if (ppl != null) {
            val one = OfficialMath.fromPeople(ppl, today, MAX)
            if (one.isEmpty()) trace(app, "热点:人民网首页解析出 0 条（${ppl.length} 字节）")
            out.addAll(NewsMath.withBoard(one, PEOPLE))
        } else {
            trace(app, "热点:人民网首页取不到")
        }

        if (out.isNotEmpty()) {
            trace(app, "热点:央媒 ${out.count { it.board == XINHUA }} 条新华 / " +
                "${out.count { it.board == PEOPLE }} 条人民")
        }
        return out
    }

    /** `yyyyMMdd`，按本地时区。 */
    fun ymd(ms: Long): String = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date(ms))

    private fun get(app: Context, url: String, cs: java.nio.charset.Charset): String? = try {
        val conn = CloudNet.conn(url, app).apply {
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            requestMethod = "GET"
            // 两家对没有 UA 的请求都会给一个精简页或者直接 403
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
            )
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
        }
        try {
            val code = conn.responseCode
            if (code != 200) {
                trace(app, "热点:HTTP $code（${url.take(32)}）")
                null
            } else {
                conn.inputStream.bufferedReader(cs).use { it.readText() }
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        trace(app, "热点:取不到（${e.javaClass.simpleName}:${e.message}）")
        null
    }

    private fun trace(ctx: Context, msg: String) {
        try {
            ModelManager.get(ctx).trace(msg)
        } catch (_: Exception) {
        }
    }
}

/**
 * [OfficialNews] 的纯算术部分：从两家的首页 HTML 里抠出标题。**没有 Context、不碰网络**。
 *
 * ★ 这一层的难点不是正则，是**判据**：
 *   · 「链接里有日期」是唯一可靠的筛选条件 —— 首页上也挂着导航、专题、旧头条，
 *     单看 `<a>` 抠不出来；
 *   · 日期必须是今天或昨天。只认今天会漏掉午夜前发的稿，
 *     不设上限则会把首页上几个月前的头条一起收进来（实测人民网首页 2026-11-07 还链着 09-26 的稿）。
 */
internal object OfficialMath {

    /** 首页上最多往回看几天（含今天）。 */
    const val KEEP_DAYS = 2

    /** 标题长度在这里之外的一律不收：太短是导航，太长是整段摘要。 */
    const val MIN_LEN = 5
    const val MAX_LEN = 44

    /**
     * 新华网的稿件链接：`20261107/38688dbea1ee4c22963c2a1a0ce306df/c.html`，
     * 页面上是相对路径，前面有时带 `https://www.news.cn/`。
     *
     * 组：1 = 路径（`20261107/<32 位哈希>/c.html`），2 = 日期（`20261107`）。
     */
    private val XINHUA_LINK = Regex(
        """href=["'](?:https?://www\.news\.cn/)?((\d{8})/[0-9a-fA-F]{32}/c\.html)["']""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 人民网的稿件链接：`http://politics.people.com.cn/n1/2026/1106/c1024-40829279.html`。
     *
     * 组：1 = 绝对地址，2/3/4 = 年 / 月 / 日。
     */
    private val PEOPLE_LINK = Regex(
        """href=["'](https?://[a-z0-9]+\.people\.com\.cn/n1/(\d{4})/(\d{2})(\d{2})/c\d+-\d+\.html)["']""",
        RegexOption.IGNORE_CASE,
    )

    /** 从 `<a ...>…</a>` 里取正文（要跨行）。 */
    private val ANCHOR = Regex("""<a[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    private val TAGS = Regex("""<[^>]+>""")
    private val SPACES = Regex("""[\s　]+""")

    /** 导航文字、按钮文字、专题名。这些也长得像标题，收进来就是噪声。 */
    private val NOISE = listOf(
        "更多", "查看更多", "详细", "点击", "专题", "首页", "上一页", "下一页",
        "返回", "登录", "注册", "下载", "客户端", "English", "日本語",
    )

    /**
     * 新华网首页。
     *
     * @param today `yyyyMMdd`，按本地时区。
     */
    fun fromXinhua(html: String, today: String, max: Int = OfficialNews.MAX): List<NewsItem> =
        pick(
            html, XINHUA_LINK, today, max,
            linkOf = { m -> "https://www.news.cn/" + m.groupValues[1] },
            dateOf = { m -> m.groupValues[2] },
        )

    /**
     * 人民网首页。
     *
     * @param today `yyyyMMdd`。
     */
    fun fromPeople(html: String, today: String, max: Int = OfficialNews.MAX): List<NewsItem> =
        pick(
            html, PEOPLE_LINK, today, max,
            linkOf = { m -> m.groupValues[1] },
            // 年 / 月 / 日 分在三个捕获组里，拼回 yyyyMMdd
            dateOf = { m -> m.groupValues[2] + m.groupValues[3] + m.groupValues[4] },
        )

    /**
     * 两家的共同逻辑：找出链接 → 判日期 → 取标题 → 去重。
     *
     * [linkOf] 从匹配结果里取出绝对地址，[dateOf] 取出这条链接的 `yyyyMMdd`。
     */
    private fun pick(
        html: String,
        link: Regex,
        today: String,
        max: Int,
        linkOf: (MatchResult) -> String,
        dateOf: (MatchResult) -> String,
    ): List<NewsItem> {
        if (html.isEmpty() || max <= 0) return emptyList()
        val keep = recentDays(today, KEEP_DAYS)
        if (keep.isEmpty()) return emptyList()

        val seen = HashSet<String>()
        val out = ArrayList<NewsItem>(max)
        for (m in link.findAll(html)) {
            if (out.size >= max) break
            if (dateOf(m) !in keep) continue
            val title = clean(anchorText(html, m))
            if (!looksLikeHeadline(title)) continue
            if (!seen.add(InterestMath.key(title))) continue
            out.add(NewsItem(out.size + 1, title, "", linkOf(m), board = ""))
        }
        return out
    }

    /** 取这个链接之后紧跟着的那个 `<a>…</a>` 的正文。 */
    private fun anchorText(html: String, link: MatchResult): String {
        // 链接匹配结束的位置之后，第一个 `<a>` 的正文就是标题。
        // ★ 有的是 `<a href="…">标题</a>`，所以从链接开头往前找最近的 `<a`，比往后找稳。
        val start = html.lastIndexOf("<a", link.range.first).coerceAtLeast(0)
        val slice = html.substring(start, (start + 400).coerceAtMost(html.length))
        val m = ANCHOR.find(slice) ?: return ""
        return m.groupValues[1]
    }

    /** 去掉标签与多余空白。 */
    fun clean(raw: String): String = SPACES.replace(TAGS.replace(raw, " "), " ").trim()

    /** 像不像一条新闻标题。 */
    fun looksLikeHeadline(t: String): Boolean {
        if (t.length !in MIN_LEN..MAX_LEN) return false
        if (t.none { it.isLetterOrDigit() }) return false
        for (n in NOISE) if (t.equals(n, ignoreCase = true)) return false
        return true
    }

    /**
     * 最近 [days] 天（含今天）的 `yyyyMMdd` 集合。今天那个解析不了就返回空集 ——
     * 调用方据此什么都不收，而不是收一堆不知道哪天的东西。
     */
    fun recentDays(today: String, days: Int): Set<String> {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.CHINA).apply { isLenient = false }
        val base = try {
            fmt.parse(today)
        } catch (_: Exception) {
            null
        } ?: return emptySet()
        val out = HashSet<String>(days.coerceAtLeast(1))
        val cal = java.util.Calendar.getInstance(Locale.CHINA)
        for (i in 0 until days.coerceAtLeast(1)) {
            cal.time = base
            cal.add(java.util.Calendar.DAY_OF_YEAR, -i)
            out.add(fmt.format(cal.time))
        }
        return out
    }
}
