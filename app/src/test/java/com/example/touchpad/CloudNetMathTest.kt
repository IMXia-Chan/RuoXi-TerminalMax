package com.example.touchpad

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端总闸的判定。**这一组是总闸唯一能被机器验的部分** ——
 * 「有没有谁偷偷绕过闸门」靠的是 grep 和真机，不靠这里。
 */
class CloudNetMathTest {

    // ---------------------------------------------------------------- 本机/局域网

    @Test
    fun loopbackIsLocal() {
        assertTrue(CloudNetMath.isLocalHost("127.0.0.1"))
        assertTrue(CloudNetMath.isLocalHost("127.13.99.4"))
        assertTrue(CloudNetMath.isLocalHost("localhost"))
        assertTrue(CloudNetMath.isLocalHost("LOCALHOST"))
        assertTrue(CloudNetMath.isLocalHost("::1"))
        assertTrue(CloudNetMath.isLocalHost("[::1]"))
        assertTrue(CloudNetMath.isLocalHost("::"))
    }

    @Test
    fun privateRangesAreLocal() {
        assertTrue(CloudNetMath.isLocalHost("10.0.0.1"))
        assertTrue(CloudNetMath.isLocalHost("10.255.255.255"))
        assertTrue(CloudNetMath.isLocalHost("192.168.1.5"))
        assertTrue(CloudNetMath.isLocalHost("172.16.0.1"))
        assertTrue(CloudNetMath.isLocalHost("172.31.255.254"))
        assertTrue(CloudNetMath.isLocalHost("169.254.1.1"))
        assertTrue(CloudNetMath.isLocalHost("0.0.0.0"))
    }

    @Test
    fun lanNamesAreLocal() {
        // 不带点的单段名只可能是 mDNS/局域网 DNS —— 公网域名一定带点。
        assertTrue(CloudNetMath.isLocalHost("pixel"))
        assertTrue(CloudNetMath.isLocalHost("DESKTOP-01"))
        assertTrue(CloudNetMath.isLocalHost("pc.local"))
    }

    @Test
    fun ipv6LocalRanges() {
        assertTrue(CloudNetMath.isLocalHost("fe80::1"))
        assertTrue(CloudNetMath.isLocalHost("fc00::1"))
        assertTrue(CloudNetMath.isLocalHost("fd12:3456:789a::1"))
    }

    // ---------------------------------------------------------------- 公网

    @Test
    fun publicIpsAreNotLocal() {
        assertFalse(CloudNetMath.isLocalHost("8.8.8.8"))
        assertFalse(CloudNetMath.isLocalHost("1.1.1.1"))
        assertFalse(CloudNetMath.isLocalHost("172.32.0.1"))     // 刚好出 172.16/12
        assertFalse(CloudNetMath.isLocalHost("172.15.255.255")) // 刚好进不来
        assertFalse(CloudNetMath.isLocalHost("192.169.0.1"))
        assertFalse(CloudNetMath.isLocalHost("11.0.0.1"))
    }

    @Test
    fun publicHostsAreNotLocal() {
        assertFalse(CloudNetMath.isLocalHost("api.openai.com"))
        assertFalse(CloudNetMath.isLocalHost("api.deepseek.com"))
        assertFalse(CloudNetMath.isLocalHost("2001:4860:4860::8888"))
    }

    @Test
    fun junkIsNotLocal() {
        assertFalse(CloudNetMath.isLocalHost(null))
        assertFalse(CloudNetMath.isLocalHost(""))
        assertFalse(CloudNetMath.isLocalHost("   "))
        assertFalse(CloudNetMath.isLocalHost("999.1.1.1"))
        assertFalse(CloudNetMath.isLocalHost("1.2.3"))
        assertFalse(CloudNetMath.isLocalHost("example.com."))
    }

    // ---------------------------------------------------------------- 放行规则

    @Test
    fun openGateAllowsEverything() {
        assertTrue(CloudNetMath.allow(true, "8.8.8.8"))
        assertTrue(CloudNetMath.allow(true, "api.openai.com"))
    }

    @Test
    fun closedGateStillAllowsLocal() {
        assertTrue(CloudNetMath.allow(false, "127.0.0.1"))
        assertTrue(CloudNetMath.allow(false, "192.168.1.7"))
        assertTrue(CloudNetMath.allow(false, "pc.local"))
        assertFalse(CloudNetMath.allow(false, "8.8.8.8"))
        assertFalse(CloudNetMath.allow(false, "api.openai.com"))
    }
}
