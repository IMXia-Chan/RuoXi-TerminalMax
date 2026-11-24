package com.example.touchpad

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 拿 [eval/cases.txt] 逐条过一遍快通道。
 *
 * 断言只有一条:**快通道只要开口,就必须是对的。**
 * 覆盖多少条不 assert —— 那是收益,不是正确性。漏掉的会照常交给模型,
 * 行为和没有快通道时一模一样,所以「漏」永远是安全的;「错」才是事故。
 *
 * 跑:`./gradlew :app:testDebugUnitTest`
 */
class FastPathTest {

    private data class Case(val n: Int, val say: String, val want: String, val arg: String)

    /** 从模块目录(app/)往仓库根上找,免得把路径写死。 */
    private fun casesFile(): File {
        val candidates = listOf("../eval/cases.txt", "eval/cases.txt", "../../eval/cases.txt")
        for (p in candidates) {
            val f = File(p)
            if (f.isFile) return f
        }
        throw AssertionError(
            "找不到 eval/cases.txt(工作目录是 ${File(".").absolutePath})。" +
                "这个测试就是靠它盯着快通道的,不能跳过。")
    }

    private fun loadCases(): List<Case> = casesFile().readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapIndexed { i, line ->
            val parts = line.split("|").map { it.trim() }
            require(parts.size >= 2) { "cases.txt 第 ${i + 1} 行格式不对: $line" }
            Case(i + 1, parts[0], parts[1], parts.getOrElse(2) { "" })
        }

    @Test
    fun `快通道一旦命中就必须正确`() {
        val cases = loadCases()
        val wrong = mutableListOf<String>()
        val handled = mutableListOf<Case>()
        val passedThrough = mutableListOf<Case>()

        for (c in cases) {
            val hit = FastPath.match(c.say)
            if (hit == null) {
                passedThrough += c          // 交给模型,安全
                continue
            }
            handled += c

            // ★ 点击必须是 strict 的 —— 这条不是风格问题,是整个改动的安全前提。
            //
            // FastPath 发出去的 click_ui 一旦失败,会走 AiAgent.resolveMiss:滚用户的
            // 窗口 → 往那个窗口的搜索框里打字 → 叫云端老师 → 还可能要花 215 秒看图,
            // **然后**才交回模型重来一遍。而规则层本来就是「宁可漏不可错」的一层,
            // 它切错的代价必须便宜。带上 strict,dispatch 就把那一次失败原样交回来,
            // 代价只剩「电脑端那次 find」(实测 1 秒上下)。
            //
            // 少了这个标记不会有任何报错,只会在真机上悄悄退化 —— 所以在这儿钉死。
            if (hit is FastPath.Hit.Tool && hit.tool == "click_ui") {
                assertTrue(
                    "#${c.n} 「${c.say}」发出的 click_ui 没带 strict —— " +
                        "规则切错的代价会从 1 秒变成「先动用户界面再交给模型」(见 FastPath 类注释)",
                    hit.args["strict"] == "true")
            }

            // 判分口径和 eval/run_eval.py 的 judge() 刻意保持一致
            val (gotTool, gotArgs) = when (hit) {
                is FastPath.Hit.Tool -> hit.tool to hit.args.values.joinToString(" ")
            }
            val why: String? = when {
                c.want == "-" ->
                    if (gotTool == null) null else "不该调工具,却调了 $gotTool"
                gotTool == null ->
                    if (c.want == "-") null else "该调 ${c.want},却只回了一句话"
                gotTool !in c.want.split("/") -> "调成 $gotTool 了(应该 ${c.want})"
                c.arg.isNotEmpty() && !gotArgs.contains(c.arg) ->
                    "$gotTool 的参数里没有「${c.arg}」(实际 $gotArgs)"
                else -> null
            }
            if (why != null) wrong += "  #%-2d %-22s %s".format(c.n, c.say, why)
        }

        val pct = 100.0 * handled.size / cases.size
        println("\n===== 快通道覆盖报告 =====")
        println("用例 %d 条:快通道接掉 %d 条(%.0f%%),交给模型 %d 条"
            .format(cases.size, handled.size, pct, passedThrough.size))
        println("接掉的这些 = 一次模型都不用跑,省下的就是实打实的电和温度")
        if (passedThrough.isNotEmpty()) {
            println("\n交给模型的(安全,但没省下电):")
            passedThrough.forEach { println("  #%-2d %s".format(it.n, it.say)) }
        }
        println("==========================\n")

        assertTrue(
            "快通道切错了 ${wrong.size} 条 —— 这比不省电严重得多,必须修:\n" +
                wrong.joinToString("\n"),
            wrong.isEmpty())
    }
}
