# -*- coding: utf-8 -*-
"""把 `server.log` 里**已经写下去的**凭证洗掉 —— 计划第 0 步(2026-10-06)。

★★ 为什么非要有这一步
    第 1、2 步改的是**写入口**。而 **改掉写入口不会回收已经写下去的字节** ——
    实测盘上躺着 **260 条明文配对码** + **12 条明文种子/恢复码**。
    拿到这个文件的人,等于拿到这台电脑的钥匙。这件事闸管不着,只能手动清。

★ 默认 **dry-run**(只数,一个字节不改)。真写下去要显式给 `--apply`。

★★ 它凭什么可信 —— 三条,缺一条就不许写:
    ① **只允许凭证那几行变**:别的行必须和原文**逐字节相同**。这条最硬,
       它把「脚本手滑改坏了日志」整类事故挡在门外。
    ② 洗完再扫一遍,**三类凭证的命中数必须是 0**(不是我"觉得"洗掉了)。
    ③ 读盘和写盘之间如果文件**长大过**(服务还在写),直接放弃 —— 不拿一份
       正在被追加的文件做读改写。

⚠️ **故意不留备份**:备份里就是明文,留一份等于没洗。所以先写到 `.new`、
   验完两条判据,再原子替换。验不过就什么都不动。

用法(★ 建议先把 pc-server 停掉 —— 它反正要重启才吃得到第 1、2 步的改动):

    python scrub_log.py              # 只看:会改几行、哪几类
    python scrub_log.py --apply      # 真洗
"""
import ast
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

# 控制台可能是 GBK。统一切到 UTF-8(和别的脚本一致)。
try:
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")
except Exception:
    pass

import ai_tools
import server

LOG = server.LOG_PATH

# ★ 判据不是"看着像没了",是这三条正则一个都不许再命中。
#   它们是**凭证本身的长相**,不是占位符的长相 —— 所以洗完之后必须全是 0。
_CRED_PATTERNS = (
    ("配对码", re.compile(r"'pin':\s*'\d{4,8}'")),
    ("长期种子", re.compile(r"'secret':\s*'[0-9a-fA-F]{16,}'")),
    # 恢复码机制 2026-11-06 已取消 —— 这条只用来扫**历史日志**;留着是为了把老账洗干净。
)

_LINE = re.compile(r"^(\d{2}:\d{2}:\d{2}) (.*)$", re.S)


def _scrub_emit_line(rest):
    """`[emit] setup {...}` 这一类,把凭证字段换成占位。

    ★ 换法**必须**走 `server._emit_log_fields` 那一份 —— 不许在这儿再写一套。
      两套写法的后果是:洗过的那一行和以后新写的一行**长得不一样**,
      事后拿 grep 比对时会以为漏了一条。
    """
    if not rest.startswith("[emit] "):
        return rest, False
    brace = rest.find("{")
    if brace < 0:
        return rest, False
    try:
        data = ast.literal_eval(rest[brace:])
    except (ValueError, SyntaxError):
        return rest, False
    if not isinstance(data, dict):
        return rest, False
    # ★ 恢复码 2026-11-06 已取消,但**历史日志里还躺着**那些行 ——
    #   `_CREDENTIAL_FIELDS` 里已经没它了,所以这里单独认一下,洗的时候整条扔掉,
    #   免得下面「洗完之后三类命中必须为 0」这条判据被历史行卡住。
    legacy = {k: v for k, v in data.items() if k != "recovery"}
    if not any(k in data for k in server._CREDENTIAL_FIELDS) and "recovery" not in data:
        return rest, False
    return rest[:brace] + repr(server._emit_log_fields(legacy)), True


def _scan(text):
    """三类凭证各命中几行 —— 唯一的验收判据。"""
    hits = {}
    for label, pat in _CRED_PATTERNS:
        hits[label] = sum(1 for line in text.splitlines() if pat.search(line))
    return hits


def main():
    apply_it = "--apply" in sys.argv

    if not os.path.exists(LOG):
        print("!! 没这个文件: %s" % LOG)
        return 2

    size_before = os.path.getsize(LOG)
    with open(LOG, "r", encoding="utf-8", errors="replace") as f:
        original = f.read()
    if os.path.getsize(LOG) != size_before:
        print("!! 读的这一刻文件还在变长 —— 服务还在写它。")
        print("   先把 pc-server 停掉(它本来也要重启才吃得到第 1/2 步的改动),再跑这个。")
        return 2

    print("文件: %s" % LOG)
    print("大小: %d 字节, %d 行" % (size_before, len(original.splitlines())))
    print()

    hits_before = _scan(original)
    print("洗之前:")
    for label, n in hits_before.items():
        print("   %-6s %3d 行" % (label, n))
    total_before = sum(hits_before.values())
    if total_before == 0:
        print()
        print("✓ 三类凭证一行都没有 —— 这个文件本来就是干净的,不用动。")
        return 0

    # ── 逐行过:凭证 → 占位;然后整行再过一遍 redact_all(和真写入口同一道闸)──
    out_lines = []
    changed = 0
    redact_touched = 0
    for line in original.splitlines(keepends=True):
        body = line.rstrip("\n").rstrip("\r")
        nl = line[len(body):]
        m = _LINE.match(body)
        if not m:
            out_lines.append(line)
            continue
        ts, rest = m.group(1), m.group(2)
        new_rest, did = _scrub_emit_line(rest)
        if did:
            changed += 1
        red, _kinds = ai_tools.redact_all(new_rest)
        if red != new_rest:
            redact_touched += 1
            new_rest = red
        out_lines.append("%s %s%s" % (ts, new_rest, nl))
    new_text = "".join(out_lines)

    print()
    print("会改: 凭证行 %d 行; 另有 %d 行被形状判据(卡号/身份证/手机号/邮箱)改到"
          % (changed, redact_touched))

    # ── 判据 ①:除了那几行,别的一个字节都不许动 ──
    old_l = original.splitlines()
    new_l = new_text.splitlines()
    if len(old_l) != len(new_l):
        print("!! 行数变了(%d → %d),不写。" % (len(old_l), len(new_l)))
        return 1
    stray = [i + 1 for i, (a, b) in enumerate(zip(old_l, new_l)) if a != b
             and not any(p.search(a) for _l, p in _CRED_PATTERNS)]
    if stray:
        print("!! 这些行**不该被改却改了**: %s" % stray[:10])
        print("   只允许凭证那几行变。不写。")
        return 1
    print("   ✓ 除了凭证那 %d 行,其余 %d 行逐字节未动" % (changed, len(old_l)))

    # ── 判据 ②:洗完再扫,必须全 0 ──
    hits_after = _scan(new_text)
    for label, n in hits_after.items():
        if n:
            print("!! 洗完还剩 %s %d 行 —— 没洗干净,不写。" % (label, n))
            return 1
    print("   ✓ 洗完三类凭证命中数全是 0")

    if not apply_it:
        print()
        print("(这是 dry-run,一个字节都没改。要真洗: python scrub_log.py --apply)")
        print("⚠️ 故意不留备份 —— 备份里就是明文。")
        return 0

    # ── 判据 ③:读→写这中间要是又长了,那一行会被我盖掉 —— 宁可不写 ──
    if os.path.getsize(LOG) != size_before:
        print("!! 这中间服务又写了几行 —— 覆盖它们等于丢日志。不写。")
        print("   等一下再跑一遍(dry-run 先看数)。")
        return 2

    tmp = LOG + ".new"
    with open(tmp, "w", encoding="utf-8", newline="") as f:
        f.write(new_text)
    os.replace(tmp, LOG)          # 原子替换:要么全新,要么原样,不会写一半
    print()
    print("✓ 已洗: %s" % LOG)
    print("  大小 %d → %d 字节" % (size_before, os.path.getsize(LOG)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
