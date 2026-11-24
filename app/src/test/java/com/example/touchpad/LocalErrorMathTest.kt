package com.example.touchpad

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 见 [LocalErrorMath]:这句英文认不出来,她就会整轮哑掉,而且不报错。 */
class LocalErrorMathTest {

    private val real = """{"error":{"code":400,"message":"request (9215 tokens) exceeds the available context size (8192 tokens), try increasing it","type":"exceed_context_size_error","n_prompt_tokens":9215,"n_ctx":8192}}"""

    @Test
    fun 认得真机那条原话() {
        assertTrue(LocalErrorMath.isContextOverflow(real))
    }

    @Test
    fun 只认错类型也够() {
        assertTrue(LocalErrorMath.isContextOverflow("""{"type":"exceed_context_size_error"}"""))
    }

    @Test
    fun 别的400不该被当成上下文超了() {
        // 形状不对的请求转云端只会再错一次,还留下假线索。
        assertFalse(LocalErrorMath.isContextOverflow("""{"error":{"code":400,"message":"invalid model"}}"""))
        assertFalse(LocalErrorMath.isContextOverflow("empty content"))
        assertFalse(LocalErrorMath.isContextOverflow(null))
        assertFalse(LocalErrorMath.isContextOverflow("  "))
    }
}
