package com.example.touchpad

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 「代码」这一档的地基测试 —— 纯 JVM，不碰 org.json / Android。
 *
 * 为什么值得钉：这一层错了**不崩**。路径漏一道检查 = 它在沙盒里写到别处；
 * 输出不截断 = 回执撑爆上下文；二进制当文本读 = 一屏乱码，它照着乱码改代码。
 */
class WorkCodeMathTest {

    @Test
    fun `普通相对路径原样通过`() {
        assertEquals("index.html", WorkCodeMath.rel("index.html"))
        assertEquals("src/main.py", WorkCodeMath.rel("src/main.py"))
        assertEquals("a/b.txt", WorkCodeMath.rel("./a/b.txt"))
    }

    @Test
    fun `空路径就是工作区本身`() {
        assertEquals(".", WorkCodeMath.rel(""))
        assertEquals(".", WorkCodeMath.rel("   "))
        assertEquals(".", WorkCodeMath.rel("/root/work"))
        assertEquals(".", WorkCodeMath.rel("/root/work/"))
    }

    @Test
    fun `写成绝对路径也认,只要它在工作区里`() {
        assertEquals("a/b.txt", WorkCodeMath.rel("/root/work/a/b.txt"))
    }

    @Test
    fun `反斜杠当斜杠用`() {
        assertEquals("a/b/c.txt", WorkCodeMath.rel("a\\b\\c.txt"))
    }

    @Test
    fun `越界的一律不认 —— 这条错了就是在写手机上的别的东西`() {
        assertNull(WorkCodeMath.rel("../secret"))
        assertNull(WorkCodeMath.rel("a/../../x"))
        assertNull(WorkCodeMath.rel("/etc/passwd"))
        assertNull(WorkCodeMath.rel("/root/work/../other"))
    }

    @Test
    fun `中间夹的点点点会被消掉`() {
        assertEquals("b", WorkCodeMath.rel("a/../b"))
        assertEquals("a/b", WorkCodeMath.rel("a/./b"))
    }

    @Test
    fun `短输出原样,长输出留头留尾`() {
        assertEquals("hi", WorkCodeMath.clip("hi"))
        val long = "头".repeat(1000) + "中".repeat(20000) + "尾".repeat(1000)
        val c = WorkCodeMath.clip(long, 1000)
        assertTrue(c.length < long.length)
        // 头是第一条报错、尾是最后的 error 行 —— 中间那段可以不要。
        assertTrue(c.startsWith("头"))
        assertTrue(c.endsWith("尾"))
        assertTrue(c.contains("省掉"))
    }

    @Test
    fun `清单里目录带斜杠,文件带大小`() {
        assertTrue(WorkCodeMath.entryLine("src", isDir = true, bytes = 0).trim().endsWith("src/"))
        assertTrue(WorkCodeMath.entryLine("a.txt", isDir = false, bytes = 2048).contains("2.0 KB"))
    }

    // ── 文件操作（真建一个临时工作区）──

    private lateinit var root: File
    private lateinit var tmp: File
    private lateinit var files: WorkFiles

    @Before
    fun setUp() {
        // ★ 工作区放在一个**测试独占的父目录**里。
        //   原先直接挂在系统 temp 下,越界用例要断言「temp 根没有 x.txt」——
        //   那是个公用目录,盘上只要躺着一个无关的 x.txt 就假报失败。
        root = File(System.getProperty("java.io.tmpdir"), "wc_root_" + System.nanoTime())
        tmp = File(root, "work")
        files = WorkFiles(tmp)
        files.ensure()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `写了能读回来`() {
        assertTrue(files.write("a/b.txt", "你好").startsWith("写好了"))
        assertTrue(files.read("a/b.txt", 0).contains("你好"))
    }

    @Test
    fun `同名再写要明说是覆盖 —— 不说的话它以为在写新文件`() {
        files.write("a.txt", "1")
        assertTrue(files.write("a.txt", "2").startsWith("覆盖了"))
        assertTrue(files.read("a.txt", 0).contains("2"))
    }

    @Test
    fun `不写就说不写,不假装成功`() {
        assertTrue(files.write("../x.txt", "坏").startsWith("不在工作区"))
        assertTrue(files.write("../x.txt", "坏").length > 0)
        assertTrue(!File(tmp.parentFile, "x.txt").exists())
    }

    @Test
    fun `列目录：空的说空,有的列出来`() {
        assertTrue(files.list(".").contains("空"))
        files.write("x.py", "print(1)")
        val l = files.list(".")
        assertTrue(l.contains("x.py"))
        assertTrue(l.contains("1 项"))
    }

    @Test
    fun `读不存在的文件要明说没有`() {
        assertTrue(files.read("nope.txt", 0).startsWith("没有这个文件"))
    }

    @Test
    fun `二进制不读正文 —— 一屏乱码会把它带偏`() {
        File(tmp, "b.bin").writeBytes(byteArrayOf(0, 1, 2, 3, 0, 5))
        assertTrue(files.read("b.bin", 0).contains("不像是文本"))
    }

    // ── patch：改一小段，而不是整份重写 ──

    @Test
    fun `patch 只改命中的那一段`() {
        files.write("a.py", "def f():\n    return 1\n\ndef g():\n    return 2\n")
        val r = files.patch("a.py", "return 1", "return 42", all = false)
        assertTrue(r.startsWith("改"))
        val after = files.read("a.py", 0)
        assertTrue(after.contains("return 42"))
        assertTrue(after.contains("return 2"))
    }

    @Test
    fun `patch 命不中就说没改,而不是假装改好了`() {
        files.write("a.py", "print(1)\n")
        val r = files.patch("a.py", "print(2)", "print(3)", all = false)
        assertTrue(r.contains("没有这段原文"))
        assertTrue(files.read("a.py", 0).contains("print(1)"))
    }

    @Test
    fun `patch 命中多处又没让全换就一处都不动`() {
        files.write("a.py", "x = 1\nx = 1\n")
        val r = files.patch("a.py", "x = 1", "x = 2", all = false)
        assertTrue(r.contains("命中"))
        assertTrue(r.contains("2 处"))
        assertEquals("x = 1\nx = 1\n", File(tmp, "a.py").readText())
    }

    @Test
    fun `patch 明确全换才全换`() {
        files.write("a.py", "x = 1\nx = 1\n")
        assertTrue(files.patch("a.py", "x = 1", "x = 2", all = true).startsWith("改"))
        assertEquals("x = 2\nx = 2\n", File(tmp, "a.py").readText())
    }

    @Test
    fun `patch 不碰工作区外的路径`() {
        assertTrue(files.patch("../x.txt", "a", "b", all = false).startsWith("不在工作区"))
        assertTrue(files.patch("nope.txt", "a", "b", all = false).startsWith("没有这个文件"))
    }

    // ── grep：先定位，再改 ──

    @Test
    fun `grep 回 路径 行号 那一行`() {
        files.write("src/a.py", "import os\nvalue = 514\n")
        val r = files.grep("514", ".", 0)
        assertTrue(r.contains("src/a.py:2:"))
        assertTrue(r.contains("value = 514"))
    }

    @Test
    fun `grep 正则写坏了退回按字面找,并且明说`() {
        files.write("a.txt", "a(b 在这里\n")
        val r = files.grep("a(b", ".", 0)
        assertTrue(r.contains("按字面搜"))
        assertTrue(r.contains("a.txt:1:"))
    }

    @Test
    fun `grep 搜不到就明说搜不到`() {
        files.write("a.txt", "hello\n")
        assertTrue(files.grep("不存在的东西", ".", 0).contains("没搜到"))
    }

    @Test
    fun `grep 最多回多少条`() {
        files.write("a.txt", (1..50).joinToString("\n") { "hit $it" } + "\n")
        val r = files.grep("hit", ".", 3)
        // 三条命中 + 一行「到上上限就停了」
        assertTrue(r.contains("到上限 3 条就停了"))
    }

    @Test
    fun `路径规范化拿到的是记账用的名字`() {
        assertEquals("a.txt", WorkFiles.nameOf("/root/work/a.txt"))
        assertNull(WorkFiles.nameOf("/root/work"))
        assertNull(WorkFiles.nameOf("../x"))
    }
}
