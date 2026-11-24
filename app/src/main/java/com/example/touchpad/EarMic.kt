package com.example.touchpad

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * 她的耳朵里**唯一碰硬件的那一层**:一个 `AudioRecord`,一块一块地把声音交出去。
 *
 * 配方照抄 [MediaStreamer.startMic]:16kHz / 单声道 / PCM16 / [MediaRecorder.AudioSource.MIC]。
 * **不是**因为抄省事 —— 是因为那个配方在这台机器上已经被真机跑通过,而
 * `AudioRecord` 的参数组合错一个的表现是**构造失败或者一耳朵零**,两种都不好查。
 *
 * ## 这一层真正的工作是「死得看得见」
 *
 * `AudioRecord.read()` 有**三种**返回值,只有一种正常:
 *   · `> 0` —— 读到了这么多个字节
 *   · `= 0` —— 这一瞬没有(时钟还没走满),下一轮再来,**不是错**
 *   · `< 0` —— `ERROR_INVALID_OPERATION(-3)` / `ERROR_BAD_VALUE(-2)` / `ERROR_DEAD_OBJECT(-6)`
 *
 * ★ 数字那个是**致命的**:`ERROR_DEAD_OBJECT` 之后这个 `AudioRecord` 永远不会再给数据。
 *   循环里若只判 `n > 0` 就继续转,线程会**满速空转、一次都不报错**,
 *   外面看到的是「她一直在听」,实际上一个字节都没进来过 —— 而且她还会一直烧电。
 *   所以负数**必须跳出循环并报出去**。
 *
 * ## 缓冲
 *
 * `read()` 用的字节缓冲**复用**(照 [MediaStreamer]),因为它要的是稳定的地址;
 * 交给 [onChunk] 的 `FloatArray` 则是**每块新分配的** —— 那 320 个浮点(每秒 64KB)
 * 的分配量对这个进程微不足道,而换来的是「调用方可以放心留着它」这条简单约定。
 * **一块会被下一轮覆盖的缓冲传来传去,是这个项目最容易写出的一种隐蔽 bug。**
 */
internal class EarMic(
    /** 占麦用的名字,见 [MicGate]。构造之后就固定,保证 acquire/release 配得了对。 */
    private val owner: String,
    /** 每块 PCM 转成的浮点样本(约 20ms)。**归调用方所有**,可以留着。 */
    private val onChunk: (FloatArray) -> Unit,
    /** 出事了。**只在真的坏了的时候调** —— 「没听见」不是错,不该走这里。 */
    private val onError: (String) -> Unit,
) {

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    /** 复用的字节缓冲,见类注释。[onChunk] 拿到的浮点数组是另外新分配的。 */
    private val bytes = ByteArray(CHUNK_BYTES)

    @Volatile var lastError: String? = null
        private set

    val isRunning: Boolean get() = running

    /**
     * 开录。返回 null = 好的;非 null = **人话写的**原因(不抛异常)。
     *
     * 返回原因而不是抛,是因为这一串失败(没权限 / 被占 / 参数不支持 / 设备坏)
     * 全都不是「程序错了」,而是**要告诉他发生了什么**。抛出去的话上面只能吞掉,
     * 而吞掉就回到了「她没听见」那个大坑里。
     */
    @SuppressLint("MissingPermission")   // 权限在 listen() 前由 Activity 要过了;真没给会走 catch
    fun start(): String? {
        if (running) return null
        if (!MicGate.acquire(owner)) {
            // ★ 不硬抢,也不排队 —— 有人正看着电脑画面时,画面优先。见 [MicGate] 的文件头。
            return MicGate.busyReason() ?: "麦克风被占着"
        }
        try {
            val min = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (min <= 0) {
                // ★★ 2026-11-08:早退**必须**松手。这里在 acquire 之后、catch 之外,
                //   漏一次 release 不是崩,是「耳朵从此永久被唤醒占着」——
                //   见 [MicGate] 的文件头,那种失败一句日志都没有。
                MicGate.release(owner)
                return fail("这台手机的麦克风不支持 16k 单声道录音")
            }
            // 缓冲给两倍最小:太小会在系统忙的时候丢样本(丢的是句首,最难发现),
            // 太大则 VAD 断句会滞后。两倍是个已经被 [MediaStreamer] 验过的折中。
            val r = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                MicGate.release(owner)   // ★ 同上一处:早退不松手 = 麦克风被永久占住
                return fail("麦克风初始化失败(可能被别的应用占着)")
            }
            r.startRecording()
            record = r
            running = true
        } catch (e: Exception) {
            // SecurityException = 没给 RECORD_AUDIO;IllegalArgumentException = 参数不被支持。
            // 两种都在这里变成人话,不让它们逃到线程里去。
            running = false
            MicGate.release(owner)
            record?.release()
            record = null
            return fail("打不开麦克风(${e.javaClass.simpleName}: ${e.message})")
        }

        thread = Thread({ loop() }, "ear-mic").apply { isDaemon = true; start() }
        return null
    }

    private fun loop() {
        val r = record ?: return
        while (running) {
            val n = try {
                r.read(bytes, 0, bytes.size)
            } catch (e: Exception) {
                dead("读麦克风出错(${e.javaClass.simpleName})")
                return
            }
            when {
                n > 0 -> {
                    // ★ 只转有效的那 n 个字节。缓冲里 n 之后是**上一轮的残留**,
                    //   全读进来等于把 20ms 前的自己又听了一遍。
                    onChunk(EarMath.pcm16ToFloat(bytes, n))
                }
                n == 0 -> {
                    // 时钟还没走满,不是错 —— 让出一下 CPU,别空转烧电
                    try { Thread.sleep(2) } catch (_: InterruptedException) { return }
                }
                else -> {
                    // ★★ 负数:**这里就是那个「满速空转、一次都不报错」的坑**,见类注释。
                    dead("读麦克风失败(错误码 $n)")
                    return
                }
            }
        }
    }

    /** 停下来。**幂等**,而且无论如何都要放开 [MicGate] —— 漏一次这个,耳朵就永久被占。 */
    fun stop() {
        running = false
        val r = record
        record = null
        try { r?.stop() } catch (_: Exception) {}
        try { r?.release() } catch (_: Exception) {}
        thread = null
        MicGate.release(owner)
    }

    private fun dead(why: String) {
        if (running) {
            running = false
            lastError = why
            val r = record
            record = null
            try { r?.release() } catch (_: Exception) {}
            MicGate.release(owner)
            onError(why)
        }
    }

    private fun fail(why: String): String {
        lastError = why
        return why
    }

    companion object {
        const val SAMPLE_RATE = 16000

        /** 20ms @ 16kHz 单声道 int16 —— 和 [MediaStreamer] 同一块大小,见那个类的常量。 */
        private const val CHUNK_BYTES = 640
    }
}
