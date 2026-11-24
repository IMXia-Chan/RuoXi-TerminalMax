package com.example.touchpad

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar
import java.util.Locale

/**
 * 房间这一屏**左一块、右一列**信息屏（他 2026-11-07 定的：房间太单调）。
 *
 * ```
 * ┌────────┐              ┌────────┐
 * │ 今日热点 │              │  天气   │
 * │  一条   │     （她）    ├────────┤
 * │  一页   │              │ 日程/语录│
 * └────────┘              └────────┘
 * ```
 *
 * 左边一块热点，右边一列上下两块。右边原来是**一整块「今日」**，他看过真机之后说
 * 「右边框确实没有东西可显示」—— 因为定时任务通常一条都没有。于是改成：
 * **上面天气**（每天都有数），**下面日程**（有就翻日程，没有就翻文学语录）。
 *
 * ## 一页一条
 *
 * 两块屏里装的是 [FlipPager]：一页一条，**自动翻**，也能手划。
 *
 * ## 左边的手势（他 2026-11-07 定）
 *
 * · **上下划 = 滚动**，看这一条的全文（标题长的时候一屏放不下）；
 * · **左右划 = 换下一条**；
 * · **点一下 = 打开那家媒体的网页**（走 `Intent.ACTION_VIEW` 交给系统浏览器，
 *   不在 App 里内嵌 WebView —— 各家新闻站对 WebView 的拦法五花八门，内嵌的结果是白屏）。
 *
 * ## 每小时整批换新
 *
 * 池子按兴趣排好序之后**切成每批 [RoomPanelMath.PAGES] 条**，每小时往后走一批：
 * 这一小时看的是排名 1–12，下一小时是 13–24。走到底就从头来，同时顺手再取一轮新的。
 * 只看前 12 条的话，他每看一遍都是同一批。
 *
 * ## 尺寸
 *
 * 方块，边长按**屏幕比例**算（见 [side]），不是写死的 dp：
 * 这台机横屏时写死的 176dp 会吃掉将近一半屏高。
 */
internal class RoomPanels(
    private val ctx: Context,
    /** 点了一条热点。跳转由调用方做（这一层不碰 Intent）。 */
    private val onOpenNews: (NewsItem) -> Unit,
) : LinearLayout(ctx) {

    private val main = Handler(Looper.getMainLooper())

    /**
     * 左边那块正方形的边长（dp）。
     *
     * ★ 三个上限取最小的：写死的 176、屏宽的 44%、屏高的 32%。
     *   竖屏 360×791 得 158；横屏 791×360 得 115 —— 横屏不压下去就是一整块横幅。
     */
    val side: Int = run {
        val dm = resources.displayMetrics
        val w = dm.widthPixels / dm.density
        val h = dm.heightPixels / dm.density
        // ★ 2026-11-07 屏高那一项从 0.32 提到 0.40：横屏 115dp 见方时，
        //   标题和留白占掉一半，正文只剩两行多，他说「下面位置太小」。
        minOf(176, (w * 0.44f).toInt(), (h * 0.40f).toInt())
    }

    /**
     * 右边那一列的总高（dp）。
     *
     * ★ 2026-11-07 从 1.30/0.42 提到 1.45/0.46：横屏时一张卡只有七十来 dp，
     *   标题加留白占满，正文一个字都露不出来（真机上语录卡只剩一行出处）。
     */
    val columnH: Int = run {
        val dm = resources.displayMetrics
        val h = dm.heightPixels / dm.density
        minOf((side * 1.45f).toInt(), (h * 0.54f).toInt())
    }

    private lateinit var newsMeta: TextView
    private lateinit var newsPager: FlipPager
    private lateinit var newsDots: LinearLayout

    private lateinit var wxHead: TextView
    private lateinit var wxBig: TextView
    private lateinit var wxDesc: TextView
    private lateinit var wxFoot: TextView

    private lateinit var lowHead: TextView
    private lateinit var lowPager: FlipPager
    private lateinit var lowDots: LinearLayout

    /** 现在屏幕上这批热点（已排序、已取当前批次）。 */
    private var pool: List<NewsItem> = emptyList()

    private var stopped = true

    init {
        orientation = HORIZONTAL
        gravity = Gravity.TOP

        // ── 左：今日热点 ─────────────────────────────────────────────
        val newsHead = header("今日热点", 11.5f)
        newsMeta = meta(8.5f)
        newsPager = FlipPager(
            ctx = ctx,
            autoMs = 6_000L,
            onTap = { openNews() },
            onPage = { i -> if (::newsDots.isInitialized) paintDots(newsDots, i, newsPager.count) },
            // 左边热点**要滚**：标题长的时候一屏放不下，上下划看全文
            scrollable = true,
        )
        newsDots = LinearLayout(ctx)
        val newsCard = card(newsHead, newsMeta, newsPager, newsDots, 10, 9)

        // ── 右上：天气 ───────────────────────────────────────────────
        wxHead = meta(9f)
        wxBig = TextView(ctx).apply {
            // 和卡片标题一样按 dp 定：固定尺寸的卡里，字号放大 1.6 倍只会把语录挤没
            setTextSize(TypedValue.COMPLEX_UNIT_PX, dpF(20f))
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }
        wxDesc = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, dpF(10.5f))
            setTextColor(0xD9FFFFFF.toInt())
        }
        wxFoot = meta(8.5f)
        val wxCard = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setBackgroundResource(R.drawable.room_screen)
            setPadding(dp(9), dp(7), dp(9), dp(6))
        }
        wxCard.addView(wxHead, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        wxCard.addView(LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(wxBig, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(View(ctx), LinearLayout.LayoutParams(dp(6), 1))
            addView(wxDesc, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(2)
            })
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(1) })
        wxCard.addView(wxFoot, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(3)
        })

        // ── 右下：日程 / 语录 ────────────────────────────────────────
        lowHead = header("今日", 9.5f)
        // ★ 这一块点了就翻下一页：它没有「点进去能去哪儿」的地方，
        //   而一个能翻的屏点了有反应，比点了什么都不发生强。
        lowPager = FlipPager(
            ctx = ctx,
            autoMs = 9_000L,
            onTap = { lowPager.next() },
            onPage = { i -> if (::lowDots.isInitialized) paintDots(lowDots, i, lowPager.count, 8) },
            // ★ 语录/日程**不滚**：要的是「整个放在框里」，字大了就缩，不给他上下划
            scrollable = false,
        )
        lowDots = LinearLayout(ctx)
        // 上下留 5dp 就够：这块卡本来就窄，留白全从正文身上扣
        val lowCard = card(lowHead, null, lowPager, lowDots, 9, 5)

        // ── 拼起来 ──────────────────────────────────────────────────
        addView(newsCard, LinearLayout.LayoutParams(dp(side), dp(side)))
        addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        // ★ 天气卡**按内容定高**，剩下的全给语录：
        //   原来两块一人一半，天气只写三行却占掉一半高度，语录那块反而放不下一句完整的话。
        //   他说「语录也太小了」，位置得从天气那边挪过来。
        col.addView(wxCard, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        col.addView(View(ctx), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(8)))
        col.addView(lowCard, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(col, LinearLayout.LayoutParams(dp(side), dp(columnH)))
    }

    // ── 开关（⚙ 里那一行读的就是它）──────────────────────────────────────

    companion object {
        private const val PREF = "conmarn_room_panels"
        private const val KEY_ON = "on"
        private const val KEY_BATCH = "batch"
        private const val KEY_BATCH_AT = "batchAt"

        /** 多久走一批、并顺手取一轮新的。他 2026-11-07 要的是「每 1 小时全部换一遍」。 */
        private const val REFRESH_MS = 60 * 60 * 1000L

        /** 天气刷得快一些：稳定状态下只发一条请求（城市和坐标是缓存的）。 */
        private const val WEATHER_MS = 30 * 60 * 1000L

        /** 日程那一块刷得快：它只读本地文件，几乎不花钱。 */
        private const val TODAY_MS = 60 * 1000L

        fun isOn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ON, true)

        fun setOn(ctx: Context, on: Boolean) =
            prefs(ctx).edit().putBoolean(KEY_ON, on).apply()

        private fun prefs(ctx: Context) =
            ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    // ── 生命周期 ────────────────────────────────────────────────────────

    /** 上屏之后叫一次：先把盘上有的东西画出来（不让屏空着），再去取新的。 */
    fun start() {
        stopped = false
        pool = NewsFeed.cached(ctx)
        advanceBatchIfDue(System.currentTimeMillis())
        repaintNews(null)
        repaintWeather()
        repaintLow()
        refreshNews()
        refreshWeather()
        refreshQuotes()
        newsPager.startAuto()
        lowPager.startAuto()
        main.postDelayed(newsTick, REFRESH_MS)
        main.postDelayed(netTick, WEATHER_MS)
        main.postDelayed(lowTick, TODAY_MS)
    }

    /** 离开房间时叫一次，把定时器和翻页动画都摘掉。 */
    fun stop() {
        stopped = true
        main.removeCallbacks(newsTick)
        main.removeCallbacks(netTick)
        main.removeCallbacks(lowTick)
        newsPager.stopAuto()
        lowPager.stopAuto()
    }

    /** 他刚点开一条之后叫一次：**只重排**，不再去取一次网。 */
    fun afterOpen() {
        if (stopped) return
        repaintNews(null)
    }

    /** 从后台回来时叫一次：立刻对一次表。 */
    fun poke() {
        if (stopped) return
        advanceBatchIfDue(System.currentTimeMillis())
        repaintNews(null)
        repaintLow()
        repaintWeather()
        refreshNews()
        refreshWeather()
        refreshQuotes()
        newsPager.startAuto()
        lowPager.startAuto()
    }

    private val newsTick = object : Runnable {
        override fun run() {
            if (stopped) return
            // 一小时到了：整批往后挪一页，同时去取一轮新的
            batch += 1
            batchAt = System.currentTimeMillis()
            prefs(ctx).edit().putInt(KEY_BATCH, batch).putLong(KEY_BATCH_AT, batchAt).apply()
            repaintNews(null)
            refreshNews()
            main.postDelayed(this, REFRESH_MS)
        }
    }

    private val netTick = object : Runnable {
        override fun run() {
            if (stopped) return
            refreshWeather()
            refreshQuotes()
            main.postDelayed(this, WEATHER_MS)
        }
    }

    private val lowTick = object : Runnable {
        override fun run() {
            if (stopped) return
            repaintLow()
            main.postDelayed(this, TODAY_MS)
        }
    }

    // ── 批次：第几个小时看第几批 ─────────────────────────────────────────

    private var batch: Int = 0
    private var batchAt: Long = 0L

    /**
     * 进房间时补上「离开期间已经过掉的整点」。
     *
     * ★ 不补的话，早上起来打开房间看到的还是昨晚那批 —— 而屏上写着「今天」。
     */
    private fun advanceBatchIfDue(now: Long) {
        val sp = prefs(ctx)
        batch = sp.getInt(KEY_BATCH, 0)
        val last = sp.getLong(KEY_BATCH_AT, 0L)
        batchAt = now
        if (last <= 0L) {
            sp.edit().putLong(KEY_BATCH_AT, now).apply()
            return
        }
        val steps = ((now - last) / REFRESH_MS).toInt()
        if (steps > 0) {
            batch += steps
            sp.edit().putInt(KEY_BATCH, batch).putLong(KEY_BATCH_AT, now).apply()
        }
    }

    // ── 左：今日热点 ────────────────────────────────────────────────────

    /**
     * 出去取一次。
     *
     * ★ **云端总闸关着就不发**（见记忆 `ruoxi-cloud-fallback`：那是总闸，
     *   关掉之后一个字节都不出去）。屏上把这件事写出来，只用缓存。
     */
    private fun refreshNews() {
        if (stopped) return
        if (!AiAgent.loadConfig(ctx).cloudOn) return
        Thread({
            val got = NewsFeed.fetchPool(ctx)
            main.post {
                if (stopped) return@post
                if (got != null) pool = got
                repaintNews(if (got == null) "没取到" else null)
            }
        }, "room-news").apply { isDaemon = true }.start()
    }

    /** 按现在的画像重排一遍、取当前批次，并重新上屏。 */
    private fun repaintNews(note: String?) {
        if (!::newsPager.isInitialized) return
        val ranked = if (pool.isEmpty()) emptyList() else InterestMath.rank(
            pool,
            InterestStore.profile(ctx),
            openedSet(),
            pool.size
        )
        val shown = RoomPanelMath.chunk(ranked, batch, RoomPanelMath.PAGES)

        // ★ 一行里问一次就够了：每问一次 `loadConfig` 就是一次盘上读
        val cloud = AiAgent.loadConfig(ctx).cloudOn
        val at = NewsFeed.cachedAt(ctx)
        newsMeta.text = listOfNotNull(
            if (at <= 0L) null else "${NewsFeed.stamp(at)} 更新",
            note,
            if (!cloud) "云端总闸关着" else null,
        ).joinToString(" · ")

        if (shown.isEmpty()) {
            newsPager.setPages(listOf(hint(if (!cloud) "云端关着" else "正在取…")))
            paintDots(newsDots, 0, 0)
            return
        }
        newsPager.setPages(shown.map { newsPage(it) })
        paintDots(newsDots, newsPager.index, shown.size)
        newsPager.startAuto()
    }

    private fun openedSet(): Set<String> {
        if (InterestStore.openedCount(ctx) == 0) return emptySet()
        val s = HashSet<String>()
        for (n in pool) if (InterestStore.hasOpened(ctx, n.title)) s.add(InterestMath.key(n.title))
        return s
    }

    private fun openNews() {
        val p = newsPager.index
        val ranked = if (pool.isEmpty()) emptyList() else InterestMath.rank(
            pool, InterestStore.profile(ctx), openedSet(), pool.size
        )
        val n = RoomPanelMath.chunk(ranked, batch, RoomPanelMath.PAGES).getOrNull(p) ?: return
        if (n.link.isBlank()) return
        onOpenNews(n)
    }

    /** 一条热点。**标题给全文** —— 滚一下就能看全，不再截成「台风登…」。 */
    private fun newsPage(n: NewsItem): View {
        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        col.addView(body(n.title).apply { textSize = 11.5f },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val foot = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        foot.addView(TextView(ctx).apply {
            text = NewsFeed.label(n.board)
            textSize = 8.5f
            setTextColor(0x66FFFFFF)
        })
        val hot = NewsMath.hotText(n.hot)
        if (hot.isNotEmpty()) {
            foot.addView(TextView(ctx).apply {
                text = " · $hot"
                textSize = 8.5f
                setTextColor(0x8AE05B8E.toInt())
            })
        }
        col.addView(foot, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return col
    }

    // ── 右上：天气 ──────────────────────────────────────────────────────

    private fun refreshWeather() {
        if (stopped) return
        if (!AiAgent.loadConfig(ctx).cloudOn) return
        Thread({
            val got = Weather.fetch(ctx)
            main.post {
                if (stopped) return@post
                if (got != null) repaintWeather()
                else if (Weather.cached(ctx) == null) {
                    wxHead.text = "天气"
                    wxBig.text = "—"
                    wxDesc.text = ""
                    wxFoot.text = if (!AiAgent.loadConfig(ctx).cloudOn) "云端总闸关着" else "没取到"
                }
            }
        }, "room-weather").apply { isDaemon = true }.start()
    }

    private fun repaintWeather() {
        if (!::wxBig.isInitialized) return
        val w = Weather.cached(ctx)
        if (w == null) {
            wxHead.text = "天气"
            wxBig.text = "—"
            wxDesc.text = ""
            wxFoot.text = if (!AiAgent.loadConfig(ctx).cloudOn) "云端总闸关着" else "正在取…"
            return
        }
        wxHead.text = "天气 · " + w.city
        wxBig.text = WeatherMath.tempText(w.temp)
        wxDesc.text = WeatherMath.desc(w.code)
        wxFoot.text = listOfNotNull(
            WeatherMath.rangeText(w.high, w.low).ifBlank { null },
            WeatherMath.rainText(w.rain).ifBlank { null },
        ).joinToString(" · ")
    }

    // ── 右下：日程 / 语录 ───────────────────────────────────────────────

    private fun repaintLow() {
        if (!::lowPager.isInitialized) return
        val now = System.currentTimeMillis()
        val plans = RoomPanelMath.todayPlans(TaskStore.all(), now, RoomPanelMath.endOfDay(now))

        if (plans.isNotEmpty()) {
            lowHead.text = if (plans.size > 1) "日程 ${plans.size} 条" else "日程"
            lowPager.setPages(plans.map { taskPage(it) })
            paintDots(lowDots, lowPager.index, plans.size, 8)
            lowPager.startAuto()
            return
        }
        // 今天一条都没有 → 换成文学语录（见 [Quotes] / [QuoteFeed]）
        lowHead.text = "语录"
        val day = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_YEAR)
        // ★ 优先挑**放得下**的那些：这块卡一行约二十来个字母、总共三四行，
        //   [QuoteMath.MAX_EN] 允许的一百多字符在这里必然被裁掉半句。
        //   池子照旧收长的，只是上屏时先挑短的。
        val quotes = RoomPanelMath.shortEnough(QuoteFeed.pool(ctx).ifEmpty { Quotes.LIST })
        val n = quotes.size
        val pages = (0 until minOf(RoomPanelMath.QUOTE_PAGES, n)).map { k ->
            val q = quotes[QuotesMath.at(day, k, n)]
            quotePage(q)
        }
        lowPager.setPages(pages)
        paintDots(lowDots, lowPager.index, pages.size, 8)
        lowPager.startAuto()
    }

    /**
     * 去补一批语录（联网）。
     *
     * ★ 池子够厚、又不算旧的时候**一条都不发** —— 见 [QuoteFeed.needsFetch]。
     *   否则每半小时就白打三次请求，而屏上换的还是那几句。
     */
    private fun refreshQuotes() {
        if (stopped) return
        if (!AiAgent.loadConfig(ctx).cloudOn) return
        // 今天有日程时语录根本不上屏，这会儿去取没有意义
        val now = System.currentTimeMillis()
        if (RoomPanelMath.todayPlans(TaskStore.all(), now, RoomPanelMath.endOfDay(now)).isNotEmpty()) return
        if (!QuoteFeed.needsFetch(ctx) && QuoteFeed.pool(ctx).isNotEmpty()) return
        Thread({
            QuoteFeed.fetch(ctx)
            main.post {
                if (stopped) return@post
                repaintLow()
            }
        }, "room-quotes").apply { isDaemon = true }.start()
    }

    private fun taskPage(p: TaskPlan): View {
        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        col.addView(TextView(ctx).apply {
            text = RoomPanelMath.hhmm(p.minuteOfDay)
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.MONOSPACE
        }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        col.addView(body(RoomPanelMath.preview(p.prompt, 40)).apply {
            setPadding(0, dp(4), 0, 0)
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return col
    }

    /**
     * 一页语录。
     *
     * ★ 他 2026-11-07 的原话：「字要小，要整个放在框里」。
     *   所以正文**自动缩字号**（11sp 放得下就用 11sp，放不下一直缩到 7sp），
     *   而这一页**不滚**（[lowPager] 的 `scrollable = false`）——
     *   能滚就说明它本来就没放进去，那不叫「放在框里」。
     */
    private fun quotePage(q: Quotes.Quote): View {
        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        val t = TextView(ctx).apply {
            text = q.text
            setTextColor(0xF2F2F3F5.toInt())
            setLineSpacing(0f, 1.15f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                // ★ 上限 13sp：他说「语录也太小了」。位置已经从天气那边挪过来了
                //   （见 init 里天气卡改成 wrap_content），装得下就按 13sp 画。
                setAutoSizeTextTypeUniformWithConfiguration(
                    8, 13, 1, TypedValue.COMPLEX_UNIT_SP
                )
            } else {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }
        }
        // 高度是「剩下的那些」：出处那一行先占住，正文不再无限长
        col.addView(t, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(TextView(ctx).apply {
            this.text = if (q.lang == "en") "— ${q.from}" else "—— ${q.from}"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, dpF(8f))
            setTextColor(0x66FFFFFF)
            gravity = Gravity.END
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        return col
    }

    // ── 零件 ────────────────────────────────────────────────────────────

    /**
     * 一块玻璃卡：标题那一行 + 一条细线 + 里面一叠页 + 底部位置点。
     *
     * [meta] 为 null 就不画右半边（右下那块放不下）。
     */
    private fun card(
        head: TextView,
        meta: TextView?,
        pager: View,
        dots: LinearLayout,
        padH: Int,
        padV: Int,
    ): LinearLayout {
        val c = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setBackgroundResource(R.drawable.room_screen)
            setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
        }
        // ★ 标题和补充信息**上下两行**：一块屏只有一两百 dp 宽，
        //   「今日热点」加粗之后已经占掉一半，再并上「08:42 更新」标题就只剩一两个字。
        c.addView(head, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        if (meta != null) {
            c.addView(meta, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(1)
            })
        }
        c.addView(View(ctx).apply { setBackgroundColor(0x26FFFFFF) },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(6) })
        c.addView(pager, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(6)
        })
        dots.orientation = HORIZONTAL
        dots.gravity = Gravity.CENTER
        c.addView(dots, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(9)))
        return c
    }

    /** 底部那排小点：一眼看出「还有几条」。只有一条时不画（一个点没有意义）。 */
    private fun paintDots(row: LinearLayout, index: Int, total: Int, max: Int = RoomPanelMath.PAGES) {
        row.removeAllViews()
        if (total <= 1) return
        val n = total.coerceAtMost(max)
        // 超过上限时按比例落点，别让最后一个点永远不亮
        val at = if (total > max) Math.round(index * (n - 1).toFloat() / (total - 1)) else index
        for (i in 0 until n) {
            val on = i == at
            row.addView(View(ctx).apply {
                setBackgroundColor(if (on) 0xF2FFFFFF.toInt() else 0x3DFFFFFF)
            }, LinearLayout.LayoutParams(dp(if (on) 4 else 3), dp(if (on) 4 else 3)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(2)
                marginEnd = dp(2)
            })
        }
    }

    /**
     * 卡片的标题行。
     *
     * ★ 这里**故意按 dp 定字号，不用 sp**：这台机的系统字号是 1.6 倍，
     *   而这块卡片是按屏幕比例算出来的固定尺寸（横屏一张只有七十多 dp 高）。
     *   用 sp 的话标题一行就吃掉半张卡，正文被挤到十几 dp、整句看不见 ——
     *   2026-11-07 真机上就是这样：语录卡只剩一行出处，正文是空的。
     *   固定尺寸的屏里，装饰性文字跟着屏幕走，正文才跟着他的字号走。
     */
    private fun header(t: String, sp: Float): TextView = TextView(ctx).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_PX, dpF(sp))
        setTextColor(0xFFFFFFFF.toInt())
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.06f
    }

    private fun meta(sp: Float): TextView = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, dpF(sp))
        setTextColor(0x7AFFFFFF)
    }

    private fun body(t: String): TextView = TextView(ctx).apply {
        text = t
        textSize = 10.5f
        setTextColor(0xF2F2F3F5.toInt())
        setLineSpacing(0f, 1.25f)
    }

    private fun hint(t: String): TextView = TextView(ctx).apply {
        text = t
        textSize = 9.5f
        setTextColor(0x8AFFFFFF.toInt())
        gravity = Gravity.CENTER
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    /** 同上，但要小数（字号）。 */
    private fun dpF(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    )
}

/**
 * 一块屏里的一叠「页」：**一页一条，自动翻，也能手划**。
 *
 * ## 方向（他 2026-11-07 定）
 *
 * · **上下划 = 滚动这一页**（交给里面的 [ScrollView]，标题长的时候看全文）；
 * · **左右划 = 换页**；
 * · **点一下 = 这一页的 `onTap`**。
 *
 * 所以这一层只在手指**横向**走得比竖向远、并且超过触摸阈值时，才把手势抢过来；
 * 其余时候一概不管，竖向留给 ScrollView。
 *
 * ## 为什么自己做，不用 ViewPager2
 *
 * ViewPager2 会把 `androidx.recyclerview` 整个拖进来（这个 APK 一直在压体积），
 * 而这里要的只有三样：换一页、动一下、被点一下。
 */
internal class FlipPager(
    private val ctx: Context,
    /** 自动翻页的间隔。0 = 不自动翻（只有一页的时候就是这种）。 */
    private val autoMs: Long,
    private val onTap: () -> Unit,
    /** 换页之后叫一声（画位置点用）。参数是新的页号。 */
    private val onPage: (Int) -> Unit = {},
    /**
     * 一页能不能上下滚。
     *
     * 左边热点要滚（标题长的时候一屏放不下）；右下语录**不滚** ——
     * 他要的是「字要小，要整个放在框里」，滚起来就说明它本来就没放进去。
     */
    private val scrollable: Boolean = true,
) : FrameLayout(ctx) {

    private val main = Handler(Looper.getMainLooper())

    /** 每一页都已经包好 [ScrollView]。 */
    private var pages: List<View> = emptyList()
    private var shown: View? = null

    /** 现在停在第几页（从 0 起）。外部拿它做位置点和「点开的是哪条」。 */
    var index = 0
        private set

    val count: Int get() = pages.size

    private var autoOn = false

    private val slop: Int by lazy { ViewConfiguration.get(ctx).scaledTouchSlop }

    private var downX = 0f
    private var downY = 0f

    /**
     * 手指现在有没有按在上面。
     *
     * ★ 2026-11-07 他说「手划后它还是需要自动翻页的，不能手滑后就不翻页了」。
     *   以前是手一碰就 `removeCallbacks` 把定时器摘掉、指望 `ACTION_UP` 再装回去 ——
     *   只要有一次 UP 没送到（手势被别处抢走、页面正被重建），自动翻页就永远停了。
     *   现在改成**定时器一直在跑**，手按着只是「这一拍先不翻」。
     */
    private var touching = false

    /** 最后一次收到触摸事件的时刻（uptime）。手指卡住不发 UP 时靠它兜底。 */
    private var touchAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!autoOn) return
            // 手还按着就等下一拍。超过两秒半没动静，当成他已经松手 ——
            // 事件丢了也要能自己爬起来。
            if (touching && SystemClock.uptimeMillis() - touchAt < TOUCH_IDLE_MS) {
                main.postDelayed(this, 400L)
                return
            }
            touching = false
            if (pages.size < 2) {
                // 页还没到（数据是异步来的）就再等一拍，别把自己停了
                main.postDelayed(this, autoMs)
                return
            }
            go(index + 1, forward = true)
            main.postDelayed(this, autoMs)
        }
    }

    init {
        clipChildren = true
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                // 手一碰就先**暂停这一拍**（不是把定时器摘掉）——
                // 他正看着的时候页不该自己溜走。见 [tick] 的注释。
                touching = true
                touchAt = SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_MOVE -> {
                touchAt = SystemClock.uptimeMillis()
                val dx = ev.x - downX
                val dy = ev.y - downY
                // 只抢**横向**的。竖向（滚动）连同横向没走够的一律放掉。
                if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 没抢 → 事件还在子 View 那边。放手就从这一刻重新计时
                touching = false
                startAuto()
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> touchAt = SystemClock.uptimeMillis()
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                if (Math.abs(dx) > dp(36)) {
                    if (dx < 0) go(index + 1, forward = true) else go(index - 1, forward = false)
                }
                touching = false
                startAuto()
            }
            MotionEvent.ACTION_CANCEL -> {
                touching = false
                startAuto()
            }
        }
        return true
    }

    fun setPages(list: List<View>) {
        pages = list.map { wrap(it) }
        index = index.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        removeAllViews()
        val v = pages.getOrNull(index)
        shown = v
        if (v != null) addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // ★ 页是异步来的：startAuto() 早就在页还没到的时候叫过了，那时 pages 是空的。
        //   这里必须**再排一次**，否则自动翻页永远不开始 —— 2026-11-07 他报的
        //   「今日热点好像还不会自动翻页」就是这个（语录那边每次重画都会叫 startAuto，所以没露馅）。
        if (autoOn && pages.size >= 2) {
            main.removeCallbacks(tick)
            main.postDelayed(tick, autoMs)
        }
        onPage(index)
    }

    fun startAuto() {
        // ★ 想不想翻和「现在有几页」分开：页没到时也要记住这个意愿
        autoOn = autoMs > 0
        main.removeCallbacks(tick)
        if (autoOn && pages.size >= 2) main.postDelayed(tick, autoMs)
    }

    fun stopAuto() {
        autoOn = false
        main.removeCallbacks(tick)
    }

    /** 手点翻页用（右下那块点了就翻）。 */
    fun next() = go(index + 1, forward = true)

    /**
     * 换到第 [to] 页。[forward] 为真 = 新页从右边推进来（「往后看」的方向）。
     */
    private fun go(to: Int, forward: Boolean) {
        if (pages.isEmpty()) return
        val n = pages.size
        val target = ((to % n) + n) % n
        if (target == index) return
        val old = shown
        val fresh = pages[target]
        index = target
        shown = fresh
        removeAllViews()
        addView(fresh, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        fresh.translationX = if (forward) dp(20).toFloat() else -dp(20).toFloat()
        fresh.alpha = 0f
        fresh.animate().translationX(0f).alpha(1f).setDuration(180).start()
        if (old != null) {
            old.animate().translationX(if (forward) -dp(20).toFloat() else dp(20).toFloat())
                .alpha(0f).setDuration(180).start()
        }
        onPage(index)
    }

    /**
     * 把一页包好：竖向滚动归 [ScrollView]，横向手势归这一层。
     * 点一下由里层那页自己接（`isClickable`），ScrollView 会把没滚动的点击原样传下去。
     */
    private fun wrap(v: View): View {
        v.isClickable = true
        v.setOnClickListener { onTap() }
        if (!scrollable) return v
        val sv = ScrollView(ctx)
        sv.isFillViewport = true
        sv.isVerticalScrollBarEnabled = false
        sv.addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return sv
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    private companion object {
        /** 多久没收到触摸事件就当成手指已经离开（UP 丢了也不至于把自动翻页卡死）。 */
        const val TOUCH_IDLE_MS = 2_500L
    }
}

/**
 * [RoomPanels] 的纯算术部分。**不碰视图、不碰盘**，所以能在 JVM 上单测。
 */
internal object RoomPanelMath {

    /** 一批几条。再多的他也不会翻到 —— 而且每多一页就多一次渲染。 */
    const val PAGES = 12

    /** 语录那一块一次装几页。翻完一轮就回到开头，不是无穷无尽。 */
    const val QUOTE_PAGES = 6

    /**
     * 右下那块卡一行大约放得下多少个字符。
     *
     * ★ 这块卡只有一百多 dp 宽、一百多 dp 高，8sp 的字一行二十个字母上下。
     *   超过这几个数就只能裁掉半句 —— 而「裁掉半句的语录」比没有更难看。
     */
    const val FIT_EN = 70
    const val FIT_ZH = 30

    /** 这条在右下那块卡上放得下吗。 */
    fun fitsCard(q: Quotes.Quote): Boolean =
        if (q.lang == "en") q.text.length <= FIT_EN else q.text.length <= FIT_ZH

    /** 先挑短的；一条短的都没有（池子里全是长句）就原样返回，别给一块空屏。 */
    fun shortEnough(all: List<Quotes.Quote>): List<Quotes.Quote> {
        val short = all.filter { fitsCard(it) }
        return short.ifEmpty { all }
    }

    const val WEEK = "一二三四五六日"

    /** 第几分钟 → `08:30`。越界的一律夹回去，别让它画出 `25:7` 这种。 */
    fun hhmm(minuteOfDay: Int): String {
        val m = minuteOfDay.coerceIn(0, 1439)
        return String.format(Locale.CHINA, "%02d:%02d", m / 60, m % 60)
    }

    /** 「11月7日 周六」。 */
    fun dayLabel(nowMs: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = nowMs }
        val w = c.get(Calendar.DAY_OF_WEEK)          // Calendar: 1=周日 … 7=周六
        val name = WEEK[(w + 5) % 7]
        return String.format(
            Locale.CHINA, "%d月%d日 周%s",
            c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), name
        )
    }

    /** 今天最后一毫秒。 */
    fun endOfDay(nowMs: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        return c.timeInMillis
    }

    /**
     * 今天**还要**响的那几条，按时间排。
     *
     * 判据全在 [TaskPlan.nextAt] 上：那是「下一次该响的时刻」，已经跑过的一次性任务
     * 它的值是 0（[TaskMath.nextAfter] 返回 null 时落成的），所以这里只认
     * `now < nextAt <= 今天最后一毫秒` 的。**别拿 createdAt 或 minuteOfDay 判** ——
     * 一个「每周三 8 点」的任务，今天是周五，它的 minuteOfDay 照样是 480。
     */
    fun todayPlans(plans: List<TaskPlan>, nowMs: Long, dayEndMs: Long): List<TaskPlan> =
        plans.filter { it.enabled && it.nextAt > nowMs && it.nextAt <= dayEndMs }
            .sortedBy { it.nextAt }
            .take(PAGES)

    /**
     * 从排好序的池子里取第 [batch] 批。
     *
     * ★ 走到底就**回到第 0 批**，而不是给一张空表 —— 空表在这块屏上的意思是
     *   「今天没新闻」，而实际上只是翻完了一轮。
     *
     * @param batch 从 0 起，允许比圈数还大（跨天累加），这里取不到就从头来。
     */
    fun <T> chunk(all: List<T>, batch: Int, size: Int = PAGES): List<T> {
        if (all.isEmpty() || size <= 0) return emptyList()
        var start = batch.coerceAtLeast(0) * size
        if (start >= all.size) start = 0
        return all.drop(start).take(size)
    }

    /**
     * 一句话压成清单上放得下的样子。
     *
     * ★ 定时任务的 prompt 是**一句完整的话**（见 [TaskPlan] 文件头：到点没人能追问），
     *   放到这块屏上只能留前面那段 —— 所以尾部补省略号，让他知道后面还有。
     */
    fun preview(prompt: String, max: Int = 15): String {
        val s = prompt.replace(Regex("[\\s\\u3000]+"), " ").trim()
        if (s.length <= max) return s
        return s.substring(0, (max - 1).coerceAtLeast(1)) + "…"
    }
}
