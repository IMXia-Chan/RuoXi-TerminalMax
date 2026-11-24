package com.example.touchpad

/**
 * 窗外那片景要长什么样 —— 把天气折成 `room.js` 认得的两档。
 *
 * ## 为什么单独一个文件
 *
 * 他的原话（2026-11-07）：「房间后面不是有外景吗，外景天气我想和现实同步」。
 * 范围是他自己圈的那一档：**昼夜 + 雨雪 + 阴晴雾**。
 *
 * 折的这一步全是纯函数，所以能钉住：`WeatherNow.code` 是 WMO 码（0–99），
 * 而某一天如果 open-meteo 换了一种码、或者 `weather_code` 缺席（`optInt` 给 -1），
 * 读错一档的症状是「外面下着雨、屋里画着月亮」—— 不会报错，也没人会天天盯窗外看。
 *
 * ## 两档，不是一个
 *
 * - [phase]：天光，`day` / `dusk` / `night`。跟天气无关，只跟几点有关。
 * - [kind]：天气，`clear` / `partly` / `overcast` / `fog` / `rain` / `snow` / `thunder`。
 *
 * 两档分开递，是因为 `room.js` 里它们管的是**不同东西**：天光换配色和星月，
 * 天气只在天光上做减法（拉灰、压暗、加水珠）。合成一档就要七种天气 × 三种天光 = 21 种画法。
 *
 * ## 认不出来的一律当晴
 *
 * ★ 不给「未知」这一档。窗外必须**总有一个样子**，而「晴」是最不刺眼的那个错，
 * 也从不会让屋里比现实更暗。真下着雨却画成晴，比画成一片灰更像 bug —— 但两者都不会崩。
 */
internal object SceneWeather {

    // ── 两档 ────────────────────────────────────────────────────────────

    const val DAY = "day"
    const val DUSK = "dusk"
    const val NIGHT = "night"

    const val CLEAR = "clear"
    const val PARTLY = "partly"
    const val OVERCAST = "overcast"
    const val FOG = "fog"
    const val RAIN = "rain"
    const val SNOW = "snow"
    const val THUNDER = "thunder"

    /**
     * WMO 天气码 → 窗外那一档。
     *
     * 表见 <https://open-meteo.com/en/docs>，和 [WeatherMath.desc] 用的是同一套码，
     * 只是那边要「小雨 / 中雨 / 大雨」，这边只关心画出来像什么。
     * `-1`（接口没给）落进 `else`，当晴。
     */
    fun kind(code: Int): String = when (code) {
        0 -> CLEAR
        1, 2 -> PARTLY
        3 -> OVERCAST
        45, 48 -> FOG
        // 毛毛雨、冻雨、小雨中雨大雨、阵雨 —— 画面上都是「在下雨」
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> RAIN
        // 小雪中雪大雪、雪粒、阵雪
        71, 73, 75, 77, 85, 86 -> SNOW
        95, 96, 99 -> THUNDER
        else -> CLEAR
    }

    /**
     * 几点 → 天光。**这是退路，不是主路。**
     *
     * ★ 2026-11-07 改：主路是 [phaseAt]，按经纬度算真实的太阳高度角（见 [SunMath]）。
     *   这张固定钟点表只在**不知道他在哪**时兜底 —— 定位权限没给、`Where` 一次都没落盘。
     *   它一定会有「一月中旬七点还是黑的 / 七月七点早亮了」的偏差，这是它本来就该有的样子：
     *   一个不知道位置的钟，本来就只能猜。
     *
     * 5–6 点算黄昏那一档：黎明和黄昏**在画上是同一件事**（低角度暖光、天色偏红）。
     */
    fun phase(hour: Int): String = when (hour) {
        in 7..16 -> DAY
        in 5..6, in 17..18 -> DUSK
        else -> NIGHT
    }

    /**
     * 该用哪一档天光。
     *
     * 有经纬度就按当地时间算太阳高度角（[SunMath.phase]）；没有就退回固定的钟点表。
     * `hour` 只在退路那条上用到，所以两个都传进来，别在调用方分叉。
     */
    fun phaseAt(lat: Double?, lon: Double?, at: Long, hour: Int): String {
        if (lat != null && lon != null && !lat.isNaN() && !lon.isNaN()) {
            return SunMath.phase(lat, lon, at)
        }
        return phase(hour)
    }

    /**
     * 递给 `Her.setWeather(...)` 的那一个字串。
     *
     * ★ 手动拼而不是 `JSONObject`：这段结果要拿来**逐字比较**（见 ConMarnActivity 的
     *   `pushSceneWeather`），`JSONObject` 不保证键序，两次一样的天气可能拼出两种字串，
     *   那样每一拍都会重发一遍、`room.js` 每一拍都重画一遍贴图。
     *
     * `temp` 只是顺手带上（以后可能要往窗外画温度），`room.js` 现在不读。
     *
     * `bg` 是按天气挑中的那张**整张背景照片**的文件名（见 [WxPhoto]）；没有就整段不写，
     * 那边保持 `room.json` 原来那张。
     */
    fun json(w: WeatherNow?, phase: String, bg: String? = null): String {
        val sb = StringBuilder(64)
        sb.append("{\"phase\":\"").append(phase)
            .append("\",\"kind\":\"").append(kind(w?.code ?: -1)).append('"')
        val t = w?.temp ?: Double.NaN
        if (!t.isNaN()) {
            sb.append(",\"temp\":").append(Math.round(t * 10) / 10.0)
        }
        // ★ 文件名只来自 [WxPhoto] 自己拼的那几个形状，不会带引号/反斜杠；
        //   这里仍然再挡一遍，因为这段要拼进 JS 字面量，一个引号就能把整句弄坏。
        if (bg != null && bg.isNotEmpty() && bg.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) {
            sb.append(",\"bg\":\"").append(bg).append('"')
        }
        return sb.append('}').toString()
    }
}
