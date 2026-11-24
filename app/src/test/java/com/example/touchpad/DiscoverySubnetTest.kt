package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [hostsInSubnet] —— 「扫描找电脑」时该往哪些地址撒单播探测。
 *
 * 为什么值得单测:整个「扫描要扫两次」的修法就压在这段上。它全是 32 位有符号整数的
 * 位运算,而 `192.168.x.x` 在 Int 里**是负数**。直接用 `<` 比较之所以对,靠的是
 * 「前缀 >= 16 时网段必然跨不过 0x80000000」这条**隐藏前提** —— 而这条前提正是被
 * 函数开头那句 `prefix < 16 就返回空` 守着的。错的表现是「扫描找不到电脑」,
 * 不报任何错,最难查的那种。所以下面两条:有符号的网段要算对,放大网段的网段要返回空。
 *
 * 断言的都是**行为**(往哪撒、不往哪撒),不是实现。
 *
 * 跑:`./gradlew :app:testDebugUnitTest`
 */
class DiscoverySubnetTest {

    private fun ipToInt(s: String): Int {
        val p = s.split(".")
        require(p.size == 4) { "不是个 IPv4:$s" }
        return (p[0].toInt() shl 24) or (p[1].toInt() shl 16) or
                (p[2].toInt() shl 8) or p[3].toInt()
    }

    private fun intToIp(v: Int): String =
        "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"

    private fun hosts(ip: String, prefix: Int, cap: Int = 1022): List<String> =
        hostsInSubnet(ipToInt(ip), prefix, cap).map(::intToIp)

    // ---------- 最常见的一种:家里就是 /24 ----------

    @Test
    fun `斜杠24 —— 撒 1 到 254,不撒网络号广播号和自己`() {
        val h = hosts("192.168.1.12", 24)

        assertEquals("该撒 254 个主机地址,再刨掉自己", 253, h.size)
        assertEquals("第一个是 .1", "192.168.1.1", h.first())
        assertEquals("最后一个是 .254", "192.168.1.254", h.last())

        assertFalse("网络号 .0 不能撒", h.contains("192.168.1.0"))
        assertFalse("广播号 .255 不能撒", h.contains("192.168.1.255"))
        assertFalse("不该往自己发(包会原样回到自己的 socket)", h.contains("192.168.1.12"))

        assertTrue("一个都不能跑到网段外面去",
            h.all { it.startsWith("192.168.1.") })
    }

    @Test
    fun `自己正好是第一个地址时也要跳过`() {
        val h = hosts("192.168.1.1", 24)
        assertEquals(253, h.size)
        assertEquals("跳过了 .1,第一个就该是 .2", "192.168.1.2", h.first())
    }

    @Test
    fun `升序、不重复`() {
        val raw = hostsInSubnet(ipToInt("192.168.1.12"), 24)
        for (i in 1 until raw.size) {
            assertTrue("第 $i 个(${intToIp(raw[i])})没有比前一个大",
                raw[i] > raw[i - 1])
        }
    }

    // ---------- ★ 有符号整数这个坑 ----------

    @Test
    fun `192点168 在 Int 里是负数 —— 整个网段照样要算对`() {
        // 0xC0A8000C 作为有符号 Int 是约 -10.6 亿,广播号 -10.6 亿也差不多 ——
        // 两个都是负数、且相对大小没颠倒,所以 `cur < bcast` 是对的。
        // 这条测的就是「这个网段确实算得出来」:哪天有人动了前缀守卫,
        // 它会先炸在这里,而不是等用户报「扫描找不到电脑」。
        val raw = hostsInSubnet(ipToInt("192.168.1.12"), 24)
        assertTrue("222.x 开头的网段算出来是空的(有符号比较写错了)", raw.isNotEmpty())
        assertEquals(253, raw.size)
    }

    @Test
    fun `223点255 —— 贴着符号位那一侧也一样`() {
        val h = hosts("223.255.255.254", 24)
        assertEquals("254 个主机刨掉自己", 253, h.size)
        assertTrue(h.all { it.startsWith("223.255.255.") })
    }

    @Test
    fun `10点0点0点0 斜杠16 —— 网段和广播号都还是正数,也要对`() {
        val h = hosts("10.0.5.5", 16)
        assertTrue("10.x 是正数那半边,同样不能算空", h.isNotEmpty())
        assertTrue(h.all { it.startsWith("10.0.") })
    }

    // ---------- 封顶 ----------

    @Test
    fun `斜杠16 有六万多个地址 —— 必须封顶,不许真撒出去`() {
        val raw = hostsInSubnet(ipToInt("10.0.5.5"), 16)
        assertEquals("默认上限 1022", 1022, raw.size)
        assertTrue("封顶后仍在同一个网段里", raw.all { (it ushr 16) and 0xFFFF == 0x0A00 })
        assertTrue("不包含自己", raw.none { it == ipToInt("10.0.5.5") })
    }

    @Test
    fun `上限可以被调用方调小`() {
        val raw = hostsInSubnet(ipToInt("192.168.1.12"), 24, cap = 10)
        assertEquals(10, raw.size)
        assertEquals("还是从 .1 开始", "192.168.1.1", intToIp(raw.first()))
    }

    // ---------- 斜杠23:不是所有家用网都是 /24 ----------

    @Test
    fun `斜杠23 —— 跨 192点168点10 和 192点168点11 两段`() {
        val h = hosts("192.168.10.5", 23)
        assertEquals("510 个主机刨掉自己", 509, h.size)
        assertEquals("从 10.1 起", "192.168.10.1", h.first())
        assertEquals("到 11.254 止", "192.168.11.254", h.last())
        assertFalse("网络号 192.168.10.0 不能撒", h.contains("192.168.10.0"))
        assertFalse("广播号 192.168.11.255 不能撒", h.contains("192.168.11.255"))
    }

    // ---------- 不该撒的网段 ----------

    @Test
    fun `点对点和单机地址没有邻居可撒`() {
        assertTrue("/31 返回空", hostsInSubnet(ipToInt("192.168.1.1"), 31).isEmpty())
        assertTrue("/32 返回空", hostsInSubnet(ipToInt("192.168.1.1"), 32).isEmpty())
    }

    @Test
    fun `比斜杠16 还大的网段一律不撒`() {
        // 那已经不是家庭网了;真撒出去几万个包既慢又像在扫端口。
        // 顺带:这条守卫同时是有符号比较能成立的前提(见文件头),别看着没用删掉。
        for (p in listOf(0, 1, 8, 12, 15)) {
            assertTrue("/$p 该返回空", hostsInSubnet(ipToInt("10.0.0.1"), p).isEmpty())
        }
    }

    @Test
    fun `只要有一台能回,就不该是空表 —— 这是扫描能成的底线`() {
        // 用户家里的实际情况:手机 192.168.1.12,电脑 192.168.1.14。
        val h = hosts("192.168.1.12", 24)
        assertTrue("电脑 192.168.1.14 必须在撒的名单里", h.contains("192.168.1.14"))
    }
}
