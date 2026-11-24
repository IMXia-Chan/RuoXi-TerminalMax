package com.example.touchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AnimationUtils
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * 按住说话时,玻璃胶囊里那条**慢慢流动的光**。
 *
 * ★★ 它有**两副样子**,而这正是它存在的全部意义 —— 用户 2026-10-05 原话:
 * > 「我说的类似基因序列的,是**它识别到我讲话的时候才会**,不然**还是线条 ~~~**,
 * >   目的是**区分她到底有没有听到我说话**」
 *
 * | 什么时候 | 画什么 | 它在回答的问题 |
 * |---|---|---|
 * | 麦开着,什么都没收到 | **一根细线** `~~~~`([Mode.LINE]) | 「她在等」 |
 * | **认出你在说话了** | **基因序列**(双螺旋 + 横档,更宽)([Mode.GENE]) | 「**她听见了**」 |
 *
 * ★★ 为什么这个区分值一条专门的代码:**「麦克风开着」和「她听见了」是两件事**,
 *   而它们在过去长得**一模一样** —— 用户按住说了一句话,屏幕上那条波一直在流,
 *   他完全没法知道是自己没说好、还是她根本没收到。这正是这个项目反复吃亏的那一类:
 *   **界面不撒谎,但也没告诉他任何事。**
 *   ⚠️ 诚实的边界要写明白:它认的是**实时字幕那条路**(见 [ConMarnBubble] / [ConMarnActivity]
 *   的 `onPartial`)—— 辨识模型把这一小段解出字来了,它才变。太短的一句(不到一个字)
 *   可能解不出东西,**那一次它不会变**,哪怕 VAD 其实听到了动静。
 *   所以「变 = 她一定听见了」成立,**反过来不成立**。
 *
 * ## 「不能超出磨砂玻璃」—— 靠的不是裁切
 *
 * 基因序列那条带子宽、振幅大,最容易撞上的不是上下边,是**左右那两个圆角**:
 * 胶囊圆角 26dp,一根横贯全宽的带子走到最左最右时正好压在圆角那一块,
 * 看起来就是「那条光糊在玻璃外面了」。
 *
 * 做法不是 `clipPath`(那要在 View 外面知道胶囊的圆角,耦合得没必要),
 * 是**让波自己在两头安静下来**:`taper(u) = sin(πu)^0.35`。
 * `u` 是这一点的横坐标占整宽的比,两头 → 0,中间 → 1,而中间那大半段几乎贴着 1
 * (指数取 0.35 就是为了这个:取 1 的话整条波会鼓成一个纺锤)。
 * 细线那一态也用它,所以两态切换时两头的收法是一样的,不会看出破绽。
 *
 * ## 它和「他删过三次的那些装饰」不是一类东西
 *
 * 同一块地方他前后删过三次紫雾/呼吸底色(见记忆 `ruoxi-ui-no-decoration`),
 * 并留过一句判据:**「状态提示做成文字可以,做成颜色/光/呼吸/底色不行」**。
 * 这一版是那条判据的一个**例外**,而且例外得有名有姓 —— 所以先记下来:
 *
 * - 那三次删的是**常驻**的东西:她安静的时候屏幕上一直糊着一层光。它不说明任何事,
 *   只是好看 —— 那才是「装饰」。
 * - 这一条**只在你手指按着的时候存在**,松开就没。它和那块玻璃胶囊是同一类:
 *   **手感区**。手指按下去总得有个回执,否则「按住了没有」这个最基本的问题没有答案。
 * - ★ 而且他现在更进一步:**两副样子各自说明一件具体的事**(等 / 听见了)。
 *   这已经不是「光效」了,是**一个不会撒谎的指示灯**。
 *
 * ## 为什么是「时间驱动」而不是「跟着你的音量跳」
 *
 * 跟着音量跳会更好看(像个真的音量条),但要给 [Ear] 的采集循环加一条每块的 RMS 回调 ——
 * 那条路是麦克风的主干道,为了一条动效去动它不划算。而且他说的就是
 * **「慢慢的流动」**:匀速、从容,不抢注意力。
 *
 * ## 不变量
 *
 * - **不吃触摸**:它不是 clickable,`onTouchEvent` 返回 false,手势照旧落到
 *   [ConMarnActivity.onCaptionTouch] / [ConMarnBubble.onPillTouch] 那个壳上。
 *   这一点错了的后果是「按住说话整个失灵」。
 * - **不跑的时候一帧都不画**:`running` 为假时 `onDraw` 不排下一帧,也不可见。
 *   这是全项目最省电的写法代价最低的一条 —— 它只在按住的那几秒活着。
 * - ★★ **它进的那一格必须是 `SlotFrameLayout`(或爸爸高度已定),绝不能是普通
 *   `FrameLayout`**。它是 `MATCH_PARENT` 高的孩子,在普通 `wrap_content` 的爸爸里
 *   会**反过来定义爸爸有多高** —— 那正是 2026-10-05 那次「条子铺满屏、整个手机
 *   点不动」的真因(见 [SlotFrameLayout] 文件头)。
 */
class VoiceWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 它现在在说哪一件事。见文件头那张表。 */
    enum class Mode {
        /** 麦开着,还什么都没收到 —— **一根细线**。 */
        LINE,

        /** **认出你在说话了** —— 基因序列(双螺旋 + 横档),比线宽得多。 */
        GENE,
    }

    /** 一层细线波:幅度、线宽、亮度、初相、角速度(越小越慢)。 */
    private class Layer(
        val amp: Float,
        val width: Float,
        val alpha: Float,
        val phase: Float,
        val speed: Float,
    )

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val bloom = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val pathA = Path()
    private val pathB = Path()
    private val drift = Matrix()

    private var colorShader: LinearGradient? = null
    private var bloomShader: RadialGradient? = null

    private var running = false

    /**
     * 现在是哪一副样子。**默认是「等」** —— 这个默认值是刻意的:
     * 麦刚开的那一瞬间什么都还没听到,那时说「听见了」就是在撒谎。
     */
    private var mode = Mode.LINE

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        // ★ 渐变**故意做得比这个 View 宽**(从 -w 到 +2w),这样它左右平移时
        //   取到的永远是渐变内部的颜色。做成正好 0..w 的话,一平移就会被 CLAMP
        //   扯成两根死色的柱子 —— 那看起来像显示坏了,不像光在流。
        colorShader = LinearGradient(
            -w.toFloat(), 0f, (2 * w).toFloat(), 0f,
            intArrayOf(C_VIOLET, C_PINK, C_CYAN, C_PINK, C_VIOLET),
            floatArrayOf(0f, 0.28f, 0.5f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        bloomShader = RadialGradient(
            w / 2f, h / 2f, (w * 0.62f).coerceAtLeast(dp(60f)),
            intArrayOf(0x59B07BFF, 0x2EE05B8E, 0x00000000),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || !running) return

        val t = AnimationUtils.currentAnimationTimeMillis() / 1000f

        // ① 弥散光晕:慢呼吸。0.10~1.0 之间来回,别让它灭掉(灭掉就断成两截)。
        //   ★ 光晕**两态都有**,而且强度一样 —— 它说的是「麦克风活着」这件事,
        //     那件事在两态里都是真的。跟着变的话,「听见了」和「在等」就又多了一重
        //     要靠对比才看得出来的差别,反而糊。
        val breathe = 0.55f + 0.45f * sin(t * BREATHE_SPEED)
        bloom.alpha = (20 + 40 * breathe).toInt()
        bloom.shader = bloomShader
        canvas.drawRect(0f, 0f, w, h, bloom)

        val s = colorShader ?: return
        // ★ 混色**自己也在流**,而且和波形**不是一个速度** ——
        //   这一步是「科技感」和「一张静态渐变图」的分界线:
        //   两者同步的话,颜色会像粘在波峰上一起走,看着是一张会动的画;
        //   错开之后光才是光。
        drift.setTranslate(((t * DRIFT_SPEED) % 1f) * w, 0f)
        s.setLocalMatrix(drift)
        line.shader = s

        when (mode) {
            Mode.LINE -> drawSimpleWave(canvas, w, h, t)
            Mode.GENE -> drawGene(canvas, w, h, t)
        }

        postInvalidateOnAnimation()   // 下一帧。`running` 为假时上面就返回了,不会走到这儿。
    }

    // ------------------------------------------------------------------
    // 等:一根细线(他原话里的「还是线条 ~~~」)

    private fun drawSimpleWave(canvas: Canvas, w: Float, h: Float, t: Float) {
        val mid = h / 2f
        val lambda = w / CRESTS_VISIBLE
        val k = (2.0 * PI / lambda).toFloat()
        val over = dp(2f)

        for (l in LAYERS) {
            val amp = h * l.amp
            pathA.reset()
            var x = -over
            while (x <= w + over) {
                val xa = x.coerceIn(0f, w)
                val y = mid + amp * taper(x / w) * sin(k * x - t * l.speed + l.phase)
                if (x <= -over + 0.001f) pathA.moveTo(xa, y) else pathA.lineTo(xa, y)
                x += dp(STEP_DP)
            }
            // 同一根线两遍:粗而淡的那遍当辉光,第二遍才是灯丝本身。
            // `setShadowLayer` 也能做,但那要求 View 走**软件层**,为一个动效不值得。
            for ((scale, alpha) in GLOW_PASSES) {
                line.strokeWidth = dp(l.width * scale)
                line.alpha = (255 * l.alpha * alpha).toInt().coerceIn(0, 255)
                canvas.drawPath(pathA, line)
            }
        }
    }

    // ------------------------------------------------------------------
    // 听见了:基因序列

    /**
     * 带子有多宽 —— ★ **高度的一个比例,不是固定 dp**。
     *
     * 这一条就是他说「比线条更宽一点」在代码里的落点:房间里那条胶囊和悬浮窗那条
     * 高度不一样,写死一个 dp 的话,**矮的那条会变成一根粗香肠,高的那条还是一根线**。
     * 夹在 [MIN_RIBBON_DP]..[MAX_RIBBON_DP] 之间,是为了两头都不难看。
     *
     * ★★ 它和 [drawSimpleWave] 用的是**两套数量级**:那边最粗的一层是 1.6dp × 3.4 的辉光,
     *   灯丝本身只有 1.6dp。这边直接是高度的 11.5%(矮胶囊里也有 2.2dp、正常尺寸六七 dp)。
     *   「一下子看得出来变了」靠的就是这个差,不是靠颜色。
     */
    private fun ribbonPx(h: Float): Float =
        (h * RIBBON_FRAC).coerceIn(dp(MIN_RIBBON_DP), dp(MAX_RIBBON_DP))

    private fun drawGene(canvas: Canvas, w: Float, h: Float, t: Float) {
        buildStrands(w, h, t)
        // 光晕:同一条带子先用粗而淡的笔再走一遍(霓虹管的标准画法)。
        strokeBoth(canvas, widthPx = ribbonPx(h) * 2.1f, alpha = 0.12f)
        drawRungs(canvas, w, h, t)
        strokeBoth(canvas, widthPx = ribbonPx(h), alpha = 1f)
    }

    /** 两条反相的链,各自取一次样。★ 两条的相位差**正好是 π** —— 差别的不是螺旋,是麻花。 */
    private fun buildStrands(w: Float, h: Float, t: Float) {
        val mid = h / 2f
        val amp = h * AMP_FRAC
        val lambda = w / CRESTS_VISIBLE
        val k = (2.0 * PI / lambda).toFloat()
        val over = dp(2f)

        pathA.reset()
        pathB.reset()
        var x = -over
        var first = true
        while (x <= w + over) {
            val a = amp * taper(x / w)
            val y = a * sin(k * x - t * HELIX_SPEED)
            val xa = x.coerceIn(0f, w)
            if (first) {
                pathA.moveTo(xa, mid + y)
                pathB.moveTo(xa, mid - y)
                first = false
            } else {
                pathA.lineTo(xa, mid + y)
                pathB.lineTo(xa, mid - y)
            }
            x += dp(STEP_DP)
        }
    }

    /**
     * 两头的**窗口函数**。见文件头:它替掉了「裁切」,是「不能超出磨砂玻璃」那条的实现。
     *
     * 指数取 0.35:两头平滑归零,而中间那一大段几乎贴着 1。
     * 取 1(纯正弦窗)的话整条带子会鼓成一个纺锤,不像序列,像一片叶子。
     *
     * ★ 细线那一态也用它 —— 两态在两头收法一致,切换时才看不出破绽。
     */
    private fun taper(u: Float): Float =
        sin(PI * u.toDouble()).toFloat().coerceAtLeast(0f).pow(0.35f)

    private fun strokeBoth(canvas: Canvas, widthPx: Float, alpha: Float) {
        line.strokeWidth = widthPx
        line.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        canvas.drawPath(pathA, line)
        // ★ B 链压暗一点当「后面那一根」,否则两条一模一样粗,看着是一根麻花不是螺旋。
        line.alpha = (255 * alpha * STRAND_B_ALPHA).toInt().coerceIn(0, 255)
        canvas.drawPath(pathB, line)
    }

    /**
     * 碱基对。★ 它的长度是两条链的**间距**,所以**在节点上自己归零** ——
     * 这不是偷懒,这正好是真的双螺旋从侧面看的样子。
     *
     * 横档还在**慢慢横着走**(`t * RUNG_FLOW` 取模),走一格的时间比波慢,
     * 两者不同步才有「序列在流动」而不是「整张图在平移」。
     */
    private fun drawRungs(canvas: Canvas, w: Float, h: Float, t: Float) {
        val mid = h / 2f
        val amp = h * AMP_FRAC
        val lambda = w / CRESTS_VISIBLE
        val k = (2.0 * PI / lambda).toFloat()
        val gap = dp(RUNG_GAP_DP)
        val shift = (t * dp(RUNG_FLOW_DP)) % gap

        line.strokeWidth = ribbonPx(h) * RUNG_WIDTH_FRAC
        line.alpha = (255 * RUNG_ALPHA).toInt().coerceIn(0, 255)

        var x = shift
        while (x < w) {
            val y = amp * taper(x / w) * sin(k * x - t * HELIX_SPEED)
            // 并拢的地方(节点)本来就该看不见横档;这里再放过短的几根,
            // 免得它们退化成一个点、糊成一串脏点。
            if (abs(y) > h * 0.04f) canvas.drawLine(x, mid - y, x, mid + y, line)
            x += gap
        }
    }

    // ------------------------------------------------------------------

    /**
     * **她认出你在说话了没有。** 两副样子之间的唯一开关。
     *
     * ★ 幂等:同一个值重复调不会重排、不会重置相位。
     * ★ 它**不动** `running` —— 「在不在听」和「听没听见」是两件事,
     *   关麦该走 [stop],别混进来。
     */
    fun setHeard(heard: Boolean) {
        val want = if (heard) Mode.GENE else Mode.LINE
        if (mode == want) return
        mode = want
        invalidate()
    }

    /** 开始流动 + 显出来。**幂等** —— 重复调不会把相位重置回去(重置会看出来一卡)。 */
    fun start() {
        if (running) return
        running = true
        // ★ 每次亮起来都从「等」开始。留着上一轮的 `GENE` 的话,新一轮麦刚开就显示
        //   「我听见了」—— 那正是这个 View 最不该犯的错(它在替麦克风撒谎)。
        mode = Mode.LINE
        visibility = VISIBLE
        invalidate()
    }

    /** 现在是不是「她听见了」那一副。给 [freeze] 的调用方判断用。 */
    fun isHeard(): Boolean = mode == Mode.GENE

    /**
     * **冻住** —— 停下动画,但**留在屏幕上**,保持当前那一帧。
     *
     * ## 为什么需要它(而不是直接 [stop])
     *
     * 松手那一刻麦就关了,所以**一条还在流的波**是在撒谎(见调用处那段注释)。
     * 但「她听见了」是**过去式的事实**,麦关了它照样成立 —— 收掉它反而是把
     * 唯一一条「她到底有没有听到我说话」的证据抹了。
     *
     * 而且不收还有个更实际的理由:松手到定稿之间要等 1~2 秒(flush + 转写),
     * 那一格正好空着。这段时间**冻住的基因序列**比一句「在认你说的话…」
     * 多说了一件事:她**确实听见了**。
     *
     * ★ 冻住之后 `running` 是 false(不再排帧),但 `visibility` 仍是 VISIBLE
     *   —— 所以 [stop] 那句早退必须去掉,否则冻住之后就**再也藏不掉了**。
     */
    fun freeze() {
        running = false
    }

    /** 停 + 藏。停了之后一帧都不再排。 */
    fun stop() {
        // ⚠️ **不能**写成 `if (!running) return`:冻住过(running=false、画面还在)
        //    之后那句会把「藏」一起挡掉,症状是水波永远停在屏幕上。
        running = false
        visibility = GONE
    }

    override fun onDetachedFromWindow() {
        // 窗口没了还在排帧 = 白白烧电,而且这种漏在日志里一点痕迹都没有。
        running = false
        super.onDetachedFromWindow()
    }

    private companion object {
        val C_VIOLET = 0xFFB07BFF.toInt()
        val C_PINK = 0xFFFF6EC7.toInt()
        val C_CYAN = 0xFF5BD9FF.toInt()

        /** 屏上同时看得到几个波峰。1.7 ≈ 「看得出在流动」和「不至于挤成线团」之间。 */
        const val CRESTS_VISIBLE = 1.7f

        /** 采样步长(dp)。3dp ≈ 一屏 130 个点,肉眼已经是光滑曲线,而它每帧只画一次。 */
        const val STEP_DP = 3f

        // ---- 「等」那一态:细线 ----

        /** (线宽倍数, 亮度倍数)。从粗到细、从淡到亮 —— 霓虹管的标准画法。 */
        val GLOW_PASSES = arrayOf(3.4f to 0.10f, 1.9f to 0.26f, 1.0f to 0.95f)

        /**
         * 三层线。速度**各不相同**是有意的:一样快的话三条会锁成一条粗线,
         * 交错起来才有「水在流」的层次感。
         */
        val LAYERS = arrayOf(
            Layer(amp = 0.30f, width = 1.6f, alpha = 1.00f, phase = 0.0f, speed = 2.4f),
            Layer(amp = 0.22f, width = 1.2f, alpha = 0.70f, phase = 2.1f, speed = 1.7f),
            Layer(amp = 0.13f, width = 0.9f, alpha = 0.50f, phase = 4.2f, speed = 3.1f),
        )

        // ---- 「听见了」那一态:基因序列 ----

        /** 振幅占这一格高度的比。★ 0.30 是**上界不是目标**:再大就会被圆角啃到。 */
        const val AMP_FRAC = 0.30f

        /** 带子宽度 = 高度 × 这个数,夹在下面两个 dp 之间。见 `ribbonPx`。 */
        const val RIBBON_FRAC = 0.115f
        const val MIN_RIBBON_DP = 2.2f
        const val MAX_RIBBON_DP = 7.0f

        /** 横档的粗细**相对带子**—— 固定 dp 的话,矮胶囊里横档会比链还粗。 */
        const val RUNG_WIDTH_FRAC = 0.42f

        /** 横档间隔与流动速度(dp)。 */
        const val RUNG_GAP_DP = 11f
        const val RUNG_FLOW_DP = 4.5f

        /** 横档比链暗一档:它是**分子里的键**,不是发光的灯丝。 */
        const val RUNG_ALPHA = 0.42f

        /** B 链(后面那一根)的亮度。两条一样亮就成了麻花。 */
        const val STRAND_B_ALPHA = 0.72f

        /** 螺旋流动的角速度(rad/s)。2.0 ≈ 3 秒一个来回,**慢慢的**。 */
        const val HELIX_SPEED = 2.0f

        // ---- 两态共用 ----

        /** 光晕呼吸的角速度(rad/s)。0.9 ≈ 7 秒一个来回。 */
        const val BREATHE_SPEED = 0.9f

        /** 混色横向漂移速度:0.06 屏宽/秒 ≈ 17 秒走完一整屏。慢到看不出「在动」,只觉得「活」。 */
        const val DRIFT_SPEED = 0.06f
    }
}
