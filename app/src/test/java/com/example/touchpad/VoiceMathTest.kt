package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 嗓子里那几个纯算术的测试。
 *
 * 和 `EarMathTest` / `MoodMathTest` 一个路数:**这里防的错全都不抛异常**。
 * 字节序写反 = 刺耳的噪声;钳位漏了 = 一声尖啸;RMS 除零 = 她的嘴整个消失。
 * 三种在真机上都会被读成「新引擎坏了」,而真因是这里的一行。
 */
class VoiceMathTest {

    /** 把两个字节按**小端**拼回一个有符号 16 位数 —— 和 `AudioTrack` 读法一致。 */
    private fun le(bytes: ByteArray, i: Int): Short =
        (((bytes[i * 2 + 1].toInt() and 0xFF) shl 8) or (bytes[i * 2].toInt() and 0xFF)).toShort()

    // ---------------------------------------------------------------- 字节序

    /**
     * ★★ 这条钉的是**字节序**。写反了不是没声音,是**刺耳的噪声** ——
     * 每个样本的高低位对调,听感是白噪声,而日志里一切正常。
     */
    @Test
    fun `PCM16_是小端_低字节在前`() {
        // 0.5 → 16383 = 0x3FFF → 小端应为 [0xFF, 0x3F]
        val b = VoiceMath.floatToPcm16(floatArrayOf(0.5f))
        assertEquals("低字节在前", 0xFF.toByte(), b[0])
        assertEquals("高字节在后", 0x3F.toByte(), b[1])
    }

    /** 三个定点值:静音是 0,正满是 32767,负满是 -32767。 */
    @Test
    fun `PCM16_定点值`() {
        assertEquals("静音必须是整 0", 0.toShort(), le(VoiceMath.floatToPcm16(floatArrayOf(0f)), 0))
        assertEquals("正满", 32767.toShort(), le(VoiceMath.floatToPcm16(floatArrayOf(1f)), 0))
        assertEquals("负满", (-32767).toShort(), le(VoiceMath.floatToPcm16(floatArrayOf(-1f)), 0))
    }

    /**
     * ★★ 这条钉的是**钳位**。
     *
     * 浮点合成偶尔会吐出超出 `[-1, 1]` 的样本。不钳的话
     * `(1.5 * 32767).toInt()` = 49150,`and 0xFF` 之后高字节变成 `0xBF` ——
     * **正半周翻转成负半周**,听感是一声尖啸加爆音。
     * 判据:超界的样本必须和「正好 1.0」**一模一样**。
     */
    @Test
    fun `PCM16_超界要钳住_不许绕回负数`() {
        val over = VoiceMath.floatToPcm16(floatArrayOf(1.5f))
        assertEquals("1.5 必须和 1.0 同值", le(VoiceMath.floatToPcm16(floatArrayOf(1f)), 0), le(over, 0))
        assertTrue("钳过之后绝不能是负的", le(over, 0) > 0)

        val under = VoiceMath.floatToPcm16(floatArrayOf(-1.8f))
        assertEquals("−1.8 必须和 −1.0 同值", le(VoiceMath.floatToPcm16(floatArrayOf(-1f)), 0), le(under, 0))
    }

    /** 长度必须是样本数的两倍 —— 少一个字节整段音频就错位了。 */
    @Test
    fun `PCM16_长度是样本数的两倍`() {
        assertEquals(0, VoiceMath.floatToPcm16(floatArrayOf()).size)
        assertEquals(2, VoiceMath.floatToPcm16(floatArrayOf(0.1f)).size)
        assertEquals(1024, VoiceMath.floatToPcm16(FloatArray(512)).size)
    }

    // ---------------------------------------------------------------- 响度

    /**
     * ★★ 空块**必须返回 0,不能返回 NaN**。
     *
     * 空块是真会来的(合成的最后一块可能只有几个样本)。
     * `0/0` = NaN,而 NaN 一路传到 `expressionManager.setValue("aa", NaN)` ——
     * **她的嘴会整个消失**(既不像张嘴也不像闭嘴,是那组网格塌掉)。
     * 症状看起来像「模型加载失败」,极难查。
     */
    @Test
    fun `RMS_空块是零_绝不是 NaN`() {
        val r = VoiceMath.rms(FloatArray(0))
        assertEquals(0f, r, 0f)
        assertFalse("NaN 会让她的嘴整个消失", r.isNaN())
    }

    /** 恒定振幅的 RMS 就是它自己(±,因为平方)。 */
    @Test
    fun `RMS_恒定振幅`() {
        assertEquals(1f, VoiceMath.rms(FloatArray(64) { 1f }), 1e-5f)
        assertEquals(0.5f, VoiceMath.rms(FloatArray(64) { 0.5f }), 1e-5f)
        assertEquals(0.5f, VoiceMath.rms(FloatArray(64) { -0.5f }), 1e-5f)
        assertEquals(0f, VoiceMath.rms(FloatArray(64) { 0f }), 0f)
    }

    /** 静音 = 闭嘴。 */
    @Test
    fun `嘴_静音就是闭上`() {
        assertEquals(0f, VoiceMath.mouthLevel(0f), 0f)
        assertEquals("负的 RMS 不该出现,但真来了也不能张嘴", 0f, VoiceMath.mouthLevel(-1f), 0f)
    }

    /** 到参考电平就算张满,再响也不超过 1。 */
    @Test
    fun `嘴_到参考电平张满_再响也不超过一`() {
        assertEquals(1f, VoiceMath.mouthLevel(VoiceMath.REFERENCE), 1e-4f)
        assertEquals(1f, VoiceMath.mouthLevel(VoiceMath.REFERENCE * 4f), 1e-4f)
    }

    /**
     * ★★ 这条钉的是**开根号**那一步,也是我第一版写错的地方。
     *
     * 正常说话 RMS 约 0.02~0.05。若用线性 `rms / REFERENCE`,0.0225 只给 0.15 的开口度 ——
     * **看着就是「她嘴几乎不动」**。开根号把它抬到 0.39,才对得上人眼。
     * 这条测试就是那句话的凭证:不许有人「顺手简化」成线性。
     */
    @Test
    fun `嘴_低电平要被抬起来_不是线性的`() {
        val quiet = 0.0225f                       // 正常说话的低端
        val got = VoiceMath.mouthLevel(quiet)
        val linear = quiet / VoiceMath.REFERENCE  // = 0.15
        assertTrue("开根号后必须明显高于线性映射(实得 $got,线性 $linear)", got > linear * 2f)
        assertEquals(0.387f, got, 0.01f)
    }

    /** 单调:越响嘴张得越大。**喇叭/嘴型不该有反直觉的区间。** */
    @Test
    fun `嘴_单调不减`() {
        var prev = -1f
        var r = 0f
        while (r <= VoiceMath.REFERENCE) {
            val m = VoiceMath.mouthLevel(r)
            assertTrue("RMS $r 处非单调($m < $prev)", m >= prev)
            assertTrue("开口度必须在 0..1 之内,实得 $m", m in 0f..1f)
            prev = m
            r += 0.005f
        }
    }

    // ------------------------------------------------------ 804 个嗓子里挑一个

    /**
     * ★ 这一组防的是「她突然不说话了」和「她整个进程没了」,
     *   而这两件事**都不抛异常** —— 所以判据必须钉在这儿。
     */
    @Test
    fun `音色号越界要夹回可用范围`() {
        assertEquals(803, VoiceMath.clampSpeaker(804, 804))
        assertEquals(803, VoiceMath.clampSpeaker(99999, 804))
        assertEquals(0, VoiceMath.clampSpeaker(-1, 804))
        assertEquals(400, VoiceMath.clampSpeaker(400, 804))
    }

    @Test
    fun `模型一个音色都没有时回零 不是随便挑`() {
        // 单音色模型,或者引擎还没就绪 —— 这时候只有「默认那个」这一个答案
        assertEquals(0, VoiceMath.clampSpeaker(7, 0))
        assertEquals(0, VoiceMath.clampSpeaker(7, -3))
        assertEquals(0, VoiceMath.clampSpeaker(0, 1))
    }

    @Test
    fun `★ 从零按上一个 得到的是最后一个 不是负一`() {
        // Kotlin 里 `0 % 804` 是 0、`-1 % 804` 是 **-1** —— 直接返 -1 就是个越界号,
        // 而它会一路走到 native 调用。这条就是为这个负数钉的。
        assertEquals(803, VoiceMath.stepSpeaker(0, 804, -1))
        assertEquals(1, VoiceMath.stepSpeaker(0, 804, 1))
    }

    @Test
    fun `试听是绕圈的 走到头弹回开头`() {
        // ★ 故意和「夹住」不一样:804 个要一个个试,
        //   停在 803 不动会让他以为按钮坏了 —— 而它确实**没反应**。
        assertEquals(0, VoiceMath.stepSpeaker(803, 804, 1))
        assertEquals(803, VoiceMath.stepSpeaker(0, 804, -1))
    }

    @Test
    fun `乱来的输入不许炸也不许吐出越界的号`() {
        // 这些数可能来自手改的 prefs / 换了个模型 / 上个版本存下的值
        for (sid in listOf(-9999, -1, 0, 803, 804, 99999)) {
            for (count in listOf(1, 2, 804)) {
                for (d in listOf(-3, -1, 0, 1, 3)) {
                    val r = VoiceMath.stepSpeaker(sid, count, d)
                    assertTrue("stepSpeaker($sid,$count,$d) = $r 越界", r in 0 until count)
                }
                assertTrue(VoiceMath.clampSpeaker(sid, count) in 0 until count)
            }
        }
    }

    @Test
    fun `步进零次等于没动`() {
        assertEquals(400, VoiceMath.stepSpeaker(400, 804, 0))
    }

}
