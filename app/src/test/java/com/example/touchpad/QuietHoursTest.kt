package com.example.touchpad

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「现在是不是免打扰时段」的纯判定测试。
 *
 * ## 为什么这个纯函数值得单独一个文件钉
 *
 * 它唯一的坑是**跨午夜**,而跨午夜恰好是**最常见**的那个配置(23:00–07:00)。
 * Kotlin 里 `1380..420` 是一个**空区间** —— 照直写 `minuteOfDay in start..end`,
 * 最常见的配置会**静默失效**:开关开着、界面显示正常、半夜照样被吵醒。
 *
 * 症状是「我设了免打扰她还在半夜说话」——**一次就够让用户关掉整个功能**,
 * 而且之后再也不会打开。真机上要验它得熬夜,只能在纯 JVM 上钉死。
 *
 * 分钟数约定:**半开区间 `[start, end)`**,整点属于它自己那一侧 ——
 * `23:00–07:00` 意味着 **07:00 整已经不静默了**(她可以开口)。
 */
class QuietHoursTest {

    /** 23:00 – 07:00,最常见的那个配置。start=1380 > end=420,跨午夜。 */
    private val NIGHT_START = 23 * 60     // 1380
    private val NIGHT_END = 7 * 60        // 420

    /** 13:00 – 14:00,同日时段(不跨午夜的那一半)。 */
    private val NOON_START = 13 * 60
    private val NOON_END = 14 * 60

    private fun quiet(h: Int, m: Int, start: Int, end: Int) =
        QuietMath.isQuiet(h * 60 + m, start, end)

    // ---------------------------------------------------------------- 跨午夜

    @Test
    fun `跨午夜_前半段(午夜之前)静默`() {
        // 1380..420 是空区间 —— 这一条就是钉它的:23:30 必须静默
        assertTrue("23:30 应该在免打扰里", quiet(23, 30, NIGHT_START, NIGHT_END))
        assertTrue("23:00 整刚开始,静默", quiet(23, 0, NIGHT_START, NIGHT_END))
        assertTrue("23:59 还在免打扰里", quiet(23, 59, NIGHT_START, NIGHT_END))
    }

    @Test
    fun `跨午夜_后半段(午夜之后)静默`() {
        assertTrue("00:00 是免打扰的中间", quiet(0, 0, NIGHT_START, NIGHT_END))
        assertTrue("02:00 睡得正香", quiet(2, 0, NIGHT_START, NIGHT_END))
        assertTrue("06:59 还差一分钟", quiet(6, 59, NIGHT_START, NIGHT_END))
    }

    @Test
    fun `跨午夜_白天两边都不静默`() {
        assertFalse("22:59 还没到点", quiet(22, 59, NIGHT_START, NIGHT_END))
        assertFalse("12:00 大中午的", quiet(12, 0, NIGHT_START, NIGHT_END))
        assertFalse("07:01 已经过了", quiet(7, 1, NIGHT_START, NIGHT_END))
    }

    @Test
    fun `跨午夜_结束的整点不再静默(半开区间)`() {
        // ★ 这条是半开区间的定义:07:00 属于「醒着」那一侧。
        //   写错成闭区间的话,她会一直安静到 07:01。
        assertFalse("07:00 整应该已经不静默", quiet(7, 0, NIGHT_START, NIGHT_END))
        assertTrue("差一分钟还静默", quiet(6, 59, NIGHT_START, NIGHT_END))
    }

    // ---------------------------------------------------------------- 同日

    @Test
    fun `同日时段`() {
        assertTrue("13:30 在午休里", quiet(13, 30, NOON_START, NOON_END))
        assertTrue("13:00 整开始", quiet(13, 0, NOON_START, NOON_END))
        assertFalse("12:59 还没到", quiet(12, 59, NOON_START, NOON_END))
        assertFalse("14:00 整结束(半开)", quiet(14, 0, NOON_START, NOON_END))
        assertFalse("14:01 早过了", quiet(14, 1, NOON_START, NOON_END))
    }

    // ---------------------------------------------------------------- 没设

    @Test
    fun `起止相同视为没设_全天都不静默`() {
        // 用户把开始和结束调成同一个点 = 「我不想免打扰」,不是「全天免打扰」。
        // 反过来的话,他会得到一个永远不说话的助手,而且找不到原因。
        assertFalse(quiet(0, 0, 0, 0))
        assertFalse(quiet(12, 0, 720, 720))
        assertFalse("23:59 也不静默", quiet(23, 59, 1380, 1380))
    }

    @Test
    fun `一天里的每一分钟都判得出个结果_不会漏`() {
        // 穷举一遍:任何一分钟要么静默要么不静默,不能有第三种情况(比如抛异常)。
        // 顺带对账:跨午夜那段的总分钟数应该正好等于(1440 - start) + end。
        var quietCount = 0
        for (minute in 0 until 1440) {
            if (QuietMath.isQuiet(minute, NIGHT_START, NIGHT_END)) quietCount++
        }
        val expected = (1440 - NIGHT_START) + NIGHT_END     // 60 + 420 = 480
        assertTrue(
            "静默分钟数应正好是 $expected,实际 $quietCount",
            quietCount == expected
        )
    }

    @Test
    fun `边界上的整分钟都属于后一侧`() {
        // 半开 [start, end):start 静默、end 不静默。两侧各取一点钉住方向,
        // 免得以后有人「顺手」把它改成闭区间。
        assertTrue("start 本身静默", QuietMath.isQuiet(NIGHT_START, NIGHT_START, NIGHT_END))
        assertFalse("end 本身不静默", QuietMath.isQuiet(NIGHT_END, NIGHT_START, NIGHT_END))
    }
}
