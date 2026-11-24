# -*- coding: utf-8 -*-
"""一次性：导出模板第 70 段之后的段落与全部表格。"""
import os

from docx import Document

SRC = (r"C:\Users\27014\Desktop\头脑风暴"
       r"\附件2：中国国际大学生创新大赛（2027）暨江西科技学院校级选拔赛项目计划书(1).docx")
OUT = r"C:\Users\27014\phone-touchpad\tools\_tpl2.txt"
L = []

doc = Document(SRC)
paras = doc.paragraphs
print("总段落", len(paras))

for i, p in enumerate(paras):
    if i < 70:
        continue
    pf = p.paragraph_format
    ind = ("%.1fpt" % pf.first_line_indent.pt) if pf.first_line_indent else "-"
    font = "-"
    size = "-"
    if p.runs:
        r = p.runs[0]
        font = r.font.name or "-"
        try:
            from docx.oxml.ns import qn
            rpr = r._element.rPr
            if rpr is not None and rpr.rFonts is not None:
                font = rpr.rFonts.get(qn("w:eastAsia")) or font
        except Exception:
            pass
        size = ("%.1fpt" % r.font.size.pt) if r.font.size else "-"
    L.append("第%02d段 缩进=%s 字体=%s 字号=%s | %s"
             % (i, ind, font, size, p.text.strip()[:70] or "（空）"))

L.append("")
L.append("=== 表格 ===")
for ti, t in enumerate(doc.tables):
    L.append("表%d：%d 行 × %d 列" % (ti, len(t.rows), len(t.columns)))
    for ri, row in enumerate(t.rows[:8]):
        cells = [c.text.strip().replace("\n", " ")[:18] for c in row.cells]
        L.append("   " + " | ".join(cells))
    L.append("")

open(OUT, "w", encoding="utf-8").write("\n".join(L))
print("写好", OUT, os.path.getsize(OUT), "字节")
