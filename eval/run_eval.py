# -*- coding: utf-8 -*-
"""跑评测集,看「脑」的工具选择命中率。

    # 手机上的服务(adb forward tcp:18080 tcp:8080)
    python run_eval.py --base-url http://127.0.0.1:18080 --model brain
    # 只看第 9、12 条,并把模型原话打出来
    python run_eval.py --only 9,12 -v

提示词与工具表**不是抄的**,是从 AiAgent.kt 现场抠的(见 ruoxi_prompt.py)——
所以这个分数永远对应 App 此刻的真实行为。

温度默认 0.2,和 AiAgent.Config.temperature 一致。别为了好看调成 0:
0 是贪心解码,能测出「模型本来知不知道」,但测不出用户实机上会遇到的那点抖动。
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.request

import ruoxi_prompt

CASES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cases.txt")
NO_TOOL = "-"


def parse_cases(path):
    out = []
    with open(path, encoding="utf-8") as fp:
        for lineno, raw in enumerate(fp, 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            parts = [p.strip() for p in line.split("|")]
            if len(parts) < 2:
                raise SystemExit("cases.txt 第 %d 行只有 %d 列,要 2~3 列: %s"
                                 % (lineno, len(parts), line))
            out.append({"n": len(out) + 1, "say": parts[0],
                        "want": parts[1], "arg": parts[2] if len(parts) > 2 else ""})
    return out


def post(url, body, timeout):
    req = urllib.request.Request(
        url + "/v1/chat/completions",
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def ask(base_url, model, system_prompt, tools, say, temperature, timeout):
    """→ (工具名 or None, 参数 JSON 串, timings)。不发 tools 之外的东西,和 App 一致。"""
    body = {
        "model": model,
        "messages": [{"role": "system", "content": system_prompt},
                     {"role": "user", "content": say}],
        "temperature": temperature,
        "stream": False,
        "cache_prompt": True,          # 全用例共享同一截前缀,不缓存等于白跑
        "max_tokens": 256,
        "tools": tools,
        "tool_choice": "auto",
        # ★ 钉死在一个槽上(2026-10-04 实测加的)。这一行不是性能优化,是**能不能跑完**。
        #
        # 服务是 `-np 4`,请求会轮流落到 4 个槽上,而**每个槽各留一份前缀缓存**。
        # 实测这条前缀是 **4686 个 token**(系统提示 5442 字符 + 工具 JSON 4055 字符;
        # 用 /slots 的 n_prompt_tokens 量的,不是估的),手机预填充约 7~14 tok/s ——
        # 也就是**每个槽各要冷算三五分钟**。4 个槽轮着来 = 前 4 条用例一条一条地冷算。
        # 钉在同一个槽上,只有第 1 条付这个钱,后面 51 条都是几秒钟。
        #
        # ⚠️ 代价:所有请求串行排在这一个槽上。评测本来就是串行的,无所谓。
        "id_slot": 0,
    }
    res = post(base_url, body, timeout)
    msg = (res.get("choices") or [{}])[0].get("message") or {}
    calls = msg.get("tool_calls") or []
    if calls:
        fn = calls[0].get("function") or {}
        return fn.get("name"), fn.get("arguments") or "", res.get("timings") or {}, msg
    return None, "", res.get("timings") or {}, msg


def judge(case, got_tool, got_args):
    """→ (过没过, 为什么)。"""
    want = case["want"]
    if want == NO_TOOL:
        if got_tool is None:
            return True, "没调工具 ✓"
        return False, "不该调工具,却调了 %s" % got_tool
    if got_tool is None:
        return False, "该调 %s,却只回了话" % want
    if got_tool not in want.split("/"):
        return False, "调成 %s 了" % got_tool
    if case["arg"] and case["arg"] not in got_args:
        return False, "%s 的参数里没有「%s」" % (got_tool, case["arg"])
    return True, ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://127.0.0.1:18080")
    ap.add_argument("--model", default="brain")
    ap.add_argument("--temperature", type=float, default=0.2)
    # ★ 900 不是随手写大的(2026-10-04 实测):前缀 4686 token,手机冷预填充
    # 三五分钟,而**第 1 条用例**一定要付这个钱。默认 300 会在预热那一步就超时,
    # 而超时的表现是「连不上手机」这种查不出原因的错(实测踩过)。
    # 只有第 1 条慢,后面命中缓存是几秒。
    ap.add_argument("--timeout", type=float, default=900)
    ap.add_argument("--only", default="", help="逗号分隔的用例号,只跑这些")
    ap.add_argument("-v", "--verbose", action="store_true", help="打偏的把模型原话也打出来")
    args = ap.parse_args()

    system_prompt, tools = ruoxi_prompt.load()
    print("提示词 %d 字符 / 工具 %d 个 —— 现场从 AiAgent.kt 抠的"
          % (len(system_prompt), len(tools)))

    cases = parse_cases(CASES)
    if args.only:
        keep = {int(x) for x in args.only.split(",") if x.strip()}
        cases = [c for c in cases if c["n"] in keep]
    print("用例 %d 条,温度 %.2f,%s\n" % (len(cases), args.temperature, args.base_url))

    # 先灌一遍前缀,否则第 1 条要冷算 800 多 token
    t0 = time.time()
    try:
        ask(args.base_url, args.model, system_prompt, tools, "你好", args.temperature, args.timeout)
    except Exception as e:
        print("连不上 %s: %s" % (args.base_url, e))
        print("手机上要先起服务;或用 adb forward tcp:18080 tcp:8080 转发。")
        return 1
    print("预热 %.1fs\n" % (time.time() - t0))

    bad, total_ms = [], 0.0
    for c in cases:
        t = time.time()
        try:
            tool, a, tim, msg = ask(args.base_url, args.model, system_prompt,
                                    tools, c["say"], args.temperature, args.timeout)
        except Exception as e:
            bad.append((c, "请求炸了: %s" % e, ""))
            print("  FAIL #%-2d %-22s %s" % (c["n"], c["say"], e))
            continue
        ms = (time.time() - t) * 1000
        total_ms += ms
        ok, why = judge(c, tool, a)
        if ok:
            print("  ok   #%-2d %-22s → %-14s (%4.0fms)" % (c["n"], c["say"], tool or "(不调)", ms))
        else:
            bad.append((c, why, msg.get("content") or ""))
            print("  FAIL #%-2d %-22s %s" % (c["n"], c["say"], why))
            if args.verbose and msg.get("content"):
                print("        模型原话: %s" % msg["content"].strip()[:160])

    hit = len(cases) - len(bad)
    print("\n命中 %d/%d = %.1f%%   平均 %.0fms/条"
          % (hit, len(cases), 100.0 * hit / max(1, len(cases)),
             total_ms / max(1, len(cases))))
    if bad:
        print("\n打偏的:")
        for c, why, _ in bad:
            print("  #%-2d %-22s %s" % (c["n"], c["say"], why))
    return 0 if not bad else 2


if __name__ == "__main__":
    sys.exit(main())
