package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 热点那一路的解析。
 *
 * ★ 这块屏最难看的一种坏法是「**看着正常，其实一个字段都没读到**」：
 *   换一家镜像、多套一层壳，`optString` 一律返回空串，屏上于是三条空白。
 *   所以三种壳子都得钉住，读不到的时候**必须返回空表**（好让调用方留缓存）。
 */
class NewsMathTest {

    @Test
    fun `今日热榜那种壳子`() {
        val t = """{"code":200,"data":[
            {"index":1,"title":"甲","hotValue":"780.9万","link":"https://a"},
            {"index":2,"title":"乙","hotValue":"12万","link":"https://b"}]}"""
        val r = NewsMath.parse(t)
        assertEquals(2, r.size)
        assertEquals("甲", r[0].title)
        assertEquals(1, r[0].rank)
        assertEquals("780.9万", r[0].hot)
        assertEquals("https://a", r[0].link)
    }

    @Test
    fun `套一层 list 的镜像`() {
        val t = """{"data":{"list":[{"index":1,"title":"甲"}]}}"""
        assertEquals(1, NewsMath.parse(t).size)
    }

    @Test
    fun `直接给数组`() {
        val t = """[{"title":"甲"},{"title":"乙"}]"""
        assertEquals(2, NewsMath.parse(t).size)
    }

    @Test
    fun `坏 JSON 返回空表而不是抛`() {
        assertTrue(NewsMath.parse("{不是 JSON").isEmpty())
        assertTrue(NewsMath.parse("").isEmpty())
        assertTrue(NewsMath.parse("   ").isEmpty())
    }

    @Test
    fun `没有名次就按位置排`() {
        val t = """{"data":[{"title":"甲"},{"title":"乙"},{"title":"丙"}]}"""
        val r = NewsMath.parse(t)
        assertEquals(listOf(1, 2, 3), r.map { it.rank })
    }

    @Test
    fun `没有标题的条目要丢掉`() {
        val t = """{"data":[{"title":"甲"},{"hotValue":"1万"},{"title":""}]}"""
        val r = NewsMath.parse(t)
        assertEquals(1, r.size)
        assertEquals("甲", r[0].title)
    }

    @Test
    fun `最多只留 MAX_ITEMS 条`() {
        val body = (1..100).joinToString(",") { """{"title":"第${it}条"}""" }
        assertEquals(NewsMath.MAX_ITEMS, NewsMath.parse("""{"data":[$body]}""").size)
    }

    @Test
    fun `热度里的长数字压成万`() {
        assertEquals("780.9万", NewsMath.hotText("7809000"))
        assertEquals("12.0万", NewsMath.hotText("120000"))
    }

    @Test
    fun `已经是万的不动`() {
        assertEquals("780.9万", NewsMath.hotText("780.9万"))
        assertEquals("1.2亿", NewsMath.hotText("1.2亿"))
    }

    @Test
    fun `空热度给空串`() {
        assertEquals("", NewsMath.hotText(""))
        assertEquals("", NewsMath.hotText("   "))
    }

    @Test
    fun `标题里的换行和连空格被压平`() {
        assertEquals("甲 乙", NewsMath.clip("甲\n\n   乙"))
    }

    @Test
    fun `超长标题截断并加省略号`() {
        val s = "一".repeat(60)
        val c = NewsMath.clip(s, 10)
        assertEquals(10, c.length)
        assertTrue(c.endsWith("…"))
    }

    @Test
    fun `不超长的一个字都不动`() {
        assertEquals("台风登陆", NewsMath.clip("台风登陆", 10))
    }

    @Test
    fun `打来源榜`() {
        val items = NewsMath.parse("""{"data":[{"title":"甲"}]}""")
        val tagged = NewsMath.withBoard(items, NewsFeed.WEIBO)
        assertEquals(NewsFeed.WEIBO, tagged[0].board)
    }
}
