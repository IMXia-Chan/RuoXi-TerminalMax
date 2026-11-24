package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 交货时的文件名规则。
 *
 * ★ 这一类错了**不报错**：文件照样落进「下载/ConMarn/」，只是名字不对 ——
 *   用户看到的是一个叫「报告.docx.txt」的东西，双击打开是一堆 XML。
 *   MediaStore 和文件系统都不会替我们纠正这个，所以只能在这儿钉住。
 *
 * [DocFile.save] 本身要真机（Context / MediaStore），纯 JVM 测不了；那一半靠真机开箱。
 */
class DocFileTest {

    @Test
    fun `普通名字补上扩展名`() {
        assertEquals("实验报告.docx", DocFileMath.cleanName("实验报告", "docx"))
        assertEquals("成绩.xlsx", DocFileMath.cleanName("成绩", "xlsx"))
    }

    @Test
    fun `★ 已经带对的扩展名不许重复一次`() {
        assertEquals("实验报告.docx", DocFileMath.cleanName("实验报告.docx", "docx"))
        assertEquals("实验报告.docx", DocFileMath.cleanName("实验报告.DOCX", "docx"))
    }

    @Test
    fun `★ 带错了扩展名就换掉 —— 否则打开是乱码`() {
        assertEquals("实验报告.docx", DocFileMath.cleanName("实验报告.txt", "docx"))
        assertEquals("成绩.xlsx", DocFileMath.cleanName("成绩.csv", "xlsx"))
    }

    @Test
    fun `★ 版本号不是扩展名，不许被吃掉`() {
        // 「报告 v1.2」按「短后缀当扩展名」去剥会变成「报告 v1.docx」—— 版本号没了，
        // 而且没有任何提示。
        assertEquals("报告 v1.2.docx", DocFileMath.cleanName("报告 v1.2", "docx"))
        assertEquals("第1.5章.xlsx", DocFileMath.cleanName("第1.5章", "xlsx"))
    }

    @Test
    fun `路径分隔符和非法字符换成空格`() {
        assertEquals("报告 初稿.docx", DocFileMath.cleanName("报告/初稿", "docx"))
        assertEquals("a b c d.docx", DocFileMath.cleanName("a:b*c?d", "docx"))
        assertEquals("a b.docx", DocFileMath.cleanName("a\n\tb", "docx"))
    }

    @Test
    fun `空名字给个通用的，不落出一个只有扩展名的文件`() {
        assertEquals("文档.docx", DocFileMath.cleanName("", "docx"))
        assertEquals("文档.docx", DocFileMath.cleanName("   ", "docx"))
        // 洗成空的情况：一个名字里全是非法字符
        assertEquals("文档.docx", DocFileMath.cleanName("/:*?", "docx"))
        assertEquals("文档.docx", DocFileMath.cleanName("...", "docx"))
    }

    @Test
    fun `太长的截短，扩展名要留住`() {
        val n = DocFileMath.cleanName("实".repeat(200), "docx")
        assertTrue(n.endsWith(".docx"))
        assertEquals("实".repeat(60) + ".docx", n)
    }

    @Test
    fun `大小翻成人话`() {
        assertEquals("512 字节", DocFileMath.sizeText(512))
        assertEquals("1.0 KB", DocFileMath.sizeText(1024))
        assertEquals("1.5 MB", DocFileMath.sizeText(1024L * 1024 * 3 / 2))
        assertFalse(DocFileMath.sizeText(2048).contains("MB"))
    }
}
