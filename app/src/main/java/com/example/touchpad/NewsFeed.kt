package com.example.touchpad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 房间里左边那块屏上的「今日热点」。
 *
 * ## 数据从哪来
 *
 * 走**今日热榜**那一套公开接口：`api.codelife.cc/api/top/list?lang=cn&id=<榜单号>`。
 * 2026-11-07 从这台电脑上实测九个榜单都通（见 [BOARDS]，各十到五十条），
 * 返回结构是 `{"code":200,"data":[{"index","title","hotValue","link"}]}`。
 *
 * 另外单开一路**央媒**（新华网 / 人民网首页抓取，见 [OfficialNews]）——
 * 今日热榜那套里一个央媒节点都没有，实测把 tophub 的 `c/news`、`c/tech` 全解析过。
 *
 * ★ 为什么不是必应的 RSS（[WebSearch] 用的那条）：RSS 是**按关键词搜**的，
 *   而这块屏要的是「今天大家在说什么」。两者不是一件事，别为了复用一条路
 *   把另一个功能硬掰过来。
 *
 * ★ 这几条接口都是第三方聚合，**会挂**。所以：
 *   · 成功一次就**落一份缓存**到 `filesDir/room/news.json`；
 *   · 下一次取不到就显示缓存，并把「几点更新的」摆在标题旁边 ——
 *     一块写着旧闻却不说自己旧的屏，比空屏更坏。
 *
 * ## 和「云端总闸」的关系
 *
 * 取热点是**对外发一个请求**。他给云端兜底定过总闸语义：「关掉它，一个字节都不出去」
 * （见记忆 `ruoxi-cloud-fallback`）。所以这里也归那道闸管 ——
 * 关着的时候**不发**，屏上明写「云端总闸关着」，只用缓存。
 * 这条不是性能取舍，是那条闸说了算。
 *
 * ## 纯算术在 [NewsMath]
 *
 * 解析、截断、压热度值全都是错不了的写法里最容易悄悄出错的那种
 * （换一个榜单就换一层嵌套），所以单独拆出来钉住。
 */
internal object NewsFeed {

    /** 榜单号，都是今日热榜的节点 id，2026-11-07 逐个实测过（返回 200 且有条目）。 */
    const val BAIDU = "Jb0vmloB1G"
    const val WEIBO = "KqndgxeLl9"
    const val ZHIHU = "mproPpoq6O"
    const val DOUYIN = "DpQvNABoNE"
    const val ITHOME = "74Kvx59dkx"
    const val KR36 = "Q1Vd5Ko85R"
    const val TENCENT = "12owgX0oNV"
    const val QUANTUM = "MZd7azPorO"
    const val SSPAI = "Y2KeDGQdNP"

    /** 默认榜单。百度热搜：时效性和「一句话说清」在这几个里最稳。 */
    const val DEFAULT = BAIDU

    private const val DIR = "room"
    private const val FILE = "news.json"

    fun label(board: String): String = when (board) {
        OfficialNews.XINHUA -> "新华网"
        OfficialNews.PEOPLE -> "人民日报"
        WEIBO -> "微博"
        ZHIHU -> "知乎"
        DOUYIN -> "抖音"
        ITHOME -> "IT之家"
        KR36 -> "36氪"
        TENCENT -> "腾讯"
        QUANTUM -> "量子位"
        SSPAI -> "少数派"
        else -> "百度"
    }

    fun nextBoard(board: String): String = when (board) {
        BAIDU -> WEIBO
        WEIBO -> ZHIHU
        else -> BAIDU
    }

    fun url(board: String): String =
        "https://api.codelife.cc/api/top/list?lang=cn&id=" + java.net.URLEncoder.encode(board, "UTF-8")

    // ── 缓存 ────────────────────────────────────────────────────────────

    private fun file(ctx: Context): File? = try {
        val d = File(ctx.filesDir, DIR).apply { mkdirs() }
        File(d, FILE)
    } catch (_: Exception) {
        null
    }

    /** 盘上的那一份。没有 / 读坏了 → 空表（调用方按「还没取过」处理）。 */
    fun cached(ctx: Context): List<NewsItem> = cache(ctx).second

    /** 盘上那份是什么时候存的。0 = 没存过。 */
    fun cachedAt(ctx: Context): Long = cache(ctx).first

    private fun cache(ctx: Context): Pair<Long, List<NewsItem>> {
        val f = file(ctx) ?: return 0L to emptyList()
        if (!f.isFile) return 0L to emptyList()
        return try {
            val o = JSONObject(f.readText())
            val at = o.optLong("at", 0L)
            val arr = o.optJSONArray("items") ?: JSONArray()
            at to NewsMath.itemsFromArray(arr)
        } catch (_: Exception) {
            0L to emptyList()
        }
    }

    private fun save(ctx: Context, board: String, items: List<NewsItem>): Boolean {
        val f = file(ctx) ?: return false
        return try {
            val arr = JSONArray()
            for (n in items) {
                arr.put(JSONObject().put("rank", n.rank).put("title", n.title)
                    .put("hot", n.hot).put("link", n.link).put("board", n.board))
            }
            val o = JSONObject()
                .put("at", System.currentTimeMillis())
                .put("board", board)
                .put("items", arr)
            // ★ 先写 .tmp 再 rename，和 [SkillStore.save] 同一套 ——
            //   半截 JSON 读回来是一条「空热点」，而那和「今天真没新闻」长得一样。
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (tmp.renameTo(f)) true else {
                f.writeText(o.toString()); tmp.delete(); true
            }
        } catch (_: Exception) {
            false
        }
    }

    // ── 网取 ────────────────────────────────────────────────────────────

    /**
     * 九个榜 + 央媒一路，合起来取，合成一个池子。
     *
     * ★ 2026-11-07 他看过真机之后说「新闻有点少了，我要每 1 小时全部换一遍新的」，
     *   紧接着又说「来源要多一点」。原来三个榜、每榜留 20 条，池子约 60 条 ——
     *   按每小时 12 条轮换，五个小时就转回原处。
     *
     * 现在综合四个（百度 / 微博 / 知乎 / 抖音）、新闻一个（腾讯）、
     * 科技四个（IT之家 / 36氪 / 量子位 / 少数派），池子约 200 条，够转一整天。
     * ★ 这一条列表**只放实测返回 200 且有内容的**：今日头条（x9ozB4KoXb）、
     *   澎湃（wWmoO5Rd4E）、机器之心（DQvnNz1vEB）三个当时都是 0 条，没有放进来。
     */
    val BOARDS = listOf(BAIDU, WEIBO, ZHIHU, DOUYIN, TENCENT, ITHOME, KR36, QUANTUM, SSPAI)

    /**
     * 出去取一轮，**必须在后台线程调**（内部同步阻塞，最长三倍 [TIMEOUT]）。
     *
     * @return 合成后的池子（已去重、已落盘）；三条一条都没取到返回 null。
     *   ★ 单个榜单失败不算失败 —— 谁家接口抽风都不该让整块屏空着。
     */
    fun fetchPool(ctx: Context): List<NewsItem>? {
        val app = ctx.applicationContext
        val all = ArrayList<NewsItem>()
        var ok = 0
        for (b in BOARDS) {
            val one = fetchOne(app, b) ?: continue
            ok++
            all.addAll(one)
        }
        // 央媒单独一路（首页抓取，不是榜单）。取不到不影响上面九个。
        val official = OfficialNews.fetchAll(app)
        if (official.isNotEmpty()) ok++
        all.addAll(official)
        if (all.isEmpty()) {
            trace(app, "热点:十个来源全没取到，用缓存")
            return null
        }
        // 同一条新闻常常好几个源都在。按标题去重，留下名次最靠前的那一份。
        val seen = HashMap<String, NewsItem>()
        for (n in all) {
            val k = InterestMath.key(n.title)
            val old = seen[k]
            if (old == null || n.rank < old.rank) seen[k] = n
        }
        // ★ 央媒排前面：他 2026-11-07 说「新华网、人民日报那种肯定得多点」。
        //   排在前面只是池子里的顺序，真正决定屏上先后的是 [InterestMath.rank]，
        //   那里的加权见 [NewsItem.official]。
        val pool = seen.values.sortedWith(
            compareBy<NewsItem> { if (it.official) 0 else 1 }.thenBy { it.rank }
        )
        save(app, if (ok == BOARDS.size + 1) "pool" else "partial", pool)
        trace(app, "热点:$ok/${BOARDS.size + 1} 个来源，去重后 ${pool.size} 条" +
            "（央媒 ${pool.count { it.official }} 条）")
        return pool
    }

    private fun fetchOne(app: Context, board: String): List<NewsItem>? {
        return try {
            val conn = CloudNet.conn(url(board), app).apply {
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                requestMethod = "GET"
                // 这几个站点对没有 UA 的请求会直接给 403
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
                )
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                if (code != 200) {
                    trace(app, "热点:HTTP $code（${label(board)}）")
                    return null
                }
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val items = NewsMath.withBoard(NewsMath.parse(text), board)
                if (items.isEmpty()) trace(app, "热点:${label(board)} 解析出 0 条（${text.length} 字节）")
                items.ifEmpty { null }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            trace(app, "热点:${label(board)} 取不到（${e.javaClass.simpleName}:${e.message}）")
            null
        }
    }

    private const val TIMEOUT = 8_000

    private fun trace(ctx: Context, msg: String) {
        try {
            ModelManager.get(ctx).trace(msg)
        } catch (_: Exception) {
        }
    }

    /** 面板标题旁边那行小字：`08:24`。没取过就是空串。 */
    fun stamp(at: Long): String =
        if (at <= 0L) "" else SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(at))
}

/**
 * 一条热点。
 *
 * [rank] 是榜单原来的名次（通常 1..50），[hot] 是原样的热度字串（「780.9万」这种），
 * 可能为空 —— 有些榜单不给。
 */
internal data class NewsItem(
    val rank: Int,
    val title: String,
    val hot: String,
    val link: String,
    /** 来自哪个源（[NewsFeed.BAIDU]、[OfficialNews.XINHUA] 这种）。画在标题下面那一行小字上。 */
    val board: String = "",
) {
    /**
     * 央媒（新华网 / 人民日报）。
     *
     * ★ 他说「新华网、人民日报那种肯定得多点」，所以这两个源的条目在排序里有一个固定加成，
     *   见 [InterestMath.rank]。加成按来源算、不按标题内容算 —— 猜内容太容易猜错。
     */
    val official: Boolean
        get() = board == OfficialNews.XINHUA || board == OfficialNews.PEOPLE
}

/**
 * [NewsFeed] 的纯算术部分。**没有 Context、不碰网络**，所以能单测。
 */
internal object NewsMath {

    /**
     * 一个榜最多收这么多条。
     *
     * ★ 2026-11-07 从 20 提到 50：池子越大，每小时换一批时越不会「转两圈就回到原处」。
     *   接口本来就给五十条，收满不额外花一次请求。
     */
    const val MAX_ITEMS = 50

    /**
     * 解析今日热榜那套 JSON。
     *
     * 认三种写法，因为这几家聚合接口的壳子不一样：
     * ```
     * {"data":[{...}]}          ← 今日热榜
     * {"data":{"list":[{...}]}} ← 有些镜像
     * [{...}]                   ← 直接给数组
     * ```
     * 一条都不认就返回空表 —— 调用方据此保留缓存，而不是把屏清空。
     */
    fun parse(text: String): List<NewsItem> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        return try {
            if (t.startsWith("[")) return itemsFromArray(JSONArray(t))
            val o = JSONObject(t)
            val data = o.opt("data") ?: o.opt("result") ?: o.opt("items")
            when (data) {
                is JSONArray -> itemsFromArray(data)
                is JSONObject -> {
                    val inner = data.opt("list") ?: data.opt("items") ?: data.opt("data")
                    if (inner is JSONArray) itemsFromArray(inner) else emptyList()
                }
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 给一批条目打上来源榜单。合成池子的时候用。 */
    fun withBoard(items: List<NewsItem>, board: String): List<NewsItem> =
        items.map { it.copy(board = board) }

    fun itemsFromArray(arr: JSONArray): List<NewsItem> {
        val out = ArrayList<NewsItem>(minOf(arr.length(), MAX_ITEMS))
        for (i in 0 until arr.length()) {
            if (out.size >= MAX_ITEMS) break
            val o = arr.optJSONObject(i) ?: continue
            val title = (o.optString("title").ifBlank { o.optString("name") }).trim()
            if (title.isEmpty()) continue
            // 原样给的名次；没给就按位置排。★ 名次只用来排序和标红，不该因为它缺
            // 就把一整条丢掉。
            val rank = o.optInt("index", 0).let { if (it > 0) it else i + 1 }
            val hot = hotText(o.optString("hotValue").ifBlank { o.optString("hot") })
            out.add(NewsItem(rank, title, hot,
                o.optString("link").ifBlank { o.optString("url") }, o.optString("board")))
        }
        return out
    }

    /**
     * 热度那一栏压成短的样子。榜单给的是「780.9万」「1.2亿」，本来就短；
     * 有些会给「7809000 热度」这种，压一下。压不动就原样。
     */
    fun hotText(raw: String): String {
        val s = raw.replace(" ", " ").trim()
        if (s.isEmpty()) return ""
        // 纯数字超过四位的，用「万」表示，保留一位小数
        val digits = s.filter { it.isDigit() }
        if (digits.isNotEmpty() && digits.length == s.length && digits.length > 4) {
            val v = digits.toLongOrNull() ?: return s
            return String.format(Locale.CHINA, "%.1f万", v / 10000.0)
        }
        return s
    }

    /**
     * 标题压成放得下的样子。**中英文都按字符数算**（等宽近似）：
     * 这块屏一行约等于 [perLine] 个汉字，两行就这么多。
     * ★ 去掉换行和连续空格 —— 榜单标题里带 `\n` 的不少，直接塞进 TextView 会撑高一行。
     */
    fun clip(raw: String, max: Int = 34): String {
        val s = raw.replace(Regex("[\\s\\u3000]+"), " ").trim()
        if (s.length <= max) return s
        return s.substring(0, (max - 1).coerceAtLeast(1)) + "…"
    }
}
