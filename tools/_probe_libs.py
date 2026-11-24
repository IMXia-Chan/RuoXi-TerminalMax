# -*- coding: utf-8 -*-
"""一次性探针：这台机上有哪些能读写 Office / PDF 的库。"""
for m in ["docx", "fitz", "pypdf", "PyPDF2", "pdfminer", "PIL", "reportlab"]:
    try:
        mod = __import__(m)
        print(m, "OK", getattr(mod, "__version__", ""))
    except Exception as e:
        print(m, "NO", type(e).__name__)
