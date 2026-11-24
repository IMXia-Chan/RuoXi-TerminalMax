package com.example.touchpad

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「谁的回声」的测试。
 *
 * ★★ 这里防的错**不抛异常、不写日志**:一条迟到的回调被认下来,麦克风就提前开了,
 *   她把自己正在说的话听回去 —— 而她拿到的是一句**语法通顺、上下文合理**的用户话。
 *   所以下面几乎每条用例都在问同一件事:**「这条不许认」**。
 */
class EchoMathTest {

    // ------------------------------------------------------------- 该认的

    @Test
    fun `正在等的那一条才算我们的`() {
        assertTrue(EchoMath.isOurs("conmarn-7", "conmarn-7"))
    }

    // ------------------------------------------------------------- 不许认的

    /**
     * ★★ 这条就是那个 bug 本身。
     *
     * 她说第 1 句 → 他打断 / 她改口 → 她在说第 2 句 → **第 1 句的 `onDone` 迟到**。
     * 认了它 = 第 2 句还没念完就把麦放回去。
     */
    @Test
    fun `上一句迟到的回声不许冒充这一句`() {
        assertFalse(EchoMath.isOurs("conmarn-6", "conmarn-7"))
    }

    /**
     * ★ 号是**整串相等**,不是「看着像」。
     *
     * `"conmarn-1"` 是 `"conmarn-11"` 的前缀 —— 哪天有人把判据「顺手」写成
     * `startsWith` / `contains`,这条会立刻红。
     */
    @Test
    fun `号要对得上整串_前缀不算`() {
        assertFalse(EchoMath.isOurs("conmarn-1", "conmarn-11"))
        assertFalse(EchoMath.isOurs("conmarn-11", "conmarn-1"))
    }

    /**
     * ★★ 没带号的一律不算。
     *
     * 全项目每一处 `speak()` 都传了号(见 [EchoMath] 文件头),
     * 所以一条不带号的回调**只可能来自我们自己没排过的那句话**。
     */
    @Test
    fun `不带号的回调不算我们的`() {
        assertFalse(EchoMath.isOurs(null, "conmarn-7"))
    }

    /**
     * ★★ 没在等的时候,谁的回声都不算 —— 这一条同时是**幂等**。
     *
     * 两处监听器都会在「已经兑现过了 / 刚被 stop 过 / 还没开始念」的时候收到回调。
     * 认下来的话,她会去开一个**已属新的一轮**的麦(见 [HerVoice] 那条 `onDone` 契约)。
     */
    @Test
    fun `没在等的时候谁的回声都不算`() {
        assertFalse(EchoMath.isOurs("conmarn-7", null))
        assertFalse(EchoMath.isOurs(null, null))
    }

    /**
     * ★ 换一句之后,前一句的回声**当场作废**。
     *
     * 这条不是在测 `isOurs`,是在把两处的**用法**钉住:认之前必须拿**当下**的号去比,
     * 不能拿「上一次问到的那个号」缓存着比。
     */
    @Test
    fun `换一句之后前一句的回声当场作废`() {
        var current: String? = "conmarn-1"
        assertTrue(EchoMath.isOurs("conmarn-1", current))
        current = "conmarn-2"                       // 她改口了
        assertFalse(EchoMath.isOurs("conmarn-1", current))
        assertTrue(EchoMath.isOurs("conmarn-2", current))
    }
}
