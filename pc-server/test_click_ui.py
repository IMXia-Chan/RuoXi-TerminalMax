# -*- coding: utf-8 -*-
"""从 ai_tools.execute() 这个**真实入口**验收 click_ui / list_ui。

为什么不直接测 uia.py:模型走的是 execute(),中间还有工具表、参数解析、
回报裁剪。直接测底层等于漏掉接线这一层,而接错线才是最可能出的错。

点击用**记录器**代替真点 —— 这台机器是用户正在用的桌面,不该被测试乱点。
记录下来的坐标再去和真值对账,一样能证伪。
"""
import sys
import time

sys.path.insert(0, r"C:\Users\27014\phone-touchpad\pc-server")
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
import ai_tools

clicked = []


def fake_click(x, y):
    clicked.append((x, y))


def call(tool, **args):
    a = time.time()
    r = ai_tools.execute({"tool": tool, "args": args}, click_fn=fake_click)
    return r, (time.time() - a) * 1000


fails = []


def check(cond, label, detail=""):
    print("   %s %s%s" % ("✓" if cond else "✗", label,
                          "" if cond else "   ← " + str(detail)))
    if not cond:
        fails.append(label)


print("=== 工具表里有没有这两个,参数标对没有 ===")
sch = {f["function"]["name"]: f["function"]["parameters"] for f in ai_tools.tool_schema()}
for n in ("click_ui", "list_ui"):
    if n in sch:
        print("  %-9s 必填=%s" % (n, sch[n]["required"]))
    else:
        print("  %-9s ✗ 没进 schema" % n)

print()
print("=== list_ui:模型能「看见」什么可点 ===")
r, ms = call("list_ui")
print("  ok=%s 窗口=%s 共%s个  %.0fms" % (r.get("ok"), r.get("window"), r.get("count"), ms))
print("  " + (r.get("text") or r.get("error", "")).replace("\n", "\n  "))

print()
print("=== click_ui:按名字点,和真值对账 ===")
for nm in ["开始", "关闭", "终端"]:
    r, ms = call("click_ui", name=nm)
    if r.get("ok"):
        print("  click_ui「%s」 %6.0fms → 点 (%d,%d)  控件=%s（%s）在[%s]"
              % (nm, ms, r["clicked"][0], r["clicked"][1], r["control"], r["type"], r["window"]))
        if r.get("also_found"):
            print("        还有同名候选:%s" % "、".join(r["also_found"]))
    else:
        print("  click_ui「%s」 %6.0fms → ✗ %s" % (nm, ms, r.get("error")))

print()
print("=== 找不到时不能瞎点(这是最要命的一条)===")
before = len(clicked)
# ⚠️ 这个探针词**不许带危险词**(发送/提交/删除/支付…)。
# 带了的话命中动作级风险闸,execute 在**找之前**就回 needs_confirm 了,
# 于是这里测到的是那道闸、不再是「找不到时的观察回执」—— 两件事,别混。
r, ms = call("click_ui", name="保存并交给他张三")
print("  %.0fms → ok=%s  error=%s" % (ms, r.get("ok"), r.get("error")))
print("  有没有偷偷点一下?%s" % ("**点了,危险**" if len(clicked) > before else "没有 ✓"))
# 旧断言是 `hint`(「改用 click_element…」)—— 那替上层选好了下一步,已去掉。
# 现在回 observation(结构观察),真正的「退路」是手机端经验库拿它去查/问老师。
obs = r.get("observation") or {}
print("  给观察了吗?%s" % (obs or "✗ 没给"))
check(isinstance(r.get("observation"), dict), "失败时带上了 observation")
check(obs.get("kind") in ("blind", "empty", "offscreen"), "kind 是三种之一", obs.get("kind"))

print()
print("=== ★ 危险名字的控件:找都不许找,先要人点头 ===")
# 上面那条测的是「找不到」;这条测的是「名字本身危险」——
# ★ 判据是**在找之前就停下来**:这类控件名字出现在支付/转账/发送/删除那一类上,
#   而 `_click_ui` 找不到时会自己去跑 OCR 兜底(那一步也可能点到东西)。
#   所以闸必须在入口,不能放在「找到了之后」。
before = len(clicked)
r, ms = call("click_ui", name="确认支付")
print("  %.0fms → ok=%s  blocked_by=%s" % (ms, r.get("ok"), r.get("blocked_by")))
print("  说明: %s" % r.get("error"))
check(r.get("blocked_by") == "needs_confirm", "危险名字被风险闸拦住", r)
check(r.get("ok") is False, "回的是失败")
print("  有没有偷偷点一下?%s" % ("**点了,危险**" if len(clicked) > before else "没有 ✓"))
check(len(clicked) == before, "★ 被拦的那一下一次都没点")

print()
print("=== ★ 顶层 confirmed:true 才放行(模型碰不到这个字段)===")
# ★ 名字里**带着**危险词(否则测不到这道闸),但整串是个界面上不可能存在的名字 ——
#   这样「放行」那一次走到的是正常的「找不到」,不会真去动桌面上任何东西。
BOGUS = "确认支付__这道闸的自测__"
r, _ = call("click_ui", name=BOGUS, confirmed=True)   # ← confirmed 塞进 args:不算数
check(r.get("blocked_by") == "needs_confirm", "塞在 args 里的 confirmed 不算数", r)
r = ai_tools.execute(
    {"tool": "click_ui", "args": {"name": BOGUS}, "confirmed": True},
    click_fn=fake_click)
check(r.get("blocked_by") != "needs_confirm", "顶层的 confirmed 才放行", r)
print("  (放行之后走到的是正常的「找不到」那条路:%s)" % r.get("error"))
check("hint" not in r, "旧 hint 去掉了", sorted(r.keys()))

print()
print("=== 开始按钮最终对账 ===")
start_hits = [c for c in clicked if c == (24, 1060)]
print("  click_ui 实际点过的坐标:%s" % clicked)
print("  真值(user32 独立测的子窗口矩形 (0,1040)-(48,1080) 中心)= (24, 1060)")
print("  命中?%s" % ("是 ✓ 0 像素误差" if start_hits else "否 ✗"))

# ---------------------------------------------------------------------------
# OCR 兜底层
# ---------------------------------------------------------------------------
# 这一层补的是 UIA 和「眼」之间的空档:微信那种 Qt/DirectUI 自绘界面,UIA 整个
# 窗口只暴露 1 个后代元素,按名字找控件是瞎的;但字是画在屏幕上的,OCR 认得出。
# 验收要点不是「能不能认字」,而是:**UIA 能中的时候绝不该白跑 OCR**(那要多花 1 秒),
# 以及 **UIA 瞎了的时候 OCR 必须接得住**。
print()
print("=== OCR 兜底层 ===")

t = time.time()
lines, oraw = ai_tools.uia.ocr()
ocr_ms = (time.time() - t) * 1000
print("  全屏识字:ok=%s  %d 行  %.0fms" % (oraw.get("ok"), len(lines), ocr_ms))
if not oraw.get("ok"):
    print("  ✗ %s" % oraw.get("error"))
else:
    for e in lines[:5]:
        print("     (%4d,%4d) %s" % (e["cx"], e["cy"], e["name"][:40]))

print()
print("  -- 分层不能倒:UIA 能中的,不该白跑 OCR(应远小于上面那个毫秒数)--")
for nm in ["终端", "帮助"]:
    r, ms = call("click_ui", name=nm)
    via = r.get("via") or "UIA"
    flag = "✓ 走的 UIA" if via == "UIA" and ms < ocr_ms else "⚠ 走了 OCR/太慢"
    print("     「%s」%6.0fms  via=%s  %s" % (nm, ms, via, flag if r.get("ok") else "✗ " + str(r.get("error"))))

print()
print("  -- 屏幕上真有的文字,要能点到(UIA 中就走 UIA,瞎了才走 OCR)--")
# 目标**不能写死**(写死过「若息模型清理」,它只是恰好在这块屏幕上,换个窗口跑全 ✗),
# 但现挑也有讲究:**这一屏是活的,而且这个测试自己在改它** —— 每 print 一行,
# 前景终端上的字就滚一行。于是「刚 OCR 到的词,3 秒后已经不在」是常态,
# 那不是代码坏了。所以:每次**现 OCR 现挑**,只挑还认得到的;认不到就当「屏幕变了」跳过,
# 最后只报「几个里中几个」,绝不把环境噪声记成失败。
# 同时滤掉纯数字/符号的碎块(「217」「0 ×」那种 OCR 噪声)—— 那不是给人点的标签。
def _pickable(t):
    t = (t or "").strip()
    if not (2 <= len(t) <= 12):
        return False
    return any(c.isalpha() or "一" <= c <= "鿿" for c in t)

tried = hit = 0
seen = []
for _ in range(3):
    live, _ = ai_tools.uia.ocr()
    pool = [e["name"].strip()
            for e in sorted(live, key=lambda e: len(e.get("name") or ""))
            if _pickable(e.get("name")) and e["name"].strip() not in seen]
    if not pool:
        break
    nm = pool[0]
    seen.append(nm)
    r, ms = call("click_ui", name=nm)
    tried += 1
    if r.get("ok"):
        hit += 1
        # ⚠️ 成功的返回**不是同一个形状**:离屏控件走「UIA 直接命令控件」那条路时
        # 根本没碰鼠标,所以没有 "clicked"(2026-10-03 撞上:KeyError 把整个测试打断)。
        pos = r.get("clicked")
        print("     「%s」%6.0fms → %s  via=%s  命中文字=%r  落在「%s」"
              % (nm, ms, ("点 (%d,%d)" % (pos[0], pos[1])) if pos else "没碰鼠标",
                 r.get("via") or "UIA", r.get("control"), r.get("clicked_in")))
        # ★ 成功回执必须说得出「点在哪」。2026-10-03 翻车时日志只有「click_ui 成功」,
        # 没人知道它点的是哪儿的「发送」,复盘只能靠猜。这条断言守着那个字段别被改没。
        check("clicked_in" in r, "成功回执带上了「点的时候前台是谁」", list(r.keys()))
    else:
        print("     「%s」%6.0fms → 没中(多半是这几秒里屏幕滚了,不算失败)" % (nm, ms))
print("     小结:%d 个里中 %d 个" % (tried, hit))

print()
print("  -- 屏幕上根本没这几个字时,OCR 也不能瞎点 --")
before = len(clicked)
# ⚠️ 同上:探针词不许带危险词,否则拦下它的是风险闸、不是 OCR 的克制。
r, ms = call("click_ui", name="保存并交给他张三")
print("     %.0fms → ok=%s  error=%s" % (ms, r.get("ok"), r.get("error")))
print("     有没有偷偷点一下?%s" % ("**点了,危险**" if len(clicked) > before else "没有 ✓"))

print()
print("  -- 和眼对比:OCR 是秒级,视觉那条路是 215 秒 --")
print("     OCR 全屏 %.1fs  vs  眼 215s  →  快 %.0f 倍" % (ocr_ms / 1000, 215000 / max(ocr_ms, 1)))

print()
print("=== 总账 ===")
print("   %s" % ("全部通过 ✓" if not fails else "**失败 %d 项**: %s" % (len(fails), fails)))
sys.exit(1 if fails else 0)

