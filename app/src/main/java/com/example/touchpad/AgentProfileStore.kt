package com.example.touchpad

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Agent 的档案，落在盘上。
 *
 * ## 一个 agent 一个文件
 *
 * `filesDir/agents/<id>.json`。★ 和 [WorkJobStore] 同一套写法（先写 `.tmp` 再 rename，
 * rename 失败就直写兜一手）—— 半截文件读回来是一个**假**的 agent：它记着做过的事，
 * 其实一件没做过。
 *
 * ## 为什么身份与记忆必须落盘
 *
 * 「真正的 agent」和「一次调用配一个角色名」的唯一区别就在这里：
 * 手机被回收、App 被杀、他隔天再打开 —— 这个 agent 还得是**同一个它**。
 * 只放内存等于每开一次 App 就换一个人，而那种错在屏上完全看不出来。
 */
internal object AgentProfileStore {

    private var dir: File? = null

    fun init(ctx: Context) {
        if (dir != null) return
        dir = try {
            File(ctx.filesDir, "agents").apply { mkdirs() }
        } catch (_: Exception) {
            null
        }
    }

    /** 全部 agent。读坏一个就跳过，不让一份坏档案拖垮整个名单。 */
    fun all(): List<AgentProfile> {
        val d = dir ?: return emptyList()
        val out = ArrayList<AgentProfile>()
        try {
            val files = d.listFiles() ?: return emptyList()
            for (f in files) {
                if (!f.isFile || !f.name.endsWith(".json")) continue
                try {
                    out.add(AgentProfile.fromJson(JSONObject(f.readText())) ?: continue)
                } catch (_: Exception) {
                    continue
                }
            }
        } catch (_: Exception) {
            return out
        }
        return out
    }

    fun find(id: String): AgentProfile? = all().firstOrNull { it.id == id }

    fun save(p: AgentProfile): String? {
        val d = dir ?: return "agent 的存档目录建不起来，这个 agent 只在内存里。"
        val f = File(d, p.id + ".json")
        return try {
            val tmp = File(d, p.id + ".json.tmp")
            tmp.writeText(p.toJson().toString())
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())   // 兜一手（同 WorkJobStore.save）
                tmp.delete()
            }
            null
        } catch (e: Exception) {
            "存不进盘：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 记一条结论，并把「做过一件」加上。档案不在了就什么都不做。 */
    fun remember(id: String, text: String, now: Long): AgentProfile? {
        val old = find(id) ?: return null
        val next = old.copy(
            notes = AgentProfileMath.addNote(old.notes, text, now),
            runs = old.runs + 1,
        )
        save(next)
        return next
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
