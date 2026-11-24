package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「玻璃胶囊这一格该多高」那条算术的测试。
 *
 * ★★ 它防的错**不抛异常、不写日志、安静时看不出来** ——
 * 只在**按住说话的那几秒**变成另一个样子,而那几秒正是用户盯着看的时候。
 * 用户为此报了**三次**(最近一次 2026-10-05:「你那个房间对话框 ~~~ 还是没好」)。
 *
 * 下面第一组的数字**全部来自真机**(`dumpsys activity top`,1080×2374 / density 3),
 * 不是编的:字幕 141px、胶囊内边距 24px、安静时那一格 141px、胶囊 165px。
 * 修之前按住的那一刻那一格被量成 1026px —— 这个数也留在用例里。
 */
class SlotMeasureTest {

    private fun kid(
        heightPx: Int,
        top: Int = 0,
        bottom: Int = 0,
        fillsParent: Boolean = false,
        gone: Boolean = false,
    ) = SlotMeasure.Child(heightPx, top, bottom, fillsParent, gone)

    // ------------------------------------------------------------------

    /**
     * ★★★ 这条是这次修复的**主证据**。
     *
     * 一条「想铺满」的水波(它自报 1026px —— 那是「爸爸还剩多少」,不是它想多高)
     * **不许**决定这一格有多高。说了算的只能是字幕那 141px。
     */
    @Test
    fun `想铺满的水波不许决定这一格有多高`() {
        val h = SlotMeasure.slotHeight(
            listOf(kid(141), kid(1026, fillsParent = true)),
            paddingPx = 24,
        )
        assertEquals("真机上安静时量出来的就是 165px", 165, h)
        // 反过来说得更直白:那个 1026 一个字都不许出现在结果里。
        assertTrue("1026(水波自报的高度)漏进结果了", h != 1026 + 24)
    }

    /** 反过来:没有想铺满的,那就是最高的那个老实孩子说了算 —— **和顺序无关**。 */
    @Test
    fun `没有想铺满的时最高的那个说了算`() {
        assertEquals(141 + 24, SlotMeasure.slotHeight(listOf(kid(141), kid(139)), 24))
        assertEquals(141 + 24, SlotMeasure.slotHeight(listOf(kid(139), kid(141)), 24))
    }

    /**
     * ★ 一个老实孩子都没有时,必须回 [SlotMeasure.NO_OPINION] —— **不许回一个像样的数**。
     *
     * 这里回 24(只剩内边距)的话,调用方会当场把这一格量成 24px:
     * 水波 0 高、看不见、不报错。**静默塌陷比报错难查一百倍。**
     */
    @Test
    fun `一个老实孩子都没有时返回没意见`() {
        assertEquals(
            SlotMeasure.NO_OPINION,
            SlotMeasure.slotHeight(listOf(kid(1026, fillsParent = true)), 24),
        )
        assertEquals(SlotMeasure.NO_OPINION, SlotMeasure.slotHeight(emptyList(), 24))
    }

    /**
     * ★ [SlotMeasure.NO_OPINION] 必须是负数:它要是 0 或正数,
     * 就会有人(包括下一个我)把它当成一个真高度悄悄用掉,而它的意思是「没人说了算」。
     * 这条纯属防呆,但它便宜。
     */
    @Test
    fun `没意见是个负数`() {
        assertTrue("NO_OPINION 得是负数,否则会被当成真高度用掉", SlotMeasure.NO_OPINION < 0)
    }

    /** `GONE` 的孩子一个字都不算 —— **哪怕它是个老实孩子**(它是真的 0 高,不是 1026)。 */
    @Test
    fun `GONE 的孩子不算`() {
        assertEquals(141 + 24, SlotMeasure.slotHeight(listOf(kid(141), kid(900, gone = true)), 24))
    }

    /**
     * 上下边距要算进去。
     *
     * ⚠️ 这一条防的是「胶囊比里面那行字矮一点点」那种错:它不报错,
     * 表现是**最后一个字被切掉半截**。而字幕上那句话往往正是用户要看的那句。
     */
    @Test
    fun `孩子的上下边距算进去`() {
        assertEquals(141 + 10 + 6 + 24, SlotMeasure.slotHeight(listOf(kid(141, top = 10, bottom = 6)), 24))
    }

    /** 内边距是**加在外面**的,不是「最高那个孩子的高度」。 */
    @Test
    fun `内边距是加在外面的`() {
        assertEquals(100, SlotMeasure.slotHeight(listOf(kid(100)), paddingPx = 0))
        assertEquals(130, SlotMeasure.slotHeight(listOf(kid(100)), paddingPx = 30))
    }
}
