package com.example.touchpad

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这个名字是不是一句窗口命令」的测试 —— 纯 JVM,不碰 Android。
 *
 * ★★ 为什么值得钉:
 * 这条判据**两个方向都会静默出错**,而且症状离原因很远:
 *
 * - **漏了**(该拦的没拦)⇒ 她把「关闭」打进用户 App 的搜索框 —— 用户看得见,但归因不到代码;
 * - **多拦**(不该拦的拦了)⇒ 一个**真内容**再也点不到,而且回执是「这是窗口动作,搜不了」,
 *   看起来像一条合理的解释,不像失败。
 *
 * 所以两个方向**各占一半**,而且是本项目的铁律:
 * 坏掉的判据和准的判据,在没有正控的测试里长得一模一样。
 */
class MissMathTest {

    // ---- 正控:先证明「该拦的会拦」,否则下面每条放行都可能是「它什么都不拦」 ----

    @Test
    fun `光是「关闭」两个字要拦`() {
        assertTrue(MissMath.isWindowCommand("关闭"))
    }

    @Test
    fun `光是「退出」两个字要拦`() {
        assertTrue(MissMath.isWindowCommand("退出"))
    }

    // ---- 真机日志里出现过的那个词,单独钉一条 ----

    /**
     * ★★ 这条是**用户报的那个 bug 本身**:
     * `click_ui {"name":"关闭"}` → 找不到 → 经验库喂它去 search → 打进微信的搜索框。
     * 谁要是哪天「优化」了判据让它不拦「关闭」,用户那句抱怨就会原样回来。
     */
    @Test
    fun `用户在微信里喊的那个词必须拦住`() {
        assertTrue(MissMath.isWindowCommand("关闭"))
        assertTrue(MissMath.isWindowCommand("退出"))
        assertTrue(MissMath.isWindowCommand("关掉"))
    }

    // ---- 同义/常见写法 ----

    @Test
    fun `常见的窗口命令都要拦`() {
        listOf(
            "关掉", "关上", "关闭窗口", "关闭这个窗口", "关闭当前窗口",
            "退出程序", "退出应用", "退出软件",
            "最小化", "最大化",
        ).forEach { assertTrue("「$it」应该被拦下", MissMath.isWindowCommand(it)) }
    }

    @Test
    fun `英文动词也要拦 —— 模型偶尔直接吐英文`() {
        listOf("close", "quit", "exit", "minimize", "maximize", "close window")
            .forEach { assertTrue("「$it」应该被拦下", MissMath.isWindowCommand(it)) }
    }

    @Test
    fun `大小写不敏感`() {
        assertTrue(MissMath.isWindowCommand("Close"))
        assertTrue(MissMath.isWindowCommand("QUIT"))
    }

    @Test
    fun `前后带空白、或者字中间被拆开,都还认得出`() {
        // 模型有时写成「关 闭」,或者带前后空格 —— 用户心里它们是同一个词
        assertTrue(MissMath.isWindowCommand("  关闭  "))
        assertTrue(MissMath.isWindowCommand("关 闭"))
        assertTrue(MissMath.isWindowCommand("关闭 窗口"))
    }

    @Test
    fun `被引号书名号括起来的名字还是同一个名字`() {
        // 提示词里到处是「」,模型照着学,常常把名字连同引号一起吐出来
        assertTrue(MissMath.isWindowCommand("「关闭」"))
        assertTrue(MissMath.isWindowCommand("\"退出\""))
        assertTrue(MissMath.isWindowCommand("《关闭》"))
    }

    // ---- ★★ 反方向:真内容一个都不许误伤 ----

    @Test
    fun `正常的界面文字要放行`() {
        listOf(
            "文件传输助手", "发送", "确定", "取消", "登录", "设置",
            "张三", "工作群", "收件箱", "开始",
        ).forEach { assertFalse("「$it」是真内容,不该拦", MissMath.isWindowCommand(it)) }
    }

    /**
     * ★★ 这一组是**精确匹配**那条取舍的证据。
     *
     * 这几个词**以窗口命令开头,但它们是真内容** —— 文件、聊天、设置项。
     * 搜它们是对的;要是当年写成「前缀匹配」,这几个就会一起被误伤,
     * 而误伤的代价是**那个目标永远点不到**。
     */
    @Test
    fun `以窗口命令开头、但是真内容的名字必须放行`() {
        listOf(
            "关闭按钮设计稿.txt",   // 一个文件
            "退出群聊",             // 微信里真有的菜单项
            "退出登录",             // 常见设置项
            "最小化能源消耗",       // 一个设置项/文件名
            "关闭的朋友",           // 一个会话名
        ).forEach { assertFalse("「$it」是真内容,不许被前缀连带误伤", MissMath.isWindowCommand(it)) }
    }

    @Test
    fun `长得像英文动词但不是的那些不算`() {
        // 「closed / closing / exits」都不是「就是一句 quit」
        listOf("closed", "closing", "exits", "exiting", "parser").forEach {
            assertFalse("「$it」不是窗口命令", MissMath.isWindowCommand(it))
        }
    }

    @Test
    fun `空名字和纯符号不拦`() {
        assertFalse(MissMath.isWindowCommand(""))
        assertFalse(MissMath.isWindowCommand("   "))
        assertFalse(MissMath.isWindowCommand("「」"))
    }

    /**
     * ★★ **已知的边界,不是「这样是对的」** —— 写在这儿免得下一个人以为它是漏的。
     *
     * 「关闭微信」这种「命令词 + 应用名」**今天拦不住**:精确匹配拦不到它。
     * 所以模型要是哪天不吐「关闭」、改吐「关闭微信」,这条老毛病会**换个名字回来**
     * (照样把「关闭微信」打进微信的搜索框)。
     *
     * ★ **为什么不顺手把它一起拦掉**:要拦就得做前缀匹配,而前缀匹配会连
     * 「关闭按钮设计稿.txt」「退出群聊」这些**真内容**一起误伤 ——
     * 那是拿「偶尔多打两个字」换「某个目标永远点不到」,不划算。
     *
     * ★ **真正的解法在另一处,不在这个文件里**:这条路只能兜住「整个名字就是一句
     * 窗口命令」。**命令词 + 应用名**这种写法靠词表兜不住,而前缀匹配又会误伤真内容。
     * ★ 2026-10-08 之后情况好了一层:工具箱里**有**「关窗口」这个动作了
     *   (`hotkey(alt+f4)`),拦住之后会把她引过去 —— 所以这一类的**出口**是通的,
     *   只是那条判据不负责认它们。见 [AiAgent.resolveMiss] 里那一段的注释。
     */
    @Test
    fun `已知边界 命令词带应用名今天拦不住`() {
        assertFalse(MissMath.isWindowCommand("关闭微信"))
        assertFalse(MissMath.isWindowCommand("帮我关闭微信"))
    }
}
