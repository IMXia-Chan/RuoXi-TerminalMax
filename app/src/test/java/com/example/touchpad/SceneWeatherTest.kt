package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 窗外那片景的折算。
 *
 * ★ 这里错的症状全是**看得见但不报错**的:外面下着雨、屋里画着月亮;
 *   或者一点钟窗外大亮。所以码表和时间档必须钉住。
 */
class SceneWeatherTest {

    // ── 天气码 ──────────────────────────────────────────────────────────

    @Test
    fun `晴和多云分得开`() {
        assertEquals(SceneWeather.CLEAR, SceneWeather.kind(0))
        assertEquals(SceneWeather.PARTLY, SceneWeather.kind(1))
        assertEquals(SceneWeather.PARTLY, SceneWeather.kind(2))
        assertEquals(SceneWeather.OVERCAST, SceneWeather.kind(3))
    }

    @Test
    fun `雾`() {
        assertEquals(SceneWeather.FOG, SceneWeather.kind(45))
        assertEquals(SceneWeather.FOG, SceneWeather.kind(48))
    }

    @Test
    fun `毛毛雨到阵雨都是雨`() {
        // 51/53/55 毛毛雨,61/63/65 小中大,80/81/82 阵雨,56/57/66/67 冻雨
        for (c in listOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82)) {
            assertEquals("code=$c", SceneWeather.RAIN, SceneWeather.kind(c))
        }
    }

    @Test
    fun `雪是单独一档`() {
        // 雪化成雨画,窗外就是「下雨」—— 一月的上海会露馅
        for (c in listOf(71, 73, 75, 77, 85, 86)) {
            assertEquals("code=$c", SceneWeather.SNOW, SceneWeather.kind(c))
        }
    }

    @Test
    fun `雷阵雨和雷阵雨伴冰雹`() {
        assertEquals(SceneWeather.THUNDER, SceneWeather.kind(95))
        assertEquals(SceneWeather.THUNDER, SceneWeather.kind(96))
        assertEquals(SceneWeather.THUNDER, SceneWeather.kind(99))
    }

    @Test
    fun `没给天气码当晴`() {
        // optInt 缺席给 -1;别的乱七八糟的码也一律当晴,不能落空
        assertEquals(SceneWeather.CLEAR, SceneWeather.kind(-1))
        assertEquals(SceneWeather.CLEAR, SceneWeather.kind(4))
        assertEquals(SceneWeather.CLEAR, SceneWeather.kind(500))
    }

    // ── 天光 ────────────────────────────────────────────────────────────

    @Test
    fun `白天是七点到十六点`() {
        assertEquals(SceneWeather.DAY, SceneWeather.phase(7))
        assertEquals(SceneWeather.DAY, SceneWeather.phase(12))
        assertEquals(SceneWeather.DAY, SceneWeather.phase(16))
    }

    @Test
    fun `黄昏和黎明都是暖光那一档`() {
        assertEquals(SceneWeather.DUSK, SceneWeather.phase(5))
        assertEquals(SceneWeather.DUSK, SceneWeather.phase(6))
        assertEquals(SceneWeather.DUSK, SceneWeather.phase(17))
        assertEquals(SceneWeather.DUSK, SceneWeather.phase(18))
    }

    @Test
    fun `夜里`() {
        for (h in listOf(0, 1, 4, 19, 22, 23)) {
            assertEquals("h=$h", SceneWeather.NIGHT, SceneWeather.phase(h))
        }
    }

    @Test
    fun `一天二十四小时都有档`() {
        for (h in 0..23) {
            assertTrue("h=$h", SceneWeather.phase(h).isNotEmpty())
        }
    }

    // ── 递出去的那串 ────────────────────────────────────────────────────

    private fun now(code: Int, temp: Double = 15.5) = WeatherNow(
        city = "上海", temp = temp, code = code,
        high = 20.0, low = 10.0, rain = 30,
        nCode = 0, nHigh = 21.0, nLow = 11.0, nRain = 10,
        at = 0L,
    )

    @Test
    fun `形状和 room_js 认的一致`() {
        assertEquals(
            "{\"phase\":\"night\",\"kind\":\"rain\",\"temp\":15.5}",
            SceneWeather.json(now(61), SceneWeather.NIGHT),
        )
    }

    @Test
    fun `没有天气也照给一串`() {
        // 盘上没缓存 / 云端兜底关着 —— 窗外必须是「夜晴」,不能是空白
        assertEquals("{\"phase\":\"day\",\"kind\":\"clear\"}", SceneWeather.json(null, SceneWeather.DAY))
    }

    @Test
    fun `温度只留一位小数`() {
        assertEquals(
            "{\"phase\":\"day\",\"kind\":\"clear\",\"temp\":15.0}",
            SceneWeather.json(now(0, 15.0), SceneWeather.DAY),
        )
    }

    @Test
    fun `同一份天气拼出来的字串逐字相同`() {
        // ★ 这一段是拿去比对的(见 pushSceneWeather)。拼法一变,每一拍都会重发。
        assertEquals(SceneWeather.json(now(3), SceneWeather.DAY), SceneWeather.json(now(3), SceneWeather.DAY))
    }
}
