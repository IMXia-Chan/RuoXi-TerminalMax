package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「盘上那两套 ZipVoice 零件用哪一套」的测试。
 *
 * ★ 为什么这条判据值得单独钉住:它挑错版本的后果**不是报错** ——
 *   是拿一版的文件名去 ack 另一版的路径,`new OfflineTts` 直接失败,
 *   而日志里只有一行「嗓子: 装载失败(...)」。那一行看起来**像是文件没推上去**,
 *   于是人会跑去重推 130MB(甚至重装),而真因是这里的一个 Boolean。
 *
 * ★ 两个方向都钉(本项目的铁律:坏掉的判据和准的判据,在没有正控的测试里长得一样):
 *   该认的(fp32 成对 → 用它)**和该退的**(半套 fp32 → **退回 int8,不许赌**)。
 *
 * ★ 文件名那两条钉的是**真实事实**:上游 fp32 包里那两个文件就叫
 *   `encoder.onnx` / `decoder.onnx`,**没有** `.int8` 后缀。它们错一个字母
 *   就是「她说不出话」,所以字面量专门从 `SherpaVoice` 挪进了 `ZvPickMath`。
 */
class ZvPickMathTest {

    // ------------------------------------------------------------ 挑哪一套

    /** 两套都齐 → **fp32 优先**(这是他要的那一版)。 */
    @Test
    fun `两套都在_fp32优先`() {
        assertEquals(ZvPickMath.FP32, ZvPickMath.pick(true, true, true))
    }

    /** fp32 齐、int8 不在(他把 int8 删了)→ 照旧用 fp32,不许因为「没退路」就不敢用。 */
    @Test
    fun `只有fp32_用fp32`() {
        assertEquals(ZvPickMath.FP32, ZvPickMath.pick(true, false, true))
    }

    /** 只有 int8 → 用 int8。这是今天(还没推 fp32)的真实状态。 */
    @Test
    fun `只有int8_用int8`() {
        assertEquals(ZvPickMath.INT8, ZvPickMath.pick(false, true, false))
    }

    /**
     * ★★ 这条是整组里最要紧的一条:**fp32 只推了一半,必须落回 int8。**
     *
     * fp32 那两个文件是**先后到**的(decoder 477MB,要传一会儿)。推 decoder
     * 那几分钟里盘上是「一整套 int8 + 半个 fp32」。这时候要是认了 fp32,
     * 她**一开口就是装载失败 = 没嗓子** —— 而那几分钟他多半正在试听。
     * 成对才认,那几分钟里她照旧用 int8 说话,推完下次装载自己就换过去。
     */
    @Test
    fun `fp32只有半套_退回int8`() {
        assertEquals("有 encoder.onnx 没 decoder.onnx", ZvPickMath.INT8, ZvPickMath.pick(false, true, true))
    }

    /**
     * 两套都不全,但盘上有 fp32 的东西 —— 说明**他正在推 fp32**。
     * 这时候缺件清单要报 **fp32** 那一套的名字,那句话才指得对地方
     * (报「encoder.int8.onnx 不在」会把人支使去干错事)。
     */
    @Test
    fun `两套都不全_有fp32痕迹_报fp32缺件`() {
        assertEquals(ZvPickMath.FP32, ZvPickMath.pick(false, false, true))
    }

    /** 盘上什么都没有(新机 / 全删了)→ 报 int8 那一套的缺件(它是出厂默认)。 */
    @Test
    fun `盘上空的_报int8缺件`() {
        assertEquals(ZvPickMath.INT8, ZvPickMath.pick(false, false, false))
    }

    // ------------------------------------------------------------ 文件名

    /** ★★ **fp32 那两个文件没有 `.int8` 后缀** —— 这是上游的命名,不是我们的风格选择。 */
    @Test
    fun `fp32的文件名不带int8后缀`() {
        assertEquals("encoder.onnx", ZvPickMath.encoderName(ZvPickMath.FP32))
        assertEquals("decoder.onnx", ZvPickMath.decoderName(ZvPickMath.FP32))
    }

    /** int8 那两个带后缀。 */
    @Test
    fun `int8的文件名带int8后缀`() {
        assertEquals("encoder.int8.onnx", ZvPickMath.encoderName(ZvPickMath.INT8))
        assertEquals("decoder.int8.onnx", ZvPickMath.decoderName(ZvPickMath.INT8))
    }

    /**
     * 版本名认不出来时**落回 int8 那一套**,不许回空串、不许回 null ——
     * 回一个不存在的路径,症状同样是「她说不出话」,而且比这更难看懂。
     */
    @Test
    fun `版本名认不出来_落回int8`() {
        assertEquals(ZvPickMath.INT8_ENCODER, ZvPickMath.encoderName(""))
        assertEquals(ZvPickMath.INT8_ENCODER, ZvPickMath.encoderName("fp16"))
        assertEquals(ZvPickMath.INT8_DECODER, ZvPickMath.decoderName("FP32"))
    }

    /**
     * ★ 这两个字符串**会出现在 `model.log` 里**(`嗓子: 克隆音就绪【fp32】(...)`)。
     * 装机后验货就是 grep 这一行 —— 所以它们是**观察口径**,不是内部实现细节,
     * 改字之前先想清楚日志那一条还认不认。
     */
    @Test
    fun `版本名字面量是日志口径_钉住`() {
        assertEquals("fp32", ZvPickMath.FP32)
        assertEquals("int8", ZvPickMath.INT8)
    }
}
