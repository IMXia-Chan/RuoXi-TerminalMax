package com.example.touchpad

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 看板模式：一块**盖满整屏**的仪表盘。
 *
 * ## 他要的是什么
 *
 * 2026-11-07 的原话：「双指往上（页面往下）的看板模式，就是如果后面接了啥外设，
 * 肯定要有看板模式对吧，比如大疆无人机的操控面板啊，比如小米音响啥的，肯定得有界面对吧？」
 *
 * 所以这一层从第一天起就不是「手机状态页」，而是**一块能往里挂面板的板子**：
 * 一块面板 = 一个数据源（[Panel]），本机只是其中最现成的那一块。
 * 以后接无人机，就是再加一块 `Panel`，外壳、网格、刷新、手势一概不用动。
 *
 * ## 两条边界
 *
 * 1. **只读**。这一版里没有任何会动外设的控件 —— 操控面板要先有外设协议、要有急停，
 *    那是另一个工期的活。先把「看得见」做好。
 * 2. **全屏覆盖层，不是新的 Activity**。他要的是「页面往下」的手感：双指下滑把它拉出来，
 *    双指上划推回去。跳 Activity 就没有这个连续感了。
 *
 * ## 退出
 *
 * 双指下滑，或返回键（由 [com.example.touchpad.ConMarnActivity.onBackPressed] 先问这一层）。
 */
internal class DashboardLayer(
    private val ctx: Context,
    /** 关掉自己。由宿主 Activity 负责把这一层从根视图上摘下来。 */
    private val onClose: () -> Unit,
) : FrameLayout(ctx) {

    private val main = Handler(Looper.getMainLooper())

    private lateinit var clock: TextView
    private val values = LinkedHashMap<String, TextView>()

    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            paint()
            main.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        setBackgroundColor(0xFF07090B.toInt())
        setPadding(dp(14), dp(10), dp(14), dp(8))

        // ── 顶栏：标题 + 时钟 ──────────────────────────────────────────
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(fixedText("看板", 13f, 0xFFFFFFFF.toInt(), bold = true),
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        clock = fixedText("", 11f, 0x99FFFFFF.toInt())
        top.addView(clock, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        // ★ 一颗看得见的关闭键（他 2026-11-07：「然后我进了很难出」）。
        //   手势划不中的时候，屏幕上必须还有一条明路 —— 不能逼人去按返回键。
        //   触摸区按 44×36dp 给，比那个「×」本身大一圈。
        val close = TextView(ctx).apply {
            text = "×"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, dpF(15f))
            setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER
            setOnClickListener { onClose() }
        }
        top.addView(close, LinearLayout.LayoutParams(dp(44), dp(36)).apply { leftMargin = dp(8) })

        addView(top, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP })

        // ── 面板 ──────────────────────────────────────────────────────
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        grid.addView(localPanel(), rowParams())
        grid.addView(spacer(dp(10)))
        grid.addView(devicesPanel(), rowParams())

        addView(grid, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
            topMargin = dp(34)
        })

        // 一行操作提示。★ 只写操作，不解释（说明文字一律进设置）。
        addView(fixedText("双指上划退出", 9.5f, 0x66FFFFFF.toInt()), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(10)
        })
    }

    // ── 面板：本机 ──────────────────────────────────────────────────────

    /**
     * 本机这一块。**只报手机自己知道的事**，不猜：
     * 电量、电池温度、当前网络、电脑那头连没连、本地模型跑没跑。
     */
    private fun localPanel(): View {
        val p = panel("本机")
        row(p, "电量")
        row(p, "电池温度")
        row(p, "网络")
        row(p, "电脑")
        row(p, "本地模型")
        return p
    }

    /**
     * 外设那一块。现在还没有任何外设协议，所以只有一行状态 ——
     * ★ 不假装有内容：一块写着「已连接」却没有数据的屏，比空着一块更坏。
     */
    private fun devicesPanel(): View {
        val p = panel("外设")
        p.addView(fixedText("尚未接入", 11f, 0x8AFFFFFF.toInt()), LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        return p
    }

    // ── 画数据 ──────────────────────────────────────────────────────────

    private fun paint() {
        clock.text = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())
        val b = battery()
        set("电量", if (b.charging) "${b.percent}%　充电中" else "${b.percent}%")
        set("电池温度", if (b.temp > 0f) String.format(Locale.CHINA, "%.1f ℃", b.temp) else "—")
        set("网络", network())
        set("电脑", if (linked()) "已连接" else "未连接")
        set("本地模型", if (modelOn()) "运行中" else "未启动")
    }

    private fun set(key: String, v: String) {
        values[key]?.text = v
    }

    private class Bat(val percent: Int, val temp: Float, val charging: Boolean)

    /**
     * 电量那一组。
     *
     * ★ 用**粘性广播直接取**，不注册接收器：注册了就要记得注销，而这一层是随手开合的，
     *   一处漏注销就是一个长期挂着的监听（和 2026-10 那次「大屏 WebView 不 dispose」同一个病）。
     */
    private fun battery(): Bat {
        val i = try {
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        } ?: return Bat(0, 0f, false)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        // 万分之一度：290 表示 29.0℃，有些机型干脆不给
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) / 10f
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return Bat(pct.coerceAtLeast(0), if (temp > -100f) temp else 0f, charging)
    }

    private fun network(): String = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        when {
            caps == null -> "未连接"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线"
            else -> "未连接"
        }
    } catch (_: Exception) {
        "—"
    }

    /** 电脑那头。判据和房间里那盏灯同一个（[TouchpadClient.isConnected]）。 */
    private fun linked(): Boolean = try {
        TouchpadClient.get(ctx).isConnected()
    } catch (_: Exception) {
        false
    }

    /** 本地模型。判据是进程还在不在，不是「上次有没有答上来」。 */
    private fun modelOn(): Boolean = try {
        ModelManager.get(ctx).isRunning()
    } catch (_: Exception) {
        false
    }

    // ── 零件 ────────────────────────────────────────────────────────────

    private fun panel(title: String): LinearLayout {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.room_screen)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        c.addView(fixedText(title, 11f, 0xFFFFFFFF.toInt(), bold = true),
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        c.addView(View(ctx).apply { setBackgroundColor(0x26FFFFFF) },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(5) })
        return c
    }

    /** 面板里的一行：左边名目，右边读数。读数先占位，[paint] 再填。 */
    private fun row(p: LinearLayout, key: String) {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        r.addView(fixedText(key, 10f, 0x8AFFFFFF.toInt()),
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val v = fixedText("—", 13f, 0xF2F2F3F5.toInt(), bold = true)
        r.addView(v, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        values[key] = v
        p.addView(r, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })
    }

    /**
     * 文字一律按 **dp** 定，不用 sp。
     *
     * ★ 这台机的系统字号是 1.6 倍。看板是**固定排版的仪表盘**，
     *   字号跟着放大会把一行读数挤到换行（房间里那两块屏 2026-11-07 就踩过这个）。
     */
    private fun fixedText(t: String, dpSize: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_PX, dpF(dpSize))
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }

    private fun spacer(h: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, h)
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    private fun dpF(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics
    )

    // ── 生命周期 ────────────────────────────────────────────────────────

    fun start() {
        running = true
        main.removeCallbacks(tick)
        paint()
        main.postDelayed(tick, REFRESH_MS)
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    // ── 双指下滑：退出 ──────────────────────────────────────────────────

    private var downY = 0f
    private var armed = false
    private var t0 = 0L

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (ev.pointerCount >= 2) {
                downY = ev.getY(0)
                armed = true
                t0 = System.currentTimeMillis()
            }
            MotionEvent.ACTION_MOVE -> if (armed && ev.pointerCount >= 2) {
                // ★ 方向反过来就是回退：进来是双指下滑，出去就是双指上划。
                //   横着跑太远不算 —— 那是别的意图，不能把看板划没了。
                // ★★ 门槛比进门那一侧低（12% 对 26%）：出门要**比进门容易**。
                //   进来的路他走一次就够了，出去的路走不掉的代价是被关在里面 ——
                //   他 2026-11-07 的原话就是「很难出」。
                val dy = ev.getY(0) - downY
                if (dy < -height * 0.12f && System.currentTimeMillis() - t0 < 1400) {
                    armed = false
                    onClose()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> armed = false
        }
        return super.dispatchTouchEvent(ev)
    }

    private companion object {
        const val REFRESH_MS = 2_000L
    }
}
