package com.example.touchpad

/**
 * ★★ 「值的样子」—— 认出**号码**的那两件事:判据 + 把号码换成标记。
 * **纯逻辑,零 Android 依赖**(照 `MoodMath` / `GreetMath` / `HandMath` / `WardrobeMath` 的惯例),
 * 所以 [SecretMathTest] 能把两个方向都钉死。
 *
 * ## 它在整条线的哪一格 —— ★ 这一格管「**要不要发云**」,不管「读不读得到」
 *
 * | 哪一类 | 他定的 | 落在哪 |
 * |---|---|---|
 * | 银行卡号 / 身份证号 | **不读不写** | **电脑那头**出门前就打掉了(`pc-server/ai_tools.py`),根本到不了这里 |
 * | 手机号 / 邮箱 | **能读、不能写、只是本地读** | ★ **就是这一层** —— 屏幕上照读,拦的是**出门那一份** |
 *
 * 用户 2026-10-06 的原话:**「保持能读,不要发云」** —— 这个文件就是那句话的落点。
 *
 * ★ 为什么手机号/邮箱**不在电脑那头的屏幕上打码**:那样她就**读不到**了,
 *   而跨应用搬运恰恰靠「读出来 → 填过去」;读出来是星号,填过去也是星号。
 *   这正是他否掉那条粗规则时说的「**粗的又难跑操作电脑了**」。
 *
 * ## 今天唯一的云出口
 *
 * [AiAgent.askTeacher] 是全项目**唯一**一处把观察发去云端的代码。它发的只有结构
 * (窗口标题、情况种类、文字行数、要找的那个词)—— 但 ★ **窗口标题是原样发的,
 * 而标题本身可以正好是一串号码**:浏览器标签可以叫「订单 13812345678」,
 * 邮件客户端的标题栏就是「zhangsan@example.com - 收件箱」。
 *
 * 所以 [maskOutgoing] 只用在**拼请求体那一步**:
 * 她手上、她的上下文里、她拿来点控件的,**全部还是原话** —— 变的只有出门那一份。
 *
 * ## ★ 判据是「形状 + 表」,不是纯形状
 *
 * 银行卡有 **Luhn** 校验、身份证有 **mod-11-2** 校验 —— 校验位把「形状」变成「确定」。
 * 手机号和邮箱没有校验位,但各有**一张表**顶上来(号段表 / 域名格式)。
 * 同一条道理:**形状会撞,表不会。**
 *
 * ★★ 最要紧的一条经验(2026-10-06 量出来的):**「有校验位」不等于「判得准」**。
 *   安卓崩溃墓碑里的 **`0000000000000000` 过得了 Luhn**(全零的和是 0,0 能被 10 整除),
 *   而墓碑里满屏都是它 —— 而「读墓碑」正是这个项目天天干的事。
 *   所以卡号那条多了一关:**首位必须是 2~6**(ISO/IEC 7812 的行业号,支付卡就是这一段)。
 *
 * ## ⚠️ 和电脑端那份的关系(别装作没有)
 *
 * `pc-server/ai_tools.py` 里也有同一批评据(Python)。**卡号和身份证那两条必须两边都有**
 * ——电脑那头要**当场**把值打掉(值根本不该进她的上下文)。
 * 但**手机号 / 邮箱**那两条在电脑那头是**没有调用方的** —— 因为他 2026-10-06 定了
 * 「能读、只是不发云」,而「发不发云」是**手机侧**的事。
 * ★ 两份实现迟早会漂移。真要长期用,得挑一处当权威 —— **这件事还没定,先记在这儿。**
 */
internal object SecretMath {

    val KIND_CARD = "银行卡号"
    val KIND_ID = "身份证号"
    val KIND_PHONE = "手机号"
    val KIND_EMAIL = "邮箱"

    /**
     * API key / 令牌。**2026-11-06 补的**：他上传的资料里可能贴着自己的 key，
     * 而这一类不像电话号码有形状可校验 —— 只能认前缀。
     *
     * ★ 宁可漏不可错：这里只认**各家有固定前缀**的那些。随手一段长字符串不算，
     *   否则会把正常正文（提交哈希、base64 片段）遮掉，云端看到的就全是标记。
     */
    val KIND_KEY = "密钥"

    /**
     * 各家 key 的固定开头 + 一段足够长的随机体。
     *
     * ★ 分三支是因为**分隔符不一样**:GitHub/OpenAI 那几家是 `前缀_` 或 `前缀-`,
     *   Slack 是 `xoxb-`,而 Google 的 `AIza...` **中间什么分隔符都没有** ——
     *   把它们塞进同一个 `[_-]` 里,Google 那一支就永远不会命中(实测踩过)。
     */
    private val KEY_RE = Regex(
        """(?:\b(?:sk|pk|ak|rk|ghp|gho|ghu|ghs|ghr|hf|gsk)[-_][A-Za-z0-9_\-]{16,}""" +
            """|\bxox[baprs]-[A-Za-z0-9\-]{10,}""" +
            """|\bAIza[A-Za-z0-9_\-]{30,})""")

    /**
     * 出门那份里,号码换成这个。
     * ★ 方括号是**故意的**:一眼看得出「这里原本有东西,被拿掉了」——
     *   比留后四位强(云端的老师不需要「认出是哪张卡」这种用处,少一个字节都是白给的)。
     */
    fun markOf(kind: String): String = "[$kind]"

    // ── 正则:这一层**只管「长得像一坨数字」**;「是不是号码」交给下面那些纯函数 ──
    //
    // ⚠️⚠️ **下面这几条和电脑端(Python)那几条不完全一样**,而差别的原因只有一个词:
    //     **Java 的 `\d` / `\w` 默认只认 ASCII,Python 在 `str` 上认全 Unicode。**
    //
    // 这不是「谁对谁错」,是**两边的行为确实会分岔**,要说清楚免得以后当成同一个东西:
    //
    //  | 例子 | Python(电脑) | Kotlin(手机) |
    //  |---|---|---|
    //  | `张三zhangsan@example.com` | **漏**(`三` 被 Python 当成 `\w`,左边的眼挡住了) | **遮**(ASCII 的 `\w` 里没有汉字) |
    //  | 全角数字的手机号 `１３８…` | 正则认得出,但号段表是 ASCII 的 → 不遮 | 正则就认不出 → 不遮 |
    //
    // ★ 第一行 Kotlin **遮得更多** —— 那是安全的方向,但**它确实是分岔**。
    //   电脑端那两条(手机号/邮箱)今天**没有调用方**,所以分岔暂时不咬人;
    //   哪天有人把它们接上去,这一处就是第一个要对的。

    /** 身份证:17 位 + 校验位(末位可能是 X)。两头不许粘着别的字母数字。 */
    private val ID_RE = Regex("""(?<!\d)(\d{17}[\dXx])(?![\dA-Za-z])""")

    /** 银行卡:只认 15 / 16 / 19 位,且不许粘着别的数字。 */
    private val CARD_RE = Regex("""(?<!\d)(\d{15}|\d{16}|\d{19})(?!\d)""")

    /**
     * ★★ **分开写的卡号** —— 屏幕上最常见的其实**不是**连着的 `4111111111111111`,
     * 而是 `4111 1111 1111 1111` / `3782-822463-10005` 那种分组样子。
     * 只认连着的那一条,等于把最常出现的那个格式整个漏掉,而**漏是静默的**。
     * 分组长度放到 3~6 位:银联 19 位那套末组是 3 位(`…7890 128`),Amex 15 位是 4/6/5。
     */
    private val GROUPED_RE = Regex("""(?<![\dA-Za-z])(\d{3,6}(?:[ -]\d{3,6}){2,})(?![\dA-Za-z])""")

    /** 手机号:11 位、1 开头。**是不是真号由号段表判**,见 [phoneShapeOk]。 */
    private val MOBILE_RE = Regex("""(?<!\d)(1\d{10})(?!\d)""")

    /** 邮箱:local@domain.tld。域名那几段由 [emailShapeOk] 判。 */
    private val EMAIL_RE = Regex(
        """(?<![\w.+-])([A-Za-z0-9._%+-]+)@([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+)(?![\w.-])""")

    private val LABEL_RE = Regex("""[a-z0-9-]+""")
    private val CARD_LENS = setOf(15, 16, 19)

    /**
     * 中国手机号的**号段表**(前三位)。来源是工信部分配给四家运营商 + 虚拟运营商的那批。
     *
     * ⚠️ **这张表会过期** —— 新号段是后来才发的。而过期的表现是**漏**,漏是静默的。
     * 所以:① 一次写宽(宁多不少 —— 多一个的代价只是多判一次「不上云」);
     * ② ★ 靠人记得更新它**不现实**。真要长期靠它,得让「这串像手机号、但前三位的号段我不认」
     * 有地方能看见,否则它会安安静静地变成一条无用的判据。
     */
    private val MOBILE_SEGMENTS: Set<String> = setOf(
        // 移动
        "134", "135", "136", "137", "138", "139", "147", "148",
        "150", "151", "152", "157", "158", "159",
        "172", "178", "182", "183", "184", "187", "188",
        "195", "197", "198",
        // 联通
        "130", "131", "132", "145", "146", "155", "156", "166", "167",
        "171", "175", "176", "185", "186", "196",
        // 电信
        "133", "149", "153", "162", "173", "174", "177",
        "180", "181", "189", "190", "191", "193", "199",
        // 广电 / 虚拟运营商
        "192", "165", "170",
    )

    private val ID_WEIGHTS = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
    private const val ID_CHECK = "10X98765432"

    // ═══════════════════════════════════════════════════════════════════════
    // 一、四条判据(全是纯算术)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Luhn 校验(银行卡用的那个)。从右往左,每隔一位翻倍,翻倍后超 9 减 9,全加能被 10 整除就算过。
     *
     * ⚠️ **单独用它是不够的** —— `0000000000000000` 也过。要 [cardShapeOk] 三关一起。
     */
    fun luhnOk(digits: String): Boolean {
        var total = 0
        for ((i, ch) in digits.reversed().withIndex()) {
            var n = ch - '0'
            if (i % 2 == 1) {
                n *= 2
                if (n > 9) n -= 9
            }
            total += n
        }
        return total % 10 == 0
    }

    /**
     * 一串**纯数字**:长度 + 行业号 + Luhn,**三关都过**才当它是卡号。
     *
     * 1. **长度**只认 15 / 16 / 19(ISO/IEC 7812 里真在用的那三个)。
     *    这条挡的是**毫秒时间戳** —— 13 位数字在日志里满地都是。
     * 2. **首位 2~6**。行业号:0 是 ISO 保留、1 是航空、7 是石油、9 是电信,**支付卡就是 2~6**。
     *    ★ 这条是**量出来的**:不补它,崩溃墓碑里的 `0000000000000000` 会中招。
     * 3. **Luhn**。真号码必然过,随手一串只有十分之一能过。
     */
    fun cardShapeOk(digits: String): Boolean {
        if (digits.length !in CARD_LENS) return false
        if (digits[0] !in "23456") return false
        return luhnOk(digits)
    }

    /**
     * 18 位身份证:校验位 + 出生日期合理性。**两个都过**才算。
     *
     * 只过校验位的话,末位有 1/11 的运气会撞上;加上日期这一层,
     * 像 `123456789012345678` 这种就永远不会被当成身份证。
     */
    fun cnIdOk(s: String): Boolean {
        if (s.length != 18) return false
        val body = s.substring(0, 17)
        if (!body.all { it in '0'..'9' }) return false
        val y = body.substring(6, 10).toIntOrNull() ?: return false
        val m = body.substring(10, 12).toIntOrNull() ?: return false
        val d = body.substring(12, 14).toIntOrNull() ?: return false
        if (y !in 1900..2099 || m !in 1..12 || d !in 1..31) return false
        var total = 0
        for (i in 0 until 17) total += (body[i] - '0') * ID_WEIGHTS[i]
        return ID_CHECK[total % 11] == s[17].uppercaseChar()
    }

    /**
     * 11 位、1 开头、**前三位在号段表里**。
     *
     * ★ 为什么非要那张表:`1[3-9]` 加九个数字**谁都能凑出来**(订单号、流水号、随便一串)。
     *   真正决定它是手机号的是「这段号有没有被放出去」。
     * ★ 这是**收窄**,所以失效方向是**漏**(表里没有的新号段)。宁可这样,也不要为了不漏
     *   而放宽成纯形状 —— 那会把一堆流水号判成手机号。
     */
    fun phoneShapeOk(digits: String): Boolean =
        digits.length == 11 && digits.substring(0, 3) in MOBILE_SEGMENTS

    /**
     * local 与 domain 分开判:长度合规矩 + **域名每一段都像域名**。
     *
     * ★★ 真正干活的是那条「**每一段必须全小写**」。
     *   真域名惯例小写,而**代码里的标识符是驼峰** ——
     *   实测那条误伤 `this@MainActivity.contentResolver`(Kotlin 里 `this@标签` 的写法)
     *   被它一句话干掉,而且**不用任何顶级域名白名单**(白名单会随 ICANN 的新顶级域名过期,
     *   这条不会)。
     *
     * ⚠️ 代价:全大写的域名(`USER@EXAMPLE.COM`)会**漏**。这种写法在真邮件里很少,
     *   而且漏的方向是「不遮」,比误伤便宜。
     */
    fun emailShapeOk(local: String, domain: String): Boolean {
        if (local.isEmpty() || local.length > 64) return false
        if (domain.length > 253) return false
        val labels = domain.split(".")
        if (labels.size < 2) return false
        for (lb in labels) {
            if (lb.isEmpty() || lb.length > 63) return false
            if (!LABEL_RE.matches(lb)) return false   // ★ 有大写 → 那是代码,不是域名
        }
        val tld = labels.last()
        if (tld.length !in 2..24) return false
        if (!tld.all { it in 'a'..'z' }) return false
        return true
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 二、出门那份:把号码换成标记
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 把 [text] 里认得出的号码换成 `[银行卡号]` 这类标记。**只给「出门那份」用。**
     *
     * ★ **不要拿它去改她手上的文本、也不要改她拿来点控件的那个名字** ——
     *   那正是「保持能读」要保住的东西。名字被改过她就会拿着星号去点,然后回一句「没找到」。
     *
     * 顺序:身份证 → 连着写的卡号 → 分开写的卡号 → 手机号 → 邮箱。
     * (身份证 18 位和卡号那三个长度不重叠;分成两条卡号正则是因为写法不同,不是有依赖。)
     */
    fun maskOutgoing(text: String): String =
        replaceEach(text) { kind, _ -> markOf(kind) }

    /**
     * 把每个认出来的号码换成 [onHit] 给的东西。**判据和 [maskOutgoing] 逐字相同** ——
     * 它们本来就是同一段代码,[maskOutgoing] 只是「一律换成类别标记」的那个用法。
     *
     * ★ 分出这一个入口是为了「假名」那一档(见 [AliasMath]):同一个号码每次要换成
     *   **同一个假名**,云端才看得出「这是同一个人两次」,而值一个字节都拿不到。
     *   两套判据各写一份的话,迟早一份遮、一份漏,而漏的那份是静默的。
     *
     * @param onHit (类别, 号码原值) → 替换成什么。**返回值会原样进正文**,
     *              所以别在里面拿不出替换词就抛异常 —— 那会把整段发送炸掉。
     */
    fun replaceEach(text: String, onHit: (kind: String, value: String) -> String): String {
        if (text.isEmpty()) return text
        var out = text
        out = ID_RE.replace(out) { m ->
            if (cnIdOk(m.groupValues[1])) onHit(KIND_ID, m.groupValues[1]) else m.value
        }
        out = CARD_RE.replace(out) { m ->
            if (cardShapeOk(m.groupValues[1])) onHit(KIND_CARD, m.groupValues[1]) else m.value
        }
        out = GROUPED_RE.replace(out) { m ->
            val digits = m.groupValues[1].replace(" ", "").replace("-", "")
            if (cardShapeOk(digits)) onHit(KIND_CARD, digits) else m.value
        }
        out = MOBILE_RE.replace(out) { m ->
            if (phoneShapeOk(m.groupValues[1])) onHit(KIND_PHONE, m.groupValues[1]) else m.value
        }
        out = EMAIL_RE.replace(out) { m ->
            if (emailShapeOk(m.groupValues[1], m.groupValues[2])) onHit(KIND_EMAIL, m.groupValues[1] + "@" + m.groupValues[2]) else m.value
        }
        // 密钥放最后:它的判据只看前缀,和上面四条不会互相吃。
        out = KEY_RE.replace(out) { m -> onHit(KIND_KEY, m.value) }
        return out
    }

    /**
     * 这段文字里会有哪些类别被拿掉 —— **回去记日志用,不含值本身**。
     *
     * ★ 同 `_risky_app` 那条老规矩:回执里不该出现敏感的东西,**哪怕是为了说明「我遮了它」**。
     * ★ 顺序**写死**(不按出现先后)—— 同一屏文字换个排法,日志不该跟着变。
     *
     * ⚠️ 要传**遮之前**的那份文本 —— 遮过了之后里面就没有东西可找了。
     */
    fun foundKinds(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val hit = mutableSetOf<String>()
        ID_RE.findAll(text).forEach { if (cnIdOk(it.groupValues[1])) hit += KIND_ID }
        CARD_RE.findAll(text).forEach { if (cardShapeOk(it.groupValues[1])) hit += KIND_CARD }
        GROUPED_RE.findAll(text).forEach {
            if (cardShapeOk(it.groupValues[1].replace(" ", "").replace("-", ""))) hit += KIND_CARD
        }
        MOBILE_RE.findAll(text).forEach { if (phoneShapeOk(it.groupValues[1])) hit += KIND_PHONE }
        EMAIL_RE.findAll(text).forEach {
            if (emailShapeOk(it.groupValues[1], it.groupValues[2])) hit += KIND_EMAIL
        }
        KEY_RE.findAll(text).forEach { hit += KIND_KEY }
        return listOf(KIND_CARD, KIND_ID, KIND_PHONE, KIND_EMAIL, KIND_KEY).filter { it in hit }
    }
}
