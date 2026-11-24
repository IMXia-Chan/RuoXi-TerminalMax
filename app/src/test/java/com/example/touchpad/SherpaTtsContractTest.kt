package com.example.touchpad

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ★★★ 一条**不许再犯**的钉子:合成**不能用带回调的那条路**(`generateWithCallback`)。
 *
 * ## 为什么这条钉子必须存在
 *
 * 2026-10-05 上午,这一条让 App **在真机上连崩四次**(13:11:06 / 13:12:16 / 13:12:47 / 13:14:32),
 * 线程全是 `her-voice`。`dumpsys dropbox` 里的墓碑原文:
 *
 * ```
 * signal 6 (SIGABRT)
 * Abort message: 'No pending exception expected: java.lang.NoSuchMethodError:
 *   no non-static method
 *   "Lcom/example/touchpad/SherpaVoice$$ExternalSyntheticLambda0;.invoke([F)Ljava/lang/Integer;"'
 *   at OfflineTts.generateWithCallbackImpl (Tts.kt:-2)
 *   at com.example.touchpad.SherpaVoice.runOne (SherpaVoice.kt:359)
 * ```
 *
 * ★★ **它 `catch` 不住。** 那是 native 层 `abort()`,不是 Java 异常 —— `try/catch(Throwable)`
 * 和 `finally` **都不会执行**,日志也来不及写。所以**它没法用「跑一遍看会不会崩」来防**:
 * 崩的是整个进程,**测试根本跑不完 = 等于没测**。
 *
 * ★ 所以这条判据只能落在**源码层面**:只要有人把调用写回 `generateWithCallback`,
 * 这里就红。这是唯一挡得住它的位置。
 *
 * ## 判据为什么是「代码里」而不是「整个文件里」
 *
 * `SherpaVoice.kt` 里**故意**留着这个 API 的名字 —— 在 KDoc 和注释里,记着它是怎么崩的
 * (第 60 行、447 行)。**那些字是要留的**,是防止下次有人"顺手改回去"的说明书。
 * 所以这里只扫**代码**,把注释行排掉。
 *
 * 自带的那份 `com/k2fsa/sherpa/onnx/Tts.kt` 是**上游 vendor 进来的**,它当然要定义这个 API
 * —— 整个目录跳过,不改上游的任何一行。
 */
class SherpaTtsContractTest {

    /** 我们要守的那条路。 */
    private val forbidden = "generateWithCallback"

    /**
     * 找 `src/main/java` 的根。
     *
     * ★ **找不到必须炸,不许静默跳过。** 一个"找不到就通过"的守卫,比没有守卫更坏 ——
     *   它会在真的出事时给出一个绿色的假信号。这正是这个项目最恨的那种失败
     *   (`ruoxi-eval-harness-traps`:量具坏了,读出来的数还是像模像样的)。
     */
    private fun mainSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
        )
        candidates.firstOrNull { it.isDirectory }?.let { return it }
        // 兜底:从当前目录往上找(IDE 里跑单测时工作目录不一定是模块根)
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = dir?.let { File(it, "app/src/main/java") }
            if (hit != null && hit.isDirectory) return hit
            dir = dir?.parentFile
        }
        fail(
            "找不到 src/main/java —— 这条守卫**没有生效**,而不是通过了。" +
                "工作目录=${File("").absolutePath}。" +
                "请修好路径探测,别把它删掉:它守的是那个会把整个进程 abort 掉的 API。"
        )
        error("unreachable")
    }

    /**
     * 把一行里的**注释部分**切掉,只留代码。
     *
     * 这是个**刻意从简**的判断,够用就行:我们防的是"有人写了一行调用",那是代码;
     * 而这里真正要放过的是 KDoc(`*` 开头)和行尾注释。不追求完整的 Kotlin 词法分析。
     */
    private fun codeOnly(line: String): String {
        val t = line.trimStart()
        if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) return ""
        val slash = line.indexOf("//")
        return if (slash >= 0) line.substring(0, slash) else line
    }

    private fun kotlinSources(root: File): List<File> =
        root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            // 上游 vendor,不改它一个字
            .filterNot { it.invariantSeparatorsPath.contains("com/k2fsa/sherpa/onnx/") }
            .toList()

    @Test
    fun `不许再用带回调的合成`() {
        val root = mainSourceRoot()
        val files = kotlinSources(root)
        assertTrue(
            "一个 .kt 都没扫到,路径探测错了 —— 这条断言没在干活(root=$root)",
            files.size > 20
        )

        val hits = mutableListOf<String>()
        for (f in files) {
            f.readLines().forEachIndexed { i, raw ->
                if (codeOnly(raw).contains(forbidden)) {
                    hits += "${f.name}:${i + 1}  ${raw.trim()}"
                }
            }
        }
        if (hits.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("★★★ 有人在代码里用回了 `$forbidden` —— 这会让 App 在真机上")
                    appendLine("直接 SIGABRT(不是异常,是 native abort,进程整个没)。")
                    appendLine("2026-10-05 上午连崩四次,墓碑在 SherpaVoice.kt 文件头的 KDoc 里。")
                    appendLine()
                    appendLine("改用不带回调的那条路:`tts.generate(text, speakerId, speed)`。")
                    appendLine("它在 JNI 里一次 lambda 都不碰,所以同一个坑不可能再踩一次。")
                    appendLine()
                    appendLine("命中位置:")
                    hits.forEach { appendLine("  $it") }
                }
            )
        }
    }

    /**
     * 钉子还要钉住**正解本身还在**。
     *
     * 只禁掉错的那条是不够的 —— 如果有人把合成整段删了/注释了,上面那条测试照样绿,
     * 而她会变成哑巴(而且 `filesPresent` 那种"读不到说成不在"的失败模式**不报错**)。
     */
    @Test
    fun `不带回调的合成还在用`() {
        val root = mainSourceRoot()
        val voice = kotlinSources(root).firstOrNull { it.name == "SherpaVoice.kt" }
            ?: fail("没找到 SherpaVoice.kt —— 路径探测错了,这条断言没在干活").let { error("unreachable") }

        val callsIt = voice.readLines().any { codeOnly(it).contains("tts.generate(") }
        assertTrue(
            "SherpaVoice.kt 里找不到不带回调的 `tts.generate(` —— " +
                "要么合成被删了(她会变成哑巴且不报错),要么又改回带回调那条路了。",
            callsIt
        )
    }
}
