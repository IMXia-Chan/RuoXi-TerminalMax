package com.example.touchpad

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 定时任务的时间算术。
 *
 * ★ 这一层错了**不报错**，症状是「每天早上八点」里有一天没响，或者**半夜响了一次**。
 *   而那时候没有人在看屏幕，日志上也只有一句「闹钟响」。所以必须能在纯 JVM 上钉死，
 *   不能靠真机等一天 —— 那也不是测，那是碰运气。
 *
 * 固定用 Asia/Shanghai，不用系统时区：测试在哪个时区跑都得是同一个答案。
 * 2026-11-07 是**周六**（下面几条按它推）。
 */
class TaskMathTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun ms(y: Int, m: Int, d: Int, h: Int, mi: Int): Long =
        LocalDate.of(y, m, d).atTime(h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun plan(
        kind: String,
        minute: Int,
        weekday: Int = 1,
        onceAt: Long = 0,
    ) = TaskPlan(
        id = "s1", prompt = "查天气", kind = kind, minuteOfDay = minute, weekday = weekday,
        onceAt = onceAt, enabled = true, nextAt = 0, lastAt = 0, lastOk = false,
        lastText = "", failCount = 0, createdAt = 0,
    )

    // ---- 每天 ----

    @Test
    fun `每天：今天这个点还没到就排今天`() {
        val p = plan(TaskMath.DAILY, 8 * 60)
        assertEquals(
            ms(2026, 11, 7, 8, 0),
            TaskMath.nextAfter(p, ms(2026, 11, 7, 7, 0), zone),
        )
    }

    @Test
    fun `★ 每天：已经过点了就排明天，不是今天`() {
        // 闹钟 08:00 醒了，跑完是 08:00:40 —— 再排必须跳到明天。
        // 判等（`>=` 写成 `>` 的错）会让它**立刻再响一次**，然后无限循环。
        val p = plan(TaskMath.DAILY, 8 * 60)
        assertEquals(
            ms(2026, 11, 8, 8, 0),
            TaskMath.nextAfter(p, ms(2026, 11, 7, 8, 0) + 40_000, zone),
        )
    }

    @Test
    fun `每天：正好卡在那一秒也算过去了`() {
        val p = plan(TaskMath.DAILY, 8 * 60)
        assertEquals(
            ms(2026, 11, 8, 8, 0),
            TaskMath.nextAfter(p, ms(2026, 11, 7, 8, 0), zone),
        )
    }

    // ---- 工作日 ----

    @Test
    fun `★ 工作日：周五过点之后是下周一，不是周六`() {
        // 2026-11-06 是周五。最坏的那种错是排到周六早上八点 ——
        // 他没课、手机在桌上响了一声，而她「按时做了那件事」。
        val p = plan(TaskMath.WORKDAYS, 8 * 60)
        assertEquals(
            ms(2026, 11, 9, 8, 0),
            TaskMath.nextAfter(p, ms(2026, 11, 6, 9, 0), zone),
        )
    }

    @Test
    fun `工作日：周六当天不会响`() {
        val p = plan(TaskMath.WORKDAYS, 8 * 60)
        assertEquals(
            ms(2026, 11, 9, 8, 0),
            TaskMath.nextAfter(p, ms(2026, 11, 7, 7, 0), zone),
        )
    }

    @Test
    fun `工作日：周一早上没过点就是今天`() {
        val p = plan(TaskMath.WORKDAYS, 8 * 60)
        assertEquals(
            ms(2026, 11, 9, 8, 0),
            TaskMath.nextAfter(p, ms(2026, 11, 9, 7, 30), zone),
        )
    }

    // ---- 每周 ----

    @Test
    fun `每周：从周六往后找下一个周一`() {
        val p = plan(TaskMath.WEEKLY, 8 * 60, weekday = 1)
        assertEquals(
            ms(2026, 11, 9, 8, 0),
            TaskMath.nextAfter(p, ms(2026, 11, 7, 10, 0), zone),
        )
    }

    @Test
    fun `每周：当天还没到点就是今天`() {
        // 2026-11-07 是周六 = 6
        val p = plan(TaskMath.WEEKLY, 8 * 60, weekday = 6)
        assertEquals(
            ms(2026, 11, 7, 8, 0),
            TaskMath.nextAfter(p, ms(2026, 11, 7, 7, 0), zone),
        )
    }

    @Test
    fun `每周：当天已经过点就是下周同一天`() {
        val p = plan(TaskMath.WEEKLY, 8 * 60, weekday = 6)
        assertEquals(
            ms(2026, 11, 14, 8, 0),
            TaskMath.nextAfter(p, ms(2026, 11, 7, 9, 0), zone),
        )
    }

    // ---- 一次 ----

    @Test
    fun `一次：还没到就排它`() {
        val p = plan(TaskMath.ONCE, 8 * 60, onceAt = ms(2026, 11, 8, 8, 0))
        assertEquals(
            ms(2026, 11, 8, 8, 0),
            TaskMath.nextAfter(p, ms(2026, 11, 7, 12, 0), zone),
        )
    }

    @Test
    fun `★ 一次：过去了就是 null —— 不许「补跑一次」`() {
        val p = plan(TaskMath.ONCE, 8 * 60, onceAt = ms(2026, 11, 7, 8, 0))
        assertNull(TaskMath.nextAfter(p, ms(2026, 11, 7, 9, 0), zone))
    }

    // ---- 参数解析 ----

    @Test
    fun `几点几分认得出来`() {
        assertEquals(480, TaskMath.minuteOfDay("08:00", zone))
        assertEquals(480, TaskMath.minuteOfDay("早上8点", zone))
        assertEquals(1290, TaskMath.minuteOfDay("晚上9点半", zone))
        assertEquals(0, TaskMath.minuteOfDay("0:00", zone))
    }

    @Test
    fun `★ 认不出的时间宁可 null，不猜`() {
        assertNull(TaskMath.minuteOfDay("", zone))
        assertNull(TaskMath.minuteOfDay("早上", zone))
        assertNull(TaskMath.minuteOfDay("25:00", zone))
        assertNull(TaskMath.minuteOfDay("八点", zone))   // 中文数字认不出就要说出来
    }

    @Test
    fun `周几认得出来`() {
        assertEquals(1, TaskMath.weekdayOf("周一"))
        assertEquals(1, TaskMath.weekdayOf("星期一"))
        assertEquals(7, TaskMath.weekdayOf("周日"))
        assertEquals(7, TaskMath.weekdayOf("星期天"))
        assertEquals(7, TaskMath.weekdayOf("礼拜日"))
        assertEquals(3, TaskMath.weekdayOf("3"))
        assertNull(TaskMath.weekdayOf("八"))
        assertNull(TaskMath.weekdayOf(""))
        assertNull("一周只有 7 天", TaskMath.weekdayOf("9"))
    }

    @Test
    fun `重复方式认得出来，认不出按每天`() {
        assertEquals(TaskMath.DAILY, TaskMath.kindOf("daily"))
        assertEquals(TaskMath.WORKDAYS, TaskMath.kindOf("工作日"))
        assertEquals(TaskMath.WEEKLY, TaskMath.kindOf("每周"))
        assertEquals(TaskMath.ONCE, TaskMath.kindOf("一次"))
        assertEquals(TaskMath.DAILY, TaskMath.kindOf("随便"))
    }

    // ---- 说人话 ----

    @Test
    fun `念成一句人话`() {
        assertEquals("每天 08:00", TaskMath.describe(plan(TaskMath.DAILY, 480), zone))
        assertEquals("工作日 07:30", TaskMath.describe(plan(TaskMath.WORKDAYS, 450), zone))
        assertEquals("周一 09:00", TaskMath.describe(plan(TaskMath.WEEKLY, 540, weekday = 1), zone))
        val once = plan(TaskMath.ONCE, 14 * 60 + 30, onceAt = ms(2026, 11, 8, 14, 30))
        assertEquals("11月8日 14:30（一次）", TaskMath.describe(once, zone))
    }

    @Test
    fun `下一次说今天是今天、说明天是明天`() {
        val now = ms(2026, 11, 7, 7, 0)
        assertEquals("今天 08:00", TaskMath.nextText(ms(2026, 11, 7, 8, 0), now, zone))
        assertEquals("明天 08:00", TaskMath.nextText(ms(2026, 11, 8, 8, 0), now, zone))
        assertEquals("后天 08:00", TaskMath.nextText(ms(2026, 11, 9, 8, 0), now, zone))
        assertEquals("11月14日 08:00", TaskMath.nextText(ms(2026, 11, 14, 8, 0), now, zone))
        assertEquals("不会再响了", TaskMath.nextText(0, now, zone))
    }

    @Test
    fun `摘要压成一行并截断`() {
        assertEquals("第一行 第二行", TaskMath.clip("第一行\n第二行", 50))
        assertTrue(TaskMath.clip("字".repeat(500), 200).endsWith("…"))
        assertEquals(201, TaskMath.clip("字".repeat(500), 200).length)
    }

    // ---- 存档 ----

    @Test
    fun `存下去再读回来，字段一个不少`() {
        val p = TaskPlan(
            id = "s9", prompt = "查天气", kind = TaskMath.WEEKLY, minuteOfDay = 450,
            weekday = 3, onceAt = 0, enabled = true, nextAt = ms(2026, 11, 11, 7, 30),
            lastAt = ms(2026, 11, 4, 7, 30), lastOk = true, lastText = "晴",
            failCount = 2, createdAt = 1,
        )
        val back = TaskPlan.fromJson(JSONObject(p.toJson().toString()))!!
        assertEquals(p, back)
    }

    @Test
    fun `★ 坏档案当没有，不返回半条`() {
        assertNull(TaskPlan.fromJson(JSONObject().put("id", "s1")))               // 没有 prompt
        assertNull(TaskPlan.fromJson(JSONObject().put("prompt", "查天气")))        // 没有 id
        assertNull(TaskPlan.fromJson(null))
    }

    @Test
    fun `读回来的越界值被夹进合法范围`() {
        val back = TaskPlan.fromJson(
            JSONObject()
                .put("id", "s1").put("prompt", "x")
                .put("minuteOfDay", 5000).put("weekday", 99)
        )!!
        assertEquals(24 * 60 - 1, back.minuteOfDay)
        assertEquals(7, back.weekday)
    }
}
