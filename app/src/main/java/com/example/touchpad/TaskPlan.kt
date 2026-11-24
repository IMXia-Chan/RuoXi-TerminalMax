package com.example.touchpad

import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * **一条定时任务**：「每天早上八点，帮我做某件事」里的那一条。
 *
 * ## 为什么要有这个东西
 *
 * 他能让她当场做一件事，但不能让她**明天早上八点**做 —— 到那会儿没人跟他说话，
 * 她也不能「等他一句话再开始」。所以这里存的不是一句话，而是
 * **一句到点能自己执行的话**：[prompt] 必须是写清楚、不依赖追问的。
 *
 * ## 一条任务 = 一件活
 *
 * 到点之后由 [TaskRunService] 建一件 [WorkJob]（走工作台那条路）跑，跑完的结果
 * 落回这里的 [lastText]，并弹一条通知。**同时上工作台的任务条**，所以他点进去能看全文。
 *
 * ## 谁都不许「当成已执行」
 *
 * [nextAt] 是**下一次该响的时刻**，由 [TaskMath.nextAfter] 算。跑失败记 [failCount]，
 * 不自动停 —— 但连续失败会在通知里说清是连着第几次，免得它一声不吭地烂在那儿。
 */
internal data class TaskPlan(
    val id: String,
    /** 到点要做的事。**一句完整的话**：那时候没人能追问。 */
    val prompt: String,
    /** [TaskMath.ONCE] / [TaskMath.DAILY] / [TaskMath.WORKDAYS] / [TaskMath.WEEKLY]。 */
    val kind: String,
    /** 一天里的第几分钟（0..1439）。ONCE 也存一份，只为显示。 */
    val minuteOfDay: Int,
    /** 周几（1=周一 … 7=周日）。只有 WEEKLY 用。 */
    val weekday: Int,
    /** ONCE 的那一个时刻。别的类型忽略。 */
    val onceAt: Long,
    val enabled: Boolean,
    /** 下一次该响的时刻。0 = 还没排上。 */
    val nextAt: Long,
    val lastAt: Long,
    val lastOk: Boolean,
    /** 上一次的结果摘要（给通知用，别太长）。 */
    val lastText: String,
    val failCount: Int,
    val createdAt: Long,
) {

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("prompt", prompt)
        .put("kind", kind)
        .put("minuteOfDay", minuteOfDay)
        .put("weekday", weekday)
        .put("onceAt", onceAt)
        .put("enabled", enabled)
        .put("nextAt", nextAt)
        .put("lastAt", lastAt)
        .put("lastOk", lastOk)
        .put("lastText", lastText)
        .put("failCount", failCount)
        .put("createdAt", createdAt)

    companion object {
        /** 读坏一条就当没有 —— 一条坏档案不该让他别的任务一起消失。 */
        fun fromJson(o: JSONObject?): TaskPlan? {
            if (o == null) return null
            val id = o.optString("id").trim()
            val prompt = o.optString("prompt").trim()
            if (id.isEmpty() || prompt.isEmpty()) return null
            return TaskPlan(
                id = id,
                prompt = prompt,
                kind = TaskMath.kindOf(o.optString("kind")),
                minuteOfDay = o.optInt("minuteOfDay", 480).coerceIn(0, 24 * 60 - 1),
                weekday = o.optInt("weekday", 1).coerceIn(1, 7),
                onceAt = o.optLong("onceAt"),
                enabled = o.optBoolean("enabled", true),
                nextAt = o.optLong("nextAt"),
                lastAt = o.optLong("lastAt"),
                lastOk = o.optBoolean("lastOk"),
                lastText = o.optString("lastText"),
                failCount = o.optInt("failCount"),
                createdAt = o.optLong("createdAt"),
            )
        }
    }
}

/**
 * 定时任务的**全部算术**：下一次几点、这会儿该不该响、怎么念成一句人话。
 *
 * ★ 抽出来单测的理由和 [CalMath] 一模一样：这儿算错**不报错**，症状是
 *   「每天早上八点」里有一天没响（或者半夜响了），而日志上一切正常。
 *   时间换算必须能在纯 JVM 上钉死，不能靠真机等一天。
 */
internal object TaskMath {

    const val ONCE = "once"
    const val DAILY = "daily"
    const val WORKDAYS = "workdays"
    const val WEEKLY = "weekly"

    const val MAX_PLANS = 20

    fun kindOf(raw: String): String = when (raw.trim().lowercase()) {
        ONCE, "一次", "一次性的", "定个点" -> ONCE
        WORKDAYS, "工作日", "周一到周五", "weekday" -> WORKDAYS
        WEEKLY, "每周", "周" -> WEEKLY
        else -> DAILY
    }

    /** 「08:00」「早上8点」→ 当天的第几分钟。→ null = 认不出来（**不许猜**）。 */
    fun minuteOfDay(time: String, zone: ZoneId = ZoneId.systemDefault()): Int? {
        val spec = CalMath.parseTime(time) ?: return null
        if (spec.allDay) return null
        val t = spec.time ?: return null
        return t.hour * 60 + t.minute
    }

    /** 周几：1=周一 … 7=周日。→ null = 认不出来。 */
    fun weekdayOf(raw: String): Int? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val n = s.toIntOrNull()
        if (n != null) return if (n in 1..7) n else null
        val table = mapOf(
            "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "日" to 7, "天" to 7,
        )
        val k = s.removePrefix("周").removePrefix("星期").removePrefix("礼拜").trim()
        return table[k.firstOrNull()?.toString() ?: ""]
    }

    /**
     * **下一次响的时刻**。→ null = 这条活儿到此为止（一次性任务已经过去了）。
     *
     * @param fromMs 从这个时刻**之后**找。判据是**严格大于**：闹钟在 08:00:00.000
     *   醒来，跑完是 08:00:40，再排下一次必须是**明天** 08:00，不是今天。
     */
    fun nextAfter(plan: TaskPlan, fromMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val from = Instant.ofEpochMilli(fromMs).atZone(zone)
        fun at(day: LocalDate): Long =
            day.atTime(LocalTime.of(plan.minuteOfDay / 60, plan.minuteOfDay % 60))
                .atZone(zone).toInstant().toEpochMilli()

        return when (plan.kind) {
            ONCE -> if (plan.onceAt > fromMs) plan.onceAt else null
            WORKDAYS -> {
                var d = from.toLocalDate()
                // 最多看 8 天：从今天起一周里必有一个工作日。
                for (i in 0 until 8) {
                    val dow = d.dayOfWeek
                    if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                        val t = at(d)
                        if (t > fromMs) return t
                    }
                    d = d.plusDays(1)
                }
                null
            }
            WEEKLY -> {
                var d = from.toLocalDate()
                for (i in 0 until 8) {
                    if (d.dayOfWeek.value == plan.weekday) {
                        val t = at(d)
                        if (t > fromMs) return t
                    }
                    d = d.plusDays(1)
                }
                null
            }
            else -> {
                // 每天：今天这个点还没到就是今天，否则明天。
                val today = at(from.toLocalDate())
                if (today > fromMs) today else at(from.toLocalDate().plusDays(1))
            }
        }
    }

    /** 一句人话：「每天 08:00」「工作日 07:30」「每周一 09:00」「11月8日 14:30（一次）」。 */
    fun describe(plan: TaskPlan, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = LocalTime.of(plan.minuteOfDay / 60, plan.minuteOfDay % 60)
        val hm = "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
        val names = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        return when (plan.kind) {
            ONCE -> {
                val z = Instant.ofEpochMilli(plan.onceAt).atZone(zone)
                "${z.monthValue}月${z.dayOfMonth}日 $hm（一次）"
            }
            WORKDAYS -> "工作日 $hm"
            WEEKLY -> names[(plan.weekday - 1).coerceIn(0, 6)] + " $hm"
            else -> "每天 $hm"
        }
    }

    /** 下一次是「什么时候」—— 给回执用，别甩毫秒。 */
    fun nextText(nextAt: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (nextAt <= 0) return "不会再响了"
        val n = Instant.ofEpochMilli(nextAt).atZone(zone)
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val hm = "${n.hour.toString().padStart(2, '0')}:${n.minute.toString().padStart(2, '0')}"
        val days = n.toLocalDate().toEpochDay() - now.toLocalDate().toEpochDay()
        val day = when (days) {
            0L -> "今天"
            1L -> "明天"
            2L -> "后天"
            else -> "${n.monthValue}月${n.dayOfMonth}日"
        }
        return "$day $hm"
    }

    /** 结果摘要截到通知放得下的长度。 */
    fun clip(text: String, max: Int = 200): String {
        val flat = text.trim().replace(Regex("\\s+"), " ")
        return if (flat.length <= max) flat else flat.take(max) + "…"
    }
}
