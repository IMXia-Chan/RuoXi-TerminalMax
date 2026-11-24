package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扇出对账的纯逻辑。
 *
 * 为什么值得钉：**对账错了不会崩**。它错的表现是「三个角色说的其实是一件事，
 * 界面却说三件事都不同」或者反过来——把一家之言标成三家共识。后者更贵：
 * 一个角色编的数字，会以「大家都同意」的身份进中枢的上下文。
 */
class FanoutMathTest {

    // ═════════════════════════════════ 一、拆单
    @Test
    fun `正常的两条`() {
        val p = FanoutMath.parse(listOf("算|粗算这三步能省多少", "挑刺|这段话哪里站不住"))
        assertEquals(2, p.specs.size)
        assertEquals("算", p.specs[0].role)
        assertEquals("粗算这三步能省多少", p.specs[0].task)
        assertEquals("挑刺", p.specs[1].role)
        assertTrue(p.problems.isEmpty())
    }

    @Test
    fun `全角竖线也认 —— 小模型常打这个`() {
        val p = FanoutMath.parse(listOf("查｜找出三条依据"))
        assertEquals(1, p.specs.size)
        assertEquals("查", p.specs[0].role)
        assertEquals("找出三条依据", p.specs[0].task)
    }

    @Test
    fun `少了竖线不判废 —— 整句当任务，角色记助手`() {
        val p = FanoutMath.parse(listOf("把这段话改得更短"))
        assertEquals("助手", p.specs[0].role)
        assertEquals("把这段话改得更短", p.specs[0].task)
    }

    @Test
    fun `重名只留第一条，并且说出来`() {
        val p = FanoutMath.parse(listOf("算|甲", "算|乙"))
        assertEquals(1, p.specs.size)
        assertEquals("甲", p.specs[0].task)
        assertTrue(p.problems.any { it.contains("重名") })
    }

    @Test
    fun `超过上限就砍，并说出来`() {
        val p = FanoutMath.parse((1..(FanoutMath.MAX_ROLES + 2)).map { "角色$it|任务$it" })
        assertEquals(FanoutMath.MAX_ROLES, p.specs.size)
        assertTrue(p.problems.any { it.contains("超过") })
    }

    @Test
    fun `斜杠也认：名字斜杠任务`() {
        val p = FanoutMath.parse(listOf("小研/查价钱", "agent2/查口碑"))
        assertEquals(listOf("小研", "agent2"), p.specs.map { it.role })
        assertEquals("查价钱", p.specs[0].task)
        assertEquals("查口碑", p.specs[1].task)
    }

    @Test
    fun `斜杠写法的第三列也认`() {
        val p = FanoutMath.parse(listOf("看图员/看这张照片/看图"))
        assertEquals("看这张照片", p.specs[0].task)
        assertEquals(RouteMath.VISION, p.specs[0].need)
    }

    @Test
    fun `开头那截带冒号就不切 —— 网址不是名字`() {
        val p = FanoutMath.parse(listOf("查 http://a.com/x 上的价"))
        assertEquals("助手", p.specs[0].role)
        assertEquals("查 http://a.com/x 上的价", p.specs[0].task)
    }

    @Test
    fun `分号断句，正文里的分号不断`() {
        assertEquals(
            listOf("小研/查A", "agent2/查B"),
            FanoutMath.splitItems("小研/查A；agent2/查B"),
        )
        assertEquals(listOf("查价格；顺便问口碑"), FanoutMath.splitItems("查价格；顺便问口碑"))
        assertEquals(listOf("甲", "乙"), FanoutMath.splitItems("甲\n乙"))
        assertEquals(emptyList<String>(), FanoutMath.splitItems("  \n ；"))
    }

    @Test
    fun `一条都没写清就明说凑不成扇出`() {
        val p = FanoutMath.parse(listOf("算|", "   "))
        assertTrue(p.specs.isEmpty())
        assertTrue(p.problems.any { it.contains("至少") })
    }

    // ═════════════════════════════════ 二、像不像
    @Test
    fun `换个说法的同一件事要认得出来`() {
        val s = FanoutMath.similarity(
            "这样改完大概能省三成时间",
            "改完之后时间大约省三成",
        )
        assertTrue("实际 $s", s >= FanoutMath.SAME)
    }

    @Test
    fun `不相干的两句话不能算像`() {
        val s = FanoutMath.similarity(
            "这样改完大概能省三成时间",
            "先确认服务器是不是还活着",
        )
        assertTrue("实际 $s", s < FanoutMath.SAME)
    }

    // ═════════════════════════════════ 三、对账
    @Test
    fun `两家都说的进相同，只有一家说的进分歧`() {
        val r = FanoutMath.reconcile(
            listOf(
                FanoutMath.Answer("甲", "先备份再改表结构。这条命令会锁表二十分钟。"),
                FanoutMath.Answer("乙", "先备份再动表结构。回滚要重放日志，很慢。"),
            )
        )
        assertEquals(2, r.answered)
        assertTrue("相同点没认出来：${r.agree}", r.agree.any { it.text.contains("备份") })
        assertTrue("分歧没认出来：${r.differ}", r.differ.any { it.text.contains("锁表") })
        assertTrue(r.differ.any { it.text.contains("回滚") })
    }

    @Test
    fun `同一条共识不会在每个角色名下重复列`() {
        val r = FanoutMath.reconcile(
            listOf(
                FanoutMath.Answer("甲", "先备份再改表结构。"),
                FanoutMath.Answer("乙", "先备份再改表结构。"),
                FanoutMath.Answer("丙", "先备份再改表结构。"),
            )
        )
        assertEquals(1, r.agree.size)
        assertEquals(3, r.agree[0].roles.size)
        assertTrue(r.differ.isEmpty())
    }

    @Test
    fun `只有一份回来时不假装对账过`() {
        val r = FanoutMath.reconcile(
            listOf(
                FanoutMath.Answer("甲", "先备份再改表结构。"),
                FanoutMath.Answer("乙", ""),
            )
        )
        assertEquals(1, r.answered)
        assertEquals(1, r.blank)
        assertTrue(r.agree.isEmpty())
        assertFalse(r.differ.isEmpty())
        assertTrue(FanoutMath.render(r).contains("没得对账"))
    }

    @Test
    fun `空的和短的垃圾句不算观点`() {
        val r = FanoutMath.reconcile(
            listOf(
                FanoutMath.Answer("甲", "好。可以。先备份再改表结构。"),
                FanoutMath.Answer("乙", "行。先备份再改表结构。"),
            )
        )
        // 「好」「可以」「行」都不足六个字，不该出现在分歧里。
        assertTrue(r.differ.none { it.text.length < 6 })
    }

    // ═════════════════════════════════ 四、版面
    @Test
    fun `回执里相同在前、分歧在后`() {
        val r = FanoutMath.reconcile(
            listOf(
                FanoutMath.Answer("甲", "先备份再改表结构。锁表二十分钟。"),
                FanoutMath.Answer("乙", "先备份再改表结构。回滚要重放日志。"),
            )
        )
        val t = FanoutMath.render(r)
        assertTrue(t.startsWith("【对账】"))
        assertTrue(t.indexOf("相同") < t.indexOf("分歧"))
    }

    @Test
    fun `超预算也保留标题那一行`() {
        val many = (1..30).map { FanoutMath.Answer("角色$it", "这是第${it}条各不相同的说法，内容足够长以避免被当成短句过滤掉。") }
        val t = FanoutMath.render(FanoutMath.reconcile(many), budget = 300)
        assertTrue(t.startsWith("【对账】"))
        assertTrue(t.length <= 340)
    }

    // ═════════════════════════════════ 四、岗位名对到在册的人

    @Test
    fun `名字对得上的就换成那个人本来的名字`() {
        val specs = listOf(FanoutMath.Spec("审稿人", "看这段"), FanoutMath.Spec("查", "找出处"))
        val r = FanoutMath.remap(specs, listOf("审稿", "小研"))
        assertEquals(listOf("审稿", "查"), r.specs.map { it.role })
        assertTrue(r.problems.isEmpty())
    }

    @Test
    fun `册子是空的原样返回`() {
        val specs = listOf(FanoutMath.Spec("甲", "一"), FanoutMath.Spec("乙", "二"))
        assertEquals(specs, FanoutMath.remap(specs, emptyList()).specs)
    }

    @Test
    fun `两条都指向同一个人只留前一条，并说出来`() {
        val specs = listOf(FanoutMath.Spec("审稿", "看这段"), FanoutMath.Spec("审稿人", "再看一遍"))
        val r = FanoutMath.remap(specs, listOf("审稿"))
        assertEquals(1, r.specs.size)
        assertEquals("看这段", r.specs[0].task)
        assertTrue(r.problems.any { it.contains("都指向") })
    }
}
