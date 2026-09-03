package com.example.touchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 自定义触控板视图:把多点触摸手势翻译成鼠标事件。
 *
 * 手势:
 *  - 单指滑动           -> 移动光标
 *  - 单指轻点           -> 左键单击
 *  - 单指按住后移动      -> 按住左键拖动
 *  - 双指上下滑动        -> 滚轮
 *  - 双指轻点           -> 右键单击
 */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 回调参数依次为:相对 X、相对 Y、滚轮、按键位(见 BUTTON_*) */
    var onMouse: ((dx: Int, dy: Int, wheel: Int, buttons: Int) -> Unit)? = null

    /** 移动灵敏度,由灵敏度滑条设置(0.5 ~ 3.0) */
    var sensitivity = 1.5f
    var scrollSensitivity = 1.0f

    // ---- 手势状态 ----
    private var mode = MODE_IDLE
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastCentroidY = 0f
    private var moved = 0f
    private var dragging = false
    private var twoFingerStartTime = 0L
    private var twoFingerTravel = 0f
    private var suppressUp = false
    private var pendingDrag = false
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val lines = listOf(
            "单指滑动 → 移动光标",
            "单指轻点 → 左键",
            "双击后按住 → 拖拽",
            "双指滑动 → 滚轮",
            "双指轻点 → 右键"
        )
        val lineHeight = hintPaint.textSize * 1.7f
        val totalHeight = lineHeight * lines.size
        var y = (height - totalHeight) / 2f + hintPaint.textSize
        for (line in lines) {
            canvas.drawText(line, width / 2f, y, hintPaint)
            y += lineHeight
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = e.getX(0); val y = e.getY(0)
                mode = MODE_MOVE
                downTime = e.eventTime
                downX = x; downY = y
                lastX = x; lastY = y
                moved = 0f
                dragging = false
                suppressUp = false
                lastCentroidY = y
                // 双击第二下:两次轻点时间/位置都近 -> 待拖动
                pendingDrag = e.eventTime - lastTapTime < DOUBLE_TAP_TIMEOUT &&
                    hypot(x - lastTapX, y - lastTapY) < DOUBLETAP_MAX_DIST
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount >= 2) {
                    mode = MODE_SCROLL
                    dragging = false
                    pendingDrag = false
                    twoFingerStartTime = e.eventTime
                    twoFingerTravel = 0f
                    lastCentroidY = centroidY(e)
                }
            }

            MotionEvent.ACTION_MOVE -> when (mode) {
                MODE_MOVE -> {
                    val x = e.getX(0); val y = e.getY(0)
                    val dx = x - lastX
                    val dy = y - lastY
                    moved += abs(dx) + abs(dy)
                    // 双击第二下按住后移动 -> 进入拖动(按下左键);普通滑动只移光标
                    if (pendingDrag && !dragging && moved > DRAG_THRESHOLD) {
                        dragging = true
                        onMouse?.invoke(0, 0, 0, BUTTON_LEFT)
                    }
                    val buttons = if (dragging) BUTTON_LEFT else 0
                    val sx = (dx * sensitivity).toInt()
                    val sy = (dy * sensitivity).toInt()
                    if (sx != 0 || sy != 0) onMouse?.invoke(sx, sy, 0, buttons)
                    lastX = x; lastY = y
                }

                MODE_SCROLL -> {
                    val cy = centroidY(e)
                    val wheel = ((lastCentroidY - cy) * scrollSensitivity).toInt()
                    if (wheel != 0) {
                        twoFingerTravel += abs(wheel)
                        onMouse?.invoke(0, 0, wheel, 0)
                    }
                    lastCentroidY = cy
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == MODE_SCROLL) {
                    // 双指之一抬起:判断是否双指轻点(右键)
                    val dur = e.eventTime - twoFingerStartTime
                    if (dur < TAP_TIMEOUT_MS && twoFingerTravel < TAP_MAX_DIST) {
                        click(BUTTON_RIGHT)
                        suppressUp = true
                    }
                    // 剩余那根手指切回单指移动模式
                    val remain = if (e.actionIndex == 0) 1 else 0
                    lastX = e.getX(remain); lastY = e.getY(remain)
                    downX = lastX; downY = lastY
                    downTime = e.eventTime
                    moved = 0f
                    dragging = false
                    pendingDrag = false
                    lastCentroidY = lastY
                    mode = MODE_MOVE
                }
            }

            MotionEvent.ACTION_UP -> {
                if (suppressUp) {
                    suppressUp = false
                } else {
                    val x = e.getX(0); val y = e.getY(0)
                    val dur = e.eventTime - downTime
                    val dist = hypot(x - downX, y - downY)
                    when (mode) {
                        MODE_MOVE -> when {
                            dragging ->
                                onMouse?.invoke(0, 0, 0, 0) // 释放左键
                            dur < TAP_TIMEOUT_MS && dist < TAP_MAX_DIST -> {
                                click(BUTTON_LEFT)
                                // 记录本次轻点;若紧接着再来一次即构成双击
                                lastTapTime = e.eventTime
                                lastTapX = x; lastTapY = y
                            }
                        }
                        MODE_SCROLL ->
                            if (dur < TAP_TIMEOUT_MS && twoFingerTravel < TAP_MAX_DIST) {
                                click(BUTTON_RIGHT)
                            }
                    }
                }
                mode = MODE_IDLE
                dragging = false
                pendingDrag = false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) onMouse?.invoke(0, 0, 0, 0)
                mode = MODE_IDLE
                dragging = false
                suppressUp = false
                pendingDrag = false
            }
        }
        return true
    }

    /** 模拟一次按键:按下后短暂延时释放。 */
    private fun click(button: Int) {
        onMouse?.invoke(0, 0, 0, button)
        postDelayed({ onMouse?.invoke(0, 0, 0, 0) }, CLICK_RELEASE_MS)
    }

    private fun centroidY(e: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until e.pointerCount) sum += e.getY(i)
        return sum / e.pointerCount
    }

    companion object {
        // 按键位(与电脑端一致)
        const val BUTTON_LEFT = 0x01
        const val BUTTON_RIGHT = 0x02
        const val BUTTON_MIDDLE = 0x04

        private const val MODE_IDLE = 0
        private const val MODE_MOVE = 1
        private const val MODE_SCROLL = 2

        private const val TAP_TIMEOUT_MS = 220L
        private const val TAP_MAX_DIST = 24f     // px
        private const val DOUBLE_TAP_TIMEOUT = 300L
        private const val DOUBLETAP_MAX_DIST = 60f   // px
        private const val DRAG_THRESHOLD = 8f
        private const val CLICK_RELEASE_MS = 40L
    }
}
