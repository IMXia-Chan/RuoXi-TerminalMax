package com.example.touchpad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * 耳朵的「麦克风资格」前台服务 —— 它存在只为一件事。
 *
 * ## 为什么需要它(2026-10-06 真机证据)
 *
 * `RECORD_AUDIO` 是 **while-in-use**:只有 App 在**前台**才给真音频。而悬浮球是个
 * **overlay,不是 Activity** —— 屏幕上只有那颗球时,系统把本 App 当**后台**,这时
 * `AudioRecord` **不报错,只回一耳朵零**(model.log 实据:球那几轮 `RMS=0.0`,房间那几轮正常)。
 *
 * Android 认的「后台也能录」只有一条路:**挂一个 microphone 类型的前台服务**。
 * 没有它,球里按住说话永远是静默 —— 这就是「悬浮窗里她听不见我」的根。
 *
 * ## 它只管「资格」,不碰音频
 *
 * 真正的录音仍在 [EarMic](那条 `AudioRecord` 线程)。本服务只是把「现在允许录」这个
 * 状态撑起来 —— 录音开始前来,录音结束后走。**音频一字节都不经过它。**
 *
 * ## 起不来的时候不许装死
 *
 * 两类失败,都落成 [failure],由 [Ear] 记进日志:
 *   · `startForegroundService` 被系统拒(后台不让起前台服务)—— 当场抛;
 *   · `startForeground(mic 类型)` 被拒 —— 降级成**不带类型**起(至少别崩),
 *     但那种情况下「后台能录」这条**不成立**,照旧是静默。
 * 两种都不假设「好了」 —— 房间(前台)本来就不需要它,降级不影响房间;球(后台)
 * 若起不来,表现和没修一样,日志里写得出来,而不是「她又不理我」。
 */
class EarFgService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            foregrounded = true
            failure = null
        } catch (e: Exception) {
            // mic 类型被拒(最常见:这次是从真正的后台起来的,没有任何豁免)。
            //
            // ★★ 2026-11-08(体检 A3/E6):原来是「降级成不带类型再起一次」,而
            //   `startForeground(id, notif, 0)` 传的类型是 **0** —— API 29 起这就是
            //   非法值,当场抛 IllegalArgumentException;就算不抛,Android 14+ 也不许
            //   一个声明了类型的服务用「无类型」进前台。
            //   也就是说那条降级路**一次都没成功过**,只是被 catch 吞了。
            //
            //   现在不降级了:mic 类型起不来 = 「后台能录」这条不成立 = 这台牌子上
            //   写着「正在听你说话」是句假话。**收手,不挂假牌子**,原因落进日志。
            failure = "mic类型前台起不来(${e.javaClass.simpleName})"
            ModelManager.get(this).trace("耳:EarFg $failure")
            foregrounded = false
            stopSelf()
            return START_NOT_STICKY
        }
        // 它只是「正在录音」这块牌子,死了别自己复活 —— 复活出来的是一条没人会停的常驻通知。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        foregrounded = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "她在听", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, ConMarnActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ConMarn")
            // ★ 这条牌子在整场语音对话期间都挂着（麦克风在她说话时是关的，
            //   见半双工闸），所以写「正在听你说话」并不总成立 —— 说「对话进行中」。
            .setContentText("语音对话进行中")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "conmarn_ear"
        // ★ 原来是 3 —— 和 [WakeService] 撞号，两边同时跑时后挂的那条会把前一条顶掉。
        private const val NOTIF_ID = 4

        /** 「mic 资格真的挂上了」。Ear 在起 `AudioRecord` **之前**等它(见 Ear.run)。 */
        @Volatile var foregrounded = false
            private set

        /** 起不来的人话原因;null = 没失败过。给日志用,不给界面当开关。 */
        @Volatile var failure: String? = null
            private set

        /**
         * 挂上「麦克风资格」。**可能失败**(见上),返回值只是「意图发出去没有」,
         * 真正的结果看 [foregrounded] / [failure]。
         */
        fun start(app: Context): Boolean {
            foregrounded = false
            failure = null
            return try {
                app.startForegroundService(Intent(app, EarFgService::class.java))
                true
            } catch (e: Exception) {
                // 后台不让起前台服务(Android 12+ 的硬规矩,除非撞上某个豁免)。
                failure = "后台起不了前台服务(${e.javaClass.simpleName})"
                ModelManager.get(app).trace("耳:EarFg $failure")
                false
            }
        }

        /** 撤掉「麦克风资格」。幂等;服务没在跑时这只是个空发。 */
        fun stop(app: Context) {
            foregrounded = false
            try { app.stopService(Intent(app, EarFgService::class.java)) } catch (_: Exception) {}
        }
    }
}
