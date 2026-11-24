# -*- coding: utf-8 -*-
"""open_app 返回的时候,那个窗口**真的能用了吗**?

## 为什么要问这个

2026-10-03 真机日志,同一个动作两种结果:

    09:47:12  open_app 微信  → 172ms  launched="微信"    ← 冷启动
    09:47:13  click_ui 文件传输助手 → 10.3s 后 ok=False「这一屏上没有」   ← 只隔 0.7 秒
    ...连失败三次
    09:49:52  open_app 微信  → 4ms                        ← 已经开着,只是切前台
    09:50:43  click_ui 文件传输助手 → ok=True via="OCR 屏幕文字"

差别不在 click_ui,在**窗口画出来了没有**。事后量「按下启动 → 窗口认得到」
= 3.20 秒 / 0.90 秒,两次独立测量**都远超那 0.7 秒**。

## 这个测试验两件事,缺一不可

  A. 冷启动:`open_app` 必须在窗口出现**之后**才返回(waited_s > 0)。
     返回后**立刻**(不 sleep)去认那个窗口里的字 —— 认得到才算数。

  B. 对照组:同一个窗口**热着**的时候,同样的查询必须认得到。
     没有这一节,A 失败就说不清是「窗口没画好」还是「记事本的菜单本来就认不到」。
     少一个变量,结论就不成立 —— 这是这一整天反复栽的同一个跟头。

拿记事本做实验(不碰微信:重开微信有开出无登录态新实例的风险)。
"""
import subprocess
import sys
import time

sys.path.insert(0, r"C:\Users\27014\phone-touchpad\pc-server")
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
import ai_tools
import uia

fails = []
voids = []


def running_notepads():
    r = subprocess.run(["tasklist", "/FI", "IMAGENAME eq notepad.exe", "/NH"],
                       capture_output=True, text=True, errors="replace")
    return [ln for ln in (r.stdout or "").splitlines() if "notepad" in ln.lower()]


def kill_notepad():
    subprocess.run(["taskkill", "/IM", "notepad.exe", "/F"], capture_output=True)
    time.sleep(1.0)


def see_menu(tag):
    """认出记事本菜单栏的字没有?返回 OCR 总行数。**立刻**跑,不 sleep。"""
    t = time.time()
    els, raw = uia.ocr_find("文件", window=None, limit=5)
    hit = [e["name"] for e in els if "文" in (e.get("name") or "")]
    total = raw.get("total") or 0
    print("     [%s] OCR %.2fs 认到 %d 行(总行数 %d)%s"
          % (tag, time.time() - t, len(els), total,
             "  认到「文件」✓" if hit else "  **没认到菜单**"))
    return total, bool(hit)


# ---------------------------------------------------------------------------
print("=== 前置:记事本必须是关着的 ===")
if running_notepads():
    print("  !! 你正开着记事本,我不动它。想跑就先关掉。")
    sys.exit(2)
kill_notepad()
print("  没在跑 ✓")

# 热身:把 PowerShell / OCR 引擎的冷启动开销从测量里摘出去。
# 不热身的话第一次 ocr_find 要 6 秒多,会被读成"窗口画得慢"——上一版就是这么量歪的。
print("  热身中……")
t = time.time()
uia.ping()
uia.list_elements(limit=5)
print("  热身完成 %.1fs" % (time.time() - t))

# ---------------------------------------------------------------------------
print()
print("=== A) 冷启动:open_app 必须等窗口出来才返回 ===")
t0 = time.time()
r = ai_tools.open_app("notepad")
dt = time.time() - t0
print("  open_app 耗时 %.2fs -> ok=%s ready=%s waited_s=%s window=%r"
      % (dt, r.get("ok"), r.get("ready"), r.get("waited_s"), r.get("window")))
if not r.get("ok"):
    print("  !! 没打开:%s" % r.get("error"))
    fails.append("open_app 冷启动失败")
else:
    if not r.get("ready"):
        print("  ✗ 12 秒内没等到窗口 —— 修复没起作用")
        fails.append("冷启动没等到窗口")
    elif not (r.get("waited_s") or 0) > 0:
        print("  ✗ 没等就返回了(waited_s=%s)—— 那说明启动快得可疑,或判据有问题"
              % r.get("waited_s"))
        fails.append("冷启动没等")
    else:
        print("  ✓ 等到窗口才返回(等了 %.2fs)" % r["waited_s"])
    print("  紧接着**不 sleep**,直接认字:")
    total_a, hit_a = see_menu("冷启动后立刻")
    if total_a == 0:
        print("  ✗ 一个字都没认到 —— **窗口出现了,内容还没画好**")
        print("     这就是手机那次失败的同一个坑:等窗口**存在**并不等于等它**画完**。")
        fails.append("窗口出现后内容仍未就绪(修复不够)")

# ---------------------------------------------------------------------------
print()
print("=== B) 对照组:同一个窗口热着的时候,同样的查询认得到吗 ===")
# 没有这一节,A 失败就分不清是"没画好"还是"记事本的菜单本来就认不到"。
r2 = ai_tools.open_app("notepad")
print("  再 open_app -> ready=%s waited_s=%s reused=%s"
      % (r2.get("ready"), r2.get("waited_s"), r2.get("reused")))
if not r2.get("reused"):
    print("  ⚠ 没认出「已经在跑」—— 那 A 和 B 比的可能不是同一个窗口")
    voids.append("对照组没走 reused 分支")
print("  立刻认字:")
total_b, hit_b = see_menu("热窗口")

if total_b == 0:
    print("  ⚠ 热着的窗口都认不到字 —— **这个查询本身不可靠**,A 的结论作废")
    voids.append("对照组也认不到,查询机制本身不可靠,无从判断")
else:
    print("  ✓ 热窗口认得到(n=%d)→ 说明查询可用,A 的结论成立" % total_b)

# ---------------------------------------------------------------------------
print()
print("=== 收尾 ===")
kill_notepad()
print("  记事本已关")

print()
print("=== 总账 ===")
if fails:
    print("  **失败 %d 项**: %s" % (len(fails), fails))
elif voids:
    print("  通过,但有 %d 项**空过**(不是通过,是没验成): %s" % (len(voids), voids))
else:
    print("  全部通过 ✓")
sys.exit(1 if fails else (2 if voids else 0))
