package com.example.touchpad

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 她的长期记忆 —— 跨会话记住「他是谁、他怎么说话」。
 *
 * 2026-10-03 用户点名要的:[[conmarn-fused-persona]] 里那条「长期记忆(跨会话称呼/习惯,
 * 和 ASR 纠错共用一套)」。和 [MoodStore] 是两种东西:**心情是此刻的状态,记忆是攒下来的资产**。
 *
 * ## 两条采集路线(想清楚「信谁」才写的)
 *
 * 1. **称呼 —— 只认明确信号。** 「叫我小明」「以后叫我小明」这类是**他亲口下的定义**,
 *    抽出来可以直接用。反过来「我是学生」这种**绝不敢抽**:抽错的代价是她一本正经地
 *    管他叫「学生」,而他会觉得她蠢 —— 记忆错了比没有记忆更糟。
 * 2. **习惯 —— 从行为里数,不从话里猜。** 他「常用什么」不靠解析句子(「我喜欢你」
 *    能抽出一个「你」来),而是数**他实际让 AI 开的应用**:open_app 成功一次记一笔。
 *    这是最诚实的信号 —— 他做的事比他说的话可信。
 *
 * ## 和 ASR 纠错共用一套
 *
 * 「不是威信,是微信」这句话同时是两件事:一个称呼纠正,和一条**同音字映射**。
 * 存进 [Store.fixes] 之后,语音输入的文字下次会被 [correct] 自动换掉 —— 他说错一次,
 * 以后就再也不用说第二次。这也是为什么这个文件和 ASR 共用一份词典而不是各存一套。
 *
 * ## 缓存纪律(和 [MoodStore] 同一条)
 *
 * 拼给模型的那块**必须缀在历史后面、新 user 消息前面**:SYSTEM_PROMPT + 工具表那截
 * 前缀是预热缓存的生命线,一个字都不能动([[ruoxi-prefix-budget-and-warmup]])。
 *
 * 纯规则拆在 [LexiconMath](不碰 org.json / Context),让 [UserLexiconTest] 纯 JVM 钉住 ——
 * 这层写错的表现是「她突然开始胡说他是谁」,真机上极难发现。
 */
object UserLexicon {

    // ★ 这三个数字**界面上要报**(「最多记 3 个」),所以不能再是 private。
    internal const val MAX_CALL = 3         // 他允许被叫的名字,最多记几个
    internal const val MAX_FIXES = 30       // 同音字映射,别让它无限长
    internal const val MAX_APPS = 40

    /** 手打一条的限长。和 [LexiconMath.looksLikeName] / `FIX` 那条正则的上限对齐。 */
    internal const val MAX_NAME_LEN = 8
    internal const val MAX_FIX_LEN = 12

    private var file: File? = null
    private val lock = Any()

    /** 一份记忆快照。list/map 都是不可变的,改就是整体换一份再落盘。 */
    data class Store(
        val call: List<String> = emptyList(),
        val avoid: List<String> = emptyList(),
        val apps: Map<String, Int> = emptyMap(),
        val fixes: Map<String, String> = emptyMap(),
    )

    fun init(ctx: Context) {
        if (file != null) return
        synchronized(lock) {
            if (file != null) return
            val dir = File(ctx.filesDir, "lexicon").apply { mkdirs() }
            val f = File(dir, "lexicon.json")
            if (!f.exists()) write(f, Store())
            file = f
        }
    }

    /**
     * 他刚说了一句话 —— 抽事实、合并、落盘。**静默、不抛**:记忆是锦上添花,
     * 不该让任何一轮任务为它报错。
     */
    fun observe(userText: String) {
        val f = file ?: return
        val facts = LexiconMath.extract(userText)
        if (facts.call.isEmpty() && facts.avoid.isEmpty() && facts.fixes.isEmpty()) return
        synchronized(lock) {
            var s = read(f)
            // 禁止的叫法要同时从「允许」里划掉 —— 「别叫我老板」之后还在喊老板最尴尬
            val call = (facts.call + s.call)
                .filterNot { it in facts.avoid || it in s.avoid }
                .distinct()
                .take(MAX_CALL)
            val fixes = LinkedHashMap(s.fixes)
            facts.fixes.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) fixes[k] = v }
            s = s.copy(
                call = call,
                avoid = (s.avoid + facts.avoid).distinct().take(MAX_CALL),
                // toList() 再 takeLast:Map.entries 是 Set,Set 上没有 takeLast,
                // 而 LinkedHashMap 的 entries 顺序就是插入顺序 —— 留下的正是最新的几条。
                fixes = fixes.entries.toList().takeLast(MAX_FIXES).associate { it.key to it.value },
            )
            write(f, s)
        }
    }

    /** 他让你开了个应用并且**真开成了** —— 记一笔。这是他「常用什么」最诚实的来源。 */
    fun noteApp(name: String) {
        val f = file ?: return
        val n = name.trim()
        if (n.isEmpty() || n.length > 24) return
        synchronized(lock) {
            val s = read(f)
            val apps = LinkedHashMap(s.apps)
            apps[n] = (apps[n] ?: 0) + 1
            val kept = apps.entries.sortedByDescending { it.value }.take(MAX_APPS)
                .associate { it.key to it.value }
            write(f, s.copy(apps = kept))
        }
    }

    /**
     * 语音转出来的文字过一遍同音字映射 —— 他纠正过一次的错,不该让他再纠正第二次。
     * 没有记忆/没有命中时**原样返回**。
     */
    fun correct(text: String): String {
        val f = file ?: return text
        val fixes = synchronized(lock) { read(f).fixes }
        return if (fixes.isEmpty()) text else LexiconMath.correct(text, fixes)
    }

    /** 拼给模型看的那块。没攒下东西时返回 null(调用方安静跳过)。 */
    fun block(): String? {
        val f = file ?: return null
        val s = synchronized(lock) { read(f) }
        return LexiconMath.block(s.call, s.avoid, s.apps, s.fixes)
    }

    // ---- 记忆库:看 / 加 / 改 / 删(2026-10-08 他点名要的) ----
    //
    // 他原话:「可以随时删改它的记忆库 …… 本质上是能有一个训练它的本地入口」。
    // 上面那三个入口(observe / noteApp / correct)是**她自己学**;这一段是他**手工教**。
    //
    // ★★ 所有手工编辑**只有一条写入口** = [edit]:读一份 → 改 → 整份写回。
    //    每条各自 read-modify-write 才会出「改完 A 把 B 覆盖回去」那种账,
    //    而这个文件写错的表现是「她突然开始胡说他是谁」——**不崩、不报错**,最难查。
    //
    // ★★ 「加进 call」必须同时从 avoid 划掉,反之亦然 —— 和 [observe] 里同一条规则。
    //    两边都留着他一个名字,拼进提示词就成了「叫他小明」+「别叫他小明」,她只能瞎猜。
    //
    // ★★ 满了(in call 已 3 个 / fixes 已 30 条)**一律拒,不许悄悄挤掉最旧的那个**。
    //    悄悄丢一条的后果是「我明明教过她,她怎么忘了」,而他根本不会往这儿想。

    /** 界面看的那一份。null = 还没 [init] —— 调用方应当什么都不做,别去建文件。 */
    fun snapshot(): Store? = file?.let { f -> synchronized(lock) { read(f) } }

    // ★ 每个入口只做两件事:**清洗输入** + **交给 [LexiconMath] 里那条纯规则**。
    //   判断本身一条都不留在这一层 —— 那层能跑 JVM 单测,这层不能。

    /** 加一个「你可以这么叫我」。已经在里面 / 已经 3 个 / 太长 → false。 */
    fun addCall(raw: String): Boolean {
        val f = file ?: return false
        val n = LexiconMath.clean(raw, MAX_NAME_LEN) ?: return false
        return edit(f) { LexiconMath.addCall(it, n) }
    }

    /** 删掉一个允许的叫法。传 UI 上原样那个字 —— **不做清洗**,否则存进去的怪东西删不掉。 */
    fun removeCall(name: String): Boolean {
        val f = file ?: return false
        return edit(f) { LexiconMath.removeCall(it, name) }
    }

    /** 加一个「别这么叫我」。已经在里面 / 满了 / 太长 → false。 */
    fun addAvoid(raw: String): Boolean {
        val f = file ?: return false
        val n = LexiconMath.clean(raw, MAX_NAME_LEN) ?: return false
        return edit(f) { LexiconMath.addAvoid(it, n) }
    }

    fun removeAvoid(name: String): Boolean {
        val f = file ?: return false
        return edit(f) { LexiconMath.removeAvoid(it, name) }
    }

    /**
     * 改一条「他常让你开的应用」。
     *
     * ★ 次数是**她数出来的**,不是他编的 —— 但允许他改,因为那是他的账本:
     *   记歪了(比如「微信」被记成 8 次)他能自己抹平,不用等我改代码。
     * `times <= 0` = 删掉这一条。
     */
    fun setApp(raw: String, times: Int): Boolean {
        val f = file ?: return false
        val n = LexiconMath.clean(raw, MAX_NAME_LEN) ?: return false
        return edit(f) { LexiconMath.setApp(it, n, times) }
    }

    /**
     * 加/改一条同音字纠正。`bad` 已经在 → **改它指向谁**。
     *
     * ★ 校验用 [LexiconMath.clean] 而**不是** `looksLikeName`:`bad` 常常就是一句
     *   识别错的怪话(「威信」这种),拿名字那套规则去挡,只会把要修的东西挡在门外。
     */
    fun putFix(rawBad: String, rawGood: String): Boolean {
        val f = file ?: return false
        val bad = LexiconMath.clean(rawBad, MAX_FIX_LEN) ?: return false
        val good = LexiconMath.clean(rawGood, MAX_FIX_LEN) ?: return false
        return edit(f) { LexiconMath.putFix(it, bad, good) }
    }

    fun removeFix(bad: String): Boolean {
        val f = file ?: return false
        return edit(f) { LexiconMath.removeFix(it, bad) }
    }

    /**
     * ★ 唯一的写入口。`change` 回 null = 「这条改动不该发生」(重复 / 满了 / 没这条),
     * 那就**一个字都不写盘**;回来的和原来一样也算没改(省一次闪存写)。
     */
    private fun edit(f: File, change: (Store) -> Store?): Boolean = synchronized(lock) {
        val before = read(f)
        val after = change(before) ?: return false
        if (after == before) return false
        write(f, after)
        true
    }

    // ---- 落盘(原子写,和 MoodStore / ExperienceStore 同一套) ----

    private fun read(f: File): Store = try {
        val o = JSONObject(f.readText())
        Store(
            call = o.optJSONArray("call").toStringList(),
            avoid = o.optJSONArray("avoid").toStringList(),
            apps = o.optJSONObject("apps").toStringIntMap(),
            fixes = o.optJSONObject("fixes").toStringMap(),
        )
    } catch (_: Exception) {
        Store()      // 坏了就重开一页,不抛
    }

    private fun write(f: File, s: Store) {
        try {
            val apps = JSONObject()
            s.apps.forEach { (k, v) -> apps.put(k, v) }
            val fixes = JSONObject()
            s.fixes.forEach { (k, v) -> fixes.put(k, v) }
            val o = JSONObject()
                .put("call", JSONArray(s.call))
                .put("avoid", JSONArray(s.avoid))
                .put("apps", apps)
                .put("fixes", fixes)
                .put("savedAt", System.currentTimeMillis() / 1000)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())   // Windows 上偶发 rename 不过,兜一手
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        val a = this ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } }
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        val o = this ?: return emptyMap()
        val m = LinkedHashMap<String, String>()
        o.keys().forEach { k -> m[k] = o.optString(k) }
        return m
    }

    private fun JSONObject?.toStringIntMap(): Map<String, Int> {
        val o = this ?: return emptyMap()
        val m = LinkedHashMap<String, Int>()
        o.keys().forEach { k -> m[k] = o.optInt(k) }
        return m
    }
}

/**
 * 记忆的纯规则。**没有 Android / org.json 依赖** —— 这层写错的表现是
 * 「她突然开始胡说他是谁」,而不是崩溃,最需要测试钉住。
 */
internal object LexiconMath {

    /** 一句话里能抽出来的东西。三样都可能为空。 */
    data class Facts(
        val call: List<String> = emptyList(),
        val avoid: List<String> = emptyList(),
        val fixes: Map<String, String> = emptyMap(),
    )

    /**
     * 名字的守门人。**这是整个抽取里最要紧的一行**:
     * 「我叫你别乱动」也含「我叫」,但「你别乱动」显然不是名字 ——
     * 宁可漏掉一个真名字,也不能让她管他叫「你别乱动」。
     */
    private val NOT_IN_NAME = listOf(
        "你", "我", "他", "她", "别", "不", "没", "吗", "呢", "吧", "啊", "呀",
        "的", "了", "要", "会", "能", "想", "是", "把", "给", "和", "就",
    )

    internal fun looksLikeName(s: String): Boolean =
        s.isNotBlank() && s.length <= 8 && NOT_IN_NAME.none { s.contains(it) }

    /**
     * 他自己在记忆库里手打的一条 —— 只做**去空白 + 限长**,回 null = 这条不能收。
     *
     * ★★ 故意**不套 [looksLikeName]**。那一套是用来从**一整句话**里猜的,防的是
     *    「我妈叫我吃饭」把「吃饭」记成名字;而他手打的那几个字**是他本人的定义**,
     *    再拿猜话的那套去挡,只会把正当的东西挡在门外(「别叫我老李头」这种)。
     *    **猜话要严,他说的要松** —— 两边搞反了,这个功能就变成了一个「点了没反应」的按钮。
     */
    internal fun clean(raw: String, maxLen: Int): String? {
        val s = raw.trim()
        return if (s.isEmpty() || s.length > maxLen) null else s
    }

    // ---- 记忆库手工编辑(2026-10-08)----
    //
    // ★ 一律**回 null = 这改动不该发生**(重复 / 满了 / 压根没这条),调用方一个字都不落盘。
    //   把这些判据放在这儿而不是 `UserLexicon` 里,是因为它们必须能被 JVM 单测钉住 ——
    //   判错了在真机上的表现是「她开始胡说他是谁」或「我明明教过她,她怎么忘了」,
    //   **不崩、不报错**,事后根本查不出来。

    internal fun addCall(s: UserLexicon.Store, n: String): UserLexicon.Store? = when {
        n in s.call -> null                                    // 已经有了
        s.call.size >= UserLexicon.MAX_CALL -> null            // ★ 满了就拒,不挤掉最旧的那个
        // ★ 和 observe 同一条:两个名单不能同时留一个名字,
        //   否则提示词里就成了「叫他小明」+「别叫他小明」,她只能瞎猜。
        else -> s.copy(call = s.call + n, avoid = s.avoid.filterNot { it == n })
    }

    internal fun removeCall(s: UserLexicon.Store, name: String): UserLexicon.Store? =
        if (name in s.call) s.copy(call = s.call.filterNot { it == name }) else null

    internal fun addAvoid(s: UserLexicon.Store, n: String): UserLexicon.Store? = when {
        n in s.avoid -> null
        s.avoid.size >= UserLexicon.MAX_CALL -> null
        else -> s.copy(avoid = s.avoid + n, call = s.call.filterNot { it == n })
    }

    internal fun removeAvoid(s: UserLexicon.Store, name: String): UserLexicon.Store? =
        if (name in s.avoid) s.copy(avoid = s.avoid.filterNot { it == name }) else null

    internal fun setApp(s: UserLexicon.Store, n: String, times: Int): UserLexicon.Store? {
        val apps = LinkedHashMap(s.apps)
        if (times <= 0) apps.remove(n) else apps[n] = times
        return when {
            apps == s.apps -> null
            apps.size > UserLexicon.MAX_APPS -> null           // 满了:先删一条,或者改现成的
            else -> s.copy(apps = apps)
        }
    }

    internal fun putFix(s: UserLexicon.Store, bad: String, good: String): UserLexicon.Store? {
        // 自己指自己 = 白学一条,而且让 correct() 空转(它每轮都要过一遍全部映射)
        if (bad == good) return null
        val fixes = LinkedHashMap(s.fixes)
        if (fixes[bad] == good) return null                    // 一模一样,没什么可改
        if (bad !in fixes && fixes.size >= UserLexicon.MAX_FIXES) return null
        fixes[bad] = good
        return s.copy(fixes = fixes)
    }

    internal fun removeFix(s: UserLexicon.Store, bad: String): UserLexicon.Store? =
        if (bad in s.fixes) s.copy(fixes = s.fixes.filterKeys { it != bad }) else null

    // 「叫我小明」「以后叫我小明」「你可以叫我小明」「我叫小明」「就叫我老王」
    //
    // ★ 这一条踩过两个坑,每个都对应一种「她开始胡说他是谁」:
    //
    //  1. **不能只在句首认「我叫」。** 那会让「你可以叫我老王」整个漏掉 ——
    //     「可以」前面是「你」,不是分隔符,正则从那里起不了头。可这偏偏是最自然的说法。
    //  2. **也不能放任句中的「叫我」。** 「我妈叫我吃饭」会把「吃饭」记成名字,
    //     从此她一本正经管他叫「吃饭」—— 教学费的是他。
    //
    // 所以开头的那个词法位置是**三选一**:句首/分隔符之后,或者前面正好缀着
    // 「可以 / 以后 / 之后 / 就 / 请 / 都 / 得」这类**明确的定义口气**。
    // 「我妈叫我吃饭」的「叫我」前是「妈」,三个都不沾 → 不收。
    // 宁可漏掉几个真名字,也不能收进来一个「吃饭」。
    private val CALL = Regex(
        """(?:^|[\s,，。.！!?？;；:：]|可以|以后|之后|就|请|都|得)(?:以后|之后|可以|就|请)?(?:叫我|我叫)\s*([^\s,，。.!！?？;；]{1,8})"""
    )
    // 「别叫我老板」「不要叫我老板」「不许叫我老板」
    private val AVOID = Regex("""(?:别|不要|不许|不准)叫我\s*([^\s,，。.!！?？;；]{1,8})""")
    // 「不是威信,是微信」「不叫王老师,叫王哥」—— 一条纠正 = 一个称呼 + 一条同音字映射
    private val FIX = Regex(
        """不(?:是|叫)\s*([^\s,，。.!！?？;；]{1,12})\s*[,，、]?\s*(?:是|叫)\s*([^\s,，。.!！?？;；]{1,12})"""
    )

    internal fun extract(text: String): Facts {
        val avoid = AVOID.findAll(text).map { it.groupValues[1] }.filter { looksLikeName(it) }.toList()
        val call = CALL.findAll(text).map { it.groupValues[1] }
            .filter { looksLikeName(it) }
            .filterNot { it in avoid }
            .toList()
        val fixes = LinkedHashMap<String, String>()
        FIX.findAll(text).forEach { m ->
            val bad = m.groupValues[1]
            val good = m.groupValues[2]
            // bad/good 完全一样,或 good 本身就是个禁词,都不收 —— 学坏一条比少学一条糟
            if (bad != good && good.isNotBlank() && looksLikeName(good)) fixes[bad] = good
        }
        return Facts(call.distinct(), avoid.distinct(), fixes)
    }

    /**
     * 同音字替换。**一次过,不链式** —— 换完的句子不会再被后面的规则扫第二遍。
     *
     * 为什么强调这个:逐个 `replace` 看起来一样,其实不 —— A→B、B→C 同时存在时,
     * 逐个替换会把「A」一路换成「C」,而他**从来没这么纠正过**。规则得可预测:
     * 一次纠正只走一步。(顺带也没有「A→B、B→A 互相弹」这类来回。)
     *
     * 长词优先:「微信号」不会先被「微信」切一刀,再拿剩下的去匹配别的。
     */
    internal fun correct(text: String, fixes: Map<String, String>): String {
        val keys = fixes.keys.filter { it.isNotEmpty() && fixes[it] != it }
            .sortedByDescending { it.length }
        if (keys.isEmpty()) return text
        val re = Regex(keys.joinToString("|") { Regex.escape(it) })
        return re.replace(text) { m -> fixes[m.value] ?: m.value }
    }

    /** 拼提示词的那块。什么都没有 → null(不许输出一个空的「我记得:」吓人)。 */
    internal fun block(
        call: List<String>,
        avoid: List<String>,
        apps: Map<String, Int>,
        fixes: Map<String, String>,
    ): String? {
        val lines = mutableListOf<String>()
        if (call.isNotEmpty()) lines += "他让你叫他:${call.joinToString("、")}。"
        if (avoid.isNotEmpty()) lines += "他明确说过**别**这么叫他:${avoid.joinToString("、")}。"
        val top = apps.entries.sortedByDescending { it.value }.take(5)
        if (top.isNotEmpty()) {
            lines += "他常让你开的应用:" + top.joinToString("、") { "${it.key}(${it.value}次)" } + "。"
        }
        if (fixes.isNotEmpty()) {
            val sample = fixes.entries.take(6).joinToString("、") { "「${it.key}」指「${it.value}」" }
            lines += "他纠正过的说法:$sample。"
        }
        if (lines.isEmpty()) return null
        return """【你记得的关于他的事 —— 这是**以前攒下来的**,不是他这一轮说的】
${lines.joinToString("\n")}
自然地用(叫他、听懂他的话),但别像在念档案,也别提「我记得数据库里有」这种话。
没把握的宁可不用 —— 记错了比不记得更让他失望。"""
    }
}
