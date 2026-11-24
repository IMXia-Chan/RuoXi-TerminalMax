package com.example.touchpad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * 「唤醒词在听」的前台服务 —— **麦克风型**。
 *
 * ## ★★ 为什么光有权限不够(这是它存在的唯一理由)
 *
 * 这台机上她的录音权限是「**仅在使用中允许**」那一档 ——
 * `cmd appops get com.example.touchpad RECORD_AUDIO` 回的是
 * `Uid mode: RECORD_AUDIO: foreground`。在这一档下,**App 一退到后台,
 * 系统喂给 `AudioRecord` 的是静音**:不报错、不抛异常,读数全是零。
 *
 * 而唤醒词要听的每一刻(**她的房间关着的时候**)正好就是这一档不允许的那一刻 ——
 * 也就是说没有这个服务,那个开关**打开了也不会响**,而且屏幕上看起来像「没做」。
 * (麦只有一个的账见 [MicGate];这一层管的是**系统给不给声音**,不是谁占着。)
 *
 * 挂上 `microphone` 类型的前台服务之后,系统就认她「正在使用中」,后台也拿得到声音。
 * 它顺带把 ColorOS 的**应用速冻**一起挡了 —— 进程被冻住时不光没声音,
 * 连解码线程都不转,而那个症状和「没喊对」长得一模一样。
 *
 * ## 为什么不和 [HerLifeService] 合成一个
 *
 * 两个开关、两条生命周期:那个管「她活着」,这个管「她在听」。
 * 合成一个的后果是**关掉其中一个会顺手关掉另一个** ——
 * 而「唤醒开着、她本人关着」是一个完全正常的状态。
 * (同 [KeepAliveService] 文件头那条「并存,不动老的」。)
 *
 * ## 它**不**做什么
 *
 * 不加载模型、不解码、不碰麦克风 —— 全是 [WakeWordManager] 的事。
 * 它只做三件:**把进程锚在前台**、**声明麦克风类型**、
 * **把「我在听」摆出来**(那条常驻通知,以及系统自己画的绿点 ——
 * 指示是系统给的,不用我们另画一个)。
 *
 * ## ⚠️ 它必须在她房间在前台的时候启动
 *
 * Android 12+ 不许后台起前台服务。而它天然就是在那一刻起的:
 * 用户按那个开关的时候、或者房间回到前台的时候(见 [WakeWordManager.applySetting])。
 *
 * ## ⚠️ 通知被挡掉时会发生什么(诚实写在这儿)
 *
 * `POST_NOTIFICATIONS` 是 Android 13+ 的**运行时**权限,不是声明了就有。
 * 它没给的时候 `startForeground` 照样成功、**服务照样生效、后台照样听得到**,
 * 只是那条通知看不见 —— 也就是说「我看不见它,但它确实在听」。
 * 这个方向是安全的(听得见她),不安全的那个方向是「看得见却没在听」,那种情况
 * [WakeWordManager.abort] 会把开关收回「关」,不留一条骗人的牌子。
 */
class WakeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        alive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(
                NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: Exception) {
            // ★★ **起不来就绝不装着起得来。** 这条是最要紧的一句:
            //   服务没起来 = 后台那一段时间听到的是静音 = 喊她不应,而**一声不响**。
            //   所以这里当场把开关收回「关」并说清楚(见 [WakeWordManager.abort])。
            //   典型原因是录制权限被用户收回(Android 14+ 对 microphone 型前台服务
            //   会查权限),那种情况下 startForeground 抛 SecurityException。
            ModelManager.get(this).trace(
                "耳:唤醒前台服务起不来(${e.javaClass.simpleName}: ${e.message}) —— 后台听不到"
            )
            // ★★ 2026-11-08(体检):`intent == null` = 这次是系统把 START_STICKY 的服务
            //   在后台重建时投的(见下面 return 前那段注释),不是他去按的开关。
            //   那一路上**不能写盘** —— 否则夜里被系统杀一次,白天打开的唤醒开关
            //   就被永久改成「关」了。理由写在 [WakeWordManager.onServiceFailed]。
            WakeWordManager.onServiceFailed(this, persistOff = intent != null)
            stopSelf()
            return START_NOT_STICKY
        }

        ModelManager.get(this).trace("耳:唤醒前台服务起来了(麦克风型,后台也听得到)")

        // ★ 被系统整杀之后 START_STICKY 会把它在新进程里重建 —— 那时内存里的开关
        //   是关的,得从盘上读回来。**这一句就是「误杀自愈」那一半**:
        //   他没关过(pref 还开着)→ 重新挂上去继续听;他关过 → 它会当场自己停。
        WakeWordManager.applySetting(this)
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "语音唤醒", NotificationManager.IMPORTANCE_LOW)
        )

        // 点通知本体 → 回她的房间(那个开关在那儿;从这儿进去才能关)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, ConMarnActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // ★ 「关掉」走一个**广播接收器**,不是 `getService` —— 理由同 [HerLifeService]:
        //   `getService` 在服务没在跑时会把服务**拉起来**,而「被拉起来的前台服务」
        //   必须在 5 秒内 `startForeground`,否则抛异常。为了关掉它而先惊动它,不值。
        val off = PendingIntent.getBroadcast(
            this, 1,
            Intent(this, WakeOffReceiver::class.java).setAction(WakeOffReceiver.ACTION_OFF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ConMarn")
            // ★ 这句说的是**开关的状态**,不是「我此刻正在录音」——
            //   房间开着那会儿她本来就不听唤醒(麦让给耳朵,见 roomInFront),
            //   写「正在听」在那几秒就是一句假话。
            .setContentText("语音唤醒开着 · 喊「从漫」叫我")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "关掉", off).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "conmarn_wake"
        private const val NOTIF_ID = 3

        @Volatile private var alive = false

        /** 服务现在活着吗。诊断用。 */
        fun isRunning(): Boolean = alive

        /**
         * 起服务。**必须在 App 前台的时候调**(Android 12+ 的规矩),
         * 而调用方天然满足:拨开关的那一刻和房间回到前台的那一刻。
         *
         * 幂等:已经在跑就直接返回。这一条不只是省事 —— 它挡掉了
         * 「[WakeWordManager.applySetting] 被服务自己的 onStartCommand 调到」
         * 那条回路(服务里再起一次服务)。
         */
        fun start(ctx: Context) {
            if (alive) return
            if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                // 没权限就别起 —— 起了也是白挂一条通知而听不见。说清楚并关掉开关。
                WakeWordManager.onServiceFailed(ctx, "没有录音权限")
                return
            }
            try {
                ctx.startForegroundService(Intent(ctx, WakeService::class.java))
            } catch (t: Throwable) {
                // 后台起前台服务被系统拒(ForegroundServiceStartNotAllowedException)
                // —— 这一条**必须说出来**:它意味着「这次开关打开了,但她后台听不见」。
                WakeWordManager.onServiceFailed(ctx, "系统不许后台起前台服务(${t.javaClass.simpleName})")
            }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, WakeService::class.java)) } catch (_: Throwable) {}
        }
    }
}

/**
 * 通知上那颗「关掉」。
 *
 * ★ 做成独立的接收器而不是让 [WakeService] 自己处理,见 `buildNotification` 里的说明。
 * ★ 关的动作**只走** [WakeWordManager.setEnabled] 那一处 —— 开关只有一处写盘,
 *   不在这里偷偷置盘(否则会出现「盘上关了、内存里还开着」这种要命的不一致)。
 */
class WakeOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_OFF) return
        ModelManager.get(context).trace("耳:用户从通知里关掉了唤醒")
        WakeWordManager.setEnabled(context, false)
    }

    companion object {
        const val ACTION_OFF = "com.example.touchpad.WAKE_OFF"
    }
}
