package com.example.touchpad

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「什么时候该问他要手指」的测试。
 *
 * ★ 这里钉的是**两个方向都会出事的**那两条:
 *   - 问多了 → 每次切回来都按，她「一直在」那件事就毁了;
 *   - 问少了 → 别人拿到手机直接进她房间。
 *
 * 所以每条都不是「默认值」，而是**一个具体的场景**。
 */
class GateMathTest {

    @Test
    fun 冷启动没验过的时候要问() {
        assertTrue(
            GateMath.needUnlock(enabled = true, available = true, unlocked = false, lockedSince = false),
        )
    }

    @Test
    fun 同一个进程里验过就别再问了_切后台回来不重问() {
        assertFalse(
            GateMath.needUnlock(enabled = true, available = true, unlocked = true, lockedSince = false),
        )
    }

    @Test
    fun 验过之后熄过屏_回来要再问一次() {
        assertTrue(
            GateMath.needUnlock(enabled = true, available = true, unlocked = true, lockedSince = true),
        )
    }

    @Test
    fun 关掉这个开关就一次都不问() {
        assertFalse(
            GateMath.needUnlock(enabled = false, available = true, unlocked = false, lockedSince = true),
        )
    }

    /**
     * ★★ 这条是「打不开」的防线:没录指纹也没设锁屏密码时**不能挡** ——
     *   挡了就是他自己永远进不去。挡不住要说出来（设置那一行会写），不能变成打不开。
     */
    @Test
    fun 这台机器验不了就绝不挡() {
        assertFalse(
            GateMath.needUnlock(enabled = true, available = false, unlocked = false, lockedSince = false),
        )
        assertFalse(
            GateMath.needUnlock(enabled = true, available = false, unlocked = true, lockedSince = true),
        )
    }

    @Test
    fun 危险动作要再验一次本人_哪怕刚验过() {
        assertTrue(GateMath.needHard(enabled = true, available = true))
    }

    @Test
    fun 危险动作在验不了的机器上不卡死_否则钱的事永远做不了() {
        assertFalse(GateMath.needHard(enabled = true, available = false))
        assertFalse(GateMath.needHard(enabled = false, available = true))
    }
}
