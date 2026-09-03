#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""server.py 协议自测(不弹窗、不真动鼠标:靠 monkeypatch 隔离)。"""
import base64
import os
import socket
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import server


def last_event(events, kind):
    for e in reversed(events):
        if e["kind"] == kind:
            return e
    raise AssertionError(f"缺少 {kind} 事件")


def readline(f):
    return f.readline().strip()


def connect(port):
    s = __import__("socket").create_connection(("127.0.0.1", port), timeout=3)
    f = s.makefile("rw", encoding="utf-8", newline="\n")
    return f, s


# 1. DPAPI 往返
def test_dpapi():
    data = b"test-secret-1234567890"
    enc = server.dpapi_encrypt(data)
    dec = server.dpapi_decrypt(enc)
    assert dec == data, "DPAPI roundtrip failed"
    print("1. DPAPI 加密往返 OK")


# 2. TOTP + 锁定
def test_totp_lockout():
    secret = server.generate_secret()
    code = server.totp_code(secret)
    assert server.verify_totp(secret, code), "TOTP verify failed"
    # 找一个必然错误的码
    wrong = None
    for attempt in range(10000):
        w = f"{attempt:06d}"
        if not server.verify_totp(secret, w):
            wrong = w
            break
    assert wrong is not None
    server._fail_count = 0
    server._lock_count = 0
    server._lock_until = 0
    for i in range(3):
        status, info = server.check_totp(secret, wrong)
        if i < 2:
            assert status == "bad", f"第 {i + 1} 次应 bad, got {status}"
        else:
            assert status == "locked", f"第 3 次应 locked, got {status}"
    status, info = server.check_totp(secret, code)  # 锁定中,即便正确也拒绝
    assert status == "locked", f"expected locked, got {status}"
    print("2. TOTP + 3 次错锁定 OK")


# 3. 完整协议:首次配对 -> TOTP -> 控制模式;已配对;恢复码;旧恢复码作废;空闲超时
def test_full_flow():
    events = []
    server._emit = lambda kind, **kw: events.append({"kind": kind, **kw})  # 隔离,不弹窗不打印
    if os.path.exists(server.SECRET_PATH):
        os.remove(server.SECRET_PATH)
    server.init_store()
    server._fail_count = 0
    server._lock_count = 0
    server._lock_until = 0

    port = 19527
    threading.Thread(target=server.serve, args=(port,), daemon=True).start()
    time.sleep(0.3)

    # ---- 首次配对 ----
    f, s = connect(port)
    assert readline(f) == "PIN_REQUIRED"
    pin = last_event(events, "pairing")["pin"]
    f.write(f"PIN {pin}\n"); f.flush()
    assert readline(f) == "SETUP"
    secret = last_event(events, "setup")["secret"]
    recovery = last_event(events, "setup")["recovery"]
    assert len(secret) == 32 and len(recovery) == 8, (secret, recovery)
    f.write(f"TOTP {server.totp_code(secret)}\n"); f.flush()
    assert readline(f) == "OK"
    assert server.get_secret() == secret
    # 控制模式:发一条合法 M 帧
    sk = server.derive_session_key(pin, secret)
    sig = server.sign_command(sk, "M 1 5 5 0 0")
    f.write(f"M 1 5 5 0 0 {sig}\n"); f.flush()
    time.sleep(0.2)
    f.close(); s.close()
    time.sleep(0.3)
    print("3a. 首次配对 + 控制模式 OK")

    # ---- 已配对 TOTP 认证 ----
    f, s = connect(port)
    assert readline(f) == "PIN_REQUIRED"
    pin = last_event(events, "pairing")["pin"]
    f.write(f"PIN {pin}\n"); f.flush()
    assert readline(f) == "TOTP_REQUIRED"
    f.write(f"TOTP {server.totp_code(secret)}\n"); f.flush()
    assert readline(f) == "OK"
    f.close(); s.close()
    time.sleep(0.3)
    print("3b. 已配对 TOTP 认证 OK")

    # ---- 恢复码重新配对(模拟手机丢失,本地无种子) ----
    f, s = connect(port)
    assert readline(f) == "PIN_REQUIRED"
    pin = last_event(events, "pairing")["pin"]
    f.write(f"PIN {pin}\n"); f.flush()
    assert readline(f) == "TOTP_REQUIRED"
    f.write(f"RECOVER {recovery}\n"); f.flush()  # 用一次性恢复码
    assert readline(f) == "SETUP"
    new_secret = last_event(events, "setup")["secret"]
    new_recovery = last_event(events, "setup")["recovery"]
    assert new_secret != secret and new_recovery != recovery
    f.write(f"TOTP {server.totp_code(new_secret)}\n"); f.flush()
    assert readline(f) == "OK"
    assert server.get_secret() == new_secret
    f.close(); s.close()
    time.sleep(0.3)
    print("3c. 恢复码重新配对 OK")

    # ---- 旧恢复码应已作废 ----
    f, s = connect(port)
    assert readline(f) == "PIN_REQUIRED"
    pin = last_event(events, "pairing")["pin"]
    f.write(f"PIN {pin}\n"); f.flush()
    assert readline(f) == "TOTP_REQUIRED"
    f.write(f"RECOVER {recovery}\n"); f.flush()  # 旧恢复码
    assert readline(f) == "ERR bad recovery"
    f.close(); s.close()
    time.sleep(0.3)
    print("3d. 旧恢复码作废 OK")

    # ---- 空闲超时(把超时降到 1 秒测) ----
    server.IDLE_TIMEOUT = 1
    f, s = connect(port)
    assert readline(f) == "PIN_REQUIRED"
    pin = last_event(events, "pairing")["pin"]
    f.write(f"PIN {pin}\n"); f.flush()
    assert readline(f) == "TOTP_REQUIRED"
    f.write(f"TOTP {server.totp_code(new_secret)}\n"); f.flush()
    assert readline(f) == "OK"
    # 1 秒不操作,服务器应断开
    assert f.readline() == "" or f.readline() == ""
    f.close(); s.close()
    print("3e. 空闲超时自动断开 OK")

    if os.path.exists(server.SECRET_PATH):
        os.remove(server.SECRET_PATH)
    print("全部通过!")


# 4. 屏幕镜像 / 绝对点击 / 文本输入(用假帧,不真抓屏)
def test_streaming():
    events = []
    server._emit = lambda kind, **kw: events.append({"kind": kind, **kw})
    server.capture_frame = lambda: (b"\xff\xd8FAKEJPEG\xff\xd9", 1920, 1080)
    server.STREAM_FPS = 50
    if os.path.exists(server.SECRET_PATH):
        os.remove(server.SECRET_PATH)
    server.init_store()
    server._fail_count = 0
    server._lock_count = 0
    server._lock_until = 0

    port = 19528
    threading.Thread(target=server.serve, args=(port,), daemon=True).start()
    time.sleep(0.3)

    s = socket.create_connection(("127.0.0.1", port), timeout=5)
    rb = s.makefile("rb")

    def rd_line():
        return rb.readline().decode("utf-8").strip()

    def send(txt):
        s.sendall((txt + "\n").encode("utf-8"))

    # 首次配对
    assert rd_line() == "PIN_REQUIRED"
    pin = last_event(events, "pairing")["pin"]
    send(f"PIN {pin}")
    assert rd_line() == "SETUP"
    secret = last_event(events, "setup")["secret"]
    send(f"TOTP {server.totp_code(secret)}")
    assert rd_line() == "OK"
    sk = server.derive_session_key(pin, secret)

    # 开启屏幕镜像,收一帧
    send(f"V 1 1 {server.sign_command(sk, 'V 1 1')}")
    header = rd_line()
    assert header.startswith("FRAME "), header
    _, length, w, h = header.split()
    length, w, h = int(length), int(w), int(h)
    assert (w, h) == (1920, 1080), (w, h)
    assert rb.read(length) == b"\xff\xd8FAKEJPEG\xff\xd9"

    # 绝对点击
    send(f"A 2 100 200 1 {server.sign_command(sk, 'A 2 100 200 1')}")
    # 文本输入(中文)
    b64 = base64.b64encode("你好".encode()).decode()
    send(f"T 3 {b64} {server.sign_command(sk, 'T 3 ' + b64)}")
    # 关闭镜像
    send(f"V 4 0 {server.sign_command(sk, 'V 4 0')}")
    time.sleep(0.3)

    rb.close()
    s.close()
    if os.path.exists(server.SECRET_PATH):
        os.remove(server.SECRET_PATH)
    print("4. 屏幕镜像/绝对点击/文本输入 OK")


if __name__ == "__main__":
    test_dpapi()
    test_totp_lockout()
    test_full_flow()
    test_streaming()
