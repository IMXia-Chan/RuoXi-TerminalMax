package com.example.touchpad

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * 把用户选的一张表读成**行列**（不是一段文字）。
 *
 * ★ 为什么不复用 [DocText]：那条路的用途是「把文件塞进记忆」，它把整张表摊成文字、
 *   砍到几万字，**行的边界和列的边界就没了** —— 对它没关系，对「算」是致命的。
 *   这里要的是能按下标取到的格子，所以 `r="C5"` 这种定位信息必须留住。
 *
 * ★ 只认 csv/tsv/txt 和 xlsx。`xls`（老的二进制）读不了，会明说，不猜。
 */
internal object TableFile {

    data class Res(
        val grid: TableMath.Grid?,
        val sheets: List<String>,
        val name: String,
        val error: String = "",
    )

    /** 表格文件的上限。再大就不是「帮我算」，是把整张卡读进内存。 */
    private const val MAX_BYTES = 40L * 1024 * 1024

    /** 压缩包里单个条目（一个 sheet 的 XML）的上限。 */
    private const val MAX_ENTRY = 30 * 1024 * 1024

    /** 认得的扩展名。不在里面的**不读字节**：选错一个几百 MB 的视频，不该先读进内存再报错。 */
    private val KNOWN = setOf("xlsx", "xlsm", "xls", "csv", "tsv", "txt")

    fun load(ctx: Context, uri: Uri, name: String): Res {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in KNOWN) {
            return Res(null, emptyList(), name, "这不是表格文件（只认 xlsx、csv、tsv、txt）。")
        }
        return try {
            when (ext) {
                "xlsx", "xlsm" -> xlsx(ctx, uri, name)
                "xls" -> Res(null, emptyList(), name, "老的 .xls 格式读不了，请先另存为 .xlsx 或 .csv。")
                else -> delimited(ctx, uri, name, ext)
            }
        } catch (e: Exception) {
            Res(null, emptyList(), name, "读这个文件时出错了（${e.javaClass.simpleName}：${e.message ?: "没说原因"}）。")
        }
    }

    private fun delimited(ctx: Context, uri: Uri, name: String, ext: String): Res {
        val text = decodeText(readBytes(ctx, uri))
        if (text.isBlank()) return Res(null, emptyList(), name, "这个文件是空的。")
        // 分隔符：扩展名说了算；没说的时候看第一行里哪个更多 —— 猜错的话，整张表会
        // 变成一个只有一列的长条，而且**看起来像读成功了**。
        val head = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val sep = when {
            ext == "tsv" -> '\t'
            ext == "csv" -> ','
            head.count { it == '\t' } > head.count { it == ',' } -> '\t'
            else -> ','
        }
        val g = TableMath.gridOf(TableMath.parseDelimited(text, sep))
            ?: return Res(null, emptyList(), name, "这个文件里没有能当表格读的内容。")
        return Res(g, emptyList(), name)
    }

    private fun xlsx(ctx: Context, uri: Uri, name: String): Res {
        val entries = zipEntries(ctx, uri) {
            it == "xl/sharedStrings.xml" ||
                (it.startsWith("xl/worksheets/") && it.endsWith(".xml"))
        }
        if (entries.isEmpty()) return Res(null, emptyList(), name, "这个文件的结构不完整（没有表）。")
        val shared = entries["xl/sharedStrings.xml"]?.let { DocTextMath.sharedStrings(it) } ?: emptyList()
        val sheets = DocTextMath.ordered(entries.keys.filter { it.startsWith("xl/worksheets/") })
        val names = sheets.map { it.substringAfterLast('/').removeSuffix(".xml") }
        // 取第一张**有内容**的表。工作簿里常有空白表在前，按 index 取会读到一张空表，
        // 而那看起来就像「文件是空的」。
        for (s in sheets) {
            val g = TableMath.gridOf(XlsxMath.sheetGrid(entries[s].orEmpty(), shared))
            if (g != null) return Res(g, names, name)
        }
        return Res(null, names, name, "这个工作簿里的表都是空的。")
    }

    // ---------------------------------------------------------------- 字节与压缩包

    private fun readBytes(ctx: Context, uri: Uri): ByteArray {
        val ins = ctx.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("打不开这个文件")
        ins.use { i ->
            val out = ByteArrayOutputStream(1 shl 16)
            val buf = ByteArray(64 * 1024)
            while (out.size() < MAX_BYTES) {
                val n = i.read(buf, 0, minOf(buf.size.toLong(), MAX_BYTES - out.size()).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    private fun decodeText(b: ByteArray): String {
        val utf = String(b, Charsets.UTF_8)
        if (WorkAttachMath.looksTextual(utf)) return utf
        val gbk = String(b, charset("GB18030"))
        return if (WorkAttachMath.looksTextual(gbk)) gbk else utf
    }

    private fun zipEntries(
        ctx: Context,
        uri: Uri,
        keep: (String) -> Boolean,
    ): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val ins = ctx.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("打不开这个文件")
        ins.use {
            ZipInputStream(it.buffered()).use { zip ->
                var entries = 0
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (++entries > 512) break
                    if (e.isDirectory || !keep(e.name)) { zip.closeEntry(); continue }
                    val buf = ByteArrayOutputStream()
                    val b = ByteArray(64 * 1024)
                    while (buf.size() < MAX_ENTRY) {
                        val n = zip.read(b, 0, minOf(b.size, MAX_ENTRY - buf.size()))
                        if (n <= 0) break
                        buf.write(b, 0, n)
                    }
                    out[e.name] = String(buf.toByteArray(), Charsets.UTF_8)
                    zip.closeEntry()
                }
            }
        }
        return out
    }
}

/** xlsx 的 `sheet1.xml` → 二维格子。纯函数，纯 JVM 可测。 */
internal object XlsxMath {

    /**
     * 单元格坐标 → 列下标（从 0 开始）。`A5`→0、`B2`→1、`AA1`→26；认不出是 -1。
     *
     * ★ 这个必须看 `r` 属性，不能数「第几个 `<c>`」：空的格子 xlsx **经常整段省掉**，
     *   数出来的位置会左移，于是「金额」那一列读到的是「备注」—— 求和结果完全正常，
     *   只是算的是另一列。
     */
    fun colOf(ref: String): Int {
        var n = -1
        for (c in ref.trim()) {
            if (c in 'A'..'Z') n = (n + 1) * 26 + (c - 'A')
            else if (c in 'a'..'z') n = (n + 1) * 26 + (c - 'a')
            else break
        }
        return n
    }

    /** 一张表的全部格子。行序按文件里的顺序，列序按 [colOf]，缺的补空串。 */
    fun sheetGrid(xml: String, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val rowRe = Regex("<row\\b[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
        val cellRe = Regex("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL)
        val vRe = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
        for (r in rowRe.findAll(xml)) {
            val cells = ArrayList<String>()
            var fallback = 0
            for (c in cellRe.findAll(r.groupValues[1])) {
                val attrs = c.groupValues[1]
                val body = c.groupValues.getOrElse(2) { "" }
                val ref = Regex("\\br=\"([A-Za-z]+)\\d*\"").find(attrs)?.groupValues?.get(1)
                val idx = colOf(ref ?: "").let { if (it >= 0) it else fallback }
                while (cells.size < idx) cells.add("")
                val t = Regex("\\bt=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1) ?: "n"
                val v = when (t) {
                    "s" -> vRe.find(body)?.groupValues?.get(1)?.trim()?.toIntOrNull()
                        ?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> DocTextMath.xmlToText(body).trim()
                    "b" -> if (vRe.find(body)?.groupValues?.get(1)?.trim() == "1") "是" else "否"
                    else -> vRe.find(body)?.groupValues?.get(1)?.trim().orEmpty()
                }
                if (idx == cells.size) cells.add(DocTextMath.unescape(v))
                else cells[idx] = DocTextMath.unescape(v)
                fallback = idx + 1
            }
            rows.add(cells)
        }
        return rows
    }
}
