package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多 agent 那两块纯逻辑的测试：**路由判据**和**事件账本**。
 *
 * 为什么值得钉：这两层错了都**不崩**。
 * 路由错了的表现是「某个带号码的东西被发出去了」；账本错了的表现是
 * 「界面画出来的流程和实际做的不是一回事」—— 两个都只能靠事后反推，反推不回来。
 */
class AgentMathTest {

    // ═══════════════════════════════════════ 一、路由
    @Test
    fun `要动手的一律本地 —— 云端没有手，这条是架构不是配置`() {
        val p = AgentMath.route(sends = setOf("text"), needsTools = true, text = "算一下 1+1")
        assertEquals(AgentMath.Where.LOCAL, p.where)
        assertTrue(p.why.contains("没有手"))
    }

    @Test
    fun `工具类内容也拦 —— 就算调用方忘了传 needsTools`() {
        assertEquals(
            AgentMath.Where.LOCAL,
            AgentMath.route(setOf("tool"), needsTools = false, text = "打开微信").where,
        )
    }

    @Test
    fun `截图和声音永远不出门，也不存在脱敏后可以`() {
        for (k in listOf("screenshot", "image", "audio", "notification", "clipboard")) {
            val p = AgentMath.route(setOf(k), needsTools = false, text = "看看这一屏")
            assertEquals(k, AgentMath.Where.LOCAL, p.where)
            assertFalse(k, p.why.isBlank())
        }
    }

    @Test
    fun `文本里带手机号就整个留在本地，不是遮了再发`() {
        val p = AgentMath.route(emptySet(), needsTools = false, text = "这张单子 13812345678 怎么回事")
        assertEquals(AgentMath.Where.LOCAL, p.where)
        assertTrue(p.why.contains(SecretMath.KIND_PHONE))
        // ★ 值本身不许进那句解释 —— 解释会被画到界面上、会进日志。
        assertFalse(p.why.contains("13812345678"))
    }

    @Test
    fun `纯文本分析才允许出门`() {
        assertEquals(
            AgentMath.Where.CLOUD,
            AgentMath.route(emptySet(), needsTools = false, text = "解释一下什么是机会成本").where,
        )
    }

    @Test
    fun `空内容不占云端额度`() {
        assertEquals(AgentMath.Where.LOCAL, AgentMath.route(emptySet(), false, "   ").where)
    }

    @Test
    fun `出门那份要遮号码`() {
        val out = AgentMath.forCloud("联系人 13812345678 邮箱 a@b.com")
        assertFalse(out, out.contains("13812345678"))
        assertFalse(out, out.contains("a@b.com"))
        assertTrue(out, out.contains(SecretMath.markOf(SecretMath.KIND_PHONE)))
    }

    // ═══════════════════════════════════════ 二、事件账本
    private fun e(id: Long, text: String, ref: Long = 0, role: String = "算", from: String = AgentBusModel.CLOUD) =
        AgentEvent(id, "t1", from, role, AgentBusModel.KIND_ANSWER, ref, text, 1000L)

    @Test
    fun `换行、竖线、反斜杠都要能原样读回来`() {
        val raw = "第一行\n第二行 | 带竖线\nC:\\Users\\27014\\路径 \\n 不是换行"
        val back = AgentBusModel.parse(AgentBusModel.line(e(1, raw)))
        assertEquals(raw, back?.text)
    }

    @Test
    fun `一整条事件的每个字段都读得回来`() {
        val ev = AgentEvent(42, "t9", AgentBusModel.HUB, "中枢", AgentBusModel.KIND_ROUTE, 7, "要动手", 123L)
        assertEquals(ev, AgentBusModel.parse(AgentBusModel.line(ev)))
    }

    @Test
    fun `坏行丢一条，不许让整次读失败`() {
        assertNull(AgentBusModel.parse(""))
        assertNull(AgentBusModel.parse("这不是一行账"))
        assertNull(AgentBusModel.parse("x|t|hub|a|note|0|0|text"))
        assertNull(AgentBusModel.parse("1|t|hub|a|note|不是数字|0|text"))
        // ★ 字段数不对就是坏行：宁可少一条，也不许猜着补。
        assertNull(AgentBusModel.parse("1|t|hub|a|note|0|0"))
    }

    @Test
    fun `认不出的转义要连反斜杠一起吐出来，不吞字`() {
        val back = AgentBusModel.parse("1|t|hub|a|note|0|0|百分号\\q在这里")
        assertEquals("百分号\\q在这里", back?.text)
    }

    @Test
    fun `单行写法带编号和引用 —— 下游要能 ref 回来`() {
        val s = AgentBusModel.oneLine(e(12, "营收 3 亿", ref = 5))
        assertTrue(s, s.contains("#12"))
        assertTrue(s, s.contains("↳5"))
        assertTrue(s, s.contains("云端/算"))
        assertEquals("她", AgentBusModel.roleCn(AgentBusModel.SELF))
    }

    @Test
    fun `压给别的角色看时保留编号；超长从最老的丢，并明说丢了几条`() {
        val all = (1..20).map { e(it.toLong(), "第 $it 条事件的内容") }
        val long = AgentBusModel.render(all, maxChars = 10_000)
        assertTrue(long.contains("#20"))
        assertTrue(long.contains("#1 ["))

        val short = AgentBusModel.render(all, maxChars = 120)
        assertTrue(short, short.startsWith("（前面"))
        assertTrue(short, short.contains("#20"))
        assertFalse(short, short.contains("#1 ["))
    }

    @Test
    fun `空表压出来就是空，不是一句空话`() {
        assertEquals("", AgentBusModel.render(emptyList()))
    }

    @Test
    fun `超长的单条事件要截断，并说明截了多少`() {
        val clipped = AgentBusModel.clip("啊".repeat(AgentMath.EVENT_MAX + 500))
        assertTrue(clipped.length < AgentMath.EVENT_MAX + 40)
        assertTrue(clipped, clipped.contains("后面截掉"))
        assertEquals("短文本不动", AgentBusModel.clip("短文本不动"))
    }

    @Test
    fun `清理只丢最老的，刚发生的一定留`() {
        val lines = (1..100).map { "第 $it 条" }
        val kept = AgentBusModel.trimLines(lines, maxBytes = 200)
        assertTrue(kept.isNotEmpty())
        assertEquals("第 100 条", kept.last())
        assertTrue(kept.size < 100)
        // 留下的总字节数不许超上限（中文按 UTF-8 三字节算，这条就是那个坑）
        assertTrue(kept.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 } <= 200)
    }
}
