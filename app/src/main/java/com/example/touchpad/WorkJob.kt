package com.example.touchpad

import org.json.JSONArray
import org.json.JSONObject

/**
 * **一件活** —— 工作台上的一次任务，从「开跑」那一刻起就是一个独立的东西。
 *
 * ★★ 2026-11-06 立的。在这之前工作台是**进程级单槽**：`AiAgent` 里一个 `running`
 *   布尔量 + 一个 `busTask` 字符串 + 一个 `delegates` 计数，两件事同时跑会互相覆盖
 *   （谁最后写 `busTask`，谁的事件就挂到谁名下；`finally` 还会把别人的号写回去）。
 *   他要的是「同时开几件、每件能接着说、不要的能删」—— 三件事都得先有「一件活」这个对象。
 *
 * ## 为什么事件表不加字段
 *
 * [AgentEvent] 是 append-only 的账，落盘在 `filesDir/bus/events.jsonl`，**老账要能照读**。
 * 加一列就等于所有旧行都要能容忍缺列（`AgentBusModel.parse` 现在只认 8/9 列）。
 * 而「这事件属于哪件活」这件事，`task` 字段**本来就有** —— 任务号是精确值，
 * 只是从前永远是同一个。所以这里只把任务号做得更唯一，事件结构一个字节都不动。
 *
 * ## 为什么多轮不写进事件表
 *
 * 事件表记的是「谁做了什么」，一轮对话里模型的输入输出**已经**在账上了
 * （中枢 ask、云端 answer）。再存一遍就成了两份，而且两份迟早不一致。
 * 会话单独放在 [turns] 里，只保留最近 [WorkJobMath.KEEP_TURNS] 轮 ——
 * 它的用途只有一个：下一次接着说的时候拼进请求。
 */
internal data class WorkJob(
    /** 任务号。**和 [AgentEvent.task] 是同一个值** —— 界面靠它对上账。 */
    val id: String,
    /** 一句话标题（第一句用户输入截断来的）。任务条上显示的就是它。 */
    val title: String,
    /** [WorkJobMath.MODE_FANOUT] / [WorkJobMath.MODE_CHAIN] / [WorkJobMath.MODE_CODE] / [WorkJobMath.MODE_AGENT]。 */
    val mode: String,
    /**
     * 这件活由**哪个 agent** 认领（[AgentProfile.id]）。
     *
     * ★ 空串 = 这件活不是 agent 干的。存进档案而不是只放内存：这件活跑完之后，
     *   他在任务条上点回来，得看得见当初是**谁**做的 —— 不然「接着说」等于换个人接手。
     */
    val agent: String = "",
    /** [WorkJobMath.ST_QUEUED] 等五个之一。 */
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** 这件活的会话。`workbench` 模式下每次「接着说」都从这里接着。 */
    val turns: List<WorkTurn> = emptyList(),
    /** 归档 = 从任务条上收起来，但**没删**：账、步骤全文、会话都还在。 */
    val archived: Boolean = false,
    val archivedAt: Long = 0,
) {

    val isRunning: Boolean get() = status == WorkJobMath.ST_RUNNING

    /** 用户说过几句。0 = 还没开始，1 = 第一轮。 */
    val round: Int get() = turns.count { it.role == WorkTurn.USER }

    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (t in turns) arr.put(JSONObject().put("role", t.role).put("text", t.text))
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("mode", mode)
            .put("agent", agent)
            .put("status", status)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
            .put("turns", arr)
            .put("archived", archived)
            .put("archivedAt", archivedAt)
    }

    companion object {

        /** 读坏一条就当没有 —— 一件活的档案坏了，不该让整个任务条消失。 */
        fun fromJson(o: JSONObject?): WorkJob? {
            if (o == null) return null
            val id = o.optString("id").trim()
            if (id.isEmpty()) return null
            val turns = ArrayList<WorkTurn>()
            val arr = o.optJSONArray("turns")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    val role = t.optString("role").trim()
                    if (role != WorkTurn.USER && role != WorkTurn.ASSISTANT) continue
                    turns.add(WorkTurn(role, t.optString("text")))
                }
            }
            return WorkJob(
                id = id,
                title = o.optString("title"),
                mode = WorkJobMath.modeOf(o.optString("mode")),
                agent = o.optString("agent"),
                status = WorkJobMath.statusOf(o.optString("status")),
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
                turns = WorkJobMath.fitTurns(turns),
                archived = o.optBoolean("archived"),
                archivedAt = o.optLong("archivedAt"),
            )
        }
    }
}

/** 会话里的一条。`role` 只有两种：用户说的、和这件活最后成的那份。 */
internal data class WorkTurn(val role: String, val text: String) {
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

/**
 * 一件活身上那些**纯算术**：标题怎么截、会话留几轮、状态怎么走、代号怎么编。
 *
 * ★ 单独抽出来是为了能上单测。这里错了**不报任何错** —— 标题多截两个字、
 *   会话多留两轮，屏上看着都正常，只有下次接着说的话会莫名其妙地忘事。
 */
internal object WorkJobMath {

    const val MODE_FANOUT = "fanout"
    const val MODE_CHAIN = "chain"
    const val MODE_CODE = "code"

    /**
     * 一个**真正的 agent** 自己做这件活：它有名字、职责、自己的记忆和步数预算，
     * 自己多轮推进，收工时把结论写回记忆。见 [AiAgent.runAgent]。
     *
     * ★ 和另外三档的根本差别：那三档是「一次问、一次答」（代码档靠步数循环），
     *   这一档是**同一个人接着上次的自己**做。换档不丢账，但记忆只在 agent 那一档长。
     */
    const val MODE_AGENT = "agent"

    /**
     * **超级**：一个手里什么都有（沙盒 + 两只手 + 读网 + 派活）、一次给足步数的 agent。
     *
     * ★ 和 [MODE_AGENT] 的差别全在能力和闸门上，见 [AiAgent.runSuper]。
     *   单独一个模式值，是因为任务条和「接着说」都要认得它 ——
     *   接着说的那一句还得交给同一档去跑。
     */
    const val MODE_SUPER = "super"

    const val ST_QUEUED = "queued"
    const val ST_RUNNING = "running"
    const val ST_DONE = "done"
    const val ST_FAILED = "failed"
    const val ST_STOPPED = "stopped"

    /**
     * 同时最多跑几件。
     *
     * ★ 他 2026-11-06 拍的 3。再多的话：一件扇出自己就能并发 8 个云端请求
     *   （`AiAgent.MAX_PARALLEL`），3 件 × 8 = 24 个同时在飞，key 和手机都吃不消
     *   —— 所以 `AiAgent` 那边还有一道全局的 10 路闸，超出的排队。
     */
    const val MAX_ACTIVE = 3

    /** 一件活里留几轮会话。6 轮够「再改短一点」「换成中文」这种追问用。 */
    const val KEEP_TURNS = 6

    /** 一屏任务条最多挂几条，再多就靠归档收起来。 */
    const val MAX_JOBS = 30

    /** 标题最长多少字。任务条一条就那么宽。 */
    private const val TITLE_MAX = 24

    fun modeOf(raw: String): String = when (raw) {
        MODE_CHAIN -> MODE_CHAIN
        MODE_CODE -> MODE_CODE
        MODE_AGENT -> MODE_AGENT
        MODE_SUPER -> MODE_SUPER
        else -> MODE_FANOUT
    }

    fun statusOf(raw: String): String = when (raw) {
        ST_RUNNING, ST_DONE, ST_FAILED, ST_STOPPED -> raw
        else -> ST_QUEUED
    }

    /** 任务条上的字。 */
    fun statusLabel(status: String): String = when (status) {
        ST_RUNNING -> "在跑"
        ST_DONE -> "完成"
        ST_FAILED -> "没成"
        ST_STOPPED -> "已停"
        else -> "排队"
    }

    /** 代号：`WX-01`。按**未归档**的先后顺序编，从 1 开始。 */
    fun codeOf(index: Int): String = "WX-" + (index + 1).toString().padStart(2, '0')

    /**
     * 第一句话变成标题。
     *
     * ★ 换行和连续空白先压成一个空格：任务条是一行，标题里带换行会把条撑高。
     */
    fun titleOf(text: String): String {
        val flat = text.trim().replace(Regex("\\s+"), " ")
        if (flat.length <= TITLE_MAX) return flat
        return flat.take(TITLE_MAX) + "…"
    }

    /**
     * 会话涨到 `KEEP_TURNS` 轮以上就从头裁。
     *
     * ★ 判据是「超过」不是「等于」—— 和 [HistoryWindow] 同一个道理，判等会让它
     *   每轮都裁一次。这里裁掉的是**最老的一轮**，不是一次性裁到只剩两轮：
     *   工作台整条路都走云端，没有本地前缀缓存那笔账，不需要那么狠。
     */
    fun fitTurns(turns: List<WorkTurn>): List<WorkTurn> {
        val keep = KEEP_TURNS * 2
        if (turns.size <= keep) return turns
        return turns.subList(turns.size - keep, turns.size).toList()
    }

    fun appendTurn(turns: List<WorkTurn>, role: String, text: String): List<WorkTurn> {
        val out = ArrayList<WorkTurn>(turns.size + 1)
        out.addAll(turns)
        out.add(WorkTurn(role, text.trim()))
        return fitTurns(out)
    }

    /**
     * 会话拼成请求里的 `messages` 前缀。
     *
     * ★ 空的一条都不进：空 assistant 会让某些兼容端点直接 400。
     */
    fun historyMessages(turns: List<WorkTurn>): List<WorkTurn> =
        turns.filter { it.text.isNotBlank() }

    /** 任务条的顺序：未归档的在前，各自按创建时间正序（老的左、新的右）。 */
    fun ordered(all: List<WorkJob>, archived: Boolean): List<WorkJob> =
        all.filter { it.archived == archived }.sortedBy { it.createdAt }

    /** 还能不能再开一件。 */
    fun canStart(active: Int): Boolean = active < MAX_ACTIVE

    /** 屏上还挂着几条（含在跑的）。超了就先让他归档。 */
    fun atCapacity(all: List<WorkJob>): Boolean = all.count { !it.archived } >= MAX_JOBS
}
