package com.example.touchpad

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MQTT 的报文形状。
 *
 * ★ 钉的是「不崩、但对方立刻断开」的那几种：变长整数少算一层、
 *   订阅少写一个 QoS 字节、QoS 1 的报文号位置错开两格。
 *   它们的症状在手机上都是**「没反应」**，抓不到任何异常。
 */
class MqttMathTest {

    @Test
    fun `变长整数来回走一趟不变`() {
        for (n in listOf(0, 1, 127, 128, 16_383, 16_384, 2_097_151, 2_097_152, 268_435_455)) {
            val b = MqttMath.encodeLength(n)
            val (v, used) = MqttMath.decodeLength(b + byteArrayOf(9), 0)!!
            assertEquals("值 $n", n, v)
            assertEquals("用了几个字节 $n", b.size, used)
        }
    }

    @Test
    fun `变长整数是规范里那几个字节`() {
        // 规范原文的例子：127 → 7F，128 → 80 01，16383 → FF 7F，16384 → 80 80 01
        assertArrayEquals(byteArrayOf(0x7F), MqttMath.encodeLength(127))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), MqttMath.encodeLength(128))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x7F), MqttMath.encodeLength(16_383))
        assertArrayEquals(
            byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x01),
            MqttMath.encodeLength(16_384),
        )
    }

    @Test
    fun `截断的变长整数返回空而不是抛异常`() {
        assertNull(MqttMath.decodeLength(byteArrayOf(0x80.toByte()), 0))
        assertNull(MqttMath.decodeLength(byteArrayOf(), 0))
    }

    @Test
    fun `发布用的主题不许带通配符`() {
        assertTrue(MqttMath.topicOk("home/livingroom/light/set"))
        assertFalse(MqttMath.topicOk("home/+/light"))
        assertFalse(MqttMath.topicOk("home/#"))
        assertFalse(MqttMath.topicOk(""))
    }

    @Test
    fun `订阅过滤器按规矩收通配符`() {
        assertTrue(MqttMath.filterOk("home/+/light"))
        assertTrue(MqttMath.filterOk("home/#"))
        assertTrue(MqttMath.filterOk("#"))
        assertFalse("井号必须独占一层且在最末", MqttMath.filterOk("home/a#"))
        assertFalse("井号后面不能再有层级", MqttMath.filterOk("home/#/x"))
        assertFalse("加号必须独占一层", MqttMath.filterOk("home/liv+room"))
    }

    @Test
    fun `主题匹配`() {
        assertTrue(MqttMath.matches("a/+/c", "a/b/c"))
        assertFalse(MqttMath.matches("a/+/c", "a/b/c/d"))
        assertFalse(MqttMath.matches("a/+", "a"))
        assertTrue(MqttMath.matches("a/#", "a/b/c"))
        assertTrue("按规范，# 也匹配它自己那一层", MqttMath.matches("a/#", "a"))
        assertTrue(MqttMath.matches("#", "任意/层级"))
        assertFalse(MqttMath.matches("a/b", "a/b/c"))
    }

    /** 把固定头读掉，返回 (报文类型, 剩余长度, 正文)。 */
    private fun split(p: ByteArray): Triple<Int, Int, ByteArray> {
        val type = (p[0].toInt() and 0xFF) shr 4
        val (len, n) = MqttMath.decodeLength(p, 1)!!
        return Triple(type, len, p.copyOfRange(1 + n, p.size))
    }

    @Test
    fun `CONNECT 的协议名、版本、标志位`() {
        val p = MqttMath.connect("cid", "u", "p")
        val (type, len, body) = split(p)
        assertEquals(MqttMath.CONNECT, type)
        assertEquals(body.size, len)
        // 前 6 个字节是 00 04 M Q T T，第 7 个是协议版本 4（3.1.1）
        assertEquals("MQTT", String(body, 2, 4, Charsets.UTF_8))
        assertEquals(4, body[6].toInt())
        val flags = body[7].toInt() and 0xFF
        assertEquals("clean session", 0x02, flags and 0x02)
        assertEquals("用户名位", 0x80, flags and 0x80)
        assertEquals("密码位", 0x40, flags and 0x40)
    }

    @Test
    fun `CONNECT 没账号时不置那两位`() {
        val (_, _, body) = split(MqttMath.connect("cid", "", ""))
        val flags = body[7].toInt() and 0xFF
        assertEquals(0x02, flags)
    }

    @Test
    fun `PUBLISH 的 QoS 位和报文号位置`() {
        val p0 = MqttMath.publish("t/x", "hi".toByteArray())
        assertEquals(MqttMath.PUBLISH, (p0[0].toInt() and 0xFF) shr 4)
        assertEquals(0, (p0[0].toInt() and 0x06) shr 1)

        val p1 = MqttMath.publish("t/x", "hi".toByteArray(), qos = 1, packetId = 0x1234)
        assertEquals(1, (p1[0].toInt() and 0x06) shr 1)
        val (_, _, body) = split(p1)
        // 00 03 t / x 之后是两个字节的报文号
        assertEquals(0x12, body[5].toInt() and 0xFF)
        assertEquals(0x34, body[6].toInt() and 0xFF)
        assertEquals("hi", String(body, 7, body.size - 7, Charsets.UTF_8))
    }

    @Test
    fun `SUBSCRIBE 每个主题各带一个 QoS 字节`() {
        val p = MqttMath.subscribe(7, listOf("a/#" to 1, "b/+" to 0))
        val (type, _, body) = split(p)
        assertEquals(MqttMath.SUBSCRIBE, type)
        assertEquals(0x02, p[0].toInt() and 0x0F)
        // 报文号 2 字节，然后 00 03 'a' '/' '#' 01，再 00 03 'b' '/' '+' 00
        assertEquals(7, ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF))
        val last = body.size - 1
        assertEquals("最后一个主题的 QoS", 0, body[last].toInt())
    }

    @Test
    fun `半条报文不算数，收全了才解出来`() {
        val full = MqttMath.publish("t", "hello".toByteArray())
        assertNull("只有固定头", MqttMath.parse(full.copyOfRange(0, 1)))
        assertNull("正文少一截", MqttMath.parse(full.copyOfRange(0, full.size - 1)))
        val p = MqttMath.parse(full)!!
        assertEquals(MqttMath.PUBLISH, p.type)
        assertEquals("total 必须正好是整条的长度", full.size, p.total)
        assertEquals("hello", MqttMath.readPublish(p)!!.second)
    }

    @Test
    fun `从 PUBLISH 里读出主题、正文和报文号`() {
        val p = MqttMath.parse(MqttMath.publish("home/灯", "开".toByteArray(), 1, 42))!!
        val (topic, msg, pid) = MqttMath.readPublish(p)!!
        assertEquals("home/灯", topic)
        assertEquals("开", msg)
        assertEquals(42, pid)

        val q0 = MqttMath.parse(MqttMath.publish("a", "b".toByteArray(), 0))!!
        assertEquals(0, MqttMath.readPublish(q0)!!.third)
    }

    @Test
    fun `连接被拒时说的是人话`() {
        assertTrue(MqttMath.connackReason(4).contains("用户名或密码"))
        assertTrue(MqttMath.connackReason(2).contains("客户端编号"))
        assertNotNull(MqttMath.connackReason(99))
    }

    @Test
    fun `SUBACK 里的 0x80 表示被拒`() {
        val ok = MqttMath.Packet(MqttMath.SUBACK, 0, byteArrayOf(0, 1, 1), 5)
        val bad = MqttMath.Packet(MqttMath.SUBACK, 0, byteArrayOf(0, 1, 0x80.toByte()), 5)
        assertFalse(MqttMath.subackRejected(ok))
        assertTrue(MqttMath.subackRejected(bad))
    }
}
