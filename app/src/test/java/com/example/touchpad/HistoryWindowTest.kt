package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「历史什么时候裁」那条算术的测试。
 *
 * ★★ 它防的错**一个异常都不抛**,而且**症状长得像性能问题、不像 bug**:
 * 裁得太勤 → 每一轮都从队首动刀 → llama-server 的前缀缓存(
 * `get_common_prefix`,只认**从头开始**的公共前缀)整段作废 → 每一轮都要把历史
 * 从零重算 → 撞上 25 秒读超时 → 静默转云端。
 *
 * 用户 2026-10-05 报的原话是「**响应时间太久了**」。
 * 真机日志里只有一行「本地算得慢(降频或这一轮算得多),转云端…」,
 * **读起来像偶发的降频,其实每一轮都必然发生、和温度无关。**
 *
 * 所以这里最值钱的一条不是边界,是最后那条**模拟**:它把「老规矩每轮都裁」和
 * 「新规矩九轮才裁一次」并排跑一遍,用数字钉住差别。
 */
class HistoryWindowTest {

    /** 没到上限就一个字都不许动 —— 裁一次要重算一整截,不便宜。 */
    @Test
    fun `没到上限不裁`() {
        assertEquals(0, HistoryWindow.dropCount(0))
        assertEquals(0, HistoryWindow.dropCount(1))
        assertEquals(0, HistoryWindow.dropCount(HistoryWindow.KEEP))
        assertEquals(0, HistoryWindow.dropCount(HistoryWindow.MAX - 1))
    }

    /**
     * ★ 边界:**正好等于上限时不许裁**。
     *
     * 判据是「超过」不是「达到」。写成 `>=` 的话,`saveTurn` 一轮加 2 条,
     * 会变成**每隔一轮裁一次** —— 那就又滑回老路了,而且滑回去的方式极其隐蔽
     * (代码看起来只是差一个等号)。
     */
    @Test
    fun `正好等于上限不裁_超过一条就裁到KEEP`() {
        assertEquals(0, HistoryWindow.dropCount(HistoryWindow.MAX))
        // 超过一条**不是只裁一条**,是一次裁到 KEEP —— 见文件头「裁得碎 = 裁得勤」。
        assertEquals(HistoryWindow.MAX + 1 - HistoryWindow.KEEP, HistoryWindow.dropCount(HistoryWindow.MAX + 1))
    }

    /** 超过之后就一次裁到 KEEP,不是裁一条。见文件头:裁得碎 = 裁得勤。 */
    @Test
    fun `超过上限就裁到只剩KEEP条`() {
        for (len in (HistoryWindow.MAX + 1)..(HistoryWindow.MAX + 50)) {
            val drop = HistoryWindow.dropCount(len)
            assertEquals("len=$len 应该正好裁到 KEEP", HistoryWindow.KEEP, len - drop)
        }
    }

    /**
     * ★★ 裁掉的数量**必须小于总长度**,否则 `h.remove(0)` 会抛
     * `IndexOutOfBoundsException` —— 而那是在**写历史的时候**炸,
     * 表现是「聊着聊着她突然没反应」。这条纯属防呆,但它便宜。
     */
    @Test
    fun `裁多少都不会把整份历史清空`() {
        for (len in 0..200) {
            assertTrue("len=$len 时裁掉的不该 ≥ 总数", HistoryWindow.dropCount(len) < len.coerceAtLeast(1))
        }
    }

    /** 两个常量本身也钉一下:它们的关系(0 < KEEP < MAX)是上面所有结论的前提。 */
    @Test
    fun `两个常量的关系是对的`() {
        assertTrue("KEEP 必须大于 0,否则裁完什么也不剩", HistoryWindow.KEEP > 0)
        assertTrue("KEEP 必须小于 MAX,否则一裁就每次都要裁", HistoryWindow.KEEP < HistoryWindow.MAX)
    }

    // ------------------------------------------------------------------

    /**
     * ★★★ 这条是这次修复的**主证据**。
     *
     * 模拟真机:`saveTurn` 每一轮往队尾加 2 条(user + assistant),然后按规矩裁。
     * 数一数 100 轮里**裁了几次** —— 裁一次 = 下一次本地请求要把留下的那截从零重算。
     *
     * 把**老规矩**并排跑一遍当对照:`len > 12 就 remove(0)`(恒保 12 条)。
     * 差别不是「快一点」,是**量级**:老规矩几乎每一轮都在裁。
     */
    @Test
    fun `模拟一百轮对话_新规矩裁的次数要比老规矩少一个量级`() {
        fun trimsIn(turns: Int, max: Int, keep: Int): Int {
            var len = keep
            var trims = 0
            repeat(turns) {
                len += 2                                     // saveTurn 加 user + assistant
                val drop = if (len > max) len - keep else 0  // 规矩
                if (drop > 0) { len -= drop; trims++ }
            }
            return trims
        }

        val turns = 100
        val old = trimsIn(turns, max = 12, keep = 12)        // 2026-10-05 之前那条
        val now = trimsIn(turns, max = HistoryWindow.MAX, keep = HistoryWindow.KEEP)

        // 老规矩:一到 12 条就每轮都裁(加 2 裁 2),所以几乎是「每轮一次」。
        assertTrue("老规矩应该几乎每轮都裁(实测 $old 次)", old >= turns - 2)
        // 新规矩:涨到 MAX 才裁到 KEEP,一轮加 2 条 —— 周期是 (MAX-KEEP)/2 + 1 轮。
        val expectedPeriod = (HistoryWindow.MAX - HistoryWindow.KEEP) / 2 + 1
        assertEquals("$turns 轮应该裁 $turns/$expectedPeriod 次左右", turns / expectedPeriod, now)
        assertTrue("新规矩必须比老规矩少一个量级(老 $old / 新 $now)", now * 5 < old)
    }
}
