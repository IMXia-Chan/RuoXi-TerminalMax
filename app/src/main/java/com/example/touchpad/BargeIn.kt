package com.example.touchpad

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Handler
import android.os.Looper

/**
 * **她说话的时候，他能插嘴。**
 *
 * ## 为什么这件事在这台机器上难
 *
 * 整个房间是按**半双工**搭的：她出声时麦克风必须关（见 ConMarnActivity.onSpokenDone
 * 那段注释）。理由是硬的 —— 这台机器是外放，扬声器的声音会绕回麦克风，变成一句
 * 「用户输入」，于是她自问自答。而那个错**不报任何错**：她拿到的是一句语法通顺的
 * 用户话，日志上干干净净。
 *
 * 所以「边说她边听」不是把麦打开就行，必须解决**回声**：
 *
 *   1. 采音用 `VOICE_COMMUNICATION` 源（这条源在多数机型上走的是通话那条有
 *      回声消除的链路）；
 *   2. 再挂一个 [AcousticEchoCanceler]（系统支持才有）；
 *   3. 电平判据用**她自己的声音做基准**：刚开口那半秒量一遍，之后超过它一倍
 *      才算「有人在说」—— 外放的音量每天都不同，写死一个阈值一定错。
 *
 * ## ★ 默认关着，而且系统不支持回声消除时**不许开**
 *
 * 没有回声消除就开这一档，结果一定是「她老是打断自己」—— 那比不能插嘴糟得多，
 * 而且看起来像她坏了。所以 [setOn] 会先问 [available]，不给开就直说原因。
 *
 * ## 纯算术在 [BargeInMath]
 *
 * 「几帧算一句话」这种规则错了**不报错**：要么她被自己的尾音打断（每句都说半截），
 * 要么他喊破嗓子也没反应。两样都只有真机上才看得出来，所以判定部分单独拿出来钉住。
 */
object BargeIn {

    private const val PREF = "conmarn_barge"
    private const val KEY_ON = "on"

    private const val RATE = 16_000
    private const val FRAME = RATE / 50          // 20 毫秒一帧 = 320 个采样

    /** 连续多少帧过线才认。160 毫秒：说一个字的启动时间，而一声咳嗽不会这么长。 */
    private const val NEED = 8

    /** 开口后先量这么多帧（500 毫秒）当基准。 */
    private const val CALIB = 25

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var thread: Thread? = null
    @Volatile private var rec: AudioRecord? = null
    @Volatile private var aec: AcousticEchoCanceler? = null
    @Volatile private var cancel = false

    fun isOn(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_ON, false)

    /**
     * 拨开关。**开之前先问系统支不支持回声消除** —— 不支持就返回 false，
     * 由调用方把原因说给他听（见 [whyOff]）。
     */
    fun setOn(ctx: Context, on: Boolean): Boolean {
        if (on && !available()) return false
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ON, on).apply()
        if (!on) stop()
        return true
    }

    /** 这台机器能不能做。**只有它说能，这一档才有意义。** */
    fun available(): Boolean = try {
        AcousticEchoCanceler.isAvailable()
    } catch (_: Throwable) {
        false
    }

    /** 开不了的时候给他看的那一句。 */
    fun whyOff(): String =
        "这台手机不支持系统级回声消除。开启后扬声器的声音会被当作用户语音，因此该项不可用。"

    /**
     * 开始听。**她开口那一刻调**，[stop] 在她闭嘴（或被掐掉）那一刻调。
     *
     * @param onTrigger 判定「有人在说话」时回调一次，然后自己停掉。**主线程**。
     */
    fun start(ctx: Context, onTrigger: () -> Unit) {
        if (thread != null) return
        val app = ctx.applicationContext
        if (!isOn(app)) return
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return
        cancel = false
        val t = Thread {
            runCatching { loop(app, onTrigger) }
            cleanup()
        }
        t.name = "barge-in"
        t.isDaemon = true
        thread = t
        t.start()
    }

    fun stop() {
        cancel = true
        thread = null
        runCatching { rec?.stop() }
        runCatching { aec?.release() }
        aec = null
        runCatching { rec?.release() }
        rec = null
    }

    private fun loop(app: Context, onTrigger: () -> Unit) {
        val minBuf = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufBytes = maxOf(minBuf, FRAME * 2 * 20)
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes,
            )
        } catch (e: Exception) {
            trace(app, "开不了麦克风（${e.javaClass.simpleName}）")
            return
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            trace(app, "麦克风没初始化成功")
            r.release()
            return
        }
        rec = r
        aec = try {
            AcousticEchoCanceler.create(r.audioSessionId)?.also { it.enabled = true }
        } catch (_: Throwable) {
            null
        }
        r.startRecording()

        val pcm = ShortArray(FRAME)
        val calib = ArrayList<Float>(CALIB)
        var floor = Float.NaN
        var run = 0
        while (!cancel) {
            val n = try {
                r.read(pcm, 0, pcm.size)
            } catch (_: Exception) {
                -1
            }
            if (n <= 0) continue
            val level = BargeInMath.rms(pcm, n)
            if (floor.isNaN()) {
                calib.add(level)
                if (calib.size >= CALIB) {
                    floor = BargeInMath.threshold(BargeInMath.median(calib))
                    trace(app, "基准 ${"%.3f".format(BargeInMath.median(calib))} → 阈值 ${"%.3f".format(floor)}")
                }
                continue
            }
            run = if (level >= floor) run + 1 else 0
            if (run >= NEED) {
                trace(app, "他插话了（电平 ${"%.3f".format(level)} ≥ 阈值 ${"%.3f".format(floor)}）")
                cancel = true
                main.post(onTrigger)
                return
            }
        }
    }

    private fun cleanup() {
        runCatching { rec?.stop() }
        runCatching { aec?.release() }
        runCatching { rec?.release() }
        aec = null
        rec = null
    }

    private fun trace(app: Context, msg: String) {
        runCatching { ModelManager.get(app).trace("[打断] $msg") }
    }
}

/** 「这几帧算不算他在说话」—— 纯算术，纯 JVM 可测。 */
internal object BargeInMath {

    /** 一段 16 位 PCM 的均方根（0~1）。全零 = 0，满幅 ≈ 1。 */
    fun rms(pcm: ShortArray, len: Int = pcm.size): Float {
        if (len <= 0) return 0f
        var sum = 0.0
        for (i in 0 until minOf(len, pcm.size)) {
            val v = pcm[i] / 32768.0
            sum += v * v
        }
        return Math.sqrt(sum / minOf(len, pcm.size)).toFloat()
    }

    /**
     * 她自己的声音量出来的基准 → 判定阈值。
     *
     * ★ 乘 2：外放绕回来的回声比原声小得多，而正常说话的音量不会只高出一点点。
     * ★ 上下夹住：太小会被空调声触发，太大则喊破嗓子也没反应。
     */
    fun threshold(baseline: Float): Float = (baseline * 2.0f).coerceIn(0.03f, 0.45f)

    fun median(v: List<Float>): Float {
        if (v.isEmpty()) return 0f
        val s = v.sorted()
        return s[s.size / 2]
    }

    /**
     * 连续 [need] 帧过线，返回**这一串的第一帧下标**；否则 null。
     *
     * ★ 必须是**连续**：单帧的尖叫（关门、碗掉地上）不该打断她。
     */
    fun firstHit(levels: List<Float>, floor: Float, need: Int): Int? {
        if (need <= 0) return null
        var run = 0
        for (i in levels.indices) {
            run = if (levels[i] >= floor) run + 1 else 0
            if (run >= need) return i - need + 1
        }
        return null
    }
}
