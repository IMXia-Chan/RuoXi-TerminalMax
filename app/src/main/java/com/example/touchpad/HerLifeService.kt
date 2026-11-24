package com.example.touchpad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.IBinder
import android.os.SystemClock

/**
 * 「她一直在」的那个前台服务 —— 把进程锚住,别让 ColorOS 在她没事可做的时候把她收走。
 *
 * ## ★★ 为什么**必须**是 `specialUse`,不能用现成的 `dataSync`
 *
 * 这台机是 **Android 17 / API 37**(OPPO 一台)。Android 15+ 给 `dataSync` 类型的
 * 前台服务设了 **「每 24 小时累计 6 小时」的硬上限** —— 到点系统调 `onTimeout()`,
 * 几秒内不 `stopSelf()` 就抛 `RemoteServiceException`;配额耗尽后再起抛
 * `ForegroundServiceStartNotAllowedException`(唯一重置办法是用户把 App 拉到前台)。
 * **而且 `dataSync` 在开机广播的禁用名单上**(同名单还有 camera / mediaPlayback /
 * phoneCall / mediaProjection / microphone)—— 开机根本起不来。
 *
 * `specialUse` 不在那个名单上,**也没有时间配额**。这是唯一走得通的路。
 * 代价是它需要一个理由字符串([SUBTYPE]),并且系统可能在「耗电异常」里点名 —— 我们认。
 *
 * ## 和 [KeepAliveService] 的关系:**并存,不动老的**
 *
 * 老的管「电脑连接 + 摄像头/麦克风」,是另一条生命周期(连着电脑才有意义);
 * 这个管「她活着」。两者生命周期不同,合在一起会让「划掉任务卡断开电脑连接」和
 * 「她不该被划掉弄死」互相打架。
 *
 * ⚠️ **Android 17 太新,文档只覆盖到 15/16 —— 这条只能真机实测,别只信文档。**
 */
class HerLifeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this

        // ★ 用户按下通知里的「让她睡」
        if (intent?.action == ACTION_SLEEP) {
            ModelManager.get(this).trace("HerLife:用户按了「让她睡」→ 关掉并停服务")
            HerLife.setAlive(this, false)      // commit(),见 HerLife.setAlive 的说明
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // ★★ `intent == null` = 这个 onStartCommand 是**系统把服务重建**时投的
        //    (START_STICKY 的语义)。这是现成的「我被误杀过」信号,不用自己造计数器。
        //    记下来的用处是:次数多到不像话,就是该去 ColorOS 白名单里加她的**客观理由**。
        val resurrected = intent == null
        if (resurrected) HerLife.noteResurrection(this)

        // 用户明确关掉了 —— 哪怕是被系统重投的,也**不许**自己醒过来。
        // 这是「我又想不被误杀,又想能自己关掉」里那个「关得掉」的落点:
        // 判据只有盘上那一个布尔,因为复活发生时没有任何人可以问。
        if (!HerLife.isAlive(this)) {
            ModelManager.get(this).trace("HerLife:alive=false,不自愈,直接停(误杀重投=$resurrected)")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startForeground(
                NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } catch (e: Exception) {
            // specialUse 被这台机器的策略拒了(Android 17 太新,只能实测)。
            // 记下来 —— 静默降级会让我们以为「她一直在」生效了,其实没有。
            ModelManager.get(this).trace(
                "HerLife:specialUse 前台服务起不来(${e.message}) —— 收掉这一轮," +
                    "别让系统判 RemoteServiceException"
            )
            // ★★ 2026-11-08(体检 E4):**起不来就必须收手**。
            //   所有调用方走的都是 `startForegroundService()`(见 BootReceiver / HerBoot /
            //   ConMarnActivity.toggleAlive),而系统对这条路的判据是
            //   「五秒内真的进入过前台」。`startForeground()` 抛了 = 没进去 ——
            //   原来这里只 trace 然后继续往下装载模型,几秒后系统抛
            //   RemoteServiceException 把进程崩掉,**而且崩在模型装载中途**。
            //   现在当场 stopSelf,并返回 START_NOT_STICKY:
            //   · 盘上的 `alive` **不动**(他不该因为一次系统策略被拒就失去这个开关);
            //   · 下一次他打开房间,HerBoot / 房间入口会照盘上的「开」再挂一次。
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val mm = ModelManager.get(this)
        mm.trace(
            "HerLife:服务起(误杀重投=$resurrected,累计=${HerLife.resurrections(this)}次," +
                "defer=${intent?.getBooleanExtra(EXTRA_DEFER, false) == true})"
        )

        // ★ 开机那一刻**只做最便宜的事**:把进程锚住就走。
        //   那时系统自己被开机风暴占着,立刻抢 CPU/IO 去装载 2.5GB 的模型不划算;
        //   而且用户可能刚开机就把手机锁屏塞兜里 —— 那就纯属白烧电。
        //   所以开机来的那次延后装载,等一个「像样的时机」。
        if (intent?.getBooleanExtra(EXTRA_DEFER, false) == true) deferModelLoad() else HerBoot.ensure(this)

        return START_STICKY
    }

    /**
     * 服务被系统回收后 `START_STICKY` 会把它重建 —— 走到这里就是「自愈」那一路。
     * (用户关掉的那种在 [onStartCommand] 里就被 `alive=false` 挡住了,到不了这。)
     */
    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 延后装载

    /**
     * 轻量轮询,等一个「像样的时机」再装载模型。条件是**三者任一**:
     * 充电中 / 连着 Wi-Fi / 开机已过 [BOOT_GRACE_MS]。
     *
     * ★ 用普通线程,**不用 `JobScheduler`/`WorkManager`** —— Android 16 起
     *   「从前台服务里启动的后台任务」也要守各自的配额,那条路只会做出一个
     *   「看起来在保活、实际是骗人的开关」。轮询一次的开销可以忽略。
     *
     * 零新权限:`ACCESS_NETWORK_STATE` 早就在清单里。
     */
    private fun deferModelLoad() {
        Thread {
            val mm = ModelManager.get(this)
            mm.trace("HerLife:开机来的,延后装载,等「充电 / Wi-Fi / 开机满 ${BOOT_GRACE_MS / 1000}s」")
            while (instance != null) {
                if (goodTimeToLoad()) {
                    mm.trace("HerLife:时机到了(充电=${isCharging()},Wi-Fi=${onWifi()}),开始装载")
                    HerBoot.ensure(this)
                    return@Thread
                }
                Thread.sleep(POLL_MS)
            }
        }.apply { isDaemon = true; name = "ruoxi-defer" }.start()
    }

    private fun goodTimeToLoad(): Boolean =
        isCharging() || onWifi() || SystemClock.elapsedRealtime() > BOOT_GRACE_MS

    private fun isCharging(): Boolean =
        try {
            getSystemService(BatteryManager::class.java)?.isCharging == true
        } catch (_: Exception) {
            false
        }

    private fun onWifi(): Boolean = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        val net = cm?.activeNetwork ?: return false
        cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (_: Exception) {
        false
    }

    // ---------------------------------------------------------------- 通知

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "她一直在", NotificationManager.IMPORTANCE_LOW)
        )

        // 点通知本体 → 进她的房间(不然这条常驻通知只是个关不掉的牌子)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, ConMarnActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // ★ 「让她睡」是「能自己关掉」的**主入口** —— 不解锁也能关。
        //   做成独立的 Receiver 而不是让服务自己处理:服务没在跑时,
        //   用 getService 会把服务**拉起来**再停,而「被拉起来的前台服务」
        //   必须在 5 秒内 startForeground,否则抛异常 —— 为了关掉她而先惊动她,不值。
        val sleep = PendingIntent.getBroadcast(
            this, 1,
            Intent(this, HerSleepReceiver::class.java).setAction(ACTION_SLEEP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ConMarn")
            .setContentText("她一直在 · 右侧可以让她睡")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "让她睡", sleep).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        /** 开机广播拉起时带上它 = 「现在别装模型,等个像样的时机」。 */
        const val EXTRA_DEFER = "defer_model"

        private const val ACTION_SLEEP = "com.example.touchpad.HER_SLEEP"
        private const val CHANNEL_ID = "conmarn_life"
        private const val NOTIF_ID = 2

        /** 开机后至少等这么久再考虑装载 —— 躲开系统自己的开机风暴。 */
        private const val BOOT_GRACE_MS = 120_000L

        private const val POLL_MS = 25_000L

        @Volatile private var instance: HerLifeService? = null

        /** 服务现在活着吗。诊断用。 */
        fun isRunning(): Boolean = instance != null

        /**
         * 把「她一直在」那条通知**补发一次**。
         *
         * ★★ 为什么必须有这个 —— 2026-10-04 真机上撞出来的:
         *
         * 这条通知全项目**只有一处发**(本文件 [onStartCommand] 里那次 `startForeground`)
         * —— `KeepAliveService` 也调 `startForeground`,但用的是**另一个 id**(1 vs 本文件的 2),
         * 两条通知互不覆盖;
         * 它只在服务**启动的那一刻**跑一次。而 `POST_NOTIFICATIONS` 是 Android 13+ 的
         * **运行时**权限,不是声明了就有。实际时序:
         *
         * ```
         * 17:56:40  HerLife:服务起            ← 这一刻 startForeground 被系统挡掉
         * 17:57:07  通知权限:给了             ← 用户到这一刻才点「允许」
         * ```
         *
         * 之后**再没有任何人会发第二次**,于是用户给了权限却看不见那条通知 ——
         * 而「让她睡」就挂在它上面,等于他刚拿到的那条路又没了。
         * 当时的机器状态(判据,不是猜的):
         * `dumpsys notification` → 活着的通知里没有我们;
         * 统计 `numEnqueuedByApp=8, numPostedByApp=0, numBlocked=8`。
         *
         * ★ 不去重启服务来达到目的:那会重跑一遍 [deferModelLoad] 那串
         * (`alive` / 时机判断 / 装载),为了补一条通知把模型逻辑再走一遍不值。
         * 只补发这一条。
         */
        fun repostNotification() {
            val s = instance ?: return
            try {
                s.getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIF_ID, s.buildNotification())
                ModelManager.get(s).trace("HerLife:补发常驻通知(多半是刚拿到通知权限)")
            } catch (e: Exception) {
                ModelManager.get(s).trace(
                    "HerLife:补发常驻通知失败(${e.javaClass.simpleName}: ${e.message})"
                )
            }
        }
    }
}

/**
 * 通知上「让她睡」的落点。
 *
 * 单独一个 Receiver(而不是让服务自己处理那个 action)的理由见
 * [HerLifeService.buildNotification] —— 一句话:**关掉她,不该先把一个前台服务拉起来。**
 */
class HerSleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != "com.example.touchpad.HER_SLEEP") return
        ModelManager.get(context.applicationContext)
            .trace("HerSleepReceiver:收到「让她睡」→ alive=false,永不自愈")
        HerLife.setAlive(context, false)
        try {
            context.stopService(Intent(context, HerLifeService::class.java))
        } catch (_: Exception) {}
    }
}
