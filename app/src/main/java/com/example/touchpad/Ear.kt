package com.example.touchpad

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.VersionInfo
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * **她的耳朵** —— 内嵌 sherpa-onnx 的语音识别。点一下,说一句,她听得见。
 *
 * ## 为什么非得内嵌
 *
 * 这台 ColorOS 上**一个 `android.speech.RecognitionService` 都没有**
 * (装它之前 `SpeechRecognizer.isRecognitionAvailable()` 恒 false)。小布和百度输入法都装了,
 * 但都不对外暴露识别接口 —— 这不是设置问题,是机器上根本没有那个服务。
 * 所以唯一的出路是把识别引擎**搬进 APK**,和已经内嵌的 llama.cpp 同一个套路。
 *
 * 引擎选 sherpa-onnx(Apache-2.0):一套库同时给 VAD + ASR + 唤醒词,全离线,
 * 音频**一个字节都不出这台手机**。识别模型是 SenseVoice int8(中英日韩粤混说),
 * 放在 `getExternalFilesDir(null)/asr/` —— **不打进 APK**,和 `models/` 一个惯例。
 *
 * ## 一次 `listen()` 的完整形状
 *
 * ```
 * 点 🎤 ─→ 占麦(MicGate)─→ AudioRecord ─┐
 *                                        │ 每 20ms 一块
 *                              ┌─────────┘
 *                              ▼
 *                        Silero VAD 断句
 *                              │ 说完停 0.6 秒
 *                              ▼
 *                        SenseVoice 转写
 *                              │
 *                     EarMath.clean(丢掉幻觉/标点/超长)
 *                              │
 *                     UserLexicon.correct(他自己纠正过的同音字)
 *                              ▼
 *                        「打开微信」
 * ```
 *
 * ## ★★ 这个类里每一个分支防的都是同一件事
 *
 * 识别这条链**一个异常都不会抛**。「模型没装」「VAD 没断句」「麦克风被推流占着」
 * 「转写出来是幻觉被我丢了」「他其实根本没说话」—— 这五种完全不同的故障,
 * 不做任何处理的话,表现**一模一样**:她没反应。
 *
 * 所以规矩是:**每一条不往下走的路,都必须往 `耳:` 日志里写一句说得出原因的话,
 * 并且往 [Callback.onNotice] 递一句人话。** `grep 耳: model.log` 要能还原出
 * 「这一次到底卡在哪一步」,而不是只看到一条沉默的流水账。
 */
object Ear {

    /** 日志前缀。查一次耳朵只 grep 这一个词。 */
    private const val TAG = "耳:"

    /**
     * 开麦之后多久没人出声就收工。
     *
     * ★★ 2026-10-04 晚从 **5000 改成 45000**。用户原话:
     * > 「我手按住字幕,和**说完话后 45 秒**,其他不要开麦克风;
     * >   我手没按字幕,如果说了话,就完整过一遍,要是没检测到说话,就自动关麦克风」
     *
     * 这句话把开麦的形状定死了:**她要说的那 45 秒是一次连续的开窗**,
     * 不是「每 5 秒重开一次、一共试九次」。
     * 两者看起来很像(都是 45 秒),但**手感完全不同**:
     *   · 连续:你说到第 30 秒它还在听,中间随便停顿;
     *   · 轮询:每 5 秒断一次,停顿正好落在断口上,那一句就在**他面前丢掉**,
     *     而他看到的是「我说了,她没听见」—— 这正是他这次抱怨的原话。
     * (旧值 5 秒是给「点错了要干等」准备的,那件事现在由 45 秒**结束会话**管。)
     */
    private const val IDLE_GIVEUP_MS = 45_000L

    /**
     * 一次 `listen()` 的总上限。**必须大于 [IDLE_GIVEUP_MS]** ——
     * 不然那 45 秒会在半路被这个上限拦腰截断,而截断的日志长得像「他到时间没说话」。
     */
    private const val LISTEN_MAX_MS = 50_000L

    /** 轮询队列的节拍。20ms 一块,50ms 转一圈足够跟得上,又不会空转烧电。 */
    private const val POLL_MS = 50L

    // ── 实时字幕(2026-10-04 晚他要的:「为什么我自己说话没有字幕?」)

    /**
     * 攒够这么多音频才值得解一次。
     *
     * ★ 不到 400ms 的音频 SenseVoice 解出来多半是残的,字幕会一个字一个字乱跳 ——
     *   那不是「实时」,那是抽搐。
     */
    private const val PARTIAL_MIN_MS = 400L

    /**
     * 两次实时解码之间的最小间隔,同时也是**占空比的上限**。
     *
     * ★★ 这个常数是**为热写的,不是为性能写的**。
     *
     *   SenseVoice 是**非流式**模型:它没有「接着上次往下听」这回事,
     *   每一次都得把**已经攒下的整段**从头解一遍。所以「实时字幕」在这里
     *   只能是**反复重解一个越来越长的缓冲区** —— 说得越久,每一次越贵。
     *
     *   不设上限的话,一段十秒的话会让 CPU 从现在开始一直满载,而这台机器的
     *   热降频是会把整个脑一起拖下水的(见 `phone-heat-cost-of-battery-runs`)。
     *   所以下一次解码至少等上一次耗时那么久 —— 占空比压到 1/2 以下,
     *   而字幕照样每 0.6~1.3 秒往前走一次,看起来就是连续的。
     *
     * ★★ 2026-10-05 更正:**原来写的是「上一次耗时的两倍」(占空比 1/3),
     *   而那个数字把用户要的东西直接吃掉了。**
     *   他的原话:「我说话是**变说变有字**在透明磨砂玻璃上的,**不要说完才能一起显示**」。
     *   真机日志(10:47:05、10:48:07)把旧参数的后果量得很清楚 ——
     *   一句两三秒的话**只出得来 1~2 版**实时字幕,而最后一版经常和「松手」撞在同一秒里:
     *   ```
     *   10:48:07 耳: 实时字幕开工:「怎么？」(解码 623ms)
     *   10:48:09 耳: 松手了,当场收尾
     *   10:48:09 耳: 录音结束:1 段 / 2810 ms(…实时字幕 2 次)
     *   ```
     *   **「2 次」在用户眼里就是「说完才一起显示」** —— 他要的是**字跟着嘴长**。
     *   而两倍这个余量的代价(CPU 从 1/3 占到 1/2)只在**他按住说话的那几秒**里发生,
     *   不是常驻 —— 拿它换「看得见她在听」是划算的。
     *   ⚠️ 热的那条约束**没有取消**:上限仍然由 `max(450ms, 上次耗时)` 兜着,
     *   而且它只在按住说话时活着。要再放宽请先量温(见 `phone-heat-cost-of-battery-runs`)。
     */
    private const val PARTIAL_EVERY_MS = 450L

    private const val VAD_FILE = "silero_vad.onnx"
    private const val ASR_FILE = "model.int8.onnx"
    private const val TOKENS_FILE = "tokens.txt"

    // ------------------------------------------------------------------ 状态

    @Volatile private var nativeChecked = false
    @Volatile private var recognizer: OfflineRecognizer? = null

    /**
     * 「**别人正在装识别模型**」—— 装完那一刻 `countDown`。
     *
     * ★★ 从 `@Volatile var loading: Boolean` 改过来的。原来那个布尔在
     *   [warmUp] 出现之前够用,因为**只有一个调用者**(耳朵线程自己)。
     *   现在有第二个(房间一打开就预热),而 `if (loading) return null` 在那个形状下
     *   会当场变成一条**假的失败**:他手指一按就撞上预热,耳朵回一句
     *   「识别器起不来」—— 一次都没坏过的机器上冒出一条错误。
     *
     *   现在改成:**等它**。等 3 秒和「起不来」是两回事,而后者会让他去查一个
     *   根本不存在的故障(见 [warmUp] 那段真机日志)。
     */
    @Volatile private var loadLatch: java.util.concurrent.CountDownLatch? = null

    private val loadLock = Any()

    @Volatile private var listening = false

    /**
     * 这一轮**线程还在跑**。★ 和 [listening] 不是一件事:取消之后 [listening] 立刻变 false,
     * 而线程要等自己的循环醒来(最多一次 `poll`,约 50ms)才收尾。这段窗口里如果放
     * 第二轮进来,就会有两个 [EarMic] 同时用**同一个 owner** 占麦 —— 而 [MicGate]
     * 对同一个 owner 是**幂等**的:`acquire` 第二次直接返回 true,`release` 一次就把门
     * 全放开。于是第二轮在「门开着」的状态下裸奔,唤醒词可以随时把麦抢走。
     *
     * ★★ 2026-11-08(体检)加。取消后立刻再点一下必现,而且症状是「第二次说话没反应」。
     */
    @Volatile private var sessionActive = false

    /** 轮次号:旧线程收尾时只许收拾**它自己那一轮**的状态。下发见 [listen]。 */
    private val sessionGen = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 「我说完了,别再等了」—— 按住说话松手时置上。
     *
     * ★ 和 [listening] 是**两件事**,别合并:
     *   [listening] = false 是**取消**(把已经收的都扔掉,见 `run` 里那句 `if (!listening) return`);
     *   这个是**收尾**(把手上这段交出去转写)。
     *   混成一个的话,「松手」要么变成「白说了」,要么「打断她」会把半句话发进脑子。
     */
    @Volatile private var finishRequested = false

    /** 上一次没能开工的原因(没装模型 / 被占 / 打不开麦)。界面要能拿到去说实话。 */
    @Volatile var lastNotice: String? = null
        private set

    /**
     * 一次听音的回调。**顺序是死的:[onHeard] 或 [onNotice] 二选一,然后 [onDone] 一次。**
     * 调用方只管在 [onDone] 里把界面恢复原状 —— 不必在每个分支里各恢复一遍,
     * 那种写法漏一个分支的后果是 🎤 永远转圈。
     */
    interface Callback {
        /**
         * 他**正在说的那半句**。会来很多次,每次都是「到目前为止听到的全貌」。
         *
         * ★ 这不是「最终结果」,拿它去当命令发出去是错的 ——
         *   它只配贴在字幕上让他看见「我在听了、我听到的是这些」。
         *   真正送进脑子的永远是 [onHeard]。
         */
        fun onPartial(text: String)
        /**
         * ★★ **他刚开口** —— VAD 第一次在这一轮里探到人声,**一个字的识别都还没有**。
         *
         * 它和 [onPartial] 是两件不同的事,而且这一件**早得多**:实测一次局部解码要
         * 1.6~1.7 秒,而 VAD 探到人声只要几十毫秒。用户要的那个「她到底有没有听到我说话」,
         * 答案必须在**他还在说**的时候就出来 —— 等解码的话,一句两秒的话会在
         * 他松手那一刻才等到,屏幕上已经是「在认你说的话…」了
         * (真机日志:09:54:38 松手和第一版解码是**同一个 100 毫秒**)。
         *
         * ★ 它说的是「**我听见你在说了**」,不是「我听懂你说的是什么」。
         *   后者要等 [onHeard] 或 [onPartial]。**别把这两件事混成一件** ——
         *   混了的代价是界面替麦克风撒谎,而那正是这一路上最难查的一类账。
         *
         * ★ 默认空实现:在这条之前就写好的实现不该因为它编译不过。
         */
        fun onSpeechStart() {}
        /** 听到一句干净的、已经过词库纠正的话。 */
        fun onHeard(text: String)
        /** 没听见 / 被占着 / 模型没装 —— **原因写成人话**,直接可以给用户看。 */
        fun onNotice(msg: String)
        /** 无论如何都会调到,且只调一次。 */
        fun onDone()
    }

    // ------------------------------------------------------------------ 模型在不在

    private fun asrDir(ctx: Context) = File(ctx.getExternalFilesDir(null), "asr")

    /**
     * 找出模型文件到底在哪个目录。
     *
     * `asr/` 下面**平铺**是最省事的形状,但 `adb push` 一个解包出来的目录
     * (里面自带一层 `sherpa-onnx-sense-voice-…/`)是最自然的动作 ——
     * 所以两层都认:**先看 `asr/` 本身,再逐个子目录找凑齐三个文件的那个**。
     *
     * ★ 找不到时**返回 `asr/` 并让 [missingPieces] 去报缺了什么**,
     *   不在这里自作主张替它猜一个路径 —— 报错必须指向用户该看的地方。
     */
    private fun locate(ctx: Context): File {
        val root = asrDir(ctx)
        if (hasAll(root)) return root
        val subs = root.listFiles { f -> f.isDirectory } ?: return root
        return subs.firstOrNull { hasAll(it) } ?: root
    }

    private fun hasAll(dir: File) =
        File(dir, VAD_FILE).isFile && File(dir, ASR_FILE).isFile && File(dir, TOKENS_FILE).isFile

    /**
     * 缺哪些文件。空 = 齐了。
     *
     * ★ 报的是**绝对路径**:他要把文件推到哪儿是唯一需要知道的事,
     *   而「模型没装」这四个字什么忙都帮不上。
     */
    fun missingPieces(ctx: Context): List<String> {
        val dir = locate(ctx)
        val want = listOf(VAD_FILE, ASR_FILE, TOKENS_FILE)
        val miss = want.filterNot { File(dir, it).isFile }.map { File(dir, it).absolutePath }
        if (miss.isEmpty()) return emptyList()
        // 连目录都没有 = 从来没装过,这比「缺一个文件」更需要一句话说清楚
        return if (!dir.isDirectory) miss + listOf("(目录还不存在:${dir.absolutePath})") else miss
    }

    /** 有耳朵吗。界面拿它决定 🎤 还是 ⌨。 */
    fun available(ctx: Context): Boolean = missingPieces(ctx).isEmpty()

    /** 现在正在听吗 —— 界面用它画「我在听」。 */
    val isListening: Boolean get() = listening

    // ------------------------------------------------------------------ 起手自检

    /**
     * App 起来时调一次(幂等):把 native 库拉起来,并把**模型在不在**写进日志。
     *
     * ★ 这是 E1 唯一看得见的成果 —— 那 26MB `.so` 和 22 个 `.kt` 装进项目之后,
     *   编译能过并不证明它跑得起来,**第一次 native 调用才是真的验证**。
     *   所以这一行不是装饰:`耳: 原生库加载成功 ver=…` 出现了,才说明 JNI 那一侧是通的。
     *
     * ★ 抓的是 **`Throwable` 不是 `Exception`** —— `UnsatisfiedLinkError` 是 `Error`。
     *   抓漏了的后果是它一路上抛到 Activity,而这个项目对它的记忆是
     *   「症状长得像『她听不见』」。
     */
    fun ensure(ctx: Context) {
        val app = ctx.applicationContext
        if (!nativeChecked) {
            nativeChecked = true
            try {
                System.loadLibrary("sherpa-onnx-jni")
                ModelManager.get(app).trace(
                    "$TAG 原生库加载成功 ver=${VersionInfo.version} " +
                        "onnxruntime=${VersionInfo.onnxruntimeVersion} git=${VersionInfo.gitSha1}"
                )
            } catch (t: Throwable) {
                ModelManager.get(app).trace(
                    "$TAG 原生库加载失败 ${t.javaClass.name}: ${t.message} —— 耳朵整个不可用"
                )
            }
        }
        val miss = missingPieces(app)
        val dir = locate(app)
        if (miss.isEmpty()) {
            ModelManager.get(app).trace("$TAG 模型齐了,在 ${dir.absolutePath}")
        } else {
            ModelManager.get(app).trace("$TAG 还没有耳朵 —— 缺:${miss.joinToString("、")}")
        }
    }

    /**
     * **把识别模型先装进内存**,不等他按下去。
     *
     * ★★ 2026-10-05 真机日志把它钉死了(第一次按住说话的那一趟):
     *   ```
     *   10:44:24 [ear]  耳: 装载识别模型…(239233841 字节)
     *   10:44:24 [main] [房] 🎤 听着呢,松手我就当你说完了
     *   10:44:27 [ear]  耳: 识别模型就绪(3047ms)
     *   10:44:27 [ear]  耳: 录音结束:0 段 / 0 ms(共收进 0 块 = 0 ms 音频)
     *   10:44:27 [main] [房] ✗ 没听见你说话
     *   ```
     *   **他按住、说话、松手,而这 3 秒里一个字节都没有进麦克风。**
     *   他那一趟的结论只能是「她听不到我说话」—— 而真凶是**装载**,
     *   和耳朵、和 VAD、和 ASR 一个字的关系都没有。
     *
     * ★ 它和 [ensure] 是两件事,别混:[ensure] 只 `System.loadLibrary`(毫秒级),
     *   这个才去碰那 239MB 的 `model.int8.onnx`。
     *
     * ★ 代价说清楚:装完就**常驻约 239MB**。但它**迟早要装**
     *   ([ensureRecognizer] 装完从不释放),所以这一行改的只是**什么时候付** ——
     *   而「他按下手指的那 3 秒」是全项目最不该付这笔钱的地方。
     *   后台线程 + 幂等:重复调只会撞上 `loading` 那道闸。
     */
    fun warmUp(ctx: Context) {
        val app = ctx.applicationContext
        if (recognizer != null || loadLatch != null) return
        if (!available(app)) return
        Thread({ ensureRecognizer(app) }, "ear-warmup").apply { isDaemon = true; start() }
    }

    // ------------------------------------------------------------------ 主流程

    /**
     * 听一句。立刻返回,活在一块后台线程上干。
     *
     * 并发规则:**一次只听一句**。正在听的时候再点一下,会收到一句
     * 「我还在听上一句」而不是排队 —— 排队会让第二次点击的延迟不可预测。
     */
    fun listen(ctx: Context, cb: Callback) {
        val app = ctx.applicationContext
        // ★★ 2026-11-08(体检):`listening` 只是「该不该继续收」的旗,取消后立刻就是 false,
        //   而上一轮的线程还在收尾。必须把「线程还在跑」单独判一次(见 [sessionActive]),
        //   否则取消 → 立刻再点 = 两个 EarMic 抢同一个 owner。
        if (listening || sessionActive) {
            notice(app, cb, if (listening) "我还在听上一句" else "上一句还在收尾,等一下再说")
            cb.onDone()
            return
        }

        // ★ 被推流占着麦:不硬抢。这条必须在**起线程之前**判 ——
        //   放进线程里的话界面会先显示「我在听」再被打回来,那一下闪动就是在骗人。
        //   ★ 这里只是**问一下**不是占住:真正占麦的是 [EarMic],见 [MicGate] 的文件头。
        //     问完到占上之间有个小窗口,万一撞上,[EarMic.start] 自己会返回同一句话。
        if (MicGate.isBusy(MicGate.EAR)) {
            notice(app, cb, MicGate.busyReason() ?: "麦克风被占着")
            cb.onDone()
            return
        }

        val miss = missingPieces(app)
        if (miss.isNotEmpty()) {
            notice(app, cb, "我还听不见 —— 识别模型没装。缺:${miss.joinToString("、")}")
            cb.onDone()
            return
        }

        listening = true
        finishRequested = false        // ★ 上一轮的收尾请求不能漏到这一轮
        val gen = sessionGen.incrementAndGet()
        sessionActive = true           // ★ 必须在起线程之前立起来,见 [sessionActive]
        Thread({ run(app, cb, gen) }, "ear").apply { isDaemon = true; start() }
    }

    /** 取消这一句。正在转写时取消不了(那几秒很短),但不会再往下走。 */
    fun cancel() {
        listening = false
    }

    /**
     * 「我说完了,现在就把听到的这句交出去。」
     *
     * 按住说话松手时调。走的是和「总上限到了」**同一条路**(break → flush → 转写),
     * 因为那条路已经被证明是对的:它会 `vad.flush()` 把最后半段吐出来。
     * ★ 自己另写一条「提前退出」的路,十有八九会漏掉 `flush()`,
     *   而漏掉 `flush()` 的症状**和「她没听见」一模一样**,零报错。
     *
     * 没在听的时候调它是**无害的空操作** —— 调用方(手指抬起)不必先问一遍状态。
     */
    fun finishNow() {
        if (listening) finishRequested = true
    }

    /**
     * 报一句人话。
     *
     * ★ **这里不调 [Callback.onDone]** —— 「无论如何都恢复界面」只在 [run] 的
     *   `finally` 里实现一次。散在十几个 return 前面的话,漏一个就是 🎤 永远转圈,
     *   而且那种 bug 在真机上看起来像「她卡住了」。
     */
    private fun notice(ctx: Context, cb: Callback, msg: String) {
        lastNotice = msg
        ModelManager.get(ctx).trace("$TAG $msg")
        cb.onNotice(msg)
    }

    private fun run(app: Context, cb: Callback, gen: Int) {
        val log = { m: String -> ModelManager.get(app).trace(m) }
        var mic: EarMic? = null
        try {
            val rec = ensureRecognizer(app)
            if (rec == null) { notice(app, cb, "识别器起不来,细节在日志里"); return }

            val vad = try {
                Vad(config = vadConfig(app))
            } catch (t: Throwable) {
                log("$TAG VAD 构造失败 ${t.javaClass.name}: ${t.message}")
                notice(app, cb, "VAD 起不来(${t.javaClass.simpleName}) —— 细节在日志里")
                return
            }

            // 麦克风那块线程只管读和排队,所有 VAD/ASR 都在**这一块线程**上跑 ——
            // 两个线程共享一个 Vad 是自找的麻烦,而排队在这里几乎不花钱。
            val queue = LinkedBlockingQueue<FloatArray>()
            // ★ 必须是 Atomic:写它的在 `ear-mic` 线程,读它的在 `ear` 线程。
            //   普通局部 var 在这个形状下是**数据竞争**(JMM 不保证可见性)。
            val micDead = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val m = EarMic(MicGate.EAR,
                onChunk = { queue.offer(it) },
                onError = { micDead.set(it) }
            )
            mic = m
            val err = m.start()
            if (err != null) { notice(app, cb, err); return }

            val collected = ArrayList<FloatArray>()
            val started = System.currentTimeMillis()
            var lastSpeechAt = started
            var gotSegment = false
            // ★ 这两个数**只为诊断存在**,见下面「0 段 / 0 ms」那段 ——
            //   没有它们,「麦克风一个字节都没进来」和「进来了但 VAD 没断出句」
            //   在日志上长得**一模一样**,而这两种故障的修法完全不同。
            var chunks = 0L
            var frames = 0L

            // ── 实时字幕的账本(见下面那个「实时字幕」段)
            // 他**这一句**到目前为止的音频。★ 只在他真的开口之后才开始攒 —— 为什么见下。
            var speechBuf: ArrayList<FloatArray>? = null
            var speechSamples = 0L
            // 「他开口了」这一轮报过没有。见 [Callback.onSpeechStart] ——
            // 它必须**只报一次**,否则每一帧 VAD 命中都会去重画一次水波。
            var speechAnnounced = false
            // 下一次允许解码的时刻。
            var nextPartialAt = 0L
            // 这一轮解了几次。只写进日志 —— 它是「实时字幕到底有没有在工作」的唯一证据。
            var partials = 0
            // 每轮复用同一个 list,别每 50ms 新建一个(一秒 20 个垃圾)
            val drained = ArrayList<FloatArray>(24)

            log("$TAG 开始听…(没人出声 ${IDLE_GIVEUP_MS}ms 就收工,总上限 ${LISTEN_MAX_MS}ms)")

            while (listening) {
                // ★★ 一次**抽干**队列,不是每 [POLL_MS] 只取一块。
                //
                //   每块音频只有 20ms([EarMic.CHUNK_BYTES]),而 `poll` 一等就是 50ms。
                //   每次只取一块的话,喂给 VAD 的速度只有**实时的 40%**,队列越积越多 ——
                //   而松手那一刻 `finishRequested` 会让循环**当场 break**,
                //   还排着队的那大半句话就被**整块丢掉**。
                //
                //   ★ 真机病历(2026-10-04 晚,`耳:` 日志):按住 **4 秒**,
                //     出来的是「录音结束:0 段 / 0 ms」→「没听见你说话」;
                //     而同样按住 3 秒的另一回是好的(「1 段 / 1274 ms」)。
                //     差别只在**他第一句话有没有落进被处理掉的那 40% 里** ——
                //     所以它表现得时好时坏,这也是它骗过这么多轮的原因。
                //     他当时的原话:「**还是感觉听不到我讲话**」。
                drained.clear()
                queue.poll(POLL_MS, TimeUnit.MILLISECONDS)?.let { drained.add(it) }
                while (true) {
                    val more = queue.poll() ?: break
                    drained.add(more)
                }
                // ★ 先都收进 `drained` 再一起喂 —— 因为同一批音频**有两个用处**:
                //   VAD 断句(下面),和实时字幕(再下面)。分两次 `poll` 就得多一次遍历。
                for (c in drained) {
                    vad.acceptWaveform(c)
                    chunks++
                    frames += c.size
                }

                // ★ 一个都不能少地抽干 VAD —— 它有内部队列,只取一个是会积压的
                while (!vad.empty()) {
                    val seg = vad.front()
                    vad.pop()
                    if (seg.samples.isEmpty()) continue
                    collected.add(seg.samples)
                    gotSegment = true
                }
                if (gotSegment) break                       // 一句说完了

                micDead.get()?.let { notice(app, cb, it); return }

                val now = System.currentTimeMillis()

                // ── 实时字幕:他说的字,在他说的时候就往字幕上走
                //
                //   ★★ 为什么只能这样写:SenseVoice 是**非流式**模型 ——
                //      它没有「接着上次往下听」这回事。所以真·流式在这里做不到,
                //      能做到的是**反复重解已经攒下的整段**,每次把最新的一版贴出去。
                //      代价是 CPU(见 [PARTIAL_EVERY_MS]),换来的是他说话的时候
                //      屏幕上**有东西在动** —— 而「没反应」和「慢」,
                //      在他眼里本来是同一件事。
                //
                //   ⚠️ 只在他**真的开口之后**才开始攒。把前面那段静音也喂进去的话,
                //      SenseVoice 会一本正经地在字幕上编出几个字 ——
                //      他还没说话、字已经出来了,那是最难看的失败。
                if (vad.isSpeechDetected()) {
                    // ★★ 「他开口了」—— 这一轮只报一次,**在攒音频和识别之前**。
                    //   界面拿它把水波换成基因序列:这是「她到底有没有听到我说话」
                    //   唯一一个**来得及在他还在说的时候**到达的信号(见 [Callback.onSpeechStart])。
                    if (!speechAnnounced) {
                        speechAnnounced = true
                        cb.onSpeechStart()
                    }
                    if (speechBuf == null) {
                        speechBuf = ArrayList()
                        nextPartialAt = now + PARTIAL_MIN_MS
                    }
                    speechBuf.addAll(drained)
                    speechSamples += drained.sumOf { it.size.toLong() }
                    // ★ 三个闸,少一个都会出问题:
                    //   · 到点了   —— 占空比,防热
                    //   · 攒够了   —— 防残句乱跳
                    //   · 队列空了 —— 我们没落后,现在花时间解码不会把 VAD 饿着
                    //   · 还没松手 —— 松手了就该直奔最终结果,别在中间插一脚延迟
                    if (now >= nextPartialAt && !finishRequested && queue.isEmpty() &&
                        EarMath.durationMs(speechSamples.toInt()) >= PARTIAL_MIN_MS) {
                        val t0 = System.currentTimeMillis()
                        val raw = transcribe(app, rec, concat(speechBuf))
                        val dt = System.currentTimeMillis() - t0
                        // ★ 用**解完之后**的时刻起算,而且至少给「刚花掉的那些」——
                        //   用解之前的时刻起算的话,一次 400ms 的解码会让下一次立刻够格,
                        //   于是解码一次接一次,CPU 满载 —— 那正是这个常数要防的事。
                        //   (★ 2026-10-05:这里原来是 `dt * 2`,见 [PARTIAL_EVERY_MS]
                        //    那段真机数字 —— 两倍把「变说变有字」直接吃掉了,改回一倍。)
                        nextPartialAt = System.currentTimeMillis() +
                            maxOf(PARTIAL_EVERY_MS, dt)
                        val say = raw?.let { EarMath.cleanPartial(it) }
                        if (say != null) {
                            partials++
                            // 只记第一次。每一版都记的话,一次三秒的说话会刷十几行日志,
                            // 而这一行要证明的东西(「实时字幕这条链是通的」)第一次就够了。
                            if (partials == 1) log("$TAG 实时字幕开工:「$say」(解码 ${dt}ms)")
                            cb.onPartial(say)
                        }
                    }
                }

                if (vad.isSpeechDetected()) lastSpeechAt = now
                if (now - lastSpeechAt > IDLE_GIVEUP_MS) {
                    log("$TAG ${IDLE_GIVEUP_MS}ms 没听到人声,收工")
                    // ★ 和下面那句「一块字节都没收到」**故意不同**:
                    //   那一句是「你说了我没听清」,这一句是「你没说,我把麦关了」。
                    //   两句都是人话,但对应的是他接下来的两种动作 ——
                    //   重说一遍,还是干脆不说了。
                    notice(app, cb, "${IDLE_GIVEUP_MS / 1000} 秒没动静,我把麦克风关了")
                    return
                }
                if (now - started > LISTEN_MAX_MS) {
                    log("$TAG 到总上限 ${LISTEN_MAX_MS}ms,强行收尾")
                    break
                }
                if (finishRequested) {
                    log("$TAG 松手了,当场收尾(不等 VAD 自己断句)")
                    break
                }
            }
            if (!listening) {
                // ★★ 2026-10-05:这一行原来只有三个字,而它**正好是丢掉证据的那一处** ——
                //   下面那句「录音结束:…共收进 N 块」在 `return` 之后,取消的每一轮都走不到。
                //   于是「被打断」成了一个黑盒,**分不出麦克风哑了、还是 VAD 没断句**。
                //   (2026-10-05 查桌面上「说话没有声音」时就卡在这儿:悬浮条上四次开麦
                //    全部只有「被打断了」,一个数都没有 —— 那一轮只能靠猜。)
                //   三个数都在手边,没有理由不带。
                log("$TAG 被打断了(共收进 $chunks 块 = ${EarMath.durationMs(frames.toInt())} ms 音频," +
                    "实时字幕 $partials 次,已断 ${collected.size} 段)")
                return
            }

            // ★ 收工前**再抽一次队列** —— 三条 break 出口(松手 / 到上限 / 正常)共用这一处。
            //   尤其是「松手」那条:他最后那半句多半正排在这儿,
            //   不抽干就等于**把他刚说完的话的最后一段扔了**。
            //   (循环顶上那次抽干管的是「一直在听」的中途,这一次管的是**收尾**。)
            while (true) {
                val tail = queue.poll() ?: break
                vad.acceptWaveform(tail)
                chunks++
                frames += tail.size
            }

            // ★★ `flush()` 是**必须的**。不调的话 VAD 内部最后那一段永远不会吐出来 ——
            //    症状和「她没听见」**一模一样**,零报错、零日志。
            vad.flush()
            while (!vad.empty()) {
                val seg = vad.front()
                vad.pop()
                if (seg.samples.isNotEmpty()) collected.add(seg.samples)
            }

            // 收麦:转写那几秒不该继续占着它
            m.stop(); mic = null

            val samples = concat(collected)
            val ms = EarMath.durationMs(samples.size)
            // ★ 把「收进多少音频」一起打出来。见 [chunks] 那段 ——
            //   这一行是**唯一**能分辨「麦克风哑了」和「VAD 没断句」的东西:
            //   收进 0 ms = 麦那层的事;收进了几百毫秒却 0 段 = VAD 那层的事。
            log("$TAG 录音结束:${collected.size} 段 / $ms ms" +
                "(共收进 $chunks 块 = ${EarMath.durationMs(frames.toInt())} ms 音频," +
                "实时字幕 $partials 次)")

            // ★ 诊断:0 段但有音频 → 算一下 RMS,分「麦克风哑了」还是「电平太低」
            if (collected.isEmpty() && frames > 0) {
                val ss = samples.fold(0f) { acc, x -> acc + x * x }
                val rms = if (samples.isNotEmpty()) kotlin.math.sqrt(ss / samples.size) else 0f
                log("$TAG ↑ 0 段但收了 ${frames.toInt()} 个样本 → RMS=$rms " +
                    "(<0.01=麦克风静音 0.01~0.05=电平太低 >0.05=VAD 阈值问题)")
            }

            if (samples.isEmpty()) {
                // 同上:把「是没听见,还是没收到」写清楚,别只留一句「没听见你说话」。
                if (chunks == 0L) log("$TAG ↑ 一块音频都没进来 —— 问题在麦克风那一层,不在 VAD")
                notice(app, cb, "没听见你说话")
                return
            }
            if (EarMath.tooShort(ms)) {
                log("$TAG $ms ms 短于 ${EarMath.MIN_SPEECH_MS}ms,判为杂音")
                notice(app, cb, "太短了,没听清")
                return
            }
            if (EarMath.tooLong(ms)) {
                log("$TAG $ms ms 超过 ${EarMath.MAX_SPEECH_MS}ms,判为杂音")
                notice(app, cb, "太长了,后面听着像杂音 —— 一次说一件事吧")
                return
            }

            val raw = transcribe(app, rec, samples)
            if (raw == null) { notice(app, cb, "识别出错了,细节在日志里"); return }

            // ★ **原文照打**。这是唯一能看出「sherpa 到底有没有先把 <|zh|> 这类标记剥掉」
            //   的东西 —— 而这件事不猜,第一次真机跑就见分晓。
            log("$TAG 转写原文(len=${raw.length}):$raw")

            val clean = EarMath.clean(raw)
            if (clean == null) {
                log("$TAG 丢掉了:${EarMath.whyDropped(raw)}")
                notice(app, cb, "没听清,再说一遍?")
                return
            }
            val fixed = UserLexicon.correct(clean)
            if (fixed != clean) log("$TAG 词库纠正:$clean → $fixed")
            log("$TAG 听见了:「$fixed」")
            cb.onHeard(fixed)
        } catch (t: Throwable) {
            // 耳朵里任何一处漏网的异常,如果不拦,表现就是「点了没反应」——
            // 那正是这个类存在的理由。所以最外面必须兜住并说出来。
            log("$TAG 崩了 ${t.javaClass.name}: ${t.message}")
            cb.onNotice("耳朵出错了(${t.javaClass.simpleName})")
        } finally {
            mic?.stop()
            // ★★ 2026-11-08(体检):只收拾**自己这一轮**。取消之后马上又点一下时,
            //   新一轮可能已经把 `listening` 重新置 true —— 旧线程若无条件清零,
            //   新一轮的录音循环会立刻退出,而界面还在等它。`onDone` 同理:
            //   旧轮次这时再报一次「结束」,界面就会在她正听着的时候收工。
            if (sessionGen.get() == gen) {
                listening = false
                // ★ onDone 只在这里调一次 —— 「无论如何都恢复界面」这件事只有一处实现,
                //   散在十几个 return 前面的话,漏一个就是 🎤 永远转圈。
                cb.onDone()
            }
            // ★ **最后一步**才摘「在跑」的牌子:它一摘,[listen] 才允许下一轮进来。
            //   提前摘的话,新一轮会在旧线程还没走完收尾时开始 —— 正是上面那个竞态。
            sessionActive = false
        }
    }

    private fun concat(parts: List<FloatArray>): FloatArray {
        val total = parts.sumOf { it.size }
        val out = FloatArray(total)
        var at = 0
        for (p in parts) { System.arraycopy(p, 0, out, at, p.size); at += p.size }
        return out
    }

    private fun transcribe(ctx: Context, rec: OfflineRecognizer, samples: FloatArray): String? = try {
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, EarMic.SAMPLE_RATE)
            rec.decode(stream)
            rec.getResult(stream).text
        } finally {
            // ★ 一定要放。OfflineStream 是个 native 句柄,靠 finalize 回收的话
            //   要等 GC —— 而这个方法的调用频率是「每说一句一次」,
            //   等他攒够了回收,已经在手机上白占了几十 MB。
            stream.release()
        }
    } catch (t: Throwable) {
        ModelManager.get(ctx).trace("$TAG 转写出错 ${t.javaClass.name}: ${t.message}")
        null
    }

    private fun vadConfig(ctx: Context) = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = File(locate(ctx), VAD_FILE).absolutePath,
            threshold = 0.5f,
            // 说完停 0.6 秒算一句结束。默认 0.25 秒在中文里太急 ——
            // 「打开……微信」中间那个停顿会被切成两句,而第二句「微信」会被当成新指令。
            minSilenceDuration = 0.6f,
            minSpeechDuration = 0.25f,
            windowSize = 512,
            // ★ 给到 30 秒:Silero 的默认 5 秒会把一段长话**切成好几段**,
            //   而 E2 的设计是「一次点击 = 一句话」。切开了就只能拿到前半句,
            //   症状是「她只听懂了一半」。
            maxSpeechDuration = 30.0f,
        ),
        sampleRate = EarMic.SAMPLE_RATE,
        numThreads = 1,
        provider = "cpu",
        debug = false,
    )

    /**
     * 把 228MB 的 SenseVoice 装进来。**只装一次,之后常驻。**
     *
     * 装一次几秒,而耳朵是要随手点随时用的东西 —— 每次点都重新装是不可接受的。
     * 闲置卸载留给后面「一直在听」那一轮去权衡(E5)。
     */
    @Synchronized
    private fun ensureRecognizer(ctx: Context): OfflineRecognizer? {
        recognizer?.let { return it }
        // ★ 决定「我来装」还是「等别人装完」—— 这一步必须**原子**,
        //   否则两个线程会各装一份 239MB(内存双份,而且后装的那份把前一份顶掉)。
        var mine: java.util.concurrent.CountDownLatch? = null
        var waitFor: java.util.concurrent.CountDownLatch? = null
        synchronized(loadLock) {
            recognizer?.let { return it }
            val cur = loadLatch
            if (cur != null) waitFor = cur
            else {
                mine = java.util.concurrent.CountDownLatch(1)
                loadLatch = mine
            }
        }
        // ★★ `await` **必须在锁外面**。`CountDownLatch.await` 和 `Object.wait` 不一样 ——
        //   它**不会**释放这个监视器。写在 `synchronized` 里面的话,装模型那一方
        //   在 `finally` 里永远拿不到锁去清闸 → 两边对死,
        //   而症状是「按住说话,她就一直没反应」,零报错。
        waitFor?.let {
            try { it.await() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            return recognizer
        }
        val latch = mine!!
        return try {
            val dir = locate(ctx)
            val t0 = System.currentTimeMillis()
            ModelManager.get(ctx).trace("$TAG 装载识别模型…(${File(dir, ASR_FILE).length()} 字节)")
            val r = OfflineRecognizer(config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = EarMic.SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = File(dir, ASR_FILE).absolutePath,
                        // 中英混说 —— 她的活儿本来就是这样(「打开 Edge」「搜一下 transformer」)
                        language = "auto",
                        // ★ 逆文本规整:把「二〇二五」变成 2025、「三点半」变成 3:30。
                        //   不开的话,送去模型的是一个数字被念成字的句子。
                        useInverseTextNormalization = true,
                    ),
                    tokens = File(dir, TOKENS_FILE).absolutePath,
                    // 手机上脑已经吃掉大半个 CPU,识别只在按下的那几秒跑,2 条线程够快也不抢
                    numThreads = 2,
                    provider = "cpu",
                    debug = false,
                    // ★ 不写这个的话 sherpa 不知道这是 SenseVoice,会去猜别的模型类型
                    modelType = "sense_voice",
                ),
                decodingMethod = "greedy_search",
            ))
            recognizer = r
            ModelManager.get(ctx).trace("$TAG 识别模型就绪(${System.currentTimeMillis() - t0}ms)")
            r
        } catch (t: Throwable) {
            ModelManager.get(ctx).trace(
                "$TAG 识别器构造失败 ${t.javaClass.name}: ${t.message} —— 多半是文件被截断了"
            )
            null
        } finally {
            // ★ 顺序要紧:**先**清掉闸、**再**放行等的人。
            //   反过来的话,被唤醒的那个线程会在 `loadLatch` 还是非 null 时
            //   又去 `synchronized` 里等一次 —— 而它等的是自己,
            //   于是耳朵永远停在「正在装载」上,零报错。
            synchronized(loadLock) { loadLatch = null }
            latch.countDown()
        }
    }

    /** 只给单测/调试用:把内存里的模型放掉。 */
    internal fun releaseForTest() {
        try { recognizer?.release() } catch (_: Exception) {}
        recognizer = null
    }
}
