# -*- coding: utf-8 -*-
"""把 .docx / .pdf 的正文抽成纯文本，供阅读与改写参考。

用法（任意目录）：python read_docs.py <文件>
"""
import re
import sys
import zipfile


def docx_text(path):
    with zipfile.ZipFile(path) as z:
        xml = z.read("word/document.xml").decode("utf-8", "replace")
    out = []
    for para in re.findall(r"<w:p[ >].*?</w:p>", xml, re.S):
        texts = re.findall(r"<w:t[^>]*>(.*?)</w:t>", para, re.S)
        line = "".join(texts)
        line = (line.replace("&amp;", "&").replace("&lt;", "<")
                    .replace("&gt;", ">").replace("&quot;", '"')
                    .replace("&apos;", "'"))
        style = re.search(r'<w:pStyle w:val="([^"]+)"', para)
        if line.strip():
            tag = style.group(1) if style else ""
            if tag and tag[0].isdigit():
                line = "[" + tag + "] " + line
            out.append(line)
        else:
            out.append("")
    return chr(10).join(out)


def pdf_text(path):
    try:
        import fitz
        with fitz.open(path) as d:
            return chr(10).join("=== 第 " + str(i + 1) + " 页 ===" + chr(10) + p.get_text()
                                for i, p in enumerate(d))
    except ImportError:
        pass
    try:
        from pypdf import PdfReader
    except ImportError:
        try:
            from PyPDF2 import PdfReader
        except ImportError:
            return "!! 没有 PDF 解析库（fitz / pypdf / PyPDF2 都没有）"
    r = PdfReader(path)
    return chr(10).join("=== 第 " + str(i + 1) + " 页 ===" + chr(10) + (p.extract_text() or "")
                        for i, p in enumerate(r.pages))


def main():
    path = sys.argv[1]
    low = path.lower()
    if low.endswith(".docx"):
        text = docx_text(path)
    elif low.endswith(".pdf"):
        text = pdf_text(path)
    else:
        text = "!! 不认的格式"
    if len(sys.argv) > 2:
        # 控制台是 GBK，中文会乱码；给第二个参数就把结果写成 UTF-8 文件
        with open(sys.argv[2], "w", encoding="utf-8") as f:
            f.write(text)
        print("已写出", sys.argv[2], len(text), "字")
    else:
        print(text)


if __name__ == "__main__":
    main()
