package com.example.touchpad

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 经验库:「这种界面该怎么找」的随身手册。
 *
 * ## 它解决的是什么
 *
 * 电脑端找不到东西时,现在会回一份**观察**(结构,不含屏幕内容),比如
 * `{kind:"blind", window:"微信", ocr_lines:36}` —— 「这界面认得出字,但没有目标」。
 *
 * 观察只是材料,还得有人回答「那接下来怎么办」。这件事分两半:
 *   - **策略**(用哪个动词)没有确定答案,代码穷举不完,该交给见多识广的一方;
 *   - **机制**(坐标、边界、红线)有确定答案,必须由代码兜死。
 *
 * 这个类就是策略那一半的**快版**:会的东西记下来,下次直接查表。查比想可靠得多 ——
 * 尤其对手机上的 4B,让它「想」出「先 Ctrl+F 再输名字」十次有八次想不出来,
 * 但让它「查」一条已经写好的策略,是查表,不会错。
 *
 * 慢版是微调(见 memory: ruoxi-training-roadmap),要攒够样本再说,不在这一轮。
 *
 * ## 置信度:用出来的,不是存下来的
 *
 * 老师(云端大模型)会教错。**一次存进去就永远照做是危险的**,所以每条经验带计数,
 * 「执行结果」本身就是标注:
 *
 *     置信度 = (wins + 1) / (uses + 2)      // 拉普拉斯平滑,一次没用过 = 0.5
 *
 * - 预置教材(seed)是人写的、已被验证 → 初始 uses=10/wins=9,置信度 ≈ 0.83;
 * - 老师教的(teacher)→ 初始 0/wins=0,置信度 0.5,**必须真用成功几次才升**。
 *
 * 于是有了自我纠正:预置教材在某个应用上不灵 → 失败记一笔 → 置信度往下掉 →
 * 掉到老师那条下面,下次就换它先上。**不需要任何手工调参**。
 *
 * 两条硬规矩:
 * 1. **置信度只决定「先试哪个」,永不决定「能不能做」。** 安全边界(点击不越界、
 *    type 要确认、破坏性操作要点名)永远是代码硬拦 —— 低置信度不能让它放行任何东西。
 * 2. **不采信老师自报的置信度。** llm 自报数字会编,只认实际用出来的胜率。
 *
 * 同 kind 下可以有多条经验共存、按置信度竞争 —— 这顺手就实现了「窗口级精化」
 * (微信→search 上位、某个列表→scroll 上位),不必手工加 key 粒度。
 */
object ExperienceStore {

    /**
     * 允许出现的动词。**封闭集合** —— 老师也只能从这里挑,出了圈的一律丢掉。
     *
     * 故意做得这么窄:网络那头是个会自由发挥的模型,而这边是**要真动手的代码**。
     * 让它能凭空说一个动词出来,就等于把「能干什么」的决定权交给了一段无法检验的文本。
     */
    val VERBS = listOf("scroll", "search", "visual")

    /**
     * 同一个 kind 下最多留几条,多了**淘汰**置信度最低的那条(平手时留老的)。
     *
     * ★★ 2026-10-08 从 `private` 改成 `internal`:记忆库那一页要**把这个数说出来**。
     *   加第 9 条时它是**悄悄挤掉**一条旧的(不是拒收),这条规矩要是界面上不说,
     *   他的感觉就是「我明明加了一条,怎么少了一条」—— 而他绝不会往这儿想。
     *   [UserLexicon] 那几个上限做成 `internal` 是同一个理由(界面要报「最多记 3 个」)。
     */
    internal const val MAX_PER_KIND = 8

    /**
     * 一条做法最多几步。★ 和 [MAX_PER_KIND] 同一个理由做成 `internal`:
     * 记忆库那一页要**拦住他点第五个**,并且把「最多 4 步」说出来。
     *
     * ★★ 不拦的后果是**静默截断** —— [parseVerbs] 末尾 `.take(MAX_VERBS)` 会一声不响地把
     *   第五步以后丢掉,而他看到的是「存好了」,回到清单才发现少了两步。
     */
    internal const val MAX_VERBS = 4

    data class Exp(
        val kind: String,
        val verbs: List<String>,
        val reason: String,
        // "seed" = 预置教材,"teacher" = 云端老师教的,"manual" = 他自己在记忆库里加的
        val source: String,
        var uses: Int,
        var wins: Int,
        /**
         * ★★ 「这条别再要了」—— 2026-10-08 他点名要的(见 [setEnabled] 那段)。
         *
         * false = 这条**永远不用**,而且老师**再教同样一条也不收**。
         * ★ **带默认值加在末尾**:测试里已经有六参的位置构造,加第七个默认参数不会弄红。
         */
        var enabled: Boolean = true,
    ) {
        /** 置信度 = 用出来的胜率(拉普拉斯平滑)。只影响顺序,不影响安全边界。 */
        val confidence: Double get() = (wins + 1.0) / (uses + 2.0)

        /** 同一个 kind 下靠它去重:同一套动词序列算同一条,反复教只会加计数、不会繁殖。 */
        val id: String get() = verbs.joinToString(">")

        fun toJson(): JSONObject = JSONObject()
            .put("kind", kind)
            .put("verbs", JSONArray(verbs))
            .put("reason", reason)
            .put("source", source)
            .put("uses", uses)
            .put("wins", wins)
            .put("enabled", enabled)
    }

    private var file: File? = null
    private val items = mutableListOf<Exp>()

    // ------------------------------------------------------------------

    fun init(ctx: Context) {
        val dir = File(ctx.filesDir, "experience")
        dir.mkdirs()
        val f = File(dir, "experiences.json")
        file = f
        synchronized(this) {
            items.clear()
            // ★★ 「删空 = 真的空」。 2026-10-08 他要在设置里能把经验库清干净。
            //
            //   原来这里写的是 `if (loaded.isEmpty()) 播种`,用的是**列表空不空**这个判据 ——
            //   于是他把条目一条条删光、下次一开机**那三条预置教材又自己冒出来**,
            //   而且不报任何错。他删的东西自己回来了,这正是他最恨的那种失败。
            //
            //   判据改成「**盘上有没有这份文件**」,两种情况分开:
            //     · 没有文件(刚装 / 刚清过 App 数据) → 播种。这是预置教材的本意。
            //     · 有文件、解析出来是空的        → 那就是他一条条删出来的,保持空。
            //     · 文件坏了(读/解析抛异常)      → **照旧播种** —— 那种情况下他并没有删过
            //       任何东西,而「坏文件退回预置教材」是原来就定下的规矩,别顺手改掉。
            //   ★ 注意 `null` 和 `emptyList()` 在这儿的区别**就是整个判据**,别把两者合并。
            val loaded: List<Exp>? = try {
                if (f.exists()) parse(f.readText(Charsets.UTF_8)) else null
            } catch (_: Exception) {
                null
            }
            if (loaded == null) {
                items.addAll(seed())
                save()
            } else {
                items.addAll(loaded)
            }
        }
    }

    /**
     * 预置教材。**冷启动就有东西可用** —— 没有云端 key、或第一次遇到某种界面时,
     * 「不知道」会退化成「什么也不做」,那是最差的结果;有几条常识性的顺序兜着,
     * 至少不会原地卡死。
     *
     * 初始 uses/wins 是**虚构的试用记录**(10 次 9 胜):它们表示「人写的、已验证」,
     * 置信度 0.83,高于老师初来时的 0.5。这不是作弊 —— 真实含义是「先用我的,
     * 除非我的在你这儿不灵」。不灵的话失败会计数,置信度自己会掉下去。
     */
    internal fun seed(): List<Exp> = listOf(
        Exp(
            "offscreen",
            listOf("scroll", "search", "visual"),
            "东西找到了,但它在屏幕外 —— 多半躺在可滚动区域的下面。" +
                "先滚一屏让它自己冒出来(滚是原地不动、最不打扰的试探);" +
                "滚不出来就用这个界面自己的搜索直接把它捞到眼前。",
            "seed", 10, 9
        ),
        Exp(
            "blind",
            listOf("search", "scroll", "visual"),
            "屏幕上有字,但没有目标那个词 —— 要么它埋在很深的列表里,要么这个界面是" +
                "自绘的(微信这一类),控件树压根不暴露。" +
                "两种情况都是**让应用自己去找**最快:打开它自己的搜索,把词喂进去。",
            "seed", 10, 9
        ),
        Exp(
            "empty",
            listOf("visual"),
            "一个字都没认出来 —— 这界面上的东西多半是图形、图标,不是文字。" +
                "按名字找和认字在这时候都是瞎的,只剩看图这条路。",
            "seed", 10, 8
        ),
    )

    // ------------------------------------------------------------------

    /**
     * 这个 kind 下所有可选的策略,**按置信度从高到低**。调用方依次试,成功即停。
     *
     * 返回列表而不是单条:第一条不灵时得有第二条顶上 —— 而「不灵」正是
     * 置信度排序该更新的信息。
     */
    @Synchronized
    fun candidates(kind: String): List<Exp> =
        items.filter { it.kind == kind && it.enabled }
            .sortedWith(compareByDescending<Exp> { it.confidence }.thenByDescending { it.uses })

    /**
     * 这个 kind 有没有经验可查(没有就得去问老师)。
     *
     * ★ 「被禁的那条不算有」—— 他按了「别再要了」之后,这个 kind 就该重新去问老师,
     * 而不是拿一条他明说不许用的打头。
     */
    @Synchronized
    fun has(kind: String): Boolean = items.any { it.kind == kind && it.enabled }

    /**
     * 老师教了一条。动词先过封闭集合这一关,出了圈的直接丢 —— 不能因为
     * 「是老师说的」就让它指挥代码干没见过的事。
     *
     * 已经有同一套动词序列就**不新增**,返回那条老的(反复教只会把它用起来)。
     *
     * ★★ 但**被他禁掉的那条是例外**:返回 null = **不收**。
     *   不然「老师教错了 → 他按『别再要了』→ 老师下次又说同样的话 → 它自己回来了」,
     *   而他不会知道是哪天回来的(见 [setEnabled])。
     */
    @Synchronized
    fun learn(kind: String, rawVerbs: List<String>, reason: String,
              source: String = "teacher"): Exp? {
        val verbs = normVerbs(rawVerbs) ?: return null

        items.firstOrNull { it.kind == kind && it.id == verbs.joinToString(">") }?.let {
            return if (it.enabled) it else null
        }

        val e = Exp(kind, verbs, reason, source, 0, 0)
        items.add(e)
        // 同 kind 太多了就淘汰置信度最低的那条(平手时留老的 —— 它至少有使用记录)。
        // ★ 只从**没被禁**的那些里挑:「别再要了」是按在他手上的,不参与自动淘汰。
        items.filter { it.kind == kind && it.enabled }
            .sortedWith(compareBy<Exp> { it.confidence }.thenBy { it.uses })
            .dropLast(MAX_PER_KIND)
            .forEach { items.remove(it) }
        save()
        return e
    }

    /**
     * 一条经验用完了,报账。**成功才算赢,失败也算用过** ——
     * 后者才是让不好的策略自己沉下去的那一半。
     */
    @Synchronized
    fun record(e: Exp, won: Boolean) {
        e.uses++
        if (won) e.wins++
        save()
    }

    /** 给设置界面看的一行字:库里有几条、其中最信得过的是什么。 */
    @Synchronized
    fun summary(): String {
        if (items.isEmpty()) return "还没有经验"
        val taught = items.count { it.source == "teacher" }
        val off = items.count { !it.enabled }
        val tail = if (off > 0) ",${off} 条你别再要了" else ""
        val best = items.filter { it.enabled }.maxByOrNull { it.confidence }
            ?: return "${items.size} 条全部都被你设成「别再要了」$tail"
        return "${items.size} 条(老师教了 $taught 条$tail)," +
            "最信得过的是「${best.kind}」→ ${best.verbs.joinToString(" → ")}" +
            "(%.0f%%,用过 ${best.uses} 次)".format(best.confidence * 100)
    }

    /** 全部条目,给设置界面列出明细。 */
    @Synchronized
    fun all(): List<Exp> = items.toList()

    // ------------------------------------------------------------------
    // ★★ 记忆库那一页要用的:看 / 删 / 改 / 加 / 禁(2026-10-08 他点名要的)
    //
    //   他原话大意:「我就能在她出现 bug、错误的时候教她」/「云端老师教错了,那我该咋办」。
    //   所以这几件的形状不是为了"管理数据",是为了**出错之后他能把那个错纠正掉** ——
    //   每一条的注释里都写清了它治的是哪种"她不听话"。一次只动一处,每件都能单独回退。
    // ------------------------------------------------------------------

    /**
     * 「这条别再要了」/「让它回来」。
     *
     * ★★ 为什么光「删掉」不够 —— 这是这一整套里最要紧的一句:
     *   删掉只是**这一份**没了。下次她碰上同样的界面,还是要去问云端老师;
     *   老师要是又说一遍同样的话,**它原样回来**,而且看不出是哪天回来的。
     *   禁掉 = 留着这条的记号,但**永不用它、也不许再教回来**。
     *   这条正是「老师教错了」的正解,删掉不是。
     */
    @Synchronized
    fun setEnabled(e: Exp, on: Boolean) {
        e.enabled = on
        save()
    }

    /** 删掉一条。★ 删了就真没了 —— [init] 不会再播种(见那里的注释)。 */
    @Synchronized
    fun remove(e: Exp): Boolean {
        val gone = items.remove(e)
        if (gone) save()
        return gone
    }

    /** 清空整个经验库。★ **清空之后不会自己长回来** —— 见 [init]。 */
    @Synchronized
    fun purge() {
        items.clear()
        save()
    }

    /**
     * 他亲手加的一条。和老师教的走**同一条路**(一样过封闭集合、一样去重、一样有上限),
     * 只是 source 记成 "manual" —— 这样以后看清单能分清"哪几条是我自己写的"。
     */
    fun add(kind: String, rawVerbs: String, reason: String): Exp? {
        val k = kind.trim()
        if (k.isEmpty()) return null
        val verbs = parseVerbs(rawVerbs) ?: return null
        return learn(k, verbs, reason, "manual")
    }

    /**
     * 改一条:动词链 + 理由。
     *
     * ★ 动词链是它的**身份**(见 [Exp.id])—— 改了链就等于换了一条,
     *   所以这是「替换这条」而不是「给它改个名」。使用记录(用过几次、成几次)原样跟着走:
     *   他改的是"该怎么做",不是"以前做得怎么样"。
     * ★ 新链要是和同 kind 下另一条撞了,**把撞的那条去掉**(两条一模一样的东西
     *   留着只会让清单变长,而且下次淘汰时它俩互相顶)。
     */
    @Synchronized
    fun update(e: Exp, rawVerbs: String, reason: String): Exp? {
        val verbs = parseVerbs(rawVerbs) ?: return null
        val i = items.indexOf(e)
        if (i < 0) return null
        val fresh = e.copy(verbs = verbs, reason = reason)
        items.firstOrNull { it !== e && it.kind == e.kind && it.id == fresh.id }
            ?.let { items.remove(it) }
        items[items.indexOf(e)] = fresh
        save()
        return fresh
    }

    /**
     * 这个 kind 下,**老师给过的那条**是不是被他设成「别再要了」了。
     *
     * ★ 它挡的是一趟**没必要花的云端调用**:老师是照「观察」教的,同样的观察就会教出
     *   同样一条;那条既然他不要,再去问一遍 = 花一次钱、再原样拒一次。
     * ★ 只看 `source == "teacher"` 的那些 —— **把预置教材禁掉不代表老师那条不要**,
     *   那种时候该照旧去问(可能是真的没别的办法了)。
     */
    @Synchronized
    fun teacherMuted(kind: String): Boolean =
        items.any { it.kind == kind && it.source == "teacher" && !it.enabled }

    /**
     * 这套动词链是不是**已经被他设成「别再要了」**。
     *
     * ★ 用处只有一个、但必须有:老师又教了一遍被禁的做法时,[learn] 会返回 null,
     *   而 null 也可能是「动词不在允许范围内」—— 那两句话**必须分开说**,
     *   否则回执会把「你不让它学」说成「老师教错了」。同一件事说错,比不说更坏。
     */
    @Synchronized
    fun isMuted(kind: String, rawVerbs: List<String>): Boolean {
        val id = normVerbs(rawVerbs)?.joinToString(">") ?: return false
        return items.any { it.kind == kind && it.id == id && !it.enabled }
    }

    /** 归一化一条动词链(小写 / 裁剪 / 过封闭集合 / 去重 / 限长)。空 ⇒ null。 */
    private fun normVerbs(raw: List<String>): List<String>? {
        val verbs = raw.map { it.trim().lowercase() }
            .filter { it in VERBS }
            .distinct()
            .take(MAX_VERBS)
        return verbs.ifEmpty { null }
    }

    /**
     * 把人打的那串动词拆成一条链。**有一个词不认就整体返回 null** ——
     * 不静默丢掉再照跑:否则他打了「search 关机」会得到一条只有 search 的经验,
     * 而他以为「关机」也进去了。**这个项目最恨的就是这种失败。**
     *
     * ★ 纯函数(不碰盘、不碰 items),所以 JVM 单测能直接钉它两个方向。
     */
    internal fun parseVerbs(raw: String): List<String>? {
        val parts = raw.split(' ', '\t', '\n', ',', '，', '、', '>', '→', '|')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        if (parts.any { it !in VERBS }) return null
        return parts.distinct().take(MAX_VERBS)
    }

    // ------------------------------------------------------------------

    private fun parse(text: String): List<Exp> {
        val arr = JSONObject(text).optJSONArray("items") ?: return emptyList()
        val out = mutableListOf<Exp>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // 用 AiAgent.jsonText 而不是 optString:JSON 里的 null 会被 optString 转成
            // 字面字符串 "null",于是「空值」悄悄变成一条叫 null 的动词(见 [AiAgent.jsonText])。
            val verbs = o.optJSONArray("verbs")?.let { a ->
                (0 until a.length()).mapNotNull { AiAgent.jsonText(a.opt(it))?.takeIf { s -> s.isNotEmpty() } }
            } ?: continue
            // 盘上存的也要过一遍封闭集合:文件是可以被手改的,
            // 而这条路径直通「真动手的代码」。别让一个手滑的字符串变成指令。
            val clean = verbs.filter { it in VERBS }.distinct()
            if (clean.isEmpty()) continue
            val kind = AiAgent.jsonText(o.opt("kind"))?.takeIf { it.isNotEmpty() } ?: continue
            out.add(
                Exp(
                    kind, clean,
                    o.optString("reason"), o.optString("source", "teacher"),
                    o.optInt("uses", 0).coerceAtLeast(0),
                    o.optInt("wins", 0).coerceAtLeast(0),
                    // ★ 缺这一格的老文件读出来是 true(没禁过)—— optBoolean 的默认值
                    //   就是为「老盘上的文件没有这个字段」准备的,别改成 false。
                    o.optBoolean("enabled", true),
                )
            )
        }
        return out
    }

    /** 先写临时文件再改名 —— 中途被杀不至于留下半截 JSON(那会让整库读不出来)。 */
    private fun save() {
        val f = file ?: return
        // ★★ 2026-11-08(体检):落盘失败**不能影响主流程**,但也不能一声不吭 ——
        //   原来这里是个空 catch,写不进去的时候「刚学到的一条经验」凭空消失。
        //   统一走 [AtomicWrite](`.tmp` + 原子换名),失败至少留一行日志。
        try {
            val root = JSONObject()
                .put("version", 1)
                .put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
            if (!AtomicWrite.replace(f, root.toString())) {
                Log.w("ExperienceStore", "经验库写不进去:${f.path}")
            }
        } catch (e: Exception) {
            // 落盘失败绝不能影响主流程(和 BehaviorLogger 同一条规矩)。
            Log.w("ExperienceStore", "经验库序列化失败", e)
        }
    }
}
