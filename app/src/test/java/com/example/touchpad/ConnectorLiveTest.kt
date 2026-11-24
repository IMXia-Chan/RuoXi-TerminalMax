package com.example.touchpad

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * **连接器目录真的连得通**：按目录里的声明拼 URL、发出去、按 `pick` 路径摘字段。
 *
 * ★ 默认不跑（不联网）：
 * ```
 * gradle :app:test --tests "*ConnectorLiveTest*" -DliveApi=1
 * ```
 *
 * ★ 为什么非要有：目录里一条「看起来对、实际 404 / 字段改名」的接口，
 *   装完在她那儿只表现成「这个工具查不到东西」—— 而她会照着记忆编一个答案。
 *   离线测试只能证明「声明自洽」，证明不了「对面认这个地址、这个字段还在」。
 *
 * ★ 这里跑的是**真代码**：[ApiMath.buildUrl] 拼地址、[ApiMath.walk] 摘字段 ——
 *   和手机上走的是同一份，不是照着抄一遍。
 */
class ConnectorLiveTest {

    private val live: Boolean = System.getProperty("liveApi") == "1"

    /** 每一条的示例参数。**新加连接器时这里必须补一行**，否则下面的穷举测试会失败。 */
    private val samples: Map<String, Map<String, String>> = mapOf(
        "geocode/find_place" to mapOf("name" to "上海"),
        "fx/exchange_rate" to mapOf("base" to "USD"),
        "gh/github_repo" to mapOf("owner" to "torvalds", "repo" to "linux"),
        "gh/github_user" to mapOf("user" to "torvalds"),
        "gh/github_search" to mapOf("q" to "android bluetooth"),
        "country/country_info" to mapOf("name" to "日本"),
        "ipinfo/my_ip" to emptyMap(),
        "ipinfo/ip_lookup" to mapOf("ip" to "8.8.8.8"),
        "openmeteo/weather_forecast" to
            mapOf("latitude" to "28.68", "longitude" to "115.89", "days" to "3"),
        "crossref/search_papers" to mapOf("q" to "graphene battery"),
        "arxiv/arxiv_search" to mapOf("q" to "diffusion model"),
        "holidays/public_holidays" to mapOf("year" to "2026", "country" to "CN"),
        "translate/translate" to mapOf("text" to "hello", "from" to "en", "to" to "zh"),
    )

    @Test
    fun `目录里每一条都要有示例`() {
        for (c in ConnectorCatalog.all) {
            if (c.needsKey) continue
            for (t in c.tools) {
                assertTrue(
                    "新加的连接器 ${c.id}/${t.name} 没给示例参数",
                    samples.containsKey("${c.id}/${t.name}"),
                )
            }
        }
    }

    @Test
    fun `每一条都真的连得通、摘得到字段`() {
        assumeTrue("没给 -DliveApi=1，跳过", live)
        val failures = ArrayList<String>()
        for (c in ConnectorCatalog.all) {
            if (c.needsKey) continue
            for (t in c.tools) {
                val args = samples["${c.id}/${t.name}"] ?: continue
                val url = ApiMath.buildUrl(
                    baseUrl = c.baseUrl,
                    path = t.exec.path,
                    fixedQuery = t.exec.fixedQuery,
                    queryNames = t.exec.query,
                    args = args,
                )
                if (url == null) {
                    failures.add("${c.id}/${t.name}: 地址拼不出来")
                    continue
                }
                val body = fetch(url)
                if (body == null) {
                    failures.add("${c.id}/${t.name}: 请求失败 $url")
                    continue
                }
                val root = runCatching { JSONObject(body) }.getOrNull()
                    ?: runCatching { JSONArray(body) as Any }.getOrNull()
                if (root == null) {
                    // arXiv 返回的是 XML，声明里 pick 也是空的 —— 只要 200 拿到了正文就算通。
                    if (t.exec.pick.isBlank()) {
                        println("ok ${c.id}/${t.name} → ${body.length} 字（非 JSON）")
                        continue
                    }
                    failures.add("${c.id}/${t.name}: 回的就不是 JSON，但 pick=${t.exec.pick}")
                    continue
                }
                if (t.exec.pick.isBlank()) {
                    println("ok ${c.id}/${t.name} → 整份 ${body.length} 字")
                    continue
                }
                val picked = ApiMath.walk(root, ApiMath.pathSegments(t.exec.pick))
                // ★ 把 URL 一起打出来：**「通了但为空」和「地址拼错了」在结果上
                //   长得一模一样**（都是一个空数组），只有地址能分清这两种。
                if (picked == null) {
                    failures.add("${c.id}/${t.name}: 摘不到「${t.exec.pick}」\n    $url")
                } else if (picked is JSONArray && picked.length() == 0 &&
                    picked.toString().length < 4
                ) {
                    failures.add("${c.id}/${t.name}: 「${t.exec.pick}」是空的\n    $url")
                } else {
                    println(
                        "ok ${c.id}/${t.name} → 「${t.exec.pick}」拿到 ${picked.toString().length} 字\n" +
                            "    $url"
                    )
                }
            }
        }
        failures.forEach { println("FAIL $it") }
        assertTrue("这几条没通：\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `搜索这条路真的通`() {
        assumeTrue("没给 -DliveApi=1，跳过", live)
        val r = WebSearch.run("上海天气", readPages = 1)
        assertEquals("搜索报错：${r.error}", null, r.error)
        assertTrue("一条结果都没有", r.hits > 0)
        val t = r.text ?: ""
        assertTrue("结果里没有链接", t.contains("http"))
        println("搜索 ok：${r.hits} 条，正文 ${r.pages} 篇，${t.length} 字")
        println(t.take(300))
    }

    private fun fetch(url: String, timeoutMs: Int = 20_000): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        // ★ 必须带 UA：GitHub 没有 User-Agent 直接 403；Wikimedia 也一样。
        c.setRequestProperty("User-Agent", "ConMarn/LiveCheck (offline test)")
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                println("HTTP $code $url → ${text.take(120)}")
                null
            } else text
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        println("请求抛了 ${e.javaClass.simpleName}: ${e.message} $url")
        null
    }
}
