# -*- coding: utf-8 -*-
"""验收 OCR 兜底层:拿微信(Weixin.exe)当靶子。

微信是 Qt/DirectUI 自绘,整个窗口 UIA 只暴露 1 个后代元素 —— 按名字找控件
那条路在它面前是瞎的,过去只能落到手机上那个 215 秒的视觉模型。
这一层要证明的就是:UIA 瞎了,OCR 接得住。

点击一律走**记录器**,不真点 —— 这是用户正在用的桌面。
"""
import sys
import time

sys.path.insert(0, r"C:\Users\27014\phone-touchpad\pc-server")
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
import ai_tools
import uia

clicked = []


def rec(x, y):
    clicked.append((x, y))


WX = "微信"
ai_tools.focus_window(WX)
time.sleep(1.2)
print("前台现在是:", ai_tools._foreground_window())
print()

print("=== 1) UIA 看微信:能看见几个可点控件? ===")
r = ai_tools.execute({"tool": "list_ui", "args": {"window": WX}}, click_fn=rec)
print("   ok=%s  count=%s" % (r.get("ok"), r.get("count")))
print("   %s" % (r.get("text") or r.get("error"))[:140])
# ★ 微信今天没开的话,整个文件都在**空转** —— 后面每一段都会「通过」,但它一个字都没验。
#   照 test_click_targets 的老规矩把这句说出来:**空过不是通过**。
WX_UP = r.get("ok") is True
if not WX_UP:
    print("   ⚠ **微信没开(或没找到窗口)→ 这一轮是空过,不是通过。**")
    print("     要真的验这条基准线,先把微信拉起来再跑一次。")
print()

print("=== 2) OCR 看同一个窗口 ===")
t = time.time()
lines, raw = uia.ocr(window=WX)
ms = (time.time() - t) * 1000
print("   ok=%s  %d 行  %.0fms  窗口名=%r" % (raw.get("ok"), len(lines), ms, raw.get("window")))
for e in lines[:22]:
    print("     (%4d,%4d) w=%4d  %r" % (e["cx"], e["cy"], e["w"], uia._tidy(e["name"])[:36]))
print()

print("=== 3) 拿 OCR 认出来的短标签走 click_ui(点击用记录器)===")
cands = [e for e in lines if 2 <= len(uia._squash(e["name"])) <= 6]
if not cands:
    print("   这块界面上没有短标签可试(换个标签页/会话再试)")
else:
    for c in cands[:3]:
        tgt = uia._tidy(c["name"])
        # ★ 名字里带危险词(发送/提交/删除…)的先跳过 —— 那一下会被动作级风险闸
        #   在**找之前**拦下,这一段就证明不了「OCR 接得住」了。跳过的要**说出来**,
        #   静默少测一个 = 这条基准线悄悄变短。
        if ai_tools._risky_control("click_ui", {"name": tgt}):
            print("   (跳过 %r:它的名字命中风险闸,那一下不该由这一段来点)" % tgt)
            continue
        before = len(clicked)
        t = time.time()
        r = ai_tools.execute({"tool": "click_ui", "args": {"name": tgt, "window": WX}},
                             click_fn=rec)
        ms = (time.time() - t) * 1000
        print("   目标 %-8r %6.0fms → ok=%s via=%s clicked=%s control=%r"
              % (tgt, ms, r.get("ok"), r.get("via"), r.get("clicked"), r.get("control")))
        if r.get("also_found"):
            print("        其它候选:%s" % r["also_found"])
        if len(clicked) > before and not r.get("clicked"):
            print("        ⚠ 报了失败却点了,危险")
print()

print("=== 4) 屏幕上没有的词,绝不能瞎点 ===")
if not WX_UP:
    print("   **空过**:微信没开,这一段没验成(不是通过)。")
else:
    before = len(clicked)
    t = time.time()
    # ⚠️ 探针词**不许带危险词**(转账/支付/发送/删除…):带了会先命中动作级风险闸,
    #    execute 在**找之前**就回 needs_confirm —— 这一段要测的是「OCR 接得住微信」,
    #    被那道闸拦下的话,下面每一行都会退化成 None,而 print 出来的照样像"通过"。
    MISS = "确认交给他张三"
    r = ai_tools.execute({"tool": "click_ui", "args": {"name": MISS, "window": WX}},
                         click_fn=rec)
    print("   %.0fms → ok=%s error=%s"
          % ((time.time() - t) * 1000, r.get("ok"), r.get("error")))
    print("   偷偷点了吗?%s" % ("**点了,危险**" if len(clicked) > before else "没有 ✓"))
    # 旧的 `hint`(「改用 click_element…」)已去掉 —— 那替上层选好了下一步。
    # 现在回 observation:自绘窗口(微信 UIA 树几乎是空的)正好是 kind=blind 的典型,
    # 手机端经验库拿这个 kind 去查「该用哪个动词」。
    _obs = r.get("observation") or {}
    print("   给了观察吗?%s" % (_obs or "✗ 没有 ← **这条基准线断了,下面几行不算数**"))
    print("      kind=%s  window=%s  ocr_lines=%s"
          % (_obs.get("kind"), _obs.get("window"), _obs.get("ocr_lines")))
    print("      旧 hint 去掉了吗?%s" % ("是 ✓" if "hint" not in r else "✗ 还在"))
    # ★ 判据带上 `_obs` 非空 —— 少了它,观察缺失时这一行会印出一个**假的「是 ✓」**
    #   (`not (空集 & set({}))` = True)。这个文件没有退出码机制,那就别在这里说谎。
    print("      观察里没有屏幕内容?%s"
          % ("是 ✓" if _obs and not ({"lines", "text", "image"} & set(_obs))
             else "**✗ 没有可判的观察**"))
print()
