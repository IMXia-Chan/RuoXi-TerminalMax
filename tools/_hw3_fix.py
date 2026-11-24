# -*- coding: utf-8 -*-
"""一次性：给第 3 题的「超出指定范围」补上句号，重写 GBK 源码并跑题目截图那三组。"""
import os
import subprocess

SRC = r"C:\Users\27014\Desktop\第3题-判断几位数.c"
GCC = r"D:\Dev-Cpp\MinGW64\bin\gcc.exe"
EXE = r"C:\Users\27014\phone-touchpad\tools\_hw3.exe"

CODE = """#define _CRT_SECURE_NO_WARNINGS
#include <stdio.h>

int main(void)
{
    int n;

    printf("请输入一个0~999之间的数：");

    if (scanf("%d", &n) != 1) {
        printf("输入无效\\n");
        return 1;
    }

    if (n < 0 || n > 999) {
        printf("%d已超出指定范围。\\n", n);
    } else if (n < 10) {
        printf("%d是一位数。\\n", n);
    } else if (n < 100) {
        printf("%d是两位数。\\n", n);
    } else {
        printf("%d是三位数。\\n", n);
    }

    return 0;
}
"""
open(SRC, "wb").write(CODE.encode("gbk"))
print("已写 GBK 源码，%d 字节" % os.path.getsize(SRC))

r = subprocess.run([GCC, "-std=c99", "-Wall", "-o", EXE, SRC], capture_output=True)
print("编译返回码", r.returncode)
if r.stderr:
    print("stderr:", r.stderr.decode("gbk", "replace"))

P = "请输入一个0~999之间的数："
CASES = [
    ("89",   P + "89是两位数。"),
    ("123",  P + "123是三位数。"),
    ("3552", P + "3552已超出指定范围。"),
    ("5",    P + "5是一位数。"),
    ("0",    P + "0是一位数。"),
]
allok = True
for inp, want in CASES:
    p = subprocess.run([EXE], input=(inp + "\n").encode(), capture_output=True)
    got = p.stdout.decode("gbk")
    ok = (got == want + "\r\n")
    allok = allok and ok
    print("%-5s %s" % (inp, "OK" if ok else "不等 -> %r" % got))

print("全部一致" if allok else "有不一致")
