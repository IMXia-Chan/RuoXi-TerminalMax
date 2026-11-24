# -*- coding: utf-8 -*-
"""一次性：第 7 题（购物折扣）写 GBK 源码、编译、验值。"""
import os
import subprocess

SRC = r"C:\Users\27014\Desktop\第7题-购物折扣.c"
GCC = r"D:\Dev-Cpp\MinGW64\bin\gcc.exe"
EXE = r"C:\Users\27014\phone-touchpad\tools\_hw7.exe"

CODE = """#define _CRT_SECURE_NO_WARNINGS
#include <stdio.h>

int main(void)
{
    double money, pay;

    printf("请输入购物金额：");

    if (scanf("%lf", &money) != 1) {
        printf("输入无效\\n");
        return 1;
    }

    if (money < 200) {
        pay = money;
    } else if (money < 500) {
        pay = money * 0.9;
    } else if (money < 1000) {
        pay = money * 0.8;
    } else {
        pay = money * 0.75;
    }

    printf("实际付款金额是%.1f。\\n", pay);

    return 0;
}
"""
open(SRC, "wb").write(CODE.encode("gbk"))
print("已写 GBK 源码，%d 字节" % os.path.getsize(SRC))

r = subprocess.run([GCC, "-std=c99", "-Wall", "-o", EXE, SRC], capture_output=True)
print("编译返回码", r.returncode)
if r.stderr:
    print("stderr:", r.stderr.decode("gbk", "replace"))

P = "请输入购物金额："
CASES = [
    ("900",      P + "实际付款金额是720.0。"),
    ("199",      P + "实际付款金额是199.0。"),
    ("200",      P + "实际付款金额是180.0。"),
    ("499",      P + "实际付款金额是449.1。"),
    ("500",      P + "实际付款金额是400.0。"),
    ("999",      P + "实际付款金额是799.2。"),
    ("1000",     P + "实际付款金额是750.0。"),
    ("120.5",    P + "实际付款金额是120.5。"),
]
allok = True
for inp, want in CASES:
    p = subprocess.run([EXE], input=(inp + "\n").encode(), capture_output=True)
    got = p.stdout.decode("gbk")
    ok = (got == want + "\r\n")
    allok = allok and ok
    print("%-8s %s" % (inp, "OK" if ok else "不等 -> %r" % got))

print("全部一致" if allok else "有不一致")
