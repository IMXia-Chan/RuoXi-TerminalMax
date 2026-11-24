package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 照片缩放的那点算术（[ImageMath.sampleSizeFor]）。
 *
 * ★ 这里错了**不报错**：拿到一张特别大的图，视觉模型算到超时为止，用户看到的
 *   只是「她说看不了」。或者反过来 —— 缩得太狠，图上一行字糊成一团，她照着猜。
 *   真正解码/压缩那一半要真机（`BitmapFactory` 在纯 JVM 上是空壳），只能靠它单测。
 */
class ImageMathTest {

    @Test
    fun `相机原图按四分之一缩下来`() {
        // 4000/4 = 1000 ≤ 1024；再上一档 2 只能降到 2000，还是超。
        assertEquals(4, ImageMath.sampleSizeFor(4000, 3000, 1024))
        assertEquals(4, ImageMath.sampleSizeFor(3000, 4000, 1024))
    }

    @Test
    fun `本来就够小的不缩`() {
        assertEquals(1, ImageMath.sampleSizeFor(800, 600, 1024))
        assertEquals(1, ImageMath.sampleSizeFor(1024, 768, 1024))
    }

    @Test
    fun `★ 结果永远是 2 的幂`() {
        // inSampleSize 不是 2 的幂时解码器会**悄悄**取邻近的那个来用 ——
        // 于是「我按 3 算的，拿到的是 4」，尺寸对不上而代码里看不出哪里错。
        val maxes = listOf(256, 512, 700, 1000, 1024, 1500)
        val sizes = listOf(100, 777, 1920, 2048, 3000, 4000, 8192, 12000)
        for (w in sizes) for (h in sizes) for (m in maxes) {
            val s = ImageMath.sampleSizeFor(w, h, m)
            assertTrue("$w×$h→$s 不是 2 的幂", s > 0 && Integer.bitCount(s) == 1)
        }
    }

    @Test
    fun `★ 缩完的长边不许超过上限`() {
        val maxes = listOf(256, 512, 1024)
        val sizes = listOf(100, 777, 1920, 2048, 3000, 4000, 8192, 12000)
        for (w in sizes) for (h in sizes) for (m in maxes) {
            val s = ImageMath.sampleSizeFor(w, h, m)
            val long = maxOf(w, h) / s
            assertTrue("$w×$h 按 $s 缩完还有 $long，超过上限 $m", long <= m)
        }
    }

    @Test
    fun `尺寸是 0 或者上限不合法时退回 1`() {
        // 读不到尺寸（坏图）时不能除零，也不能给个巨大的 sampleSize 把图缩成 1 像素。
        assertEquals(1, ImageMath.sampleSizeFor(0, 0, 1024))
        assertEquals(1, ImageMath.sampleSizeFor(-1, 500, 1024))
        assertEquals(1, ImageMath.sampleSizeFor(4000, 3000, 0))
        assertEquals(1, ImageMath.sampleSizeFor(4000, 3000, -5))
    }
}
