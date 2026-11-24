package com.example.touchpad

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 房间左边那块屏的**兴趣画像** —— 「他常看什么」那一本账。
 *
 * ## 说清楚能力边界（别把它当成抖音）
 *
 * 他 2026-11-07 的原话是「分析我的常看内容，大数据分析，跟抖音精准投喂那样」。
 * 抖音那套的底座是**海量内容池 + 云端行为日志 + 重模型**，三样这里一样都没有：
 * 内容池只有三个榜单约一百五十条，行为只有「他点开了哪一条」，
 * 模型只有一本词表。所以这里做的是**真推荐里最小的那个闭环**：
 *
 *   点开一条 → 记下它由哪些词组成 → 以后再遇到含这些词的，往前排。
 *
 * ★ 这条路的诚实说法是「**越用越像你的**」，不是「精准投喂」。别在界面或文档里
 *   把它写成后者 —— 他听得出差别，而夸大了就等于把这块屏的承诺抬到它够不着的地方。
 *
 * ## 为什么画像**不出手机**
 *
 * 他给这条项目的红线之一是「风险信息不上去」。看什么新闻是**比号码更私密**的东西
 * （它泄露的是想法，不是身份）。所以：只落盘、只在本机算，和云端一句话都不沾。
 * 这一条不是性能取舍，别为了「云端推荐更准」把它拆了。
 *
 * ## 两本账
 *
 * · `interest.json` —— 词 → 权重。点一次 +[TAP] 分，然后随时间衰减（半衰期 [HALF_LIFE]）。
 *   不衰减的话，三个月前偶然点过的一条会永远压在他今天想看的东西上面。
 * · 同一份文件里的 `opened` —— 他点开过的条目标题（存**哈希**不存原文：
 *   画像已经泄露兴趣了，没必要再存一份他读过什么的清单）。
 *
 * ## 纯算术在 [InterestMath]
 *
 * 分词、打分、衰减全是**错了不报错**的东西：词切错了只是推荐不准，
 * 衰减算错了只是「它老推荐同一条」。所以判定部分单独拿出来钉住。
 */
internal object InterestStore {

    private const val DIR = "room"
    private const val FILE = "interest.json"

    /** 一个词的权重上限。到顶之后再多点也不涨 —— 否则他会发现自己被困在同一类新闻里。 */
    const val MAX_W = 8f

    private var cache: MutableMap<String, Float>? = null
    private var openedCache: MutableSet<String>? = null
    /** 上次衰减到哪一刻。只存这一件事，不存「他现在对什么感兴趣」。 */
    private var decayedAt = 0L

    private fun file(ctx: Context): File? = try {
        val d = File(ctx.filesDir, DIR).apply { mkdirs() }
        File(d, FILE)
    } catch (_: Exception) {
        null
    }

    fun init(ctx: Context) {
        if (cache != null) return
        load(ctx)
    }

    private fun load(ctx: Context) {
        val f = file(ctx) ?: run { cache = HashMap(); openedCache = HashSet(); return }
        val w = HashMap<String, Float>()
        val o = HashSet<String>()
        try {
            if (f.isFile) {
                val root = JSONObject(f.readText())
                root.optJSONObject("tokens")?.let { t ->
                    for (k in t.keys()) {
                        val v = t.optDouble(k, 0.0)
                        if (v > 0.0001) w[k] = v.toFloat()
                    }
                }
                root.optJSONArray("opened")?.let { a ->
                    for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotEmpty() }?.let { o.add(it) }
                }
                decayedAt = root.optLong("at", 0L)
            }
        } catch (_: Exception) {
            // 读坏了就当没有 —— 一本画像读坏了不值得让房间起不来
        }
        // ★ 读进来先衰减一遍：不然一份两个月没动过的画像，权重还是当初那个数 ——
        //   那他今天点十条新东西，也压不过两个月前的偏好。
        val now = System.currentTimeMillis()
        val aged = if (decayedAt > 0L) InterestMath.decay(w, now - decayedAt) else w
        cache = HashMap(aged)
        openedCache = o
        if (decayedAt > 0L) decayedAt = now
    }

    private fun save(ctx: Context) {
        val f = file(ctx) ?: return
        val w = cache ?: return
        val o = openedCache ?: return
        try {
            val t = JSONObject()
            for ((k, v) in w) if (v > 0.0001f) t.put(k, v.toDouble())
            val a = org.json.JSONArray()
            for (h in o) a.put(h)
            if (decayedAt <= 0L) decayedAt = System.currentTimeMillis()
            val root = JSONObject().put("tokens", t).put("opened", a).put("at", decayedAt)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(root.toString()); tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    /** 现在的画像。返回的是一份快照，调用方改它不影响账。 */
    fun profile(ctx: Context): Map<String, Float> {
        init(ctx)
        return HashMap(cache ?: emptyMap())
    }

    fun openedCount(ctx: Context): Int {
        init(ctx)
        return openedCache?.size ?: 0
    }

    fun hasOpened(ctx: Context, title: String): Boolean {
        init(ctx)
        return openedCache?.contains(InterestMath.key(title)) == true
    }

    /** 他点开了一条。这是画像**唯一**的输入 —— 见文件头那条边界。 */
    fun recordOpen(ctx: Context, title: String) {
        init(ctx)
        val w = cache ?: return
        val o = openedCache ?: return
        for (t in InterestMath.tokens(title)) {
            w[t] = ((w[t] ?: 0f) + InterestMath.TAP).coerceAtMost(MAX_W)
        }
        o.add(InterestMath.key(title))
        if (decayedAt <= 0L) decayedAt = System.currentTimeMillis()
        // 只留最近 300 条：这个小本子不需要无限长，而一个无限长的文件迟早会写坏
        while (o.size > 300) o.remove(o.first())
        save(ctx)
    }

    /** ⚙ 里那个「清空」用。 */
    fun clear(ctx: Context) {
        cache = HashMap()
        openedCache = HashSet()
        decayedAt = System.currentTimeMillis()
        save(ctx)
    }

    /** 有多少个词已经有分量了 —— ⚙ 里那行小字报的就是它。 */
    fun tokenCount(ctx: Context): Int {
        init(ctx)
        return cache?.count { it.value > 0.01f } ?: 0
    }
}

/**
 * [InterestStore] 的纯算术部分。不碰盘、不碰 Context，所以能在 JVM 上单测。
 */
internal object InterestMath {

    /** 点开一次加多少。比榜单名次那一项大，否则「他爱看」压不过「榜单排第一」。 */
    const val TAP = 1.0f

    /** 十二天衰减一半。一周左右还看得出偏好，一个月前的兴趣基本归零。 */
    const val HALF_LIFE_MS = 12L * 24 * 60 * 60 * 1000

    /** 榜单名次在总分里占多少。剩下的是兴趣分 —— 保证新东西永远有出头机会。 */
    const val RANK_WEIGHT = 0.35f

    /**
     * 央媒条目的名次分乘多少。
     *
     * ★ 1.6 是「明显靠前但不霸屏」：央媒某条排在第 8 位，效果约等于别的源排在第 2 位；
     *   央媒一共也就几十条，占池子两三成，乘完之后大约占屏上前列的一半。
     */
    const val OFFICIAL_BOOST = 1.6f

    /**
     * 把一条标题切成「词」。
     *
     * ★ 手机上**没有分词器**，而中文标题不切就一个字都对不上。这里用的是
     *   **字符二元组**（「国产大模型」→ 国产/产大/大模/模型）：切得不准，
     *   但同义的字面能自然对上 —— 「大模型」和「模型开源」共享「模型」。
     *   代价是会出现「的的」这种废词，所以下面有一张停用表。
     *
     * ★ 纯数字的二元组直接丢掉：「2026」「99%」这种在每条新闻里都出现，
     *   留着的话所有标题都会长得像。
     */
    fun tokens(title: String): List<String> {
        val out = LinkedHashSet<String>()
        val clean = title.replace(Regex("[^\\u4e00-\\u9fa5A-Za-z0-9]+"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        for (part in clean.split(' ')) {
            if (part.isEmpty()) continue
            if (part.all { it.isDigit() }) continue
            if (part.length == 1) {
                if (isCjk(part[0]) && part[0] !in STOP) out.add(part)
                continue
            }
            if (part.all { isCjk(it) }) {
                for (i in 0 until part.length - 1) {
                    val bg = part.substring(i, i + 2)
                    if (bg.any { it in STOP }) continue
                    out.add(bg)
                }
            } else {
                // 英文 / 混排：整个词当一个记号，小写
                val w = part.lowercase()
                if (w.length in 2..20) out.add(w)
            }
        }
        return out.toList()
    }

    private fun isCjk(c: Char): Boolean = c in '一'..'龥'

    /** 高频虚字。含这几个字的二元组基本不携带意思，留着只会让所有标题互相像。 */
    private val STOP = setOf(
        '的', '了', '是', '在', '和', '与', '也', '都', '就', '而', '及', '或',
        '我', '你', '他', '她', '它', '们', '这', '那', '个', '有', '被', '把',
        '为', '以', '对', '从', '到', '等', '着', '过', '吗', '呢', '啊', '吗',
        '：', '，', '。', '、', '！', '？', '（', '）', '《', '》', '“', '”'
    )

    /**
     * 一条标题的兴趣分：它含的每个词的权重加起来，再**除以词数**。
     *
     * ★ 不除词数的话，长标题天然赢 —— 它词多，蹭到的权重也多。
     *   那样榜单会退化成「只推长句子」，而那是错得很安静的那种错。
     */
    fun score(title: String, profile: Map<String, Float>): Float {
        if (profile.isEmpty()) return 0f
        val ts = tokens(title)
        if (ts.isEmpty()) return 0f
        var sum = 0f
        for (t in ts) sum += profile[t] ?: 0f
        return sum / ts.size
    }

    /**
     * 合并排序：兴趣分 + 榜单名次分。
     *
     * [rank] 是榜单上的名次（1 起）。名次分按 `1/(rank+3)` 归一，头部陡、尾部平，
     * 免得第五十条和第一条差出一百倍。
     *
     * [opened] 里有的**直接压到最后** —— 他点开过的那条已经看过了，
     * 再占首页位置就是在浪费他那一屏。
     */
    fun rank(
        items: List<NewsItem>,
        profile: Map<String, Float>,
        opened: Set<String> = emptySet(),
        limit: Int = 30,
    ): List<NewsItem> {
        val scored = items.map { n ->
            val i = score(n.title, profile)
            // ★ 央媒加成：他说「新华网、人民日报那种肯定得多点」。
            //   乘在名次分上而不是加一个常数 —— 常数会把央媒的末条也抬到头条前面，
            //   乘系数只让它们整体靠前，条内先后还是按名次。
            val r = 1f / (n.rank.coerceAtLeast(1) + 3) * (if (n.official) OFFICIAL_BOOST else 1f)
            val seen = if (InterestMath.key(n.title) in opened) -100f else 0f
            Triple(n, i + RANK_WEIGHT * r + seen, i)
        }
        return scored.sortedWith(
            compareByDescending<Triple<NewsItem, Float, Float>> { it.second }
                .thenByDescending { it.third }
                .thenBy { it.first.rank }
        ).take(limit).map { it.first }
    }

    /**
     * 按时间衰减一遍。半衰期 [HALF_LIFE_MS]。
     *
     * @param sinceMs 距离上次整理过去了多久。≤0 时原样返回（**别让时钟往回拨把账清零**）。
     */
    fun decay(profile: Map<String, Float>, sinceMs: Long, halfLifeMs: Long = HALF_LIFE_MS): Map<String, Float> {
        if (sinceMs <= 0L || halfLifeMs <= 0L) return profile
        val factor = Math.pow(0.5, sinceMs.toDouble() / halfLifeMs).toFloat()
        if (factor >= 0.999f) return profile
        val out = HashMap<String, Float>(profile.size)
        for ((k, v) in profile) {
            val nv = v * factor
            if (nv > 0.0001f) out[k] = nv
        }
        return out
    }

    /**
     * 标题的**哈希**。画像里存的是它，不是原文 ——
     * 「他读过哪几条」本身就是敏感信息，能少存一份就少存一份。
     */
    fun key(title: String): String {
        val s = title.trim()
        var h = 1125899906842597L          // 和 Java String.hashCode 不同的起点，免得撞
        for (c in s) h = 31 * h + c.code
        return java.lang.Long.toHexString(h)
    }
}
