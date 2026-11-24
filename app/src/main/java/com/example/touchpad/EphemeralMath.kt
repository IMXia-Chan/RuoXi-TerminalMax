package com.example.touchpad

/**
 * ★★ 「用完即删」的两件事:**没有形状的东西怎么认** + **什么时候抹**。
 * **纯逻辑,零 Android 依赖**(照 `SecretMath` / `RiskMath` / `MoodMath` 的惯例),
 * 所以 [EphemeralMathTest] 能把两个方向都钉死。
 *
 * ## 它在整条线的哪一格
 *
 * [SecretMath] 管的是**有形状**的值 —— 卡号有 Luhn、身份证有 mod-11-2、手机号有号段表。
 * 而**一个纯字母数字的密码没有任何形状**:`hunter2` 和一串随便打的东西长得一模一样,
 * **进了文字之后任何正则都找不回来**。
 *
 * 所以这一格只做两件**做得到**的事,不做那件做不到的:
 *
 * 1. ★ **词锚定的短数字** —— 「把验证码 **123456** 填进去」:词表里的词**紧挨着的那串数字**
 *    是这一层唯一抓得住的东西。这是用户自己打的那句话的兜底
 *    (他 2026-10-06 定:**「你打的算不算」→「算,一样抹掉」**)。
 * 2. **控件名判据** —— 给「密码框整段不读」那道**来源闸**用(见下)。
 *
 * ## ★★ 一句话不许说错
 *
 * **「把她上下文里所有密码都擦干净」这句话不说,因为它做不到。**
 * 这个文件能保证的是:**有形状的值一定被遮** + **词旁边的那串数字一定被遮**。
 * 剩下的只能靠**不让它进来**(来源闸 0),不靠事后擦。
 *
 * ## ★★ 名字里的一处**故意改动**(计划里叫 `keywordAnchoredSecrets`)
 *
 * 计划给它起的名字带着 `Secrets`,而**它回的永远是词,不是那串数字**。
 * 改了名字,理由是这个项目自己的老规矩:**会撒谎的接口不是接口,是陷阱** ——
 * 一个叫 `...Secrets` 却不回 secrets 的函数,和「会撒谎的手」是同一个病。
 * ★ 同 `RiskMath.matchedWord` 的口径:**只回命中的那个词,永远不回值**。
 *
 * ## ★ 词表按**语料**定,不按字典定
 *
 * 下面这张表是**封闭**的,而且**故意不收**三个东西 —— 收了会天天误伤,
 * 而这个项目天天在打日志:
 *
 * | 不收 | 收了会误伤 |
 * |---|---|
 * | 光一个 `码` | 二维码 / 条形码 / 编码 / 编号 |
 * | 光一个 `验证` | 「验证」「校验日志」 |
 * | 光一个 `token` | ★ 这个项目拿 token 说前缀快照的账说了无数遍 |
 *
 * ★ 这三个都**在真语料上量过**:`.tmp-model.log`(266 行真机日志)和
 *   `.tmp-crash-before.txt`(真崩溃墓碑)里,整张词表**一个都不命中** ——
 *   而它们正是这个项目天天在读的两样东西。**判据是量出来的,不是想出来的。**
 */
internal object EphemeralMath {

    // ═══════════════════════════════════════════════════════════════════════
    // 一、封闭词表
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * ★★ **顺序就是优先级** —— 长的、具体的在前。
     *
     * 为什么这很重要:「我要登录密码」里,`密码` 和 `登录密码` **都命中**,
     * 而回给用户看的那个词应该是**具体的那个**(回「登录密码」比回「密码」说清了原因)。
     * 同 `RiskMath.MONEY_AND_IDENTITY.firstOrNull` 的口径。
     *
     * ⚠️ **改这张表 = 改行为**:它同时喂着 [sensitiveControlKind](来源闸 0)
     *    和 [maskKeywordAnchored](出门/落盘那三道闸)。
     */
    private val ORDERED_KEYWORDS: List<String> = listOf(
        // ── 中文:先长后短 ──
        "支付密码", "交易密码", "登录密码", "取款密码", "网银密码",
        "短信验证码", "短信码", "校验码", "动态码", "安全码", "验证码",
        "密码", "口令",
        "助记词", "私钥",
        // ── 英文:[isWordBoundarySafe] 会给这些加词边界 ──
        "password", "passwd", "pwd", "PIN", "captcha", "CVV", "CVC", "mnemonic",
    )

    /**
     * ★★ 词锚定的数字**至少要有几位**才算「一串数字」。
     *
     * 定 3 而不是 4,是因为最不敏感的那个也不该漏:`CVV` 只有 3 位。
     * ★ 而这两个方向**不对称**:多遮一位的代价只是「云端那份读起来别扭」,
     *   漏一位的代价是**值出去了**。所以往松的那一侧定。
     * ★ 上界**故意不设** —— 同样的理由,一串 12 位的数字只会更敏感,不会更不敏感。
     *   (真要担心误伤,要担心的是**词**,不是数字长度;词表已经收窄过了。)
     */
    private const val MIN_ANCHORED_DIGITS = 3

    /**
     * 词和那串数字之间**最多允许几个「连接符」**。
     *
     * 要能跨过这些写法:`密码123456` / `密码 123456` / `密码：123456` / `密码=123456` /
     * `PIN码 1234` / `{'pin': '1234'}`。
     * ★ **最多 5 个** —— 这是**故意收窄**的:再放宽就会跨过「错误」「发到他手机上」这类
     *   真正有语义的词,把一句话里隔了半句的数字也吃进来。
     */
    private const val MAX_CONNECTOR_CHARS = 5

    /**
     * 算「连接符」的那些字符。**只有这些**,别的字符一出现就停下。
     *
     * ★ 里面**没有**逗号、句号、顿号 —— 那是句子边界,跨过去就意味着「不是紧挨着的」。
     * (`{'pin': '1234'}` 里跨的那四个是 `'` `:` ` ` `'`,刚好在 5 以内。)
     */
    private const val CONNECTORS = "'\"` 　\t:=：＝是码为"

    // ── 正则:ASCII 词要词边界,中文词不要 ──
    //
    // ★ **为什么 ASCII 非要词边界**:`pin` 是 `spin` / `typing` / `pinned` / `spinlock`
    //   的子串。真语料里这些天天出现(内核符号、Kotlin 标识符),不加边界会天天误伤。
    // ★ **为什么中文不用**:中文没有词边界这个概念,`登录密码` 本来就是连写的。
    private val KEYWORD_RE: Regex = run {
        val parts = ORDERED_KEYWORDS.map { kw ->
            if (kw.all { it in 'a'..'z' || it in 'A'..'Z' }) {
                "(?<![A-Za-z])" + Regex.escape(kw) + "(?![A-Za-z])"
            } else {
                Regex.escape(kw)
            }
        }
        Regex(parts.joinToString("|"), RegexOption.IGNORE_CASE)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 二、来源闸(闸 0):这个名字的控件,整段不读
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 这个控件名命中词表里的哪个词;没命中回 null。
     *
     * ★★ **只回那个词,永远不回值** —— 调用方拿它去写 `withheld:["密码"]`
     * (同 `_risky_app` 那条老规矩:**回执里不该出现敏感的东西,哪怕是为了说明「我挡了它」**)。
     *
     * ★ **喂进来的应该是控件的「名字」**(如 `请输入登录密码` / `Password` / `确认支付密码`),
     *   **不是控件里装的值** —— 这道闸的意义就是**值还没变成字符串的那一刻**就把路堵死。
     *
     * ★ 它今天**零调用方**(因为 `list_ui` 还不带值),按这个项目自己的记账规矩
     *   (`ai_tools.py` 那段):**零调用方 = 没做**。所以它必须和「读值」**同一批**落地。
     */
    fun sensitiveControlKind(name: String): String? {
        if (name.isEmpty()) return null
        val m = KEYWORD_RE.find(name) ?: return null
        return canonicalOf(m.value)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 三、词锚定的短数字(没形状的东西唯一能抓的一处)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 把 `text` 里**紧跟在词表里的词后面的那串数字**换成 `[词]`。
     *
     * ```
     * 把验证码 123456 填进去   →  把验证码 [验证码] 填进去
     * 密码是1234              →  密码是[密码]
     * 密码是我的生日           →  密码是我的生日        (没有数字 → 不动)
     * ```
     *
     * ★★ **它只用在「出门那份」和「落盘那份」上,不改她手上的文本。**
     *   同 `SecretMath.maskOutgoing` 的口径:她拿来点控件的、她上下文里的,
     *   仍然是原话 —— 变的只有**要离开这台手机的那一份**。
     *
     * ⚠️ **已知够不着的地方,不装看不见**:
     *   ① **数字在词的前面**(「123456 是验证码」)**抓不到** —— 只认「词 → 数字」这一个方向。
     *      这一条是**故意**的:双向会让「3 次密码错误」这种把 `3` 也吃掉,而单向漏掉的
     *      那种语序在真话里少得多。
     *   ② **纯字母的密码**(`hunter2` 里那个 `hunter`)**抓不到,永远抓不到**。
     *      这就是文件头那句「不许说擦得干净」的来处。
     */
    fun maskKeywordAnchored(text: String): String {
        if (text.isEmpty()) return text
        val hits = anchoredHits(text)
        if (hits.isEmpty()) return text
        val sb = StringBuilder(text.length + 16)
        var cursor = 0
        for (h in hits) {
            sb.append(text, cursor, h.gapEnd).append(markOf(h.word))
            cursor = h.digitsEnd
        }
        sb.append(text, cursor, text.length)
        return sb.toString()
    }

    /**
     * 这段文字里**哪些词**锚住了数字(会被 [maskKeywordAnchored] 拿掉)——
     * **回去记日志用,不含那串数字本身**。
     *
     * ★ 同 `SecretMath.foundKinds` 的口径:**回的是词,不是值**。
     *   「遮了一个验证码」可以写进日志;那个验证码本身不行。
     * ★ 顺序**写死**(按 [ORDERED_KEYWORDS],不按出现先后)—— 同一句话换个排法,日志不该跟着变。
     */
    fun keywordAnchoredWords(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val hit = anchoredHits(text).mapTo(HashSet()) { it.word }
        if (hit.isEmpty()) return emptyList()
        return ORDERED_KEYWORDS.filter { it in hit }
    }

    /** 出门 / 落盘那一份里,词换成这个。口径同 `SecretMath.markOf`:方括号,**认出类别不回值**。 */
    fun markOf(word: String): String = "[$word]"

    // ═══════════════════════════════════════════════════════════════════════
    // 四、★★ 所有闸共用的那一个判断
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * ★★ **每一个值出口都应该调它,不要各写一套。**
     *
     * 落点照 `ApiMath.redact` 的先例:**一个纯函数,几处调用**。
     *
     * 两步,**顺序有理由**:
     * 1. [SecretMath.maskOutgoing] —— **有形状的**先打掉(卡号 / 身份证 / 手机号 / 邮箱);
     * 2. [maskKeywordAnchored] —— 再抓**词旁边那串没形状的**。
     *
     * ★ 顺序反过来也能跑通(第 1 步的产物 `[银行卡号]` 里没有数字,第 2 步不会再动它),
     *   但**形状先走**更符合「能确定的先做」:形状判据有校验位、有表,比词锚定硬得多。
     *
     * ⚠️ **它不是「加密」,也不是「删除」** —— 它只是把值换成标记。
     *   真要「用完即删」,靠的是 [wipeDue] 那条路 + 「源头不读」那道闸。
     */
    fun scrubText(text: String): String {
        if (text.isEmpty()) return text
        return maskKeywordAnchored(SecretMath.maskOutgoing(text))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 五、抹的时机(**纯真假,不读时钟** —— 时钟在调用方读,同 `MoodMath(nowSec)`)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 她**停下来**多久之后就该抹。用户定的是「**停下来超过几分钟**」。
     *
     * ★ 取 3 分钟并**钉死**:短了会在一次多步搬运的思考间隙里把东西抹掉,
     *   长了值就多躺一会儿。**这是个取舍,不是个常数** —— 改它要单独说。
     */
    internal const val DEFAULT_IDLE_WIPE_MS = 3 * 60 * 1000L

    /**
     * ★ **闲置超时**那一支的判据:现在该抹了吗。
     *
     * @param nowMs 墙上时间,**由调用方读** —— 这个函数自己不碰时钟,测试里传数字就行。
     * @param lastActivityMs 最后一次交互的时刻;从没活动过传 0(或负数)。
     * @param anythingRunning ★★ **还有任何一件事在跑吗**(不是「我这一件」)。
     * @param idleMs 闲置多久算超时,默认 [DEFAULT_IDLE_WIPE_MS]。
     *
     * ## ★★ 三条「回 false」,每一条都有具体理由(不是防御性代码)
     *
     * 1. **还有事在跑** → false。★ 这是全项目最要紧的一条不变量:
     *    「**抹不会破坏多步搬运**」—— 读出来 → 切到 WPS → 找到框 → 填进去,
     *    中间任何一刻抹掉,那件事就断了。
     *    ⚠️ 注意参数名字是 `anythingRunning` 而**不是** `running`:**并行起来之后**,
     *    「我这件完了」不等于「没别的事在跑」。调用方必须先把自己那份数减掉再问。
     * 2. **从没活动过**(`lastActivityMs <= 0`)→ false。没有「这件事」,就没有「事情完了」,
     *    更没有可抹的东西。(回 true 也无害,但那是**把一个说不清的状态当成"该抹了"**,
     *    这个项目已经吃够了「说不清就等于默认放行」的亏。)
     * 3. **时钟回拨** → false。NTP 校正、时区、用户手动改表都算。方向是**故意选的**:
     *    不抹的代价是值多躺一会儿,抹错的代价是**搬运中途断掉** ——
     *    而且定时器下一次跑还会再判一次,不会永远漏。
     */
    fun wipeDue(
        nowMs: Long,
        lastActivityMs: Long,
        anythingRunning: Boolean,
        idleMs: Long = DEFAULT_IDLE_WIPE_MS,
    ): Boolean {
        if (anythingRunning) return false
        if (lastActivityMs <= 0L) return false
        val idle = nowMs - lastActivityMs
        if (idle < 0L) return false
        return idle >= idleMs
    }

    /**
     * ★ **「这件事刚办完」**那一支的判据:现在能立刻抹吗。
     *
     * ★ 为什么不并进 [wipeDue]:**那两个门槛不一样**。这件事刚办完时闲置是 0,
     *   拿 `wipeDue` 问必然回 false —— 而用户要的恰恰是「**办完就没了**」。
     *   所以「办完」那一支**不走时间那道闸,只过"还有没有别的事在跑"这一道**。
     *
     * ⚠️ **调用点在 `launch` 的 `finally` 里、`running.set(false)` 之前**(那时还没放开)——
     *   所以传进来的必须是「**把这一件自己算完之后**」的全局状态,否则并行时会抹掉别人的活。
     */
    fun wipeNow(anythingRunning: Boolean): Boolean = !anythingRunning

    // ═══════════════════════════════════════════════════════════════════════
    // 六、内部
    // ═══════════════════════════════════════════════════════════════════════

    /** 一处命中的样子:`[gapStart+1, gapEnd)` 是词和连接符(原样保留),`[gapEnd, digitsEnd)` 是那串数字。 */
    private class Hit(val word: String, val gapEnd: Int, val digitsEnd: Int)

    /**
     * ★★ **扫描只写一遍** —— [maskKeywordAnchored] 和 [keywordAnchoredWords] 都吃它。
     * 抄两遍判据正是这个项目吃过好几次的亏(「工具表抄三遍」),这里不重演。
     */
    private fun anchoredHits(text: String): List<Hit> {
        val out = ArrayList<Hit>(2)
        var cursor = 0
        for (m in KEYWORD_RE.findAll(text)) {
            // 落进已经吃掉的区间(比如同一句话里两次命中贴在一起)→ 跳过
            if (m.range.first < cursor) continue
            val gapEnd = skipConnectors(text, m.range.last + 1)
            val digitsEnd = gapEnd + digitRun(text, gapEnd)
            if (digitsEnd - gapEnd < MIN_ANCHORED_DIGITS) continue
            out += Hit(canonicalOf(m.value), gapEnd, digitsEnd)
            cursor = digitsEnd
        }
        return out
    }

    /** 从 [from] 开始,吃连接符,吃到第一个不是连接符的字符为止(最多 [MAX_CONNECTOR_CHARS] 个)。 */
    private fun skipConnectors(text: String, from: Int): Int {
        var i = from
        var n = 0
        while (i < text.length && n < MAX_CONNECTOR_CHARS && CONNECTORS.indexOf(text[i]) >= 0) {
            i++
            n++
        }
        return i
    }

    /**
     * 从 [from] 开始连着几个**ASCII 数字**。
     *
     * ★ **只认 ASCII,不认全角** —— 同 `SecretMath` 文件头那条:Java/Kotlin 的 `\d`
     *   和 `Char.isDigit()` 会认全角数字和别的 Unicode 数字,而号段表、长度判据全是 ASCII 的。
     *   两边混着用会在 `１３８…` 上分岔。
     */
    private fun digitRun(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i] in '0'..'9') i++
        return i - from
    }

    /**
     * 正则 `IGNORE_CASE` 匹配出来的是用户打的**原样**(可能是 `Pin` / `PASSWORD`)——
     * 而回给用户看的、写进日志的应该是词表里那个**规范写法**。
     */
    private fun canonicalOf(matched: String): String =
        ORDERED_KEYWORDS.firstOrNull { it.equals(matched, ignoreCase = true) } ?: matched
}
