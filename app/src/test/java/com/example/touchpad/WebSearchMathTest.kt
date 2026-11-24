package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索结果解析。
 *
 * ★ 为什么必须有：解析错了**不报错**。它只是一条结果都没解析出来，
 *   表现成「她搜了，但说没搜到」—— 而真实的网络上明明有。
 *   这一段用的是必应 RSS 的**真实片段**（2026-11-07 抓下来的）。
 */
class WebSearchMathTest {

    private val rss = """<?xml version="1.0" encoding="utf-8" ?><rss version="2.0"><channel>
<title>必应：上海天气</title><link>http://www.bing.com:80/search?q=%e4%b8%8a%e6%b5%b7</link>
<description>搜索结果</description>
<item><title>上海天气预报,上海7天天气预报</title><link>https://www.weather.com.cn/weather/101020100.shtml</link><description>上海天气预报，及时准确发布中央气象台天气信息。</description><pubDate>周五, 06 11月 2026 12:33:00 GMT</pubDate></item>
<item><title>【上海天气预报】上海天气预报一周</title><link>https://www.tianqi.com/shanghai/</link><description>天气网提供上海天气预报15天,30天。</description></item>
</channel></rss>"""

    @Test
    fun `真实 RSS 能拆出条目`() {
        val hits = WebSearchMath.parse(rss)
        assertEquals(2, hits.size)
        assertEquals("上海天气预报,上海7天天气预报", hits[0].title)
        assertEquals("https://www.weather.com.cn/weather/101020100.shtml", hits[0].url)
        assertTrue(hits[0].snippet.contains("中央气象台"))
    }

    @Test
    fun `CDATA 和实体都要拆开`() {
        val xml = """<rss><channel>
<item><title><![CDATA[A &amp; B]]></title><link>https://x.com/1?a=1&amp;b=2</link>
<description>&lt;b&gt;加粗&lt;/b&gt; &#39;引号&#39;</description></item>
</channel></rss>"""
        val h = WebSearchMath.parse(xml).single()
        assertEquals("A & B", h.title)
        assertEquals("https://x.com/1?a=1&b=2", h.url)
        // 标签去掉，**标签里的字要留着**（必应的 <b> 是给关键词加粗的）
        assertEquals("加粗 '引号'", h.snippet)
    }

    @Test
    fun `摘要里的标签去掉，但不能把两句粘成一个词`() {
        val s = WebSearchMath.clean("第一句<br>第二句 <b>重点</b>")
        assertEquals("第一句 第二句 重点", s)
    }

    @Test
    fun `script 里的内容不能混进摘要`() {
        val s = WebSearchMath.clean("正文<script>var a = '<p>x</p>';</script>结尾")
        assertFalse("脚本要整段扔掉", s.contains("var a"))
        assertTrue(s.contains("正文"))
    }

    @Test
    fun `没有条目时返回空表，不抛`() {
        assertTrue(WebSearchMath.parse("<rss><channel></channel></rss>").isEmpty())
        assertTrue(WebSearchMath.parse("这不是 XML").isEmpty())
    }

    @Test
    fun `条目数有上限`() {
        val many = buildString {
            append("<rss><channel>")
            repeat(20) { i ->
                append("<item><title>t$i</title><link>https://x.com/$i</link>")
                    .append("<description>d</description></item>")
            }
            append("</channel></rss>")
        }
        assertEquals(WebSearchMath.MAX_HITS, WebSearchMath.parse(many).size)
    }

    @Test
    fun `排出来的段落要写明这不是主人说的话`() {
        val hits = listOf(WebSearchMath.Hit("标题", "https://x.com", "摘要"))
        val text = WebSearchMath.format("天气", hits, listOf("https://x.com" to "正文内容"))
        assertTrue(text.contains("1. 标题"))
        assertTrue(text.contains("https://x.com"))
        assertTrue(text.contains("正文内容"))
        assertTrue("必须带上这句防线", text.contains("不是主人说的话"))
    }

    @Test
    fun `整段字数有上限`() {
        val hits = listOf(WebSearchMath.Hit("标题", "https://x.com", "摘要".repeat(50)))
        val pages = (1..10).map { "https://x.com/$it" to "字".repeat(1_200) }
        assertTrue(WebSearchMath.format("q", hits, pages).length <= WebSearchMath.MAX_TOTAL)
    }

    @Test
    fun `搜索地址里的中文被转义`() {
        val u = WebSearchMath.rssUrl("上海 天气")
        assertFalse(u.contains("上海"))
        assertTrue(u.contains("%E4%B8%8A%E6%B5%B7"))
        assertTrue(u.contains("format=rss"))
    }
}
