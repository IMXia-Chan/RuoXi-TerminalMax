# -*- coding: utf-8 -*-
"""一次性：PRD 改过名称后重出 docx 与 pdf。"""
import os
import sys

sys.path.insert(0, r"C:\Users\27014\phone-touchpad\tools")
import doc_export

BASE = r"C:\Users\27014\Desktop\头脑风暴"
STEM = "PRD-ConMarn-v2.0"
md = os.path.join(BASE, STEM + ".md")

blocks = doc_export.parse(open(md, encoding="utf-8").read())
docx = os.path.join(BASE, STEM + ".docx")
n = doc_export.build_docx(blocks, docx)
print("docx", n, "块", os.path.getsize(docx), "字节")

html = os.path.join(BASE, "_" + STEM + ".html")
doc_export.build_html(blocks, html)
pdf = os.path.join(BASE, STEM + ".pdf")
ok = doc_export.to_pdf(html, pdf)
if ok:
    print("pdf", os.path.getsize(pdf), "字节")
os.remove(html)
