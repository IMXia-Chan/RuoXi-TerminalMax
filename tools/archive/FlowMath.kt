package com.example.touchpad

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 「协同大屏」那张图的**几何与运动**。纯 JVM，可单测（同 [AgentMath] / [AliasMath] 的惯例）。
 *
 * ---
 *
 * ## 这张图是什么，不是什么
 *
 * 他 2026-11-04 要的是「**作战大屏**」那个视觉：黑底、荧光绿、以调度中枢为圆心的发散线、
 * 底部一条事件流。**数据流是主语**：图上每一个移动的点都必须是 [AgentBus] 里
 * **真发生过的一条事件**，方向由它的 `from` 决定，出现时刻由它的 `at` 决定。
 * 没有事件就没有点 —— 空屏就是空屏，不拿循环动画填。
 *
 * ★ 这条约束不是洁癖。这个项目里已经有过一次教训（见 `VoiceWaveView` 文件头）：
 *   **好看的动效只要和事实对不上，它就是在撒谎**，而且比没有更糟 ——
 *   他会照着图去判断「刚刚到底有没有出门算」，图错了，判断就错了。
 *
 * ## 为什么是星形，不是任意图
 *
 * 架构上所有流动都经过中枢（它是**唯一有手**的那一层）：她说 → 中枢 → 派出去 → 回来。
 * 所以画成「外圈节点 ⇄ 圆心」的星形，图的形状本身就是架构的形状，不需要额外解释。
 *
 * ```
 *              云端
 *         ╲    │    ╱
 *   她 ────── 中枢 ────── 角色
 *         ╱    │    ╲
 *              (动手：中枢身上的一圈)
 * ```
 *
 * ## 时间用 [AgentEvent.at]（`System.currentTimeMillis`）
 *
 * 不能用 `AnimationUtils.currentAnimationTimeMillis()`（那是开机以来的毫秒数），
 * 两个时钟对不上，点会全部瞬间走完或者永远不走 —— 而且**两边都不会报错**。
 */
internal object FlowMath {

    const val KEY_SELF = "self"
    const val KEY_HUB = "hub"
    const val KEY_CLOUD = "cloud"

    /** 角色节点的 key 前缀。角色名是自由文本，直接当 key 会和上面三个撞。 */
    const val ROLE_PREFIX = "role:"

    /** 右边最多排几个角色。再多圆周就挤成一条螺旋。 */
    const val MAX_ROLES = 4

    /** 一个点从出发到抵达用多久（毫秒）。太快看不见，太慢追不上说话的速度。 */
    const val TRAVEL_MS = 900L

    /** 抵达之后节点亮多久。它让「刚刚回来的是谁」在图上留一下。 */
    const val GLOW_MS = 1_400L

    /** 图上看最近多少条。更老的事件只留下「这条路走过」的淡痕。 */
    const val SHOW_EVENTS = 24

    /** 底部事件流留几行。 */
    const val TICKER_LINES = 7

    data class Pt(val x: Float, val y: Float)

    /** 横竖两个半径。横屏时画面很宽，画成正圆的话星形只占中间三分之一。 */
    data class Radii(val x: Float, val y: Float)

    data class Node(val key: String, val label: String, val x: Float, val y: Float)

    /** 一个正在路上的点。`t` 是 0..1 的行程进度。 */
    data class Travel(val id: Long, val from: String, val to: String, val t: Float, val kind: String)

    /**
     * 圆心的位置。**在正中** —— 上下左右都要留出节点和标签的位置，
     * 偏上偏下都会让某一侧的标签撞到标题栏或事件流。
     */
    fun center(w: Float, h: Float): Pt = Pt(w * 0.5f, h * 0.50f)

    /**
     * 横/竖半径。**按比例**取，不写死 dp —— 竖屏、横屏、大屏都得成立
     * （同 `VoiceWaveView.ribbonPx` 那条理由）。
     *
     * ★ 竖半径比横半径小：节点标签画在**背离圆心**那一侧，正上方的「云端」
     *   还要再往上留出 17dp。0.30 是「标签不被标题栏吃掉」的上界。
     */
    fun radii(w: Float, h: Float): Radii = Radii(w * 0.38f, h * 0.30f)

    /**
     * 外圈每个槽位的角度（度，0 = 右，90 = 下 —— 屏幕上 y 朝下）。
     *
     * ★ 槽位是**固定的**，不按角色出现顺序重排：角色一换位置，同一个角色上一秒在右上、
     *   下一秒在右下，看图的人会以为多出来一个角色。
     *
     * 她固定在左边（读图从左往右：她说了什么 → 中枢 → 云/角色），云端固定在正上方。
     */
    fun slotAngle(slot: Int): Float = when (slot) {
        0 -> 180f          // 她
        1 -> 270f          // 云端
        else -> 315f + (slot - 2) * 45f
    }

    /** 槽位上的点。 */
    fun polar(c: Pt, r: Radii, deg: Float): Pt {
        val rad = (deg * PI / 180.0).toFloat()
        return Pt(c.x + r.x * cos(rad), c.y + r.y * sin(rad))
    }

    /**
     * 这张图上有哪些角色。**按时间顺序、去重、只留最近的 [MAX_ROLES] 个。**
     */
    fun rolesOf(events: List<AgentEvent>): List<String> {
        val seen = LinkedHashSet<String>()
        for (e in events) {
            val r = e.role.trim()
            if (r.isNotEmpty()) seen.add(r)
        }
        return seen.toList().takeLast(MAX_ROLES)
    }

    /** 圆心 + 外圈。顺序固定：她、云端、角色…… */
    fun nodes(w: Float, h: Float, roles: List<String>): List<Node> {
        val c = center(w, h)
        val r = radii(w, h)
        val out = ArrayList<Node>(3 + roles.size)
        val fixed = listOf(
            KEY_SELF to "她",
            KEY_CLOUD to "云端",
        )
        for ((i, f) in fixed.withIndex()) {
            val p = polar(c, r, slotAngle(i))
            out.add(Node(f.first, f.second, p.x, p.y))
        }
        val n = roles.size.coerceAtMost(MAX_ROLES)
        for (i in 0 until n) {
            val p = polar(c, r, slotAngle(2 + i))
            out.add(Node(ROLE_PREFIX + roles[i], roles[i], p.x, p.y))
        }
        out.add(Node(KEY_HUB, "中枢", c.x, c.y))
        return out
    }

    /**
     * 这条事件画在哪两个节点之间。
     *
     * ★ 判据的顺序是有讲究的：**先看谁发的**，再看它是什么性质。
     *   反过来（先看 kind）的话，「云端回来的一条 `answer`」会被画成「中枢发出去的」，
     *   整张图的方向就反了 —— 而方向正是这张图唯一的信息。
     */
    fun edgeOf(e: AgentEvent, roles: Set<String>): Pair<String, String> {
        val from = when (e.from) {
            AgentBusModel.SELF -> KEY_SELF
            AgentBusModel.CLOUD -> KEY_CLOUD
            // LOCAL 和 HUB 都是「这台手机上」的那一层。分成两个节点会把图切成三列，
            // 而它们之间**没有**数据流动（同一层里的两个说法而已）。
            else -> KEY_HUB
        }
        if (from == KEY_SELF) return KEY_SELF to KEY_HUB
        if (from == KEY_CLOUD) return KEY_CLOUD to KEY_HUB
        // 中枢发的：出得去的看出门，出不去的画成自己身上的一圈。
        if (e.kind == AgentBusModel.KIND_ASK || e.kind == AgentBusModel.KIND_ROUTE) {
            val r = e.role.trim()
            return if (r.isNotEmpty() && roles.contains(r)) KEY_HUB to (ROLE_PREFIX + r)
            else KEY_HUB to KEY_CLOUD
        }
        return KEY_HUB to KEY_HUB
    }

    /** 行程进度。早于窗口的算 0（还没出门），晚于窗口的算 1（已经到了）。 */
    fun progress(at: Long, now: Long, durMs: Long = TRAVEL_MS): Float {
        if (durMs <= 0L) return 1f
        return ((now - at).toFloat() / durMs).coerceIn(0f, 1f)
    }

    /** 这一条现在还在路上吗。 */
    fun isTraveling(at: Long, now: Long, durMs: Long = TRAVEL_MS): Boolean {
        val age = now - at
        return age in 0..durMs
    }

    /** 节点被点亮到什么程度（0..1）。用于「刚刚到达的是谁」。 */
    fun glowAt(at: Long, now: Long, glowMs: Long = GLOW_MS): Float {
        val age = now - at
        if (age < 0L || age > glowMs) return 0f
        return 1f - age.toFloat() / glowMs
    }

    /**
     * 一条事件该用什么颜色。★ 颜色和 `kind` 一一对应，别按角色上色 —— 角色是会变的。
     *
     * 主色是荧光绿（大屏那个基调），只在**性质变了**的时候换色：
     * 出门是青、出错是红、动手是紫。四种颜色各有各的含义，不是配色。
     */
    fun toneOf(kind: String): Int = when (kind) {
        AgentBusModel.KIND_ASK -> 0xFF5BE8E0.toInt()      // 青:出门
        AgentBusModel.KIND_ANSWER -> 0xFF35FFB0.toInt()   // 绿:回来
        AgentBusModel.KIND_TOOL -> 0xFFB07BFF.toInt()     // 紫:动手
        AgentBusModel.KIND_ROUTE -> 0xFFFFD166.toInt()    // 琥珀:判定
        AgentBusModel.KIND_ERROR -> 0xFFFF6E7A.toInt()    // 红:出错
        else -> 0xFF8FA3A0.toInt()                        // 灰绿:记一笔
    }

    /**
     * 图例。★ 它画在**图里面**，因为它解的是「这一屏上的颜色是什么意思」，
     * 不是「这个功能怎么用」—— 后者在 ⚙ 那一行的小字里（见 `showSettings`）。
     */
    val LEGEND: List<Pair<String, String>> = listOf(
        AgentBusModel.KIND_ASK to "派活",
        AgentBusModel.KIND_ANSWER to "回话",
        AgentBusModel.KIND_TOOL to "动手",
        AgentBusModel.KIND_ERROR to "出错",
    )

    fun keyLabel(key: String): String = when {
        key == KEY_SELF -> "她"
        key == KEY_HUB -> "中枢"
        key == KEY_CLOUD -> "云端"
        key.startsWith(ROLE_PREFIX) -> key.removePrefix(ROLE_PREFIX)
        else -> key
    }

    /** 底部事件流一行。等内容截到 [FEED_CHARS] —— 大屏是扫一眼的，不是读全文的。 */
    const val FEED_CHARS = 18

    fun feedLine(e: AgentEvent, roles: Set<String>): String {
        val (a, b) = edgeOf(e, roles)
        // ★ 箭头用 ASCII 的 `>`：这台机器的符号字体是精简子集（见记忆
        //   `android-symbol-fonts-are-subsetted`），花箭头有变成豆腐块的风险，而 `>` 永远在。
        val route = if (a == b) "${keyLabel(a)}·本机" else "${keyLabel(a)}>${keyLabel(b)}"
        val t = e.text.replace("\n", " ").trim()
        val body = if (t.length > FEED_CHARS) t.take(FEED_CHARS) + "…" else t
        return "#${e.id} $route ${AgentBusModel.kindCn(e.kind)} $body"
    }
}
