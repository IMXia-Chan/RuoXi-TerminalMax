# -*- coding: utf-8 -*-
"""一次性：把统一后的产品名写进官网首页，并同步 README 到公开快照后推送。"""
import os
import shutil
import subprocess

TAG = "基于端侧大模型的本地优先多智能体协同与跨设备执行系统"
SITE_REPO = r"D:\conmarn-site-gh"
SNAP = r"D:\conmarn-src"
WORK_README = r"C:\Users\27014\phone-touchpad\README.md"


def git(root, *args, timeout=180):
    r = subprocess.run(["git", "-C", root] + list(args), capture_output=True, timeout=timeout)
    return (r.returncode,
            r.stdout.decode("utf-8", "replace"),
            r.stderr.decode("utf-8", "replace"))


# ── 1. 官网首页（按字节读写，不动换行）─────────────────────────
SITE = os.path.join(SITE_REPO, "index.html")
raw = open(SITE, "rb").read()
html = raw.decode("utf-8")
pairs = [
    ("<title>ConMarn · 运行在 Android 设备本地的 AI 角色运行时</title>",
     "<title>ConMarn——%s</title>" % TAG),
    ("ConMarn 是一个运行在用户自有 Android 设备上的 AI 角色运行时。",
     "ConMarn 是基于端侧大模型的本地优先多智能体协同与跨设备执行系统。"),
]
for old, new in pairs:
    c = html.count(old)
    print("[站点] 命中 %d 处：%s" % (c, old[:30]))
    html = html.replace(old, new)
open(SITE, "wb").write(html.encode("utf-8"))

# ── 2. README 同步 ────────────────────────────────────────────
shutil.copyfile(WORK_README, os.path.join(SNAP, "README.md"))
print("[README] 已同步到快照，%d 字节" % os.path.getsize(WORK_README))

# ── 3. 两个仓库各自提交、推送 ────────────────────────────────
MSG = "统一产品全称为「ConMarn——%s」" % TAG
for name, repo, path in (("快照", SNAP, "README.md"), ("站点", SITE_REPO, "index.html")):
    rc, out, err = git(repo, "status", "--porcelain")
    changed = out.strip()
    print("[%s] 改动: %s" % (name, changed.replace("\n", " | ") or "（无）"))
    if not changed:
        continue
    git(repo, "add", path)
    rc, out, err = git(repo, "commit", "-m", MSG)
    print("[%s] commit %d %s" % (name, rc, out.strip()[:140]))
    try:
        rc, out, err = git(repo, "push", "origin", "HEAD")
        print("[%s] push %d %s" % (name, rc, (out + err).strip()[:300]))
    except subprocess.TimeoutExpired:
        print("[%s] push 超时" % name)
