package com.example.touchpad

/**
 * **协作档位。** 三个名字、一句话说清各自管什么：
 *
 * | 档 | 谁决定派不派 | 什么时候问他 |
 * |---|---|---|
 * | [AUTO] 自动 | 她自己判断（默认，和以前一样） | 只在碰钱/碰身份时（见 [RiskMath]） |
 * | [PLAN] 规划 | 要派就先拆成 2~4 份再并发派 | 同上 |
 * | [STEP] 逐步 | 和自动一样 | **每动一步都先问一句** |
 *
 * ## 为什么是这三档，而不是照抄别的产品的名字
 *
 * 他问的是「三档 auto、plan 这种的怎么调」。落到这台机器上，真正能拨的其实就两根轴：
 * **要不要先拆**（花不花钱、快不快）和**要不要每步点头**（安不安全、烦不烦）。
 * 三档就是这两根轴上三个说得清的位置，再多一档就说不清它比上一档多了什么。
 *
 * ## ★ 为什么存的是字符串而不是序数
 *
 * 序数会被「以后在中间插一档」这件事毁掉：插进去之后，盘上存的 `1` 会静默变成别的档。
 * 字符串认不出来就退回 [AUTO]（[norm]），坏数据最多让人回到默认，不会跳到「逐步」那种
 * 每步都要点一下的位置。
 *
 * ## ★ 这个文件零 Android 依赖
 *
 * 档位判定全是纯逻辑，能跑 JVM 单测 —— 和 [RiskMath] / [ConfirmMath] 一个待遇。
 * 它决定的是「要不要停下来问他」，这种判断写错一次就是白弹一堆框，或者该问的不问。
 */
object AgentMode {

    /** 自动：她自己判断要不要派、派几个。默认档。 */
    const val AUTO = "auto"

    /** 规划：要派出去的活先拆成几份，再并发派。 */
    const val PLAN = "plan"

    /** 逐步：每动一步（每次调工具、每次往外派）都先问一句。 */
    const val STEP = "step"

    /** 循环切换的顺序。面板上那颗钮按这个顺序转。 */
    val ALL: List<String> = listOf(AUTO, PLAN, STEP)

    /**
     * 认不出来的一律当 [AUTO]。
     *
     * ★ 落向**最不打扰**的那一档：坏数据不该让机器开始每步弹框。
     */
    fun norm(raw: String?): String {
        val s = raw?.trim()?.lowercase().orEmpty()
        return if (s in ALL) s else AUTO
    }

    /** 界面上显示的名字。**只要这两个字，别加解释** —— 解释在设置页。 */
    fun label(raw: String?): String = when (norm(raw)) {
        PLAN -> "规划"
        STEP -> "逐步"
        else -> "自动"
    }

    /** 下一档。面板上点一下就转一格。 */
    fun next(raw: String?): String {
        val i = ALL.indexOf(norm(raw))
        return ALL[(i + 1) % ALL.size]
    }

    /** 这一档是不是「要派就先拆」。 */
    fun wantsPlan(raw: String?): Boolean = norm(raw) == PLAN

    /**
     * 这一步动手之前要不要先问他。
     *
     * @param tool      要调的工具名
     * @param readOnly  只读工具（不改变世界，也不花钱）
     * @param alreadyAsks 这个工具本来就有一道确认闸（见 [RiskMath.needsTap] 和 `type`）。
     *                   ★ 已经问过一次的不再问第二次 —— 一次动作弹两个框，人会开始闭着眼点，
     *                     那比不问还糟。
     */
    fun asksBeforeTool(raw: String?, tool: String, readOnly: Boolean, alreadyAsks: Boolean): Boolean =
        norm(raw) == STEP && !readOnly && !alreadyAsks && tool.isNotBlank()

    /** 回执里那句「现在是哪一档」—— 只在回执需要解释「为什么又弹了个框」时才用。 */
    fun why(raw: String?): String = when (norm(raw)) {
        PLAN -> "现在是「规划」档：要派的活先拆开再并发派"
        STEP -> "现在是「逐步」档：每动一步都会先问你"
        else -> "现在是「自动」档：派不派由她自己判断"
    }
}
