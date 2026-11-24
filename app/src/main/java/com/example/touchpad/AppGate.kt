package com.example.touchpad

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 「什么时候该问他要手指」—— 纯规则，能跑 JVM 单测。
 *
 * ★ 判据只有两条，都是他 2026-10-06 拍过板的：
 *   1. **冷启动一次 + 锁屏回来一次**；普通切后台（回来）、开个子页、弹个框，都不重问。
 *      做成「每次切回来都按」会把她「一直在」那件事毁掉。
 *   2. 危险动作（钱 / 身份那一轮）动手前再验一次本人。
 *
 * ★ 两条**不能互相替代**：门口那道锁挡的是「别人拿到你的手机」，
 *   动作那道挡的是「她自己判断错了」。装了指纹 ≠ 安全了 —— 这一句要能说出口。
 */
internal object GateMath {

    /**
     * 这一下要不要问。
     *
     * @param unlocked 这个进程里已经验过没有
     * @param lockedSince 验过之后屏幕熄过 / 锁过没有
     *
     * ★ **验不了就别挡**（`available=false` → 不问）：这台机器没有指纹也没有锁屏密码时，
     *   硬挡的结果是他永远进不去。挡不住要说出来（设置那一行会写），不能变成打不开。
     */
    fun needUnlock(
        enabled: Boolean,
        available: Boolean,
        unlocked: Boolean,
        lockedSince: Boolean,
    ): Boolean = enabled && available && (!unlocked || lockedSince)

    /** 危险动作那一下：已经验过也要再验 —— 它是「现在动手的是不是本人」。 */
    fun needHard(enabled: Boolean, available: Boolean): Boolean = enabled && available
}

/**
 * 门口那道锁 + 危险动作的二次验证。**平台自带弹窗，不引任何库、不拿任何像素。**
 *
 * ## 为什么用平台的 `BiometricPrompt`
 *
 * minSdk 30，平台从 Android 9 就有这个弹窗；指纹模板在安全芯片里，App 只收到「过 / 没过」。
 * 所以「不能保留我的脸」这条在**验证这条路**上天然成立 —— 这条路上根本没有画面。
 *
 * ## 它挡不住什么（必须说清，否则是假的安全感）
 *
 * - 挡不住**她判断错**：那要靠三层风险闸；
 * - 挡不住**已经解锁之后**别人拿走手机 —— 那是系统的锁屏该管的事。
 */
object AppGate {

    private const val TAG = "Gate"
    private const val PREF = "gate"
    private const val KEY_ON = "on"

    @Volatile private var unlocked = false
    @Volatile private var lockedSince = false
    @Volatile private var asking = false

    /** 屏一熄就记一笔「验过之后又锁过」—— 见 [GateMath.needUnlock]。 */
    @Volatile private var inited = false

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        try {
            ctx.applicationContext.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.action == Intent.ACTION_SCREEN_OFF) lockedSince = true
                }
            }, IntentFilter(Intent.ACTION_SCREEN_OFF))
        } catch (e: Exception) {
            // 注册不上也不会崩：锁屏回来那一次就退化成人自己再开一次 App。
            Log.w(TAG, "屏息广播没注册上", e)
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isOn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ON, true)

    fun setOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ON, on).apply()
        // ★ 关掉时把「这个进程已经验过」也置上：下一次开 App 不该因为刚才关开关就咬他一口。
        if (!on) markUnlocked()
    }

    /**
     * 这台机器能不能验。
     *
     * ★ 允许**锁屏密码兜底**（`DEVICE_CREDENTIAL`）：指纹按错太多次、或者传感器坏了的时候，
     *   他还能用密码进来。**没有这条兜底，这道锁会变成「打不开」**。
     */
    fun available(ctx: Context): Boolean = try {
        // ★ 平台那个 `BiometricManager` 没有 `from()`（那是 androidx 的写法）——
        //   这里刻意**不引 androidx.biometric**，所以只能从系统服务上拿。
        val bm = ctx.getSystemService(Context.BIOMETRIC_SERVICE) as? BiometricManager
        bm != null && bm.canAuthenticate(ALLOWED) == BiometricManager.BIOMETRIC_SUCCESS
    } catch (e: Exception) {
        false
    }

    /** 门口那一下要不要按。 */
    fun needsUnlock(ctx: Context): Boolean =
        GateMath.needUnlock(isOn(ctx), available(ctx), unlocked, lockedSince)

    /** 危险动作那一下要不要按。 */
    fun needsHard(ctx: Context): Boolean = GateMath.needHard(isOn(ctx), available(ctx))

    fun markUnlocked() {
        unlocked = true
        lockedSince = false
    }

    /**
     * 问他一次。**异步**：结果在 UI 线程回调。
     *
     * @param onFail 没验过（按了取消 / 按错太多次 / 弹不出来）。**弹不出来也算没过**，
     *   绝不静默放行 —— 那会让「没验」和「验过了」在账上长得一模一样。
     */
    fun ask(activity: Activity, title: String, why: String, onPass: () -> Unit, onFail: () -> Unit) {
        if (asking) return
        asking = true
        var done = false
        val main = Handler(Looper.getMainLooper())
        val token = Any()

        fun finish(ok: Boolean) {
            if (done) return
            done = true
            asking = false
            main.removeCallbacksAndMessages(token)
            if (ok) {
                markUnlocked()
                onPass()
            } else {
                onFail()
            }
        }

        // ★★ 2026-11-08 真机踩出来的死结：**弹窗没回调，`asking` 就永远是真的。**
        //
        //   `finish()` 是唯一的出口，而它只由 `onAuthenticationSucceeded` /
        //   `onAuthenticationError` / 构建异常三处调用。平台的弹窗**存在第四种结局**：
        //   窗口被系统拆掉（配置变化、锁屏、别的弹窗顶上来）而两个回调一个都不来。
        //   那时候 `asking` 卡死为真 ⇒ 之后每一次 [ask] 都在第一行就返回，
        //   两个回调都不跑 ⇒ 房间那层黑底（`coverForGate`）再也撤不掉。
        //
        //   实测(03:55:14 BiometricPrompt 窗口 destroySurface，AppGate 一行日志都没有)：
        //   房间还在、网页还在画、「今日热点/天气/语录」三块板照常显示,
        //   而她的房间、顶栏、输入栏全被那块 `#160F13` 盖住 —— 他说的
        //   「主界面连她房间都没了」就是这个。
        //
        //   看门狗只做一件事：**保证 `finish()` 一定会被叫一次**。
        main.postDelayed({
            Log.w(TAG, "验证弹窗 ${ASK_TIMEOUT_MS}ms 没有回音 —— 按「没验过」处理")
            finish(false)
        }, token, ASK_TIMEOUT_MS)

        // ★ 建弹窗必须在 UI 线程：危险动作那一路是从后台线程调过来的([askBlocking])。
        activity.runOnUiThread {
            try {
                val prompt = BiometricPrompt.Builder(activity)
                    .setTitle(title)
                    .setSubtitle(why)
                    .setAllowedAuthenticators(ALLOWED)
                    .build()
                prompt.authenticate(
                    CancellationSignal(),
                    activity.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(
                            result: BiometricPrompt.AuthenticationResult,
                        ) = finish(true)

                        override fun onAuthenticationError(code: Int, msg: CharSequence) {
                            Log.w(TAG, "没验过：$code $msg")
                            finish(false)
                        }

                        // 按错一次不算失败：系统会让人再按，回调里只是「这一次没对上」。
                        override fun onAuthenticationFailed() {}
                    },
                )
            } catch (e: Exception) {
                Log.w(TAG, "验证弹窗起不来", e)
                finish(false)
            }
        }
    }

    /** 后台线程要一个同步答案时用（危险动作的确认框那边就是这么用的）。 */
    fun askBlocking(activity: Activity, title: String, why: String, seconds: Long = 60): Boolean {
        val latch = CountDownLatch(1)
        val ok = booleanArrayOf(false)
        ask(activity, title, why, onPass = { ok[0] = true; latch.countDown() }, onFail = { latch.countDown() })
        return try {
            latch.await(seconds, TimeUnit.SECONDS) && ok[0]
        } catch (e: InterruptedException) {
            false
        }
    }

    /**
     * 嘴不动也要有个上限。见 [ask] 里那段看门狗。
     *
     * 90 秒：够他找手指、也够锁屏密码那条兜底慢慢输；再久就说明这个弹窗已经死了。
     */
    private const val ASK_TIMEOUT_MS = 90_000L

    /** 指纹（弱档也算）+ 锁屏密码兜底。见 [available] 那条注释。 */
    private const val WEAK = BiometricManager.Authenticators.BIOMETRIC_WEAK
    private const val CREDENTIAL = BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** `const` 里不能用 `or`（它不是编译期常量），所以单独一条。 */
    private val ALLOWED = WEAK or CREDENTIAL
}
