package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 央媒首页的解析。
 *
 * ★ 这块最容易出的错是**安静地收错东西**：首页上除了新闻，还挂着导航、专题、几个月前的旧头条。
 *   所以每条断言都对着一个具体的筛选条件，而不是「能抠出几条算几条」。
 */
class OfficialMathTest {

    private val TODAY = "20261107"

    // ── 新华网 ──────────────────────────────────────────────────────────

    @Test
    fun `新华网相对链接`() {
        val html = """
            <a href="20261107/38688dbea1ee4c22963c2a1a0ce306df/c.html">泽连斯基称将继续打击俄炼油厂</a>
        """.trimIndent()
        val r = OfficialMath.fromXinhua(html, TODAY)
        assertEquals(1, r.size)
        assertEquals("泽连斯基称将继续打击俄炼油厂", r[0].title)
        assertEquals(
            "https://www.news.cn/20261107/38688dbea1ee4c22963c2a1a0ce306df/c.html",
            r[0].link,
        )
    }

    @Test
    fun `新华网带域名的链接也认`() {
        val html = """<a href="https://www.news.cn/20261107/38688dbea1ee4c22963c2a1a0ce306df/c.html">一条正经的新闻标题</a>"""
        assertEquals(1, OfficialMath.fromXinhua(html, TODAY).size)
    }

    @Test
    fun `新华网只收今天和昨天`() {
        val html = """
            <a href="20261107/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/c.html">今天的稿子</a>
            <a href="20261106/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb/c.html">昨天的稿子</a>
            <a href="20261105/cccccccccccccccccccccccccccccccc/c.html">前天的稿子</a>
            <a href="20260926/dddddddddddddddddddddddddddddddd/c.html">一个多月前的稿子</a>
        """.trimIndent()
        val r = OfficialMath.fromXinhua(html, TODAY)
        assertEquals(listOf("今天的稿子", "昨天的稿子"), r.map { it.title })
    }

    @Test
    fun `标题里的标签被剥掉`() {
        val html = """<a href="20261107/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/c.html"><em>注意</em>正文在后</a>"""
        assertEquals("注意 正文在后", OfficialMath.fromXinhua(html, TODAY)[0].title)
    }

    @Test
    fun `导航文字不算标题`() {
        val html = """
            <a href="20261107/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/c.html">更多</a>
            <a href="20261107/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb/c.html">专题</a>
            <a href="20261107/cccccccccccccccccccccccccccccccc/c.html">一条正经的新闻标题</a>
        """.trimIndent()
        assertEquals(listOf("一条正经的新闻标题"), OfficialMath.fromXinhua(html, TODAY).map { it.title })
    }

    @Test
    fun `太短太长都不收`() {
        val html = """
            <a href="20261107/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/c.html">短</a>
            <a href="20261107/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb/c.html">${"长".repeat(OfficialMath.MAX_LEN + 1)}</a>
        """.trimIndent()
        assertTrue(OfficialMath.fromXinhua(html, TODAY).isEmpty())
    }

    @Test
    fun `同名标题只收一条`() {
        val html = """
            <a href="20261107/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/c.html">同样一条新闻</a>
            <a href="20261107/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb/c.html">同样一条新闻</a>
        """.trimIndent()
        assertEquals(1, OfficialMath.fromXinhua(html, TODAY).size)
    }

    @Test
    fun `条数有上限`() {
        val html = (1..10).joinToString("") { i ->
            "<a href=\"20261107/${i.toString().padStart(32, 'a')}/c.html\">第${i}条新闻标题</a>"
        }
        assertEquals(3, OfficialMath.fromXinhua(html, TODAY, max = 3).size)
    }

    // ── 人民网 ──────────────────────────────────────────────────────────

    @Test
    fun `人民网年月日在三个片段里`() {
        val html = """
            <a href="http://politics.people.com.cn/n1/2026/1106/c1024-40829279.html">习近平参加北京市区人大代表换届选举投票</a>
        """.trimIndent()
        val r = OfficialMath.fromPeople(html, TODAY)
        assertEquals(1, r.size)
        assertEquals(
            "http://politics.people.com.cn/n1/2026/1106/c1024-40829279.html",
            r[0].link,
        )
    }

    @Test
    fun `人民网首页上挂着的旧头条要挡掉`() {
        val html = """
            <a href="http://politics.people.com.cn/n1/2026/1107/c1024-40830001.html">今天的新稿</a>
            <a href="http://politics.people.com.cn/n1/2026/0926/c1024-40806006.html">九月底的旧头条</a>
        """.trimIndent()
        assertEquals(listOf("今天的新稿"), OfficialMath.fromPeople(html, TODAY).map { it.title })
    }

    // ── 公共部分 ────────────────────────────────────────────────────────

    @Test
    fun `空页面不算出错`() {
        assertTrue(OfficialMath.fromXinhua("", TODAY).isEmpty())
        assertTrue(OfficialMath.fromPeople("", TODAY).isEmpty())
        assertTrue(OfficialMath.fromXinhua("<html><body>什么都没有</body></html>", TODAY).isEmpty())
    }

    @Test
    fun `今天的日期解析不了就一条都不收`() {
        val html = """<a href="20261107/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/c.html">一条正经的新闻标题</a>"""
        assertTrue(OfficialMath.fromXinhua(html, "不是日期").isEmpty())
    }

    @Test
    fun `最近两天`() {
        assertEquals(setOf("20261107", "20261106"), OfficialMath.recentDays(TODAY, 2))
        assertEquals(setOf("20261101"), OfficialMath.recentDays("20261101", 1))
    }

    @Test
    fun `空白压平`() {
        assertEquals("甲 乙", OfficialMath.clean("  甲 \n 乙 "))
    }

    @Test
    fun `来源决定算不算央媒`() {
        val xh = NewsItem(1, "标题", "", "", OfficialNews.XINHUA)
        val ppl = NewsItem(1, "标题", "", "", OfficialNews.PEOPLE)
        val baidu = NewsItem(1, "标题", "", "", NewsFeed.BAIDU)
        assertTrue(xh.official)
        assertTrue(ppl.official)
        assertFalse(baidu.official)
    }
}
