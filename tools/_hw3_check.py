# -*- coding: utf-8 -*-
"""一次性：编第 3 题的源码，跑样例，对比两种执行字符集的输出字节。"""
import os
import subprocess

SRC = r"C:\Users\27014\Desktop\第3题-判断几位数.c"
GCC = r"D:\Dev-Cpp\MinGW64\bin\gcc.exe"
WORK = r"C:\Users\27014\phone-touchpad\tools"
LOG = os.path.join(WORK, "_hw3_log.txt")

BOM = b"\xef\xbb\xbf"
raw = open(SRC, "rb").read()
if not raw.startswith(BOM):
    open(SRC, "wb").write(BOM + raw)
    note = "已补 UTF-8 BOM"
else:
    note = "已带 UTF-8 BOM"

lines = [note, "源文件大小 %d 字节" % os.path.getsize(SRC)]

VARIANTS = [
    ("默认（UTF-8 执行字符集）", []),
    ("-fexec-charset=GBK", ["-fexec-charset=GBK"]),
]

CASES = ["5", "26", "0", "999", "1234", "-5"]

for name, extra in VARIANTS:
    exe = os.path.join(WORK, "_hw3_%d.exe" % len(lines))
    cmd = [GCC, "-std=c99", "-Wall"] + extra + ["-o", exe, SRC]
    r = subprocess.run(cmd, capture_output=True)
    lines.append("")
    lines.append("=== " + name + " ===")
    lines.append("编译返回码 %d" % r.returncode)
    for tag, buf in (("stdout", r.stdout), ("stderr", r.stderr)):
        if buf:
            lines.append("%s: %r" % (tag, buf))
    if r.returncode != 0:
        continue
    for c in CASES:
        p = subprocess.run([exe], input=(c + "\n").encode(), capture_output=True)
        out = p.stdout
        u = out.decode("utf-8", "replace").replace(chr(10), r"\n")
        g = out.decode("gbk", "replace").replace(chr(10), r"\n")
        lines.append("输入 %-5s 字节 %2d" % (c, len(out)))
        lines.append("   utf8-> %s" % u)
        lines.append("   gbk -> %s" % g)
        lines.append("   hex  -> %s" % out.hex())

with open(LOG, "w", encoding="utf-8") as f:
    f.write(chr(10).join(lines))
print("写好了", LOG, os.path.getsize(LOG), "字节")
