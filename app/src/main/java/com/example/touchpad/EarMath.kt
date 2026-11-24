package com.example.touchpad

/**
 * 耳朵里**所有不需要 Android 的判断** —— 单独放一个 object,好让 JVM 单测跑得动。
 *
 * 同 `LexiconMath` / `MoodMath` / `GreetMath` / `QuietMath` 的惯例:逻辑和平台分开,
 * 于是「字节序反了」「标点被当成话」这类**不会崩、只会让她听错**的错,能在电脑上钉死,
 * 不用推着 234MB 的模型上真机试。
 *
 * ★ 这个文件里每一行防的都是**静默失败**:识别相关的错没有一个是抛异常的,
 * 它们全都表现成同一句话 —— 「她没听见」。
 */
internal object EarMath {

    const val SAMPLE_RATE = 16000

    /**
     * 短于这个时长的「语音」不是话 —— 是咳嗽、键盘声、桌子响、他自己的呼吸。
     * 门槛定在 300ms:低于这个数,Silero VAD 也会偶尔把噪声判成一小段语音。
     */
    const val MIN_SPEECH_MS = 300

    /**
     * 长于这个时长的不要。一条指令说 20 秒,后面十几秒多半是电视、邻居、或者
     * 手机放兜里走路的摩擦声 —— 那已经不是「他在跟我说话」了。
     * 也正好是 [Ear] 那边听音看门狗的时长。
     */
    const val MAX_SPEECH_MS = 20_000

    /**
     * 转写结果的字符上限。他的指令最长也就「帮我把电脑上那个浏览器里第二个标签页关掉」
     * 这个量级(20 来个字)。超过 200 字**只可能是模型在幻觉** —— VAD 放过去了一长段
     * 噪声,模型就一本正经地编一整段话出来。宁可丢掉,也不要让她对一段瞎话作出反应。
     */
    const val MAX_TEXT = 200

    /**
     * 幻觉的形状之一:**同一个字符循环**。
     *
     * SenseVoice 在纯噪声上会吐出「好好好好好好好」「哈哈哈哈哈哈」这种。
     * 判据用「最常见字符的占比」而不是「有没有重复」—— 因为:
     *   · 「哈哈哈」是真笑,不能丢
     *   · 「嗯嗯嗯嗯嗯嗯嗯嗯好的」也是真话(8 个嗯 + 好的 = 80%),不能丢
     *   · 「哈哈哈哈哈哈哈哈哈哈哈」不是话,要丢
     * 所以门槛定在 **90%**,并且短于 [LOOP_MIN_LEN] 的一律放过(短串没法判,别误杀)。
     */
    private const val LOOP_MIN_LEN = 8
    private const val LOOP_RATIO = 0.9f

    /** `<|zh|>` `<|NEUTRAL|>` `<|withitn|>` 这类标记。 */
    private val TAG = Regex("<\\|[^|]*\\|>")

    /**
     * PCM16 小端字节 → [-1, 1] 的浮点。Sherpa 的 VAD 和 ASR 都吃这个形状。
     *
     * ★★ **小端:低字节在前。** 写反了**不会崩** —— 得到的是整段波形被掰成锯齿,
     *    识别出来全是乱码。而那个症状看起来像「模型不行」「口音太重」,
     *    完全不像「字节序写反了」。这是这个函数唯一值得单测的理由。
     *
     * 用 [hi] 的**有符号**值直接移位:`-128 shl 8 = -32768`,正好落在 -1.0,
     * 对称且不用特判。末尾那个不足两个字节的残字节**丢掉** —— 一个孤立的半样本
     * 变不出声音,硬补零反而会在每个 chunk 边界插一个咔哒。
     */
    internal fun pcm16ToFloat(bytes: ByteArray, len: Int): FloatArray {
        val n = len / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val lo = bytes[2 * i].toInt() and 0xFF
            val hi = bytes[2 * i + 1].toInt()
            out[i] = ((hi shl 8) or lo) / 32768f
        }
        return out
    }

    /** 采样数 → 毫秒。VAD 是按样本给区间的,判定门槛是按毫秒定的,中间就靠这一行。 */
    internal fun durationMs(sampleCount: Int): Int =
        (sampleCount.toLong() * 1000L / SAMPLE_RATE).toInt()

    internal fun tooShort(ms: Int): Boolean = ms < MIN_SPEECH_MS
    internal fun tooLong(ms: Int): Boolean = ms > MAX_SPEECH_MS

    /**
     * 模型吐出来的原文 → 能送去 [UserLexicon.correct] 的干净句子。**丢就返回 null。**
     *
     * ★ 这里的每一步都是「丢掉」而不是「修一修」:一段可疑的转写**没有安全的修法**,
     *   而放它过去的后果是她对着一句没人说过的话作出反应 —— 那比没听见更糟。
     *   **但每一次丢弃都要在 `耳:` 日志里说清楚是哪一条规则丢的** ——
     *   否则「她没听见」和「她听见了但我判它无效」在日志上长得一模一样。
     *
     * ⚠️ **手头还没有真机转写的样本**,所以这条链是**防御性**写的:
     *   sherpa 的 C++ 那侧(SenseVoice 实现)本来就会把 `<|zh|>` 这类标记解析进
     *   `OfflineRecognizerResult` 的 `lang` / `emotion` / `event` 字段,`text` 里可能
     *   本来就没有标记。**这件事不猜** —— [Ear] 那边会把**原文**原样打进日志,
     *   第一次真机跑就能看见它到底长什么样。到时候若确认标记早就被剥掉了,
     *   这一行就是一次无害的空转(正则匹配不到东西),不构成错误。
     */
    internal fun clean(raw: String): String? {
        val t = TAG.replace(raw, "").trim()
        if (t.isEmpty()) return null
        if (t.length > MAX_TEXT) return null
        // 全是标点。「。」「。。。;」「? ? ?」都是噪声上来的,不是话。
        // 中日韩统一表意文字在 `Char.isLetter()` 里是 true(类别 Lo),所以这一行
        // 中英文都认,不需要额外判码点范围。
        if (t.none { it.isLetterOrDigit() }) return null
        if (t.length >= LOOP_MIN_LEN) {
            val top = t.groupingBy { it }.eachCount().values.max()
            if (top >= t.length * LOOP_RATIO) return null
        }
        return t
    }

    /**
     * **实时字幕**用的轻量清洗。和 [clean] 是两条路,别合并 —— 它们要的东西正好相反。
     *
     *   · [clean] 判的是「**这句话能不能当真**」,可疑就丢。它面对的是一句**已经说完**的话。
     *   · 这个判的是「**这几个字能不能先给他看着**」,面对的是**半句话**。
     *
     * ★★ 所以长度上限和循环判据**一律不看**,这不是偷懒,是不看才对:
     *   半句话天生就短、就带重复(「我我我」「那个那个」),拿 [clean] 去卡它,
     *   字幕会在说到一半时**突然整行消失** —— 而消失在一句话中间,
     *   看起来不像「被判为幻觉」,像**卡住了**。
     *   真正该丢的那半句,[clean] 在最后那一遍会照丢不误,一个字都不会进脑子。
     *
     * ★ 只留三条:剥标记、去空白、**至少得有一个字**。
     *   最后那条防的是 SenseVoice 在纯噪声上吐「。。。」或「嗯。」——
     *   那种东西贴到字幕上,他会以为自己在跟一台坏掉的机器说话。
     */
    internal fun cleanPartial(raw: String): String? {
        val t = TAG.replace(raw, "").trim()
        if (t.isEmpty()) return null
        if (t.none { it.isLetterOrDigit() }) return null
        return t
    }

    /** 给日志用的一句话理由 —— 「为什么丢了」必须比「丢了」更响。 */
    internal fun whyDropped(raw: String): String {
        val t = TAG.replace(raw, "").trim()
        return when {
            t.isEmpty() -> "转写是个空串"
            t.length > MAX_TEXT -> "转写 ${t.length} 字,超过 $MAX_TEXT —— 判为幻觉"
            t.none { it.isLetterOrDigit() } -> "转写全是标点,没有字"
            t.length >= LOOP_MIN_LEN &&
                t.groupingBy { it }.eachCount().values.max() >= t.length * LOOP_RATIO ->
                "转写是同一个字的循环(幻觉)"
            else -> "没丢,但也没通过?这是 bug"
        }
    }
}
