package com.example.touchpad

/**
 * 事件流的**数据模型 + 编解码**。纯 JVM，可单测（同 [HandCodec] / [SkillMath] 的惯例）。
 *
 * ---
 *
 * ## 为什么要一条事件流，而不是「让她自己想」
 *
 * 多 agent 有两条路：让每个角色各说各的、最后拼一段话；或者**每一步都留一条可引用的事件**。
 * 前者看起来更「智能」，实际有两个死法：
 *
 * 1. **对不上。** 三个角色各自述，谁依据谁、哪句是猜的、哪个数字从哪来 ——
 *    拼完之后全都丢了。用户要的是「互相协作」，协作的前提是**引用得到**。
 * 2. **看不出来。** 他说的那个科研界面要画「工作流」，如果数据源是模型的自由文本，
 *    界面只能去猜；猜出来的动画再好看也是编的。**事件流是它唯一诚实的数据源。**
 *
 * 所以 [AgentEvent.id] 和 [AgentEvent.ref] 是这张表的主键与指针：
 * 结果是哪个子任务要的、依据的是哪一条，**写成数字，不写成话**。
 *
 * ## 为什么自己写编解码，不用 org.json
 *
 * 事件要**每一条都落盘**（崩了之后还能看它做到哪了）。JSON 每行一个也行，
 * 但这一行里有三样东西一定会出事：换行（模型输出）、竖线（人写的分隔符）、反斜杠。
 * 用 `org.json` 得每行建一次对象，还会在单测里抛「not mocked」。
 * 这里就四个符号的转义，手写比 JSON 短，也不用依赖。
 *
 * ## ★ 落盘的这一份**不脱敏**
 *
 * 事件流是**本机的账**：她做了什么、谁给的答案。给它脱敏的话，出事之后就查不清了。
 * 脱敏只发生在**出门那一份**（[AgentMath.forCloud]），两件事不许混。
 */
internal data class AgentEvent(
    val id: Long,
    /** 哪一次协同。同一次任务的所有事件共用一个值。 */
    val task: String,
    /** 谁发的，见 [AgentBusModel.HUB] 那组常量。 */
    val from: String,
    /** 角色名（「中枢」「算」「查」……）。自由文本，只给人看。 */
    val role: String,
    /** 这一步是什么，见 [AgentBusModel.KIND_*]。 */
    val kind: String,
    /** 指向上一条：它依据哪一条事件。0 = 这是起点。 */
    val ref: Long,
    val text: String,
    val at: Long,
    /**
     * 这一步**真的花掉多少 token**（提示 + 生成），服务端在回包里自报的 `usage`。
     *
     * ★ 0 = 没有账可报（本地算的、或者服务端没给 `usage`）。**不许拿估算值填**：
     *   屏上那个数是给人判断「贵不贵」用的，填一个猜的数比留空更糟。
     * ★ 加在**最后一列**，所以老账本（8 列）照样读得回来，读不到就当 0。
     */
    val tok: Int = 0,
)

internal object AgentBusModel {

    // 谁发的。★ 用英文是为了落盘那份稳定 —— 改中文标签不该动历史文件。
    const val HUB = "hub"        // 本地中枢（唯一有手的那层）
    const val LOCAL = "local"    // 本机算力
    const val CLOUD = "cloud"    // 云端算力（无手）
    const val SELF = "self"      // 她本体（对话里那句话）

    // 这一步是什么。
    const val KIND_ASK = "ask"       // 交给谁去做
    const val KIND_ANSWER = "answer" // 交回来的结果
    const val KIND_TOOL = "tool"     // 动手（调了一只手的工具）
    const val KIND_ROUTE = "route"   // 路由判定（在哪算、为什么）
    const val KIND_NOTE = "note"     // 记一笔（计划、判据、结论）
    const val KIND_ERROR = "error"   // 失败

    private const val SEP = '|'

    /**
     * 三段转义：反斜杠、换行、竖线。
     *
     * ★ 顺序要紧 —— 先转反斜杠。反过来的话，`\n` 里的那个反斜杠会被再转一次，
     *   读回来变成 `\\n` 而不是换行。
     */
    private fun esc(s: String): String {
        val b = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> {}                       // 换行统一成 \n，回车直接丢
                SEP -> b.append("\\p")
                else -> b.append(ch)
            }
        }
        return b.toString()
    }

    private fun unesc(s: String): String {
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> b.append('\n')
                    'p' -> b.append(SEP)
                    '\\' -> b.append('\\')
                    // ★ 不认识的转义**连反斜杠一起吐**。吞掉那个反斜杠看起来更干净，
                    //   但 Windows 路径、正则、LaTeX 里全是「不认识的反斜杠」——
                    //   吞一次就是静默改字，而且改的正是他最常发的那类文本。
                    else -> b.append(s[i]).append(s[i + 1])
                }
                i += 2
            } else {
                b.append(ch)
                i++
            }
        }
        return b.toString()
    }

    /** 一条事件 = 一行。字段顺序固定，读的时候不按名字找。 */
    fun line(e: AgentEvent): String = buildString {
        append(e.id).append(SEP)
        append(esc(e.task)).append(SEP)
        append(esc(e.from)).append(SEP)
        append(esc(e.role)).append(SEP)
        append(esc(e.kind)).append(SEP)
        append(e.ref).append(SEP)
        append(e.at).append(SEP)
        append(esc(e.text))
        // ★ 2026-11-05 起多一列（token）。写在最后：老的 8 列账本照样读得回来。
        append(SEP).append(e.tok)
    }

    /** 读一行。**坏一行就丢一行**，不抛 —— 账本不该让整次读失败。 */
    fun parse(line: String): AgentEvent? {
        if (line.isBlank()) return null
        // text 是最后一列，可以含竖线吗？不能 —— 已经转义过了。所以正好切 7 刀。
        val p = splitFields(line)
        // ★ 8 列 = 2026-11-05 之前的老账（没有 token 那列），9 列 = 之后。
        //   两样都得读得回来：历史账本是 append-only 的，读不回来等于把旧账撕了。
        if (p == null || p.size < 8) return null
        val id = p[0].toLongOrNull() ?: return null
        val ref = p[5].toLongOrNull() ?: return null
        val at = p[6].toLongOrNull() ?: return null
        val tok = if (p.size >= 9) p[8].toIntOrNull() ?: 0 else 0
        return AgentEvent(
            id = id, task = unesc(p[1]), from = unesc(p[2]), role = unesc(p[3]),
            kind = unesc(p[4]), ref = ref, text = unesc(p[7]), at = at, tok = tok,
        )
    }

    /**
     * 按**没转义过的**竖线切。转义后的竖线是 `\p`，里面没有裸竖线，所以能直接切。
     * ★ 但反斜杠本身要成对看：`\\p` 是「一个反斜杠 + 一个 p」，不是转义的竖线。
     *   所以这里逐个字符走，见 `\\` 就一起跳两格。
     */
    private fun splitFields(line: String): List<String>? {
        val out = ArrayList<String>(8)
        val cur = StringBuilder()
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (ch == '\\' && i + 1 < line.length) {
                cur.append(ch).append(line[i + 1])
                i += 2
                continue
            }
            if (ch == SEP) {
                out.add(cur.toString()); cur.setLength(0)
            } else {
                cur.append(ch)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }

    /** 单条事件最多留多少字 —— 账本是看脉络的，不是全文库。 */
    fun clip(s: String): String {
        if (s.length <= AgentMath.EVENT_MAX) return s
        return s.take(AgentMath.EVENT_MAX - 20) + "…（共 ${s.length} 字，后面截掉）"
    }

    /**
     * 把一串事件压成给**另一个角色**看的一段话。
     *
     * ★ 这是「互通」真正落地的地方：角色之间不共享上下文，
     *   它们共享**这张表**。所以压缩必须保三样：谁的、几号、说的是什么。
     *   丢了编号，下游就没法 `ref` 回来，「协作」退化成「几个模型各说各的」。
     *
     * ★ [maxChars] 是硬上限。超了就从**最老的**开始丢，并在最前面明说丢了几条 ——
     *   不说的代价是下游以为那就是全部。
     */
    fun render(events: List<AgentEvent>, maxChars: Int = 4_000): String {
        if (events.isEmpty()) return ""
        val kept = ArrayList<String>()
        var used = 0
        var dropped = 0
        for (e in events.asReversed()) {
            val l = oneLine(e)
            if (used + l.length + 1 > maxChars && kept.isNotEmpty()) { dropped++; continue }
            kept.add(l)
            used += l.length + 1
        }
        kept.reverse()
        val head = if (dropped > 0) "（前面 $dropped 条较早的事件已略去）\n" else ""
        return head + kept.joinToString("\n")
    }

    /** 一条事件的单行写法。给人看，也给别的角色看。 */
    fun oneLine(e: AgentEvent): String {
        val who = if (e.role.isBlank()) e.from else "${roleCn(e.from)}/${e.role}"
        val tail = if (e.ref > 0) " ↳${e.ref}" else ""
        return "#${e.id} [$who]$tail ${e.text.replace("\n", " ")}"
    }

    fun roleCn(from: String): String = when (from) {
        HUB -> "中枢"
        LOCAL -> "本地"
        CLOUD -> "云端"
        SELF -> "她"
        else -> from
    }

    fun kindCn(kind: String): String = when (kind) {
        KIND_ASK -> "派活"
        KIND_ANSWER -> "回话"
        KIND_TOOL -> "动手"
        KIND_ROUTE -> "路由"
        KIND_NOTE -> "记录"
        KIND_ERROR -> "出错"
        else -> kind
    }

    /**
     * 账本超过 [maxBytes] 时，保留最后 [keepLines] 行。
     *
     * ★ 这里只算「该留哪几行」，真删是 [AgentBus] 的事 —— 纯函数好测。
     * ★ 保留的是**尾部**：刚发生的事比三个月前的事有用。
     */
    fun trimLines(lines: List<String>, maxBytes: Int): List<String> {
        var total = 0L
        var i = lines.size
        while (i > 0) {
            val n = lines[i - 1].toByteArray(Charsets.UTF_8).size + 1
            if (total + n > maxBytes) break
            total += n
            i--
        }
        return lines.subList(i, lines.size)
    }
}
