package com.example.touchpad

/**
 * 接力里**不需要模型**的那部分：把 `["岗位|这一段做什么", …]` 拆开，再把每一段该递过去的话拼好。
 *
 * ## 「同时分工」和「一个任务里的不同角色」不是一回事
 *
 * 这两件事长得像，做起来完全相反：
 *
 * ```
 * 并行分工（FanoutMath）   检索 ─┐
 *                          编辑 ─┼─→ 对账 ─→ 用
 *                          审稿 ─┘        互不相干，同时做完再拼
 *
 * 串行接力（这里）         检索 → 编辑 → 审稿 → 用
 *                             一段的产出交给下一段，后一段离了前一段开不了工
 * ```
 *
 * 判据只有一句：**这一段，离了上一段交回来的东西还能不能开工？**
 * 能 → 并行；不能 → 接力。
 *
 * ## 为什么接力不「对账」
 *
 * 并行的那几份互相看不见，分歧才有意义，所以要[FanoutMath.reconcile]。
 * 接力的几段是**一条线**，后一段已经看过前一段了 —— 再拿字符统计去「对账」，
 * 对的是自己和自己，什么也验不出来。
 */
object ChainMath {

    /**
     * 一条接力最多几段。
     *
     * 和 [AiAgent.MAX_DELEGATES] 不是同一个数：那个管「这一轮总共花几次」，
     * 这个管「一条链别长得没人看得完」。四段已经能把「查 → 写 → 挑 → 改」走完一轮。
     */
    const val MAX_STAGES = 4

    /**
     * 岗位名最多几个字，和扇出同一把尺子。
     *
     * ★★ 2026-11-08（体检 D7）：原来这里写的是 **8**，而 [FanoutMath.MAX_ROLE_CHARS] 是 12。
     *   同一句「名字/这一段做什么」，走分工叫「资深技术方案审核员」，走接力被截成
     *   「资深技术方案审核」—— 而他看到的是同一条链上两个岗位名字不一样长，
     *   原因只是选错了档位。两处同一个含义的常数必须是同一个数。
     */
    const val MAX_ROLE_CHARS = 12

    /**
     * 上一段交回来的东西往下带多少字。
     *
     * ★ 截断不是省事，是**必须**：不截的话第四段要背着前三段的全文去读，
     *   又贵又容易把重点读丢。截了要在话里说清楚截了，别让它以为那就是全部。
     */
    const val PREV_BUDGET = 4000

    data class Parsed(val stages: List<FanoutMath.Spec>, val problems: List<String>)

    /**
     * 拆单。和 [FanoutMath.parse] 有意不同的一点：**允许重名**。
     *
     * 「编辑 → 审稿人 → 编辑」是真实存在的走法（改、挑刺、再改），
     * 扇出那边重名要合并是因为它们是同时说话的人；接力这边重名是同一个岗位隔一段再上手。
     * 顺序也**一个字都不能动** —— 顺序就是这条链的意思。
     */
    fun parse(raw: List<String>?): Parsed {
        val out = ArrayList<FanoutMath.Spec>()
        val problems = ArrayList<String>()
        for (line in raw.orEmpty()) {
            val s = line.trim().trim('-', '*', '·', '•', '#', ' ', '\t')
            if (s.isEmpty()) continue
            // ★ 2026-11-06 起也认 `名字/这一段做什么` —— 见 [FanoutMath.splitItems] 那段。
            //   开头那一截带冒号的不切：`http://…` 不是岗位名。
            val cut = s.indexOfFirst { it == '|' || it == '｜' || it == '/' || it == '／' }
            val who = if (cut < 0) "" else s.substring(0, cut).trim()
            val named = who.isNotEmpty() && !who.contains(':') && !who.contains('：')
            var role = if (named) who else ""
            val task = if (named) s.substring(cut + 1).trim() else s
            if (role.isEmpty()) role = "助手"
            if (role.length > MAX_ROLE_CHARS) role = role.take(MAX_ROLE_CHARS)
            if (task.isEmpty()) {
                problems.add("第 ${out.size + 1} 段没写这一段要做什么，跳过了")
                continue
            }
            if (out.size >= MAX_STAGES) {
                problems.add("超过 $MAX_STAGES 段，后面 ${raw.orEmpty().size - out.size} 段没派")
                break
            }
            out.add(FanoutMath.Spec(role, task))
        }
        if (out.size < 2) problems.add("至少要两段才算接力")
        return Parsed(out, problems)
    }

    /** 一条链长什么样：`检索员 → 编辑 → 审稿人`。给它看，也给[AgentBus]那一行看。 */
    fun plan(stages: List<FanoutMath.Spec>): String =
        stages.joinToString(" → ") { it.role }

    /**
     * 第 [index] 段（从 1 数）该递出去的话。
     *
     * 三块：整件事、上一段交回来的、你这一段要做的。
     * ★ 顺序不能颠倒：「整件事」在最前面定调，「你这一段」在最后面是它真正要执行的东西。
     */
    fun promptFor(
        whole: String,
        prev: String?,
        stage: FanoutMath.Spec,
        index: Int,
        total: Int,
    ): String = buildString {
        append("【整件事】\n").append(whole.trim()).append("\n\n")
        if (!prev.isNullOrBlank()) {
            append("【上一段交回来的东西】\n")
            if (prev.length > PREV_BUDGET) {
                append(prev.take(PREV_BUDGET))
                append("\n（后面还有 ").append(prev.length - PREV_BUDGET).append(" 字，这里截断了）")
            } else {
                append(prev)
            }
            append("\n\n")
        }
        append("【你是第 ").append(index).append(" 段，共 ").append(total).append(" 段】\n")
        append(stage.task.trim()).append("\n")
        if (index == total) {
            // ★ 「最后一段」要说死：不说的话它会给一份提纲，而中枢会把它当成成品用。
            append("\n这是最后一段：你交回来的东西会被**直接拿去用**，所以要成品，不要提纲、不要「下一步可以…」。")
        } else {
            append("\n只做你这一段：你交回来的东西会原样交给下一位，别替后面几段干活，也别评价整件事该怎么安排。")
        }
    }
}
