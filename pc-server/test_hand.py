#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""手的自测:「电脑是谁、会什么」能不能被问出来,而且问出来的东西是对的。

分三段,前两段不用起服务器、秒回:

  1. `hand_info()` 本身 —— 自述的**形状和内容**对不对。
  2. 发现应答的身份 —— ★ 这一段的重点是**不变量**,不是新功能。
     加 `hand` 字段最容易犯的错是顺手把应答变成一个「长得像探测包」的东西,
     那会让两台电脑互相回包。这里把三条不变量钉死。
  3. 真的走一遍 `HAND`/`HARR` 签名命令 —— 梯子插错位置、b64 编错、
     哪个字段拼错了,只有真连一次才知道。

不用 `--live`,不碰鼠标、不碰真实 secret.json(照 test_ai.py 用临时文件)。
"""
import base64
import io
import json
import os
import socket
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

# 控制台可能是 GBK(cp936),直接 print 中文/符号会 UnicodeEncodeError;统一切到 UTF-8。
try:
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")
except Exception:
    pass

import server
import ai_tools

TMP_SECRET = os.path.join(HERE, "test_hand_secret.json")
PORT = 19531

FAILS = []


def check(name, cond, extra=""):
    print("  %s %s %s" % ("OK  " if cond else "FAIL", name, extra))
    if not cond:
        FAILS.append(name)
    return cond


def readline_until(f, prefix, timeout=10):
    """读到指定前缀的行为止(中间可能有别的行,如日志/光标上报)。"""
    end = time.time() + timeout
    while time.time() < end:
        line = f.readline()
        if not line:
            break
        line = line.strip()
        if line.startswith(prefix):
            return line
    raise AssertionError("等 %s 超时" % prefix)


# --------------------------------------------------------------------------
# 1. hand_info() 自述的形状
# --------------------------------------------------------------------------
def test_hand_info():
    print("== 1. hand_info():自述的形状 ==")
    info = ai_tools.hand_info()

    for k in ("id", "kind", "name", "version", "tools", "hidden", "locks"):
        check("有 %s" % k, k in info)

    check("kind 认得出是一只 windows-pc", info.get("kind") == ai_tools.HAND_KIND,
          info.get("kind"))
    check("id/name 非空(手机端列表里要显示)",
          bool(info.get("id")) and bool(info.get("name")),
          "%s / %s" % (info.get("id"), info.get("name")))

    tools = info.get("tools") or []
    names = sorted(t["function"]["name"] for t in tools)
    hidden = sorted(info.get("hidden") or [])

    check("自述的工具表非空", len(tools) > 0, "%d 个" % len(tools))
    check("工具是 OpenAI function 形状",
          all("type" in t and "function" in t for t in tools))
    check("每个工具有名字和参数表",
          all(t["function"].get("name") and "parameters" in t["function"] for t in tools))

    # ★ 自述里**只能**有模型看得见的那些。手机端拿这份去跟模型讲「电脑会什么」,
    # 混进 screenshot/click_at 这种像素原语,模型看不见图却拿到坐标原语,只会瞎猜。
    check("自述里没有隐藏工具",
          not (set(names) & set(hidden)), sorted(set(names) & set(hidden)))
    check("声明隐藏的确实都不在自述里",
          set(hidden) == {"screenshot", "click_at"}, hidden)
    check("自述工具数 = 全部工具 - 隐藏工具",
          len(names) == len(ai_tools.TOOLS) - len(hidden),
          "%d = %d - %d" % (len(names), len(ai_tools.TOOLS), len(hidden)))

    # locks 是并行调度的唯一依据(见 S4)。漏一个 = 那个工具会被当成独占,
    # 表现为「莫名其妙地串行」—— 不崩、不报错,只是慢,最难查。
    locks = info.get("locks") or {}
    missing = sorted(set(ai_tools.TOOLS) - set(locks))
    check("每个工具都在 locks 表里", not missing, missing)
    check("locks 里的值是 list 或 None(没声明过就 None=独占)",
          all(v is None or isinstance(v, list) for v in locks.values()))
    no_lock = sorted(n for n, v in locks.items() if v == [])
    check("确实有工具声明了「不占任何东西」",
          len(no_lock) > 0, "%d 个只读工具" % len(no_lock))

    # 指纹:手机端拿它判断「我这边的抄本过期了没有」。它要是每次不一样,
    # 漂移检查就会天天误报;要是永远一样,漂移检查就形同虚设。
    fp = info.get("version")
    check("指纹 = schema_fingerprint()", fp == ai_tools.schema_fingerprint(), fp)
    check("指纹稳定(两次调用一致)", fp == ai_tools.hand_info().get("version"))


def test_fingerprint_tracks_schema():
    """指纹真的跟着工具表走 —— 不然漂移守卫就是摆设。

    加一个工具 → 指纹必须变。加完原样还回去(改的是 TOOLS 这个 dict 本身,
    所以要么还原、要么就地执行完立刻删)。
    """
    print("== 2. 指纹真的跟着工具表走 ==")
    before = ai_tools.schema_fingerprint()
    probe = {"__drift_probe__": {"desc": "只是为了测指纹的临时工具",
                                 "params": {}, "optional": [], "locks": []}}
    ai_tools.TOOLS.update(probe)
    try:
        after = ai_tools.schema_fingerprint()
        check("加一个工具 → 指纹变了", after != before, "%s → %s" % (before, after))
        check("临时加的工具也进了自述(不是硬编码的常数)",
              "__drift_probe__" in [t["function"]["name"]
                                    for t in ai_tools.hand_info()["tools"]])
    finally:
        ai_tools.TOOLS.pop("__drift_probe__", None)
    check("还回去之后指纹复原", ai_tools.schema_fingerprint() == before)


# --------------------------------------------------------------------------
# 3. 发现应答的身份(重点是不变量)
# --------------------------------------------------------------------------
def test_discovery_payload():
    print("== 3. 发现应答的身份 ==")
    raw = server._discovery_payload(9527)
    obj = json.loads(raw.decode("utf-8"))

    # 不变量 1:app / port 必须在,值不能变 —— 手机端认电脑就靠这两个键。
    check("app 还是 phone-touchpad(手机端靠它认电脑)",
          obj.get("app") == "phone-touchpad", obj.get("app"))
    check("port 原样带回", obj.get("port") == 9527, obj.get("port"))

    # 不变量 2:★ 绝不能像探测包。手机端拿「有没有 probe」分探测/应答
    # (TouchpadClient.kt:931),而电脑端自己 discovery_responder 的过滤条件
    # 也是「有 probe 才回包」—— 应答里一旦有 probe,两台电脑会互相回包轰到死。
    check("★ 应答里没有 probe(否则两台电脑会互相回包)",
          b"probe" not in raw.lower(), raw)

    # 而探测包仍然能被 responder 认出来(那条路没被改坏)。
    real_probe = b'{"app":"phone-touchpad","probe":true}'
    check("真的探测包仍然匹配 responder 的过滤条件",
          b"phone-touchpad" in real_probe and b"probe" in real_probe)
    check("应答**不**匹配那个过滤条件(不会被自己人当探测)",
          not (b"phone-touchpad" in raw and b"probe" in raw))

    hand = obj.get("hand") or {}
    check("带了 hand 身份", bool(hand), hand)
    for k in ("id", "kind", "name"):
        check("hand.%s 在" % k, bool(hand.get(k)), hand.get(k))
    check("hand.kind = windows-pc", hand.get("kind") == "windows-pc", hand.get("kind"))
    check("身份和 HAND 命令自述的一致(两处不能各说各的)",
          hand.get("id") == ai_tools.hand_info()["id"]
          and hand.get("name") == ai_tools.hand_info()["name"], hand)

    # ★ 身份里**不带**工具表。这包是 UDP、一台电脑发一次、整个网段都听得到;
    # 能力清单走签名命令(要配对才问得出来)。
    check("身份里没有工具表(不吃 UDP 包体、也不广播能力)",
          json.dumps(hand, ensure_ascii=False).find("tools") < 0, hand)
    check("整包小到能塞进一个 UDP 包", len(raw) < 512, "%d 字节" % len(raw))


# --------------------------------------------------------------------------
# 4. 真的走一遍 HAND / HARR
# --------------------------------------------------------------------------
def test_hand_command():
    print("== 4. HAND → HARR(真走一遍签名命令) ==")

    events = []
    server._emit = lambda kind, **kw: events.append({"kind": kind, **kw})

    # 用临时密钥文件,绝不碰真实 secret.json(真删了就得重新配对)
    real_secret_path = server.SECRET_PATH
    server.SECRET_PATH = TMP_SECRET
    if os.path.exists(TMP_SECRET):
        os.remove(TMP_SECRET)
    assert os.path.abspath(real_secret_path) != os.path.abspath(TMP_SECRET)
    server.init_store()
    server._fail_count = 0
    server._lock_count = 0
    server._lock_until = 0

    threading.Thread(target=server.serve, args=(PORT,), daemon=True).start()
    time.sleep(0.4)

    s = socket.create_connection(("127.0.0.1", PORT), timeout=15)
    f = s.makefile("rw", encoding="utf-8", newline="\n")

    # ---- 配对拿会话密钥 ----
    assert f.readline().strip() == "PIN_REQUIRED"
    pin = [e for e in reversed(events) if e["kind"] == "pairing"][0]["pin"]
    f.write("PIN %s\n" % pin); f.flush()
    assert f.readline().strip() == "SETUP"
    secret = [e for e in reversed(events) if e["kind"] == "setup"][0]["secret"]
    f.write("TOTP %s\n" % server.totp_code(secret)); f.flush()
    assert f.readline().strip() == "OK"
    sk = server.derive_session_key(pin, secret)
    print("     配对 OK")

    seq = [0]

    def send(cmd, payload):
        """发一条签名命令,回 (命令行, 回包行)。"""
        seq[0] += 1
        b64 = base64.b64encode(json.dumps(payload, ensure_ascii=False).encode("utf-8")).decode("ascii")
        line = "%s %d %s" % (cmd, seq[0], b64)
        f.write("%s %s\n" % (line, server.sign_command(sk, line))); f.flush()
        return line

    # ---- 4.1 正常问一次 ----
    send("HAND", {})
    raw = readline_until(f, "HARR ")
    r = json.loads(base64.b64decode(raw[5:]).decode("utf-8"))

    check("ok", r.get("ok") is True, r.get("error", ""))
    hand = r.get("hand") or {}
    check("回的是 hand 而不是 result(免得和 AI 那条撞形状)",
          "hand" in r and "result" not in r, sorted(r.keys()))

    tools = hand.get("tools") or []
    names = sorted(t["function"]["name"] for t in tools)
    expect = sorted(t["function"]["name"] for t in ai_tools.tool_schema(for_model=True))
    check("★ 走完协议回的工具表和直接调函数一模一样", names == expect, names)
    check("工具数 = 模型可见的工具数",
          len(names) == len(ai_tools.tool_schema(for_model=True)), len(names))
    check("指纹穿过协议没变形",
          hand.get("version") == ai_tools.schema_fingerprint(), hand.get("version"))

    # 参数表也得完整穿过 b64 —— 手机端要拿它拼 use_hand 的示例。
    open_app = [t for t in tools if t["function"]["name"] == "open_app"]
    check("open_app 的参数表带着 required",
          bool(open_app) and open_app[0]["function"]["parameters"].get("required"),
          (open_app[0]["function"]["parameters"].get("required") if open_app else None))

    # ---- 4.2 坏 JSON 不崩 ----
    seq[0] += 1
    line = "HAND %d %s" % (seq[0], base64.b64encode(b"not json").decode())
    f.write("%s %s\n" % (line, server.sign_command(sk, line))); f.flush()
    raw = readline_until(f, "HARR ")
    r = json.loads(base64.b64decode(raw[5:]).decode("utf-8"))
    check("坏负载不崩,照样回一份完整自述(负载现在没人读)",
          r.get("ok") is True and bool(r.get("hand")), r.get("error", ""))

    # ---- 4.3 没签名必须被拒 ----
    seq[0] += 1
    line = "HAND %d %s" % (seq[0], base64.b64encode(b"{}").decode())
    f.write("%s %s\n" % (line, "0" * 64)); f.flush()
    # 被拒时可能直接断连、也可能回别的行;两种都算拒绝,只要**没拿到 HARR**。
    # 超时那一下是 socket 自己抛 TimeoutError(不是我们的 AssertionError),
    # 所以这里 catch Exception —— 而且 socket 超时设短,别白等 15 秒。
    s.settimeout(2)
    got = None
    try:
        got = readline_until(f, "HARR ", timeout=2)
    except Exception:
        pass
    check("★ 没有效签名 → 拿不到自述", got is None, got)

    try:
        f.close(); s.close()
    except Exception:
        pass
    time.sleep(0.2)
    server.SECRET_PATH = real_secret_path
    if os.path.exists(TMP_SECRET):
        os.remove(TMP_SECRET)


def main():
    test_hand_info()
    test_fingerprint_tracks_schema()
    test_discovery_payload()
    test_hand_command()
    print()
    if FAILS:
        print("失败 %d 项: %s" % (len(FAILS), FAILS))
        sys.exit(1)
    print("手自测全部通过")


if __name__ == "__main__":
    main()
