package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话循环里那几个纯判断的测试。
 *
 * 和 `EarMathTest` 是同一路数:**这里防的错全都不抛异常**,
 * 它们的表现是「她不理我了」或者「对话莫名其妙断了」——
 * 真机上要分辨这两种得先排除十几个别的可能,在这里是一秒钟的事。
 */
class EarSessionMathTest {

    // ---------------------------------------------------------------- 挂断

    /**
     * ★ 这条钉的是**判据的形状**:整句等于。
     *
     * 句尾带标点是识别的常态 —— 真机跑出来的文本几乎总是「不聊了。」而不是「不聊了」。
     * 不剥标点的话,这四个用例里有两个永远不成立,而症状是
     * 「我说了不聊了,她还接着说」。
     */
    @Test
    fun `挂断_整句就算数_标点空白剥掉`() {
        assertTrue(EarSessionMath.isExitPhrase("不聊了"))
        assertTrue("句尾带句号是常态", EarSessionMath.isExitPhrase("不聊了。"))
        assertTrue("感叹号也一样", EarSessionMath.isExitPhrase("再见!"))
        assertTrue("两头的空白要剥掉", EarSessionMath.isExitPhrase("  拜拜  "))
        assertTrue(EarSessionMath.isExitPhrase("退下吧"))
    }

    /**
     * ★★ 这一条是**反向**的,而且是故意的:**实词一进来就不认。**
     *
     * 「行了不聊了」是一句很自然的挂断 —— 但判据一旦放宽到「以挂断词结尾」,
     * 下面两条真话就会被误杀(见后两个用例)。而误杀的代价是
     * **对话在她正需要继续的时候突然断掉**,用户会以为坏了。
     *
     * 所以漏掉这句是**设计**,不是缺陷:漏了只是多听一轮。
     * ★ 2026-10-05 判据放宽成「剥垫词再整句对」之后,这一条**一个字都没让** ——
     *   「行」「这样」都不是垫词,剥的动作在第一个字就停住了。
     */
    @Test
    fun `挂断_多一个字就不认_宁可漏不可错`() {
        assertFalse("放宽到「结尾是」就会误杀下面两条", EarSessionMath.isExitPhrase("行了不聊了"))
        assertFalse(EarSessionMath.isExitPhrase("那我们先这样吧不聊了"))
    }

    /**
     * ★★ 2026-10-05 用户当面点名要的两句。
     *
     * 原话:「我说『**那你先退下吧**』,『**退出**』等等,就关闭麦克风模式」
     *
     * ★ 老判据(整句等于)对第一句是**必然失效**的 —— 六个字,词表里只有「退下吧」。
     *   而「那你先」正是人说话时最常见的垫法:他要挂断,还得先想一下
     *   这句判据认不认,那这个功能就是白做的。
     *
     * ★ 放宽只在**两头**,而且是**封闭的一小把垫词**(见 `FILLER_HEAD` / `FILLER_TAIL`)。
     */
    @Test
    fun `挂断_垫词和语气词剥掉之后算数`() {
        assertTrue("用户点名的那一句", EarSessionMath.isExitPhrase("那你先退下吧"))
        assertTrue("识别结果常带句号", EarSessionMath.isExitPhrase("那你先退下吧。"))
        assertTrue("用户点名的另一个词", EarSessionMath.isExitPhrase("退出"))
        assertTrue(EarSessionMath.isExitPhrase("退出。"))
        assertTrue("只垫一个称呼", EarSessionMath.isExitPhrase("你退下吧"))
        assertTrue("语气词换了", EarSessionMath.isExitPhrase("那你先退下啊"))
        assertTrue("没有语气词", EarSessionMath.isExitPhrase("你先退下"))
    }

    /**
     * ★★ 放宽之后**必须**守住的四条 —— 它们是上一条的护栏。
     *
     * 剥垫词这件事唯一的安全来源是:**实词一旦出现在两头,剥的动作当场停住**。
     * 下面每一句里都藏着一个实词(让 / 跟 / 说 / 给),所以一个字都剥不掉,
     * 也就永远不中 —— 这正是它和「结尾是」的分水岭。
     */
    @Test
    fun `挂断_放宽之后实词仍然挡得住`() {
        assertFalse("「让」是实词:这是给她的指令", EarSessionMath.isExitPhrase("让他退下吧"))
        assertFalse("同上", EarSessionMath.isExitPhrase("叫他退下吧"))
        assertFalse("「跟」「说」都是实词", EarSessionMath.isExitPhrase("跟他说再见"))
        assertFalse("「跟」「说」挡在前面,剥不动", EarSessionMath.isExitPhrase("你说跟他说再见"))
        assertFalse("「要给」是实词", EarSessionMath.isExitPhrase("那我要给他退下了"))
    }

    /**
     * ★★ 假阳性防线之一:**命令 vs 挂断**。
     *
     * 「跟他说再见」是让他去操作电脑(她完全有能力做这件事),
     * 不是要结束跟她的对话。判成挂断的话,她会在用户正指挥她干活的时候挂断。
     * 这个项目在 `FastPath` 上吃过一模一样的亏:规则层认识的字越多,能犯的错越多。
     */
    @Test
    fun `挂断_别把命令当挂断`() {
        assertFalse("这是让她去操作电脑", EarSessionMath.isExitPhrase("跟他说再见"))
    }

    /**
     * ★★ 假阳性防线之二:**回答 vs 挂断**。
     *
     * 「不用了」多半是在回答她的问题(「要我帮你打开吗?」)。
     * 把它当挂断 = 她每次问完一个问题、用户回答「不用了」,对话就断了 ——
     * 而那正是对话**该继续**的时候。
     * 所以「不用了」「没事了」「算了」**故意都不在词表里**。
     */
    @Test
    fun `挂断_别把回答当挂断`() {
        assertFalse("这是在回答她的问题", EarSessionMath.isExitPhrase("不用了"))
        assertFalse(EarSessionMath.isExitPhrase("没事了"))
        assertFalse(EarSessionMath.isExitPhrase("算了"))
    }

    /** 剥完只剩标点 = 什么都没说,不是挂断。空串同理。 */
    @Test
    fun `挂断_空的和纯标点都不算`() {
        assertFalse(EarSessionMath.isExitPhrase(""))
        assertFalse(EarSessionMath.isExitPhrase("   "))
        assertFalse("剥完是空的", EarSessionMath.isExitPhrase("。。。"))
    }

    /** 普通话当然不是挂断 —— 防的是「词表里塞了个常用词」这种事故。 */
    @Test
    fun `挂断_普通话不误伤`() {
        listOf(
            "帮我打开微信",
            "电脑上那个记事本切到前面",
            "今天天气怎么样",
            "好",
        ).forEach {
            assertFalse("这句不该被当成挂断:$it", EarSessionMath.isExitPhrase(it))
        }
    }

    // ---------------------------------------------------------------- 等她的声音

    /**
     * ★★ 这个文件里最值钱的一条。
     *
     * 嗓子关着、引擎没就绪、她的回复出错 —— 这几种情况下 TTS 的 `onDone`
     * **一个都不会来**。如果这时候还按「等她念完」去等兜底时长,
     * 每一轮都会空转 90 秒。用户看到的是「她说了一句,然后死机一样不理我了」,
     * 而**日志里一片太平**(因为什么都没发生)。
     *
     * 明知道她不会出声,就一秒都不能等。
     */
    @Test
    fun `等待_她不会出声就一秒都不等`() {
        assertEquals(
            "不会出声还等兜底 = 每轮空转 90 秒,表现是「她不理我了」",
            0L,
            EarSessionMath.speechWaitMs(willSpeak = false)
        )
    }

    /** 她会出声才用得上兜底,而且兜底必须真的存在(不能是 0,否则长段落会被拦腰打断)。 */
    @Test
    fun `等待_她会出声才等_而且兜底够长`() {
        assertEquals(EarSessionMath.SPEAK_MAX_MS, EarSessionMath.speechWaitMs(willSpeak = true))
        assertTrue(
            "她念一段长话要好几十秒,兜底短了会在她正说着的时候抢麦",
            EarSessionMath.SPEAK_MAX_MS >= 60_000L
        )
    }

    // ---------------------------------------------------------------- 半双工

    /**
     * ★★ 冷却**不能是零**。
     *
     * `onDone` 回来的那一刻,扬声器的**尾音还没散干净**(这台机器是外放)。
     * 立刻开麦的话,最后半个字会被当成一句新输入 → 她自问自答。
     * 而那得到的是一句**看起来完全正常的用户输入**,从日志上根本看不出是假的。
     *
     * 这条只有「大于零」能测 —— 真正的半双工在真机上验(见 `Ear` 的耳朵验证清单)。
     */
    @Test
    fun `冷却_不能是零_否则尾音会被听成输入`() {
        assertTrue("零冷却 = 自问自答的入口", EarSessionMath.COOLDOWN_MS > 0)
    }

    /** 冷却从「她念完」那一刻往后算,不是从现在算 —— 传进去的是墙上时间。 */
    @Test
    fun `冷却_从她念完那刻往后算`() {
        assertEquals(1000L + EarSessionMath.COOLDOWN_MS, EarSessionMath.resumeAt(1000L))
        assertEquals(0L + EarSessionMath.COOLDOWN_MS, EarSessionMath.resumeAt(0L))
    }

    // ------------------------------------------------------------------
    // 45 秒:整场对话能安静多久(用户 2026-10-04 点名的那个数)
    // ------------------------------------------------------------------

    /**
     * ★ 这个数**是他给的**(「我 45 秒内没说话,就自动关闭麦克风」)。
     *
     * 这条不是仪式性的:44 秒和 45 秒的差别正是「够不够想一下再开口」。
     * 谁要改它,得先看见它钉在这儿。
     */
    @Test
    fun `整场安静预算_就是他说的 45 秒`() {
        assertEquals(45_000L, EarSessionMath.SESSION_SILENCE_MS)
    }

    /**
     * ★★ 边界**必须**钉死,因为这条判据的两侧行为完全相反:
     * 一边是「再听一轮」,一边是「把麦克风关掉」。
     * 差一毫秒就变成「他刚想开口,麦已经关了」或者「早该收了还在听」。
     */
    @Test
    fun `整场安静预算_差一毫秒不算到点_整点到点`() {
        assertFalse("44999ms 还没到", EarSessionMath.sessionOver(44_999L))
        assertTrue("45000ms 整点到点", EarSessionMath.sessionOver(45_000L))
        assertTrue("超过当然到点", EarSessionMath.sessionOver(60_000L))
    }

    /**
     * ★ 一秒钟都不安静时**绝不能**判成结束 —— 它是每一轮 `onDone` 都会走一遍的判据,
     *   而绝大部分轮次是「他刚说完、她答完、立刻又开麦」,安静时间接近 0。
     *   这里要是判反了,症状是「她答完第一句就把麦关了」,也就是回到修之前那副样子。
     */
    @Test
    fun `整场安静预算_刚开始的一轮不该被收掉`() {
        assertFalse(EarSessionMath.sessionOver(0L))
        assertFalse(EarSessionMath.sessionOver(EarSessionMath.COOLDOWN_MS))
    }

    // ------------------------------------------------------------------
    // 同一个手指上的两个动作:点一下 = 打断,按住 = 说话
    // ------------------------------------------------------------------

    /**
     * ★ 分界线两侧做的是**完全相反**的事(松手即收尾转写 vs 把这几百毫秒扔掉),
     *   所以边界值必须定死。
     */
    @Test
    fun `点与按住_差一毫秒算点_整点算按住`() {
        assertTrue("349ms 是点", EarSessionMath.isTap(349L))
        assertFalse("350ms 是按住", EarSessionMath.isTap(350L))
    }

    /** 手指刚碰一下就抬起,那是「点」,不是「按住说话」。 */
    @Test
    fun `点与按住_一碰就抬是点`() {
        assertTrue(EarSessionMath.isTap(0L))
        assertTrue(EarSessionMath.isTap(80L))
    }

    /**
     * 按住说话**松手就要收尾**,不等 VAD。
     *
     * ★ 这条的代价是「手感」:松手之后再等 5 秒 VAD,用户会以为卡了 ——
     *   而他刚刚才为「说了话没反应」找过我,这种等待他一定会读成「又坏了」。
     */
    @Test
    fun `点与按住_按住松手要当场收尾_点一下不要`() {
        assertTrue(EarSessionMath.shouldFinishOnRelease(350L))
        assertTrue(EarSessionMath.shouldFinishOnRelease(3_000L))
        assertFalse("点一下是打断,不是说完", EarSessionMath.shouldFinishOnRelease(100L))
    }

    // ------------------------------------------------------------------
    // 半双工闸:她的嗓子空了没有(2026-10-05 自问自答那一条)
    // ------------------------------------------------------------------

    /**
     * ★★ **这三行是「她跟自己聊上了」那笔账的判据。**
     *
     * 用户原话(和日志里量到的**一模一样**):
     * > 「她还没说完,系统就误认为她说完,就打开麦克风,然后她就自己跟自己聊起来了」
     *
     * 失败的样子是**不报错**:她录到的是自己刚念的那句,而它语法通顺、上下文合理,
     * 日志上看不出任何破绽 —— 只有把人声和 ASR 输出对起来才发现是她自己。
     */
    @Test
    fun `她嗓子空了没有_真的说完了才算完`() {
        assertTrue("没在念、队也空了 —— 只有这一格能开麦", EarSessionMath.herVoiceFinished(false, 0))
    }

    /**
     * ★★ 中间那一行是**整件事的要害**:正在合成下一句。
     *
     * 这台机器上合成**比实时慢**(实测 0.35×~0.79×),一句 20 个字要造十几秒
     * (日志里排队等待到过 18177ms)。那十几秒里**一点声音都没有**,
     * 只看「嘴在不在动」就以为她说完了 —— 于是开麦,等她下一句开口。
     */
    @Test
    fun `她嗓子空了没有_正在合成下一句时_嘴是闭着的但还没完`() {
        assertFalse("没有声音,可队伍里压着话 —— 就是这一格害的", EarSessionMath.herVoiceFinished(false, 1))
        assertFalse(EarSessionMath.herVoiceFinished(false, 3))
    }

    /**
     * ★ 反过来的漏法:两次出声之间那一下 `speaking` 会短暂变 false(换句的时候)。
     *   那一瞬间也够麦克风抓半句话 —— 所以**两个条件缺一不可**。
     */
    @Test
    fun `她嗓子空了没有_正在出声但队空了也算没完`() {
        assertFalse("正在念最后一句,还没念完", EarSessionMath.herVoiceFinished(true, 0))
        assertFalse(EarSessionMath.herVoiceFinished(true, 2))
    }

    /**
     * ★ 防御性一条:计时器/计数器在**任何**异常路径下被压成负数时,
     *   闸门**不许**因此打开 —— 负数在这里只可能是账算错了,不是「欠了负一句」。
     */
    @Test
    fun `她嗓子空了没有_计数是负数也不许开麦`() {
        assertTrue("0 是空,该开", EarSessionMath.herVoiceFinished(false, 0))
        assertTrue("负数按「没有欠话」算,但前提是嘴也闭着", EarSessionMath.herVoiceFinished(false, -1))
        assertFalse("嘴还动着,负数也不开", EarSessionMath.herVoiceFinished(true, -1))
    }
}
