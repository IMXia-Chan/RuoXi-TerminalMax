package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出门前的代号替换。
 *
 * ★ 这一层的错**不报错**：漏一个号码，界面上照样写「已脱敏」；换不回来，
 *   他就看到一串代号，还以为是资料里本来就有的。两种都要钉住。
 */
class RedactMathTest {

    @Test
    fun `手机号换成代号 同一个号永远同一个代号`() {
        val v = RedactMath.Vault()
        val out = v.redact("联系 13800138000，备用还是 13800138000，另一个 13900139000。")
        assertEquals("联系 ［电话1］，备用还是 ［电话1］，另一个 ［电话2］。", out)
        assertFalse(out.contains("13800138000"))
    }

    @Test
    fun `身份证 卡号 邮箱 密钥都挡得住`() {
        val v = RedactMath.Vault()
        val out = v.redact(
            "身份证 11010519491231002X；卡 6222021234567890123；" +
                "邮箱 zhang.san+work@example.com.cn；key sk-abcdefghijklmnopqrstuvwxyz012345"
        )
        assertFalse(out.contains("11010519491231002X"))
        assertFalse(out.contains("6222021234567890123"))
        assertFalse(out.contains("zhang.san+work"))
        assertFalse(out.contains("sk-abcdefghij"))
        assertTrue(out.contains("［证件1］"))
        assertTrue(out.contains("［邮箱1］"))
        assertTrue(out.contains("［密钥1］"))
    }

    @Test
    fun `带空格分组的卡号整段算一个`() {
        val v = RedactMath.Vault()
        assertEquals("卡号 ［卡号1］", v.redact("卡号 6222 0212 3456 7890"))
        // 另一个号是另一个代号 —— 同一本账里不会把两个号并成一个
        assertEquals("卡号［卡号2］", v.redact("卡号6222021234567890123"))
    }

    @Test
    fun `普通数字不动`() {
        val v = RedactMath.Vault()
        val text = "2026年11月8日 14:30，花了 1200 元，第 3 条，订单号 20261108143000。"
        assertEquals(text, v.redact(text))
        assertTrue(v.isEmpty())
    }

    @Test
    fun `回来能换回真值 半角方括号也认`() {
        val v = RedactMath.Vault()
        val sent = v.redact("打 13800138000 找他")
        assertTrue(sent.contains("［电话1］"))
        // 模型把全角括号写成半角，一样要换回来
        assertEquals("打 13800138000 找他", v.restore("打 [电话1] 找他"))
        assertEquals("打 13800138000 找他", v.restore(sent))
    }

    @Test
    fun `没有命中时一个字都不改`() {
        val v = RedactMath.Vault()
        val text = "第三章 结论 甲方的义务如下"
        assertEquals(text, v.redact(text))
        assertEquals(text, v.restore(text))
        assertTrue(v.isEmpty())
    }

    @Test
    fun `代号挨着中文也认得出来`() {
        val v = RedactMath.Vault()
        val out = v.redact("电话13800138000和邮箱a@b.cn都在这")
        assertEquals("电话［电话1］和邮箱［邮箱1］都在这", out)
    }
}
