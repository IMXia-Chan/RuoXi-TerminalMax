# -*- coding: utf-8 -*-
"""一次性：查开源快照里到底有没有把签名/密钥文件提交进去。"""
import subprocess

ROOT = r"D:\conmarn-src"
KEYS = ("jks", "keystore", "secret", "local.properties", "master.json", "properties")


def git(*args):
    r = subprocess.run(["git", "-C", ROOT] + list(args), capture_output=True)
    return r.returncode, r.stdout.decode("utf-8", "replace")


rc, out = git("ls-files")
files = out.splitlines()
print("跟踪文件总数", len(files))
bad = [f for f in files if any(k in f.lower() for k in ("jks", "keystore", "secret.json",
                                                         "master.json", "keystore.properties",
                                                         "local.properties"))]
print("可疑文件", len(bad))
for f in bad:
    print("   ", f)

rc, out = git("status", "--porcelain")
print("工作区改动行数", len(out.splitlines()))
for line in out.splitlines()[:20]:
    print("   ", line)

rc, out = git("log", "--oneline", "-3")
print("最近提交:")
print(out)
