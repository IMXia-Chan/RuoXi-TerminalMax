#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""「值的样子」—— **出门之前把不该出去的东西拿掉**的自测。

## 这一层守的是哪一条路

`read_screen`(OCR)今天把屏幕上的**原文**整段交给手机,**一个字符都没过滤**。
它旁边那条 `list_ui` 反而是干净的(UIA 读得到值,但只拿它判一下 `editable` 就扔了)。
所以今天「框里的值」只有这一个出口,而它是敞的。

## ★ 遮挡 ≠ 移除

在画面上糊一层马赛克、底下照样把原文发出去 —— 等于没挡。
必须是**数据层就拿掉**。所以判定做在**电脑侧、出门之前**,不是等到了手机再判。

## 两组代价(★ 两个方向都要钉,第二个才是要紧的)

| 判错的方向 | 后果 |
|---|---|
| 真的卡号/身份证没遮 | 它进了她的上下文 → 云端兜底那一轮会把它发去 DeepSeek |
| ★ **读报错/读代码时把数字遮了** | 这个工具**唯一正当的用途**就是读报错和代码,而报错里全是数字。误伤 = 把它废掉 |

所以下面 [2] 那一组和 [1] 一样要紧 —— 它钉的是「日常那些数字一个都别动」。

## 为什么这里能钉死,旁边那个向量方案钉不死

银行卡号有 **Luhn 校验**、身份证有 **ISO 7064 mod-11-2 校验**。
真实号码必然过,随手一串数字只有十分之一能过。**判据是确定的,所以能被测试钉住。**
(对比:`probe.py` / `probe2.py` 量过的向量相似度那条路,分不开「转五十」和「发个消息」。)

不用 `--live`,不碰鼠标键盘,不起服务器。
"""
import io
import json
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


def redact(s):
    return ai_tools.redact_values(s)


# ===========================================================================
# 一、真的号码必须遮
#
# ★ 下面每一个都是**过我自己的校验位**的真号码(测试号 + 我按 mod-11-2 现算的一个)。
#   如果哪天有人把校验逻辑改坏了,这一组会先红。
# ===========================================================================
print("\n[1] 真的卡号 / 身份证:必须遮")

CARDS = [
    ("4111 1111 1111 1111", "16 位 · 分组写法(屏幕上最常见的样子)"),
    ("4111111111111111", "16 位 · 连着写"),
    ("3782 822463 10005", "15 位 · Amex(分组是 4/6/5,不是四个一组)"),
    ("378282246310005", "15 位 · Amex 连着写"),
    ("6222 0212 3456 7890 128", "19 位 · 银联(末组只有 3 位)"),
    ("6222021234567890128", "19 位 · 银联连着写"),
]
for raw, note in CARDS:
    out, kinds = redact(raw)
    check("遮住 %-26s" % raw,
          out != raw and kinds == (ai_tools.KIND_CARD,),
          "→ %s  (%s)" % (out, note))

VALID_ID = "110101199003071233"
out, kinds = redact(VALID_ID)
check("遮住 %s" % VALID_ID, out != VALID_ID and kinds == (ai_tools.KIND_ID,), "→ %s" % out)

# 末位大写的 X 也要认(真实身份证里很常见)。
ID_X = "11010119900307123X"
out_x, kinds_x = redact(ID_X)
print("     (末位 X 那条:%s → %s)" % (ID_X, out_x))

# 一句话里两样都有 → 两个类别都报,而且**顺序写死**(不按出现先后)。
mixed = "收款卡 4111 1111 1111 1111,户主身份证 %s" % VALID_ID
out_m, kinds_m = redact(mixed)
check("一句话里两样都有 → 两个类别",
      kinds_m == (ai_tools.KIND_CARD, ai_tools.KIND_ID), "→ %r" % (kinds_m,))
check("  而且两个原文都没了", "4111" not in out_m and "110101" not in out_m)

# 同一类出现两次 → 类别只报一次(别报成两遍)。
out_two, kinds_two = redact("4111 1111 1111 1111 和 3782 822463 10005")
check("两处卡号 → 类别只报一次", kinds_two == (ai_tools.KIND_CARD,), "→ %r" % (kinds_two,))


# ===========================================================================
# 二、★ 日常的数字一个都不许动 —— 这一组和上一组一样要紧
#
# 它钉的不是「安不安全」,是「这个工具还能不能用」。
# `read_screen` 唯一的正当用途就是读报错、读代码、读日志,而那三样全是数字。
# ===========================================================================
print("\n[2] 日常的报错 / 代码 / 日志:一个字符都不许动")

SAFE = [
    # ── 报错与代码 ──
    'requests.exceptions.ConnectionError: HTTPConnectionPool(host=\'127.0.0.1\', port=8080): Max retries exceeded',
    'File "C:\\Users\\27014\\phone-touchpad\\pc-server\\ai_tools.py", line 1411, in _read_screen',
    "Traceback (most recent call last):\n  ValueError: invalid literal for int() with base 10: '1411'",
    "const MAX_RETRY = 3; const TIMEOUT_MS = 25000;",
    "sha256=9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
    # ── 时间与版本 ──
    "2026-10-06 21:03:41.123 [ruoxi-warmup] 预热完成 424ms",
    "1759723200000",            # ★ 13 位毫秒时间戳 —— 「只认 15/16/19」就是为了它
    "1759723200123",            # 同上,再来一个(13 位的 Luhn 通过率本来就有十分之一)
    "version 1.13.8 / build 20260101 / gradle 9.6.0",
    # ── 网络与容量 ──
    "192.168.1.100:9527  →  192.168.0.20:9527",
    "已用 1234 MB / 共 2048 MB,剩余 814 MB",
    "model.log: 4889 token, 冷算 685325 ms, 还原 434 ms",
    # ── 看着像、其实不是 ──
    "1234567890123456",         # ★ 16 位但**过不了 Luhn**
    "9999 8888 7777 6666",      # ★ 16 位分组但过不了 Luhn
    "110101199003071234",       # ★ 18 位、日期也对,但**校验位是错的**
    "123456789012345678",       # ★ 18 位,日期那一段根本不是日期
    # ── ★★ 安卓崩溃墓碑:这是**量出来的**,不是我编的例子 ──
    #   2026-10-06 拿这台机器上 282 个真文件跑误伤,改之前这里会中招。
    #   `0000000000000000` 是**十六个零** —— 它**过得了 Luhn**(和是 0,0 能被 10 整除),
    #   而墓碑里满屏都是它(`x0  0000000000000000`)。读墓碑正是这个项目天天干的事。
    #   判据补了「首位必须是 2~6」(ISO/IEC 7812 的行业号)之后才挡得住。
    "x0  0000000000000000  x1  0000007d48fa7708",
    "x26 000000005a000000  x4  0000000000000000",
    "10-05 11:20:05.422  14592  17420  signal 6 (SIGABRT)",
    # ── 故意不遮的那一类(不是漏,是他的裁决)──
    #   手机号 / 姓名 / 邮箱 = 「只读、不写、不上云」,该在「要不要发云」那一刻判,
    #   在这儿遮会把「只读」也一起遮掉。见 `redact_values` 文档里那张三类表。
    "13812345678",
    "zhangsan@example.com",
]
for s in SAFE:
    out, kinds = redact(s)
    check("不动 %-30s" % (s[:30].replace("\n", "⏎")),
          out == s and kinds == (), "→ %r" % (kinds,) if kinds else "")

# ★★ 单独把那条**真误报**钉在判据本身上 —— 只钉「读文件不中招」的话,
#    以后有人换个写法(比如先 strip 掉前导零)还会绕回来。
check("★ 十六个零:过得了 Luhn,但不是卡号",
      ai_tools.luhn_ok("0000000000000000") and not ai_tools.card_shape_ok("0000000000000000"),
      "→ Luhn 是 %s(所以不能只看 Luhn)" % ai_tools.luhn_ok("0000000000000000"))
check("★ 首位不在 2~6 的都不算(0/1/7/9)",
      not any(ai_tools.card_shape_ok(d + "411111111111111") for d in "0179"))
check("  真的那几张照样算", all(ai_tools.card_shape_ok(d) for d in
      ("4111111111111111", "378282246310005", "6222021234567890128")))


# ===========================================================================
# 三、回执里不许出现命中的值
#
# 同 `_risky_app` 那条老规矩:回的是**类别名**,不是命中的那个东西。
# 连「我遮了它」这个理由里都不该带原文。
# ===========================================================================
print("\n[3] 只回类别名,不回值")

out, kinds = redact("卡号 4111 1111 1111 1111")
blob = json.dumps({"text": out, "kinds": list(kinds)}, ensure_ascii=False)
check("前 12 位不在结果里", "4111" not in blob)
check("后 4 位留着(够认是哪张卡)", "1111" in out)
check("类别名是人话", kinds == ("银行卡号",))


# ===========================================================================
# 四、遮挡的形状:长度不变、分隔符不变
# ===========================================================================
print("\n[4] 遮完之后:长度和分隔符原样")

for raw, _note in CARDS:
    out, _k = redact(raw)
    check("长度不变 %-24s" % raw, len(out) == len(raw), "→ %s" % out)
    check("  后 4 位还在            ", out[-4:] == raw[-4:])

out, _k = redact("3782 822463 10005")
check("分组样子没被压平", out.count(" ") == 2, "→ %s" % out)


# ===========================================================================
# 五、★ 真的接到 `read_screen` 上了吗
#
# `redact_values` 再好,没接上去就是一段死代码 —— 而这个项目里
# 「零调用方 = 没做」是有前例的(`Speaker.kt` / `KeywordSpotter.kt` 都编译进包了但没人调)。
# 所以这一组**打桩 OCR**,走真的 `_read_screen`,看回执。
# ===========================================================================
print("\n[5] 接到 read_screen 的回执上了吗(打桩 OCR)")

_orig_ocr = ai_tools.uia.ocr


def _fake_ocr(window=None, limit=200):
    return ([{"name": "收款账户 6222 0212 3456 7890 128"},
             {"name": "身份证 %s" % VALID_ID},
             {"name": "报错行 1411,端口 8080,耗时 1759723200000"}],
            {"ok": True, "window": "记事本"})


try:
    ai_tools.uia.ocr = _fake_ocr
    r = ai_tools._read_screen()
finally:
    ai_tools.uia.ocr = _orig_ocr

blob = json.dumps(r, ensure_ascii=False)
check("回执 ok", r.get("ok") is True)
check("行数照旧 3 行", r.get("count") == 3, "→ %r" % r.get("count"))
check("编号行还在", "1. " in (r.get("text") or ""))
check("两个类别都报了", r.get("redacted") == ["银行卡号", "身份证号"],
      "→ %r" % (r.get("redacted"),))
check("★ 卡号原文不在回执**任何**字段里", "6222" not in blob and "7890 128" not in blob)
check("★ 身份证原文不在回执任何字段里", "1101011990030" not in blob)
# ★ 后 4 位是**隔着原来那个空格**留着的(19 位银联最后一段只有 3 位数字),
#   所以判据要去掉分隔符再看 —— 直接找 "0128" 会误判成没留。
_flattened = "".join(c for c in (r.get("text") or "") if c.isdigit())
check("  后 4 位留着", "0128" in _flattened and "1233" in _flattened,
      "→ 回执里所有数字:%s" % _flattened)
check("★ 报错里那些数字一个字没动",
      "1411" in blob and "8080" in blob and "1759723200000" in blob)
check("★ 遮了就要说(不许静默)", "打码" in (r.get("note") or ""))

# 没遮的时候**不许**无中生有地报「我遮了」—— 那会让她以为屏幕上写着星号。
def _clean_ocr(window=None, limit=200):
    return ([{"name": "报错行 1411,端口 8080"}], {"ok": True, "window": "记事本"})


try:
    ai_tools.uia.ocr = _clean_ocr
    r2 = ai_tools._read_screen()
finally:
    ai_tools.uia.ocr = _orig_ocr

check("屏幕上没有号码时:不报 redacted", "redacted" not in r2)
check("  也不提「打码」两个字", "打码" not in (r2.get("note") or ""))


# ===========================================================================
# 六、★ 第二条敞着的路:`list_ui` 的控件名
#
# 别以为只有 OCR 会漏字 —— 在 UI 自动化里,**文本控件的 `Name` 就是它显示的原文**
# (`uia.ps1:116` 自己写着踩过的坑:VS Code 里有个容器的 Name 是整篇文档的内容)。
# `list_ui` 把每个控件的 Name 原样回给她,所以它和 OCR 是同一个病。
# ===========================================================================
print("\n[6] list_ui 的控件名也要过同一道闸")

_orig_list = ai_tools.uia.list_elements
_orig_uia_ok = ai_tools.UIA_OK


def _fake_list(window=None, limit=40):
    return ([{"name": "账户 6222 0212 3456 7890 128", "type": "Text", "cx": 10, "cy": 20},
             {"name": "报错 行 1411", "type": "Text", "cx": 30, "cy": 40}],
            {"ok": True, "window": "记事本"})


try:
    ai_tools.UIA_OK = True
    ai_tools.uia.list_elements = _fake_list
    L = ai_tools._list_ui()
finally:
    ai_tools.uia.list_elements = _orig_list
    ai_tools.UIA_OK = _orig_uia_ok

lblob = json.dumps(L, ensure_ascii=False)
check("list_ui ok / 两条", L.get("ok") is True and L.get("count") == 2)
check("★ 控件名里的卡号被遮了", "6222" not in lblob, "→ %r" % L.get("elements"))
check("★ `text` 那一份也遮了", "6222" not in (L.get("text") or ""))
check("  两条用同一份遮过的名字", (L.get("elements") or [{}])[0].get("name") in (L.get("text") or ""))
check("  后 4 位留着", "0128" in lblob.replace(" ", ""))
check("★ 无关那条一个字没动", "报错 行 1411" in (L.get("text") or ""))
check("★ 说了「名字被改过」", "名字被改过" in (L.get("note") or ""),
      "→ 不说的话她会拿星号去 click_ui,然后回一句「没找到」")


def _clean_list(window=None, limit=40):
    return ([{"name": "报错 行 1411", "type": "Text", "cx": 30, "cy": 40}],
            {"ok": True, "window": "记事本"})


try:
    ai_tools.UIA_OK = True
    ai_tools.uia.list_elements = _clean_list
    L2 = ai_tools._list_ui()
finally:
    ai_tools.uia.list_elements = _orig_list
    ai_tools.UIA_OK = _orig_uia_ok

check("没有号码时:不报 redacted、不加 note",
      "redacted" not in L2 and "note" not in L2)


# ===========================================================================
# 七、手机号 / 邮箱的判据 —— ★ **它们现在还没装到任何地方**,这一节钉两件事
#
# 判据本身(号段表 + 域名格式)是准的;**装在哪一层**还没定。
# 所以这里钉的是:① 判据别坏;② **「还没装」这个状态是被钉住的** ——
# 谁哪天把它接上去了,这一节会红,逼他回来看一眼这是不是他想要的那一层。
# ★ 不改 `redact_values`、不接任何工具 —— 这一节只是让那两个纯函数有人看着。
# ===========================================================================
print("\n[7] 手机号 / 邮箱的判据(★ 已做好,但**这一轮故意没接线**)")

# ── 正控:先证明它认得真的(不然下面的「不遮」分不清是准还是坏)──
for p in ("13812345678", "18612345678", "19912345678", "19212345678", "17012345678"):
    check("认得真号 %s" % p, ai_tools.phone_shape_ok(p))

# ── 号段表是**收窄**,所以反面正是它存在的理由:形状对、号段没放出去 ──
for p in ("15412345678", "16812345678", "17912345678", "19412345678", "12345678901"):
    check("形状像、号段不在表里 → 不认 %s" % p, not ai_tools.phone_shape_ok(p))

for lo, do in (("zhangsan", "example.com"), ("a.b+tag", "mail.example.org"), ("x", "sub.domain.co.uk")):
    check("认得真邮箱 %s@%s" % (lo, do), ai_tools.email_shape_ok(lo, do))

# ★★ 这条是**量出来的真误伤**,不是编的例子:
#    `MainActivity.kt` 里 Kotlin 的 `this@MainActivity.contentResolver` 被旧的纯形状判据
#    当成了邮箱。新判据靠「域名每一段必须全小写」把它干掉 —— **不用任何 TLD 白名单**
#    (白名单会随 ICANN 的新顶级域名过期,这条不会)。
check("★ Kotlin 的 this@标签 不再被当成邮箱",
      not ai_tools.email_shape_ok("this", "MainActivity.contentResolver"))
for lo, do in (("obj", "Foo.Bar"), ("x", "example.123"), ("x", "example.c")):
    check("不算邮箱 %s@%s" % (lo, do), not ai_tools.email_shape_ok(lo, do))

# ── ★ 「还没接线」这个状态本身也是被钉住的 ──
#    哪天决定把它装到 `redact_values` 里(屏幕上直接打码),这三条会自动红 ——
#    那时候要停下来想清楚:手机号/邮箱他是要「**能读**」的,遮了就没有「读」了。
_o, _k = redact("手机 13812345678,邮箱 zhangsan@example.com")
check("★ 现在**仍然不遮**手机号(装了就该红,逼人回来看一眼)",
      _o == "手机 13812345678,邮箱 zhangsan@example.com" and _k == (),
      "→ %r" % (_k,))
check("  但那两条判据**是活的**(纯函数站着,只是没人调)",
      bool(ai_tools.phone_hits("打 13812345678 找我"))
      and bool(ai_tools.email_hits("发 zhangsan@example.com")))


# ===========================================================================
# 六、★ 落盘那一份:`redact_all`(2026-10-06)
#
# ★★ **这一节和上面那两节钉的是两件不同的事,别混**:
#    `redact_values` = 给**屏幕/模型**看的,只遮卡号+身份证、且**保后四位**;
#    `redact_all`    = 给**盘上的日志/出门那一份**的,四样全遮、**一个字都不留**。
#
#    两个方向在这里都要钉,而**第二个方向(不误伤)才是重点** ——
#    它现在挂在 `_log_line` 上,而那是全项目唯一往 server.log 写字的地方:
#    它要是一紧张,以后日志里全是星号,这个项目**靠日志活着**。
# ===========================================================================
print("\n[6] 落盘那一份:四样都遮")


def scrub(s):
    return ai_tools.redact_all(s)


# ── 正控:四类一个都不能漏 ──
_o, _k = scrub("卡 4111111111111111 证 110101199003071233 "
               "手机 13812345678 邮 zhangsan@example.com")
check("四类各遮各的", _o == "卡 [银行卡号] 证 [身份证号] 手机 [手机号] 邮 [邮箱]",
      "→ %r" % _o)
check("  顺序写死,不跟出现先后走",
      _k == (ai_tools.KIND_CARD, ai_tools.KIND_ID,
             ai_tools.KIND_PHONE, ai_tools.KIND_EMAIL), "→ %r" % (_k,))
check("分开写的卡号也遮(屏幕上最常见的就是那个样子)",
      scrub("卡号 4111 1111 1111 1111 到期 12/28")[0]
      == "卡号 [银行卡号] 到期 12/28")

# ── ★★ 和 `redact_values` 的那处分岔**本身**要被钉住 ──
#    「屏幕上留着、盘上必须没」—— 哪天有人把两条合并,这两条会一起红。
check("★ 同一个号码:屏幕那份保后四位、盘上那份一个字不留",
      ai_tools.redact_values("卡 4111111111111111")[0] == "卡 ************1111"
      and scrub("卡 4111111111111111")[0] == "卡 [银行卡号]")

# ── 反控(重点):这个项目的日志里天天有的东西,一个字都不许动 ──
_KEEP = [
    # ★ 证据行 —— 计划里点名要保住的就是这一条
    "[ai] click_ui -> ok=True 11565ms clicked_in=记事本",
    "[ai] type -> ok=True 812ms verified=True changed_px=42",
    # 崩溃墓碑(读墓碑正是这个项目天天干的事)
    "x0  0000000000000000  x1  0000007d48fa7708\n"
    "10-05 11:20:05.422  14592  17420  signal 6 (SIGABRT)",
    # 鼠标/按键(每条事件都要过一遍 `_log_line`,这还是最热的那条路)
    "[mouse] DOWN bit=0x1",
    "[key] vk=%#x SendInput=%s 落到 %s",
    # 形状对但表里没有 —— 订单号 / 流水号 / 工号 / 日期区间
    "工号 20240101001", "起始 2024 2025 2026 结束", "流水号 15412345678",
    # 代码里的标识符(实测过的那条误伤)
    "this@MainActivity.contentResolver",
]
for _s in _KEEP:
    _o2, _k2 = scrub(_s)
    check("不许动:%s" % _s.splitlines()[0][:44], _o2 == _s and _k2 == (), "→ %r" % _o2)

# ── 幂等:它在「调用点」和「唯一写入口」各过一遍,两遍不能互相打架 ──
_once, _ = scrub("手机 13812345678 邮 zhangsan@example.com")
_twice, _k2 = scrub(_once)
check("★ 遮过的那份再遮一遍,一个字都不再变(所以两处各过一遍是安全的)",
      _twice == _once and _k2 == (), "→ %r" % _twice)

# ── 预筛那条短路**不许改变行为** ──
#    它靠「没有数字也没有 @」直接返回;够不够严要拿上面那些反例验,
#    这里只钉「它没有把该遮的漏过去」。
check("预筛短路之后,该遮的照遮",
      scrub("[ai] type {\"text\": \"我的号 13812345678\"}")[0]
      == "[ai] type {\"text\": \"我的号 [手机号]\"}")


# ===========================================================================
print("\n" + "=" * 60)
if FAILS:
    print("✗ %d 条没过:" % len(FAILS))
    for f in FAILS:
        print("   · %s" % f)
    sys.exit(1)
print("✓ 全过")
