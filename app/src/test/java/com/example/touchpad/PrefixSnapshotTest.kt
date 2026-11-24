package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 槽位快照的判定规则 —— 纯 JVM。
 *
 * 为什么值得钉死:**这个功能出错是静默的。** 读回一份过期的 KV,模型不会报错,
 * 它会拿着「上一版提示词 + 上一版工具表」的世界观跟你说话 —— 症状是「她答得怪怪的」,
 * 而任何人去查都会先怀疑模型、怀疑提示词,不会想到是磁盘上一个文件的问题。
 *
 * 所以这里两个方向都要钉:
 *   - **该作废的必须作废**(换提示词/换模型/换 KV 量化 → 键必须变);
 *   - **不该作废的必须不作废**(重新 push 同一个模型文件 → 键不许变,
 *     否则每次导模型都白扔一次快照)。
 */
class PrefixSnapshotTest {

    private fun key(
        prompt: String = "你是若息",
        tools: String = """[{"name":"open_app"}]""",
        model: String = "brain.gguf",
        bytes: Long = 2_500_000_000L,
        nCtx: Int = 8192,
        cache: String = "q8_0/q8_0",
    ) = PrefixSnapshot.key(prompt, tools, model, bytes, nCtx, cache)

    @Test
    fun `同一份输入永远给同一个键`() {
        assertEquals(key(), key())
    }

    @Test
    fun `长度固定,够当文件名`() {
        val k = key()
        assertEquals(32, k.length)
        assertTrue(PrefixSnapshot.isValidFileName(PrefixSnapshot.fileName(k)))
    }

    @Test
    fun `提示词改一个字就作废`() {
        assertNotEquals(key(), key(prompt = "你是若息。"))
    }

    @Test
    fun `工具表改一个字就作废`() {
        assertNotEquals(key(), key(tools = """[{"name":"open_app "}]"""))
    }

    @Test
    fun `模型换了就作废`() {
        assertNotEquals(key(), key(model = "brain2.gguf"))
        assertNotEquals(key(), key(bytes = 2_500_000_001L))
    }

    @Test
    fun `KV 形状变了就作废`() {
        assertNotEquals(key(), key(nCtx = 4096))
        assertNotEquals(key(), key(cache = "f16/f16"))
    }

    /**
     * ★ 这条是**方向相反**的一半:重新 push 同一个模型时 mtime 会变,
     * 但内容没变 —— 键**必须**纹丝不动,否则用户每次导模型都要重付一次冷启动。
     * (所以 [PrefixSnapshot.key] 收的是字节数,不是 lastModified。)
     */
    @Test
    fun `重新导同一个模型不作废`() {
        assertEquals(key(), key(bytes = 2_500_000_000L))
    }

    /** 拼接不许有歧义:`("ab","")` 和 `("a","b")` 不能撞成同一个键。 */
    @Test
    fun `字段边界不会串味`() {
        assertNotEquals(key(prompt = "ab", tools = ""), key(prompt = "a", tools = "b"))
    }

    @Test
    fun `文件名必须是纯十六进制加后缀`() {
        val n = PrefixSnapshot.fileName(key())
        assertTrue(n.endsWith(".bin"))
        assertTrue(n.dropLast(4).all { it in "0123456789abcdef" })
    }

    /**
     * 镜子照着 llama.cpp 的 `fs_validate_filename`。**我们这个格式永远合法**,
     * 所以这条测试真正拦的是「以后有人改了 fileName 的格式」。
     */
    @Test
    fun `非法文件名必须被认出来`() {
        assertFalse(PrefixSnapshot.isValidFileName(""))
        assertFalse(PrefixSnapshot.isValidFileName("a".repeat(256)))
        for (bad in listOf("/", "\\", ":", "*", "?", "\"", "<", ">", "|")) {
            assertFalse("这个字符应该非法: $bad", PrefixSnapshot.isValidFileName("a${bad}b"))
        }
        assertFalse(PrefixSnapshot.isValidFileName("a\u0000b"))
        assertFalse(PrefixSnapshot.isValidFileName("../x.bin"))
        assertTrue(PrefixSnapshot.isValidFileName("ok-name_1.bin"))
    }

    /**
     * ★ 判据是**一模一样**,不是「大于 0」。
     *
     * 少还原一截 = KV 里只有半截前缀,而我们已经据此跳过了预热 →
     * 用户第一句话照样冷算几分钟,**正好是这个功能本来要治的病**。
     */
    @Test
    fun `还原要一个 token 都不差才算数`() {
        assertTrue(PrefixSnapshot.restoreHolds(4889, 4889))
        assertFalse("少一截必须判失败", PrefixSnapshot.restoreHolds(4888, 4889))
        assertFalse("多出来也不正常", PrefixSnapshot.restoreHolds(4890, 4889))
        assertFalse("空快照不算数", PrefixSnapshot.restoreHolds(0, 0))
        assertFalse("没存过就没有可还原的", PrefixSnapshot.restoreHolds(100, 0))
    }

    /**
     * ★ 这条拦的是最难查的那种坏:**一份几乎空的快照会「还原成功、对账也平」**,
     * 于是我们理直气壮地跳过预热 —— 而前缀根本没算,用户第一句话照样冷算好几分钟。
     *
     * 下限刻意取得很低(真前缀 4889,下限 256):缩短前缀是路线图上要做的事,
     * 硬凑一个贴近真值的数,以后一缩短就会**误伤真快照**。
     */
    @Test
    fun `几乎空的快照不许当数`() {
        assertFalse("空槽", PrefixSnapshot.worthKeeping(0))
        assertFalse("就几个 token", PrefixSnapshot.worthKeeping(5))
        assertFalse(PrefixSnapshot.worthKeeping(PrefixSnapshot.MIN_WORTH_KEEPING - 1))
        assertTrue("真的前缀", PrefixSnapshot.worthKeeping(4889))
        assertTrue("刚好够线", PrefixSnapshot.worthKeeping(PrefixSnapshot.MIN_WORTH_KEEPING))
    }

    @Test
    fun `下限必须低到不会误伤以后缩短的前缀`() {
        // 前缀从 4889 缩到 1/8 也还是远远在线上 —— 缩短前缀不该把快照功能一起废掉。
        assertTrue(PrefixSnapshot.worthKeeping(600))
    }

    /**
     * ★★ 这条是**真机量出来的**,不是想出来的。
     *
     * 实测:客户端超时断开后,服务端把预填充掐死在 batch 边界,存下来 `n_saved=4096`,
     * 而这次请求真实的 `usage.prompt_tokens=4889`。
     *
     * 那种快照**每一道弱闸都过得去**(不是空槽、还原时 `4096 == 4096` 对账也平),
     * 于是我们跳过预热 —— 而少了 793 个 token 没算。只有这一条能识破它。
     */
    @Test
    fun `被掐死在 batch 边界的残废快照必须被抓出来`() {
        assertFalse("4096 < 4889,残废", PrefixSnapshot.coversFullPrompt(4096, 4889))
        // 而它过得掉那道弱闸 —— 这正是必须有这一条的原因。
        assertTrue("弱闸拦不住它", PrefixSnapshot.worthKeeping(4096))
    }

    @Test
    fun `完整的快照要放行`() {
        assertTrue("正好相等", PrefixSnapshot.coversFullPrompt(4889, 4889))
        assertTrue("多存了生成的那个 token 也没关系", PrefixSnapshot.coversFullPrompt(4890, 4889))
    }

    @Test
    fun `拿不到真值就退回弱闸,但别当常态`() {
        assertTrue(PrefixSnapshot.coversFullPrompt(4889, -1))
        assertTrue(PrefixSnapshot.coversFullPrompt(4889, 0))
        assertFalse("真值拿不到也不许放行空槽", PrefixSnapshot.coversFullPrompt(5, -1))
    }

    @Test
    fun `对账文件读不出来就当作不可用`() {
        assertEquals(4889, PrefixSnapshot.decodeMeta("4889"))
        assertEquals(4889, PrefixSnapshot.decodeMeta("  4889\n"))
        assertEquals(-1, PrefixSnapshot.decodeMeta(null))
        assertEquals(-1, PrefixSnapshot.decodeMeta(""))
        assertEquals(-1, PrefixSnapshot.decodeMeta("坏掉的内容"))
    }
}
