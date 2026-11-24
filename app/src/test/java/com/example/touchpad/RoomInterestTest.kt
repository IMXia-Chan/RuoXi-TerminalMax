package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「他常看什么」那本账的算术。
 *
 * ★ 这些错的症状是**推荐不准**，不报错、日志干净 —— 所以必须钉在纯 JVM 上。
 *   尤其是 [InterestMath.score] 那一条归一化：不除词数的话长标题会通吃，
 *   而那是「越用越不像你」的开始。
 */
class RoomInterestTest {

    // ── 分词 ────────────────────────────────────────────────────────────

    @Test
    fun `中文按二元组切`() {
        val t = InterestMath.tokens("国产大模型")
        assertEquals(listOf("国产", "产大", "大模", "模型"), t)
    }

    @Test
    fun `含虚字的二元组被丢掉`() {
        // 「模型」留着，「型的」不要
        val t = InterestMath.tokens("模型的")
        assertTrue(t.contains("模型"))
        assertTrue(t.none { it.contains('的') })
    }

    @Test
    fun `纯数字不成为词`() {
        val t = InterestMath.tokens("2026 年 99 元")
        assertTrue(t.none { it.all { c -> c.isDigit() } })
    }

    @Test
    fun `英文按整词小写`() {
        val t = InterestMath.tokens("OpenAI 发布 GPT")
        assertTrue(t.contains("openai"))
        assertTrue(t.contains("gpt"))
    }

    @Test
    fun `标点不会把词切坏`() {
        val a = InterestMath.tokens("台风登陆：停课")
        val b = InterestMath.tokens("台风登陆 停课")
        assertEquals(b, a)
    }

    @Test
    fun `空标题切出空表`() {
        assertTrue(InterestMath.tokens("").isEmpty())
        assertTrue(InterestMath.tokens("   ").isEmpty())
    }

    // ── 打分 ────────────────────────────────────────────────────────────

    @Test
    fun `没有画像时任何一条都是零分`() {
        assertEquals(0f, InterestMath.score("国产大模型开源", emptyMap()), 1e-6f)
    }

    @Test
    fun `长标题不会因为词多就赢`() {
        val profile = mapOf("模型" to 4f)
        // 短的那条只有一个词命中，但词数少 —— 归一化之后它更高
        val short = InterestMath.score("模型", profile)
        val long = InterestMath.score("今天天气不错适合出去走走看看那些模型到底怎么样了吧", profile)
        assertTrue("短句应该不低于长句: $short vs $long", short >= long)
    }

    @Test
    fun `画像里有的词会加分`() {
        val profile = mapOf("模型" to 3f)
        assertTrue(InterestMath.score("大模型开源", profile) > 0f)
        assertTrue(InterestMath.score("猪肉价格上涨", profile) == 0f)
    }

    // ── 排序 ────────────────────────────────────────────────────────────

    private fun n(rank: Int, title: String) =
        NewsItem(rank, title, "", "https://example.com/$rank", NewsFeed.BAIDU)

    @Test
    fun `没有画像时按榜单名次排`() {
        val items = listOf(n(3, "丙"), n(1, "甲"), n(2, "乙"))
        val r = InterestMath.rank(items, emptyMap())
        assertEquals(listOf("甲", "乙", "丙"), r.map { it.title })
    }

    @Test
    fun `喜欢的同类会被顶上来`() {
        val items = listOf(n(1, "猪肉价格上涨"), n(40, "国产大模型开源周"))
        val r = InterestMath.rank(items, mapOf("模型" to 6f))
        assertEquals("国产大模型开源周", r.first().title)
    }

    @Test
    fun `点开过的沉到最后`() {
        val items = listOf(n(1, "甲"), n(2, "乙"))
        val opened = setOf(InterestMath.key("甲"))
        val r = InterestMath.rank(items, emptyMap(), opened)
        assertEquals("乙", r.first().title)
        assertEquals("甲", r.last().title)
    }

    @Test
    fun `限长生效`() {
        val items = (1..40).map { n(it, "第${it}条新闻") }
        assertEquals(5, InterestMath.rank(items, emptyMap(), emptySet(), 5).size)
    }

    @Test
    fun `同分时按名次`() {
        val items = listOf(n(9, "新闻九"), n(2, "新闻二"))
        val r = InterestMath.rank(items, emptyMap())
        assertEquals(2, r.first().rank)
    }

    // ── 衰减 ────────────────────────────────────────────────────────────

    @Test
    fun `半衰期到了就剩一半`() {
        val p = mapOf("模型" to 4f)
        val d = InterestMath.decay(p, InterestMath.HALF_LIFE_MS)
        assertEquals(2f, d["模型"]!!, 1e-4f)
    }

    @Test
    fun `时钟往回拨不会把账清零`() {
        val p = mapOf("模型" to 4f)
        assertEquals(p, InterestMath.decay(p, -5_000L))
        assertEquals(p, InterestMath.decay(p, 0L))
    }

    @Test
    fun `衰减到底的词会被丢掉`() {
        val p = mapOf("模型" to 0.00001f)
        val d = InterestMath.decay(p, InterestMath.HALF_LIFE_MS * 40)
        assertTrue(d.isEmpty())
    }

    // ── 记号 ────────────────────────────────────────────────────────────

    @Test
    fun `同一条标题的记号稳定`() {
        assertEquals(InterestMath.key("台风登陆"), InterestMath.key("台风登陆"))
        assertEquals(InterestMath.key("台风登陆"), InterestMath.key("  台风登陆 "))
    }

    @Test
    fun `不同标题的记号不同`() {
        assertNotEquals(InterestMath.key("甲"), InterestMath.key("乙"))
    }
}
