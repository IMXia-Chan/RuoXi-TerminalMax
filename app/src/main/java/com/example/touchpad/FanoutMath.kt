package com.example.touchpad

/**
 * 扇出（一次把同一件事派给好几个角色）里**不需要模型**的那部分：拆单、对账、挑分歧。
 *
 * ## 为什么对账不交给模型
 *
 * 再调一次云端来「汇总一下」，等于：多花一次钱、多等十几秒、
 * 还要赌它不会把三份互相矛盾的东西写成一份自洽的结论。
 * 而「两句话说的是不是同一件事」是**字符统计**就做得完的事 —— 做得完就不该外包。
 *
 * ## 它给出什么
 *
 * - **相同点**：至少两个角色各自说了、而且说法对得上 —— 这部分可以当依据用。
 * - **分歧点**：只有某一个角色说过 —— 能当线索，不能当结论。
 *
 * ★ 拿不准的一律往「分歧」那边放。把分歧误报成共识，比把共识误报成分歧贵得多：
 *   前者会把一个角色编的东西变成三个角色「都同意」的东西。
 */
object FanoutMath {

    /**
     * 一次最多派几个角色。
     *
     * ★ 2026-11-06 他：「分工，需要自动识别并匹配最适应的 agent（最高 5-8 个 agent 干活）」。
     *   从 4 抬到 8。**不是**鼓励每次拆八份 —— 提示词里仍然写着「小事就拆两份」；
     *   抬高的只是天花板，让真正大的那件事不必被迫合并。
     * ★ 真并发由 [AiAgent.MAX_PARALLEL] 和全进程的云端闸各管一道，这里只管「最多收几行」。
     */
    const val MAX_ROLES = 8

    /**
     * 角色名最多几个字 —— 它要出现在回执的每一行开头，太长会把版面挤乱。
     *
     * ★★ 2026-11-06 从 8 抬到 12，**和 [AgentProfileMath.NAME_MAX] 对齐**。
     *   这两个数一旦不一致，就会出现一种看不见的错：拆活时照抄了在册 agent 的名字，
     *   名字超过 8 个字在这儿被截掉，到了 [AgentProfileMath.idOf] 就不是同一个人了 ——
     *   于是「交给那个人」变成「交给一个同名同姓的陌生人」，而且不报任何错。
     */
    const val MAX_ROLE_CHARS = 12

    /**
     * 两句话算「同一个意思」的门槛（字符二元组的 Dice 系数）。
     *
     * ★ 用 Dice 不是 Jaccard：中文换个说法时两段长度差得远，
     *   Jaccard 的分母把「多出来的那半句」也算进去，同一件事会被判成两件事。
     *   实测「这样改完大概能省三成时间」对「改完之后时间大约省三成」：
     *   Jaccard 0.24（判反）、Dice 0.38（判对）。
     */
    const val SAME = 0.30

    /** 回执里每条观点最多留几个字。 */
    const val SNIPPET = 90

    /**
     * 一份活。
     *
     * @param need 这一份**需要什么**（[RouteMath.TEXT] / [RouteMath.VISION] / [RouteMath.CODE]）。
     *   拆活时由云端自己标，写在那一行的第三列；没写就是文字。
     *   ★ 它决定这一份派给哪一家模型 —— 见 [RouteMath.pick]。
     */
    data class Spec(val role: String, val task: String, val need: String = RouteMath.TEXT)

    data class Parsed(val specs: List<Spec>, val problems: List<String>)

    data class Answer(val role: String, val text: String)

    data class Agreed(val text: String, val roles: List<String>)

    data class Differed(val role: String, val text: String)

    data class Recon(
        val agree: List<Agreed>,
        val differ: List<Differed>,
        /** 真的回来了内容的份数。 */
        val answered: Int,
        /** 回来了但一个字都没有的份数。 */
        val blank: Int,
    )

    /**
     * 把 `["算|粗算这三步能省多少", "挑刺|……"]` 拆成角色和任务。
     *
     * ★ 认 `|` 和全角 `｜` 两种竖线 —— 小模型十次里总有两三次打全角。
     * ★ 少了竖线**不判废**：整句当任务，角色记「助手」。为了一条丑名字把整次扇出扔掉，
     *   代价差得太远。
     * ★ 去重按角色名：同名两条只留第一条，并在 problems 里说一声。
     */
    fun parse(raw: List<String>?, purposes: List<Pair<String, String>> = RouteMath.PURPOSES): Parsed {
        val specs = ArrayList<Spec>()
        val problems = ArrayList<String>()
        val seen = HashSet<String>()
        for (line in raw.orEmpty()) {
            val s = line.trim().trim('-', '*', '·', '•', '#', ' ', '\t')
            if (s.isEmpty()) continue
            var role = ""
            var task = s
            var need = RouteMath.TEXT
            if (s.any { it == '|' || it == '｜' }) {
                // ── 老写法 `名字|任务[|需要]`。一个字都没动：模型的输出走的就是这一条。 ──
                val parts = s.split('|', '｜').map { it.trim() }
                role = if (parts.size >= 2) parts[0] else ""
                // ── 第三列是「这一份需要什么」（文字/看图/代码）──
                // ★ 只认**那几个词**：认不出来就是任务正文的一部分，不能吞掉。
                //   写「把这张图|看」这种任务时，最后一个竖线后面的字是真内容。
                var body = if (parts.size >= 2) parts.drop(1) else listOf(s)
                if (body.size >= 2 && RouteMath.isNeedWord(body.last(), purposes)) {
                    need = RouteMath.needOf(body.last(), purposes)
                    body = body.dropLast(1)
                }
                task = body.joinToString("|").trim()
            } else {
                // ── 新写法 `名字/任务`（[splitItems] 那条路）。
                // ★★ 2026-11-06 他的原话：「能不能不要用那个竖线一样的符号，而是用；当结束，/当隔开」——
                //   竖线在手机键盘上要翻页才打得出来，斜杠和分号不用。
                // ★ 只在**没有竖线**时才把 `/` 当分隔符：模型那边照旧用竖线，
                //   它写的任务里带不带斜杠都不可能被抢。
                // ★ 开头那一截带冒号的**不切**：`http://…` 和「注意：…」不是名字。
                val slash = s.indexOfFirst { it == '/' || it == '／' }
                val who = if (slash < 0) "" else s.substring(0, slash).trim()
                val isName = slash >= 0 && who.isNotEmpty() &&
                    !who.contains(':') && !who.contains('：')
                if (isName) {
                    role = who
                    var body = s.substring(slash + 1).trim()
                    // 末尾那段如果是「看图/代码」这种字，当第三列
                    val last = body.lastIndexOfAny(charArrayOf('/', '／'))
                    if (last > 0) {
                        val tail = body.substring(last + 1).trim()
                        if (RouteMath.isNeedWord(tail, purposes)) {
                            need = RouteMath.needOf(tail, purposes)
                            body = body.substring(0, last).trim()
                        }
                    }
                    task = body
                }
            }
            if (role.isEmpty()) role = "助手"
            if (role.length > MAX_ROLE_CHARS) role = role.take(MAX_ROLE_CHARS)
            if (task.isEmpty()) {
                problems.add("「$role」那一条没写要做什么，跳过了")
                continue
            }
            if (!seen.add(role)) {
                problems.add("角色「$role」重名，后面那条没用上")
                continue
            }
            if (specs.size >= MAX_ROLES) {
                problems.add("超过 $MAX_ROLES 个角色，多出来的没派")
                break
            }
            specs.add(Spec(role, task, need))
        }
        if (specs.size < 2) problems.add("至少要有两个角色才算扇出")
        return Parsed(specs, problems)
    }

    /**
     * 那颗钮弹出来的框里写的一坨 → 一条一条。
     *
     * ★★ 2026-11-06 他的原话：「能不能不要用那个竖线一样的符号，而是用；当结束，/当隔开」。
     *   所以这个框里现在认三种写法，混着写也行：
     *
     * ```
     * 小研/查现在市面上有哪几家；agent2/查这几种各要多少钱；agent3/查有没有人踩过坑
     * ```
     *
     * ★ **换行永远断开**；`；` 只在**后面那一截像一条新活**（带 `/` 或 `|`）时才断开。
     *   不然「查价格；顺便问问口碑」这种正文会被切掉一半 —— 正文里本来就有分号。
     *   名字后面不写作用的，一行一个（`；` 分不出来那是名字还是正文）。
     */
    fun splitItems(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() {
            val t = cur.toString().trim()
            cur.setLength(0)
            if (t.isNotEmpty()) out.add(t)
        }
        for (rawLine in text.split('\n')) {
            val chunks = rawLine.split('；', ';')
            for ((i, c) in chunks.withIndex()) {
                val piece = c.trim()
                if (piece.isEmpty()) continue
                val fresh = piece.any { it == '|' || it == '｜' || it == '/' || it == '／' }
                if (i > 0 && fresh) {
                    flush()
                    cur.append(piece)
                } else if (i > 0) {
                    // 回到正文：分号原样还给它
                    cur.append('；').append(piece)
                } else {
                    cur.append(piece)
                }
            }
            flush()
        }
        return out
    }

    data class Remap(val specs: List<Spec>, val problems: List<String>)

    /**
     * 把拆出来的岗位名**对准在册的 agent**。
     *
     * ★★ 2026-11-06 他：「分工，需要自动识别并匹配最适应的 agent」。
     *   提示词里已经给了名单并写明「照抄」，但小模型十次里总有几次写成
     *   近义词、少一个字、或者换个大小写。名字差一个字符，[AgentProfileMath.idOf]
     *   算出来就是另一个人 —— 那一份就交给了临时助手，那个人自己的记忆也用不上。
     *   所以在**解析之后、派发之前**再对一次，认不准的（[AgentProfileMath.matchName]
     *   要求唯一命中）一律不动。
     *
     * ★ 认成同一个人之后要**去重**：两个岗位名都指向同一个 agent 的，
     *   只留前一份。否则同一个人会被同时派两次、答案对着打。
     */
    fun remap(specs: List<Spec>, names: List<String>): Remap {
        if (names.isEmpty()) return Remap(specs, emptyList())
        val out = ArrayList<Spec>(specs.size)
        val problems = ArrayList<String>()
        val taken = HashSet<String>()
        for (s in specs) {
            val hit = AgentProfileMath.matchName(s.role, names)
            if (hit == null) {
                out.add(s)
                continue
            }
            if (!taken.add(hit)) {
                problems.add("「${s.role}」和前面那一份都指向 ${hit}，这一条没派")
                continue
            }
            out.add(s.copy(role = hit))
        }
        return Remap(out, problems)
    }

    /** 两个文本有多像：0（毫无共同）到 1（完全一样）。只看字符二元组，不看语序。 */
    fun similarity(a: String, b: String): Double {
        val x = bigrams(a)
        val y = bigrams(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        var inter = 0
        for (t in x) if (t in y) inter++
        return 2.0 * inter / (x.size + y.size).toDouble()
    }

    /**
     * 对账。
     *
     * 每一句单独看：只要**另一个角色**也有某句跟它像到 [SAME]，这句就算「被印证」；
     * 一个都没有的，记到这个角色名下当分歧。
     */
    fun reconcile(answers: List<Answer>): Recon {
        val live = answers.filter { it.text.isNotBlank() }
        val blank = answers.size - live.size
        if (live.size < 2) {
            // 只有一份（或零份）时没有「对账」可言：全部按分歧原样交回去，
            // 由中枢自己判断。★ 这时候说「一致」是在骗人。
            val differ = live.flatMap { a ->
                sentences(a.text).map { Differed(a.role, it) }
            }
            return Recon(emptyList(), differ, live.size, blank)
        }
        // 先切成句子，算一次就够了（句子数 × 句子数，几十乘几十）。
        val sents = live.map { sentences(it.text) }
        val agree = ArrayList<Agreed>()
        val differ = ArrayList<Differed>()
        for (i in live.indices) {
            for (s in sents[i]) {
                val who = ArrayList<String>()
                for (j in live.indices) {
                    if (i == j) continue
                    if (sents[j].any { similarity(s, it) >= SAME }) who.add(live[j].role)
                }
                if (who.isEmpty()) {
                    differ.add(Differed(live[i].role, s))
                } else {
                    // 同一个意思可能好几个人各说一遍 —— 并进同一条，别重复列。
                    val hit = agree.firstOrNull { similarity(it.text, s) >= SAME }
                    if (hit == null) {
                        agree.add(Agreed(s, (who + live[i].role).distinct()))
                    } else {
                        val idx = agree.indexOf(hit)
                        agree[idx] = hit.copy(roles = (hit.roles + who + live[i].role).distinct())
                    }
                }
            }
        }
        return Recon(agree, differ, live.size, blank)
    }

    /**
     * 对账结果压成回执里那几行。
     *
     * ★ 版面固定：「相同」在前、「分歧」在后。中枢要能一眼看出哪些能直接用、
     *   哪些得自己拿主意，而不是读一段散文再自己分。
     */
    fun render(recon: Recon, budget: Int = 1_100): String {
        val sb = StringBuilder()
        sb.append("【对账】回来了 ").append(recon.answered).append(" 份")
        if (recon.blank > 0) sb.append("（另有 ").append(recon.blank).append(" 份是空的）")
        sb.append('\n')
        if (recon.answered < 2) {
            sb.append("只有一份，没得对账 —— 下面这些都是它一家之言。\n")
        }
        val head = sb.length
        if (recon.agree.isNotEmpty()) {
            sb.append("相同（至少两个角色都这么说，可以直接当依据）：\n")
            for ((i, a) in recon.agree.take(5).withIndex()) {
                sb.append(" ").append(i + 1).append(". ").append(cut(a.text))
                    .append("（").append(a.roles.joinToString("、")).append("）\n")
            }
        }
        if (recon.differ.isNotEmpty()) {
            sb.append("分歧（只有一家这么说，别直接当结论）：\n")
            for (d in recon.differ.take(6)) {
                sb.append(" · ").append(d.role).append("：").append(cut(d.text)).append('\n')
            }
        }
        val out = sb.toString()
        if (out.length <= budget) return out
        // 超预算就硬截 —— 但**保留标题那一行**，不然中枢不知道这段是什么。
        return out.take(head) + out.substring(head).take(budget - head - 20) + "…（后面截掉）\n"
    }

    private fun cut(s: String): String =
        if (s.length <= SNIPPET) s else s.take(SNIPPET) + "…"

    private fun bigrams(s: String): Set<String> {
        val n = StringBuilder(s.length)
        for (c in s) {
            // 只留文字和数字：标点、空白、换成全角的符号都不参与比较。
            if (c.isLetterOrDigit()) n.append(c.lowercaseChar())
        }
        val t = n.toString()
        if (t.isEmpty()) return emptySet()
        if (t.length == 1) return setOf(t)
        val out = HashSet<String>(t.length)
        for (i in 0 until t.length - 1) out.add(t.substring(i, i + 2))
        return out
    }

    /**
     * 切句。
     *
     * ★ 不切小数点：`3.5` 里的 `.` 一断，「3」和「5」就成了两个短句，
     *   而这两个短句会被当成两个独立观点送进对账。
     * ★ 太短的不算观点（「好」「是的」「可以」）—— 它们会在每一份里都出现，
     *   被当成「三个人都同意」的垃圾共识。
     */
    private fun sentences(text: String): List<String> =
        text.split('。', '！', '？', '!', '?', '；', ';', '\n')
            .map { it.trim().trim('-', '*', '·', '•', '#', ' ', '\t') }
            .filter { it.length >= MIN_SENTENCE }
            .map { if (it.length <= SNIPPET * 3) it else it.take(SNIPPET * 3) }

    /** 短于这个数的话不算「一个观点」。 */
    private const val MIN_SENTENCE = 6
}
