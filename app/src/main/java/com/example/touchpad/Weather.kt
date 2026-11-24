package com.example.touchpad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset

/**
 * 房间里右边那块屏上的天气。
 *
 * ## 为什么是它
 *
 * 2026-11-07 他看过真机之后问「右边框确实没有东西可显示」—— 那块原本写的是
 * **今天的定时任务**，而定时任务只有她自己能建，通常一条都没有，于是永远停在一句
 * 「今天没有安排」上。他选的是换成天气：每天都有数，永远不会空。
 * 任务还在：**有任务时先显示任务，任务后面才是天气**（见 [RoomPanels]）。
 *
 * ## 三条请求，从一头走到另一头
 *
 * 1. `whois.pconline.com.cn/ipJson.jsp` —— 按出口 IP 认出城市，**给中文名**。
 *    ★ 它回的是 **GBK**，不是 UTF-8：按 UTF-8 解出来是满地乱码的「涓枃」。
 * 2. `geocoding-api.open-meteo.com` —— 中文城市名换成经纬度。
 * 3. `api.open-meteo.com/v1/forecast` —— 经纬度换成天气。**不要 key**。
 *
 * 城市的经纬度会跟天气一起落盘，所以**稳定状态下每小时只发第 3 条**。
 * 换城市（比如回家、出差）不会自己发现 —— 想换就在设置里清掉缓存。
 *
 * ## 和「云端总闸」的关系
 *
 * 这三条都是对外请求，所以和 [NewsFeed] 一样归那道总闸管：关着就一个字节都不发，
 * 只用盘上那份，并把「几点取的」写在屏上。
 *
 * ## 纯算术在 [WeatherMath]
 *
 * 三条 JSON 三种壳子，且**字段缺席不报错**（`optDouble` 给 NaN，`optInt` 给 0）——
 * 读错一个字段的症状是「上海 0° 晴」，看着像真的。所以解析全部拆出来钉住。
 */
internal object Weather {

    private const val DIR = "room"
    private const val FILE = "weather.json"
    private const val TIMEOUT = 8_000

    // ── 缓存 ────────────────────────────────────────────────────────────

    private fun file(ctx: Context): File? = try {
        val d = File(ctx.filesDir, DIR).apply { mkdirs() }
        File(d, FILE)
    } catch (_: Exception) {
        null
    }

    /** 盘上那份天气；没有就是 null。 */
    fun cached(ctx: Context): WeatherNow? = load(ctx)?.now

    fun clear(ctx: Context) {
        try {
            file(ctx)?.delete()
        } catch (_: Exception) {
        }
    }

    private class Saved(val city: String, val lat: Double, val lon: Double, val now: WeatherNow?)

    private fun load(ctx: Context): Saved? {
        val f = file(ctx) ?: return null
        if (!f.isFile) return null
        return try {
            val o = JSONObject(f.readText())
            Saved(
                city = o.optString("city"),
                lat = o.optDouble("lat", Double.NaN),
                lon = o.optDouble("lon", Double.NaN),
                now = WeatherMath.fromJson(o.optJSONObject("now")),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun save(ctx: Context, city: String, lat: Double, lon: Double, now: WeatherNow?): Boolean {
        val f = file(ctx) ?: return false
        return try {
            val o = JSONObject()
                .put("city", city)
                .put("lat", lat)
                .put("lon", lon)
                .put("at", now?.at ?: 0L)
                .put("now", WeatherMath.toJson(now))
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(o.toString())
                tmp.delete()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    // ── 网取 ────────────────────────────────────────────────────────────

    /**
     * 出去取一次。**必须在后台线程调**（内部同步阻塞，最多三次 [TIMEOUT]）。
     *
     * 取到了 → 落盘并返回；没取到 → null（调用方保留盘上那份）。
     */
    fun fetch(ctx: Context): WeatherNow? {
        val app = ctx.applicationContext
        val old = load(app)

        // ★ 有 GPS 就用 GPS 的经纬度。出口 IP 在移动网络出口下常常落到省会,
        //   天气会差一档(他 2026-11-07 要「开 GPS」就是为了这个)。
        //   ★ 这一步**不等**:没权限/太久没定就立刻返回,定到的结果下一次取天气才用上。
        Where.refresh(app)
        val fix = Where.cached(app)

        var city = fix?.city?.takeIf { it.isNotBlank() } ?: old?.city.orEmpty()
        var lat = fix?.lat ?: old?.lat ?: Double.NaN
        var lon = fix?.lon ?: old?.lon ?: Double.NaN
        // 有坐标、只是城市名没换出来(Geocoder 在国产 ROM 上常常不可用)——
        // 不能因为缺一个名字就退回 IP,那样等于白定位。
        if (city.isEmpty() && !lat.isNaN() && !lon.isNaN()) city = "当前位置"

        // 1+2 只会在这个城市**第一次**取的时候跑
        if (city.isEmpty() || lat.isNaN() || lon.isNaN()) {
            city = get(app, IP_URL, "GBK")?.let { WeatherMath.cityFromIpJson(it) }.orEmpty()
            if (city.isEmpty()) {
                trace(app, "天气:认不出城市，这次不取")
                return null
            }
            val geo = get(app, GEO_URL + URLEncoder.encode(city, "UTF-8"), "UTF-8")
                ?.let { WeatherMath.coordFromGeoJson(it) }
            if (geo == null) {
                trace(app, "天气:「$city」换不到经纬度")
                return null
            }
            lat = geo.first
            lon = geo.second
            trace(app, "天气:城市=$city 坐标=$lat,$lon")
        }

        val text = get(app, forecastUrl(lat, lon), "UTF-8")
        if (text == null) {
            trace(app, "天气:预报没取到")
            return null
        }
        val now = WeatherMath.nowFromForecast(wrapForecast(text), city, System.currentTimeMillis())
        if (now == null) {
            trace(app, "天气:预报解析出 0 条（${text.length} 字节）")
            return null
        }
        save(app, city, lat, lon, now)
        trace(app, "天气:$city ${WeatherMath.desc(now.code)} ${WeatherMath.tempText(now.temp)}")
        return now
    }

    /** 预报那一条要的字段全在根上（current + daily），这里原样交回去。 */
    private fun wrapForecast(text: String): JSONObject? = try {
        JSONObject(text)
    } catch (_: Exception) {
        null
    }

    private fun forecastUrl(lat: Double, lon: Double): String =
        "https://api.open-meteo.com/v1/forecast" +
            "?latitude=" + fmt(lat) + "&longitude=" + fmt(lon) +
            "&current=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            // 未来两小时要看逐小时那组。★ 和 daily 一起要,**不要**换成 forecast_hours ——
            //   那是另一种窗口口径,换了以后 daily 那几项就取不到了。
            "&hourly=weather_code,precipitation_probability" +
            "&timezone=auto&forecast_days=2"

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.4f", v)

    private const val IP_URL = "https://whois.pconline.com.cn/ipJson.jsp?json=true"
    private const val GEO_URL = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=zh&format=json&name="

    private fun get(app: Context, url: String, charset: String): String? = try {
        val conn = CloudNet.conn(url, app).apply {
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            requestMethod = "GET"
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
            )
        }
        try {
            val code = conn.responseCode
            if (code != 200) {
                trace(app, "天气:HTTP $code（${url.substringBefore('?').takeLast(28)}）")
                null
            } else {
                conn.inputStream.bufferedReader(Charset.forName(charset)).use { it.readText() }
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        trace(app, "天气:取不到（${e.javaClass.simpleName}:${e.message}）")
        null
    }

    private fun trace(ctx: Context, msg: String) {
        try {
            ModelManager.get(ctx).trace(msg)
        } catch (_: Exception) {
        }
    }
}

/**
 * 屏上那一条天气。**温度一律摄氏度**（接口给的就是摄氏度，别再做单位换算）。
 *
 * [rain] / [nRain] 是降水概率百分比；`-1` = 这个接口没给，别当 0 画出来。
 */
internal data class WeatherNow(
    val city: String,
    val temp: Double,
    val code: Int,
    val high: Double,
    val low: Double,
    val rain: Int,
    val nCode: Int,
    val nHigh: Double,
    val nLow: Double,
    val nRain: Int,
    val at: Long,
    /**
     * 未来两小时（含当前这个整点）里**最重**的天气码，`-1` = 接口没给。
     *
     * ★ 2026-11-07 他要「实时识别未来两小时是否下雨，好提醒带不带伞」。
     *   单独一档而不是复用 [code]：`code` 是「现在」，而带伞这件事问的是「出门以后」。
     */
    val soonCode: Int = -1,
    /** 未来两小时里**最高**的降水概率；`-1` = 接口没给（别当 0 画出来）。 */
    val soonRain: Int = -1,
)

/** [Weather] 的纯算术部分：三份 JSON 的解析 + 屏上那几个字。能单测。 */
internal object WeatherMath {

    /** 默认城市。认不出 IP、又没缓存时用，**屏上会写明是哪个城市**。 */
    const val FALLBACK_CITY = "北京"

    // ── 解析 ────────────────────────────────────────────────────────────

    /** pconline 那份：`{"city":"上海市","pro":"上海市",...}`。取不到返回空串。 */
    fun cityFromIpJson(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        return try {
            val o = JSONObject(t)
            val c = o.optString("city").trim()
            val p = o.optString("pro").trim()
            // 「上海市」→「上海」：屏上一行只放得下两三个字，多一个「市」就换行了
            val short = c.removeSuffix("市").removeSuffix("地区").removeSuffix("自治州")
            if (short.isNotEmpty()) short else p.removeSuffix("省")
        } catch (_: Exception) {
            ""
        }
    }

    /** open-meteo 地理编码：`{"results":[{"latitude":..,"longitude":..}]}`。 */
    fun coordFromGeoJson(text: String): Pair<Double, Double>? = try {
        val r = JSONObject(text.trim()).optJSONArray("results")?.optJSONObject(0)
        val lat = r?.optDouble("latitude", Double.NaN) ?: Double.NaN
        val lon = r?.optDouble("longitude", Double.NaN) ?: Double.NaN
        if (lat.isNaN() || lon.isNaN()) null else lat to lon
    } catch (_: Exception) {
        null
    }

    /**
     * 预报那一份。
     *
     * ★ 读的是 `current` + `daily` 两个对象。**缺一个就返回 null** ——
     *   半份数据画在屏上比空屏更坏：`optDouble` 缺席给 NaN，一路 `%.0f` 下去就是「0°」，
     *   而「0°」是个合法温度，没人看得出它是假的。
     */
    fun nowFromForecast(o: JSONObject?, city: String, at: Long): WeatherNow? {
        if (o == null) return null
        val cur = o.optJSONObject("current") ?: return null
        val temp = cur.optDouble("temperature_2m", Double.NaN)
        if (temp.isNaN()) return null
        val code = cur.optInt("weather_code", -1)

        val d = o.optJSONObject("daily")
        val hi = arrDouble(d, "temperature_2m_max", 0)
        val lo = arrDouble(d, "temperature_2m_min", 0)
        val rain = arrInt(d, "precipitation_probability_max", 0)
        val nCode = arrInt(d, "weather_code", 1)
        val nHi = arrDouble(d, "temperature_2m_max", 1)
        val nLo = arrDouble(d, "temperature_2m_min", 1)
        val nRain = arrInt(d, "precipitation_probability_max", 1)

        val soon = soonHorizon(o, at)

        return WeatherNow(
            city = city.ifBlank { FALLBACK_CITY },
            temp = temp, code = code,
            high = hi, low = lo, rain = rain,
            nCode = nCode, nHigh = nHi, nLow = nLo, nRain = nRain,
            at = at,
            soonCode = soon.first, soonRain = soon.second,
        )
    }

    /**
     * 未来两小时：最重的天气码 + 最高的降水概率。
     *
     * ## 为什么按「钟点字符串」找位置
     *
     * `hourly.time` 是**当地时间的整点串**（`2026-11-07T14:00`），从当天 00:00 排起
     * （`timezone=auto`）。拿 `at` 算出本地钟点、拼成同样的串去对上那一条 ——
     * 比按数组下标猜稳:接口多给或少给几天,下标就错位了,而错位的症状是
     * 「拿明天下午的天气说今天要不要带伞」,看不出来。
     *
     * 两头缺一个就返回 `-1 to -1`（= 不知道）。**不把「不知道」当「不下雨」**。
     */
    fun soonHorizon(o: JSONObject?, at: Long): Pair<Int, Int> {
        val h = o?.optJSONObject("hourly") ?: return -1 to -1
        val times = h.optJSONArray("time") ?: return -1 to -1
        val codes = h.optJSONArray("weather_code")
        val probs = h.optJSONArray("precipitation_probability")

        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = at
        val key = String.format(
            java.util.Locale.US, "%04d-%02d-%02dT%02d:00",
            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH), cal.get(java.util.Calendar.HOUR_OF_DAY),
        )
        var start = -1
        for (i in 0 until times.length()) {
            if (times.optString(i) == key) {
                start = i
                break
            }
        }
        if (start < 0) return -1 to -1

        var worst = -1
        var p = -1
        for (i in start..minOf(start + 2, times.length() - 1)) {
            val c = codes?.optInt(i, -1) ?: -1
            if (c >= 0 && harsh(c) > harsh(worst)) worst = c
            val q = probs?.optInt(i, -1) ?: -1
            if (q > p) p = q
        }
        return worst to p
    }

    /**
     * 天气码「有多重」。只用来在两小时窗口里挑最坏的那一档，**不是**给人看的等级。
     * 雷 > 雪 > 雨 > 雾 > 其余。`-1`（没给）排在最前，好让任何真天气都盖过它。
     */
    fun harsh(code: Int): Int = when {
        code < 0 -> -1
        code >= 95 -> 4
        code in 71..77 || code in 85..86 -> 3
        code in 51..67 || code in 80..82 -> 2
        code == 45 || code == 48 -> 1
        else -> 0
    }

    /** 落盘用。`now` 为 null 时存一个空对象（下次读回来当作没有）。 */
    fun toJson(w: WeatherNow?): JSONObject {
        val o = JSONObject()
        if (w == null) return o
        return o.put("city", w.city).put("temp", w.temp).put("code", w.code)
            .put("high", w.high).put("low", w.low).put("rain", w.rain)
            .put("nCode", w.nCode).put("nHigh", w.nHigh).put("nLow", w.nLow).put("nRain", w.nRain)
            .put("at", w.at)
            .put("soonCode", w.soonCode).put("soonRain", w.soonRain)
    }

    /**
     * [toJson] 的逆运算：把盘上那份还原成 [WeatherNow]。
     *
     * ★ 别拿 [nowFromForecast] 来干这件事 —— 那是**接口原样**的壳子
     *   （`current` + `daily`），盘上存的是压平后的 `temp` / `code` / `night`。
     *   两个壳子混用的结果是「取值永远为 null」，也就是天气永远显示不出来。
     */
    fun fromJson(o: JSONObject?): WeatherNow? {
        if (o == null || o.length() == 0) return null
        val temp = o.optDouble("temp", Double.NaN)
        if (temp.isNaN()) return null
        return WeatherNow(
            city = o.optString("city").ifBlank { FALLBACK_CITY },
            temp = temp,
            code = o.optInt("code", -1),
            high = o.optDouble("high", Double.NaN),
            low = o.optDouble("low", Double.NaN),
            rain = o.optInt("rain", -1),
            nCode = o.optInt("nCode", -1),
            nHigh = o.optDouble("nHigh", Double.NaN),
            nLow = o.optDouble("nLow", Double.NaN),
            nRain = o.optInt("nRain", -1),
            at = o.optLong("at", 0L),
            // ★ 老档案里没有这两项,optInt 的默认值 -1 正好就是「不知道」
            soonCode = o.optInt("soonCode", -1),
            soonRain = o.optInt("soonRain", -1),
        )
    }

    private fun arrDouble(d: JSONObject?, key: String, i: Int): Double {
        val a: JSONArray = d?.optJSONArray(key) ?: return Double.NaN
        if (i >= a.length()) return Double.NaN
        return a.optDouble(i, Double.NaN)
    }

    private fun arrInt(d: JSONObject?, key: String, i: Int): Int {
        val a: JSONArray = d?.optJSONArray(key) ?: return -1
        if (i >= a.length()) return -1
        return a.optInt(i, -1)
    }

    // ── 屏上的字 ────────────────────────────────────────────────────────

    /** WMO 天气代码 → 中文。表见 <https://open-meteo.com/en/docs>。 */
    fun desc(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "晴间多云"
        2 -> "多云"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55 -> "毛毛雨"
        56, 57 -> "冻雨"
        61 -> "小雨"
        63 -> "中雨"
        65 -> "大雨"
        66, 67 -> "冻雨"
        71 -> "小雪"
        73 -> "中雪"
        75 -> "大雪"
        77 -> "雪粒"
        80, 81, 82 -> "阵雨"
        85, 86 -> "阵雪"
        95 -> "雷阵雨"
        96, 99 -> "雷阵雨伴冰雹"
        else -> ""
    }

    /** `4°`。一位小数只在它不是整数时才露（「4°」不是「4.0°」）。 */
    fun tempText(v: Double): String {
        if (v.isNaN()) return "—"
        val r = Math.round(v * 10) / 10.0
        return if (r == Math.floor(r)) "${r.toInt()}°" else "$r°"
    }

    /** `9° / -1°`。缺一头就只画有的那头。 */
    fun rangeText(hi: Double, lo: Double): String = when {
        hi.isNaN() && lo.isNaN() -> ""
        hi.isNaN() -> "最低 " + tempText(lo)
        lo.isNaN() -> "最高 " + tempText(hi)
        else -> tempText(hi) + " / " + tempText(lo)
    }

    /** `降水 30%`。没给就是空串 —— 别把「不知道」画成「0%」。 */
    fun rainText(p: Int): String = if (p < 0) "" else "降水 $p%"

    /** `02:30`。复用热点那套口径。 */
    fun stamp(at: Long): String = NewsFeed.stamp(at)
}
