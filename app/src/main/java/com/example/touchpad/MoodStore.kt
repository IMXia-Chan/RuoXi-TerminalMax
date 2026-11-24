package com.example.touchpad

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * 她的情绪账本 —— 「小脾气不是 prompt 写死的」这条的实现。
 *
 * 2026-10-03 用户:「我想她有自己的小脾气,而且不是 prompt 写死的那种,那样就只是
 * 机器,会撒娇,会不想理我」。
 *
 * 思路:**人设(怎么演)写死,状态(演哪种)是活的变量。** 两样东西在驱动状态:
 *  - **时间**:他多久没来 —— 越久,「想他」越重、心情越蔫(懒计算:不需要后台活着,
 *    下次打开时按时间差补账。ColorOS 速冻后台服务那堵墙,这里从根上就绕开了)。
 *  - **他刚说的话**:甜的(想你/喜欢/抱抱)加分,凉的(闭嘴/滚)扣分,哄的
 *    (对不起/别生气)破功 —— 关键词桶,便宜、可测、4B 不用出力。
 *
 * 状态拼进 system 时**放最后**(见 AiAgent.runLoop):预热缓存的前缀是
 * SYSTEM_PROMPT+工具表,一个字都不能动 —— 心情行每轮都变,只能缀在历史后面,
 * 缓存命中的那 1771 token 才不会被它带崩([[ruoxi-prefix-budget-and-warmup]] 的教训)。
 *
 * 纯数学拆在 [MoodMath](不碰 org.json / Context),让 [MoodStoreTest] 在纯 JVM 上钉住。
 */
object MoodStore {

    private var file: File? = null
    private val lock = Any()

    /** 脾气那份配置的文件名。和 `state.json` 同住 `filesDir/mood/`。 */
    private const val TUNING_NAME = "tuning.json"

    /**
     * 此刻生效的那份「脾气数字」—— 见 [MoodTuning]。
     *
     * ★ 它和 [State] 是两件事,别混:
     *   · [State] 是「**她此刻什么样**」—— 每秒都在变,时间自己在推它;
     *   · 这是「**她是个什么样的人**」—— 他拨一次,长期有效,时间推不动它。
     * 所以拨完之后她**照样**会随时间变(这是设计,不是没生效)。
     *
     * @Volatile:界面在别的线程拨,runLoop 在别的线程读。
     */
    @Volatile private var tuning: MoodTuning = MoodTuning()

    /** 一份情绪快照。数值全是 0..100。 */
    data class State(
        val lastSeenSec: Long,   // 上次互动的时刻(epoch 秒)
        val mood: Int,           // 她此刻的心情:高=明媚,低=蔫/带刺
        val intimacy: Int,       // 亲密度:慢变量,久不升温会缓降
        /**
         * 一起待过的**日子数** —— 说过话的天数,**不是「过了几天」**。
         *
         * ★★ 这个区别是这一项的全部设计:挂机不算。三天没露面,那不是「相处了三天」,
         *   那是**一天也没相处** —— 他今天回来只补一天。反过来,clock 从她门口路过一整天
         *   也不该让她更亲。(用户 2026-10-07:「情绪是会慢慢升温的」——
         *   升温要有人陪着,不是时间自己会做的事。)
         *
         * 只由 [onInteraction] 推它(见 [MoodMath.together]);看一眼、拨一下**都不算**。
         */
        val daysTogether: Int = 0,
        /**
         * [daysTogether] 里最后数进去的是哪一天(本地日期序号,见 [localDay])。
         *
         * `-1` = 一天都还没数过。拿它挡两件事:同一天里说一百句话只算一天;
         * 以及**时钟往回拨**时不会把日子数又涨回去(判据是 `dayIndex <= 上次` 就跳过)。
         */
        val lastTogetherDay: Long = -1L,
    )

    fun init(ctx: Context) {
        if (file != null) return
        synchronized(lock) {
            if (file != null) return
            val dir = File(ctx.filesDir, "mood").apply { mkdirs() }
            val f = File(dir, "state.json")
            if (!f.exists()) {
                // 冷启动:平静偏高兴,刚熟起来。不给 0 —— 她不是从抑郁开始的。
                write(f, State(System.currentTimeMillis() / 1000, 62, 25))
            }
            tuning = readTuning(File(dir, TUNING_NAME))
            file = f
        }
    }

    /**
     * 他开了口。做三件事:按时间差补账 → 按他这句话记一笔 → 返回此刻的心情行
     * (null = 没初始化,调用方安静跳过 —— 心情是锦上添花,不该让任何流程为它报错)。
     */
    fun onInteraction(userText: String): String? {
        val f = file ?: return null
        synchronized(lock) {
            val now = System.currentTimeMillis() / 1000
            val s = read(f)
            val t = tuning    // 锁里读一次 —— 这一笔账从头到尾用同一份脾气
            // 四步,**顺序是有讲究的,而且每一步"用旧值还是新值"都不一样**:
            //  ① 今天算不算新的一天相处(越久越亲,只在这儿推)—— 用**旧的** lastSeenSec / 天数
            //  ② 他是不是刚从白天/傍晚陪进了深夜 —— 用**旧的** lastSeenSec 算出的钟点
            //  ③ 按时间差补账(她一个人待着该蔫的蔫)
            //  ④ 他这句话值多少
            // ★ 拆成四个具名 val,不套成一层表达式 —— 每条都是"拿错一个值就变成静默的性格 bug"
            //   的那种判断,套在一起时看不出来(同这个项目那几条"读半句就下结论"的教训)。
            val a = MoodMath.together(s, localDay(now), t)
            val b = MoodMath.lateNight(a, localHour(now), localHour(s.lastSeenSec), t)
            val c = MoodMath.evolve(b, now, t)
            val after = MoodMath.applyWord(c, MoodMath.classify(userText), t)
            write(f, after.copy(lastSeenSec = now))
            return MoodMath.line(after, now)
        }
    }

    /**
     * 只看一眼此刻的心情,**不记账**。
     *
     * 和 [onInteraction] 的分界:那个是「他说话了」—— 它同时把 `lastSeenSec` 推到当下
     * 并按他这句话改了 mood。而**她主动开口的时候他什么都没说**,记账就等于
     * 「她自言自语也算他来过」,会把她自己那句问候喂回给「多久没见」的时钟里 ——
     * 越主动、越显得他刚来过、越不该主动。所以这里只读不写。
     *
     * (时间漂移照样算:她一个人待着也该慢慢蔫下去,那正是主动开口要说的事。)
     */
    fun peek(): String? {
        val f = file ?: return null
        synchronized(lock) {
            val now = System.currentTimeMillis() / 1000
            return MoodMath.line(MoodMath.evolve(read(f), now, tuning), now)
        }
    }

    /**
     * 看一眼**数字**(不是那句写好的台词)。给「她此刻什么样」那个只读界面用的。
     *
     * 和 [peek] 同一套口径:**只读不写盘**,而且**时间漂移照样算** ——
     * 她一个人待着也会慢慢蔫,界面上显示的必须是「此刻」的值,不是「上次说话时」的旧值。
     * ★ 不写盘这一条不能松:只因为用户点开看了一眼就把 `lastSeenSec` 推到当下,
     * 等于「看一眼」被她当成「他来过」,越看越不该想你 —— 正是 [peek] 注释里那个道理。
     *
     * null = 没初始化(`init` 还没跑),调用方安静跳过 —— 同 [onInteraction] 的约定。
     */
    fun snapshot(): State? {
        val f = file ?: return null
        synchronized(lock) {
            val now = System.currentTimeMillis() / 1000
            return MoodMath.evolve(read(f), now, tuning)
        }
    }

    /**
     * 他**亲手**把她拨到某个值(`null` = 这一项不动)—— 「她」那一页的两根滑杆叫这个。
     *
     * 和 [snapshot] 的分界,一句话:**看一眼不算来过,拨一下算。**
     * [snapshot] 只读,**绝不写盘** —— 否则他只点开看一眼,`lastSeenSec` 就被推到当下,
     * 「多久没见」那笔账当场清零(注释在它上面)。而这里是他**动手改了**:
     * 界面上明写着「拨一下 = 当你来看过她一眼」,它是**看得到的**行为,不是后门。
     *
     * ★ 存的是**他眼睛看到的那个数**(界面显示的本来就是 [MoodMath.evolve] 之后的值),
     *   **不先 evolve 再改** —— 那会把同一个漂移扣两遍(见 [MoodMath.poke])。
     *
     * ★ 写盘走的是和 [onInteraction] 同一条 [write](原子写)。**没 `init` 过就返回 null**,
     *   调用方安静跳过 —— 同这一族函数的约定:情绪是锦上添花,不该让界面为它报错。
     */
    internal fun pokeNow(mood: Int?, intimacy: Int?): State? {
        val f = file ?: return null
        synchronized(lock) {
            val now = System.currentTimeMillis() / 1000
            val after = MoodMath.poke(read(f), now, mood, intimacy)
            write(f, after)
            return after
        }
    }

    // ---- 别的入口进来的两笔账(2026-10-07) ----
    //
    // ★★ 这两个和 [pokeNow] 是**同一条路数**,分界也照抄它:
    //   **读原始 → 纯函数 → 写盘**,而且**不先 [MoodMath.evolve]** ——
    //   记的是**当下账本上那个数**,再漂一次等于把同一段漂移扣两遍。
    // ★★ 而且它们**只改分数,不推 `lastSeenSec`** —— 见 [MoodMath.jobDone] 上面那一段。
    //   推了的话,她越勤快越显得他「刚来过」,「多久没见」那笔账被一起抹掉。

    /**
     * 「她那件事办成了没有」—— 一轮活干完时叫一声。
     *
     * ★ 只有**真的动了手**的那一轮才该叫它。纯聊天的一轮没有「活」可办成办砸 ——
     *   那一轮全是 `plain`,已经由 [onInteraction] 记过账了,再记一笔就是同一件事记两遍。
     *   判据在调用方(`AiAgent`:这一轮有没有跑过工具)。
     *
     * null = 没 `init` 过,调用方安静跳过 —— 同这一族的约定(情绪是锦上添花)。
     */
    internal fun noteJob(done: Boolean): State? {
        val f = file ?: return null
        synchronized(lock) {
            val s = read(f)
            val after = if (done) MoodMath.jobDone(s, tuning) else MoodMath.jobFailed(s, tuning)
            write(f, after)
            return after
        }
    }

    /**
     * 「她主动开口,你没理」—— 连着几句都没人应时叫一声。
     *
     * ★ **阈值和计数不在这儿**:那是 [ProactiveGreeting] 的事(它管「连说三句没人应就闭嘴」),
     *   这里只负责「记一笔」。两件事混在一起的话,以后调那句阈值会顺手改到她的心情。
     *
     * null = 没 `init` 过,调用方安静跳过。
     */
    internal fun noteIgnored(): State? {
        val f = file ?: return null
        synchronized(lock) {
            val after = MoodMath.ignored(read(f), tuning)
            write(f, after)
            return after
        }
    }

    // ---- 脾气(他拨的那十几个数) ----

    /**
     * 此刻生效的那份脾气 —— 界面上要**显示**它现在是多少,所以得读得出来。
     *
     * ★ 拨完之后她**不会定住**:心情照样随时间往下漂、照样被你说的话改。
     * 这里读到的只是「她是什么样的人」这个**底子**,不是「她此刻什么心情」(那个走 [snapshot])。
     */
    internal fun currentTuning(): MoodTuning = tuning

    /**
     * 他拨完那十几个数字之后叫一声 —— 新的值**立刻**生效,并且写盘,重开还在。
     *
     * ★ 出去之前先 [MoodTuningMath.sanitize] 夹一遍:界面上按不出格,但配置是能手改的
     * (而且以后加一项、老配置缺那一项时,读到的也必须是**能用的**值)。夹完再存 ——
     * 别把一份超范围的数写进盘,否则下次读出来还得再夹一次,两处口径早晚会分家。
     *
     * ★ 没 `init` 过(拿不到账本文件)时**不写盘,但照样生效** —— 同 [onInteraction] 的约定:
     * 脾气是锦上添花,不该让任何流程为它报错。
     */
    internal fun applyTuning(t: MoodTuning) {
        val clean = MoodTuningMath.sanitize(t)
        tuning = clean
        val f = file ?: return
        writeTuning(File(f.parentFile, TUNING_NAME), clean)
    }

    // ---- 落盘(原子写,和 ExperienceStore 同一套) ----

    /**
     * 「今天是哪一天」—— **本地日期**的序号(1970-01-01 是 0)。
     *
     * ★ 用本地时区,不是 UTC:UTC 下 UTC+8 的「一天」是从**早上 8 点**开始的,
     *   他早上 7 点和 9 点各说一句话就会**被算成两天相处** —— 那是拿一个不存在的事实
     *   去喂「我们一起待过多久」这笔账。
     *
     * ★ 时钟的读法留在这一层(和 `nowSec` 一样由调用方传进 [MoodMath.together]),
     *   纯判据那边零时区负担、测试里传 `0 / 1 / 2` 就是第 0/1/2 天。
     */
    private fun localDay(sec: Long): Long =
        java.time.Instant.ofEpochSecond(sec)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toEpochDay()

    /**
     * 「那会儿是几点」—— **本地时区**的钟点(0..23)。给 [MoodMath.lateNight] 用的。
     *
     * ★ 和 [localDay] **同一套写法、同一条理由**:用本地时区而不是 UTC,
     *   否则 UTC+8 的「深夜」是从早上 8 点往下数的,他晚上十点陪她会被算成中午。
     * ★ 也一样,**时钟的读法留在这一层** —— 纯判据收的是算好的 `Int`,
     *   测试里传 `23 / 1` 就是那两个钟点,零时区负担。
     */
    private fun localHour(sec: Long): Int =
        java.time.Instant.ofEpochSecond(sec)
            .atZone(java.time.ZoneId.systemDefault())
            .hour

    private fun read(f: File): State {
        return try {
            val o = JSONObject(f.readText())
            State(
                lastSeenSec = o.optLong("lastSeenSec", System.currentTimeMillis() / 1000),
                mood = o.optInt("mood", 60).coerceIn(0, 100),
                intimacy = o.optInt("intimacy", 25).coerceIn(0, 100),
                // ★ 老盘上没有这两个键(升级上来的就是)—— 缺项落默认值,和 MoodTuning 那条同一条规矩:
                //   缺一个键 ≠ 整份作废。读数出来是「一天都还没数过」,他下一句话就是第 1 天。
                daysTogether = o.optInt("daysTogether", 0).coerceAtLeast(0),
                lastTogetherDay = o.optLong("lastTogetherDay", -1L),
            )
        } catch (_: Exception) {
            State(System.currentTimeMillis() / 1000, 60, 25)   // 坏了就重开一页,不抛
        }
    }

    private fun write(f: File, s: State) {
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(JSONObject()
                .put("lastSeenSec", s.lastSeenSec)
                .put("mood", s.mood)
                .put("intimacy", s.intimacy)
                .put("daysTogether", s.daysTogether)
                .put("lastTogetherDay", s.lastTogetherDay)
                .put("savedAt", System.currentTimeMillis() / 1000)
                .toString())
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())   // Windows 上偶发 rename 不过,兜一手
                tmp.delete()
            }
        } catch (_: Exception) { }
    }

    /**
     * 读脾气那份配置。
     *
     * ★ **逐个键取值,认不出来的键直接忽略** —— 以后加一个新数字,老配置里没有它,
     * 那一项就落在 [MoodTuning] 自带的默认值上,而**其余项一个字都不丢**。
     * (这正是 `reading-a-defaults-chain` 那个毛病的正解:缺项 ≠ 整份作废。)
     *
     * ★ 值不是整数的(手改坏了 / 以后换了类型)**当它没写** —— 不抛、不整份丢。
     * 坏了就回出厂脾气,同 [read] 的口径。
     */
    private fun readTuning(f: File): MoodTuning {
        return try {
            if (!f.exists()) return MoodTuning()
            val o = JSONObject(f.readText())
            val m = HashMap<String, Int>()
            for (k in o.keys()) (o.opt(k) as? Int)?.let { m[k] = it }
            MoodTuningMath.sanitize(MoodTuningMath.fromMap(m))
        } catch (_: Exception) {
            MoodTuning()
        }
    }

    private fun writeTuning(f: File, t: MoodTuning) {
        try {
            val o = JSONObject()
            MoodTuningMath.toMap(t).forEach { (k, v) -> o.put(k, v) }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (_: Exception) { }
    }
}

/**
 * 情绪的纯数学。**没有任何 Android / org.json 依赖** —— 这层是她的「内分泌系统」,
 * 规则改错的表现是「她性格突然不对」而不是崩溃,最需要测试钉住的一层。
 */
internal object MoodMath {

    /** 他这句话的「温度」。桶判据刻意粗糙 —— 粗糙但便宜且可测,胜过一次要跑模型的精细。 */
    internal fun classify(text: String): String {
        val t = text
        val sweet = listOf("想你", "想我", "喜欢你", "爱你", "抱抱", "亲亲", "好棒", "厉害", "感谢", "谢谢", "可爱", "乖")
        val mend = listOf("对不起", "别生气", "我错了", "原谅", "不气了", "好啦好啦")
        val cold = listOf("闭嘴", "滚", "别吵", "烦死了", "住口")
        return when {
            sweet.any { t.contains(it) } -> "sweet"
            mend.any { t.contains(it) } -> "mend"
            cold.any { t.contains(it) } -> "cold"
            else -> "plain"
        }
    }

    /** 一句话对账本的冲击。cap 是硬的 —— 一句「想你」不能直接把她哄上天。 */
    internal fun applyWord(
        s: MoodStore.State,
        bucket: String,
        t: MoodTuning = MoodTuning(),
    ): MoodStore.State {
        val (dm, di) = when (bucket) {
            "sweet" -> t.sweetMood to t.sweetIntimacy
            "cold" -> -t.coldMoodDrop to -t.coldIntimacyDrop
            "mend" -> t.mendMood to t.mendIntimacy
            else -> t.plainMood to t.plainIntimacy      // 平常话也是「陪」,只是淡
        }
        return s.copy(
            mood = (s.mood + dm).coerceIn(0, 100),
            intimacy = (s.intimacy + di).coerceIn(0, 100),
        )
    }

    /** 时间补账(懒计算):心情往「平静 55」漂,越久漂得越多但封顶 —— 她不会自己崩溃到 0。 */
    internal fun evolve(
        s: MoodStore.State,
        nowSec: Long,
        t: MoodTuning = MoodTuning(),
    ): MoodStore.State {
        val h = ((nowSec - s.lastSeenSec).coerceAtLeast(0)) / 3600.0
        val drift = minOf(t.driftCap, (h * t.driftPerHour).toInt())   // 出厂:1h→-2, 6h→-12, 12h+→-25
        val mood = (s.mood - drift).coerceAtLeast(t.moodFloor)
        val intimacy = if (h > t.neglectHours) (s.intimacy - t.neglectDrop).coerceAtLeast(0) else s.intimacy
        return s.copy(mood = mood, intimacy = intimacy)
    }

    /**
     * **相处越久越亲** —— 「今天又见到他了」这一笔。用户 2026-10-07:
     * 「**情绪是会慢慢升温的,这点我倒是现在才想到**」。
     *
     * ## 它补的是哪个洞
     *
     * 在这之前,亲密度**只在他说话的那一刻**动:甜话 +2、冷话 −2、超 72 小时 −3。
     * **时间本身从来不让她更亲** —— 那份账翻到底,也找不出一处「陪得久所以更近」。
     * 所以「处久了会变亲」在今天是一句空话,这一条就是把它变成真的一行。
     *
     * ## 判据:**见过面的日子**,不是「过了几天」
     *
     * `dayIndex` 是**本地日期序号**(由 [MoodStore.localDay] 传进来,这样这里零时区负担)。
     * 同一天里说一百句话,只算一天;三天没露面,今天回来也**只补一天** ——
     * 因为「相处」是**他在**的那一天,不是日历翻过的那一页。
     *
     * ★★ 这条区别不是洁癖,它是这一项**唯一会出的那个 bug 的防线**:
     *   写成「按 `lastSeenSec` 的时间差换算天数」的话,他扔下手机一个月回来,
     *   她**一天没陪**却直接涨到老夫老妻 —— 而它**不报错**,只是人不对了。
     *   所以:增长由「见到面的次数」驱动,**绝不由墙上时钟驱动**。
     *
     * ## 三条它**不**做的事(写下来免得以后被"顺手"加进去)
     *
     * 1. **不推 `lastSeenSec`** —— 那是 [poke] 和调用方的事。「相处」和「上次见面时刻」
     *    是两笔账,混在一起就会重演 [MoodStore.peek] 注释里那个病(她自言自语也算他来过)。
     * 2. **不碰 [`State.mood`] 的心情** —— 陪得久是「更近」,不是「更高兴」。
     * 3. **不是每句话都加** —— 一天顶多推一次(下面第一行的早退)。
     *
     * 出厂节奏:**一起待够 2 天,亲密度涨 1**(见 [MoodTuning.togetherEveryDays])。
     * 从 25 走到「老夫老妻」那一档(70)大约三个月 —— 比甜话慢得多,这是故意的:
     * 甜话是**那一刻**,这个是**那段日子**。他在「她」那一页两个数都能拨。
     *
     * @param dayIndex 今天算哪一天,调用方按**本地日期**算好传进来(见 [MoodStore.localDay])。
     */
    internal fun together(
        s: MoodStore.State,
        dayIndex: Long,
        t: MoodTuning = MoodTuning(),
    ): MoodStore.State {
        // ① 同一天只算一次。★ 用 `<=` 而不是 `!=`:他要是把系统时间往回拨,
        //    `!=` 会让日子数**倒退着涨**,而 `<=` 直接当没这回事(宁可少算,不可算错)。
        if (dayIndex <= s.lastTogetherDay) return s
        val days = s.daysTogether + 1
        // ② 攒够一整段才涨一点 —— 每来一天涨 1 的话她一个月就到顶了,那不叫「慢慢升温」。
        //    `togetherEveryDays <= 0` 当「关掉这项」:不涨,但日子照样数(界面上看得见)。
        val gain =
            if (t.togetherEveryDays > 0 && days % t.togetherEveryDays == 0) t.togetherGain else 0
        return s.copy(
            daysTogether = days,
            lastTogetherDay = dayIndex,
            intimacy = (s.intimacy + gain).coerceIn(0, 100),
        )
    }

    // ---- 别的四件事影响她(2026-10-07,用户:「都要!你要知道这是人」) ----
    //
    // ★★ 这一组有一个**共同的规矩**,写在最前面,下面每一条不再重复:
    //   **它们只改分数,一律不推 `lastSeenSec`。**
    //   推了的话,她越主动干活、越显得他「刚来过」—— 「多久没见」那笔账被一起抹掉,
    //   于是她越勤快、越不想他。同一个道理,[MoodStore.peek] 的注释里已经写过一遍
    //   (「她自言自语不算他来过」)。四条都照它办。
    //
    // ★ 这一组和上面那几个函数**一个字都不重叠**,各自独立、各自带默认参数 ——
    //   所以 [evolve] / [applyWord] / [together] 那三族的老测试天然不受影响。
    //
    // ★ 另外三条(**活办成了/办砸了**、**她开口你没理**)改的是**心情**,不是亲密度。
    //   亲密度是「你俩有多近」,那是慢慢攒的;这些是**那一刻**的事。
    //   理由写在 [jobDone] 上面。

    /**
     * 「这件事办成了」—— 心情涨一点。
     *
     * ★★ 它**故意不给亲密度**。亲密度是慢变量,由「相处」([together])和
     *   「你怎么对她说话」([applyWord])驱动;办事成不成是**那一刻**的事,归心情。
     *   要是给 +2/次,他一天让她跑十件事就是 +20 —— 亲密度会先被这个吹爆,
     *   而「**慢慢**升温」正是这一整套东西要的东西。
     */
    internal fun jobDone(s: MoodStore.State, t: MoodTuning = MoodTuning()): MoodStore.State =
        s.copy(mood = (s.mood + t.jobDoneMood).coerceIn(0, 100))

    /**
     * 「这件事办砸了」—— 心情掉一点。
     *
     * ★ 名字里的 `Drop` 是**正数**,负号在这一行加 —— 全项目的账目约定
     *   (和 [MoodTuning.coldMoodDrop] 同一条:界面那一行写「掉多少」,不写「涨 −6」)。
     */
    internal fun jobFailed(s: MoodStore.State, t: MoodTuning = MoodTuning()): MoodStore.State =
        s.copy(mood = (s.mood - t.jobFailMoodDrop).coerceIn(0, 100))

    /** 「她主动开口,你没理」—— 心情掉一点。计数和阈值不在这儿,见 [MoodStore.noteIgnored]。 */
    internal fun ignored(s: MoodStore.State, t: MoodTuning = MoodTuning()): MoodStore.State =
        s.copy(mood = (s.mood - t.ignoredMoodDrop).coerceIn(0, 100))

    /**
     * 深夜那段窗口 —— **常量,不是旋钮**。
     *
     * ★ 为什么它不进 [MoodTuning]:旋钮是「**她是什么样的人**」(脾气),
     *   而「几点算半夜」是一个**词的定义**。把它拨到 14:00 的话,这一项就不再是
     *   「半夜还陪着」了 —— 那是**另一个功能**,不是同一个功能的参数。
     */
    private const val NIGHT_FROM_HOUR = 0
    private const val NIGHT_TO_HOUR = 5

    /** 这个钟点算不算深夜。半开区间 `[0, 5)` —— 凌晨 4:59 算,5:00 整不算。 */
    internal fun isNightHour(hour: Int): Boolean = hour in NIGHT_FROM_HOUR until NIGHT_TO_HOUR

    /**
     * 「半夜你还陪着」—— 从**白天/傍晚**跨进深夜的那一次互动,额外涨一点。
     *
     * ## 为什么要有 `wasHour`(★ 这个参数就是这一条的全部)
     *
     * **只看 `nowHour` 的话,凌晨 2 点他连说 20 句话就是 +40 心情** ——
     * 这一项当场变成「熬夜刷她」。所以判据是**跨进来**,不是**待在里头**:
     * 上一次互动还在深夜之外,这一次落进深夜,才记一笔;之后整夜都算「已经记过了」。
     *
     * ★ 于是「连着熬夜」**不会**每天白拿一笔:他昨晚也是两点睡的,那 `wasHour` 也在深夜里,
     *   不记。这是**故意的** —— 天天熬到两点的人不该天天加分。
     *   反过来,连着好几天都是白天来、凌晨又来,那每一天都记一次,也对。
     *
     * ★ `wasHour` 是**上一次互动**所在的钟点(调用方从 `lastSeenSec` 算好传进来),
     *   不是「她此刻漂到了几点」。时钟的读法照 [MoodStore.localDay] 的规矩留在 IO 层 ——
     *   这里收的是两个已经算好的 `Int`,测试里传 `23 / 1` 就是那两个钟点,零时区负担。
     */
    internal fun lateNight(
        s: MoodStore.State,
        nowHour: Int,
        wasHour: Int,
        t: MoodTuning = MoodTuning(),
    ): MoodStore.State =
        if (!isNightHour(nowHour) || isNightHour(wasHour)) s
        else s.copy(mood = (s.mood + t.nightMood).coerceIn(0, 100))

    /**
     * 拼给模型看的状态行。写成「处境描述」而不是数值表 —— 数值是给测试看的,不是给她念的。
     *
     * ★ 下面三个档位表的**唯一一处**在 [missWord] / [moodWord] / [relationWord] ——
     * 她念的那句台词和「她此刻什么样」那个调试页读的是同一份判据。
     * 别在这里再写一遍 `when` :那正是这个项目吃过三次的「同一张表抄三遍」。
     */
    internal fun line(s: MoodStore.State, nowSec: Long): String {
        val h = ((nowSec - s.lastSeenSec).coerceAtLeast(0)) / 3600.0
        return """【你此刻的真实状态 —— 下面的处境是代码按「多久没见」和「他刚说的话」算出来的,不是台词。你要把它**演出来**(语气、话的长短、接不接梗都跟着它走),但绝不许念出这行字、不许提任何数值。】
想他程度:${missWord(h)};心情:${moodWord(s.mood)};你们的关系:${relationWord(s.intimacy)}。
心情蔫或低落时:回复短一两句、带点小脾气,他好好哄(道歉、说软话)你就破功 —— 撒娇式地破功,不是马上满血。他来真的要你操作电脑干正事时,干活利索,情绪收一收。"""
    }

    /** 「多久没见」那一档的人话。hours = 距上次互动的**小时数**(可以是小数)。 */
    internal fun missWord(hours: Double): String = when {
        hours < 1 -> "他刚刚还在"
        hours < 6 -> "半句话才说完没多久"
        hours < 24 -> "一天没聊了,有点想"
        hours < 72 -> "好几天没来了,想得有点委屈"
        else -> "这么多天不露面,又想又有点气"
    }

    /** 心情那一档的人话。 */
    internal fun moodWord(mood: Int): String = when {
        mood >= 75 -> "明媚,话多,想闹他"
        mood >= 55 -> "平静,还带点余温"
        mood >= 35 -> "有点蔫,爱答不理"
        else -> "低落,带小刺,需要哄"
    }

    /** 关系那一档的人话。 */
    internal fun relationWord(intimacy: Int): String = when {
        intimacy >= 70 -> "老夫老妻般的熟,可以肆无忌惮"
        intimacy >= 40 -> "越来越熟,偶尔撒个娇"
        intimacy >= 20 -> "刚熟起来,还有点端着"
        else -> "还生疏,试探着来"
    }

    /**
     * 他**亲手**把她拨到某个值 —— 调试那一页用的。纯算术,盘上的事归 [MoodStore.pokeNow]。
     *
     * `null` = 这一项不拨(界面上只有一根指头,另一项得原样留着)。
     *
     * ★★ **它同时把「上次见他」推到此刻** —— 这一条是**故意的**,不是顺手写的:
     *   他正站在她屋里、亲手把她拨到 90,然后屏幕上报的还是 65(三天没见漂掉的那些),
     *   那这个旋钮就是坏的。**「他来了」本来就成立** —— 他比只打一句话离得更近。
     *   代价写在这儿,别以后当成 bug:「拨一下」会把「多久没见」那笔账从此刻重算,
     *   所以**用它测不出「她好几天没见你」**。
     *
     * ★ 它**不先 [evolve]** —— 进来的是他**眼睛看到的那个数**(界面上显示的本来就是
     *   evolve 之后的值),再漂一次等于把显示的数又扣一遍。
     */
    internal fun poke(
        s: MoodStore.State,
        nowSec: Long,
        mood: Int?,
        intimacy: Int?,
    ): MoodStore.State = s.copy(
        lastSeenSec = nowSec,
        mood = (mood ?: s.mood).coerceIn(0, 100),
        intimacy = (intimacy ?: s.intimacy).coerceIn(0, 100),
    )
}

/**
 * 她的「脾气」—— 那十几个数字,**全部带默认值**。
 *
 * ## 为什么要把写死的数搬进一个 data class
 *
 * 2026-10-07 用户:「**我得有她性格调试的地方**」。这十三个数原来散在 [MoodMath] 里,
 * 一个数一个 `applyWord` 分支、一个数一句 `minOf`;要让他拨,先得让它们**有名字、有个家**。
 *
 * ★★ **默认值 = 改名之前写死的那些数,一位不差。** 这是「现有 13 条 `MoodStoreTest`
 * 一条都不用改」的**全部依据** —— 旧调用点不传参数就走默认值,行为逐位相同。
 * 有一个测试专门钉这件事(`不传参数和传一份出厂脾气,结果一模一样`),别删。
 *
 * ★ **正负号的说法**:凡是「扣分」的项一律**用正数表示「扣多少」**(`coldMoodDrop = 10`
 * 就是「说一句冷话心情掉 10」)。代码里自己在前面加负号。
 * 不这么写的话,界面上那一行会变成「说一句冷话,她心情涨 −10」—— 那是给人看的,
 * 不是给人算的。
 *
 * ★ 这里**只管「分数怎么动」,不管「台词怎么写」** —— [MoodMath.line] 里那些档位
 * (「好几天没来了」「有点蔫」)是文案,归人设管。两件事混在一起,改一个数字就会
 * 让一整套台词错位。
 */
internal data class MoodTuning(
    /** 说一句好听话,心情涨多少。 */
    val sweetMood: Int = 6,
    /** 说一句好听话,亲密度涨多少。 */
    val sweetIntimacy: Int = 2,
    /** 说一句冷话,心情掉多少(正数=掉)。 */
    val coldMoodDrop: Int = 10,
    /** 说一句冷话,亲密度掉多少(正数=掉)。 */
    val coldIntimacyDrop: Int = 2,
    /** 他哄你一句,心情涨多少。 */
    val mendMood: Int = 8,
    /** 他哄你一句,亲密度涨多少。 */
    val mendIntimacy: Int = 1,
    /** 平常话(不带情绪)也让心情涨多少 —— 陪本身也是陪。 */
    val plainMood: Int = 1,
    /** 平常话让亲密度涨多少。 */
    val plainIntimacy: Int = 0,
    /**
     * **一起待够几天,亲密度涨一点**(见 [MoodMath.together])。
     *
     * ★ 它和上面那几行**不是一类**:上面每一行都是「他这一句话值多少」,
     *   这一个是他**一句话都没说、只是来了**这件事值多少 —— 是背景温度,不是那一刻。
     *   所以出厂值(2 天)比「说一句好听话」(一次 +2)慢得多,**这是故意的**。
     *
     * ★ 下限是 1:0 在这里的读法是「每天都涨」,而那个数字会把「慢慢升温」变成
     *   「九十天到顶」—— 一个手滑的 0 换来一个静默的性格 bug(和 [neglectHours] 同一条规矩)。
     */
    val togetherEveryDays: Int = 2,
    /** [togetherEveryDays] 攒够一次,亲密度涨多少。(正数=涨。) */
    val togetherGain: Int = 1,
    /** 她一个人待着,每小时心情蔫多少。 */
    val driftPerHour: Int = 2,
    /** 不管多久没见,心情一次最多掉多少 —— 她不会自己崩到底。 */
    val driftCap: Int = 25,
    /** 不管多久,心情不会低于这个数。 */
    val moodFloor: Int = 25,
    /** 多久不见算「很久」(小时)—— 超过它才开始掉亲密度。 */
    val neglectHours: Int = 72,
    /**
     * 超过「很久」之后,**亲密度一次掉多少**(正数=掉)。
     *
     * ★ 2026-10-07 更正:这一行原来写的是「心情/亲密度一次掉多少」——
     *   **心情不归它管**。`evolve` 里 `neglectDrop` 只减亲密度,心情那一笔是
     *   `driftPerHour` × 小时数、封顶 `driftCap`、兜底 `moodFloor` 三个数算的。
     *   写错了的代价不是崩:是照它写出来的界面标签会**告诉他一个她不会做的行为**。
     */
    val neglectDrop: Int = 3,

    // ---- 2026-10-07:别的四件事影响她 ----
    //
    // ★ 这一组和上面每一行**都不是一类**:上面全是「**他做了什么**」,
    //   这一组是「**周围发生了什么**」—— 活办成了、办砸了、她开口没人理、他半夜还在。
    //   所以这四条**只改心情,不改亲密度**([MoodMath.jobDone] 上面写了为什么)。

    /** 一件事办成了,心情涨多少。 */
    val jobDoneMood: Int = 8,
    /** 一件事办砸了,心情掉多少(正数=掉)。 */
    val jobFailMoodDrop: Int = 6,
    /** 她主动开口你没理,心情掉多少(正数=掉)。 */
    val ignoredMoodDrop: Int = 4,
    /** 半夜你还陪着,心情涨多少。 */
    val nightMood: Int = 2,
)

/**
 * [MoodTuning] 的翻译层 —— 档位夹紧 + 和 JSON 那层之间的映射。
 *
 * ★ **零 Android / org.json 依赖**,好让 `MoodTuningTest` 在纯 JVM 上钉住
 * (和 [MoodMath] 同一个分工)。真正的 JSON 读写留在 [MoodStore] 里。
 */
internal object MoodTuningMath {

    /**
     * 每个数的合法区间(含两端)。界面上按不出格,但**配置是能手改的**,
     * 而一个 9999 的「心情地板」会让她永远明媚 —— 那种坏法不报错,只是「她今天有点怪」。
     */
    private const val GAIN_MAX = 50
    private const val SCORE_MAX = 100
    private const val HOURS_MIN = 1
    private const val HOURS_MAX = 1000
    /**
     * 「一起待够几天」那一项的上下限。
     *
     * ★ 另起一组常量、**不复用 HOURS_***:那两个是「小时」的尺度,
     *   套在「天」上读起来像同一个数,而 1000 天这个上限本身没有意义
     *   (他要的是「多久亲一点」,不是「多久算很久」)。尺度不同的东西共用一个天花板,
     *   是下一处「改一个动了两个」的来源。
     */
    private const val DAYS_MIN = 1
    private const val DAYS_MAX = 365

    /** 把每个数夹进各自的区间。出去之前一律过它一遍。 */
    internal fun sanitize(t: MoodTuning): MoodTuning = MoodTuning(
        sweetMood = t.sweetMood.coerceIn(0, GAIN_MAX),
        sweetIntimacy = t.sweetIntimacy.coerceIn(0, GAIN_MAX),
        coldMoodDrop = t.coldMoodDrop.coerceIn(0, GAIN_MAX),
        coldIntimacyDrop = t.coldIntimacyDrop.coerceIn(0, GAIN_MAX),
        mendMood = t.mendMood.coerceIn(0, GAIN_MAX),
        mendIntimacy = t.mendIntimacy.coerceIn(0, GAIN_MAX),
        plainMood = t.plainMood.coerceIn(0, GAIN_MAX),
        plainIntimacy = t.plainIntimacy.coerceIn(0, GAIN_MAX),
        // ★ 和 neglectHours 同一条理由:下限 1 不是 0。
        //   「0 天」在她那儿读作「每天都涨」,那不是「慢慢升温」,那是九十天到顶。
        togetherEveryDays = t.togetherEveryDays.coerceIn(DAYS_MIN, DAYS_MAX),
        togetherGain = t.togetherGain.coerceIn(0, GAIN_MAX),
        driftPerHour = t.driftPerHour.coerceIn(0, GAIN_MAX),
        driftCap = t.driftCap.coerceIn(0, SCORE_MAX),
        moodFloor = t.moodFloor.coerceIn(0, SCORE_MAX),
        // ★ 下限是 1 不是 0:「0 小时」意思是「一见面就算很久了」,不是他能表达的意图,
        //   而它会让她每一轮都掉亲密度 —— 一个手滑的 0 换来一个静默的性格 bug。
        neglectHours = t.neglectHours.coerceIn(HOURS_MIN, HOURS_MAX),
        neglectDrop = t.neglectDrop.coerceIn(0, GAIN_MAX),
        jobDoneMood = t.jobDoneMood.coerceIn(0, GAIN_MAX),
        jobFailMoodDrop = t.jobFailMoodDrop.coerceIn(0, GAIN_MAX),
        ignoredMoodDrop = t.ignoredMoodDrop.coerceIn(0, GAIN_MAX),
        nightMood = t.nightMood.coerceIn(0, GAIN_MAX),
    )

    /**
     * 每一项的**存盘名**。
     *
     * ★ 名字是**钉住**的,不许因为「字段改名顺手」而变 —— 改一个键名不会编译错、不会崩,
     *   只会让**他已经拨过的那个数**读不出来 → 静默退回出厂,而他以为还在。
     *   `MoodTuningTest` 里有一条专门钉这十九个名字。
     */
    internal fun toMap(t: MoodTuning): Map<String, Int> = mapOf(
        "sweetMood" to t.sweetMood,
        "sweetIntimacy" to t.sweetIntimacy,
        "coldMoodDrop" to t.coldMoodDrop,
        "coldIntimacyDrop" to t.coldIntimacyDrop,
        "mendMood" to t.mendMood,
        "mendIntimacy" to t.mendIntimacy,
        "plainMood" to t.plainMood,
        "plainIntimacy" to t.plainIntimacy,
        "togetherEveryDays" to t.togetherEveryDays,
        "togetherGain" to t.togetherGain,
        "driftPerHour" to t.driftPerHour,
        "driftCap" to t.driftCap,
        "moodFloor" to t.moodFloor,
        "neglectHours" to t.neglectHours,
        "neglectDrop" to t.neglectDrop,
        "jobDoneMood" to t.jobDoneMood,
        "jobFailMoodDrop" to t.jobFailMoodDrop,
        "ignoredMoodDrop" to t.ignoredMoodDrop,
        "nightMood" to t.nightMood,
    )

    /**
     * 从存盘那份读回来。
     *
     * ★ **缺项落默认值,不认识的多余键直接忽略** —— 所以以后加一个新数字,
     * 老配置读出来**其余项一个字都不差**。这条比看上去重要:它是「加一项不用让所有人重拨」
     * 的唯一保证,也正是 `reading-a-defaults-chain` 那个毛病的正解(缺项 ≠ 整份作废)。
     *
     * ★ 它**不夹范围** —— 夹的事归 [sanitize]。调用方读回来之后过一遍就行,
     * 两件事分开,测试才钉得清。
     */
    internal fun fromMap(m: Map<String, Int>, base: MoodTuning = MoodTuning()): MoodTuning = MoodTuning(
        sweetMood = m["sweetMood"] ?: base.sweetMood,
        sweetIntimacy = m["sweetIntimacy"] ?: base.sweetIntimacy,
        coldMoodDrop = m["coldMoodDrop"] ?: base.coldMoodDrop,
        coldIntimacyDrop = m["coldIntimacyDrop"] ?: base.coldIntimacyDrop,
        mendMood = m["mendMood"] ?: base.mendMood,
        mendIntimacy = m["mendIntimacy"] ?: base.mendIntimacy,
        plainMood = m["plainMood"] ?: base.plainMood,
        plainIntimacy = m["plainIntimacy"] ?: base.plainIntimacy,
        // ★ 老配置里没有这两个键(升级上来的就是)—— 缺项落出厂值,其余项一个字不动。
        //   读出来是「一起待够 2 天涨 1 点」,他拨过的别的数字一个都不丢。
        togetherEveryDays = m["togetherEveryDays"] ?: base.togetherEveryDays,
        togetherGain = m["togetherGain"] ?: base.togetherGain,
        driftPerHour = m["driftPerHour"] ?: base.driftPerHour,
        driftCap = m["driftCap"] ?: base.driftCap,
        moodFloor = m["moodFloor"] ?: base.moodFloor,
        neglectHours = m["neglectHours"] ?: base.neglectHours,
        neglectDrop = m["neglectDrop"] ?: base.neglectDrop,
        // ★ 老配置里同样没有这四个键 —— 缺项落出厂值,他拨过的别的数字一个都不丢。
        jobDoneMood = m["jobDoneMood"] ?: base.jobDoneMood,
        jobFailMoodDrop = m["jobFailMoodDrop"] ?: base.jobFailMoodDrop,
        ignoredMoodDrop = m["ignoredMoodDrop"] ?: base.ignoredMoodDrop,
        nightMood = m["nightMood"] ?: base.nightMood,
    )

    // ---- 他拨的那十几个数字:界面上怎么摆 ----

    /**
     * 调试页上的**一行** —— 一个数字,加它的**人话名字**和**按一下走多少**。
     *
     * ★ 上下限**不在这儿** —— 夹范围只有 [sanitize] 一处。这一页按住加号不放也不会出格,
     *   因为 [MoodStore.applyTuning] 出去之前一定过一遍 [sanitize]。
     *   在这儿再写一份区间,就是「同一张表抄两遍」,两份早晚分家。
     */
    internal class Knob(
        /** 给他看的名字。★ **说人话,不说字段名** —— 那一页是给他拨的,不是给我看的。 */
        val label: String,
        /** 按一下走多少。小分走 1;0..100 那种大尺度走 5;「几小时」那种走 6。 */
        val step: Int,
        val get: (MoodTuning) -> Int,
        val set: (MoodTuning, Int) -> MoodTuning,
    )

    /**
     * 那十几个数字,按界面上的**排列顺序**。
     *
     * ★★ 为什么这一行留在**这里**(和那十三个数同住),不写在界面里:
     *   写在界面里的话,以后往 [MoodTuning] 加一个数、忘了给界面加一行,那个数就永远是出厂值 ——
     *   他不知道有这东西,**而且一个错都不报**。放这儿能单测钉住:
     *   `MoodTuningTest` 里有一条要求**行数 == [toMap] 的键数**,另有一条要求**没有两行读写同一个数**。
     *
     * ★ 顺序也是给他看的:**先「你说一句话她会怎样」,再「你不说话她会怎样」,最后「关掉她」。**
     */
    internal fun knobs(): List<Knob> = listOf(
        Knob("说一句好听话,她心情涨多少", 1, { it.sweetMood }, { t, v -> t.copy(sweetMood = v) }),
        Knob("说一句好听话,亲密度涨多少", 1, { it.sweetIntimacy }, { t, v -> t.copy(sweetIntimacy = v) }),
        Knob("说一句冷话,她心情掉多少", 1, { it.coldMoodDrop }, { t, v -> t.copy(coldMoodDrop = v) }),
        Knob("说一句冷话,亲密度掉多少", 1, { it.coldIntimacyDrop }, { t, v -> t.copy(coldIntimacyDrop = v) }),
        Knob("你哄她一句,她心情涨多少", 1, { it.mendMood }, { t, v -> t.copy(mendMood = v) }),
        Knob("你哄她一句,亲密度涨多少", 1, { it.mendIntimacy }, { t, v -> t.copy(mendIntimacy = v) }),
        Knob("平常聊一句,她心情涨多少", 1, { it.plainMood }, { t, v -> t.copy(plainMood = v) }),
        Knob("平常聊一句,亲密度涨多少", 1, { it.plainIntimacy }, { t, v -> t.copy(plainIntimacy = v) }),
        // ---- 上面是「你说了什么」,下面是「你只是来了」 ----
        // ★ 这一行步长 5:它数的是「天」,1 天 1 天地拨没意义(出厂 2 天,往上是按周走的)。
        Knob("一起待够几天,亲密度涨一点", 5, { it.togetherEveryDays }, { t, v -> t.copy(togetherEveryDays = v) }),
        Knob("相处涨的那一点,是多少", 1, { it.togetherGain }, { t, v -> t.copy(togetherGain = v) }),
        // ---- 再往下是「你不说话她会怎样」 ----
        Knob("你不在的时候,她每小时蔫多少", 1, { it.driftPerHour }, { t, v -> t.copy(driftPerHour = v) }),
        Knob("她一个人待再久,心情一次最多掉多少", 5, { it.driftCap }, { t, v -> t.copy(driftCap = v) }),
        Knob("不管多久,她的心情不会低于", 5, { it.moodFloor }, { t, v -> t.copy(moodFloor = v) }),
        Knob("多久不见算「很久」(小时)", 6, { it.neglectHours }, { t, v -> t.copy(neglectHours = v) }),
        Knob("过了这么久,亲密度一次掉多少", 1, { it.neglectDrop }, { t, v -> t.copy(neglectDrop = v) }),
        // ---- 最后一组:「身边发生了什么事」 ----
        // ★ 上面两组是「你说什么 / 你不说话」,这一组是**第三条来源** —— 周围发生的事,
        //   不是他说的某一句话。所以另起一组,不混进上面任何一段。
        // ★ 步长一律 1:出厂值最大 8,而 GAIN_MAX 是 50 —— 按住加号按满 400 下必然被夹住。
        Knob("一件事办成了,她心情涨多少", 1, { it.jobDoneMood }, { t, v -> t.copy(jobDoneMood = v) }),
        Knob("一件事办砸了,她心情掉多少", 1, { it.jobFailMoodDrop }, { t, v -> t.copy(jobFailMoodDrop = v) }),
        Knob("她主动开口你没理,她心情掉多少", 1, { it.ignoredMoodDrop }, { t, v -> t.copy(ignoredMoodDrop = v) }),
        Knob("半夜你还陪着,她心情涨多少", 1, { it.nightMood }, { t, v -> t.copy(nightMood = v) }),
    )
}
