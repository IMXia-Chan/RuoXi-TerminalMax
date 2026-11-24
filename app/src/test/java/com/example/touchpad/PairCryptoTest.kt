package com.example.touchpad

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger

/**
 * 见 [PairCrypto]:这一段要是和电脑那份算得不一样,症状只有「扫码后配对失败」,
 * 而两边代码单看都没错 —— 所以这里钉的是**跨语言**的固定向量。
 */
class PairCryptoTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** 电脑端(pair_crypto.py)用同一组标量算出来的,原样搬过来。 */
    private val pcPoint = hex(
        "04471c3e758c4904285bba7e53118ed0f524adeb0757d25bd2f8e7b0d76dfa714c" +
            "dd520f7aca8a8b917acc37f51de8f0c9bbe3ad858382e702dc25a12d09f7a858"
    )
    private val phScalar = BigInteger("fedcba09876543210fedcba09876543210fedcba09876543210fedcba0987654", 16)
    private val phPoint = hex(
        "04b01b0028802990186d2d7d9d347a25db665b19dbdbeb00653174a986e53707b4" +
            "f022b4fac95dc030bda5328f96d94c549cba2893128432a536ee392e78dad519"
    )
    private val expectedKey = hex("745a79c6a99b7d170355bb4d87d8b986ee35c69faf2d7675070dbfd5da5f0237")
    private val nonce = hex("000102030405060708090a0b")
    private val seed = "0123456789abcdef0123456789abcdef".toByteArray(Charsets.US_ASCII)
    /** 电脑用 expectedKey + nonce 加密上面那个种子得到的密文(原样搬过来)。 */
    private val blob = hex(
        "000102030405060708090a0b461d8c60a0da248a2589417bc79c9c872bd41d5290" +
            "d470b331bbafb464c0576c83cb291f01a2abd195e7ca28a4f83175"
    )

    @Test
    fun hkdf对上RFC5869向量() {
        // 和电脑那边是同一份实现;两边都对着 RFC 的向量,就不会各错各的
        val okm = PairCrypto.hkdfSha256(
            ikm = ByteArray(22) { 0x0b },
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
            okm.joinToString("") { "%02x".format(it) }
        )
    }

    @Test
    fun 与电脑算出同一把密钥并能解开种子() {
        val priv = PairCrypto.privateKeyOf(phScalar)
        val key = PairCrypto.deriveKey(priv, pcPoint, phPoint)
        assertArrayEquals(expectedKey, key)
        assertArrayEquals(seed, PairCrypto.unseal(key, blob))
    }

    @Test
    fun 顺序反过来也必须算出同一把密钥() {
        // ★ 这条就是当初差点埋进去的 bug:电脑按 (电脑,手机) 拼指纹,手机按 (手机,电脑) 拼。
        //   两端都「看起来对」,配完就是连不上。
        assertArrayEquals(
            PairCrypto.transcript(pcPoint, phPoint),
            PairCrypto.transcript(phPoint, pcPoint)
        )
    }

    @Test
    fun 密文被改一个字节就解不开() {
        val key = PairCrypto.deriveKey(PairCrypto.privateKeyOf(phScalar), pcPoint, phPoint)
        for (i in intArrayOf(0, 12, blob.size - 1)) {
            val bad = blob.copyOf()
            bad[i] = (bad[i].toInt() xor 1).toByte()
            try {
                PairCrypto.unseal(key, bad)
                fail("第 $i 字节被改了还能解开")
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun 二维码文本只认带前缀的() {
        val p = PairCrypto.parsePayload(" " + PairCrypto.payload(pcPoint) + "\n")
        assertArrayEquals(pcPoint, p)
        // 老格式(32 位种子)必须被拒绝,而且要说人话 —— 不能当密钥硬用
        try {
            PairCrypto.parsePayload("0123456789abcdef0123456789abcdef")
            fail("一段 32 位 hex 被当成配对码了")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("配对二维码"))
        }
    }

    @Test
    fun 自己那对密钥算出来的点能还原成公钥() {
        val pair = PairCrypto.newKeyPair()
        val point = PairCrypto.publicPoint(pair)
        assertEquals(65, point.size)
        assertEquals(0x04, point[0].toInt())
        // 反解回去应该能正常参与协商(不抛异常),且和对方算出来的一致
        val other = PairCrypto.newKeyPair()
        val otherPoint = PairCrypto.publicPoint(other)
        val k1 = PairCrypto.deriveKey(pair.private, otherPoint, point)
        val k2 = PairCrypto.deriveKey(other.private, point, otherPoint)
        assertArrayEquals(k1, k2)
        assertNotEquals(0, k1.size)
    }
}
