package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连接器目录的**形状**。
 *
 * ★ 这里钉的不是「接口通不通」（那要联网，单测不该碰），而是「**装上去之后能不能发出去**」。
 *   目录项是我们自己写死的声明，写错一个占位符的后果是：她调这个工具时
 *   [ApiMath.buildUrl] 按 fail-closed 返回 null ⇒ 永远不发 ⇒ 用户看到的是
 *   「地址里有没填上的参数」。**而目录是我们给的，他没写错任何东西。**
 *   这类错不崩、不报异常，只有在这里拦得住。
 */
class ConnectorCatalogTest {

    private val all = ConnectorCatalog.all

    @Test
    fun `id 不重名，地址都是 https`() {
        assertEquals(all.size, all.map { it.id }.toSet().size)
        all.forEach {
            assertTrue("${it.id} 的地址不是 https：${it.baseUrl}", it.baseUrl.startsWith("https://"))
        }
    }

    @Test
    fun `每一项至少有一个工具，且工具的用途编号对得上`() {
        all.forEach { c ->
            assertTrue("${c.id} 一个工具都没有", c.tools.isNotEmpty())
            val d = ConnectorCatalog.draft(c)
            assertEquals(c.id, d.purpose.id)
            assertEquals(c.tools.size, d.tools.size)
            d.tools.forEach { t ->
                assertEquals("工具 ${t.name} 绑错了用途", c.id, t.purpose)
            }
        }
    }

    @Test
    fun `必填参数都必须是声明过的参数`() {
        all.forEach { c ->
            c.tools.forEach { t ->
                val names = t.params.map { it.name }.toSet()
                t.required.forEach { r ->
                    assertTrue("${c.id}/${t.name} 的必填项 $r 不在参数表里", r in names)
                }
            }
        }
    }

    /**
     * ★ 路径和写死的 query 里，除了 `key`（由当前那一档注入）之外，
     *   每一个占位符都必须在参数表里 —— 否则这个工具**永远发不出去**。
     */
    @Test
    fun `占位符都能被参数或 key 填上`() {
        all.forEach { c ->
            c.tools.forEach { t ->
                val names = t.params.map { it.name }.toSet() + "key"
                val inPath = ApiMath.placeholders(t.exec.path)
                val inQuery = t.exec.fixedQuery.values
                    .flatMap { ApiMath.placeholders(it) }
                    .toSet()
                (inPath + inQuery).forEach { p ->
                    assertTrue("${c.id}/${t.name} 的占位符 {$p} 没人填", p in names)
                }
            }
        }
    }

    @Test
    fun `需要 key 的那几项，没填 key 时请求不发`() {
        val q = ConnectorCatalog.find("qweather")!!
        val t = q.tools.first { it.name == "weather_now" }
        val args = mapOf("location" to "115.89,28.68")

        assertNull(
            "没填 key 还发出去，就会拿到一份报错的正文当成天气报给他",
            ApiMath.buildUrl(q.baseUrl, t.exec.path, t.exec.fixedQuery, t.exec.query, args),
        )

        val url = ApiMath.buildUrl(
            q.baseUrl, t.exec.path, t.exec.fixedQuery, t.exec.query, args + ("key" to "abc123"),
        )
        assertNotNull(url)
        assertTrue(url!!.startsWith("https://devapi.qweather.com/v7/weather/now?"))
        assertTrue(url.contains("key=abc123"))
        assertTrue(url.contains("location=115.89%2C28.68"))
    }

    @Test
    fun `不需要 key 的那几项，参数齐了就能拼出地址`() {
        all.filterNot { it.needsKey }.forEach { c ->
            c.tools.forEach { t ->
                val args = t.params.associate { it.name to "x" }
                val url = ApiMath.buildUrl(c.baseUrl, t.exec.path, t.exec.fixedQuery, t.exec.query, args)
                assertNotNull("${c.id}/${t.name} 拼不出地址", url)
                assertTrue("${c.id}/${t.name} 拼出来是 $url", url!!.startsWith("https://"))
                assertTrue("${c.id}/${t.name} 还留着占位符：$url", !url.contains("{"))
            }
        }
    }

    @Test
    fun `声明里不许送图送声音`() {
        all.forEach { c ->
            val d = ConnectorCatalog.draft(c)
            d.tools.forEach { t ->
                assertTrue(
                    "${c.id}/${t.name} 的声明里夹了图/声音，这条到了真机上会被整条拦掉",
                    ApiMath.sendsOk(t.sends),
                )
            }
        }
    }
}
