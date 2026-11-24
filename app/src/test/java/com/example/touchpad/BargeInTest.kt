package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「他插话了没有」的判定 —— 纯 JVM。
 *
 * 为什么值得钉:这里判错**不崩、不报错**,只有两种样子:
 * · 判太松 —— 她被自己的尾音打断,每句都说半截,看着像她坏了;
 * · 判太紧 —— 他喊破嗓子也没反应,看着像麦克风坏了。
 * 两种都只有把手机拿在手上才看得出来,而那时已经很难查了。所以把算术单独钉住。
 */
class BargeInTest {

    /** 造一段固定振幅的 PCM。振幅按 0~1 给,内部换成 16 位的整数。 */
    private fun pcm(amp: Double, n: Int = 320): ShortArray =
        ShortArray(n) { (amp * 32767).toInt().toShort() }

    // ── 电平 ──

    @Test
    fun `全静音是零,满幅接近一`() {
        assertEquals(0f, BargeInMath.rms(pcm(0.0)), 1e-6f)
        assertEquals(1f, BargeInMath.rms(pcm(1.0)), 1e-3f)
    }

    @Test
    fun `只算进来的那一段`() {
        // 后半截塞满幅,按 160 个采样算的话应该还是零。
        val buf = ShortArray(320)
        for (i in 160 until 320) buf[i] = 32767
        assertEquals(0f, BargeInMath.rms(buf, 160), 1e-6f)
        assertTrue(BargeInMath.rms(buf, 320) > 0.7f)
    }

    @Test
    fun `长度给出零或负数时不算除零`() {
        assertEquals(0f, BargeInMath.rms(pcm(0.9), 0), 1e-6f)
        assertEquals(0f, BargeInMath.rms(pcm(0.9), -5), 1e-6f)
    }

    // ── 阈值 ──

    @Test
    fun `阈值是基准的两倍`() {
        assertEquals(0.2f, BargeInMath.threshold(0.1f), 1e-6f)
    }

    @Test
    fun `基准太小或太大时被上下夹住`() {
        // 太小:空调、风扇的低频底噪不该触发。
        assertEquals(0.03f, BargeInMath.threshold(0f), 1e-6f)
        assertEquals(0.03f, BargeInMath.threshold(0.01f), 1e-6f)
        // 太大:她的声音再大,阈值也不能高到「喊破嗓子也没反应」。
        assertEquals(0.45f, BargeInMath.threshold(0.9f), 1e-6f)
    }

    @Test
    fun `基准取中位数 —— 单帧的爆音带不动它`() {
        // 她说话时量到的五帧里混进一个关门声。
        assertEquals(0.05f, BargeInMath.median(listOf(0.04f, 0.05f, 0.06f, 0.9f, 0.05f)), 1e-6f)
        assertEquals(0f, BargeInMath.median(emptyList()), 1e-6f)
    }

    // ── 连续几帧才算 ──

    @Test
    fun `连续过线才认,并报出这一串的第一帧`() {
        val levels = listOf(0.1f, 0.5f, 0.5f, 0.5f)
        assertEquals(1, BargeInMath.firstHit(levels, 0.2f, 3))
    }

    @Test
    fun `散着的尖峰不算 —— 关门、碗掉地上不该打断她`() {
        val levels = listOf(0.5f, 0.1f, 0.5f, 0.1f, 0.5f, 0.1f)
        assertNull(BargeInMath.firstHit(levels, 0.2f, 3))
    }

    @Test
    fun `刚好等于阈值算过线`() {
        assertEquals(0, BargeInMath.firstHit(listOf(0.2f, 0.2f, 0.2f), 0.2f, 3))
    }

    @Test
    fun `要的帧数不合法时一律不认`() {
        assertNull(BargeInMath.firstHit(listOf(0.9f, 0.9f), 0.1f, 0))
        assertNull(BargeInMath.firstHit(emptyList(), 0.1f, 3))
    }

    @Test
    fun `不够长的串不认`() {
        assertNull(BargeInMath.firstHit(listOf(0.5f, 0.5f), 0.2f, 3))
    }

    // ── 端到端:把她自己的回声和他的人声分开 ──

    @Test
    fun `外放回来的她自己的声音不触发,他压低声音说话触发`() {
        // 她在念,外放绕回来的电平;阈值就是按这个量出来的。
        val echo = pcm(0.05)
        val floor = BargeInMath.threshold(BargeInMath.median(listOf(BargeInMath.rms(echo))))
        assertNull(BargeInMath.firstHit(List(20) { BargeInMath.rms(echo) }, floor, 8))

        // 他正常说话的电平,还是同一个阈值。
        val his = BargeInMath.rms(pcm(0.3))
        assertEquals(0, BargeInMath.firstHit(List(8) { his }, floor, 8))
    }
}
