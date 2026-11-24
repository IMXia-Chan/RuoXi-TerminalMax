package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「一件活」身上那些纯算术。
 *
 * ★ 这些错了**不报任何错**：标题多截两个字、会话多留两轮，屏上看着都正常，
 *   只有下次「接着说」的时候会莫名其妙地忘事 —— 所以它们必须有判据。
 */
class WorkJobMathTest {

    private fun turn(t: String) = WorkTurn(WorkTurn.USER, t)

    // ── 标题 ──

    @Test
    fun `标题把换行和连续空白压成一个空格`() {
        // 任务条是一行，标题里带换行会把整条撑高。
        assertEquals("写一份 周报 并 发出去", WorkJobMath.titleOf("写一份\n周报  \n\t并   发出去"))
    }

    @Test
    fun `标题超长就截断并加省略号`() {
        val t = WorkJobMath.titleOf("一".repeat(40))
        assertTrue(t.endsWith("…"))
        // 24 个字 + 一个省略号 = 25
        assertEquals(25, t.length)
    }

    @Test
    fun `刚好的长度不加省略号`() {
        val s = "一".repeat(24)
        assertEquals(s, WorkJobMath.titleOf(s))
    }

    // ── 会话轮次 ──

    @Test
    fun `超过六轮从头裁掉最老的一轮`() {
        var turns = emptyList<WorkTurn>()
        for (i in 1..7) {
            turns = WorkJobMath.appendTurn(turns, WorkTurn.USER, "第 $i 句")
            turns = WorkJobMath.appendTurn(turns, WorkTurn.ASSISTANT, "第 $i 答")
        }
        // 7 轮 = 14 条，只留最近 6 轮 = 12 条
        assertEquals(12, turns.size)
        assertEquals("第 2 句", turns.first().text)
        assertEquals("第 7 答", turns.last().text)
        assertTrue(turns.none { it.text == "第 1 句" || it.text == "第 1 答" })
    }

    @Test
    fun `刚好六轮不动`() {
        var turns = emptyList<WorkTurn>()
        for (i in 1..6) {
            turns = WorkJobMath.appendTurn(turns, WorkTurn.USER, "u$i")
            turns = WorkJobMath.appendTurn(turns, WorkTurn.ASSISTANT, "a$i")
        }
        assertEquals(12, turns.size)
        assertEquals("u1", turns.first().text)
    }

    @Test
    fun `空的一条不进请求`() {
        // 空 assistant 会让某些兼容端点直接 400。
        val turns = listOf(turn("改短一点"), WorkTurn(WorkTurn.ASSISTANT, "  "))
        assertEquals(1, WorkJobMath.historyMessages(turns).size)
    }

    @Test
    fun `轮数只数用户说的`() {
        val job = job(
            turns = listOf(
                turn("一"), WorkTurn(WorkTurn.ASSISTANT, "答一"),
                turn("二"), WorkTurn(WorkTurn.ASSISTANT, "答二"),
            )
        )
        assertEquals(2, job.round)
    }

    // ── 状态 ──

    @Test
    fun `认不出来的状态一律当排队`() {
        assertEquals(WorkJobMath.ST_QUEUED, WorkJobMath.statusOf("什么鬼"))
        assertEquals(WorkJobMath.ST_QUEUED, WorkJobMath.statusOf(""))
        assertEquals(WorkJobMath.ST_RUNNING, WorkJobMath.statusOf("running"))
    }

    @Test
    fun `认不出来的模式一律当分工`() {
        assertEquals(WorkJobMath.MODE_CHAIN, WorkJobMath.modeOf("chain"))
        assertEquals(WorkJobMath.MODE_CODE, WorkJobMath.modeOf("code"))
        assertEquals(WorkJobMath.MODE_FANOUT, WorkJobMath.modeOf("???") )
    }

    @Test
    fun `五个状态都有字`() {
        for (s in listOf(
            WorkJobMath.ST_QUEUED, WorkJobMath.ST_RUNNING, WorkJobMath.ST_DONE,
            WorkJobMath.ST_FAILED, WorkJobMath.ST_STOPPED,
        )) {
            assertTrue(WorkJobMath.statusLabel(s).isNotEmpty())
        }
    }

    // ── 代号 ──

    @Test
    fun `代号从 01 起编 两位`() {
        assertEquals("WX-01", WorkJobMath.codeOf(0))
        assertEquals("WX-09", WorkJobMath.codeOf(8))
        assertEquals("WX-10", WorkJobMath.codeOf(9))
    }

    // ── 顺序与上限 ──

    @Test
    fun `未归档的在前 各自按创建时间正序`() {
        val a = job("a", createdAt = 3)
        val b = job("b", createdAt = 1, archived = true)
        val c = job("c", createdAt = 2)
        assertEquals(listOf("c", "a"), WorkJobMath.ordered(listOf(a, b, c), false).map { it.id })
        assertEquals(listOf("b"), WorkJobMath.ordered(listOf(a, b, c), true).map { it.id })
    }

    @Test
    fun `三件同时跑 第四件开不了`() {
        // ★ 他 2026-11-06 拍的 3。
        assertTrue(WorkJobMath.canStart(0))
        assertTrue(WorkJobMath.canStart(2))
        assertFalse(WorkJobMath.canStart(3))
        assertFalse(WorkJobMath.canStart(4))
    }

    @Test
    fun `上限只数没归档的`() {
        val many = (0 until WorkJobMath.MAX_JOBS).map { job("j$it", createdAt = it.toLong()) }
        assertTrue(WorkJobMath.atCapacity(many))
        // 归档一件就腾出一个位置 —— 归档是收起来，不是不要了，但它不占位置。
        val one = many.mapIndexed { i, j -> if (i == 0) j.copy(archived = true) else j }
        assertFalse(WorkJobMath.atCapacity(one))
    }

    // ── 落盘往返 ──
    //
    // ★ 这一步**不在这儿测**：JVM 单测里的 `org.json` 是个只会抛 "Stub!" 的空壳，
    //   所以 `toJson` / `fromJson` 在这儿跑不起来（同 [ExperienceStoreTest] 头注释）。
    //   真正要守的那一条 —— 读回来也裁到六轮 —— 是 [WorkJobMath.fitTurns]，
    //   它在读的时候被调一次，所以直接测它。

    @Test
    fun `读回来的会话也裁到六轮`() {
        val raw = (1..16).map { turn("u$it") }
        val kept = WorkJobMath.fitTurns(raw)
        assertEquals(12, kept.size)
        assertEquals("u5", kept.first().text)
        assertEquals("u16", kept.last().text)
    }

    @Test
    fun `没超长就原样返回 一条也不动`() {
        // ★ 判据是「超过」，不是「等于」：判等会让它每轮都裁一次，而每裁一次都白白复制一份。
        val raw = (1..12).map { turn("u$it") }
        assertEquals(raw, WorkJobMath.fitTurns(raw))
    }

    private fun job(
        id: String = "j",
        createdAt: Long = 1,
        archived: Boolean = false,
        turns: List<WorkTurn> = emptyList(),
    ) = WorkJob(
        id = id,
        title = "一件事",
        mode = WorkJobMath.MODE_FANOUT,
        status = WorkJobMath.ST_QUEUED,
        createdAt = createdAt,
        updatedAt = createdAt,
        turns = turns,
        archived = archived,
        archivedAt = if (archived) createdAt else 0,
    )
}
