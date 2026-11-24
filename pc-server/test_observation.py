# -*- coding: utf-8 -*-
"""失败时给的是「观察」而不是「结论」,而且**观察里不许有屏幕内容**。

背景:`click_ui` 找不到东西时,以前回的是
    {"ok": false, "error": "界面上没有叫「张三」的控件", "hint": "改用 click_element…"}

这句话有两个毛病:
  1. 它是个**结论**(而且是假话 —— 多半只是"不在这一屏")。拿到它的人除了放弃做不了别的。
  2. `hint` 替上层把下一步选好了。

现在回的是:
    {"ok": false, "error": "这一屏上没有「张三」",
     "observation": {"kind": "blind", "window": "微信", "target": "张三", "ocr_lines": 36}}

拿到 kind=blind + ocr_lines=36 的人能推出来:"这界面认得出字却没有目标 → 目标在更深处,
那就滚动,或者用它自己的搜索框。" —— 这就是判断所需的全部材料。

这个测试守两条线:
  A. **观察的结构是对的**(kind 判得对、字段齐)。
  B. **观察里没有任何屏幕内容** —— 它可能会被发去云端问"该怎么办",
     而屏幕文字里有聊天记录。这条比第一条重要。

鼠标键盘**全部打桩** —— 这台机器是用户正在用的桌面,不该被测试乱点乱打字。
"""
import sys

sys.path.insert(0, r"C:\Users\27014\phone-touchpad\pc-server")
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
import ai_tools
import uia

# observation 允许出现的键 —— 全是结构,没有一个是内容。
# 加字段就得改这里,这是**故意的摩擦**:逼人看一眼新字段会不会带出屏幕内容。
ALLOWED_KEYS = {"kind", "window", "target", "offscreen", "same_name", "ocr_lines"}

fails = []


def check(cond, label, detail=""):
    print("   %s %s%s" % ("✓" if cond else "✗", label,
                          "" if cond else "   ← " + str(detail)))
    if not cond:
        fails.append(label)


# ---------------------------------------------------------------------------
print("=== 1) 找不到时,回的是「观察」不是「结论」 ===")
clicked = []
r = ai_tools._click_ui("确认转账给张三", None, lambda x, y: clicked.append((x, y)))
print("   返回: ok=%s error=%s" % (r.get("ok"), r.get("error")))
obs = r.get("observation")
check(r.get("ok") is False, "失败时 ok=False")
check(isinstance(obs, dict), "带上了 observation", r.keys())

if isinstance(obs, dict):
    print("   observation = %s" % obs)
    check(obs.get("kind") in ("blind", "empty"), "kind 判定了", obs.get("kind"))
    check(obs.get("target") == "确认转账给张三", "target 就是用户要找的那个词")
    check("ocr_lines" in obs, "带了 ocr_lines(说明屏幕上有多少字)")
    check(obs.get("ocr_lines", 0) > 0, "屏幕上认得出字 → 所以是 blind 不是 empty",
          obs.get("ocr_lines"))

    print()
    print("   -- 旧的那句「界面上没有」应该没了 --")
    check("界面上没有" not in (r.get("error") or ""),
          "不再把「这一屏上没有」说成「界面上没有」", r.get("error"))
    check("hint" not in r, "不再替上层选好下一步(旧 hint 字段去掉了)", r.keys())

# ---------------------------------------------------------------------------
print()
print("=== 2) ★ 红线:观察里不许有屏幕内容 ===")
# 它会被发去云端问「该怎么办」,而屏幕文字里就有聊天记录。
if isinstance(obs, dict):
    bad = set(obs.keys()) - ALLOWED_KEYS
    check(not bad, "只含结构字段", bad)
    # 逐值检查:字符串只允许出现在**三个已知来源**上 ——
    # kind(封闭枚举)、window / target(窗口名和目标词,都不是屏幕正文)。
    # 别的字段一旦冒出字符串,就说明有人往观察里塞了内容,必须人工看一眼。
    FREE = ("window", "target")
    KINDS = ("offscreen", "blind", "empty")
    for k, v in obs.items():
        if not isinstance(v, str):
            continue
        if k in FREE:
            continue
        if k == "kind":
            check(v in KINDS, "kind 是封闭枚举里的值", v)
            continue
        check(False, "字段 %s 冒出个字符串,可疑(观察里不该有内容)" % k, v)
    check("lines" not in obs and "text" not in obs and "image" not in obs,
          "没有 OCR 原文 / 截图字段")

print()
print("   -- 对比:真跑一次 OCR,看看屏幕上确实有字(证明上面不是空手套) --")
lines, oraw = uia.ocr(limit=5)
print("   OCR 认出 %d 行(截前 5 行):%s"
      % (oraw.get("count", 0), [l.get("name") for l in lines[:3]]))
check(oraw.get("count", 0) > 0, "屏幕上确实有字,而这些字**没有**进 observation")
if isinstance(obs, dict):
    # ★ 只把 observation 的**字符串值**拼起来查,别用 str(obs):
    # str(obs) 里有键名和数字(比如 "ocr_lines": 0),而屏幕上可能恰好有行字是 "0"
    # → "0" in str(obs) 误报泄露。字符串值才是「真正能藏内容的地方」。
    #
    # ★★ 2026-10-06 修一处**假警报**:上面那句「字符串值才是能藏内容的地方」对
    #    `window` / `target` 不成立 —— 它俩**就是**屏幕上的字(窗口标题本来就是屏幕上
    #    读得出来的东西),上面第 79 行已经把它俩列进 `FREE` 白名单了,这里却还照着拼。
    #    于是只要 OCR 读到的某一行**是窗口标题的子串**,就一定报警:
    #    实测 `window` = 「**VRoid Hub** 和另外 3 个页面 - 个人 - Microsoft Edge」,
    #    而屏幕上正好有一行叫 `VRoid Hub` → 报「泄露」,可它一个字都没多给。
    #
    #    **用一个 OCR 永远不会撞上窗口标题的窗口去跑,才能让它变绿 —— 那不是修,是躲。**
    #    所以这里把白名单对齐(和上面同一个 `FREE`),查的仍是「真正能藏内容的地方」。
    #    ★ 至于窗口标题本身算不算敏感 —— 那是 `window` 这个字段**设计上就允许**的事
    #      (`_mark_where` 那一套:模型要知道自己在哪个窗口上动手),归那条线管,不归这条查。
    #    ★ 说白一句:这么改之后,这条查**基本被上面第 89 行那条盖住了**
    #      (`FREE` 之外的字段冒出字符串,本来就已经报错)。它留着只当第二张网 ——
    #      **别把它当成主要防线。**
    obs_text = " ".join(v for k, v in obs.items()
                        if isinstance(v, str) and k not in FREE)
    leaked = [l.get("name") for l in lines[:5]
              if l.get("name") and l["name"] in obs_text]
    check(not leaked, "observation 里找不到任何一行 OCR 文字", leaked)

# ---------------------------------------------------------------------------
print()
print("=== 3) 离屏那条路:kind 应该是 offscreen ===")
hits, raw = uia.find("Copy response to clipboard", window="Visual Studio Code", limit=3)
if hits:
    r3 = ai_tools._click_ui("Copy response to clipboard",
                            "Visual Studio Code", lambda x, y: clicked.append((x, y)))
    if r3.get("ok"):
        # ok=True 有两条路:直接命令控件(没碰鼠标),或 ScrollIntoView 滚进视野
        # 再点一下。后者会真的调 click_fn(这里是假的)。两种都算成功,
        # 所以这里只是「没走到失败分支」,不是失败。
        print("   (它这次成功了,没走到失败分支 —— via=%s,环境不同,跳过)"
              % r3.get("via"))
    else:
        o3 = r3.get("observation") or {}
        print("   ok=False  error=%s" % r3.get("error"))
        print("   observation = %s" % o3)
        check(o3.get("kind") == "offscreen", "kind=offscreen", o3.get("kind"))
        check(o3.get("offscreen") is True, "offscreen=True")
        check(set(o3.keys()) <= ALLOWED_KEYS, "还是只含结构字段", o3.keys())
else:
    print("   (VS Code 里没这个名字的控件 —— 换过窗口了,跳过)")

# ---------------------------------------------------------------------------
print()
print("=== 4) 两个新动词:滚动 与 搜索 ===")
scrolled = []
r = ai_tools.execute({"tool": "scroll", "args": {"direction": "down", "amount": "2"}},
                     scroll_fn=lambda n: scrolled.append(n))
print("   scroll down 2 → %s   实际发出=%s" % (r, scrolled))
check(r.get("ok") and scrolled == [-2], "向下滚发的是负值(Windows 约定)", scrolled)

scrolled.clear()
r = ai_tools.execute({"tool": "scroll", "args": {"direction": "up"}},
                     scroll_fn=lambda n: scrolled.append(n))
check(r.get("ok") and scrolled == [3], "不填 amount 默认滚 3", scrolled)

r = ai_tools.execute({"tool": "scroll", "args": {"direction": "斜着"}},
                     scroll_fn=lambda n: scrolled.append(n))
check(r.get("ok") is False and "up 或 down" in (r.get("error") or ""),
      "乱给方向要拒绝", r.get("error"))

print()
print("   -- search:打桩,绝不真发 Ctrl+F(那会动到用户的窗口)--")
sent_keys, typed = [], []
orig_hotkey = ai_tools._press_hotkey
# search 现在会给搜索词**验货**(2026-10-03 加的)。这一条测的是「search 有没有
# 发 Ctrl+F、有没有把词交给键盘」,不是「屏幕上真的出现了张三」—— 所以要把两件
# 仪器一起打桩,不然它会去抓真屏幕、如实回报「没看见张三」,测的就不是这里了。
# 仪器本身的行为钉在 test_type_verified.py 里。
orig_thumb, orig_ocr = ai_tools._screen_thumb, ai_tools._text_on_screen
try:
    ai_tools._press_hotkey = lambda k: (sent_keys.append(k), (True, ""))[1]
    ai_tools._screen_thumb = lambda: None
    ai_tools._text_on_screen = lambda text: True
    r = ai_tools.execute({"tool": "search", "args": {"query": "张三"}},
                         type_fn=lambda t: typed.append(t))
finally:
    ai_tools._press_hotkey = orig_hotkey
    ai_tools._screen_thumb, ai_tools._text_on_screen = orig_thumb, orig_ocr
print("   search 张三 → %s" % r)
print("   打桩记录: 按键=%s 输入=%s" % (sent_keys, typed))
check(r.get("ok"), "search 正常返回")
check(sent_keys == ["ctrl+f"], "发的是 Ctrl+F", sent_keys)
check(typed == ["张三"], "输的是要找的词", typed)
check("回车" not in str(r.get("via")) or "未回车" in str(r.get("via")),
      "没有替上层按回车(那是导航决定)", r.get("via"))

r = ai_tools.execute({"tool": "search", "args": {}}, type_fn=lambda t: None)
check(r.get("ok") is False, "不给词要拒绝")

# ---------------------------------------------------------------------------
print()
print("=== 5) 工具表登记了,而且只有这两个是新增的 ===")
for t in ("scroll", "search"):
    check(t in ai_tools.TOOLS, "TOOLS 里有 %s" % t)
sch = {f["function"]["name"] for f in ai_tools.tool_schema()}
check({"scroll", "search"} <= sch, "tool_schema 也导出了", sch)
check(ai_tools.TOOLS["scroll"].get("optional") == ["amount"],
      "scroll 的 amount 是可选(不填会逼模型每轮编一个)")
print("   全部工具: %s" % sorted(ai_tools.TOOLS.keys()))

# ---------------------------------------------------------------------------
print()
print("=== 6) ★ 红线:真的点了的话,一次都不许落在屏幕外 ===")
# 注意不是「零点击」—— 第 3 节如果走 ScrollIntoView 那条路,会真调 click_fn
#(这里是假的,不会动到用户鼠标)。真正要守的是:**坐标不许越界**。
# 离屏元素的坐标是虚拟滚动空间里的天文数字(实测 y=-64728),照点鼠标会飞出去。
SCREEN = (0, 0, 1920, 1080)
bad = [c for c in clicked
       if not (SCREEN[0] <= c[0] <= SCREEN[2] and SCREEN[1] <= c[1] <= SCREEN[3])]
print("   记录的(假)点击: %s" % clicked)
print("   越界的          : %s" % (bad if bad else "没有 ✓"))
check(not bad, "没有一次点击落在屏幕外", bad)
check(len(clicked) <= 2, "这个测试最多产生一两次假点击(没有到处乱点)", len(clicked))

print()
print("=== 总账 ===")
print("   %s" % ("全部通过 ✓" if not fails else "**失败 %d 项**: %s" % (len(fails), fails)))
sys.exit(1 if fails else 0)
