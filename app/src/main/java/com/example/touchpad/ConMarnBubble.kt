package com.example.touchpad

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * 她的悬浮小窗 —— 用户 2026-10-03 定的形态:「干活的时候她是悬浮窗在一边看着
 * 我做事情」。今天的落法:
 *  - 小圆钮 / 人形常驻(可拖、可拖到底部垃圾桶扔掉),点一下弹出**底部一条玻璃输入条**。
 *  - 条子上能**打字跟她说**(回车或 ↑ 发出去),右边一颗 🎤 **就地开聊**(2026-10-05;
 *    在那之前它只负责把房间拉起来,用户不要那个绕路 —— 见 [startBubbleVoice])。
 *  - 她回的话显示在条子上方那一行(她的头像底色),**不占地方**:没有就不显示。
 *
 * ★★ 2026-10-04 晚:**原来那个大面板删了**。用户原话:
 *   「那个大弹窗(有进她的房间,中转站那个大弹窗)**删掉去**」
 *   它当时装着 44dp 大脸 + 名字 + 她的整段话 + 干活日志框 + 状态行 + 两颗大按钮
 *   —— 一个 45% 屏宽的竖长条,而他抱怨过两次「**我要那么大块面板干嘛?挡我操作了**」。
 *   现在:点一下只升起来一条输入条,和房间里那条**长得一模一样**
 *   ([R.drawable.her_glass_pill] + 白字),因为这两条干的是同一件事 —— 跟她说话。
 *
 * ★ 那两颗大按钮的去处(删之前先确认过路没断,**不是随手删的**):
 *   · 「进她的房间」→ 变成条子上那颗 🎤(还能进,而且直接开始聊);
 *   · 「中转站」→ **不补**。那个 hook([onOpenStation])存在的原因是
 *     「换主之后右缘那条磨砂书签条被球替掉了,而它是打开中转站唯一的路」;
 *     而那条书签条**已经复位了**([isEnabled] 默认改回 false 那次,见 `TransferOverlay.show`),
 *     所以中转站的门牌早就不止这一个。**入口不缺,就不该再留一颗挡手的按钮。**
 *
 * 内容从哪来:[AiAgent.start] 里那层 tee(见 AiAgent.kt 的 start)—— 不管这一轮
 * 从她的房间、主界面还是 OI 卡片发起,结果/错误/忙闲都会同步进来。
 *
 * 窗口纪律(跟 MainActivity 悬浮球同款教训):
 *  - **球**用精确像素尺寸、`FLAG_NOT_FOCUSABLE`(ColorOS 会把 WRAP 窗撑大,触发区乱飘);
 *  - **输入条**是**另一个窗口**,而且这一次**必须**能拿到焦点 —— 见 [buildBar] 里
 *    flags 那一段,那里也是这条链上唯一的新风险;
 *  - tee 回调都从后台线程来,这里统一 post 到主线程再碰 View。
 */
object ConMarnBubble {

    private const val PREF = "conmarn_bubble"
    private const val KEY_ON = "enabled"
    private const val KEY_X = "ball_x"        // 拖到哪儿了(像素)。不存的话「能拖着放」是假的
    private const val KEY_Y = "ball_y"

    /** 🎤 的两副面孔:没在听 = 白(同条子上其他图标),在听 = 粉。见 [setListeningUI]。 */
    private val MIC_IDLE = 0xFFFDF2F6.toInt()
    private val MIC_LIVE = 0xFFFF7FB0.toInt()

    /**
     * 语音那一态里,胶囊上写的那句**说明书**。
     *
     * ★ 它必须把**两件事**都写出来(按住说话 / 点一下打字)—— 只写一半的话,
     *   另一半就变成了「需要先找到的东西」。房间里那句一字不差,是同一块玻璃上
     *   同一件事,不该有两个说法。
     */
    private const val HINT_HOLD = "按住说话,点一下打字"

    /** 松手到定稿之间那句(和房间同一个说法)。 */
    private const val HINT_DECODING = "在认你说的话…"

    /** 进了取消区那句。★ **不带箭头** —— 用户 2026-10-05 点名了,见 [setCancelGlow]。 */
    private const val HINT_SWIPE_CANCEL = "松手就不发出去"

    /**
     * 一轮没听到人声之后,隔多久再听下一轮。
     *
     * ★ 不能是 0:那样「收工 → 重开」在日志里会连成一串,看不出是两个回合,
     *   出问题时会把「一分钟听了九轮」读成「一次听了一分钟」。
     *   和房间里那个 `EAR_RETRY_GAP_MS` 是同一个数、同一个理由。
     */
    private const val BAR_RETRY_GAP_MS = 200L

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var appCtx: Context? = null

    /**
     * 「中转站」面板的入口 —— `ConMarnBubble` 是个静态 object,拿不到 Activity 的
     * `TransferOverlay`,所以由 [MainActivity] 挂进来。
     * ★ **挂在 onResume、摘在 onPause**(不是 onCreate/onDestroy):这个 lambda 捕获了
     * Activity,而宿主是个静态 object —— 挂在进程级上就是泄漏。
     *
     * ★ 这个 hook 存在的理由(2026-10-04 换主):右缘原来那条 16x100 磨砂书签条
     * 被她的球替掉了,而**那条东西是唯一能打开中转站面板的入口**。入口必须补一个,
     * 否则「换个脸」会顺手弄丢文件互传那条路。
     */
    @Volatile var onOpenStation: (() -> Unit)? = null

    /**
     * 她自己的房间是不是在前台。在的话把球收起来 —— 那儿她本人就是满屏的,
     * 再在旁边浮一颗自己的头很怪(而且会挡住场景,点物件也点不着)。
     */
    @Volatile private var roomInFront = false

    private var bubbleView: View? = null

    /** 底部那条玻璃输入条(整条命就是「点一下球 → 出现 → 再点一下/点别处 → 消失」)。 */
    private var barView: View? = null

    // ★★ 2026-10-06:**条子上方那一行字幕整块删了**(原来是 `herView` 那个 `TextView`)。
    //
    //   用户原话:「**悬浮窗不要再有字幕**」(同一句里还有「字和条子都删,但**别删掉悬浮窗的条子**」)。
    //   它承载过四样字,逐条的去处:
    //     · 「你:xxx」/「…正在想」(他和她的对白)      → **删掉**,这就是他要拿掉的那一层
    //     · 她最近一句话(条子重开时回显 [lastFinal]) → **删掉**(见下面 [lastFinal] 那句注释)
    //     · 「🎤 在听…(点别处 = 结束)」               → **删掉,但不变量没破**:「开着麦看得见」
    //       早就不靠这行字了 —— 它靠的是那一格里**流动的基因序列**([showWave],由
    //       [setListeningUI] 点亮),那才是他 2026-10-04/10-05 点名要的形状。
    //     · 「✗ 出错」                                 → ★ **不许静默失败**,所以改走 **Toast**
    //       (这个文件本来就用 Toast 报「识别模型没装」,见 [canStartVoice] 那条)。
    //
    //   ⚠️ 那 64dp 的让位**不受影响** —— 它躲的是**这条玻璃条子**,不是上面那行字幕;
    //      条子仍然是 48dp、仍然是系统浮层。见 [media_preview_panel] 那段注释。

    private var barInput: EditText? = null
    private var barSend: TextView? = null

    /** 那条**玻璃胶囊自己**。它红了 = 现在松手这句话就作废(见 [setCancelGlow])。 */
    private var barPill: LinearLayout? = null

    /** 语音那一态里,胶囊左边那一格写的字(「按住说话,点一下打字」)。 */
    private var barHint: TextView? = null

    /**
     * 🎤「回语音」。**只在打字那一态露面** —— 和房间里那颗同一个规矩。
     *
     * ★★ 用户 2026-10-05 原话:「**我要的是按住对话框说话,不是右边弄个麦克风按钮**,
     *   当然,如果点对话框(透明玻璃)就会是打字模式,**到打字模式后如果还想回语音输入模式,
     *   就再点麦克风**」。
     *   所以这颗按钮**不再是「开麦」的入口**(语音态整块胶囊就是入口),
     *   它只剩下一个作用:**从打字态回到语音态**。语音态露着它反而是块碍事的补丁 ——
     *   它压在手该落下去的那块玻璃上。
     */
    private var barVoiceBtn: ImageView? = null

    /** 现在是打字态吗。**两个座位(提示 / 输入框)轮着坐,只有一个开关** —— 见 [setBarTypingMode]。 */
    private var barTyping = false

    /**
     * 按住说话时那条**慢慢流动的基因序列**([VoiceWaveView])。
     *
     * ★ 和房间里那条**是同一个类**,理由写在他的原话里:这一次他把两处一起点了名。
     * ★ 它只占那一格、只在按住的几秒活着,而且**它出现的时候那一格里没有字**
     *   (见 [showWave] / [restSlot])—— 两者同时露着会当场叠在一起。
     */
    private var barWave: VoiceWaveView? = null

    // ---- 那块玻璃上的手指(2026-10-05,和房间里那套一字不差) ----
    //
    // ★★ 判据的算法收在 [BarGestureMath] 一份(房间和这里共用),这里只留状态。

    /** 手指是不是正按在胶囊上。用它挡掉重复的 DOWN(没有它,一次长按会开出好几个麦)。 */
    private var pressDown = false

    /** 按下的时刻,松手时算「点一下还是按住说话」。 */
    private var pressStartedAt = 0L

    /** 上滑取消的判定起点(按下时的原始 Y)。 */
    private var pillDownRawY = 0f

    /** 这一下**进过取消区** —— 闩,不是当前距离。见 [BarGestureMath.release]。 */
    private var swipeCancelled = false

    /** 按下之前对话是不是本来就开着 —— 「点一下打断」要据此判断该不该留在这场对话里。 */
    private var sessionBeforePress = false

    /**
     * 手指**落下那一刻**她是不是正在念。
     *
     * ★★ 2026-11-08（体检 A2）：半双工闸在按下时就把她停了（见 [startEarTurn]），
     *   于是到松手时 `HerVoice.isSpeaking()` 已经是 false —— 如果那一下是「点」，
     *   松手那一支会把它判成「她本来就没在说话 → 我要打字」，**弹出键盘**。
     *   而他刚才那一下的意思明明是「别说了」。所以状态得在按下时记下来。
     */
    private var spokeAtPress = false

    /** 这一轮开麦是不是**他手指按着**开的。它只影响「没听到东西之后」怎么收。 */
    private var turnFromHold = false

    /**
     * 这一轮是**被手指掐掉的**,不是「没人说话」。
     *
     * ★ 缺了它就会有一个很难看的 bug:他点一下打断她,而点的那一下会**先开麦再取消**,
     *   `onDone` 回来时 `heardThisTurn` 是 false → 按原逻辑**整场对话就此结束**。
     *   他看到的会是「我就点了她一下,她就不理我了」(同 [ConMarnActivity] 那条)。
     */
    private var roundCancelled = false

    /**
     * 按住够了 [EarSessionMath.TAP_MAX_MS] 才兑现的「我在听」。
     *
     * ★ 按下的瞬间先开麦(手感要快),但**界面上一声不吭** —— 这一下是点还是按,
     *   要到松开才知道。一按下就亮「我在听」的话,每一次「点一下打断她」
     *   都会先闪一句「我在听」,**而那是在撒谎**。
     */
    private val confirmHold = Runnable {
        if (!pressDown || !voiceOn) return@Runnable
        setListeningUI(true)
        trace(appCtx ?: return@Runnable, "🎤 听着呢,松手我就当你说完了")
    }

    // ---- 就在这条条子上跟她说话(2026-10-05) ----
    //
    // ★★ 用户原话:「桌面悬浮窗,点语音输入,还是会跳到房间,**不能直接语音输入需要转房间**」。
    //   在那之前这颗 🎤 只干一件事:把房间拉起来、开麦。理由是「对话是个循环,
    //   循环里牵着半双工闸和『她在听』那盏灯,而那盏灯在这条 40dp 的条子上没地方放」——
    //   那个理由今天不成立了:**灯就点在这颗 🎤 自己身上**(听的时候变粉),
    //   外加那一格里流动的基因序列(见 [showWave])。所以循环整条搬过来。
    //   ★ 2026-10-06:原来这儿还写着一句「状态写在它上面那一行([herView])」——
    //     那行字幕今天整块删了,状态改由上面那两处说,见文件顶部那段说明。
    //
    // ★ 循环的算术**一条都不重写**,全部复用房间里那套已经钉过单测的
    //   [EarSessionMath](冷却 / 45 秒 / 挂断词),和进程级的 [Ear]。
    //   两份实现是「同一个 bug 修两次」的温床,这个项目已经吃过好几回。

    /** 对话开着没有。它同时是「麦克风该不该开着」的唯一判据。 */
    @Volatile private var voiceOn = false

    /** 这一轮听到东西没有。用它判「该收工还是再听一轮」,不去嗅错误文案(同房间)。 */
    private var heardThisTurn = false

    /** 上一轮他真的有动静的时刻 —— 45 秒从这儿往后数,不是从开麦那一刻(同房间)。 */
    private var silenceSince = 0L

    /**
     * 这一句答复是**说出来的**(而不是他打字进来的),所以她要念出声。
     *
     * ★ 只在「他开的口」这一条路上置位。他打字问的那条路**不出声** ——
     *   那多半是在不能出声的场合(地铁上、她旁边有人),而那正是打字这条路存在的理由。
     */
    private var speakThisReply = false

    /** 「该把麦放回去了」。会话中反复重排,见 [armResume]。 */
    private val resumeListening = Runnable { startEarTurn(fromHold = false) }

    // 她最近一次说的话。★ 2026-10-06 起**不再显示**(那行字幕删了),
    // 它的唯一读者是 [onFinal] 里那句「她答完要念出来」—— [HerVoice.speak] 得拿到文本。
    private var lastFinal: String? = null
    @Volatile private var busy = false

    // 拖动手势的临时态(触摸事件序列之间要记住,只能是字段)
    private var downRawX = 0f
    private var downRawY = 0f
    private var startLpX = 0
    private var startLpY = 0
    private var dragging = false

    // ---- 拖到底部垃圾桶 = 关掉悬浮窗(2026-10-04 他点名要的) ----
    private var trashView: View? = null
    /** 她此刻**够得着**垃圾桶没有。它同时是「红光晕亮着没有」和「松手算不算数」的唯一判据。 */
    private var trashArmed = false

    // ------------------------------------------------------------------
    // 开关(持久化,模式和语音唤醒一致)
    // ------------------------------------------------------------------

    /**
     * ★ 默认**关** —— 2026-10-04 晚**退回**「换主」那一版。
     *
     * 换主当时把它改成默认开,理由是「它是电脑端回到她房间唯一的那条路」。
     * 但用户上手之后的结论是**这条路本身代价太大**:
     *   · 「人物**还是一大张卡在那里**」—— 她占的那块地方,比他愿意让出来的多
     *   · 「那个中转站**要先打开 app 是什么鬼,以前都不用**…**拖不了东西到中转站的**」
     *
     * 第二句才是要命的:换主之后,**中转站的门牌从「右缘那条书签条」换成了「她的脸」**,
     * 而门牌后面那扇门([MainActivity.TransferOverlay])仍然要 Activity 活着才建得出来。
     * 于是「往中转站拖文件」这条主用途多了一层前提。
     *
     * 所以默认关、书签条复位(见 `TransferOverlay.show` 里那段)。
     * **代码一行没删** —— 中转站面板里的「她的悬浮窗」卡片还在,想叫她出来点一下就行。
     */
    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        trace(ctx, "开关=$on")
        if (on && !Settings.canDrawOverlays(ctx.applicationContext)) {
            toast(ctx.applicationContext, "先去系统设置给悬浮窗权限,她的悬浮窗才出得来")
            return
        }
        applySetting(ctx)
    }

    /**
     * ★ 一次性迁移:把「换主」那一版**自动写进盘里的 true** 关掉一次。
     *
     * 光把默认值改成 false 是**不够的** —— 默认值只在盘上没有这个键时生效,
     * 而这台机器上盘里已经存着 `true` 了(换主那版默认开,一启动就写进去了)。
     * 不迁移的话他装完新版本,**她还是照样杵在那儿**,然后他会以为我没改。
     *
     * 用**独立的标记键**而不是「每次启动都写 false」:
     * 后者会让他以后从卡片里手动打开她、下次启动又被悄悄关掉 —— 那是「关了又自己变回去」,
     * 比不生效更让人恼火。标记键保证**只关这一次**。
     */
    private const val KEY_RETIRED_ONCE = "retired_20261004"

    private fun migrateOffOnce(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (sp.getBoolean(KEY_RETIRED_ONCE, false)) return
        sp.edit().putBoolean(KEY_ON, false).putBoolean(KEY_RETIRED_ONCE, true).commit()
        trace(ctx, "一次性迁移:换主那版把球默认开了,现在关掉一次(以后你手动开的不受影响)")
    }

    /** 进程活着但小窗没了(进程被杀重开)时对一下表;MainActivity.onResume 调。 */
    fun applySetting(ctx: Context) {
        migrateOffOnce(ctx)
        val enabled = isEnabled(ctx)
        val want = enabled && !roomInFront
        trace(ctx, "对表:enabled=$enabled roomInFront=$roomInFront → ${if (want) "要出来" else "要收起"}")
        schedule(ctx, want)
    }

    /**
     * 进 / 出她的房间。进去把球收起来(她本人已经满屏了),出来再按设置放回去。
     * 由 [ConMarnActivity] 的 onResume / onPause 调。
     */
    fun setRoomInFront(ctx: Context, front: Boolean) {
        roomInFront = front
        trace(ctx, "房间前台=$front")
        // ★★ 2026-10-05:房间进前台 = 这条条子马上要收起来 —— **麦克风必须先关**。
        //   条子没了而麦还开着,是隐私那条线上唯一不能犯的错(见 [voiceOn] 的注释)。
        //   放在 [applySetting] **之前**:那个函数会把球收起来,而收的过程里
        //   这几行要是晚一步,中间就有几毫秒是「看不见的麦克风」。
        if (front) endBubbleVoice(ctx, null)
        applySetting(ctx)
    }

    /**
     * 她的球的显隐是一次**看不见的状态翻转** —— 所以每次翻都留一行。
     * 和那层透明纸同一个道理:这类东西失效的样子是「球不见了」或「球乱冒」,
     * 它不报错、不崩、界面一切正常,只靠猜是猜不出来的
     * (2026-10-04 换主第一版就踩了:球在**她自己的房间**里冒了出来)。
     */
    private fun trace(ctx: Context, msg: String) {
        try { ModelManager.get(ctx.applicationContext).trace("她的球: $msg") } catch (_: Exception) {}
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /**
     * ★★ **物理屏**当下的真实尺寸 —— 悬浮窗的一切几何都必须用它,不能用 `app.resources.displayMetrics`。
     *
     * 2026-10-04 晚查出来的真根因,他原话是
     * 「**我整个屏幕,全部都是她那个悬浮窗的面板**」。
     *
     * `app.resources.displayMetrics` 跟的是 **App 自己的配置方向**,不是设备方向:
     * `ConMarnActivity` 锁了 `sensorLandscape`(manifest:143),所以只要她房间开过一次,
     * 这个 App 的 resources 就**一直**停在横屏 —— 哪怕人已经回到**竖屏桌面**。
     *
     * 实测对账(`adb shell dumpsys window displays` → `cur=1080x2374` 竖屏,
     * 而悬浮球自己打的日志写着 `屏=2374x1080`):
     * 面板宽 `2374 × 0.45 = 1068px` 被画在一块 **1080px 宽**的屏上 = **占屏宽 99%**。
     * 于是「改成 45% 宽」那一版**看起来毫无变化** —— 数字变小了,坐标系还是错的。
     * 同一份错值还把她本人和垃圾桶的落点算到 x≈2166(竖屏外的右边),
     * 也就是说**拖到垃圾桶那个交互也会失效**。
     *
     * `Resources.getSystem()` 不带 App 的配置覆盖,跟的是**物理屏当前真实方向**
     * (竖=1080x2374,横=2374x1080)。这是 `MainActivity.realDm()` 早就写过、而且
     * 带着同一段血泪注释的做法 —— 那边踩过一次,这边又踩了一次。
     * **这个类里凡是拿屏幕尺寸算位置/大小的地方,一律走这里。**
     */
    private fun realDm(ctx: Context): android.util.DisplayMetrics {
        val out = android.util.DisplayMetrics()
        try {
            val sys = android.content.res.Resources.getSystem().displayMetrics
            if (sys.widthPixels > 0 && sys.heightPixels > 0) {
                out.setTo(sys)
                return out
            }
        } catch (_: Exception) {}
        out.setTo(ctx.resources.displayMetrics)
        return out
    }

    private fun savedPos(ctx: Context): Pair<Int, Int>? {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!sp.contains(KEY_X) || !sp.contains(KEY_Y)) return null
        return sp.getInt(KEY_X, 0) to sp.getInt(KEY_Y, 0)
    }

    private fun savePos(ctx: Context, x: Int, y: Int) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }

    /**
     * ★ 显隐的**唯一出口**。为什么不直接 show / hide:
     *
     * 2026-10-04「换主」第一版就栽在这儿 —— `ConMarnActivity` 起场时会
     * **onResume → onPause → onResume**(ColorOS 给横屏 App 做固定旋转重建,
     * logcat 里 `hasFixedRotationTransform=true` 就是它,实测同一秒内三次)。
     * 那时 show 和 hide 各自 `post` 一个任务到主线程,**谁先执行不由调用顺序决定**,
     * 于是「出来」抢在「收起」前面跑完 —— 球留在了**她自己的房间**里(截图验过)。
     *
     * 改法:只认最后一次意图。每次调度发一个号,任务执行时号不是最新的就作废。
     * 无论中间翻了几次,落在屏幕上的永远是最后那一次的状态。
     */
    private var pendingTick = 0

    private fun schedule(ctx: Context, visible: Boolean) {
        val my = ++pendingTick
        val app = ctx.applicationContext
        main.post {
            if (my != pendingTick) return@post   // 后面又有新意图了,这条作废
            if (visible) realShow(app) else realHide()
        }
    }

    private fun realShow(app: Context) {
        if (bubbleView != null) return
        // 兜底:就算调度漏了,也绝不在她自己的房间上头浮一颗她的头
        if (roomInFront) { trace(app, "轮到出来了,但房间已在前台 —— 算了"); return }
        if (!Settings.canDrawOverlays(app)) { trace(app, "没有悬浮窗权限,出不来"); return }
        appCtx = app
        val w = wm ?: app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w
        val dm = realDm(app)   // ★ 物理屏,不是 App 的横屏配置(见 realDm 的注释)

        // ★★ 2026-10-04「**悬浮窗改成**人形**」。
        //
        // 先试盘上那张人形 —— 它是 [ConMarnActivity] 从**活着的场景**里扒下来的
        // (见那边 captureFigure 的说明:头像里那张是**照片**,这张是**她**)。
        //
        // 拿不到就退回圆球。**退回不是失败,是常态**:她还没被打开过、模型还没装好、
        // WebGL 上下文丢了 —— 都会没有那张图。圆球一直在,只是为了不再「没有脸」,
        // 从 46dp 提到了 56dp(那张脸是照片,缩到 138px 五官就糊了)。
        val figure = Drawable.createFromPath(File(app.filesDir, CONMARN_FIGURE_FILE).absolutePath)

        val b: ImageView
        val wpx: Int
        val hpx: Int
        if (figure != null) {
            // ★ 窗口按**这张图的比例**开,不是正方。
            //   280:700 是她拍图时就定死的取景(见 her.js captureFigure 的 W/H)——
            //   这里照抄那两个数,不另外算,免得两边哪天各改各的。
            b = ImageView(app).apply {
                setImageDrawable(figure)
                // FIT_XY:窗口比例本来就是照这张图开的,再 FIT_CENTER 只会留白边,
                // 而留白边 = 她周围多出一圈**看不见但吃触摸**的区域。
                scaleType = ImageView.ScaleType.FIT_XY
            }
            // 高度取「不超过 160dp」和「不超过屏高 1/3.4」里更小的那个;
            // 宽度按图的比例跟上。横屏那页(1080 高)落下来约 127x317 px ——
            // 站在边上,不挡你看电脑。
            //
            // ★ 2026-10-04 晚从 200dp / 屏高三分之一 收下来的。他原话:
            //   「**桌面那个悬浮窗人形有点大**」。竖屏那条路原来落 600px(占屏高 25%),
            //   收完是 480px。**只动大小,不动那张图** —— 他另外说了
            //   「人形先不管,等跑通了换上最终皮肤了再说」,美术那部分不碰。
            hpx = minOf(dp(app, 80), (dm.heightPixels / 3.4f).toInt())
            wpx = (hpx * CONMARN_FIGURE_W / CONMARN_FIGURE_H).coerceAtLeast(dp(app, 24))
        } else {
            b = ImageView(app).apply {
                setImageResource(R.drawable.conmarn_avatar)
                scaleType = ImageView.ScaleType.FIT_CENTER
                // 圆钮上是**她的脸**([R.drawable.conmarn_avatar] 是预先裁好的圆形 PNG,
                // 带 alpha 遮罩 —— 缩小也不会露方角)。外面那圈粉色描边是她的「色」,
                // 脸在圈里,一眼就知道这是谁。
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(app, 2), 0xE6E05B8E.toInt())
                }
            }
            wpx = dp(app, 40)
            hpx = wpx
        }

        val lp = WindowManager.LayoutParams(
            wpx, hpx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // ★ 上次拖到哪儿就放回哪儿(没存过才用默认:右缘、约 1/3 屏高)。
            // clamp 是必须的 —— 屏幕转过方向 / 分辨率变过之后旧坐标可能整个在屏外,
            // 那症状是「她不见了」,而它其实还在,只是放在了看不见的地方。
            // ★ 这里上界用的是 **wpx/hpx 而不是圆球的 size** —— 用错的话人形会被
            //   钳到屏幕右下角外面去,而圆球时代留下的那段代码看不出这个错。
            val p = savedPos(app)
            x = (p?.first ?: (dm.widthPixels - wpx - dp(app, 6)))
                .coerceIn(0, (dm.widthPixels - wpx).coerceAtLeast(0))
            y = (p?.second ?: (dm.heightPixels / 3))
                .coerceIn(0, (dm.heightPixels - hpx).coerceAtLeast(0))
        }
        try {
            w.addView(b, lp)
        } catch (_: Exception) {
            return
        }
        bubbleView = b
        b.setOnTouchListener { _, e -> onBubbleTouch(app, lp, e) }
        // ★ 两个尺寸**一起打**。这不是啰嗦,是这条 bug 唯一的证据来源:
        //   「面板铺满全屏」只在 `app.resources.displayMetrics` 还停在横屏时发生,
        //   而那一瞬间人的眼睛只看到「又满了」,分不清是尺寸算错还是方向取错。
        //   两个数并排一放,`资源=` 和 `物理=` 一旦不一样,**就是这一条**,
        //   且能立刻看出我们用的是哪一个(用的是后面那个才是对的)。
        val raw = app.resources.displayMetrics
        trace(app, "出来(${if (figure != null) "人形" else "圆球"} ${wpx}x$hpx x=${lp.x} y=${lp.y} " +
                "物理=${dm.widthPixels}x${dm.heightPixels} 资源=${raw.widthPixels}x${raw.heightPixels})")
    }

    /** 已经在主线程上(由 [schedule] 投递),所以直接动 View,不再 post。 */
    private fun realHide() {
        appCtx?.let { trace(it, "收起") }
        removeBar()
        // ★ 垃圾桶窗口也要一起收 —— 它是**独立的一个窗口**,不跟着球走。
        //   漏了这句,拖动中被别的路径收起来(切前台、她进房间)会留一个
        //   NOT_TOUCHABLE 的圆浮在那儿,看得见、点不掉、也不报错。
        appCtx?.let { hideTrash(it) }
        bubbleView?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        bubbleView = null
        // ★ 位置**不清** —— 盘上存的是「他把她摆在哪了」,不是这个 View 的状态。
        //   收起来再放出来,她该还在他摆的那个地方。
    }

    // ------------------------------------------------------------------
    // 圆钮:拖动 / 点开
    // ------------------------------------------------------------------

    private fun onBubbleTouch(app: Context, lp: WindowManager.LayoutParams, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX; downRawY = e.rawY
                startLpX = lp.x; startLpY = lp.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (!dragging && (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12)) {
                    dragging = true
                    // ★ 垃圾桶**拖动中才出现**,平时不占地方 —— 它是个路标,不是常驻装饰。
                    //   放在这儿而不是 DOWN 上:点一下(开面板)不该闪一个垃圾桶出来。
                    showTrash(app)
                }
                if (dragging) {
                    lp.x = startLpX + dx.toInt()
                    lp.y = startLpY + dy.toInt()
                    try { wm?.updateViewLayout(bubbleView, lp) } catch (_: Exception) {}
                    updateTrashArmed(app, lp)
                }
            }
            // ★ CANCEL 也要收垃圾桶。系统随时可能打断一次拖动(来电、切前台、
            //   手势导航的边缘判定)—— 少收这一处,**垃圾桶会永远留在屏幕上**,
            //   而它是个 NOT_TOUCHABLE 的窗口,盖在那儿点不掉。
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.actionMasked == MotionEvent.ACTION_UP) {
                    if (!dragging) {
                        // 没拖动 = 点一下 → 展开/收起她的面板
                        toggleBar(app)
                    } else if (trashArmed) {
                        // ★ 红光晕亮着 = 这一下是「扔」。见 [dropToTrash]。
                        dropToTrash(app)
                    } else {
                        // 拖到别处 = 她停在那儿(和以前一样,记住新位置)。
                        savePos(app, lp.x, lp.y)
                    }
                }
                hideTrash(app)
                dragging = false
            }
        }
        return true
    }

    // ------------------------------------------------------------------
    // 拖到底部垃圾桶 = 关掉悬浮窗
    // ------------------------------------------------------------------

    /**
     * 拖动一开始,在**屏幕底部正中**放一个垃圾桶。
     *
     * ★ 它是个独立的悬浮窗口,而且**必须**带 `FLAG_NOT_TOUCHABLE`:
     *   手指已经按下去了,从那一刻起这一整串 MOVE/UP **全部**投给她的球那个窗口,
     *   垃圾桶只是被看着的。不设这个标志的话它会自己抢走事件,拖动会半路断掉 ——
     *   症状是「拖到一半她就不跟手了」,而且完全不报错。
     */
    private fun showTrash(app: Context) {
        val w = wm ?: return
        if (trashView != null) return
        val size = dp(app, 66)
        // ★★ 2026-10-05:**里面那个垃圾桶图标拿掉了** —— 用户原话
        //   「垃圾桶,等等,**就红色光晕就行**」。
        //   它现在只有一层背景(平时是一团很淡的冷白微光,热区里变成红色光晕),
        //   所以这一格不再是 `FrameLayout + ImageView`,就是**一个 View**。
        //   ★ 那两团光里**一处硬边都没有**:只要有一个硬边,它看起来就又变成一个图标了。
        val v = View(app).apply {
            background = app.getDrawable(R.drawable.bubble_trash_idle)
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(app, 28)
        }
        try { w.addView(v, lp) } catch (_: Exception) { return }
        trashView = v
        trashArmed = false
    }

    private fun hideTrash(app: Context) {
        trashView?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        trashView = null
        trashArmed = false
    }

    /**
     * 她**够得着**垃圾桶没有 —— 够得着就亮红光晕。
     *
     * ★★ 判据的写法是这个交互里唯一值得小心的地方。他原话:
     *   「到旁边会有红色光晕,**直到红色光晕出来,松开就能退出悬浮窗**」。
     *   重音在「直到」上 —— 光晕是**给眼睛看的承诺**:看得见光晕,松手就一定算数。
     *   所以这两个判断**必须是同一个**([trashArmed]),不能一份给显示、一份给落点。
     *   写成两份的话,迟早会出现「亮着红光晕、松手却没关」那种最让人恼火的情况。
     *
     * ★ 用的是**两个窗口中心的距离**,不是手指到垃圾桶的距离:
     *   她的球可能比她本人矮很多(人形是竖长的),用手指算的话,
     *   视觉上明明已经贴上了,判定却还没到。
     */
    private fun updateTrashArmed(app: Context, lp: WindowManager.LayoutParams) {
        // ★ 垃圾桶自己是用 Gravity.BOTTOM|CENTER_HORIZONTAL 摆的,所以它落在哪由系统算、
        //   永远是对的;但**热区**是拿算术算出来的 —— 用 App 的横屏尺寸算,
        //   目标点会跑到 x≈1187(竖屏 1080 之外),结果是「光晕永远不亮、松手也关不掉」。
        //   判据和显示共用一个 [trashArmed],错的是几何,那就一起错。
        val dm = realDm(app)
        val size = dp(app, 66)
        // 垃圾桶窗口的几何(gray = BOTTOM|CENTER_HORIZONTAL,y=28dp 是从底边往上)
        val tCx = dm.widthPixels / 2f
        val tCy = dm.heightPixels - dp(app, 28) - size / 2f
        val bCx = lp.x + lp.width / 2f
        val bCy = lp.y + lp.height / 2f
        // 热区半径 = 垃圾桶半径 + 她的一半宽度。**给得宽一点是故意的**:
        // 窄了会变成「明明拖上去了却没反应」,而那看起来像功能坏了。
        val hot = size / 2f + dp(app, 46)
        val armed = kotlin.math.hypot((bCx - tCx).toDouble(), (bCy - tCy).toDouble()) <= hot
        if (armed == trashArmed) return
        trashArmed = armed
        trashView?.background = app.getDrawable(
            if (armed) R.drawable.bubble_trash_armed else R.drawable.bubble_trash_idle
        )
        if (armed) {
            try {
                val vib = app.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    vib?.vibrate(android.os.VibrationEffect.createOneShot(22, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION") vib?.vibrate(22)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * 松手落在垃圾桶上 —— 她下去。
     *
     * ★ 走 [setEnabled](false) 而不是只 `realHide()`:他要的是「**不然一直在我桌面上**」,
     *   也就是**以后也别再自己冒出来**。只藏一次的话下次一进电脑页她又回来了,
     *   而那时他早就忘了自己扔过她 —— 那是最典型的「关了又开」。
     * ★ 想叫她回来:中转站面板里有「她的悬浮窗」那张卡片(见 MainActivity 的 toggleHerFloat)。
     */
    private fun dropToTrash(app: Context) {
        trace(app, "拖到垃圾桶 → 关闭悬浮窗")
        removeBar()
        setEnabled(app, false)
        toast(app, "好,我下去了。想叫我回来:中转站面板里那张「她的悬浮窗」")
    }

    // ------------------------------------------------------------------
    // 迷你面板
    // ------------------------------------------------------------------

    private fun toggleBar(app: Context) {
        if (barView != null) { removeBar(); return }
        buildBar(app)
    }

    /**
     * 底部那条玻璃输入条 —— **它就是「那个大面板」的全部替代品**。
     *
     * 用户 2026-10-04 晚原话:「那个大弹窗(有进她的房间,中转站那个大弹窗)**删掉去**」。
     * 旧的那个 45% 屏宽、装着大脸 + 名字 + 整段话 + 日志框 + 状态行 + 两颗大按钮,
     * 而这条只有**一行**:胶囊里一个输入框、一颗 🎤、一颗 ↑。
     *
     * ## 为什么它长得和房间里那条一模一样
     *
     * 同一个 [R.drawable.her_glass_pill]、同一种白字、同一颗圆箭头
     * ([R.drawable.btn_her_send])。这两条干的是**同一件事** —— 跟她说话 ——
     * 在同一部手机上长得不一样是没有道理的。**但键意不同**:房间里那条回车是
     * 「打到电脑的输入框里」,这条回车是「发给她」,所以那两条的 `imeOptions`
     * 是两回事,别顺手一起改。
     *
     * ★★ 2026-10-05:「一模一样」这句话在那天之前**只对了一半**,而用户一眼看了出来:
     *   「(桌面这条)然后也没有房间里那种光效的透明磨砂玻璃」。
     *   玻璃([R.drawable.her_glass_pill])确实是同一块,缺的是**它底下那层紫雾** ——
     *   现在它是 [col] 自己的背景([R.drawable.her_mist_float])。
     *   **同一个 drawable 不等于同一种样子**,半透明的东西长什么样,一半由它压在什么上决定。
     */
    private fun buildBar(app: Context) {
        val w = wm ?: return
        val pad = dp(app, 12)

        val col = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, dp(app, 12))
        }

        // ── ★★ 2026-10-05 新增:垫在玻璃底下那层光。
        //
        //   用户原话:「(桌面这条)**然后也没有房间里那种光效的透明磨砂玻璃**」。
        //   查下来玻璃本身就是**同一块**([R.drawable.her_glass_pill]),差的**不是玻璃,
        //   是它底下那层东西** —— 房间里垫着紫雾([R.drawable.her_mist]),而这条底下
        //   什么都没有。半透明的东西**压在什么上**才决定它像不像玻璃(见那两个 drawable
        //   的文件头):压在墙纸上,它就是一片看不出边界的白。
        //
        //   ★ 所以这一层不是"加个装饰" —— 他前面已经删过三次装饰了。
        //     这一层和房间里那层是同一件事:**给玻璃垫底**,删了玻璃立刻显脏。
        //
        //   ★★ **它是这一格的背景,不是一个包裹的外壳。** 我第一版写的是
        //      「套一层 FrameLayout,里面放一个 MATCH_PARENT 的雾」—— 那**正好是今天上午
        //      刚修掉的那个坑**(见 [SlotFrameLayout] 的文件头):`MATCH_PARENT` 的孩子
        //      在 `WRAP_CONTENT` 的爸爸里会**反过来定义爸爸有多高**,而那扇窗的高度规格
        //      正是 `AT_MOST(整屏)` —— 于是窗口会当场涨成满屏高,条子被顶到**屏幕最上面**去。
        //      写成背景就没有这个问题:背景不参与测量,孩子多高它就多高。
        col.background = app.getDrawable(R.drawable.her_mist_float)

        // ★★ 2026-10-06:这里原来是条子上方那行字幕(`herView`)——
        //   一个 13sp、最多三行、背景 `bubble_her` 的 `TextView`,条子重开时还会把
        //   `lastFinal`(她最近一句话)回显上去。**整块删了**,见文件顶部那段说明。
        //   于是 `col` 现在**直接**是胶囊本体,少一层。

        // ── 胶囊本体。α 从默认降到 0xCC(~80%)——这条条子不该抢她本人的注意力。
        val pill = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = app.getDrawable(R.drawable.her_glass_pill)
            background.alpha = 0xCC
            setPadding(dp(app, 6), dp(app, 4), dp(app, 6), dp(app, 4))
        }.also { barPill = it }

        // ── ★★ 2026-10-05:**一个壳,两个座位** —— 和房间里那条是同一个结构。
        //
        //   用户原话(这一整段都是为它写的):
        //     > 「我要的是**按住对话框说话**,不是右边弄个麦克风按钮…**点对话框**就会是
        //     >   打字模式,到打字模式后如果还想回语音输入模式,就**再点麦克风**…
        //     >   **松开对话框就是发送**…如果在语音输入的时候不想发送,就**往上滑**」
        //
        //   左边那一格轮着坐两个人:**语音态坐「按住说话,点一下打字」,打字态坐输入框**。
        //   ⚠️ 两个人**不能同时露着** —— 都是 `MATCH_PARENT` 宽、叠在同一个 FrameLayout 里,
        //     谁要是自己把可见性改回来,两段字**当场叠在一起**(房间里那条为这个立过
        //     [setBarTypingMode] 这个唯一开关,这里照抄)。
        //
        //   ★ 两格的**字号和边距必须一样**:不一样的话切态时胶囊会**跳一下高**,
        //     而那种抖是最伤手感的(房间里那条为同一个理由专门留了空行占位)。
        //   ★★ **这一格必须是 [SlotFrameLayout]**,不能是普通 FrameLayout ——
        //     底下那条 `MATCH_PARENT` 高的水波在普通 FrameLayout 里会**反过来定义
        //     这一格有多高**。房间里那条今天上午刚为同一个病根改过一次
        //     (见 [SlotFrameLayout] 文件头),悬浮条这一格是同一个形状,照抄。
        val slot = SlotFrameLayout(app)

        barHint = TextView(app).apply {
            text = HINT_HOLD
            textSize = 15f
            setTextColor(0xFFFDF2F6.toInt())
            setSingleLine(true)
            // 描边:白字压在浅色玻璃上,这是唯一的可读性来源(同房间那条)。
            setShadowLayer(2f, 0f, 1f, 0x99000000.toInt())
            setPadding(dp(app, 12), dp(app, 8), dp(app, 12), dp(app, 8))
        }
        slot.addView(barHint, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        barInput = EditText(app).apply {
            hint = "打字跟她说…"
            textSize = 15f
            setTextColor(0xFFFDF2F6.toInt())
            setHintTextColor(0xCC9A828D.toInt())
            // 玻璃里再套一个实心盒子 = 双下巴(房间里那条同理),所以底色去掉。
            background = null
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            setPadding(dp(app, 12), dp(app, 8), dp(app, 12), dp(app, 8))
            visibility = View.GONE
            setOnEditorActionListener { _, actionId, ev ->
                val enter = ev != null && ev.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                if (enter || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                    sendFromBar(app); true
                } else false
            }
        }
        slot.addView(barInput, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        // ── 基因序列:按住说话时那一格显示的东西(见 [VoiceWaveView])。
        //   ★ 和房间里那条是**同一个 View、同一份代码** —— 他 2026-10-05 把两处
        //     一起点了名(「它的房间**还有**悬浮窗的语音输入」),两处画得不一样
        //     是迟早会被看出来的。
        //   ★★ 高度 `MATCH_PARENT`:**上面那两格(mode 提示 / 输入框)撑着这一格的高度**,
        //     水波只是铺满它。这也是它进 [`SlotFrameLayout`] 的原因 ——
        //     在普通 FrameLayout 里,这一句会把这根胶囊撑成满屏。
        //   ★ 最后一个加进来 = 画在最上层;它不吃触摸(`onTouchEvent` 返回 false),
        //     手势照旧落到胶囊那个壳上(见 [onPillTouch])。
        barWave = VoiceWaveView(app).apply { visibility = View.GONE }
        slot.addView(barWave, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        pill.addView(slot, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // ── 🎤「回语音」:**只在打字那一态露面**(见 [barVoiceBtn])。
        //   ★ 刻意压在**最右边、发送键旁边**:打字那一态里他刚敲完/正要敲,
        //     视线和手指都在右半边;摆到左边的话他要先跨过整个输入框才够得着,
        //     而这一颗的全部意义就是「够得着」。
        pill.addView(ImageView(app).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(MIC_IDLE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val p = dp(app, 9)
            setPadding(p, p, p, p)
            contentDescription = "按住说话"
            visibility = View.GONE
            setOnClickListener {
                // 回到语音态。★ 键盘必须在这儿收干净 —— 只把 input 设成 GONE 的话,
                //   输入法不认账,会继续霸着屏幕下半截,表现是「按了没反应,
                //   只是输入框没了」,而那时整颗胶囊正好被键盘顶着,按不到。
                setBarTypingMode(app, false)
                trace(app, "🎤 回到按住说话")
            }
        }.also { barVoiceBtn = it }, LinearLayout.LayoutParams(dp(app, 40), dp(app, 40)))

        // ── ↑ 发送:和房间里那颗圆箭头同款(白实心圆 + **深色**字)。
        //   ★ 深色是因为它压在白圆上,浅色字会看不见 —— 全项目唯一一处深色字。
        barSend = TextView(app).apply {
            text = "↑"; textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(0xFF160F13.toInt())
            background = app.getDrawable(R.drawable.btn_her_send)
            contentDescription = "发送"
            setOnClickListener { sendFromBar(app) }
        }
        pill.addView(barSend, LinearLayout.LayoutParams(dp(app, 40), dp(app, 40)))

        // ── 手势:这一整块是**语音态的入口**
        //   ★ 接在壳上,所以**除了那颗箭头和输入框**,胶囊上任何一处都能按住说话 ——
        //     那两颗是 ViewGroup 的孩子,触摸先给他们,他们不吃才轮到壳。
        pill.setOnTouchListener { _, e -> onPillTouch(app, e) }

        col.addView(pill, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── 窗口 flags:★★ **这一整条链上唯一的新风险,写详细。**
        //
        // 和球那扇窗不同:那扇窗是 `FLAG_NOT_FOCUSABLE`(它不需要键盘),
        // 而这扇窗**必须能拿焦点**,否则输入法根本不弹。
        // `FLAG_NOT_FOCUSABLE` 和输入法是互斥的 —— 这是原来那个面板
        // 「刻意不接键盘」那句话的代价,这次决定付。
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            //  · NOT_TOUCH_MODAL:条子以外的地方**照旧能点**。少了它,这扇全屏宽的
            //    窗口会把下面一整块屏吃掉 —— 症状是「她的条子一出来,底下什么都点不动了」,
            //    而那条子**看起来**只占最下面一条,极难联想到是它。
            //  · WATCH_OUTSIDE_TOUCH:点到别处自己收起来。少了它,条子会一直挂着,
            //    而他没有任何一处能想到要去关它(球再点一下也行,但没人会去试)。
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            // ★ 让**窗口管理器**把条子抬到键盘上面去。
            //   另一种写法是「监听 insets 自己减 translationY」——**两者不能一起用**,
            //   会叠加,条子被顶到屏幕中间去。选 WM 的理由:键盘多高在不同机型/不同
            //   输入法下都不一样,那个数我们自己算不准,而 WM 本来就知道。
            //
            // ★★ 2026-10-05:`STATE_ALWAYS_VISIBLE` **删掉了**,不是漏写。
            //   条子现在**升起来就是语音态**(第一层是「按住说话」,打字要点一下),
            //   而 ALWAYS_VISIBLE 的意思是「这扇窗一拿到焦点就弹键盘」——
            //   留着它的话,条子刚出来键盘就顶上来,把「按住说话」那第一层顶没了一半,
            //   而这正是他要改掉的那个形状。
            //   键盘改由 [setBarTypingMode] 在**真的进打字态**时**主动叫**一次。
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        try {
            w.addView(col, lp)
        } catch (_: Exception) {
            return
        }
        barView = col

        // ★★ **它有多高,要有据可查。** 2026-10-05 用户报过一次很危险的故障:
        //   > 「她现在悬浮窗对话框(透明磨砂玻璃)**莫名其妙到最顶上了**,原来是最底下,
        //   >   现在这么一搞,就**直接不能操作任何屏幕**了,非常的危险」
        //   真因就是这一格被撑成满屏高(见上面 `col.background` 那段注释),
        //   而那扇窗是 `NOT_TOUCH_MODAL` 的全屏窗 —— 屏幕上**没有一处点得动**,
        //   连「点别处收起来」都收不到(手指根本没落在窗口外面过)。
        //   一行日志是这种故障唯一的证据:它不抛异常、不报错,只表现成「手机死了」。
        main.postDelayed({
            val h = col.height
            if (h <= 0) return@postDelayed
            val screen = realDm(app).heightPixels
            if (h > screen / 3) {
                trace(app, "✗✗ 条子高 ${h}px(屏 ${screen}px)—— 它本该一两百像素。" +
                        "这行说明那条子又铺满屏了,它会挡住整个手机")
            } else {
                trace(app, "条子高 ${h}px(屏 ${screen}px)")
            }
        }, 250L)

        // 点条子外面的地方 = 收起来(配合上面那个 WATCH_OUTSIDE_TOUCH)。
        // ★ 它必须挂在**窗口根**上(= col 自己)。
        //   `ACTION_OUTSIDE` 的坐标在窗口**外面**,永远落不到窗口内某个孩子的范围内,
        //   挂在孩子上就收不到 —— 症状是「点了别处,条子赖着不走」。
        col.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { removeBar(); true } else false
        }

        // ★★ **升起来是语音态,不是打字态** —— 所以这里**不叫键盘**。
        //   第一层永远是「按住这块玻璃说话」,打字要点一下(见 [onPillTouch])。
        //   在这儿叫键盘的话,条子一出来键盘就顶上来,他要的那个第一层就没了。
        setBarTypingMode(app, false)

        refreshStatus()
        // ★ 2026-10-06:原来这句尾巴上挂着「她最近一句=有/无」——那行字幕删了,
        //   这个尾巴也跟着没意义了(再留着就是「日志说的 ≠ 界面上有的」)。
        trace(app, "输入条升起")
    }

    // ==================================================================
    // 那块玻璃上的手指(2026-10-05)
    //
    // ★★ 这一整段是**房间里那套手势搬过来的**,判据的算法共用 [BarGestureMath],
    //    状态各存一份(两条玻璃各归各的窗口,不可能共享一个手指)。
    //
    //   同一个手指上有**四件事**,用户 2026-10-05 一条一条说的:
    //     · 「按住对话框说话」          → 长按 = 开麦,松手 = 我说完了
    //     · 「松开对话框就是发送」      → 松手当场收尾转写([Ear.finishNow])
    //     · 「点对话框(透明玻璃)就是打字模式」→ 短按 = 进打字那一态
    //     · 「不想发送就往上滑」        → 上滑 = 取消,**连转写都不做**
    //
    //   按下的瞬间判不出来他想干哪一件 —— 只有松开时才知道这一下是长是短、走没走。
    //   所以:**按下先开麦(手感),但界面上一声不吭**(见 [confirmHold]),松开再分。
    // ==================================================================

    /**
     * 「点一下」落在哪儿,**看她的嘴张没张** —— 和房间里用的是同一条判据:
     *   · 她在说 → **打断她**(把刚才那几百毫秒的录音丢掉)
     *   · 她安静 → **进打字那一态**([setBarTypingMode])
     *
     * ★ 判据选「她当时在不在说话」是唯一不别扭的一条:她安静的时候你想打字,
     *   她正在说的时候你想的第一件事永远是让她停。
     *   ⚠️ 已知的窄缝(房间里那条也有,是故意的):她**在想**(还没出声)的时候
     *     点一下会去打字。那种时候你想说下一句比想打字多,而打字那条路
     *     只在「她安静」时才通,不挡着谁。
     */
    private fun onPillTouch(app: Context, e: MotionEvent): Boolean {
        when (e.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                if (pressDown) return true                      // 重复 DOWN 直接吞掉
                buzz(app)
                // ★ 打字态下这块玻璃是**输入框**,不是手势靶子 —— 交回给它自己。
                //   不挡这一下的话,他每次把光标点到输入框里都会先开一次麦。
                if (barTyping) return false
                // ★★ 没有耳朵的时候,这一整套手势(按住说话 / 上滑取消)一件也没有意义 ——
                //   当场改道去打字。`pressDown` 保持 false,于是 UP 那一支开头的
                //   `if (!pressDown) return true` 会把后面所有分支一起吃掉,
                //   不会有人再跑去 `Ear.finishNow()` 或者点亮「在听」。
                if (!canListen(app)) {
                    setBarTypingMode(app, true)
                    return true
                }
                pressDown = true
                pressStartedAt = System.currentTimeMillis()
                pillDownRawY = e.rawY
                swipeCancelled = false
                // 记下「按之前是不是就在聊」——「点一下」要不要留在这场对话里,靠它判。
                sessionBeforePress = voiceOn
                // ★ 同时记下「按下去那一刻她是不是在念」。半双工闸会在下面那一次
                //   [startEarTurn] 里当场把她停掉，所以到松手时已经问不出来了。
                spokeAtPress = HerVoice.isSpeaking()
                if (!voiceOn) {
                    voiceOn = true
                    silenceSince = System.currentTimeMillis()
                }
                startEarTurn(fromHold = true)
                main.removeCallbacks(confirmHold)
                main.postDelayed(confirmHold, EarSessionMath.TAP_MAX_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!pressDown) return true
                val threshold = dp(app, BarGestureMath.CANCEL_SWIPE_DP).toFloat()
                if (!BarGestureMath.entersCancelZone(pillDownRawY, e.rawY, threshold)) return true
                if (swipeCancelled) return true                 // 已经取消了,别重复喊
                swipeCancelled = true
                // ★ 当场把话掐掉:这一轮**不再转写**。只置标志是不够的 ——
                //   那只是「送出去之后不处理」,而这条规矩要的是**根本不做转写那几秒的活**。
                if (Ear.isListening) Ear.cancel()
                main.removeCallbacks(confirmHold)
                setListeningUI(false)
                setCancelGlow(app, true)
                setSlotText(HINT_SWIPE_CANCEL)
                // ★ 2026-10-06:这段原来还解释「为什么不去动上面那一行(herView)」——
                //   那行字幕整块删了,于是回执天然只剩两块,**更干净**:
                //   **胶囊里这行字** + **整块玻璃红了**([setCancelGlow])。
                trace(app, "上滑取消,松手这一句就作废")
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!pressDown) return true
                pressDown = false
                // ★★ 红光晕**绝不越过这一次手势**(和房间里那块玻璃同一个做法):
                //   手指一松,不管落在哪一支,先熄掉。放在这儿而不是各支里各写一遍,
                //   是因为漏一支的后果是「玻璃红着,但松手已经不算取消了」——
                //   那是界面在撒谎,而且红着的时候他不敢说话。
                setCancelGlow(app, false)
                main.removeCallbacks(confirmHold)
                val held = System.currentTimeMillis() - pressStartedAt
                pressStartedAt = 0L
                when (BarGestureMath.release(swipeCancelled, held)) {

                    BarGestureMath.Release.CANCEL -> {
                        // 录音在 MOVE 那一步就已经掐了,这里只收界面。
                        swipeCancelled = false
                        roundCancelled = true       // 这是「他自己不要的」,不是「没人说话」
                        setCancelGlow(app, false)
                        setListeningUI(false)
                        restSlot()
                        if (!sessionBeforePress) endBubbleVoice(app, null)
                    }

                    BarGestureMath.Release.TAP -> {
                        roundCancelled = true       // 这一轮不算「没人说话」,见它的注释
                        if (Ear.isListening) Ear.cancel()
                        // ★★ 2026-11-08（体检 A2）：`spokeAtPress` 也要算进来 ——
                        //   半双工闸在按下那一刻已经把她停了，这里的 `isSpeaking`
                        //   到松手时是 false，只认它就会把「别说了」判成「我要打字」。
                        val wasSpeaking = HerVoice.isSpeaking() || spokeAtPress
                        spokeAtPress = false
                        if (wasSpeaking) {
                            // 她在说 → 这一下是「别说了」。
                            HerVoice.stop()
                            setListeningUI(false)
                            restSlot()
                            if (!sessionBeforePress) endBubbleVoice(app, null)
                        } else {
                            // 她没在说 → 这一下是「我要打字」。
                            // ★ 无条件收工:打字和开麦是**互斥**的两态 ——
                            //   键盘响着、麦克风还开着,那不只是浪费,
                            //   是**你打字的声音会被她当成话听进去**。
                            endBubbleVoice(app, null)
                            setBarTypingMode(app, true)
                        }
                    }

                    BarGestureMath.Release.HOLD -> {
                        // ── 松手 = 我说完了
                        roundCancelled = false
                        Ear.finishNow()
                        // ★ 波形/在听收工,换成「在认」这一句(**不能就这么空着**)。
                        //   松手到定稿之间还有一两秒(VAD flush + 转写),而那两秒里
                        //   他刚刚说完一句最重要的话 —— 屏幕上什么都没有的话,
                        //   看起来就像白说了,然后他就会再说一遍。
                        setListeningUI(false)
                        setSlotText(HINT_DECODING)
                    }
                }
                return true
            }
        }
        return false
    }

    /**
     * 进 / 出**打字那一态**。胶囊左边那格是「提示」和「输入框」轮流坐的,
     * 这个函数是**唯一**的换人开关。
     *
     * ★★ 为什么必须只有一个开关:两格都是 `MATCH_PARENT` 宽、叠在同一个
     *   `FrameLayout` 里。谁要是自己去 `barHint.visibility = VISIBLE`(而输入框
     *   还露着),两段字**当场叠在一起** —— 看起来像花屏,不像 bug。
     *
     * ★ 打字不是「兜底」,是**另一条正经的路**:
     *   地铁上、图书馆里、她旁边有人 —— 那些时候你不能说话,但你还想跟她说话。
     */
    private fun setBarTypingMode(app: Context, on: Boolean) {
        barTyping = on
        barHint?.visibility = if (on) View.GONE else View.VISIBLE
        barInput?.visibility = if (on) View.VISIBLE else View.GONE
        // ★ 打字态才露出「回语音」那颗。语音态整块玻璃就是手势靶子,
        //   那时它反而是一块压在手该落下去的地方上的补丁(见 [barVoiceBtn])。
        barVoiceBtn?.visibility = if (on) View.VISIBLE else View.GONE
        // 取消的红玻璃跟着清 —— 切态时留着它,他会以为还在取消区。
        setCancelGlow(app, false)
        restSlot()
        if (on) {
            barInput?.requestFocus()
            // 键盘不会在同一个消息里就弹出来(布局还没走完),让一帧再叫。
            main.postDelayed({
                val v = barInput ?: return@postDelayed
                try {
                    (app.getSystemService(Context.INPUT_METHOD_SERVICE)
                            as? android.view.inputmethod.InputMethodManager)
                        ?.showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                } catch (_: Exception) {}
            }, 120L)
        } else {
            try {
                (app.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager)
                    ?.hideSoftInputFromWindow(barInput?.windowToken, 0)
            } catch (_: Exception) {}
            barInput?.clearFocus()
        }
    }

    /** 语音那一格写的字。**只在语音态有意义** —— 打字态那一格坐的是输入框。 */
    private fun setSlotText(t: String) {
        hideWave()
        barHint?.let { it.text = t; it.visibility = View.VISIBLE }
    }

    /** 那一格回到「按住说话,点一下打字」。 */
    private fun restSlot() {
        hideWave()
        barHint?.let { it.text = HINT_HOLD }
    }

    /**
     * 按住了:那一格换成**流动的基因序列**([VoiceWaveView])。
     *
     * ★★ **提示文字不能 GONE,只换成空格。** 这一条不是美观,是安全:
     *   [SlotFrameLayout] 靠「一个老实孩子」算这一格多高,而水波是 `MATCH_PARENT` 高的 ——
     *   提示一旦 GONE,这一格就**一个老实孩子都没有**,`SlotMeasure` 会回
     *   [SlotMeasure.NO_OPINION],那时只能退回原生量法,水波拿到的是「爸爸还剩多少」,
     *   这根胶囊**当场涨成满屏**。那正是 2026-10-05 那次「整个手机点不动」。
     *   一个空格仍然占一整行高,所以格高纹丝不动 —— 顺带也满足「按住时胶囊不跳高」。
     */
    private fun showWave() {
        barHint?.let { it.text = " "; it.visibility = View.VISIBLE }
        barWave?.start()
    }

    /** 收掉水波。**幂等**,而且不碰提示的可视性 —— 谁接管那一格由调用方决定。 */
    private fun hideWave() {
        barWave?.stop()
    }

    /**
     * 那块玻璃**红了** = 现在松手这句话就作废。
     *
     * ★★ 用户 2026-10-05 原话:
     *   > 「他的房间,和悬浮窗,**往上滑还是不要有箭头**。垃圾桶,等等,
     *   >   **就红色光晕就行**」
     *
     *   在这之前这件事是用**字符**说的(房间里那句提示前面顶着一个 `↥`)——
     *   一个箭头说的是「这是什么方向」,而这里要答的是「**你现在松手会怎样**」。
     *   光答得快,而且不需要他读字。红色取的是和垃圾桶红光**同一个色号**:
     *   同一个意思在同一个屏幕上用两种红,是没有道理的。
     *
     * ★ 它换的是胶囊**自己的背景**([R.drawable.her_glass_pill_cancel])——
     *   那张图比普通那块多了一层从中心散出去的红,而玻璃本身照旧画在上面,
     *   所以看起来是**这块玻璃自己在发红**,不是贴上去一块红纸。
     *   ⚠️ 别改成「套一层外壳再画一层光」:那正是 2026-10-05 那个
     *     「条子涨成满屏、整台手机点不动」的故障的写法(见 [buildBar] 里那段)。
     */
    private fun setCancelGlow(app: Context, on: Boolean) {
        barPill?.background = app.getDrawable(
            if (on) R.drawable.her_glass_pill_cancel else R.drawable.her_glass_pill
        )
    }

    // ★ 2026-10-06:`showHerLine()` 删了 —— 它本来就是个 no-op(唯一一句是把那行字幕
    //   置 GONE),而那行字幕今天整块没了,连座位一起拆掉,函数没有存在的理由。

    /**
     * 能不能真的开麦。**不能就当场说清楚** —— 三条路各说各的,不装作在听。
     *
     * ★★ 权限那条**必须退回房间**:悬浮窗手里只有 **application context**,
     *   它弹不出系统权限框。所以没给权限时把房间拉起来,由 Activity 去要。
     *   这条路留着是对的 —— 它正是以前那条路,只是从此只在「还没给过权限」时走一次。
     */
    private fun canListen(app: Context): Boolean {
        if (app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            removeBar()
            try {
                ConMarnActivity.pendingVoiceSession = true
                app.startActivity(
                    Intent(app, ConMarnActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                toast(app, "第一次得先给麦克风权限 —— 我把房间打开了")
            } catch (_: Exception) {
                // 拉不起来就把标志清掉 —— 留着的话**下一次**进房间她会突然开麦,
                // 而那时他早就忘了自己点过什么。这种「隔一次才发作」最难查。
                ConMarnActivity.pendingVoiceSession = false
            }
            return false
        }
        // ★ 模型没装就**明说**,别让她「在听」着却一个字都听不见 ——
        //   那两种情况在屏幕上长得一样,而他能做的补救完全不同。
        if (!Ear.available(app)) {
            // ★ 2026-10-06:原来这句写在上方那行字幕上,字幕删了 → 改由 Toast 说
            //   (不变量「不许静默失败」),而且**把缺哪几件带上** ——
            //   只喊「听不见」而不说缺什么,他不知道去补哪一件。
            val miss: String = Ear.missingPieces(app).joinToString("、")
            toast(app, "我还听不见 —— 识别模型没装:$miss")
            return false
        }
        return true
    }

    /** 按下的那一下回执。★ 一次 18ms —— 和房间那颗 `buzz()` 同一个数,手感要一致。 */
    private fun buzz(app: Context) {
        try {
            val vib = app.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vib?.vibrate(android.os.VibrationEffect.createOneShot(18, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vib?.vibrate(18)
            }
        } catch (_: Exception) {}
    }

    /**
     * 把条子里那句话交给她。**和房间里是同一个口**([AiAgent.start]),
     * 所以人格、历史、心情、记忆全是同一份 —— 从哪儿说进去不影响她是谁。
     */
    private fun sendFromBar(app: Context) {
        val v = barInput ?: return
        val t = v.text.toString().trim()
        if (t.isEmpty()) return
        v.setText("")
        // ★★ 2026-10-06 和房间同一条改法(用户:「悬浮球所有改法和房间里一样」):
        //   他发新消息时,她正在说话 → **立刻闭嘴**,新的一句顶上去。
        //   原来是 `busy` 就弹「上一句还在办」直接拒掉 —— 那是「打断不了她」的老病:
        //   [AiAgent.start] 现在有交接机制(旧一轮收到取消、新一轮接上),
        //   所以这里不再拒绝,只管把正在响的那副嗓门先掐了。
        if (HerVoice.isSpeaking()) HerVoice.stop()
        // ★ 2026-10-06:原来这儿把他打的字回显到上方那行字幕(「你:$t」),
        //   字幕整块删了 —— 他自己的话就交给条子里的输入框本身(它一直显示着他刚打的字)。
        // ★★ 2026-10-05:这一句修的是「**桌面好像说话没有声音**」。
        //   在那之前只有 [sendSpoken](说话那条路)会置这个位 —— 于是**打字问她她只写字**,
        //   而房间里同一个动作她是念的(`onFinal → say`)。两副嗓子行为不一样,
        //   错的是这一条:**他问了她,她就该出声,不管他是打的还是说的。**
        //   判据和「要不要开麦」是两件事,见 [BubbleSpeech]。
        speakThisReply = true
        // ★ 留一句痕迹说明这一轮是**打字**来的。少了它,事后读日志分不出
        //   「他打的字」和「他说的话」—— 而这两个的故障原因完全不同
        //   (打字那条不走耳朵)。20 字截断:够认出是哪一句,又不至于把整段抄进日志。
        trace(app, "条子上打字:${t.take(20)}")
        AiAgentHolder.get(app).start(t, bubbleCallbacks(app))
    }

    /**
     * 从这条条子发起的那些回调。
     *
     * ★ 四个口**故意全空**:[AiAgent.start] 里那层 tee 已经把它们转给
     *   [ConMarnBubble.onLog] / [onFinal] / [onError] / [onBusy] 了(见 AiAgent.kt),
     *   那儿也是房间/主界面发起时走的路 —— 在这儿再写一遍就是**同一份显示逻辑两份实现**。
     *   这里只补 tee **没有**覆盖的那个口:[onConfirm]。
     */
    private fun bubbleCallbacks(app: Context) = object : AiAgent.Callback {
        override fun onLog(msg: String) {}
        override fun onTrace(msg: String) {}
        override fun onFinal(msg: String) {}
        override fun onError(msg: String) {}
        override fun onBusy(busy: Boolean) {}
        override fun onConfirm(summary: String, detail: String): Confirm =
            confirmOverlay(app, summary, detail)
    }

    /**
     * 「要点确认」的框 —— 从**悬浮条**发起时必须自己画一个。
     *
     * ★★ 这里的 Context 是 **application**,不是 Activity。直接把 `AlertDialog.show()`
     *   画上去会在那一刻抛 `BadTokenException` —— 而且是在后台线程上抛,这一轮直接失败。
     *   他看到的是「她说要做,然后就没下文了」,而日志里那行异常**看起来像网络问题**。
     *
     * ★ 没有一种不确定的状态是「同意」(fail closed):拿不到浮层权限、弹不出来、超时 60 秒,
     *   全部落在「不做」那一边。这是安全边界那一侧 —— [AiAgent.Callback.onConfirm] 的
     *   存在本来就是「不可逆的动作要人点头」,那我们这边的不确定就只能是「不做」。
     *
     * ★★ **但它们不再塌成同一个 `false`**(2026-10-05)。「没有浮层权限」和「他点了取消」
     *   是不同的两件事:前者要他去设置里开一下,后者是他的选择,回执说错就是替用户说话。
     *   判「能不能做」的仍然是 [ConfirmMath.allowed] —— 只有 [Confirm.APPROVED] 算数。
     */
    private fun confirmOverlay(app: Context, summary: String, detail: String): Confirm {
        // ★ 这条路**连框都建不出来**,不用等到超时:权限是当场就能问的。
        if (!Settings.canDrawOverlays(app)) return Confirm.NO_UI
        val latch = java.util.concurrent.CountDownLatch(1)
        val ok = booleanArrayOf(false)
        val built = java.util.concurrent.atomic.AtomicBoolean(false)
        main.post {
            try {
                val d = android.app.AlertDialog.Builder(app, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(summary)
                    .setMessage(detail)
                    .setPositiveButton("确认") { _, _ -> ok[0] = true; latch.countDown() }
                    .setNegativeButton("取消") { _, _ -> latch.countDown() }
                    .setCancelable(false)
                    .create()
                // ★★ `setType` 必须在 `show()` **之前** —— 反过来的话窗口已经建好了,
                //   类型改不动,`show()` 那一下照样抛。
                d.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                d.show()
                built.set(true)
            } catch (_: Exception) {
                latch.countDown()
            }
        }
        val answered = try {
            latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
            return Confirm.INTERRUPTED
        }
        return when {
            !built.get() -> Confirm.NO_UI
            !answered -> Confirm.NO_ANSWER
            ok[0] -> Confirm.APPROVED
            else -> Confirm.DENIED
        }
    }

    private fun removeBar() {
        // ★★ 2026-10-05:**条子要没了 —— 麦克风先关。**
        //   这一句必须在最前面,而且**不能**交给调用方去记得调:
        //   [removeBar] 有四个进处(点球、点条外、说话时点别处、房间进前台),
        //   漏一个的后果是**麦克风开着而屏幕上什么都没有** ——
        //   那不是难看,那是隐私那条线上唯一不能犯的错。
        //   ★ 顺带把「点别处 = 结束对话」这条手势也白捡了,而且它是唯一不骗人的做法:
        //     条子收起来之后没有任何地方能显示「我在听」,那就必须真的不在听。
        appCtx?.let { if (voiceOn) endBubbleVoice(it, null) }
        // ★ 先收键盘**再**拆条子:窗口一撤,`windowToken` 就没了,
        //   而 `hideSoftInputFromWindow(null, 0)` 是**静默无效**的 ——
        //   症状是「条子收起来了,键盘还杵在屏幕上」。
        try {
            (appCtx?.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(barInput?.windowToken, 0)
        } catch (_: Exception) {}
        barView?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        barView = null
        barInput = null; barSend = null
        barPill = null; barHint = null; barVoiceBtn = null
        // ★ 手势那几件内存态**一起归零**:条子都没了,残留的 `pressDown=true`
        //   会让下一根条子一上来就以为手指还按着。
        barTyping = false
        pressDown = false
        swipeCancelled = false
        main.removeCallbacks(confirmHold)
    }

    // ==================================================================
    // 就在这条条子上跟她说话(2026-10-05)
    //
    // ★★ 这一段是房间里那套对话循环([ConMarnActivity] 的 toggleEar / startEarTurn /
    //    armVoiceResume / earCallbacks / endVoiceSession)**搬过来的**,
    //    不是重写的。形状一模一样,理由也一样:
    //
    //        开麦听 → 听到了 → 送进脑子 → 她在想 → 她答 → 她念
    //           ↑                                              ↓
    //           └──── +300ms 冷却 ←──── TTS onDone(半双工闸在这儿)
    //
    //    **半双工闸**(她说话时麦克风必须关)是这一整套里最要紧的一条:
    //    不关的话扬声器出来的声音绕回自己的麦,变成一条「用户输入」,
    //    而它看起来是一句语法通顺、上下文合理的话 —— 她自问自答,日志上看不出破绽。
    //
    // ★★ 2026-10-05:开头那句「和房间不一样」**不再成立了** —— 按住说话那个手势
    //    已经搬过来了(见 [onPillTouch])。两处现在真的是同一套东西:
    //    同一块玻璃、同一个手指、同一套判据([BarGestureMath])、同一条 45 秒规则。
    // ==================================================================

    /**
     * 收工。
     *
     * ★ 顺序和房间里一样,**先落 [voiceOn] 再动别的** —— 反过来会有一个窗口让
     *   [resumeListening] 挤进来再开一次麦,而那正好落在「已经说好不聊了」之后。
     */
    private fun endBubbleVoice(app: Context, farewell: String?) {
        if (!voiceOn) return
        voiceOn = false
        main.removeCallbacks(resumeListening)
        main.removeCallbacks(confirmHold)
        pressDown = false
        swipeCancelled = false
        if (Ear.isListening) Ear.cancel()
        HerVoice.stop()
        // ★★ 2026-11-08(体检 A3/E6)：对话收工 = 撤掉那块「语音对话进行中」的牌子。
        //   不撤的话它会一直挂着，而麦克风其实早就不听了 —— 和唤醒那条一样的谎。
        EarFgService.stop(app)
        setListeningUI(false)
        setCancelGlow(app, false)
        restSlot()
        trace(app, "条子上收工")
        if (farewell != null) HerVoice.speak(app, farewell, null)
    }

    /**
     * 开麦听这一轮。会话中会被反复调用([armResume])。
     *
     * @param fromHold 这一轮是不是**他手指按着**开的。
     *   ★ 它改的是「这一轮没听到东西之后该怎么办」,不是怎么录:
     *     按着开的 → 松手就是「我说完了」,这时**一个字节都没收到**的正确动作是
     *     **把麦关掉、别再自己开回来**(用户原话:「我手按住字幕,和说完话后 45 秒,
     *     **其他不要开麦克风**」)。自动那几轮才是「45 秒走完了才收工」。
     */
    private fun startEarTurn(fromHold: Boolean) {
        val app = appCtx ?: return
        if (!voiceOn || Ear.isListening) return
        // ★★ 2026-11-08(体检 A2):**半双工闸 —— 她还在念的时候绝不开麦。**
        //
        //   这条线上原来没有这道闸:按住胶囊时她若正在说话,`Ear.listen()` 会当场
        //   把扬声器出来的声音录回麦克风 —— 那一段会被当成「他说的」送去识别,
        //   于是她**自问自答**,日志干净、一次报错都没有。
        //   (房间那条路一直有这道闸,见 `ConMarnActivity.herVoiceBusy`;这一条是补上的。)
        //
        //   做法和房间完全一样:**先让她停**,留一拍 [EarSessionMath.COOLDOWN_MS] 再开麦
        //   —— 喇叭里还有尾音,立刻开麦会把最后半个字听成新输入。
        //   ★ 重排时**带住原来的 fromHold**:按住说话那一下的语义不能因为多等 300ms 就变。
        if (HerVoice.isSpeaking()) {
            trace(app, "条子:她还在念 —— 先让她停,${EarSessionMath.COOLDOWN_MS}ms 后再开麦")
            HerVoice.stop()
            main.postDelayed({ startEarTurn(fromHold) }, EarSessionMath.COOLDOWN_MS)
            return
        }
        heardThisTurn = false
        turnFromHold = fromHold
        swipeCancelled = false
        // ★ 每一轮都要清。「上一轮是被手指掐掉的」这件事**绝不能漏到这一轮** ——
        //   漏过来的话,这一轮他真的什么都没说时,我们就会以为「他是掐的」,
        //   于是**再也不会自己收工**,麦一直开着。那是隐私那条线上的事。
        roundCancelled = false
        setListeningUI(true)
        // ★★ 2026-11-08(体检 A3/E6)：**先挂「麦克风资格」，再开麦。**
        //   悬浮球是 overlay 不是 Activity —— 屏幕上只有这颗球时，系统把本 App 当后台，
        //   `AudioRecord` 不报错、只回一耳朵零。mic 型前台服务是唯一能让它真收音的形态。
        //   ★ 这个类早就写好了，但**从来没被声明、也从来没被调用过**（死类）；
        //     这一行是它第一次真正上岗。起不来时它会自己 trace 并收手，见 [EarFgService]。
        //   ★ 幂等：一轮对话里每次开麦都会调到，服务已经在跑时只是再投一次 intent。
        EarFgService.start(app)
        Ear.listen(app, earCallbacks)
        // ★ 灯只在**真的开起来了**才留着亮。[Ear.listen] 会在两种情况下原地打回、
        //   一个字节都不录(麦被推流占着 / 模型没装),那时 `isListening` 仍是 false。
        //   判据用 `isListening` 而不是去认 onNotice 的文案 —— 文案是给人看的,
        //   以后加新失败原因时它不会跟着改,而界面在替她撒谎正是这条线上最坏的 bug。
        if (!Ear.isListening) setListeningUI(false)
    }

    /**
     * 排下一次「放麦」。
     *
     * ★ **重排不是追加**:`removeCallbacks` 必须在 `postDelayed` 之前。
     *   少了那一句,「她念完」和「兜底」会各排一次,于是每聊一轮就多欠一次开麦 ——
     *   聊十轮之后你一停下,她会连着听十次。
     */
    private fun armResume(delayMs: Long) {
        if (!voiceOn) return
        main.removeCallbacks(resumeListening)
        main.postDelayed(resumeListening, delayMs)
    }

    /**
     * 「我在听」这盏灯。**只有这一个落点** —— 所有开麦/关麦的地方都走它,
     * 免得出现「灯亮着、麦是关的」那种界面在撒谎的样子。
     */
    private fun setListeningUI(on: Boolean) {
        if (on) {
            // ★★ 那一格**在画面上说的那句话**是「麦克风开着」——
            //   就是那条流动的基因序列(见 [showWave])。以前这里是「🎤 在听…」一行字,
            //   他 2026-10-04 点过一次名要换成会流的波,2026-10-05 又给房间和这里
            //   一起指定了形状。
            // ★ 2026-10-06:那行「🎤 在听…(点别处 = 结束)」随字幕一起删了。
            //   结束的办法一个字没变,而且**不用猜**:[removeBar] 收条子时
            //   **一定**会 `endBubbleVoice`(见那儿那段注释 —— 「条子收起来之后没有任何
            //   地方能显示『我在听』,那就必须真的不在听」)。所以点别处照旧有效。
            showWave()
        } else {
            hideWave()
        }
    }

    /** 他**说出来**的一句话 —— 送进脑子。和打字那条路是同一个口([AiAgent.start])。 */
    private fun sendSpoken(t: String) {
        val app = appCtx ?: return
        // ★★ 2026-10-06 和房间同一条改法:他开口时她正在说话 → **立刻闭嘴**。
        //   (半双工闸本应让她说着时麦是关的,这里是兜那道闸的缝。)
        //   原来 `busy` 就弹「上一句还在办」并 2 秒后再听 —— 那是「打断不了她」的老病,
        //   [AiAgent.start] 的交接机制接得住新一轮,这里只管先把嗓门掐了。
        if (HerVoice.isSpeaking()) HerVoice.stop()
        // ★ 2026-10-06:他说的话原来会回显到上方那行字幕(「你:$t」)——字幕整块删了。
        //   说话这条路本来就不该靠字幕确认(他自己听得见自己说了什么)。
        // ★ 这一轮是**说**出来的,所以她答完要念出来 —— 见 [speakThisReply]。
        speakThisReply = true
        AiAgentHolder.get(app).start(t, bubbleCallbacks(app))
    }

    /**
     * 耳朵的回调。**它们从 `ear` 那条后台线程来** —— 一律 post 回主线程再碰 View。
     */
    private val earCallbacks = object : Ear.Callback {

        // ⚠️ 这四条**必须用块体**(`{ main.post { … } }`)而不是表达式体
        //   (`= main.post { … }`)—— `Handler.post` 返回 `Boolean`,表达式体会把
        //   覆写函数的返回类型推成 Boolean,而接口上是 Unit,编译直接不过。
        //   (房间里那几条用的是 `runOnUiThread`,它回 void,所以那边没这个问题。)

        /** 他正在说的那半句。**绝不进脑子**,只贴给他看(同房间那条)。 */
        override fun onPartial(text: String) {
            main.post {
                if (!voiceOn || heardThisTurn || text.isBlank()) return@post
                // ★★ **她听见了** —— 那一格的细线换成基因序列。用户 2026-10-05 原话:
                //   「我说的类似基因序列的,是**它识别到我讲话的时候才会**,不然**还是线条 ~~~**,
                //     目的是**区分她到底有没有听到我说话**」。
                barWave?.setHeard(true)
                // ★ 2026-10-06:那半句「…$text」原来贴在字幕行上,和字幕一起删了。
                //   「他到底有没有被听到」的证据**就是上面那条变色的波** —— 那才是他要的形状。
            }
        }

        override fun onHeard(text: String) {
            main.post {
                val app = appCtx ?: return@post
                if (!voiceOn) return@post
                heardThisTurn = true
                // 他真开口了 —— 45 秒那笔账**从这一刻重新开始数**。
                silenceSince = System.currentTimeMillis()
                // ★ 挂断在**本地判**,省一轮模型。而且必须判在她答之前 ——
                //   让模型去理解「不聊了」,她会认真地回一句「好的,那我们下次聊」,
                //   然后**继续等着听**,看起来像没听懂。
                if (EarSessionMath.isExitPhrase(text)) {
                    endBubbleVoice(app, "好,那我不吵你了")
                    return@post
                }
                sendSpoken(text)
            }
        }

        override fun onNotice(msg: String) {
            main.post {
                val app = appCtx
                if (app != null) ModelManager.get(app).trace("球:耳 $msg")
                // ★ 2026-10-06:这行「✗ $msg」原来写在上方那行字幕上,字幕删了 ——
                //   改走 Toast(不变量「不许静默失败」:耳朵出问题时他得当场知道,
                //   不然症状就是「她忽然不说话了」,看着像闹脾气不像故障)。
                if (app != null) toast(app, "✗ $msg")
            }
        }

        override fun onDone() {
            main.post {
                setListeningUI(false)
                // ★ 这一轮**听到了**(她正在想/正在答)或者他已经收工了 —— 什么都不做。
                //   循环的接力棒这会儿在 [HerVoice] 的 onDone 手里。
                if (!voiceOn || heardThisTurn) return@post
                val app = appCtx ?: return@post
                // ★★ 这一轮是**他自己掐掉的**(上滑取消 / 点一下打断),不是「没人说话」——
                //   收工的事已经在那两条路上做完了。这里**绝不能**再 `armResume`:
                //   那会在他说完「这句不要了」之后**把麦又开回来**,而他以为它已经关了。
                if (roundCancelled || swipeCancelled) return@post
                // ★★ 按住说话,松手之后**一个字都没收到** —— 当场收工,不等 45 秒。
                //   用户原话:「松开对话框(语音输入完毕)后需要先检测是否输入了文字,
                //   **如果没有输入,就不发送,关闭麦克风**」。
                //   所以这一支和下面那支是两件事:自动那几轮才是「静默 45 秒才收工」。
                if (turnFromHold) {
                    endBubbleVoice(app, null)
                    return@post
                }
                val silentFor = System.currentTimeMillis() - silenceSince
                if (EarSessionMath.sessionOver(silentFor)) {
                    // 45 秒没人说话 = 这场对话走完了(他定的「没检测到说话就自动关麦」)
                    endBubbleVoice(app, null)
                } else {
                    armResume(BAR_RETRY_GAP_MS)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // AiAgent 的 tee 从这里进来(后台线程 → 一律 post 主线程)
    //
    // ★ 2026-10-04 晚:那个**日志框**和它背后的滑动窗口一起删了(用户原话
    //   「那个大弹窗…删掉去」;房间里那个黑色日志框也是同一轮删的)。
    //   过程日志的去处从「屏幕上的一小块」换成 **model.log 一个地方** ——
    //   他是从 adb 那边读日志的人,而屏幕上那几行他从来没看过第二眼。
    // ------------------------------------------------------------------

    fun onLog(msg: String) {
        val ctx = appCtx ?: return
        // ★ 仍然 post 到主线程再写:`ModelManager.trace` 是**非同步**的
        //   (它自己 appendText),而 tee 从 ai-agent 那个后台线程调进来 ——
        //   两个任务同时写会**把行写花**,而花掉的那几行正好是排查时要看的。
        main.post { ModelManager.get(ctx).trace("球: $msg") }
    }

    fun onError(msg: String) {
        val ctx = appCtx ?: return
        main.post {
            // ★ 错误**要看得见**,这和过程日志不是一回事(见 AiAgent.Callback 里
            //   onLog/onTrace 那条分界:他需要据此决定什么吗?)。
            //   ★ 2026-10-06:它原来上那行字幕,字幕删了 → 改走 Toast;
            //   **落进 model.log 那一条一个字不动**(房间不在前台时,日志是唯一留痕)。
            val line = "✗ $msg"
            lastFinal = line
            toast(ctx, line)
            ModelManager.get(ctx).trace("球:$line")
            // ★ 出错就没有「她念完」这回事了 —— 立刻把麦放回去,别让她晾在那儿。
            //   不补这一句的话,对话会**死在这一轮**,而它看起来像「她不理我了」。
            if (speakThisReply) {
                speakThisReply = false
                armResume(0L)
            }
        }
    }

    fun onFinal(msg: String) {
        lastFinal = if (msg.length > 160) msg.substring(0, 160) + "…" else msg
        main.post {
            // ★★ 念出来的那一支**必须在任何「条子没了就提前 return」之前**:条子收着的
            //   (他点开条子说话、说完又点掉)照样要把这一轮的麦克风放回去,
            //   不然对话就死在这一轮 —— 而症状是「她说了一句就不理我了」。
            //   ★ 2026-10-06:原来这里写的是「必须在 `herView == null` 之前」——
            //   那个提前 return 随字幕一起删了,**但这条规矩本身照旧**:以后谁要在这段
            //   前面再加提前 return,先想清楚「这一轮的麦还放不放得回去」。
            // ★★ 2026-10-05 重写。修之前这里写的是「在对话里才念」——
            //   于是**打字问的那一轮她一个字都不出声**,而房间里同一个动作她是念的
            //   (用户在桌面上看见的就是这个:「桌面好像说话没有声音」)。
            //   判据交给 [BubbleSpeech]:出不出声看「这一轮是不是他问的」,
            //   放不放麦看「在不在对话里」—— **两件事,不许再绑在一起。**
            val plan = BubbleSpeech.plan(
                speakThisTurn = speakThisReply,
                inVoiceSession = voiceOn,
                hasApp = appCtx != null,
            )
            speakThisReply = false
            val app = appCtx
            when {
                plan.speaks && app != null ->
                    // ★★ **半双工闸就挂在这儿**:她念完了才把麦放回去,再留 300ms 冷却。
                    //   直接开麦的话,尾音会绕回麦克风变成一句「新输入」——
                    //   她自问自答,而日志上那是一句完全正常的用户话。
                    HerVoice.speak(app, lastFinal.orEmpty()) {
                        if (plan.rearms) armResume(EarSessionMath.COOLDOWN_MS)
                    }
                // 念不出来 / 这一轮不该念,但对话还开着 —— 立刻交棒,别让她晾在那儿。
                plan.rearms -> armResume(0L)
            }
            // ★ 2026-10-06:这里原来还有一段,把上方那行字幕(herView)置 GONE ——
            //   「她回答的字幕不亮出来」(和房间同一条改法)。那行字幕现在整块没了,
            //   这一段连同它前面那个 `herView ?: return@post` 一起删掉。
            //   ⚠️ 那个 `return@post` **只是**为这一行字服务的,删了不会少做别的事。
        }
    }

    /**
     * 她**主动开口**的时候(见 [ProactiveGreeting])。
     * ★ 2026-10-06:它原来还管「他点开条子就能看见那句」—— 那行字幕删了,
     *   于是它现在只做 [onFinal] 那一件事:记下 [lastFinal] + 按 [BubbleSpeech] 决定念不念。
     *   Toast 由 ProactiveGreeting 负责,这里不重复弹。
     */
    fun onSpontaneous(line: String) = onFinal(line)

    fun onBusy(b: Boolean) {
        busy = b
        if (bubbleView == null) return
        main.post { refreshStatus() }
    }

    /**
     * 忙闲的唯一落点。
     *
     * ★ 它原来写的是一行「电脑已连 / 没连 · 干活中…」的状态字,那行字跟着大面板一起删了。
     *   而「她在干活」这件事**不能没有地方说**(不然他一连问两句,第二句只是被
     *   悄悄弹回,看起来就是「她不理我」)。所以换个地方说:**发送键变成 `…`、按不动**。
     *   这比一行小字强 —— 那行字在他打字的时候离眼睛很远,而发送键就在手指底下。
     */
    private fun refreshStatus() {
        val v = barSend ?: return
        // ★★ 和房间里那颗**同一个规矩**(2026-10-05 用户:「右边的那箭头不要搞成说字,
        //   难看」):它从头到尾就是一颗箭头,忙闲靠**按不动 + 暗下去**说,
        //   不靠把字换掉。
        v.isEnabled = !busy
        v.alpha = if (busy) 0.45f else 1f
    }

    private fun toast(ctx: Context, s: String) =
        main.post { Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show() }
}

/**
 * 悬浮窗那张「人形」的落点与取景比例。**这里是唯一的来源。**
 *
 * 写它的是 [ConMarnActivity] 的 `captureFigure`(从她活着的场景里拍),读它的是
 * [ConMarnBubble]。两边各写一份的话,改动那天**不会报错** —— 症状是
 * 「明明拍成功了,悬浮窗还是圆球」,而那正是这个项目已经吃过好几次的那种静默失败。
 *
 * 280:700 不是随便定的:它和 her.js `captureFigure()` 里出图的 W/H **是同一对数**。
 * 哪天要给悬浮窗换个更瘦或更宽的比例,**两边一起改**。
 */
internal const val CONMARN_FIGURE_FILE = "conmarn_figure.png"
internal const val CONMARN_FIGURE_W = 280
internal const val CONMARN_FIGURE_H = 700
