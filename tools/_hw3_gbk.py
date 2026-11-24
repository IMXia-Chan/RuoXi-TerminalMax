# -*- coding: utf-8 -*-
"""一次性：把桌面那份 UTF-8 源码转成 GBK（Dev-C++ 控制台才不会显示乱码），
再编一次确认输出字节是 GBK。"""
import os
import subprocess

SRC = r"C:\Users\27014\Desktop\第3题-判断几位数.c"
GCC = r"D:\Dev-Cpp\MinGW64\bin\gcc.exe"
EXE = r"C:\Users\27014\phone-touchpad\tools\_hw3_gbk.exe"

raw = open(SRC, "rb").read()
if raw.startswith(b"\xef\xbb\xbf"):
    raw = raw[3:]
text = raw.decode("utf-8")

# GBK 里没有 BOM 的概念，直接存 GBK 字节
data = text.encode("gbk")
open(SRC, "wb").write(data)
print("已转 GBK，%d 字节" % os.path.getsize(SRC))

r = subprocess.run([GCC, "-std=c99", "-Wall", "-o", EXE, SRC], capture_output=True)
print("编译返回码", r.returncode)
if r.stderr:
    print("stderr:", r.stderr.decode("gbk", "replace"))

OK = {
    "5":    "请输入一个0~999之间的数：5是一位数。",
    "26":   "请输入一个0~999之间的数：26是两位数。",
    "999":  "请输入一个0~999之间的数：999是三位数。",
    "0":    "请输入一个0~999之间的数：0是一位数。",
    "1234": "请输入一个0~999之间的数：1234已超出指定范围",
    "-5":   "请输入一个0~999之间的数：-5已超出指定范围",
}
allok = True
for k, want in OK.items():
    p = subprocess.run([EXE], input=(k + "\n").encode(), capture_output=True)
    got = p.stdout.decode("gbk")
    want_full = want + "\r\n"
    ok = (got == want_full)
    allok = allok and ok
    print("%-5s %s" % (k, "OK" if ok else "不等 -> %r" % got))

print("全部一致" if allok else "有不一致")
