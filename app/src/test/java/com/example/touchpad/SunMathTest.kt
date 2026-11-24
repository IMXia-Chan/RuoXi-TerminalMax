package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 太阳高度角与天光。
 *
 * ★ 这里错的症状**不报错、也查不出来**:窗外是亮的还是黑的，取决于一个谁也不会去
 *   盯一眼的三角函数。所以拿真地方、真日子钉住 —— 坐标取上海（中纬度、东八区），数字可复核。
 *
 * 验证基准（离线算过，和公开的日出日落数据差 5 分钟以内）：
 * 上海 2026-11-07 日出约 06:19、日落约 16:57。
 */
class SunMathTest {

    private val cst = TimeZone.getTimeZone("Asia/Shanghai")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, tz: TimeZone = cst): Long =
        Calendar.getInstance(tz).apply {
            clear()
            set(y, mo - 1, d, h, mi, 0)
        }.timeInMillis

    // ── 高度角 ──────────────────────────────────────────────────────────

    @Test
    fun `上海正午太阳很高`() {
        val e = SunMath.elevation(31.23, 121.47, at(2026, 11, 7, 12))
        // 十一月的上海正午约 42°（纬度 31.2° + 赤纬约 -16.5°）
        assertTrue("e=$e", e in 40.0..45.0)
    }

    @Test
    fun `上海凌晨太阳在地平线下`() {
        val e = SunMath.elevation(31.23, 121.47, at(2026, 11, 7, 3))
        assertTrue("e=$e", e < -30.0)
    }

    @Test
    fun `坐标不合法给的是 NaN 不是瞎猜`() {
        assertTrue(SunMath.elevation(Double.NaN, 121.47, 0L).isNaN())
        assertTrue(SunMath.elevation(200.0, 0.0, 0L).isNaN())
        assertTrue(SunMath.elevation(31.23, 400.0, 0L).isNaN())
    }

    // ── 三档 ────────────────────────────────────────────────────────────

    @Test
    fun `上海这一天 中午白天 傍晚黄昏 夜里黑`() {
        assertEquals(SceneWeather.DAY, SunMath.phase(31.23, 121.47, at(2026, 11, 7, 12)))
        // 17:20 太阳已落到地平线下 4.8°，还在民用曙暮光里
        assertEquals(SceneWeather.DUSK, SunMath.phase(31.23, 121.47, at(2026, 11, 7, 17, 20)))
        assertEquals(SceneWeather.NIGHT, SunMath.phase(31.23, 121.47, at(2026, 11, 7, 18, 0)))
        assertEquals(SceneWeather.NIGHT, SunMath.phase(31.23, 121.47, at(2026, 11, 7, 3)))
    }

    @Test
    fun `早上六点 十一月还暗 七月已经大亮`() {
        // 这一条就是「按钟点猜」修不掉的毛病 —— 同一时刻，两个季节窗外不一样。
        assertEquals(SceneWeather.DUSK, SunMath.phase(31.23, 121.47, at(2026, 11, 7, 6, 0)))
        assertEquals(SceneWeather.DAY, SunMath.phase(31.23, 121.47, at(2026, 7, 7, 6, 0)))
    }

    @Test
    fun `极昼与极夜`() {
        // 北纬 78° 的六月，半夜太阳还在地平线上
        assertEquals(SceneWeather.DAY, SunMath.phase(78.0, 15.0, at(2026, 6, 21, 0, 30)))
        // 同纬度十二月正午，太阳压根不上来
        assertEquals(SceneWeather.NIGHT, SunMath.phase(78.0, 15.0, at(2026, 12, 21, 12, 0, TimeZone.getTimeZone("UTC"))))
    }

    @Test
    fun `算不出来就当天黑`() {
        // 宁可让窗外比现实暗，也绝不能比现实亮
        assertEquals(SceneWeather.NIGHT, SunMath.phase(Double.NaN, Double.NaN, 0L))
    }

    // ── 白昼长度 ────────────────────────────────────────────────────────

    @Test
    fun `赤道一年到头十二小时`() {
        assertEquals(12.0, SunMath.dayLengthHours(0.0, at(2026, 3, 21, 12)), 0.2)
        assertEquals(12.0, SunMath.dayLengthHours(0.0, at(2026, 9, 23, 12)), 0.2)
    }

    @Test
    fun `高纬度极昼二十四小时 极夜零`() {
        assertEquals(24.0, SunMath.dayLengthHours(78.0, at(2026, 6, 21, 12)), 0.01)
        assertEquals(0.0, SunMath.dayLengthHours(78.0, at(2026, 12, 21, 12)), 0.01)
    }

    // ── 有坐标走计算，没坐标走钟点表 ────────────────────────────────────

    @Test
    fun `有坐标就用太阳算`() {
        assertEquals(
            SceneWeather.DAY,
            SceneWeather.phaseAt(31.23, 121.47, at(2026, 11, 7, 12), hour = 22),
        )
    }

    @Test
    fun `没坐标退回钟点表`() {
        // 22 点在钟点表里是夜里 —— 和有坐标那条给出同一个答案，但走的是另一条路
        assertEquals(SceneWeather.NIGHT, SceneWeather.phaseAt(null, null, 0L, hour = 22))
        assertEquals(SceneWeather.NIGHT, SceneWeather.phaseAt(Double.NaN, 121.47, 0L, hour = 22))
    }
}
