package com.example.touchpad

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 每天第一次进房间，她先开口说一句 —— **按点上、按天气、按今天的事**。
 *
 * ## 他要的原话
 *
 * 「每天第一次进app，早上说早上好，主人。今天天气。。。，需要。。。，别往了今天有。。。事情要做，
 *   下午就说下午好啥啥的，反正别一进去就只有你好。」
 * 「然后还有，如果是凌晨，就得说这么晚了怎么还没睡啥啥的，就得有活人感」
 * 「不只是凌晨，反正就是有活人感！」
 *
 * ## 「活人感」怎么落地
 *
 * **不是**从一张台词表里挑一句 —— 那样第三天就能听出是程序。
 * 这句话由**她自己想**：这条路走 [AiAgent.spontaneous]（一次不带工具的模型调用），
 * 人设、心情、说话习惯全都在里面，跟我们房间那条「她说的不是台词」是同一条路
 * （见 [ProactiveGreeting] 的文件头）。
 *
 * 这个文件只负责三件**能算准**的事：
 *   1. 此刻是哪个时段、该不该问「怎么还没睡」；
 *   2. 未来两小时要不要带伞；
 *   3. 今天有哪几件安排。
 * 然后把这些**事实**交给她，让她用自己的话讲。模型说不出话时退回 [fallback] ——
 * 一句不用润色的实话，总比一片安静强。
 *
 * ## 一天只一次
 *
 * 进进出出房间很多次，只有**当天第一次**说。账落在 [PREF] 里，跨进程、跨重启都认。
 */
internal object DayGreet {

    private const val PREF = "day_greet"
    private const val KEY_DAY = "last_day"

    // ── 一天一次 ────────────────────────────────────────────────────────

    /** `2026-11-07`。**本地日期**，不是 UTC —— 凌晨零点跨天要跟着人的作息走。 */
    fun dayKey(at: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(at))

    fun greeted(ctx: Context, day: String): Boolean = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_DAY, "") == day
    } catch (_: Exception) {
        true                    // 读不到就当成说过:宁可不说话,也别每次进房间都念一遍
    }

    fun mark(ctx: Context, day: String) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY_DAY, day).apply()
        } catch (_: Exception) {
        }
    }

    // ── 时段 ────────────────────────────────────────────────────────────

    /** 给他看/给她用的时段名。**这是描述，不是台词** —— 台词由她自己想。 */
    fun slot(hour: Int): String = when (hour) {
        in 0..4 -> "凌晨"
        in 5..6 -> "清晨"
        in 7..10 -> "早上"
        in 11..12 -> "中午"
        in 13..17 -> "下午"
        in 18..19 -> "傍晚"
        in 20..22 -> "晚上"
        else -> "深夜"
    }

    /**
     * 这个点说「早上好」是不是傻话。
     *
     * ★ 凌晨 0–4 和深夜 23 点都算。他点名要的就是这一档：
     *   「如果是凌晨，就得说这么晚了怎么还没睡」。
     */
    fun isLate(hour: Int): Boolean = hour in 0..4 || hour >= 23

    // ── 带不带伞 ────────────────────────────────────────────────────────

    /** 「雨」「雪」「雷雨」= 两小时内真的要下；「可能下雨」= 只有概率，没有天气码；空 = 不用提。 */
    fun soon(soonCode: Int, soonRain: Int): String {
        if (soonCode >= 0) {
            when (SceneWeather.kind(soonCode)) {
                SceneWeather.THUNDER -> return "雷雨"
                SceneWeather.SNOW -> return "雪"
                SceneWeather.RAIN -> return "雨"
                else -> Unit
            }
        }
        // 天气码说没雨、但概率过半 —— 那是「预报拿不准」，值得提一句，但不该说得像板上钉钉
        return if (soonRain >= 50) "可能下雨" else ""
    }

    // ── 交给她的事实 ────────────────────────────────────────────────────

    private fun deg(v: Double): String = if (v.isNaN()) "" else Math.round(v).toString()

    /**
     * 一段**事实**，不是一段话术。`tasks` 是「14:00 交作业」这种已经排好序的短句。
     */
    fun facts(hour: Int, day: String, w: WeatherNow?, tasks: List<String>): String {
        val b = StringBuilder(160)
        b.append("现在").append(slot(hour)).append("，").append(day).append("。\n")
        if (w != null) {
            b.append(w.city).append("现在").append(WeatherMath.desc(w.code))
            if (!w.temp.isNaN()) b.append("，").append(deg(w.temp)).append(" 度")
            val range = WeatherMath.rangeText(w.high, w.low)
            if (range.isNotEmpty()) b.append("；今天 ").append(range)
            b.append("。\n")

            val s = soon(w.soonCode, w.soonRain)
            when {
                s.isNotEmpty() -> b.append("未来两小时：").append(s).append("。\n")
                // 知道「不下雨」也要说 —— 不然她就分不清「没雨」和「不知道」，
                // 而后者不该被当成前者讲给他听。
                w.soonCode >= 0 || w.soonRain >= 0 -> b.append("未来两小时：没有降水。\n")
                else -> b.append("未来两小时：不知道（接口这次没给）。\n")
            }
        } else {
            b.append("天气预报这次没取到。\n")
        }
        if (tasks.isNotEmpty()) {
            b.append("今天要做的事：").append(tasks.joinToString("；")).append("。\n")
        }
        return b.toString()
    }

    /**
     * 递给她的话。
     *
     * ★ 把她**不该说**的也写清楚（别用「早上好」、别解释数据来源）——
     *   模型最容易犯的错就是照着事实一条条念，那样听起来像播报，不像人。
     */
    fun hint(facts: String): String = buildString {
        append("（系统提示：他今天第一次打开房间。下面是此刻你确实知道的事实，")
        append("是给你用的，不是让你逐条念的。）\n")
        append(facts)
        append("用一句话跟他打招呼，就像你刚好看见他进来。")
        append("按上面的时段说话：只有早晨才说「早上好」，中午下午晚上各说各的；")
        append("凌晨和深夜要问一句他怎么还没睡，别用「早上好」。")
        append("要下雨就提醒他带伞；有安排就顺口提一句；没有安排就别提。")
        append("别说「根据数据」「天气显示」这类话，也别解释你怎么知道的。")
        append("只回这一句话，不要引号，不要署名。")
    }

    /**
     * 模型没话说时的退路。
     *
     * ★ 只拼事实，不装人设 —— 装出来的活泼比平静更像程序。
     */
    fun fallback(hour: Int, w: WeatherNow?, tasks: List<String>): String {
        val b = StringBuilder()
        b.append(
            when {
                hour in 0..4 -> "这么晚了，还没睡。"
                hour in 5..6 -> "早。"
                hour in 7..10 -> "早上好。"
                hour in 11..12 -> "中午好。"
                hour in 13..17 -> "下午好。"
                hour in 18..19 -> "晚上好。"
                else -> "这么晚还没睡。"
            }
        )
        if (w != null) {
            b.append(w.city).append(" ").append(WeatherMath.desc(w.code))
            if (!w.temp.isNaN()) b.append(" ").append(deg(w.temp)).append(" 度")
            b.append("。")
            when (soon(w.soonCode, w.soonRain)) {
                "可能下雨" -> b.append("两小时内可能有雨，带把伞。")
                "" -> Unit
                else -> b.append("两小时内要下").append(soon(w.soonCode, w.soonRain)).append("，带把伞。")
            }
        }
        if (tasks.isNotEmpty()) {
            b.append("今天：").append(tasks.joinToString("；")).append("。")
        }
        return b.toString()
    }

    /** 今天还剩哪几件，写成「14:00 交作业」这种短句，最多 [MAX_TASKS] 条。 */
    fun taskLines(plans: List<TaskPlan>, now: Long, end: Long, max: Int = MAX_TASKS): List<String> {
        val fmt = SimpleDateFormat("HH:mm", Locale.CHINA)
        return RoomPanelMath.todayPlans(plans, now, end).take(max).map { p ->
            val t = fmt.format(Date(p.nextAt))
            val what = RoomPanelMath.preview(p.prompt, 18)
            if (what.isBlank()) t else "$t $what"
        }
    }

    internal const val MAX_TASKS = 3

    /** 给测试用:按某个时刻算出本地小时。 */
    internal fun hourOf(at: Long): Int =
        Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.HOUR_OF_DAY)
}
