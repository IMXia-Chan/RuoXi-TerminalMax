package com.example.touchpad

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * 把用户选的那张照片**缩到能喂给视觉模型的大小**。
 *
 * ## 为什么必须先缩
 *
 * 一台手机的相机是 4000×3000。原图直接 base64 是十几 MB、而且是几百个视觉 token 的
 * 面积 —— 在这台机上「眼」算一张 1920×1080 已经要 3~4 分钟（见 `VISION_TIMEOUT_MS`），
 * 原图会让它跑到超时为止，看起来就是「她说看不了」。
 * 缩到长边 [MAX_SIDE] 一般不影响「图里有什么」这类问题，速度却差一个数量级。
 *
 * ## 为什么分两步缩
 *
 * `inSampleSize` 只能按 2 的幂降（解码器就是这样实现的），得到的结果**不一定是**
 * 我们要的那个尺寸。所以先按 2 的幂粗缩、再把长边精确缩到上限 —— 两步都不多花内存。
 */
object ImagePrep {

    /** 长边上限。1024 是「看得清内容」和「算得动」之间的折中。 */
    const val MAX_SIDE = 1024

    /** JPEG 质量。85 对视觉模型足够，压完一般 100~200 KB。 */
    private const val QUALITY = 85

    fun load(ctx: Context, uri: Uri, maxSide: Int = MAX_SIDE): ByteArray? {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = ImageMath.sampleSizeFor(bounds.outWidth, bounds.outHeight, maxSide)
        }
        val raw = runCatching {
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull() ?: return null

        val bmp = try {
            val long = maxOf(raw.width, raw.height)
            if (long <= maxSide) raw else {
                val scale = maxSide.toFloat() / long
                val w = (raw.width * scale).toInt().coerceAtLeast(1)
                val h = (raw.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(raw, w, h, true).also { if (it !== raw) raw.recycle() }
            }
        } catch (_: Throwable) {
            raw
        }

        return try {
            ByteArrayOutputStream().use { bos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, bos)
                bos.toByteArray()
            }
        } finally {
            runCatching { bmp.recycle() }
        }
    }
}

/** [ImagePrep] 的纯算术：那个 2 的幂该取多少。纯 JVM 可测。 */
internal object ImageMath {

    /**
     * 为了让**长边 ≤ max**，`inSampleSize` 取多少。
     *
     * ★ 返回值一定是 2 的幂：`inSampleSize` 不是 2 的幂时，解码器会**悄悄**取最近的
     *   那个来用 —— 于是「我按 3 算的，拿到的是 4」，尺寸和预期对不上，而代码里
     *   看不出哪里错。
     * ★ 结果比 max 略小是正常的（2 的幂只能一档一档降）。
     */
    fun sampleSizeFor(w: Int, h: Int, max: Int): Int {
        if (w <= 0 || h <= 0) return 1
        val long = maxOf(w, h)
        if (max <= 0 || long <= max) return 1
        var s = 1
        while (long / s > max) s *= 2
        return s
    }
}
