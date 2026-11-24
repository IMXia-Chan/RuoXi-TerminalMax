package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「先出声」判定的钉子。
 *
 * ★ 为什么这组测试**必须**存在(2026-10-05 下午):
 *
 * 这一轮里**手机是拔着的** —— 「第一声早了多少」在真机上一秒都量不了。
 * 所以「在哪切、切完剩下什么」只能在这儿咬住。
 * **这里没钉住的行为,一律等于没做。**
 *
 * ★ 挑用例的判据不是边界条件的趣味,是**每一种「她说话怪掉了」的真实长相**:
 *
 * | 切错了会怎样 | 怎么听出来 |
 * |---|---|
 * | 从数字中间劈开 | `3.14` 念成「三。一四」 |
 * | 切出一块两个字的 | ★ 第一声**更晚**了(那笔不随文本变短的固定开销) |
 * | 在句子中间劈开 | 听着像噎住半句话 |
 * | 定稿和提前念的对不上账 | **同一句念两遍** |
 */
class SpeechChunkMathTest {

    // ---------------------------------------------------------------- 该切的

    @Test
    fun `在第一个够长的逗号上切开 这一条就是整套东西存在的理由`() {
        // 中文长回答最常见的形状:几十个字,中间只有逗号。
        // ★ 只认句号的话这段**永远不会触发**,而且不报错 —— 表现成「改了跟没改一样」。
        assertEquals(
            "好的呀,我这就去帮你看一眼,",
            SpeechChunkMath.firstChunk("好的呀,我这就去帮你看一眼,那边的情况"),
        )
    }

    @Test
    fun `句号上切`() {
        assertEquals(
            "她在呢。你找我有事吗,",
            SpeechChunkMath.firstChunk("她在呢。你找我有事吗,今天怎么突然想起来找我"),
        )
    }

    @Test
    fun `中文顿号和分号也认`() {
        assertEquals(
            "苹果、香蕉和橘子都买了，",
            SpeechChunkMath.firstChunk("苹果、香蕉和橘子都买了，一共三样"),
        )
        assertEquals(
            "等一下；我先把手上的事收个尾，",
            SpeechChunkMath.firstChunk("等一下；我先把手上的事收个尾，马上就来"),
        )
    }

    @Test
    fun `英文标点也认`() {
        assertEquals(
            "Sure, let me take a look at it,",
            SpeechChunkMath.firstChunk("Sure, let me take a look at it, one moment"),
        )
        assertEquals(
            "Okay. Here is what I found,",
            SpeechChunkMath.firstChunk("Okay. Here is what I found, take a look"),
        )
    }

    @Test
    fun `问叹号连着的一串一起带走`() {
        // `？！` 是中文里一个语气。切成 `？` + `！` 会让 TTS 停顿两次。
        assertEquals(
            "你说这是真的吗？！",
            SpeechChunkMath.firstChunk("你说这是真的吗？！我可不信"),
        )
    }

    @Test
    fun `断点后面的收尾引号属于上一句 不留到下一块`() {
        // ★ 不带的话,那个孤零零的 `」` 会被下一块念成一个停顿。
        assertEquals(
            "她说「我不知道。」",
            SpeechChunkMath.firstChunk("她说「我不知道。」然后走了"),
        )
    }

    @Test
    fun `换行也是断点`() {
        // 模型爱用换行分段,那是天然的句尾。
        assertEquals(
            "第一件事说完了。\n",
            SpeechChunkMath.firstChunk("第一件事说完了。\n第二件是这样"),
        )
    }

    // ---------------------------------------------------------------- 不该切的

    @Test
    fun `短于下限就不切`() {
        // ★ 「嗯,好」只有三个字。为它单起一次 TTS = **比不切更慢**(见 MIN_CHARS 的注释)。
        assertNull(SpeechChunkMath.firstChunk("嗯,好"))
        assertNull(SpeechChunkMath.firstChunk("好的。"))
        assertNull(SpeechChunkMath.firstChunk(""))
    }

    @Test
    fun `一个断点都没有就绝不切 宁可不提前也不在句子中间劈开`() {
        // 这才是「宁可漏不可错」:漏了只是晚一点出声,劈错了是听着像噎住。
        assertNull(SpeechChunkMath.firstChunk("我今天想跟你说一件挺重要的事情"))
        assertNull(SpeechChunkMath.firstChunk("let me think about that for a moment"))
    }

    @Test
    fun `断点来得太早要跳过它去找下一个 而不是就此作罢`() {
        // 「好,」只有两个字 —— 跳过它,后面的句号才是能用的那个。
        assertEquals("好,我在呢。", SpeechChunkMath.firstChunk("好,我在呢。你等一下"))
        // 跳过了、后面也确实没有了 → 不切。**不是切出「好,」那两个字。**
        assertNull(SpeechChunkMath.firstChunk("好,我在呢你别急"))
    }

    @Test
    fun `冒号不算断点`() {
        // 「他跟我说:」切出去是半句话,而提前量并不比逗号多。故意不收。
        assertNull(SpeechChunkMath.firstChunk("他跟我说:这件事得从长计议慢慢来"))
    }

    // ---------------------------------------------------------------- 数字保护

    @Test
    fun `小数点不许劈开`() {
        // ★ 切点必须是那个**中文逗号**,不是小数点 —— 否则 `3.14` 念成「三。一四」。
        assertEquals(
            "圆周率是 3.14,",
            SpeechChunkMath.firstChunk("圆周率是 3.14,记一下这个数"),
        )
    }

    @Test
    fun `千位分隔符不许劈开`() {
        assertEquals(
            "这个月花掉 1,000 块,",
            SpeechChunkMath.firstChunk("这个月花掉 1,000 块,比上个月少"),
        )
    }

    @Test
    fun `句末的英文句点照切 那不是数字里的小数点`() {
        assertEquals(
            "Okay. Let me check that,",
            SpeechChunkMath.firstChunk("Okay. Let me check that, for you"),
        )
    }

    // ---------------------------------------------------------------- 对账

    @Test
    fun `没提前念过就返回负一 意思是整段念`() {
        assertEquals(-1, SpeechChunkMath.spokenPrefix("完整的一句话", ""))
    }

    @Test
    fun `对得上就从那一块后面接着念`() {
        assertEquals(
            "好的呀,".length,
            SpeechChunkMath.spokenPrefix("好的呀,我这就去帮你看看。", "好的呀,"),
        )
    }

    @Test
    fun `定稿就是那一块的话 返回全长 意思是别再念了`() {
        // ★ 这一格最要紧:它避开的是「提前念过之后,定稿又整段念一遍」——
        //   同一句话说两次,听起来像是她突然重复自己。
        val full = "好的呀,"
        assertEquals(full.length, SpeechChunkMath.spokenPrefix(full, full))
    }

    @Test
    fun `对不上账就退回整段念 绝不按长度切一刀`() {
        // 模型改口 / 流式那版和定稿版不是一个开头 —— 这是真会发生的。
        // ★ 按长度硬切 = 把半句话吞掉,而且**不报错**。
        assertEquals(-1, SpeechChunkMath.spokenPrefix("其实我刚才说错了,应该是另一回事", "好的呀,"))
    }

    @Test
    fun `定稿把提前那块整个吞掉了也要退回整段`() {
        assertEquals(-1, SpeechChunkMath.spokenPrefix("好的", "好的呀,我这就去"))
    }

    // ---------------------------------------------------------------- 不变量

    /** 每一条都必须是「切得出东西」的样本 —— 前缀不变量只在非 null 时有意义。 */
    private val samples = listOf(
        "好的呀,我这就去帮你看一眼,那边的情况",
        "她在呢。你找我有事吗,今天怎么突然想起来找我",
        "你说这是真的吗？！我可不信",
        "她说「我不知道。」然后走了",
        "第一件事说完了。\n第二件是这样",
        "Sure, let me take a look at it, one moment",
        "圆周率是 3.14,记一下这个数",
    )

    @Test
    fun `切出来的永远是原文的前缀`() {
        // ★ 这是 [spokenPrefix] 能不能对上账的**唯一前提**。
        //   它一破,startsWith 就失败 → 退回整段念(安全的),但功能也就死了一半。
        for (s in samples) {
            val c = SpeechChunkMath.firstChunk(s)
            assertTrue("$s → 切出了 null", c != null)
            assertTrue("★ $s\n   切出:$c\n   不是前缀", s.startsWith(c!!))
        }
    }

    @Test
    fun `切出来的每一块都不短于下限`() {
        // ★ 这条守的是 AIRI 那笔「不随文本变短的固定开销」:
        //   一旦有一块短于下限,这次切分就是**净亏**(第一声反而更晚)。
        for (s in samples) {
            val c = SpeechChunkMath.firstChunk(s)!!
            assertTrue("$s → 切出了太短的一块:$c", c.length >= SpeechChunkMath.MIN_CHARS)
        }
    }

    @Test
    fun `同样的输入永远同样的结论`() {
        val s = "好的呀,我这就去帮你看一眼,那边的情况"
        val first = SpeechChunkMath.firstChunk(s)
        repeat(5) { assertEquals(first, SpeechChunkMath.firstChunk(s)) }
    }

    // ---------------------------------------------------------------- 接着往下抢

    @Test
    fun `没抢过的时候 接着往下 就是第一块`() {
        // ★ 这两条路必须是同一件事 —— 各写一份切法,总有一天会有一边偷偷改掉。
        for (s in samples) {
            assertEquals(SpeechChunkMath.firstChunk(s), SpeechChunkMath.nextChunk(s, ""))
        }
    }

    @Test
    fun `这一版变长之后能接着抢第二句`() {
        // ★★ 就是用户 2026-10-05 报的那条:
        //   「她在说话的时候,**会中间断一下再说**,前半句话……后半句话,会中间加延迟」
        //   只抢一块的话,第一块念完到定稿之间是**一整段空白**(真机上量到 8 秒)。
        val v1 = "听得到听得到,耳朵灵着呢"
        val first = SpeechChunkMath.nextChunk(v1, "")!!
        assertEquals("听得到听得到,", first)
        // 第一版就吐了这么多 —— 没有第二块可抢。
        assertNull(SpeechChunkMath.nextChunk(v1, first))

        // 模型接着往下吐了 —— 这一次才有第二块。
        val v2 = "听得到听得到,耳朵灵着呢。就是这会儿你那台电脑我够不着,别的"
        val second = SpeechChunkMath.nextChunk(v2, first)!!
        assertEquals("耳朵灵着呢。", second)
        assertTrue(v2.startsWith(first + second))
    }

    @Test
    fun `抢光了就没有下一块`() {
        // 一整句都念出去了,再切就是切空气;
        // 而切出个空串会让调用方白起一次 TTS —— 那笔固定开销是这一层最贵的一种错。
        val s = "好,我在呢。"
        val said = SpeechChunkMath.firstChunk(s)!!
        assertEquals(s, said)
        assertNull(SpeechChunkMath.nextChunk(s, said))
    }

    @Test
    fun `抢过的那段比这一版还长也只是停手`() {
        // 流式偶尔会这样:这一版比上一版短。★ **那不是异常,是一个必须处理的状态** ——
        // 它不许抛、不许硬切,只能回 null。
        assertNull(SpeechChunkMath.nextChunk("好,我", "好,我在呢。"))
    }

    @Test
    fun `模型改了口就停手 死也不硬切`() {
        // ★★ 这一层最重要的一条(见 [SpeechChunkMath.spokenPrefix] 那段):
        //   按长度硬切一刀会把半句话吞掉,而且**不报错**。
        //   改了口就这一版不抢,交给定稿那条路整段重念。**宁可重复,绝不吞字。**
        val said = SpeechChunkMath.firstChunk("听得到听得到,耳朵灵着呢")!!
        assertNull(SpeechChunkMath.nextChunk("其实我刚才说错了,应该是另一回事", said))
    }

    @Test
    fun `剩下的那截还没成句就不抢`() {
        // ★ 和 firstChunk 同一条规矩:绝不在句子中间劈开。
        val s = "好,我在呢。你等一下我马上就来"
        val said = SpeechChunkMath.nextChunk(s, "")!!
        assertEquals("好,我在呢。", said)
        assertNull(SpeechChunkMath.nextChunk(s, said))
    }

    @Test
    fun `第二块照样不许从数字里劈开`() {
        // 数字保护是**逐块**的 —— 别以为第一块躲过去了就一劳永逸。
        val s = "好,我在呢。他说的 1,000 块我不信,得再查一下"
        val first = SpeechChunkMath.nextChunk(s, "")!!
        assertEquals("好,我在呢。", first)
        // ★ 如果这个保护只在第一块里生效,这里会切成 `他说的 1,` + `000 块…`,
        //   那个孤零零的 `000` 会被念成「零零零」。
        assertEquals("他说的 1,000 块我不信,", SpeechChunkMath.nextChunk(s, first))
    }

    @Test
    fun `一句一句往下抢 拼起来永远是原文的前缀 而且一定会停`() {
        // ★★ 这是 [SpeechChunkMath.nextChunk] 存在的**唯一**前提。
        //   一轮现在可以抢**好几块**了,所以「每一块是前缀」不够 ——
        //   必须「拼起来那一整段也是前缀」:中间哪一块切歪了,
        //   定稿时 startsWith 就失败 → 整段重念(**不报错**,但白念一整句)。
        //   顺带钉住它会停 —— onPartial 每来一版都要调它一次。
        for (s in samples) {
            var said = ""
            var rounds = 0
            while (true) {
                val c = SpeechChunkMath.nextChunk(s, said) ?: break
                assertTrue("★ $s\n   抢出了太短的一块:$c", c.length >= SpeechChunkMath.MIN_CHARS)
                said += c
                assertTrue("★ $s\n   累计「$said」已经不是原文的前缀", s.startsWith(said))
                rounds++
                assertTrue("★ $s 切不完 —— 会死循环", rounds <= 20)
            }
            // ★ 走到底之后必须**它自己**回 null 停住,不是靠上面那道护栏兜住的。
            assertNull(SpeechChunkMath.nextChunk(s, said))
        }
    }

    @Test
    fun `同样的输入永远同样的结论 接着往下也一样`() {
        val s = "好的呀,我这就去帮你看一眼,那边的情况"
        val said = SpeechChunkMath.firstChunk(s)!!
        val next = SpeechChunkMath.nextChunk(s, said)
        repeat(5) { assertEquals(next, SpeechChunkMath.nextChunk(s, said)) }
    }
}
