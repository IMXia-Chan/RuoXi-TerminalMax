package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「手」的纯逻辑测试 —— 纯 JVM,不碰 org.json / Android。
 *
 * 为什么值得钉:这一层错了**不崩**。它错的表现是「她照着一份空菜单说这台电脑
 * 什么都干不了」「她照着示例填了个空串」「她以为这份三个月前的能力清单是新的」——
 * 全都是**静默**的,真机上只能靠结果反推。
 *
 * 落盘那半(HandRegistry / HandCodec)碰 Context 和 org.json,项目惯例是不做单测;
 * 这里测的是它调用的那半纯逻辑。
 */
class HandModelTest {

    private fun tool(
        name: String,
        params: List<String>,
        required: List<String> = emptyList(),
    ) = HandTool(
        name = name,
        desc = "d",
        params = params.map { HandParam(it, "$it 的说明") },
        required = required,
    )

    private fun hand(
        id: String = "PC-A",
        kind: String = HandMath.KIND_PC,
        tools: List<HandTool> = listOf(tool("open_app", listOf("name"), listOf("name"))),
        locks: Map<String, List<String>?> = mapOf("open_app" to listOf("pc.foreground")),
        fetchedAtMs: Long = 1_000_000L,
    ) = Hand(
        id = id, kind = kind, name = id, version = "fp1",
        fetchedAtMs = fetchedAtMs, tools = tools, hidden = emptyList(), locks = locks,
    )

    // ---- 可照抄的示例:4B 唯一能抄的东西 ----

    @Test
    fun `示例填的是真值不是尖括号`() {
        val t = tool("open_app", listOf("name"), listOf("name"))
        val ex = HandMath.callExample(hand(), t)
        assertEquals("use_hand(hand=\"PC-A\", tool=\"open_app\", args={\"name\":\"微信\"})", ex)
        // ★ 这是整条链上最要紧的一句话:4B 会**照抄**它看见的东西。
        // 给它 `<字符串>` 它就填尖括号;给它空串它就填空串。
        assertFalse(ex.contains("<"))
        assertFalse(ex.contains("\"\""))
    }

    @Test
    fun `只给必填参数样例,可选的不填`() {
        val t = tool("scroll", listOf("direction", "amount"), listOf("direction"))
        val ex = HandMath.callExample(hand(), t)
        assertTrue(ex.contains("\"direction\":\"down\""))
        assertFalse(ex.contains("amount"))
    }

    @Test
    fun `没有样例的参数会露出空串 —— 这正是要能被测出来的那种坏`() {
        val t = tool("weird", listOf("count"), listOf("count"))
        assertEquals(mapOf("count" to ""), HandMath.sampleArgs(t))
    }

    @Test
    fun `样例值里的引号会转义(不然拼出来是坏 JSON)`() {
        val t = tool("say", listOf("text"), listOf("text"))
        // 直接构造一个带引号的样例来看拼接
        val j = HandMath.argsJson(mapOf("text" to "他说\"你好\""))
        assertEquals("{\"text\":\"他说\\\"你好\\\"\"}", j)
    }

    @Test
    fun `空参数拼成空对象而不是空串`() {
        assertEquals("{}", HandMath.argsJson(emptyMap()))
    }

    @Test
    fun `工具无必填参数时示例是空对象`() {
        val ex = HandMath.callExample(hand(), tool("get_state", emptyList()))
        assertEquals("use_hand(hand=\"PC-A\", tool=\"get_state\", args={})", ex)
    }

    // ---- 资源锁:没声明 = 独占 ----

    @Test
    fun `声明了空列表就是真的不占`() {
        val h = hand(locks = mapOf("list_windows" to emptyList()))
        assertEquals(emptyList<String>(), HandMath.locksOf(h, "list_windows"))
        assertEquals(emptyList<String>(), HandMath.exclusiveIfUndeclared(h, "list_windows"))
    }

    @Test
    fun `没声明过 = 独占(往严的那边倒)`() {
        val h = hand(locks = mapOf("open_app" to listOf("pc.foreground")))
        // 键不存在
        assertNull(HandMath.locksOf(h, "brand_new_tool"))
        // 值是 null(电脑那边没写 locks)也是同一个意思
        val h2 = hand(locks = mapOf("open_app" to null))
        assertNull(HandMath.locksOf(h2, "open_app"))
        // 两者都退化成「独占」,而不是「不占」
        assertTrue(HandMath.exclusiveIfUndeclared(h, "brand_new_tool").isNotEmpty())
        assertTrue(HandMath.exclusiveIfUndeclared(h2, "open_app").isNotEmpty())
    }

    @Test
    fun `两只不同的手,独占的假资源名不会撞上`() {
        val a = HandMath.exclusiveIfUndeclared(hand(id = "A"), "x")
        val b = HandMath.exclusiveIfUndeclared(hand(id = "B"), "x")
        assertTrue(a.intersect(b.toSet()).isEmpty())
    }

    // ---- 合并:★ 发现应答不许把工具表擦掉 ----

    @Test
    fun `新手的信息按 id 换掉旧的`() {
        val old = listOf(hand(id = "A"), hand(id = "B"))
        val merged = HandMath.merged(old, hand(id = "A", tools = emptyList()))
        assertEquals(listOf("A", "B"), merged.map { it.id })
        assertEquals(2, merged.size)
    }

    @Test
    fun `最近拿到的那只排最前`() {
        val old = listOf(hand(id = "A"), hand(id = "B"))
        val merged = HandMath.merged(old, hand(id = "B"))
        assertEquals(listOf("B", "A"), merged.map { it.id })
    }

    @Test
    fun `完整自述会整份替换(那是刚问到的,最权威)`() {
        val old = listOf(hand(tools = listOf(tool("open_app", listOf("name"), listOf("name")))))
        val incoming = hand(
            tools = listOf(tool("open_app", listOf("name"), listOf("name")),
                           tool("type", listOf("text"), listOf("text"))),
            locks = mapOf("open_app" to listOf("pc.foreground"), "type" to listOf("pc.keyboard")),
        )
        val after = HandMath.merged(old, incoming).first()
        assertEquals(2, after.tools.size)
        assertEquals("fp1", after.version)
    }

    @Test
    fun `★ 发现应答(没工具表)不许把工具表擦成空的`() {
        val rich = hand(
            tools = listOf(tool("open_app", listOf("name"), listOf("name")),
                           tool("type", listOf("text"), listOf("text"))),
            locks = mapOf("open_app" to listOf("pc.foreground"), "type" to listOf("pc.keyboard")),
            fetchedAtMs = 1_000L,
        )
        // 扫描到的只有身份:tools 为空、version 为空
        val bare = Hand(id = "PC-A", kind = HandMath.KIND_PC, name = "改了个名字",
                        version = "", fetchedAtMs = 9_999_999L, tools = emptyList())
        val after = HandMath.merged(listOf(rich), bare).first()
        // 名字跟着更新(那是新信息)
        assertEquals("改了个名字", after.name)
        // 但工具表和资源锁必须原样留着 —— 我们**并没有**新问到能力
        assertEquals(2, after.tools.size)
        assertEquals(2, after.locks.size)
        assertEquals("fp1", after.version)
        // 而「什么时候问到的」也不能假装刚问过,否则过期告警永远不会响
        assertEquals(1_000L, after.fetchedAtMs)
    }

    @Test
    fun `★ 全新的一只手就算只有身份也照样登记`() {
        // 旧记录里根本没有它 → 没什么可保留的,如实记下「我们只知道它是谁」
        val bare = Hand(id = "PC-NEW", kind = HandMath.KIND_PC, name = "书房那台",
                        version = "", fetchedAtMs = 5L, tools = emptyList())
        val after = HandMath.merged(listOf(hand(id = "PC-A")), bare)
        assertEquals(listOf("PC-NEW", "PC-A"), after.map { it.id })
        assertTrue(HandMath.find(after, "PC-NEW")!!.tools.isEmpty())
    }

    @Test
    fun `移除一只`() {
        val after = HandMath.without(listOf(hand(id = "A"), hand(id = "B")), "A")
        assertEquals(listOf("B"), after.map { it.id })
    }

    // ---- 过期:那份菜单是三个月前的 ----

    @Test
    fun `刚问到的不啰嗦`() {
        assertNull(HandMath.staleNote(hand(fetchedAtMs = 1_000_000L), 1_000_000L + 60_000L))
        assertFalse(HandMath.isStale(hand(fetchedAtMs = 1_000_000L), 1_000_000L + 60_000L))
    }

    @Test
    fun `过期了要说实话,而且带上多久`() {
        val t = 1_000_000L
        val h = hand(fetchedAtMs = t)
        val now = t + 8 * 3600_000L      // 8 小时
        assertTrue(HandMath.isStale(h, now))
        val note = HandMath.staleNote(h, now)!!
        assertTrue(note.contains("8 小时"))
        assertTrue(note.contains("先连一下"))
    }

    // ---- 种类标签 ----

    @Test
    fun `手的种类有中文说法,不认识的照原样`() {
        assertEquals("电脑", HandMath.kindLabel(HandMath.KIND_PC))
        assertEquals("这部手机自己", HandMath.kindLabel(HandMath.KIND_SELF))
        assertEquals("云端服务", HandMath.kindLabel(HandMath.KIND_CLOUD))
        assertEquals("sensor:温度计", HandMath.kindLabel("sensor:温度计"))
    }

    // ---- SAMPLES 自己不许退化成空 ----

    @Test
    fun `样例表里没有空值`() {
        for ((k, v) in HandMath.SAMPLES) {
            assertTrue("样例 `$k` 是空串,拿去拼示例就是把空串喂给 4B", v.isNotBlank())
        }
    }

    // ---- 通用入口:认手 / 并名单 / 给模型看的那两段字 ----

    private val selfHand = SelfHand.hand(500_000L)

    @Test
    fun `hand 认得出来 —— id、名字、种类、中文简称`() {
        val hands = listOf(hand(id = "LAPTOP-X", kind = HandMath.KIND_PC), selfHand)
        assertEquals("LAPTOP-X", HandMath.resolveId(hands, "LAPTOP-X"))
        assertEquals("LAPTOP-X", HandMath.resolveId(hands, "laptop-x"))
        assertEquals("LAPTOP-X", HandMath.resolveId(hands, HandMath.KIND_PC))
        assertEquals("LAPTOP-X", HandMath.resolveId(hands, "电脑"))
        assertEquals("self", HandMath.resolveId(hands, "self"))
        assertEquals("self", HandMath.resolveId(hands, "手机"))
        assertEquals("self", HandMath.resolveId(hands, "本机"))
    }

    @Test
    fun `★ 抄了一半的主机名也认得,但只在只可能是一只的时候`() {
        // 电脑的 id 是主机名,4B 抄它十有八九要变形 —— 严格比较的代价是一整轮白跑。
        val one = listOf(hand(id = "LAPTOP-M5QBJO9M", kind = HandMath.KIND_PC), selfHand)
        assertEquals("LAPTOP-M5QBJO9M", HandMath.resolveId(one, "LAPTOP-M5Q"))
        // 两台都以 LAPTOP 开头时:分不出来就分不出来,别赌(赌错 = 去点另一台电脑的屏幕)。
        val two = listOf(
            hand(id = "LAPTOP-A", kind = HandMath.KIND_PC),
            hand(id = "LAPTOP-B", kind = HandMath.KIND_PC),
        )
        assertNull(HandMath.resolveId(two, "LAPTOP"))
    }

    @Test
    fun `认不出来的 hand 给 null,不是硬凑一个`() {
        val hands = listOf(hand(id = "PC-A"), selfHand)
        assertNull(HandMath.resolveId(hands, "我那台台式机"))
        assertNull(HandMath.resolveId(hands, ""))
        assertNull(HandMath.resolveId(hands, "   "))
        assertNull(HandMath.resolveId(emptyList(), "self"))
    }

    @Test
    fun `★ 并进来的自述手排在前面,而且盘上那份同 id 的被挤掉`() {
        // 盘上那份是旧的。两条同 id 摞在一起,find 拿到先出现的那个,
        // 于是「代码里改了工具表,手机上还是老的」—— 而且不报任何错。
        val stale = hand(id = "self", kind = HandMath.KIND_SELF, tools = emptyList())
        val out = HandMath.withSelf(listOf(stale, hand(id = "PC-A")), selfHand)
        assertEquals(2, out.size)
        assertEquals("self", out[0].id)
        assertEquals("PC-A", out[1].id)
        assertEquals(SelfHand.tools.size, out[0].tools.size)
    }

    @Test
    fun `签名带上可选标记`() {
        assertEquals("set_alarm(hour, minute, label?)", HandMath.signature(
            tool("set_alarm", listOf("hour", "minute", "label"), listOf("hour", "minute"))
        ))
        assertEquals("get_state()", HandMath.signature(tool("get_state", emptyList())))
    }

    @Test
    fun `★ 概览里有每一只手、每个工具,还有那张参数样例表`() {
        val hands = listOf(selfHand, hand(id = "PC-A"))
        val s = HandMath.overview(hands, 500_000L)
        assertTrue(s.contains("self"))
        assertTrue(s.contains("PC-A"))
        assertTrue("得说清一共几只手", s.contains("2 只手"))
        assertTrue("手机那只手的工具要露出来", s.contains("set_alarm(hour, minute, label?)"))
        // 参数样例表:模型照抄的就是这里
        assertTrue(s.contains("hour=7"))
        assertTrue(s.contains("minute=30"))
        assertTrue("要教会它 args 是 JSON 字符串", s.contains("use_hand(hand="))
        // ★ 概览里**一个尖括号都不许有** —— 和 detail 那条同一个理由。
        // 这条以前是漏的:概览曾经写着 `hand="<上面的 hand>"`,而它上面两行
        // 正是那张样例表,表头还写着「别自己编,也别写尖括号」。自己打自己,
        // 而 4B 会照抄 —— 填了那个字符串就认不出来,白跑一轮。
        assertFalse("概览是模型第一步看到的东西,不许出现尖括号占位符: $s", s.contains("<"))
        assertFalse("也不许出现「那只手的 hand」这种可照抄的假值", s.contains("\"那只手的 hand\""))
    }

    @Test
    fun `★ 概览不列被藏起来的工具`() {
        // 藏起来的工具出现在菜单上,模型就会去点它,然后被拒 —— 白跑一轮。
        val h = hand(id = "PC-A").copy(hidden = listOf("open_app"))
        val s = HandMath.overview(listOf(h), 500_000L)
        assertFalse("open_app 被藏了就不该出现在菜单上", s.contains("open_app"))
    }

    @Test
    fun `概览在一只手都没有的时候说实话`() {
        val s = HandMath.overview(emptyList(), 0L)
        assertTrue(s.contains("一只手都没有"))
        assertTrue("得告诉它这是没连上,不是没这个功能", s.contains("连上"))
    }

    @Test
    fun `★ 展开某只手时带上参数说明和能照抄的例子`() {
        val s = HandMath.detail(selfHand, 500_000L)
        assertTrue(s.contains("set_alarm(hour, minute, label?)"))
        assertTrue(s.contains("几点"))
        assertTrue("可选参数要标出来", s.contains("label(可不填)"))
        assertTrue("例子必须是真值,不是尖括号",
            s.contains("""use_hand(hand="self", tool="set_alarm", args={"hour":"7","minute":"30"})"""))
        assertFalse("例子里的值不许留尖括号占位符", s.contains("<"))
    }

    @Test
    fun `★ 「没问过」和「问了但没有」要分开说`() {
        // 说成一句的话,模型会一直重试一个永远不会成功的调用。
        val neverAsked = hand(id = "PC-A", tools = emptyList()).copy(fetchedAtMs = 0L)
        assertTrue(HandMath.detail(neverAsked, 0L).contains("还没拿到"))
        val askedButEmpty = hand(id = "PC-A", tools = emptyList()).copy(fetchedAtMs = 1L)
        assertTrue(HandMath.detail(askedButEmpty, 0L).contains("没报出任何"))
    }

    @Test
    fun `过期的菜单会被明说出来`() {
        val old = hand(id = "PC-A", fetchedAtMs = 0L)
        val s = HandMath.detail(old, HandMath.STALE_AFTER_MS + 1)
        assertTrue(s.contains("小时前问到的"))
    }

    // ---- 房间里摆什么(外设 vs 能力) ----

    @Test
    fun `实物摆进房间,云端那些能力不摆`() {
        // ★★ 用户 2026-10-05 的原话:「像天气,地图,路线这些它不是具体的外设,
        //    所以不需要单独的 AI 助手外设」。
        //
        //    这条钉的是一个**已经埋好的雷**:`pushRoomObjects` 遍历的是
        //    `HandRegistry.all()` 全摆。云端手接进来之前那个循环是对的,
        //    接进来之后它就变成错的 —— 而且**不报错**,
        //    症状只是「她屋里凭空多了几个点不开的方块」。
        assertTrue(HandMath.isFixture(hand(kind = HandMath.KIND_PC)))
        assertTrue(HandMath.isFixture(hand(kind = HandMath.KIND_SELF)))
        assertFalse(HandMath.isFixture(hand(kind = HandMath.KIND_CLOUD)))
    }

    @Test
    fun `今天预置的三只云端手,一只都不许摆进房间`() {
        // 用**真的**那三只的 id 与 kind 各来一遍 —— 上面那条测的是种类,
        // 这条测的是「我们实际会造出来的那几只」。两者都过才说明真机上是干净的。
        for (id in listOf("api:天气", "api:地图与路线", "api:网上查")) {
            val h = hand(id = id, kind = HandMath.KIND_CLOUD)
            assertFalse("$id 不该摆在房间里", HandMath.isFixture(h))
        }
    }

    @Test
    fun `以后新加的实物种类默认就能摆 —— 漏改不报错的那种坏要提前堵掉`() {
        // ★ 判据写成「排除云端」而不是「列举电脑和手机」,就是为了这一条:
        //   以后加空调、灯、窗帘(用户点名要的「房间里真的多一台空调」),
        //   它们带一种**今天还不存在**的 kind,必须**默认摆得出来**。
        //   写成列举式的话,新家具会静默消失 —— 而那是这个项目吃过一次的病
        //   (「工具表抄三遍」:加一个能力要改三处,漏一处不报错)。
        assertTrue(HandMath.isFixture(hand(id = "home:ac", kind = "home-appliance")))
        assertTrue(HandMath.isFixture(hand(id = "home:light", kind = "home-appliance")))
    }
}
