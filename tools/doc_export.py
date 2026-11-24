# -*- coding: utf-8 -*-
"""把本项目写的 markdown 稿导出成 .docx 与 .pdf。

用法：
    python doc_export.py <输入.md> [输出目录]

产物：与输入同名的 .docx 和 .pdf（放在输出目录，默认与输入同目录）。

★ 排版按《头脑风暴》大赛模板要求：
  A4；上/左 30mm，下/右 25mm；正文宋体小四、行距 1.25 倍、首行缩进 2 字符；
  一级标题黑体小三、二级黑体四号、三级黑体小四；表格宋体五号；页码页脚居中。

★ PDF 走 Edge 无头打印（本机没有 poppler，也调不动 Word），
  所以 PDF 与 docx 是两套渲染，版式接近但不等同；对外发 PDF、提交用 docx。
"""
import os
import re
import subprocess
import sys

from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt

EDGE_CANDIDATES = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
]

HEI = "黑体"
SONG = "宋体"
MONO = "Consolas"


# ─────────────────────────── markdown 解析 ───────────────────────────

def parse(md):
    """返回块列表：('h',level,text) ('p',text) ('li',text,ordered)
    ('table',rows) ('code',text) ('quote',text) ('hr',)"""
    blocks = []
    lines = md.split("\n")
    i = 0
    n = len(lines)
    while i < n:
        line = lines[i]
        s = line.strip()

        if s.startswith("```"):
            i += 1
            buf = []
            while i < n and not lines[i].strip().startswith("```"):
                buf.append(lines[i])
                i += 1
            i += 1
            blocks.append(("code", "\n".join(buf)))
            continue

        if not s:
            i += 1
            continue

        if s in ("---", "***", "___"):
            blocks.append(("hr",))
            i += 1
            continue

        m = re.match(r"^(#{1,4})\s+(.*)$", s)
        if m:
            blocks.append(("h", len(m.group(1)), m.group(2).strip()))
            i += 1
            continue

        if s.startswith("|"):
            rows = []
            while i < n and lines[i].strip().startswith("|"):
                raw = lines[i].strip().strip("|")
                cells = [c.strip() for c in raw.split("|")]
                if not all(re.fullmatch(r":?-{2,}:?", c) for c in cells if c):
                    rows.append(cells)
                i += 1
            if rows:
                blocks.append(("table", rows))
            continue

        m = re.match(r"^[-*]\s+(.*)$", s)
        if m:
            blocks.append(("li", m.group(1), False, "·"))
            i += 1
            continue

        m = re.match(r"^(\d+)[.、]\s+(.*)$", s)
        if m:
            # ★ 序号必须原样保留：条目是引用得到的内容（如「三阶段」「四道闸」），
            #   丢了序号读者就无法对应正文里的「第 3 阶段」。
            blocks.append(("li", m.group(2), True, m.group(1) + ". "))
            i += 1
            continue

        if s.startswith(">"):
            blocks.append(("quote", s.lstrip("> ").strip()))
            i += 1
            continue

        blocks.append(("p", s))
        i += 1
    return blocks


INLINE = re.compile(r"(\*\*.+?\*\*|`[^`]+`)")


def inline_runs(paragraph, text, size=Pt(12), base_font=SONG):
    for part in INLINE.split(text):
        if not part:
            continue
        if part.startswith("**") and part.endswith("**"):
            run = paragraph.add_run(part[2:-2])
            run.bold = True
        elif part.startswith("`") and part.endswith("`"):
            run = paragraph.add_run(part[1:-1])
            run.font.name = MONO
            run._element.rPr.rFonts.set(qn("w:eastAsia"), SONG)
            run.font.size = Pt(size.pt - 1)
            continue
        else:
            run = paragraph.add_run(part)
        run.font.name = base_font
        run._element.rPr.rFonts.set(qn("w:eastAsia"), base_font)
        run.font.size = size


# ─────────────────────────── docx 生成 ───────────────────────────

def set_page(doc):
    for sec in doc.sections:
        sec.page_width = Cm(21.0)
        sec.page_height = Cm(29.7)
        sec.top_margin = Cm(3.0)
        sec.left_margin = Cm(3.0)
        sec.bottom_margin = Cm(2.5)
        sec.right_margin = Cm(2.5)


def add_page_number_footer(doc):
    for sec in doc.sections:
        p = sec.footer.paragraphs[0]
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        run = p.add_run()
        f1 = OxmlElement("w:fldChar")
        f1.set(qn("w:fldCharType"), "begin")
        it = OxmlElement("w:instrText")
        it.set(qn("xml:space"), "preserve")
        it.text = "PAGE"
        f2 = OxmlElement("w:fldChar")
        f2.set(qn("w:fldCharType"), "end")
        run._r.append(f1)
        run._r.append(it)
        run._r.append(f2)
        run.font.name = "Times New Roman"
        run.font.size = Pt(9)


def build_docx(blocks, out_path):
    doc = Document()
    set_page(doc)

    normal = doc.styles["Normal"]
    normal.font.name = SONG
    normal.element.rPr.rFonts.set(qn("w:eastAsia"), SONG)
    normal.font.size = Pt(12)
    normal.paragraph_format.line_spacing = 1.25
    normal.paragraph_format.space_before = Pt(0)
    normal.paragraph_format.space_after = Pt(0)

    heads = {1: (HEI, Pt(15), Pt(24), Pt(6)),
             2: (HEI, Pt(14), Pt(12), Pt(6)),
             3: (HEI, Pt(12), Pt(12), Pt(3)),
             4: (HEI, Pt(12), Pt(6), Pt(3))}

    first_h1 = True
    for b in blocks:
        kind = b[0]

        if kind == "h":
            level, text = b[1], b[2]
            p = doc.add_paragraph()
            if level == 1 and first_h1:
                p.alignment = WD_ALIGN_PARAGRAPH.CENTER
                first_h1 = False
            font, size, before, after = heads.get(level, heads[3])
            p.paragraph_format.space_before = before
            p.paragraph_format.space_after = after
            p.paragraph_format.line_spacing = 1.25
            if level >= 2:
                p.paragraph_format.first_line_indent = Pt(0)
            run = p.add_run(text)
            run.font.name = font
            run._element.rPr.rFonts.set(qn("w:eastAsia"), font)
            run.font.size = size
            run.bold = True
            # 让 Word 目录能抓到
            p.style = doc.styles["Heading %d" % min(level, 4)]

        elif kind == "p":
            p = doc.add_paragraph()
            p.paragraph_format.first_line_indent = Pt(24)
            p.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
            inline_runs(p, b[1])

        elif kind == "li":
            p = doc.add_paragraph()
            p.paragraph_format.left_indent = Pt(24)
            p.paragraph_format.first_line_indent = Pt(0)
            inline_runs(p, b[3] + b[1])

        elif kind == "quote":
            p = doc.add_paragraph()
            p.paragraph_format.left_indent = Pt(24)
            p.paragraph_format.first_line_indent = Pt(0)
            inline_runs(p, b[1], size=Pt(10.5))

        elif kind == "code":
            p = doc.add_paragraph()
            p.paragraph_format.left_indent = Pt(12)
            p.paragraph_format.first_line_indent = Pt(0)
            p.paragraph_format.line_spacing = 1.0
            run = p.add_run(b[1])
            run.font.name = MONO
            run._element.rPr.rFonts.set(qn("w:eastAsia"), SONG)
            run.font.size = Pt(9)

        elif kind == "table":
            rows = b[1]
            cols = max(len(r) for r in rows)
            table = doc.add_table(rows=len(rows), cols=cols)
            table.style = "Table Grid"
            table.alignment = WD_TABLE_ALIGNMENT.CENTER
            for ri, row in enumerate(rows):
                for ci in range(cols):
                    cell = table.cell(ri, ci)
                    cell.text = ""
                    p = cell.paragraphs[0]
                    p.paragraph_format.line_spacing = 1.0
                    p.paragraph_format.first_line_indent = Pt(0)
                    txt = row[ci] if ci < len(row) else ""
                    for part in INLINE.split(txt):
                        if not part:
                            continue
                        bold = part.startswith("**") and part.endswith("**")
                        clean = part[2:-2] if bold else part.strip("`")
                        run = p.add_run(clean)
                        run.font.name = SONG
                        run._element.rPr.rFonts.set(qn("w:eastAsia"), SONG)
                        run.font.size = Pt(10.5)
                        run.bold = bold or ri == 0
            doc.add_paragraph()

        elif kind == "hr":
            p = doc.add_paragraph()
            p.paragraph_format.space_before = Pt(6)
            p.paragraph_format.space_after = Pt(6)

    add_page_number_footer(doc)
    doc.save(out_path)
    return len(blocks)


# ─────────────────────────── pdf（Edge 无头） ───────────────────────────

CSS = """
@page { size: A4; margin: 30mm 25mm 25mm 30mm; }
body { font-family: "宋体", SimSun, serif; font-size: 12pt; line-height: 1.25; }
h1 { font-family: "黑体", SimHei, sans-serif; font-size: 15pt; text-align: center;
     margin: 24pt 0 6pt; }
h2 { font-family: "黑体", SimHei, sans-serif; font-size: 14pt; margin: 12pt 0 6pt; }
h3 { font-family: "黑体", SimHei, sans-serif; font-size: 12pt; margin: 10pt 0 4pt; }
h4 { font-family: "黑体", SimHei, sans-serif; font-size: 12pt; margin: 8pt 0 3pt; }
p { margin: 0; text-indent: 2em; text-align: justify; }
li { margin: 0 0 0 2em; }
table { border-collapse: collapse; width: 100%; font-size: 10.5pt; margin: 6pt 0 10pt; }
th, td { border: 1px solid #444; padding: 3pt 5pt; vertical-align: top; }
th { background: #efefef; font-weight: bold; }
pre { font-family: Consolas, monospace; font-size: 9pt; line-height: 1.15;
      margin: 6pt 0; white-space: pre-wrap; }
blockquote { margin: 6pt 2em; font-size: 10.5pt; color: #333; }
hr { border: none; border-top: 1px solid #ccc; margin: 10pt 0; }
"""


def build_html(blocks, out_path):
    def esc(t):
        return (t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))

    def inline(t):
        t = esc(t)
        t = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", t)
        t = re.sub(r"`([^`]+)`", r"<code>\1</code>", t)
        return t

    parts = ['<meta charset="utf-8"><style>' + CSS + "</style>"]
    for b in blocks:
        kind = b[0]
        if kind == "h":
            parts.append("<h%d>%s</h%d>" % (b[1], inline(b[2]), b[1]))
        elif kind == "p":
            parts.append("<p>%s</p>" % inline(b[1]))
        elif kind == "li":
            parts.append("<div style=\"margin-left:2em;text-indent:0\">%s%s</div>"
                         % (b[3], inline(b[1])))
        elif kind == "quote":
            parts.append("<blockquote>%s</blockquote>" % inline(b[1]))
        elif kind == "code":
            parts.append("<pre>%s</pre>" % esc(b[1]))
        elif kind == "table":
            rows = b[1]
            parts.append("<table>")
            for ri, row in enumerate(rows):
                tag = "th" if ri == 0 else "td"
                cells = "".join("<%s>%s</%s>" % (tag, inline(c), tag) for c in row)
                parts.append("<tr>%s</tr>" % cells)
            parts.append("</table>")
        elif kind == "hr":
            parts.append("<hr>")
    html = ("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
            "<title>doc</title><style>" + CSS + "</style></head><body>"
            + "".join(parts) + "</body></html>")
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(html)


def to_pdf(html_path, pdf_path):
    edge = next((e for e in EDGE_CANDIDATES if os.path.isfile(e)), None)
    if not edge:
        print("!! 找不到 Edge，跳过 PDF")
        return False
    url = "file:///" + html_path.replace("\\", "/")
    cmd = [edge, "--headless=new", "--disable-gpu", "--no-pdf-header-footer",
           "--print-to-pdf=" + pdf_path, url]
    r = subprocess.run(cmd, capture_output=True)
    if not os.path.isfile(pdf_path):
        print("!! PDF 生成失败", r.stderr.decode("utf-8", "replace")[:400])
        return False
    return True


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    md_path = sys.argv[1]
    out_dir = sys.argv[2] if len(sys.argv) > 2 else os.path.dirname(os.path.abspath(md_path))
    stem = os.path.splitext(os.path.basename(md_path))[0]

    with open(md_path, encoding="utf-8") as f:
        md = f.read()

    blocks = parse(md)
    docx_path = os.path.join(out_dir, stem + ".docx")
    n = build_docx(blocks, docx_path)
    print("docx  %s  (%d 块, %.1f KB)" % (docx_path, n, os.path.getsize(docx_path) / 1024))

    html_path = os.path.join(out_dir, "_" + stem + ".html")
    build_html(blocks, html_path)
    pdf_path = os.path.join(out_dir, stem + ".pdf")
    if to_pdf(html_path, pdf_path):
        print("pdf   %s  (%.1f KB)" % (pdf_path, os.path.getsize(pdf_path) / 1024))
    os.remove(html_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
