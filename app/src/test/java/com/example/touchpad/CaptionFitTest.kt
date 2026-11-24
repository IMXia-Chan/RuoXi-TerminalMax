package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「她的字幕装不装得下」的钉子。
 *
 * ★ 为什么这组测试**必须**存在(2026-10-05 晚):
 *
 * 用户报的那条是:
 * > 「**最后是以省略号的形式出现**,那太不好了,所以,**要么把字做小一点,
 * >  不然不能全显示出来**」
 *
 * 它是**看着屏幕才发现的**,不是日志能告诉我们的 —— 而这一轮**手机拔着**,
 * 装不了机、也截不了图。所以「多大的句子用多大的字」只能在这儿咬住。
 * **这里没钉住的行为,一律等于没做。**
 *
 * ★ 挑用例的判据不是「边界条件的趣味」,是**每一档在屏幕上长什么样**:
 *
 * | 算错了会怎样 | 屏幕上长什么样 |
 * |---|---|
 * | 长句不肯收字 | 末尾变 `…`,最后那句真话读不到(就是这次报的) |
 * | 短句跟着一起收 | 一句「嗯,好」被缩得看不清 |
 * | 收了字却还卡三行 | 治了等于没治,症状一模一样 |
 * | 收得没底 | 极长的回答缩成一片看不清的小字,比省略号还糟 |
 */
class CaptionFitTest {

    /** 她定稿那句的基准字号(见 `ConMarnActivity.sayCaptionOnly`)。 */
    private val herBase = 16f

    /** 他说的 / 她的草稿的基准字号。 */
    private val draftBase = 14f

    // ---------------------------------------------------------------- 装得下

    @Test
    fun `短句一个字都不收 原样是基准字号`() {
        // 「嗯,好」「我在呢」这种 —— 它们本来就一行装得下,收小纯粹是难看。
        assertEquals(herBase, CaptionFit.textSize(herBase, 0), 0.001f)
        assertEquals(herBase, CaptionFit.textSize(herBase, 12), 0.001f)
        assertEquals(herBase, CaptionFit.textSize(herBase, 40), 0.001f)
    }

    @Test
    fun `越长的句子字越小 而且是单调的`() {
        // ★ 单调 = 「更长的句子绝不会用更大的字」。中间任何一处反了,
        //   屏幕上就是「同一段回答打到某处突然涨大」,像抽搐。
        var prev = CaptionFit.textSize(herBase, 0)
        for (n in 1..600 step 7) {
            val now = CaptionFit.textSize(herBase, n)
            assertTrue("★ $n 字时字反而变大了:$prev → $now", now <= prev + 0.001f)
            prev = now
        }
    }

    @Test
    fun `真正长的那一句收到最低档 这是这次报的那条`() {
        // 一次回答 200 字以上是常事(他报的就是这种被切成省略号的)。
        assertEquals(herBase - 4f, CaptionFit.textSize(herBase, 201), 0.001f)
        assertEquals(herBase - 4f, CaptionFit.textSize(herBase, 900), 0.001f)
    }

    // ---------------------------------------------------------------- 天花板

    @Test
    fun `三行抬到了七行 光收字是不够的`() {
        // ★★ 这一条是**这次修复的另一半**。只收字不抬行数的话,
        //   长的照样在第 3 行被 `…` 截掉 —— 症状和用户报的一模一样。
        assertEquals(7, CaptionFit.MAX_LINES)
        assertTrue("★ 天花板没抬,那次修复只做了一半", CaptionFit.MAX_LINES > 3)
    }

    @Test
    fun `天花板跟句子长短无关 它是天花板不是目标`() {
        // ★ `herSay` 是 WRAP_CONTENT —— 短句就占一行。
        //   若这里按长度分档,短句会被**撑**成好几行,那就是「占一大片屏」。
        assertEquals(CaptionFit.MAX_LINES, CaptionFit.maxLines(0))
        assertEquals(CaptionFit.MAX_LINES, CaptionFit.maxLines(50))
        assertEquals(CaptionFit.MAX_LINES, CaptionFit.maxLines(9999))
    }

    // ---------------------------------------------------------------- 不许收没底

    @Test
    fun `收到底就不再收 宁可留一个看得见的省略号`() {
        // 「看不清的满屏小字」比「末尾一个省略号」更糟 —— 省略号至少在说
        // 「这里还有,我装不下」,而缩成一团的字什么都没说,只是读不了。
        for (n in listOf(300, 10_000, Int.MAX_VALUE)) {
            val size = CaptionFit.textSize(herBase, n)
            assertTrue("★ $n 字时缩到了 ${size}sp,看不清了", size >= CaptionFit.MIN_TEXT_SIZE)
        }
    }

    @Test
    fun `基准字号小的那一档也跟着收 但不会跌到底`() {
        // 他说的 / 她的草稿是 14 起。★ 既有设计里「她的话大、他的话小」这一层
        //   一个字都不许动 —— 这一层只在它之上收小。
        assertEquals(14f, CaptionFit.textSize(draftBase, 20), 0.001f)
        assertEquals(10f, CaptionFit.textSize(draftBase, 500), 0.001f)
        assertTrue(CaptionFit.textSize(draftBase, 500) >= CaptionFit.MIN_TEXT_SIZE)
    }

    @Test
    fun `她的话收到底仍然不小于他的话收到底 谁大谁小没被翻过来`() {
        // ★ 这是对「既有设计」的守卫:他可以接受字变小,
        //   但「一眼分得清谁在说」那层区分不能被这一改弄反。
        val long = 500
        assertTrue(CaptionFit.textSize(herBase, long) > CaptionFit.textSize(draftBase, long))
    }

    // ---------------------------------------------------------------- 分档的接缝

    @Test
    fun `每一档的接缝两边都得有值 而且差一档就小一档`() {
        // 档位写错一个 `<=` 的症状是「某个长度区间里字突然大回去」——
        // 单测之外看不见。逐档对一遍。
        val seams = listOf(40 to 41, 80 to 81, 130 to 131, 200 to 201)
        for ((below, above) in seams) {
            val a = CaptionFit.textSize(herBase, below)
            val b = CaptionFit.textSize(herBase, above)
            assertEquals("★ $below → $above 这一档没接上", a - 1f, b, 0.001f)
        }
    }

    @Test
    fun `同样的长度永远同样的字号`() {
        repeat(5) { assertEquals(CaptionFit.textSize(herBase, 137), CaptionFit.textSize(herBase, 137), 0.001f) }
    }

    @Test
    fun `负数不算数 也不许崩`() {
        // 防御性一条:`herSayTarget.length` 正常不会是负的,但这个函数
        // 是纯逻辑,调用方可能从别处算长度传进来。
        assertEquals(herBase, CaptionFit.textSize(herBase, -1), 0.001f)
    }
}
