package com.example.touchpad

import kotlin.math.sqrt

/**
 * 嗓子里那几个**纯算术**的部分。
 *
 * 和 `EarMath` / `MoodMath` / `GreetMath` 一个路数:零 Android 依赖,
 * 所以能在电脑上跑 JVM 单测。这不是洁癖 —— 下面每一个函数错了都**不抛异常**,
 * 表现只是「她嘴型怪」或者「她炸麦」,真机上要查得先排除十几个别的可能。
 *
 * ★ 这些函数是**为流式音频写的**:外面 [SherpaVoice] 每合成出一小块调用一次。
 * 所以它们必须便宜 —— 一块几百个样本,一秒要跑几十次。
 */
internal object VoiceMath {

    /**
     * 浮点样本 → PCM16 小端字节。
     *
     * sherpa 吐出来的样本归一在 `[-1, 1]`(`GeneratedAudio.samples` 的约定),
     * 而 `AudioTrack` 要的是 16 位有符号整数。
     *
     * ★ 两处最容易写反的地方,各钉一条测试:
     *   · **字节序** —— `AudioTrack` 收的是**小端**,低字节在前。
     *     写反了不是没声音,是**刺耳的噪声**(每个样本的高低位对调)。
     *   · **钳位** —— 乘以 32767 之后**要先钳再转 Int**。浮点合成偶尔会吐出
     *     超出 1.0 的样本,`(1.2 * 32767).toInt()` = 39320,转成 Short 会
     *     **绕回负数** —— 一声巨响之后跟着一个反向的尖峰。必须 `coerceIn`。
     */
    internal fun floatToPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            out[i * 2] = (v and 0xFF).toByte()          // 低字节在前 = 小端
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * 这一块音频有多响,归一到 `0..1` —— **她的嘴张多大就是它说了算**。
     *
     * 真 RMS(平方平均开方)。下面那条曲线不是随便定的:
     *
     * | 输入 RMS | 输出 | 为什么 |
     * |---|---|---|
     * | 0 | 0 | 静音 = 闭嘴 |
     * | 0.02(气声) | ≈ 0.36 | ★ **开根号**:人耳对响度是对数的,线性映射会让正常说话**永远只张一条缝** |
     * | 0.15(正常说话) | 1.0 | [REFERENCE] —— 到这儿就算「张满」 |
     * | > 0.15 | 1.0 | 钳住,不让她张大到变形 |
     *
     * ★ 开根号(`sqrt`)这一步是这条曲线里唯一有讲究的地方,也是我第一版写错的地方:
     *   直接用 `rms / REFERENCE` 的话,正常说话大概只有 0.1~0.2 的开口度,
     *   看着就是「她嘴几乎不动」。**平方根把低电平抬起来**,这才对得上人眼。
     *
     * 返回 0 是合法的(静音),返回 1 也是合法的(喊)。**调用方不需要再钳。**
     */
    internal fun mouthLevel(rms: Float): Float {
        if (rms <= 0f) return 0f
        return sqrt((rms / REFERENCE).coerceIn(0f, 1f))
    }

    /**
     * 一块样本的 RMS。空数组返回 0 —— **不能返回 NaN**。
     *
     * ★ 空块是真会来的(合成的最后一块可能只有几个样本,而 VAD/切片边界
     *   可能给出 0 长度)。`0/0` = NaN,而 NaN 一路走到 `sqrt` 还是 NaN,
     *   最后进了 `expressionManager.setValue("aa", NaN)` —— **她的嘴会整个消失**
     *   (不像张嘴也不像闭嘴,是渲染管线里那个网格塌掉)。静默、难查、且看起来像
     *   「模型加载失败」。
     */
    internal fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / samples.size).toFloat()
    }

    /**
     * 正常说话大约到这个 RMS 就算「嘴张满」。
     *
     * 0.15 是照 sherpa VITS 的输出电平定的(它做过归一,不是原始录音电平)。
     * ⚠️ 换模型(比如从 eula 换到 echo)可能要重调这一个数 ——
     *   症状是「嘴一直张满」或者「一直不动」。**就这一个常数,别去改曲线。**
     */
    internal const val REFERENCE = 0.15f

    // ------------------------------------------------------------ 804 个嗓子里挑一个

    /**
     * 把「存着的那个音色号」夹进**这台机器上、这一份模型真的有的**范围。
     *
     * ★★ 为什么这道夹必须存在(2026-10-05):
     *
     * 那个 804 是**当前这份 VITS 模型**的数字(`e.numSpeakers()`)。他哪天换一个
     * (voice-lab 里就躺着 4 个中文 VITS),同一个号指向的就是**另一个人**,或者压根不存在。
     * 越界的号会被直接送进 `tts.generate(text, sid, …)` —— 那是 **native 调用**:
     * 它可能回一份**空音频**(表现成「她不说话了」),也可能像
     * `generateWithCallback` 那次一样直接把进程 **abort** 掉。
     * 两种都是**不报错**的死法,所以判据只能落在这儿。
     *
     * ★ 模型报 0 个音色(单音色模型,或引擎还没就绪)时一律回 0 ——
     *   不是「随便挑一个」,是「这个模型只有一个默认的」。
     */
    internal fun clampSpeaker(sid: Int, count: Int): Int =
        if (count <= 0) 0 else sid.coerceIn(0, count - 1)

    /**
     * 试听时按「上一个 / 下一个」。[delta] 通常是 ±1。
     *
     * ★ 这里是**绕圈**,和 [clampSpeaker] 的夹住**故意不一样**:
     *   804 个音色要一个个试过去,走到 803 再按「下一个」弹回 0
     *   比停在原地有用得多 —— 停在原地的话他会以为按钮坏了(而它确实没反应)。
     *
     * ★ `%` 在 Kotlin 里对负数回负数,所以要先 `+count` 再取模一次,
     *   否则从 0 按「上一个」会得到 -1 → 一个**越界**的号。
     *   (这条单独有测试钉着:`stepSpeaker(0, 804, -1)` 必须是 803。)
     */
    internal fun stepSpeaker(sid: Int, count: Int, delta: Int): Int {
        if (count <= 0) return 0
        val cur = clampSpeaker(sid, count)
        return ((cur + delta) % count + count) % count
    }
}
