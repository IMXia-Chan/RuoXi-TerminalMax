package com.example.touchpad

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 天气那一路的解析。
 *
 * ★ 这块屏最难看的坏法不是空着，是「**上海 0° 晴**」——
 *   `optDouble` 遇到缺字段给的是 NaN、`optInt` 给 0，一路格式化下去就变成一个
 *   看着完全合法的温度。所以「缺什么就不画什么」必须钉住。
 */
class WeatherMathTest {

    private val full = """{
        "latitude":31.23,"longitude":121.47,
        "current":{"time":"2026-11-07T02:30","temperature_2m":4.1,"weather_code":0},
        "daily":{
          "weather_code":[0,2],
          "temperature_2m_max":[9.4,-1.0],
          "temperature_2m_min":[-1.2,2.0],
          "precipitation_probability_max":[0,30]}}"""

    // ── 解析 ────────────────────────────────────────────────────────────

    @Test
    fun `正常的一份`() {
        val w = WeatherMath.nowFromForecast(JSONObject(full), "上海", 123L)!!
        assertEquals("上海", w.city)
        assertEquals(4.1, w.temp, 1e-6)
        assertEquals(0, w.code)
        assertEquals(9.4, w.high, 1e-6)
        assertEquals(-1.2, w.low, 1e-6)
        assertEquals(0, w.rain)
        assertEquals(2, w.nCode)
        assertEquals(30, w.nRain)
        assertEquals(123L, w.at)
    }

    @Test
    fun `没有 current 就不画`() {
        assertNull(WeatherMath.nowFromForecast(JSONObject("""{"daily":{}}"""), "上海", 0L))
        assertNull(WeatherMath.nowFromForecast(null, "上海", 0L))
    }

    @Test
    fun `没有温度就不画`() {
        val t = """{"current":{"weather_code":0}}"""
        assertNull(WeatherMath.nowFromForecast(JSONObject(t), "上海", 0L))
    }

    @Test
    fun `没有 daily 也能画当下`() {
        val t = """{"current":{"temperature_2m":4.1,"weather_code":0}}"""
        val w = WeatherMath.nowFromForecast(JSONObject(t), "上海", 0L)!!
        assertTrue(w.high.isNaN())
        assertEquals(-1, w.rain)
    }

    @Test
    fun `城市空着用兜底那个`() {
        val w = WeatherMath.nowFromForecast(JSONObject(full), "  ", 0L)!!
        assertEquals(WeatherMath.FALLBACK_CITY, w.city)
    }

    // ── 认城市 ──────────────────────────────────────────────────────────

    @Test
    fun `pconline 给的中文名`() {
        val t = """{"ip":"1.2.3.4","pro":"浙江省","city":"上海市","err":""}"""
        assertEquals("上海", WeatherMath.cityFromIpJson(t))
    }

    @Test
    fun `没有市字就不动`() {
        assertEquals("北京", WeatherMath.cityFromIpJson("""{"city":"北京"}"""))
    }

    @Test
    fun `只给省名就用省名`() {
        assertEquals("浙江", WeatherMath.cityFromIpJson("""{"pro":"浙江省","city":""}"""))
    }

    @Test
    fun `认不出就给空串`() {
        assertEquals("", WeatherMath.cityFromIpJson(""))
        assertEquals("", WeatherMath.cityFromIpJson("{不是 JSON"))
    }

    @Test
    fun `经纬度`() {
        val t = """{"results":[{"name":"上海","latitude":31.23,"longitude":121.47}]}"""
        val c = WeatherMath.coordFromGeoJson(t)!!
        assertEquals(31.23, c.first, 1e-6)
        assertEquals(121.47, c.second, 1e-6)
    }

    @Test
    fun `换不到经纬度就是 null`() {
        assertNull(WeatherMath.coordFromGeoJson("""{"results":[]}"""))
        assertNull(WeatherMath.coordFromGeoJson("{不是 JSON"))
    }

    // ── 屏上的字 ────────────────────────────────────────────────────────

    @Test
    fun `整度不带小数`() {
        assertEquals("4°", WeatherMath.tempText(4.0))
        assertEquals("4°", WeatherMath.tempText(3.98))
        assertEquals("0°", WeatherMath.tempText(0.0))
    }

    @Test
    fun `半度带一位`() {
        assertEquals("4.5°", WeatherMath.tempText(4.5))
        assertEquals("-1.2°", WeatherMath.tempText(-1.2))
    }

    @Test
    fun `不知道就画一横`() {
        assertEquals("—", WeatherMath.tempText(Double.NaN))
    }

    @Test
    fun `高低温`() {
        assertEquals("9° / -1°", WeatherMath.rangeText(9.0, -1.0))
        assertEquals("最高 9°", WeatherMath.rangeText(9.0, Double.NaN))
        assertEquals("最低 -1°", WeatherMath.rangeText(Double.NaN, -1.0))
        assertEquals("", WeatherMath.rangeText(Double.NaN, Double.NaN))
    }

    @Test
    fun `降水概率没给就不写`() {
        assertEquals("", WeatherMath.rainText(-1))
        assertEquals("降水 0%", WeatherMath.rainText(0))
        assertEquals("降水 30%", WeatherMath.rainText(30))
    }

    @Test
    fun `天气码`() {
        assertEquals("晴", WeatherMath.desc(0))
        assertEquals("多云", WeatherMath.desc(2))
        assertEquals("大雨", WeatherMath.desc(65))
        assertEquals("雷阵雨伴冰雹", WeatherMath.desc(99))
        assertEquals("", WeatherMath.desc(1234))
    }

    // ── 落盘 ────────────────────────────────────────────────────────────

    @Test
    fun `存下来再读回来一个字段不差`() {
        // ★ 这两步走的是**两个不同的壳子**（接口原样 vs 压平后落盘），
        //   混用的话取值永远是 null，天气就永远显示不出来
        val w = WeatherMath.nowFromForecast(JSONObject(full), "上海", 777L)!!
        val back = WeatherMath.fromJson(WeatherMath.toJson(w))!!
        assertEquals("上海", back.city)
        assertEquals(4.1, back.temp, 1e-6)
        assertEquals(0, back.code)
        assertEquals(9.4, back.high, 1e-6)
        assertEquals(-1.2, back.low, 1e-6)
        assertEquals(0, back.rain)
        assertEquals(2, back.nCode)
        assertEquals(30, back.nRain)
        assertEquals(777L, back.at)
        assertNotNull(WeatherMath.toJson(null))
    }

    @Test
    fun `盘上的空壳子读成 null`() {
        assertNull(WeatherMath.fromJson(JSONObject()))
        assertNull(WeatherMath.fromJson(null))
        assertNull(WeatherMath.fromJson(JSONObject("""{"city":"上海"}""")))
    }
}
