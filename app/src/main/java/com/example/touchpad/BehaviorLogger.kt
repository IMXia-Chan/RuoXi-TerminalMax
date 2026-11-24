package com.example.touchpad

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 行为数据采集:把每次 AI 任务的完整轨迹落成 JSONL(每行一条)。
 *
 * 这是「训练/微调」的地基 —— 攒的是 (用户指令 + 每一步工具调用 + 成败 + 是否被停止)
 * 的样本。最有价值的是**负信号**:用户按了「停止」= 这轮做错了/太慢;任务失败 = 没做成。
 * 数据攒够后拿去 SFT/DPO,方向见 memory: ruoxi-long-horizon-planning。
 *
 * 只依赖 JSONObject + File 追加写,与项目其余部分一致,不加库。
 * 采集失败绝不能影响 AI 主流程 —— 所有写操作都吞异常。
 */
object BehaviorLogger {

    private var logFile: File? = null
    private var count = 0

    fun init(ctx: Context) {
        val dir = File(ctx.filesDir, "behavior")
        dir.mkdirs()
        logFile = File(dir, "traces.jsonl")
        count = logFile?.takeIf { it.exists() }?.readLines()?.size ?: 0
    }

    /** 已记录的轨迹条数。给设置界面显示,让用户看得见数据在涨。 */
    fun count(): Int = count

    /** 追加一条轨迹。trace 由 AiAgent 填好(见 [AiAgent.finishTrace])。 */
    @Synchronized
    fun append(trace: JSONObject) {
        val f = logFile ?: return
        try {
            f.appendText(trace.toString() + "\n")
            count++
        } catch (_: Exception) {
            // 采集失败不影响主流程。
        }
    }

    fun filePath(): String? = logFile?.absolutePath

    /** 导出到系统 Download 目录(Android 10+ 免权限),方便拿出来看/喂训练。 */
    fun exportToDownloads(ctx: Context): String {
        val f = logFile ?: return "还没有数据文件"
        if (!f.exists() || f.length() == 0L) return "还没有数据(用几次 AI 再来导出)"
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "ruoxi-behavior-$stamp.jsonl"
        // 故意**不设 MIME_TYPE**:MediaProvider 会拿 MIME 去查 MimeTypeMap 要扩展名,
        // 名字不是以它结尾就补一个。上次设了 application/json,于是得到
        // 「…jsonl.json」这种双后缀(实测踩到)。不设 MIME 就没有这个「该以什么结尾」的
        // 判断,名字原样落盘。jsonl 本来也没有官方 MIME。
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return "下载目录不可用"
        return try {
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                f.inputStream().use { it.copyTo(out) }
            } ?: return "打不开下载目标"
            "已导出到 Download/$name"
        } catch (e: Exception) {
            "导出失败:${e.message}"
        }
    }
}
