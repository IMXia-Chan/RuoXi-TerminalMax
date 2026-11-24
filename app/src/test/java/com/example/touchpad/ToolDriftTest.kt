package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具表对账的判定规则 —— 纯 JVM。
 *
 * 为什么值得钉:**这个检查本身有「叫错」和「不叫」两种坏,而两种都很难发现。**
 *
 *   - **叫错**(把所有差异都报成故障):真机上每次连接都刷几行不会坏的告警 →
 *     人学会无视它 → 真出事那天也无视。比没有检查更坏。
 *   - **不叫**(漏判):能力静默少一块,症状是「她答不出那件事」,查不出原因。
 *
 * 所以这里两个方向都钉:该报 bad 的必须 bad,该只是 warn 的**不许**算 bad。
 */
class ToolDriftTest {

    private fun shape(vararg tools: Triple<String, Set<String>, Set<String>>): ToolDrift.Shape {
        val p = HashMap<String, Set<String>>()
        val r = HashMap<String, Set<String>>()
        for ((n, ps, rs) in tools) { p[n] = ps; r[n] = rs }
        return ToolDrift.Shape(p, r)
    }

    private val openApp = Triple("open_app", setOf("name"), setOf("name"))
    private val typeTool = Triple("type", setOf("text"), setOf("text"))

    @Test
    fun `一模一样就什么都不报`() {
        val r = ToolDrift.compare(
            shape(openApp, typeTool),
            shape(openApp, typeTool),
        )
        assertTrue(r.ok)
        assertTrue(r.warn.isEmpty())
    }

    @Test
    fun `手机独有的工具不算漂移`() {
        val r = ToolDrift.compare(
            shape(openApp, Triple("click_element", setOf("description"), setOf("description"))),
            shape(openApp),
        )
        assertTrue("click_element 是手机上的眼,电脑本来就没有: ${r.bad}", r.ok)
        assertTrue(r.warn.isEmpty())
    }

    @Test
    fun `★ 电脑会而手机没抄到 —— 提醒,但不再是「会坏」`() {
        // 通用入口进来之前这是 bad,因为模型**永远够不着**那个工具。
        // 现在 list_hands 是当场问电脑要的菜单,新工具自己会冒出来,
        // `use_hand` 照样调得到 —— 只是要多绕一轮。叫它「会坏」是撒谎,
        // 而撒谎的检查会被无视(那比没有检查更坏)。
        val r = ToolDrift.compare(shape(openApp), shape(openApp, typeTool))
        assertTrue("够得着的就差一轮,不该算故障: ${r.bad}", r.ok)
        assertTrue(r.warn.any { it.contains("type") })
        assertTrue(r.warn.any { it.contains("use_hand") })
    }

    @Test
    fun `★ 两个通用入口本身不是漂移`() {
        // 它们是「去哪找手」的机制,电脑那边本来就没有 —— 有才奇怪。
        // 不豁免的话,连上电脑那一刻就会尖叫两条,然后人就学会无视整个报告了。
        val r = ToolDrift.compare(
            shape(
                openApp, typeTool,
                Triple("list_hands", setOf("hand"), emptySet()),
                Triple("use_hand", setOf("hand", "tool", "args"), setOf("hand", "tool")),
            ),
            shape(openApp, typeTool),
        )
        assertTrue("通用入口不该被算成漂移: ${r.bad} ${r.warn}", r.ok)
        assertTrue(r.warn.isEmpty())
    }

    @Test
    fun `★ 手机教模型调一个电脑不认的工具,必须报`() {
        val r = ToolDrift.compare(shape(openApp, typeTool), shape(openApp))
        assertFalse(r.ok)
        assertTrue(r.bad.any { it.contains("type") })
    }

    @Test
    fun `★ 必填对不上必须报(电脑要的模型永远填不出)`() {
        val r = ToolDrift.compare(
            shape(Triple("open_app", setOf("name"), emptySet())),
            shape(Triple("open_app", setOf("name"), setOf("name"))),
        )
        assertFalse(r.ok)
        assertTrue(r.bad.any { it.contains("必填") })
    }

    @Test
    fun `★ 手机编了一个电脑不认的参数,必须报`() {
        val r = ToolDrift.compare(
            shape(Triple("open_app", setOf("name", "speed"), setOf("name"))),
            shape(Triple("open_app", setOf("name"), setOf("name"))),
        )
        assertFalse(r.ok)
        assertTrue(r.bad.any { it.contains("speed") })
    }

    @Test
    fun `★ 电脑多一个可选参数只是 warn,不是 bad`() {
        // 这正是 click_ui.window / list_ui.window / scroll.amount 那三条的形态:
        // 电脑支持、手机**故意**不告诉模型。报成故障就是天天误报。
        val r = ToolDrift.compare(
            shape(Triple("scroll", setOf("direction"), setOf("direction"))),
            shape(Triple("scroll", setOf("direction", "amount"), setOf("direction"))),
        )
        assertTrue("这只是少块能力,不该算故障: ${r.bad}", r.ok)
        assertTrue(r.warn.any { it.contains("amount") })
        assertTrue(r.warn.any { it.contains("PC_ONLY_PARAMS") })
    }

    @Test
    fun `必填参数不会同时既报 bad 又报 warn`() {
        // 必填漏在手机那份外面时,它**同时**长得像「电脑多一个可选参数」。
        // 报两遍会让人以为是两件事。减法里减掉 required 就是为了这个。
        val r = ToolDrift.compare(
            shape(Triple("open_app", setOf(), emptySet())),
            shape(Triple("open_app", setOf("name"), setOf("name"))),
        )
        assertFalse(r.ok)
        assertTrue(r.warn.none { it.contains("name") })
    }

    @Test
    fun `★ 必填参数没有样例值要报 —— 否则回执里那句示例是空的`() {
        val r = ToolDrift.compare(
            shape(Triple("weird_tool", setOf("count"), setOf("count"))),
            shape(Triple("weird_tool", setOf("count"), setOf("count"))),
        )
        assertTrue("样例缺失不该算故障: ${r.bad}", r.ok)
        assertTrue(r.warn.any { it.contains("count") })
        assertTrue(r.warn.any { it.contains("HandMath.SAMPLES") })
    }

    @Test
    fun `有样例的参数不会触发样例告警`() {
        val r = ToolDrift.compare(shape(openApp), shape(openApp))
        assertTrue(r.warn.none { it.contains("样例") })
    }

    @Test
    fun `两种报告各自成行,bad 带星号`() {
        // ★ 这个 bad 只能拿「手机教模型调电脑不认的工具」来造。
        // 之前这里用的是「电脑有、手机没抄到」—— 那条 2026-10-04 降成 warn 了,
        // 于是这个测试**仍然会通过**断言之外的部分、只在星号那一行炸掉。
        // 换句话说:它挂掉不是因为它错了,是因为它借用的例子被降级了。
        val bad = ToolDrift.lines(ToolDrift.compare(shape(openApp, typeTool), shape(openApp)))
        assertTrue("真 bad 得戴星号: $bad", bad.any { it.startsWith("★") && it.contains("type") })

        // 而 warn 那类**不许**带星号 —— 星号是「这条会坏」的记号,
        // 戴着它去说一件不会坏的事,就是在稀释它(稀释到最后人整个无视)。
        val warnOnly = ToolDrift.lines(
            ToolDrift.compare(
                shape(Triple("scroll", setOf("direction"), setOf("direction"))),
                shape(Triple("scroll", setOf("direction", "amount"), setOf("direction"))),
            )
        )
        assertTrue("这条应该是有话要说的,不然下面那句断言等于没测", warnOnly.isNotEmpty())
        assertTrue(warnOnly.none { it.startsWith("★") })
    }

    @Test
    fun `手机自有工具的清单和 Python 守卫那份对得上`() {
        // 这两份是**手工同步**的(Kotlin 读不了 py 文件)。数量对不上就是有人改了一边。
        // 具体名字改错查不出来,但「多加/少加了一条」能兜住。
        //
        // ★★ 2026-11-06:从 3 条扩到 12 条。以前只有 click_element / list_hands /
        //    use_hand 三条,而手机 dispatch 里其实还有 9 个电脑不认分支 ——
        //    于是每次连电脑对账都报一串「电脑根本不认这个工具」的假警报,
        //    读到第三次就没人看了,真警报也跟着一起被无视。
        //    下面的集合就是**两份清单共同的真相**,一边加了另一边没加,这里会挂。
        val expected = setOf(
            "click_element",   // 视觉找像素再点,眼在手机上
            "list_hands", "use_hand",  // 去哪找手的机制本身
            "read_url",        // 手机上自己抓网页
            "delegate",        // 分头派活的编排层
            "skill_list", "skill_save", "skill_new", "skill_delete",
            "skill_note", "skill_ledger", "skill_run",
        )
        assertEquals(expected, ToolDrift.PHONE_LOCAL)
    }
}
