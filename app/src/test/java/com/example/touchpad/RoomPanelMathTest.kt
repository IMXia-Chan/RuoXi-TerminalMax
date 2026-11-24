package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 房间里右边那块「今日」屏的算术。
 *
 * ★ 这里错的症状是「屏上写着一条早就跑完的活」或者「明明有安排显示没有」——
 *   两样都不报错，人也不会天天盯着这块屏看，所以只能靠这里钉住。
 */
class RoomPanelMathTest {

    private fun plan(
        id: String,
        minute: Int = 480,
        enabled: Boolean = true,
        nextAt: Long = 0L,
        prompt: String = "做点什么",
    ) = TaskPlan(
        id = id, prompt = prompt, kind = TaskMath.DAILY, minuteOfDay = minute,
        weekday = 0, onceAt = 0L, enabled = enabled, nextAt = nextAt,
        lastAt = 0L, lastOk = false, lastText = "", failCount = 0, createdAt = 0L,
    )

    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(y, mo - 1, d, h, mi, 0)
        }.timeInMillis

    // ── 时刻 ────────────────────────────────────────────────────────────

    @Test
    fun `第几分钟变成几点几分`() {
        assertEquals("00:00", RoomPanelMath.hhmm(0))
        assertEquals("08:00", RoomPanelMath.hhmm(480))
        assertEquals("23:59", RoomPanelMath.hhmm(1439))
    }

    @Test
    fun `越界的分钟夹回去`() {
        assertEquals("00:00", RoomPanelMath.hhmm(-5))
        assertEquals("23:59", RoomPanelMath.hhmm(9999))
    }

    @Test
    fun `日期那一行带星期`() {
        // 2026-11-07 是周六
        assertEquals("11月7日 周六", RoomPanelMath.dayLabel(at(2026, 11, 7)))
        // 2026-11-09 是周一
        assertEquals("11月9日 周一", RoomPanelMath.dayLabel(at(2026, 11, 9)))
    }

    @Test
    fun `今天最后一毫秒`() {
        val end = RoomPanelMath.endOfDay(at(2026, 11, 7, 9, 30))
        val c = Calendar.getInstance().apply { timeInMillis = end }
        assertEquals(23, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, c.get(Calendar.MINUTE))
        assertEquals(59, c.get(Calendar.SECOND))
        assertEquals(999, c.get(Calendar.MILLISECOND))
        assertEquals(7, c.get(Calendar.DAY_OF_MONTH))
    }

    // ── 今天还剩哪几条 ──────────────────────────────────────────────────

    @Test
    fun `只留今天之内还要响的`() {
        val now = at(2026, 11, 7, 9, 0)
        val end = RoomPanelMath.endOfDay(now)
        val list = listOf(
            plan("a", nextAt = now + 3_600_000),        // 一小时后 —— 要
            plan("b", nextAt = now - 3_600_000),        // 已经过去 —— 不要
            plan("c", nextAt = end + 1_000),            // 明天 —— 不要
        )
        assertEquals(listOf("a"), RoomPanelMath.todayPlans(list, now, end).map { it.id })
    }

    @Test
    fun `关掉的任务不上屏`() {
        val now = at(2026, 11, 7, 9, 0)
        val end = RoomPanelMath.endOfDay(now)
        val list = listOf(plan("a", enabled = false, nextAt = now + 1000))
        assertTrue(RoomPanelMath.todayPlans(list, now, end).isEmpty())
    }

    @Test
    fun `从没排过的不上屏`() {
        val now = at(2026, 11, 7, 9, 0)
        val end = RoomPanelMath.endOfDay(now)
        // nextAt = 0 = 一次性任务已经跑完（见 TaskMath.nextAfter）
        assertTrue(RoomPanelMath.todayPlans(listOf(plan("a", nextAt = 0L)), now, end).isEmpty())
    }

    @Test
    fun `正好到点的那一条不算`() {
        val now = at(2026, 11, 7, 9, 0)
        val end = RoomPanelMath.endOfDay(now)
        assertTrue(RoomPanelMath.todayPlans(listOf(plan("a", nextAt = now)), now, end).isEmpty())
    }

    @Test
    fun `按时间先后排`() {
        val now = at(2026, 11, 7, 9, 0)
        val end = RoomPanelMath.endOfDay(now)
        val list = listOf(
            plan("晚", nextAt = now + 7_200_000),
            plan("早", nextAt = now + 600_000),
            plan("中", nextAt = now + 3_600_000),
        )
        assertEquals(listOf("早", "中", "晚"), RoomPanelMath.todayPlans(list, now, end).map { it.id })
    }

    @Test
    fun `最多只留 PAGES 页`() {
        val now = at(2026, 11, 7, 9, 0)
        val end = RoomPanelMath.endOfDay(now)
        val list = (1..40).map { plan("p$it", nextAt = now + it * 1000L) }
        assertEquals(RoomPanelMath.PAGES, RoomPanelMath.todayPlans(list, now, end).size)
    }

    // ── 每小时换一批 ────────────────────────────────────────────────────

    @Test
    fun `第一批是前 PAGES 条`() {
        val all = (1..30).toList()
        assertEquals((1..12).toList(), RoomPanelMath.chunk(all, 0))
    }

    @Test
    fun `第二批接着往下`() {
        val all = (1..30).toList()
        assertEquals((13..24).toList(), RoomPanelMath.chunk(all, 1))
    }

    @Test
    fun `最后一批不够也不补`() {
        val all = (1..30).toList()
        assertEquals((25..30).toList(), RoomPanelMath.chunk(all, 2))
    }

    @Test
    fun `走到底就从头来而不是给空表`() {
        val all = (1..30).toList()
        assertEquals((1..12).toList(), RoomPanelMath.chunk(all, 3))
        // 跨天累加的批次号很大时也一样，不能算出越界的起点
        assertEquals((1..12).toList(), RoomPanelMath.chunk(all, 999))
    }

    @Test
    fun `没有素材就是空表`() {
        assertTrue(RoomPanelMath.chunk(emptyList<Int>(), 0).isEmpty())
        assertTrue(RoomPanelMath.chunk(listOf(1, 2), 0, size = 0).isEmpty())
    }

    @Test
    fun `负数批次当成第一批`() {
        assertEquals(listOf(1, 2, 3), RoomPanelMath.chunk((1..9).toList(), -3, 3))
    }

    // ── 一句话压短 ──────────────────────────────────────────────────────

    @Test
    fun `短句不动`() {
        assertEquals("开灯", RoomPanelMath.preview("开灯", 10))
    }

    @Test
    fun `长句截断并加省略号`() {
        val p = "每天早上八点把昨天的销售表格汇总一下发给我"
        val s = RoomPanelMath.preview(p, 10)
        assertEquals(10, s.length)
        assertTrue(s.endsWith("…"))
    }

    @Test
    fun `换行被压成空格`() {
        assertEquals("甲 乙", RoomPanelMath.preview("甲\n乙", 10))
    }

    // ── 语录放不放得下 ──────────────────────────────────────────────────

    private fun q(t: String, lang: String = "zh") = Quotes.Quote(t, "出处", lang)

    @Test
    fun `短语录放得下`() {
        assertTrue(RoomPanelMath.fitsCard(q("知之为知之。")))
        assertTrue(RoomPanelMath.fitsCard(q("Knowledge is power.", "en")))
    }

    @Test
    fun `英文按字符数另算`() {
        assertTrue(RoomPanelMath.fitsCard(q("a".repeat(RoomPanelMath.FIT_EN), "en")))
        assertTrue(!RoomPanelMath.fitsCard(q("a".repeat(RoomPanelMath.FIT_EN + 1), "en")))
        // 三十个英文字符当中文算就短了，但这条本身是英文，按英文那把尺子量
        assertTrue(RoomPanelMath.fitsCard(q("a".repeat(RoomPanelMath.FIT_ZH), "zh")))
    }

    @Test
    fun `先挑短的`() {
        val long = q("长".repeat(RoomPanelMath.FIT_ZH + 1))
        val short = q("短句")
        assertEquals(listOf(short), RoomPanelMath.shortEnough(listOf(long, short)))
    }

    @Test
    fun `一条短的都没有就原样给`() {
        val long = q("长".repeat(RoomPanelMath.FIT_ZH + 1))
        assertEquals(listOf(long), RoomPanelMath.shortEnough(listOf(long)))
        assertTrue(RoomPanelMath.shortEnough(emptyList()).isEmpty())
    }
}
