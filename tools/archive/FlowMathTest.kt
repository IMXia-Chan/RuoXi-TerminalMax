package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 大屏那张图的几何。**测的是「画出来的东西和事件对不对得上」**，
 * 不是像素 —— 像素只在真机上看（见 sandbox 自检）。
 */
class FlowMathTest {

    private fun ev(
        id: Long,
        from: String,
        kind: String,
        role: String = "",
        text: String = "x",
        at: Long = 0L,
    ) = AgentEvent(id = id, task = "t1", from = from, role = role, kind = kind, ref = 0, text = text, at = at)

    @Test
    fun `槽位固定 —— 同一个角色不会换位置`() {
        val a = (0 until 6).map { FlowMath.slotAngle(it) }
        assertEquals(a, (0 until 6).map { FlowMath.slotAngle(it) })
        assertEquals(180f, a[0])   // 她在左
        assertEquals(270f, a[1])   // 云端在上
        assertEquals(6, a.toSet().size)
    }

    @Test
    fun `节点数和角色数对得上,圆心只有一个`() {
        val ns = FlowMath.nodes(1000f, 800f, listOf("算", "查"))
        assertEquals(3 + 2, ns.size)
        assertEquals(1, ns.count { it.key == FlowMath.KEY_HUB })
        val hub = ns.first { it.key == FlowMath.KEY_HUB }
        assertEquals(FlowMath.center(1000f, 800f), FlowMath.Pt(hub.x, hub.y))
    }

    @Test
    fun `角色最多排四个`() {
        val ns = FlowMath.nodes(1000f, 800f, listOf("a", "b", "c", "d", "e"))
        assertEquals(3 + FlowMath.MAX_ROLES, ns.size)
        assertFalse(ns.any { it.key == FlowMath.ROLE_PREFIX + "e" })
    }

    @Test
    fun `所有节点都落在画面里`() {
        val ns = FlowMath.nodes(720f, 1200f, listOf("算"))
        for (n in ns) {
            assertTrue("${n.key} 跑出左边", n.x >= 0f)
            assertTrue("${n.key} 跑出右边", n.x <= 720f)
            assertTrue("${n.key} 跑出上边", n.y >= 0f)
            assertTrue("${n.key} 跑出下边", n.y <= 1200f)
        }
    }

    @Test
    fun `角色去重且只留最近四个`() {
        val es = listOf(
            ev(1, AgentBusModel.HUB, AgentBusModel.KIND_ASK, "算"),
            ev(2, AgentBusModel.HUB, AgentBusModel.KIND_ASK, "算"),
            ev(3, AgentBusModel.HUB, AgentBusModel.KIND_ASK, "查"),
            ev(4, AgentBusModel.HUB, AgentBusModel.KIND_ASK, "改"),
            ev(5, AgentBusModel.HUB, AgentBusModel.KIND_ASK, "挑刺"),
            ev(6, AgentBusModel.HUB, AgentBusModel.KIND_ASK, "译"),
        )
        assertEquals(listOf("查", "改", "挑刺", "译"), FlowMath.rolesOf(es))
    }

    @Test
    fun `方向由谁发的决定 —— 云端回来的一定是往中枢`() {
        val roles = setOf("算")
        assertEquals(
            FlowMath.KEY_SELF to FlowMath.KEY_HUB,
            FlowMath.edgeOf(ev(1, AgentBusModel.SELF, AgentBusModel.KIND_NOTE), roles)
        )
        assertEquals(
            FlowMath.KEY_CLOUD to FlowMath.KEY_HUB,
            FlowMath.edgeOf(ev(2, AgentBusModel.CLOUD, AgentBusModel.KIND_ANSWER, "算"), roles)
        )
        assertEquals(
            FlowMath.KEY_HUB to (FlowMath.ROLE_PREFIX + "算"),
            FlowMath.edgeOf(ev(3, AgentBusModel.HUB, AgentBusModel.KIND_ASK, "算"), roles)
        )
    }

    @Test
    fun `认不出的角色退回云端 —— 不能画成中枢自言自语`() {
        assertEquals(
            FlowMath.KEY_HUB to FlowMath.KEY_CLOUD,
            FlowMath.edgeOf(ev(1, AgentBusModel.HUB, AgentBusModel.KIND_ASK, "旧的"), setOf("算"))
        )
    }

    @Test
    fun `动手和记一笔都画成中枢身上的一圈`() {
        val roles = setOf("算")
        val tool = FlowMath.edgeOf(ev(1, AgentBusModel.HUB, AgentBusModel.KIND_TOOL), roles)
        assertEquals(FlowMath.KEY_HUB, tool.first)
        assertEquals(FlowMath.KEY_HUB, tool.second)
    }

    @Test
    fun `节奏 —— 早到的算 0,晚到的算 1,中间按比例`() {
        assertEquals(0f, FlowMath.progress(1000L, 1000L), 0.0001f)
        assertEquals(0.5f, FlowMath.progress(1000L, 1450L), 0.0001f)
        assertEquals(1f, FlowMath.progress(1000L, 9000L), 0.0001f)
        assertFalse(FlowMath.isTraveling(1000L, 9000L))
        assertTrue(FlowMath.isTraveling(1000L, 1500L))
    }

    @Test
    fun `热度从 1 掉到 0`() {
        assertEquals(1f, FlowMath.glowAt(1000L, 1000L), 0.0001f)
        assertEquals(0f, FlowMath.glowAt(1000L, 1000L + FlowMath.GLOW_MS + 1), 0.0001f)
        assertEquals(0f, FlowMath.glowAt(2000L, 1000L), 0.0001f)   // 未来的事件不发光
    }

    @Test
    fun `颜色按性质分,不同性质不同色`() {
        val kinds = listOf(
            AgentBusModel.KIND_ASK, AgentBusModel.KIND_ANSWER,
            AgentBusModel.KIND_TOOL, AgentBusModel.KIND_ROUTE, AgentBusModel.KIND_ERROR,
        )
        assertEquals(kinds.size, kinds.map { FlowMath.toneOf(it) }.toSet().size)
        assertNotEquals(FlowMath.toneOf(AgentBusModel.KIND_ASK), FlowMath.toneOf(AgentBusModel.KIND_ANSWER))
    }

    @Test
    fun `事件流一行 —— 带编号、方向、性质,正文截断`() {
        val roles = setOf("算")
        val line = FlowMath.feedLine(
            ev(12, AgentBusModel.HUB, AgentBusModel.KIND_ASK, "算", "这是一段很长很长的说明文字用来检查截断"),
            roles,
        )
        assertTrue(line.startsWith("#12 "))
        assertTrue(line.contains("中枢>算"))
        assertTrue(line.contains("派活"))
        assertTrue(line.endsWith("…"))
        assertFalse(line.contains("\n"))
    }

    @Test
    fun `本机那一圈在事件流里不写箭头`() {
        val line = FlowMath.feedLine(ev(7, AgentBusModel.HUB, AgentBusModel.KIND_TOOL, "", "click"), emptySet())
        assertTrue(line.contains("中枢·本机"))
        assertFalse(line.contains(">"))
    }

    @Test
    fun `极坐标 —— 角度对,半径对`() {
        val c = FlowMath.Pt(500f, 400f)
        val left = FlowMath.polar(c, FlowMath.Radii(100f, 100f), 180f)
        assertTrue(abs(left.x - 400f) < 0.5f)
        assertTrue(abs(left.y - 400f) < 0.5f)
        val up = FlowMath.polar(c, FlowMath.Radii(100f, 100f), 270f)
        assertTrue(abs(up.x - 500f) < 0.5f)
        assertTrue(abs(up.y - 300f) < 0.5f)
    }
}
