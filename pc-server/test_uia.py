# -*- coding: utf-8 -*-
"""uia.py 的验收:按名字找控件,和**已知真值**对账。

为什么必须有真值才叫验收:UIA 返回一个坐标,我怎么知道它对?所以拿开始按钮对账。

⚠️ 2026-10-03 改:**真值不再写死,当场用 user32 独立测。**

以前这里写死 `(24, 1060)` —— 那是 Win10 的答案(任务栏靠左,开始按钮在 (0,1040)-(48,1080))。
系统升级到 Win11 后任务栏图标居中,开始按钮跑到 x≈760,于是这个测试**每次跑都报一次 ✗**,
而 UIA 其实报得完全正确。一个永远在报假警报的测试比没有测试更糟:它教会人忽略它的输出。

也**不能**「按系统版本挑一个常量」:实测同一天里 x 从 738 变成 760 —— 任务栏图标会随
开着几个窗口而移动。写死任何数都会再过期一次。

所以改成当场独立测:`Shell_TrayWnd` 的子窗口里有一个 class 叫 `Start` 的,那就是开始按钮。
这个探针在 Win10 和 Win11 上都成立(两边都实测过),不需要版本分支。
它走的是**裸 user32**,和 uia.py 的 UIA COM 是两套完全不同的 API 栈,所以仍然是第三方证据。

判据也从「中心点是否完全相等」换成「**UIA 报的点落不落在那块矩形里**」——
那才是真问题(落进去就会点中),而中心差 1 像素不是问题。
"""
import ctypes
import ctypes.wintypes
import os
import sys
import time

sys.path.insert(0, r"C:\Users\27014\phone-touchpad\pc-server")
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# 和 ai_tools.py:68-77 保持一致。不一致的话,在缩放 >100% 的屏上这个测试量的
# 和服务端量的**不是同一个数**(不开 DPI 感知拿到的是逻辑像素),对账就白对了。
# 只能设一次,重复设会失败 —— 忽略即可。
try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)   # PROCESS_PER_MONITOR_DPI_AWARE
except Exception:
    try:
        ctypes.windll.user32.SetProcessDPIAware()
    except Exception:
        pass

import uia


def run(label, fn):
    a = time.time()
    out = fn()
    return out, (time.time() - a) * 1000


def start_truth():
    """user32 独立测开始按钮矩形 (l,t,r,b)。

    取不到返回 None —— 注意这**不是**「没有开始按钮」,而是「这个探针在本机上不成立」
    (任务栏被换掉、被第三方壳顶掉、枚举被挡……)。调用方必须把这两种情况分开说,
    否则探针失效会被读成「UIA 报错了」,又变成一次假警报。
    """
    u = ctypes.windll.user32
    u.FindWindowW.restype = ctypes.c_void_p
    u.FindWindowW.argtypes = [ctypes.c_wchar_p, ctypes.c_wchar_p]
    u.GetClassNameW.argtypes = [ctypes.c_void_p, ctypes.c_wchar_p, ctypes.c_int]
    u.GetWindowRect.argtypes = [ctypes.c_void_p,
                                ctypes.POINTER(ctypes.wintypes.RECT)]
    CB = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    u.EnumChildWindows.argtypes = [ctypes.c_void_p, CB, ctypes.c_void_p]

    tray = u.FindWindowW("Shell_TrayWnd", None)
    if not tray:
        return None

    buf = ctypes.create_unicode_buffer(256)
    found = []

    def cb(h, _lp):
        u.GetClassNameW(h, buf, 256)
        if buf.value == "Start":
            r = ctypes.wintypes.RECT()
            u.GetWindowRect(h, ctypes.byref(r))
            found.append((r.left, r.top, r.right, r.bottom))
        return True

    u.EnumChildWindows(tray, CB(cb), 0)
    return found[0] if found else None


fail = 0        # 真问题,退出码 1
void = 0        # 探针失效,对账作废,退出码 2(既不是通过也不是失败)

print("=== 逐条按名字找 ===")
_, ms = run("ping", uia.ping)
print("  ping(含冷启动)  %6.0fms" % ms)

for nm in ["开始", "关闭", "文件", "终端", "最小化", "微信"]:
    (els, raw), ms = run(nm, lambda n=nm: uia.find(n))
    if els:
        e = els[0]
        print("  find %-8s %6.0fms  [%s] %s（%s）中心=(%d,%d) %dx%d 候选%d"
              % ("「%s」" % nm, ms, raw.get("window"), e["name"], e["type"],
                 e["cx"], e["cy"], e["w"], e["h"], raw.get("count")))
    else:
        print("  find %-8s %6.0fms  ✗ 没找到（搜了 %s）" % ("「%s」" % nm, ms, raw.get("window")))

print()
print("=== 开始按钮对账（这是这次改造的全部理由）===")
truth = start_truth()
(els, raw), ms = run("开始", lambda: uia.find("开始"))

if not els:
    print("  ✗ UIA 根本没找到开始按钮 —— 这是真失败")
    fail += 1
elif truth is None:
    print("  UIA 给的中心 = (%d, %d)，类型 %s，耗时 %.0fms"
          % (els[0]["cx"], els[0]["cy"], els[0]["type"], ms))
    print("  ⚠ 独立真值取不到（Shell_TrayWnd 下没有 class=Start 的子窗口）")
    print("     → 这条对账**作废**,不是通过。任务栏可能被换了实现,探针要跟着改。")
    void += 1
else:
    e = els[0]
    l, t, r, b = truth
    inside = l <= e["cx"] <= r and t <= e["cy"] <= b
    print("  UIA 给的中心        = (%d, %d)，类型 %s，耗时 %.0fms"
          % (e["cx"], e["cy"], e["type"], ms))
    print("  真值(user32 现测)   = 矩形 (%d,%d)-(%d,%d)，中心 (%.1f, %.1f)"
          % (l, t, r, b, (l + r) / 2.0, (t + b) / 2.0))
    print("  UIA 的点落在真值里? %s（偏离中心 %.1f 像素）"
          % ("是 ✓" if inside else "否 ✗",
             ((e["cx"] - (l + r) / 2.0) ** 2 + (e["cy"] - (t + b) / 2.0) ** 2) ** 0.5))
    if not inside:
        print("     → 按 UIA 的坐标点下去会落在开始按钮外面,这是真失败")
        fail += 1

print()
print("=== list:给模型看的可点清单 ===")
(els, raw), ms = run("list", lambda: uia.list_elements(limit=15))
print("  窗口=%s  取回 %d 个  %.0fms" % (raw.get("window"), raw.get("count"), ms))
print(uia.describe(els, 15))

print()
print("=== 不存在的名字（不能瞎编，要老实说没有）===")
(els, raw), ms = run("miss", lambda: uia.find("保存并发送给张三"))
if els:
    print("  %.0fms → ✗ 编了一个: %s" % (ms, els[0]))
    fail += 1
else:
    print("  %.0fms → ✓ 老实说没有" % ms)

print()
print("=== 读一次屏幕,留下的那张临时截图有没有自己消失(2026-10-06)===")
# ★★ 这一段钉的是一个**真的在盘上躺过**的东西。
#
# 原先 `Get-OcrLines` 把整屏 PNG 存到 %TEMP% 之后就再也不管了(实测盘上留了 1.4MB)。
# 它留的是**你的整个屏幕**,比日志里任何一条都敏感 —— 而且**打字之后验货的兜底也走这条路**,
# 也就是说**打一次字就可能顺带把整屏留在盘上**。
#
# ★ 现在它 try/finally 兜住,正常返回也好、WinRT 那几步中途抛异常也好,离开那个函数时它就没了。
# ★ 它**兜不住**什么:进程被强杀在 `Save` 和 `finally` 之间,那一屏就留下了。所以开头还有一句
#   「先扫一遍」—— 那一具由下一次读屏收走。这里钉的是那条**正常路径**。
#
# ★★ 判据有两个方向,缺了这个测试就是在自欺:
#   ① **正控**:这一次读**真的读到了字** —— 不然「没留下文件」可能只是因为压根没读成;
#   ② **要钉的**:读完之后那个文件**不在了**。
_shot = os.path.join(os.environ.get("TEMP", ""), "ruoxi-uia-ocr.png")
if os.path.exists(_shot):
    print("  ⚠ 开头就有一张残留(上次被硬杀了?): %d 字节" % os.path.getsize(_shot))
(_ocr, _meta), _ms = run("ocr", lambda: uia.ocr())
_n = int((_meta or {}).get("count") or 0)
print("  %.0fms → 读到 %d 行" % (_ms, _n))
if _n <= 0:
    print("  ✗ 这一次一行都没读到 —— 下面的结论不算数(读都没读成,谈何「用完就删」)")
    void += 1
else:
    if os.path.exists(_shot):
        print("  ✗ 那个文件还在: %s (%d 字节)" % (_shot, os.path.getsize(_shot)))
        fail += 1
    else:
        print("  ✓ 读到了字,而且那张整屏截图已经不在了")

print()
if fail:
    print("!! 有 %d 项真失败" % fail)
elif void:
    print("?? 有 %d 项对账作废(探针失效,不是通过)" % void)
else:
    print("全部通过")
sys.exit(1 if fail else (2 if void else 0))
