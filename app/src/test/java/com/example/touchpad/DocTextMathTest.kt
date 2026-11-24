package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DocTextMath] 的纯函数：格式怎么认、字怎么抠。
 *
 * ★ 这里错了**不报错**：抠出来的照样是一段能读的字，只是少了半页、或者混进了
 *   字体名/修订记录。所以每种格式都用一段**真长得像**的样例断言。
 */
class DocTextMathTest {

    // ---------------------------------------------------------------- 认格式

    @Test
    fun `按后缀和类型认格式，老 Office 认得出但读不了`() {
        assertEquals(DocTextMath.Kind.DOCX, DocTextMath.kindOf("报告.docx", ""))
        assertEquals(DocTextMath.Kind.XLSX, DocTextMath.kindOf("表.xlsx", ""))
        assertEquals(DocTextMath.Kind.PPTX, DocTextMath.kindOf("讲义.pptx", ""))
        assertEquals(DocTextMath.Kind.ODF, DocTextMath.kindOf("笔记.odt", ""))
        assertEquals(DocTextMath.Kind.PDF, DocTextMath.kindOf("论文.pdf", "application/octet-stream"))
        assertEquals(DocTextMath.Kind.IMAGE, DocTextMath.kindOf("照片.jpg", ""))
        assertEquals(DocTextMath.Kind.IMAGE, DocTextMath.kindOf("无名", "image/png"))
        assertEquals(DocTextMath.Kind.HTML, DocTextMath.kindOf("页.html", "text/html"))
        assertEquals(DocTextMath.Kind.RTF, DocTextMath.kindOf("旧.rtf", ""))
        assertEquals(DocTextMath.Kind.TEXT, DocTextMath.kindOf("a.txt", "text/plain"))
        assertEquals(DocTextMath.Kind.UNKNOWN, DocTextMath.kindOf("老.doc", ""))
        assertEquals(DocTextMath.Kind.UNKNOWN, DocTextMath.kindOf("包.zip", "application/zip"))
    }

    // ---------------------------------------------------------------- docx

    @Test
    fun `docx 段落换行，单元格用制表符分开`() {
        val xml = """
            <w:document><w:body>
              <w:p><w:r><w:t>第一段</w:t></w:r></w:p>
              <w:tbl><w:tr><w:tc><w:p><w:r><w:t>甲</w:t></w:r></w:p></w:tc>
              <w:tc><w:p><w:r><w:t>乙</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
            </w:body></w:document>
        """.trimIndent()
        val t = DocTextMath.docx(xml)
        assertTrue(t.contains("第一段"))
        assertTrue(t.contains("甲"))
        assertTrue(t.contains("乙"))
        // ★ 单元格之间**必须有制表符**：没有它，两张表会连成一串「甲甲乙乙」。
        assertTrue(t.contains('\t'))
    }

    @Test
    fun `docx 里修订删除的字、域代码、注音都不要`() {
        val xml = """
            <w:p><w:r><w:t>正文</w:t></w:r>
            <w:del><w:r><w:delText>删掉的</w:delText></w:r></w:del>
            <w:r><w:instrText>PAGE</w:instrText></w:r>
            <w:r><w:t>结尾</w:t></w:r></w:p>
        """.trimIndent()
        val t = DocTextMath.docx(xml)
        assertEquals("正文结尾", t)
    }

    @Test
    fun `docx 里的制表符和换行标记要变成真的制表符和换行`() {
        val xml = "<w:p><w:r><w:t>甲</w:t><w:tab/><w:t>乙</w:t><w:br/><w:t>丙</w:t></w:r></w:p>"
        assertEquals("甲\t乙\n丙", DocTextMath.docx(xml))
    }

    // ---------------------------------------------------------------- pptx / odf / html

    @Test
    fun `pptx 一段一行`() {
        val xml = "<p:sld><p:cSld><a:p><a:r><a:t>标题</a:t></a:r></a:p><a:p><a:r><a:t>正文</a:t></a:r></a:p></p:cSld></p:sld>"
        assertEquals("标题\n正文", DocTextMath.pptx(xml))
    }

    @Test
    fun `odf 的批注不算正文`() {
        val xml = """
            <office:body><text:p>第一段<office:annotation><text:p>这是批注</text:p></office:annotation></text:p>
            <text:p>第二段</text:p></office:body>
        """.trimIndent()
        val t = DocTextMath.odf(xml)
        assertTrue(t.contains("第一段"))
        assertTrue(t.contains("第二段"))
        assertTrue(!t.contains("批注"))
    }

    @Test
    fun `html 丢掉脚本和样式，段落换行`() {
        val html = """
            <html><head><style>p{color:red}</style><script>var x = "不要我";</script></head>
            <body><p>你好</p><p>世界</p></body></html>
        """.trimIndent()
        val t = DocTextMath.html(html)
        assertEquals("你好\n世界", t)
    }

    @Test
    fun `html 实体要还原，不认得的原样留着`() {
        assertEquals("A & B < C 中", DocTextMath.unescape("A &amp; B &lt; C &#x4E2D;"))
        assertEquals("&foo;", DocTextMath.unescape("&foo;"))
    }

    // ---------------------------------------------------------------- xlsx

    @Test
    fun `xlsx 共享字符串按顺序取，跳过注音`() {
        val xml = """
            <sst><si><t>姓名</t></si><si><t>张三</t><rPh><t>zhang</t></rPh></si></sst>
        """.trimIndent()
        assertEquals(listOf("姓名", "张三"), DocTextMath.sharedStrings(xml))
    }

    @Test
    fun `xlsx 一张表：共享字符串、内联字符串、数字、布尔都要对`() {
        val xml = """
            <worksheet><sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
            <row r="2"><c r="A2"><v>42</v></c><c r="B2" t="inlineStr"><is><t>备注</t></is></c></row>
            <row r="3"><c r="A3" t="b"><v>1</v></c></row>
            </sheetData></worksheet>
        """.trimIndent()
        val t = DocTextMath.sheet(xml, listOf("姓名", "张三"))
        assertEquals("姓名\t张三\n42\t备注\n是", t)
    }

    // ---------------------------------------------------------------- rtf

    @Test
    fun `rtf 控制词扔掉，段落换行，中文按 GBK 解`() {
        // \'d6\'d0 = 中，\'ce\'c4 = 文（GBK）
        val rtf = """{\rtf1\ansi\ansicpg936{\fonttbl{\f0 SimSun;}}{\*\generator Test;}
            \f0\fs24 \'d6\'d0\'ce\'c4\par hello}""".trimIndent()
        val t = DocTextMath.rtf(rtf)
        assertEquals("中文\nhello", t)
    }

    @Test
    fun `rtf 里的字体名不能混进正文`() {
        val rtf = """{\rtf1{\fonttbl{\f0 Times New Roman;}}正文\par}"""
        assertEquals("正文", DocTextMath.rtf(rtf))
    }

    // ---------------------------------------------------------------- zip 里的顺序

    @Test
    fun `幻灯片按数字排，不按字典序`() {
        val names = listOf("ppt/slides/slide10.xml", "ppt/slides/slide2.xml", "ppt/slides/slide1.xml")
        assertEquals(
            listOf("ppt/slides/slide1.xml", "ppt/slides/slide2.xml", "ppt/slides/slide10.xml"),
            DocTextMath.ordered(names)
        )
    }

    @Test
    fun `tidy 不许吃掉行内的制表符`() {
        assertEquals("甲\t乙\n丙", DocTextMath.tidy("  甲\t乙  \n\n\n丙  \n"))
    }
}
