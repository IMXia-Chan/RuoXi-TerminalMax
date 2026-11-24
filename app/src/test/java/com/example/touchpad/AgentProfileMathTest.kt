package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentProfileMath] 的纯函数。
 *
 * ★ 这一层错了**不崩**：记忆多留两条、名字变了个 id、预算夹错一格，屏上一切正常，
 *   只有「它怎么又从头开始」「怎么换了个人」这种事后才反应得过来的症状。
 *   所以这里每条都拿**具体字符串**断言，不拿「不等于空」糊过去。
 */
class AgentProfileMathTest {

    private fun p(
        name: String = "小研",
        charter: String = "查资料、给出处。",
        notes: List<AgentNote> = emptyList(),
        runs: Int = 0,
        budget: Int = AgentProfileMath.DEFAULT_BUDGET,
        createdAt: Long = 0,
    ) = AgentProfile(id = AgentProfileMath.idOf(name), name = name, charter = charter,
        notes = notes, runs = runs, budget = budget, createdAt = createdAt)

    // ------------------------------------------------------------------
    // 名字 / 职责
    // ------------------------------------------------------------------

    @Test
    fun `名字空着不行`() {
        assertNotNull(AgentProfileMath.nameProblem(""))
        assertNotNull(AgentProfileMath.nameProblem("   "))
    }

    @Test
    fun `名字超长不行`() {
        assertNull(AgentProfileMath.nameProblem("小研"))
        assertNull(AgentProfileMath.nameProblem("x".repeat(AgentProfileMath.NAME_MAX)))
        assertNotNull(AgentProfileMath.nameProblem("x".repeat(AgentProfileMath.NAME_MAX + 1)))
    }

    @Test
    fun `职责空着不行`() {
        assertNotNull(AgentProfileMath.charterProblem(""))
        assertNull(AgentProfileMath.charterProblem("查资料"))
    }

    @Test
    fun `id 保留中文、标点变下划线`() {
        assertEquals("小研", AgentProfileMath.idOf("小研"))
        assertEquals("editor_2", AgentProfileMath.idOf("Editor 2"))
        assertEquals("a_b_c", AgentProfileMath.idOf(" a-b-c "))
        // 全是标点 → 兜底成 agent，不能返回空串（空 id 会让档案读写都指向无名文件）
        assertEquals("agent", AgentProfileMath.idOf("!!!  "))
    }

    // ------------------------------------------------------------------
    // 预算
    // ------------------------------------------------------------------

    @Test
    fun `预算夹在合理范围`() {
        assertEquals(AgentProfileMath.DEFAULT_BUDGET, AgentProfileMath.fitBudget(0))
        assertEquals(AgentProfileMath.DEFAULT_BUDGET, AgentProfileMath.fitBudget(-3))
        assertEquals(12, AgentProfileMath.fitBudget(12))
        assertEquals(AgentProfileMath.MAX_BUDGET, AgentProfileMath.fitBudget(999))
    }

    // ------------------------------------------------------------------
    // 记忆
    // ------------------------------------------------------------------

    @Test
    fun `记忆新的在前、同一条不重复记`() {
        var notes = AgentProfileMath.addNote(emptyList(), "第一件事", 1L)
        notes = AgentProfileMath.addNote(notes, "第二件事", 2L)
        notes = AgentProfileMath.addNote(notes, "第一件事", 3L)
        assertEquals(2, notes.size)
        assertEquals("第一件事", notes[0].text)
        assertEquals("第二件事", notes[1].text)
        assertEquals(3L, notes[0].at)
    }

    @Test
    fun `空白的一条不记`() {
        val notes = AgentProfileMath.addNote(emptyList(), "   ", 1L)
        assertTrue(notes.isEmpty())
    }

    @Test
    fun `一条记忆按上限截断`() {
        val long = "字".repeat(AgentProfileMath.NOTE_MAX + 50)
        val notes = AgentProfileMath.addNote(emptyList(), long, 1L)
        assertEquals(AgentProfileMath.NOTE_MAX, notes[0].text.length)
    }

    @Test
    fun `超过上限丢最老的`() {
        var notes = emptyList<AgentNote>()
        for (i in 1..(AgentProfileMath.MAX_NOTES + 5)) {
            notes = AgentProfileMath.addNote(notes, "第 $i 件", i.toLong())
        }
        assertEquals(AgentProfileMath.MAX_NOTES, notes.size)
        // 最新的在最前，最老的那几条被丢掉了
        assertEquals("第 ${AgentProfileMath.MAX_NOTES + 5} 件", notes[0].text)
        assertFalse(notes.any { it.text == "第 1 件" })
    }

    @Test
    fun `空记忆一个字都不发`() {
        assertEquals("", AgentProfileMath.memoryBlock(p()))
    }

    @Test
    fun `有记忆时按条数上限带`() {
        var notes = emptyList<AgentNote>()
        for (i in 1..5) notes = AgentProfileMath.addNote(notes, "第 $i 件", i.toLong())
        val block = AgentProfileMath.memoryBlock(p(notes = notes), limit = 2)
        assertTrue(block.contains("第 5 件"))
        assertTrue(block.contains("第 4 件"))
        assertFalse(block.contains("第 3 件"))
    }

    // ------------------------------------------------------------------
    // 系统提示词
    // ------------------------------------------------------------------

    @Test
    fun `提示词带上名字、职责和步数`() {
        val s = AgentProfileMath.systemPrompt(p(name = "小研", charter = "只查公开资料。", budget = 9))
        assertTrue(s.contains("小研"))
        assertTrue(s.contains("只查公开资料。"))
        // 预算是**这一份**的，不是写死的 16
        assertTrue(s.contains("9 步"))
        assertFalse(s.contains("{budget}"))
        // 没有记忆时不该出现记忆那一段
        assertFalse(s.contains("你记得的事"))
    }

    @Test
    fun `提示词把记忆排在规矩前面`() {
        val notes = AgentProfileMath.addNote(emptyList(), "上一回用的是 python3", 1L)
        val s = AgentProfileMath.systemPrompt(p(notes = notes))
        val memAt = s.indexOf("你记得的事")
        val ruleAt = s.indexOf("【工作方式】")
        assertTrue(memAt >= 0)
        assertTrue(ruleAt > memAt)
        assertTrue(s.contains("上一回用的是 python3"))
    }

    // ------------------------------------------------------------------
    // 排序与挑选
    // ------------------------------------------------------------------

    @Test
    fun `做得多的排前面、一样多按新建的在前`() {
        val a = p(name = "A", runs = 0, createdAt = 100)
        val b = p(name = "B", runs = 3, createdAt = 1)
        val c = p(name = "C", runs = 0, createdAt = 200)
        assertEquals(listOf("B", "C", "A"), AgentProfileMath.ordered(listOf(a, b, c)).map { it.name })
    }

    @Test
    fun `按 id 或名字都能找到`() {
        val a = p(name = "小研")
        val b = p(name = "Editor 2")
        val all = listOf(a, b)
        assertEquals(a, AgentProfileMath.pick(all, "小研"))
        assertEquals(a, AgentProfileMath.pick(all, a.id))
        assertEquals(b, AgentProfileMath.pick(all, "editor_2"))
        assertNull(AgentProfileMath.pick(all, "  "))
        assertNull(AgentProfileMath.pick(all, "查无此人"))
    }

    // ------------------------------------------------------------------
    // 拆出来的岗位名 → 在册的人
    // ------------------------------------------------------------------

    @Test
    fun `名字一模一样就对得上`() {
        assertEquals("小研", AgentProfileMath.matchName("小研", listOf("小研", "审稿")))
    }

    @Test
    fun `全角半角和空白不算差别`() {
        val names = listOf("Editor AI")
        assertEquals("Editor AI", AgentProfileMath.matchName("ｅｄｉｔｏｒ　ａｉ", names))
        assertEquals("Editor AI", AgentProfileMath.matchName(" Editor AI ", names))
    }

    @Test
    fun `少一个字也认，但只在对得上的只有一个时`() {
        assertEquals("审稿", AgentProfileMath.matchName("审稿人", listOf("审稿", "查证")))
    }

    @Test
    fun `两个都像就不认 —— 宁可当临时助手也不派错人`() {
        assertNull(AgentProfileMath.matchName("审稿", listOf("审稿甲", "审稿乙")))
    }

    @Test
    fun `一个字的不做包含匹配`() {
        assertNull(AgentProfileMath.matchName("写", listOf("写手小王")))
    }

    @Test
    fun `完全不沾边就是临时助手`() {
        assertNull(AgentProfileMath.matchName("算账", listOf("审稿", "查证")))
        assertNull(AgentProfileMath.matchName("", listOf("审稿")))
    }
}
