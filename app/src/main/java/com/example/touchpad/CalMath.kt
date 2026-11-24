package com.example.touchpad

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 「哪天几点」→ 两个毫秒数。**纯函数，能跑 JVM 单测。**
 *
 * ★ 为什么这一层要单独拿出来：模型（尤其手机上那个 4B）不会算日期。他说「周五」、
 *   「后天」、「11月8号下午三点」，模型能做的最好的事就是**原样把它们抄进参数里**。
 *   换算成毫秒是代码的活 —— 它在 [SelfHand] 里算错的话，症状是「日程写进去了，
 *   但是写到了去年」这种**不报错**的错。
 *
 * ★ 认不出来就返回 null，让上层给一句人话，**不猜**。猜错的日程比没写进去更糟：
 *   人会以为它会提醒自己。
 */
internal object CalMath {

    /** 她自己建的日程在描述里的记号。只认这一种 —— 没这个记号的一律不碰（那是别人的）。 */
    const val MARK = "〔若息〕"

    /** 一个算好的时段。`allDay` 的 `endMs` 是**第二天零点**（日历就是这么存的）。 */
    data class When(val beginMs: Long, val endMs: Long, val allDay: Boolean)

    /**
     * 没填时间（或写「整天」）。和「填了但认不出来」是两回事，见 [parseTime]。
     *
     * @param dayShift 「晚上12点」= **第二天**零点。加在日期上，见 [parse]。
     */
    data class TimeSpec(val time: LocalTime?, val allDay: Boolean, val dayShift: Int = 0)

    private const val DEFAULT_MINUTES = 60

    /**
     * @param date     「今天/明天/后天」「周五/下周三」「2026-11-08」「11月8号」，空 = 今天
     * @param time     「14:30」「下午3点」「晚上八点」不好认，只认数字；空 = 整天
     * @param minutes  时长，不填按 [DEFAULT_MINUTES]；整天时长忽略
     * @return 认不出来是 null
     */
    fun parse(
        date: String,
        time: String,
        minutes: Int,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): When? {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val base = parseDate(date, today) ?: return null
        val spec = parseTime(time) ?: return null
        val day = base.plusDays(spec.dayShift.toLong())
        if (spec.allDay || spec.time == null) {
            val begin = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            return When(begin, end, true)
        }
        val len = if (minutes in 5..(24 * 60)) minutes else DEFAULT_MINUTES
        val begin = LocalDateTime.of(day, spec.time).atZone(zone).toInstant().toEpochMilli()
        return When(begin, begin + len * 60_000L, false)
    }

    fun parseDate(raw: String, today: LocalDate): LocalDate? {
        val s = raw.trim()
        if (s.isEmpty()) return today
        when (s) {
            "今天", "今日", "今晚", "今早" -> return today
            "明天", "明日", "明早", "明晚" -> return today.plusDays(1)
            "后天" -> return today.plusDays(2)
            "大后天" -> return today.plusDays(3)
            "昨天", "昨日" -> return today.minusDays(1)
        }

        // 周X / 星期X / 礼拜X（可带「下」「本/这」）
        val prefix = when {
            s.startsWith("下") -> "next"
            s.startsWith("本") || s.startsWith("这") -> "this"
            else -> ""
        }
        val body = s.removePrefix("下").removePrefix("本").removePrefix("这").trim()
        val wm = Regex("^(?:周|星期|礼拜)([一二三四五六日天])$").find(body)
        if (wm != null) {
            val idx = "一二三四五六日天".indexOf(wm.groupValues[1])
            if (idx >= 0) {
                val monday = today.with(DayOfWeek.MONDAY)
                return when (prefix) {
                    "next" -> monday.plusDays(7L + idx)
                    "this" -> monday.plusDays(idx.toLong())
                    // 不带前缀 = **下一次**那天（今天算）
                    else -> {
                        val target = if (idx >= 6) 7 else idx + 1
                        today.plusDays(((target - today.dayOfWeek.value + 7) % 7).toLong())
                    }
                }
            }
        }

        // 数字：2026-11-08 / 2026年11月8日 / 11月8号 / 11.8
        val norm = s
            .replace(Regex("[年月日]"), "-")
            .replace("号", "")
            .replace(Regex("[／/．.－–—]"), "-")
            .replace(Regex("\\s+"), "")
            .trim('-')
        val parts = norm.split("-").filter { it.isNotEmpty() }
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return when (nums.size) {
            3 -> runCatching { LocalDate.of(nums[0], nums[1], nums[2]) }.getOrNull()
            2 -> {
                val m = nums[0]
                val d = nums[1]
                if (m !in 1..12 || d !in 1..31) return null
                var cand = runCatching { LocalDate.of(today.year, m, d) }.getOrNull() ?: return null
                // 十二月底说「1月5号」，指的是明年 —— 往后放一天以上才算过去。
                if (cand.isBefore(today.minusDays(1))) {
                    cand = runCatching { LocalDate.of(today.year + 1, m, d) }.getOrNull() ?: return null
                }
                cand
            }
            else -> null
        }
    }

    /**
     * @return null = **填了但认不出来**（要报错，不能悄悄当成整天）；
     *         `allDay=true` = 没填时间。
     */
    fun parseTime(raw: String): TimeSpec? {
        val s = raw.trim()
        if (s.isEmpty() || s == "整天" || s == "全天") return TimeSpec(null, true)
        val pm = listOf("下午", "晚上", "傍晚", "夜里", "半夜", "中午").any { s.startsWith(it) }
        // ★ 「晚上12点」说的是**第二天的零点**，不是中午十二点 —— 这个区别错了，
        //   提醒会早响整整十二个小时（或者晚十二个小时），而且看不出来。
        val lateNight = listOf("晚上", "夜里", "半夜").any { s.startsWith(it) }
        val rest = s
            .replace(Regex("^(下午|晚上|傍晚|夜里|半夜|早上|上午|凌晨|清晨|中午)"), "")
            .trim()
        // ★ 2026-11-07 支持「八点半」：`(\\d{1,2})?\\s*(分|半)?`
        //   以前只认「九点十五分」，而口语里最常见的是「九点半」——
        //   认不出就退成 null，用户会看到「我听不出几点」，明明说得很清楚。
        val m = Regex("^(\\d{1,3})\\s*[:：点时]\\s*(\\d{1,2})?\\s*(分|半)?$").find(rest) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val minStr = m.groupValues.getOrElse(2) { "" }
        val tail = m.groupValues.getOrElse(3) { "" }
        // 「九点十五半」既给了分又说「半」—— 这不是一句话，认不出，别猜成 15 或 30。
        if (minStr.isNotEmpty() && tail == "半") return null
        val mi = if (tail == "半") 30 else minStr.toIntOrNull() ?: 0
        if (mi !in 0..59) return null
        var hour = h
        var shift = 0
        if (pm && hour in 1..11) hour += 12
        // 「晚上12点」「24点」都是次日零点：小时归 0、日期加一天。
        if ((lateNight && hour == 12) || hour == 24) {
            hour = 0
            shift = 1
        }
        if (hour !in 0..23) return null
        return TimeSpec(LocalTime.of(hour, mi), false, shift)
    }

    /** 回执里那句给他看的「哪天几点」。**说人话，不甩毫秒。** */
    fun human(beginMs: Long, endMs: Long, allDay: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
        val b = Instant.ofEpochMilli(beginMs).atZone(zone)
        val head = "${b.monthValue}月${b.dayOfMonth}日"
        if (allDay) return "${b.year}年$head 全天"
        val e = Instant.ofEpochMilli(endMs).atZone(zone)
        fun hm(t: java.time.ZonedDateTime) =
            t.hour.toString().padStart(2, '0') + ":" + t.minute.toString().padStart(2, '0')
        return "${b.year}年$head ${hm(b)}–${hm(e)}"
    }

    /** 事件标题：一行、去两边空白、封顶 [max]。空标题由上层拒掉。 */
    fun cleanTitle(raw: String, max: Int = 80): String =
        raw.replace(Regex("\\s+"), " ").trim().take(max)
}
