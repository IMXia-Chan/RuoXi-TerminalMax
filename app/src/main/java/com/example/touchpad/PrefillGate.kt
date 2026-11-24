package com.example.touchpad

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * 「现在该不该开始那一次冷算」—— 纯规则，能跑 JVM 单测。
 *
 * ## 为什么要有这道门
 *
 * 2026-11-08 真机：冷算 8192 token，手机烫到 56.7°C、被压到 ~3 token/秒，
 * 30 分钟只啃到 6144，最后客户端超时、按设计**不存盘**——一小时的热白烧，
 * 快照还是没落盘。
 *
 * 而同样是这份前缀，10-04 那次 4889 token 在**降频**下是 19 分钟。
 * 差别不在代码，在**开始算的时候手机凉不凉**。
 *
 * 所以：冷算不再「一开 App 就扑上去」，只在手机凉且插着电的时候做。
 * 它不是一个新功能，是把「什么时候付这笔算力」从随机改成挑时间。
 *
 * ## 两条档位（为什么不是一个数）
 *
 * - **插着电**：算多久都不怕掉电，只受温度限制 ⇒ 门槛 38°C。
 * - **没插电**：满载 30–60 分钟是实打实的电池消耗，所以更严 ⇒ 门槛 34°C。
 *
 * ## 它挡不住什么
 *
 * 门只决定「什么时候**开始**」。开始之后温度又会爬上来，那是 [AiAgent.warmUpOnce]
 * 的客户端超时和「超时绝不存盘」两条在兜底，不归这里管。
 */
internal object PrefillMath {

    /** 插着电时的温度门槛。见文件头两条档位。 */
    const val MAX_TEMP_CHARGING_C = 38.0f

    /** 没插电时的温度门槛。 */
    const val MAX_TEMP_BATTERY_C = 34.0f

    /**
     * 现在开始冷算，行不行。
     *
     * @param batteryTempC 电池温度（摄氏度）。读不到就传 null。
     * @param charging     插着电没有。
     *
     * ★ 温度**读不到**时判「不行」。这一条是故意的：读不到温度还硬算，
     *   就是 11-08 那次重演；而代价只是晚一点再算，不是失去这个功能。
     */
    fun shouldStart(batteryTempC: Float?, charging: Boolean): Boolean {
        if (batteryTempC == null) return false
        val cap = if (charging) MAX_TEMP_CHARGING_C else MAX_TEMP_BATTERY_C
        return batteryTempC <= cap
    }

    /** 给日志看的一句话。 */
    fun why(tempC: Float?, charging: Boolean): String {
        if (tempC == null) return "读不到电池温度 —— 先不算，等下次"
        val cap = if (charging) MAX_TEMP_CHARGING_C else MAX_TEMP_BATTERY_C
        val state = if (charging) "插着电" else "没插电"
        return if (tempC <= cap) {
            "可以冷算（${fmt(tempC)}°C，$state，门槛 ${fmt(cap)}°C）"
        } else {
            "等凉一点再算（现在 ${fmt(tempC)}°C，$state，门槛 ${fmt(cap)}°C）"
        }
    }

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.1f", v)
}

/**
 * [PrefillMath] 的取数那半边：电池温度与充电状态。
 *
 * ★ 为什么用**电池**温度而不是 CPU 温度：这颗 ColorOS 上
 *   `/sys/class/thermal/thermal_zoneN/temp` 连 shell 都读不到（实测全空），
 *   而 `BatteryManager` 的 `ACTION_BATTERY_CHANGED` 是公开、免权限、随取随有的。
 *   电池温度不若 SoC 灵敏，但它和**降频**是同一件事的两面 —— 皮肤热了才会降。
 */
internal object PrefillGate {

    fun batteryTempC(ctx: Context): Float? = try {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (t == Int.MIN_VALUE) null else t / 10f
    } catch (e: Exception) {
        null
    }

    fun charging(ctx: Context): Boolean = try {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        when (i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL -> true
            else -> false
        }
    } catch (e: Exception) {
        false
    }

    /** null = 现在可以算；否则是不能算的原因（给人话）。 */
    fun whyNotNow(ctx: Context): String? {
        val t = batteryTempC(ctx)
        val c = charging(ctx)
        return if (PrefillMath.shouldStart(t, c)) null else PrefillMath.why(t, c)
    }
}
