package com.example.touchpad

import android.bluetooth.BluetoothGattCharacteristic
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 蓝牙那几个「会错但不崩」的地方。
 *
 * ★ 钉的是**编号认错**和**字节认错**。这两种错的症状都不是崩溃，
 *   而是「连上了、找不到服务」和「写进去它没反应」—— 站在面板前
 *   完全看不出是哪边的问题。
 */
class BleMathTest {

    @Test
    fun `四位短号展开成标准 UUID`() {
        val u = BleMath.uuidOf("180f")!!
        assertEquals("0000180f-0000-1000-8000-00805f9b34fb", u.toString())
        assertEquals("短号写回去还是四位", "180f", BleMath.name(u))
    }

    @Test
    fun `八位短号展开成标准 UUID`() {
        val u = BleMath.uuidOf("12345678")!!
        assertEquals("12345678-0000-1000-8000-00805f9b34fb", u.toString())
    }

    @Test
    fun `没有横线的 32 位也能认`() {
        val u = BleMath.uuidOf("12345678123456781234567812345678")!!
        assertEquals("12345678-1234-5678-1234-567812345678", u.toString())
    }

    @Test
    fun `自定义 UUID 原样保留`() {
        val u = BleMath.uuidOf("6e400001-b5a3-f393-e0a9-e50e24dcca9e")!!
        assertEquals("6e400001-b5a3-f393-e0a9-e50e24dcca9e", u.toString())
        assertEquals("不是标准短号，别缩成四位数", "6e400001-b5a3-f393-e0a9-e50e24dcca9e", BleMath.name(u))
    }

    @Test
    fun `认不出来的编号返回空`() {
        assertNull(BleMath.uuidOf(""))
        assertNull(BleMath.uuidOf("不是编号"))
        assertNull("位数不对", BleMath.uuidOf("180"))
        assertNull("有三段但不是 UUID", BleMath.uuidOf("1234-5678"))
    }

    @Test
    fun `十六进制字节三种写法都认`() {
        val want = byteArrayOf(0x01, 0x0A, 0xFF.toByte())
        assertArrayEquals(want, BleMath.hexToBytes("01 0a ff"))
        assertArrayEquals(want, BleMath.hexToBytes("0x01,0x0A,0xFF"))
        assertArrayEquals(want, BleMath.hexToBytes("010AFF"))
        assertArrayEquals(byteArrayOf(), BleMath.hexToBytes("  "))
    }

    @Test
    fun `不是合法十六进制就返回空，不许猜`() {
        assertNull("奇数位", BleMath.hexToBytes("010"))
        assertNull("有非法字符", BleMath.hexToBytes("0g"))
        assertNull("半截", BleMath.hexToBytes("zz"))
    }

    @Test
    fun `字节写回去是两位一组`() {
        assertEquals("01 0a ff", BleMath.bytesToHex(byteArrayOf(0x01, 0x0A, 0xFF.toByte())))
    }

    @Test
    fun `能读成文字就一起给，不能就只给十六进制`() {
        val text = BleMath.describe("BT-TH-01".toByteArray())
        assertTrue("文字部分", text.contains("BT-TH-01"))
        assertTrue("十六进制也留着", text.contains("42 54 2d"))

        // ★ 这串里有一个 0x01：不能因为它后面有个换行就被当成文字。
        val bin = BleMath.describe(byteArrayOf(0x01, 0x00, 0x0A))
        assertEquals("01 00 0a", bin)

        assertEquals("（空）", BleMath.describe(byteArrayOf()))
    }

    @Test
    fun `属性位翻成人话`() {
        val readWrite = BluetoothGattCharacteristic.PROPERTY_READ or
            BluetoothGattCharacteristic.PROPERTY_WRITE
        assertEquals("读、写", BleMath.props(readWrite))
        val notify = BluetoothGattCharacteristic.PROPERTY_NOTIFY
        assertEquals("通知", BleMath.props(notify))
        val none = BluetoothGattCharacteristic.PROPERTY_BROADCAST
        assertEquals("无", BleMath.props(none))
    }

    @Test
    fun `GATT 状态码要说成能照着做的话`() {
        assertNotNull(BleMath.statusText(133))
        assertTrue("133 是最常见的那个，要指出几个可能的原因", BleMath.statusText(133).contains("别的手机"))
        assertTrue(BleMath.statusText(2).contains("读"))
        assertTrue("没见过的码也要给出来", BleMath.statusText(999).contains("999"))
    }
}
