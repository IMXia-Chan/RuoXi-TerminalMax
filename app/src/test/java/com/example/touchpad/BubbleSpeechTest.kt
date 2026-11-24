package com.example.touchpad

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「她这一句念不念、麦放不放」那条判断的测试。
 *
 * ★★ 它防的错**不抛异常、不写日志**,而且**静止时看不出来** ——
 * 只有在桌面上真的打一句才会现身。用户 2026-10-05 报的原话:
 * 「**桌面好像说话没有声音**」。
 *
 * 后面两组是本文件真正值钱的地方:**「念」和「开麦」必须分得开**。
 */
class BubbleSpeechTest {

    // ------------------------------------------------------------------
    // 一、他问的就要念 —— 不管是打的还是说的

    /**
     * ★★★ 这条就是这次修复的**主证据**。
     *
     * 在桌面上**打字**问她(不在对话里):她**要念出来**。
     * 修之前这一格是 `speaks = false` —— 她只写字,一个字都不出声,
     * 而房间里同一个动作她是念的。
     */
    @Test
    fun `打字问的也要念出来`() {
        val p = BubbleSpeech.plan(speakThisTurn = true, inVoiceSession = false, hasApp = true)
        assertTrue("在桌面上打字问她,她一个字都不出声 —— 房间里不是这样的", p.speaks)
    }

    /** 说话问的、对话进行中:念,而且念完要把麦放回去。 */
    @Test
    fun `说话问的念完要把麦放回去`() {
        val p = BubbleSpeech.plan(speakThisTurn = true, inVoiceSession = true, hasApp = true)
        assertTrue(p.speaks)
        assertTrue("对话里她念完必须放麦,否则这一轮就死在这儿(症状:她说完就不理我了)", p.rearms)
    }

    /** 她**主动开口**那一轮(不是他问的):不出声 —— 那句是 [ConMarnBubble.onSpontaneous] 来的。 */
    @Test
    fun `她主动开口那一轮不出声`() {
        val p = BubbleSpeech.plan(speakThisTurn = false, inVoiceSession = false, hasApp = true)
        assertFalse("她主动说的那句不该再念一遍", p.speaks)
        assertFalse(p.rearms)
    }

    // ------------------------------------------------------------------
    // 二、★★ 「念」绝不许把「开麦」带出来

    /**
     * ★★ 隐私那一侧:**打字问的、又不在对话里 —— 念完之后一个字都不许录**。
     *
     * 这两件事一旦被写成同一个布尔,就会变成「他打了个字,麦克风自己开了」。
     * 那不是体验问题,是这个项目最不能接受的那种错。
     */
    @Test
    fun `打字问的不许把麦打开`() {
        val p = BubbleSpeech.plan(speakThisTurn = true, inVoiceSession = false, hasApp = true)
        assertFalse("他没用语音,机器却开始录音", p.rearms)
    }

    /** 反过来:不出声的那一轮,如果正在对话里,麦照样要放回去(否则对话断在这儿)。 */
    @Test
    fun `不出声但在对话里_麦照样要放回去`() {
        val p = BubbleSpeech.plan(speakThisTurn = false, inVoiceSession = true, hasApp = true)
        assertFalse(p.speaks)
        assertTrue(p.rearms)
    }

    // ------------------------------------------------------------------
    // 三、没有 Context 就念不了 —— 但对话不能因此断掉

    /** 悬浮窗手里那个 Context 可能已经没了:念不了是事实,但不能连麦也不放。 */
    @Test
    fun `没有Context时不念但对话照旧`() {
        val p = BubbleSpeech.plan(speakThisTurn = true, inVoiceSession = true, hasApp = false)
        assertFalse("没有 Context 念不出来", p.speaks)
        assertTrue("念不出来不是把这一轮扔掉的理由", p.rearms)
    }
}
