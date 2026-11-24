# -*- coding: utf-8 -*-
"""一次性：用 VS Code 打开 README。"""
import os
import shutil
import subprocess

DOC = r"C:\Users\27014\phone-touchpad\README.md"

cands = [
    shutil.which("code"),
    shutil.which("code.cmd"),
    r"C:\Users\27014\AppData\Local\Programs\Microsoft VS Code\Code.exe",
    r"C:\Program Files\Microsoft VS Code\Code.exe",
]
exe = next((c for c in cands if c and os.path.isfile(c)), None)
print("code ->", exe)

if exe:
    subprocess.Popen([exe, "-r", DOC])
    print("已交给 VS Code 打开")
else:
    os.startfile(DOC)
    print("未找到 VS Code，改用默认程序")
