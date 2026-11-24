package com.example.touchpad

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.Collections

/**
 * **重新录入唤醒词** —— 打字输入 + 念两遍校准。
 *
 * ## 为什么是「念两遍」而不是一遍
 *
 * 唤醒词这件事最难的地方是：**它不响的时候，你不知道为什么**。
 * 是这个词本身在这个模型里识别不了？是阈值太紧？是麦克风没给？
 * 还是他喊得和拼音对不上？四种原因在屏幕上长得一模一样。
 *
 * 所以录两遍，用**刚写出来的那一行关键词**现场跑一遍检测：
 *
 * | 两遍结果 | 判定 | 落盘 |
 * |---|---|---|
 * | 两遍都听到 | 这个词在这个模型上是活的 | 门槛 0.25（验过的那一组） |
 * | 只听到一遍 | 差一点 | 门槛放宽到 0.20 再验；验过就存 0.20 |
 * | 一遍都没听到 | 不能存 | **绝不落盘**，并说清是哪一步不行 |
 *
 * ★ 「一遍都没听到就不存」这条和前缀快照那条是同一个道理：
 *   存一份**看起来配好了、其实永远不响**的配置，比没有配置毒得多 ——
 *   用户会以为自己录好了，而它一次都不会应。
 *
 * ## 为什么校准期间要抢麦克风
 *
 * 麦只有一个（见 [MicGate]）。唤醒那边正拿着它，这里要录就得先让它放下。
 * 录完必须**放回去** —— 漏掉这一步的症状是「录完唤醒词，唤醒再也不工作了」。
 */
object WakeEnroll {

    /** 验过的那一组。见 [WakeWordManager] 文件头。**和唤醒实时用的那组是同一个来源**。 */
    private val THRESHOLD_OK = WakeWordManager.KEYWORDS_THRESHOLD

    /** 差一点时放宽到这儿。再宽就是「随便说句话都算喊她」，那不能接受。 */
    private const val THRESHOLD_LOOSE = 0.20f

    /** 每一遍录多久。三个字正常语速 1 秒出头，留一倍余量。 */
    private const val CLIP_MS = 2_200L

    private val main = Handler(Looper.getMainLooper())

    /** 现在用的唤醒词。盘上没有就是「从漫」。 */
    fun currentWord(ctx: Context): String {
        val dir = WakeWordManager.locate(ctx)
        val f = File(dir, WakeWordManager.KEYWORDS)
        if (!f.isFile) return DEFAULT_WORD
        val line = runCatching { f.readLines().firstOrNull { it.isNotBlank() } }.getOrNull()
            ?: return DEFAULT_WORD
        val at = line.substringAfter('@', "").trim()
        return if (at.isEmpty()) DEFAULT_WORD else at
    }

    /**
     * 录一个词。**在后台线程跑，回调在主线程。**
     *
     * @param onStage 给人看的进度（「第 1 遍…」「两遍都听到了」）
     * @param onDone  成功 = 已保存；失败 = 原因（都已成人话）
     */
    fun enroll(
        ctx: Context,
        word: String,
        onStage: (String) -> Unit,
        onDone: (ok: Boolean, msg: String) -> Unit,
    ) {
        val app = ctx.applicationContext
        Thread {
            fun stage(s: String) = main.post { onStage(s) }
            fun done(ok: Boolean, msg: String) = main.post { onDone(ok, msg) }

            // ★ 目录用 [WakeWordManager.locate] 找 —— 和唤醒自己认的是**同一处**。
            //   各算各的时候，目录一旦是「带子目录」的那种形状，就会写进一个没人读的地方。
            val dir = WakeWordManager.locate(app)
            if (!File(dir, WakeWordManager.TOKENS).isFile) {
                done(false, "唤醒模型还没装（缺 asr/kws 那套文件），先装模型再录")
                return@Thread
            }
            val vocab = vocab(dir)
            if (vocab.isEmpty()) {
                done(false, "读不到 ${File(dir, WakeWordManager.TOKENS).absolutePath} 里的词表")
                return@Thread
            }

            // ① 字 → token。翻不出来就地拒绝，别去动麦克风。
            stage("正在把「$word」翻成拼音…")
            val tokens = WakeTokenMath.toTokens(word, vocab) { ch -> pinyinOf(ch) }
                .getOrElse { e ->
                    done(false, e.message ?: "这个唤醒词翻不出来")
                    return@Thread
                }

            // ② 抢麦：先让唤醒放下。
            stage("先让唤醒松手…")
            WakeWordManager.holdMic()
            try {
                val clips = ArrayList<FloatArray>()
                for (i in 1..2) {
                    stage("第 $i 遍：请念「$word」")
                    val clip = recordClip(app)
                    if (clip == null || clip.isEmpty()) {
                        done(false, "没拿到麦克风。检查一下录音权限，或者是不是正推流给电脑")
                        return@Thread
                    }
                    if (clip.all { it == 0f }) {
                        done(false, "录到的全是静音 —— 麦克风被别处占着（推流？），这一遍不算")
                        return@Thread
                    }
                    clips.add(clip)
                    stage("第 $i 遍录好了")
                }

                // ③ 用**刚写出来的那一行**现场验。
                stage("正在验（门槛 ${fmt(THRESHOLD_OK)}）…")
                val strict = clips.map { hits(app, dir, word, tokens, THRESHOLD_OK, it) }
                val threshold = when {
                    strict.all { it > 0 } -> THRESHOLD_OK
                    else -> {
                        stage("严的那档只听到 ${strict.count { it > 0 }}/2，放宽到 ${fmt(THRESHOLD_LOOSE)} 再验一次…")
                        val loose = clips.map { hits(app, dir, word, tokens, THRESHOLD_LOOSE, it) }
                        if (loose.all { it > 0 }) THRESHOLD_LOOSE else -1f
                    }
                }
                if (threshold < 0f) {
                    done(
                        false,
                        "两遍都没听到「$word」。没有保存 —— 现在这一份还是原来的。\n" +
                            "可以试试：离手机近一点、念慢一点，或者换一个更长的词。"
                    )
                    return@Thread
                }

                // ④ 落盘 + 让唤醒重新装载这一份。
                val line = WakeTokenMath.line(word, tokens, threshold)
                val f = File(dir, WakeWordManager.KEYWORDS)
                // ★★ 2026-11-08(体检):直接 `writeText` —— 写到一半被杀,唤醒词文件
                //   就是半截,而它是**唤醒唯一的凭据**:下次开机喊不应,日志里
                //   只会写「没加载到关键词」。改成先写 .tmp 再整体换上。
                if (!AtomicWrite.replace(f, line + "\n")) {
                    done(false, "写不进去 ${f.absolutePath}：磁盘写入失败")
                    return@Thread
                }
                WakeWordManager.reloadSpotter()
                done(true, "已保存：$word（门槛 ${fmt(threshold)}，两遍都验过）")
            } finally {
                // ★ 麦一定要还回去。录失败也一样 —— 见文件头。
                WakeWordManager.releaseMic(app)
            }
        }.apply { isDaemon = true; name = "wake-enroll" }.start()
    }

    // ------------------------------------------------------------------ 取数

    /** `tokens.txt` 的第一列。 */
    private fun vocab(dir: File): Set<String> = runCatching {
        File(dir, WakeWordManager.TOKENS).readLines(Charsets.UTF_8)
            .mapNotNull { l -> l.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() } }
            .toSet()
    }.getOrDefault(emptySet())

    /**
     * 一个字 → 带调拼音。
     *
     * ★ 用系统 ICU 的 `Han-Latin`：它随 Android 一起装，不引任何库，
     *   也不用往 APK 里塞一张拼音表（那张表光常见字就几百 KB）。
     */
    private fun pinyinOf(ch: String): String = try {
        android.icu.text.Transliterator.getInstance("Han-Latin").transliterate(ch)
    } catch (e: Exception) {
        ""
    }

    // ------------------------------------------------------------------ 录音与验证

    /** 录一段。拿不到麦回 null。 */
    private fun recordClip(ctx: Context): FloatArray? {
        val chunks = Collections.synchronizedList(ArrayList<FloatArray>())
        val m = EarMic(MicGate.WAKE, onChunk = { chunks.add(it) }, onError = { })
        val err = m.start()
        if (err != null) return null
        try {
            Thread.sleep(CLIP_MS)
        } catch (_: InterruptedException) {
        } finally {
            m.stop()
        }
        val total = synchronized(chunks) { chunks.sumOf { it.size } }
        if (total == 0) return null
        val out = FloatArray(total)
        var p = 0
        synchronized(chunks) {
            for (c in chunks) {
                System.arraycopy(c, 0, out, p, c.size)
                p += c.size
            }
        }
        return out
    }

    /** 这段音频在这行关键词上被听到几次。 */
    private fun hits(
        ctx: Context,
        dir: File,
        word: String,
        tokens: List<String>,
        threshold: Float,
        audio: FloatArray,
    ): Int {
        var sp: KeywordSpotter? = null
        var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null
        return try {
            // 临时关键词文件：**不碰盘上那一份**，验不过就不该留下任何痕迹。
            val tmp = File(ctx.cacheDir, "kws_probe.txt")
            tmp.writeText(WakeTokenMath.line(word, tokens, threshold) + "\n", Charsets.UTF_8)
            val cfg = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = EarMic.SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(dir, WakeWordManager.ENCODER).absolutePath,
                        decoder = File(dir, WakeWordManager.DECODER).absolutePath,
                        joiner = File(dir, WakeWordManager.JOINER).absolutePath,
                    ),
                    tokens = File(dir, WakeWordManager.TOKENS).absolutePath,
                    numThreads = WakeWordManager.NUM_THREADS,
                    modelType = "zipformer2",
                ),
                keywordsFile = tmp.absolutePath,
                // ★ 这两个数和唤醒实时跑的那一组**是同一个常量** —— 见 [WakeWordManager]
                //   文件头：它们直接决定「喊十次中几次」，验货用的必须和上岗用的完全一致。
                keywordsScore = WakeWordManager.KEYWORDS_SCORE,
                keywordsThreshold = threshold,
                numTrailingBlanks = WakeWordManager.NUM_TRAILING_BLANKS,
            )
            // ★ 先落到两个**非空**局部量上再干活，只把可空的那两个留给 finally 收尾。
            //   可空 var 后面再靠智能转型调用方法，是这个文件里最容易编译不过的一处。
            val spotter = KeywordSpotter(config = cfg)
            sp = spotter
            val st = spotter.createStream()
            stream = st
            val step = 1600          // 0.1 秒一块
            var i = 0
            var found = 0
            while (i < audio.size) {
                val n = minOf(step, audio.size - i)
                val chunk = FloatArray(n)
                System.arraycopy(audio, i, chunk, 0, n)
                st.acceptWaveform(chunk, EarMic.SAMPLE_RATE)
                while (spotter.isReady(st)) spotter.decode(st)
                val r = spotter.getResult(st)
                if (r.keyword.isNotEmpty()) {
                    found++
                    spotter.reset(st)
                }
                i += n
            }
            found
        } catch (t: Throwable) {
            // 起不来就是 0 次 —— 但要让调用方知道它失败过（返回 0 已经表达了「没听到」）。
            0
        } finally {
            runCatching { stream?.release() }
            runCatching { sp?.release() }
            runCatching { File(ctx.cacheDir, "kws_probe.txt").delete() }
        }
    }

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.2f", v)

    /** 出厂那个唤醒词。见 [WakeWordManager] 文件头。 */
    private const val DEFAULT_WORD = "从漫"
}
