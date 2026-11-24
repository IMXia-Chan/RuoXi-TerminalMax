# -*- coding: utf-8 -*-
"""一次性：第 4 题（四个整数求最小值）转 GBK、编译、跑样例看字节。"""
import os
import subprocess

SRC = r"C:\Users\27014\Desktop\第4题-四个整数最小值.c"
GCC = r"D:\Dev-Cpp\MinGW64\bin\gcc.exe"
EXE = r"C:\Users\27014\phone-touchpad\tools\_hw4.exe"

raw = open(SRC, "rb").read()
if raw.startswith(b"\xef\xbb\xbf"):
    raw = raw[3:]
open(SRC, "wb").write(raw.decode("utf-8").encode("gbk"))
print("已转 GBK，%d 字节" % os.path.getsize(SRC))

r = subprocess.run([GCC, "-std=c99", "-Wall", "-o", EXE, SRC], capture_output=True)
print("编译返回码", r.returncode)
if r.stderr:
    print("stderr:", r.stderr.decode("gbk", "replace"))

runs = [
    ("9 -3 5 7", "最小值为-3"),
    ("1 2 3 4", "最小值为1"),
    ("-5 -9 -2 -7", "最小值为-9"),
    ("0 0 0 0", "最小值为0"),
    ("7 3 9 2", "最小值为2"),
]
allok = True
for inp, want in runs:
    p = subprocess.run([EXE], input=(inp + "\n").encode(), capture_output=True)
    got = p.stdout.decode("gbk")
    # 题面要求最后输出无换行符；提示语那行后面也没有换行，程序自己不打
    ok = got.endswith(want)
    allok = allok and ok
    print("%-12s -> %r  期望以 %r 结尾  %s" % (inp, got, want, "OK" if ok else "不对"))
    print("              末尾字节: %r" % p.stdout[-6:].hex())

print("全部一致" if allok else "有不一致")
