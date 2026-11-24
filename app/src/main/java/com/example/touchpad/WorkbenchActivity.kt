package com.example.touchpad

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * **超级工作台** —— 和她的房间**分开的**另一个界面，专门派多 agent 干活。
 *
 * ## 为什么要拆出来
 *
 * 原来这套东西叫「协同大屏」，挂在她的设置里，看的还是**她那几轮对话**里的派活。
 * 他要的是另一件事：一个**不经过她**的地方 —— 在那儿你自己说要做什么，自己定
 * 「同时分工」还是「一个接一个」，然后看着它跑完。她怎么想、她记不记得，都和工作台无关。
 *
 * ## 三条分界（和她之间）
 *
 * 1. **账分开**：任务号用 [AgentBus.newWorkTask]，前缀是 `w`；她的是 `t`。
 *    [DashWeb] 只画自己那一种，所以两边谁也看不见谁的活。
 * 2. **额度分开**：见 [AiAgent.runWorkbench]。
 * 3. **最多三件并存**：同时最多 [WorkJobMath.MAX_ACTIVE] 件在跑，多的在
 *    [AiAgent.workSlots] 门口排队（2026-11-06 从「一次只能跑一件」放开的）。
 *
 * ## 入口
 *
 * 房间里**双指右滑**进来（见 [ConMarnActivity.dispatchTouchEvent]）；
 * 在这儿**双指左滑**回房间。没有桌面图标 —— 它是「另一个沙盒」，不是第二个 App。
 *
 * ## 底下那一条为什么必须有输入框
 *
 * 他 2026-11-05 原话：「超级工作台需要有输入，不然用户想干嘛说都说不了」。
 * 没有输入的工作台只是一个**看板**，看见的都是她干的活 —— 那正是这次要拆掉的东西。
 */
class WorkbenchActivity : Activity() {

    private var web: DashWeb? = null

    /** 整个界面的根（[buildUi] 那个 `FrameLayout`）—— 指纹那道黑底盖在它上面。 */
    private var rootView: View? = null

    /** 指纹还没验过时盖在最上面的那块黑底；验过就摘掉。 */
    private var gateCover: View? = null

    private lateinit var taskInput: EditText
    /** 输入框里那行浅色占位字。和 [taskInput] 分开挂，是为了让它**永远只占一行**（见 [buildComposer]）。 */
    private lateinit var inputPh: TextView
    /** 「＋文件」那颗钮（收进来的文件数写在它身上）。 */
    private var attachChip: TextView? = null
    private lateinit var modeChip: TextView
    private lateinit var rolesChip: TextView
    private lateinit var modeTierChip: TextView
    private lateinit var runBtn: TextView
    private lateinit var status: TextView

    /**
     * 现在这一栏是干什么的：**并行** / **接力** / **代码**。
     *
     * ★ 第三种是 2026-11-05 补的。他给工作台定的用途是「协作 + 能跑代码」——
     *   只做前两样的话，写好了东西还要拿去别处跑，那它就不是工作台，是看板。
     *   代码这一路走的是 [Sandbox]（Alpine 发行版），跑出来的东西照样落进同一条账，
     *   所以在屏上和「派出去一份活」长得一样：一行代号 + 读数，点开看全文。
     */
    private var mode = MODE_FANOUT

    /** 这一种模式下的角色清单。两种模式各记各的，来回切不会丢。 */
    private val roles = HashMap<Boolean, MutableList<String>>()

    /**
     * **现在是哪一件活**（空 = 还没开跑的那一件）。
     *
     * ★ 2026-11-06：这是这次改动的核心。之前「一件事」是进程里唯一的那一格，
     *   所以界面不需要知道「我在看哪件」—— 现在同时能挂三件，这个号就是**切页的钥匙**：
     *   大屏按它取账，输入框按它决定是「新开」还是「接着说」。
     */
    private var curJob: String = ""

    /** 盘上现有的全部活（含归档的）。界面只是它的一个显示。 */
    private val jobs = ArrayList<WorkJob>()

    /**
     * 每一件活的**停止令牌** —— 见 companion 里的 [cancels]。
     *
     * ★★ 2026-11-08（体检 D1）：它原来是**这个 Activity 的**实例字段。Activity 一重建
     *   （转屏、被系统回收、从任务卡回来），表就是空的，而活还在跑 ——
     *   那时按「停止」只会把档案改成「已停止」，**模型那边一行都没停**，
     *   几秒后它跑完又把状态写回「完成」。挪到进程级之后，令牌和活同寿命。
     */

    /**
     * 这一轮带的附件。
     *
     * ★ 文件本体**不进这里**，它们已经在沙盒工作区（[WORK_DIR]）里了；这里只留
     *   名字、大小、和（文本类的）全文 —— 全文要拼进这一轮的提示词，而沙盒里的那份
     *   是给「代码」那一档和模型自己动手用的。
     */
    private val picks = ArrayList<AttachItem>()

    private val chain: Boolean get() = mode == MODE_CHAIN

    /**
     * 盘上现存的 agent（长期实体：有名字、职责、自己的记忆）。
     *
     * ★ 它是**盘上的东西**，不是界面上的状态：App 被杀、手机重启，回来还是这几个人。
     */
    private var agents: List<AgentProfile> = emptyList()

    /** 现在选中的 agent（[AgentProfile.id]）。空 = 还没选过。 */
    private var agentId: String = ""

    /** 这一档现在由谁做。选过就用选过的，没选过就用做得最多的那位。 */
    private val agent: AgentProfile?
        get() = AgentProfileMath.pick(agents, agentId) ?: AgentProfileMath.ordered(agents).firstOrNull()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.black)
        // ★ 默认**空**：留空就是让中枢自己把活拆成几段不同的，不再硬塞「编辑/审稿人/估算师」。
        //   他 2026-11-05 问：「就必须用编辑、审稿人、估算师等等吗？就不能分布式协同做不同的事情？」
        //   —— 那三个名字本来是「同一个问题问三遍」的评审席，不是分工。
        //   存档键换成了 `fanout2`：旧版本存过那三个名字，沿用旧键会把它们又捡回来。
        roles[false] = loadRoles("fanout2", emptyList()).toMutableList()
        roles[true] = loadRoles("chain", listOf("检索员", "编辑", "审稿人")).toMutableList()
        WorkJobStore.init(this)
        AgentProfileStore.init(this)
        // ★★ 门口那道锁（2026-11-06 他报：「超级工作台息屏再开不用指纹」）。
        //   原来看门只有 [ConMarnActivity.onResume] 一处，而工作台是**另一个
        //   Activity**：息屏时它在前台，亮屏回来走的是它的 onResume，房间那一处
        //   根本不会被调到 —— 于是整道锁在这条路上等于不存在（而且不报错）。
        //   [AppGate.init] 幂等，这里再调一次是为了「工作台先于房间起来」那种顺序。
        AppGate.init(applicationContext)
        val content = buildUi()
        rootView = content
        setContentView(content)
        // ★ 进来先接上**最近的那一件**（还没归档的）：进程被回收之后再进来，
        //   他还得看得见上次那件事，而不是面对一块空白。
        curJob = WorkJobStore.all().filter { !it.archived }.maxByOrNull { it.updatedAt }?.id ?: ""
        recoverStale()
        refreshAgents()
        refreshJobs()
        web?.showTask(curJob)
        enterImmersive()
        // ★ 工作台里的活照样要能查原文（她那边点一行也是这样），不因为是新界面就少一条路。
        StepStore.init(this)
        AgentBus.init(this)
        // ★ 沙盒要先备好：`linux` 这一路第一次跑会把 Alpine 解开（几百毫秒到一两秒）。
        //   在这里 init 掉，等真按「跑」的时候它已经备着了。
        Sandbox.init(applicationContext)
    }

    /**
     * 进来时把「上次没跑完」的账收干净。
     *
     * ★ 判据挂在**进程**上（[liveJobs]），不挂在这一个 Activity 上：进程被杀、
     *   或者线程抛异常退出时，job 文件里还写着 `running`，而那一刻活已经没了。
     *   不收的话任务条永远写着「在跑」——「接着说」永远被拒（见 [openJob]），
     *   「停止」也永远只回一句「没在跑」，这件活就**卡死**了。
     */
    private fun recoverStale() {
        val now = System.currentTimeMillis()
        for (j in WorkJobStore.all()) {
            if (j.status != WorkJobMath.ST_RUNNING) continue
            if (liveJobs.contains(j.id)) continue
            WorkJobStore.save(j.copy(status = WorkJobMath.ST_STOPPED, updatedAt = now))
        }
    }

    /**
     * 线程里**抛了异常**时的兜底：`out` 是外层 catch 拼出来的，没人定过状态，
     * 那件活的档案可能还停在 `running`。定过状态的（带 `job`）不碰。
     */
    private fun unstuck(jobId: String, out: JSONObject) {
        if (out.has("job")) return
        val j = WorkJobStore.find(jobId) ?: return
        if (j.status != WorkJobMath.ST_RUNNING) return
        WorkJobStore.save(
            j.copy(
                status = if (out.optBoolean("stopped")) WorkJobMath.ST_STOPPED else WorkJobMath.ST_FAILED,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    // ------------------------------------------------------------------
    // 界面
    // ------------------------------------------------------------------

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ── 场 + 列表：整个上半块交给它（和调度大屏同一份资产、同一套判据）──
        val dash = DashWeb(
            this,
            taskPrefix = "w",
            title = "SUPER WORKBENCH",
            // 工作台的空屏不该说「她一开口派活」—— 这里的活是他自己派的。
            empty = "还没有活。\n在下面写清要做什么，然后开跑。",
            onStep = { id -> showStep(id) },
            onSwitchJob = { id -> selectJob(id) },
            onJobMenu = { id -> jobMenu(id) },
            onNewJob = { startNewJob() },
        )
        web = dash
        col.addView(dash, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        // ★ 大屏要**先挂进根布局**：底栏改成浮层之后这一句被顺手漏掉了，
        //   结果是 WebView 根本没被 attach —— 整屏全黑，只剩底栏。见 2026-11-06 那次。
        root.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // ── 状态行：一行字，说清楚现在在干什么。不占说明的位置（说明在设置里）──
        status = TextView(this).apply {
            text = ""
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(DIM)
            setPadding(dp(14), dp(2), dp(14), dp(2))
            visibility = View.GONE
        }

        // ★ 2026-11-06：底栏**浮在大屏上面**（不再是它下面的一条）。
        //   理由是他要的那句「透明磨砂玻璃」—— 玻璃得有东西可透。
        //   原来是竖着排的两块，底栏背后是纯黑的根布局，半透明压上去只会发灰，
        //   正是他嫌的那种「灰色工厂面板」。现在它压在大屏上，透出来的是真实的场和文字。
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(10), dp(10))
        }
        bottom.addView(status)
        bottom.addView(buildComposer())
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        )
        // ★ 2026-11-06 他要的：**底栏只占右边那一栏**（日志那一侧），别横跨整屏 ——
        //   左边那半是场和读数，底栏压上去正好盖住「派发 / 回传 / token」那一排。
        //   左边那一栏的宽度不在 Android 里定义，它在 dash.css 的 `--split`（30.5%）：
        //   两处必须是同一个数，所以这里直接照着它算，并写了 [LEFT_PANEL] 做锚。
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            lp.leftMargin = (resources.displayMetrics.widthPixels * LEFT_PANEL).toInt()
        }
        root.addView(bottom, lp)

        // ★ 2026-11-06 他点名：「自动放在最底下那个对话框最左端，然后不需要关闭按钮」。
        //   所以右上角那一排整个撤掉了：
        //   - 「关闭」删掉 —— 出口是**双指左滑**（见 dispatchTouchEvent），和进来的
        //     双指右滑对称；留一颗钮等于同一个动作有两个说法。
        //   - 档位那颗（自动 / 规划 / 逐步）挪到下面那一条的**最左端**，
        //     见 [buildComposer]。
        return root
    }

    /**
     * 底下那一条：**说要做的事** + 怎么分工 + 开跑。
     *
     * 一行放得下：手机上横屏的高度很紧，多一行就把列表挤掉一截。
     * 分工那一段点开是一个多行框（一行一个岗位），因为它本来就可能长。
     */
    private fun buildComposer(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            // 一块压在**大屏之上**的透明磨砂玻璃（见 res/drawable/wb_glass_panel.xml）
            setBackgroundResource(R.drawable.wb_glass_panel)
        }

        // ── 协作档位：自动 → 规划 → 逐步，点一下转一格 ──
        // ★ 他 2026-11-06 指定的位置：**底下这一条的最左端**。
        //   和房间里那颗钮同一份设置（改内存里那一份，下一句就生效 + 落盘）。
        // ★ 2026-11-06 他改的口径：**点一下展开、选一项**，不再「点一下换一个」。
        //   三个档位挤在一个循环里，转错了要再点两下转回来，而他根本不知道下一格是什么。
        modeTierChip = chip(AgentMode.label(loadTier()) + " ▾") {
            chooseTier()
        }.apply {
            setTextColor(0xFF9BE8DA.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        row.addView(modeTierChip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(6) })

        // ── 附件：手机上任何应用里的文件都能挑进来 ──
        // ★ 用系统的 ACTION_OPEN_DOCUMENT（不是自建一个文件浏览器）：相册、微信、下载、
        //   网盘里的东西各有一套权限，只有系统那个框能一次问全。
        //   收进来的文件**放进沙盒工作区**而不是留在 App 私有目录 —— 这样「代码」那一档
        //   可以直接在命令里用它，也是以后在手机上改代码的基础。
        attachChip = chip("＋文件") { pickFiles() }.apply {
            setTextColor(0xFFE6F4FF.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        row.addView(attachChip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(6) })

        // ★ 2026-11-06 他说「那个要做的事，又两行了，要么你就不要有字在那」。
        //   这句是**量出来的**问题：这台机 font_scale=1.6，一行 12.5sp 的「任务内容」
        //   实际要七十多 dp，而这一格被五颗钮夹在中间，宽度不够就折成两行。
        //   光把字改短只是把折行推后，换一台机/换一种语言又折 —— 所以改成
        //   **占位文字单独一层**：`maxLines = 1` + 省略号，宽度不够时截断，永远只有一行。
        val inputBox = FrameLayout(this).apply { setBackgroundResource(R.drawable.wb_glass_edit) }
        taskInput = EditText(this).apply {
            textSize = 12.5f
            setTextColor(0xFFE2F5EE.toInt())
            // 玻璃底挪到外面那一层了，这里必须是透明的，否则两层底叠成一块实心
            setBackgroundColor(0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 3
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        inputBox.addView(taskInput, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        inputPh = TextView(this).apply {
            textSize = 12.5f
            setTextColor(0xFF46675F.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), dp(8), dp(12), dp(8))
            // 不吃触摸：字在上面，点下去要落到输入框里
            isClickable = false
            isFocusable = false
        }
        inputBox.addView(inputPh, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        taskInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                inputPh.visibility = if (s.isNullOrEmpty()) View.VISIBLE else View.GONE
            }
        })
        row.addView(inputBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // ★ 同上：**点一下展开、选一项**（并行 / 接力 / 代码 / Agent）。
        modeChip = chip("分工 ▾") { chooseMode() }
        row.addView(modeChip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(6) })

        // ★ 同一颗钮在 agent 档里换个意思：**由谁来做**（见 [editAgents]）。
        rolesChip = chip("3 位") { if (mode == MODE_AGENT) editAgents() else editRoles() }
        row.addView(rolesChip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(6) })

        runBtn = chip("开跑") { run() }.apply {
            setTextColor(0xFF04120C.toInt())
            setBackgroundResource(R.drawable.wb_glass_run)
            setPadding(dp(18), dp(9), dp(18), dp(9))
            letterSpacing = 0.06f
        }
        row.addView(runBtn)

        refreshChips()
        return row
    }

    private fun chip(label: String, tap: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        typeface = Typeface.MONOSPACE
        setTextColor(0xFFE6F4FF.toInt())
        letterSpacing = 0.04f
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setBackgroundResource(R.drawable.wb_glass_chip)
        setOnClickListener { tap() }
    }

    /**
     * 「点一下展开、选一项」—— 底栏那些多选一的钮都走这里。
     *
     * ★ 2026-11-06 他报的：「点一下换一个、点一下换一个，非常不方便」。
     *   循环切换的问题不在快慢，在**看不见有哪些项、也看不见选到了没**——
     *   转错了要再点两下转回来。
     *
     * ★ 展开的列表用**同一块玻璃**（[R.drawable.wb_glass_panel]），
     *   当前那一项前面点一个 ·，不靠颜色分（他的屏在阳光下颜色分不出来）。
     *
     * @param items 要选的项（显示名）
     * @param current 当前那一项的序号；-1 = 没有
     * @param onPick 选了以后回调**序号**
     */
    private fun choose(anchor: View, items: List<String>, current: Int, onPick: (Int) -> Unit) {
        val pop = android.widget.PopupWindow(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundResource(R.drawable.wb_glass_panel)
        }
        for ((i, name) in items.withIndex()) {
            val on = i == current
            val t = TextView(this).apply {
                text = if (on) "· $name" else name
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setTextColor(if (on) 0xFF9BE8DA.toInt() else 0xFFE6F4FF.toInt())
                letterSpacing = 0.03f
                setPadding(dp(16), dp(10), dp(24), dp(10))
                setBackgroundResource(R.drawable.wb_glass_chip)
                setOnClickListener {
                    pop.dismiss()
                    onPick(i)
                }
            }
            col.addView(t, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) })
        }
        // ★ 先量一次高度再决定往哪弹：没量之前 `col.height` 是 0，
        //   偏移量会算成「贴着钮」，列表直接从屏幕底下弹出去。
        col.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        pop.contentView = col
        pop.isOutsideTouchable = true
        pop.isFocusable = true
        // 透明底 + 窗外点一下能收：不加那层透明 drawable，外面点不动、按返回也收不掉。
        pop.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        pop.elevation = dp(12).toFloat()
        // ★ 往上弹：底栏贴着屏幕底边，往下弹会出一半在屏外。
        pop.showAsDropDown(anchor, 0, -anchor.height - col.measuredHeight - dp(6))
    }

    /** 协作档位：自动 / 规划 / 逐步。 */
    private fun chooseTier() {
        val cur = AgentMode.norm(loadTier())
        val names = AgentMode.ALL.map { AgentMode.label(it) }
        choose(modeTierChip, names, AgentMode.ALL.indexOf(cur)) { i -> pickTier(AgentMode.ALL[i]) }
    }

    /** 这一件活怎么派：并行 / 接力 / 代码 / Agent / 超级。 */
    private fun chooseMode() {
        val names = listOf("分工", "接力", "代码", "Agent", "超级")
        choose(modeChip, names, mode) { i -> setMode(i) }
    }

    /**
     * 换占位字。
     *
     * ★ 2026-11-06 他：「那个任务框不要有字」—— 所以这个框里**一律不显示任何提示文字**。
     *   留着这个函数和它的调用点，把参数吃掉即可：哪天要挂回来，只改这一处。
     */
    private fun placeholder(text: String) {
        inputPh.text = ""
        inputPh.visibility = View.GONE
    }

    private fun refreshChips() {
        when (mode) {
            MODE_CHAIN -> {
                modeChip.text = "接力 ▾"
                rolesChip.visibility = View.VISIBLE
                rolesChip.text = "${roles[true]?.size ?: 0} 段"
                placeholder("任务内容")
                runBtn.text = "执行"
            }
            MODE_CODE -> {
                modeChip.text = "代码 ▾"
                // 跑一条命令没有「几位」可分，那一颗钮整个收起来，省下的宽度给输入框。
                rolesChip.visibility = View.GONE
                // ★ 两种用法写进提示词里就够，不再加钮（底部那一排已经满了）。
                placeholder("任务内容")
                runBtn.text = "执行"
            }
            MODE_AGENT -> {
                modeChip.text = "Agent ▾"
                // 这颗钮在 agent 档里换了个意思：**由谁来做**。点开是名单。
                rolesChip.visibility = View.VISIBLE
                rolesChip.text = agent?.name ?: "没有 agent"
                placeholder("任务内容")
                runBtn.text = "派发"
            }
            MODE_SUPER -> {
                modeChip.text = "超级 ▾"
                // 超级不挑人也不分段：它一个就够，那一颗钮收起来，宽度让给输入框。
                rolesChip.visibility = View.GONE
                placeholder("任务内容")
                runBtn.text = "交给它"
            }
            else -> {
                modeChip.text = "分工 ▾"
                rolesChip.visibility = View.VISIBLE
                val n = roles[false]?.size ?: 0
                // 留空＝交由中枢拆；写了几个岗位就写「N 段」，两种都看得见。
                rolesChip.text = if (n == 0) "自动分配" else "$n 段"
                placeholder("任务内容")
                runBtn.text = "执行"
            }
        }
    }

    /** 直接选到某一档（不再「转一格」）。 */
    private fun setMode(to: Int) {
        if (to == mode) return
        mode = to.coerceIn(0, MODE_SUPER)
        if (mode == MODE_AGENT) refreshAgents()
        refreshChips()
    }

    /**
     * 协作档位（自动 / 规划 / 逐步）。
     *
     * ★ 和房间里那颗钮**同一份设置**：改内存里那一份（下一句就生效）+ 落盘。
     *   用 [AiAgentHolder.peek] 而不是 `get` —— 转个档不该为了它把整套东西建出来。
     * ★ 屏上只出现档位那两个字，解释留在设置页。
     */
    private fun loadTier(): String =
        AiAgentHolder.peek()?.config?.agentMode
            ?: AiAgent.loadConfig(this).agentMode

    /** 直接选到某一档（不再「转一格」）。 */
    private fun pickTier(raw: String) {
        val live = AiAgentHolder.peek()
        val c = live?.config ?: AiAgent.loadConfig(this)
        c.agentMode = AgentMode.norm(raw)
        live?.config = c
        AiAgent.saveConfig(this, c)
        modeTierChip.text = AgentMode.label(c.agentMode) + " ▾"
    }

    // ------------------------------------------------------------------
    // 附件
    // ------------------------------------------------------------------

    /** 沙盒里这些文件所在的目录（proot 的 `/root` 就是 rootfs/root）。 */
    private fun workDir(): File = File(Sandbox.rootfs(this), "root/work")

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_PICK)
        } catch (e: Exception) {
            toast("这台机没有能用的文件选择器。")
        }
    }

    /**
     * 选完之后：**拷进沙盒工作区**，再把文本读进来。
     *
     * ★ 必须离开 UI 线程：一个几十 MB 的文件在主线程上拷，手机会先卡住、然后 ANR，
     *   而界面上只会看到「什么都没发生」。
     */
    @Deprecated("这一层是 android.app.Activity，没有 androidx 的 result 通道，照旧用它。")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        if (uris.isEmpty()) return

        status.visibility = View.VISIBLE
        status.text = "在收文件…"
        Thread {
            val dir = workDir()
            val got = ArrayList<AttachItem>()
            var fail = 0
            for (u in uris) {
                try {
                    val item = copyIn(u, dir)
                    if (item == null) fail++ else got.add(item)
                } catch (e: Exception) {
                    fail++
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                picks.addAll(got)
                refreshAttach()
                status.text = buildString {
                    if (got.isEmpty()) append("一个文件都没收进来。")
                    else append("收到 ").append(got.size).append(" 个文件，放在 ").append(WORK_DIR).append("/")
                    if (fail > 0) append("（").append(fail).append(" 个没收进来，可能是太大或者读不到）")
                }
                status.visibility = View.VISIBLE
            }
        }.start()
    }

    /** 拷一个 `content://` 到工作区。→ null 表示这个文件收不了（太大 / 打不开）。 */
    private fun copyIn(uri: Uri, dir: File): AttachItem? {
        var name = "file"
        var size = -1L
        runCatching {
            contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) c.getString(ni)?.takeIf { it.isNotBlank() }?.let { name = it }
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        // 先按「提供方说的大小」拦一道：超过上限的连读都不用读。
        if (size > WorkAttachMath.MAX_FILE_BYTES) return null
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return null

        val mime = contentResolver.getType(uri).orEmpty()
        val target = uniqueFile(dir, WorkAttachMath.safeName(name))
        val tmp = File(dir, target.name + ".part")
        var written = 0L
        try {
            contentResolver.openInputStream(uri)?.use { ins ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        written += n
                        // 提供方报的大小可能是假的（0 或 -1），所以边写边量。
                        if (written > WorkAttachMath.MAX_FILE_BYTES) return null
                        out.write(buf, 0, n)
                    }
                }
            } ?: return null
            if (!tmp.renameTo(target)) return null
        } finally {
            if (tmp.exists() && !tmp.renameTo(target)) tmp.delete()
        }
        val body = if (WorkAttachMath.isText(target.name, mime)) readText(target) else null
        return AttachItem(target.name, if (size >= 0) size else written, body)
    }

    /** 同名不覆盖：`a.txt`、`a-2.txt`、`a-3.txt`。后传的那份不该悄悄顶掉前一份。 */
    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (f.exists()) {
            f = File(dir, "$stem-$n$ext")
            n++
            if (n > 999) break
        }
        return f
    }

    /** 读文本的前一段。→ null = 看着不像文本（送一屏乱码比不送更糟）。 */
    private fun readText(f: File): String? {
        // ★ 不用 `readNBytes`：那是 API 33 才有的，而 minSdk 是 30 —— 在 30/31 上
        //   会是一个 NoSuchMethodError，而且只在他真的带附件时才炸。
        val cap = WorkAttachMath.MAX_ONE_CHARS * 4 + 8
        val bytes = runCatching {
            f.inputStream().use { ins ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8 * 1024)
                while (out.size() < cap) {
                    val n = ins.read(buf, 0, minOf(buf.size, cap - out.size()))
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        }.getOrNull() ?: return null
        val s = String(bytes, Charsets.UTF_8)
        if (!WorkAttachMath.looksTextual(s)) return null
        return if (s.length > WorkAttachMath.MAX_ONE_CHARS) {
            s.substring(0, WorkAttachMath.MAX_ONE_CHARS) + "\n…（后面还有；全文在沙盒的 " + WORK_DIR + "/" + f.name + "）"
        } else s
    }

    private fun refreshAttach() {
        attachChip?.text = if (picks.isEmpty()) "＋文件" else "＋文件 ${picks.size}"
    }

    // ------------------------------------------------------------------
    // 分工：一行一个岗位，写「岗位/这一段做什么」；也可以用 `；` 隔开（竖线照样认）
    // ------------------------------------------------------------------

    private fun editRoles() {
        val box = EditText(this).apply {
            // ★ 2026-11-06：竖线在手机键盘上翻页才打得到，所以主写法改成 `岗位/任务`
            //   和 `；` 断句（见 [FanoutMath.splitItems]）。竖线照样认，老清单不用重写。
            hint = if (chain) "每行一段：岗位/任务内容"
            else "每行一段：岗位/任务内容\n留空则由中枢自行拆分"
            // ★ 还是**一行一条**地摆出来（用 `；` 拼会毁掉只有名字、没写作用的那种清单）
            setText((roles[chain] ?: mutableListOf()).joinToString("\n"))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            gravity = Gravity.TOP
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        AlertDialog.Builder(this, R.style.Theme_Touchpad_GlassDialogDark)
            .setTitle(if (chain) "接力设置（第 1 段至第 n 段）" else "并行分工设置")
            .setView(rolesEditor(box))
            .setPositiveButton("好") { _, _ ->
                // ★ 「；」也当断句（见 [FanoutMath.splitItems]）：一行里写好几条也算数。
                val list = FanoutMath.splitItems(box.text.toString()).toMutableList()
                roles[chain] = list
                saveRoles(if (chain) "chain" else "fanout", list)
                refreshChips()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 写岗位的弹框：上面一排**快捷岗位**，下面还是那份清单。
     *
     * ★ 快捷的那几个来自**上一次真用过的清单**（取岗位名，去重，最多六格），
     *   第一次用没有历史，就给三个通用的兜底。点一下在清单末尾加一行 `岗位|`，
     *   光标停在竖线后面 —— 接着写的就这一段要它干什么。
     * ★ 没有「删除某一位」按钮：清单就是一多行文本，删掉那一行就是删掉那个人。
     *   再加一套增删按钮，等于同一件事有两个说法。
     */
    private fun rolesEditor(box: EditText): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(0))
        }

        val recent = loadRoles(if (chain) "chain" else "fanout", emptyList())
            .map { it.substringBefore('|').substringBefore('｜').substringBefore('/').trim() }
            .filter { it.isNotEmpty() }
        val quick = (recent + listOf("检索", "文案", "审核")).distinct().take(6)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (name in quick) {
            val c = chip("＋" + name) {
                val cur = box.text.toString().trimEnd()
                // ★ 插进去的是 `名字/`，不是 `名字|` —— 和框里的主写法一致。
                box.setText(if (cur.isEmpty()) "$name/" else "$cur\n$name/")
                box.setSelection(box.text.length)
            }.apply { setTextColor(0xFF0E7C74.toInt()) }
            row.addView(c, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) })
        }
        col.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })

        col.addView(ScrollView(this).apply { addView(box) })
        return col
    }

    // ------------------------------------------------------------------
    // 开跑
    // ------------------------------------------------------------------

    private fun run() {
        val what = taskInput.text.toString().trim()
        if (what.isEmpty()) {
            toast("请先填写任务内容")
            return
        }
        // ★ 代码这一档有两个意思，判据只看开头一个字符（和 Claude Code 的 `!` 同一条）：
        //   `!命令` = 我就要跑这一条；其余 = 说一件事，它自己写、自己跑、自己改。
        if (mode == MODE_CODE) {
            val raw = what.removePrefix("!").removePrefix("！").trim()
            if (raw != what.trim()) {
                if (raw.isEmpty()) { toast("！后面要跟命令"); return }
                runCode(raw)
            } else {
                runCodingJob(what)
            }
            return
        }
        if (mode == MODE_AGENT) {
            runAgentJob(what)
            return
        }
        if (mode == MODE_SUPER) {
            runSuperJob(what)
            return
        }
        val list = roles[chain].orEmpty()
        // ★ 分工留空是**允许**的：那是「你拆，我不指定」。只有接力必须有段数 ——
        //   没有段的接力没有任何意义。
        if (chain && list.size < 2) {
            toast("接力至少要两段")
            return
        }
        // ★ 没写「这一步做什么」的行，就把整件事交给它 —— 角色本身是在
        //   [AiAgent.delegateBrief] 那边区分的（同一个岗位看到的约束不一样）。
        // ★ 2026-11-06：`名字/任务` 也算写全了（见 [FanoutMath.splitItems]）。
        val specs = list.map { line ->
            if (line.any { it == '|' || it == '｜' || it == '/' || it == '／' }) line
            else "$line|$what"
        }
        val job = openJob(what) ?: return

        val tok = AtomicBoolean(false)
        cancels[job.id] = tok
        liveJobs.add(job.id)
        taskInput.setText("")
        status.visibility = View.VISIBLE
        status.text = if (specs.isEmpty()) "先拆成几段，再同时派出去…" else "已派出 " + specs.size + " 份…"

        val agent = AiAgentHolder.get(this)
        val jobId = job.id
        // ★ 附件**只进这一轮**（见 [AiAgent.runWorkbench] 的 attach）：写回会话的话，
        //   之后每一轮都要背着这几十 KB。
        val attachText = WorkAttachMath.block(picks, WORK_DIR)
        Thread {
            val out = try {
                agent.runWorkbench(jobId, specs, chain, tok, cb, attachText)
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
            }
            // ★ 摘记号、补状态都在进 UI 线程之前 —— 这个 Activity 可能已经不在了。
            liveJobs.remove(jobId)
            unstuck(jobId, out)
            runOnUiThread {
                // ★ 令牌在这里摘掉：这件活已经跑完了，再留着它，下一次「停止」
                //   会按到一个已经没人看的令牌上（看起来像按了没反应）。
                cancels.remove(jobId)
                refreshJobs()
                status.text = if (out.optBoolean("ok")) "" else (out.optString("error").ifBlank { "没跑成" })
                if (status.text.isBlank()) status.visibility = View.GONE
                showResult(out)
            }
        }.start()
    }

    private fun idleLabel() = if (mode == MODE_CODE) "执行" else "执行"

    /**
     * 交给一个 agent 去做。
     *
     * ★ 记下「谁认领了这件活」（[WorkJob.agent]），因为「接着说」要接给**同一个人**——
     *   否则他改一句追问，接手的是另一个什么都不记得的它，而界面上看起来一切照旧。
     */
    private fun runAgentJob(what: String) {
        val picked = agent
        if (picked == null) {
            toast("尚未配置执行者。请点「新建」，格式：名称|职责")
            return
        }
        val opened = openJob(what) ?: return
        // ★ 认领过的活沿用当初那位：换个 agent 再接着说，等于让别人接手。
        val who = AgentProfileMath.pick(agents, opened.agent) ?: picked
        val job = opened.copy(agent = who.id)
        WorkJobStore.save(job)

        val tok = AtomicBoolean(false)
        cancels[job.id] = tok
        liveJobs.add(job.id)
        taskInput.setText("")
        status.visibility = View.VISIBLE
        status.text = who.name + " · 执行中…"
        refreshJobs()

        val core = AiAgentHolder.get(this)
        val jobId = job.id
        val attachText = WorkAttachMath.block(picks, WORK_DIR)
        val whoId = who.id
        Thread {
            val out = try {
                core.runAgent(this, jobId, whoId, what, tok, cb, attachText)
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
            }
            // ★ 先摘「在跑」的记号、再补状态，**都在进 runOnUiThread 之前**：
            //   这个 Activity 完全可能在活跑完的那一刻已经没了，那时 UI 那一半根本不会执行。
            liveJobs.remove(jobId)
            unstuck(jobId, out)
            runOnUiThread {
                cancels.remove(jobId)
                refreshAgents()
                refreshJobs()
                val head = when {
                    out.optBoolean("stopped") -> "已停止"
                    out.optBoolean("ok") -> "「" + who.name + "」执行完成"
                    else -> out.optString("error").ifBlank { "执行失败" }
                }
                status.text = head
                status.visibility = if (head.isBlank()) View.GONE else View.VISIBLE
                // ★ agent 档**不弹成品窗**：它的报告就是时间线上那条铺开的答复
                //   （见 dash-app.js 的 reportId）。弹窗只在真出错时出现 ——
                //   否则他要读到结论就得开一个窗、关一个窗，再回来看这条线。
                if (!out.optBoolean("ok") && !out.optBoolean("stopped")) showResult(out)
                // ★ agent 这一档**没有成品窗**，报告是时间线上那条 —— 所以发声要在这儿接，
                //   不能借 [showResult]（那条路这里根本不走）。
                if (out.optBoolean("ok")) HerSpeak.say(this, speakable(out.optString("final")))
            }
        }.start()
    }

    /**
     * 交给**超级**去做。
     *
     * ★ 和 [runAgentJob] 是同一副骨架（同一个 cb、同一套停止令牌、同一条「在跑」记号），
     *   不同的只有两处：不挑人（超级只有一个，就是它自己），以及它自己会派活。
     * ★ 它跑完**照样出成品窗**：这一档不是「一条铺开的答复」，收工时它给的是一个结论。
     */
    private fun runSuperJob(what: String) {
        val job = openJob(what) ?: return
        val tok = AtomicBoolean(false)
        cancels[job.id] = tok
        liveJobs.add(job.id)
        taskInput.setText("")
        status.visibility = View.VISIBLE
        status.text = "超级 · 执行中…"
        refreshJobs()

        val core = AiAgentHolder.get(this)
        val jobId = job.id
        val attachText = WorkAttachMath.block(picks, WORK_DIR)
        Thread {
            val out = try {
                core.runSuper(this, jobId, what, tok, cb, attachText)
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
            }
            liveJobs.remove(jobId)
            unstuck(jobId, out)
            runOnUiThread {
                cancels.remove(jobId)
                refreshJobs()
                val head = when {
                    out.optBoolean("stopped") -> "已停止"
                    out.optBoolean("ok") -> "超级 · 做完了（${out.optInt("steps")} 步）"
                    else -> out.optString("error").ifBlank { "执行失败" }
                }
                status.text = head
                status.visibility = if (head.isBlank()) View.GONE else View.VISIBLE
                showResult(out)
            }
        }.start()
    }

    /** 名单从盘上重读一遍。读失败就保持原样，不把界面清空。 */
    private fun refreshAgents() {
        val got = try { AgentProfileStore.all() } catch (_: Exception) { agents }
        agents = AgentProfileMath.ordered(got)
        if (AgentProfileMath.pick(agents, agentId) == null) {
            agentId = agents.firstOrNull()?.id ?: ""
        }
    }

    /**
     * 「由谁来做」：名单 + 新建。
     *
     * ★ 一个新人只有两样东西要写：**名字**和**它负责什么**。没有第三样 ——
     *   提示词、手、预算都是同一套，让它跟别人不一样的只有这两行和它自己的记忆。
     */
    private fun editAgents() {
        refreshAgents()
        if (agents.isEmpty()) { createAgent(); return }
        val names = agents.map { "${it.name} · ${it.runs} 件" }.toTypedArray()
        val cur = agents.indexOfFirst { it.id == agent?.id }
        val pick = intArrayOf(cur)
        AlertDialog.Builder(this, R.style.Theme_Touchpad_GlassDialogDark)
            .setTitle("选择执行者")
            .setSingleChoiceItems(names, cur) { _, which -> pick[0] = which }
            .setPositiveButton("好") { _, _ ->
                agents.getOrNull(pick[0])?.let { agentId = it.id }
                refreshChips()
            }
            .setNeutralButton("新建") { _, _ -> createAgent() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 新建一个 agent：`名字|它负责什么`。同名 = 改写职责，不另起一个。 */
    private fun createAgent() {
        val box = EditText(this).apply {
            hint = "名称｜职责"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        AlertDialog.Builder(this, R.style.Theme_Touchpad_GlassDialogDark)
            .setTitle("新建执行者")
            .setView(box)
            .setPositiveButton("好") { _, _ ->
                val raw = box.text.toString().trim()
                val name = raw.substringBefore('|').substringBefore('｜').trim()
                val charter = raw.substringAfter('|', "").let {
                    if (it.isEmpty()) raw.substringAfter('｜', "") else it
                }.trim()
                AgentProfileMath.nameProblem(name)?.let { toast(it); return@setPositiveButton }
                AgentProfileMath.charterProblem(charter)?.let { toast(it); return@setPositiveButton }
                val id = AgentProfileMath.idOf(name)
                val old = AgentProfileStore.find(id)
                val p = AgentProfile(
                    id = id,
                    name = name,
                    charter = charter,
                    notes = old?.notes ?: emptyList(),
                    runs = old?.runs ?: 0,
                    createdAt = old?.createdAt ?: System.currentTimeMillis(),
                )
                AgentProfileStore.save(p)?.let { toast(it); return@setPositiveButton }
                agentId = id
                refreshAgents()
                refreshChips()
                if (old != null) toast("职责改了，「$name」的记忆还在。")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------------
    // 一件活：新开 / 接着说 / 切换 / 停止 / 归档 / 删
    // ------------------------------------------------------------------

    private fun modeName(): String = when (mode) {
        MODE_CHAIN -> WorkJobMath.MODE_CHAIN
        MODE_CODE -> WorkJobMath.MODE_CODE
        MODE_AGENT -> WorkJobMath.MODE_AGENT
        MODE_SUPER -> WorkJobMath.MODE_SUPER
        else -> WorkJobMath.MODE_FANOUT
    }

    /**
     * 这一次的话是「新开一件」还是「接着说」。
     *
     * ★ 判据只看**当前选的是哪一件**：选着的那件就接着说，没选就是新开。
     *   不按「输入框里有没有内容」判 —— 那是两回事（他改一句再发，还是同一件事）。
     * @return 这件活；null = 开不了（到上限了，或者那一件还在跑）。
     */
    private fun openJob(what: String): WorkJob? {
        val now = System.currentTimeMillis()
        val cur = if (curJob.isEmpty()) null else WorkJobStore.find(curJob)

        // ★ 正在跑的那件不接受「接着说」：那一轮的成品还没写回会话，
        //   现在把话塞进去，两轮的上下文会互相踩，而且后者会被前者覆盖。
        //
        // ★★ 2026-11-08（体检 D1）：**档案状态不是唯一判据。**
        //   活跑在进程里，档案是它跑完才写的；两者之间只要错开一次（上一次没收尾、
        //   界面上被手动改过），只看 `status` 就会放第二轮进来 —— 同一件活两个线程写账。
        //   令牌表才是「此刻真的有没有人在跑」。判据取两者之或。
        if (cur != null && (cur.status == WorkJobMath.ST_RUNNING || cancels.containsKey(cur.id))) {
            toast("该任务正在执行，完成后可继续追加指令。")
            return null
        }
        val job = if (cur != null) {
            cur.copy(
                mode = modeName(),
                status = WorkJobMath.ST_QUEUED,
                updatedAt = now,
                turns = WorkJobMath.appendTurn(cur.turns, WorkTurn.USER, what),
            )
        } else {
            // ★ 上限只数**没归档**的：归档是收起来，不是不要了 —— 归档的还能恢复，
            //   所以它不该占着新活的位置。
            if (WorkJobMath.atCapacity(WorkJobStore.all())) {
                toast("任务数已达上限（${WorkJobMath.MAX_JOBS} 件），请先归档部分任务。")
                return null
            }
            WorkJob(
                id = AgentBus.newWorkTask(),
                title = WorkJobMath.titleOf(what),
                mode = modeName(),
                status = WorkJobMath.ST_QUEUED,
                createdAt = now,
                updatedAt = now,
                turns = listOf(WorkTurn(WorkTurn.USER, what)),
            )
        }
        WorkJobStore.save(job)
        curJob = job.id
        // ★ 必须跟着切过去：大屏是按 `curJob` 取账的，这里只改字段不告诉它，
        //   右边就还是上一格（新开那一格是空的）—— 而任务条上明明已经写着「在跑」。
        //   看起来像「事件没记上」，其实账上一条不少。
        web?.showTask(job.id)
        refreshJobs()
        return job
    }

    private fun selectJob(id: String) {
        val j = WorkJobStore.find(id) ?: return
        curJob = j.id
        // ★ 切到哪一件，底栏就切回**它当初是用哪一档跑的** ——
        //   「接着说」的那一句必须交给同一档去跑；不跟着切的话，
        //   一件超级做的活会被一张停在「分工」的底栏接着做，看着一切照旧。
        mode = when (j.mode) {
            WorkJobMath.MODE_CHAIN -> MODE_CHAIN
            WorkJobMath.MODE_CODE -> MODE_CODE
            WorkJobMath.MODE_AGENT -> MODE_AGENT
            WorkJobMath.MODE_SUPER -> MODE_SUPER
            else -> MODE_FANOUT
        }
        refreshChips()
        web?.showTask(j.id)
        refreshJobs()
        placeholder(if (j.status == WorkJobMath.ST_RUNNING) "这件还在执行" else "补充说明")
    }

    /** `+`：不选任何一件，下一次「开跑」就是新的一件。 */
    private fun startNewJob() {
        curJob = ""
        web?.showTask("")
        refreshJobs()
        taskInput.setText("")
        // ★ 提示语跟着**当前档位**走（agent 档是「交给它去做的一件事」，不是「要做的事」）。
        refreshChips()
        status.visibility = View.GONE
        // ★ 附件跟着这一件走：新开一件时清空清单，**文件本身留在工作区**
        //   （那也是它的去路 —— 要删就去沙盒里删）。
        picks.clear()
        refreshAttach()
    }

    /** 长按任务条上那一格：停止 / 归档（恢复）/ 彻底删。 */
    private fun jobMenu(id: String) {
        val j = WorkJobStore.find(id) ?: return
        val acts = ArrayList<String>()
        if (j.status == WorkJobMath.ST_RUNNING) acts.add("停止")
        acts.add(if (j.archived) "恢复" else "归档")
        acts.add("彻底删除")

        val title = WorkJobMath.codeOf(codeIndex(j.id)) + " · " + j.title
        AlertDialog.Builder(this, R.style.Theme_Touchpad_GlassDialogDark)
            .setTitle(title)
            .setItems(acts.toTypedArray()) { _, i ->
                when (acts[i]) {
                    "停止" -> stopJob(j)
                    "归档" -> archiveJob(j)
                    "恢复" -> WorkJobStore.unarchive(j.id, System.currentTimeMillis()).let { refreshJobs() }
                    else -> deleteForever(j)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun stopJob(j: WorkJob) {
        val tok = cancels[j.id]
        if (tok == null) {
            // ★ 没有一个活着的令牌 = 这一轮早就没了（进程被杀、线程异常退出）。
            //   档案却还写着 running，于是「接着说」被拒、「停止」只会回一句「没在跑」——
            //   这条路是死的。就地把它改成「已停」，这件活才拿得回来。
            WorkJobStore.save(
                j.copy(status = WorkJobMath.ST_STOPPED, updatedAt = System.currentTimeMillis())
            )
            refreshJobs()
            toast("该轮次已终止，状态已更新为「已停止」。")
            return
        }
        // ★★ 必须走 abort 而不是 tok.set(true)：模型在**思考**的时候一行都不吐，
        //    只按令牌要等它想完才生效 —— 那就是他说的「一直在思考，停都停不下来」。
        //    abort 会把在飞的那条 HTTP 连接直接掐断。
        AiAgentHolder.get(this).abort(tok)
        // ★ 停活也要闭嘴：活停了、声音还在念上一段的结论，会让人以为它没停。
        HerSpeak.stop()
        toast("已停止：" + j.title)
    }

    private fun archiveJob(j: WorkJob) {
        // ★ 归档**不删任何东西**：账在事件表、全文在步骤账本、会话在 job 文件里，
        //   一样都不动 —— 所以「恢复」能把整件事原样拿回来。
        cancels[j.id]?.let { AiAgentHolder.get(this).abort(it) }
        WorkJobStore.archive(j.id, System.currentTimeMillis())
        if (curJob == j.id) startNewJob() else refreshJobs()
    }

    /**
     * **彻底删**：这件活的档案、它的整本账、它每一步的全文，一起删。
     *
     * ★ 三样都要删。只删档案 = 屏上没了、盘上还留着几十个谁也指不到的文件；
     *   只删账不删全文 = 留下大段正文。三样各有各的删除入口，顺序无所谓。
     */
    private fun deleteForever(j: WorkJob) {
        AlertDialog.Builder(this, R.style.Theme_Touchpad_GlassDialogDark)
            .setTitle("彻底删除")
            .setMessage(
                "「" + j.title + "」的全部记录与每一步的全文将一并删除，无法恢复。" +
                    "\n\n如仅需从列表中移除，请使用「归档」。"
            )
            .setPositiveButton("删除") { _, _ ->
                cancels[j.id]?.let { AiAgentHolder.get(this).abort(it) }
                for (e in AgentBus.of(j.id)) StepStore.remove(e.id)
                AgentBus.purge(j.id)
                WorkJobStore.remove(j.id)
                if (curJob == j.id) startNewJob() else refreshJobs()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 代号 `WX-01` 里的那个序号：按**创建先后**数，含归档的。归档不重排。 */
    private fun codeIndex(id: String): Int {
        val all = WorkJobStore.all().sortedBy { it.createdAt }
        val i = all.indexOfFirst { it.id == id }
        return if (i < 0) 0 else i
    }

    /** 把盘上那几件活的现状推给任务条。 */
    private fun refreshJobs() {
        jobs.clear()
        jobs.addAll(WorkJobStore.all())
        val sorted = jobs.sortedBy { it.createdAt }
        val arr = JSONArray()
        for (j in sorted) {
            arr.put(
                JSONObject()
                    .put("id", j.id)
                    .put("code", WorkJobMath.codeOf(sorted.indexOf(j)))
                    .put("status", j.status)
                    .put("label", WorkJobMath.statusLabel(j.status))
                    .put("title", j.title)
                    .put("archived", j.archived)
            )
        }
        web?.setJobs(arr.toString(), curJob)
    }

    // ------------------------------------------------------------------
    // 代码：沙盒里跑一条命令
    // ------------------------------------------------------------------

    /**
     * 在沙盒里跑一条命令，结果落进**同一条账**。
     *
     * ★ 为什么也写成「派活 + 回话」两条事件，而不是直接弹一个输出框：
     *   他这一屏要的是「一条时间线」—— 跑过什么、成了没有，和派出去那几份活
     *   排在**同一张列表**里才看得出先后。弹框是「一次性」的，关掉就没了。
     *   写成事件之后，它和别的活一样有代号、有读数、点开有全文，也不占额外一套界面。
     *
     * ★ 原文（完整输出）进 [StepStore]，事件里那条只带 2000 字以内的正文——
     *   卡片上的「回来 N 字」读的就是它，所以别在这儿预先摘要掉。
     */
    private fun runCode(cmd: String) {
        // ★ 2026-11-06：跑一条命令也**是一件活**，和派活一样挂到任务条上。
        //   不这么做的话它就是一个没有代号的孤儿：切到别的活再切回来，它就不见了，
        //   而账其实还在盘上 —— 看得见和存得下成了两回事。
        //
        // ★★ 2026-11-08（体检 D3）：补上它缺的两样 —— **并发闸**和**停止令牌**。
        //   原来这条路上：开几件都不挡（一条命令可以跑满 CODE_TIMEOUT_SEC），
        //   「停止」按下去只会把档案改成「已停止」而命令还在跑，跑完又用旧快照
        //   把状态写回「完成」—— 三步都是假的。
        if (liveJobs.size >= WorkJobMath.MAX_ACTIVE) {
            toast("同时最多 ${WorkJobMath.MAX_ACTIVE} 件，等一件跑完再来。")
            return
        }
        val job = openJob(cmd) ?: return
        val tok = AtomicBoolean(false)
        cancels[job.id] = tok
        liveJobs.add(job.id)
        WorkJobStore.save(job.copy(status = WorkJobMath.ST_RUNNING, updatedAt = System.currentTimeMillis()))
        refreshJobs()
        taskInput.setText("")
        status.visibility = View.VISIBLE
        status.text = "沙盒里跑…"

        val ask = AgentBus.emit(job.id, AgentBusModel.HUB, ROLE_SANDBOX, AgentBusModel.KIND_ASK, cmd)
        StepStore.save(ask.id, "沙盒 · 命令", cmd)

        Thread {
            val out = try {
                Sandbox.exec(
                    "linux",
                    JSONObject().put("cmd", cmd).put("timeout", CODE_TIMEOUT_SEC),
                )
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
            }
            val ok = out.optBoolean("ok")
            val body = codeReport(out)
            val head = if (ok) codeHeadline(out) else out.optString("error").ifBlank { "没跑成" }
            val back = AgentBus.emit(
                job.id, AgentBusModel.LOCAL, ROLE_SANDBOX,
                if (ok) AgentBusModel.KIND_ANSWER else AgentBusModel.KIND_ERROR,
                body, ask.id,
            )
            StepStore.save(back.id, "沙盒 · 输出", body)
            // ★★ 2026-11-08（体检 D3）：**先重读、再看令牌**，不能用开跑前那份快照。
            //   · 重读：他中途归档/删掉了这件活，写回旧快照会把它**复活**；
            //   · 令牌：他中途按了「停止」，命令可能还跑到了底 —— 那种情况下
            //     状态必须是「已停止」，不能翻回「完成」，成品也不该再写进会话。
            val cur = WorkJobStore.find(job.id)
            if (cur != null) {
                val stopped = tok.get()
                WorkJobStore.save(
                    cur.copy(
                        status = when {
                            stopped -> WorkJobMath.ST_STOPPED
                            ok -> WorkJobMath.ST_DONE
                            else -> WorkJobMath.ST_FAILED
                        },
                        updatedAt = System.currentTimeMillis(),
                        // 会话里也留一条成品：下一句「接着说」才有上下文。
                        turns = if (stopped) cur.turns
                        else WorkJobMath.appendTurn(cur.turns, WorkTurn.ASSISTANT, body),
                    )
                )
            }

            liveJobs.remove(job.id)
            runOnUiThread {
                // ★ 令牌跟着活一起摘（同 [run]）：留着的话下一次「停止」会按到一个
                //   已经没人看的令牌上，看起来像按了没反应。
                cancels.remove(job.id)
                refreshJobs()
                status.text = if (tok.get()) "已停止" else head
                status.visibility = if (status.text.isBlank()) View.GONE else View.VISIBLE
            }
        }.start()
    }

    /**
     * 代码这一档的另一半：**说一件事，它自己在 `/root/work` 里做出来**。
     *
     * ★ 和 [runCode] 的差别只有一个，但是根本的：那是「我让它跑一条命令」，
     *   这是「我给它一件事」。所以它要开一个线程跑一个**循环**（见 [AiAgent.runCoding]），
     *   而不是一条 [Sandbox.exec]。停止令牌照旧挂上 —— 循环里每一步都会看它。
     */
    private fun runCodingJob(what: String) {
        val job = openJob(what) ?: return
        val tok = AtomicBoolean(false)
        cancels[job.id] = tok
        liveJobs.add(job.id)
        taskInput.setText("")
        status.visibility = View.VISIBLE
        status.text = "正在执行…"
        refreshJobs()

        val agent = AiAgentHolder.get(this)
        val jobId = job.id
        val attachText = WorkAttachMath.block(picks, WORK_DIR)
        Thread {
            val out = try {
                agent.runCoding(this, jobId, what, tok, cb, attachText)
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
            }
            liveJobs.remove(jobId)
            unstuck(jobId, out)
            runOnUiThread {
                // ★ 令牌在这里摘掉（同 [run]）：留着的话下一次「停止」会按到没人看的令牌上。
                cancels.remove(jobId)
                refreshJobs()
                val head = when {
                    // ★ 做完了得**告诉他东西在哪** —— 他不知道工作区这个东西存在，
                    //   就会以为「又只给了一个界面」。见 ConMarnActivity.showWorkArea。
                    out.optBoolean("ok") -> "做完了 · 成品在她房间 ⚙ → 工作区（点开是全屏的，也能放到桌面）"
                    out.optBoolean("stopped") -> "已停止"
                    else -> out.optString("error").ifBlank { "执行失败" }
                }
                status.text = head
                status.visibility = if (head.isBlank()) View.GONE else View.VISIBLE
                showResult(out)
            }
        }.start()
    }

    /** 卡片上不需要额外摘要 —— 正文本身就是读数。这行只给状态条用。 */
    private fun codeHeadline(out: JSONObject): String {
        if (out.optBoolean("timedout")) return "超时了（${CODE_TIMEOUT_SEC}s），已经杀掉"
        val code = out.optInt("exit", -1)
        return if (code == 0) "退出码 0" else "退出码 $code"
    }

    /** 完整输出：退出码 + stdout + stderr，一行不吞。 */
    private fun codeReport(out: JSONObject): String {
        if (!out.optBoolean("ok")) {
            return "没跑成：" + out.optString("error").ifBlank { "沙盒没起来" }
        }
        val sb = StringBuilder()
        sb.append("退出码 ").append(out.optInt("exit", -1))
        if (out.optBoolean("timedout")) sb.append("（超时被杀）")
        sb.append('\n')
        val so = out.optString("stdout")
        val se = out.optString("stderr")
        if (so.isNotBlank()) sb.append('\n').append(so.trimEnd()).append('\n')
        if (se.isNotBlank()) sb.append("\n[stderr]\n").append(se.trimEnd()).append('\n')
        if (so.isBlank() && se.isBlank()) sb.append("\n(没有输出)\n")
        return sb.toString().trimEnd()
    }

    /** 结果：能直接用的那段（接力的成品 / 并行的对账）单独弹一次。 */
    private fun showResult(out: JSONObject) {
        if (!out.optBoolean("ok")) {
            val err = out.optString("error").ifBlank { "没跑成" }
            dialog("没跑成", err + (out.optString("partial").takeIf { it.isNotBlank() }
                ?.let { "\n\n已经有的一段：\n" + it } ?: ""))
            // ★ 失败也要出声，而且**念短的**：他多半不在看屏。
            HerSpeak.say(this, "没跑成。" + err)
            return
        }
        // ★ 优先给人**成品**（几段合起来的那份）；合成没成才退回几段原文 / 对账记录。
        val body = out.optString("final").ifBlank { out.optString("reconcile") }
        if (body.isBlank()) return
        dialog(if (chain) "这一段做完了" else "成品", body)
        // ★ 2026-11-06 他要的：「最终结果能发声」。
        HerSpeak.say(this, speakable(body))
    }

    /**
     * 把成品改成**能念的**那一段。
     *
     * ★ 成品是写给眼睛看的：可能有小标题、表格、代码块、星号加粗。这些念出来是噪音 ——
     *   表格会变成一串竖线，代码块会念出十几个变量名。所以先过一遍：
     *   围栏代码整段丢掉、表格行整行丢掉、标题和列表符号去掉、`**` 与反引号去掉。
     * ★★ **整篇念完，不截断**（他 2026-11-06 定的：「可以等，但是得一次性读出来」）。
     *   合成是边合成边放，等待只体现在开头那一小段；截断反而让他听到一半没了下文。
     */
    private fun speakable(raw: String): String {
        val sb = StringBuilder()
        var inFence = false
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.startsWith("```")) { inFence = !inFence; continue }
            if (inFence) continue
            if (t.isEmpty()) { if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n'); continue }
            // 表格：整行都是竖线分出来的格子，念出来只有噪音。
            if (t.startsWith("|") || t.count { it == '|' } >= 2) continue
            // 分隔线。
            if (t.all { it == '-' || it == '*' || it == '_' || it == ' ' }) continue
            sb.append(
                t.trimStart('#', '>', '-', '*', '+', ' ')
            ).append('。').append('\n')
        }
        val text = sb.toString()
            .replace("**", "")
            .replace("`", "")
            .replace("\n\n", "\n")
            .trim()
        return text
    }

    /**
     * 成品窗。
     *
     * ★★ **关掉它必须让她闭嘴**（他 2026-11-06 报的：「退出成品界面，就必须让 agent
     *   闭嘴，不然关都关不了声音」）。所以关闭和发声绑在同一件事上：窗没了，声音就停。
     *   不绑的话，一段上千字的成品会在他已经看完之后再念几分钟，而他没有任何按钮能止住。
     */
    private fun dialog(title: String, body: String) {
        // ★★ 2026-11-08:人已经退出去了就别再弹。
        //   活是普通 Thread,Activity 销毁了照跑;跑完 `runOnUiThread` 仍会在主线程执行,
        //   而 `Dialog.show()` 拿一个已经失效的 window token 会抛 BadTokenException ——
        //   症状正是「跑着一段活,顺手退出去看别的,过一会儿自己崩了」。
        //   ★ 类里的 `copyIn`(:600)早就有这层保护,这里当时漏了。
        if (isFinishing || isDestroyed) return
        val tv = TextView(this).apply {
            text = body
            textSize = 12.5f
            setTextColor(0xFFE2F5EE.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(4))
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this, R.style.Theme_Touchpad_GlassDialogDark)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("关闭", null)
            .setOnDismissListener { HerSpeak.stop() }
            .show()
    }

    // ------------------------------------------------------------------
    // 回调：AiAgent 要的那些口子
    // ------------------------------------------------------------------

    private val cb = object : AiAgent.Callback {
        override fun onLog(msg: String) {
            runOnUiThread {
                status.visibility = View.VISIBLE
                status.text = msg
                // ★ 顺带把任务条刷一遍：状态（排队 → 在跑 → 完成）是 [AiAgent.runWorkbench]
                //   在后台改的，这里不刷的话那一格会一直停在「排队」，直到整件活跑完。
                //   内容没变时页面那边会丢包（见 `window.dashJobs` 的去重），所以不会闪。
                refreshJobs()
            }
        }

        override fun onTrace(msg: String) {
            ModelManager.get(applicationContext).trace("工作台: $msg")
        }

        override fun onFinal(msg: String) {
            runOnUiThread { if (msg.isNotBlank()) status.text = msg.take(120) }
        }

        override fun onError(msg: String) {
            runOnUiThread { status.visibility = View.VISIBLE; status.text = msg; toast(msg) }
        }

        override fun onBusy(busy: Boolean) {
            runOnUiThread {
                runBtn.isEnabled = !busy
                runBtn.text = if (busy) "在跑" else "开跑"
            }
        }

        /**
         * 「逐步」档要点头的那一下。
         *
         * ★ 这是**花钱/出门前**的问话（见 [ConfirmMath]），不能因为换了个界面就自动放行。
         *   这个方法和她的房间那条一样是**阻塞**的：后台线程停在这儿，等界面上点完再走。
         *   60 秒没人碰就按「没人应」返回 —— 不是同意。
         */
        override fun onConfirm(summary: String, detail: String): Confirm {
            val latch = CountDownLatch(1)
            val res = AtomicReference(Confirm.NO_UI)
            runOnUiThread {
                try {
                    AlertDialog.Builder(this@WorkbenchActivity, R.style.Theme_Touchpad_GlassDialogDark)
                        .setTitle(summary)
                        .setMessage(detail)
                        .setPositiveButton("确认") { _, _ ->
                            res.set(Confirm.APPROVED); latch.countDown()
                        }
                        .setNegativeButton("取消") { _, _ ->
                            res.set(Confirm.DENIED); latch.countDown()
                        }
                        .setOnCancelListener {
                            res.set(Confirm.DENIED); latch.countDown()
                        }
                        .show()
                } catch (e: Exception) {
                    res.set(Confirm.NO_UI); latch.countDown()
                }
            }
            latch.await(60, TimeUnit.SECONDS)
            return res.get()
        }
    }

    // ------------------------------------------------------------------
    // 手势：双指左滑回房间
    // ------------------------------------------------------------------

    private var twoX = 0f
    private var twoY = 0f
    private var twoOn = false
    private var twoT0 = 0L

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_POINTER_DOWN ->
                if (ev.pointerCount >= 2) {
                    twoX = ev.getX(0); twoY = ev.getY(0)
                    twoOn = true; twoT0 = System.currentTimeMillis()
                }
            android.view.MotionEvent.ACTION_MOVE ->
                if (twoOn && ev.pointerCount >= 2) {
                    val dx = ev.getX(0) - twoX
                    val dy = ev.getY(0) - twoY
                    // ★ 左滑 = 回房间（进来是右滑，出去是左滑，方向对称才记得住）
                    if (dx < -resources.displayMetrics.widthPixels * 0.26f &&
                        Math.abs(dy) < resources.displayMetrics.heightPixels * 0.22f &&
                        System.currentTimeMillis() - twoT0 < 900
                    ) {
                        twoOn = false
                        finish()
                        return true
                    }
                }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> twoOn = false
        }
        return super.dispatchTouchEvent(ev)
    }

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

    /**
     * 点开一行 → 任务和回答**一起**给。
     *
     * ★ 屏上那一行只有代号和读数（`01 编辑 · 回来 22 字 · 4.0s`），
     *   东西没少，只是叠起来了 —— 点开就是把它摊平。配对规则见 [StepDetail]。
     */
    private fun showStep(id: Int) {
        // ★ 配对要在**同一件活**的账里做（[StepDetail] 是拿 ask/answer 配对的）：
        //   跨件配对会配出一对毫无关系的「问」和「答」，而且看起来完全正常。
        val mine = if (curJob.isNotEmpty()) AgentBus.recentOf(curJob, DashWeb.SHOW_EVENTS)
        else AgentBus.recentWith("w", DashWeb.SHOW_EVENTS)
        val shown = StepDetail.of(mine, id.toLong())
        dialog(shown.title, shown.body)
    }

    private fun loadRoles(key: String, def: List<String>): List<String> {
        val s = getSharedPreferences(PREFS, MODE_PRIVATE).getString(key, null) ?: return def
        val list = s.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return if (list.isEmpty()) def else list
    }

    private fun saveRoles(key: String, list: List<String>) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(key, list.joinToString("\n")).apply()
    }

    /**
     * 收起上下两条系统栏，划一下临时唤出。
     *
     * ★ 2026-11-05 他点名：「最顶上的系统状态栏需要自动收回」。和她的房间
     *   （[ConMarnActivity.enterImmersive]）用同一套，不是 `windowFullscreen`：
     *   那种是硬的，想看一眼时间就得退出去。
     * ★ 弹过对话框之后系统栏会自己回来，所以**每次拿回焦点都再收一次**。
     */
    private fun enterImmersive() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    /**
     * 门口那道锁 —— 和她的房间**同一道、同一套判据**（[AppGate.needsUnlock]：
     * 冷启动一次、熄屏回来一次）。
     *
     * ★ 2026-11-06 他报的那条：工作台息屏再开不用指纹。原因是这道锁只装在
     *   [ConMarnActivity.onResume] 上，而息屏时在前台的是**这个** Activity，
     *   亮屏回来调的是这里的 onResume —— 房间那一处根本没被调到。
     *
     * ★ 为什么先盖一层底色再验：系统那个指纹弹窗**是半透明的**，底下看得见
     *   时间线和任务条。不盖的话「验了」和「没验」看到的是同一幅画面。
     * ★ 验不过（按取消 / 弹不出来）就退出工作台，不是留在一个空壳里 ——
     *   留在原地等于告诉人「没按也进来了」。
     */
    override fun onResume() {
        super.onResume()
        // ★ 工作台在前台时，她需要弹系统权限框（写系统日程）也是从这里弹。
        //   房间让位时已经把自己的引用摘掉了（见 ConMarnActivity.onPause）。
        SelfHand.hostActivity = java.lang.ref.WeakReference(this)
        if (AppGate.needsUnlock(this)) {
            coverForGate()
            AppGate.ask(
                this,
                "确认是你",
                "打开工作台前先验证一次",
                onPass = { uncoverGate() },
                onFail = { finish() },
            )
        }
    }

    override fun onPause() {
        // ★ 出前台就没有「能弹系统权限框的界面」了 —— 摘掉引用，别让后台的活
        //   以为还能问（见 SelfHand.hostActivity）。
        SelfHand.hostActivity = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 日历权限（写系统日程，见 CalHand）：结果由那一头唤醒等着它的线程。
        if (CalHand.onResult(requestCode, grantResults)) return
    }

    /** 盖一层跟工作台底色一样的黑底（重复调用安全）。 */
    private fun coverForGate() {
        if (gateCover != null) return
        val root = rootView as? android.view.ViewGroup ?: return
        val v = View(this).apply {
            setBackgroundColor(BG)
            isClickable = true          // 吃掉底下的触摸：验之前点不着任何东西
        }
        root.addView(v, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        gateCover = v
    }

    private fun uncoverGate() {
        gateCover?.let { (rootView as? android.view.ViewGroup)?.removeView(it) }
        gateCover = null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        // ★ WebView 不跟着父视图一起释放，关掉时得自己收（和 DashWeb 头注释同一个理由）。
        web?.dispose()
        web = null
        // ★★ 离开工作台就闭嘴：发声不跟着 Activity 的寿命，他退出去之后还念着，
        //   而且**没有任何界面能把声音关掉**（2026-11-06 报的）。
        HerSpeak.stop()
        super.onDestroy()
    }

    private companion object {
        /**
         * 这个**进程**里正在跑的活。
         *
         * ★ 判据必须挂在进程上，不能挂在这一个 Activity 上：实例字段每次重建都是空的，
         *   拿它当「有没有在跑」的判据，会把真的在跑的活误判成「上次没跑完」。
         */
        val liveJobs: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())

        /**
         * 每一件活的**停止令牌**（一件一枚）。
         *
         * ★ 一件一个：按「停止」只停这一件。全局那一枚（[AiAgent] 的 `cancelFlag`）
         *   在这儿不能用 —— 那会把另外几件一起掐掉。
         *
         * ★★ 2026-11-08（体检 D1）：从 Activity 的实例字段搬到这儿。
         *   理由和 [liveJobs] 完全一样，而且更狠：令牌一丢，「停止」就只剩把档案
         *   改成「已停止」这一半 —— 活还在飞，跑完还会把状态覆盖回去。
         */
        val cancels = java.util.concurrent.ConcurrentHashMap<String, AtomicBoolean>()

        /**
         * 左栏（场 + 读数）占屏宽多少 —— **必须和 `assets/dash/dash.css` 里的
         * `--split` 一致**。改一处就得改另一处，否则底栏会压到场上去，或者右栏底下
         * 空出一条没人用的深色带。横屏才有意义（竖屏是上下分，底栏照旧横跨整屏）。
         */
        const val LEFT_PANEL = 0.305f

        const val PREFS = "workbench"
        const val BG = 0xFF05080A.toInt()
        const val DIM = 0xFF7FA99B.toInt()

        const val MODE_FANOUT = 0
        const val MODE_CHAIN = 1
        const val MODE_CODE = 2
        const val MODE_AGENT = 3
        const val MODE_SUPER = 4

        /** 文件选择器的请求码。 */
        const val REQ_PICK = 0x51

        /**
         * 附件在**沙盒里**的目录（`linux` 那一档看到的就是这个路径）。
         *
         * ★ 写死成 `/root/work`：模型要照着这句话去读文件，路径一变它就找不到 ——
         *   而「找不到文件」和「文件是空的」在回执里长得一样。
         */
        const val WORK_DIR = "/root/work"

        /**
         * 沙盒里那条命令的角色名。
         *
         * ★ 单独一个岗位名有实际用处：它在场上会占一个节点、在列表里占一个代号，
         *   所以「跑过代码」和「派给编辑/审稿人」在同一屏上分得开。
         */
        const val ROLE_SANDBOX = "沙盒"

        /** 一条命令最多跑多久。装包（`apk add python3`）也要够。 */
        const val CODE_TIMEOUT_SEC = "60"
    }
}
