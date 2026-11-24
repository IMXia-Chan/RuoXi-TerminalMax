package com.example.touchpad

/**
 * **技能** —— 她把一件做成过的事，存成一张可以重放的步骤表。
 *
 * 项目里第一次出现「越用越强」这四个字是在能力清单里：一次做完的事，下次一条命令。
 * 这个文件只放**纯数据与纯规则**（不碰 Android、不碰 org.json），理由同
 * [HandModel] 的文件头：这一层的错**不崩**，表现是「技能存了个空的」「重放跑到一半
 * 停在莫名其妙的地方」，真机上极难复现。
 *
 * ---
 *
 * ## ★ 技能只能是「真实做过的事」
 *
 * 步骤**不是模型自己写的**，是把这一轮的行为记录（[AiAgent] 的 currentTrace）原样搬过来。
 * 让模型自己拼步骤的代价是：它会写出一个从没跑通过的顺序，而回执上看起来一样漂亮。
 * 存下来的东西必须等于「刚才真发生过的那一串」，否则重放第一次就会炸，而且是当着他的面炸。
 *
 * ## ★ 看菜单的那几步不收
 *
 * `list_hands` 这类只是看一眼，不是「做成的事」。更关键的是 `skill_*` 自己
 * **绝对不能进步骤表**：重放一个包含 `skill_run` 的技能，就是一条无限套娃的路。
 * 这里挡两道 —— 存档时滤掉（[SkillMath.clean]），重放时再挡一次（[SkillMath.isMeta]）。
 *
 * ## ★ 高危轮存下来的技能，重放时先问一次
 *
 * 「这一轮碰钱/碰身份」是**存档那一刻**的事实（[Skill.sensitive]）。重放时的用户
 * 指令是「跑一下那个技能」，关键词匹配看不出它是碰钱的事。所以把那一档**记在技能上**，
 * 重放前照着再问一次。这是唯一一处「把上一轮的风险判断带到这一轮」，
 * 因为重放本来就等于把上一轮重做一遍。
 */
data class SkillStep(
    /** 工具名，和 [HandTool.name] 同一套。 */
    val tool: String,
    /** 参数，**原样是 JSON 文本**。放字符串不放 JSONObject：这个文件要保持纯 JVM 可测。 */
    val args: String,
)

/**
 * 技能的两条路。**它们不是同一件事，别用同一套字段硬套。**
 *
 * | | [ACTION] | [REASONING] |
 * |---|---|---|
 * | 存的是 | 一串确定的工具调用 | 一套方法（问什么、查什么、怎么推、怎么输出） |
 * | 例子 | 关灯、开勿扰、设明早七点闹钟 | 分析一只股票、把一堆资料理成结论 |
 * | 执行 | 逐步照做，每一步过闸 | **每次都由她自己算**，没有固定步骤 |
 * | 错了会怎样 | 点错地方、发错消息 | 说得不对、结论站不住 |
 *
 * ★ 为什么必须分开：把「分析股票」硬拆成步骤是假的 —— 数据每天不同，
 *   分析师的第一步永远是看今天的数。存成录像，第二天就是错的。
 *   反过来，把「设闹钟」存成一段提示词，她也可能哪天忘了设。
 */
enum class SkillKind {
    ACTION,
    REASONING,

    /**
     * **研究型** —— 要跨很多轮、要查资料、要验证、可能今天做不完的事。
     *
     * 它和 [REASONING] 的区别是**时间**：思考型一次回答就完了；研究型要跑很久，
     * 中途会被打断（他去上课、手机没电、她要去干别的），所以**进度必须落在盘上**。
     *
     * ★ 它必须带一条 [Skill.acceptance]（怎样算对）。没有判据的研究型技能
     *   只是在许愿 —— 跑二十轮也说不清是成了还是没成，而那正好是这个项目
     *   最不能接受的一种「看起来在工作」。
     */
    RESEARCH,
}

data class Skill(
    /** 稳定 id，由名字算出来（[SkillMath.idOf]）。重名就是覆盖。 */
    val id: String,
    val name: String,
    val desc: String,
    val steps: List<SkillStep> = emptyList(),
    /** 存下来那一刻，这一轮是不是碰钱/碰身份的高危轮。见文件头。 */
    val sensitive: Boolean,
    val createdAt: Long,
    /** 重放过几次。只用来在清单里按「常用的」排前面。 */
    val runs: Int = 0,
    /**
     * 这条是**拼出来的草稿**，不是跑通过的事实。
     *
     * 用户用一句话描述的技能（「以后我说晚安你就……」）只能靠拼 —— 那一刻并没有真跑过。
     * 所以它和 [sensitive] 一样，**每次重放前都要他点一下**：拼错了的代价是点错地方、
     * 发错消息，而不是「少一句话」。
     */
    val draft: Boolean = false,
    val kind: SkillKind = SkillKind.ACTION,
    /**
     * **思考型技能的方法本身** —— 她在执行时要照着走的那一段。
     *
     * 写的是：要先问清楚什么、去查什么、按什么顺序推、最后输出成什么样。
     * 不是给人看的说明（那是 [desc]），是**给她看的作业要求**。
     */
    val instructions: String = "",
    /** 执行前必须从用户那里拿到的东西（股票代码、时间段、对象）。缺了要先问。 */
    val inputs: List<String> = emptyList(),

    /** 研究型：到底要产出什么。一句话说清，不写就是空转。 */
    val objective: String = "",
    /**
     * 研究型：**怎样算对**。
     *
     * 判据必须能被检查（数字对得上、反例没找到、推导无跳步、有人在别处复现），
     * 不能是「感觉差不多」。这一栏决定了这个技能值不值得让它跑第二轮。
     */
    val acceptance: String = "",
    /** 研究型：已经跑了多少轮。每调一次 `skill_run` 加一，用来卡预算。 */
    val rounds: Int = 0,
    /** 研究型：最多跑多少轮。到顶就停，要他说继续才接着跑 —— 没人管的循环是烧电的。 */
    val budget: Int = 20,
    /**
     * **他说到什么话就该想起这条技能**。
     *
     * ★ 这是「以后我说晚安你就……」真正落地的地方：光有技能表没用，得有人在她听见
     *   「晚安」的时候想起来。判据在本地（[SkillMath.hit]），不花一次模型调用。
     * ★ 存的是**他嘴里会说的词**，不是给人看的说明；老档案读不到就是空 —
     *   空触发词的技能**永远不会自己冒出来**（只能靠 skill_run 点名），这是故意的：
     *   猜错的代价是她在不相干的时候硬套一条技能。
     */
    val triggers: List<String> = emptyList(),
)

internal object SkillMath {

    /** 一条技能最多几步。超出的部分不存 —— 二十步以上的事该拆成两条。 */
    const val MAX_STEPS = 20

    /** 最多存几条技能。到了上限不静默丢，回执里说清楚，让他删。 */
    const val MAX_SKILLS = 40

    const val NAME_MAX = 20

    /** 方法最长多少字。太长的不是方法，是一篇文章 —— 她在上下文里也用不好。 */
    const val INSTR_MAX = 4000

    /**
     * 不属于「她做成的事」的工具。
     *
     * 前一个（`list_hands`）是看菜单；后三个是技能自己的出入口 ——
     * 它们进步骤表就是套娃。
     */
    private val META = setOf(
        "list_hands",
        "skill_list", "skill_save", "skill_new", "skill_run", "skill_note", "skill_ledger",
        "skill_delete",
    )

    /**
     * **顶层工具** —— 模型能直接调的那一批，和 [AiAgent] 里 `fn(...)` 的名单是同一套。
     *
     * ★ 加新工具时这里要跟着加，否则自然语言拼出来的技能用不了它。
     *   漏了的表现是**看得见的报错**（「没有叫 X 的工具」），不是静默少一步 ——
     *   这是它比「自动从 schema 里扒」更安全的地方：扒错了不吭声。
     *
     * 技能自己的四个出入口（`skill_*`）不在这里：技能里不许套技能。
     */
    val TOP_TOOLS = setOf(
        "open_app", "list_windows", "focus_window", "media", "get_state",
        "click_ui", "scroll", "search", "list_ui", "read_screen",
        "click_element", "hotkey", "type", "set_value", "list_hands", "use_hand",
    )

    fun isMeta(tool: String): Boolean = tool in META

    /**
     * 一步收不收进技能。判据放在这里，因为它错起来是静默的：
     * 收早一步会重放一个失败过的动作，收漏一步会让技能缺一块、下次跑到一半就断。
     *
     * - 看菜单的、技能自己的出入口：不是「做成的事」，且会套娃 → 不收
     * - 失败的、被他中途停下的：重放时一定再坏一次 → 不收
     */
    fun keepStep(tool: String, failed: Boolean, stopped: Boolean): Boolean =
        tool.isNotBlank() && !isMeta(tool) && !failed && !stopped

    /** 只留真要重放的那几步，并按上限截断。 */
    fun clean(steps: List<SkillStep>): List<SkillStep> =
        steps.filter { keepStep(it.tool, failed = false, stopped = false) }.take(MAX_STEPS)

    /** 名字能不能用。**回执里要能照着这句话改**，所以写人话。 */
    fun nameProblem(name: String): String? = when {
        name.isBlank() -> "技能得有名字，不然下次叫不出来。"
        name.length > NAME_MAX -> "名字太长（最多 $NAME_MAX 个字）。"
        else -> null
    }

    /**
     * 名字 → id。
     *
     * 中文原样留着（这条机器上文件名能存中文），只把空格和标点换成下划线。
     * 不取哈希：**他得能从文件名认出这是哪条技能**。
     */
    /**
     * 这条技能存不存得下。两种技能查的东西不一样，所以分开问。
     *
     * ★ 空技能是最坏的失败方式：存的时候一切正常，叫它的时候**什么都发生不了**，
     *   而回执上写着「跑完了」。所以两头都要能报出原因。
     */
    fun shapeProblem(kind: SkillKind, steps: List<SkillStep>, instructions: String): String? =
        when (kind) {
            SkillKind.ACTION ->
                if (clean(steps).isEmpty()) "动作型技能一步都没有，存了也叫不出来。" else null
            SkillKind.REASONING -> when {
                instructions.isBlank() -> "思考型技能没写方法，存了她也不知道该怎么分析。"
                instructions.length > INSTR_MAX ->
                    "方法太长（${instructions.length} 字，最多 $INSTR_MAX）。写成要点，不要写成文章。"
                else -> null
            }
            else -> null
        }

    /**
     * 研究型还要多几样东西。单独立一个函数（不塞进 [shapeProblem]），
     * 因为它的参数和前两种对不上 —— 硬套会让调用处传一堆没用的参数。
     */
    fun researchProblem(objective: String, acceptance: String, instructions: String): String? = when {
        objective.isBlank() -> "研究型技能得说清**要产出什么**，不然跑起来就是空转。"
        acceptance.isBlank() ->
            "研究型技能必须写**怎样算对**（数字对得上、反例没找到、有复现……）。" +
                "没有判据的不是研究，是许愿 —— 跑二十轮也说不清成没成。"
        instructions.isBlank() -> "研究型技能要写怎么做：查什么、怎么推、怎么验、产出成什么样。"
        instructions.length > INSTR_MAX ->
            "方法太长（${instructions.length} 字，最多 $INSTR_MAX）。"
        else -> null
    }

    /** 还能再跑几轮。0 = 到顶了，要用户点头才续。 */
    fun left(skill: Skill): Int = (skill.budget - skill.rounds).coerceAtLeast(0)

    fun idOf(name: String): String {
        val sb = StringBuilder()
        for (c in name.trim()) {
            when {
                c.isLetterOrDigit() -> sb.append(c.lowercaseChar())
                else -> sb.append('_')
            }
        }
        val s = sb.toString().trim('_')
        return if (s.isEmpty()) "skill" else s.take(40)
    }

    /** 找到一条技能：先按 id 精确匹配，再按名字。找不到返回 null。 */
    fun pick(all: List<Skill>, key: String): Skill? {
        val k = key.trim()
        if (k.isEmpty()) return null
        all.firstOrNull { it.id == k }?.let { return it }
        all.firstOrNull { it.name == k }?.let { return it }
        val kid = idOf(k)
        return all.firstOrNull { it.id == kid }
    }

    /** 清单按「跑得多」排前面；一样多按新存的在前。 */
    fun ordered(all: List<Skill>): List<Skill> =
        all.sortedWith(compareByDescending<Skill> { it.runs }.thenByDescending { it.createdAt })

    /** 一次最多提几条 —— 提多了等于把技能表全倒给她，那就不是「想起来」了。 */
    const val MAX_HITS = 3

    /**
     * 一句话里提到了哪几条技能。
     *
     * ★ 判据只有「这句话里出现了它的触发词」，**不是猜语义**：猜错的代价是她在
     *   说别的的时候硬套一条技能 —— 而那种错看起来完全正常（她会很自信地做错的事）。
     * ★ 一个字的触发词一律不算：「灯」「嗯」这种词迟早会撞上无关的话。
     */
    fun hit(text: String, all: List<Skill>): List<Skill> {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return emptyList()
        val out = ArrayList<Skill>()
        for (s in ordered(all)) {
            val yes = s.triggers.any { trig ->
                val k = trig.trim().lowercase()
                k.length >= 2 && t.contains(k)
            }
            if (yes) {
                out.add(s)
                if (out.size >= MAX_HITS) break
            }
        }
        return out
    }

    /**
     * **槽位** —— 步骤参数里写着 `{{名字}}` 的地方，跑之前必须先填上。
     *
     * ★ 「以后我说『给张三发消息你就……』」这种技能，拼步骤那一刻并不知道张三是谁。
     *   现在会把 `{{对象}}` 原样存进去；若不填就重放，**她会把「{{对象}}」这四个字
     *   打进搜索框**，回执上还写着「跑完了」—— 静默错里最难查的一种。所以：
     *   存的时候把槽位记进 [Skill.inputs]，跑的时候没给值就**一步都不动**。
     */
    fun slotsOf(steps: List<SkillStep>): List<String> {
        val out = LinkedHashSet<String>()
        for (s in steps) {
            for (m in SLOT.findAll(s.args)) {
                val n = m.groupValues[1].trim()
                if (n.isNotEmpty()) out.add(n)
                if (out.size >= MAX_SLOTS) return out.toList()
            }
        }
        return out.toList()
    }

    /** 还差哪几个值没给。空 = 齐了。 */
    fun missing(steps: List<SkillStep>, values: Map<String, String>): List<String> =
        slotsOf(steps).filter { values[it].isNullOrBlank() }

    /** 把值填进步骤。**没给的槽位原样留着**（调用方应先查 [missing]）。 */
    fun fill(steps: List<SkillStep>, values: Map<String, String>): List<SkillStep> =
        steps.map { s ->
            val body = SLOT.replace(s.args) { m ->
                val n = m.groupValues[1].trim()
                val v = values[n]
                if (v.isNullOrBlank()) m.value else jsonEscape(v.trim())
            }
            SkillStep(s.tool, body)
        }

    /**
     * 一个值要变成 JSON 字符串里的一截。
     *
     * ★ 必须转义：他说「搜一下 "会议纪要"」时值里带引号，不转义就把 args 拼成坏 JSON，
     *   重放第一步就炸，而且报的是「参数不是合法的 JSON」—— 看不出是哪个字。
     */
    fun jsonEscape(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else ->
                    if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    else sb.append(c)
            }
        }
        return sb.toString()
    }

    /** 一条技能最多几个槽位。再多就是该拆成几条技能了。 */
    const val MAX_SLOTS = 8

    /**
     * `{{名字}}`。名字里不许再有花括号，1~20 个字。
     *
     * ★★ 结尾那两个括号**必须都转义**（`\\}\\}`）。只转义前一半在桌面 JVM 上能跑，
     *   到了手机上（Android 用的是 ICU 正则）**静态初始化就抛** ——
     *   而它在 `object` 的 `<clinit>` 里，一抛就是整个类初始化失败：
     *   点一次「技能」直接闪退。2026-11-06 他撞上的就是这个。
     */
    private val SLOT = Regex("\\{\\{\\s*([^{}]{1,20}?)\\s*\\}\\}")

    /**
     * 「他这句话可能要用到这几条技能」那一段。→ null = 一条都没命中（**什么都不发**）。
     *
     * ★ 只在命中时发，不每轮发整张技能表：整表每轮都在 messages 里，
     *   她会把「我有一堆技能」当成「他想要我干点什么」，而那句话本来只是闲聊。
     */
    fun hint(text: String, all: List<Skill>): String? {
        val hits = hit(text, all)
        if (hits.isEmpty()) return null
        return buildString {
            append("[他这句话像是要用到这几条技能]\n")
            for (s in hits) {
                append("- ").append(s.name).append("（id=").append(s.id).append("）")
                if (s.desc.isNotBlank()) append("：").append(s.desc.take(60))
                append('\n')
            }
            append("要用就走 skill_run(id=…)。")
            // ★ 这一句不能省：只给「像」，不说「不像就别用」，她会把每句闲聊都当成派活。
            append("**不像**就别用 —— 硬套一条技能，比不做更糟。")
        }
    }
}

/**
 * **他是不是在交代一条技能。**
 *
 * ★★ 2026-11-06 他：「你他妈不要傻逼一样写死在代码里坑我 —— 一测试能通过，
 *   换个 skill、新增一个就调不出来」。所以这里认的是**说法本身**，不是某一条技能：
 *   「以后我说 X 你就……」这个句式，换任何一个 X 都走同一条路。
 *
 * ★ 判据故意窄（三个条件同时成立才算），宁可漏、不可错：
 *   把一句闲聊认成交代技能，会弹一个莫名其妙的确认框；漏了只是落回原来那条
 *   「靠模型自己想起来」的路，和现在一样，不会更糟。
 */
object SkillIntentMath {

    /** 什么时候开始算数。 */
    private val WHEN = listOf("以后", "往后", "下次", "今后", "从今", "每次")

    /** 触发从哪来 —— 必须是「他说的话」，不是「他说的事」。 */
    private val TRIG = listOf("我说", "我提", "我喊", "我告诉", "你听", "说到", "听到")

    /**
     * @return true = 这句话看起来在交代「以后我说 X，你就 Y」。
     *         **不判断它能不能真的建成**，只判断该不该走那条路。
     */
    fun looksLikeOrder(text: String): Boolean {
        val t = text.trim()
        // 太短的不像交代（「以后我就这样」）；太长的多半是在讲一件事，不是交代一条规矩。
        if (t.length < 8 || t.length > 200) return false
        if (WHEN.none { t.contains(it) }) return false
        if (TRIG.none { t.contains(it) }) return false
        // 「就」是那句式的骨架：有「以后我说 X」，没有「就 Y」，那只是聊天。
        return t.contains("就")
    }
}
