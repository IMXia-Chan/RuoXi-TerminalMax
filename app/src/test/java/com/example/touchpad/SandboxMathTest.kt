package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 沙盒那道**字符串闸**的测试 —— 纯 JVM,不起进程。
 *
 * 为什么值得钉:这条闸错了**不报错**。它错成「放行」的表现是命令照跑、看起来一切正常,
 * 只是它碰了沙盒外的东西;它错成「拦下」的表现是模型被一句它看不懂的错挡住,
 * 然后开始瞎换写法 —— 两种情况都不会在日志里留痕,只能靠结果反推。
 *
 * ★ 这里钉的是**规矩本身**(相对路径、不许 `..`、dev 下的重定向目标放行),
 *   不是「沙盒绝对进不去」—— 那是 chroot 的事,没有 root 拿不到,别在这儿假装。
 */
class SandboxMathTest {

    private fun ok(cmd: String) = assertNull("应当放行:$cmd", SandboxMath.reject(cmd))

    private fun blocked(cmd: String, mustMention: String? = null) {
        val why = SandboxMath.reject(cmd)
        assertNotNull("应当拦下:$cmd", why)
        if (mustMention != null) {
            assertTrue("回执要说出是哪儿不对,实际是:$why", why!!.contains(mustMention))
        }
    }

    // ---- 放行的那些 ----

    @Test
    fun `沙盒里的寻常命令都放行`() {
        ok("ls")
        ok("ls -l")
        ok("uname -a")
        ok("cat a.txt")
        ok("echo 你好 > a.txt && cat a.txt")
        ok("ls | wc -l")
        ok("grep -rn TODO . ")
        ok("mkdir -p work/src && cd work/src && touch main.c")
        // 相对路径里的斜杠不是绝对路径
        ok("cat sub/dir/file.txt")
    }

    @Test
    fun `dev 下那几个重定向目标放行`() {
        ok("ls 2>/dev/null")
        ok("echo hi > /dev/null")
        ok("cat a.txt 2>/dev/stderr")
    }

    // ---- 拦下的那些 ----

    @Test
    fun `宿主机绝对路径一律拦下,并且说出是哪一条`() {
        blocked("/system/bin/id", "/system/bin/id")
        blocked("cat /data/data/com.example.touchpad/files/x", "/data/data")
        blocked("ls /sdcard")
        // 单独的 `/` 是宿主机根目录 —— 最该挡住的那一下
        blocked("cd / && ls")
    }

    @Test
    fun `嗯号爬目录一律拦下`() {
        blocked("cd .. && ls")
        blocked("cat ../../x")
        blocked("ls ..")
    }

    @Test
    fun `命令里的点号不是嗯号`() {
        // 这一条是误伤防线:file..txt、a..b 这类名字不该被当成爬目录
        ok("cat file..txt")
        ok("ls a..b")
        ok("echo 1.2.3")
    }

    @Test
    fun `空命令拦下`() {
        blocked("   ")
    }

    // ---- 列路径的那半 ----

    @Test
    fun `hostPaths 只挑出沙盒外的绝对路径`() {
        assertEquals(listOf("/sdcard/x"), SandboxMath.hostPaths("cp a /sdcard/x"))
        assertEquals(emptyList<String>(), SandboxMath.hostPaths("ls 2>/dev/null"))
        assertEquals(emptyList<String>(), SandboxMath.hostPaths("cat a/b"))
        // 两个都得报出来 —— 只报一个,模型改掉一个再来一次,白跑一轮
        assertEquals(
            listOf("/data/x", "/sdcard/y"),
            SandboxMath.hostPaths("cp /data/x /sdcard/y"),
        )
    }

    // ---- 输出与措辞 ----

    @Test
    fun `长输出要截断并说明原长`() {
        val long = "x".repeat(100)
        val c = SandboxMath.clip(long, 10)
        assertTrue(c.startsWith("x".repeat(10)))
        assertTrue(c.contains("100"))
    }

    @Test
    fun `短输出原样返回`() {
        assertEquals("hi", SandboxMath.clip("hi\n", 10))
    }

    @Test
    fun `退出码 0 不说成成功,127 要说命令没找到`() {
        assertTrue(SandboxMath.verdict(0, false, "", "").contains("任何输出"))
        assertTrue(SandboxMath.verdict(127, false, "", "").contains("没找到"))
        assertTrue(SandboxMath.verdict(1, false, "e", "").contains("1"))
        assertTrue(SandboxMath.verdict(0, true, "", "").contains("超时"))
    }
}
