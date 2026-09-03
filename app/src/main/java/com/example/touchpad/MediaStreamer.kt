package com.example.touchpad

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 媒体流:把手机摄像头(JPEG)和麦克风(PCM)推到电脑端,让电脑把手机当「虚拟摄像头/麦克风」。
 *
 * 传输:独立 TCP 连接(复用 9527 端口),先发一行 `MEDIA <token>` 认证,之后是二进制帧:
 *   [1 字节 type][4 字节大端 length][length 字节 payload]  (type=1 视频 / type=2 音频)
 *
 * 相机采集:部分 ColorOS/MTK 机型的硬件 JPEG 只有 1080p 以上的大档,降不到 640x480,
 * 会把带宽占满导致掉帧(实测每帧 ~590KB)。所以改为 **YUV_420_888 采集 640x480 +
 * 手机端软件压 JPEG(Q75,每帧 ~30-50KB)**,既小又省带宽发热。AudioRecord 采集麦克风。
 * 权限由 MainActivity 保证。
 */
class MediaStreamer(private val context: Context) {

    var onLog: ((String) -> Unit)? = null
    var onPreview: ((Bitmap) -> Unit)? = null

    private var socket: Socket? = null
    private var out: OutputStream? = null
    private val writeLock = Any()

    @Volatile private var connected = false
    @Volatile private var wantCamera = false          // 用户是否仍想开摄像头(掉线/设备报错后自动重开)
    private val reconnecting = AtomicBoolean(false)   // 防多条线程同时抢着重连媒体 socket
    private var fpsRange: Range<Int>? = null          // 采集限帧档位(从相机支持的档里挑)
    private var ip: String = ""
    private var port: Int = 9527
    private var token: String = ""

    // ---- 相机 ----
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var captureSession: CameraCaptureSession? = null
    @Volatile private var cameraRunning = false
    @Volatile private var flipH = false              // 摄像头画面左右翻转(照镜子 / 把前置拍的书翻正)
    private var cameraFacing = CameraCharacteristics.LENS_FACING_FRONT
    private val latestNv21 = AtomicReference<ByteArray?>(null)   // 最新一帧 YUV(NV21 排列,待软件压 JPEG)
    private val latestJpeg = AtomicReference<ByteArray?>(null)   // 最新一帧已压好的 JPEG(发送 + 预览共用)
    private var frameW = 0
    private var frameH = 0
    private var videoSendThread: Thread? = null
    private var previewThread: Thread? = null

    // ---- 麦克风 ----
    private var audioRecord: AudioRecord? = null
    private var micThread: Thread? = null
    private var micRunning = false

    /** 记录媒体连接参数。真正的建连由 [ensureConnected] 懒执行,断线后会自动重建。 */
    fun configure(ip: String, port: Int, token: String) {
        if (this.ip == ip && this.port == port && this.token == token) return
        stop()
        this.ip = ip; this.port = port; this.token = token
    }

    /** 认证/续连后电脑会重新签发 token,把它同步进来;媒体正在推且已断线时立刻触发重连。 */
    fun refreshToken(newToken: String) {
        if (newToken.isNotEmpty() && newToken != token) {
            token = newToken
            if ((cameraRunning || micRunning) && !connected) {
                startReconnect()
            }
        }
    }

    /** 媒体 socket 掉线后自动重连(带退避上限);成功后正在跑的摄像头/麦克风线程会接着推。 */
    private fun startReconnect() {
        if (!connected && reconnecting.compareAndSet(false, true)) {
            Thread {
                try {
                    val deadline = System.currentTimeMillis() + MAX_RECONNECT_MS
                    while ((cameraRunning || micRunning) && !connected &&
                        System.currentTimeMillis() < deadline
                    ) {
                        if (connectNow()) break
                        Thread.sleep(RECONNECT_DELAY_MS)
                    }
                } catch (_: InterruptedException) {
                } finally {
                    reconnecting.set(false)
                }
            }.apply { isDaemon = true }.start()
        }
    }

    /** 确保媒体连接是活的;断了就重建。网络操作放到后台线程,规避主线程限制。 */
    private fun ensureConnected(): Boolean {
        if (connected && socket != null) return true
        if (ip.isEmpty() || token.isEmpty()) return false
        val ok = AtomicBoolean(false)
        val t = Thread { ok.set(connectNow()) }
        t.isDaemon = true
        t.start()
        try { t.join(3500) } catch (_: InterruptedException) {}
        return ok.get()
    }

    /** 在后台线程里真正建立媒体连接(connect + 认证)。 */
    private fun connectNow(): Boolean {
        try {
            try { socket?.close() } catch (_: Exception) {}   // 关掉可能残留的半开 socket
            val s = Socket()
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(ip, port), 3000)
            socket = s
            out = s.getOutputStream()
            writeBytes(("MEDIA $token\n").toByteArray(Charsets.UTF_8))
            val resp = readLine(s.getInputStream())
            if (resp != "MEDIA_OK") {
                log("媒体认证失败: $resp")
                closeQuietly()
                return false
            }
            connected = true
            log("媒体连接已就绪")
            return true
        } catch (e: Exception) {
            log("媒体连接失败: ${e.message}")
            closeQuietly()
            return false
        }
    }

    private fun closeQuietly() {
        connected = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        out = null
    }

    // ================= 摄像头 =================
    fun startCamera() {
        if (cameraRunning) return
        wantCamera = true
        if (!ensureConnected()) { log("媒体未连接,无法推摄像头"); return }
        openCamera()
    }

    private fun openCamera() {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        try {
            val id = pickCamera(cm)
                ?: run { log("找不到可用相机"); return }
            if (cameraThread == null) {
                cameraThread = HandlerThread("camera").also { it.start() }
                cameraHandler = Handler(cameraThread!!.looper)
            }
            val chars = cm.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            // 这类机型硬件 JPEG 没有 ≤640x480 的档(最小约 1080p),只能走 YUV 采集 + 软件压 JPEG
            val size = chooseSize(map, TARGET_W, TARGET_H, ImageFormat.YUV_420_888)
            frameW = size.width
            frameH = size.height
            fpsRange = pickFpsRange(chars, CAP_FPS)
            log("相机 ${size.width}x${size.height}@${fpsRange?.let { "≤${it.upper}fps" } ?: "auto"} (YUV+软压限帧降载)")
            imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            imageReader!!.setOnImageAvailableListener({ r -> onImage(r) }, cameraHandler)
            cm.openCamera(id, stateCallback, cameraHandler)
        } catch (e: Exception) {
            log("相机打开失败: ${e.message}")
            Log.e(TAG, "openCamera", e)
        }
    }

    fun stopCamera() {
        wantCamera = false
        cameraRunning = false
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        videoSendThread = null
        previewThread = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    /** 相机出错/断开后,清掉 session/reader(保留线程);设备指针由调用方负责 close。 */
    private fun teardownCamera() {
        cameraRunning = false
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        imageReader?.close()
        imageReader = null
        videoSendThread = null
        previewThread = null
    }

    /** 相机设备报错/被系统回收后(过热等),只要用户仍想用摄像头就自动重开。 */
    private fun scheduleReopen() {
        if (!wantCamera) return
        val h = cameraHandler ?: return
        h.removeCallbacksAndMessages(null)
        h.postDelayed({
            if (wantCamera && !cameraRunning && cameraThread != null) openCamera()
        }, REOPEN_CAMERA_DELAY_MS)
    }

    /** 切换前置/后置摄像头;开着就关掉重开,没开只切换方向。 */
    fun switchCamera() {
        cameraFacing = if (cameraFacing == CameraCharacteristics.LENS_FACING_FRONT)
            CameraCharacteristics.LENS_FACING_BACK
        else
            CameraCharacteristics.LENS_FACING_FRONT
        if (cameraRunning) {
            stopCamera()
            startCamera()
        }
    }

    /** 左右翻转(镜像)开关:开 = 画面左右颠倒。前置摄像头看书/文档时把字翻正。返回翻转后的状态。 */
    fun toggleFlip(): Boolean {
        flipH = !flipH
        return flipH
    }

    fun isFlipHorizontal(): Boolean = flipH

    private fun pickCamera(cm: CameraManager): String? {
        val ids = cm.cameraIdList
        if (ids.isEmpty()) return null
        return ids.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == cameraFacing
        } ?: ids[0]
    }

    private fun chooseSize(
        map: android.hardware.camera2.params.StreamConfigurationMap?, tw: Int, th: Int, format: Int
    ): Size {
        val sizes = map?.getOutputSizes(format) ?: return Size(tw, th)
        val budget = tw * th   // 目标像素数(≤ 它即可);挑最大的,画面小 = 编码轻 = 发热低
        return sizes.filter { it.width * it.height <= budget }.maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }   // 没有更小档就退回最小(少数机型只有大档)
            ?: Size(tw, th)
    }

    /** 从 CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES 挑:优先 ≤ cap 且尽量接近 cap,否则取最低档。 */
    private fun pickFpsRange(chars: CameraCharacteristics, cap: Int): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        val ok = ranges.filter { it.upper <= cap }
        return (if (ok.isNotEmpty()) ok.maxByOrNull { it.upper } else ranges.minByOrNull { it.upper })
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createSession()
        }
        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "相机连接被系统断开")
            try { camera.close() } catch (_: Exception) {}
            cameraDevice = null
            teardownCamera()
            scheduleReopen()
        }
        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "相机错误 error=$error")
            try { camera.close() } catch (_: Exception) {}
            cameraDevice = null
            teardownCamera()
            scheduleReopen()
        }
    }

    private fun createSession() {
        val device = cameraDevice ?: return
        val surface = imageReader?.surface ?: return
        device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    // YUV 目标没有 JPEG 质量可设(质量在软件压 JPEG 时定,见 JPEG_QUALITY)。
                    fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    // 用帧时长把帧率硬压在 CAP_FPS 以内:实测相机可能超速产出(30fps+),不压会突刺掉帧
                    try { set(CaptureRequest.SENSOR_FRAME_DURATION, NANOS_PER_SEC / CAP_FPS.toLong()) } catch (_: Exception) {}
                }
                try {
                    session.setRepeatingRequest(req.build(), null, cameraHandler)
                } catch (e: Exception) {
                    Log.e(TAG, "setRepeatingRequest", e)
                }
                cameraRunning = true
                startVideoPump()
                log("摄像头推流中…")
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                log("相机会话配置失败,稍后自动重开")
                try { cameraDevice?.close() } catch (_: Exception) {}
                cameraDevice = null
                teardownCamera()
                scheduleReopen()
            }
        }, cameraHandler)
    }

    private fun onImage(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            // 采集线程只做「取最新帧 + 拷成 NV21」(轻量 memcpy),压 JPEG 交给发送线程,
            // 避免阻塞采集造成延迟堆积。NV21 是 YuvImage 需要的输入格式。
            val nv21 = yuvToNv21(image)
            latestNv21.set(nv21)
        } catch (e: Exception) {
            Log.w(TAG, "YUV->NV21 失败", e)
        } finally {
            image.close()
        }
    }

    /** 把 YUV_420_888 的 Image 拷成 NV21 排列(Y 全平面 + VU 交错)。处理 rowStride/pixelStride。
     *  flipH 为真时左右镜像:Y/U/V 各行按相反列序写,色度块列也反转(镜像不改变 Cb/Cr 关系,
     *  所以 V/U 不互换)。预览与发送共用同一份 NV21,观感一致。不翻转时走原快路径,零开销。 */
    private fun yuvToNv21(img: android.media.Image): ByteArray {
        val w = img.width
        val h = img.height
        val out = ByteArray(w * h * 3 / 2)
        val y = img.planes[0]
        val u = img.planes[1]
        val v = img.planes[2]
        val flip = flipH

        // ---- Y 平面 ----
        val yBuf = y.buffer
        if (!flip && y.rowStride == w) {
            yBuf.get(out, 0, w * h)
        } else if (!flip) {
            var o = 0
            for (r in 0 until h) {
                yBuf.position(r * y.rowStride)
                yBuf.get(out, o, w)
                o += w
            }
        } else {
            // 镜像:每行按相反列序放
            val yRow = y.rowStride
            for (r in 0 until h) {
                yBuf.position(r * yRow)
                val row = r * w
                for (c in 0 until w) out[row + (w - 1 - c)] = yBuf.get()
            }
        }

        // ---- 色度:NV21 是每 2x2 一块 [V U] 交错 ----
        val cw = w / 2
        val ch = h / 2
        val uBuf = u.buffer
        val vBuf = v.buffer
        val uPs = u.pixelStride
        val vPs = v.pixelStride
        val uRow = u.rowStride
        val vRow = v.rowStride
        var o = w * h
        if (!flip && uPs == 1 && vPs == 1 && uRow == cw && vRow == cw) {
            // 常见快路径:I420 平面式(Y 独立 + U/V 各一平面,pixelStride=1)
            for (r in 0 until ch) {
                uBuf.position(r * cw)
                vBuf.position(r * cw)
                for (c in 0 until cw) {
                    out[o++] = vBuf.get()   // NV21:V 在前
                    out[o++] = uBuf.get()
                }
            }
        } else if (!flip) {
            // 通用路径:逐个像素按 stride 取(个别设备色度可能带 padding / pixelStride=2)
            for (r in 0 until ch) {
                for (c in 0 until cw) {
                    uBuf.position(r * uRow + c * uPs)
                    vBuf.position(r * vRow + c * vPs)
                    out[o++] = vBuf.get()
                    out[o++] = uBuf.get()
                }
            }
        } else {
            // 镜像:色度平面逐列反转(块列 c → cw-1-c),V/U 对保持 V 在前
            for (r in 0 until ch) {
                val rowBase = w * h + 2 * (r * cw)
                for (c in 0 until cw) {
                    uBuf.position(r * uRow + c * uPs)
                    vBuf.position(r * vRow + c * vPs)
                    val vv = vBuf.get()
                    val uu = uBuf.get()
                    val d = rowBase + 2 * (cw - 1 - c)
                    out[d] = vv
                    out[d + 1] = uu
                }
            }
        }
        return out
    }

    /** 用 YuvImage 把最新 NV21 压成 JPEG(发送 + 预览都用它)。 */
    private fun nv21ToJpeg(): ByteArray? {
        val nv21 = latestNv21.get() ?: return null
        if (frameW <= 0 || frameH <= 0) return null
        return try {
            val yuv = YuvImage(nv21, ImageFormat.NV21, frameW, frameH, null)
            val baos = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, frameW, frameH), JPEG_QUALITY, baos)
            baos.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "JPEG 压缩失败", e)
            null
        }
    }

    /** 启动两个独立消费者:发送线程把最新 NV21 压成 JPEG 发给电脑,预览线程解码同一份 JPEG;
     *  都只取最新、丢旧帧保低延迟。压 JPEG 是这里最重的活(640x480 约十几毫秒),所以放独立线程不挡采集。 */
    private fun startVideoPump() {
        videoSendThread = Thread {
            var lastNv: ByteArray? = null
            var lastSent: ByteArray? = null
            val slotMs = 1000L / CAP_FPS     // 每片时长;按节拍发 → 电脑端收到均匀节奏,不突刺
            var next = System.currentTimeMillis()
            while (cameraRunning) {
                val now = System.currentTimeMillis()
                if (now < next) {
                    try { Thread.sleep(next - now) } catch (_: InterruptedException) { break }
                    continue
                }
                next = now + slotMs            // 发完一片算下一片,节奏恒定;偶发编码超时会略降但不再堆积爆发
                val nv = latestNv21.get()
                if (nv == null || nv === lastNv) continue   // 相机没出新帧,等下一片即可
                lastNv = nv
                val jpeg = nv21ToJpeg()        // 软压是较重的活(640x480 ~十几 ms),放进节拍里做
                if (jpeg != null) {
                    latestJpeg.set(jpeg)
                    if (jpeg !== lastSent) {
                        lastSent = jpeg
                        writeFrame(TYPE_VIDEO, jpeg)
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        previewThread = Thread {
            var lastShown: ByteArray? = null
            while (cameraRunning) {
                val f = latestJpeg.get()
                if (f != null && f !== lastShown) {
                    lastShown = f
                    decodeAndShow(f)
                } else {
                    try { Thread.sleep(4) } catch (_: InterruptedException) { break }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    /** 把 JPEG 解码成小图(1/4 采样)交给界面预览;丢帧保新,所以不需要额外节流。 */
    private fun decodeAndShow(jpeg: ByteArray) {
        val cb = onPreview ?: return
        try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }  // 源已是小图,预览也小些即可
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            if (bmp != null) cb(bmp)
        } catch (_: Exception) {}
    }

    // ================= 麦克风 =================
    fun startMic() {
        if (micRunning) return
        if (!ensureConnected()) { log("媒体未连接,无法推麦克风"); return }
        val bufSize = AudioRecord.getMinBufferSize(
            AUDIO_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufSize <= 0) { log("麦克风不可用"); return }
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, AUDIO_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
        )
        if (audioRecord!!.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release(); audioRecord = null; log("麦克风初始化失败"); return
        }
        audioRecord!!.startRecording()
        micRunning = true
        val chunk = ByteArray(AUDIO_CHUNK)
        micThread = Thread {
            log("麦克风推流中…")
            while (micRunning) {
                val n = audioRecord?.read(chunk, 0, chunk.size) ?: break
                if (n > 0) writeFrame(TYPE_AUDIO, chunk.copyOf(n))
            }
        }.apply { isDaemon = true; start() }
    }

    fun stopMic() {
        micRunning = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        micThread = null
    }

    // ================= 传输 =================
    private fun writeFrame(type: Int, payload: ByteArray) {
        synchronized(writeLock) {
            val o = out ?: return
            val len = payload.size
            val header = byteArrayOf(
                type.toByte(),
                ((len shr 24) and 0xFF).toByte(),
                ((len shr 16) and 0xFF).toByte(),
                ((len shr 8) and 0xFF).toByte(),
                (len and 0xFF).toByte()
            )
            try {
                o.write(header)
                o.write(payload)
                o.flush()
            } catch (e: Exception) {
                Log.w(TAG, "写帧失败,触发媒体重连", e)
                connected = false   // 连接已断:交给后台自动重连,别让画面/声音干等
                startReconnect()
            }
        }
    }

    private fun writeBytes(b: ByteArray) {
        synchronized(writeLock) {
            try {
                out?.write(b)
                out?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "写失败", e)
                connected = false
                startReconnect()
            }
        }
    }

    fun stop() {
        stopCamera()
        stopMic()
        connected = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        out = null
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

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLog?.invoke(msg)
    }

    companion object {
        private const val TAG = "MediaStreamer"
        private const val TYPE_VIDEO = 1
        private const val TYPE_AUDIO = 2
        private const val AUDIO_RATE = 16000
        private const val AUDIO_CHUNK = 640           // 20ms @16kHz mono int16
        // 降载:手机端用小画面 + 限帧 + 中质量推流。发热高→系统限流→掉帧,才是「卡」的根源;
        // 稳定的低帧率小画面远比忽高忽低的大画面流畅。PC 端 OBS 预览也同步调小。
        private const val TARGET_W = 640
        private const val TARGET_H = 480
        // 实测 640x480 YUV+软压 在 MTK 这类机型上稳在 30fps(编码/sensor 上限,设 60 也到不了),就按 30 走
        private const val CAP_FPS = 30
        private const val JPEG_QUALITY = 70   // 软件压 JPEG 质量:够清晰又够小,兼顾带宽/发热/编码耗时
        private const val NANOS_PER_SEC = 1_000_000_000L
        private const val RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_MS = 15_000L
        private const val REOPEN_CAMERA_DELAY_MS = 1500L
    }
}
