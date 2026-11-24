package com.example.touchpad

import org.json.JSONObject
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * **OAuth 授权** —— 用别家的账号（GitHub、Google…）换一串凭证，再拿它调接口。
 *
 * 他 2026-11-06 在「API 宿主系统」那四项里全选了，这一块是第三项。
 *
 * ## 它和「填一个 key」的区别
 *
 * | | 填 key | OAuth |
 * |---|---|---|
 * | 谁发的 | 用户在服务商后台**自己复制** | 用户在网页上**点同意**，服务商现发 |
 * | 活多久 | 一般不过期 | 有有效期，靠 refresh token 续 |
 * | 范围 | 一串字符串，什么都意味着 | **带权限范围**（只给日历读，不给写） |
 *
 * ## ★ 三道保护，都在 [OAuthProto] 里
 *
 * 1. **PKCE**（RFC 7636）：授权请求带 `code_challenge`，换 token 时带 `code_verifier`。
 *    这样即使有人在街上截到了那串授权码，没有 verifier 也换不成 token。
 *    公共客户端（手机 App 拿不到 client_secret）**必须**用它。
 * 2. **state**：发出去时随机生成、回传时必须一模一样。对不上就整次作废 ——
 *    这是防「别人把一份授权码塞给你」的那道闸。
 * 3. **redirect 写死**：只认 `conmarn://oauth`。服务商那边注册的回调地址必须和它一致。
 *
 * ## ★ 令牌存在哪
 *
 * `access_token` 存进 [ApiProfile.apiKey] —— **和用户手填的 key 走同一条加密通道**
 * （Keystore，见 `TouchpadClient.SecretCipher`）。于是「不落明文」「日志里要抹掉」
 * 「请求头 / query 注入」这些现成的规矩,一个字都不用重写。
 * `refresh_token` 和 `client_secret` 是同一级的凭据,**同样加密落盘**。
 */

/** 一档 OAuth 的配置。挂在 [ApiProfile.oauth] 上，跟着它一起落盘。 */
data class OAuthConfig(
    /** `github` / `google` / 空 = 自定义。只用来在面板上显示和预填。 */
    val provider: String = "",
    /** 授权页地址：用户在浏览器里点「同意」的那一页。 */
    val authUrl: String,
    /** 换 token 的地址。后端对后端，用户看不到。 */
    val tokenUrl: String,
    val clientId: String,
    /** 有的服务需要（GitHub），有的不需要（Google 的移动端）。 */
    val clientSecret: String = "",
    /** 空格分隔的权限范围。空 = 用服务商给的默认范围。 */
    val scopes: String = "",
    /** 回调地址。**别改** —— 服务商那边注册的就是这一个。 */
    val redirect: String = OAuthProto.DEFAULT_REDIRECT,
    /** 服务商要求额外带的参数（Google 的 `access_type=offline`、`prompt=consent`）。 */
    val extra: Map<String, String> = emptyMap(),
    /** 续期用。没给 = 到期就得重新点授权。 */
    val refreshToken: String = "",
    val tokenType: String = "Bearer",
    /** 0 = 服务商没说什么时候过期（GitHub 就是这种）。 */
    val expiresAtMs: Long = 0,
    val scopeGranted: String = "",
) {
    /** 到现在这一刻，这串 access_token 还能不能用。 */
    fun expiredAt(nowMs: Long): Boolean =
        expiresAtMs > 0 && nowMs >= expiresAtMs - OAuthProto.SKEW_MS
}

/** 换回来的一串凭证。 */
data class OAuthToken(
    val access: String,
    val refresh: String,
    val tokenType: String,
    val expiresAtMs: Long,
    val scope: String,
)

/** 授权发出去之后、还没回来之前，需要记住的那几样。 */
data class OAuthPending(
    val purposeId: String,
    val profileId: String,
    val state: String,
    val verifier: String,
    val createdAtMs: Long,
    /** 自己点「粘贴授权码」那条路要用的 —— 服务商回的是整条地址还是只有 code。 */
    val redirect: String,
)

/**
 * 服务商预置。选了它，面板上只剩 client_id（和 GitHub 的 client_secret）要填。
 *
 * ★ 地址和权限范围是**公开资料**，写死在这里不涉及任何人的凭据。
 *   凭据（client_id / secret / token）永远只在用户自己的机器上。
 */
data class OAuthPreset(
    val id: String,
    val name: String,
    val vendor: String,
    val authUrl: String,
    val tokenUrl: String,
    val scopes: String,
    val extra: Map<String, String> = emptyMap(),
    /** 这家要不要 client_secret。GitHub 的 web 应用流程要，Google 移动端不要。 */
    val needsSecret: Boolean = false,
    /** 一句人话：去哪建应用、拿 client_id。 */
    val hint: String,
)

/**
 * 纯逻辑：拼地址、算 PKCE、解析回执。
 *
 * ★ 和别的 `*Math` 一样放在这里而不是塞进 [OAuthClient]：这些形状写错了
 *   **不会崩**，只会「授权页打不开」或「换 token 时被服务商拒掉」——
 *   而那一刻用户已经离开 App 了，界面上什么线索都没有。单测是唯一能拦的东西。
 */
object OAuthProto {

    const val DEFAULT_REDIRECT = "conmarn://oauth"

    /** 提前一分钟换新的。让「刚好在请求路上过期」这件事不会发生。 */
    const val SKEW_MS = 60_000L

    private val unreserved =
        ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~')

    /**
     * 一个 PKCE 的 code_verifier：43–128 个 unreserved 字符（RFC 7636 §4.1）。
     *
     * ★ 默认 48 而不是 32：32 个字符**低于 RFC 的下限 43**。有的服务商不校验长度，
     *   有的会直接回 `invalid_request`，而那一刻用户已经在浏览器里点过同意了。
     */
    fun verifier(bytes: Int = 48): String {
        val r = SecureRandom()
        val sb = StringBuilder()
        repeat(bytes) { sb.append(unreserved[r.nextInt(unreserved.size)]) }
        return sb.toString()
    }

    fun state(): String = verifier(16)

    /** `code_challenge = BASE64URL-ENCODE(SHA256(code_verifier))`，去掉补位的 `=`。 */
    fun challenge(verifier: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(d)
    }

    /**
     * 用户在浏览器里打开的那条地址。
     *
     * → null = **这条授权请求不能发**（少了 client_id / 授权地址）。
     *   和 [ApiMath.buildUrl] 一个尺度：宁可当场说清楚，也不要跳到一个缺参数的页面上
     *   —— 那种页面不是报错，而是服务商的首页，用户会以为「点了没反应」。
     */
    fun authorizeUrl(cfg: OAuthConfig, state: String, challenge: String, redirect: String): String? {
        if (cfg.authUrl.isBlank() || cfg.clientId.isBlank()) return null
        val p = LinkedHashMap<String, String>()
        p["response_type"] = "code"
        p["client_id"] = cfg.clientId
        p["redirect_uri"] = redirect
        if (cfg.scopes.isNotBlank()) p["scope"] = cfg.scopes
        p["state"] = state
        p["code_challenge"] = challenge
        p["code_challenge_method"] = "S256"
        // ★ 服务商自己的额外参数放最后：它们不该覆盖上面这几条协议字段。
        cfg.extra.forEach { (k, v) -> if (k !in p) p[k] = v }
        val q = p.entries.joinToString("&") { (k, v) -> urlEncode(k) + "=" + urlEncode(v) }
        val base = cfg.authUrl.trim()
        return base + (if (base.contains('?')) "&" else "?") + q
    }

    /** 换 token 的正文（`application/x-www-form-urlencoded`）。 */
    fun codeBody(cfg: OAuthConfig, code: String, verifier: String, redirect: String): String {
        val p = LinkedHashMap<String, String>()
        p["grant_type"] = "authorization_code"
        p["code"] = code
        p["redirect_uri"] = redirect
        p["client_id"] = cfg.clientId
        p["code_verifier"] = verifier
        if (cfg.clientSecret.isNotBlank()) p["client_secret"] = cfg.clientSecret
        return form(p)
    }

    /** 续期的正文。 */
    fun refreshBody(cfg: OAuthConfig, refreshToken: String): String {
        val p = LinkedHashMap<String, String>()
        p["grant_type"] = "refresh_token"
        p["refresh_token"] = refreshToken
        p["client_id"] = cfg.clientId
        if (cfg.clientSecret.isNotBlank()) p["client_secret"] = cfg.clientSecret
        return form(p)
    }

    /**
     * 回调地址里带回来的是什么。
     *
     * ★ 服务商可能把参数放在 `?` 后面，也可能放在 `#` 后面（OAuth 2.1 的隐式流程），
     *   两处都要看。只看 `?` 的话，Google 的某些返回会**什么都读不到**，
     *   而表现是「授权完了但没反应」。
     */
    fun params(uri: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val q = uri.substringAfter('?', "").substringBefore('#')
        val f = uri.substringAfter('#', "")
        for (part in listOf(q, f)) {
            if (part.isBlank()) continue
            for (kv in part.split('&')) {
                if (kv.isBlank()) continue
                val at = kv.indexOf('=')
                val k = if (at < 0) kv else kv.substring(0, at)
                val v = if (at < 0) "" else kv.substring(at + 1)
                if (k.isNotBlank()) out[decode(k)] = decode(v)
            }
        }
        return out
    }

    /**
     * 他手动粘贴回来的那一串，可能是整条回调地址，也可能只有 code 本身。
     *
     * → null = 里面既没有 code 也没有 error（多半是粘错了 / 复制到了别的页面）。
     */
    fun codeOf(pasted: String): String? {
        val s = pasted.trim()
        if (s.isEmpty()) return null
        if (!s.contains('=') && !s.contains("://")) return s
        return params(s)["code"]
    }

    fun errorOf(uri: String): String? {
        val p = params(uri)
        val e = p["error"] ?: return null
        val d = p["error_description"]
        return if (d.isNullOrBlank()) e else "$e：$d"
    }

    /**
     * 换 token 的响应 → [OAuthToken]。
     *
     * ★ 服务商可能回 JSON，也可能回 `access_token=…&scope=…`（GitHub 默认就是后者，
     *   除非请求头写 `Accept: application/json`）。两种都要认 —— 只认一种的话，
     *   另一种会表现为「授权成功但 token 是空的」，而它会被存成「已授权」。
     */
    fun tokenOf(body: String, nowMs: Long): OAuthToken? {
        val s = body.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("{")) {
            val o = runCatching { JSONObject(s) }.getOrNull() ?: return null
            val access = o.optString("access_token").trim()
            if (access.isEmpty()) return null
            val secs = o.optLong("expires_in", 0L)
            return OAuthToken(
                access = access,
                refresh = o.optString("refresh_token").trim(),
                tokenType = o.optString("token_type").ifBlank { "Bearer" },
                expiresAtMs = if (secs > 0) nowMs + secs * 1000L else 0L,
                scope = o.optString("scope"),
            )
        }
        val m = params("?" + s)
        val access = m["access_token"].orEmpty().trim()
        if (access.isEmpty()) return null
        val secs = m["expires_in"]?.toLongOrNull() ?: 0L
        return OAuthToken(
            access = access,
            refresh = m["refresh_token"].orEmpty().trim(),
            tokenType = m["token_type"].orEmpty().ifBlank { "Bearer" },
            expiresAtMs = if (secs > 0) nowMs + secs * 1000L else 0L,
            scope = m["scope"].orEmpty(),
        )
    }

    /** 失败回执里的人话。**不许只说「失败」** —— 用户没法据此改任何东西。 */
    fun errorText(body: String): String {
        val s = body.trim()
        if (s.isEmpty()) return "对方没有返回任何内容。"
        val o = runCatching { JSONObject(s) }.getOrNull()
        if (o != null) {
            val e = o.optString("error")
            val d = o.optString("error_description")
            if (e.isNotEmpty() || d.isNotEmpty()) return listOf(e, d).filter { it.isNotEmpty() }.joinToString("：")
        }
        val m = params("?" + s)
        val e = m["error"].orEmpty()
        val d = m["error_description"].orEmpty()
        if (e.isNotEmpty() || d.isNotEmpty()) return listOf(e, d).filter { it.isNotEmpty() }.joinToString("：")
        return if (s.length > 200) s.take(200) + "…" else s
    }

    fun form(p: Map<String, String>): String =
        p.entries.joinToString("&") { (k, v) -> urlEncode(k) + "=" + urlEncode(v) }

    /**
     * 和 [ApiMath] 里那个一样：把空格编成 `%20`，不是 `+`。
     *
     * ★ 不能省这一步去用 `URLEncoder` —— 它把空格编成 `+`，而 `+` 在
     *   `redirect_uri=conmarn://oauth` 里没事，在 scope 里会被服务商当成
     *   字面的加号解析，范围就对不上了。
     */
    fun urlEncode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c.toChar() in unreserved) sb.append(c.toChar())
            else sb.append('%').append("%02X".format(c))
        }
        return sb.toString()
    }

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}

/** 预置的服务商。**只放公开资料，不放任何人的凭据。** */
internal object OAuthPresets {

    val all: List<OAuthPreset> = listOf(
        OAuthPreset(
            id = "github",
            name = "GitHub",
            vendor = "GitHub",
            authUrl = "https://github.com/login/oauth/authorize",
            tokenUrl = "https://github.com/login/oauth/access_token",
            scopes = "read:user repo",
            needsSecret = true,
            hint = "在 GitHub → Settings → Developer settings → OAuth Apps 新建一个应用，" +
                "回调地址填 conmarn://oauth。建完拿到 Client ID 和 Client secret。",
        ),
        OAuthPreset(
            id = "google",
            name = "Google",
            vendor = "Google",
            authUrl = "https://accounts.google.com/o/oauth2/v2/auth",
            tokenUrl = "https://oauth2.googleapis.com/token",
            scopes = "https://www.googleapis.com/auth/calendar.readonly " +
                "https://www.googleapis.com/auth/drive.readonly",
            // ★ 不带这两个，Google 只在**第一次**授权时给 refresh token，
            //   之后再授权就只给 access token —— 表现为「第二次授权之后，一天就过期」。
            extra = linkedMapOf("access_type" to "offline", "prompt" to "consent"),
            needsSecret = false,
            hint = "在 Google Cloud Console 建一个 OAuth 客户端（类型选「桌面应用」或「Android」），" +
                "拿到 Client ID。桌面应用类型会同时给一个 secret，一并填进来也行。",
        ),
    )

    fun find(id: String): OAuthPreset? = all.firstOrNull { it.id == id }

    fun toConfig(p: OAuthPreset, clientId: String = "", clientSecret: String = ""): OAuthConfig =
        OAuthConfig(
            provider = p.id,
            authUrl = p.authUrl,
            tokenUrl = p.tokenUrl,
            clientId = clientId,
            clientSecret = clientSecret,
            scopes = p.scopes,
            extra = p.extra,
        )
}
