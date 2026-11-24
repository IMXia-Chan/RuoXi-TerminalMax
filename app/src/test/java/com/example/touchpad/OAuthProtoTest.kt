package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OAuth 的纯逻辑。
 *
 * ★ 钉的是「不崩、但授权一定成不了」的那几种：PKCE 算错（服务商直接拒）、
 *   回调参数读不到（表现为「同意完了没反应」）、token 解析认错一种格式
 *   （表现为「授权成功」但存的是一串空）。
 */
class OAuthProtoTest {

    private fun cfg(
        clientId: String = "abc",
        secret: String = "",
        scopes: String = "read repo",
        extra: Map<String, String> = emptyMap(),
    ) = OAuthConfig(
        provider = "test",
        authUrl = "https://example.com/authorize",
        tokenUrl = "https://example.com/token",
        clientId = clientId,
        clientSecret = secret,
        scopes = scopes,
        extra = extra,
    )

    /**
     * ★ **已知答案**：这一对是用 Python 的 `hashlib.sha256` + `urlsafe_b64encode`
     *   独立算出来的，不是拿我们自己的实现算完再抄过来。
     *   为什么非要有这一条：PKCE 算错了不会崩，服务商只在换 token 那一步回
     *   `invalid_grant` —— 而那时用户已经点完同意了，界面上什么线索都没有。
     */
    @Test
    fun `PKCE 的 hash 和独立实现算出来的一致`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjvBw"
        assertEquals("7yaj4uSb0zESC9AxoUkTJzWwklZzEMry6UsrjvwpLi4", OAuthProto.challenge(verifier))
        // 形状：SHA-256 是 32 字节 → base64url 43 个字符、没有补位 `=`。
        val c = OAuthProto.challenge(OAuthProto.verifier())
        assertEquals(43, c.length)
        assertFalse("url-safe 的字母表里不该有 + 和 /", c.contains('+') || c.contains('/'))
        assertFalse(c.contains('='))
    }

    @Test
    fun `verifier 只用不收尾的字符，长度在 43 到 128 之间`() {
        repeat(20) {
            val v = OAuthProto.verifier()
            assertTrue("长度 ${v.length}", v.length in 43..128)
            assertTrue("出现了非法字符：$v", v.all { c ->
                c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '.' || c == '_' || c == '~'
            })
        }
    }

    @Test
    fun `授权地址带齐协议字段，redirect 被编码`() {
        val url = OAuthProto.authorizeUrl(cfg(), "st8", "ch4", "conmarn://oauth")!!
        assertTrue(url.startsWith("https://example.com/authorize?"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=abc"))
        assertTrue(url.contains("redirect_uri=conmarn%3A%2F%2Foauth"))
        assertTrue(url.contains("scope=read%20repo"))
        assertTrue(url.contains("state=st8"))
        assertTrue(url.contains("code_challenge=ch4"))
        assertTrue(url.contains("code_challenge_method=S256"))
    }

    @Test
    fun `服务商的额外参数不会盖掉协议字段`() {
        val url = OAuthProto.authorizeUrl(
            cfg(extra = linkedMapOf("access_type" to "offline", "state" to "被覆盖")),
            "真state", "ch", "conmarn://oauth",
        )!!
        assertTrue(url.contains("access_type=offline"))
        assertTrue(url.contains("state=%E7%9C%9Fstate"))
        assertFalse("额外参数不许覆盖 state", url.contains("被覆盖"))
    }

    @Test
    fun `没有 client_id 就不拼地址`() {
        assertNull(OAuthProto.authorizeUrl(cfg(clientId = ""), "s", "c", "conmarn://oauth"))
    }

    @Test
    fun `换 token 的正文带 verifier，secret 该有才有`() {
        val pub = OAuthProto.codeBody(cfg(), "CODE", "VER", "conmarn://oauth")
        assertTrue(pub.contains("grant_type=authorization_code"))
        assertTrue(pub.contains("code=CODE"))
        assertTrue(pub.contains("code_verifier=VER"))
        assertFalse("公共客户端不该凭空多一个 secret", pub.contains("client_secret"))

        val conf = OAuthProto.codeBody(cfg(secret = "s3"), "CODE", "VER", "conmarn://oauth")
        assertTrue(conf.contains("client_secret=s3"))
    }

    @Test
    fun `续期的正文用的是 refresh_token`() {
        val b = OAuthProto.refreshBody(cfg(secret = "s3"), "R1")
        assertTrue(b.contains("grant_type=refresh_token"))
        assertTrue(b.contains("refresh_token=R1"))
        assertFalse(b.contains("code_verifier"))
    }

    @Test
    fun `空格编成 %20，不是加号`() {
        assertEquals("a%20b", OAuthProto.urlEncode("a b"))
        assertEquals("c%3A%2F%2Fx", OAuthProto.urlEncode("c://x"))
    }

    @Test
    fun `JSON 形式的 token 能读出来`() {
        val t = OAuthProto.tokenOf(
            """{"access_token":"AT","refresh_token":"RT","token_type":"bearer","expires_in":3600,"scope":"a b"}""",
            1_000L,
        )!!
        assertEquals("AT", t.access)
        assertEquals("RT", t.refresh)
        assertEquals(3_601_000L, t.expiresAtMs)
        assertEquals("a b", t.scope)
    }

    @Test
    fun `表单形式的 token 也能读出来（GitHub 默认就是这种）`() {
        val t = OAuthProto.tokenOf("access_token=AT&scope=repo&token_type=bearer", 5L)!!
        assertEquals("AT", t.access)
        assertEquals("repo", t.scope)
        // 没给 expires_in = 不过期。**不能拿一个假的过期时间顶上**，
        // 那会让一条永不失效的令牌在每个小时都被"续"一次。
        assertEquals(0L, t.expiresAtMs)
    }

    @Test
    fun `没有 access_token 的响应不算令牌`() {
        assertNull(OAuthProto.tokenOf("""{"error":"bad_verification_code"}""", 0L))
        assertNull(OAuthProto.tokenOf("", 0L))
    }

    @Test
    fun `回调参数从问号和井号后面都读得到`() {
        assertEquals(
            "C1",
            OAuthProto.params("conmarn://oauth?code=C1&state=S").getValue("code"),
        )
        assertEquals(
            "C2",
            OAuthProto.params("conmarn://oauth#code=C2&state=S").getValue("code"),
        )
    }

    @Test
    fun `粘贴的可能是整条地址，也可能只有 code`() {
        assertEquals("C1", OAuthProto.codeOf("conmarn://oauth?code=C1&state=S"))
        assertEquals("C9", OAuthProto.codeOf("  C9  "))
        assertNull(OAuthProto.codeOf(""))
        assertNull(OAuthProto.codeOf("conmarn://oauth?error=access_denied"))
    }

    @Test
    fun `被拒绝时要把服务商的原话说出来`() {
        val e = OAuthProto.errorOf("conmarn://oauth?error=access_denied&error_description=用户点了取消")
        assertTrue(e!!.contains("access_denied"))
        assertTrue(e.contains("用户点了取消"))
        assertNull(OAuthProto.errorOf("conmarn://oauth?code=X"))

        assertTrue(OAuthProto.errorText("""{"error":"invalid_grant","error_description":"code 过期"}""")
            .contains("code 过期"))
        assertTrue(OAuthProto.errorText("error=invalid_client").contains("invalid_client"))
    }

    @Test
    fun `过期判据提前一分钟`() {
        val c = OAuthConfig(authUrl = "a", tokenUrl = "t", clientId = "i", expiresAtMs = 100_000L)
        assertFalse(c.expiredAt(0L))
        // 只差 59 秒就到期 —— 按过期算，让续期在请求发出去之前完成。
        assertTrue(c.expiredAt(100_000L - OAuthProto.SKEW_MS + 1))
        // 没说什么时候过期的（0）永远不过期。
        assertFalse(c.copy(expiresAtMs = 0L).expiredAt(Long.MAX_VALUE / 2))
    }

    @Test
    fun `预置的服务商资料是齐的`() {
        assertTrue(OAuthPresets.all.size >= 2)
        OAuthPresets.all.forEach { p ->
            assertTrue("${p.id} 没有授权地址", p.authUrl.startsWith("https://"))
            assertTrue("${p.id} 没有换 token 地址", p.tokenUrl.startsWith("https://"))
            assertTrue("${p.id} 没有权限范围", p.scopes.isNotBlank())
            assertTrue("${p.id} 没写去哪建应用", p.hint.isNotBlank())
            val c = OAuthPresets.toConfig(p)
            assertEquals(p.provider(), c.provider)
            assertEquals(p.authUrl, c.authUrl)
        }
        assertTrue("Google 不带 offline 就只在第一次给 refresh token",
            OAuthPresets.find("google")!!.extra["access_type"] == "offline")
    }

    private fun OAuthPreset.provider() = id
}
