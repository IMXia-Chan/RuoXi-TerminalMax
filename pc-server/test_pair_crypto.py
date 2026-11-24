#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""pair_crypto 自测:二维码里真的没有钥匙,而且手机和电脑算出来的是同一把。

★ 这里每一条都对应一个**会出事的**情形:
  - HKDF 两边写得不一样 → 手机解不开,屏幕上只会看到「配对失败」,查不出来是哪一边;
  - 密钥派生没绑两边公钥 → 有人能把消息反射回去;
  - 密文没验完整性   → 改一个字节也照样解出来,配对就成了「谁都能塞一把钥匙进来」。
"""
import io
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

try:
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")
except Exception:
    pass

import pair_crypto as pc


def test_hkdf_rfc5869():
    """RFC 5869 Test Case 1 —— 逐字节钉死。Android 那边抄的是同一套,少一位就配对失败。"""
    ikm = bytes([0x0b] * 22)
    salt = bytes.fromhex("000102030405060708090a0b0c")
    info = bytes.fromhex("f0f1f2f3f4f5f6f7f8f9")
    okm = pc.hkdf_sha256(ikm, salt, info, 42)
    assert okm.hex() == (
        "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
        "34007208d5b887185865"
    ), okm.hex()
    print("1. HKDF-SHA256 对上 RFC 5869 向量 OK")


def test_payload_roundtrip():
    _, point = pc.new_ephemeral()
    text = pc.payload(point)
    assert text.startswith(pc.PREFIX)
    # 二维码里有 65 字节 = 88 个 base64 字符,扫得下,也抄得下
    assert len(text) == len(pc.PREFIX) + 87, len(text)
    assert pc.parse_payload(text) == point
    # 手抄时多打了空格 / 换行,不许因此失败
    # ★ 但**不能**去 "-":urlsafe base64 的字母表里就有它,剥掉等于改坏公钥
    messy = text[:8] + " " + text[8:20] + "\n" + text[20:] + " "
    assert pc.parse_payload(messy) == point
    # ★ 认不出来的东西必须当场拒绝,不能当成密钥用
    for bad in ("", "hello", "PAIR1:AAAA", "pair2:" + "A" * 87):
        try:
            pc.parse_payload(bad)
        except ValueError:
            pass
        else:
            raise AssertionError("不该认: %r" % bad)
    print("2. 二维码文本往返 + 杂物拒绝 OK")


def test_both_sides_same_key():
    """手机和电脑各自拿对方的公钥点算,必须算出**同一把**密钥。"""
    pc_priv, pc_point = pc.new_ephemeral()
    ph_priv, ph_point = pc.new_ephemeral()

    k_pc = pc.derive_key(pc_priv, ph_point, pc_point)
    k_ph = pc.derive_key(ph_priv, pc_point, ph_point)
    assert k_pc == k_ph and len(k_pc) == 32, (k_pc.hex(), k_ph.hex())
    print("3. 两端算出同一把密钥 OK")


def test_seed_travels_encrypted():
    pc_priv, pc_point = pc.new_ephemeral()
    ph_priv, ph_point = pc.new_ephemeral()
    k_pc = pc.derive_key(pc_priv, ph_point, pc_point)
    k_ph = pc.derive_key(ph_priv, pc_point, ph_point)

    seed = b"0123456789abcdef0123456789abcdef"
    blob = pc.seal(k_pc, seed)
    assert seed not in blob, "密文里出现了明文 —— AES-GCM 根本没起作用"
    assert pc.unseal(k_ph, blob) == seed
    print("4. 种子是加密过去的(密文里搜不到明文) OK")


def test_tamper_rejected():
    """改一个字节就必须解不开 —— 否则「拍下二维码」的人可以把手里的东西换成自己的。"""
    pc_priv, pc_point = pc.new_ephemeral()
    ph_priv, ph_point = pc.new_ephemeral()
    k_pc = pc.derive_key(pc_priv, ph_point, pc_point)
    k_ph = pc.derive_key(ph_priv, pc_point, ph_point)
    blob = bytearray(pc.seal(k_pc, b"0123456789abcdef0123456789abcdef"))
    for i in (0, 12, len(blob) - 1):
        bad = bytearray(blob)
        bad[i] ^= 0x01
        try:
            pc.unseal(k_ph, bytes(bad))
        except Exception:
            continue
        raise AssertionError("第 %d 字节被改了还能解开" % i)
    print("5. 密文被改一个字节就拒绝 OK")


def test_wrong_peer_gets_nothing():
    """拿别人的公钥点来算,算出来的密钥不一样 —— 所以第三方拍下二维码也没用。"""
    pc_priv, pc_point = pc.new_ephemeral()
    ph_priv, ph_point = pc.new_ephemeral()
    ev_priv, ev_point = pc.new_ephemeral()   # 旁边拍下二维码的那个人

    k_real = pc.derive_key(pc_priv, ph_point, pc_point)
    # 他只有 pc_point,自己造一对密钥,也只能算出「他以为的」那把
    k_evil = pc.derive_key(ev_priv, pc_point, ev_point)
    assert k_real != k_evil
    blob = pc.seal(k_real, b"0123456789abcdef0123456789abcdef")
    try:
        pc.unseal(k_evil, blob)
    except Exception:
        pass
    else:
        raise AssertionError("旁人用二维码里的公钥就把种子解开了 —— 整件事白做")
    print("6. 只拍到二维码的人解不开 OK")


if __name__ == "__main__":
    test_hkdf_rfc5869()
    test_payload_roundtrip()
    test_both_sides_same_key()
    test_seed_travels_encrypted()
    test_tamper_rejected()
    test_wrong_peer_gets_nothing()
    print("\n全部通过!")
