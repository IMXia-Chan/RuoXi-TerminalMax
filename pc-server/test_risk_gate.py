#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""三层风险闸 · **第二层**(电脑侧动作级兜底)的自测。

## 这一层守的是什么

手机侧第一层判的是**用户说的那句话**;这一层判的是**动手那一瞬间电脑上的事实** ——
焦点在谁身上、要点的那个控件叫什么。

第一层会漏,而且是**明知**的漏:「把这个付了」里既没有名单里的词、也没有金额。
抓得住的只有动手这一刻 —— 那一下真正点的按钮叫「确认支付」。

## 为什么这个文件是纯逻辑测试,不开任何窗口

`risk_reason()` 是**纯函数**,事实由调用方查好传进来。所以「焦点在支付宝上会怎样」
可以在这里直接喂进去,**不用真的开一个支付宝**。这个项目里「测不了的东西等于不可信」,
而这一层是承重墙 —— 它必须能被钉死。

## 两组代价(两个方向都钉)

| 判错的方向 | 后果 |
|---|---|
| 该拦的没拦 | ★ **钱直接就出去了**,没有任何一层会接住 |
| 不该拦的拦了 | 他每次点击都被问一次 → 嫌烦 → **把整个功能关掉** |

第二条不是体验问题:一条会被关掉的安全功能等于没有。所以「日常那些**不许**被拖下水」
那一组和「该拦的」一样要紧。

不用 `--live`,不碰鼠标键盘,不起服务器。
"""
import io
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

# 控制台可能是 GBK(cp936),直接 print 中文/符号会 UnicodeEncodeError;统一切到 UTF-8。
try:
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")
except Exception:
    pass

import ai_tools

FAILS = []


def check(name, cond, extra=""):
    print("  %s %s %s" % ("OK  " if cond else "FAIL", name, extra))
    if not cond:
        FAILS.append(name)
    return cond


def reason(tool, args=None, proc="", title=""):
    return ai_tools.risk_reason(tool, args or {}, proc, title)


# ===========================================================================
# 一、焦点在「本身就是钱」的应用上 —— 做**任何**事都要问
# ===========================================================================
print("\n[1] 焦点在钱的应用上:碰鼠标键盘的都要问")


def test_risky_app_blocks_all_mutating():
    apps = [
        ("Alipay.exe", "支付宝"), ("WeChatPay.exe", "云闪付"),
        ("ABC.exe", "中国农业银行 - 网上银行"), ("x.exe", "招商银行个人网银"),
        ("ths.exe", "同花顺 - A股"), ("chrome.exe", "某某银行 信用卡还款"),
    ]
    for proc, title in apps:
        for tool in ("click_at", "click_ui", "type", "hotkey", "search", "scroll"):
            r = reason(tool, {"name": "确定"}, proc, title)
            check("%s @ %s 被拦" % (tool, proc), bool(r), (r or "")[:28])
    return True


test_risky_app_blocks_all_mutating()

print("\n[2] ★★ 日常那些**不许**被拖下水(名单分界,防止有人「顺手」加进去)")
# 微信/淘宝/浏览器**本身不做钱的事** —— 危险的是里面某一个按钮,由控件名那一关抓。
# 把应用名也收进来的话,他每天发消息每一步都要点头,功能当天就会被关掉。
for proc, title in [("Weixin.exe", "微信"), ("chrome.exe", "淘宝 - 搜索"),
                    ("chrome.exe", "京东"), ("Notepad.exe", "未命名 - 记事本"),
                    ("msedge.exe", "百度一下")]:
    r = reason("click_ui", {"name": "确定"}, proc, title)
    check("%s 焦点下点「确定」不问" % proc, r is None, (r or "")[:28])

# 名单纪律:它必须是**元组**(不可变)。写成 list/set 的话,以后任意一处 `.append` /
# `.add` 就能改它 —— 而「能被外部内容改的名单等于没有名单」(Aider CVE-2026-85674)。
check("RISKY_APPS 是元组(不可变)", isinstance(ai_tools.RISKY_APPS, tuple))
check("RISKY_WORDS 是元组(不可变)", isinstance(ai_tools.RISKY_WORDS, tuple))
check("RISKY_APPS 非空", len(ai_tools.RISKY_APPS) > 0)

# ===========================================================================
# 三、控件名那一关 —— 点那一下本身收不回来
# ===========================================================================
print("\n[3] 控件名里是钱 / 是送出去 / 是不可逆的,要问")
for name, word in [("确认支付", "支付"), ("立即付款", "付款"), ("转账", "转账"),
                   ("立即购买", "立即购买"), ("提交订单", "提交订单"),
                   ("发送", "发送"), ("删除文件", "删除"), ("卸载", "卸载"),
                   ("开通会员", "开通"), ("确认收货", "确认收货")]:
    r = reason("click_ui", {"name": name}, "chrome.exe", "某个普通网页")
    check("点「%s」被拦" % name, bool(r) and word in r, (r or "")[:30])

print("\n[4] ★ 日常控件名不许被拖下水")
# 这些是他天天点的,一个都不许拦 —— 否则「点一下都要问」,功能会被关掉。
for name in ["确定", "取消", "搜索", "登录", "用户名", "下一首", "返回",
             "最小化", "刷新", "新建标签页", "输入框", "关闭", "保存"]:
    r = reason("click_ui", {"name": name}, "chrome.exe", "某个普通网页")
    check("点「%s」不问" % name, r is None, (r or "")[:30])

print("\n[5] 原因里只回命中的那个词,**不回显标题/整段控件名**")
# 窗口标题可能是对方的昵称/一整段正文,控件名可能是别的东西 —— 都不该进日志和回执。
r = reason("click_ui", {"name": "确认支付"}, "chrome.exe", "张三 的私聊 - 微信")
check("原因里有「支付」", bool(r) and "支付" in r)
check("原因里没有整个标题", bool(r) and "张三" not in r, (r or "")[:40])
check("原因里没有整个控件名", bool(r) and "确认支付" not in r, (r or "")[:40])

# ===========================================================================
# 六、不改变电脑上东西的工具 —— 一个都不问
# ===========================================================================
print("\n[6] 只读的、切焦点的、看像素的:都不问")
for tool in ["list_windows", "get_state", "screenshot", "list_ui", "read_screen",
             "open_app", "focus_window", "media", "list_hands"]:
    r = reason(tool, {"name": "确认支付"}, "Alipay.exe", "支付宝")
    check("「%s」不问(即使焦点在支付宝上)" % tool, r is None, (r or "")[:30])

print("\n[7] ★ 明知会漏的那几种:点坐标的问不出来(由别的层接住)")
# `click_at` 没有控件名可判,「坐标 500,300 是不是支付按钮」猜不出来。
# 硬猜的代价是**该拦的没拦** —— 所以它在这一层就是漏的,归「焦点在谁身上」
# 和手机侧第一层接住。**这条测试的用处是防止有人把它改成「一律拦」**:
# 那样会让每一次点坐标都弹框,而点坐标是最高频的动作。
check("click_at 在普通应用上不问", reason("click_at", {"x": 500, "y": 300},
                                       "chrome.exe", "某个网页") is None)
check("click_at 在银行上照问", bool(reason("click_at", {"x": 500, "y": 300},
                                        "ABC.exe", "中国农业银行")))

# ===========================================================================
# 八、★★ 名单守卫 —— 新写工具不进名单 = 完全没有守门
# ===========================================================================
print("\n[8] ★★ 守焦点的工具,一个都不能漏过风险闸")
# `_GUARDED_TOOLS` 是白名单,新工具不进去就完全没有守门 —— 这个亏项目已经吃过一次。
# 所以这里反过来钉一条:**凡是要过焦点闸的(会动鼠标键盘的),都必须过风险闸。**
for tool in ai_tools._GUARDED_TOOLS:
    check("「%s」也在 _MUTATING_TOOLS 里" % tool, tool in ai_tools._MUTATING_TOOLS)
# set_value 2026-11-06 真做出来了（ValuePattern 直写 + 读回核对）。这一条现在
# 不再是「别忘了」的提示，而是**实打实的守门**：它真会改电脑上的东西。
check("set_value 在 _MUTATING_TOOLS 里", "set_value" in ai_tools._MUTATING_TOOLS)

# ===========================================================================
# 九、★★★ `confirmed` 只能来自手机侧代码,**模型碰不到**
# ===========================================================================
print("\n[9] ★★★ 模型把 confirmed 塞进 args 不算数")
# 这一层是「用户用手指点过确认」的唯一凭证。它只认 **call 的顶层**、而且必须是**布尔 True**。
# 模型能填的只有 `args`(payload 里 {"tool","args","id"})—— 它在 args 里怎么写都够不着。
#
# 测法:把「控件名危险」那一关**临时钉成永远命中**,这样能干净地只测这条接线,
# 而不用真的去点一个支付按钮。名字用一个几乎不可能存在的字符串,所以就算真跑到了
# `_click_ui`,也什么都找不到、什么都点不到。


_orig_risky_control = ai_tools._risky_control
ai_tools._risky_control = lambda tool, args: "支付"     # ← 假装这一下永远危险
BOGUS = "__risk_gate_test_不存在的控件__"


def gate(code):
    """跑一次 execute,只回「风险闸放没放行」。"""
    r = ai_tools.execute({"tool": "click_ui", "args": {"name": BOGUS}, **code})
    return r if isinstance(r, dict) else {}


try:
    r = gate({})
    check("不 confirmed → 拦下", r.get("blocked_by") == "needs_confirm", str(r)[:40])
    check("回执里带一句人话", bool(r.get("error")))
    check("回执是 ok:false", r.get("ok") is False)

    r = gate({"confirmed": True})
    check("★ 顶层 confirmed:true → 放行(不再是 needs_confirm)",
          r.get("blocked_by") != "needs_confirm", str(r)[:60])

    # ↓↓↓ 这三条才是这一节的重点 ↓↓↓
    r = gate({"args": {"name": BOGUS, "confirmed": True}})
    check("★★★ 模型塞在 args 里的 confirmed **不算数**",
          r.get("blocked_by") == "needs_confirm", str(r)[:60])
    r = gate({"confirmed": "yes"})
    check("★ 字符串 \"yes\" 不算数(必须真布尔)",
          r.get("blocked_by") == "needs_confirm", str(r)[:60])
    r = gate({"confirmed": 1})
    check("★ 数字 1 不算数(必须真布尔)",
          r.get("blocked_by") == "needs_confirm", str(r)[:60])
finally:
    ai_tools._risky_control = _orig_risky_control

print("\n[10] 两道闸都要过,而且焦点闸在前面")
# 一个问「该不该做」(风险闸),一个问「会不会打到别的窗口去」(焦点闸)。
# ★ 次序是有意的:焦点被抢走时该说的是「字可能打到别的窗口」,不是「等用户确认」——
#   把这两句说反了,他点完确认还是会失败,而原因根本不是他以为的那个。
_orig_guard = ai_tools._guard_focus
ai_tools._guard_focus = lambda: "焦点跑到别的窗口去了(测试)"
try:
    r = gate({"confirmed": True})          # 风险闸已放行,焦点闸应当拦下
    check("焦点闸拦下时,理由不是 needs_confirm",
          r.get("blocked_by") != "needs_confirm", str(r)[:60])
    check("焦点闸的理由原样透出来",
          "焦点" in str(r.get("error", "")), str(r)[:60])
finally:
    ai_tools._guard_focus = _orig_guard


# ===========================================================================
print("")
if FAILS:
    print("失败 %d 条:" % len(FAILS))
    for f in FAILS:
        print("  - %s" % f)
    sys.exit(1)
print("风险闸(第二层)自测:全部通过")
