package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按任务选角色。
 *
 * 为什么值得钉：选错了**不会报错**，只会让「你是估算师」这句话跟干的活对不上，
 * 而那种错在日志里看不出来，只有读回执的人才觉得别扭。
 */
class RoleMathTest {

    @Test
    fun `润色的活派给编辑，不是估算师`() {
        val r = RoleMath.pick("把这段话改短一点，书面一些")
        assertEquals("编辑", r.first())
    }

    @Test
    fun `挑毛病的活派给审稿人`() {
        val r = RoleMath.pick("帮我说说这个方案哪里站不住、有什么风险")
        assertEquals("审稿人", r.first())
    }

    @Test
    fun `粗算的活派给估算师`() {
        val r = RoleMath.pick("粗算一下这三步要花多久")
        assertEquals("估算师", r.first())
    }

    @Test
    fun `一件活可以带出两个角色，最贴的在前`() {
        val r = RoleMath.pick("先粗算要多久，再说说哪里可能翻车")
        assertEquals("估算师", r.first())
        assertTrue(r.contains("审稿人"))
    }

    @Test
    fun `最多给三个，不会把整张表倒出来`() {
        val r = RoleMath.pick("查一下依据、举例说明、改一下措辞、算个成本、讲讲原理")
        assertTrue(r.size <= 3)
    }

    @Test
    fun `什么都没命中就回空表 —— 不硬凑角色`() {
        assertTrue(RoleMath.pick("你好").isEmpty())
        assertTrue(RoleMath.pick("").isEmpty())
    }

    @Test
    fun `兜底那位永远不会被挑中`() {
        // 兜底不该抢活：它没有触发词，只有在什么都不命中时才由 FALLBACK 点名。
        assertTrue(RoleMath.pick("查、改、算、解释、计划、举例、挑毛病").none { it == RoleMath.FALLBACK })
    }

    @Test
    fun `拆解用的话里带职责说明，而且留了口子`() {
        val b = RoleMath.brief("把这段话润色一下")
        assertTrue(b.contains("编辑（"))
        assertTrue(b.contains("也可以按这件活起更细的名字"))
    }

    @Test
    fun `拆解用的话鼓励起更细的名字`() {
        val b = RoleMath.brief("给这份报价单做一份核对")
        assertTrue(b.contains("更细的名字"))
    }

    @Test
    fun `表里的角色都能查到职责`() {
        for (r in RoleMath.ROLES) assertTrue(r.name, RoleMath.jobOf(r.name) != null)
        assertNull(RoleMath.jobOf("不存在的角色"))
    }

    @Test
    fun `名字都是岗位，不是单个动作字`() {
        // 「算」「改」这种是一个动作，不是一个岗位 —— 他 2026-11-05 明说要改掉。
        for (r in RoleMath.ROLES) assertTrue(r.name, r.name.length >= 2)
    }
}
