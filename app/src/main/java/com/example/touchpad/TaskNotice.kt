package com.example.touchpad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 定时任务的通知：**跑的时候一条（进行中），跑完换成结果那一条**。
 *
 * ★ 通知是这个功能唯一的出口：到点那一刻他不一定在看手机，结果不弹出来，
 *   就等于既花了钱、又做了事、而他什么都不知道。
 *
 * ★ 通知里**只说结果摘要**（[TaskMath.clip]）。全文在工作台那件活里，
 *   点通知跳到那儿去看 —— 一条通知放不下，而且会被系统截断。
 */
internal object TaskNotice {

    const val CHANNEL = "scheduled"
    private const val CHANNEL_NAME = "定时任务"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    /** 到点、已经接手，正在做。 */
    fun running(ctx: Context, plan: TaskPlan): Notification {
        ensureChannel(ctx)
        return base(ctx, plan)
            .setContentTitle("正在做：" + TaskMath.describe(plan))
            .setContentText(TaskMath.clip(plan.prompt, 80))
            .setOngoing(true)
            .build()
    }

    /** 兜底的一条：任务档案读不出来也要有个东西顶住前台服务的 5 秒。 */
    fun plain(ctx: Context, title: String, text: String): Notification {
        ensureChannel(ctx)
        return Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    /** 做完了 / 没做成。 */
    fun result(ctx: Context, plan: TaskPlan, ok: Boolean, text: String, nextAt: Long) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val body = text.ifBlank { if (ok) "做完了，但没有留下文字结果。" else "这次没做成。" }
        val next = if (nextAt > 0) "下次：" + TaskMath.nextText(nextAt, System.currentTimeMillis())
        else "这条任务到此结束。"
        val n = base(ctx, plan)
            .setContentTitle((if (ok) "做好了 · " else "没做成 · ") + TaskMath.describe(plan))
            .setContentText(TaskMath.clip(body, 120))
            .setStyle(Notification.BigTextStyle().bigText(TaskMath.clip(body, 600) + "\n" + next))
            .setSubText(next)
            .setOngoing(false)
            .build()
        runCatching { nm.notify(idOf(plan.id), n) }
    }

    /** 闹钟响了，但系统不让在后台起服务 —— 这是「到点了却什么都没发生」的唯一解释。 */
    fun failedToStart(ctx: Context, id: String, why: String) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("定时任务没有跑起来")
            .setContentText("系统拦下了后台启动（$why）。打开应用后可以手动重做。")
            .setStyle(Notification.BigTextStyle().bigText(
                "到点了，但系统拦下了后台启动（$why）。\n" +
                    "这不是任务本身的问题：打开应用、或把「她一直在」开着，下一次就会正常。"
            ))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id.hashCode(), n) }
    }

    fun cancel(ctx: Context, id: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.cancel(idOf(id)) }
    }

    private fun idOf(planId: String) = planId.hashCode()

    private fun base(ctx: Context, plan: TaskPlan): Notification.Builder =
        Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentIntent(openWorkbench(ctx))
            .setAutoCancel(true)

    /** 点通知 → 工作台。那件活就在任务条上，全文在那儿。 */
    private fun openWorkbench(ctx: Context): PendingIntent {
        val i = Intent(ctx, WorkbenchActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, ConMarnActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            ctx, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
