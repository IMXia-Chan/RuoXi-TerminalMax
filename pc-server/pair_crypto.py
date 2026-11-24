# -*- coding: utf-8 -*-
"""配对用的密钥协商与加密 —— 二维码里**不再装钥匙**。

## 为什么

以前二维码里直接写着种子(32 位 hex)。谁把屏幕拍下来,谁就等于拿到了钥匙 ——
而钥匙是长期的,拍到一次就永久有效。

现在二维码里只放**一次性**的临时公钥(ECDH / P-256):

    电脑:临时私钥 P、临时的公钥点 Q  ──(QR 里只有 Q)──▶  手机
    手机:临时私钥 p、临时的公钥点 q  ──(KEY 行发 q)──▶   电脑
    两端各自算  S = ECDH(P, q) = ECDH(p, Q)
    K = HKDF-SHA256(S, 固定盐, 两个公钥点)  ⇒ 只有两端知道
    电脑把新种子用 K 做 AES-256-GCM 加密,发 `SEED <nonce||密文||tag>`

⇒ **拍到二维码 = 只拍到一半,解不开种子**。
   偷听整条连接也不行:没有 P 或 p,算不出 S。

一次性是这里的关键:每弹一次二维码就换一对新的临时密钥,
所以在两分钟窗口里录下来再重放也拿不到第二次的种子。

## 术语

- 这套叫 **ECDHE**(临时椭圆曲线 Diffie-Hellman),P-256 曲线;
- 密钥派生 **HKDF-SHA256**(RFC 5869);
- 加密 **AES-256-GCM**(AEAD,自带完整性校验)。
- 微信网页版 / WhatsApp Web / Signal Desktop 的扫码配对是同一类做法。

## 为什么是 P-256 而不是 Curve25519

手机端 minSdk=30,`KeyPairGenerator.getInstance("XDH")` 要 API 33。P-256 在
Android 和 Python 两边都是系统自带的,不引第三方库。
"""
import base64
import hashlib
import hmac

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

CURVE = ec.SECP256R1()

#: 二维码文本前缀。手机端靠它认出「这是配对码」,而不是一段莫名其妙的字符串。
PREFIX = "PAIR1:"

#: 协议版本的盐。**改协议就改这个**,新旧两端不会错配成半截握手的死局。
_SALT = b"touchpad-pair-v1"

#: AEAD 的附加数据:把用途钉死,这份密文只能当「配对种子」解,不能挪作他用。
AAD = b"touchpad-pair-seed"

#: 种子是 32 个 hex 字符,明文 32 字节;GCM 的 tag 16 字节;nonce 12 字节。
SEED_BYTES = 32
_NONCE_BYTES = 12


# ---------------------------------------------------------------------------
# HKDF-SHA256(RFC 5869)—— 两边各写一份,**必须逐字节一致**,见 test_pair_crypto.py
# 里直接钉了 RFC 的测试向量。
# ---------------------------------------------------------------------------
def hkdf_sha256(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    """extract-then-expand。够长、够单调,只做「把共享密钥拉成定长材料」这一件事。"""
    prk = hmac.new(salt or b"\x00" * hashlib.sha256().digest_size, ikm, hashlib.sha256).digest()
    out = b""
    block = b""
    counter = 1
    while len(out) < length:
        block = hmac.new(prk, block + info + bytes([counter]), hashlib.sha256).digest()
        out += block
        counter += 1
        if counter > 255:
            raise ValueError("HKDF 请求的长度超出上限")
    return out[:length]


# ---------------------------------------------------------------------------
# 编解码
# ---------------------------------------------------------------------------
def _b64e(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _b64d(text: str) -> bytes:
    pad = "=" * (-len(text) % 4)
    return base64.urlsafe_b64decode(text + pad)


def encode(blob: bytes) -> str:
    """二进制 → 一行文本(走 socket / 二维码时用)。"""
    return _b64e(blob)


def decode(text: str) -> bytes:
    return _b64d(text.strip())


def payload(point: bytes) -> str:
    """二维码里那串文本:[PREFIX] + 公钥点(65 字节,0x04||X||Y)。"""
    return PREFIX + _b64e(point)


def decode_point(text: str) -> bytes:
    """裸公钥点(手机 KEY 行里那一段)→ 65 字节。形状不对抛 ValueError。"""
    point = _b64d(text.strip())
    if len(point) != 65 or point[0] != 0x04:
        raise ValueError("公钥长度不对")
    return point


def parse_payload(text: str) -> bytes:
    """手机抄/扫回来的那串文本 → 公钥点。形状不对就抛 ValueError。"""
    if not isinstance(text, str):
        raise ValueError("不是一个字符串")
    # ★ 只去掉空白(手抄时容易多敲空格 / 换行)。
    #   **不能去 "-"**:urlsafe base64 的字母表里就有 `-`,剥掉会把公钥改坏,
    #   而报错会是「公钥长度不对」—— 一个指向错误方向的线索。
    text = "".join(text.split())
    if not text.upper().startswith(PREFIX):
        raise ValueError("不是配对二维码")
    point = _b64d(text[len(PREFIX):])
    if len(point) != 65 or point[0] != 0x04:
        raise ValueError("公钥长度不对")
    return point


# ---------------------------------------------------------------------------
# 协商
# ---------------------------------------------------------------------------
def new_ephemeral():
    """一对**一次性**的临时密钥。返回 (私钥对象, 公钥点 65 字节)。"""
    priv = ec.generate_private_key(CURVE)
    point = priv.public_key().public_bytes(
        serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint
    )
    return priv, point


def load_peer_point(point: bytes):
    return ec.EllipticCurvePublicKey.from_encoded_point(CURVE, point)


def transcript(my_point: bytes, peer_point: bytes) -> bytes:
    """两个公钥点拼成「这次握手」的指纹。

    ★ 顺序必须**与谁是谁无关**:电脑是 (我的=电脑, 对方=手机),手机是 (我的=手机, 对方=电脑),
      直接按参数顺序拼会得到两段不同的 info,两端算出来的密钥就不一样
      —— 症状是「扫码后配对失败」,而两边代码看起来都没错。
      所以按字节序排一下,两端得到同一段。
    """
    a, b = sorted([my_point, peer_point])
    return PREFIX.encode("ascii") + a + b


def derive_key(priv, peer_point: bytes, my_point: bytes) -> bytes:
    """ECDH → HKDF。两个公钥点都进 info,防止有人把消息反射回来当另一半。"""
    peer = load_peer_point(peer_point)
    shared = priv.exchange(ec.ECDH(), peer)
    return hkdf_sha256(shared, _SALT, transcript(my_point, peer_point), 32)


def seal(key: bytes, plaintext: bytes, nonce: bytes = None) -> bytes:
    """AES-256-GCM 加密,返回 nonce||密文||tag。"""
    import os
    nonce = nonce or os.urandom(_NONCE_BYTES)
    return nonce + AESGCM(key).encrypt(nonce, plaintext, AAD)


def unseal(key: bytes, blob: bytes) -> bytes:
    if len(blob) < _NONCE_BYTES + 16:
        raise ValueError("密文太短")
    return AESGCM(key).decrypt(blob[:_NONCE_BYTES], blob[_NONCE_BYTES:], AAD)
