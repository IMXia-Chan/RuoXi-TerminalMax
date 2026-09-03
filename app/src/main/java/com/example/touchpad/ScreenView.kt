package com.example.touchpad

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * 电脑屏幕镜像视图:把电脑推过来的 JPEG 帧画出来,并把手机上的触摸映射成「电脑屏幕的绝对坐标」。
 *
 * 手势:
 *  - 单指轻点       -> 移动光标到该点并左键单击
 *  - 快速连点两下    -> 左键双击(打开桌面图标/程序)
 *  - 双击后按住拖动   -> 按住左键拖动(拖文件/框选)
 *  - 单指轻移       -> 只移动光标(悬停)
 *  - 单指长按       -> 右键点击
 *  - 双指张开/并拢   -> 放大 / 缩小手机端画面(缩放后保持,不会自动回到原比例)
 *  - 双指并排上下滑  -> 滚轮滚动(手指上滑=往下翻页,下滑=往上翻)
 *  (三指是手机系统「截屏」手势,App 收不到,故滚动改用双指并排滑动)
 */
class ScreenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 回调:绝对坐标 + 按键位(0=无按键,其余同 TouchpadView.BUTTON_*) */
    var onAbs: ((x: Int, y: Int, buttons: Int) -> Unit)? = null

    /** 回调:滚轮刻度(正=向上滚,负=向下滚,一个刻度=一格)。 */
    var onWheel: ((Int) -> Unit)? = null

    private var bitmap: Bitmap? = null
    private var screenW = 0
    private var screenH = 0

    // 屏幕帧解码放后台线程,避免和预览一起挤在主线程造成延迟
    private val decodeThread = HandlerThread("screen-decode").apply { start() }
    private val decodeHandler = Handler(decodeThread.looper)

    // 画面变换:bitmap 左上角在视图里的位置 + 缩放倍率
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var userAdjusted = false  // 用户手动缩放过:不再自动适应/回到原比例

    // ---- 手势状态 ----
    private var mode = MODE_IDLE
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = 0f
    private var dragging = false
    private var lastSx = 0
    private var lastSy = 0
    private var pendingDrag = false
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var cursorX = -1   // 鼠标光标位置(电脑屏幕坐标),-1=未初始化
    private var cursorY = -1
    private var longPressed = false
    private val longPressRunnable = Runnable {
        // 按住不动超时 -> 右键点击(模拟电脑鼠标右键)
        if (mode == MODE_SINGLE && !dragging && !longPressed && moved < LONG_PRESS_MOVE_MAX) {
            longPressed = true
            onAbs?.invoke(lastSx, lastSy, BUTTON_RIGHT)
            postDelayed({ onAbs?.invoke(lastSx, lastSy, 0) }, CLICK_RELEASE_MS)
        }
    }

    // 双指缩放锚点
    private var startDist = 0f
    private var startScale = 1f
    private var startFocalX = 0f
    private var startFocalY = 0f
    private var startOffsetX = 0f
    private var startOffsetY = 0f

    // 双指手势子状态:张开/并拢=缩放,并排上下滑=滚动
    private var twoFingerMode = GESTURE_UNDECIDED  // 0=未定 1=缩放 2=滚动
    private var startMidY = 0f        // 双指落下的中点 Y(判定 + 滚动累计用)
    private var scrollNotchesSent = 0 // 本次双指滚动已发出的滚轮刻度数

    private var ignoreTapUntil = 0L   // 多指结束瞬间的抬指不当作单击

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
    }

    // 鼠标光标画笔:白底黑边箭头
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val cursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    /** 收到一帧(JPEG 字节 + 电脑屏幕原始宽高)。解码放后台线程,主线程只负责赋值+重绘。 */
    fun setFrame(jpeg: ByteArray, w: Int, h: Int) {
        decodeHandler.post {
            val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@post
            post {
                bitmap?.recycle()
                bitmap = decoded
                if (w != screenW || h != screenH) {
                    screenW = w
                    screenH = h
                    // 屏幕分辨率变了才重新适应;同分辨率的新帧不改用户缩放
                    userAdjusted = false
                    refit()
                }
                if (!userAdjusted) refit()
                if (cursorX < 0) { cursorX = screenW / 2; cursorY = screenH / 2 }
                invalidate()
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!userAdjusted) refit()
    }

    /** 断开时清屏,避免残留最后一帧卡在画面上。 */
    fun clear() {
        post {
            bitmap?.recycle()
            bitmap = null
            screenW = 0
            screenH = 0
            invalidate()
        }
    }

    /**
     * 电脑回传的真实光标位置(流式坐标):手指没按时箭头跟着实体鼠标走,怎么挪都对得上。
     * 手指按着时以本地即时坐标为准,避免回传的 ~80ms 延迟让箭头往回跳。
     */
    fun setRemoteCursor(x: Int, y: Int) {
        if (mode != MODE_IDLE) return
        if (screenW <= 0 || screenH <= 0) return
        cursorX = x.coerceIn(0, screenW - 1)
        cursorY = y.coerceIn(0, screenH - 1)
        invalidate()
    }

    /** 让整个电脑屏幕完整显示在视图内(等比缩小,不足的边留黑边,不裁任何内容)。 */
    private fun refit() {
        if (width <= 0 || height <= 0 || screenW <= 0 || screenH <= 0) return
        scale = min(width.toFloat() / screenW, height.toFloat() / screenH)
        offsetX = (width - screenW * scale) / 2f
        offsetY = (height - screenH * scale) / 2f
    }

    /** 把视图坐标映射成电脑屏幕坐标(夹到 [0, screenW-1] × [0, screenH-1])。 */
    private fun toScreen(vx: Float, vy: Float): Pair<Int, Int> {
        if (screenW <= 0 || screenH <= 0) return 0 to 0
        val sx = ((vx - offsetX) / scale).toInt().coerceIn(0, screenW - 1)
        val sy = ((vy - offsetY) / scale).toInt().coerceIn(0, screenH - 1)
        return sx to sy
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap
        if (bmp == null) {
            canvas.drawText(
                "先点「扫描」连上电脑,这里会显示电脑画面",
                width / 2f,
                height / 2f,
                hintPaint
            )
            return
        }
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        canvas.drawBitmap(bmp, 0f, 0f, null)
        canvas.restore()
        // 叠加鼠标光标(白底黑边箭头),光标位置 = 手指映射的电脑屏幕坐标
        if (cursorX >= 0 && screenW > 0) {
            drawCursor(canvas, offsetX + cursorX * scale, offsetY + cursorY * scale)
        }
    }

    /** 画一个类似系统鼠标的箭头,热点在左上顶点。 */
    private fun drawCursor(canvas: Canvas, vx: Float, vy: Float) {
        val p = Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, 14f)
            lineTo(5f, 9.5f)
            lineTo(8.5f, 12f)
            lineTo(10f, 10.5f)
            lineTo(6.5f, 8f)
            lineTo(9.5f, 5.5f)
            lineTo(9.5f, 3f)
            close()
        }
        canvas.save()
        canvas.translate(vx, vy)
        canvas.drawPath(p, cursorFill)
        canvas.drawPath(p, cursorStroke)
        canvas.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = e.getX(0); val y = e.getY(0)
                mode = MODE_SINGLE
                downTime = e.eventTime
                downX = x; downY = y
                lastX = x; lastY = y
                moved = 0f
                dragging = false
                longPressed = false
                postDelayed(longPressRunnable, LONG_PRESS_MS)
                pendingDrag = e.eventTime - lastTapTime < DOUBLE_TAP_TIMEOUT &&
                    hypot(x - lastTapX, y - lastTapY) < DOUBLETAP_MAX_DIST
                val (sx, sy) = toScreen(x, y)
                lastSx = sx; lastSy = sy
                cursorX = sx; cursorY = sy
                invalidate()
                onAbs?.invoke(sx, sy, 0)  // 先把光标移到手指处,不按键
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val n = e.pointerCount
                dragging = false
                pendingDrag = false
                longPressed = false
                removeCallbacks(longPressRunnable)
                if (n >= 2) {
                    // 双指:张开/并拢=缩放,并排上下滑=滚动(具体是哪个,推迟到首次移动再判定)
                    mode = MODE_TWO
                    twoFingerMode = GESTURE_UNDECIDED
                    userAdjusted = true
                    startDist = fingerDist(e)
                    startScale = scale
                    startFocalX = midX(e)
                    startFocalY = midY(e)
                    startOffsetX = offsetX
                    startOffsetY = offsetY
                    startMidY = midY(e)
                    scrollNotchesSent = 0
                }
            }

            MotionEvent.ACTION_MOVE -> when (mode) {
                MODE_SINGLE -> {
                    val x = e.getX(0); val y = e.getY(0)
                    moved += abs(x - lastX) + abs(y - lastY)
                    lastX = x; lastY = y
                    val (sx, sy) = toScreen(x, y)
                    lastSx = sx; lastSy = sy
                    cursorX = sx; cursorY = sy
                    invalidate()
                    when {
                        pendingDrag && !dragging && moved > DRAG_THRESHOLD -> {
                            dragging = true
                            onAbs?.invoke(sx, sy, BUTTON_LEFT)  // 开始拖动
                        }
                        dragging -> onAbs?.invoke(sx, sy, BUTTON_LEFT)
                        else -> onAbs?.invoke(sx, sy, 0)  // 轻移:只移动光标
                    }
                }

                MODE_TWO -> {
                    if (e.pointerCount >= 2) {
                        val nd = fingerDist(e)
                        val my = midY(e)
                        // 首次移动时判定:两指间距变化大 -> 缩放;中点纵向位移大 -> 滚动;之后锁定不再切换
                        if (twoFingerMode == GESTURE_UNDECIDED) {
                            val dDist = abs(nd - startDist)
                            val dMidY = abs(my - startMidY)
                            if (dDist > GESTURE_DECIDE_PX || dMidY > GESTURE_DECIDE_PX) {
                                twoFingerMode = if (dDist >= dMidY) GESTURE_ZOOM else GESTURE_SCROLL
                            }
                        }
                        when (twoFingerMode) {
                            GESTURE_ZOOM -> {
                                val fx = midX(e)
                                val ns = (startScale * nd / startDist).coerceIn(MIN_SCALE, MAX_SCALE)
                                // 保持「初始焦点」下的屏幕点始终跟随当前焦点 -> 缩放但尽量不漂移
                                val anchorSx = (startFocalX - startOffsetX) / startScale
                                val anchorSy = (startFocalY - startOffsetY) / startScale
                                scale = ns
                                offsetX = fx - anchorSx * ns
                                offsetY = my - anchorSy * ns
                                invalidate()
                            }
                            GESTURE_SCROLL -> {
                                val dY = my - startMidY
                                val notches = (dY / SCROLL_STEP_PX).toInt()
                                if (notches != scrollNotchesSent) {
                                    onWheel?.invoke(notches - scrollNotchesSent)
                                    scrollNotchesSent = notches
                                }
                            }
                        }
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 双指抬一根 -> 只剩一根:切回单指绝对控制;短期内不让抬指误判成单击
                if (mode == MODE_TWO && e.pointerCount - 1 == 1) {
                    val ri = firstRemainIndex(e)
                    lastX = e.getX(ri); lastY = e.getY(ri)
                    downX = lastX; downY = lastY
                    downTime = e.eventTime
                    moved = 0f
                    dragging = false
                    pendingDrag = false
                    longPressed = false
                    ignoreTapUntil = e.eventTime + AFTER_MULTI_IGNORE_MS
                    mode = MODE_SINGLE
                }
            }

            MotionEvent.ACTION_UP -> {
                if (mode == MODE_SINGLE) {
                    val x = e.getX(0); val y = e.getY(0)
                    val dur = e.eventTime - downTime
                    val dist = hypot(x - downX, y - downY)
                    val (sx, sy) = toScreen(x, y)
                    lastSx = sx; lastSy = sy
                    when {
                        dragging -> onAbs?.invoke(sx, sy, 0)  // 松开左键
                        longPressed -> Unit  // 长按已触发右键,不再补左键
                        e.eventTime < ignoreTapUntil -> Unit  // 多指刚结束的抬指,不算单击
                        dur < LONG_PRESS_MS && dist < TAP_MAX_DIST -> {
                            val isSecondTap = e.eventTime - lastTapTime < DOUBLE_TAP_TIMEOUT &&
                                hypot(x - lastTapX, y - lastTapY) < DOUBLETAP_MAX_DIST
                            if (isSecondTap) {
                                // 快速连点两下 -> 在同一坐标合成标准双击,保证开桌面图标/程序
                                fireDoubleClick(sx, sy)
                                lastTapTime = e.eventTime
                                lastTapX = x; lastTapY = y
                            } else {
                                // 单击左键:只要没触发长按右键、位移足够小,抬起即算点击
                                onAbs?.invoke(sx, sy, BUTTON_LEFT)
                                postDelayed({ onAbs?.invoke(sx, sy, 0) }, CLICK_RELEASE_MS)
                                lastTapTime = e.eventTime
                                lastTapX = x; lastTapY = y
                            }
                        }
                    }
                }
                mode = MODE_IDLE
                dragging = false
                pendingDrag = false
                longPressed = false
                removeCallbacks(longPressRunnable)
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) onAbs?.invoke(lastSx, lastSy, 0)  // 只松开,不移动
                mode = MODE_IDLE
                dragging = false
                pendingDrag = false
                longPressed = false
                removeCallbacks(longPressRunnable)
            }
        }
        return true
    }

    /** 在指定坐标发一次标准双击(按下-松开-按下-松开,约 220ms 内完成)。 */
    private fun fireDoubleClick(sx: Int, sy: Int) {
        onAbs?.invoke(sx, sy, BUTTON_LEFT)
        postDelayed({ onAbs?.invoke(sx, sy, 0) }, 60)
        postDelayed({ onAbs?.invoke(sx, sy, BUTTON_LEFT) }, 150)
        postDelayed({ onAbs?.invoke(sx, sy, 0) }, 210)
    }

    private fun firstRemainIndex(e: MotionEvent): Int {
        return (0 until e.pointerCount).firstOrNull { it != e.actionIndex } ?: 0
    }

    private fun fingerDist(e: MotionEvent): Float =
        hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))

    private fun midX(e: MotionEvent): Float = (e.getX(0) + e.getX(1)) / 2f

    private fun midY(e: MotionEvent): Float = (e.getY(0) + e.getY(1)) / 2f

    companion object {
        const val BUTTON_LEFT = 0x01
        const val BUTTON_RIGHT = 0x02

        private const val MODE_IDLE = 0
        private const val MODE_SINGLE = 1
        private const val MODE_TWO = 2

        private const val GESTURE_UNDECIDED = 0
        private const val GESTURE_ZOOM = 1
        private const val GESTURE_SCROLL = 2

        private const val TAP_MAX_DIST = 24f       // px
        private const val DOUBLE_TAP_TIMEOUT = 300L
        private const val DOUBLETAP_MAX_DIST = 60f     // px
        private const val DRAG_THRESHOLD = 12f     // px
        private const val CLICK_RELEASE_MS = 40L
        private const val LONG_PRESS_MS = 500L
        private const val LONG_PRESS_MOVE_MAX = 20f    // px,长按期间允许的轻微移动
        private const val AFTER_MULTI_IGNORE_MS = 400L
        private const val SCROLL_STEP_PX = 42f     // 双指每纵向滑动这么多像素,滚一格
        private const val GESTURE_DECIDE_PX = 18f  // 双指位移超过这个阈值才判定是缩放还是滚动
        private const val MIN_SCALE = 0.2f
        private const val MAX_SCALE = 8.0f
    }
}
