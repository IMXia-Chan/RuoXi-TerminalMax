package com.example.touchpad

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 研究型技能的**进度本**。
 *
 * 为什么单开一个东西，而不是塞进技能 JSON：
 *
 * 1. 技能是「方法」，进度是「今天走到哪儿」。前者几乎不变，后者每轮都在长 ——
 *    混在一起，每记一行笔记就要重写一遍整条技能，写坏一次技能就没了。
 * 2. 它必须**能被人直接读**。技能 JSON 是给程序的，进度本是给他看的：
 *    断在哪儿、试过什么、哪条已经证伪了。
 *
 * 一条研究技能一个文件：`files/research/<id>.md`，追加写。
 *
 * ## ★ 截断要说出来
 *
 * 进度本太长会撑爆她的上下文。超过 [MAX_BYTES] 就只留后面那一截，**并在文件头
 * 写一行「前面 X 字已截掉」**。静默截断的后果是：她以为那是全部，于是在
 * 早就证伪的方向上从头再来一遍 —— 比不记还糟。
 */
object ResearchStore {

    private const val TAG = "SANDBOX"

    /** 一本进度本最多多大。约 4 万字节，够记几十轮。 */
    private const val MAX_BYTES = 40_000

    /** 交给模型时最多给多少字 —— 上下文是按字算钱的。 */
    private const val GIVE_CHARS = 6_000

    @Volatile
    private var dir: File? = null

    private val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    fun init(ctx: Context) {
        if (dir != null) return
        dir = File(ctx.filesDir, "research").apply { mkdirs() }
    }

    private fun fileOf(id: String): File? = dir?.let { File(it, id + ".md") }

    /** 整本进度本。没有就是空串。 */
    fun ledger(id: String): String {
        val f = fileOf(id) ?: return ""
        return try {
            if (f.isFile) f.readText() else ""
        } catch (e: Exception) {
            Log.w(TAG, "进度本读不动:" + id, e)
            ""
        }
    }

    /**
     * 交给她的那一截。太长就只给后面一段，**并明说截了多少**。
     */
    fun forModel(id: String): String {
        val all = ledger(id)
        if (all.length <= GIVE_CHARS) return all
        val cut = all.length - GIVE_CHARS
        return "（进度本太长，前面 $cut 字已截掉，这里是最近的部分）\n" + all.substring(cut)
    }

    /** 记一行。失败返回原因，成功返回 null。 */
    fun note(id: String, text: String): String? {
        val f = fileOf(id) ?: return "进度本记不下来（应用私有目录拿不到）。"
        val body = text.trim()
        if (body.isEmpty()) return "记的内容是空的。"
        return try {
            f.parentFile?.mkdirs()
            val line = "\n### ${stamp.format(Date())}\n" + body + "\n"
            f.appendText(line)
            trim(f)
            null
        } catch (e: Exception) {
            Log.w(TAG, "进度本写不进去:" + id, e)
            "进度本没记上：${e.message ?: "写入失败"}。"
        }
    }

    /** 从头再来。**只在用户明确要求时调** —— 笔记里可能有证伪结论，删了要重走一遍。 */
    fun clear(id: String): Boolean = try {
        fileOf(id)?.delete() ?: false
    } catch (_: Exception) {
        false
    }

    private fun trim(f: File) {
        try {
            if (f.length() <= MAX_BYTES) return
            val all = f.readText()
            val keep = all.takeLast(GIVE_CHARS)
            val dropped = all.length - keep.length
            // ★★ 2026-11-08(体检):原来直接 `writeText` 重写 —— 进程在中途被杀,
            //   留下半截文件,整本进度（含已证伪的结论）就都读不出来了,而且不报错。
            AtomicWrite.replace(f, "（进度本超过上限，前面 $dropped 字已截掉）\n" + keep)
        } catch (_: Exception) {
        }
    }
}
