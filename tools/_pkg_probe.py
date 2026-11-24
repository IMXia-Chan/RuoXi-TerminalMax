# -*- coding: utf-8 -*-
"""只读摸底：打包电脑端要的东西在不在。不装、不改、不删。"""
import importlib.util
import os
import shutil
import sys

NEED = ["PyInstaller", "webview", "PIL", "requests", "cryptography",
        "numpy", "cv2", "segno", "pyvirtualcam", "sounddevice",
        "quotas", "websockets", "qrcode"]

print("python", sys.version.split()[0], "at", sys.executable)
print()
print("模块：")
for n in NEED:
    try:
        ok = importlib.util.find_spec(n) is not None
    except Exception as e:
        ok = "err:" + type(e).__name__
    print(f"  {'OK ' if ok is True else '—  '} {n}" + ("" if ok is True else f"   {ok}"))
print()
print("打包/安装器：")
for exe in ["pyinstaller", "makensis", "iscc"]:
    p = shutil.which(exe)
    print(f"  {'OK ' if p else '—  '} {exe}" + (f"   {p}" if p else ""))
print()
root = r"C:\Users\27014\phone-touchpad\pc-server"
print("pc-server 关键文件：")
for f in ["server.py", "conmarn_app.py", "app_bridge.py", "ai_tools.py",
          "uia.ps1", "uia.py", "pair_crypto.py", "conmarn.ico",
          "secret.json", "master.json", "server.log"]:
    p = os.path.join(root, f)
    if os.path.isfile(p):
        print(f"  {os.path.getsize(p):>10,}  {f}")
    else:
        print(f"  {'—':>10}  {f}")
