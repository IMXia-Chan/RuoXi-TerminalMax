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
import android.text.InputType
import android.util.Log
import android.view.Gravity
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
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var client: TouchpadClient
    private lateinit var screen: ScreenView
    private lateinit var btnDisconnect: Button
    private lateinit var btnScan: Button
    private lateinit var btnKeyboard: Button
    private lateinit var btnMirror: Button
    private lateinit var drawerContainer: LinearLayout
    private lateinit var drawerPanel: LinearLayout
    private lateinit var drawerHandle: View
    private var drawerOpen = false
    private var autoHideRunnable: Runnable? = null

    private lateinit var mediaStreamer: MediaStreamer
    private lateinit var mediaPreviewPanel: LinearLayout
    private lateinit var previewBox: FrameLayout
    private lateinit var previewImage: ImageView
    private lateinit var btnSwitchCamera: Button
    private lateinit var btnFlipCamera: Button
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
    private var pendingOpenDrawer = false                 // 呼出面板「快捷启动」回前台后要打开控制抽屉

    private val prefs by lazy { getSharedPreferences("touchpad", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        hideSystemBars()
        // 保活:屏幕常亮,不因长时间不用而熄屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 标记「本进程有宿主 Activity」:进程被系统整杀后 KeepAliveService 若裸复活
        // (新进程里没有 MainActivity),会让它立即自停,不再出现「闪退后又自己冒出来」。
        KeepAliveService.hostActivityLive = true
        // 屏幕旋转/尺寸变化时(即使本 App 退到后台、书签条还悬在桌面或其他 App 上),
        // 全进程都会收到 onConfigurationChanged:借此把书签条/面板重新贴回新屏右缘。
        // 单用 DisplayManager 监听在部分 ColorOS 上退到后台后不回调,这里用组件回调兜底。
        applicationContext.registerComponentCallbacks(object : android.content.ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
                try { transferOverlay?.onScreenChanged() } catch (_: Exception) {}
                // 旋转瞬间 getRealMetrics 可能还在过渡值,稍后再对一次,确保贴准最终尺寸
                window.decorView.postDelayed(
                    { try { transferOverlay?.onScreenChanged() } catch (_: Exception) {} },
                    260
                )
            }

            override fun onLowMemory() {}
        })

        screen = findViewById(R.id.screen)
        btnDisconnect = findViewById(R.id.btn_disconnect)
        btnScan = findViewById(R.id.btn_scan)
        btnKeyboard = findViewById(R.id.btn_keyboard)
        btnMirror = findViewById(R.id.btn_mirror)
        drawerContainer = findViewById(R.id.drawer_container)
        drawerPanel = findViewById(R.id.drawer_panel)
        drawerHandle = findViewById(R.id.drawer_handle)
        mediaPreviewPanel = findViewById(R.id.media_preview_panel)
        previewBox = findViewById(R.id.preview_box)
        previewImage = findViewById(R.id.preview_image)
        btnSwitchCamera = findViewById(R.id.btn_switch_camera)
        btnFlipCamera = findViewById(R.id.btn_flip_camera)
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
                toast("首次配对:请扫描电脑上的二维码")
                showSecretChooser()
            }

            override fun onRecoveryRequired() {
                toast("本机缺少密钥,请输入恢复码")
                showInputDialog(
                    "输入恢复码",
                    "这台电脑之前已配对过。请输入首次配对时保存的 8 位恢复码,重新配对:",
                    "8 位数字",
                    InputType.TYPE_CLASS_NUMBER,
                    { v -> if (v.length == 8 && v.all { it.isDigit() }) v else null },
                    onInput = { client.submitRecovery(it) },
                    onCancel = { cancelAuth() }
                )
            }

            override fun onConnected() {
                toast("已连接")
                connected = true
                connecting = false
                applyMirror()          // 按镜像开关(开=高清)决定是否开屏幕镜像
                startKeepAlive()
                maybeAskIgnoreBatteryOptimizations()   // 首次连上时引导允许忽略电池优化(治 ColorOS 强停)
                setConnecting(false)   // 连上后恢复扫描/断开按钮,别一直灰着
                updateMediaPanel()
                restoreWantedMedia()   // 自动恢复断前开着的摄像头/麦克风
                flushPendingShare()    // 断前/冷启动时排队的「分享」文件,连上后自动发
            }

            override fun onDisconnected() {
                dismissAuthDialog()
                toast("已断开")
                connected = false
                connecting = false
                setConnecting(false)
                stopKeepAlive()
                stopMedia()
                screen.clear()
            }

            override fun onResumed() {
                // 掉线后自动免码续连成功:UI 不复位、不弹「已断开」。
                // 重新拉起保活服务(ColorOS 刚把它停掉就是断连主因),并按镜像档位重开屏幕镜像;
                // 摄像头/麦克风流会由 MediaStreamer 检测断连后自行续连。
                connected = true
                setConnecting(false)
                startKeepAlive()
                applyMirror()
                updateMediaPanel()
                restoreWantedMedia()   // 保险:若续连时流确实停了,把断前开着的再拉起来
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
                setConnecting(false)
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
        // 双指上下滑 -> 滚轮:正数向上滚(页面往下看),负数向下滚
        screen.onWheel = { w ->
            if (w != 0) client.sendMouse(0, 0, w, 0)
        }

        btnKeyboard.setOnClickListener {
            showTextInput()
            setDrawerOpen(false)
        }
        btnMirror.setOnClickListener { toggleMirror() }

        btnScan.setOnClickListener { discoverComputers() }
        btnDisconnect.setOnClickListener { doDisconnect() }
        micIcon.setOnClickListener { toggleMic() }
        camIcon.setOnClickListener { toggleCamera() }
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
        updateMediaPanel()   // 初始状态:未连接 → 断开按钮灰、媒体面板隐藏
        refreshMirrorButton()   // 镜像按钮显示 关/高清

        // 抽屉:点击把手呼出/收起;收起时只露一条把手
        drawerHandle.setOnClickListener { toggleDrawer() }
        drawerContainer.post { setDrawerOpen(false) }

        // 跨重启免密:只在进程全新启动时自动连上次的电脑(旋转/恢复旧 Activity 不重连)。
        // 有存下的免密 token 就秒连;过期/没有才自动进配对,此时只需输一次 6 位码。
        if (savedInstanceState == null) {
            drawerContainer.post { autoConnectLast() }
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
        // 回前台时屏幕方向/尺寸可能已变,先把悬浮书签条/面板贴回当前屏
        try { transferOverlay?.onScreenChanged() } catch (_: Exception) {}
        // 呼出面板「快捷启动」卡片:把 App 拉到前台后,自动打开控制抽屉(连接/镜像/相机/麦克风等快捷开关),用后即清。
        if (pendingOpenDrawer) {
            pendingOpenDrawer = false
            drawerContainer.post { setDrawerOpen(true) }
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
        setConnecting(false)
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
        setConnecting(true)
        client.connect(ip, port)
    }

    /**
     * 冷启动自动连「上次那台电脑」:connect() 里会先试盘上存下的免密 token,
     * 服务器几分钟内仍认(滑动过期)就秒连、一个码都不用输;token 没了/过期则自动进入
     * 完整配对流程,这时只需输一次电脑端弹的 6 位配对码。实现「跨重启免密」。
     */
    private fun autoConnectLast() {
        if (connected) return
        val stored = prefs.getString("ip", null)?.trim() ?: return
        if (stored.isEmpty()) return
        val idx = stored.lastIndexOf(':')
        val ip: String
        val port: Int
        if (idx > 0) {
            ip = stored.substring(0, idx)
            port = stored.substring(idx + 1).toIntOrNull() ?: 9527
        } else {
            ip = stored
            port = 9527
        }
        connectTo(ip, port)
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
        setConnecting(false)     // 恢复扫描按钮
        screen.clear()           // 清掉残留的电脑画面
        toast("已断开连接")
        setDrawerOpen(false)
    }

    /** 连接/扫描期间禁用扫描入口,避免重复点击产生并发连接、弹两个配对码。 */
    private fun setConnecting(connecting: Boolean) {
        btnScan.isEnabled = !connecting
    }

    /** 扫描局域网里的电脑端(靠电脑 UDP 广播,无需手输 IP)。 */
    private fun discoverComputers() {
        val found = ArrayList<Pair<String, Int>>()
        setConnecting(true)
        btnScan.text = "扫描中…"
        toast("正在扫描局域网…")
        client.discover(
            timeoutMs = 3000L,
            onFound = { ip, port ->
                if (found.none { it.first == ip && it.second == port }) {
                    found.add(ip to port)
                }
            },
            onDone = {
                btnScan.text = getString(R.string.scan)
                when {
                    found.isEmpty() -> {
                        toast("没找到电脑,请确认电脑端已启动、且手机电脑同一 Wi-Fi")
                        setConnecting(false)
                    }
                    found.size == 1 -> connectTo(found[0].first, found[0].second)
                    else -> {
                        showFoundList(found)
                        setConnecting(false)
                    }
                }
            }
        )
    }

    private fun showFoundList(found: List<Pair<String, Int>>) {
        val labels = found.map { (ip, port) -> "$ip:$port" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("发现 ${found.size} 台电脑,选一个连接")
            .setItems(labels) { _, which ->
                val (ip, port) = found[which]
                connectTo(ip, port)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 弹一个输入框,把文本发到电脑当前焦点处;软键盘右下角提供单独的「发送」键。 */
    private fun showTextInput() {
        val input = EditText(this)
        input.hint = "输入要发送到电脑的文本"
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setPadding(48, 24, 48, 24)

        val dialog = AlertDialog.Builder(this)
            .setTitle("键盘输入")
            .setView(input)
            .setPositiveButton("发送", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()

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

    /** 获取种子:优先扫码,也保留手动输入作为兜底。 */
    private fun showSecretChooser() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("获取配对种子")
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

    private fun showManualSecretInput() {
        showInputDialog(
            "输入配对种子",
            "电脑端已显示 32 位配对种子,请照抄(短横线可省略):",
            "32 位字符",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            { v ->
                val c = v.replace("-", "").replace(" ", "").lowercase()
                if (c.length == 32 && c.all { it in "0123456789abcdef" }) c else null
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

    /** 弹一个输入对话框,validator 返回 null 表示无效,否则返回清理后的值。 */
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
        input.setPadding(48, 24, 48, 24)

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消") { _, _ -> onCancel() }
            .create()
        dialog.show()
        authDialog = dialog

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

    /** 呼出/收起左侧抽屉。 */
    private fun toggleDrawer() {
        setDrawerOpen(!drawerOpen)
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
    // 镜像会跟摄像头/麦克风抢 Wi-Fi,需要时用按钮关掉即可;坐标由电脑端按镜像缩放自动对准,不受影响。
    private fun mirrorPrefLevel(): Int = prefs.getInt("mirror_level", 3)

    private fun refreshMirrorButton() {
        btnMirror.text = if (mirrorPrefLevel() == 0) "镜像:关" else "镜像:高清"
    }

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

    /** 点镜像按钮:只是 开⇄关(开 = 高清)。选完立刻生效并记住。 */
    private fun toggleMirror() {
        val was = mirrorPrefLevel()
        val next = if (was == 0) 3 else 0
        prefs.edit().putInt("mirror_level", next).apply()
        refreshMirrorButton()
        if (connected) applyMirror()
        toast(if (next == 0) "镜像已关(想再看电脑画面就再点一下)" else "镜像:高清(已套用并记住)")
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
        btnDisconnect.isEnabled = connected
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
        for (b in arrayOf<View>(micIcon, camIcon, btnSwitchCamera, btnFlipCamera, btnMinimize)) {
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

    private fun setDrawerOpen(open: Boolean) {
        drawerOpen = open
        val panelW = drawerPanel.width.toFloat()
        drawerContainer.animate()
            .translationX(if (open) 0f else -panelW)
            .setDuration(220)
            .start()
        autoHideRunnable?.let { drawerContainer.removeCallbacks(it) }
        if (open) {
            autoHideRunnable = Runnable { setDrawerOpen(false) }
            drawerContainer.postDelayed(autoHideRunnable!!, AUTO_HIDE_MS)
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
        try { transferOverlay?.dismiss() } catch (_: Exception) {}
        transferOverlay = null
        try { client.listener = null } catch (_: Exception) {}
        try { client.disconnect() } catch (_: Exception) {}
        try { stopKeepAlive() } catch (_: Exception) {}
        cameraOn = false
        micOn = false
        saveWants()
    }

    override fun onDestroy() {
        KeepAliveService.hostActivityLive = false
        try { transferOverlay?.dismiss() } catch (_: Exception) {}
        transferOverlay = null
        mediaStreamer.stop()
        client.listener = null
        client.disconnect()
        stopKeepAlive()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 文件互传:中转站悬浮球 + SAF 选择 + 系统「分享」入口
    // ------------------------------------------------------------------
    private fun canOverlay(): Boolean = Settings.canDrawOverlays(this)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    /** 连接状态驱动悬浮球显隐(updateMediaPanel 每次回调到这里)。 */
    private fun syncTransferOverlay() {
        if (connected && canOverlay()) {
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
        toast("请在弹出的系统设置中,允许「超级终端」显示在其他应用上层")
        try {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
        } catch (_: Exception) {
            toast("无法打开悬浮窗设置,请到 设置→应用→超级终端→显示在其他应用上层 手动开启")
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
    // 卡片「快捷启动」→ 回 App 并打开控制抽屉。
    // 所有窗口常驻只切显隐,避免大窗 add/remove 闪一下;书签条会跟随屏幕横竖旋转挪回右缘。
    // ------------------------------------------------------------------
    private inner class TransferOverlay {
        private val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        private var handleView: View? = null          // 右侧磨砂书签条(呼出控件)
        private var hubView: View? = null             // 呼出面板(整屏透明层,右 70% 白玻璃)
        private var drawerView: View? = null          // 文件主面板(45%×3/4 白色磨砂;尺寸不再改动)

        private var dockRight = true                  // 书签条与面板都固定在屏幕右侧
        private var expanded = false                  // 文件主面板是否展开
        private var hubOpen = false                   // 呼出面板是否展开
        private var dragging = false
        private var swipeIn = false                   // 从右缘向左滑,视为「呼出」
        private var downRawX = 0f
        private var downRawY = 0f
        private val dragSlop = 14
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

        // 竖屏自适应用的权重容器(drawer=文件主面板,hub=呼出面板)
        private var dvGapTop: View? = null
        private var dvMid: View? = null
        private var dvGapLeft: View? = null
        private var dvGapBot: View? = null
        private var hubGapTop: View? = null
        private var hubMid: View? = null
        private var hubGapLeft: View? = null
        private var hubGapBot: View? = null

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
            // 只负责把右侧书签条立起来(呼出面板 / 文件主面板都是第一次用到才懒建,之后常驻复用)
            if (handleView == null && !createHandle()) return
            handleView?.apply {
                visibility = View.VISIBLE
                alpha = 1f
            }
            watchDisplay()   // 屏幕旋转(横↔竖)时自动把书签条/面板挪回新屏的右缘
            onScreenChanged()   // 顺便按当前屏重新贴边(可能是在别处旋转后才回来的)
        }

        fun dismiss() {
            unwatchDisplay()
            removeDrawer()
            removeHub()
            removeHandle()
            expanded = false
            hubOpen = false
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

        /** 屏幕尺寸/方向变了:把书签条贴回新屏右缘,并把面板/抽屉挪到新屏尺寸。 */
        private fun repositionAll() {
            val dm = realDm()
            handleView?.let { h ->
                val lp = lpOf(h)
                if (lp != null) {
                    dockRight = true
                    lp.width = handleW()
                    lp.height = handleH()
                    lp.x = dm.widthPixels - handleW() - dp(2)
                    lp.y = dm.heightPixels / 2 - handleH() / 2
                    try { wm.updateViewLayout(h, lp) } catch (_: Exception) {}
                }
            }
            hubView?.let { hv ->
                val lp = lpOf(hv)
                if (lp != null) {
                    lp.x = drawerX()
                    lp.y = drawerY()
                    lp.width = drawerW()
                    lp.height = drawerH()
                    try { wm.updateViewLayout(hv, lp) } catch (_: Exception) {}
                }
                // 收起态的面板要推去「屏宽」之外,不然会露在屏幕边上
                hv.translationX = if (hubOpen) 0f else drawerDist().toFloat()
            }
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
            applyHubShape()
            applyDrawerShape()
        }

        /** 给 MainActivity 等外部调:屏幕旋转/尺寸变了,把书签条与各面板挪回新屏正确位置。 */
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

        private fun removeHandle() {
            try { handleView?.let { wm.removeView(it) } } catch (_: Exception) {}
            handleView = null
        }

        private fun removeDrawer() {
            try { drawerView?.let { wm.removeView(it) } } catch (_: Exception) {}
            drawerView = null
            bar = null
        }

        private fun removeHub() {
            try { hubView?.let { wm.removeView(it) } } catch (_: Exception) {}
            hubView = null
        }

        // ---- 右侧磨砂书签条(平时唯一的可见窗口) ----
        private fun handleW() = dp(12)
        private fun handleH() = dp(80)

        /** 当前真实屏幕尺寸(随系统旋转 / 回到桌面实时变化),用于贴边和抽屉定位。
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

        /** 呼出面板(中转站):横屏 45%宽×75%高;竖屏 62%宽×52%高,上下大留白 → 明显方框。 */
        private fun applyHubShape() {
            val p = portraitNow()
            val card = hubView?.findViewById<View>(R.id.hub_panel)
            if (p) setRowShape(hubGapTop, hubMid, hubGapBot, hubGapLeft, card,
                1.92f, 4.16f, 1.92f, 7.6f, 12.4f)
            else setRowShape(hubGapTop, hubMid, hubGapBot, hubGapLeft, card,
                1f, 6f, 1f, 11f, 9f)
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

        /** 建立右侧书签条窗口。窗口常驻,只在 dismiss 时销毁;固定贴屏幕右缘并竖直居中。 */
        private fun createHandle(): Boolean {
            if (handleView != null) return true
            val dm = realDm()
            val w = handleW()
            val h = handleH()
            val x = dm.widthPixels - w - dp(2)
            val y = dm.heightPixels / 2 - h / 2
            dockRight = true
            Log.i(TAG, "createHandle 书签条 右侧 x=$x y=$y w=${dm.widthPixels}")
            val v = LayoutInflater.from(this@MainActivity).inflate(R.layout.overlay_bookmark, null)
            // 书签条给「精确像素尺寸」而不是 WRAP_CONTENT:悬浮窗里若放 match_parent 子视图,
            // ColorOS 可能把 WRAP 窗撑大/触发区放大,导致在屏幕任意位置都能点到它。
            val p = WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x
                this.y = y
            }
            handleView = v
            try {
                wm.addView(v, p)
            } catch (e: Exception) {
                handleView = null
                return false
            }
            v.setOnTouchListener { _, e -> onHandleTouch(e) }
            return true
        }

        private fun lpOf(v: View): WindowManager.LayoutParams? =
            v.layoutParams as? WindowManager.LayoutParams

        private fun onHandleTouch(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    dragging = false
                    swipeIn = false
                    Log.i(TAG, "bookmark DOWN raw=${e.rawX.toInt()},${e.rawY.toInt()}")
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    if (!dragging &&
                        (kotlin.math.abs(dx) > dragSlop || kotlin.math.abs(e.rawY - downRawY) > dragSlop)) {
                        dragging = true
                    }
                    if (dragging && dx < -dragSlop) swipeIn = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val cancel = e.actionMasked == MotionEvent.ACTION_CANCEL
                    Log.i(TAG, "bookmark ${if (cancel) "CANCEL" else "UP"} dragging=$dragging swipeIn=$swipeIn")
                    // 点一下书签条,或从右缘向左滑 → 呼出「中转站」面板
                    if (!cancel && (!dragging || swipeIn)) openHub()
                    dragging = false
                    swipeIn = false
                    true
                }
                else -> true
            }
            return true
        }

        // ---- 呼出面板(中转站首页:整屏透明层,右 70% 白玻璃 + 功能卡片) ----

        private fun ensureHubWindow(): Boolean {
            if (hubView != null) return true
            val v = LayoutInflater.from(this@MainActivity).inflate(R.layout.overlay_hub, null)
            hubView = v
            bindHub(v)
            val p = WindowManager.LayoutParams(
                drawerW(), drawerH(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = drawerX()
                y = drawerY()
            }
            try {
                wm.addView(v, p)
            } catch (e: Exception) {
                hubView = null
                return false
            }
            // 隐藏态:推到屏幕右缘之外 + 透明 + 不拦截
            v.alpha = 0f
            v.translationX = drawerDist().toFloat()
            return true
        }

        /** 点书签条 / 向左滑 → 面板带「过冲回弹」滑入,弹出给 50ms 短震。 */
        private fun openHub() {
            if (hubOpen) return
            if (expanded) collapse()          // 若文件主面板开着先收好,别两层叠着
            if (!ensureHubWindow()) return
            val v = hubView ?: return
            val lp = lpOf(v)
            if (lp != null) {
                lp.x = drawerX()
                lp.y = drawerY()
                lp.width = drawerW()
                lp.height = drawerH()
                lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE   // 清掉 NOT_TOUCHABLE
                try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
            }
            v.visibility = View.VISIBLE
            v.alpha = 1f
            v.translationX = drawerDist().toFloat()
            applyHubShape()
            hubOpen = true
            v.animate().translationX(0f).setDuration(260)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.35f))
                .start()
            buzz(50)
        }

        /** 收起呼出面板(回落成书签条)。收起时给 30ms 轻震;onDone 在动画结束后执行。 */
        private fun closeHub(onDone: Runnable? = null) {
            if (!hubOpen) {
                onDone?.run()
                return
            }
            hubOpen = false
            val v = hubView ?: run { onDone?.run(); return }
            buzz(30)
            val anim = v.animate().translationX(drawerDist().toFloat()).alpha(0f).setDuration(180)
            anim.withEndAction {
                val lp = lpOf(v)
                if (lp != null) {
                    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                }
                onDone?.run()
            }
            anim.start()
        }

        private fun bindHub(v: View) {
            v.findViewById<View>(R.id.hub_scrim).setOnClickListener { closeHub() }   // 点屏幕其他地方收起
            v.findViewById<View>(R.id.hub_close).setOnClickListener { closeHub() }   // 右上角箭头
            v.findViewById<View>(R.id.hub_card_file).setOnClickListener { goTransfer() }
            v.findViewById<View>(R.id.hub_card_quick).setOnClickListener { goQuick() }
            hubGapTop = v.findViewById(R.id.hub_gap_top)
            hubMid = v.findViewById(R.id.hub_mid)
            hubGapLeft = v.findViewById(R.id.hub_gap_left)
            hubGapBot = v.findViewById(R.id.hub_gap_bot)
        }

        /** 卡片 · 文件传输:收起呼出面板 → 打开「电脑文件 / 手机文件」文件主面板。 */
        private fun goTransfer() {
            closeHub {
                openDrawer()
            }
        }

        /** 卡片 · 快捷启动:回 App 并打开控制抽屉(连接/镜像/相机/麦克风等快捷开关)。 */
        private fun goQuick() {
            closeHub()
            bringAppFront(openControlDrawer = true)
        }

        private fun bringAppFront(openControlDrawer: Boolean) {
            if (openControlDrawer) this@MainActivity.pendingOpenDrawer = true
            try {
                startActivity(
                    Intent(this@MainActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            } catch (_: Exception) {
            }
        }

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
            close.rotation = if (dockRight) 0f else 180f
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
        private const val REQ_CAMERA = 100
        private const val REQ_SCAN = 101
        private const val REQ_CAM = 102
        private const val REQ_MIC = 103
        private const val REQ_OVERLAY = 104   // 悬浮窗授权设置页返回
        private const val REQ_PICK_SEND = 105 // 系统文件选择器:选文件发电脑
        private const val REQ_PICK_DIR = 106  // 系统文件夹选择器:手机接收目录
        private const val AUTO_HIDE_MS = 5000L
        private const val PICK_DELAY_MS = 150L // 文件按下多久即“拿起”拖拽(避开系统长按)
    }
}
