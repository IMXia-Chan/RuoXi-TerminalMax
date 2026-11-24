package com.example.touchpad

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.format.DateFormat
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ConMarn —— 她的房间。
 *
 * ## 2026-10-04 的大改:这里原来是个聊天框,那是错的
 *
 * 用户的原话:「她是会动的,会主动听我讲话,还会和我聊天的,不是一个界面,
 * 然后你发一句我发一句那种,那种和千问豆包 deepseek 压根没区别!」
 *
 * 对的。一个气泡列表 + 输入框,无论里面坐的是谁,观感都是「聊天机器人」。
 * 现在这个界面**没有聊天记录**:
 *
 *   - 全屏是**她本人**(WebView 里的 three.js + VRM 模型),站着、呼吸、眨眼;
 *   - 她说的话是**字幕**(屏幕下方一行,像视频通话的实时字幕),不是气泡;
 *   - 输入框默认**藏起来**,点一下屏幕才出来 —— 打字是兜底,不是主路;
 *   - 工具日志默认**不显示** —— 那是给机器看的,不该占据她的房间。
 *
 * ## 分工(改这个文件之前先看懂这条线)
 *
 *   身体(动)   -> WebView 里的 her.js(three-vrm),只负责「怎么动」
 *   嘴(说)     -> 本类的 TTS;念到第几个字通过 onRangeStart 回给 her.js 对嘴型
 *   耳朵(听)   -> [WakeWordManager] / [SherpaVoice](sherpa-onnx 内嵌进 APK)。
 *                 唤醒词是「从漫」;她说话时麦克风必须关,否则把自己的声音听回去
 *   脑子(想)   -> [AiAgent](llama.cpp 本地 + DeepSeek 云端兜底),本类不碰
 *
 * 本类只做一件事:**把这几样接起来**,以及决定「她现在是什么状态」。
 */

/** 用途名最多几个字 —— 它要在一行里跟读数挤在一起。和 [RouteMath.purposes] 的上限一致。 */
private const val PURPOSE_MAX_CHARS = 12

class ConMarnActivity : Activity() {

    // ── 她的身体
    private var webView: WebView? = null
    private var herReady = false
    /** 她还没加载出来时攒下的话,加载完一起补给她(否则开场白会丢) */
    private val pendingJs = mutableListOf<String>()

    /**
     * 「给她拍一张人形」那一件事。存成字段而不是每次 new ——
     * `postDelayed` 只能用同一个实例 `removeCallbacks`,而重复进房间会重复 post,
     * 攒下的一堆旧 Runnable 会在她走后台之后一起触发,白拍好几张。
     */
    private val figureShot = Runnable { captureFigure() }

    /**
     * 盖在她身上接点击的那张透明纸。
     *
     * ★ 她加载出来之后它必须**让开** —— 否则房间里那些东西根本点不着。
     * 但**不能拆掉**:她没出场时(`herReady == false`)它是唯一能唤出控件栏的东西,
     * 拆了就再也没法跟她说话。见 [setOverlayEatsTouches]。
     */
    private lateinit var overlay: View

    /** 上一次摆出去的那份物件清单。一样就不重摆(否则物件会闪)。 */
    private var lastRoomJson: String? = null

    // ── 覆盖在她身上的那一层(字幕 / 开关 / 输入)
    private lateinit var root: FrameLayout

    /**
     * 房间里**左右各挂的那块信息屏**（2026-11-07，见 [RoomPanels]）。
     *
     * ★ 可空，而且**默认是 null**：[showSettings] 里那一行能把它关掉，
     *   关掉之后这两块屏一个像素都不画 —— 房间回到原来那样。
     */
    private var roomPanels: RoomPanels? = null

    /**
     * 指纹那一道盖在最上面的黑底([coverForGate])。
     *
     * ★ 它不是装饰:系统那个指纹弹窗是半透明的,**不盖住的话没验过也看得见她的房间**。
     */
    private var gateCover: View? = null
    private lateinit var caption: TextView
    /**
     * 那块**磨砂玻璃胶囊** —— 字幕和输入框共用的壳。
     *
     * ★ 它以前是个 `FrameLayout` 而且**没有底色**(用户连着删了三次装饰之后的样子)。
     *   2026-10-04 晚换成了**横着排的 LinearLayout**:左边一格放字([textSlot]),
     *   右边一颗圆箭头([sendBtn])。见 buildBottomBar 里那段长注释 ——
     *   这一块玻璃是有**职责**的(它是「按住说话」的手感区),不是装饰回来的。
     */
    private lateinit var captionWrap: LinearLayout
    /** 按下字幕的时刻。松手时拿它减一下,判这一下是「点」还是「按住」。 */
    private var pressStartedAt = 0L
    /**
     * 字幕上现在那句是不是「我在听…」这个占位。
     *
     * ★ 要记着,不然会**擦错东西**:收工时如果无脑清字幕,会把她刚说完的那句话
     *   一起擦掉 —— 而那句话正是他等着看的那句。
     */
    private var captionIsPlaceholder = false
    /**
     * 字幕上现在是**他自己正在说的那半句**(实时转写的中途结果)。
     *
     * ★ 它和 [captionIsPlaceholder] 是两件事,但对 [setCaptionPlaceholder] 的作用一样:
     *   两者**都不是「一句已经说完的台词」**,所以都可以被覆盖。
     *
     * ★ 不把它记下来的话,「说了一半停下、最后什么都没听清」这一种情况下,
     *   字幕会**卡在那半句上** —— 收工那句「按住说话」再也回不来,
     *   他看着一行残缺的字,不知道麦克风早就关了。
     */
    private var captionLivePartial = false
    /**
     * 占位话是**什么时候**被写上去的 —— [CaptionDwell] 靠它算「他看够没有」。
     *
     * ★★ 2026-10-05 用户报:「房间语音输入结束会在透明磨砂玻璃中跳出个提示,
     *   **太快了我看不清是什么**」—— 一句占位话转写快的时候只活两三百毫秒。
     *   修法是**给占位话一个最短停留时间**,真话排队等一会儿(见 [deferredCaption])。
     *   (原来那句是「在认你说的话…」,当天晚些时候整句被删掉了;
     *    这个机制留着,是因为 [HINT_SWIPE_CANCEL] / [HINT_STOPPED] 同样瞬来瞬去。)
     */
    private var placeholderSince = 0L

    /**
     * 排队等着顶掉占位话的那句**真话**(`文本 to 是不是她说的`)。
     *
     * ★ 它**只是排队,不是丢弃**。占位话站满 [CaptionDwell.MIN_MS] 之后,
     *   这一句原样上来 —— 用户既读得到提示,也不会少看到自己说的那句话。
     *
     * ⚠️ 任何**直接**写字幕的地方([setCaptionNow] / [liveCaption])都会把它清掉,
     *   见 [cancelDeferredCaption]。少了那道清理,会出现「他早就干别的去了,
     *   屏幕忽然冒出一句两秒前的话」—— 那种 bug 看起来像**她答非所问**。
     */
    private var deferredCaption: Pair<String, Boolean>? = null

    /** [deferredCaption] 到点了,兑现它。 */
    private val flushDeferredCaption = Runnable {
        val d = deferredCaption ?: return@Runnable
        deferredCaption = null
        applyCaption(d.first, d.second)
    }
    /**
     * 打字机要吐的**整句**。显示出来的永远只是它前面一段([captionShown] 那么长)。
     *
     * ★ 它是**目标**,不是「已经说完的话」—— 流式(`onPartial`)每来一版就把目标改长,
     *   于是字是**追着**她的思路跑出来的,而不是等她憋完一大段才「啪」地整块出现。
     *
     * ★★ 它顺带治了「**反应速度有点慢**」这条体感(他 2026-10-04 晚的原话):
     *   字一到位就开始一个一个往外走,第一个字和整句之间的等待被**填满**了。
     *   实际算完的时间一点没变,但他盯着看的那段时间里屏幕上**有东西在动** ——
     *   「慢」和「没反应」于是分开了。这是这一版唯一一处「不改性能也能改体感」的地方。
     */
    private var captionTarget = ""
    /** 已经吐出来几个字。 */
    private var captionShown = 0
    /**
     * 玻璃胶囊里**放字的那一格** —— 字幕和输入框**住在同一个位置**,轮流出现。
     *
     * ★ 为什么是「同一个位置轮流」而不是「一上一下两行」:
     *   两行的话,「按住说话」那条和「打字」那条是**两个不同高度的东西**,
     *   手指得先看清现在在跟谁说话;一行两态的话,**那一块地方永远管说话,
     *   不管现在是用嘴还是用手**。
     *
     * ⚠️ 它俩**必须互斥**。同时 VISIBLE 的话两段字会叠在一起(都是
     *   `MATCH_PARENT` 宽),看起来像花屏。所以谁也别自己去动 `caption.visibility`
     *   —— 统一走 [setTypingMode] 和它那几个兄弟。
     */
    private lateinit var textSlot: FrameLayout
    private lateinit var input: EditText
    /** 右边那颗**白色圆箭头**。原来是个写着「说」的粉色方块,见 `btn_her_send`。 */
    private lateinit var sendBtn: Button

    /**
     * ★★ **她说的那行字** —— 独立一行,浮在那块磨砂胶囊**上方**。
     *
     * 用户 2026-10-05 原话:
     * > 「她字幕呢,她说话得有字,就之前那个样子,但是,**是在我的透明磨砂对话框上面出现**」
     *
     * ★ 在这之前她的话和**输入框**挤在同一个座位([textSlot])里,而那一格是**互斥**的。
     *   两个后果都是「她说了话,屏幕上却没有」:
     *     · [setTypingMode] 一进打字态就把 `caption` 置 GONE ——
     *       **他打字的时候,她答的话一个字都看不见**。而那恰恰是他问长问题的时候;
     *     · `onPartial` 那条流式路走 [revealCaption],而它第一行就是 `if (typing) return`。
     *
     *   所以这一次搬的不是位置,是**分开两个抢座位的人**:
     *     · **她说的** → 这儿(上面,只用来看)
     *     · **你说的 / 提示话 / 水波** → 胶囊里(下面,手要落下去的地方)
     *
     * ★ 它**不排队、不看 `typing`、不参与 [CaptionDwell]**。那套机制是给占位话
     *   不被一闪而过的,而她的字从来没有「一闪而过」的问题 ——
     *   少一个变量就少一个「她说了话却没显示」的成因,
     *   而这句抱怨(「房间她说的话怎么不显示了」→ 今天这句)已经来过两次了。
     */
    private lateinit var herSay: TextView
    /**
     * 她那行字自己的打字机进度。
     *
     * ★ 和 `captionTarget` / `captionShown` **刻意不复用**:两套状态混在一起的话,
     *   她的一句话会把胶囊里他那句吐到一半的进度顶掉 —— 那是**另一种**
     *   「他正说着,屏幕上却是别人的字」。
     */
    private var herSayTarget = ""
    private var herSayShown = 0

    /**
     * ★★ 那行字上摆着的**是不是一句已经定稿的话**。
     *
     * 它只治一件事:**一轮里她先吐了几版草稿、再定稿,定稿之后又来一条空 partial,
     * 那行字就会被自己的草稿撤掉。**
     *
     * `AiAgent` 里那句 `cb.onPartial("")` 的用意是「撤掉规划时的自言自语,别让它冒充结果」
     * (见 [typeHerSay] 的 KDoc)。可它**不知道该不该撤** —— 而定稿之后来的那一条空串,
     * 撤掉的正是他抬头要看的那句**真话**。症状:她明明答了,字**一闪而过**,
     * 屏幕上最后什么都没有,而日志上一片太平(它本来就什么都没干错)。
     *
     * ★ 判据只有一条:**只有「上一句还没说定」才允许被空串清掉。**
     *   · [sayCaptionOnly] 落地(定稿)→ 置 true
     *   · 任何一版非空 partial(草稿)→ 置 false
     *   · 他自己说的那一句 → 置 false(那是**他的**话,草稿规则不该把它锁住)
     *
     * ★ 它**不是**状态机,就是一个「这行说定没有」的位。别往上加语义。
     */
    private var laneFinal = false

    /**
     * 那行字的**记录仪**用的一格:上一次写进日志时的状态(见 [laneTrace])。
     *
     * ★ 它在**去重**,不是状态机 —— 流式是一版一版来的,一版写一条的话,
     *   真正要找的那一行会被自己刷掉。
     */
    private var laneLastState = ""

    /**
     * 那行字**不收字**时该多大 —— 长度带来的收小由 [fitHerSay] 在那之上做。
     *
     * ★ 它身上挂着**既有设计**的那层意思:**她说的(定稿)16、他说的和她的草稿 14** ——
     *   一眼分得清谁在说。这一格存在的唯一理由是:**别让「按长度收字」把那一层压掉**
     *   (见 [CaptionFit.textSize] 的 `base` 参数)。
     *
     * ★ 每个会写字的地方**先设它、再调 [fitHerSay]** —— 顺序反了就是拿上一次的字号去收字。
     */
    private var laneBaseSize = 16f

    /**
     * 「她正忙」—— 在想 或 在说。那颗圆按钮按这个**换职责**(发送 ↔ 停)。
     *
     * ★ 由 [Callbacks.onBusy] 置位。**它是按钮长相的唯一依据**,
     *   所以两个方向都要写:`busy=true` 时置位,`false` 时必须清掉 ——
     *   漏了清的那一次,那颗按钮会**永远停在「停」上**,再也发不出话。
     */
    private var herBusy = false
    /**
     * 打字那一态里那颗 **🎤**,点一下回到「按住光晕说话」。
     *
     * ★★ 2026-10-05 用户报的真 bug:
     *   「那个房间的语音输入是在输入框里的,然后点了一次打字输入就没法语音输入」
     *
     *   根因不在按钮,在**触摸派发**:`input`(EditText)一旦 VISIBLE,它就是
     *   `textSlot` 的孩子,而 `ViewGroup` 的 `OnTouchListener` 只在**没有孩子消费**
     *   的时候才吃到事件。于是整个胶囊的 `onCaptionTouch` 一声不响地死了 ——
     *   **按住说话被输入框吃掉,而且不报错、不缺日志,界面看上去一切正常。**
     *   唯一能回语音的路是 [send] 里那句自动 `setTypingMode(false)`,
     *   也就是「你必须先打出几个字发出去」—— 那显然不是人想要的。
     *
     * ★ 为什么只在打字态露出来(**这一条是关键,别顺手改成常驻**):
     *   2026-10-04 晚他点名删掉过一颗常驻的 🎤,原话是「说话不需要按钮,
     *   按这一整块就是说话」。那句话在**语音态**是对的 —— 那时整颗胶囊就是手势靶子,
     *   摆一颗按钮只会在手感上切走一块。所以它只在**语音态已经够不着了**的那一态出现:
     *   它不是装饰,是把丢掉的入口补回来。
     */
    private var voiceBtn: ImageView? = null
    /**
     * 按住说话时那条**慢慢流动的水波**([VoiceWaveView])。
     *
     * 它和字幕**共用左边那一格**([textSlot]) —— 同一时刻只有一个露面,谁露面由
     * [waveOn] 说了算。这是 [typing] 那套的第三个住户(字幕 / 输入框 / 水波),
     * 所以三个都得收在 [revealCaption] 那**一个**汇点上,别各写各的 visibility。
     */
    private lateinit var wave: VoiceWaveView
    /** 水波是不是正占着那一格。见 [showWave] / [hideWave]。 */
    private var waveOn = false
    /**
     * 现在是不是**打字那一态**(键盘在,胶囊里是输入框,不是字幕)。
     *
     * ★ 它唯一的用处就是**拦住字幕把你的字盖掉**:她在你打字的时候答一句
     *   (`say` → `showCaption`)会把字幕置成 VISIBLE,而输入框也在 ──
     *   两段字当场叠在一起。所有「把字幕显示出来」的地方都得看这个标志。
     */
    private var typing = false
    /**
     * 左上角那盏灯:**绿 = 连上电脑,红 = 没连**。
     *
     * ★★ 2026-10-04 晚用户点名要的,原话:「在左上角显示绿灯红灯,绿灯就是连上电脑,
     *   红灯就是没连上」。它替掉的是原来那颗写着「电脑没连」四个字的按钮 ——
     *   **状态不该占用一行文字的位置**,一眼看得出来的东西就用一眼的量表达。
     *   (那四个字进 ⚙ 里了,连「点它去配对」那条路一起。)
     */
    private lateinit var connDot: TextView

    /** ★ 顶栏右边那个 ⚙。**故意是图标不是文字** —— 见 [showSettings] 的说明。 */
    private lateinit var settingsBtn: Button

    /**
     * ★ 顶栏上那颗「扫描」—— 找局域网里那台电脑。
     *
     *   用户原话:「实在不行,就拉一个扫描按钮到她房间,一个图标(美观的图标),点一下就扫描」。
     *   我问过「点下去会跳去电脑页并自动开扫,能接受吗」,他答:「能啊,这有啥不能」。
     *
     *   ★ 为什么只能摆在**顶栏、而且只能挨着 ⚙**:这个房间里能放东西的地方只有三处 ——
     *     顶栏、下栏、和 3D 场景本身。场景那一块**放不得**(右缘那条书签条正是
     *     因为会吃掉 WebGL 的一格手势才被藏起来的,见 [StationOverlay.setHandleVisible]);
     *     下栏那一条是她的字幕、输入框和发送键。所以只剩顶栏,而顶栏里能挨着放的只有 ⚙。
     *     ★ 左上角那颗连状态灯也不行 —— 它 [connDot] 是**故意不可点**的,见 [buildTopBar]。
     *
     *   ⚠️ 顶栏「只留设置按钮」是用户定过的规矩(见 [buildTopBar] 那段)。
     *     这一颗是**他后来点名要的**,所以是例外,不是我把那条规矩作废了 ——
     *     但它必须**只有一个图标、没有文字**,不许长成第二排开关(那才是他嫌的东西)。
     */
    private lateinit var scanBtn: ImageView

    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout

    /**
     * ★★ 挑动作那一栏(2026-10-09)—— 房间主页**最右侧**,能上下滑。
     *
     *   用户原话:「**在她房间的主页(不是设置)最右侧加上可以上下滑动的挑选动作的栏**」。
     *
     *   ★ 为什么是「栏」不是「一页」:挑动作是**看着她的身体挑**的事 ——
     *     躲进 ⚙ 里点三层才能换一条,那是设置;摆在房间里点一下就换、当场看得见,
     *     那才是「挑」。所以它**住在房间里、不进 ⚙**(和顶栏同一条理由,
     *     见 [buildTopBar] 末尾那段)。
     *   ★ 2026-10-09 晚它变成**可收起**的(见 [motionPickerOpen]):收起时只剩顶上
     *     「动作」那一条,点它才铺满、点房间空处又收回去 —— 见 [setMotionPickerOpen]。
     *
     *   ⚠️ **它确实会吃掉右缘那一条的 WebGL 触摸** —— 这正是当初书签条被藏起来的原因
     *     (见 [scanBtn] 那段)。他点名要它、也点名要在最右侧,所以这一条是**他选的代价**;
     *     能做的只有把它做窄(见 [MOTION_PICKER_W])。真挡住房间里哪件东西了,是他会先看出来的。
     *     ★ 可收起**顺带把这笔代价压小了**:收着的时候右缘只剩顶上那一条玻璃。
     */
    private var motionPicker: LinearLayout? = null

    /** 那一栏里的行:`rel` → 那一行的按钮。换完动作要照它重刷高亮,见 [refreshMotionPicker]。 */
    private val motionRows = LinkedHashMap<String, Button>()

    /**
     * 那一栏的**外框**与里面的**滚动区**。
     *
     * ★ 它们本来是 [buildMotionPicker] 里的局部变量 —— 要能收起来就必须留住。
     *   因为「收起来」要动的是**两个**视图:`motionPickerScroll` 得藏掉,
     *   `motionPickerWrap` 的高度得从 `MATCH_PARENT` 换成 `WRAP_CONTENT`。
     */
    private var motionPickerWrap: LinearLayout? = null
    private var motionPickerScroll: ScrollView? = null

    /** 顶上那一条 —— 它既是标题,也是**开关**(点它展开 / 收起)。 */
    private var motionPickerHead: TextView? = null

    /**
     * 现在是铺满着,还是收成顶上一条。
     *
     * ★★ **默认收着。** 用户原话:「**点一下往下展开,再点屏幕其他地方,又缩回去的**」——
     *   「点一下**才**展开」就是把「收起」当成常态;而且收着的时候右缘被吃掉的
     *   WebGL 触摸只剩那一条(见 [motionPicker] 那笔代价)。要改成默认铺满,
     *   把这里的初值换成 `true` 就行,别处一个字不用动。
     */
    private var motionPickerOpen = false

    // ★★ 这里原来有一整套「控件显隐」:一个 `controlsShown` 开关、一个六秒自动收起的
    //   定时器 `hideControls`、以及 `setControls` / `toggleControls` / `keepControlsAlive`
    //   三个函数,还有散布在打字、收听、发消息那几处的 `keepControlsAlive()`。
    //   **2026-10-04 晚整套删了** —— 它们最后只剩一件事:**工具日志露不露**。
    //   而那件事随着那块日志一起没了(见 [buildBottomBar] 里那段)。
    //
    //   ★ 现在房间里没有「会露会藏的东西」了:
    //     顶栏(红绿灯 + ⚙)和底下那块玻璃胶囊是**常驻**的 —— 用户 2026-10-04 点名的
    //     「不要点屏幕才能跳出设置和红绿色灯」就是这个意思。
    //   ★ 所以**点空处从此什么都不做**,这是有意的,不是漏了。
    //
    //   ⚠️ 留个记号给下一个想加东西的人:想加「点一下冒出来的面板」的话,
    //     它会撞上这个决定。加之前先问一句 **用户是不是又要一个要点才出来的东西** ——
    //     他今天已经因为它删过两次了。

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsOn = true          // 默认出声:她就该有声音,不想听点一下

    /**
     * 嗓子还没热起来时她说过的话,先攒在这儿,热了补念。
     *
     * ★ 2026-10-04 他报了「她得发声啊」,真机上查出来的是:**她第一句永远是哑的**。
     * 原来 [speak] 在 `!ttsReady` 时是 `initTts(); return` —— 直接把这句**丢掉**,
     * 而 `initTts()` 全项目**没有任何一处**在启动时调用(只挂在 toggleTts 和 speak 上),
     * 所以必然如此:第一次开口 = 触发初始化 + 丢句。用户看到字幕弹出来了、
     * 一点声音没有,而且**不报错**。
     *
     * 攒着而不是丢了重说 —— 她说的第一句往往正是他等着的那句。
     * 上限 3 句:再多就是她自说自话,补念出来只会吵。
     */
    private val pendingSpeech = ArrayDeque<String>()

    /** 「她的嗓子装不上」这句话只当面说一次,不每次进房间都弹。 */
    private var ttsFailTold = false

    // ★ 这里曾经有 `recognizer: SpeechRecognizer?` 和 `listening: Boolean`。
    //   2026-10-04 晚跟着 🎤 按钮一起删了(理由见「打字那一态」那一段)。
    //   ⚠️ 现在判断「她在不在听」**只有一个判据**:[Ear.isListening]。
    //   以前是两个(`listening || Ear.isListening`),而两个判据的地方
    //   迟早会出现「一个说在听、一个说没在听」—— 那正是这一版在治的病。

    /** 这一句正在念的原文 —— 对嘴型要用(见 speak 里的 onRangeStart) */
    private var speakingText: String? = null

    /**
     * ★★ **她的嘴真的张开了没有** —— 「在准备」和「在出声」是两件事,这一格记的是后者。
     *
     * 整句合成要好几秒(这台机器实测 ≈1 倍实时,见 [SherpaVoice]),那几秒里
     * [speakingText] 早就不是 null 了,**却一个字节的声音都没有**。
     * 所以「点一下算不算打断」**不能用 [speakingText] 判** ——
     * 用它的话,你在这几秒里点一下,那一句**在合成里就被作废了**:
     * 一个字都没出过声,而日志上写着「念「…」」,看着像念过。
     * 用户 2026-10-05 点名说的就是这个:「点一下时,如果她还没出声,别把整句作废」。
     *
     * ★ 立/收的地方**和「她开口 / 她闭嘴」是同一处**,不另开一套状态:
     *   立在 [startTalking]、收在 [onSpokenDone] 和 [interruptHer]。
     */
    private var herSounding = false

    /**
     * ★★ **先出声**已经抢出去的那一块(空 = 这一轮没抢)。
     *
     * 治的是「响应太长」里**不属于模型的那一半**:她整段生成完之前一个音都不出
     * (见 [SpeechChunkMath] 文件头)。这一格记着「这句我已经念过一半了」,
     * 定稿([Callbacks.onFinal])时拿它跟全文对账,只补**还没念过的那半截**。
     *
     * ★ 存的是**原文**(没剥过舞台指示的那一份)—— 对账是跟 [AiAgent] 给的定稿比,
     *   中间隔着一层 [SpeechMath.forSpeech],拿剥过的那份比必然对不上。
     *
     * ★ **每一轮的头和尾都要清**(见 [forgetEarlySaid])。漏了尾部的话,
     *   下一轮会拿着上一轮那句话去对账 —— 而对不上账的后果是**整段重念一遍**。
     */
    private var earlySaid: String = ""

    /**
     * 「先出声」抢出去的那些块**全都念完了**没有。
     *
     * ★ 为什么要单独记:那些块挂的是**空回调**(见 [speakNow]),它们的 `onDone`
     *   不是「放麦」的信号,只是「这一块交割完了」。而定稿时如果发现**抢出去的
     *   就是全部**,这一轮就该由它们来交割 —— 于是这个标志决定「现在补一次」
     *   还是「等它响完再补」。
     *
     * ★★ 2026-10-05 下午:**它从「那一块」变成了「那些块」。**
     *
     *   原来一整轮只抢**一块**(`onPartial` 里那句 `if (earlySaid.isEmpty())`),
     *   而那一块念完之后会有一段**很长的空白**,然后才接上定稿补念的剩下半句 ——
     *   用户原话:
     *   > 「她在说话的时候,会**中间断一下再说**,前半句话……后半句话,
     *   >   **会中间加延迟**」
     *
     *   真机日志把那段空白量得很清楚(一句 7 个字的头,后面空了 **8 秒**):
     *   ```
     *   18:20:36 先出声:抢了头 7 个字先念
     *   18:20:38 嗓子: 念「听得到听得到，」(24832 帧 ≈ 1.13 秒)
     *   ……      8 秒静音(她还在合成下半截)
     *   18:20:46 嗓子: 念「耳朵灵着呢～ 就是这会儿你那台电脑我够不着，别的」
     *   ```
     *
     *   改成**一句一句往下抢**之后,那 8 秒被摊到每一句之间的缝里。
     *   ⚠️ 摊不平 —— 这台机器的嗓子和成速度只有实时的 **0.58 倍**
     *   (1.13 秒的音频要 ~2 秒造出来),一段 5 秒的话永远要 8 秒才念得完。
     *   **切成句能把它从「沉默 8 秒」变成「一句一顿」,不能让它变流利。**
     *
     * ★★ 所以它现在必须由**一个计数**驱动,不能是一个布尔:
     *   布尔只能记住「最后响完的那一块」,而 [Callbacks.onFinal] 是拿它决定
     *   「要不要现在放麦」的 —— 用旧的那半句去算,就会在**后面的块还在响的时候
     *   把麦克风打开**,她把自己的话听回去。**不报错、日志干净、她开始自问自答。**
     */
    private var earlyDone = false

    /**
     * 抢出去、但**还没念完**的块数(0 = 全交割完了)。
     *
     * ★ [earlyDone] 是它的派生结论,不是另一个独立的账本 ——
     *   只有它归零,`earlyDone` 才为真。见 [onEarlyChunkDone]。
     */
    private var earlyPending = 0

    /**
     * 定稿判定:**抢出去那一块就是全部**,它念完就等于这一轮交割完了。
     *
     * ★ 它存在,是因为 [speakNow] 给提前念那一块挂的是**空回调** ——
     *   正常那条路上「她念完了 → 放麦」这个信号,在这儿**永远不会来**。
     *   [onError] 那条路尤其需要它:那时候她可能**还在响**,
     *   立刻开麦就会把扬声器里的声音听回去 → 自问自答,而且不报错。
     */
    private var earlyOwnsTurn = false

    /**
     * ★★ 这条嗓子**正在等的那一条**的号。空 = 没在等(念完了 / 被打断了 / 交给了内嵌嗓子)。
     *
     * 它治的是**房间里那一半**的「谁的回声」(判定和理由见 [EchoMath])。这条嗓子是
     * `QUEUE_ADD` 的 —— 她连着说两句时,第 1 句的 `onDone` 会**在第 2 句还在念的时候**回来。
     * 认了它 = 半双工闸提前放行 = 她把第 2 句听回去。**不报错、日志干净。**
     *
     * ★ 注意它**不是** [speakingText] 的附件:号管的是「哪一声回调算数」,
     *   文本管的是「嘴型对哪个字」。两件事分开,是因为内嵌嗓子那条路只动文本、不动号。
     */
    private var ttsId: String? = null

    private var lastCaptionAt = 0L

    /**
     * 上一次把响度推给 her.js 的时刻 / 推过去的那个值 —— 见 [pushMouth] 的限流。
     *
     * ★ 为什么要限流而不是「有变化就推」:合成每几十毫秒吐一块,
     *   一条 `evaluateJavascript` 就是一次跨进程调用 + 一次 JS 解析。
     *   不拦的话一秒钟几十条脚本往场景里灌,**她的嘴会去换掉整个房间的帧率**。
     */
    private var mouthPushAt = 0L
    private var mouthPushed = -1f

    /** 进程里唯一的她(和主界面共用,见 [AiAgentHolder])。 */
    private fun agent(): AiAgent = AiAgentHolder.get(this)

    // ==================================================================
    // 界面
    // ==================================================================

    /**
     * 收起**上下两条系统栏** —— 2026-10-04 用户点名要的
     * (「能不能自动收回系统的状态栏,不然不好看」→ 补一句「还有地下那条杠,能不能也收起来」)。
     *
     * 和 [SplashActivity] 用的是同一套(`systemBars()` + 划一下临时唤出),
     * **不是** `windowFullscreen` 主题属性:那样是硬的,想看一眼时间就得退出去。
     *
     * ★ 安全性:`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` 下,从顶/底边划一下,
     *   状态栏和导航栏都会**临时**浮出来并自动缩回去 —— 返回、回主页这些手势本身**不经过**这两条栏,
     *   所以收起来不会把人困在这里。三键导航的机型上常驻按钮会消失,但同样能划出来。
     */
    private fun enterImmersive() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersive()
        ExperienceStore.init(this)
        MoodStore.init(this)
        UserLexicon.init(this)
        // ★ 她记住他说过的话（2026-11-06）。全在本机，见 [MemoryStore]。
        MemoryStore.init(this)
        // ★ 另一本：**所有 agent 共用**的那本（2026-11-06）。见 [AgentMemory]。
        //   和上面那本故意分开 —— 干活的结论不能混进「他说过的话」里。
        AgentMemory.init(this)
        // ★ 她也得有:这条路上没有 MainActivity,不 init 的话名单是空的 ——
        // 而表现是「问有哪些手,答没有」,还是那种**不报错**的坏。
        HandRegistry.init(this)
        SelfHand.init(this)
        // ★ 房间左边那块屏的**兴趣画像**（2026-11-07）。只在本机，见 [InterestStore]。
        InterestStore.init(this)
        // ★ 指纹那组状态是**进程级**的：冷启动算「没验过」，屏一熄就记成「又锁上了」。
        //   不在这里注册的话，「熄屏回来要再按一次」那条规则永远不触发（见 [AppGate])。
        AppGate.init(applicationContext)
        // ★ 把她拉起来 —— 这一行修的是「她的门不预热」。
        //
        //   全项目原来唯一拉起模型的地方是 MainActivity.onCreate 的 onState 回调;
        //   而用户平时点的多半是**她自己的桌面图标**(→ 这里),那条路只走
        //   AiAgentHolder.get(),从不预热 → 第一句话要现场冷算(几十秒)。
        //   合并成一个 App 之后这里**就是唯一的门**,所以这一行等于把所有入口
        //   一起修好了(是摘掉病根,不是补一个补丁)。
        //
        //   放在 init 那几行之后:HandRegistry / SelfHand 是 agent 建钩子时要用的。
        HerBoot.ensure(this)
        // ★ 顺手对一次耳朵的表:把 native 库拉起来、把「模型在不在」写进日志。
        //   那 26MB 的 `.so` 编译能过**不证明**它跑得起来,第一次 native 调用才是验证 ——
        //   所以这一行存在的意义是让 `耳: 原生库加载成功 ver=…` 出现在 model.log 里。
        Ear.ensure(this)
        // ★★ 然后**把识别模型先装进内存**。这是另一件事,别和上面那行混:
        //   [Ear.ensure] 只 `System.loadLibrary`(毫秒级);这一行碰的是那 239MB 的
        //   `model.int8.onnx`,实测 **3047ms**。
        //
        //   不预热的话那 3 秒会**砸在他按住说话的那一趟里** —— 真机日志:
        //   ```
        //   10:44:24 耳: 装载识别模型…(239233841 字节)
        //   10:44:24 [房] 🎤 听着呢,松手我就当你说完了
        //   10:44:27 耳: 识别模型就绪(3047ms)
        //   10:44:27 耳: 录音结束:0 段 / 0 ms(共收进 0 块 = 0 ms 音频)
        //   10:44:27 [房] ✗ 没听见你说话
        //   ```
        //   他按住、说了话、松手,而这 3 秒里**一个字节都没进麦克风**。
        //   他那一趟能得到的结论只有「她听不到我说话」—— 真凶却是装载。
        //
        //   ★ 放在这里(房间一打开)而不是 App 一启动:它要 239MB 常驻,
        //     而「人进了房间」是「他接下来八成要说话」最准的预报。
        //   ★ 它在后台线程上跑,不挡 UI。
        Ear.warmUp(this)
        // ★★ 2026-10-05:「本进程被用户打开过」—— 见 [KeepAliveService.hostActivityLive]
        //   那一段更正过的注释。这一行的位置很关键:它必须**早于**下面那次自动连接
        //   (`PcLink.connectLast`),否则线连上了、[KeepAliveService] 却以
        //   「没有宿主」为由当场自停 —— 那就是用户报的「退出房间就断连」的**前半段**:
        //   连上的那一刻进程就没有任何前台服务锚着,ColorOS 一冻,socket 就是死的。
        //
        //   ★ 以前这个标志只有 MainActivity 会置真。而**她的房间才是桌面唯一入口**,
        //     所以那条路上它恒为假 —— 又一个「挂在某个 Activity 寿命上」的坑。
        KeepAliveService.hostActivityLive = true

        root = FrameLayout(this).apply { setBackgroundColor(0xFF160F13.toInt()) }

        // ── 第一层:她 ────────────────────────────────────────────────
        val wv = WebView(this)
        webView = wv
        setUpWebView(wv)
        root.addView(wv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ── 第二层:一张透明的「手」──────────────────────────────────
        // 原来这张纸是**常驻**的:WebView 会吃掉所有触摸事件,而我们要的是
        // 「点屏幕任意处露出控件」,所以在它上面盖一层专门接点击。
        //
        // ★★ 2026-10-04 改:她现在有东西可以点了。这张纸**一直盖着**的话,
        //    触摸永远到不了 WebGL 场景 —— 房间里摆什么都点不着。
        //
        //    但它**不能拆**:她没出场时(加载失败 / 还在加载)这张纸是唯一能唤出
        //    控件栏的东西,拆掉 = 你连话都没法跟她说(软锁)。
        //    所以做法是**翻开关**,不是增删视图:她一出场就让它让开,
        //    它一出场之前一直顶着。见 [setOverlayEatsTouches]。
        // ★★ 2026-10-04 晚:**监听器本身也删了**。它原来是 `toggleControls()`,
        //   而那个开关现在什么都不管了。它**必须一起删** —— 留着的话这张纸就
        //   一直是个 clickable 的 View,点击会被它吃掉,而他看到的是「点了没反应」。
        //   删了之后它就是一块**纯粹让不让路**的玻璃:见 [setOverlayEatsTouches]。
        overlay = View(this)
        root.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ── 第三层:控件(默认隐藏) ──────────────────────────────────
        buildTopBar()
        buildBottomBar()
        // ★★ 2026-11-05：**不再建那一栏**。他的原话是
        //   「把原来的动作（右上角的动作选项）去掉……她说话带语气带动作，
        //    但用户不能点动作看她表演」。
        //
        //   所以：**动作本身一点没少** —— 情绪 → 动作那条路（EmotionMotionMath）、
        //   她自己挑动作、一次性的单子，全都照旧；去掉的只是**让他手动点一条让她演**的那个挑栏。
        //   那一栏是「看着身体挑」的设计（2026-10-09），现在他不想要这个入口了。
        //
        //   ★ 下面那几个函数（[buildMotionPicker] / [refreshMotionPicker] /
        //     [setMotionPickerOpen]）**留着但全是空转**：它们每一个开头都有
        //     `?: return`，视图没建出来就直接返回。留着是因为删干净要动的地方不少
        //     （点空处、设置页、换动作之后重刷），而每一处都是「她突然不动了」的风险。
        //   要恢复：把这一行加回来就行，一个字都不用改。
        // buildMotionPicker()
        // ★ 那颗圆按钮建出来时**默认**就是「发送」(三个忙的标志都还没置位),
        //   这里只是把话说明白:它的长相**只有一个来源**([refreshSendBtn]),
        //   别处谁也别去写 `sendBtn.text`。两口子各写一半的那种 bug 长这样:
        //   某个分支改了字没改背景,屏幕上是一颗白底白字的按钮 —— 看着没坏,按不到。
        refreshSendBtn()

        // ── 第四层:左右两块信息屏(2026-11-07) ─────────────────────────
        //
        //   他的原话:「房间现在太单调了，能不能左右两边挂一些东西」
        //   →「左边屏幕挂一个今日热点新闻，右边屏幕挂一个啥啥的」
        //   →「得有上下翻页的特效」「点进去得真有网页」「分析我的常看的内容」。
        //
        //   ★ 位置是效果图里的「乙」:挂在她头顶两侧。她站在正中间,头在画面偏上,
        //     两块屏落在头两侧的空处 —— 谁也不压谁。
        //   ★ **加在最后** = 盖在顶栏之上；两块的区域和顶栏、胶囊都不重叠,
        //     所以谁也不会被谁吃掉。
        buildRoomPanels()

        setContentView(root)

        // ── 让开系统栏 ──────────────────────────────────────────────
        // targetSdk 35+ 强制边到边;上一版真机截图里「ConMarn」上面叠着时间、
        // 右上角开关被电量和信号压住。把系统栏高度吃成内边距。
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(dp(0), bars.top, dp(0), bars.bottom)
            insets
        }

        // ── ★★ 把她和电脑那条线接上(2026-10-05)
        //
        //   用户报:「**明明是连着电脑,agent 说没有**」。
        //
        //   根因不在她说错,在**这条路一次连接都不会发起**:自动连上次那台电脑
        //   原来是 [MainActivity.autoConnectLast] 的私有方法,只有 MainActivity
        //   活着才会跑。而身份合并之后**这里是桌面唯一的入口**,从来不建它 ——
        //   于是她的脑子跑得好好的、会聊天,只有「电脑上的活」永远做不了,
        //   而那在她嘴里是一句听起来像「电脑那边出问题了」的话。
        //
        //   ★ 只在 `savedInstanceState == null` 时试,理由和 MainActivity 那边一样:
        //     旋转屏幕 / 从后台回来**不该重新连一次**(那会把活着的连接掐断重来)。
        //
        //   ★ `root.post` 而不是直接调:连接成功会一路回调到界面上(mirror 按屏幕
        //     尺寸算坐标之类),得等这一帧的布局量完。
        if (savedInstanceState == null) {
            if (PcLink.needsLocalNetPermission(this)) {
                // Android 16+ 没有这个权限,连内网**必超时而且不报错**
                // (见记忆 `android17-local-network-permission`)。拿到再连。
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK), PcLink.REQ_LOCAL_NET
                )
            } else {
                root.post { PcLink.connectLast(this) }
            }
        }

        // 开场:先让她出场,再说第一句。
        // 不叫模型 —— 本地写死一句。这层的意义是「有个开门的人」。
        //
        // ★★ 2026-10-05 用户报:「**我至今不明白「电脑和话」是什么意思**」——
        //   那句话是 `来啦?电脑和话,哪个都要?`,**是我写的**,而且它不通。
        //   他每次进房间都会看到它一次,看了好几天。
        //   ★ 教训(和 `ruoxi-eval-harness-traps` 那条同族):**写死在代码里的台词,
        //     不会有任何人替它做质量检查** —— 模型吐的胡话有人盯着,这几行没人看。
        //     所以这里的判据只有一条:**读一遍,是不是人话**。别为了「有性格」去造句,
        //     那正是「跟个傻子一样」的来源。
        //   ⚠️ 他明确说了后面会**自己调**她的性格、说话语气、内分泌系统(见记忆
        //     `conmarn-persona-is-his-to-tune`)—— 所以这几句刻意写得**平、短、不表演**,
        //     等他给方向再动,现在不该由我替她定人设。
        if ((agent().history ?: JSONArray()).length() == 0) {
            val hello = listOf("回来啦。", "嗯,我在。", "在呢,说吧。")
            window.decorView.postDelayed({
                say(hello[(System.nanoTime() / 1_000_000_000L).toInt() % hello.size])
            }, 1400)
        }
    }

    private fun setUpWebView(wv: WebView) {
        // 换装的两个目录(人物 / 房间)—— 建出来,各放一份人话说明。
        // 幂等而且很便宜(稳态就是四次 stat),所以放主线程没关系。
        // ★ 她出场不依赖这一步:建不出来就跳过,不报错(见 Wardrobe.ensure)。
        Wardrobe.ensure(this)

        wv.setBackgroundColor(0xFF160F13.toInt())
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 她自己会动,不许把一个手势当成「用户要播放媒体」的拦截理由
            mediaPlaybackRequiresUserGesture = false
            // assets 走 assetLoader(https://appassets.androidplatform.net/),
            // 不再需要 file:// 那一串放宽的开关 —— 那些开关是 WebView 的主要攻击面。
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        // WebView 默认底色是白的,她加载出来之前会先闪一下白 —— 深色房间里很扎眼
        wv.setBackgroundColor(Color.TRANSPARENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        wv.webViewClient = object : WebViewClient() {
            // 她的身体是一个会 fetch 的网页(three.js 要下载 .vrm 和 .js),
            // 而 file:// 下的 fetch 会被同源策略挡死。所以:网页假装挂在
            // https://appassets.androidplatform.net/her/ 这个域名下,实际内容从
            // APK 的 assets/her/ 里读出来 —— 同源成立、fetch 正常、WebView 的
            // allowFileAccess 那一串危险开关一个都不用开。
            //
            // 为什么不用官方的 WebViewAssetLoader:试过了,它对主框架文档返回 null
            // (真机日志:「拦截: .../her/index.html -> null」),而且它把 .vrm 猜成
            // text/plain。这里就三行匹配 + 一个 assets.open,自己写反而看得见。
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val u = request.url ?: return null
                // ★★ 2026-11-08(体检 B3)：**她这一页也不许绕过云端总闸。**
                //
                //   这里原来对 `appassets` 之外的地址一律回 null，意思是「交给 WebView
                //   自己去发」—— 而 WebView 的网络栈走的是**它自己**，根本不经过
                //   [CloudNet]。于是总闸关着的时候，房间里任何一个 `<img src="https://…">`
                //   (她写的 HTML、天气图、外部模型)照样**一个字节都不少地发出去**。
                //   同一个洞在 [WorkAppActivity] 那边已经堵过了，这里是漏掉的一处。
                //
                //   ★ `CloudNet.allow(host)` 放行本机/局域网，所以「连电脑」那条路不受影响。
                val scheme = u.scheme?.lowercase()
                if (scheme == "http" || scheme == "https" ||
                    scheme == "ws" || scheme == "wss"
                ) {
                    if (u.host != HER_HOST && !CloudNet.allow(u.host)) {
                        return WebResourceResponse(
                            "text/plain", "utf-8", 403, "Cloud Off",
                            mapOf("Cache-Control" to "no-store"),
                            java.io.ByteArrayInputStream(ByteArray(0)),
                        )
                    }
                }
                return serveAsset(u)
            }

            override fun onPageFinished(view: WebView, url: String) {
                Log.i(TAG, "她的页面加载完: $url")
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                Log.e(TAG, "页面出错: ${request.url} -> ${error.description} " +
                    "(code=${error.errorCode}, 主框架=${request.isForMainFrame})")
            }
        }
        wv.webChromeClient = android.webkit.WebChromeClient()
        wv.addJavascriptInterface(HerBridge(), "HerBridge")
        wv.loadUrl(HER_ORIGIN + "her/index.html")
    }

    /**
     * 把 https://appassets.androidplatform.net/her/xxx 映射成 APK 里的 assets/her/xxx。
     *
     * 任何一步不对都返回 null 并留下日志 —— 「她没出现」的时候,这一行日志就是
     * 「文件不存在」和「域名不对」的分界线,不用猜。
     */
    private fun serveAsset(url: android.net.Uri): WebResourceResponse? {
        if (url.host != HER_HOST) return null
        val path = url.path?.trimStart('/') ?: return null
        // 只放 her/ 这一个目录,别的路径一律不管(她的资源是 APK 内的,
        // 不给自己开一条「随便读 assets」的口子)
        if (!path.startsWith("her/")) return null

        val mime = when {
            path.endsWith(".html") -> "text/html"
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") -> "image/jpeg"
            // ★ 2026-10-06:背景那张图要用到。原来只有 .jpg —— `.jpeg` 和 `.webp`
            //   会掉到下面那个 octet-stream 去,浏览器认不认全看它心情(要静默就静默在这儿)。
            //   纯加行,不改任何一条已有分支。
            path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".webp") -> "image/webp"
            // ★ 2026-11-07:天景也可以是视频。给不对 MIME 的话 `<video>` 会直接拒播,
            //   而且**一句错都不报** —— 屏幕上就是「背景没换」。
            path.endsWith(".mp4") -> "video/mp4"
            path.endsWith(".webm") -> "video/webm"
            // .vrm 就是个 glTF 二进制,GLTFLoader 不看 MIME,但给对更省事
            path.endsWith(".vrm") -> "model/gltf-binary"
            path.endsWith(".glb") -> "model/gltf-binary"
            path.endsWith(".gltf") -> "model/gltf+json"
            // ★ 2026-10-08:她的动作(.vrma)。它和 .glb 一样是个 glTF 二进制容器
            //   (里面装的是骨骼每一帧转到哪),GLTFLoader 不看 MIME,
            //   但**给对更省事** —— 掉到 octet-stream 去,认不认全看浏览器心情,
            //   要静默就静默在这儿。纯加行,不改任何一条已有分支。
            path.endsWith(".vrma") -> "model/gltf-binary"
            path.endsWith(".bin") -> "application/octet-stream"
            else -> "application/octet-stream"
        }

        // ★★ 挑动作 —— 他自己从**库**里点名的那一条(2026-10-09)。
        //
        //   库里的文件**不在 APK 里、也不在 `motion/` 里** —— 它在
        //   `files/motion-library/` 下面。页面要拿到它,只能走这条专门开的路:
        //   地址前缀 `her/motion-lib/`,后面跟的**就是**库里那份相对路径。
        //
        //   ★★ 它必须排在下面 [Wardrobe.resolve] **之前**。`resolve` 是按**扩展名**
        //     分派的(`.vrma` → `motion/`),而库里的东西压根不在 `motion/` 里 ——
        //     让它去判必然落空,然后掉到 `assets.open`,结果是一条**静默的 404**,
        //     症状正是「她不动,一个字都不报」。这一条要先把它截住。
        //
        //   ★ 判定全在 [Wardrobe.motionFile] 里(路径白名单 + 扩展名白名单 + 库根包含,
        //     三道都是纯逻辑、都有单测)。这里只负责「问一句、写一行日志」。
        if (path.startsWith(LIB_PREFIX)) {
            val rel = path.removePrefix(LIB_PREFIX)
            val f = try {
                Wardrobe.motionFile(this, rel)
            } catch (e: Exception) {
                ModelManager.get(this).trace(
                    "动作库:读 $rel 出错(${e.javaClass.simpleName}: ${e.message})")
                null
            }
            if (f != null) {
                ModelManager.get(this).trace("动作库:页面取用 $rel(${f.length()} 字节)")
                return try {
                    WebResourceResponse(mime, null, f.inputStream()).apply {
                        setStatusCodeAndReasonPhrase(200, "OK")
                    }.also { Log.i(TAG, "动作库: $url -> ${f.absolutePath} (${f.length()} 字节)") }
                } catch (e: Exception) {
                    // 说好要给它这份、却打不开 —— 原因**必须留下**(不许静默)。
                    ModelManager.get(this).trace(
                        "动作库:$rel 打不开(${e.javaClass.simpleName}: ${e.message})")
                    null
                }
            }
            // ★★ **读不到也要留一行**。这一格要是静默,它和「你压根没挑过」
            //   在她那边长得一模一样 —— 而它其实有三种来源(白名单挡了 / 文件没了 /
            //   是坏文件),日志是唯一分得开它们的东西。
            ModelManager.get(this).trace("动作库:页面要的 $rel 读不到(挡了 / 不在 / 是坏的)")
            return null
        }

        // ★★ 换装 —— 「放文件就生效」的**全部机制就是这十几行**。
        //
        //   放在 `assets.open` **之前**:外部目录里有能顶掉这个请求的文件,就用它;
        //   没有(或者判定为坏文件)就掉下去读 APK 里那份 —— 也就是内置的那个人。
        //   判定在 [WardrobeMath](能单测),这里只负责「问一句、写一行日志」。
        //
        //   ⚠️ 日志**必须**写。这条路的失败长相是「我换了,它没变」——
        //   而那种失败**一个字都不会报**,和「我没放对文件夹」在屏幕上一模一样。
        //   这一行是唯一分得开它们的东西(判据见 WardrobeMath.sentence)。
        val hit = try {
            Wardrobe.resolve(this, path)
        } catch (e: Exception) {
            // 换装出错绝不能让她出不了场:记一笔,照旧读内置那份。
            ModelManager.get(this).trace("换装:判定出错(${e.javaClass.simpleName}: ${e.message})")
            null
        }
        if (hit != null) {
            ModelManager.get(this).trace("换装:${hit.note}")
            val f = hit.file
            if (f != null) {
                return try {
                    WebResourceResponse(mime, null, f.inputStream()).apply {
                        setStatusCodeAndReasonPhrase(200, "OK")
                    }.also { Log.i(TAG, "换装: $url -> ${f.absolutePath} (${f.length()} 字节)") }
                } catch (e: Exception) {
                    // 说好要用外部那份、却打不开 → **掉回内置那份**,并把原因留下。
                    ModelManager.get(this).trace(
                        "换装:${f.name} 打不开(${e.javaClass.simpleName}: ${e.message})," +
                            "退回内置那份"
                    )
                    null
                }
            }
        }

        return try {
            val stream = assets.open(path)
            WebResourceResponse(mime, null, stream).apply {
                setStatusCodeAndReasonPhrase(200, "OK")
            }.also { Log.i(TAG, "拦截: $url -> $mime, ${stream.available()} 字节") }
        } catch (e: Exception) {
            Log.e(TAG, "拦截: $url 打不开 assets/$path", e)
            null
        }
    }

    /** JS 侧的回执。她加载好了、她崩了、她点了什么,都从这里知道。 */
    private inner class HerBridge {
        @JavascriptInterface
        fun onReady() = runOnUiThread {
            herReady = true
            Log.i(TAG, "她的身体就位了")
            // ★ 她出场了 —— 把触摸让给场景(房间里的东西这才点得着)。
            //   顺序要紧:必须在 setObjects 之前让开?不,两者无关;
            //   但**必须在 herReady = true 之后**,因为那张纸的判据就是它。
            setOverlayEatsTouches(false)
            // 把等着的指令补发
            val queued = pendingJs.toList(); pendingJs.clear()
            queued.forEach { eval(it) }
            // ★ 先把房间那份布置推过去(他丢进来的 room.json / 房间模型,可以有可以没有)。
            //   排在摆东西**前面**:背景和相机是他给房间定的调子,东西按那个调子摆。
            pushRoom()
            // ★ 再摆房间里的东西。放最后:她先站好,东西再出现,
            //   而不是物件先浮在空中等她。
            pushRoomObjects()
            // ★ 然后是**她自己的动作**(他丢进 motion/ 的 .vrma,可以有可以没有)。
            //   排在摆东西**后面**是刻意的:动作要等她站好、房间摆好再套上去,
            //   而且它是**我们主动推**的(见 [pushMotion] 的 KDoc)。
            pushMotion()
            // ★ 窗外那片景。放在房间和动作之后:它只改窗玻璃那张贴图,
            //   不碰她的站姿,所以早一点晚一点都行 —— 但**必须在这里补一发**,
            //   因为上面那次 `pendingJs` 是排给「她还没加载好」那会儿的,
            //   页面刷新过之后那串可能已经跟着旧页面一起没了。
            pushSceneWeather(force = true)
            // ★ 每天第一次进来，她先开口（见 [maybeDayGreet]）。放在这里：
            //   她站好了、屋子亮了、天气和任务也都取得到，那句话才是有内容的。
            maybeDayGreet()
            // GPS：天气用它认位置（见 [Where]）。没给权限就一直是 IP 认城市。
            ensureLocation()
            // ★ 最后给她拍一张,给悬浮窗当「人形」。**晚一拍**是刻意的 ——
            //   见 [captureFigure] 的说明。重复进房间都拍,但内容一样就不落盘。
            root.removeCallbacks(figureShot)
            root.postDelayed(figureShot, FIGURE_DELAY_MS)
        }

        @JavascriptInterface
        fun onError(msg: String) = runOnUiThread {
            // WebView 里的报错在 logcat 里很难捞,直接摆到台面上,省半小时瞎猜
            Log.e(TAG, "她那边的错误: $msg")
            showLog("✗ 她: $msg")
            // ★ 2026-10-04 晚:这句话原来写在 [hintView] 上,而那个 View 连着自己
            //   的一条命一起被删了。「她没能出场」是**必须留着**的一句 ——
            //   它是「你眼前这个人是空的」唯一的解释。现在它写在玻璃胶囊上,
            //   那块地方本来就是「她对你说话」的位置。
            //   ★ 走 [showCaption] 而不是 [setCaptionPlaceholder]:前者会让
            //     `captionIsPlaceholder = false`,这句话于是**站得住** ——
            //     下一轮 `restCaption()` 不会拿「按住说话」把它擦掉。
            //     报错被一句占位话盖掉,是那种「一闪就没了」的坏。
            showCaption("她没能出场: $msg", fromHer = false)
            // ★ 她出不了场 —— 那张透明纸**必须顶回去**。
            //   不顶的话:场景加载失败 → 点哪都没反应 → 控件栏再也开不了
            //   → 你连话都没法跟她说。这是这一整套里最该验的一条兜底。
            setOverlayEatsTouches(true)
        }

        /** 她那边的「不是错误、但你想知道」。进 model.log,不打扰用户。 */
        @JavascriptInterface
        fun onNote(msg: String) = runOnUiThread {
            ModelManager.get(this@ConMarnActivity).trace("她那边: $msg")
        }

        /** 点中了房间里某件东西。id 是 [HandRegistry] 里那只手的 id。 */
        @JavascriptInterface
        fun onObjectTapped(id: String) = runOnUiThread { objectTapped(id) }

        /**
         * 点了房间里的空处 —— **把挑动作那一栏收回去**。
         *
         * ★ 它以前是 `toggleControls()` —— 点空处把工具日志翻出来。2026-10-04 晚那块
         *   日志被删了,这条路于是空了一段(当时那句「故意留空」写的就是那阵子)。
         * ★ 2026-10-09 晚它有了新差事。用户原话:「**点一下往下展开,再点屏幕其他地方,
         *   又缩回去的**」—— 后半句就是它。
         *
         * ★★ 这一件事**只能挂在它身上**,别处都挂不了:那张盖在房间上的透明纸
         *   (`overlay`)**不能**挂 `OnClickListener` —— 一挂上它 `clickable` 就永远是
         *   `true`,她就再也点不着房间里的东西了(见 [setOverlayEatsTouches] 那段)。
         *   而 JS 那边点空处**本来就会喊这一声**(已在 `her.bundle.js` 里核实
         *   `HerBridge.onEmptyTapped` 有调用方),所以这是白捡的一条路。
         *
         * ★ 必须 `runOnUiThread`:喊过来的是 JS 的线程,碰视图只能在主线程。
         *   (同 [onObjectTapped] / [onReady] 的写法。)
         */
        @JavascriptInterface
        fun onEmptyTapped() = runOnUiThread { setMotionPickerOpen(false) }

        /**
         * 一条「只播一次」的动作为什么停了 —— 她那边 `setMotion(url, {once:true, id})` 播完时来一声。
         *
         * ★ 今天它**只记事**,不改变任何行为 —— 用户定的第二条(「她话说完了,但动作还没做完,
         *   就等动作做完再说下一句」)要用它,而那一条里那个「等」**必须配硬超时**
         *   (记忆 `conmarn-emotion-to-motion` 里他自己写死的唯一一条)。那一步单独做。
         *
         * ★★ 但这一行**不是废的**:她是「谁最后说话」和「动作到底播完没有」这两件事
         *   唯一的交汇点。`setMotion` 那边文件坏了 / 压根没播 / 回调抛了,
         *   **这一行就不会出现** —— 而在这之前,「她没动」和「她动了但没播完」
         *   在日志上长得一模一样。
         *
         * ★ id 是她原样送回来的那张单子,和 [fireEmotionMotion] 里发出去的那串对得上。
         */
        @JavascriptInterface
        fun onMotionDone(id: String) = runOnUiThread {
            ModelManager.get(this@ConMarnActivity).trace("动作:她那边的单子回来了 #$id")
        }
    }

    /**
     * 那张透明纸吃不吃触摸。
     *
     * ★ 用**翻开关**而不是增删视图。`setOnClickListener` 会把 clickable 置 true,
     *   之后 `isClickable = false` 会让 `View.onTouchEvent` 返回 false ——
     *   FrameLayout 于是继续往下派发,事件落到下一层(WebView)手上。
     *   **监听器留着没删**:翻回 true 就立刻又能用,不用重装。
     *
     * ★ 不静默:每一次翻都留一行日志。这一路失效的样子是「点了没反应」,
     *   和「点错地方了」长得一模一样 —— 没有日志就只能靠猜。
     */
    private fun setOverlayEatsTouches(eat: Boolean) {
        overlay.isClickable = eat
        overlay.isLongClickable = eat
        ModelManager.get(this).trace("房间:透明纸${if (eat) "接管触摸(她还没出场)" else "让开(场景可以点了)"}")
    }

    // ------------------------------------------------------------------
    // 房间左右两块信息屏(2026-11-07)
    // ------------------------------------------------------------------

    /**
     * 把 [RoomPanels] 挂上去。关着的时候**一个视图都不建** —— 建出来再藏起来的话,
     * 那个每小时一次的取热点线程照样会起来。
     */
    private fun buildRoomPanels() {
        if (!RoomPanels.isOn(this)) return
        val rp = RoomPanels(this) { n -> openNewsItem(n) }
        roomPanels = rp
        // ★ 尺寸由 [RoomPanels] 自己按屏幕比例算（[RoomPanels.side] / [RoomPanels.columnH]），
        //   这里只负责**装上去**。横竖屏差得很远，写死的 dp 在横屏会吃掉将近一半屏高
        //   （他 2026-11-07 的原话是「占屏有点大」）。
        root.addView(rp, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(rp.columnH)
        ).apply {
            gravity = Gravity.TOP
            // 顶栏是 32dp 的按钮 + 8dp 上下内边距;再让开 6dp,两块屏不贴着她的名字
            topMargin = dp(54)
            leftMargin = dp(10)
            rightMargin = dp(10)
        })
        rp.start()
    }

    /** ⚙ 里那一行拨完开关之后重建（见 [toggleRoomPanels]）。 */
    private fun rebuildRoomPanels() {
        roomPanels?.let {
            it.stop()
            root.removeView(it)
        }
        roomPanels = null
        buildRoomPanels()
    }

    /**
     * 他点开了某一条热点。
     *
     * ★ 顺序是**先记账、再开网页**：开网页会把房间切到后台（[onPause] 里有事要做），
     *   账要在那之前落下来。
     * ★ 用系统浏览器（`ACTION_VIEW`），**不在 App 里内嵌** ——
     *   理由写在 [RoomPanels] 的文件头:各家新闻站对 WebView 的拦法五花八门,
     *   内嵌的结果是白屏,比打不开更坏。
     */
    private fun openNewsItem(n: NewsItem) {
        InterestStore.recordOpen(this, n.title)
        ModelManager.get(this).trace("房间:点开热点「${NewsMath.clip(n.title, 20)}」")
        val ok = try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(n.link)))
            true
        } catch (e: Exception) {
            ModelManager.get(this).trace("房间:打不开网页 ${e.javaClass.simpleName}:${e.message}")
            false
        }
        if (!ok) toast("这台手机上没有能打开网页的应用")
        // 点开的那条会沉下去、后面的顶上来 —— 当场重排，不用等下一次刷新
        roomPanels?.afterOpen()
    }

    /**
     * 「信息屏」那个开关。
     *
     * ★ 关掉之后房间立刻恢复原样（视图真拆掉），不是盖一层透明的东西糊弄。
     */
    private fun toggleRoomPanels() {
        val on = !RoomPanels.isOn(this)
        RoomPanels.setOn(this, on)
        rebuildRoomPanels()
        toast(if (on) "房间信息屏:开" else "房间信息屏:关")
    }

    // ------------------------------------------------------------------
    // 顶栏:名字 + 连接状态 + 三个开关
    // ------------------------------------------------------------------

    private fun buildTopBar() {
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = getDrawable(R.drawable.her_scrim_top)
        }
        // ★★ 左上角:连接状态那盏灯。**绿 = 连上电脑,红 = 没连。**
        //
        //   2026-10-04 晚用户原话:「在左上角显示绿灯红灯,绿灯就是连上电脑,
        //   红灯就是没连上」。它替掉的是原来那颗写着「电脑没连」四个字的按钮 ——
        //   **一眼看得出来的东西,就用一眼的量表达**,别占一整条文字的位置。
        //
        //   ★★ 2026-10-04 晚**它不再能按了**。用户原话:
        //     「红色绿色灯**不受屏幕点击影响**」。
        //
        //     上一版它是 `isClickable = true` + 点了跳电脑页面。那条路的用意是好的
        //     (身份合并摘掉 SplashActivity 入口之后,这是随手能够到电脑页的那一条),
        //     但它踩了两件更要紧的事:
        //
        //     ① **它长得不像按钮。** 一颗 20dp 的圆点被点中时,用户的解释是
        //        「我点屏幕,结果跳到电脑页面去了」,不是「我按到了那颗灯」——
        //        一个没有按钮长相的东西**不该有按钮的行为**。
        //     ② 房间里「点空白 = 开/关控件栏」是主交互。左上角那块地方**是空白的一部分**,
        //        手指稍微偏一点就被它截走 —— 而反馈是**换页面**,不是控件栏开合,
        //        于是他连「我刚才那一下到底点没点着」都判断不了。
        //
        //     去电脑页的正规路是**点房间里那台电脑**(`onObjectTapped` → `openTouchpad`),
        //     那条路一直都在,而且是「东西自己会说话」那条设计。这一条是多余的。
        //
        //   ★ 用的是现成的 `dot_green` / `dot_red`,和 `MainActivity` 那两颗
        //     (麦克风/摄像头指示灯)**同一套颜色** —— 同一件事在两个页面上
        //     长得不一样,是那种「看着别扭但说不上哪里不对」的坏。
        connDot = TextView(this).apply {
            // ★ 连 `isClickable` 都**不设**:默认就是 false(TextView 的可点性由
            //   clickable / onClickListener 任一个打开)。这里什么都不写才对 ——
            //   写了 `isClickable = false` 反而会让人以为「本来是能点的,被关掉了」。
        }
        topBar.addView(connDot, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(2) })

        // 中间这段空白只是把 ⚙ 顶到最右边 —— 它不显示任何东西
        topBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        // ★ 「扫描」:找局域网里那台电脑。为什么它在这儿、为什么是例外,见 [scanBtn]。
        //   ★ 图标**复用**书签条「终端」页那颗 [R.drawable.ic_scan],不新画一张 ——
        //     同一件事在两个页面上长得不一样,是那种「看着别扭但说不上哪里不对」的坏。
        scanBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_scan)
            // 和 ⚙ **同一套皮**:btn_her(近黑方块 + 8dp 圆角 + 一道粉描边)。
            // 字色取 [herButton] 的默认浅色 —— 两颗按钮摆在一条线上,得像一套。
            background = getDrawable(R.drawable.btn_her)
            setColorFilter(0xFFF2F3F5.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
            val p = dp(7)
            setPadding(p, p, p, p)
            // 它长得像按钮、也确实能按 —— 和左上角那盏灯正好相反,理由见 [connDot]。
            contentDescription = "扫描电脑"
            setOnClickListener { scanFromRoom() }
        }
        topBar.addView(scanBtn, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(6) })

        // ★★ 顶栏**只剩这一个按钮**。用户原话:「最顶上只留设置按钮」。
        //
        //   原先这一排还有「电脑没连」「🔊 出声」「唤醒:开」「主动:开」四个 ——
        //   一排带文字的开关,正是他说过的「工厂的控制板」。
        //   **四个一个都没丢,全都进了 [showSettings]**,而且在那里有了能写清楚的
        //   一行说明(顶栏那点宽度连「免打扰」三个字都放不下,更别说解释)。
        settingsBtn = herButton("⚙", 14f) { showSettings() }
        topBar.addView(settingsBtn, LinearLayout.LayoutParams(dp(40), dp(32)))

        root.addView(topBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP })

        // ★★ 2026-10-04 晚:这里原来是 `topBar.visibility = View.GONE` ——
        //   **顶栏和它上面那盏红绿灯、那个 ⚙ 全是藏着的**,要**点一下屏幕**才出来。
        //   用户原话:
        //     > 「能不能**不要点屏幕才能跳出设置和红绿色灯**」
        //
        //   ★ 这条要求的份量比它听起来重:**那一盏红绿灯是这个房间里唯一
        //     「电脑连没连上」的答案**。一个要你先点一下屏幕才知道「她能不能干活」的
        //     指示灯,等于没有指示灯 —— 而你点那一下的本意根本不是去看它。
        //     它当初被做成红色/绿色(而不是四个字)就是为了**一眼看得出来**;
        //     藏起来之后那个设计目的一个字都没剩下。
        //
        //   ★ 常驻的代价是顶栏会**一直**吃掉屏幕最上面那一小条触摸。
        //     它能吃到的只有那颗 ⚙(40×32dp),其余地方是空白 +
        //     一颗 `isClickable` 都没设的 TextView —— 触摸照样漏到下面那层去。
        //     同 [connDot] 那段里的第 ② 条顾虑,不重复了。
        //
        //   ⚠️ 这里**一个字都不写**就是常驻(View 默认 VISIBLE)。别在这儿补一句
        //      `setControls(…)` —— 这一刻 `bottomBar` 里那些孩子**还没建出来**,
        //      它们全是 `lateinit`,当场抛。
    }

    // ------------------------------------------------------------------
    // 下栏:字幕 + 日志 + 输入
    // ------------------------------------------------------------------

    private fun buildBottomBar() {
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(16), dp(8), dp(16), dp(10))
        }

        // ── ★★ 那块**工具日志**在 2026-10-04 晚被整个删掉了(不是藏起来)。
        //
        //   用户原话:
        //   > 「为什么那个房间点对话框外面,会显示一个**黑色对话框**,里面写着
        //   >   听着呢,松手就说完了,本地模型什么的,这个**不好看**,
        //   >   而且**感觉会影响最后的外设点击**,所以删掉」
        //
        //   那两句话各指一件事,两件都成立:
        //   · **不好看** —— 它本来是「默认藏」的(只看 `setControls` 那一行),
        //     但控件栏一露它就跟着露,而控件栏是**点一下房间就会开**的。
        //     所以实际效果是「随手点一下屏幕,房间里就多出一块深色的机器字」。
        //   · ★ **会影响点击** —— 这一条比好看重要:它占掉了房间底部
        //     `dp(72)` 的一条,**压在她以后要摆物件的地方上**(见「房间里摆什么」
        //     那一轮)。一个会把物件盖住的日志框,是这一轮必须让路的东西。
        //
        //   ★ **删的是显示,不是信息。** 那些行今天仍然写到 `model.log` 里
        //     (见 [showLog]),而且她干活的过程 `ConMarnBubble` 那边也收一份。
        //     这一点必须保住 —— 里面有一句「✗ 她: …」,那是**她出错时唯一的出口**;
        //     连它一起删掉的话,这个改动就等于「为了好看,把报警灯拆了」。

        // ── 那块**磨砂玻璃胶囊**:字幕 + 输入框 + 发送键,三样东西一个壳。
        //
        // ★★ 2026-10-04 晚用户拿着截图一次点了四件事:
        //   「右边那个麦克风和说字删掉,说话改成**按住对话框**,键盘输入改成**点对话框**,
        //     那个对话框参考我图片这个,做成**透明磨砂玻璃**的形式,
        //     发送键也和我图里一样,**一个小箭头代表发送**」
        //
        //   四条收到一起,落到结构上就是**一个壳、两个座位**:
        //     · 左边那格([textSlot])轮流坐着「字幕」和「输入框」
        //     · 右边那颗圆箭头永远在,它是**同一个发送键**,只是换了个长相
        //   🎤 按钮**没了** —— 说话不需要按钮,按这一整块就是说话。
        //
        // ★ 它还是**三合一**(字幕 / 麦克风 / 打断键),那部分一个字没变:
        //   ① **常驻**(空的时候写「按住说话,点一下打字」)—— 上一版的 🎤 住在
        //      `inputRow` 里,而 `inputRow` 默认 GONE、还会六秒自动收 ——
        //      也就是说**说话的路本来是藏起来的**,他找不到它就会说「听不到我说话」。
        //      麦克风不能是个需要先找到的东西。
        //   ② 手势分「点」和「按住」(见 [onCaptionTouch])
        //   ③ 「我在听」有个明确的说法 —— ★ 但**不由我们画**:系统右上角那盏灯就是
        //      (见下面 micHint 被删的那段)。字幕上那句「我在听…」是**按下的回执**,
        //      他一开口就被自己的字顶掉,不是状态灯。
        //
        // ⚠️ 关于那块玻璃**会不会又被删**(同一块地方他前后删过三次):
        //   前三次删的是「好看但不说明任何事」的东西。这一块说明的事是**它能按** ——
        //   一行悬空的白字没有边界,手指不知道该往哪儿落;有了胶囊,
        //   「按住了没有」「按在哪儿」都是看得见的。**边界就是手感。**
        // ── ★★ **她那行字** —— 摆在磨砂胶囊**上面**,不占胶囊里的座位。
        //
        //   ★ 顺序就是位置:`bottomBar` 是 VERTICAL 的,先加的在上面。
        //     所以这一句必须**排在 `bottomBar.addView(captionWrap, …)` 之前** ——
        //     挪到后面去的话她会掉到胶囊底下,而那正是他这次特意点出来的位置。
        herSay = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFFDF2F6.toInt())
            // ★ 描边和胶囊里那句同一个理由:**白字压在浅色的房间上时,
            //   这是唯一的可读性来源**。不是装饰。
            setShadowLayer(2f, 0f, 1f, 0x99000000.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
            // ★★ 2026-10-05 下午:它从「单行 + 横向滚动」改成了**折行**。
            //
            //   用户原话:
            //   > 「她说话如果不是按从左到右滚动式说话,要么就是会像现在一样
            //   >   **隐藏到一半后面没法显示**,要么不是滚动就会**占一大片屏**。
            //   >   有两种解法,第一是**像游戏一样按全屏的形式,但要缩小字体**」
            //
            //   ★ 那两个「要么」说的是**同一件事的两面**,根因是横向滚动:
            //     一行装不下就往外推,推到哪儿就看不见哪儿 —— 长句必然丢后半截。
            //     而它一开始是照抄 [caption] 的(见上面那句注释),那个抄法在
            //     **一行短占位话**上没问题,在**她整段回答**上就是错的。
            //
            //   ★ 现在的形状(游戏字幕那种):一张**通栏的、整体居中**的
            //     小字块,长句往下折而不是往右跑。位置就在原来那行(胶囊正上方),
            //     所以他没有丢掉任何东西,只是它**装得下了**。
            //   ★ 字号从 17f 收到 16f:折行之后同屏字数变多,不收就真的「占一大片屏」。
            //
            //   ★★ 2026-10-05 晚 —— 上面那版写了 `maxLines = 3` + 省略号收尾,
            //     于是**长回答的最后一句变成一个 `…`**。用户当场报:
            //     > 「**最后是以省略号的形式出现**,那太不好了,所以,**要么把字做小一点,
            //     >  不然不能全显示出来**」
            //
            //   所以那两个写死的属性**挪到 [fitHerSay] 里按句子的长短算**([CaptionFit]):
            //   句子越长字越小、折行空间越大。**光抬行数不收字会占一大片屏;
            //   光收字不抬行数还是装不下 —— 两个一起动才治得了。**
            //
            //   ★ 这里仍然留一个 `maxLines`:它是**构造时的初值**,
            //     任何一条写字的路上都会先被 [fitHerSay] 覆盖掉。
            //
            //   ★ `ellipsize` **保留,但降级成最后手段** —— 它现在只在
            //     「收到最小字号 + 七行还装不下」时才可能露面。那一下的省略号是
            //     **诚实的**(它在说「这里还有,我装不下」);★ 删掉它换来的
            //     「一声不响地在半句话上截断」比省略号糟得多。
            maxLines = CaptionFit.MAX_LINES
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        bottomBar.addView(herSay, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            // ★ 左右让出 24dp = 和底下那块胶囊**同一条对齐线**(见 captionWrap 那段)。
            //   两行字一样宽,看上去才像同一场对话的上下两句,而不是两个控件。
            marginStart = dp(24)
            marginEnd = dp(24)
            bottomMargin = dp(2)
        })

        captionWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.her_glass_pill)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

        // ★★ 这一格**必须是 [SlotFrameLayout]**,不能是普通 FrameLayout ——
        //   2026-10-05 用户第三次报「那个 ~~~ 还是没好」的真因就在这一个类名上。
        //   普通 FrameLayout 里,底下那条 `MATCH_PARENT` 高的水波会**反过来定义
        //   这一格有多高**(见那边的文件头),按住说话时整根胶囊涨成满屏。
        textSlot = SlotFrameLayout(this)

        caption = TextView(this).apply {
            textSize = 17f
            setTextColor(0xFFFDF2F6.toInt())
            // ★ 描边从 4px 降到 2px:底下现在有玻璃兜着,再糊一圈黑边就脏了。
            //   它不是装饰,是**白字压在浅色玻璃上时唯一的可读性来源**。
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            // ★★ `setSingleLine(true)` 一次给三件事,缺一不可:
            //   `maxLines = 1`(不再占一整块屏)+ `setHorizontallyScrolling(true)`
            //   (长句变成一条能滚的带子而不是被切掉)+ 去掉 ellipsize。
            //   ★ 它的**高度也就跟着变成常量** —— 这正是他说的
            //     「她说一次占我一整块屏幕」的解药:一句三行变一行。
            setSingleLine(true)
            setShadowLayer(2f, 0f, 1f, 0x99000000.toInt())
            visibility = View.GONE
        }
        textSlot.addView(caption, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        input = EditText(this).apply {
            hint = "打字跟她说…"; textSize = 14f
            setTextColor(0xFFFDF2F6.toInt()); setHintTextColor(0xCC9A828D.toInt())
            setSingleLine(true)
            // ★ 输入框自己的底色**去掉了** —— 它现在就住在玻璃里,
            //   再套一层 `her_input_bg` 就是「玻璃里再摆一个实心盒子」,双下巴。
            background = null
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
        }
        textSlot.addView(input, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ── 水波:按住说话时那一格显示的东西(见 VoiceWaveView)。
        //   ★ 高度 MATCH_PARENT,**不是** wrap_content:那一格的高度是字幕撑出来的,
        //     而字幕在放水波的时候是**空的但不是 GONE**(空的一行仍然占一行高)——
        //     这样切来切去的时候胶囊的高度纹丝不动。让它 GONE 掉的话,
        //     这一格会当场塌成 0 高,表现是「一按住说话,那根胶囊就缩了一下」。
        //
        //   ★★ 2026-10-05:上面那句话**当时只是愿望,机制上并不成立** ——
        //     `MATCH_PARENT` 的孩子在 `wrap_content` 的爸爸里拿到的是
        //     「爸爸的父容器还剩多少」(AT_MOST 那个数),所以它**反过来定义了这一格
        //     有多高**:按住 350 毫秒之后,这一格从 141px 涨到 1026px,胶囊跟着涨到满屏,
        //     波的振幅(高度 × 0.3)也跟着涨到三百多像素 —— 用户看到的就是
        //     「那条 ~~~~ 铺满了整个房间」。真机数字和成因见 [SlotFrameLayout] 文件头。
        //     **修法不在这一行,在 [textSlot] 是哪个类。**
        //   ★ 它是最后加进来的,所以画在最上层;它不吃触摸(`onTouchEvent` 返回 false),
        //     手势照旧落到 captionWrap 那个壳上。
        wave = VoiceWaveView(this).apply { visibility = View.GONE }
        textSlot.addView(wave, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 左边那格吃掉所有剩余宽度,右边那颗圆箭头固定 40dp。
        captionWrap.addView(textSlot, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // ── 🎤「回语音」:只在**打字那一态**露面(见 [voiceBtn] 那段)。
        //   ★ 它刻意压在**最右边、发送键旁边**:打字那一态里他刚敲完/正要敲,
        //     视线和手指都在右半边;摆到左边的话,他要先跨过整个输入框才够得着,
        //     而这一颗的全部意义就是「够得着」。
        captionWrap.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(0xFFFDF2F6.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
            val p = dp(8)
            setPadding(p, p, p, p)
            contentDescription = "说话"
            visibility = View.GONE
            setOnClickListener {
                // 回到语音态。★ 键盘必须在这儿收干净 —— 只把 input 设成 GONE 的话,
                //   输入法不认账,会继续霸着屏幕下半截,表现是「按了没反应,
                //   只是输入框没了」,而那时整颗胶囊正好被键盘顶着,按不到。
                setTypingMode(false)
                showLog("🎤 按住这儿说话")
            }
        }.also { voiceBtn = it }, LinearLayout.LayoutParams(dp(36), dp(40)))

        // ── 发送键:白色实心圆 + 一个箭头(见 btn_her_send)。
        //   ★ 字色是**深的** —— 它压在白圆上,浅色字会看不见。这是全房间唯一
        //     一颗深色字的按钮,原因只有这一个。
        //   ★★ 2026-10-05:它现在**按状态换职责** —— 她在忙时是「停」。
        //     派发在 [onSendOrStop],长相在 [refreshSendBtn],理由见那两处。
        sendBtn = herButton("↑", 20f, R.drawable.btn_her_send, 0xFF160F13.toInt()) {
            onSendOrStop()
        }
        captionWrap.addView(sendBtn, LinearLayout.LayoutParams(dp(40), dp(40)))

        // ── 手势:这一整块是**这一版语音交互的入口**
        //   ★ 接在壳上,所以**除了那颗箭头**,胶囊上任何一处都能按住说话 ——
        //     箭头是 ViewGroup 的孩子,触摸先给它,它不吃才轮到壳。
        captionWrap.setOnTouchListener { v, e -> onCaptionTouch(v, e) }

        // ★★ 2026-10-05:**左右各让出 24dp**。用户原话:
        //   > 「那个炫酷的 ~~~~~ 要在**透明磨砂玻璃中,而不是全屏**」
        //   之前这条胶囊是 `MATCH_PARENT` **一路顶到屏幕两边**的,
        //   而那条波浪又是横贯整块胶囊画的 —— 合起来在横屏上就是**一条横穿全屏的光带**,
        //   它读起来不像「一块玻璃」,像给整个房间罩了一层东西。
        //   让出两边之后,「这是一块能按的玻璃」这件事才回得来 ——
        //   而**边界正是手感**(见 [her_glass_pill] 那段):
        //   看不见边界的半透明色块,和一行悬空的字没有区别。
        //   ⚠️ 24dp 是**他挑的**(问过他一回,给了三个选项,他选了这一个)。
        //     别再自作主张收成居中的小药丸,也别把它改回 0。
        //
        //   ★★ 而且这一句只管**左右**。用户说的是「在玻璃中,而不是全屏」——
        //     一个「全屏」有两个方向,我那天只堵掉了横的那个,竖的那个一直漏着,
        //     直到 2026-10-05 他第三次报「那个 ~~~ 还是没好」才被抓出来
        //     (真凶是水波把胶囊撑满高,见 [SlotFrameLayout] 文件头)。
        //     **下一个人读到这行时看到的「修好了」是半边修好了。**
        bottomBar.addView(captionWrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(6)
            marginStart = dp(24)
            marginEnd = dp(24)
        })

        // ★ 这里曾经有一行「🎤 我在听…」。2026-10-04 晚被删了,原因记在这儿 ——
        //   **免得下一个人(包括我)再「为了隐私」把它加回来**:
        //   麦开着这件事**系统自己会说**,而且说得比我们权威 ——
        //   Android 12 起右上角有常驻的隐私指示器,CDD 明令它不得被应用遮挡;
        //   沉浸式全屏下它只是从状态栏挪到屏幕右上角,**不会消失**。
        //   这一条在该项目的记忆里躺了很久(「麦克风开着的时候界面上必须有明确的『我在听』」),
        //   现在它的**供应商换成了 Android 自己** —— 要求还在,只是不用我们画。
        //
        //   ★ 三次删掉同一个东西(紫雾 → 胶囊 → 这行字)之后,规律很清楚了:
        //     **他嫌的从来不是「有提示」,是「提示长得像装饰」。**

        // ★ 这里曾经有一行「点一下屏幕,跟我说话」 —— 2026-10-04 晚**删了**。
        //   不是因为它难看(它一直挺素),是因为它**从此刻起是句错话**:
        //   点屏幕现在开的是工具日志,不是说话的路。而现在该说的那句
        //   「按住说话,点一下打字」**已经印在玻璃胶囊上了** ——
        //   印在手该落下去的那块地方,胜过一个悬在半空的提醒。
        //   ★ 这就是它和 [captionWrap] 上那块玻璃的分工:
        //     **提示要长在你该碰的东西上,不是长在你该看的地方。**

        // ── 那层**紫雾**:玻璃胶囊底下垫着的那团光。
        //
        // ★★ 用户原话:「还是需要一个跟紫色雾一样的东西,但是一定不要难看」。
        //   它**不是**给字幕加的装饰(那样的话就是第四次删同一个东西了)——
        //   它铺在**整个房间的最底下**,一个控件也不碰,胶囊浮在它上面。
        //
        // ★ 它有一半是**功能**:磨砂玻璃要「磨」得住东西才叫磨砂。
        //   压在纯黑(`#160F13`)上,半透明的胶囊看上去只是一块灰;
        //   压在紫雾上,它才是**玻璃**。删了这层雾,胶囊当场变脏。
        //
        // ⚠️ 三条「不许长歪」的规矩:
        //   1. **不吃触摸** —— 没设 clickable、没设 listener,`onTouchEvent`
        //      返回 false,事件照常漏给下面那层(她没出场时的透明纸)。
        //      一给它加监听器,房间里就多出一片「点不动的死区」。
        //   2. **只占底下那一块**(见下面 300dp),不是整屏 —— 整屏就是给整个房间
        //      罩了层紫纱,那是「难看」的定义。
        //   3. 加在 `bottomBar` **之前**,所以它在胶囊下面,不在上面。
        val mist = View(this).apply { background = getDrawable(R.drawable.her_mist) }
        root.addView(mist, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(300)
        ).apply { gravity = Gravity.BOTTOM })

        root.addView(bottomBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM })

        // ★ **永远不置 GONE**。字幕住在这条 bar 里,bar 一隐,字幕就永远弹不出来 ——
        //   「只要出现字幕就是在页面上显示」是他 2026-10-04 点名的。
        //   这条 bar 里现在只有那块玻璃胶囊,它是**常驻**的。
    }

    // ------------------------------------------------------------------
    // 挑动作那一栏(2026-10-09)
    // ------------------------------------------------------------------

    /**
     * 房间里**最右侧**那一栏:能上下滑,点一下就换她正在做的动作。
     *
     * 用户原话(2026-10-09):
     * > 「在她房间的主页(**不是设置**)最右侧加上可以**上下滑动**的挑选动作的栏,直接这么干」
     *
     * ★★ 形状是被他这句话**三项锁死**的,别自作主张:
     *   ① **在主页,不在设置里** —— 挑动作是**看着她的身体挑**的事。
     *      躲进 ⚙ 点三层才能换一条,那是「配置」;摆在房间里点一下当场看得见,那才是「挑」。
     *      所以它**住在房间里、不进 ⚙**(和顶栏同一条理由,见 [buildTopBar] 末尾)。
     *   ② **最右侧** —— `Gravity.END`。
     *   ③ **能上下滑** —— 31 条动作一屏放不下,`ScrollView`。
     *
     * ★ 2026-10-09 晚他又加了一条:**可收起** ——
     *   「点一下往下展开,再点屏幕其他地方,又缩回去的」。收起时只剩顶上「动作」那一条
     *   (点它展开、点房间空处收起),见 [setMotionPickerOpen]。
     *
     * ⚠️ **它确实会吃掉右缘那一条的 WebGL 触摸** —— 这正是当初那条书签条被藏起来的原因
     *    (见 [scanBtn] 的 KDoc)。他点名要它、也点名要在最右侧,所以这一条是**他选的代价**;
     *    能做的只有把它做窄(见 [MOTION_PICKER_W])。真挡住房间里哪件东西了,他会先看出来的。
     *
     * ★ **一行按钮的清单来自 [Wardrobe.listMotions]**(磁盘上真有的那些),
     *   **不是写死的一串名字** —— 他以后往库里丢新文件,这一栏自己会长出来。
     *   第一行永远是「默认」= 不挑、用 `motion/` 那套(见 [MOTION_ROW_DEFAULT]),
     *   所以**挑过的能退回来**。
     */
    private fun buildMotionPicker() {
        val head = TextView(this).apply {
            text = "动作"
            textSize = 11f
            setTextColor(0xFFE05B8E.toInt())
            gravity = Gravity.CENTER
            maxLines = 1
            // ★ 上下各留 9dp:收起时**整栏就只剩这一条**,它得是一块点得着的把手,
            //   不能是一条 11sp 高的细线。(展开时它也顺带成了标题的那点呼吸。)
            setPadding(0, dp(9), 0, dp(9))
            // ★★★ 它**就是**开关本身 —— 点它展开 / 收起(见 [setMotionPickerOpen])。
            //   `setOnClickListener` 会把它置成 clickable,这正是这里要的:
            //   它是个按钮,不是装饰。(**别**照这一行去给 `overlay` 挂监听 ——
            //   那张透明纸的 clickable 是「让不让路」的开关,见 [setOverlayEatsTouches]。)
            setOnClickListener { setMotionPickerOpen(!motionPickerOpen) }
        }

        // ★ 这一格**就是**那一栏里的行容器(见 [motionPicker] 的字段声明)。
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val sv = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            // ★ 到头了不许再「弹一下」:那一格本来就是 WebGL 的地方,
            //   别再多吃一份过卷的手势。
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(col, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                // 约三成五黑:字看得清,后面她的身体也还透得出来 —— 它是**玻璃**,不是挡板。
                setColor(0x59120D10)
                setStroke(dp(1), 0x40E05B8E)
            }
            setPadding(dp(5), dp(6), dp(5), dp(6))
        }
        wrap.addView(head, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // ★ 高度给 0 + weight 1:让滚动区**吃掉剩下的全部**,
        //   而整栏的外框仍然是 MATCH_PARENT —— 内容多长都不会把它撑破。
        wrap.addView(sv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(wrap, FrameLayout.LayoutParams(
            dp(MOTION_PICKER_W), ViewGroup.LayoutParams.MATCH_PARENT
        ).apply {
            gravity = Gravity.END
            // 让开顶栏和底下那块字幕胶囊(它们各自常驻,见两处 KDoc)。
            topMargin = dp(52)
            bottomMargin = dp(96)
            marginEnd = dp(4)
        })

        motionPicker = col
        motionPickerWrap = wrap
        motionPickerScroll = sv
        motionPickerHead = head
        refreshMotionPicker()
        // ★ 摆完**立刻**按当前状态收一次。默认是收着的(见 [motionPickerOpen]),
        //   所以这一句就是「进门时只剩顶上那一条」的全部实现 ——
        //   不靠「先画成铺满、下一帧再收」,那样会闪一下。
        applyMotionPickerState()
    }

    /**
     * 把那一栏摆成「铺满」或「收成一条」的样子。
     *
     * ★★ **两件事必须一起做**,只做一件都不行:
     *
     *   * 只把滚动区藏掉(`GONE`)→ 外框还是 `MATCH_PARENT` 全高,右边仍然立着
     *     一整条**看不见的玻璃**,照样把 WebGL 的触摸吃掉 —— 而且你看着是收起来了,
     *     这种「收了但还在挡」是最难查的一种;
     *   * 只把高度换成 `WRAP_CONTENT` → 滚动区照样画,内容被裁成顶上第一行,看着像坏了。
     *
     * ★ 状态没变就直接返回:`onEmptyTapped` 会从她那边**频繁**喊过来
     *   (你每点一次房间空处就一次),每次都 `requestLayout` 是白花钱。
     *
     * ★ 箭头只说**点了会怎么样**,不描述现在是什么样:
     *   收着时是「动作 ▼」(点了往下展开),铺满时是「动作 ▲」(点了收起来)。
     *   ★★ 这两个符号是**读过了字体表才敢用**的(`adb pull` 下来用 fontTools 读 cmap):
     *     `▼` U+25BC 和 `▲` U+25B2 在 NotoSansSymbols 子集**和** Noto CJK 全部 5 个
     *     face 里都有 —— 两个独立的提供方,和已经在用的 `←` / `↓` 同一档安全。
     *     (更小巧的 `▾` U+25BE **只有符号子集一个提供方**,所以没用它。)
     */
    private fun setMotionPickerOpen(open: Boolean) {
        if (open == motionPickerOpen) return
        motionPickerOpen = open
        applyMotionPickerState()
        // ★ 留一行日志,理由和 [setOverlayEatsTouches] 那条一样:这一路失效的样子是
        //   「点了没反应」,和「点错地方了」长得一模一样 —— 没有日志就只能靠猜。
        //   ★ 只在**真的翻了**的时候写(上面那个 return 挡着),所以不会刷屏。
        ModelManager.get(this).trace("房间:动作栏${if (open) "展开" else "收起"}")
    }

    private fun applyMotionPickerState() {
        val wrap = motionPickerWrap ?: return
        val sv = motionPickerScroll ?: return

        sv.visibility = if (motionPickerOpen) View.VISIBLE else View.GONE
        (wrap.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.height = if (motionPickerOpen) {
                ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
            wrap.layoutParams = lp
        }
        motionPickerHead?.text = if (motionPickerOpen) "动作 ▲" else "动作 ▼"
        wrap.requestLayout()
    }

    /**
     * 照磁盘上真有的动作,**重铺**那一栏,并把当前生效的那条点亮。
     *
     * ★ 每次点完都要重刷(不只是第一次)—— 点亮的那个是**他挑的那条**,
     *   所以刷新的判据是 [Wardrobe.chosenMotion],不是「谁排第一」。
     *
     * ★★ **坏文件必须说出来,不许静默藏掉**:库是个他随时会往里丢文件的目录,
     *   半截下载 / 写到一半断电都会留下 0 字节的文件。只把好的那一半摆出来、
     *   另外半个一个字不说 —— 他看到的就是「我明明放进去了,这一栏里没有」。
     *   所以 [Wardrobe.MotionList.skipped] 一律写进日志。
     */
    private fun refreshMotionPicker() {
        val col = motionPicker ?: return
        col.removeAllViews()
        motionRows.clear()

        val list = try {
            Wardrobe.listMotions(this)
        } catch (e: Exception) {
            ModelManager.get(this).trace(
                "动作库:列目录出错(${e.javaClass.simpleName}: ${e.message})")
            null
        }
        if (list != null && list.skipped.isNotEmpty()) {
            // ★ 这一行是「我放进去了它怎么没出现」唯一的分界线。
            ModelManager.get(this).trace(
                "动作库:跳过 ${list.skipped.size} 个用不了的 —— ${list.skipped.joinToString("、")}")
        }

        // ★ 第一行永远是「默认」:不挑 = 用 `motion/` 那套。**挑过的能退回来。**
        addMotionRow(col, MOTION_ROW_DEFAULT, "默认")
        for (m in list?.usable.orEmpty()) addMotionRow(col, m.rel, m.label)

        // ── 点亮当前生效的那条 ──────────────────────────────────────
        // ★ 挑的那条**文件没了**时(删了 / 改名了),它就不在 [motionRows] 里 ——
        //   那时光点回「默认」。她在那边其实已经**回退到 motion/ 那套**了
        //   (见 [Wardrobe.activeMotion]),所以「默认」亮着是**说实话**;
        //   而那条退回的说明已经由 pushMotion 写进日志了。
        val want = try { Wardrobe.chosenMotion(this) } catch (e: Exception) { null }
        val on = if (want != null && motionRows.containsKey(want)) want else MOTION_ROW_DEFAULT
        for ((rel, b) in motionRows) {
            val lit = rel == on
            b.background = getDrawable(if (lit) R.drawable.btn_her_primary else R.drawable.btn_her)
            b.setTextColor(if (lit) 0xFF120D10.toInt() else 0xFFF2F3F5.toInt())
        }
    }

    /** 那一栏里的一行。[rel] 是空串时表示「默认」(见 [MOTION_ROW_DEFAULT])。 */
    private fun addMotionRow(col: LinearLayout, rel: String, label: String) {
        val b = herButton(label, 11f, R.drawable.btn_her, 0xFFF2F3F5.toInt()) {
            onMotionPicked(rel)
        }.apply { ellipsize = android.text.TextUtils.TruncateAt.END }
        col.addView(b, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(32)
        ).apply { bottomMargin = dp(4) })
        motionRows[rel] = b
    }

    /**
     * 他点了某一条 —— **当场换**,不重开房间、不重启她。
     *
     * ★ 为什么不用重进房间:[Her.setMotion](her.js) 自己就会把上一条 `stop()` 掉再装新的,
     *   它是可重入的。所以这里只要「记住 + 推一次」,她**下一帧**就换过来了 ——
     *   这正是「看着她的身体挑」这件事成立的全部前提。
     *
     * ★ [rel] 是空串 = 退回默认:把挑过的那条**忘掉**(存 null),`motion/` 那套重新生效。
     *   ★ 这里**只是忘掉一个偏好**,不动任何文件 —— 「删任何东西前先问」那条规矩在这条路上
     *     天然成立:`motion/` 里那个文件**一个字节都不碰**(见 [Wardrobe.activeMotion])。
     */
    private fun onMotionPicked(rel: String) {
        try {
            Wardrobe.setChosenMotion(this, rel.ifEmpty { null })
        } catch (e: Exception) {
            ModelManager.get(this).trace(
                "动作库:记下选择出错(${e.javaClass.simpleName}: ${e.message})")
        }
        refreshMotionPicker()
        pushMotion()
    }

    // ==================================================================
    // 她的身体:和 WebView 说话
    // ==================================================================

    /** 调 her.js 暴露的方法。她还没加载好就先排队,加载完补发。 */
    private fun her(js: String) {
        if (herReady) eval("window.Her && Her.$js")
        else pendingJs.add("window.Her && Her.$js")
    }

    // ------------------------------------------------------------------
    // 窗外那片景跟着现实天气走
    // ------------------------------------------------------------------

    /** 上次推过去的那一串（见 [pushSceneWeather] 为什么按字串比较）。 */
    private var sceneWx = ""

    /** 上次读盘时间。天气**盘上那份每小时才更新**,没必要两秒读一次文件。 */
    private var sceneAt = 0L

    /**
     * 把现实天气推给窗外那层。
     *
     * 三件事刻意写在这里而不是 `room.js` 里：
     *
     * 1. **天气从哪来**。`room.js` 不该知道 open-meteo、不该读盘、更不该发请求 ——
     *    它只管画。请求那一路归 [Weather]，它守着「云端总闸」那道总闸。
     * 2. **多久看一次**。`Weather.cached` 每次都读盘，两秒一次读文件不值当，所以卡在
     *    [SCENE_EVERY_MS]；而**天光**一小时才换一档，也够快。
     * 3. **只发变化**。结果按字串逐字比较：一样就不发，`room.js` 也就不会白白重画一张贴图。
     *    这也是 [SceneWeather.json] 手拼、不用 `JSONObject` 的原因（键序不保证）。
     */
    private fun pushSceneWeather(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - sceneAt < SCENE_EVERY_MS) return
        sceneAt = now
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val w = Weather.cached(this)
        // ★ 天光按**他所在的地方**算（太阳高度角），不是按钟点猜：一月的七点和七月的七点
        //   在上海窗外根本不是一回事。没有坐标（定位没给）才退回那张固定钟点表。
        val fix = try {
            Where.cached(this)
        } catch (_: Exception) {
            null
        }
        val phase = SceneWeather.phaseAt(fix?.lat, fix?.lon, now, hour)
        // ★ 换天：有对应那张照片就整张换掉（见 WxPhoto）。没有就什么都不动。
        val bg = try {
            WxPhoto.pick(this, phase, SceneWeather.kind(w?.code ?: -1))
        } catch (_: Exception) {
            null
        }
        val json = SceneWeather.json(w, phase, bg)
        // ★ `force` = **不看字串也要发一次**。页面是会被整个换掉的（她重载、WebView 重建），
        //   换掉之后新页面什么都没收到，而我们手里那串「上次发的」还是旧的 ——
        //   只比字串的话，要等到天气真的变了才会补上，中间这一段窗外是默认的夜晴。
        if (!force && json == sceneWx) return
        sceneWx = json
        // ★ 留一行账。窗外那层画错了是**看不出是不是没送到**的（默认就是夜晴）。
        //   只在真发的时候写，所以它同时是「链路通没通」的探针。
        try {
            ModelManager.get(this).trace("窗外:$json")
        } catch (_: Exception) {
        }
        her("setWeather($json)")
    }

    // ------------------------------------------------------------------
    // 每天第一次进来，她先开口（见 DayGreet）
    // ------------------------------------------------------------------

    /** 今天那句还在跑模型。防的是 onReady 短时间里来两次。 */
    private var dayGreetBusy = false

    /**
     * 每天第一次进房间，让她说一句「早上好 + 今天天气 + 要不要带伞 + 今天有什么事」。
     *
     * 三件事刻意这么做：
     *
     * 1. **先记账再说**。模型要跑几十秒，这中间他可能退出去又进来 ——
     *    不先落一个「今天说过了」，就会排队再念一遍。
     * 2. **在后台线程**。`spontaneous` 是一次完整的模型调用，放主线程就是几秒的卡顿。
     * 3. **模型说不出话也得有一句**（[DayGreet.fallback]）。她站在屋里却一声不吭，
     *    比一句朴素的话更像坏了。
     */
    private fun maybeDayGreet() {
        if (dayGreetBusy) return
        val day = DayGreet.dayKey(System.currentTimeMillis())
        if (DayGreet.greeted(this, day)) return
        DayGreet.mark(this, day)
        dayGreetBusy = true

        Thread({
            var line = ""
            try {
                val now = System.currentTimeMillis()
                val hour = DayGreet.hourOf(now)
                TaskStore.init(this)
                val tasks = DayGreet.taskLines(TaskStore.all(), now, RoomPanelMath.endOfDay(now))
                // ★ 盘上那份太旧就当场补一次 —— 「要不要带伞」问的是**接下来两小时**，
                //   拿三个小时前的天气回答等于没回答。★ 但必须过「云端总闸」那道总闸：
                //   关着就一个字节都不往外发，宁可说「不知道」。
                var w = Weather.cached(this)
                if ((w == null || now - w.at > WEATHER_FRESH_MS) &&
                    AiAgent.loadConfig(this).cloudOn
                ) {
                    w = Weather.fetch(this) ?: w
                }
                val facts = DayGreet.facts(hour, RoomPanelMath.dayLabel(now), w, tasks)
                line = AiAgentHolder.peek()?.spontaneous(DayGreet.hint(facts))?.trim().orEmpty()
                if (line.isBlank()) line = DayGreet.fallback(hour, w, tasks)
            } catch (_: Exception) {
                line = ""
            } finally {
                dayGreetBusy = false
            }
            if (line.isBlank()) return@Thread
            // 记一笔:这句话对不对、是不是模型说的,只有日志能分出来。
            try {
                ModelManager.get(this).trace("每天第一句:$line")
            } catch (_: Exception) {
            }
            runOnUiThread { if (!isFinishing) onSpontaneous(line) }
        }, "day-greet").apply { isDaemon = true }.start()
    }

    /**
     * GPS。天气有它就不必靠出口 IP 认城市（见 [Where]）。
     *
     * ★ 只问一次。他不给就永远走 IP 那条路，功能不受影响，也不再弹框。
     */
    private fun ensureLocation() {
        if (Where.granted(this)) {
            Thread({ Where.refresh(this) }, "where").apply { isDaemon = true }.start()
            return
        }
        if (Where.asked(this)) return
        Where.markAsked(this)
        requestPermissions(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            REQ_LOC,
        )
    }

    private fun eval(js: String) = runOnUiThread {
        webView?.evaluateJavascript(js, null)
    }

    // ------------------------------------------------------------------
    // 给悬浮窗拍一张「人形」
    // ------------------------------------------------------------------

    /**
     * 从**活着的场景**里拍一张全身照,存成悬浮窗那张脸。
     *
     * 用户原话:「**悬浮窗没有她的脸**」「能不能就是把悬浮窗改成**人形**」。
     *
     * ★ 为什么不塞一张图片进 res/ 了事:圆头像里那张是**照片**,房间里这个是**她**。
     *   换张图顶上,只是把「这是谁」的问题换了个样子 —— 他看到的仍然不是她。
     *   这个函数的意义就在于那张图是**从她本人身上扒下来的**。
     *
     * ★ 为什么要晚一拍:onReady 是 VRM 刚解析完的那一刻。那时她还没站稳
     *   (待机动作会把她挪一点),而且第一帧的姿势是绑定姿势、表情是空的。
     *   等一秒多,她才真的是「她」。
     *
     * ★ 失败是**正常路径**,不是异常:她还没出场、包围盒量不出来、
     *   WebGL 上下文丢了 —— 都会回一个空串。空串就让盘上那张旧的留着,
     *   悬浮窗**退回头像圆球**(见 [ConMarnBubble.realShow])。不许把空的存下去,
     *   那样悬浮窗会变成一块空白,比没有脸更糟。
     */
    private fun captureFigure() {
        val wv = webView ?: return
        try {
            wv.evaluateJavascript(
                "(window.Her && Her.captureFigure) ? Her.captureFigure() : ''"
            ) { raw -> saveFigure(raw) }
        } catch (e: Exception) {
            ModelManager.get(this).trace("人形:拍不了(${e.javaClass.simpleName})")
        }
    }

    /** 把 evaluateJavascript 回传的 dataURL 落盘。**这是唯一写 [CONMARN_FIGURE_FILE] 的地方。** */
    private fun saveFigure(raw: String?) {
        // ★ 回传的是**JSON 编码过的字符串**(带引号、内部 `/` 被转义成 `\/`),
        //   不是裸的 dataURL。少解这一层,`startsWith("data:image/")` 恒为 false,
        //   症状是「拍了但永远存不下来」,而且一行报错都没有。
        val url = try {
            if (raw.isNullOrEmpty() || raw == "null") null
            else JSONTokener(raw).nextValue() as? String
        } catch (_: Exception) {
            null
        }
        val comma = url?.indexOf(',') ?: -1
        if (url.isNullOrEmpty() || comma < 0 || !url.startsWith("data:image/")) {
            ModelManager.get(this).trace("人形:她还没量出来,这次不拍(留旧的)")
            return
        }
        val png = try {
            Base64.decode(url.substring(comma + 1), Base64.DEFAULT)
        } catch (e: Exception) {
            ModelManager.get(this).trace("人形:解不开(${e.javaClass.simpleName})")
            return
        }
        if (png.size < 1024) {       // 一张 280x700 的 PNG 不可能这么小 —— 那是空白
            ModelManager.get(this).trace("人形:图太小(${png.size} 字节),当没有")
            return
        }

        val live = File(filesDir, CONMARN_FIGURE_FILE)
        // ★ 一样就不写。房间每开一次都会拍一张,姿势通常一模一样 ——
        //   每次都覆写等于白白磨闪存,而这台机器上闪存是她的寿命。
        if (live.isFile && live.length() == png.size.toLong() && live.readBytes().contentEquals(png)) {
            ModelManager.get(this).trace("人形:和盘上那张一样(${png.size} 字节),不重写")
            return
        }

        // ★ 先写临时文件再改名。悬浮窗是**另一个进程里的另一个窗口**,
        //   它可能正好在我们写到一半时去读 —— 读到半张 PNG 的样子是
        //   「她的脸缺一半」,而且不报错。改名是原子的,读到的永远是完整的那张。
        val tmp = File(filesDir, "$CONMARN_FIGURE_FILE.tmp")
        try {
            tmp.writeBytes(png)
            if (!tmp.renameTo(live)) {
                live.writeBytes(png)
                tmp.delete()
            }
            ModelManager.get(this).trace("人形:拍好了(${png.size} 字节 → ${live.absolutePath})")
        } catch (e: Exception) {
            tmp.delete()
            ModelManager.get(this).trace("人形:存不下(${e.javaClass.simpleName}: ${e.message})")
        }
    }

    // ==================================================================
    // 说话:字幕 + TTS + 嘴型
    // ==================================================================

    /**
     * 她开口了。
     *
     * 这一句同时走三条路:
     *   1. 字幕 —— 看得见
     *   2. TTS  —— 听得见
     *   3. Her.setTalking + speakRange —— 嘴动得对得上
     *
     * 第 3 条是这次新加的关键:以前她是个静态头像,说什么都一个表情。
     */
    private fun say(line: String) {
        sayCaptionOnly(line)
        speak(line)
    }

    /**
     * 把她的话摆到那行字上,**不出声**。
     *
     * ★ 它是给「先出声」那条路拆出来的:那一轮里声音被切成了两段
     *   (头一块在生成途中就先念了,见 [speakEarly]),但
     *   **字幕上仍然是整句** —— 她说的本来就是一整句。
     *   所以定稿时要「上整句的字 + 只补念剩下的那半截」,这两件事必须能分开做。
     *
     * ★ 原样保留 2026-10-05 那条更正:从 [showCaption] 改成 [typeHerSay] ——
     *   她的话搬到胶囊**上面**那行去了(见 [herSay]),顺带甩掉了 [CaptionDwell]
     *   那套排队(那是为「占位话别一闪而过」设计的;她的话现在不和占位话抢同一行,
     *   再排队只会让她晚说一句)。
     */
    private fun sayCaptionOnly(line: String) {
        herSay.setTextColor(0xFFFDF2F6.toInt())
        // ★ 她定稿的话 = 基准 16;长句由 [fitHerSay] 在这之上收小(见 [CaptionFit])。
        laneBaseSize = 16f
        fitHerSay(line.length)
        // ★ 定稿落地 —— 从这一刻起,那行字上摆的是**一句说完的话**
        //   (见 [laneFinal])。草稿期来的空 partial 只能撤草稿,撤不动它。
        if (line.isNotEmpty()) laneFinal = true
        typeHerSay(line)
    }

    // ------------------------------------------------------------------
    // 打字机(2026-10-04 晚,用户点名)
    //
    //   > 「说话是从左往右说,然后就是像打字机一样的从左往右,**说完的字往左移**,
    //   >   不然她说一次占我一整块屏幕」
    //
    //   ★★ 2026-10-05 下午:这一套**从胶囊搬到了上面那行**([herSay]),
    //     而那句「说完的字往左移」**被换掉了** —— 它正是他后来报的
    //     「隐藏到一半后面没法显示」的根因(横向滚动推到哪儿就看不见哪儿)。
    //     现在是**折行**,见 [herSay] 建它时那段。
    //
    // 留下来的那条规矩一个字没变,而且现在**只有一处**打字机了:
    //   · **从左往右一个字一个字出来** = [herSayTicker] + [herSayShown]
    //   · **字幕只在 [herSay] 上**(她的、他的、草稿 —— 谁最后说谁占着),
    //     胶囊里只剩占位话 / 输入框 / 水波。
    //
    // ★ 为什么必须**做成「追一个目标」而不是「一段一段念出来」**:
    //   她的回复是流式来的(`onPartial` 每来一版就是一次更长的目标)。
    //   写成「按顺序播放已收到的段落」的话,流式只会让队列越来越长,
    //   字幕会**越拖越远**,追到她说完了字还没出完。
    //
    // ★ 这里原本还有一个 `typeCaption()` + `captionTicker`(胶囊里的那台打字机)。
    //   2026-10-05 下午随「他的字也搬去上面那行」一起删了 —— 它再没有调用方。
    //   删它的理由和 [showWave] 下面那段一样:**死代码最贵的地方不是它占的那几行,
    //   是下一个人会认真读它,然后以为这条路是活的。**
    //   它换来的那两条经验没有丢,都留在这段注释里:
    //     ① 「追一个目标」而不是「一段一段念」(见上);
    //     ② **「把字写上屏」不能全托给 ticker** —— ticker 的第一句是
    //        「已经吐够了 → return」,于是**目标变短时它一个字都不写**,
    //        文本会留在上一句上谁也不去改它。真机症状是「她说了话却没显示」。
    //        [typeHerSay] 从第一天就照办了(先写当前进度,再决定要不要继续吐)。
    // ------------------------------------------------------------------

    /** 立刻显示整句,不做动画。占位话(「按住说话」)用它 —— 那个不需要打字机。 */
    private fun setCaptionNow(text: String) {
        // ★ 这是**有人直接动手写字幕了** —— 排队等着的那么一句真话已经过期,撤掉。
        //   不撤的话会出现「他早干了别的,屏幕忽然冒出一句两秒前的话」。
        cancelDeferredCaption()
        captionTarget = text
        captionShown = text.length
        caption.text = text
        scrollCaptionTail()
    }

    /**
     * 撤掉排队的那句真话。**任何直接写字幕的地方都要调它** ——
     * 见 [deferredCaption] 那段里那条「晚了就会看起来像她答非所问」。
     */
    private fun cancelDeferredCaption() {
        deferredCaption = null
        root.removeCallbacks(flushDeferredCaption)
    }

    /**
     * 停掉打字机。打断她、收工、以及**有人要整段直接写字幕**的时候调 ——
     * 否则她的话会**在她闭嘴之后**继续往外爬,或者爬回来把刚写上的字盖掉。
     *
     * ★★ 2026-10-05 下午:它停的从「胶囊里那台」换成了**上面那行那一台**
     *   ([herSayTicker])—— 全屏现在只有一台打字机了(见上面那段)。
     *   名字没改(项目规矩:不改名),但**它管的座位换了**,这一点要记住。
     */
    private fun stopCaptionTicker() {
        root.removeCallbacks(herSayTicker)
    }

    // ------------------------------------------------------------------
    // 那行字([herSay])的打字机 —— ★ 现在是**全屏唯一的一台**
    //
    //   ★ 状态仍和 `captionTarget` / `captionShown` **各一套**(见 [herSayTarget]
    //     的注释):两套状态混在一起,她的一句话会把他那句吐到一半的进度顶掉。
    // ------------------------------------------------------------------

    private val herSayTicker = object : Runnable {
        override fun run() {
            val t = herSayTarget
            if (herSayShown >= t.length) {
                // 追完了 —— 那颗圆按钮的「停」该收回去了(见 [refreshSendBtn])。
                refreshSendBtn()
                return
            }
            // 落后得多就一次多吐几个:追平比匀速重要。
            val left = t.length - herSayShown
            val step = when {
                left > 80 -> 4
                left > 24 -> 2
                else -> 1
            }
            herSayShown = minOf(t.length, herSayShown + step)
            herSay.text = t.substring(0, herSayShown)
            root.postDelayed(this, CAPTION_TYPE_MS)
        }
    }

    // ★ 这里曾经有一个 `scrollHerSayTail()`(「说完的字往左移」)。
    //   2026-10-05 下午跟着 `herSay` 的折行一起删了 —— 那行字现在**换行**,
    //   不再有「装不下、往右推」这回事,横向滚动也就没有可滚的。
    //   ★ 用户报的那个毛病正是它造成的:「**隐藏到一半后面没法显示**」——
    //     推到哪儿就看不见哪儿,长句必然丢后半截。
    //     删它的理由和下面那段一样:**死代码最贵的地方不是它占的那几行,
    //     是下一个人会认真读它,然后以为这条路是活的。**

    /**
     * 把这句设成她那行字的**目标**,让打字机去追。
     *
     * ★ 重复调用(流式)安全:目标换长了就接着往下吐,不回退、不重头再来
     *   —— 回退的话每一版 partial 都会让那句话从第一个字重新长一遍,看起来像抽搐。
     *
     * ★★ 空串**必须清屏**,不能只是「写个空串上去」——
     *   `AiAgent` 里那句 `cb.onPartial("")` 的用意正是**撤掉规划时的自言自语、
     *   别让它冒充结果**。这一条是 `caption` 那边用真机 bug 换来的
     *   (见 [typeCaption] 里那段),这里从第一天就照办。
     */
    private fun typeHerSay(full: String, fromHer: Boolean = true) {
        // ★★ 空串 = 「撤掉这一版」。**但一句已经说定的话撤不动** —— 见 [laneFinal]。
        //   定稿之后来的那条空 partial 撤掉的,恰恰是他抬头要看的那句真话;
        //   而它的症状是「字一闪而过」,不报错、日志干净。
        //
        //   ★ 锁由**调用方**决定,这里只认它:`onPartial` 上的是草稿(解锁)、
        //     `sayCaptionOnly` / `liveCaption` / `applyCaption` 上的是说定的话(上锁)。
        //     写字的人不该猜这一笔算不算数 —— 那种猜法迟早会猜错一处。
        if (full.isEmpty()) {
            // ★★ 这一条**故意绕过记录仪的去重**(见 [laneTrace]):它状态没变
            //   (上面那句说定的话原样留着),可「有一条空串来过、被挡住了」本身
            //   就是我们要找的那个凶手。**状态没变的事件,才最需要留痕。**
            if (laneFinal) {
                ModelManager.get(this)
                    .trace("字幕: 空串想撤,被说定锁挡住 → 上面那句留着")
                return
            }
            clearHerSay()
            return
        }
        herSayTarget = full
        // 已经吐出去的那截留着,不回退。上面 [herSayShown] 的夹取同理。
        if (herSayShown > full.length) herSayShown = full.length
        root.removeCallbacks(herSayTicker)
        herSay.text = full.substring(0, herSayShown)
        // ★ 2026-10-05 晚:她说的话不亮出来(用户嫌她的字幕不美观);
        //   他自己说的话(语音实时转写 + 打字)仍然亮着。
        //   ★★ 关键:不能不碰 visibility —— 他的话(line 3318)把 herSay 设成 VISIBLE 之后,
        //   她的回应如果不显式设回 GONE,VISIBLE 会一直带着,她的字幕就"又有了"。
        herSay.visibility = if (fromHer) View.GONE else View.VISIBLE
        laneTrace("写上屏")
        if (herSayShown < full.length) {
            root.post(herSayTicker)
            refreshSendBtn()
        }
    }

    /**
     * ★★ 2026-10-05 傍晚:那行字的**记录仪**。
     *
     * ## 为什么非要有它
     *
     * 用户报的头号毛病是「字幕**一闪而过**」。而那是**一声不响**的那种坏 ——
     * 它不给他报错,也不给日志留痕:屏幕上一闪,`model.log` 里干干净净。
     * 我盯着看了 70 秒没复现,只能回他一句「我没复现」。**那不对。**
     *
     * 从这儿起,那行字**每一次亮/灭、有字/空**都留一行。他只要进一次她的房间,
     * 日志就能回答三个问题:它灭没灭、什么时候灭的、**被哪条路**灭的。
     *
     * ★★ 只在**状态真的变了**的时候写(判据是 [laneLastState])。
     *   理由不是省日志,是**流式一版一版地来** —— 一版写一条的话,
     *   真正要看的那一行会被它自己刷掉。要抓的是「灭」这个**事件**,不是每一版的字数。
     *
     * ★ 别把它做成「只在出错时打」—— 出事的时候没有人知道什么叫出错。全打。
     *   一天也就几十行。
     */
    private fun laneTrace(why: String) {
        val lit = herSay.visibility == View.VISIBLE
        val state = "${if (lit) "亮" else "灭"}·${if (herSay.text.isEmpty()) "空" else "有字"}"
        if (state == laneLastState) return
        laneLastState = state
        ModelManager.get(this).trace(
            "字幕: $why → $state(${herSay.text.length} 字 / 目标 ${herSayTarget.length} 字 / " +
                "说定=$laneFinal / 打字态=$typing)"
        )
    }

    /**
     * ★★ 2026-10-05 晚:把「这一句多长」翻成「这一行字多大、最多几行」,落到 [herSay] 上。
     *
     * ## 治的是哪一条(用户当场报的)
     *
     * > 「**最后是以省略号的形式出现**,那太不好了,所以,**要么把字做小一点,
     * >  不然不能全显示出来**」
     *
     * 病根是 `herSay` 上写死的 `maxLines = 3`,超过就 `…`。
     * 判据全在 [CaptionFit] 里(纯逻辑,有单测);这里只做两件事:**算**、**落**。
     *
     * ## ★ 参数是「这一句最终多长」,不是「已经显示到第几个字」
     *
     * 传 `herSayTarget.length`(不是 `herSayShown`)—— 打字机是一格一格往外吐的,
     * 按已吐的长度算的话,一句话在显示过程中会**一路缩水**,看着像有人在拧镜头。
     * 按最终长度算,一句话从第一个字起就是同一个字号,吐到最后一个字都不动。
     *
     * (它仍然会在**跨档**的时候跳一下 —— 40/80/130/200 那四道坎。
     *  一档 1sp,一次回答通常跨一到两档。**这是「装得下」要付的代价,认。**)
     *
     * ## ★ 它不改「谁大谁小」
     *
     * 基准字号由 [laneBaseSize] 给(她 16 / 他 14),这一层只在它之上收小。
     * 所以「一眼分得清谁在说」那层既有区分一个字都没动 —— 见 [CaptionFitTest] 里
     * 那条专门钉它的用例。
     */
    private fun fitHerSay(chars: Int) {
        herSay.textSize = CaptionFit.textSize(laneBaseSize, chars)
        herSay.maxLines = CaptionFit.maxLines(chars)
    }

    /** 她这轮不说了 -> 那行字撤掉。**只在她的话被作废时用**(空串),不是说完就清。 */
    private fun clearHerSay() {
        root.removeCallbacks(herSayTicker)
        herSayTarget = ""
        herSayShown = 0
        // 空行上没有「说定的话」可言 —— 锁定位跟着塌掉(见 [laneFinal])。
        laneFinal = false
        herSay.text = ""
        herSay.visibility = View.GONE
        laneTrace("撤掉")
        refreshSendBtn()
    }

    /**
     * 她那行字**停下打字机,但把已生成的吐完**。
     *
     * ★ 用「吐完」而不是「停在半句」,理由和 [interruptHer] 里那句一样:
     *   半句话比整句更让人以为出了故障 —— 而他点这一下的本意是**让她停**,
     *   不是让屏幕看起来坏了。
     */
    private fun settleHerSay() {
        root.removeCallbacks(herSayTicker)
        herSay.text = herSayTarget
        herSayShown = herSayTarget.length
        // ★ 它**不碰可见性** —— 所以它只会出现在「本来亮着、字数变了」的记录里。
        //   万一哪天它把一行说定的话**吐空了**,那一次也会留下一条(空→灭 或 空→亮)。
        laneTrace("打断后吐完")
    }

    // ★ 这里曾经有一个 `captionTicker`(胶囊里那台打字机)。
    //   2026-10-05 下午随「他的字也搬去上面那行」一起删了 —— 再没有调用方。
    //   那一段「追一个目标」的写法与理由,留在上面那段注释里,一个字没丢。

    /**
     * 把最新吐出来的那截顶进可视区 —— 「说完的字往左移」就是这一行。
     *
     * ★ 必须 `post`:刚 `setText` 完的那一刻**布局还没算**(`caption.layout` 还是旧的),
     *   拿旧宽度去算位移,就会慢一帧 —— 表现是字走到右边**贴边停一下**再跳。
     *
     * ★ `coerceAtLeast(0)` 不能省:短句的时候 `layout.width < view.width`,
     *   差是负的,直接 `scrollTo` 会把它**往左推出去**,一句话就飘在屏幕外了。
     *   (那种情况下位移本就该是 0 —— 居中的活由 `gravity = CENTER` 干。)
     */
    private fun scrollCaptionTail() {
        caption.post {
            val lw = caption.layout?.width ?: return@post
            val max = (lw + caption.paddingLeft + caption.paddingRight - caption.width)
                .coerceAtLeast(0)
            caption.scrollTo(max, 0)
        }
    }

    /**
     * 一句**真话**要上字幕 —— 但她得先问一句「占位话看够了没有」。
     *
     * ★★ 2026-10-05:这里从「直接写」拆成了「先判该不该排队,再写」。
     *   原因见 [CaptionDwell] 那段 —— 松手之后那句占位话在转写快的
     *   时候只活两三百毫秒,用户的原话是「太快了我看不清是什么」。
     *
     * ⚠️ 两条路都要保住:
     *   · 排队的([deferredCaption])是真话,只是**晚到**,不能变成丢失;
     *   · 不排队的照旧立刻上 —— 正常一轮(转写一两秒)一个字都感觉不到变化。
     */
    private fun showCaption(text: String, fromHer: Boolean) {
        val wait = CaptionDwell.waitMs(
            placeholderShowing = captionIsPlaceholder,
            sinceMs = placeholderSince,
            nowMs = System.currentTimeMillis(),
        )
        if (wait > 0) {
            // 重排不是追加:两句真话前后脚来的话,后一句要顶掉前一句排队的那份。
            root.removeCallbacks(flushDeferredCaption)
            deferredCaption = text to fromHer
            root.postDelayed(flushDeferredCaption, wait)
            return
        }
        applyCaption(text, fromHer)
    }

    /**
     * 真的把这句话写上字幕。**只有 [showCaption] 和 [flushDeferredCaption] 会调它。**
     *
     * ★★ 2026-10-05 下午:它从**胶囊里**搬到了**上面那行**([herSay])。
     *
     *   用户原话(带一张「位置错的图」):
     *   > 「当我说完后,不能直接(无延迟的)显示在透明磨砂玻璃的**上方**……
     *   >   我需要的是,**我的她的字幕,都显示在现在她那张图的字幕中**,
     *   >   只是我说话显示我的,她说话显示她的,我说话**自动清理她的**并且换成我的,
     *   >   她说话也是**自动清理我的**(占屏字幕)。」
     *
     *   ★ 「自动清理对方的」不需要一行代码 —— **两个人共用一个座位**,
     *     写上去的那一句天生就把上一句替换掉了。谁最后说,谁占着。
     *   ★ 之所以要两个人共用,而不是各占一行:胶囊**横屏时只有一行高**,
     *     他自己那句话摆进去,她的回答就没地方了(那张图里就是).
     *
     *   ★ 胶囊从此只剩三副样子:**占位话 / 输入框 / 水波** —— 它不再是字幕。
     *     于是那个「他打字时她的话一个字都看不见」的老病**结构上消失了**
     *     (见 [herSay] 的 KDoc:那正是她当初被搬到上面那行的理由)。
     */
    private fun applyCaption(text: String, fromHer: Boolean) {
        cancelDeferredCaption()      // 已经在写了,排队的那份不该再冒出来盖它
        lastCaptionAt = System.currentTimeMillis()
        // 你说的话用灰色小一号,她说的亮一点大一点 —— 一眼分得清谁在说,
        // 但都是字幕,不是气泡。
        herSay.setTextColor(if (fromHer) 0xFFFDF2F6.toInt() else 0xFFB9A6AE.toInt())
        // ★ 既有那层区分(她 16 / 他 14)在这儿定;长句的收小在 [fitHerSay] 里做。
        laneBaseSize = if (fromHer) 16f else 14f
        fitHerSay(text.length)
        typeHerSay(text, fromHer)
        // ★ 这一句是**说定的**(她自己说的,或者他已经发出去的那句)——
        //   锁上,别让随后那条草稿期的空 partial 把它撤掉(见 [laneFinal])。
        if (text.isNotEmpty()) laneFinal = true
    }

    /**
     * 出声的**门**。嗓子没好就先把话攒着,见 [pendingSpeech]。
     *
     * 诚实边界:如果这台机器根本没有可用的中文引擎,[initTts] 会把
     * [ttsBtn] 打灰 + 弹一次 toast 说明白,**不留一个「开关是开的、就是没声」的哑谜**。
     */
    private fun speak(s: String) {
        // ★ 嗓子关着的时候**光晕不能亮成「在说」** —— 亮了他就会以为她说了,
        //   只是没声。那是最难查的一类:界面在撒谎,而日志里一切正常。
        if (!ttsOn) {
            herSounding = false
            her("setTalking(false)"); setVoiceState(VOICE_OFF); return
        }
        if (!ttsReady) {
            if (pendingSpeech.size < 3) pendingSpeech.addLast(s)
            initTts()
            return
        }
        speakNow(s)
    }

    /**
     * 嗓子确实好了才会走到这儿。
     *
     * @param earlyChunk 「先出声」抢出来的那一块(见 [speakEarly])。
     *   ★★ 它只走**内嵌嗓子**这一条路,而且要**故意不接 `onDone`** ——
     *   理由见下面两处注释,一句话:**半双工闸一次都不许被碰**。
     * @return 这一块**真的交给一条嗓子了没有**。调用方([speakEarly])靠它决定
     *   要不要把那块记成「已经念过」。返回 false = 什么都没发生(视觉也收回去了)。
     */
    private fun speakNow(s: String, earlyChunk: Boolean = false): Boolean {
        // ★★ 「先出声」那一块**只走内嵌嗓子**,在这儿就把它挡掉。
        //
        //   系统 TTS 那条路要走 [ttsId] 立号那一套,而提前念的块**故意不接 onDone**
        //   (理由见下)—— 两边的号对不上,它的「念完了」会提前把麦克风放回去 →
        //   她把自己正在说的话听回去 → **自问自答**,而且日志上看不出破绽。
        //
        //   所以宁可这一块不提前:**什么都没发生**,定稿时照旧整段念。
        //   **检查放在所有视觉副作用之前** —— 不然界面会亮起「她在说」而没有声音,
        //   那是这个项目最恨的一类错(界面撒谎)。
        if (earlyChunk && !SherpaVoice.isReady()) return false

        // ★★ 先把**舞台指示**剥掉再念(判定和理由都在 [SpeechMath])。模型吐的是剧本,
        //   不剥的话你会真听见「叹气」两个字 —— 而她本人没有叹气。
        //
        //   ★ 剥的是**念出去的那一份**。字幕([herSay])上原样留着:那是给他看的,
        //     `（叹气）` 摆在那儿是表情,念出来才是事故。
        //   ★ [emotionOf] 也读**原文** —— 提示词里那几个动作词(笑 / 叹气)恰恰是情绪线索,
        //     剥掉了她就会一脸平静地说一句带着笑的话。
        //   ★ [speakingText] 必须是**剥过的这份**:`onRangeStart` 报的偏移是
        //     **相对送进 TTS 的那个字符串**的,拿原文去索引会错位(见 her.js 的 speakRange)。
        val said = SpeechMath.forSpeech(s)
        speakingText = said
        // ★ 「她开口了」也是三种忙之一(见 [herIsBusy]),所以那颗圆按钮
        //   此刻就该变成「停」。**必须在 setVoiceState 之前摆平** ——
        //   她主动开口([onSpontaneous])那条路不走 agent,`onBusy` 一声都不会响,
        //   漏了这里的话,她自言自语的时候那颗按钮会一直写着「↑」,
        //   而他正是听到她说话才想去按的。
        refreshSendBtn()
        // ★★「她开口了」这三件事**挪到声音真的出来那一刻** —— 见下面 [startTalking]。
        //
        //   原来它们就摆在这儿,而这里**离出声还差一整段合成**:整句合成要好几秒
        //   (实测 ≈1 倍实时),那几秒里她的嘴在动、表情在变、光晕写着「在说」,
        //   **却一个字节的声音都没有**。用户 2026-10-05 报的
        //   「她嘴巴动和声音是不同步的」有一半就是这个。
        //
        //   ★★ **系统 TTS 那条路早就是对的**:它的 `onStart` 回调里才
        //     `setTalking(true)`(见 [attachTtsListener])。内嵌嗓子这条只是没跟上 ——
        //     而它恰好是最慢的那条(整句合成),所以差得最明显。
        //     [SherpaVoice.speak] 现在也收 `onStart`,两副嗓子形状对齐了。
        //
        //   ★ 光晕仍然是**可见证据**,没变:嗓子关着不会走到这儿(speak 提前 return),
        //     内置嗓子没出声就不会亮 —— 「光晕亮 = 真的在念」这条比原来更硬了。
        val emo = emotionOf(s)
        val startTalking = {
            herSounding = true
            setVoiceState(VOICE_SPEAK)
            her("setTalking(true)")
            her("setEmotion('$emo', 0.75)")
            // ★ 2026-10-17:**声音出来那一刻,身体也跟上**。在这之前这条链是断的 ——
            //   语气算出来了,只送去给脸,身体一步没动(见 [EmotionMotionMath])。
            //   ★ 放在 setEmotion **后面**是刻意的:两者是同一件事的两半,
            //     「脸先到、身体随后」比反过来自然,而且她本人的动作永远比表情慢一点。
            //   ★ 它自己会按 [EmotionMotionMath.shouldFire] 决定这一次要不要真的换 ——
            //     她是**按句念**的,一句话会进来好几次,不掐就会抽搐。
            fireEmotionMotion(emo)
            // ★ 2026-11-07:**她真的出声了,从这一刻才开始听他有没有插话**(见 [BargeIn])。
            //
            //   放在这儿而不是 speak 之前:整句合成要好几秒,那几秒房间里是安静的 ——
            //   提前开麦只会把一段静音量成基准,阈值被压到地板,之后她一开口就把自己打断。
            //
            //   ★ 它自己会先看开关和回声消除支不支持;没开就什么都不做。
            //   ★ 默认关:开着它等于在「她说话时麦克风必须关」这条半双工规则上开一个口子,
            //     这个口子只在有回声消除的机器上才有意义(见 [BargeIn] 文件头)。
            BargeIn.start(this) { runOnUiThread { onBargeIn() } }
        }
        // ★★ 先问**内嵌那个真嗓子**(sherpa VITS)。
        //
        //   换它的唯一理由是**发音**:这台机器的系统 TTS 一个中文声学模型都没有
        //   (真机日志:`房间:TTS 音色 5 种,其中中文 0 种`),它是拿英文嗓子读汉字 ——
        //   所以用户点名的「调试」读成 diào 而不是 tiáo 不是口音问题,是**念错了**。
        //   内嵌那份的 lexicon 里「调试」是一条写死的 tiao2 词组条目(见 [SherpaVoice])。
        //
        //   ★ 它**没就绪就交回系统 TTS**(模型还在装 / 装失败 / 文件不在),
        //     见 [SherpaVoice.speak] 的返回值:没受理就没有回调,所以这里
        //     `return` 之前**绝不能**先挂上 onDone —— 那样会兑现两次,
        //     而这一条的后果是半双工闸提前放行 → 她自问自答。
        // ★★ 2026-10-08:先看她此刻选的是**哪副嗓子**(见 [KEY_ENGINE])。
        //
        //   选「系统」那副时**根本不问内嵌那份** —— 直接落到下面系统 TTS 那一段。
        //   这不是省一次调用,是**必须**的:[SherpaVoice.speak] 一进去就起合成线程、
        //   挂它自己的回调,先问一句再把它丢掉 = 让她**说两遍**。
        //
        //   ★ 但系统那副**没就绪**(引擎没起来 / 她换了引擎又卸了)时要**退回去**
        //     问内嵌那份 —— 宁可慢,不许哑。「没有声音」比「声音慢」坏得多,
        //     而这个项目所有的静默失败都是从「反正还有另一条路」开始的。
        val systemVoiceUsable = !usingEmbeddedVoice() && ttsReady && tts != null
        val took = if (systemVoiceUsable) false else SherpaVoice.speak(
            ctx = this,
            text = said,
            speed = voiceSpeed(),
            onLevel = { lv -> runOnUiThread { pushMouth(lv) } },
            // ★★ **声音出来那一刻**才张嘴、才亮光晕、才记「她在说」。
            //   这是上面 [startTalking] 唯一的另一个调用点(另一个在系统 TTS 那条路上)。
            //   它跑在 `her-voice` 那条合成线程上,所以要回主线程。
            //
            //   ★ 它**可能一次都不来**(合成失败 / 中途被叫停)—— 那是对的:
            //     没出过声就不该摆出「她在说」的样子。而放麦那条路**不靠它**,
            //     靠的是 [onDone] 的兜底,所以这里失约不会卡死。
            onStart = { runOnUiThread { startTalking() } },
            // ★★ 「先出声」那一块**接的是另一个回调** —— 这一条是整件事的安全支点。
            //
            //   `onDone` 在这个项目里只有一个意思:「**她说完了**,把麦克风放回去」
            //   (见 [onSpokenDone])。而这一块念完之后她**还在生成剩下的部分** ——
            //   用它放麦,她还在那儿想,你说话她接不住;更要命的是扬声器里的尾音
            //   会绕回自己的麦 → **自问自答**,不报错、日志干净。
            //
            //   所以**一轮里放麦的信号只有一个**:定稿那一句([Callbacks.onFinal])。
            //   `onSpokenDone` 那条路一个字节都没动 —— 提前念那一块走的是
            //   [onEarlyChunkDone],它只管「这一块交割完了」,再按 [earlyOwnsTurn]
            //   决定要不要顺便把这一轮结掉(那一块就是全部时,只能由它来结)。
            //
            //   ★ 反过来也安全:`stop()` 让它失约也无所谓(见 [SherpaVoice] 类头契约)——
            //     放麦那条兜底([armVoiceResume])照旧挂着,不会卡死。
            onDone = if (earlyChunk) {
                ({ runOnUiThread { onEarlyChunkDone() } })
            } else {
                ({ runOnUiThread { onSpokenDone() } })
            },
            queue = true,          // 房间里她可以连着说几句,排队(QUEUE_ADD 的老行为)
        )
        if (took) {
            // ★ 这一声交给内嵌嗓子了 —— 系统这条的号**当场作废**。
            //   不作废的话:它上一句的 `onDone` 还会回来,而那时她正在用内嵌嗓子说
            //   新的一句 → 半双工闸提前放行 → 自问自答。**换嗓子不该换丢这条规矩。**
            ttsId = null
            return true
        }

        if (earlyChunk) {
            // 内嵌嗓子在最后这一瞬拒了(`isReady` 刚刚还是 true)—— 上面那句
            // `isReady` 检查和这里之间只有几微秒,但**该收的还是要收**。
            // ★ 2026-10-08:走进这一格现在有**两条**路 —— 上面这条,以及
            //   「他选的是系统那副」([systemVoiceUsable],那种情况下提前念整个不做)。
            //   两条的收尾是同一件事,所以不用分开写。
            //
            // ★ 2026-10-05:「亮光晕、张嘴」那三件事已经挪去 [startTalking]
            //   (声音出来才做),所以走这条路时它们**通常根本还没摆过** ——
            //   下面那两行只是把已经是的状态再说一遍。
            //   **留着当兜底,不删**:万一以后有人把 [startTalking] 挪回合成之前,
            //   这两行就是唯一的收尾,而漏了它的后果是「光晕亮着,却永远不出声」。
            speakingText = null
            herSounding = false
            her("setTalking(false)")
            setVoiceState(VOICE_OFF)
            refreshSendBtn()
            return false
        }

        // ★★★ 系统 TTS 这条路**没有**「声音出来了」这个信号(`tts?.speak` 之前
        //   拿不到任何回调),所以只能照旧在这儿把 [startTalking] 叫上。
        //   代价说清:它张嘴会比出声早**几十毫秒**(引擎起播的时间),不是几秒 ——
        //   和上面内嵌那条的差别在这儿,别再想办法去「对齐」它。
        startTalking()
        val id = "conmarn-${System.nanoTime()}"
        ttsId = id                       // ★ 先立号,再开口 —— 回调只认这个
        tts?.speak(said, TextToSpeech.QUEUE_ADD, null, id)
        return true
    }

    /**
     * ★★ **先出声** —— 趁整段还在生成,把已经吐出来的第一句先念出去。
     *
     * 治的是「响应太长」里**不属于模型的那一半**:本地模型本身不慢
     * (裸打 8080,4 个 token 只要 1.2~3.8 秒),慢的是**她要等整段生成完才开口** ——
     * 按 7 token/秒算,一段 200 token 的回答 = 近 30 秒的绝对安静。
     * 判定(在哪切)全在 [SpeechChunkMath],这一层只负责「念」。
     *
     * ## 三道门,任何一道没过就**照旧等定稿**(老行为一个字不变)
     *
     * 1. **嗓子关着 / 还没就绪** —— 走 [speak] 那三态,一次都别抢。
     *    (没有 `!ttsReady` 这一道的话,`speakNow` 会把话塞进 [pendingSpeech] ——
     *     而定稿时又念一遍 → **同一句说两次**。)
     * 2. **剥完舞台指示一个字都不剩** —— 比如抢出来的正好是 `(叹气)` 那种纯动作。
     *    这时**绝不能**把它记成「已经念过」,否则定稿时那半句就被吞了。
     * 3. **内嵌嗓子没就绪 / 他选的是系统那副** —— 见 [speakNow] 里那条:
     *    系统 TTS 那条路不做提前念(它的号对不上,见 [speakNow] 开头那段)。
     *
     * @return 真的念出去了没有。**false = 什么都没发生** —— 调用方
     *   ([Callbacks.onPartial])据此决定要不要立 [earlySaid]。
     */
    private fun speakEarly(chunk: String): Boolean {
        if (!ttsOn || !ttsReady) return false
        if (SpeechMath.forSpeech(chunk).isBlank()) return false
        if (!speakNow(chunk, earlyChunk = true)) return false
        // ★ 记的是**原文**,不是剥过的那份 —— 定稿要对账的是 [AiAgent] 给的原句。
        // ★★ 是 **`+=` 不是 `=`**(2026-10-05 下午):现在一轮可以抢**好几块**,
        //   而 [SpeechChunkMath.spokenPrefix] 要的是「已经念过的那一整段前缀」——
        //   每一块都是接着上一块往下念的,拼起来正好是它要的那个东西。
        earlySaid += chunk
        // ★★ 抢一块就**把「全念完了」作废,并把欠账加一**。
        //   漏了 `earlyDone = false` 这一步会出一个**静默的**恶果:
        //   前面那块响完时它已经变成 true,而新抢的这块**还在响** ——
        //   [Callbacks.onFinal] 就会拿这个过期的 true 当「都说完了」,当场开麦。
        earlyDone = false
        earlyPending++
        ModelManager.get(this).trace(
            "先出声:抢了一块 ${chunk.length} 个字先念(累计 ${earlySaid.length} 字,还有 $earlyPending 块没念完)"
        )
        return true
    }

    /**
     * 「先出声」抢出去的**一块**念完了。
     *
     * ★ 它**不是** [onSpokenDone] —— 一块念完的时候她多半还在生成剩下的部分,
     *   那时候放麦 = 把扬声器里的声音听回去 → 自问自答。
     *   只有当定稿判定「抢出去的就是全部」([earlyOwnsTurn])、**而且欠的块都还清了**,
     *   它才顺手把这一轮结掉。
     *
     * ★★ `earlyDone` **只在欠账归零那一刻**才为真 —— 见 [earlyPending] 那段。
     *   写成「来一个 onDone 就置 true」的话,第一块响完就等于宣布「都说完了」,
     *   而后面排队的块还在响 —— 那时候开麦,她会把自己正在说的话听回去。
     *
     * ★ 和别处的回调一样,**每条路只在主线程跑一遍**:两处调用都包了 runOnUiThread。
     */
    private fun onEarlyChunkDone() {
        if (earlyPending > 0) earlyPending--
        if (earlyPending > 0) return          // 还有块在响 —— 现在什么都不算数
        earlyDone = true
        if (earlyOwnsTurn) onSpokenDone()
    }

    /**
     * 把「先出声」那一轮的账清干净。**话轮的头和尾都要调。**
     *
     * ★ 漏了尾部的话,下一轮会拿着上一轮那句话去跟定稿对账 ——
     *   而对不上账的后果是**整段重念一遍**(安全的,但白念)。
     * ★ 漏了 [earlyPending] 的话更难看:欠账记在上一轮头上,这一轮的「说完了没」
     *   就永远等不到,她**说完这句话不放麦** —— 症状是「她说完这句就不理我了」。
     */
    private fun forgetEarlySaid() {
        earlySaid = ""
        earlyDone = false
        earlyPending = 0
        earlyOwnsTurn = false
    }

    /**
     * 用户调的那个语速,给内嵌嗓子用(sherpa 的 `speed` 就是它)。
     *
     * ★ 和 [applyTtsVoice] 读的是**同一批 key**,不是抄一份默认值 ——
     *   否则「换嗓子」会变成「她换衣服的时候只有半个人换了」。
     */
    private fun voiceSpeed(): Float = try {
        getSharedPreferences(PREFS_VOICE, MODE_PRIVATE)
            .getFloat(KEY_RATE, DEF_RATE)
            .coerceIn(0.5f, 2.0f)
    } catch (_: Exception) { DEF_RATE }

    /**
     * 她说一句话用**哪副嗓子** —— 见 [KEY_ENGINE] 那段(为什么默认是系统那副)。
     *
     * ★ 读不出来(盘坏了 / 老版本没这个 key)就当没选过,退回默认值 ——
     *   和 [voiceSpeed] 同一个写法,不许抛。
     */
    private fun enginePref(): String = try {
        getSharedPreferences(PREFS_VOICE, MODE_PRIVATE)
            .getString(KEY_ENGINE, DEF_ENGINE) ?: DEF_ENGINE
    } catch (_: Exception) { DEF_ENGINE }

    /** 此刻她用的是内嵌那副嗓子吗。见 [KEY_ENGINE]。 */
    private fun usingEmbeddedVoice(): Boolean = enginePref() != ENGINE_SYSTEM

    /**
     * 「她嗓子里还有话」—— **两副嗓子都算**。
     *
     * ★★ 2026-11-08(体检):原来两处半双工判据只问了 [SherpaVoice]。系统 TTS 那副
     *   嗓子在念的时候 `SherpaVoice.isSpeaking()` 恒为 false、`pendingCount()` 恒为 0,
     *   于是判据回答「她说完了」→ 麦克风被放回去 → 她念的每个字都绕回自己的麦 →
     *   **自问自答,不报错,日志干净**。这和 [EarSessionMath.herVoiceFinished] 文件头
     *   里那段是同一个病,只是换了一副嗓子。
     *
     * ★ 系统那副没有「还欠几句」的读数,用 [speakingText] 代替:它在 [speakNow] 交给
     *   **任何一副**嗓子之前立起、在 [onSpokenDone] 和两个 `onError` 里落下 —— 和
     *   「这一句交割完了」是同一件事。它比 `tts?.isSpeaking` 靠得住:`QUEUE_ADD`
     *   排进去、还没开播的那一小段里 `isSpeaking` 是 false,而话明明欠着。
     */
    private fun herVoiceBusy(): Boolean =
        SherpaVoice.isSpeaking() || SherpaVoice.pendingCount() > 0 ||
            speakingText != null || tts?.isSpeaking == true

    /**
     * 换嗓子:内嵌 ⇄ 系统。见 [KEY_ENGINE]。
     *
     * ★ 它**当场说一句**给你听 —— 这个钮存在的全部意义就是「哪个更好听 / 哪个不卡」,
     *   而这两件事只有耳朵能判。切完不出声的话,得等下一句话才知道,那就白切了。
     * ★ 用 [AUDITION_LINE] 那种短句:他要来回切好几次,长句子每次都要等。
     *
     * ★★ 2026-10-08:**它现在一个调用方都没有** —— 设置里那一行收起来了
     *    (他判完了,要系统那副;理由和挂回来的四处在 `showSettings()` 里那段注释)。
     *    留着**不是因为被忘了**,而是:① 动它的风险全在「切完她当场说话」这条路上,
     *    而那正是当初花力气调对的;真要挂回来,重写一遍比取消注释贵得多;
     *    ② 它是 [KEY_ENGINE] **唯一**的写入方 —— 删了它,盘上那个值就再没人能改,
     *    那份设置会变成一条谁也说不清来路的死数据。
     *    ★ 代价说明白:它不动,就永远不会跑,所以它也永远不会坏 ——
     *      **哪天要挂回来,先按上面那段注释把四处一起改回来,再验一次「切完当场出声」。**
     */
    private fun toggleVoiceEngine() {
        val to = if (usingEmbeddedVoice()) ENGINE_SYSTEM else ENGINE_EMBEDDED
        try {
            getSharedPreferences(PREFS_VOICE, MODE_PRIVATE)
                .edit().putString(KEY_ENGINE, to).apply()
        } catch (_: Exception) {
            // ★ 盘写不进去也照样往下走 —— 这一次的切换在**内存里**是生效不了的
            //   (enginePref 是从盘上读的),所以要说出来,别让他以为切了。
            //   不抛:这一下是他点在 ⚙ 上,不该弹异常。
            toast("换嗓子没写进盘里,这一下不算数")
        }
        ModelManager.get(this).trace(
            "房间:她的嗓子换成" + if (to == ENGINE_SYSTEM) "系统那副(边合成边出声)" else "内嵌那副(VITS)"
        )
        // ★ 先在念的那一句必须掐掉 —— 不然旧嗓子念完下一句才轮到新嗓子,
        //   听感上就是「切了没反应」。
        tts?.stop(); SherpaVoice.stop()
        // ★ 他按这个钮就是来听声音的 —— 嗓子关着的话,切完一片安静,等于这个钮坏了。
        if (!ttsOn) toggleTts()
        auditionVoice()
    }

    /**
     * 拿 [AUDITION_LINE] 当场念一句 —— 只在 [toggleVoiceEngine] 里用。
     *
     * ★ 系统那副还没起来时**退回内嵌**,并**明说**。不静默:他刚按下「换成系统那副」,
     *   听到的还是内嵌的声音、而界面已经显示切过去了 —— 那就是这个项目最恨的那类谎。
     */
    private fun auditionVoice() {
        if (usingEmbeddedVoice() || !ttsReady || tts == null) {
            if (!usingEmbeddedVoice()) toast("系统那个引擎还没起来,这一句先用内嵌那副念")
            SherpaVoice.speak(this, AUDITION_LINE, speed = voiceSpeed(), onDone = {}, queue = false)
            return
        }
        speakNow(AUDITION_LINE)
    }

    /**
     * 把**这块正要播出去的音频的响度**推给 her.js —— 她的嘴张多大就是它说了算。
     *
     * ★ 这条路比原来那条准,不是花架子:原来靠系统 TTS 的 `onRangeStart` 报
     *   「念到第几个字」,再拿那个字去猜元音。而这台机器的系统 TTS 连中文音色都没有,
     *   `onRangeStart` 大概率根本不报 —— her.js 那边会**静默退化成按时间转的循环**
     *   (见它 `stale` 那段),也就是一张和声音没关系的嘴。
     *   现在的响度是**从同一块音频上算出来的**,天生同步。
     *
     * ★ 必须**限流**。合成是几十毫秒吐一块,每块都 `evaluateJavascript` 一次的话,
     *   一秒钟往 WebView 里灌几十条脚本 —— 那是拿她的嘴去换掉整个场景的帧率。
     *   60ms 一条对眼睛已经是连续的(16 帧),而变化小于 0.08 的那些**看不出来**,
     *   丢掉它们纯赚。
     */
    private fun pushMouth(level: Float) {
        val now = System.currentTimeMillis()
        if (now - mouthPushAt < 60L) return
        if (kotlin.math.abs(level - mouthPushed) < 0.08f) return
        mouthPushAt = now
        mouthPushed = level
        her("setMouth($level)")
    }

    /**
     * 从她自己说的话里猜个情绪,让她脸上有反应。
     *
     * 这不是「理解」,是最粗暴的关键词映射 —— 但**比一张永远不变的脸强太多**。
     * 真正的情绪应该来自她自己的判断(让模型多吐一个字段),那是下一步;
     * 现在先让这条通路存在、跑通,后面对上真值只要换这一个函数。
     */
    private fun emotionOf(text: String): String = when {
        text.contains("哈哈") || text.contains("嘿") || text.contains("开心") ||
            text.contains("太好了") || text.contains("棒") || text.contains("笑") -> "happy"
        text.contains("对不起") || text.contains("抱歉") || text.contains("难过") ||
            text.contains("可惜") || text.contains("哎") -> "sad"
        text.contains("!!") || text.contains("！") && text.contains("啊") ||
            text.contains("竟然") || text.contains("居然") -> "surprised"
        text.contains("烦") || text.contains("生气") || text.contains("别闹") -> "angry"
        else -> "relaxed"
    }

    // ==================================================================
    // TTS
    // ==================================================================

    private fun toggleTts() {
        ttsOn = !ttsOn
        // ★ 2026-11-06：出声开关要给别的界面看得到（工作台跑完会念成品）。
        //   唯一能拨的还是这里，那边只读。
        HerVoice.enabled = ttsOn
        if (ttsOn && !ttsReady) initTts()
        // ★ 顺手把「她在说」那两格也放平 —— 关嗓子时她可能正说到一半,
        //   漏了 [herSounding] 的话它会永远卡在 true,而症状是「之后每次点一下
        //   都被当成打断」,和「她再也不理我了」是同一种难查。
        if (!ttsOn) {
            tts?.stop(); SherpaVoice.stop()
            speakingText = null
            herSounding = false
            ttsId = null
            her("setTalking(false)")
        }
    }

    private fun initTts() {
        if (tts != null || ttsReady) return
        tts = TextToSpeech(this) { status ->
            // ★ 语言不是「要 zh-CN,不然拉倒」,是一条**回落链**。
            //   这台机器上 `tts_default_synth` 是 null(用户没在设置里选过引擎),
            //   全靠 AOSP 的 getDefaultEngine() 回落到系统分区里排名最高的那个 ——
            //   真机实测**有且只有一个**引擎 `com.oplus.ttsaccessibilityengine`。
            //   它认不认 zh-CN 不能靠猜,而且有点口音也**比没声音强**,所以逐个试。
            var lang = -99
            if (status == TextToSpeech.SUCCESS) {
                for (loc in TTS_LANGS) {
                    lang = tts?.setLanguage(loc) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    if (lang >= 0) break
                }
            }
            ttsReady = status == TextToSpeech.SUCCESS && lang >= 0
            if (ttsReady) {
                attachTtsListener()
                applyTtsVoice()
                // ★ 把嗓子借给「手机自己」那只手(见 SelfHand.voice)。
                // 她的房间关着的时候这个回调是 null —— 那时正确的回答是
                // 「她现在不在房间里,念不了话」,而不是一句干巴巴的「失败」。
                SelfHand.voice = { text -> runOnUiThread { speak(text) } }
                // 她嗓子热起来之前说过的话,现在补上 —— 见过 pendingSpeech 的注释。
                val queued = pendingSpeech.toList()
                pendingSpeech.clear()
                ModelManager.get(this).trace("房间:TTS 就绪(status=$status 语言=$lang" +
                    if (queued.isEmpty()) ")" else ",补念 ${queued.size} 句)")
                queued.forEach { speakNow(it) }
            } else {
                pendingSpeech.clear()
                runOnUiThread {
                    ModelManager.get(this).trace("房间:TTS 起不来(status=$status 语言=$lang)—— 她不发声")
                    // 只当面说一次。每次进房间都弹同一句,那和没说一样,还吵。
                    if (!ttsFailTold) {
                        ttsFailTold = true
                        toast("系统里没有可用的中文语音,我现在念不出来。\n" +
                            "去「设置 → 其他设置 → 无障碍 → 文字转语音」里看一眼引擎。")
                    }
                    // ★ 失败必须把 tts 放掉:否则上面那句 `if (tts != null …) return`
                    //   会把「试过一次」变成「永远不再试」。引擎晚一步起来(或用户
                    //   刚在设置里装好语音包),她这一整个房间实例就永久哑了 ——
                    //   而界面看不出任何异常。放掉之后,下次 onResume 会再试一次。
                    tts?.shutdown()
                    tts = null
                }
            }
        }
    }

    /**
     * 她的音色 / 语速 / 音高。
     *
     * ★ 2026-10-04 他听过第一版之后的第一句话:「**声音不好听,我要御姐声**」。
     *
     * 两道都上:
     *  1. **有别的音色就换过去** —— 引擎给多少种只有 `getVoices()` 说了算,
     *     所以先把清单打进 model.log(见 [dumpTtsVoices]),**按真名单挑,不猜名字**。
     *  2. **没有第二副嗓子的引擎,pitch/rate 就是唯一的手**。
     *     御姐的听感其实就是这两条:**低一点、慢一点**。
     *     同一个引擎念同一句话,`pitch 1.0 / rate 1.0` 是播报员,
     *     `pitch 0.9 / rate 0.92` 才是坐在你对面说话的人。
     *
     * ★ 存进 prefs 而不是写死:下一步要是给他一个「她的声音」选择页,
     *   动的就是这几个 key,执行器一行不用改(和 `use_hand` 那套同一个道理)。
     */
    private fun applyTtsVoice() {
        val p = getSharedPreferences(PREFS_VOICE, MODE_PRIVATE)
        val want = p.getString(KEY_VOICE, null)
        if (want != null) {
            val v = try { tts?.voices?.firstOrNull { it.name == want } } catch (_: Exception) { null }
            if (v != null) tts?.voice = v
            else ModelManager.get(this).trace("房间:音色「$want」这台机器上没有,先用默认的")
        }
        val pitch = p.getFloat(KEY_PITCH, DEF_PITCH)
        val rate = p.getFloat(KEY_RATE, DEF_RATE)
        tts?.setPitch(pitch)
        tts?.setSpeechRate(rate)
        // ★ 此刻她用的是哪副嗓子 —— 见 [KEY_ENGINE]。
        //   ★ 为什么要单独记一行:默认值是**新代码才写出来的**那个,而这一行是
        //     「新代码真的跑到了」的判据(改文件不影响已经在跑的进程,这个项目吃过亏)。
        //     光看小字看不出来,只有日志说了算。
        ModelManager.get(this).trace(
            "房间:她的嗓子是" + if (usingEmbeddedVoice()) "内嵌那副(VITS)" else "系统那副(边合成边出声)"
        )
        dumpTtsVoices(pitch, rate)
    }

    /**
     * 把引擎提供的音色**原样**打进 model.log。
     *
     * 为什么要费这一趟:这台机器上那个引擎叫 `com.oplus.ttsaccessibilityengine`
     * ——**无障碍朗读引擎**,不是给对话用的。它很可能只有一副嗓子(那就只能靠 pitch/rate),
     * 也可能藏着好几副(那就直接换)。**这种事猜不出来,列出来就有答案。**
     */
    private fun dumpTtsVoices(pitch: Float, rate: Float) {
        try {
            val all = tts?.voices?.toList() ?: emptyList()
            val zh = all.filter { it.locale.language == "zh" }
            // ★ 念出来的 pitch/rate 就是**我们自己设的**那几个数。
            //   不去回读 `getPitch()` / `getSpeechRate()` —— 它们在 API 37 的公开
            //   stub 里**根本不存在**(setter 有、getter 没有,编译直接过不去),
            //   而且「我们设了什么」本来就比「引擎报什么」更该信。
            ModelManager.get(this).trace("房间:TTS 音色 ${all.size} 种,其中中文 ${zh.size} 种" +
                " (pitch=$pitch rate=$rate)")
            // ★★ 这会儿**真正在用的**是哪一副 —— 清单再全,不写这一行也不知道她张嘴是谁。
            //
            //   2026-10-08 加:这一条是上面那个 `zh` 过滤捅出来的同一个洞的下一半 ——
            //   知道了「有哪几副」还不够,得知道「现在是哪副」。`want` 没设过的时候
            //   用的一直是**引擎自己挑的那个默认**(挑的是谁,只有这一行说得出来)。
            val cur = try { tts?.voice } catch (_: Exception) { null }
            ModelManager.get(this).trace(
                "房间:这会儿用的是「${cur?.name ?: "引擎自己的默认"}」区域=${cur?.locale ?: "?"}")
            // ★★ 2026-10-08:**列全部,不只列中文那几条。**
            //
            //   原来这里写的是 `zh.take(24)` —— 于是日志里只有「中文 0 种」这一个数,
            //   而**另外那几条到底叫什么、是哪个区域的,一条都没记**。
            //   这个项目正拿着这个数说一件事(「这台机器一个中文声学模型都没有」),
            //   而下这个结论所依据的那半张清单**从来没被打印过** —— 判据自己没留证据。
            //
            //   ★ 现在列全部(还是 24 条上限,它本来就是防刷屏的):中文那几条会不会出现,
            //     取决于引擎把它们的 locale 报成什么(`zh` / `cmn` / `und` / `ROOT` 都可能),
            //     而**这件事只有列出来才有答案**。零行为改动 —— 只是多几行日志。
            all.take(24).forEach {
                ModelManager.get(this).trace(
                    "房间:  音色「${it.name}」区域=${it.locale} 质量=${it.quality}" +
                        " 特性=${it.features}")
            }
        } catch (e: Exception) {
            // 列不出来不是致命错,但**必须留痕** —— 静默失败的诊断信息等于没有
            ModelManager.get(this).trace("房间:列音色失败(${e.javaClass.simpleName}: ${e.message})")
        }
    }

    /**
     * 口型同步的**真身**就在这儿。
     *
     * [UtteranceProgressListener.onRangeStart] 是 Android 唯一一处会告诉你
     * 「现在正念到这句话的第几个字」的 API(API 26+,本地 TTS 引擎普遍支持)。
     * 拿到区间就丢给 her.js,她按那个字选元音槽位 —— 不是随机张嘴,是真的
     * 和声音对得上。TTS 如果不支持这个回调,her.js 会自动退化成时间驱动的
     * 口型循环,至少不是一张死嘴。
     */
    private fun attachTtsListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            // ★★ 这三条**只读**的回调进门先过 [EchoMath] 那道闸:回调带的号得是
            //   我们**正在等的那一条**(理由见 [ttsId])。这条嗓子是 `QUEUE_ADD` 的,
            //   而迟到的 `onDone` 会把麦克风提前放开 → 她自问自答。
            //   `onRangeStart` 认错了也有事:拿上一句的偏移去索引这一句的字,
            //   她的嘴会在一个莫名其妙的字上张一下 —— 也不报错。
            override fun onStart(utteranceId: String?) {
                if (!EchoMath.isOurs(utteranceId, ttsId)) return
                runOnUiThread { her("setTalking(true)") }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                if (!EchoMath.isOurs(utteranceId, ttsId)) return
                val text = speakingText ?: return
                // 用 JSONObject.quote 转义,别让台词里的引号/换行把 JS 打断
                runOnUiThread {
                    her("speakRange($start, $end, ${JSONObject.quote(text)})")
                }
            }

            override fun onDone(utteranceId: String?) {
                if (!EchoMath.isOurs(utteranceId, ttsId)) return
                runOnUiThread { onSpokenDone() }
            }

            // 两个 onError 都得实现:基类里那个单参数的是抽象方法,
            // 不实现直接编译不过。出错时也要记得让她的嘴停下,不然会一直张着。
            //
            // ★★ **这两个故意不过上面那道闸**,和那三条不一样 —— 不是漏了。
            //   两个方向的代价不对称:
            //     · 认错一条:顶多让嘴早闭一下、按钮早点收回来。它**不调 [onSpokenDone]**,
            //       所以**开不了麦**,自问自答不了;
            //     · 漏认一条:老引擎的 `onError` 可能不带号,而那样 [speakingText] 就
            //       **永远清不掉** → 按钮卡在「停」上 → 正是「她再也不理我了」,
            //       而且**没有兜底**(按字数估的那个闹钟只放麦,不清它)。
            //   一边是早闭嘴,一边是永远卡住 —— 所以这里宁可信其有。
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    speakingText = null; herSounding = false; her("setTalking(false)")
                    // ★ 出错也要把按钮收回来 —— 否则她会永远停在「停」上,
                    //   而那颗按钮在忙态是**按得动**的,所以他按下去只会一遍遍
                    //   调 interruptAll,永远发不出下一句。这是最难查的一种:
                    //   界面看着正常,只是「她再也不理我了」。
                    refreshSendBtn()
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                runOnUiThread {
                    speakingText = null; herSounding = false; her("setTalking(false)")
                    refreshSendBtn()
                }
            }
        })
    }

    /**
     * 她**这一句念完了** —— ★★ 半双工闸的落点。
     *
     * 两副嗓子(callback 那条 / 系统 TTS 那条)都汇到这儿,**只有一份**。
     * 分成两份的代价这个项目已经付过一次:两处判据迟早会漂,
     * 而漂了之后一边放行一边不放行,症状是「她有时候自问自答,有时候不理我」——
     * 两种都难查,凑在一起基本查不动。
     *
     * **必须在主线程上跑。**
     */
    private fun onSpokenDone() {
        if (isFinishing) return
        // ★ 她说完了 —— 插话监听跟着收(见 [BargeIn])。它是一段**独占麦克风**的录音,
        //   不收的话紧接着被 armVoiceResume 开起来的耳朵会拿不到麦
        //   (Android 上同一时刻只该有一个 AudioRecord 在跑)。
        BargeIn.stop()
        mouthPushed = -1f          // 下一句从「闭着嘴」重新开始
        speakingText = null
        // ★ 她闭嘴了 —— 「嘴真的张开过」这一格跟着收(见 [herSounding])。
        herSounding = false
        ttsId = null               // ★ 这一句交割完了 —— 它后面的回声一律不算数
        her("setTalking(false)")
        setVoiceState(VOICE_OFF)
        // ★★ 半双工闸的落点就在这儿 —— **她说完了,才把麦放回去**。
        //
        //   反过来的话(开麦跟她说话同时进行),扬声器出来的声音会绕回
        //   自己的麦克风,变成一条「用户输入」→ 她自问自答。
        //   这条最毒的地方是**它不报任何错**:她拿到的是一句语法通顺、
        //   上下文合理的用户话,日志上看不出任何破绽。
        //
        //   ★ 冷却不是可选的礼貌:`onDone` 回来那一刻尾音还在房间里,
        //     这台机器是外放,立刻开麦会把最后半个字听成一句新输入。
        //
        //   ★ 45 秒那笔账**从这一刻重新数**:话轮交到他手上了。
        //     不重置的话,一场长对话会在开场 45 秒后突然收工 ——
        //     而他明明一直在说话。重排它的是 [earCallbacks] 的 onHeard 和这里,
        //     **故意不放进 [armVoiceResume]** —— 放进去就会每轮清零,
        //     那 45 秒永远到不了,麦再也关不掉了。
        silenceSince = System.currentTimeMillis()
        // ★ 她闭嘴了 —— 那颗圆按钮该从「停」收回「发送」(见 [herIsBusy])。
        //   漏了这一下,按钮会停在一个已经没用的「停」上。
        refreshSendBtn()
        if (voiceSession) armVoiceResume(EarSessionMath.COOLDOWN_MS)
    }

    // ==================================================================
    // 打字那一态
    //
    // ★★ 2026-10-04 晚,这里删掉了一整条路:走**系统**识别服务
    //   (`SpeechRecognizer` + `RecognizerIntent` + `VoiceListener`)的那一套。
    //   删它的理由不是它坏了,是它**没有门了** ——
    //   那个 🎤 按钮是它唯一的入口,而用户点了名:
    //     > 「右边那个麦克风和说字**删掉**」
    //   一台永远没有 `RecognitionService` 的手机 + 一个被删掉的按钮 = 一堆
    //   谁也走不到的代码。**死代码最贵的不是它占地方,是下一个人会认真读它。**
    //   (查证留档:`adb shell pm query-services -a android.speech.RecognitionService`
    //    → `No services found`。小布、百度输入法定制版都装了,但都不暴露这个接口。)
    //
    // ★ 耳朵现在是内嵌的 sherpa-onnx(见 [Ear]),入口是**整块玻璃胶囊**。
    // ==================================================================

    /**
     * 进 / 出**打字那一态**。胶囊左边那格是「字幕」和「输入框」轮流坐的,
     * 这个函数是**唯一**的换人开关。
     *
     * ★★ 为什么必须只有一个开关:两格都是 `MATCH_PARENT` 宽、叠在同一个
     *   `FrameLayout` 里。谁要是自己去 `caption.visibility = VISIBLE`(而输入框
     *   还露着),两段字**当场叠在一起** —— 看起来像花屏,不像 bug。
     *   所以:字幕那边所有要显示自己的地方,都得先看 [typing] 这个标志。
     *
     * ★ 打字不是「兜底」,是**另一条正经的路**:
     *   地铁上、图书馆里、她旁边有人 —— 那些时候你不能说话,但你还想跟她说话。
     */
    private fun setTypingMode(on: Boolean) {
        val was = typing
        typing = on
        // 键盘和水波不可能同时存在(打字是「我不说,我写」,水波是「我在说」)。
        // 不在这儿收的话,走「点一下 → 打字」那条路时,上一手势留下的波形
        // 会硬邦邦地停在输入框底下 —— 停住不动的波形比没有波形难看得多。
        hideWave()
        input.visibility = if (on) View.VISIBLE else View.GONE
        caption.visibility = if (on) View.GONE else View.VISIBLE
        // ★ 打字态才露出「回语音」那颗。理由见 [voiceBtn] —— 一句话:
        //   语音态整颗胶囊就是手势靶子,那时它反而是块碍事的补丁。
        voiceBtn?.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            input.requestFocus()
            // 键盘不会在同一个消息里就弹出来(布局还没走完),让一帧再叫
            root.postDelayed({
                (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }, 120)
        } else {
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()
        }
        // ★★ 用户报的第 4 条:「**只有点到透明磨砂玻璃后以键盘输入的形式,
        //   她才会正常显示字幕**」——
        //   这一行就是那句话的**直接对照物**:日志里「切到打字态」和
        //   「字幕: … → 亮」谁先谁后、隔了多少,一眼看得出。
        //   所以这一条**必须**写,而且只在真的换了态的时候写(去重)。
        if (was != on) {
            ModelManager.get(this)
                .trace("字幕: 切到${if (on) "打字" else "说话"}态")
        }
    }

    /**
     * 「把字幕显示出来」。**所有**要露字幕的地方都走它,别直接写
     * `caption.visibility = VISIBLE` —— 那正是会把输入框盖掉的那一行,
     * 而症状是两段字叠在一起(见 [setTypingMode])。
     */
    private fun revealCaption() {
        if (typing) return
        // ★ 「有字要露」= 水波让位。这一行让「按住 → 他开始说 → 实时字幕顶上来」
        //   自然发生(见 [liveCaption]),不用谁专门去叫停那条波。
        hideWave()
        caption.visibility = View.VISIBLE
    }

    /**
     * 按住说话的那几秒:那一格显示**他自己的字**(实时转写,见 [liveCaption])。
     *
     * ★★ 2026-10-05:它原来是「流动的水波」([VoiceWaveView]),用户把它换掉了。
     *   原话:
     *   > 「**实在不行不要基因序列和 ~~~ ,直接换成透明玻璃加字,不然混一起不好看**」
     *
     *   换掉之前那一格是**三段**走的(这个顺序原来写在 [liveCaption] 的注释里):
     *   细线 `~~~` → 基因序列 → 他自己的字。而他是这么报的:
     *   > 「**透明玻璃按住后一下子基因序列就没了,就直接 ~~~ 和基因序列都没了**」
     *   我们自己的设备日志把时间线量得很清楚:按下 → 1.3 秒后第一版转写落地,
     *   而**转写一落地,水波就按设计让位了**—— 于是中间那一段是
     *   「水波没了、字还只有两三个残字」,**看着就是整格空了**。
     *
     *   → 现在只剩一副样子:**空玻璃 → 他的字一个字一个字长出来**。
     *     没有第二副样子,也就没有「混一起」。
     *
     * ★ [VoiceWaveView] 本身**留着没删** —— 它是「麦克风开着」这件事唯一看得见的
     *   证据,哪天想要回去,把下面那句 `wave.start()` 放开就是(它连同
     *   「为什么当初是水波」都写在 [VoiceWaveView] 的文件头里)。
     *   ⚠️ 但**别顺手把 `wave.freeze()` 那条路也放开**:`freeze` 会把
     *   `visibility` 置成 VISIBLE,而这条水波现在**从来没 start 过** ——
     *   松手时它会让一片没在流的东西停在那一格上。
     *
     * ★ 字幕**留着 VISIBLE 但清空**:空的一行仍然占一行高,那一格的高度就不会跳。
     *   把它置成 GONE 的话,胶囊会在按住的瞬间缩一下 —— 那种抖是最伤手感的。
     */
    private fun showWave() {
        if (typing || waveOn) return
        waveOn = true
        // 这三个标志一起摆平:空字幕要算「占位态」,否则松手之后紧接着要上的
        // 那句话会被它自己上一条的「讨好条件」挡掉(见 setCaptionPlaceholder)。
        captionIsPlaceholder = true
        captionLivePartial = false
        // 水波也算一种「占位态」,所以它一样要起表、一样要撤掉排队的那句 ——
        // 不然他按下手说话时,上一条排队的话会在他**正说着**的时候冒上来。
        placeholderSince = System.currentTimeMillis()
        cancelDeferredCaption()
        captionTarget = ""
        captionShown = 0
        // ★ 2026-10-05 下午:这里原来还有一句 `stopCaptionTicker()`(停掉胶囊里
        //   那台打字机)。胶囊现在**没有打字机了**(见上面那段),而那台
        //   唯一剩下的打字机属于**上面那行** —— 他按下说话时她正好说到一半的话,
        //   不该被这一下**卡在半句上**。他开口 → [liveCaption] 会整句换掉它。
        caption.text = ""
        caption.visibility = View.VISIBLE
        // ★★ 那条水波**从这里退场了**。理由和原话见本函数上面那段 KDoc。
        //
        //   原来这里是三句:`wave.start()` + 一段「亮起来之后要把 VAD 报过的
        //   『听见了』补回去」的补救(因为 350ms 那一拍会把样子打回 `~~~`)。
        //   水波整条不亮了,那套补救自然也没有对象 —— 留着它只会让人以为
        //   这里还有一副样子要维护。`speechHeard` 那个记账**留着**:它仍然是
        //   「这一轮 VAD 到底有没有报过」的账本,松手那一支还要读它。
        //
        // wave.start()
        // if (speechHeard) wave.setHeard(true)
    }

    /** 收掉水波。**幂等**,而且不碰字幕的可视性 —— 谁接管那一格由调用方决定。 */
    private fun hideWave() {
        if (!waveOn) return
        waveOn = false
        wave.stop()
    }

    // ---------------------------------------------------------------- 跟她说话
    //
    // ★★ 2026-10-04 晚他当面纠正的形状,这一整段都是为它写的:
    //
    //   我之前把耳朵做成了「点一下 🎤 → 说一句 → 文字进输入框 → 发出去」,
    //   也就是**语音转文字**。他纠正:「我不是要语音转文字,我是要直接对话,懂?」
    //
    //   对的形状是**对话**:进去之后你只管说 → 她答 → 你再接着说不碰任何东西、
    //   也不用再喊,一直聊到你说「不聊了」或者停住不说。
    //
    //   所以这里管的不再是「听一句」,而是**一轮接一轮**。

    /** 对话进行中。它管的是「这一轮结束了接下来干什么」,不是麦克风的开关。 */
    private var voiceSession = false

    /** 这一轮她到底听没听到东西 —— 用它判「对话该不该结束」,不去嗅错误文案。 */
    private var heardThisTurn = false

    /**
     * 这一轮**VAD 报过「他开口了」**没有 —— 和 [heardThisTurn] 是两件事。
     *
     * | | 谁置的 | 什么时候 | 拿它干什么 |
     * |---|---|---|---|
     * | [heardThisTurn] | [Callback.onHeard](**转写完**) | 一两秒后 | 判对话该不该结束 |
     * | 这个 | [Callback.onSpeechStart](**VAD**) | 几十毫秒 | 水波该摆哪一副样子 |
     *
     * ★ 它存在的**唯一**理由是那 350 毫秒的先后手:耳朵在手指按下时就开听,
     *   水波要等 [EarSessionMath.TAP_MAX_MS] 才亮,而 VAD 正好落在这中间。
     *   详细的失败链见 [showWave] 里那段 —— 那边也钉着用户的原话
     *   「那个线条 ~~~ **变基因序列样变不了**」。
     */
    private var speechHeard = false

    /**
     * 这一轮是**被手指掐掉的**(点一下 = 打断),不是「没人说话」。
     *
     * ★ 缺了它就会有一个很难看的 bug:他点一下打断她,而点的那一下会**先开麦再取消**,
     *   `onDone` 回来时 `heardThisTurn` 是 false → 按原逻辑**整场对话就此结束**。
     *   他看到的会是「我就点了她一下,她就不理我了」。
     *   这两种「什么都没听到」必须分开 —— 一种该结束,一种不该。
     */
    private var roundCancelled = false

    /** 手指是不是正按在光晕上。用它挡掉重复的 DOWN(没有它,一次长按会开出好几个麦)。 */
    private var pressDown = false

    /** 按下光晕之前,对话是不是本来就开着 —— 「点一下打断」要据此判断该不该留在这轮对话里。 */
    private var sessionBeforePress = false

    /**
     * 手指按下去的那一刻,她的嘴张开了没有 —— 「点一下」要据此分岔(打断她 / 去打字)。
     *
     * ★★ 为什么要**按下时**记一份、而不是松开时现问一次:
     *   因为 350ms 之后 [confirmHold] 会**主动把她掐停**(按住说话 = 先让她停)。
     *   掐完之后 [herSounding] 就是 false 了,而 UP 再去问它,会得到
     *   「她刚才没在说话」—— 于是一次想打断她的短按会**弹出键盘**。
     *   他真正想表达的那件事,在**按下的那一瞬间**就已经定下来了;判据跟着它走才对。
     */
    private var herSoundingBeforePress = false

    /** 这一轮开麦是不是**他手指按着**开的。它只影响「没听到东西之后」怎么收,见 [startEarTurn]。 */
    private var turnFromHold = false

    /**
     * 这一下**上滑取消了** —— 录的东西要**扔**,不能送出去。
     *
     * ★ 用户 2026-10-04 晚点名:「**如果说错了,就往上滑,就能取消语音发送**」。
     *   做成一个标志而不是「松手时现算距离」,是因为**发出去之后就没有回头路**:
     *   一旦手指进入了取消区,哪怕他最后又滑回来,这一次也**认取消**
     *   —— 宁可多取消一次(他重说一遍就行),也不能出现
     *   「明明上滑了,那句话还是送出去了」。这一条和 `type` 的验货是同一条规矩:
     *   **不可逆的那一侧,判据一律从严。**
     */
    private var swipeCancelled = false

    /** 上滑取消的判定起点(按下时的原始 Y)。 */
    private var captionDownRawY = 0f

    /**
     * 「上次他真的有动静」的时刻。
     *
     * ★ [EarSessionMath.SESSION_SILENCE_MS] 那 45 秒是**从这儿往后数**的,不是从开麦那一刻。
     *   跟着「最后一条真实动静」走,他说话的**速度**才不会被计费 ——
     *   一句长话说到第 40 秒才停,那 40 秒不该算进「他没说话」。
     */
    private var silenceSince = 0L

    /**
     * 按住够了 [EarSessionMath.TAP_MAX_MS] 才兑现的「我在听」。
     *
     * ★ 这就是「点一下」和「按住」共用一个手指时**唯一不撒谎**的排法:
     *   按下的瞬间先开麦(手感要快),但**界面上一声不吭** ——
     *   因为这一下是点还是按,要到松开才知道。要是一按下就亮「我在听」,
     *   那每一次「点一下打断她」都会先闪一句「我在听」,**而那是在撒谎**。
     */
    private val confirmHold = Runnable {
        if (!pressDown) return@Runnable
        // ★★ **按住说话 = 先让她停,再听你说**(2026-10-05 用户当场在两条路里选的这一条)。
        //
        //   为什么打断放在**这儿**、不放在按下那一刻:
        //   手指刚落下时分不出「点一下」和「按住」,而这两件事对她的嗓子要求**相反** ——
        //   点是「别说了」,按住是「你先停,轮到我说」。在 DOWN 就打断的话,
        //   一次短按(想打断她)会先把她的队伍清空、再被 UP 判成「她本来就没在说话」,
        //   于是弹出来的**是键盘**。放到 350ms 之后,这里已经确定是**按住**了,不会连累那一下。
        //
        //   ★ 判据用 [EarSessionMath.herVoiceFinished] 而不是 [herSounding]:
        //     她在合成下一句的那十几秒里 `herSounding` 是 false,可**队伍里还压着话** ——
        //     只看 `herSounding` 的话,这种时候按下去会「什么都没停」,而你还以为停了。
        if (herVoiceBusy()) {
            ModelManager.get(this).trace(
                "耳: 按住说话 —— 先让她停(在念=${SherpaVoice.isSpeaking()},还欠=${SherpaVoice.pendingCount()} 句," +
                    "系统嗓子=${speakingText != null || tts?.isSpeaking == true})"
            )
            interruptHer()
            // ★ 打断之后要留一拍再开麦:喇叭里还有尾音,立刻开麦会把最后半个字听成新输入
            //   (见 [EarSessionMath.COOLDOWN_MS])。
            // ★★ 这里**必须带 `fromHold = true`** —— 松手才算「我说完了」,那就是按住说话的语义。
            //   `resumeListening` 默认按 `fromHold = false` 放麦(那条是「她念完自己重开」用的),
            //   混了的话你会按住说完、松手,而那台机器**不认松手**,只能干等 VAD 那 5 秒。
            armVoiceResume(EarSessionMath.COOLDOWN_MS, fromHold = true)
        }
        setVoiceState(VOICE_LISTEN)
        // ★ 原来是 `setCaptionPlaceholder(HINT_LISTENING)`(一句「我在听…」)。
        //   2026-10-04 晚他点名换成了那条流动的水波 —— 同一件事,换一种说法。
        //   它仍然只在这一刻才出现(350ms 之后、松手之前),所以「点一下」那条路
        //   永远不会闪出它来,这一点和原来一模一样。
        showWave()
        her("setListening(true)")
        showLog("🎤 听着呢,松手我就当你说完了")
    }

    /**
     * 下一次「放麦」要不要按**按住说话**的语义接上(松手即「我说完了」)。
     *
     * ★ 为什么要有它:[resumeListening] 一个 Runnable 管两件事 ——
     *   「她念完自己重开麦」(那不是他按的,`false`)和「按住说话被打断之后放麦」(`true`)。
     *   后者的松手必须能收尾,否则他按住说完、一松手,那台机器**不认**。
     * ★ 由 [armVoiceResume] 在**重排时一起覆盖**:重排就是「旧的作废」,
     *   所以最后排进去的那一个说了算。全是主线程动作,不会有两个重排在飞。
     */
    private var resumeFromHold = false

    /** 「该把麦放回去了」。会话中反复重排,见 [armVoiceResume]。 */
    private val resumeListening = Runnable {
        if (voiceSession) startEarTurn(fromHold = resumeFromHold)
    }

    /**
     * 排下一次「放麦」。**会话外什么都不做** —— 这条守住了就不会出现
     * 「早就不聊了,麦却还自己开起来」那种最难查的幽灵。
     *
     * ★ 它是**重排不是追加**:`removeCallbacks` 必须在 `postDelayed` 之前。
     *   少了那一句,「她念完(T+20s)」和「兜底(T+90s)」会各排一次,
     *   于是每聊一轮就多欠一次开麦 —— 聊十轮之后你一停下,她会连着听十次。
     */
    private fun armVoiceResume(delayMs: Long, fromHold: Boolean = false) {
        if (!voiceSession) return
        // ★ 重排 = 旧的作废,所以「这一次算不算按住说话」也跟着被覆盖(见 [resumeFromHold])。
        resumeFromHold = fromHold
        root.removeCallbacks(resumeListening)
        root.postDelayed(resumeListening, delayMs)
    }

    /**
     * 进入 / 退出对话。
     *
     * ★ 退出走 [endVoiceSession],这里只管「进」和「已经在了就出」。
     *   以前这个函数是「点一下听一句」,现在点一下是**开始聊**。
     */
    private fun toggleEar() {
        if (voiceSession) { endVoiceSession(null); return }
        if (Ear.isListening) { Ear.cancel(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_EAR)
            return
        }
        voiceSession = true
        // 说是「不用再喊」,但他得知道**怎么结束** —— 不然她会一直等,
        // 而「她一直在等」看起来跟「她卡住了」一模一样。
        showLog("🎤 聊着呢 —— 说「不聊了」,或者停一会儿不说话,就结束")
        startEarTurn(fromHold = false)
    }

    /**
     * 开麦听这一轮。会话中会被反复调用([armVoiceResume])。
     *
     * ★ [fromHold] **改的是「这一轮没听到东西之后该怎么办」**,不是怎么录:
     *   · `true` —— 他手指按着开的麦。松开就是「我说完了」,
     *     这时候要是**一个字节都没收到**,正确的动作是**把麦关掉、别再自己开回来**
     *     (他的原话:「我手按住字幕,和说完话后 45 秒,**其他不要开麦克风**」)。
     *   · `false` —— 她说完话之后那 45 秒的窗口。同样什么都没听到 = **窗口走完了**,
     *     该结束这场对话。
     *   两者在 [earCallbacks] 的 `onDone` 里分开处理 —— 那也是同一段代码里
     *   唯一需要知道「麦是怎么开起来的」的地方。
     */
    private fun startEarTurn(fromHold: Boolean) {
        if (isFinishing || !voiceSession) return
        if (Ear.isListening) return
        // ★★ 半双工闸(2026-10-05 晚补的一格):**她嗓子里还有话,就不许开麦。**
        //
        //   这是 `onSpokenDone` 那条老规矩(**她说完了才把麦放回去**)在
        //   「她说完了吗」这个问题上唯一靠得住的判据。老规矩只问了 `herSounding`
        //   (≈「嘴正在动」),而她的句子是**排队**念的:合成一句要十几秒
        //   (实测实时倍率 0.35×~0.79×,日志里排队等待到过 18177ms)。
        //   那十几秒里没有声音、`herSounding` 是 false —— 于是麦克风被提前打开,
        //   她下一句一开口,每个字都绕回自己的麦克风。
        //
        //   用户 2026-10-05 描述得和日志一字不差:
        //   > 「她还没说完,系统就误认为她说完,就打开麦克风,然后她就自己跟自己聊起来了」
        //
        //   ★ 判据在 [EarSessionMath.herVoiceFinished](纯逻辑,有单测)。
        //   ★ 被挡下来**不是收工** —— 重新排一次,她说完了这一轮自己会开。
        //     不重排的话麦克风就再也回不来了,而那正是这个项目最怕的那种
        //     「静默失败」:她说完这句,就再也不理他了。
        if (herVoiceBusy()) {
            ModelManager.get(this).trace(
                "耳: 她嗓子里还有话(在念=${SherpaVoice.isSpeaking()},还欠=${SherpaVoice.pendingCount()} 句," +
                    "系统嗓子=${speakingText != null || tts?.isSpeaking == true}),这一轮先不开麦"
            )
            // ★ 重排时**把本次的 `fromHold` 带过去** —— 被打回来的这一下就是「按住说话」的话,
            //   下一次放麦也得按按住说话的语义放(松手算说完)。丢了它的话,
            //   他按住 → 碰到她还在说 → 被挡 → 等她说完了麦才开,而那时已经不认松手了。
            armVoiceResume(EarSessionMath.COOLDOWN_MS, fromHold)
            return
        }
        heardThisTurn = false
        // ★ 和上面那条一起清,理由也一样:它是**这一轮**的账。
        //   漏到下一轮的话,水波一亮就摆成 GENE —— 那一轮他还没开口,
        //   而「替麦克风撒谎」是 [VoiceWaveView] 最不该犯的错(见 [showWave])。
        speechHeard = false
        turnFromHold = fromHold
        swipeCancelled = false
        // ★ 每一轮都要清。「上一轮是被手指掐掉的」这件事**绝不能漏到这一轮** ——
        //   漏过来的话,这一轮他真的什么都没说时,我们就会以为「他是掐的」,
        //   于是**再也不会自己收工**,麦一直开着。那是隐私那条线上的事。
        roundCancelled = false
        // 他一开口,她就不说话、抬起眼看他 —— 这是「她在听」唯一看得见的证据
        her("setListening(true)")
        // ★ 每开一次麦就点一次灯。**自动重开的那些轮次也要走这一行** ——
        //   以前只有手指按下时点([confirmHold]),于是「她答完自动重开麦」那几轮
        //   麦克风是开的、灯却是灭的:界面上写着「没在听」。
        //   那不只是一个难看的 bug,它是**「她不理我」这条体感的来源之一** ——
        //   坏掉的指示灯和坏掉的耳朵,在屏幕上长得一模一样。
        //   顺带:灯亮 = 屏幕不息屏(见 [keepScreenForVoice]),整场对话屏幕就不会黑。
        setVoiceState(VOICE_LISTEN)
        Ear.listen(this, earCallbacks)
        // ★ 灯只在**真的开起来了**才留着亮。[Ear.listen] 会在两种情况下
        //   **原地打回、一个字节都不录**:麦克风被推流占着、识别模型没装。
        //   那两种下 `isListening` 仍是 false —— 灯必须跟着熄。
        //   ★ 判据用 `isListening` 而不是去认 [Ear.Callback.onNotice] 的文案:
        //     文案是给人看的,以后加第六种失败原因时它不会跟着改,
        //     而这种 bug 的样子正是**界面在替她撒谎**(灯亮着,麦是关的)。
        //   ★ 也**不用** `onNotice` 当熄灯点:它每一轮「没听见你说话」都会走一遍,
        //     那样灯会每五秒闪一下 —— 把稳定的指示器变成噪音。
        if (!Ear.isListening) setVoiceState(VOICE_OFF)
    }

    private val earCallbacks = object : Ear.Callback {

        /**
         * 他正在说的那半句 —— 贴到字幕上,让他看见「我在听了、我听到的是这些」。
         *
         * ★ **它绝不进脑子**。这一版只是「到目前为止听到的全貌」,
         *   半句话送过去她会答非所问;真正进脑子的永远是 [onHeard] 那一版。
         *   两条路在 [sendText] 那里汇合,而这里根本不碰它。
         */
        /**
         * ★★ **他刚开口,她就把细线换成基因序列。**
         *
         * 用户 2026-10-05 原话:
         *   「我说的类似基因序列的,是**它识别到我讲话的时候才会**,不然**还是线条 ~~~**,
         *     目的是**区分她到底有没有听到我说话**」。
         *
         * ★ 这一版**原来挂在 [onPartial] 上,是接错了信号** —— 局部解码实测要
         *   1.6~1.7 秒,而一句两秒的话说完了那个结果才回来。真机日志把这件事
         *   钉得很死:09:54:38 松手和第一版解码落在**同一个 100 毫秒**里,
         *   于是基因序列只活了零点几秒 —— 用户看到的结论是「好像没看到」。
         *
         * ★ VAD 探到人声只要几十毫秒,而且它回答的**正好就是那个问题**:
         *   「她到底有没有听到我说话」。它不回答「她听懂了什么」—— 那是 [onHeard]。
         *   这两件事在这条链路上必须分开,混起来的代价是界面替麦克风撒谎。
         *
         * ★ 放在最前面,**连上滑取消那一支之前**:「她有没有听见」和
         *   「这句话要不要发出去」是两回事,后者取消不该让前者倒退回「没听见」。
         */
        override fun onSpeechStart() = runOnUiThread {
            if (isFinishing) return@runOnUiThread
            // ★ 先记账再摆样子:水波可能**还没亮**(手指刚按下不到 350ms),
            //   这一笔要留着给 [showWave] 用它去补那一句(见那个字段的注释)。
            speechHeard = true
            wave.setHeard(true)
        }

        override fun onPartial(text: String) = runOnUiThread {
            if (isFinishing) return@runOnUiThread
            // ★ 兜底:万一这一轮的 VAD 一次都没命中(那 [onPartial] 本来也来不了),
            //   解码出了字仍然算「她听见了」。正常路径由 [onSpeechStart] 那条先到。
            wave.setHeard(true)
            // ★ 已经上滑取消了就别再往上贴字 —— 那句「松手就不发出去」
            //   必须一直是屏幕上最新的东西,不然他看到的是半句话,
            //   会以为取消没生效。
            if (swipeCancelled) return@runOnUiThread
            // ★ 整句已经出来了,中途那版就没意义了(它在 [onHeard] 之后才到是有可能的:
            //   最后一次解码和最终那次转写会前后脚回来)。贴上去会把定稿**盖回半句**。
            if (heardThisTurn) return@runOnUiThread
            liveCaption(text)
        }

        override fun onHeard(text: String) = runOnUiThread {
            if (isFinishing) return@runOnUiThread
            heardThisTurn = true
            // ★ 他真开口了 —— 45 秒那笔账**从这一刻重新开始数**。
            //   少了这一句,长对话会在「开场 45 秒」到点后**突然收工**,
            //   而且看起来毫无理由(他明明一直在说话)。
            silenceSince = System.currentTimeMillis()
            // ★ 挂断在**本地判**,省一轮模型。而且必须判在她答之前 ——
            //   让模型去理解「不聊了」,她会认真地回一句「好的,那我们下次聊」,
            //   然后**继续等着听**,看起来像没听懂。
            if (voiceSession && EarSessionMath.isExitPhrase(text)) {
                endVoiceSession("好,那我不吵你了")
                return@runOnUiThread
            }
            // ★ 不经过输入框 —— 说话这条路直接把话交出去(见 [sendText])
            sendText(text)
        }

        // showLog 自己会 post 回主线程,不用再包一层 —— 但水波得**显式**收:
        // 它可能正**冻在**那一格等着定稿(见松手那支),而「没听见」这条路
        // 不会再有任何东西来接管那一格,不主动收它就**永远停在那儿**。
        override fun onNotice(msg: String) = runOnUiThread {
            if (!isFinishing) hideWave()
            showLog("✗ $msg")
        }

        override fun onDone() = runOnUiThread {
            if (isFinishing) return@runOnUiThread
            her("setListening(false)")
            // ★★ 这一轮她**一个字都没听到** —— 该怎么办。
            //
            //   2026-10-04 晚用户把开麦的形状定死了,原话:
            //   > 「我手按住字幕,和**说完话后 45 秒**,**其他不要开麦克风**」
            //   > 「我手没按字幕,如果说了话,就完整过一遍,
            //   >   要是没检测到说话,就自动关麦克风」
            //
            //   拆成四种,一种都不能少:
            //   · 上滑取消的([swipeCancelled])→ **什么都不做**。他自己说了不要,不是没人说话
            //   · 手指掐的([roundCancelled])  → **什么都不做**,打断不是散场
            //   · 这一轮**是按住开的**([turnFromHold])→ 收工。
            //     ★★ 这条是新的,也是他这次真正在抱怨的那条。以前这里会
            //     `armVoiceResume` 再听一轮:他按住说一句、松手、什么都没录到,
            //     麦**自己又开了**,一遍一遍地开到他 45 秒用完 ——
            //     而他的规矩是「手没按着就不要开麦」。松手 = 结束这一轮。
            //   · 自动那 45 秒走完了 → 收工(这就是他要的「没检测到说话就自动关麦」)
            //
            //   判据仍然是「听没听到」而不是去认那句提示文案 ——
            //   失败的理由有五种(没听见人声 / 太短了 / 被推流占着 / 模型没装 / 读麦报错),
            //   五种都表现为「没听到东西」;认文案的话以后加第六种就会静默地漏掉。
            if (!voiceSession || heardThisTurn || roundCancelled || swipeCancelled) return@runOnUiThread
            if (turnFromHold) { endVoiceSession(null); return@runOnUiThread }
            val silentFor = System.currentTimeMillis() - silenceSince
            if (EarSessionMath.sessionOver(silentFor)) {
                showLog("⏳ 静音 ${EarSessionMath.SESSION_SILENCE_MS / 1000} 秒，本轮监听结束")
                endVoiceSession(null)
            } else {
                // ★ 还有时间,再听一轮。间隔给一点点,不是 0:
                //   0 会让「收工 → 重开」连成一串,日志里看不出是两个回合,
                //   真排查的时候会以为它一次听了 45 秒。
                armVoiceResume(EAR_RETRY_GAP_MS)
            }
        }
    }

    /**
     * 收工。
     *
     * ★ 这里要**收干净**,而且顺序有讲究:先落 [voiceSession] 再动别的 ——
     *   反过来(`Ear.cancel()` 在前)会有一个窗口让 `resumeListening` 挤进来
     *   再开一次麦,而那正好落在「已经说好不聊了」之后。
     */
    private fun endVoiceSession(farewell: String?) {
        voiceSession = false
        root.removeCallbacks(resumeListening)
        root.removeCallbacks(confirmHold)
        pressDown = false
        if (Ear.isListening) Ear.cancel()
        her("setListening(false)")
        // 收工时那块光晕要熄掉 —— 留着它亮就是在说「我在听」,而麦已经关了。
        setVoiceState(VOICE_OFF)
        restCaption()
        if (farewell != null) say(farewell)
    }

    /**
     * 开始一场对话(幂等)。**按下的那一刻**就该进来 ——
     * 45 秒那笔账是从这儿起算的,不是从「听到第一句话」起算。
     */
    private fun beginVoiceSession() {
        if (voiceSession) return
        voiceSession = true
        silenceSince = System.currentTimeMillis()
    }

    // ------------------------------------------------------------------
    // 那块光晕:手势 + 呼吸
    // ------------------------------------------------------------------

    /**
     * 那块**玻璃胶囊**被碰了(箭头那一小块除外,见 [sendBtn])。
     *
     * ★ 这里排的是**同一个手指上的三件事**,用户 2026-10-04 原话:
     *   · 「我说话的时候按住那个动态光晕,就开始说话」
     *   · 「**如果说错了,就往上滑,就能取消语音发送**」
     *   · 「我打断她,就点那个光晕字幕就打断了」
     *     然后当晚补了第四件:「**键盘输入改成点对话框**」
     *
     *   按下的瞬间判不出来他想干哪一件 —— 只有松开时才知道这一下是长是短、走没走。
     *   所以:**按下先开麦(手感),但界面上先不承认在听**(见 [confirmHold]),
     *   松开时再分:
     *   · 短  = 点,她在说话 → **打断她**(把刚才那几百毫秒的录音丢掉)
     *   · 短  = 点,她没说话 → **进打字那一态**(见 [setTypingMode])
     *   · 长  = 按住说话     → 松手即「我说完了」,当场收尾转写([Ear.finishNow])
     *   · 长 + 往上滑        → **取消**:这一句连转写都不做,直接扔掉
     *
     * ★★ 「点」这一下为什么要**分岔**,而不是干脆全给键盘:
     *   因为打断她**只有这一个入口**。全给键盘的话,她一旦开始念一段长话,
     *   你就只能等她念完 —— 而「我说一句她就闭嘴」正是他最早提的那条。
     *   判据选「她当时在不在说话」也是**唯一不别扭**的一条:她安静的时候
     *   你想打字,她正在说的时候你想的第一件事永远是让她停。
     *   ⚠️ 这条判据有个已知的窄缝:她**在想**(还没出声)的时候点一下会去打字。
     *     那是故意的 —— 那种时候你想说下一句比想打字多,而打字的路
     *     只在「她安静」时才通,不会挡着谁。
     *
     * ★★ 上滑**必须在一进入取消区就当场表态**,不能等松手再算距离:
     *   他手指还按着的时候就得知道「现在松手是取消还是发送」,
     *   否则那几十毫秒里他会以为已经取消、松手之后字却送出去了。
     *   这是这一整套手势里**唯一不可逆**的一步,判据一律从严(见 [swipeCancelled])。
     */
    private fun onCaptionTouch(v: View, e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                if (pressDown) return true          // 重复 DOWN 直接吞掉
                // ★ 按下的回执,当场给。(放在耳朵那条判断**之前**:没有耳朵时这一下
                //   也真的发生了一件事 —— 它切到了打字。回执要跟**发生了什么**走,
                //   不是跟「我们希不希望这件事发生」走。)
                buzz()
                // ★★ 没有耳朵的时候,这一整套手势(按住说话 / 上滑取消)一件也没有意义 ——
                //   当场改道去打字,而且**不进手势状态**:
                //   `pressDown` 保持 false,于是 UP 那一支开头的
                //   `if (!pressDown) return true` 会把后面所有分支一起吃掉,
                //   不会有人再跑去 `Ear.finishNow()` 或者点亮「在听」。
                //
                // ★ 这句话是从被删掉的 🎤 按钮那儿搬过来的(见「打字那一态」那段)。
                //   删按钮不该顺手把「为什么按了没反应」的唯一一句解释也删掉 ——
                //   现在它绑在**动作**上,比原来绑在按钮上更准。
                if (!Ear.available(this)) {
                    toast("她还没有耳朵(识别模型没装),先打字跟她说。")
                    setTypingMode(true)
                    return true
                }
                pressDown = true
                pressStartedAt = System.currentTimeMillis()
                captionDownRawY = e.rawY
                swipeCancelled = false
                // ★ 记下「按之前是不是就在聊」——「点一下打断」要不要留在这场对话里, 靠它判。
                sessionBeforePress = voiceSession
                // ★★ 还要记下**按下的那一刻她的嘴张没张** —— UP 那支只能问这一份,
                //   不能现问 [herSounding]:350ms 之后 [confirmHold] 会主动掐停她,
                //   现问的话得到的是「掐停之后的她」,不是「你按下时的她」。见那个字段的注释。
                herSoundingBeforePress = herSounding
                beginVoiceSession()
                startEarForGesture()
                root.removeCallbacks(confirmHold)
                root.postDelayed(confirmHold, EarSessionMath.TAP_MAX_MS)
                return true
            }

            android.view.MotionEvent.ACTION_MOVE -> {
                if (!pressDown) return true
                // 只认**往上**。往下滑是「算了我不说了」以外的意思,这里不解释它 ——
                // 多认一个方向就多一种误取消,而误取消的代价是他白说一遍。
                // ★ 门槛的算术收在 [BarGestureMath] 一份(和悬浮窗那条共用)—— 见它的文件头:
                //   这个判断错起来的样子是「那句话还是送出去了」(不可逆)。
                if (!BarGestureMath.entersCancelZone(
                        captionDownRawY, e.rawY, dp(BarGestureMath.CANCEL_SWIPE_DP).toFloat())) return true
                if (swipeCancelled) return true    // 已经取消了,别重复喊
                swipeCancelled = true
                // ★ 当场把话掐掉:turnFromHold 那一轮**不再转写**。
                //   只置标志是不够的 —— 那只是「送出去之后不处理」,
                //   而这条规矩要的是**根本不做转写那几秒的活**。
                if (Ear.isListening) Ear.cancel()
                root.removeCallbacks(confirmHold)
                setVoiceState(VOICE_OFF)
                // `force = true`:这一句**必须盖过**当时字幕上的任何东西。
                // 取消是一次不可逆的动作,他要的是「我现在松手会怎样」的答案是确定的 ——
                // 而那句提示被上一句台词挡掉的话,他就只能靠猜。
                //
                // ★★ 2026-10-05:那个 `↥` **拿掉了**。用户原话:
                //   「他的房间,和悬浮窗,**往上滑还是不要有箭头**。
                //     垃圾桶,等等,**就红色光晕就行**」。
                //   所以这件事现在有**两个回执**,都不是图标:
                //     · 这块玻璃**红了**([setCancelGlow]) —— 它说的是「松手会怎样」
                //     · 这行字说的是「怎么做」,而且不再自带一个画出来的箭头
                setCaptionPlaceholder(HINT_SWIPE_CANCEL, force = true)
                setCancelGlow(true)
                showLog("上滑取消,松手这一句就作废")
                return true
            }

            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                if (!pressDown) return true
                pressDown = false
                // ★★ 红光晕**绝不越过这一次手势**:手指一松,不管落在哪一支,先熄掉。
                //   放在这儿而不是各支里各写一遍,是因为 UP 有三支(CANCEL/TAP/HOLD),
                //   漏一支的后果是「玻璃红着,但松手已经不算取消了」—— 那是界面在撒谎,
                //   而且红着的时候他不敢说话。
                setCancelGlow(false)
                root.removeCallbacks(confirmHold)
                val held = System.currentTimeMillis() - pressStartedAt
                pressStartedAt = 0L

                // ★★ 这一下算什么,**判据不在这儿** —— 在 [BarGestureMath.release]。
                //   悬浮窗那条走的是同一个函数:用户 2026-10-05 把两处一起点了名,
                //   而「上滑取消」错起来的样子是**那句话还是送出去了**(不可逆)。
                //   那边有一条测试专门钉「上滑取消了就算只按了六十毫秒也是取消」——
                //   写成 `isTap` 在前的话,那一下会落到 TAP,进打字模式,而话已经出去了。
                when (BarGestureMath.release(swipeCancelled, held)) {

                    BarGestureMath.Release.CANCEL -> {
                        // ── 上滑取消:录音在 MOVE 那一步就已经掐了,这里只收界面
                        swipeCancelled = false
                        roundCancelled = true          // 这是「他自己不要的」,不是「没人说话」
                        restCaption()                  // 字幕回到「按住说话」,重新说一遍就行
                        if (!sessionBeforePress) endVoiceSession(null)
                    }

                    BarGestureMath.Release.TAP -> {
                        // ── 点一下。**两件事,看她当时在不在说话** ——
                        //
                        // ★★ 2026-10-04 晚用户点名改了这半边:
                        //   > 「**键盘输入改成点对话框**」
                        //   在这之前,「点一下」**只会**打断她 ——
                        //   所以这一下现在要分岔,而分岔的判据只有一条:**她的嘴张没张**。
                        roundCancelled = true          // 这一轮不算「没人说话」,见它的注释
                        if (Ear.isListening) Ear.cancel()
                        // ★★ 判据是**她的嘴真的张开了没有**([herSounding]),
                        //   不是「这句话交给嗓子了没有」([speakingText])。
                        //
                        //   内嵌嗓子的整句合成要好几秒(实测 ≈1 倍实时),那几秒里
                        //   [speakingText] 早就不是 null 了,可是**一个字节的声音都没有**。
                        //   原来拿它当判据 → 你在这几秒里点一下,那一句**在合成里就被作废**:
                        //   一个字都没出过声,而日志上写着「念「…」」,看着像念过。
                        //   用户 2026-10-05 点名:「点一下时,如果她还没出声,别把整句作废」。
                        //
                        //   ★ 两副嗓子都盖住了:内嵌那条在**声音出来时**立它,
                        //     系统那条在交给 `tts.speak` 时立它 —— 见 [startTalking]。
                        //
                        // ★★ 2026-10-05:判据从**现问** [herSounding] 改成**按下那一刻记的**
                        //   [herSoundingBeforePress]。原因是新加的「按住说话先让她停」:
                        //   [confirmHold] 会在 350ms 后把她掐停,掐完 `herSounding` 就是 false,
                        //   现问的话这一支会以为「她本来就没在说话」→ 你去打断她,弹出来的是键盘。
                        if (herSoundingBeforePress) {
                            // 她在说 → 这一下是「别说了」。这条**一个字都没让**:
                            // 他当初就是点名要的「我打断她,就点那个光晕字幕就打断了」,
                            // 而打字那条路本来也不需要抢这一下 —— 你不说话的时候
                            // 才需要打字,她正说着的时候你想干的第一件事就是让她停下来。
                            interruptHer()
                            restCaption()
                            if (!sessionBeforePress) endVoiceSession(null)
                        } else {
                            // 她的嘴没张开 → 这一下是「我要打字」(见 [setTypingMode])。
                            //
                            // ★★ 这一支现在盖**两种**情形,而且是有意的:
                            //   ① 她什么都没在做 —— 老情形,照旧;
                            //   ② **她这轮已经交给嗓子了,声音还没出来**(还在合成)。
                            //      这一支**不碰她的嗓子** —— 所以那句照念,一个字不作废。
                            //      他点的这一下本意是「我要打字」,不是「别说了」;
                            //      真要说「别说了」,等她出声之后那一下就是打断。
                            //
                            //   ★ 代价说清:合成那几秒里想打断她,要点**两下**
                            //     (第一下开键盘、第二下才是打断)。这是拿「不误杀那一句」
                            //     换来的,**不是漏掉的**。
                            //
                            // ★ 无条件收工:打字和开麦是**互斥**的两态 ——
                            //   键盘响着、麦克风还开着,那不只是浪费,
                            //   是你打字的声音会被她当成话听进去。
                            //   (收的是**耳朵**,不是嗓子 —— 见 [endVoiceSession]。)
                            endVoiceSession(null)
                            setTypingMode(true)
                        }
                    }

                    BarGestureMath.Release.HOLD -> {
                        // ── 松手 = 我说完了
                        Ear.finishNow()
                        // 松手到定稿之间还有一两秒(VAD flush + 转写),那一格不能空着 ——
                        // 他刚说完一句最重要的话,屏幕上一片空白看起来就像白说了。
                        //
                        // ⚠️ 水波**不能就这么留着流**:它的意思是「麦克风开着」,
                        //   而这时候麦已经关了。用一条还在流、底下却没有麦的波去骗人,
                        //   是这个项目反复吃过的那种账。
                        //   → ★ 2026-10-05:整条水波已经退场(见 [showWave]),
                        //     所以这里只剩「收掉」一个动作,不存在「留着流」的风险了。
                        //
                        // ★★ 当天晚些时候他把「没听到」那一支也删了 —— 原本写的是
                        //   「在认你说的话…」。原话:
                        //   > 「那个**正在认你说的话**(或者正在思考)**删掉**,因为
                        //   >   **有最右边的方格就不需要了**」
                        //   他说得对:那颗按钮(见 [refreshSendBtn])已经把「她在忙」写在那儿了,
                        //   而且写得更准 —— 它同时盖住「想」和「说」两种忙。
                        //   再摆一句占位话,是**同一件事说两遍**。
                        // ★★ 2026-10-05:那一格现在**没有第二副样子**可留了 ——
                        //   水波整条退场(见 [showWave]),这里原来那句
                        //   `wave.freeze()`(冻住基因序列)跟着一起没了。
                        //
                        //   而它想解决的那件事**已经自然成立**:松手到定稿之间
                        //   那一格上摆着的就是他**最后一句实时字幕**,是
                        //   [liveCaption] 写的、已经在了。冻住水波当初是为了
                        //   「别让那一格空着」,现在那儿本来就有字。
                        //   ⚠️ 别把 `freeze()` 加回来:`freeze` 只停排帧、
                        //   **不碰 `visibility`**,而那条水波现在从没 start 过 ——
                        //   加回来只是白写一句没人看懂的代码。
                        hideWave()
                        setVoiceState(VOICE_LISTEN)
                    }
                }
                return true
            }
        }
        return false
    }

    /** 开麦。权限没给就去要 —— 要不到就说清楚,不装作在听。 */
    private fun startEarForGesture() {
        if (Ear.isListening) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_EAR)
            return
        }
        startEarTurn(fromHold = true)
    }

    /**
     * 打断她。
     *
     * ★ 只 `tts?.stop()` 是不够的:字幕上那句、她脸上的嘴、还有 `speakingText`
     *   都会留在「她还在说」的状态里。而且**嘴会一直张着** —— 那正是
     *   `setTalking(false)` 存在的理由。
     */
    private fun interruptHer() {
        // ★ 插话监听也要收 —— 它是她自己开的一段录音。她已经被叫停了,
        //   再留着它就只是在跟马上要开的耳朵抢麦克风(见 [BargeIn])。
        //   放在最前面:它是这里唯一一个会去动音频设备的异步线程。
        BargeIn.stop()
        tts?.stop()
        // ★ 两副嗓子都得闭嘴。漏了内嵌这句的后果很具体:
        //   系统那条停了、合成线程还在跑,几百毫秒之后它把 `onDone` 送到
        //   [onSpokenDone] —— 而那一刻**已经是新的一轮了**,于是麦克风被提前放开。
        SherpaVoice.stop()
        speakingText = null
        // ★ 她已经被叫停了 —— 「嘴真的张开过」这一格跟着收,
        //   否则下一句还没出声时你再点一下,会被当成「她还在说」(见 [herSounding])。
        herSounding = false
        // ★★ 号也要撤 —— 上面那句 `tts?.stop()` **保证不了引擎不再回调**
        //   (这一段的注释讲的就是「几百毫秒之后它把 onDone 送过来」)。
        //   不撤的话那一声会落在**已经属于新的一轮**的麦上,而她正是点了这一下
        //   想让她闭嘴的。撤了之后 [EchoMath] 就会把它扔掉。
        ttsId = null
        her("setTalking(false)")
        // ★ 打字机也要停。不停的话她会**在闭了嘴之后继续往外吐字** ——
        //   那看起来像「打断了没用,她还在说」,而他正是点这一下想让她闭嘴的。
        setCaptionNow(captionTarget)
        // ★★ 2026-10-06:用户明确要求「打断后字幕消失」—— 原来用 settleHerSay(吐完整句),
        //   现在改成 clearHerSay(直接清掉、置 GONE)。等她说完新一轮再重新显示。
        //   他那行字在他开口时由 [liveCaption](line 3318) 重新设成 VISIBLE。
        clearHerSay()
        setVoiceState(VOICE_OFF)
        refreshSendBtn()
    }

    /**
     * ★★ **那颗圆按钮点下去做的事** —— 把「她在想」和「她在说」一起掐掉。
     *
     * 用户 2026-10-05 原话:
     * > 「我要用一个可以**随时打断,停止她思考、说话**的按钮」
     *
     * ★ 「随时」这两个字就是这份设计的全部:她**想**的时候(模型在跑、工具在调)
     *   和**说**的时候(TTS 在念、打字机在爬)是两套完全不同的东西,
     *   而他要的是**不用先分辨是哪种**就能按下去。所以两件一起做:
     *   · [AiAgent.stop] —— 掐掉思考。它是**唯一**能停下工具循环的口,
     *     不调它的话她会在你按下之后继续在电脑上点东西(而她人已经「闭嘴」了,
     *     那看起来像闹鬼);
     *   · [interruptHer] —— 掐掉说话(嗓子 + 嘴 + 两套打字机)。
     *
     * ★★ 最后那句 [armVoiceResume] **不是善后,是必修**:
     *   语音会话里「把麦放回去」的时机**挂在 TTS 的 `onDone` 上**(半双工闸),
     *   而 `tts.stop()` **不会回调 onDone**。少了这一行,他打断一次之后
     *   麦就再也不会开 —— 现象是「她被打断一次之后就不听我说话了」,
     *   而且**不报错、日志干净**。(同一个道理见 [Callbacks.onError] 那条路。)
     *   ★ 传 0 而不是冷却:被打断时扬声器里本来就没有尾音要等。
     */
    private fun interruptAll() {
        // ★ 打断 = 这一轮不存在了 —— 「先出声」的账跟着一起作废。
        //   不清的话,下一轮会拿它去对账(见 [forgetEarlySaid])。
        //   ★ [interruptHer] 会把内嵌嗓子停掉,而 [SherpaVoice.stop] **不兑现**
        //     speak 那个回调(见它的类头契约)—— 所以哪怕 [earlyOwnsTurn] 立着,
        //     也不会有回调来触发放麦;**这里下面那句 armVoiceResume(0L) 就是放麦**,
        //     而她已经闭嘴了,不冲突。
        forgetEarlySaid()
        agent().stop()
        interruptHer()
        setCaptionPlaceholder(HINT_STOPPED, force = true)
        armVoiceResume(0L)
    }

    /**
     * **他插话了** —— [BargeIn] 判定他在她说话时开了口,从这里把她停下、把麦交出去。
     *
     * ★ 与他按那颗圆按钮([interruptAll])差三件事,每一件都是故意的:
     *   · **不动 agent()** —— 她这时候只是在念,没有在思考;真在跑工具的话,
     *     那件活儿是他在她开口**之前**交代的,不该被一声「等一下」删掉;
     *   · **不写「已停止」** —— 他不是要停,是要说话,字幕该留给下面那句;
     *   · **一定放麦** —— 同 [interruptAll]:`tts.stop()` 不回调 onDone,
     *     少了这一行他插完一句,麦就再也不开(这正是插话的全部意义)。
     *
     * ★ 判定那一下**必须已经不在说话**才继续:回调是异步落到主线程的,
     *   排到它的时候她可能已经自己说完了(见 [herSounding])。
     */
    private fun onBargeIn() {
        if (isFinishing || !herSounding) return
        interruptHer()
        armVoiceResume(0L)
    }

    /**
     * **她正忙吗** —— 三种忙都得算上:在想([herBusy])、在说([speakingText])、
     * 以及**她那行字还在往外爬**(打字机没追平)。
     *
     * ★ 第三种最容易被漏掉,而它恰好是**他最可能去按的那一小段**:
     *   TTS 念得快、打字机追得慢,她闭了嘴之后屏幕上还会再爬一两秒。
     *   漏了它的话,那两秒里按钮会变回「发送」——
     *   他一按不是打断,是**把输入框里的话当新问题发出去**。
     */
    private fun herIsBusy(): Boolean =
        herBusy || speakingText != null || herSayShown < herSayTarget.length

    /**
     * **那颗圆按钮按状态换职责**:平时是发送,她忙的时候是「停」。
     *
     * ★★ 为什么是同一颗按钮,而不是在旁边再加一颗 —— 他要的是
     *   「一个可以**随时**打断的按钮」,而「随时」两个字否决了「新加一颗」:
     *   · **位置就是可达性**。他的手指本来就在这儿(打完字要按发送)。
     *     新加一颗,他得先找它在哪、再够过去 —— 那不叫随时;
     *   · 而且这颗按钮**在她忙的时候原来是「按不动 + 暗掉」的**
     *     (`isEnabled = false` / `alpha = 0.45`)。把一颗按不动的按钮
     *     换成一颗按得动的「停」,**不添一件新东西,只换一个职责** ——
     *     这正是他删过三次装饰之后留下的一贯要求。
     *
     * ★★ 2026-10-06 晚:**忙态那个记号从「白 ■ + 近黑方块」换成了「两条竖线 + 白圆」。**
     *
     * 他连着两轮的原话:
     *   ① 「**不是,不是齿轮,是黑框,一个方形的**,她说话用来打断她的」
     *   ② 「那个难看的她说话打断她的那个按钮……**能不能就是,改成好看一点的**」
     *
     * 病根是**借错了皮**:忙态原来借的是 [R.drawable.btn_her] —— 那是房间里那几个
     * 小开关和齿轮的皮(近黑**方块** + 8dp 圆角 + 粉描边)。于是闲态是**白圆 + 深色箭头**、
     * 忙态是**近黑方块 + 白方块**,同一个位置两颗按钮,却**不是一套**。
     *
     * 现在忙态 = 闲态**同一颗白圆**(连三态一起,直接引用 [R.drawable.btn_her_send]),
     * 只把里面的记号换成两条竖线([R.drawable.btn_her_stop])。
     * ★ 两颗之间差的**只剩记号** —— 「忙」这件事实就该占这么点分量;
     *   而停 = 两条竖线,和那个「↑」是同一套笔画,不用教。
     *
     * ★ 「■ 不是汉字」那条**照旧成立**(他说过「右边的那箭头不要搞成说字,难看」):
     *   两条竖线同样是**形状**,不是字 —— 而且是**画出来的矢量**,不是字形
     *   (字形长什么样由系统字体决定,这颗按钮的表情不能跟着别人机器变,见 [her_ic_pause])。
     */
    private fun refreshSendBtn() {
        val stop = herIsBusy()
        // ★ 「停下来」这件事**永远按得动** —— 这一行就是整个功能的全部意义。
        sendBtn.isEnabled = true
        sendBtn.alpha = 1f
        if (stop) {
            // ★ 忙态**一个字都不放** —— 记号全在底里那两条竖线上([R.drawable.btn_her_stop])。
            //   留着一串空文字是有意的:`sendBtn.text` 是**上一态**留下的("↑"),
            //   不清掉的话箭头会压在圆上、和两条竖线叠成第三个记号。
            sendBtn.text = ""
            sendBtn.background = getDrawable(R.drawable.btn_her_stop)
        } else {
            sendBtn.text = "↑"
            sendBtn.setTextColor(0xFF160F13.toInt())
            sendBtn.background = getDrawable(R.drawable.btn_her_send)
        }
    }

    /** 那颗圆按钮:她在忙就是「停」,否则是「发」。 */
    private fun onSendOrStop() {
        if (herIsBusy()) interruptAll() else send()
    }

    /**
     * 「在听 / 在说 / 都停了」**唯一的落点**。
     *
     * ★★ 这里**曾经会换底色、会呼吸**;再往前,它背后还有一层糊出来的紫雾。
     *   2026-10-04 晚他连着删了三次,现在它只剩一件事:**说话的时候别息屏**。
     *
     *   三次删的是三样东西,但**是同一个理由** —— 他的原话,按发生顺序:
     *     > 「算了还是把那个光晕删了吧好难看」(那层雾)
     *     > 「那个紫色的光晕删了,保留原来的那种没有装饰的」(胶囊底色)
     *     > 「把麦克风删掉!因为我**手机用麦克风本身就会显示**」(那行状态字)
     *
     *   ★ 第三次是最值得记的一次:那行字本来是为了守住
     *     「麦克风开着的时候,界面上必须有明确的『我在听』」这条不变量才加的,
     *     而他指出的是一件事 —— **那盏灯 Android 自己就在亮**(见 buildBottomBar 里那段)。
     *     所以要求没被放弃,只是**供应商换了**:从我们画,换成系统画。
     *     他说的「不骗你」是有道理的,查证之后确实如此。
     *
     * ★ 顺带记一笔删掉的东西,免得有人再手痒加回来:
     *   `her_caption_glow` / `her_caption_bg` 两张 drawable 已经**从项目里删了**。
     *   ⚠️ 但 `applyCaptionBg` 里那次 `mutate()` 的教训本身是对的、留着有用:
     *   `getDrawable()` 拿到的可能是**所有使用者共享的那一份 `ConstantState`**,
     *   要改它的 alpha 必须先 `mutate()`,不然会改到别人的 —— 而这种 bug 的样子是
     *   「另一处的颜色莫名变了」,没人会怀疑到动画头上。以后哪张图要动 alpha,照用。
     */
    private fun setVoiceState(mode: Int) {
        // ★★ 它的身体现在是**空的**,但**别把它删掉、也别把已经搬走的那行搬到调用处去**。
        //
        //   它唯一做过的事曾经是「在听 / 在说的时候别让屏幕睡」——
        //   2026-10-04 晚这条规矩**被用户自己放大了**:
        //     > 「还有就是息屏,能不能**常驻不息屏**如果开在前台就不息屏」
        //   于是判据从「她当时在干什么」换成了「房间在不在前台」,
        //   实现搬去了 [onResume] / [onPause] 那对函数(只有那里答得出「在前台没有」)。
        //
        //   ★ 留着这个空函数,是因为它在十几个地方被调,而那些地方说的是
        //     **同一件事**:「从现在起,她的耳朵开着 / 她的嘴张着 / 两边都停了」。
        //     那是一个**概念**,不是一行代码 —— 而散着写的话,下一个
        //     「按状态该发生的事」会漏掉其中一处,那种 bug 长这样:
        //     **她还在说,屏却黑了。**
        //
        //   (参数 `mode` 留着没动:调用处传的是 VOICE_OFF / VOICE_LISTEN /
        //    VOICE_SPEAK,下一个「按状态该发生的事」直接用它就行。)
    }

    /**
     * 房间在前台就**别让屏幕睡**。
     *
     * ★★ 2026-10-04 晚用户点名放大的(原来只在「在听 / 在说」时候拦):
     *   > 「还有就是息屏,能不能**常驻不息屏**如果开在前台就不息屏」
     *
     * ★ 判据是**生命周期**而不是「她当时在干什么」,这一点很要紧:
     *   挂在这个 Activity 上,就天然只在她**看得见**的时候生效 ——
     *   退到后台、锁屏、切走,系统自己就把这条规则收回了。
     *   拿状态去判(以前那样)反而会漏:比如她**在想**(还没出声)的那几十秒里
     *   屏黑掉,他一抬头看到的是黑屏,而不是「她在想」。
     *
     * ⚠️ 用的是窗口标志,不是 `PowerManager.WakeLock`:
     *   前者**不需要任何权限**,而且**随窗口消失自动失效** ——
     *   不会出现「忘了释放、电一直掉」那种事故。这是这条需求能安全实现的全部理由。
     */
    private fun keepScreenOn(on: Boolean) {
        try {
            if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) { /* 窗口没了就算了,这是体验不是功能 */ }
    }

    /**
     * 在字幕上写一句**占位**话(「按住说话」/「我在听…」)。
     *
     * ★ 它有**讨好条件**:只有在字幕空着、或者本来就写着占位的时候才写。
     *   不加这条的话,收工时那句 `restCaption()` 会把她刚说完的那句话**擦掉** ——
     *   而那句话正是他抬头要看的东西。
     */
    private fun setCaptionPlaceholder(text: String, force: Boolean = false) {
        // ★ `force` 只给**上滑取消**用:那句「松手就不发出去」必须盖过一切。
        //   其余每一处都得守这个「讨好条件」,见上。
        // ★ [captionLivePartial] 和 [captionIsPlaceholder] 一起放行:实时字幕那半句
        //   也不是「一句已经说完的台词」,该被占位话盖掉(否则收工后它会卡在那儿)。
        if (!force && !captionIsPlaceholder && !captionLivePartial &&
            caption.text.isNotEmpty()) return
        captionIsPlaceholder = true
        captionLivePartial = false
        // ★ 占位话的**计时从这里开始** —— 见 [CaptionDwell]。
        //   换了一句占位话就等于重新起表(「上滑取消」那句 force 进来的也算)。
        placeholderSince = System.currentTimeMillis()
        // 占位话**不走打字机** —— 「按住说话」一点点爬出来,看着像卡了
        setCaptionNow(text)
        caption.setTextColor(0xCCFDF2F6.toInt())
        caption.textSize = 15f
        revealCaption()
    }

    /**
     * 那块玻璃**红了** = 现在松手这一句就作废。
     *
     * ★★ 用户 2026-10-05 原话:
     *   > 「他的房间,和悬浮窗,**往上滑还是不要有箭头**。垃圾桶,等等,**就红色光晕就行**」
     *
     * 红光晕替掉的是一句 `↥ 松手就不发出去` 的提示文字(箭头拿掉了),理由在这个项目里
     * 说过很多次了:**手指还按着的时候,他看的是那块玻璃的颜色,不是那行小字**。
     * 光比字快,也比字准。
     *
     * ★ 和悬浮条上那套是**同一份**做法([ConMarnBubble.setCancelGlow]),连红都是一个色号
     *   (`#E53E3E` 系,见 [R.drawable.her_glass_pill_cancel])—— 同一个意思在同一个屏幕上
     *   用两种红,是没有道理的。
     * ★ 它**只在取消区里出现**,松手就换回去。平时屏幕上没有红 ——
     *   常驻的颜色就是「装饰」,那是他删过三次的东西(见记忆 `ruoxi-ui-no-decoration`)。
     */
    private fun setCancelGlow(on: Boolean) {
        try {
            captionWrap.background = getDrawable(
                if (on) R.drawable.her_glass_pill_cancel else R.drawable.her_glass_pill
            )
        } catch (_: Exception) {
            // 换不出图(理论上不会)时**不能连累取消这件事本身** ——
            // 手势才是主体,颜色只是回执。
        }
    }

    /** 回到静止态那句「按住说话」。同样只在它**还没说定**的时候才动。 */
    private fun restCaption() {
        // ★ 实时字幕那半句也要放行 —— 见 [captionLivePartial]。它是「还没说定」的另一种。
        if (!captionIsPlaceholder && !captionLivePartial) return
        captionIsPlaceholder = true
        captionLivePartial = false
        placeholderSince = System.currentTimeMillis()   // 见 [CaptionDwell]
        setCaptionNow(HINT_HOLD)
        caption.setTextColor(0xCCFDF2F6.toInt())
        caption.textSize = 15f
        revealCaption()
    }

    /**
     * 他自己**正在说的那半句**(实时转写的中途结果)。用户 2026-10-04 晚点名要的:
     * > 「**为什么我自己说话没有字幕?我要我自己说话实时字幕**」
     *
     * ★★ 2026-10-05:它从胶囊里搬到了**上面那行**([herSay])。
     *
     *   原来的实现最后一句是 [revealCaption],而那个函数**第一件事就是 [hideWave]** ——
     *   于是**水波被它自己招来的实时字幕顶掉了**。用户的原话:
     *   > 「问题就是我按住那个语音输入**几秒后就没有了**」
     *   日志里的时间正好对得上:`开始听` → 两秒后 `实时字幕开工` → 水波消失。
     *
     * ★ 搬到上面那行之后,两件事**一起**对了:
     *   · 水波在**整段按住期间**不被打断(它是「麦克风开着」唯一看得见的东西);
     *   · 他说的和她说的是**同一行**、按时间先后覆盖 —— 那正是「字幕」本来的样子。
     *
     * ★ 写法上**不走打字机** —— 实时字幕每一版都是**整段重解的结果**,不是「比上次多了几个字」。
     *   拿去喂打字机的话,目标串会被一次次改写,已经吐出去的字会被追着改回来,
     *   看起来像字幕在抽搐。整体换掉反而最稳:每一次都是「当前听到的全貌」。
     */
    private fun liveCaption(text: String) {
        // ★ 它也是**真话**(他自己正在说的那半句),而且他正盯着看 ——
        //   不能让它被一句排队中的话晚一步盖回去。见 [deferredCaption]。
        cancelDeferredCaption()
        // ★★ 2026-10-05 下午(第三次改写法,前两次的账都记在这儿):
        //
        //   ① 第一版:写进 [herSay] → 撤回。理由是「[herSay] 是**她的**那行,
        //      两个人两行,合起来就分不清谁在说」。
        //   ② 第二版:写进**胶囊**([caption])。理由是用户当天的原话
        //      「我要我说话是**变说变有字在透明磨砂玻璃上的**」。
        //   ③ **现在是第三版,而且是他拿着截图要回来的**:那一行字在胶囊里
        //      就是**位置错了** —— 他要的是两个人**共用上面那一行**,
        //      谁最后说谁占着(见 [applyCaption] 里他那段原话)。
        //
        //   ★ 第 ① 版的两个顾虑现在都**不成立**了,所以这次不会再翻回去:
        //     · 「分不清谁在说」—— 区分在**颜色和字号**上(他灰 14f / 她亮 16f),
        //       而这是 [sayCaptionOnly] / [onPartial] 从第一天就在做的;
        //     · 「两行并存」—— 现在根本**没有两行**:只有这一行。
        //
        //   ★ 不走打字机:实时那几版是**整段重解**的结果,
        //     不是「比上次多了几个字」。喂给打字机会让目标串被一次次改写、
        //     已经吐出去的字被追着改回来 —— 看起来像字幕在抽搐(见上面那段)。
        //   ★ 不走 [showCaption](排队):排队那套是为「占位话别一闪而过」写的,
        //     而这一句他**正盯着看**,晚一步就是「没反应」。
        lastCaptionAt = System.currentTimeMillis()
        herSay.setTextColor(0xFFB9A6AE.toInt())
        // ★ 他说的那句 = 基准 14(比她小一号,一眼分得清谁在说);长句照样收小。
        laneBaseSize = 14f
        fitHerSay(text.length)
        stopCaptionTicker()
        herSayTarget = text
        herSayShown = text.length
        herSay.text = text
        herSay.visibility = View.VISIBLE
        // ★ 他自己那句话也**算说定的** —— 她开口之前它该稳稳待在那儿,
        //   而不是被她规划时的空 partial 撤掉(见 [laneFinal])。
        if (text.isNotEmpty()) laneFinal = true
        laneTrace("他说的写上屏")
    }

    // ★ 这里曾经有一个 `VoiceListener`(走**系统** `SpeechRecognizer` 的那条路)。
    //   2026-10-04 晚跟着 🎤 按钮一起删了 —— 它是那个按钮的回调,按钮没了,
    //   它就再也不会被构造。删它的理由和上面那段一样:**死代码最贵的地方
    //   不是它占的那几行,是下一个人会认真读它,然后以为这条路是活的。**
    //   留着的那点知识(这台机器没有 RecognitionService)已经写进注释里了。

    // ==================================================================
    // 发送 / 回调
    // ==================================================================

    /**
     * 胶囊右边那颗**圆箭头**。
     *
     * ★★ `t.isEmpty()` 那一支**不是空转**,是**出打字态的门**:
     *   点进来打字之后,如果不是想发、只是想收回键盘,把字删干净再点一下箭头
     *   就出去了。没有这一支的话,输入框会一直占着胶囊,「按住说话」再也回不来 ——
     *   而**一扇只能进不能出的门**比没有门更糟。
     *   (她说话的时候点胶囊也能出去,但那一条得看她当时在不在说,不算数。)
     */
    private fun send() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) {
            if (typing) {
                setTypingMode(false)
                restCaption()
            }
            return
        }
        input.setText("")
        setTypingMode(false)          // 发完就收回键盘:这条路的终点是「她收到」
        sendText(t)
    }

    /**
     * 把一句话交给她。**打字和说话都汇到这儿** —— 只有一条路进脑子,
     * 「她收到的是什么」就不可能因为来源不同而分叉。
     *
     * ★ 2026-10-04 晚他纠正过一次,这条注释就是那次纠正留下的:
     *   走语音的时候**不许再 `input.setText()`**。以前是
     *   「认出的话塞回输入框 → 调 [send]」,功能上是通的,但**体验的形状是错的**:
     *   字先掉进输入框、再被发出去 —— 那看起来就是**语音转文字**。
     *   他要的是**对话**:你说 → 她答 → 你接着说,中间不碰任何东西、也不用看字。
     *   所以输入框只属于打字那条路;说话这条路直接把话交出去,字幕上见。
     *   (汇合点保住了,形状也对了 —— 这两件事不矛盾。)
     */
    private fun sendText(t: String) {
        // ★★ 2026-10-05 更正:**他的话在玻璃上,她的话在上面那行。**
        //
        //   上一条我把他打的字也搬去了 [herSay],理由是「两条路写同一行就不会分叉」——
        //   理由是错的,因为**这两行本来就不是同一行**,它们是两个人。
        //   他的原话:「**我要我说话是变说变有字在透明磨砂玻璃上的**,
        //   不要说完才能一起显示」。而 [herSay] 是给她的(见 [say])。
        //
        //   所以这里回到 [showCaption](fromHer = false) —— 走说话那条路时
        //   [liveCaption] 已经实时写过同一格了,这里是**定稿覆盖那半句**;
        //   打字那条路没有实时转写,少了这一句他打的字就永远上不了屏。
        showCaption(t, fromHer = false)

        // ★★ 2026-10-05 晚:用户发新消息时,如果她正在说话,**立刻闭嘴**。
        //   原来只靠 handoff(等旧 AI 任务退出)来间接触发停声 —— 而旧任务可能
        //   卡在等 PC 工具响应,要 5~25 秒才退出。这段时间里她还在念旧内容,
        //   而用户已经发了新消息。这条修法是:发新消息的那一刻,嗓子先停。
        //   handoff 继续在后台处理 AI 任务的交接,但声音已经没了。
        if (SherpaVoice.isSpeaking() || SherpaVoice.pendingCount() > 0) {
            SherpaVoice.stop()
            tts?.stop()
            speakingText = null
            herSounding = false
            her("setTalking(false)")
        }

        // ★ 新的一轮开始 —— 上一轮「先出声」的账**一个字都不许留**。
        //   留下的话,她会拿着上一轮那句话去跟这一轮的定稿对账,而**对不上账**
        //   的后果是把这一轮的话整段重念一遍(安全的,但白念一遍)。
        forgetEarlySaid()
        agent().start(t, Callbacks())
    }

    private inner class Callbacks : AiAgent.Callback {
        override fun onLog(msg: String) { showLog(msg) }
        override fun onTrace(msg: String) { Log.i(TAG, msg) }

        override fun onPartial(text: String) = runOnUiThread {
            // 她还在想 —— 只在字幕上滚动显示,不念(念半个句子是折磨)
            lastCaptionAt = System.currentTimeMillis()
            // ★★ 2026-10-05:她的字**全部改走上面那行**([herSay]),不再碰胶囊里的 caption。
            //   原来这几行同时踩了两个坑,而两个的最终症状是同一句话 ——
            //   「她说了话,屏幕上却没有」:
            //     · `caption` 和输入框**互斥**:[revealCaption] 第一行就是 `if (typing) return`,
            //       所以他打字的时候**她的话一个字都上不了屏**;
            //     · 写进 `caption` 等于占掉「他正在说的那半句」的座位,两段字会互相顶。
            //   颜色暗一点 = **还在生成**;定稿时 [say] 会把它调亮。这个区分保留。
            herSay.setTextColor(0xFFB9A6AE.toInt())
            // ★ 她还是草稿 —— 基准 14(比定稿暗、小一号);长句照样收小(见 [CaptionFit])。
            laneBaseSize = 14f
            fitHerSay(text.length)
            // ★★ 这一版是**草稿** —— 只有草稿才允许被后面那条空 partial 撤掉
            //   (见 [laneFinal])。**空串不算草稿**:它本身就是那条「撤掉」,
            //   在这里解了锁,`typeHerSay("")` 就会把上一句**说定的真话**一起清掉。
            if (text.isNotEmpty()) laneFinal = false
            typeHerSay(text)
            // ★ 她一开口,胶囊里那句**占位话**就该让位(她停下之后那句「好,停下了」、
            //   或者收工时的「按住说话」)。留着它等于同一件事说了两遍 ——
            //   而且画面上她还是一副「没在说话」的样子,可她明明已经开口了。
            //   清空但**不改 [captionIsPlaceholder]**:空的一行仍然占一行高(胶囊不会跳),
            //   而收工时那句「按住说话」照旧认得它该回来。
            if (captionIsPlaceholder) setCaptionNow("")
            // ★★ 先出声(2026-10-05)—— 整段还没生成完,但**已经成句的那部分能念了**。
            //
            //   不加这一段的话,她要等所有 token 吐完才开口:按 7 token/秒算,
            //   一段 200 token 的回答 = **近 30 秒的绝对安静**,然后哗啦一下全说出来。
            //   判定在 [SpeechChunkMath],念法在 [speakEarly]。
            //
            //   ★★ 2026-10-05 下午从「只抢一块」改成了「一块一块往下抢」。
            //     原来那句是 `if (earlySaid.isEmpty())`,也就是**整轮只开口那一下**;
            //     第一块念完之后要**一直等到定稿**才有下一声 —— 用户听到的就是
            //     「前半句话……后半句话,会中间加延迟」(真机日志里那段空白是 **8 秒**)。
            //
            //   ★ 为什么现在可以多抢:[SpeechChunkMath.nextChunk] 只从
            //     **已经念过的那一段之后**再切一句,而且**只认句读**
            //     ([SpeechChunkMath.MIN_CHARS] 那个下限也照旧)。所以每一块都是一句
            //     完整的话,不是把句子剁碎 —— 那一笔「不随文本变短的 TTS 固定开销」
            //     才是原来只敢抢一次的理由,剁碎了它反而更慢。
            //
            //   ⚠️ 改了口(模型把前面重写了一遍)时 `nextChunk` 回 null ——
            //     **就此停手,交给定稿那条路整段重念**。宁可重复,绝不按长度硬切。
            val chunk = SpeechChunkMath.nextChunk(text, earlySaid)
            if (chunk != null) speakEarly(chunk)
        }

        override fun onFinal(msg: String) = runOnUiThread {
            // ★★ 「先出声」的收尾:字幕上**整句**,嗓子里只补**还没念过的那半截**。
            //
            //   ★ 对不上账([SpeechChunkMath.spokenPrefix] 返回 -1,比如模型改了口)
            //     → **整段重念**。宁可重复,也绝不按长度硬切 ——
            //     硬切的那一下是把半句话吞掉,而且**不报错**。
            //   ★ `rest` 空 = 抢出去的那一块**就是全部** —— 一个字都不许再发,
            //     否则会听见她把同一句重复一遍。
            //
            //   ★★ 真回调(`onDone`)只在下面这一次 `speak` 上,一轮一次,和改之前完全一样。
            //     抢出去那一块是**空回调**(见 [speakNow])——
            //     所以「她说完了,才把麦放回去」这条半双工契约一个字都没动。
            val at = SpeechChunkMath.spokenPrefix(msg, earlySaid)
            val chunkDone = earlyDone              // ★ forget 会把它清掉,先接住
            val spokeEarly = at >= 0
            forgetEarlySaid()
            if (!spokeEarly) {
                say(msg)
            } else {
                sayCaptionOnly(msg)
                val rest = msg.substring(at)
                when {
                    rest.isNotBlank() -> speak(rest)
                    // 抢出去的**就是全部** —— 没有第二声可念,也就没有真回调会来。
                    // ★ 但那一块**可能还在响**(它挂的是 [onEarlyChunkDone] 那个只管
                    //   「这一块交割完了」的回调),所以**必须**由它来结这一轮:
                    //   · 已经响过了 → 现在就结;
                    //   · 还在响 → 让它响完那一刻再结。
                    //   直接在这儿调 [onSpokenDone] 的话,麦会在她还在说话时就开。
                    chunkDone -> onSpokenDone()
                    else -> {
                        earlyOwnsTurn = true
                        ModelManager.get(this@ConMarnActivity)
                            .trace("先出声:那一块就是全部,等它念完再放麦")
                    }
                }
            }
            // ★ 会话里:她这段话念完就得把麦放回去,**但放麦的时机由 TTS 说了算**
            //   (见 attachTtsListener 的 onDone)。这里只是先架一道兜底 ——
            //   因为下面这四种情况 onDone **一个都不会来**:她的回复出错、
            //   TTS 引擎没就绪、有人把「出声」关了、念到一半引擎崩了。
            //   没有这道兜底,循环就死在这一轮,而症状是「她说完这句就不理我了」。
            //   ★ 注意传的是 ttsOn:嗓子关着的时候一秒都不能等(见 EarSessionMath)。
            armVoiceResume(EarSessionMath.speechWaitMs(willSpeak = ttsOn))
            // ★★ 2026-11-08(体检 E1)：这一轮里她可能刚定了一条定时任务 ——
            //   那是「到点自己跑、跑完只发通知」的那条路，没通知权限就等于白跑。
            //   放在这里是因为**他刚提出这件事**，问一句最自然；没有任务时它就是一次
            //   两个判断的短路，不打扰任何人。见 [askTaskNotifIfNeeded]。
            askTaskNotifIfNeeded()
        }

        override fun onError(msg: String) = runOnUiThread {
            showLog("✗ $msg")
            // ★★ 也要落进 model.log。房间里的日志框**不解锁、不点开就没人看得见** ——
            //    2026-10-04 晚就是这么栽的:她每句话都在 runLoop 里当场抛(电脑没连),
            //    而从 adb 那边看到的是「她跟没反应一样」,一行证据都没有,
            //    只能靠 netstat 反推。错误是**稀有事件**,写它不会刷爆日志。
            ModelManager.get(this@ConMarnActivity).trace("房间:✗ $msg")
            // ★★ 出错就没有「她念完」这回事了 —— 立刻把麦放回去,别让她晾在那儿。
            //
            //   ★ 但**有一个例外**:如果这一轮已经「先出声」抢出去一块,而它**可能还在响**
            //     (她的第一句在生成途中就念了,紧接着模型就出错——中间可能只有几百毫秒)。
            //     那一刻立刻开麦 = 把扬声器里的声音听回去 → **自问自答**,不报错、日志干净。
            //
            //     所以抢过就**让「那一块念完」来放麦**:把它记成 [earlyOwnsTurn],
            //     再挂上别处一样的兜底([EarSessionMath.speechWaitMs],和 [Callbacks.onFinal]
            //     那条路同一个数)。**没抢过的照旧 0** —— 老行为一个字不动。
            val early = earlySaid.isNotEmpty()
            forgetEarlySaid()
            if (early) {
                earlyOwnsTurn = true
                armVoiceResume(EarSessionMath.speechWaitMs(willSpeak = true))
            } else {
                armVoiceResume(0L)
            }
        }

        override fun onBusy(busy: Boolean) = runOnUiThread {
            // ★★ 2026-10-05 用户:「**右边的那箭头不要搞成说字,难看**」。
            //
            //   那颗按钮从建出来那一刻起就是一颗箭头([herButton] 建它时传的就是 "↑"),
            //   「说」是后来为了「把忙闲写在按钮上」改上去的 —— 他不要。
            //   **要保留的是「她在忙」这件事,不是那两个汉字**。
            //   (同一个道理见 [ConMarnBubble.refreshStatus] 里那颗一样的箭头。)
            //
            // ★★ 当晚他又补了下一步:「我要用一个可以随时打断、停止她思考、说话的按钮」
            //   —— 于是「她在忙」从一个**状态**升级成了一个**动作**:
            //   原来这颗按钮只是「按不动 + 暗下去」,现在它**换成「停」并且按得动**。
            //   忙闲还是那个信息,只是从「暗下去」换成了「换形状」——
            //   信息没丢,而且顺手变得有用了。见 [refreshSendBtn] / [interruptAll]。
            herBusy = busy
            refreshSendBtn()
            // ★★ 这里原来还会摆一句「在想…」的占位话。2026-10-05 用户点名删掉:
            //   「那个正在认你说的话(或者正在思考)删掉,**因为有最右边的方格就不需要了**」。
            //   ★ 他抓住的是要害 —— 那颗按钮已经从「按不动的暗箭头」变成了**能按的停**
            //     (★ 2026-10-06 晚记号又换了一次:现在是**两条竖线**,见 [refreshSendBtn]),
            //     「她在忙」这件事它已经说全了(而且顺带能按)。
            //     再在胶囊里写一句「在想…」,是同一件事说两遍,还得占着他刚说完的那句话的座位。
            //   ★ 删掉不丢信息:**忙闲仍然唯一地由 [refreshSendBtn] 表达**,
            //     而「她开口了」由上面那行([herSay])说 —— 各说各的,不重。
        }

        override fun onConfirm(summary: String, detail: String): Confirm {
            // 阻塞等用户点头(和主界面 runAi 同一套:后台线程挂住,UI 弹框)
            val latch = CountDownLatch(1)
            val ok = booleanArrayOf(false)
            // ★ 记「框到底弹出来了没有」。没有它就分不清「他点了取消」和「压根没弹出来」——
            //   而这两件事对用户的意义完全不同(一个是他的选择,一个是我们少了权限)。
            val built = java.util.concurrent.atomic.AtomicBoolean(false)
            runOnUiThread {
                try {
                    AlertDialog.Builder(this@ConMarnActivity)
                        .setTitle(summary)
                        .setMessage(detail)
                        .setPositiveButton("确认") { _, _ -> ok[0] = true; latch.countDown() }
                        .setNegativeButton("取消") { _, _ -> latch.countDown() }
                        .setCancelable(false)
                        .show()
                    built.set(true)
                } catch (_: Exception) {
                    latch.countDown()       // 弹不出来就立刻放行等待,由上面的 built 说清是哪一种
                }
            }
            val answered = try {
                latch.await(60, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                return Confirm.INTERRUPTED
            }
            return when {
                !built.get() -> Confirm.NO_UI
                !answered -> Confirm.NO_ANSWER
                ok[0] -> Confirm.APPROVED
                else -> Confirm.DENIED
            }
        }

        /**
         * 钱 / 身份那一类动作：**他点头之后，还要验一次本人**。
         *
         * ★ 两道不是同一件事（见 [GateMath] 的文件头）：门口那道挡的是「别人拿到了你的手机」，
         *   这一道挡的是「她判断错了，或者手机正被别人开着」。
         * ★ 弹不出来 / 没录指纹时 [AppGate.needsHard] 是假 —— 那时候**不能**把这动作卡死，
         *   否则这台机器上钱的事永远做不了。代价写在设置那一行里。
         * ★ 没过返回 [Confirm.NOT_YOU]，不是 [Confirm.DENIED]：他确实点了确认，
         *   说「你点了取消」是假话，模型会据此换个法子再试一次。
         */
        override fun onConfirmHard(summary: String, detail: String): Confirm {
            val first = onConfirm(summary, detail)
            if (first != Confirm.APPROVED) return first
            if (!AppGate.needsHard(this@ConMarnActivity)) return first
            val ok = AppGate.askBlocking(
                this@ConMarnActivity,
                "确认是你",
                "这一步碰到钱或身份，动手前再验一次本人",
            )
            return if (ok) Confirm.APPROVED else Confirm.NOT_YOU
        }
    }

    /**
     * 过程日志。★★ **2026-10-04 晚它不再往屏幕上写了,只往 `model.log` 写。**
     *
     * 那块屏幕上的黑框被用户点名删掉了(原话和理由见 [buildBottomBar] 里那段)。
     * 但**这些行一条都不能丢**,所以改了去向,不是删了调用:
     *
     * - 里面有一句 `✗ 她: …`([HerBridge.onError]),那是**她出错时唯一的出口**。
     *   为了好看把报警灯一起拆掉,是这个项目已经写过好几遍的那种错。
     * - 剩下的 `→ 执行 …` / `← …` / `快通道:…` 是**她干活的过程**。
     *   她干活看不见过程的时候,「她卡住了」和「她没在做事」长得一模一样。
     *
     * ★ 还有第二个消费方:`AiAgent` 那边把它 tee 了一份给 [ConMarnBubble]
     *   (见 `AiAgent.start` 里那个 `tee`),所以泡泡球面板里照样看得到。
     *   **这里只管 model.log 那一份。**
     *
     * ★ 前缀 `[房]` 是给 grep 用的:一次调试只有一条命令能把整轮还原出来。
     */
    private fun showLog(msg: String) {
        try { ModelManager.get(this).trace("[房] $msg") } catch (_: Exception) {}
    }

    /** 她主动开口了(见 [ProactiveGreeting])。 */
    fun onSpontaneous(line: String) = runOnUiThread { say(line) }

    // ==================================================================
    // 生命周期
    // ==================================================================

    /** 盖一层跟房间底色一样的黑底 —— 见 [onResume] 里那段话。重复调用是安全的。 */
    private fun coverForGate() {
        if (gateCover != null) return
        val v = View(this).apply {
            setBackgroundColor(0xFF160F13.toInt())
            isClickable = true          // 吃掉底下的触摸:验之前点不着任何东西
        }
        root.addView(v, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        gateCover = v
        // ★ 记一笔。这块黑底**看上去和「房间没画出来」一模一样**(同一个色值 `#160F13`),
        //   2026-11-08 那次就是这么误诊了半小时 —— 日志里必须有「盖了/撤了」这两行,
        //   否则下次只能靠数视图树才分得清。
        showLog("门口锁:盖黑底(等指纹)")
    }

    private fun uncoverGate() {
        gateCover?.let {
            root.removeView(it)
            showLog("门口锁:撤黑底(验过了)")
        }
        gateCover = null
    }

    override fun onResume() {
        super.onResume()
        // ★★ 门口那道锁（2026-11-06）—— 冷启动一次、熄屏回来一次，别的时候不咬人。
        //
        //   为什么盖一层黑底：系统那个指纹弹窗**是半透明的**，底下看得见她的房间。
        //   不盖的话「验了」和「没验」看到的是同一幅画面 —— 那这道锁只挡住了输入，
        //   没挡住内容。
        // ★ 验不过（按了取消 / 弹不出来）就退出房间，不是退回一个空壳 ——
        //   留在原地会让人以为「没按就进来了」。
        if (AppGate.needsUnlock(this)) {
            coverForGate()
            AppGate.ask(
                this,
                "确认是你",
                "打开她的房间前先验证一次",
                onPass = { uncoverGate() },
                onFail = { finish() },
            )
        } else {
            // ★★ 2026-11-08：**已经不用问了，那黑底就必须撤。**
            //
            //   这块底原来是「只在 onPass 里撤」的。只要有一轮没走到 onPass，
            //   它就会一直挂在房间上；而它跟房间底色同一个色值，看上去就是
            //   「她的房间没了」。任何一次回到前台都是一次免费的自愈机会：
            //   进来说明此刻不需要验，那就不该有东西挡着。
            //   （重复调用安全 —— 没盖就什么都不做，见 [uncoverGate]。）
            uncoverGate()
        }
        // ★★ 房间在前台 = **屏幕不睡**。用户 2026-10-04 晚点名要的:
        //   > 「还有就是息屏,能不能**常驻不息屏**如果开在前台就不息屏」
        //   ★ 挂在这一对生命周期上而不是挂在她「在听 / 在说」上,是她自己挑的判据
        //     (他说的就是「开在前台」)—— 而且这一条**天然是对的**:
        //     标志绑在窗口上,窗口一不可见系统就把它收回,不会出现「退到后台还亮着屏」。
        //     见 [keepScreenOn]。
        keepScreenOn(true)
        // ★ 房间在屏幕上 = 她需要弹系统权限框时**有地方弹**（写系统日历，见 [CalHand]）。
        //   挂弱引用：房间是 singleTask 常驻的，强引用挂在 object 上等于把它钉死。
        SelfHand.hostActivity = java.lang.ref.WeakReference(this)
        // ★ 2026-11-07：让她能「看照片」「读表格」—— 弹系统选择器这件事只有界面能办
        //   （见 SelfHand.picker）。
        SelfHand.picker = ::pickFileBlocking
        // ★ 告诉「手机自己」那只手:她现在在屏幕上。系统只在 App 有前台界面时
        // 才允许从后台拉起 Activity(否则**静默不弹**,不报错)。回执要靠这个标志
        // 决定说「打开了」还是「可能弹不出来」—— 见 SelfHand.start。
        SelfHand.visible = true
        // ★★ 房间在前台 = **麦克风该归她的耳朵**。唤醒词那一头必须让开,立刻让 ——
        //   见 [WakeWordManager.roomInFront]。麦只有一个:她在这儿的时候点 🎤
        //   要能马上拿到,不能先撞一句「唤醒 正用着麦克风」。
        //   ★ 这一句不是可有可无的礼貌:少了它,他进屋按 🎤 会随机失败几秒,
        //     而那个失败看起来像「她坏了」。
        WakeWordManager.roomInFront = true
        // ★★ 同一次对表里还要把「唤醒」那个**麦克风型前台服务**重新锚住 ——
        //   前台服务只能从前台上下文启动,而这里(她进房间了)是全项目唯一那个时刻。
        //   没有这一句:重启手机之后那个开关会永远停在「开」而实际什么都不做,
        //   而且**一点声音都没有**(后台拿到的全是静音,见 [WakeService] 文件头)。
        //   ★ 顺序在 `roomInFront = true` 之后 —— 房间开着的时候监听本来就该让给耳朵,
        //     这里要的只是「服务挂上、对表转起来」。
        try { WakeWordManager.applySetting(this) } catch (_: Exception) {}
        webView?.onResume()
        // ★ 她自己就在满屏上,那颗球收起来(否则她旁边还浮着一颗自己的头,
        //   而且会挡住场景 —— 房间里那台电脑现在是要点得中的)。
        //   出去时 onPause 放回去,见 ConMarnBubble.setRoomInFront。
        try { ConMarnBubble.setRoomInFront(this, true) } catch (_: Exception) {}
        // ★ 回到房间 = 一个**明确的「线该是通的」时刻**,顺手补一次连接。
        //
        //   为什么需要:ColorOS 在息屏那一刻会把整个 App 冻起来(`OplusHansManager
        //   freeze … scene: |StrictMode-1|LcdOff`),socket 跟着断;亮屏解冻之后
        //   读循环**要等下一次心跳失败**才发现线没了,这中间房间上的那个点就是
        //   「没连上」—— 用户看到的就是「退出去房间就显示电脑断连」。
        //   这一句让「你回来看她」这个动作本身就触发一次续连,不用等。
        //
        //   ★ 安全的理由写在 [PcLink.connectLast] 里:**已经连着就直接返回 false**,
        //     而且 6 秒内只发起一次 —— 所以它不会和上面那些自动续连打架,
        //     也不会在房间来回切的时候把连接打成抖动。
        PcLink.connectLast(this)
        // ★ 回到房间 = 两块信息屏立刻对一次表（热点可能已经变了；「今日」那一块
        //   要重算「现在还剩哪几条」）。见 [RoomPanels.poke]。
        roomPanels?.poke()
        // 窗外那片景也立刻对一次表(理由同 [pushSceneWeather] 里 `force` 那段)。
        pushSceneWeather(force = true)
        // ★★ 2026-11-08(体检 E1)：盘上有开着的定时任务、而通知权限还没给 —— 在这儿要一次。
        //   任务是他**之前**用对话定下的（那时可能还没有权限），所以这条不能只挂在
        //   创建那一刻；回房间也是一个自然的时机。见 [askTaskNotifIfNeeded]。
        askTaskNotifIfNeeded()
        refreshConnDot()
        root.removeCallbacks(connDotTick)
        root.postDelayed(connDotTick, 2000L)
        // ★★ 失败之后要能自己重来 —— 2026-10-04 真机踩出来的第二个 bug。
        //   她现在是 `singleTask`,**再点桌面图标走的是 onNewIntent + onResume,不走 onCreate**,
        //   所以「脑起挂了」之后 `HerBoot.ensure()` 再也不会被调用:她永远哑着,
        //   界面一切正常、也不报错,用户只能去系统设置里「强制停止」才救得回来。
        //   实测复现路径:`adb install -r` 之后脑必挂一次(见 ModelManager.EARLY_EXIT_MS),
        //   点图标 → 日志一行都不动。
        //   ⚠️ 只救 FAILED,**不碰 STOPPED** —— 那是用户自己在设置里关的,不能替他打开。
        if (ModelManager.get(this).state == ModelManager.State.FAILED) {
            ModelManager.get(this).trace("房间回到前台:上次脑是 FAILED,重新拉一次")
            HerBoot.ensure(this)
        }
        refreshSettingsBtn()
        // ★ OAuth 刚回来：面板要跳回刚才授权的那一条。
        //   那一刻用户是**从浏览器切回来**的，脑子里想的是「授权成了没有」——
        //   什么都不弹、只留一个 Toast，等于让他自己再去三层菜单里找一遍。
        OAuthClient.takeReopen()?.let { pid ->
            closeGlassAll()
            showApiPurpose(pid)
        }
        // ★ 回到房间时**收掉打字那一态**:上次退出时键盘可能还开着,
        //   而 `singleTask` 的 Activity 是**同一个实例**,状态原样留着 ——
        //   他不做任何事再进来,看到的会是一个空输入框而不是「按住说话」。
        if (typing) setTypingMode(false)
        // ★ 进房间就把嗓子热上。她进来说的第一句往往是「你回来了」这种**立刻**要出口的话,
        //   等它到跟前才 initTts() 已经晚了 —— TTS 的 onInit 是异步回调,
        //   而这正是原来「第一次开口没有声音」的成因(见 pendingSpeech 的注释)。
        //   现在这行让「引擎起不来」这件事**在进房间那一刻就暴露**,而不是等他发现她不响。
        if (ttsOn) initTts()
        // ★★ 内嵌那个**真嗓子**也要在进房间时就热上,理由和上面那句一模一样,
        //   只是代价大了两个数量级:eula 是个 **116MB** 的 onnx,
        //   冷装载按耳朵那边的实测口径要秒级(耳朵 239MB 是 3047ms)。
        //   不预热的话表现是「她进来说的第一句还是系统那个英文嗓子念的」,
        //   而那**听起来完全正常** —— 只是调是错的,最难查的一类。
        //   幂等:已经在装 / 装好了 / 装失败了都直接返回(见 [SherpaVoice.warmUp])。
        SherpaVoice.warmUp(this)
        // ★ 回到房间时对一次账:手上可能**多了一只**。
        //   最常见的路是「点房间里的电脑 → 连上 → 回来」,那一刻电脑那只手才第一次
        //   出现在名单里。不对账的话,房间里那台电脑要等她下次重建 WebView 才亮起来
        //   —— 而用户刚把它连上,心里想的是「现在能点了吧」。清单没变就什么都不做。
        pushRoomObjects(force = false)
        // 她的房间开着 = 她「在」。主动开口说进这里(字幕 + 念出来)。
        ProactiveGreeting.enter(SCREEN)
        ProactiveGreeting.bindRoom(this)
        ProactiveGreeting.applySetting(this)
        // 回到房间 = 把注意力放回他身上
        her("setPresence(1)")
        // ★★ 进房间就把「按住说话,点一下打字」立起来。
        //
        //   这一句是**这一版最要紧的一行**,因为它修的不是外观而是**可达性**:
        //   在一个更早的版本里,说话的路是 `inputRow` 里那个 🎤 按钮,而
        //   `inputRow` 默认 GONE、六秒还会自己收 —— 也就是说**「跟她说话」
        //   这件事本来是藏起来的**。他找不到它,报告出来就是
        //   「**语音识别听不到我说话**」。
        //
        //   现在说话就是**按那一整块玻璃**,而那行字就印在玻璃上:
        //   不用找,不用先打开什么。★ 这也是这一版把提示字从天上挪到胶囊里的原因:
        //   **提示要长在你该碰的东西上,不是长在你该看的地方。**
        setCaptionPlaceholder(HINT_HOLD)
        // ★ 她的房间在前台 → 把右缘那条书签条**藏起来**(不是销毁)。
        //   房间是全屏横屏 3D,那块 12dp 宽的条子会吃掉 WebGL 的一格手势 ——
        //   而这一版正在做的就是「房间里的物件要能点到」。见 StationOverlay.setHandleVisible。
        try { StationHolder.setHandleVisible(applicationContext, false) } catch (_: Exception) {}
        // ★ 静止态**不给光晕**(VOICE_OFF),只留胶囊本身。
        //   一直亮着呼吸的话,「它亮着」就不再意味着「她在听」或者「她在说」——
        //   一个永远亮着的灯等于没有灯,而这一版全靠它说真话。
        setVoiceState(VOICE_OFF)
        // ★ 悬浮窗那颗球上的 🎤 进来的话,**替他按完那一下** —— 见 [pendingVoiceSession]。
        //   放在最后一行是有意的:上面那些(setCaptionPlaceholder / StationHolder /
        //   setVoiceState)都是「房间刚开」的静止态,而 [toggleEar] 要覆盖它们
        //   —— 顺序反了的话,他会看到胶囊先立起来、再被复位成静止态,**然后才**开始听,
        //   中间那一闪看着像「按了没反应,又自己好了」。
        if (pendingVoiceSession) {
            pendingVoiceSession = false
            toggleEar()
        }
    }

    /**
     * 转屏。
     *
     * ★ 这个 Activity 在清单里声明了 `configChanges="orientation|screenSize"`，
     *   也就是说**转屏不会重建**，onCreate 不会再走一遍。而两块信息屏的尺寸是
     *   **按屏幕比例算的**（[RoomPanels.side]）—— 不在这里重算的话，横屏会继续挂着
     *   竖屏那套尺寸，反过来一样。所以他 2026-11-07 说「占屏有点大」时看到的那一版，
     *   就是横屏套着竖屏的数字。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 重建会重取一次热点；这点开销比「横屏时两块屏盖住半个屏幕」便宜得多
        rebuildRoomPanels()
    }

    override fun onPause() {
        // ★ 出了前台就把「别息屏」放掉 —— 和 [onResume] 里那一句是一对。
        //   窗口标志本来就会随窗口不可见自动失效,这一行是**把话说清楚**:
        //   电池是用户的,不该由一条「反正系统会收回」的默契来保护。
        keepScreenOn(false)
        // ★ 她退到后台,**麦克风立刻关**。这条是隐私线上写着的那一条
        //   (「房间不在前台时麦克风必须是关的」),不是一个可以商量的优化:
        //   他按下 🎤 之后切走 App,耳朵不该继续听。
        //   Ear.cancel() 只是放下标志,真正松手在 ear 那块线程的 finally 里 ——
        //   所以这里不会卡住主线程。
        //
        // ★ 2026-10-04 晚:在对话里的话要**整个收工**,不只是掐掉这一句 ——
        //   只掐一句的话,排着的那次 `resumeListening` 还在,她会在后台自己把麦开回来。
        //   那正是隐私线上写着的那一条(「房间不在前台时麦克风必须是关的」),
        //   而且它**不留任何界面痕迹**:人切走了,只有麦克风还开着。
        if (voiceSession) endVoiceSession(null) else Ear.cancel()

        // ★ 那条水波也必须收掉,而且**理由和上面麦克风那条是同一条** ——
        //   [VoiceWaveView] 在画面上说的那句话是「麦克风开着」。麦克风关了她还留着,
        //   那就是界面在替我们撒谎(「开着麦必须看得见」反过来同样成立:
        //   **看不见了就不许显示开着麦**)。而且它 `running` 为真会一直排帧 ——
        //   一个后台还在烧电的动效,日志里一点痕迹都没有。
        hideWave()

        // 那盏灯的轮询也跟着停 —— 她不在画面上,没人看它,别白烧每两秒一次的主线程消息。
        root.removeCallbacks(connDotTick)

        // ★ 出了前台，两块信息屏的定时器和翻页动画一起停 ——
        //   一个切到后台还在每十分钟出去取三次热点的线程，日志上一点痕迹都没有。
        roomPanels?.stop()
        // 她不在画面里了,视线就该散开 —— 不要假装还在看他
        SelfHand.visible = false
        SelfHand.voice = null      // 房间关了,嗓子也收回来(见 SelfHand.say)
        // ★ 房间不在前台了,就没有「能弹系统权限框的界面」了 —— 摘掉手里的这个引用
        //   (见 [SelfHand.hostActivity]:写系统日历时要靠它弹那个框)。
        SelfHand.hostActivity = null
        // 房间不在前台了，也弹不出选择器（见 SelfHand.picker）。
        // ★ 正在等照片的那次调用**不受影响**：它手里的队列是局部变量，
        //   这个字段只是「下一次还让不让挑」的闸。
        SelfHand.picker = null
        // ★★ 出了房间 → **唤醒词可以把麦克风拿回去了**。和 [onResume] 那一句是一对。
        //   上面刚 `endVoiceSession` / `Ear.cancel()`,耳朵正在松手;唤醒那一头
        //   由它自己的对表在 2 秒内挂回去(见 [WakeWordManager] 的 poll)。
        WakeWordManager.roomInFront = false
        // 出了房间 → 她的球放回来(她该陪在屏幕边上;关没关由 conmarn_bubble/enabled 说了算)
        try { ConMarnBubble.setRoomInFront(this, false) } catch (_: Exception) {}
        // ★★ 出了房间 → **右缘那条书签条放回来**。这一行是用户那句
        //   「确实有书签也确实能回到房间,但是**好像点一下就没了**」的正面回答:
        //   书签条现在住在进程上([StationOverlay]),进房间时被系统销毁的那个
        //   MainActivity 再也带不走它;房间让位的时候它自己回来。
        //   ★ 用 `visible=true` 而不是「恢复原状」:冷启动直接进房间那条路上
        //     它**从来没被建过**,「恢复」在那种情况下等于什么都不做。
        try { StationHolder.setHandleVisible(applicationContext, true) } catch (_: Exception) {}
        her("setPresence(0)")
        her("setListening(false)")
        ProactiveGreeting.unbindRoom(this)
        ProactiveGreeting.exit(SCREEN)
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        // 拍图那一发也要摘掉:它捕获了 `this`,而 `root.postDelayed` 是**进程级**的
        // 消息队列 —— 不摘的话她退场之后还会被叫醒一次,去访问一个已经 destroy 的 WebView。
        root.removeCallbacks(figureShot)
        // 两块信息屏的定时器也在这里摘干净（同一个理由：它们捕获了 `this`）。
        roomPanels?.stop()
        roomPanels = null
        // 排队等着的字幕也跟着走 —— 她退场之后它再冒出来,是往一个不存在的房间里写字。
        cancelDeferredCaption()
        // ★ 这里原来还有一句 `recognizer?.destroy()` —— 系统识别器那条路
        //   2026-10-04 晚整个删了(见「打字那一态」那段),所以它也跟着走了。
        tts?.shutdown(); tts = null
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    // ==================================================================
    // 双指滑动（2026-11-05）
    // ==================================================================

    /** 双指按下时的那一点，以及这一下还作不作数。 */
    private var swipeX = 0f
    private var swipeY = 0f
    private var swipeArmed = false
    private var swipeT0 = 0L

    /**
     * **双指右滑 → 超级工作台。**
     *
     * ## 为什么用双指，不用一颗按钮
     *
     * 他 2026-11-05 的原话是：房间里 🔍 旁边加个方块也行，**但更中意双指滑动** ——
     * 「因为万一以后有啥外设，也可以用这种形式调出界面」。所以这里做成手势：
     * 外设只要能发两个触点在屏幕上横着走，就能把工作台叫出来，不需要它认识任何图标。
     *
     * ## 三条边界
     *
     * 1. **必须是真的两根手指**：一根手指的横划是房间里本来就有的交互（转身、拖视角），
     *    不能被这里截走。所以只在 `pointerCount >= 2` 的时候才开始记账。
     * 2. **够快、够横**：横过屏宽四分之一、竖着别跑太远、900 毫秒内完成。
     *    慢吞吞地拖通常是在拖视角，不是在切界面。
     * 3. **大屏开着的时候不认**：那一层是盖满屏的，手势该归它自己。
     *
     * ★ 这里**不消费**事件（除了真触发那一下）：没触发就照常传给下面，
     *   不然房间里的双指缩放之类的就废了。
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (flowLayer == null && dashLayer == null) {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_POINTER_DOWN ->
                    if (ev.pointerCount >= 2) {
                        swipeX = ev.getX(0)
                        swipeY = ev.getY(0)
                        swipeArmed = true
                        swipeT0 = System.currentTimeMillis()
                    }
                android.view.MotionEvent.ACTION_MOVE ->
                    if (swipeArmed && ev.pointerCount >= 2) {
                        val dx = ev.getX(0) - swipeX
                        val dy = ev.getY(0) - swipeY
                        val w = resources.displayMetrics.widthPixels
                        val h = resources.displayMetrics.heightPixels
                        // ★ 右滑＝去工作台。方向要和「在工作台里左滑回房间」对得上；
                        //   两边反着做的话，同一个手势在两屏上是两个意思。
                        if (dx > w * 0.26f && Math.abs(dy) < h * 0.22f &&
                            System.currentTimeMillis() - swipeT0 < 900
                        ) {
                            swipeArmed = false
                            openWorkbench()
                            return true
                        }
                        // ★ 双指**下划**＝看板模式（方向是他 2026-11-07 当场定的：
                        //   「双指往下划是进，往上划是出」）。
                        //   和右滑是同一套方向逻辑：横着去工作台，竖着去看板；
                        //   各自反方向就是回退。互不抢：一个要求横向位移占绝对多数，
                        //   一个要求竖向占绝对多数，中间那一块两边都不算。
                        if (dy > h * 0.26f && Math.abs(dx) < w * 0.24f &&
                            System.currentTimeMillis() - swipeT0 < 900
                        ) {
                            swipeArmed = false
                            openDashboard()
                            return true
                        }
                    }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> swipeArmed = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ==================================================================
    // 看板模式（2026-11-07）
    // ==================================================================

    /** 现在挂着的看板层。非 null = 开着（返回键先关它）。 */
    private var dashLayer: DashboardLayer? = null

    /**
     * 叫出看板。
     *
     * ★ 和协同大屏一样是**覆盖层**，不是新 Activity —— 他要的是「页面往下」的手感，
     *   跳页面会把这一段连续感切碎。
     */
    private fun openDashboard() {
        if (dashLayer != null) return
        val layer = DashboardLayer(this) { closeDashboard() }
        root.addView(layer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        dashLayer = layer
        layer.start()
    }

    private fun closeDashboard() {
        val v = dashLayer ?: return
        dashLayer = null
        v.stop()
        root.removeView(v)
    }

    /** 开超级工作台。★ 它和她**不在同一个界面里**，是另一个 Activity。 */
    private fun openWorkbench() {
        try {
            startActivity(Intent(this, WorkbenchActivity::class.java))
        } catch (e: Exception) {
            toast("工作台打不开:" + (e.message ?: e.javaClass.simpleName))
        }
    }

    override fun onBackPressed() {
        // ★ 2026-11-04:协同大屏开着的时候,返回键先关它 —— 那是一块**盖满屏**的层,
        //   直接退到桌面会让人以为「大屏把我 App 弄退了」。
        if (dashLayer != null) {
            closeDashboard()
            return
        }
        if (flowLayer != null) {
            closeFlowPanel()
            return
        }
        // ★ 2026-10-04 晚:这里原来是「控件露着就先收起控件,再按才退出」。
        //   控件层整个删掉之后(见文件上半部分那段),**返回键就是退出**,一步到底。
        //   这是有意收窄的:留着旧写法的话,他要按两次才能走,而第一次按
        //   **屏幕上什么都不会发生** —— 那正是「按了没反应」。
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ==================================================================
    // 连接那盏灯 + ⚙ 里的那几个开关
    // ==================================================================

    /** 上一次那盏灯是什么颜色。**用它避免每两秒重设一次背景** —— 那会白白造 drawable。 */
    private var connDotOn: Boolean? = null

    /**
     * 亮一下那盏灯。**这是唯一改 [connDot] 的地方。**
     *
     * ★ 绿 = 连上电脑,红 = 没连 —— 用户点名要的语义,别换颜色。
     * ★ 用的是和 `MainActivity` 那两颗指示同一个 drawable,别自己画一个
     *   (同一件事在两个页面上长得不一样,是那种「看着别扭但说不上哪里不对」的坏)。
     */
    private fun refreshConnDot() {
        val on = TouchpadClient.get(this).isConnected()
        if (on == connDotOn) return          // 没变就别动它,见上面那个字段
        connDotOn = on
        connDot.background = getDrawable(if (on) R.drawable.dot_green else R.drawable.dot_red)
    }

    /**
     * 那盏灯得**自己会变**。
     *
     * ★ 只在 onResume 对一次账是不够的:电脑可能在她眼皮底下掉线(电脑睡了、
     *   Wi-Fi 换了、pc-server 重启),而他正盯着那盏灯找答案。
     * ★ 用轮询而不是去挂 `TouchpadClient` 的那个 listener:listener 是**单槽**的,
     *   `MainActivity` 在用;两个人抢一个槽,后设的把先设的顶掉 ——
     *   那正是「主界面突然不响应了」这种最难查的坏法。两秒读一个布尔值不值一提。
     */
    private val connDotTick = object : Runnable {
        override fun run() {
            refreshConnDot()
            // 窗外那片景。★ 搭这趟车而不是另起一个定时器：这盏灯本来就在两秒一转，
            //   再多一条 postDelayed 就是再多一处忘了摘的隐患（他离场时只摘得掉已知的那几个）。
            pushSceneWeather()
            if (!isFinishing) root.postDelayed(this, 2000L)
        }
    }

    /**
     * 唤醒词那一行/那句话现在该说什么。
     *
     * ★★ 这一版的判据换过了,写清楚免得以后有人绕回去。
     *
     *   原来 `available()` 问的是「**系统里有没有那个语音识别服务**」——
     *   而 ColorOS 上**一个 `RecognitionService` 都没有**,所以它恒为「还没装」,
     *   那个开关从写下那天起**一次都没工作过**。
     *
     *   现在 `available()` 问的是「**模型文件在不在**」—— 这是一个能被修好的问题,
     *   而且修好的动作就是「把文件放进去」(见 [WakeWordManager 的文件头])。
     *
     * ★ 「还没装」这三个字留着,但它现在**指真的缺文件**,不再是一句
     *   「这台机器做不到」的托词。缺哪几个会写进 `耳:` 日志。
     */
    private fun wakeState(): String = when {
        !WakeWordManager.available(this) -> "未就绪"
        WakeWordManager.isEnabled(this) -> "已开启"
        else -> "已关闭"
    }

    private fun toggleWake() {
        if (!WakeWordManager.available(this)) {
            toast(
                "语音唤醒组件未安装：模型文件尚未放置到本机。\n" +
                    "当前可通过输入框旁的麦克风按钮发起语音输入。"
            )
            return
        }
        if (WakeWordManager.isEnabled(this)) {
            WakeWordManager.setEnabled(this, false)
            toast("语音唤醒已关闭，麦克风不再监听。")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            WakeWordManager.setEnabled(this, true)
            // ★ 唤醒词是「从漫」,不是英文那个 —— 2026-10-06 他改的。
            //   toast 里那两个字要跟着它;写错了他会照着念,而**喊不中是不报错的**
            //   (见 WakeWordManager 文件头那条)。
            // ★★ 后半句是 2026-10-08 加的:开了之后**通知栏会多一条常驻的**。
            //   不说,他会以为那是他装的其他 App 或者什么推送,顺手划掉。
            toast("语音唤醒已开启。唤醒词为「从漫」；通知栏中的「语音唤醒」为常驻通知，请保留。")
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_WAKE)
        }
    }

    /**
     * 「打断」开关(见 [BargeIn])。
     *
     * ★ 不支持回声消除时**开不了**,而且要把原因说出来 —— 这里静默拒绝的话,
     *   他会一直点同一个开关,以为是自己点错了地方。
     */
    private fun toggleBarge() {
        if (!BargeIn.available()) {
            toast(BargeIn.whyOff())
            return
        }
        val on = !BargeIn.isOn(this)
        BargeIn.setOn(this, on)
        toast(
            if (on) "已开启：播报期间可直接开口，系统将立即停止播报并开始聆听。"
            else "已关闭：播报期间麦克风保持关闭。"
        )
    }

    private fun toggleGreet() {
        val on = !ProactiveGreeting.isEnabled(this)
        ProactiveGreeting.setEnabled(this, on)
        toast(
            if (on) "已开启：静默 20 分钟以上且角色在线时，可能主动发起一次对话。\n" +
                "如需避免打扰，请开启「免打扰」。"
            else "已关闭：角色不再主动发起对话。"
        )
    }

    // ------------------------------------------------------------------
    // ⚙ 设置(「她一直在」+ 免打扰时段)
    // ------------------------------------------------------------------

    /**
     * ⚙ 上带个点:有东西开着的时候一眼看得出来,不用点进去找。
     *
     * ★ 2026-10-04:原来还把 `ProactiveGreeting.isQuietOn(this)` 算进来,而免打扰的默认值是
     *   **true** —— 于是这颗点**从装上那天起就一直亮着**,永远不变。
     *   一个永远亮着的点不传递任何信息,只是让人以为「哪里出问题了」。
     *
     * 判据改成:**只报「不是默认状态」的那一项**。免打扰开着是默认,它不在名单里;
     * 「一直在」默认是 **false**(`HerLife.isAlive`),他一旦开了就该看得见。
     * 换句话说 —— 这颗点回答的是「我改过什么」,不是「什么开着」。
     */
    private fun refreshSettingsBtn() {
        // ★ 2026-10-25:「不用时关模型」默认关,他一旦开也是**改过什么**,同一把尺子。
        val changed = HerLife.isAlive(this) || BrainIdle.isOn(this)
        settingsBtn.text = if (changed) "⚙·" else "⚙"
    }

    /**
     * 能不能发通知。**Android 13+ 这是运行时权限,清单里声明了不算数** ——
     * 没给的时候 `startForeground()` 照样成功,但那条通知**不会出现在通知栏**,
     * 所以「一直在」看起来生效了、「让她睡」却根本够不着。见 [toggleAlive]。
     */
    private fun canPostNotifications(): Boolean = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 这一进程里问过一次就不再烦他 —— 见 [askTaskNotifIfNeeded]。 */
    private var askedTaskNotif = false

    /**
     * ★★ 2026-11-08(体检 E1):**定了定时任务,就得先把通知权限要下来。**
     *
     * 原来只在一个地方请求过通知权限 ——「让她一直在」那个开关([toggleAlive])。
     * 可定时任务是另一条路:他在对话里说「每天八点……」,工具把它存下来、闹钟排上,
     * 然后**到点跑完只发通知**。没权限的时候 `TaskNotice.result` 会静默失败
     * (通知根本不出现在通知栏),症状是「它到底跑没跑?」—— 结果写在那儿,没人看得见。
     *
     * ★ 问的时机:一轮对话结束、或者房间回到前台,并且**盘上确实有开着的任务**。
     *   没有任务的时候一个问题都不问(不能一进 App 就弹冷脸)。
     * ★ 只问一次:拒绝了还反复弹,是把人往设置里逼,不是帮忙。
     */
    private fun askTaskNotifIfNeeded() {
        if (askedTaskNotif || canPostNotifications()) return
        TaskStore.init(this)
        if (TaskStore.all().none { it.enabled }) return
        askedTaskNotif = true
        toast("定时的结果只能靠通知送到你手上 —— 先给一下通知权限")
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
    }

    /**
     * 云端兜底的开关。**只拨开关,不碰 key** —— 这正是它和「把 key 删空」的区别:
     * 关掉之后想再开,拨回来就行,不用把那个 key 找回来重填一遍。
     *
     * ★ 改完**必须落盘**([AiAgent.saveConfig])。`agent().config` 是内存里那一份的**引用**,
     *   改它当场就生效 —— 但也**只**在这一个进程里生效。不落盘的话关掉 App 再打开,
     *   它自己就回来了,而那个症状**看起来像「我没动过」**(同 `ProactiveGreeting` 那笔
     *   「粉笔写的牌子」的账)。
     *
     * ★ 不重启任何东西:[AiAgent.chat] 每一轮都现读 `config.cloudOn`,下一句话就按新状态走。
     */
    private fun toggleCloud() {
        val c = agent().config
        c.cloudOn = !c.cloudOn
        AiAgent.saveConfig(this, c)
        toast(
            if (c.cloudOn) "云端总闸:已开启"
            // ★ 关掉之后**所有**出网都停(见 [CloudNet]),不只是她的模型调用。
            //   本机和局域网不受影响 —— 这句话必须跟着一起说,否则「关闸」听起来
            //   像是「连家里那台电脑也不能用了」。
            else "云端总闸:已关闭，所有对外请求已停（本机与局域网不受影响）"
        )
    }

    /**
     * 「云端总闸」点进去的那一页：**开关和密钥放在同一处**。
     *
     * ★ 2026-11-08 他的原话：「能不能在云端总闸那点一下，填 key？」
     *   原来那一行点一下只翻转开关，填密钥得绕去触控板那页的老弹窗（那边才有一个
     *   能粘贴的输入框）。可这两件事本来就是一件事 —— 一个管「出不出手机」，
     *   一个管「以谁的身份出去」。分在两处的结果就是他填完密钥回来，还得再找一次开关。
     */
    private fun showCloudPanel() {
        val box = column()
        var sw: LinearLayout? = null
        var key: LinearLayout? = null

        fun refresh() {
            val c = agent().config
            sw?.let {
                setRow(
                    it, "云端总闸:" + if (c.cloudOn) "已开启" else "已关闭",
                    if (c.cloudOn) "" else "所有出网已停 · 本机与局域网除外"
                )
            }
            key?.let {
                val own = c.cloudApiKey.isNotEmpty()
                val viaApi = c.endpoints.any { e -> RouteMath.viable(e) }
                setRow(
                    it,
                    "云端密钥:" + when {
                        own -> "已配置"
                        viaApi -> "由「模型与接口」提供"
                        else -> "未配置"
                    },
                    if (own) "点这里更换或清空" else "点这里填写"
                )
            }
        }

        val r1 = settingRow("云端总闸", "", {})
        r1.setOnClickListener { toggleCloud(); refresh() }
        sw = r1
        box.addView(r1, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val r2 = settingRow("云端密钥", "", {})
        r2.setOnClickListener { showCloudKeyDialog { refresh() } }
        key = r2
        box.addView(r2, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        box.addView(hint(
            "总闸管「出不出手机」：关着的时候天气、新闻、网页读取、图片生成、接口调用全部停，" +
                "本机模型与局域网里的电脑照常使用。\n" +
                "密钥管「以谁的身份出去」：只存在本机，卸载即丢。它和总闸是两把锁，" +
                "两个都满足，请求才会发出。\n" +
                "也可以在「模型与接口」里按用途分别配置；那边配好了，这一格可以留空。"
        ))

        refresh()
        pageDialog("云端总闸", box)
    }

    /**
     * 填或更换云端密钥。
     *
     * ★ 带「粘贴」：key 又长又随机，在手机上纯手打基本打不对 —— 触控板那页的老弹窗
     *   早就为这件事挂了一颗粘贴键，这边不能反而没有。
     */
    private fun showCloudKeyDialog(after: () -> Unit) {
        val c = agent().config
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        val input = EditText(this).apply {
            setSingleLine()
            setText(c.cloudApiKey)
            setSelection(text.length)
            setTextColor(getColor(R.color.glass_text))
            setHintTextColor(getColor(R.color.glass_dim))
            hint = "sk-…"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            herButton("粘贴", 13f) {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                val s = cm.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(this@ConMarnActivity)?.toString()?.trim().orEmpty()
                if (s.isEmpty()) {
                    toast("剪贴板里没有文字")
                } else {
                    input.setText(s)
                    input.setSelection(s.length)
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // ★ `show()` 必须接在 **Builder** 上（它返回 AlertDialog）；接在 create() 之后
        //   拿到的是 Unit，那就 `.glassified()` 不上了 —— 这里踩过一次。
        val d = glassBuilder("云端密钥")
            .setMessage("只存在本机。填完记得把总闸打开，请求才会出去；留空 = 不用兜底。")
            .setView(row)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .show()
            .glassified()
        // ★ 自己接管「保存」那一颗（所以上面传 null）：默认那颗**先关窗再回调**，
        //   保存还要写盘，得等写完了再关。
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val v = input.text.toString().trim()
            // ★ 改内存里那一份（下一句就生效）+ 落盘（不落盘的话关掉 App
            //   再打开它自己就回来了 —— 和 [toggleCloud] 同一条道理）。
            c.cloudApiKey = v
            AiAgent.saveConfig(this@ConMarnActivity, c)
            d.dismiss()
            toast(if (v.isEmpty()) "云端密钥:已清空" else "云端密钥:已保存")
            after()
        }
    }

    /**
     * 前缀快照的预热：**自动挑时间 + 手动立刻算，手动优先**。
     *
     * ★ 2026-11-08 他的原话：「设置里加一个前缀快照预热的设置选项，我想手动和自动
     *   识别环境是否可以加载，手动更优先」。
     *
     * 四行，各管一件事：
     *  - 「当前快照」：现在这份提示词/工具表/模型对应的快照在不在盘上、多少 token、多大；
     *  - 「自动预热」：开着时开机后由 [HerBoot] 挑凉的时候算（关掉 = 只还原、不冷算，
     *    见 [AiAgent.warmUpOnce] 里那道闸）；他 2026-11-08 要的「自动识别环境」就是这一行
     *    下面那行「环境」报的读数；
     *  - 「环境」：电池温度 + 插没插电 + 自动那一路现在能不能开始；
     *  - 「现在就算一次」：**跳过温度闸**立刻算 —— [AiAgent.warmUp] 的 manual 那一路。
     *
     * ★ 为什么手动不受温度限制：闸的职责是替**自动**那一路挑时间，不是替他做决定。
     *   代价照实写在小字里 —— 热着算实测约 3 token/秒，可能撞 60 分钟超时。
     */
    private fun showPrefillPanel() {
        val box = column()
        var statusRow: LinearLayout? = null
        var autoRow: LinearLayout? = null
        var envRow: LinearLayout? = null
        var runRow: LinearLayout? = null

        fun fmtMb(b: Long): String =
            String.format(java.util.Locale.US, "%.0f MB", b / 1024.0 / 1024.0)

        fun refresh() {
            val a = agent()
            val st = a.snapshotStatus()
            statusRow?.let {
                setRow(
                    it,
                    "当前快照:" + when {
                        st == null -> "还没有"
                        st.tokens <= 0 -> "不可用"
                        else -> "已就绪"
                    },
                    when {
                        st == null -> "生成之前，每次开机都要冷算一遍前缀"
                        st.tokens <= 0 -> "盘上有文件但记录对不上，下次仍会重算"
                        else -> "${st.tokens} token · ${fmtMb(st.bytes)} · " +
                            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(st.atMs)) + " 存的"
                    })
            }
            autoRow?.let {
                setRow(it, "自动预热:" + if (a.config.prefillAuto) "已开启" else "已关闭", "")
            }
            envRow?.let {
                val t = PrefillGate.batteryTempC(this)
                val ch = PrefillGate.charging(this)
                val ok = PrefillMath.shouldStart(t, ch)
                val cap = if (ch) PrefillMath.MAX_TEMP_CHARGING_C else PrefillMath.MAX_TEMP_BATTERY_C
                val read = when {
                    t == null -> "读不到电池温度"
                    else -> String.format(java.util.Locale.US, "%.1f°C", t) +
                        (if (ch) " · 插着电" else " · 没插电")
                }
                setRow(
                    it,
                    "环境:" + if (ok) "自动预热可以开始" else "自动预热要等一等",
                    "$read（自动那一路的门槛 " +
                        String.format(java.util.Locale.US, "%.1f", cap) + "°C）")
            }
            runRow?.let {
                setRow(
                    it, "现在就算一次",
                    if (a.prefillRunning()) "已经在算了，等它跑完" else "跳过温度限制，立刻开始"
                )
            }
        }

        val r1 = settingRow("当前快照", "", {})
        statusRow = r1
        box.addView(r1, fullRow())

        val r2 = settingRow("自动预热", "", {})
        r2.setOnClickListener {
            val c = agent().config
            c.prefillAuto = !c.prefillAuto
            // ★ 必须落盘：开关只在内存里改的话，关掉 App 再打开它自己就回来了。
            AiAgent.saveConfig(this, c)
            toast(if (c.prefillAuto) "自动预热:已开启" else "自动预热:已关闭 —— 只还原快照，不再自己冷算")
            refresh()
            if (c.prefillAuto) {
                // ★ 当场试一次自动那一路 —— 不然这个开关要等到下次开机才生效。
                //   温度不合适它会自己退回「等凉了再算」，不硬算（见 [AiAgent.warmUpOnce]）。
                Thread {
                    agent().warmUp(this, manual = false)
                    runOnUiThread { if (!isFinishing && !isDestroyed) refresh() }
                }.apply { isDaemon = true; name = "prefill-auto-on" }.start()
            }
        }
        autoRow = r2
        box.addView(r2, fullRow())

        val r3 = settingRow("环境", "", {})
        envRow = r3
        box.addView(r3, fullRow())

        val r4 = settingRow("现在就算一次", "", {})
        r4.setOnClickListener { confirmManualPrefill { refresh() } }
        runRow = r4
        box.addView(r4, fullRow())

        box.addView(hint(
            "快照是「系统提示 + 工具表」这截前缀算好的缓存，存一份在本机。有它，开机后第一次" +
                "开口不用再冷算十几分钟。只在本机算，不出网。\n" +
                "自动：开机后挑手机凉且插着电的时候算（插电不高于 38°C，没插电不高于 34°C）。" +
                "关掉之后只还原已有快照，不再自己冷算。\n" +
                "手动：不受温度限制，按了就立刻开始。手机热的时候会慢很多（实测约 3 token/秒），" +
                "也可能算到超时；超时不会存下半成品，下次还会重算。\n" +
                "一份快照约 300–400 MB，存在应用自己的目录里，卸载即清。提示词、工具表或模型" +
                "一变，旧快照自动作废并清掉。"
        ))

        refresh()
        pageDialog("前缀快照预热", box)
    }

    /**
     * 手动预热的确认与执行。**不阻塞界面**：算多久都在后台线程，完成后再回来报。
     *
     * ★ 确认框只写**这一刻要做的决定和代价**，原理都在 [showPrefillPanel] 那段小字里
     *   （说明文字进设置页，浮层里只放选项本身）。
     */
    private fun confirmManualPrefill(after: () -> Unit) {
        if (agent().prefillRunning()) {
            toast("已经在算了，等它跑完")
            return
        }
        val d = glassBuilder("现在就算一次")
            .setMessage(
                "会跳过温度限制立刻开始，可能持续十几分钟到一小时，期间手机明显发热。" +
                    "计算期间对话照常可用。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("开始", null)
            .show()
            .glassified()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            d.dismiss()
            toast("已开始：算完会提示，可以离开这一页")
            Thread {
                val t0 = System.currentTimeMillis()
                val why = agent().warmUp(this, manual = true)
                val cost = (System.currentTimeMillis() - t0) / 1000
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    toast(
                        if (why == null) "前缀快照:完成（$cost 秒）"
                        else "前缀快照:没算成（$cost 秒）—— $why"
                    )
                    after()
                }
            }.apply { isDaemon = true; name = "prefill-manual" }.start()
        }
    }

    /**
     * 重新录入唤醒词：**打字输入 + 念两遍校准**。实现见 [WakeEnroll]。
     *
     * ★ 说明为什么这么设计，全写给看这个界面的人：
     *   唤醒词只收汉字（模型按拼音切）；录的不是「你的声音」，而是**验这个模型
     *   能不能听到这个词** —— 所以「念两遍」是验货，不是训练。
     *   两遍都听到才存；一遍都听不到**不存**，盘上还是原来那一份。
     */
    private fun showWakeEnroll(after: () -> Unit) {
        val input = glassInput().apply {
            setText(WakeEnroll.currentWord(this@ConMarnActivity))
            setSelection(text.length)
        }
        glassBuilder("唤醒词")
            .setMessage(
                "输入要喊的词（2–4 个汉字）。\n" +
                    "下一步会让你念两遍：两遍都能被听到才算录好；" +
                    "一遍都听不到不会保存，保持原来的唤醒词。"
            )
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("开始校准", null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val w = input.text.toString().trim()
                        if (w.isEmpty()) {
                            toast("唤醒词不能是空的")
                            return@setOnClickListener
                        }
                        dismiss()
                        startWakeEnroll(w, after)
                    }
                }
            }
            .show()
    }

    /** 校准过程：一个不可取消的进度框，每一步都由 [WakeEnroll] 报上来。 */
    private fun startWakeEnroll(word: String, after: () -> Unit) {
        val stage = TextView(this).apply {
            setPadding(dp(20), dp(12), dp(20), dp(12))
            textSize = 15f
            text = "准备中…"
        }
        val d = AlertDialog.Builder(this)
            .setTitle("正在校准「$word」")
            .setView(stage)
            .setCancelable(false)
            .create()
        d.show()
        WakeEnroll.enroll(
            this,
            word,
            onStage = { s -> if (!isFinishing && d.isShowing) stage.text = s },
            onDone = { _, msg ->
                if (!isFinishing && d.isShowing) d.dismiss()
                toast(msg)
                after()
            },
        )
    }

    /**
     * 「云端只见代号」的开关(见 [AliasStore] / [AiAgent.Config.aliasOn])。
     *
     * ★ 和 [toggleCloud] 同一个形状:改内存里那一份(下一句就生效)+ 落盘
     *   (不落盘的话关掉 App 再打开它自己就回来了,而那个症状看起来像「我没动过」)。
     * ★ 关掉**不清表**:表是「那边见过的代号」,清了之后它记得的代号在本地就失忆了。
     *   要清是另一个动作,这儿不给。
     */
    private fun toggleAlias() {
        val c = agent().config
        c.aliasOn = !c.aliasOn
        AiAgent.saveConfig(this, c)
        toast(
            if (c.aliasOn) "云端代号化:已开启，发往云端的敏感值以稳定代号表示。"
            else "云端代号化:已关闭，敏感值按原文发往云端。"
        )
    }

    /**
     * 拨「开 App 要指纹」。
     *
     * ★ 拨到「开」而机器验不了时**不拦他**（开关照样存成开 —— 他以后录了指纹就直接生效），
     *   但当场说出来，否则下一版他会带着「我有指纹锁」的错觉去比赛。
     */
    private fun toggleGate() {
        val on = !AppGate.isOn(this)
        AppGate.setOn(this, on)
        if (on && !AppGate.available(this)) {
            toast("已开启，但本机当前不可用：未录入指纹且未设置锁屏密码，暂时无法拦截。")
            return
        }
        toast(
            if (on) "启动身份验证:已开启"
            else "启动身份验证:已关闭，持有本机者可直接进入。"
        )
    }

    /**
     * 协作档位转一格（自动 → 规划 → 逐步 → 自动）。
     *
     * ★ 开关放在**协同大屏顶栏**上，不是设置页里 —— 他 2026-11-05 的原话是
     *   「要么全部在协同大屏里开启多 agent 模式」。档位是**用的时候**才想拨的东西，
     *   藏进设置里等于每次拨一下都要退出去走四步。
     * ★ 说明文字仍然**只在设置页**（规矩见记忆 `all-explanations-live-in-settings`），
     *   这一屏上只出现档位本身那两个字的当前值。
     *
     * ★ 和 [toggleCloud] 同一个形状：改内存里那一份（下一句就生效）+ 落盘。
     *   内存里那份**只在 agent 已经建出来的时候**才存在 —— 面板可以先于她说第一句话打开，
     *   所以这里用 [AiAgentHolder.peek] 而不是 `get`：没人用她的时候，
     *   不该为了转一个档位把她连人设带手全都建出来。
     */
    private fun cycleAgentMode() {
        val live = AiAgentHolder.peek()
        val c = live?.config ?: AiAgent.loadConfig(this)
        c.agentMode = AgentMode.next(c.agentMode)
        live?.config = c
        AiAgent.saveConfig(this, c)
        toast("协作档位:${AgentMode.label(c.agentMode)}")
    }

    // ==================================================================
    // 协同大屏(2026-11-04)
    // ==================================================================

    /** 现在挂着的那一层大屏。非 null = 它开着(返回键先关它,见 [onBackPressed])。 */
    private var flowLayer: View? = null

    /** 大屏那张 WebView。
     *
     * ★ 单独留一个字段只为一件事：**关面板时要 dispose**。WebView 不会跟着父视图
     *   一起释放，不管的话它会带着一个页面挂在后台（和 [DashWeb] 头注释里那条同一个理由）。
     */
    private var flowWeb: DashWeb? = null

    /**
     * 全屏黑底覆盖层：顶栏（标题 / 协作档位 / 关闭）+ 一张按时间往下排的列表。
     *
     * ## 为什么是覆盖层,不是弹窗
     *
     * 这一屏的对比度全靠黑底。压在 ⚙ 用的那种七成白玻璃上,荧光绿会糊成一片浅灰,
     * 而且弹窗还会给它加一圈内边距,把内容挤小。覆盖层是**另一块屏**,这正是他要的
     * 「另外一个 UI 界面」。
     *
     * ## 它挂在哪一层
     *
     * 挂在 [root] 最上面,和 [overlay] 同一个高度。加的时候**不挡返回键** ——
     * 返回键由 [onBackPressed] 先问 [flowLayer]。
     */
    private fun openFlowPanel() {
        if (flowLayer != null) return

        val layer = FrameLayout(this).apply { setBackgroundColor(0xFF05080A.toInt()) }

        // ── 顶栏:现在**只有档位和关闭两颗**,浮在网页顶上 ──
        //
        // ★ 2026-11-05：原来这里还有一整套标题（「AGENT OPS / 调度中心」+ 一行副标题），
        //   而网页自己顶部又有一条「AGENT OPS / DISPATCH CENTER」—— **同一个屏上两条标题**，
        //   叠在一起，而且底下那条被压住不动。
        //   现在标题只留网页那一条（它已经做成吸顶的磨砂玻璃，见 dash.css 的 `#hdr`），
        //   这一条退成两**颗浮钮**，背景透明：内容翻上去的时候从它们下面过，
        //   而不是被一块黑板挡住 —— 这正是他要的「磨砂透明玻璃」。
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.END
            setPadding(dp(0), dp(6), dp(10), dp(2))
        }

        // ── 协作档位：自动 / 规划 / 逐步。点一下就转一格 ──
        // ★ 这里**只写档位本身**（「自动」「规划」「逐步」），不写解释 ——
        //   「这三档各是什么意思」在设置页那一行的小字里。
        // ★ 三个字宽窄不一，所以留固定最小宽度，免得点的时候那颗钮左右跳。
        val modeBtn = TextView(this).apply {
            text = AgentMode.label(AiAgentHolder.peek()?.config?.agentMode
                ?: AiAgent.loadConfig(this@ConMarnActivity).agentMode)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF5BE8E0.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            minWidth = dp(46)
            gravity = android.view.Gravity.CENTER
            setOnClickListener {
                cycleAgentMode()
                text = AgentMode.label(
                    AiAgentHolder.peek()?.config?.agentMode
                        ?: AiAgent.loadConfig(this@ConMarnActivity).agentMode
                )
            }
        }
        top.addView(modeBtn)

        val close = TextView(this).apply {
            text = "关闭"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF8FD9C4.toInt())
            setPadding(dp(12), dp(8), dp(6), dp(8))
            setOnClickListener { closeFlowPanel() }
        }
        top.addView(close)

        // ── 主体：一张按时间往下排的列表（见 [DashWeb] / `dash-core.js`）──
        //
        // ★ 2026-11-05 换掉了原来的「网页/原生」双版：结构不好，要改的是结构，
        //   不是把同一个结构再画一遍。原来那张原生星形图连同它的几何（AgentFlowView /
        //   FlowMath）已经删了 —— 只留这一版，改起来也只有一处会走样。
        // ★ 每一行都能点开，看到那一步的完整输入输出（见 [StepStore]）。
        //   这就是「调试」的入口：他 2026-11-04 的原话是「多agent协同是黑盒!没法调试!」。
        val web = DashWeb(this, onStep = { id -> showStepById(id) })
        flowWeb = web
        // ★ 网页铺满整层（不再被上面那条顶栏挤下去一格）：顶栏那两颗钮浮在它上面，
        //   页面自己那条吸顶玻璃标题占住最上面一条，两颗钮正好落在它右边的空白处。
        layer.addView(web, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        layer.addView(top, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.END })
        root.addView(layer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        flowLayer = layer
    }

    /**
     * 大屏上点了某一行 → 看那一步的**原文**。
     *
     * ★ 行是网页画的，它只带回来一个编号；正文要回 [AgentBus] 里找。
     *   找不到（那一条已经滑出最近窗口）就说清楚「没有完整记录」，
     *   不是编一段出来 —— 详见 [showStepDetail]。
     */
    private fun showStepById(id: Int) {
        // ★ 编号在事件里是 Long，从网页桥传回来的是 Int（JS 只有一种 number）——
        //   这里转一次，别让两边的类型自己去找平。
        val key = id.toLong()
        // ★ 2026-11-05 起屏上那一行只有代号和读数，任务原文和回答全文都收进点开之后，
        //   所以这里要**两段一起给**（配对规则在 [StepDetail]）。
        val shown = StepDetail.of(AgentBus.recent(DashWeb.SHOW_EVENTS), key)
        showStepDetail(key, shown)
    }

    /**
     * 一步的**原文**。没有完整记录时退回到事件流那一行 —— 并**说清是哪一种**，
     * 别让人以为这就是全部（那正是「黑盒」的另一半）。
     */
    private fun showStepDetail(id: Long, shown: StepDetail.Shown) {
        val tv = TextView(this).apply {
            text = shown.body
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFCFEFE6.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextIsSelectable(true)   // 能把参数复制出来单独试 —— 调试常干的事
        }
        AlertDialog.Builder(this)
            .setTitle(shown.title.ifBlank { "#$id" })
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("好", null)
            .show()
    }

    private fun closeFlowPanel() {
        val v = flowLayer ?: return
        flowLayer = null
        // ★ WebView 不会跟着父视图一起释放。不 dispose 的话，这一屏还挂着一个页面在后台。
        flowWeb?.dispose()
        flowWeb = null
        // ★ 只 remove 这一层:摘掉之后 [DashWeb.onDetachedFromWindow] 会自己摘监听。
        //   这里再手写一遍等于有两处管同一个开关。
        root.removeView(v)
    }

    /**
     * 「不用时关模型」。**只拨开关,不立刻停任何东西** —— 立刻停会把正在说的那一句话掐掉。
     * 真正的收人在 [BrainIdle] 的看门线程里,「用不到那么久」才会动手。
     */
    private fun toggleBrainIdle() {
        val on = !BrainIdle.isOn(this)
        BrainIdle.setOn(this, on)
        toast(
            if (on) "闲置释放模型:已开启 —— ${BrainIdle.IDLE_LABEL}无使用即释放，以降低功耗。"
            else "闲置释放模型:已关闭 —— 模型常驻内存，随时可用。"
        )
    }

    /** ⚙ 里的一行:大字标题 + 小字说明,整行可点。 */
    private fun settingRow(title: String, sub: String, onClick: () -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(13), dp(2), dp(13))
            isClickable = true
            setOnClickListener { onClick() }
        }
        box.addView(TextView(this).apply {
            text = title.replace("**", ""); textSize = 15f; setTextColor(getColor(R.color.glass_text))
        })
        box.addView(TextView(this).apply {
            text = sub.replace("**", ""); textSize = 11.5f; setTextColor(getColor(R.color.glass_sub))
            setPadding(0, dp(4), 0, 0)
        })
        return box
    }

    /**
     * 设置页的一条**分组标题**。
     *
     * ★ 2026-11-06 他的原话：「设置的UI要再优化一下太乱，而且跟玩具一样的」。
     *   原来是一张二十来行的大平铺，「电脑」一路排到「指纹」，每一条看上去一样重 ——
     *   找不到东西，也看不出哪几条是同一类。这一条只做一件事：把同一类框在一起。
     * ★ 只有**一个词**：分组名不是说明，说明仍然在每一条下面那行小字里。
     */
    private fun settingHead(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 11f
        setTextColor(getColor(R.color.glass_accent))
        letterSpacing = 0.18f
        setPadding(dp(2), dp(20), dp(2), dp(2))
    }

    /**
     * 改一行的字。
     *
     * ★ 2026-11-06 他第二次点名：「怎么还有各种底下的说明」「难看死，跟玩具一样的」。
     *   所以 `sub` 现在只放**状态**（开 / 关、几件、几级），不放解释；
     *   传空串 = 这一行只有标题，那行小字**整个收起来**，不留一条空白。
     *   解释全部在「使用说明」那一页（见 [showUsage]）。
     */
    private fun setRow(row: LinearLayout, title: String, sub: String) {
        (row.getChildAt(0) as TextView).text = title
        val s = row.getChildAt(1) as TextView
        if (sub.isBlank()) {
            s.visibility = View.GONE
        } else {
            s.visibility = View.VISIBLE
            s.text = sub
        }
    }

    /**
     * ⚙ 里的列表 —— **顶栏上除了它,一个带文字的按钮都不留**。
     *
     * 用户 2026-10-04 原话:「**最顶上只留设置按钮**,电脑没连不需要显示,放设置里,
     * 或者在左上角显示绿灯红灯…**还有那个喇叭图标是什么?**」
     *
     * 三件事一次说完:
     *  ① 顶栏从 5 个按钮(状态 / 🔊 / 唤醒 / 主动 / ⚙)砍成「左上角一盏灯 + 右边一个 ⚙」。
     *     上一版那句「一排带文字的开关怎么美化都还是控制板」还留着,这次是**真砍了**。
     *  ② 「电脑没连」不再当一行字挂在顶上 —— 变成左上角那盏灯(绿=连上,红=没连),
     *     点它进电脑那页。**灯的含义他点名给的,别换颜色。**
     *  ③ 「那个喇叭图标是什么」= 出声开关。它本身就说明这个图标**没自解释** ——
     *     搬进这里,配一句人话。
     */
    private fun showSettings() {
        // ── 从顶栏搬下来的四个 ──
        val pcRow = settingRow("电脑连接", "", { openTouchpad() })
        val ttsRow = settingRow("语音播报", "", { toggleTts() })
        val wakeRow = settingRow("语音唤醒", "", { toggleWake() })
        // ── 唤醒词（2026-11-08）──
        //
        //   他的原话：「唤醒词我得喊她几遍才行，然后我在这里得加一个重新录入唤醒词，
        //   这样才能给用户输入唤醒词」。
        //   ★ 唤醒词不是训练出来的（见 [WakeTokenMath] 文件头），录的是**校验**：
        //     让他打的字在现场被这个模型听到，才存。听不到就不存 —— 一份「配好了却永远不响」
        //     的配置比没有配置毒得多。
        val wakeWordRow = settingRow("唤醒词", "", {})
        val greetRow = settingRow("主动问候", "", { toggleGreet() })

        // ── 她的性格(2026-10-07)──
        //
        //   「**我得有她性格调试的地方**」。这一行是那道门 —— 里面能看见她此刻什么样,
        //   也能亲手把她拨到某个值。见 [showHerPanel]。
        //
        //   ★ 名字叫「她的性格」不是「她」:外面那个列表的标题**本来就叫「她」**,
        //     再挂一行「她」会变成「她 / 她」,谁也看不出那行是干什么的。
        val herRow = settingRow("角色设定", "", { showHerPanel() })

        // ── 记忆库(2026-10-08)──
        //
        //   他的名字就叫「**记忆库**」(「再在房间设置里,多加一条,记忆库?」),所以这一行
        //   照他的原话写。但**它底装着两样东西**,进去看得见:
        //     · 经验库 —— 她「碰上麻烦先按什么顺序试」的做法(★ 大半是**云端老师**教的);
        //     · 长期记忆 —— 关于他本人的事(怎么叫他、常开什么应用、听错的词)。
        //
        //   ★★ 这一行**回答的是他那句话**:「云端老师教错了,那我该咋办」——
        //     所以它的副标题必须**当场报出条数**:她一条都没记着(或记得很少)的时候,
        //     他根本不会想到去点它。见 [showMemoryPanel]。
        val memRow = settingRow("经验库", "", {})

        // ★ 2026-11-07「信息屏」:房间左右那两块挂着的屏（见 [RoomPanels]）。
        //   关掉之后房间立刻回到原来那样 —— 视图是真拆掉的，不是藏起来。
        val panelRow = settingRow("房间信息屏", "", { toggleRoomPanels() })

        // ── 原有的「她一直在」那一组 ──
        val aliveRow = settingRow("后台常驻", "", { toggleAlive() })
        val quietRow = settingRow("免打扰", "", { toggleQuiet() })
        // ★ 2026-11-07「打断」:她说话时他能不能直接开口(见 [BargeIn])。
        //   默认关,而且这台手机不支持回声消除时**开不了** —— 副标题会自己说明原因。
        val bargeRow = settingRow("语音打断", "", { toggleBarge() })
        val quietTimeRow = settingRow("免打扰时段", "", { showQuietIntro() })

        // ── 她的声音:★ 2026-10-06 收起来了 ──
        //
        //   他原话:「**音色不要选了,就第一次系统自带的那个音色,我觉得蛮好听的**」。
        //   起因是他那天进来一个个试,最后停在 801 号上 —— 那不是原来那个。
        //
        //   ★ 收掉的是**入口这一行**,不是 [showVoicePick] 那个面板本身(它原样留着,
        //     一行就能挂回来:把下面这行 `val voiceRow = …` 取消注释、再把它加回
        //     最下面那个清单和 `setRow` 那一段)。
        //   ★ 同时 SherpaVoice 那边改回**写死第 0 号**(见那里的注释)—— 只收入口
        //     是不够的:盘上存着 801,不收掉读的那一头,她张嘴还是 801 号。
        //
        // val voiceRow = settingRow("她的声音", "") { showVoicePick() }

        // ★★★ 2026-10-08:「她的嗓子」这一行**收起来了**(他亲口说的「删掉那个设置」)。
        //
        //   这一行昨天才加的,加它的理由是真的 —— 「不卡」(系统那副,边合成边出声)和
        //   「多音字准」(内嵌那副 VITS)在这台机器上**要不了同一个**,而哪个更要紧
        //   只有他的耳朵能判。**他判完了**:他要系统那副(「就是这个音色!」)。
        //
        //   ★ 判完就不该再留一个钮。留着的坏处是**实打实的**:那一行是个开关,
        //     点一下会切**走** —— 他哪天想让房间说句话,顺手点它,听见的就是内嵌那副,
        //     而界面上写的还是他选的那个。**一个会把事情弄坏的按钮,比没有这个按钮差。**
        //   ★ 收的只是**这一行**;她那副嗓子一个字节都不跟着变 —— 她照旧是系统那副
        //     (见 [DEF_ENGINE]),音色照旧是引擎默认的「中文(温柔女声)」区域=chn。
        //     收掉一个开关 ≠ 换一副嗓子,这两件事别混。
        //
        //   ★ 要挂回来:把下面这行取消注释,再加上「她的嗓子」那一行回到 [liveRows] 里、
        //     `setRow(engineRow, …)` 那一段(在下面的 `refresh()` 里,原样留着)、
        //     以及那行 `engineRow.setOnClickListener`。
        //     [toggleVoiceEngine] / [auditionVoice] 两个函数原样留着,没动过。
        //
        // val engineRow = settingRow("她的嗓子", "", {})

        // ── 连接（2026-11-06 合并）──
        // ★★ 他原话:「api控制中心和模型清单感觉是一个东西,不是吗?」「能不能把模型清单和
        //   api控制中心进行一个有机合并」。
        //   对 —— 从用户那边看,两页回答的是同一个问题:**她这项能力由谁提供、key 填在哪**。
        //   分两页的代价就是他找不到(他确实找错了两次)。所以合成一个入口,
        //   里面只有「一类活」和它下面的「一家」,第三层永远是填 key 那一屏。
        //   见 [showLinks]。
        // ★ 点击**故意留空**:它也要走「先关掉这一层再开子页」,而那句话得等
        //   [openPage] 声明出来才写得了(Kotlin 的局部函数不能提前引用后声明的局部变量)。
        val linkRow = settingRow("模型与接口", "") {}

        // ── 前缀快照预热（2026-11-08）──
        //
        //   他的原话：「设置里加一个前缀快照预热的设置选项，我想手动和自动识别环境
        //   是否可以加载，手动更优先」。见 [showPrefillPanel]。
        //   ★ 副标题**只放状态**（规矩同 [setRow]）：「有没有快照」和「自动开没开」
        //     各占一半 —— 这两件是他点进去最想确认的。
        val prefillRow = settingRow("前缀快照预热", "") {}

        // ── 工作区（2026-11-06）──
        //   他问「返回了成品，但是就一个界面，不能真正生成在桌面上的应用」——
        //   成品落在沙盒工作区里，而那个目录第三方文件管理器进不去（Android 11+ 不让进
        //   `Android/data`）。**没有这一页，他做出来的东西等于拿不到。**
        val workRow = settingRow("工作区", "") {}

        // ── 技能（2026-11-06）──
        //   他问「怎么生成 skill、在哪看、哪删哪增加」—— 之前**只有模型能读写技能**，
        //   他一条都看不见。技能是他自己的东西，得能在界面上管。
        val skillRow = settingRow("技能", "") {}

        // ── 关于本机（2026-11-06）──
        //   ★ 他的原话：「反正就是当初做daw那种有一个设置里面什么关于本机，什么版本啥啥的」。
        //     版本号这东西平时没人看，出了问题时第一个被问 —— 没有这一页，
        //     他只能去应用商店或安装包里找。
        val aboutRow = settingRow("关于本机", "", {})

        // ── 云端兜底(2026-10-09)──
        //
        //   他原话:「**帮我直接加一个云端兜底开关好吧**」。加它之前,「关掉云端」**只有一条路** ——
        //   进「API 控制中心」把 key 那一框删空。那不是开关,那是**销毁配置**:
        //   想再打开得把 key 找回来重填一遍。而他要的只是「现在别发出去」。
        //
        //   ★ 它是**一把独立的锁**,和 key **串着** —— 两个都开才出手机(见
        //     [AiAgent.chat] 那条 if)。所以「有 key 但关着」是**合法状态**,而且
        //     正是他要待的那个位置:key 原地留着,一个字节都不出去,想开的时候拨回来。
        //   ★ 它同时管**云端老师**(见 [AiAgent.askTeacher])—— 「关掉云端」如果只关一半,
        //     界面结构照旧往外走,那这个钮就是在说反话。
        //   ★ 点击**故意留空**:它要调的那两个东西(`toggleCloud` 和 `doRefresh`)都在后面
        //     (Kotlin 的局部变量不能提前引用),照 [apiRow] 那个先例,挂到下面那一排里。
        val cloudRow = settingRow("云端总闸", "") {}

        // ── 云端只见代号（假名，2026-11-04）──
        //
        //   他原话:「能不能做加密解密，就是本地加密后传云端，云端不能解密，
        //   然后云端只能通过一些加密但能识别的进行思考，然后再传到本地解密」。
        //
        //   ★ 真加密做不到（密文在模型眼里就是噪声）；能做的是**稳定代号**：
        //     同一个号码永远换成同一个词，云端能推理「这是同一个东西」，但拿不回值。
        //   ★ 它**盖不掉**「有敏感就整个留在本地」那条 —— 结构还在外面
        //     （它知道这个代号出现了几次）。所以这是个能拨的钮，不是默认就最安全。
        //   ★ 点击**故意留空**:同 [cloudRow] 那个先例，挂到下面那一排里。
        val aliasRow = settingRow("云端代号化", "") {}

        // ── 开 App 要指纹（2026-11-06）──
        //
        //   他问「指纹二次安全验证做了吗」。做了，而且**是两处**（见 [GateMath]）：
        //   进门一次 + 碰钱/身份时再验一次。
        //   ★ 这一行的小字必须写清**这台机器验不了的时候它挡不住** ——
        //     没录指纹也没设锁屏密码时，写「开」是骗人的（[AppGate.available] 会是假）。
        //   ★ 默认**开**：这是门口，不是功能；关掉要他自己拨。
        val gateRow = settingRow("启动身份验证", "") {}

        // ── 动作台账（2026-11-07）──
        //   他 2026-11-04 要的三件里，这一版做前两件：统一的动作分级 + 本地台账。
        //   ★ 这一版**只记不拦**（他的原话：「不改变现有行为，只增加可见性」）。
        //   ★ 小字只说两件他最需要知道的事：记了多少条、只在本机。怎么分档、
        //     记什么，全在点进去那一页里。
        val auditRow = settingRow("动作台账", "") {}

        // ── 她记得的事（2026-11-06）──
        //
        //   他问过「本地向量记忆做没做」。做的是**够用的那一版**（见 [MemoryStore]
        //   文件头）：按字面形状在本机算相似度，不是神经网络向量。这一点在这一行的
        //   小字里必须说清 —— 说成「语义检索」就是在骗评委。
        //   ★ 记忆必须能看能删：只有能删，他才敢让它一直记。
        val rememberRow = settingRow("本地记忆", "") {}

        // ── 共用记忆（2026-11-06）──
        //
        //   他问「不同 agent 那个库能共享了吗」，然后一条一条定了：活内 + 长期、
        //   只记最后的成品、按相关度捞、资料和结论装在一本里、放设置里。
        //   ★ 这是**第二本**，和「她记得的事」分开（见 [AgentMemory] 文件头）。
        val sharedRow = settingRow("共享记忆", "") {}

        // ── 协同大屏(2026-11-04)──
        //
        //   他原话:「一个界面……是他们工作的神经网络的数据流的科幻界面」。
        //   ★ 那一屏上**只放事实**:每个移动的点都是一条真事件(见 [AgentFlowView])。
        //     解释「这屏是干什么的」「颜色什么意思」全在这一行的小字和图里的图例上 ——
        //     大屏本身一句说明都不写(规矩见记忆 `all-explanations-live-in-settings`)。
        //   ★ 点开是**全屏黑底覆盖层**,不是玻璃弹窗:那张图的对比度靠黑底撑着,
        //     压在七成白的玻璃上会糊掉。
        //   ★ 2026-11-05 补：这一行的小字还要说清**三档**各是什么。他问过
        //     「那三档 auto、plan 这种的怎么调」—— 开关在大屏顶栏，解释只能在这儿。
        // ★ 2026-11-06 他的原话：「设置中的协同大屏不要有这个入口」——
        //   所以这里**没有那一行了**。这一屏的代码（[openFlowPanel]）原样留着，
        //   只是设置页不再是它的入口。

        // ── 不用时关模型(2026-10-25)──
        //
        //   他原话:「看看能不能使用的时候关模型,因为我一般都不怎么用Agent」
        //   「帮我设一个开关就行」。起因是**手机发烫**,他怀疑是她。
        //
        //   ★ 这个钮**只回答一件事**:很久没用到她,就把模型进程收掉([BrainIdle])。
        //     门槛是 [BrainIdle.IDLE_MS] —— ★ 第一版定 10 分钟,他当场指出比重新装载
        //     还短(他报 11 分钟),省的不够赔,已改成 1 小时。
        //     默认**关** —— 和「她一直在」同一个道理,这是**改动她现在的脾气**,
        //     不能装完就自己生效。
        //   ★ 代价必须写在下面那行小字里:**收掉以后下一次开口要等重新装载**。
        //     不说清楚的话,他关完遇到一次「半天不理我」会当成 bug ——
        //     那正是这些开关最容易骗人的地方。
        val idleRow = settingRow("闲置释放模型", "") {}

        // ★ 只在「她该一直在、却发不出通知」时才多出这一行。
        //
        //   为什么非有它不可:没有通知权限时,前台服务的通知**不会出现在通知栏**
        //   (Android 13+ 明文如此:只在任务管理器里看得见),而「让她睡」只长在那条
        //   通知上。这个状态是**静默**的 —— 她照常跑、日志一切正常,用户只会觉得
        //   「说好的那条通知呢」,而且无处可查。
        //
        //   2026-10-04 真机实测就正好卡在这个状态上:「一直在」早就开着,
        //   而光在 [toggleAlive] 里问是**救不回来**的 —— 那条路要「先关掉再开」才走得到。
        //   所以补救入口必须独立存在。
        val notifRow = if (HerLife.isAlive(this) && !canPostNotifications()) {
            settingRow("通知权限 · 未授权", "") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            }
        } else null

        // ★ 「彻底关掉」那条路（系统「强制停止」）现在写在「使用说明」里 ——
        //   它原来是一条挂在列表最底下的小字，2026-11-06 他点名「难看死，跟玩具一样的」。
        //   说明归说明页，列表只放能点的行。

        // ── 使用说明：这一页把原来挂在每行下面、以及列表最底下的那些话收进来 ──
        val usageRow = settingRow("使用说明", "", {})

        fun refresh() {
            setRow(aboutRow, "关于本机", "版本 " + appVersionName() + " · Android " + Build.VERSION.RELEASE)
            val pcOn = TouchpadClient.get(this).isConnected()
            setRow(pcRow, "电脑连接:" + if (pcOn) "已连接" else "未连接", "")

            setRow(ttsRow, "语音播报:" + if (ttsOn) "已开启" else "已关闭", "")

            // ★ 「她的声音」那一行 2026-10-06 收起来了(理由见上面那段)。
            //   挂在墙上的那段 `setRow(voiceRow, …, …)` 原样留着,在 git 历史里;
            //   要挂回来就连同上面那行 `val voiceRow = …` 一起取消注释。

            // ★ 「她的嗓子」那一行 2026-10-08 收起来了(理由见上面声明那段)。
            //   原来那段 `setRow(engineRow, …)` —— 写着两档各自的代价 —— **原样留着,
            //   在 git 历史里**。要挂回来照上面那段注释里写的四处一起改。
            //
            //   ★ 一句要留着的话:她那副嗓子是**哪一副**,现在只从日志里看 ——
            //     [applyTtsVoice] 里那行「房间:她的嗓子是…」就是它。界面上一行都不显示,
            //     这是**故意的**(他不想看),不是漏了。

            // ★ 副标题**必须跟着真实状态走**（2026-11-06 修）：原来写死「现在还没装」，
            //   装好之后标题显示「开」、副标题还在说没装 —— 自相矛盾，演示时最扎眼。
            setRow(wakeRow, "语音唤醒:" + wakeState(), "")

            // ★ 小字只说一句「点这里改」，怎么改全在点进去那一页里 ——
            //   说明文字一律进设置页，行上只放状态（同 cloudRow 那条规矩）。
            setRow(wakeWordRow, "唤醒词:" + WakeEnroll.currentWord(this), "点这里重新录入")

            setRow(greetRow, "主动问候:" + if (ProactiveGreeting.isEnabled(this)) "已开启" else "已关闭", "")

            val aliveOn = HerLife.isAlive(this)
            setRow(aliveRow, "后台常驻:" + if (aliveOn) "已开启" else "已关闭", "")

            val quietOn = ProactiveGreeting.isQuietOn(this)
            setRow(quietRow, "免打扰:" + if (quietOn) "已开启" else "已关闭", "")

            // ★ 三句话对着三种实情:不支持(说原因)、开着、关着。**不支持时不能显示「关」了事** ——
            //   他会一直点它,以为是自己没找到开关。
            setRow(bargeRow, "语音打断:" + if (BargeIn.isOn(this)) "已开启" else "已关闭",
                if (BargeIn.available()) "" else "本机不支持")

            // ★ 小字只报数：画像是几个词、他点开过几条。怎么算的、会不出手机，都在使用说明里。
            setRow(panelRow, "房间信息屏:" + if (RoomPanels.isOn(this)) "已开启" else "已关闭",
                if (!RoomPanels.isOn(this)) "" else
                    "${InterestStore.tokenCount(this)} 个词 · 看过 ${InterestStore.openedCount(this)} 条")

            setRow(quietTimeRow,
                "免打扰时段 " + fmtMin(ProactiveGreeting.quietStart(this)) +
                    " – " + fmtMin(ProactiveGreeting.quietEnd(this)), "")

            // ★ 这一行的小字**当场读一次她此刻的状态** —— 它同时是两件事:
            //   ① 那扇门的招牌;② 不用点进去就看得见她现在什么心情。
            //   ★ 用的必须是 [MoodStore.snapshot](只读)—— 不能是 [MoodStore.onInteraction],
            //     那会把「他只是扫了一眼 ⚙ 列表」记成「他来过」,把「多久没见」当场清零。
            val st = MoodStore.snapshot()
            setRow(herRow, "角色设定",
                if (st == null) "" else "心情 ${st.mood} · 亲密度 ${st.intimacy}")

            // ★★ 记忆库那一行**必须当场报条数**:空的(或记得很少)的时候,
            //   他根本不会想到点它 —— 而这正是他最需要进去的时候(老师教错了)。
            //   ★ 两个库都是**只读**入口([ExperienceStore.all] / [UserLexicon.snapshot]),
            //     不写盘、不记「他来过」(同下面那句 [MoodStore.snapshot] 的规矩)。
            val exps = ExperienceStore.all().size
            val lex = UserLexicon.snapshot()
            val about = ((lex?.call?.size ?: 0) + (lex?.avoid?.size ?: 0) +
                (lex?.apps?.size ?: 0) + (lex?.fixes?.size ?: 0))
            setRow(memRow, "经验库",
                if (exps == 0 && about == 0) "暂无记录" else "$exps 条经验 · $about 条用户资料")

            // ★ 两个数各报一半:模型几个、接口几项。都没有才说「未配置」。
            val apis = ApiStore.purposes()
            val eps = agent().config.endpoints
            val ready = eps.count { RouteMath.viable(it) }
            setRow(linkRow, "模型与接口",
                when {
                    apis.isEmpty() && eps.isEmpty() -> "未配置"
                    eps.isEmpty() -> "${apis.size} 项接口"
                    apis.isEmpty() -> "$ready 个模型"
                    else -> "$ready 个模型 · ${apis.size} 项接口"
                })

            // ★ 两个状态各报一半：快照在不在（只读盘，很快）+ 自动开关。
            //   「没有」不代表坏了 —— 提示词一换名字就换，旧的还躺着但不算数。
            val snap = agent().snapshotStatus()
            setRow(
                prefillRow,
                "前缀快照预热:" + if (snap != null && snap.tokens > 0) "已就绪" else "还没有",
                "自动" + if (agent().config.prefillAuto) "已开启" else "已关闭"
            )

            // ★ 两行小字都**只报数**，怎么用写在里面那两页（规矩：解释文字进设置）。
            val made = workFiles().size
            setRow(workRow, "工作区", if (made == 0) "暂无" else "$made 件")
            val skills = SkillStore.all()
            setRow(skillRow, "技能", if (skills.isEmpty()) "暂无" else "${skills.size} 条")

            // ★ 这句小字必须同时说清两件事:**记的是什么**、**会不会出去**。
            //   只写「本地」会让他以为开着云端也绝不会出去 —— 那是假的:
            //   想起来的那几句会跟着这一轮的话一起走到云端。
            val memN = MemoryStore.count()
            setRow(rememberRow, "本地记忆", if (memN == 0) "暂无" else "$memN 句")

            // ★ 只报数：怎么用、存了什么、会不会出去，全在点进去的那一页里。
            val sharedN = AgentMemory.count()
            setRow(sharedRow, "共享记忆",
                if (sharedN == 0) "暂无" else "$sharedN/${AgentMemory.MAX} 条")

            // ★ 三句话对着三种实情，一句都不许混:
            //   ① 这台机器验不了 —— 开关写着「开」也是假的，必须说出来;
            //   ② 开着 —— 说清**什么时候**会按（进门 + 熄屏回来 + 碰钱/身份）;
            //   ③ 关着 —— 说清代价（谁拿到手机都能进）。
            val gateOn = AppGate.isOn(this)
            val gateOk = AppGate.available(this)
            setRow(gateRow, "启动身份验证:" + if (gateOn && gateOk) "已开启" else "已关闭",
                if (!gateOk) "本机不可用" else "")

            val cf = agent().config
            val cloudOn = cf.cloudOn
            // ★ 「配没配」不能只看兜底那一个 key：他也可以在「模型与接口」里按用途配好一家。
            //   只看 cloudApiKey 的话，明明能出网，这一行却写着「未配置密钥」——
            //   他照着这句话去填，填完发现没变，会以为开关是坏的。
            //   ★ 判据和 [AiAgent] 里那几处**用同一条**：任一家可用就算配过。
            val cloudHasKey = cf.cloudApiKey.isNotEmpty() || cf.endpoints.any { RouteMath.viable(it) }
            // ★ 小字是这一行的**全部说明**(浮层里只放选项本身,见「说明文字一律进设置」)。
            //   关着时必须说清代价与边界:天气/热点/网页搜索/图片生成/接口调用**全部**停,
            //   而本机那个脑子和他家电脑照常能用 —— 前者是他要的,后者是他会担心的。
            setRow(cloudRow, "云端总闸:" + if (cloudOn) "已开启" else "已关闭",
                when {
                    !cloudOn -> "所有出网已停 · 本机与局域网除外"
                    !cloudHasKey -> "点进去填密钥"
                    else -> ""
                })
            // ★★ 这一行只说**出不出手机**这件事,一个字都不提实现。
            //   「同一个号码同一个代号」是它能工作的原因,也得让他知道 —— 那是代价所在:
            //   云端虽然拿不回值,但看得出「这是同一个」。
            val aliasOn = agent().config.aliasOn
            setRow(aliasRow, "云端代号化:" + if (aliasOn) "已开启" else "已关闭",
                if (aliasOn) "${AliasStore.count()} 个代号" else "")
            // ★ 这一行的小字**必须报出代价**:开着的时候,下次开口要等重新装载。
            //   他要的是「手机别烫」,而代价是「她变慢了」—— 两件事都得让他看见。
            val idleOn = BrainIdle.isOn(this)
            setRow(idleRow, "闲置释放模型:" + if (idleOn) "已开启" else "已关闭", "")

            // ★ 只报两个数：多少条、只在本机。
            val auditN = Audit.count()
            setRow(auditRow, "动作台账", if (auditN == 0) "暂无 · 仅本机" else "$auditN 条 · 仅本机")

            refreshSettingsBtn()
        }

        // 点一下 = 切一下,切完原地刷新(不关框重开 —— 那样会闪)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(8))
        }
        // ★ 2026-11-06：从「一列平铺」改成**分组**。分组名只有一个词，说明仍在每条下面。
        val items = ArrayList<View>()
        // ★ 分组头**跟着行一起进**:一行都没有的分组连头也不要 —— 否则会出现一个
        //   光秃秃的标题悬在两段之间(`notifRow` 就是这种可有可无的行:权限给了它就不存在)。
        fun section(t: String, vararg rows: View?) {
            val live = rows.filterNotNull()
            if (live.isEmpty()) return
            items.add(settingHead(t))
            items.addAll(live)
        }
        section("连接", pcRow)
        section("语音与回应", ttsRow, wakeRow, wakeWordRow, greetRow, bargeRow, quietRow, quietTimeRow)
        section("房间", panelRow)
        section("角色", herRow, memRow, aliveRow, idleRow)
        section("能力", linkRow, prefillRow, skillRow, workRow, rememberRow, sharedRow)
        section("安全与隐私", cloudRow, aliasRow, gateRow, auditRow, notifRow)
        section("关于", usageRow, aboutRow)
        // 行里的点击要能在切完之后刷新,所以把 refresh 挂到一个可变的引用上
        var doRefresh: () -> Unit = {}
        // 「电脑」那一行是**唯一一条会离开这个框的路**(它跳去电脑那页)——
        // 不关掉的话那个框会一直挂在后面,回来时看着像卡住了。
        var dlg: AlertDialog? = null

        // ★★ 往里走一层 = **先关掉这一层,再开子页** —— 两层玻璃叠着会透字(见 [glassLive])。
        //   ★「电脑」那一行**不走这条路**:它是离开这个界面,不是往里走一层。
        //   ★ 每开一层都把自己的回程写上(回程是**一个**变量,后来的人会把它换掉)。
        fun openPage(open: () -> Unit) {
            glassReturn = { showSettings() }
            dlg?.dismiss()
            open()
        }

        pcRow.setOnClickListener { dlg?.dismiss(); openTouchpad() }
        ttsRow.setOnClickListener { toggleTts(); doRefresh() }
        // ★ 「她的嗓子」那一行的点击 2026-10-08 跟着收起来了(见上面声明那段)。
        //   原来是:`engineRow.setOnClickListener { toggleVoiceEngine(); doRefresh() }`
        wakeRow.setOnClickListener { toggleWake(); doRefresh() }
        wakeWordRow.setOnClickListener { showWakeEnroll { doRefresh() } }
        greetRow.setOnClickListener { toggleGreet(); doRefresh() }
        aliveRow.setOnClickListener { toggleAlive(); doRefresh() }
        // ★ 拨到「开」才弹那一页 —— 所以得先问它拨完是什么状态(见 [toggleQuiet] 的返回值)。
        quietRow.setOnClickListener {
            if (toggleQuiet()) openPage { showQuietIntro() } else doRefresh()
        }
        bargeRow.setOnClickListener { toggleBarge(); doRefresh() }
        panelRow.setOnClickListener { toggleRoomPanels(); doRefresh() }
        quietTimeRow.setOnClickListener { openPage { showQuietIntro() } }
        linkRow.setOnClickListener { openPage { showLinks() } }
        prefillRow.setOnClickListener { openPage { showPrefillPanel() } }
        workRow.setOnClickListener { openPage { showWorkArea() } }
        rememberRow.setOnClickListener { openPage { showRemember() } }
        sharedRow.setOnClickListener { openPage { showAgentMemory() } }
        skillRow.setOnClickListener { openPage { showSkills() } }
        aboutRow.setOnClickListener { openPage { showAbout() } }
        usageRow.setOnClickListener { openPage { showUsage() } }
        // ★ 点它不再是「直接翻转」。开关和密钥都收进那一页（见 [showCloudPanel]）——
        //   他 2026-11-08 要的就是「在云端总闸那点一下，填 key」。
        cloudRow.setOnClickListener { openPage { showCloudPanel() } }
        aliasRow.setOnClickListener { toggleAlias(); doRefresh() }
        gateRow.setOnClickListener { toggleGate(); doRefresh() }
        auditRow.setOnClickListener { openPage { showAudit() } }
        idleRow.setOnClickListener { toggleBrainIdle(); doRefresh() }
        // ★ 它原来靠「关掉子页那一刻叫一声」来刷新那行小字(它写着「此刻:心情 62」)。
        //   现在不用了:**子页关掉时整个列表是重新开一遍的**(回程就是 [showSettings]),
        //   数字天然是新的 —— 而且比原来还新(原来那个列表是点进去之前那一份)。
        herRow.setOnClickListener { openPage { showHerPanel() } }
        memRow.setOnClickListener { openPage { showMemoryPanel() } }
        doRefresh = { refresh() }
        items.forEach { box.addView(it, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        refresh()

        // ★★ 必须套一层 ScrollView —— 这不是「以防万一」,是**踩过的坑**:
        //   不套的话弹窗高度会被系统卡在 ~294dp(横竖屏都一样),**超出部分直接不画**,
        //   而且不报任何错。实测症状:三行只出来两行、脚注整段消失,
        //   看着像是「代码里没写」,其实是画布外面去了。
        //   这个坑的危险在于它随内容长度静默出现 —— 加一行、改长一句话就可能触发。
        // ★★ 到这一页就是**到底了** —— 回程清空。
        //   ★ 不清的后果具体得很:「电脑」那一行会关掉这个框跳去电脑那页,
        //     那一关会让「最后一层关完了」成立 → 把上面某一份设置面板**又开出来**
        //     盖在电脑那页上,而且他还退不出去。
        glassReturn = null
        dlg = glassBuilder("设置")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("完成", null)
            .show()
            .glassified()
    }

    // ==================================================================
    // 她的性格 —— 看得见她此刻什么样,也能自己拨
    //
    // 用户 2026-10-07:「**我得有她性格调试的地方**」。
    //
    // ★ 这一页和 ⚙ 里那些**不是一类东西**:那些是开关(开 / 关,两种状态),
    //   这一页是**旋钮** —— 她此刻是个什么数,他看得见,也能亲手改。
    //   做成一行「开 / 关」就把它做没了。
    //
    // ★★ 界面**绝不自己算那个数**。心情怎么随时间漂、被一句话改多少,判据只有
    //   [MoodMath] 那一处;这一页只做两件事:读([MoodStore.snapshot])和写
    //   ([MoodStore.pokeNow])。自己再算一遍 = 又一个「同一张表抄三遍」。
    // ==================================================================

    /**
     * 这一页那根滑杆:0..100,粉色。
     *
     * ★ 这是全项目**第一根 SeekBar**(此前一根都没有 —— `overlay_transfer.xml` 那个
     *   ProgressBar 是只读的)。用它而不是「− / ＋」两粒按钮,理由只有一条:
     *   心情是 0..100 的连续量,按一次走 5 的话,从他看见的那个数走到想去的那个数
     *   要按十几下 —— 那不叫「拨到某个值」。
     *
     * ★ 染色不是好看:默认那套灰底轨道压在这块深色对话框上几乎看不见,
     *   而**看不见的轨道看起来就像「这里没有东西可拨」** —— 这个项目的头号敌人。
     */
    private fun herSlider(): SeekBar = SeekBar(this).apply {
        max = 100
        progressTintList = ColorStateList.valueOf(0xFFE05B8E.toInt())
        thumbTintList = ColorStateList.valueOf(0xFFE05B8E.toInt())
    }

    /**
     * 「她的性格」这一页。
     *
     * @param onClosed 原样传给 [showPersonaEdit],它回来时再用 —— 这一页自己**不用**它。
     *   ★ 别再往这里挂「关掉就叫一声」的回调:⚙ 列表那行小字(「此刻:心情 62」)
     *     现在靠**回来时整个列表重开一遍**来保证新鲜,那比回调还新
     *     (回调刷的是点进去之前那一份)。理由和那个坑见这一页的末尾。
     */
    private fun showHerPanel(onClosed: () -> Unit = {}) {
        val box = column()
        // ★ 「改她的自我介绍」那一节要用它把自己收掉再开子页(照 [swapPage] 那套:
        //   父页先 dismiss,子页回来时重开一个父页)。
        //   ★ 声明必须放在这一页的**最前面**:Kotlin 的局部函数能前向引用后面的局部函数,
        //     但**不能**前向引用后面的局部变量 —— 放下面会编译错 `Unresolved reference`。
        var dlg: AlertDialog? = null

        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 12f; setTextColor(getColor(R.color.glass_sub))
            setPadding(dp(2), dp(12), dp(2), dp(2))
        }
        fun value() = TextView(this).apply {
            textSize = 14f; setTextColor(getColor(R.color.glass_text))
            setPadding(0, dp(12), 0, 0)
        }
        fun word() = TextView(this).apply {
            textSize = 12f; setTextColor(getColor(R.color.glass_sub))
            setPadding(dp(2), dp(2), dp(2), 0)
        }

        val moodVal = value(); val intiVal = value()
        val moodWord = word(); val intiWord = word()
        // ★ 它必须**声明在 [paintSeen] 之前**:Kotlin 的局部函数能前向引用别的局部函数,
        //   但**不能**前向引用还没声明的局部变量 —— 放下面那一行会编译错 `Unresolved reference`。
        val seen = TextView(this).apply {
            textSize = 12f; setTextColor(getColor(R.color.glass_sub))
            setPadding(dp(2), dp(14), dp(2), 0)
        }
        val moodBar = herSlider(); val intiBar = herSlider()

        // ★ 进来先读一次。`null` = 情绪账本还没开 —— 那种时候两根杆只能看不能拨,
        //   而且要**说出来**,不能让它看起来像「拨了没反应」。
        val s0 = MoodStore.snapshot()
        if (s0 != null) {
            moodBar.progress = s0.mood
            intiBar.progress = s0.intimacy
        } else {
            moodBar.isEnabled = false; intiBar.isEnabled = false
        }

        /** 跟着**指头**走的那两处文字:他正拨到哪儿,旁边那句人话就跟着变。不碰盘。 */
        fun paint() {
            moodVal.text = moodBar.progress.toString()
            intiVal.text = intiBar.progress.toString()
            moodWord.text = MoodMath.moodWord(moodBar.progress)
            intiWord.text = MoodMath.relationWord(intiBar.progress)
        }

        /** 从**盘上**读「上次见你」那一行。只在进来时和拨完之后调,**不在拖动途中调**。 */
        fun paintSeen() {
            val s = MoodStore.snapshot()
            if (s == null) {
                seen.text = "情绪账本还没开 —— 这一页这会儿拨不动。"
                return
            }
            val h = ((System.currentTimeMillis() / 1000 - s.lastSeenSec).coerceAtLeast(0)) / 3600.0
            // ★ 「一起待过 N 天」是他唯一看得见「相处越久越亲」在动的地方 ——
            //   亲密度那个数一天动不了几格,而这行**每见一面就 +1**,所以他明天再来看,
            //   它是会变的。★ 数的是**说过话的天数**(挂机不算),见 MoodMath.together。
            val days = if (s.daysTogether > 0) ",一起待过 ${s.daysTogether} 天" else ""
            seen.text = "上次见你:%.1f 小时前$days —— 她这会儿:".format(h) + MoodMath.missWord(h)
        }

        /**
         * 松手 = 定下来。
         * ★ 只能在**松手**时写盘:拖动途中每变一个像素写一次,盘会被打花,而且没有意义 ——
         *   他要的是最后停在哪。
         */
        fun commit() {
            val after = MoodStore.pokeNow(moodBar.progress, intiBar.progress) ?: return
            ModelManager.get(this).trace(
                "房间:我把她拨到 心情 ${after.mood} 亲密度 ${after.intimacy}")
            paintSeen()
        }

        // ★ 两根杆共用一个 listener —— 拨哪根都同时提交**两根此刻的位置**,这样不会
        //   出现「拨了心情、把刚拨好的亲密度又按盘上的旧值写回去」。
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = paint()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) = commit()
        }
        moodBar.setOnSeekBarChangeListener(listener)
        intiBar.setOnSeekBarChangeListener(listener)

        fun block(title: String, bar: SeekBar, v: TextView, w: TextView) {
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(label(title), LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(v)
            box.addView(head)
            box.addView(bar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            box.addView(w)
        }
        block("心情", moodBar, moodVal, moodWord)
        block("亲密度", intiBar, intiVal, intiWord)
        box.addView(seen)


        // ── 她是个什么样的人:那十几个数字(2026-10-07)──
        //
        //   ★ 和上面那两根**不是一类东西**,别混:
        //     · 上面那两根拨的是「她**此刻**什么样」—— 拨完照样随时间漂、照样被你说的话改;
        //     · 这一节拨的是「她**是个什么样的人**」—— 拨一次,长期有效,时间推不动它。
        //
        //   ★★ 上下限**这一页不判**。夹范围只有 [MoodTuningMath.sanitize] 一处,
        //     [MoodStore.applyTuning] 出去之前一定过它一遍。这一页只管 ± 一个步长 ——
        //     在这儿再写一份区间,就是「同一张表抄两遍」,两份早晚分家。
        //     哪一头到头了,是**问它**问出来的(照着再拨一步、看数字还动不动)。
        val knobs = MoodTuningMath.knobs()
        var tuning = MoodStore.currentTuning()
        val knobVals = mutableListOf<TextView>()
        val knobMinus = mutableListOf<Button>()
        val knobPlus = mutableListOf<Button>()

        /** 把那一排数字重刷一遍,顺带决定哪几个 ± 已经按不动了。 */
        fun paintKnobs() {
            knobs.forEachIndexed { i, k ->
                val v = k.get(tuning)
                knobVals[i].text = v.toString()
                val atLow = k.get(MoodTuningMath.sanitize(k.set(tuning, v - k.step))) == v
                val atHigh = k.get(MoodTuningMath.sanitize(k.set(tuning, v + k.step))) == v
                // ★ 到头了就把那颗按钮**变淡并按不动**,不许让它看起来能按却没反应 ——
                //   「按了没动静」正是这个项目最恨的那种坏法:它长得像「这个功能是坏的」。
                knobMinus[i].isEnabled = !atLow
                knobMinus[i].alpha = if (atLow) 0.3f else 1f
                knobPlus[i].isEnabled = !atHigh
                knobPlus[i].alpha = if (atHigh) 0.3f else 1f
            }
        }

        /** 拨一格。★ 写盘和夹范围都在 [MoodStore.applyTuning] 里,这一页不重复做。 */
        fun bump(k: MoodTuningMath.Knob, delta: Int) {
            tuning = k.set(tuning, k.get(tuning) + delta)
            MoodStore.applyTuning(tuning)
            paintKnobs()
        }

        box.addView(TextView(this).apply {
            text = "她是个什么样的人"
            textSize = 13f
            setTextColor(getColor(R.color.glass_accent))
            setPadding(dp(2), dp(22), dp(2), 0)
        })
        box.addView(word().apply {
            text = "下面这些拨一次长期有效 —— 和上面那两根不一样,上面拨的是她此刻。"
        })

        knobs.forEach { k ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(label(k.label), LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val v = TextView(this).apply {
                textSize = 13f
                setTextColor(getColor(R.color.glass_text))
                gravity = Gravity.CENTER
                minWidth = dp(48)
                setPadding(dp(4), 0, dp(4), 0)
            }
            val minus = herButton("－", 15f, onClick = { bump(k, -k.step) })
            val plus = herButton("＋", 15f, onClick = { bump(k, k.step) })
            listOf(minus, plus).forEach {
                it.layoutParams = LinearLayout.LayoutParams(dp(34), dp(30))
            }
            row.addView(minus)
            row.addView(v)
            row.addView(plus)
            box.addView(row)

            knobVals.add(v); knobMinus.add(minus); knobPlus.add(plus)
        }


        // ── 她是谁:自我介绍那几行(2026-10-07)──
        //
        //   ★ 和上面**两层都不是一类东西**,这一页到这里是第三层:
        //     · 那两根拨的是「她**此刻**怎么样」;· 那十几个数字拨的是「她**是个什么样的人**」;
        //     · 这一节改的是「她**是谁**」—— 她叫什么、什么脾气、怎么跟他说话。
        //
        //   ★★ 只有这一节要付一次冷算(约 11 分钟):前面两层的东西都是每轮临时拼在
        //     对话**后面**的,而这一段在**被缓存的那段开头**里 —— 改一个字,存的那份
        //     快照就对不上了。所以它单独放一节,并且**存之前先问他**。
        //
        //   ★ 工作手册一个字都不在这儿:**结构上够不着**(留在源码里,运行时才拼上去),
        //     不是「藏起来了」—— 这一页能碰到的东西里根本没有它。
        box.addView(TextView(this).apply {
            text = "她是谁"
            textSize = 13f
            setTextColor(getColor(R.color.glass_accent))
            setPadding(dp(2), dp(22), dp(2), 0)
        })
        box.addView(word().apply {
            text = "她的自我介绍 —— 写着她叫什么、什么脾气、平时怎么跟你说话。" +
                "改这里等于换一个人,所以存之前会先问你一次。"
        })
        box.addView(
            herButton("改她的自我介绍", 13f) { dlg?.dismiss(); showPersonaEdit(onClosed) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38),
            ).apply { topMargin = dp(10) },
        )

        paint()
        paintSeen()
        paintKnobs()
        dlg = pageDialog("她的性格", box)
        // ★★ 这里**不许**再挂 `setOnDismissListener`。原来挂着一个「关掉就 onClosed()」,
        //   玻璃那套把它换掉了 —— 换的理由不是它多余,是它**有害**:
        //   `setOnDismissListener` 会**先把旧的那个立刻触发一次**再换新的,
        //   而旧的那个正是「最后一层关完了吗」那根线 → 于是这一页**刚开出来**就判定
        //   「全关完了」→ 把 ⚙ 列表又开一份盖在它上面。
        //   ★ 那一行小字(「此刻:心情 62」)现在**不靠回调刷新**了:
        //     子页关掉时整个列表是重新开一遍的(回程就是 [showSettings]),数字天然是新的。
    }

    // ==================================================================
    // 她是谁 —— 改她的自我介绍
    //
    // 用户 2026-10-07 定的两条,这一页就是照它们做的:
    //   · 「**只看自我介绍**」—— 只给他「她是什么样的人」那几行;
    //   · 「**要,先问我**」—— 存之前先弹一句问要不要付那约 11 分钟。
    //
    // ★★ 顺序是定死的,别换:
    //   改字 → 按「保存」→ **先校验**(不过就当场说原因并**留在这一页**) →
    //   有变化才弹那句确认 → 他点头才真的写盘 + 踢一脚预热。
    //   把校验放到确认之后,症状就是「他点了『现在改』,然后又被告知不行」——
    //   白点一次,而且他会以为是自己点错了。
    //
    // ★ 这一页**不碰 prefs,也不碰 config**:读走 [AiAgent.currentPersona] /
    //   [AiAgent.factoryPersona],验走 [AiAgent.personaProblem],写走 [AiAgent.savePersona]。
    //   哪份算数、写到哪去,只有那一处说了算。
    // ==================================================================
    private fun showPersonaEdit(onClosed: () -> Unit = {}) {
        val a = agent()
        // ★ 比对和预填都用**此刻真正在生效的那份** —— 没改过的人拿到的是出厂那段。
        //   拿「存的那份」(空串)去比的话,他一进来、看一眼、直接按「保存」,
        //   会被判成「改了」,于是白付一次 11 分钟。这是这一页最容易犯的错。
        val nowText = a.currentPersona().ifBlank { a.factoryPersona() }

        val box = column()
        // ★ 换玻璃之前这个框**既没背景也没字色** —— 字色吃的是深色主题给的近白,
        //   压在约七成白的玻璃上看不见;背景是系统默认那种亮色框。
        //   [glassInput] 给的是房间里那套深色小圆角框(和「输入配对码」同一个)。
        val ed = glassInput().apply {
            setText(nowText)
            textSize = 13f
            gravity = Gravity.TOP
            minLines = 6
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        fun labeled(t: String, v: View) {
            box.addView(TextView(this).apply {
                text = t; textSize = 12f; setTextColor(getColor(R.color.glass_sub))
                setPadding(dp(2), dp(12), dp(2), dp(2))
            })
            box.addView(v, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        labeled("她是什么样的人(她的自我介绍)", ed)


        // ★ 回程 = 「回到她的性格那一页」—— 因为这一页是**那一页关掉之后**才开的,
        //   底下没有别的东西可回。两处出口(「取消」「保存」)本来就写着 `showHerPanel(onClosed)`,
        //   这一句管的是**别的**出口(系统返回键、点到外面)。
        glassReturn = { showHerPanel(onClosed) }
        val d = glassBuilder("她是什么样的人")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("保存", null)
            .setNegativeButton("取消") { _, _ -> showHerPanel(onClosed) }
            .show()
            .glassified()

        // ★ 自己接管「保存」那一颗(所以上面 `setPositiveButton("保存", null)` 传 null)。
        //   默认那颗按下去**先关窗再回调**,校验不过就没法把他留在这一页上了。
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val typed = ed.text.toString()
            // ① 先验。不过就**留在这儿**把原因说了 —— 不推他去下一层再告诉他白等。
            val bad = a.personaProblem(typed)
            if (bad != null) {
                toast(bad)
                return@setOnClickListener
            }
            val next = typed.trim()
            // ② 没变化就什么都不做 —— 尤其别白付那 11 分钟。
            if (next == nowText.trim()) {
                toast("和现在的一样,不用重算。")
                d.dismiss()
                showHerPanel(onClosed)
                return@setOnClickListener
            }
            // ③ 真的改了 → 按他自己定的那条,先问一句。
            // ★★ 这一层**得先把下面那一层关掉**:不然就是两层玻璃叠着(见 [glassLive]),
            //   而且底下那层的输入框会从他这段话里透上来。
            d.dismiss()
            glassReturn = { showPersonaEdit(onClosed) }
            glassBuilder("要重算一遍吗?")
                .setMessage(
                    "她一开口之前,得先把「她是谁 + 怎么干活」那一整段读一遍;" +
                        "机器把这段存起来复用,所以平时开口是快的。\n\n" +
                        "你这一改,存的那份就对不上了 —— **下一次开口要重新算一遍," +
                        "大约 11 分钟**(不烫、有电的时候)。\n\n" +
                        "★ 那 11 分钟里她**照常跟你说话**,只是答话要绕到云端," +
                        "也就是说**那段时间你说的话会发出去**。\n" +
                        "★ 只此一次,算过就好了;不改就一直不用付。\n" +
                        "★ 想反悔:按「再想想」,一个字都没动。"
                )
                .setPositiveButton("现在改") { _, _ ->
                    // ★ 再验一遍不是多余:界面那道闸是为了「当场告诉他为什么」,
                    //   这道是为了「盘上那份一定拼得回去」。
                    val why = a.savePersona(this, next)
                    if (why != null) {
                        // 到这儿 = 界面说行、写盘说不行,两份判据分家了。
                        // ★ 必须**说出来**,否则就是「他以为存了、其实没存」——
                        //   那正是 [PersonaMath.problem] 存在的理由。理论上到不了,到了就得响。
                        toast(why)
                        ModelManager.get(this).trace("人设: ★ 界面放行了、写盘却拦下了 —— $why")
                        return@setPositiveButton
                    }
                    toast("改好了。她下次开口会先算一遍,约 11 分钟。")
                    // ★ 立刻踢一脚预热,不等他下次开房间 —— 口径和日志都是 HerBoot 的
                    //   (它已经有「同一时刻只有一个盯装载的线程」那道闸)。
                    HerBoot.ensure(this)
                    d.dismiss()
                    showHerPanel(onClosed)
                }
                .setNegativeButton("再想想") { _, _ -> showPersonaEdit(onClosed) }
                .show()
                .glassified()
        }
    }

    // ==================================================================
    // 她的声音:804 个嗓子里挑一个
    //
    // ★ 为什么非有这一页(2026-10-05,他报的「语音好难听」):
    //
    //   内嵌那份 VITS 模型里有 **804 个嗓子**,而代码一直**写死用第 0 个**
    //   (`tts.generate(text, 0, …)`)。等于买了一柜子衣服只穿一件 ——
    //   他那句「说明可以换语音,这条路是通的」说的就是这件事。
    //
    // ★ 804 个没法一个个听,所以这一页**不是列表**,是「开盲盒」:
    //   上一个 / 试听 / 下一个,听到顺耳的按「就她」。
    //   挑中的只是**一个数字**(存进 PREFS_VOICE/KEY_SPEAKER),
    //   读它的是 SherpaVoice —— 同一份设置,没有第二处真相。
    // ==================================================================

    /** 试听念的那句话。★ 短的 —— 他可能连按几十次,长句子会让每次试听都等很久。 */
    private val AUDITION_LINE = "你好呀,我是 ConMarn。今天天气不错。"

    private fun showVoicePick() {
        val p = getSharedPreferences(PREFS_VOICE, MODE_PRIVATE)
        val total = SherpaVoice.speakerCount()
        var cur = VoiceMath.clampSpeaker(p.getInt(KEY_SPEAKER, DEF_SPEAKER), total)

        val big = TextView(this).apply {
            textSize = 22f; gravity = Gravity.CENTER
            // ★ 换玻璃之前这儿**根本没有 setTextColor** —— 靠的是深色主题给的近白默认色,
            //   压在玻璃上正好是「看不见」。这一页现在收在 [showSettings] 里没挂出来,
            //   但挂回来那天不能再踩一次同样的坑。
            setTextColor(getColor(R.color.glass_text))
            setPadding(0, dp(10), 0, dp(4))
        }
        val sub = TextView(this).apply {
            textSize = 12f; gravity = Gravity.CENTER
            setTextColor(getColor(R.color.glass_dim)); setPadding(0, 0, 0, dp(10))
        }
        var dlg: AlertDialog? = null

        /** 存下来。★ 只存数字 —— 引擎那边下次说话自己会读。 */
        fun commit() {
            p.edit().putInt(KEY_SPEAKER, cur).apply()
            ModelManager.get(this@ConMarnActivity).trace("房间:她的声音换成第 $cur 号")
        }

        /**
         * 重画那两行字。
         *
         * ★ 它必须排在 [audition] **前面** —— Kotlin 的局部函数**不能前向引用**,
         *   谁写在后面谁就还不能被调用。这一条不看编译报错很难想到。
         */
        fun refresh() {
            if (total <= 0) {
                big.text = "嗓子还没装好"
                sub.text = "模型没就绪 —— 这会儿点了也没声音。"
                return
            }
            big.text = "第 $cur 号"
            sub.text = "共 $total 个"
        }

        /**
         * 试听。
         *
         * ★ `queue = false` 是**故意的**,不是随手写的:连按十次「下一个」,
         *   排队的话就是十句话接着念完 —— 他会听见一个停不下来的她,
         *   而且那十几秒里按什么都没用(worker 是独线程)。
         *   顶掉旧的 = 每次试听都是「现在这一个」。
         */
        fun audition() {
            if (total <= 0) return
            commit()
            // ★ `queue = false` **必须具名传** —— `speak` 最后一个是 Boolean 参数,
            //   尾随 lambda 那个语法在这儿编译不过(onDone 排在 queue 前面)。
            val ok = SherpaVoice.speak(
                this@ConMarnActivity, AUDITION_LINE, onDone = {}, queue = false)
            // ★ 没受理就得吭一声。试听键按下去**既没声音也没提示**,
            //   他听到的结论会是「这个号不好听」,于是一直按下去 —— 白按。
            if (!ok) Toast.makeText(
                this@ConMarnActivity, "这会儿发不出声(嗓子没就绪)",
                Toast.LENGTH_SHORT).show()
            refresh()
        }

        val line = TextView(this).apply {
            textSize = 12f; setTextColor(getColor(R.color.glass_dim))
            setPadding(dp(2), dp(8), dp(2), 0)
            text = "804 个没法一个个听,所以是「开盲盒」:按「下一个」听一个,顺耳就按" +
                "「就她」。挑中之后我说的话全用这个嗓子 —— 打字、说话、主动开口,都是它。"
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, w: Float, act: () -> Unit) {
            row.addView(herButton(label, 13f) { act() }, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, w))
        }
        btn("◀ 上一个", 1f) {
            cur = VoiceMath.stepSpeaker(cur, total, -1); audition()
        }
        btn("试听这个", 1f) { audition() }
        btn("下一个 ▶", 1f) {
            cur = VoiceMath.stepSpeaker(cur, total, 1); audition()
        }
        val keep = herButton("就她", 14f) { commit(); dlg?.dismiss() }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(8))
            addView(big); addView(sub); addView(row); addView(keep); addView(line)
        }
        refresh()
        dlg = pageDialog("她的声音", box)
    }

    // ==================================================================
    // API 控制中心(三级)
    //
    // ★★ 一条架构主张:**一个用途 = 一只手**。
    //    这里管的和 `use_hand` 执行的是**同一份数据**(`ApiStore` ↔ `CloudHand`)。
    //    不许有第二张表 —— 否则「面板里开了、她说没这能力」是必然会发生的事,
    //    而且症状极难查。这条是**这个项目已经吃过一次的病**(工具表抄了三遍)。
    //
    // 三级的分工:
    //   二级 —— 按用途列,每行**直接写出当前用的是谁**(答案不许藏在三级页里)
    //   三级 —— 这个用途下的配置档(CC Switch 那种):切 / 加 / 改 / 删 / 测
    //   用量与调试 —— 另开一个框,因为它回答的是另一个问题
    // ==================================================================

    // ==================================================================
    // 透明磨砂玻璃(2026-10-08)—— ⚙ 里那一整套面板
    //
    // 用户原话:「**能不能把那个她的房间的设置面板里整个换成透明磨砂玻璃**」。
    //
    // ★★ 这台机器**没有真的背景模糊** —— `ro.surface_flinger.supports_background_blur`
    //    是空的(2026-10-08 真机 getprop 查过)。所谓「磨砂」是**画出来的**:
    //    半透明 + 顶上那道高光 + 一道白棱。全部在 `drawable/dialog_glass.xml` 里。
    //
    // ★ 这一套是**第二次用**:第一次是 2026-10-08 的「输入配对码」弹窗
    //   (`MainActivity.showInputDialog`),那一段就是范本,已经真机验过。
    //   下面这两个是把它抽出来一次,不是抄十遍。
    //
    // ★★ 三个必须由这里补的坑(范本里踩过的,一个都不能省):
    //    ① 换了 `windowBackground`,9-patch 自带的内衬就没了 → 内容贴着圆角,
    //       要在**内容根**上把内衬补回来(见 [glassified]);
    //    ② 房间的主主题 `Theme.Touchpad` 是**深色**的 → 弹窗里的字默认是白的,
    //       压在约七成白的玻璃上**看不见**。主题的 `textColorPrimary` 只管得了正文,
    //       **按钮它管不到** —— 所以正文和按钮都得在这里逐个给死色;
    //    ③ 玻璃上的输入框要换成深色小框(见 [glassInput]),否则是亮框上的超浅色字。
    //
    // ★ 明确不在这里的两处:
    //    · **打字确认框**(三层风险闸那个,`AiAgent.onConfirm` 的落地)—— 它不是设置面板,
    //      这一轮一个字不动;
    //    · **`TimePickerDialog`**([pickQuietTime])—— 系统自带的整块不透明弹窗,
    //      套上这个透明 `windowBackground` 会读不清,故意留着。
    // ==================================================================

    // ==================================================================
    // ★★ 两层玻璃不能叠着:底下那层的字会从上面那层的半透明底里透上来(真机上见过)。
    //    所以「往里走一层」一律是**先关掉这一层、再开子页**。代价是「关掉子页回哪儿」得自己记。
    //
    // ★ 只有**最后一层**关上时才算回程 —— 往里走的那一瞬间也会关掉一层,那时候子页已经
    //   开好了([glassLive] 里有人),那不是回程,别把上一层又开出来盖上去。
    // ★ 子页是**同步**建出来的,而父页那句 `dismiss()` 只是投了个消息、要晚一步才跑到 ——
    //   所以这条判断是稳的:它不靠时序,靠的是「谁已经在集合里了」。
    // ==================================================================

    /** 现在开着的玻璃弹窗。判「是不是全关完了」只看它空不空 —— 见 [glassified]。 */
    private val glassLive = LinkedHashSet<AlertDialog>()

    /**
     * 关掉最后一层玻璃之后要开的东西(没有 = 就这么回到房间)。每一页开的时候自己写。
     * ★ 它是**一个**变量,不是一摞 —— 因为同时只会有一层活着,回程只有一条。
     */
    private var glassReturn: (() -> Unit)? = null

    /**
     * 现在就办回程。
     * ★ 只用在「这一层关掉之后要开的是**系统**弹窗」那种地方(见 [showQuietIntro]):
     *   那儿要是不先办,这一层自己那个晚一步的关闭会把列表**盖到系统弹窗上面**去。
     */
    private fun glassReturnNow() {
        if (isFinishing || isDestroyed) return
        val r = glassReturn ?: return
        glassReturn = null
        r()
    }

    /** 玻璃弹窗的 Builder。七处构造点全部走它,别再各写各的 `AlertDialog.Builder(this)`。 */
    private fun glassBuilder(title: CharSequence?): AlertDialog.Builder =
        AlertDialog.Builder(this, R.style.Theme_Touchpad_GlassDialog).setTitle(title)

    /**
     * `show()` **之后**必须调一次。
     *
     * ★ `getButton()` 在**那颗按钮压根没加过**时返回 null ⇒ 必须 `?.`。
     *   范本(`MainActivity.showInputDialog`)两颗按钮都设了,所以那个坑没暴露;
     *   这一摊里有只用一颗按钮的弹窗(`showSettings` / `pageDialog`),
     *   还有用三颗的(`showQuietIntro`)。
     */
    private fun AlertDialog.glassified(): AlertDialog {
        // ★ 谁还开着、什么时候**全关完了** —— 这就是「别把两层玻璃叠起来」的全部机制。
        //   理由和那条「不能叠」的来由见 [glassLive] 上面那段。
        glassLive.add(this)
        setOnDismissListener {
            glassLive.remove(this)
            if (glassLive.isEmpty()) glassReturnNow()
        }
        findViewById<TextView>(android.R.id.message)?.setTextColor(getColor(R.color.glass_text))
        getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(getColor(R.color.glass_text))
        getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(getColor(R.color.glass_dim))
        getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(getColor(R.color.glass_dim))
        // ★ 补的是**内容根**,不是 decorView —— decorView 那一层会被系统的窗口内边距重新摆,
        //   在它上面补的 padding 会丢。
        (window?.decorView?.findViewById<View>(android.R.id.content) ?: window?.decorView)
            ?.setPadding(dp(14), dp(12), dp(14), dp(8))
        return this
    }

    /**
     * 玻璃上的输入框 —— 深色小圆角框 + 浅色字,照 [MainActivity.showInputDialog] 那一套。
     *
     * ★ 这里**不设 textSize**:调用方各有各的字号诉求(「她是什么样的人」那个多行框是 13f,
     *   API 那几个档位框一直用的是默认值)。统一设了等于顺手改掉别人的设计。
     */
    private fun glassInput(): EditText = EditText(this).apply {
        setBackgroundResource(R.drawable.her_input_bg)
        setTextColor(getColor(R.color.text_primary))
        setHintTextColor(0x8AF2F3F5.toInt())
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    /**
     * 三级页共用的弹窗外壳。★ 必须套 ScrollView,理由见 [showSettings] 那段注释。
     *
     * ★ 回程在这儿**写死成「⚙ 列表」** —— 这一摊里每一层本来都是开着 ⚙ 列表点的,
     *   所以底下除了它没别的可回。
     * ★★ **要往里再挂一层新页的人注意**:回程是**一个**变量,你在 `show()` 之前
     *   把它改成「回到你自己那一页」就行(照 [showPersonaEdit] 的样子);
     *   不改的话,新页一关就会跳过你这一层、直接落到 ⚙ 列表。
     */
    /**
     * **换一层:先关这一层,再开下一层 —— 但不让「关」的那一下触发回程。**
     *
     * ★★ 他 2026-11-06 的原话:「然后返回要跳到上一层,现在跳多了」。
     *   原因是这两件事在时间上分开:关掉当前玻璃 → [glassLive] 空 → [glassReturnNow]
     *   立刻执行(把 `glassReturn` 干掉并调它)→ **先跳回 ⚙ 列表** → 下一层才弹上来。
     *   所以他把 `dlg?.dismiss(); showXxx()` 换成这一句就不会跳过头。
     *
     * 为什么是把 `glassReturn` 置空、而不是加一个「别回程」的开关:
     * 回程整套机制就是「全关完了就执行一次,执行完置空」。置空 = 这一次没有回程,
     * 下一层自己会用 [pageDialog] 重新写上一个 —— 不需要再多一个状态。
     */
    private fun swapPage(from: AlertDialog?, open: () -> Unit) {
        glassReturn = null
        from?.dismiss()
        open()
    }

    /**
     * 把所有玻璃层一起收掉，**不留回程**。
     *
     * ★ 用在「我们从外面回来了，要停在某一页」的时刻（OAuth 授权回来，见 onResume）。
     *   逐层 `dismiss()` 时最后一层会触发 [glassReturnNow] —— 所以先把 `glassReturn`
     *   置空（和 [swapPage] 同一个理由），否则会先跳一层回去再盖上来。
     */
    private fun closeGlassAll() {
        glassReturn = null
        glassLive.toList().forEach { runCatching { it.dismiss() } }
    }

    private fun pageDialog(title: String, box: View): AlertDialog {
        glassReturn = { showSettings() }
        return glassBuilder(title)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("好", null)
            .show()
            .glassified()
    }

    private fun column(vararg pad: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(4), dp(20), dp(8))
    }

    /**
     * 正文小字。
     *
     * ★ 2026-11-06 真机上抓到 `**` 原样出现在屏幕上（「**删掉** = …」）——
     *   这里的字是直接画出来的，没有任何渲染器会把星号变成粗体。
     *   所以出口处统一抹掉，写文案时不必再提心吊胆。
     */
    private fun hint(text: String): TextView = TextView(this).apply {
        textSize = 11f
        setTextColor(getColor(R.color.glass_dim))
        setPadding(dp(2), dp(12), dp(2), 0)
        this.text = text.replace("**", "")
    }

    /**
     * 区块标题。★ 和 [showHerPanel] 里那份同款式 —— 13f / 她的粉 / 上面留一大截白。
     *
     * ★ 用的色是 [R.color.glass_accent] 而**不是**原来那个 `0xFFE05B8E`:后者压在
     *   约七成白的玻璃上对比度只有 3:1 上下,13sp 的小字会吃力(这是 2026-10-08
     *   换玻璃那一轮定的规矩,深色按钮上那份粉一个字不动)。
     */
    private fun sectionTitle(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(getColor(R.color.glass_accent))
        setPadding(dp(2), dp(22), dp(2), 0)
    }

    /**
     * 一整行的布局参数。
     *
     * ★ 记忆库里**每一行都得占满整宽** —— `box.addView(row)` 不传参数时默认 wrap_content,
     *   在竖排 LinearLayout 里会挤成靠左一小团,点起来一半是空的(那正是「看得见、按不动」的成因之一)。
     */
    private fun fullRow() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** 二级:按用途列。 */
    /**
     * **模型清单**（三层：一类活 → 这一类的几家 → 一家的四个格子）。
     *
     * 他 2026-11-06 的原话：「太麻烦了，我需要在模型清单中用多层界面来填 ——
     * 比如看图，点进去，一行填名字、一行填地址、一行填模型、一行填 key，
     * 然后视频、音频等等功能之后都可以自己加……类似一个更高端的 ccSwitch」。
     *
     * 所以这一层**只列用途**，每家一个「N 个 / 正在用的是谁」的读数。
     * ★ 说明全写在这一页和里面两层（项目那条规矩：解释文字进设置）。
     */
    /**
     * **模型与接口** —— 语言模型与数据接口，一处管理。
     *
     * ★★ 他 2026-11-06 的原话:「api控制中心和模型清单感觉是一个东西,不是吗?」
     *   「能不能把模型清单和api控制中心进行一个有机合并」。
     *   **对。** 从用户那边看,两页回答的是同一个问题:「她这项能力由谁提供、key 填在哪」。
     *   分成两页的代价不是「多一步」,是他**根本找不到** —— 他先点错了两次。
     *
     * 所以合并的判据不是「放在同一页」,是**三层长得一模一样**:
     *
     * | 层 | 说什么 |
     * |---|---|
     * | 一 | 她能为哪些事找外面的服务(文字 / 看图 / 写代码 / 天气 / 路线 / 搜索) |
     * | 二 | 这件事现在由谁做、还能换成谁 |
     * | 三 | 填 key(预置的那一家地址和模型名已经填好了) |
     *
     * ★ 两组的**数据源仍然是两套**([AiAgent.Config.endpoints] 与 [ApiStore]),
     *   这一点没有合并 —— 它们本来就不是一种东西(一个回一句话,一个回一份数据)。
     *   合并的是**入口和走法**:用户不会再去想「这是脑子还是手」,他只看见「她能干什么」。
     */
    private fun showLinks() {
        val box = column()
        var dlg: AlertDialog? = null
        val c = agent().config
        val eps = c.endpoints

        fun line(title: String, sub: String, open: () -> Unit): View {
            val row = settingRow(title, sub, {})
            row.setOnClickListener { swapPage(dlg) { open() } }
            box.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return row
        }

        fun addButton(text: String, open: () -> Unit) {
            box.addView(herButton(text, 14f) { swapPage(dlg) { open() } }.apply {
                setPadding(dp(18), dp(10), dp(18), dp(10))
            })
        }

        // ── 语言模型:一类用途 → 一个模型 ──
        box.addView(sectionTitle("语言模型 · 负责回答"))
        for ((key, label) in c.purposeList) {
            val mine = eps.filter { key in it.can }
            val ready = mine.filter { RouteMath.viable(it) }
            val now = RouteMath.pick(eps, key)
            val sub = when {
                mine.isEmpty() -> "未配置，调用时使用「云端总闸」那一项"
                ready.isEmpty() -> "${mine.size} 个未填写完整，无法使用"
                else -> "${now?.label}（${now?.model}）· 共 ${ready.size} 个可用"
            }
            val row = line(label, sub) { showModelPurpose(key) }
            // ★ 内置那几样删不掉（它们对应代码里固定的几条路）；他自己加的可以长按删掉。
            if (RouteMath.PURPOSES.none { it.first == key }) {
                row.setOnLongClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("删掉「$label」这一类？")
                        .setMessage(
                            "这一类会从清单上消失；填在这一类下面的模型也会被摘掉这一项能力，" +
                                "如果它本来还能干别的，那些照样留着。"
                        )
                        .setPositiveButton("删掉") { _, _ -> removePurpose(key) }
                        .setNegativeButton("算了", null)
                        .show()
                    true
                }
            }
        }
        addButton("＋ 加一类功能") { askNewPurpose() }

        // ── 数据接口:一项能力 → 一个服务 ──
        box.addView(sectionTitle("数据接口 · 负责提供数据"))
        val tools = ApiStore.tools()
        val apis = ApiStore.purposesWithUsage()
        if (apis.isEmpty()) box.addView(hint("一条都没有了。删除是不可逆的。"))
        for (p in apis) {
            val n = tools.count { it.purpose == p.id }
            line(p.label, ApiMath.summary(p) + " · $n 个工具") { showApiPurpose(p.id) }
        }
        addButton("＋ 新建 API") { showApiNew() }
        addButton("＋ 从目录添加") { showConnectorList() }

        // ── MCP 服务器：工具清单由它自己报 ──
        box.addView(sectionTitle("MCP 服务器 · 工具清单由服务器提供"))
        val mcps = McpStore.servers()
        if (mcps.isEmpty()) {
            box.addView(hint("尚未添加。添加后连一次，它会自行报告有哪些工具。"))
        }
        for (s in mcps) {
            val n = McpStore.toolsOf(s.id).size
            val sub = when {
                !s.enabled -> "已停用"
                s.fetchedAtMs == 0L -> "尚未获取工具清单"
                else -> "$n 个工具 · 上次获取 ${agoText(s.fetchedAtMs)}"
            }
            line(s.label, sub) { showMcpServer(s.id) }
        }
        addButton("＋ 添加 MCP 服务器") { showMcpEdit(null) }

        // ── 消息代理：发一条、收一段 ──
        box.addView(sectionTitle("消息代理 · MQTT"))
        val brokers = MqttStore.all()
        if (brokers.isEmpty()) {
            box.addView(hint("尚未添加。填一个地址就能连；接智能家居通常就是这一步。"))
        }
        for (b in brokers) {
            val sub = when {
                !b.enabled -> "已停用"
                else -> "${b.host}:${b.port}${if (b.tls) " · TLS" else ""} · 2 个工具"
            }
            line(b.label, sub) { showMqtt(b.id) }
        }
        addButton("＋ 添加消息代理") { showMqttEdit(null) }

        // ── 蓝牙：这台手机自己的无线电 ──
        box.addView(sectionTitle("蓝牙 · 这台手机的无线电"))
        val bleSub = when {
            !BleClient.supported(this) -> "这台机器没有蓝牙"
            !BleClient.enabled(this) -> "蓝牙未开启 · 开启后可扫描"
            !BleClient.canScan(this) || !BleClient.canConnect(this) -> "尚未授权 · 4 个工具"
            else -> "已就绪 · 4 个工具"
        }
        line("蓝牙", bleSub) { showBle() }

        dlg = pageDialog("模型与接口", box)
    }

    /**
     * 蓝牙：状态 + 一次扫描。
     *
     * ★ 这里**不放「每台设备一个开关」**。蓝牙设备没有统一的语义，一个厂家
     *   一套编号和字节；做出「一键开灯」只会是给某几台设备写死的捷径，
     *   而不是「支持蓝牙设备」。所以这里给的是两件实在东西：现在能不能用，
     *   以及扫得到哪些设备（连上之后要读要写，由她说，按设备手册的编号走）。
     */
    private fun showBle() {
        val box = column()
        val act = this
        box.addView(hint("扫描附近设备、连接、读一项特征值、写一项特征值。每一项的具体编号与字节由设备手册规定。"))
        box.addView(hint(
            when {
                !BleClient.supported(act) -> "这台机器没有蓝牙。"
                !BleClient.enabled(act) -> "蓝牙未开启。打开手机蓝牙后再回到这里。"
                !BleClient.canScan(act) || !BleClient.canConnect(act) -> "尚未授权。第一次扫描或连接时系统会询问。"
                else -> "已授权，可以使用。"
            }
        ))
        val out = hint("")
        box.addView(out)

        box.addView(herButton("扫描一次（6 秒）", 14f) {
            if (!BleClient.supported(act)) {
                out.text = "这台机器没有蓝牙。"
                return@herButton
            }
            if (!BleClient.enabled(act)) {
                out.text = "蓝牙未开启。"
                return@herButton
            }
            out.text = "正在扫描…"
            Thread {
                val granted = if (BleClient.canScan(act)) true else BleHand.askGrant(act)
                val msg = if (!granted) {
                    "未取得扫描权限。"
                } else {
                    runCatching { BleClient.scan(act, 6) }.fold(
                        onSuccess = { devs ->
                            if (devs.isEmpty()) "6 秒内未扫描到设备。"
                            else devs.joinToString("\n") {
                                "${it.name.ifBlank { "（无名称）" }} · ${it.address} · 信号 ${it.rssi}"
                            }
                        },
                        onFailure = { "扫描失败：" + (it.message ?: it.javaClass.simpleName) },
                    )
                }
                runOnUiThread { out.text = msg }
            }.start()
        }.apply { setPadding(dp(18), dp(10), dp(18), dp(10)) })

        val dlg = pageDialog("蓝牙", box)
        glassReturn = { showLinks() }
    }

    /** 一台 MQTT 代理。 */
    private fun showMqtt(id: String) {
        val b = MqttStore.broker(id) ?: run { showLinks(); return }
        val box = column()
        var dlg: AlertDialog? = null

        box.addView(hint("地址：${b.host}:${b.port}${if (b.tls) "（TLS）" else ""}"))
        box.addView(hint(
            if (b.username.isBlank()) "不需要账号。"
            else "账号：${b.username} · 密码${if (b.password.isBlank()) "没填" else "已保存"}"
        ))
        box.addView(hint("她在这一台上能做的事：往一个主题发消息；订阅一个主题等几秒，把这段时间里收到的收回来。"))

        fun row(title: String, sub: String, act: () -> Unit) {
            val r = settingRow(title, sub, {})
            r.setOnClickListener { act() }
            box.addView(r, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        row("测一测", "连一次，拿到回执就断开。") {
            toast("试着连一下…")
            Thread {
                val msg = MqttClient.ping(b)
                runOnUiThread { toast(msg) }
            }.start()
        }
        row("编辑", "地址、端口、账号、TLS。") { swapPage(dlg) { showMqttEdit(b) } }
        row(if (b.enabled) "停用" else "启用", "停用之后这一台从她的菜单上消失。") {
            MqttStore.upsert(b.copy(enabled = !b.enabled))
            toast(if (b.enabled) "停用了。" else "启用了。")
            swapPage(dlg) { showLinks() }
        }
        row("删除", "删了之后这一台从菜单上消失，盘上也不留。") {
            glassBuilder("删掉「${b.label}」？")
                .setMessage("它不会影响那台设备本身，只是我们不再连它。")
                .setPositiveButton("删掉") { _, _ ->
                    MqttStore.remove(id)
                    toast("删掉了。")
                    glassReturn = { showLinks() }
                }
                .setNegativeButton("算了") { _, _ -> glassReturn = { showMqtt(id) } }
                .show()
                .glassified()
            glassReturn = { showMqtt(id) }
        }

        dlg = pageDialog(b.label, box)
        glassReturn = { showLinks() }
    }

    /** 加 / 改一台 MQTT 代理。 */
    private fun showMqttEdit(existing: MqttBroker?) {
        val box = column()
        var page: AlertDialog? = null

        fun labeled(t: String, e: EditText) {
            box.addView(TextView(this).apply {
                text = t; textSize = 12f; setTextColor(getColor(R.color.glass_sub))
                setPadding(dp(2), dp(10), dp(2), dp(2))
            })
            box.addView(e, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val fLabel = glassInput().apply {
            hint = "名字，比如「家里的 Home Assistant」"
            setText(existing?.label.orEmpty())
        }
        val fHost = glassInput().apply {
            hint = "地址，比如 192.168.1.10"
            setText(existing?.host.orEmpty())
        }
        val fPort = glassInput().apply {
            hint = "端口，一般 1883；带 TLS 一般 8883"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText((existing?.port ?: 1883).toString())
        }
        val fUser = glassInput().apply {
            hint = "账号（不需要就留空）"
            setText(existing?.username.orEmpty())
        }
        val fPass = glassInput().apply {
            hint = if (existing != null && existing.password.isNotBlank())
                "留空 = 不改这个密码"
            else "密码（不需要就留空）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val fTls = glassInput().apply {
            hint = "走 TLS 就填 yes，不走留空"
            setText(if (existing?.tls == true) "yes" else "")
        }

        labeled("名字", fLabel)
        labeled("地址", fHost)
        labeled("端口", fPort)
        labeled("账号", fUser)
        labeled("密码", fPass)
        labeled("TLS", fTls)
        box.addView(hint("这一版不做后台常驻推送：她能「发一条」，也能「订阅一段时间把收到的收回来」，不会一直被消息叫醒。"))

        page = glassBuilder(if (existing == null) "添加消息代理" else "改「${existing.label}」")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                val label = fLabel.text.toString().trim()
                val host = fHost.text.toString().trim()
                val port = fPort.text.toString().trim().toIntOrNull()
                if (label.isEmpty() || host.isEmpty()) {
                    toast("名字和地址都得填。")
                    return@setPositiveButton
                }
                if (port == null || port !in 1..65535) {
                    toast("端口要填 1 到 65535 之间的数字。")
                    return@setPositiveButton
                }
                val typedPass = fPass.text?.toString().orEmpty()
                val tls = fTls.text.toString().trim().lowercase() in
                    setOf("yes", "y", "true", "1", "tls", "是")
                val next = MqttBroker(
                    id = existing?.id ?: MqttStore.newId(label),
                    label = label,
                    host = host,
                    port = port,
                    tls = tls,
                    clientId = existing?.clientId.orEmpty(),
                    username = fUser.text.toString().trim(),
                    // ★ 留空 = 不改。要清掉密码得走「编辑 → 填一个空格」这条路吗？
                    //   不 —— 这里给的是最安全的那一种：留空保留。清密码请删了重建。
                    password = if (typedPass.isBlank() && existing != null) existing.password
                    else typedPass,
                    enabled = existing?.enabled ?: true,
                )
                if (!MqttStore.upsert(next)) {
                    toast("写不进盘上，请重试。")
                    return@setPositiveButton
                }
                toast("保存了。")
                swapPage(page) { showMqtt(next.id) }
            }
            .setNegativeButton("取消") { _, _ -> swapPage(page) { showLinks() } }
            .show()
            .glassified()
        glassReturn = { showLinks() }
    }

    /** 连接器目录：预置好、点一下就能装的接口。 */
    private fun showConnectorList() {
        val box = column()
        var dlg: AlertDialog? = null
        box.addView(hint("选一项安装。安装后可在这一项里填写 key，并可以直接使用。"))
        for (c in ConnectorCatalog.all) {
            val has = ApiStore.purpose(c.id) != null
            val sub = c.vendor + " · " + c.note + if (has) "（已安装）" else ""
            val row = settingRow(c.name, sub, {})
            row.setOnClickListener { swapPage(dlg) { showConnectorPreview(c.id) } }
            box.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dlg = pageDialog("连接器目录", box)
        glassReturn = { showLinks() }
    }

    /** 安装前先看一眼：会多出哪几个工具。 */
    private fun showConnectorPreview(id: String) {
        val c = ConnectorCatalog.find(id) ?: run { showConnectorList(); return }
        val d = ConnectorCatalog.draft(c)
        val box = column()

        box.addView(TextView(this).apply {
            text = d.purpose.label + "　" + c.baseUrl
            textSize = 13f
            setTextColor(getColor(R.color.glass_text))
            setPadding(dp(2), dp(4), dp(2), dp(6))
        })
        box.addView(hint("安装后会增加 ${d.tools.size} 个工具："))
        d.tools.forEach { t ->
            val ps = t.params.joinToString("、") { it.name }
            box.addView(
                settingRow(t.name, t.desc.take(60) + if (ps.isNotEmpty()) "　参数：$ps" else "", {}),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        d.notes.forEach { box.addView(hint(it)) }
        if (ApiStore.purpose(c.id) != null) {
            box.addView(hint("已经装过这一项。重新安装会更新它的工具清单，已填的 key 和用量保留。"))
        }

        var page: AlertDialog? = null
        page = glassBuilder("安装「${c.name}」")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("安装") { _, _ ->
                // ★ 已经装过时，保留他填好的 key 和已经记下的用量 ——
                //   upsertPurpose 是整体替换，不这样合一下就是把 key 抹了，
                //   而界面上只会显示「(不需要 key)」，看起来像我们没保存。
                val cur = ApiStore.purpose(c.id)
                val purpose = if (cur == null) d.purpose else d.purpose.copy(
                    profiles = if (cur.profiles.isNotEmpty()) cur.profiles else d.purpose.profiles,
                    activeId = if (cur.activeId.isNotEmpty()) cur.activeId else d.purpose.activeId,
                    usage = cur.usage,
                )
                if (!ApiStore.upsertPurpose(purpose)) {
                    toast("未安装：写入失败，请重试。")
                    return@setPositiveButton
                }
                var ok = true
                d.tools.forEach { if (!ApiStore.upsertTool(it)) ok = false }
                if (!ok) {
                    toast("部分工具未写入成功，请重试。")
                    return@setPositiveButton
                }
                toast("已安装：${d.tools.size} 个工具。")
                swapPage(page) { showApiPurpose(c.id) }
            }
            .setNegativeButton("取消") { _, _ -> swapPage(page) { showConnectorList() } }
            .show()
            .glassified()
        glassReturn = { showConnectorList() }
    }

    /** 「刚刚 / N 分钟前 / N 小时前 / N 天前」。 */
    private fun agoText(ms: Long): String {
        val d = System.currentTimeMillis() - ms
        return when {
            d < 60_000L -> "刚刚"
            d < 3_600_000L -> "${d / 60_000L} 分钟前"
            d < 86_400_000L -> "${d / 3_600_000L} 小时前"
            else -> "${d / 86_400_000L} 天前"
        }
    }

    /** 一台 MCP 服务器：连通测试、刷新清单、编辑、删除。 */
    private fun showMcpServer(id: String) {
        val box = column()
        var dlg: AlertDialog? = null
        val s = McpStore.server(id) ?: run {
            box.addView(hint("这台服务器不存在（可能已被删除）。"))
            val p = pageDialog("MCP 服务器", box)
            glassReturn = { showLinks() }
            return
        }

        box.addView(TextView(this).apply {
            text = s.url
            textSize = 12f
            setTextColor(getColor(R.color.glass_sub))
            setPadding(dp(2), dp(4), dp(2), dp(8))
        })
        if (s.headers.isNotEmpty()) {
            box.addView(hint("请求头：" + s.headers.keys.joinToString("、")))
        }

        val tools = McpStore.toolsOf(s.id)
        if (tools.isEmpty()) {
            box.addView(hint("还没有工具清单。点「获取工具清单」。"))
        } else {
            box.addView(hint("共 ${tools.size} 个工具："))
            tools.take(8).forEach { t ->
                box.addView(settingRow(t.name, t.desc.take(60), {}))
            }
            if (tools.size > 8) box.addView(hint("另有 ${tools.size - 8} 个未列出。"))
        }

        val probeRow = settingRow("连通测试", "只做一次握手，不获取工具清单。", {})
        probeRow.setOnClickListener {
            toast("正在连接…")
            Thread {
                val msg = McpClient.probe(s)
                runOnUiThread { toast(msg) }
            }.start()
        }
        box.addView(probeRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val fetchRow = settingRow("获取工具清单", "连接成功后由服务器报告它能做什么。", {})
        fetchRow.setOnClickListener {
            toast("正在获取…")
            Thread {
                val msg = McpClient.refresh(s)
                runOnUiThread {
                    toast(msg)
                    swapPage(dlg) { showMcpServer(s.id) }
                }
            }.start()
        }
        box.addView(fetchRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val editRow = settingRow("编辑", "名称、地址、请求头。", {})
        editRow.setOnClickListener { swapPage(dlg) { showMcpEdit(s) } }
        box.addView(editRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val toggleRow = settingRow(if (s.enabled) "停用" else "启用", "", {})
        toggleRow.setOnClickListener {
            McpStore.upsert(s.copy(enabled = !s.enabled))
            swapPage(dlg) { showMcpServer(s.id) }
        }
        box.addView(toggleRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val delRow = settingRow("删除", "连同已获取的工具清单一并删除。", {})
        delRow.setOnClickListener {
            swapPage(dlg) {
                glassBuilder("删除「${s.label}」？")
                    .setMessage("它的 ${tools.size} 个工具会一起删除。")
                    .setPositiveButton("删除") { _, _ ->
                        McpStore.remove(s.id)
                        toast("已删除。")
                        glassReturn = { showLinks() }
                    }
                    .setNegativeButton("取消") { _, _ -> glassReturn = { showMcpServer(s.id) } }
                    .show()
                    .glassified()
                glassReturn = { showMcpServer(s.id) }
            }
        }
        box.addView(delRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dlg = pageDialog(s.label, box)
        glassReturn = { showLinks() }
    }

    /** 添加 / 编辑一台 MCP 服务器。 */
    private fun showMcpEdit(existing: McpServer?) {
        val box = column()
        val fLabel = glassInput().apply {
            hint = "名称"
            setText(existing?.label.orEmpty())
        }
        val fUrl = glassInput().apply {
            hint = "地址，例如 https://mcp.example.com/mcp"
            setText(existing?.url.orEmpty())
        }
        val fHead = glassInput().apply {
            hint = "请求头，每行一条，格式「键: 值」；不需要就留空"
            setText(McpMath.formatHeaders(existing?.headers.orEmpty()))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        fun labeled(t: String, e: EditText) {
            box.addView(TextView(this).apply {
                text = t; textSize = 12f; setTextColor(getColor(R.color.glass_sub))
                setPadding(dp(2), dp(10), dp(2), dp(2))
            })
            box.addView(e, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        labeled("名称", fLabel)
        labeled("地址", fUrl)
        labeled("请求头", fHead)
        box.addView(hint("这一版只支持通过地址访问的 MCP 服务器（Streamable HTTP），不支持本机命令方式。"))

        var page: AlertDialog? = null
        page = glassBuilder(if (existing == null) "添加 MCP 服务器" else "编辑 MCP 服务器")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                val label = fLabel.text.toString().trim()
                val url = fUrl.text.toString().trim()
                if (label.isEmpty() || url.isEmpty()) {
                    toast("名称和地址都要填写。")
                    return@setPositiveButton
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    toast("地址要以 http:// 或 https:// 开头。")
                    return@setPositiveButton
                }
                val headers = McpMath.parseHeaders(fHead.text.toString())
                val s = existing?.copy(label = label, url = url, headers = headers)
                    ?: McpServer(
                        id = McpClient.newId(label, McpStore.servers()),
                        label = label,
                        url = url,
                        headers = headers,
                    )
                if (!McpStore.upsert(s)) {
                    toast("未保存，写入失败，请重试。")
                    return@setPositiveButton
                }
                toast("已保存。")
                swapPage(page) { showMcpServer(s.id) }
            }
            .setNegativeButton("取消") { _, _ -> swapPage(page) { showLinks() } }
            .show()
            .glassified()
        glassReturn = { showLinks() }
    }

    /**
     * 「加一类功能」：只要一个名字（「视频」「语音」「听音」都行）。
     *
     * ★ 这里**不问他这一类要接哪家**：加完回到上一层，那一行自然带着自己的「＋」。
     *   两步分开，是因为「先有一个格子」和「往格子里放一家」本来就是两件事。
     */
    private fun askNewPurpose() {
        val c = agent().config
        val box = column()
        val f = glassInput().apply {
            setSingleLine()
            hint = "比如：视频、语音、听音"
            setTextColor(getColor(R.color.glass_text))
            setHintTextColor(getColor(R.color.glass_dim))
        }
        box.addView(f)
        // ★★ 这一页必须**走玻璃那一套**（[pageDialog]），不能用系统弹窗：
        //   上一层关掉时 [glassReturn] 会立刻办回程（⚙ 列表），系统弹窗不在
        //   [glassLive] 里，于是 ⚙ 列表会**盖到这一页上面** —— 他 2026-11-06 看到的就是这个。
        val page = pageDialog("加一类功能", box)
        // ★ 按钮的 listener 在 dismiss **之前**跑（AlertController 的 ButtonHandler 顺序），
        //   所以这里必须走 swapPage:直接 dismiss 的话回程会先跑一遍、再显式开一次 = 两层叠着。
        page.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val name = f.text.toString().trim()
            val err = purposeError(name, c.purposeList)
            if (err != null) {
                toast(err)
                return@setOnClickListener
            }
            c.purposes = customPurposes(c.purposeList).plus(name).joinToString(",")
            AiAgent.saveConfig(this, c)
            toast("已加上「$name」")
            swapPage(page) { showLinks() }
        }
        page.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
            swapPage(page) { showLinks() }
        }
        glassReturn = { showLinks() }
    }

    /** 删掉自己加的一类，顺手把它从每一家的「会什么」里摘掉。 */
    private fun removePurpose(key: String) {
        val c = agent().config
        c.purposes = customPurposes(c.purposeList).filter { it != key }.joinToString(",")
        val eps = c.endpoints.map { ep ->
            val left = ep.can - key
            if (left.isEmpty()) ep.copy(can = setOf(RouteMath.TEXT)) else ep.copy(can = left)
        }
        c.providers = RouteMath.format(eps, c.purposeList)
        AiAgent.saveConfig(this, c)
        toast("已删掉这一类")
        showLinks()
    }

    /** 清单里除了内置那三样以外的，就是他自己加的。 */
    private fun customPurposes(list: List<Pair<String, String>>): List<String> =
        list.filter { p -> RouteMath.PURPOSES.none { it.first == p.first } }.map { it.second }

    private fun purposeError(name: String, list: List<Pair<String, String>>): String? = when {
        name.isEmpty() -> "名字不能空"
        name.length > PURPOSE_MAX_CHARS -> "名字最多 $PURPOSE_MAX_CHARS 个字"
        list.any { it.second == name } -> "已经有「$name」了"
        else -> null
    }

    /** 第二层：这一类活下面有哪几家。 */
    private fun showModelPurpose(key: String) {
        val box = column()
        val all = agent().config.endpoints
        var dlg: AlertDialog? = null
        val now = RouteMath.pick(all, key)
        var shown = 0
        for ((i, ep) in all.withIndex()) {
            if (key !in ep.can) continue
            shown++
            val on = now != null && now.label == ep.label && now.model == ep.model && now.baseUrl == ep.baseUrl
            val bad = if (RouteMath.viable(ep)) "" else "  ⚠ 没填全"
            val sub = ep.model + bad + if (on) "  ★ 正在用" else ""
            val row = settingRow(ep.label, sub) {}
            row.setOnClickListener { swapPage(dlg) { showModelEdit(key, i) } }
            box.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (shown == 0) box.addView(hint("这一类尚未配置。点击下方按钮添加。"))

        box.addView(herButton("＋ 添加模型", 14f) {
            swapPage(dlg) { showModelPick(key) }
        }.apply {
            setPadding(dp(18), dp(10), dp(18), dp(10))
        })

        val page = pageDialog(RouteMath.purposeLabel(key, agent().config.purposeList), box)
        dlg = page
        // ★ 这一层的「上一级」是「连接」,不是 ⚙ 列表 —— pageDialog 的默认值是 ⚙,要改。
        glassReturn = { showLinks() }

        // ★ 「好」在这里是回去，不是保存 —— 这一层没有要保存的东西。
        //   保存只发生在下一层那个明文写着「保存」的钮上，别让两个「好」长得一样。
        page.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            // ★ 走 swapPage:直接 dismiss 的话回程会先跑一遍,再显式开一次 = 两层叠着。
            swapPage(page) { showLinks() }
        }
    }

    /**
     * **加一家的第一步：先选供应商,再填 key。**
     *
     * 他 2026-11-06 的原话：「最后有市面上绝大多数的模型,然后用户可选,
     * 这样就只填 key 就行了,反正按照 ccSwitch 那样来」。
     *
     * ★ 在这一层之前,「加一家」直接弹四个空格 —— 用户得知道那家的地址和模型名。
     *   那一屏把「接入一个模型」这件事的门槛卡在**懂接口**上,而这正是不该有的门槛。
     * ★ 表里没有的走「自定义」,四个格子照旧 —— 私有部署、内网网关、中转站
     *   都不在表里,而它们恰恰是最常见的一种。
     */
    private fun showModelPick(key: String) {
        val box = column()
        var dlg: AlertDialog? = null
        box.addView(hint("选一家,地址和模型会自动填好,你只要填 key。"))
        for (v in LlmCatalog.all) {
            val row = settingRow(v.name, "${v.vendor}　${v.note}", {})
            row.setOnClickListener {
                swapPage(dlg) { showModelEdit(key, -1, v) }
            }
            box.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val custom = settingRow("自定义", "表里没有的:私有部署、中转站、内网网关。四个格子自己填。", {})
        custom.setOnClickListener {
            swapPage(dlg) { showModelEdit(key, -1, null) }
        }
        box.addView(custom, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dlg = pageDialog("添加模型", box)
        // ★ 这一层的上一级是「这一类活下面的几家」,不是 ⚙ 列表。
        glassReturn = { showModelPurpose(key) }
    }

    /**
     * 第三层：一家的四个格子 + 三个动作。
     *
     * @param index 改哪一个（[agent] 的清单里的下标）；-1 = 新加一家。
     * @param vendor 从 [LlmCatalog] 里选的那一家；null = 自定义（格子全空）。
     */
    private fun showModelEdit(key: String, index: Int, vendor: LlmVendor? = null) {
        val box = column()
        val all = agent().config.endpoints.toMutableList()
        val cur = all.getOrNull(index)
        // ★ 先声明后赋值：三个钮的回调里都要 `dlg.dismiss()`，而框本身要等
        //   里面那些控件建完才弹得出来。
        var dlg: AlertDialog? = null

        fun field(title: String, value: String, secret: Boolean = false): EditText {
            box.addView(TextView(this).apply {
                text = title
                textSize = 12f
                setTextColor(getColor(R.color.glass_sub))
                setPadding(dp(2), dp(12), dp(2), dp(2))
            })
            return EditText(this).apply {
                setText(value)
                setSingleLine()
                setTextColor(getColor(R.color.glass_text))
                setHintTextColor(getColor(R.color.glass_dim))
                if (secret) {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }.also { box.addView(it) }
        }

        // ★ 新加一家时按选中的供应商预填；改一家时按盘上原来的值。两者不会同时成立。
        val fName = field("名字（自己起，方便认）", cur?.label ?: vendor?.name.orEmpty())
        val fUrl = field("地址", cur?.baseUrl ?: vendor?.endpoint.orEmpty())
        val fModel = field("模型", cur?.model ?: vendor?.models?.firstOrNull().orEmpty())
        val fKey = field("key（本机模型留空）", cur?.apiKey.orEmpty(), secret = true)

        // ★ 预置里那一家的特殊说明（火山方舟要填接入点 id 这类）—— 必须当场说，
        //   否则他会把模型名照抄进去，然后收到一句「model not found」。
        if (vendor != null && vendor.caveat.isNotBlank()) box.addView(hint(vendor.caveat))

        fun commit() {
            val c = agent().config
            c.providers = RouteMath.format(all, c.purposeList)
            AiAgent.saveConfig(this, c)
        }

        box.addView(herButton("保存", 15f, R.drawable.btn_her_primary) {
            val url = fUrl.text.toString().trim()
            val model = fModel.text.toString().trim()
            if (url.isEmpty() || model.isEmpty()) {
                toast("地址和模型两样都要填")
                return@herButton
            }
            val name = fName.text.toString().trim().ifEmpty { model }
            val ep = Endpoint(
                label = name.take(20),
                baseUrl = url,
                model = model,
                apiKey = fKey.text.toString().trim(),
                // ★ 编辑时**保留它原本还会的那几样**：一家同时干文字和看图时，
                //   在「看图」里改一次不该把文字那一档弄丢。
                // ★ 新加时按预置给的能力预填（[LlmCatalog.defaultCan]），
                //   自定义的至少声明文字 —— 一个什么都不声明的那家永远不会被挑中，
                //   而界面上看不出原因。
                can = when {
                    cur != null -> cur.can + key
                    vendor != null -> LlmCatalog.defaultCan(vendor) + key
                    else -> setOf(key, RouteMath.TEXT)
                },
            )
            if (index in all.indices) all[index] = ep else all.add(ep)
            commit()
            toast("已保存")
            swapPage(dlg) { showModelPurpose(key) }
        }.apply {
            setPadding(dp(18), dp(10), dp(18), dp(10))
        })

        // ★ 「测一测」是这一页最要紧的一颗钮：地址和模型名**写错了不会报错**，
        //   只会在某天真正要用的时候回一句看不懂的失败。这里花一次最小请求，
        //   把「通 / 404 模型名不对 / 401 key 不对 / 连不上」当场分成四种。
        // ★ 必须离开主线程：HttpURLConnection 在 UI 线程上直接抛异常，
        //   而那个异常长得像「连不上」—— 用户会去查网络，问题其实在我们这儿。
        box.addView(herButton("测一测", 14f) {
            val url = fUrl.text.toString().trim()
            val model = fModel.text.toString().trim()
            if (url.isEmpty() || model.isEmpty()) {
                toast("地址和模型两样都要填")
                return@herButton
            }
            val k = fKey.text.toString().trim()
            toast("正在发一条两个字的请求…")
            Thread {
                val msg = CloudHand.probeLlm(url, model, k)
                runOnUiThread { toast(msg) }
            }.start()
        }.apply {
            setPadding(dp(18), dp(10), dp(18), dp(10))
        })

        if (cur != null) {
            box.addView(herButton("切到它（排到最前）", 14f) {
                val ep = all.removeAt(index)
                all.add(0, ep)
                commit()
                toast("已设为这一类用途的默认模型")
                swapPage(dlg) { showModelPurpose(key) }
            }.apply {
                setPadding(dp(18), dp(10), dp(18), dp(10))
            })
            box.addView(herButton("删掉这家", 14f) {
                AlertDialog.Builder(this@ConMarnActivity)
                    .setTitle("删掉「${cur.label}」？")
                    .setMessage("只从模型清单里删掉，不会动别的东西。")
                    .setPositiveButton("删掉") { _, _ ->
                        if (index in all.indices) all.removeAt(index)
                        commit()
                        swapPage(dlg) { showModelPurpose(key) }
                    }
                    .setNegativeButton("算了", null)
                    .show()
            }.apply {
                setPadding(dp(18), dp(10), dp(18), dp(10))
            })
        }

        val label = RouteMath.purposeLabel(key, agent().config.purposeList)
        box.addView(hint(
            if (cur == null) "填完点「保存」。这一家会归在「$label」下面。"
            else "改完点「保存」。名字只影响这一页和账上的显示，不影响怎么调它。"
        ))
        val page = pageDialog(
            (if (cur == null) "新增 · " else "编辑 · ") + label, box
        )
        dlg = page
        // ★ 这一层的上一级是「这一类下面的几家」。
        glassReturn = { showModelPurpose(key) }
        // ★ 「好」= 不保存地回去。保存只认那个写着「保存」的钮。
        page.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            swapPage(page) { showModelPurpose(key) }
        }
    }

    // ── 工作区（2026-11-06）────────────────────────────────────────────────────
    //
    // 他问：「返回了成品，但是就一个界面，不能真正的生成在手机真正的桌面上的应用」。
    //
    // ★ 手机里没有 JDK / aapt2 / d8，**编不出 APK**（这不是没做，是做不了）。
    //   成品都是网页，所以让它以网页应用的身份上桌面：这里点「放到桌面」，
    //   系统会弹一次确认，桌面上就多一个图标，点开全屏跑（[WorkAppActivity]）。
    //
    // ★ 工作区在 `files/sandbox/rootfs/root/work`：**第三方文件管理器进不去**
    //   （Android 11+ 不让进 `Android/data`）。所以「成品在哪」这个问题的答案
    //   只能是这一页 —— 没有它，他做出来的东西等于拿不到。

    private fun workRoot(): File = File(Sandbox.rootfs(this), "root/work")

    /** 工作区里的文件（递归，最多 [WorkCodeMath.MAX_ENTRIES] 个，不列隐藏文件）。 */
    private fun workFiles(): List<File> {
        val root = workRoot()
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<File>()
        fun walk(d: File, depth: Int) {
            if (depth > 3 || out.size >= WorkCodeMath.MAX_ENTRIES) return
            val kids = d.listFiles()?.sortedBy { it.name } ?: return
            for (f in kids) {
                if (out.size >= WorkCodeMath.MAX_ENTRIES) return
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) walk(f, depth + 1) else out.add(f)
            }
        }
        walk(root, 0)
        return out
    }

    private fun sizeText(n: Long): String = when {
        n < 1024 -> "$n 字节"
        n < 1024 * 1024 -> "${n / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", n / 1048576.0)
    }

    /** 「工作区」：成品在哪、点开、放到桌面、删掉。 */
    /**
     * 「动作台账」：她每一次动设备 / 往外发东西，都在这里留下一条。
     *
     * ★ 这一页**只回答两件事**：她做了什么、成没成。分档规则、为什么不拦，
     *   都在上面的说明里一句说完（规矩：说明文字进设置）。
     */
    private fun showAudit() {
        val box = column()
        var dlg: AlertDialog? = null
        box.addView(hint(
            "记录两档动作：黄（改变这台手机或某台设备的状态）、红（内容发出去、花钱、不可逆）。" +
                "只读的操作不记。参数里的 key、密码一律写成 ***。全部只存在这台手机上。"
        ))
        box.addView(hint("这一版只记录，不拦截。"))
        val list = Audit.recent(120)
        if (list.isEmpty()) {
            box.addView(hint("还没有记录。"))
        }
        for (e in list) {
            val title = Audit.timeText(e.at) + " · " + e.level.label + " · " + e.hand + " · " + e.tool
            val sub = (if (e.ok) "成" else "没成：" + e.outcome) +
                (if (e.args.isBlank()) "" else " · " + e.args)
            val row = settingRow(title, sub, {})
            box.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (list.isNotEmpty()) {
            box.addView(herButton("清空台账", 14f) {
                glassBuilder("清空动作台账？")
                    .setMessage("清空之后这些记录找不回来。")
                    .setPositiveButton("清空") { _, _ ->
                        Audit.clear()
                        toast("清空了。")
                        swapPage(dlg) { showAudit() }
                    }
                    .setNegativeButton("算了") { _, _ -> glassReturn = { showAudit() } }
                    .show()
                    .glassified()
                glassReturn = { showAudit() }
            }.apply { setPadding(dp(18), dp(10), dp(18), dp(10)) })
        }
        dlg = pageDialog("动作台账", box)
        glassReturn = { showSettings() }
    }

    private fun showWorkArea() {
        val box = column()
        val root = workRoot()
        val files = workFiles()
        var dlg: AlertDialog? = null
        box.addView(sectionTitle("代码档做出来的东西"))

        if (files.isEmpty()) {
            box.addView(hint("还是空的。在工作台「代码」那一档让它做点什么，做出来的文件会落在这里。"))
        }
        for (f in files) {
            val rel = runCatching { f.relativeTo(root).path }.getOrNull() ?: f.name
            val row = settingRow(rel, sizeText(f.length())) {}
            row.setOnClickListener { openWorkFile(rel) }
            // ★ 只有网页能放桌面（2026-11-06 修）：原来每个文件都给「放到桌面」，
            //   而桌面图标点开一律是 WebView —— 一个 .py / .txt 放上去，点开是空白。
            //   「能上桌面的只有网页」这句话在下面写着，菜单就得照着做，不能自相矛盾。
            val isWeb = rel.lowercase().let { it.endsWith(".html") || it.endsWith(".htm") }
            row.setOnLongClickListener {
                if (!isWeb) {
                    askDeleteWorkFile(rel) { dlg?.dismiss(); showWorkArea() }
                    return@setOnLongClickListener true
                }
                AlertDialog.Builder(this@ConMarnActivity)
                    .setTitle(rel)
                    .setItems(arrayOf("放到桌面", "删掉")) { _, which ->
                        if (which == 0) pinToHome(rel) else askDeleteWorkFile(rel) { dlg?.dismiss(); showWorkArea() }
                    }
                    .show()
                true
            }
            box.addView(row, fullRow())
        }

        dlg = pageDialog("工作区", box)
    }

    private fun openWorkFile(rel: String) {
        val f = File(workRoot(), rel)
        if (!f.isFile) {
            toast("这个文件已经不在了")
            return
        }
        val name = rel.lowercase()
        if (name.endsWith(".html") || name.endsWith(".htm")) {
            startActivity(WorkAppActivity.intent(this, rel, rel))
            return
        }
        val body = try {
            if (f.length() > 2_000_000) "（文件太大，这里只看前 6000 字）\n" + f.bufferedReader().use {
                val c = CharArray(6_000); val n = it.read(c); String(c, 0, maxOf(n, 0))
            } else f.readText().take(6_000)
        } catch (e: Exception) {
            "读不出来：${e.message ?: "未知错误"}"
        }
        val tv = TextView(this).apply {
            text = body
            textSize = 12f
            setTextColor(getColor(R.color.glass_text))
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setTitle(rel)
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("好", null)
            .show()
    }

    private fun pinToHome(rel: String) {
        if (WorkAppActivity.pin(this, rel, rel)) {
            toast("系统会弹一次确认，点「添加」就上桌面了")
        } else {
            toast("这台桌面不支持往桌面放图标")
        }
    }

    private fun askDeleteWorkFile(rel: String, after: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("删掉「$rel」？")
            .setMessage("工作区里的文件删了就没了。桌面上那个图标会点不开（点开直接退出）。")
            .setPositiveButton("删掉") { _, _ ->
                val ok = runCatching { File(workRoot(), rel).delete() }.getOrDefault(false)
                toast(if (ok) "删掉了" else "没删掉（文件可能已经不在了）")
                after()
            }
            .setNegativeButton("算了", null)
            .show()
    }

    // ── 技能（2026-11-06）──────────────────────────────────────────────────────
    //
    // 他问：「我也不知道怎么生成 skill，然后 skill 在哪看，再哪删哪增加」——
    // 之前技能**只有模型能读写**，他一条都看不见。
    //
    // ★ 三条来路都写在清单底下那几行里：跟她说话时让她记、代码档做完让她存、这里手写。

    /** 「技能」：清单 → 一条的详情 → 删掉 / 新建。 */
    private fun showSkills() {
        val box = column()
        var dlg: AlertDialog? = null
        val all = SkillMath.ordered(SkillStore.all())
        box.addView(sectionTitle("她会做的事"))

        if (all.isEmpty()) box.addView(hint("一条都还没有。怎么做出来，看最下面那几行。"))
        for (s in all) {
            val row = settingRow(s.name, skillSub(s)) {}
            row.setOnClickListener { dlg?.dismiss(); showSkillDetail(s.id) }
            box.addView(row, fullRow())
        }

        box.addView(herButton("＋ 新建一条", 14f) {
            dlg?.dismiss()
            showSkillNew()
        }.apply {
            setPadding(dp(18), dp(10), dp(18), dp(10))
        })

        dlg = pageDialog("技能", box)
    }

    private fun skillSub(s: Skill): String {
        val kind = when (s.kind) {
            SkillKind.ACTION -> "动作"
            SkillKind.REASONING -> "方法"
            SkillKind.RESEARCH -> "研究"
        }
        val trig = if (s.triggers.isEmpty()) "没有触发词"
        else "说到「" + s.triggers.joinToString("」「") + "」时会想起来"
        val runs = if (s.runs > 0) " · 跑过 ${s.runs} 次" else ""
        val draft = if (s.draft) " · 你写的，用之前会先问" else ""
        return "$kind · $trig$runs$draft"
    }

    private fun showSkillDetail(id: String) {
        val s = SkillStore.all().firstOrNull { it.id == id } ?: return
        val box = column()
        var dlg: AlertDialog? = null

        fun line(title: String, body: String) {
            box.addView(sectionTitle(title))
            box.addView(TextView(this).apply {
                text = body
                textSize = 13f
                setTextColor(getColor(R.color.glass_text))
                setTextIsSelectable(true)
            })
        }

        line("做什么", s.desc.ifBlank { s.name })
        line("什么时候用", if (s.triggers.isEmpty()) "没有触发词 —— 只能在对话里点名让她跑。"
        else s.triggers.joinToString("、"))
        when (s.kind) {
            SkillKind.ACTION -> line(
                "她会照做的步骤",
                s.steps.mapIndexed { i, st -> "${i + 1}. ${st.tool}" }.joinToString("\n")
                    .ifBlank { "（没有步骤）" },
            )
            else -> line("她照着走的方法", s.instructions.ifBlank { "（没写方法）" })
        }
        if (s.kind == SkillKind.RESEARCH) line("怎样算对", s.acceptance.ifBlank { "（没写判据）" })
        line("用过的次数", "${s.runs} 次")

        box.addView(herButton("删掉这条", 14f) {
            AlertDialog.Builder(this@ConMarnActivity)
                .setTitle("删掉「${s.name}」？")
                .setMessage("删了就找不回来了。她下次碰到同样的事得重新学。")
                .setPositiveButton("删掉") { _, _ ->
                    SkillStore.delete(s.id)
                    toast(if (SkillStore.all().none { it.id == s.id }) "删掉了" else "没删掉")
                    dlg?.dismiss()
                    showSkills()
                }
                .setNegativeButton("算了", null)
                .show()
        }.apply {
            setPadding(dp(18), dp(10), dp(18), dp(10))
        })

        val page = pageDialog(s.name, box)
        dlg = page
        page.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            page.dismiss()
            showSkills()
        }
    }

    /**
     * 「她记得的事」—— 看、删。
     *
     * ★ 只列出**他自己说过的话**（[MemoryStore] 只存这一种），不显示向量、不显示分数：
     *   那些是内部账，给他看只会让人以为「分数高就是真的记得对」。
     * ★ 长按一条删一条。**能删才敢让她记** —— 这是这一页存在的理由。
     */
    private fun showRemember() {
        val box = column()
        var dlg: AlertDialog? = null
        val mems = MemoryStore.all()

        box.addView(sectionTitle("她记得的事"))
        if (mems.isEmpty()) {
            box.addView(hint("还是空的。你跟她说过的话（长一点的）会自动记在这儿。"))
        } else {
            for (m in mems.take(200)) {
                val row = settingRow(m.text, "") {}
                row.setOnLongClickListener {
                    AlertDialog.Builder(this@ConMarnActivity)
                        .setTitle("不再记着这句？")
                        .setMessage(m.text)
                        .setPositiveButton("删掉") { _, _ ->
                            MemoryStore.forget(m.text)
                            dlg?.dismiss()
                            showRemember()
                        }
                        .setNegativeButton("留着", null)
                        .show()
                    true
                }
                box.addView(row, fullRow())
            }
        }

        dlg = pageDialog("她记得的事", box)
    }

    /**
     * 「共用记忆」—— 所有 agent 共用的那一本（[AgentMemory]）。
     *
     * ★ 两样东西装在里面：**干完一件活的成品**（自动记）和**他丢进来的资料**（这一页加）。
     *   列表里分开写〔以前查到的〕/〔资料〕，他得一眼看出来哪条是谁放的。
     * ★ 长按一条删一条，跟「她记得的事」同一个手势。**能删才敢让它一直记**。
     */
    private fun showAgentMemory() {
        val box = column()
        var dlg: AlertDialog? = null
        val mems = AgentMemory.all()

        box.addView(sectionTitle("共用记忆"))
        box.addView(
            hint(
                "所有 agent 共用这一本，只在本机。一件活干完，成品记一条；" +
                    "谁开工都先查一遍，查得中才给它看。资料你从这里加，按 ${
                        AgentMemory.CHUNK
                    } 字左右切开存。\n" +
                    "检索方式：" + AgentMemory.mode() + "。\n" +
                    // ★ 2026-11-07：主对话也开始查这一本了（见 AiAgent 里那次召回），
                    //   所以「会不会出去」这一句必须写在明面上 —— 同「她记得的事」那一页的规矩。
                    "命中时：那几段会跟着这一轮的话一起走。开着云端兜底的话它们会离开本机，" +
                    "出门前电话、证件号、卡号、邮箱、密钥换成代号，答复回来再换回真值；" +
                    "走本机模型时原文不出手机。"
            )
        )

        val addText = settingRow("加一段资料", "") {}
        addText.setOnClickListener { openAddDoc { dlg?.dismiss(); showAgentMemory() } }
        box.addView(addText, fullRow())

        val addFile = settingRow("选一个文件", "文字、Word、Excel、PPT、PDF、图片") {}
        addFile.setOnClickListener {
            // ★ 先关掉这一页再开系统选文件的界面，而且**关掉的瞬间不能让回程把它弹回来**：
            //   回程由 [onActivityResult] 一个人负责。两边都开就变成两层一模一样地叠着，
            //   他按返回要点两下才回到上一层 —— 2026-11-06 真机报的。
            glassReturn = null
            dlg?.dismiss()
            try {
                pickDoc()
            } catch (e: Exception) {
                toast("这台机上没有能选文件的应用")
                showAgentMemory()
            }
        }
        box.addView(addFile, fullRow())

        if (mems.isEmpty()) {
            box.addView(hint("还是空的。"))
        } else {
            for (m in mems.take(200)) {
                val tag = if (m.kind == AgentMemory.DOC) "资料" else "以前查到的"
                val head = if (m.who.isBlank()) "[$tag]" else "[$tag] ${m.who}"
                val row = settingRow(m.text, head) {}
                row.setOnLongClickListener {
                    AlertDialog.Builder(this@ConMarnActivity)
                        .setTitle("这条不再留着？")
                        .setMessage(m.text)
                        .setPositiveButton("删掉") { _, _ ->
                            AgentMemory.forgetAt(m.at)
                            dlg?.dismiss()
                            showAgentMemory()
                        }
                        .setNegativeButton("留着", null)
                        .show()
                    true
                }
                box.addView(row, fullRow())
            }
        }

        dlg = pageDialog("共用记忆", box)
    }

    /** 粘贴一段话进共用记忆。存完回 [after]。 */
    private fun openAddDoc(after: () -> Unit) {
        val box = column()
        val title = EditText(this).apply {
            hint = "给这段资料起个名字"
            setTextColor(getColor(R.color.glass_text))
            setHintTextColor(getColor(R.color.glass_dim))
            setSingleLine()
        }
        val body = EditText(this).apply {
            hint = "把字贴在这里"
            setTextColor(getColor(R.color.glass_text))
            setHintTextColor(getColor(R.color.glass_dim))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 8
            gravity = Gravity.TOP
        }
        box.addView(title, fullRow())
        box.addView(body, fullRow())
        val page = pageDialog("加一段资料", box)
        page.setButton(AlertDialog.BUTTON_POSITIVE, "存进库") { _, _ ->
            val name = title.text.toString().trim().ifBlank { "资料" }
            val text = body.text.toString()
            val n = AgentMemory.remember(AgentMemory.DOC, name, text)
            toast(
                when {
                    n > 0 -> "记进去 $n 条"
                    text.trim().length < 12 -> "太短了，没记"
                    AgentMemory.count() >= AgentMemory.MAX -> "库满了，先删几条"
                    else -> "这段已经有过了"
                }
            )
            after()
        }
        page.setButton(AlertDialog.BUTTON_NEGATIVE, "取消") { _, _ -> after() }
    }

    // ---- 选文件（[SelfHand.see_photo] / [SelfHand.open_table] 的那一半）----

    /** 等用户挑文件的那条队列。同一时刻最多一次（工具是一个一个跑的）。 */
    @Volatile
    private var pickQueue: java.util.concurrent.ArrayBlockingQueue<Any>? = null

    /**
     * 弹系统文件选择器，**在这个线程上等**他选完，回来的是 uri + 显示名。
     *
     * ★ 为什么是「弹出去再等着」而不是「弹出去、选完再来一轮」：她执行一次工具调用
     *   是一口气跑完的流程，`see_photo`/`open_table` 必须在这一步里拿到东西；拆成两轮，
     *   模型在中间那一轮不知道自己该等什么，会把它当成「这件事办完了」。
     * ★ 必须在**非 UI 线程**上调用。`poll` 会一直占着调用它的线程；占住主线程的话，
     *   `onActivityResult` 永远送不进来 —— 自己把自己锁死，而且看不出原因。
     * ★ 等 [PICK_WAIT_MS] 是**兜底**，不是正常路径：正常路径是用户按了取消（立刻回来）。
     */
    private fun pickFileBlocking(mime: String): SelfHand.Picked? {
        if (isFinishing || isDestroyed) {
            SelfHand.pickError = "她现在不在房间里（那个界面关掉了），弹不出选择器。"
            return null
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            // 写死这条不是洁癖：主线程上 `poll` 会卡住界面，而选完那一刻的结果正是
            // 走主线程送回来的 —— 症状是「一按就整个冻住」。
            SelfHand.pickError = "选文件不能在界面线程上等（这是程序的问题，不是你的）。"
            return null
        }
        val q = java.util.concurrent.ArrayBlockingQueue<Any>(1)
        pickQueue = q
        try {
            runOnUiThread {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mime
                }
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(i, REQ_PICK)
                } catch (e: Exception) {
                    // 这台机器上没有能打开选择器的界面 —— 说清楚，别让它一直等下去。
                    q.offer(e)
                }
            }
            val r = try {
                q.poll(PICK_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            }
            val uri = r as? android.net.Uri
            if (uri == null) {
                SelfHand.pickError = when (r) {
                    null -> "等了十分钟也没选文件，这次先算了。"
                    is Exception -> "文件选择器打不开（${r.javaClass.simpleName}）。"
                    else -> "这次没选文件。"
                }
                return null
            }
            return SelfHand.Picked(uri, displayNameOf(uri))
        } finally {
            pickQueue = null
        }
    }

    /**
     * 系统给的显示名（带扩展名）。问不出来时退回最后一段，再不行给个占位。
     *
     * ★ 扩展名要留着：认格式（csv 还是 xlsx）全靠它，`TableFile` 不猜内容。
     */
    private fun displayNameOf(uri: android.net.Uri): String {
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) return c.getString(0) ?: "" }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "文件"
    }

    private fun pickDoc() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_DOC)
    }

    /**
     * 把一个 `content://` 读成文字，切成几条存进共用记忆。
     *
     * ★ 必须离开 UI 线程：读一个几 MB 的文件在主线程上做，界面会先卡住；
     *   图片认字、PDF 抽文字更是几秒钟起步。
     * ★ 格式怎么认、字怎么抠全在 [DocText]：这里**不猜**后缀，也别在这里再加一层
     *   「是不是文本」的判断 —— 两层判断不一致时，受罪的是一整类文件。
     */
    private fun ingestDoc(uri: Uri) {
        Thread {
            var name = "资料"
            runCatching {
                contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0) ?: name
                }
            }
            val mime = runCatching { contentResolver.getType(uri).orEmpty() }.getOrDefault("")
            val r = DocText.read(this, uri, name, mime)
            val n = if (r.ok) AgentMemory.remember(AgentMemory.DOC, name, r.text) else 0
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    !r.ok -> toast(r.why)
                    n > 0 -> toast("「$name」记进去 $n 条")
                    else -> toast("「$name」里没有能记的东西")
                }
                showAgentMemory()
            }
        }.start()
    }

    /** 手写一条：名字 + 触发词 + 做法。存成「方法型」，因为手写的是做法不是录像。 */
    private fun showSkillNew() {
        val box = column()
        var dlg: AlertDialog? = null

        fun field(title: String, hintText: String, multi: Boolean = false): EditText {
            box.addView(TextView(this).apply {
                text = title
                textSize = 12f
                setTextColor(getColor(R.color.glass_sub))
                setPadding(dp(2), dp(12), dp(2), dp(2))
            })
            return EditText(this).apply {
                hint = hintText
                setTextColor(getColor(R.color.glass_text))
                setHintTextColor(getColor(R.color.glass_dim))
                if (multi) {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    minLines = 4
                } else {
                    setSingleLine()
                }
            }.also { box.addView(it) }
        }

        val fName = field("叫什么", "比如：整理课堂笔记")
        val fTrig = field("什么时候想起来", "你嘴里会说的词，用逗号分开，比如：整理笔记, 课堂笔记")
        val fHow = field(
            "她该怎么做",
            "写做法，不写步骤：先问清什么、查什么、按什么顺序做、最后输出成什么样。",
            multi = true,
        )


        val page = pageDialog("新建一条技能", box)
        dlg = page
        page.setButton(AlertDialog.BUTTON_POSITIVE, "保存") { _, _ ->
            val name = fName.text.toString().trim()
            SkillMath.nameProblem(name)?.let { toast(it); return@setButton }
            val how = fHow.text.toString().trim()
            if (how.isEmpty()) {
                toast("「她该怎么做」不能空 —— 空技能叫起来什么都不会发生")
                return@setButton
            }
            val triggers = fTrig.text.toString().split(',', '，', '、', ';', '；')
                .map { it.trim() }.filter { it.length >= 2 }.distinct()
            val s = Skill(
                id = SkillMath.idOf(name),
                name = name,
                desc = "",
                instructions = how,
                triggers = triggers,
                kind = SkillKind.REASONING,
                sensitive = true,
                createdAt = System.currentTimeMillis(),
                // ★ 手写的是**没跑过**的：每次用之前都先问他一次。
                draft = true,
            )
            val err = SkillStore.save(s)
            toast(err ?: "存好了")
            page.dismiss()
            showSkills()
        }
        page.setButton(AlertDialog.BUTTON_NEGATIVE, "算了") { _, _ ->
            page.dismiss()
            showSkills()
        }
        glassReturn = { showSkills() }
    }

    /**
     * **新建一条用途** —— 面板里的入口。
     *
     * 两种贴法都认,判据在 [ApiDraftMath](纯函数,单测钉着):
     *  - `openapi.json` 的内容(OpenAPI 3 / Swagger 2)—— 一次进来它所有的接口;
     *  - 浏览器「复制为 cURL」—— 一个请求换一个工具。
     * 都不是就**当场说认不出**,不猜。
     *
     * ★ 只让我们认这两种,是因为它们的形状是确定的;「一句自然语言描述一个 API」
     *   也能做,但猜错的代价是一条**看起来对、参数少一个**的工具,而她调它的时候
     *   会拿回别的东西 —— 这种错没有任何报错。宁可不做。
     */
    private fun showApiNew() {
        val box = column()
        val fLabel = glassInput().apply { hint = "名字,比如「和风天气」(可以不填)" }
        val fBody = glassInput().apply {
            hint = "把 openapi.json 的内容,或者浏览器里「复制为 cURL」贴在这里"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        fun labeled(t: String, e: EditText) {
            box.addView(TextView(this).apply {
                text = t; textSize = 12f; setTextColor(getColor(R.color.glass_sub))
                setPadding(dp(2), dp(10), dp(2), dp(2))
            })
            box.addView(e, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        labeled("名字", fLabel)
        labeled("接口描述", fBody)
        box.addView(hint("识别出来的东西会先列一遍,点确认才存。key 不在这一步填。"))

        glassBuilder("新建 API")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("识别") { _, _ ->
                val label = fLabel.text.toString().trim()
                val text = fBody.text.toString().trim()
                if (text.isEmpty()) {
                    toast("先把接口描述贴进来。")
                    return@setPositiveButton
                }
                val d = ApiDraftMath.parse(text, label)
                if (d == null) {
                    toast("认不出来 —— 要 openapi.json 的内容,或者一段 curl。")
                    return@setPositiveButton
                }
                showApiDraft(d)
            }
            .setNegativeButton("取消") { _, _ -> showLinks() }
            .show()
            .glassified()
        // ★ 这一层是从「连接」进来的,回程就是它。
        glassReturn = { showLinks() }
    }

    /**
     * 识别出来的草稿,先给他看一遍。
     *
     * ★ 这一步不能省。解析错的形态有两种,都**不报错**:
     *   少一个必填参数(她调的时候对方回 400)、或者路径拼错(对方回一份别的东西)。
     *   唯一能在落盘前发现它们的,就是人眼 —— 所以列出来。
     */
    private fun showApiDraft(d: ApiDraft) {
        val box = column()
        box.addView(TextView(this).apply {
            text = "${d.purpose.label}　${d.purpose.profiles.firstOrNull()?.baseUrl.orEmpty()}"
            textSize = 14f
            setTextColor(getColor(R.color.glass_text))
            setPadding(dp(2), dp(4), dp(2), dp(6))
        })
        box.addView(hint("共 ${d.tools.size} 个接口:"))
        d.tools.take(12).forEach { t ->
            val ps = t.params.joinToString("、") { it.name }
            box.addView(settingRow(
                t.name,
                t.desc.take(50) + if (ps.isNotEmpty()) "　参数:$ps" else "",
                {},
            ))
        }
        if (d.tools.size > 12) box.addView(hint("还有 ${d.tools.size - 12} 个没列出来,存了都能用。"))
        d.notes.forEach { box.addView(hint(it)) }

        // ★ 同名会覆盖。不说的话他会以为是在「再加一个」,而原来的配置**静默没了**。
        if (ApiStore.purpose(d.purpose.id) != null) {
            box.addView(hint("已经有一条叫「${d.purpose.label}」的了 —— 保存会覆盖它。"))
        }

        glassBuilder("确认新建")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                ApiDraftMath.problem(d)?.let { toast(it); return@setPositiveButton }
                val okP = ApiStore.upsertPurpose(d.purpose)
                var okT = true
                d.tools.forEach { if (!ApiStore.upsertTool(it)) okT = false }
                if (!okP || !okT) {
                    toast("没存进去 —— 盘上写失败了,再试一次。")
                    return@setPositiveButton
                }
                toast("加好了:${d.tools.size} 个接口。")
                showApiPurpose(d.purpose.id)
            }
            .setNegativeButton("返回") { _, _ -> showApiNew() }
            .show()
            .glassified()
        glassReturn = { showApiNew() }
    }

    /** 三级:这一个用途下的配置档。 */
    private fun showApiPurpose(purposeId: String) {
        val box = column()
        val p = ApiStore.purpose(purposeId) ?: run {
            box.addView(hint("这条用途不见了(可能刚被删掉)。"))
            pageDialog("用途", box)
            return
        }
        var dlg: AlertDialog? = null

        val rows = p.profiles.map { prof ->
            val on = prof.id == p.activeId
            val state = when {
                !prof.enabled -> "已禁用"
                on -> "★ 正在用"
                else -> "点一下切到它"
            }
            // ★ 走 OAuth 的那几档**不能**用 keyless 那一套：还没授权时 apiKey 是空的，
            //   按 keyless 会显示成「不需要 key」—— 那是在撒谎，而且恰好是用户
            //   最需要看到「还没授权」的那一刻。
            val k = if (prof.oauth != null) OAuthClient.statusLine(prof)
            else if (prof.keyless) {
                if (prof.keyUnreadable) "⚠ key 解不开,要重填" else "不需要 key"
            } else "key ${ApiMath.maskKey(prof.apiKey)}"
            val row = settingRow("${prof.label}　$state", "$k · ${prof.baseUrl}", {})
            row to prof
        }

        rows.forEach { (row, prof) ->
            row.setOnClickListener {
                // ★ 已经生效的那一档,点一下不再是「切到它」(那是个空动作,会让用户
                //   以为自己点了什么)。改成打开它的编辑框 —— 那里才是他真正想去的地方。
                if (prof.id == p.activeId) {
                    swapPage(dlg) { showApiProfileEdit(purposeId, prof) }
                    return@setOnClickListener
                }
                ApiStore.setActive(purposeId, prof.id)
                swapPage(dlg) { showApiPurpose(purposeId) }
            }
            // 改 / 禁用 / 清 key / 删 —— 都在长按里。面板上每档配三个按钮会挤成一团,
            // 而这几件事的频率远低于「切一档」。
            row.setOnLongClickListener {
                swapPage(dlg) { showProfileMenu(purposeId, prof) }
                true
            }
            box.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val addRow = settingRow("+ 加一档", "同一个用途换一家服务商、或者换一个 key。", {})
        addRow.setOnClickListener {
            swapPage(dlg) { showApiProfileEdit(purposeId, null) }
        }
        box.addView(addRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val usageRow = settingRow("用量与调试", ApiMath.usageLine(p.usage).substringBefore('\n'), {})
        usageRow.setOnClickListener {
            swapPage(dlg) { showApiUsage(purposeId) }
        }
        box.addView(usageRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ★ 建错了得能删。没有这个入口的话,「自己加 API」就只进不出 ——
        //   而他试的第一版几乎一定会建成歪的。
        val delRow = settingRow("删掉这条用途", "连它下面的工具一起删,删了没有后悔药。", {})
        delRow.setOnClickListener {
            swapPage(dlg) {
                val n = ApiStore.tools().count { it.purpose == purposeId }
                // ★ 这颗确认框的回程是**这一页**:listener 在 dismiss 之前跑,
                //   所以「删掉」那条自己把回程改成「连接」,「算了」那条改回这一页。
                glassBuilder("删掉「${p.label}」?")
                    .setMessage("它的 $n 个工具会一起删掉。要只是先停用,回上一级把它关掉就行。")
                    .setPositiveButton("删掉") { _, _ ->
                        if (ApiStore.removePurpose(purposeId)) toast("删掉了。") else toast("没删成,盘上写失败了。")
                        glassReturn = { showLinks() }
                    }
                    .setNegativeButton("算了") { _, _ -> glassReturn = { showApiPurpose(purposeId) } }
                    .show()
                    .glassified()
                glassReturn = { showApiPurpose(purposeId) }
            }
        }
        box.addView(delRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dlg = pageDialog(p.label, box)
        // ★ 这一层的上一级是「连接」。
        glassReturn = { showLinks() }
    }

    /** 长按一档之后的那张菜单。 */
    private fun showProfileMenu(purposeId: String, prof: ApiProfile) {
        val acts = listOf("改它", if (prof.enabled) "禁用" else "启用", "清掉 key", "删掉")
        var dlg: AlertDialog? = null
        dlg = glassBuilder(prof.label)
            .setItems(acts.toTypedArray()) { _, which ->
                when (which) {
                    0 -> swapPage(dlg) { showApiProfileEdit(purposeId, prof) }
                    1 -> {
                        ApiStore.upsertProfile(purposeId, prof.copy(enabled = !prof.enabled))
                        if (prof.id == ApiStore.purpose(purposeId)?.activeId && prof.enabled) {
                            // 把**正在生效**的那一档禁掉 = 这条用途暂时整个停用。
                            // 这里不替她换档(见 ApiMath.active 的说明),但要当场说清楚,
                            // 否则他会以为「禁用了,应该自动用另一个」。
                            toast("「${prof.label}」禁用了 —— 这条用途现在整个停着,不会自动换别的档。")
                        }
                        swapPage(dlg) { showApiPurpose(purposeId) }
                    }
                    2 -> {
                        ApiStore.upsertProfile(purposeId, prof.copy(apiKey = "", keyUnreadable = false))
                        toast("key 清掉了。")
                        swapPage(dlg) { showApiPurpose(purposeId) }
                    }
                    else -> {
                        ApiStore.removeProfile(purposeId, prof.id)
                        toast("删掉了。")
                        swapPage(dlg) { showApiPurpose(purposeId) }
                    }
                }
            }
            .setNegativeButton("算了", null)
            .show()
            .glassified()
        glassReturn = { showApiPurpose(purposeId) }
    }

    /** 加 / 改一档。 */
    private fun showApiProfileEdit(purposeId: String, existing: ApiProfile?) {
        val box = column()
        // ★ 先声明再建框：下面「OAuth 授权」那一行的点击要用它做 [swapPage] 的起点。
        var page: AlertDialog? = null
        val fLabel = glassInput().apply {
            hint = "名字,比如「高德」"
            setText(existing?.label.orEmpty())
        }
        val fUrl = glassInput().apply {
            hint = "地址,要带 https:// 比如 https://restapi.amap.com"
            setText(existing?.baseUrl.orEmpty())
        }
        val fKey = glassInput().apply {
            hint = when {
                existing?.oauth != null -> "这一档走 OAuth 授权，这里一般不用填"
                existing != null && existing.apiKey.isNotBlank() ->
                    "留空 = 不改这个 key(要清掉请用长按菜单)"
                else -> "key(有的服务不需要,留空就行)"
            }
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val fModel = glassInput().apply {
            hint = "模型名(用不到就留空)"
            setText(existing?.model.orEmpty())
        }
        fun labeled(t: String, e: EditText) {
            box.addView(TextView(this).apply {
                text = t; textSize = 12f; setTextColor(getColor(R.color.glass_sub))
                setPadding(dp(2), dp(10), dp(2), dp(2))
            })
            box.addView(e, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        labeled("名字", fLabel)
        labeled("地址", fUrl)
        labeled("key", fKey)
        labeled("模型名", fModel)

        // ★ OAuth 那一块**另开一页**，不塞进这个框里。
        //   理由：这一页的「保存」是一个动作、一次写盘；而授权要先写盘、再跳浏览器、
        //   再等回来 —— 混在一起会让「保存了吗」「授权了吗」两件事分不清。
        //   ★ 新建的这一档还没有 id，授权结果没处存 —— 所以这里只对已经存在的档开口。
        if (existing == null) {
            box.addView(hint("这一档保存之后，可以再进来给它配 OAuth 授权。"))
        } else {
            val oauthRow = settingRow(
                "OAuth 授权",
                if (existing.oauth == null) "用别家的账号授权，不用手填 key。"
                else OAuthClient.statusLine(existing),
                {},
            )
            oauthRow.setOnClickListener {
                swapPage(page) { showOAuth(purposeId, existing.id) }
            }
            box.addView(oauthRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val testRow = settingRow("测一测连通", "只打一下上面这个地址,不碰任何工具。", {})
        testRow.setOnClickListener {
            val draft = ApiProfile(
                id = existing?.id ?: "draft",
                label = fLabel.text.toString().trim().ifBlank { "这一档" },
                baseUrl = fUrl.text.toString().trim(),
                apiKey = if (fKey.text.isNullOrBlank()) existing?.apiKey.orEmpty()
                else fKey.text.toString(),
            )
            ApiMath.checkProfile(draft)?.let { toast(it); return@setOnClickListener }
            toast("试着连一下…")
            // ★ 必须离开主线程:HttpURLConnection 在 UI 线程上直接抛
            //   NetworkOnMainThreadException,而那个异常**长得像「连不上」**
            //   —— 用户会去查网络,问题其实在我们这儿。
            Thread {
                val msg = CloudHand.ping(draft)
                runOnUiThread { toast(msg) }
            }.start()
        }
        box.addView(testRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        page = glassBuilder(if (existing == null) "加一档" else "改「${existing.label}」")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                val label = fLabel.text.toString().trim()
                val url = fUrl.text.toString().trim()
                if (label.isEmpty() || url.isEmpty()) {
                    toast("名字和地址都得填。")
                    return@setPositiveButton
                }
                val typedKey = fKey.text?.toString().orEmpty()
                val keepOld = typedKey.isBlank() && existing != null
                val next = ApiProfile(
                    id = existing?.id ?: ("p" + System.currentTimeMillis()),
                    label = label,
                    baseUrl = url,
                    apiKey = if (keepOld) existing.apiKey else typedKey.trim(),
                    model = fModel.text.toString().trim(),
                    enabled = existing?.enabled ?: true,
                    // ★ 他刚亲手填了一个 key → 「解不开」这一位必须清掉,
                    //   否则面板会一直挂着一句已经不成立的警告。
                    keyUnreadable = false,
                    // ★ OAuth 配置**原样带过去**。这一页是「改名字/改地址」,
                    //   不是「把这个档重置」—— 漏了这一句,他改一次名字就把
                    //   那档的授权配置连同服务商/范围一起抹了,而且面板上只会
                    //   显示成「用 key 的那一档」。
                    oauth = existing?.oauth,
                )
                ApiStore.upsertProfile(purposeId, next)
                // 第一次加档、而这条用途还没生效档 → 顺手设成生效。
                // 不这么做的话,他会看到「加好了」但这条用途仍然用不了,而原因藏在别处。
                if (ApiStore.purpose(purposeId)?.activeId.isNullOrBlank()) {
                    ApiStore.setActive(purposeId, next.id)
                }
                swapPage(page) { showApiPurpose(purposeId) }
            }
            .setNegativeButton("取消") { _, _ -> swapPage(page) { showApiPurpose(purposeId) } }
            .show()
            .glassified()
        // ★ 这一层的上一级是「这一条用途」那一页。
        glassReturn = { showApiPurpose(purposeId) }
    }

    /** 选服务商：GitHub / Google / 自定义。选完才去填 client_id。 */
    private fun showOAuthPick(purposeId: String, profileId: String) {
        val box = column()
        var dlg: AlertDialog? = null

        fun picker(title: String, sub: String, make: () -> OAuthConfig) {
            val row = settingRow(title, sub, {})
            row.setOnClickListener {
                val prof = ApiStore.purpose(purposeId)?.profiles?.firstOrNull { it.id == profileId }
                if (prof == null) {
                    toast("这一档不见了。")
                    return@setOnClickListener
                }
                if (!ApiStore.upsertProfile(purposeId, prof.copy(oauth = make()))) {
                    toast("写不进盘上，请重试。")
                    return@setOnClickListener
                }
                swapPage(dlg) { showOAuth(purposeId, profileId) }
            }
            box.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        box.addView(hint("选一家。地址和权限范围会预填好，你只需要填 client_id。"))
        for (ps in OAuthPresets.all) {
            picker(
                ps.name,
                ps.vendor + if (ps.needsSecret) " · 需要 client_id 和 secret" else " · 只需要 client_id",
            ) { OAuthPresets.toConfig(ps) }
        }
        picker("自定义", "授权地址、换 token 的地址都由你自己填。") {
            OAuthConfig(authUrl = "", tokenUrl = "", clientId = "")
        }

        dlg = pageDialog("OAuth 服务商", box)
        glassReturn = { showApiPurpose(purposeId) }
    }

    /**
     * 一档的 OAuth 配置 + 授权动作。
     *
     * ★ 四个动作（开始授权 / 粘贴授权码 / 清除授权 / 保存）都会**先写盘再做事** ——
     *   因为它们都要离开这一页（跳浏览器、弹子框），留在输入框里没保存的字会丢，
     *   而丢了之后用户看到的是「我明明填了 client_id」。
     */
    private fun showOAuth(purposeId: String, profileId: String) {
        val prof0 = ApiStore.purpose(purposeId)?.profiles?.firstOrNull { it.id == profileId }
            ?: run {
                toast("这一档不见了。")
                return
            }
        val cfg = prof0.oauth ?: run {
            showOAuthPick(purposeId, profileId)
            return
        }
        val preset = OAuthPresets.find(cfg.provider)
        val custom = cfg.provider.isBlank()

        val box = column()
        var dlg: AlertDialog? = null

        box.addView(hint("状态：${OAuthClient.statusLine(prof0)}"))
        preset?.let { box.addView(hint(it.hint)) }

        val providerRow = settingRow(
            "服务商",
            (preset?.name ?: "自定义") + "　点一下更换",
            {},
        )
        providerRow.setOnClickListener { swapPage(dlg) { showOAuthPick(purposeId, profileId) } }
        box.addView(providerRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun labeled(t: String, e: EditText) {
            box.addView(TextView(this).apply {
                text = t; textSize = 12f; setTextColor(getColor(R.color.glass_sub))
                setPadding(dp(2), dp(10), dp(2), dp(2))
            })
            box.addView(e, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val pw = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        val fId = glassInput().apply { hint = "client_id"; setText(cfg.clientId) }
        val fSecret = glassInput().apply {
            hint = if (preset?.needsSecret == true) "client_secret" else "client_secret（有的服务不需要，留空）"
            setText(cfg.clientSecret)
            inputType = pw
        }
        val fScope = glassInput().apply {
            hint = "权限范围，空格分开。留空 = 用服务商的默认范围"
            setText(cfg.scopes)
        }
        val fAuth = glassInput().apply { hint = "授权地址，比如 https://…/authorize"; setText(cfg.authUrl) }
        val fToken = glassInput().apply { hint = "换 token 的地址，比如 https://…/token"; setText(cfg.tokenUrl) }

        labeled("client_id", fId)
        labeled("client_secret", fSecret)
        labeled("权限范围", fScope)
        if (custom) {
            labeled("授权地址", fAuth)
            labeled("换 token 地址", fToken)
        }

        fun current(): OAuthConfig = cfg.copy(
            clientId = fId.text.toString().trim(),
            clientSecret = fSecret.text.toString().trim(),
            scopes = fScope.text.toString().trim(),
            authUrl = if (custom) fAuth.text.toString().trim() else cfg.authUrl,
            tokenUrl = if (custom) fToken.text.toString().trim() else cfg.tokenUrl,
        )

        /**
         * 写盘。**换了 client_id / secret 就把旧凭证清掉** —— 那是另一个应用的令牌。
         *
         * ★ 每次都从盘上重读一遍再合：这一页可能会开着很久（跳浏览器、等回传），
         *   期间后台刚写进来的新 token 不能用这页开头那份快照盖掉。
         */
        var base = cfg
        fun persist(): OAuthConfig {
            val latest = ApiStore.purpose(purposeId)?.profiles?.firstOrNull { it.id == profileId } ?: prof0
            val c = current()
            val changed = c.clientId != base.clientId || c.clientSecret != base.clientSecret
            val merged =
                if (changed) c.copy(refreshToken = "", expiresAtMs = 0, scopeGranted = "") else c
            ApiStore.upsertProfile(
                purposeId,
                latest.copy(
                    oauth = merged,
                    // 换了应用 = 手上那串 access_token 也不作数了，一起清。
                    apiKey = if (changed) "" else latest.apiKey,
                    keyUnreadable = if (changed) false else latest.keyUnreadable,
                ),
            )
            base = merged
            return merged
        }

        fun actionRow(title: String, sub: String, act: () -> Unit) {
            val row = settingRow(title, sub, {})
            row.setOnClickListener { act() }
            box.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        actionRow("开始授权", "跳到浏览器，点同意之后自动回来。") {
            val c = persist()
            val url = OAuthClient.begin(purposeId, profileId, c)
            if (!url.startsWith("http")) {
                toast(url)
                return@actionRow
            }
            try {
                // ★ 浏览器可能不止一个 —— 或一个都没有（被冻掉 / 无双开）。
                //   没有的话 startActivity 抛 ActivityNotFoundException，
                //   那时候**必须**指到「粘贴授权码」那条路上，而不是只说一句失败。
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                toast("这台机器上没有能打开的浏览器。改用「粘贴授权码」。")
            }
        }

        actionRow("粘贴授权码", "浏览器跳完回来之后，把地址栏那一整行复制到这里。") {
            persist()
            val input = glassInput().apply {
                hint = "conmarn://oauth?code=…"
                setSingleLine(false)
            }
            var paste: AlertDialog? = null
            paste = glassBuilder("粘贴授权码")
                .setView(ScrollView(this).apply { addView(input) })
                .setPositiveButton("确定") { _, _ ->
                    val text = input.text.toString()
                    if (text.isBlank()) {
                        toast("什么都没贴。")
                        return@setPositiveButton
                    }
                    toast("正在换 token…")
                    // ★ 必须是后台线程：换 token 是一次 POST。
                    Thread {
                        val msg = OAuthClient.finishPasted(text)
                        runOnUiThread {
                            toast(msg)
                            swapPage(null) { showOAuth(purposeId, profileId) }
                        }
                    }.start()
                }
                .setNegativeButton("取消") { _, _ -> swapPage(paste) { showOAuth(purposeId, profileId) } }
                .show()
                .glassified()
            glassReturn = { showOAuth(purposeId, profileId) }
        }

        actionRow("清除授权", "把这档手上的凭证删掉。服务商那边的授权记录不受影响。") {
            val c = persist()
            val latest = ApiStore.purpose(purposeId)?.profiles?.firstOrNull { it.id == profileId } ?: prof0
            ApiStore.upsertProfile(
                purposeId,
                latest.copy(
                    apiKey = "",
                    keyUnreadable = false,
                    oauth = c.copy(refreshToken = "", expiresAtMs = 0, scopeGranted = ""),
                ),
            )
            toast("凭证清掉了。")
            swapPage(dlg) { showOAuth(purposeId, profileId) }
        }

        dlg = glassBuilder("OAuth 授权")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                persist()
                toast("保存了。")
                swapPage(dlg) { showOAuth(purposeId, profileId) }
            }
            .setNegativeButton("返回") { _, _ -> swapPage(dlg) { showApiPurpose(purposeId) } }
            .show()
            .glassified()
        glassReturn = { showApiPurpose(purposeId) }
    }

    /** 用量与调试 —— 另开一框:它回答的是「花了多少、刚才那下成没成」,不是「用的是谁」。 */
    private fun showApiUsage(purposeId: String) {
        val box = column()
        val p = ApiStore.purpose(purposeId)
        if (p == null) {
            pageDialog("用量", box)
            glassReturn = { showLinks() }
            return
        }
        val u = ApiStore.usageOf(purposeId)
        box.addView(TextView(this).apply {
            text = ApiMath.usageLine(u)
            textSize = 13f
            setTextColor(getColor(R.color.glass_text))
            setPadding(dp(2), dp(6), dp(2), dp(6))
        })
        box.addView(TextView(this).apply {
            text = "最近几次"
            textSize = 12f; setTextColor(getColor(R.color.glass_sub))
            setPadding(dp(2), dp(10), dp(2), dp(4))
        })
        val now = System.currentTimeMillis()
        ApiMath.debugLines(u, now).forEach { line ->
            box.addView(TextView(this).apply {
                text = line
                textSize = 12f
                setTextColor(getColor(R.color.glass_sub))
                setPadding(dp(2), dp(3), dp(2), dp(3))
            })
        }
        pageDialog("${p.label} · 用量", box)
        glassReturn = { showApiPurpose(purposeId) }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  记忆库(2026-10-08)—— 他能看 / 删 / 加 / 改她学到的东西
    // ══════════════════════════════════════════════════════════════════════════
    //
    // 他原话:「**我要自己的记忆库+经验库就行**」「可以随时删改它的记忆库,这个能做到吗?
    // 这样我就能在她出现 bug、错误的时候教她」「本质上,就是能有一个**训练它的本地入口**」。
    // 而这一整摊真正回答的是他那句话:「**云端老师教错了,那我该咋办**」。
    //
    // ★★ 两半,名字不同、后果也不同,别混成一件:
    //   · **经验库**([ExperienceStore])—— 她「碰上这种情况先按这个顺序试」的做法。
    //     这些大半是**云端老师**教的,所以「老师教错了」就发生在这里。
    //   · **长期记忆**([UserLexicon])—— 关于他本人的事实(怎么叫他、常开什么应用、
    //     他听错过的词)。这一半是她自己从他说话里听来的。
    //
    // ★★ 两种「不要它了」后果**不一样**,这是这一页最要紧的一句话,界面上必须写出来:
    //   「删掉」= 她手上没这条了,下次再碰上**还得花钱问云端老师**;
    //   「别再要了」= 这条还留着,但**永远不会用**,老师下次再教一模一样的做法会被当场拒掉
    //   ([ExperienceStore.learn] 回 null)→ 也就不用再花那一次钱。**老师教错了用这个。**
    //
    // ★ 这里每一次「删」都**不带二次确认框** —— 因为**他本人就是那个被问的人**
    //   (这个项目那条最高优先级的规矩是「删任何东西前先问」,这儿他就是按下去的那位)。
    //   代价:误点一下就真没了。所以**每一行都必须把后果写在它自己身上**,不能藏在别的页面里。
    //
    // ★★ 用词的规矩(他自己定的那条「**别用行话跟他讲代码**」):
    //   `offscreen` / `blind` / `empty` / `scroll` / `teacher` **一个都不许出现在屏幕上** ——
    //   见 [kindWord] / [verbWord] / [sourceWord]。
    //
    // ★★ 两个库**都不落盘一份 UI 快照**:每一页进/show 的时候**现读一遍**
    //   ([ExperienceStore.all] / [UserLexicon.snapshot] 都是只读、不写盘、不记「他来过」)。
    //   所以「改完退出来数字还是旧的」这件事在结构上不会发生。

    /** 经验库那三种情况的人话。★ 和 `AiAgent` 里那两份说法对齐,别造第三套词。 */
    private fun kindWord(kind: String): String = when (kind) {
        "offscreen" -> "东西在,但滚出屏幕了"
        "blind" -> "屏幕上有字,但没有要找的那个词"
        "empty" -> "屏幕上认不出字"
        else -> kind
    }

    /** 三个动词的人话。★ 这几个词是唯一能存的「做法」单位(`ExperienceStore.VERBS`)。 */
    private fun verbWord(v: String): String = when (v) {
        "scroll" -> "滚动"
        "search" -> "用它自己的搜索"
        "visual" -> "看图找"
        else -> v
    }

    /** 这条是谁教的 —— 「老师教错了」那句话的落点就在它上面。 */
    private fun sourceWord(s: String): String = when (s) {
        "seed" -> "出厂自带"
        "teacher" -> "云端老师教的"
        "manual" -> "你自己加的"
        else -> s
    }

    /** 把一串动词拼成人话:「1.滚动 → 2.用它自己的搜索」。 */
    private fun verbsText(verbs: List<String>): String =
        verbs.mapIndexed { i, v -> "${i + 1}.${verbWord(v)}" }.joinToString(" → ")

    /** 经验库只有这三种情况 —— 和 `AiAgent` 里那个 `when` 是同一个封闭集合。 */
    private val MEM_KINDS = listOf("offscreen", "blind", "empty")

    /**
     * 记忆库里**往里走一层**用的壳。
     *
     * ★★ 为什么不能直接用 [pageDialog]:它把回程**写死成「⚙ 列表」**
     *   (`glassReturn = { showSettings() }`)。回程是**一个**变量 ——
     *   在记忆库里点进「经验库」再关掉,直接落到 ⚙ 列表就等于**把记忆库那一层跳掉了**,
     *   他只会觉得「我刚点的那页没了」。
     *
     * ★ `positive` 默认「好」(只是看看的那些页);要存东西的页传「保存」/「存」。
     *   这两页的保存**必须走 [showPersonaEdit] 那个写法** —— `setPositiveButton(text, null)`
     *   拿到句柄自己挂监听,而不是 `setPositiveButton("保存") { … }`:后者是**先关窗再回调**,
     *   校验没过就没法把他留在这一页(改了半天被关掉,改动全丢)。
     */
    private fun memPage(
        title: String,
        box: View,
        back: () -> Unit = { showMemoryPanel() },
        positive: String = "好",
    ): AlertDialog {
        glassReturn = back
        return glassBuilder(title)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(positive, null)
            .show()
            .glassified()
    }

    /** 记忆库的门 —— ⚙ 列表里那一行点进来的第一页。★ 回程走 [pageDialog](= 回到 ⚙ 列表)。 */
    private fun showMemoryPanel() {
        val box = column()

        box.addView(sectionTitle("经验库"))
        val expRow = settingRow("她记住的做法", "", {})
        box.addView(expRow, fullRow())

        box.addView(sectionTitle("长期记忆"))

        val lex = UserLexicon.snapshot()
        val callRow = settingRow("她该怎么叫你", "", {})
        val avoidRow = settingRow("她不许这么叫你", "", {})
        val appRow = settingRow("你常开的应用", "", {})
        val fixRow = settingRow("她听错的词", "", {})
        listOf(callRow, avoidRow, appRow, fixRow).forEach { box.addView(it, fullRow()) }

        // ★ 这一页每次进来现读一遍(它没有 ⚙ 列表那种 refresh),所以下面四个数**当场就是新的**。
        val exps = ExperienceStore.all()
        setRow(expRow, "她记住的做法",
            if (exps.isEmpty()) "空的 —— 她一条做法都没记住。"
            else "${exps.size} 条(每种情况最多记 ${ExperienceStore.MAX_PER_KIND} 条)。")

        setRow(callRow, "她该怎么叫你",
            if (lex == null || lex.call.isEmpty()) "还没记 —— 你直接说「叫我小明」她自己也会记。"
            else lex.call.joinToString("、"))
        setRow(avoidRow, "她不许这么叫你",
            if (lex == null || lex.avoid.isEmpty()) "没有。" else lex.avoid.joinToString("、"))
        setRow(appRow, "你常开的应用",
            if (lex == null || lex.apps.isEmpty()) "还没记 —— 她真开成一次才算数。"
            else lex.apps.entries.sortedByDescending { it.value }.take(4)
                .joinToString("、") { "${it.key} ${it.value} 次" } +
                if (lex.apps.size > 4) " 等 ${lex.apps.size} 个" else "")
        setRow(fixRow, "她听错的词",
            if (lex == null || lex.fixes.isEmpty()) "没有。" else "共 ${lex.fixes.size} 条")

        box.addView(sectionTitle("「删掉」和「别再要了」不一样"))

        var dlg: AlertDialog? = null

        // ★★ 「往里走一层」= **先关掉这一层,再开子页** —— 两层玻璃叠着会透字(见 [glassLive])。
        //   顺序不能反:先 `dismiss()` 后 `glassReturn` 的话,那一层关掉时回程还是旧的。
        fun open(go: () -> Unit) {
            glassReturn = { showMemoryPanel() }
            dlg?.dismiss()
            go()
        }

        expRow.setOnClickListener { open { showMemExpPage() } }
        callRow.setOnClickListener { open { showMemNamePage(avoid = false) } }
        avoidRow.setOnClickListener { open { showMemNamePage(avoid = true) } }
        appRow.setOnClickListener { open { showMemAppPage() } }
        fixRow.setOnClickListener { open { showMemFixPage() } }

        dlg = pageDialog("记忆库", box)
    }

    // ── 经验库 ────────────────────────────────────────────────────────────────

    /** 经验库的清单。按那三种情况分组 —— 分组是**读得懂**的前提(不分组就是一堆动词串)。 */
    private fun showMemExpPage() {
        val box = column()
        val all = ExperienceStore.all()


        var dlg: AlertDialog? = null
        fun open(go: () -> Unit) {
            glassReturn = { showMemExpPage() }
            dlg?.dismiss()
            go()
        }

        if (all.isEmpty()) {
            box.addView(hint("一条都没有。她下次碰上麻烦只能去问云端老师 —— **那要联网,而且要花钱**。"))
        }

        MEM_KINDS.forEach { k ->
            val mine = all.filter { it.kind == k }
            box.addView(sectionTitle(kindWord(k)))
            if (mine.isEmpty()) {
                box.addView(hint("(这种情况还没有任何做法)"))
            }
            mine.forEach { e -> box.addView(expRowOf(e) { open { showMemExpMenu(e) } }, fullRow()) }
        }

        // ★ 封闭集合**以外**的也列出来,别静默丢掉 —— 丢掉的那条它照样会用,
        //   而他会以为「我删了」或者「这里没有」。将来加了新 kind,这一块自动接住。
        val others = all.filter { it.kind !in MEM_KINDS }
        if (others.isNotEmpty()) {
            box.addView(sectionTitle("别的情况"))
            others.forEach { e -> box.addView(expRowOf(e) { open { showMemExpMenu(e) } }, fullRow()) }
        }

        box.addView(hint("★ 每种情况最多记 ${ExperienceStore.MAX_PER_KIND} 条。再往里加,最没把握的那条**会被挤出去** —— 到时候会当场告诉你。"))

        dlg = memPage("经验库", box)
    }

    /** 经验库里的一行。★ 页面各处在用,单独抽出来免得两份说法慢慢长歪。 */
    private fun expRowOf(e: ExperienceStore.Exp, onTap: () -> Unit): LinearLayout {
        val title = (if (e.enabled) "" else "【已停用】") + verbsText(e.verbs)
        val sub = buildString {
            append(sourceWord(e.source))
            append(" · 成 ${e.wins} 次 / 用 ${e.uses} 次")
            if (!e.enabled) append(" · **她不会再碰这条**")
            if (e.reason.isNotBlank()) { append("\n"); append(e.reason) }
        }
        return settingRow(title, sub, onTap)
    }

    /** 单条经验能做的事:改 / 别再要了(或让它回来) / 删掉。 */
    private fun showMemExpMenu(e: ExperienceStore.Exp) {
        val box = column()

        box.addView(sectionTitle("这一条"))
        box.addView(hint(
            "什么时候用:${kindWord(e.kind)}\n" +
                "她会试的顺序:${verbsText(e.verbs)}\n" +
                "谁教的:${sourceWord(e.source)}\n" +
                "战绩:成 ${e.wins} 次 / 用 ${e.uses} 次" +
                (if (e.reason.isBlank()) "" else "\n当时的说法:${e.reason}")
        ))

        var dlg: AlertDialog? = null

        box.addView(sectionTitle("要做什么"))

        box.addView(settingRow("改这条的做法", "换掉试的顺序,或者少一步多一步。", {
            glassReturn = { showMemExpMenu(e) }
            dlg?.dismiss()
            showMemExpEdit(e.kind, e)
        }), fullRow())

        box.addView(settingRow(
            if (e.enabled) "这条别再要了" else "让它回来",
            if (e.enabled)
                "她还留着它,但**永远不会再用**;老师下次再教一模一样的做法也会被当场拒掉 —— **连那次钱也省了**。"
            else
                "她重新开始用这条。",
            {
                // ★★ `setEnabled` 是**原地改**这个对象的(`e.enabled = on`)——
                //   所以那句回话必须**先记下原来是什么**再说。反过来的话第一句永远成立,
                //   第二句永远说不出口(他刚点了「让它回来」,回话却是「她不会再用了」)。
                val was = e.enabled
                ExperienceStore.setEnabled(e, !was)
                toast(if (was) "好,这条她不会再用了。" else "好,这条她重新用上了。")
                dlg?.dismiss()
            }
        ), fullRow())

        box.addView(settingRow("删掉", "真的删掉。她以后要再学会它,得**再花钱问一次**云端老师。", {
            val ok = ExperienceStore.remove(e)
            toast(if (ok) "删掉了。" else "没删成 —— 它已经不在了。")
            dlg?.dismiss()
        }), fullRow())

        // ★ 回程是**这一条自己的页**而不是清单:改完退回来,他还看得见刚才那条的现状。
        dlg = memPage("这条经验", box, back = { showMemExpPage() })
    }

    /**
     * 加一条 / 改一条经验。
     *
     * ★★ 做法**只能从 [ExperienceStore.VERBS] 里点选,不许手打** —— 因为
     *   `ExperienceStore.parseVerbs` 遇到任何不认识的词**整条返回 null**。
     *   给他一个能打字却默默存不进去的框,就是这一摊里最坏的那种失败。
     *
     * ★★ **保存那一下必须查两件事**(都写在下面了):
     *   ① 存进去了没有([ExperienceStore.add] / [update] 回 null);
     *   ② ★ 存进去的那条**会不会当场被挤掉** —— 见 [ExperienceStore.MAX_PER_KIND] 那段:
     *      满了是**挤掉旧的**,而且新加的那条(成 0 次 / 用 0 次)本身就可能被挤掉,
     *      可 `add` 照样回一个非 null 的 `Exp`。不查的话他会得到一句「存好了」而东西不在。
     */
    private fun showMemExpEdit(kind: String, existing: ExperienceStore.Exp?) {
        val box = column()


        val picked = ArrayList<String>(existing?.verbs ?: emptyList())
        val preview = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(R.color.glass_text))
            setPadding(dp(2), dp(12), dp(2), dp(2))
        }
        val reasonEd = glassInput().apply {
            setHint("这条是为什么(写给你自己看就行)")
            if (existing != null && existing.reason.isNotBlank()) setText(existing.reason)
            setSingleLine(false)
        }

        val rows = ExperienceStore.VERBS.map { v -> settingRow("", "", {}) to v }

        fun paint() {
            rows.forEach { (row, v) ->
                val i = picked.indexOf(v)
                setRow(
                    row,
                    if (i >= 0) "${i + 1}. ${verbWord(v)}" else "＋ ${verbWord(v)}",
                    if (i >= 0) "已经在里面了 —— 再点一下拿掉。" else "点一下加进去。"
                )
            }
            preview.text = if (picked.isEmpty()) "还没选任何一步 —— 空的做法存不了。"
            else "她会依次试:" + verbsText(picked)
        }

        rows.forEach { (row, v) ->
            row.setOnClickListener {
                val i = picked.indexOf(v)
                when {
                    i >= 0 -> picked.removeAt(i)
                    // ★★ 第 5 步**在这里就拦住** —— 放进去了 `parseVerbs` 也会 `.take(4)`
                    //   静默丢掉,而他看到的是「存好了」。宁可当场说一句,不要存完才发现少两步。
                    picked.size >= ExperienceStore.MAX_VERBS ->
                        toast("最多 ${ExperienceStore.MAX_VERBS} 步 —— 先拿掉一个再加。")
                    else -> picked.add(v)
                }
                paint()
            }
        }

        box.addView(sectionTitle("按顺序试哪几步"))
        rows.forEach { (row, _) -> box.addView(row, fullRow()) }
        box.addView(preview, fullRow())
        box.addView(sectionTitle("为什么"))
        box.addView(reasonEd, fullRow())
        box.addView(hint("★ 这种情况最多记 ${ExperienceStore.MAX_PER_KIND} 条。满了再往里加,最没把握的那条会被挤出去。"))

        val d = memPage(
            if (existing == null) "加一条做法" else "改这条做法",
            box,
            back = { showMemExpPage() },
            positive = "保存",
        )
        paint()

        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (picked.isEmpty()) {
                toast("至少选一步 —— 空的做法存不了。")
                return@setOnClickListener
            }
            val reason = reasonEd.text.toString().trim()
            val chain = picked.joinToString(" ")

            val before = ExperienceStore.all()
            val saved = if (existing == null) ExperienceStore.add(kind, chain, reason)
            else ExperienceStore.update(existing, chain, reason)
            if (saved == null) {
                // ★ 走到这儿只可能是「同一条做法已经在了」—— 而 `add` 在**它被停用**时也回 null
                //   (见 [ExperienceStore.learn] 那段)。所以这句话必须把他指到那条上,不能只说「失败了」。
                toast(
                    if (existing == null)
                        "这条加不进去 —— 多半是它已经在了、而且被你设成了「别再要了」。去那条上点「让它回来」。"
                    else
                        "改不了 —— 这条已经不在了(大概是刚被删过)。退出去重进一下。"
                )
                return@setOnClickListener                  // ★ 留在这一页,别把他刚敲的东西扔掉
            }

            val after = ExperienceStore.all()
            // ★★ 「谁被挤掉了」只能靠**对象身份**比 —— [ExperienceStore.all] 交出来的就是
            //   `items` 里那几个对象本身,所以 `===` 是一条准的判据。
            //   ★ 而**必须把 `existing` 自己排除掉**:[ExperienceStore.update] 是「换一条」,
            //     它回的是一个**新对象**(`e.copy(...)`),旧的那个自然就不在 `after` 里了 ——
            //     不排除的话,他每改一次都会听到「旧的那条被让位了」,而根本没东西被挤掉。
            val gone = before.filter { o -> o !== existing && after.none { it === o } }
            toast(when {
                // ★★ 新加的这条自己就是那个被挤掉的 —— `add` 这时候**照样回了一个非 null**
                after.none { it === saved } ->
                    "没能留下 —— 这一种情况已经攒满 ${ExperienceStore.MAX_PER_KIND} 条," +
                        "而它最没把握,当场就被挤掉了。"
                gone.isEmpty() -> "存好了。"
                gone.size == 1 ->
                    "存好了 —— 但这一种情况只留 ${ExperienceStore.MAX_PER_KIND} 条," +
                        "旧的那条「${verbsText(gone[0].verbs)}」被让位了。"
                else ->
                    "存好了 —— 但这一种情况只留 ${ExperienceStore.MAX_PER_KIND} 条," +
                        "${gone.size} 条旧的被让位了。"
            })
            d.dismiss()
            return@setOnClickListener
        }
    }

    // ── 长期记忆 · 她该怎么叫你 / 不许这么叫 ───────────────────────────────────

    /**
     * 两个名字名单共用一页 —— 它们本来就是**同一份名单的两面**:
     * 一边加进去,另一边的同名会被当场拿掉(`LexiconMath.addCall` / `addAvoid`)。
     * 分成两页写会让人以为这是两件不相干的事,而它俩必须一致,否则拼进提示词
     * 就成了「叫她小明」+「别叫她小明」,她只能瞎猜。
     */
    private fun showMemNamePage(avoid: Boolean) {
        val box = column()
        val s = UserLexicon.snapshot()
        val list: List<String> = if (avoid) (s?.avoid ?: emptyList()) else (s?.call ?: emptyList())

        box.addView(hint(
            if (avoid) "这些是她**不许**用来叫你的。你说了「别叫我老板」,她就会记在这儿。"
            else "这些是她**会**用来叫你的。她跟你说话时会照着这个叫。"
        ))

        var dlg: AlertDialog? = null
        fun open(go: () -> Unit) {
            glassReturn = { showMemNamePage(avoid) }
            dlg?.dismiss()
            go()
        }

        if (list.isEmpty()) {
            box.addView(hint("现在是空的 —— 她自己还没听出来,你也没手填过。"))
        }
        list.forEach { n ->
            box.addView(settingRow(n, "点一下能删掉。", { open { showMemNameMenu(avoid, n) } }), fullRow())
        }

        box.addView(sectionTitle("加一个"))
        box.addView(settingRow("＋ 自己写一个", "最多 ${UserLexicon.MAX_NAME_LEN} 个字。", {
            open { showMemNameAdd(avoid) }
        }), fullRow())

        dlg = memPage(if (avoid) "她不许这么叫你" else "她该怎么叫你", box)
    }

    /** 单个名字:只有一件事可做 —— 删。 */
    private fun showMemNameMenu(avoid: Boolean, name: String) {
        val box = column()
        box.addView(hint("「$name」"))
        var dlg: AlertDialog? = null
        box.addView(settingRow("删掉", "真的删掉。以后她想再记起来,得重新听你说一次。", {
            val ok = if (avoid) UserLexicon.removeAvoid(name) else UserLexicon.removeCall(name)
            toast(if (ok) "删掉了。" else "没删成 —— 它已经不在了。")
            dlg?.dismiss()
        }), fullRow())
        dlg = memPage(if (avoid) "不许这么叫" else "怎么叫你", box, back = { showMemNamePage(avoid) })
    }

    /** 手填一个名字。★ 校验走 `LexiconMath.clean`(**不套** `looksLikeName`)—— 见那个函数的 KDoc。 */
    private fun showMemNameAdd(avoid: Boolean) {
        val box = column()
        box.addView(hint(
            if (avoid) "写一个她**不许**用来叫你的。"
            else "写一个她**可以**用来叫你的。"
        ))
        val ed = glassInput().apply {
            setHint(if (avoid) "比如:老板" else "比如:小明")
            setSingleLine(true)
        }
        box.addView(ed, fullRow())
        box.addView(hint("★ 最多 ${UserLexicon.MAX_NAME_LEN} 个字。"))

        val d = memPage(if (avoid) "不许这么叫" else "怎么叫你", box,
            back = { showMemNamePage(avoid) }, positive = "存")

        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = LexiconMath.clean(ed.text.toString(), UserLexicon.MAX_NAME_LEN)
            if (name == null) {
                toast("得是 1~${UserLexicon.MAX_NAME_LEN} 个字。")
                return@setOnClickListener
            }
            val s = UserLexicon.snapshot()
            if (s == null) {
                toast("记忆没读出来 —— 退出去重进一下。")
                return@setOnClickListener
            }
            val cur = if (avoid) s.avoid else s.call
            val other = if (avoid) s.call else s.avoid
            if (name in cur) {
                toast("「$name」已经在里面了。")
                return@setOnClickListener
            }
            if (cur.size >= UserLexicon.MAX_CALL) {
                toast("最多记 ${UserLexicon.MAX_CALL} 个 —— 满了要先删一个,旧的不会被挤掉。")
                return@setOnClickListener
            }
            val ok = if (avoid) UserLexicon.addAvoid(name) else UserLexicon.addCall(name)
            if (!ok) {
                toast("没存进去。")
                return@setOnClickListener
            }
            toast(
                if (name in other) "存好了:$name(顺手把另一边的同名拿掉了 —— 不然她得猜。)"
                else "存好了:$name"
            )
            d.dismiss()
            return@setOnClickListener
        }
    }

    // ── 长期记忆 · 你常开的应用 ────────────────────────────────────────────────

    /** 次数是**她数出来的**,但允许他改 —— 那是他的账本,记歪了得能自己抹平。 */
    private fun showMemAppPage() {
        val box = column()
        val s = UserLexicon.snapshot()
        val apps = s?.apps ?: emptyMap<String, Int>()

        box.addView(hint("她真开成一次才算数。这个数是她的账本 —— 记歪了你随时能改。"))
        if (apps.isEmpty()) box.addView(hint("现在是空的。"))

        var dlg: AlertDialog? = null
        fun open(go: () -> Unit) {
            glassReturn = { showMemAppPage() }
            dlg?.dismiss()
            go()
        }

        apps.entries.sortedByDescending { it.value }.forEach { (n, t) ->
            box.addView(settingRow(n, "$t 次 · 点一下能改或删掉。", { open { showMemAppEdit(n, t) } }), fullRow())
        }

        box.addView(sectionTitle("加一个"))
        box.addView(settingRow("＋ 自己写一个", "最多 ${UserLexicon.MAX_APPS} 个。", { open { showMemAppAdd() } }), fullRow())

        dlg = memPage("你常开的应用", box)
    }

    /** 改一条的次数:＋ / − / 删。★ 用加减而**不是**手填数字 —— 少一个能打错的地方。 */
    private fun showMemAppEdit(name: String, times: Int) {
        val box = column()
        box.addView(hint("「$name」现在是 $times 次。"))

        var dlg: AlertDialog? = null
        fun set(newTimes: Int, say: String) {
            val ok = UserLexicon.setApp(name, newTimes)
            toast(if (ok) say else "没改成功 —— 它已经不在了。")
            dlg?.dismiss()
        }

        box.addView(herButton("＋1(她刚又开了一次)", 14f) { set(times + 1, "好,$name 记成 ${times + 1} 次。") }, fullRow())
        box.addView(herButton("−1", 14f) {
            if (times <= 1) set(0, "减到 0 了,这条一并删掉。") else set(times - 1, "好,$name 记成 ${times - 1} 次。")
        }, fullRow())
        box.addView(settingRow("删掉这条", "她以后还会自己重新数。", { set(0, "删掉了。") }), fullRow())

        dlg = memPage("改次数", box, back = { showMemAppPage() })
    }

    private fun showMemAppAdd() {
        val box = column()
        box.addView(hint("写一个应用的名字(和她开的时候叫的那个名字一致)。"))
        val ed = glassInput().apply {
            setHint("比如:微信")
            setSingleLine(true)
        }
        box.addView(ed, fullRow())

        val d = memPage("加一个应用", box, back = { showMemAppPage() }, positive = "存")
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val raw = ed.text.toString()
            val name = LexiconMath.clean(raw, UserLexicon.MAX_NAME_LEN)
            if (name == null) {
                toast("得是 1~${UserLexicon.MAX_NAME_LEN} 个字。")
                return@setOnClickListener
            }
            val s = UserLexicon.snapshot()
            if (s == null) {
                toast("记忆没读出来 —— 退出去重进一下。")
                return@setOnClickListener
            }
            // ★ 已经有的**不许当成「加」** —— `setApp` 会把次数直接写成 1,
            //   那就成了「他点了一下加,结果原来的 12 次要变成 1 次」。得指他去改。
            if (name in s.apps) {
                toast("「$name」已经在里面了(现在 ${s.apps[name]} 次)—— 点它改次数。")
                return@setOnClickListener
            }
            if (s.apps.size >= UserLexicon.MAX_APPS) {
                toast("最多记 ${UserLexicon.MAX_APPS} 个 —— 满了要先删一个。")
                return@setOnClickListener
            }
            val ok = UserLexicon.setApp(name, 1)
            if (!ok) {
                toast("没加进去。")
                return@setOnClickListener
            }
            toast("加上了:$name(先记 1 次)")
            d.dismiss()
            return@setOnClickListener
        }
    }

    // ── 长期记忆 · 她听错的词 ──────────────────────────────────────────────────

    /**
     * 同音字纠正:她把这个词听错了,下次自动换成那个对的。
     *
     * ★ `bad` 常常就是一句识别错的怪话(「威信」这种),所以这里**一个字都不校验是不是人话** ——
     *   拿「像不像名字」那套规则去挡,只会把要修的东西挡在门外。
     */
    private fun showMemFixPage() {
        val box = column()
        val s = UserLexicon.snapshot()
        val fixes = s?.fixes ?: emptyMap<String, String>()

        if (fixes.isEmpty()) box.addView(hint("现在是空的。"))

        var dlg: AlertDialog? = null
        fun open(go: () -> Unit) {
            glassReturn = { showMemFixPage() }
            dlg?.dismiss()
            go()
        }

        fixes.forEach { (bad, good) ->
            box.addView(settingRow("「$bad」→「$good」", "点一下能改或者删掉。", {
                open { showMemFixEdit(bad) }
            }), fullRow())
        }

        box.addView(sectionTitle("加一条"))
        box.addView(settingRow("＋ 自己写一条", "最多 ${UserLexicon.MAX_FIXES} 条。", {
            open { showMemFixEdit(null) }
        }), fullRow())

        dlg = memPage("她听错的词", box)
    }

    /**
     * 加 / 改一条纠正。
     *
     * ★ 改的时候**只让改右边那个词** —— 左边那个是「她听到的声音」,改了它就是另一条记录了,
     *   那样「原来那条」会不声不响地消失。要换左边,先删掉再加。
     */
    private fun showMemFixEdit(bad: String?) {
        val box = column()

        box.addView(hint("左边是**她听到的**,右边是**你想要的**。"))
        val badEd = glassInput().apply {
            setHint("她听到的,比如:威信")
            setSingleLine(true)
            if (bad != null) { setText(bad); isEnabled = false }
        }
        val goodEd = glassInput().apply {
            setHint("你要的,比如:微信")
            setSingleLine(true)
            if (bad != null) UserLexicon.snapshot()?.fixes?.get(bad)?.let { setText(it) }
        }
        box.addView(badEd, fullRow())
        box.addView(goodEd, fullRow())

        var dlg: AlertDialog? = null
        if (bad != null) {
            box.addView(settingRow("删掉这条", "以后她就照原样听了。", {
                val ok = UserLexicon.removeFix(bad)
                toast(if (ok) "删掉了。" else "没删成 —— 它已经不在了。")
                dlg?.dismiss()
            }), fullRow())
        }

        val d = memPage(if (bad == null) "加一条纠正" else "改这条纠正", box,
            back = { showMemFixPage() }, positive = "保存")
        // ★ 上面那行「删掉这条」的回调里要关掉**这一页** —— 它拿的是 `dlg`,
        //   所以这里必须接上。漏了的话删除会生效、页面却留在原地(看着像没删成)。
        dlg = d

        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val b = LexiconMath.clean(badEd.text.toString(), UserLexicon.MAX_FIX_LEN)
            val g = LexiconMath.clean(goodEd.text.toString(), UserLexicon.MAX_FIX_LEN)
            if (b == null || g == null) {
                toast("两边都得填,而且各自不超过 ${UserLexicon.MAX_FIX_LEN} 个字。")
                return@setOnClickListener
            }
            if (b == g) {
                toast("两边一模一样 —— 那等于没记。")
                return@setOnClickListener
            }
            val s = UserLexicon.snapshot()
            if (s == null) {
                toast("记忆没读出来 —— 退出去重进一下。")
                return@setOnClickListener
            }
            if (b !in s.fixes && s.fixes.size >= UserLexicon.MAX_FIXES) {
                toast("最多记 ${UserLexicon.MAX_FIXES} 条 —— 满了要先删一条。")
                return@setOnClickListener
            }
            val ok = UserLexicon.putFix(b, g)
            if (!ok) {
                toast("没存 —— 和原来一模一样,没什么可改的。")
                return@setOnClickListener
            }
            toast("存好了:听到「$b」就当成「$g」。")
            d.dismiss()
            return@setOnClickListener
        }
    }

    private fun toggleAlive() {
        val on = !HerLife.isAlive(this)
        HerLife.setAlive(this, on)          // commit() 同步落盘,见 HerLife.setAlive
        try {
            if (on) startForegroundService(Intent(this, HerLifeService::class.java))
            else stopService(Intent(this, HerLifeService::class.java))
        } catch (e: Exception) {
            ModelManager.get(this).trace("一直在:切${if (on) "开" else "关"}时服务操作失败(${e.message})")
        }
        ModelManager.get(this).trace("一直在:${if (on) "开" else "关"}")

        if (!on) {
            toast("好。你关掉 App 我就不跟着了。")
            return
        }

        // ★★ 「让她睡」只在通知栏里,而**没有通知权限的 App,前台服务的通知根本不会
        //    出现在通知栏**(Android 13+ 明文如此:只在任务管理器里看得见)。
        //    2026-10-04 真机抓到的:`cmd appops get` 是 `POST_NOTIFICATION: ignore` ——
        //    这个权限在清单第 41 行声明了,但**代码里从来没请求过**(requestPermissions
        //    只请求过麦克风/相机/局域网)。后果是那句「通知栏会挂一条,上面有让她睡」
        //    **一直是句假话**,用户从来没见过那条通知,也就没有「不解锁也能关」的那条路。
        //
        //    问的时机就选在这儿:她**要开始一直在**的那一刻。不是一进 App 就弹冷脸,
        //    而是正当他刚说了「我想要你一直在」—— 这时候解释一条常驻通知的存在最自然。
        if (!canPostNotifications()) {
            toast("还差一步:系统得允许我发通知 —— 那一条是你随时能让我睡的地方")
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        toast("好,我尽量一直在 —— 通知栏会挂一条,上面有「让她睡」。")
    }

    // ==================================================================
    // 房间里的东西
    // ==================================================================

    /**
     * 把房间那份**布置**推给 JS —— 用户丢进 `room/` 的文件(见 [Wardrobe])。
     *
     * ★ 它和 [pushRoomObjects] 是**两件事**,故意没有合并:
     *   - [pushRoomObjects] 回答「房间里**有哪些**东西」—— 那份清单是
     *     [HandRegistry] 的镜像,也就是「她会做什么」;
     *   - 这里回答「每一件**摆在哪**、房间什么调子」—— 纯外观,来自他丢进来的文件。
     *
     *   合并的话,读文件这条路(外置存储可能没挂载、文件可能被推坏了)的
     *   任何一次失败都会波及「房间里有没有东西」—— 而那是不能出错的那一半。
     *
     * ★ 这一路上的每一种「没有」都要写得出来。理由见 [Wardrobe] 的文件头:
     *   「我换了它没变」和「我没放对文件夹」在屏幕上**一模一样**,只有日志分得开。
     */
    private fun pushRoom() {
        if (!herReady) return

        // ---- 一、room.json(可选)----
        val cfg = try {
            Wardrobe.roomJson(this)
        } catch (e: Exception) {
            ModelManager.get(this).trace("换装:房间配置判定出错(${e.javaClass.simpleName})")
            null
        }
        if (cfg != null) {
            ModelManager.get(this).trace("换装:${cfg.note}")
            val f = cfg.file
            if (f != null) {
                val text = try {
                    f.readText(Charsets.UTF_8)
                } catch (e: Exception) {
                    ModelManager.get(this).trace(
                        "换装:room.json 读不出来(${e.javaClass.simpleName}),当没写"
                    )
                    null
                }
                // ★ 整份原文交给 JS 去解析,不在这儿挑字段。
                //   在 Kotlin 侧解一份 JSON 意味着「哪些字段认得」有两处说法,
                //   而它们迟早会对不上 —— 那正是这个项目栽过的「工具表抄三遍」。
                //   ★ 用 JSONObject.quote 转义,不手工拼引号。
                if (text != null) {
                    eval("window.Her && Her.setRoom(" + JSONObject.quote(text) + ")")
                }
            }
        }

        // ---- 二、整个房间的模型(可选)----
        val model = try {
            Wardrobe.roomModel(this)
        } catch (e: Exception) {
            ModelManager.get(this).trace("换装:房间模型判定出错(${e.javaClass.simpleName})")
            null
        }
        if (model == null) return
        ModelManager.get(this).trace("换装:${model.note}")
        val f = model.file ?: return

        // ★ 传的是**真文件名**,不是写死的 "glb"。GLTFLoader 拿这个地址当相对基准
        //   去解析模型里引用的贴图 —— 名字对不上,那几条贴图就全都取不到,
        //   而在模型上只表现成「一片灰」,**不报错**。
        val name = JSONObject.quote(f.name)
        eval("window.Her && Her.setScenery(new URL($name, document.baseURI).href)")
    }

    /**
     * 把她的**动作**推给 JS —— 用户丢进 `motion/` 的那份 `.vrma`(见 [Wardrobe])。
     *
     * ★ 和 [pushRoom] / [pushRoomObjects] 有一处根本的不同:**动作不是页面来请求的**。
     *   模型和房间模型那两条都是「页面发一个请求 → 我们决定拿外部那份还是内置那份」;
     *   而动作**没有一个自然的请求方** —— 页面不知道他放了什么、也不知道该请求哪个名字。
     *   所以这一条是**我们主动推**:进房间时查一次盘,有就告诉她。
     *
     * ★ 没放就**一句废话都不说**(`Wardrobe.motion` 返回 null)—— 她照旧用自带的待机微动。
     *   但「目录里确实躺着 .vrma、却一个都用不了」那一种**必须说出来** ——
     *   它和「你没放」在屏幕上长得一模一样,只有日志分得开(见 [Wardrobe.motion] 的 KDoc)。
     */
    private fun pushMotion() {
        if (!herReady) return

        // ★★ 2026-10-09 起这里问的是 [Wardrobe.activeMotion],不是 [Wardrobe.motion]。
        //   两者**只差一件事**:他挑过的那条优先。
        //   ★ **没挑过的人走的仍是原来那条路**(activeMotion 里 chosenMotion 回 null
        //     就直接委托给 motion()),所以行为和今天**一字不差**。
        val hit = try {
            Wardrobe.activeMotion(this)
        } catch (e: Exception) {
            ModelManager.get(this).trace("换装:动作判定出错(${e.javaClass.simpleName}: ${e.message})")
            null
        }
        if (hit == null) return
        ModelManager.get(this).trace("换装:${hit.note}")
        val f = hit.file ?: return

        // ★ 和 [pushRoom] 同一个理由:传**真文件名**,不是写死的 "vrma"。
        //   GLTFLoader 拿这个地址当相对基准去解析文件里引用的东西 ——
        //   名字对不上就取不到,而那只表现成「她不动」,**一个字都不报**。
        //
        // ★★ 但库里的那份**不能只传文件名** —— 见 [motionServeUrl]。
        val url = JSONObject.quote(motionServeUrl(f))
        eval("window.Her && Her.setMotion(new URL($url, document.baseURI).href)")
    }

    /**
     * 上一次**由语气**推动作的时刻(`SystemClock.elapsedRealtime()`)。
     *
     * ★ 用开机以来的毫秒数,不用墙上时间 —— 这个项目吃过「跨机器比 `at`」的亏,
     *   而这里本来就不需要绝对时间,只需要「隔了多久」。
     * ★ 0 = 从没推过(见 [EmotionMotionMath.shouldFire]:这一格不能靠「差得够大」,
     *   否则开机头几秒的第一条动作会被静默吃掉)。
     */
    @Volatile private var lastEmotionMotionAt = 0L

    /**
     * 上一次报「这条动作找不到 / 对不上」时的**那一格** —— 只在换了的时候才记日志。
     *
     * ★ 她是按句念的:一段话会进来好几次 `startTalking`。不去重的话,
     *   缺一个文件就会把日志刷满,而刷满的日志**没人看**,等于什么都没说。
     */
    private var missingEmotionMotion = ""

    /**
     * 「她这一句是什么语气」→ 让她**做那条动作**。
     *
     * ---------------------------------------------------------------------------
     * ★★ 2026-10-17:用户原话「我想知道,agent 她现在语气能不能和动作匹配,这是我现在想做的」。
     *
     * 在这之前这条链是**断的**,而且断得很干净:语气早就算出来了([emotionOf]),
     * 但只送去给脸(`setEmotion`),**身体一步没动**。动作只有「进房间推一次」
     * ([pushMotion])和动作栏手挑两个触发点 —— 没有一次是跟着她说话走的。
     *
     * 查表那半在 [EmotionMotionMath](纯逻辑,有单测);这里只负责落地。
     *
     * ---------------------------------------------------------------------------
     * ★ 为什么 `once:true`(播一次就定格):
     *
     *   动作库里那 11 条全是 **3.93 秒**的循环动作。跟着语气播的如果用循环,
     *   她说完那句之后会**一直重复同一个动作** —— 那不是「她在生气」,是「她卡住了」。
     *   所以这一条路播一次、停在最后一帧。这是用户 2026-10-09 定的第一条:
     *   「她话没说完,但是动作做完了,那就直接定格在她最后的那个动作上」。
     *
     * ---------------------------------------------------------------------------
     * ★★ 为什么先过 [EmotionMotionMath.shouldFire] 这一道:
     *
     *   `Her.setMotion` 是**先把上一条停掉再装新的**。而她是按句念的(切句抢先音),
     *   一段长话会进来好几次 —— 不掐的话,动作被抢三次、每次从第 0 帧重来,
     *   画面上是**抽搐**。掐的判据用「距上次推动作够不够 4.2 秒」,
     *   等价于「上一条已经播完」,而它**不需要等一个可能永远不来的回调**。
     */
    private fun fireEmotionMotion(emotion: String) {
        if (!herReady) return

        // ★★ 他 2026-10-17 定的两句话:「笑不要那个动作」/「嘴巴动就行了」/
        //    「不要这么故意,不是什么话都要动作的」—— 落成一张 [EmotionMotionMath.SILENT]。
        //
        //    ★ **静静返回,不记日志**:这是**他要的行为**,不是故障。
        //    ★ 它挡在下面那道 4.2 秒的闸之前,**不消耗**那个间隔 ——
        //      `shouldFire` 只读不写,所以顺序上其实无所谓;放前面是为了读代码的人一眼看到
        //      「大多数句子走到这里就停了」。
        if (EmotionMotionMath.isSilent(emotion)) return

        val now = SystemClock.elapsedRealtime()
        if (!EmotionMotionMath.shouldFire(now, lastEmotionMotionAt)) return

        val rel = EmotionMotionMath.relativePath(emotion)
        if (rel == null) {
            // ★ 「不在表里」和「文件没找到」是两件事,日志里必须分得开 ——
            //   否则以后看到「她不动」,分不清该改表还是该补文件。
            reportEmotionMotionProblem("词:$emotion", "语气「$emotion」不在表里(表和 emotionOf 对不上了)")
            return
        }

        val f = try {
            Wardrobe.motionFile(this, rel)
        } catch (e: Exception) {
            reportEmotionMotionProblem(
                rel, "动作判定出错(${e.javaClass.simpleName}: ${e.message})")
            return
        }
        if (f == null) {
            reportEmotionMotionProblem(rel, "库里的 $rel 读不到,「$emotion」这条语气她动不了")
            return
        }
        missingEmotionMotion = ""

        // ★ 记在**真的推出去之后**。算在了前面而这里没推成的话,
        //   接下来 4.2 秒里所有真该播的动作都会被这道闸挡住。
        lastEmotionMotionAt = now

        val label = "$emotion@$now"
        val url = JSONObject.quote(motionServeUrl(f))
        val id = JSONObject.quote(label)
        her("setMotion(new URL($url, document.baseURI).href, {once:true, id:$id})")
        ModelManager.get(this).trace("动作:语气 $emotion → ${f.name}")
    }

    /** [fireEmotionMotion] 出问题时说一句 —— **同一格只说一次**,不刷屏。 */
    private fun reportEmotionMotionProblem(key: String, msg: String) {
        if (missingEmotionMotion == key) return
        missingEmotionMotion = key
        ModelManager.get(this).trace("动作:$msg")
    }

    /**
     * 这一份动作文件,页面该用**哪个地址**去取它。
     *
     * ★★ 这个函数的存在只有一个理由,而它是个**会静默出错**的理由:
     *
     *   页面那边是 `new URL(名字, document.baseURI)`,而 `baseURI` 是
     *   `https://…/her/index.html` —— 所以一个**裸文件名**(`Relax.vrma`)会解析成
     *   `…/her/Relax.vrma`,而 `serveAsset` 只会拿它去 `motion/` 里找。
     *   **库里的文件根本不在那儿** —— 于是它一路掉到 `assets.open`,
     *   最后是一条**静默的 404**,症状是「她不动,一个字都不报」。
     *
     * ★ 所以:是库里的那份 → 给 `motion-lib/<相对路径>`(走 [LIB_PREFIX] 那条路);
     *   不是 → 原样给文件名(**`motion/` 那套一个字节都不动**)。
     *
     * ★ 怎么判「是不是库里的」:**拿库根做一次包含判定**,不猜、不看名字。
     *   `File.base` 对不上(或 canonical 化失败)就退回老路 —— 退回是安全的:
     *   老路最多是「取不到」,而**猜**是「取到了别的文件」,后者更糟。
     */
    private fun motionServeUrl(f: File): String {
        val rel = try {
            Wardrobe.motionLibraryDir(this)?.let { base ->
                val prefix = base.canonicalPath + File.separator
                val p = f.canonicalPath
                if (p.startsWith(prefix)) {
                    p.removePrefix(prefix).replace(File.separatorChar, '/')
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            ModelManager.get(this).trace(
                "动作库:算 ${f.name} 的取用地址出错(${e.javaClass.simpleName}: ${e.message})")
            null
        }

        // ★ 第二道:这条相对路径要**当场过一遍白名单**才敢拼进 URL。
        //   它其实进过 [Wardrobe.motionFile] 的同一道闸,但这里拼的是
        //   **要给出去的那个字符串** —— 在这个项目里,给出去的字符串一律当场验一次。
        if (rel != null && WardrobeMath.safeLibraryPath(rel)) return "motion-lib/$rel"

        // 认不出 / 判不过 → 老路(裸文件名,给 motion/ 那套用)。
        return f.name
    }

    /**
     * 把房间里的东西摆出来 —— 清单从 [HandRegistry] 来。
     *
     * ★★ **这是这一整块的关键**:房间里摆什么,和「她会做什么」是**同一份数据**。
     *    空调不是 UI 里的一个按钮,它是注册表里的一条;这里**不写死任何一件东西**。
     *    加一只假手 → 房间里自动多一件,`her.js` 一个字符都不用改。
     *
     *    反过来做(JS 里画死一份物件、Kotlin 里写死一份能力)就是这个项目已经
     *    吃过一次的「工具表抄三遍」——PC `ai_tools.py` 一份、手机 `TOOL_SCHEMA`
     *    一份、`SYSTEM_PROMPT` 里再抄一份,加一个能力要改三处,而且漏一处不报错。
     *
     * ★ 只推 `id` / `kind` / `name` 三个字段:
     *   - `id`   —— 点回来时用它反查是哪只手
     *   - `kind` —— 只决定「长什么样」(显示器 / 一块竖板 / 方块)
     *   - `name` —— 留给以后做标签用,现在 JS 不显示
     *   **不推工具表**:那是给模型看的,和「房间里长什么样」无关,推过去只是
     *   白白多传几 KB。
     */
    private fun pushRoomObjects(force: Boolean = true) {
        // 她还没出场:别推。`window.Her &&` 那道守卫会让这一句**静默无效**,
        // 而 onReady 里会自己推一次,所以这里直接返回是对的。
        if (!herReady) return

        val list = JSONArray()
        // ★★ 只摆**实物**(见 [HandMath.isFixture])。
        //
        //   不筛的话,云端那几只(天气 / 地图与路线 / 上网查)会**一只一个方块**地
        //   长到她屋里来 —— 而它们不是东西,是能力。用户 2026-10-05 原话:
        //   「**像天气,地图,路线这些它不是具体的外设,所以不需要单独的 AI 助手外设**」。
        //
        //   ⚠️ 判据是 kind,**不是**「列表非空」。这里曾经是「遍历 all() 全摆」,
        //   它当时是对的 —— 因为那会儿 all() 里只有电脑和手机自己。
        //   云端手一接进来,同一个循环就变成了错的,而且**不报错**。
        for (h in HandRegistry.all()) {
            if (!HandMath.isFixture(h)) continue

            // ★★ 2026-10-06:电脑**暂时不摆进房间**(他拍板:「还是隐藏起来先吧,决定了」)。
            //
            //   一行的事,但它同时管两件事 —— 因为 [Her.setObjects] 那一份清单
            //   既是**摆在屋里的东西**,也是**射线能打到的东西**(her.js 的拾取只遍历
            //   被推过去的那些)。不推 = 屏幕上看不见,也点不到。不用再单独去关点击。
            //
            //   ★ 这不是软锁:进电脑那页的门**不在这里** —— 房间右上角 ⚙ 列表里
            //     那一项(见 `settingRow("电脑", …)`)照旧在,悬浮窗那条路也照旧。
            //
            //   ★ 他说的「先」是字面意思:想让她屋里重新长出那台电脑,**删掉这一行就行**,
            //     别的地方一个字都不用动。`HandMath.isFixture` 把电脑算作实物仍然是对的
            //     —— 是这一行在临时压着它,不是那个判据过时了。
            if (h.kind == HandMath.KIND_PC) continue

            // ★★ 2026-11-08:手机自己**也不摆进房间**了(他拍板)。
            //   他原话:「房间有一个("那是我自己,不用点她")的很早之前的问题」。
            //   那块竖板点了只会说一句「那是我自己…不用点它」—— 是死胡同,
            //   而屋里每一件东西都该是**能点的**(见 [HandMath.isFixture] 的判据)。
            //
            //   ★ 她**照样能**操作这部手机:[SelfHand] 那只手仍在注册表和工具清单里,
            //     这一行只挡「摆在屋里」,不挡「她会做什么」。也**不是**软锁:
            //     进各页面的门都在右上角 ⚙ 里,和电脑那条一样。
            //   ★ 判据写在**这里**而不是改 [HandMath.isFixture]:isFixture 还管着
            //     中转站「她的手」那一列(那边要列出手机自己),两者的答案不一样。
            if (h.kind == HandMath.KIND_SELF) continue

            list.put(JSONObject().apply {
                put("id", h.id)
                put("kind", h.kind)
                put("name", h.name)
            })
        }
        val json = list.toString()

        // ★ 没变就一个字都不干。onResume 会调这个(见那里),而 onResume 走得很勤
        //   —— 每次都重建一遍物件会让它们**闪一下**:位置、旋转全部从头来。
        if (!force && json == lastRoomJson) return
        lastRoomJson = json

        // ★ 用 JSONObject.quote 转义,不手工拼引号 —— 手写转义在这个项目里
        //   已经埋过一次雷(中文/引号一进来就断)。
        eval("window.Her && Her.setObjects(" + JSONObject.quote(json) + ")")
        ModelManager.get(this).trace("房间:摆出 ${list.length()} 件东西($json)")
    }

    /**
     * 房间里的东西被点了一下。
     *
     * ★ 按 **kind** 认,不按 id 认。电脑那只手的 id 是**电脑的主机名**
     *   (`ai_tools.hand_info()` 里是 `socket.gethostname()`),不是 `"pc"`
     *   —— 写死 `id == "pc"` 在真机上一次都命中不了,而且**不报错**。
     */
    private fun objectTapped(id: String) {
        val hand = HandRegistry.get(id)
        val kind = hand?.kind
        ModelManager.get(this).trace(
            "房间:点了 $id(${hand?.name ?: "?"} / ${kind ?: "不在名单里"})"
        )
        when (kind) {
            HandMath.KIND_PC -> openTouchpad()

            HandMath.KIND_SELF ->
                // 手机自己就在她手里 —— 没有「另一页」可去,她做的事本来就是这里的事。
                // ★ 2026-11-08 起这条在真机上**走不到**:pushRoomObjects 已经不再把
                //   self 摆进屋里(见那里的注释)。留着是防御 —— 万一以后又有东西
                //   把 self 推过来,回一句总比静默强。
                say("那是我自己。手机上能做的事,你直接说就行 —— 不用点它。")

            else ->
                // ★ 绝不静默。点了没反应和点错了地方长得一模一样,
                //   说一句「打不开」比什么都不说强得多。
                say("这个我还打不开。")
        }
    }

    /**
     * 去「电脑」那个页面。
     *
     * ★ 它补的是身份合并留下的一个洞,不是临时糊上去的按钮:
     *
     * 全项目**只有** [MainActivity] 会 `autoConnectLast()`(跨重启免密自动连上次那台电脑),
     * 而它的入口本来是 `SplashActivity` —— 合并成「只有一个 App」时那个 LAUNCHER filter
     * 被摘掉了,于是 [MainActivity] 在 UI 上变成了**孤岛**:桌面图标只到 [ConMarnActivity],
     * 她这里又不跳过去。表现就是**电脑明明在跑、网络也通,她却永远显示「电脑没连」**
     * —— 因为根本没有人去连。
     *
     * ★ 所以这里**不自己实现连接**(配对码对话框、免密续连、媒体流那一整套都在
     *   [MainActivity] 里,搬过来就是抄第二份)。只负责把那扇门重新打开。
     *   计划里「点房间里的那台电脑进电脑页面」做出来之后,这条路仍然该留着 ——
     *   点连接状态去看/去连,本来就是它该有的意思。
     */
    /**
     * 进电脑页。
     *
     * ★★ 2026-10-08 加的那一句:连自动连都指望不上时,**落地就直接开始扫**。
     *
     * 起因是这一轮给自动连加的第一条闸:**盘上没有免密凭证就不自动拨**。
     * 闸本身是对的(它掐的是配对码风暴),但它带出一个新的空洞 ——
     * 新电脑、或者凭证已经没了的手机,点进电脑页会看到**一页空白**:
     * 不自动连、也不提示、也不扫。而空白**和「坏了」长得一模一样**。
     *
     * 所以这里先问一句 [PcLink.willAutoConnect](只问,不动手):
     *   - 会自动连 → 什么都不做,照老样子进页面,那趟自己会拨;
     *   - 不会自动连 → 留一张「落地就扫」的便条([MainActivity.pendingScan])。
     *     ★ 扫描是**用户明确发起**的连接,不受那几条自动连的闸管 —— 这正是闸想要的:
     *     「别自作主张,**等他自己点一下**」,而这一下就是他点的。
     */
    private fun openTouchpad() {
        try {
            if (!PcLink.willAutoConnect(this)) MainActivity.pendingScan = true
            startActivity(Intent(this, MainActivity::class.java))
        } catch (e: Exception) {
            // ❌ 便条要撕掉 —— 留着的话他下次正常进电脑页会莫名其妙开始扫描,
            //    而且没有任何东西告诉他为什么(同 scanFromRoom 那条注释)。
            MainActivity.pendingScan = false
            // 起不来要说出来 —— 静默失败的样子是「点了没反应」,和「点错地方」分不清
            ModelManager.get(this).trace("进电脑页面失败(${e.javaClass.simpleName}: ${e.message})")
            toast("打不开电脑页面:${e.message}")
        }
    }

    /**
     * 房间顶栏那颗「扫描」:去电脑页,而且**一落地就开始扫**。
     *
     * ★ 这三行是照 [StationOverlay.goScan] 的口径写的,**不是另发明一套** ——
     *   扫描要 toast 进度、要申请「本地网络」权限、要弹配对码,而那三样**全都长在
     *   [MainActivity] 上**(权限回调更是只有 Activity 才收得到)。所以这里只能
     *   「下张便条 + 把界面叫起来」,不能就地开扫。
     *
     * ★ 起不来就把便条**撕掉**。留着的话,他下次正常进电脑页会**莫名其妙开始扫描**,
     *   而且没有任何东西告诉他为什么 —— 正是这个项目最怕的那种静默。
     */
    private fun scanFromRoom() {
        MainActivity.pendingScan = true
        try {
            startActivity(Intent(this, MainActivity::class.java))
        } catch (e: Exception) {
            MainActivity.pendingScan = false
            ModelManager.get(this).trace("房间:去扫描失败(${e.javaClass.simpleName}: ${e.message})")
            toast("打不开电脑页面:${e.message}")
        }
    }

    /** @return 拨完之后是「开」吗 —— ★ 调用方要拿它决定弹不弹那一页,所以它**不能自己弹**。 */
    private fun toggleQuiet(): Boolean {
        val on = !ProactiveGreeting.isQuietOn(this)
        ProactiveGreeting.setQuietOn(this, on)
        ModelManager.get(this).trace("免打扰:${if (on) "开" else "关"}")
        // 关 → 直接关,不弹框(多问一句只会让人烦)
        return on
    }

    /**
     * 从「关」到「开」时弹这一次。**它不是多余的确认,是这个功能唯一那个严重 bug 的解药:**
     * 「她不理我」看起来像「她不高兴了」,不像 bug —— 所以必须先把边界说清楚,
     * 而且要说在**他打开它的那一刻**,不是写在某个他永远不会翻的说明里。
     */
    /** 版本名读一次就够；读不到就给空串 —— 这一页宁可少一行，也不能崩。 */
    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    /**
     * 使用说明：**全部解释文字的唯一边**。
     *
     * ★ 2026-11-06 他连着三次说同一件事：「怎么还有各种底下的说明」「说明放在设置的使用说明里」
     *   「所有的提示……就不要挂在最底下，难看死，跟玩具一样的」。
     *   所以各设置页行下面只留**状态**（开/关、几件、几级），一长句解释全部搬到这里；
     *   原来挂在列表最底下那条「彻底关掉」也一并搬进来（见本节最后一段）。
     *
     * ★ 正文一律书面语，不用星号加粗 —— 这些字是直接画出来的，`**` 不会被渲染成粗体，
     *   只会原样留在屏幕上（这件事已经在真机上被抓到过）。
     */
    private fun showUsage() {
        val box = column()

        // 一段正文：短、书面、不复述行标题。
        fun para(t: String) = TextView(this).apply {
            text = t
            textSize = 12f
            setTextColor(getColor(R.color.glass_dim))
            setPadding(dp(2), dp(6), dp(2), 0)
        }

        fun group(t: String, vararg ps: String) {
            box.addView(sectionTitle(t))
            ps.forEach { box.addView(para(it)) }
        }

        group(
            "连接",
            "「电脑」指的是同一台机器上的电脑端服务。她的推理与对话在这台手机上完成，电脑只承担执行动作的部分。电脑端未连接时，她仍然可以对话，只是无法操作电脑。",
        )

        group(
            "说话与回应",
            "「出声」控制她是否朗读回答。「唤醒词」开启后，说出唤醒词即可叫起她，无需解锁操作屏幕。",
            "「主动」开启后，她会在长时间没有交流时主动说一句话。「免打扰」及其时段内，她不主动出声，也不播报通知。",
        )

        group(
            "房间",
            "左侧显示当日热点，每一页一条。新闻取自十一个来源：百度、微博、知乎、抖音、腾讯新闻、IT之家、36氪、量子位、少数派九个公开榜单，以及新华网与人民网的当日要闻（后两者取自官网首页，排序时优先）。合并后去重。上下划动阅读全文，左右划动换下一条，点开一条会用系统浏览器打开那家媒体的原网页。每小时整批更换一次。",
            "热点按你的偏好排序：你点开过的条目会让同类标题往前排。这份偏好只在这台手机上计算和保存，不发往任何外部服务，也不会随对话发出。点开过的条目本身不保留原文，只保留无法还原的记号。",
            "右侧分为上下两块。上方是今日天气，按网络出口所在城市获取，城市与坐标保存在本机；下方显示今天还要执行的定时任务，没有任务时改为显示中英文语录。语录取自一言、今日诗词、dummyjson 与 stoic-quotes 四个公开来源，文字自动缩放到方框内，不滚动。",
            "「云端总闸」关闭时，热点与天气都不会对外取用，屏上只显示此前保存过的内容。定时任务与语录不受影响，全部在本机完成。",
        )

        group(
            "她",
            "「她的性格」里可以查看和调整心情、亲密度，以及她长期的处事方式。心情与亲密度随时间变化，也可由对话影响；长期设定拨动后保持有效。所有调整即时生效，关闭页面后仍然保留。",
            "「记忆库」是她在处理问题中积累的做法，以及关于你的记录。可以逐条查看、修改或删除。",
            "「她一直在」开启后，她保持常驻，随时可以叫起。「不用时关模型」开启后，长时间无操作会释放模型占用的内存，下次使用时重新装载。",
        )

        group(
            "能力",
            "「API 控制中心」按用途组织外部服务。一条用途代表她的一类查询能力，其下可以配置多家服务商，同一时刻只有一家生效。未配置的用途不会发出任何请求。",
            "「模型清单」按文字、写代码、看图三类分别配置模型，未配置的类别回落到「云端总闸」。看图一类只处理派发出去的任务，操作屏幕时使用的识别始终在本机完成，截图不会按这一类发出。",
            "「技能」是她可以复用的做法，可以由对话生成，也可以在工作台完成后保存，或在此页手写。触发词用于在对话中自动想起该技能；手写的技能每次执行前都会先征求同意。",
            "「工作区」存放她在「代码」一档中产出的文件。网页文件可以全屏打开，也可以放置到桌面，使用时与其他应用一致。",
            "「她记得的事」是本地保留的对话记忆，按字面相近程度召回。开启「云端总闸」时，被召回的内容会随当轮请求一并发出，可以在此逐条删除。",
        )

        group(
            "安全",
            "「云端总闸」在本机答不上来时改用云端模型。关闭它等于总闸：联网查询与电脑端操作一并停用。",
            "「云端只见代号」开启后，发给云端的请求中，人名、地名等已登记的敏感词替换为代号。对照表只保存在本机，不随请求发出。",
            "「开 App 要指纹」开启后，进入应用需要指纹验证；涉及支付与身份的操作会再次验证。本机不支持时会显示为不可用。",
            "「通知权限」用于她在后台运行时播报消息。系统未授权时，需要在此重新申请。",
        )

        group(
            "关闭",
            "以上各项均为应用内开关。若需要彻底停止，包括不再响应开机启动，请在系统「设置」→「应用」→「ConMarn」中选择「强制停止」。",
        )

        glassReturn = { showSettings() }
        glassBuilder("使用说明")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("好", null)
            .show()
            .glassified()
    }

    /**
     * 关于本机。
     *
     * ★ 2026-11-06 他点名要的和 DAW（SingMic Studio）那一页同一种东西：**版本**。
     * ★ 只报**能查到的**：版本、构建号、Android、机型、首次安装时间。
     *   查不到的不写一行占位 —— 一页「—」比没有这一页更让人以为是坏的。
     */
    private fun showAbout() {
        val pkg = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (_: Exception) {
            null
        }
        val ver = pkg?.versionName ?: "—"
        val code = if (pkg != null && Build.VERSION.SDK_INT >= 28) pkg.longVersionCode.toString()
        else pkg?.versionCode?.toString() ?: "—"
        val installed = pkg?.let {
            android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", it.firstInstallTime)
        }?.toString() ?: "—"
        val body = buildString {
            append("ConMarn  ").append(ver).append("\n")
            append("构建号  ").append(code).append("\n\n")
            append("Android  ").append(Build.VERSION.RELEASE)
                .append("（API ").append(Build.VERSION.SDK_INT).append("）\n")
            append("机型  ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n")
            append("首次安装  ").append(installed).append("\n\n")
            append("包名  ").append(packageName)
        }
        glassReturn = { showSettings() }
        glassBuilder("关于本机")
            .setMessage(body)
            .setPositiveButton("关闭", null)
            .show()
            .glassified()
    }

    private fun showQuietIntro() {
        val s = ProactiveGreeting.quietStart(this)
        val e = ProactiveGreeting.quietEnd(this)
        glassBuilder("免打扰 ${fmtMin(s)} – ${fmtMin(e)}")
            .setMessage("这段时间我不会主动开口。\n\n你找我,我照常回。")
            .setPositiveButton("好", null)
            // ★ 这两颗**要先把回程办掉再弹系统那个时间选择器**:这一层的关闭是晚一步的,
            //   不先办的话它会把 ⚙ 列表**盖到系统弹窗上面**去(系统弹窗在它下面,点不着)。
            //   先办的话顺序正好和今天一致:⚙ 列表在底下,时间选择器在最上面。
            .setNeutralButton("开始时间") { _, _ -> glassReturnNow(); pickQuietTime(true) }
            .setNegativeButton("结束时间") { _, _ -> glassReturnNow(); pickQuietTime(false) }
            .show()
            .glassified()
    }

    private fun pickQuietTime(isStart: Boolean) {
        val cur = if (isStart) ProactiveGreeting.quietStart(this) else ProactiveGreeting.quietEnd(this)
        // ★ 框架自带的 TimePickerDialog(24 小时制跟随系统)——
        //   这是本项目第一个。它的样子和房间里那套粉色手绘风**不搭**,
        //   但为了它手写一个时间选择器不值(`src/main` 里连一个先例都没有)。
        //   以后加「23–07 / 22–08 / 00–09」预设列表是顺手的事,现在不做。
        //
        // ★★ 2026-10-08 补:设置面板整套换玻璃时,**这个弹窗是故意没换的**。
        //   它是系统自带的**整块不透明**弹窗(自己那套主题跟随 Activity,不认 dialog 主题),
        //   把它套上玻璃那个透 `windowBackground` 会**读不清上面的时间和按钮** ——
        //   那是退步,不是美化。所以它留在系统原样。看见这里和别处不一样**别当漏改**。
        //   (同一条理由见下面 glassBuilder 那段注释:玻璃只给「内容由我们自己搭的」弹窗。)
        TimePickerDialog(
            this,
            { _, h, m ->
                val start = if (isStart) h * 60 + m else ProactiveGreeting.quietStart(this)
                val end = if (isStart) ProactiveGreeting.quietEnd(this) else h * 60 + m
                ProactiveGreeting.setQuietRange(this, start, end)
                toast(
                    if (start == end) "起止一样 = 没设,我不会静默。"
                    else "免打扰 ${fmtMin(start)} – ${fmtMin(end)}"
                )
            },
            cur / 60, cur % 60,
            DateFormat.is24HourFormat(this)
        ).show()
    }

    private fun fmtMin(minuteOfDay: Int) = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    /**
     * 从系统选文件的界面回来。
     *
     * ★ 取消也要把「共用记忆」那一页重新打开：开系统界面之前**先关掉了它**（两层叠着会闪），
     *   不重开的话他一按返回就落回空白，看着像把东西弄丢了。
     * ★★ 这一页的重开**只由这里负责** —— 关掉的时候回程已经置空（见 [showAgentMemory]），
     *   否则两条路各开一层，叠在一起，返回要点两下。
     */
    @Deprecated("这一层是 android.app.Activity，没有 androidx 的 result 通道，照旧用它。")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // ★ 2026-11-07：挑照片（给 SelfHand.see_photo 用）。**只回一句结果**，
        //   转码在等它的那个线程上做 —— 主线程不干重活。
        if (requestCode == REQ_PICK) {
            val q = pickQueue
            val uri = if (resultCode == RESULT_OK) data?.data else null
            q?.offer(uri ?: PICK_CANCELLED)
            return
        }
        if (requestCode != REQ_DOC) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            showAgentMemory()
            return
        }
        ingestDoc(uri)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        // 日历权限（写系统日程，见 [CalHand]）：结果由那一头唤醒等着它的线程，
        // 这里不用再说一句话 —— 她说的是它回给模型的那句。
        if (CalHand.onResult(requestCode, grantResults)) return

        // 蓝牙权限（见 [BleHand]）：同样是「唤醒等着它的那个线程」。
        // ★ 不分批准还是拒绝 —— 那一头会自己重查一次权限，**唯一诚实的判据是
        //   系统现在到底给没给**，而不是我们记下的那次结果。
        if (BleHand.onResult(requestCode)) return

        // 「本地网络」权限(Android 16+):拿到就**接着把刚才那次自动连接发起** ——
        // 见 [onCreate] 里那段。★ 没拿到也**什么都不说**:她没有电脑照样活,
        // 而她答不出来「电脑上的活」时那句回执已经说清楚了,再叠一句弹框只是吵。
        if (requestCode == PcLink.REQ_LOCAL_NET) {
            root.post { PcLink.connectLast(this) }
            return
        }

        // 定位:给了就立刻去定一次,天气下一次取的时候就用上（见 [Where]）。
        // ★ 拒了**什么都不说**:这条只影响天气准不准,不影响任何功能，
        //   再弹一句「你没给权限」只是提醒他做了一次拒绝。
        if (requestCode == REQ_LOC) {
            if (grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                Thread({ Where.refresh(this) }, "where").apply { isDaemon = true }.start()
            }
            return
        }

        // 通知权限:真拒了要说清楚**后果**,不能只说「好/不好」——
        // 它的失效样子是「她还在跑,但通知栏里那条不出现」,而那看起来像没生效。
        if (requestCode == REQ_NOTIF) {
            // ★ `==` 不能另起一行 —— Kotlin 会把上一行当成语句结束(`Expecting an element`)。
            val ok = (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
            // ★★ 拿到权限**不算完**,还得让她把那条通知重发一遍 ——
            //    因为 startForeground() 只在服务启动那一刻跑过一次,而服务多半**早就起了**
            //    (那一次被系统挡掉了)。不补发的话:权限给了、AppSettings 也变成 DEFAULT 了,
            //    **通知栏里照样什么都没有**,而「让她睡」就挂在它上面。
            //    2026-10-04 真机就是这个状态:numEnqueuedByApp=8 / numPostedByApp=0。
            // ★★ 2026-11-08(体检 E1)：这条现在有**两个**入口 ——「让她一直在」
            //   和「定了定时任务」。文案得同时说清楚两件事，否则从任务那边进来的
            //   人会以为刚才那句话答非所问。
            if (ok) HerLifeService.repostNotification()
            ModelManager.get(this).trace(
                if (ok) "通知权限:给了 —— 常驻通知、「让她睡」、定时任务的结果现在都能送到"
                else "通知权限:拒了 —— 她照常跑,但通知栏里不会有常驻那条,定时任务的结果也送不到"
            )
            toast(
                if (ok) "好。通知栏现在能用了 ——「让她睡」和定时任务的结果都走那儿。"
                else "没给通知权限。她还在跑,但定时任务的结果送不到、通知栏里也不会有「让她睡」。想改去 设置→通知→ConMarn。"
            )
            return
        }

        // 耳朵要的麦克风权限。拿到了就**接着把刚才那一下按完** ——
        // 他只是被系统弹框打断了一次,不该让他再按住一次胶囊。
        if (requestCode == REQ_EAR) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                toggleEar()
            } else {
                // ★ 拿不到麦 = 说话这条路没有,那就**当场把他送进打字那一态** ——
                //   光说一句「我听不见」而屏幕上什么都不变,他会以为是自己按错了。
                toast("不给麦克风权限,我听不见。给你打开打字了。")
                setTypingMode(true)
            }
            return
        }

        if (requestCode != REQ_WAKE) return
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            WakeWordManager.setEnabled(this, true)
            // ★ 唤醒词在这个项目里改过两次(若息 → ConMarn → **从漫**),
            //   而这句话每次都落在后面 —— 他 2026-10-04 就是被这种
            //   「界面说的和实际不是一回事」惹到的。
            //   不在这里写死名字更好,但唤醒词本身还没做成配置,先跟着改。
            toast("开了。喊「从漫」叫她 —— 通知栏那条「语音唤醒」是它自己,不用管")
        } else {
            toast("不给麦克风权限,唤醒开不了")
        }
    }

    // ==================================================================

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 短促震动 —— 他 2026-10-04 晚点名要的这一下:「**点那个语音对话框,它需要震动一下**」。
     *
     * ★ 为什么这一下不是装饰:那条磨砂胶囊上**同时挂着两件事** —— 按住说话、点一下打字,
     *   而两件事在**按下的那一瞬间长得一模一样**(区别要到松手才判得出来,见 [onCaptionTouch])。
     *   所以指头下面必须**当场**有个回执,否则他按下去的第一秒是不知道自己按中了没有的。
     *   ★ 用 [Vibrator] 而不是 [View.performHapticFeedback]:后者会被系统的「触感反馈」
     *     总开关静默吃掉,而这台机器上「按下去了但什么也没发生」正是最难查的那类症状
     *     (permission 在清单里,不用新加)。
     *
     * ⚠️ 时长给短:这是个**按下**的回执,不是「开始录音」的宣告 —— 后者由 [confirmHold]
     *   那一声「我在听」负责,长震会和它撞在一起,听起来像是按了两下。
     */
    private fun buzz(ms: Long = 18L) {
        try {
            val vib = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(ms)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 她房间里的按钮都从这儿出。
     *
     * 为什么不用裸的 `Button(this)`:不指定 background 的 Button 会吃主题里的 accent ——
     * 那是触控板主界面留下的绿色 #4CAF50。真机截图里,一屏深色界面上顶着一排荧光绿,
     * 像是从别的 App 借来贴上去的。统一走 btn_her(深底 + 她的粉描边);
     * 「说」这种要按下去的主按钮传 btn_her_primary(实心粉)。
     */
    private fun herButton(
        label: String,
        size: Float,
        bg: Int = R.drawable.btn_her,
        /**
         * 字色。默认是浅色(房间里所有按钮都压在深底上)。
         * ★ 唯一的例外是那颗**白色圆箭头**的发送键 —— 它是全房间唯一一颗
         *   浅底按钮,浅色字压上去会当场看不见(见 [sendBtn] 那行)。
         */
        fg: Int = 0xFFF2F3F5.toInt(),
        onClick: () -> Unit
    ): Button = Button(this).apply {
        text = label
        textSize = size
        setTextColor(fg)
        background = getDrawable(bg)
        // 默认 Button 自带 48dp 的最小尺寸和一肚子内边距,会把固定宽高的布局撑歪。
        setPadding(0, 0, 0, 0)
        minWidth = 0; minHeight = 0
        minimumWidth = 0; minimumHeight = 0
        isAllCaps = false
        // ★ **永不许折行** —— 2026-10-04 真机截图里抓到的:「唤醒:无耳朵」在 58dp 宽里
        //   折成两行,第二行被 32dp 的固定高**切掉**。折行 + 固定高 = 半个字,
        //   而且看起来只是「有点怪」,不像 bug —— 这种坏最难被发现。
        //   宁可横向挤出去(一眼看得出),也不要竖着被切(要盯着看才看得出)。
        maxLines = 1
        setOnClickListener { onClick() }
    }

    companion object {
        private const val TAG = "ConMarn"
        private const val REQ_WAKE = 104
        /** 通知权限(Android 13+ 运行时权限)。清单里声明了不算数,必须问。 */
        private const val REQ_NOTIF = 105
        /**
         * 耳朵要的麦克风权限。
         *
         * ★ 和 [REQ_WAKE] **不是一个**:两个都要 `RECORD_AUDIO`,但拿到之后干的事不同 ——
         *   那个去开系统唤醒(这台机器上根本开不了),这个去开她自己内嵌的耳朵。
         *   合成一个的话,回调里分不出该叫醒谁。
         */
        private const val REQ_EAR = 106
        /** 定位（天气用，见 [Where]）。★ 和蓝牙那条 FINE_LOCATION 不是一回事。 */
        private const val REQ_LOC = 109
        /** 往「共用记忆」里选一个资料文件（见 [ingestDoc]）。 */
        private const val REQ_DOC = 107
        /** 挑一个文件（照片 / 表格，见 [pickFileBlocking] 与 `SelfHand` 的 `see_photo`、`open_table`）。 */
        private const val REQ_PICK = 108
        /** 等用户挑照片的上限。兜底而已：正常是选完或按取消就回来。 */
        private const val PICK_WAIT_MS = 10 * 60 * 1000L
        /** 「他取消了」—— 和「挑好了」区分开，两句回执不一样。 */
        private val PICK_CANCELLED = Any()
        /** 「她在不在」用的点名 key(见 ProactiveGreeting.visibleScreens)。 */
        private const val SCREEN = "ConMarnActivity"

        /**
         * 窗外那片景多久对一次表。
         *
         * ★ 天气是**每小时**才更新的（见 [Weather]），天光也是一小时换一档 ——
         *   比这更勤只会多读盘。一分钟一次，换点最多晚一分钟，看不出来。
         */
        private const val SCENE_EVERY_MS = 60_000L

        /**
         * 每天那句开场白里，天气多旧算旧。
         *
         * ★ 半小时。「未来两小时要不要带伞」拿三小时前的数据答，等于没答；
         *   而每答一次都重新求一次接口又太贵 —— 半小时这个量级刚好卡在「同一场雨里」。
         */
        private const val WEATHER_FRESH_MS = 30 * 60_000L

        /**
         * 「他是在悬浮窗那颗球上按的 🎤」—— 进了房间**直接开始聊**,不用再按一次。
         *
         * ★ 为什么语音只在房间里,不在那颗球上(2026-10-04 晚定的):
         *   他要的是**对话**不是听写(见 [earCallbacks] 那一整段),而对话是
         *   「说 → 她答 → 接着说」的**循环**,循环里牵着一整套东西 ——
         *   半双工闸(她说话时麦必须关)、TTS 的 `onDone`、45 秒静默窗口、
         *   手指按住/点一下/上滑取消三种手势、还有那块会亮的胶囊。
         *   那一整套今天长在这个 Activity 里;把它搬进一个 40dp 高的悬浮条,
         *   等于把已经跑通的东西重写一遍,而**悬浮条上没有一个地方放得下
         *   「她在听/她在说」这盏灯** —— 而那盏灯是隐私那条线上的东西。
         *
         *   所以:球上的 🎤 = **把房间开起来、并且替他把那一按按完**。
         *   少按一次、少找一次,而对话本身仍然跑在它唯一跑得动的地方。
         *
         * ★ 不用 Intent extra:房间是 `singleTask`,从球上进房间走的是
         *   **onNewIntent + onResume**(不是 onCreate),extra 得在两条路上各取一次;
         *   而这个标志是**进程级**的,哪条路进来都读得到。同 `MainActivity` 的
         *   `pendingScan`/`pendingDisconnect` 一套办法。
         */
        @Volatile var pendingVoiceSession = false

        // ── 她的嗓子:音色 / 音高 / 语速(见 applyTtsVoice)
        //
        // ★★ 2026-10-05:**这几个 key 故意不是 `private` 了** —— [HerVoice] 要读同一份。
        //   分开定义是「同一个设置两份来源」,那正是这个项目吃过好几次的病
        //   (PC 的工具表抄三遍)。**她的嗓子只有一个设置,不管从哪条路说话。**
        const val PREFS_VOICE = "conmarn_voice"
        const val KEY_VOICE = "voice"      // 引擎音色名;空 = 用引擎默认
        const val KEY_PITCH = "pitch"
        const val KEY_RATE = "rate"

        /**
         * 内嵌那份 VITS 模型里的**第几个嗓子**。这台机器上是 804 个,写死用的第 0 个。
         *
         * ★ 和上面三个 key 放一起是**故意的**:都是「她的嗓子」这一件事,
         *   分成两份来源就是「同一个设置两个真相」那个病。
         *   读它的是 [SherpaVoice],改它的是房间 ⚙ 里的「她的声音」试听。
         */
        const val KEY_SPEAKER = "speaker"
        const val DEF_SPEAKER = 0

        /**
         * 她说一句话用**哪副嗓子**。
         *
         * ★★ 2026-10-08,他听完两副之后的裁决:
         *   > 「继续找,因为**感觉就那个不卡**,我就要那个」
         *   > 「你直接用到我手机里面,我用 APP 自己听,这样不可以吗」
         *
         * 两个值:
         *
         * - [ENGINE_SYSTEM] —— **系统那个引擎**。这台机器上叫
         *   `com.oplus.ttsaccessibilityengine`,而它**是这台机上唯一的 TTS 引擎、
         *   也是默认引擎**(`cmd package query-services -a android.intent.action.TTS_SERVICE`
         *   只回它一条,`isDefault=true`)。它是**边合成边出声**的(那个 .so 导出的就是
         *   `oai_stream_processor_start/compute/end`),所以**第一声几乎立刻就来** ——
         *   这就是他说的「不卡」。
         *   代价:多音字按它自己的词表走(「调试」有念成 diào 的风险)。
         *
         * - [ENGINE_EMBEDDED] —— **内嵌那份 VITS**([SherpaVoice])。多音字准
         *   (lexicon 里「调试」是一条写死的 tiao2 词组条目),但**整句合成**,
         *   一段话要等好几秒才出第一声 —— 那就是他说的「卡」。
         *
         * ★ 默认是**系统那副**:他点名要的就是它。
         * ★ 它读不到 / 没就绪时,[speakNow] 会**自动退回内嵌那副** ——
         *   绝不静默变成哑巴(这个项目的头号敌人)。
         */
        const val KEY_ENGINE = "engine"
        const val ENGINE_SYSTEM = "system"
        const val ENGINE_EMBEDDED = "embedded"
        const val DEF_ENGINE = ENGINE_SYSTEM

        /**
         * ★ 御姐的听感就是这两条:**低一点、慢一点**。
         * 1.0/1.0 是播报员;这套数是坐在你对面说话的人。
         *
         * ★★ 2026-10-07:**音高从 0.90 改成 1.00**([DEF_RATE] 不动)。
         *
         * 为什么改:用户说手机上「**像个男的**」,而同一套克隆流水线在电脑上出的
         * 那一份,他说「很好啊,**不像男的**」。两边唯一的差别就是这一个数 ——
         * [DEF_PITCH] 只在这一台手机上生效([SherpaVoice] 用它调 AudioTrack),
         * 电脑上那些渲染根本没有它。
         *
         * ★ 而且它是**关不掉的**:全工程 grep `putFloat(KEY_PITCH` 零命中 ——
         *   没人写过这个 pref,所以 `getFloat(KEY_PITCH, DEF_PITCH)` 永远拿默认值。
         *   没有界面、没有 adb 路径(装的是 release,`run-as` 被拒)。
         *
         * 它是**系统 TTS 时代**留下的:那时候她靠系统引擎说话,只能拿 pitch/rate 凑御姐
         * 的听感。而现在她有自己的嗓子([SherpaVoice]),音色由模型给 ——
         * 再叠一层降调只会把她压成男声。
         *
         * ⚠️ 改回 0.90 就回到原样。这一条是单独一处、能单独回退。
         * ★ 为什么不动 [DEF_RATE]:一次只动一处;而且 AudioTrack 那条路只吃 pitch,
         *   rate 只影响系统 TTS(那台机器上中文音色 0 种,本来就不会用它说话)。
         */
        const val DEF_PITCH = 1.00f
        const val DEF_RATE = 0.92f

        /**
         * 她身体的「假域名」。assets 里的东西没法直接 fetch(同源策略),
         * 所以让 WebView 以为自己在访问 https,由 serveAsset 在中间把请求
         * 换成从 APK 里读。这个域名是 AndroidX 官方约定的那个,不会被解析到真网络。
         */
        private const val HER_HOST = "appassets.androidplatform.net"
        private const val HER_ORIGIN = "https://$HER_HOST/"

        /**
         * 动作库里那份文件的取用前缀(2026-10-09)—— **只在这个文件里用**。
         *
         * ★ 为什么不是一条新的 sheme / 新的域名:库里的文件和 `motion/` 那个一样,
         *   都是**从 `serveAsset` 里读**的。它需要的只是「**一段能和别人分开的路径前缀**」,
         *   而 `her/` 底下本来就是我们自己的地方 —— 不需要新的机制。
         *
         * ★★ **前缀里的 `her/` 不是装饰。** `serveAsset` 开头就有一道
         *   `if (!path.startsWith("her/")) return null`;而且页面那边是用
         *   `new URL(rel, document.baseURI)` 解析出来的,`baseURI` 是
         *   `.../her/index.html` —— 所以「相对地址」天然就落在 `her/` 底下。
         *   换成别的前缀,**那条 URL 永远不会打到这里**。
         */
        private const val LIB_PREFIX = "her/motion-lib/"

        /**
         * 挑动作那一栏的宽度(见 [motionPicker])。
         *
         * ★ 这个数**故意做窄**:它压在房间最右缘,而右缘那一格**会吃掉 WebGL 的手势**
         *   (见 [scanBtn] 那段)。窄,就是这条代价唯一能压的地方。
         *   要加宽,先回去看一眼 [WardrobeMath.motionLabel] 那批中文名会不会被切掉。
         */
        private val MOTION_PICKER_W = 96

        /**
         * 那一栏里「默认」那一行的键(见 [buildMotionPicker])。
         *
         * ★ 为什么是**空串**:库里的相对路径永远不可能是空的
         *   (`WardrobeMath.safeLibraryPath` 第一句就把空串挡了),
         *   所以空串天然是一个「外面不会撞上」的保留值 —— 不用另外发明一个哨兵。
         *
         * ★★ 「默认」这一行**必须存在**:他挑过之后总要能退回去。
         *   没有它的话,一旦点过任何一条,`motion/` 那套就**再也回不来了** ——
         *   而那条路是他自己铺的、东西还在盘上,只是界面上找不到回去的门。
         */
        private const val MOTION_ROW_DEFAULT = ""

        /**
         * 她出场之后隔多久拍那张人形。
         * 一秒二是量出来的:她出场后要站稳 + 待机动作起步,这个时长足够,
         * 又不至于让用户回来时悬浮窗还是旧的圆球太久。
         */
        private const val FIGURE_DELAY_MS = 1200L

        // ── 那块字幕(它同时是麦克风、也是打断键,见 buildBottomBar)

        /** 打字机一个字(或一小撮)之间的间隔。**34ms ≈ 每秒 30 个字。**
         *
         * ★ 这个数是按「比说话快一点」选的:她的语速大约每秒 5~6 个字,
         *   打字机要**追得上她的嘴**,否则字幕会一直落在声音后面 ——
         *   那就变成了「看字幕要等」,比没有字幕还难受。
         *   快了没意义(看不清),慢了会拖。
         */
        private const val CAPTION_TYPE_MS = 34L

        /**
         * 「在听 / 在说 / 都停了」这三个状态。
         *
         * ⚠️ 名字和数字都别改着玩:这三个数散在**十几个**调用点上。
         *   (它们以前叫 `GLOW_*`,因为那时候真的会发光;光晕删了,名字跟着改,
         *    免得以后有人照着名字去找那层不存在的雾。)
         */
        private const val VOICE_OFF = 0
        /** 我在听 —— 麦克风开着。 */
        private const val VOICE_LISTEN = 1
        /** 她正在说 —— 麦克风这时候是**关的**(半双工)。 */
        private const val VOICE_SPEAK = 2

        /**
         * 静止时玻璃胶囊上那句话。**它就是这块胶囊的说明书** ——
         * 而且是**唯一**的一处:说完「按住可以说话」,顺手把「点一下可以打字」
         * 也说清楚,因为这两件事**共用同一块地方**,一次说完才不会有半个功能没人知道。
         *
         * ★★ 2026-10-04 晚从那句光秃秃的「按住说话」改过来,依据是用户当场的要求:
         *   > 「说话改成**按住对话框**,键盘输入改成**点对话框**」
         *   原来只有「按住」那一半写在这儿,打字那一半**哪儿也没写** ——
         *   而它以前是藏在屏幕左上角 ⚙ 里的一个开关。**印在手该落下去的地方,
         *   胜过一个要你去找的开关。**
         */
        private const val HINT_HOLD = "按住说话,点一下打字"
        /**
         * 他按下那颗「停」之后,胶囊上写这句。
         *
         * ★ 为什么**必须有回执**:这一下按下去之后,屏幕上发生的事是「**什么都没了**」——
         *   她的字不长了、嗓子不响了、嘴闭上了。而他刚才看到的是她在动。
         *   **一个把动的东西变成不动的东西**,和「她卡死了」长得一模一样 ——
         *   这正是本项目那句「会撒谎的手不是手,是陷阱」的另一面:
         *   一件做成了的事,得有回执。
         *
         * ★ 措辞照她已有的口气(短句、不敬语),理由同 [HINT_HOLD] ——
         *   **这是她的话,归他调**,改这一个字符串就行。
         */
        private const val HINT_STOPPED = "好,停下了"

        /**
         * 手指滑进了取消区:这句话**现在松手就不发出去**。
         *
         * ★★ 2026-10-05:**原来开头有个 `↥` 箭头,拿掉了。** 用户原话:
         *   「他的房间,和悬浮窗,**往上滑还是不要有箭头**。垃圾桶,等等,**就红色光晕就行**」。
         *   这件事的回执因此只剩两个,而且都不是图标:
         *   **这行字**(说「怎么做」)+ **这块玻璃红了**([setCancelGlow],说「松手会怎样」)。
         *   光比字快,也比字准 —— 手指还按着的时候,他看的是那块玻璃的颜色,不是这行小字。
         */
        private const val HINT_SWIPE_CANCEL = "松手就不发出去"

        /**
         * 一轮没听到人声之后,隔多久再听下一轮。
         *
         * ★ 不能是 0:那样「收工 → 重开」在日志里会连成一串,看不出是两个回合,
         *   出问题时会把「一分钟听了九轮」读成「一次听了一分钟」。
         */
        private const val EAR_RETRY_GAP_MS = 200L

        /**
         * 「往上滑多少算不要了」「松手这一下算什么」——★ **判据不在这儿**。
         *
         * 2026-10-05 搬去了 [BarGestureMath]:用户把房间里和悬浮窗两处**一起点了名**
         * (「他的房间,和悬浮窗,往上滑还是不要有箭头」),两处各写一份的话,
         * 这个判断就有两份实现,而它错起来的样子是「那句话还是送出去了」(不可逆)。
         * 那边有完整的取向说明(上滑取消**从严**,和挂断词「宁可漏不可错」正好相反)。
         */
        // const val SWIPE_CANCEL_DP 已删 —— 用它请直接写 [BarGestureMath.CANCEL_SWIPE_DP]。
    }
}

/**
 * 要声音时,按这个顺序跟 TTS 引擎要语言。
 *
 * 先中文;引擎说没有,就退而求其次要它自己的默认 —— ★ **有点口音也比没声音强**。
 * 为什么不能只写 `setLanguage(SIMPLIFIED_CHINESE)` 就完事:这台机器
 * `tts_default_synth` 是 null(用户从没在设置里选过引擎),靠 AOSP 回落到
 * 系统分区里排名最高的那个,而它认不认 zh-CN **不能靠猜,只能试**。
 */
/**
 * 挑嗓子时按这个顺序试,第一个认得的就是她的声音。
 *
 * ★ 2026-10-05 从 `private` 放开:[HerVoice] 也要用同一份。
 *   两份清单迟早会分叉,而分叉的症状是「房间里她说话是中文,悬浮窗那条上是英文」。
 */
internal val TTS_LANGS = listOf(Locale.SIMPLIFIED_CHINESE, Locale.CHINA, Locale.getDefault())
