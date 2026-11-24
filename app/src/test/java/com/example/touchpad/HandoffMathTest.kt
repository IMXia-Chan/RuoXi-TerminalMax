package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「她忙的时候你说的那句怎么办」的测试。
 *
 * ★★ 这里防的错**全都不报错**:等得太短 = 两轮同时跑(两双手抢同一只鼠标);
 *   一直等 = 你的话卡在后台没回音;把「没接手」说得像「没事」= 你干等一个
 *   永远不会来的回答。所以下面测的不是算法,是**两个方向和两句人话**。
 */
class HandoffMathTest {

    // --------------------------------------------------------- 等多久

    /**
     * ★★ **最要紧的一条:等待上限必须长过一次工具调用。**
     *
     * 她的「停」不是瞬时的 —— 跨机器那一步靠轮询才检查得到,所以最坏情况
     * **就是一个工具跑完为止**。上限短于这个数,我们就会在她最该停一下的那次
     * 调用上放弃,而放弃的样子是「你的话又没了」= 这条功能白做。
     */
    @Test
    fun `等待上限必须长过一次工具调用`() {
        for (t in listOf(5_000L, 25_000L, 60_000L)) {
            val cap = HandoffMath.waitCapMs(t)
            assertTrue("上限 $cap 没有盖过一次工具调用 $t", cap > t)
        }
    }

    /** ★ 但也不能没有头 —— 你说的话不能卡在后台等到天荒地老。 */
    @Test
    fun `等待有上界_不能一直等下去`() {
        for (t in listOf(0L, 25_000L, 600_000L)) {
            val cap = HandoffMath.waitCapMs(t)
            assertTrue("上限 $cap 大得不像话", cap <= 600_000L + HandoffMath.MARGIN_MS)
            assertTrue("上限 $cap 根本不是正数", cap > 0)
        }
    }

    /**
     * ★ 配置坏掉(`toolTimeoutMs` 是 0 或负数)时退回默认值,**不许把等待变成零**。
     *
     * 那正是上面第一条要防的「等得不够久」,而且它会以「配置读错了」为由混进来。
     */
    @Test
    fun `配置坏掉时退回默认_不许变成零`() {
        val good = HandoffMath.waitCapMs(HandoffMath.DEFAULT_TOOL_TIMEOUT_MS)
        assertEquals(good, HandoffMath.waitCapMs(0L))
        assertEquals(good, HandoffMath.waitCapMs(-1L))
        assertTrue(HandoffMath.waitCapMs(0L) > HandoffMath.DEFAULT_TOOL_TIMEOUT_MS)
    }

    /**
     * ★ 半开区间:到点就是不能再等。
     *
     * 和免打扰那条(`QuietMath`)同一个写法 —— 「正好卡在上限」算哪边,
     * 必须只有一个答案,不能靠读代码现推。
     */
    @Test
    fun `到点了就不能再等`() {
        val cap = 30_000L
        assertTrue(HandoffMath.mayKeepWaiting(0L, cap))
        assertTrue(HandoffMath.mayKeepWaiting(cap - 1L, cap))
        assertFalse(HandoffMath.mayKeepWaiting(cap, cap))
        assertFalse(HandoffMath.mayKeepWaiting(cap + 1L, cap))
    }

    // --------------------------------------------------------- 那两句话

    /** ★★ **绝不许承诺「我一动她就停」。** */
    @Test
    fun `叫停那句话不许承诺马上停`() {
        val s = HandoffMath.calledOut()
        // 改措辞就得改这份名单 —— **这是故意的**:这几个词一出现,那句话就在撒谎。
        for (w in listOf("马上", "立刻", "立即", "瞬间", "随时", "一下子就")) {
            assertFalse("叫停那句话里出现了「$w」: $s", s.contains(w))
        }
    }

    /**
     * ★★ **必须说清「已经按下去的那一下收不回来」。**
     *
     * 一次点击在 Windows 上 350ms 就落地(`TAP_MAX_MS`),没有 OS 级钩子收得回来。
     * 这一句漏了,用户会以为她那一击**没发生** —— 而那一击可能是转账。
     */
    @Test
    fun `叫停那句话必须说清按下去的那一下收不回来`() {
        val s = HandoffMath.calledOut()
        assertTrue("没说清已经落地的收不回来: $s", s.contains("收不回来"))
        // 同时也不能让他以为这句话被扔了 —— 那正是这条功能要修的。
        assertTrue("没说这句记着: $s", s.contains("记着") || s.contains("记住"))
    }

    /**
     * ★★ **没接手就得明说没送出去。**
     *
     * 只说「卡住了」的话,用户会以为话在跑,干等一个永远不会来的回答 ——
     * 静默失败在这个项目里是头号敌人,而这一条的静默版本尤其难查:
     * 界面上什么都没错,就是她不吭声。
     */
    @Test
    fun `没接手那句必须明说这句没送出去`() {
        val s = HandoffMath.gaveUp(30_000L)
        assertTrue("没明说没送出去: $s", s.contains("没"))
        assertTrue("没让他重说一次: $s", s.contains("再说一次") || s.contains("重新说"))
    }

    /** ★ 等过头那句要带上**实际等了多久** —— 一个他能拿去核对的时间,不是形容词。 */
    @Test
    fun `等过头那句要带上等了多少秒`() {
        assertTrue("30 秒没说出来", HandoffMath.gaveUp(30_000L).contains("30"))
        assertTrue("90 秒没说出来", HandoffMath.gaveUp(90_000L).contains("90"))
    }

    /**
     * ★ 不到一秒也要说成 1 秒,不许出现「等了 0 秒」。
     *
     * 「0 秒」看起来像没等就放弃了 —— 而那正是它**不是**的事。
     */
    @Test
    fun `不到一秒也说成一秒_不许出现零秒`() {
        for (ms in listOf(0L, 1L, 999L)) {
            val s = HandoffMath.gaveUp(ms)
            assertTrue("出现了 0 秒: $s", s.contains("1 秒"))
            assertFalse("出现了 0 秒: $s", s.contains("0 秒"))
        }
    }

    /** 两句话都不许是空的 —— 空的回执在界面上什么都不显示,等于静默。 */
    @Test
    fun `两句话都不许是空的`() {
        assertTrue(HandoffMath.calledOut().isNotBlank())
        assertTrue(HandoffMath.gaveUp(1_000L).isNotBlank())
    }
}
