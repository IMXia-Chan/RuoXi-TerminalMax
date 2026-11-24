package com.example.touchpad

import android.content.Context
import java.io.File

/**
 * **多 agent 的事件流** —— 一次协同里谁说了什么、依据谁、动没动手，全落在这儿。
 *
 * 它是两件事共同的底座：
 * - **互通**：角色之间不共享上下文，共享这张表（[AgentBusModel.render] 压成一段话递过去）。
 * - **界面**：那个「神经网络数据流」要画的东西，只能来自这里。真实事件有多少画多少 ——
 *   界面是为了让人**看见**，不是为了好看。
 *
 * ## 为什么是 append-only
 *
 * 事后改一条事件，等于改历史。他问「这一步为什么上云了」的时候，答案必须还是当时那个答案。
 * 所以这里只有 [emit]，没有 update/delete。清理只有一种：**整条尾巴太长了，丢最老的**
 * （[AgentBusModel.trimLines]）。
 *
 * ## 落盘那份是明文，不脱敏
 *
 * 见 [AgentBusModel] 头注释：本机的账要查得清。脱敏只发生在出门那一份。
 *
 * ## 监听者
 *
 * 界面打开时挂一个，关掉时摘掉。**不在锁里回调** —— 回调里可能要画东西，
 * 占着这把锁会把 [emit] 的调用方卡住，而调用方可能是她正在说话的那条线程。
 */
internal object AgentBus {

    /** 账本超过这个大小就只留尾部。约 4000~6000 条事件。 */
    private const val MAX_BYTES = 400_000

    /** 内存里留最近多少条。界面只画最近的，不翻全账。 */
    private const val KEEP = 500

    private var file: File? = null
    private val lock = Any()
    private var nextId = 1L
    private val recent = ArrayDeque<AgentEvent>()
    private var listener: ((AgentEvent) -> Unit)? = null

    fun init(ctx: Context) {
        if (file != null) return
        synchronized(lock) {
            if (file != null) return
            try {
                val dir = File(ctx.filesDir, "bus").apply { mkdirs() }
                val f = File(dir, "events.jsonl")
                file = f
                if (f.exists()) {
                    // 只扫 id 和尾部事件。整份读进内存没必要，账本可能有几十万字。
                    val lines = f.readLines()
                    for (l in lines) {
                        val e = AgentBusModel.parse(l) ?: continue
                        if (e.id >= nextId) nextId = e.id + 1
                    }
                    for (l in lines.takeLast(KEEP)) {
                        AgentBusModel.parse(l)?.let { recent.addLast(it) }
                    }
                }
            } catch (_: Exception) {
                // 目录建不出来就当没有落盘能力：事件还在内存里能用，界面照样画得出来。
            }
        }
    }

    /** 一次协同的编号。★ 用时间戳，不用自增 —— 两次任务之间没有先后可比性。 */
    fun newTask(): String = "t" + System.currentTimeMillis()

    /**
     * **超级工作台**的一次任务号。
     *
     * ★ 前缀和她的账不同（`w` 对 `t`），这是两边的**唯一一道分界**：
     *   这张表是同一个（append-only、同一个文件），但谁画谁由前缀决定 ——
     *   她的大屏只看 `t`，工作台只看 `w`。工作台里的活不会长进她的房间，
     *   她的对话也不会长进工作台。
     * ★ 别改成自增或别的形状：前缀就是判据，改前缀等于把两边的账并回去。
     */
    fun newWorkTask(): String = "w" + System.currentTimeMillis() + "-" + workSeq.incrementAndGet()

    /**
     * ★ 2026-11-06：任务号加了自增后缀。
     *
     *   工作台现在能同时开三件，两次「开跑」完全可能落在**同一毫秒**。
     *   撞号之后两件事的事件会落进同一条 `task`，界面上两件事的时间线并成一条,
     *   **而且每一行都还确有其事** —— 这种错查不出来。后缀就是防这个。
     */
    private val workSeq = java.util.concurrent.atomic.AtomicInteger(1)

    /** 只取某一类任务号的事件（按前缀）。界面用它把自己那份账挑出来。 */
    fun recentWith(prefix: String, n: Int = 50): List<AgentEvent> = synchronized(lock) {
        recent.filter { it.task.startsWith(prefix) }.takeLast(n.coerceAtLeast(1))
    }

    /**
     * **某一件活**的最近若干条（按精确任务号）。
     *
     * ★ 和 [recentWith] 的差别就是「前缀」和「整值」：工作台同时挂三件活，
     *   三件的号都以 `w` 开头，`recentWith("w")` 会把三件搅在一起。
     *   这里只从**内存**里筛，不读盘 —— 它每来一条事件就会被调一次。
     */
    fun recentOf(task: String, n: Int = 120): List<AgentEvent> = synchronized(lock) {
        recent.filter { it.task == task }.takeLast(n.coerceAtLeast(1))
    }

    /** 当前这一轮的任务号。由对话那层推开（[com.example.touchpad.AiAgent.beginTrace]）。 */
    @Volatile private var current: String = ""

    fun setCurrent(task: String) {
        current = task
    }

    /**
     * 记一笔到**当前这一轮**。
     *
     * ★ 给「不在对话线程里」的那些出口用（比如 [CloudHand] 调第三方接口）——
     *   它们不知道自己是哪一轮的，但它们做的事必须一样被记下来。
     */
    fun emitHere(from: String, role: String, kind: String, text: String, ref: Long = 0, tok: Int = 0): AgentEvent =
        emit(current, from, role, kind, text, ref, tok)

    /**
     * 记一笔。返回落在账上的那条（带编号）。
     *
     * @param ref 依据哪一条。0 = 起点。**不知道依据谁就填 0，别编号**。
     */
    fun emit(
        task: String,
        from: String,
        role: String,
        kind: String,
        text: String,
        ref: Long = 0,
        tok: Int = 0,
    ): AgentEvent {
        val e: AgentEvent
        val l: ((AgentEvent) -> Unit)?
        synchronized(lock) {
            e = AgentEvent(
                id = nextId++,
                task = task,
                from = from,
                role = role,
                kind = kind,
                ref = ref,
                text = AgentBusModel.clip(text),
                at = System.currentTimeMillis(),
                tok = tok,
            )
            recent.addLast(e)
            while (recent.size > KEEP) recent.removeFirst()
            l = listener
            append(e)
        }
        // ★ 出了锁再回调：界面那边要画东西，别把它串进这条线程的时序里。
        try {
            l?.invoke(e)
        } catch (_: Exception) {
        }
        return e
    }

    private fun append(e: AgentEvent) {
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) {
                // 只在真超了才重写。重写是**整份**的，所以别让它在每条事件上跑。
                val kept = AgentBusModel.trimLines(f.readLines(), MAX_BYTES)
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"))
                if (!tmp.renameTo(f)) {
                    f.writeText(tmp.readText())   // 兜一手（同 HandRegistry.persist）
                    tmp.delete()
                }
            }
            f.appendText(AgentBusModel.line(e) + "\n")
        } catch (_: Exception) {
        }
    }

    /** 最近 [n] 条，**新的在后**（和落盘顺序一致，画出来就是从左往右）。 */
    fun recent(n: Int = 50): List<AgentEvent> = synchronized(lock) {
        recent.toList().takeLast(n.coerceAtLeast(1))
    }

    /** 某一次协同的全部事件（从盘上读，含内存里没有的旧账）。 */
    fun of(task: String): List<AgentEvent> {
        val f = file
        if (f == null || !f.exists()) {
            return synchronized(lock) { recent.filter { it.task == task } }
        }
        return try {
            f.readLines().mapNotNull { AgentBusModel.parse(it) }.filter { it.task == task }
        } catch (_: Exception) {
            synchronized(lock) { recent.filter { it.task == task } }
        }
    }

    fun listen(l: ((AgentEvent) -> Unit)?) {
        synchronized(lock) { listener = l }
    }

    /**
     * 只删**某一个任务号**下的事件。两个调用方：自检（验落盘），和工作台的「彻底删」。
     *
     * ⚠️ 2026-11-06 改了这条注释。原来写的是「唯一的调用方是自检，别的地方别调」——
     * 现在他要的是「不要的那件活能删掉」，而**只把活从任务条上藏起来、账还留在盘上**
     * 等于删了个空。所以：
     *
     * - 允许调用，但**必须点名一个任务号**（`w1751…-2` 这种整值，不是前缀 `w`）；
     * - 整表仍然 append-only：除了这一个号，别的一条都不动。
     *
     * ★ 用前缀调它会一次删掉所有工作台的账 —— 别那么干。
     */
    fun purge(task: String) {
        synchronized(lock) {
            recent.removeAll { it.task == task }
            val f = file ?: return
            try {
                if (!f.exists()) return
                val kept = f.readLines().filter { l ->
                    val e = AgentBusModel.parse(l)
                    e != null && e.task != task
                }
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"))
                if (!tmp.renameTo(f)) {
                    f.writeText(tmp.readText())
                    tmp.delete()
                }
            } catch (_: Exception) {
            }
        }
    }

    /** 只清内存和盘上的账，不动别的东西。自检用。 */
    fun clear() {
        synchronized(lock) {
            recent.clear()
            nextId = 1L
            try {
                file?.delete()
            } catch (_: Exception) {
            }
        }
    }
}
