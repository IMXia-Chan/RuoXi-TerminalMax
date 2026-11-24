package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 角色之间那一句「追问」的纯逻辑。
 *
 * 为什么值得钉：**认错一条不会崩，只会多花一次钱**。
 * 而「不认」的代价是它想说的那块缺口没人补，成品里就那么空着。
 * 两个方向不对称，所以规矩要写死：只问在场的人、一个人只被问一次、总数封顶。
 */
class PeerMathTest {

    private fun ans(role: String, text: String) = FanoutMath.Answer(role, text)

    // ═════════════════════════════════ 一、认那一行

    @Test
    fun `标准写法`() {
        val q = PeerMath.parseLine("问@算：这三步里哪一步最贵")
        assertEquals("算", q?.first)
        assertEquals("这三步里哪一步最贵", q?.second)
    }

    @Test
    fun `全角冒号和全角at都认`() {
        val q = PeerMath.parseLine("问＠算：这一条算得对吗")
        assertEquals("算", q?.first)
    }

    @Test
    fun `前面带列表符号也认`() {
        val q = PeerMath.parseLine("- 问@审稿：这段话站得住吗")
        assertEquals("审稿", q?.first)
    }

    @Test
    fun `普通句子不算追问`() {
        assertNull(PeerMath.parseLine("我这里算完了，结论是三成"))
        assertNull(PeerMath.parseLine("问：这算不算"))
        assertNull(PeerMath.parseLine("问@：没写名字"))
        assertNull(PeerMath.parseLine("问@算："))
    }

    @Test
    fun `太长的不算追问 —— 那像第二份任务`() {
        assertNull(PeerMath.parseLine("问@算：" + "啊".repeat(PeerMath.MAX_Q + 1)))
    }

    // ═════════════════════════════════ 二、收的时候卡三条

    @Test
    fun `只认在场的人`() {
        val got = PeerMath.harvest(
            listOf(ans("算", "结论一\n问@老板：能不能多给点预算")),
            listOf("算", "审稿"),
        )
        assertTrue(got.isEmpty())
    }

    @Test
    fun `一个人只被问一次`() {
        val got = PeerMath.harvest(
            listOf(
                ans("算", "问@审稿：这句站得住吗"),
                ans("查", "问@审稿：还有别的出处吗"),
            ),
            listOf("算", "查", "审稿"),
        )
        assertEquals(1, got.size)
        assertEquals("算", got[0].from)
        assertEquals("审稿", got[0].to)
    }

    @Test
    fun `总数封顶`() {
        val got = PeerMath.harvest(
            listOf(
                ans("甲", "问@乙：一"),
                ans("乙", "问@丙：二"),
                ans("丙", "问@丁：三"),
            ),
            listOf("甲", "乙", "丙", "丁"),
        )
        assertEquals(PeerMath.MAX_ASKS, got.size)
    }

    @Test
    fun `不自己问自己`() {
        val got = PeerMath.harvest(
            listOf(ans("算", "问@算：我刚才算得对吗")),
            listOf("算", "审稿"),
        )
        assertTrue(got.isEmpty())
    }

    @Test
    fun `追问问出来的是谁问谁`() {
        val got = PeerMath.harvest(
            listOf(ans("审稿", "看着不错\n问@算：这数是怎么来的")),
            listOf("算", "审稿"),
        )
        assertEquals(1, got.size)
        assertEquals("审稿", got[0].from)
        assertEquals("算", got[0].to)
        assertEquals("这数是怎么来的", got[0].question)
    }

    // ═════════════════════════════════ 三、拼回正文

    @Test
    fun `没有追问就原样返回`() {
        assertEquals("一段话", PeerMath.attach("一段话", emptyList()))
    }

    @Test
    fun `回答接在提问者那一段后面`() {
        val got = PeerMath.attach("我算完了。", listOf("算" to "三成左右。"))
        assertTrue(got.startsWith("我算完了。"))
        assertTrue(got.contains("追问「算」的回答"))
        assertTrue(got.contains("三成左右。"))
    }
}
