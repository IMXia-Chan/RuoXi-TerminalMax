package com.example.touchpad

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 把「下一次几点」交给系统的闹钟，到点由 [TaskAlarmReceiver] 接住。
 *
 * ## 为什么不用 `Handler` / 常驻线程数时间
 *
 * 那两样都要**进程活着**。ColorOS 在这台机器上会把她冻住（应用速冻 30~60 秒，
 * 息屏时前台服务也挡不住 —— 见记忆 `coloros-freezes-background-termux`），
 * 一个跑在进程里的计时器会跟着一起冻停，到点不响，而且**不报错**。
 * `AlarmManager` 是系统层的，唤醒的是进程，不是靠进程自己数。
 *
 * ## 精确 vs 不精确：**说清楚**
 *
 * Android 12 起，`setExactAndAllowWhileIdle` 要先拿到「闹钟和提醒」这个特殊权限。
 * 没拿到时**不能悄悄降级还不说** —— 否则他说「八点提醒我」，实际八点二十才响，
 * 而她没有一个字提示过这件事。所以 [arm] 把这件事作为**返回值**交给上层，
 * 由她原话转告。
 *
 * ## 重启之后闹钟是会没的
 *
 * 系统的闹钟表在重启时清空。所以 [BootReceiver] 里要调 [rescheduleAll]。
 */
internal object TaskAlarm {

    private const val ACTION_FIRE = "com.example.touchpad.TASK_FIRE"

    /** 精确闹钟能不能用。API 31 以下没这个概念，一律算能。 */
    fun canExact(ctx: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        }.getOrDefault(false)
    } else true

    /**
     * 给一条任务排下一次。→ null = 精确排上了；非空 = **一句要转告他的话**
     * （现在只能排不精确的，或者排失败）。
     */
    fun arm(ctx: Context, plan: TaskPlan): String? {
        val at = plan.nextAt
        if (at <= 0) return null
        val am = runCatching {
            ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        }.getOrNull() ?: return "系统闹钟服务起不来，这条任务现在排不上。"

        val pi = pending(ctx, plan.id)
        return try {
            if (canExact(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                null
            } else {
                // 不精确：Doze 里最多可能晚到十几分钟。**不能当成精确的用**。
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                "系统没给这个应用「闹钟和提醒」权限，所以只能排个**大概**的时间 —— " +
                    "可能会晚几分钟到十几分钟。要准的话，去系统设置的「应用 → 特殊权限 → " +
                    "闹钟和提醒」里打开。"
            }
        } catch (e: Exception) {
            "排闹钟没成（${e.javaClass.simpleName}：${e.message ?: "没说原因"}）。"
        }
    }

    fun cancel(ctx: Context, id: String) {
        val am = runCatching {
            ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        }.getOrNull() ?: return
        runCatching { am.cancel(pending(ctx, id)) }
    }

    /**
     * 重启之后把**所有还开着**的任务重新排上。→ 排上了几条。
     *
     * ★ 只排 [TaskPlan.nextAt] 还没过去的那些：一次性任务要是已经过点了，
     *   老老实实当它完了，不要在重启时补跑一遍 —— 那是「做过的事又做一次」。
     */
    fun rescheduleAll(ctx: Context, nowMs: Long = System.currentTimeMillis()): Int {
        TaskStore.init(ctx)
        var n = 0
        for (p in TaskStore.all()) {
            if (!p.enabled || p.nextAt <= nowMs) continue
            // 返回值是「只能排个大概」时的提示，重启这条路没人能听，不往上报。
            arm(ctx, p)
            n++
        }
        return n
    }

    private fun pending(ctx: Context, id: String): PendingIntent {
        val i = Intent(ctx, TaskAlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(TaskAlarmReceiver.EXTRA_ID, id)
        // ★ requestCode 用 id 的哈希：两条任务的 PendingIntent 不能互相顶掉
        //   （顶掉了就是「排了 A，到点跑的是 B」或者「B 把 A 冲了」）。
        return PendingIntent.getBroadcast(
            ctx,
            id.hashCode(),
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * 闹钟响的那一下。
 *
 * ★ 这里**只负责叫醒服务**，一秒钟的活都不干：广播接收器有 10 秒的硬上限
 *   （超时 ANR / 被杀），而跑一件活要几十秒到几分钟。
 */
class TaskAlarmReceiver : android.content.BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_ID)?.trim().orEmpty()
        val app = context.applicationContext
        val mm = ModelManager.get(app)
        if (id.isEmpty()) {
            mm.trace("[定时] 闹钟响了但没有任务号，忽略")
            return
        }
        mm.trace("[定时] 闹钟响：$id")
        val svc = Intent(app, TaskRunService::class.java)
            .setAction(TaskRunService.ACTION_RUN)
            .putExtra(TaskRunService.EXTRA_ID, id)
        try {
            context.startForegroundService(svc)
        } catch (e: Exception) {
            // Android 12+ 后台起前台服务有限制，少数机型会拦。**别静默吞掉**：
            // 拦掉的样子就是「到点了什么都没发生」，比一条通知都没有更难查。
            mm.trace("[定时] 起不了前台服务：${e.javaClass.simpleName}: ${e.message}")
            onStartRefused(app, id, e.javaClass.simpleName, mm)
        }
    }

    /**
     * 前台服务被系统拦下之后，**必须把这条任务的闹钟重新排上**。
     *
     * ★★ 2026-11-08（体检 E5）：原来这里只发一条通知就完了。而闹钟**已经被消费掉**，
     *   重排只发生在 [TaskRunService.settle] 里 —— 服务根本没起来，settle 永远不会跑。
     *   于是那条任务从此**再也不响**，而屏幕上还写着「每天 08:00」。
     *   通知里那句「下一次就会正常」也就成了一句假话。
     *
     * 现在的做法（两次机会，然后体面收场）：
     *   1. 前 [RETRY_TIMES] 次：往后挪几分钟重排 —— 精确闹钟本身会给应用一个短暂的
     *      「可以起前台服务」的窗口，很多拦截是**这一次**运气不好，下一次就过了；
     *   2. 用完就放弃这一次，按「跑了但没成」记账（[TaskMath.nextAfter] 算下一次），
     *      发一条结果通知说清「这次没跑」。重复任务照常继续排，一次性任务到此结束。
     */
    private fun onStartRefused(app: Context, id: String, why: String, mm: ModelManager) {
        TaskStore.init(app)
        val plan = TaskStore.find(id)
        if (plan == null || !plan.enabled) {
            TaskNotice.failedToStart(app, id, why)
            return
        }
        val now = System.currentTimeMillis()
        if (plan.failCount < RETRY_TIMES) {
            val at = now + RETRY_GAP_MS * (plan.failCount + 1)
            val retry = plan.copy(nextAt = at, failCount = plan.failCount + 1)
            TaskStore.save(retry)
            TaskAlarm.arm(app, retry)
            mm.trace(
                "[定时] $id 这次没起来（$why），${(at - now) / 1000} 秒后重试" +
                    "（第 ${retry.failCount}/$RETRY_TIMES 次）"
            )
            // 只在**第一次**就告诉他。重试阶段不反复弹 —— 三条一样的通知只会吵。
            if (plan.failCount == 0) TaskNotice.failedToStart(app, id, why)
            return
        }

        // 放弃这一次：算「跑了但没成」，但**下一次必须照常排上**。
        val next = TaskMath.nextAfter(plan, now)
        val after = plan.copy(
            nextAt = next ?: 0,
            enabled = plan.enabled && next != null,
            lastAt = now,
            lastOk = false,
            lastText = TaskMath.clip("系统拦下了后台启动（$why），这次没跑。", 400),
            failCount = 0,
        )
        TaskStore.save(after)
        if (after.enabled) TaskAlarm.arm(app, after)
        TaskNotice.result(app, after, false, "系统拦下了后台启动（$why）。这次没跑。", after.nextAt)
        mm.trace(
            "[定时] $id 重试 $RETRY_TIMES 次都被拦，放弃这一次；" +
                if (after.enabled) "下一次 ${TaskMath.nextText(after.nextAt, now)}" else "这条任务到此结束"
        )
    }

    companion object {
        const val EXTRA_ID = "task_id"

        /** 起不来时最多重排几次。 */
        private const val RETRY_TIMES = 2

        /** 两次重试之间隔多久（乘第几次）。 */
        private const val RETRY_GAP_MS = 3 * 60 * 1000L
    }
}
