package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 「哪天几点」的换算。
 *
 * ★ 这些错**都不抛异常**：日程写进去了、时间和说定的差一天，人只会以为她会提醒，
 *   到点什么也不响。所以每一条认法都在这里钉住。
 */
class CalMathTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-11-06（周五）上午十点。 */
    private val now = at(2026, 11, 6, 10, 0)

    private fun at(y: Int, m: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, m, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun whenOf(date: String, time: String = "", minutes: Int = 0): CalMath.When? =
        CalMath.parse(date, time, minutes, now, zone)

    @Test
    fun `今天明天后天`() {
        assertEquals(at(2026, 11, 6, 14, 0), whenOf("今天", "14:00")!!.beginMs)
        assertEquals(at(2026, 11, 7, 14, 0), whenOf("明天", "14:00")!!.beginMs)
        assertEquals(at(2026, 11, 8, 14, 0), whenOf("后天", "14:00")!!.beginMs)
    }

    @Test
    fun `周几取下一次包括今天`() {
        // 2026-11-06 是周五
        assertEquals(LocalDate.of(2026, 11, 6).dayOfWeek.value, 5)
        assertEquals(at(2026, 11, 6, 9, 0), whenOf("周五", "9点")!!.beginMs)
        assertEquals(at(2026, 11, 8, 9, 0), whenOf("周日", "9点")!!.beginMs)
        assertEquals(at(2026, 11, 8, 9, 0), whenOf("星期天", "9点")!!.beginMs)
        assertEquals(at(2026, 11, 9, 9, 0), whenOf("周一", "9点")!!.beginMs)
    }

    @Test
    fun `下周几和本周几`() {
        assertEquals(at(2026, 11, 13, 9, 0), whenOf("下周五", "9点")!!.beginMs)
        assertEquals(at(2026, 11, 9, 9, 0), whenOf("下周一", "9点")!!.beginMs)
        // 本周一已经过去了，指的仍然是这一周那一天
        assertEquals(at(2026, 11, 2, 9, 0), whenOf("本周一", "9点")!!.beginMs)
    }

    @Test
    fun `数字日期两种写法`() {
        assertEquals(at(2026, 11, 8, 14, 30), whenOf("2026-11-08", "14:30")!!.beginMs)
        assertEquals(at(2026, 11, 8, 14, 30), whenOf("2026年11月8日", "14:30")!!.beginMs)
        assertEquals(at(2026, 11, 8, 14, 30), whenOf("11月8号", "14:30")!!.beginMs)
        assertEquals(at(2026, 11, 8, 14, 30), whenOf("11.8", "14:30")!!.beginMs)
    }

    @Test
    fun `没写年份的过去日期算明年`() {
        // 今天是 11 月 6 日，说「1月5号」指的是明年
        assertEquals(at(2027, 1, 5, 9, 0), whenOf("1月5号", "9点")!!.beginMs)
        // 昨天刚过的不算过去（前后一天以内）—— 说「11月5号」还是今年的
        assertEquals(at(2026, 11, 5, 9, 0), whenOf("11月5号", "9点")!!.beginMs)
    }

    @Test
    fun `下午和晚上要加十二小时`() {
        assertEquals(at(2026, 11, 6, 15, 0), whenOf("今天", "下午3点")!!.beginMs)
        assertEquals(at(2026, 11, 6, 15, 30), whenOf("今天", "下午3:30")!!.beginMs)
        // 「晚上12点」= 第二天的零点，不是当天中午
        assertEquals(at(2026, 11, 7, 0, 0), whenOf("今天", "晚上12点")!!.beginMs)
        assertEquals(at(2026, 11, 6, 7, 0), whenOf("今天", "早上7点")!!.beginMs)
        // 已经写了 13 点的「下午」不加倍
        assertEquals(at(2026, 11, 6, 13, 0), whenOf("今天", "下午13点")!!.beginMs)
    }

    @Test
    fun `★ 口语的「半」也认`() {
        // 2026-11-07 加：以前只认「九点十五分」，而口语里最常见的是「九点半」。
        // 认不出就退成 null，他会看到「我听不出几点」，明明说得很清楚。
        assertEquals(at(2026, 11, 6, 21, 30), whenOf("今天", "晚上9点半")!!.beginMs)
        assertEquals(at(2026, 11, 6, 9, 30), whenOf("今天", "9点半")!!.beginMs)
        assertEquals(at(2026, 11, 6, 9, 30), whenOf("今天", "9:30")!!.beginMs)
        // 既给分又说「半」= 一句说不清的话，**不许猜**成 15 或 30
        assertNull(whenOf("今天", "9点15半"))
    }

    @Test
    fun `没填时间是整天`() {
        val w = whenOf("明天")!!
        assertTrue(w.allDay)
        assertEquals(at(2026, 11, 7, 0, 0), w.beginMs)
        assertEquals(at(2026, 11, 8, 0, 0), w.endMs)
    }

    @Test
    fun `时长不填按一小时`() {
        val w = whenOf("明天", "14:00")!!
        assertEquals(3_600_000L, w.endMs - w.beginMs)
        val v = whenOf("明天", "14:00", 25)!!
        assertEquals(25 * 60_000L, v.endMs - v.beginMs)
        // 离谱的时长退回默认，不是「写一条 0 分钟的日程」
        val bad = whenOf("明天", "14:00", 9999)!!
        assertEquals(3_600_000L, bad.endMs - bad.beginMs)
    }

    @Test
    fun `认不出来就是 null 不猜`() {
        assertNull(whenOf("明天", "晚上八点"))      // 中文数字不认
        assertNull(whenOf("下下雨", "9点"))
        assertNull(whenOf("明天", "25:00"))
        assertNull(whenOf("明天", "9:75"))
        assertNull(whenOf("2026-13-01", "9点"))
    }

    @Test
    fun `回执是人话`() {
        val w = whenOf("2026-11-08", "14:30", 90)!!
        assertEquals("2026年11月8日 14:30–16:00", CalMath.human(w.beginMs, w.endMs, false, zone))
        val d = whenOf("2026-11-08")!!
        assertEquals("2026年11月8日 全天", CalMath.human(d.beginMs, d.endMs, true, zone))
    }

    @Test
    fun `标题压成一行并封顶`() {
        assertEquals("交 报告", CalMath.cleanTitle("  交\n报告  "))
        assertEquals(80, CalMath.cleanTitle("字".repeat(200)).length)
    }

    @Test
    fun `空日期就是今天`() {
        val w = CalMath.parse("", "9:00", 0, now, zone)
        assertNotNull(w)
        assertEquals(at(2026, 11, 6, 9, 0), w!!.beginMs)
    }
}
