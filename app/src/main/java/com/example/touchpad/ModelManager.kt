package com.example.touchpad

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque

/**
 * 把 llama-server 拉成「若息自己的子进程」—— 这是治「应用速冻」的根治手段。
 *
 * 背景:模型原本寄生在 Termux 里,而用户用 AI 时若息在前台、Termux 在后台,
 * ColorOS 的「应用速冻」30~60 秒就把 Termux 冻死,模型随之失联。改成子进程后
 * **App 前台 = 模型前台**,系统冻 App 就等于冻它自己,不会再出现「连得上但没人应」。
 *
 * 二进制从哪来:Android 10+ 只允许从 APK 的 native library 目录执行文件(app 私有
 * 目录被 SELinux W^X 标成不可执行)。所以 llama-server 那堆东西以 `lib*.so` 的名字
 * 躺在 jniLibs/arm64-v8a 里,装机时被解到 [Context.getApplicationInfo.nativeLibraryDir],
 * 运行时直接 exec 那个路径。
 *
 * HTTP 层完全不动:脑监听 127.0.0.1:[PORT],AiAgent 的 textBaseUrl 默认就指那儿。
 *
 * 管**两个**进程:
 *   - 脑(`brain.gguf`,:[PORT])文本。App 一起来就加载,[start] 之后常驻。
 *   - 眼(`eye.gguf` + `mmproj.gguf`,:[VISION_PORT])视觉定位,只服务 `click_element`。
 *     按需加载,理由见 [ensureEye]。
 */
class ModelManager private constructor(private val ctx: Context) {

    enum class State { STOPPED, MISSING_MODEL, STARTING, LOADING, READY, FAILED }

    /** 状态变化回调(只报**脑**的)。可能在任意线程被调,UI 侧自己 post 回主线程。 */
    var onState: ((State, String) -> Unit)? = null

    @Volatile
    var state: State = State.STOPPED
        private set

    @Volatile
    var lastMessage: String = ""
        private set

    // ---------------------------------------------------------------- 路径

    /** 内嵌的 llama-server 可执行文件(名字带 .so 是为了能被 PackageManager 解压出来)。 */
    private fun binary(): File =
        File(ctx.applicationInfo.nativeLibraryDir, "libllamaserver.so")

    /**
     * 模型放 App 自己的外部目录:免权限、用户能在文件管理器里看到(/Android/data/...)。
     * 2.5G 塞不进 APK,装机后单独 push 或导入。
     */
    private fun modelsDir() = File(ctx.getExternalFilesDir(null), "models")
    fun modelFile(): File = File(modelsDir(), "brain.gguf")
    fun eyeModelFile(): File = File(modelsDir(), "eye.gguf")
    fun mmprojFile(): File = File(modelsDir(), "mmproj.gguf")

    /** 嵌入模型（bge-small-zh-v1.5，约 26MB）。没导入就退回字面相似检索。 */
    fun embedModelFile(): File = File(modelsDir(), "embed.gguf")

    /**
     * 槽位快照放哪儿 —— `--slot-save-path` 指的就是这里。
     *
     * 放**外部**目录、和模型同一个卷:一份快照按 Qwen3-4B / 4889 token / K 和 V 都 q8_0
     * 估约 300MB,内部存储不一定吃得下;而且放在这儿用户能在文件管理器里看见它、
     * 想删就删 —— **删掉的后果只是下次冷启动一次,不会坏任何东西**。
     *
     * ⚠️ **这个目录必须在拉起服务端之前存在。** llama.cpp 解析 `--slot-save-path` 时会
     * `fs_is_directory()` 检查,**不是目录就直接抛异常**,症状是服务端根本起不来,
     * 报错只有一句 "not a directory"。见 [start] 与 [brainExtraArgs]。
     */
    fun slotsDir() = File(ctx.getExternalFilesDir(null), "slots")

    private fun nativeDir() = File(ctx.applicationInfo.nativeLibraryDir)

    // ---------------------------------------------------------------- 两个进程

    /**
     * 「脑」的命令行参数。**每次启动现算**,不是构造时定死的 —— 因为
     * `--slot-save-path` 只有在目录真建出来了才能带,否则服务端会因为
     * "not a directory" **直接起不来**(快照是锦上添花,不该有能力搞死主流程)。
     */
    private fun brainExtraArgs(): List<String> = buildList {
        addAll(listOf("--alias", "brain", "--jinja",
            "-c", BRAIN_N_CTX.toString(),
            "--cache-type-k", "q8_0", "--cache-type-v", "q8_0"))
        // ★ 这一句是槽位快照的**唯一开关**:不带它就 `POST /slots/{id}?action=save`
        //   直接回 501 "This server does not support slots action"。
        //   它是个运行时参数,不用重编 native。
        if (slotsDir().isDirectory) {
            add("--slot-save-path"); add(slotsDir().absolutePath)
        }
        // ⚠️ 故意**不加 `-np 1`**。手机上那 4 个槽是 llama-server 的 auto 默认
        //   (`common/arg.cpp:1402` 把 server 这个 example 的 n_parallel 改成 -1,
        //    `tools/server/server.cpp:160` 再展开成「4 个槽 **且 kv_unified = true**」),
        //   而统一 KV 意味着四个槽**共享一个 8192 的池子** —— 总量就是 8192,不是 32768。
        //   显式写 `-np 1` 只会让 kv_unified 回到默认的 false、n_ctx_seq 还是 8192:
        //   **一点内存都不省,只白丢并发**。见 memory: reading-a-defaults-chain。
    }

    private val brain = LlamaServer(
        name = "脑", port = PORT,
        extraArgs = { brainExtraArgs() },
        onLoading = { publish(State.LOADING, it) },
        onReady = { publish(State.READY, "本地模型就绪(:$PORT)") },
        onExited = { if (state == State.READY) publish(State.STOPPED, "本地模型已退出") },
        onFailed = { publish(State.FAILED, it) },
    )

    /**
     * 「眼」故意**不给**它 onState 之类的 UI 回调:它是个内部零件,用户不需要知道
     * 它在加载。加载慢只体现在那一次点击的等待上,进度由 AiAgent 直接报给对话流。
     */
    private val eye = LlamaServer(
        name = "眼", port = VISION_PORT,
        // -c 4096 够放「一张截图 + 一句提问」(截图约一千多个视觉 token)。
        // KV 也要量化:不量化的话 f16 KV 在这个尺寸上要多吃 600MB,而这台机器
        // 恰恰是内存最紧的 —— 眼和脑加起来已经 5G 上下了。
        //
        // --image-min-tokens 1024:llama.cpp 加载 Qwen-VL 时**自己打的警告** ——
        //   "Qwen-VL models require at minimum 1024 image tokens to function
        //    correctly on grounding tasks"
        // 而 click_element 做的就是 grounding(在图上找东西),正好是它点名的那种任务。
        // 1920x1080 的截图默认切出 ~2046 个视觉 token,本来就够,所以这条**今天不改行为**;
        // 它防的是另一种情况:对方电脑屏幕小(1280x720 只切出 ~900 个),那种图上定位会
        // 悄悄退化,而这里没有任何东西会报错 —— 只会「点偏了」。宁可多花一点算力,
        // 不要一个不吭声的精度损失。
        // 「眼」也走按次计算(见 [brainExtraArgs])—— 它**不带** `--slot-save-path`:
        // 眼是按需加载、闲置就卸的,每次拉起都是一张新截图,没有可复用的公共前缀。
        extraArgs = {
            listOf("--alias", "eye", "--jinja", "-c", "4096",
                "--image-min-tokens", "1024",
                "--cache-type-k", "q8_0", "--cache-type-v", "q8_0")
        },
        onLoading = { trace("眼: $it") },
        onReady = { trace("眼 就绪(:$VISION_PORT)") },
        onExited = { trace("眼 退出了") },
        onFailed = { trace("眼 失败: $it") },
    )

    /**
     * 「嵌入」—— 把一段字变成向量，只给共用记忆做检索（[AgentMemory]）。
     *
     * ★ 为什么是**第三个进程**：`--embeddings` 不只是「打开一个接口」，它同时把
     *   pooling 从生成模式切到嵌入模式 —— 带了这个开关的服务端**不再能生成**。
     *   所以不能挂在「脑」上，只能单独起一个。
     * ★ 为什么**按需拉起、闲置就收**：这台机内存紧（见 [ensureEye] 那段）。
     *   嵌入模型只有 26MB，但进程本身也要占一份；不用的时候不该占着。
     *   。代价就是第一次检索要多等几秒，之后一直在。
     */
    private val embed = LlamaServer(
        name = "嵌入", port = EMBED_PORT,
        extraArgs = {
            listOf(
                "--alias", "embed",
                "--embeddings", "--pooling", "cls",
                // bge-small-zh 的最长输入就是 512；再长服务端会截断。
                "-c", "512", "-b", "512", "-ub", "512",
                // 嵌入是一次前向，没有可预热的状态，别浪费那几秒。
                "--no-warmup",
            )
        },
        onLoading = { trace("嵌入: $it") },
        onReady = { trace("嵌入 就绪(:$EMBED_PORT)") },
        onExited = { trace("嵌入 退出了") },
        onFailed = { trace("嵌入 失败: $it") },
    )

    // ---------------------------------------------------------------- 查询

    fun isRunning(): Boolean = brain.isRunning()

    /** 二进制和模型都到位了才谈得上启动。只管**脑**——眼没导入不该拦住 App 启动。 */
    fun missingPieces(): List<String> = buildList {
        if (!binary().isFile) add("可执行文件缺失:${binary().name}")
        if (!modelFile().isFile) add("模型未导入:${modelFile().absolutePath}")
    }

    /** 眼现在在跑吗。诊断用。 */
    fun isEyeRunning(): Boolean = eye.isRunning()

    // ---------------------------------------------------------------- 启停

    /**
     * 启动本地模型。幂等 —— 已经在跑或正在启动就直接返回,不会起第二个。
     *
     * [explicit] = true 表示是用户手动点的(那是他主动要求的),失败要报得详细些;
     * 自动启动时链路还不完整(比如模型没导入)就静静地待着,别一进 App 就弹错。
     */
    fun start(explicit: Boolean = false) {
        trace("start() 被调用 explicit=$explicit 已在跑=${isRunning()}")
        if (brain.isBusy()) return

        val missing = missingPieces()
        if (missing.isNotEmpty()) {
            publish(State.MISSING_MODEL, missing.joinToString("; "))
            if (explicit) Log.w(TAG, "本地模型起不来:" + missing.joinToString("; "))
            return
        }
        // ⚠️ 必须在拉起进程**之前**把这个目录建出来,顺序反了服务端会因为
        // "not a directory" 起不来。建不出来也**不拦启动** —— [brainExtraArgs]
        // 会看到目录不在、干脆不带那个参数,快照功能本次缺席而已。
        try {
            val dir = slotsDir()
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
                trace("槽位快照目录建不出来(${dir.absolutePath}) —— 本次不做快照")
            }
        } catch (e: Exception) {
            trace("槽位快照目录建不出来(${e.javaClass.simpleName}: ${e.message}) —— 本次不做快照")
        }

        publish(State.STARTING, "正在启动本地模型…")
        brain.start(modelFile())
        // ★ 有脑在,就有人替它数时间 —— 「不用时关模型」那个开关(⚙ 里)靠这里挂上。
        //   挂在 start() 上而不是 Activity 上:她可能从气泡、通知、开机广播任意一条路来。
        BrainIdle.arm(ctx)
    }

    /** 停掉两个子进程。App 被杀时系统会连带收掉,这里主要给「设置」里的开关用。 */
    fun stop() {
        eyeIdleTimer?.cancel()
        eyeIdleTimer = null
        embedIdleTimer?.cancel()
        embedIdleTimer = null
        eye.stop()
        embed.stop()
        brain.stop()
        publish(State.STOPPED, "本地模型已停止")
    }

    /**
     * 确保「眼」在跑;已经在跑就立刻返回。
     *
     * **为什么按需加载**:眼(4B + mmproj)≈2.8G,脑 ≈2.5G。这机器实测可用内存只有
     * 1~3G,两个常驻会疯狂换页 —— 那比「慢」更糟,是**忽快忽慢**(实测过:误判
     * q8_0 KV「更慢」,其实是被并存的第二个模型拖的)。而 click_element 本来就是
     * 低频路径(能用 open_app / focus_window / media 就不该去点屏幕),为它让脑
     * 一直难受不划算。所以:要用才拉,闲置 [EYE_IDLE_MS] 就收掉。
     *
     * 代价:**久没用之后第一次点击要等加载**(实测 20~30s)。调用方务必先给用户
     * 一句提示,别让人对着转圈干等。
     *
     * @return null = 就绪;否则是给人看的原因。**不抛异常。**
     */
    fun ensureEye(timeoutMs: Long = EYE_READY_MAX_MS): String? {
        if (!binary().isFile) return "可执行文件缺失"
        val model = eyeModelFile()
        val mmproj = mmprojFile()
        if (!model.isFile) return "视觉模型未导入:${model.absolutePath}"
        if (!mmproj.isFile) return "mmproj 未导入:${mmproj.absolutePath}"

        if (!eye.isRunning()) {
            trace("拉起「眼」…")
            eye.start(model, mmproj)
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (eye.ready) {
                armEyeIdleTimer()          // 每次用都重置闲置倒计时
                return null
            }
            // 进程已经没了、而且没就绪 → 别干等到超时,直接把它的遗言报上去
            if (!eye.isBusy() && !eye.ready) return "进程退出了。${eye.tail()}"
            Thread.sleep(POLL_INTERVAL_MS)
        }
        eye.stop()
        return "加载超时(${timeoutMs / 1000}s),已收掉。${eye.tail()}"
    }

    /** 「眼」用完之后的闲置倒计时;再被 ensureEye 叫到就取消重排。 */
    private var eyeIdleTimer: java.util.Timer? = null

    private fun armEyeIdleTimer() {
        eyeIdleTimer?.cancel()
        eyeIdleTimer = java.util.Timer("ruoxi-eye-idle", true).apply {
            schedule(object : java.util.TimerTask() {
                override fun run() {
                    if (!eye.isRunning()) return
                    trace("「眼」闲置 ${EYE_IDLE_MS / 1000}s,收掉腾内存")
                    eye.stop()
                }
            }, EYE_IDLE_MS)
        }
    }

    // ---------------------------------------------------------------- 嵌入

    /** 嵌入模型到位了吗。**没到位不是错误**：共用记忆会退回字面相似。 */
    fun hasEmbedModel(): Boolean = binary().isFile && embedModelFile().isFile

    fun isEmbedReady(): Boolean = embed.ready

    /** 「嵌入」用完之后的闲置倒计时。比「眼」短：它便宜、也不像眼那样一用就是几十秒。 */
    private var embedIdleTimer: java.util.Timer? = null

    private fun armEmbedIdleTimer() {
        embedIdleTimer?.cancel()
        embedIdleTimer = java.util.Timer("ruoxi-embed-idle", true).apply {
            schedule(object : java.util.TimerTask() {
                override fun run() {
                    if (!embed.isRunning()) return
                    trace("「嵌入」闲置 ${EMBED_IDLE_MS / 1000}s,收掉")
                    embed.stop()
                }
            }, EMBED_IDLE_MS)
        }
    }

    /**
     * 确保「嵌入」在跑。已经在跑就立刻返回。
     *
     * @return null = 就绪;否则是给人看的原因。**不抛异常。**
     */
    // ★★ 2026-11-06 真机 ANR 的根因,两条:
    //   1. 日志用**自己**的锁。原来 [trace] 是 `@Synchronized`,和 [ensureEmbed] 抢的是
    //      同一个 `ModelManager` 监视器 —— 于是「等嵌入模型起来」那 30 秒里,
    //      **任何线程写一行日志都会被堵住**,包括主线程。主线程堵 5 秒 = 系统判 ANR
    //      (真机栈:`main` Blocked → HerVoice.onInit → ModelManager.trace)。
    //   2. 等嵌入起来这件事也换一把**专用**的锁:它该挡住别的「也要拉起嵌入」的线程,
    //      不该顺带挡住全进程。
    private val traceLock = Any()
    private val embedLock = Any()

    fun ensureEmbed(timeoutMs: Long = EMBED_READY_MAX_MS): String? =
        synchronized(embedLock) { ensureEmbedLocked(timeoutMs) }

    private fun ensureEmbedLocked(timeoutMs: Long): String? {
        if (!binary().isFile) return "可执行文件缺失"
        val model = embedModelFile()
        if (!model.isFile) return "嵌入模型未导入:${model.absolutePath}"
        if (!embed.isRunning()) {
            trace("拉起「嵌入」…")
            embed.start(model)
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (embed.ready) {
                armEmbedIdleTimer()
                return null
            }
            if (!embed.isBusy() && !embed.ready) return "进程退出了。${embed.tail()}"
            // ★ 被打断不该把 InterruptedException 抛给调用方 —— 它会一路逃到线程顶层,
            //   而线程顶层的异常等于**整个进程 FATAL**。
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return "被打断了"
            }
        }
        embed.stop()
        return "加载超时(${timeoutMs / 1000}s),已收掉。${embed.tail()}"
    }

    /**
     * 一段字 → 一个向量。**会阻塞**（第一次要等进程起来，之后约几十毫秒）。
     *
     * @return null = 嵌入不可用（模型没导入 / 起不来 / 请求失败）。
     *         **调用方必须能处理 null**，并且**不能把 null 的兜底向量和这里的向量混着比** ——
     *         那是两个不同的向量空间，算出来的相似度是噪声。
     */
    fun embed(text: String): FloatArray? {
        if (text.isBlank()) return null
        if (ensureEmbed() != null) return null
        return postEmbed(text)
    }

    /**
     * 只在「已经就绪」时算 —— **不会去拉起进程**。
     *
     * 共用记忆在写一条/查一次的时候用它：那里**绝不能**为了一个向量卡住几十秒，
     * 没就绪就用兜底的向量，等进程起来了再把旧条目重算一遍（见 AgentMemory.reindex）。
     */
    fun embedIfReady(text: String): FloatArray? {
        if (!embed.ready || text.isBlank()) return null
        return postEmbed(text)
    }

    private fun postEmbed(text: String): FloatArray? {
        val conn = try {
            (URL("http://127.0.0.1:$EMBED_PORT/v1/embeddings").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 3_000
                readTimeout = EMBED_READ_TIMEOUT_MS.toInt()
                setRequestProperty("Content-Type", "application/json")
            }
        } catch (e: Exception) {
            trace("嵌入 连不上:${e.javaClass.simpleName} ${e.message}")
            return null
        }
        return try {
            val body = "{\"model\":\"embed\",\"input\":" + org.json.JSONObject.quote(text) + "}"
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) {
                trace("嵌入 请求返回 ${conn.responseCode}:${conn.errorStream?.bufferedReader()?.readText()?.take(200)}")
                return null
            }
            val s = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = org.json.JSONObject(s).getJSONArray("data")
                .getJSONObject(0).getJSONArray("embedding")
            FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
        } catch (e: Exception) {
            trace("嵌入 请求失败:${e.javaClass.simpleName} ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    // ---------------------------------------------------------------- 子进程

    /**
     * 一个 llama-server 子进程。脑和眼各持一个。
     *
     * 抽出来是因为两边要踩的坑**一模一样**(nativeLibraryDir、LD_LIBRARY_PATH、
     * /health 轮询、日志尾巴),不同的只有模型文件、端口、和起来之后通知谁。
     * 抄成两份的话,下面那行「LD_LIBRARY_PATH 别删」迟早只留在其中一份里 ——
     * 另一份就再也起不来了,而且报错是「cannot locate symbol」那种要查很久的。
     */
    private inner class LlamaServer(
        private val name: String,
        private val port: Int,
        /**
         * **每次启动现算**的参数表,不是构造时定死的。
         *
         * 定死过一次,踩到的是:参数里要不要带 `--slot-save-path`,取决于那个目录
         * **此刻**建出来没有 —— 而建目录是 [ModelManager.start] 里刚做完的事。
         * 写成常量的话,「目录没建成」和「带了参数」会同时发生,而后者会让服务端
         * **起不来**(见 [slotsDir] 的注释)。
         */
        private val extraArgs: () -> List<String>,
        private val onLoading: (String) -> Unit,
        private val onReady: () -> Unit,
        private val onExited: () -> Unit,
        private val onFailed: (String) -> Unit,
    ) {
        @Volatile private var process: Process? = null
        @Volatile var ready = false
            private set

        /**
         * 「是我们自己叫停的」。
         *
         * 没有它的话,stop() 把进程干掉之后,监听线程会看见「进程退出了」然后报一条
         * FAILED —— 用户主动关掉模型,却收到一个红色的错误。区分「自己关的」和
         * 「自己死的」,两者该报的话完全不一样。
         */
        @Volatile private var stopped = false

        /**
         * 这一轮尝试的失败说明。**由 [run] 写、由 [runWithRetry] 报** ——
         * 分开是因为重试期间不能报(见 [runWithRetry] 的注释)。
         */
        private var lastFailure: String? = null

        private var worker: Thread? = null
        private val logTail = ArrayDeque<String>()
        private val logLock = Any()

        fun isRunning(): Boolean = process?.isAlive == true

        /** 在跑、或在起。**不能**用它判「就绪」——那个看 [ready]。 */
        fun isBusy(): Boolean = isRunning() || worker?.isAlive == true

        /**
         * `@Synchronized` 不是装饰:下面是「先查后动」,而查和动之间**没有**原子性。
         * 两个线程同时进来(main 的 onCreate 与 ai-agent 线程的 [AiAgent.ensureBrain])
         * 本来会各 exec 一个 —— 这两个 2.5G 模型同时驻留会疯狂换页,症状是
         * **忽快忽慢**,而不是「慢一点」(见 memory: ruoxi-embedded-llama-server)。
         * 脑和眼是两个实例,各自持锁,互不阻塞。
         */
        @Synchronized
        fun start(model: File, mmproj: File? = null) {
            if (isBusy()) return
            ready = false
            stopped = false
            synchronized(logLock) { logTail.clear() }
            worker = Thread({
                // ★ 兜底:这个线程里逃出去的异常**会直接判进程 FATAL**,不是「这个模型坏了」
                //   而是整个 App 被杀。2026-11-06 真机连崩三次就是这么来的(见 [pollSleep])。
                //   接住、记一笔、让它死在这条线程里。
                try {
                    runWithRetry(model, mmproj)
                } catch (t: Throwable) {
                    Log.e(TAG, "[$name] 模型线程异常,已隔离", t)
                    runCatching {
                        trace("[$name] 模型线程异常(${t.javaClass.simpleName}:${t.message}),已隔离")
                    }
                }
            }, "ruoxi-$name-start").apply {
                isDaemon = true
                start()
            }
        }

        /** 和 [start] 同一把锁:否则「停」和「起」交错会留下一个没人管的子进程。 */
        @Synchronized
        fun stop() {
            stopped = true
            val p = process ?: run { worker?.interrupt(); worker = null; ready = false; return }
            process = null
            ready = false
            p.destroy()
            // 给它几秒体面退出(它在 mmap 着好几个 G,直接 SIGKILL 也行,但先礼后兵)
            Thread {
                if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
            }.apply { isDaemon = true; start() }
        }

        /**
         * 起一次,死得太早就再来一次。判据和次数见 [EARLY_EXIT_MS] 那组常量。
         *
         * ★ **失败只在最后一次报** —— 重试期间连着闪两次红色 FAILED,
         *   用户会以为她坏了(而其实第二次就起来了)。日志里重试过程照写,那是诊断用的。
         */
        private fun runWithRetry(model: File, mmproj: File?) {
            var attempt = 0
            while (true) {
                lastFailure = null
                val earlyExit = run(model, mmproj)
                if (!earlyExit || stopped) {
                    if (!stopped) lastFailure?.let { onFailed(it) }
                    return
                }
                if (attempt >= MAX_EARLY_RETRY) {
                    trace("[$name] 早退了 $attempt 次还是起不来,不再重试")
                    lastFailure?.let { onFailed(it) }
                    return
                }
                attempt++
                trace(
                    "[$name] 起来不到 ${EARLY_EXIT_MS}ms 就退了 —— 多半是上一份还没放掉 :$port;" +
                        "${RETRY_DELAY_MS}ms 后重试(第 $attempt/$MAX_EARLY_RETRY 次)"
                )
                try { Thread.sleep(RETRY_DELAY_MS) } catch (_: InterruptedException) { return }
                if (stopped) return
            }
        }

        /**
         * @return true = **刚起来就退了**(值得重试);false = 跑过了 / 是我们自己停的 /
         *         是别种失败。失败说明放 [lastFailure],由 [runWithRetry] 决定报不报 ——
         *         **这里一律不报**,否则每次重试都会往界面上闪一次红。
         */
        private fun run(model: File, mmproj: File?): Boolean {
            val startedAt = System.currentTimeMillis()
            val cmd = buildList {
                add(binary().absolutePath)
                add("-m"); add(model.absolutePath)
                if (mmproj != null) { add("--mmproj"); add(mmproj.absolutePath) }
                addAll(extraArgs())
                add("--host"); add("127.0.0.1")
                add("--port"); add(port.toString())
            }
            trace("[$name] 命令: " + cmd.joinToString(" "))

            val p = try {
                ProcessBuilder(cmd).apply {
                    // ⚠️ 这行是**必须的**,不是保险 —— 别删。
                    // 现在这套库是用 NDK 自己编的(见 ruoxi-llama-bin/build_android.sh),
                    // 它们**没有 RUNPATH**,加载器只能靠 LD_LIBRARY_PATH 在同目录里找
                    // 兄弟库。少这一行就会报 "cannot locate symbol" / 找不到
                    // libggml-cpu.so 之类。**脑和眼共用这一个类就是为了不让它被漏掉一份。**
                    environment()["LD_LIBRARY_PATH"] = nativeDir().absolutePath
                    directory(model.parentFile ?: ctx.getExternalFilesDir(null)!!)
                    redirectErrorStream(true)
                }.start()
            } catch (e: Exception) {
                // exec 失败(文件不在、没有执行权限…)不是时机问题,重试也白搭 —— 直接判死
                lastFailure = "起不来:${e.javaClass.simpleName} ${e.message}"
                Log.e(TAG, "[$name] exec 失败", e)
                return false
            }
            process = p
            onLoading("$name 加载中…")

            // 把它的输出引到 logcat,并在内存里留个尾巴。加载期这行日志是唯一能看出
            // 「到底在动还是卡死了」的东西,别省。
            Thread({
                try {
                    BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                        r.lineSequence().forEach { line ->
                            Log.i(TAG, "[$name] $line")
                            synchronized(logLock) {
                                if (logTail.size >= TAIL_LINES) logTail.removeFirst()
                                logTail.addLast(line)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // 进程结束时流会断,正常
                }
            }, "ruoxi-$name-log").apply { isDaemon = true; start() }

            // 轮询就绪。llama-server 的 /health 在加载期回 503,就绪回 200。
            val deadline = System.currentTimeMillis() + READY_WAIT_MAX_MS
            while (System.currentTimeMillis() < deadline) {
                if (!p.isAlive) {
                    if (process === p) process = null
                    // 自己叫停的不算失败,别给用户报红
                    if (stopped) return false
                    lastFailure = "进程退出了(exit=${p.exitValue()})。${tail()}"
                    // ★ 活得够久才死 = 真问题;秒退 = 多半端口还被上一份占着,值得重试
                    return System.currentTimeMillis() - startedAt < EARLY_EXIT_MS
                }
                when (probeHealth()) {
                    true -> {
                        ready = true
                        onReady()
                        // 进程守着:它一退就通知,免得 UI 一直显示「就绪」
                        try { p.waitFor() } catch (_: InterruptedException) {}
                        ready = false
                        if (process === p) process = null
                        onExited()
                        return false
                    }
                    // 还在加载 / 连不上(多半还没开始监听):歇一下再看。
                    // ★★ 这两句在 2026-11-06 之前是**裸的** Thread.sleep。stop() 会
                    //   interrupt() 这条线程,中断一到就抛 InterruptedException,没人接,
                    //   从线程顶层逃出去 ⇒ 进程 FATAL EXCEPTION(真机实测连崩三次,线程名
                    //   ruoxi-嵌入-start)。「停一下模型」不该等于「杀掉 App」。
                    else -> if (!pollSleep()) {
                        if (process === p) process = null
                        return false
                    }
                }
            }
            if (process === p) process = null
            p.destroy()
            lastFailure = "等超时了(${READY_WAIT_MAX_MS / 1000}s)。${tail()}"
            return false
        }

        /**
         * 轮询之间歇一下。**被叫停时返回 false。**
         *
         * ★ 不把 `InterruptedException` 放出去是这一句存在的全部理由:放出去它就成了
         *   线程顶层异常 ⇒ 进程 FATAL EXCEPTION。见调用处那段注释。
         */
        private fun pollSleep(): Boolean = try {
            Thread.sleep(POLL_INTERVAL_MS)
            true
        } catch (_: InterruptedException) {
            false
        }

        /**
         * 探一次 /health。true=就绪,false=在加载(503),null=连不上。
         * 超时给得很短 —— 这是本机回环,正常是微秒级。
         */
        private fun probeHealth(): Boolean? = try {
            val c = (URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection)
                .apply {
                    connectTimeout = 1000
                    readTimeout = 2000
                    requestMethod = "GET"
                }
            try {
                c.responseCode == 200
            } finally {
                c.disconnect()
            }
        } catch (_: Exception) {
            null
        }

        /** 出错时把它最后几行日志带上 —— 它的问题描述都在那儿。 */
        fun tail(): String = synchronized(logLock) {
            if (logTail.isEmpty()) "没有日志输出" else "最后一行: " + logTail.last()
        }
    }

    // ---------------------------------------------------------------- 杂项

    private fun publish(s: State, msg: String) {
        state = s
        lastMessage = msg
        trace("状态 $s: $msg")
        onState?.invoke(s, msg)
    }

    /**
     * 调试探针:把过程写进 App 自己的外部目录,用 `adb shell cat` 直接读。
     * 这台机器上 logcat 抓不到本 App 的日志(实测),文件是唯一可靠的观测口径。
     * 路径:/sdcard/Android/media/<包名>/model.log —— adb 读得到,App 不用任何权限。
     */
    // ★ 2026-11-05 起扇出会让好几个线程同时写这份日志。原来只有一条线程，
    //   现在不加锁的话，缩容那一步（读全文再写回）会把别人刚追加的行吞掉。
    fun trace(msg: String) {
        // ★ 只锁日志自己。理由见 [traceLock]:原来它和 [ensureEmbed] 抢同一个监视器,
        //   「等嵌入起来」那 30 秒里全进程写日志都堵,主线程因此被判 ANR。
        synchronized(traceLock) { traceLocked(msg) }
    }

    /** [trace] 的真身。调用方必须已经持有 [traceLock]。 */
    private fun traceLocked(msg: String) {
        try {
            val dir = ctx.getExternalMediaDirs().firstOrNull() ?: return
            if (!dir.isDirectory && !dir.mkdirs()) return
            val f = File(dir, "model.log")
            // 这是**唯一**的观测口径(logcat 抓不到本 App,实测),所以不能关掉;
            // 但一次调试就能刷出好几 MB,也不能让它无限长。过线就只留尾巴 ——
            // 排查问题看的从来都是最近这几百行,前面的历史没有价值。
            if (f.length() > LOG_MAX_BYTES) {
                val keep = f.readLines(Charsets.UTF_8).takeLast(LOG_KEEP_LINES)
                f.writeText(keep.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            }
            f.appendText(
                "%tT [%s] %s%n".format(java.util.Date(), Thread.currentThread().name, msg),
                Charsets.UTF_8)
        } catch (_: Exception) {
            // 探针不能反过来把主流程搞挂
        }
    }

    companion object {
        private const val TAG = "RuoxiLlama"

        /** 和 AiAgent.Config.textBaseUrl 的默认端口对齐,改要一起改。 */
        const val PORT = 8080

        /** 和 AiAgent.Config.visionBaseUrl 的默认端口对齐,改要一起改。 */
        const val VISION_PORT = 8081

        /** 「嵌入」的端口。**没有任何外部配置指向它** —— 只由 [AgentMemory] 走本机。 */
        const val EMBED_PORT = 8082

        /**
         * 「脑」的上下文长度 —— **KV 形状的一半**。
         *
         * ★ 提成常量是为了**同一个值只有一处**:它既进 [brainExtraArgs] 的 `-c`,
         * 又进前缀快照的哈希([PrefixSnapshot.key] 的 `nCtx`)。两处各写一个字面量的话,
         * 改了一处忘了另一处,后果是**拿着按 8192 存的快照往 4096 的槽里灌** ——
         * 而那不是崩溃,是模型开始胡言乱语。
         *
         * ★★ 2026-11-06:8192 → **12288**。原因是量出来的,不是猜的:
         *   开场提示词(SYSTEM_PROMPT 9363 字 = **5339 token**)加 25 个工具表
         *   (**3876 token**)就已经 **9215**,比 8192 还大 —— 每一轮都回
         *   `exceed_context_size_error`,本地脑子等于全哑,而且**不报别的错**。
         *   日志里 23 个工具时 8815、24 个 8995、25 个 9215,一路涨上来的。
         *   12288 之后:9215 + 历史约 1200 + 生成约 600 ≈ 11000,还有一千余量。
         *   代价是 KV 多占约 300MB(Qwen3-4B / 36 层 / 8 KV 头 / q8_0,约 76KB 每 token)。
         *   ⚠️ 再往上涨就得先瘦提示词了 —— 16384 要多占 600MB,这台机只剩 1.1GB 可用。
         */
        const val BRAIN_N_CTX = 12288

        /** KV 形状的另一半(K 和 V 的量化)。理由同上,见 [BRAIN_N_CTX]。 */
        const val BRAIN_CACHE = "q8_0/q8_0"

        private const val READY_WAIT_MAX_MS = 600_000L   // 冷加载实测几十秒,上限给足
        private const val POLL_INTERVAL_MS = 1000L
        private const val TAIL_LINES = 40

        /**
         * ★★ 「刚起来就退」的门限与重试次数 —— 2026-10-04 真机上踩出来的。
         *
         * 症状:**每次 `adb install -r` 之后她的脑必挂**,日志只有一行
         * `E srv llama_server: exiting due to HTTP server error`,时间戳是启动后 **0.03 秒**。
         * 不是配置错 —— 旧的那份 llama-server 是**上一条命的孤儿**,还在占着 :8080,
         * 新的抢不到端口就直接退,而且**一次都不重试**:她从此哑掉,界面却一切正常,
         * 只能靠用户去系统设置里「强制停止」才恢复。装机现在很频繁,这就成了每次必现。
         *
         * 判据:**活了不到 [EARLY_EXIT_MS] 就死 = 时机不对,不是配置不对** → 隔
         * [RETRY_DELAY_MS] 再试,最多 [MAX_EARLY_RETRY] 次(**有界**,不会变死循环)。
         * 活过这门限才死的(比如加载到一半炸了)= 真问题,**不重试**,照原样报错。
         */
        private const val EARLY_EXIT_MS = 5_000L
        private const val RETRY_DELAY_MS = 2_500L
        private const val MAX_EARLY_RETRY = 3

        /** 「眼」的加载上限。比脑短:它是给交互用的,等太久不如报错。 */
        private const val EYE_READY_MAX_MS = 180_000L

        /** 「眼」闲置多久就收掉。权衡:留久了省一次加载,但一直占着 2.8G。 */
        private const val EYE_IDLE_MS = 5 * 60 * 1000L

        /**
         * 「嵌入」的加载上限。**比「眼」短得多**:模型才 26MB,几十秒还起不来就是真出事了,
         * 干等着只会让「查一下共用记忆」变成一次卡住整个分工的黑洞。
         */
        private const val EMBED_READY_MAX_MS = 30_000L

        /** 单次嵌入请求的读超时。一段 300 字的嵌入在 ARM 上是几十毫秒级。 */
        private const val EMBED_READ_TIMEOUT_MS = 15_000L

        /** 「嵌入」闲置多久收掉。比「眼」短:它便宜,重新拉起也快。 */
        private const val EMBED_IDLE_MS = 3 * 60 * 1000L

        /** model.log 超过这个大小就裁到只剩尾巴,见 [trace]。 */
        private const val LOG_MAX_BYTES = 512L * 1024
        private const val LOG_KEEP_LINES = 1500

        /**
         * 进程级单例。**必须是单例**:Activity 因旋转/主题变化重建时会重新走 onCreate,
         * 若每次 new 一个,第二个实例会再 exec 一个 llama-server 去抢 8080 —— 一个绑得上、
         * 一个绑不上,表现成「有时好有时坏」。子进程是进程级的,管理器也得是。
         */
        @Volatile
        private var instance: ModelManager? = null

        fun get(ctx: Context): ModelManager =
            instance ?: synchronized(this) {
                instance ?: ModelManager(ctx.applicationContext).also { instance = it }
            }
    }
}
