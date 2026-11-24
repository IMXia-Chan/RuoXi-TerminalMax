package com.example.touchpad

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 冷算的开算条件。数字变了这里必须跟着变 —— 它是唯一能机器验的地方。 */
class PrefillMathTest {

    @Test
    fun noTemperatureMeansNo() {
        // 读不到温度就硬算 = 11-08 那次重演。宁可晚一点算。
        assertFalse(PrefillMath.shouldStart(null, charging = true))
        assertFalse(PrefillMath.shouldStart(null, charging = false))
    }

    @Test
    fun chargingUsesTheLooserCap() {
        assertTrue(PrefillMath.shouldStart(38.0f, charging = true))
        assertFalse(PrefillMath.shouldStart(38.1f, charging = true))
        assertTrue(PrefillMath.shouldStart(30.0f, charging = true))
    }

    @Test
    fun onBatteryUsesTheStricterCap() {
        assertTrue(PrefillMath.shouldStart(34.0f, charging = false))
        assertFalse(PrefillMath.shouldStart(34.1f, charging = false))
        // 插电时能算的温度，没插电时不许算 —— 满载 30–60 分钟要电池撑。
        assertFalse(PrefillMath.shouldStart(36.0f, charging = false))
        assertTrue(PrefillMath.shouldStart(36.0f, charging = true))
    }

    @Test
    fun whySaysTheReasonInPlainWords() {
        assertTrue(PrefillMath.why(42.0f, charging = true).contains("等凉"))
        assertTrue(PrefillMath.why(30.0f, charging = true).contains("可以冷算"))
        assertTrue(PrefillMath.why(null, charging = true).contains("读不到"))
    }
}
