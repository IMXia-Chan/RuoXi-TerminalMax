package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「打的字 → 关键词 token」。
 *
 * 词表用的是从真机 `tokens.txt` 里抄下来的那一批形状（声母 + 带调韵母），
 * 只取了测试用得上的那几个 —— **结构是真的，规模是缩的**。
 */
class WakeTokenMathTest {

    private val vocab = setOf(
        "c", "óng", "m", "àn",          // 从 漫
        "zh", "ōng", "g", "uó",         // 中 国
        "ān", "ài", "n",                // 安 爱
        "z", "h",                       // 用来验「zh 不能被切成 z + h」
        "y", "ī", "l", "èi", "er", "éi",
    )

    private fun py(map: Map<String, String>): (String) -> String = { map[it] ?: "" }

    @Test
    fun twoCharacterWord() {
        val r = WakeTokenMath.toTokens("从漫", vocab, py(mapOf("从" to "cóng", "漫" to "màn")))
        assertEquals(listOf("c", "óng", "m", "àn"), r.getOrThrow())
    }

    @Test
    fun zhInitialWinsOverZ() {
        // 词表里同时有 "z" 和 "zh"：必须选长的，否则 zhōng 会变成 z + hōng，
        // 而 hōng 不在词表里 —— 那正是「翻不出来」的静默来源之一。
        val r = WakeTokenMath.toTokens("中国", vocab, py(mapOf("中" to "zhōng", "国" to "guó")))
        assertEquals(listOf("zh", "ōng", "g", "uó"), r.getOrThrow())
    }

    @Test
    fun bareFinalHasNoInitial() {
        val r = WakeTokenMath.toTokens("安爱", vocab, py(mapOf("安" to "ān", "爱" to "ài")))
        assertEquals(listOf("ān", "ài"), r.getOrThrow())
    }

    @Test
    fun capitalAndSpacesAreNormalized() {
        val r = WakeTokenMath.toTokens("从安", vocab, py(mapOf("从" to " Cóng ", "安" to "ān")))
        assertEquals(listOf("c", "óng", "ān"), r.getOrThrow())
    }

    @Test
    fun singleCharacterIsRejected() {
        // 一个字会被**任何一句带这个音的话**触发 —— 见 WakeTokenMath.MIN_LEN。
        val r = WakeTokenMath.toTokens("安", vocab, py(mapOf("安" to "ān")))
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("至少"))
    }

    @Test
    fun missingTokenNamesTheCharacter() {
        val tiny = setOf("c", "óng")   // 没有 m / àn
        val r = WakeTokenMath.toTokens("从漫", tiny, py(mapOf("从" to "cóng", "漫" to "màn")))
        assertTrue(r.isFailure)
        val msg = r.exceptionOrNull()!!.message!!
        assertTrue(msg.contains("漫"))
    }

    @Test
    fun nonHanIsRejected() {
        val r = WakeTokenMath.toTokens("abc", vocab, py(emptyMap()))
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("不是汉字"))
    }

    @Test
    fun emptyAndTooLong() {
        assertTrue(WakeTokenMath.toTokens("   ", vocab, py(emptyMap())).isFailure)
        assertTrue(WakeTokenMath.toTokens("一二三四五", vocab, py(emptyMap())).isFailure)
    }

    @Test
    fun lineFormatMatchesTheModel() {
        // 盘上现在那一行是：`c óng m àn @从漫`
        // 新写的这一行多了奖励分与阈值 —— 语法是 sherpa-onnx 的 `:score #threshold @name`。
        assertEquals(
            "c óng m àn :1.0 #0.25 @从漫",
            WakeTokenMath.line("从漫", listOf("c", "óng", "m", "àn"), 0.25f),
        )
    }

    @Test
    fun looseThresholdKeepsTwoDecimals() {
        assertEquals(
            "ān :1.0 #0.20 @安",
            WakeTokenMath.line("安", listOf("ān"), 0.20f),
        )
    }
}
