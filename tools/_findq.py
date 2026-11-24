# -*- coding: utf-8 -*-
"""一次性：从会话记录里把我之前读到的「第3题」题目原文捞出来。"""
import re

SRC = r"C:\Users\27014\.claude\projects\C--Users-27014\c9fb3149-9144-4ddb-a027-a6c8ea8ec3b8.jsonl"
OUT = r"C:\Users\27014\phone-touchpad\tools\_findq.txt"

KEY = "超出指定范围"
seen = set()
hits = []

with open(SRC, encoding="utf-8", errors="replace") as f:
    for lineno, line in enumerate(f, 1):
        if KEY not in line:
            continue
        for m in re.finditer(re.escape(KEY), line):
            a = max(0, m.start() - 160)
            b = min(len(line), m.end() + 60)
            frag = line[a:b]
            # 只留看得懂的部分：去掉 JSON 转义
            frag = frag.replace("\\n", " | ").replace('\\"', '"')
            key = frag[-120:]
            if key in seen:
                continue
            seen.add(key)
            hits.append("行 %d: %s" % (lineno, frag))

with open(OUT, "w", encoding="utf-8") as f:
    f.write(("共 %d 段" % len(hits)) + chr(10) + chr(10).join(hits[:40]))
print("写好", OUT, len(hits))
