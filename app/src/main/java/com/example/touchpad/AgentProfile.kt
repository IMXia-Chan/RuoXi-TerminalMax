package com.example.touchpad

import org.json.JSONArray
import org.json.JSONObject

/**
 * **一个真正的 agent** —— 不是一次云端调用配一个角色名，而是一个有身份、有记忆、
 * 有目标、能自己决定下一步的长期实体。
 *
 * ★★ 2026-11-06 立的。在此之前工作台里的「多 agent」只是
 *   「把一段话派给云端 + 贴一个角色名」：做完就散，下一次同一个角色**什么都不记得**，
 *   也没有「这件事该由我推进到哪一步」的概念。他要的是后一种：
 *   每个 agent 有自己的名字和职责、自己的记忆本、自己的预算，认领一件活之后
 *   **自己多轮推进**，做完把结论写回自己的记忆。
 *
 * ## 四件事，缺一件就不是 agent
 *
 * | | 字段 | 缺了会怎样 |
 * |---|---|---|
 * | 身份 | [name] + [charter] | 它只是一段匿名 prompt，换个模板就换个人 |
 * | 记忆 | [notes] | 同一件事做第二遍还从零开始，等于没有 |
 * | 目标 | 认领的那件活（见 [AiAgent.runAgent]） | 它不知道该做到哪、什么时候算完 |
 * | 预算 | [budget] | 没人管的循环是烧电的，而这台机 40°C 就降频 |
 *
 * ## 记忆只记结论，不记过程
 *
 * 过程在 [AgentBus] 的账上，逐条可查。这里记的是「做完之后值得下一轮带着的东西」：
 * 结论、踩过的坑、被证伪的方向。记过程的代价是每轮请求里塞进一段没人看的流水账，
 * 而它会把「我做过这些」误当成「这些已经做完」。
 */
internal data class AgentNote(
    /** 一句话，最多 [AgentProfileMath.NOTE_MAX] 字。 */
    val text: String,
    val at: Long,
)

internal data class AgentProfile(
    /** 稳定 id，由名字算出（[AgentProfileMath.idOf]）。重名就是覆盖。 */
    val id: String,
    val name: String,
    /** 职责：他让这个 agent 负责什么、不负责什么。写人话，它会照着它判断该不该接一件活。 */
    val charter: String,
    /** 自己的记忆。新的在前。 */
    val notes: List<AgentNote> = emptyList(),
    /** 做过几件活。只用来在清单里排序。 */
    val runs: Int = 0,
    /** 一件活最多跑几步。到顶就收口，把现在到哪一步说清楚。 */
    val budget: Int = AgentProfileMath.DEFAULT_BUDGET,
    val createdAt: Long = 0,
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (n in notes) arr.put(JSONObject().put("text", n.text).put("at", n.at))
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("charter", charter)
            .put("notes", arr)
            .put("runs", runs)
            .put("budget", budget)
            .put("createdAt", createdAt)
    }

    companion object {
        /** 读坏一条就当没有 —— 一个 agent 的档案坏了，不该让整张名单消失。 */
        fun fromJson(o: JSONObject?): AgentProfile? {
            if (o == null) return null
            val id = o.optString("id").trim()
            if (id.isEmpty()) return null
            val notes = ArrayList<AgentNote>()
            val arr = o.optJSONArray("notes")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val n = arr.optJSONObject(i) ?: continue
                    val t = n.optString("text").trim()
                    if (t.isNotEmpty()) notes.add(AgentNote(t, n.optLong("at")))
                }
            }
            return AgentProfile(
                id = id,
                name = o.optString("name").trim().ifEmpty { id },
                charter = o.optString("charter"),
                notes = AgentProfileMath.fitNotes(notes),
                runs = o.optInt("runs"),
                budget = AgentProfileMath.fitBudget(o.optInt("budget")),
                createdAt = o.optLong("createdAt"),
            )
        }
    }
}

/**
 * 一个 agent 身上那些**纯算术**：名字怎么变 id、记忆怎么裁、预算怎么夹。
 *
 * ★ 单独抽出来是为了能上单测。这里的错**不崩**：记忆多留两条、预算多夹一格，
 *   屏上什么异常都看不见，只有「它怎么又从头开始」这种事后才反应得过来的症状。
 */
internal object AgentProfileMath {

    /** 一个 agent 自己记多少条。再多就不是「记得住」，是把账本搬进了提示词。 */
    const val MAX_NOTES = 20

    /** 一条记忆最多几个字。 */
    const val NOTE_MAX = 400

    /** 一件活的默认步数。和他的工作台一样：到顶就收口，别让它自己转。 */
    const val DEFAULT_BUDGET = 16

    /** 预算上限。再多，一次活就能烧掉一晚上的额度。 */
    const val MAX_BUDGET = 40

    /** 名字最长几个字。任务条和清单上都只有那么宽。 */
    const val NAME_MAX = 12

    fun fitBudget(n: Int): Int = when {
        n <= 0 -> DEFAULT_BUDGET
        n > MAX_BUDGET -> MAX_BUDGET
        else -> n
    }

    /** 名字能不能用。回执要能照着这句话改，所以写人话。 */
    fun nameProblem(name: String): String? = when {
        name.isBlank() -> "agent 得有名字，不然下次找不到它。"
        name.length > NAME_MAX -> "名字太长（最多 $NAME_MAX 个字）。"
        else -> null
    }

    /** 职责能不能用。没有职责的 agent 等于一段匿名 prompt。 */
    fun charterProblem(charter: String): String? =
        if (charter.isBlank()) "得写一句它负责什么 —— 没有职责的 agent 谁都能顶，也就谁都不算。" else null

    /** 名字 → id。判据和 [SkillMath.idOf] 同一套：中文原样留着，标点换下划线。 */
    fun idOf(name: String): String {
        val sb = StringBuilder()
        for (c in name.trim()) {
            when {
                c.isLetterOrDigit() -> sb.append(c.lowercaseChar())
                else -> sb.append('_')
            }
        }
        val s = sb.toString().trim('_')
        return if (s.isEmpty()) "agent" else s.take(40)
    }

    /**
     * 记一条。**同一条不重复记**，新的在前，超上限丢最老的。
     *
     * ★ 去重按整句比：同一个结论被它每轮写一遍，二十条里十九条是同一句 ——
     *   那本记忆等于没有，而且每轮都在白花上下文。
     */
    fun addNote(notes: List<AgentNote>, text: String, at: Long): List<AgentNote> {
        val t = text.trim().take(NOTE_MAX)
        if (t.isEmpty()) return notes
        val out = ArrayList<AgentNote>(notes.size + 1)
        out.add(AgentNote(t, at))
        for (n in notes) if (n.text != t) out.add(n)
        return fitNotes(out)
    }

    /** 超过上限就只留最新的那些。 */
    fun fitNotes(notes: List<AgentNote>): List<AgentNote> =
        if (notes.size <= MAX_NOTES) notes else notes.take(MAX_NOTES)

    /**
     * 记忆拼成提示词里的一段。空记忆返回空串 —— **不要发「（暂无记忆）」**：
     * 那句话会被当成「你还是新的」，于是它忽略掉手里正拿着的上下文。
     *
     * @param limit 最多带几条。分工那条路上一个角色只配占一两百字，带满二十条会把
     *   这一轮的事挤掉 —— 记忆是背景，不是任务。
     */
    internal fun memoryBlock(p: AgentProfile, limit: Int = MAX_NOTES): String {
        if (p.notes.isEmpty() || limit <= 0) return ""
        return buildString {
            append("【你记得的事（你自己以前做过的结论，不是这次别人告诉你的）】\n")
            for (n in p.notes.take(limit)) {
                append("- ").append(n.text.replace("\n", " ")).append('\n')
            }
        }
    }

    /**
     * agent 的系统提示词。
     *
     * ★ 四段固定顺序：**身份 → 职责 → 记忆 → 规矩**。
     *   记忆放在规矩前面，是因为规矩要对「它做过什么」生效；反过来先讲规矩再讲它做过什么，
     *   它会把这些记忆当成这一轮别人告诉它的话。
     * ★ 记忆为空时整段不出现（[memoryBlock] 返回空串），一个字都不留 ——
     *   空的记忆段等于告诉它「你什么都不知道」，它会因此忽略手里正拿着的上下文。
     */
    internal fun systemPrompt(p: AgentProfile): String = buildString {
        append("你是「").append(p.name.trim()).append("」，ConMarn 里的一个长期 agent：")
        append("有名字、有职责、有自己做过的事。\n\n")
        append("【职责】\n").append(p.charter.trim()).append("\n\n")
        val mem = memoryBlock(p)
        if (mem.isNotEmpty()) append(mem).append('\n')
        append(AGENT_RULES.replace("{budget}", fitBudget(p.budget).toString()))
    }

    /**
     * 一个 agent 干活时的规矩。和 [AiAgent] 里代码档那份提示词是同一条原则：
     * **没跑过的东西不许说它能用**。差别在于这里还多一条「只做职责范围内的事」——
     * 没有这条，它会顺手把整台机器当成自己的。
     */
    private val AGENT_RULES = """
【工作方式】
一、你在自己的工作区里动手，目录是 /root/work。文件上能用的：ls 看目录、read 读文件、write 写文件、patch 改文件、grep 搜、run 在 Linux 里执行一条命令。
      他手机上的事（日程、闹钟、打开应用、开网址）：先 list_hands，再 use_hand(hand="self", tool=…)。
      ★ 他说「提醒我明天下午三点交报告」这种**说得出哪天几点**的事，直接用 calendar_add 写进手机系统日历，
        date 和 time 原样抄他的话（日期换算由程序做，不许自己算毫秒）。说不出哪天几点的「记着」不算日程。
        回他说「记好了」之前必须有 calendar_add 的真实回执 —— 没回执就是没写。
二、每一次 run 都返回真实的输出与退出码。没有跑过的东西，不许说它能用。
三、一件事做完就调 done，summary 只写结论：做了什么、结果是什么、还剩什么没做。不要写过程。
四、这次最多动手 {budget} 步，到顶会停下来。所以先做最要紧的那一步，不要在第 1 步上反复打磨。
五、只做职责范围内的事。超出职责的，在 summary 里写清该由谁做，不要自己接。
六、你的记忆只有上面那一段。每一件活收工时，系统会把这一轮的结论记进去，下一件活开工时你还会看到它。
""".trimStart()

    /** 按「做得多的」排前面；一样多按新建的在前。 */
    internal fun ordered(all: List<AgentProfile>): List<AgentProfile> =
        all.sortedWith(compareByDescending<AgentProfile> { it.runs }.thenByDescending { it.createdAt })

    /**
     * 拆活拆出来的岗位名 → 册上某一个人的名字。认不出来返回 null。
     *
     * ★★ 2026-11-06 他：「分工，需要自动识别并匹配最适应的 agent」。
     *   提示词里已经让拆活的模型照抄名字，但它会写成近义词、少一个字、打全角。
     *   名字差一个字符，[idOf] 算出来就是另一个人 —— 那个人自己的记忆就用不上了。
     *
     * 三档，从严到宽：
     * 1. 一字不差（全角半角、大小写、空白都不算差别）；
     * 2. 按 [idOf] 折出来的 id 相同；
     * 3. 互相包含 —— 但**必须唯一命中**。两个都像就等于认不准，宁可当临时助手，
     *    也不能把活派错人：派错人的代价是那个人带着别人的记忆去做这件事。
     */
    internal fun matchName(role: String, names: List<String>): String? {
        val r = flat(role)
        if (r.isEmpty()) return null
        names.firstOrNull { flat(it) == r }?.let { return it }
        val rid = idOf(r)
        names.firstOrNull { idOf(it) == rid }?.let { return it }
        // ★ 一个字的不做包含匹配：「写」「查」这种名字跟谁都能对上。
        if (r.length < 2) return null
        val wide = names.filter {
            val n = flat(it)
            n.length >= 2 && (n.contains(r) || r.contains(n))
        }
        return if (wide.size == 1) wide[0] else null
    }

    /** 只为了比名字：全角转半角、去掉所有空白、字母小写。**不改存下来的名字**。 */
    private fun flat(s: String): String = buildString {
        for (c in s.trim()) {
            val x = when {
                c in '！'..'～' -> (c.code - 0xFEE0).toChar()
                c == '　' -> ' '
                else -> c
            }
            if (!x.isWhitespace()) append(x.lowercaseChar())
        }
    }

    /** 按 id、再按名字找。找不到返回 null。 */
    internal fun pick(all: List<AgentProfile>, key: String): AgentProfile? {
        val k = key.trim()
        if (k.isEmpty()) return null
        all.firstOrNull { it.id == k }?.let { return it }
        all.firstOrNull { it.name == k }?.let { return it }
        val kid = idOf(k)
        return all.firstOrNull { it.id == kid }
    }
}
