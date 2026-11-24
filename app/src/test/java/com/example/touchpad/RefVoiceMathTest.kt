package com.example.touchpad

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★★ `ref.wav`(ZipVoice 的参考音频)的读法。
 *
 * ## 为什么这个解析器必须有测试:它错的时候**一声都不响**
 *
 * 参考音频读错的四种方式,听起来全是「她声音不对」,而 sherpa **一句抱怨都没有**
 * —— 它拿到一个 `FloatArray` 就照用。日志里也没有任何异常。所以:
 *
 * | 错在哪 | 她听起来 |
 * |---|---|
 * | 字节序写反 | 一整段沙沙的噪声 |
 * | 偏移差几个字节(把 `data` 块的头当采样) | 开头一记爆响 + 噪声 |
 * | 采样率读错 | 唐老鸭(或慢放) |
 * | 声道数读错(单声道当成立体声) | 波形被**劈成两半点** |
 *
 * ★ 这四种没有一种看起来像「文件解析错了」,全都像「这个模型的音色不行」
 *   —— 而那时**你会去换模型,不会来看这里**。这就是为什么判据必须落在这儿。
 *
 * ★ 反方向也要钉:**坏文件必须被拒**,不许"猜着读"。
 *   一个 24bit 的 WAV 按 16bit 硬读出来的是噪音,而噪音不像报错。
 */
class RefVoiceMathTest {

    // ---------------------------------------------------------------- 造 WAV

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
    )

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    /** 一个块:`id` + 小端长度 + 内容(+ 长度为奇数时的那个填充字节,和真 WAV 一样)。 */
    private fun chunk(id: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ascii(id))
        out.write(le32(body.size))
        out.write(body)
        if (body.size % 2 == 1) out.write(0)          // ★ 填充字节 —— 落了它后面每个块的位置全错
        return out.toByteArray()
    }

    private fun fmtChunk(channels: Int, rate: Int, bits: Int, format: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(le16(format))
        out.write(le16(channels))
        out.write(le32(rate))
        out.write(le32(rate * channels * bits / 8))   // byteRate
        out.write(le16(channels * bits / 8))          // blockAlign
        out.write(le16(bits))
        return chunk("fmt ", out.toByteArray())
    }

    private fun pcm16(samples: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (s in samples) out.write(le16(s))
        return out.toByteArray()
    }

    /** 拼一个完整的 WAV:`RIFF <size> WAVE` + 后面那些块 + 可选的一截垃圾。 */
    private fun wav(vararg chunks: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        for (c in chunks) body.write(c)
        val b = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(ascii("RIFF"))
        out.write(le32(4 + b.size))
        out.write(ascii("WAVE"))
        out.write(b)
        return out.toByteArray()
    }

    private fun parse(bytes: ByteArray) = RefVoiceMath.parse(bytes, bytes.size)

    private fun okInfo(bytes: ByteArray): RefVoiceMath.WavInfo {
        val p = parse(bytes)
        assertTrue("本该解析成功,结果:${(p as? RefVoiceMath.WavParse.Bad)?.why}", p is RefVoiceMath.WavParse.Ok)
        return (p as RefVoiceMath.WavParse.Ok).info
    }

    private fun badWhy(bytes: ByteArray): String {
        val p = parse(bytes)
        assertTrue("本该被判为坏文件,结果解析成功了", p is RefVoiceMath.WavParse.Bad)
        return (p as RefVoiceMath.WavParse.Bad).why
    }

    // ---------------------------------------------------------------- 正面

    @Test
    fun `单声道 24k 16bit —— 和手机上那份参考音频一模一样的形状`() {
        val bytes = wav(fmtChunk(1, 24000, 16), chunk("data", pcm16(intArrayOf(0, 16384, -16384, 32767))))
        val info = okInfo(bytes)
        assertEquals(24000, info.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(16, info.bitsPerSample)
        assertEquals(8, info.dataLength)          // 4 个采样 × 2 字节
        // ★ data 的起点必须是**那个块的内容**,不是块头 —— 差 8 个字节就是「开头一记爆响」。
        assertEquals(12 + 24 + 8, info.dataOffset)
    }

    @Test
    fun `采样值原样解出来,没有翻倍也没有正负颠倒`() {
        val bytes = wav(
            fmtChunk(1, 24000, 16),
            chunk("data", pcm16(intArrayOf(0, 32767, -32768, 16384))),
        )
        val info = okInfo(bytes)
        val f = RefVoiceMath.toMono(info, bytes, bytes.size)
        assertEquals(4, f.size)
        assertEquals(0f, f[0], 1e-5f)
        assertEquals(32767f / 32768f, f[1], 1e-5f)
        assertEquals(-1f, f[2], 1e-5f)
        assertEquals(0.5f, f[3], 1e-4f)
    }

    @Test
    fun `data 前面有别的块,偏移要跟着走`() {
        // LIST 的长度取奇数(3)—— 顺手把「填充字节」那条一起钉了:
        // 落了它,data 的起点会差一个字节,于是每个采样都错位 → 噪声。
        val bytes = wav(
            chunk("LIST", ascii("abc")),
            fmtChunk(1, 24000, 16),
            chunk("data", pcm16(intArrayOf(100, 200))),
        )
        val info = okInfo(bytes)
        val f = RefVoiceMath.toMono(info, bytes, bytes.size)
        assertEquals(2, f.size)
        assertEquals(100f / 32768f, f[0], 1e-5f)
        assertEquals(200f / 32768f, f[1], 1e-5f)
    }

    @Test
    fun `立体声降成单声道 —— 取左右平均,不是只取左`() {
        // 左 1000 / 右 3000 → 平均 2000。取左的话会是 1000,差一倍。
        val bytes = wav(
            fmtChunk(2, 24000, 16),
            chunk("data", pcm16(intArrayOf(1000, 3000, -1000, -3000))),
        )
        val info = okInfo(bytes)
        assertEquals(2, info.channels)
        val f = RefVoiceMath.toMono(info, bytes, bytes.size)
        assertEquals(2, f.size)                    // 2 帧,不是 4 个采样
        assertEquals(2000f / 32768f, f[0], 1e-5f)
        assertEquals(-2000f / 32768f, f[1], 1e-5f)
    }

    @Test
    fun `data 的长度字段撒谎时按文件真实长度夹住`() {
        // 流式写出来的 WAV 里 data 的长度经常是 0xFFFFFFFF(作者还不知道要写多长)。
        // 照它读 = 越界;**夹一下** = 剩下的都能用。
        val raw = pcm16(intArrayOf(1, 2, 3, 4))
        val bytes = wav(fmtChunk(1, 24000, 16), chunk("data", raw))
        // 手工把 data 的长度字段改成 0xFFFFFFFF
        val at = bytes.size - raw.size - 4
        val huge = le32(-1)
        for (i in 0 until 4) bytes[at + i] = huge[i]

        val info = okInfo(bytes)
        assertEquals(raw.size, info.dataLength)
        assertEquals(4, RefVoiceMath.toMono(info, bytes, bytes.size).size)
    }

    // ---------------------------------------------------------------- 反面

    @Test
    fun `不是 WAV 的两句话分开报,不是一句「坏了」`() {
        val notRiff = badWhy(ascii("JUNKxxxxxxxxyyyy"))
        assertTrue("该说开头不是 RIFF,实际:$notRiff", notRiff.contains("RIFF"))

        val riffNotWave = ByteArray(16).also {
            ascii("RIFF").copyInto(it, 0)
            ascii("AVI ").copyInto(it, 8)
        }
        val notWave = badWhy(riffNotWave)
        assertTrue("该说不是 WAVE,实际:$notWave", notWave.contains("WAVE"))
    }

    @Test
    fun `非 PCM 一律拒 —— 按 16bit 硬读出来的是噪音`() {
        val f32 = badWhy(wav(fmtChunk(1, 24000, 32, format = 3), chunk("data", ByteArray(8))))
        assertTrue("该报格式编号不对,实际:$f32", f32.contains("3"))

        val b24 = badWhy(wav(fmtChunk(1, 24000, 24), chunk("data", ByteArray(6))))
        assertTrue("该报非 16bit,实际:$b24", b24.contains("24"))
    }

    @Test
    fun `声道数不认的三声道要拒,不是硬读`() {
        val why = badWhy(wav(fmtChunk(3, 24000, 16), chunk("data", ByteArray(12))))
        assertTrue("该报声道数,实际:$why", why.contains("3"))
    }

    @Test
    fun `缺 fmt 或缺 data 各说各的话`() {
        val noFmt = badWhy(wav(chunk("data", ByteArray(4))))
        assertTrue("data 在 fmt 前面该被拒,实际:$noFmt", noFmt.contains("fmt"))

        val noData = badWhy(wav(fmtChunk(1, 24000, 16)))
        assertTrue("只有 fmt 没有 data 该被拒,实际:$noData", noData.contains("data"))
    }

    @Test
    fun `空的 data 要拒 —— 它会解出零个采样,而她听起来是「没出声」`() {
        val why = badWhy(wav(fmtChunk(1, 24000, 16), chunk("data", ByteArray(0))))
        assertTrue("该报 data 是空的,实际:$why", why.contains("空"))
    }

    @Test
    fun `文件被砍短了要拒,而且要说成截断`() {
        // ★★ 这条和下面那条**长得一模一样、修法完全相反**:
        //   一个是**传输断在半路**(重推),一个是文件**本来就这么写的**(一个字都别动)。
        //   拿「夹一下照读」把两者都兜住,代价是一段半截的参考音频被照用 ——
        //   她以后的音色就是废的,而日志上一片太平。
        val full = wav(fmtChunk(1, 24000, 16), chunk("data", pcm16(IntArray(64) { 1000 })))
        val cut = full.copyOf(full.size - 125)   // data 自称 128 字节,盘上只剩 3 字节
        val why = badWhy(cut)
        assertTrue("该报截断,实际:$why", why.contains("截断"))
    }

    @Test
    fun `流式 WAV 的长度字段不算撒谎,但也救不回来`() {
        // 0xFFFFFFFF 是流式写入时约定的「我还不知道要写多长」——
        // **它不是截断**,不能报成「传输断在半路」(那会把人支使去重推一个没坏的文件)。
        // 但这个文件里一个采样都没有,所以照样得拒,而且要说成「空的」。
        val raw = pcm16(intArrayOf(1, 2))
        val bytes = wav(fmtChunk(1, 24000, 16), chunk("data", raw))
        val at = bytes.size - raw.size - 4
        val huge = le32(-1)
        for (i in 0 until 4) bytes[at + i] = huge[i]

        val why = badWhy(bytes.copyOf(at + 4))   // 长度字段留着,内容全砍掉
        assertTrue("流式标记不算截断:$why", !why.contains("截断"))
        assertTrue("但没数据就得报空:$why", why.contains("空"))
    }
}
