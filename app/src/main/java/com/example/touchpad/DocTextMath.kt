package com.example.touchpad

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * 各种文档格式**怎么把字抠出来** —— 纯字符串进、纯字符串出，不碰文件、不碰 Android。
 *
 * ★ 这一层错了**不报错**：抠出来照样是一段能读的字，只是少了半页、或者把格式编号
 *   当成正文一起记进了共用记忆。所以每种格式都有一条拿具体样例断言的单测。
 *
 * ★ 只做「把字拿出来」这件事：不排版、不保留样式、不认公式图片。
 *   目的只有一个 —— 让他丢进来的资料能被检索到、能被 agent 读到。
 */
internal object DocTextMath {

    enum class Kind { TEXT, DOCX, XLSX, PPTX, ODF, EPUB, RTF, HTML, PDF, IMAGE, UNKNOWN }

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")

    /** Office 的老二进制格式：能认出来，但读不了 —— 要给他一句人话而不是一屏乱码。 */
    private val OLD_OFFICE_EXT = setOf("doc", "xls", "ppt")

    fun kindOf(name: String, mime: String): Kind {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val m = mime.lowercase(Locale.ROOT)
        return when {
            ext == "pdf" || m == "application/pdf" -> Kind.PDF
            ext in IMAGE_EXT || m.startsWith("image/") -> Kind.IMAGE
            ext == "docx" || ext == "docm" -> Kind.DOCX
            ext == "xlsx" || ext == "xlsm" -> Kind.XLSX
            ext == "pptx" || ext == "pptm" -> Kind.PPTX
            ext in setOf("odt", "ods", "odp", "ott", "ots", "otp") -> Kind.ODF
            ext == "epub" -> Kind.EPUB
            ext == "rtf" -> Kind.RTF
            ext == "html" || ext == "htm" || ext == "xhtml" -> Kind.HTML
            ext in OLD_OFFICE_EXT -> Kind.UNKNOWN
            WorkAttachMath.isText(name, mime) -> Kind.TEXT
            else -> Kind.UNKNOWN
        }
    }

    // ---------------------------------------------------------------- 通用扫描

    /**
     * 把一个 XML 里的正文拿出来。
     *
     * @param breakOn 标签名 → 遇到它（**闭合或自闭合**）时补的字符。开标签不补，
     *                否则每一段前面都会多一行空。
     * @param skip    这些标签里的字**不要**：修订记录里的删除文字、拼音注音、
     *                域代码、`mc:Fallback`（它是同一段字的副本）。
     */
    fun xmlToText(
        xml: String,
        breakOn: Map<String, String> = emptyMap(),
        skip: Set<String> = emptySet(),
        /**
         * 在某个标签**里面**时，不要让这些标签的断行生效。
         *
         * ★ docx 的每个表格单元格里都套着一个 `w:p`。不处理的话，单元格先被换行、
         *   再补制表符 —— 而制表符单独占一行在收尾时会被当空行丢掉，整张表就被
         *   摊成一列一列的长条，**而且不报错**。
         */
        suppressBreakIn: Map<String, Set<String>> = emptyMap(),
    ): String {
        val sb = StringBuilder(xml.length / 2)
        var i = 0
        val n = xml.length
        var skipDepth = 0
        val open = HashMap<String, Int>()
        while (i < n) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) {
                if (skipDepth == 0) appendText(sb, xml.substring(i))
                break
            }
            if (skipDepth == 0 && lt > i) appendText(sb, xml.substring(i, lt))
            if (xml.startsWith("<!--", lt)) {
                val e = xml.indexOf("-->", lt)
                i = if (e < 0) n else e + 3
                continue
            }
            if (xml.startsWith("<![CDATA[", lt)) {
                val e = xml.indexOf("]]>", lt)
                if (skipDepth == 0) sb.append(xml, lt + 9, if (e < 0) n else e)
                i = if (e < 0) n else e + 3
                continue
            }
            val gt = xml.indexOf('>', lt)
            if (gt < 0) break
            val raw = xml.substring(lt + 1, gt)
            i = gt + 1
            if (raw.isEmpty() || raw[0] == '?' || raw[0] == '!') continue
            val closing = raw[0] == '/'
            var body = if (closing) raw.substring(1) else raw
            val selfClosing = body.endsWith("/")
            if (selfClosing) body = body.dropLast(1)
            val name = body.takeWhile { !it.isWhitespace() }
            if (name.isEmpty()) continue

            if (name in skip) {
                if (closing) { if (skipDepth > 0) skipDepth-- }
                else if (!selfClosing) skipDepth++
                continue
            }
            if (skipDepth > 0) continue
            if (name in suppressBreakIn) {
                val now = (open[name] ?: 0) + if (closing) -1 else if (selfClosing) 0 else 1
                open[name] = if (now > 0) now else 0
            }
            if (closing || selfClosing) {
                val muted = suppressBreakIn.any { (outer, inner) ->
                    name in inner && (open[outer] ?: 0) > 0
                }
                if (!muted) breakOn[name]?.let { sb.append(it) }
            }
        }
        return sb.toString()
    }

    /**
     * 标签之间的空白。
     *
     * ★ **带换行的空白一律丢掉**：那是 XML/HTML 的排版缩进，不是正文。
     *   留着的话，docx 里同一个段落的两截字会被源码缩进掰成两行 —— 而人看不出来，
     *   只会觉得「它读到的段落怪怪的」。
     * ★ 单个空格**留着**：`<b>甲</b> <b>乙</b>` 中间那个空格是字与字的分界。
     */
    private fun appendText(sb: StringBuilder, raw: String) {
        if (raw.isBlank() && raw.indexOf('\n') >= 0) return
        sb.append(unescape(raw))
    }

    /** XML / HTML 实体。**不认得的原样留着** —— 宁可看见 `&foo;` 也不要把它吃掉。 */
    fun unescape(s: String): String {
        if (s.indexOf('&') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i)
                if (semi in (i + 2)..(i + 12)) {
                    val ent = s.substring(i + 1, semi)
                    val rep = when {
                        ent.startsWith("#x") || ent.startsWith("#X") ->
                            ent.substring(2).toIntOrNull(16)?.let(::codePoint)
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let(::codePoint)
                        ent == "amp" -> "&"
                        ent == "lt" -> "<"
                        ent == "gt" -> ">"
                        ent == "quot" -> "\""
                        ent == "apos" -> "'"
                        ent == "nbsp" -> " "
                        else -> null
                    }
                    if (rep != null) { sb.append(rep); i = semi + 1; continue }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun codePoint(cp: Int): String? =
        if (cp in 0..0x10FFFF && cp !in 0xD800..0xDFFF) runCatching { String(Character.toChars(cp)) }.getOrNull()
        else null

    /**
     * 收尾：统一换行、去掉行尾空白、连续空行压成一行。
     *
     * ★ **不压行内的制表符** —— 表格里那一列就是靠它分开的。
     */
    fun tidy(t: String): String {
        val lines = t.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            // ★ 只去**空格**，不动制表符：表格里的列就是靠制表符分开的，
            //   行尾那个制表符表示「右边还有一格」—— 删掉就把格子弄丢了。
            .map { it.trimStart(' ').trimEnd(' ') }
        val out = StringBuilder()
        for (l in lines) {
            // ★ 空行**全丢掉**：XML/HTML 里元素之间的换行和缩进都会变成「空行」，
            //   留着就是一段资料里每隔一行一个空行 —— 切块时白占字数，念出来也是停顿。
            if (l.isBlank()) continue
            out.append(l).append('\n')
        }
        return out.toString().trim()
    }

    // ---------------------------------------------------------------- 各格式

    /** docx：段落换行、单元格制表符。 */
    fun docx(xml: String): String = tidy(
        xmlToText(
            xml,
            breakOn = mapOf(
                "w:p" to "\n", "w:tr" to "\n", "w:tc" to "\t",
                "w:tab" to "\t", "w:br" to "\n", "w:cr" to "\n",
            ),
            skip = setOf("w:instrText", "w:delText", "mc:Fallback", "w:rPh"),
            // ★ 单元格里的段落不换行，见 [xmlToText] 的 suppressBreakIn。
            suppressBreakIn = mapOf("w:tc" to setOf("w:p")),
        )
    )

    /** pptx：一段一行。 */
    fun pptx(xml: String): String = tidy(
        xmlToText(xml, breakOn = mapOf("a:p" to "\n"), skip = setOf("a:fld"))
    )

    /** ODF（odt/ods/odp）：正文都在 `content.xml` 里。 */
    fun odf(xml: String): String = tidy(
        xmlToText(
            xml,
            breakOn = mapOf(
                "text:p" to "\n", "text:h" to "\n",
                "table:table-cell" to "\t", "table:table-row" to "\n",
            ),
            skip = setOf("office:annotation", "text:note", "text:tracked-changes"),
        )
    )

    /** HTML：先把脚本和样式整块丢掉，再抠字。 */
    fun html(xml: String): String {
        val noScript = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>").replace(xml, " ")
        return tidy(
            xmlToText(
                noScript,
                breakOn = mapOf(
                    "p" to "\n", "div" to "\n", "br" to "\n", "li" to "\n",
                    "tr" to "\n", "td" to "\t", "th" to "\t",
                    "h1" to "\n", "h2" to "\n", "h3" to "\n",
                    "h4" to "\n", "h5" to "\n", "h6" to "\n",
                ),
            )
        )
    }

    /** xlsx 的 `sharedStrings.xml`：所有字符串按顺序排好，单元格里只存下标。 */
    fun sharedStrings(xml: String): List<String> {
        val out = ArrayList<String>()
        Regex("<si\\b[^>]*>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { m ->
            out.add(xmlToText(m.groupValues[1], skip = setOf("rPh", "phoneticPr")).trim())
        }
        return out
    }

    /**
     * xlsx 的一张表。
     *
     * ★ 日期在 xlsx 里是**一个数字**（序列号），它的含义放在样式里。这里不猜 ——
     *   猜错比不猜更糟（2026-05-01 会变成 46143）。数字原样留着，行里其它字照常。
     */
    fun sheet(xml: String, shared: List<String>): String {
        val sb = StringBuilder()
        val rows = Regex("<row\\b[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
        val cell = Regex("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL)
        val value = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
        for (r in rows.findAll(xml)) {
            var first = true
            for (c in cell.findAll(r.groupValues[1])) {
                val attrs = c.groupValues[1]
                val body = c.groupValues.getOrElse(2) { "" }
                val t = Regex("\\bt=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1) ?: "n"
                val v = when (t) {
                    "s" -> value.find(body)?.groupValues?.get(1)?.trim()?.toIntOrNull()
                        ?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> xmlToText(body).trim()
                    "b" -> if (value.find(body)?.groupValues?.get(1)?.trim() == "1") "是" else "否"
                    // str / e / n：公式的字符串结果、错误码、数字，都在 <v> 里
                    else -> value.find(body)?.groupValues?.get(1)?.trim().orEmpty()
                }
                if (!first) sb.append('\t')
                sb.append(unescape(v))
                first = false
            }
            sb.append('\n')
        }
        return tidy(sb.toString())
    }

    /**
     * 这些组里装的是**元数据**（字体表、颜色表、图片、页眉页脚……），字不是正文。
     *
     * ★ 不丢的话，一份 RTF 的书名后面会跟一串 `SimSun;Times New Roman;`，
     *   而且那串字**会被检索到** —— 查「字体」时命中任何一份文档。
     */
    private val RTF_NON_CONTENT = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object",
        "header", "footer", "footnote", "annotation", "field", "fldinst",
        "listtable", "listoverridetable", "rsidtbl", "generator", "xmlnstbl",
        "themedata", "colorschememapping", "latentstyles", "datastore",
        "filetbl", "revtbl", "pnseclvl", "mmathPr", "fldrslt",
    )

    /** RTF：控制词当命令扔掉，正文留下来。中文的 `\'hh` 按 GBK 解。 */
    fun rtf(raw: String): String {
        val body = stripNonContent(raw)
        val cp936 = body.contains("\\ansicpg936")
        val sb = StringBuilder(body.length / 2)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                val n = body[i + 1]
                if (n == '\'') {
                    val bytes = ByteArrayOutputStream()
                    while (i + 3 < body.length && body[i] == '\\' && body[i + 1] == '\'') {
                        val hex = body.substring(i + 2, i + 4)
                        hex.toIntOrNull(16)?.let { bytes.write(it) }
                        i += 4
                    }
                    sb.append(String(bytes.toByteArray(), if (cp936) charset("GBK") else Charsets.ISO_8859_1))
                    continue
                }
                if (n == '\\' || n == '{' || n == '}') { sb.append(n); i += 2; continue }
                if (n == '\n' || n == '\r') { sb.append('\n'); i += 2; continue }
                if (n.isLetter()) {
                    var j = i + 1
                    val word = StringBuilder()
                    while (j < body.length && body[j].isLetter()) { word.append(body[j]); j++ }
                    if (j < body.length && body[j] == '-') j++
                    val numStart = j
                    while (j < body.length && body[j].isDigit()) j++
                    val num = if (j > numStart) body.substring(numStart, j).toIntOrNull() else null
                    if (j < body.length && body[j] == ' ') j++
                    i = j
                    when (word.toString()) {
                        "par", "line", "row" -> sb.append('\n')
                        "tab", "cell" -> sb.append('\t')
                        "u" -> num?.let { sb.append(((it + 65536) % 65536).toChar()) }
                        else -> Unit  // 字体表、颜色表、页边距…… 全是控制词，扔掉
                    }
                    continue
                }
                i += 2
                continue
            }
            if (c != '{' && c != '}') sb.append(c)
            i++
        }
        return tidy(sb.toString())
    }

    /**
     * 扔掉 RTF 里「不是正文」的组：`{\*\...}` 和 [RTF_NON_CONTENT] 里那些。
     *
     * 做法是数括号：从一个组的 `{` 开始，配对到它的 `}` 整段跳过 ——
     * 这些组可以套好几层（字体表里每个字体又是自己一组）。
     */
    private fun stripNonContent(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '{') {
                // 组是 `{\word ...}` 的形状 —— 先越过那个反斜杠才读得到控制词。
                // （第一版漏了这一步，字体表就原样留在了正文里。）
                var j = i + 1
                var star = false
                if (j < s.length && s[j] == '\\') {
                    j++
                    if (j < s.length && s[j] == '*') {
                        star = true
                        j++
                    }
                }
                var k = j
                while (k < s.length && s[k].isLetter()) k++
                val word = s.substring(j, k)
                if (star || word in RTF_NON_CONTENT) {
                    var depth = 1
                    var p = i + 1
                    while (p < s.length && depth > 0) {
                        when (s[p]) {
                            '{' -> depth++
                            '}' -> depth--
                        }
                        p++
                    }
                    i = p
                    continue
                }
            }
            out.append(s[i])
            i++
        }
        return out.toString()
    }

    /**
     * zip 里的条目按名字里的数字排：`slide2` 要排在 `slide10` 前面。
     *
     * ★ 字典序会把它排反（`"slide10" < "slide2"`），症状是整本 PPT 的页码乱掉，
     *   而内容一条不少 —— 这种错最难看出来。
     */
    fun ordered(names: List<String>): List<String> = names.sortedBy { name ->
        Regex("(\\d+)").findAll(name).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
