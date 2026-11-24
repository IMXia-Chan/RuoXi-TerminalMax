# -*- coding: utf-8 -*-
"""一次性：第 6 题（switch 星期几）写 GBK 源码、编译、验字节。"""
import os
import subprocess

SRC = r"C:\Users\27014\Desktop\第6题-星期几.c"
GCC = r"D:\Dev-Cpp\MinGW64\bin\gcc.exe"
EXE = r"C:\Users\27014\phone-touchpad\tools\_hw6.exe"

CODE = """#define _CRT_SECURE_NO_WARNINGS
#include <stdio.h>

int main(void)
{
    int n;

    printf("请输入一个1~7之间的整数：");

    if (scanf("%d", &n) != 1) {
        printf("输入无效\\n");
        return 1;
    }

    switch (n) {
    case 1: printf("Monday\\n");    break;
    case 2: printf("Tuesday\\n");   break;
    case 3: printf("Wednesday\\n"); break;
    case 4: printf("Thursday\\n");  break;
    case 5: printf("Friday\\n");    break;
    case 6: printf("Saturday\\n");  break;
    case 7: printf("Sunday\\n");    break;
    default: printf("输入超出范围\\n"); break;
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

P = "请输入一个1~7之间的整数："
CASES = [
    ("3", P + "Wednesday\r\n"),
    ("6", P + "Saturday\r\n"),
    ("1", P + "Monday\r\n"),
    ("7", P + "Sunday\r\n"),
    ("9", P + "输入超出范围\r\n"),
]
allok = True
for inp, want in CASES:
    p = subprocess.run([EXE], input=(inp + "\n").encode(), capture_output=True)
    got = p.stdout.decode("gbk")
    ok = (got == want)
    allok = allok and ok
    print("%-3s %s" % (inp, "OK" if ok else "不等 -> %r" % got))

print("全部一致" if allok else "有不一致")
