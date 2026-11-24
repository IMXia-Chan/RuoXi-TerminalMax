# -*- coding: utf-8 -*-
"""一次性：把重写后的 README 同步到公开快照、提交并推送。"""
import os
import shutil
import subprocess

WORK = r"C:\Users\27014\phone-touchpad\README.md"
SNAP = r"D:\conmarn-src"
DST = os.path.join(SNAP, "README.md")


def git(*args, timeout=120):
    r = subprocess.run(["git", "-C", SNAP] + list(args),
                       capture_output=True, timeout=timeout)
    return r.returncode, r.stdout.decode("utf-8", "replace"), r.stderr.decode("utf-8", "replace")


# 1) 连通性
try:
    rc, out, err = git("ls-remote", "--heads", "origin", timeout=60)
    print("[1] ls-remote 返回码", rc)
    if out:
        print(out.strip())
    if err:
        print("    stderr:", err.strip()[:300])
except subprocess.TimeoutExpired:
    print("[1] ls-remote 超时（github 不通）")
    raise SystemExit(2)

if rc != 0:
    print("    → GitHub 不通，先不推")
    raise SystemExit(2)

# 2) 同步文件
shutil.copyfile(WORK, DST)
print("[2] 已覆盖快照 README，%d 字节" % os.path.getsize(DST))

rc, out, err = git("status", "--porcelain")
print("[3] 改动:")
print(out.strip() or "    （无）")

if "README.md" not in out:
    print("    没有改动，不用提交")
    raise SystemExit(0)

# 3) 提交
msg = "README：按 v3.1.1 现状重写，许可口径改为 Apache-2.0"
rc, out, err = git("add", "README.md")
rc, out, err = git("commit", "-m", msg)
print("[4] commit 返回码", rc, out.strip()[:200], err.strip()[:200])

# 4) 推送
try:
    rc, out, err = git("push", "origin", "HEAD", timeout=180)
except subprocess.TimeoutExpired:
    print("[5] push 超时")
    raise SystemExit(3)
print("[5] push 返回码", rc)
if out:
    print("    ", out.strip()[:400])
if err:
    print("    stderr:", err.strip()[:400])

if rc == 0:
    rc, out, err = git("log", "-1", "--format=%h %s")
    print("[6] 远端最新提交:", out.strip())
