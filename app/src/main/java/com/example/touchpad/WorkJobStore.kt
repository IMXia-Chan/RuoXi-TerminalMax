package com.example.touchpad

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 工作台上的那几件活，落在盘上。
 *
 * ## 一个任务一个文件
 *
 * `filesDir/work/<任务号>.json`。★ 和 `SkillStore` 同一套写法（先写 `.tmp` 再 rename，
 * rename 失败就直写兜一手）—— 半截文件读回来是一条**假**的活，比没有更糟。
 *
 * ## 为什么必须落盘
 *
 * 一件活的会话是多轮的：手机端进程被回收（ColorOS 会干这事）之后，
 * 他再进来接着说，得还认得上一句说过什么。只放内存等于「切个后台就失忆」。
 *
 * ## 归档 ≠ 删除
 *
 * [archive] 只是打一个标记（`archived=true`）。账在 `AgentBus`、步骤全文在 `StepStore`、
 * 会话在这个文件里，**一样都不动** —— 所以能原样恢复。
 * 真正抹掉要走 [remove]，而且调用方还要一并清 [AgentBus.purge] 和步骤文件，
 * 否则盘上会留下一堆谁也指不到的东西。
 */
internal object WorkJobStore {

    private var dir: File? = null

    fun init(ctx: Context) {
        if (dir != null) return
        dir = try {
            File(ctx.filesDir, "work").apply { mkdirs() }
        } catch (_: Exception) {
            null
        }
    }

    /** 全部活（含归档的）。读坏一条就跳过，不让一条坏档案拖垮整个任务条。 */
    fun all(): List<WorkJob> {
        val d = dir ?: return emptyList()
        val out = ArrayList<WorkJob>()
        try {
            val files = d.listFiles() ?: return emptyList()
            for (f in files) {
                if (!f.isFile || !f.name.endsWith(".json")) continue
                try {
                    out.add(WorkJob.fromJson(JSONObject(f.readText())) ?: continue)
                } catch (_: Exception) {
                    continue
                }
            }
        } catch (_: Exception) {
            return out
        }
        return out
    }

    fun find(id: String): WorkJob? = all().firstOrNull { it.id == id }

    /** 写一条。同名（同任务号）覆盖。 */
    fun save(job: WorkJob): String? {
        val d = dir ?: return "工作台的存档目录建不起来，这件活只在内存里。"
        val f = File(d, job.id + ".json")
        return try {
            val tmp = File(d, job.id + ".json.tmp")
            tmp.writeText(job.toJson().toString())
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())   // 兜一手（同 SkillStore.save）
                tmp.delete()
            }
            null
        } catch (e: Exception) {
            "存不进盘：${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun archive(id: String, now: Long): Boolean = touch(id) {
        it.copy(archived = true, archivedAt = now, updatedAt = now)
    }

    fun unarchive(id: String, now: Long): Boolean = touch(id) {
        it.copy(archived = false, archivedAt = 0, updatedAt = now)
    }

    private fun touch(id: String, f: (WorkJob) -> WorkJob): Boolean {
        val old = find(id) ?: return false
        return save(f(old)) == null
    }

    /**
     * 从盘上删掉这一条。
     *
     * ⚠️ 只删**这件活的档案**。它的账还在 `AgentBus` 里、步骤全文还在 `StepStore` 里 ——
     * 调用方要一并清（见 `WorkbenchActivity.deleteForever`），否则留下谁也指不到的东西。
     */
    fun remove(id: String): Boolean {
        val d = dir ?: return false
        return try {
            File(d, id + ".json").delete()
        } catch (_: Exception) {
            false
        }
    }

    /** 归档那些已经躺了很久的，清掉。没归档的不许动。 */
    fun purgeArchived(before: Long): Int {
        var n = 0
        for (j in all()) {
            if (j.archived && j.archivedAt in 1 until before) {
                if (remove(j.id)) n++
            }
        }
        return n
    }
}
