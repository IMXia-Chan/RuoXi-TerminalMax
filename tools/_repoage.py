# -*- coding: utf-8 -*-
"""一次性：查公开仓库快照里到底有没有多智能体那部分代码，以及最后一次提交是什么时候。"""
import os
import subprocess

SNAP = r"D:\conmarn-src"
WORK = r"C:\Users\27014\phone-touchpad"
SRC = os.path.join(SNAP, "app", "src", "main", "java", "com", "example", "touchpad")

print("== 快照里最后一次提交 ==")
r = subprocess.run(["git", "-C", SNAP, "log", "-1", "--format=%ci | %s"], capture_output=True)
print("   ", r.stdout.decode("utf-8", "replace").strip())

print()
print("== 快照里的工作台 / 智能体相关文件 ==")
keys = ("Work", "Agent", "Chain", "Fanout", "Skill", "Connector", "Mcp", "OAuth")
hit = sorted(f for f in os.listdir(SRC) if any(k in f for k in keys))
for f in hit:
    print("   ", f)
print("    共", len(hit), "个")

print()
print("== 工作树与快照的差异（同名文件内容不同 / 只在一侧存在）==")
diff = []
missing = []
for root, dirs, files in os.walk(os.path.join(WORK, "app", "src")):
    dirs[:] = [d for d in dirs if d not in ("build",)]
    for name in files:
        p = os.path.join(root, name)
        rel = os.path.relpath(p, WORK)
        q = os.path.join(SNAP, rel)
        if not os.path.isfile(q):
            missing.append(rel)
            continue
        if os.path.getsize(p) != os.path.getsize(q):
            diff.append(rel)
        else:
            a = open(p, "rb").read()
            b = open(q, "rb").read()
            if a != b:
                diff.append(rel)

print("    只在工作树、快照里没有:", len(missing))
for f in missing[:10]:
    print("       ", f)
print("    两边内容不同:", len(diff))
for f in diff[:10]:
    print("       ", f)
