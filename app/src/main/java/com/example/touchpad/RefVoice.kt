package com.example.touchpad

/**
 * 参考音频的**读法**。
 *
 * ZipVoice 要的不是「一段声音」,是**一段已经解成浮点的 24000Hz 单声道波形**
 * (见 `SherpaVoice` 里 ZipVoice 那一段)。而我们要放进目录的是一个
 * **`ref.wav` 文件** —— 这样换一个人的声音 = **换两个文件,不改代码**,
 * 和 `person/`、`room/` 那套「放文件就生效」是同一个形态。
 *
 * ## ★★ 为什么这段解析要单独拿出来、还要单测
 *
 * 因为**它错的时候一点声音都没有,而且看起来完全不像解析错了**:
 *
 * | 错在哪 | 她听起来是什么样 | 日志里是什么 |
 * |---|---|---|
 * | 字节序写反(大端当小端) | **沙沙的噪声**,不是人声 | 一片太平 |
 * | 偏移差 4 个字节(把 `data` 的头当成采样) | 开头一记爆响 + 整段噪声 | 一片太平 |
 * | 采样率读错 | 声音变成**唐老鸭或者慢放** | 一片太平 |
 * | 声道数读错(单声道当立体声) | 波形被**劈成两半点** | 一片太平 |
 *
 * 这四种的共同点是:**sherpa 一句抱怨都没有** —— 它拿到一个 `FloatArray` 就照用。
 * 所以判据只有一个:**在电脑上先把这个解析器钉死,再看真机。**
 *
 * ## 分层(照 [VoiceFiles] / [EarMath] 的惯例)
 *
 * [parse] 和 [toMono] **零 IO、零 Android 依赖** —— 单测直接喂字节;
 * 去读盘那一步留在调用方([SherpaVoice])。
 */
internal object RefVoiceMath {

    /** WAV 头里我们要的那几个数。 */
    internal data class WavInfo(
        /** 采样率(Hz)。ZipVoice 的参考音频实测是 24000。 */
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        /** `data` 块的**第一个采样字节**在文件里的下标。 */
        val dataOffset: Int,
        /** `data` 块**可用的字节数**(已经按「文件真的有多长」夹过一次)。 */
        val dataLength: Int,
    )

    /** 解析结果。**坏掉时带一句人话** —— 它是唯一能让「她出声是噪音」和「文件坏了」分开的东西。 */
    internal sealed class WavParse {
        internal data class Ok(val info: WavInfo) : WavParse()
        internal data class Bad(val why: String) : WavParse()
    }

    private const val FMT_PCM = 1

    /** 流式写出来的 WAV 在「还不知道要写多长」时给 `data` 的长度字段填的那个数。 */
    private const val STREAMING_SIZE = 0xFFFFFFFFL

    /**
     * 小端读一个无符号多字节整数。WAV 里**所有**多字节字段都是小端。
     *
     * ★ 越界一律回 -1(调用方会把它演成「文件被截断了」)——
     *   别让它抛:`catch(Throwable)` 那一层**分不出**「文件坏」和「我们写错了」。
     */
    private fun le(bytes: ByteArray, at: Int, n: Int): Long {
        if (at < 0 || at + n > bytes.size) return -1L
        var v = 0L
        for (i in n - 1 downTo 0) v = (v shl 8) or (bytes[at + i].toLong() and 0xFFL)
        return v
    }

    private fun fourcc(bytes: ByteArray, at: Int): String =
        if (at < 0 || at + 4 > bytes.size) "" else String(bytes, at, 4, Charsets.US_ASCII)

    /**
     * RIFF/WAVE 头 → [WavInfo]。
     *
     * ★ **不用 `javax.sound`**:那是桌面 JVM 的库,Android 上没有 ——
     *   在电脑上跑得通、在手机上 `NoClassDefFoundError`,是这个项目最怕的那种
     *   「量具和真机不是一回事」。
     *
     * ★ **不认的格式一律 `Bad`,绝不猜** —— 猜错了得到的是噪音,而噪音看起来
     *   像「模型不行」,不像「我们把 24bit 当 16bit 读了」。
     */
    internal fun parse(bytes: ByteArray, len: Int): WavParse {
        val n = minOf(len, bytes.size)
        if (n < 12) return WavParse.Bad("文件太短($n 字节),连 WAV 的头都不够")
        if (fourcc(bytes, 0) != "RIFF") return WavParse.Bad("开头不是 RIFF —— 这不是一个 WAV 文件")
        if (fourcc(bytes, 8) != "WAVE") return WavParse.Bad("RIFF 后面不是 WAVE —— 这不是一个 WAV 文件")

        var sampleRate = 0
        var channels = 0
        var bits = 0
        var fmtSeen = false

        var at = 12
        while (at + 8 <= n) {
            val id = fourcc(bytes, at)
            val size = le(bytes, at + 4, 4)
            if (size < 0) return WavParse.Bad("块「$id」的长度字段读不出来 —— 文件被截断了")
            val body = at + 8

            if (id == "fmt ") {
                if (size < 16) return WavParse.Bad("fmt 块只有 $size 字节,至少要有 16")
                if (body + 16 > n) return WavParse.Bad("fmt 块越界 —— 文件被截断了")
                val audioFormat = le(bytes, body, 2)
                if (audioFormat != FMT_PCM.toLong()) {
                    // ★ 24bit / 32bit float / ADPCM 全落在这儿。它们是**能读出来**的,
                    //   只是读法完全不同 —— 按 16bit 硬读出来的是一段噪音。
                    return WavParse.Bad("这个 WAV 的格式编号是 $audioFormat,不是未压缩 PCM(1)")
                }
                channels = le(bytes, body + 2, 2).toInt()
                sampleRate = le(bytes, body + 4, 4).toInt()
                bits = le(bytes, body + 14, 2).toInt()
                fmtSeen = true
            } else if (id == "data") {
                if (!fmtSeen) return WavParse.Bad("data 块跑到 fmt 前面去了 —— 这种 WAV 我们不认")
                if (bits != 16) return WavParse.Bad("这是 ${bits}bit 的,我们只认 16bit")
                if (channels != 1 && channels != 2) {
                    return WavParse.Bad("这是 $channels 声道的,我们只认单声道或立体声")
                }
                if (sampleRate <= 0) return WavParse.Bad("采样率读出来是 $sampleRate")
                val avail = (n - body).coerceAtLeast(0).toLong()
                // ★★ 「文件被砍短了」和「这个 WAV 本来就没写长度」长得像,**但修法完全相反**:
                //   前者是**传输断在半路**(重推),后者是文件本身就这么写的(**一个字都别动**)。
                //   分开它们只需要一条:`0xFFFFFFFF` 是流式写入约定的「我还不知道要写多长」,
                //   而**任何一个别的数**比文件真实长度还大,就是文件没有它自称的那么长。
                //
                // ★ 这一条不能只靠「夹一下照读」兜着 —— 那样一段半截的参考音频会被**照用**,
                //   她以后的音色就是废的,而日志上一片太平。
                if (size > 0L && size != STREAMING_SIZE && size > avail) {
                    return WavParse.Bad(
                        "data 块声明 $size 字节,文件里只剩 $avail —— 文件被截断了(传输断在半路)"
                    )
                }
                // ★ 其余情况按**文件真的有多长**夹一次。
                val real = minOf(size, avail)
                // 整样本对齐:末尾半个样本丢掉(和 EarMath.pcm16ToFloat 同一条规矩)
                val frame = channels * 2
                val aligned = (real / frame) * frame
                if (aligned < frame) return WavParse.Bad("data 块是空的($aligned 字节可用)")
                return WavParse.Ok(WavInfo(sampleRate, channels, bits, body, aligned.toInt()))
            }

            // ★ 每个块的长度是**奇数**时要补一个填充字节,算下一块的起点必须带上它。
            //   漏了这一步,后面每一个块的位置全错 —— 而错的表现是「找不到 data」。
            at = body + size.toInt() + (size.toInt() and 1)
        }
        return WavParse.Bad(if (fmtSeen) "没找到 data 块" else "没找到 fmt 块")
    }

    /**
     * [WavInfo] + 原始字节 → **单声道** 的 [-1,1] 浮点。
     *
     * 立体声按左右**平均**降成单声道 —— 不是取左声道。
     * 参考音频原则上就该是单声道;真给了立体声时,**取一边**会把她的一半音色丢掉,
     * 而平均**不会**(代价只是可能的相位抵消,那种情况耳朵听得出来,不属于静默失败)。
     */
    internal fun toMono(info: WavInfo, bytes: ByteArray, len: Int): FloatArray {
        val n = minOf(len, bytes.size)
        val end = minOf(info.dataOffset + info.dataLength, n)
        val start = info.dataOffset.coerceIn(0, end)
        val raw = end - start
        val frame = info.channels * 2
        val frames = (raw / frame).coerceAtLeast(0)

        // 单声道**不需要**再拷一份:直接解 data 那一段。
        // ★ 这里用 `copyOfRange` 是因为 [EarMath.pcm16ToFloat] 吃的是「从头算」的数组,
        //   而 data 不一定从 0 开始(前面有 RIFF/fmt/可能有 LIST…)。
        val pcm = bytes.copyOfRange(start, start + frames * frame)
        if (info.channels == 1) return EarMath.pcm16ToFloat(pcm, pcm.size)

        val lr = EarMath.pcm16ToFloat(pcm, pcm.size)
        val out = FloatArray(frames)
        for (i in 0 until frames) out[i] = (lr[2 * i] + lr[2 * i + 1]) * 0.5f
        return out
    }
}
