package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 她的长期记忆的纯规则测试 —— 不碰 Android / org.json。
 *
 * 为什么值得钉:这层写错的表现**不是崩溃**,是「她突然一本正经地管他叫别的」
 * 或者「把一句普通话当成名字记了三年」。真机上要等好几天才看得出来,
 * 而且那时候错的记忆已经落盘了 —— 只能靠这里拦住。
 */
class UserLexiconTest {

    // ------------------------------------------------------------------
    // looksLikeName:整个抽取里最要紧的一道闸
    // ------------------------------------------------------------------

    @Test
    fun `像名字的放行`() {
        listOf("小明", "老王", "阿May", "老板", "哥").forEach {
            assertTrue("「$it」该被当成名字", LexiconMath.looksLikeName(it))
        }
    }

    @Test
    fun `不像名字的拦住`() {
        // 「我叫你别乱动」「别叫我」「你是谁」—— 这些含「我叫 / 叫我」但不是名字
        listOf("你别乱动", "你", "我", "不要", "没空", "吗", "了吧", "").forEach {
            assertFalse("「$it」不该被当成名字", LexiconMath.looksLikeName(it))
        }
    }

    // ------------------------------------------------------------------
    // extract:他说了什么关于自己的事
    // ------------------------------------------------------------------

    @Test
    fun `以后叫我X`() {
        val f = LexiconMath.extract("以后叫我小明")
        assertEquals(listOf("小明"), f.call)
        assertTrue(f.avoid.isEmpty())
    }

    @Test
    fun `你可以叫我X_也可以是我叫X`() {
        assertEquals(listOf("老王"), LexiconMath.extract("你可以叫我老王").call)
        assertEquals(listOf("阿May"), LexiconMath.extract("我叫阿May").call)
    }

    @Test
    fun `别叫我X_是禁止不是称呼`() {
        val f = LexiconMath.extract("别叫我老板")
        assertEquals(listOf("老板"), f.avoid)
        assertTrue("禁止的叫法不能再被当成允许的", f.call.isEmpty())
    }

    @Test
    fun `句中的叫我_不是他在给自己起名`() {
        // ★ 这条是真机上最贵的一种错:漏了「你可以叫我老王」只是少记一条,
        //   收了「我妈叫我吃饭」他会从此被她叫「吃饭」。两个方向都得钉住。
        assertTrue("「我妈叫我吃饭」里的「叫我」是别人叫他", LexiconMath.extract("我妈叫我吃饭").call.isEmpty())
        assertTrue(LexiconMath.extract("他叫我做事").call.isEmpty())
        assertTrue(LexiconMath.extract("老板叫我加班").call.isEmpty())
    }

    @Test
    fun `就叫我X_也算`() {
        assertEquals(listOf("老王"), LexiconMath.extract("就叫我老王").call)
    }

    @Test
    fun `句中的我叫不算自我介绍`() {
        // 「我叫你别乱动」——「我叫」在句子中间,后面跟的是「你别乱动」,不是名字
        val f = LexiconMath.extract("我叫你别乱动")
        assertTrue("句中「我叫」不该被当自我介绍", f.call.isEmpty())
    }

    @Test
    fun `我是学生这类抽不出来_这是有意的`() {
        // 宁可不记得,也不能让她一本正经管他叫「学生」——见 UserLexicon 的注释
        val f = LexiconMath.extract("我是个学生,平时写代码")
        assertTrue(f.call.isEmpty())
        assertTrue(f.avoid.isEmpty())
    }

    @Test
    fun `不是A是B_既是称呼纠正也是同音字`() {
        val f = LexiconMath.extract("不是威信,是微信")
        assertEquals("微信", f.fixes["威信"])
    }

    @Test
    fun `纠正里的好词得不含禁词才算数`() {
        // good 侧本身不合格(null/空/含「你」)就不学 —— 学坏一条比少学一条糟
        val f = LexiconMath.extract("不是微信,是你")
        assertTrue("「你」不是个词,不该学进去", f.fixes.isEmpty())
    }

    @Test
    fun `自己纠正自己不算`() {
        assertTrue(LexiconMath.extract("不是微信,是微信").fixes.isEmpty())
    }

    @Test
    fun `一句话能同时抽三样`() {
        val f = LexiconMath.extract("以后叫我明哥,不是威信,是微信")
        assertEquals(listOf("明哥"), f.call)
        assertEquals("微信", f.fixes["威信"])
    }

    // ------------------------------------------------------------------
    // correct:同音字替换
    // ------------------------------------------------------------------

    @Test
    fun `同音字换掉`() {
        val out = LexiconMath.correct("打开威信给老王发条消息", mapOf("威信" to "微信"))
        assertEquals("打开微信给老王发条消息", out)
    }

    @Test
    fun `没有映射就原样返回`() {
        assertEquals("随便一句话", LexiconMath.correct("随便一句话", emptyMap()))
    }

    @Test
    fun `不做链式传播`() {
        // A→B,B→C 同时存在时,结果只能是 B —— 「A 变成 C」是他从没纠正过的事
        val out = LexiconMath.correct("A", linkedMapOf("A" to "B", "B" to "C"))
        assertEquals("B", out)
    }

    @Test
    fun `长词优先`() {
        // 「微信」不该先把「微信号」切走半截
        val out = LexiconMath.correct("加个微信号", linkedMapOf("微信" to "威信", "微信号" to "微信"))
        assertEquals("加个微信", out)
    }

    // ------------------------------------------------------------------
    // block:拼给模型看的那块
    // ------------------------------------------------------------------

    @Test
    fun `什么都没有就不出块`() {
        assertNull(LexiconMath.block(emptyList(), emptyList(), emptyMap(), emptyMap()))
    }

    @Test
    fun `块里带上称呼和禁用`() {
        val b = LexiconMath.block(listOf("小明"), listOf("老板"), emptyMap(), emptyMap())!!
        assertTrue(b.contains("小明"))
        assertTrue(b.contains("老板"))
        // 必须说清这不是他这一轮说的 —— 否则模型会当成新消息去回应
        assertTrue(b.contains("以前攒下来的"))
    }

    @Test
    fun `常用应用按次数排_只报前几个`() {
        val apps = mapOf("微信" to 9, "Edge" to 3, "记事本" to 1, "计算器" to 8, "画图" to 2, "设置" to 1)
        val b = LexiconMath.block(emptyList(), emptyList(), apps, emptyMap())!!
        assertTrue(b.indexOf("微信") < b.indexOf("计算器"))
        assertTrue("只报前 5 个,最次的「设置」不该出现", !b.contains("设置"))
    }

    // ==================================================================
    // 记忆库手工编辑那几条规则(2026-10-08)
    //
    // ★★ 为什么这几条值得单独钉一片:它们的调用方是**设置界面上的一个按钮**,
    //   所以他按下去之后**不会有人再看第二眼**。判错的后果全都长这样 ——
    //   「我明明教过她,她怎么忘了」(满了被静默挤掉)或者
    //   「她突然一本正经管我叫别的」(两个名单同时留着一个名字)。
    //   **不崩、不报错**,事后查不出来。所以两个方向都要钉:
    //   该收的必须收下,不该收的**必须一个字都不落盘**(回 null)。
    // ==================================================================

    private fun store(call: List<String> = emptyList(), avoid: List<String> = emptyList(),
                      apps: Map<String, Int> = emptyMap(), fixes: Map<String, String> = emptyMap()) =
        UserLexicon.Store(call = call, avoid = avoid, apps = apps, fixes = fixes)

    // ---- clean:他手打的那几个字 ----

    @Test
    fun `clean_去空白`() {
        assertEquals("小明", LexiconMath.clean("  小明  ", UserLexicon.MAX_NAME_LEN))
    }

    @Test
    fun `clean_空的不要`() {
        assertNull(LexiconMath.clean("", UserLexicon.MAX_NAME_LEN))
        assertNull("光空格等于没填", LexiconMath.clean("    ", UserLexicon.MAX_NAME_LEN))
    }

    @Test
    fun `clean_限长是闭区间`() {
        val max = UserLexicon.MAX_NAME_LEN                       // 8
        assertEquals("刚好 $max 个字要收", "一二三四五六七八", LexiconMath.clean("一二三四五六七八", max))
        assertNull("$max+1 个字就太长了", LexiconMath.clean("一二三四五六七八九", max))
    }

    @Test
    fun `clean_不套像不像名字那道闸`() {
        // ★★ 这是他和"猜"的分界线:下面这些走 looksLikeName 全会被拒
        //   (从一整句话里猜才需要那么严),但**他手打的就是定义**,必须收。
        // ★ 这三个是**从上面 `不像名字的拦住` 那份语料里取的** —— 「哥」不在里面:
        //   它其实是**过得去**的(上面 `像名字的放行` 就收着它)。我第一版写错了一次,
        //   红在这条断言上 —— 这正是「前提也得断言」的好处:语料写错,当场就知道。
        listOf("你别乱动", "不要", "没空").forEach {
            assertFalse("前提:「$it」确实过不了猜话那道闸", LexiconMath.looksLikeName(it))
            assertEquals("但手打的必须收下", it, LexiconMath.clean(it, UserLexicon.MAX_NAME_LEN))
        }
    }

    // ---- 称呼 / 禁止:同一份名单的两面 ----

    @Test
    fun `加称呼_已经有了就不收`() {
        assertNull(LexiconMath.addCall(store(call = listOf("小明")), "小明"))
    }

    @Test
    fun `加称呼_满了就拒_旧的不许被挤掉`() {
        val full = store(call = listOf("小明", "老王", "阿May"))
        assertEquals("前提:正好是上限", UserLexicon.MAX_CALL, full.call.size)
        assertNull("满了应该拒收", LexiconMath.addCall(full, "老板"))
        assertEquals("★ 原来的三个一个都不许少", listOf("小明", "老王", "阿May"), full.call)
    }

    @Test
    fun `加称呼_会顺手把禁止名单里的同名拿掉`() {
        // ★ 两个名单同时留一个名字 = 提示词里「叫他小明」+「别叫他小明」,她只能瞎猜
        val out = LexiconMath.addCall(store(call = emptyList(), avoid = listOf("小明", "老板")), "小明")!!
        assertEquals(listOf("小明"), out.call)
        assertEquals(listOf("老板"), out.avoid)
    }

    @Test
    fun `加禁止_会顺手把称呼名单里的同名拿掉`() {
        val out = LexiconMath.addAvoid(store(call = listOf("小明", "老王")), "小明")!!
        assertEquals(listOf("小明"), out.avoid)
        assertEquals(listOf("老王"), out.call)
    }

    @Test
    fun `加禁止_满了也拒`() {
        val full = store(avoid = listOf("小明", "老王", "阿May"))
        assertNull(LexiconMath.addAvoid(full, "老板"))
        assertEquals(3, full.avoid.size)
    }

    @Test
    fun `删称呼_没这条就回null`() {
        assertEquals(emptyList<String>(), LexiconMath.removeCall(store(call = listOf("小明")), "小明")!!.call)
        assertNull("压根没这条就不该落盘", LexiconMath.removeCall(store(call = listOf("小明")), "老王"))
    }

    @Test
    fun `删禁止_没这条就回null`() {
        assertEquals(emptyList<String>(), LexiconMath.removeAvoid(store(avoid = listOf("小明")), "小明")!!.avoid)
        assertNull(LexiconMath.removeAvoid(store(avoid = listOf("小明")), "老王"))
    }

    // ---- 常用应用:次数是他的账本 ----

    @Test
    fun `设应用次数_零和负数都是删`() {
        val s = store(apps = mapOf("微信" to 12))
        assertEquals("减到 0 = 删掉这条", emptyMap<String, Int>(), LexiconMath.setApp(s, "微信", 0)!!.apps)
        assertEquals("负数也一样", emptyMap<String, Int>(), LexiconMath.setApp(s, "微信", -3)!!.apps)
    }

    @Test
    fun `设应用次数_新的应用从零加起`() {
        assertEquals(mapOf("微信" to 1), LexiconMath.setApp(store(), "微信", 1)!!.apps)
    }

    @Test
    fun `设应用次数_删一条不存在的应用不算改动`() {
        // 界面上的「删掉」按在一个已经不在了的名字上 —— 不该落盘,也不该报成功
        assertNull(LexiconMath.setApp(store(apps = mapOf("微信" to 3)), "记事本", 0))
    }

    @Test
    fun `设应用次数_值没变就不落盘`() {
        assertNull(LexiconMath.setApp(store(apps = mapOf("微信" to 3)), "微信", 3))
    }

    @Test
    fun `应用满了可以改现成的_但不能加新的`() {
        val full = store(apps = (1..UserLexicon.MAX_APPS).associate { "应用$it" to 1 })
        assertNull("再加一个就得挤掉别人,拒", LexiconMath.setApp(full, "新的", 5))
        assertEquals("★ 但改已经有的那条是正当的",
            9, LexiconMath.setApp(full, "应用7", 9)!!.apps["应用7"])
    }

    // ---- 听错的词 ----

    @Test
    fun `同音字_前后一样就不收`() {
        assertNull("指自己等于白记一条,还让 correct 每轮空转", LexiconMath.putFix(store(), "微信", "微信"))
    }

    @Test
    fun `同音字_一模一样就不落盘`() {
        assertNull(LexiconMath.putFix(store(fixes = mapOf("威信" to "微信")), "威信", "微信"))
    }

    @Test
    fun `同音字_改现成的映射不受条数上限影响`() {
        val full = store(fixes = (1..UserLexicon.MAX_FIXES).associate { "错$it" to "对$it" })
        assertNull("新键满了要拒", LexiconMath.putFix(full, "新的", "对的"))
        assertEquals("★ 但改已经记下的那条是正当的",
            "改过", LexiconMath.putFix(full, "错7", "改过")!!.fixes["错7"])
    }

    @Test
    fun `删同音字_没这条就回null`() {
        assertEquals(emptyMap<String, String>(),
            LexiconMath.removeFix(store(fixes = mapOf("威信" to "微信")), "威信")!!.fixes)
        assertNull(LexiconMath.removeFix(store(fixes = mapOf("威信" to "微信")), "威兴"))
    }
}
