package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 她的「内分泌系统」的测试 —— 纯 JVM,不碰 Android / org.json。
 *
 * 为什么值得钉:这层规则改错的表现不是崩溃,是「她的性格突然不对」
 * (一句「想你」直接哄上天 / 三天不来她还是笑嘻嘻),真机上极难发现。
 */
class MoodStoreTest {

    private fun s(mood: Int = 60, intimacy: Int = 40, lastSeenSec: Long = 0) =
        MoodStore.State(lastSeenSec = lastSeenSec, mood = mood, intimacy = intimacy)

    // ---- classify:话的温度 ----

    @Test
    fun `甜的归甜`() {
        assertEquals("sweet", MoodMath.classify("好想你啊"))
        assertEquals("sweet", MoodMath.classify("今天好厉害"))
        assertEquals("sweet", MoodMath.classify("谢谢你啦"))
    }

    @Test
    fun `凉的归凉`() {
        assertEquals("cold", MoodMath.classify("闭嘴"))
        assertEquals("cold", MoodMath.classify("滚"))
        assertEquals("cold", MoodMath.classify("别吵,烦死了"))
    }

    @Test
    fun `哄的归哄`() {
        assertEquals("mend", MoodMath.classify("对不起嘛,别生气了"))
        assertEquals("mend", MoodMath.classify("我错了"))
    }

    @Test
    fun `平常话是淡淡的陪`() {
        assertEquals("plain", MoodMath.classify("帮我打开微信"))
        assertEquals("plain", MoodMath.classify("看看电脑上开着什么"))
    }

    @Test
    fun `「讨厌」不算凉 —— 那可能是撒娇`() {
        // 分类桶的判据故意不含「讨厌」:中文里它十有八九是娇嗔
        assertFalse(MoodMath.classify("讨厌啦你").contains("cold"))
    }

    // ---- applyWord:一句冲击,上限是硬的 ----

    @Test
    fun `一句甜话不能哄上天`() {
        val after = MoodMath.applyWord(s(mood = 60), "sweet")
        assertEquals(66, after.mood)          // +6,不是 +60
        assertEquals(42, after.intimacy)      // +2
    }

    @Test
    fun `一句凉话不能一巴掌拍死`() {
        val after = MoodMath.applyWord(s(mood = 40), "cold")
        assertEquals(30, after.mood)
        assertEquals(38, after.intimacy)
    }

    @Test
    fun `上限和下限都封得住`() {
        assertEquals(100, MoodMath.applyWord(s(mood = 99), "sweet").mood)
        assertEquals(0, MoodMath.applyWord(s(mood = 3), "cold").mood)
        assertEquals(0, MoodMath.applyWord(s(intimacy = 1), "cold").intimacy)
    }

    // ---- evolve:时间补账(懒计算,不看后台死活) ----

    @Test
    fun `刚聊完心情不掉`() {
        val now = 1000L
        val after = MoodMath.evolve(s(mood = 70, lastSeenSec = now - 60), now)  // 1 分钟
        assertEquals(70, after.mood)
    }

    @Test
    fun `越久越蔫但有地板`() {
        val now = 1000L
        val sixH = MoodMath.evolve(s(mood = 70, lastSeenSec = now - 6 * 3600), now)
        assertEquals(58, sixH.mood)           // 70 - 12
        val twoDays = MoodMath.evolve(s(mood = 70, lastSeenSec = now - 48 * 3600), now)
        assertEquals(45, twoDays.mood)        // 封顶 -25
        val floor = MoodMath.evolve(s(mood = 30, lastSeenSec = now - 100 * 3600), now)
        assertEquals(25, floor.mood)          // 地板:她不会自己崩溃到 0
    }

    @Test
    fun `亲密度只在很久不见时缓降`() {
        val now = 1000L
        assertEquals(40, MoodMath.evolve(s(intimacy = 40, lastSeenSec = now - 48 * 3600), now).intimacy)
        assertEquals(37, MoodMath.evolve(s(intimacy = 40, lastSeenSec = now - 80 * 3600), now).intimacy)
    }

    // ---- line:演出的口径 ----

    @Test
    fun `状态行说处境不说数值`() {
        val now = 1000L
        val text = MoodMath.line(s(mood = 40, intimacy = 50, lastSeenSec = now - 30 * 3600), now)
        assertTrue(text.contains("有点委屈"))       // 30h < 72h 那档
        assertTrue(text.contains("有点蔫"))
        assertFalse(Regex("""\d+(/100)""").containsMatchIn(text))   // 不许念数字
        assertTrue(text.contains("不许"))
    }

    @Test
    fun `很久不见是又想又气`() {
        val now = 1000L
        val text = MoodMath.line(s(mood = 50, intimacy = 60, lastSeenSec = now - 96 * 3600), now)
        assertTrue(text.contains("有点气"))
    }
}
