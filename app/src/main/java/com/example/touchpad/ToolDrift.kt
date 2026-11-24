package com.example.touchpad

/**
 * **现场**对账:电脑此刻说的工具表,和手机里抄的那份,对得上吗?
 *
 * ## 和 `eval/check_tool_drift.py` 的分工(两个都在,别以为重复)
 *
 * | | 什么时候跑 | 比的是 | 抓得到什么 |
 * |---|---|---|---|
 * | `eval/check_tool_drift.py` | 开发机上,想起来就跑 | 两个**源文件** | 改了电脑忘了改手机 |
 * | 这个 | **真机上,连上电脑就跑** | 电脑**此刻**说的 vs 手机**烧进 APK** 的那份 | 只更新了电脑、没重装 APK |
 *
 * 第二行是**只有在真机上才存在**的一种坏:APK 装在手机上、server.py 在电脑上,
 * 两边是**分开部署**的。源文件可能完全一致,而手机上跑的还是三个月前那份。
 * Python 那个守卫永远看不见这种情况 —— 它只看源文件。
 *
 * ## ★ 两边的严重度**故意不一样**,别去「统一」
 *
 * 「电脑有、手机那份没抄到」这个方向:Python 那边是**硬 FAIL**,这边是 **warn**。
 * 看着像不一致,其实是同一个事实在两个时刻的**改起来代价差一个数量级**:
 *
 *   - 在开发机上:源文件就在手边,补一行 TOOL_SCHEMA 是三十秒的事 → 该拦。
 *   - 在真机上:已经烧进 APK、装到手机上了,下次重装才生效 → 拦也没用,
 *     只会每次连接都刷几行不会坏的告警,而**总在尖叫的检查会教会人无视它**。
 *
 * 而且这个方向在真机上**确实不算坏**:[PHONE_LOCAL] 里那两个入口让模型
 * 照样够得着 —— 只是每用一次多绕一轮对话。所以这边叫 warn 是诚实的,
 * 那边叫 FAIL 也是诚实的。**两边都写着这段注释,是为了让它保持不一致。**
 *
 * ## 只说,不改
 *
 * 这里**一个字节都不改**工具表。自动「对齐」等于让一台机器的猜想悄悄改掉另一台
 * 的行为,出了事没人找得到是谁改的。它只往 logcat 大声说,人去改。
 *
 * ## 纯逻辑,没有 org.json
 *
 * 抠形状(读 [AiAgent] 里的 TOOL_SCHEMA)那步在 AiAgent 里;这里只吃两个
 * [Shape],好让 [ToolDriftTest] 在纯 JVM 上把判定规则钉住。
 */
internal object ToolDrift {

    /**
     * ★ **手机自己实现的**工具:电脑那份里本来就没有,不是漂移。
     *
     * ⚠️ **这份清单在 `eval/check_tool_drift.py` 的 `PHONE_LOCAL` 里还有一份。**
     * 那边比源文件、这边比真机,两份都要有,改一处务必改另一处。
     * (想只留一处的话,得让 Kotlin 去读那个 py 文件——为一个三行的集合不值得。)
     *
     *   - `click_element`:用视觉模型在**手机屏幕上**找像素再点。「眼」在手机上。
     *   - `list_hands` / `use_hand`:通用入口本身(见 AiAgent 里那一段注释)。
     *     它们不是「电脑会的一件事」,是**去哪找手**的机制 —— 电脑那边当然没有,
     *     有才奇怪。它们指到的那些工具名才是电脑的,而那些由**电脑自己报**上来
     *     (`HAND`/`HARR`),比一张烧死在 APK 里的表准。
     */
    /**
     * ★★ 2026-11-06 补齐：原来这里只有三个，而 `TOOL_SCHEMA` 里另有 `read_url`、
     *   7 个 `skill_*`、`delegate` 共 9 个**手机自己实现**的工具没列进来。
     *   后果是**每次连上电脑的对账都报一串假警报**（「模型会去调这些工具，而电脑根本不认」），
     *   而假警报读三次之后就没人看了 —— 真坏的那天也照样没人看。
     *
     *   判据：**电脑那份 `TOOLS` 里没有、而手机 `dispatch` 里有分支的**，都该在这里。
     */
    val PHONE_LOCAL: Set<String> = setOf(
        "click_element", "list_hands", "use_hand",
        // 手机上自己抓网页（见 AiAgent.readWeb），电脑那边没有这个工具。
        "read_url",
        // 分头派活，是手机侧编排层的事。
        "delegate",
        // 技能就是存在手机里的，电脑不知道什么叫「技能」。
        "skill_list", "skill_save", "skill_new", "skill_delete",
        "skill_note", "skill_ledger", "skill_run",
    )

    /** 一份工具表的形状:**只要名字和参数**。描述文字两边**故意不同**(手机那份为 4B 调过),所以永远不比。 */
    data class Shape(
        val params: Map<String, Set<String>>,
        val required: Map<String, Set<String>>,
    ) {
        val names: Set<String> get() = params.keys
    }

    /**
     * 对账结果。
     *
     * ★ [bad] 和 [warn] 分开了,这个分法是有讲究的:
     *
     *   - [bad] = **一定会坏**。电脑要求的参数手机根本没告诉模型 → 模型永远填不出,
     *     调了必失败;或者名字集合对不上 → 模型叫一个电脑不认识的名字。
     *   - [warn] = **只是少块能力**。电脑支持一个可选参数、手机没告诉模型。
     *     这是**常态而且往往是有意的**(见 Python 那边 `PC_ONLY_PARAMS` 的理由)。
     *
     * 混成一种是查得最勤的反模式:报告里天天有几条不会坏的东西,人就开始
     * 整个无视它 —— 真坏的那天也照样无视。**只在该叫的时候叫。**
     */
    data class Report(val bad: List<String>, val warn: List<String>) {
        val ok: Boolean get() = bad.isEmpty()
    }

    fun compare(phone: Shape, pc: Shape): Report {
        val bad = ArrayList<String>()
        val warn = ArrayList<String>()

        // ---- 1. 名字集合 ----
        // 手机那份减掉手机独有的,应该**正好**等于电脑那份。
        val phoneNames = phone.names - PHONE_LOCAL
        val lost = (phoneNames - pc.names).sorted()    // 手机告诉模型了,电脑不认
        val missed = (pc.names - phoneNames).sorted()  // 电脑会,但模型永远不知道
        if (lost.isNotEmpty()) {
            bad.add("模型会去调这些工具,而电脑根本不认: $lost " +
                    "(电脑要么删了它们,要么名字改了;手机那份 TOOL_SCHEMA 是旧的)")
        }
        if (missed.isNotEmpty()) {
            // ★ 2026-10-04 从 bad 降成 warn —— 因为「永远不知道」这句话不再成立了。
            //
            // 通用入口进来之前,电脑多一个工具而手机没抄到 = 模型**永远够不着它**,
            // 一个字都不会提,那就是能力静默少一块,够得上「会坏」。
            // 现在 `list_hands` 是**当场问电脑**要的菜单(`HAND`/`HARR`),
            // 新工具它自己就会出现在上面,模型照样能用 `use_hand` 调到。
            //
            // 那还报什么?因为**这条路明显更差**:每一步都要先 list_hands 再看一眼菜单,
            // 多一轮(二三十秒),而且 4B 得先想清楚该走这条阶梯。所以该提醒人去补
            // TOOL_SCHEMA —— 但把它叫成「会坏」是在撒谎,而撒谎的检查会被无视。
            warn.add("电脑有这些工具,手机那份 TOOL_SCHEMA 里还没有: $missed " +
                    "(模型还是能通过 list_hands + use_hand 够到它们,不算坏;" +
                    "但每用一次都要多绕一轮对话,建议补进 AiAgent.kt 的 TOOL_SCHEMA)")
        }

        // ---- 2. 逐个工具的参数 ----
        for (name in (phoneNames intersect pc.names).sorted()) {
            val pp = phone.params[name] ?: emptySet()
            val cp = pc.params[name] ?: emptySet()
            val pr = phone.required[name] ?: emptySet()
            val cr = pc.required[name] ?: emptySet()

            // (a) 必填必须**两边一模一样**。这个方向一条都不许放过:
            //     电脑必填而手机没声明 → 模型永远不填 → 那一步必失败;
            //     手机必填而电脑没这个参数 → 模型白填一个,电脑要么忽略要么报错。
            if (pr != cr) {
                bad.add("$name 的必填参数对不上:电脑 ${cr.sorted()} / 手机 ${pr.sorted()}")
            }
            // (b) 手机不许编一个电脑不认的参数(模型会照着填,然后被无视)。
            val invented = (pp - cp).sorted()
            if (invented.isNotEmpty()) {
                bad.add("$name 里这些参数电脑不认,模型却会被教着去填: $invented")
            }
            // (c) 电脑有、手机没给模型看的可选参数 —— **只提醒**。
            val hidden = (cp - pp - cr).sorted()
            if (hidden.isNotEmpty()) {
                warn.add("$name 的可选参数 $hidden 没给模型看(通常是有意的;" +
                        "想放出来就改 AiAgent.kt 的 TOOL_SCHEMA,并去 " +
                        "eval/check_tool_drift.py 的 PC_ONLY_PARAMS 里删掉对应豁免)")
            }
        }

        // ---- 3. 参数有样例可照抄吗 ----
        //
        // 严格说这不算「两张表不一致」,是**手机这边自己缺东西**。放这儿是因为
        // 它和上面两条由**同一个时刻**暴露:电脑新加了一个参数名,我们此刻才知道。
        //
        // 为什么够得上「要报」:`use_hand` 的 args 是让 4B 自己拼的 JSON,而它唯一能
        // 照抄的就是回执里那句示例。示例没给出真值(拼成 `{"count":""}`)比不给更坏 ——
        // 4B 会忠实地把空串填进去,然后得到一个「参数为空」的错。
        for (name in pc.names.sorted()) {
            val blank = (pc.required[name] ?: emptySet())
                .filter { (HandMath.SAMPLES[it] ?: "").isBlank() }
                .sorted()
            if (blank.isNotEmpty()) {
                warn.add("$name 的必填参数 $blank 没有样例值 —— 回执里那句「可照抄的示例」" +
                        "会变成空串,而 4B 会照抄。去 HandMath.SAMPLES 里补一行")
            }
        }
        return Report(bad, warn)
    }

    /** 给 logcat 的一行行。分开写是因为 [Report.bad] 要显眼,[Report.warn] 只要看得见。 */
    fun lines(r: Report): List<String> {
        val out = ArrayList<String>()
        for (b in r.bad) out.add("★ 工具表漂移(会坏):$b")
        for (w in r.warn) out.add("工具表差异(只是少块能力):$w")
        return out
    }
}
