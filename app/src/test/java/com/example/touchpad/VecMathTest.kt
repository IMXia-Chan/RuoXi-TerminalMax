package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「她想起以前说过的话」那把尺子的测试。
 *
 * ★ 这里钉的不是「准不准」（它不是语义模型，见 [VecMath] 文件头），而是两条**会静默
 *   骗人的边界**：
 *   - 空串和任何东西都「像」—— 那会让每轮都塞进一堆无关记忆；
 *   - 完全不相干的两句也被判成像 —— 那就是「她开始胡联系」。
 */
class VecMathTest {

    @Test
    fun 同一句话和自己最像() {
        assertEquals(1.0, VecMath.similarity("我今天很累", "我今天很累"), 1e-6)
    }

    @Test
    fun 说过同一件事的句子比不相干的高() {
        val a = VecMath.similarity("我养了只猫叫团子", "团子今天不吃东西")
        val b = VecMath.similarity("我养了只猫叫团子", "明天记得交数学作业")
        assertTrue("相关的 $a 应该高于不相干的 $b", a > b)
    }

    @Test
    fun 不相干的两句不该被判成像() {
        val s = VecMath.similarity("我养了只猫叫团子", "明天记得交数学作业")
        assertTrue("$s 太高了", s < 0.28)
    }

    @Test
    fun 空串不给相似度() {
        assertEquals(0.0, VecMath.similarity("", "我今天很累"), 1e-9)
        assertEquals(0.0, VecMath.similarity("我今天很累", "   "), 1e-9)
    }

    @Test
    fun 英文大小写不影响() {
        assertEquals(1.0, VecMath.similarity("Hello World", "hello world"), 1e-6)
    }

    @Test
    fun 中文按相邻两字切() {
        assertTrue(VecMath.tokens("我今天很累").contains("今天"))
        assertTrue(VecMath.tokens("我今天很累").contains("天很"))
    }

    @Test
    fun 向量是归一化的_余弦才不会随句子长短漂() {
        val v = VecMath.vector("今天心情不错我们去吃饭吧")
        val n = kotlin.math.sqrt(v.fold(0.0) { acc, x -> acc + x * x })
        assertEquals(1.0, n, 1e-5)
    }
}
