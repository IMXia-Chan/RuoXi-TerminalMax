package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 假名那一档的纯逻辑测试。
 *
 * 这一层错了**不崩**,而且很难发现:
 * - 代号不稳定 → 云端把同一个号码当成两个人,结论全错,**看起来还很像对的**;
 * - 换不回来 → 他看到的答复里躺着一个 `[PHONE_3f2a91]`;
 * - 碰到不该碰的方括号 → 把正文里的 `[注]` 也换了。
 * 三个都在真机上只能靠「多看几眼」发现,所以钉在这儿。
 */
class AliasMathTest {

    private val phone = "13812345678"
    private val phone2 = "13900001111"
    private val card = "4111111111111111"
    private val mail = "zhangsan@example.com"

    /** 一张最小的假名表:值 → 词。 */
    private val table = mapOf(
        phone to AliasMath.baseAlias(SecretMath.KIND_PHONE, phone),
        phone2 to AliasMath.baseAlias(SecretMath.KIND_PHONE, phone2),
        card to AliasMath.baseAlias(SecretMath.KIND_CARD, card),
        mail to AliasMath.baseAlias(SecretMath.KIND_EMAIL, mail),
    )

    private fun sink(kind: String, value: String): String = table[value] ?: AliasMath.baseAlias(kind, value)
    private fun lookup(alias: String): String? = table.entries.firstOrNull { it.value == alias }?.key

    @Test
    fun `代号由值算出来，同一个值永远是同一个词`() {
        assertEquals(AliasMath.code(phone), AliasMath.code(phone))
        assertEquals(6, AliasMath.code(phone).length)
        assertTrue(AliasMath.code(phone).all { it in "0123456789abcdef" })
        assertEquals(
            AliasMath.baseAlias(SecretMath.KIND_PHONE, phone),
            AliasMath.baseAlias(SecretMath.KIND_PHONE, phone),
        )
        // 不同的值给不同的词(这条只是形状上的保证;真撞了由 store 错开一位)
        assertTrue(AliasMath.code(phone) != AliasMath.code(phone2))
    }

    @Test
    fun `出门那份里同一个号码出现两次是同一个代号 —— 这正是云端能推理的前提`() {
        val text = "他给的号码是 $phone，过一会儿又说了一遍 $phone。"
        val out = AgentMath.forCloud(text, ::sink)
        assertFalse(out, out.contains(phone))
        val token = AliasMath.wrap(table.getValue(phone))
        assertEquals(token, out.substringBefore("，").substringAfter("号码是 "))
        // 两次都换成同一个词
        assertEquals(2, Regex(Regex.escape(token)).findAll(out).count())
    }

    @Test
    fun `不同类别各有各的前缀，一眼看得出它是什么`() {
        val out = AgentMath.forCloud("卡 $card 邮箱 $mail 号码 $phone", ::sink)
        assertTrue(out, out.contains("[CARD_"))
        assertTrue(out, out.contains("[EMAIL_"))
        assertTrue(out, out.contains("[PHONE_"))
        assertFalse(out, out.contains(card))
        assertFalse(out, out.contains(mail))
        assertFalse(out, out.contains(phone))
    }

    @Test
    fun `不传假名就退成老样子 —— 只换成类别标记`() {
        val out = AgentMath.forCloud("号码 $phone")
        assertFalse(out, out.contains(phone))
        assertTrue(out, out.contains(SecretMath.markOf(SecretMath.KIND_PHONE)))
        assertFalse(out, out.contains("PHONE_"))
    }

    @Test
    fun `出去再回来，给出去的是代号，拿回来的是真值`() {
        val out = AgentMath.forCloud("号码 $phone", ::sink)
        assertFalse(out, out.contains(phone))
        // 云端原样把那句话复述了一遍
        val back = AgentMath.fromCloud("我记下了:$out", ::lookup)
        assertTrue(back, back.contains(phone))
        assertFalse(back, back.contains("PHONE_"))
    }

    @Test
    fun `查不到的代号原样留着 —— 不许替它编或者删掉`() {
        val s = "它说 [PHONE_abcdef] 和 [CARD_000000] 都要"
        assertEquals(s, AgentMath.fromCloud(s, ::lookup))
    }

    @Test
    fun `正文里的普通方括号不许被碰`() {
        val s = "见 [注] 和 [1],还有 [PHONE] 这种没编号的"
        assertEquals(s, AgentMath.fromCloud(s, ::lookup))
    }

    @Test
    fun `错开的那个代号也认得出来`() {
        val alias = AliasMath.disambiguate(AliasMath.baseAlias(SecretMath.KIND_PHONE, phone), 2)
        assertTrue(alias.endsWith("-2"))
        val back = AgentMath.fromCloud(AliasMath.wrap(alias)) { a -> if (a == alias) phone else null }
        assertEquals(phone, back)
    }

    @Test
    fun `mentioned 只报代号，不带值`() {
        val out = AgentMath.forCloud("$phone 和 $card", ::sink)
        val names = AliasMath.mentioned(out)
        assertEquals(2, names.size)
        assertTrue(names.all { !it.contains("13812345678") })
    }

    @Test
    fun `落盘一行六个字段，坏行丢一条`() {
        val a = Alias("PHONE_3f2a91", SecretMath.KIND_PHONE, phone, 100L, 200L, 3)
        assertEquals(a, AliasMath.parse(AliasMath.line(a)))
        assertNull(AliasMath.parse(""))
        assertNull(AliasMath.parse("PHONE_3f2a91|手机号|$phone"))          // 字段不够
        assertNull(AliasMath.parse("PHONE_3f2a91|手机号|$phone|x|1|1"))    // 时间不是数
        assertNull(AliasMath.parse("|手机号|$phone|1|1|1"))                // 词是空的
    }

    @Test
    fun `表满了丢最久没用过的，不是最早建的`() {
        val old = Alias("A_000001", SecretMath.KIND_PHONE, "1", createdAt = 1, lastUsed = 900, hits = 9)
        val fresh = Alias("B_000002", SecretMath.KIND_PHONE, "2", createdAt = 800, lastUsed = 100, hits = 1)
        val kept = AliasMath.trim(listOf(old, fresh), max = 1)
        assertEquals(listOf("A_000001"), kept.map { it.alias })
    }
}
