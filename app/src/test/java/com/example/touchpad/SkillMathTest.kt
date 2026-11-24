package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 技能的纯逻辑测试 —— 纯 JVM,不碰 org.json / Android。
 *
 * 为什么值得钉:这一层错了**不崩**。它错的表现是「技能存进去发现是空的」
 * 「重放跑到一半套娃」「同名技能悄悄被另一条盖掉」—— 全是静默的,真机上只能靠结果反推。
 */
class SkillMathTest {

    private fun st(tool: String) = SkillStep(tool, "{}")

    private fun skill(
        name: String,
        runs: Int = 0,
        created: Long = 0L,
        steps: List<SkillStep> = listOf(st("hotkey")),
    ) = Skill(
        id = SkillMath.idOf(name),
        name = name,
        desc = "",
        steps = steps,
        sensitive = false,
        createdAt = created,
        runs = runs,
    )

    @Test
    fun `看菜单的那几步不进步骤表`() {
        val out = SkillMath.clean(listOf(st("list_hands"), st("open_app"), st("hotkey")))
        assertEquals(listOf("open_app", "hotkey"), out.map { it.tool })
    }

    @Test
    fun `技能自己的出入口也不进步骤表 —— 否则重放会套娃`() {
        val out = SkillMath.clean(
            listOf(st("skill_run"), st("skill_save"), st("skill_list"), st("open_app"))
        )
        assertEquals(listOf("open_app"), out.map { it.tool })
    }

    @Test
    fun `步骤超过上限就截断,不静默全存`() {
        val many = (1..SkillMath.MAX_STEPS + 5).map { st("t$it") }
        assertEquals(SkillMath.MAX_STEPS, SkillMath.clean(many).size)
    }

    @Test
    fun `名字空着和太长的都要拦,并给出能照着改的话`() {
        assertNull(SkillMath.nameProblem("开晚安模式"))
        assertTrue(SkillMath.nameProblem("").isNullOrEmpty().not())
        assertTrue(SkillMath.nameProblem("啊".repeat(SkillMath.NAME_MAX + 1)).isNullOrEmpty().not())
    }

    @Test
    fun `id 认得中文,标点变下划线`() {
        assertEquals("开晚安模式", SkillMath.idOf("开晚安模式"))
        assertEquals("开晚安_模式", SkillMath.idOf(" 开晚安,模式 "))
        assertEquals("skill", SkillMath.idOf("！！"))
    }

    @Test
    fun `按 id 找、按名字找、按名字算出来的 id 找,三条都要通`() {
        val s = skill("开晚安模式")
        val all = listOf(s)
        assertEquals(s, SkillMath.pick(all, "开晚安模式"))
        assertEquals(s, SkillMath.pick(all, s.id))
        assertEquals(s, SkillMath.pick(all, " 开晚安模式 "))
    }

    @Test
    fun `找不到返回空,不许拿第一条顶替`() {
        val all = listOf(skill("开晚安模式"), skill("下班"))
        assertNull(SkillMath.pick(all, "上班"))
        assertNull(SkillMath.pick(all, ""))
    }

    @Test
    fun `常用的排前面,一样多按新存的在前`() {
        val a = skill("a", runs = 1, created = 100)
        val b = skill("b", runs = 3, created = 50)
        val c = skill("c", runs = 1, created = 200)
        assertEquals(listOf("b", "c", "a"), SkillMath.ordered(listOf(a, b, c)).map { it.name })
    }

    @Test
    fun `动作型必须有步骤,思考型必须有方法 —— 两头都拦住空技能`() {
        assertTrue(SkillMath.shapeProblem(SkillKind.ACTION, emptyList(), "有方法") != null)
        assertNull(SkillMath.shapeProblem(SkillKind.ACTION, listOf(st("hotkey")), ""))
        assertTrue(SkillMath.shapeProblem(SkillKind.REASONING, emptyList(), "   ") != null)
        assertTrue(
            SkillMath.shapeProblem(
                SkillKind.REASONING, emptyList(), "啊".repeat(SkillMath.INSTR_MAX + 1)
            ) != null
        )
        assertNull(
            SkillMath.shapeProblem(SkillKind.REASONING, emptyList(), "先看财报,再看估值和同行")
        )
    }

    @Test
    fun `研究型缺判据就不许存 —— 没判据的不是研究`() {
        assertTrue(SkillMath.researchProblem("", "反例没找到", "先查文献").isNullOrEmpty().not())
        assertTrue(SkillMath.researchProblem("证一个不等式", "", "先查文献").isNullOrEmpty().not())
        assertTrue(SkillMath.researchProblem("证一个不等式", "反例没找到", "").isNullOrEmpty().not())
        assertTrue(
            SkillMath.researchProblem("证一个不等式", "反例没找到", "啊".repeat(SkillMath.INSTR_MAX + 1))
                .isNullOrEmpty().not()
        )
        assertNull(SkillMath.researchProblem("证一个不等式", "反例没找到", "先查文献,再写证明,再数值验"))
    }

    @Test
    fun `研究型的剩余轮数到顶就是零,不许出现负数`() {
        val s = Skill(
            id = "r", name = "r", desc = "", kind = SkillKind.RESEARCH,
            objective = "证", acceptance = "反例没找到", instructions = "查",
            budget = 5, rounds = 2, sensitive = false, createdAt = 0,
        )
        assertEquals(3, SkillMath.left(s))
        assertEquals(0, SkillMath.left(s.copy(rounds = 5)))
        assertEquals(0, SkillMath.left(s.copy(rounds = 9)))
    }

    @Test
    fun `失败的和被停下的步骤都不收 —— 重放时它们一定再坏一次`() {
        assertFalse(SkillMath.keepStep("open_app", failed = true, stopped = false))
        assertFalse(SkillMath.keepStep("open_app", failed = false, stopped = true))
        assertTrue(SkillMath.keepStep("open_app", failed = false, stopped = false))
        assertFalse(SkillMath.keepStep("", failed = false, stopped = false))
        assertFalse(SkillMath.keepStep("list_hands", failed = false, stopped = false))
    }

    @Test
    fun `isMeta 的口径和 clean 一致 —— 两处口径分叉过就是静默少一步或多一步`() {
        for (t in listOf("list_hands", "skill_list", "skill_save", "skill_run", "skill_delete")) {
            assertTrue(t, SkillMath.isMeta(t))
        }
        assertFalse(SkillMath.isMeta("open_app"))
    }

    // ── 触发词 ──
    //
    // ★ 判据错了不报错：她会在他聊别的事的时候**很自信地做错的事**。

    private fun trig(name: String, vararg t: String, runs: Int = 0) =
        skill(name, runs = runs).copy(triggers = t.toList())

    @Test
    fun `说出触发词就想起这条`() {
        val all = listOf(trig("晚安模式", "晚安", "睡了"))
        assertEquals(listOf("晚安模式"), SkillMath.hit("我睡了，晚安", all).map { it.name })
        assertEquals(listOf("晚安模式"), SkillMath.hit("晚安", all).map { it.name })
    }

    @Test
    fun `没说到就一条都不提`() {
        val all = listOf(trig("晚安模式", "晚安", "睡了"))
        assertTrue(SkillMath.hit("今天天气怎么样", all).isEmpty())
        // 没给触发词的技能**永远不会自己冒出来**（老档案就是这种）
        assertTrue(SkillMath.hit("晚安", listOf(skill("老技能"))).isEmpty())
    }

    @Test
    fun `一个字的触发词不算 —— 迟早撞上无关的话`() {
        val all = listOf(trig("开灯", "灯"))
        assertTrue(SkillMath.hit("这个灯真好看", all).isEmpty())
    }

    @Test
    fun `大小写不敏感`() {
        val all = listOf(trig("日报", "Daily"))
        assertEquals(1, SkillMath.hit("写个 daily 报告", all).size)
    }

    @Test
    fun `一次最多提三条`() {
        val all = (1..6).map { trig("技能$it", "测试") }
        assertEquals(SkillMath.MAX_HITS, SkillMath.hit("测试一下", all).size)
    }

    @Test
    fun `没命中就一个字都不发`() {
        val all = listOf(trig("晚安模式", "晚安"))
        assertNull(SkillMath.hint("随便聊聊", all))
    }

    // ── 槽位 `{{名字}}`:跑之前必须填,缺一个就一步都不动 ──

    private fun stepArgs(args: String) = SkillStep("type", args)

    @Test
    fun `槽位认得出来、去重、按出现顺序`() {
        val steps = listOf(
            stepArgs("""{"text":"{{对象}} 你好 {{对象}}"}"""),
            stepArgs("""{"q":"{{关键词}}"}"""),
        )
        assertEquals(listOf("对象", "关键词"), SkillMath.slotsOf(steps))
    }

    @Test
    fun `两边带空格的槽位也认 —— 模型常写成 {{ 对象 }}`() {
        assertEquals(listOf("对象"), SkillMath.slotsOf(listOf(stepArgs("""{"text":"{{ 对象 }}"}"""))))
    }

    @Test
    fun `空串不算给了 —— 和没问是同一件事,不能动`() {
        val steps = listOf(stepArgs("""{"text":"{{对象}}"}"""))
        assertEquals(listOf("对象"), SkillMath.missing(steps, mapOf("对象" to "  ")))
        assertTrue(SkillMath.missing(steps, mapOf("对象" to "张三")).isEmpty())
    }

    @Test
    fun `没给的槽位原样留着 —— 不许被静默换成空`() {
        val steps = listOf(stepArgs("""{"text":"{{对象}} / {{内容}}"}"""))
        assertEquals(
            """{"text":"张三 / {{内容}}"}""",
            SkillMath.fill(steps, mapOf("对象" to "张三"))[0].args,
        )
    }

    @Test
    fun `值里的引号换行反斜杠都要转义 —— 不转义就把 args 拼成坏 JSON`() {
        val steps = listOf(stepArgs("""{"text":"给 {{对象}} 发：{{内容}}"}"""))
        val filled = SkillMath.fill(
            steps,
            mapOf("对象" to """张"三""", "内容" to "第一行\n第二行\\结尾"),
        )
        assertEquals("""{"text":"给 张\"三 发：第一行\n第二行\\结尾"}""", filled[0].args)
    }

    @Test
    fun `槽位再多也有上限 —— 超过就是该拆技能了`() {
        val body = (1..20).joinToString(" ") { "{{v$it}}" }
        assertEquals(SkillMath.MAX_SLOTS, SkillMath.slotsOf(listOf(stepArgs("""{"t":"$body"}"""))).size)
    }

    @Test
    fun `命中的那一段带 id 和「不像就别用」`() {
        val all = listOf(trig("晚安模式", "晚安"))
        val h = SkillMath.hint("晚安", all)!!
        assertTrue(h.contains("id=晚安模式"))
        // ★ 少了这一句，她会把每句闲聊都当成派活
        assertTrue(h.contains("不像"))
    }
}
