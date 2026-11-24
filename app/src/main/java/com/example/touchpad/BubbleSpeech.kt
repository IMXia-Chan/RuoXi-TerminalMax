package com.example.touchpad

/**
 * 她这一句答完,**出不出声**、**要不要把麦放回去**。
 *
 * ## 为什么它值得单独一个文件
 *
 * 2026-10-05 用户报:「**桌面好像说话没有声音**」。查下来是这条:
 *
 * > 房间里她**每答一句都念**(不管你是按住说的还是打字打的,见
 * > [ConMarnActivity] 的 `onFinal → say`)。
 * > 悬浮条上她**只有在你"说"出来的那一轮才念**(`speakThisReply` 只在
 * > `sendSpoken` 里置位)—— **在桌面上打字问她,她只写字、不出声。**
 *
 * 同一部手机、同一件事(跟她说话)、两副嗓子,**行为不一样**。这就是他看见的那个"没有声音"。
 *
 * ★ 这条错**不抛异常、不写日志**,而且**静止时看不出来** —— 只有真的在桌面上打一句
 *   才会现身。所以它和 [SlotMeasure] / [HistoryWindow] 是同一类东西,值得一块纯逻辑 + 单测。
 *
 * ## 两个答案,两个不同的理由
 *
 * | 问什么 | 谁说了算 | 错了会怎样 |
 * |---|---|---|
 * | 出不出声 | 这一轮是不是**他问的**([speakThisTurn]) | 错了 = 「她说话没有声音」 |
 * | 要不要放麦 | 这一轮**在不在对话里**([inVoiceSession]) | 错了 = 麦**自己**开起来(见下) |
 *
 * ★★ 第二行是**两件事,不是一件**,而且第二件比第一件要紧:
 *   打字问的时候她**必须出声**,但**绝不许**因此把麦克风打开 ——
 *   他根本没用语音,机器却开始录音,那是这个项目里最不能接受的一种错。
 *   把它们分开写,是为了让"念"和"开麦"不会再被顺手绑在一起。
 */
internal object BubbleSpeech {

    /**
     * @param speakThisTurn 这一轮是**他问的**(打字或说话)吗。
     *   `false` = 她主动开口那种([ConMarnBubble.onSpontaneous]),那时不出声。
     * @param inVoiceSession 现在在不在**一轮接一轮的对话**里(麦克风该不该还开着)。
     * @param hasApp 有没有 Context 能念(悬浮窗手里那个可能已经没了)。
     */
    internal fun plan(
        speakThisTurn: Boolean,
        inVoiceSession: Boolean,
        hasApp: Boolean,
    ): Plan = Plan(speaks = speakThisTurn && hasApp, rearms = inVoiceSession)

    /**
     * @param speaks 该念出来。
     * @param rearms 念完之后**要把麦放回去**吗 —— 注意它和 [speaks] 是两件事:
     *   打字问的那一轮 `speaks=true, rearms=false`,她出声,但麦克风一个字都不许开。
     */
    internal class Plan(val speaks: Boolean, val rearms: Boolean)
}
