package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表格的纯算术。
 *
 * ★ 这些错**一个都不会抛异常**：算出来的是另一列的数、少算了空行、把「12人」当成 12,
 *   结果都长得完全正常。而她会把那个数当成事实告诉用户 —— 所以在测试里把每条认法钉死。
 */
class TableMathTest {

    private fun grid(text: String, sep: Char = ','): TableMath.Grid =
        TableMath.gridOf(TableMath.parseDelimited(text, sep))!!

    // ---- 切分 ----

    @Test
    fun `普通 csv`() {
        val g = grid("姓名,金额\n张三,100\n李四,200")
        assertEquals(listOf("姓名", "金额"), g.header)
        assertEquals(2, g.rowCount)
        assertEquals(listOf("张三", "100"), g.rows[0])
    }

    @Test
    fun `★ 引号里的逗号不算分隔符`() {
        val g = grid("名称,备注\nA,\"甲,乙\"\nB,普通")
        assertEquals(listOf("A", "甲,乙"), g.rows[0])
        assertEquals(listOf("B", "普通"), g.rows[1])
    }

    @Test
    fun `★ 引号里的两个引号是一个引号`() {
        val g = grid("a\n\"他说\"\"好\"\"\"")
        assertEquals(listOf("他说\"好\""), g.rows[0])
    }

    @Test
    fun `引号里的换行属于这一格`() {
        val g = grid("a,b\n\"第一行\n第二行\",x")
        assertEquals(1, g.rowCount)
        assertEquals("第一行\n第二行", g.rows[0][0])
    }

    @Test
    fun `★ 格子中间那个引号是普通字符`() {
        // 只有**开头**的引号才开始引用。不这样分的话，一节带引号的备注会让整张表错位。
        val g = grid("a,b\n5\"屏,x")
        assertEquals(listOf("5\"屏", "x"), g.rows[0])
    }

    @Test
    fun `CRLF 和空行`() {
        val g = grid("a,b\r\n1,2\r\n\r\n3,4\r\n")
        assertEquals(2, g.rowCount)
        assertEquals(listOf("3", "4"), g.rows[1])
    }

    @Test
    fun `★ 缺的格子补空串,后面的列不许左移`() {
        // 表头三列，第二行只写了两个格子：第二个格子必须在第 2 列，不能滑到第 3 列。
        val g = grid("省,市,区\n浙江,杭州")
        assertEquals(3, g.cols)
        assertEquals(listOf("浙江", "杭州", ""), g.rows[0])
    }

    @Test
    fun `表头里的空格子有个名字`() {
        val g = grid("姓名,,金额\n甲,乙,1")
        assertEquals(listOf("姓名", "第2列", "金额"), g.header)
    }

    @Test
    fun `一行数据都没有就是 null`() {
        assertNull(TableMath.gridOf(TableMath.parseDelimited("姓名,金额", ',')))
        assertNull(TableMath.gridOf(emptyList()))
    }

    // ---- 数字 ----

    @Test
    fun `认得出的数`() {
        assertEquals(1234.0, TableMath.normNum("1,234")!!, 1e-9)
        assertEquals(12.0, TableMath.normNum("¥12")!!, 1e-9)
        assertEquals(-3.0, TableMath.normNum("-3")!!, 1e-9)
        assertEquals(7.0, TableMath.normNum(" 7 ")!!, 1e-9)
        assertEquals(12.5, TableMath.normNum("12.5")!!, 1e-9)
    }

    @Test
    fun `★ 百分号只去掉符号,不除以一百`() {
        // 一列都是百分比时，求和得到的是「加起来几个百分点」。
        assertEquals(30.0, TableMath.normNum("30%")!!, 1e-9)
    }

    @Test
    fun `★ 认不出来是 null,不许当成 0`() {
        // 当成 0 的话，合计会静默少一截，而结果看起来完全正常。
        assertNull(TableMath.normNum(""))
        assertNull(TableMath.normNum("2026-11-07"))
        assertNull(TableMath.normNum("12人"))
        assertNull(TableMath.normNum("约30"))
        assertNull(TableMath.normNum("—"))
        assertNull(TableMath.normNum("¥"))
    }

    @Test
    fun `回执里的数`() {
        assertEquals("100", TableMath.fmt(100.0))
        assertEquals("12.35", TableMath.fmt(12.3456))
        assertEquals("0.5", TableMath.fmt(0.5))
        assertEquals("算不出来", TableMath.fmt(Double.NaN))
    }

    // ---- 找列 ----

    private val head = listOf("姓名", "金额", "税额金额", "备注")

    @Test
    fun `先认数字列号再从一数`() {
        assertEquals(0, TableMath.colIndex(head, "1"))
        assertEquals(3, TableMath.colIndex(head, "4"))
    }

    @Test
    fun `完全一样优先,不分大小写也算`() {
        assertEquals(1, TableMath.colIndex(head, "金额"))
        assertEquals(0, TableMath.colIndex(head, "姓名 "))
        assertEquals(0, TableMath.colIndex(listOf("Name"), "name"))
    }

    @Test
    fun `★ 命中多个就说不出来,别替模型挑第一个`() {
        // 「金额」同时命中「金额」和「税额金额」—— 猜错的代价是算错一整列，且看不出来。
        val h = listOf("税额金额", "金额合计")
        assertEquals(-1, TableMath.colIndex(h, "金额"))
        // 但只有一个包含它时可以用
        assertEquals(0, TableMath.colIndex(listOf("员工姓名", "部门"), "姓名"))
    }

    @Test
    fun `找不到时的提示列出表头`() {
        val s = TableMath.colHint(head, "价格")
        assertTrue(s.contains("价格"))
        assertTrue(s.contains("姓名"))
        assertTrue(s.contains("金额"))
    }

    // ---- 统计 ----

    private val sales = grid(
        """
        姓名,部门,金额
        张三,销售,100
        李四,销售,"1,200"
        王五,技术,300
        赵六,销售,没填
        """.trimIndent()
    )

    private fun agg(col: String, op: String, where: Pair<String, String>? = null): TableMath.Agg {
        val idx = TableMath.colIndex(sales.header, col)
        val f = where?.let { TableMath.RowFilter(TableMath.colIndex(sales.header, it.first), it.second) }
        return TableMath.agg(sales, idx, op, f)
    }

    @Test
    fun `求和跳过非数字,并且报出有几个是数字`() {
        val r = agg("金额", "sum")
        assertEquals(1600.0, r.value!!, 1e-9)
        assertEquals(4, r.count)
        assertEquals(3, r.numbers)
    }

    @Test
    fun `平均只按数字算`() {
        assertEquals(1600.0 / 3, agg("金额", "avg").value!!, 1e-9)
    }

    @Test
    fun `最大最小`() {
        assertEquals(1200.0, agg("金额", "max").value!!, 1e-9)
        assertEquals(100.0, agg("金额", "min").value!!, 1e-9)
    }

    @Test
    fun `数行数数的是行,不是数字`() {
        val r = agg("金额", "count")
        assertEquals(4, r.count)
        assertEquals(3, r.numbers)
    }

    @Test
    fun `不同的值按出现顺序`() {
        val r = agg("部门", "distinct")
        assertEquals(listOf("销售", "技术"), r.values)
        assertEquals(4, r.count)
    }

    @Test
    fun `distinct 超过上限时说明没列全`() {
        val r = TableMath.agg(sales, 1, "distinct", null, limit = 1)
        assertEquals(listOf("销售"), r.values)
        assertTrue("没列全必须说出来，不能假装只有这几个", r.more)
    }

    @Test
    fun `筛选之后只算那几行`() {
        val r = agg("金额", "sum", "部门" to "销售")
        assertEquals(3, r.count)
        assertEquals(1300.0, r.value!!, 1e-9)
        // 筛选也是不分大小写
        assertEquals(1300.0, agg("金额", "sum", "部门" to "销售").value!!, 1e-9)
    }

    @Test
    fun `筛出零行时不许报成零元`() {
        val r = agg("金额", "sum", "部门" to "财务")
        assertEquals(0, r.count)
        assertNull("一行都没匹配上，合计是「没有」不是 0", r.value)
    }

    // ---- xlsx 的格子坐标 ----

    @Test
    fun `列字母换算`() {
        assertEquals(0, XlsxMath.colOf("A1"))
        assertEquals(1, XlsxMath.colOf("B2"))
        assertEquals(25, XlsxMath.colOf("Z9"))
        assertEquals(26, XlsxMath.colOf("AA1"))
        assertEquals(-1, XlsxMath.colOf(""))
        assertEquals(-1, XlsxMath.colOf("12"))
    }

    @Test
    fun `★ 中间空掉的格子不读,后面的列也不许左移`() {
        // xlsx 里空的单元格**整段省掉**是很常见的写法。数「第几个 c」会让 C 列读成 B 列，
        // 于是「金额」那一列读到的是「备注」—— 求和结果完全正常，只是算的是另一列。
        val xml = """
            <sheetData>
              <row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1" t="s"><v>1</v></c></row>
              <row r="2"><c r="A2"><v>1</v></c><c r="B2"/><c r="C2"><v>99</v></c></row>
            </sheetData>
        """.trimIndent()
        val rows = XlsxMath.sheetGrid(xml, listOf("姓名", "金额"))
        assertEquals(listOf("姓名", "", "金额"), rows[0])
        assertEquals(listOf("1", "", "99"), rows[1])
    }

    @Test
    fun `xlsx 的共享字符串、内联字符串、布尔`() {
        val xml = """
            <row r="1">
              <c r="A1" t="s"><v>1</v></c>
              <c r="B1" t="inlineStr"><is><t>直接写的</t></is></c>
              <c r="C1" t="b"><v>1</v></c>
            </row>
        """.trimIndent()
        val rows = XlsxMath.sheetGrid(xml, listOf("没用", "共享的"))
        assertEquals(listOf("共享的", "直接写的", "是"), rows[0])
    }

    @Test
    fun `xlsx 里的转义`() {
        val xml = """<row r="1"><c r="A1" t="inlineStr"><is><t>甲 &amp; 乙</t></is></c></row>"""
        assertEquals(listOf("甲 & 乙"), XlsxMath.sheetGrid(xml, emptyList())[0])
    }
}
