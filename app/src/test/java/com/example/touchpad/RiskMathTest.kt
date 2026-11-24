package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这一轮要不要一路问你」的测试。
 *
 * ★★ 这一层是**安全承重墙**,所以它测的不是算法,是两条方向相反的代价:
 *
 * | 判错的方向 | 后果 |
 * |---|---|
 * | 该进高危档没进 | ★ **钱直接就出去了**,没有任何一层会拦 |
 * | 不该进高危档进了 | 他每次动手都被问一次 → 嫌烦 → **把整个功能关掉** |
 *
 * 第二条不是「体验问题」:一条会被关掉的安全功能等于没有。所以下面**两个方向都钉死** ——
 * 尤其是「日常那些话**不许**被拖下水」那一组,它防的是我自己手滑把名单放宽。
 */
class RiskMathTest {

    // --------------------------------------------------------- 该拦的

    /** ★ 钱和身份,出现任何一个就整轮盯着。 */
    @Test
    fun `钱和身份的词进高危档`() {
        for (s in listOf(
            "给张三转账", "把这个付款了", "发个红包给妈", "提现到银行卡",
            "下单买那个", "帮我充值", "密码是多少", "把验证码念给我",
        )) {
            assertEquals("「$s」没进高危档", RiskMath.Tier.HIGH, RiskMath.taskTier(s))
        }
    }

    /**
     * ★★ **最自然的说法根本不带「转账」两个字。**
     *
     * 「给张三转 50」「转五万给他」和「给张三转账」是同一件事,而上面那张表一个都对不上。
     * 这个洞是 `RiskMath` 自己写在文件头当例子的那句,不能留着。
     */
    @Test
    fun `光有转字但有金额的也算高危`() {
        for (s in listOf("给张三转 50", "转五万给他", "给他付 200", "充 100 话费")) {
            assertEquals("「$s」没进高危档", RiskMath.Tier.HIGH, RiskMath.taskTier(s))
        }
        // 汉字数字漏了(「转五十」) —— 这是**明知**的漏,由电脑侧第二层接住。
        // 钉在这里是提醒:以后谁想补它,先想清楚会不会把「转成 PDF」也拖进来。
    }

    /**
     * ★★ **这一层明知会漏,而且漏得掉 —— 漏的方向由电脑侧第二层接住。**
     *
     * 「把这个付了」是**真会说的话**(没带金额、也没带表里任何一个词),这一层判不出来。
     * 所以它必须落进第二层:那一下真正点的控件叫「确认支付 / 立即付款」,
     * 电脑侧按**控件名**拦。两层要在**不同的东西**上判,才叫两层。
     *
     * ★ 这条测试的用处是**防止有人把这一层当成唯一一道闸**:如果哪天第二层没了,
     *   这几种说法就整个裸奔,而日志上什么都看不出来。
     */
    @Test
    fun `明知会漏的几种说法_必须由第二层接住`() {
        for (s in listOf("把这个付了", "给张三转五十", "付掉它")) {
            assertEquals(
                "「$s」现在的判定变了 —— 要么它被收进名单了(好,删掉这条)," +
                    "要么它变成高危了(看会不会误伤日常用词)。**别默默改了不更新这里。**",
                RiskMath.Tier.NORMAL, RiskMath.taskTier(s),
            )
        }
    }

    // --------------------------------------------------------- 不许误伤的(★ 这一组最要紧)

    /**
     * ★★ **他每天在用的那些话,一个字都不许被拖下水。**
     *
     * 名单收窄到「只认钱和身份」正是为了这一组(2026-10-05 用户当场定的)。
     * 「微信」被去掉是因为**它单独出现时不代表任何事要发生** ——
     * 一句「给文件传输助手发一条」里每一步都点头,他两天就会把这个开关关掉。
     */
    @Test
    fun `日常的话不许被拖进高危档`() {
        for (s in listOf(
            "打开微信", "给文件传输助手发一条消息说我晚点回", "打开浏览器搜一下天气",
            "把这段话复制到记事本", "把窗口切到网易云音乐", "帮我查一下上海天气",
            "上一首", "截图给我看看",
        )) {
            assertEquals("「$s」被误判成高危了", RiskMath.Tier.NORMAL, RiskMath.taskTier(s))
        }
    }

    /**
     * ★★ **「转」这个字单独不成话,配上文件格式就是另一件事。**
     *
     * 「转成 PDF」「翻译成英文」每天都在用,而它们和「转 50」共用一个字。
     * 判据是「有没有一笔钱的样子」—— 这一条把边界划在代价上:
     * 误判它们只是多问一次,但**问的是他天天在做的事**,那就会把功能问没。
     */
    @Test
    fun `转成文件格式不算高危`() {
        for (s in listOf(
            "把这个文档转成 PDF", "把那句话翻译成英文", "转到下一个文件夹",
            "把视频转个格式", "付这个表格的空格填一下", "充一下这个输入框的默认值",
        )) {
            assertEquals("「$s」被误判成高危了", RiskMath.Tier.NORMAL, RiskMath.taskTier(s))
        }
    }

    /** 空话、闲聊一律不进高危档 —— 她是伴侣,不是保安。 */
    @Test
    fun `闲聊不算高危`() {
        for (s in listOf("", "   ", "在吗", "今天好累", "讲个笑话")) {
            assertEquals("「$s」被误判成高危了", RiskMath.Tier.NORMAL, RiskMath.taskTier(s))
        }
    }

    // --------------------------------------------------------- 告诉他原因的那句话

    /**
     * ★★ **只回命中的那个词,绝不回原句。**
     *
     * 原句是他自己打的,里面完全可能带着一串验证码(「把验证码 123456 打进去」)——
     * 而那串数字**绝不能回显**(它要进日志、进字幕、进模型上下文)。
     * 回「验证码」三个字既说清了原因,又不可能把敏感的东西带出来。
     */
    @Test
    fun `报原因时不许把原句回显出来`() {
        val s = "把验证码 123456 输进去"
        val w = RiskMath.matchedWord(s)
        assertNotNull("这句没命中,下面的断言就没意义了", w)
        val why = RiskMath.whyHighTier(w!!)
        assertFalse("原因里带出了原句里的数字: $why", why.contains("123456"))
        assertTrue("原因里没说清是哪个词触发的: $why", why.contains(w))
    }

    /** 命中「转 + 金额」时回的是那个动词,不是整句。 */
    @Test
    fun `金额那一档回的是光秃秃的那个动词`() {
        val w = RiskMath.matchedWord("给张三转 50 块")
        assertEquals("转", w)
    }

    /** 没命中就是 null —— 别硬凑一个理由出来。 */
    @Test
    fun `没命中就回 null`() {
        assertNull(RiskMath.matchedWord("打开浏览器"))
    }

    // --------------------------------------------------------- 哪些动作要点头

    /**
     * ★★ **碰鼠标键盘的那几个,高危档下全都要点头。**
     *
     * 这份名单是**故意手写**的(不是从别处推出来的):以后加一个新的写工具
     * (比如 `set_value`),唯一会让人想起来补它的地方就是这里红一条。
     * 电脑侧的白名单 `_GUARDED_TOOLS` 就吃过这个亏 —— **新工具不在名单里 = 完全没有守门**,
     * 而且不报错。
     */
    @Test
    fun `高危档下碰鼠标键盘的都要点头`() {
        for (t in listOf("click_at", "click_ui", "click_element", "hotkey", "search")) {
            assertTrue("「$t」在高危档下没被拦", RiskMath.needsTap(RiskMath.Tier.HIGH, t))
        }
    }

    /**
     * ★ **`type` 故意不在名单里** —— 它不是被放过,是**本来就有自己那道闸**,
     * 而且那道闸更具体(它把要打的**内容**摆给他看)。
     * 放进来的话一次打字弹两个框,人会开始闭着眼点 —— 那比不问还糟。
     */
    @Test
    fun `打字不弹第二个框`() {
        assertFalse("type 弹了第二个框", RiskMath.needsTap(RiskMath.Tier.HIGH, "type"))
    }

    /**
     * ★ **只读的和「决定焦点在哪」的不问。**
     *
     * `open_app` / `focus_window` 决定的是焦点,不往里面送东西 —— 真正危险的是
     * **在**那个窗口里做的事,而那些事在上面那份名单里。
     * `scroll` 不动任何东西:问它一次只是让他烦一次,而漏问一次的代价是零。
     */
    @Test
    fun `只读的和切窗口的不问`() {
        for (t in listOf(
            "list_windows", "get_state", "screenshot", "list_ui", "read_screen",
            "open_app", "focus_window", "scroll", "list_hands", "use_hand", "media",
        )) {
            assertFalse("「$t」被拦了(它不改变电脑上的东西)", RiskMath.needsTap(RiskMath.Tier.HIGH, t))
        }
    }

    /**
     * ★★ **普通档下什么都不问。**
     *
     * 这条看着像废话,它防的是最要命的那种写法:把判定写成
     * 「只要工具在名单里就问」—— 忘了带上档位。那样**每一次点击都要点头**,
     * 功能当天就会被关掉。判定必须**两个条件同时成立**。
     */
    @Test
    fun `普通档下什么都不问`() {
        for (t in listOf("click_at", "click_ui", "click_element", "hotkey", "search", "type")) {
            assertFalse("普通档下「$t」也被拦了", RiskMath.needsTap(RiskMath.Tier.NORMAL, t))
        }
    }

    // --------------------------------------------------------- 那个框上写什么

    /** ★ 标题必须是**人话**,不是工具名 —— 他看的是「要点一下」,不是「click_ui」。 */
    @Test
    fun `弹框标题里不许出现工具名`() {
        for (t in listOf("click_at", "click_ui", "click_element", "hotkey", "search")) {
            val title = RiskMath.tapTitle(t)
            assertTrue("标题是空的($t)", title.isNotBlank())
            assertFalse("标题里出现了工具名: $title", title.contains(t))
        }
    }

    /** ★★ 视觉那条路要说清「慢」—— 否则他把「她在看图」当成「她卡住了」。 */
    @Test
    fun `视觉那条路的标题要说清慢`() {
        val t = RiskMath.tapTitle("click_element")
        assertTrue("没说清要等: $t", t.contains("慢") || t.contains("看图") || t.contains("等"))
    }

    /** ★ 原因那句话不许是空的 —— 空的原因在界面上什么都不显示,等于静默。 */
    @Test
    fun `原因那句话不许是空的`() {
        assertTrue(RiskMath.whyHighTier("转账").isNotBlank())
    }
}
