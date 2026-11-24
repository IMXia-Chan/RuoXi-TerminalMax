package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「要不要等他点头」那几条路的测试。
 *
 * ★★ 这里防的错**不抛异常、不报错,只说一句假话**:他根本没看到确认框,
 *   而她回一句「用户取消了这次输入」。那不是 bug 的写法,是**替用户说话**——
 *   在这个项目里它比崩溃更糟,因为它读起来完全正常。
 *
 * 所以下面钉的是:**四种「不做」必须真的说得不一样**,而且**只有一种算数**。
 */
class ConfirmMathTest {

    /** 除 APPROVED 以外的全部状态 —— 有一处漏了,这个列表就是错的,下面几条会一起红。 */
    private val notApproved = Confirm.entries.filter { it != Confirm.APPROVED }

    /**
     * ★★ 最要紧的一条:**只有 APPROVED 能让动作落地**。
     *
     * 写成黑名单(`!= DENIED`)的话,以后新加一种「问不出来」的状态会**默认放行** ——
     * 那正是这套机制唯一不能出的错。
     */
    @Test
    fun `只有确认才算数_其余一律不做`() {
        assertTrue(ConfirmMath.allowed(Confirm.APPROVED))
        for (c in notApproved) {
            assertFalse("$c 不该被放行", ConfirmMath.allowed(c))
        }
    }

    /** 四种「不做」的理由必须两两不同 —— 塌成一句话就退回到了原来的 bug。 */
    @Test
    fun `四种不做各有各的说法`() {
        val words = notApproved.map { ConfirmMath.reason(it) }
        assertEquals("理由条数对不上:$words", notApproved.size, words.toSet().size)
    }

    /**
     * ★ 最容易被漏掉、也最要紧的一条:**「他没回应」不能说成「他取消了」**。
     *
     * 这两句在模型眼里都是「没做成」,所以他不会追问;但他在屏幕前知道
     * 自己**根本没点过取消**,那一刻她已经不可信了。
     */
    @Test
    fun `没回应和点了取消必须分得开`() {
        val noAnswer = ConfirmMath.reason(Confirm.NO_ANSWER)
        val denied = ConfirmMath.reason(Confirm.DENIED)
        assertTrue("没人应不能说成取消:$noAnswer", !noAnswer.contains("取消"))
        assertTrue("取消不能说成没人应:$denied", !denied.contains("回应"))
    }

    /** 「框没弹出来」要指向**权限**,因为那是他能自己动手修的那一种。 */
    @Test
    fun `弹不出来要点出权限`() {
        assertTrue(ConfirmMath.reason(Confirm.NO_UI).contains("权限"))
    }

    /**
     * 每句都要带「这件事没有执行」那半句。模型拿到「用户取消了」之后最常见的误读
     * 是「那换个方式再来一次」;把「没做」写死,它就少一次自作主张的机会。
     */
    @Test
    fun `每句都说清没做`() {
        val banned = "用户已经做了"
        for (c in notApproved) {
            val r = ConfirmMath.reason(c)
            assertTrue("$c 的措辞里看不出「没做」:$r", r.contains("没有执行"))
            assertFalse(r.contains(banned))
        }
    }

    /** 给他看的那一份也要两两不同,而且**不许出现「用户」**——那是给模型读的那一份的口气。 */
    @Test
    fun `给他看的那句不带客服腔`() {
        val words = notApproved.map { ConfirmMath.shortUser(it) }
        assertEquals("口头那句条数对不上:$words", notApproved.size, words.toSet().size)
        for (c in notApproved) {
            assertTrue("「用户」是给模型读的口径:${ConfirmMath.shortUser(c)}",
                !ConfirmMath.shortUser(c).contains("用户"))
        }
    }
}
