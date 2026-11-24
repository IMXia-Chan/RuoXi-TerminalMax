package com.example.touchpad

/**
 * 点开一行之后要显示的东西：**任务**和**回答**两段一起给。
 *
 * ---
 *
 * ## 为什么是两段
 *
 * 2026-11-05 大屏改成「一行读数」（`01 编辑 · 回来 22 字 · 4.0s`）之后，
 * 铺在屏上的任务原文和回答全文都收了进去。收进去没问题，**点开必须两样都有**——
 * 只给任务，等于让他自己猜「这一行到底做成了没有」；只给回答，等于给出的东西来路不明。
 *
 * ## 回答怎么找
 *
 * 一张卡在事件流里其实是**两条**：派活（`ask`）和回话（`answer` / `error`）。
 * 回话那条用 `ref` 指回派活的编号 —— 所以拿派活的编号反查一次就有了，
 * 不需要网页多传一个字段。（`dash-core.js` 的 `build()` 配的是同一对。）
 *
 * ## 没有完整记录时说清楚
 *
 * [StepStore] 只留最近 200 步。翻到老记录时读不到原文，就退回事件流那一行，
 * 并**明说是哪一种**：让他知道手里这份是摘要，不是全部。这条规矩和一进来时
 * 「多 agent 是黑盒」那笔账是同一件事的两个面。
 */
internal object StepDetail {

    data class Shown(val title: String, val body: String)

    fun of(events: List<AgentEvent>, id: Long): Shown {
        val ask = events.firstOrNull { it.id == id }
        val back = events.firstOrNull {
            it.ref == id && (it.kind == AgentBusModel.KIND_ANSWER || it.kind == AgentBusModel.KIND_ERROR)
        }
        if (ask == null && back == null) {
            return Shown("#$id", "这条已经滑出记录，没有完整原文。")
        }

        val sb = StringBuilder()
        sb.append("【任务】\n")
        sb.append(StepStore.read(id) ?: ask?.text.orEmpty().ifBlank { "（没有留下任务原文）" })
        sb.append("\n\n")
        if (back == null) {
            sb.append("【回答】\n（还在跑，没有回来）")
        } else {
            sb.append(if (back.kind == AgentBusModel.KIND_ERROR) "【出错】\n" else "【回答】\n")
            sb.append(StepStore.read(back.id) ?: back.text.ifBlank { "（没有留下回答原文）" })
        }

        val who = ask?.role.orEmpty().ifBlank { back?.role.orEmpty() }
        val title = if (who.isBlank()) "#$id" else "$who · #$id"
        return Shown(title, sb.toString())
    }
}
