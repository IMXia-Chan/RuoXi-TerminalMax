# -*- coding: utf-8 -*-
"""把项目计划书 markdown 排成 .docx。

★ 做法：拿学校下发的样板 docx 当底稿，只替换文字。
  字体、字号、缩进、段前段后、页面设置、目录样式、封面信息表全部沿用样板，
  因此成品与样板视觉一致，不再靠"按文字要求猜参数"。

用法：
    python plan_export.py <输入.md> [输出.docx]
"""
import os
import re
import sys
from copy import deepcopy

from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Pt, RGBColor
from docx.table import Table

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import doc_export

TEMPLATE = (r"C:\Users\27014\Desktop\头脑风暴"
            r"\附件2：中国国际大学生创新大赛（2027）暨江西科技学院校级选拔赛项目计划书(1).docx")

SONG = "宋体"
TNR = "Times New Roman"

# 封面信息表：False = 完全照样板（方正小标宋简体，笔画较重）；
#             True  = 改用宋体三号（《格式要求》表2 的字面口径）。
COVER_CELL_SONG = False
SZ_BODY = 12          # 正文小四
SZ_CAPTION = 10.5     # 图表题注五号

CAP = re.compile(r"^(表|图)\s*\d+\s*[-－]\s*\d+")

PGNUM_SUCCESSORS = (
    "w:cols", "w:formProt", "w:vAlign", "w:noEndnote", "w:titlePg",
    "w:textDirection", "w:bidi", "w:rtlGutter", "w:docGrid",
    "w:printerSettings", "w:sectPrChange",
)
PPR_SUCCESSORS = ("w:divId", "w:cnfStyle", "w:rPr", "w:sectPr", "w:pPrChange")


# ─────────────────────── 从样板取样 ───────────────────────

def sample(tpl):
    """取样板里各类段落的 pPr / rPr，以及封面信息表。"""
    ps = list(tpl.paragraphs)

    def pick(pred, idx):
        # 目录条目形如「一、项目背景与痛点分析\t1」，带制表符，必须排除，
        # 否则会被当成正文一级标题（它排在正文之前）。
        for p in ps:
            t = p.text.strip()
            if "\t" not in t and pred(t):
                return p
        return ps[idx]

    spots = {
        "cover1": pick(lambda t: t.startswith("2026年江西科技学院"), 0),
        "cover2": pick(lambda t: t == "项目计划书", 1),
        "date": pick(lambda t: "年" in t and "月" in t and "日" in t, 5),
        "h1": pick(lambda t: t.startswith("一、项目背景"), 29),
        "h2": pick(lambda t: t.startswith("1.1"), 30),
        "toctitle": pick(lambda t: t == "目录", 7),
    }

    out = {}
    for k, p in spots.items():
        pPr = p._p.pPr
        rPr = p.runs[0]._r.rPr if p.runs else None
        out[k] = (deepcopy(pPr) if pPr is not None else None,
                  deepcopy(rPr) if rPr is not None else None)

    out["cover_tbl"] = deepcopy(tpl.tables[0]._tbl) if tpl.tables else None

    # 表格单元格的格式也从样板取，不自己写字体
    def first_run_rPr(tbl):
        for row in tbl.rows:
            for cell in row.cells:
                for p in cell.paragraphs:
                    if p.runs and p.runs[0]._r.rPr is not None:
                        return deepcopy(p.runs[0]._r.rPr)
        return None

    out["cover_cell"] = first_run_rPr(tpl.tables[0]) if tpl.tables else None
    out["body_cell"] = (first_run_rPr(tpl.tables[1])
                        if len(tpl.tables) > 1 else out["cover_cell"])
    return out


# ─────────────────────── 排版小工具 ───────────────────────

def add_sampled(doc, s, key, text, outline=None):
    """按样板块落格式写一段，可选写入大纲级别（供目录域抓取）。"""
    pPr, rPr = s[key]
    p = doc.add_paragraph()
    if pPr is not None:
        p._p.insert(0, deepcopy(pPr))
    run = p.add_run(text)
    if rPr is not None:
        run._r.insert(0, deepcopy(rPr))
    if outline is not None:
        ppr = p._p.get_or_add_pPr()
        ol = OxmlElement("w:outlineLvl")
        ol.set(qn("w:val"), str(outline))
        ppr.insert_element_before(ol, *PPR_SUCCESSORS)
    return p


def set_font(run, font, size, bold=False):
    run.font.name = font
    run.font.size = Pt(size)
    run.bold = bold
    run.font.color.rgb = RGBColor(0, 0, 0)
    rpr = run._element.get_or_add_rPr()
    rf = rpr.find(qn("w:rFonts"))
    if rf is None:
        rf = OxmlElement("w:rFonts")
        rpr.append(rf)
    rf.set(qn("w:eastAsia"), font)
    rf.set(qn("w:ascii"), TNR if font == SONG else font)
    rf.set(qn("w:hAnsi"), TNR if font == SONG else font)


def run_text(p, text, base_rPr, size=None, mono=False):
    """按 **加粗** / `代码` 切段写入，字体沿用样板 run 的 rPr。"""
    for part in doc_export.INLINE.split(text):
        if not part:
            continue
        bold = part.startswith("**") and part.endswith("**")
        code = part.startswith("`") and part.endswith("`")
        t = part[2:-2] if bold else (part[1:-1] if code else part)
        r = p.add_run(t)
        if base_rPr is not None:
            r._r.insert(0, deepcopy(base_rPr))
        if code or mono:
            r.font.name = "Consolas"
        if size:
            r.font.size = Pt(size)


def body_para(doc, s, text, align=WD_ALIGN_PARAGRAPH.JUSTIFY, indent=True,
              size=None, before=0, after=0, line=None):
    """正文段：段落格式取样板一级标题的 pPr（首行缩进 2 字符），
    字体取样板二级标题的 rPr（方正仿宋_GB2312 14pt、不加粗）。"""
    pPr, _ = s["h1"]
    _, rPr = s["h2"]
    p = doc.add_paragraph()
    if pPr is not None:
        p._p.insert(0, deepcopy(pPr))
    p.alignment = align
    pf = p.paragraph_format
    if not indent:
        pf.first_line_indent = Pt(0)
    pf.space_before = Pt(before)
    pf.space_after = Pt(after)
    if line is not None:
        pf.line_spacing = line
    run_text(p, text, rPr, size)
    return p


def plain(doc, text, font=SONG, size=SZ_BODY, bold=False,
          align=WD_ALIGN_PARAGRAPH.CENTER, before=0, after=0, line=1.0):
    p = doc.add_paragraph()
    p.alignment = align
    pf = p.paragraph_format
    pf.first_line_indent = Pt(0)
    pf.space_before = Pt(before)
    pf.space_after = Pt(after)
    pf.line_spacing = line
    if text:
        set_font(p.add_run(text), font, size, bold)
    return p


def strip_hdr_ftr(sec):
    sectPr = sec._sectPr
    for tag in ("w:headerReference", "w:footerReference", "w:titlePg"):
        for el in sectPr.findall(qn(tag)):
            sectPr.remove(el)


def set_pgnum(sec, fmt=None, start=None):
    sectPr = sec._sectPr
    el = sectPr.find(qn("w:pgNumType"))
    if el is None:
        el = OxmlElement("w:pgNumType")
        sectPr.insert_element_before(el, *PGNUM_SUCCESSORS)
    if fmt:
        el.set(qn("w:fmt"), fmt)
    if start is not None:
        el.set(qn("w:start"), str(start))


def footer_pagenum(sec):
    sec.footer.is_linked_to_previous = False
    p = sec.footer.paragraphs[0]
    for r in list(p.runs):
        r._r.getparent().remove(r._r)
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.first_line_indent = Pt(0)
    run = p.add_run()
    b = OxmlElement("w:fldChar")
    b.set(qn("w:fldCharType"), "begin")
    it = OxmlElement("w:instrText")
    it.set(qn("xml:space"), "preserve")
    it.text = "PAGE"
    e = OxmlElement("w:fldChar")
    e.set(qn("w:fldCharType"), "end")
    run._r.append(b)
    run._r.append(it)
    run._r.append(e)
    set_font(run, TNR, 9)


def toc_field(doc):
    p = doc.add_paragraph()
    p.paragraph_format.first_line_indent = Pt(0)
    p.paragraph_format.line_spacing = 1.0

    run = p.add_run()
    b = OxmlElement("w:fldChar")
    b.set(qn("w:fldCharType"), "begin")
    it = OxmlElement("w:instrText")
    it.set(qn("xml:space"), "preserve")
    it.text = 'TOC \\o "1-3" \\h \\z \\u'
    sep = OxmlElement("w:fldChar")
    sep.set(qn("w:fldCharType"), "separate")
    run._r.append(b)
    run._r.append(it)
    run._r.append(sep)

    hint = p.add_run("（打开本文档后，在弹出的提示里选「是」，目录即自动生成）")
    set_font(hint, SONG, SZ_BODY)

    run2 = p.add_run()
    e = OxmlElement("w:fldChar")
    e.set(qn("w:fldCharType"), "end")
    run2._r.append(e)

    st = doc.settings.element
    if st.find(qn("w:updateFields")) is None:
        uf = OxmlElement("w:updateFields")
        uf.set(qn("w:val"), "true")
        st.insert_element_before(uf, "w:hdrShapeDefaults", "w:footnotePr",
                                 "w:endnotePr", "w:compat", "w:docVars",
                                 "w:rsids", "w:mathPr")


def table_borders(t):
    """样板是 WPS 文档，不一定有「Table Grid」这个表格样式，边框直接写死。"""
    tblPr = t._tbl.tblPr
    old = tblPr.find(qn("w:tblBorders"))
    if old is not None:
        tblPr.remove(old)
    borders = OxmlElement("w:tblBorders")
    for edge in ("top", "left", "bottom", "right", "insideH", "insideV"):
        el = OxmlElement("w:" + edge)
        el.set(qn("w:val"), "single")
        el.set(qn("w:sz"), "4")
        el.set(qn("w:space"), "0")
        el.set(qn("w:color"), "000000")
        borders.append(el)
    tblPr.insert_element_before(borders, "w:shd", "w:tblLayout", "w:tblCellMar",
                                "w:tblLook", "w:tblCaption", "w:tblDescription")


def fill_cell(cell, text, rPr=None, font=None, size=None):
    """整格重建：格式要么用样板现成的 rPr，要么显式指定（封面表格那个开关）。"""
    p = cell.paragraphs[0]
    for extra in cell.paragraphs[1:]:
        extra._p.getparent().remove(extra._p)
    # 表格单元格不参与正文的首行缩进，样式里带过来的一并清掉
    p.paragraph_format.first_line_indent = Pt(0)
    for r in list(p.runs):
        r._r.getparent().remove(r._r)
    run = p.add_run(text)
    if rPr is not None:
        run._r.insert(0, deepcopy(rPr))
    if font is not None:
        set_font(run, font, size)


# ─────────────────────── 主流程 ───────────────────────

def mirror_para_mark(doc):
    """把每段首个 run 的字符格式抄到段落标记上。

    段落标记（Word 里的回车符）本身带一套字符格式，在它后面接着打字，
    新字会继承那套格式——这正是"自己写的字莫名其妙变粗"的来源。
    抄成与正文一致之后，续写就不会跑偏，同时也没有改动样板给的任何格式。
    """
    body = doc.element.body
    for p in body.iter(qn("w:p")):
        runs = p.findall(qn("w:r"))
        if not runs:
            continue
        src = runs[0].find(qn("w:rPr"))
        if src is None:
            continue
        pPr = p.find(qn("w:pPr"))
        if pPr is None:
            continue
        old = pPr.find(qn("w:rPr"))
        if old is not None:
            pPr.remove(old)
        pPr.insert_element_before(deepcopy(src), "w:sectPr", "w:pPrChange")


def split_md(blocks):
    cover_title, meta, body, seen = [], [], [], False
    for b in blocks:
        if b[0] == "h" and b[1] == 1 and len(cover_title) < 2:
            cover_title.append(b[2])
            continue
        if cover_title and not seen and b[0] == "table":
            meta = b[1]
            seen = True
            continue
        if not cover_title:
            continue
        body.append(b)
    return cover_title, meta, body


def build(md_path, out_path):
    blocks = doc_export.parse(open(md_path, encoding="utf-8").read())
    cover_title, meta, body = split_md(blocks)

    tpl = Document(TEMPLATE)
    s = sample(tpl)

    doc = Document(TEMPLATE)
    root = doc.element.body
    for child in list(root):
        if child.tag == qn("w:sectPr"):
            continue
        root.remove(child)

    # ── 封面 ──
    add_sampled(doc, s, "cover1", cover_title[0] if cover_title else "")
    add_sampled(doc, s, "cover2",
                cover_title[1] if len(cover_title) > 1 else "项目计划书")

    rows = [r for r in meta if len(r) >= 2 and r[0] not in ("项", "内容")]
    finish = ""
    for r in rows:
        if r[0].startswith("项目完成时间"):
            finish = r[1].strip()
    rows = [r for r in rows if not r[0].startswith("项目完成时间")]

    cover_tbl_obj = None
    if s["cover_tbl"] is not None and rows:
        tbl_el = deepcopy(s["cover_tbl"])
        root.insert(list(root).index(root.find(qn("w:sectPr"))), tbl_el)
        t = Table(tbl_el, doc._body)
        while len(t.rows) < len(rows):
            t._tbl.append(deepcopy(t.rows[-1]._tr))
        for i, r in enumerate(rows):
            row = t.rows[i]
            for cell, txt in ((row.cells[0], r[0]),
                              (row.cells[1], "："),
                              (row.cells[2], r[1])):
                if COVER_CELL_SONG:
                    fill_cell(cell, txt, font=SONG, size=16)
                else:
                    fill_cell(cell, txt, rPr=s["cover_cell"])
        cover_tbl_obj = t

    add_sampled(doc, s, "date", finish or "二〇二六年十一月")

    # ── 目录 ──
    doc.add_section(WD_SECTION.NEW_PAGE)
    add_sampled(doc, s, "toctitle", "目录")
    toc_field(doc)

    # ── 正文 ──
    doc.add_section(WD_SECTION.NEW_PAGE)

    for b in body:
        kind = b[0]

        if kind == "h":
            level, text = b[1], b[2]
            text = text.replace("**", "")
            if level <= 2:
                add_sampled(doc, s, "h1", text, outline=0)
            elif level == 3:
                add_sampled(doc, s, "h2", text, outline=1)
            else:
                p = add_sampled(doc, s, "h2", text, outline=2)
                for r in p.runs:
                    r.bold = True

        elif kind == "p":
            text = b[1]
            if text.startswith("!["):
                m = re.match(r"!\[(.*?)\]\((.*?)\)", text)
                body_para(doc, s, m.group(1) if m else text,
                          WD_ALIGN_PARAGRAPH.CENTER, False, SZ_CAPTION, 6, 6, 1.0)
            elif CAP.match(text):
                body_para(doc, s, text.replace("**", ""),
                          WD_ALIGN_PARAGRAPH.CENTER, False, SZ_CAPTION, 6, 3, 1.0)
            else:
                body_para(doc, s, text)

        elif kind == "li":
            body_para(doc, s, b[3] + b[1])

        elif kind == "quote":
            body_para(doc, s, b[1])

        elif kind == "code":
            p = doc.add_paragraph()
            p.paragraph_format.first_line_indent = Pt(0)
            p.paragraph_format.line_spacing = 1.0
            set_font(p.add_run(b[1]), "Consolas", 9)

        elif kind == "table":
            rows_t = b[1]
            cols = max(len(r) for r in rows_t)
            t = doc.add_table(rows=len(rows_t), cols=cols)
            table_borders(t)
            t.alignment = WD_TABLE_ALIGNMENT.CENTER
            for ri, row in enumerate(rows_t):
                for ci in range(cols):
                    cell = t.cell(ri, ci)
                    cell.text = ""
                    p = cell.paragraphs[0]
                    p.paragraph_format.line_spacing = 1.0
                    p.paragraph_format.first_line_indent = Pt(0)
                    p.paragraph_format.space_before = Pt(0)
                    p.paragraph_format.space_after = Pt(0)
                    txt = row[ci] if ci < len(row) else ""
                    for part in doc_export.INLINE.split(txt):
                        if not part:
                            continue
                        bold = part.startswith("**") and part.endswith("**")
                        clean = part[2:-2] if bold else part.strip("`")
                        run = p.add_run(clean)
                        if s["body_cell"] is not None:
                            run._r.insert(0, deepcopy(s["body_cell"]))
            plain(doc, "", line=1.0)

    # ── 收口：段落标记与正文同格式，避免续写时格式跑偏 ──
    mirror_para_mark(doc)

    # ── 自检：封面信息格实际用的字体 ──
    if cover_tbl_obj is not None:
        for r in cover_tbl_obj.rows[0].cells[2]._tc.findall(".//" + qn("w:r"))[:1]:
            rPr = r.find(qn("w:rPr"))
            rf = rPr.find(qn("w:rFonts")) if rPr is not None else None
            sz = rPr.find(qn("w:sz")) if rPr is not None else None
            print("封面内容格：字体=%s 字号=%s 半磅"
                  % (rf.get(qn("w:eastAsia")) if rf is not None else "-",
                     sz.get(qn("w:val")) if sz is not None else "-"))

    # ── 分节与页码 ──
    secs = doc.sections
    for sec in secs:
        strip_hdr_ftr(sec)
    if len(secs) >= 3:
        set_pgnum(secs[1], "upperRoman", 1)
        footer_pagenum(secs[1])
        set_pgnum(secs[2], "decimal", 1)
        footer_pagenum(secs[2])

    # 目标文件若正被 Word/WPS 打开会写不进去，自动换个名字存，不打断流程
    try:
        doc.save(out_path)
    except PermissionError:
        base, ext = os.path.splitext(out_path)
        for i in range(2, 20):
            alt = "%s-%d%s" % (base, i, ext)
            try:
                doc.save(alt)
                print("!! 原文件被 Word/WPS 占用，改存为：%s" % alt)
                out_path = alt
                break
            except PermissionError:
                continue
        else:
            raise
    return out_path, len(body)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    md = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.splitext(md)[0] + ".docx"
    out, n = build(md, out)

    # 自检：把关键事实打出来，一眼能看出字体有没有继承到
    d = Document(out)
    print("docx %s（正文 %d 块，%.1f KB）"
          % (out, n, os.path.getsize(out) / 1024.0))
    print("节 %d，表格 %d，非空段落 %d"
          % (len(d.sections), len(d.tables),
             sum(1 for p in d.paragraphs if p.text.strip())))
    for p in d.paragraphs:
        if not p.text.strip() or not p.runs:
            continue
        r = p.runs[0]
        rf = r._r.rPr.rFonts if r._r.rPr is not None else None
        ea = rf.get(qn("w:eastAsia")) if rf is not None else None
        size = ("%.1fpt" % r.font.size.pt) if r.font.size else "-"
        print("  首段字体 %s / %s / 粗=%s —— %s"
              % (ea or r.font.name or "-", size, r.bold, p.text.strip()[:24]))
        break
    return 0


if __name__ == "__main__":
    sys.exit(main())
