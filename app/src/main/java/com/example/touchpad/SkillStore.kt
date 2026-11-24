package com.example.touchpad

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 技能落盘。**一条技能一个文件**，放在 `files/skills/<id>.json`。
 *
 * 为什么不合成一个大 JSON：一条写坏了只丢它自己。合成一份的话，某次写一半断电，
 * 整本技能表一起没了 —— 而且这件事没有任何办法从外面看出来（读回来是空清单，
 * 和「从来没存过」长得一模一样）。
 *
 * ★ 目录按 id 命名，**名字里带中文也照存**；[SkillMath.idOf] 已经把标点换掉了，
 *   剩下的字在这台机器上的文件系统里是能存的（本项目其它落盘文件同样如此）。
 */
object SkillStore {

    private const val TAG = "SANDBOX"

    @Volatile
    private var dir: File? = null

    fun init(ctx: Context) {
        if (dir != null) return
        dir = File(ctx.filesDir, "skills").apply { mkdirs() }
    }

    /** 全部技能。读坏了就丢那一条，不报错 —— 技能是锦上添花，不该让任何流程为它失败。 */
    fun all(): List<Skill> {
        val d = dir ?: return emptyList()
        val files = d.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                fromJson(JSONObject(f.readText()))
            } catch (e: Exception) {
                Log.w(TAG, "技能读不动，跳过:" + f.name, e)
                null
            }
        }
    }

    fun find(key: String): Skill? = SkillMath.pick(all(), key)

    /**
     * 存一条。同名就是覆盖。
     *
     * @return null = 存好了；否则是给他的原话。
     */
    fun save(skill: Skill): String? {
        val d = dir ?: return "技能存不下去（应用私有目录拿不到）。"
        // ★ 两种技能查的东西不一样：动作型查步骤，思考型查方法。这里只做最后一道，
        //   上层（skill_save / skill_new）已经各自查过一遍 —— 两道都留着，
        //   因为「存进去一条空技能」这种错，发现的时候一定是在她叫它的时候。
        SkillMath.shapeProblem(skill.kind, skill.steps, skill.instructions)?.let { return it }
        val steps = SkillMath.clean(skill.steps)

        val old = find(skill.id)
        if (old == null && all().size >= SkillMath.MAX_SKILLS) {
            return "技能已经存了 ${SkillMath.MAX_SKILLS} 条，满了。用 skill_delete(id=…) 删掉几条再存。"
        }
        // 覆盖时保留原来的创建时间和次数 —— 那是「这条技能的历史」，
        // 不该因为改名或重存一次就归零。
        val next = skill.copy(
            steps = steps,
            createdAt = old?.createdAt ?: skill.createdAt,
            runs = old?.runs ?: 0,
        )
        return try {
            val tmp = File(d, next.id + ".json.tmp")
            tmp.writeText(toJson(next).toString())
            val dst = File(d, next.id + ".json")
            if (!tmp.renameTo(dst)) {
                dst.writeText(tmp.readText())   // 同 HandRegistry.persist 的兜底
                tmp.delete()
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "技能写不进去:" + next.id, e)
            "技能没写成：${e.message ?: "写入失败"}。"
        }
    }

    /** 跑成功一次就加一。写失败也不影响这次重放，所以不往上抛。 */
    fun bump(id: String) {
        val s = find(id) ?: return
        runCatching { save(s.copy(runs = s.runs + 1)) }
    }

    fun delete(id: String): Boolean {
        val d = dir ?: return false
        return File(d, id + ".json").delete()
    }

    // ---------------------------------------------------------------- JSON

    private fun toJson(s: Skill): JSONObject {
        val arr = JSONArray()
        for (st in s.steps) {
            arr.put(
                JSONObject()
                    .put("tool", st.tool)
                    // ★ 存文本不存对象：`JSONObject` 一进 `put` 就变成了**引用**，
                    //   之后再被改一处，存档跟着变。技能是历史，历史不该被后来的人改写。
                    .put("args", st.args),
            )
        }
        return JSONObject()
            .put("id", s.id)
            .put("name", s.name)
            .put("desc", s.desc)
            .put("kind", s.kind.name)
            .put("instructions", s.instructions)
            .put("inputs", JSONArray(s.inputs))
            .put("objective", s.objective)
            .put("acceptance", s.acceptance)
            .put("rounds", s.rounds)
            .put("budget", s.budget)
            .put("steps", arr)
            .put("sensitive", s.sensitive)
            .put("draft", s.draft)
            .put("triggers", JSONArray(s.triggers))
            .put("createdAt", s.createdAt)
            .put("runs", s.runs)
    }

    private fun fromJson(o: JSONObject): Skill? {
        val id = o.optString("id", "").ifBlank { return null }
        val kind = when (o.optString("kind", "ACTION").uppercase()) {
            "REASONING" -> SkillKind.REASONING
            "RESEARCH" -> SkillKind.RESEARCH
            else -> SkillKind.ACTION
        }
        val instructions = o.optString("instructions", "")
        val arr = o.optJSONArray("steps")
        val steps = ArrayList<SkillStep>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val st = arr.optJSONObject(i) ?: continue
                val tool = st.optString("tool", "")
                if (tool.isBlank()) continue
                steps.add(SkillStep(tool, st.optString("args", "{}")))
            }
        }
        // ★ 两种技能各自的「这是空的」：动作型没步骤、思考型没方法 —— 都当坏档案丢掉。
        //   留着的话，叫它时什么都不会发生，而回执写着「跑完了」。
        if (kind == SkillKind.ACTION && steps.isEmpty()) return null
        if (kind == SkillKind.REASONING && instructions.isBlank()) return null
        val objective = o.optString("objective", "")
        if (kind == SkillKind.RESEARCH && (objective.isBlank() || o.optString("acceptance", "").isBlank())) {
            return null
        }
        val inputs = ArrayList<String>()
        o.optJSONArray("inputs")?.let { ia ->
            for (i in 0 until ia.length()) {
                val v = ia.optString(i, "").trim()
                if (v.isNotEmpty()) inputs.add(v)
            }
        }
        // ★ 老档案（2026-11-06 之前存的）没有这一栏：读成空 = 只能被 skill_run 点名，
        //   不会自己冒出来。别给它猜一个触发词 —— 猜出来的那一句会在不相干的时候响。
        val triggers = ArrayList<String>()
        o.optJSONArray("triggers")?.let { ta ->
            for (i in 0 until ta.length()) {
                val v = ta.optString(i, "").trim()
                if (v.isNotEmpty()) triggers.add(v)
            }
        }
        return Skill(
            kind = kind,
            instructions = instructions,
            inputs = inputs,
            objective = objective,
            acceptance = o.optString("acceptance", ""),
            rounds = o.optInt("rounds", 0),
            budget = o.optInt("budget", 20).coerceIn(1, 200),
            id = id,
            name = o.optString("name", id),
            desc = o.optString("desc", ""),
            steps = steps,
            sensitive = o.optBoolean("sensitive", true),   // ★ 读不到就当高危,宁可多问一次
            createdAt = o.optLong("createdAt", 0L),
            runs = o.optInt("runs", 0),
            draft = o.optBoolean("draft", true),           // ★ 同上:读不到就当草稿
            triggers = triggers,

        )
    }
}
