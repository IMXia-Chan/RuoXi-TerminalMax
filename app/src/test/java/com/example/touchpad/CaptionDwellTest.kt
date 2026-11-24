package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「占位话最短停留」那条算术的测试。
 *
 * 防的错**全都不抛异常**:错了的表现是「那句话还是一闪而过」或者反过来
 * 「字幕卡死在『在认你说的话…』上,真话永远不上来」。
 * 后一种更难看 —— 它长得像**她没听见你说话**,而真机上要靠翻日志才分得清。
 */
class CaptionDwellTest {

    private val MIN = CaptionDwell.MIN_MS

    /** 不是占位话就别拦:她正常答一句话,不该被任何东西排队。 */
    @Test
    fun `不是占位话_一秒都不等`() {
        assertEquals(0L, CaptionDwell.waitMs(placeholderShowing = false, sinceMs = 0, nowMs = 0))
        assertEquals(0L, CaptionDwell.waitMs(placeholderShowing = false, sinceMs = 5_000, nowMs = 5_001))
    }

    /** 转写慢(常见情况):占位话早看够了,真话立刻上 —— 这条保证「改了没影响正常路径」。 */
    @Test
    fun `占位话已经待够_立刻放行`() {
        assertEquals(0L, CaptionDwell.waitMs(true, sinceMs = 1_000, nowMs = 1_000 + MIN))
        assertEquals(0L, CaptionDwell.waitMs(true, sinceMs = 1_000, nowMs = 1_000 + MIN + 5_000))
    }

    /** 转写快(要修的那一种):真话排队,等它把 1.2 秒站满。 */
    @Test
    fun `占位话刚上去_真话要排队`() {
        assertEquals(MIN, CaptionDwell.waitMs(true, sinceMs = 1_000, nowMs = 1_000))
        assertEquals(MIN - 300, CaptionDwell.waitMs(true, sinceMs = 1_000, nowMs = 1_300))
    }

    /**
     * ★ 边界:差一毫秒还在等,正好到点就放行。
     *
     * 这一对是**半开区间**的钉子。写成 `<=` 和 `<` 在这里看不出区别,
     * 但配合下面那条时钟倒流一起看:负数必须走「放行」那一侧,
     * 因为它唯一的来源是**时钟被重设**,而不是「还没待够」。
     */
    @Test
    fun `边界_差一毫秒要等_正好到点放行`() {
        assertEquals(1L, CaptionDwell.waitMs(true, sinceMs = 1_000, nowMs = 1_000 + MIN - 1))
        assertEquals(0L, CaptionDwell.waitMs(true, sinceMs = 1_000, nowMs = 1_000 + MIN))
    }

    /**
     * ★★ 时钟被重设(或 `sinceMs` 根本没写成、还是 0)。
     *
     * 这里**必须返回 0**,不能返回一个「按差值算出来」的等待:
     * 那会是个几万毫秒的等待,字幕就**卡死在占位话上**,真话永远不上来 ——
     * 而他看到的是「我说完话,屏幕上一直写着『在认你说的话…』」,
     * 那读起来就是**她卡住了**。宁可不拦。
     */
    @Test
    fun `时钟倒流或没置过_不许拦`() {
        assertEquals(0L, CaptionDwell.waitMs(true, sinceMs = 9_000, nowMs = 1_000))
        assertEquals(0L, CaptionDwell.waitMs(true, sinceMs = 0, nowMs = -1))
    }

    /** 常量本身也钉一下:它变了,上面几条的相对判断会跟着变,但绝对值得有据可依。 */
    @Test
    fun `最短停留是1200毫秒`() {
        assertEquals(1_200L, MIN)
    }
}
