package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人设切分/拼回的测试 —— 纯 JVM。
 *
 * ★ 为什么这件事必须两个方向都钉:
 * 这里错了**不会崩**,它会让「他改了人设」静默地变成「没改」,或者更糟 ——
 * 拼出半份提示词,她照常说话、只是**不再守规矩**。
 * 两种都长得像「她今天有点怪」,真机上查不出来。
 */
class PersonaMathTest {

    /** 照出厂那份的**结构**造的夹具:一段人设 + 一个空行 + 一条分界 + 工作手册。 */
    private val full = """
        你是 Test,住在他手机里的 AI 伙伴。他有事找你,没事也找你。

        规矩(每条都是踩过的坑):
        1. 先做 A。
        2. 再做 B。
    """.trimIndent()

    private val manual = "规矩(每条都是踩过的坑):\n1. 先做 A。\n2. 再做 B。"

    // ---- 切:给他看的只有自我介绍那几行 ----

    @Test
    fun `切出来的是人设,不含分界`() {
        val p = PersonaMath.splitOut(full)!!
        assertTrue(p.startsWith("你是 Test"))
        assertTrue(p.contains("没事也找你"))
        assertFalse(p.contains(PersonaMath.MARK))
    }

    @Test
    fun `工作手册一个字都不在切出来的那半里`() {
        val p = PersonaMath.splitOut(full)!!
        assertFalse(p.contains("先做 A"))
        assertFalse(p.contains("再做 B"))
    }

    // ---- 拼:他的那份换掉出厂那段,手册逐字不动 ----

    @Test
    fun `换了人设,工作手册逐字不变`() {
        val mine = "你是别的名字,话很少。"
        val out = PersonaMath.rebuild(full, mine)!!
        assertTrue(out.startsWith("你是别的名字,话很少。"))
        assertTrue(out.endsWith(manual))            // ★ 手册段连标点都没动
        assertFalse(out.contains("你是 Test"))
    }

    @Test
    fun `切出来再拼回去,和出厂一字不差`() {
        assertEquals(full, PersonaMath.rebuild(full, PersonaMath.splitOut(full)))
    }

    @Test
    fun `他的前后空白会被收拾掉`() {
        val out = PersonaMath.rebuild(full, "\n\n  你是别的名字。  \n")!!
        assertTrue(out.startsWith("你是别的名字。"))
        assertTrue(out.endsWith(manual))
    }

    // ---- ★★ 认不出来 / 不能用的时候:一律 null,由调用方退回出厂 ----

    @Test
    fun `分界不见了 —— 回 null,不猜`() {
        val broken = "你是 Test。\n\n1. 先做 A。"
        assertNull(PersonaMath.splitOut(broken))
        assertNull(PersonaMath.rebuild(broken, "你是别的名字。"))
    }

    @Test
    fun `分界出现两次 —— 一样回 null,不许挑一个切`() {
        // 说明结构变了(或有人手滑复制了一行);切哪儿都有歧义,猜不如不猜
        val twice = "你是 Test。\n" + PersonaMath.MARK + "\n1. A。\n" + PersonaMath.MARK + "\n2. B。"
        assertNull(PersonaMath.splitOut(twice))
        assertNull(PersonaMath.rebuild(twice, "你是别的名字。"))
    }

    @Test
    fun `空人设 —— 回 null,不肯把提示词的头砍掉`() {
        assertNull(PersonaMath.rebuild(full, ""))
        assertNull(PersonaMath.rebuild(full, "   \n\t "))
        assertNull(PersonaMath.rebuild(full, null))
    }

    @Test
    fun `超长的人设 —— 回 null`() {
        val tooLong = "字".repeat(PersonaMath.MAX_PERSONA_CHARS + 1)
        assertNull(PersonaMath.rebuild(full, tooLong))
        // 正好到上限要收下 —— 边界不能设成「小于」
        val atLimit = "字".repeat(PersonaMath.MAX_PERSONA_CHARS)
        assertTrue(PersonaMath.rebuild(full, atLimit)!!.startsWith(atLimit))
    }

    @Test
    fun `人设里又写了一遍分界 —— 回 null`() {
        // ★ 放过去的话拼出来会有两处分界,下次切就从人设里那处切开 —— 结构当场碎掉
        val bad = "你是 X。\n" + PersonaMath.MARK + "\n随便写点"
        assertNull(PersonaMath.rebuild(full, bad))
    }

    // ---- ★★ 界面那道闸:它和 rebuild 必须是同一个判据 ----

    @Test
    fun `problem 说行的那一段,rebuild 一定拼得出来`() {
        // ★ 这条钉的是「同一张表抄两遍」那个病。两份判据分家的症状是
        //   「他按了保存、提示也过去了、人设却没变」—— 真机上查不出来。
        val cases = listOf(
            "你是别的名字,话很少。",
            "字".repeat(PersonaMath.MAX_PERSONA_CHARS),
            "\n\n  前后有空白  \n",
            PersonaMath.splitOut(full)!!,
        )
        cases.forEach {
            assertNull("problem 拦了,可它其实拼得出来:${it.take(12)}", PersonaMath.problem(full, it))
            assertTrue(
                "problem 放行了、rebuild 却拼不出来:${it.take(12)}",
                PersonaMath.rebuild(full, it) != null,
            )
        }
    }

    @Test
    fun `problem 说不行的那一段,rebuild 一定也拼不出来`() {
        val cases = listOf(
            "字".repeat(PersonaMath.MAX_PERSONA_CHARS + 1),
            "你是 X。\n" + PersonaMath.MARK + "\n随便写点",
        )
        cases.forEach {
            assertTrue("problem 放行了、rebuild 却拼不出来", PersonaMath.problem(full, it) != null)
            assertNull("problem 拦了、rebuild 却能拼 —— 两份判据分家了", PersonaMath.rebuild(full, it))
        }
    }

    @Test
    fun `分界没了的时候,problem 也说不行`() {
        // ★ 这条挡的是「他按了保存、提示过了、人设没变」——
        //   拼不回去的时候必须当场拦住,而不是存下去等下一轮默默退回出厂。
        val broken = "你是 Test。\n\n1. 先做 A。"
        assertTrue(PersonaMath.problem(broken, "你是别的名字。") != null)
    }

    @Test
    fun `空串算合法 —— 那是「还原出厂」,不是错误`() {
        // ★ 唯一一处 rebuild 回 null 而 problem 放行的地方:还原出厂根本不用拼。
        //   写在这儿免得以后有人把它当 bug 修掉。
        assertNull(PersonaMath.problem(full, ""))
        assertNull(PersonaMath.problem(full, "   \n\t "))
        assertNull(PersonaMath.problem(full, null))
        // 而「拼」是另一回事 —— 它确实拼不出来,两件事不能混
        assertNull(PersonaMath.rebuild(full, ""))
    }

    @Test
    fun `每一条拒绝都是一句人话,而且各不相同`() {
        // ★ 只说「保存失败」等于没说 —— 他会照着同一个错再试一遍。
        val msgs = listOf(
            PersonaMath.problem(full, "字".repeat(PersonaMath.MAX_PERSONA_CHARS + 1)),
            PersonaMath.problem(full, "你是 X。\n" + PersonaMath.MARK + "\n随便写点"),
            PersonaMath.problem("你是 Test。\n\n1. 先做 A。", "你是别的名字。"),
        )
        msgs.forEach {
            assertTrue("这句太短了,不像一句人话:$it", (it ?: "").length >= 8)
            assertFalse("把他看不懂的东西端上去了:$it", it!!.contains("rebuild"))
        }
        assertEquals("三种不同的拒绝理由,报的却是同一句话", 3, msgs.toSet().size)
    }

    // ---- 出厂那段自己也得经得起切 ----

    @Test
    fun `出厂原文里人设段本身是干净的`() {
        val p = PersonaMath.splitOut(full)!!
        assertTrue(p.isNotBlank())
        assertFalse(p.contains(PersonaMath.MARK))
        assertTrue(PersonaMath.rebuild(full, p) == full)
    }
}
