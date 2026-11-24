# -*- coding: utf-8 -*-
"""一次性：检查导出的 docx / pdf 是否完整。"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import read_docs

BASE = r"C:\Users\27014\Desktop\头脑风暴"
STEM = "PRD-ConMarn-v2.0"

docx = os.path.join(BASE, STEM + ".docx")
pdf = os.path.join(BASE, STEM + ".pdf")

text = read_docs.docx_text(docx)
lines = [l for l in text.split(chr(10)) if l.strip()]
print("docx 段落数", len(lines))
print("docx 前 12 行：")
for l in lines[:12]:
    print("   ", l[:70])

from pypdf import PdfReader
r = PdfReader(pdf)
print("pdf 页数", len(r.pages))
head = (r.pages[0].extract_text() or "").split(chr(10))
print("pdf 首页前 8 行：")
for l in head[:8]:
    print("   ", l[:70])
last = (r.pages[-1].extract_text() or "").split(chr(10))
print("pdf 末页尾 3 行：")
for l in last[-3:]:
    print("   ", l[:70])
