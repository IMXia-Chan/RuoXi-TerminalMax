package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三层风险闸的**第三层(手指)**在手机侧那半 —— 拿到电脑回执之后怎么办。
 *
 * ## 为什么这半也值得一个测试文件
 *
 * 第三层的**判定**在电脑那边(焦点在谁身上、要点的控件叫什么),那半由
 * `pc-server/test_risk_gate.py` 钉着。手机这边只拿得到**一句回执**,要判的只有
 * 「这句话是什么意思、我该做什么」—— 听起来没什么可错的,但它的失效方式
 * **两种都静默**:
 *
 * | 写错的地方 | 表现 |
 * |---|---|
 * | 两边的字面量拼得不一样 | 这道闸**再也不响了**,不报错,只表现为「她开始什么都直接点」 |
 * | 判定写松了(变成可以问两轮) | 同一件事连问两次 → 他闭着眼点 → **确认框等于没有** |
 *
 * 第二条不是体验问题,是安全问题。所以这两条各有一个测试。
 */
class RiskGateTapTest {

    // =====================================================================
    // 一、★ 跨端契约:这个字符串必须两边一模一样
    // =====================================================================

    /**
     * ★★ 电脑那边回的就是这个字面量(`pc-server/ai_tools.py` 的
     * `return {"ok": False, "blocked_by": "needs_confirm", "error": why}`)。
     *
     * 这条断言看着像废话,**它不是**:把两边任何一个字改掉(或者这边改用
     * `riskNeedsConfirm` 这种别名),闸就**永远不触发**,而唯一的症状是
     * 「某天开始她不再问了」—— 没有报错、没有日志、没有崩溃。
     * 和工具表漂移是同一类病,所以这儿也钉一根桩。
     */
    @Test
    fun `第三层的暗号必须和电脑那边一个字不差`() {
        assertEquals("needs_confirm", RiskMath.NEEDS_CONFIRM)
        assertTrue(
            "两边拼字对不上 = 这道闸静默失效:${RiskMath.NEEDS_CONFIRM}",
            RiskMath.NEEDS_CONFIRM == "needs_confirm"
        )
    }

    /**
     * ★ 别的失败原因**不许**被当成「要你点头」。
     *
     * 这批 code 是电脑那边早就在用的。它们说的是完全另一回事 —— 焦点跑了、
     * 字没打进去、搜索没提交。把它们也判成「等用户确认」的话,他会对着一个
     * **技术故障**白点一次头,而真正的原因被他自己的那一下盖过去,
     * 复盘时再也看不出来。
     */
    @Test
    fun `别的失败原因一律直接放行_不许冒充要点头`() {
        val others = listOf(
            "lost_focus", "search_not_typed", "search_not_submitted", "type_failed",
            // ↓ 这几个是**故意试的坏输入**:含糊的说法最容易被人「顺手包含一下」,
            //   而 contains 写法会让它们全部命中 —— 那正是这条测试要拦的。
            "needs_confirm_but_ok", "not_needs_confirm", "NEEDS_CONFIRM", " needs_confirm",
        )
        for (b in others) {
            assertEquals("「$b」被误判成要点头了", RiskMath.Tap.PASS,
                RiskMath.tapNext(b, confirmedSent = false))
        }
    }

    /** 空串(回执里根本没有 `blocked_by`) → 原样放行。 */
    @Test
    fun `没有这个字段就直接放行`() {
        assertEquals(RiskMath.Tap.PASS, RiskMath.tapNext("", confirmedSent = false))
    }

    // =====================================================================
    // 二、★★ 「最多问一次」——这一条是安全项,不是体验项
    // =====================================================================

    /** 电脑说要做不了,而还没问过他 → 弹框,点头后带凭证重发。 */
    @Test
    fun `第一次要点头_点头后带凭证重发一次`() {
        assertEquals(RiskMath.Tap.ASK_THEN_RETRY,
            RiskMath.tapNext(RiskMath.NEEDS_CONFIRM, confirmedSent = false))
    }

    /**
     * ★★ 问过、他也点了头,电脑**还是**说做不了 → 不再问,把原话交回去。
     *
     * 会走到这儿只有一种可能:那一头压根没认 `confirmed`(接线漏了,或者手机上
     * 装的是旧电脑端)。这时候**绝不能弹第二个框** —— 他对同一件事连点两次之后,
     * 第三次就会**不看内容直接点**。一个被闭着眼点的确认框,和没有这个框一样,
     * 但会让人以为「有这道闸」。
     */
    @Test
    fun `问过之后绝不再问`() {
        assertEquals(RiskMath.Tap.GIVE_UP,
            RiskMath.tapNext(RiskMath.NEEDS_CONFIRM, confirmedSent = true))
    }

    /**
     * ★★ 上一条的**全量版**:不管电脑回什么,只要我们已经带着凭证发过一轮,
     * 就绝不会再要一次点头。
     *
     * 单独写一条是因为「问不完」这种错**不是靠某一个用例发现的** —— 它要么在
     * 某个没试到的输入上冒出来,要么永远不冒出来。这里把可能出现的输入全枚举一遍
     * (包括以后新加的 code),把「[RiskMath.Tap.ASK_THEN_RETRY] 只能在
     * `confirmedSent = false` 时出现」变成一条**结构性的**保证。
     */
    @Test
    fun `任何回执_只要问过了就不许再要一次点头`() {
        val codes = listOf(
            "", RiskMath.NEEDS_CONFIRM,
            "lost_focus", "search_not_typed", "search_not_submitted", "type_failed",
            "user_took_over", "needs_confirm ", "NEEDS_CONFIRM",
        )
        for (b in codes) {
            assertNotEquals("「$b」在问过之后又被要求问一次", RiskMath.Tap.ASK_THEN_RETRY,
                RiskMath.tapNext(b, confirmedSent = true))
        }
    }

    // =====================================================================
    // 三、框上那行标题
    // =====================================================================

    /**
     * ★ 打字被第二层拦下时,框上不能只写一个「确认」。
     *
     * `type` **不在**第一层的名单里(它本来就每次都问,见 [RiskMath.needsTap]),
     * 所以这一条以前永远不会落到 [RiskMath.tapTitle] 上。但**第二层拦得到它**:
     * 焦点在银行那类应用上时,往里面打任何字都会被拦。那时标题要是通用的
     * 「确认」,他看到的就是一句没头没脑的话,不知道该不该点。
     *
     * ★ 而且措辞必须**和打字那道闸一模一样**(`AiAgent.dispatch` 里那个
     * 「确认输入文字」)—— 两处不一样的话,同一件事他会以为是两个不同的框。
     */
    @Test
    fun `打字那条路被第二层拦下时_标题要说清是输入`() {
        val t = RiskMath.tapTitle("type")
        assertNotEquals("还在用那个没头没脑的通用标题", "确认", t)
        assertEquals("和打字那道闸的措辞必须一致", "确认输入文字", t)
    }

    /** 第一层会问的那几个,标题一个都不许空、也不许退化成通用的「确认」。 */
    @Test
    fun `碰鼠标键盘的那几个都有具体标题`() {
        for (tool in listOf("click_at", "click_ui", "hotkey", "search")) {
            val t = RiskMath.tapTitle(tool)
            assertTrue("「$tool」的标题是空的", t.isNotBlank())
            assertNotEquals("「$tool」退化成通用标题了", "确认", t)
        }
    }
}
