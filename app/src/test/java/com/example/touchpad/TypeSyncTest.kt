package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「手机输入框 → 电脑输入框」那套差异算法的纯测试。
 *
 * ## 为什么它值得单独一个文件
 *
 * 它算错的**唯一**表现是:**电脑上多出或少了几个字**。
 * 而手机上看起来一切正常 —— 输入框里就是你想打的那句话。
 * 也就是说:**在手机上是查不出这个 bug 的**,只能在这儿钉。
 *
 * 真机上的代价还更大一点:电脑上多出来的字是**打进了你正在用的程序里**
 * (可能是聊天框、可能是搜索框),你多半会先怀疑自己手抖。
 */
class TypeSyncTest {

    private fun ops(old: String, now: String) = TypeSync.diff(old, now)

    /** `<8×3>你好` 这样的可读形式,断言起来比比对 sealed class 列表清楚得多。 */
    private fun desc(old: String, now: String) = TypeSync.describe(TypeSync.diff(old, now))

    // ------------------------------------------------------------ 最常见的三种

    @Test
    fun `逐字追加_只发多出来的那一截`() {
        // 打「你好」的每一步:不能每步都把整句重发一遍(那会在电脑上打出「你你好你好」)
        assertEquals("你", desc("", "你"))
        assertEquals("好", desc("你", "你好"))
        assertEquals("", desc("你好", "你好"))   // 没变就是**什么都不发**
    }

    @Test
    fun `退格_几个字就按几下`() {
        assertEquals("<8×1>", desc("你好", "你"))
        assertEquals("<8×2>", desc("你好吗", "你"))
        assertEquals("<8×5>", desc("helloworld", "hello"))
    }

    @Test
    fun `一次删光_全退格`() {
        assertEquals("<8×3>", desc("abc", ""))
    }

    // ------------------------------------------------- 输入法真正会干的事

    @Test
    fun `中文上屏_拼音候选整块替换(中间改动)`() {
        // 「nihao」上屏成「你好」:公共前缀是空,所以是「全删 5 个 + 打 2 个字」。
        // ★ 这一条是**故意的**:「ni」和「你好」没有任何可复用的前缀,
        //   想「聪明一点」只退一部分的话,电脑上就会剩下半截拼音。
        assertEquals("<8×5>你好", desc("nihao", "你好"))
    }

    @Test
    fun `英文自动改正_只换掉不同的那几个字母`() {
        // teh -> the:公共前缀只有 "t"(第 2 个字符 e≠h 就分岔了),
        // 所以退 2 补 "he" —— **不是**「只换掉中间那个字母」。
        //
        // ★ 这条断言以前写的是 `<8×1>e`,那是**错的**:自动改正看着像「换个字母」,
        //   但差异算法只认公共前缀,不认「哪几个字母不同」。多退一格是它的**必然形状**,
        //   不是精度不够 —— 想做成「最小编辑距离」得让算法看得见 IME 的候选替换动作,
        //   而我们手里只有快照。多删一格在两处都成立:屏幕上多闪一下,结果一样对。
        assertEquals("<8×2>he", desc("teh", "the"))
        // 反过来也成立(改回去)
        assertEquals("<8×2>eh", desc("the", "teh"))
    }

    @Test
    fun `中间插字_只退被顶掉的那一段`() {
        // 「我好」→「我很好」:公共前缀「我」,退格 1(好),补打「很好」
        assertEquals("<8×1>很好", desc("我好", "我很好"))
    }

    @Test
    fun `粘贴_一大串一次过去`() {
        val long = "这是一段被粘贴进来的很长很长的话"
        assertEquals(desc("", long), long)
        assertEquals("", TypeSync.describe(TypeSync.diff(long, long)))
    }

    @Test
    fun `清空再重打_退到空再补`() {
        assertEquals("<8×2>xy", desc("ab", "xy"))   // 公共前缀为空
    }

    // ------------------------------------------------------------- 空与边界

    @Test
    fun `两边都空_什么都不发`() {
        assertTrue(ops("", "").isEmpty())
    }

    @Test
    fun `从空到空以外_只有补打没有退格`() {
        assertEquals(0, TypeSync.backspaceCount(ops("", "abc")))
        assertEquals("abc", desc("", "abc"))
    }

    @Test
    fun `退到空_只有退格没有补打`() {
        val o = ops("abc", "")
        assertEquals(3, TypeSync.backspaceCount(o))
        assertTrue("不该有 Append", o.none { it is TypeSync.Op.Append })
    }

    // ------------------------------------------- ★ 代理对(最容易写错的那一处)

    @Test
    fun `emoji 退格按码点算_不按 length`() {
        // "😀" 在 Kotlin 里 length == 2(两个 UTF-16 代码单元),但它是**一个**字符。
        // 按 length 发退格 = 删一半 → 电脑上留下一个乱码方块,而手机上看不出来。
        val emoji = "😀"
        assertEquals(2, emoji.length)                                  // 前提:确实是代理对
        assertEquals(1, TypeSync.backspaceCount(ops("a$emoji", "a")))   // 只该按 1 下
    }

    @Test
    fun `前缀不要停在代理对中间`() {
        // old="a😀", now="a😀b":公共前缀按代码单元算是 3(a + 两个代理单元),
        // 末尾正好是完整的,所以只补打 "b"、不按退格。
        val emoji = "😀"
        val o = ops("a$emoji", "a${emoji}b")
        assertEquals(0, TypeSync.backspaceCount(o))
        assertEquals("b", TypeSync.describe(o))

        // 而如果**只在中间换掉一个代理单元**(真实场景:输入法把一个 emoji 换成另一个,
        // 前面的高位代理恰好相同),前缀必须退回到代理对的**前面**,
        // 宁可多删一个字,也不能留下半个字符。
        val other = "😁"
        val o2 = ops("a$emoji", "a$other")
        assertEquals(1, TypeSync.backspaceCount(o2))
        // ★ 退完还要**补上**新的那个 emoji,所以完整描述是「退一格 + 😁」,
        //   不是光秃秃一个 😁 —— 只退不补的话电脑上会少一个字。
        assertEquals("<8×1>$other", TypeSync.describe(o2))
    }

    // --------------------------------------------------------------- 不变量

    @Test
    fun `正向追加永不产生退格`() {
        // 这是最要紧的一条不变量:**打新字绝不能删掉已有的字**。
        // 一旦这条破了,用户会在电脑上看到自己的字一个个消失,而原因在手机上完全看不见。
        var s = ""
        for (ch in "这是一个逐字追加的句子 with English 123".toCharArray()) {
            val o = ops(s, s + ch)
            assertEquals("追加『$ch』时不该按退格", 0, TypeSync.backspaceCount(o))
            s += ch
        }
    }

    @Test
    fun `算出来的操作把旧串变成新串`() {
        // 端到端性质:把 Op 序列「应用」回去,必须得到 now。
        // 用它扫一批随机/边角组合,比一条条写死更能抓住没想到的分支。
        val cases = listOf(
            "" to "", "a" to "a", "a" to "", "" to "a",
            "abc" to "abd", "abc" to "abcabc", "abcabc" to "abc",
            "你好世界" to "你好", "hello" to "hello world",
            "a b c" to "abc", "  " to " ", "😀a" to "a😀",
            "12345" to "1235", "xyz" to "xYz",
        )
        for ((old, now) in cases) {
            var cur = old
            for (op in TypeSync.diff(old, now)) {
                when (op) {
                    is TypeSync.Op.Backspace -> {
                        val n = op.times
                        // 退格 = 删掉**最后 n 个码点**
                        var end = cur.length
                        repeat(n) {
                            end -= if (end >= 2 && Character.isLowSurrogate(cur[end - 1])) 2 else 1
                        }
                        cur = cur.substring(0, end.coerceAtLeast(0))
                    }
                    is TypeSync.Op.Append -> cur += op.text
                }
            }
            assertEquals("『$old』→『$now』算出来做不到", now, cur)
        }
    }
}
