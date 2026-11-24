package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 她「脾气」那十几个数字的测试 —— 纯 JVM。
 *
 * ★ 这一层为什么必须钉:
 * 数字拨错了**不会崩**,它只会让她「性格慢慢变得不对」—— 说话还是通的、规矩还是守的,
 * 只是人不是原来那个人了。那种坏法在真机上分不出来。
 *
 * ★★ 而这里**最要紧的一条**是 `出厂那份就是今天写死的那些数` +
 * `不传参数和传一份出厂脾气,结果一模一样` —— 它们是「现有 13 条 MoodStoreTest
 * 一条都不用改」的全部依据。**这两条红了,说明我动了她的行为,必须停下来看。**
 */
class MoodTuningTest {

    /** 秒 → 方便写「几小时之后」。 */
    private fun hours(h: Int): Long = h * 3600L

    // ---- ★★ 出厂那份 = 改名之前写死的那些数,一位不差 ----

    @Test
    fun `出厂那份就是今天写死的那些数`() {
        val d = MoodTuning()
        // 右列是改之前 MoodMath 里写死的字面量,一个都没有「顺手对齐」过
        assertEquals(6, d.sweetMood)
        assertEquals(2, d.sweetIntimacy)
        assertEquals(10, d.coldMoodDrop)
        assertEquals(2, d.coldIntimacyDrop)
        assertEquals(8, d.mendMood)
        assertEquals(1, d.mendIntimacy)
        assertEquals(1, d.plainMood)
        assertEquals(0, d.plainIntimacy)
        assertEquals(2, d.driftPerHour)
        assertEquals(25, d.driftCap)
        assertEquals(25, d.moodFloor)
        assertEquals(72, d.neglectHours)
        assertEquals(3, d.neglectDrop)
    }

    @Test
    fun `不传参数和传一份出厂脾气,结果一模一样`() {
        // ★★ 这是「现有 13 条 MoodStoreTest 一条都不用改」的全部依据:
        //    旧调用点不传参数 → 走默认值 → 和改名之前逐位相同。
        val s = MoodStore.State(lastSeenSec = 0, mood = 73, intimacy = 41)
        val now = hours(5)
        assertEquals(MoodMath.evolve(s, now), MoodMath.evolve(s, now, MoodTuning()))
        for (bucket in listOf("sweet", "cold", "mend", "plain", "其它任何东西")) {
            assertEquals(
                MoodMath.applyWord(s, bucket),
                MoodMath.applyWord(s, bucket, MoodTuning()),
            )
        }
    }

    @Test
    fun `时间那两笔账,默认值和以前算出来的一样`() {
        val s = MoodStore.State(lastSeenSec = 0, mood = 80, intimacy = 50)
        // 12 小时:一小时漂 2 → 12*2 = 24,还没撞封顶 25
        assertEquals(56, MoodMath.evolve(s, hours(12)).mood)
        // 20 小时:20*2 = 40 → 撞封顶,只掉 25
        assertEquals(55, MoodMath.evolve(s, hours(20)).mood)
        // 73 小时:超过 72,亲密度开始掉
        assertEquals(47, MoodMath.evolve(s, hours(73)).intimacy)
        // 71 小时:还没到那一档,亲密度一分不掉
        assertEquals(50, MoodMath.evolve(s, hours(71)).intimacy)
    }

    // ---- 拨大了、拨小了,账真的跟着走吗 ----

    @Test
    fun `好听话涨多少拨大,同一句涨得更多`() {
        val s = MoodStore.State(0, 50, 50)
        assertEquals(56, MoodMath.applyWord(s, "sweet").mood)
        assertEquals(62, MoodMath.applyWord(s, "sweet", MoodTuning(sweetMood = 12)).mood)
    }

    @Test
    fun `冷话扣多少是正数说的 —— 拨大是扣得更多,不是加更多`() {
        // ★ 这一条钉的是符号。参数写成「扣多少」而不是「加多少」之后,
        //   代码里那个负号要是漏了,这一句会从 40 变成 60 —— 而她突然变得越骂越高兴。
        val s = MoodStore.State(0, 50, 50)
        assertEquals(40, MoodMath.applyWord(s, "cold").mood)
        assertEquals(30, MoodMath.applyWord(s, "cold", MoodTuning(coldMoodDrop = 20)).mood)
    }

    @Test
    fun `哄一句涨多少拨大,破功得更快`() {
        val s = MoodStore.State(0, 40, 50)
        assertEquals(48, MoodMath.applyWord(s, "mend").mood)
        assertEquals(60, MoodMath.applyWord(s, "mend", MoodTuning(mendMood = 20)).mood)
    }

    @Test
    fun `每小时蔫多少拨大,她掉得更快`() {
        val s = MoodStore.State(0, 80, 50)
        val h = hours(3)
        assertEquals(74, MoodMath.evolve(s, h).mood)                                 // 出厂:掉 6
        assertEquals(77, MoodMath.evolve(s, h, MoodTuning(driftPerHour = 1)).mood)   // 拨慢:掉 3
        assertEquals(68, MoodMath.evolve(s, h, MoodTuning(driftPerHour = 4)).mood)   // 拨快:掉 12
    }

    @Test
    fun `心情地板拨到 0 —— 她可以真的蔫到底`() {
        val s = MoodStore.State(0, 80, 50)
        // 封顶也得一起放开,否则掉不到底就撞上限了
        val t = MoodTuning(moodFloor = 0, driftCap = 100)
        assertEquals(0, MoodMath.evolve(s, hours(100), t).mood)
    }

    @Test
    fun `多久不见算很久拨短一点,她更早开始掉亲密度`() {
        val s = MoodStore.State(0, 60, 50)
        val impatient = MoodTuning(neglectHours = 6)
        assertEquals(47, MoodMath.evolve(s, hours(7), impatient).intimacy)   // 过了 6 小时 → 掉
        assertEquals(50, MoodMath.evolve(s, hours(5), impatient).intimacy)   // 还没到 → 不掉
        assertEquals(50, MoodMath.evolve(s, hours(7)).intimacy)              // 出厂那份要 72 小时
    }

    // ---- 存盘 / 读回 ----

    @Test
    fun `存了再读,十三个数一个不差`() {
        val t = MoodTuning(
            sweetMood = 3, sweetIntimacy = 4, coldMoodDrop = 5, coldIntimacyDrop = 6,
            mendMood = 7, mendIntimacy = 8, plainMood = 9, plainIntimacy = 10,
            driftPerHour = 11, driftCap = 12, moodFloor = 13, neglectHours = 14, neglectDrop = 15,
        )
        assertEquals(t, MoodTuningMath.fromMap(MoodTuningMath.toMap(t)))
    }

    @Test
    fun `老配置里没有新加的那个数 —— 读它自带默认值,其余项一个字不丢`() {
        // ★ 这正是 `reading-a-defaults-chain` 那个毛病要防的:
        //   以后加一项,老配置**不该**因为少一个键就整份退回出厂。
        val old = mapOf("sweetMood" to 9, "moodFloor" to 40)
        val t = MoodTuningMath.fromMap(old)
        assertEquals(9, t.sweetMood)
        assertEquals(40, t.moodFloor)
        assertEquals(MoodTuning(sweetMood = 9, moodFloor = 40), t)   // 其余十一项全是出厂值
    }

    @Test
    fun `配置里混进不认识的东西 —— 忽略它,不崩`() {
        val t = MoodTuningMath.fromMap(mapOf("sweetMood" to 9, "未来加的某一项" to 1))
        assertEquals(MoodTuning(sweetMood = 9), t)
    }

    @Test
    fun `名字是钉住的 —— 改一个键名,等于把用户拨过的那个数悄悄丢掉`() {
        // ★ 这条看着蠢,挡的是一个真坑:字段改名不会编译错、不会崩,
        //   只会让**已经存下的那个数**读不出来 → 静默退回出厂,而他以为还在。
        // ★ 2026-10-07:名字集合从 13 个扩到 15 个 —— 步 8「相处越久越亲」按设计
        //   长出两个可拨的数(togetherEveryDays / togetherGain)。这不是「改红」,
        //   是这一项本来就该多两行;把它们漏在外面,才是「界面上拨不动、也看不见」的死数。
        // ★ 同一天再扩到 19 个 —— 步 9「别的四件事影响她」又长出四个(jobDoneMood /
        //   jobFailMoodDrop / ignoredMoodDrop / nightMood)。同一条理由,同一个规矩。
        assertEquals(
            setOf(
                "sweetMood", "sweetIntimacy", "coldMoodDrop", "coldIntimacyDrop",
                "mendMood", "mendIntimacy", "plainMood", "plainIntimacy",
                "togetherEveryDays", "togetherGain",
                "driftPerHour", "driftCap", "moodFloor", "neglectHours", "neglectDrop",
                "jobDoneMood", "jobFailMoodDrop", "ignoredMoodDrop", "nightMood",
            ),
            MoodTuningMath.toMap(MoodTuning()).keys,
        )
    }

    // ---- 夹范围 ----

    @Test
    fun `拨过头会被夹住 —— 界面上按不出格,但配置是能手改的`() {
        val wild = MoodTuning(
            sweetMood = 9999,
            coldMoodDrop = -50,
            driftPerHour = -1,
            driftCap = 9999,
            moodFloor = -3,
            neglectHours = 0,
            neglectDrop = 9999,
        )
        val t = MoodTuningMath.sanitize(wild)
        assertEquals(50, t.sweetMood)
        assertEquals(0, t.coldMoodDrop)
        assertEquals(0, t.driftPerHour)
        assertEquals(100, t.driftCap)
        assertEquals(0, t.moodFloor)
        assertEquals(1, t.neglectHours)      // ★ 下限 1:0 小时 = 「一见面就算很久」,不是人话
        assertEquals(50, t.neglectDrop)
    }

    @Test
    fun `出厂那份夹了还是它自己`() {
        // 夹范围不许把出厂值动到 —— 动了就是「静默换了默认性格」
        assertEquals(MoodTuning(), MoodTuningMath.sanitize(MoodTuning()))
    }

    // ---- 界面上那一页:每个数都得有一行 ----

    @Test
    fun `界面上那几行,一个数都不许落下`() {
        // ★ 这条挡的是「加了新数字、忘了给界面加一行」—— 那个数会永远停在出厂值,
        //   而他**不知道有这东西**,界面也不会报错。这正是这个项目最恨的那种坏法。
        assertEquals(
            MoodTuningMath.toMap(MoodTuning()).size,
            MoodTuningMath.knobs().size,
        )
    }

    @Test
    fun `没有两行读写同一个数 —— 复制粘贴漏改字段,这条会红`() {
        // ★ 抄一行改标签最容易漏的就是里面那两个 lambda。症状是「拨这一行,那一行跟着动」,
        //   而两行都是合法数字,看不出哪一行是坏的。
        val knobs = MoodTuningMath.knobs()
        knobs.forEachIndexed { i, k ->
            // 拿一个**每一项都互不相同**的值,这样「改错了字段」一定能被看出来
            val probe = MoodTuning(
                sweetMood = 1, sweetIntimacy = 2, coldMoodDrop = 3, coldIntimacyDrop = 4,
                mendMood = 5, mendIntimacy = 6, plainMood = 7, plainIntimacy = 8,
                togetherEveryDays = 14, togetherGain = 15,
                driftPerHour = 9, driftCap = 10, moodFloor = 11, neglectHours = 12, neglectDrop = 13,
                jobDoneMood = 16, jobFailMoodDrop = 17, ignoredMoodDrop = 18, nightMood = 19,
            )
            val after = k.set(probe, 21 + i)
            assertEquals("第 $i 行:拨完读回来得是它自己", 21 + i, k.get(after))
            knobs.forEachIndexed { j, other ->
                if (j == i) return@forEachIndexed
                assertEquals(
                    "第 $i 行动了第 $j 行(${other.label})的字段",
                    other.get(probe), other.get(after),
                )
            }
        }
    }

    @Test
    fun `每一行的标签都是人话,不是字段名`() {
        // ★ 那一页是给他拨的,不是给我看的。
        val names = setOf(
            "sweetMood", "sweetIntimacy", "coldMoodDrop", "coldIntimacyDrop",
            "mendMood", "mendIntimacy", "plainMood", "plainIntimacy",
            "togetherEveryDays", "togetherGain",
            "driftPerHour", "driftCap", "moodFloor", "neglectHours", "neglectDrop",
            "jobDoneMood", "jobFailMoodDrop", "ignoredMoodDrop", "nightMood",
        )
        MoodTuningMath.knobs().forEach { k ->
            assertFalse("这一行直接把字段名端上去了:${k.label}", k.label in names)
            assertTrue("这一行太短了,不像一句话:${k.label}", k.label.length >= 6)
        }
    }

    @Test
    fun `按一下至少要走 1 —— 步长 0 是个拨不动的旋钮`() {
        MoodTuningMath.knobs().forEach {
            assertTrue("${it.label} 的步长是 ${it.step}", it.step >= 1)
        }
    }

    @Test
    fun `每一行按住加号,都会被夹住 —— 界面上按不出格`() {
        // ★ 界面**不自己判上下限**(那样就是同一张表抄两遍):它只管 +step,
        //   夹的事交给 sanitize。这条钉的就是「照它来的话真的能夹住」。
        MoodTuningMath.knobs().forEach { k ->
            var t = MoodTuning()
            repeat(400) { t = MoodTuningMath.sanitize(k.set(t, k.get(t) + k.step)) }
            val hi = k.get(t)
            // 再按一下不许再动 —— 到头了
            assertEquals("${k.label} 的高位没夹住", hi, k.get(MoodTuningMath.sanitize(k.set(t, hi + k.step))))
            assertTrue("${k.label} 冲出了 0..1000", hi in 0..1000)

            var d = MoodTuning()
            repeat(400) { d = MoodTuningMath.sanitize(k.set(d, k.get(d) - k.step)) }
            val lo = k.get(d)
            assertEquals("${k.label} 的低位没夹住", lo, k.get(MoodTuningMath.sanitize(k.set(d, lo - k.step))))
            assertTrue("${k.label} 掉到负数了", lo >= 0)
        }
    }
}
