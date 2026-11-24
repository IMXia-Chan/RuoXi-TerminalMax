package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「按住那块玻璃」那两个判断的测试。
 *
 * ★★ 它防的错**一个都不抛异常**,而且**手指一松就过去了** ——
 * 表现只有两种,还都长得像「她坏了」:
 *   · 上滑了却没取消 → 那句他不想发的话**发出去了**(不可逆)
 *   · 没滑够却取消  → 他白说了一遍,而且会以为是自己没按好
 *
 * 这两条连着房间和悬浮条两条路(用户 2026-10-05 把两处一起点名了),
 * 所以判据收在 [BarGestureMath] 一份,这里钉死。
 */
class BarGestureMathTest {

    /** 一块 3.0 密度的屏上,56dp 是 168px。用真数字,别用 1。 */
    private val threshold = 56f * 3f

    // ------------------------------------------------------------------
    // 一、只认往上

    /** ★ 门槛本身:正好滑到 56dp 就算数(边界取 `>=`,和会话超时同一个规矩)。 */
    @Test
    fun `正好滑到门槛就算取消`() {
        assertTrue(BarGestureMath.entersCancelZone(downRawY = 2000f, nowRawY = 2000f - threshold, thresholdPx = threshold))
    }

    /** 差一个像素不算 —— 门槛不是「大概」。 */
    @Test
    fun `差一点点不算`() {
        assertFalse(
            BarGestureMath.entersCancelZone(
                downRawY = 2000f, nowRawY = 2000f - threshold + 1f, thresholdPx = threshold,
            )
        )
    }

    /**
     * ★★ **往下滑永远不是取消。**
     *
     * 这是这一套里最容易顺手写错的一条:`abs(dy) > 门槛` 看起来更「对称」,
     * 但往下滑在这个手势里**没有含义** —— 把它算成取消的话,
     * 他手指自然往下压一点点,话就没了。
     */
    @Test
    fun `往下滑多远都不是取消`() {
        assertFalse(BarGestureMath.entersCancelZone(1000f, 1500f, threshold))
        assertFalse(BarGestureMath.entersCancelZone(1000f, 5000f, threshold))
    }

    /** 原地不动也不是取消。 */
    @Test
    fun `没动不算取消`() {
        assertFalse(BarGestureMath.entersCancelZone(1000f, 1000f, threshold))
    }

    // ------------------------------------------------------------------
    // 二、★★ 松手时那一下算什么 —— 取消排在最前面

    /**
     * ★★★ 这一条是**本文件最值钱的**。
     *
     * 一个只按了 60ms 的上滑(远短于 350ms 的「点一下」门槛)照样是**取消**。
     * 要是判据把 `swipeCancelled` 放到 `isTap` **后面**,这一下会落到 `TAP` ——
     * 于是「我滑了一下想撤」变成了「点一下 → 进打字模式」,
     * 而那句话在上滑之前就已经被 [Ear.finishNow] 送出去了。
     */
    @Test
    fun `上滑取消了就算只按了六十毫秒也是取消`() {
        assertEquals(
            "上滑取消必须盖过「点一下」—— 否则撤不回来",
            BarGestureMath.Release.CANCEL,
            BarGestureMath.release(swipeCancelled = true, heldMs = 60L),
        )
    }

    /** 「已经进过取消区」是**闩**:他中间又滑回来了,这一次照样认取消。 */
    @Test
    fun `进过取消区就一直算取消`() {
        assertEquals(
            BarGestureMath.Release.CANCEL,
            BarGestureMath.release(swipeCancelled = true, heldMs = 8_000L),
        )
    }

    /** 没上滑:短按 = 点一下(她正说着 → 打断;她安静 → 打字)。 */
    @Test
    fun `没上滑的短按算点一下`() {
        assertEquals(BarGestureMath.Release.TAP, BarGestureMath.release(swipeCancelled = false, heldMs = 120L))
    }

    /** 没上滑:按住了 = 说话,松手就是「我说完了」。 */
    @Test
    fun `没上滑的长按算说话`() {
        assertEquals(BarGestureMath.Release.HOLD, BarGestureMath.release(swipeCancelled = false, heldMs = 2_400L))
    }

    /** 边界跟着 [EarSessionMath.isTap] 走:349 算点、350 算按住。 */
    @Test
    fun `点与按的边界在三百五十毫秒`() {
        assertEquals(
            BarGestureMath.Release.TAP,
            BarGestureMath.release(swipeCancelled = false, heldMs = EarSessionMath.TAP_MAX_MS - 1),
        )
        assertEquals(
            BarGestureMath.Release.HOLD,
            BarGestureMath.release(swipeCancelled = false, heldMs = EarSessionMath.TAP_MAX_MS),
        )
    }
}
