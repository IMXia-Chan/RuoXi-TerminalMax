package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「相处越久越亲」的测试 —— 纯 JVM,不碰 Android / org.json / 时钟。
 *
 * ## 它为什么单独一个文件
 *
 * 它测的是一个**新函数** [MoodMath.together],和旧的 `evolve` / `applyWord` 一个字都不重叠 ——
 * 所以旧的 13 条 `MoodStoreTest` 天然不受影响,这里也**不许去动它们**。
 *
 * ## 这个函数错了会怎样(所以为什么值得钉)
 *
 * **它不崩,也不报错,它只是让人不对了。**
 * 三种坏法,每一种的外观都像「她今天有点怪」:
 *  - 把「相处」写成「按墙上时钟换算天数」→ 他扔下手机一个月回来,她**一天没陪**却直接老夫老妻;
 *  - 同一天里每说一句话就数一天 → 一个下午就能从陌生人走到老夫老妻;
 *  - 顺手把「上次见你」也推了 → 她越主动干活,越显得他「刚来过」,
 *    连带着把久不露面的账一并抹掉。
 *
 * 所以下面每一条**正例**都配一条**反例**:该涨的必须涨,**不该涨的必须不涨**。
 *
 * ★ `dayIndex` 由调用方算好传进来(见 [MoodStore.localDay]),所以这里传 `0 / 1 / 2`
 *   就是第 0 / 1 / 2 天 —— **零时区负担**,和 [MoodMath.evolve] 收 `nowSec` 是同一个分工。
 */
class MoodMathTogetherTest {

    /** 照出厂那份造一个人:有心情、有亲密度、一天都还没相处过。 */
    private fun s(mood: Int = 60, intimacy: Int = 25, days: Int = 0, lastDay: Long = -1L) =
        MoodStore.State(
            lastSeenSec = 0,
            mood = mood,
            intimacy = intimacy,
            daysTogether = days,
            lastTogetherDay = lastDay,
        )

    // ---- 该涨的 ----

    @Test
    fun `见了第一面 —— 日子数从零走到一`() {
        val a = MoodMath.together(s(), 0)
        assertEquals(1, a.daysTogether)
        assertEquals(0L, a.lastTogetherDay)
    }

    @Test
    fun `一起待够两天 —— 亲密度涨出厂那一点`() {
        // 出厂节奏 = 每 2 天涨 1(见 MoodTuning.togetherEveryDays)
        val a = MoodMath.together(s(), 0)          // 第 1 天:还没攒够
        assertEquals("第 1 天不该涨", 25, a.intimacy)
        val b = MoodMath.together(a, 1)            // 第 2 天:涨
        assertEquals(2, b.daysTogether)
        assertEquals(26, b.intimacy)
    }

    @Test
    fun `一直陪着 —— 攒得越多涨得越多`() {
        var x = s()
        repeat(10) { day -> x = MoodMath.together(x, day.toLong()) }
        assertEquals("第 10 天", 10, x.daysTogether)
        assertEquals("10 天里涨了 5 次", 30, x.intimacy)
    }

    @Test
    fun `拨成每天都涨 —— 账跟着走`() {
        val t = MoodTuning(togetherEveryDays = 1, togetherGain = 3)
        val a = MoodMath.together(s(), 0, t)
        assertEquals(28, a.intimacy)
    }

    @Test
    fun `拨成十天涨一次 —— 账也跟着走`() {
        val t = MoodTuning(togetherEveryDays = 10, togetherGain = 5)
        var x = s()
        repeat(9) { day -> x = MoodMath.together(x, day.toLong(), t) }
        assertEquals("第 9 天还不到点", 25, x.intimacy)
        x = MoodMath.together(x, 9, t)
        assertEquals("第 10 天到了", 30, x.intimacy)
    }

    @Test
    fun `涨多少是 positive 的那个数 —— 是「涨」不是「扣」`() {
        // ★ 全项目的账目约定:凡是「扣」的项,字段名里带 Drop 且值是正数。
        //   这一项反过来 —— 名字是 Gain,值就是**涨**那么多。
        val t = MoodTuning(togetherEveryDays = 1, togetherGain = 7)
        assertEquals(32, MoodMath.together(s(), 0, t).intimacy)
    }

    // ---- ★★ 不该涨的:这一组才是这个函数的正身 ----

    @Test
    fun `同一天说一百句话 —— 只算一天`() {
        // ★ 这是「相处」和「说话」的分界:说话有别的账管(applyWord),
        //   相处只认**他来了**这件事,一天来多少次都只算一次。
        var x = s()
        repeat(100) { x = MoodMath.together(x, 0) }
        assertEquals(1, x.daysTogether)
        assertEquals("说一百句也不该多涨", 25, x.intimacy)
    }

    @Test
    fun `隔了三天没露面 —— 回来也只补一天`() {
        // ★★ 这条是整个设计的关键,也是「挂机不算」那句话的落点。
        //   第 0 天来过,下一次是第 4 天 —— 中间那三天**不是相处**,那是他没出现。
        val a = MoodMath.together(s(), 0)
        val b = MoodMath.together(a, 4)
        assertEquals("日子数是「待过的天数」,不是「过了几天」", 2, b.daysTogether)
        // 出厂是「攒够 2 天涨 1」——所以真到了第 2 天,是该涨的;
        // 这一条要钉的是**中间那三天没被算进去**,不是「不涨」。
        assertEquals("只按 2 天算,不是按 5 天算", 26, b.intimacy)
    }

    @Test
    fun `隔了很久很久 —— 也只是今天这一天`() {
        // 同一件事往极端推:一年没来,不该一次补 365 天
        val a = MoodMath.together(s(), 0)
        val b = MoodMath.together(a, 5000)
        assertEquals(2, b.daysTogether)
    }

    @Test
    fun `时钟往回拨 —— 一天都不许涨回去`() {
        // ★ 判据是 `dayIndex <= lastTogetherDay`(不是 `!=`):
        //   改系统时间的人不该能靠倒拨时钟把日子数刷上去。
        val a = MoodMath.together(MoodMath.together(s(), 9), 10)
        val back = MoodMath.together(a, 3)
        assertEquals("倒着走的日子数不动", a.daysTogether, back.daysTogether)
        assertEquals("那天记的日子也不许被改", 10L, back.lastTogetherDay)
    }

    @Test
    fun `关掉这一项 —— 日子照数,但不涨`() {
        // ★ `togetherEveryDays <= 0` 当「关掉」:界面上拨不到 0(sanitize 下限是 1),
        //   但配置能手改 —— 手改出来的 0 必须是「不涨」,不是「每天都涨」或者除零。
        val t = MoodTuning(togetherEveryDays = 0, togetherGain = 9)
        var x = s()
        repeat(5) { day -> x = MoodMath.together(x, day.toLong(), t) }
        assertEquals("日子还在数 —— 界面上那行字看得见", 5, x.daysTogether)
        assertEquals("但一点都没涨", 25, x.intimacy)
    }

    // ---- ★ 它不许碰的那两样 ----

    @Test
    fun `不碰心情 —— 陪得久是「更近」,不是「更高兴」`() {
        val a = MoodMath.together(s(mood = 37), 0)
        val b = MoodMath.together(a, 1)
        assertEquals(37, a.mood)
        assertEquals(37, b.mood)
    }

    @Test
    fun `不推「上次见你」—— 那是别的一笔账`() {
        // ★★ 这条钉的是这个项目栽过的那个坑(见 MoodStore.peek 的注释:
        //   「她自言自语不算他来过」)。四条会改分数的入口都**只改分数、不推时刻** ——
        //   推了的话,她越主动干活,越显得他「刚来过」,久不露面的账就被一起抹了。
        val a = MoodMath.together(s(), 0)
        assertEquals(0L, a.lastSeenSec)
        val b = MoodMath.together(a, 1)
        assertEquals(0L, b.lastSeenSec)
    }

    @Test
    fun `除了日子数和亲密度,别的一个字段都不许动`() {
        // ★ 拿「它该变成的样子」和「它真变成的样子」整份比 ——
        //   比逐个字段断言强:以后往 State 里加一个新字段,这条路会**自动**把它一起比进去,
        //   不用谁记得回来补一行。
        val before = s()
        val after = MoodMath.together(before, 0, MoodTuning(togetherEveryDays = 1))
        assertEquals(
            before.copy(daysTogether = 1, lastTogetherDay = 0L, intimacy = 26),
            after,
        )
    }

    // ---- 边界 ----

    @Test
    fun `亲密度撞到顶 —— 封在 100,不越界`() {
        val t = MoodTuning(togetherEveryDays = 1, togetherGain = 50)
        var x = s(intimacy = 90)
        repeat(5) { day -> x = MoodMath.together(x, day.toLong(), t) }
        assertEquals(100, x.intimacy)
        assertTrue(x.intimacy <= 100)
    }

    @Test
    fun `亲密度到底了 —— 不会掉成负数`() {
        // 出厂 togetherGain 是正数,但配置能手改成 0;0 的情况上面那条已经钉了。
        // 这一条钉的是**「这一项永远不做减法」**。
        val zero = MoodTuning(togetherEveryDays = 1, togetherGain = 0)
        val x = MoodMath.together(s(intimacy = 0), 0, zero)
        assertEquals(0, x.intimacy)
    }

    @Test
    fun `第一次见面就撞上「今天已数过」—— 一天都没数过时 lastTogetherDay 是 -1`() {
        // ★ `-1` 这个初值是有用的:第 0 天来的时候 `0 <= -1` 是假,所以**数得进去**。
        //   要是初值写成 0,那么「第 0 天」这个合法的 dayIndex 会被当成「今天已经数过了」——
        //   表现是她永远慢一天,而且只在「epochDay 恰好是 0」时出错(测试里天天撞见,真机上永远见不到)。
        val fresh = s()
        assertEquals(-1L, fresh.lastTogetherDay)
        assertEquals(1, MoodMath.together(fresh, 0).daysTogether)
    }

    @Test
    fun `出厂那份在出厂节奏下 —— 从 25 走到老夫老妻要小半年`() {
        // ★ 这是个「手感」的账,不是精确值 —— 但它挡的是「顺手把出厂值调到 1 天」
        //   那种改动:那样她一个月就走到顶了,「慢慢升温」就没了。
        var x = s(intimacy = 25)
        var day = 0L
        while (x.intimacy < 70 && day < 1000) {
            x = MoodMath.together(x, day)
            day++
        }
        assertEquals("走到 70 用掉的**天**数", 90, x.daysTogether)
        assertEquals(70, x.intimacy)
    }

    @Test
    fun `日子不动的时候,返回的是原来那一份`() {
        // ★ 同一天里被反复调到时不该造垃圾对象 —— 这条顺手把「早退那一条真的早退」
        //   钉住(早退写成了 `return s.copy(...)` 的话,值一样、对象不一样,这条会红)。
        val a = MoodMath.together(s(), 5)
        assertSame(a, MoodMath.together(a, 5))
        assertEquals(1, a.daysTogether)      // 顺手确认夹具那一份不是空的
    }
}
