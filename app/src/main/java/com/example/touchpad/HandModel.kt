package com.example.touchpad

/**
 * 「一只手」的数据模型 —— 以及关于手的一切**纯逻辑**。
 *
 * **这个文件里不许出现 org.json / Context / 网络。** 项目惯例(见 [MoodMath] /
 * LexiconMath / GreetMath):碰 JSON 的那层薄壳另放,规则层全是纯函数,好让
 * [HandModelTest] 在纯 JVM 上跑。理由不是洁癖,是这层的错**不崩**——
 * 它错的表现是「她调了个不存在的工具」「她瞎编参数」,真机上极难复现。
 *
 * ---
 *
 * ## 「一只手」的判据只有两条
 *
 * 1. **能自述「我会什么」** —— 见 [Hand.tools]。
 * 2. **能诚实回答「做成了没有」** —— 这条不在这个文件里,在回执那边。
 *
 * ★ 第二条是这个项目的血泪。`type` 回成功≠字真的进去了;`click_ui` 的回包
 * `count` 曾经是 hashtable 的**键数**(恒 11)。**会撒谎的手不是手,是陷阱** ——
 * 脑拿到一张假的现状图,后面每一步都建在它上面。
 *
 * ## 为什么手要「自述」而不是手机这边硬编码
 *
 * 在这之前,「电脑会什么」是**手抄在手机端 Kotlin 里**的(见 AiAgent.TOOL_SCHEMA
 * 那段长长的注释)。电脑加一个工具、忘了同步,模型就永远不知道那个工具存在 ——
 * 不报错、不崩,只是能力静默少一块。现在电脑自己会说,手机只负责转达。
 */
data class HandParam(val name: String, val desc: String)

data class HandTool(
    val name: String,
    val desc: String,
    /** 有顺序 —— 拼「可照抄的示例」时按这个顺序,顺序稳定示例才稳定。 */
    val params: List<HandParam>,
    val required: List<String>,
    /**
     * 这个工具**怎么执行**。`null` = 由 [Hand.kind] 决定(电脑转发 / 手机自己分派)。
     *
     * ★ 只有云端手([HandMath.KIND_CLOUD])会带它。之所以挂在 [HandTool] 上而不是
     * 另起一个平行的工具类型:那样 `list_hands` 就得分两条渲染路径,
     * 而**两条路径迟早会分叉** —— 分叉的表现是「云端工具在菜单上显示不全」
     * 或「参数说明漏了」,模型照着残缺的菜单点菜。
     */
    val exec: ApiExec? = null,
    /**
     * 调用它会**送出去什么**。默认只发文本。
     *
     * ★★ **「截图永不上云」这条红线在这儿从一句注释变成一个数据结构。**
     * 运行时 fail closed:声明里出现 `image` / `screenshot` 之类就**不发**
     * (见 [ApiMath.sendsOk])。照抄 [AiAgent.teacherBodyIsClean] 的白名单思路。
     */
    val sends: List<String> = listOf(ApiMath.SENDS_TEXT),
) {
    val optional: List<String> get() = params.map { it.name }.filter { it !in required }
}

/**
 * 一只手。
 *
 * [fetchedAtMs] 是**诚实的账**:这份能力清单是「什么时候问到的」。手不在线时
 * 我们只能拿上次问到的用,而那份可能已经过期。[HandMath.staleNote] 会把这句
 * 实话写进回执 —— 脑知道自己在用一份可能过期的菜单,才好决定要不要先连一下。
 * (不写这句的代价:脑照着三个月前的菜单点菜,得到「没这个工具」,然后开始瞎试。)
 */
data class Hand(
    val id: String,
    val kind: String,
    val name: String,
    val version: String,
    val fetchedAtMs: Long,
    val tools: List<HandTool>,
    /** 电脑声明「有、但**不给文本模型看**」的工具(像素原语那类)。多数手是空的。 */
    val hidden: List<String> = emptyList(),
    /** 工具名 → 它要占的排他资源;**null = 没声明过 = 独占**(见 [locksOf])。 */
    val locks: Map<String, List<String>?> = emptyMap(),
)

/**
 * 关于手的纯逻辑。没有任何 Android 依赖,所以能在纯 JVM 上钉住。
 */
internal object HandMath {

    const val KIND_PC = "windows-pc"
    const val KIND_SELF = "android-self"
    const val KIND_CLOUD = "cloud-api"

    /**
     * 沙盒 —— 长在这部手机里的一个 Linux 用户态([Sandbox])。
     *
     * ★ 单独立一个 kind,不是并进 [KIND_SELF]:两件事的**边界**不一样。
     * 手机那只手做的都是「这台手机本身的功能」(开应用、设闹钟),每一步都要落到
     * 安卓的权限体系上;沙盒里跑的东西**碰不到那些**,它只有一个目录和一套命令。
     * 混成一个 kind 的话,`use_hand` 那层就没法按边界分道了。
     */
    const val KIND_SANDBOX = "android-sandbox"

    /**
     * ★★ 这只手在房间里该不该有**一件实物**?
     *
     * 判据是「**它是不是一件具体的东西**」,不是「它重不重要」:
     *
     * | 手 | 算不算实物 | 为什么 |
     * |---|---|---|
     * | 电脑 | **算** | 看得见的一台机器,点它进电脑那页 |
     * | 手机自己 | **算** | 握在手里的一件东西 |
     * | 云端(天气 / 地图 / 路线 / 上网搜) | **不算** | ★ **它们是能力,不是东西** |
     *
     * ★★ **算实物 ≠ 一定摆进房间**:`ConMarnActivity.pushRoomObjects` 另有两道
     *   他拍板的闸 —— 电脑(2026-10-06)、手机自己(2026-11-08,屋里那块薄板点了
     *   只会说「那是我自己…不用点它」,是死胡同)。那两道闸只管「摆」;这个判据
     *   还管着中转站「她的手」那一列,那边要照旧列出它们 —— 两边答案不一样,别并成一处。
     *
     * 用户 2026-10-05 原话:「**像天气,地图,路线这些它不是具体的外设,所以不需要
     * 单独的 AI 助手外设**」。他说得对,而且这是个**当时就已经埋好的雷** ——
     * [ConMarnActivity.pushRoomObjects] 遍历的是 [HandRegistry.all()],云端手一进来,
     * 屋里就会凭空多出一个叫「天气」的方块,点它还打不开(见 `objectTapped` 的 else 分支)。
     * 那不是「功能多」,那是**噪声**:用户房间里每一件东西**都该是能点的**。
     *
     * ★★ 写成「排除云端」而不是「列举哪些能摆」,是**故意**的:
     * 以后加实物(空调、灯、窗帘 —— 用户点名要的「房间里真的多一台空调」)
     * **默认就该摆出来**,不用回来改这一行。列举式的写法每加一件家具就要改一次,
     * 而**漏改不报错** —— 症状是「空调做了,房间里没有」,查半天。
     *
     * ★ 纯函数,能在 JVM 上钉住(见 [HandModelTest])。
     */
    fun isFixture(h: Hand): Boolean =
        h.kind != KIND_CLOUD
            // ★ 沙盒也**不摆**。判据还是同一个:它是不是一件**能拿起来的东西**。
            //   沙盒是手机里的一个目录,不是桌上的一台机器 —— 屋里凭空多出一个
            //   叫「沙盒」的方块,点它还只能说「打不开」,那就是噪声。
            && h.kind != KIND_SANDBOX

    /**
     * ★ 给 4B 看的**可照抄示例**用的样例值,按**参数名**索引(不是按工具)。
     *
     * 为什么需要它:`use_hand(hand, tool, args)` 的 `args` 是一个 **JSON 对象字符串**,
     * 而这是 4B 最可能翻车的地方 —— 给它 `{"name": "<字符串>"}` 它会**照抄尖括号**。
     * 所以示例里必须是真值(`{"name":"微信"}`),而且是这一套稳定的真值。
     *
     * 为什么按参数名而不是按工具:参数名一共就这几个,加个新工具通常只是复用;
     * 按工具索引的话每加一个工具都要回来补一行,补漏的那天示例就空了。
     *
     * ★ [sampleArgs] 对**找不到样例的参数**返回空串,而 [HandModelTest] 里有一条
     * 测试**遍历所有工具**要求必填参数都有非空样例 —— 所以真加了新参数名(比如
     * `count`),测试会红,逼人来这儿补一行。不给它一个假值糊过去。
     */
    val SAMPLES: Map<String, String> = mapOf(
        "name" to "微信",
        "title" to "记事本",
        "action" to "play_pause",
        "direction" to "down",
        "keys" to "ctrl+c",
        "text" to "你好",
        "query" to "今天天气",
        "window" to "微信",
        // 手机自己那只手用的(见 SelfHand)。★ 全是**真能跑通**的值,不是占位符 ——
        // 模型会照抄,写个 "<小时>" 它就真的会抄成 "<小时>"。
        "hour" to "7",
        "minute" to "30",
        "seconds" to "300",
        "label" to "起床",
        "url" to "https://www.bing.com",
        "page" to "wifi",
        "number" to "10086",
        "ms" to "200",
        // 系统日历（见 SelfHand.calendarAdd）。★ 日期写**相对说法**而不是某一天：
        // 样例是给模型照抄的，抄成固定的 "2026-11-08" 它就会给所有事都写那一天。
        "date" to "明天",
        "time" to "14:30",
        "minutes" to "60",
        "days" to "30",
        "note" to "带上学生证",
        // 交货（见 SelfHand.makeFile）。★ 文件名**不叫 name**：按参数名取样例，
        // 「name」那份是「微信」，模型会把它抄成文件名。content 也必须是**能直接抄的正文**，
        // 抄成一行占位符它就真会写一行占位符进去。
        "filename" to "实验报告",
        "format" to "word",
        "content" to "一、目的：测量声速。",
        // 定时任务（见 SelfHand.scheduleTask）。★ prompt 的样例是一句**完整、能独立执行**的话，
        // 不是占位符：模型会照抄这个写法，写「帮我弄一下」的那种到点就白跑。
        "prompt" to "查一下今天上海的天气，用三句话告诉我",
        "repeat" to "daily",
        "id" to "s1762500000000-1",
        // 看照片（见 SelfHand.seePhoto）。样例是一句**具体**的问题：抄成「这是什么」
        // 也能跑，但抄成占位符就白看了。
        "question" to "这张照片里是什么？",
        // 表格（见 SelfHand.openTable / tableCalc）。column 的样例必须是一个**像列名的词**，
        // 抄成「第一列」会让 colIndex 走「按序号」那条路，正好绕开我们想验的按名找列。
        "op" to "sum",
        "column" to "金额",
        // 沙盒那只手的。★ 必须是一条**真能跑通**的命令:模型会照抄,
        // 抄一条跑不起来的,它只会以为是沙盒坏了。
        "cmd" to "uname -a",
        // 通用入口那两个参数。**今天用不到**(use_hand 是模型直接调的顶层工具,
        // 不走 sampleArgs),补在这儿是为了堵一个还没爆的雷:哪天有人把
        // use_hand / click_element 挪进「手」那条路,拼出来的示例就是
        // `{"hand":""}` —— 而 4B 会忠实地照抄这个空串,然后收到一个「参数为空」的错。
        "hand" to "self",
        "tool" to "set_alarm",
        "description" to "发送",
        // 云端手那些(天气 / 地图路线)。★ 用**能直接抄的例子**当样例:
        // 「从上海站到人民广场应该怎么走」—— 这既是能直接抄的值,
        // 也顺手把「她该怎么用这些工具」示范了一遍。
        "city" to "上海",
        "address" to "上海站",
        "origin" to "上海站",
        "destination" to "人民广场",
    )

    /** 这个工具的必填参数各填什么(拿来拼示例)。 */
    fun sampleArgs(t: HandTool): Map<String, String> =
        t.required.associateWith { SAMPLES[it] ?: "" }

    /** 参数值里可能有引号(样例是我们自己的表,但别赌),所以老实转义。 */
    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(c)
        }
        return sb.toString()
    }

    /** → `{"name":"微信","window":"微信"}`;空 map → `{}`。 */
    fun argsJson(args: Map<String, String>): String =
        args.entries.joinToString(",", "{", "}") { "\"${esc(it.key)}\":\"${esc(it.value)}\"" }

    /**
     * ★ 一行**可照抄**的调用示例 —— 这是 `use_hand` 那一步唯一的兜底。
     *
     * 4B 要在四条路里挑对这条(先 list_hands → 再 use_hand → 工具名 → args 的 JSON),
     * 任何一环空着它都会自己发挥。与其在提示词里讲规则,不如回执里直接给一行能抄的。
     */
    fun callExample(hand: Hand, t: HandTool): String =
        "use_hand(hand=\"${hand.id}\", tool=\"${t.name}\", args=${argsJson(sampleArgs(t))})"

    /**
     * 「这是谁」那一小段。
     *
     * ★ 判据是「**一个包含另一个**」,不是相等。手机上那只手叫「这部手机」,种类叫
     * 「这部手机自己」—— 不相等,可拼起来就是「这部手机自己「这部手机」」,同义词
     * 说两遍。而这一行恰好是模型读得最多的(它得先认出该用哪只手),噪音留在这儿最不划算。
     *
     * 电脑那只手是 `电脑「LAPTOP-M5QBJO9M」` —— 主机名是**新信息**,得留着。
     */
    private fun titleOf(kind: String, name: String): String {
        val k = kindLabel(kind)
        return if (name.isBlank() || k.contains(name) || name.contains(k)) k else "$k「$name」"
    }

    /** 手是哪种,给人/给模型看的中文说法。 */
    fun kindLabel(kind: String): String = when (kind) {
        KIND_PC -> "电脑"
        KIND_SELF -> "这部手机自己"
        KIND_CLOUD -> "云端服务"
        KIND_SANDBOX -> "沙盒"
        else -> kind
    }

    /**
     * 模型说中文,**不会去抄 `windows-pc` 这种字符串**。这几个是它最可能说的词。
     *
     * 跟 [kindLabel] 分开是有意的:那个是「说给用户听的完整说法」,这个是
     * 「模型可能随手打的简称」。混在一起,以后想改其中一个措辞就会同时动到另一个。
     */
    fun aliasOf(kind: String): String = when (kind) {
        KIND_PC -> "电脑"
        KIND_SELF -> "手机"
        KIND_CLOUD -> "云端"
        KIND_SANDBOX -> "沙盒"
        else -> kind
    }

    /**
     * 模型给的那个 `hand=` 到底指哪只手。→ 认出来了给**规范的 id**,认不出给 null。
     *
     * ★ 这一步不能省成「字符串相等」。电脑的 id 是主机名(`LAPTOP-M5QBJO9M`),
     * 4B 抄它十有八九要变形(大小写、抄一半、写成「我那台电脑」)。严格比较的结果
     * 是**一整轮白跑**(二三十秒),而模型多半会再猜一次、再白跑一轮。
     *
     * 认得松,但**认得松不等于乱认**:最后那步「唯一前缀」只有在**只有一只**手
     * 匹配时才认。两台电脑的 id 都以 `LAPTOP` 开头的那天,它会老老实实返回 null,
     * 让回执把候选列给模型 —— **猜错手比多问一轮贵得多**(可能去点另一台电脑的屏幕)。
     */
    fun resolveId(hands: List<Hand>, given: String): String? {
        val g = given.trim()
        if (g.isEmpty()) return null
        hands.forEach { if (it.id.equals(g, true) || it.name.equals(g, true)) return it.id }
        hands.forEach {
            if (it.kind.equals(g, true) || kindLabel(it.kind).equals(g, true) ||
                aliasOf(it.kind).equals(g, true)
            ) return it.id
        }
        // 「手机」两个字单独认一次:上面那轮里 aliasOf 已经覆盖,但模型也可能只说
        // 「本机」「自己」—— 这两个词**只对手机自己成立**,不会歧义到别的种类上。
        if (g == "本机" || g == "自己" || g == "我自己") {
            hands.firstOrNull { it.kind == KIND_SELF }?.let { return it.id }
        }
        val hit = hands.filter { it.id.startsWith(g, true) || it.name.startsWith(g, true) }
        return if (hit.size == 1) hit[0].id else null
    }

    /**
     * 名字写死的那些手(今天只有手机自己)并进名单,**排在前面**。
     *
     * ★ 落盘那份里**不许**有同名的一条。手工装进去的那只会随代码升级换掉,
     * 而落盘那份是旧的 —— 两条同 id 摞在一起,`find` 拿到的会是先出现的那个,
     * 于是「代码里改了工具表,手机上还是老的」。所以并进来时顺手把盘上那份同 id 的删掉。
     */
    fun withSelf(existing: List<Hand>, self: Hand): List<Hand> =
        listOf(self) + existing.filter { it.id != self.id }

    // ---- 给模型看的两段文字(进的是工具回执 = messages,不占预热前缀) ----

    /** `set_alarm(hour, minute, label?)` —— 参数名照抄就能填。 */
    fun signature(t: HandTool): String =
        t.name + "(" + t.params.joinToString(", ") {
            if (it.name in t.required) it.name else it.name + "?"
        } + ")"

    /**
     * `list_hands()` 的正文:有哪些手、每只手会什么、参数怎么写成 JSON。
     *
     * ★ 两处刻意的设计:
     *
     * 1. **参数样例做成一张全局表**(按参数名),不是每个工具各给一个示例。
     *    参数名一共就十来个,加个新工具通常只是复用 —— 每个工具都展开示例,
     *    这段正文会长到离谱,而它是**每一轮都要带进上下文**的。
     * 2. **只列模型能用的工具**(`hidden` 里的跳过)。电脑那边已经把像素原语藏起来了,
     *    这里再挡一道:藏起来的工具出现在菜单上,模型就会去点它,然后被拒。
     */
    fun overview(hands: List<Hand>, nowMs: Long): String {
        if (hands.isEmpty()) {
            return "现在一只手都没有。那台电脑还没连上 —— 跟用户说一声,让她先连上。"
        }
        val sb = StringBuilder("你手上有 ${hands.size} 只手:\n")
        val needed = LinkedHashSet<String>()
        hands.forEachIndexed { i, h ->
            val visible = h.tools.filter { it.name !in h.hidden }
            sb.append("${i + 1}. hand=\"${h.id}\" —— ${titleOf(h.kind, h.name)},${visible.size} 个工具\n")
            visible.forEach { t ->
                sb.append("     ").append(signature(t)).append(" ").append(t.desc).append('\n')
                needed.addAll(t.required)
            }
            staleNote(h, nowMs)?.let { sb.append("     ⚠ ").append(it).append('\n') }
        }
        val table = needed.filter { !SAMPLES[it].isNullOrEmpty() }
        if (table.isNotEmpty()) {
            sb.append("\n参数**照抄**下面这些真值(别自己编,也别写尖括号):\n     ")
            sb.append(table.joinToString("  ") { "$it=${SAMPLES[it]}" })
            sb.append('\n')
        }
        // ★ 这一段以前是 `use_hand(hand="<上面的 hand>", tool="<工具名>", args={"<参数名>":"<值>"})`
        // —— 一整行的尖括号模板,而**紧挨着的**那张样例表还写着「别写尖括号」。自己打自己。
        //
        // 4B 会**照抄**它看见的东西:给了 `hand="<上面的 hand>"`,它就真会把这个字符串
        // 填进去,而 resolveId 认不出来 → 回一句认不出 → 白跑一轮(二三十秒),多半还要再猜一次。
        // [detail] 早就是「给一行真能抄的」,概览这行没跟上。
        //
        // 换成拿**真手真工具**举例:能直接抄,而且「换别的工具就把 tool 和 args 换掉」
        // 这一句把「哪部分固定、哪部分变」讲清楚了 —— 模板想教的就是这个。
        val demo = hands.asSequence()
            .flatMap { h -> h.tools.filter { it.name !in h.hidden }.map { h to it } }
            .firstOrNull { (_, t) -> t.required.all { !SAMPLES[it].isNullOrEmpty() } }
        sb.append("\n怎么用:")
        if (demo != null) {
            val (dh, dt) = demo
            sb.append("拿那只手上的 ${dt.name} 举例 —— 真值,可以直接抄:\n     ")
            sb.append(callExample(dh, dt))
            sb.append("\n换别的工具就把 tool 和 args 换成菜单上那一行的,hand 照抄它前面那个。")
        } else {
            sb.append("先 list_hands() 看有哪几只手、各会什么,再 use_hand 去调。")
        }
        sb.append('\n')
        sb.append("★ args 是一个 **JSON 对象的字符串**。只想看某只手的完整参数说明和现成例子,")
        sb.append("就调 list_hands,并把 hand 填成上面某一行那个 hand。")
        return sb.toString()
    }

    /** `list_hands(hand=...)` 的正文:那一只手展开,每个工具配一行**能照抄的**调用。 */
    fun detail(hand: Hand, nowMs: Long): String {
        val visible = hand.tools.filter { it.name !in hand.hidden }
        val sb = StringBuilder()
        sb.append("hand=\"${hand.id}\" —— ${titleOf(hand.kind, hand.name)}\n")
        staleNote(hand, nowMs)?.let { sb.append("⚠ ").append(it).append('\n') }
        if (visible.isEmpty()) {
            // ★ 「没问过」和「问了但没有」是两件事。前者是菜单还没拿到,后者是这只手
            // 真的什么都干不了 —— 说成一句会让模型一直重试一个永远不会成功的调用。
            sb.append(
                if (hand.fetchedAtMs <= 0) "这只手的工具表还没拿到(先连一下再问)。"
                else "这只手现在没报出任何可用的工具。"
            )
            return sb.toString()
        }
        sb.append("${visible.size} 个工具,照抄例子里的写法:\n")
        visible.forEach { t ->
            sb.append("\n· ").append(signature(t)).append(" —— ").append(t.desc).append('\n')
            t.params.forEach { p ->
                sb.append("    ").append(p.name)
                if (p.name !in t.required) sb.append("(可不填)")
                sb.append(": ").append(p.desc).append('\n')
            }
            sb.append("    例:").append(callExample(hand, t)).append('\n')
        }
        return sb.toString()
    }

    /**
     * 工具要占的排他资源。**没声明过 = 独占**。
     *
     * ★ 这个默认值的方向是刻意选的:新加的工具忘了写 `locks` 是**安全**的
     * (只是变成串行,慢),写错成 `[]` 才是危险的(两个任务同时抢鼠标)。
     * 所以往严的那边倒,而且不区分「键不在」和「值是 null」—— 两者都是「没说」。
     */
    fun locksOf(hand: Hand, tool: String): List<String>? = hand.locks[tool]

    /** 没声明 → 独占(用一个不可能撞上的名字表示「整台机器」)。 */
    fun exclusiveIfUndeclared(hand: Hand, tool: String): List<String> =
        locksOf(hand, tool) ?: listOf("hand:${hand.id}:exclusive")

    const val STALE_AFTER_MS = 6 * 60 * 60 * 1000L   // 6 小时

    fun isStale(hand: Hand, nowMs: Long): Boolean = nowMs - hand.fetchedAtMs > STALE_AFTER_MS

    /**
     * 何时该说这句实话:手不在线,菜单是上次问到的。
     *
     * 只在真的过期时才说 —— 天天挂着的电脑每次都说「可能过期」,模型会学会无视它,
     * 真过期那天也照样无视。**只在有用的那天开口。**
     */
    fun staleNote(hand: Hand, nowMs: Long): String? {
        if (!isStale(hand, nowMs)) return null
        val h = (nowMs - hand.fetchedAtMs) / 3600000L
        return "这份能力清单是 ${h} 小时前问到的,未必是最新的;如果调不动,先连一下再说。"
    }

    // ---- 注册表的核心:纯的、可测的那一半(落盘在 HandRegistry) ----

    /**
     * 合并一份新手的信息进来。**这里有个不能想当然的地方,单独说清楚。**
     *
     * 同一台电脑有两条路能给我们信息,信息量**差很多**:
     *
     *   - `HAND` 签名命令 → 完整自述(工具表、指纹、资源锁)
     *   - UDP 发现应答   → **只有身份**(id/kind/name,不能带工具表:那是广播,
     *                      整个网段都收得到,而且包要短)
     *
     * 所以「发现应答覆盖掉旧记录」会是**灾难**:一扫描,一台好端端会 12 个工具的
     * 电脑就被写成「会 0 个工具」的空壳。手**不是变少了,是我们在撒谎** ——
     * 脑看到一张空菜单,然后判定「这台电脑什么都干不了」。
     *
     * 规则:
     *   - 新来的**有**工具表 → 整份替换(那是刚问到的,最权威)。
     *   - 新来的**没有**工具表 → 只更新名字这类身份信息,**保留旧工具表和旧
     *     [Hand.fetchedAtMs]**。因为我们**并没有**新问到能力,说过期就说过期。
     */
    fun merged(existing: List<Hand>, incoming: Hand): List<Hand> {
        val old = find(existing, incoming.id)
        val keep = if (incoming.tools.isEmpty() && old != null && old.tools.isNotEmpty()) {
            incoming.copy(
                tools = old.tools,
                hidden = old.hidden,
                locks = old.locks,
                version = old.version,
                fetchedAtMs = old.fetchedAtMs,
            )
        } else {
            incoming
        }
        return listOf(keep) + existing.filter { it.id != incoming.id }
    }

    fun without(existing: List<Hand>, id: String): List<Hand> =
        existing.filter { it.id != id }

    /** 同名去重:同一台电脑换了 IP 也不该在列表里出现两次。 */
    fun find(existing: List<Hand>, id: String): Hand? = existing.firstOrNull { it.id == id }
}
