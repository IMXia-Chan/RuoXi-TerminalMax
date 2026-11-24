package com.example.touchpad

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
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
 *        -> 已配对:收 TOTP_REQUIRED,本地有种子则算 TOTP;没种子则「重新配对」(发 REPAIR,换新种子)
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

/**
 * 给定本机 IP 和前缀长度,算出**该往哪些地址撒探测**(不含网络号/广播号本身)。
 *
 * 抽成纯函数是为了能单测。这里全是 32 位有符号整数的位运算,而 `192.168.x.x`
 * 在 Int 里是**负数** —— 直接用 `<` 比较之所以对,是因为有下面那条
 * `prefix < 16 就返回空` 的守卫:前缀 >= 16 时,一个网段必然是 2^16 的整数倍对齐的,
 * **跨不过 0x80000000**,网段和广播号一定同号,有符号序和无符号序结果一致。
 * 所以这不是"碰巧没事",而是前提被守着 —— 谁要是放开那条守卫,这里就会静默错,
 * 而且错的表现是「扫描找不到电脑」,不报任何错。
 *
 * 为什么 cap:`/16` 有 6 万多个地址,一口气撒出去既慢又像在扫端口。
 * 超过 [cap] 就不再往下发 —— 家庭网段不会是 /16 以下。
 * 前缀 < 16 或 >= 31 一律返回空:前者不像家庭网(顺带守住上面的前提),
 * 后者(点对点/单机)没有可撒的邻居。
 *
 * @return 主机地址(按 IP 数值升序),已被 Int 表示。
 */
internal fun hostsInSubnet(ip: Int, prefix: Int, cap: Int = 1022): List<Int> {
    if (prefix >= 31 || prefix < 16) return emptyList()
    val mask = -1 shl (32 - prefix)
    val bcast = ip or mask.inv()
    val out = ArrayList<Int>()
    var cur = (ip and mask) + 1
    while (cur < bcast && out.size < cap) {
        // 跳过自己:往自己的地址发探测会原样回到自己的 socket(同端口回环),
        // 白捡一个包。接收那边虽然有守卫,但没必要制造它。
        if (cur != ip) out.add(cur)
        cur++
    }
    return out
}

class TouchpadClient(context: Context) {

    interface Listener {
        fun onStatus(status: String)
        fun onPinRequired()      // 电脑端已弹配对码,手机端弹输入框
        fun onSecretRequired()   // 首次/恢复配对:电脑端已显示二维码,手机端扫码
        fun onRepairRequired()   // 本地没种子但电脑已配对:手机端弹「重新配对」确认
        fun onConnected()
        fun onDisconnected()
        fun onError(message: String)   // 认证/连接失败(与正常断开区分)
        fun onResumed() {}             // 掉线后自动免码续连成功(UI 不重置);默认空实现
        fun onNewMediaToken(token: String) {}  // 服务器签发新媒体 token(续连后刷新媒体流用)
        fun onFrame(jpeg: ByteArray, screenW: Int, screenH: Int) // 收到一帧屏幕画面
        fun onMediaCommand(cmd: String)  // 电脑端反向控制:切换摄像头/麦克风
        fun onCursor(x: Int, y: Int) {}  // 电脑回传的真实光标位置(镜像箭头跟着它走);默认空实现
        // ---- 文件互传(全部默认空实现,UI 按需覆盖) ----
        fun onFileStatus(msg: String) {}      // 传输状态(接收完成/失败/无法写入等),UI 用来 toast
        fun onLsResult(json: JSONObject) {}   // 电脑中转目录清单(响应手机 sendLs)
        fun onPullRequest(name: String) {}    // 电脑要手机把中转站里 name 这个文件传回电脑
        fun onFileProgress(name: String, done: Long, total: Long) {} // 下载进度(UI 驱动进度条)
        fun onTransferEnded() {}              // 一次传输结束(成功/失败都调),UI 收起进度条
        fun onPcThumb(rel: String, jpeg: ByteArray) {} // 电脑图片缩略图 JPEG(rel=相对路径)
        fun onThumbErr(msg: String) {}        // 缩略图生成失败
        fun onDelResult(ok: Boolean, msg: String) {}   // 电脑端文件删除回执(DELOK/DELERR)
        // ---- AI 助手(AiAgent 编排器用;默认空实现) ----
        fun onAiReply(json: JSONObject) {}     // 电脑端执行完一个 AI 工具后的回执(响应 sendAi)
        // ---- 手协议(电脑自述「我是谁、我会什么」;响应 sendHand) ----
        // 回包形如 {"ok":true,"hand":{"id","kind","name","version","tools",...}}
        // 内容由 HandCodec.fromHandInfo 解;`ok:false` 时带 `error`(电脑端没装 AI 模块等)。
        fun onHandReply(json: JSONObject) {}
        // ---- 键盘(「点电脑的输入框 → 手机自己弹输入法」;响应 sendHit / sendKey) ----
        // HITR 形如 {"ok":true,"editable":bool,"cursor":"IBeam","at":{...},"focus":{...}}
        // `editable` 是**判据**,别拿 `at` 当判据(见电脑端 `uia.probe` 的说明)。
        // `ok:false` 是**常态**(非 Windows / 没装 UIA / 探测失败)—— 调用方当成一次
        // 普通点击,什么都不做,不要弹键盘。
        // ⚠️ ★ 2026-10-06:今天**没有实现者** —— 「点对话框 → 手机自己弹输入法」那条路
        //   随输入条一起删了(见 [sendHit] 那段)。协议和 [onKeyReply] 那条入口照旧保留。
        fun onHitReply(json: JSONObject) {}
        // KEYR 形如 {"ok":true,"sent":bool,"error":null|"…"}。
        // ★ `sent=false` 时必须让人看见 —— 那意味着手机按了一个电脑不认的键,
        //   静默处理的话症状就是「退格键坏了」,而那根本不是坏了。
        fun onKeyReply(json: JSONObject) {}
    }

    var listener: Listener? = null

    /** 媒体流(摄像头/麦克风)连接参数。 */
    data class MediaConfig(val ip: String, val port: Int, val token: String)

    /** 文件上传连接参数(电脑 IP/端口 + 认证后签发的文件 token)。 */
    data class FileConfig(val ip: String, val port: Int, val token: String)

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
    // 文件互传 token:认证通过后签发;手机开「上传文件」独立连接凭它(与媒体 token 同理)
    @Volatile private var fileToken: String? = null

    // 免码续连:认证通过后电脑签发;掉线后凭它几分钟内静默重连,不用重输配对码
    @Volatile private var resumeToken: String? = null
    // ★ 这一次连接是**自动**发起的吗(进房间/进电脑页自己拨的),还是用户明确点的?
    //   只用来判一件事:自动拨号撞上「要输配对码」时,落下 [markAutoBlocked] 那个标记。
    //   见 [connect] 的 auto 参数和 [PcLink]。
    @Volatile private var autoAttempt = false
    private val socketLock = Any()                 // 保护 socket/writer 的换连(发送线程 vs 续连线程)
    private val heartbeatStarted = AtomicBoolean(false)

    private val pinQueue = LinkedBlockingQueue<String>()
    private val secretQueue = LinkedBlockingQueue<String>()
    private val repairQueue = LinkedBlockingQueue<Boolean>()  // 用户在弹窗上按了「重新配对」

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
     *
     * @param auto **这一次拨号是自动发起的吗。** 用户 2026-10-08 那三条(见 [PcLink] /
     *   [AutoLinkMath])里,只有「自动」那一路才需要留后手 ——
     *   自动拨号一旦撞上「要输配对码」,就在盘上落一个 [markAutoBlocked] 标记,
     *   下次**不再自动拨**,等他点(见 [AutoLinkMath] 里那段「光有 ① 掐不断风暴」)。
     *
     *   ★ **用户明确点的那一路(`auto = false`)反过来要清掉这个标记** ——
     *   他点了,意思就是「我知道可能要输码,来吧」。不清的话他手点一次、标记还挂着,
     *   下次进房间又不动了,那个标记就成了一个关不掉的锁。
     *   ★ 默认 `false` 是故意的:漏传的地方(手工调试、以后新加的调用点)
     *   走的是**不会往下留后手**的那条路 —— fail safe 的方向是「别自己动」。
     */
    fun connect(ip: String, port: Int, auto: Boolean = false) {
        disconnect()
        hostIp = ip
        hostPort = port
        autoAttempt = auto
        if (!auto) clearAutoBlocked()   // 用户自己点的:标记作废,见上面 @param
        mediaToken = null
        fileToken = null   // 新会话:服务端认证通过后会重签
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
                    CloudNet.checkHost(hostIp)   // 云端总闸：私有地址照常放行
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
                            // ★ 续连上也算「线在」——前台服务该在就得在(见 [anchorProcess])。
                            //   ColorOS 掐过服务之后能自己续上,靠的就是这一句。
                            anchorProcess()
                            // ★ 2026-10-05:续连成功也算一次「连上」,一样要重新问手
                            //   —— 中间那段断线期间**电脑可能重启过**,它的工具表
                            //   可能已经不是刚才那份了。理由同上一条。
                            sendHand()
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

            // ---- 文件下载(电脑 -> 手机)状态机:FILE_START 起、FCHUNK 写盘、FILE_END 收尾 ----
            // 服务器把 FILE_* 与镜像 FRAME 都写成「行头(+原始字节)」的原子记录(write_lock 内连写),
            // 所以本读循环逐个消费即可,天然不会把块数据和别的行搅在一起。
            var dlActive = false                     // 是否处于一次进行中的下载
            var dl: PhoneTransferStore.Created? = null
            var dlName = ""
            var dlSize = 0L
            var dlGot = 0L
            var dlLastMb = -1L                        // 上次上报进度的 MB 里程碑(降噪)
            var dlSha: MessageDigest? = null
            var dlWriteFailed = false                // 写盘失败(SAF 目录不可写等)时仍读完丢弃,保流同步

            fun abortDl() {
                if (!dlActive) return
                dlActive = false
                dlName = ""
                val created = dl
                dl = null
                dlSha = null
                dlWriteFailed = false
                val ended = dlLastMb != -1L || created != null   // 确实有过传输才通知结束
                dlLastMb = -1L
                if (created != null) {
                    try { created.out?.close() } catch (_: Exception) {}
                    PhoneTransferStore.deleteCreated(appContext, created)   // 清半截文件,不留残留
                }
                if (ended) post { listener?.onTransferEnded() }
            }

            // 控制模式读循环;服务器断开/读异常时自动续连,续不上才结束会话。
            // input/output 由 tryRecover 换新,这里是同一条线程,无并发。
            fun readLoop() {
                try {
                while (active.get() && myGen == generation.get()) {
                    val line = try {
                        val i = input
                        if (i == null) null else readLine(i)
                    } catch (e: Exception) {
                        null
                    }
                    if (line == null) {
                        Log.d(TAG, "读循环中断,尝试自动免码续连…")
                        abortDl()   // 下载进行中连接断了:先清掉半截文件
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
                        line.startsWith("FILE_TOKEN ") -> {
                            fileToken = line.substringAfter("FILE_TOKEN ").trim()
                            Log.d(TAG, "收到文件互传 token")
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
                        // ---- 文件互传(电脑 -> 手机 下载 / 电脑请求手机数据) ----
                        line.startsWith("FILE_START ") -> {
                            if (!dlActive) {
                                val p = line.split(" ")
                                val rawName = if (p.size >= 2) b64d(p[1]) ?: "" else ""
                                val sz = if (p.size >= 3) p[2].toLongOrNull() ?: -1L else -1L
                                val created = PhoneTransferStore.create(appContext, rawName)
                                dlActive = true
                                dl = created
                                dlName = created?.name ?: rawName.ifEmpty { "文件" }
                                dlSize = sz
                                dlGot = 0
                                dlLastMb = -1L
                                dlWriteFailed = false
                                dlSha = try { MessageDigest.getInstance("SHA-256") } catch (_: Exception) { null }
                                post { listener?.onFileStatus("正在接收「${dlName}」") }
                            }
                        }
                        line.startsWith("FCHUNK ") -> {
                            val len = line.substringAfter(' ').trim().toIntOrNull()
                            if (dlActive && len != null && len > 0) {
                                val bytes = input?.let { readExact(it, len) }
                                if (bytes == null) {
                                    abortDl()          // 半截断开:清残留;下一轮 line==null 会自动续连
                                } else {
                                    dlGot += bytes.size
                                    try { dlSha?.update(bytes) } catch (_: Exception) {}
                                    // 每 ~1MB 里程碑上报一次进度(驱动悬浮窗进度条)
                                    val mb = dlGot shr 20
                                    if (mb != dlLastMb) {
                                        dlLastMb = mb
                                        val nm = dlName
                                        val sz = dlSize
                                        val got = dlGot
                                        post { listener?.onFileProgress(nm, got, sz) }
                                    }
                                    val created = dl
                                    if (created?.out != null && !dlWriteFailed) {
                                        try {
                                            created.out.write(bytes)
                                        } catch (e: Exception) {
                                            Log.w(TAG, "写入手机中转站失败", e)
                                            dlWriteFailed = true
                                        }
                                    }
                                }
                            } else if (len != null && len > 0) {
                                // 无进行中的下载却有数据块(状态错位):按声明长度吃掉,保持流同步
                                input?.let { readExact(it, len) }
                            }
                        }
                        line.startsWith("FILE_END ") -> {
                            if (dlActive) {
                                val shaHex = line.substringAfter(' ').trim()
                                val created = dl
                                val sizeOk = dlSize < 0 || dlGot == dlSize
                                dlActive = false
                                dl = null
                                val digest = dlSha
                                dlSha = null
                                val shaOk = digest != null && digest.digest()
                                    .joinToString("") { "%02x".format(it) }
                                    .equals(shaHex, ignoreCase = true)
                                val ok = created?.out != null && !dlWriteFailed && sizeOk && shaOk
                                try { created?.out?.close() } catch (_: Exception) {}
                                if (ok) {
                                    post { listener?.onFileStatus("已存到手机中转站:${created?.name ?: dlName}") }
                                } else {
                                    created?.let { PhoneTransferStore.deleteCreated(appContext, it) }
                                    post { listener?.onFileStatus("接收失败(校验不一致或无法写入),已丢弃") }
                                }
                                post { listener?.onTransferEnded() }
                            }
                        }
                        line.startsWith("FILE_ERR ") -> {
                            val msg = b64d(line.substringAfter(' ').trim())
                            abortDl()
                            post { listener?.onFileStatus("传输失败:${msg ?: "未知错误"}") }
                        }
                        line.startsWith("LSR ") -> {
                            val s = b64d(line.substringAfter(' ').trim())
                            if (s != null) {
                                val obj = try { JSONObject(s) } catch (_: Exception) { null }
                                if (obj != null) post { listener?.onLsResult(obj) }
                            }
                        }
                        line.startsWith("THUMB ") -> {
                            // 电脑图片缩略图:THUMB <b64rel> <len> + len 字节 JPEG
                            val p = line.split(" ")
                            if (p.size >= 3) {
                                val rel = b64d(p[1])
                                val len = p[2].toIntOrNull()
                                if (rel != null && len != null && len > 0) {
                                    val bytes = input?.let { readExact(it, len) }
                                    if (bytes != null) post { listener?.onPcThumb(rel, bytes) }
                                }
                            }
                        }
                        line.startsWith("THUMBERR ") -> {
                            val msg = b64d(line.substringAfter(' ').trim())
                            post { listener?.onThumbErr(msg ?: "无法生成预览") }
                        }
                        line.startsWith("DELOK ") || line.startsWith("DELERR ") -> {
                            val ok = line.startsWith("DELOK ")
                            val msg = b64d(line.substringAfter(' ').trim())
                                ?: (if (ok) "已移入电脑回收站" else "删除失败")
                            post { listener?.onDelResult(ok, msg) }
                        }
                        line.startsWith("AIR ") -> {
                            // AI 工具执行回执:{"id":N,"ok":bool,"result":{...}} / {"error":".."}
                            // screenshot 的 result 里带 base64 JPEG,单行可能上百 KB。
                            val s = b64d(line.substringAfter(' ').trim())
                            if (s != null) {
                                val obj = try { JSONObject(s) } catch (_: Exception) { null }
                                if (obj != null) post { listener?.onAiReply(obj) }
                            }
                        }
                        line.startsWith("HARR ") -> {
                            // 手的自述:{"ok":bool,"hand":{...}} / {"ok":false,"error":".."}
                            // 注意别和 AI 那条混:两条都是 b64 JSON、都走 post,
                            // 但 HARR 的负载里是**能力清单**,不是某一次执行的结果。
                            val s = b64d(line.substringAfter(' ').trim())
                            if (s != null) {
                                val obj = try { JSONObject(s) } catch (_: Exception) { null }
                                if (obj != null) {
                                    // ★★ 2026-10-05:**登记在客户端做,不在 Activity 里做。**
                                    //   详见 [registerHandFromReply] 上那段 —— 一句话:
                                    //   「电脑这只手存不存在」是**进程级的事实**,不该由
                                    //   「哪个 Activity 恰好活着、恰好占着 listener」决定。
                                    registerHandFromReply(obj)
                                    post { listener?.onHandReply(obj) }
                                }
                            }
                        }
                        line.startsWith("HITR ") -> {
                            // 「刚点的那一点能不能打字」的答案:{"ok":..,"editable":..,...}
                            // ⚠️ 顺序:这两条必须放在别的 `H..` 分支**之前或之后都行**,
                            //    但**不能**被更短的公共前缀吃掉 —— "HITR " 和 "HAND"/"HARR"
                            //    都不互为前缀(第五个字符就分开了),所以这里是安全的。
                            val s = b64d(line.substringAfter(' ').trim())
                            if (s != null) {
                                val obj = try { JSONObject(s) } catch (_: Exception) { null }
                                if (obj != null) post { listener?.onHitReply(obj) }
                            }
                        }
                        line.startsWith("KEYR ") -> {
                            // 按键回执:{"ok":true,"sent":bool,"error":null|"…"}
                            val s = b64d(line.substringAfter(' ').trim())
                            if (s != null) {
                                val obj = try { JSONObject(s) } catch (_: Exception) { null }
                                if (obj != null) post { listener?.onKeyReply(obj) }
                            }
                        }
                        line.startsWith("PHLS") -> {
                            // 电脑中转站「刷新手机文件」:本地列清单后用 PHL 回传
                            Thread {
                                sendPhl(PhoneTransferStore.toJson(PhoneTransferStore.list(appContext)))
                            }.apply { isDaemon = true }.start()
                        }
                        line.startsWith("PULL ") -> {
                            val name = b64d(line.substringAfter(' ').trim())
                            if (!name.isNullOrEmpty()) {
                                post { listener?.onPullRequest(name) }
                                pullFromPhone(name)   // 自动把该文件从手机中转站传回电脑
                            }
                        }
                    }
                }
                } finally {
                    abortDl()   // 任何退出路径:清掉未完成的下载残留
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
                    // ★★ 用户 2026-10-08:自动拨号落到这一步 = 该停下来等他了。
                    //   落下这个标记之后,[PcLink] 那条自动路**不再拨**,直到他自己点一下。
                    //   没有这一句,「凭证过期 → 自动拨 → 弹配对码」这个圈会一直转:
                    //   拨号 → 弹码 → 120 秒没人理 → 超时 → 退出去 → 再进 → 又拨一遍。
                    if (autoAttempt) markAutoBlocked()
                    // ★ 2026-11-06:这几行 trace 是被一次「配对卡住、日志里什么都没有」
                    //   逼出来的 —— 连接失败只走 Log.e,而那条路不落 model.log。
                    //   配对这条路上每一步都得有字,否则下次还是只能靠猜。
                    trace("[pair] 电脑要配对码(auto=$autoAttempt),请界面弹框")
                    post { listener?.onPinRequired() }
                    val pin = await(pinQueue, PIN_TTL_SEC)
                    if (pin == null) {
                        trace("[pair] 等了 ${PIN_TTL_SEC}s 没人输入配对码")
                        throw TouchpadException("配对码输入超时")
                    }
                    trace("[pair] 收到输入的配对码(${pin.length} 位),发给电脑")
                    writeLine(bOut, "PIN $pin")
                    var resp = readLine(bIn) ?: throw TouchpadException("服务器断开")
                    trace("[pair] 电脑回了:$resp")
                    if (resp.startsWith("ERR")) throw TouchpadException("配对码错误")

                    // ---- 因素二:种子(首次配对扫码 / 已配对算 TOTP / 丢失则重新配对) ----
                    var secret = loadSecret(ip)
                    while (true) {
                        when (resp) {
                            "SETUP" -> {
                                // 首次配对 / 重新配对:电脑显示二维码,手机扫码。
                                //
                                // ★★ 2026-11-06:二维码里**不是钥匙**,只是一次性的临时公钥。
                                //   手机现场造一对临时密钥,把公钥发过去,两边各自算出同一个
                                //   共享密钥(ECDHE),电脑再用它把种子 **AES-GCM 加密**送过来。
                                //   ⇒ 就算有人把屏幕拍下来,也解不开种子。见 [PairCrypto]。
                                trace("[pair] 电脑发来配对二维码,请界面弹扫码")
                                post { listener?.onSecretRequired() }
                                val scanned = await(secretQueue, SECRET_TTL_SEC)
                                if (scanned == null) {
                                    trace("[pair] 等了 ${SECRET_TTL_SEC}s 没扫码")
                                    throw TouchpadException("扫码超时")
                                }
                                trace("[pair] 扫到 ${scanned.length} 个字符,开始协商密钥")
                                val pcPoint = try {
                                    PairCrypto.parsePayload(scanned)
                                } catch (e: IllegalArgumentException) {
                                    throw TouchpadException(e.message ?: "二维码内容不正确")
                                }
                                val pair = PairCrypto.newKeyPair()
                                val myPoint = PairCrypto.publicPoint(pair)
                                post { listener?.onStatus("正在配对…") }
                                writeLine(bOut, "KEY " + PairCrypto.encode(myPoint))
                                val seedLine = readLine(bIn)
                                    ?: throw TouchpadException("服务器断开")
                                if (!seedLine.startsWith("SEED ")) {
                                    throw TouchpadException("配对被电脑拒绝:${seedLine.take(60)}")
                                }
                                val seedBytes = try {
                                    PairCrypto.unseal(
                                        PairCrypto.deriveKey(pair.private, pcPoint, myPoint),
                                        PairCrypto.decode(seedLine.substring(5))
                                    )
                                } catch (e: Exception) {
                                    throw TouchpadException("配对失败:密钥对不上,请重新扫描电脑上的二维码")
                                }
                                val got = String(seedBytes, Charsets.US_ASCII)
                                // 解出来的东西必须长得像种子,否则就是协议对错了 ——
                                // 不能把一个乱七八糟的字符串当密钥存下来。
                                if (got.length != 32 || !got.all { it in "0123456789abcdef" }) {
                                    throw TouchpadException("配对失败:收到的种子格式不对")
                                }
                                secret = got
                                saveSecret(ip, got)
                                trace("[pair] 密钥协商成功,种子已加密领到并保存")
                            }
                            "TOTP_REQUIRED" -> {
                                if (secret == null) {
                                    // 本地没种子但电脑已配对:走「重新配对」——没有恢复码这种
                                    // 备用钥匙,和 authenticator 一样重新登记一次。
                                    // 门槛全在电脑那边:电脑要先过自己的登录 PIN 才会把新二维码
                                    // 画出来,窗口还有倒计时。
                                    post { listener?.onRepairRequired() }
                                    await(repairQueue, SECRET_TTL_SEC)
                                        ?: throw TouchpadException("重新配对确认超时")
                                    writeLine(bOut, "REPAIR")
                                    resp = readLine(bIn) ?: throw TouchpadException("服务器断开")
                                    if (resp.startsWith("ERR")) throw TouchpadException("重新配对失败")
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

                // ★★ 2026-10-05:**「连上就问一次手」改成连接自己的不变量。**
                //   原来这两句写在 [MainActivity] 的 `onConnected` / `onResumed` 里
                //   (`MainActivity.kt:241` / `:266`)。那意味着:**只有 MainActivity 活着
                //   且占着 listener 的时候,电脑那只手才会被问出来**。
                //   她的房间是桌面唯一入口,而它从来不建 MainActivity —— 于是
                //   「连上了,但 `HandRegistry` 里没有电脑那只手」,表现就是
                //   用户报的「明明是连着电脑 agent 说没有」。
                //   ★ 这条属于**连接**的语义,不属于某个界面的语义。放这儿,谁连上都算。
                //   `sendSigned` 自带 `synchronized(writeLock)`,从连接线程直接调是安全的。
                // ★ 认证成了(免密或完整配对都算)——「别再自动拨」那个标记作废。
                //   放这儿而不是放在两条路各自里面:这里是**两条路的汇合点**
                //   (见上面那段「连上就问一次手」的说明,它选这个位置是同一个理由)。
                clearAutoBlocked()
                post { listener?.onConnected() }
                // ★★ 见 [anchorProcess]:把进程锚到前台,是**连接**的不变量。
                //   写在 listener 里就变成「谁活着谁负责」—— 而她的房间从不占 listener。
                anchorProcess()
                sendHand()

                // ---- 控制模式读循环(内置掉线自动续连) ----
                readLoop()
                Log.d(TAG, "会话结束 -> onDisconnected")
                if (myGen == generation.get()) {
                    connected.set(false)
                    post { listener?.onDisconnected() }
                }
            } catch (e: TouchpadException) {
                Log.e(TAG, "连接失败(协议): ${e.message}")
                trace("[pair] 连接失败:${e.message}")
                if (myGen == generation.get()) {
                    post { listener?.onError(e.message ?: "连接失败") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "连接失败(异常)", e)
                trace("[pair] 连接失败(${e.javaClass.simpleName}):${e.message}")
                if (myGen == generation.get()) {
                    post { listener?.onError("连接失败: ${e.message}") }
                }
            } finally {
                if (myGen == generation.get()) {
                    active.set(false)
                    connected.set(false)
                    cleanup()
                    // ★ 会话真的结束了(读循环退出 / 连接失败)就撤锚 —— 见 [anchorProcess]。
                    //   ★ 这一句**必须放在这个 if 里**:代际已经被 `disconnect()` 换掉时
                    //     (用户主动断、或者马上要连新的)撤锚是那两条路自己的事,
                    //     从这儿撤会把**刚连上的新会话**的锚顺手摘掉。
                    //   ★ 它是「谁都不听也会执行」的那一份 —— 她的房间不占 listener,
                    //     不写在这儿就会留下一条谁也关不掉的常驻通知。
                    releaseProcessAnchor()
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

    /**
     * 问电脑「镜像上这一点(镜像画面坐标)是不是一个能打字的地方」。
     * 电脑回 HITR,经 [Listener.onHitReply] 回调。
     *
     * ★ 它存在的全部理由是用户 2026-10-04 的那句话:
     *   「那个键盘我想,就是**手机点击电脑对话框手机能自己跳出输入法**啊」
     *   —— 也就是说「点输入框」和「打开手机键盘」应该是**同一个动作**,
     *   而不是先去找一颗叫「键盘」的按钮(那颗按钮已经跟着抽屉删了)。
     *
     * ★ 为什么这一问必须由**电脑**回答:镜像是**像素**,输入框是**控件**。
     *   同一个长方形,在计算器上是只读显示、在聊天窗口里能打字 —— 这件事
     *   在图像上分不出来。电脑那边用指针形状 + UIA 控件树判(见 `uia.probe`)。
     *
     * ⚠️ **坐标是镜像画面坐标**(和 [sendAbs] 同一个空间),电脑端换算成真实屏幕。
     *   别在这边换算 —— 这边并不知道电脑那台屏有多大。
     *
     * ⚠️ ★★ 2026-10-06:**今天没有调用方了。** 用户当天把「直接打到电脑上」那条输入条
     *   整块删掉(原话:「**我的输入法可以直接打到直接发送**」),而这条问路**唯一**
     *   的去处就是弹出那条条子(`MainActivity` 的 `onTap → sendHit → HITR → 弹条子`)。
     *   函数和它这半套协议(`HIT` / `HITR` / [Listener.onHitReply])**故意留着** ——
     *   要恢复那条路,重新接一个监听就行,不用再查电脑端怎么写。
     *   ★ 同理 `VK_BACKSPACE` / `VK_ENTER` 也留在 `MainActivity` 的常量说明里。
     */
    fun sendHit(x: Int, y: Int) {
        val b64 = Base64.encodeToString(
            "{\"x\":$x,\"y\":$y}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        sendSigned("HIT", b64)
    }

    /**
     * 按一下电脑上的一个键,**白名单**见电脑端 `VK_KEYS`
     * (8 退格 / 9 Tab / 13 回车 / 27 Esc / 37~40 方向键 / 46 Delete)。
     * 电脑回 KEYR,经 [Listener.onKeyReply] 回调。
     *
     * ★ 为什么退格不能走 [sendText]:`type_text` 发的是 Unicode 里的**退格字符**
     *   (U+0008)。记事本会无视它,Chrome 会画一个豆腐块 —— 看着「什么都没发生」,
     *   于是这种 bug 会被记成「手机键盘坏了」。**要删字只能真的按一下退格键。**
     */
    fun sendKey(vk: Int) {
        val b64 = Base64.encodeToString(
            "{\"vk\":$vk}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        sendSigned("KEY", b64)
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

    /** 是否已认证进入控制模式(AI 编排开始前先确认通道可用)。 */
    fun isConnected(): Boolean = connected.get()

    /** 文件上传连接参数(电脑 IP/端口 + 认证通过签发的文件 token);未认证返回 null。 */
    fun getFileConfig(): FileConfig? {
        val t = fileToken ?: return null
        if (hostIp.isEmpty()) return null
        return FileConfig(hostIp, hostPort, t)
    }

    /** 请求列电脑中转目录某层(空/"."=根)。电脑回 LSR,经 onLsResult 回调。
     *  注意:根目录必须发 "." 而非空串——空 payload 会让线格式变成 "LS <seq>  <sig>"
     *  (连续两空格),服务端 split 后字段数不对、签名也重建不一致,整条会被拒。 */
    fun sendLs(relpath: String = "") {
        val r = if (relpath.isEmpty() || relpath == "/") "." else relpath
        val b64 = Base64.encodeToString(r.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        sendSigned("LS", b64)
    }

    /** 请求把电脑中转目录里的 relpath 拉到手机(存进手机中转站)。 */
    fun sendGet(relpath: String) {
        val b64 = Base64.encodeToString(relpath.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        sendSigned("GET", b64)
    }

    /** 把手机中转目录清单回传给电脑(响应电脑 PHLS)。json 形如 {"entries":[...]}。 */
    fun sendPhl(json: String) {
        val b64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        sendSigned("PHL", b64)
    }

    /** 请求电脑端生成并下发某张图片的缩略图 JPEG(列电脑目录时逐张发)。 */
    fun sendThumb(relpath: String) {
        val b64 = Base64.encodeToString(relpath.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        sendSigned("THUMB", b64)
    }

    /** 请电脑把中转目录里的某文件移入回收站(可恢复,不会真删)。 */
    fun sendDel(relpath: String) {
        val b64 = Base64.encodeToString(relpath.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        sendSigned("DEL", b64)
    }

    /**
     * 请电脑执行一个 AI 工具(打开应用/切窗口/媒体控制/截图/点击)。
     * 电脑回 AIR,经 onAiReply 回调。payload 形如 {"tool":"open_app","args":{"name":"微信"},"id":3}。
     *
     * id 由调用方(AiAgent)自己填:一次编排循环里可能并发多条工具,靠 id 对上是哪一条的回执。
     */
    fun sendAi(payload: String) {
        val b64 = Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        sendSigned("AI", b64)
    }

    /**
     * 问电脑「你是谁、你会什么」。电脑回 HARR,经 onHandReply 回调。
     *
     * 负载现在是空的 `{}`。留着它是为了这条命令和别的**同形**
     * (`CMD <seq> <负载> <签名>`)—— 以后要问「你支不支持 X」时不用改协议形状。
     *
     * ★ 这条命令存在的理由:在这之前,「电脑会什么」是**手抄在手机端 Kotlin 里**
     * 的(AiAgent.TOOL_SCHEMA)。电脑加个工具、忘了同步,模型就永远不知道它存在 ——
     * 不报错、不崩,只是能力静默少一块。现在电脑自己会说。
     */
    fun sendHand() {
        val b64 = Base64.encodeToString("{}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        sendSigned("HAND", b64)
    }

    /**
     * 电脑回了「我是谁、我会什么」→ 把这只手记进名单。
     *
     * ## ★★ 为什么这件事必须在这儿做,不能留在 Activity 里
     *
     * 这段逻辑原来长在 [MainActivity] 的 `onHandReply` 里(`MainActivity.kt:353`),
     * 而 `listener` 是**单槽**的 —— 每个 Activity 一建就把前一个换掉。
     * 于是「电脑这只手在不在名单里」这件事情,取决于
     * **「恰好是哪个 Activity 活着、恰好占着 listener」**。
     *
     * 这在 2026-10-05 之前不显眼,因为那时进 App 的门是 `MainActivity`。
     * 身份合并之后**她的房间成了桌面唯一入口**,而 `ConMarnActivity` 从不建
     * `MainActivity`、也从不碰 listener —— 于是那条路上 `HARR` 回来**没人接**,
     * 名单里永远只有手机自己。用户看到的就是:
     * **「明明是连着电脑,agent 说没有。」**
     *
     * ★ 判断这件事该放哪儿的规矩,和 [HerBoot] 那条是同一条:
     *   **「是不是连着电脑」是进程级的事实,不该挂在任何一个 Activity 的寿命上。**
     *
     * ★ 幂等:重复登记同 id 的手由 [HandMath.merged] 合并,不会摞出两条。
     * 失败一律吞掉 —— 名单是锦上添花,不该让读循环为它报错(同 [HandRegistry])。
     */
    private fun registerHandFromReply(obj: JSONObject) {
        try {
            // `ok:false` = 电脑端没装 AI 模块之类。**不是错误路径**,只是这只手
            // 什么也不会 —— 说清楚原因就够了,别假装它有(这层语义留给回执)。
            if (!obj.optBoolean("ok", false)) return
            val info = obj.optJSONObject("hand") ?: return
            val hand = HandCodec.fromHandInfo(info, System.currentTimeMillis()) ?: return
            HandRegistry.init(appContext)
            HandRegistry.upsert(hand)
            // ★★ 顺手把「这个 ip 是谁」记下来 —— 免密凭证按身份存(见 [rememberIdentity])。
            //   这是**第二条**认识身份的时机(第一条更早:扫描时的发现应答)。
            //   ★ 认出来之后,旧格式存的那个 `resume_<ip>` 会被搬到 `resume_<身份>` 下,
            //     所以升级上来的手机不用重新配对一次。
            rememberIdentity(hostIp, hand.id)
            // 对一次工具表漂移。★ `peek()` 而不是 `get()`:没人用助手的时候,
            //   不该为了对账把 AiAgent(以及它背后 2.5GB 的模型)建出来。
            AiAgentHolder.peek()?.checkToolDrift()
        } catch (_: Exception) {
        }
    }

    /**
     * 电脑请手机把中转站里的 name 传回电脑(PULL)。手机用文件 token 另开上传连接完成。
     * 上传在后台线程进行,结果经 onFileStatus 回调(已 post 到 UI 线程)。
     */
    private fun pullFromPhone(name: String) {
        val cfg = getFileConfig()
        if (cfg == null) {
            post { listener?.onFileStatus("上传通道未就绪,无法回传「$name」") }
            return
        }
        val uri = PhoneTransferStore.findUri(appContext, name)
        if (uri == null) {
            post { listener?.onFileStatus("手机中转站里没有「$name」") }
            return
        }
        val resolver = appContext.contentResolver
        val size = if (uri.scheme == "content") {
            PhoneTransferStore.sizeOf(appContext, uri)
        } else {
            runCatching { java.io.File(uri.path ?: "").length() }.getOrDefault(-1L)
        }
        FileTransfer.upload(
            ip = cfg.ip, port = cfg.port, token = cfg.token,
            name = name, knownSize = size,
            openInput = { FileTransfer.openUriStream(resolver, uri) },
            onResult = { ok, msg -> post { listener?.onFileStatus(msg) } },
        )
    }

    fun submitPin(pin: String) {
        pinQueue.offer(pin)
    }

    fun submitSecret(secret: String) {
        secretQueue.offer(secret)
    }

    /** 用户在弹窗上确认「重新配对」—— 没有输入,只是一次确认。 */
    fun submitRepair() {
        repairQueue.offer(true)
    }

    /**
     * 连接在,进程就得有前台服务锚着 —— **这是连接的语义,不是某个界面的语义**。
     *
     * ★★ 2026-10-05 用户报:「**好像退出去房间就会显示电脑断连**」。
     *
     * 查下来的账:把进程提到前面那个前台服务([KeepAliveService]),原来**只在
     * [MainActivity] 的 listener 回调里**(`onConnected` / `onResumed`)才启动。
     * 而 2026-10-04 身份合并之后**她的房间才是桌面唯一入口**,`client.listener`
     * 只有 MainActivity 会占(`MainActivity.kt:193`)—— 那条路上没有任何人去开它。
     *
     * 于是:连上了、但没有前台服务 → 进程只是个普通后台进程 → ColorOS 一冻结,
     * socket 就是死的。真机实测 `dumpsys activity services` 里 `isForeground=true`
     * 的条数是 **0**,电脑端日志里是「客户端关闭连接」,几分钟后才免码续连回来。
     *
     * 这和 [PcLink] 文件头、和 [HerBoot] 是**同一条教训的第三次**:
     *   **「线通不通」「她还在不在」这类进程级的事实,一旦挂到某个 Activity 的寿命上,
     *   它不会报错,只会静默地少一块能力。** 前两次是「连不上」和「不预热」,
     *   这次是「连着连着就没了」。
     */
    private fun anchorProcess() {
        try {
            appContext.startForegroundService(Intent(appContext, KeepAliveService::class.java))
        } catch (e: Exception) {
            // 例如后台启动前台服务不被允许(Android 12+);此时服务本来就在跑或马上要断,
            // 不是需要用户知道的事 —— 但**不能静默**,写一行日志。
            Log.w(TAG, "拉起保活服务失败:${e.message}")
        }
    }

    /** [anchorProcess] 的反面。只在**这次连接真的结束了**的时候调,没见过它单独被调。 */
    private fun releaseProcessAnchor() {
        try {
            appContext.stopService(Intent(appContext, KeepAliveService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "停保活服务失败:${e.message}")
        }
    }

    fun disconnect() {
        Log.d(TAG, "disconnect() 被调用", Throwable("调用来源"))
        // ★ 断开 = 线没了,锚也就没有意义了。放在最前面:无论下面哪一步抛,
        //   都不该留一个「连不上却还挂着常驻通知」的状态。
        releaseProcessAnchor()
        generation.incrementAndGet()   // 使进行中的连接线程立即失效,不再弹框/干扰新连接
        active.set(false)
        connected.set(false)
        sessionKey = null
        mediaToken = null
        fileToken = null
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
     * 本机所在网段里可以发探测的地址(不含网络号/广播号本身)。
     *
     * 拿 `InterfaceAddress.broadcast` + `networkPrefixLength` 算,而不是直接假设 /24 ——
     * 用户的路由器可能是 /23 甚至别的。超过 /16 就**不扫**(1022 个上限):
     * 再大就不是家庭网段了,一口气撒几万个包既慢又像在扫端口。
     * 认不出来就返回空 —— 那就只剩下广播那条路,不猜。
     */
    private fun subnetHosts(): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val addr = ia.address
                    if (addr !is Inet4Address || addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    if (ia.broadcast !is Inet4Address) continue
                    val prefix = ia.networkPrefixLength.toInt()
                    val ip = ByteBuffer.wrap(addr.address).int
                    for (h in hostsInSubnet(ip, prefix)) {
                        out.add(ByteBuffer.allocate(4).putInt(h).array())
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /**
     * 局域网自动发现:找到一台就回调一次 onFound(ip, port);扫满 timeoutMs 后回调 onDone。
     * 后台线程执行,回调在 UI 线程。
     *
     * ★ 2026-10-03:原来是**只听广播**的,而这台手机上广播基本收不到 —— 用户报的
     * 「扫描要扫两次」就是它。实测(同一端口、同一段时间,两次独立复现):
     *
     *     电脑发**单播**到本机  →  23 个,到 23 个
     *     电脑发**广播**        →  23 个,到 1 个
     *     真实广播听 22 秒(该有 11 个)  →  到 1 个
     *
     * 原因是 Android 的 Wi-Fi 芯片在省电时**过滤广播/组播帧**(要 WifiManager 的
     * MulticastLock 才能可靠接收),本 App 没有拿这个锁。3 秒窗口里该有 1~2 个广播,
     * 实际经常是 0 个 → 第一次「没找到电脑」;用户点一下、看一眼提示的工夫收音机醒了,
     * 第二次就成了。**看起来像"要扫两次",其实是一直在赌运气。**
     *
     * 所以现在改成**主动撒单播探测**:往本网段每个地址发一个小包,电脑端在同一个端口上
     * 听,谁回谁就是。单播不受那个过滤影响 —— 这条路是实测可靠的(而且已经用真机
     * 端到端验过:手机发探测 → 电脑回包,39 字节,一次到位)。
     * 广播照旧听着(同一个 socket,不额外花钱):别的机子、别的网络上是它管用,
     * 而且多台电脑时它能补充单播扫不到的段。
     *
     * 时间线(总共仍不超过 timeoutMs):撒探测和听回包**交替进行**(★ 2026-10-07 改的 ——
     * 原来是"一口气撒完才开始听",那正是「电脑连热点、手机扫描找不到电脑」的真因,
     * 详见函数体里那段注释)→ 有应答就再等 1.2 秒收齐多台 → 结束;
     * 没人应就在第 1.2 秒再补撒一轮(只丢一个包也是失败,值得补一趟)→ 等到超时。
     */
    fun discover(
        timeoutMs: Long = 3000L,
        // hand = 电脑的**身份**(id/kind/name),老版本电脑端没有这个字段时是 null。
        // ★ 注意里面**没有工具表**:那是 UDP 广播,整个网段都收得到,包也要短。
        // 能力清单走连上之后的 HAND 命令。见 HandCodec.fromDiscovery / HandMath.merged。
        onFound: (ip: String, port: Int, hand: Hand?) -> Unit,
        onDone: () -> Unit
    ) {
        Thread {
            val seen = HashMap<String, Pair<String, Int>>()
            var sock: DatagramSocket? = null
            try {
                trace("开扫 timeout=${timeoutMs}ms")
                val ds = DatagramSocket(null)
                sock = ds
                ds.reuseAddress = true
                ds.broadcast = true
                ds.bind(InetSocketAddress(DISCOVERY_PORT))
                trace("socket 已绑 :$DISCOVERY_PORT(本机 ${ds.localAddress?.hostAddress}:" +
                        "${ds.localPort})")
                val buf = ByteArray(1024)

                // 听一小段多久(毫秒)。★ 撒那一边已经没有"小段"这个概念了 ——
                // 它整个搬去了另一条线程,见下面那段长注释。
                val recvSliceMs = 100L

                // ---- 可靠的那条路:往本网段每个地址撒单播探测 ----
                val probe = "{\"app\":\"phone-touchpad\",\"probe\":true}".toByteArray(Charsets.UTF_8)

                // ★★ 2026-10-07 第二次改。**第一次改错了,这一版是量出来的。**
                //
                // 第一次(同一天早先)把"撒"和"听"改成交替:每小段最多撒 100ms、然后听 100ms。
                // 它**没生效**。真机日志三趟完全一致:
                //
                //     第 1 轮:撒到 226/253,本段耗时 3067ms
                //     结束:共 0 台;听了 0 次,收到 0 个包,走到第 1 轮,总耗时 3075ms
                //
                // ★ 那一段名义上只有 100ms,实际跑了 **3067ms** —— 正好是整个 timeout。
                //   为什么拦不住:上面那个 while 的时间条件只在**每次 ds.send() 之前**检查,
                //   而**一次 send() 会被内核堵住**:往一个没人应答的网段狂撒,会一路触发
                //   ARP 解析,socket 的发送队列满了就阻塞。**一个卡住的 send 就冲破了整段
                //   slice,一直冲到 deadline**。它一返回,left <= 0 → break →
                //   **ds.receive() 一次都没轮到**(日志里那句"听了 0 次"就是这件事)。
                //
                // ★★ 所以结论不是"片要切得更细",是**两者不能待在同一条线程上**:
                //    "听"等的那段预算是可以被无限期抢占的,而"撒"里那个阻塞调用不可中断。
                //    只要同线程,"撒"永远有机会把"听"饿死。→ **把"撒"整个挪去另一条线程。**
                //
                // 症状之所以难查,是因为它和「电脑根本没回包」**一模一样**:
                // 手机上零日志、电脑上一条探测。而电脑的日志里每条探测后面都写着
                // 「已单播应答」—— 它的回包是真的发出来了,只是躺在接收缓冲里没人取,
                // 三秒一到连 socket 一起 close。
                //
                // 顺带修掉同一类的一处:`subnetHosts()` 原来也在 deadline 之内跑,而它要
                // 枚举网卡(在 Android 上是个会走系统服务的调用)。**凡是可能慢的东西,都不
                // 许待在计时窗口里** —— 它现在挪到 deadline 之前先算好。
                //
                // 代价(老实说):撒探测不再有"保证 254 个都撒完"这回事了 ——
                // 反正它本来就撒不完,而**这不要紧**:电脑每 2 秒会自己**单播**喊一声
                // (见 pc-server/server.py 的 discovery_beacon),**光听着就够**;
                // 撒探测只是那条路不通时的备胎。
                val hosts = subnetHosts()
                // ★ 这一行当初是为了判「候选 B」(蜂窝那个 /16 会不会把 hosts 撑到六万多、
                //   让听永远轮不上)。实测回的是 **253** —— 那条路已经排除,留着当例行观测。
                val head = hosts.take(3).joinToString(",") { b ->
                    (0 until 4).joinToString(".") { i -> (b[i].toInt() and 0xff).toString() }
                }
                trace("网段共 ${hosts.size} 个地址" +
                        (if (hosts.isEmpty()) "" else ":$head" + if (hosts.size > 3) " …" else ""))
                val startedAt = System.currentTimeMillis()
                val deadline = startedAt + timeoutMs

                // 有人应了就绝不补撒第二轮(不然白打扰整个网段)。
                val gotHit = AtomicBoolean(false)

                // ★★ 「撒」自己一条线程 —— 这是这一版唯一的行为改动,理由见上面那段注释。
                //    它爱堵多久堵多久;堵死、抛异常、被 close 打断,都不影响下面那个"听"。
                Thread {
                    try {
                        var sent = 0
                        val t0 = System.currentTimeMillis()
                        for (h in hosts) {
                            if (gotHit.get()) break
                            try {
                                ds.send(DatagramPacket(probe, probe.size,
                                        InetAddress.getByAddress(h), DISCOVERY_PORT))
                                sent++
                            } catch (_: Exception) {
                                // 单个地址发不出去不算失败:那个地址可能压根没人。
                            }
                        }
                        trace("撒完第 1 轮:$sent/${hosts.size} 个,耗时 " +
                                "${System.currentTimeMillis() - t0}ms")
                        // 一轮探测 = 一趟 UDP。丢了就白扫 —— 症状和用户报的「找不到电脑」
                        // 一模一样。所以 1.2 秒还没人应,补撒一轮当保险。
                        Thread.sleep(1200)
                        if (!gotHit.get()) {
                            var sent2 = 0
                            for (h in hosts) {
                                if (gotHit.get()) break
                                try {
                                    ds.send(DatagramPacket(probe, probe.size,
                                            InetAddress.getByAddress(h), DISCOVERY_PORT))
                                    sent2++
                                } catch (_: Exception) {
                                }
                            }
                            trace("撒完第 2 轮:$sent2/${hosts.size} 个")
                        }
                    } catch (_: Throwable) {
                        // 这条线程死了不该影响扫描本身 —— 听的那条路照跑。
                    }
                }.apply { isDaemon = true }.start()

                var firstHitAt = 0L
                // ★ 这两个计数是收尾那一行的核心:听了 0 次 = 撒把听饿死了;
                //   听了 >0 次、收 0 个包 = 电脑的回包压根没到(网络那头);
                //   收到包却 seen 为空 = 被过滤器挡掉。
                var recvTries = 0
                var recvGot = 0

                // ★ 这条线程现在只干一件事:听。**完整的 timeoutMs 都是它的**,一个字节不被抢。
                while (System.currentTimeMillis() < deadline) {
                    // 应答几乎和探测同时回来 —— 已经捞到了就别让用户
                    // 盯着「扫描中…」白等满 3 秒。留 1.2 秒是为了收齐**多台**电脑。
                    if (firstHitAt > 0 && System.currentTimeMillis() - firstHitAt > 1200) break

                    // 听一小会儿。剩余时间比这一小段还短就按剩下的来,
                    // 别把总时长拖过 deadline。
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) break
                    ds.soTimeout = minOf(recvSliceMs, left).toInt()

                    val pkt = DatagramPacket(buf, buf.size)
                    recvTries++
                    try {
                        ds.receive(pkt)
                        recvGot++
                        val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                        trace("收到包 from=${pkt.address?.hostAddress}:${pkt.port} " +
                                "len=${pkt.length} 原文=${text.take(120)}")
                        val obj = JSONObject(text)
                        // ★ 只认"应答",不认"探测":探测包长着同一张脸(app 字段一样),
                        // 两台手机同时扫描时会互相把对方回成"电脑",然后连过去、
                        // 连不上,报一个查不出原因的错。多这一行就不认探测包了。
                        if (obj.optString("app") == "phone-touchpad" &&
                                !obj.optBoolean("probe", false)) {
                            val ip = pkt.address.hostAddress ?: continue
                            val port = obj.optInt("port", 9527)
                            val key = "$ip:$port"
                            if (!seen.containsKey(key)) {
                                seen[key] = ip to port
                                if (firstHitAt == 0L) firstHitAt = System.currentTimeMillis()
                                // 告诉"撒"那条线程别补第二轮了 —— 已经有人应了。
                                gotHit.set(true)
                                // 电脑的名字。老版本没有这个字段 → null,退化成只显示
                                // ip:port。**这条兼容不能省**:不能让「多了个可选字段」
                                // 变成「找不到电脑」。
                                val hand = try {
                                    HandCodec.fromDiscovery(obj, System.currentTimeMillis())
                                } catch (_: Exception) { null }
                                // ★★ **认识的第一个时机,而且它比「连上之后」更早**:
                                //   发现应答里本来就带着这台电脑的身份(`hand.id`)。
                                //   记下来之后,等会儿真去连它时就能**直接按身份读到免密凭证** ——
                                //   即使这台电脑今天的 IP 和上次不一样(开热点就会变)。
                                //   见 [rememberIdentity] 那段说明。
                                if (hand != null) rememberIdentity(ip, hand.id)
                                Log.i(TAG, "发现:$key(第 ${seen.size} 台)" +
                                        (hand?.let { ",名字「${it.name}」" } ?: ""))
                                post { onFound(ip, port, hand) }
                            }
                        } else {
                            trace("  ↑ 不是应答(probe=${obj.optBoolean("probe", false)}),丢掉")
                        }
                    } catch (_: SocketTimeoutException) {
                        // 继续扫描直到超时
                    } catch (e: Exception) {
                        // ★ 以前这里是无条件吞掉的。现在至少留下一句 —— 一个非超时的异常
                        //   (比如 pkt.address 拿不到)会让整轮扫描悄悄少听一次。
                        trace("收包出错: ${e.javaClass.simpleName} ${e.message}")
                    }
                }
                trace("结束:共 ${seen.size} 台;听了 $recvTries 次,收到 $recvGot 个包," +
                        "总耗时 ${System.currentTimeMillis() - startedAt}ms")
                Log.i(TAG, "发现结束,共 ${seen.size} 台")
            } catch (e: Exception) {
                // 别再不声不响地吞掉了:以前这里一句 catch 吞掉一切,于是
                // 「绑不上端口」也会被报成「没找到电脑」—— 让人去查 Wi-Fi 和路由器,
                // 查错方向。至少往 logcat 留一句。
                trace("发现过程出错: ${e.javaClass.simpleName} ${e.message}")
                Log.w(TAG, "发现过程出错", e)
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

    // ---- 免密 token 持久化(Keystore AES-GCM 加密;重启 App 后几分钟内仍免密自动连) ----
    // 「先解屏」:手机锁着时免密凭证一律视为不存在、不解密不用 —— 想免密连必须先解锁手机。
    private fun isDeviceLocked(): Boolean = try {
        (appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
            ?.isDeviceLocked ?: false
    } catch (_: Exception) {
        false
    }

    /** 「这台手机锁着没有」—— 给 [PcLink] / [AutoLinkMath] 那第六七条闸用。 */
    fun isLockedNow(): Boolean = isDeviceLocked()

    // ======================================================================
    // ★★ 免密凭证**按「电脑是谁」存,不再按 IP 存**(用户 2026-10-08 定的第 ② 条)
    // ======================================================================
    //
    // 病根:手机开热点的时候,电脑的地址会变(通常从 192.168.1.x 变成 192.168.43.x)。
    // 而凭证原来是 `resume_192.168.1.7` 这么存的 —— 地址一变,**那个键就再也对不上任何东西**,
    // 于是「明明配过对,它还是要我重新配」。而且它**永远不会自愈**:每次都是新 IP、每次都没凭证。
    //
    // 做法:**真正的键是电脑的身份**(`hand.id`,就是它的主机名),IP 只留一份「谁是谁」的映射。
    //   `resume_<主机名>`         ← 凭证本体(加密)
    //   `idof_<ip>`               ← 这台 ip 上次看到的是谁
    //
    // ★ 身份什么时候知道?**两个地方都能知道,而且第二个更早**:
    //   ① 连上之后电脑回的 `hand`(HARR)—— 见 [registerHandFromReply];
    //   ② ★★ **扫描时的发现应答里就带着它**(`HandCodec.fromDiscovery`)——
    //      也就是说,「刚刚连上的这一台」和「下次扫描到的那一台」,
    //      在**还没开始连**的时候就能对上号。见 [rememberIdentity] 的调用点。
    //
    // ★ 老数据怎么办:以前存在 `resume_<ip>` 下的那一份,**第一次认出身份时搬过去**
    //   (见 [rememberIdentity] 里那段迁移),搬完删旧的。所以升级上来的手机
    //   **不用重新配对一次**。读的时候两边都认(见 [loadResumeToken]),兜住搬之前那一刻。

    private fun identityOf(ip: String): String? = prefs.getString(KEY_ID_OF + ip, null)

    /** 记下「这个 ip 是谁」;顺带把旧的按 IP 存的那份凭证搬到身份键下。 */
    private fun rememberIdentity(ip: String, id: String) {
        if (ip.isBlank() || id.isBlank()) return
        val prev = identityOf(ip)
        if (prev == id) return
        val ed = prefs.edit().putString(KEY_ID_OF + ip, id)
        if (prev == null) {
            // 第一次认出这台 ip 的身份:旧格式的凭证搬个家,免得升级后要重配一次。
            prefs.getString("resume_$ip", null)?.let { legacy ->
                ed.remove("resume_$ip").putString("resume_$id", legacy)
            }
        }
        ed.apply()
    }

    /** 这台电脑有没有存下免密凭证 —— **给 [PcLink] 判「该不该自动拨」用**(第 ① 条闸)。 */
    fun hasResumeCredentialFor(ip: String): Boolean {
        identityOf(ip)?.let { if (prefs.contains("resume_$it")) return true }
        return prefs.contains("resume_$ip")
    }

    // ---- 「上一次自动拨号是以『要输配对码』告终的」那个标记 ----
    // 见 [AutoLinkMath] 里那段:光有「有没有凭证」那一闸**掐不断配对风暴**,
    // 凭证存在但过期时照样会转到配对上去。真正拦得住的是这个**记在结果上的**标记。
    // ★ 必须落盘:进程一重启就忘了的话,「退出去再进一次」又能把它刷起来。
    /** 上一次自动拨号是不是撞上了配对(撞上了就别再自动拨)。 */
    fun isAutoBlockedByPairing(): Boolean = prefs.getBoolean(KEY_AUTO_BLOCKED, false)

    /** 自动拨号撞上配对码那一刻落下。见 [connect] 里 `PIN_REQUIRED` 那一处。 */
    private fun markAutoBlocked() {
        if (!prefs.getBoolean(KEY_AUTO_BLOCKED, false)) {
            prefs.edit().putBoolean(KEY_AUTO_BLOCKED, true).apply()
        }
    }

    /** 用户明确点着连 / 任何一次认证成功 —— 标记作废。 */
    private fun clearAutoBlocked() {
        if (prefs.getBoolean(KEY_AUTO_BLOCKED, false)) {
            prefs.edit().putBoolean(KEY_AUTO_BLOCKED, false).apply()
        }
    }

    private fun loadResumeToken(ip: String): String? {
        if (isDeviceLocked()) return null   // 锁屏状态下不解密 → 只能走手动配对(配对种子不受此限)
        // 按身份存的优先;没有就退回按 IP 存的那份(迁徙还没发生 / 老数据)
        val byId = identityOf(ip)?.let { prefs.getString("resume_$it", null) }
        val enc = byId ?: prefs.getString("resume_$ip", null) ?: return null
        return SecretCipher.decryptResume(enc)
    }

    private fun saveResumeToken(ip: String, token: String) {
        val enc = SecretCipher.encryptResume(token) ?: return
        // 知道是谁就存身份键下;还不知道就先存 IP 键下,[rememberIdentity] 认出之后会搬家
        val key = identityOf(ip) ?: ip
        prefs.edit().putString("resume_$key", enc).apply()
    }

    private fun deleteStoredResume(ip: String) {
        val ed = prefs.edit().remove("resume_$ip")
        identityOf(ip)?.let { ed.remove("resume_$it") }
        ed.apply()
    }

    /** 等待队列里出现一个值;若连接被取消则立即返回 null。 */
    private fun <T : Any> await(queue: LinkedBlockingQueue<T>, timeoutSec: Long): T? {
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

    /**
     * 开发者日志出口 —— 写进 `model.log`,和 [AiAgentHolder] 给 [AiAgent] 接的是同一个口径。
     *
     * ★ 为什么不用 `Log.i`:这台机器上 **logcat 抓不到本 App 的 `Log.*`**
     * (实测结论早就写在 `ModelManager.trace` 和 `AiAgent.trace` 的注释里了)。
     * 而 [discover] 过去**只**打 `Log.*` —— 于是「扫描」这件事在全项目**唯一**一根
     * 已知瞎掉的管子上报事:用户扫了七次、电脑侧收到七条探测、手机上零日志,
     * 分不清是「没收到包」「收到了被过滤」还是「压根没轮到听」。
     * 现在这一路和别的路一样,写进那份文件里。
     *
     * 纯增量:只加日志,不改任何行为。整段可删。
     */
    private fun trace(msg: String) {
        try {
            ModelManager.get(appContext).trace(msg)
        } catch (_: Exception) {
        }
    }

    private class TouchpadException(message: String) : Exception(message)

    /**
     * 用 Android Keystore 的 AES-GCM 密钥加密/解密种子。
     * 密钥只存在系统密钥库里,不导出;App 卸载后密钥随之作废(此时在手机上点「重新配对」扫码换新种子)。
     *
     * ★ 2026-10-05:`private` → `internal`,因为第三方 API key 也要用它加密
     * ([ApiStore])。**不是**为了让外面随便加解密 —— 只是不想把同一套 AES-GCM
     * 抄第二遍(两份实现迟早会分叉,而分叉的那天是「有的 key 解得开、有的解不开」)。
     */
    internal object SecretCipher {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "touchpad_seed_key"            // 配对种子:不绑锁屏,保证配对/恢复流程任何状态都能解密
        private const val KEY_ALIAS_RESUME = "touchpad_resume_key"   // 免密凭证:绑定「设备解锁」(先解屏)

        /**
         * ★★ 第三方 API key 用**自己的**别名,不蹭配对种子那把。
         *
         * 为什么必须分开:配对种子在**重新配对流程**里会被 `deleteKey` 掉,
         * 而 API key 是**用户自己花钱买的** —— 让它跟着配对流程一起蒸发,是
         * 「我们的实现细节毁掉用户的东西」。KEYS 没了就再也解不回来
         * (Keystore 密钥不导出),所以这两个的生命周期一天都不能绑在一起。
         *
         * ⚠️ 已知取舍:`requireUnlocked = false`(和种子一致,**不绑锁屏**)。
         * 绑锁屏更安全,但她在后台干活时一锁屏就静默失效 —— 而**静默失效**
         * 正是这个项目最怕的那类故障(不报错,只是「她不会了」)。
         * 拿它换的是「锁屏时也没法用」这点收益,不划算。
         * 真被偷手机的风险由**每条用途的配额**(见 S5)兜,不靠这里。
         */
        private const val KEY_ALIAS_API = "touchpad_api_key"

        /** 加密一个第三方 API key。失败返回 null(**绝不回退成明文存储**)。 */
        fun encryptApi(plain: String): String? = encryptWith(plain, KEY_ALIAS_API, requireUnlocked = false)

        /** 解密。失败返回 null —— 调用方要把它当成「这个 key 没了」,别当成空串。 */
        fun decryptApi(enc: String): String? = decryptWith(enc, KEY_ALIAS_API, requireUnlocked = false)
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
        private const val SECRET_TTL_SEC = 180L  // 扫码 / 确认「重新配对」的时限
        private const val TOTP_PERIOD = 30
        private const val TOTP_DIGITS = 6
        private const val DISCOVERY_PORT = 9528  // 与电脑端 UDP 广播端口一致
        private const val HEARTBEAT_MS = 8000L   // 控制连接心跳间隔
        private const val RESUME_RETRY_MS = 1500L  // 续连重试间隔
        private const val MAX_RESUME_TRIES = 12    // 最多试 ~18 秒,还不行才上报断开

        // ---- 「谁是谁」的映射键:ip → 电脑身份(见 [rememberIdentity]) ----
        private const val KEY_ID_OF = "idof_"
        // ---- 「上一次自动拨号落到了配对码」那个标记(见 [isAutoBlockedByPairing]) ----
        private const val KEY_AUTO_BLOCKED = "auto_blocked_by_pairing"

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

        /** 解码文件互传协议里的 base64 文本载荷;非法返回 null。 */
        private fun b64d(s: String): String? = try {
            String(Base64.decode(s, Base64.NO_WRAP), Charsets.UTF_8)
        } catch (_: Exception) {
            null
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
