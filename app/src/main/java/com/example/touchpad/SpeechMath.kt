package com.example.touchpad

/**
 * 「哪些字该念,哪些字只是个提示」—— **纯逻辑,零 Android 依赖,能跑 JVM 单测。**
 *
 * ## 它治的是什么
 *
 * 模型吐出来的是**剧本**,不是台词。她要是照单全念,你会听见:
 *
 * > 星号叹气星号 …… 我没事
 *
 * 「叹气」两个字被念出来,而她本人没有叹气。这不是口音问题、不是音色问题,
 * 是**把舞台指示当成了台词** —— 听感上就是「说话感觉怪得要死」里最直的那一种。
 *
 * ## ★★ 判错的代价不对称,所以整条规则往「少剥」那边倒
 *
 * | 判错的方向 | 后果 |
 * |---|---|
 * | **漏剥**(该剥没剥) | 多念两个字,难听一下 |
 * | **错剥**(不该剥却剥了) | **把她说的话吃掉一截**,而且**没有任何提示** |
 *
 * 所以:
 *
 * - `*……*` 是聊天里约定俗成的动作写法,**不问内容直接剥**;
 * - 其余括号(`（）` `()` `【】`)在中文里**到处都是真内容**(「(真的)」「【重要】」),
 *   所以**多看一步**:里面得出现一个[动作词][STAGE_WORDS]才认。
 *   这一步是照 AIRI 的 `isProbablyAngleTag` 那个思路写的 —— 用语义兜住纯形状的歧义。
 * - `[…]` 和 `<…>` **一律不剥**:它们和数组下标、泛型、HTML 撞得太狠,
 *   而这三样在这台机器上是真会出现的东西(`list_ui` 的回执、网页正文)。
 *   ★ 计划里列了它们,这里是**故意收窄** —— 见上面那张表。
 *
 * ## 剥完不能变成空的
 *
 * 她整句就是 `（叹气）` 的话,剥完什么都不剩。**那时退回原文** ——
 * 念一句「叹气」总比一声不吭强:下游的对话循环挂在「她念完了」那个回调上,
 * 而「她不出声」在这个项目里的症状是「她不理我了」,查起来比难听难受得多。
 */
internal object SpeechMath {

    /**
     * 括号里的东西最长算多长。
     *
     * 超过就不像「一个提示」了,倒像一句真话 —— 而真话要念出来。
     * `（轻轻地叹了口气）` 是 8 个字,留一倍余量。
     */
    private const val MAX_INNER = 16

    /** 括号里出现这些,就说明那不是提示而是**一整句**。 */
    private const val SENTENCE_END = "。！？!?…;；"

    /** 左括号 → 右括号。不在表里的括号**一个都不动**(见文件头)。 */
    private fun closerOf(open: Char): Char? = when (open) {
        '*', '＊' -> '*'          // 全角星号也认 —— 4B 打全角的时候不少
        '（' -> '）'
        '(' -> ')'
        '【' -> '】'
        else -> null
    }

    /**
     * 括号里出现这些词才算「动作」。
     *
     * ★ 中英都收:她的活儿中英混着来(「打开 Edge」),模型偶尔也会整句吐英文。
     * ★ 刻意**不含**「说 / 道 / 问 / 答」这种 —— 它们多半是 `（他说）` 这类
     *   **对话的一部分**,剥掉了句子就不完整了。
     */
    private val STAGE_WORDS = listOf(
        // 动作
        "笑", "叹", "哭", "咳", "喘", "眨", "摇头", "点头", "皱眉", "耸肩", "摊手",
        "摆手", "拍", "抱", "伸", "站", "坐", "转身",
        // 说话的方式
        "沉默", "停顿", "小声", "轻声", "低语", "嘟囔", "喃喃",
        // 停顿/思考
        "犹豫", "想想", "沉吟", "顿了顿",
        // 英文
        "sigh", "laugh", "giggle", "chuckle", "smile", "cry", "sob", "breath",
        "whisper", "pause", "silence", "thought", "shrug", "nod", "smirk",
    )

    /** 连续空白(含全角空格)收成一个 —— 剥掉一个括号常会留下两个空格。 */
    private val SPACES = Regex("[ \t　]{2,}")

    /**
     * 该**念出来**的那一份。原文和结果都是给人念的,不改写、不润色 ——
     * 只做「拿掉提示」这一件事。
     */
    internal fun forSpeech(raw: String): String {
        if (raw.isBlank()) return raw
        var cur = raw
        // 剥到不动为止 —— 一句话里连着两处提示是常事(「(笑)我没事(叹气)」)。
        // 上限 8 轮纯粹是防呆:每轮至少吃掉一对括号,真跑满是不可想象的。
        repeat(8) {
            val next = stripOnce(cur)
            if (next == cur) return finish(cur, raw)
            cur = next
        }
        return finish(cur, raw)
    }

    /** 收尾:空格归一 + 空了就退回原文。 */
    private fun finish(stripped: String, raw: String): String {
        val out = SPACES.replace(stripped, " ").trim()
        return out.ifEmpty { raw.trim() }
    }

    /**
     * 扫一遍,能剥的剥掉,剥不了的原样留下。
     *
     * ★ 配不上对的括号(只剩一个左括号)**原样留着** —— 补一个右括号或者整段丢掉
     *   都是在替模型改字,而这是唯一一件我们绝不该做的事。
     */
    private fun stripOnce(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val open = s[i]
            val close = closerOf(open)
            if (close == null) {
                sb.append(open); i++; continue
            }
            // 全角星号开、半角星号收这种混搭也认,所以收尾字符放宽到两种。
            val j = indexOfCloser(s, i + 1, close)
            if (j < 0) {
                sb.append(open); i++; continue
            }
            if (looksLikeStage(open, s.substring(i + 1, j))) {
                i = j + 1                       // 整段吃掉(含两个括号)
            } else {
                sb.append(s, i, j + 1)          // 原样搬过去
                i = j + 1
            }
        }
        return sb.toString()
    }

    private fun indexOfCloser(s: String, from: Int, close: Char): Int {
        for (k in from until s.length) {
            val c = s[k]
            if (c == close) return k
            if (close == '*' && (c == '*' || c == '＊')) return k
        }
        return -1
    }

    private fun looksLikeStage(open: Char, inner: String): Boolean {
        val t = inner.trim()
        if (t.isEmpty() || t.length > MAX_INNER) return false
        if (t.any { it in SENTENCE_END }) return false
        if (open == '*' || open == '＊') return true        // 约定俗成的动作写法,不问内容
        return STAGE_WORDS.any { t.contains(it) }
    }
}
