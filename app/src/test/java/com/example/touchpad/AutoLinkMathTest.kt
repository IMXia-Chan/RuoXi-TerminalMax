package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「要不要自己去拨号」那六条闸的测试 —— 纯 JVM,不碰 Android。
 *
 * ★★ 为什么值得钉:
 * 这六条里有三条(锁屏 / 上次落到配对 / 没有凭证)**坏掉时不报错**,
 * 表现只是「她又不连了」或者「配对码又弹了」—— 和「电脑没开」长得一模一样。
 * 真机上你没法把它们分开,只能在这儿分开。
 *
 * ★ 两个方向都要钉(本项目的铁律:坏掉的判据和准的判据,在没有正控的测试里长得一样):
 * 「该拨的必须拨」和「不该拨的必须不拨」各占一半。
 */
class AutoLinkMathTest {

    private val GAP = 6_000L

    /** 一切都好:连着网、没锁屏、有凭证、没被挡 —— **这一条必须拨**。 */
    private fun ok(
        connected: Boolean = false,
        needsPermission: Boolean = false,
        msSinceLastAttempt: Long = 99_999L,
        deviceLocked: Boolean = false,
        hasCredential: Boolean = true,
        blockedByPairing: Boolean = false,
    ) = AutoLinkMath.Facts(
        connected = connected,
        needsPermission = needsPermission,
        msSinceLastAttempt = msSinceLastAttempt,
        deviceLocked = deviceLocked,
        hasCredential = hasCredential,
        blockedByPairing = blockedByPairing,
    )

    // ---- 正控:不先证明「该拨的会拨」,下面每条不拨都可能是「它压根不拨」 ----

    @Test
    fun `一切正常就该自动拨`() {
        assertTrue(AutoLinkMath.shouldAutoDial(ok(), GAP))
    }

    // ---- 老的两条闸,一条都不许松 ----

    @Test
    fun `已经连着就不拨`() {
        assertFalse(AutoLinkMath.shouldAutoDial(ok(connected = true), GAP))
    }

    @Test
    fun `没有本地网络权限就不拨`() {
        assertFalse(AutoLinkMath.shouldAutoDial(ok(needsPermission = true), GAP))
    }

    @Test
    fun `六秒之内不重复拨`() {
        assertFalse(AutoLinkMath.shouldAutoDial(ok(msSinceLastAttempt = 0L), GAP))
        assertFalse(AutoLinkMath.shouldAutoDial(ok(msSinceLastAttempt = 5_999L), GAP))
        // ★ 边界:正好 6000 算过 —— 半开区间的另一头也要钉住
        assertTrue(AutoLinkMath.shouldAutoDial(ok(msSinceLastAttempt = 6_000L), GAP))
    }

    // ---- ★ 第 ③ 条:锁屏不自动连 ----

    @Test
    fun `手机锁着就不拨`() {
        assertFalse(AutoLinkMath.shouldAutoDial(ok(deviceLocked = true), GAP))
    }

    @Test
    fun `锁着的时候别的原因都不算了 —— 理由要报锁屏`() {
        // 锁屏 + 没凭证,两条都成立;但用户看到的那一句应该是「手机锁着」
        val f = ok(deviceLocked = true, hasCredential = false)
        assertFalse(AutoLinkMath.shouldAutoDial(f, GAP))
        assertEquals("手机锁着 —— 锁屏期间不去连电脑", AutoLinkMath.why(f, GAP))
    }

    // ---- ★★ 第 ① 条:没有免密凭证就不自动连(用户 2026-10-08 定的) ----

    @Test
    fun `盘上没有免密凭证就不拨`() {
        assertFalse(AutoLinkMath.shouldAutoDial(ok(hasCredential = false), GAP))
    }

    // ---- ★★ 第五条:上一次自动拨号落到了配对,就别再自作主张 ----

    @Test
    fun `上次自动连要了配对码,这次就别再拨`() {
        assertFalse(AutoLinkMath.shouldAutoDial(ok(blockedByPairing = true), GAP))
    }

    /**
     * ★★ 这一条是整套里最要紧的一个用例 —— **它钉的就是「光有第 ① 条掐不断风暴」那件事**。
     *
     * 凭证**存在但已经过期**时,`hasCredential` 是 true(盘上真有那个键);
     * 自动拨号照样发生、照样落到配对、照样弹码。
     * 只有 `blockedByPairing` 这个**记在结果上的**标记能掐住它。
     * 谁要是哪天觉得「有了 hasCredential 就够了吧」把这条删了,风暴就回来了。
     */
    @Test
    fun `凭证还在但已经过期 —— 只有「上次落到配对」这一条拦得住它`() {
        val expiredCredential = ok(hasCredential = true, blockedByPairing = true)
        assertFalse(AutoLinkMath.shouldAutoDial(expiredCredential, GAP))
        // 同一份事实,只要把那个标记摘掉 —— 它立刻就又会去拨(证明拦它的正是这一条)
        assertTrue(AutoLinkMath.shouldAutoDial(ok(hasCredential = true), GAP))
    }

    // ---- why():给人看的那句话,几条要分得开 ----

    @Test
    fun `几条不拨的理由必须互相分得开`() {
        val reasons = listOf(
            ok(connected = true),
            ok(needsPermission = true),
            ok(msSinceLastAttempt = 0L),
            ok(deviceLocked = true),
            ok(blockedByPairing = true),
            ok(hasCredential = false),
        ).map { AutoLinkMath.why(it, GAP) }
        assertEquals("六条理由,一句都不许重复", 6, reasons.toSet().size)
    }

    @Test
    fun `可以拨的时候 why 也说人话`() {
        assertEquals("可以自动连", AutoLinkMath.why(ok(), GAP))
    }

    // ---- worthLogging():别把日志刷成一片噪声 ----

    @Test
    fun `高频的那两条不记日志`() {
        assertFalse(AutoLinkMath.worthLogging(ok(connected = true), GAP))
        assertFalse(AutoLinkMath.worthLogging(ok(msSinceLastAttempt = 0L), GAP))
        assertFalse(AutoLinkMath.worthLogging(ok(needsPermission = true), GAP))
    }

    @Test
    fun `真正会让人困惑的那三条要记`() {
        assertTrue(AutoLinkMath.worthLogging(ok(deviceLocked = true), GAP))
        assertTrue(AutoLinkMath.worthLogging(ok(blockedByPairing = true), GAP))
        assertTrue(AutoLinkMath.worthLogging(ok(hasCredential = false), GAP))
    }

    @Test
    fun `一切正常时也不记 —— 拨号本身另有日志`() {
        assertFalse(AutoLinkMath.worthLogging(ok(), GAP))
    }
}
