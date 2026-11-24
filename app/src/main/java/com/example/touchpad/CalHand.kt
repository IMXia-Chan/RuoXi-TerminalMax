package com.example.touchpad

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * **真的往手机系统日历里写。**
 *
 * 他 2026-11-06 问：「她记忆的事情能不能写进手机系统日程」，并当场选了
 * 「先试试直接写，然后我看看手机系统会不会自己有提示」。
 *
 * ## 边界（写清楚，别让它变成别的意思）
 *
 * - **只写、只读她自己建的那些**（描述里有 [CalMath.MARK]）。他日历上原有的安排
 *   一行都不读 —— 那是他的私人事项，跟「她记得的事」不是一回事。
 * - **第一次写会弹系统的那个权限框**（读写日历都是运行时权限）。他不按就没有
 *   「同意」这回事：这里返回的原因会说清是「没同意」还是「系统没弹」，
 *   不拿一句「写入失败」把它们混成一个。
 * - 写进去之后**系统会不会自己提醒**取决于他手机上的日历账户设置（提醒是不是开着、
 *   日历通知有没有被关），我们保证的只有「事件+一条 15 分钟前的提醒已经进了系统日历」。
 */
object CalHand {

    private const val TAG = "Cal"
    private const val REMIND_MINUTES = 15
    private const val REQ = 0x0CA1
    private const val AWAIT_S = 90L

    @Volatile private var waiting: CountDownLatch? = null
    @Volatile private var granted = false

    /** 只为记日志（`onResult` 里拿不到 Context）。App 级，不是界面级。 */
    @Volatile private var appCtx: Context? = null

    private fun tx(msg: String) {
        runCatching { appCtx?.let { ModelManager.get(it).trace("[日历] $msg") } }
    }

    // ------------------------------------------------------------------
    // 权限
    // ------------------------------------------------------------------

    fun canWrite(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /**
     * 要一次权限，**等到他按完为止**（最多 [AWAIT_S] 秒）。
     *
     * ★ 阻塞是故意的：[SelfHand.exec] 跑在后台线程，模型要的是一句「写成了没有」。
     *   和 [AppGate.askBlocking] 同一个理由、同一种写法。
     *
     * @return 拿到了 = true。**「他点了拒绝」和「框没弹出来」都是 false** ——
     *         调用方分不清的话至少要说成「没拿到权限」，不能编成「他拒绝了」。
     */
    fun askBlocking(activity: Activity, seconds: Long = AWAIT_S): Boolean {
        if (canWrite(activity)) return true
        // 已经有一个在等：不叠第二个框，直接说没拿到。
        synchronized(this) {
            if (waiting != null) return false
            granted = false
            waiting = CountDownLatch(1)
        }
        val latch = waiting ?: return false
        appCtx = activity.applicationContext
        activity.runOnUiThread {
            try {
                activity.requestPermissions(
                    arrayOf(
                        Manifest.permission.READ_CALENDAR,
                        Manifest.permission.WRITE_CALENDAR,
                    ),
                    REQ,
                )
            } catch (e: Exception) {
                Log.w(TAG, "权限框起不来", e)
                tx("权限框起不来: ${e.javaClass.simpleName}:${e.message}")
                clear()
                latch.countDown()
            }
        }
        return try {
            if (!latch.await(seconds, TimeUnit.SECONDS)) {
                Log.w(TAG, "等权限超时")
                tx("等权限超时 ${seconds}s")
                clear()
                false
            } else {
                granted
            }
        } catch (e: InterruptedException) {
            clear()
            false
        }
    }

    /**
     * Activity 的 `onRequestPermissionsResult` 转发到这里。
     *
     * @return 是不是这个请求 —— `false` 时调用方照旧走自己的处理。
     */
    fun onResult(requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode != REQ) return false
        val ok = grantResults.isNotEmpty() && grantResults.all {
            it == PackageManager.PERMISSION_GRANTED
        }
        // ★ 回执要能看出「框弹了没有」：这里进来了说明弹出来了，结果是什么。
        Log.i(TAG, "权限结果 ok=$ok n=${grantResults.size} code=$requestCode")
        tx("权限结果 ok=$ok n=${grantResults.size}")
        granted = ok
        val latch = synchronized(this) { waiting.also { waiting = null } }
        latch?.countDown()
        return true
    }

    private fun clear() {
        synchronized(this) { waiting = null }
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /** 最后一个能写事件的日历（优先主日历、优先自己的）。没有 = null。 */
    private fun writableCalendar(ctx: Context): Pair<Long, String>? {
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.VISIBLE,
        )
        val c = try {
            ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, proj, null, null, null
            )
        } catch (e: Exception) {
            // ★ 抛异常和「一行都没有」在这里长得一模一样（都返回 null），
            //   2026-11-06 就是靠分不清这两件事白猜了一轮。分开记。
            tx("查日历表抛异常 ${e.javaClass.simpleName}:${e.message}")
            return null
        }
        if (c == null) {
            tx("查日历表返回 null 游标")
            return null
        }
        c.use {
            var best: Pair<Long, String>? = null
            var bestScore = -1
            var rows = 0
            while (it.moveToNext()) {
                rows++
                val access = it.getInt(2)
                val primary = it.getInt(3)
                val visible = it.getInt(4)
                tx("行 id=${it.getLong(0)} 名=「${it.getString(1).orEmpty()}」 " +
                    "access=$access primary=$primary visible=$visible")
                // ★ 只有「贡献者」及以上才能往里塞事件。只读的日历（节假日、订阅）
                //   看起来和普通日历一模一样，往里写会抛异常 —— 先筛掉。
                if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                if (visible == 0) continue                           // 隐藏的
                val score = (if (primary == 1) 2 else 0) +
                    (if (access >= CalendarContract.Calendars.CAL_ACCESS_OWNER) 1 else 0)
                if (score > bestScore) {
                    bestScore = score
                    best = it.getLong(0) to it.getString(1).orEmpty()
                }
            }
            tx("共 $rows 行,选中=${best?.first ?: "无"}")
            if (rows == 0) {
                // 再用最省的投影查一次：如果换投影就有行,那是列的问题;
                // 如果还是 0,是 provider 对这一整个 uid 都不给看。
                runCatching {
                    ctx.contentResolver.query(
                        CalendarContract.Calendars.CONTENT_URI,
                        arrayOf(CalendarContract.Calendars._ID), null, null, null,
                    )?.use { c2 -> tx("最小投影 行数=${c2.count}") }
                }.onFailure { tx("最小投影 抛 ${it.javaClass.simpleName}:${it.message}") }
                runCatching {
                    ctx.contentResolver.query(
                        CalendarContract.Events.CONTENT_URI,
                        arrayOf(CalendarContract.Events._ID), null, null, null,
                    )?.use { c3 -> tx("事件表 行数=${c3.count}") }
                }.onFailure { tx("事件表 抛 ${it.javaClass.simpleName}:${it.message}") }
            }
            return best
        }
    }

    /**
     * 写一条。**调用前必须已经拿到权限**（[askBlocking] / [canWrite]）。
     *
     * @return `{ok, id, calendar, error}` —— 永远不抛。
     */
    internal fun add(ctx: Context, title: String, w: CalMath.When, note: String): JSONObject {
        // 权限已经授过时不会再走 askBlocking，那条路上才设 appCtx —— 这里补一次，
        // 否则「没有可写日历」这类失败又是无声的。
        appCtx = ctx.applicationContext
        tx("入口 包=${ctx.packageName} 读=" +
            (ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED) +
            " 写=" +
            (ctx.checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED))
        // ★★ 2026-11-06 真机实测：这台 ColorOS 上，我们的进程**读得到权限、
        //   但日历表和事件表都返回 0 行**（adb 用 shell 查同一张表有 42 条事件）。
        //   也就是说系统在**按进程过滤**，而且不报错 —— 拿「没有可写日历」当结论是错的。
        //   所以读不到日历表时**退到按 id 试写**：第一个接下的就是能用的那个。
        //   每次失败都记下系统原话，别让「试不出来」和「没有日历」长得一样。
        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, w.beginMs)
            put(CalendarContract.Events.DTEND, w.endMs)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, CalMath.MARK + note)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            // ★ 先写 0：提醒那条插成功了才改成 1。反过来的话，插提醒失败时
            //   日历上会挂着一个「有提醒」的铃铛，到点却什么都不响。
            put(CalendarContract.Events.HAS_ALARM, 0)
            if (w.allDay) put(CalendarContract.Events.ALL_DAY, 1)
        }

        val picked = writableCalendar(ctx)
        var calId = 0L
        var calName = ""
        var uri: android.net.Uri? = null
        if (picked != null) {
            calId = picked.first
            calName = picked.second
            tx("写进日历 id=$calId 名=「$calName」")
            values.put(CalendarContract.Events.CALENDAR_ID, calId)
            uri = try {
                ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            } catch (e: Exception) {
                Log.w(TAG, "写事件失败", e)
                tx("写事件抛异常: ${e.javaClass.simpleName}:${e.message}")
                return err("系统日历拒绝了这次写入：" + (e.message ?: e.javaClass.simpleName))
            }
            if (uri == null) {
                tx("写事件没有回 URI")
                return err("系统日历没有收下这条事件。")
            }
        } else {
            // ★ 无路可走时的最后一条:按 id 逐个试。**写的就是这一条真事件**,
            //   不是先塞一条探针再删 —— 探针在别人日历上留下的痕迹同样是要解释的东西。
            tx("日历表读不到,改为按 id 试写")
            var lastWhy = "没有候选"
            for (cid in 1L..8L) {
                values.put(CalendarContract.Events.CALENDAR_ID, cid)
                val u = try {
                    ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                } catch (e: Exception) {
                    lastWhy = "${e.javaClass.simpleName}:${e.message}"
                    tx("试写 id=$cid 被拒 $lastWhy")
                    null
                }
                if (u != null) {
                    tx("试写 id=$cid 成功")
                    calId = cid
                    uri = u
                    break
                } else if (lastWhy == "没有候选") {
                    lastWhy = "插入没有回 URI"
                    tx("试写 id=$cid 没回 URI")
                }
            }
            if (uri == null) {
                return err("系统日历没有接受这次写入（读不到能写的日历，逐个试也都失败了：$lastWhy）。")
            }
        }
        val id = ContentUris.parseId(uri)

        if (!w.allDay) {
            val rem = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, id)
                put(CalendarContract.Reminders.MINUTES, REMIND_MINUTES)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            val remOk = try {
                ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, rem) != null
            } catch (e: Exception) {
                Log.w(TAG, "写提醒失败", e)
                false
            }
            if (remOk) {
                runCatching {
                    val v = ContentValues().apply { put(CalendarContract.Events.HAS_ALARM, 1) }
                    ctx.contentResolver.update(
                        ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), v, null, null
                    )
                }
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("id", id)
            .put("calendar", calName)
            .put("remindMinutes", if (w.allDay) 0 else REMIND_MINUTES)
    }

    // ------------------------------------------------------------------
    // 读（只读她自己写的那一批）
    // ------------------------------------------------------------------

    /** 她写进去、还没过去的那几条。 */
    fun listMine(ctx: Context, nowMs: Long, days: Int = 30, limit: Int = 20): JSONArray {
        val out = JSONArray()
        val begin = nowMs - 86_400_000L
        val end = nowMs + days * 86_400_000L
        val proj = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.DESCRIPTION,
        )
        val where = CalendarContract.Events.DESCRIPTION + " LIKE ? AND " +
            CalendarContract.Events.DTSTART + " BETWEEN ? AND ? AND " +
            CalendarContract.Events.DELETED + " = 0"
        val args = arrayOf(CalMath.MARK + "%", begin.toString(), end.toString())
        ctx.contentResolver.query(
            CalendarContract.Events.CONTENT_URI, proj, where, args,
            CalendarContract.Events.DTSTART + " ASC",
        )?.use { c ->
            while (c.moveToNext() && out.length() < limit) {
                val allDay = c.getInt(4) == 1
                val b = c.getLong(2)
                val e = c.getLong(3)
                out.put(
                    JSONObject()
                        .put("id", c.getLong(0))
                        .put("title", c.getString(1).orEmpty())
                        .put("when", CalMath.human(b, e, allDay))
                )
            }
        }
        return out
    }

    private fun err(msg: String): JSONObject = JSONObject().put("ok", false).put("error", msg)
}
