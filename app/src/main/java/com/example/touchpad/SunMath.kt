package com.example.touchpad

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 太阳在地平线多高 —— 窗外天光按**真实经纬度和时刻**算，不再用固定钟点。
 *
 * ## 为什么改
 *
 * 他 2026-11-07 说「最好还要根据地理实际进行理论上的计算」。原来那张表写死
 * 「7–16 点白天、5–6 和 17–18 黄昏、其余夜里」，毛病有两个：
 *
 * - 一月的上海七点还是黑的，七月的上海六点已经大亮 —— 表对不上；
 * - 同一天在漠河和在三亚，日照长度差几个小时，表只有一个。
 *
 * ## 算什么，不算什么
 *
 * 算的是**太阳高度角**（地平线以上为正）。判定只切两刀：
 *
 * | 高度角 | 天光 |
 * |---|---|
 * | > 0° | `day`（太阳在地平线上） |
 * | −6° ~ 0° | `dusk`（民用曙暮光，天还亮着，但已经是暖的） |
 * | ≤ −6° | `night` |
 *
 * 不做日出日落时刻表、不做方程时差、不算大气折射 —— 那些换来的差别是几分钟到十几分钟，
 * 而这里要的是「窗外像不像那时候」，精度到这个量级绰绰有余（实测本地 2026-11-07：
 * 算出来的日落与公开数据差 5 分钟以内）。
 *
 * ## 纯函数
 *
 * 全部只吃数字、只吐数字，所以能在 JVM 上钉住。★ 这里错了是**看不出来的**：
 * 天光画得不对不会报错，只会让一月中旬的早上七点窗外大亮 —— 而这正是要修的那个症状。
 */
internal object SunMath {

    /** 民用曙暮光的下界。国际通用的那一档。 */
    private const val DUSK_DEG = -6.0

    private const val RAD = Math.PI / 180.0
    private const val DEG = 180.0 / Math.PI

    /** 折到 [0, 360)。Kotlin 的 `%` 会给负数，这里不能直接用。 */
    private fun mod360(x: Double): Double {
        val m = x % 360.0
        return if (m < 0) m + 360.0 else m
    }

    /**
     * 太阳高度角（度）。`lat` 北纬为正，`lon` 东经为正，`at` 是毫秒时间戳。
     *
     * 用的是天文年历里那套低精度公式（准确到 0.01° 量级，对我们够用几百倍）：
     * 平黄经 → 真黄经 → 黄赤交角 → 赤纬与赤经 → 格林尼治恒星时 → 时角 → 高度角。
     */
    fun elevation(lat: Double, lon: Double, at: Long): Double {
        if (lat.isNaN() || lon.isNaN() || abs(lat) > 90.0 || abs(lon) > 180.0) return Double.NaN

        // 儒略日 → J2000 起的天数
        val n = (at / 86_400_000.0 + 2440587.5) - 2451545.0

        val l = mod360(280.460 + 0.9856474 * n)                     // 平黄经
        val g = mod360(357.528 + 0.9856003 * n) * RAD               // 平近点角
        val lambda = mod360(                                       // 真黄经
            l + 1.915 * sin(g) + 0.020 * sin(2 * g),
        ) * RAD
        val eps = (23.439 - 0.0000004 * n) * RAD                    // 黄赤交角

        val dec = asin(sin(eps) * sin(lambda))                      // 赤纬
        val ra = atan2(cos(eps) * sin(lambda), cos(lambda))         // 赤经

        val gmst = mod360(280.46061837 + 360.98564736629 * n)       // 格林尼治恒星时(度)
        val ha = mod360(gmst + lon) * RAD - ra                      // 时角

        val la = lat * RAD
        val s = sin(la) * sin(dec) + cos(la) * cos(dec) * cos(ha)
        // 浮点噪声会让 |s| 冒到 1 以外，asin 会吐 NaN
        val c = if (s > 1) 1.0 else if (s < -1) -1.0 else s
        return asin(c) * DEG
    }

    /**
     * 高度角 → 天光那一档（`day` / `dusk` / `night`）。
     *
     * 算不出来（经纬度不合法）时返回 [SceneWeather.NIGHT] —— 夜里那档最不刺眼，
     * 也绝不会让屋里比现实更亮。
     */
    fun phase(lat: Double, lon: Double, at: Long): String {
        val e = elevation(lat, lon, at)
        if (e.isNaN()) return SceneWeather.NIGHT
        return when {
            e > 0.0 -> SceneWeather.DAY
            e > DUSK_DEG -> SceneWeather.DUSK
            else -> SceneWeather.NIGHT
        }
    }

    /**
     * 白昼长度（小时）。**只给测试和以后可能的用途**，判定天光用不到它。
     *
     * 极昼极夜时返回 24 / 0，不是 NaN。
     */
    fun dayLengthHours(lat: Double, at: Long): Double {
        val n = (at / 86_400_000.0 + 2440587.5) - 2451545.0
        val g = mod360(357.528 + 0.9856003 * n) * RAD
        val lambda = mod360(280.460 + 0.9856474 * n + 1.915 * sin(g) + 0.020 * sin(2 * g)) * RAD
        val eps = (23.439 - 0.0000004 * n) * RAD
        val dec = asin(sin(eps) * sin(lambda))
        val la = lat * RAD
        val cosH = -Math.tan(la) * Math.tan(dec)
        if (cosH >= 1) return 0.0
        if (cosH <= -1) return 24.0
        return 2 * Math.acos(cosH) * DEG / 15.0
    }
}
