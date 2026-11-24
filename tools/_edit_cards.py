# -*- coding: utf-8 -*-
"""撤掉场上的岗位卡片（他 2026-11-06：「不要在球上显示研究员、核验员那些了」）。"""
import io

P = "C:/Users/27014/phone-touchpad/app/src/main/assets/dash/dash-app.js"
src = io.open(P, encoding="utf-8").read()
lines = src.split("\n")

a, b = 1612 - 1, 1685 - 1          # 0-based，闭区间
assert "岗位面板" in lines[a], lines[a]
assert lines[b].strip() == "}", repr(lines[b])
# 往前多确认一行（1611 是空行）
assert lines[a - 1].strip() == "", repr(lines[a - 1])

new = [
    "    /* ★ 2026-11-06 撤掉：环外那圈「研究员 / 核验员 …」的岗位卡。",
    "     *   他要的是球本身做主角，卡片压在球上把网络盖掉了一半。",
    "     *   岗位的账还在：右边列表一行一个岗位，点开就是全文。",
    "     *   `hitSites` 留着但是空的 —— 场上的点击从此不做事，只有悬停还会点亮节点。 */",
    "    hitSites = [];",
]

out = lines[:a] + new + lines[b + 1:]
io.open(P, "w", encoding="utf-8", newline="\n").write("\n".join(out))
print("ok", len(lines), "->", len(out))
