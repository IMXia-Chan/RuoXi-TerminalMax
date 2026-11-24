package com.example.touchpad

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 配对用的密钥协商 —— 电脑那份实现在 `pc-server/pair_crypto.py`,**两边必须逐字节一致**。
 *
 * ## 这一步解决什么问题
 *
 * 以前二维码里直接写着种子。谁能拍到那块屏幕,谁就拿到了钥匙,而且永久有效。
 * 现在二维码里只有**一次性公钥**:
 *
 * ```
 * 电脑:临时私钥 P、公钥点 Q   ──(二维码里只有 Q)──▶  手机
 * 手机:临时私钥 p、公钥点 q   ──(KEY 行发 q)─────▶  电脑
 * 两端各自算 S = ECDH(P,q) = ECDH(p,Q)
 * K = HKDF-SHA256(S, 固定盐, 两个公钥点)   ⇒ 只有这两台知道
 * 电脑把新种子用 K 做 AES-256-GCM 加密,回 `SEED <nonce||密文||tag>`
 * ```
 *
 * ⇒ 拍到二维码只拍到一半,解不开;偷听整条连接也解不开。
 *
 * ## 为什么不是 Curve25519
 *
 * 手机 minSdk=30,`XDH` 要 API 33。P-256 在 Android 和 Python 两边都是系统自带的。
 *
 * ## 为什么这段代码里有几处「看起来多余」的写法
 *
 * - [transcript] 按字节序排序:电脑传 (我的, 对方),手机传 (我的, 对方),
 *   直接按参数拼会得到两段不同的 info,两端密钥就不一样 —— 症状是「扫码后配对失败」,
 *   而两边代码单看都没错。**这条已经在测试里钉死了。**
 * - [fixed32]:BigInteger 转字节会吃掉前导零(或者多出一个 0x00),公钥点长度就对不上。
 */
internal object PairCrypto {

    /** 二维码文本前缀。认不出这个前缀就当「这不是配对码」,不能当密钥用。 */
    const val PREFIX = "PAIR1:"

    private val SALT = "touchpad-pair-v1".toByteArray(Charsets.US_ASCII)
    private val AAD = "touchpad-pair-seed".toByteArray(Charsets.US_ASCII)

    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val POINT_BYTES = 65          // 0x04 || X(32) || Y(32)
    private const val CURVE = "secp256r1"

    private fun spec(): ECParameterSpec =
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec(CURVE))
            getParameterSpec(ECParameterSpec::class.java)
        }

    // ---- 密钥对 ----------------------------------------------------------

    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec(CURVE))
            generateKeyPair()
        }

    /** 自己的公钥 → 65 字节非压缩点。 */
    fun publicPoint(pair: KeyPair): ByteArray {
        val w = (pair.public as java.security.interfaces.ECPublicKey).w
        return byteArrayOf(0x04) + fixed32(w.affineX) + fixed32(w.affineY)
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        // toByteArray 可能带一个前导 0x00(表示正整数),也可能短于 32(前导零被吃掉)
        val src = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
        System.arraycopy(src, 0, out, 32 - src.size, src.size)
        return out
    }

    private fun publicKeyOf(point: ByteArray): PublicKey {
        require(point.size == POINT_BYTES && point[0] == 0x04.toByte()) { "公钥长度不对" }
        val x = BigInteger(1, point.copyOfRange(1, 33))
        val y = BigInteger(1, point.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC")
            .generatePublic(ECPublicKeySpec(ECPoint(x, y), spec()))
    }

    /** 只给测试用:拿一个标量当私钥(为了和电脑那边产生的那组固定向量对齐)。 */
    internal fun privateKeyOf(scalar: BigInteger): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(scalar, spec()))

    // ---- 编解码 ----------------------------------------------------------

    /** urlsafe base64,不带 `=` 填充(二维码更短,手抄也不会因为数的个数不对而失败)。 */
    fun encode(raw: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(raw)

    fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trim())

    fun payload(point: ByteArray): String = PREFIX + encode(point)

    /**
     * 扫码 / 手抄回来的那串文本 → 电脑的临时公钥点。
     *
     * ★ **只去掉空白**。不能顺手去掉 `-`:urlsafe base64 的字母表里就有 `-`,
     *   剥掉等于把公钥改坏,而报错会指向「长度不对」—— 一个骗人的线索。
     */
    fun parsePayload(text: String): ByteArray {
        val cleaned = text.filterNot { it.isWhitespace() }
        if (!cleaned.startsWith(PREFIX, ignoreCase = true)) {
            throw IllegalArgumentException("这不是配对二维码,请扫电脑上新弹出的那张")
        }
        val point = decode(cleaned.substring(PREFIX.length))
        if (point.size != POINT_BYTES || point[0] != 0x04.toByte()) {
            throw IllegalArgumentException("二维码内容不完整,请重新扫码")
        }
        return point
    }

    // ---- 协商 ------------------------------------------------------------

    /**
     * 两个公钥点拼成「这次握手」的指纹。
     *
     * ★ 必须**与谁是谁无关**(按字节序排),否则电脑按 (电脑, 手机) 拼、手机按 (手机, 电脑) 拼,
     *   两端算出来的密钥不一样。
     */
    fun transcript(a: ByteArray, b: ByteArray): ByteArray {
        val (x, y) = if (compareUnsigned(a, b) <= 0) a to b else b to a
        return PREFIX.toByteArray(Charsets.US_ASCII) + x + y
    }

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    /** ECDH → HKDF-SHA256,得到 32 字节的 AES 密钥。 */
    fun deriveKey(priv: PrivateKey, peerPoint: ByteArray, myPoint: ByteArray): ByteArray {
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(priv)
            doPhase(publicKeyOf(peerPoint), true)
            generateSecret()
        }
        return hkdfSha256(shared, SALT, transcript(myPoint, peerPoint), 32)
    }

    /** HKDF-SHA256(RFC 5869)。电脑那边有一份等价的实现,测试里用 RFC 的向量对过。 */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var filled = 0
        var block = ByteArray(0)
        var counter = 1
        while (filled < length) {
            require(counter <= 255) { "HKDF 请求的长度超出上限" }
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()
            val n = minOf(block.size, length - filled)
            System.arraycopy(block, 0, out, filled, n)
            filled += n
            counter++
        }
        return out
    }

    // ---- 加解密 ----------------------------------------------------------

    /** 电脑 → 手机:把种子解出来。blob = nonce || 密文 || tag。 */
    fun unseal(key: ByteArray, blob: ByteArray): ByteArray {
        require(blob.size > NONCE_BYTES + TAG_BITS / 8) { "密文太短" }
        val nonce = blob.copyOfRange(0, NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(AAD)
        return cipher.doFinal(blob, NONCE_BYTES, blob.size - NONCE_BYTES)
    }

    /** 只给测试用(手机上从不加密,只解密)。 */
    fun seal(key: ByteArray, plaintext: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(AAD)
        return nonce + cipher.doFinal(plaintext)
    }
}
