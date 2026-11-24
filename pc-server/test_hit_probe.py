# -*- coding: utf-8 -*-
"""「点电脑的输入框 → 手机弹输入法」那只眼睛的测试。

钉四件事:

1. **probe 真的问得动电脑**,而且回执里有判据字段。
2. **原生编辑器认得出**。记事本当靶子不是因为简单,是因为它**撒谎**:
   它谎报 `ValuePattern.IsReadOnly = true`,按 UIA 那条路判会得到 false,
   而它明明能打字。真正救场的是**指针形状**。
3. **浏览器里的输入框认得出**。靶子是 `probe_test_page.html`(自己开的)、
   不是用户的浏览器 —— 见那个文件里的说明。
4. **probe 没有副作用**:跑完之后记事本内容一字不差。

跑法:python test_hit_probe.py   (要桌面会话;会短暂借用记事本和 Edge)
"""
import ctypes
import os
import subprocess
import sys
import time

sys.path.insert(0, ".")

import uia  # noqa: E402

FAILS = []
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
u32 = ctypes.windll.user32
HERE = os.path.dirname(os.path.abspath(__file__))

# 靶子页里每条 I 型条带(自上而下)背后的东西。顺序是**故意**排的:
# 中间那个「正文」也是 I 型 —— 那是已知代价,不是漏判。测试按顺序认,
# 不按绝对像素,DPI/缩放变了也不会误报。
EXPECT_BANDS = ["输入框", "多行文本域", "可选正文(已知假阳性)"]


def check(name, cond, extra=""):
    print(("  ok  " if cond else "  XX  ") + name + (("  " + str(extra)) if extra else ""))
    if not cond:
        FAILS.append(name)


class RECT(ctypes.Structure):
    _fields_ = [("left", ctypes.c_long), ("top", ctypes.c_long),
                ("right", ctypes.c_long), ("bottom", ctypes.c_long)]


def visible_windows():
    out = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    def cb(h, _):
        n = u32.GetWindowTextLengthW(h)
        if n > 0 and u32.IsWindowVisible(h):
            buf = ctypes.create_unicode_buffer(n + 1)
            u32.GetWindowTextW(h, buf, n + 1)
            out.append((h, buf.value))
        return True

    u32.EnumWindows(cb, 0)
    return out


def find_window(sub):
    for h, t in visible_windows():
        if sub in t:
            return h, t
    return None, None


def window_class(h):
    b = ctypes.create_unicode_buffer(256)
    u32.GetClassNameW(h, b, 256)
    return b.value


def find_new_window(cls, before):
    """★ 找**新出现的**那个窗口,按 class 找 —— 不按标题、也不按 PID。
    · 按标题会捞到上一次跑测试留下的记事本(躲在后头,顶不上前台,
      于是量出来的其实是别的窗口);
    · 按 PID 也不行:Win11 的 notepad.exe 是个启动器,真正的窗口属于**另一个**进程。"""
    for h, t in visible_windows():
        if h not in before and window_class(h) == cls:
            return h
    return None


def force(h):
    """顶到前台。★ 必须回读确认 —— 顶不上就静默失败,量出来的是别的窗口,
    2026-10-04 那次「Edge 的结论」就是这么来的。"""
    if u32.GetForegroundWindow() == h:
        return True
    u32.ShowWindow(h, 9)
    u32.SetForegroundWindow(h)
    for _ in range(12):
        if u32.GetForegroundWindow() == h:
            return True
        time.sleep(0.1)
    return u32.GetForegroundWindow() == h


def click(x, y, hwnd):
    """生产流程里这一击**已经先落到电脑上了**(手机把触摸转成了点击),
    然后才问 probe。测试必须照这个顺序来。

    ★ 每次都要确认 hwnd 还是前台 —— 指针形状**只在活动窗口上才更新**,
    前台被抢走时 `GetCursorInfo` 回的是那个窗口的指针(通常是箭头)。
    这条已经骗过我一次:测试报「输入框 cursor=arrow」,而单独测同一个点
    却是 text;差别就在前台漂了。"""
    if u32.GetForegroundWindow() != hwnd:
        force(hwnd)
        time.sleep(0.25)
    u32.SetCursorPos(x, y)
    time.sleep(0.12)
    u32.mouse_event(0x0002, 0, 0, 0, 0)
    time.sleep(0.04)
    u32.mouse_event(0x0004, 0, 0, 0, 0)
    time.sleep(0.45)


def page_rect(hwnd):
    """Edge 里真正画网页的那个子窗口的矩形 —— 页面坐标的原点。"""
    got = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    def cb(h, _):
        buf = ctypes.create_unicode_buffer(256)
        u32.GetClassNameW(h, buf, 256)
        if buf.value == "Chrome_RenderWidgetHostHWND":
            got.append(h)
        return True

    u32.EnumChildWindows(hwnd, cb, 0)
    if not got:
        return None
    r = RECT()
    u32.GetWindowRect(got[0], ctypes.byref(r))
    return r.left, r.top, r.right - r.left, r.bottom - r.top


def scan_bands(hwnd, x_off, y_off, w, h, step=8):
    """竖向扫一遍,把连续同类的指针形状压成条带。→ [(起点y, 终点y, 形状)]"""
    bands = []
    for y in range(4, h - 4, step):
        if u32.GetForegroundWindow() != hwnd:
            force(hwnd)
            time.sleep(0.15)
        u32.SetCursorPos(x_off + 220, y_off + y)
        time.sleep(0.03)
        k = uia.probe(x_off + 220, y_off + y).get("cursor")
        if bands and bands[-1][2] == k:
            bands[-1][1] = y
        else:
            bands.append([y, y, k])
    # 掐头去尾那种一两条的抖动不算条带
    return [(a, b, k) for a, b, k in bands if (b - a) >= step]


def main():
    print("== 一、probe 问得动电脑吗 ==")
    r = uia.probe(600, 400)
    check("probe 有回包", isinstance(r, dict) and r.get("ok") is True, r.get("error", ""))
    for k in ("cursor", "editable", "at", "focus", "diag"):
        check("回执里有 %s" % k, k in r)

    print()
    print("== 二、原生编辑器(记事本:它谎报 IsReadOnly) ==")
    np_win = None
    before = {h for h, _ in visible_windows()}
    try:
        np_win = subprocess.Popen(["notepad.exe"])
    except Exception as e:
        print("  开不了记事本: %s" % e)
    time.sleep(2.0)
    h = find_new_window("Notepad", before) if np_win else None
    if h is None:
        print("  找不到记事本窗口,跳过")
    else:
        check("记事本顶得上前台", force(h))
        time.sleep(0.4)
        r = RECT()
        u32.GetWindowRect(h, ctypes.byref(r))
        ex, ey = (r.left + r.right) // 2, r.top + 160     # 菜单栏下面就是编辑区
        click(ex, ey, h)
        p = uia.probe(ex, ey)
        check("记事本编辑区 → 认得出能打字", p.get("editable") is True,
              "cursor=%s at=%s" % (p.get("cursor"), (p.get("at") or {}).get("type")))
        at = p.get("at") or {}
        check("★ 而 UIA 自己判的是「不能打字」(证明救场的是指针形状)",
              at.get("editable") is False,
              "at.editable=%s patterns=%s" % (at.get("editable"), at.get("patterns")))

        # 反例取**状态栏**(窗口最底下那条):点它什么都不会发生。
        # 不取菜单栏 —— 点菜单栏会弹出下拉菜单,那是副作用。
        sx, sy = (r.left + r.right) // 2, r.bottom - 14
        click(sx, sy, h)
        p2 = uia.probe(sx, sy)
        check("记事本状态栏 → 不认成能打字", p2.get("editable") is False,
              "cursor=%s" % p2.get("cursor"))

        before = uia.probe(ex, ey)
        after = uia.probe(ex, ey)
        check("probe 是只读的(两次回包一致)", before.get("editable") == after.get("editable"))

    if np_win is not None:
        try:
            np_win.terminate()
        except Exception:
            pass

    print()
    print("== 三、浏览器里的输入框 ==")
    page = os.path.join(HERE, "probe_test_page.html")
    if not os.path.isfile(EDGE):
        print("  没有 Edge,跳过")
    else:
        before = {h for h, _ in visible_windows()}
        proc = subprocess.Popen([EDGE, "--app=file:///" + page.replace("\\", "/"),
                                 "--window-position=40,40", "--window-size=700,720",
                                 "--no-first-run"])
        time.sleep(4.0)
        eh = find_new_window("Chrome_WidgetWin_1", before)
        if eh is None:
            print("  靶子窗口没开起来,跳过")
        else:
            check("靶子窗口顶得上前台", force(eh))
            time.sleep(0.6)
            pr = page_rect(eh)
            check("找得到画网页的子窗口", pr is not None)
            if pr:
                x0, y0, pw, ph = pr
                print("  页面区 = (%d,%d) %dx%d" % pr)
                bands = scan_bands(eh, x0, y0, pw, ph)
                print("  条带:", [(a, b, k) for a, b, k in bands])
                text_bands = [(a, b) for a, b, k in bands if k == "text"]
                check("页面里有 I 型条带(至少认出输入框)", len(text_bands) >= 1,
                      "共 %d 条" % len(text_bands))
                if text_bands:
                    # 单点确认最上面那条确实是 <input>
                    cx, cy = x0 + 220, y0 + (text_bands[0][0] + text_bands[0][1]) // 2
                    click(cx, cy, eh)
                    p = uia.probe(cx, cy)
                    check("输入框那一点 → editable=True", p.get("editable") is True,
                          "cursor=%s at=%s" % (p.get("cursor"),
                                               (p.get("at") or {}).get("type")))
                    check("★ 说得出那是个 Edit 控件",
                          (p.get("at") or {}).get("type") == "Edit",
                          str((p.get("at") or {}).get("name")))
                # 空白区(第一个元素上面留的 20px 边距)不该弹键盘。
                # ★ 这一条是防**焦点泄漏**的:窗口里只要有个输入框拿着焦点,
                #   拿 focus 当判据就会在**任何地方**都回 true(2026-10-04 踩到)。
                click(x0 + 220, y0 + 8, eh)
                p = uia.probe(x0 + 220, y0 + 8)
                check("页面顶上的空白 → editable=False(焦点不许泄漏)",
                      p.get("editable") is False,
                      "cursor=%s focus.editable=%s" % (
                          p.get("cursor"), (p.get("focus") or {}).get("editable")))
            try:
                proc.terminate()
            except Exception:
                pass

    print()
    if FAILS:
        print("失败 %d 条: %s" % (len(FAILS), ", ".join(FAILS)))
        return 1
    print("全绿")
    return 0


if __name__ == "__main__":
    sys.exit(main())
