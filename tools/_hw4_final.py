# -*- coding: utf-8 -*-
"""一次性：第 4 题按题目截图口径（最小值为：-3，末尾无换行）写 GBK 源码并验字节。"""
import os
import subprocess

SRC = r"C:\Users\27014\Desktop\第4题-四个整数最小值.c"
GCC = r"D:\Dev-Cpp\MinGW64\bin\gcc.exe"
EXE = r"C:\Users\27014\phone-touchpad\tools\_hw4.exe"

CODE = """#define _CRT_SECURE_NO_WARNINGS
#include <stdio.h>

int main(void)
{
    int a, b, c, d, min;

    printf("请输入四个整数：");
    scanf("%d %d %d %d", &a, &b, &c, &d);

    min = a;
    if (b < min) min = b;
    if (c < min) min = c;
    if (d < min) min = d;

    printf("最小值为：%d", min);

    return 0;
}
"""
open(SRC, "wb").write(CODE.encode("gbk"))
print("已写 GBK 源码，%d 字节" % os.path.getsize(SRC))

r = subprocess.run([GCC, "-std=c99", "-Wall", "-o", EXE, SRC], capture_output=True)
print("编译返回码", r.returncode)
if r.stderr:
    print("stderr:", r.stderr.decode("gbk", "replace"))

P = "请输入四个整数："
CASES = [
    ("9 -3 5 7",  P + "最小值为：-3"),
    ("1 2 3 4",   P + "最小值为：1"),
    ("-5 -9 -2 -7", P + "最小值为：-9"),
    ("0 0 0 0",   P + "最小值为：0"),
    ("7 3 9 2",   P + "最小值为：2"),
]
allok = True
for inp, want in CASES:
    p = subprocess.run([EXE], input=(inp + "\n").encode(), capture_output=True)
    got = p.stdout.decode("gbk")
    ok = (got == want)
    allok = allok and ok
    print("%-13s %s" % (inp, "OK" if ok else "不等 -> %r" % got))

print("全部一致（且末尾无换行）" if allok else "有不一致")
