package com.example.touchpad

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 耳朵里那几条纯判断的测试 —— **它们防的全是「不崩,只是她听错」的错**。
 *
 * ## 为什么这些值得单独钉
 *
 * 识别这条链上一个异常都不会抛。字节序反了、标点被当成话、噪声幻觉出一整句 ——
 * 每一种的表现都**一模一样**:「她没听见」,或者更糟,她听成了一句没人说过的话。
 * 真机上要分辨这些得推 234MB 模型上去,一次试一个变量;在电脑上是一秒钟的事。
 *
 * 所以这个文件的存在理由和 `QuietHoursTest` 一样:**把静默失败从真机上挪走。**
 */
class EarMathTest {

    // ---------------------------------------------------------------- 字节序

    /**
     * ★★ 这个文件里最值钱的一条。
     *
     * PCM16 是**小端**:低字节在前。写反了不会崩,得到的是整段波形被掰成锯齿,
     * 识别出来全是乱码 —— 而那看起来像「模型不行」,不像「字节序反了」。
     */
    @Test
    fun `PCM16_小端_负满幅`() {
        // 0x8000 = -32768,小端字节序是 [低, 高] = [0x00, 0x80]
        // 写反成 [0x80, 0x00] 的话会得到 +1.0 —— 正负颠倒是这里最容易犯的错
        val f = EarMath.pcm16ToFloat(byteArrayOf(0x00, 0x80.toByte()), 2)
        assertArrayEquals(floatArrayOf(-1.0f), f, 1e-6f)
    }

    @Test
    fun `PCM16_小端_正数`() {
        // 0x1234 = 4660;小端 [0x34, 0x12]
        val f = EarMath.pcm16ToFloat(byteArrayOf(0x34, 0x12), 2)
        assertArrayEquals(floatArrayOf(4660f / 32768f), f, 1e-6f)
    }

    @Test
    fun `PCM16_正满幅接近一但不等于一`() {
        // 0x7FFF = 32767,除以 32768 → 差一点点到 1。**这是对的**:
        // 写实了 +1.0 就说明用了 32767 当分母,那样每个样本都会略微偏大
        val f = EarMath.pcm16ToFloat(byteArrayOf(0xFF.toByte(), 0x7F), 2)
        assertEquals(32767f / 32768f, f[0], 1e-6f)
        assertTrue("不能等于 1.0", f[0] < 1.0f)
    }

    @Test
    fun `PCM16_静音是零`() {
        val f = EarMath.pcm16ToFloat(byteArrayOf(0x00, 0x00, 0x00, 0x00), 4)
        assertArrayEquals(floatArrayOf(0f, 0f), f, 1e-6f)
    }

    /** 奇数个字节:末尾那个孤立的半样本**丢掉**,不补零。补零会在每个 chunk 边界插一个咔哒。 */
    @Test
    fun `PCM16_半个样本丢掉`() {
        val f = EarMath.pcm16ToFloat(byteArrayOf(0x00, 0x80.toByte(), 0x7F), 3)
        assertEquals("只剩一个完整样本", 1, f.size)
        assertEquals(-1.0f, f[0], 1e-6f)
    }

    /** `len` 是**有效字节数**,不是数组长度 —— AudioRecord.read 回来的 n 常常小于缓冲大小。 */
    @Test
    fun `PCM16_只认前len个字节`() {
        val buf = ByteArray(640)
        buf[0] = 0x00; buf[1] = 0x80.toByte()
        buf[2] = 0xFF.toByte(); buf[3] = 0x7F   // 后面这些是上一轮的残留,不能读进来
        val f = EarMath.pcm16ToFloat(buf, 2)
        assertEquals(1, f.size)
        assertEquals(-1.0f, f[0], 1e-6f)
    }

    // ---------------------------------------------------------------- 时长

    @Test
    fun `时长_门槛是闭的开区间`() {
        assertEquals("6400 样本 @16k = 400ms", 400, EarMath.durationMs(6400))
        assertEquals(1000, EarMath.durationMs(16000))
        assertEquals(0, EarMath.durationMs(0))
    }

    /** ★ 边界:299 算太短、300 不算。差一个毫秒,判据就换了。 */
    @Test
    fun `时长_299太短_300不算`() {
        assertTrue(EarMath.tooShort(299))
        assertFalse(EarMath.tooShort(300))
    }

    /** ★ 边界:19999 不算超、20000 不算超、20001 算超(「超过」是严格大于)。 */
    @Test
    fun `时长_20000不算超_20001算超`() {
        assertFalse(EarMath.tooLong(19999))
        assertFalse("正好到顶不算超", EarMath.tooLong(20000))
        assertTrue(EarMath.tooLong(20001))
    }

    @Test
    fun `时长_零样本`() {
        assertEquals(0, EarMath.durationMs(0))
        assertTrue(EarMath.tooShort(0))
    }

    // ------------------------------------------------------------ 实时字幕的清洗

    /**
     * ★★ 这一组钉的是**和 [EarMath.clean] 故意不一样**的地方。
     *
     * 判据不是「这半句对不对」,是「这几个字能不能先给他看着」。
     * 所以**短、重复、没说完,一律放过** —— 拿整句那套标准去卡半句话,
     * 字幕会在说到一半时突然整行消失,而那种消失看起来不像「被判掉了」,
     * 像**卡住了**。这一组就是防以后有人「顺手统一一下两个清洗函数」。
     */
    @Test
    fun `半句清洗_剥标记也去空白`() {
        assertEquals("打开", EarMath.cleanPartial("<|zh|><|NEUTRAL|>打开"))
        assertEquals("打开", EarMath.cleanPartial("  打开  "))
    }

    /** 半句话天生就短 —— 「我」「那个」这种一两个字的版本**必须**能贴出去。 */
    @Test
    fun `半句清洗_一两个字也放过`() {
        assertEquals("我", EarMath.cleanPartial("我"))
        assertEquals("那个", EarMath.cleanPartial("那个"))
        // 整句那套会把这些判成「短串没法判」而放行,这里也必须放行
        assertEquals("我我我", EarMath.cleanPartial("我我我"))
    }

    /** ★★ 和 [EarMath.clean] **故意不同**的一条:循环幻觉在半句这一层不拦。 */
    @Test
    fun `半句清洗_重复在说一半时不算幻觉`() {
        val loop = "哈哈哈哈哈哈哈哈哈哈哈"
        assertNull("整句那一层必须丢掉它", EarMath.clean(loop))
        assertEquals("半句这一层得先给他看着", loop, EarMath.cleanPartial(loop))
    }

    /** ★★ 同理:200 字的长度上限在半句这一层也不拦(说长句子时它必然会被越过)。 */
    @Test
    fun `半句清洗_长句不截断`() {
        val long = "我".repeat(250)
        assertNull(EarMath.clean(long))
        assertEquals(long, EarMath.cleanPartial(long))
    }

    /** 但「一个字都没有」还是要丢 —— 那是 SenseVoice 在纯噪声上吐的「。。。」。 */
    @Test
    fun `半句清洗_没字还是丢掉`() {
        assertNull(EarMath.cleanPartial(""))
        assertNull(EarMath.cleanPartial("   "))
        assertNull(EarMath.cleanPartial("。。。"))
        assertNull(EarMath.cleanPartial("<|zh|><|NEUTRAL|>"))
        assertNull(EarMath.cleanPartial("? ? ?"))
    }

    // ---------------------------------------------------------------- 清洗

    @Test
    fun `清洗_剥掉标记`() {
        // 语言 / 情绪 / 事件 / ITN 四种标记一起上 —— 真机第一次跑之前先按最坏情况防
        assertEquals(
            "打开微信",
            EarMath.clean("<|zh|><|NEUTRAL|><|Speech|><|withitn|>打开微信")
        )
    }

    @Test
    fun `清洗_两端空白剥掉`() {
        assertEquals("打开微信", EarMath.clean("  打开微信  "))
    }

    /** 剥完标记只剩空白 = 没听见东西。 */
    @Test
    fun `清洗_只有空白丢掉`() {
        assertNull(EarMath.clean("   "))
        assertNull(EarMath.clean(""))
        assertNull(EarMath.clean("<|zh|><|NEUTRAL|>"))
    }

    /** 全是标点 —— 噪声上来的,不是话。 */
    @Test
    fun `清洗_全是标点丢掉`() {
        assertNull(EarMath.clean("。"))
        assertNull(EarMath.clean("。。。;"))
        assertNull(EarMath.clean("? ? ?"))
    }

    /**
     * 中日韩统一表意文字在 `Char.isLetter()` 里**是 true**(类别 Lo),
     * 所以「有字就留」这一条中英文都认。这条钉的是「别为了认中文去判码点范围」。
     */
    @Test
    fun `清洗_中文算字_英文也算字`() {
        assertEquals("好", EarMath.clean("好"))
        assertEquals("ok", EarMath.clean("ok"))
        assertEquals("3", EarMath.clean("3"))
    }

    @Test
    fun `清洗_超长丢掉`() {
        // ★ 必须用一段**变化**的字来试长度 —— 拿同一个字重复 200 遍的话,
        //   它会先被下面那条「单字循环」拦掉,于是这条测试**永远测不到长度那条规则**。
        //   (量具会撒谎的老毛病:测试绿着,却什么都没验。)
        //
        // ★ 长度用 `take` **算**出来而不是手写常数 —— 我第一版手数了一遍,数错了
        //   (以为 base 是 14 字,其实是 13),于是这条测试红在一个跟被测逻辑无关的地方。
        val ok200 = "帮我把电脑上那个浏览器关掉".repeat(16).take(200)
        assertEquals("正好 200 字要留着", 200, ok200.length)
        assertEquals("正好 200 字要留着", ok200, EarMath.clean(ok200))
        assertNull("201 字判为幻觉", EarMath.clean(ok200 + "嗯"))
    }

    // ---------------------------------------------------------------- 幻觉循环

    @Test
    fun `清洗_单字循环丢掉`() {
        assertNull(EarMath.clean("哈哈哈哈哈哈哈哈哈哈哈"))
        assertNull(EarMath.clean("好好好好好好好好好"))
    }

    /**
     * ★ 这一条是**反向**的:真笑不能丢。
     * 「哈哈哈」是真话,「嗯嗯嗯嗯嗯」也是。判据必须能分开它们和幻觉 ——
     * 靠的是**占比**加**最短长度**,不是「有没有重复」。
     */
    @Test
    fun `清洗_短的真笑不能丢`() {
        assertEquals("哈哈哈", EarMath.clean("哈哈哈"))
        assertEquals("嗯嗯嗯嗯嗯", EarMath.clean("嗯嗯嗯嗯嗯"))
    }

    /** 8 个「嗯」+「好的」= 80% 占比,低于 90% 门槛 —— 是真话,要留。 */
    @Test
    fun `清洗_八成占比不能丢`() {
        val t = "嗯".repeat(8) + "好的"
        assertEquals(t, EarMath.clean(t))
    }

    /** 「好了好了好了好了好了」—— 最多的是「好」占 50%,远不到门槛。 */
    @Test
    fun `清洗_重复的词组不是幻觉`() {
        val t = "好了".repeat(5)
        assertEquals(t, EarMath.clean(t))
    }

    // ---------------------------------------------------------------- 丢弃理由

    /**
     * ★ 「为什么丢的」必须能和「她没听见」分开。
     * 两条都被丢时日志长得一模一样的话,真机排查就白瞎了。
     */
    @Test
    fun `丢弃理由_每种都说得出来`() {
        listOf(
            "",
            "。。",
            "把".repeat(201),
            "哈哈哈哈哈哈哈哈哈哈哈",
        ).forEach { raw ->
            assertNull("这条应该被丢:$raw", EarMath.clean(raw))
            assertTrue(
                "丢了就必须说得出理由,现在是:${EarMath.whyDropped(raw)}",
                EarMath.whyDropped(raw).isNotBlank()
            )
        }
        // 没被丢的那条不该走这个函数,但万一走了也不能编个假理由
        assertFalse(
            "好的转写不该被描述成丢了",
            EarMath.whyDropped("打开微信").startsWith("转写")
        )
    }
}
