package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 点开一行之后要看到什么。
 *
 * ★ 这几条盯的是同一件事：屏上被压成「一行读数」之后，**点开必须拿得回两段**。
 *   少给一段，就等于把东西收起来又不给看 —— 那和他说的「太复杂」是两码事。
 */
class StepDetailTest {

    private fun ev(
        id: Long, role: String, kind: String, text: String, ref: Long = 0,
        from: String = AgentBusModel.HUB,
    ) = AgentEvent(
        id = id, task = "w1", from = from, role = role, kind = kind,
        ref = ref, text = text, at = id * 1000,
    )

    @Test
    fun `任务和回答两段都拿得回来`() {
        val shown = StepDetail.of(
            listOf(
                ev(7, "编辑", AgentBusModel.KIND_ASK, "改短一点，书面一些"),
                ev(9, "编辑", AgentBusModel.KIND_ANSWER, "已给出 300 字版本", ref = 7,
                    from = AgentBusModel.CLOUD),
            ),
            7,
        )
        assertTrue(shown.body.contains("【任务】"))
        assertTrue(shown.body.contains("改短一点"))
        assertTrue(shown.body.contains("【回答】"))
        assertTrue(shown.body.contains("已给出 300 字版本"))
        assertEquals("编辑 · #7", shown.title)
    }

    @Test
    fun `还在跑的活说明白是没回来`() {
        val shown = StepDetail.of(
            listOf(ev(7, "估算师", AgentBusModel.KIND_ASK, "粗算工期")),
            7,
        )
        assertTrue(shown.body.contains("粗算工期"))
        assertTrue(shown.body.contains("还在跑"))
        assertFalse(shown.body.contains("已经回来"))
    }

    @Test
    fun `出错的那条单独标出来`() {
        val shown = StepDetail.of(
            listOf(
                ev(7, "编辑", AgentBusModel.KIND_ASK, "改短一点"),
                ev(9, "编辑", AgentBusModel.KIND_ERROR, "超时", ref = 7,
                    from = AgentBusModel.CLOUD),
            ),
            7,
        )
        assertTrue(shown.body.contains("【出错】"))
        assertTrue(shown.body.contains("超时"))
    }

    @Test
    fun `回话不能配到别人头上`() {
        // ref 指的不是这一条 —— 两张卡各是各的，接错就是「她做了我没让她做的事」。
        val shown = StepDetail.of(
            listOf(
                ev(7, "编辑", AgentBusModel.KIND_ASK, "改短一点"),
                ev(8, "审稿人", AgentBusModel.KIND_ASK, "挑毛病"),
                ev(9, "审稿人", AgentBusModel.KIND_ANSWER, "第三节撑不住", ref = 8),
            ),
            7,
        )
        assertTrue(shown.body.contains("还在跑"))
        assertFalse(shown.body.contains("第三节撑不住"))
    }

    @Test
    fun `滑出记录的说清楚是滑出去了`() {
        val shown = StepDetail.of(emptyList(), 404)
        assertTrue(shown.body.contains("滑出记录"))
        assertEquals("#404", shown.title)
    }

    @Test
    fun `没有岗位名时不编一个`() {
        val shown = StepDetail.of(listOf(ev(7, "", AgentBusModel.KIND_ASK, "跑一下")), 7)
        assertEquals("#7", shown.title)
    }
}
