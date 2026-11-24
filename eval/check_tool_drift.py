#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""工具表漂移守卫:电脑说的和手机抄的是不是同一份。

## 它守的是什么

同一个工具词汇表在这个项目里**存了三份**:

    电脑  pc-server/ai_tools.py  TOOLS          ← 真正执行的那个,权威
    手机  AiAgent.kt             TOOL_SCHEMA    ← 给模型看的那份
    散文  AiAgent.kt             SYSTEM_PROMPT  ← 里面又把工具名写了一遍

加一个能力要改三处。漏改的表现是**静默少一块能力**:电脑明明会,模型永远不知道;
或者模型兴冲冲调一个工具,电脑回「不知道这个工具」。两种都不报错、不崩。

所以这个脚本拿**电脑那份**去对**手机那份**,不一致就退非零。

## 只比结构,不比描述文字

手机那份的**描述文字是为 4B 专门调过的**(eval 里一轮轮试出来的),电脑那份是给
人看的。把两边文字对齐是**退步**,不是修复。所以这里只比:

    名字集合 + 每个工具的参数名集合 + 必填集合

`SYSTEM_PROMPT` 那段散文暂时**不进这个守卫** —— 它没有结构,拿正则抠工具名
只会抠出一堆假阳性。散文的一致性靠 eval(`run_eval.py`)守:提示词说错了,
用例的命中率会掉。

## 不需要服务器、不需要手机

只读两个文件。定位:`sys.path.insert` 找 `pc-server`,再 import `ruoxi_prompt`。
"""
import io
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, ".."))
PC_SERVER = os.path.join(ROOT, "pc-server")

sys.path.insert(0, HERE)
sys.path.insert(0, PC_SERVER)

try:
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")
except Exception:
    pass

# ---------------------------------------------------------------------------
# ★ 手机独有的工具:只存在于手机端,电脑那份里**本来就没有**,不是漂移。
#
# 每加一条都要写清理由。这份清单最大的风险是**过期** —— 哪天 click_element
# 从手机上删了,这条豁免还在,就会把「手机删了工具、电脑没删」这种真漂移
# 悄悄放过去。所以下面有一条自检:豁免的名字必须真的在手机那份里。
# ---------------------------------------------------------------------------
PHONE_LOCAL = {
    # 用视觉模型在手机屏幕上找像素再点。「眼」在手机上,电脑没有这个能力。
    "click_element",
    # ★ 通用入口本身(2026-10-04 加的,见 AiAgent.kt 里那段长注释)。
    # 它们不是「电脑会的一件事」,是**去哪找手**的机制 —— 电脑那边没有才对,
    # 有才奇怪。它们指到的那些名字才是电脑的,而那个由**电脑自己报**上来
    # (`HAND`/`HARR` 那条签名命令),比一张烧死在 APK 里的表准。
    "list_hands",
    "use_hand",
    # ★★ 2026-11-06 补齐：这 9 个同样是**手机自己实现**的，以前漏了，
    # 于是每次连电脑对账都报一串假警报（「电脑根本不认」），读到第三次就没人看了。
    # 判据：电脑那份 TOOLS 里没有、而手机 dispatch 里有分支的，都该在这儿。
    "read_url",        # 手机上自己抓网页
    "delegate",        # 分头派活，手机侧编排层的事
    "skill_list", "skill_save", "skill_new", "skill_delete",
    "skill_note", "skill_ledger", "skill_run",
}

# ---------------------------------------------------------------------------
# ★ 电脑有、手机**故意不告诉模型**的可选参数。
#
# 这个方向和 PHONE_LOCAL 正好相反,但同样必须显式写下来 —— 因为它和真正的
# 漂移长得一模一样:电脑支持一个参数、手机那份里没有。区别只在于**一个是
# 决定,一个是遗漏**,而这台机器分不出来。所以让人来分,并且留下理由。
#
# 全是可选参数(不在 required 里):不暴露的代价只是「模型用不上这个能力」,
# 不是「模型调了必被拒」。★ 必填参数**没有豁免** —— 那个方向漏了就是硬故障。
#
# 同样有过期自检:电脑那边哪天删了这个参数,这里的豁免就得跟着删。
# ---------------------------------------------------------------------------
PC_ONLY_PARAMS = {
    ("click_ui", "window"):
        "窗口归属由服务端的任务窗口守门者兜(_guard_focus/_ocr_scope),不靠模型点名。"
        "放出来等于请模型猜窗口名 —— 正是「命中必须验窗口归属」那轮要堵的洞。",
    ("set_value", "window"):
        "和 click_ui 同一条理由:窗口归属靠服务端守门「先当前窗口、再全桌面」,"
        "不靠模型点名。放出来等于请 4B 猜窗口名 —— 而它写的是**覆盖**别人的输入框,"
        "猜错窗口比猜错点击更难收场。",
    ("list_ui", "window"):
        "同上。注意手机那份的 read_screen **有** window:要读哪个窗口的字是要点,"
        "要点哪个窗口的控件不是。",
    ("scroll", "amount"):
        "默认 3 够用。给它一个 1~10 的数字,4B 会稳定地挑 10(滚过头比没滚更难收场)。",
}

FAILS = []
NOTES = []


def check(name, cond, extra=""):
    print("  %s %s %s" % ("OK  " if cond else "FAIL", name, extra))
    if not cond:
        FAILS.append(name)
    return cond


def note(msg):
    NOTES.append(msg)
    print("  .. %s" % msg)


def canon(tools):
    """→ {名字: (参数名 frozenset, 必填 frozenset)}。

    只取结构。**刻意不取 description** —— 两边的描述文字是为不同读者写的,
    对齐它们是退步。
    """
    out = {}
    for t in tools:
        f = t["function"]
        p = f.get("parameters") or {}
        out[f["name"]] = (frozenset((p.get("properties") or {}).keys()),
                          frozenset(p.get("required") or ()))
    return out


def main():
    import ai_tools
    import ruoxi_prompt

    print("== 取两份工具表 ==")
    pc_tools = ai_tools.tool_schema(for_model=True)
    pc = canon(pc_tools)
    _sp, phone_tools = ruoxi_prompt.load()
    phone = canon(phone_tools)
    note("电脑(模型可见) %d 个 / 手机 %d 个" % (len(pc), len(phone)))

    # 隐藏工具不该出现在任何一份给模型看的表里 —— 尤其手机那份,
    # 混进去模型就会拿到像素原语(它看不见图)。
    hidden = sorted(n for n, s in ai_tools.TOOLS.items() if s.get("model") is False)
    stray = sorted(set(hidden) & set(phone))
    check("隐藏工具(%s)没出现在手机那份里" % ",".join(hidden) or "(无)", not stray, stray)

    # 豁免清单的自检 —— 防它过期。
    stale = sorted(PHONE_LOCAL - set(phone))
    check("★ 豁免清单没过期(每条都真的还在手机那份里)", not stale,
          "这些已经不在手机端了,该删掉豁免: %s" % stale)

    print("== 名字集合 ==")
    expected_phone = set(pc) | PHONE_LOCAL
    only_pc = sorted(expected_phone - set(phone))   # 电脑会、手机没抄
    only_phone = sorted(set(phone) - expected_phone)  # 手机有、电脑没有 → 调了必被拒
    # ★ 这一条**故意**比真机上那份严(ToolDrift.kt 里同一个方向是 warn,不是 bad)。
    #
    # 同样一个事实,两个时刻,修它的代价差一个数量级:
    #   · 在这儿 —— 源文件就在手边,补进 TOOL_SCHEMA 是**零成本**,三十秒。
    #   · 真机上 —— 已经烧进 APK、装到手机上了,下次重装才生效。
    #
    # 而且真机上那个方向**确实不算坏**:`list_hands` 是当场问电脑要的菜单,
    # 新工具自己会冒出来,`use_hand` 照样调得到,只是要多绕一轮对话。
    # 所以那边叫 warn 是诚实的,这边叫 FAIL 也是诚实的 —— **别去「统一」这两个**。
    check("电脑有的,手机都抄到了(源文件就在手边,现在补零成本)",
          not only_pc, only_pc)
    check("手机有的,电脑都认", not only_phone, only_phone)

    # 豁免清单的自检(同上,防过期)。
    gone = sorted(k for k in PC_ONLY_PARAMS
                  if k[0] not in pc or k[1] not in pc[k[0]][0])
    check("★ 参数豁免清单没过期(电脑那边还真的有这个参数)", not gone,
          "这些参数电脑已经没有了,该删掉豁免: %s" % gone)

    # 豁免的硬前提:**只能是可选参数**。必填参数漏在手机那份外面 = 模型永远
    # 填不出这个字段 = 调了必失败。这个方向不许有嘴可以堵。
    bad_exempt = sorted(k for k in PC_ONLY_PARAMS
                        if k[0] in pc and k[1] in pc[k[0]][1])
    check("★ 豁免的都只是可选参数(必填参数一个都不许豁免)", not bad_exempt, bad_exempt)

    print("== 参数名 + 必填集合(逐工具) ==")
    for name in sorted(set(pc) & set(phone)):
        p_props, p_req = pc[name]
        h_props, h_req = phone[name]
        # 方向一:**手机不许漏掉电脑需要的参数**。漏了 = 模型拿不到那个能力,
        # 而豁免名单里写了理由的除外。
        missing = sorted(p_props - h_props - {k[1] for k in PC_ONLY_PARAMS if k[0] == name})
        check("%-14s 没漏掉电脑需要的参数" % name, not missing, missing)
        # 方向二:**手机不许编一个电脑不认的参数**。编了 = 模型调了必被拒。
        extra = sorted(h_props - p_props)
        check("%-14s 没编出电脑不认的参数" % name, not extra, extra)
        check("%-14s 必填一致" % name, p_req == h_req,
              "电脑 %s / 手机 %s" % (sorted(p_req), sorted(h_req)))
        # 没暴露的可选参数**报一声** —— 它是决定不是遗漏,但值得看得见。
        for k, reason in sorted(PC_ONLY_PARAMS.items()):
            if k[0] == name and k[1] in p_props:
                note("%s.%s 故意没给模型看:%s" % (name, k[1], reason))

    print()
    if NOTES:
        print("说明:")
        for m in NOTES:
            print("  - %s" % m)
    if FAILS:
        print()
        print("漂移 %d 项: %s" % (len(FAILS), FAILS))
        print()
        print("怎么办:上面每条 FAIL 都写着两边分别是什么。真源是电脑的")
        print("pc-server/ai_tools.py::TOOLS;改完回 AiAgent.kt 的 TOOL_SCHEMA 对齐")
        print("(只对齐名字/参数/必填,**别动描述文字** —— 手机那份是为 4B 调过的)。")
        print("手机独有的工具(不在电脑上的)写进本文件的 PHONE_LOCAL 并写明理由。")
        sys.exit(1)
    print("工具表没有漂移")


if __name__ == "__main__":
    main()
