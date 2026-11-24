package com.example.touchpad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
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
        val cb = onUserRemovedTask
        if (cb != null) {
            try { cb() } catch (_: Exception) {}
        } else {
            // ★★ 没人登记回调 —— 她的房间那条路(MainActivity 从来没建过)。
            //   老代码这里什么都不做,而那时**也真的不需要做**:没有前台服务,
            //   进程就是个空缓存进程,系统自己会收掉。现在连接会挂一个常驻 FGS
            //   (见 [TouchpadClient.anchorProcess]),不自己收就变成
            //   「划掉了还赖在通知栏里、电脑那头也一直连着」。
            exitApp(this)
        }
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
        // ★★ 2026-11-08(体检 E3):底座从 `dataSync` 换成 `specialUse`。
        //
        //   Android 15+ 给 `dataSync` 型前台服务设了「每 24 小时累计 6 小时」的硬上限,
        //   到点系统调 `onTimeout()`,几秒内不收手就抛 `RemoteServiceException`。
        //   连着电脑挂一天是**正常用法**,原来那条路等于「用满 6 小时必断」。
        //   `specialUse` 没有时间配额 —— 理由和 [HerLifeService] 用的是同一条
        //   (那边已经在真机上验过能起来)。camera / microphone 仍然按需叠在上面,
        //   它们的用处不变:系统把服务当「正在录像/录音」,ColorOS 基本不强停。
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (cameraRec) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (micRec) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            startForeground(NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            // 例如带 camera/mic 类型而权限状态不满足:先降级纯 specialUse,别让服务起不来
            Log.w(TAG, "带 camera/mic 类型启动失败(${e.message}),降级 specialUse")
            try {
                startForeground(
                    NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } catch (e2: Exception) {
                // specialUse 也被拒(Android 17 太新,只能实测)。最后退回老的 dataSync ——
                // **不是**最优解(有 6 小时上限),但总比「一点前台都没有」强。
                Log.w(TAG, "specialUse 也失败(${e2.message}),退回 dataSync(仅 6 小时)", e2)
                try {
                    startForeground(
                        NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } catch (e3: Exception) {
                    Log.w(TAG, "dataSync 也失败,前台服务可能未生效", e3)
                }
            }
        }
    }

    /**
     * 前台服务被系统判定「超时」了 —— Android 14+ 对 `dataSync` 这类有配额的
     * 类型会调这里,**几秒内不 `stopForeground()` 就抛 `RemoteServiceException`
     * 把整个进程崩掉**(症状是「连着连着闪退」,而且不给任何提示)。
     *
     * ★★ 2026-11-08(体检 E3):`updateType()` 已经把底座换成 `specialUse`(无配额),
     *   正常情况下不会走到这儿。但真走到了 —— 比如某台机器不认 specialUse、退回了
     *   `dataSync`,或者以后叠上了别的配额类型 —— **必须体面收场**:收起前台形态、
     *   留一条能看懂的记录,而不是崩进程。
     *
     * ★ 两个重载都实现:API 34 调单参那个,API 35+ 调带 `fgsType` 的那个。
     *   不去调 `super`,因为带两个参数的重载在 API 34 的机器上根本不存在(会 NoSuchMethodError)。
     */
    override fun onTimeout(startId: Int) {
        onFgsTimeout("dataSync 配额用完")
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        onFgsTimeout("前台服务类型 $fgsType 超时")
    }

    /**
     * 收手。**不立刻 `stopSelf`**:进程还活着的那几秒里,电脑那头是连着还是断了,
     * 由 [TouchpadClient] 自己那条断线重连去判断;这里只把「前台」这层壳摘掉,
     * 再把事实写进日志(诊断时能看出「不是网络问题,是系统收了配额」)。
     */
    private fun onFgsTimeout(why: String) {
        Log.w(TAG, "$why —— 收起前台形态(不收起就会被系统判 RemoteServiceException)")
        try { ModelManager.get(this).trace("保活:$why,系统收回了前台保活,连接可能随时断") } catch (_: Exception) {}
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        // ★ 渠道名字**改了要换 id**:createNotificationChannel 对**已存在**的渠道
        //   只会忽略新名字(系统不允许改已建渠道的名称/描述),不换 id 就是白改。
        //   代价:旧的「触控板连接」渠道会作为孤儿留在 系统设置→通知 里,卸载重装才清。
        //   2026-10-04 从 touchpad_keepalive 换过来。
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "连接电脑", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ConMarn")
            .setContentText("正在控制电脑,保持屏幕镜像")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "conmarn_keepalive"
        private const val NOTIF_ID = 1

        @Volatile private var instance: KeepAliveService? = null
        @Volatile private var cameraRec = false
        @Volatile private var micRec = false

        /**
         * ★ 语义(2026-10-05 更正,原来的注释是错的):**本进程被用户打开过没有**。
         *
         * 原来写的是「是否存在宿主 MainActivity」,由 `MainActivity.onCreate` 置真、
         * `onDestroy` 置假。身份合并之后 **她的房间才是桌面唯一入口**,那条路上
         * MainActivity **从来不存在** —— 于是这个标志恒为假,而 [onStartCommand]
         * 那句「没有宿主就自停」会把**刚被连接拉起来的**前台服务当场掐掉:
         * 表现为「连着电脑,进程却没有任何前台服务锚着」,ColorOS 一冻就断。
         *
         * ★ 为什么不改成「有没有 Activity 活着」的计数:那会让**退出所有界面之后**
         *   前台服务也失去正当性,而用户要的恰恰是「退出房间**别**断连」。
         *   它要挡的是**另一个**东西 —— 「进程被系统整杀后 START_STICKY 在全新进程里
         *   裸复活,冒出一条谁也关不掉的常驻通知」。判据是「这个进程被打开过没有」,
         *   静态变量在新进程里天然为假,正好答得准。
         *
         * 所以:**只在 `onCreate` 里置真,只在「用户明确退出」(`onTaskRemoved` /
         * [MainActivity.handleUserRemovedTask])里置假。** 别在 onDestroy 里碰它。
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

        /**
         * 「用户明确退出」的收工动作 —— **不依赖任何 Activity**。
         *
         * [onUserRemovedTask] 是 [MainActivity] 登记的回调,它还要关几样只有 MainActivity
         * 才有的东西(中转站面板、文件传输浮层、摄像头/麦克风流)。她的房间那条路上
         * MainActivity 从来没建过,回调是空的 —— 那时至少得把**进程级**的这几件事做掉,
         * 否则「划掉任务卡」等于什么都没发生。
         *
         * ⚠️ 这里**故意不做** `saveWants()` 之类:那些记的是某个界面的开关状态,
         *    没有界面就没有状态可记。
         */
        fun exitApp(ctx: Context) {
            hostActivityLive = false
            try { StationHolder.hide() } catch (_: Exception) {}
            // 断开会连带 [TouchpadClient.releaseProcessAnchor] 把本服务停掉,
            // 外面 onTaskRemoved 紧接着也会 stopSelf —— 两条路都收,不冲突。
            try { TouchpadClient.get(ctx).disconnect() } catch (_: Exception) {}
        }
    }
}
