package com.example.touchpad

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 她的**真嗓子** —— 内嵌 sherpa-onnx 的 VITS(离线、本机、不出网)。
 *
 * ## 为什么非换不可
 *
 * 这台机器的系统 `TextToSpeech` **一个中文声学模型都没有**
 * (真机日志:`房间:TTS 音色 5 种,其中中文 0 种`)。
 * `setLanguage(zh-CN)` 失败后退回默认的英文嗓子去读汉字 —— 所以问题不是
 * 「有点口音」,是**发音本身错**:用户 2026-10-05 点名
 *
 *   > 「我说『调试』的**调第二声**,她说**调第四声**」
 *
 * 多音字全凭一个不认识中文的引擎瞎猜,应用侧怎么写都没用。
 *
 * ## 换完为什么就对了 —— 这一条**我在本地亲眼验过**
 *
 * `vits-zh-hf-eula/lexicon.txt` 里同一个字有**两条**记录:
 *
 * ```
 * 第 15866 行:  调   t ⁼ y a u ↓          ← 单字默认,↓ = 降调 = diào(这就是你听到的错音)
 * 第 59319 行:  调试 t ʰ y a u ↑ s ` ɹ ` ↓  ← 词组条目,↑ = 升调 = tiáo  ✓
 * ```
 *
 * VITS 前端对 lexicon 做**最长匹配**,所以「调试」走进九万多条记录里的第二条,
 * 自动就是 tiáo。**不用写任何多音字规则,不用 HomophoneReplacer**
 * (那是 ASR 侧的东西,`Tts.kt` 里根本没有那个字段 —— 这条弯路我差点走了)。
 *
 * ## 契约(**这个类存在的全部理由**)
 *
 * [speak] 的 `onDone` **一定会来,而且只来一次**(除非这中间有人 [stop])。
 *
 * 这不是讲究:调用方是**对话循环**,它把麦克风的开合挂在 `onDone` 上。
 * 回调不来 = 她说完这句之后永远沉默,而症状是「她不理我了」,日志里一片太平。
 * `HerVoice` 就是为这条契约立过专文的(见那边的文件头),这里**原样继承** ——
 * 换引擎不许把那条契约换松。
 *
 * 「永远不会来」的四个来源,逐个堵死:
 *
 * | 真因 | 这里怎么堵 |
 * |---|---|
 * | 模型不在 / 装载失败 | [isReady] 为假,调用方走系统 TTS 兜底([speak] 里也直接兑现回调) |
 * | 合成抛异常 | `catch(Throwable)` → 记日志 → 兑现回调 |
 * | 合成了但 `AudioTrack` 建不起来 | 同上 |
 * | **合成完了、播放还没播完** | ★ 见下面「为什么要 drain」—— 这条最阴 |
 *
 * ## ★★★ 为什么不用流式回调(`generateWithCallback`)—— 2026-10-05 真机崩出来的
 *
 * 原来这里是**流式**的:每合成出一块就写一块进 `AudioTrack`,嘴型也跟着每块更新。
 * 它在真机上**一开口就把整个 App 崩掉**,而且是**静默的** —— `model.log` 里一行都没有
 * (崩在 logcat),用户看到的只有「**她嘴巴在动,但是没声音,没字幕**」。
 *
 * ### 真凶在 JNI 里,不在我们的代码里
 *
 * sherpa 的 `libsherpa-onnx-jni.so` 拿到那个 Kotlin lambda 之后是这么找方法的:
 *
 * ```
 * jclass    cls = GetObjectClass(callback);
 * jmethodID mid = GetMethodID(cls, "invoke", "([F)Ljava/lang/Integer;");
 * ```
 *
 * 它找的是**装箱签名**(`float[]` → `Integer`)。那是 `LambdaMetafactory` 建这个 lambda 时
 * 用的**特化方法类型**,ART 会在运行时把它生成出来 —— **但 D8 打包时把这个 lambda
 * 折成了一个只有擦除签名的类**。
 *
 * 实测 APK 里的 `classes.dex`(`dexdump`),我们自己的 `SherpaVoice$$ExternalSyntheticLambda0`
 * **只有这一个方法**:
 *
 * ```
 * invoke:(Ljava/lang/Object;)Ljava/lang/Object;
 * ```
 *
 * 带类型那个 `invoke:([F)Ljava/lang/Integer;` **根本没生成**。
 * 于是 `GetMethodID` 抛 `NoSuchMethodError`、JNI **没有清掉这个异常**,
 * 紧接着的一次 `FindClass` 撞上「有未决异常」→ `art::Thread::AssertNoPendingException()`
 * → **`abort()` → SIGABRT**。真机 tombstone 上是:
 *
 * ```
 * signal 6 (SIGABRT)
 * Abort message: 'No pending exception expected:
 *   java.lang.NoSuchMethodError: no non-static method
 *   "Lcom/example/touchpad/SherpaVoice$$ExternalSyntheticLambda0;.invoke([F)Ljava/lang/Integer;"'
 *   at OfflineTts.generateWithCallbackImpl (Tts.kt:-2)
 *   at com.example.touchpad.SherpaVoice.runOne (SherpaVoice.kt:359)
 * ```
 *
 * ★★ **这条 `catch` 不住。** 它是 native 的 `abort()`,不是 Java 异常 ——
 *   `catch(Throwable)` 和 `finally` 都来不及跑,整个进程直接没了。
 *   所以**没有「重试 / 兜底 / 优雅降级」这条路,唯一的出路是不用它**。
 *
 * ### 换成了什么
 *
 * 不带回调的 `tts.generate()`。它在 JNI 里**一次 lambda 都不碰**
 * (实测 `.so` 里有 `Java_…_OfflineTts_generateImpl`,那条路只收字符串、回一个音频数组),
 * 所以**同一个坑不可能再踩一次**。
 *
 * ### ★ 代价,说清楚
 *
 * | | 流式回调 | **现在(整句合成)** |
 * |---|---|---|
 * | 第一声 | 合成出第一块就响 | 整句合成完才响 |
 * | 被叫停 | 能在块边界掉头 | **要等这句合成完** |
 * | 嘴型 | 每块一档 | ★ **一样** —— 写的时候自己切块,见 `LEVEL_CHUNK` |
 *
 * 两条都是**延迟**问题,不是功能问题。→ 由计划里的「切句抢先音」按句切开来解,
 * 那时窗口就从「一整段」缩到「一句话」。**用一点延迟换一条命,值。**
 *
 * ## ★★ 为什么要 drain:这是换引擎最容易漏掉的一条
 *
 * `generate` 是**阻塞**的,它返回时意味着**合成**结束 ——
 * 但 `AudioTrack` 里还压着最多半秒的音频**没播出来**。
 * 那一刻就兑现 `onDone` 的话,麦克风会在**她最后半个字还在响**的时候开,
 * 于是那句尾音被听成一条新输入 → **她自问自答**。
 *
 * 它不报任何错,拿到的是一句语法通顺、上下文合理的用户话。这就是
 * `EarSessionMath.COOLDOWN_MS` 那段注释讲的同一个坑,只是换了个入口。
 * 所以这里**必须等到播放头追上写入的帧数**才算「说完」。
 *
 * ## 每句一个 AudioTrack(故意的)
 *
 * 复用一条 track 就要 `flush()` + 重置播放头,而 `playbackHeadPosition`
 * 在 flush 之后归零 —— **上一句的 drain 判据会和新一句的撞在一起**,
 * 表现是偶尔「她还没说完就说完了」(自问自答的入口)或者「她卡住不说话」。
 * 建一条 track 大约几毫秒,一句一辈子,换掉整类别名 bug。
 */
internal object SherpaVoice {

    // ------------------------------------------------------------------ 状态

    private enum class State { IDLE, LOADING, READY, FAILED }

    @Volatile private var state = State.IDLE

    @Volatile private var engine: OfflineTts? = null

    /** 装载失败的人话原因,给日志用。 */
    @Volatile private var why: String? = null

    // —— ZipVoice(克隆音)那一套:参考音频 + 它念的是什么 ——
    //
    // ★ 装进内存一次就够:它是**同一个人的音色**,每一句都要用,
    //   每次合成再去读盘既慢又没必要(和「空输入框」那种一次性的东西不一样)。
    // ★ 只在这份嗓子是 ZipVoice 时才有值;VITS 那份永远是 null,
    //   而 `synthOne` 是按 [engineOf] 分派的,不会去读它。
    @Volatile private var refAudio: FloatArray? = null
    @Volatile private var refRate: Int = 0
    @Volatile private var refText: String? = null

    /** 参考音频怎么读出来的 —— 进日志用(「几个点 / 多少 Hz」是唯一能一眼看穿读错没读错的数)。 */
    @Volatile private var refWhy: String? = null

    /**
     * 现在装的是哪一套([ENGINE_ZV] / [ENGINE_VITS])。
     *
     * ★ 必须**在装载那一刻定下来、存进字段** —— 每合成一句都去 `engineOf(File(...))`
     *   问一次盘的话,合成的热路径上就多了一次文件系统调用;更要命的是**盘上那个目录
     *   可以在跑的时候被人改掉**(他推文件换嗓子),于是同一份引擎会一会儿走这条路、
     *   一会儿走那条路,而两条路的参数形状完全不同 → 症状是**她说不出话**。
     *
     * ★ **同一套里的 int8 / fp32 不进这个字段** —— 那两个版本参数形状**完全一样**,
     *   只在装载那一刻拼一次路径(见 [zvNames])。所以换版本不用重开 App,重开一次她就行。
     */
    @Volatile private var engineKind: String = ENGINE_VITS

    /** 「语速滑块对克隆音无效」这条只报一次 —— 每句都报会把日志刷爆,而他只需要知道一次。 */
    @Volatile private var speedTold = false

    /**
     * 正在念的这一句的编号。**`stop()` 和「顶掉」都靠它作废旧回调** ——
     * 一条已经在跑的合成线程发现自己的号不是当前号了,就安静退出,
     * **不兑现回调**(调用方已经明确不要这句话了,见类头契约)。
     */
    @Volatile private var currentSeq = 0

    /** 当前这句的音量回调。**和 [currentSeq] 一起作废。** */
    @Volatile private var level: ((Float) -> Unit)? = null

    /**
     * 现在有没有**真的在出声** —— 不是「这一句交给嗓子了」。
     *
     * ★★ 它**只在第一块音频写进 track 之后**才立起来(见 [runOne] 里那一处)。
     *   立在合成之前的话,整句合成的这几秒里它就是 true,而**一个字节的声音都没有**
     *   —— 「她在说」这件事就没有一个诚实的判据了。
     */
    @Volatile private var speaking = false

    /**
     * 抬线程优先级失败的人话原因 —— **只报一次**,见 [audioPriority]。
     *
     * 为什么落到字段上、不当场 trace:这两条线程是**对象初始化那一刻**造的,
     * 那时一次 `speak()` 都还没发生,**手上没有 Context**,写不了 model.log。
     */
    @Volatile private var priorityWhy: String? = null

    /**
     * ★★ 2026-10-06 晚:**嗓子的两条线程都抬到「音频级」优先级**(合成 [worker] + 出声 [player])。
     *
     * 他报的第三条原话:
     * > 「她说话中断还是有问题」
     *
     * 那一轮量清楚了 —— 同一台机器、同一段话,**唯一变的只有本机模型在不在跑**:
     *   · 本机 llama 闲着那两分钟:实时 **1.33~1.44×**(合成比播放快,跟得上);
     *   · 本机 llama 跑着那一分钟:当场掉到 **0.33~0.69×**,`等待` 涨到 **23.7 秒**。
     * 所以 —— **不是合成慢,是合成被抢了 CPU**:她的话排在本机模型的后面。
     *
     * 这一处只做一件事:**出声优先于这台手机上的其他一切**。
     * 理由不是「运气好点」,是一条本来就不该破的规矩 ——
     * **音频是实时的,别的是尽力而为**:一段话合成不出来就是不出来(加缓冲、加队列
     * 都没用,见 [clips] 那段写的账);而本机模型慢一点只是她多想一会儿。
     * **慢半拍能等,断一下不能等。**
     *
     * ★★ 必须在这条线程**自己**里面调 —— `setThreadPriority` 抬的是**调用它的那条线程**。
     *    写在 [worker] / [player] 的构造式里(不在下面这个 Runnable 内部),抬的是
     *    **造线程的那条线程**(也就是没事干的调用方),嗓子这条**一点没变**;
     *    而且它**不报错**,症状是「改了跟没改一样」。
     * ★ 抬不动(被系统拒)**不算失败**:退回原来的优先级,她照常说,只是还断。
     *    所以异常在这儿接住 —— 但**不吞得无声无息**:记进 [priorityWhy],
     *    等下一次真要出声时(那时手上才有 app)报一行。
     *    否则以后分不清「这一刀没用」和「系统不让抬」。
     */
    private fun audioPriority(name: String, body: Runnable): Thread = Thread({
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        } catch (t: Throwable) {
            priorityWhy = "嗓子: 抬不动「$name」的优先级(${t.javaClass.simpleName}: ${t.message})"
        }
        body.run()
    }, name).apply { isDaemon = true }

    private val worker = Executors.newSingleThreadExecutor { r ->
        audioPriority("her-voice", r)
    }

    private val seqGen = AtomicInteger(0)

    /**
     * ★★ **世代号** —— 打断一次加一,**整队作废**。
     *
     * 为什么 [currentSeq] 不够:它只作废**正在念的那一句**。而她的话是**排队**说的
     * (先出声抢的那几块 + 定稿那一句,全是 `queue = true`),排在后面的 Runnable
     * 在 `stop()` 之后**照样会一个一个跑下去** —— 每跑一个就 `currentSeq = 自己` 的号,
     * 于是 `alive()` 又是 true,又开口。
     *
     * 症状就是用户 2026-10-05 报的那句:
     * > 「我这边都到**第三轮**对话,她还在留着**第一轮**对话」
     * 打断一次只掐掉嘴里那一句,欠的几句照念。
     *
     * ★ 每一件活儿在**递进队列那一刻**记下当时的世代号,轮到它时对一下:
     *   不是当前这一代 = 一个字都不念、**也不兑现回调**(调用方已经明确不要它了)。
     */
    private val gen = AtomicInteger(0)

    /**
     * ★★ **嗓子里还欠着几句话**(含正在合成、还没轮到出声的那一句)。
     *
     * 为什么要它:半双工闸读的是 [isSpeaking],而它只在**真的在出声**时为真。
     * 合成一块要好几秒(实测中位 **1.25× 实时**,慢的那 41% 只有 **0.28×**),
     * 那几秒里 `speaking` 是 false,
     * **而队列里明明压着东西** —— 于是麦克风被提前打开,她把**自己**接下来要念的
     * 那句听回去,当成他的话。用户 2026-10-05 原话:
     * > 「她还没说完,系统就误认为她说完,就打开麦克风,然后她就自己跟自己聊起来了」
     *
     * ★ 计数在**包在 [synthOne] 外面那一层**加减,不在它里面 ——
     *   `synthOne` 在进自己的 `try` **之前**就有好几条提前 return(世代号过期 /
     *   引擎是 null / 采样率读不到),写在里面会**永久泄漏**,麦克风再也回不来。
     *   ★★ 而且销账的真正落点是 [playLoop] 的 `finally`:合成完只是「交给出声线程」,
     *     那块音频**还没响**。在合成完就销账的话,会有一段窗口是 `pending == 0`
     *     而下一块正要开口 —— 半双工闸当场放行,**同一个 bug 换个门再进来一次**。
     */
    private val pending = AtomicInteger(0)

    /**
     * 递活儿([speak])和 [stop] 之间的一把锁。
     *
     * ★ 只要一处竞态就够毁掉上面那个计数:`stop()` 刚把 `pending` 清成 0,
     *   而另一条线程正好夹在「读了世代号」和「欠账 +1」中间 —— 那一笔就永远还不掉了,
     *   而还不掉的后果是**麦克风一直关着**(她说完这句就再也不理他)。
     *   两处都短,锁的代价可以忽略。
     */
    private val submitLock = Any()

    /**
     * ★★★ **合成与出声分成两条线程** —— 治「说话断断续续」。
     *
     * 用户 2026-10-05 原话:
     * > 「然后说话**断断续续**的,这真是个大问题断断续续的」
     *
     * ## 真因(不是猜的,是从真机日志里量出来的)
     *
     * 原来 [synthOne] 和出声在**同一条线程**上,先合成、再出声。于是排队的每一块
     * 都只能**等前一块念完**才开始合成 —— 他听到的每一处停顿 = **一整块的合成耗时**。
     *
     * 2026-10-05 晚从 `model.log` 的 `★量` 行量出来的(52 个「同一轮之内」的接缝):
     *
     * | 量到的 | 数字 |
     * |---|---|
     * | 上一块念完 → 下一块出声之间的**空白** | 中位 **2476ms**,最大 25505ms |
     * | 空白超过 2 秒的接缝 | **62%** |
     * | 「上一块念完 → 合成才开始」的间隔 | 只有 ~50ms ← ★ **这就是串行的指纹** |
     * | 这一块**交到嗓子**的时间比上一块念完还早 | **75%**(中位早 **41 秒**) |
     * | 合成本身追不追得上 | 中位 **1.25× 实时**(追得上);但 41% 的块慢于实时 |
     *
     * 第三、四行是结论:**七成半的块早就排队等着了**,而合成只要 2.1 秒 ——
     * 只要多一条线程,这 2.1 秒完全可以**在前一块还念着的时候**花掉。
     *
     * ## 形状
     *
     * ```
     *  [her-voice] 合成线程:tts.generate → Clip ─┐
     *                                            ↓ clips(有界 = 背压)
     *  [her-play]  出声线程:取 Clip → AudioTrack → onLevel / onDone
     * ```
     *
     * ★ 合成线程**不再**出声,出声线程**不再**合成。合成线程在预告下一块时,
     *   出声线程正念着上一块 —— 那 2.5 秒就是在这儿被省掉的。
     *
     * ⚠️ **救不了的那部分,说清**:41% 的块合成**慢于实时**(最慢 0.28×)。
     *   那种块流水线也追不上 —— 它的出路是**把块切得更小**或者换更快的嗓子,
     *   不在这个类里。别以为改完就一句停顿都没有了。
     */
    private val clips = LinkedBlockingQueue<Clip>(4)

    /**
     * 正在念的那个 [AudioTrack]。
     *
     * ★ 存在的唯一理由:**`stop()` 要能当场让它闭嘴**。
     *   没有它的话,`stop()` 只能等写循环下一次醒来才生效,而写循环大多数时间
     *   **卡在阻塞的 `track.write()` 里** —— 于是「先让她停」会拖到缓冲里那
     *   0.4 秒(见 [MIN_BUF_SECONDS])播完为止。按住说话那条路上,
     *   这 0.4 秒就是「我已经按住了,她还在说」。
     */
    @Volatile private var playTrack: AudioTrack? = null

    /**
     * 一块**已经合成好、等着出声**的音频。见 [clips]。
     *
     * ★ `settle` 是「这一块整条命走完了」的销账口 —— **合成失败、被作废、
     *   念完了,三条路都要走它,而且每条只准走一次**。它管的是 [pending],
     *   而 [pending] 管的是**麦克风什么时候能开**:漏一次 = 她说完这句再也不理他;
     *   多一次 = 她正念着麦克风就开了(自问自答)。
     */
    private class Clip(
        val app: Context,
        val id: Int,
        val myGen: Int,
        val text: String,
        val samples: FloatArray,
        val rate: Int,
        val onLevel: ((Float) -> Unit)?,
        val onStart: (() -> Unit)?,
        val onDone: () -> Unit,
        val settle: () -> Unit,
    )

    // ★ 出声那条也抬 —— 见 [audioPriority]。少抬这一条等于没抬:
    //   合成跟上来了、播放被抢走,他听到的还是断。
    private val player = Executors.newSingleThreadExecutor { r ->
        audioPriority("her-play", r)
    }

    init {
        // ★ 一整条命都在这个循环里等 [clips]。空转的代价是**零**(阻塞在队列上),
        //   所以不需要惰性启动 —— 惰性启动反而多一处竞态(两条线程同时发现"还没起")。
        player.execute { playLoop() }
    }

    /**
     * ★★ 2026-10-05 晚:**量「中间断一下」的量具**。
     *
     * 用户报的第三条原话:
     *
     * > 「她在说话的时候,**会中间断一下再说**,前半句话……后半句话,会中间加延迟」
     *
     * 上一轮我只能靠真机日志**反推**(那次量到约 8 秒的静默),而反推出来的
     * 「合成慢」和「模型根本没吐这么快」**两种解释都成立** —— 它们的修法**完全相反**,
     * 所以不量清楚就动手是赌。这一格就是那把尺子。
     *
     * 上一句**真的念完**的那个时刻(见 [playClip] 的 `finally`)。
     * 下一句开始出声时减掉它 = **他耳朵听到的那一段空白**,不用再反推。
     *
     * ★★ 2026-10-05 晚起**它是跨线程的**:写它的是 `her-play`(出声那条),
     *   读它的是 `her-voice`(合成那条,报「断口」)—— 所以必须 `@Volatile`。
     *   少了这个修饰,合成线程可能一直读到寄存器里的旧值,于是「断口」那一栏
     *   **报出一个看着合理其实不存在的数** —— 而它正是我们判断流水线有没有生效的尺子。
     *   (★ 量具自己坏了比没有量具更糟:它会让人以为修好了。见记忆「量具会撒谎」。)
     *
     * ★ [stop] 会把它清掉 —— 那是「新的一轮」的边界,不清的话每一轮第一句都会报一段
     *   「上一轮结束后过了 40 秒」的假断层。
     */
    @Volatile private var prevEndedAtMs = 0L

    // ------------------------------------------------------------------ 配置

    /**
     * 模型放在 `getExternalFilesDir(null)/tts/zv/` —— **不打进 APK**,
     * 和 `asr/`、`models/` 一个惯例(可删:删掉只是嗓子退回系统那个,
     * 她本人还在)。
     *
     * ★★ 2026-10-07:**两份嗓子都留在盘上,只是这一行指着哪一份。**
     *
     * | 目录 | 是什么 | 谁在跑 |
     * |---|---|---|
     * | `tts/zv/` | **ZipVoice 克隆音**(他那段参考音频) | ★ 现在指这里 |
     * | `tts/eula/` | 老的 VITS(804 个音色,原来的御姐音) | 一个字都没动,还在盘上 |
     *
     * 这么摆是为了两件事:① 同一句话两个引擎各念一遍,耳朵当场比;
     * ② 想回退**只要改这一行**,不用重新编译别的东西。
     * (代价:手机暂时多占 ≈195MB。要清就在盘上删 `tts/eula/`。)
     *
     * ★ 走哪一套**不靠这一行硬判,靠盘上摆的是哪一套零件**(见 [engineOf])——
     *   所以这一行指回 `tts/eula` 时,老的那条路原样复活,不是「指过去但代码不认」。
     */
    private fun modelDir(ctx: Context) = File(ctx.getExternalFilesDir(null), "tts/zv")

    private const val ENGINE_VITS = "vits"
    private const val ENGINE_ZV = "zv"

    /** 换嗓子只要改这个目录名(`eula` 是用户自己挑的御姐音)。 */
    private const val MODEL_ONNX = "model.onnx"
    private const val LEXICON_FILE = "lexicon.txt"
    private const val TOKENS_FILE = "tokens.txt"

    // —— ZipVoice(克隆音)那一套零件 ——
    //
    // ★ 它和 VITS 是**两种形状的模型**,不是一个模型的两种配置:
    //   VITS 一次前馈出波形;ZipVoice 是「4 步 flow 从噪声里迭代出一个说话人特征」,
    //   所以它必然慢,而且有个**约 2~4 秒的固定开销**(和句子多长关系很小)。
    //   那个开销的真身就是下面这两件:参考音频要现编码一遍、flow 要迭代 4 次。
    // ★★ 那套零件有**两个版本**,而它们**只差一个文件名**:
    //   int8 那份带 `.int8`,fp32 那份**不带** —— 上游的包就是这么命名的,不是我们起的。
    //   所以「用哪一版」**不能写死在这些常量里**,得看盘上摆的是哪一套。
    //
    // ★ 字面量本身放在 [ZvPickMath] 里,这里只是别名 —— 那两个名字**错一个字母
    //   就是「她说不出话」**,所以它们得能被 JVM 单测钉住,而 private const 钉不住。
    private const val ZV_ENCODER = ZvPickMath.INT8_ENCODER
    private const val ZV_DECODER = ZvPickMath.INT8_DECODER
    private const val ZV_ENCODER_FP32 = ZvPickMath.FP32_ENCODER
    private const val ZV_DECODER_FP32 = ZvPickMath.FP32_DECODER

    // ★ 两个版本**共用**这一个 vocoder:上游的 fp32 包里**根本没有它**
    //   (那份 `vocos_24khz.onnx` 是单独发的),所以换版本时这个文件一个字都不用动。
    private const val ZV_VOCODER = "vocos_24khz.onnx"

    /** `espeak-ng-data/` 是**目录**不是文件,单独判 —— 见 [missingPieces]。 */
    private const val ZV_LANG_DIR = "espeak-ng-data"

    /** 参考音频 + 它念的是什么。换一个人的声音 = 换这两个文件。 */
    private const val REF_WAV = "ref.wav"
    private const val REF_TXT = "ref.txt"

    /**
     * flow 迭代几步。**用户拍的板:4 步。**
     *
     * 电脑上实测(24000Hz):4 步 ≈ 2.6 秒、8 步 ≈ 8.4 秒,差一倍多,
     * 而 4 步那一版他自己听过、认了(「音色还是非常的好的」)。
     * ★ 这个数是**质量换时间**的那一格,要调就调它,别去动线程数。
     */
    private const val ZV_STEPS = 4

    /**
     * ★★ ZipVoice 的 `speed` 在这一版 sherpa-onnx(1.13.8)**是个雷**。
     *
     * 2026-10-07 在电脑上逐档实测(电脑和手机是**同一个版本号**,
     * 所以这一行是**代理结论里最硬的一条**):
     *
     * | speed | 结果(同一句「在呢,说吧。」) |
     * |---|---|
     * | 0.80 | 2.40 秒(离谱地长) |
     * | 1.00 | **0.70 秒** ✓ |
     * | 1.05 | 0.38 秒(短了快一半) |
     * | 1.10 | 0.10 秒(**基本没声**) |
     * | **1.15 / 1.20** | ★★ **段错误,进程当场没** |
     *
     * 它**不是「快一点慢一点」**,是一条会走到崩溃的陡坡。
     * 而 native 的段错误 `catch(Throwable)` **拦不住** ——
     * 和文件头那条 `generateWithCallback` 是**同一个死法**(进程直接消失)。
     *
     * → 所以这里**钉死 1.0**,不把用户那个语速滑块透传下去。
     *   ★ 代价说白:**克隆音这一版,语速滑块没有作用**(见 [warnSpeedOnce])。
     *     这是已知的、有数字支撑的取舍,不是漏做。
     */
    private const val ZV_SPEED = 1.0f

    /**
     * 盘上摆的是哪一套零件。
     *
     * ★ 判据是**零件**,不是那个目录名 —— 这样把 [modelDir] 指回 `tts/eula`
     *   时老那条路原样复活,不用改第二处。
     * ★ 这里用 `exists()` 是**安全的**:调用它的两条路([missingPieces] / [load])
     *   都会先过 [VoiceFiles.blockedAt] —— 目录进不去时那一句就返回了,
     *   轮不到这里,所以此刻的 `exists()` 说的是真话。
     * ★ 四个名字里**认出一个就算 ZipVoice** —— 两个版本都算(见上面那段常量注释)。
     */
    private fun engineOf(d: File): String =
        if (File(d, ZV_ENCODER).exists() || File(d, ZV_DECODER).exists() ||
            File(d, ZV_ENCODER_FP32).exists() || File(d, ZV_DECODER_FP32).exists()
        ) ENGINE_ZV else ENGINE_VITS

    /**
     * 这次那两个模型文件摆的是哪一版 —— **问盘在这里,判断在 [ZvPickMath]**。
     *
     * ★ 只问一次、结果用来拼路径(见 [zvNames]),所以「问盘」和「用哪一套」
     *   在**同一趟装载里**不可能对不上。盘上那两份文件可以在跑的时候被换掉
     *   (他推文件换嗓子),但那要下一次 [load] 才生效 —— 这正是 [engineKind] 那条 KDoc 的道理。
     */
    private fun zvVariant(d: File): String = ZvPickMath.pick(
        fp32Complete = File(d, ZV_ENCODER_FP32).exists() && File(d, ZV_DECODER_FP32).exists(),
        int8Complete = File(d, ZV_ENCODER).exists() && File(d, ZV_DECODER).exists(),
        anyFp32 = File(d, ZV_ENCODER_FP32).exists() || File(d, ZV_DECODER_FP32).exists(),
    )

    /** 这一版的两个模型文件名(int8 带 `.int8`、fp32 不带)。**全项目只有这一处拼它们。** */
    private fun zvNames(d: File): Pair<String, String> {
        val v = zvVariant(d)
        return ZvPickMath.encoderName(v) to ZvPickMath.decoderName(v)
    }

    /** 齐了才敢去 `new OfflineTts`。顺序就是日志里报错的顺序。 */
    private fun neededFor(d: File): List<String> {
        if (engineOf(d) != ENGINE_ZV) {
            return listOf(MODEL_ONNX, LEXICON_FILE, TOKENS_FILE)
        }
        val (enc, dec) = zvNames(d)
        return listOf(enc, dec, ZV_VOCODER, TOKENS_FILE, LEXICON_FILE, REF_WAV, REF_TXT)
    }

    /**
     * VITS 的合成线程数。和耳朵一样取 2 ——
     * 手机满级会热降频,线程开多了是**加热**不是加速(见 `phone-heat-cost-of-battery-runs`)。
     */
    private const val NUM_THREADS = 2

    /**
     * 音频缓冲:取 `minBufferSize` 的 2 倍,再兜一个 0.4 秒的下限。
     *
     * ★ 下限不是给「合成快不快」用的 —— 合成早就结束了,现在是一次性拿到整段音频。
     *   它买的是**写的时候的余量**:我们是一块一块往 track 里写的(见 `LEVEL_CHUNK`),
     *   缓冲只够放 40ms 的话,中途只要有一次调度抖动就 **underrun(爆音)**。
     *   宁可多 200ms 延迟 —— 她说话不是打电话,延迟听不出来,爆音一听就出来。
     */
    private const val MIN_BUF_SECONDS = 0.4f

    // ------------------------------------------------------------------ 对外

    /**
     * 嗓子缺什么。**空 = 齐了。**
     *
     * ★★ 它替掉的是老的 `filesPresent` —— 那个版本用 `isFile && length() > 0`,
     *   而这两句在**「文件在、但 app 进不去这个目录」**时都返回 false,
     *   于是「读不到」被说成了「不在」。**这两种病的修法完全不同**
     *   (推文件 vs `chmod`),分不清就等于这一行日志在把人支使去干错事。
     *   判定本体在 [VoiceFiles],那里有完整的来龙去脉。
     *
     * ★ 报的是**绝对路径**:他要知道往哪儿放、或者该 chmod 谁。
     */
    fun missingPieces(ctx: Context): List<String> {
        val top = ctx.getExternalFilesDir(null) ?: return listOf("(这台机器上没有外部文件目录)")
        val dir = modelDir(ctx)

        // ★ 必须先过这一道,再问 [engineOf] —— 见它 KDoc 里那条「先决条件」。
        //   反过来的话,「进不去这个目录」会被读成「零件不在」,于是判成 VITS,
        //   报出来的缺件清单**全是假的**。
        VoiceFiles.blockedAt(top, dir)?.let { return listOf(it) }

        val missing = VoiceFiles.problems(top, dir, neededFor(dir))

        // ZipVoice 那份多一个 `espeak-ng-data/` —— 它是目录,单独判。
        if (engineOf(dir) == ENGINE_ZV) {
            val lang = File(dir, ZV_LANG_DIR)
            VoiceFiles.reasonForDir(
                lang.absolutePath,
                lang.exists(),
                lang.canRead(),
                lang.canExecute(),
                lang.list()?.size ?: 0,
            )?.let { return missing + it }
        }
        return missing
    }

    /** 文件齐了没有。**齐了不代表能装载**(那要真去 new 一下)。 */
    fun filesPresent(ctx: Context): Boolean = missingPieces(ctx).isEmpty()

    /** 引擎好了没有。**没好的时候调用方走系统 TTS 兜底,不是干等。** */
    fun isReady(): Boolean = state == State.READY

    /**
     * 这份模型里**有多少个嗓子**。这台机器上是 804 个。
     *
     * ★ 引擎没就绪时回 **0**,而 0 的意思是「**还不知道**」,不是「一个都没有」——
     *   试听页要据此说「嗓子还没装好」,而不是画出「第 0 号 / 共 0 个」。
     *   这两者在界面上长得几乎一样,但一个能点、一个点了不会有任何反应。
     */
    fun speakerCount(): Int = try { engine?.numSpeakers() ?: 0 } catch (_: Throwable) { 0 }

    /**
     * 后台把模型装进内存。
     *
     * 和 `Ear.warmUp` 同一个理由:**别让「他问的第一句话」去付装载的钱**。
     * 耳朵那边实测过一次 3047ms 的冷装载,那 3 秒里麦克风一个字节都没进 ——
     * 嗓子这边同理,只是表现成「她第一句话的语气不对」(退回系统嗓子)。
     *
     * 幂等:已经在装 / 装好了 / 装失败了,都直接返回。
     */
    fun warmUp(ctx: Context) {
        if (state != State.IDLE) return
        val app = ctx.applicationContext
        state = State.LOADING
        worker.execute { load(app) }
    }

    /**
     * 念一句。
     *
     * @param speed 语速倍率(1.0 = 原速)。来自用户在房间里调的那个滑块。
     * @param onLevel 每合成出一块就回调一次**归一化响度 0..1** —— 她的嘴张多大。
     *   从**正要播出去的那块音频**上算出来的,所以嘴和声音天生同步,
     *   不用去猜「现在念到第几个字了」。
     * @param onStart ★★ **声音真的出来了那一刻**才响一次 —— 不是「交给嗓子的那一刻」。
     *
     *   整句合成要好几秒(这台机器实测 **≈1 倍实时**:7.5 秒的话要 8 秒才合成完),
     *   而那几秒里**一个字节的声音都没有**。把「她开口了」的视觉和状态摆在那之前,
     *   得到的正是用户 2026-10-05 报的「**嘴巴动,没声音**」——
     *   而它不报任何错,日志上看一切正常。
     *
     *   → 要张嘴、要亮光晕、要开始算「她在说」,都挂在这儿。
     *
     *   ★ 它**可能一次都不来**(合成失败 / 中途被 [stop])。那正好是对的:
     *     没出过声,就不该有谁摆出「她在说」的样子。
     *     **别拿它当配对信号** —— 配对的那个是 [onDone],而且只有 [onDone]。
     * @param onDone **一定会来、而且只来一次**(除非中间有人 [stop])。见类头契约。
     * @param queue 她正在说的时候又来一句:`true` = 排在后面(QUEUE_ADD),
     *   `false` = **顶掉**正在说的那句(悬浮球那条路要的就是这个)。
     *
     * @return 受理了没有。`false` = 这条路现在用不了(模型没装 / 没就绪 / 装载失败),
     *   **调用方必须自己走兜底**,而且**不要指望 `onDone` 会来** ——
     *   这里没受理就没有回调,不装那个假。
     */
    fun speak(
        ctx: Context,
        text: String,
        speed: Float = 1.0f,
        onLevel: ((Float) -> Unit)? = null,
        onStart: (() -> Unit)? = null,
        onDone: () -> Unit,
        queue: Boolean = true,
    ): Boolean {
        if (!isReady()) return false
        val body = text.trim()
        if (body.isEmpty()) { onDone(); return true }
        if (!queue) stop()                      // 顶掉 = 先停(顺带作废旧回调)
        val id = seqGen.incrementAndGet()
        val app = ctx.applicationContext
        // ★★ 交出去的那一刻 —— 和「真的轮到它」之间的差,就是**排队**。
        //   ★ 这一格是分开「模型吐得晚」和「合成慢」的**唯一**判据:
        //     交得早却轮得晚 = 前面在念、合成线程被占着(→ 该做双线程流水线);
        //     交得本来就晚 = 模型还没吐出来(→ 改流水线一点用都没有)。
        val submittedAtMs = System.currentTimeMillis()
        // ★ 我属于哪一代 —— 见 [gen]。**必须在递进去之前定下来**,而且和欠账 +1
        //   在同一个锁里(见 [submitLock])。
        val myGen: Int
        synchronized(submitLock) {
            myGen = gen.get()
            pending.incrementAndGet()
        }
        // ★★ 销账口 —— 这一块**整条命走完**(合成失败 / 被作废 / 念完)才准叫,而且只叫一次。
        //
        //   ⚠️ 它**不能**挂在 [synthOne] 的 finally 上:合成完了只是「交付给播放线程」,
        //     那块音频**还没出声**。在那儿销账的话,会有一段窗口是
        //     `pending == 0` 而下一块正要开口 —— 半双工闸当场放行,麦克风开在她的声音前面。
        //     那正是 2026-10-05 那条自问自答,换个门再进来一次。
        //
        //   ★ 只有**还属于当前这一代**的活儿才真减。属于被作废那一代的,
        //     它的账在 [stop] 那一刻就一笔勾销了 —— 让它再减一次会把计数器压成
        //     **负数**,而负数会被半双工闸读成「她没在说」。
        // ★★ 「`onDone` 一定会来,而且**只来一次**」—— 类头那条契约。
        //
        //   从前它靠人读代码保证:每一条提前收工的出口都**只 return**,由 `finally` 统一兑现。
        //   现在一句被拆成**两条线程、四段命**(合成 / 入队 / 出声 / 销账),出口从 4 个变成 9 个,
        //   靠读是读不住的 —— 所以这一格和下面的 `settle` 一样,**用 CAS 把「只来一次」做成机制**,
        //   不靠调用方自觉。
        //
        //   ★ 它同时堵住反方向那个更贵的错:**漏一次**。漏掉 `onDone` 的表现是
        //     半双工闸永不放行 → 她说完整句之后麦克风再也不开 → 「她不理我了」,零报错。
        val doneFired = AtomicBoolean(false)
        val finish: () -> Unit = {
            if (doneFired.compareAndSet(false, true)) onDone()
        }
        val settled = AtomicBoolean(false)
        val settle: () -> Unit = {
            // ★ 「只准一次」这条**由这里强制**,不靠调用方自觉 —— 现在有三条路会叫它
            //   (合成失败 / 播放线程念完或丢弃 / 外层兜底),靠自觉迟早会算错一次。
            if (settled.compareAndSet(false, true)) {
                synchronized(submitLock) {
                    if (myGen == gen.get()) pending.decrementAndGet()
                }
            }
        }
        worker.execute {
            try {
                synthOne(app, id, myGen, body, speed, submittedAtMs, onLevel, onStart, finish, settle)
            } catch (t: Throwable) {
                // [synthOne] 自己把能预见的失败路都收干净了(见它那个 `giveUp`)——
                // 这一层是**最后的保险**:绝不让一记异常把「销账」和「兑现」一起吞掉。
                trace(app, "嗓子: 合成这一层抛了(${t.javaClass.simpleName}: ${t.message})")
                // ★ 只有**还属于当前这一代**才兑现:被 [stop] 作废的那一代不许再回调,
                //   它会在新一轮里去开麦克风(类头契约:「一定会来……除非中间有人 stop」)。
                if (myGen == gen.get()) finish()
                settle()
            }
        }
        return true
    }

    /**
     * 闭嘴。**不会**兑现 `speak` 那个回调 —— 见类头契约。
     *
     * ★ 两步都要做:作废编号(让正在合成的那条线程安静退出)、
     *   并且**立刻把 `speaking` 放下** —— 半双工闸读的是它,
     *   拖到合成线程真的退出才放手的话,麦克风会白关几百毫秒。
     */
    fun stop() {
        // ★★ 先作废**整队**,再谈别的 —— 见 [gen]。
        //   只把 `currentSeq` 归零是**不够的**:它只作废正在念的那一句,
        //   而队列里排在后面的那些 Runnable 照样会一个一个跑下去、
        //   每跑一个又把 `currentSeq` 立成自己的号 —— 于是她照念。
        //   用户报的就是这个:「我这边都到第三轮对话,她还在留着第一轮对话」。
        //
        // ★ 欠账同时清零:作废之后**不会再有第二个字节出声**(它们全死在上一代),
        //   所以「嗓子里还剩几句」的诚实答案是 **0** —— 麦克风该立刻能开,
        //   不用等那条卡在 `tts.generate()` 里的线程(它可能还要十几秒才退出来)。
        synchronized(submitLock) {
            gen.incrementAndGet()
            pending.set(0)
        }
        // ★★ 还要**把已经合成好、排在播放线程门口**的那些丢掉。
        //   只作废世代号是不够的:它们不需要再合成,`synthOne` 里那道闸拦不住它们 ——
        //   播放线程会一块一块照念,而 `stop()` 的承诺是「不会再有第二个字节出声」。
        //   ★ 每一块都照样叫一次 `settle()`(它内部有世代号守卫,作废之后是空操作):
        //     账本在 `stop()` 里已经清零了,这里只是不让**销账**这条不变量有例外。
        while (true) {
            val dropped = clips.poll() ?: break
            dropped.settle()
        }
        // ★ 「她当场闭嘴」:让正在写的那个 track 立刻停。没有这一下,
        //   `stop()` 要等她缓冲里那 0.4 秒(见 MIN_BUF_SECONDS)播完才生效。
        try { playTrack?.pause() } catch (_: Throwable) {}
        try { playTrack?.flush() } catch (_: Throwable) {}
        currentSeq = 0
        level = null
        speaking = false
        // ★ 量具也跟着归零:新的一轮从这儿划线。不归零的话下一轮第一句
        //   会报一段「上一句念完到这一句开头隔了 40 秒」的**假断层**,
        //   而那正是我们要找的东西 —— 假的那条会把真的淹掉。
        prevEndedAtMs = 0L
    }

    /** 她现在是不是**真的在出声**(不是「交给嗓子了」——见 [speaking] 的注释)。 */
    fun isSpeaking(): Boolean = speaking

    /**
     * ★★ 嗓子里**还欠着几句话** —— 含正在合成、还没轮到出声的那些。
     *
     * 半双工闸必须**同时**看它和 [isSpeaking] —— 见 [pending] 和
     * [EarSessionMath.herVoiceFinished]。只看 `isSpeaking` 就是用户报的那条:
     * 「她还没说完,系统就误认为她说完,就打开麦克风,然后她就自己跟自己聊起来了」。
     */
    fun pendingCount(): Int = pending.get()

    // ------------------------------------------------------------------ 里面

    private fun load(app: Context) {
        val d = modelDir(app)
        val model = File(d, MODEL_ONNX)
        val lexicon = File(d, LEXICON_FILE)
        val tokens = File(d, TOKENS_FILE)
        val t0 = System.currentTimeMillis()
        try {
            // ★ 报**具体原因**(不在 / 读不到 / 空文件),不是一句「不在」——
            //   2026-10-05 就是被那句假话支使着去找了一个明明在盘上的模型。
            val miss = missingPieces(app)
            if (miss.isNotEmpty()) {
                state = State.FAILED
                why = miss.joinToString("、")
                trace(app, "嗓子: $why —— 她先用系统那个嗓子(中文会念错调)")
                return
            }
            // ★ ruleFsts 只有在文件真的在的时候才传:路径不存在时 sherpa 构造会失败,
            //   而那会把「手机号读得好听一点」这种小事升级成「嗓子整个不能用」。
            //   ★ 2026-10-07 在电脑上验过:ZipVoice 前端**也吃** phone.fst
            //     (带它构造成功、电话串合成成功),所以这条路对它一样成立。
            val phoneFst = File(d, "phone.fst")
            val rules = if (phoneFst.isFile && phoneFst.length() > 0) phoneFst.absolutePath else ""

            val kind = engineOf(d)
            engineKind = kind
            // ★ 这一版是 int8 还是 fp32 —— **两个版本的参数形状完全一样**
            //   (只有文件名和权重精度不同),所以它不进 [engineKind] 那个缓存字段,
            //   只用来拼路径 + 在日志里说清这一趟装的是哪一份。
            val variant = if (kind == ENGINE_ZV) zvVariant(d) else null
            val base = OfflineTtsModelConfig(
                numThreads = NUM_THREADS,
                provider = "cpu",
            )

            val e = if (kind == ENGINE_ZV) {
                // ---- ZipVoice:克隆音 ----
                // ★ 先把这一版的两个文件名定下来,后面拼路径只用它俩 ——
                //   这样「挑哪一版」和「读了哪个文件」在这一趟里是**同一个答案**。
                val (encName, decName) = zvNames(d)

                // ★ 参考音频是**整段装进内存**的 —— 它每次合成都要用,
                //   而且 sherpa 是逐样本读的,给它一个懒加载的 getter 没有意义。
                val (ref, rate) = readReference(d)
                refAudio = ref
                refRate = rate
                refText = File(d, REF_TXT).readText(Charsets.UTF_8).trim()

                val cfg = OfflineTtsConfig(
                    model = base.copy(
                        zipvoice = OfflineTtsZipVoiceModelConfig(
                            tokens = tokens.absolutePath,
                            encoder = File(d, encName).absolutePath,
                            decoder = File(d, decName).absolutePath,
                            vocoder = File(d, ZV_VOCODER).absolutePath,
                            dataDir = File(d, ZV_LANG_DIR).absolutePath,
                            lexicon = lexicon.absolutePath,
                        ),
                    ),
                    ruleFsts = rules,
                )
                val one = OfflineTts(config = cfg)
                trace(
                    app,
                    "嗓子: 克隆音就绪【$variant】(${System.currentTimeMillis() - t0}ms / " +
                        "${one.sampleRate()}Hz / " +
                        "参考 ${ref.size} 点 @ ${rate}Hz = " +
                        String.format("%.2f", ref.size.toFloat() / rate) + " 秒)"
                )
                trace(app, "嗓子: 参考文本「${refText?.take(40)}」")
                one
            } else {
                val cfg = OfflineTtsConfig(
                    model = base.copy(
                        vits = OfflineTtsVitsModelConfig(
                            model = model.absolutePath,
                            lexicon = lexicon.absolutePath,
                            tokens = tokens.absolutePath,
                        ),
                    ),
                    ruleFsts = rules,
                )
                val one = OfflineTts(config = cfg)
                trace(
                    app,
                    "嗓子: 就绪(${model.length() / 1048576}MB / ${System.currentTimeMillis() - t0}ms / " +
                        "${one.sampleRate()}Hz / ${one.numSpeakers()} 个音色)"
                )
                one
            }
            engine = e
            state = State.READY
            // ★ 报清**是哪一份** —— 「我换了它没变」是换装最容易出的错,
            //   而那种错一个字都不报。这一行是它唯一的答案。
            //   ★ 版本(【fp32】/【int8】)也要报:推了 348MB 上去、听起来没变,
            //     第一个要回答的问题就是「她到底装的是哪一份」—— 而这一行说了算。
            trace(app, "嗓子: 用的是 $kind${variant?.let { "【$it】" } ?: ""}(${d.absolutePath})")
            // ★ 参考音频读成什么样,单独一行。读成功也可能**读错**(见 [RefVoiceMath])——
            //   而读错的表现是她一开口是噪音,不是报错。这几个数是唯一能对得上的凭据。
            refWhy?.let { trace(app, "嗓子: 参考音频 $it") }
        } catch (t: Throwable) {
            // ★ 不吞异常:吞掉的话症状是「她不理我」,而日志里什么都没有。
            state = State.FAILED
            why = "${t.javaClass.simpleName}: ${t.message}"
            trace(app, "嗓子: 装载失败(${why}) —— 她先用系统那个嗓子(中文会念错调)")
        }
    }

    /**
     * 读参考音频。**坏了就抛** —— 让 [load] 那个 `catch(Throwable)` 接住。
     *
     * ★ 这里**故意不返回 null 再在别处慢慢处理**:参考音频坏了 = 克隆音这条路
     *   根本走不通,而「照常合成、只是声音不对」比「整个退回系统嗓子」危险得多
     *   (前者听起来像模型不行,后者一眼看得见)。
     *
     * ★ `refWhy` 是给日志的一句话:读成了什么样(几个点、多少 Hz)。
     *   它和「读失败」是两件事 —— 读成功也可能是**读错了**(见 [RefVoiceMath] 那张表)。
     */
    private fun readReference(d: File): Pair<FloatArray, Int> {
        val f = File(d, REF_WAV)
        val bytes = f.readBytes()
        return when (val p = RefVoiceMath.parse(bytes, bytes.size)) {
            is RefVoiceMath.WavParse.Bad -> throw IllegalStateException("参考音频读不出来:${p.why}")
            is RefVoiceMath.WavParse.Ok -> {
                val mono = RefVoiceMath.toMono(p.info, bytes, bytes.size)
                if (mono.isEmpty()) throw IllegalStateException("参考音频解出来是空的")
                refWhy = "${p.info.sampleRate}Hz / ${p.info.channels} 声道 / ${p.info.bitsPerSample}bit"
                return mono to p.info.sampleRate
            }
        }
    }

    /**
     * **半条命:把字变成音频,然后交给出声那条线程。**
     *
     * 跑在 [worker](`her-voice`)上。它**不出声** —— 出声是 [playClip] 的事,
     * 跑在 [player](`her-play`)上。这一刀正是治「说话断断续续」的那一刀,
     * 账和量到的数字都在 [clips] 那段注释里。
     *
     * ## 它和老 `runOne` 的区别(★ 别把老毛病改回来)
     *
     * 老版是「合成 → 出声 → drain」**串在一条线程上**,所以排队的下一块
     * **连合成都不能开始**,一直要等前一块播完。量出来:每处接缝中位静默 **2476ms**。
     *
     * ## 三条出口的规矩(★ 每一条都必须把两件事都办完)
     *
     * | 出口 | `onDone` | `settle` | 为什么 |
     * |---|---|---|---|
     * | 被 [stop] 作废(世代号不对) | **不兑现** | **要销账** | 调用方已经明确不要这句了;但账得还,否则麦克风永远关着 |
     * | 合成不出来(引擎没了 / 采样率读不到 / generate 抛了 / 回来是空的) | 兑现 | 销账 | 这一句永远不会出声,等它的人不能干等 |
     * | 交给出声线程了 | **由 [playClip] 兑现** | **由 [playLoop] 销账** | 合成完不等于出声了 —— 见 [clips] |
     *
     * ★★ 第一行那个「不兑现 `onDone`」是**故意的**,别"顺手补上":
     *   类头契约写的是「一定会来,而且只来一次(**除非中间有人 stop**)」。
     *   作废之后还兑现,等于在新一轮里放一次旧回调 —— 它会去开麦克风。
     *
     * @param submittedAtMs 这一句交给嗓子的时刻(**不是**轮到它的时刻)——
     *        两者的差就是排队时间,那个差是分开「合成慢」和「模型吐得晚」的唯一钥匙。
     */
    private fun synthOne(
        app: Context,
        id: Int,
        myGen: Int,
        text: String,
        speed: Float,
        submittedAtMs: Long,
        onLevel: ((Float) -> Unit)?,
        onStart: (() -> Unit)?,
        onDone: () -> Unit,
        settle: () -> Unit,
    ) {
        // ★ 抬优先级失败那次只报一回 —— 见 [audioPriority]。放在最前面:
        //   它管的是**这一整条线程**,和这一句作不作废无关(所以排在下面那道世代号闸之前)。
        priorityWhy?.let { trace(app, it); priorityWhy = null }

        // ★★ 先看这一件还作不作数 —— 见 [gen]。**必须排在第一条提前 return 之前**,
        //   否则被 [stop] 作废的那些句子会在打断之后一句一句接着念出来
        //   (「到第三轮还在说第一轮」)。这里**不兑现回调**:它已经被取消了,
        //   兑现反而会在新一轮里去开麦克风。**但要销账** —— 见上面那张表。
        if (myGen != gen.get()) {
            trace(app, "嗓子: 这一句在开始合成之前就被叫停了(第 $myGen 代),不念")
            settle()
            return
        }

        // ★ 「这一句造不出来」的统一出口:说清原因 + 兑现 + 销账。
        //   三条路共用一个,免得以后再加一条出口时漏掉其中一件 ——
        //   漏 `onDone` = 等它的人干等 90 秒;漏 `settle` = 麦克风永远关着。
        //   **两种都不报错。**
        fun giveUp(why: String) {
            trace(app, why)
            onDone()
            settle()
        }

        val tts = engine
        if (tts == null) { giveUp("嗓子: 引擎没了(被卸载或还没装好),这一句跳过"); return }

        val rate = try { tts.sampleRate() } catch (_: Throwable) { 0 }
        if (rate <= 0) { giveUp("嗓子: 采样率读不到,这一句跳过"); return }

        // ★★ 用第几个嗓子:还是**夹进这份模型真的有的范围**,但不再读 prefs 了。
        //
        //   2026-10-06 他拍板:「**音色不要选了,就第一次系统自带的那个音色,我觉得
        //   蛮好听的**」—— 所以回到**写死第 0 号**(挑声音那个面板也一起收起来了,
        //   见 [ConMarnActivity.showSettings] 里那段注释)。
        //
        //   ★ 为什么不只是把界面上那一行藏掉、还非得动这里:盘上**已经存着 801 号**
        //     (他那天进去试出来的)。只收入口的话,他再也换不回去,而她一张嘴
        //     还是那个他不想要的嗓子 —— 界面说一套、耳朵听一套,正是这个项目最恨的
        //     那种对不上,而且**两边都不报错**。
        //
        //   ★ 夹那一步(以及 `clampedFrom` 那条提示)**留着,不是给「挑声音」用的**:
        //     它管的是「号越界会一路走进 native,死法是她不出声、或者整个进程 abort」
        //     —— 那两种都不报错,所以判据必须在 VoiceMath.clampSpeaker 里待着(有单测)。
        //   ★ 以后真要放开挑声音:把下面 `want` 那行换回读 prefs,别的地方一行都不用动。
        val (speaker, clampedFrom) = try {
            val want = ConMarnActivity.DEF_SPEAKER
            val n = tts.numSpeakers()
            val got = VoiceMath.clampSpeaker(want, n)
            got to (if (got != want) want else null)
        } catch (_: Throwable) { ConMarnActivity.DEF_SPEAKER to null }

        if (clampedFrom != null) {
            // ★ 必须说出来:他上次挑的是 500 号,换了个模型之后那个号不存在了。
            //   不说的话,他听到的是「另一个人」而完全不知道发生过什么。
            trace(app, "嗓子: 存着的第 $clampedFrom 号嗓子这份模型里没有,先用第 $speaker 号")
        }

        // ★★ 他按了语速滑块,而这版克隆音**不听它的** —— 必须说出来。
        //   不说的话,他听到的是「调了没反应」,而那看起来像 bug,不像取舍。
        //   ★ 只报一次:这句话对**整段会话**都是同一个答案,每句都报会把日志刷爆。
        if (engineKind == ENGINE_ZV && speed != ZV_SPEED && !speedTold) {
            speedTold = true
            trace(
                app,
                "嗓子: 克隆音这一版**语速滑块不起作用**(钉死在 ${ZV_SPEED});" +
                    "再往上调会让进程当场崩,不是它不听你的"
            )
        }

        val genAtMs = System.currentTimeMillis()
        val audio = try {
            if (engineKind == ENGINE_ZV) {
                // ---- ZipVoice:要带上「参考音频 + 它念的是什么」才认得出是谁在说 ----
                //
                // ★★ `speed` **故意不透传**,钉死 [ZV_SPEED] —— 那不是保守,是保命:
                //   1.15 / 1.20 会让 native **段错误**、整个进程当场没,`catch(Throwable)`
                //   拦不住。表在 [ZV_SPEED] 的 KDoc 里。★ 用户按一次语速滑块就会踩到。
                //
                // ★ `sid` 也钉 0:这份模型只有**一个**说话人
                //   (`num_speakers == 1`,2026-10-07 在电脑上量的),那个 0 是「就是她」。
                //   ★ 别改成 `speaker`:那是 VITS 才会有的多音色编号,在 ZipVoice 上没有意义。
                val cfg = GenerationConfig(
                    speed = ZV_SPEED,
                    sid = 0,
                    referenceAudio = refAudio,
                    referenceSampleRate = refRate,
                    referenceText = refText,
                    numSteps = ZV_STEPS,
                )
                tts.generateWithConfig(text, cfg)
            } else {
                tts.generate(text, speaker, speed)
            }
        } catch (t: Throwable) {
            giveUp("嗓子: 合成失败(${t.javaClass.simpleName}: ${t.message})")
            return
        }
        val samples = audio.samples
        if (samples.isEmpty()) {
            giveUp("嗓子: 合成回来是空的(「$text」)")
            return
        }

        // ★★ 2026-10-05 晚:**量「中间断一下」** —— 见 [prevEndedAtMs] 那段。
        //
        //   这一行把三个数摆在一起,足够把「她说话中间断一下」拆成两种情况:
        //
        //   | 这一行长什么样 | 说明 | 该怎么修 |
        //   |---|---|---|
        //   | **交得早、轮得晚** | 上一句还在念,而合成线程被 `synthOne` 占着 —— 后面那一块**排在一整句合成后面** | ★ 合成和播放分成两条线程(流水线) |
        //   | **交得本来就晚** | 4B 还没把这半句吐出来,嗓子一直在等米下锅 | 改流水线**一点用都没有**,要动的是切句 |
        //   | 实时倍率 **×1.0 以下** | 一句 5 秒的话要 8 秒才造得出来 | 出声**永远**跟不上,只能换嗓子或加线程 |
        //
        //   ★ `等待` 从**交给嗓子**算起(不是从上一句念完算起)——
        //     后者会把「模型吐得晚」也一起算成排队,那两件事就分不开了。
        val synthMs = System.currentTimeMillis() - genAtMs
        val audioMs = samples.size * 1000L / rate
        val waitedMs = genAtMs - submittedAtMs
        val realtime = if (synthMs > 0) audioMs.toDouble() / synthMs else -1.0
        trace(
            app,
            "嗓子: ★量「${text.take(16)}」合成 ${synthMs}ms / 音频 ${audioMs}ms" +
                "(实时 ${if (realtime > 0) String.format("%.2f", realtime) else "?"}×);" +
                "等待 ${waitedMs}ms"
        )

        // ★ 一句话一条。这条报的是「**造好了**」,不是「开始念了」——
        //   「开始念了」那一声现在由 [playClip] 报,在声音真的出来那一刻。
        trace(app, "嗓子: 造好「${text.take(24)}」(${samples.size} 帧 / ${rate}Hz),交给出声那条线程")

        // ★★ 交出去。**这一行就是流水线的接缝** —— 它一返回,`her-voice` 立刻
        //   可以开始合成下一块,而这一块在 `her-play` 那边等着出声。
        //
        //   队列是**有界的**([clips] = 4):满了这里就阻塞,于是「合成」自动跟着
        //   「出声」的节奏走。这是**背压**,不是缺陷 —— 无界的话,一段长回复
        //   会把几十块音频全造出来堆在内存里(每块几 MB),而手机只有 3GB 可用。
        //
        //   ⚠️ 阻塞期间被 [stop] 叫停是**安全的**:`stop()` 会把队列抽干,
        //   腾出位置让这里返回,然后 [playClip] 的世代号闸把它丢掉。
        try {
            clips.put(Clip(app, id, myGen, text, samples, rate, onLevel, onStart, onDone, settle))
        } catch (t: Throwable) {
            giveUp("嗓子: 这一块交不到出声线程(${t.javaClass.simpleName}: ${t.message})")
        }
    }

    /**
     * 出声那条线程的**一整条命**:取一块 → 念 → 销账 → 再取。
     *
     * ★★ 这里那个 `catch` **不是防御性编程,是承重墙**。
     *
     * `playLoop` 是 [player] 上唯一的任务。它一抛出去,这条线程就**结束了**,
     * 而 `Executors.newSingleThreadExecutor` **不会补一条新的**给已经提交过的任务 ——
     * 于是队列涨到 4 就满,`synthOne` 里的 `put` 永久阻塞,`her-voice` 也跟着死。
     * 症状是:**她从此再也不出声,而日志里一片太平**(最后一块连「出错」都没写)。
     *
     * 所以规则是:**任何一块出错都只丢这一块,循环必须活着。**
     */
    private fun playLoop() {
        while (true) {
            val clip = try {
                clips.take()
            } catch (_: InterruptedException) {
                return                      // 进程要走了,daemon 线程收工
            }
            try {
                playClip(clip)
            } catch (t: Throwable) {
                trace(clip.app, "嗓子: 出声这一块抛了(${t.javaClass.simpleName}: ${t.message}),丢掉接着念下一块")
            } finally {
                // ★ 销账挂在**这儿**、不挂在 [playClip] 里面:它有三条提前 return
                //   (世代号过期 / track 建不起来 / 被叫停),写在里面就会漏。
                //   漏一次的后果是 `pending` 永远大于 0 → 麦克风再也开不了。
                clip.settle()
            }
        }
    }

    /**
     * **另外半条命:把合成好的音频真的念出来。**
     *
     * 跑在 [player](`her-play`)上。它**不合成** —— 见 [synthOne]。
     *
     * ★ `onDone` 的唯一兑现点在下面的 `finally` 里,和老 `runOne` 一样:
     *   提前收工的那几条出口**一律只 return**,自己兑现 = 回调来两下。
     * ★ `settle` 不在这里 —— 在 [playLoop] 的 `finally` 上(那里盖得住所有出口)。
     */
    private fun playClip(clip: Clip) {
        val app = clip.app
        val id = clip.id
        val text = clip.text
        val samples = clip.samples
        val rate = clip.rate
        val onLevel = clip.onLevel
        val onStart = clip.onStart
        val onDone = clip.onDone

        // ★★ 出声之前**再对一次世代号**。
        //
        //   [synthOne] 开头对过一次,但那是**合成之前** —— 合成要花几秒,
        //   这几秒里用户完全可能按了「先让她停」。而 [stop] 抽干队列的那一下
        //   **抽不到正在合成的这一块**(它还没进队),所以它会照样进来。
        //   没有这道闸,「先让她停」之后她**还会再吐出一整块** ——
        //   那正是按住说话最不能接受的延迟。
        if (clip.myGen != gen.get()) {
            trace(app, "嗓子: 这一块造好了但已经过期(第 ${clip.myGen} 代),不念")
            return
        }

        // ★★ 认领这个号 —— 「现在轮到我念了」。**这一行曾经漏了。**
        //
        //   [currentSeq] 全文件只有 `stop()` 往里写过 0(见上面那个字段的声明),
        //   **没有任何地方写过真的号** —— 于是 `alive()` 恒等于 `false`,
        //   而它的后果不是报错,是**一句都念不出来,且每一层看上去都正常**:
        //
        //     · 写音频那个循环**第一圈就 return** → AudioTrack 里一个字节都没有
        //       → **没声音**(而 track.play() 已经调过,连"静音播放"都算不上);
        //     · `finally` 里 `if (alive())` 同样是 false → **`onDone` 永远不来**
        //       → 半双工闸永久不放行 → 他说完一句,麦克风再也回不来;
        //     · `level` 回调一次都没响过,可上面 `setTalking(true)` 已经发出去了,
        //       her.js 自己按时间转嘴 → **她的嘴照动**。
        //
        //   合起来正是他 2026-10-05 报的那句「**嘴巴动,没声音**」——
        //   一个「静默成功」的反面教材:没有一条日志说这里出过事。
        //
        //   ★ 认领点为什么在**这儿**、不在 `speak()` 里:这里才是「轮到我了」。
        //     写进 `speak()` 的话,`queue = true` 排队的那句**一进队就把正在念的那句
        //     作废了** —— 排队当场变成顶掉,而这两件事的区别**全靠这个号**。
        //   ★★ 2026-10-05 晚拆成两条线程之后,「这儿」从老 `runOne` 挪到了 [playClip] ——
        //     判据没变(**真的轮到出声了**才认领),但位置必须跟着出声走。
        //     留在 [synthOne] 里的话,合成第 3 块就会把正在念的第 1 块作废掉。
        currentSeq = id

        var track: AudioTrack? = null
        var written = 0L
        // ★★ 「声音出来了吗」。它翻转的那一刻,才是这一句**真的开始念**——
        //   见下面第一次 write 之后那一段(`speaking` 和 [onStart] 都挂在那儿)。
        var started = false
        // ★ 「这句话还算不算数」。stop() / 被顶掉之后就是 false ——
        //   那时**绝不能**兑现回调(它会在新一轮里去开麦克风)。
        fun alive() = currentSeq == id

        try {
            // ★★ `speaking` **不在这儿立** —— 它的落点在下面第一次 `write` 之后。
            //
            //   立在这儿的话:`speaking = true` 发生在 `track.play()` 和
            //   `tts.generate()` **之前**,而整句合成要好几秒、`track.play()` 那一刻
            //   缓冲里还一个字节都没有。于是这几秒里 `isSpeaking()` 是 true、
            //   调用方的嘴已经张开、光晕已经亮成「在说」—— **而一点声音都没有**。
            //   那正是「嘴巴动,没声音」的一半,而且**不报错**。
            //
            // 谁读音量,由这一句说了算 —— 和 currentSeq 一起作废,
            // 免得上一句的残余把新一句的嘴带着动
            level = onLevel

            val minBuf = AudioTrack.getMinBufferSize(
                rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).let { if (it > 0) it else rate / 4 }
            val bufBytes = maxOf((rate * MIN_BUF_SECONDS).toInt() * 2, minBuf * 2)

            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            // ★★ 记下来,好让 [stop] 能**当场**掐住它。见 [playTrack] 那段:
            //   写循环大多数时间卡在阻塞的 `track.write()` 里,不记这一格的话
            //   「先让她停」要等缓冲里那 0.4 秒播完才生效。
            playTrack = track

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                // ★ 只 trace,**不在这里兑现回调** —— `finally` 是唯一的兑现点。
                //   原来这里是 `onDone(); return`(在 try 里面),`finally` 紧接着
                //   又兑现一次 → 那条路径上 `onDone` **来了两下**,
                //   而类头那条契约写的是「一定会来,而且**只来一次**」。
                //   多出来那一下会让对话循环白开一次麦克风。
                trace(app, "嗓子: AudioTrack 建不起来(状态 ${track.state}),这一句跳过")
                return
            }

            // ★ 用户在房间里调的音高。sherpa 的 VITS 不吃音高参数,
            //   但 AudioTrack 自己能移 —— 不设的话「音色」那一栏会**静默失效**
            //   (她换了嗓子,音高却还是老的,而他调过那个滑块)。
            //   失败就算了:移调是锦上添花,不该把整句拖没。
            try {
                val p = app.getSharedPreferences(ConMarnActivity.PREFS_VOICE, Context.MODE_PRIVATE)
                val pitch = p.getFloat(ConMarnActivity.KEY_PITCH, ConMarnActivity.DEF_PITCH)
                if (pitch > 0f && kotlin.math.abs(pitch - 1f) > 0.01f) {
                    track.playbackParams = track.playbackParams.setPitch(pitch)
                }
            } catch (_: Throwable) { /* 不支持移调就算了 */ }

            track.play()

            // ★★ 2026-10-05 晚:**量「他耳朵里那段空白」** —— 流水线的验收尺。
            //
            //   合成那一侧([synthOne] 里的 `★量`)量的是「造这一块花了多久」,
            //   而这一行量的是**「上一块真的不响了 → 这一块真的出声了」**。
            //   两个数必须一起看:
            //
            //   | 改完之后的样子 | 说明 |
            //   |---|---|
            //   | 出声口 **≈ 0**,合成口还是 2 秒 | ★ **流水线生效了** —— 那 2 秒被藏在前一块播放的时间里 |
            //   | 出声口 **还是 2 秒以上** | 流水线**没生效**,或者这一块属于「合成慢于实时」那 41% |
            //
            //   ⚠️ 改之前这一栏的中位数是 **2476ms**(52 个接缝,最大 25505ms)。
            //     这就是他说「断断续续,这真是个大问题」时,耳朵里实际听到的东西。
            val soundAtMs = System.currentTimeMillis()
            val heardGapMs = if (prevEndedAtMs > 0) soundAtMs - prevEndedAtMs else -1L
            trace(
                app,
                "嗓子: 念「${text.take(24)}」(${samples.size} 帧 / ${rate}Hz);" +
                    "他听到的空白 ${if (heardGapMs in 0..10_000) "${heardGapMs}ms" else "（新的一轮）"}"
            )

            // ★★ 每块的响度**先算好存着**,什么时候报出去由**播放头**说了算。
            //
            //   2026-10-05 之前是「算一块、当场报一块」,而那时候这一块**还没播** ——
            //   它只是刚进了 `AudioTrack` 的缓冲。缓冲有多深,她的嘴就比声音早多久:
            //   这里 `MIN_BUF_SECONDS = 0.4`,所以**嘴最多能早到 400 毫秒**。
            //   用户原话就是这么报的:「**她嘴巴动和声音是不同步的**」。
            //
            //   ★ 它不是「调一个延迟值」能修的:写和播是两条独立的进度,
            //     中间隔着一整条缓冲,而缓冲深度还随设备变(见 [MIN_BUF_SECONDS])。
            //     唯一对的判据是**播放头走到哪儿了** —— 那正是下面 [pumpMouth] 干的事,
            //     也正好和 [drain] 用的是同一个量。
            val mouth = MouthTrack(FloatArray((samples.size + LEVEL_CHUNK - 1) / LEVEL_CHUNK))

            var i = 0
            var chunk = 0
            while (i < samples.size) {
                if (!alive()) {           // 被 stop / 顶掉了:一个字都不写,也不兑现回调
                    trace(app, "嗓子: 第 $i/${samples.size} 帧被叫停(号 $id 已作废),不念了")
                    return
                }
                val n = minOf(LEVEL_CHUNK, samples.size - i)
                val slice = samples.copyOfRange(i, i + n)
                val pcm = VoiceMath.floatToPcm16(slice)
                // 阻塞写:缓冲满了就等播出去,这正是我们要的背压
                track.write(pcm, 0, pcm.size)
                written += n.toLong()
                // ★★ **声音从这一下才算真的开始了。**
                //
                //   上面 `track.play()` 的那一刻缓冲是空的 —— 它只是把设备打开、
                //   等着喂;第一块写进去它才出声。所以「她在说」这个状态、
                //   以及给调用方的 [onStart] 信号,都落在这一行之后。
                //
                //   ⚠️ **别挪到合成之前**(别挪回 `try` 的开头)。挪回去 =
                //     整句合成的那几秒里,她的嘴在动、光晕亮着、`isSpeaking()`
                //     是 true,**却没有声音** —— 这个项目最恨的那种「界面撒谎」。
                if (!started) {
                    started = true
                    // 中间可能刚被人 `stop()` 掉(号已经不是我的了)——
                    // 那时**不认这一声**:认了就会把 `stop()` 刚放下的 `speaking`
                    // 重新立起来,而她已经被叫停了。
                    if (alive()) {
                        speaking = true
                        onStart?.invoke()
                    }
                }
                mouth.levels[chunk] = VoiceMath.mouthLevel(VoiceMath.rms(slice))
                chunk++
                // 写的时候顺手跟一下播放头 —— 长句子在缓冲写满之后**大多数时间卡在
                // 上面那句 `write` 里**,所以这里就是它真正的节拍。整句很短(全进了缓冲、
                // 一句都没卡住)的时候这里只会报出第 0 块,剩下交给 [drain] 慢慢走。
                pumpMouth(track, mouth, onLevel)
                i += n
            }

            if (alive()) drain(app, track, written, id, mouth, onLevel)
        } catch (t: Throwable) {
            // 不吞:内嵌引擎第一次上异步,失败方式比系统 TTS 多得多
            trace(app, "嗓子: 这一句出错(${t.javaClass.simpleName}: ${t.message})")
        } finally {
            try { track?.stop() } catch (_: Throwable) {}
            try { track?.release() } catch (_: Throwable) {}
            // ★ 放手:别留着一条已经 release 的 track 让 [stop] 去 pause 它。
            //   用 `===` 比,不是「非空就清」—— 万一 stop() 之后新一块已经把它
            //   换成了自己的 track,这里清掉就等于**把别人的手放开**。
            if (playTrack === track) playTrack = null
            // ★★ 量具:这一句**真的不响了**的那一刻。下一句用它算「他听到的那段空白」
            //   (见 [prevEndedAtMs])。★ 放在 `alive()` 外面 —— 被顶掉的那句
            //   也是真的不响了,它同样该成为下一句的计时起点。
            prevEndedAtMs = System.currentTimeMillis()
            if (alive()) {
                speaking = false
                level = null
                // ★★ **这里是出声这一侧 `onDone` 唯一的兑现点**,而且只在还算数的时候 ——
                //   类头那条契约就靠这一处。上面几条「提前收工」的出口
                //   (世代号过期 / track 建不起来 / 被叫停)**一律只 return**,
                //   不自己兑现 —— 自己兑现 + 这条 `finally` = **回调来两下**。
                //   而 `finally` 一定会跑,所以那个写法**只会多不会少**,是纯亏。
                //   ★ 合成那一侧的出口(`giveUp`)也兑现,但**它压根到不了这里**
                //     (没进队),所以两边不会撞。真要撞了也有 `doneFired` 那道 CAS 兜着。
                onDone()
            }
        }
    }

    /**
     * 等最后那点音频真的播出去。见类头「为什么要 drain」。
     *
     * 判据是**播放头追上写入帧数**,不是「睡一会儿大概够了」——
     * 后者在热降频的时候会差好几百毫秒,而那正好是自问自答的入口。
     */
    private fun drain(
        app: Context,
        track: AudioTrack,
        written: Long,
        id: Int,
        mouth: MouthTrack,
        onLevel: ((Float) -> Unit)?,
    ) {
        val deadline = System.currentTimeMillis() + drainBudgetMs(written)
        while (System.currentTimeMillis() < deadline) {
            if (currentSeq != id) return                       // 被 stop / 顶掉了
            // ★ 这一段本来就是「等播放头追上写入」—— 顺手把嘴也交给同一个量,
            //   于是**声音在哪儿,嘴就在哪儿**,不用再猜一个延迟值。
            pumpMouth(track, mouth, onLevel)
            // playbackHeadPosition 是**这条 track 自己**的计数(每句一条,从 0 起),
            // 所以不用管它会不会和上一句撞。
            if (track.playbackHeadPosition.toLong() >= written) return
            try { Thread.sleep(10) } catch (_: InterruptedException) { return }
        }
        trace(app, "嗓子: 播放头等了 ${drainBudgetMs(written)}ms 还没追上(${written} 帧),放行")
    }

    /**
     * 一句里每块的响度,外加「报到第几块了」。
     *
     * ★ 它是**一句一份**的:嘴型的进度跟着这一句的播放头走,
     *   换一句就是零,和每句一条 `AudioTrack` 是同一个道理(见类头那段)。
     */
    private class MouthTrack(val levels: FloatArray) {
        var idx = -1
    }

    /**
     * 把**正在播的那一块**的响度报出去。
     *
     * ★★ 判据是 `playbackHeadPosition`,不是「写到第几块了」——
     *   见 [playClip] 里那段「写进去」和「播出来」之间隔着整条缓冲的账。
     *   报重了没意义(嘴不会动),所以 `MouthTrack.idx` 记着上一次报到哪。
     */
    private fun pumpMouth(track: AudioTrack, mouth: MouthTrack, onLevel: ((Float) -> Unit)?) {
        val idx = (track.playbackHeadPosition.toLong() / LEVEL_CHUNK).toInt()
        if (idx == mouth.idx) return
        mouth.idx = idx
        // 播放头偶尔会短暂跑过写入帧数(或者返回负值),越界就什么都不报 ——
        // **报一个假的开合度比不报更糟**:她的嘴会在最后一块上卡住。
        if (idx in mouth.levels.indices) onLevel?.invoke(mouth.levels[idx])
    }

    /**
     * drain 最多等多久 = 音频时长 + 1 秒。
     *
     * 这个 1 秒是**挂死**的判据,不是时长预估:正常情况下播放头一定在
     * 音频放完那一刻追上,多出来的 1 秒只有两个来源 —— 设备卡住,或者
     * 播放头根本没在动(比如音频焦点被抢)。到点就放行,让对话继续,
     * 同时留一行日志 —— **不能因为嗓子卡住把整个对话循环拖死**。
     */
    private fun drainBudgetMs(frames: Long): Long {
        val rate = try { engine?.sampleRate() ?: 22050 } catch (_: Throwable) { 22050 }
        return (frames * 1000L / rate) + 1000L
    }

    private fun trace(app: Context, msg: String) {
        try { ModelManager.get(app).trace(msg) } catch (_: Throwable) {}
    }

    /**
     * 往 `AudioTrack` 写的时候,每块多少帧(22050Hz 下约 46ms)。
     *
     * ★ 它**不是**性能参数,是**嘴型参数**:一块就是一个开合度,所以
     *   块太大 → 她的嘴一帧一个开合度,看着是僵的;块太小 → 每秒几十次
     *   `runOnUiThread`,白烧电。46ms 差不多是人对口型变化的感知下限。
     *
     * ★ 2026-10-05:它现在还有一个身份 —— [`pumpMouth`] 拿它把**播放头**
     *   换算成「正在播第几块」。所以他同时也是嘴追声音的**分辨率**。
     */
    private const val LEVEL_CHUNK = 1024

    /** 装载失败的人话原因(给设置页/日志用)。 */
    fun failureReason(): String? = why
}

/**
 * 盘上那两套 ZipVoice 零件(**int8 / fp32**)这次用哪一套。**纯判断,不碰盘。**
 *
 * ★ 为什么它不是 `SherpaVoice` 里的两行 if:挑错版本的后果**不是报错** ——
 *   是拿一版的文件名去 ack 另一版的路径,于是 `new OfflineTts` 失败,
 *   而日志里只有一行「嗓子: 装载失败」,看起来像是文件没推上去。
 *   所以这条判据必须是**纯逻辑 + JVM 单测**(同 `VoiceMath` / `EarMath` / `RefVoiceMath`)。
 *
 * ★ 2026-10-07 他听完两轮 ABX(14 题答对 12 道,瞎猜碰上的概率 0.0065)
 *   拍板换 fp32。代价 +348MB 常驻、合成慢约 25%,**int8 那一套原样留着当退路** ——
 *   退回去只要把 fp32 那两个文件删掉,不用重推 130MB、也不用卸载重装。
 */
internal object ZvPickMath {
    internal const val FP32 = "fp32"
    internal const val INT8 = "int8"

    // ★ 这两个名字**是真实事实,不是风格**:上游 fp32 那个包里的文件就叫
    //   `encoder.onnx` / `decoder.onnx`,**没有** `.int8`。
    internal const val INT8_ENCODER = "encoder.int8.onnx"
    internal const val INT8_DECODER = "decoder.int8.onnx"
    internal const val FP32_ENCODER = "encoder.onnx"
    internal const val FP32_DECODER = "decoder.onnx"

    /** 这一版的两个模型文件名。**全项目只有这一处拼它们。** */
    internal fun encoderName(variant: String): String =
        if (variant == FP32) FP32_ENCODER else INT8_ENCODER

    internal fun decoderName(variant: String): String =
        if (variant == FP32) FP32_DECODER else INT8_DECODER

    /**
     * ★ 规则只有一条:**成对才认,fp32 优先。**
     *
     * 「成对才认」不是讲究 —— fp32 那两个文件是**先后推到手机上**的
     * (decoder 那一个 477MB,传一会儿)。要是认半套,推文件的那几分钟里
     * 她**一开口就是装载失败 = 没嗓子**;成对才认的话,那几分钟里她照旧用 int8 说话,
     * 推完下一次装载自己就换过去。**半套必须退回整套旧的,绝不赌一把。**
     *
     * @param fp32Complete 盘上 `encoder.onnx` + `decoder.onnx` **都在**吗
     * @param int8Complete 盘上那两个带 `.int8` 的**都在**吗
     * @param anyFp32 盘上有**任何一个** fp32 文件吗(半套也算)。两套都不全时靠它
     *        决定缺件清单该报哪一套 —— 报他**正在推**的那一套,缺件那句话才指得对地方。
     */
    internal fun pick(fp32Complete: Boolean, int8Complete: Boolean, anyFp32: Boolean): String = when {
        fp32Complete -> FP32
        int8Complete -> INT8
        anyFp32 -> FP32
        // 两套都没有:int8 是出厂那一套,缺什么就报它的名字。
        else -> INT8
    }
}
