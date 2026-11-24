package com.example.touchpad

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 「这两句话像不像」—— **纯函数，零 Android 依赖，能跑 JVM 单测**。
 *
 * ## 它是什么，不是什么（必须说清，否则就是假的安全感）
 *
 * 它算的是**字面形状**（中文按相邻两字切成小块，英文按词），再把这些小块散列进
 * 256 个桶里做余弦相似度。这是 FastText 那一路的散列技巧，**不是神经网络向量**：
 *
 * - **能做到**：说过的话里有同一批字/词，就找得回来。「我养了只猫叫团子」和
 *   「团子今天不吃东西」会靠「团子」两个字对上 —— 这是日常回忆的绝大多数情况。
 * - **做不到**：换一个说法就认不出来（「喜欢听周杰伦」和「爱听杰伦的歌」只有
 *   「杰伦」两字对得上，勉强）。真语义要靠一个嵌入模型，这台机器上没有，
 *   而为了这个再内嵌一个模型不划算（见 [MemoryStore] 文件头）。
 *
 * ★ 所以界面上**不许写「语义检索」**，只写「她想起以前说过的事」。
 *
 * ## 为什么是散列进桶
 *
 * 不用词典：中文没有空格，现成词典要么大、要么漏；散列不需要任何词表，
 * 256 个桶、每桶一个浮点数，一条记忆 1KB，八百条不到 1MB —— 手机上完全放得下。
 * 代价是**碰撞**：不同的字可能落进同一个桶。用符号散列（同一个字永远加正号或
 * 永远加负号）能让碰撞互相抵消一部分，这是标准做法。
 */
internal object VecMath {

    /** 桶的数量。256 对「日常聊天量级」够用，一条记忆 1KB。改它会作废旧档案。 */
    const val DIM = 256

    /**
     * 切块。中文按**相邻两字**（一个字太泛：「的」「我」到处都是），
     * 英文/数字按整词（小写）。空白、标点一律丢掉。
     *
     * ★ 单个汉字组成的长串（中文没有空格）也会被两两切开 —— 「我今天很累」
     *   → 我今 / 今天 / 天很 / 很累。
     */
    internal fun tokens(s: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        var prevCjk: Char? = null
        fun flushWord() {
            if (word.length >= 1) out.add(word.toString().lowercase())
            word.setLength(0)
        }
        for (c in s) {
            when {
                isCjk(c) -> {
                    flushWord()
                    val p = prevCjk
                    if (p != null) out.add("" + p + c)
                    prevCjk = c
                }
                c.isLetterOrDigit() -> {
                    prevCjk = null
                    if (word.length < 24) word.append(c)
                }
                else -> {
                    prevCjk = null
                    flushWord()
                }
            }
        }
        flushWord()
        return out
    }

    private fun isCjk(c: Char): Boolean =
        c in '一'..'鿿' || c in '㐀'..'䶿' || c in '豈'..'﫿'

    /** 散列：FNV-1a 32 位。同一个词在任何一台机器上都是同一个数（不依赖 Java 的 hashCode）。 */
    private fun hash(t: String): Int {
        var h = -0x7ee3623b // 2166136261
        for (c in t) {
            h = h xor c.code
            h *= 16777619
        }
        return h
    }

    /** 一段文字的向量（已 L2 归一化，余弦 = 点积）。 */
    internal fun vector(s: String): FloatArray {
        val v = FloatArray(DIM)
        val counts = HashMap<Int, Int>()
        for (t in tokens(s)) {
            val h = hash(t)
            val idx = (h ushr 1) % DIM
            // 符号散列：同一个词永远同一个方向，碰撞时正负相消，不至于互相加强。
            val sign = if (h and 1 == 0) 1 else -1
            counts[idx] = (counts[idx] ?: 0) + sign
        }
        var norm = 0.0
        for ((i, c) in counts) {
            // ★ c == 0 必须跳过：两个不同符号的词撞进同一个桶会正负抵消，计数归零。
            //   不跳过的话下面那步会按 `1 + ln(1+0)` 给它一个**满权重 1.0** ——
            //   等于「两个词互相抵消」这件事本身变成了一个独立的词，白送一段相似度。
            if (c == 0) continue
            // 次线性词频：说十遍也不比说一遍重要十倍。
            val w = (if (c > 0) 1.0 else -1.0) * (1.0 + ln(1.0 + kotlin.math.abs(c)))
            v[i] = w.toFloat()
            norm += w * w
        }
        if (norm <= 0.0) return v
        val n = sqrt(norm)
        for (i in v.indices) v[i] = (v[i] / n).toFloat()
        return v
    }

    /** 两个归一化向量的余弦（-1~1）。任一个是零向量就是 0 —— 没有相似度可言。 */
    internal fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var s = 0.0
        for (i in a.indices) s += a[i].toDouble() * b[i]
        return s.coerceIn(-1.0, 1.0)
    }

    /** 两段文字的相似度。空串一律 0 —— 空串和任何东西「都像」是它最容易骗人的地方。 */
    internal fun similarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        return cosine(vector(a), vector(b))
    }
}
