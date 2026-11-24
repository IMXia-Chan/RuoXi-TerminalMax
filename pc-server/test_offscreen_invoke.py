# -*- coding: utf-8 -*-
"""离屏控件:找得到、动得了、而且**绝不按坐标乱点**。

背景:以前的 `IsOffscreen` 一刀切掉了 94% 的控件 —— 实测 VS Code 一个窗口
972 个可点控件里 **909 个是离屏的**。于是模型问「有没有 X」的时候,
我们**明明知道却回答没有**。这正是「看不见 = 不存在」那个病根。

现在的做法:
    find 连离屏一起搜  →  但离屏元素的坐标是「虚拟滚动空间」里的天文数字
    (实测 y = -19165 / -54509),**照着点鼠标会飞出屏幕** ——
    所以改走 uia.invoke:让控件自己动作,或 ScrollIntoView 把它滚进来。

这个测试的核心**不是**「能不能点中」,而是那条安全红线:
**任何一次真实点击的坐标,都必须落在屏幕范围内。**
点不中是能力问题,点到屏幕外是安全问题。

点击用**记录器**代替真点 —— 这台机器是用户正在用的桌面,不该被测试乱点。
"""
import sys
import time

sys.path.insert(0, r"C:\Users\27014\phone-touchpad\pc-server")
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
import ai_tools
import uia

# 主屏分辨率。越界判定就用它。
SCREEN = (0, 0, 1920, 1080)
TARGET_WINDOW = "Visual Studio Code"

clicked = []


def fake_click(x, y):
    clicked.append((x, y))


def call(tool, **args):
    t = time.time()
    r = ai_tools.execute({"tool": tool, "args": args}, click_fn=fake_click)
    return r, (time.time() - t) * 1000


def onscreen(x, y):
    return SCREEN[0] <= x <= SCREEN[2] and SCREEN[1] <= y <= SCREEN[3]


fails = []


def check(cond, label, detail=""):
    print("   %s %s%s" % ("✓" if cond else "✗", label,
                          "" if cond else "   ← " + str(detail)))
    if not cond:
        fails.append(label)


# ---------------------------------------------------------------------------
print("=== 1) find 现在能看见「没画出来」的控件 ===")
TARGET = "Copy response to clipboard"
hits, raw = uia.find(TARGET, window=TARGET_WINDOW, limit=5)
if not raw.get("ok"):
    print("   ✗ %s" % raw.get("error"))
else:
    n_off = sum(1 for h in hits if h.get("offscreen"))
    print("   查到 %d 个,其中离屏 %d 个" % (len(hits), n_off))
    for h in hits[:3]:
        print("     offscreen=%-5s 坐标(%d,%d)" % (h.get("offscreen"), h["cx"], h["cy"]))
    if n_off:
        print("   ↑ 坐标一律是 0:离屏元素**没有可用坐标**,这是故意的 ——")
        print("     它的真实包围盒是「虚拟滚动空间」里的天文数字,照点鼠标会飞出屏幕。")
        print("     以前这种元素被整个扔掉,于是「明明在」被说成「没有」。")

    # 真实矩形只在 patterns 这条诊断路上读(只查几个,读得起)
    phits, praw = uia.patterns(TARGET, window=TARGET_WINDOW)
    if praw.get("ok") and phits:
        for h in phits[:3]:
            print("     [patterns] %s  真实包围盒=%-18s 支持=%s"
                  % (h.get("type"), h.get("rect"), h.get("patterns")))

# ---------------------------------------------------------------------------
print()
print("=== 2) 离屏控件走 invoke,而不是点像素 ===")
before = len(clicked)
r, ms = call("click_ui", name="Show more", window=TARGET_WINDOW)
if r.get("ok"):
    print("   %.0fms → ok=True" % ms)
    print("   via  = %s" % r.get("via"))
    print("   控制 = %s（%s）" % (r.get("control"), r.get("type")))
    print("   这一轮碰鼠标了吗?%s"
          % ("碰了,点了 %s" % clicked[before:] if len(clicked) > before
             else "没碰 —— 直接命令控件 ✓"))
else:
    print("   %.0fms → ok=False  %s" % (ms, r.get("error")))
    print("   (控件不接受命令时,如实报错也算对 —— 关键是别谎报「没有」)")
    if "没有叫" in (r.get("error") or "") or "界面上没有" in (r.get("error") or ""):
        print("   ⚠️ 但这句话是**假话** —— 它明明在屏幕上,只是没画出来")

# ---------------------------------------------------------------------------
print()
print("=== 3) 红线:任何一次点击都不许落在屏幕外 ===")
# 这条比「点没点中」重要得多 —— 点到屏幕外就是点到别的东西上,
# 在有危险动作的场景(转账/删除/发送)这是真会出事的。
bad = [c for c in clicked if not onscreen(*c)]
print("   屏幕范围            : %s" % (SCREEN,))
print("   实际点过的坐标       : %s" % (clicked,))
print("   越界的               : %s" % (bad if bad else "没有 ✓"))

# ---------------------------------------------------------------------------
print()
print("=== 4) 分层没倒:屏幕内能中的,仍然走屏幕内、而且快 ===")
for nm in ["终端", "帮助"]:
    r, ms = call("click_ui", name=nm)
    via = r.get("via") or "UIA"
    ok = "✓" if (r.get("ok") and via == "UIA") else "⚠"
    print("   「%s」%6.0fms → via=%-8s ok=%s %s" % (nm, ms, via, r.get("ok"), ok))

# ---------------------------------------------------------------------------
print()
print("=== 5) 真不存在的,还是要诚实说没有,并且不点 ===")
before = len(clicked)
# ⚠️ 探针词**不许带危险词**(转账/支付/发送/删除/提交…):带了会先命中动作级风险闸,
# execute 在**找之前**就回 needs_confirm,这条就测不到「找不到时的观察回执」了。
# 那道闸自己另有测试(test_click_ui.py 里那两节)。
MISS = "确认交给他张三"
r, ms = call("click_ui", name=MISS)
print("   %.0fms → ok=%s  error=%s" % (ms, r.get("ok"), r.get("error")))
print("   有没有偷偷点一下?%s" % ("**点了,危险**" if len(clicked) > before else "没有 ✓"))
# 以前这里断言 `hint`(「改用 click_element…」)—— 那是**替上层把下一步选好了**,
# 现已去掉。现在回的是 observation(结构观察),由手机端的经验库/云端老师消费。
# 所以断言跟着换成:有没有给出**判断所需的材料**。
obs = r.get("observation") or {}
print("   观察 = %s" % (obs or "✗ 没给"))
check(isinstance(r.get("observation"), dict), "失败时带上了 observation")
check(obs.get("kind") in ("blind", "empty", "offscreen"), "kind 是三种之一", obs.get("kind"))
check(obs.get("target") == MISS, "target 就是要找的词", obs.get("target"))
check("hint" not in r, "不再替上层选好下一步(旧 hint 去掉了)", sorted(r.keys()))
# 顺带守隐私线:观察里不许有屏幕正文(它可能会被发去云端问老师)
check(not ({"lines", "text", "image"} & set(obs)), "观察里没有屏幕内容", sorted(obs.keys()))

# ---------------------------------------------------------------------------
print()
print("=== 总账 ===")
print("   这一轮一共点过 %d 次,全部落在屏幕内:%s"
      % (len(clicked), "是 ✓" if not bad else "**否,有 %d 次越界**" % len(bad)))
check(not bad, "红线:没有一次点击落在屏幕外", bad)
print()
print("   %s" % ("全部通过 ✓" if not fails else "**失败 %d 项**: %s" % (len(fails), fails)))
sys.exit(1 if fails else 0)
