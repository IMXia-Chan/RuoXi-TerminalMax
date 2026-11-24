package com.example.touchpad

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * 每天那句开场白的算术。
 *
 * ★ 这里错的症状是**听得出来但不报错**的：「凌晨两点说早上好」「外面下着雨却说不用带伞」
 *   「今天有事却一个字没提」。所以每一档都钉住。
 */
class DayGreetTest {

    // ── 时段 ────────────────────────────────────────────────────────────

    @Test
    fun `时段划分`() {
        assertEquals("凌晨", DayGreet.slot(2))
        assertEquals("清晨", DayGreet.slot(6))
        assertEquals("早上", DayGreet.slot(8))
        assertEquals("中午", DayGreet.slot(12))
        assertEquals("下午", DayGreet.slot(15))
        assertEquals("傍晚", DayGreet.slot(19))
        assertEquals("晚上", DayGreet.slot(21))
        assertEquals("深夜", DayGreet.slot(23))
    }

    @Test
    fun `凌晨和深夜算晚`() {
        assertTrue(DayGreet.isLate(0))
        assertTrue(DayGreet.isLate(4))
        assertFalse(DayGreet.isLate(5))
        assertFalse(DayGreet.isLate(22))
        assertTrue(DayGreet.isLate(23))
    }

    // ── 带伞 ────────────────────────────────────────────────────────────

    @Test
    fun `两小时内有雨雪雷`() {
        assertEquals("雨", DayGreet.soon(61, 80))
        assertEquals("雪", DayGreet.soon(73, 70))
        assertEquals("雷雨", DayGreet.soon(95, 90))
    }

    @Test
    fun `天气码说没事但概率过半`() {
        assertEquals("可能下雨", DayGreet.soon(0, 60))
        assertEquals("可能下雨", DayGreet.soon(-1, 50))
    }

    @Test
    fun `概率不高就不提`() {
        assertEquals("", DayGreet.soon(1, 20))
        assertEquals("", DayGreet.soon(3, 49))
    }

    @Test
    fun `什么都不知道也不提`() {
        assertEquals("", DayGreet.soon(-1, -1))
    }

    // ── 事实 ────────────────────────────────────────────────────────────

    private fun w(code: Int = 61, soonCode: Int = 61, soonRain: Int = 80) = WeatherNow(
        city = "上海", temp = 15.4, code = code,
        high = 20.0, low = 10.0, rain = 30,
        nCode = 0, nHigh = 21.0, nLow = 11.0, nRain = 10,
        at = 0L, soonCode = soonCode, soonRain = soonRain,
    )

    @Test
    fun `事实里有城市 天气 温度 两小时`() {
        val f = DayGreet.facts(15, "11月7日 周五", w(), emptyList())
        assertTrue(f.contains("上海"))
        assertTrue(f.contains("小雨"))
        assertTrue(f.contains("15 度"))
        assertTrue(f.contains("未来两小时：雨"))
    }

    @Test
    fun `没雨也要说清楚是没雨`() {
        val f = DayGreet.facts(15, "11月7日 周五", w(code = 0, soonCode = 0, soonRain = 5), emptyList())
        assertTrue(f.contains("未来两小时：没有降水"))
    }

    @Test
    fun `接口没给两小时就别编`() {
        val f = DayGreet.facts(15, "11月7日 周五", w(soonCode = -1, soonRain = -1), emptyList())
        assertTrue(f.contains("不知道"))
    }

    @Test
    fun `没有天气也照说`() {
        val f = DayGreet.facts(15, "11月7日 周五", null, emptyList())
        assertTrue(f.contains("没取到"))
    }

    @Test
    fun `有安排就写进去`() {
        val f = DayGreet.facts(15, "11月7日 周五", w(), listOf("14:00 交作业"))
        assertTrue(f.contains("今天要做的事：14:00 交作业"))
    }

    @Test
    fun `没安排就一个字不提`() {
        val f = DayGreet.facts(15, "11月7日 周五", w(), emptyList())
        assertFalse(f.contains("今天要做的事"))
    }

    // ── 交给她的话 ──────────────────────────────────────────────────────

    @Test
    fun `提示里禁掉早上好和播报腔`() {
        val h = DayGreet.hint("现在凌晨，…")
        assertTrue(h.contains("别说「根据数据」"))
        assertTrue(h.contains("凌晨"))
        assertTrue(h.contains("带伞"))
    }

    // ── 模型说不出话时 ──────────────────────────────────────────────────

    @Test
    fun `凌晨那句不是早上好`() {
        val s = DayGreet.fallback(2, w(), emptyList())
        assertTrue(s.startsWith("这么晚了"))
        assertFalse(s.contains("早上好"))
    }

    @Test
    fun `白天按点问好`() {
        assertEquals("早上好。", DayGreet.fallback(8, null, emptyList()))
        assertEquals("下午好。", DayGreet.fallback(15, null, emptyList()))
    }

    @Test
    fun `退路里也提醒带伞`() {
        val s = DayGreet.fallback(9, w(), emptyList())
        assertTrue(s.contains("带把伞"))
    }

    @Test
    fun `不下雨就不提伞`() {
        val s = DayGreet.fallback(9, w(code = 0, soonCode = 0, soonRain = 0), emptyList())
        assertFalse(s.contains("伞"))
    }

    @Test
    fun `退路里提今天的安排`() {
        val s = DayGreet.fallback(9, null, listOf("14:00 交作业", "20:00 复盘"))
        assertTrue(s.contains("14:00 交作业；20:00 复盘"))
    }

    // ── 今天哪几件 ──────────────────────────────────────────────────────

    private fun plan(id: String, at: Long) = TaskPlan(
        id = id, prompt = "做点什么", kind = TaskMath.DAILY, minuteOfDay = 480,
        weekday = 0, onceAt = 0L, enabled = true, nextAt = at,
        lastAt = 0L, lastOk = false, lastText = "", failCount = 0, createdAt = 0L,
    )

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(y, mo - 1, d, h, mi, 0)
        }.timeInMillis

    @Test
    fun `写成几点做什么`() {
        val now = at(2026, 11, 7, 9, 0)
        val end = RoomPanelMath.endOfDay(now)
        val lines = DayGreet.taskLines(listOf(plan("a", at(2026, 11, 7, 14, 30))), now, end)
        assertEquals(listOf("14:30 做点什么"), lines)
    }

    @Test
    fun `最多三条`() {
        val now = at(2026, 11, 7, 9, 0)
        val end = RoomPanelMath.endOfDay(now)
        val many = (1..9).map { plan("p$it", now + it * 600_000L) }
        assertEquals(DayGreet.MAX_TASKS, DayGreet.taskLines(many, now, end).size)
    }

    // ── 一天只一次 ──────────────────────────────────────────────────────

    @Test
    fun `日期键是本地年月日`() {
        val key = DayGreet.dayKey(at(2026, 11, 7, 0, 30))
        assertEquals("2026-11-07", key)
    }

    // ── 两小时窗口 ──────────────────────────────────────────────────────

    @Test
    fun `窗口从当前整点往后数两格`() {
        val base = Calendar.getInstance().apply {
            clear()
            set(2026, 10, 7, 0, 0, 0)
        }
        val times = JSONArray()
        val codes = JSONArray()
        val probs = JSONArray()
        for (h in 0..23) {
            times.put(String.format(Locale.US, "2026-11-07T%02d:00", h))
            // 14/15/16 点下大雨,别的时候晴
            codes.put(if (h in 14..16) 65 else 0)
            probs.put(if (h in 14..16) 90 else 5)
        }
        val o = JSONObject()
            .put("hourly", JSONObject()
                .put("time", times).put("weather_code", codes)
                .put("precipitation_probability", probs))

        // 13:20 进去 → 窗口 = 13、14、15 —— 能看见 14 点那场雨
        val at13 = base.apply { set(Calendar.HOUR_OF_DAY, 13); set(Calendar.MINUTE, 20) }.timeInMillis
        assertEquals(65 to 90, WeatherMath.soonHorizon(o, at13))

        // 17:00 进去 → 窗口 = 17、18、19 —— 雨已经过去了
        val at17 = base.apply { set(Calendar.HOUR_OF_DAY, 17); set(Calendar.MINUTE, 0) }.timeInMillis
        assertEquals(0 to 5, WeatherMath.soonHorizon(o, at17))
    }

    @Test
    fun `没有 hourly 就说不知道`() {
        assertEquals(-1 to -1, WeatherMath.soonHorizon(null, 0L))
        assertEquals(-1 to -1, WeatherMath.soonHorizon(JSONObject(), 0L))
        // 有时刻表但对不上(比如接口回的是别的日子) —— 也是不知道
        val o = JSONObject().put("hourly", JSONObject()
            .put("time", JSONArray().put("2000-01-01T00:00"))
            .put("weather_code", JSONArray().put(65)))
        assertEquals(-1 to -1, WeatherMath.soonHorizon(o, at(2026, 11, 7, 9, 0)))
    }
}
