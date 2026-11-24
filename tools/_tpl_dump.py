# -*- coding: utf-8 -*-
"""一次性：把官方计划书模板的版式参数（页面、样式、前若干段的字体字号缩进）导出成文本。"""
import os

from docx import Document
from docx.shared import Pt

SRC = (r"C:\Users\27014\Desktop\头脑风暴"
       r"\附件2：中国国际大学生创新大赛（2027）暨江西科技学院校级选拔赛项目计划书(1).docx")
OUT = r"C:\Users\27014\phone-touchpad\tools\_tpl.txt"

L = []


def w(s=""):
    L.append(s)


doc = Document(SRC)

w("=== 节 / 页面 ===")
for i, sec in enumerate(doc.sections):
    w("节%d 页宽 %.1fmm 页高 %.1fmm 上 %.1fmm 下 %.1fmm 左 %.1fmm 右 %.1fmm 装订线 %.1fmm" % (
        i, sec.page_width.mm, sec.page_height.mm, sec.top_margin.mm, sec.bottom_margin.mm,
        sec.left_margin.mm, sec.right_margin.mm,
        sec.gutter.mm if sec.gutter else 0))

w()
w("=== 样式（段落类）===")
for st in doc.styles:
    try:
        if st.type != 1:
            continue
    except Exception:
        continue
    f = st.font
    w("%-22s 字体=%-12s 字号=%-6s 粗体=%s" % (
        st.name,
        f.name or "-",
        ("%.1fpt" % f.size.pt) if f.size else "-",
        f.bold))

w()
w("=== 段落（前 70 段）===")
for i, p in enumerate(doc.paragraphs[:70]):
    pf = p.paragraph_format
    bits = []
    bits.append("样式=%s" % (p.style.name if p.style else "-"))
    bits.append("对齐=%s" % (pf.alignment if pf.alignment is not None else "-"))
    bits.append("首行缩进=%s" % (("%.1fpt" % pf.first_line_indent.pt) if pf.first_line_indent else "-"))
    bits.append("左缩进=%s" % (("%.1fpt" % pf.left_indent.pt) if pf.left_indent else "-"))
    bits.append("行距=%s" % (pf.line_spacing if pf.line_spacing is not None else "-"))
    bits.append("段前=%s" % (("%.1fpt" % pf.space_before.pt) if pf.space_before else "-"))
    bits.append("段后=%s" % (("%.1fpt" % pf.space_after.pt) if pf.space_after else "-"))
    txt = p.text.strip().replace("\n", " ")[:60]
    w("第%02d段 %s" % (i, " ".join(bits)))
    w("      文: %s" % (txt or "（空）"))
    for r in p.runs[:2]:
        w("      字: %-14s %-8s 粗=%s 斜=%s 下划线=%s" % (
            r.text.strip()[:12] or "（空）",
            r.font.name or "-",
            r.font.bold,
            r.font.italic,
            r.font.underline))
    # 东亚字体在 rPr 里，单独取
    try:
        rpr = p.runs[0]._element.rPr if p.runs else None
        if rpr is not None and rpr.rFonts is not None:
            from docx.oxml.ns import qn
            w("      东亚字体: %s" % rpr.rFonts.get(qn("w:eastAsia")))
    except Exception as e:
        w("      东亚字体: ? %s" % e)

w()
w("=== 表格数 ===")
w(str(len(doc.tables)))

open(OUT, "w", encoding="utf-8").write("\n".join(L))
print("写好", OUT, os.path.getsize(OUT), "字节")
