package com.example.touchpad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 房间里右下那块屏的语录：**联网取，中英文都有**。
 *
 * ## 为什么不只用内置那几十条
 *
 * 他 2026-11-07 问「为什么不联网？」—— 内置的那批翻两三天就转回原处，
 * 而语录这种东西，**看见同一句第二遍就不值钱了**。
 *
 * ## 两个源
 *
 * | 语言 | 接口 | 一次给几条 |
 * |---|---|---|
 * | 中文 | `v1.hitokoto.cn`（一言，文学/诗词/哲学三类） | 一条 |
 * | 中文 | `v1.jinrishici.com`（今日诗词，古诗文） | 一条 |
 * | 英文 | `dummyjson.com/quotes` | 二十条 |
 * | 英文 | `stoic-quotes.com`（斯多葛，马可·奥勒留那一辈） | 一条 |
 *
 * 都**不要 key**，2026-11-07 实测从这台电脑与手机都通。
 * ★ 试过的、**不通**的：`api.quotable.io`、`zenquotes.io`（都不回）、
 *   `type.fit/api/quotes`（只回 5 条）、`quote-garden`（503）、`apiopen`（404）。
 * ★ 他 2026-11-07 说「来源要多一点」，所以是**四个源各走各的**：
 *   任何一个挂了，另外三个照样往池子里添。
 *
 * ## 池子
 *
 * 取到的都攒在 `filesDir/room/quotes.json` 里，最多 [MAX_POOL] 条，取过的不会再要。
 * 屏上翻的是**池子里按天轮换**的那几页（见 [QuotesMath.at]），不是每翻一页发一次请求 ——
 * 一块屏每八秒换一句，每次换都联网的话一小时就是四百多次请求。
 *
 * 池子薄了（少于 [LOW_WATER] 条）或者太久没补（超过 [STALE_MS]）才去取。
 * ★ 取不到就用盘上有的；盘上也没有，退回内置那批（[Quotes.LIST]）—— 三种情况都有话说，
 *   不会出现一块空着的框。
 *
 * ## 和「云端总闸」的关系
 *
 * 和热点、天气一样归那道总闸管：关着就一个字节都不发，只用盘上与内置的。
 */
internal object QuoteFeed {

    private const val DIR = "room"
    private const val FILE = "quotes.json"
    private const val TIMEOUT = 8_000

    /** 池子最多攒这么多条。再多也翻不到，只是白占地方。 */
    const val MAX_POOL = 240

    /** 池子里少于这么多条就去补一次。 */
    const val LOW_WATER = 24

    /** 超过这么久没补过，就算池子够也补一次（换换新鲜）。 */
    private const val STALE_MS = 12 * 60 * 60 * 1000L

    /** 一次补几条。中英各来一点，别一边倒。 */
    private const val FETCH_ZH = 3
    private const val FETCH_EN = 20

    // ── 缓存 ────────────────────────────────────────────────────────────

    private fun file(ctx: Context): File? = try {
        val d = File(ctx.filesDir, DIR).apply { mkdirs() }
        File(d, FILE)
    } catch (_: Exception) {
        null
    }

    /** 盘上攒的池子（可能为空）。 */
    fun pool(ctx: Context): List<Quotes.Quote> {
        val f = file(ctx) ?: return emptyList()
        if (!f.isFile) return emptyList()
        return try {
            QuoteMath.fromJson(JSONObject(f.readText()))
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun savedAt(ctx: Context): Long {
        val f = file(ctx) ?: return 0L
        if (!f.isFile) return 0L
        return try {
            JSONObject(f.readText()).optLong("at", 0L)
        } catch (_: Exception) {
            0L
        }
    }

    private fun save(ctx: Context, list: List<Quotes.Quote>) {
        val f = file(ctx) ?: return
        try {
            val o = JSONObject()
                .put("at", System.currentTimeMillis())
                .put("items", QuoteMath.toJson(list))
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(o.toString())
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    /** 该不该去补一次。**不联网**，只看盘。 */
    fun needsFetch(ctx: Context): Boolean {
        val n = pool(ctx).size
        if (n < LOW_WATER) return true
        val at = savedAt(ctx)
        return at <= 0L || System.currentTimeMillis() - at > STALE_MS
    }

    // ── 网取 ────────────────────────────────────────────────────────────

    /**
     * 补一批。**必须在后台线程调**。
     *
     * @return 池子里现在有几条（失败时是原样）。调用方据此决定要不要重画。
     */
    fun fetch(ctx: Context): Int {
        val app = ctx.applicationContext
        val old = pool(app)
        val fresh = ArrayList<Quotes.Quote>()

        // 中文两路：一言（文学/诗词/哲学）+ 今日诗词（古诗文）
        repeat(FETCH_ZH) {
            val q = get(app, ZH_URL)?.let { QuoteMath.zhFromHitokoto(it) }
            if (q != null) fresh.add(q)
        }
        get(app, POEM_URL)?.let { t -> QuoteMath.zhFromPoem(t)?.let { fresh.add(it) } }

        // 英文两路：dummyjson（一次二十条）+ stoic（一次一条）
        get(app, enUrl())?.let { fresh.addAll(QuoteMath.enFromDummy(it)) }
        get(app, STOIC_URL)?.let { t -> QuoteMath.enFromStoic(t)?.let { fresh.add(it) } }

        if (fresh.isEmpty()) {
            trace(app, "语录:一条都没取到，用盘上/内置")
            return old.size
        }
        val merged = QuoteMath.merge(old, fresh, MAX_POOL)
        save(app, merged)
        trace(app, "语录:新增 ${fresh.size} 条，池子 ${merged.size} 条")
        return merged.size
    }

    private const val ZH_URL =
        "https://v1.hitokoto.cn/?c=d&c=i&c=k&encode=json"

    /** 今日诗词：古诗文那一类，一次一句，不要 key。 */
    private const val POEM_URL = "https://v1.jinrishici.com/all.json"

    /** 斯多葛引文：塞涅卡、马可·奥勒留那一辈，一次一句。 */
    private const val STOIC_URL = "https://stoic-quotes.com/api/quote"

    /** 英文那一路换个起点，免得每次都是同一批。 */
    private fun enUrl(): String {
        val skip = (System.currentTimeMillis() / 3_600_000L % 1_300).toInt()
        return "https://dummyjson.com/quotes?limit=$FETCH_EN&skip=$skip"
    }

    private fun get(app: Context, url: String): String? = try {
        val conn = CloudNet.conn(url, app).apply {
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            requestMethod = "GET"
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
            )
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code != 200) {
                trace(app, "语录:HTTP $code（${url.substringBefore('?').takeLast(24)}）")
                null
            } else {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        trace(app, "语录:取不到（${e.javaClass.simpleName}:${e.message}）")
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
 * [QuoteFeed] 的纯算术：两个接口的解析、长度把关、去重、中英交替。能单测。
 */
internal object QuoteMath {

    /** 中文最多这么多个字。再长，一块两三百 dp 的方框里字号要小到看不清。 */
    const val MAX_ZH = 42

    /** 英文最多这么多字符（英文一个词占的宽度和汉字差不多，字数不能照搬）。 */
    const val MAX_EN = 110

    /** 一条太长就不收 —— **收进来才会出问题**，屏上放不下只能缩到看不见。 */
    fun keep(q: Quotes.Quote): Boolean {
        val t = q.text.trim()
        if (t.isEmpty()) return false
        return if (q.lang == "en") t.length <= MAX_EN else t.length <= MAX_ZH
    }

    /** 一言：`{"hitokoto":"…","from":"…","from_who":"…"}`。 */
    fun zhFromHitokoto(text: String): Quotes.Quote? = try {
        val o = JSONObject(text.trim())
        val body = o.optString("hitokoto").trim()
        if (body.isEmpty()) null else {
            // 出处优先用作者；没有作者就用来源（书名/词牌）
            val from = o.optString("from_who").trim().ifEmpty { o.optString("from").trim() }
            Quotes.Quote(body, from.ifEmpty { "一言" }, "zh").takeIf { keep(it) }
        }
    } catch (_: Exception) {
        null
    }

    /** 今日诗词：`{"content":"…","origin":"书篇名","author":"作者"}`。 */
    fun zhFromPoem(text: String): Quotes.Quote? = try {
        val o = JSONObject(text.trim())
        val body = o.optString("content").trim()
        if (body.isEmpty()) null else {
            // 「谢灵运《登庐山绝顶望诸峤》」—— 出处那一行留一个就够了，太长的会被截掉
            val who = o.optString("author").trim()
            val what = o.optString("origin").trim()
            val from = when {
                who.isNotEmpty() && what.isNotEmpty() -> "$who《$what》"
                who.isNotEmpty() -> who
                what.isNotEmpty() -> what
                else -> "今日诗词"
            }
            Quotes.Quote(flatten(body), from, "zh").takeIf { keep(it) }
        }
    } catch (_: Exception) {
        null
    }

    /** stoic-quotes：`{"text":"…","author":"Seneca"}`。 */
    fun enFromStoic(text: String): Quotes.Quote? = try {
        val o = JSONObject(text.trim())
        val body = o.optString("text").trim()
        if (body.isEmpty()) null else {
            Quotes.Quote(
                flatten(body),
                o.optString("author").trim().ifEmpty { "Unknown" },
                "en",
            ).takeIf { keep(it) }
        }
    } catch (_: Exception) {
        null
    }

    /** dummyjson：`{"quotes":[{"quote":"…","author":"…"}]}`。 */
    fun enFromDummy(text: String): List<Quotes.Quote> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        return try {
            val arr: JSONArray = JSONObject(t).optJSONArray("quotes") ?: return emptyList()
            val out = ArrayList<Quotes.Quote>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val body = o.optString("quote").trim()
                if (body.isEmpty()) continue
                val q = Quotes.Quote(flatten(body), o.optString("author").trim().ifEmpty { "Unknown" }, "en")
                if (keep(q)) out.add(q)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 接口里带换行和连续空格的不少，直接塞进 TextView 会多出半行。 */
    fun flatten(s: String): String = s.replace(Regex("[\\s\\u3000]+"), " ").trim()

    /**
     * 合并：老的在前、新的在后，**按正文去重**（取过的不会再要），截到 [max] 条。
     *
     * ★ 去重按正文，不按出处 —— 同一句话从两个源进来，屏上看见的是一模一样的两个字。
     */
    fun merge(old: List<Quotes.Quote>, fresh: List<Quotes.Quote>, max: Int = QuoteFeed.MAX_POOL): List<Quotes.Quote> {
        val seen = HashSet<String>()
        val out = ArrayList<Quotes.Quote>(max)
        for (q in old + fresh) {
            val k = key(q)
            if (!seen.add(k)) continue
            out.add(q)
            if (out.size >= max) break
        }
        return out
    }

    fun key(q: Quotes.Quote): String =
        q.text.replace(Regex("[\\s\\u3000]+"), "").lowercase()

    /** 存盘用。 */
    fun toJson(list: List<Quotes.Quote>): JSONArray {
        val arr = JSONArray()
        for (q in list) {
            arr.put(JSONObject().put("t", q.text).put("f", q.from).put("l", q.lang))
        }
        return arr
    }

    fun fromJson(o: JSONObject?): List<Quotes.Quote> {
        val arr = o?.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<Quotes.Quote>(arr.length())
        for (i in 0 until arr.length()) {
            val q = arr.optJSONObject(i) ?: continue
            val t = q.optString("t")
            if (t.isEmpty()) continue
            out.add(Quotes.Quote(t, q.optString("f"), q.optString("l").ifEmpty { "zh" }))
        }
        return out
    }

    /**
     * 中英交替排一遍。
     *
     * ★ 池子是按「先补中文、后补英文」的顺序攒起来的，直接翻的话会先连着看十几句中文
     *   再连着看十几句英文。交替之后每一页都和上一页换一种语言，才像是「有中英两种」。
     */
    fun interleave(list: List<Quotes.Quote>): List<Quotes.Quote> {
        val zh = list.filter { it.lang != "en" }
        val en = list.filter { it.lang == "en" }
        val out = ArrayList<Quotes.Quote>(list.size)
        var i = 0
        var j = 0
        while (i < zh.size || j < en.size) {
            if (i < zh.size) out.add(zh[i++])
            if (j < en.size) out.add(en[j++])
        }
        return out
    }
}
