package com.example.touchpad

/**
 * AI 对话框里那条「正在生成」的行 —— **整段覆盖**,不是追加。
 *
 * 为什么要有这么一行:本地模型约 7 token/秒,一轮一百个 token 要十几秒。在这之前
 * 界面上一个字都不动,用户看到的就是「卡住了」。流式本来每生成一个 token 就推一行
 * 过来,报出来就能把这十几秒填上(见 `AiAgent.Callback.onPartial`)。
 *
 * 为什么抽成纯函数:它是个小状态机(start / 覆盖 / 撤掉 / 顶下去),而结果只能靠
 * 肉眼在对话框里看 —— 一旦写错,表现是「文字重复一遍」或者「最后一行缺字」这种
 * 不疼不痒但一直碍眼的东西。抽出来单测钉住,比每次改完手动点一遍靠谱。
 *
 * @param text      当前整个日志框的内容。
 * @param liveStart 这一行在 [text] 里的起点;`-1` = 当前没有这样一行。
 * @param partial   模型此刻写到的全文。**空串 = 把这一行撤掉**(这一轮其实是去调
 *                  工具了,那半句话不是给用户的答复)。
 * @return 新的 [text] 和新的 liveStart。
 */
internal fun liveLineUpdate(text: String, liveStart: Int, partial: String): Pair<String, Int> {
    // 撤掉:掐回这一行的起点。没有这样一行时(或下标已经失效)原样不动。
    if (partial.isEmpty()) {
        val end = if (liveStart in 0..text.length) liveStart else text.length
        return text.substring(0, end) to -1
    }

    var base = text
    var start = liveStart
    if (start < 0 || start > base.length) {
        // 新起一行:先补个换行,免得贴在上一句后面。
        if (base.isNotEmpty() && !base.endsWith("\n")) base += "\n"
        start = base.length
    }
    return (base.substring(0, start) + LIVE_PREFIX + partial) to start
}

/** 那条预览行的前缀。**不参与 liveStart 的计算** —— 起点永远指向前缀之前。 */
internal const val LIVE_PREFIX = "✍ "
