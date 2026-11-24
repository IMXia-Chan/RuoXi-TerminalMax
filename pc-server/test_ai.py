#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AI 指令通道自测:AI <b64 json> -> AIR <b64 json>。

注意:**不能碰真实的 secret.json**(真删了就得重新配对),所以把 server.SECRET_PATH
临时指到本目录下的 test_ai_secret.json,测完删掉。

默认只验证「协议打通 + 工具语义」,**不真动鼠标**(click 换成记录器)。
真开应用/真点击另跑 --live。
"""
import base64
import io
import json
import os
import socket
import sys
import tempfile
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

TMP_SECRET = os.path.join(HERE, "test_ai_secret.json")
PORT = 19529


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


def main(live=False):
    events = []
    server._emit = lambda kind, **kw: events.append({"kind": kind, **kw})

    # 用临时密钥文件,绝不碰真实 secret.json
    real_secret_path = server.SECRET_PATH
    server.SECRET_PATH = TMP_SECRET
    for p in (TMP_SECRET,):
        if os.path.exists(p):
            os.remove(p)
    assert os.path.abspath(real_secret_path) != os.path.abspath(TMP_SECRET)
    server.init_store()
    server._fail_count = 0
    server._lock_count = 0
    server._lock_until = 0

    # 记录点击,不真动鼠标(除非 --live)
    clicks = []
    real_click = server.ai_click_real
    if not live:
        server.ai_click_real = lambda x, y: clicks.append((x, y))

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
    print("配对 OK,会话密钥已派生")

    seq = [0]

    def ai(payload, expect_line=True):
        """发一条签名 AI 指令并取回 AIR。"""
        seq[0] += 1
        body = json.dumps(payload, ensure_ascii=False)
        b64 = base64.b64encode(body.encode("utf-8")).decode("ascii")
        line = "AI %d %s" % (seq[0], b64)
        f.write("%s %s\n" % (line, server.sign_command(sk, line))); f.flush()
        if not expect_line:
            return None
        raw = readline_until(f, "AIR ")
        return json.loads(base64.b64decode(raw[4:]).decode("utf-8"))

    fails = []

    def check(name, cond, extra=""):
        print("  %s %s %s" % ("OK  " if cond else "FAIL", name, extra))
        if not cond:
            fails.append(name)

    print()
    print("== 1. list_windows ==")
    r = ai({"tool": "list_windows", "args": {}, "id": 101})
    check("id 原样带回", r.get("id") == 101, r.get("id"))
    check("ok", r.get("ok") is True, r.get("error", ""))
    wins = (r.get("result") or {}).get("windows") or []
    check("拿到窗口列表", len(wins) > 0, "%d 个" % len(wins))
    if wins:
        print("     例:", wins[0]["title"][:40])   # 只回标题了,没有 proc(见 ai_tools.list_windows)

    print("== 2. get_state ==")
    r = ai({"tool": "get_state", "args": {}, "id": 102})
    check("ok", r.get("ok") is True, r.get("error", ""))
    print("     前台:", (r.get("result") or {}).get("foreground"))

    print("== 3. 白名单(越权工具必须被拒) ==")
    r = ai({"tool": "delete_all_files", "args": {}, "id": 103})
    check("拒绝未知工具", r.get("ok") is False, r.get("error", ""))
    # 这里**故意不写死条数**。以前断言的是 len == 7,于是每加一个工具都得回来改一次,
    # 而漏改的表现是「工具明明加对了、测试却红」—— 纯属自找。这条断言真正要守的只有
    # 一件事:**拒绝的时候,把当时真正允许的那些回给模型,好让它下一轮自己纠正。**
    # 所以拿 TOOLS 表本身对齐,而不是拿一个会过期的数字。
    import ai_tools
    check("拒绝时回带的正是当前的允许列表",
          sorted(r.get("allowed") or []) == sorted(ai_tools.TOOLS.keys()),
          r.get("allowed"))
    check("被拒的那个自己不在允许列表里",
          "delete_all_files" not in (r.get("allowed") or []))
    r = ai({"tool": "media", "args": {"action": "nuke"}, "id": 104})
    check("拒绝未知媒体动作", r.get("ok") is False, r.get("error", ""))

    print("== 4. 非法请求体 ==")
    seq[0] += 1
    line = "AI %d %s" % (seq[0], base64.b64encode(b"not json").decode())
    f.write("%s %s\n" % (line, server.sign_command(sk, line))); f.flush()
    raw = readline_until(f, "AIR ")
    r = json.loads(base64.b64decode(raw[4:]).decode("utf-8"))
    check("坏 JSON 不崩、回错误", r.get("ok") is False, r.get("error", ""))

    print("== 5. screenshot(真抓屏) ==")
    t0 = time.time()
    r = ai({"tool": "screenshot", "args": {}, "id": 105})
    dt = (time.time() - t0) * 1000
    check("ok", r.get("ok") is True, r.get("error", ""))
    res = r.get("result") or {}
    img = res.get("image") or ""
    check("有 base64 图", len(img) > 1000, "%d 字节 b64" % len(img))
    if img:
        raw_img = base64.b64decode(img)
        check("是 JPEG(FFD8 开头)", raw_img[:2] == b"\xff\xd8", raw_img[:2].hex())
        check("缩放后宽度 <= 1280", res.get("w", 0) <= 1280,
              "图 %sx%s / 屏 %sx%s" % (res.get("w"), res.get("h"),
                                       res.get("screen_w"), res.get("screen_h")))
    print("     耗时 %.0fms" % dt)

    print("== 6. click_at(走坐标换算,%s) ==" % ("真实点击" if live else "记录器"))
    r = ai({"tool": "click_at", "args": {"x": 600, "y": 400}, "id": 106})
    check("ok", r.get("ok") is True, r.get("error", ""))
    if live:
        import ctypes
        class _PT(ctypes.Structure):
            _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]
        pt = _PT()
        ctypes.windll.user32.GetCursorPos(ctypes.byref(pt))
        # DPI/缩放不影响:两者都应是「物理像素」;允许少量误差
        check("光标落在 (600,400) 附近", abs(pt.x - 600) <= 3 and abs(pt.y - 400) <= 3,
              "实际 (%d,%d)" % (pt.x, pt.y))
    else:
        check("点到了 (600,400)", clicks == [(600, 400)], clicks)

    print("== 7. focus_window(找不到时的候选) ==")
    r = ai({"tool": "focus_window", "args": {"title": "绝对不存在的窗口zzz"}, "id": 107})
    check("没找到就报错+候选", r.get("ok") is False and bool(r.get("candidates")),
          str(r.get("candidates"))[:60])
    # ★ 失败回执里那句 hint 是给两个**真踩过的错**写的(2026-10-03),它们报的错一模一样
    # ——「没找到窗口」—— 但下一步该做的完全相反:
    #   (a) 模型把「文件传输助手」这种**会话名**当窗口标题去 focus_window → 该先切应用再 search;
    #   (b) 应用**根本没开着**(微信没在跑)→ 该直接 open_app。
    # (b) 在真机上白烧了一整轮。错在哪儿就说哪儿,比在 SYSTEM_PROMPT 里讲一百遍管用,
    # 所以两条路都得守住,别在重构里掉了一条。
    hint = r.get("hint") or ""
    check("失败时给得出「这是应用里面的条目」那条路", "不是" in hint and "search" in hint)
    check("也给出「应用没开着 → 直接 open_app」那条路", "open_app" in hint, hint[:60])

    print("== 8. 焦点守门(不许把字打到别的窗口里去) ==")
    # 2026-10-03 真机翻车:模型 open_app 微信 → 中间用户切回 VS Code → search 的
    # Ctrl+F 打进了编辑器,最后还报「已成功发送」。修法是记住本任务的操作窗口。
    # 这里用一个假的 user32 测,**不动用户真实的前台窗口**。
    import ai_tools
    real_u32 = ai_tools.user32

    class _FakeU32:
        """★ `SetForegroundWindow` 故意**异步生效**(头两次回读还给旧值)——
        真实的那个也这样。2026-10-03 真机上正是栽在这:老代码「调完立刻回读」,
        把自己刚顶回来的窗口判成「没顶回来」,于是同一次任务里 `type` 被拒、
        几毫秒后的 `hotkey` 却放行。假同步的话这个 bug 在测试里根本不会出现。
        """
        def __init__(self, fg, real):
            self.fg = fg
            self._want = None      # 已经请求、还没生效的前台窗口
            self._ticks = 0
            self.focused = []
            self._real = real
        def __getattr__(self, k):
            return getattr(self._real, k)   # 其余原样透传给真 user32
        def GetForegroundWindow(self):
            if self._want is not None:
                if self._ticks <= 0:
                    self.fg, self._want = self._want, None
                else:
                    self._ticks -= 1
            return self.fg
        def IsWindow(self, h):
            return bool(h)
        def SetForegroundWindow(self, h):
            self.focused.append(h)
            self._want, self._ticks = h, 2
            return 1
        def ShowWindow(self, h, n):
            return 1
        def keybd_event(self, *a):
            pass
        def GetWindowTextLengthW(self, h):
            return 0          # → 标题空,只有文案里那句「(桌面或其他窗口)」
        def GetWindowThreadProcessId(self, h, out):
            # 222 = 任务窗口,444 = 同一应用的弹窗(同 pid),333 = 别的应用
            out._obj.value = {222: 9001, 444: 9001, 333: 9002}.get(int(h or 0), 0)
            return 1

    fake = _FakeU32(222, real_u32)
    ai_tools.user32 = fake
    try:
        ai_tools._set_task_window(222, "任务窗口", [])
        check("前台就是任务窗口 → 放行", ai_tools._guard_focus() is None)
        check("放行时不该碰前台", fake.focused == [], fake.focused)

        fake.fg = 444
        check("同应用的弹窗(同 pid)→ 放行", ai_tools._guard_focus() is None)
        check("放行弹窗时也不碰前台", fake.focused == [], fake.focused)

        # ★ 回归:`SetForegroundWindow` 异步生效(见 _FakeU32)。这一步同时钉住
        # 「顶回成功就该放行」和「不能调完立刻回读」—— 后者在老代码上会误判成没顶回来。
        fake.fg = 333
        check("被别的应用切走 → 先顶回一次(放行)", ai_tools._guard_focus() is None)
        check("确实把任务窗口拉回了前台", fake.focused == [222], fake.focused)
        check("顶回之后前台真的变了", fake.fg == 222, fake.fg)

        fake.fg = 333
        why = ai_tools._guard_focus()
        check("顶回还被切走 → 拒绝", isinstance(why, str) and "任务窗口" in why, why)
        r = ai_tools.execute({"tool": "type", "args": {"text": "这句不许打出去"}})
        check("execute 里 type 同样被拦", r.get("ok") is False, r.get("error", ""))
        check("只读工具不受影响",
              ai_tools.execute({"tool": "list_windows", "args": {}}).get("ok") is True)

        ai_tools._clear_task_window()
        check("没钉任务窗口 → 放行(不知道就不拦)", ai_tools._guard_focus() is None)
        check("拿不到 pid 时不当作同一个应用",
              ai_tools._same_app(111, 999) is False)
    finally:
        ai_tools.user32 = real_u32
        ai_tools._clear_task_window()

    print("== 9. 命中必须落在「本任务操作的那个应用」里 ==")
    # ★ 2026-10-03 真机第二次翻车,而且比第一次更狠:模型要点微信里的
    # 「文件传输助手」,`Find-Scoped` 在前台窗口没找到之后会去搜**其余所有桌面窗口**,
    # 那一遍在 VS Code 里匹配到了几段 Text 节点 —— 内容是 `click_ui {"name":"文件传输助手"}`
    # 这种**我们自己打出来的运行日志**。于是「滚进视野 → 点它」,报 ok=True。
    # 用户看到的就是「搜到了,但进不去」。这几条断言钉住那道闸门。
    ai_tools._set_task_window(222, "微信", [])
    check("前台以外那些窗口来的命中一律丢掉",
          ai_tools._scope_ok({"desk": True, "window": "整个桌面"}) is False)
    check("命中窗口就是任务窗口 → 放行",
          ai_tools._scope_ok({"desk": False, "window": "微信"}) is True)

    real_enum, real_pid = ai_tools._enum_windows, ai_tools._pid_of
    ai_tools._enum_windows = lambda: [
        {"title": "另存为", "hwnd": 555, "proc": "wechat.exe"},     # 同一进程的独立弹窗
        {"title": "手机WiFi触控板 - Visual Studio Code", "hwnd": 666, "proc": "Code.exe"},
    ]
    # 222 = 任务窗口(微信主窗口)自己,555 = 它的「另存为」弹窗,两者必须同 pid
    ai_tools._pid_of = lambda h: {222: 9001, 555: 9001, 666: 9002}.get(int(h or 0), 0)
    try:
        check("同进程的独立弹窗(标题完全不像)要放行 —— 打开/保存就在那儿",
              ai_tools._scope_ok({"desk": False, "window": "另存为"}) is True)
        check("别的进程的窗口要拒",
              ai_tools._scope_ok({"desk": False, "window": "手机WiFi触控板 - Visual Studio Code"}) is False)
    finally:
        ai_tools._enum_windows, ai_tools._pid_of = real_enum, real_pid

    check("认不出这个标题对应哪个窗口时不拦(认不出就别装作认得)",
          ai_tools._scope_ok({"desk": False, "window": "某个没登记过的窗口"}) is True)
    ai_tools._clear_task_window()
    check("没钉任务窗口时不拦(不知道在操作谁)",
          ai_tools._scope_ok({"desk": False, "window": "随便什么窗口"}) is True)
    check("助手脚本没报 desk(旧版)也不拦", ai_tools._scope_ok({"window": "x"}) is True)
    check("空回包不拦", ai_tools._scope_ok(None) is True)

    print()
    print("  -- OCR 必须和 UIA 绑在同一个作用域上,否则同一批字会被再捞回来 --")
    check("没指名窗口时 → 认本任务的操作窗口", ai_tools._ocr_scope(None) is None)
    ai_tools._set_task_window(222, "微信", [])
    check("钉了任务窗口 → 就用它",
          ai_tools._ocr_scope(None) == "微信", ai_tools._ocr_scope(None))
    check("模型自己指了窗口 → 听它的",
          ai_tools._ocr_scope("记事本") == "记事本")
    ai_tools._clear_task_window()

    if live:
        print("== 10. open_app(真开记事本) ==")
        r = ai({"tool": "open_app", "args": {"name": "记事本"}, "id": 108})
        check("ok", r.get("ok") is True, r.get("error", ""))
        print("     启动了:", (r.get("result") or {}).get("launched"))
        time.sleep(1.5)
        r = ai({"tool": "get_state", "args": {}, "id": 109})
        titles = [w["title"] for w in (r.get("result") or {}).get("windows", [])]
        check("窗口列表里出现记事本", any("记事本" in t for t in titles),
              str([t for t in titles if "记事本" in t]))
        os.system('taskkill /FI "IMAGENAME eq notepad.exe" /F >nul 2>&1')

    f.close(); s.close()
    time.sleep(0.2)
    if not live:
        server.ai_click_real = real_click
    server.SECRET_PATH = real_secret_path
    for p in (TMP_SECRET,):
        if os.path.exists(p):
            os.remove(p)
    print()
    if fails:
        print("失败 %d 项: %s" % (len(fails), fails))
        sys.exit(1)
    print("AI 通道自测全部通过" + ("(--live 含真实点击/启动)" if live else ""))


if __name__ == "__main__":
    main(live="--live" in sys.argv)
