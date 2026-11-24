package com.example.touchpad

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * 右缘那条磨砂书签条 + 「中转站」呼出面板 —— **长在进程上,不长在 Activity 上**。
 *
 * ## 为什么必须单独有这个文件(这是这一版真正要修的东西)
 *
 * 用户 2026-10-04 晚的原话:
 * > 「确实有书签也确实能回到房间,但是**好像点一下就没了**」
 *
 * 这一句是**准确的观测**,根因不是书签条本身坏了,是**它住错了地方**:
 *
 * | 事实(都是 `dumpsys` 读出来的,不是猜的) | 后果 |
 * |---|---|
 * | `ConMarnActivity` 是 `singleTask`,**而且是任务根**(桌面别名 `.ConMarnLauncher` 指向它) | 进她的房间 = 系统清掉它**上面**的一切 |
 * | 那一刻活着的 `ActivityRecord` 只剩 `.ConMarnLauncher` 一个 | `MainActivity` 被销毁 |
 * | 书签条/面板是 `MainActivity.TransferOverlay` 拿 `wm.addView` 挂上去的 | `onDestroy` → `dismiss()` → **窗口被摘掉** |
 * | 能把它重新立起来的只有 `MainActivity.onResume` | 而它已经不会 resume 了 |
 *
 * 所以「点一下(她的房间)就没了」不是玄学,是**必然**。而只要窗口的宿主是那个会被销毁的
 * Activity,怎么修都只是把同一件事再演一遍。
 *
 * ## 修法:**把宿主从 Activity 换成进程**
 *
 * 这个类只持有 `applicationContext`(绝不持有 Activity —— 持有了就是泄漏,而且窗口会比
 * Activity 活得久)。谁调 `show()` 都行;`MainActivity` 死了它还在,重新打开 App 时
 * `show()` 是幂等的(已经有就不重建)。
 *
 * ★ 顺带修好用户的另一条抱怨:「**那个中转站要先打开 app 是什么鬼,以前都不用**」。
 *   以前不用,是因为书签条常驻;换主那版把入口挪进了她的球的面板里,而那个面板要 Activity
 *   活着才建得出来。现在入口回来了,而且**不再依赖任何 Activity**。
 *
 * ## 抽屉(文件主面板)为什么不一起搬
 *
 * 抽屉是**文件传输**的主面板,它的数据和回调(`client`、`onPcListing`、上传进度…)
 * 全都长在 `MainActivity` 上。把它也搬出来 = 顺手重写整条传输链,**和修这个 bug 无关**。
 * 所以边界划在这儿:书签条和呼出面板搬走,**抽屉留在 `MainActivity`**(它本来就是
 * 「你正在用 App」时才需要的东西)。呼出面板里那张「文件传输」卡片改成**把 App 拉起来
 * 并打开抽屉** —— 一步没多,只是这一步现在由系统来完成。
 */
class StationOverlay(private val app: Context) {

    private val wm: WindowManager =
        app.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var handleView: View? = null      // 右侧磨砂书签条(常驻,平时唯一的可见窗口)
    private var hubView: View? = null         // 呼出面板(整屏透明层,右 70% 白玻璃)
    private var hubOpen = false
    private var displayListener: DisplayManager.DisplayListener? = null

    // 书签条上的手势状态
    private var dragging = false
    private var swipeIn = false               // 从右缘向左滑,视为「呼出」
    private var downRawX = 0f
    private var downRawY = 0f
    private val dragSlop = 14

    // 呼出面板的形状权重(横屏/竖屏两套,见 applyHubShape)
    private var hubGapTop: View? = null
    private var hubMid: View? = null
    private var hubGapLeft: View? = null
    private var hubGapBot: View? = null

    // 同一扇窗里的**五层**(见 overlay_hub.xml 的文件头)。层级编号只是给
    // [showHubLevel] 用的号码牌,和 `R.layout` 里的顺序无关。
    private var hubMain: View? = null       // ① 终端
    private var hubHands: View? = null      // ② AI 助手(选外设)
    private var hubHandsList: LinearLayout? = null
    private var hubPc: View? = null         // ③ 电脑
    private var hubPick: View? = null       // ④ 哪一台电脑
    private var hubPickList: LinearLayout? = null
    private var hubConn: View? = null       // ⑤ 扫描 / 断开连接

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 把书签条立起来。**幂等** —— 已经有就只重新贴一下位置。
     *
     * @return 真的在屏幕上(false = 没悬浮窗权限 / addView 被拒)
     */
    fun show(): Boolean {
        if (!canOverlay()) {
            log("show() 被拒:没有「显示在其他应用上层」权限")
            return false
        }
        if (handleView == null && !createHandle()) return false
        watchDisplay()
        repositionAll()
        // ★ 顺手把它显出来。**不是多余的**:她的房间在前台时会把书签条 GONE 掉
        //   (见 setHandleVisible),而「房间退场 → 书签条回来」这条路上
        //   `onResume` 的顺序不保证 —— 不在这儿兜一句,表现就是
        //   「出了房间,右缘那条子还在隐身」,也就是用户报的那个症状的变体。
        handleView?.visibility = View.VISIBLE
        return true
    }

    /**
     * 只切书签条的显隐,**不销毁窗口**。
     *
     * ★ 为什么不让她的房间直接调 [hide]:`hide()` 会 `removeView`,而窗口反复
     *   add/remove 是这台机器上实测会闪一下的(抽屉那边留过同样的注释)。
     *   书签条小,但「进出房间」是个高频动作,而且它一闪就正好发生在
     *   全屏 3D 场景的首帧 —— 看着像她加载失败。
     *
     * ★ 为什么房间里要藏它:她的房间是**全屏横屏**的 3D 场景,右缘正中那块
     *   12dp 宽的条子会**吃掉 WebGL 那一格的手势** —— 而这一版正在做的恰恰是
     *   「让房间里的物件可以被点到」。一个悬浮条挡掉一块点击区,正好是这个项目
     *   最怕的那种**静默**失败(物件点不动,但没有任何报错)。
     */
    fun setHandleVisible(visible: Boolean) {
        // ★ 这一行必须落盘:书签条的隐显**没有别的观测口径** —— 它错了的样子是
        //   「屏幕上没有那条子」,而那是用户唯一能看见的、也是最难从日志里倒推的状态。
        log("setHandleVisible($visible) 现存=${handleView != null}")
        if (visible) {
            if (!show()) return
        } else {
            handleView?.visibility = View.GONE
            // 书签条藏起来的时候面板也不该留着 —— 它本来就是从条子上拉出来的
            if (hubOpen) closeHub()
        }
    }

    /** 彻底收掉(用户明确退出时用,比如划掉任务卡)。 */
    fun hide() {
        unwatchDisplay()
        removeHub()
        removeHandle()
        hubOpen = false
    }

    // ------------------------------------------------------------------
    // 屏幕旋转跟随
    // ------------------------------------------------------------------

    private fun watchDisplay() {
        if (displayListener != null) return
        val dmgr = app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val cb = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) {}
            override fun onDisplayRemoved(id: Int) {}
            override fun onDisplayChanged(id: Int) {
                if (id == Display.DEFAULT_DISPLAY) {
                    // 回调在 Binder 线程,挪窗口要回主线程
                    Handler(Looper.getMainLooper()).post { repositionAll() }
                }
            }
        }
        displayListener = cb
        try {
            dmgr.registerDisplayListener(cb, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {
        }
    }

    private fun unwatchDisplay() {
        val cb = displayListener ?: return
        displayListener = null
        try {
            (app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.unregisterDisplayListener(cb)
        } catch (_: Exception) {
        }
    }

    /** 屏幕尺寸/方向变了:书签条贴回新屏右缘,呼出面板挪到新屏尺寸。 */
    fun onScreenChanged() = repositionAll()

    private fun repositionAll() {
        val dm = realDm()
        handleView?.let { h ->
            val lp = lpOf(h)
            if (lp != null) {
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
                lp.x = 0
                lp.y = 0
                lp.width = screenW()
                lp.height = screenH()
                try { wm.updateViewLayout(hv, lp) } catch (_: Exception) {}
            }
            // 收起态的面板要推去「屏宽」之外,不然会露在屏幕边上
            hv.translationX = if (hubOpen) 0f else screenW().toFloat()
        }
        applyHubShape()
    }

    // ------------------------------------------------------------------
    // 书签条
    // ------------------------------------------------------------------

    private fun handleW() = dp(12)
    private fun handleH() = dp(80)

    /**
     * 当前**物理屏**的真实尺寸,用于贴边和面板定位。
     *
     * ★★ 只能用 `Resources.getSystem()`,**绝不能**用 `app.resources.displayMetrics`。
     * 后者跟随的是**本 App 的配置方向**,而她的房间锁横屏 —— 从房间退出来的那一瞬间它还是
     * (2374×1080),于是书签条会按横屏的宽度贴到一个 1080 宽的竖屏上,表现是「铺满全屏」。
     * **同一个坑踩过两次了**(见记忆 `overlay-screen-size-must-use-system-resources`)。
     */
    private fun realDm(): android.util.DisplayMetrics {
        val dm = android.util.DisplayMetrics()
        try {
            val sys = android.content.res.Resources.getSystem().displayMetrics
            if (sys.widthPixels > 0 && sys.heightPixels > 0) {
                dm.setTo(sys)
                return dm
            }
        } catch (_: Exception) {
        }
        try {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
        } catch (_: Exception) {}
        if (dm.widthPixels <= 0 || dm.heightPixels <= 0) {
            try { dm.setTo(app.resources.displayMetrics) } catch (_: Exception) {}
        }
        return dm
    }

    private fun screenW(): Int = realDm().widthPixels
    private fun screenH(): Int = realDm().heightPixels
    private fun dp(v: Int): Int =
        (v * (try { app.resources.displayMetrics.density } catch (_: Exception) { 1f }) + 0.5f).toInt()

    private fun canOverlay(): Boolean =
        try { Settings.canDrawOverlays(app) } catch (_: Exception) { false }

    private fun lpOf(v: View): WindowManager.LayoutParams? =
        v.layoutParams as? WindowManager.LayoutParams

    /** 建立右侧书签条窗口。常驻,固定贴屏幕右缘并竖直居中。 */
    private fun createHandle(): Boolean {
        if (handleView != null) return true
        val dm = realDm()
        val w = handleW()
        val h = handleH()
        val x = dm.widthPixels - w - dp(2)
        val y = dm.heightPixels / 2 - h / 2
        log("createHandle 书签条 右侧 x=$x y=$y 屏=${dm.widthPixels}x${dm.heightPixels}")
        val v = try {
            LayoutInflater.from(app).inflate(R.layout.overlay_bookmark, null)
        } catch (e: Exception) {
            log("书签条 inflate 失败:${e.javaClass.simpleName} ${e.message}")
            return false
        }
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
        return try {
            wm.addView(v, p)
            handleView = v
            v.setOnTouchListener { _, e -> onHandleTouch(e) }
            true
        } catch (e: Exception) {
            handleView = null
            log("书签条 addView 失败:${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    private fun removeHandle() {
        try { handleView?.let { wm.removeView(it) } } catch (_: Exception) {}
        handleView = null
    }

    private fun onHandleTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX
                downRawY = e.rawY
                dragging = false
                swipeIn = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                if (!dragging &&
                    (abs(dx) > dragSlop || abs(e.rawY - downRawY) > dragSlop)) {
                    dragging = true
                }
                if (dragging && dx < -dragSlop) swipeIn = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cancel = e.actionMasked == MotionEvent.ACTION_CANCEL
                // 点一下书签条,或从右缘向左滑 → 呼出「中转站」面板
                if (!cancel && (!dragging || swipeIn)) openHub()
                dragging = false
                swipeIn = false
            }
        }
        return true
    }

    // ------------------------------------------------------------------
    // 呼出面板(中转站首页)
    // ------------------------------------------------------------------

    private fun ensureHubWindow(): Boolean {
        if (hubView != null) return true
        val v = try {
            LayoutInflater.from(app).inflate(R.layout.overlay_hub, null)
        } catch (e: Exception) {
            log("呼出面板 inflate 失败:${e.javaClass.simpleName} ${e.message}")
            return false
        }
        bindHub(v)
        val p = WindowManager.LayoutParams(
            screenW(), screenH(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        return try {
            wm.addView(v, p)
            hubView = v
            // 隐藏态:推到屏幕右缘之外 + 透明 + 不拦截
            v.alpha = 0f
            v.translationX = screenW().toFloat()
            true
        } catch (e: Exception) {
            hubView = null
            log("呼出面板 addView 失败:${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    /** 点书签条 / 向左滑 → 面板带「过冲回弹」滑入,弹出给 50ms 短震。 */
    fun openHub() {
        if (hubOpen) return
        if (!show()) return
        if (!ensureHubWindow()) return
        val v = hubView ?: return
        val lp = lpOf(v)
        if (lp != null) {
            lp.x = 0
            lp.y = 0
            lp.width = screenW()
            lp.height = screenH()
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE   // 清掉 NOT_TOUCHABLE
            try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
        }
        v.visibility = View.VISIBLE
        v.alpha = 1f
        v.translationX = screenW().toFloat()
        applyHubShape()
        refreshHerFloatState()
        // ★ 每次都从**一级**开:二级是「刚才在挑哪只外设」的状态,不该跨次记住 ——
        //   记着的话,下一次拉开书签条看到的是一页设备,而他以为自己在看中转站。
        showHubMain()
        hubOpen = true
        v.animate().translationX(0f).setDuration(260)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.35f))
            .start()
        buzz(50)
    }

    /** 收起呼出面板(回落成书签条)。收起时给 30ms 轻震;onDone 在动画结束后执行。 */
    fun closeHub(onDone: Runnable? = null) {
        if (!hubOpen) {
            onDone?.run()
            return
        }
        hubOpen = false
        val v = hubView ?: run { onDone?.run(); return }
        buzz(30)
        val anim = v.animate().translationX(screenW().toFloat()).alpha(0f).setDuration(180)
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

    private fun removeHub() {
        try { hubView?.let { wm.removeView(it) } } catch (_: Exception) {}
        hubView = null
    }

    private fun bindHub(v: View) {
        v.findViewById<View>(R.id.hub_scrim).setOnClickListener { closeHub() }   // 点屏幕其他地方收起
        v.findViewById<View>(R.id.hub_close).setOnClickListener { closeHub() }   // 右上角箭头
        v.findViewById<View>(R.id.hub_card_file).setOnClickListener { goTransfer() }
        v.findViewById<View>(R.id.hub_card_quick).setOnClickListener { goRoom() }
        // ★ 2026-10-04 晚从主界面抽屉搬来的两件事(用户原话见 overlay_hub.xml 那段)。
        v.findViewById<View>(R.id.hub_card_scan).setOnClickListener { goScan() }
        v.findViewById<View>(R.id.hub_card_disconnect).setOnClickListener { goDisconnect() }
        // ★ 2026-10-04 晚改的:这张卡**不再直奔电脑**,它开的是二级面板(选外设)。
        //   原话:「AI 助手做成有二级界面的…要点个电脑,她才会到最初那个 AI 控制电脑的页面」
        v.findViewById<View>(R.id.hub_card_ai).setOnClickListener { showHubLevel(2) }
        v.findViewById<View>(R.id.hub_card_her_float).setOnClickListener { toggleHerFloat() }
        v.findViewById<View>(R.id.hub_hands_back).setOnClickListener { showHubLevel(1) }
        v.findViewById<View>(R.id.hub_hands_close).setOnClickListener { closeHub() }
        // ★ 2026-10-05 新增的三层。back 一律**只退一层**(不是一键回一级)——
        //   这是一条链条:「哪一台」的上一页永远是「电脑」,把它直接送回一级
        //   会让人丢了自己的位置。
        v.findViewById<View>(R.id.hub_pc_back).setOnClickListener { showHubLevel(2) }
        v.findViewById<View>(R.id.hub_pc_close).setOnClickListener { closeHub() }
        v.findViewById<View>(R.id.hub_card_pc_talk).setOnClickListener { openPcPage() }
        v.findViewById<View>(R.id.hub_card_pc_pick).setOnClickListener { showHubLevel(4) }
        v.findViewById<View>(R.id.hub_pick_back).setOnClickListener { showHubLevel(3) }
        v.findViewById<View>(R.id.hub_pick_close).setOnClickListener { closeHub() }
        v.findViewById<View>(R.id.hub_card_pick_conn).setOnClickListener { showHubLevel(5) }
        v.findViewById<View>(R.id.hub_conn_back).setOnClickListener { showHubLevel(4) }
        v.findViewById<View>(R.id.hub_conn_close).setOnClickListener { closeHub() }
        hubGapTop = v.findViewById(R.id.hub_gap_top)
        hubMid = v.findViewById(R.id.hub_mid)
        hubGapLeft = v.findViewById(R.id.hub_gap_left)
        hubGapBot = v.findViewById(R.id.hub_gap_bot)
        hubMain = v.findViewById(R.id.hub_main)
        hubHands = v.findViewById(R.id.hub_hands)
        hubHandsList = v.findViewById(R.id.hub_hands_list)
        hubPc = v.findViewById(R.id.hub_pc)
        hubPick = v.findViewById(R.id.hub_pick)
        hubPickList = v.findViewById(R.id.hub_pick_list)
        hubConn = v.findViewById(R.id.hub_conn)
    }

    /** 卡片 · 文件传输:把 App 拉起来并直接打开文件主面板(抽屉的数据在 MainActivity 上)。 */
    private fun goTransfer() {
        closeHub()
        MainActivity.pendingOpenTransfer = true
        launchApp(MainActivity::class.java)
    }

    /**
     * 卡片 · **回到房间**(原来叫「快捷启动」)。
     *
     * ★ 顺序:**先把 App 拉到前台,再进她的房间**。反过来的话,从后台直接起
     *   `ConMarnActivity`,按返回会掉回桌面而不是主界面 —— 那看起来像「退出了」。
     *   ⚠️ 但现在 `ConMarnActivity` 是 `singleTask` 且是任务根,**它一起来就会清掉
     *   `MainActivity`**。所以「先拉主界面」这一步在多数情况下只是把它顶一下 ——
     *   书签条不再依赖它,这正是这个文件存在的意义。
     */
    private fun goRoom() {
        closeHub()
        launchApp(MainActivity::class.java)
        launchApp(ConMarnActivity::class.java)
    }

    /**
     * 卡片 · 扫描:把 App 拉起来,回前台后开始扫局域网。
     *
     * ★ 为什么**必须**绕这一道 `pendingScan` 回执,不能在这儿直接扫:
     *   扫描要 toast 进度、要申请「本地网络」权限、要弹配对码 —— 那三样全都长在
     *   `MainActivity` 上(权限回调更是只有 Activity 才收得到)。这里只有
     *   `applicationContext`,所以只能下个便条、把界面叫起来。
     *   便条的名字和消费点写在 `MainActivity` companion 的那段注释里。
     */
    private fun goScan() {
        closeHub()
        MainActivity.pendingScan = true
        launchApp(MainActivity::class.java)
    }

    /**
     * 卡片 · 断开连接:同上,回前台后由 `MainActivity.doDisconnect()` 办。
     *
     * ★ 不在没连接时提前拦一句 —— `doDisconnect()` 第一行就是 `if (!connected) { toast("当前未连接"); return }`,
     *   再拦一道等于把这个判断抄两遍,而抄出来的那份迟早会和真那份对不上。
     */
    private fun goDisconnect() {
        closeHub()
        MainActivity.pendingDisconnect = true
        launchApp(MainActivity::class.java)
    }

    // ------------------------------------------------------------------
    // 二级面板:AI 助手(= 她的手)
    // ------------------------------------------------------------------

    /**
     * ★★ 为什么「AI 助手」下面要有一层,而不是直接跳电脑 —— 用户 2026-10-04 晚原话:
     *
     * > 「AI 助手,应该是能**直接控制不同的外设**的,而电脑,我已经说过,它**只是一只手**,
     * >   之后做的外设,都可以通过这个 AI 助手快速打开」
     *
     * 所以这一层列的是**她的手**,不是「电脑的一个别名」。今天名单里只有电脑和这部手机自己,
     * 看起来像多此一举 —— 但**形状是对的**:以后加空调、加灯、加车,都往 `HandRegistry`
     * 里加一条,这一页自己会长,而这一页的代码一行都不用动。
     *
     * ★ 反过来的做法(在这儿写死一张「电脑 / 空调 / 灯」的清单)是这个项目已经吃过一次
     *   的亏 —— 同一张工具表在 PC、`TOOL_SCHEMA`、系统提示词里各抄了一份,加一个能力要改三处。
     *   **房间里摆什么,必须由「她能做什么」决定,不能由 UI 决定。**
     */
    private fun showHubMain() = showHubLevel(1)

    /**
     * 切到某一层。**这是这扇窗唯一的"去哪儿"入口** —— 五层全是 `match_parent` 叠在
     * 同一个 `FrameLayout` 里,所以"在哪一层"完全由这五句 `visibility` 决定。
     *
     * ★ 为什么不做成一个 `Array<View?>` + 循环:那样每加一层就多一个"记得往数组里补"
     *   的地方,而**漏补的那一层不会报错** —— 它只是永远不显示,或者永远不隐藏
     *   (叠在别人上面)。五句直白的 if 是这里最省事的写法。
     *
     * ★ 两处现填必须在**切过去之前**做完:第三层的副标题要写「用的是哪一台」,
     *   第四层那一列要按当下的连接状态重画 —— 缓存住的话,答案是打开面板那一刻的,
     *   而连上/断开随时会变。
     */
    private fun showHubLevel(n: Int) {
        if (n == 2) buildHandCards()
        if (n == 3) refreshPickCard()
        if (n == 4) buildPickList()
        hubMain?.visibility = if (n == 1) View.VISIBLE else View.GONE
        hubHands?.visibility = if (n == 2) View.VISIBLE else View.GONE
        hubPc?.visibility = if (n == 3) View.VISIBLE else View.GONE
        hubPick?.visibility = if (n == 4) View.VISIBLE else View.GONE
        hubConn?.visibility = if (n == 5) View.VISIBLE else View.GONE
    }

    /**
     * 按 [HandRegistry] 现填那一列。
     *
     * ★ **每次开都重填**(不是建一次就完):手是会来会走的(那台电脑关了、连上了),
     *   而这一页正是「她现在能控制什么」的答案 —— 缓存住的话,答案会停在打开 App 那一刻。
     */
    private fun buildHandCards() {
        val list = hubHandsList ?: return
        list.removeAllViews()
        try { HandRegistry.init(app) } catch (_: Exception) {}
        val hands = try { HandRegistry.all() } catch (_: Exception) { emptyList() }
        // ★★ **云端服务不许出现在前端。**(2026-10-05 用户点名,原话:)
        //   > 「那个 AI 助手、终端,也就是原来的中转站那个悬浮窗中的**云端服务,
        //   >  不要显示在前端**……那些**没有实体外设**的,我说了不要出现在我的 AI 助手里」
        //   [HandMath.isFixture] 返回 true = **这条有实体**(电脑 / 手机自己),false = 云端。
        //   ★ 2026-10-06 更正:原来写成 `!isFixture`,条件写反了,
        //     把实物删了只留云端——正好是用户抱怨的两个症状。
        //
        //   ★★ **只过滤「显示」,数据源一个字节都不许动。**
        //      `HandRegistry.all()` / `CloudHand.hands()` / `list_hands` 必须照旧看得见它们 ——
        //      天气 / 高德 / 上网查是她**真的在用**的能力(记忆 `ruoxi-room-objects`:
        //      「只摆实物,云端那些能力一个都不摆」说的是**摆**,不是**有**)。
        //      把过滤下沉到源头 = 顺手把她查天气的本事一起删了,而且症状是
        //      「她说没这个能力」,极难查回来。
        val shown = hands.filter { HandMath.isFixture(it) }
        if (shown.isEmpty()) {
            // 今天到不了这儿(`withSelf` 总会并进手机自己那只),留着是防以后改坏了。
            list.addView(hintRow("她一只手都没有"))
            return
        }
        // 电脑排在最前。用户那句话的重音是「要点个电脑」,而且它是今天唯一**有页面**的手 ——
        // 把能用的那个放在要滑一下才看得到的位置,是没有理由的。
        // (注册表的顺序是「手机自己在前」,那是为模型给的顺序,不是为眼睛定的。)
        shown.sortedBy { kindOrder(it.kind) }.forEach { list.addView(handCard(it)) }
    }

    private fun kindOrder(kind: String): Int = when (kind) {
        HandMath.KIND_PC -> 0
        HandMath.KIND_CLOUD -> 1
        HandMath.KIND_SELF -> 2
        else -> 3
    }

    /** 一行手卡。**用代码拼而不是 inflate**:里面每一项都随这只手变(图标/标题/副标题),写个布局也要在每个字段上再 findViewById 一遍。 */
    private fun handCard(h: Hand): View {
        val row = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = app.getDrawable(R.drawable.ov_card_bg)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            setOnClickListener { openHand(h) }
        }
        val icon = ImageView(app).apply {
            setImageResource(iconOf(h.kind))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = app.getDrawable(R.drawable.hub_icon_circle)
            // tintMode="src_in" 的等价物:布局里那几张卡走的是 android:tint,这里是代码
            setColorFilter(tintOf(h.kind), PorterDuff.Mode.SRC_IN)
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))

        val col = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(app).apply {
            text = HandMath.kindLabel(h.kind)
            setTextColor(0xE4263238.toInt())
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        // ★ 没话可说就**不加这一行**(不是加一个空 TextView)——空的那一行照样占掉
        //   一个 `WRAP_CONTENT` 的位,只是矮一点,而且会把这个两行的卡片变成
        //   「看起来本该有两行、结果第二行是空的」。
        subtitleOf(h)?.let { sub ->
            col.addView(TextView(app).apply {
                text = sub
                setTextColor(0x8A263238.toInt())
                textSize = 12f
            })
        }
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = dp(13) })

        row.addView(ImageView(app).apply {
            setImageResource(R.drawable.ic_chev)
            setColorFilter(0x66000000, PorterDuff.Mode.SRC_IN)
        }, LinearLayout.LayoutParams(dp(16), dp(16)))
        return row
    }

    private fun hintRow(msg: String): View = TextView(app).apply {
        text = msg
        setTextColor(0x8A263238.toInt())
        textSize = 13f
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun iconOf(kind: String): Int = when (kind) {
        HandMath.KIND_PC -> R.drawable.ic_hand_pc
        HandMath.KIND_SELF -> R.drawable.ic_hand_phone
        else -> R.drawable.ic_ai
    }

    private fun tintOf(kind: String): Int = when (kind) {
        HandMath.KIND_PC -> 0xFF5B8DEF.toInt()
        HandMath.KIND_SELF -> 0xFFE05B8E.toInt()
        else -> 0xFF8E5BEF.toInt()
    }

    /**
     * 副标题:**能看出这只手现在是什么状态**的那一行。没有可说的就返回 null(整行不画)。
     *
     * ★ 电脑那一只额外报「连上没连上」。理由很实际:点进去是「跟她说一句话,让她去动电脑」,
     *   而电脑没连的时候那句会失败 —— 与其让人点进去才发现,不如在这一行就说。
     *   (注意口径:那是**连着没连着**,不是「能不能用」—— 她的脑子在手机上,
     *   电脑不在她也照样听得见你说的话,只是没法动手。)
     *
     * ★★ 2026-10-04 晚删掉了「**会 N 件事**」那一截。用户原话:
     *   > 「啥会 12 件事,会 10 件事,这是什么东西?能不能删掉,感觉影响美观」
     *   那行字是 `h.tools.count { it.name !in h.hidden }` —— 这只手自述能做几件事的**计数**。
     *   它不准(同一件事在电脑和手机上各算一件)、跟这一层要回答的问题也无关:
     *   **这一层要回答的是「哪个是电脑」,不是「她有几把刷子」。**
     *   一个用户看不懂、又不帮他做选择的数字,放在卡片上就是噪声 —— 它把两行清爽的
     *   设备名撑成一行半,而这一层总共就那么点地方。
     *
     *   ⚠️ **删的是显示,不是数据。** `Hand.tools` 一个字没动 —— `list_hands` 那个
     *   通用入口(以及 4B 的选工具)全靠它。这里只是不再把它数给用户看。
     *
     *   ★ 顺带:手机自己那一只因此**没有任何副标题**(它的 name「这部手机」已经含在
     *   kindLabel「这部手机自己」里),那一行就只剩一个标题,正好是想要的样子。
     */
    private fun subtitleOf(h: Hand): String? {
        val parts = ArrayList<String>(2)
        if (h.kind == HandMath.KIND_PC) {
            val on = try { TouchpadClient.get(app).isConnected() } catch (_: Exception) { false }
            parts.add(if (on) "已连上" else "没连上")
        }
        if (h.name.isNotBlank() && !HandMath.kindLabel(h.kind).contains(h.name)) parts.add(h.name)
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    /**
     * 点一只手的去处。
     *
     * ★ 今天**只有电脑有页面** —— 所以只有它跳转,别的手老老实实说「还没有页面」。
     *   为什么不说成「她还控制不了」:那是假话,手机上那只手(SelfHand)今天就能设闹钟、
     *   开应用。差别只是**没有一个给它做的界面** —— 而这两件事必须分开说,
     *   否则以后接上外设时会顺着这句错话去改错地方。
     *
     * ★★ 2026-10-05:电脑那只手**不再一步跳到命令输入框**,改成先进第三层「电脑」。
     *   用户点名要的五级里,「电脑」和「哪一台电脑」是两页 —— 前者是"她能对它做什么",
     *   后者是"说的是哪一台"。挤在一起的时候,点进去直接弹输入框,
     *   而"哪一台"根本没被回答过。
     */
    private fun openHand(h: Hand) {
        if (h.kind == HandMath.KIND_PC) showHubLevel(3) else
            toast("「${HandMath.kindLabel(h.kind)}」还没有自己的页面 —— 在房间里直接跟她说就行")
    }

    // ------------------------------------------------------------------
    // 三级~五级:电脑 / 哪一台电脑 / 扫描·断开连接
    // ------------------------------------------------------------------

    /**
     * App 记着的那台电脑(`touchpad` 首选项里的 `"ip"`)。
     *
     * ★ 口径必须和 [MainActivity.autoConnectLast] 一致:那里存的是
     *   `if (port == 9527) ip else "$ip:$port"` —— 也就是**端口是默认的就不写**。
     *   所以这儿拿到的东西**可能带端口也可能不带**,原样显示就好,别自作聪明补一个
     *   `:9527` 上去:补错了就会显示一个他没连过的地址。
     */
    private fun rememberedAddr(): String? = try {
        app.getSharedPreferences("touchpad", android.content.Context.MODE_PRIVATE)
            .getString("ip", null)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /** 第三层那张卡的副标题:**一句话回答「用的是哪一台」**。 */
    private fun machineLine(): String {
        val addr = rememberedAddr() ?: return "还没连过 —— 进去看看"
        val on = try { TouchpadClient.get(app).isConnected() } catch (_: Exception) { false }
        return "$addr · ${if (on) "已连上" else "没连上"}"
    }

    private fun refreshPickCard() {
        hubPc?.findViewById<TextView>(R.id.hub_pc_pick_sub)?.text = machineLine()
    }

    /**
     * 第四层那一列。**每次进来重画**(同 [buildHandCards] 的理由:连上/断开随时会变)。
     *
     * ★★ 这一页**故意不给「换一台」的按钮**,只回答"是哪一台"。
     *   换一台 = 连一台,而连接要跑权限回调、要弹配对码、要复位媒体流 ——
     *   那三样全在 `MainActivity` 上(见 [goScan] 那段注释)。
     *   在这一页画一个点了没反应的「选它」,比不给按钮糟得多:那正是这个项目
     *   反复在防的「会撒谎的手」。真要换台,走下一层。
     *
     * ★ 底下的「扫描 / 断开连接」那张卡是**必须的** —— 它是这一页唯一的出路,
     *   少了它这一页就是个死胡同(而五级链条里没有返回键,只有左上角那个箭头)。
     */
    private fun buildPickList() {
        val list = hubPickList ?: return
        list.removeAllViews()

        // ★★ 2026-10-05 用户原话:
        //   「终端的哪台机器那能滑动的位置太小了,看看哪里能把字删了腾出点空位置」
        //
        //   原来每组是**两行**:一个 12sp 的小标题 + 一张两行卡片。而每组最多
        //   一行内容 —— 标题比内容占的地方还大。现在小标题并进卡片的**标题行**里:
        //   卡片本来就是「一行粗标题 + 一行小字」,把组名放上面、答案放下面,
        //   信息一个字没少,每组的纵向空间**砍掉一半**(单组省约 36dp)。
        //
        // ⚠️ 别再拆回 `sectionLabel` + `infoRow` 两件 —— 那正是被删掉的那版。
        //   `sectionLabel` 本身还留着(别处照用),但**这里不许再用**。
        val addr = rememberedAddr()
        val on = try { TouchpadClient.get(app).isConnected() } catch (_: Exception) { false }
        if (addr == null) {
            list.addView(hintRow("App 里还没记着哪一台 —— 下一层扫一下就有了"))
        } else {
            list.addView(infoRow("上次连的那一台", "$addr · ${if (on) "已连上" else "没连上"}"))
        }

        // 她"认识"的电脑(手注册表里 KIND_PC 的那一只)。名字是**主机名**,和上面的
        // 地址是两回事 —— 两个都画,「哪一台」这个问题才算答全了。
        //
        // ★ 电脑没连过 / 没问过它会什么的时候注册表里是空的,那时这一节整块不画
        //   (不是画一行"暂无")。
        try { HandRegistry.init(app) } catch (_: Exception) {}
        val pc = try { HandRegistry.pcHand() } catch (_: Exception) { null }
        val name = pc?.name?.trim().orEmpty()
        if (name.isNotEmpty() && name != addr) {
            list.addView(infoRow("她记得的电脑", "$name · 她跟这台说过话").apply {
                // 两张卡之间那点缝还是要留的,不然它们会粘成一块,看起来像一张卡
                // —— 而这一页总共就两行,看错一行就答错了「哪一台」。
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            })
        }
    }

    /** 一节的小标题(和一级那几张「常用功能」同款)。 */
    private fun sectionLabel(t: String): View = TextView(app).apply {
        text = t
        setTextColor(0xFF263238.toInt())
        textSize = 12f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(12), 0, dp(8))
    }

    /**
     * 一行**只读**信息(没有 chevron、不可点)。
     *
     * ★ 和 [handCard] 长得很像但**故意不是同一个**:手卡点得动,这一行点不动。
     *   给它画上那个箭头,就是在对它说"点我" —— 见 [buildPickList] 那段。
     */
    private fun infoRow(title: String, sub: String): View {
        val col = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            background = app.getDrawable(R.drawable.ov_card_bg)
            // ★ 2026-10-05:12dp → 10dp。这一页的问题就是「能滑的地方太小」,
            //   而卡片内边距是**唯一能省下来又不删字**的那一点 —— 一页两张卡,
            //   上下各省 2dp 就有 8dp。不多,但它不为任何东西买单。
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        col.addView(TextView(app).apply {
            text = title
            setTextColor(0xE4263238.toInt())
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        col.addView(TextView(app).apply {
            text = sub
            setTextColor(0x8A263238.toInt())
            textSize = 12f
        })
        return col
    }

    /** 卡片 · 电脑:回 App 并弹出 AI 命令输入框(就是那个「AI 控制电脑」的老页面)。 */
    private fun openPcPage() {
        closeHub()
        MainActivity.pendingOpenAi = true
        launchApp(MainActivity::class.java)
    }

    /**
     * 卡片 · 她的悬浮窗:开关型 —— 开了她就在屏幕边上待着(圆钮,点开是迷你面板)。
     * 权限没给的话顺手把她送去授权页,别只弹一句「没权限」让人自己找。
     */
    private fun toggleHerFloat() {
        val on = !ConMarnBubble.isEnabled(app)
        if (on && !canOverlay()) {
            toast("先给「显示在其他应用上层」权限,她才能待在你屏幕边上")
            try {
                app.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
            closeHub()
            return
        }
        ConMarnBubble.setEnabled(app, on)
        toast(if (on) "她出来了,在屏幕边上(点她展开)" else "她回去了")
        closeHub()
    }

    /** 面板每次拉开都把开关状态对齐一下(别显示上一次的旧值)。 */
    private fun refreshHerFloatState() {
        hubView?.findViewById<TextView>(R.id.hub_her_float_state)?.text =
            if (ConMarnBubble.isEnabled(app)) "开" else "关"
    }

    /**
     * 从**应用 Context** 拉一个界面起来。
     *
     * ★ `FLAG_ACTIVITY_NEW_TASK` 不是可选项:应用 Context 没有自己的任务栈,
     *   不带这个标志 `startActivity` 会直接抛 `AndroidRuntimeException`。
     *   `REORDER_TO_FRONT` 让「已经在跑就切前台」成立 —— 不给的话从书签条点进来
     *   会**又开一个** MainActivity(她的房间有 `singleTask` 兜着,主界面没有)。
     */
    private fun launchApp(cls: Class<*>) {
        try {
            app.startActivity(
                Intent(app, cls)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (e: Exception) {
            log("拉不起来 ${cls.simpleName}:${e.javaClass.simpleName} ${e.message}")
        }
    }

    // ---- 形状自适应(和 MainActivity 那套同一份权重) ----

    private fun portraitNow(): Boolean {
        val dm = realDm()
        return dm.heightPixels > dm.widthPixels
    }

    private fun setWeight(v: View?, w: Float) {
        val lp = v?.layoutParams as? LinearLayout.LayoutParams ?: return
        lp.weight = w
        try { v.requestLayout() } catch (_: Exception) {}
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

    // ---- 小工具 ----

    /** 短促震动:面板弹出 50ms / 收起 30ms,模拟「弹出/归位」的物理感。 */
    private fun buzz(ms: Long) {
        try {
            val vib = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(ms)
            }
        } catch (_: Exception) {
        }
    }

    private fun toast(msg: String) {
        try { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
    }

    /**
     * ★ 走 `ModelManager.trace`(落 `model.log`)而**不是**只写 logcat。
     *
     * 这不是讲究:这台机器上 logcat 抓不到本 App 的日志(实测),而书签条的消失
     * **正是因为没有持久日志才难查** —— logcat 一转,证据就没了。悬浮窗的生老病死
     * 必须落在文件里。
     */
    private fun log(msg: String) {
        Log.i(TAG, msg)
        try { ModelManager.get(app).trace("[station] $msg") } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "ConMarnStation"
    }
}

/**
 * 进程级的那一个中转站。
 *
 * ★ **必须是进程级的**,这就是整个修复的支点:窗口的宿主从「一个会被销毁的 Activity」
 *   换成「进程」。`MainActivity` 进她的房间时被系统清掉,**这里一行都不会动**。
 *
 * ★ 拿的时候用 [get] 而不是 [peek](这里没有 peek,是故意的):中转站是**可以随时建**的
 *   纯 UI 对象,没有「建出来会启动什么东西」的副作用,所以不该存在一个「只取不建」
 *   的入口 —— 那正是 `ProactiveGreeting` 那边踩过的坑(`peek()` 恒为 null,
 *   她永远不开口且**不报错**)。
 */
object StationHolder {
    @Volatile
    private var inst: StationOverlay? = null

    fun get(app: Context): StationOverlay =
        inst ?: synchronized(this) {
            inst ?: StationOverlay(app.applicationContext).also { inst = it }
        }

    /** 立起书签条(幂等)。没悬浮窗权限时返回 false,不抛。 */
    fun show(app: Context): Boolean = get(app).show()

    /** 收掉书签条与面板(用户明确退出时用)。 */
    fun hide() {
        val s = inst ?: return
        s.hide()
    }

    /** 打开「中转站」面板(她的球的面板里那个按钮落到这儿)。 */
    fun openHub(app: Context) = get(app).openHub()

    /**
     * 她的房间在前台 / 退到后台时切书签条显隐。
     *
     * ★ **`visible=true` 会把它建出来,`visible=false` 只在已存在时动它** ——
     *   这两侧不对称是故意的:
     *   · 「藏起来」不该顺手造一个窗口(那正是「藏了个东西结果屏幕上多出一层」);
     *   · 「显出来」要是因为「从来没建过」而什么都不做,冷启动直接进她房间的那条路
     *     就会在**出了房间之后**右缘空空 —— 那正是用户报的那个症状的另一个入口。
     */
    fun setHandleVisible(app: Context, visible: Boolean) {
        if (visible) get(app).setHandleVisible(true) else inst?.setHandleVisible(false)
    }

    /** 屏幕旋转 / 尺寸变了。 */
    fun onScreenChanged() = inst?.onScreenChanged()
}
