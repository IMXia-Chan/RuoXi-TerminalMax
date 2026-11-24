package com.example.touchpad

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/**
 * **MQTT** —— 连一台消息代理，往里发消息、从里读消息。
 *
 * 他 2026-11-06 在「API 宿主系统」四项之外点名要「协议层」，2026-11-07 选了先做 MQTT。
 *
 * ## 为什么协议层不能做成「又一张 HTTP 表」
 *
 * 前面那四种（自己加 API / MCP / OAuth / 连接器目录）全是「发一条请求、等一个响应」。
 * MQTT 不是：它是**一条常连的线 + 一个主题树**。发出去的消息不等于有人收到，
 * 收到消息也不需要你先问 —— 这是两种完全不同的问法，硬塞进 [ApiExec] 只会两边都不对。
 *
 * ## ★ 这一版的范围（写死在界面上，别让人猜）
 *
 * - 协议版本 **3.1.1**（v5 的属性表和原因码这一版不做）。
 * - 发：**QoS 0 和 1**。读：QoS 0/1（收到 QoS 1 会回 PUBACK）。
 * - **不做常驻推送**：没有后台长连、没有「新消息主动叫醒她」。
 *   读消息这件事做成「订阅 N 秒，把这段时间里来的收下来」（见 [MqttClient.collect]）。
 *   理由很实际 —— ColorOS 会把后台进程冻死（见记忆 ruoxi-keepalive-facts），
 *   挂一条长连等于做一个**看起来在工作、实际早被冻住**的东西。
 */
data class MqttBroker(
    val id: String,
    val label: String,
    val host: String,
    val port: Int = 1883,
    /** 2883/8883 那一类。开了就走 TLS。 */
    val tls: Boolean = false,
    /** 留空 = 用 `conmarn-<id>` 自动生成。 */
    val clientId: String = "",
    val username: String = "",
    /** 落盘前加密（和 API key 同一套），见 [MqttStore]。 */
    val password: String = "",
    val enabled: Boolean = true,
) {
    val clientIdOrAuto: String
        get() = clientId.ifBlank { "conmarn-" + id }
}

/**
 * 纯逻辑：报文编解码 + 主题匹配。
 *
 * ★ 放在这里而不是散在 [MqttClient] 里，理由和别的 `*Math` 一样：这些形状错了
 *   **不会崩**。发出去的报文头错一个字节，对方要么静默断开、要么把我们当成
 *   一个坏客户端挂在那儿 —— 而手机这边只觉得「没反应」。
 */
object MqttMath {

    const val ID_PREFIX = "mqtt:"

    /** 报文类型（高 4 位）。 */
    const val CONNECT = 1
    const val CONNACK = 2
    const val PUBLISH = 3
    const val PUBACK = 4
    const val SUBSCRIBE = 8
    const val SUBACK = 9
    const val PINGREQ = 12
    const val PINGRESP = 13
    const val DISCONNECT = 14

    fun isMqtt(handId: String): Boolean = handId.startsWith(ID_PREFIX)

    fun brokerIdOf(handId: String): String = handId.removePrefix(ID_PREFIX)

    // ------------------------------------------------------------------
    // 变长整数：每一字节低 7 位是数据，最高位是「后面还有」
    // ------------------------------------------------------------------

    fun encodeLength(n: Int): ByteArray {
        require(n in 0..268_435_455) { "剩余长度超范围：$n" }
        val out = ArrayList<Byte>()
        var v = n
        do {
            var b = v and 0x7F
            v = v ushr 7
            if (v > 0) b = b or 0x80
            out.add(b.toByte())
        } while (v > 0)
        return out.toByteArray()
    }

    /**
     * 读一个变长整数。→ (值, 占了几个字节)；读不全就 → null。
     *
     * ★ 最多四个字节。不设这个上限的话，一段被截断的垃圾数据能让它一直读到
     *   buf 末尾再抛下标越界 —— 而那个异常在调用方看来和「连接断了」一样。
     */
    fun decodeLength(buf: ByteArray, offset: Int): Pair<Int, Int>? {
        var value = 0
        var mult = 1
        var i = 0
        while (i < 4) {
            val at = offset + i
            if (at >= buf.size) return null
            val b = buf[at].toInt() and 0xFF
            value += (b and 0x7F) * mult
            if (b and 0x80 == 0) return value to (i + 1)
            mult *= 128
            i++
        }
        return null
    }

    // ------------------------------------------------------------------
    // 主题
    // ------------------------------------------------------------------

    /** 发布用的主题：**不许带通配符**。带了的话服务端会直接断开连接。 */
    fun topicOk(topic: String): Boolean =
        topic.isNotEmpty() &&
            topic.length <= 60_000 &&
            !topic.contains('+') &&
            !topic.contains('#') &&
            !topic.contains('\u0000')

    /** 订阅用的过滤器：`#` 只能在最后且独占一层，`+` 必须独占一层。 */
    fun filterOk(filter: String): Boolean {
        if (filter.isEmpty() || filter.contains('\u0000')) return false
        val levels = filter.split('/')
        for ((i, lv) in levels.withIndex()) {
            if (lv.contains('#')) {
                if (lv != "#" || i != levels.lastIndex) return false
            }
            if (lv.contains('+') && lv != "+") return false
        }
        return true
    }

    /**
     * MQTT 的主题匹配（含 `+` 单层、`#` 多层）。
     *
     * ★ 这个函数**不用在网络上**（服务端才是权威），它只用来在本地做
     *   「这条消息是不是我要的那个前缀」。写错了不会少收消息，只会多收 ——
     *   而多收的那条会被当成用户要的答案念出来。
     */
    fun matches(filter: String, topic: String): Boolean {
        val f = filter.split('/')
        val t = topic.split('/')
        var i = 0
        while (i < f.size) {
            val fv = f[i]
            if (fv == "#") return true
            if (i >= t.size) return false
            if (fv != "+" && fv != t[i]) return false
            i++
        }
        return i == t.size
    }

    // ------------------------------------------------------------------
    // 报文
    // ------------------------------------------------------------------

    private fun str(s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        return byteArrayOf((b.size shr 8).toByte(), b.size.toByte()) + b
    }

    private fun u16(n: Int): ByteArray = byteArrayOf((n shr 8).toByte(), n.toByte())

    private fun packet(type: Int, flags: Int, body: ByteArray): ByteArray =
        byteArrayOf(((type shl 4) or flags).toByte()) + encodeLength(body.size) + body

    /**
     * CONNECT。
     *
     * ★ `cleanSession = true`：每次都是新会话。理由是我们本来就不打算常驻 ——
     *   开着持久会话会让服务端替我们攒一堆 QoS 1 消息，攒到上限之后**开始丢**，
     *   而丢的时候谁都不知道。
     */
    fun connect(
        clientId: String,
        username: String,
        password: String,
        keepAliveSec: Int = 60,
    ): ByteArray {
        var flags = 0x02 // clean session
        if (username.isNotEmpty()) flags = flags or 0x80
        if (password.isNotEmpty()) flags = flags or 0x40
        var body = str("MQTT") + byteArrayOf(4) + byteArrayOf(flags.toByte()) +
            u16(keepAliveSec)
        body += str(clientId)
        if (username.isNotEmpty()) body += str(username)
        if (password.isNotEmpty()) body += str(password)
        return packet(CONNECT, 0, body)
    }

    /**
     * @param retain 保留消息：服务端会把它存下来，**之后**才订阅的人也能立刻拿到。
     *   用于「先发一条、再收一段」的验货（见 MqttLiveTest）和真正的「当前状态」场景。
     */
    fun publish(
        topic: String,
        message: ByteArray,
        qos: Int = 0,
        packetId: Int = 1,
        retain: Boolean = false,
    ): ByteArray {
        require(qos in 0..1) { "这一版只做 QoS 0/1" }
        var body = str(topic)
        if (qos > 0) body += u16(packetId)
        body += message
        return packet(PUBLISH, (qos shl 1) or (if (retain) 0x01 else 0), body)
    }

    /** 主题 + 一个 QoS 字节。多个主题**必须各有各的 QoS**，少一个服务端就断开。 */
    fun subscribe(packetId: Int, filters: List<Pair<String, Int>>): ByteArray {
        var body = u16(packetId)
        for ((f, q) in filters) body += str(f) + byteArrayOf(q.toByte())
        return packet(SUBSCRIBE, 0x02, body)
    }

    fun puback(packetId: Int): ByteArray = packet(PUBACK, 0, u16(packetId))

    fun pingReq(): ByteArray = packet(PINGREQ, 0, ByteArray(0))

    fun disconnect(): ByteArray = packet(DISCONNECT, 0, ByteArray(0))

    /** 收下来的一个报文。`body` 是去掉固定头之后的部分。 */
    data class Packet(val type: Int, val flags: Int, val body: ByteArray, val total: Int)

    /**
     * 从缓冲区头部解一个报文。→ null = 还没收全（继续读）。
     *
     * ★ 返回值里带 [Packet.total]（固定头 + 变长头 + 正文一共多少字节），
     *   调用方按它从缓冲里切掉。少了它就得在调用方再算一遍长度，
     *   而那正是变长整数最容易算错的地方。
     */
    fun parse(buf: ByteArray): Packet? {
        if (buf.size < 2) return null
        val type = (buf[0].toInt() and 0xFF) shr 4
        val flags = buf[0].toInt() and 0x0F
        val (len, n) = decodeLength(buf, 1) ?: return null
        val start = 1 + n
        if (buf.size < start + len) return null
        return Packet(type, flags, buf.copyOfRange(start, start + len), start + len)
    }

    /** CONNACK 里的返回码。0 才是成功。 */
    fun connackReason(code: Int): String = when (code) {
        0 -> "接通了"
        1 -> "服务端不接受这个协议版本"
        2 -> "客户端编号被服务端拒绝"
        3 -> "服务端不可用"
        4 -> "用户名或密码不对"
        5 -> "没有被授权（这台代理不让连）"
        else -> "服务端返回了原因码 $code"
    }

    /** 一条 PUBLISH 报文 → (主题, 正文, 要回的 packetId；QoS 0 时是 0)。 */
    fun readPublish(p: Packet): Triple<String, String, Int>? {
        if (p.type != PUBLISH) return null
        var i = 0
        if (p.body.size < 2) return null
        val topicLen = ((p.body[0].toInt() and 0xFF) shl 8) or (p.body[1].toInt() and 0xFF)
        i = 2
        if (p.body.size < i + topicLen) return null
        val topic = String(p.body, i, topicLen, Charsets.UTF_8)
        i += topicLen
        val qos = (p.flags shr 1) and 0x03
        var pid = 0
        if (qos > 0) {
            if (p.body.size < i + 2) return null
            pid = ((p.body[i].toInt() and 0xFF) shl 8) or (p.body[i + 1].toInt() and 0xFF)
            i += 2
        }
        val payload = String(p.body, i, p.body.size - i, Charsets.UTF_8)
        return Triple(topic, payload, pid)
    }

    /** 订阅回执里每一档的返回值。0/1/2 = 用这个 QoS；0x80 = 拒了。 */
    fun subackRejected(p: Packet): Boolean =
        p.body.size >= 2 && p.body.drop(2).any { (it.toInt() and 0xFF) == 0x80 }
}

/** 落盘：`filesDir/mqtt/brokers.json`。密码加密，和 API key 同一套。 */
internal object MqttStore {

    private var dir: File? = null
    private val lock = Any()
    private var brokers: List<MqttBroker> = emptyList()

    fun init(ctx: android.content.Context) {
        if (dir != null) return
        synchronized(lock) {
            if (dir != null) return
            val d = File(ctx.filesDir, "mqtt").apply { mkdirs() }
            dir = d
            runCatching { brokers = read(File(d, "brokers.json")) }
        }
    }

    fun all(): List<MqttBroker> = synchronized(lock) { brokers }

    fun broker(id: String): MqttBroker? = all().firstOrNull { it.id == id }

    fun upsert(b: MqttBroker): Boolean = synchronized(lock) {
        brokers = brokers.filterNot { it.id == b.id } + b
        write()
    }

    fun remove(id: String): Boolean = synchronized(lock) {
        brokers = brokers.filterNot { it.id == id }
        write()
    }

    fun newId(label: String): String {
        val base = label.lowercase().filter { it.isLetterOrDigit() }.ifBlank { "mqtt" }
        val taken = all().map { it.id }.toSet()
        if (base !in taken) return base
        var i = 2
        while ("$base$i" in taken) i++
        return "$base$i"
    }

    private fun write(): Boolean = runCatching {
        val d = dir ?: return false
        val arr = JSONArray()
        for (b in brokers) {
            val o = JSONObject()
                .put("id", b.id)
                .put("label", b.label)
                .put("host", b.host)
                .put("port", b.port)
                .put("tls", b.tls)
                .put("clientId", b.clientId)
                .put("username", b.username)
                .put("enabled", b.enabled)
            if (b.password.isNotEmpty()) {
                // ★ 加不上就不存。**绝不给「加密失败就存明文」留一条路。**
                o.put("password", TouchpadClient.SecretCipher.encryptApi(b.password) ?: "")
            }
            arr.put(o)
        }
        val f = File(d, "brokers.json")
        val tmp = File(d, "brokers.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
        true
    }.getOrDefault(false)

    private fun read(f: File): List<MqttBroker> {
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        val out = ArrayList<MqttBroker>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            val host = o.optString("host").trim()
            if (id.isEmpty() || host.isEmpty()) continue
            val rawPw = o.optString("password", "")
            out.add(
                MqttBroker(
                    id = id,
                    label = o.optString("label").ifBlank { id },
                    host = host,
                    port = o.optInt("port", 1883),
                    tls = o.optBoolean("tls", false),
                    clientId = o.optString("clientId"),
                    username = o.optString("username"),
                    password = if (rawPw.isBlank()) "" else TouchpadClient.SecretCipher.decryptApi(rawPw).orEmpty(),
                    enabled = o.optBoolean("enabled", true),
                )
            )
        }
        return out
    }
}

/**
 * 真的连上去做事。
 *
 * ★ 这里**不复用连接**：每次调用开一条、做完关掉。理由写在 [MqttBroker] 那边 ——
 *   常驻长连在 ColorOS 上迟早被冻住，而冻住的那条线看起来和「信号不好」一样。
 *   开一次连一次，代价是几百毫秒，换来的是「每次调用都是真的」。
 */
object MqttClient {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 15_000

    /** 这台代理能用吗（面板上那行字也用它）。→ null = 能用。 */
    fun check(b: MqttBroker): String? = when {
        b.host.isBlank() -> "地址还没填。"
        b.port !in 1..65535 -> "端口不对。"
        else -> null
    }

    /**
     * 一次「连上 → 做点事 → 断开」。
     *
     * @param body 连上之后要做什么。拿到的是已经连通的 socket 和一个「发一个报文」。
     * @return 人话结果；失败时返回以 `✗` 开头的说明。
     */
    private fun <T> session(b: MqttBroker, body: (MqttSession) -> T): T {
        check(b)?.let { throw IllegalStateException(it) }
        // ★ 云端总闸：代理填成公网地址时，它也算云端。私有地址（家里那台）照常放行。
        CloudNet.checkHost(b.host)
        val socket = if (b.tls) {
            (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket()
                .also { it.connect(InetSocketAddress(b.host, b.port), CONNECT_TIMEOUT_MS) }
        } else {
            Socket().apply { connect(InetSocketAddress(b.host, b.port), CONNECT_TIMEOUT_MS) }
        }
        socket.soTimeout = READ_TIMEOUT_MS
        try {
            val s = MqttSession(socket)
            s.send(MqttMath.connect(b.clientIdOrAuto, b.username, b.password))
            val ack = s.readPacket(5_000) ?: throw IllegalStateException("服务端没有回连接回执。")
            if (ack.type != MqttMath.CONNACK) {
                throw IllegalStateException("服务端回的第一条不是连接回执（类型 ${ack.type}）。")
            }
            val code = if (ack.body.isEmpty()) 255 else (ack.body[0].toInt() and 0xFF)
            if (code != 0) throw IllegalStateException(MqttMath.connackReason(code))
            return try {
                body(s)
            } finally {
                runCatching { s.send(MqttMath.disconnect()) }
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    /** 发一条消息。 */
    fun publish(
        b: MqttBroker,
        topic: String,
        message: String,
        qos: Int = 0,
        retain: Boolean = false,
    ): String {
        if (!MqttMath.topicOk(topic)) return "这个主题不能用来发消息（不能带 + 或 #，也不能为空）。"
        val q = qos.coerceIn(0, 1)
        return try {
            session(b) { s ->
                s.send(
                    MqttMath.publish(
                        topic, message.toByteArray(Charsets.UTF_8), q, s.nextId(), retain,
                    )
                )
                if (q > 0) {
                    // QoS 1 要等到 PUBACK 才算发出去了。不等的话，「成功」是我们自己说的。
                    val ack = s.readPacket(5_000)
                        ?: return@session "消息已经发出，但没等到服务端确认（QoS $q）。"
                    if (ack.type != MqttMath.PUBACK) return@session "消息已经发出，但服务端没确认。"
                }
                "已发送到 ${b.label} 的 $topic（${message.length} 字）。"
            }
        } catch (e: Exception) {
            "连不上 ${b.label}：" + why(e)
        }
    }

    /**
     * 订阅一段时间，把这段时间里来的消息收下来。
     *
     * @param seconds 最多等多久（1–20）。**用户要的是「现在是多少」，
     *   不是「一直等下去」** —— 所以有上限，而且到了就如实说「这段时间里没有新消息」。
     */
    fun collect(
        b: MqttBroker,
        filter: String,
        seconds: Int,
        maxMessages: Int = 20,
    ): List<Pair<String, String>> {
        if (!MqttMath.filterOk(filter)) throw IllegalArgumentException("这个订阅主题不合法。")
        val waitMs = (seconds.coerceIn(1, 20)) * 1000L
        val out = ArrayList<Pair<String, String>>()
        session(b) { s ->
            s.send(MqttMath.subscribe(s.nextId(), listOf(filter to 1)))
            val ack = s.readPacket(5_000)
            if (ack == null || ack.type != MqttMath.SUBACK) {
                throw IllegalStateException("订阅没有得到确认。")
            }
            if (MqttMath.subackRejected(ack)) {
                throw IllegalStateException("服务端不接受订阅 $filter（可能没有权限）。")
            }
            val until = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < until && out.size < maxMessages) {
                val left = until - System.currentTimeMillis()
                if (left <= 0) break
                val p = s.readPacket(left) ?: continue
                when (p.type) {
                    MqttMath.PUBLISH -> {
                        val m = MqttMath.readPublish(p) ?: continue
                        // QoS 1 要回执，否则服务端会一直重发同一条。
                        if (m.third > 0) runCatching { s.send(MqttMath.puback(m.third)) }
                        out.add(m.first to m.second)
                    }
                    // 服务端也会在我们等的期间发 PINGRESP（如果它自己 ping）。
                    // 不回它，有的代理 90 秒就把我们踢掉 —— 这里什么都不用做。
                    else -> Unit
                }
            }
        }
        return out
    }

    /** 面板上的「测一测」。连上、拿回执、断开。 */
    fun ping(b: MqttBroker): String = try {
        session(b) { "通了（${b.host}:${b.port}${if (b.tls) "，TLS" else ""}）" }
    } catch (e: Exception) {
        "连不上：" + why(e)
    }

    /**
     * 一句人话的失败原因。
     *
     * ★ 我们自己抛的 [IllegalStateException] 里已经写好了人话（连接被拒的原因码、
     *   订阅被拒……），**原样用**；只有网络层那几种才需要翻译成「什么坏了」。
     *   混在一起翻会把「用户名或密码不对」变成「连不上」，用户就会去查网络。
     */
    private fun why(e: Exception): String {
        if (e is IllegalStateException) return e.message.orEmpty().ifBlank { "这次没成。" }
        val m = e.message.orEmpty()
        return when (e) {
            is java.net.UnknownHostException -> "找不到这个地址。"
            is java.net.SocketTimeoutException -> "等太久了，对面没回应。"
            is java.net.ConnectException -> "对面没有接（端口对吗？服务开着吗？）"
            is javax.net.ssl.SSLException -> "TLS 握手失败（这个端口是不是不带 TLS？）"
            else -> if (m.isBlank()) e.javaClass.simpleName else m
        }
    }
}

/**
 * 把 MQTT 代理变成她菜单上的一只手。
 *
 * ★ 走的是和 [CloudHand] / [McpClient] 同一条路（`kind = KIND_CLOUD`）：
 *   「云端兜底:关」那个总闸、调用日志、回执形状都是同一份 —— 不另起一套。
 * ★ 工具只有两个：发、收。**不做「列出所有主题」**：MQTT 没有这个概念
 *   （主题是发出来的，不是登记过的），做出来只会是一个假清单。
 */
object MqttHand {

    fun hands(): List<Hand> = MqttStore.all().filter { it.enabled }.map { b ->
        Hand(
            id = MqttMath.ID_PREFIX + b.id,
            kind = HandMath.KIND_CLOUD,
            name = b.label,
            version = "1",
            fetchedAtMs = System.currentTimeMillis(),
            tools = listOf(
                HandTool(
                    name = "publish",
                    desc = "往「${b.label}」的一台设备发一条消息。主题按那台设备的要求写。",
                    params = listOf(
                        HandParam("topic", "主题，比如 home/livingroom/light/set"),
                        HandParam("message", "消息内容，比如 ON"),
                    ),
                    required = listOf("topic", "message"),
                    exec = null,
                ),
                HandTool(
                    name = "read",
                    desc = "从「${b.label}」读一段时间的消息。订阅一个主题，等几秒，" +
                        "把这段时间里来的都收回来。**不会一直等** —— 到点就把收到的给你。",
                    params = listOf(
                        HandParam("topic", "订阅主题，可以用 + 和 # 通配，比如 home/livingroom/#"),
                        HandParam("seconds", "等几秒，1 到 20，不填按 5"),
                    ),
                    required = listOf("topic"),
                    exec = null,
                ),
            ),
            // ★ 空列表 = 不占排他资源 = 可以并发。理由和云端那只手一样。
            locks = mapOf("publish" to emptyList(), "read" to emptyList()),
        )
    }

    fun exec(hand: Hand, toolName: String, args: JSONObject): JSONObject {
        val b = MqttStore.broker(MqttMath.brokerIdOf(hand.id))
            ?: return fail("找不到这台代理（可能刚被删掉）。")
        MqttClient.check(b)?.let { return fail(it) }
        return when (toolName) {
            "publish" -> {
                val topic = args.optString("topic").trim()
                val message = args.optString("message")
                if (topic.isEmpty()) return fail("还缺参数：topic。")
                val r = MqttClient.publish(b, topic, message)
                if (r.startsWith("连不上") || r.startsWith("这个主题")) fail(r)
                else JSONObject().put("ok", true).put("data", r)
            }
            "read" -> {
                val topic = args.optString("topic").trim()
                if (topic.isEmpty()) return fail("还缺参数：topic。")
                val secs = args.optString("seconds").toIntOrNull() ?: 5
                try {
                    val list = MqttClient.collect(b, topic, secs)
                    if (list.isEmpty()) {
                        JSONObject().put("ok", true)
                            .put("data", "$secs 秒内 ${b.label} 上没有新消息。")
                    } else {
                        val text = list.joinToString("\n") { (t, m) -> "$t → $m" }
                        JSONObject().put("ok", true).put("data", text)
                    }
                } catch (e: Exception) {
                    fail(e.message ?: "读不到消息。")
                }
            }
            else -> fail("「${b.label}」上没有「$toolName」这个工具。")
        }
    }

    private fun fail(why: String): JSONObject = JSONObject().put("ok", false).put("error", why)
}

/** 一条已经连通的 TCP/TLS 线。收报文这一层要处理「一次 read 只回来半条」。 */
internal class MqttSession(private val socket: Socket) {

    private val buf = java.io.ByteArrayOutputStream()
    private var packetId = 1

    fun nextId(): Int {
        val v = packetId
        packetId = if (packetId >= 65_535) 1 else packetId + 1
        return v
    }

    fun send(bytes: ByteArray) {
        socket.getOutputStream().apply { write(bytes); flush() }
    }

    /**
     * 读到一条完整的报文，或者超时 → null。
     *
     * ★ 必须**攒够一整条**再交出去：TCP 是字节流，一条报文可能分两次到。
     *   一次 read 就当成一条报文的话，短包会解析失败、长包会把下一条的头吃掉 ——
     *   而症状是「偶尔少一条消息」，最难查的一类。
     */
    fun readPacket(timeoutMs: Long): MqttMath.Packet? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val tmp = ByteArray(4096)
        while (true) {
            val parsed = MqttMath.parse(buf.toByteArray())
            if (parsed != null) {
                // 切掉已经消费掉的那一段，剩下的留给下一条。
                val all = buf.toByteArray()
                buf.reset()
                if (parsed.total < all.size) buf.write(all, parsed.total, all.size - parsed.total)
                return parsed
            }
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            socket.soTimeout = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val n = try {
                socket.getInputStream().read(tmp)
            } catch (e: java.net.SocketTimeoutException) {
                return null
            }
            if (n < 0) return null
            if (n > 0) buf.write(tmp, 0, n)
        }
    }
}
