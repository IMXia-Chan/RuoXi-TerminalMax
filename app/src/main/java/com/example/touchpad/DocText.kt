package com.example.touchpad

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 一个文件 → 一段字。**必须离开 UI 线程调用。**
 *
 * 格式怎么认、字怎么抠在 [DocTextMath]（纯函数、有单测）；这里只管「从哪儿读」：
 * 压缩包（docx/xlsx/pptx/odt/epub）、PDF 的文字层、图片的 OCR。
 *
 * ## 几条边界
 *
 * - **不是在读的时候才判格式**：选文件时类型不限，什么都能选进来。读不了的要
 *   给一句人话（「老式 .doc 请另存为 docx」），不能给一屏替换字符。
 * - **字数封顶** [MAX_CHARS]：共用记忆一共 400 条，一份 300 页的 PDF 能把库一次塞满，
 *   之后所有活都记不进去。宁可少收一点，也要留出位置给以后的结论。
 * - **PDF 只有文字层**：扫描件抽出来是空的，这时明确说「没有文字层」，
 *   让人知道该走别的路（把图片单独丢进来走 OCR）。
 */
internal object DocText {

    private const val TAG = "DocText"

    /** 一个文件最多收多少字。约 200 条（[AgentMemory.CHUNK]=300 字），只占库的一半。 */
    const val MAX_CHARS = 60_000

    /** 读进内存的字节上限。PDFBox 会把整个文件读进内存，不能没有天花板。 */
    private const val MAX_BYTES = 64 * 1024 * 1024

    /** 压缩包里单个条目读多少字节。一个 xlsx 的内联字符串能大到离谱。 */
    private const val MAX_ENTRY_BYTES = 16 * 1024 * 1024

    /** 最多翻多少个压缩包条目 —— 防 zip 炸弹（一张表里有几万个空文件）。 */
    private const val MAX_ENTRIES = 4000

    /** PDF 最多抽多少页。 */
    private const val MAX_PAGES = 200

    /** OCR 等多久。超时就说清楚，别让它一直挂着。 */
    private const val OCR_TIMEOUT_S = 90L

    /** 图片解码后的最长边。再大只是多占内存，认得出来的字不会更多。 */
    private const val OCR_MAX_SIDE = 2048

    /**
     * @param text 抠出来的字（可能为空）。
     * @param why  **非空就是读不了**，这句话可以直接给他看。
     */
    data class Result(val text: String, val why: String = "") {
        val ok: Boolean get() = why.isEmpty()
    }

    /** OCR 的客户端**建一次就够了**：它内部带着模型，反复建会反复装载。 */
    @Volatile private var recognizer: TextRecognizer? = null

    fun read(ctx: Context, uri: Uri, name: String, mime: String): Result {
        val kind = DocTextMath.kindOf(name, mime)
        return try {
            when (kind) {
                DocTextMath.Kind.TEXT -> cut(decodeText(readBytes(ctx, uri)))
                DocTextMath.Kind.HTML -> cut(DocTextMath.html(decodeText(readBytes(ctx, uri))))
                DocTextMath.Kind.RTF -> cut(DocTextMath.rtf(decodeText(readBytes(ctx, uri))))
                DocTextMath.Kind.DOCX -> zipText(ctx, uri, "word/document.xml") { DocTextMath.docx(it) }
                DocTextMath.Kind.ODF -> zipText(ctx, uri, "content.xml") { DocTextMath.odf(it) }
                DocTextMath.Kind.XLSX -> xlsx(ctx, uri)
                DocTextMath.Kind.PPTX -> pptx(ctx, uri)
                DocTextMath.Kind.EPUB -> epub(ctx, uri)
                DocTextMath.Kind.PDF -> pdf(ctx, uri)
                DocTextMath.Kind.IMAGE -> ocr(ctx, uri)
                DocTextMath.Kind.UNKNOWN -> Result("", unknownWhy(name))
            }
        } catch (e: Exception) {
            Log.w(TAG, "读「$name」失败", e)
            Result("", "这个文件读不了：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun unknownWhy(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return if (ext in setOf("doc", "xls", "ppt")) {
            "这是老式的 .$ext 文件，手机读不了。请用 Office 或 WPS 另存为 .${ext}x 再来。"
        } else {
            "这种格式现在还读不了。"
        }
    }

    // ---------------------------------------------------------------- 读原始字节 / 文字

    private fun readBytes(ctx: Context, uri: Uri, cap: Int = MAX_BYTES): ByteArray {
        val ins = ctx.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("打不开这个文件")
        ins.use { i ->
            val out = ByteArrayOutputStream(minOf(cap, 1 shl 20))
            val buf = ByteArray(64 * 1024)
            while (out.size() < cap) {
                val n = i.read(buf, 0, minOf(buf.size, cap - out.size()))
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    /**
     * 字节 → 文字。
     *
     * ★ 中文的 txt 有一半是 GB18030 存的。先按 UTF-8 解，解出一屏 `�` 就换 GB18030 重来。
     *   两样都不像文字就原样返回由上层判 —— 不能在这里悄悄返回空串，那样「文件是坏的」
     *   和「文件是空的」会变成同一个症状。
     */
    private fun decodeText(b: ByteArray): String {
        val utf = String(b, Charsets.UTF_8)
        if (WorkAttachMath.looksTextual(utf)) return utf
        val gbk = String(b, charset("GB18030"))
        return if (WorkAttachMath.looksTextual(gbk)) gbk else utf
    }

    private fun cut(t: String): Result {
        val s = t.trim()
        if (s.isEmpty()) return Result("", "里面没有能记下来的字。")
        return Result(if (s.length <= MAX_CHARS) s else s.take(MAX_CHARS))
    }

    // ---------------------------------------------------------------- 压缩包类

    /**
     * 把压缩包里若干条目读成文字。
     *
     * @param keep 收哪些条目（按名字判）。
     * @return 条目名 → 内容（**只收不超过上限的**，超了就截断，不报错）。
     */
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
                    if (++entries > MAX_ENTRIES) break
                    if (e.isDirectory || !keep(e.name)) { zip.closeEntry(); continue }
                    val buf = ByteArrayOutputStream()
                    val b = ByteArray(64 * 1024)
                    while (buf.size() < MAX_ENTRY_BYTES) {
                        val n = zip.read(b, 0, minOf(b.size, MAX_ENTRY_BYTES - buf.size()))
                        if (n <= 0) break
                        buf.write(b, 0, n)
                    }
                    // ★ 条目名一律按 UTF-8 读：OOXML 就是这样写的。
                    out[e.name] = String(buf.toByteArray(), Charsets.UTF_8)
                    zip.closeEntry()
                }
            }
        }
        return out
    }

    private fun zipText(
        ctx: Context,
        uri: Uri,
        name: String,
        parse: (String) -> String,
    ): Result {
        val entries = zipEntries(ctx, uri) { it == name }
        val xml = entries[name] ?: return Result("", "这个文件的结构不完整（缺 $name）。")
        return cut(parse(xml))
    }

    private fun xlsx(ctx: Context, uri: Uri): Result {
        val entries = zipEntries(ctx, uri) {
            it == "xl/sharedStrings.xml" ||
                (it.startsWith("xl/worksheets/") && it.endsWith(".xml"))
        }
        if (entries.isEmpty()) return Result("", "这个文件的结构不完整（没有表）。")
        val shared = entries["xl/sharedStrings.xml"]?.let { DocTextMath.sharedStrings(it) } ?: emptyList()
        val sheets = DocTextMath.ordered(entries.keys.filter { it.startsWith("xl/worksheets/") })
        val sb = StringBuilder()
        for (s in sheets) {
            val body = DocTextMath.sheet(entries[s].orEmpty(), shared)
            if (body.isBlank()) continue
            sb.append("〔").append(s.substringAfterLast('/').removeSuffix(".xml")).append("〕\n")
            sb.append(body).append('\n')
        }
        return cut(sb.toString())
    }

    private fun pptx(ctx: Context, uri: Uri): Result {
        val entries = zipEntries(ctx, uri) {
            it.startsWith("ppt/slides/") && it.endsWith(".xml")
        }
        if (entries.isEmpty()) return Result("", "这个文件的结构不完整（没有幻灯片）。")
        val slides = DocTextMath.ordered(entries.keys.toList())
        val sb = StringBuilder()
        slides.forEachIndexed { i, s ->
            val body = DocTextMath.pptx(entries[s].orEmpty())
            if (body.isBlank()) return@forEachIndexed
            sb.append("〔第 ").append(i + 1).append(" 页〕\n").append(body).append("\n\n")
        }
        return cut(sb.toString())
    }

    private fun epub(ctx: Context, uri: Uri): Result {
        val entries = zipEntries(ctx, uri) {
            val n = it.lowercase()
            (n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm")) &&
                !n.contains("toc")
        }
        if (entries.isEmpty()) return Result("", "这个电子书里没有能读的文字。")
        val sb = StringBuilder()
        // 书里的章节名带序号，按数字排；没有序号的靠文件名近似顺序。
        for (n in DocTextMath.ordered(entries.keys.toList())) {
            val body = DocTextMath.html(entries[n].orEmpty())
            if (body.isNotBlank()) sb.append(body).append("\n\n")
        }
        return cut(sb.toString())
    }

    // ---------------------------------------------------------------- PDF

    private fun pdf(ctx: Context, uri: Uri): Result {
        // 初始化一次字体资源加载器：PDFBox 抽文字时某些字体仍要它。
        runCatching { PDFBoxResourceLoader.init(ctx.applicationContext) }
        val bytes = readBytes(ctx, uri)
        PDDocument.load(bytes).use { doc ->
            val pages = doc.numberOfPages
            if (pages <= 0) return Result("", "这份 PDF 里没有页面。")
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                endPage = minOf(pages, MAX_PAGES)
            }
            val text = stripper.getText(doc).trim()
            if (text.isEmpty()) {
                return Result(
                    "",
                    "这份 PDF 没有文字层（扫描件或纯图片）。把里面的页面截成图片单独导进来，会走图片识字。"
                )
            }
            return cut(text)
        }
    }

    // ---------------------------------------------------------------- 图片 OCR

    private fun ocr(ctx: Context, uri: Uri): Result {
        val bmp = decodeSampled(ctx, uri, OCR_MAX_SIDE)
            ?: return Result("", "这张图打不开。")
        val client = recognizer ?: TextRecognition
            .getClient(ChineseTextRecognizerOptions.Builder().build())
            .also { recognizer = it }
        val image = InputImage.fromBitmap(bmp, 0)
        return try {
            val result = Tasks.await(client.process(image), OCR_TIMEOUT_S, TimeUnit.SECONDS)
            bmp.recycle()
            cut(result.text)
        } catch (e: Exception) {
            bmp.recycle()
            Log.w(TAG, "OCR 失败", e)
            Result("", "这张图里的字没认出来：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * 按最长边 [maxSide] 采样解码，并按 EXIF 把照片转正。
     *
     * ★ 不转正的话，竖着拍的书页会横着进 OCR —— 中文横着认基本是全错，
     *   而它**不会报错**，只会给你一排似是而非的字。
     */
    private fun decodeSampled(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val degrees = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                when (ExifInterface(ins).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        if (degrees == 0f) return raw
        val m = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (rotated !== raw) raw.recycle()
        return rotated
    }
}
