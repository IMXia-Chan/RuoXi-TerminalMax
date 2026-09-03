package com.example.touchpad

import android.app.KeyguardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import android.util.Log

/**
 * Wi-Fi TCP 客户端:负责连接电脑、完成「动态配对码 + TOTP 动态码」双因素认证,
 * 认证通过后把触摸手势翻译成的控制帧(HMAC 签名)发给电脑,并接收电脑推送的屏幕画面。
 *
 * 认证流程(每次连接都完整重走):
 *   连接 -> 收 PIN_REQUIRED -> 用户输入电脑弹窗里的配对码 -> 发 PIN
 *        -> 首次配对:收 SETUP,手机扫电脑二维码得到种子
 *        -> 已配对:收 TOTP_REQUIRED,本地有种子则算 TOTP;没种子则用恢复码重新配对
 *        -> 收 OK -> 进入控制模式
 *
 * 控制模式支持:
 *   M dx dy wheel buttons   相对移动(触控板)
 *   A x y buttons           绝对坐标点击(屏幕视图)
 *   T base64                把文本打到电脑
 *   V 0|1                   关/开屏幕镜像;开之后电脑持续推 FRAME(JPEG)
 *
 * TOTP 种子按「电脑 IP」分别存储,并用 Android Keystore(AES-GCM)加密,不落明文。
 */
class TouchpadClient(context: Context) {

    interface Listener {
        fun onStatus(status: String)
        fun onPinRequired()      // 电脑端已弹配对码,手机端弹输入框
        fun onSecretRequired()   // 首次/恢复配对:电脑端已显示二维码,手机端扫码
        fun onRecoveryRequired() // 本地没种子但电脑已配对:手机端弹恢复码输入框
        fun onConnected()
        fun onDisconnected()
        fun onError(message: String)   // 认证/连接失败(与正常断开区分)
        fun onResumed() {}             // 掉线后自动免码续连成功(UI 不重置);默认空实现
        fun onNewMediaToken(token: String) {}  // 服务器签发新媒体 token(续连后刷新媒体流用)
        fun onFrame(jpeg: ByteArray, screenW: Int, screenH: Int) // 收到一帧屏幕画面
        fun onMediaCommand(cmd: String)  // 电脑端反向控制:切换摄像头/麦克风
        fun onCursor(x: Int, y: Int) {}  // 电脑回传的真实光标位置(镜像箭头跟着它走);默认空实现
    }

    var listener: Listener? = null

    /** 媒体流(摄像头/麦克风)连接参数。 */
    data class MediaConfig(val ip: String, val port: Int, val token: String)

    private val appContext = context.applicationContext
    private val prefs = appContext
        .getSharedPreferences("touchpad_secret", Context.MODE_PRIVATE)

    private val mainHandler = Handler(Looper.getMainLooper())

    private var socket: Socket? = null
    private var writer: OutputStream? = null
    private val writeLock = Any()

    private val active = AtomicBoolean(false)     // 连接线程是否在跑
    private val connected = AtomicBoolean(false)  // 是否已认证进入控制模式

    // 媒体流所需:电脑 IP/端口 + 认证通过后签发的媒体 token
    private var hostIp = ""
    private var hostPort = 9527
    @Volatile private var mediaToken: String? = null

    // 免码续连:认证通过后电脑签发;掉线后凭它几分钟内静默重连,不用重输配对码
    @Volatile private var resumeToken: String? = null
    private val socketLock = Any()                 // 保护 socket/writer 的换连(发送线程 vs 续连线程)
    private val heartbeatStarted = AtomicBoolean(false)

    private val pinQueue = LinkedBlockingQueue<String>()
    private val secretQueue = LinkedBlockingQueue<String>()
    private val recoveryQueue = LinkedBlockingQueue<String>()

    private var sessionKey: ByteArray? = null
    private var seq = 0
    private val generation = AtomicInteger(0)   // 连接代际:新连接启动后,旧连接线程立即失效

    private val sendQueue = LinkedBlockingQueue<String>()

    init {
        // 独立写线程:手势在 UI 线程触发,算好签名后只入队;真正的 socket 写
        // 统一放到这里(后台线程),避免主线程网络 IO 抛 NetworkOnMainThreadException。
        Thread {
            while (true) {
                val line = try {
                    sendQueue.take()
                } catch (e: InterruptedException) {
                    break
                }
                val out = writer
                val cur = socket
                if (out == null) continue
                try {
                    writeLine(out, line)
                    Log.d(TAG, "已发送: $line")
                } catch (e: Exception) {
                    Log.e(TAG, "发送失败: $line", e)
                    // 只处理「当前会话」socket 的失败:关掉它唤醒读线程,让读线程去自动续连。
                    // 若 socket 已被续连换走,说明是旧连接的残留失败,忽略即可。
                    synchronized(socketLock) {
                        if (cur === socket) {
                            connected.set(false)
                            sessionKey = null
                            socket = null
                            writer = null
                            try { cur?.close() } catch (_: Exception) {}
                        }
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 连接电脑。IP/端口由 UI 传入。
     * 连接顺序:有上次存下的免密 token 就先试免密(秒连,不输码);
     * token 没有/过期(服务器回 ERR resume expired)才回落到完整配对(配对码 + TOTP)。
     * 断线后自动免码续连;免密 token 已加密存盘,重启 App 也能在几分钟内免密。
     */
    fun connect(ip: String, port: Int) {
        disconnect()
        hostIp = ip
        hostPort = port
        mediaToken = null
        resumeToken = loadResumeToken(ip)   // 跨重启免密:读上次存下的凭证(空=走配对)
        active.set(true)
        startHeartbeat()
        val myGen = generation.incrementAndGet()
        Thread {
            var input: BufferedInputStream? = null
            var output: OutputStream? = null

            // 打开一条到电脑的 TCP 连接(共用)。返回 null 表示连不上或已被取消。
            fun openRaw(): Pair<BufferedInputStream, OutputStream>? {
                if (myGen != generation.get() || !active.get()) return null
                return try {
                    val s = Socket()
                    s.tcpNoDelay = true   // 禁用 Nagle,减小控制帧延迟
                    s.keepAlive = true    // TCP keepalive,防网络层静默断连
                    s.connect(InetSocketAddress(hostIp, hostPort), 5000)
                    if (myGen != generation.get()) {
                        try { s.close() } catch (_: Exception) {}
                        return null
                    }
                    synchronized(socketLock) {
                        socket = s
                        writer = s.getOutputStream()
                    }
                    Pair(BufferedInputStream(s.getInputStream()), s.getOutputStream())
                } catch (e: Exception) {
                    null
                }
            }

            // 断线续连:凭免码 token 静默重连。成功返回 true 并已换好 input/output/sessionKey。
            fun tryRecover(): Boolean {
                var tries = 0
                while (active.get() && myGen == generation.get() && tries < MAX_RESUME_TRIES) {
                    tries++
                    val tok = resumeToken ?: return false   // 没有 token 无法免码续连
                    val opened = openRaw() ?: run {
                        Thread.sleep(RESUME_RETRY_MS)
                        continue
                    }
                    val (in2, out2) = opened
                    var needClose = true
                    try {
                        writeLine(out2, "RESUME $tok")   // 一上来就表明身份,电脑不会弹新配对码
                        val resp = readLine(in2)
                        if (resp == "OK") {
                            sessionKey = deriveResumeSessionKey(tok)
                            seq = 0
                            connected.set(true)
                            input = in2
                            output = out2
                            sendQueue.clear()   // 丢弃断线期间排队的旧指令(签名已对不上)
                            Log.d(TAG, "免码续连成功,重进读循环")
                            needClose = false
                            post { listener?.onResumed() }
                            return true
                        }
                        if (resp?.startsWith("ERR resume") == true) {
                            resumeToken = null   // 过期/失效:本次放弃,回落到人工配对
                            deleteStoredResume(hostIp)   // 盘上凭证一并删掉,下次从完整配对重新走
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "续连失败", e)
                    } finally {
                        if (needClose) {
                            try { in2.close() } catch (_: Exception) {}
                        }
                    }
                    Thread.sleep(RESUME_RETRY_MS)
                }
                return false
            }

            // 控制模式读循环;服务器断开/读异常时自动续连,续不上才结束会话。
            // input/output 由 tryRecover 换新,这里是同一条线程,无并发。
            fun readLoop() {
                while (active.get() && myGen == generation.get()) {
                    val line = try {
                        val i = input
                        if (i == null) null else readLine(i)
                    } catch (e: Exception) {
                        null
                    }
                    if (line == null) {
                        Log.d(TAG, "读循环中断,尝试自动免码续连…")
                        if (!tryRecover()) return   // 续不上:结束会话,上报断开
                        continue
                    }
                    when {
                        line.startsWith("FRAME ") -> {
                            val parts = line.split(" ")
                            if (parts.size >= 4) {
                                val len = parts[1].toIntOrNull()
                                val w = parts[2].toIntOrNull()
                                val h = parts[3].toIntOrNull()
                                if (len != null && w != null && h != null && len > 0) {
                                    val data = input?.let { readExact(it, len) } ?: return
                                    post { listener?.onFrame(data, w, h) }
                                }
                            }
                        }
                        line.startsWith("MEDIA_TOKEN ") -> {
                            mediaToken = line.substringAfter("MEDIA_TOKEN ").trim()
                            Log.d(TAG, "收到媒体 token")
                            post { listener?.onNewMediaToken(mediaToken ?: "") }
                        }
                        line.startsWith("RESUME ") -> {
                            val tok = line.substringAfter("RESUME ").trim()
                            resumeToken = tok
                            saveResumeToken(hostIp, tok)   // 跨重启免密:加密存盘,下次冷启动也能免密连
                            Log.d(TAG, "收到免码续连 token")
                        }
                        line.startsWith("CMD ") -> {
                            post { listener?.onMediaCommand(line.substringAfter("CMD ").trim()) }
                        }
                        line.startsWith("CP ") -> {
                            val cp = line.split(" ")
                            if (cp.size >= 3) {
                                val cx = cp[1].toIntOrNull()
                                val cy = cp[2].toIntOrNull()
                                if (cx != null && cy != null) {
                                    post { listener?.onCursor(cx, cy) }
                                }
                            }
                        }
                    }
                }
            }

            try {
                // ---- (A) 免密自动连:有上次存下的 token 就先试,秒连不输码 ----
                // 服务器收到 RESUME:通过回 OK(不弹新配对码);token 过期回 ERR resume expired 并断开。
                var authOk = false
                val rTok = resumeToken
                if (rTok != null) {
                    val op = openRaw()
                    if (op != null) {
                        // op.first/second 是非空本地,直接用它做免密 IO;input/output 同步赋好供后续 readLoop 用
                        val rIn = op.first
                        val rOut = op.second
                        input = rIn
                        output = rOut
                        try {
                            writeLine(rOut, "RESUME $rTok")
                            val rr = readLine(rIn)
                            if (rr == "OK") {
                                sessionKey = deriveResumeSessionKey(rTok)
                                seq = 0
                                connected.set(true)
                                authOk = true
                                Log.d(TAG, "免密自动连上 $hostIp")
                            } else if (rr?.startsWith("ERR resume") == true) {
                                resumeToken = null
                                deleteStoredResume(hostIp)   // 过期/失效:删掉,下面走完整配对
                            }
                        } finally {
                            if (!authOk) {
                                // 服务器已断开本次会话(ERR/EOF):关掉连接,走下面的手动配对
                                try { op.first.close() } catch (_: Exception) {}
                                input = null
                                output = null
                            }
                        }
                    }
                }

                if (!authOk) {
                    // ---- (B) 手动完整配对 ----
                    val opened = openRaw() ?: throw TouchpadException("无法连接电脑 $hostIp:$hostPort")
                    // bIn/bOut:本段认证期间的本地非空别名(闭包里没法 smart-cast);input/output 同步赋好供 readLoop 用
                    val bIn = opened.first
                    val bOut = opened.second
                    input = bIn
                    output = bOut

                    // ---- 因素一:动态配对码 ----
                    val resp1 = readLine(bIn) ?: throw TouchpadException("服务器无响应")
                    if (resp1 != "PIN_REQUIRED") throw TouchpadException("协议错误: $resp1")
                    if (myGen != generation.get()) return@Thread
                    post { listener?.onPinRequired() }
                    val pin = await(pinQueue, PIN_TTL_SEC) ?: throw TouchpadException("配对码输入超时")
                    writeLine(bOut, "PIN $pin")
                    var resp = readLine(bIn) ?: throw TouchpadException("服务器断开")
                    if (resp.startsWith("ERR")) throw TouchpadException("配对码错误")

                    // ---- 因素二:种子(首次配对扫码 / 已配对算 TOTP / 丢失用恢复码) ----
                    var secret = loadSecret(ip)
                    while (true) {
                        when (resp) {
                            "SETUP" -> {
                                // 首次配对或恢复配对:电脑显示二维码,手机扫码得种子
                                post { listener?.onSecretRequired() }
                                val scanned = await(secretQueue, SECRET_TTL_SEC)
                                    ?: throw TouchpadException("扫码超时")
                                val cleaned = scanned.replace("-", "").replace(" ", "").lowercase()
                                if (cleaned.length != 32 || !cleaned.all { it in "0123456789abcdef" }) {
                                    throw TouchpadException("种子格式错误(应为 32 位字符)")
                                }
                                secret = cleaned
                                saveSecret(ip, cleaned)
                            }
                            "TOTP_REQUIRED" -> {
                                if (secret == null) {
                                    // 本地没种子但电脑已配对:用一次性恢复码重新配对
                                    post { listener?.onRecoveryRequired() }
                                    val recovery = await(recoveryQueue, SECRET_TTL_SEC)
                                        ?: throw TouchpadException("恢复码输入超时")
                                    writeLine(bOut, "RECOVER $recovery")
                                    resp = readLine(bIn) ?: throw TouchpadException("服务器断开")
                                    if (resp.startsWith("ERR")) throw TouchpadException("恢复码错误")
                                    continue  // resp == "SETUP",重进循环扫码
                                }
                            }
                            else -> throw TouchpadException("协议错误: $resp")
                        }
                        val sec = secret ?: throw TouchpadException("缺少密钥")
                        post { listener?.onStatus("正在验证动态码…") }
                        writeLine(bOut, "TOTP ${totpCode(sec)}")
                        resp = readLine(bIn) ?: throw TouchpadException("服务器断开")
                        when {
                            resp == "OK" -> break
                            resp.startsWith("ERR locked") ->
                                throw TouchpadException(
                                    "已锁定,请 ${resp.substringAfter("ERR locked ").trim()} 秒后重试"
                                )
                            resp.startsWith("ERR bad totp") ->
                                throw TouchpadException("动态码验证失败(密钥不匹配或时间不同步)")
                            else -> throw TouchpadException("验证失败: $resp")
                        }
                    }

                    // ---- 认证通过 ----
                    val sec = secret ?: throw TouchpadException("缺少密钥")
                    if (myGen != generation.get()) return@Thread
                    sessionKey = deriveSessionKey(pin, sec)
                    seq = 0
                    connected.set(true)
                    Log.d(TAG, "认证通过,connected=true, keyLen=${sessionKey?.size}")
                }

                post { listener?.onConnected() }

                // ---- 控制模式读循环(内置掉线自动续连) ----
                readLoop()
                Log.d(TAG, "会话结束 -> onDisconnected")
                if (myGen == generation.get()) {
                    connected.set(false)
                    post { listener?.onDisconnected() }
                }
            } catch (e: TouchpadException) {
                Log.e(TAG, "连接失败(协议): ${e.message}")
                if (myGen == generation.get()) {
                    post { listener?.onError(e.message ?: "连接失败") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "连接失败(异常)", e)
                if (myGen == generation.get()) {
                    post { listener?.onError("连接失败: ${e.message}") }
                }
            } finally {
                if (myGen == generation.get()) {
                    active.set(false)
                    connected.set(false)
                    cleanup()
                }
            }
        }.start()
    }

    /** 发一条 HMAC 签名的控制帧。UI 线程调用;只算签名并入队,真正的写由后台线程完成。 */
    private fun sendSigned(cmd: String, payload: String) {
        if (!connected.get()) { Log.w(TAG, "未连接,忽略 cmd=$cmd"); return }
        val key = sessionKey
        if (key == null) { Log.w(TAG, "无 sessionKey,忽略 cmd=$cmd"); return }
        synchronized(writeLock) {
            if (!connected.get()) { return }
            seq++
            val full = "$cmd $seq $payload"
            val sig = hmacHex(key, full.toByteArray(Charsets.UTF_8))
            sendQueue.offer("$full $sig")
            Log.d(TAG, "已入队: $full")
        }
    }

    /** 相对移动(触控板模式)。 */
    fun sendMouse(dx: Int, dy: Int, wheel: Int, buttons: Int) {
        sendSigned("M", "$dx $dy $wheel $buttons")
    }

    /** 绝对坐标点击/拖动(屏幕视图模式)。 */
    fun sendAbs(x: Int, y: Int, buttons: Int) {
        sendSigned("A", "$x $y $buttons")
    }

    /** 把文本打到电脑当前焦点处。 */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        sendSigned("T", b64)
    }

    /** 开启屏幕镜像。 */
    fun startView() {
        sendSigned("V", "1")
    }

    /** 关闭屏幕镜像。 */
    fun stopView() {
        sendSigned("V", "0")
    }

    /**
     * 屏幕镜像清晰度档位:maxWidth<=0=按电脑原生分辨率(最重);fps 为镜像帧率上限;
     * quality 为电脑端 JPEG 压缩质量(50~95),越低每帧越小、越省 Wi-Fi 带宽(静止桌面本身就不发帧)。
     */
    fun setMirrorProfile(maxWidth: Int, fps: Int, quality: Int = 85) {
        sendSigned("MR", "$maxWidth $fps $quality")
    }

    /** 媒体流连接参数(电脑 IP/端口 + 认证通过的媒体 token);未认证返回 null。 */
    fun getMediaConfig(): MediaConfig? {
        val t = mediaToken ?: return null
        if (hostIp.isEmpty()) return null
        return MediaConfig(hostIp, hostPort, t)
    }

    fun submitPin(pin: String) {
        pinQueue.offer(pin)
    }

    fun submitSecret(secret: String) {
        secretQueue.offer(secret)
    }

    fun submitRecovery(recovery: String) {
        recoveryQueue.offer(recovery)
    }

    fun disconnect() {
        Log.d(TAG, "disconnect() 被调用", Throwable("调用来源"))
        generation.incrementAndGet()   // 使进行中的连接线程立即失效,不再弹框/干扰新连接
        active.set(false)
        connected.set(false)
        sessionKey = null
        mediaToken = null
        resumeToken = null   // 仅清内存;盘上加密凭证保留——下次 connect()/重启 App 在几分钟内仍免密自动连
        sendQueue.clear()   // 丢弃上一个会话未发出的残留指令,避免串到新连接
        cleanup()
    }

    /** 连接期间每 ~8 秒发一条无副作用的心跳,让半开连接尽快暴露、触发自动续连。 */
    private fun startHeartbeat() {
        if (!heartbeatStarted.getAndSet(true)) {
            Thread {
                while (true) {
                    try { Thread.sleep(HEARTBEAT_MS) } catch (e: InterruptedException) { break }
                    if (connected.get() && active.get()) {
                        sendSigned("K", "0")   // K 对电脑无副作用,只推进序号/验证链路通
                    }
                }
            }.apply { isDaemon = true }.start()
        }
    }

    /**
     * 局域网自动发现:监听电脑端 UDP 广播,找到一台就回调一次 onFound(ip, port)。
     * 扫满 timeoutMs 毫秒后回调 onDone。后台线程执行,回调在 UI 线程。
     */
    fun discover(
        timeoutMs: Long = 3000L,
        onFound: (ip: String, port: Int) -> Unit,
        onDone: () -> Unit
    ) {
        Thread {
            val seen = HashMap<String, Pair<String, Int>>()
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket(null)
                sock.reuseAddress = true
                sock.broadcast = true
                sock.bind(InetSocketAddress(DISCOVERY_PORT))
                sock.soTimeout = 500
                val buf = ByteArray(1024)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(pkt)
                        val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                        val obj = JSONObject(text)
                        if (obj.optString("app") == "phone-touchpad") {
                            val ip = pkt.address.hostAddress ?: continue
                            val port = obj.optInt("port", 9527)
                            val key = "$ip:$port"
                            if (!seen.containsKey(key)) {
                                seen[key] = ip to port
                                post { onFound(ip, port) }
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // 继续扫描直到超时
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { sock?.close() } catch (_: Exception) {}
            }
            post { onDone() }
        }.start()
    }

    private fun cleanup() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        writer = null
    }

    // ---- TOTP 种子持久化(按电脑 IP,Keystore AES-GCM 加密) ----
    private fun loadSecret(ip: String): String? {
        val enc = prefs.getString("secret_$ip", null) ?: return null
        return SecretCipher.decrypt(enc)
    }

    private fun saveSecret(ip: String, secret: String) {
        val enc = SecretCipher.encrypt(secret) ?: return
        prefs.edit().putString("secret_$ip", enc).apply()
    }

    // ---- 免密 token 持久化(按电脑 IP,Keystore AES-GCM 加密;重启 App 后几分钟内仍免密自动连) ----
    // 「先解屏」:手机锁着时免密凭证一律视为不存在、不解密不用 —— 想免密连必须先解锁手机。
    private fun isDeviceLocked(): Boolean = try {
        (appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
            ?.isDeviceLocked ?: false
    } catch (_: Exception) {
        false
    }

    private fun loadResumeToken(ip: String): String? {
        if (isDeviceLocked()) return null   // 锁屏状态下不解密 → 只能走手动配对(配对种子不受此限)
        val enc = prefs.getString("resume_$ip", null) ?: return null
        return SecretCipher.decryptResume(enc)
    }

    private fun saveResumeToken(ip: String, token: String) {
        val enc = SecretCipher.encryptResume(token) ?: return
        prefs.edit().putString("resume_$ip", enc).apply()
    }

    private fun deleteStoredResume(ip: String) {
        prefs.edit().remove("resume_$ip").apply()
    }

    /** 等待队列里出现一个值;若连接被取消则立即返回 null。 */
    private fun await(queue: LinkedBlockingQueue<String>, timeoutSec: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000
        while (active.get()) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            val v = queue.poll(minOf(remaining, 500L), TimeUnit.MILLISECONDS)
            if (v != null) return v
        }
        return null
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    private class TouchpadException(message: String) : Exception(message)

    /**
     * 用 Android Keystore 的 AES-GCM 密钥加密/解密种子。
     * 密钥只存在系统密钥库里,不导出;App 卸载后密钥随之作废(此时走恢复码)。
     */
    private object SecretCipher {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "touchpad_seed_key"            // 配对种子:不绑锁屏,保证配对/恢复流程任何状态都能解密
        private const val KEY_ALIAS_RESUME = "touchpad_resume_key"   // 免密凭证:绑定「设备解锁」(先解屏)
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LEN = 12

        private fun getOrCreateKey(alias: String, requireUnlocked: Boolean): SecretKey {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            // 系统级「先解屏」(API 28+,本工程 minSdk 30 恒满足):屏幕锁着时该密钥不可用,解密/加密都会失败
            if (requireUnlocked) spec.setUnlockedDeviceRequired(true)
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            kg.init(spec.build())
            return kg.generateKey()
        }

        private fun deleteKey(alias: String) {
            try {
                KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(alias)
            } catch (_: Exception) {
            }
        }

        private fun encryptWith(plain: String, alias: String, requireUnlocked: Boolean): String? = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias, requireUnlocked))
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }

        private fun decryptWith(enc: String, alias: String, requireUnlocked: Boolean): String? = try {
            val data = Base64.decode(enc, Base64.NO_WRAP)
            val iv = data.copyOfRange(0, IV_LEN)
            val ct = data.copyOfRange(IV_LEN, data.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(alias, requireUnlocked), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }

        // 种子:不要求解锁,保持原行为(配对/恢复任何状态都要能用)
        fun encrypt(plain: String): String? = encryptWith(plain, KEY_ALIAS, requireUnlocked = false)
        fun decrypt(enc: String): String? = decryptWith(enc, KEY_ALIAS, requireUnlocked = false)

        /**
         * 免密凭证:绑定「设备解锁」。手机锁屏 → 解密失败 → 等于凭证不存在,只能手动配对。
         * 失败若为永久性(如安全锁被关/重置把密钥作废),删掉旧密钥,下次自动重建再存,不会一直坏。
         */
        fun encryptResume(plain: String): String? {
            var r = encryptWith(plain, KEY_ALIAS_RESUME, requireUnlocked = true)
            if (r == null) {
                deleteKey(KEY_ALIAS_RESUME)   // 密钥可能已作废,重建后再试一次
                r = encryptWith(plain, KEY_ALIAS_RESUME, requireUnlocked = true)
            }
            return r
        }

        fun decryptResume(enc: String): String? {
            val r = decryptWith(enc, KEY_ALIAS_RESUME, requireUnlocked = true)
            if (r == null) deleteKey(KEY_ALIAS_RESUME)   // 锁屏/作废:清理,下次配对会自动重建
            return r
        }
    }

    companion object {
        @Volatile
        private var instance: TouchpadClient? = null

        /** 全局单例:跨 Activity 重建保持同一条连接(切后台/旋转后不断连)。 */
        fun get(context: Context): TouchpadClient =
            instance ?: synchronized(this) {
                instance ?: TouchpadClient(context.applicationContext).also { instance = it }
            }

        private const val TAG = "TouchpadClient"
        private const val PIN_TTL_SEC = 120L
        private const val SECRET_TTL_SEC = 180L  // 扫码 / 输恢复码的时限
        private const val TOTP_PERIOD = 30
        private const val TOTP_DIGITS = 6
        private const val DISCOVERY_PORT = 9528  // 与电脑端 UDP 广播端口一致
        private const val HEARTBEAT_MS = 8000L   // 控制连接心跳间隔
        private const val RESUME_RETRY_MS = 1500L  // 续连重试间隔
        private const val MAX_RESUME_TRIES = 12    // 最多试 ~18 秒,还不行才上报断开

        // ---- 二进制安全读:自定义按行/按长度读,避免 BufferedReader 预读进二进制帧 ----
        private fun writeLine(out: OutputStream, line: String) {
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString()
                if (b != '\r'.code) sb.append(b.toChar())
            }
        }

        private fun readExact(input: InputStream, n: Int): ByteArray? {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r == -1) return null
                off += r
            }
            return buf
        }

        // ---- HMAC ----
        private fun hmac(key: ByteArray, data: ByteArray, algo: String): ByteArray {
            val mac = Mac.getInstance(algo)
            mac.init(SecretKeySpec(key, algo))
            return mac.doFinal(data)
        }

        private fun hmacSha1(key: ByteArray, data: ByteArray) = hmac(key, data, "HmacSHA1")

        private fun hmacSha256(key: ByteArray, data: ByteArray) = hmac(key, data, "HmacSHA256")

        private fun hmacHex(key: ByteArray, data: ByteArray): String =
            hmacSha256(key, data).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        /** 会话密钥 = HMAC-SHA256(pin|secret, "session"),与电脑端一致。 */
        private fun deriveSessionKey(pin: String, secret: String): ByteArray {
            val material = "$pin|$secret".toByteArray(Charsets.UTF_8)
            return hmacSha256(material, "session".toByteArray(Charsets.UTF_8))
        }

        /** 免码续连的会话密钥 = HMAC-SHA256(token, "resume-session"),与电脑端一致。 */
        private fun deriveResumeSessionKey(token: String): ByteArray =
            hmacSha256(token.toByteArray(Charsets.UTF_8), "resume-session".toByteArray(Charsets.UTF_8))

        // ---- TOTP (RFC 6238, HMAC-SHA1) ----
        private fun totpCode(secret: String, period: Int = TOTP_PERIOD, digits: Int = TOTP_DIGITS): String {
            val key = secret.hexToBytes()
            val counter = System.currentTimeMillis() / 1000 / period
            val msg = ByteBuffer.allocate(8).putLong(counter).array()
            val digest = hmacSha1(key, msg)
            val offset = digest[digest.size - 1].toInt() and 0x0F
            val binary = ((digest[offset].toInt() and 0x7F) shl 24) or
                ((digest[offset + 1].toInt() and 0xFF) shl 16) or
                ((digest[offset + 2].toInt() and 0xFF) shl 8) or
                (digest[offset + 3].toInt() and 0xFF)
            return (binary % Math.pow(10.0, digits.toDouble()).toInt())
                .toString().padStart(digits, '0')
        }

        private fun String.hexToBytes(): ByteArray {
            val s = lowercase()
            val out = ByteArray(s.length / 2)
            for (i in out.indices) {
                val idx = i * 2
                out[i] = ((s[idx].digitToInt(16) shl 4) or s[idx + 1].digitToInt(16)).toByte()
            }
            return out
        }
    }
}
