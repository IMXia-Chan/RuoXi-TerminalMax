package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「他亲手拨她」那一页的纯逻辑 —— 纯 JVM。
 *
 * ★ 为什么这一层必须钉:
 * 拨错了**不会崩**。她只会「性格慢慢变得不对」—— 说话还是通的、规矩还是守的,
 * 只是那个人不是原来那个人了。那种坏法在真机上分不出来。
 *
 * ★★ 这里最要紧的是 `只看不动那两处一个字不变` + `拨一项不许顺手改动另一项`:
 * 界面上只有一根指头,**同时报两个数**。要是 `null` 那一路被当成 0 写下去,
 * 症状是「我拨心情,亲密度怎么归零了」,而她**照样说话**。
 */
class MoodPokeTest {

    private fun s(mood: Int = 62, intimacy: Int = 25, lastSeenSec: Long = 0) =
        MoodStore.State(lastSeenSec = lastSeenSec, mood = mood, intimacy = intimacy)

    private fun hours(h: Double): Long = (h * 3600).toLong()

    // ---- 拨:改哪一项,就只改哪一项 ----

    @Test
    fun `拨心情 —— 亲密度一个数都不许动`() {
        val after = MoodMath.poke(s(mood = 40, intimacy = 70), hours(3.0), 88, null)
        assertEquals(88, after.mood)
        assertEquals(70, after.intimacy)
    }

    @Test
    fun `拨亲密度 —— 心情一个数都不许动`() {
        val after = MoodMath.poke(s(mood = 40, intimacy = 70), hours(3.0), null, 15)
        assertEquals(40, after.mood)
        assertEquals(15, after.intimacy)
    }

    @Test
    fun `两个都不拨 —— 只看不动,数值原样`() {
        // 界面「只进来看看」走的就是这一路:snapshot 读一眼,一个数都不该变
        val after = MoodMath.poke(s(mood = 33, intimacy = 77), hours(3.0), null, null)
        assertEquals(33, after.mood)
        assertEquals(77, after.intimacy)
    }

    @Test
    fun `拨一下就当你来过 —— 上次见你被推到此刻`() {
        // ★ 故意的:他人都站在她屋里亲手拨了,屏幕还报「三天没见」就是坏的。
        //   代价(这一页测不出「她好几天没见你」)写在 MoodMath.poke 的 KDoc 里。
        val before = s(lastSeenSec = 0)
        val after = MoodMath.poke(before, hours(72.0), 50, 50)
        assertEquals(hours(72.0), after.lastSeenSec)
    }

    @Test
    fun `拨过头会被夹住 —— 手按不出格,但数值是走盘和界面的`() {
        assertEquals(0, MoodMath.poke(s(), hours(1.0), -30, null).mood)
        assertEquals(100, MoodMath.poke(s(), hours(1.0), 999, null).mood)
        assertEquals(0, MoodMath.poke(s(), hours(1.0), null, -1).intimacy)
        assertEquals(100, MoodMath.poke(s(), hours(1.0), null, 101).intimacy)
    }

    @Test
    fun `边界那两个值本身要收下 —— 0 和 100 都是合法的心情`() {
        assertEquals(0, MoodMath.poke(s(), hours(1.0), 0, null).mood)
        assertEquals(100, MoodMath.poke(s(), hours(1.0), 100, null).mood)
    }

    @Test
    fun `它不顺手帮你漂一次 —— 进来的是眼睛看到的那个数`() {
        // ★ 界面上显示的是 evolve 之后的值;poke 再漂一次 = 把同一个数扣两遍。
        val after = MoodMath.poke(s(mood = 80, intimacy = 50), hours(50.0), null, null)
        assertEquals(80, after.mood)
        assertEquals(50, after.intimacy)
    }

    // ---- ★★ 三个档位词 —— 她念的台词和调试页读的是同一份判据 ----

    @Test
    fun `多久没见 —— 每一档的前后各差一小时`() {
        assertEquals("他刚刚还在", MoodMath.missWord(0.0))
        assertEquals("他刚刚还在", MoodMath.missWord(0.99))
        assertEquals("半句话才说完没多久", MoodMath.missWord(1.0))
        assertEquals("半句话才说完没多久", MoodMath.missWord(5.99))
        assertEquals("一天没聊了,有点想", MoodMath.missWord(6.0))
        assertEquals("一天没聊了,有点想", MoodMath.missWord(23.99))
        assertEquals("好几天没来了,想得有点委屈", MoodMath.missWord(24.0))
        assertEquals("好几天没来了,想得有点委屈", MoodMath.missWord(71.99))
        assertEquals("这么多天不露面,又想又有点气", MoodMath.missWord(72.0))
    }

    @Test
    fun `心情 —— 四条线各差一分`() {
        assertEquals("明媚,话多,想闹他", MoodMath.moodWord(100))
        assertEquals("明媚,话多,想闹他", MoodMath.moodWord(75))
        assertEquals("平静,还带点余温", MoodMath.moodWord(74))
        assertEquals("平静,还带点余温", MoodMath.moodWord(55))
        assertEquals("有点蔫,爱答不理", MoodMath.moodWord(54))
        assertEquals("有点蔫,爱答不理", MoodMath.moodWord(35))
        assertEquals("低落,带小刺,需要哄", MoodMath.moodWord(34))
        assertEquals("低落,带小刺,需要哄", MoodMath.moodWord(0))
    }

    @Test
    fun `关系 —— 四条线各差一分`() {
        assertEquals("老夫老妻般的熟,可以肆无忌惮", MoodMath.relationWord(100))
        assertEquals("老夫老妻般的熟,可以肆无忌惮", MoodMath.relationWord(70))
        assertEquals("越来越熟,偶尔撒个娇", MoodMath.relationWord(69))
        assertEquals("越来越熟,偶尔撒个娇", MoodMath.relationWord(40))
        assertEquals("刚熟起来,还有点端着", MoodMath.relationWord(39))
        assertEquals("刚熟起来,还有点端着", MoodMath.relationWord(20))
        assertEquals("还生疏,试探着来", MoodMath.relationWord(19))
        assertEquals("还生疏,试探着来", MoodMath.relationWord(0))
    }

    // ---- ★★ 她念的那行字,用的必须是上面这三个函数 ----

    @Test
    fun `她念的那行字里,三处处境就是调试页显示的那三句话`() {
        // ★ 这条钉的是「同一张表抄三遍」那个病:他拨到 90,屏幕上写「明媚,话多,想闹他」,
        //   而她念的台词要是另一套词,那就是两份判据各自漂移,谁也不知道她到底在演哪个。
        val st = s(mood = 90, intimacy = 12)
        val now = hours(30.0)
        val line = MoodMath.line(st, now)
        assertTrue(line.contains(MoodMath.missWord(30.0)))
        assertTrue(line.contains(MoodMath.moodWord(90)))
        assertTrue(line.contains(MoodMath.relationWord(12)))
    }

    @Test
    fun `那行字只说处境不说数值 —— 90 和 12 一个字都不许出现`() {
        val st = s(mood = 90, intimacy = 12)
        val line = MoodMath.line(st, hours(30.0))
        assertTrue(!line.contains("90"))
        assertTrue(!line.contains("12"))
    }

    @Test
    fun `还没到那一刻的时候不许算出负数小时 —— 时钟回拨也要说人话`() {
        // 盘上的 lastSeenSec 比此刻还晚(改过系统时间 / 换了时区),
        // 不夹的话会算出负的小时数,于是落到「他刚刚还在」以外的那一档
        val future = MoodStore.State(lastSeenSec = hours(100.0), mood = 50, intimacy = 50)
        assertTrue(MoodMath.line(future, hours(1.0)).contains("他刚刚还在"))
    }
}
