package com.example.touchpad

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat

/**
 * 若息·Terminal Max —— Android 开屏动画画布(两幕)。
 *
 * 【第一幕 RuoXi 品牌】纯黑底,"RuoXi" 大字渐入→保持→渐出(约 1.5s)。
 * 【第二幕 XChan OS】现有的光带动画:
 * 一根暗蓝光带(宽=屏宽 1/3,水平渐变 #0A1628→#1E3A5F→#0A1628,垂直上下各超出文字区 50dp,
 * 边缘 20dp 高斯羽化 BlurMaskFilter)1500ms 从左外线性滑到右外;实时求它与文字的水平重叠,
 * 扫过时 "XChan OS" 由 alpha 0 点亮到 255;光带盖满文字的瞬间,字旁还会叠一圈白/银
 * (#EDF2F9,半径 18dp)光影 —— "光晕过去"那一下的白银色亮边,约 380ms 褪去;底色再配
 * #4A90D9 半径 8dp 冰蓝辉光,光带完全通过后辉光 400ms 消退、文字保持纯白,再整屏淡出。
 *
 * 纯 ValueAnimator + Canvas,逐帧 invalidate —— ValueAnimator 由 Choreographer 按帧驱动,
 * 每帧一 invalidate 即稳定 60fps,无任何第三方依赖。
 *
 * 时间轴(ms):0→1500 RuoXi 品牌 / 1500→3000 光带扫过点亮、辉光消退 /
 *             3000→4100 文字保持纯白 / 4100→4500 整体淡出 / 4500 结束。总长约 4.5s。
 */
class SplashView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    // ---- 第一幕:RuoXi 品牌 ----
    private val BRAND_MS = 1500f

    // ---- 第二幕 XChan OS 时间轴常量(相对本幕起点) ----
    private val BAND_MS = 1500f        // 光带滑扫
    private val GLOW_DECAY_MS = 400f   // 光带完全通过后冰蓝辉光消退用时
    private val GLINT_TAIL_MS = 380f   // 白/银色光影:盖满文字瞬间最强,之后 ~380ms 褪去
    private val HOLD_END_MS = 2600f    // 辉光退净后文字保持纯白(2600-1900=700ms)
    private val FADE_MS = 400f         // 整屏淡出
    private val X_MS = HOLD_END_MS + FADE_MS // 第二幕总长 3000
    private val TOTAL_MS = BRAND_MS + X_MS   // 4500

    // ---- 颜色:全部取自 colors.xml,不写死在代码里 ----
    private val bgColor = ContextCompat.getColor(context, R.color.splash_bg)
    private val bandColors = intArrayOf(
        ContextCompat.getColor(context, R.color.splash_band_dark),
        ContextCompat.getColor(context, R.color.splash_band_mid),
        ContextCompat.getColor(context, R.color.splash_band_dark),
    )
    private val glowColor = ContextCompat.getColor(context, R.color.splash_glow)    // 冰蓝 #4A90D9
    private val glintColor = ContextCompat.getColor(context, R.color.splash_glint) // 白/银 #EDF2F9
    private val textColor = ContextCompat.getColor(context, R.color.splash_text)

    // ---- 单位换算 ----
    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    // 第二幕 "XChan OS" 用字
    private val textSizePx = 32f * scaledDensity
    private val padY = 50f * density               // 光带上下各超出文字区 50dp
    private val blurRadiusPx = 20f * density       // 光带边缘高斯羽化半径 20dp
    private val glowRadiusPx = 8f * density        // 冰蓝辉光半径 8dp
    private val glintRadiusPx = 18f * density      // 白/银色光影半径(比冰蓝大一圈)

    // 第一幕 "RuoXi" 品牌用字:更大、带字距,更"logo"
    private val brandTextSizePx = 46f * scaledDensity

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = textColor
        textSize = textSizePx
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) // 字重 Medium(500)
    }
    // 银色光影层:和 textPaint 同字型同色,只是单独控制阴影,先垫在冰蓝辉光底下
    private val glintPaint = Paint(textPaint)
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(blurRadiusPx, BlurMaskFilter.Blur.NORMAL) // 高斯羽化
    }
    private val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = textColor
        textSize = brandTextSizePx
        letterSpacing = 0.14f // 拉开字距更像品牌字标
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val BRAND_TEXT = "RuoXi"
    private val TEXT = "XChan OS"

    init {
        // BlurMaskFilter(羽化)与 setShadowLayer 在硬件加速画布上不支持/被忽略 → 强制软件层
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    /** 第二幕文字被点亮(光带盖满)瞬间回调一次,用于同步音频。 */
    var onLit: (() -> Unit)? = null

    // ---- 运行状态 ----
    private var elapsed = 0f     // 已进行毫秒数(整体线性进度轴)
    private var lit = false      // 第二幕:光带已盖满文字 → 永久点亮
    private var litAtMs = -1f    // 盖满时刻(相对第二幕起点),银白光影从这里开始消退
    private var doneNotified = false
    private var animator: ValueAnimator? = null

    /** 是否已开播(动画与音效就绪不同步,防止被重复 start)。 */
    val animatorStarted: Boolean get() = animator != null

    /** 开始动画,约 4.5s 后回调 [onFinished](由调用方负责跳转 MainActivity)。 */
    fun start(onFinished: () -> Unit) {
        if (animator != null) return
        val a = ValueAnimator.ofFloat(0f, TOTAL_MS)
        a.duration = TOTAL_MS.toLong()
        a.interpolator = LinearInterpolator()
        a.addUpdateListener { u ->
            elapsed = u.animatedValue as Float
            invalidate()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (!doneNotified) {
                    doneNotified = true
                    onFinished()
                }
            }
        })
        animator = a
        a.start()
    }

    /** 提前退出(按返回/被回收)时停表。 */
    fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor) // 整屏纯黑兜底(主题 windowBackground 同色)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return // 尚未布局,下一帧再画

        val e = elapsed - BRAND_MS // 第二幕相对时间(负=还在第一幕)
        if (e < 0f) {
            drawBrand(canvas, w, h)
            return
        }
        drawXchan(canvas, w, h, e)
    }

    // ==================== 第一幕:RuoXi 品牌 ====================
    private fun drawBrand(canvas: Canvas, w: Float, h: Float) {
        // 0→450 渐入;450→1100 保持;1100→1500 渐出(黑场切第二幕)
        val a = when {
            elapsed < 450f -> elapsed / 450f
            elapsed < 1100f -> 1f
            else -> 1f - (elapsed - 1100f) / 400f
        }.coerceIn(0f, 1f)
        if (a <= 0f) return

        val cx = w / 2f
        // 让大字在视觉上真正居中(baseline 需按字体 box 修正)
        val fm = brandPaint.fontMetrics
        val cy = h / 2f - (fm.ascent + fm.descent) / 2f
        val alpha = (255f * a).toInt().coerceIn(0, 255)

        brandPaint.alpha = alpha
        // 保持期间带一圈很淡的白/银光,略有一点"贵气"
        if (a > 0.6f) {
            brandPaint.setShadowLayer(
                glowRadiusPx * 1.5f, 0f, 0f,
                Color.argb((90 * a).toInt(), Color.red(textColor), Color.green(textColor), Color.blue(textColor)),
            )
        } else {
            brandPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
        canvas.drawText(BRAND_TEXT, cx, cy, brandPaint)
        brandPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
    }

    // ==================== 第二幕:XChan OS 光带点亮 ====================
    private fun drawXchan(canvas: Canvas, w: Float, h: Float, e: Float) {
        val cx = w / 2f
        val cy = h / 2f

        // 文字尺寸/上下沿(首次测量一次,band 与文字重叠都以它为准)
        val textWidth = textPaint.measureText(TEXT)
        val fm = textPaint.fontMetrics
        val textTop = cy + fm.top   // ascent 为负 → 上沿在基线之上
        val textBottom = cy + fm.bottom
        val textLeft = cx - textWidth / 2f
        val textRight = cx + textWidth / 2f

        // ---- 光带几何:BAND_MS 内线性滑动,左缘 -bandW → w(右缘恰好到 w+bandW) ----
        val bandW = w / 3f
        val seg = (e / BAND_MS).coerceIn(0f, 1f)
        val bandLeft = lerp(-bandW, w, seg)
        val bandRight = bandLeft + bandW
        val bandTop = textTop - padY
        val bandBottom = textBottom + padY

        // ---- 文字点亮:光带与文字的水平重叠占比(垂直恒覆盖,只看水平) ----
        val ovW = (minOf(bandRight, textRight) - maxOf(bandLeft, textLeft)).coerceAtLeast(0f)
        val cover = if (textWidth > 0f) ovW / textWidth else 0f
        if (cover >= 1f) {
            if (!lit) {
                litAtMs = e                // 记下盖满时刻(相对本幕起点)
                lit = true                 // 盖满 → 永久点亮
                onLit?.invoke()            // 亮起瞬间通知外部(播音效),只触发一次
                onLit = null
            }
        }
        val bright = if (lit) 1f else cover.coerceIn(0f, 1f)

        // ---- 冰蓝辉光:点亮即全亮;光带完全通过后 GLOW_DECAY_MS 内线性消退 ----
        val glow = when {
            !lit -> 0f
            e >= BAND_MS + GLOW_DECAY_MS -> 0f
            e >= BAND_MS -> 1f - (e - BAND_MS) / GLOW_DECAY_MS
            else -> 1f
        }

        // ---- 白/银色光影:扫过期间随覆盖增强,盖满瞬间最亮,之后 GLINT_TAIL_MS 内褪去 ----
        val glint = if (!lit) cover.coerceIn(0f, 1f)
                    else (1f - (e - litAtMs) / GLINT_TAIL_MS).coerceIn(0f, 1f)

        // ---- 画光带(带高斯羽化;透明度随进入/离开 0↔0.8) ----
        val bandAlpha = bandAlphaOf(bandLeft, bandRight, w, bandW)
        if (bandAlpha > 0f) {
            bandPaint.shader = LinearGradient(
                bandLeft, 0f, bandRight, 0f,
                bandColors, null, Shader.TileMode.CLAMP,
            )
            bandPaint.alpha = (255f * bandAlpha).toInt().coerceIn(0, 255)
            canvas.drawRect(bandLeft, bandTop, bandRight, bandBottom, bandPaint)
        }

        val textAlpha = (255f * bright).toInt().coerceIn(0, 255)

        // ---- 银色光影层垫底:光晕扫过文字那一下,字旁亮一圈白/银(半径比冰蓝大) ----
        if (glint > 0f && textAlpha > 0) {
            glintPaint.alpha = textAlpha
            glintPaint.setShadowLayer(
                glintRadiusPx, 0f, 0f,
                Color.argb(
                    (255f * glint * 0.95f).toInt().coerceIn(0, 255),
                    Color.red(glintColor), Color.green(glintColor), Color.blue(glintColor),
                ),
            )
            canvas.drawText(TEXT, cx, cy, glintPaint)
            glintPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT) // 复原,防残留
        }

        // ---- 画文字字核(白字 + 冰蓝辉光),叠在银色光影上 ----
        textPaint.alpha = textAlpha
        if (glow > 0f) {
            textPaint.setShadowLayer(
                glowRadiusPx, 0f, 0f,
                Color.argb(
                    (255f * glow).toInt().coerceIn(0, 255),
                    Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor),
                ),
            )
        } else {
            textPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT) // 无辉光时不残留影
        }
        canvas.drawText(TEXT, cx, cy, textPaint)

        // ---- 本幕收尾:辉光退净、纯白保持至 HOLD_END_MS 后整屏淡出 ----
        if (e >= HOLD_END_MS) {
            alpha = 1f - ((e - HOLD_END_MS) / FADE_MS).coerceIn(0f, 1f)
        } else {
            alpha = 1f
        }
    }

    /** 光带不透明度:部分进入屏内 0→0.8,全在屏内 0.8,开始离开 0.8→0。 */
    private fun bandAlphaOf(left: Float, right: Float, w: Float, bandW: Float): Float {
        if (right <= 0f || left >= w) return 0f
        var visible = 1f
        if (left < 0f) visible = minOf(visible, right / bandW)        // 进入
        if (right > w) visible = minOf(visible, (w - left) / bandW)   // 离开
        return 0.8f * visible.coerceIn(0f, 1f)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
