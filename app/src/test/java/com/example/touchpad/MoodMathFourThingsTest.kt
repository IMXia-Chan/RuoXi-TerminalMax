package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「别的四件事影响她」的测试 —— 纯 JVM,不碰 Android / org.json / 时钟。
 *
 * ## 这四件事
 *
 * | 事 | 函数 | 只影响 |
 * |---|---|---|
 * | 一件事**办成了** | [MoodMath.jobDone] | 心情 ↑ |
 * | 一件事**办砸了** | [MoodMath.jobFailed] | 心情 ↓ |
 * | 她**主动开口你没理** | [MoodMath.ignored] | 心情 ↓ |
 * | **半夜你还陪着** | [MoodMath.lateNight] | 心情 ↑ |
 *
 * ## 它为什么单独一个文件
 *
 * 四个都是**新函数**,和旧的 `evolve` / `applyWord` / `together` 一个字都不重叠 ——
 * 所以旧的 13 条 `MoodStoreTest` 天然不受影响,这里也**不许去动它们**。
 *
 * ## 错了会怎样(所以为什么值得钉)
 *
 * 一样**不崩、不报错,只是让人不对了**:
 *  - 符号写反 → 她越被忽视越高兴、活办砸了反而涨心情;
 *  - **顺手推了「上次见你」**(★★ 最要命的一条)→ 她越主动干活、越显得他「刚来过」,
 *    久不露面的账被一并抹掉,而且**从此再也算不出「你多久没来」**;
 *  - 顺手给了亲密度 → 一天十件事就把那个**慢变量**吹爆,「慢慢升温」当场没了;
 *  - 半夜那一笔记成了「只要此刻是深夜就记」→ 凌晨 2 点说二十句话 = 心情 +40。
 *
 * 所以下面每一条**正例**都配一条**反例**:该涨的必须涨,**不该涨的必须不涨**。
 *
 * ★ 时钟的读法一律在调用方(见 [MoodStore.localDay] / `localHour`),
 *   这里收的是**已经算好的数字** —— 「23 点」就传 `23`,零时区负担。
 */
class MoodMathFourThingsTest {

    /** 出厂那份之外的默认值,在这里写死一遍,好让断言看得懂。 */
    private val DONE = MoodTuning().jobDoneMood        // 8
    private val FAIL = MoodTuning().jobFailMoodDrop    // 6
    private val IGNORE = MoodTuning().ignoredMoodDrop  // 4
    private val NIGHT = MoodTuning().nightMood         // 2

    /**
     * 照出厂那份造一个人。
     *
     * ★ `lastSeenSec` 默认是**一个非零的过去时刻**,不是 `0` ——
     *   因为下面有一组测试专门钉「这四个入口**不许推**上次见你」;
     *   夹具要是 `0`,那句断言就弱得看不出来(推成了「此刻」也照样能过)。
     */
    private fun s(
        mood: Int = 60,
        intimacy: Int = 25,
        seen: Long = 1_700_000_000L,
        days: Int = 0,
        lastDay: Long = -1L,
    ) = MoodStore.State(
        lastSeenSec = seen,
        mood = mood,
        intimacy = intimacy,
        daysTogether = days,
        lastTogetherDay = lastDay,
    )

    // ================= 一、活办成了 =================

    @Test
    fun `活办成了 —— 心情涨出厂那一点`() {
        assertEquals(60 + DONE, MoodMath.jobDone(s()).mood)
    }

    @Test
    fun `活办成了涨多少拨大,同一件事涨得更多`() {
        val t = MoodTuning(jobDoneMood = 20)
        assertEquals(80, MoodMath.jobDone(s(), t).mood)
    }

    @Test
    fun `活办成了 —— 心情撞到顶就封在 100,不越界`() {
        val t = MoodTuning(jobDoneMood = 50)
        val a = MoodMath.jobDone(s(mood = 95), t)
        assertEquals(100, a.mood)
        assertTrue(a.mood <= 100)
    }

    @Test
    fun `活办成了 —— 心情能低于地板,这一项不管地板`() {
        // ★ `moodFloor` 是 [MoodMath.evolve] 那一笔账的东西(「她一个人待着不会蔫穿」),
        //   不是「任何一笔都夹在它上面」。这里钉的就是这条分工:
        //   拨成 0 的地板之后,办成一件好事仍然从 0 涨上去,不会因为地板而先被抬到 25。
        val t = MoodTuning(jobDoneMood = 5, moodFloor = 25)
        assertEquals(5, MoodMath.jobDone(s(mood = 0), t).mood)
    }

    // ================= 二、活办砸了 =================

    @Test
    fun `活办砸了 —— 心情掉出厂那一点`() {
        assertEquals(60 - FAIL, MoodMath.jobFailed(s()).mood)
    }

    @Test
    fun `办砸了掉多少是正数说的 —— 拨大是掉得更多,不是加更多`() {
        // ★ 这一条钉的是符号。参数写成「掉多少」而不是「涨多少」之后,
        //   代码里那个负号要是漏了,这一句会从 40 变成 80 —— 而她突然变得**越办砸越高兴**。
        assertEquals(40, MoodMath.jobFailed(s(), MoodTuning(jobFailMoodDrop = 20)).mood)
    }

    @Test
    fun `活办砸了掉到底 —— 不会掉成负数`() {
        val t = MoodTuning(jobFailMoodDrop = 50)
        val a = MoodMath.jobFailed(s(mood = 3), t)
        assertEquals(0, a.mood)
        assertTrue(a.mood >= 0)
    }

    // ================= 三、她主动开口你没理 =================

    @Test
    fun `她主动开口你没理 —— 心情掉出厂那一点`() {
        assertEquals(60 - IGNORE, MoodMath.ignored(s()).mood)
    }

    @Test
    fun `没理她掉多少拨大,她更难过`() {
        assertEquals(50, MoodMath.ignored(s(), MoodTuning(ignoredMoodDrop = 10)).mood)
    }

    @Test
    fun `没理她 —— 一样不会掉成负数`() {
        val a = MoodMath.ignored(s(mood = 1), MoodTuning(ignoredMoodDrop = 50))
        assertEquals(0, a.mood)
        assertTrue(a.mood >= 0)
    }

    @Test
    fun `关了这一项 —— 掉 0 就是「不在意」,不是「翻过来涨」`() {
        // ★ 界面上拨不到 0(下限是 1),但配置能手改。手改出来的 0 必须是**无反应**。
        assertEquals(60, MoodMath.ignored(s(), MoodTuning(ignoredMoodDrop = 0)).mood)
        assertEquals(60, MoodMath.jobFailed(s(), MoodTuning(jobFailMoodDrop = 0)).mood)
        assertEquals(60, MoodMath.jobDone(s(), MoodTuning(jobDoneMood = 0)).mood)
    }

    // ================= 四、你的作息(半夜还陪着) =================

    @Test
    fun `几点算半夜 —— 凌晨 4 点算,5 点整不算`() {
        // ★ 半开区间 `[0, 5)`:4:59 算,5:00 整不算。差一分钟是两个人。
        assertTrue(MoodMath.isNightHour(0))
        assertTrue(MoodMath.isNightHour(4))
        assertFalse(MoodMath.isNightHour(5))
        assertFalse(MoodMath.isNightHour(23))
        assertFalse(MoodMath.isNightHour(12))
    }

    @Test
    fun `从傍晚跨进深夜 —— 记这一笔`() {
        val a = MoodMath.lateNight(s(), nowHour = 1, wasHour = 23)
        assertEquals(60 + NIGHT, a.mood)
    }

    @Test
    fun `★★ 已经在深夜里头 —— 不记`() {
        // ★★ 这是这一项的正身。判据是「**跨进来**」,不是「**待在里头**」。
        //   写成后者的话,凌晨两点跟他聊二十句就是 心情 +40 —— 那不是「陪我到很晚」,
        //   那是**同一件事记了二十遍**。
        assertEquals(60, MoodMath.lateNight(s(), nowHour = 2, wasHour = 1).mood)
        assertEquals(60, MoodMath.lateNight(s(), nowHour = 4, wasHour = 3).mood)
    }

    @Test
    fun `从深夜聊到天亮 —— 不记`() {
        // 1 点 → 23 点(同一段对话一路聊到第二天晚上):出来那一步不该有反应
        assertEquals(60, MoodMath.lateNight(s(), nowHour = 23, wasHour = 1).mood)
    }

    @Test
    fun `白天到白天 —— 不记`() {
        assertEquals(60, MoodMath.lateNight(s(), nowHour = 15, wasHour = 14).mood)
    }

    @Test
    fun `白天跨进深夜的边界 —— 5 点跨到 4 点算,4 点跨到 5 点不算`() {
        // 5 点是白天,4 点是深夜 → 跨进来了
        assertEquals(60 + NIGHT, MoodMath.lateNight(s(), nowHour = 4, wasHour = 5).mood)
        // 反方向:从深夜里走出去
        assertEquals(60, MoodMath.lateNight(s(), nowHour = 5, wasHour = 4).mood)
    }

    @Test
    fun `半夜陪着涨多少拨大,账跟着走`() {
        val t = MoodTuning(nightMood = 10)
        assertEquals(70, MoodMath.lateNight(s(), nowHour = 2, wasHour = 22, t = t).mood)
    }

    @Test
    fun `半夜陪着也会撞顶 —— 封在 100`() {
        val t = MoodTuning(nightMood = 50)
        assertEquals(100, MoodMath.lateNight(s(mood = 90), 1, 23, t).mood)
    }

    @Test
    fun `不在那一段的时候,返回的是原来那一份`() {
        // ★ 同 `MoodMath.together` 的早退那条:不该造垃圾对象。
        //   把早退写成 `return s.copy(...)` 的话,值一样、**对象不一样**,这条会红。
        val a = s()
        assertSame(a, MoodMath.lateNight(a, 14, 13))
    }

    // ================= ★★ 四条共同的不许(这一组才是这个文件的重点) =================

    @Test
    fun `★★ 四条都不推「上次见你」—— 那是别的一笔账`() {
        // ★★ 这条钉的是这个项目栽过的那个坑(见 `MoodStore.peek` 的注释:
        //   「她自言自语不算他来过」)。
        //   推了的话,她**越主动干活、越显得他刚来过** —— 于是「你多久没来」那笔账
        //   被自己的勤奋抹平,而且从此**永远算不出来**。四条一条都不许例外。
        val before = s()
        val seen = before.lastSeenSec

        assertEquals(seen, MoodMath.jobDone(before).lastSeenSec)
        assertEquals(seen, MoodMath.jobFailed(before).lastSeenSec)
        assertEquals(seen, MoodMath.ignored(before).lastSeenSec)
        assertEquals(seen, MoodMath.lateNight(before, 1, 23).lastSeenSec)
    }

    @Test
    fun `四条都只碰心情 —— 亲密度一分都不给`() {
        // ★★ 亲密度是**慢变量**(由相处和说话驱动)。办一件事就给 +2 的话,
        //   他一天让她跑十件事就是 +20 —— 先被这个吹爆的是「慢慢升温」。
        val before = s(intimacy = 41)
        assertEquals(41, MoodMath.jobDone(before).intimacy)
        assertEquals(41, MoodMath.jobFailed(before).intimacy)
        assertEquals(41, MoodMath.ignored(before).intimacy)
        assertEquals(41, MoodMath.lateNight(before, 1, 23).intimacy)
    }

    @Test
    fun `四条都只碰心情 —— 日子数和「上次相处是哪天」一个字都不动`() {
        // ★ `daysTogether` / `lastTogetherDay` 归 [MoodMath.together] 一个人管。
        //   这四条要是顺手碰了,「一天算一天」的账会乱 —— 而它乱起来完全看不出来。
        val before = s(days = 7, lastDay = 42L)
        assertEquals(before.copy(mood = before.mood + DONE), MoodMath.jobDone(before))
        assertEquals(before.copy(mood = before.mood - FAIL), MoodMath.jobFailed(before))
        assertEquals(before.copy(mood = before.mood - IGNORE), MoodMath.ignored(before))
        assertEquals(before.copy(mood = before.mood + NIGHT), MoodMath.lateNight(before, 1, 23))
    }

    @Test
    fun `除了心情,别的一个字段都不许动`() {
        // ★ 拿「它该变成的样子」和「它真变成的样子」**整份比** ——
        //   比逐个字段断言强:以后往 State 里加一个新字段,这条路会**自动**把它一起比进去,
        //   不用谁记得回来补一行。
        val before = s(days = 3, lastDay = 9L)

        assertEquals(before.copy(mood = before.mood + DONE), MoodMath.jobDone(before))
        assertEquals(before.copy(mood = before.mood - FAIL), MoodMath.jobFailed(before))
        assertEquals(before.copy(mood = before.mood - IGNORE), MoodMath.ignored(before))
        assertEquals(before.copy(mood = before.mood + NIGHT), MoodMath.lateNight(before, 0, 23))
    }

    @Test
    fun `四条都是纯的 —— 传进去的那一份不会被改`() {
        // ★ `State` 是 `data class`(val),理论上改不动;但这一条钉的是
        //   「别哪天有人把它改成 var 然后就地改」—— 那会让调用方手里的旧值**当场变样**,
        //   而 `onInteraction` 那种「用旧值算这一步、用新值算下一步」的写法**全靠它不变**。
        val before = s()
        val snapshot = before.toString()
        MoodMath.jobDone(before)
        MoodMath.jobFailed(before)
        MoodMath.ignored(before)
        MoodMath.lateNight(before, 1, 23)
        assertEquals(snapshot, before.toString())
    }
}
