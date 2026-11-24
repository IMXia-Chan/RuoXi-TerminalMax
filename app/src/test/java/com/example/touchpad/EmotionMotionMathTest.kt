package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「语气 → 动作」那张表,和「这一次该不该换」那个判断的钉子。
 *
 * ★ 挑用例的判据不是边界条件的趣味,是**每一种「她不动 / 她抽搐 / 她换错」的真实长相**:
 *
 * | 判错了会怎样 | 你看到的是 |
 * |---|---|
 * | 表里少一个语气 | 那个语气她**永远不动**,而且不报错 |
 * | 表里多一个查不到的键 | 等于没写 —— 同样不报错 |
 * | 间隔太短 | 她连说三句 = 动作被抢三次、每次从第 0 帧重来 = **抽搐** |
 * | 间隔那条判据用了「距开机多久」 | 开机后头几秒里第一条动作被吃掉,**没有任何征兆** |
 * | 表指到 pixiv 那 7 条 | 违反授权(不能进公开仓库、不能打进 APK) |
 */
class EmotionMotionMathTest {

    /**
     * ★★ `ConMarnActivity.emotionOf` 会吐出来的**那五个词**。
     *
     * 它是 `private`,这里够不着,所以**抄一份当契约**。
     * 这不是偷懒:这张表存在的全部意义就是接住那五个词 ——
     * 哪天 `emotionOf` 改了词,这条会红,**而那正是该有人来处理的那个时刻**。
     */
    private val emotionOfWords = setOf("happy", "sad", "surprised", "angry", "relaxed")

    // ------------------------------------------------------------ 表本身

    @Test
    fun `emotionOf 吐的五个词 一个不漏地分成了动和不动两组`() {
        // ★★ 这条 2026-10-17 改了契约(他改的设计,不是「测试该更新了」):
        //    原来是「五个词都必须在表里」—— 现在是「五个词**要么在表里、要么在 SILENT 里**」。
        //    保护的东西**一样**(漏一个词 = 那个语气从此没人管,且屏幕上和「正常」一模一样),
        //    只是多了「不动」这个合法出口。
        assertEquals(
            emotionOfWords,
            EmotionMotionMath.TABLE.keys + EmotionMotionMath.SILENT,
        )
        // 两组不许重叠 —— 重叠的话「动」和「不动」同时成立,谁赢看代码顺序,那不叫设计。
        assertTrue(
            "TABLE 和 SILENT 有重叠:${EmotionMotionMath.TABLE.keys intersect EmotionMotionMath.SILENT}",
            (EmotionMotionMath.TABLE.keys intersect EmotionMotionMath.SILENT).isEmpty(),
        )
    }

    @Test
    fun `会动的那三个都能查到库里的一条路径`() {
        for (w in EmotionMotionMath.TABLE.keys) {
            assertFalse("$w 在 SILENT 里却还在表里", EmotionMotionMath.isSilent(w))
            val rel = EmotionMotionMath.relativePath(w)
            assertNotNull("语气 $w 查不到动作", rel)
            assertEquals("$w 的动作必须在 ${EmotionMotionMath.DIR} 下", true, rel!!.startsWith("${EmotionMotionMath.DIR}/"))
            assertTrue("$w 的动作要以 .vrma 结尾", rel.endsWith(".vrma"))
        }
    }

    @Test
    fun `三条名字直接对得上的 别被人好心改坏`() {
        // 记忆 conmarn-emotion-to-motion:这三个是库里名字和语气词**直接对上**的。
        // 改这三条一定是有意的,不是顺手。
        assertEquals("vrm-viewer/Angry.vrma", EmotionMotionMath.relativePath("angry"))
        assertEquals("vrm-viewer/Sad.vrma", EmotionMotionMath.relativePath("sad"))
        assertEquals("vrm-viewer/Surprised.vrma", EmotionMotionMath.relativePath("surprised"))
    }

    @Test
    fun `笑只动脸和嘴 身体一下都不动`() {
        // ★★★ 他 2026-10-17 的原话:「笑不要那个动作」→「嘴巴动就行了」。
        //
        //   这一条钉的是**他的决定本身**。哪天有人觉得「笑就该有个动作」把它加回表里,
        //   这条会红 —— 那正是该问他的那个时刻,不是该改测试的那个时刻。
        assertTrue("笑(happy)必须是不动的那一档", EmotionMotionMath.isSilent("happy"))
        assertNull("笑不许查到任何动作", EmotionMotionMath.relativePath("happy"))
    }

    @Test
    fun `兜底的 relaxed 不动 它只是没听出情绪`() {
        // ★★ 他 2026-10-17:「不要这么故意,不是什么话都要动作的」。
        //
        //   `emotionOf` 的 `else` 就是 relaxed —— 那意味着**每一句没带情绪的话**都落在这里。
        //   它一旦配着动作,就是「她随便回一句都要摆个姿势」,那正是他说的故意。
        assertTrue("relaxed 必须是不动的那一档", EmotionMotionMath.isSilent("relaxed"))
        assertNull("relaxed 不许查到任何动作", EmotionMotionMath.relativePath("relaxed"))
    }

    @Test
    fun `只落在能再分发的那些来源里 pixiv 那七条一个都不许出现`() {
        // vroid-official(pixiv)禁止以可提取形式再分发 ⇒ 不能进公开仓库、不能打进 APK。
        // voxavatar 是 MIT,但它没有「情绪」这一档。
        for (rel in EmotionMotionMath.TABLE.values) {
            assertFalse("$rel 在 pixiv 那 7 条里,不能出现在默认表里", rel.startsWith("vroid-official"))
            assertFalse("$rel 在 voxavatar 里,这套情绪表不该用", rel.startsWith("voxavatar"))
        }
    }

    // ------------------------------------------------------------ 查表

    @Test
    fun `表外的语气回 null 不是回一条兜底动作`() {
        // ★「不在表里」和「文件没找到」是两件事,日志里必须分得开。
        //   回一条兜底动作的话,她会对着任何未知语气做同一个动作,而没人知道为什么。
        assertNull(EmotionMotionMath.relativePath("confused"))
        assertNull(EmotionMotionMath.relativePath(""))
        assertNull(EmotionMotionMath.relativePath("   "))
    }

    @Test
    fun `大小写和前后空白都容错`() {
        // 这个值今天由 emotionOf 直接给(小写),但一旦哪天它带了空格 / 大写,
        // 症状是「那个语气她不动」—— 一个没人会想到去查字符串的地方。
        assertEquals(EmotionMotionMath.relativePath("angry"), EmotionMotionMath.relativePath(" Angry "))
        assertEquals(EmotionMotionMath.relativePath("sad"), EmotionMotionMath.relativePath("SAD"))
    }

    // ------------------------------------------------------------ 该不该换

    @Test
    fun `从没推过 放行`() {
        assertTrue(EmotionMotionMath.shouldFire(nowMs = 100_000L, lastFireAtMs = 0L))
    }

    @Test
    fun `刚推过 不放行`() {
        assertFalse(EmotionMotionMath.shouldFire(nowMs = 100_000L, lastFireAtMs = 99_000L))
    }

    @Test
    fun `满四秒二 放行 少一毫秒 不放行`() {
        val last = 100_000L
        assertFalse(
            EmotionMotionMath.shouldFire(
                nowMs = last + EmotionMotionMath.MIN_GAP_MS - 1, lastFireAtMs = last))
        assertTrue(
            EmotionMotionMath.shouldFire(
                nowMs = last + EmotionMotionMath.MIN_GAP_MS, lastFireAtMs = last))
    }

    @Test
    fun `间隔要盖得住库里那条约四秒的动作`() {
        // vrm-viewer 那 11 条实测全是 3.93s / 119 帧。
        // 间隔比它短的话,上一条还在播就被抢走 = 抽搐。
        assertTrue(
            "间隔 ${EmotionMotionMath.MIN_GAP_MS}ms 盖不住 3930ms 的动作",
            EmotionMotionMath.MIN_GAP_MS > 3_930L,
        )
    }

    @Test
    fun `刚开机四秒内 第一条也要放行`() {
        // ★★ 这一条是「用 elapsedRealtime 当判据」最容易栽的地方:
        //   开机 2 秒时 now=2000,而 lastFireAtMs=0 ——
        //   「now - last >= 4200」碰巧不成立,于是**进房间后第一句话她不动**,
        //   而且没有任何征兆。判据必须是「从没推过」,不是「差值够大」。
        assertTrue(EmotionMotionMath.shouldFire(nowMs = 2_000L, lastFireAtMs = 0L))
        assertTrue(EmotionMotionMath.shouldFire(nowMs = 4_199L, lastFireAtMs = 0L))
    }

    @Test
    fun `时钟倒退时放行 而不是把动作永久锁死`() {
        // 判据算错、或者某个上游给了个坏数的时候,宁可多播一次,
        // 也不要因为一个负数把「换动作」这件事**从此**判成不许。
        assertTrue(EmotionMotionMath.shouldFire(nowMs = 1_000L, lastFireAtMs = 500_000L))
    }
}
