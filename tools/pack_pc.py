# -*- coding: utf-8 -*-
"""打电脑端的包（Windows）。

用法（仓库根目录）：

    python tools/pack_pc.py --onefile   单文件 exe（官网发这个）
    python tools/pack_pc.py             文件夹版（启动快，适合自己用）

产物：`pc-server/dist-onefile/ConMarn.exe` 或 `pc-server/dist/ConMarn/ConMarn.exe`。

★ 打包前会检查三样不能进包的东西不在 dist 里（`secret.json` / `master.json` /
  `server.log`）—— 这是**配对密钥和明文配对码**，跟着包发出去等于把别人电脑的钥匙送人。
"""
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PC = os.path.join(ROOT, "pc-server")
ONEFILE = "--onefile" in sys.argv
SPEC = os.path.join(PC, "ConMarn-PC-onefile.spec" if ONEFILE else "ConMarn-PC.spec")
OUT = os.path.join(PC, "dist-onefile" if ONEFILE else "dist")
DIST = os.path.join(OUT, "ConMarn") if not ONEFILE else OUT
BUILD = os.path.join(PC, "build-onefile" if ONEFILE else "build")

FORBIDDEN = ["secret.json", "master.json", "server.log", "fake_llm.log", "restart_log.txt"]


def main():
    if not os.path.isfile(SPEC):
        print("!! 没有", SPEC)
        return 1

    # 上一次的产物先清掉：PyInstaller 的 --clean 只管自己的缓存，
    # dist 里多出来的旧文件会**一起被打进分发目录**，很容易漏。
    for d in (OUT, BUILD):
        if os.path.isdir(d):
            shutil.rmtree(d, ignore_errors=True)

    cmd = [sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean",
           "--distpath", OUT,
           "--workpath", BUILD, SPEC]
    print("跑：", " ".join(cmd))
    r = subprocess.run(cmd, cwd=PC)
    if r.returncode != 0:
        print("!! 打包失败，退出码", r.returncode)
        return r.returncode

    exe = os.path.join(DIST, "ConMarn.exe")
    if not os.path.isfile(exe):
        print("!! 没打出 ConMarn.exe")
        return 1

    bad = []
    for base, _dirs, files in os.walk(DIST):
        for f in files:
            if f in FORBIDDEN:
                bad.append(os.path.join(base, f))
    if bad:
        print("!! 包里混进了不能发的东西：")
        for b in bad:
            print("   ", b)
        return 1

    total = 0
    for base, _dirs, files in os.walk(DIST):
        for f in files:
            total += os.path.getsize(os.path.join(base, f))
    print()
    print("产物  %s" % exe)
    print("体积  %.1f MB" % (total / 1e6))
    print("检查  没有 secret.json / master.json / server.log")
    return 0


if __name__ == "__main__":
    sys.exit(main())
