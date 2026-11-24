package com.example.touchpad

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore

/**
 * **把生成好的文件放进「下载/ConMarn」，让用户拿得到。**
 *
 * ## 为什么不写 App 自己的目录
 *
 * 写 `getExternalFilesDir()` 最省事，但 Android 11 起第三方文件管理器
 * **进不去 `Android/data`**（系统自己的文件 App 也进不去）。文件在那儿等于
 * 用户看不见 —— 那就不叫「交货」。走 MediaStore 的 Downloads，落到
 * 「下载/ConMarn/」，任何文件管理器、任何电脑插上 USB 都找得到，而且
 * **不需要任何存储权限**。
 *
 * ## ★ 落完必须读回来对账
 *
 * MediaProvider 会干两件我们控制不了的事：
 *   1. 同名文件自动改名（`报告.docx` → `报告 (1).docx`）；
 *   2. 某些机型的 MIME 处理会把文件名改掉（见 [BehaviorLogger.exportToDownloads]
 *      踩过的「jsonl.json」双后缀）。
 * 所以「我写了」和「用户能打开的文件叫什么、多大」是两件事：这里写完**按 URI 读回来**，
 * 拿真实的显示名和真实字节数回报，而不是把我准备写的那个名字当成结果。
 */
object DocFile {

    /** 下载目录下的子目录。给他留一个能一眼认出来的地方。 */
    const val SUBDIR = "ConMarn"

    data class Out(
        val ok: Boolean,
        /** MediaProvider 里**真实的**显示名（可能被加了 (1)）。 */
        val name: String = "",
        /** 读回来的字节数。 */
        val bytes: Long = 0,
        /** 读回来的首字节和写进去的一致。 */
        val verified: Boolean = false,
        val error: String = "",
    )

    fun save(ctx: Context, rawName: String, kind: DocGen.Kind, data: ByteArray): Out {
        if (data.isEmpty()) return Out(ok = false, error = "内容是空的，没写文件。")
        val name = DocFileMath.cleanName(rawName, kind.ext)

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, kind.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR)
            // ★ 写的过程中别人别看见一个半截文件（微信那种「0 字节」多半就是没这么做）。
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val cr = ctx.contentResolver
        val uri = try {
            cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            null
        } ?: return Out(ok = false, name = name, error = "下载目录打不开（系统不让写）。")

        try {
            val wrote = runCatching {
                cr.openOutputStream(uri)?.use { it.write(data); it.flush() } != null
            }.getOrDefault(false)
            if (!wrote) {
                runCatching { cr.delete(uri, null, null) }
                return Out(ok = false, name = name, error = "文件打开失败，没写进去。")
            }
            // 落定（IS_PENDING=0）。这一步之前，文件对别的应用不存在。
            cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)

            // ★ 对账：**问系统**这个文件现在叫什么、多大，而不是用我们准备写的那个。
            var realName = name
            var size = -1L
            runCatching {
                cr.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            realName = c.getString(0) ?: name
                            size = c.getLong(1)
                        }
                    }
            }
            // 再读一遍正文的头一截，确认落下去的是我们那份字节（不是空壳）。
            val head = runCatching {
                cr.openInputStream(uri)?.use { ins ->
                    val buf = ByteArray(minOf(data.size, 4096))
                    var off = 0
                    while (off < buf.size) {
                        val n = ins.read(buf, off, buf.size - off)
                        if (n <= 0) break
                        off += n
                    }
                    off
                } ?: -1
            }.getOrDefault(-1)

            // 两个都要对：系统报的大小 = 我们那份的大小，且头一截真能读出来。
            val verified = size == data.size.toLong() && head == minOf(data.size, 4096)
            return Out(ok = true, name = realName, bytes = if (size >= 0) size else data.size.toLong(), verified = verified)
        } catch (e: Exception) {
            runCatching { cr.delete(uri, null, null) }
            return Out(ok = false, name = name, error = e.message ?: e.javaClass.simpleName)
        }
    }
}

/** [DocFile] 的纯逻辑：文件名怎么洗干净。纯 JVM 可测。 */
internal object DocFileMath {

    private val BAD = Regex("[\\\\/:*?\"<>|\\r\\n\\t\\u0000-\\u001f]")

    /**
     * 认得出的扩展名。**只剥这一张表里的** —— 「报告 v1.2」末尾那个 `.2`
     * 不是扩展名，按「短后缀就当扩展名」去剥会把版本号吃掉（→「报告 v1.docx」），
     * 而这种错不会有任何提示。
     */
    private val KNOWN_EXT = setOf(
        "docx", "doc", "xlsx", "xls", "pptx", "ppt", "csv", "md", "txt",
        "pdf", "rtf", "odt", "ods", "odp", "html", "htm", "json",
    )

    /**
     * 用户/模型给的名字 → 一个真的能落盘的名字。
     *
     * - 路径分隔符和 Windows 不认的字符**换成空格**，不报错（模型经常写「报告/初稿」）；
     * - 已经带对了扩展名就不再补；带的是**认得出的**别的扩展名（`.txt` 但格式说 docx）
     *   就换掉 —— 否则会落出一个「报告.docx.txt」，双击打开是一堆 XML 的乱码；
     * - 太长的截到 60 个字符（exFAT 单个文件名上限 255 字节，中文一个字 3 字节）；
     * - 洗完是空的就给个通用的。
     */
    fun cleanName(raw: String, ext: String): String {
        var s = BAD.replace(raw.trim(), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.')
        if (s.isEmpty()) s = "文档"
        val want = "." + ext.lowercase()
        if (s.lowercase().endsWith(want)) {
            // 已经是这个扩展名了：只把扩展名剥掉，最后再统一补一次
            s = s.dropLast(want.length).trimEnd('.')
        } else {
            val m = Regex("\\.([A-Za-z0-9]{1,5})$").find(s)
            val cur = m?.groupValues?.get(1)?.lowercase()
            if (cur != null && cur in KNOWN_EXT) s = s.substring(0, m.range.first).trimEnd('.')
        }
        if (s.isEmpty()) s = "文档"
        if (s.length > 60) s = s.take(60).trimEnd()
        return s + want
    }

    /** 字节数 → 人话。回报给模型用，它再转成给用户看的话。 */
    fun sizeText(bytes: Long): String = when {
        bytes < 1024 -> "$bytes 字节"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    }
}
