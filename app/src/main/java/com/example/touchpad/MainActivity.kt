package com.example.touchpad

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions

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
                applyMirror()          // 按镜像档位设置(高清/均衡/流畅/关)决定是否开镜像、开多清
                startKeepAlive()
                maybeAskIgnoreBatteryOptimizations()   // 首次连上时引导允许忽略电池优化(治 ColorOS 强停)
                setConnecting(false)   // 连上后恢复扫描/断开按钮,别一直灰着
                updateMediaPanel()
                restoreWantedMedia()   // 自动恢复断前开着的摄像头/麦克风
            }

            override fun onDisconnected() {
                dismissAuthDialog()
                toast("已断开")
                connected = false
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
        btnMirror.setOnClickListener { cycleMirror() }

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
        refreshMirrorButton()   // 镜像档位按钮显示上次选的清晰度(默认均衡)

        // 抽屉:点击把手呼出/收起;收起时只露一条把手
        drawerHandle.setOnClickListener { toggleDrawer() }
        drawerContainer.post { setDrawerOpen(false) }

        // 跨重启免密:只在进程全新启动时自动连上次的电脑(旋转/恢复旧 Activity 不重连)。
        // 有存下的免密 token 就秒连;过期/没有才自动进配对,此时只需输一次 6 位码。
        if (savedInstanceState == null) {
            drawerContainer.post { autoConnectLast() }
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

    // ---- 屏幕镜像档位(镜像=手机上看到的电脑画面)----
    // 档位:0=关 1=流畅 2=均衡(默认) 3=高清。镜像越清占用 Wi-Fi 越大,会跟摄像头/麦克风抢带宽,
    // 掉帧或延迟高时调低或关掉;档位存盘,重连自动按档位开。坐标由电脑端按镜像缩放自动对准,不受影响。
    private fun mirrorPrefLevel(): Int = prefs.getInt("mirror_level", 2)

    private fun mirrorLevelName(level: Int): String = when (level) {
        0 -> "关"
        1 -> "流畅"
        2 -> "均衡"
        else -> "高清"
    }

    /** 档位 -> (最大宽度, 帧率上限, JPEG 质量);宽≤0 = 电脑原生分辨率。质量越低每帧越小、越省带宽。
        均衡默认质量 80:比 88 每帧能小约 1/3,文字仍清晰,给摄像头/麦克风留更多 Wi-Fi。
        高清用 原生@24(原生@30 实测太容易掉帧),稳定性优先。 */
    private fun mirrorProfile(level: Int): Triple<Int, Int, Int> = when (level) {
        1 -> Triple(1024, 20, 70)
        2 -> Triple(1600, 24, 80)
        3 -> Triple(0, 24, 85)
        else -> Triple(0, 0, 0)
    }

    private fun refreshMirrorButton() {
        btnMirror.text = "镜像:${mirrorLevelName(mirrorPrefLevel())}"
    }

    /** 按当前镜像档位决定是否开镜像、开多清;连接上/续连上时调用。 */
    private fun applyMirror() {
        if (!connected) return
        val lvl = mirrorPrefLevel()
        if (lvl == 0) {
            client.stopView()   // 关:不开镜像,给摄像头/麦克风让带宽
        } else {
            val (w, fps, q) = mirrorProfile(lvl)
            client.setMirrorProfile(w, fps, q)
            client.startView()
        }
    }

    /** 点镜像按钮:循环 关→流畅→均衡→高清→关,选完立刻生效并记住。 */
    private fun cycleMirror() {
        val next = (mirrorPrefLevel() + 1) % 4
        prefs.edit().putInt("mirror_level", next).apply()
        refreshMirrorButton()
        if (connected) applyMirror()
        toast(
            "镜像:" + mirrorLevelName(next) +
                (if (next == 0) "(已关,想再开就再点一下)" else "(已套用并记住)")
        )
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
        try { client.listener = null } catch (_: Exception) {}
        try { client.disconnect() } catch (_: Exception) {}
        try { stopKeepAlive() } catch (_: Exception) {}
        cameraOn = false
        micOn = false
        saveWants()
    }

    override fun onDestroy() {
        KeepAliveService.hostActivityLive = false
        mediaStreamer.stop()
        client.listener = null
        client.disconnect()
        stopKeepAlive()
        super.onDestroy()
    }

    companion object {
        private const val REQ_CAMERA = 100
        private const val REQ_SCAN = 101
        private const val REQ_CAM = 102
        private const val REQ_MIC = 103
        private const val AUTO_HIDE_MS = 5000L
    }
}
