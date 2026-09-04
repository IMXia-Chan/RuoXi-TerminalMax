package com.example.touchpad

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import org.json.JSONObject

/**
 * 文件互传:手机 ⇄ 电脑。
 *
 * [PhoneTransferStore] —— 手机上「中转站」落盘处:
 *   - 默认:App 专属外置目录(免任何存储权限,保证一定能写入);
 *   - 可选:用户在系统文件选择器里授权的 SAF 目录树(存到可见的 Download 等,
 *     用系统标准 Storage Access Framework API,不需要存储权限)。
 *   收电脑来的文件、给电脑看的目录清单、被电脑「取回」都走这里。
 *
 * [FileTransfer] —— 手机 -> 电脑 的上传客户端:独立 TCP(复用 9527),首行
 * `FILE <token>`,之后与 media.py 同款分帧 [type][len][payload]:
 *   type=3 元数据 json {"name","size"}(size<0 表示未知总长)
 *   type=4 数据块   type=5 结束
 */
object PhoneTransferStore {
    private const val TAG = "PhoneTransferStore"
    private const val PREFS = "touchpad"
    private const val KEY_TREE = "transfer_tree_uri"
    private const val MIME_DIR = "vnd.android.document/directory"
    private const val MAX_NAME_LEN = 160

    data class Entry(val name: String, val size: Long, val isDir: Boolean)

    fun treeUri(ctx: Context): Uri? {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE, null) ?: return null
        return try { Uri.parse(s) } catch (e: Exception) { null }
    }

    fun setTreeUri(ctx: Context, uri: Uri?) {
        val ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (uri == null) ed.remove(KEY_TREE) else ed.putString(KEY_TREE, uri.toString())
        ed.apply()
    }

    /** 默认落盘:App 专属外置目录(免权限、一定能写)。 */
    fun fallbackDir(ctx: Context): File {
        val d = File(ctx.getExternalFilesDir(null), "中转站")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** 给 UI 看的落盘位置说明(用户选了 SAF 树则显示系统文件夹名)。 */
    fun summary(ctx: Context): String {
        val t = treeUri(ctx)
        if (t == null) return fallbackDir(ctx).absolutePath
        val name = queryTreeDisplayName(ctx, t)
        return "系统文件夹:${name ?: t.toString()} (可再改)"
    }

    // ---- 列出手机中转目录 ----
    fun list(ctx: Context): List<Entry>? {
        val t = treeUri(ctx)
        return if (t != null) listTree(ctx, t) else listFileDir(fallbackDir(ctx))
    }

    private fun listTree(ctx: Context, tree: Uri): List<Entry>? {
        val out = ArrayList<Entry>()
        try {
            val docId = DocumentsContract.getTreeDocumentId(tree)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            ctx.contentResolver.query(childrenUri, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val mime = c.getString(1) ?: ""
                    val size = if (c.isNull(2)) 0L else c.getLong(2)
                    out.add(Entry(name, size, mime == MIME_DIR))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "listTree 失败", e)
            return null
        }
        out.sortWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        return out
    }

    private fun listFileDir(dir: File): List<Entry> {
        val out = ArrayList<Entry>()
        dir.listFiles()?.forEach { f ->
            out.add(Entry(f.name, if (f.isFile) f.length() else 0L, f.isDirectory))
        }
        out.sortWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        return out
    }

    // ---- 在手机中转目录里新建一个文件(唯一名),返回输出流(后台线程调用) ----
    class Created(val name: String, val out: OutputStream?, val delete: () -> Unit)

    fun create(ctx: Context, rawName: String): Created? {
        val safe = sanitizeName(rawName)
        val t = treeUri(ctx)
        return if (t != null) createInTree(ctx, t, safe) else createInDir(fallbackDir(ctx), safe)
    }

    private fun sanitizeName(raw: String): String {
        var n = raw.replace("\\", "/").substringAfterLast('/').trim()
        n = n.filter { it.code >= 32 && it.code != 127 }
        if (n.isEmpty()) n = "未命名"
        return n.take(MAX_NAME_LEN)
    }

    private fun createInDir(dir: File, name: String): Created? {
        return try {
            val f = uniqueFile(dir, name)
            if (!f.createNewFile()) return null
            Created(f.name, FileOutputStream(f), { f.delete() })
        } catch (e: Exception) {
            Log.w(TAG, "createInDir", e)
            null
        }
    }

    private fun createInTree(ctx: Context, tree: Uri, name: String): Created? {
        return try {
            val used = uniqueTreeName(ctx, tree, name)
            val docUri = DocumentsContract.createDocument(
                ctx.contentResolver, tree, "application/octet-stream", used
            ) ?: return null
            val out = try {
                ctx.contentResolver.openOutputStream(docUri, "w")
            } catch (e: Exception) {
                try { DocumentsContract.deleteDocument(ctx.contentResolver, docUri) } catch (_: Exception) {}
                throw e
            }
            Created(used, out, { try { DocumentsContract.deleteDocument(ctx.contentResolver, docUri) } catch (_: Exception) {} })
        } catch (e: Exception) {
            Log.w(TAG, "createInTree", e)
            null
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists() && i < 1000) {
            f = File(dir, "$stem ($i)$ext")
            i++
        }
        return f
    }

    private fun uniqueTreeName(ctx: Context, tree: Uri, name: String): String {
        // SAF 提供方对同名文件行为不一,先自查同层是否已存在,存在则补 (n)
        val exist = listTree(ctx, tree)?.map { it.name }?.toHashSet() ?: emptySet()
        if (!exist.contains(name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (exist.contains("$stem ($i)$ext") && i < 1000) i++
        return "$stem ($i)$ext"
    }

    /** 删除本次接收失败的文件(后台线程)。 */
    fun deleteCreated(ctx: Context, created: Created) {
        try { created.delete() } catch (_: Exception) {}
    }

    /** 按文件名删除手机中转目录里的一个文件(真删;调用前已弹确认)。SAF 树或默认目录都支持。 */
    fun deleteByName(ctx: Context, name: String): Boolean {
        val t = treeUri(ctx)
        if (t != null) {
            return try {
                val docId = DocumentsContract.getTreeDocumentId(t)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(t, docId)
                val cols = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                )
                ctx.contentResolver.query(childrenUri, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        if (c.getString(1) == name) {
                            val id = c.getString(0) ?: return false
                            val doc = DocumentsContract.buildDocumentUriUsingTree(t, id)
                            return DocumentsContract.deleteDocument(ctx.contentResolver, doc)
                        }
                    }
                }
                false
            } catch (e: Exception) {
                Log.w(TAG, "deleteByName(tree)", e)
                false
            }
        }
        val f = File(fallbackDir(ctx), name)
        return f.isFile && f.delete()
    }

    // ---- 给电脑看的清单 JSON ----
    fun toJson(entries: List<Entry>?): String {
        val arr = JSONObject()
        if (entries == null) {
            arr.put("err", "无法读取手机中转文件夹")
            return arr.toString()
        }
        val list = org.json.JSONArray()
        entries.forEach { e ->
            list.put(JSONObject().put("n", e.name).put("s", e.size).put("d", if (e.isDir) 1 else 0))
        }
        arr.put("entries", list)
        return arr.toString()
    }

    // ---- 被电脑「取回」/本地上传:按文件名找可用 Uri ----
    fun findUri(ctx: Context, name: String): Uri? {
        val t = treeUri(ctx)
        if (t != null) {
            try {
                val docId = DocumentsContract.getTreeDocumentId(t)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(t, docId)
                val cols = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                )
                ctx.contentResolver.query(childrenUri, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        if (c.getString(1) == name) {
                            val id = c.getString(0) ?: continue
                            return DocumentsContract.buildDocumentUriUsingTree(t, id)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "findUri(tree)", e)
            }
            return null
        }
        val f = File(fallbackDir(ctx), name)
        return if (f.isFile) Uri.fromFile(f) else null
    }

    /** 用系统的媒体/文档 URI 取显示名;失败返回 fallback。 */
    fun displayNameOf(ctx: Context, uri: Uri): String {
        var n: String? = null
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) n = c.getString(0) }
        } catch (_: Exception) {}
        return n ?: uri.lastPathSegment ?: "文件"
    }

    /** 尽量取已知大小;-1 表示未知(流式来源)。 */
    fun sizeOf(ctx: Context, uri: Uri): Long {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0) }
        } catch (_: Exception) {}
        return -1L
    }

    private fun queryTreeDisplayName(ctx: Context, tree: Uri): String? {
        return try {
            val id = DocumentsContract.getTreeDocumentId(tree)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            ctx.contentResolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        } catch (e: Exception) { null }
    }

    /** 请求把收到的 SAF 目录授权持久化(重启后仍可读写)。 */
    fun persistTree(ctx: Context, uri: Uri) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            ctx.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            Log.w(TAG, "takePersistableUriPermission", e)
        }
        setTreeUri(ctx, uri)
    }
}

/**
 * 手机 -> 电脑 上传。在后台线程建立独立 FILE 连接发送单个文件。
 * @param openInput 返回待上传文件的输入流(在工作线程里调用,避免主线程 IO)。
 */
object FileTransfer {
    private const val TAG = "FileTransfer"
    private const val TYPE_META = 3
    private const val TYPE_CHUNK = 4
    private const val TYPE_END = 5
    private const val HEADER_LEN = 5
    private const val CHUNK = 256 * 1024

    fun upload(
        ip: String,
        port: Int,
        token: String,
        name: String,
        knownSize: Long,
        openInput: () -> InputStream?,
        onResult: (ok: Boolean, msg: String) -> Unit,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        Thread {
            var socket: Socket? = null
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(ip, port), 5000)
                socket = s
                val out = s.getOutputStream()
                writeBytes(out, "FILE $token\n".toByteArray(Charsets.UTF_8))
                val line = readLine(s.getInputStream())
                if (line != "FILE_OK") {
                    onResult(false, if (line?.startsWith("ERR") == true) "电脑未就绪: $line" else "连接被拒")
                    return@Thread
                }
                val meta = JSONObject()
                    .put("name", name)
                    .put("size", knownSize)
                    .toString().toByteArray(Charsets.UTF_8)
                writeFrame(out, TYPE_META, meta)

                val digest = MessageDigest.getInstance("SHA-256")
                var got = 0L
                var lastMb = -1L
                val total = if (knownSize > 0) knownSize else -1L
                val buf = ByteArray(CHUNK)
                val input = openInput()
                if (input == null) {
                    onResult(false, "无法读取该文件")
                    return@Thread
                }
                input.use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        got += n
                        writeFrame(out, TYPE_CHUNK, buf.copyOf(n))
                        val mb = got shr 20            // 每 ~1MB 上报一次进度,避免刷屏
                        if (mb != lastMb) {
                            lastMb = mb
                            onProgress(got, total)
                        }
                    }
                }
                writeFrame(out, TYPE_END, ByteArray(0))

                val resp = readLine(s.getInputStream()) ?: ""
                if (resp.startsWith("FILE_DONE ")) {
                    val serverSha = resp.substringAfter(' ').trim()
                    val mySha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (serverSha.equals(mySha, ignoreCase = true)) {
                        onResult(true, "已发送到电脑: $name")
                    } else {
                        onResult(false, "传输后校验不一致,请重试")
                    }
                } else {
                    onResult(false, "电脑接收失败: ${resp.take(60)}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "upload 失败", e)
                onResult(false, "上传失败: ${e.message}")
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }.apply { isDaemon = true; start() }
    }

    /** 取一个 Uri 的输入流(content 走 ContentResolver,file 直接 File)。 */
    fun openUriStream(resolver: ContentResolver, uri: Uri): InputStream? {
        return try {
            if (uri.scheme == "content") resolver.openInputStream(uri)
            else FileInputStream(File(uri.path ?: return null))
        } catch (e: Exception) { null }
    }

    private fun writeFrame(out: OutputStream, type: Int, payload: ByteArray) {
        val len = payload.size
        val header = byteArrayOf(
            type.toByte(),
            ((len shr 24) and 0xFF).toByte(),
            ((len shr 16) and 0xFF).toByte(),
            ((len shr 8) and 0xFF).toByte(),
            (len and 0xFF).toByte(),
        )
        out.write(header)
        if (len > 0) out.write(payload)
        out.flush()
    }

    private fun writeBytes(out: OutputStream, b: ByteArray) {
        out.write(b)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
        }
    }
}
