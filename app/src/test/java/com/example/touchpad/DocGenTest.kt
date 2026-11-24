package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 文档产出。
 *
 * ★ 这一层错了**不报错**：zip 总能拼起来，坏的是里面那份 XML。
 *   症状是用户双击打开，Office 弹「需要修复」—— 而这句话不会出现在任何日志里。
 *   所以这里把每一份 XML 都拆出来、按真 XML 解析一遍，再核对必需的部件齐不齐。
 */
class DocGenTest {

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    private fun assertWellFormed(files: Map<String, String>) {
        val f = DocumentBuilderFactory.newInstance()
        for ((name, body) in files) {
            if (!name.endsWith(".xml") && !name.endsWith(".rels")) continue
            try {
                f.newDocumentBuilder().parse(ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
            } catch (e: Exception) {
                throw AssertionError("$name 不是合法 XML：${e.message}\n$body", e)
            }
        }
    }

    @Test
    fun `docx 的部件齐全且都是合法 XML`() {
        val files = unzip(DocGen.docx("实验报告", "# 第一节\n正文一段\n## 小节\n- 条目一"))
        for (need in listOf(
            "[Content_Types].xml", "_rels/.rels",
            "word/document.xml", "word/_rels/document.xml.rels", "word/styles.xml",
        )) assertNotNull("缺 $need", files[need])
        assertWellFormed(files)
        val doc = files.getValue("word/document.xml")
        assertTrue(doc.contains("实验报告"))
        assertTrue(doc.contains("Heading1"))
        assertTrue(doc.contains("条目一"))
    }

    @Test
    fun `docx 里的特殊字符被转义`() {
        val doc = unzip(DocGen.docx("A&B", "x < y && z > 1 \"引号\""))
            .getValue("word/document.xml")
        assertTrue(doc.contains("A&amp;B"))
        assertTrue(doc.contains("&lt;"))
        assertFalse("不能出现裸的 &", Regex("&(?!(amp|lt|gt|quot|apos|#)\\b?)").containsMatchIn(doc))
    }

    @Test
    fun `xlsx 的部件齐全、表名合法`() {
        val files = unzip(DocGen.xlsx("成绩:表/2026", "姓名,分数\n张三,90\n李四,85"))
        for (need in listOf(
            "[Content_Types].xml", "_rels/.rels",
            "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/worksheets/sheet1.xml",
        )) assertNotNull("缺 $need", files[need])
        assertWellFormed(files)
        val wb = files.getValue("xl/workbook.xml")
        assertFalse("表名里不能有冒号斜杠", wb.contains("成绩:表/2026"))
        val sheet = files.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("姓名"))
        assertTrue(sheet.contains("李四"))
        assertTrue(sheet.contains("r=\"A1\""))
        assertTrue(sheet.contains("r=\"B2\""))
    }

    @Test
    fun `列号 26 之后是 AA`() {
        assertEquals("A", DocGen.colName(0))
        assertEquals("Z", DocGen.colName(25))
        assertEquals("AA", DocGen.colName(26))
        assertEquals("AB", DocGen.colName(27))
        assertEquals("BA", DocGen.colName(52))
    }

    @Test
    fun `CSV 里带逗号和换行的格子要包在引号里`() {
        val rows = DocGen.parseCsv("a,\"b,c\",\"d\"\"e\"\n1,2,3")
        assertEquals(listOf("a", "b,c", "d\"e"), rows[0])
        assertEquals(listOf("1", "2", "3"), rows[1])
    }

    /** 真的那份骨架（和打进 APK 的是同一份文件）。 */
    private fun realTemplate(): PptxTemplate {
        val cand = listOf("src/main/assets/pptx", "../app/src/main/assets/pptx")
            .map { java.io.File(it) }.firstOrNull { it.isDirectory }
            ?: throw AssertionError("找不到 assets/pptx，工作目录是 " + java.io.File(".").absolutePath)
        val parts = LinkedHashMap<String, String>()
        cand.walkTopDown().filter { it.isFile }.forEach {
            parts[it.relativeTo(cand).path.replace('\\', '/')] = it.readText()
        }
        return PptxTemplate(parts)
    }

    @Test
    fun `pptx 一页一个标题，页数和 presentation 对得上`() {
        val files = unzip(DocGen.docx("t", "x")) // 先确认 zip 能读
        assertTrue(files.isNotEmpty())

        val p = unzip(DocGen.pptx(realTemplate(), "放映稿", "# 第一页\n- 甲\n- 乙\n# 第二页\n- 丙"))
        for (need in listOf(
            "[Content_Types].xml", "_rels/.rels", "ppt/presentation.xml",
            "ppt/_rels/presentation.xml.rels", "ppt/slides/slide1.xml",
            "ppt/slides/slide2.xml", "ppt/slideMasters/slideMaster1.xml",
            "ppt/slideLayouts/slideLayout1.xml", "ppt/theme/theme1.xml",
        )) assertNotNull("缺 $need", p[need])
        assertWellFormed(p)
        assertTrue(p.getValue("ppt/slides/slide1.xml").contains("第一页"))
        assertTrue(p.getValue("ppt/slides/slide1.xml").contains("乙"))
        assertTrue(p.getValue("ppt/slides/slide2.xml").contains("丙"))
        val pres = p.getValue("ppt/presentation.xml")
        assertTrue(pres.contains("rId101"))
        assertTrue(pres.contains("rId102"))
    }

    @Test
    fun `pptx 内容里没有标题时也要有一页`() {
        val p = unzip(DocGen.pptx(realTemplate(), "只有正文", "一行字\n又一行"))
        assertNotNull("至少一页，否则 PowerPoint 会说文件损坏", p["ppt/slides/slide1.xml"])
        assertTrue(p.getValue("ppt/slides/slide1.xml").contains("只有正文"))
    }

    @Test
    fun `格式别名认得出来`() {
        assertEquals(DocGen.Kind.DOCX, DocGen.kindOf("word"))
        assertEquals(DocGen.Kind.XLSX, DocGen.kindOf("Excel"))
        assertEquals(DocGen.Kind.PPTX, DocGen.kindOf(".pptx"))
        assertEquals(DocGen.Kind.CSV, DocGen.kindOf("csv"))
        assertEquals(null, DocGen.kindOf("zip"))
    }

    /**
     * 把三个样品落到磁盘上，供**真的用 Office 打开**验货（单元测试只能证明 XML 合法，
     * 证明不了 Office 肯收）。跑完去 `app/build/tmp/docgen/` 拿文件。
     */
    @Test
    fun `落一份样品给 Office 验`() {
        // ★ 目录名带个后缀：`docgen` 那个被 Office 的 COM 咬住过（文件句柄没释放），
        //   复用同一个路径会让这条用例报「另一个程序正在使用此文件」—— 和产物无关。
        val dir = java.io.File("build/tmp/docgen-samples").apply { mkdirs() }
        java.io.File(dir, "sample.docx").writeBytes(
            DocGen.docx("实验报告", "# 一、目的\n测量声速。\n## 器材\n- 示波器\n- 信号源")
        )
        java.io.File(dir, "sample.xlsx").writeBytes(
            DocGen.xlsx("成绩", "姓名,分数,备注\n张三,90,\"及格,良好\"\n李四,85,")
        )
        java.io.File(dir, "sample.pptx").writeBytes(
            DocGen.pptx(realTemplate(), "汇报", "# 第一页\n- 甲\n- 乙\n# 第二页\n- 丙")
        )
        println("样品在 ${dir.absolutePath}")
    }

    @Test
    fun `纯文本格式原样输出`() {
        val b = String(DocGen.build(DocGen.Kind.MD, "t", "# 标题"), Charsets.UTF_8)
        assertEquals("# 标题", b)
    }
}
