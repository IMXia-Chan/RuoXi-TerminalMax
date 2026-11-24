package com.example.touchpad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * **动作分级 + 本地台账**（2026-11-07）
 *
 * 他 2026-11-04 提的三件事，这一版做前两件：
 *
 * 1. **统一的动作分级表** —— 绿（只读）/ 黄（写本机或动设备）/ 红（发出去、花钱、不可逆）。
 *    新加的工具必须能落进某一档；**落不进就当红**，防止「以后加了功能忘了挂闸」。
 * 2. **本地审计台账** —— 每一次黄/红都记一条（时间、手、工具、参数、结果），只存这台手机。
 *
 * ## ★ 这一版**不改变任何行为**
 *
 * 台账只记录，不拦截。他的话是「先做 1+2（不改变现有行为，只增加可见性），
 * 看到真实数据再定上限」。所以这里没有任何一处会让动作变得做不了 ——
 * 要拦的那一层（频次与上限）是第三步，等有数据再说。
 *
 * ## ★ 为什么分级要「默认红」
 *
 * 表是**人写的**，而工具会**一直加**。默认绿的话，漏填一个就是「一个新的外发动作
 * 悄悄不被记」；默认红的话，漏填只是「多记了一条、看起来严一点」——
 * 错的方向是安全的那一边。
 *
 * ## ★ 台账里不放敏感值
 *
 * 参数里可能有 key、token、密码（她调接口时会带）。写盘前按参数名抹掉值
 * （见 [RiskGrade.redact]）。这台手机上的文件本身就只属于他，但**台账会被他拿出来看**，
 * 也会在演示时被投到屏幕上 —— 那时候 key 不该在里面。
 */
enum class RiskLevel(val label: String) {
    GREEN("绿"),
    YELLOW("黄"),
    RED("红"),
}

object RiskGrade {

    /** 参数名像密钥的：值一律写成 `***`。**宁可误抹，不可漏抹。** */
    private val SECRET_NAME = Regex(
        "(?i)(^|_)(key|token|secret|password|passwd|pwd|auth|authorization|cookie|session)(_|$)"
    )

    fun redact(args: JSONObject): String {
        if (args.length() == 0) return ""
        val out = JSONObject()
        for (k in args.keys()) {
            val v = args.opt(k)
            val text = when (v) {
                null, JSONObject.NULL -> ""
                is String -> v
                else -> v.toString()
            }
            out.put(
                k,
                if (SECRET_NAME.containsMatchIn(k)) "***"
                else if (text.length > 120) text.take(120) + "…" else text
            )
        }
        return out.toString()
    }

    /**
     * 一次调用的档位。
     *
     * ★ 判断顺序是「先按这只手的性质，再按这个工具的名字」—— 反过来的话，
     *   一个叫 `get_secret` 的 PC 工具会被 PC 那条规则收走，而它其实是红的。
     */
    fun level(hand: Hand, tool: String): RiskLevel {
        val t = hand.tools.firstOrNull { it.name == tool }
        return when (hand.kind) {
            HandMath.KIND_SELF -> selfLevel(tool)
            HandMath.KIND_SANDBOX -> RiskLevel.YELLOW
            HandMath.KIND_CLOUD -> cloudLevel(hand.id, t)
            else -> remoteLevel(tool)
        }
    }

    /** 手机自己：只会动这台手机本身，所以最重是黄。 */
    private fun selfLevel(tool: String): RiskLevel = when (tool) {
        // ★ 看照片是**绿**：图只在手机上被本机视觉模型看一遍（`allowCloud` 写死 false），
        //   不落盘、不对外、不改任何东西。要他点「允许」的那一下会白白训练成无脑同意。
        // ★ 看照片、读表格、算数是**绿**：都只在本机读一遍（`allowCloud` 写死 false 的那条
        //   路），不落盘、不对外、不改任何东西。让它们弹「允许」只会训练成无脑同意。
        "say", "calendar_list", "open_settings", "task_list", "see_photo",
        "open_table", "table_calc",
        -> RiskLevel.GREEN
        "open_phone_app", "open_url", "web_search",
        "set_alarm", "set_timer", "copy", "dial", "vibrate", "calendar_add",
        // 会在「下载」里留下一个文件 —— 动了手机上的东西，不是只读，所以是黄。
        "make_file",
        // 画图：**把提示词发到他自己配的那家云服务**，还花他的额度。黄。
        "draw_picture",
        // 取消定时任务只动她自己的档案，不花钱、不对外。
        "task_cancel",
        -> RiskLevel.YELLOW
        // ★ 定一条定时任务 = 以后**每天**在没人看着的时候花他的云端额度去跑一件事。
        //   按这张表的口径（「花钱」是红），它是红，不是黄。
        "schedule_task",
        -> RiskLevel.RED
        // ★ 以后新加的工具落进这里 —— 默认红，见文件头。
        else -> RiskLevel.RED
    }

    /**
     * 云端那些手：接口声明里已经有足够信息，不用再单独填一张表。
     *
     * - 只读（GET）→ 绿；
     * - 会改东西（GET 以外的动词）→ 黄；
     * - 声明里要送图片/文件 → 红（这是「截图永不上云」那条红线的同一根神经）。
     */
    private fun cloudLevel(handId: String, t: HandTool?): RiskLevel {
        // MQTT：发消息是动设备，读是只读。
        if (MqttMath.isMqtt(handId)) {
            return if (t?.name == "publish") RiskLevel.YELLOW else RiskLevel.GREEN
        }
        // 蓝牙：写特征值是动设备；扫描、看服务、读值都是只读。
        if (BleMath.isBle(handId)) {
            return if (t?.name == "write") RiskLevel.YELLOW else RiskLevel.GREEN
        }
        // MCP：服务器的工具我们事先不知道它是干什么的，按红记。
        if (McpMath.isMcp(handId)) return RiskLevel.RED

        val exec = t?.exec ?: return RiskLevel.RED
        if (!ApiMath.sendsOk(t.sends)) return RiskLevel.RED
        return if (exec.method.equals("GET", ignoreCase = true)) RiskLevel.GREEN
        else RiskLevel.YELLOW
    }

    /**
     * 电脑那只手（以后可能还有更多转发型的）：按名字判。
     *
     * ★ 认不出就当红。这类工具是**别人报上来的**，我们连它的参数都不知道，
     *   唯一稳妥的假设是「它可能会往外发东西」。
     */
    private fun remoteLevel(tool: String): RiskLevel {
        val n = tool.lowercase()
        // 只读的那一类
        if (n.startsWith("screenshot") || n.startsWith("list_") || n.startsWith("get_") ||
            n.startsWith("read_") || n.startsWith("find_") || n.contains("status")
        ) return RiskLevel.GREEN
        // 明摆着往外发的
        if (n.contains("send") || n.contains("email") || n.contains("post") ||
            n.contains("upload") || n.contains("publish") || n.contains("pay") ||
            n.contains("transfer") || n.contains("submit")
        ) return RiskLevel.RED
        // 动手改本机状态的那一类
        if (n.startsWith("click") || n.startsWith("type") || n.startsWith("key") ||
            n.startsWith("press") || n.startsWith("scroll") || n.startsWith("move") ||
            n.startsWith("drag") || n.startsWith("open") || n.startsWith("close") ||
            n.startsWith("run") || n.startsWith("launch") || n.startsWith("set_") ||
            n.startsWith("focus") || n.startsWith("save") || n.startsWith("wait")
        ) return RiskLevel.YELLOW
        return RiskLevel.RED
    }
}

/** 台账里的一条。字段刻意少：能对账就够。 */
data class AuditEntry(
    val at: Long,
    val level: RiskLevel,
    val hand: String,
    val tool: String,
    val args: String,
    val ok: Boolean,
    val outcome: String,
)

/**
 * 本地审计台账。**只存这台手机**（`files/audit/log.jsonl`），一行一条，不联网。
 *
 * ★ 只记黄和红。绿的那些（看一眼天气、列一下日程）每天几十条，
 *   记进来会把真正要看的淹掉。
 */
object Audit {

    /** 超过这个行数就把最早的丢掉，只留最近 [KEEP] 条。 */
    private const val MAX_LINES = 3000
    private const val KEEP = 2000

    private var file: File? = null
    private val lock = Any()
    @Volatile private var cachedCount = -1

    fun init(ctx: Context) {
        if (file != null) return
        synchronized(lock) {
            if (file != null) return
            val dir = File(ctx.filesDir, "audit").apply { mkdirs() }
            file = File(dir, "log.jsonl")
            cachedCount = readAll().size
        }
    }

    /**
     * 记一条。**只有黄和红会真的写下去。**
     *
     * ★ 这里的所有异常都吞掉：台账是「多一层可见性」，不该因为它写不进去
     *   就让一次正常的动作失败。写不进去的代价是少一条账，报错的代价是
     *   「她明明做成了，界面说失败」。
     */
    fun record(hand: Hand, tool: String, args: JSONObject, result: JSONObject, task: String = "") {
        val level = RiskGrade.level(hand, tool)
        if (level == RiskLevel.GREEN) return
        val ok = result.optBoolean("ok", false)
        val outcome = when {
            ok -> "成"
            else -> result.optString("error").ifBlank { "没成" }.take(80)
        }
        val e = AuditEntry(
            at = System.currentTimeMillis(),
            level = level,
            hand = hand.name.ifBlank { hand.id },
            tool = tool,
            args = RiskGrade.redact(args) + if (task.isBlank()) "" else " · 任务 $task",
            ok = ok,
            outcome = outcome,
        )
        append(e)
    }

    private fun append(e: AuditEntry) {
        val f = file ?: return
        synchronized(lock) {
            try {
                val line = JSONObject()
                    .put("at", e.at)
                    .put("lv", e.level.label)
                    .put("hand", e.hand)
                    .put("tool", e.tool)
                    .put("args", e.args)
                    .put("ok", e.ok)
                    .put("out", e.outcome)
                    .toString()
                f.appendText(line + "\n")
                cachedCount = if (cachedCount < 0) 1 else cachedCount + 1
                if (cachedCount > MAX_LINES) trim()
            } catch (_: Exception) {
            }
        }
    }

    /** 只留最近 [KEEP] 条。整份重写（这份文件最多几 MB，重写比逐行改简单得多）。 */
    private fun trim() {
        val f = file ?: return
        try {
            val lines = f.readLines().filter { it.isNotBlank() }
            val keep = lines.takeLast(KEEP)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(keep.joinToString("\n", postfix = "\n"))
            if (!tmp.renameTo(f)) {
                f.writeText(keep.joinToString("\n", postfix = "\n"))
                tmp.delete()
            }
            cachedCount = keep.size
        } catch (_: Exception) {
        }
    }

    private fun readAll(): List<AuditEntry> {
        val f = file ?: return emptyList()
        return try {
            f.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
                try {
                    val o = JSONObject(line)
                    AuditEntry(
                        at = o.optLong("at"),
                        level = when (o.optString("lv")) {
                            "红" -> RiskLevel.RED
                            "黄" -> RiskLevel.YELLOW
                            else -> RiskLevel.GREEN
                        },
                        hand = o.optString("hand"),
                        tool = o.optString("tool"),
                        args = o.optString("args"),
                        ok = o.optBoolean("ok"),
                        outcome = o.optString("out"),
                    )
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun count(): Int = synchronized(lock) {
        if (cachedCount >= 0) return cachedCount
        val n = readAll().size
        cachedCount = n
        n
    }

    /** 最近的 [n] 条，新的在前。 */
    fun recent(n: Int = 100): List<AuditEntry> =
        synchronized(lock) { readAll().takeLast(n).reversed() }

    fun clear(): Boolean = synchronized(lock) {
        val f = file ?: return false
        val ok = try {
            f.writeText("")
            true
        } catch (_: Exception) {
            false
        }
        if (ok) cachedCount = 0
        ok
    }

    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    fun timeText(at: Long): String = fmt.format(Date(at))
}
