# -*- coding: utf-8 -*-
"""一次性：给 README 重写取几个事实（顶层目录、版本号、包名）。"""
import os
import re

ROOT = r"C:\Users\27014\phone-touchpad"
print("== 顶层 ==")
for name in sorted(os.listdir(ROOT)):
    if name.startswith("."):
        continue
    p = os.path.join(ROOT, name)
    print(("DIR  " if os.path.isdir(p) else "FILE ") + name)

print()
print("== app/build.gradle.kts 关键行 ==")
txt = open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
for line in txt.splitlines():
    if re.search(r"versionName|versionCode|applicationId|namespace", line):
        print("   ", line.strip())

print()
print("== pc-server 顶层 ==")
for name in sorted(os.listdir(os.path.join(ROOT, "pc-server"))):
    p = os.path.join(ROOT, "pc-server", name)
    print(("DIR  " if os.path.isdir(p) else "FILE ") + name)
