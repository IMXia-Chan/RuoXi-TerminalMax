package com.example.touchpad

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * **给她一条交货的路。**
 *
 * 她能写代码、能在沙盒里跑，但「给我一份 Word」这件事一直办不到 ——
 * 最后只能把内容念出来，让用户自己去复制。这一层补的就是这个出口。
 *
 * ## 不引依赖，自己拼 OOXML
 *
 * 项目有一条老规矩：不轻易加库（见 AiAgent 文件头）。而 docx / xlsx / pptx
 * 本质就是**一个 zip 里装几份 XML**，格式在 [ECMA-376] 里是公开的。
 * 自己拼的好处是：不涨包、可控、而且**生成的过程能在电脑上逐字节测**。
 * 代价是每个格式都要写准 —— 少一个关系文件，Office 会弹「需要修复」。
 *
 * ## 支持的格式与内容写法
 *
 * | 格式 | 内容怎么写 |
 * |---|---|
 * | `docx` | Markdown 风格的几行：`# 标题`、`## 小节`、`- 条目`、空行分段 |
 * | `xlsx` | CSV：第一行是表头，逗号分隔，带逗号的格子用双引号包起来 |
 * | `pptx` | `# 标题` 开一页，正文按 `- ` 一条一行 |
 * | `csv` / `md` / `txt` | 原样 |
 *
 * ## ★ 这一层的判据
 *
 * 生成出来的文件**不会在生成时报错**：zip 总是能拼起来，坏的是里面那份 XML。
 * 症状是「双击打开，Office 说要修复」—— 而这句话不会出现在任何日志里。
 * 所以 [DocGenTest] 把每一份 XML 都拆出来验，另有 `tools/` 下的真机开箱用手工做。
 */
object DocGen {

    // ------------------------------------------------------------------
    // 对外
    // ------------------------------------------------------------------

    enum class Kind(val ext: String, val mime: String) {
        DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
        CSV("csv", "text/csv"),
        MD("md", "text/markdown"),
        TXT("txt", "text/plain"),
        // ★ 2026-11-07：图片不是「生成」出来的，是模型直接给的字节（见 [ImageGen]）。
        //   放在这张表里只为了共用 [DocFile] 那套「落盘 + 读回对账」。
        PNG("png", "image/png"),
        JPG("jpg", "image/jpeg"),
    }

    fun kindOf(raw: String): Kind? {
        val k = raw.trim().lowercase().removePrefix(".")
        return when (k) {
            "docx", "word", "doc" -> Kind.DOCX
            "xlsx", "excel", "xls", "table" -> Kind.XLSX
            "pptx", "ppt", "slides" -> Kind.PPTX
            "csv" -> Kind.CSV
            "md", "markdown" -> Kind.MD
            "txt", "text" -> Kind.TXT
            else -> null
        }
    }

    /** 生成文件内容。→ null = 这个格式没有。 */
    fun build(kind: Kind, title: String, content: String, pptx: PptxTemplate? = null): ByteArray = when (kind) {
        Kind.DOCX -> docx(title, content)
        Kind.XLSX -> xlsx(title, content)
        Kind.PPTX -> pptx(pptx ?: error("生成 pptx 需要骨架"), title, content)
        Kind.CSV, Kind.MD, Kind.TXT -> content.toByteArray(Charsets.UTF_8)
        // 图片的字节来自模型，不在这里拼 —— 走到这儿说明调用方搞错了工具。
        Kind.PNG, Kind.JPG -> error("图片要用 draw_picture 生成，make_file 只做文字类文件。")
    }

    // ------------------------------------------------------------------
    // docx
    // ------------------------------------------------------------------

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun docx(title: String, content: String): ByteArray {
        val body = StringBuilder()
        val blocks = blocksOf(content)
        if (title.isNotBlank()) body.append(para(title, "Title"))
        blocks.forEach { b ->
            body.append(
                when (b.level) {
                    1 -> para(b.text, "Heading1")
                    2 -> para(b.text, "Heading2")
                    3 -> para(b.text, "Heading3")
                    else -> if (b.bullet) para("• " + b.text, "Normal", indent = true)
                    else para(b.text, "Normal")
                }
            )
        }
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="$W"><w:body>$body<w:sectPr>
<w:pgSz w:w="11906" w:h="16838"/>
<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/>
</w:sectPr></w:body></w:document>"""

        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="$W">
<w:docDefaults><w:rPrDefault><w:rPr>
<w:rFonts w:ascii="Calibri" w:eastAsia="等线" w:hAnsi="Calibri"/>
<w:sz w:val="22"/></w:rPr></w:rPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/>
<w:pPr><w:spacing w:after="120" w:line="300" w:lineRule="auto"/></w:pPr></w:style>
${heading("Title", "Title", 44)}
${heading("Heading1", "heading 1", 36)}
${heading("Heading2", "heading 2", 30)}
${heading("Heading3", "heading 3", 26)}
</w:styles>"""

        return zip(
            linkedMapOf(
                "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
</Types>""",
                "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>""",
                "word/_rels/document.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>""",
                "word/document.xml" to document,
                "word/styles.xml" to styles,
            )
        )
    }

    private fun heading(id: String, name: String, halfPoints: Int) =
        """<w:style w:type="paragraph" w:styleId="$id"><w:name w:val="$name"/>
<w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
<w:pPr><w:spacing w:before="240" w:after="120"/><w:outlineLvl w:val="${
            when (id) { "Heading1" -> 0; "Heading2" -> 1; "Heading3" -> 2; else -> 0 }
        }"/></w:pPr>
<w:rPr><w:b/><w:sz w:val="$halfPoints"/></w:rPr></w:style>"""

    private fun para(text: String, style: String, indent: Boolean = false): String {
        val ppr = if (indent) "<w:pPr><w:ind w:left=\"420\"/></w:pPr>" else ""
        val runs = if (text.isEmpty()) "" else
            "<w:r><w:t xml:space=\"preserve\">${xml(text)}</w:t></w:r>"
        return "<w:p><w:pPr><w:pStyle w:val=\"$style\"/>$ppr</w:pPr>$runs</w:p>"
    }

    // ------------------------------------------------------------------
    // xlsx
    // ------------------------------------------------------------------

    fun xlsx(title: String, content: String): ByteArray {
        val rows = parseCsv(content)
        val sd = StringBuilder("<sheetData>")
        rows.forEachIndexed { r, row ->
            sd.append("<row r=\"").append(r + 1).append("\">")
            row.forEachIndexed { c, cell ->
                if (cell.isEmpty()) return@forEachIndexed
                sd.append("<c r=\"").append(colName(c)).append(r + 1)
                    .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                    .append(xml(cell)).append("</t></is></c>")
            }
            sd.append("</row>")
        }
        sd.append("</sheetData>")
        val sheet = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">$sd</worksheet>"""
        // 表名：Excel 不接受 : \ / ? * [ ]，且最长 31 字。
        val safe = title.ifBlank { "Sheet1" }.replace(Regex("[\\\\/:*?\\[\\]]"), " ").take(31)
        return zip(
            linkedMapOf(
                "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>""",
                "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""",
                "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
 xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="${xml(safe)}" sheetId="1" r:id="rId1"/></sheets></workbook>""",
                "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>""",
                "xl/worksheets/sheet1.xml" to sheet,
            )
        )
    }

    /** 0 → A，25 → Z，26 → AA。列号写错时 Excel 打不开，而生成时一声不响。 */
    fun colName(i: Int): String {
        var n = i
        val sb = StringBuilder()
        while (true) {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
            if (n < 0) break
        }
        return sb.toString()
    }

    /** 够用的 CSV：双引号包起来的格子里的逗号和换行都当普通字符；`""` = 一个引号。 */
    fun parseCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        val s = text.replace("\r\n", "\n").replace('\r', '\n')
        while (i < s.length) {
            val ch = s[i]
            when {
                quoted && ch == '"' && i + 1 < s.length && s[i + 1] == '"' -> {
                    cell.append('"'); i++
                }
                ch == '"' -> quoted = !quoted
                !quoted && ch == ',' -> { row.add(cell.toString()); cell.setLength(0) }
                !quoted && ch == '\n' -> {
                    row.add(cell.toString()); cell.setLength(0)
                    rows.add(row); row = ArrayList()
                }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString()); rows.add(row)
        }
        return rows
    }

    // ------------------------------------------------------------------
    // pptx
    // ------------------------------------------------------------------

    private const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"

    /**
     * 一页一张：`# 标题` 开新的一页，其余非空行是这一页的条目。
     *
     * ## ★ 骨架不是我们写的
     *
     * 母版 / 版式 / 主题这三样（[tpl]）来自一份 **PowerPoint 自己存出来的空稿**，
     * 原样打在 `assets/pptx/` 里。手写的那一版 python-pptx 认、PowerPoint 却判
     * 「文件已损坏（0x80070570）」—— 而那个错不会告诉我们哪个元素不合规。
     * 与其一点点试，不如把这三样交给做它的软件。
     *
     * 实测（2026-11-07）：PowerPoint 的骨架 + 这份代码生成的幻灯片 →
     * PowerPoint 正常打开，2 页，标题和条目都在。
     */
    fun pptx(tpl: PptxTemplate, title: String, content: String): ByteArray {
        val pages = ArrayList<Pair<String, List<String>>>()
        var curTitle: String? = null
        val cur = ArrayList<String>()
        fun flush() {
            if (curTitle != null || cur.isNotEmpty()) pages.add((curTitle ?: title) to cur.toList())
            cur.clear()
        }
        for (raw in content.replace("\r\n", "\n").lines()) {
            val line = raw.trim()
            if (line.startsWith("# ")) { flush(); curTitle = line.removePrefix("# ").trim() }
            else if (line.startsWith("## ")) { flush(); curTitle = line.removePrefix("## ").trim() }
            else if (line.isNotEmpty()) cur.add(line.removePrefix("- ").removePrefix("* ").trim())
        }
        flush()
        if (pages.isEmpty()) pages.add(title.ifBlank { "第 1 页" } to emptyList())

        val files = LinkedHashMap<String, ByteArray>()
        tpl.parts.forEach { (k, v) -> files[k] = v.toByteArray(Charsets.UTF_8) }
        files["_rels/.rels"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
</Relationships>""".toByteArray()

        val presRels = StringBuilder()
        val sldIds = StringBuilder()
        pages.forEachIndexed { idx, (t, lines) ->
            val n = idx + 1
            files["ppt/slides/slide$n.xml"] = slide(t, lines).toByteArray()
            files["ppt/slides/_rels/slide$n.xml.rels"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
</Relationships>""".toByteArray()
            presRels.append(
                "<Relationship Id=\"rId${100 + n}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide$n.xml\"/>"
            )
            sldIds.append("<p:sldId id=\"${255 + n}\" r:id=\"rId${100 + n}\"/>")
        }

        // 母版的 rId 在骨架里定死了；这里只补「主题」那一条（骨架里不一定有）。
        val themePath = tpl.themePart
        val masterPath = tpl.masterPart
        if (themePath != null) {
            presRels.append(
                "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"${themePath.removePrefix("ppt/")}\"/>"
            )
        }
        files["ppt/presentation.xml"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:p="$P" xmlns:a="$A" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>
<p:sldIdLst>$sldIds</p:sldIdLst>
<p:sldSz cx="12192000" cy="6858000"/><p:notesSz cx="6858000" cy="9144000"/>
</p:presentation>""".toByteArray()
        files["ppt/_rels/presentation.xml.rels"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="${masterPath?.removePrefix("ppt/") ?: "slideMasters/slideMaster1.xml"}"/>
$presRels</Relationships>""".toByteArray()

        files["[Content_Types].xml"] = contentTypes(files.keys).toByteArray()
        return zipBytes(files)
    }

    /** 按路径给每一份 XML 定内容类型。**漏一个，Office 就会说「需要修复」。** */
    fun contentTypes(names: Collection<String>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        sb.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        sb.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        for (n in names) {
            if (n.endsWith(".rels") || n == "[Content_Types].xml") continue
            val t = when {
                n == "ppt/presentation.xml" ->
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
                n.startsWith("ppt/slides/slide") && n.endsWith(".xml") ->
                    "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
                n.startsWith("ppt/slideMasters/") && n.endsWith(".xml") ->
                    "application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"
                n.startsWith("ppt/slideLayouts/") && n.endsWith(".xml") ->
                    "application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"
                n.startsWith("ppt/theme/") && n.endsWith(".xml") ->
                    "application/vnd.openxmlformats-officedocument.theme+xml"
                n == "ppt/presProps.xml" ->
                    "application/vnd.openxmlformats-officedocument.presentationml.presProps+xml"
                n == "ppt/viewProps.xml" ->
                    "application/vnd.openxmlformats-officedocument.presentationml.viewProps+xml"
                n == "ppt/tableStyles.xml" ->
                    "application/vnd.openxmlformats-officedocument.presentationml.tableStyles+xml"
                else -> null
            } ?: continue
            sb.append("<Override PartName=\"/").append(n).append("\" ContentType=\"").append(t).append("\"/>")
        }
        sb.append("</Types>")
        return sb.toString()
    }

    private fun slide(title: String, lines: List<String>): String {
        val body = if (lines.isEmpty()) "" else
            "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"body\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>" +
                "<p:spPr><a:xfrm><a:off x=\"838200\" y=\"2000000\"/><a:ext cx=\"10515600\" cy=\"4000000\"/></a:xfrm></p:spPr>" +
                "<p:txBody><a:bodyPr/><a:lstStyle/>" +
                lines.joinToString("") { l ->
                    "<a:p><a:r><a:rPr lang=\"zh-CN\" sz=\"2000\"/><a:t>${xml(l.take(300))}</a:t></a:r></a:p>"
                } + "</p:txBody></p:sp>"
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:p="$P" xmlns:a="$A" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<p:cSld><p:spTree>
<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>
<p:sp><p:nvSpPr><p:cNvPr id="2" name="title"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
<p:spPr><a:xfrm><a:off x="838200" y="600000"/><a:ext cx="10515600" cy="1200000"/></a:xfrm></p:spPr>
<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang="zh-CN" sz="3600" b="1"/><a:t>${xml(title.take(120))}</a:t></a:r></a:p></p:txBody></p:sp>
$body
</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"""
    }

    // ------------------------------------------------------------------
    // 公共
    // ------------------------------------------------------------------

    class Block(val text: String, val level: Int, val bullet: Boolean)

    /** Markdown-lite → 段落。空行是被丢掉的：它们只影响排版，不影响内容。 */
    fun blocksOf(content: String): List<Block> {
        val out = ArrayList<Block>()
        for (raw in content.replace("\r\n", "\n").lines()) {
            val line = raw.trimEnd()
            val t = line.trim()
            if (t.isEmpty()) continue
            when {
                t.startsWith("### ") -> out.add(Block(t.removePrefix("### ").trim(), 3, false))
                t.startsWith("## ") -> out.add(Block(t.removePrefix("## ").trim(), 2, false))
                t.startsWith("# ") -> out.add(Block(t.removePrefix("# ").trim(), 1, false))
                t.startsWith("- ") || t.startsWith("* ") ->
                    out.add(Block(t.substring(2).trim(), 0, true))
                else -> out.add(Block(t, 0, false))
            }
        }
        return out
    }

    /** XML 里这几个字符不转义 = 文件坏。控制字符更要命，它们会让 Office 直接拒收。 */
    fun xml(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch == '\'' -> sb.append("&apos;")
                // XML 1.0 不允许 0x00-0x08、0x0B、0x0C、0x0E-0x1F
                ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r' -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 按顺序写进 zip。**顺序不重要，缺文件才重要** —— 那份清单在上面的每个格式里。 */
    private fun zip(files: Map<String, String>): ByteArray =
        zipBytes(files.mapValues { it.value.toByteArray(Charsets.UTF_8) })

    private fun zipBytes(files: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, body) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(body)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}
