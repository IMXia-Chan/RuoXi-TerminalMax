package com.example.touchpad

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 定时任务落在盘上：`filesDir/tasks/<id>.json`。
 *
 * ★ 和 [WorkJobStore] 同一套写法（先写 `.tmp` 再 rename，rename 失败就直写兜一手）——
 *   半截文件读回来是一条**假**的任务，比没有更糟：它会照样被排进闹钟，
 *   到点拿着半句话去跑。
 *
 * ★ 必须落盘：AlarmManager 那一条只是「什么时候叫我」，**任务内容在这儿**。
 *   进程被杀、手机重启之后，闹钟要靠这份文件重新排（见 [TaskAlarm.rescheduleAll]）。
 */
internal object TaskStore {

    private var dir: File? = null
    private val seq = AtomicInteger(1)

    fun init(ctx: Context) {
        if (dir != null) return
        dir = try {
            File(ctx.filesDir, "tasks").apply { mkdirs() }
        } catch (_: Exception) {
            null
        }
    }

    /** 新任务号。**和任务号（`w`）/她的账（`t`）都不撞前缀**：`s` 开头。 */
    fun newId(): String = "s" + System.currentTimeMillis() + "-" + seq.getAndIncrement()

    fun all(): List<TaskPlan> {
        val d = dir ?: return emptyList()
        val out = ArrayList<TaskPlan>()
        try {
            val files = d.listFiles() ?: return emptyList()
            for (f in files) {
                if (!f.isFile || !f.name.endsWith(".json")) continue
                try {
                    out.add(TaskPlan.fromJson(JSONObject(f.readText())) ?: continue)
                } catch (_: Exception) {
                    continue
                }
            }
        } catch (_: Exception) {
            return out
        }
        return out.sortedBy { it.createdAt }
    }

    fun find(id: String): TaskPlan? = all().firstOrNull { it.id == id }

    fun save(plan: TaskPlan): String? {
        val d = dir ?: return "定时任务的存档目录建不起来，这条只在内存里。"
        val f = File(d, plan.id + ".json")
        return try {
            val tmp = File(d, plan.id + ".json.tmp")
            tmp.writeText(plan.toJson().toString())
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
            null
        } catch (e: Exception) {
            "存不进盘：${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun remove(id: String): Boolean {
        val d = dir ?: return false
        return try {
            File(d, id + ".json").delete()
        } catch (_: Exception) {
            false
        }
    }
}
