package com.example.touchpad

/**
 * ★★ **先出声** —— 把她已经吐出来的话**按句**一段段切出来,趁后面还在生成就先念。
 *
 * ## 为什么有这个东西(用户 2026-10-05 点名要的)
 *
 * 原话:「**然后看看能不能把响应(思考时间)再压缩一点,在本地模型上**」。
 *
 * 查清楚之后的实情是:**本地模型本身不慢**(裸打 `127.0.0.1:8080`,4 个 token 只要
 * 1.2 ~ 3.8 秒),而**他感觉到的「慢」有一大半是另一回事** ——
 *
 * > **她整段话生成完之前,一个音都不出。**
 *
 * `onPartial` 一路上来只写字幕(`ConMarnActivity.kt:2841` 那行注释写着「她还在想 ——
 * 只在字幕上滚动显示,**不念**」),出声那一路(`say()` → `speak()`)只在 `onFinal` 才响。
 * 按代码里自己写的 **7 token/秒**(`AiAgent.kt:2119`)算,一段 200 token 的回答 =
 * **将近 30 秒的绝对安静**,然后哗啦一下全说出来。
 *
 * 生成速度压不动 —— 那是 4B 在这颗 SoC 上的物理。**但第一声不必等整段。**
 * 这就是这一层干的事:在已经吐出来的那半句里挑一个**能断的地方**先送进 TTS。
 *
 * ## 借来的判据(AIRI `tts-chunker.ts`,只借设计不抄代码)
 *
 * * `boost = 2` → 第一块**允许在软标点(逗号)上断**。这一条是关键:
 *   只认句号的话,一段没有句号的长回答永远不会触发。
 *   (OLV 在**第一句**上专门开小灶,配置项就叫 `faster_first_response`,
 *   判据是「buffer 里一出现逗号就切」。中文这份逗号表必须含 `，`、`、`、`;`。)
 * * `minimumWords = 4` → 见 [MIN_CHARS]。
 * * **数字保护** → `3.14` / `1,000` 不许从中间劈开,见 [insideNumber]。
 *
 * ## ★ 这一层为什么必须是纯逻辑
 *
 * 「在哪切」是一件**能判定**的事,而这个项目的老规矩是能判定的事一律零 Android 依赖、
 * 必须能 JVM 单测([MoodMath] / [GreetMath] / [EarMath] / [SpeechMath] / [WardrobeMath]
 * 全是这个形状 —— **不能单测的代码在这里等于不可信**)。
 *
 * 而且这一层的失败**全是静默的**:切错了听着像噎住半句话,对不上账会重复念一遍。
 * [SpeechChunkMathTest] 逐个钉住。
 *
 * ## ★ 边界(为什么它碰不到安全闸)
 *
 * 它只回答三个问题:**「这一句能不能先念」**([firstChunk])、
 * **「接着往下还有没有下一句」**([nextChunk])、
 * 和 **「剩下多少还没念」**([spokenPrefix])。
 * 出声的时机、麦克风什么时候放回去,全在调用方 —— 这一层**不碰任何闸门**,
 * 也**不认识** [ConMarnActivity] 里的任何状态。
 */
internal object SpeechChunkMath {

    /**
     * 短于这个长度就别切了。**★ 别调小。**
     *
     * AIRI 那份切分器原话(借的是这句判据,不是它的代码):
     *
     * > *every TTS request carries a fixed cost that does not shrink with the text,
     * > so a two-word fragment delays the audio it was meant to bring forward.*
     * >
     * > 每一次 TTS 请求都有一笔**不随文本变短而变小**的固定开销;
     * > 为了抢先音把句子切成两个词,反而比不切**更慢**。
     *
     * 内嵌 sherpa 那笔固定开销是实打实的:起一次 worker、开一次流式会话、
     * 回一次 PCM。六到八个字是「固定开销摊得开」和「第一声明显提前」两条同时成立的量级。
     */
    const val MIN_CHARS = 6

    /**
     * 能在它**后面**断开的标点。
     *
     * ★ 中文那几个(`，`、`、`、`；`)**一个都不能少** —— 她的回答是中文,
     *   只认英文标点的话这条功能在她身上几乎从不触发,而且**不报任何错**,
     *   表现出来就是「改了跟没改一样」。
     *
     * ★ 冒号(`：` / `:`)故意**不在**表里:「他说:」切出去听着是半句话,
     *   而它带来的提前量比起逗号并没有多多少。**宁可漏不可错。**
     */
    private const val BREAKS = "。！？!?…\n；;，,、"

    /**
     * 跟在断点后面、**属于上一句**的收尾符号 —— 一起带走,不留到下一块去。
     *
     * 不带的话 `她笑了。「然后…」` 会切成 `她笑了。` + `「然后…」`,
     * 那个孤零零的左引号会被下一块念成一个停顿。
     */
    private const val CLOSERS = "」』）)》〉\"'”’"

    /**
     * 这一版已经吐出来的文字里,**最前面那一句**。
     *
     * 返回 null = **不要提前念**,等整段出来照旧整段念。三种情况:
     * * 还不够长(比 [MIN_CHARS] 短);
     * * 一个断点都找不到(比如一段没有标点的话)—— ★ **绝不在句子中间劈开**;
     * * 太短,不值得为它单起一次 TTS。
     *
     * @return **一定是 [text] 的前缀** —— 调用方要靠这一条跟定稿的全文对账
     *         (见 [spokenPrefix])。**改了这里就必须改那儿。**
     */
    fun firstChunk(text: String): String? {
        if (text.length < MIN_CHARS) return null

        // 从 MIN_CHARS-1 开始找:这样切出来的那一块**至少** MIN_CHARS 个字符。
        var i = MIN_CHARS - 1
        while (i < text.length) {
            if (text[i] in BREAKS && !insideNumber(text, i)) {
                var end = i + 1
                // 连着的一串收尾符号一起带走:`？！`、`。"`、`…`
                while (end < text.length && (text[end] in BREAKS || text[end] in CLOSERS)) end++
                return text.substring(0, end)
            }
            i++
        }
        return null
    }

    /**
     * **接着**已经念过的那一段,再切一句出来先念。
     *
     * ## 它和 [firstChunk] 的关系
     *
     * `onPartial` 每来一版就是**一次更长的目标**,而那一版是**从头开始**的
     * (流式重发的是整段,不是增量)。所以「接着往下切」不能拿上一版的尾巴去对,
     * 只能拿**已经念过的那一段原文**去对 —— 那正好是 [spokenPrefix] 认的那个东西。
     *
     * ```
     * 第 1 版: 「听得到听得到,耳朵灵着呢～」
     *           └─ firstChunk ─→ 「听得到听得到,」          ← 念了
     * 第 2 版: 「听得到听得到,耳朵灵着呢～ 就是这会儿你那台电脑我够不着,别的」
     *           └─ nextChunk(全文, 上面那块) ─→ 「耳朵灵着呢～」  ← 接着念
     * ```
     *
     * ★★ 它治的是用户 2026-10-05 报的那条:
     *   > 「她在说话的时候,**会中间断一下再说**,前半句话……后半句话,会中间加延迟」
     *   只抢一次的话,第一块念完到定稿之间是**一整段空白**(真机上量到 8 秒)。
     *   一句一句往下抢,那段空白就被摊进每一句之间的缝里。
     *
     * ⚠️ **摊不平,只是摊薄**:这台机器的嗓子和成速度是实时的 **0.58 倍**
     *   (1.13 秒的音频要 ~2 秒造出来),一段 5 秒的话永远要 8 秒才念得完。
     *   所以做到的是「一句一顿」,不是「流利」。要真流利只能换嗓子或加线程,
     *   而加线程要用热换(见 `SherpaVoice.NUM_THREADS` 那段)。
     *
     * @param text 这一版**从头开始**的全文。
     * @param spoken 已经抢出去念过的那一段原文(空 = 还没抢过,等价于 [firstChunk])。
     * @return 下一块;null = **这一版没有新东西可抢**(还没成句 / 还不够长 /
     *         **模型改了口**)。
     *
     * ★ **改了口就回 null,不许硬切** —— 见 [spokenPrefix] 那段:
     *   按长度切一刀会把半句话吞掉,而且**不报错**。改了口的时候这一版就不再抢,
     *   交给定稿那条路整段重念。**宁可重复,绝不吞字。**
     */
    fun nextChunk(text: String, spoken: String): String? {
        if (spoken.isEmpty()) return firstChunk(text)
        // 改了口 / 不是一个开头 —— 就此停手。
        if (!text.startsWith(spoken)) return null
        // 已经抢光了 —— 没有「下一块」这回事。
        if (spoken.length >= text.length) return null
        return firstChunk(text.substring(spoken.length))
    }

    /**
     * 定稿的全文里,**从哪一位开始是还没念过的**。
     *
     * * 返回 **-1** = **整段都还没念过** —— 照旧整段念。
     *   两种情况:压根没提前念过;或者**提前念的那一块对不上定稿**
     *   (模型改口 / 流式那版和定稿版不是一个开头)。
     *   ★ 对不上账时**必须退回整段念**,不许「按长度切一刀」——
     *     切错的那一下是把半句话吞了,而且不报错。
     * * 返回 **`>= 0`** = 从这一位往后才是新的。
     *   **`== full.length` 是合法的**,意思是「一个字都不剩了」——
     *   那一块已经就是全部,调用方**不该再发一次 TTS**。
     *
     * @param alreadySaid 提前念出去的那一块(原样的、没剥过舞台指示的那一份)。
     */
    fun spokenPrefix(full: String, alreadySaid: String): Int {
        if (alreadySaid.isEmpty()) return -1
        if (!full.startsWith(alreadySaid)) return -1
        return alreadySaid.length
    }

    /**
     * 这个断点是不是**夹在数字里面**。
     *
     * `3.14` 从 `.` 切开 = `3.` + `14`(小数点被念成句号,后面那个 14 孤零零);
     * `1,000` 从 `,` 切开 = `1,` + `000`(千位分隔符被念成逗号)。
     * 两条都难听,而且**都不报错**。
     *
     * 判据取窄:`.` / `,` 且**左右都是数字**才算数。
     * 句子末尾的 `。` 不是 ASCII 句点、中文逗号也不是 `,`,所以中文根本碰不到这条 ——
     * 它是给「她偶尔夹一句英文/数字」准备的。
     */
    private fun insideNumber(t: String, i: Int): Boolean {
        val c = t[i]
        if (c != '.' && c != ',') return false
        if (i == 0 || i == t.length - 1) return false
        return t[i - 1].isDigit() && t[i + 1].isDigit()
    }
}
