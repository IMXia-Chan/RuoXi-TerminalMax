package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型清单与分流的纯逻辑 —— 纯 JVM。
 *
 * 为什么值得钉：挑错了**不崩**。让一家纯文字模型去看图，它不会说「我看不到」，
 * 它会编一段听起来完全合理的描述，而账上写着一切正常。
 */
class ModelRouteTest {

    private fun ep(
        label: String = "x",
        url: String = "https://a.example",
        model: String = "m",
        key: String = "k",
        can: Set<String> = setOf(RouteMath.TEXT),
    ) = Endpoint(label, url, model, key, can)

    // ── 清单的读法 ──

    @Test
    fun `一行一家,注释和写不全的跳过`() {
        val list = RouteMath.parse(
            """
            # 名字|地址|模型|key|能干什么
            通义|https://dashscope.aliyuncs.com/compatible-mode|qwen-vl-max|sk-1|看图,文字

            豆包|https://ark.cn-beijing.volces.com/api/v3|doubao-pro|sk-2|
            这一行少了东西
            """.trimIndent()
        )
        assertEquals(2, list.size)
        assertEquals("通义", list[0].label)
        assertTrue(RouteMath.VISION in list[0].can)
        // 没写能力 → 当文字：它是「每一家都能做」的那一档，最保守。
        assertEquals(setOf(RouteMath.TEXT), list[1].can)
    }

    @Test
    fun `能干什么认中文也认英文`() {
        val l = RouteMath.parse("a|https://x|m|k|vision, code")
        assertEquals(setOf(RouteMath.VISION, RouteMath.CODE), l[0].can)
    }

    // ── 分流 ──

    @Test
    fun `要看图就只给能看图的那家`() {
        val vision = ep("qwen", can = setOf(RouteMath.VISION, RouteMath.TEXT))
        val fast = ep("deepseek", can = setOf(RouteMath.TEXT))
        assertEquals("qwen", RouteMath.pick(listOf(fast, vision), RouteMath.VISION)!!.label)
    }

    @Test
    fun `没有能看图的一家时返回空,绝不拿文字模型凑合`() {
        val fast = ep("deepseek")
        assertNull(RouteMath.pick(listOf(fast), RouteMath.VISION))
    }

    @Test
    fun `写代码没专长就降级到文字 —— 写代码本来就是文字`() {
        val fast = ep("deepseek", can = setOf(RouteMath.TEXT))
        assertEquals("deepseek", RouteMath.pick(listOf(fast), RouteMath.CODE)!!.label)
    }

    @Test
    fun `地址或模型空着的不算数`() {
        assertNull(RouteMath.pick(listOf(ep("x", url = "")), RouteMath.TEXT))
        assertNull(RouteMath.pick(listOf(ep("x", model = "")), RouteMath.TEXT))
    }

    @Test
    fun `key 可以空 —— 本机那台 llama-server 就没有 key`() {
        assertEquals("local", RouteMath.pick(listOf(ep("local", url = "http://127.0.0.1:8080", key = "")), RouteMath.TEXT)!!.label)
    }

    @Test
    fun `写出去再读回来,一个字都不变`() {
        val text = "通义|https://dashscope.aliyuncs.com/compatible-mode|qwen-vl-max|sk-1|看图\n" +
            "本机|http://127.0.0.1:8080|brain||文字"
        val back = RouteMath.format(RouteMath.parse(text))
        assertEquals(text, back)
    }

    // ── 地址 ──

    @Test
    fun `没写全的补上最常见的那种`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            RouteMath.url(ep(url = "https://api.deepseek.com/")),
        )
    }

    @Test
    fun `已经写到 chat-completions 的照原样用 —— 豆包方舟不是 v1`() {
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
            RouteMath.url(ep(url = "https://ark.cn-beijing.volces.com/api/v3/chat/completions")),
        )
    }

    // ── 拆活那一行的第三列 ──

    @Test
    fun `第三列认得出来就是需要`() {
        assertTrue(RouteMath.isNeedWord("看图"))
        assertTrue(RouteMath.isNeedWord("代码"))
        assertTrue(RouteMath.isNeedWord(" vision "))
        assertEquals(RouteMath.VISION, RouteMath.needOf("看图"))
    }

    @Test
    fun `认不出来的不能当需要 —— 否则任务正文的最后一截会被吃掉`() {
        assertTrue(!RouteMath.isNeedWord("把这两段对齐"))
        val p = FanoutMath.parse(listOf("编辑|写一段说明|认真一点的"))
        assertEquals("写一段说明|认真一点的", p.specs[0].task)
        assertEquals(RouteMath.TEXT, p.specs[0].need)
    }

    @Test
    fun `写看了图的那一份,拆出来带 need`() {
        val p = FanoutMath.parse(listOf("看图|这张照片里是什么|看图", "算|粗算一下"))
        assertEquals(2, p.specs.size)
        assertEquals(RouteMath.VISION, p.specs[0].need)
        assertEquals("这张照片里是什么", p.specs[0].task)
        assertEquals(RouteMath.TEXT, p.specs[1].need)
    }

    // ── 他自己加的那几类 ──

    @Test
    fun `自己加的一类排在内置三样后面,重名和太长的跳过`() {
        val list = RouteMath.purposes("视频，语音, 视频\n一二三四五六七八九十一二三")
        // 内置四样（生图是 2026-11-07 加的），他自己加的两类排在后面。
        assertEquals(listOf("文字", "看图", "写代码", "生图", "视频", "语音"), list.map { it.second })
    }

    @Test
    fun `自己加的那一类,拆活时第三列也认`() {
        val ps = RouteMath.purposes("音频")
        assertTrue(RouteMath.isNeedWord("音频", ps))
        val p = FanoutMath.parse(listOf("听|这段录音里说了什么|音频", "算|粗算一下"), ps)
        // ★ 不加进 purposes 的话「音频」两个字会被当成任务正文的一部分,这一类永远轮不到活。
        assertEquals("音频", p.specs[0].need)
        assertEquals("这段录音里说了什么", p.specs[0].task)
    }

    @Test
    fun `加了音频以后,能干什么那一栏也写得回去`() {
        val ps = RouteMath.purposes("音频")
        val text = "甲|http://127.0.0.1:9000|m||音频"
        assertEquals(text, RouteMath.format(RouteMath.parse(text, ps), ps))
    }

    @Test
    fun `没有能看图的模型时,那句话要说清没看到图`() {
        assertTrue(RouteMath.missing(RouteMath.VISION).contains("没看到图"))
    }

    // ── 生图（2026-11-07）──

    @Test
    fun `★ 生图和看图是两个方向,不许互相顶替`() {
        // 「生图」里有「图」字。要是不先查用途表、让关键字先跑，它会被认成「看图」——
        // 于是派一家只会看图的模型去画图，而它不会说做不到。
        assertEquals(RouteMath.IMAGE, RouteMath.needOf("生图", RouteMath.PURPOSES))
        assertEquals(RouteMath.VISION, RouteMath.needOf("看图", RouteMath.PURPOSES))
        assertEquals(RouteMath.VISION, RouteMath.needOf("图片", RouteMath.PURPOSES))
    }

    @Test
    fun `只挑了声明能干生图的那一家`() {
        val vision = ep(label = "看图那家", can = setOf(RouteMath.VISION))
        val draw = ep(label = "画图那家", can = setOf(RouteMath.IMAGE))
        assertEquals("画图那家", RouteMath.pick(listOf(vision, draw), RouteMath.IMAGE)?.label)
        // 一家都没有就是没有，不拿看图的凑合
        assertNull(RouteMath.pick(listOf(vision), RouteMath.IMAGE))
    }

    @Test
    fun `生图那一行能存回去也能读出来`() {
        val text = "火山|https://ark.cn-beijing.volces.com/api/v3|doubao-seedream|sk-1|生图"
        val list = RouteMath.parse(text)
        assertTrue(RouteMath.IMAGE in list[0].can)
        assertEquals(text, RouteMath.format(list))
    }

    @Test
    fun `没配生图时那句话让人去设置里加一行`() {
        val m = RouteMath.missing(RouteMath.IMAGE)
        assertTrue(m.contains("生图"))
        assertTrue(m.contains("画不了"))
    }

    @Test
    fun `生图的地址按端点拼`() {
        // 他填的可能是 /v1、/api/v3，也可能整条写全。
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3/images/generations",
            ImageGen.generationUrl("https://ark.cn-beijing.volces.com/api/v3/"),
        )
        assertEquals(
            "https://api.openai.com/v1/images/generations",
            ImageGen.generationUrl("https://api.openai.com/v1"),
        )
        assertEquals(
            "https://x.example/v1/images/generations",
            ImageGen.generationUrl("https://x.example/v1/images/generations"),
        )
    }
}
