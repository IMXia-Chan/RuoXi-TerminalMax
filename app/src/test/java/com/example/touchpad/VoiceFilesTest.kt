package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「模型文件怎么了」那三种状态的测试。
 *
 * ★★ 这个测试防的错**不抛异常、不报错,只说一句假话** ——
 *   老的判断把「进不去目录」和「文件不在」说成同一件事,而这两种病的修法
 *   ( `chmod` vs 推文件)**完全不同**。2026-10-05 就是被那句假话支使着
 *   去找了一个明明在盘上的模型。
 *
 * 所以这里钉的不是行为,是**三个不同的人话**必须真的不同。
 */
class VoiceFilesTest {

    @Test
    fun `齐全时不该有话说`() {
        assertNull(VoiceFiles.reasonFor("/x/model.onnx", exists = true, canRead = true, length = 1234))
    }

    /**
     * ★ 这条是这一整轮的核心:「在、但读不到」**必须**和「不在」说得不一样。
     * 两者都返回 null(判成好)或者都返回同一句话,这个测试就红了。
     */
    @Test
    fun `不在和读不到必须说得不一样`() {
        val gone = VoiceFiles.reasonFor("/x/model.onnx", exists = false, canRead = false, length = 0)!!
        val locked = VoiceFiles.reasonFor("/x/model.onnx", exists = true, canRead = false, length = 0)!!

        assertTrue("「不在」得说不在:$gone", gone.contains("不在"))
        assertTrue("「读不到」不能说成不在:$locked", !locked.contains("不在"))
        assertTrue("「读不到」得点出权限:$locked", locked.contains("权限"))
    }

    /**
     * ★ 空文件和「不在」也不能混 —— 传输断在半路时**文件是存在的**,
     *   看起来「推过了」,不去重推的话会一直以为问题在别处。
     */
    @Test
    fun `空文件是第三种病`() {
        val empty = VoiceFiles.reasonFor("/x/model.onnx", exists = true, canRead = true, length = 0)!!
        assertTrue("空文件不能说成不在:$empty", !empty.contains("不在"))
        assertTrue("空文件得点出要重推:$empty", empty.contains("重推"))
    }

    /** 路径必须在消息里 —— 「缺件」四个字帮不上任何忙,他要能直接照着去动那个文件。 */
    @Test
    fun `三种说法都带着路径`() {
        val p = "/sdcard/Android/data/com.example.touchpad/files/tts/eula/model.onnx"
        for (r in listOf(
            VoiceFiles.reasonFor(p, exists = false, canRead = false, length = 0),
            VoiceFiles.reasonFor(p, exists = true, canRead = false, length = 0),
            VoiceFiles.reasonFor(p, exists = true, canRead = true, length = 0),
        )) {
            assertTrue("每条都要带路径:$r", r!!.contains(p))
        }
    }

    @Test
    fun `齐了就不报`() {
        val top = java.io.File("/root/files")
        val dir = java.io.File("/root/files/tts/eula")
        // 这条会真去问盘,而 JVM 测试机上这些目录**不存在** ——
        // 于是它走的是「目录还不存在」那条支路。这正好也钉住了:
        // 目录走不通时**只报一句**,不是把三个文件各报一遍「不在」(那三句都是假的)。
        val out = VoiceFiles.problems(top, dir, listOf("model.onnx", "lexicon.txt", "tokens.txt"))
        assertEquals("目录走不通时只报一句:$out", 1, out.size)
    }

    /**
     * ★★ 目录版的三分法 —— 为什么它**必须和文件那套分开**,而不是复用同一个函数。
     *
     * ZipVoice 那份嗓子里有个 `espeak-ng-data/`,它是个**目录**。拿上面那个
     * `reasonFor` 去量它,`length()` 会回 0 或者 4096(和里面装了多少东西无关),
     * 于是它永远落进「在,但是空的 —— 得重推」那一支。**那是一句假话**:
     * 一个装得好好的目录会被报成「传输断在半路」,而人会照着去重推 195MB。
     *
     * ★ 所以这个测试钉的不是「目录也能判定」,是**「目录的三句话互不冒用」**。
     */
    @Test
    fun `目录进不去要说成权限,不能和空目录混`() {
        val lockedRaw = VoiceFiles.reasonForDir(
            "/x/asr", exists = true, canRead = true, canExecute = false, entryCount = 0,
        )
        assertTrue("进不去必须报出来,不许判成好", lockedRaw != null)
        val locked = lockedRaw!!
        assertTrue("进不去是权限问题:$locked", locked.contains("权限"))
        assertTrue("不能把进不去说成不在:$locked", !locked.contains("不在"))

        val emptyRaw = VoiceFiles.reasonForDir(
            "/x/asr", exists = true, canRead = true, canExecute = true, entryCount = 0,
        )
        assertTrue("空目录必须报出来,不许判成好", emptyRaw != null)
        val empty = emptyRaw!!
        assertTrue("空目录要说重推:$empty", empty.contains("传输"))
        assertTrue("空目录不能说成权限:$empty", !empty.contains("权限"))
    }

    @Test
    fun `目录齐了就不报,不在就说不在`() {
        assertNull(
            VoiceFiles.reasonForDir("/x/asr", exists = true, canRead = true, canExecute = true, entryCount = 7),
        )
        assertEquals(
            "/x/asr(不在)",
            VoiceFiles.reasonForDir("/x/asr", exists = false, canRead = false, canExecute = false, entryCount = 0),
        )
    }
}
