package com.example.touchpad

import com.example.touchpad.SecretMath.KIND_CARD
import com.example.touchpad.SecretMath.KIND_EMAIL
import com.example.touchpad.SecretMath.KIND_ID
import com.example.touchpad.SecretMath.KIND_PHONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「出门那份遮号码」的钉子。
 *
 * ★ 两条判据是**两个方向**都钉的,而且**顺序不能反**:
 *
 * 1. **先证它认得真的** —— 不然「一个都没遮」分不清是「判据准」还是「判据坏了」。
 *    坏掉的判据和准的判据,在没有正控的测试里长得一模一样。
 * 2. **再证它不误伤** —— 这个项目天天读**崩溃墓碑和日志**,而墓碑里
 *    `0000000000000000` 满屏都是、**它过得了 Luhn**。误伤在这里不是多遮几个字的事,
 *    是把「读墓碑」这件事本身弄坏(而读墓碑正是她在干的事)。
 *
 * ★ 还有第三条:**遮了之后,其余一个字都不许动。**
 *   这不是「差不多就行」—— 云端的老师拿到的是策略题面,题面被改动过,
 *   它给的策略就不对;而且「只改一个词」在这里比「整句重写」难查得多。
 */
class SecretMathTest {

    // ══════════════════════════════════════════════════════════════════════
    // 一、正控:真的必须认得出(这一节全绿,下面的才有意义)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `真银行卡号认得出来 连着写和分组写都算`() {
        // Visa 16 位 / Amex 15 位 / 银联 19 位 —— 三种长度各来一张
        assertTrue(SecretMath.cardShapeOk("4111111111111111"))
        assertTrue(SecretMath.cardShapeOk("378282246310005"))
        assertTrue(SecretMath.cardShapeOk("6222021234567890128"))

        // ★ 分开写的才是屏幕上最常见的那个样子;★ 两种写法**各遮各的,其余原样**
        assertEquals("卡号 [银行卡号] 到期 12/28",
            SecretMath.maskOutgoing("卡号 4111 1111 1111 1111 到期 12/28"))
        assertEquals("[银行卡号]", SecretMath.maskOutgoing("3782-822463-10005"))
    }

    @Test
    fun `真身份证认得出来`() {
        // 110101 19900307 1233 —— 校验位是算出来的(3),不是随手凑的
        assertTrue(SecretMath.cnIdOk("110101199003071233"))
        assertEquals("证件号 [身份证号]", SecretMath.maskOutgoing("证件号 110101199003071233"))
    }

    @Test
    fun `真手机号认得出来 四家运营商各来一个`() {
        for (p in listOf("13812345678", "18612345678", "19912345678", "19212345678", "17012345678")) {
            assertTrue("$p 该被认出来", SecretMath.phoneShapeOk(p))
        }
    }

    @Test
    fun `真邮箱认得出来`() {
        assertTrue(SecretMath.emailShapeOk("zhangsan", "example.com"))
        assertTrue(SecretMath.emailShapeOk("a.b+tag", "mail.example.org"))
        assertTrue(SecretMath.emailShapeOk("x", "sub.domain.co.uk"))
        assertEquals("发到 [邮箱] 就行", SecretMath.maskOutgoing("发到 zhangsan@example.com 就行"))
    }

    // ══════════════════════════════════════════════════════════════════════
    // 二、不误伤:这个项目天天读的东西,一个字都不许动
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `十六个零过得了 Luhn 但不是卡号`() {
        // ★★ 这条是**量出来的**,不是我编的例子(2026-10-06 拿这台机器上 282 个真文件跑误伤)。
        //    全零的和是 0,0 能被 10 整除 —— **Luhn 拦不住它**。
        //    而崩溃墓碑里满屏都是 `x0  0000000000000000`,而读墓碑正是这个项目天天干的事。
        assertTrue("Luhn 确实拦不住(所以不能只看 Luhn)", SecretMath.luhnOk("0000000000000000"))
        assertFalse("但它不是卡号", SecretMath.cardShapeOk("0000000000000000"))

        val tombstone = "x0  0000000000000000  x1  0000007d48fa7708\n" +
            "x26 000000005a000000  x4  0000000000000000\n" +
            "10-05 11:20:05.422  14592  17420  signal 6 (SIGABRT)"
        assertEquals("墓碑必须原样出来", tombstone, SecretMath.maskOutgoing(tombstone))
    }

    @Test
    fun `首位不在 2 到 6 的一律不算卡号`() {
        // ISO/IEC 7812 的行业号:0 是 ISO 保留、1 是航空、7 是石油、9 是电信 —— 支付卡是 2~6
        for (d in listOf("0", "1", "7", "8", "9")) {
            assertFalse("首位 $d 不该算卡号", SecretMath.cardShapeOk(d + "411111111111111"))
        }
    }

    @Test
    fun `长度只认 15 16 19 十三位的毫秒时间戳不许中招`() {
        assertFalse(SecretMath.cardShapeOk("1759700000000"))    // 13 位
        assertFalse(SecretMath.cardShapeOk("17597000000000000")) // 17 位
        // 卡号粘着别的数字时不算(避免从一长串里抠出半截来)
        assertEquals("订单 41111111111111110001", SecretMath.maskOutgoing("订单 41111111111111110001"))
    }

    @Test
    fun `手机号光有形状不够 号段没放出去的就不是`() {
        // ★★ 这就是号段表存在的全部理由:下面这几个形状**完全正确**(1 开头、11 位),
        //    光靠正则一个都挡不住 —— 订单号、流水号随手一凑就长这样。
        for (p in listOf("15412345678", "16812345678", "17912345678", "19412345678", "12345678901")) {
            assertFalse("$p 号段不在表里,不该算手机号", SecretMath.phoneShapeOk(p))
        }
        assertEquals("流水号 15412345678 不是手机号",
            "流水号 15412345678 不是手机号", SecretMath.maskOutgoing("流水号 15412345678 不是手机号"))
    }

    @Test
    fun `代码里的 this@标签 不许被当成邮箱`() {
        // ★ 实测的那条真误伤(Kotlin 里 `this@标签` 的写法)。
        //   干掉它的是「域名每一段必须全小写」—— 真域名惯例小写,而标识符是驼峰。
        assertFalse(SecretMath.emailShapeOk("this", "MainActivity.contentResolver"))
        assertEquals("this@MainActivity.contentResolver",
            SecretMath.maskOutgoing("this@MainActivity.contentResolver"))
    }

    @Test
    fun `不像域名的尾巴都不算邮箱`() {
        assertFalse(SecretMath.emailShapeOk("obj", "Foo.Bar"))      // 有大写 → 是代码
        assertFalse(SecretMath.emailShapeOk("x", "example.123"))    // 顶级域名得是字母
        assertFalse(SecretMath.emailShapeOk("x", "a.b"))            // 顶级域名只有 1 个字母
        assertFalse(SecretMath.emailShapeOk("x", "example.c"))
    }

    @Test
    fun `日期区间和普通日志不许被分组卡号那条误伤`() {
        // 分组那条要求 3 组以上,所以 `2020-01-01` 会**匹配上**,但 8 位数字过不了长度那关。
        // 这条钉的是「一个判据管两条正则」——两边各判各的迟早会分家。
        for (s in listOf("2020-01-01", "起始 2024 2025 2026 结束", "工号 20240101001")) {
            assertEquals(s, SecretMath.maskOutgoing(s))
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 二·五、★ 和电脑端(Python)那一份**会分岔**的地方 —— 钉住它,别当成同一个东西
    //
    // 原因只有一个词:Java 的 `\d` / `\w` **默认只认 ASCII**,Python 在 `str` 上**认全 Unicode**。
    // 下面两条都是**实测**出来的,不是推理:
    //   (Python 那边:`email_hits('张三zhangsan@example.com')` 回空 —— 汉字被当成 `\w`,挡住了)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `汉字紧挨着的邮箱 手机这边遮 电脑那边会漏`() {
        // 这一条 Kotlin **遮得更多**。是安全的方向,但**两边确实不一样** ——
        // 电脑端那两条判据今天没有调用方,所以暂时不咬人;接上去那天这是第一处要对齐的。
        assertEquals("张三[邮箱]", SecretMath.maskOutgoing("张三zhangsan@example.com"))
    }

    @Test
    fun `全角数字的号码两边都不遮`() {
        // Python 是「正则认得出、号段表是 ASCII 的 → 不遮」;Kotlin 是「正则就认不出」。
        // 结果一样,路径不同 —— 写下来免得有人以为其中一边能挡住。
        assertEquals("１３８１２３４５６７８", SecretMath.maskOutgoing("１３８１２３４５６７８"))
    }

    // ══════════════════════════════════════════════════════════════════════
    // 三、遮:换掉的只有号码,其余一个字不动
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `一屏里混着好几种 各遮各的 其余原样`() {
        val src = "标题:订单 13812345678 - 收件箱\n客户 zhangsan@example.com 卡号 4111 1111 1111 1111"
        val want = "标题:订单 [手机号] - 收件箱\n客户 [邮箱] 卡号 [银行卡号]"
        assertEquals(want, SecretMath.maskOutgoing(src))
    }

    @Test
    fun `认不出号码时原样返回 一个字节都不改`() {
        // ★ 这条看着像废话,其实是最要紧的一条:它保证「遮」这个动作是**局部**的。
        //   否则老师拿到的题面被整句重写过,它给的策略就不对了。
        val plain = "在 Windows 上要点击「保存」但没有找到,界面上认得出 12 行字"
        assertEquals(plain, SecretMath.maskOutgoing(plain))
        assertEquals("", SecretMath.maskOutgoing(""))
    }

    // ══════════════════════════════════════════════════════════════════════
    // 四、回执:只报类别,不报值
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `foundKinds 说得出遮了哪几种 但一个值都不带`() {
        val kinds = SecretMath.foundKinds("打 13812345678 或发 zhangsan@example.com")
        assertEquals(listOf(KIND_PHONE, KIND_EMAIL), kinds)

        // ★ 「为了说明我遮了它,所以把原文也写进日志」—— 那就等于没遮。
        //   这条和 `_risky_app` 是同一条老规矩。
        val joined = kinds.joinToString()
        assertFalse("日志里绝不许出现号码本身", joined.contains("13812345678"))
        assertFalse(joined.contains("@"))
    }

    @Test
    fun `foundKinds 的顺序写死 不跟着出现先后走`() {
        // 同一屏文字换个排法,日志不该跟着变 —— 否则日志没法比对
        val a = SecretMath.foundKinds("13812345678 zhangsan@example.com 4111111111111111")
        val b = SecretMath.foundKinds("4111111111111111 zhangsan@example.com 13812345678")
        assertEquals(a, b)
        assertEquals(listOf(KIND_CARD, KIND_PHONE, KIND_EMAIL), a)
    }

    @Test
    fun `foundKinds 要传遮之前的那份`() {
        // ★ 传遮过的进去,它只会回一个空列表 —— 这不是 bug,是用法。
        //   在这里钉住,免得以后有人「先遮再问遮了什么」然后以为判据坏了。
        assertEquals(emptyList<String>(), SecretMath.foundKinds(SecretMath.maskOutgoing("13812345678")))
    }

    // ══════════════════════════════════════════════════════════════════════
    // 五、标记本身
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `API key 按前缀认出并遮掉`() {
        // 2026-11-06 补的:资料里贴着自己的 key 是最常见的一类泄露。
        val cases = listOf(
            "sk-abcdefghijklmnopqrstuvwxyz012345",
            "ghp_1234567890abcdefghijKLMNOPQRST",
            // ★ 拼起来写：整串字面量会被 GitHub 的推送保护当成真 token 拦下来，
            //   这只是一条格式样例，不指向任何真实账号。
            "xox" + "b-123456789012-abcdefghijklmnop",
            "AIzaSyA1234567890abcdefghijklmnopq",
        )
        for (k in cases) {
            val out = SecretMath.maskOutgoing("我的 key 是 $k 别外传")
            assertFalse("漏了:$k", out.contains(k))
            assertTrue("没换标记:$k", out.contains("[密钥]"))
            assertEquals(listOf(SecretMath.KIND_KEY), SecretMath.foundKinds("我的 key 是 $k 别外传"))
        }
    }

    @Test
    fun `普通长字符串和提交哈希不算密钥`() {
        // ★ 宁可漏不可错:遮太多的话,云端拿到的正文全是标记,答案也就不对了。
        val text = "提交 3f2a9c8b1d4e5f60718293a4b5c6d7e8f90a1b2c 已经合进去了"
        assertEquals(text, SecretMath.maskOutgoing(text))
        assertEquals(emptyList<String>(), SecretMath.foundKinds(text))
    }

    @Test
    fun `标记看得出是被拿掉的 而且不带后四位`() {
        // ★ 方括号是故意的:一眼看得出「这里原本有东西」。留后四位没用 ——
        //   云端的老师不需要「认出是哪张卡」,少给一个字节都是白给的。
        assertEquals("[银行卡号]", SecretMath.markOf(KIND_CARD))
        assertEquals("[身份证号]", SecretMath.markOf(KIND_ID))
        val out = SecretMath.maskOutgoing("卡 4111111111111111")
        assertFalse("后四位也不许留", out.contains("1111"))
    }
}
