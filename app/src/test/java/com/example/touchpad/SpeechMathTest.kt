package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「哪些字该念」的测试。
 *
 * ★★ 这里防的错**不抛异常、不报错**:一段台词被多剥了几个字,她照念——只是内容少了一截。
 *   所以下面几乎一半的用例是**反面的**(「这个不许剥」),而不是正面的。
 *
 * ★ 判错的代价不对称(见 [SpeechMath] 文件头):
 *   漏剥只是多念两个字,**错剥是把她说的话吃掉一截**。所以测试的重心在「不许剥」。
 */
class SpeechMathTest {

    // ------------------------------------------------------------- 该剥的

    @Test
    fun `星号包起来的动作要剥掉`() {
        assertEquals("我没事", SpeechMath.forSpeech("*叹气*我没事"))
        assertEquals("我今天好累", SpeechMath.forSpeech("我今天好累*sigh*"))
    }

    /** ★ 中文括号里的动作 —— 靠[动作词]认出来,不靠括号形状。 */
    @Test
    fun `中文括号里的动作词要剥掉`() {
        assertEquals("我没事", SpeechMath.forSpeech("（叹气）我没事"))
        assertEquals("我没事", SpeechMath.forSpeech("我没事（笑）"))
        assertEquals("好吧", SpeechMath.forSpeech("【沉默了一会儿】好吧"))
    }

    @Test
    fun `一句里连着两处提示要都剥掉`() {
        assertEquals("我没事", SpeechMath.forSpeech("（笑）我没事（叹气）"))
    }

    @Test
    fun `英文动作也要剥`() {
        assertEquals("I'm fine", SpeechMath.forSpeech("(sighs) I'm fine"))
    }

    @Test
    fun `剥完不留双空格`() {
        assertEquals("我没事 真的", SpeechMath.forSpeech("（笑）我没事 真的"))
    }

    // ------------------------------------------------------------- 不许剥的
    // ★ 这一节比上面那一节重要:错剥是静默的,而且吃掉的是她的话。

    /**
     * ★★ 最要紧的一条:中文括号里**到处都是真内容**。
     * 「(真的)」没有动作词,所以只能原样留着 —— 剥了就等于替她删字。
     */
    @Test
    fun `括号里没有动作词的_一个字都不许动`() {
        for (s in listOf(
            "我(真的)没骗你",
            "这个是【重点】",
            "（他说今天下雨了）",
            "我明天(周五)回来",
        )) {
            assertEquals("不该动:$s", s, SpeechMath.forSpeech(s))
        }
    }

    /**
     * ★★ 方括号和尖括号**一律不剥** —— 它们和数组下标、泛型、HTML 撞得太狠,
     * 而这三样在这台机器上是真会出现的东西(`list_ui` 的回执、网页正文)。
     * 计划里列了它们,这里是**故意收窄**。
     */
    @Test
    fun `方括号尖括号一律不剥`() {
        for (s in listOf(
            "数组 a[0] 是第一个",
            "看到 <div> 这个标签",
            "回执里有 [click_ui] 这个工具名",
            "[sigh] 这种写法不许当动作",
        )) {
            assertEquals("不该动:$s", s, SpeechMath.forSpeech(s))
        }
    }

    /**
     * ★ 括号里带着**句号**就说明那不是提示,是一整句 —— 而一整句是要念出来的。
     * 少了这条,(他说：今天下雨了。) 会整段消失。
     */
    @Test
    fun `括号里是一整句的真话要留着`() {
        val s = "（他说今天会下雨。记得带伞）"
        assertEquals(s, SpeechMath.forSpeech(s))
    }

    /** 太长的也不像提示。 */
    @Test
    fun `括号里太长的不当提示`() {
        val s = "（她轻轻地叹了口气然后把头转过去看着窗外）"
        assertEquals(s, SpeechMath.forSpeech(s))
    }

    /**
     * ★ 配不上对的括号**原样留着** —— 补一个右括号或者整段丢掉都是在替模型改字,
     * 而这是唯一一件我们绝不该做的事。
     */
    @Test
    fun `配不上对的括号不许动`() {
        assertEquals("（笑 我没事", SpeechMath.forSpeech("（笑 我没事"))
        assertEquals("*叹气 我没事", SpeechMath.forSpeech("*叹气 我没事"))
    }

    @Test
    fun `没有括号的话原样通过`() {
        val s = "我笑了,真的挺好的"
        assertEquals(s, SpeechMath.forSpeech(s))
    }

    // ------------------------------------------------------------- 边界

    /**
     * ★★ 整句就是一个提示时,**退回原文**。
     *
     * 剥完变空的话她就一声不吭了 —— 而「她不出声」在这个项目里的症状是
     * 「她不理我了」,比难听难查得多(对话循环挂在「她念完了」那个回调上)。
     */
    @Test
    fun `剥空了要退回原文_不能一声不吭`() {
        assertEquals("（叹气）", SpeechMath.forSpeech("（叹气）"))
        assertEquals("*笑*", SpeechMath.forSpeech("*笑*"))
    }

    @Test
    fun `空串和白串进什么出什么`() {
        assertEquals("", SpeechMath.forSpeech(""))
        assertEquals("   ", SpeechMath.forSpeech("   "))
    }

    /** 剥干净之后再跑一次不该变 —— 这个函数会在两条嗓子各调一次,不能有二次效应。 */
    @Test
    fun `幂等`() {
        for (s in listOf("（笑）我没事（叹气）", "我(真的)没骗你", "*sigh* 好吧")) {
            val once = SpeechMath.forSpeech(s)
            assertEquals("二次调用变了:$s", once, SpeechMath.forSpeech(once))
        }
    }

    /** 全角星号也要认 —— 4B 打全角的时候不少。 */
    @Test
    fun `全角星号也算动作`() {
        assertTrue(!SpeechMath.forSpeech("＊叹气＊我没事").contains("叹气"))
    }
}
