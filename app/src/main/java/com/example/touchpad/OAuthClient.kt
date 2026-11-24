package com.example.touchpad

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * OAuth 的**流程**：发出去、等回来、换 token、续期。
 *
 * ## 一条流程从头到尾
 *
 * ```
 * 面板「开始授权」
 *   → [begin]      生成 state + verifier，把地址交出去（外面去开浏览器）
 *   → 用户点同意
 *   → 系统打开 conmarn://oauth?code=…&state=…
 *   → [OAuthReturnActivity] 收到 → [finish]
 *   → POST 换 token → 加密存进那一档 → 回到面板
 * ```
 *
 * ## ★ 两处容易写成「看起来成功」的地方
 *
 * 1. **换 token 可能失败，而浏览器已经关了。** 那一下用户看到的是「同意完了，
 *    好像什么都没发生」。所以 [finish] 的每一句都要说清楚是成是败、败在哪。
 * 2. **续期失败不能静默。** 过期之后调接口会拿到 401，而 401 看起来和
 *    「key 填错了」一模一样 —— 面板上必须能说出「授权过期了，重新点一次授权」。
 */
object OAuthClient {

    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 20_000

    private var appContext: Context? = null
    private val lock = Any()

    /** 授权回来之后，面板该跳回哪一条用途。空 = 不用跳。 */
    @Volatile
    var reopenPurposeId: String? = null
        private set

    fun takeReopen(): String? = synchronized(lock) {
        val v = reopenPurposeId
        reopenPurposeId = null
        v
    }

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    private fun pendingFile(): File? {
        val ctx = appContext ?: return null
        return File(File(ctx.filesDir, "oauth").apply { mkdirs() }, "pending.json")
    }

    private fun trace(msg: String) {
        try {
            appContext?.let { ModelManager.get(it).trace("[oauth] $msg") }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // 发出去
    // ------------------------------------------------------------------

    /**
     * 准备一次授权。
     *
     * @return 让外部去打开的那条地址；或一句人话（为什么发不出去）。
     */
    fun begin(purposeId: String, profileId: String, cfg: OAuthConfig): String {
        if (cfg.clientId.isBlank()) return "还没填 client_id。去服务商后台建一个应用，把它复制进来。"
        if (cfg.authUrl.isBlank() || cfg.tokenUrl.isBlank()) return "授权地址或换 token 的地址还没填。"
        val redirect = cfg.redirect.ifBlank { OAuthProto.DEFAULT_REDIRECT }
        val state = OAuthProto.state()
        val verifier = OAuthProto.verifier()
        val url = OAuthProto.authorizeUrl(cfg, state, OAuthProto.challenge(verifier), redirect)
            ?: return "这条授权请求拼不出来：client_id 或授权地址是空的。"

        // ★ 先把「这一趟是哪一趟」写进盘再交出去。浏览器一开，我们这个进程随时可能
        //   被系统收掉（拍屏、切走几分钟）；不落盘的话，回来时 verifier 已经没了，
        //   那一趟授权就只能作废。
        val ok = writePending(
            OAuthPending(
                purposeId = purposeId,
                profileId = profileId,
                state = state,
                verifier = verifier,
                createdAtMs = System.currentTimeMillis(),
                redirect = redirect,
            )
        )
        if (!ok) return "写不进临时文件，这一次授权没法安全完成。"
        trace("发出授权：${cfg.provider.ifBlank { "自定义" }} / $purposeId")
        return url
    }

    // ------------------------------------------------------------------
    // 收回来
    // ------------------------------------------------------------------

    /**
     * 回调地址回来了。**阻塞**（要发一次网络请求），调用方别放主线程。
     *
     * @return 一句人话。成功时会顺带把 [reopenPurposeId] 设好。
     */
    fun finish(uri: String): String {
        val p = readPending()
            ?: return "这一趟授权已经失效了（或者不是从这台机器发出去的）。回面板重新点一次「开始授权」。"
        // 30 分钟还没回来就别再认了：多半是用户在浏览器里干别的事去了，
        // 这时候把一份陈旧的 code 拿去换 token，换回来的是别人的一次失败请求。
        if (System.currentTimeMillis() - p.createdAtMs > 30 * 60_000L) {
            clearPending()
            return "这趟授权超过半小时了，已经作废。回面板重新点一次「开始授权」。"
        }

        OAuthProto.errorOf(uri)?.let {
            clearPending()
            return "服务商拒绝了这次授权：$it"
        }
        val code = OAuthProto.codeOf(uri)
            ?: return "回调里没有授权码。这一趟没完成，回面板重新点一次「开始授权」。"
        // ★ state 对不上 = **不是我们发出去的那一趟**。整次作废，不换 token。
        val got = OAuthProto.params(uri)["state"]
        if (got != p.state) {
            clearPending()
            return "回传的编号和刚才发出去的对不上，已经按规矩作废。请回面板重新点一次「开始授权」。"
        }
        return exchange(p, code)
    }

    /** 粘贴回来的那一串。返回同一套人话。 */
    fun finishPasted(pasted: String): String {
        val p = readPending()
            ?: return "没有正在进行的授权。先点「开始授权」，拿到授权码再贴回来。"
        val code = OAuthProto.codeOf(pasted)
            ?: return "粘贴的内容里没有找到授权码。把浏览器地址栏那一整行复制过来就行。"
        // ★ 粘贴这条路 state 可能被用户裁掉了，所以**只在带了 state 的时候比**。
        //   带了对不上 = 还是作废；没带 = 认（这条路本身就需要用户在场）。
        val got = OAuthProto.params(pasted)["state"]
        if (got != null && got != p.state) {
            clearPending()
            return "粘贴进来的那一串和这次授权的编号对不上，已经作废。请重新点一次「开始授权」。"
        }
        return exchange(p, code)
    }

    private fun exchange(p: OAuthPending, code: String): String {
        val profile = ApiStore.purpose(p.purposeId)?.profiles?.firstOrNull { it.id == p.profileId }
            ?: run {
                clearPending()
                return "要授权的那一档不见了（可能刚被删掉）。"
            }
        val cfg = profile.oauth ?: run {
            clearPending()
            return "那一档的 OAuth 配置没了。回面板重新选一次服务商。"
        }

        val body = OAuthProto.codeBody(cfg, code, p.verifier, p.redirect)
        val (status, text) = try {
            postForm(cfg.tokenUrl, body, cfg.provider)
        } catch (e: Exception) {
            return "换 token 时连不上 ${hostOf(cfg.tokenUrl)}：" +
                ApiMath.redact("${e.javaClass.simpleName}: ${e.message}", ApiStore.knownKeys())
        }
        if (status !in 200..299) {
            return "服务商不肯换 token（HTTP $status）：${OAuthProto.errorText(text)}"
        }
        val token = OAuthProto.tokenOf(text, System.currentTimeMillis())
            ?: return "服务商回了 HTTP $status，但里面没有 access_token：${OAuthProto.errorText(text)}"

        save(p.purposeId, profile, cfg, token)
        clearPending()
        reopenPurposeId = p.purposeId
        trace("授权成功：${cfg.provider.ifBlank { "自定义" }} / ${profile.label}")
        return "已授权：${profile.label}。" +
            if (token.expiresAtMs > 0) "凭证到期后会自动续。" else ""
    }

    /** 把换回来的凭证写进那一档。 */
    private fun save(purposeId: String, profile: ApiProfile, cfg: OAuthConfig, token: OAuthToken) {
        val next = profile.copy(
            // ★ access_token 走 apiKey 这条现成的加密通道。
            apiKey = token.access,
            keyUnreadable = false,
            oauth = cfg.copy(
                // ★ 服务商这次没给新的 refresh_token 时**保留旧的** ——
                //   Google 只有第一次给，覆盖成空就等于把续期的路断了。
                refreshToken = token.refresh.ifBlank { cfg.refreshToken },
                tokenType = token.tokenType,
                expiresAtMs = token.expiresAtMs,
                scopeGranted = token.scope,
            ),
        )
        ApiStore.upsertProfile(purposeId, next)
    }

    // ------------------------------------------------------------------
    // 续期
    // ------------------------------------------------------------------

    /**
     * 用之前先看一眼要不要续。
     *
     * → 返回**当前可用的那一档**（续过就是新的那份）。
     *   续失败时原样返回 —— 调用方那一发会拿到 401，而那时 [statusLine] 会说出原因。
     *   **不在这里抛**：抛出去会让「授权过期」看起来像「连接坏了」。
     */
    fun ensureFresh(purposeId: String, profile: ApiProfile): ApiProfile {
        val cfg = profile.oauth ?: return profile
        if (!cfg.expiredAt(System.currentTimeMillis())) return profile
        if (cfg.refreshToken.isBlank()) return profile
        val (status, text) = try {
            postForm(cfg.tokenUrl, OAuthProto.refreshBody(cfg, cfg.refreshToken), cfg.provider)
        } catch (e: Exception) {
            trace("续期连不上：${e.javaClass.simpleName}")
            return profile
        }
        if (status !in 200..299) {
            trace("续期失败 HTTP $status：${OAuthProto.errorText(text)}")
            return profile
        }
        val token = OAuthProto.tokenOf(text, System.currentTimeMillis()) ?: return profile
        save(purposeId, profile, cfg, token)
        trace("续期成功：${profile.label}")
        return ApiStore.purpose(purposeId)?.profiles?.firstOrNull { it.id == profile.id } ?: profile
    }

    // ------------------------------------------------------------------
    // 面板上那一行字
    // ------------------------------------------------------------------

    /**
     * 面板上一档的状态。**每一种都必须说得出来** —— 尤其是「过期了」，
     * 因为它和「key 填错了」的表现完全一样（都是 401）。
     */
    fun statusLine(p: ApiProfile): String {
        val cfg = p.oauth ?: return "key ${ApiMath.maskKey(p.apiKey)}"
        if (p.apiKey.isBlank()) {
            return if (p.keyUnreadable) "⚠ 凭证解不开，要重新授权" else "尚未授权"
        }
        if (cfg.expiresAtMs <= 0) return "已授权（长期有效）"
        val left = cfg.expiresAtMs - System.currentTimeMillis()
        if (left <= 0) return if (cfg.refreshToken.isNotBlank()) "已过期，下次调用时自动续" else "已过期，要重新授权"
        return "已授权，还剩 " + leftText(left)
    }

    /** 「还剩 …」那一截。天/小时/分钟，粗到够用就行 —— 这一格不承担精确对账。 */
    private fun leftText(ms: Long): String = when {
        ms >= 86_400_000L -> "${ms / 86_400_000L} 天"
        ms >= 3_600_000L -> "${ms / 3_600_000L} 小时"
        else -> "${(ms / 60_000L).coerceAtLeast(1)} 分钟"
    }

    // ------------------------------------------------------------------

    private fun postForm(url: String, body: String, provider: String): Pair<Int, String> {
        val conn = CloudNet.conn(url).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            // ★ GitHub 默认回 `access_token=…&scope=…` 这种表单，回 JSON 要明说。
            //   不说的话我们也能解析（[OAuthProto.tokenOf] 两种都认），
            //   但 JSON 里带着 `token_type`、`scope`，好过自己猜。
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return status to text
        } finally {
            conn.disconnect()
        }
    }

    private fun hostOf(url: String): String =
        runCatching { URL(url).host }.getOrDefault(url.take(40))

    // ---- 临时状态落盘 ----

    private fun writePending(p: OAuthPending): Boolean = synchronized(lock) {
        val f = pendingFile() ?: return false
        runCatching {
            val o = JSONObject()
                .put("purposeId", p.purposeId)
                .put("profileId", p.profileId)
                .put("state", p.state)
                .put("verifier", p.verifier)
                .put("createdAtMs", p.createdAtMs)
                .put("redirect", p.redirect)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
            true
        }.getOrDefault(false)
    }

    private fun readPending(): OAuthPending? = synchronized(lock) {
        val f = pendingFile() ?: return null
        if (!f.exists()) return null
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return null
        val purposeId = o.optString("purposeId")
        val verifier = o.optString("verifier")
        if (purposeId.isEmpty() || verifier.isEmpty()) return null
        OAuthPending(
            purposeId = purposeId,
            profileId = o.optString("profileId"),
            state = o.optString("state"),
            verifier = verifier,
            createdAtMs = o.optLong("createdAtMs", 0L),
            redirect = o.optString("redirect").ifBlank { OAuthProto.DEFAULT_REDIRECT },
        )
    }

    private fun clearPending() {
        synchronized(lock) { runCatching { pendingFile()?.delete() } }
    }
}
