package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 [ApiDraftMath]。
 *
 * **这一层错了不崩** —— 它错的表现是「新建了一个 API,面板上看着正常,
 * 她一调就报参数不对」或者更糟:「参数少了两个,于是查到了别的东西」。
 * 两种都不报错,所以只能在这里钉死。
 */
class ApiDraftMathTest {

    // ---- OpenAPI 3 ----

    private val v3 = """
    {
      "openapi": "3.0.0",
      "info": {"title": "示例天气", "description": "查天气的"},
      "servers": [{"url": "https://api.example.com/v1"}],
      "paths": {
        "/weather": {
          "get": {
            "operationId": "getWeather",
            "summary": "查实时天气",
            "parameters": [
              {"name": "city", "in": "query", "required": true, "description": "城市名"},
              {"name": "lang", "in": "query", "required": false, "description": "语言"}
            ]
          }
        },
        "/alerts/{region}": {
          "get": {
            "summary": "查预警",
            "parameters": [
              {"name": "region", "in": "path", "required": true, "description": "地区代码"}
            ]
          }
        }
      }
    }
    """.trimIndent()

    @Test
    fun `openapi3 认得地址和两个接口`() {
        val d = ApiDraftMath.parse(v3, "示例天气")
        assertNotNull(d)
        d!!
        assertEquals("https://api.example.com/v1", d.purpose.profiles.first().baseUrl)
        assertEquals(2, d.tools.size)
        assertEquals("示例天气", d.purpose.label)
    }

    @Test
    fun `openapi3 的 query 参数进白名单 path 参数不进`() {
        val d = ApiDraftMath.parse(v3, "x")!!
        val w = d.tools.first { it.name == "getweather" }
        assertEquals(listOf("city", "lang"), w.exec.query)
        assertEquals(listOf("city"), w.required)

        // path 里的 {region} 不进 query 白名单 —— 它靠路径模板替换,
        // 进白名单等于同一个值发两遍。
        val a = d.tools.first { it.name.contains("alert") }
        assertTrue(a.exec.query.isEmpty())
        assertEquals(listOf("region"), a.required)
    }

    @Test
    fun `openapi3 没有 operationId 时用方法加路径造名字`() {
        val d = ApiDraftMath.parse(v3, "x")!!
        val a = d.tools.first { it.name.contains("alert") }
        assertTrue(a.name.startsWith("get_"))
        assertFalse(a.name.contains("{"))
        assertFalse(a.name.contains("/"))
    }

    // ---- Swagger 2 ----

    private val v2 = """
    {
      "swagger": "2.0",
      "info": {"title": "老接口"},
      "host": "api.old.com",
      "basePath": "/api",
      "schemes": ["https"],
      "paths": {
        "/user": {
          "post": {
            "operationId": "createUser",
            "parameters": [
              {"name": "body", "in": "body", "schema": {
                "type": "object",
                "required": ["name"],
                "properties": {
                  "name": {"type": "string", "description": "名字"},
                  "age": {"type": "integer"}
                }
              }}
            ]
          }
        }
      }
    }
    """.trimIndent()

    @Test
    fun `swagger2 用 host 加 basePath 拼地址`() {
        val d = ApiDraftMath.parse(v2, "老接口")!!
        assertEquals("https://api.old.com/api", d.purpose.profiles.first().baseUrl)
        val t = d.tools.first()
        assertEquals("POST", t.exec.method)
        assertEquals(listOf("name", "age"), t.exec.bodyParams)
        assertEquals(listOf("name"), t.required)
    }

    // ---- curl ----

    @Test
    fun `curl 的 query 参数进白名单 值丢掉`() {
        val d = ApiDraftMath.parse(
            """curl 'https://api.x.com/v1/weather?city=Shanghai&token=SHOULD_NOT_APPEAR' -H 'Accept: application/json'""",
            "天气",
        )!!
        val t = d.tools.first()
        assertEquals("https://api.x.com", d.purpose.profiles.first().baseUrl)
        assertEquals("/v1/weather", t.exec.path)
        assertTrue(t.exec.query.contains("city"))
        // ★ 鉴权参数不收,而且它的**值**一个字都不许留在草稿里。
        assertFalse(t.exec.query.contains("token"))
        val all = t.toString() + d.toString()
        assertFalse(all.contains("SHOULD_NOT_APPEAR"))
    }

    @Test
    fun `curl 的鉴权头变成 key 占位`() {
        val d = ApiDraftMath.parse(
            """curl 'https://api.x.com/v1/w' -H 'Authorization: Bearer sk-REAL-SECRET'""",
            "x",
        )!!
        val t = d.tools.first()
        assertEquals("Bearer {key}", t.exec.headers["Authorization"])
        assertFalse(t.toString().contains("sk-REAL-SECRET"))
        assertTrue(d.notes.any { it.contains("鉴权头") })
    }

    @Test
    fun `curl 的 POST body 变成参数`() {
        val d = ApiDraftMath.parse(
            """curl -X POST 'https://api.x.com/v1/q' -H 'Content-Type: application/json' -d '{"q":"你好","n":3}'""",
            "x",
        )!!
        val t = d.tools.first()
        assertEquals("POST", t.exec.method)
        assertEquals(listOf("q", "n"), t.exec.bodyParams)
        assertEquals(setOf("q", "n"), t.required.toSet())
    }

    // ---- 认不出 ----

    @Test
    fun `不认识的文本返回 null 而不是猜`() {
        assertNull(ApiDraftMath.parse("帮我接一个天气 API", "天气"))
        assertNull(ApiDraftMath.parse("", "x"))
        assertNull(ApiDraftMath.parse("""{"foo":"bar"}""", "x"))
    }

    // ---- problem ----

    @Test
    fun `problem 会拦住没有地址和没有接口的草稿`() {
        val t = ApiToolDecl("p", "a", "d", exec = ApiExec("GET", "/a"))
        assertNotNull(ApiDraftMath.problem(ApiDraft(ApiPurpose("p", "名", "", emptyList()), listOf(t))))
        assertNotNull(ApiDraftMath.problem(ApiDraft(ApiPurpose("p", "", "", listOf(ApiProfile("x", "x", "https://a.com"))), listOf(t))))
        assertNotNull(ApiDraftMath.problem(ApiDraft(ApiPurpose("p", "名", "", listOf(ApiProfile("x", "x", "https://a.com"))), emptyList())))
    }

    @Test
    fun `problem 会拦住重名`() {
        val a = ApiToolDecl("p", "same", "d", exec = ApiExec("GET", "/a"))
        val b = a.copy(exec = ApiExec("GET", "/b"))
        val d = ApiDraft(ApiPurpose("p", "名", "", listOf(ApiProfile("x", "x", "https://a.com"))), listOf(a, b))
        assertTrue(ApiDraftMath.problem(d)!!.contains("same"))
    }

    @Test
    fun `一份合法的草稿没有问题`() {
        val d = ApiDraftMath.parse(v3, "示例天气")!!
        assertNull(ApiDraftMath.problem(d))
    }

    // ---- slug ----

    @Test
    fun `slug 把中文原样留着 标点变下划线`() {
        assertEquals("天气", ApiDraftMath.slug("天气"))
        assertEquals("get_weather", ApiDraftMath.slug("Get Weather"))
        assertEquals("", ApiDraftMath.slug("///"))
    }
}
