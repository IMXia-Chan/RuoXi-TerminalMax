package com.example.touchpad

import com.example.touchpad.EphemeralMath.DEFAULT_IDLE_WIPE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EphemeralMath] 的钉子。
 *
 * ## ★★ 为什么每个判据都要**两个方向**
 *
 * 这是这个项目的铁律:**一个坏掉的判据和一个准的判据,在没有正控的测试里长得一模一样**。
 * 「什么都不遮」的那一版,和「遮对了」的那一版,过的测试条数是一样的 ——
 * 除非你**同时**钉住「该遮的遮了」和「不该动的一个字节都没动」。
 *
 * 所以这个文件里**负控比正控多**,而且负控语料**一条都不是我编的**:
 *
 * | 语料 | 从哪来 |
 * |---|---|
 * | `11:06:32 [main] 房间:TTS 音色 5 种…` 等 | **真机日志** `.tmp-model.log`(266 行,2026-10-06 那天拉的) |
 * | `x0  0000000000000000  x1 …` 那几行 | **真崩溃墓碑** `.tmp-crash-before.txt`(2026-10-05 那次 `her-voice` SIGABRT) |
 * | `[ai] click_ui -> ok=True 11565ms clicked_in=记事本` | `pc-server/test_redact.py` 的 `_KEEP` 表(那边已经钉过一次) |
 * | `[emit] pairing {'pin': …}` | **真 `server.log`**,2026-10-06 那天 20 遍 |
 *
 * ★ **编出来的语料只会证明我编得对** —— 所以一行都不编。
 * ★ 而这几样东西**正是这个项目天天在读的东西**:读墓碑、读 model.log、读 server.log。
 *   判据要在这里成立,才算成立。
 */
class EphemeralMathTest {

    // ═══════════════════════════════════════════════════════════════════════
    // 一、来源闸(闸 0):控件名
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `正控 该认的控件名一个都不许漏`() {
        // 计划里点名要中的那一批,逐个过一遍 —— 漏一个就是「密码框被读了」。
        val shouldHit = mapOf(
            "密码" to "密码",
            "请输入登录密码" to "登录密码",
            "确认支付密码" to "支付密码",
            "验证码" to "验证码",
            "短信验证码" to "短信验证码",
            "动态码" to "动态码",
            "PIN" to "PIN",
            "CVV" to "CVV",
            "助记词" to "助记词",
            "Password" to "password",
            "pwd" to "pwd",
        )
        for ((name, want) in shouldHit) {
            assertEquals("「$name」该被认出来,而且要回那个词", want, EphemeralMath.sensitiveControlKind(name))
        }
    }

    @Test
    fun `★ 具体的词要压过笼统的词 —— 回给用户看的是前者`() {
        // 「我要登录密码」里 `密码` 和 `登录密码` 都命中;回「密码」说不清是哪一个框。
        assertEquals("登录密码", EphemeralMath.sensitiveControlKind("我要登录密码"))
        assertEquals("支付密码", EphemeralMath.sensitiveControlKind("请输入支付密码"))
        assertEquals("短信验证码", EphemeralMath.sensitiveControlKind("短信验证码"))
        // 反面:只有笼统那个的时候,照旧回笼统的
        assertEquals("密码", EphemeralMath.sensitiveControlKind("密码"))
    }

    @Test
    fun `★★ 负控 这个项目天天吃的东西一个都不许中`() {
        // 计划里点名「故意不收」的那三个(光一个 码 / 光一个 验证 / 光一个 token),
        // 外加它们带出来的一整片近亲。
        val mustNotHit = listOf(
            "二维码", "条形码", "编码", "编号", "验证", "校验和",
            "token", "前缀快照 token 数", "搜索", "发送", "文件名", "日志",
        )
        for (n in mustNotHit) {
            assertNull("「$n」不该被当成敏感控件 —— 认了它这个项目会天天误伤", EphemeralMath.sensitiveControlKind(n))
        }
    }

    @Test
    fun `★★ 负控 ASCII 词要词边界 —— spin typing pinned 这些不是 pin`() {
        // ★ 真语料里这些天天出现(内核符号 spin_lock、Kotlin 标识符 typing/pinned)。
        //   只看 contains 的话,这张表会把整个日志都判成凭据。
        val mustNotHit = listOf(
            "spin_lock", "spinlock", "typing", "pinned", "ping", "pinging", "spinner",
            "captchax", "tokenizer", "mnemonics",
        )
        for (n in mustNotHit) {
            assertNull("「$n」里的那几个字母不是那个词", EphemeralMath.sensitiveControlKind(n))
        }
        // 正面:边界两侧不是字母时,该中的照样中(否则就是收得太狠)
        assertEquals("PIN", EphemeralMath.sensitiveControlKind("user_pin"))
        assertEquals("PIN", EphemeralMath.sensitiveControlKind("PIN码"))
        assertEquals("PIN", EphemeralMath.sensitiveControlKind("(PIN)"))
        assertEquals("PIN", EphemeralMath.sensitiveControlKind("pin1234"))
        assertEquals("captcha", EphemeralMath.sensitiveControlKind("Captcha"))
    }

    @Test
    fun `★★ 负控 真日志和真墓碑的整行,控件判据一个都不许中`() {
        // ⚠️ 这里**排除了 pairing 那一行** —— 它真的含 `pin` 这个词,而那是**对的**,不是误报。
        //    原因写在下面那条专门的测试里。★ 一条真语料被排除,**理由必须摆在明面上**,
        //    不能悄悄 filter 掉 —— 那就成了「改测试让红灯变绿」。
        for (line in REAL_CORPUS.filter { it != PAIRING_LINE }) {
            assertNull("这一行是真东西,判据在它身上不许响:\n$line", EphemeralMath.sensitiveControlKind(line))
        }
    }

    @Test
    fun `★★ 那行 pairing 里的 pin 是真的词 —— 闸 0 只在控件名上跑,不从日志上跑`() {
        // ★★ 这条是我写测试时踩出来的,留着当记录,免得以后有人以为它是误报:
        //    我一开始把 server.log 那行 pairing 塞进了上面那组「控件判据不许中」,然后它红了 ——
        //    而**红了是对的**:那行里 `pin` 前后是引号,词边界成立,它本来就该被认出来。
        //    它不是误报 —— 闸 0 喂进来的是**控件的名字**(`请输入登录密码` / `Password`),
        //    **从来不喂日志行**。把「日志行」和「控件名」混成一锅的是我,不是判据。
        // ★ 它照旧必须**一个字节都不许动** —— 下面那两条「不许被改」的测试钉着它。
        assertEquals("PIN", EphemeralMath.sensitiveControlKind(PAIRING_LINE))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 二、词锚定的短数字
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `正控 词后面紧挨着的那串数字要遮掉`() {
        assertEquals(
            "把验证码 [验证码] 填进去",
            EphemeralMath.maskKeywordAnchored("把验证码 123456 填进去"),
        )
        // 各种连接写法都要跨得过去 —— 少认一种,用户就得手打一次密码
        assertEquals("密码[密码]", EphemeralMath.maskKeywordAnchored("密码123456"))
        assertEquals("密码：[密码]", EphemeralMath.maskKeywordAnchored("密码：123456"))
        assertEquals("密码是[密码]", EphemeralMath.maskKeywordAnchored("密码是123456"))
        assertEquals("密码为 [密码]", EphemeralMath.maskKeywordAnchored("密码为 123456"))
        assertEquals("PIN码 [PIN]", EphemeralMath.maskKeywordAnchored("PIN码 1234"))
        assertEquals("口令=[口令]", EphemeralMath.maskKeywordAnchored("口令=1234"))
        // ★ 3 位也算(CVV 只有 3 位;而多遮一位的代价只是云端读起来别扭)
        assertEquals("CVV [CVV]", EphemeralMath.maskKeywordAnchored("CVV 123"))
    }

    @Test
    fun `负控 词在那儿但后面没数字 —— 一个字都不许动`() {
        val untouched = listOf(
            "密码我忘了",
            "验证码发到他手机上了",
            "密码是这个吗 3 个字符",       // 隔了「这个吗」,跨不过去
            "验证码错误 3 次",             // 同理:连接符最多 5 个,而且 `错` 不是连接符
            "二维码扫不出来",
            "前缀快照 token 数 4889",
            "编号 12345678",               // ★ 这个词不在表里,数字再长也不动它
        )
        for (s in untouched) {
            assertEquals("「$s」不该被改", s, EphemeralMath.maskKeywordAnchored(s))
        }
    }

    @Test
    fun `★★ 负控 真语料整行,一个字节都不许动`() {
        for (line in REAL_CORPUS) {
            assertEquals("这一行是真东西,遮字在它身上不许响:\n$line", line, EphemeralMath.maskKeywordAnchored(line))
        }
    }

    @Test
    fun `★ 数字在词前面 —— 够不着,而且这是故意的`() {
        // ★ 不装看不见:这是已知的够不着的地方,钉在这儿免得以后有人以为它覆盖了双向。
        //   做成双向的代价是「3 次密码错误」里的 `3` 也会被吃掉。
        assertEquals("123456 是验证码", EphemeralMath.maskKeywordAnchored("123456 是验证码"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 三、回去记日志的那一份:**回词,不回值**
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `★★ 报出去的是词,那串数字一个字符都不许出现`() {
        val text = "把验证码 123456 填进去,支付密码是 654321"
        val words = EphemeralMath.keywordAnchoredWords(text)
        assertEquals("遮了哪两类,按写死的顺序", listOf("支付密码", "验证码"), words)

        // ★★ 这才是这一条的重点:日志里那一行**不可能把值带出去**。
        val reported = words.toString()
        assertFalse("回出去的东西里不许有那串数字", reported.contains("123456"))
        assertFalse("回出去的东西里不许有那串数字", reported.contains("654321"))
    }

    @Test
    fun `负控 没锚住数字的词不进报告 —— 报告说的是「拿掉了什么」`() {
        // 同 SecretMath.foundKinds 的口径:回的是**会被拿掉的**,不是「句子里出现过」。
        assertTrue(EphemeralMath.keywordAnchoredWords("密码我忘了").isEmpty())
        assertTrue(EphemeralMath.keywordAnchoredWords("").isEmpty())
        assertEquals(listOf("密码"), EphemeralMath.keywordAnchoredWords("密码是1234"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 四、★★ 所有闸共用的那一个判断
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `★ 有形状的先走,词锚定的兜底 —— 两步都要在`() {
        // 卡号(有形状)和验证码(没形状)同时出现:两个都得没。
        assertEquals(
            // ★ 注意 `是` 和 `[验证码]` 中间**那个空格留下了是对的** —— 词和数字之间的连接符
            //   是**原样保留**的(同 `密码为 123456` → `密码为 [密码]`),被拿掉的只有那串数字。
            "卡 [银行卡号] 的验证码是 [验证码]",
            EphemeralMath.scrubText("卡 4111111111111111 的验证码是 123456"),
        )
        // 手机号那一类(有形状但没校验位)
        assertEquals("打 [手机号]", EphemeralMath.scrubText("打 13812345678"))
        // 只有词锚定那一步能抓的东西
        assertEquals("PIN [PIN]", EphemeralMath.scrubText("PIN 1234"))
    }

    @Test
    fun `★★ 负控 真语料过一遍 scrubText,逐字节不变`() {
        // 这是**最硬的一条**:真日志、真墓碑、真 server.log 行,一个字都不许被改动。
        // ★ 只要它红了,就说明某条判据在真东西上误伤了 —— 而这个项目靠这几样东西活着。
        for (line in REAL_CORPUS) {
            assertEquals("这一行被改了,那就是误伤:\n$line", line, EphemeralMath.scrubText(line))
        }
    }

    @Test
    fun `★ server_log 里那行 pairing 带着 pin 三个字母,但它没有值`() {
        // ★ 真 server.log 里出现过 20 遍:
        //   [emit] pairing {'pin': '<凭证:只在界面上显示,不落日志>'}
        // 这个词**本来就该被认出来**(它是凭据字段名),但那行**必须原样不动** ——
        // 因为 `pin` 后面跟的不是数字,是那个已经打好码的占位串。
        assertEquals("占位串照旧,不能被套上第二层标记", PAIRING_LINE, EphemeralMath.scrubText(PAIRING_LINE))
        // 但**值真在的时候**必须遮得住(JSON 那种写法,`'` `:` ` ` `'` 四个连接符)
        assertEquals("pairing {'pin': '[PIN]'}", EphemeralMath.scrubText("pairing {'pin': '1234'}"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 五、抹的时机
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `★★ 还有任何一件事在跑,就绝不抹 —— 那是搬运的命根子`() {
        // 读出来 → 切到 WPS → 找到框 → 填进去,中间任何一刻抹掉,那件事就断了。
        assertFalse(
            "闲置再久也不许在任务中途抹",
            EphemeralMath.wipeDue(nowMs = 10_000_000L, lastActivityMs = 1L, anythingRunning = true),
        )
        // ★ 参数名是 anythingRunning 不是 running:并行之后「我这件完了」≠「没别的事在跑」
        assertFalse(EphemeralMath.wipeNow(anythingRunning = true))
        assertTrue(EphemeralMath.wipeNow(anythingRunning = false))
    }

    @Test
    fun `闲置到点就抹,差一毫秒不抹`() {
        val base = 1_000_000L
        assertFalse(
            "差 1 毫秒 —— 还没到",
            EphemeralMath.wipeDue(base + DEFAULT_IDLE_WIPE_MS - 1, base, anythingRunning = false),
        )
        assertTrue(
            "整好到点 —— 算到了",
            EphemeralMath.wipeDue(base + DEFAULT_IDLE_WIPE_MS, base, anythingRunning = false),
        )
        assertTrue(
            "过了很久 —— 更该抹",
            EphemeralMath.wipeDue(base + DEFAULT_IDLE_WIPE_MS * 10, base, anythingRunning = false),
        )
        // 门槛可以传进来(调用点要换一个数的时候不用改这里)
        assertTrue(EphemeralMath.wipeDue(100L, 0L + 1L, anythingRunning = false, idleMs = 99L))
    }

    @Test
    fun `★ 从没活动过 —— 不抹,因为「这件事」根本不存在`() {
        // 回 true 也无害(没有东西可抹),但那是把「说不清的状态」当成「该抹了」。
        // 这个项目已经吃够了「说不清就等于默认放行」的亏,方向反过来定。
        assertFalse(EphemeralMath.wipeDue(9_999_999L, lastActivityMs = 0L, anythingRunning = false))
        assertFalse(EphemeralMath.wipeDue(9_999_999L, lastActivityMs = -5L, anythingRunning = false))
    }

    @Test
    fun `★ 时钟回拨 —— 不抹,宁可多躺一会儿`() {
        // NTP 校正 / 时区 / 手动改表都算。两个方向的代价不对称:
        // 不抹 = 值多躺一会儿(定时器下次还会再判);抹错 = 搬运当场断掉。
        assertFalse(
            "现在比最后一次活动还早 —— 说不清,不抹",
            EphemeralMath.wipeDue(nowMs = 500L, lastActivityMs = 1_000_000L, anythingRunning = false),
        )
    }

    companion object {

        /**
         * ★ 真 `server.log` 里出现过 20 遍的那一行。
         *
         * **单独拎出来是因为它和别的真语料不一样**:别的行里一个词表词都没有,
         * 而这一行**真的含 `pin`** —— 它前后是引号,词边界成立。
         * 所以「控件判据在真语料上不许响」那一组**必须把它择出来**(见那一组的注释),
         * 同时它在「一个字节都不许被改」那一组里**照旧必须过**。
         */
        private const val PAIRING_LINE: String = "[emit] pairing {'pin': '<凭证:只在界面上显示,不落日志>'}"

        /**
         * ★★ **全部是真东西,一行都不是我编的。**
         *
         * 前四条从 `.tmp-model.log` 抄(2026-10-06 那天拉的真机日志);
         * 中间四条从 `.tmp-crash-before.txt` 抄(2026-10-05 那次 `her-voice` SIGABRT 的真墓碑);
         * 最后两条分别来自 `test_redact.py` 的 `_KEEP` 表和真 `server.log`。
         *
         * ⚠️ **改它们等于改判据的靶子** —— 要改先想清楚为什么。
         */
        private val REAL_CORPUS: List<String> = listOf(
            "11:06:32 [main] 房间:TTS 音色 5 种,其中中文 0 种 (pitch=0.9 rate=0.92)",
            "11:06:32 [main] [station] createHandle 书签条 右侧 x=1038 y=1067 屏=1080x2374",
            "11:06:32 [main] HerLife:服务起(误杀重投=false,累计=1次,defer=false)",
            "11:06:32 [ruoxi-warmup] 前缀快照还原成功(4889 token),跳过冷算",
            "10-05 11:20:05.422 13233 13233 F DEBUG   : Abort message: 'No pending exception expected: " +
                "java.lang.NoSuchMethodError: no non-static method " +
                "\"Lcom/example/touchpad/SherpaVoice\$\$ExternalSyntheticLambda0;.invoke([F)Ljava/lang/Integer;\"",
            "10-05 11:20:05.422 13233 13233 F DEBUG   :     x0  0000000000000000  x1  00000000000022da  " +
                "x2  0000000000000006  x3  00000079ca1f6bc0",
            "10-05 11:20:05.422 13233 13233 F DEBUG   : tagged_addr_ctrl: 0000000000000001 (PR_TAGGED_ADDR_ENABLE)",
            "10-05 11:20:05.422 13233 13233 F DEBUG   :       #00 pc 00000000000aaf40  " +
                "/apex/com.android.runtime/lib64/bionic/libc.so (abort+160) " +
                "(BuildId: 3b6cfe86cd4ffefb8a2bb012b110984f)",
            "[ai] click_ui -> ok=True 11565ms clicked_in=记事本",
            PAIRING_LINE,
        )
    }
}
