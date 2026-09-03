package com.example.touchpad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * 保活前台服务:连接期间挂一个常驻通知,把进程提到「前台」,
 * 避免 ColorOS 这类系统在切 App / 息屏后把进程杀掉导致 TCP 断连。
 *
 * ColorOS 对纯 dataSync 的前台服务有「Stop FGS timeout」强停策略(实测连前台、
 * 屏幕常亮也会定时触发),一停就把进程降级并掐断 socket → 摄像头/麦克风卡顿、频繁重连。
 *
 * 对策:推摄像头时服务类型带上 camera、推麦克风带上 microphone —— 系统会把服务当
 * 「正在录像/录音」(顶部亮隐私指示点),这类前台服务 ColorOS 基本不会强停;
 * 失败时降级回纯 dataSync。真正的兜底是主界面引导用户开「忽略电池优化」(见 MainActivity)。
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        updateType()   // 按当前 camera/mic 状态选择服务类型
        // 系统整杀进程后,START_STICKY 会在「新进程」里裸复活本服务 —— 此时宿主 MainActivity
        // 不在(hostActivityLive=false,新进程静态变量为假),保持前台没有任何意义,立即自停,
        // 免得出现「闪退后又自己冒出来」。真正的恢复由用户重开 App 冷启动自动连回完成。
        // (同一进程内 ColorOS 只停服务不停进程时,hostActivityLive 仍为真,这里会正常续上 FGS。)
        if (!hostActivityLive) {
            Log.i(TAG, "无宿主 Activity 的裸复活(进程被整杀)→ 自停,不再冒")
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /**
     * 用户从最近任务划掉本 App = 明确想退出:此时要真正断开并停止,
     * 而不是像系统误杀那样靠 START_STICKY 复活(否则会「关掉又自己出来」)。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "用户划掉任务卡 → 主动断开并停止,不再自动复活")
        try { onUserRemovedTask?.invoke() } catch (_: Exception) {}
        onUserRemovedTask = null
        instance = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** 按当前声明的录制状态更新前台服务类型。手机端开关摄像头/麦克风时也会调到这里。 */
    fun updateType() {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (cameraRec) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (micRec) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            startForeground(NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            // 例如带 camera/mic 类型而权限状态不满足:降级纯 dataSync,别让服务起不来
            Log.w(TAG, "带 camera/mic 类型启动失败(${e.message}),降级 dataSync")
            try {
                startForeground(
                    NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } catch (e2: Exception) {
                Log.w(TAG, "dataSync 也失败,前台服务可能未生效", e2)
            }
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "触控板连接", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("手机触控板")
            .setContentText("正在控制电脑,保持屏幕镜像")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "touchpad_keepalive"
        private const val NOTIF_ID = 1

        @Volatile private var instance: KeepAliveService? = null
        @Volatile private var cameraRec = false
        @Volatile private var micRec = false

        /**
         * 本进程是否存在宿主 MainActivity(onCreate 置真)。仅在同一进程内为真;
         * 进程被杀后新进程里自动为假,供裸复活检测用。
         */
        @Volatile var hostActivityLive = false

        /** 用户划掉任务卡时回调(MainActivity 注册:断开连接、停流,彻底退出)。 */
        @Volatile var onUserRemovedTask: (() -> Unit)? = null

        /** 主界面开关摄像头/麦克风时调用:更新服务类型为「正在录像/录音」,降低被 ColorOS 强停概率。 */
        fun setRecording(camera: Boolean, mic: Boolean) {
            cameraRec = camera
            micRec = mic
            instance?.updateType()
        }
    }
}
