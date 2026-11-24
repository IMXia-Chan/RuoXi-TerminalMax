# -*- coding: utf-8 -*-
"""一次性：把桌面「头脑风暴」文件夹里的 docx / pdf 全部抽成 UTF-8 文本。"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import read_docs  # noqa: E402

SRC = r"C:\Users\27014\Desktop\头脑风暴"
OUT = r"C:\Users\27014\phone-touchpad\tools\_ext"

os.makedirs(OUT, exist_ok=True)
for i, name in enumerate(sorted(os.listdir(SRC)), 1):
    low = name.lower()
    if not (low.endswith(".docx") or low.endswith(".pdf")):
        continue
    path = os.path.join(SRC, name)
    try:
        text = read_docs.docx_text(path) if low.endswith(".docx") else read_docs.pdf_text(path)
    except Exception as e:
        text = "!! 解析失败 " + type(e).__name__ + ": " + str(e)
    dst = os.path.join(OUT, "%02d.txt" % i)
    with open(dst, "w", encoding="utf-8") as f:
        f.write("# " + name + chr(10) + chr(10) + text)
    print("%02d.txt  %-60s %d 字" % (i, name, len(text)))
