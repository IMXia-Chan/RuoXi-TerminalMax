package com.example.touchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * 「调度大屏」中间那张图：**以中枢为圆心的星形数据流**。
 *
 * 视觉语言取自他 2026-11-04 给的那张参考图（AGENT OPS / DISPATCH CENTER）：
 * 黑底、荧光绿、等宽字、外圈刻度、从中心发散出去的线和点。
 * 但他的屏是手机，所以只取**语法**，不搬密度 —— 外圈最多 6 个节点。
 *
 * ## ★ 它只画真事
 *
 * 每一个移动的点都对应 [AgentBus] 里一条事件。几何在 [FlowMath]，这里只负责画。
 * **没有事件时它一帧都不排**（[onDraw] 末尾那句判断），更不会自己放着好看的空转动画 ——
 * 他对着这张屏判断「刚刚到底做了什么」，图不能撒谎。
 *
 * ## 边是怎么定的
 *
 * | 事件 | 画成 |
 * |---|---|
 * | `from = self` | 她 → 中枢 |
 * | `from = hub`，`ask` / `route` | 中枢 → 云端（或某个角色） |
 * | `from = cloud` | 云端 → 中枢 |
 * | `from = hub`，`tool` / `note` | **中枢身上的一圈** —— 这一步没出门 |
 *
 * ★ 最后一圈是整张图上最该被看见的东西：它一眼说明「这件事留在了本机」。
 *
 * ## 监听者只有一份
 *
 * [AgentBus.listen] 是**一个**全局槽位（见那里的注释）。同一时刻只该有一个
 * [AgentFlowView] 活着 —— 面板是全屏覆盖层，本来也只有一个。
 * 如果以后要同时开两个，得先把 [AgentBus] 改成监听者集合，别在这里各自注册了事。
 *
 * ## 不在窗口上就摘掉监听、停帧
 *
 * 同 [VoiceWaveView]：`onDetachedFromWindow` 里 `running = false`。
 * 漏了这一条的代价是**退出界面之后还在每秒烧 60 帧**，而日志里一点痕迹都没有。
 */
internal class AgentFlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 界面最近看到的事件（新的在后）。只在主线程读写。 */
    private val sight = ArrayList<AgentEvent>()

    /** 有新事件（或刚载入）时叫一声。面板拿它刷新底下那条事件流。 */
    var onEvents: ((List<AgentEvent>) -> Unit)? = null

    private var attached = false

    private val listener: (AgentEvent) -> Unit = { e ->
        post {
            sight.add(e)
            while (sight.size > FlowMath.SHOW_EVENTS) sight.removeAt(0)
            onEvents?.invoke(sight)
            invalidate()
        }
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.6f)
        // ★ 压得很暗:格子是**底**,不是内容。亮一点就会抢走节点和粒子的注意力。
        color = 0xFF0D211D.toInt()
    }

    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0xFF1B3B35.toInt()
    }

    private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val ringStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = dp(10.5f)
        textAlign = Paint.Align.CENTER
    }

    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = dp(8.5f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        sight.clear()
        sight.addAll(AgentBus.recent(FlowMath.SHOW_EVENTS))
        onEvents?.invoke(sight)
        AgentBus.listen(listener)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        attached = false
        AgentBus.listen(null)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val now = System.currentTimeMillis()
        val roles = FlowMath.rolesOf(sight)
        val nodes = FlowMath.nodes(w, h, roles).associateBy { it.key }
        val c = FlowMath.center(w, h)
        val r = FlowMath.radii(w, h)

        drawGrid(canvas, w, h, c, r)

        // ── 静态边：每个外圈节点 → 圆心。先画，让节点压在线上。 ──
        for (n in nodes.values) {
            if (n.key == FlowMath.KEY_HUB) continue
            canvas.drawLine(n.x, n.y, c.x, c.y, edge)
        }

        // ── 每颗节点的热度：最近到达它的事件把它点亮 ──
        val heat = HashMap<String, Float>()
        for (e in sight) {
            val (_, to) = FlowMath.edgeOf(e, roles.toSet())
            val g = FlowMath.glowAt(e.at, now)
            if (g > (heat[to] ?: 0f)) heat[to] = g
        }

        for (n in nodes.values) {
            val hot = heat[n.key] ?: 0f
            if (n.key == FlowMath.KEY_HUB) drawHub(canvas, c, hot) else drawNode(canvas, n, c, hot)
        }

        // ── 粒子：只在路上那 0.9 秒里存在 ──
        for (e in sight) {
            if (!FlowMath.isTraveling(e.at, now)) continue
            val (a, b) = FlowMath.edgeOf(e, roles.toSet())
            val t = FlowMath.progress(e.at, now)
            val tone = FlowMath.toneOf(e.kind)
            if (a == b) {
                drawPulse(canvas, c, t, tone)          // 中枢自己身上的一圈
            } else {
                val from = nodes[a] ?: continue
                val to = nodes[b] ?: continue
                drawParticle(canvas, from, to, c, t, tone)
            }
        }

        drawLegend(canvas, w, h)
        if (sight.isEmpty()) drawIdle(canvas, w, h)

        // ★ 没有东西可动就**不排下一帧** —— 这是这张图唯一的耗电开关。
        val last = sight.lastOrNull()?.at ?: return
        if (attached && now - last <= FlowMath.TRAVEL_MS + FlowMath.GLOW_MS) {
            postInvalidateOnAnimation()
        }
    }

    // ------------------------------------------------------------------ 底层

    /** 方格。★ 间距是固定的 dp，不随屏幕缩放 —— 缩放会变成一张渐变的糊。 */
    private fun drawGrid(canvas: Canvas, w: Float, h: Float, c: FlowMath.Pt, r: FlowMath.Radii) {
        val step = dp(22f)
        var x = 0f
        while (x <= w) {
            canvas.drawLine(x, 0f, x, h, grid)
            x += step
        }
        var y = 0f
        while (y <= h) {
            canvas.drawLine(0f, y, w, y, grid)
            y += step
        }
        // 调度环：外圈一条细椭圆，再加一层更暗的内环。
        // ★ 参考图里那一圈是**刻度和环一起**的；这里只留环 ——
        //   椭圆很扁（横屏），沿边画的短针会散成一片噪点，不像刻度。
        ringStroke.color = 0xFF1E4A40.toInt()
        ringStroke.strokeWidth = dp(1f)
        canvas.drawOval(
            c.x - r.x, c.y - r.y, c.x + r.x, c.y + r.y, ringStroke
        )
        ringStroke.color = 0xFF122E29.toInt()
        canvas.drawOval(
            c.x - r.x * 0.72f, c.y - r.y * 0.72f, c.x + r.x * 0.72f, c.y + r.y * 0.72f, ringStroke
        )
        ringStroke.strokeWidth = dp(1.2f)
    }

    private fun drawNode(canvas: Canvas, n: FlowMath.Node, c: FlowMath.Pt, hot: Float) {
        val base = dp(8f)
        val rad = base + dp(2.5f) * hot
        ringFill.color = withAlpha(0xFF0B1512.toInt(), 255)
        canvas.drawCircle(n.x, n.y, rad, ringFill)
        ringStroke.color = withAlpha(0xFF35FFB0.toInt(), (90 + 165 * hot).toInt().coerceIn(0, 255))
        canvas.drawCircle(n.x, n.y, rad, ringStroke)
        if (hot > 0f) {
            ringStroke.color = withAlpha(0xFF35FFB0.toInt(), (60 * hot).toInt())
            canvas.drawCircle(n.x, n.y, rad + dp(6f) * (1f - hot) + dp(4f), ringStroke)
        }
        // 标签放在**背离圆心**那一侧，永远不会压到连线。
        val dx = n.x - c.x
        val dy = n.y - c.y
        val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
        val lx = n.x + dx / len * dp(17f)
        val ly = n.y + dy / len * dp(17f) + dp(4f)
        label.color = withAlpha(0xFFD9FFF1.toInt(), (150 + 105 * hot).toInt().coerceIn(0, 255))
        canvas.drawText(n.label, lx, ly, label)
    }

    private fun drawHub(canvas: Canvas, c: FlowMath.Pt, hot: Float) {
        ringFill.color = 0xFF071310.toInt()
        canvas.drawCircle(c.x, c.y, dp(20f), ringFill)
        ringStroke.color = withAlpha(0xFF35FFB0.toInt(), (110 + 145 * hot).toInt().coerceIn(0, 255))
        canvas.drawCircle(c.x, c.y, dp(20f), ringStroke)
        ringStroke.color = withAlpha(0xFF35FFB0.toInt(), (40 + 60 * hot).toInt())
        canvas.drawCircle(c.x, c.y, dp(27f), ringStroke)
        label.color = withAlpha(0xFFD9FFF1.toInt(), 235)
        label.textSize = dp(10.5f)
        canvas.drawText("中枢", c.x, c.y + dp(4f), label)
    }

    /** 沿 from→to 走的一个点，带三段拖尾。 */
    private fun drawParticle(
        canvas: Canvas,
        from: FlowMath.Node,
        to: FlowMath.Node,
        c: FlowMath.Pt,
        t: Float,
        tone: Int,
    ) {
        for (i in 0 until 4) {
            val tt = t - i * 0.055f
            if (tt < 0f) continue
            val p = lerpEdge(from, to, c, tt)
            val fade = 1f - i * 0.24f
            val rad = dp(3.4f) * (1f - i * 0.18f)
            dot.color = withAlpha(tone, (255 * fade).toInt().coerceIn(0, 255))
            canvas.drawCircle(p.x, p.y, rad, dot)
            if (i == 0) {
                // 外圈那一层淡光：霓虹管的标准画法（同 [VoiceWaveView] 的那三遍）。
                dot.color = withAlpha(tone, 48)
                canvas.drawCircle(p.x, p.y, rad * 3.1f, dot)
            }
        }
    }

    /** 「动手」那一步：中枢身上扩出去的一圈。它说的就是「没出门」。 */
    private fun drawPulse(canvas: Canvas, c: FlowMath.Pt, t: Float, tone: Int) {
        val rad = dp(20f) + dp(30f) * t
        ringStroke.color = withAlpha(tone, (200 * (1f - t)).toInt().coerceIn(0, 255))
        ringStroke.strokeWidth = dp(2.4f)
        canvas.drawCircle(c.x, c.y, rad, ringStroke)
        ringStroke.strokeWidth = dp(1.2f)
    }

    /**
     * 起点/终点都往圆心方向收一点，让点**从节点边上出发、在节点边上停**。
     * 不收的话，点会先钻进节点里再钻出来，看着像穿模。
     */
    private fun lerpEdge(a: FlowMath.Node, b: FlowMath.Node, c: FlowMath.Pt, t: Float): FlowMath.Pt {
        val ax = a.x + (c.x - a.x) * 0.11f
        val ay = a.y + (c.y - a.y) * 0.11f
        val bx = b.x + (c.x - b.x) * 0.11f
        val by = b.y + (c.y - b.y) * 0.11f
        return FlowMath.Pt(ax + (bx - ax) * t, ay + (by - ay) * t)
    }

    /** 「还没有第一条」不是错误，是事实。写一行状态，不写一段说明（说明在 ⚙ 里）。 */
    private fun drawIdle(canvas: Canvas, w: Float, h: Float) {
        small.color = 0xFF3E6B60.toInt()
        small.textAlign = Paint.Align.CENTER
        canvas.drawText("NO EVENTS · 等她跑第一件事", w / 2f, h - dp(10f), small)
        small.textAlign = Paint.Align.LEFT
    }

    /** 图例：四个色点 + 两个字。★ 它解的是这一屏，不是这个功能。 */
    private fun drawLegend(canvas: Canvas, w: Float, h: Float) {
        small.color = 0xFF6F8F88.toInt()
        var x = dp(12f)
        val y = dp(16f)
        for ((kind, cn) in FlowMath.LEGEND) {
            dot.color = FlowMath.toneOf(kind)
            canvas.drawCircle(x + dp(3f), y - dp(3f), dp(3f), dot)
            canvas.drawText(cn, x + dp(11f), y, small)
            x += dp(11f) + small.measureText(cn) + dp(14f)
        }
        small.color = 0xFF3E6B60.toInt()
        small.textAlign = Paint.Align.RIGHT
        canvas.drawText("${sight.size} EV", w - dp(12f), y, small)
        small.textAlign = Paint.Align.LEFT
    }

    private fun withAlpha(color: Int, a: Int): Int =
        (color and 0x00FFFFFF) or ((a.coerceIn(0, 255)) shl 24)
}
