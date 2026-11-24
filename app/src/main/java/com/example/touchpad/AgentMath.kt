package com.example.touchpad

/**
 * 多 agent 协同的**纯逻辑**：一个子任务该在哪儿算，以及它能不能出门。
 *
 * 没有 Android、没有网络、没有 org.json —— 理由同 [HandModel]/[SkillMath]：
 * 这一层错了**不崩**，表现是「某个本来该本地算的东西被发出去了」，
 * 而那件事发生过一次就收不回来。
 *
 * ---
 *
 * ## 架构（用户 2026-11-04 定的）
 *
 * ```
 *  本地 4B 中枢 ──拆任务、定判据、**唯一有手**──┐
 *        │                                      │
 *        ├─ 能出门的子任务 ──→ 云端算力（无手，只回文本）
 *        │                         │
 *        └←──── 结果当**数据**收回来 ┘
 * ```
 *
 * **云永远没有手。** 算力拓展扩的是脑，不是权限：所有工具调用、所有写入、
 * 所有对外发送，一律由本地中枢发起。这一条不是策略，是架构 ——
 * 想让它变，得改这个文件。
 *
 * ## 路由判据：从「能不能发」反推「该在哪算」
 *
 * 不是「先决定在哪算，再检查能不能发」。顺序反了就会出这种事：
 * 已经排进云端队列，才发现里面有身份证号，于是要么发出去要么临时改道。
 * 这里一律**先判内容，再定位置**。
 *
 * 判据是 fail-closed 的：认不出来就当不能发。
 */
internal object AgentMath {

    enum class Where {
        /** 本机算。默认落点 —— 云端是例外，不是默认。 */
        LOCAL,

        /** 脱敏后可以出门算。 */
        CLOUD,
    }

    /**
     * 一个子任务的路由结论。
     *
     * [why] 是给人看的一句话，事件流和界面直接显示它 ——
     * 「为什么这条没上云」比「它上没上云」更需要被看见。
     */
    data class Plan(val where: Where, val why: String) {
        val cloudy: Boolean get() = where == Where.CLOUD
    }

    /**
     * 这几类内容**永远不出门**，也不存在「脱敏后可以」的说法。
     *
     * - 截图 / 图像：一分钟里能做多少事，图上一目了然；而且是他的房间、他的屏幕。
     * - 声音：他的声音本身就不上传（这是他自己定的红线）。
     * - 通知正文：别人的话，他没有替别人同意的权利。
     * - 剪贴板 / 联系人：同上，来源不是他本人。
     */
    private val NEVER_SEND = setOf(
        "image", "screenshot", "audio", "voice", "notification", "clipboard", "contact",
    )

    /** 手能做的事只有本地能做 —— 云端没有手，这一条是架构，不是配置。 */
    private val TOOL_KINDS = setOf("tool", "act", "click", "type", "exec")

    /**
     * 定这个子任务在哪算。
     *
     * ★ 这里判「有号码 → 本地」，和对话兜底那条（`AiAgent.cloudChat` 里遮掉再发）
     *   **不是一回事**，别哪天把它俩「统一」掉：
     *   - 这里判的是**派活** —— 把一个子任务派出去算。这一趟本来就可有可无，
     *     留在本地最省事，少一次赌。
     *   - 对话兜底是**他亲手打开的开关**：本地不吭声时，这轮对话只有云端能接。
     *     那一份「遮掉号码照发」—— 号码不出门，话不断。
     *   两条路的目标不一样：一条是「别多送」，一条是「别断话」。
     *
     * @param sends 这个子任务要带出去的内容类别（和 [HandTool.sends] 同一套词）。
     * @param needsTools 它要不要动手（调工具、写盘、发消息）。
     * @param text 真正要发的那段文本 —— 用来找敏感号码。**传没遮过的那份**。
     */
    fun route(sends: Set<String>, needsTools: Boolean, text: String): Plan = when {
        needsTools || sends.any { it in TOOL_KINDS } ->
            Plan(Where.LOCAL, "这一步要动手，云端没有手")

        sends.any { it in NEVER_SEND } -> {
            val what = sends.filter { it in NEVER_SEND }.joinToString("、")
            Plan(Where.LOCAL, "带着$what，这类内容不出门")
        }

        // ★ 有敏感号码就整个留在本地，**不是**「遮了再发」。
        //   遮过的那份确实安全，但遮的时候得先认出它 —— 认错一个就是发出去一个。
        //   这一步本来就不必上云，少一次赌。
        SecretMath.foundKinds(text).isNotEmpty() ->
            Plan(
                Where.LOCAL,
                "里面有" + SecretMath.foundKinds(text).joinToString("、") + "，留在本地算",
            )

        text.isBlank() -> Plan(Where.LOCAL, "没内容要算")

        else -> Plan(Where.CLOUD, "纯文本分析，可以出门")
    }

    /**
     * 出门前的那一道：把号码换成标记。
     *
     * ★ 它在 [route] 之后 —— 走得到这里就说明这段文本刚才没查出敏感项，
     *   再遮一遍是**防两次检查之间内容被拼进来**（比如调用的上一段结果里带了号码）。
     *
     * @param alias 传了就换成**假名**（同一个值永远同一个词，见 [AliasMath]）；
     *              不传就退成老样子 —— 一律换成 `[手机号]` 这种类别标记。
     *              ★ 两条路的**判据是同一段代码**（[SecretMath.replaceEach]），
     *              所以「哪一档漏了」这个问题不存在：要漏两档一起漏。
     */
    fun forCloud(
        text: String,
        alias: ((kind: String, value: String) -> String)? = null,
    ): String =
        if (alias == null) SecretMath.maskOutgoing(text)
        else SecretMath.replaceEach(text) { kind, value -> AliasMath.wrap(alias(kind, value)) }

    /**
     * 云端回来的那一道：把假名换回真值。
     *
     * ★ 这是「只有本地能解密」的字面落点 —— 换得回来，因为表在这台手机上。
     * ★ 查不到的假名**原样留着**（见 [AliasMath.restore]）：它可能是云端自己编的，
     *   留着才能看出「它提到了一个我不认识的东西」，删掉就是替它圆谎。
     */
    fun fromCloud(text: String, lookup: (alias: String) -> String?): String =
        AliasMath.restore(text, lookup)

    /** 一条事件最多留多少字。事件流是给人看脉络的，不是全文库。 */
    const val EVENT_MAX = 2_000
}
