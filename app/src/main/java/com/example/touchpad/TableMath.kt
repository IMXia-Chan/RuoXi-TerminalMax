package com.example.touchpad

/**
 * 表格的**纯算术**：切行切列、认数字、按列名找列、求和/平均/筛选。
 *
 * ★ 为什么这一层要单独拿出来：在这之前她读 xlsx 只能把单元格摊成文字再「复述」，
 *   而一个 4B 模型对着两百行数字求和，**每一次都会给出一个很像的错数**，且不报错。
 *   所以「算」这件事必须由代码做，模型只负责说「按哪一列、做哪种运算」。
 *
 * ★ 这里的每个函数错了都不会抛异常：数错一位、列错一列，结果照样长得像回事。
 *   所以全在 `TableMathTest` 里钉住。
 */
internal object TableMath {

    /** 最多读这么多行。再多就不是「帮我算一下」，是「把数据库倒进提示词」。 */
    const val MAX_ROWS = 5000

    /** 最多这么多列。校园里的表格基本不会超过。 */
    const val MAX_COLS = 60

    /** 单个格子最多留这么多个字（超长的一般是备注/地址，统计用不上）。 */
    const val MAX_CELL = 200

    /** 表头与数据行。`rows` **不含**表头。 */
    data class Grid(val header: List<String>, val rows: List<List<String>>) {
        val cols: Int get() = header.size
        val rowCount: Int get() = rows.size
    }

    /** 筛选条件：`column` 那一格（去掉首尾空白、不分大小写）等于 `value`。 */
    data class RowFilter(val column: Int, val value: String)

    /**
     * 一次统计的结果。
     *
     * @param value  sum/avg/max/min 的结果；`count`/`distinct` 时为 null
     * @param count  **符合条件的行数**
     * @param numbers 这一列里能当数字用的格子数 —— 和 `count` 不一样时要说清楚
     *        （「20 行里只有 15 格是数字」是个会被追问的事实）
     * @param values `distinct` 的取值（按出现顺序，最多 [limit] 个）
     * @param more   `distinct` 因为 [limit] 没列全
     */
    data class Agg(
        val op: String,
        val value: Double?,
        val count: Int,
        val numbers: Int,
        val values: List<String> = emptyList(),
        val more: Boolean = false,
    )

    /** 能做的运算。顺序就是回执里列给模型看的顺序。 */
    val OPS = listOf("sum", "avg", "count", "max", "min", "distinct")

    // ---------------------------------------------------------------- 切分

    /**
     * 按分隔符切。`\t` 和 `,` 都走这里（tsv 也常有双引号）。
     *
     * ★ 双引号只在**格子开头**才算引号的开始：`a"b` 里的那个引号是普通字符。
     *   不这样分的话，一格身份证号里带个引号，后面整张表就错一位 —— 而且不报错。
     * ★ 引号里的 `""` 是一个引号；引号里的换行属于格子内容，不是换行。
     */
    fun parseDelimited(text: String, sep: Char): List<List<String>> {
        val out = ArrayList<List<String>>()
        val s = text.removePrefix("﻿")
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') {
                        cell.append('"'); i += 2; continue
                    }
                    quoted = false; i += 1; continue
                }
                cell.append(c); i += 1; continue
            }
            when {
                c == '"' && cell.isEmpty() -> { quoted = true; i += 1 }
                c == sep -> { row.add(cell.toString()); cell.setLength(0); i += 1 }
                c == '\r' -> i += 1
                c == '\n' -> {
                    row.add(cell.toString()); cell.setLength(0)
                    out.add(row); row = ArrayList()
                    i += 1
                }
                else -> { cell.append(c); i += 1 }
            }
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            out.add(row)
        }
        return out
    }

    /**
     * 切成「表头 + 数据行」。
     *
     * 规则（都为了**不要悄悄错位**）：
     *   - 每行去掉尾部的空格子（`a,,` 这种多半是导出工具加的）；
     *   - 全是空格子的行整行丢掉（空行不该占一行数据）；
     *   - 宽度按最宽的那行补齐，缺的补空串 —— 宁可多一列空的，也不能让后面几行左移；
     *   - 表头里空的格子叫「第N列」，不然模型没法引用它。
     *
     * @return null = 一行数据都没有
     */
    fun gridOf(raw: List<List<String>>): Grid? {
        val rows = raw
            .map { r -> r.map { clip(it) } .dropLastWhile { it.isEmpty() } }
            .filter { r -> r.any { it.isNotEmpty() } }
        if (rows.isEmpty()) return null
        val width = rows.maxOf { it.size }.coerceAtMost(MAX_COLS)
        fun pad(r: List<String>) = List(width) { r.getOrElse(it) { "" } }

        val head = pad(rows[0]).mapIndexed { i, h ->
            h.trim().ifEmpty { "第${i + 1}列" }
        }
        val body = rows.drop(1).take(MAX_ROWS).map { pad(it) }
        if (body.isEmpty()) return null
        return Grid(head, body)
    }

    private fun clip(s: String): String {
        val t = s.trim()
        return if (t.length <= MAX_CELL) t else t.take(MAX_CELL)
    }

    // ---------------------------------------------------------------- 数字

    /**
     * 一格文字 → 数字。认不出来是 null，**不猜成 0**。
     *
     * 认：`1,234`、`¥12`、`12.5 %`、`-3`、`+7`；空格和全角空格都去掉。
     * 不认：`2026-11-07`（那是日期，加它没有意义）、`12人`、`约30`、空串。
     *
     * ★ 百分号只去掉符号、**不除以 100**：一列都是百分比时，求和得到的是
     *   「加起来几个百分点」，除以 100 反而是个要再解释一遍的数。
     */
    fun normNum(raw: String): Double? {
        var s = raw.trim().replace(" ", "").replace(" ", "").replace("　", "")
        if (s.isEmpty()) return null
        s = s.replace(",", "").replace("，", "")
        s = s.trimStart('¥', '￥', '$', '€', '£', '元')
        if (s.endsWith("%") || s.endsWith("％")) s = s.dropLast(1).trim()
        if (s.isEmpty()) return null
        return s.toDoubleOrNull()
    }

    /** 回执里那个数：整数就不带小数点，否则最多两位、尾巴上的 0 去掉（`0.50` → `0.5`）。 */
    fun fmt(v: Double): String {
        if (!v.isFinite()) return "算不出来"
        val r = Math.round(v * 100.0) / 100.0
        if (r == Math.floor(r) && Math.abs(r) < 1e15) return r.toLong().toString()
        return String.format(java.util.Locale.US, "%.2f", r).trimEnd('0').trimEnd('.')
    }

    // ---------------------------------------------------------------- 找列

    /**
     * 按名字找列的下标（从 0 开始），找不到是 -1。
     *
     * 顺序：数字（当第几列，**从 1 数**） → 完全一样 → 忽略大小写一样 → 只有一个包含它。
     * ★ 有**多个**格子包含同一个词时返回 -1，不挑第一个：「金额」同时命中「金额」和
     *   「税额金额」时替模型赌一个，算出来的是另一列的数，而且看起来完全正常。
     */
    fun colIndex(header: List<String>, key: String): Int {
        val k = key.trim()
        if (k.isEmpty()) return -1
        k.toIntOrNull()?.let { n -> return if (n in 1..header.size) n - 1 else -1 }
        header.forEachIndexed { i, h -> if (h == k) return i }
        header.forEachIndexed { i, h -> if (h.equals(k, ignoreCase = true)) return i }
        val hits = header.indices.filter { header[it].contains(k, ignoreCase = true) }
        return if (hits.size == 1) hits[0] else -1
    }

    /** 找列失败时给人看的那一句：把表头列出来，让他换个词再说一次。 */
    fun colHint(header: List<String>, key: String, max: Int = 12): String {
        val near = header.filter { key.isNotBlank() && (it.contains(key) || key.contains(it)) }
        val head = header.take(max).joinToString("、")
        val tail = if (header.size > max) " 等 ${header.size} 列" else ""
        return "表里没有叫「$key」的列。现有的列是：$head$tail。" +
            if (near.isEmpty()) "" else "（你是不是想说：${near.joinToString("、")}？）"
    }

    // ---------------------------------------------------------------- 统计

    fun filterOf(g: Grid, column: String, value: String): RowFilter? {
        val idx = colIndex(g.header, column)
        return if (idx < 0) null else RowFilter(idx, value.trim())
    }

    fun matchedRows(g: Grid, f: RowFilter?): List<List<String>> {
        if (f == null) return g.rows
        return g.rows.filter { it.getOrElse(f.column) { "" }.trim().equals(f.value, ignoreCase = true) }
    }

    /**
     * 算一次。
     *
     * @param op [OPS] 里的一个；不认识的由调用方先拒掉
     * @param limit `distinct` 最多列几个不同的值
     */
    fun agg(g: Grid, col: Int, op: String, filter: RowFilter? = null, limit: Int = 30): Agg {
        val hit = matchedRows(g, filter)
        val cells = hit.map { it.getOrElse(col) { "" } }
        return when (op) {
            "count" -> Agg(op, null, hit.size, cells.count { normNum(it) != null })
            "distinct" -> {
                val seen = LinkedHashSet<String>()
                for (c in cells) if (c.isNotEmpty()) seen.add(c)
                val list = seen.toList()
                Agg(op, null, hit.size, list.size,
                    values = list.take(limit.coerceIn(1, 200)),
                    more = list.size > limit.coerceIn(1, 200))
            }
            else -> {
                val nums = cells.mapNotNull { normNum(it) }
                val v = when (op) {
                    "sum" -> if (nums.isEmpty()) null else nums.sum()
                    "avg" -> if (nums.isEmpty()) null else nums.average()
                    "max" -> nums.maxOrNull()
                    "min" -> nums.minOrNull()
                    else -> null
                }
                Agg(op, v, hit.size, nums.size)
            }
        }
    }
}
