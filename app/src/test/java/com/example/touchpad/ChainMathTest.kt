package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainMathTest {

    @Test
    fun `拆单保持原顺序`() {
        val p = ChainMath.parse(listOf("检索员|先把原文找全", "编辑|照原文写成三百字", "审稿人|挑站不住的地方"))
        assertEquals(3, p.stages.size)
        assertEquals(listOf("检索员", "编辑", "审稿人"), p.stages.map { it.role })
        assertEquals("先把原文找全", p.stages[0].task)
        assertTrue(p.problems.isEmpty())
    }

    @Test
    fun `同一个岗位可以隔一段再上手`() {
        // 改 → 挑刺 → 再改，是真实走法。扇出那边重名要合并，接力这边不能合并。
        val p = ChainMath.parse(listOf("编辑|改成书面语气", "审稿人|挑毛病", "编辑|照审稿意见改完"))
        assertEquals(3, p.stages.size)
        assertEquals("编辑", p.stages[2].role)
        assertTrue(p.problems.isEmpty())
    }

    @Test
    fun `只有一段不算接力`() {
        val p = ChainMath.parse(listOf("编辑|改一下"))
        assertEquals(1, p.stages.size)
        assertTrue(p.problems.any { it.contains("至少") })
    }

    @Test
    fun `全角竖线也认`() {
        val p = ChainMath.parse(listOf("检索员｜找原文", "编辑｜写成三百字"))
        assertEquals(listOf("检索员", "编辑"), p.stages.map { it.role })
    }

    @Test
    fun `斜杠也认 —— 手机上打不出竖线`() {
        val p = ChainMath.parse(listOf("检索员/找原文", "编辑/写成三百字"))
        assertEquals(listOf("检索员", "编辑"), p.stages.map { it.role })
        assertEquals("找原文", p.stages[0].task)
    }

    @Test
    fun `收尾的列表符号和空行不算一段`() {
        val p = ChainMath.parse(listOf("- 检索员|找原文", "", "  ", "• 编辑|写成三百字"))
        assertEquals(2, p.stages.size)
    }

    @Test
    fun `超过上限的部分不派，并且说出来`() {
        val raw = (1..6).map { "岗位$it|第 $it 件事" }
        val p = ChainMath.parse(raw)
        assertEquals(ChainMath.MAX_STAGES, p.stages.size)
        assertTrue(p.problems.any { it.contains("超过") })
    }

    @Test
    fun `没写这一段做什么的那一段被跳过`() {
        val p = ChainMath.parse(listOf("检索员|找原文", "编辑|", "审稿人|挑毛病"))
        assertEquals(2, p.stages.size)
        assertTrue(p.problems.any { it.contains("第 2 段") })
    }

    @Test
    fun `第一段不带上一段的块`() {
        val s = FanoutMath.Spec("检索员", "找原文")
        val text = ChainMath.promptFor("把这版方案写短", null, s, 1, 3)
        assertTrue(text.contains("【整件事】"))
        assertFalse(text.contains("【上一段交回来的东西】"))
        assertTrue(text.contains("第 1 段，共 3 段"))
    }

    @Test
    fun `中间段带上上一段的产出`() {
        val s = FanoutMath.Spec("编辑", "照原文写成三百字")
        val text = ChainMath.promptFor("把这版方案写短", "这里是检索员找回来的三段原文", s, 2, 3)
        assertTrue(text.contains("【上一段交回来的东西】"))
        assertTrue(text.contains("检索员找回来的三段原文"))
        assertTrue(text.contains("照原文写成三百字"))
        // 中间段明确「别替下一段干活」
        assertTrue(text.contains("别替后面几段干活"))
    }

    @Test
    fun `最后一段要成品不要提纲`() {
        val s = FanoutMath.Spec("审稿人", "挑毛病")
        val text = ChainMath.promptFor("整件事", "上一段的产出", s, 3, 3)
        assertTrue(text.contains("最后一段"))
        assertTrue(text.contains("成品"))
    }

    @Test
    fun `上一段太长要截断，而且截断这件事要说出来`() {
        val s = FanoutMath.Spec("编辑", "写")
        val long = "字".repeat(ChainMath.PREV_BUDGET + 500)
        val text = ChainMath.promptFor("整件事", long, s, 2, 2)
        assertTrue(text.contains("这里截断了"))
        assertTrue(text.contains("还有 500 字"))
        // 正文里不该真的塞进那 500 字
        assertFalse(text.contains("字".repeat(ChainMath.PREV_BUDGET + 1)))
    }

    @Test
    fun `链路名字按顺序写`() {
        val p = ChainMath.parse(listOf("检索员|a", "编辑|b", "审稿人|c"))
        assertEquals("检索员 → 编辑 → 审稿人", ChainMath.plan(p.stages))
    }
}
