package com.example.touchpad

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 到点了，**在这里把那条任务真的跑一遍**。
 *
 * ## 为什么必须是前台服务
 *
 * 跑一件活要几十秒到几分钟，而这个进程多半在后台。Android 会毫不犹豫地把它收走，
 * 症状是「到点响了一下，然后什么都没发生」—— 一条日志都不留。
 * 前台服务是唯一能让系统知道「这件事还没完」的形态；类型用 `specialUse`，
 * 理由和 [HerLifeService] 同一条（dataSync 有 6 小时配额、且在开机广播禁用名单上），
 * 见那边的类注释。
 *
 * ## 跑的是**工作台那一条路**（[AiAgent.runWorkbench]）
 *
 * 不是她的对话：那时候没人跟她说话，她不该开始一段聊天。工作台那条路是
 * 「一件事进、一份成品出」，产物落在 [WorkJob] 的会话里，打开工作台就能看见全文。
 * 顺带还得了两个现成的好处：和手动开的活**共用那个三件并发的闸**，
 * 以及每个角色都**没有手**（派出去的人只能写字，不能动手机或电脑）——
 * 定时跑的东西在没人看着的时候不该有动手的能力。
 *
 * ## 跑完三件事：写回档案、排下一次、弹通知
 *
 * 顺序不能反：闹钟**已经响过了**，这一次的 `nextAt` 是过去的时间。
 * 不重排的话，这条任务就永远停在这儿了 —— 而界面上还写着「每天 08:00」。
 */
class TaskRunService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    private val busy = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_RUN) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val id = intent.getStringExtra(EXTRA_ID)?.trim().orEmpty()
        TaskStore.init(this)
        WorkJobStore.init(this)

        val plan = TaskStore.find(id)
        // ★ 5 秒内必须 startForeground。先起一个最便宜的（拿不到任务就用一句话顶住），
        //   拿到真任务之后再换成带任务名的那条。
        runCatching {
            startForeground(
                NOTIF_ID,
                plan?.let { TaskNotice.running(this, it) }
                    ?: TaskNotice.plain(this, "定时任务", "到点了，正在核对是哪一条…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }.onFailure { trace("前台服务起不来：${it.javaClass.simpleName}: ${it.message}") }

        if (plan == null) {
            trace("[定时] 找不到任务 $id（可能已经被删掉），这次不跑")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!plan.enabled) {
            trace("[定时] 任务 $id 已关掉，不跑")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!busy.compareAndSet(false, true)) {
            // 上一条还没跑完（同一条任务的定时重叠，或者别的任务在跑）。
            // ★ **不能就这样把它丢掉**：这一次闹钟已经消费掉了，不重排就永远不响了。
            trace("[定时] 上一条还在跑，这次跳过并照常排下一次")
            // ★ 跳过不发通知：他这时候并没有在等这一条，多弹一条只会吵；
            //   但**必须重排**，否则这条任务从此不再响。
            settle(plan, false, "上一次还没跑完，这次跳过了。", notify = false)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        Thread({ runOne(plan, startId) }, "task-run").start()
        return START_NOT_STICKY
    }

    /** 真跑。跑完无论如何都要 [settle]（写回 + 排下一次 + 通知），然后自杀。 */
    private fun runOne(plan: TaskPlan, startId: Int) {
        var ok = false
        var text = ""
        try {
            val now = System.currentTimeMillis()
            val job = WorkJob(
                id = AgentBus.newWorkTask(),
                title = "定时：" + WorkJobMath.titleOf(plan.prompt),
                mode = WorkJobMath.MODE_FANOUT,
                status = WorkJobMath.ST_QUEUED,
                createdAt = now,
                updatedAt = now,
                turns = listOf(WorkTurn(WorkTurn.USER, plan.prompt)),
            )
            WorkJobStore.save(job)
            trace("[定时] 开跑 $plan.id → 活 ${job.id}，${plan.prompt.length} 字")

            val cancel = AtomicBoolean(false)
            // ★ 没人看着的活必须自带一个死线：模型绕圈、云端不吐字时，
            //   没有这一条它能一直挂着，通知永远停在「正在做」。
            val watchdog = Thread({
                try {
                    Thread.sleep(TIMEOUT_MS)
                    if (!cancel.getAndSet(true)) trace("[定时] 超过 ${TIMEOUT_MS / 1000} 秒，掐掉")
                } catch (_: InterruptedException) { }
            }, "task-watchdog")
            watchdog.isDaemon = true
            watchdog.start()

            val out = AiAgentHolder.get(this)
                .runWorkbench(job.id, emptyList(), false, cancel, cb)
            watchdog.interrupt()

            ok = out.optBoolean("ok")
            text = out.optString("final").trim().ifBlank { out.optString("error").trim() }
            trace("[定时] 跑完 ${plan.id} ok=$ok ${text.length} 字")
        } catch (e: Exception) {
            ok = false
            text = "没跑完：" + (e.message ?: e.javaClass.simpleName)
            trace("[定时] 抛了 ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            busy.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            settle(plan, ok, text)
            stopSelf(startId)
        }
    }

    /** 写回结果、算下一次、排闹钟、弹通知。**这四件事一件都不能少。** */
    private fun settle(plan: TaskPlan, ok: Boolean, text: String, notify: Boolean = true) {
        val now = System.currentTimeMillis()
        val next = TaskMath.nextAfter(plan, now)
        val after = plan.copy(
            lastAt = now,
            lastOk = ok,
            lastText = TaskMath.clip(text, 400),
            failCount = if (ok) 0 else plan.failCount + 1,
            // 一次性任务过了点就自然结束；重复任务永远有下一次。
            nextAt = next ?: 0,
            enabled = plan.enabled && next != null,
        )
        TaskStore.save(after)
        if (after.enabled) TaskAlarm.arm(this, after)
        if (notify) TaskNotice.result(this, after, ok, text, after.nextAt)
    }

    private fun trace(msg: String) = runCatching { ModelManager.get(this).trace(msg) }

    /** 定时跑的时候**没有界面**，所以所有「要他点头」的动作一律答「根本没有界面」。 */
    private val cb = object : AiAgent.Callback {
        override fun onLog(msg: String) { trace("[定时] $msg") }
        override fun onTrace(msg: String) { trace("[定时·过程] $msg") }
        override fun onFinal(msg: String) { /* 结果从 runWorkbench 的返回里拿 */ }
        override fun onError(msg: String) { trace("[定时] 出错：$msg") }
        override fun onBusy(busy: Boolean) { /* 没有按钮要驱动 */ }
        override fun onConfirm(summary: String, detail: String): Confirm {
            trace("[定时] 需要确认但没人：$summary")
            return Confirm.NO_UI
        }
    }

    companion object {
        const val ACTION_RUN = "com.example.touchpad.TASK_RUN"
        const val EXTRA_ID = "task_id"

        /** 前台服务那条通知用固定 id；结果通知用任务 id 的哈希，两条互不顶替。 */
        private const val NOTIF_ID = 4101

        /** 一件定时活最多跑多久。到点掐掉，也算「跑了但没成」。 */
        private const val TIMEOUT_MS = 8 * 60 * 1000L
    }
}
