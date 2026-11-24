package com.example.touchpad

/**
 * 分工里**角色之间的追问**：谁问了谁、问什么、最多几次 —— 全部是不需要模型的那一半。
 *
 * ## 为什么用一行文字，而不是让它们真的互相调用
 *
 * 一份活就是**一次**云端请求（见 `AiAgent.askOne`），没有工具循环，也没有「等对方回话」
 * 这种能力。要让它俩真对话，得给每个角色装一套工具循环 —— 那是另一个量级的改动，
 * 而且手机上一轮就是几十秒，来回三轮他会先受不了。
 *
 * 所以协议压到最小：**答案里单独一行写 `问@角色：问题`**。中枢收答案时把这一行抠出来，
 * 再单独问被点名的那个人一次。也就是说：
 *
 * - 出钱的是中枢，不是它们俩私下聊；
 * - 每一问都是**一次真实的请求**，所以 [MAX_ASKS] 卡死；
 * - 时间线上看得见「谁问谁」—— 那两行就是真发生过的事。
 *
 * ## 三条硬规矩
 *
 * 1. **只能问同场的人**。问一个不在这一轮的角色的名字，一律丢弃 ——
 *    否则它会编出「问@老板」然后自己把答案也编出来。
 * 2. **一个人最多被问一次**。两次就会变成对话，而对话是这条协议没有的东西。
 * 3. **总共最多 [MAX_ASKS] 次**。这一条是钱的闸门，不是优雅的考虑。
 */
object PeerMath {

    /**
     * 一次分工里最多追问几次。
     *
     * ★ 每问一次 = 多一次云端请求（多花钱、多等一轮）。2 次刚好够补上「缺的那一块」，
     *   再多就说明这件事一开始就该拆得更细，而不是靠对话补。
     */
    const val MAX_ASKS = 2

    /** 一个问题最多几个字。太长的不像追问，像第二份任务。 */
    const val MAX_Q = 120

    /** 一次追问：谁问、问谁、问什么。 */
    data class Ask(val from: String, val to: String, val question: String)

    /**
     * `问@角色：问题` 这一行的形状。
     *
     * ★ 认全角 `＠` 和全角冒号 —— 小模型十次里总有两三次打全角。
     * ★ 角色名限 1–8 个字：这是 [FanoutMath.MAX_ROLE_CHARS] 那条尺子。
     */
    private val RE = Regex("^问\\s*[@＠]\\s*([^\\s:：]{1,8})\\s*[:：]\\s*(.+)$")

    /**
     * 从几份答案里把追问那一行抠出来。
     *
     * @param answers 已经回来的几份（角色 + 正文）。
     * @param roles   这一轮在场的全部角色名 —— 问到不在场的人一律不认。
     */
    fun harvest(answers: List<FanoutMath.Answer>, roles: List<String>): List<Ask> {
        val here = roles.toSet()
        val out = ArrayList<Ask>()
        val taken = HashSet<String>()
        for (a in answers) {
            for (line in a.text.lines()) {
                val q = parseLine(line) ?: continue
                if (q.first == a.role) continue           // 问自己 = 没问
                if (q.first !in here) continue            // 不在场
                if (!taken.add(q.first)) continue         // 一个人只被问一次
                out.add(Ask(a.role, q.first, q.second))
                if (out.size >= MAX_ASKS) return out
            }
        }
        return out
    }

    /** 一行是不是 `问@角色：问题`。是就回 (角色, 问题)，不是就回 null。 */
    fun parseLine(line: String): Pair<String, String>? {
        val t = line.trim().trimStart('-', '*', '·', '•', ' ')
        val m = RE.find(t) ?: return null
        val who = m.groupValues[1].trim()
        val q = m.groupValues[2].trim().trim('。', '.', ' ')
        if (who.isEmpty() || q.isEmpty()) return null
        if (q.length > MAX_Q) return null
        return who to q
    }

    /**
     * 把别人回答的内容拼到提问者那一份后面。
     *
     * ★ 拼进**提问者的正文**，而不是另起一段附在最后：合并那一步（`mergeParts`）
     *   读的是每一份的正文，附在最后它就当成「额外的一段」写进成品里，
     *   而那一段跟谁都不连着。
     */
    fun attach(text: String, supplements: List<Pair<String, String>>): String {
        if (supplements.isEmpty()) return text
        return buildString {
            append(text.trimEnd())
            for ((who, ans) in supplements) {
                append("\n\n【追问「").append(who).append("」的回答】\n").append(ans.trim())
            }
        }
    }
}
