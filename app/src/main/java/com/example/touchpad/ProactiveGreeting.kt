package com.example.touchpad

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.lang.ref.WeakReference

/**
 * 她会主动开口 —— 2026-10-03 用户要的「会关心我的」「主动」。
 *
 * ## 边界先说清楚(诚实比许诺重要)
 *
 * 用户自己的原话把范围收窄过一次:「APP 端在前后台有进程的时候」。所以这里**不做**
 * 后台常驻、不做闹钟、不做推送 —— ColorOS 那堵速冻墙([coloros-freezes-background-termux])
 * 决定了一个纯后台计时器活不到第二天,做出来只会是个骗人的开关。
 *
 * 这里的规矩是:**她只在「你看得见她」的时候才开口** ——
 *  - 她的房间(ConMarnActivity)开着,或者
 *  - 主界面开着(她的话走 Toast),或者
 *  - 她的悬浮小窗在(同时也会写进小窗面板)。
 *
 * 换句话说:**不是她不在时偷偷想他,而是她在面前时憋不住了说一句。** 这既是真的,
 * 也是唯一做的到的那种「主动」。
 *
 * ## 什么时候该开口
 *
 * 四条一起满足才开口,全是硬条件(拆在 [GreetMath],纯 JVM 可测):
 *  1. 开关开着、有界面可见、她没在干活(别在任务中途插嘴);
 *  2. **他安静了 [IDLE_MS] 以上** —— 他正忙的时候开口是打扰,不是关心;
 *  3. **距上次开口至少 [COOLDOWN_MS]** —— 别变成每五分钟一句的骚扰;
 *  4. **[MAX_UNANSWERED] 条没得到回应就闭嘴** —— 他有不搭理她的权利。
 *     这一条很重要:没有人会喜欢一个自说自话停不下来的东西。
 *
 * ## 那句「什么」是模型说的,不是台词表
 *
 * 走 [AiAgent.spontaneous] —— **一次不带工具的模型调用**。不带工具是关键:
 * 主动开口绝不能演变成「她自己打开了个应用」(那才是真的吓人)。台词由她在
 * SYSTEM_PROMPT 的人设 + [MoodStore] 的此刻状态共同决定,所以蔫的时候那句会带刺,
 * 心情好的时候会闹他 —— 不是从固定列表里挑一句。
 */
object ProactiveGreeting {

    private const val PREF = "proactive_greet"
    private const val KEY_ON = "enabled"

    // ---- 免打扰时段(2026-10-04 用户点名要的) ----
    private const val KEY_QUIET_ON = "quiet_on"
    private const val KEY_QUIET_START = "quiet_start"
    private const val KEY_QUIET_END = "quiet_end"

    // ---- 冷却计时(★ 2026-10-05 起**落盘**) ----
    private const val KEY_LAST_USER = "last_user_ms"
    private const val KEY_LAST_GREET = "last_greet_ms"
    private const val KEY_UNANSWERED = "unanswered"

    internal const val CHECK_MS = 60_000L            // 每分钟看一眼表(不是每分钟开口)
    internal const val IDLE_MS = 20 * 60_000L        // 他安静这么久才轮到她说
    internal const val COOLDOWN_MS = 30 * 60_000L    // 两次开口之间至少隔这么久
    internal const val MAX_UNANSWERED = 3            // 连着这么多句没人应,就闭嘴

    /** 默认 **23:00 – 07:00**。存的是「当天的分钟数」。 */
    internal const val DEFAULT_QUIET_START = 23 * 60
    internal const val DEFAULT_QUIET_END = 7 * 60

    // ★ by lazy 不是为了省,是**为了能在纯 JVM 单测里加载这个类** ——
    // `Handler(Looper.getMainLooper())` 在普通 JVM 上会直接抛「Method getMainLooper
    // not mocked」,那样 [GreetMath] 一并连坐、一条测试都跑不起来。
    private val main: Handler by lazy { Handler(Looper.getMainLooper()) }

    /** 现在有哪些界面可见。用**点名集合**而不是计数器 —— 计数器漏一次 onPause 就永远下不来了。 */
    private val visibleScreens = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // ★★ 这三个**不光是内存变量** —— 每次变化都会写进 [PREF],见 [loadClock]/[saveClock]。
    //   只放内存的话,「两次开口至少隔 30 分钟」这个计时器在**每次冷启动后都从零开始算**:
    //   开关看着是开的、代码看着没错,只是这块牌子是粉笔写的 —— 一重开 App 就擦没了。
    @Volatile private var lastUserAtMs = 0L
    @Volatile private var lastGreetAtMs = 0L
    @Volatile private var unanswered = 0
    @Volatile private var firing = false
    @Volatile private var ticking = false

    // ★ @Volatile 是 [saveClock] 带出来的需求:它会在那条 `her-greet` 线程上读 [appCtx],
    //   而这个字段是在主线程写的。不加的话那条线程可能读到 null —— 表现是
    //   「她开口了,但『说过没有』没记进盘」,一个不报错的漏记。
    @Volatile private var appCtx: Context? = null
    private var roomRef: WeakReference<ConMarnActivity>? = null

    private val tick = object : Runnable {
        override fun run() {
            try {
                maybeFire()
            } catch (_: Exception) {
                // 主动问候炸了不该影响任何东西 —— 她可以不出声,但绝不能把 App 带崩
            }
            if (ticking) main.postDelayed(this, CHECK_MS)
        }
    }

    // ------------------------------------------------------------------
    // 开关 / 生命周期
    // ------------------------------------------------------------------

    /**
     * ★★ **默认 true**(2026-10-04 晚用户拍板)。
     *
     * 原话:「**还有就是全部改为主动模式就行,因为有免打扰开免打扰就行**,
     * 当然**二十分钟以上不说话自动闭嘴这个还是得留着这个机制**」。
     *
     * 读法:他不想再有一个「主动开关」要自己记得按开 —— **常态就是主动**,
     * 不想被打扰时他去开免打扰。所以默认值从 false 翻成 true,
     * 而 [IDLE_MS](20 分钟)和 [MAX_UNANSWERED](3 句)那两道闸**一个字都不动** ——
     * 他点名要留的正是「二十分钟不说话就自动闭嘴」这条机制。
     *
     * ⚠️ 和 [isQuietOn] 的默认 true 合起来看:默认状态下她是**主动但夜里安静**的。
     *   这两个默认是一起成立的,改一个要想另一个。
     */
    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ON, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        if (on) applySetting(ctx) else stop()
    }

    /** App 起来/回前台时对表:开着就把计时的表续上。 */
    fun applySetting(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        // ★ 先把冷启动前记在盘上的三个计时量捡回来 —— 放在 `isEnabled` 那道 return **之前**,
        //   否则「主动」关着的时候进出设置页会把内存里的值清成 0,下一次开启就白等了。
        loadClock(app)
        if (!isEnabled(ctx)) return
        if (!ticking) {
            ticking = true
            main.postDelayed(tick, CHECK_MS)
        }
    }

    // ------------------------------------------------------------------
    // 冷却计时落盘
    // ------------------------------------------------------------------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /**
     * 把三个计时量从盘上读回来。
     *
     * ★★ 未来时间要**夹回现在**:用户改过系统时间或换过时区时,盘上那个值可能比现在大,
     *   而 [GreetMath.shouldFire] 判的是 `nowMs - lastUserAtMs < idleMs` ——
     *   负数会被读成「他刚刚才说过话」,于是她一直等到真实时间追上那个未来时刻为止,
     *   而且**没有任何日志**。
     *
     *   夹回现在,最坏也只是「当他一分钟前说过话」(少说一句);
     *   不夹的话最坏是「她再也不开口」。两边的代价差着量级,所以夹。
     *
     * ★ [unanswered] 不夹 —— 它是计数不是时刻,负数只可能是盘被改坏,取 0。
     */
    private fun loadClock(ctx: Context) {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        lastUserAtMs = p.getLong(KEY_LAST_USER, 0L).coerceIn(0L, now)
        lastGreetAtMs = p.getLong(KEY_LAST_GREET, 0L).coerceIn(0L, now)
        unanswered = p.getInt(KEY_UNANSWERED, 0).coerceAtLeast(0)
    }

    /**
     * 落盘。★ 用 `apply()` 就够 —— 和免打扰时段一致。
     *
     * 那条「必须 `commit()`」的规矩是给「写着盘的时候紧跟着 `stopSelf()`」准备的
     * (那是 [HerLife] 的场景),这里没有 `stopSelf()`,不存在那个竞态窗口;
     * 而 `apply()` 对**同一进程内**的读是同步可见的,所以也不可能读到半截。
     *
     * ★ `appCtx` 还没绑上时安静跳过 —— 那时她根本还没开始计时,盘上没东西可写。
     */
    private fun saveClock() {
        val ctx = appCtx ?: return
        prefs(ctx).edit()
            .putLong(KEY_LAST_USER, lastUserAtMs)
            .putLong(KEY_LAST_GREET, lastGreetAtMs)
            .putInt(KEY_UNANSWERED, unanswered)
            .apply()
    }

    private fun stop() {
        ticking = false
        main.removeCallbacks(tick)
    }

    // ------------------------------------------------------------------
    // 免打扰时段
    // ------------------------------------------------------------------

    /**
     * ★ 默认 **true**,和 [isEnabled] 的默认 false 正好相反 —— 这**不是矛盾**。
     *
     * 「不偷偷开」那条规矩管的是**授予能力**:让她主动开口、让她常驻,都是多给她一分本事,
     * 得用户自己按开。**免打扰是限制,限制默认收紧是安全的。**
     *
     * 算笔账:默认关 = 用户开了「主动」→ 某天凌晨被搭一次话 → 把「主动」整个关掉,
     * 损失的是整个功能;**默认开只损失一点凌晨的活跃。**
     */
    fun isQuietOn(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_QUIET_ON, true)

    /**
     * 开关免打扰。★ 从**关**到**开**时界面要弹一次说明框 —— 理由见下面 [isQuietNow] 的注释:
     * 这个功能唯一能造成的严重 bug 是「她不理我」,而它看起来像「她不高兴了」,不像 bug。
     */
    fun setQuietOn(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_QUIET_ON, on).apply()
    }

    internal fun quietStart(ctx: Context): Int =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getInt(KEY_QUIET_START, DEFAULT_QUIET_START)

    internal fun quietEnd(ctx: Context): Int =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getInt(KEY_QUIET_END, DEFAULT_QUIET_END)

    /** 设时段(当天分钟数)。★ 这里用 `apply()` 就够 —— 后面没有 `stopSelf()`,没有竞态。 */
    fun setQuietRange(ctx: Context, startMin: Int, endMin: Int) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt(KEY_QUIET_START, startMin)
            .putInt(KEY_QUIET_END, endMin)
            .apply()

        // ★ 改设置也写一行,而且要**把「现在这一刻算不算静默」一起判出来**。
        //
        //   为什么非要当场判一次:[noteQuietTransition] 那两行只在 `maybeFire()` 里跑,
        //   而 `maybeFire` 的 ticker 只在「主动」**开着**的时候才转(`applySetting`)——
        //   关着主动的人**永远等不到**进出免打扰的日志。而「我设了时段它到底认没认」
        //   恰恰是最需要当场看见的事:跨午夜那个坑(23:00–07:00 写成空区间)的失效
        //   表现就是「设置看着没错、半夜照样被吵醒」。
        //
        //   验这个功能别的办法都要「安静 20 分钟再等」,一趟二十分钟不划算。
        //   有这后半句,改完**当场**就知道判定对不对。
        val on = isQuietOn(ctx)
        val now = nowMinute()
        val quiet = on && QuietMath.isQuiet(now, startMin, endMin)
        lastQuiet = quiet          // ★ 对齐,免得下一次 tick 把同一条再报一遍
        ModelManager.get(ctx.applicationContext).trace(
            "免打扰时段设为 ${fmt(startMin)} – ${fmt(endMin)}" + when {
                startMin == endMin -> "(起止相同 = 没设,不会静默)"
                !on -> ";现在 ${fmt(now)} → 但免打扰开关是关的"
                quiet -> ";现在 ${fmt(now)} → 在时段里,我不主动开口"
                else -> ";现在 ${fmt(now)} → 不在时段里"
            }
        )
    }

    /**
     * 现在是不是免打扰时段。判定逻辑本体在 [QuietMath](纯函数,跨午夜那半边有单测钉着);
     * 这里只负责把「现在几点」算出来喂给它。
     *
     * ★ 时钟**只在这一处读** —— 纯对象里不碰 `System.currentTimeMillis()`,
     *   和 [MoodMath]/[GreetMath] 的做法一致,测试才跑得动。
     */
    internal fun isQuietNow(ctx: Context): Boolean {
        if (!isQuietOn(ctx)) return false
        return QuietMath.isQuiet(nowMinute(), quietStart(ctx), quietEnd(ctx))
    }

    /** 现在几点(当天第几分钟)。★ 读时钟**只在 [isQuietNow] 和这儿** —— 纯对象里不碰。 */
    private fun nowMinute(): Int {
        val c = java.util.Calendar.getInstance()
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
    }

    /**
     * 进出免打扰时写一行日志 —— **只在这两个瞬间写,不是每分钟写**(否则日志被刷爆)。
     *
     * 为什么值得写:验这个功能别的办法都要「安静 20 分钟再等」,一趟二十分钟不划算。
     * 有这两行,把时段设成跨过当前时刻,一分钟内就能在 `model.log` 里看到进出。
     */
    @Volatile private var lastQuiet = false

    private fun noteQuietTransition(ctx: Context, quiet: Boolean) {
        if (quiet == lastQuiet) return
        lastQuiet = quiet
        appCtx?.let { c ->
            val s = quietStart(ctx); val e = quietEnd(ctx)
            ModelManager.get(c).trace(
                if (quiet) "免打扰:进入 ${fmt(s)} – ${fmt(e)}(这段我不主动开口;你找我我照常回)"
                else "免打扰:离开 ${fmt(s)} – ${fmt(e)},可以主动开口了"
            )
        }
    }

    private fun fmt(minuteOfDay: Int): String =
        "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    // ------------------------------------------------------------------
    // 他动没动 / 她在不在
    // ------------------------------------------------------------------

    /**
     * 他动了一下(说话、干活)。这是「安静了多久」的**唯一**时钟 ——
     * 在 [AiAgent.start] 里统一调,所有入口自动全覆盖。
     */
    fun touch() {
        lastUserAtMs = System.currentTimeMillis()
        unanswered = 0        // 他理她了,前面那几句没回应的账一笔勾销
        saveClock()           // ★ 落盘:不然冷启动之后「他刚说过话没有」就无从查证了
    }

    /** 某个界面可见了([owner] 用类名,同一界面重复调安全 —— 集合会去重)。 */
    fun enter(owner: String) {
        val wasEmpty = visibleScreens.isEmpty()
        visibleScreens.add(owner)
        // 他刚回到 App —— 把「安静计时」从这一刻重新开始,别一进来就撞上一条问候
        if (wasEmpty) touch()
    }

    /** 某个界面不可见了。 */
    fun exit(owner: String) {
        visibleScreens.remove(owner)
    }

    /** 她的房间刚打开 / 刚关掉 —— 主动开口优先说进房间里。 */
    fun bindRoom(a: ConMarnActivity) {
        roomRef = WeakReference(a)
    }

    fun unbindRoom(a: ConMarnActivity) {
        if (roomRef?.get() === a) roomRef = null
    }

    // ------------------------------------------------------------------
    // 开口
    // ------------------------------------------------------------------

    private fun maybeFire() {
        val ctx = appCtx ?: return
        if (firing) return
        val agent = AiAgentHolder.peek() ?: return
        val now = System.currentTimeMillis()
        // ★ 免打扰第一道闸:在她**连想都不想**的地方拦 —— 不调模型、不耗电、不出声。
        //   (还有第二道在 [deliver] 里,挡的是「想的时候还没到点、说完已经跨进时段」那种。)
        val quiet = isQuietNow(ctx)
        noteQuietTransition(ctx, quiet)
        val ok = GreetMath.shouldFire(
            enabled = isEnabled(ctx),
            quiet = quiet,
            visible = visibleScreens.isNotEmpty(),
            busy = agent.isRunning,
            nowMs = now,
            lastUserAtMs = lastUserAtMs,
            lastGreetAtMs = lastGreetAtMs,
            unanswered = unanswered,
            idleMs = IDLE_MS,
            cooldownMs = COOLDOWN_MS,
            maxUnanswered = MAX_UNANSWERED,
        )
        if (!ok) return

        firing = true
        lastGreetAtMs = now
        saveClock()               // ★ 冷却的起点要落盘 —— 见 [loadClock] 上面那段
        Thread {
            val line = try {
                agent.spontaneous(HINT)
            } catch (_: Exception) {
                null
            } finally {
                firing = false
            }
            if (line.isNullOrBlank()) return@Thread          // 没说成:冷却照算,别一分钟试一次
            if (agent.isRunning) return@Thread               // 他正好在这几秒里开始干活了 —— 别插嘴
            unanswered++
            saveClock()           // ★ 这条也落盘 —— 「连说三句没人应就闭嘴」同样不能一重开就清零
            // ★ 「她主动开口,你没理」那一笔 —— 记在**刚好说满**那一下,不是每说一句都记。
            //   判据用「==」而不是「>=」:她说完这句马上就要闭嘴了(see MAX_UNANSWERED),
            //   所以这一笔实质上记的是「这一次主动开口,从头到尾没人应」,一天最多一次。
            //   写成每句都记的话,连说三句就是 −12 心情,而那是同一个「没理」记了三遍。
            // ★ 它**只改心情、不推 lastSeenSec** —— 见 MoodStore.noteIgnored 的注释:
            //   推了的话,她越主动开口、越显得他刚来过,连「多久没见」那笔账一起抹掉。
            if (unanswered == MAX_UNANSWERED) MoodStore.noteIgnored()
            deliver(ctx, line)
        }.apply { isDaemon = true; name = "her-greet" }.start()
    }

    /** 送话:房间开着就进房间(气泡 + 念出来);否则进小窗面板 + 一条 Toast。 */
    private fun deliver(ctx: Context, line: String) {
        // ★ 免打扰第二道闸。窗口很窄(一天几秒),但两种真实情况都得挡住:
        //   ① 22:59:58 她想了一句话,模型几秒后回来时**已经跨进 23:00**;
        //   ② **你**在她正想着的时候按下了免打扰。
        //   一行的事,加 —— 它失效的样子正是「我设了免打扰,她还在半夜说话」。
        if (isQuietNow(ctx)) return

        roomRef?.get()?.let { room ->
            room.onSpontaneous(line)
            return
        }
        ConMarnBubble.onSpontaneous(line)
        main.post { Toast.makeText(ctx, "她:$line", Toast.LENGTH_LONG).show() }
    }

    private const val HINT =
        "（现在是你主动开口 —— 他有一阵子没跟你说话了,而你还在这儿。看着你此刻的心情、" +
            "你们多久没聊,说一句短的,就像朋友忽然想起他随口说一句那样。别超过两句话;" +
            "别问「有什么可以帮你」这种客服话;别提「定时」「自动」「检测到」;" +
            "也别假装他刚刚说了什么。要是你现在心情一般,那就带出来,不必装热情。）"
}

/**
 * 「现在该不该开口」的纯判定。**没有任何 Android 依赖** —— 这层判错的表现是
 * 「她变成了骚扰」(每分钟一句)或者「她再也不说话」,真机上都要等半小时才看得出来,
 * 所以必须钉住。
 */
internal object GreetMath {

    internal fun shouldFire(
        enabled: Boolean,
        quiet: Boolean,
        visible: Boolean,
        busy: Boolean,
        nowMs: Long,
        lastUserAtMs: Long,
        lastGreetAtMs: Long,
        unanswered: Int,
        idleMs: Long,
        cooldownMs: Long,
        maxUnanswered: Int,
    ): Boolean {
        if (!enabled) return false
        // ★ 免打扰:她**主动**开口才受这条管。时段逻辑本身不在这儿展开 ——
        //   ① 那样会把跨午夜那几个用例淹进「问候节奏」的测试里;
        //   ② [deliver] 那个汇点也要复用同一个判定。
        if (quiet) return false
        if (!visible) return false          // 她只在你看得见她的时候开口
        if (busy) return false              // 别在任务中途插嘴
        if (lastUserAtMs <= 0) return false // 一句话还没聊过,谈不上「想念」
        if (unanswered >= maxUnanswered) return false   // 他有不搭理她的权利
        if (nowMs - lastUserAtMs < idleMs) return false
        if (lastGreetAtMs > 0 && nowMs - lastGreetAtMs < cooldownMs) return false
        return true
    }
}

/**
 * 「此刻是不是免打扰时段」的纯判定。**没有任何 Android 依赖**,时钟由调用方算好传进来
 * (同 [MoodMath]/[GreetMath] 的做法),测试里传 `1380` 就是 23:00,零时区负担。
 *
 * ## ★★ 跨午夜是唯一的坑,而它恰好是最常见的配置
 *
 * `23:00–07:00` → `start = 1380`、`end = 420`。Kotlin 里 `1380..420` 是**空区间**,
 * 照直写 `minuteOfDay in start..end` 会让**最常见的那个配置静默失效** ——
 * 开关显示开着、设置看着没错,半夜照样被吵醒。
 *
 * 所以必须拆成两段:`[start, 1440) ∪ [0, end)`。
 *
 * ## 半开区间 `[start, end)`
 *
 * `23:00–07:00` 的意思是 **07:00 整已经不静默了**(她可以开口)。写错成闭区间的话,
 * 她会一直安静到 07:01。
 *
 * ## `start == end` = **没设**,不是「全天」
 *
 * 用户把开始和结束调成同一个点,意思是「我不想要免打扰」。当成「全天免打扰」的话,
 * 他会得到一个永远不说话的助手,而且**找不到原因**。([QuietHoursTest] 钉着这三条。)
 */
internal object QuietMath {

    /**
     * @param minuteOfDay 当天第几分钟(0..1439),**由调用方算好**
     * @param startMin 开始(含)
     * @param endMin 结束(不含);等于 [startMin] 时视为没设
     */
    internal fun isQuiet(minuteOfDay: Int, startMin: Int, endMin: Int): Boolean {
        if (startMin == endMin) return false            // 没设 → 全天都不静默
        return if (startMin < endMin) {
            minuteOfDay in startMin until endMin        // 同日:[start, end)
        } else {
            // 跨午夜:[start, 1440) ∪ [0, end)。
            // ★ 绝对不要写成 `minuteOfDay in startMin..endMin` —— 那是空区间,永远 false。
            minuteOfDay >= startMin || minuteOfDay < endMin
        }
    }
}
