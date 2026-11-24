package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [liveLineUpdate] —— AI 对话框里那条「正在生成」的行。
 *
 * 为什么值得单测:它是个「覆盖而不是追加」的小状态机,而写错的表现**全都不疼不痒**
 * ——文字在屏幕上重复一遍、最后一行缺半句、撤掉的时候把上一行也带走。这些只能靠
 * 眼睛在对话框里盯出来,每次改完手动点一遍不现实。抽成纯函数就是为了在这里钉住。
 *
 * 断言的是**行为**:覆盖时旧内容必须消失、撤掉时上面的历史一个字都不能少。
 *
 * 跑:`./gradlew :app:testDebugUnitTest`
 */
class LiveLineTest {

    /** 简化调用:只关心新文本,不关心新的 liveStart。 */
    private fun text(text: String, liveStart: Int, partial: String) =
        liveLineUpdate(text, liveStart, partial).first

    private fun start(text: String, liveStart: Int, partial: String) =
        liveLineUpdate(text, liveStart, partial).second

    // ---------------------------------------------------------------- 覆盖
    @Test
    fun `覆盖时旧的那半句必须消失`() {
        val t1 = text("", -1, "你好")
        assertEquals("✍ 你好", t1)
        val s1 = start("", -1, "你好")
        val t2 = text(t1, s1, "你好,我是若息")
        assertEquals("✍ 你好,我是若息", t2)
    }

    @Test
    fun `连报多次不会把历史越堆越长`() {
        var t = ""
        var s = -1
        for (piece in listOf("a", "ab", "abc", "abcd", "abcde")) {
            val r = liveLineUpdate(t, s, piece)
            t = r.first; s = r.second
        }
        assertEquals("✍ abcde", t)
    }

    // ---------------------------------------------------------------- 起点
    @Test
    fun `新起一行时要跟前面的日志隔开`() {
        val t = text("→ 执行 open_app", -1, "好的")
        assertEquals("→ 执行 open_app\n✍ 好的", t)
        // 起点指向前缀之前 —— 这样下一次覆盖才不会把「✍」也叠进去。
        assertEquals("→ 执行 open_app\n".length, start("→ 执行 open_app", -1, "好的"))
    }

    @Test
    fun `前面已经有换行时不重复加`() {
        assertEquals("上一条\n✍ x", text("上一条\n", -1, "x"))
    }

    @Test
    fun `空日志框不会以换行开头`() {
        assertEquals("✍ x", text("", -1, "x"))
    }

    // ---------------------------------------------------------------- 撤掉
    @Test
    fun `撤掉时上面的历史一个字都不能少`() {
        val before = "→ 执行 open_app\n← open_app ok\n"
        val t1 = text(before, -1, "我在想…")
        val s1 = start(before, -1, "我在想…")
        val t2 = text(t1, s1, "")
        assertEquals(before, t2)
        assertEquals(-1, start(t1, s1, ""))
    }

    @Test
    fun `没有预览行时撤掉是空操作`() {
        val t = "✅ 做完了"
        assertEquals(t, text(t, -1, ""))
        assertEquals(-1, start(t, -1, ""))
    }

    @Test
    fun `起点失效时不炸也不吞内容`() {
        // 理论上不会发生,但界面状态是跨线程拼起来的 —— 真坏了也得退回安全行为,
        // 而不是抛异常把整个对话框带走。
        assertEquals("✍ x", text("", 999, "x"))
        assertEquals("abc", text("abc", 999, ""))
    }

    // ---------------------------------------------------------------- 定格
    @Test
    fun `定格之后新日志接在预览行的下一行`() {
        // 真实顺序:预览行还在 → 来了正式日志(append 会把 liveStart 置 -1) →
        // 再报一次预览时,应该**新起一行**,不能贴到上一句尾巴上。
        var t = text("", -1, "我在想…")
        var s = start("", -1, "我在想…")
        assertEquals(0, s)

        s = -1                       // append 干的
        t += "\n→ 执行 click_ui"      // append 干的
        val r = liveLineUpdate(t, s, "接着想…")
        assertEquals("✍ 我在想…\n→ 执行 click_ui\n✍ 接着想…", r.first)
    }

    @Test
    fun `前缀只出现一次`() {
        var t = ""
        var s = -1
        for (p in listOf("一", "一二", "一二三")) {
            val r = liveLineUpdate(t, s, p)
            t = r.first; s = r.second
        }
        assertEquals(1, Regex(Regex.escape(LIVE_PREFIX)).findAll(t).count())
        assertTrue(t.endsWith("✍ 一二三"))
    }
}
