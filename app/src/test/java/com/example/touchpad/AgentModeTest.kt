package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协作档位。
 *
 * 为什么值得钉：这一层错了**不会报错**，只会变成「明明选的逐步，一步都没问」
 * 或者「明明选的自动，每步都弹框」。两种都不会在日志里留下痕迹，
 * 只有坐在手机前的人知道自己被坑了。
 */
class AgentModeTest {

    @Test
    fun `三个名字都认`() {
        assertEquals("auto", AgentMode.norm("auto"))
        assertEquals("plan", AgentMode.norm("plan"))
        assertEquals("step", AgentMode.norm("step"))
    }

    @Test
    fun `大小写和空格不影响`() {
        assertEquals("plan", AgentMode.norm("  PLAN "))
    }

    @Test
    fun `认不出来的退回自动 —— 坏数据不该让机器开始每步弹框`() {
        assertEquals(AgentMode.AUTO, AgentMode.norm(null))
        assertEquals(AgentMode.AUTO, AgentMode.norm(""))
        assertEquals(AgentMode.AUTO, AgentMode.norm("2"))
        assertEquals(AgentMode.AUTO, AgentMode.norm("逐步"))
    }

    @Test
    fun `点一下转一格，转一圈回来`() {
        assertEquals("plan", AgentMode.next("auto"))
        assertEquals("step", AgentMode.next("plan"))
        assertEquals("auto", AgentMode.next("step"))
        assertEquals("plan", AgentMode.next("乱写的"))
    }

    @Test
    fun `界面上只显示名字`() {
        assertEquals("自动", AgentMode.label("auto"))
        assertEquals("规划", AgentMode.label("plan"))
        assertEquals("逐步", AgentMode.label("step"))
    }

    @Test
    fun `只有规划档要拆`() {
        assertFalse(AgentMode.wantsPlan("auto"))
        assertTrue(AgentMode.wantsPlan("plan"))
        assertFalse(AgentMode.wantsPlan("step"))
    }

    @Test
    fun `逐步档每一步都要问，但只读的不问`() {
        assertTrue(AgentMode.asksBeforeTool("step", "open_app", readOnly = false, alreadyAsks = false))
        assertFalse(AgentMode.asksBeforeTool("step", "get_state", readOnly = true, alreadyAsks = false))
    }

    @Test
    fun `本来就有一道确认闸的不再问第二次`() {
        assertFalse(AgentMode.asksBeforeTool("step", "type", readOnly = false, alreadyAsks = true))
    }

    @Test
    fun `别的档一个框都不弹`() {
        assertFalse(AgentMode.asksBeforeTool("auto", "open_app", readOnly = false, alreadyAsks = false))
        assertFalse(AgentMode.asksBeforeTool("plan", "open_app", readOnly = false, alreadyAsks = false))
    }

    @Test
    fun `没名字的工具不问 —— 问了也说不清在问什么`() {
        assertFalse(AgentMode.asksBeforeTool("step", " ", readOnly = false, alreadyAsks = false))
    }
}
