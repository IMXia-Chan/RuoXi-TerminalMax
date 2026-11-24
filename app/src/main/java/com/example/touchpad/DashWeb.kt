package com.example.touchpad

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * 调度大屏：`assets/dash/index.html`。
 *
 * ## 为什么是大屏是网页，不是原生 View
 *
 * 改一次要重新打包、安装、重启的是原生 View；这一版是 HTML/CSS/JS，能在电脑上用浏览器
 * 直接跑（[tools/dash-preview.html]、判据 [tools/dash-test.html]）—— 排版、配色、节奏
 * 在电脑上调好，再塞进 APK。
 *
 * ★ 2026-11-05 起**只有这一版**：原来那张原生星形图（AgentFlowView / FlowMath）已经删掉。
 *   两版并存意味着两份判定、两处会走样的样子，而他说的是「结构不好」—— 要改的是结构，
 *   不是把同一个结构再画一遍。
 *
 * ## 它只画**真事件**
 *
 * 事件只来自 [AgentBus]，判据全在 `dash-core.js`。这一版**不新增任何状态**，
 * 也没有待机动画：没有事件，屏上就是空的。
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
internal class DashWeb(
    ctx: Context,
    /**
     * 只画**哪一类**任务号的事件（[AgentBus] 的任务号前缀）。
     *
     * ★ 默认 `t` = 她的账。超级工作台传 `w` —— 两边共用同一张表、同一个文件，
     *   但谁画谁由这个前缀分开：她的房间不该出现工作台里的活，反过来也一样。
     */
    private val taskPrefix: String = "t",
    /** 顶上那行大标题。空 = 用页面自带的那句。 */
    private val title: String = "",
    /**
     * 空屏上那一句。空 = 用页面自带的那句。
     *
     * ★ 两处用同一个页面，但「还没有事件」在两边不是一件事：她那边是没有话说，
     *   工作台那边是没有说要做什么。同一句话贴两处，等于有一处必然是错的。
     */
    private val empty: String = "",
    /** 点开某一行时回调那条事件的编号（Android 侧去 [StepStore] 取全文）。 */
    private val onStep: ((Int) -> Unit)? = null,
    /**
     * 任务条上的三个动作（只有工作台会用到）。
     *
     * ★ 分开成三个回调而不是一个 `(动作, 任务号)`：这三个的**参数形状**本来就不同
     *   （新开一件没有任务号），合成一个反而要在两边各写一遍判空。
     */
    private val onSwitchJob: ((String) -> Unit)? = null,
    private val onJobMenu: ((String) -> Unit)? = null,
    private val onNewJob: (() -> Unit)? = null,
) : WebView(ctx) {

    private var ready = false
    private var pending: String? = null

    /**
     * 「重画一次」的请求，**排在 UI 线程上**。
     *
     * ★ 同一次派活会连着来好几条事件（认领、每一步、回传），一条一画太浪费；
     *   所以这个 Runnable 进来先把自己还没跑的那几份撤掉。撤掉的只是**重复**，
     *   不是最后那一次 —— 屏上一定会画到最新。
     */
    private val onFlush = object : Runnable {
        override fun run() {
            removeCallbacks(this)
            flush()
        }
    }

    /**
     * 只看**这一件活**的账（空 = 按 [taskPrefix] 取一类）。
     *
     * ★ 前缀 `w` 在工作台能同时开几件之后已经不够用了：三件事的号都以 `w` 开头，
     *   按前缀取会把三件的时间线并成一条 —— 而且每一行都确有其事，看不出是错的。
     */
    @Volatile private var only: String? = null

    init {
        setBackgroundColor(0xFF05080A.toInt())
        settings.javaScriptEnabled = true
        // ★ 只读本地资产：不开放 file:// 去读别的路径，也不需要网络权限。
        settings.allowFileAccess = true
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        setOverScrollMode(OVER_SCROLL_NEVER)
        // ★ 2026-11-06：右侧那条灰色「书签」其实是 WebView 自己画的滚动条，
        //   网页里的 `::-webkit-scrollbar` 关不掉它（Android 用的是覆盖式滚动条）。
        //   内容照常能滚，只是不再画出来。
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        // ★ 2026-11-06：这块屏是按 CSS 像素排的仪表（左右 30.5%、每格读数固定宽），
        //   系统字号会跟着 `font_scale` 放大 —— 这台机是 1.6 倍。放大之后
        //   「执行中」挤成两行、标题被省略号截掉、右栏一行卡占掉半屏。
        //   系统字号是给系统界面用的；固定排版的屏要把 WebView 的缩放钉回 100%。
        settings.textZoom = 100
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                ready = true
                if (title.isNotEmpty()) {
                    evaluateJavascript(
                        "window.dashTitle(" + JSONObject.quote(title) + ")", null
                    )
                }
                if (empty.isNotEmpty()) {
                    evaluateJavascript(
                        "window.dashEmpty(" + JSONObject.quote(empty) + ")", null
                    )
                }
                pending?.let {
                    pending = null
                    push(it)
                }
                // 页面刚开时先把最近这段补上：否则要先等一条新事件才看得见东西。
                if (jobsJson.isNotEmpty()) pushJobs()
                flush()
            }
        }
        // ★ 只挂**一个**函数，名字是常量、不拼参数：JS 桥是攻击面，
        //   能少给一点就少给一点（页面是本地资产，但仍然按外部输入对待）。
        addJavascriptInterface(object {
            @JavascriptInterface
            fun openStep(id: Int) {
                // ★ 回到 UI 线程再弹框：JS 桥是在 WebView 自己的线程上调进来的。
                post { onStep?.invoke(id) }
            }

            @JavascriptInterface
            fun switchJob(id: String) {
                post { onSwitchJob?.invoke(id) }
            }

            @JavascriptInterface
            fun jobMenu(id: String) {
                post { onJobMenu?.invoke(id) }
            }

            @JavascriptInterface
            fun newJob() {
                post { onNewJob?.invoke() }
            }
        }, JS_BRIDGE)
        loadUrl("file:///android_asset/dash/index.html")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // ★ 只在自己那一类事件上重画。两个大屏同时开着时（比如工作台压在房间上面），
        //   不加这个判断就会互相把对方的账推过去 —— 而且推过去之后**看不出是错的**，
        //   因为画出来的每一行都确有其事，只是不是这一屏的事。
        AgentBus.listen { e ->
            val cur = only
            val mine = if (cur == null) e.task.startsWith(taskPrefix)
            else cur.isNotEmpty() && e.task == cur
            // ★★ 必须 `post` 回 UI 线程：`emit` 是在**跑活的那条线程**上回调的
            //   （见 [AgentBus.emit]「出了锁再回调」），而 WebView 的方法只在 UI 线程
            //   上可用。在这儿直接 flush 会抛异常，而 `emit` 那边把异常吞了 ——
            //   表现就是**账上早就有了、屏上一直不动**，等下次别的原因触发一次
            //   flush 才「一下子全出来」。
            if (mine) post(onFlush)
        }
        flush()
    }

    override fun onDetachedFromWindow() {
        AgentBus.listen(null)
        super.onDetachedFromWindow()
    }

    private fun flush() {
        val cur = only
        val list = when {
            cur == null -> AgentBus.recentWith(taskPrefix, SHOW_EVENTS)
            // ★ 空串 = 「新的一件」：它还没有任何事件，屏上就该是空的。
            //   退回按前缀取会把三件活的账全画出来 —— 那不叫「新的一件」。
            cur.isEmpty() -> emptyList()
            // ★ 内存里那份只有最近 [AgentBus] 的 KEEP 条：老账（或者重装/重启之后再进来
            //   的那一件）在内存里是空的。这时候回盘上读一次，否则切过去是一片空白 ——
            //   而盘上明明整整齐齐全都还在。
            else -> AgentBus.recentOf(cur, SHOW_EVENTS).ifEmpty {
                AgentBus.of(cur).takeLast(SHOW_EVENTS)
            }
        }
        push(eventsJson(list))
    }

    /** 换到另一件活（null = 按前缀取一类；空串 = 还没有活）。 */
    fun showTask(id: String?) {
        if (only == id) return
        only = id
        flush()
    }

    /**
     * 推任务条。
     *
     * @param json `[{id, code, status, label, title}]`
     * @param cur  当前那一件的号（空 = 还没选，那一格不高亮）
     */
    fun setJobs(json: String, cur: String) {
        jobsJson = json
        jobsCur = cur
        // 页面还没加载完就先存着，`onPageFinished` 里补推 —— 丢掉那一次的话，
        // 任务条要等到下一条事件才出现，看起来像「没做出来」。
        if (ready) pushJobs()
    }

    private var jobsJson = ""
    private var jobsCur = ""

    private fun pushJobs() {
        evaluateJavascript(
            "window.dashJobs(" + JSONObject.quote(jobsJson) + "," + JSONObject.quote(jobsCur) + ")",
            null,
        )
    }

    private fun push(json: String) {
        if (!ready) {
            pending = json
            return
        }
        // ★ 传的是**一段 JSON 文本**，不是拼进 JS 里的对象字面量：
        //   事件正文里有引号、换行、反斜杠，拼进去就是一个语法错误，而 WebView 不会告诉你。
        evaluateJavascript("window.dashPush(" + JSONObject.quote(json) + ")", null)
    }

    private fun eventsJson(list: List<AgentEvent>): String {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("task", e.task)
                    .put("from", e.from)
                    .put("role", e.role)
                    .put("kind", e.kind)
                    .put("ref", e.ref)
                    .put("text", e.text)
                    .put("at", e.at)
                    // 这一步真花掉的 token（0 = 没有账）。判据在 dash-core.js。
                    .put("tok", e.tok)
            )
        }
        return arr.toString()
    }

    /** 摘掉时把 WebView 自己收干净；不然它会一直挂在后台，还带着一个页面在跑。 */
    fun dispose() {
        AgentBus.listen(null)
        loadUrl("about:blank")
        destroy()
    }

    companion object {
        /** JS 桥的名字。页面里调 `window.ConMarn.openStep(id)`。 */
        const val JS_BRIDGE = "ConMarn"

        /**
         * 一次推多少条事件。
         *
         * ★ 比一屏能排的多（`dash-core.js` 里 MAX_ROWS 是 60），这样点开一条**已经滑出
         *   屏幕**的旧行时，正文还在。再多就是从内存里囤东西，不是多知道什么。
         */
        const val SHOW_EVENTS = 80
    }
}
