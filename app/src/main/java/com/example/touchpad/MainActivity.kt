package com.example.touchpad

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var client: TouchpadClient
    private lateinit var screen: ScreenView

    // ★★ 2026-10-04 晚:抽屉那一套字段(btnScan / btnDisconnect / btnKeyboard / btnMirror /
    //   btnAi / btnConMarn / drawerContainer / drawerPanel / drawerHandle / drawerOpen /
    //   autoHideRunnable)**整组删掉**。它们的去处逐条写在 [onCreate] 里那一段。

    private lateinit var mediaStreamer: MediaStreamer
    private lateinit var mediaPreviewPanel: LinearLayout
    private lateinit var previewBox: FrameLayout
    private lateinit var previewImage: ImageView
    private lateinit var btnSwitchCamera: Button
    private lateinit var btnFlipCamera: Button
    /** ★ 麦克风/摄像头开关的**壳**(44dp 圆底,见 [media_btn_bg])—— 点击监听挂在它上面。 */
    private lateinit var micBtn: View
    private lateinit var camBtn: View
    /**
     * 「键盘」那颗图标的壳 —— 点它弹 [showTextInput]。
     *
     * ★★ 2026-10-06:它和输入条一起被删过一次,当天装回来。删的是**条子**(用户不要那条
     * 横条),不是**入口** —— 入口没了这一页就再没法往电脑打字。判据是「有没有调用方」。
     */
    private lateinit var kbBtn: View
    /**
     * 「退格」那颗 ← 的**壳** —— 按一下让电脑真按一次退格键,一次删一个字符。
     *
     * ★★ 2026-10-06 用户点名加的:「在输入法右边加一个←的按钮用来作为删除键(一个一个删)」。
     *   它和 [kbBtn] 是两件事:那颗负责**打字**,这颗只负责**删**。
     *   走的不是「发送一段文字」,是**按键** —— 见 [TouchpadClient.sendKey] 的说明
     *   (发退格**字符**电脑那边会当没看见,那种失败看起来和「键盘坏了」一模一样)。
     */
    private lateinit var kbBackBtn: View
    /**
     * 「回车」那颗的**壳** —— 按一下让电脑真按一次回车键(虚键码 13)。
     *
     * ★★ 2026-10-08 用户点名加的:「**在我控制电脑那个界面,加一个回车(换行键)
     *   不然我没法换行**」。位置紧挨着上面那颗 ←,同一排、同一个样式。
     *
     * ★ 和 [kbBackBtn] 是同一条路:发的是**按键**,不是一段文字 ——
     *   见 [TouchpadClient.sendKey](「发一个换行字符」电脑那边会当没看见)。
     *   ★★ 而这次**电脑端一个字都没改**:`server.py` 的 `VK_KEYS` 白名单里 13 一直在
     *   (那一天加 [VK_BACKSPACE] 的时候就在旁边写了「回车是 13,那张表上也有」)。
     *
     * ⚠️ 它和 [kbBackBtn]、[kbBtn] 一样是「这一排里的一颗」——**必须一起加进
     *   [isOnMediaButton]**。漏了它的症状是「看得见、按不动」,而且不报错。
     */
    private lateinit var kbEnterBtn: View
    private lateinit var micIcon: ImageView
    private lateinit var micDot: View
    private lateinit var micSlash: ImageView
    private lateinit var camIcon: ImageView
    private lateinit var camDot: View
    private lateinit var camSlash: ImageView
    private lateinit var btnMinimize: Button
    private lateinit var btnRestorePanel: ImageButton
    private var cameraOn = false
    private var micOn = false
    private var connected = false
    private var panelMinimized = false
    private var authDialog: AlertDialog? = null  // 当前认证输入框,超时/断开/取消时统一关掉

    // ---- 文件互传/中转站 ----
    private var connecting = false                        // 是否正在发起连接(避免重复触发)
    private var transferOverlay: TransferOverlay? = null  // 系统级悬浮球(文件中转站)
    private val pendingShare = ArrayList<Uri>()           // 系统「分享」来的文件,连上电脑后自动发

    // ---- AI 助手 ----
    private var aiAgent: AiAgent? = null                  // 编排器(懒建,配置从 SharedPreferences 读)
    private var models: ModelManager? = null              // 内嵌本地模型(Termux 那套的根治版)
    private var aiDialog: AlertDialog? = null             // 当前 AI 对话框(停止/断开时统一关)
    private var aiLog: android.widget.TextView? = null    // 对话框里的过程日志

    private val prefs by lazy { getSharedPreferences("touchpad", MODE_PRIVATE) }

    // ★★ 2026-10-06:**「直接打到电脑上」那条输入条整块删了** —— 字段、视图、逻辑一起走。
    //   用户原话和两处连带后果都写在 activity_main.xml 那片注释里,这里不重复。
    //   ★ 底下的能力层是**故意留着**的(见文件末那段说明),不是漏删。

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BehaviorLogger.init(this)
        ExperienceStore.init(this)
        MoodStore.init(this)
        UserLexicon.init(this)
        HandRegistry.init(this)
        // 手机自己那只手要一个 Context 才能开应用、弹闹钟界面(见 SelfHand)。
        SelfHand.init(this)
        ModelManager.get(this).trace("onCreate 开始")
        setContentView(R.layout.activity_main)
        hideSystemBars()
        // 保活:屏幕常亮,不因长时间不用而熄屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 标记「本进程被用户打开过」:进程被系统整杀后 KeepAliveService 若裸复活
        // (全新进程里这个静态变量是假的),会让它立即自停,不再出现「闪退后又自己冒出来」。
        // ★★ 2026-10-05 语义更正:它**不是**「有没有宿主 Activity」—— 那个说法会让
        //    onDestroy 顺手把它置假,而她的房间才是桌面唯一入口,那条路根本不建这个
        //    Activity。详见 [KeepAliveService.hostActivityLive] 上那一段。
        KeepAliveService.hostActivityLive = true
        // 屏幕旋转/尺寸变化时(即使本 App 退到后台、书签条还悬在桌面或其他 App 上),
        // 全进程都会收到 onConfigurationChanged:借此把书签条/面板重新贴回新屏右缘。
        // 单用 DisplayManager 监听在部分 ColorOS 上退到后台后不回调,这里用组件回调兜底。
        applicationContext.registerComponentCallbacks(object : android.content.ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
                try { StationHolder.onScreenChanged() } catch (_: Exception) {}   // 书签条(进程级)
                try { transferOverlay?.onScreenChanged() } catch (_: Exception) {} // 抽屉(这条 Activity)
                // 旋转瞬间 getRealMetrics 可能还在过渡值,稍后再对一次,确保贴准最终尺寸
                window.decorView.postDelayed(
                    {
                        try { StationHolder.onScreenChanged() } catch (_: Exception) {}
                        try { transferOverlay?.onScreenChanged() } catch (_: Exception) {}
                    },
                    260
                )
            }

            override fun onLowMemory() {}
        })

        // 内嵌本地模型:起了它,「若息前台 = 模型前台」,ColorOS 再也冻不掉它
        // (Termux 那套的老毛病)。模型没导入/二进制不在时它会静静待着,不打扰用户。
        models = ModelManager.get(this).also { mm ->
            mm.onState = { st, msg ->
                Log.i("RuoxiLlama", "状态 $st: $msg")
                when (st) {
                    // ★ 2026-10-04:「起了脑之后干什么」整套搬去了 [HerBoot]。
                    //   原因是这一行原来**只长在 MainActivity 里** —— 用户点她自己的
                    //   桌面图标进房间(→ ConMarnActivity)那条路从不预热,第一句话
                    //   要现场冷算。她的启动不该挂在触控板界面上。
                    ModelManager.State.READY -> runOnUiThread { HerBoot.ensure(this) }
                    // 起不来要弹的**只有**这里:HerBoot 是后台路径,出错只写 model.log 不打扰人。
                    ModelManager.State.FAILED -> runOnUiThread { toast("本地模型:$msg") }
                    else -> {}
                }
            }
            // ★ 2026-10-04:原来这里是 `mm.start()` 直调 —— 改成走唯一入口 [HerBoot.ensure]。
            //   两个理由,第二个才是真的会咬人的:
            //   ① 它做的事是 `mm.start()` 的超集(建 agent / 起脑 / 盯装载 / 预热);
            //   ② ★ `onState` 是**边沿触发**的:脑要是早就 READY 了(开机广播先把她拉起来、
            //      或者别的界面刚起过),`mm.start()` 立刻返回、状态**根本不跳变**,
            //      上面那个 READY 分支永远不来 → 「一直在」的锚就没人打。
            //      边沿触发的东西不能拿来当「保证发生过」用。
            HerBoot.ensure(this)
            mm.trace("start() 返回 state=${mm.state} msg=${mm.lastMessage} 缺件=${mm.missingPieces()} " +
                "模型=${mm.modelFile().isFile}@${mm.modelFile().absolutePath}")
        }

        screen = findViewById(R.id.screen)
        mediaPreviewPanel = findViewById(R.id.media_preview_panel)
        previewBox = findViewById(R.id.preview_box)
        previewImage = findViewById(R.id.preview_image)
        btnSwitchCamera = findViewById(R.id.btn_switch_camera)
        btnFlipCamera = findViewById(R.id.btn_flip_camera)
        micBtn = findViewById(R.id.mic_btn)
        camBtn = findViewById(R.id.cam_btn)
        kbBtn = findViewById(R.id.kb_btn)
        kbBackBtn = findViewById(R.id.kb_back_btn)
        kbEnterBtn = findViewById(R.id.kb_enter_btn)
        micIcon = findViewById(R.id.mic_icon)
        micDot = findViewById(R.id.mic_dot)
        micSlash = findViewById(R.id.mic_slash)
        camIcon = findViewById(R.id.cam_icon)
        camDot = findViewById(R.id.cam_dot)
        camSlash = findViewById(R.id.cam_slash)
        btnMinimize = findViewById(R.id.btn_minimize)
        btnRestorePanel = findViewById(R.id.btn_restore_panel)
        mediaStreamer = MediaStreamer(this)
        mediaStreamer.onLog = { msg -> runOnUiThread { toast(msg) } }
        mediaStreamer.onPreview = { bmp -> runOnUiThread { previewImage.setImageBitmap(bmp) } }
        // 语音唤醒要避开推流的麦克风(抢麦两边都听不清):接个状态钩子过去
        WakeWordManager.micHook = { mediaStreamer.isMicOn }

        client = TouchpadClient.get(this)
        client.listener = object : TouchpadClient.Listener {
            override fun onStatus(status: String) {
                toast(status)
            }

            override fun onPinRequired() {
                toast("请在电脑上查看配对码")
                showInputDialog(
                    "输入配对码",
                    "电脑端已弹出 6 位配对码,请输入:",
                    "6 位数字",
                    InputType.TYPE_CLASS_NUMBER,
                    { v -> if (v.length == 6 && v.all { it.isDigit() }) v else null },
                    onInput = { client.submitPin(it) },
                    onCancel = { cancelAuth() }
                )
            }

            override fun onSecretRequired() {
                toast("配对:请扫描电脑上的二维码")
                showSecretChooser()
            }

            override fun onRepairRequired() {
                // 没有恢复码这种备用钥匙了:本机没种子就重新登记一次,
                // 和 authenticator 一样。电脑那边要过自己的登录 PIN 才画得出二维码。
                showRepairDialog()
            }

            override fun onConnected() {
                toast("已连接")
                connected = true
                connecting = false
                applyMirror()          // 按镜像开关(开=高清)决定是否开屏幕镜像
                startKeepAlive()
                maybeAskIgnoreBatteryOptimizations()   // 首次连上时引导允许忽略电池优化(治 ColorOS 强停)
                updateMediaPanel()
                restoreWantedMedia()   // 自动恢复断前开着的摄像头/麦克风
                flushPendingShare()    // 断前/冷启动时排队的「分享」文件,连上后自动发
                // ★ 2026-10-06:这里原来有一段「连上**不**自动露出输入条」的说明 ——
                //   输入条本身已经整块删了,那一段跟着没了(见 activity_main.xml)。
                // ★ 2026-10-05:`client.sendHand()` 从这儿**搬走**了 ——
                //   现在是 [TouchpadClient] 在每次连上/续连成功时自己问。
                //   搬的理由见 `TouchpadClient.registerHandFromReply`:那句话是
                //   **连接的不变量**,写在 Activity 里就变成「谁活着谁负责」,
                //   而她的房间(桌面唯一入口)从不建这个 Activity。
            }

            override fun onDisconnected() {
                dismissAuthDialog()
                toast("已断开")
                connected = false
                connecting = false
                stopKeepAlive()
                stopMedia()
                screen.clear()
                // ★ 2026-10-06:这里原来是 `hideTypeBar()` —— 断连时把输入条收掉,
                //   否则留着一块「你打的字去哪了」的黑洞。输入条删了,这一行跟着没了。
            }

            override fun onResumed() {
                // 掉线后自动免码续连成功:UI 不复位、不弹「已断开」。
                // 重新拉起保活服务(ColorOS 刚把它停掉就是断连主因),并按镜像档位重开屏幕镜像;
                // 摄像头/麦克风流会由 MediaStreamer 检测断连后自行续连。
                connected = true
                startKeepAlive()
                applyMirror()
                updateMediaPanel()
                restoreWantedMedia()   // 保险:若续连时流确实停了,把断前开着的再拉起来
                // ★ 2026-10-06:原来这里还有一句「续连也不自动露出输入条」—— 输入条删了,跟着没了。
                // ★ `client.sendHand()` 搬走了(同上)。
            }

            // 引导一次:允许忽略电池优化,避开 ColorOS 定时强停前台服务导致的断连/卡顿
            private fun maybeAskIgnoreBatteryOptimizations() {
                if (Build.VERSION.SDK_INT < 23) return
                val pm = getSystemService(PowerManager::class.java) ?: return
                if (pm.isIgnoringBatteryOptimizations(packageName)) return
                if (prefs.getBoolean("battery_opt_asked", false)) return   // 只弹一次,避免烦人
                prefs.edit().putBoolean("battery_opt_asked", true).apply()
                toast("为保证长时间连接稳定,请允许忽略电池优化")
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                    // 个别系统不支持该 intent,忽略(用户可走 ColorOS 手动白名单)
                }
            }

            override fun onNewMediaToken(token: String) {
                // 每次认证/续连后电脑都会签发新 token;同步给媒体流,重连媒体 socket 时用新的
                mediaStreamer.refreshToken(token)
                restoreWantedMedia()   // token 就绪了:若 onConnected 时还没下来,这里补恢复断前状态
            }

            override fun onError(message: String) {
                dismissAuthDialog()   // 超时/认证失败:先关掉弹窗,别让它盖住界面
                toast(message)
                connected = false
                connecting = false
                stopKeepAlive()
                updateMediaPanel()
                screen.clear()
            }

            override fun onFrame(jpeg: ByteArray, screenW: Int, screenH: Int) {
                screen.setFrame(jpeg, screenW, screenH)
            }

            override fun onCursor(x: Int, y: Int) {
                // 电脑回传的真实光标:让镜像箭头永远跟着它走,实体鼠标怎么挪都不会「对不上」
                screen.setRemoteCursor(x, y)
            }

            // ---- 文件互传状态/结果 ----
            override fun onFileStatus(msg: String) {
                transferOverlay?.setStatus(msg)
                toast(msg)
            }

            override fun onLsResult(json: JSONObject) {
                // 电脑中转目录清单:转发给悬浮球(只有它在「电脑文件」页时才会渲染)
                transferOverlay?.onPcListing(json)
            }

            override fun onPcThumb(rel: String, jpeg: ByteArray) {
                transferOverlay?.onPcThumb(rel, jpeg)
            }

            override fun onDelResult(ok: Boolean, msg: String) {
                transferOverlay?.onDelResult(ok, msg)
            }

            override fun onPullRequest(name: String) {
                // 电脑要手机把中转站里的该文件传回:TouchpadClient 已自动执行,这里只提示
                transferOverlay?.setStatus("电脑正在取回「$name」…")
            }

            override fun onFileProgress(name: String, done: Long, total: Long) {
                // 下载进度(每 ~1MB):驱动悬浮窗进度条
                transferOverlay?.setProgress("接收", name, done, total)
            }

            override fun onTransferEnded() {
                // 一次传输结束:悬浮窗收起进度条,回到列表态
                transferOverlay?.setTransferEnded()
            }

            override fun onAiReply(json: JSONObject) {
                // 电脑端执行完一个 AI 工具:按 id 配对唤醒等待队列。
                // ★ 走 holder 而不是本 Activity 的 aiAgent 字段:用户可能正开着
                // OI助手 / ConMarn,跑任务的实例是 holder 里那个 —— 别把回执交错人。
                AiAgentHolder.peek()?.onReply(json)
            }

            override fun onHandReply(json: JSONObject) {
                // 电脑自述「我是谁、我会什么」。两件事:**记下来**、顺手**对一次账**。
                if (!json.optBoolean("ok", false)) {
                    // 电脑端没装 AI 模块(ai_tools.py 缺失)之类。不是错误路径,
                    // 只是这只手什么也不会 —— 说清楚原因,别假装它有。
                    return
                }
                val hand = try {
                    json.optJSONObject("hand")
                        ?.let { HandCodec.fromHandInfo(it, System.currentTimeMillis()) }
                } catch (_: Exception) { null } ?: return
                HandRegistry.upsert(hand)
                // ★ peek() 而不是 get():没人用助手的时候,不该为了对一次账
                // 把 AiAgent(以及它背后的模型)建出来。没建就等它自己用的时候对
                // (warmUpOnce 里也调了同一个,幂等)。
                AiAgentHolder.peek()?.checkToolDrift()
            }

            // ★★ 2026-10-06:**这里原来是一个 `onHitReply`** —— 电脑答「这一点能打字」
            //   就把输入条升起来(= 用户要的「点电脑对话框,手机自己弹输入法」)。
            //   输入条整块删了,这个重写跟着删了。
            //   ★ 通信层没动:`TouchpadClient` 仍然会派发 `HITR`、仍然有 `sendHit`,
            //     只是手机侧不再有人问(理由见文件末「能力层为什么留着」那段)。

            /**
             * 按键回执。**只有失败才说话。**
             *
             * ★ `sent=false` 是「电脑不认这个键」,不是「电脑没收到」——
             *   而手机这边按了退格却什么都没发生的样子,和「键盘坏了」一模一样。
             *   所以它必须说话(见 `VK_KEYS` 那张白名单:白名单**不是通道**,
             *   以后哪天手机端多发了一个键,这一句就是唯一的线索)。
             */
            override fun onKeyReply(json: JSONObject) {
                if (json.optBoolean("sent", true)) return
                toast("电脑不认这个键:" + (json.optString("error").ifBlank { "未知" }))
            }

            override fun onMediaCommand(cmd: String) {
                when (cmd) {
                    "CAM 0" -> if (cameraOn) toggleCamera()
                    "CAM 1" -> if (!cameraOn) toggleCamera()
                    "MIC 0" -> if (micOn) toggleMic()
                    "MIC 1" -> if (!micOn) toggleMic()
                }
            }
        }

        screen.onAbs = { x, y, buttons ->
            client.sendAbs(x, y, buttons)
        }
        // ★★ 2026-10-06:**这里原来接了两件事,都随输入条一起删了** ——
        //   · `screen.onTap = { x, y -> … probeTyping(x, y) }`:点中电脑的输入框 →
        //     问电脑「这一点能打字吗」→ 答了就把那条子升起来 + 弹输入法。
        //   · `setupTypeBar()`:那条子的全部接线(边打边发 / 回车 / IME 顶位)。
        //   ★ `ScreenView.onTap` 那个回调**留着没删**(它是「刚刚点了哪儿」的上报口,
        //     本身不绑输入条)—— 现在没人接,所以每次点击只是白跑一次空判断,无害。
        // 双指上下滑 -> 滚轮:正数向上滚(页面往下看),负数向下滚
        screen.onWheel = { w ->
            if (w != 0) client.sendMouse(0, 0, w, 0)
        }

        // ★★ 2026-10-04 晚:**左边那一整排抽屉按钮全删了**(用户原话见文件头那段)。
        //   这里原本挂着六件事:键盘输入框、镜像档位、AI 助手、ConMarn、扫描、断开连接。
        //   现在的去处:
        //     · 键盘输入 → ~~改成**点屏幕自己弹输入法**~~ ★ 2026-10-06 连同输入条一起删了
        //       (用户:「我的输入法可以直接打到直接发送」)
        //     · 扫描 / 断开连接 → 搬进**中转站**([StationOverlay] + [pendingScan] / [pendingDisconnect])
        //     · AI 助手 / ConMarn → 删。这两个入口今天分别由「中转站 → AI 助手 → 电脑」
        //       和「回到房间」承担,抽屉里那两颗按钮本来就是重复的第三条路。
        //     · 镜像档位 → 删。它是个**调试开关**(高清/关),平时没人会去动它;
        //       真要关镜像有的是办法(见 [applyMirror]),为它留一格抽屉不值。
        // ★★ 2026-10-05:监听从**图标**挪到了**壳**上。
        //   原来可点的只有那颗 30dp 的 ImageView;现在壳是 44dp、还带着一圈看得见的底
        //   ([media_btn_bg]),手指该落的地方和看得见的地方终于是同一块。
        //   用户问「麦克风和摄像头的开启件在哪」——一半是图标隐形,另一半是它太小。
        micBtn.setOnClickListener { toggleMic() }
        camBtn.setOnClickListener { toggleCamera() }
        // ★★ 2026-10-06:这颗「键盘」图标和输入条一起删过一次,当天又装回来了 ——
        //   删的是**条子**(用户不要那条横条),但入口被连带删掉之后,这一页
        //   **再没有任何办法自己往电脑打字**(用户原话:「我控制电脑页面的输入法按钮呢」)。
        //   现在它只叫 [showTextInput]:一个输入框 + 一个「发送」,不再长回横条。
        kbBtn.setOnClickListener { showTextInput() }
        // ★★ 2026-10-06:紧挨着键盘图标右边那颗 ← —— 按一下,电脑那边**真的按一次退格键**。
        //   一次一个字符:这里不接长按、不做连发,按几下删几个(用户原话「一个一个删」)。
        //   ★ 没有连接时它也跟着面板一起藏着(它就是那排里的一颗),
        //     不会出现「按了却没反应」那种看不出所以然的样子。
        kbBackBtn.setOnClickListener { client.sendKey(VK_BACKSPACE) }
        // ★★ 2026-10-08:紧挨着 ← 右边那颗「回车」—— 按一下,电脑那边真的按一次回车键。
        //   一次一个(= 一个换行 / 一次提交),这里同样不接长按、不做连发。
        //   ⚠️ 「换行」还是「提交」**不是这颗按钮说了算,是电脑上那个窗口说了算**:
        //     记事本 / 编辑器里它是换行;聊天窗口(微信这种)里它是**把话发出去**。
        //     这颗只负责「按一下回车」,它不知道对面是哪种 —— 回执里也不许编。
        kbEnterBtn.setOnClickListener { client.sendKey(VK_RETURN) }
        btnSwitchCamera.setOnClickListener { mediaStreamer.switchCamera() }
        btnFlipCamera.setOnClickListener {
            // 左右翻转开关:开 = 照镜子(前置拍书把字翻正),再点还原。画面发到电脑/OBS 也一起翻。
            val on = mediaStreamer.toggleFlip()
            btnFlipCamera.text = getString(if (on) R.string.flip_camera_on else R.string.flip_camera)
            toast(if (on) "画面已左右翻转(再点还原)" else "画面已还原")
        }
        btnMinimize.setOnClickListener { setPanelMinimized(true) }
        btnRestorePanel.setOnClickListener { setPanelMinimized(false) }
        setupPanelGestures()
        updateMediaPanel()   // 初始状态:未连接 → 媒体面板隐藏

        // 跨重启免密:只在进程全新启动时自动连上次的电脑(旋转/恢复旧 Activity 不重连)。
        // 有存下的免密 token 就秒连;过期/没有才自动进配对,此时只需输一次 6 位码。
        if (savedInstanceState == null) {
            // Android 16(API 36)起访问局域网需先拿到「本地网络」权限,没有它连内网必超时;
            // 先申请,拿到(或已授权)后再自动连上次的电脑。
            // ★ `post` 的理由没变:等这一帧的布局量完再连,免得 `onConnected` 里
            //   那几处按屏幕尺寸算坐标的代码拿到 0。挂靠的 View 从抽屉容器换成了屏幕。
            ensureLocalNetworkAccess { screen.post { autoConnectLast() } }
        }

        // 若是被系统「分享」拉起来(SEND/SEND_MULTIPLE),把文件加进上传队列
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)   // 之后 getIntent() 也指向这份(部分系统用这个取 EXTRA_STREAM)
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 回前台时屏幕方向/尺寸可能已变,先把中转站面板贴回当前屏(顺带把它建出来 ——
        // 2026-10-04 起它不再等「连上电脑」才建,见 syncTransferOverlay)
        try { syncTransferOverlay() } catch (_: Exception) {}
        // 语音唤醒:上次开着的话把监听续上(进程活着它就该听着)
        try { WakeWordManager.applySetting(this) } catch (_: Exception) {}
        // ★ 她的球 —— 2026-10-04 晚**退回**默认关(见 ConMarnBubble.isEnabled 那段)。
        // 屏幕上陪着的是右缘那条磨砂书签条(TransferOverlay.show 立起来的),
        // 它本身就是中转站;她的球成了**可选**的那个,想叫她出来走中转站里那张卡片。
        try { ConMarnBubble.applySetting(this) } catch (_: Exception) {}
        // 她球面板里的「中转站」按钮落到这儿。
        // ★ 2026-10-04 晚:**不再需要「只在 App 前台时挂」这条约束了** ——
        //   中转站面板搬到了 [StationOverlay](进程级,只拿 applicationContext)。
        //   原来那句「中转站要先打开 app」的抱怨,根因就是这里挂着 Activity。
        //   现在这个 lambda 捕获的是 applicationContext 路的单例,**挂着也不泄漏**;
        //   而且它已经不需要 MainActivity 活着了。仍然在 onPause 里摘掉,是为了
        //   「App 不在前台时别接她的球的活」这条语义,不是因为泄漏。
        ConMarnBubble.onOpenStation = {
            try { StationHolder.get(applicationContext).openHub() } catch (_: Exception) {}
        }
        // ★ 「手机自己」那只手也要知道前台有没有界面:后台拉 Activity 会被系统
        // 静默拦掉(不报错,就是没动静),回执得靠这个标志说清「可能弹不出来」。
        // 主界面也算 —— 见 ConMarnActivity.onResume 那处,两处缺一个都会误报。
        SelfHand.visible = true
        // 她主动开口:主界面开着也算「你看得见她」(她的话走 Toast + 小窗面板)。
        // 计时的表在这里续上 —— 进程被杀重开时 ticking 是 false,不续就永远不响。
        try {
            ProactiveGreeting.enter("MainActivity")
            ProactiveGreeting.applySetting(this)
        } catch (_: Exception) {}
        // ★ 这几个便条是**静态**的,因为下单的是 [StationOverlay](见 companion 那段注释)。
        //
        // 中转站「文件传输」:回前台后**直接打开文件面板**。
        // ★ 它原来写的是 `setDrawerOpen(true)`(开左边那个抽屉),而抽屉今天删了。
        //   但这条路的**本意**从来不是「开抽屉」,是「把文件面板打开」——
        //   抽屉当时只是通往它的门口。所以现在直接叫 [openTransferCenter],
        //   少一道门,意思一个字没变。
        if (pendingOpenTransfer) {
            pendingOpenTransfer = false
            screen.post { openTransferCenter() }
        }
        // 中转站「AI 助手 → 电脑」:回前台后自动弹出命令输入框,用后即清。
        if (pendingOpenAi) {
            pendingOpenAi = false
            screen.post { showAiDialog() }
        }
        // 中转站「扫描」:回前台后立刻扫描局域网(见 [discoverComputers])。
        if (pendingScan) {
            pendingScan = false
            screen.post { discoverComputers() }
        }
        // 中转站「断开连接」:回前台后断开并复位(见 [doDisconnect])。
        if (pendingDisconnect) {
            pendingDisconnect = false
            screen.post { doDisconnect() }
        }
    }

    /** 隐藏系统状态栏/导航栏(沉浸式),避免挡屏幕镜像视线;从边缘下滑可临时呼出。 */
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** 关掉当前认证弹窗(若有);被超时/错误/断开触发,避免弹窗盖住界面点不到扫描。 */
    private fun dismissAuthDialog() {
        authDialog?.let { d ->
            try {
                d.dismiss()
            } catch (_: Exception) {
            }
        }
        authDialog = null
    }

    /** 连接/认证彻底结束后把 UI 复位到「空闲」:扫描可点、媒体面板隐藏、画面清掉。 */
    private fun resetToIdle() {
        connected = false
        connecting = false
        stopKeepAlive()
        stopMedia()
        saveWants()   // 用户取消/中止连接:记下「不想要摄像头/麦克风」,避免下次连接自动拉起
        screen.clear()
    }

    /** 用户主动取消认证输入(点弹窗「取消」)。必须完整复位,否则扫描按钮会一直灰着。 */
    private fun cancelAuth() {
        dismissAuthDialog()
        client.disconnect()
        resetToIdle()
        toast("已取消连接,可重新扫描")
    }

    private fun connectTo(ip: String, port: Int) {
        if (connecting) return
        connecting = true
        prefs.edit().putString("ip", if (port == 9527) ip else "$ip:$port").apply()
        toast("正在连接 $ip:$port …")
        client.connect(ip, port)
    }

    /**
     * 冷启动自动连「上次那台电脑」:connect() 里会先试盘上存下的免密 token,
     * 服务器几分钟内仍认(滑动过期)就秒连、一个码都不用输;token 没了/过期则自动进入
     * 完整配对流程,这时只需输一次电脑端弹的 6 位配对码。实现「跨重启免密」。
     *
     * ★★ 2026-10-05:**实现整个搬去了 [PcLink]**,这里只剩一次转发。
     *   搬的理由是它的**调用点太窄** —— 它原来只在这儿、只在 `savedInstanceState == null`
     *   时跑,而身份合并之后**她的房间才是桌面唯一入口**,那条路上一次都不会发起连接。
     *   用户看到的就是「明明是连着电脑,agent 说没有」。
     *   ★ 转发而不是各写一份:两条路各留一份的话,「两个入口互相把对方的连接掐掉」
     *     是迟早的事([TouchpadClient.connect] 第一句就是 `disconnect()`)。
     */
    private fun autoConnectLast() {
        if (connected) return
        if (PcLink.connectLast(this)) toast("正在连接上次那台电脑 …")
    }

    /** 「断开连接」:手动断开当前连接并复位 UI(不再自动重连,想连就再扫一次)。 */
    private fun doDisconnect() {
        if (!connected) {
            toast("当前未连接")
            return
        }
        client.disconnect()      // 代际自增,当前连接线程立即失效,onDisconnected 不再触发
        connected = false
        stopMedia()              // 关手机摄像头/麦克风流,复位媒体面板
        saveWants()              // 用户手动断开:记下「不想要」,下次连接别自动拉起
        stopKeepAlive()
        screen.clear()           // 清掉残留的电脑画面
        toast("已断开连接")
    }

    // ======================================================================
    // 「直接打到电脑上」那条输入条
    // ======================================================================

    // ★★ 2026-10-06:**这一整段(约 180 行)整块删了。** 一起走的九个成员:
    //
    //   setupTypeBar()   那条子的全部接线 —— 边打边发([TypeSync].diff → 真退格 / sendText)、
    //                    回车当提交(VK_ENTER)、点条子显式叫输入法、IME insets 顶位
    //                    (沉浸式 Activity 不显式处理就会被输入法盖住)
    //   revealTypeBar()  让它露面,顺带把右下角媒体面板顶上去一格
    //   showTypeBar()    露面 + 叫输入法
    //   showKeyboard()   显式 showSoftInput(SHOW_IMPLICIT)—— 沉浸式下系统不会自己弹
    //   hideTypeBar()    收起,并复位那两处 translationY
    //   clearTypeInput() 静默清空(不因此回灌电脑一串退格)
    //   probeTyping()    问电脑「这一点能不能打字」
    //   VK_BACKSPACE / VK_ENTER   那两个键码
    //
    // ★ 为什么删:用户要「我的输入法可以直接打到直接发送」—— 他的输入法本来就能
    //   直接往电脑打字,手机在中间转一手是多余的。详见 activity_main.xml 那片注释。
    //
    // ⚠️ **故意留着没删的**(不是漏):
    //   · [TypeSync] 和它的单测 —— 纯逻辑、零 Android 依赖,真要用回来成本高;
    //   · `TouchpadClient.sendHit` / `Listener.onHitReply` —— 通信层封装,
    //     PC 侧 `HIT`/`HITR` 那条命令还在,删它要动两头 + 重启服务;
    //   · `ScreenView.onTap` —— 「刚点了哪儿」的上报口,不绑输入条。
    //     现在没人接 = 每次点击白跑一次空判断,无害。
    //   要连这些一起清干净,说一声 —— 那是一次独立的、跨两端的删除。

    /** 等「本地网络」权限拿到后要执行的动作(onCreate 里自动连用);权限结果回来时触发。 */
    private var pendingLocalReady: (() -> Unit)? = null

    /**
     * Android 16(API 36)起,App 访问局域网(192.168.x.x 这类内网地址)必须持有
     * ACCESS_LOCAL_NETWORK 权限。没有它,外网照样通、连内网直接超时——这是系统行为,不是网络故障。
     * 已授权或系统版本低时立刻执行 onReady;否则弹系统授权框,授权结果在 onRequestPermissionsResult 里回调。
     */
    private fun ensureLocalNetworkAccess(onReady: () -> Unit) {
        if (Build.VERSION.SDK_INT < 36) { onReady(); return }
        if (checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) {
            onReady()
        } else {
            pendingLocalReady = onReady
            requestPermissions(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK), REQ_LOCAL_NET)
        }
    }

    /** 扫描局域网里的电脑端(靠电脑 UDP 广播,无需手输 IP)。 */
    private fun discoverComputers() {
        // ★ 防重复:**以前靠「把扫描按钮置灰」**,而那颗按钮跟着抽屉一起删了。
        //   守门不能跟着按钮一起消失 —— 重复扫描会并发拨号、弹出两个配对码
        //   (`connecting` 那条老注释记的就是这个)。所以判断挪到了函数里:
        //   **正在连接 / 正在连的路上就别再扫一遍。**
        if (connecting) {
            // ★ 静默返回 = 屏幕上一个字都没有,和「扫不到」长得一模一样。
            //   留一行,别让「按了没反应」变成查不出来的事。
            ModelManager.get(this).trace("扫描:连接进行中(connecting=true),这次扫描被挡掉了")
            return
        }
        // 没拿到「本地网络」权限的话,扫描/连接都会静默超时:先补权限,拿到后自动重进本函数。
        if (Build.VERSION.SDK_INT >= 36 &&
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            ModelManager.get(this).trace("扫描:没有「本地网络」权限,先去要权限")
            toast("请允许「本地网络」权限,否则连不上电脑")
            ensureLocalNetworkAccess { discoverComputers() }
            return
        }
        // 三元组:ip、port、电脑的名字(可能没有 —— 老版本电脑端不带身份字段)。
        val found = ArrayList<Triple<String, Int, String?>>()
        ModelManager.get(this).trace("扫描:开始(3 秒)")
        toast("正在扫描局域网…")
        client.discover(
            timeoutMs = 3000L,
            onFound = { ip, port, hand ->
                // 先登记:发现应答里**只有身份没有工具表**(见 HandCodec.fromDiscovery)。
                // 记下来是为了让列表里有名字、也让「上次连的那只手」活着;
                // 完整的工具表要等连上之后 HAND 命令才问得到。
                if (hand != null) HandRegistry.upsert(hand)
                if (found.none { it.first == ip && it.second == port }) {
                    found.add(Triple(ip, port, hand?.name))
                }
            },
            onDone = {
                // ★ 走的是哪一支必须记下来:屏幕上看,
                //   「扫不到」= 有那句 toast、「扫到了但连不上」= 一个提示都没有。两者差得远。
                ModelManager.get(this).trace(
                    "扫描:结束,找到 ${found.size} 台" +
                            when {
                                found.isEmpty() -> " → 报「没找到电脑」"
                                found.size == 1 -> " → 直接连 ${found[0].first}:${found[0].second}"
                                else -> " → 弹列表让他选"
                            })
                when {
                    found.isEmpty() -> {
                        toast("没找到电脑,请确认电脑端已启动、且手机电脑同一 Wi-Fi")
                            }
                    found.size == 1 -> connectTo(found[0].first, found[0].second)
                    else -> {
                        showFoundList(found)
                            }
                }
            }
        )
    }

    private fun showFoundList(found: List<Triple<String, Int, String?>>) {
        // 有名字就显示名字 —— 「LAPTOP-M5QBJO9M:9527」和「这台笔记本:9527」,
        // 后者才让人选得动。没名字(旧电脑端)就退回 ip:port,照常能用。
        val labels = found.map { (ip, port, name) ->
            if (name.isNullOrBlank()) "$ip:$port" else "$name($ip:$port)"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("发现 ${found.size} 台电脑,选一个连接")
            .setItems(labels) { _, which ->
                connectTo(found[which].first, found[which].second)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 弹一个输入框,把文本发到电脑当前焦点处;软键盘右下角提供单独的「发送」键。
     *
     * ★ 2026-11-07:外壳换成**透明磨砂玻璃**(他点名的)。四件必须一起做、少一件就读不清的
     *   理由全写在 [showInputDialog] 上面那段注释里(背景/标题/正文与按钮/输入框),
     *   这里照着做,不另起一套。
     */
    private fun showTextInput() {
        val input = EditText(this)
        input.hint = "输入要发送到电脑的文本"
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        // 玻璃上再放一个**深色**小圆角框,里面才是浅色的字(同 [showInputDialog])
        input.setBackgroundResource(R.drawable.her_input_bg)
        input.setTextColor(getColor(R.color.text_primary))
        input.setHintTextColor(0x8AF2F3F5.toInt())
        input.setPadding(dp(16), dp(12), dp(16), dp(12))

        val dialog = AlertDialog.Builder(this, R.style.Theme_Touchpad_GlassDialog)
            .setTitle("键盘输入")
            .setView(input)
            .setPositiveButton("发送", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()

        // 玻璃上的字要逐个给死:深色主题的默认弹窗字是白的,压在白玻璃上等于没字。
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(getColor(R.color.glass_text))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(0x8A263238.toInt())
        // 换成玻璃背景之后 9-patch 那圈内衬没了,自己补回来,否则内容贴着圆角。
        (dialog.window?.decorView?.findViewById<View>(android.R.id.content)
            ?: dialog.window?.decorView)?.setPadding(dp(14), dp(12), dp(14), dp(8))

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val text = input.text.toString()
                if (text.isNotEmpty()) {
                    dialog.dismiss()
                    client.sendText(text)
                }
                true
            } else {
                false
            }
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = input.text.toString()
            if (text.isNotEmpty()) {
                dialog.dismiss()
                client.sendText(text)
            }
        }
    }

    // ------------------------------------------------------------------
    // AI 助手:说一句话,让(手机本地或云端的)小模型来操作电脑
    // ------------------------------------------------------------------

    /**
     * 懒建编排器。实现搬进了 [AiAgentHolder] —— OI助手 / ConMarn 是独立 Activity,
     * 它们和这里**必须共用同一个实例**(AI 回执只有一条通道、一个路由,见 holder 的说明)。
     * 这里只是主界面的取用点,顺带把引用留在 [aiAgent] 字段上(onDestroy 还要停它)。
     */
    private fun ai(): AiAgent = aiAgent ?: AiAgentHolder.get(this).also { aiAgent = it }

    // ★ 2026-10-04:「本地模型一就绪就偷偷问它一句废话」那个 warmUpLocalModel()
    //   整段搬去了 [HerBoot.warmUp]。它本来就和界面无关(后台线程 + trace),
    //   留在这里的后果是**只有走 MainActivity 才预热** —— 而她自己的桌面图标
    //   指向的是 ConMarnActivity,那条路永远不预热。
    //   ★ 别在这儿再包一层转发:预热是 91 秒的 CPU 密集活,两个入口各起一遍
    //     就是白烧一遍电,而且会跟用户那轮抢算力。

    /** 抽屉「AI 助手」入口:先让用户说一句话。 */
    private fun showAiDialog() {
        if (!connected) {
            toast("先连上电脑再用 AI 助手")
            return
        }
        val input = EditText(this)
        input.hint = "想让电脑干什么?比如「打开微信」「放首歌」「关掉浏览器」"
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setPadding(48, 24, 48, 24)
        // 多行好写长指令,但回车仍然直接发
        input.setSingleLine(false)
        input.maxLines = 4

        val dlg = AlertDialog.Builder(this)
            .setTitle("AI 助手")
            .setView(input)
            .setPositiveButton("执行", null)
            .setNeutralButton("设置", null)
            .setNegativeButton("取消", null)
            .create()
        dlg.show()
        aiDialog = dlg

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    dlg.dismiss()
                    runAi(t)
                }
                true
            } else {
                false
            }
        }
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val t = input.text.toString().trim()
            if (t.isEmpty()) {
                toast("先说一句要干什么")
            } else {
                dlg.dismiss()
                runAi(t)
            }
        }
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { showAiSettings() }
    }

    /** 配置两个模型的地址。默认是手机本地 Termux 里的两个 llama-server;
     *  想先拿云端验证流程,就把这里换成任意 OpenAI 兼容端点(DeepSeek / 通义 / OpenAI)。 */
    private fun showAiSettings() {
        val cfg = ai().config
        val pad = (48 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }

        /**
         * 一个标签 + 输入框。[withPaste] 为 true 时在右边挂一个「粘贴」按钮 ——
         * API Key 又长又随机,在手机上纯手打基本打不对,必须能一键贴上来。
         */
        fun field(label: String, value: String, withPaste: Boolean = false): EditText {
            box.addView(android.widget.TextView(this).apply {
                text = label
                setPadding(0, pad / 2, 0, 0)
            })
            val et = EditText(this).apply {
                setText(value)
                setSingleLine()
            }
            if (!withPaste) {
                box.addView(et)
                return et
            }
            val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(et, LinearLayout.LayoutParams(0, wrap, 1f))
                addView(android.widget.Button(this@MainActivity).apply {
                    text = "粘贴"
                    setOnClickListener {
                        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                        // Android 10+ 只允许前台应用读剪贴板;这里是弹窗,一定在前台,没问题。
                        val s = cm.primaryClip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()?.trim().orEmpty()
                        if (s.isEmpty()) {
                            toast("剪贴板里没有文字")
                        } else {
                            et.setText(s)
                            et.setSelection(s.length)   // 光标挪到末尾,方便接着改
                        }
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, wrap))
            })
            return et
        }

        val fText = field("文本模型地址 —— 决定调哪个工具", cfg.textBaseUrl, withPaste = true)
        val fTextModel = field("文本模型名", cfg.textModel)
        val fVision = field("视觉模型地址 —— 只在「点屏幕上某处」时用", cfg.visionBaseUrl, withPaste = true)
        val fVisionModel = field("视觉模型名", cfg.visionModel)
        val fKey = field("API Key(本地模型留空)", cfg.apiKey, withPaste = true)
        val fSteps = field("一次任务最多几步(工具调用次数)", cfg.maxSteps.toString())
        fKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        val fCloudUrl = field("云端兜底地址(默认 DeepSeek;留空=不用兜底)", cfg.cloudBaseUrl, withPaste = true)
        val fCloudModel = field("云端模型名", cfg.cloudModel)
        val fCloudKey = field("云端 API Key —— 填了才启用兜底。只发文字,截图永不上传", cfg.cloudApiKey, withPaste = true)
        fCloudKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        // ── 模型清单：按「需要什么」把活分给不同的家（见 [RouteMath]）──
        //
        // ★ 说明一律写在这里（设置页），别的地方只留选项本身。
        // ★ 留空 = 和以前一模一样：所有活都走上面那一家。填了才有分流。
        box.addView(android.widget.TextView(this).apply {
            text = "模型清单（一行一家：名字|地址|模型|key|能干什么）\n" +
                "例：通义|https://dashscope.aliyuncs.com/compatible-mode|qwen-vl-max|sk-xxx|看图\n" +
                "能干什么写「文字 / 看图 / 代码 / 生图」，多个用逗号隔开。地址写完整的接口前缀，\n" +
                "以 /chat/completions 结尾的就照原样写（豆包方舟的路径不是 /v1，别套模板）。\n" +
                "生图另走 /images/generations（OpenAI 兼容的那一套）：地址填到 /v1 或 /api/v3 为止，\n" +
                "模型名填那家的画图模型（如豆包 Seedream）。没配这一家时，「画一张图」会明说画不了。\n" +
                "拆活时，标了「看图」的那一份才需要能看图的模型；一家都没有时会明说没看到图，\n" +
                "不会拿文字模型去编一段图像描述。留空 = 全部走上面那一家。\n" +
                "音频不在这一栏：你的声音不上传，声音只在本机转成文字。"
            setPadding(0, pad / 2, 0, 0)
        })
        val fProviders = EditText(this).apply {
            setText(cfg.providers)
            setMinLines(3)
            setMaxLines(10)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        box.addView(fProviders)

        // 「测试云端连接」:用当前输入框里的值(不必先保存)打一次最小请求,把原始结果原样摊开。
        // 存在的理由 —— key 输入框是密码框,里面全是圆点,401 的时候根本没法判断是「粘错了」
        // 还是「只有一部分」还是「key 本身失效」。所以这里额外把长度和首尾几位打出来。
        box.addView(android.widget.Button(this).apply {
            text = "测试云端连接(用上面填的,不用先保存)"
            setOnClickListener {
                val url = fCloudUrl.text.toString().trim().trimEnd('/').ifEmpty { cfg.cloudBaseUrl }
                val model = fCloudModel.text.toString().trim().ifEmpty { cfg.cloudModel }
                val key = fCloudKey.text.toString().trim()
                if (key.isEmpty()) {
                    toast("云端 API Key 是空的 —— 填了才启用兜底")
                    return@setOnClickListener
                }
                val summary = "地址:$url\n模型:$model\n" +
                    "Key:${key.length} 位,开头「${key.take(3)}」结尾「${key.takeLast(4)}」"
                toast("正在测…")
                Thread {
                    val r = testCloud(url, key, model)
                    runOnUiThread {
                        // 人已经退出去了就别再往一个没有 token 的窗口上弹框 ——
                        // 那会抛 BadTokenException,和「连不上」毫无关系。
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(if (r.startsWith("OK")) "✅ 云端通了" else "❌ 云端没通")
                            .setMessage("$summary\n\n$r")
                            .setPositiveButton("知道了", null)
                            .show()
                    }
                }.start()
            }
        })

        // 行为数据:微调的地基。显示已攒条数,一键导出到 Download 拿去喂训练。
        box.addView(android.widget.Button(this).apply {
            text = "行为数据:已记录 ${BehaviorLogger.count()} 条 → 导出到 Download"
            setOnClickListener {
                toast(BehaviorLogger.exportToDownloads(this@MainActivity))
            }
        })

        // 经验库:AI 自己攒的「这种界面该怎么找」。点开看明细 —— 让它**可见**,
        // 用户才看得见它是不是在变强(也在变错,错了能发现)。只读,不提供编辑。
        box.addView(android.widget.Button(this).apply {
            text = "AI 经验:${ExperienceStore.summary()}"
            setOnClickListener {
                val lines = ExperienceStore.all().joinToString("\n") { e ->
                    "【${e.kind}】${e.verbs.joinToString(" → ")}\n" +
                        "    可信度 %.0f%%(用过 ${e.uses} 次,成了 ${e.wins} 次)".format(e.confidence * 100) +
                        ",来自${if (e.source == "seed") "预置" else "云端老师"}\n" +
                        "    ${e.reason.take(80)}"
                }.ifEmpty { "还没有经验。用几次 AI,它自己会攒。" }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("AI 攒下来的经验")
                    .setMessage(lines)
                    .setPositiveButton("知道了", null)
                    .show()
            }
        })

        AlertDialog.Builder(this)
            .setTitle("AI 设置")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                cfg.textBaseUrl = fText.text.toString().trim().ifEmpty { cfg.textBaseUrl }
                cfg.textModel = fTextModel.text.toString().trim().ifEmpty { cfg.textModel }
                cfg.visionBaseUrl = fVision.text.toString().trim().ifEmpty { cfg.visionBaseUrl }
                cfg.visionModel = fVisionModel.text.toString().trim().ifEmpty { cfg.visionModel }
                cfg.apiKey = fKey.text.toString().trim()
                cfg.cloudBaseUrl = fCloudUrl.text.toString().trim()
                cfg.cloudModel = fCloudModel.text.toString().trim().ifEmpty { cfg.cloudModel }
                cfg.cloudApiKey = fCloudKey.text.toString().trim()
                cfg.providers = fProviders.text.toString().trim()
                fSteps.text.toString().trim().toIntOrNull()?.let { if (it > 0) cfg.maxSteps = it }
                AiAgent.saveConfig(this, cfg)
                toast("已保存")
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("恢复默认") { _, _ ->
                val d = AiAgent.Config()
                cfg.textBaseUrl = d.textBaseUrl
                cfg.visionBaseUrl = d.visionBaseUrl
                cfg.textModel = d.textModel
                cfg.visionModel = d.visionModel
                cfg.apiKey = ""
                cfg.cloudBaseUrl = d.cloudBaseUrl
                cfg.cloudModel = d.cloudModel
                cfg.cloudApiKey = ""          // key 绝不塞默认值,只能自己填
                cfg.providers = ""            // 清单里全是 key,一并清空
                cfg.localDeadlineMs = d.localDeadlineMs
                cfg.maxSteps = d.maxSteps
                AiAgent.saveConfig(this, cfg)
                toast("已恢复默认(指向手机本地 127.0.0.1)")
            }
            .show()
    }

    /**
     * 拿云端端点打一次最小请求(max_tokens=1),把结果原样返回。
     * 成功和失败都返回字符串、不抛异常 —— 这是给人看的诊断,不是给流程用的。
     */
    private fun testCloud(baseUrl: String, key: String, model: String): String {
        val url = baseUrl.trimEnd('/') + "/v1/chat/completions"
        val body = org.json.JSONObject()
            .put("model", model)
            .put("messages", org.json.JSONArray().put(
                org.json.JSONObject().put("role", "user").put("content", "hi")))
            .put("max_tokens", 1)
            .put("stream", false)
        // ★★ 2026-11-08:`CloudNet.conn` 必须**在 try 里面**。
        //   它自己会抛 —— 地址没写 `http(s)://` 抛 MalformedURLException,云端总闸
        //   关着抛 CloudClosed。写在 try 外面时,异常会从 :999 那个**没有 catch 的
        //   Thread** 里逃出去,而全包没有全局异常处理器 → 点一下「测试云端连接」
        //   App 就当场消失,连一句提示都没有。这是最常见的那条路:填了 key、
        //   总闸还没拨,先点一下试试。
        return try {
            val conn = CloudNet.conn(url, this).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $key")
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code in 200..299) "OK  HTTP $code\n${text.take(300)}"
                else "HTTP $code\n${text.take(400)}"
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            "连不上:${e.javaClass.simpleName} ${e.message.orEmpty()}"
        }
    }

    /** 跑一条指令,并在同一个框里滚动显示过程日志(模型在想什么、调了什么工具、结果如何)。 */
    private fun runAi(userText: String) {
        val pad = (48 * resources.displayMetrics.density).toInt()
        val logView = android.widget.TextView(this).apply {
            textSize = 13f
            setPadding(pad, pad / 2, pad, pad / 2)
            setTextIsSelectable(true)
            text = "指令:$userText\n"
        }
        aiLog = logView
        val scroll = android.widget.ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (220 * resources.displayMetrics.density).toInt()
            )
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle("AI 助手")
            .setView(scroll)
            .setPositiveButton("停止", null)
            .setNegativeButton("关闭", null)
            .create()
        dlg.show()
        aiDialog = dlg

        // 「正在生成」那一行是可以被**整段覆盖**的(见 AiAgent.Callback.onPartial):
        // liveStart 是它在 logView.text 里的起点,-1 = 当前没有这样一行。
        // 它永远在最后,所以「替换」就是掐掉它再写新的。
        //
        // 只在这个 lambda 和 appendLive 里动它,而两者都跑在 UI 线程上 ——
        // 不用加锁,UI 线程自己把顺序排好了。
        var liveStart = -1

        fun append(s: String) {
            // 同一句话也丢一份给 logcat。**只在本机、只在 adb 里读得到**(不写文件、
            // 不出设备、不进云端)—— 换来的是把「AI 的过程」从一块屏上解放出来。
            // 刻意放在 runOnUiThread **外面**:界面卡住时日志照样出得来,而那正是
            // 最需要日志的时候。
            Log.i(AGENT_TAG, s.trim())
            runOnUiThread {
                // 有正式日志了 = 那一行定格成普通内容,后面的日志从下一行接着走。
                liveStart = -1
                if (logView.text.isNotEmpty() && !logView.text.endsWith("\n")) logView.append("\n")
                logView.append(s)
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }

        /**
         * 「模型正在写的这半句话」—— **替换**最后那一行,不是追加。
         *
         * 传空串 = 把这一行撤掉(这一轮其实是去调工具了,那半句不是给用户的答复)。
         *
         * 刻意**不往 logcat 里写**:它是每 250ms 一次的整段重发,写进去会把
         * logcat 环缓冲冲干净 —— 而那正是排查时要看的东西。最终答复由 onFinal
         * 照常记一条完整的,信息不丢。
         */
        fun appendLive(s: String) {
            runOnUiThread {
                // 状态机本身抽在 [liveLineUpdate] 里(纯函数,有单测)—— 这里只管
                // 把它算出来的结果贴回界面。
                val (text, start) = liveLineUpdate(logView.text.toString(), liveStart, s)
                if (text != logView.text.toString()) {
                    logView.text = text
                    if (s.isNotEmpty()) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
                liveStart = start
            }
        }

        val stopBtn = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
        stopBtn.setOnClickListener {
            ai().stop()
            stopBtn.isEnabled = false
            stopBtn.text = "停止中…"
        }
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            ai().stop()
            dlg.dismiss()
            if (aiDialog === dlg) aiDialog = null
        }
        dlg.setOnDismissListener { if (aiDialog === dlg) aiDialog = null }

        ai().start(userText, object : AiAgent.Callback {
            override fun onLog(msg: String) = append(msg)

            // 纯内部进度(第几轮…):只进 logcat,不上屏。排查时一行不少,
            // 但用户不用盯着一屏「第 N 轮」。见 AiAgent.Callback.onTrace 的说明。
            override fun onTrace(msg: String) {
                Log.i(AGENT_TAG, msg.trim())
            }

            // 生成期实时刷新的那半句话。**整段覆盖**,见 appendLive。
            override fun onPartial(text: String) = appendLive(text)

            override fun onFinal(msg: String) {
                // 先撤掉预览行:它内容跟这句答复是同一段话,留着就是屏幕上重复两遍。
                appendLive("")
                append("\n✅ $msg")
                runOnUiThread { stopBtn.text = "完成"; stopBtn.isEnabled = false }
            }

            override fun onError(msg: String) {
                appendLive("")
                append("\n❌ $msg")
                runOnUiThread { stopBtn.text = "出错"; stopBtn.isEnabled = false }
            }

            override fun onBusy(busy: Boolean) = runOnUiThread {
                if (!busy && stopBtn.text == "停止") stopBtn.isEnabled = false
            }

            override fun onConfirm(summary: String, detail: String): Confirm {
                // 在后台线程(ai-agent)被调。弹框必须回主线程,再用锁把本线程挂住等结果;
                // 他在对话框点的「确认/取消」就是返回值。60 秒不点算没回应,免得卡死。
                val latch = java.util.concurrent.CountDownLatch(1)
                val ok = booleanArrayOf(false)
                // ★ 见 [ConfirmMath]:没有它就分不清「他点了取消」和「框没弹出来」。
                val built = java.util.concurrent.atomic.AtomicBoolean(false)
                runOnUiThread {
                    try {
                        val shown = if (detail.length > 300) detail.take(300) + "…(共 ${detail.length} 字)" else detail
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(summary)
                            .setMessage(shown)
                            .setPositiveButton("确认") { _, _ -> ok[0] = true; latch.countDown() }
                            .setNegativeButton("取消") { _, _ -> latch.countDown() }
                            .setCancelable(false)
                            .show()
                        built.set(true)
                    } catch (e: Exception) {
                        latch.countDown() // 弹不出来就放行等待,别把 ai-agent 挂死
                    }
                }
                val answered = try {
                    latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    return Confirm.INTERRUPTED      // 被停止打断了,不是他没回答
                }
                return when {
                    !built.get() -> Confirm.NO_UI
                    !answered -> Confirm.NO_ANSWER
                    ok[0] -> Confirm.APPROVED
                    else -> Confirm.DENIED
                }
            }
        })
    }

    /** 获取种子:优先扫码,也保留手动输入作为兜底。 */
    private fun showSecretChooser() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("配对")
            .setMessage("电脑端已显示配对二维码。请选择获取方式:")
            .setPositiveButton("扫码", null)
            .setNegativeButton("手动输入", null)
            .setCancelable(false)
            .create()
        dialog.show()
        authDialog = dialog
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            dialog.dismiss()
            authDialog = null
            startScan()
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            dialog.dismiss()
            authDialog = null
            showManualSecretInput()
        }
    }

    private fun startScan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchScanner()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    private fun launchScanner() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("扫描电脑上的配对二维码")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
        val intent = options.createScanIntent(this)
        startActivityForResult(intent, REQ_SCAN)
    }

    /**
     * 扫不了码时的兜底:把电脑上那一行 `PAIR1:...` 抄进来。
     *
     * ★ 以前这里抄的是「32 位配对种子」;现在二维码里只是一次性的临时公钥
     *   (见 [PairCrypto]),所以抄的东西也跟着变了 —— 抄错了只会配对失败,
     *   不会把别人写的字符串当密钥存下来。
     */
    private fun showManualSecretInput() {
        showInputDialog(
            "输入配对码",
            "把电脑上那一行 PAIR1: 开头的字符完整抄进来:",
            "PAIR1:...",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            { v ->
                val c = v.filterNot { it.isWhitespace() }
                if (c.startsWith(PairCrypto.PREFIX, ignoreCase = true) && c.length > PairCrypto.PREFIX.length + 80) c else null
            },
            onInput = { client.submitSecret(it) },
            onCancel = { cancelAuth() }
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchScanner()
            } else {
                client.disconnect()
                resetToIdle()
                toast("需要相机权限才能扫码,可改用「手动输入」")
            }
        } else if (requestCode == REQ_CAM) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                cameraOn = true
                mediaStreamer.startCamera()
                updateMediaPanel()
                saveWants()   // 用户这次想要摄像头:记下,断连/重启后自动恢复
            } else {
                toast("需要相机权限才能用摄像头")
            }
        } else if (requestCode == REQ_MIC) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                micOn = true
                mediaStreamer.startMic()
                updateMediaPanel()
                saveWants()   // 同上:想要麦克风
            } else {
                toast("需要录音权限才能用麦克风")
            }
        } else if (requestCode == REQ_LOCAL_NET) {
            if (!(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                toast("未授予「本地网络」权限,将无法连接电脑")
            }
            // 授权与否都触发等待中的动作:授权了就能连上;没授权则连接失败并提示,可去设置里补授。
            pendingLocalReady?.invoke()
            pendingLocalReady = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_SCAN) {
            val result = ScanIntentResult.parseActivityResult(resultCode, data)
            if (result != null && result.contents != null) {
                client.submitSecret(result.contents)
            } else {
                client.disconnect()
                resetToIdle()
                toast("扫码取消")
            }
        } else if (requestCode == REQ_OVERLAY) {
            // 从「悬浮窗授权设置页」回来
            if (Settings.canDrawOverlays(this)) {
                openTransferCenter()
            } else {
                toast("未授权悬浮窗,中转站悬浮球无法显示")
            }
        } else if (requestCode == REQ_PICK_SEND) {
            // 系统文件选择器里挑的文件 -> 发到电脑中转目录(可多选)
            if (resultCode == RESULT_OK && data != null) {
                val uris = ArrayList<Uri>()
                data.clipData?.let { cd ->
                    for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { uris.add(it) }
                }
                if (uris.isEmpty()) data.data?.let { uris.add(it) }
                uploadUris(uris)
            }
        } else if (requestCode == REQ_PICK_DIR) {
            // 选「手机接收电脑文件的文件夹」(SAF 目录树授权)
            if (resultCode == RESULT_OK && data != null) {
                val uri = data.data
                if (uri != null) {
                    PhoneTransferStore.persistTree(this, uri)
                    toast("已设置接收位置:${PhoneTransferStore.summary(this)}")
                    transferOverlay?.onDirChanged()
                }
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    /** 弹一个输入对话框,validator 返回 null 表示无效,否则返回清理后的值。
     *
     * ★ 2026-10-08:外壳换成**透明磨砂玻璃 + 圆角**(他点名的)。原来的默认弹窗是个深灰方框。
     *   四件事必须**一起**做,少一件就读不清 —— 这是换背景最容易踩的一处:
     *     ① 背景   主题 Theme.Touchpad.GlassDialog 里的 windowBackground([dialog_glass])
     *     ② 标题   同上,主题里的 windowTitleStyle
     *     ③ 正文/按钮  主题管不全,在下面**逐个给死** —— 白玻璃上原来是白字,等于没字
     *     ④ 输入框 玻璃上再放一个**深色**小圆角框([her_input_bg]),里面才是浅色的字
     *   ★ 还有一处**不在主题里**:背景一换,AlertDialog 原来那层 9-patch 自带的内衬也没了,
     *     内容会**贴着圆角**。所以 show() 之后给内容补一圈 padding(见下)。
     *   ⚠️ 它**不是真把背后糊掉**:这台机器上 `ro.surface_flinger.supports_background_blur`
     *     是空的(真机 getprop 查过),系统不给做背景模糊。玻璃是画出来的 ——
     *     半透明 + 顶上高光 + 一道白棱,看得见背后的轮廓,但背后没被模糊。
     *   ★ 配对码那个框走的就是这个函数(重新配对现在是纯确认,不再输入,见 [showRepairDialog])。
     */
    private fun showInputDialog(
        title: String,
        message: String,
        hint: String,
        inputType: Int,
        validator: (String) -> String?,
        onInput: (String) -> Unit,
        onCancel: () -> Unit
    ) {
        val input = EditText(this)
        input.hint = hint
        input.inputType = inputType
        input.setBackgroundResource(R.drawable.her_input_bg)   // 玻璃上的深色小框(她那个粉边)
        input.setTextColor(getColor(R.color.text_primary))
        input.setHintTextColor(0x8AF2F3F5.toInt())
        input.setPadding(dp(16), dp(12), dp(16), dp(12))

        val dialog = AlertDialog.Builder(this, R.style.Theme_Touchpad_GlassDialog)
            .setTitle(title)
            .setMessage(message)
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消") { _, _ -> onCancel() }
            .create()
        dialog.show()
        authDialog = dialog

        // 玻璃上的字。★ 三处都要给死没商量:深色主题的默认弹窗字是白的,压在白玻璃上就是看不见。
        //   颜色沿用呼出面板那一套(#263238 系),不新造颜色。
        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(getColor(R.color.glass_text))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.glass_text))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(0x8A263238.toInt())
        // 内衬:背景换成玻璃之后 9-patch 那圈内衬没了,自己补回来,否则内容贴着圆角。
        // 找内容根(不是 decorView)—— decorView 那层会被系统的窗口内边距重新摆,补了会丢。
        (dialog.window?.decorView?.findViewById<View>(android.R.id.content)
            ?: dialog.window?.decorView)?.setPadding(dp(14), dp(12), dp(14), dp(8))

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val cleaned = validator(input.text.toString().trim())
            if (cleaned != null) {
                dialog.dismiss()
                authDialog = null
                onInput(cleaned)
            } else {
                input.error = "输入格式不正确"
            }
        }
    }

    /**
     * 「重新配对」确认框 —— **没有输入框**,只是一次确认。
     *
     * 为什么去掉输入框(2026-11-06 他拍的板):原来这里要输一串 8 位「恢复码」,
     * 那等于在盘上(以及一张纸上)留了一把**长期有效的备用钥匙**。现在改成
     * authenticator 的模型:本机没种子,就重新登记一台。
     * 门槛没变低 —— 电脑那边要先过自己的登录 PIN 才会把新二维码画出来。
     * 代价说清楚:确认之后**原来的种子会被覆盖**,别的手机从此连不上。
     */
    private fun showRepairDialog() {
        val dialog = AlertDialog.Builder(this, R.style.Theme_Touchpad_GlassDialog)
            .setTitle("重新配对")
            .setMessage(
                "这台电脑之前已经配对过,但本机没有它的密钥(重装或清了数据)。\n\n" +
                    "点「重新配对」后,电脑会弹出新的二维码,扫一下即可。\n\n" +
                    "原来的密钥会被替换:其他已配对的手机需要重新扫一次。"
            )
            .setCancelable(false)
            .setPositiveButton("重新配对") { _, _ ->
                authDialog = null
                client.submitRepair()
            }
            .setNegativeButton("取消") { _, _ -> cancelAuth() }
            .create()
        dialog.show()
        authDialog = dialog
        // 玻璃主题下字色要逐个给死(同 [showInputDialog],否则白字压在白玻璃上)
        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(getColor(R.color.glass_text))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.glass_text))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(0x8A263238.toInt())
        (dialog.window?.decorView?.findViewById<View>(android.R.id.content)
            ?: dialog.window?.decorView)?.setPadding(dp(14), dp(12), dp(14), dp(8))
    }

    /** 摄像头开关:把手机摄像头推到电脑当虚拟摄像头。 */
    private fun toggleCamera() {
        if (cameraOn) {
            cameraOn = false
            mediaStreamer.stopCamera()
            updateMediaPanel()
            saveWants()          // 记下「用户这次不想要摄像头」,断连/重启后不再自动开
            return
        }
        enableCamera()
    }

    private fun enableCamera() {
        val cfg = client.getMediaConfig() ?: run { toast("请先连接电脑"); return }
        mediaStreamer.configure(cfg.ip, cfg.port, cfg.token)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraOn = true
            mediaStreamer.startCamera()
            updateMediaPanel()
            saveWants()          // 记下「用户想要摄像头」,断连/被杀重启后自动恢复
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAM)
        }
    }

    /** 麦克风开关:把手机麦克风推到电脑当虚拟麦克风。 */
    private fun toggleMic() {
        if (micOn) {
            micOn = false
            mediaStreamer.stopMic()
            updateMediaPanel()
            saveWants()
            return
        }
        enableMic()
    }

    private fun enableMic() {
        val cfg = client.getMediaConfig() ?: run { toast("请先连接电脑"); return }
        mediaStreamer.configure(cfg.ip, cfg.port, cfg.token)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            micOn = true
            mediaStreamer.startMic()
            updateMediaPanel()
            saveWants()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    /** 把当前 cameraOn/micOn 存进盘上,供断连/被杀重启后自动恢复断前状态。 */
    private fun saveWants() {
        prefs.edit().putBoolean("cam_want", cameraOn).putBoolean("mic_want", micOn).apply()
    }

    /**
     * 恢复断前状态:上次连上时摄像头/麦克风是开的,就自动再开(镜像由 startView 自动重开)。
     * 只认用户上次的「想要」,不是无条件开;用户手动断开/关闭过则不会恢复。
     * 若此刻媒体 token 还没从电脑下发,则等 onNewMediaToken 再来一趟(此时才具备连媒体 socket 的条件)。
     */
    private fun restoreWantedMedia() {
        if (!connected) return
        if (client.getMediaConfig() == null) return   // 媒体 token 未就绪,等 onNewMediaToken 触发
        try {
            if (prefs.getBoolean("cam_want", false) && !cameraOn) enableCamera()
            if (prefs.getBoolean("mic_want", false) && !micOn) enableMic()
        } catch (_: Exception) {
        }
    }

    // ---- 屏幕镜像(镜像=手机上看到的电脑画面)----
    // 统一只有两态:0=关  /  非0=开(固定高清:原生@24 + JPEG 质量 85,文字最清晰)。
    // 以前那套 流畅/均衡/高清/关 的清晰度档位已删掉,不再让你选 —— 开就是高清。
    // 镜像会跟摄像头/麦克风抢 Wi-Fi。
    //
    // ★★ 2026-10-04 晚:**那个「镜像:高清/关」按钮也删了**(它住在被删掉的抽屉里),
    //   所以 `refreshMirrorButton` / `toggleMirror` 两个函数跟着走了。
    //   `mirrorPrefLevel()` 和 `applyMirror()` **留着** —— 它们读的还是盘上那个
    //   `mirror_level`,连接时照旧按它决定开不开镜像。也就是说:
    //   **这个偏好仍然有效,只是界面上不再有一个随时能翻它的开关。**
    //   ⚠️ 想关镜像的人今天得改盘上的值(或以后把它放进 ⚙ 里);我没顺手把它删掉,
    //   是因为删掉它等于**把「关镜像」这个能力一起删了**,而他只是嫌那颗按钮占地方。
    private fun mirrorPrefLevel(): Int = prefs.getInt("mirror_level", 3)

    /** 连接上/续连上时调用:关=不开镜像;开=固定高清档。 */
    private fun applyMirror() {
        if (!connected) return
        if (mirrorPrefLevel() == 0) {
            client.stopView()   // 关:不开镜像,给摄像头/麦克风让带宽
        } else {
            client.setMirrorProfile(0, 24, 85)   // 高清:电脑原生分辨率 @24fps,JPEG 85
            client.startView()
        }
    }

    /** 断开时关掉媒体流,复位开关状态。 */
    private fun stopMedia() {
        cameraOn = false
        micOn = false
        mediaStreamer.stop()
        panelMinimized = false
        updateMediaPanel()
    }

    /**
     * 麦克风/摄像头两个开关图标:只要连着电脑就常驻(绿=开/推流中,红点+斜杠=关);
     * 摄像头人像预览框只在摄像头开启时出现;整体面板可最小化成悬浮球。
     */
    private fun updateMediaPanel() {
        mediaPreviewPanel.visibility =
            if (connected && !panelMinimized) View.VISIBLE else View.GONE
        btnRestorePanel.visibility =
            if (connected && panelMinimized) View.VISIBLE else View.GONE
        micDot.background = getDrawable(if (micOn) R.drawable.dot_green else R.drawable.dot_red)
        camDot.background = getDrawable(if (cameraOn) R.drawable.dot_green else R.drawable.dot_red)
        micSlash.visibility = if (micOn) View.GONE else View.VISIBLE
        camSlash.visibility = if (cameraOn) View.GONE else View.VISIBLE
        previewBox.visibility = if (cameraOn) View.VISIBLE else View.GONE
        // 翻转状态灯跟着相机状态走:重开相机后仍显示上次的翻转开/关,不会闪回默认文案。
        btnFlipCamera.text = getString(
            if (mediaStreamer.isFlipHorizontal()) R.string.flip_camera_on else R.string.flip_camera
        )
        if (!cameraOn) previewImage.setImageBitmap(null)
        // 让保活前台服务类型跟当前状态走:推摄像头/麦克风时带上 camera/microphone,
        // 系统当「正在录像/录音」→ ColorOS 不易再强停服务断 socket。
        KeepAliveService.setRecording(connected && cameraOn, connected && micOn)
        // 连接状态驱动「文件中转站」悬浮球:连上(且已开悬浮窗权限)显示,断开就撤掉
        syncTransferOverlay()
    }

    // ---- 预览面板:拖动 + 双指缩放 + 最小化 ----
    private var panelGestureMode = 0  // 0=空闲 1=拖动 2=缩放
    private var panelDownX = 0f
    private var panelDownY = 0f
    private var panelStartTX = 0f
    private var panelStartTY = 0f
    private var panelStartScale = 1f
    private var panelStartDist = 0f

    private fun setupPanelGestures() {
        mediaPreviewPanel.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (isOnMediaButton(e.rawX, e.rawY)) {
                        panelGestureMode = 0
                        false
                    } else {
                        panelDownX = e.rawX; panelDownY = e.rawY
                        panelStartTX = mediaPreviewPanel.translationX
                        panelStartTY = mediaPreviewPanel.translationY
                        panelGestureMode = 1
                        true
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    panelStartDist = fingerDist(e)
                    panelStartScale = mediaPreviewPanel.scaleX
                    panelGestureMode = 2
                    true
                }
                MotionEvent.ACTION_MOVE -> when (panelGestureMode) {
                    1 -> {
                        mediaPreviewPanel.translationX = panelStartTX + (e.rawX - panelDownX)
                        mediaPreviewPanel.translationY = panelStartTY + (e.rawY - panelDownY)
                        true
                    }
                    2 -> {
                        val d = fingerDist(e)
                        if (d > 1f && panelStartDist > 1f) {
                            val ns = (panelStartScale * d / panelStartDist).coerceIn(0.5f, 3f)
                            mediaPreviewPanel.scaleX = ns
                            mediaPreviewPanel.scaleY = ns
                        }
                        true
                    }
                    else -> true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    panelDownX = e.rawX; panelDownY = e.rawY
                    panelStartTX = mediaPreviewPanel.translationX
                    panelStartTY = mediaPreviewPanel.translationY
                    panelGestureMode = 1
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    panelGestureMode = 0
                    true
                }
                else -> true
            }
        }
    }

    /** 触点是否落在面板上的按钮(切摄像头/最小化)上,是则让按钮处理点击。 */
    private fun isOnMediaButton(x: Float, y: Float): Boolean {
        // ★ 列的是**壳**(44dp 的 `micBtn` / `camBtn` / `kbBtn`),不是里面的图标 ——
        //   壳把图标整个包住了,列壳等于把它们都算上,而且**外面那一圈也归它**。
        //   列图标的话,手指落在那圈看得见的底上会被面板的拖动逻辑吃掉:
        //   表现正是「看得见却按不动」。
        //   ★ 2026-10-06 [kbBtn] 装回来时**必须一起加进这一列** —— 漏了它的症状
        //     正是上面那句:键盘图标看得见、按不动,而且不报错。
        //   ★★ 同一天加的 [kbBackBtn](那颗 ←)同理:它是**新长出来的一颗**,
        //     漏进来就是「按了没反应」,而且没人会怀疑是漏了一行数组。
        //   ★ 2026-10-08 加的 [kbEnterBtn](那颗「回车」)是**第三颗**走同一条教训的 ——
        //     每往这一排长一颗,这一行就得跟着长一颗。
        for (b in arrayOf<View>(micBtn, camBtn, kbBtn, kbBackBtn, kbEnterBtn, btnSwitchCamera, btnFlipCamera, btnMinimize)) {
            if (b.visibility == View.VISIBLE) {
                val loc = IntArray(2)
                b.getLocationOnScreen(loc)
                if (x >= loc[0] && x <= loc[0] + b.width &&
                    y >= loc[1] && y <= loc[1] + b.height) return true
            }
        }
        return false
    }

    private fun fingerDist(e: MotionEvent): Float {
        return if (e.pointerCount >= 2)
            kotlin.math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)) else 0f
    }

    private fun setPanelMinimized(min: Boolean) {
        panelMinimized = min
        if (min) {
            mediaPreviewPanel.visibility = View.GONE
            btnRestorePanel.visibility = View.VISIBLE
        } else {
            mediaPreviewPanel.translationX = 0f
            mediaPreviewPanel.translationY = 0f
            mediaPreviewPanel.scaleX = 1f
            mediaPreviewPanel.scaleY = 1f
            mediaPreviewPanel.visibility = View.VISIBLE
            btnRestorePanel.visibility = View.GONE
        }
    }

    private fun startKeepAlive() {
        // 用户从最近任务划掉本 App 时应「真退出」而非 START_STICKY 复活;每次拉起服务都重挂回调
        KeepAliveService.onUserRemovedTask = { handleUserRemovedTask() }
        try {
            startForegroundService(Intent(this, KeepAliveService::class.java))
        } catch (_: Exception) {
            // 后台时系统可能禁止前台服务启动(Android 12+),忽略即可,前台时会由 onResumed 重拉
        }
    }

    private fun stopKeepAlive() {
        KeepAliveService.onUserRemovedTask = null
        stopService(Intent(this, KeepAliveService::class.java))
    }

    /** 用户划掉任务卡:主动断开控制+媒体、停摄像头/麦克风,并停掉保活服务不再复活。
     *  盘上免密 token 保留,重开 App 会自动连回上次那台电脑。
     *  划掉 = 明确退出,所以把「想要摄像头/麦克风」也清掉,重开后不会自作主张拉起。 */
    private fun handleUserRemovedTask() {
        KeepAliveService.hostActivityLive = false
        try { mediaStreamer.stop() } catch (_: Exception) {}
        // ★ 划掉任务卡 = **用户明确退出**,所以这里连书签条一起收掉。
        //   注意它和 onDestroy 的区别:onDestroy 那条路是「系统为了进她的房间清掉了我」,
        //   书签条**必须活下来**(否则就是这次的 bug 又演一遍)。
        try { StationHolder.hide() } catch (_: Exception) {}
        try { transferOverlay?.dismiss() } catch (_: Exception) {}
        transferOverlay = null
        try { client.listener = null } catch (_: Exception) {}
        try { client.disconnect() } catch (_: Exception) {}
        try { stopKeepAlive() } catch (_: Exception) {}
        cameraOn = false
        micOn = false
        saveWants()
    }

    /**
     * 主界面不可见了(去了她的房间 / 切到别的 App / 熄屏)。
     *
     * 只做一件事:把「主界面可见」这条划掉 —— 她主动开口的前提之一是「你看得见她」,
     * 切出去之后就不该再开口了(见 [ProactiveGreeting])。
     * **计时器不在这里停**:她可能只是在两个界面之间切换,停了再续会白丢时间;
     * 真要关是 [ProactiveGreeting.setEnabled]。
     */
    override fun onPause() {
        // 见 onResume 那处。Android 保证「A.onPause 在 B.onResume 之前」,
        // 所以两个界面来回切的时候这个标志不会反 —— 不用额外做计数。
        SelfHand.visible = false
        // 摘掉她的球 → 中转站的那个钩子(它捕获了 this,挂着就是泄漏;见 onResume)
        ConMarnBubble.onOpenStation = null
        try { ProactiveGreeting.exit("MainActivity") } catch (_: Exception) {}
        super.onPause()
    }

    override fun onDestroy() {
        try { aiAgent?.stop() } catch (_: Exception) {}
        aiAgent = null
        aiDialog = null
        // 只摘回调,**不停**模型进程:它是进程级单例,Activity 重建后要接着用热模型。
        // (进程真被杀时子进程会跟着走,不用我们操心。)
        models?.onState = null
        // ★★ 这里**故意不碰** [StationHolder] —— 这一条 onDestroy 最常走的那条路
        //    就是「用户进了她的房间,系统把我清掉」。书签条要是跟着我一起走,
        //    用户看到的就是那句「点一下就没了」。它住在进程上,不归我管。
        try { transferOverlay?.dismiss() } catch (_: Exception) {}
        transferOverlay = null
        mediaStreamer.stop()
        // 回调捕获了 `this`,必须摘(否则这个 Activity 被它钉住)
        client.listener = null
        // ★ 登记的那个回调也捕获了 `this`,同样得摘 —— 但**只摘回调,不停服务**。
        //   以前这里写的是 `stopKeepAlive()`(顺带把回调清了),那一句正是 bug 的另一半。
        KeepAliveService.onUserRemovedTask = null

        // ★★ 2026-10-05:**这里原来有 `client.disconnect()`,删掉了。**
        //   用户报「好像退出去房间就会显示电脑断连」—— 这一句就是那个「好像」。
        //
        //   它让「电脑那条线」跟着 **MainActivity 这一个界面**一起死,而 MainActivity
        //   只是「电脑控制页」:从她房间点进电脑、再返回,走的就是 onDestroy。
        //   线是 [TouchpadClient] 这个进程级单例的,凭什么由某个界面决定它该不该活?
        //
        //   现在**只有三种情况会断**:用户按了「断开连接」([doDisconnect])、
        //   用户把任务卡划掉([handleUserRemovedTask] / [KeepAliveService.onTaskRemoved])、
        //   以及连接自己失败。**「离开某个页面」不在其中,一条都不占。**
        //
        //   ★ 同一条教训在这个项目里已经是第三次了(见 [PcLink] 文件头、[HerBoot]):
        //     进程级的事实挂到 Activity 的寿命上,不会报错,只会静默地少一块能力。
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 文件互传:中转站悬浮球 + SAF 选择 + 系统「分享」入口
    // ------------------------------------------------------------------
    private fun canOverlay(): Boolean = Settings.canDrawOverlays(this)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 去她的房间。抽屉里的「ConMarn」按钮、以及任何以后要加的第二入口都走这里。
     *
     * 和中转站面板里那张「ConMarn」卡片是同一件事,只是那条路要先连上电脑才走得通 ——
     * 这条路不用。她不该因为电脑没开就消失。
     */
    private fun goHerRoom() {
        try {
            startActivity(Intent(this, ConMarnActivity::class.java))
        } catch (e: Exception) {
            toast("进不去她的房间:${e.message}")
        }
    }

    /** 连接状态驱动悬浮球显隐(updateMediaPanel 每次回调到这里)。 */
    private fun syncTransferOverlay() {
        // ★ 2026-10-04「换主」:去掉了 `connected` 这个门。
        //
        // 原来这块窗口只在连上电脑时立起来 —— 也就是说**没连电脑 = 屏幕上什么也没有**。
        // 现在这里只剩「中转站面板能不能打开」这一件事;面板在没连电脑时也该打得开
        // (它会自己说「电脑没连」)。没权限就还是别建 —— 建了也 addView 不进去。
        //
        // ★★ 2026-10-04 晚:**书签条与呼出面板已经搬去 [StationOverlay](进程级)**。
        //    这里只负责「抽屉」那一半 —— 抽屉的数据(`client`、上传进度、文件列表)
        //    全在这条 Activity 上,它本来就不该在 App 不在时存在。
        //    书签条走 [StationHolder.show],它是幂等的;而且**这一行不是它唯一的立起时机** ——
        //    进她的房间把 MainActivity 销毁之后,它照样在(那正是这次要修的 bug)。
        if (canOverlay()) {
            StationHolder.show(applicationContext)
            val o = transferOverlay ?: TransferOverlay().also { transferOverlay = it }
            o.show()
        } else {
            transferOverlay?.dismiss()
            transferOverlay = null
        }
    }

    /** 抽屉「中转站」入口:没开悬浮窗权限先去开;没连接先自动连;都就绪就呼出悬浮球。 */
    private fun openTransferCenter() {
        if (!canOverlay()) {
            requestOverlayPermission()
            return
        }
        if (!connected) {
            toast("请先连接电脑,再使用文件中转站")
            if (!connecting) autoConnectLast()
            return
        }
        val o = transferOverlay ?: TransferOverlay().also { transferOverlay = it }
        o.show()
        o.expand()
    }

    private fun requestOverlayPermission() {
        // 2026-10-04:「超级终端」这个名字没了,App 就叫 ConMarn。
        // 这句 toast 直接引到系统设置里的路径,名字写错用户就找不到入口。
        toast("请在弹出的系统设置中,允许「ConMarn」显示在其他应用上层")
        try {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
        } catch (_: Exception) {
            toast("无法打开悬浮窗设置,请到 设置→应用→ConMarn→显示在其他应用上层 手动开启")
        }
    }

    /** 系统文件选择器:挑任意文件发到电脑中转目录(可多选)。 */
    private fun launchSendPicker() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(i, REQ_PICK_SEND)
        } catch (_: Exception) {
            toast("无法打开文件选择器")
        }
    }

    /** 系统文件选择器:挑「手机接收文件夹」(下载落点)。 */
    private fun launchDirPicker() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_DIR)
        } catch (_: Exception) {
            toast("无法打开文件夹选择器")
        }
    }

    /** 把若干 content/file Uri 上传到电脑中转目录。每个文件一条独立上传连接(后台线程)。 */
    private fun uploadUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val cfg = client.getFileConfig()
        if (cfg == null) {
            toast("尚未连接电脑,无法发送")
            return
        }
        val resolver = contentResolver
        for (uri in uris) {
            val name = PhoneTransferStore.displayNameOf(this, uri)
            val size = PhoneTransferStore.sizeOf(this, uri)
            toast("开始发送:$name")
            FileTransfer.upload(
                ip = cfg.ip, port = cfg.port, token = cfg.token,
                name = name, knownSize = size,
                openInput = { FileTransfer.openUriStream(resolver, uri) },
                onResult = { ok, msg ->
                    runOnUiThread {
                        transferOverlay?.setTransferEnded()
                        transferOverlay?.setStatus(msg)
                        toast(msg)
                    }
                },
                onProgress = { done, total ->
                    runOnUiThread { transferOverlay?.setProgress("上传", name, done, total) }
                },
            )
        }
    }

    /** 把手机中转站里已存的一个文件上传到电脑(手机端主动,等价于电脑 PULL 的逆操作)。 */
    private fun sendStoredToPc(name: String) {
        val cfg = client.getFileConfig()
        if (cfg == null) {
            toast("尚未连接电脑,无法发送")
            return
        }
        val uri = PhoneTransferStore.findUri(this, name)
        if (uri == null) {
            toast("手机中转站里没有「$name」")
            return
        }
        val resolver = contentResolver
        val size = if (uri.scheme == "content") PhoneTransferStore.sizeOf(this, uri)
        else runCatching { java.io.File(uri.path ?: "").length() }.getOrDefault(-1L)
        FileTransfer.upload(
            ip = cfg.ip, port = cfg.port, token = cfg.token,
            name = name, knownSize = size,
            openInput = { FileTransfer.openUriStream(resolver, uri) },
            onResult = { ok, msg ->
                runOnUiThread {
                    transferOverlay?.setTransferEnded()
                    transferOverlay?.setStatus(msg)
                    toast(msg)
                }
            },
            onProgress = { done, total ->
                runOnUiThread { transferOverlay?.setProgress("上传", name, done, total) }
            },
        )
    }

    /**
     * 处理任意 App「分享」进来的 Intent(ACTION_SEND/SEND_MULTIPLE):
     * 把 EXTRA_STREAM / ClipData 里的文件收集起来;已连接就立刻上传,
     * 没连上就入队并自动去连上次那台电脑,onConnected 时统一补发。
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = ArrayList<Uri>()
        @Suppress("DEPRECATION")
        if (action == Intent.ACTION_SEND) {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
        } else {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        }
        if (uris.isEmpty()) {
            intent.clipData?.let { cd ->
                for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { uris.add(it) }
            }
        }
        // 上传可能要等「连上电脑」:把系统临时授权转成可持久读取(提供方支持才行)
        for (u in uris) {
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        if (uris.isEmpty()) {
            toast("没有读到要发送的文件")
            return
        }
        if (connected) {
            uploadUris(uris)
        } else {
            pendingShare.addAll(uris)
            toast("已把 ${uris.size} 个文件加入待发队列,正在连接电脑…")
            if (!connecting) autoConnectLast()
        }
    }

    /** onConnected 后把断前/冷启动排队等待的「分享」文件发出。 */
    private fun flushPendingShare() {
        if (pendingShare.isEmpty()) return
        val list = ArrayList(pendingShare)
        pendingShare.clear()
        uploadUris(list)
    }

    // 系统中转站:真·系统级悬浮层(标准 API,不影响保修)。
    // 平时 = 屏幕右缘一根白色磨砂「书签条」(12×80,取代旧悬浮球);点它 / 向左滑 =
    // 弹簧呼出一张整屏透明层:左侧轻压暗点按即收,右侧 70% 白色磨砂「中转站」面板,
    // 列出功能卡片【文件传输 | 快捷启动】,点卡片自动收起并跳转。
    // 弹出/收起各带一次短震动(50ms / 30ms),滑入带过冲回弹的弹簧感。
    // 卡片「文件传输」→ 打开文件主面板(45%×3/4 白色磨砂,尺寸不再改动;内含
    //   【电脑文件 | 手机文件】,电脑文件点文件夹进入、点文件拉到手机,手机文件点文件发到电脑)。
    // 卡片「回到房间」→ 回 App 并进她的房间(2026-10-04 从「快捷启动」改的;面板与卡片
    //   现在住在 [StationOverlay])。
    // 所有窗口常驻只切显隐,避免大窗 add/remove 闪一下;书签条会跟随屏幕横竖旋转挪回右缘。
    // ------------------------------------------------------------------
    private inner class TransferOverlay {
        private val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        // ★ 书签条(`handleView`)和呼出面板(`hubView`)曾经是这里的前两个字段。
        //   2026-10-04 晚它们连同手势状态、形状权重一起搬去了 [StationOverlay] ——
        //   因为那个类活在进程上,而这里活在一条会被 `ConMarnActivity` 顶掉的 Activity 上。
        private var drawerView: View? = null          // 文件主面板(45%×3/4 白色磨砂;尺寸不再改动)

        private var expanded = false                  // 文件主面板是否展开
        private var displayListener: android.hardware.display.DisplayManager.DisplayListener? = null

        // 抽屉控件
        private var hintTv: TextView? = null
        private var listBox: LinearLayout? = null
        private var navRow: View? = null
        private var pathTv: TextView? = null
        private var tabPc: Button? = null
        private var tabPhone: Button? = null
        private var bar: ProgressBar? = null
        private var progWrap: View? = null
        private var trashBar: View? = null

        // 竖屏自适应用的权重容器(只剩文件主面板那一组;呼出面板那组跟着它搬去 StationOverlay)
        private var dvGapTop: View? = null
        private var dvMid: View? = null
        private var dvGapLeft: View? = null
        private var dvGapBot: View? = null

        // 电脑文件图片缩略图:路径 -> 位图缓存;每次列目录的请求预算(避免大文件夹一次刷爆)
        private val pcThumbCache = HashMap<String, android.graphics.Bitmap>()
        private var thumbBudget = 0

        // 浏览 / 传输状态
        private var browsing = "pc"
        private var pcPath = ""
        private var lastMsg = ""
        private var loadedOnce = false
        private var transferName = ""
        private var transferKind = ""
        private var transferGot = 0L
        private var transferTotal = -1L

        // ---- 生命周期(被 MainActivity 调用) ----
        fun show() {
            // ★★ 2026-10-04 晚:**书签条回来了。** 当初那句「一行没删,真要退回去把这行
            //    恢复即可」就是为今天留的,现在原样兑现。
            //
            // 为什么退回来(用户原话):
            //   「人物**还是一大张卡在那里**」
            //   「那个中转站**要先打开 app 是什么鬼,以前都不用**,那我这个功能会受限,
            //     **拖不了东西到中转站的**」
            //   「实在不行你就不用这个悬浮窗…**人物悬浮窗改成之前那个书签自动隐藏侧边栏的
            //     悬浮窗**」
            //
            // 他那两条抱怨指向同一件事:**那条书签条就是中转站本尊,她的球只是个门牌。**
            // 换主之后,「往中转站拖东西」这条主用途要多走一步(点球 → 点按钮),
            // 而 MainActivity 不在前台时那一步还会退化成一句 toast ——
            // **一个入口要多开一个 App 才存在,那它就不是入口。**
            //
            // 她的球([ConMarnBubble])没删,只是默认关了(见那边 isEnabled),
            // 想叫她出来:中转站 →「她的悬浮窗」卡片。
            //
            // ★★ 2026-10-04 晚(第二轮):**书签条和呼出面板又搬了一次家,这回搬出这个类。**
            //    它们现在住在 [StationOverlay](进程级)。原因是用户那句
            //    「**确实有书签也确实能回到房间,但是好像点一下就没了**」——
            //    根因是这里的 `dismiss()` 挂在 MainActivity.onDestroy 上,而
            //    `ConMarnActivity` 是 singleTask 且是任务根,**进她的房间必然销毁 MainActivity**。
            //    详见 [StationOverlay] 文件头那张表。
            //    这个类现在只管**抽屉**(文件主面板):它的数据全在 MainActivity 上,
            //    App 不在时它本来就不该存在。
            //
            // ★ 这里**不主动建抽屉**(它由 `openDrawer()` 懒建):`show()` 是从
            //   `syncTransferOverlay()` 每回调一次就调一次的,顺手建一个全屏窗口
            //   会在屏幕上多出一层看不见但拦触摸的东西。只挂转屏监听。
            watchDisplay()      // 屏幕旋转(横↔竖)时抽屉要跟着挪回新屏
        }

        /**
         * 打开「中转站」面板(文件传输 / 回到房间 / AI 助手 / ConMarn 五张卡片)。
         *
         * ★ 2026-10-04:入口从右缘那条书签条挪到她的球的面板里。那条条子原来是
         * **唯一**能打开这个面板的东西,换主时必须把入口补回来,否则「换个脸」
         * 会顺手弄丢文件互传那条路。
         *
         * ★ 2026-10-04 晚:面板本身也搬去 [StationOverlay] 了,这里**转交**。
         *   留着这个方法是为了不惊动既有调用点(她的球那个钩子)。
         */
        fun openStation() {
            StationHolder.openHub(this@MainActivity)
        }

        fun dismiss() {
            unwatchDisplay()
            removeDrawer()
            expanded = false
        }

        // ---- 屏幕旋转跟随:App 本身锁横屏,但书签条要悬在「桌面 / 其他竖屏 App」之上也好用 ----
        private fun watchDisplay() {
            if (displayListener != null) return
            val dmgr = getSystemService(android.content.Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager ?: return
            val cb = object : android.hardware.display.DisplayManager.DisplayListener {
                override fun onDisplayAdded(id: Int) {}
                override fun onDisplayRemoved(id: Int) {}
                override fun onDisplayChanged(id: Int) {
                    if (id == android.view.Display.DEFAULT_DISPLAY) {
                        // 回调在 Binder 线程,挪窗口要回主线程
                        android.os.Handler(android.os.Looper.getMainLooper()).post { repositionAll() }
                    }
                }
            }
            displayListener = cb
            try {
                dmgr.registerDisplayListener(cb, android.os.Handler(android.os.Looper.getMainLooper()))
            } catch (_: Exception) {}
        }

        private fun unwatchDisplay() {
            val cb = displayListener ?: return
            displayListener = null
            try {
                (getSystemService(android.content.Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
                    ?.unregisterDisplayListener(cb)
            } catch (_: Exception) {}
        }

        /** 屏幕尺寸/方向变了:把抽屉挪到新屏尺寸。
         *  ★ 书签条与呼出面板那份同理的活现在在 [StationOverlay.repositionAll] 里。 */
        private fun repositionAll() {
            drawerView?.let { dv ->
                val lp = lpOf(dv)
                if (lp != null) {
                    lp.x = drawerX()
                    lp.y = drawerY()
                    lp.width = drawerW()
                    lp.height = drawerH()
                    try { wm.updateViewLayout(dv, lp) } catch (_: Exception) {}
                }
                if (!expanded) dv.translationX = drawerDist().toFloat()
            }
            applyDrawerShape()
        }

        /** 给 MainActivity 等外部调:屏幕旋转/尺寸变了,把抽屉挪回新屏正确位置。 */
        fun onScreenChanged() {
            repositionAll()
        }

        fun expand() { openDrawer() }          // 抽屉「中转站」按钮、系统分享等直接打开文件主面板

        fun collapse() {
            if (!expanded) return
            expanded = false
            val v = drawerView ?: return
            val dist = drawerDist()
            // 滑出 + 淡出。结束后窗口保持存在(内容推到屏外、透明、不拦截触摸),
            // 不再 removeView —— 之前每次移除大悬浮窗会触发系统原地补帧 → 闪一下。
            v.animate().translationX(dist.toFloat()).alpha(0f).setDuration(170)
                .withEndAction {
                    val lp = lpOf(v)
                    if (lp != null) {
                        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                }.start()
        }

        private fun removeDrawer() {
            try { drawerView?.let { wm.removeView(it) } } catch (_: Exception) {}
            drawerView = null
            bar = null
        }

        /** 当前真实屏幕尺寸(随系统旋转 / 回到桌面实时变化),用于抽屉定位。
            用 WindowManager.defaultDisplay 而非 resources.displayMetrics:后者在 app 退到
            桌面后可能仍停留在 app 的横屏配置,导致侧边条只能按错误宽度吸附、拖不过另一边。 */
        private fun realDm(): android.util.DisplayMetrics {
            // 物理屏当前真实尺寸。wm.defaultDisplay.getRealMetrics 在本 App 锁横屏退到后台
            // (物理屏已被前台应用转回竖屏)时,会停留在 App 的横屏尺寸(2374x1080),导致
            // 书签条/呼出面板按横屏摆放、竖屏下开到了屏外 → 「桌面/其他 App 里呼出不了」。
            // Resources.getSystem() 跟随物理屏当前真实方向(横=2374x1080,竖=1080x2374),以此为准。
            val dm = android.util.DisplayMetrics()
            try {
                val sys = android.content.res.Resources.getSystem().displayMetrics
                if (sys.widthPixels > 0 && sys.heightPixels > 0) {
                    dm.setTo(sys)
                    return dm
                }
            } catch (_: Exception) {}
            try {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
            } catch (_: Exception) {}
            if (dm.widthPixels <= 0 || dm.heightPixels <= 0) dm.setTo(resources.displayMetrics)
            return dm
        }

        // ---- 形状自适应:同一套布局,横屏(App 内)是 45%×75% 的方卡;竖屏(桌面/其他 App)
        // 若还按 75% 高度取,卡片会被拉成通高细条,看着像"没改"。这里按当前物理方向调权重,
        // 竖屏压成宽 62%、高约 68%(主面板)/52%(呼出面板)的明显"悬浮方框",上下大留白。 ----
        private fun portraitNow(): Boolean {
            val dm = realDm()
            return dm.heightPixels > dm.widthPixels
        }

        private fun setWeight(v: View?, w: Float) {
            val lp = v?.layoutParams as? LinearLayout.LayoutParams ?: return
            lp.weight = w
            try { v?.requestLayout() } catch (_: Exception) {}
        }

        private fun setRowShape(gapTop: View?, mid: View?, gapBot: View?,
                                gapLeft: View?, card: View?,
                                top: Float, midW: Float, bot: Float,
                                left: Float, cardW: Float) {
            setWeight(gapTop, top)
            setWeight(mid, midW)
            setWeight(gapBot, bot)
            setWeight(gapLeft, left)
            setWeight(card, cardW)
        }

        /** 文件主面板(文件中转站):横屏 45%宽×75%高;竖屏 62%宽×68%高,上下留白。 */
        private fun applyDrawerShape() {
            val p = portraitNow()
            val card = drawerView?.findViewById<View>(R.id.ov_root)
            if (p) setRowShape(dvGapTop, dvMid, dvGapBot, dvGapLeft, card,
                1.28f, 5.44f, 1.28f, 7.6f, 12.4f)
            else setRowShape(dvGapTop, dvMid, dvGapBot, dvGapLeft, card,
                1f, 6f, 1f, 11f, 9f)
        }

        private fun lpOf(v: View): WindowManager.LayoutParams? =
            v.layoutParams as? WindowManager.LayoutParams

        /** 短促震动:面板弹出 50ms / 收起 30ms,模拟「弹出/归位」的物理感。 */
        private fun buzz(ms: Long) {
            try {
                val vib = getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    vib.vibrate(
                        android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(ms)
                }
            } catch (_: Exception) {}
        }

        // 面板窗口铺满整屏:左侧透明触控区(点按收起)+ 右侧约 1/3 白玻璃面板(布局里用 weight 分)。
        // 整窗从右侧滑入/滑出,面板实际只占右侧 1/3。
        private fun drawerW(): Int = realDm().widthPixels
        private fun drawerH(): Int = realDm().heightPixels
        private fun drawerX(): Int = 0
        private fun drawerY(): Int = 0
        private fun drawerDist(): Int = realDm().widthPixels

        // ---- 抽屉(展开态;窗口常驻只切显隐) ----
        /** 首次懒建抽屉窗口:建好后先锁在“内容屏外 + 透明 + 不拦截”,等真正展开才亮出。 */
        private fun ensureDrawerWindow(): Boolean {
            if (drawerView != null) return true
            val v = LayoutInflater.from(this@MainActivity).inflate(R.layout.overlay_transfer, null)
            drawerView = v
            bindDrawer(v)
            val p = WindowManager.LayoutParams(
                drawerW(), drawerH(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = drawerX()
                this.y = drawerY()
            }
            try {
                wm.addView(v, p)
            } catch (e: Exception) {
                drawerView = null
                toast("无法弹出中转站(请检查悬浮窗权限)")
                return false
            }
            val dist = drawerDist()
            v.alpha = 0f
            v.translationX = dist.toFloat()
            return true
        }

        fun openDrawer() {
            if (expanded) return
            if (!ensureDrawerWindow()) return
            val v = drawerView ?: return
            // 贴住侧边条当前停靠的那一侧
            val lp = lpOf(v)
            if (lp != null) {
                lp.x = drawerX()
                lp.y = drawerY()
                lp.width = drawerW()
                lp.height = drawerH()
                lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE   // 清掉 NOT_TOUCHABLE
                try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
            }
            val dist = drawerDist()
            v.visibility = View.VISIBLE
            v.alpha = 0f
            v.translationX = dist.toFloat()
            expanded = true
            renderTabs()
            renderNav()
            if (!loadedOnce) {
                loadedOnce = true
                switchBrowse("pc")
            } else if (browsing == "pc") {
                client.sendLs(pcPath)
                refreshHint()
            } else {
                renderPhoneRows()
            }
            applyDrawerShape()
            // 从屏幕侧边滑入 + 淡入
            v.animate().translationX(0f).alpha(1f).setDuration(190).start()
        }

        private fun bindDrawer(v: View) {
            hintTv = v.findViewById(R.id.ov_hint)
            listBox = v.findViewById(R.id.ov_list)
            navRow = v.findViewById(R.id.ov_nav)
            pathTv = v.findViewById(R.id.ov_path)
            tabPc = v.findViewById(R.id.ov_tab_pc)
            tabPhone = v.findViewById(R.id.ov_tab_phone)
            bar = v.findViewById(R.id.ov_bar)
            progWrap = v.findViewById(R.id.ov_prog)
            trashBar = v.findViewById(R.id.ov_trash)
            dvGapTop = v.findViewById(R.id.ov_gap_top)
            dvMid = v.findViewById(R.id.ov_mid)
            dvGapLeft = v.findViewById(R.id.ov_gap_left)
            dvGapBot = v.findViewById(R.id.ov_gap_bot)
            v.findViewById<View>(R.id.ov_scrim).setOnClickListener { collapse() }   // 点屏幕其他地方收起
            val close = v.findViewById<ImageButton>(R.id.ov_close)
            // 抽屉永远贴右缘(原来那个可左可右的 `dockRight` 跟着书签条一起搬走了,
            // 而书签条那边也一直是右侧 —— 留着它只会是一个恒为 true 的变量)
            close.rotation = 0f
            close.setOnClickListener { collapse() }
            val back = v.findViewById<ImageButton>(R.id.ov_btn_back)
            back.rotation = 180f
            back.setOnClickListener { goUp() }
            // 顶栏两个功能钮已是纯图标(发送/目录)
            v.findViewById<View>(R.id.ov_btn_send).setOnClickListener { launchSendPicker() }
            v.findViewById<View>(R.id.ov_btn_dir).setOnClickListener { launchDirPicker() }
            tabPc?.setOnClickListener { switchBrowse("pc") }
            tabPhone?.setOnClickListener { switchBrowse("phone") }
            bindTrashDrag(v)
        }

        private fun renderTabs() {
            tabPc?.isSelected = browsing == "pc"
            tabPhone?.isSelected = browsing == "phone"
        }

        // ---- 提示与状态 ----
        fun setStatus(msg: String) {
            lastMsg = msg
            hintTv?.text = msg
        }

        private fun refreshHint() {
            if (!expanded) return
            hintTv?.text = lastMsg.ifEmpty {
                when {
                    browsing != "pc" -> "点文件 → 发到电脑"
                    pcPath.isEmpty() -> "点文件名 → 拉到手机"
                    else -> "点文件夹进入 · 点文件拉到手机"
                }
            }
        }

        private fun switchBrowse(mode: String) {
            browsing = mode
            pcPath = ""
            navRow?.visibility = View.GONE
            lastMsg = ""
            renderTabs()
            if (mode == "pc") {
                hintTv?.text = "载入电脑文件…"
                client.sendLs("")
            } else {
                renderPhoneRows()
            }
        }

        private fun goUp() {
            if (browsing != "pc") return
            val idx = pcPath.lastIndexOf('/')
            pcPath = if (idx < 0) "" else pcPath.substring(0, idx)
            renderNav()
            client.sendLs(pcPath)
        }

        private fun childPath(name: String): String =
            if (pcPath.isEmpty()) name else "$pcPath/$name"

        private fun enterDir(name: String) {
            pcPath = childPath(name)
            renderNav()
            setStatus("进入「$pcPath」…")
            client.sendLs(pcPath)
        }

        private fun renderNav() {
            if (!expanded) return
            if (browsing != "pc" || pcPath.isEmpty()) {
                navRow?.visibility = View.GONE
            } else {
                navRow?.visibility = View.VISIBLE
                pathTv?.text = "…/$pcPath"
            }
        }

        /** 电脑返回 LSR(仅抽屉开着、在「电脑文件」页时渲染)。 */
        fun onPcListing(json: JSONObject) {
            if (!expanded || browsing != "pc") return
            val err = json.optString("err")
            if (err.isNotEmpty()) {
                listBox?.removeAllViews()
                addEmpty("无法读取:$err")
                setStatus("无法读取:$err")
                renderNav()
                return
            }
            val arr = json.optJSONArray("entries")
            listBox?.removeAllViews()
            if (arr == null || arr.length() == 0) {
                addEmpty("(空)")
                refreshHint()
                renderNav()
                return
            }
            thumbBudget = 60          // 每列一次目录,给缩略图请求发一次预算(已在缓存的不占)
            val cells = ArrayList<View>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val n = o.optString("n")
                val isDir = o.optInt("d", 0) == 1
                val size = o.optLong("s", 0L)
                val key = childPath(n)
                cells.add(buildCell(
                    n, isDir, size, isPhone = false,
                    {
                        if (isDir) enterDir(n)
                        else {
                            setStatus("正在从电脑拉取「$n」…")
                            client.sendGet(key)
                        }
                    },
                    dragJson = if (isDir) null else makeDragJson("pc", key, n),
                    thumbKey = if (!isDir && isImageExt(n)) key else null
                ))
            }
            renderCells(cells)
            refreshHint()
            renderNav()
        }

        private fun renderPhoneRows() {
            listBox?.removeAllViews()
            val entries = PhoneTransferStore.list(this@MainActivity)
            if (entries == null) {
                addEmpty("无法读取手机中转文件夹")
                return
            }
            val files = entries.filter { !it.isDir }
            if (files.isEmpty()) {
                addEmpty("(空) 点上方「发送」按钮选文件试试")
                return
            }
            val cells = ArrayList<View>()
            for (e in files) {
                cells.add(buildCell(
                    e.name, false, e.size, isPhone = true,
                    {
                        setStatus("正在发送「${e.name}」到电脑…")
                        sendStoredToPc(e.name)
                    },
                    dragJson = makeDragJson("phone", e.name, e.name)
                ))
            }
            renderCells(cells)
            refreshHint()
        }

        /** 用户重选了手机收件夹:刷新(若在手机页)列表。 */
        fun onDirChanged() {
            if (!expanded) return
            lastMsg = ""
            if (browsing == "phone") renderPhoneRows() else refreshHint()
        }

        // ---- 传输进度 ----
        fun setProgress(kind: String, name: String, done: Long, total: Long) {
            transferKind = kind
            transferName = name
            transferGot = done
            transferTotal = total
            if (!expanded) return
            if (total > 0) {
                progWrap?.visibility = View.VISIBLE
                bar?.visibility = View.VISIBLE
                bar?.progress = (done * 1000 / total).toInt().coerceIn(0, 1000)
                hintTv?.text = "$kind「$name」 ${(done * 100 / total).toInt()}%"
            } else {
                bar?.visibility = View.GONE
                hintTv?.text = "$kind「$name」 ${sizeText(done)}…"
            }
        }

        fun setTransferEnded() {
            transferName = ""
            transferGot = 0L
            transferTotal = -1L
            progWrap?.visibility = View.GONE
            bar?.visibility = View.GONE
            refreshHint()
        }

        // ---- 两列网格 ----
        private fun addEmpty(text: String) {
            listBox?.removeAllViews()
            val tv = TextView(this@MainActivity).apply {
                this.text = text
                setTextColor(Color.parseColor("#9A263238"))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(18), 0, dp(18))
            }
            listBox?.addView(
                tv,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        /** 把已构建的格子按每行两列排进列表;末行单数时补透明占位保持对齐。 */
        private fun renderCells(cells: List<View>) {
            listBox?.removeAllViews()
            var i = 0
            while (i < cells.size) {
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                val firstLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                firstLp.marginEnd = dp(3)
                row.addView(cells[i], firstLp)
                if (i + 1 < cells.size) {
                    val secondLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    secondLp.marginStart = dp(3)
                    row.addView(cells[i + 1], secondLp)
                } else {
                    val spacer = View(this@MainActivity)
                    val spLp = LinearLayout.LayoutParams(0, 1, 1f)
                    spLp.marginStart = dp(3)
                    row.addView(spacer, spLp)
                }
                listBox?.addView(
                    row,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                i += 2
            }
        }

        /** 单个网格格子:上部缩略图/图标区(高 76dp),图片不标文件名、其余省略号截断单行名。 */
        private fun buildCell(name: String, isDir: Boolean, size: Long, isPhone: Boolean,
                              onClick: () -> Unit, dragJson: String? = null,
                              thumbKey: String? = null): View {
            val isImg = isImageExt(name)
            val cell = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setBackgroundResource(R.drawable.ov_row_bg)
                setPadding(dp(4), dp(5), dp(4), dp(5))
                setOnClickListener { onClick() }
            }
            // 图标区压矮(76→56),让一屏多排几行文件
            val box = FrameLayout(this@MainActivity)
            box.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            )
            when {
                isDir -> {
                    val ic = folderIcon()
                    ic.scaleType = ImageView.ScaleType.CENTER
                    box.addView(ic, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
                }
                isImg -> {
                    val ic = ImageView(this@MainActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    box.addView(ic, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ))
                    if (isPhone) loadThumb(ic, name)
                    else {
                        placeholderIv(ic)
                        if (thumbKey != null) {
                            ic.tag = thumbKey            // 缩略图回来时凭这个 tag 定位
                            requestPcThumb(ic, thumbKey)
                        }
                    }
                }
                else -> {
                    val tag = extTagOf(name)
                    if (tag != null) {
                        box.addView(typeChip(tag.first, tag.second), FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
                        ))
                    } else {
                        val ic = fileIcon()
                        box.addView(ic, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
                    }
                }
            }
            cell.addView(box)
            // 图片不标文件名;其余文件名单行、末尾省略号
            if (!isImg) {
                val nameTv = TextView(this@MainActivity).apply {
                    text = name
                    setTextColor(Color.parseColor("#E4263238"))
                    textSize = 10f
                    setSingleLine(true)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setPadding(0, dp(3), 0, 0)
                }
                cell.addView(nameTv, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }
            // 短按即拿起 → 拖到上方垃圾桶删除(仅文件;文件夹不支持删除)。
            // 不用系统长按:ColorOS 系统中转站的长按拖拽会和它打架。
            if (!isDir && dragJson != null) attachPickDrag(cell, dragJson)
            return cell
        }

        /** 电脑图片还没缩略图时的占位:通用文件图标(蓝色)。 */
        private fun placeholderIv(iv: ImageView) {
            iv.setImageResource(R.drawable.ic_file)
            iv.setColorFilter(Color.parseColor("#B03E7BD1"), android.graphics.PorterDuff.Mode.SRC_IN)
            iv.scaleType = ImageView.ScaleType.CENTER
        }

        /** 用位图填充图片格:清掉占位、切真图、平铺裁剪。 */
        private fun applyThumb(iv: ImageView, bmp: android.graphics.Bitmap) {
            iv.clearColorFilter()
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageBitmap(bmp)
        }

        // ---- 电脑图片缩略图 ----
        private fun requestPcThumb(iv: ImageView, rel: String) {
            val cached = pcThumbCache[rel]
            if (cached != null) {
                applyThumb(iv, cached)
                return
            }
            if (thumbBudget <= 0) return
            thumbBudget--
            client.sendThumb(rel)
        }

        /** 缩略图 JPEG 到达(经 listener post 到 UI):按 tag 找到那个图片格并填上。 */
        fun onPcThumb(rel: String, jpeg: ByteArray) {
            val bmp = try {
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            } catch (e: Exception) { null } ?: return
            if (pcThumbCache.size > 240) pcThumbCache.clear()
            pcThumbCache[rel] = bmp
            if (!expanded || browsing != "pc") return
            val iv = findThumbIv(rel) ?: return
            applyThumb(iv, bmp)
        }

        private fun findThumbIv(rel: String): ImageView? {
            val box = listBox ?: return null
            for (i in 0 until box.childCount) {
                val row = box.getChildAt(i) as? LinearLayout ?: continue
                for (j in 0 until row.childCount) {
                    val cell = row.getChildAt(j) as? ViewGroup ?: continue
                    if (cell.childCount == 0) continue
                    val b = cell.getChildAt(0) as? FrameLayout ?: continue
                    if (b.childCount == 0) continue
                    val iv = b.getChildAt(0) as? ImageView ?: continue
                    if (rel == iv.tag?.toString()) return iv
                }
            }
            return null
        }

        // ---- 短按拿起拖到垃圾桶删除 ----
        private fun makeDragJson(side: String, key: String, name: String): String =
            JSONObject().put("side", side).put("key", key).put("name", name).toString()

        /** 短按即拿起:按下停留约 PICK_DELAY_MS(0.15s)就把文件拖起来。
         *  比系统长按(~0.5s)快很多,ColorOS 系统中转站的长按手势来不及触发,不会打架。
         *  快速点按(不移动、很快松手)仍是普通点击;按下后立刻大幅滑动视为列表滚动,不拿起。 */
        private fun attachPickDrag(cell: View, dragJson: String) {
            val slop = ViewConfiguration.get(this@MainActivity).scaledTouchSlop
            var downX = 0f
            var downY = 0f
            var live = false
            val pick = Runnable {
                live = false
                startDragFile(cell, dragJson)
            }
            cell.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        downY = e.y
                        live = true
                        cell.removeCallbacks(pick)
                        cell.postDelayed(pick, PICK_DELAY_MS)
                        false                       // 不吞事件:普通点击/滚动仍走默认
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (live && (Math.abs(e.x - downX) > slop || Math.abs(e.y - downY) > slop)) {
                            live = false            // 起手就滑 → 判定为列表滚动
                            cell.removeCallbacks(pick)
                        }
                        false
                    }
                    else -> {                       // UP / CANCEL
                        live = false
                        cell.removeCallbacks(pick)
                        false
                    }
                }
            }
        }

        private fun startDragFile(v: View, payload: String) {
            try {
                buzz(30)
                val clip = ClipData.newPlainText("file", payload)
                v.startDragAndDrop(clip, View.DragShadowBuilder(v), null, 0)
            } catch (e: Exception) {
                setStatus("拖动失败:${e.message}")
            }
        }

        /** 垃圾桶栏:接受拖放,悬停变红,放下弹确认。 */
        private fun bindTrashDrag(v: View) {
            val trash = v.findViewById<View>(R.id.ov_trash) ?: return
            trashBar = trash
            trash.setOnDragListener { _, event ->
                when (event.action) {
                    android.view.DragEvent.ACTION_DRAG_STARTED -> true
                    android.view.DragEvent.ACTION_DRAG_ENTERED,
                    android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                        trash.setBackgroundResource(R.drawable.ov_trash_bg_on)
                        true
                    }
                    android.view.DragEvent.ACTION_DRAG_EXITED,
                    android.view.DragEvent.ACTION_DRAG_ENDED -> {
                        trash.setBackgroundResource(R.drawable.ov_trash_bg)
                        true
                    }
                    android.view.DragEvent.ACTION_DROP -> {
                        trash.setBackgroundResource(R.drawable.ov_trash_bg)
                        val clip = event.clipData
                        val payload = clip?.getItemAt(0)?.text?.toString()
                        if (!payload.isNullOrEmpty()) deleteDropped(payload) // 直接删,不弹确认
                        true
                    }
                    else -> false
                }
            }
        }

        /** 松开在垃圾桶上:不再弹确认(桌面/其他 App 时本 App 不在前台,对话框弹不出),
         *  直接删 —— 手机文件真删,电脑文件移入回收站(可恢复)。 */
        private fun deleteDropped(payload: String) {
            val o = try { JSONObject(payload) } catch (e: Exception) { null } ?: return
            val side = o.optString("side")
            val key = o.optString("key")
            val name = o.optString("name")
            if (key.isEmpty()) return
            if (side == "phone") deletePhoneFile(name) else client.sendDel(key)
        }

        private fun deletePhoneFile(name: String) {
            Thread {
                val ok = PhoneTransferStore.deleteByName(this@MainActivity, name)
                runOnUiThread {
                    if (ok) {
                        setStatus("已删除手机中转文件「$name」")
                        if (browsing == "phone") renderPhoneRows()
                    } else {
                        setStatus("删除失败:「$name」")
                    }
                }
            }.apply { isDaemon = true; start() }
        }

        /** 服务端 DEL 结果回执(经 listener post 到 UI)。 */
        fun onDelResult(ok: Boolean, msg: String) {
            setStatus(msg)
            if (ok && browsing == "pc") client.sendLs(pcPath)   // 刷新电脑目录列表
        }

        private fun isImageExt(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
        }

        /** 手机侧图片:后台按文件名去中转目录找 Uri,解出真实缩略图。 */
        private fun loadThumb(iv: ImageView, name: String) {
            iv.setImageResource(R.drawable.ic_file)
            iv.setColorFilter(Color.parseColor("#B03E7BD1"), android.graphics.PorterDuff.Mode.SRC_IN)
            iv.scaleType = ImageView.ScaleType.CENTER
            Thread {
                val bmp = try {
                    val uri = PhoneTransferStore.findUri(this@MainActivity, name) ?: return@Thread
                    val resolver = this@MainActivity.contentResolver
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    var sample = 1
                    while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                } catch (e: Exception) { null }
                runOnUiThread {
                    if (bmp != null) {
                        iv.scaleType = ImageView.ScaleType.CENTER_CROP
                        iv.setImageBitmap(bmp)
                        iv.clearColorFilter()
                    }
                }
            }.apply { isDaemon = true; start() }
        }

        private fun folderIcon(): ImageView = ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_folder)
            setColorFilter(Color.rgb(224, 158, 62), android.graphics.PorterDuff.Mode.SRC_IN)
        }

        private fun fileIcon(): ImageView = ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_file)
            setColorFilter(Color.parseColor("#FF71808E"), android.graphics.PorterDuff.Mode.SRC_IN)
            alpha = 0.9f
        }

        private fun typeChip(tag: String, color: Int): TextView {
            val g = android.graphics.drawable.GradientDrawable()
            g.setColor(color)
            g.cornerRadius = dp(6).toFloat()
            return TextView(this@MainActivity).apply {
                text = tag
                setTextColor(Color.WHITE)
                textSize = 9f
                gravity = Gravity.CENTER
                background = g
                minWidth = dp(32)
                includeFontPadding = false
                setPadding(dp(5), 0, dp(5), 0)
            }
        }

        /** 已知扩展名 -> (标签, 色块色)。认不出的返回 null,显示通用文件图标。 */
        private fun extTagOf(name: String): Pair<String, Int>? {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return null
            val groups = mapOf(
                "图片" to setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg", "ico"),
                "视频" to setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts"),
                "音频" to setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "opus"),
                "文档" to setOf("doc", "docx", "odt", "rtf", "wps", "md"),
                "表格" to setOf("xls", "xlsx", "csv", "ods"),
                "演示" to setOf("ppt", "pptx"),
                "PDF" to setOf("pdf"),
                "ZIP" to setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso"),
                "APK" to setOf("apk", "xapk"),
                "TXT" to setOf("txt", "text", "log"),
                "代码" to setOf("kt", "kts", "java", "py", "js", "ts", "c", "cpp", "h", "cs",
                    "go", "rs", "php", "html", "xml", "json", "sql", "yml", "yaml", "gradle", "sh"),
                "EXE" to setOf("exe", "msi", "dll"),
            )
            val colors = mapOf(
                "图片" to Color.rgb(66, 165, 245),
                "视频" to Color.rgb(149, 117, 205),
                "音频" to Color.rgb(255, 167, 38),
                "文档" to Color.rgb(92, 123, 244),
                "表格" to Color.rgb(38, 166, 154),
                "演示" to Color.rgb(255, 138, 101),
                "PDF" to Color.rgb(239, 83, 80),
                "ZIP" to Color.rgb(255, 152, 0),
                "APK" to Color.rgb(0, 191, 165),
                "TXT" to Color.rgb(102, 187, 106),
                "代码" to Color.rgb(126, 87, 194),
                "EXE" to Color.rgb(120, 144, 156),
            )
            for ((k, exts) in groups) if (ext in exts) {
                val label = when (k) {
                    "文档" -> "DOC"
                    "表格" -> "XLS"
                    "演示" -> "PPT"
                    "TXT" -> "TXT"
                    else -> if (k.length <= 4) k.uppercase() else k.take(4).uppercase()
                }
                return label to (colors[k] ?: Color.rgb(150, 160, 170))
            }
            return null
        }

        private fun sizeText(s: Long): String {
            if (s < 0) return ""
            val kb = 1024.0
            return when {
                s < kb -> "$s B"
                s < kb * kb -> d1(s / kb) + " KB"
                s < kb * kb * kb -> d1(s / kb / kb) + " MB"
                else -> d1(s / kb / kb / kb) + " GB"
            }
        }

        private fun d1(v: Double): String =
            if (v >= 100) v.toLong().toString()
            else String.format("%.1f", v).replace(',', '.')
    }

    companion object {
        private const val TAG = "SuperTerminal"
        // AI 助手的过程日志单独占一个 tag。理由:学习闭环(找不到 → 查经验库 → 问老师
        // → 学会)**只有真机跑起来才验得了**,而它的日志原本只落在手机那一块屏幕上,
        // 想核实就只能凭肉眼盯。给它一个能 `adb logcat -s RuoxiAgent` 单独捞的口子。
        private const val AGENT_TAG = "RuoxiAgent"
        private const val REQ_CAMERA = 100
        private const val REQ_SCAN = 101
        private const val REQ_CAM = 102
        private const val REQ_MIC = 103
        private const val REQ_LOCAL_NET = 104   // Android 16+ 局域网(本地网络)权限
        private const val REQ_OVERLAY = 104   // 悬浮窗授权设置页返回
        private const val REQ_PICK_SEND = 105 // 系统文件选择器:选文件发电脑
        private const val REQ_PICK_DIR = 106  // 系统文件夹选择器:手机接收目录
        private const val PICK_DELAY_MS = 150L // 文件按下多久即“拿起”拖拽(避开系统长按)

        // ---- ★★ 2026-10-06:虚键码 ----
        //   [VK_BACKSPACE] 是「键盘输入条」那条子用剩的,条子删掉之后它一度零调用方;
        //   **同一天它被右边那颗 ← 按钮重新用上了**(用户原话:「加一个←的按钮用来作为
        //   删除键(一个一个删)」)—— 所以别再把这里当成「删剩的孤儿」清掉。
        //   ★ 为什么退格要**真的按退格键**,而不是发一段「退格字符」:见 [TouchpadClient.sendKey]。
        //   ★ 虚键码必须和电脑端 `server.py` 的 `VK_KEYS` 对上 —— **白名单不是通道**,
        //     发别的一律回 `sent=false`(电脑那边一次都不会动)。
        //     ★★ 2026-10-08:这里原来挂着半句「(回车是 13,那张表上也有;今天没有调用方,
        //        要用的时候照这个格式加一行。)」—— **现在有调用方了**,就是右边那颗「回车」,
        //        所以它从「备查的备注」变成下面实实在在一行常量。**别把它再删回去。**
        //     ⚠️ 想加**这张表以外的**键(Ctrl/Shift/字母键/组合键)在这里加常量是**没用的**:
        //        电脑端 `VK_KEYS` 会拒,手机这边弹一句「电脑不认这个键」。要用组合键得先动电脑端。
        private const val VK_BACKSPACE = 8
        /** 回车:**换行**还是**提交**由电脑上那个窗口决定,这颗按钮只管「按一下」。 */
        private const val VK_RETURN = 13

        // ---- 从「中转站呼出面板」回来时要做的事 ----
        //
        // ★★ 这几个**必须是静态的**(2026-10-04 晚)。它们原来是实例字段,因为当时
        //    面板是 MainActivity 自己弹的,「谁弹的谁收尾」成立。
        //    现在面板搬到了 [StationOverlay](进程级,见那个文件头),**弹面板的时候
        //    MainActivity 很可能根本不存在** —— 实例字段写在谁身上?写不上,于是
        //    「点文件传输 → 打开了 App 但抽屉没开」,而且**不报错**。
        //    静态之后语义才对:**这是「交给下一个打开的主界面」的便条,不是某个实例的状态。**
        //
        // ★★ 2026-10-04 晚:抽屉删掉之后,「文件传输」那张卡的便条**改了意思**——
        //   它从前叫 `pendingOpenDrawer`(开抽屉),现在叫 [pendingOpenTransfer]
        //   (开文件面板)。名字必须跟着改:留着旧名字会让下一个人以为抽屉还在。
        //
        //    同时添了 [pendingScan] / [pendingDisconnect]:**扫描和断开连接搬进了中转站**,
        //    而它们要用的东西(发现列表弹窗、媒体流复位、保活服务)全都在主界面上,
        //    所以走的是和 AI 助手同一条路 —— 留张便条 + 把主界面拉起来。
        @Volatile var pendingOpenTransfer = false // 「文件传输」回前台后打开文件面板
        @Volatile var pendingOpenAi = false       // 「AI 助手 → 电脑」回前台后弹出对话框
        @Volatile var pendingScan = false         // 中转站「扫描」回前台后开始扫描
        @Volatile var pendingDisconnect = false   // 中转站「断开连接」回前台后断开
    }
}
