package com.example.touchpad

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「这个地址算不算出手机」—— 纯函数，能跑 JVM 单测。
 *
 * ★ 分辨这件事是**总闸成立的前提**：总闸关掉以后，`127.0.0.1:8080` 上那个本地脑子
 *   必须照常能用，他家里那台 PC 上的服务也必须照常能用 —— 那不是云端。
 *   要是连本机一起掐了，关总闸 = 她变哑巴，而这个开关就没人敢关。
 *
 * ★ 判据只认**地址本身**，不认域名解析结果：解析要出网，而「判断要不要出网」
 *   这件事本身绝不能先出一次网（否则关着闸也在发 DNS）。
 */
internal object CloudNetMath {

    /**
     * 本机 / 局域网 / 链路本地。
     *
     * 覆盖：回环 `127/8`、私有网段 `10/8`、`172.16/12`、`192.168/16`、
     * 链路本地 `169.254/16`、IPv6 回环 `::1`、唯一本地 `fc00::/7`、链路本地 `fe80::/10`，
     * 以及不带点的单段主机名（`localhost`、`pixel` 这类局域网名字）。
     *
     * ★ 不带点的名字一律当本机/局域网：能连上「单段名」的只可能是 mDNS/局域网 DNS，
     *   公网域名一定带点。反过来把 `localhost` 判成外网，会让关闸之后连自己都连不上。
     */
    fun isLocalHost(host: String?): Boolean {
        val h = host?.trim()?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".localhost")) return true
        if (!h.contains('.')) {
            // 纯 IPv6（带冒号）落到下面那段处理；单段字母名当局域网。
            if (!h.contains(':')) return true
        }

        // ---- IPv6 ----
        if (h.contains(':')) {
            if (h == "::" || h == "::1" || h == "0:0:0:0:0:0:0:1") return true
            val first = h.substringBefore(':').ifEmpty { "0" }
            val v = first.toIntOrNull(16) ?: return false
            if (v and 0xFE00 == 0xFC00) return true   // fc00::/7 唯一本地
            if (v and 0xFFC0 == 0xFE80) return true   // fe80::/10 链路本地
            if (v and 0xFF00 == 0xFF00) return true   // ff00::/8 组播（不发到公网）
            return false
        }

        // ---- IPv4 ----
        val parts = h.split('.')
        if (parts.size != 4) return false
        val n = IntArray(4) { parts[it].toIntOrNull() ?: return false }
        if (n.any { it < 0 || it > 255 }) return false
        return when {
            n[0] == 127 -> true                       // 回环
            n[0] == 10 -> true                        // 私有
            n[0] == 192 && n[1] == 168 -> true        // 私有
            n[0] == 172 && n[1] in 16..31 -> true     // 私有
            n[0] == 169 && n[1] == 254 -> true        // 链路本地
            n[0] == 0 -> true                         // 0.0.0.0 这类「本机」
            else -> false
        }
    }

    /**
     * 这一次出网放不放行。
     *
     * @param open   总闸开着没有
     * @param host   目标主机（URL 里的 host，或 socket 要连的地址）
     */
    fun allow(open: Boolean, host: String?): Boolean = open || isLocalHost(host)
}

/**
 * **云端总闸** —— 全 App 唯一一个「这批字节能不能出门」的判定点。
 *
 * ## 为什么要有这个文件
 *
 * 原来那个「云端总闸」开关只挡了**她自己的模型调用**（`AiAgent.config.cloudOn`
 * 在十几处被读）。可天气、热点、语录、网页搜索、图片生成、MCP、OAuth、MQTT 走的
 * 是另外几条各自独立的 HTTP 路，**一个都没看那个开关**。于是关掉开关之后，
 * 屏幕上写着「已关闭，不再发出任何对外请求」，而天气和热点照常在往外发 ——
 * 一句话说两样，其中一样是假的。
 *
 * 现在把「发不发」这件事**收到一个地方**：所有出网都必须先问 [CloudNet]，
 * 问不过就不建连接（不是建了再断，是根本不发一个字节）。
 *
 * ## 闸门的语义（要看清楚，这是它的边界）
 *
 * - **关闸 = 不许连公网**。本机（`127.0.0.1` 上那个脑子）和局域网（他家 PC）
 *   **照常能用** —— 见 [CloudNetMath.isLocalHost]。理由同上：连本机一起掐，
 *   这个开关就变成「把她弄哑」，没人敢关。
 * - 闸门只认地址，不做 DNS 解析（解析本身就是一次出网）。
 * - 它**不是**「隐私模式」：闸开的时候该怎么发还怎么发，这里只管开与关。
 *
 * ## 关闸之后长什么样
 *
 * 每个调用点拿到的都是「抛异常 → 走它自己原有的失败分支」。这些分支本来就为
 * 「断网」写好了（天气显示「没取到」、热点报失败、搜索降级），所以关闸之后
 * 界面是**安静地少几块内容**，不会崩、不会卡。错误文案统一是
 * [CloudClosed] 那句话，用户一看就知道是总闸关着，不是网坏了。
 */
object CloudNet {

    private const val TAG = "CloudGate"

    /**
     * 进程级 context。
     *
     * ★ 为什么不是每个调用点都传：有几条路（网页搜索、图片生成、OAuth、MCP）
     *   的函数签名里根本没有 Context，而它们都在 `AiAgent` 深处被调。为了一个布尔
     *   把那几个签名全改一遍，改的是十条不相关的路。
     *   [ConMarnApp.onCreate] 在任何组件之前跑，所以这里一定有值。
     */
    @Volatile private var app: Context? = null

    /** 由 [ConMarnApp] 调用。重复调用无害。 */
    fun install(ctx: Context) {
        app = ctx.applicationContext
    }

    /** 闸开着没有。没装 context 时按「开」处理 —— 宁可放行，也不因为初始化顺序把人锁死。 */
    fun isOpen(ctx: Context? = null): Boolean {
        val c = ctx?.applicationContext ?: app ?: return true
        return try {
            AiAgent.cloudSwitch(c)
        } catch (e: Exception) {
            Log.w(TAG, "总闸读不出来，按开着处理", e)
            true
        }
    }

    /** 这个地址放不放行。 */
    fun allow(host: String?, ctx: Context? = null): Boolean =
        CloudNetMath.allow(isOpen(ctx), host)

    /**
     * 闸门挡下来的那一次。
     *
     * ★ 特意带上 host：日志里能看出「他想连哪儿」。没有这一行的话，
     *   关闸之后所有功能一起「没取到」，和真的断网、和站点改版，
     *   在日志里长得一模一样。
     */
    class CloudClosed(val host: String) : java.io.IOException(
        "云端总闸关着（设置 → 云端总闸），已拦下到 $host 的请求"
    )

    /**
     * 所有 HTTP 出网都从这里拿连接。
     *
     * @throws CloudClosed 闸关着且目标是公网。**在 `openConnection` 之前抛** ——
     *   `openConnection` 本身不连网，但它一返回就可能被别处顺手读掉；
     *   这里在拿到 URL 之后就判定，连一个对象都不往下给。
     */
    fun conn(url: String, ctx: Context? = null): HttpURLConnection {
        val u = URL(url)
        if (!allow(u.host, ctx)) {
            Log.w(TAG, "拦下一笔出网：$url")
            throw CloudClosed(u.host)
        }
        return u.openConnection() as HttpURLConnection
    }

    /**
     * 裸 socket（MQTT / 文件传输 / 推流 / 局域网发现）在 `connect` 之前问一次。
     *
     * ★ 它们平时连的都是私有地址，闸开着关着都不受影响；问这一句是为了
     *   **万一**他把 MQTT 代理填成公网地址 —— 那时候它也是云端。
     */
    fun checkHost(host: String?, ctx: Context? = null) {
        if (!allow(host, ctx)) {
            Log.w(TAG, "拦下一笔 socket 出网：$host")
            throw CloudClosed(host ?: "?")
        }
    }
}
