package com.example.touchpad

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「步数到顶了,模型还不肯收口」的测试。
 *
 * ★★ 这里防的错**全都不报错**:无界循环的样子是「她想了特别久」,日志里只有
 *   一条接一条的工具轮,没有一处写着「它已经不听话了」。所以钉的是三件事:
 *   给几次机会、边界算哪边、收口那句话不许撒谎。
 */
class StepLimitMathTest {

    // --------------------------------------------------------- 给几次机会

    /**
     * ★★ **恰好等于上限时仍然给机会**(半开语义,和 `HandoffMath.mayKeepWaiting`
     *    同一套写法):第一条回执是提醒、第二条是重申,「正好说了两遍」不算无视。
     *
     * 边界算哪边必须只有一个答案 —— 写成 `>=` 的话,第二条重申会被当场掐掉,
     * 掐掉的是本来下一轮就会改口的模型,那是误伤。
     */
    @Test
    fun `恰好说到上限遍数时仍然给机会`() {
        assertFalse("第二遍就掐了(应该还给机会)", StepLimitMath.giveUp(StepLimitMath.MAX_NOTICES))
        assertTrue("第三遍还没掐", StepLimitMath.giveUp(StepLimitMath.MAX_NOTICES + 1))
    }

    /** ★ 头两回撞上限都不收口 —— 模型听一次就改口是完全正常的,这是它应得的机会。 */
    @Test
    fun `头两次撞上限都不收口`() {
        assertFalse(StepLimitMath.giveUp(0))
        assertFalse(StepLimitMath.giveUp(1))
        assertFalse(StepLimitMath.giveUp(2))
    }

    /**
     * ★★ **判定必须单调** —— 一旦决定收口,再多撞几次也必须还是收口。
     *
     * 这条是循环**能终止**的全部保证:`giveUp` 若在某个数上翻回 false,
     * 无视回执的模型就会永远在「给机会 / 不给」之间打摆子,无界循环修了个寂寞。
     */
    @Test
    fun `判定必须单调_一旦收口就永远收口`() {
        var everGaveUp = false
        for (n in 1..20) {
            if (StepLimitMath.giveUp(n)) {
                everGaveUp = true
                assertTrue("第 $n 次收口了,第 ${n + 1} 次又给机会(不单调)", StepLimitMath.giveUp(n + 1))
            }
        }
        assertTrue("20 次都没收口 —— 这份守卫什么都不拦", everGaveUp)
    }

    // --------------------------------------------------------- 收口那句话

    /**
     * ★★ **不许说「做完了」。** 这一轮就是没做完才停的 —— 收口的话把它说成完成,
     *    是「会撒谎的手」那条规矩在说话层的同款。
     */
    @Test
    fun `收口那句话不许说做完了`() {
        val s = StepLimitMath.finalSay()
        // 改措辞就得改这份名单 —— 这是故意的:这几个词一出现,那句话就在撒谎。
        for (w in listOf("做完了", "完成了", "搞定了", "都办好了")) {
            assertFalse("收口那句话里出现了「$w」: $s", s.contains(w))
        }
    }

    /** ★★ **必须明说「停了」** —— 含糊掉的收口和无限循环在界面上长得一样。 */
    @Test
    fun `收口那句话必须明说停了`() {
        val s = StepLimitMath.finalSay()
        assertTrue("没明说停了: $s", s.contains("停"))
    }

    /**
     * ★ **不许把责任推给他。** 是模型的循环被掐断,不是「你让我做的事太多」——
     *    说成他的错,他会去改自己的说法,而病根在循环守卫上。
     */
    @Test
    fun `收口那句话不许把责任推给他`() {
        val s = StepLimitMath.finalSay()
        for (w in listOf("都怪你", "你让我做", "你说得太多", "太多了")) {
            assertFalse("收口那句话在怪他(出现了「$w」): $s", s.contains(w))
        }
    }

    /** ★ 收口不是句空话 —— 空的最终答复在界面上什么都不显示,等于静默。 */
    @Test
    fun `收口那句话不许是空的`() {
        assertTrue(StepLimitMath.finalSay().isNotBlank())
    }
}
