package com.example.touchpad

/**
 * 「他打的字」→「关键词模型认识的 token」。纯函数，能跑 JVM 单测。
 *
 * ## 为什么需要这一层
 *
 * 唤醒词**不是训练出来的**：sherpa-onnx 的关键词检测吃的是一行**拼音 token**，
 * 比如现在盘上这一行：
 *
 * ```
 * c óng m àn @从漫
 * ```
 *
 * 所以「让用户自己输入唤醒词」这件事，落到地上就是**把汉字翻成这一串 token**。
 * 翻不了的地方必须**说清楚是哪个字翻不了**，不能静默跳过 —— 少一个 token，
 * 这行关键词就永远是死的，而症状和「他喊的声音不对」一模一样。
 *
 * ## 词表从哪来
 *
 * 不是硬编码的：读模型目录里那份 `tokens.txt`（227 个，声母 + 带调的韵母）。
 * 拿词表当判据的好处是**翻不出来当场就知道** —— 不用等它不响。
 *
 * ## 一个汉字的翻法
 *
 * 1. 用系统 ICU 的 `Han-Latin` 逐字转拼音（带声调，`从` → `cóng`）；
 * 2. 这个音节要么**整个**就是一个韵母（安 = `ān`），
 *    要么**声母 + 韵母**（从 = `c` + `óng`）—— 两种都必须在词表里。
 *
 * 逐字转而不是整词转，是为了绕开分词：整词转出来的是一串连在一起的拼音，
 * 还要再猜音节边界；逐字转每个字正好一个音节，边界天然确定。
 */
internal object WakeTokenMath {

    /**
     * 声母表。**长的放前面**（`zh`/`ch`/`sh` 必须先于 `z`/`c`/`s` 试，
     * 否则 `zhōng` 会被切成 `z` + `hōng`，而 `hōng` 不在词表里，翻不出来）。
     */
    private val INITIALS = listOf(
        "zh", "ch", "sh",
        "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h",
        "j", "q", "x", "r", "z", "c", "s", "y", "w",
    )

    /**
     * 一个音节 → 1 个或 2 个 token。翻不出来回 null。
     *
     * @param syllable 已经小写、带声调、且**不含空格**（`cóng` / `ān` / `zhōng`）
     */
    fun parseOne(syllable: String, vocab: Set<String>): List<String>? {
        if (syllable.isEmpty()) return null
        // ① 整个音节就是一个韵母 —— 没有声母的字（安、爱、欧）
        if (syllable in vocab) return listOf(syllable)
        // ② 声母 + 韵母
        for (ini in INITIALS) {
            if (!syllable.startsWith(ini)) continue
            if (ini !in vocab) continue
            val fin = syllable.substring(ini.length)
            if (fin in vocab) return listOf(ini, fin)
        }
        return null
    }

    /**
     * 一个词 → token 串。翻不出来时回**第一个翻不了的那个字**（给它的人看）。
     *
     * @param word      用户打的词（汉字）
     * @param vocab     tokens.txt 里的词表
     * @param pinyinOf  一个字 → 带调拼音（测试里注入假实现，真机上是 ICU 的 Han-Latin）
     */
    fun toTokens(
        word: String,
        vocab: Set<String>,
        pinyinOf: (String) -> String,
    ): Result<List<String>> {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return Result.failure(WakeTokenError("唤醒词不能是空的", null))
        if (trimmed.length < MIN_LEN) {
            // ★ 一个字不收：模型按音节匹配，单字词会被**任何一句带这个音的话**触发,
            //   症状是「没喊她，她自己跳出来」。这不是阈值能调的，是词本身太短。
            return Result.failure(WakeTokenError("唤醒词至少要 $MIN_LEN 个字", null))
        }
        if (trimmed.length > MAX_LEN) {
            return Result.failure(WakeTokenError("唤醒词最多 $MAX_LEN 个字", null))
        }
        val out = ArrayList<String>()
        for (ch in trimmed) {
            // 只要汉字。字母/数字/空格都不收 —— 这个模型按拼音切，收进来也翻不出。
            if (ch !in '一'..'鿿') {
                return Result.failure(WakeTokenError("「$ch」不是汉字，唤醒词只收汉字", ch.toString()))
            }
            val raw = pinyinOf(ch.toString()).trim().lowercase()
            val syllable = raw.replace(" ", "").replace("'", "").replace("’", "")
            if (syllable.isEmpty()) {
                return Result.failure(WakeTokenError("「$ch」翻不出拼音", ch.toString()))
            }
            val parts = parseOne(syllable, vocab)
                ?: return Result.failure(
                    WakeTokenError("「$ch」的拼音（$syllable）不在这个唤醒模型的词表里", ch.toString())
                )
            out.addAll(parts)
        }
        return Result.success(out)
    }

    /**
     * 真正写进 `keywords.txt` 的那一行。
     *
     * 语法是 sherpa-onnx 的：`token token … :奖励分 #阈值 @显示名`。
     * ★ 奖励分固定 **1.0** —— 那是 10-06 在电脑上验过的那一组，不是库的默认值。
     */
    fun line(word: String, tokens: List<String>, threshold: Float): String =
        tokens.joinToString(" ") +
            " :" + oneDecimal(1.0f) +
            " #" + twoDecimals(threshold) +
            " @" + word

    /** 最多几个字。三个字以上关键词就太长，喊起来不像唤醒词了。 */
    const val MAX_LEN = 4

    /** 最少几个字。见 [toTokens] 里那一段：一个字会被随便一句话触发。 */
    const val MIN_LEN = 2

    private fun oneDecimal(v: Float): String = String.format(java.util.Locale.US, "%.1f", v)
    private fun twoDecimals(v: Float): String = String.format(java.util.Locale.US, "%.2f", v)

    /** 翻不出来的原因。带上具体是哪个字 —— 他要知道去改哪一个。 */
    class WakeTokenError(message: String, val char: String?) : Exception(message)
}
