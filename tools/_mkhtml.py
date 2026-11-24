# -*- coding: utf-8 -*-
"""一次性：把 md 生成 HTML 预览（与 PDF 同源，用来看版式）。"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import doc_export

md = r"C:\Users\27014\Desktop\头脑风暴\PRD-ConMarn-v2.0.md"
out = r"C:\Users\27014\phone-touchpad\tools\_prd_preview.html"
with open(md, encoding="utf-8") as f:
    blocks = doc_export.parse(f.read())
doc_export.build_html(blocks, out)
print("ok", out, os.path.getsize(out), "字节")
