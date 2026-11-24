package com.example.touchpad

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「手」的 JSON 边界 —— **org.json 只许出现在这个文件里**。
 *
 * 规则层([HandModel])一个 JSON 类型都不认识,所以能在纯 JVM 上测。这里是那层
 * 薄壳:进来是 `JSONObject`,出去是 [Hand] 或 `JSONObject`,中间不做任何判断。
 *
 * ---
 *
 * ## 三个来源,三份不同形状的东西,别混
 *
 * | 来源 | 有什么 | 谁调 |
 * |---|---|---|
 * | `HARR` 命令的回包 | 完整自述(工具表 + 指纹 + 资源锁) | [fromHandInfo] |
 * | UDP 发现应答 | **只有身份**(id/kind/name) | [fromDiscovery] |
 * | 本地落盘 | 上面那份 + 问到它的时刻 | [toJson] / [fromJson] |
 *
 * ★ 前两个形状**不一样**,这个区别是要命的(见 [HandMath.merged] 的注释):
 * 发现应答里没有工具表。谁要是想当然地拿它当完整自述用,一扫描就会把一台好好的
 * 电脑写成「会 0 个工具」的空壳。所以这里是两个不同的函数,名字也不让人混淆。
 *
 * ## 电脑那边的话是权威
 *
 * `tools` 里那个 OpenAI function 形状是电脑端 `ai_tools.tool_schema()` 直接吐出来的,
 * 手机这边**只转述不改写**。哪天电脑改了工具表,手机自动跟着变 —— 这正是做这件事
 * 的全部目的(在此之前那份清单是手抄的,漏改是静默的能力缺失)。
 */
internal object HandCodec {

    // ---------------------------------------------------------------------
    // 1. HAND 命令的完整自述
    // ---------------------------------------------------------------------

    /**
     * `{"ok":true,"hand":{...}}` 里那个 `hand` → [Hand]。
     *
     * 解析失败回 null 而不是抛:手是**可选**的,一台电脑自述格式不对不该把
     * 整条连接搞崩。但调用方**必须**把 null 当「什么都没问到」,不能当成
     * 「这只手什么都不会」—— 那是同一件事的两种说法,而后者是撒谎。
     */
    fun fromHandInfo(hand: JSONObject, nowMs: Long): Hand? {
        val id = hand.optString("id").trim()
        if (id.isEmpty()) return null
        return Hand(
            id = id,
            kind = hand.optString("kind").ifEmpty { HandMath.KIND_PC },
            name = hand.optString("name").ifEmpty { id },
            version = hand.optString("version"),
            fetchedAtMs = nowMs,
            tools = toolsOf(hand.optJSONArray("tools")),
            hidden = stringsOf(hand.optJSONArray("hidden")),
            locks = locksOf(hand.optJSONObject("locks")),
        )
    }

    private fun toolsOf(arr: JSONArray?): List<HandTool> {
        if (arr == null) return emptyList()
        val out = ArrayList<HandTool>(arr.length())
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i)?.optJSONObject("function") ?: continue
            val name = f.optString("name").trim()
            if (name.isEmpty()) continue
            val ps = f.optJSONObject("parameters")
            out.add(
                HandTool(
                    name = name,
                    desc = f.optString("description"),
                    params = paramsOf(ps?.optJSONObject("properties")),
                    // ★ 顺序照抄电脑给的。参数表是 LinkedHashMap 性质,顺序稳定;
                    // 按字母重排会让示例和电脑那边的说法对不上。
                    required = stringsOf(ps?.optJSONArray("required")),
                )
            )
        }
        return out
    }

    private fun paramsOf(props: JSONObject?): List<HandParam> {
        if (props == null) return emptyList()
        val out = ArrayList<HandParam>()
        val it = props.keys()
        while (it.hasNext()) {
            val k = it.next()
            out.add(HandParam(k, props.optJSONObject(k)?.optString("description") ?: ""))
        }
        return out
    }

    private fun stringsOf(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out
    }

    /**
     * `{"工具名": ["资源",...] 或 null}` → map。
     *
     * ★ 保留 `null` 的语义:电脑那边**刻意区分**「没声明过」(null → 独占)和
     * 「核对过、确实不占」(`[]`)。这两者要是在这儿被抹平成一个,以后新加的工具
     * 就会**悄悄**变成「不占任何资源」—— 而并行里最难查、后果最脏的错正是这个。
     */
    private fun locksOf(o: JSONObject?): Map<String, List<String>?> {
        if (o == null) return emptyMap()
        val out = HashMap<String, List<String>?>()
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            out[k] = if (o.isNull(k)) null else stringsOf(o.optJSONArray(k))
        }
        return out
    }

    // ---------------------------------------------------------------------
    // 2. UDP 发现应答 —— 只有身份
    // ---------------------------------------------------------------------

    /**
     * 发现应答最外层 → [Hand],**工具表是空的**(那包 UDP 里本来就没有)。
     *
     * 老版本电脑端没有 `hand` 字段(这功能是后加的),那时回 null ——
     * 调用方退化成「只知道 ip:port,叫不出名字」。**兼容是必须的**:新手机连旧电脑
     * 得照常能用,只是列表里没名字,不能因为多了个字段就整个找不到电脑了。
     */
    fun fromDiscovery(obj: JSONObject, nowMs: Long): Hand? {
        val h = obj.optJSONObject("hand") ?: return null
        val id = h.optString("id").trim()
        if (id.isEmpty()) return null
        return Hand(
            id = id,
            kind = h.optString("kind").ifEmpty { HandMath.KIND_PC },
            name = h.optString("name").ifEmpty { id },
            version = "",
            fetchedAtMs = nowMs,
            tools = emptyList(),   // ← 有意为空。见 HandMath.merged
            hidden = emptyList(),
            locks = emptyMap(),
        )
    }

    // ---------------------------------------------------------------------
    // 3. 本机落盘
    // ---------------------------------------------------------------------

    fun toJson(hand: Hand): JSONObject {
        val tools = JSONArray()
        for (t in hand.tools) {
            val props = JSONObject()
            for (p in t.params) props.put(p.name, JSONObject().put("description", p.desc))
            tools.put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", t.name)
                .put("description", t.desc)
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", props)
                    .put("required", JSONArray(t.required)))))
        }
        val locks = JSONObject()
        for ((k, v) in hand.locks) locks.put(k, if (v == null) JSONObject.NULL else JSONArray(v))
        return JSONObject()
            .put("id", hand.id)
            .put("kind", hand.kind)
            .put("name", hand.name)
            .put("version", hand.version)
            .put("fetchedAtMs", hand.fetchedAtMs)
            .put("tools", tools)
            .put("hidden", JSONArray(hand.hidden))
            .put("locks", locks)
    }

    /** 落盘那份 → [Hand]。**这里 `tools` 空和 `tools` 键不在是一个意思**(坏存档)。 */
    fun fromJson(o: JSONObject): Hand? {
        val id = o.optString("id").trim()
        if (id.isEmpty()) return null
        return Hand(
            id = id,
            kind = o.optString("kind").ifEmpty { HandMath.KIND_PC },
            name = o.optString("name").ifEmpty { id },
            version = o.optString("version"),
            fetchedAtMs = o.optLong("fetchedAtMs", 0L),
            tools = toolsOf(o.optJSONArray("tools")),
            hidden = stringsOf(o.optJSONArray("hidden")),
            locks = locksOf(o.optJSONObject("locks")),
        )
    }

    fun listToJson(hands: List<Hand>): String {
        val arr = JSONArray()
        for (h in hands) arr.put(toJson(h))
        return arr.toString()
    }

    /** 坏一条就丢一条,不整份丢 —— 一只手的存档坏了不该让别的手一起消失。 */
    fun listFromJson(text: String): List<Hand> {
        return try {
            val arr = JSONArray(text)
            val out = ArrayList<Hand>(arr.length())
            for (i in 0 until arr.length()) {
                val h = arr.optJSONObject(i)?.let { fromJson(it) } ?: continue
                out.add(h)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }
}
