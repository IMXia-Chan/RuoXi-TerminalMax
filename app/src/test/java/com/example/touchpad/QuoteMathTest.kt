package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在线语录那一路的解析。
 *
 * ★ 这块屏最难看的一种坏法是「**显示正常，但永远是同一句**」：
 *   去重写错（比如按出处去重），池子就攒不起来，屏上翻来翻去还是那几条。
 *   所以去重按什么、截断怎么截、中英怎么交替，都得钉住。
 */
class QuoteMathTest {

    private fun q(t: String, from: String = "出处", lang: String = "zh") =
        Quotes.Quote(t, from, lang)

    // ── 一言 ────────────────────────────────────────────────────────────

    @Test
    fun `一言那种壳子`() {
        val t = """{"id":1,"hitokoto":"丢掉幻想，准备斗争。","type":"k","from":"毛泽东选集","from_who":"毛泽东"}"""
        val r = QuoteMath.zhFromHitokoto(t)!!
        assertEquals("丢掉幻想，准备斗争。", r.text)
        assertEquals("毛泽东", r.from)
        assertEquals("zh", r.lang)
    }

    @Test
    fun `没有作者就用书名`() {
        val t = """{"hitokoto":"路漫漫其修远兮。","from":"离骚","from_who":""}"""
        assertEquals("离骚", QuoteMath.zhFromHitokoto(t)!!.from)
    }

    @Test
    fun `两者都没有就写一言`() {
        val t = """{"hitokoto":"一句没有出处的话。","from":"","from_who":""}"""
        assertEquals("一言", QuoteMath.zhFromHitokoto(t)!!.from)
    }

    @Test
    fun `正文是空的不收`() {
        assertNull(QuoteMath.zhFromHitokoto("""{"hitokoto":"  "}"""))
        assertNull(QuoteMath.zhFromHitokoto("{不是 JSON"))
    }

    // ── 今日诗词 ────────────────────────────────────────────────────────

    @Test
    fun `今日诗词那种壳子`() {
        val t = """{"content":"昼夜蔽日月，冬夏共霜雪。","origin":"登庐山绝顶望诸峤","author":"谢灵运","category":"古诗文-山水-庐山"}"""
        val r = QuoteMath.zhFromPoem(t)!!
        assertEquals("昼夜蔽日月，冬夏共霜雪。", r.text)
        assertEquals("谢灵运《登庐山绝顶望诸峤》", r.from)
        assertEquals("zh", r.lang)
    }

    @Test
    fun `诗词只有作者也认`() {
        val t = """{"content":"海上生明月，天涯共此时。","origin":"","author":"张九龄"}"""
        assertEquals("张九龄", QuoteMath.zhFromPoem(t)!!.from)
    }

    @Test
    fun `诗词没有正文就不收`() {
        assertNull(QuoteMath.zhFromPoem("""{"content":" "}"""))
        assertNull(QuoteMath.zhFromPoem("{不是 JSON"))
    }

    // ── 斯多葛 ──────────────────────────────────────────────────────────

    @Test
    fun `stoic 那种壳子`() {
        val t = """{"text":"No man is free who is not master of himself.","author":"Epictetus"}"""
        val r = QuoteMath.enFromStoic(t)!!
        assertEquals("No man is free who is not master of himself.", r.text)
        assertEquals("Epictetus", r.from)
        assertEquals("en", r.lang)
    }

    @Test
    fun `stoic 正文是空的不收`() {
        assertNull(QuoteMath.enFromStoic("""{"text":""}"""))
        assertNull(QuoteMath.enFromStoic("[]"))
    }

    // ── 英文那一路 ──────────────────────────────────────────────────────

    @Test
    fun `dummyjson 那种壳子`() {
        val t = """{"quotes":[
            {"id":1,"quote":"Knowledge is power.","author":"Francis Bacon"},
            {"id":2,"quote":"I think, therefore I am.","author":"René Descartes"}]}"""
        val r = QuoteMath.enFromDummy(t)
        assertEquals(2, r.size)
        assertEquals("en", r[0].lang)
        assertEquals("Francis Bacon", r[0].from)
    }

    @Test
    fun `英文里的换行被压平`() {
        val t = """{"quotes":[{"quote":"First  line\n  second","author":"X"}]}"""
        assertEquals("First line second", QuoteMath.enFromDummy(t)[0].text)
    }

    @Test
    fun `没有作者的英文写 Unknown`() {
        val t = """{"quotes":[{"quote":"Anonymous.","author":""}]}"""
        assertEquals("Unknown", QuoteMath.enFromDummy(t)[0].from)
    }

    @Test
    fun `坏 JSON 返回空表而不是抛`() {
        assertTrue(QuoteMath.enFromDummy("{不是 JSON").isEmpty())
        assertTrue(QuoteMath.enFromDummy("").isEmpty())
    }

    // ── 长度把关 ────────────────────────────────────────────────────────

    @Test
    fun `中文太长不收`() {
        assertTrue(QuoteMath.keep(q("甲".repeat(QuoteMath.MAX_ZH))))
        assertTrue(!QuoteMath.keep(q("甲".repeat(QuoteMath.MAX_ZH + 1))))
    }

    @Test
    fun `英文按字符数另算`() {
        assertTrue(QuoteMath.keep(q("a".repeat(QuoteMath.MAX_EN), lang = "en")))
        assertTrue(!QuoteMath.keep(q("a".repeat(QuoteMath.MAX_EN + 1), lang = "en")))
    }

    @Test
    fun `空的不收`() {
        assertTrue(!QuoteMath.keep(q("   ")))
    }

    // ── 合并去重 ────────────────────────────────────────────────────────

    @Test
    fun `同样的正文只留一条`() {
        val m = QuoteMath.merge(listOf(q("Knowledge is power.")), listOf(q("knowledge  IS power.")))
        assertEquals(1, m.size)
    }

    @Test
    fun `老的排在前面`() {
        val m = QuoteMath.merge(listOf(q("旧")), listOf(q("新")))
        assertEquals(listOf("旧", "新"), m.map { it.text })
    }

    @Test
    fun `截到上限`() {
        val old = (1..30).map { q("旧$it") }
        val fresh = (1..30).map { q("新$it") }
        assertEquals(10, QuoteMath.merge(old, fresh, 10).size)
    }

    @Test
    fun `只在末尾截不挖洞`() {
        val m = QuoteMath.merge(listOf(q("甲"), q("乙")), listOf(q("乙"), q("丙")), 5)
        assertEquals(listOf("甲", "乙", "丙"), m.map { it.text })
    }

    // ── 中英交替 ────────────────────────────────────────────────────────

    @Test
    fun `中英交替排`() {
        val list = listOf(q("中1"), q("中2"), q("en1", lang = "en"), q("en2", lang = "en"))
        assertEquals(listOf("中1", "en1", "中2", "en2"), QuoteMath.interleave(list).map { it.text })
    }

    @Test
    fun `一边多也不会丢`() {
        val list = listOf(q("中1"), q("中2"), q("中3"), q("en1", lang = "en"))
        assertEquals(4, QuoteMath.interleave(list).size)
    }

    @Test
    fun `空表交替还是空表`() {
        assertTrue(QuoteMath.interleave(emptyList()).isEmpty())
    }

    // ── 落盘 ────────────────────────────────────────────────────────────

    @Test
    fun `存下来再读回来一条不少`() {
        val list = listOf(q("甲", "《书》"), q("Mind over matter.", "Anon", "en"))
        val back = QuoteMath.fromJson(
            org.json.JSONObject().put("items", QuoteMath.toJson(list))
        )
        assertEquals(list.map { it.text }, back.map { it.text })
        assertEquals("en", back[1].lang)
    }
}
