package com.example.touchpad

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/**
 * 她的嗓子 —— **房间里没有的那一份**。
 *
 * ## 为什么需要第二个 TTS(以及为什么不是「重复造」)
 *
 * 原来全项目只有一处嗓子:[ConMarnActivity] 的 `tts` 字段。那是**跟着 Activity 活的**,
 * 而且它在 `onPause` 里被摘掉([SelfHand.voice] 置回 null)。这个形状一直是对的 ——
 * 直到用户要在**悬浮窗那条四十来像素高的条子上**直接说话:
 *
 *   > 「桌面悬浮窗,点语音输入,还是会跳到房间,**不能直接语音输入需要转房间**」
 *
 * 对话循环里必须有「她说完了」这个信号([EarSessionMath] 那一整套都建在它上面),
 * 而那个信号只从 TTS 的 `onDone` 来。悬浮窗拿不到 Activity 的 `tts`,所以要么
 * 把 Activity 的嗓子搬到进程级(动手术,风险全在房间那条已经被调好的路上),
 * 要么给「房间外」单独一副嗓子(这一份)。
 *
 * **选后者**,理由是这两件事在屏幕上**永远不会同时发生**:房间进前台时悬浮窗会被
 * 收起来([ConMarnBubble.setRoomInFront]),而房间退到后台时会结束对话。
 * 所以两副嗓子不会打架 —— 这是**排他**换来的简单,不是侥幸。
 *
 * ⚠️ 但仍有一个窄缝:房间在后台、她上一句还没念完,而他从悬浮窗开了口。
 * 那一刻两副嗓子会重叠零点几秒。**代价是难听,不是做错事** —— 值得换掉上面那个手术。
 *
 * ## 这个类存在的**唯一理由**是那一条契约
 *
 * [speak] 的 `onDone` **一定会来**(除非这中间有人 [stop])。
 *
 * 这不是讲究:调用方是**对话循环** —— 它把麦克风的开合挂在 `onDone` 上。
 * 回调不来 = 她说完这句之后永远沉默,而症状是「她不理我了」,
 * 日志里一片太平(房间里那条路为此专门立了 [EarSessionMath.speechWaitMs] 那道兜底,
 * 见那边的注释)。所以这里自己就把三种「永远不会来」堵死:
 *
 * | 会不会来 | 真因 | 这里怎么堵 |
 * |---|---|---|
 * | 引擎还没初始化好 | `speak` 那一刻 `tts == null` | 攒一句,初始化完补念;初始化**失败**就直接兑现回调 |
 * | 引擎在念的时候被顶掉 | 新的一句把旧的 `QUEUE_FLUSH` 了 | 顶掉的同时**兑现旧的回调**(它已经不算数了) |
 * | 引擎静默失败 | `speak` 返回非 SUCCESS,或者干脆不回 `onDone` | 按字数估一个上界,到点自己兑现 |
 *
 * 第三行那个上界**不是「大概等这么久」,是「再等下去就是卡住了」**:
 * 按 220ms/字(中文语速约 4.5 字/秒,而她的语速是 0.92 倍)再加 4 秒余量。
 * 估长了只是偶尔让下一轮晚开半秒,估短了会把「她还在念」当成「她念完了」——
 * 那一下正好是**自问自答**的入口。
 */
internal object HerVoice {

    private val main = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var initializing = false

    /**
     * 出声总开关的**共享副本**。
     *
     * ★「出声」那个开关原来只是 [ConMarnActivity] 里的一个字段，别的界面读不到 ——
     *   于是工作台没法知道他现在要不要听。这里放一份，房间那边每次拨动都会写进来
     *   （见 `ConMarnActivity.toggleTts`）。
     * ★ 它**不是**第二套开关：唯一能拨的还是房间/设置里那一行。
     */
    @Volatile var enabled = true

    /** 引擎好了没有。**只用于日志和「要不要攒一句」,调用方不必读它。** */
    @Volatile private var ready = false

    /** 这一句念完之后要叫谁。**只有一个** —— 她一次只念一句,队列在这里没有意义。 */
    private var done: (() -> Unit)? = null

    /** 引擎还没热起来时,先记着要念的那一句。**只留最后一句**(见 [speak])。 */
    private var pending: String? = null

    /** 念到一半的这句话,给日志用。 */
    private var current: String? = null

    /** 每条话音一个号,只听自己那条的 `onDone`。 */
    private var seq = 0

    /**
     * ★★ **正在等的那一条**的号。空 = 没在等。
     *
     * 它就是文件头那张表里「引擎在念的时候被顶掉」那一行的落点:号在这里,
     * 引擎报回来的那条要**对得上**才算数(判定见 [EchoMath])。
     * 少了它,被顶掉的旧那句**迟到的 `onDone`** 会被当成「新的这句念完了」——
     * 麦克风在她还在说的时候开,她把自己的话听回去。**不报错。**
     */
    private var curId: String? = null

    /**
     * 兜底:到点自己兑现。**它和 [speak] 共用同一个 Runnable 实例**,
     * 所以重排就是 `removeCallbacks` + `postDelayed`,不会攒下好几个闹钟。
     */
    private val backstop = Runnable { fire() }

    /**
     * 引擎的那三个回调。**它们从 binder 线程来** —— 所以一律 post 回主线程再动状态,
     * 否则 [done] 那个 lambda(它会去开麦克风)会在别的线程上跑。
     */
    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            main.post { fromEngine(utteranceId) }
        }

        @Deprecated("老 API 的回调,新引擎走下面那条", ReplaceWith("onError(utteranceId, errorCode)"))
        override fun onError(utteranceId: String?) {
            main.post { fromEngine(utteranceId) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            main.post { fromEngine(utteranceId) }
        }
    }

    /**
     * 引擎报回来一条。**先问它是不是我们正在等的那一条**(判定和理由见 [EchoMath])。
     *
     * ★ 认错一条的代价是麦克风提前开 → 她自问自答;漏认一条只是多等一会儿,
     *   而 [backstop] 那个按字数估的闹钟会兜住。**两个方向不对称,所以宁可不认。**
     */
    private fun fromEngine(utteranceId: String?) {
        if (!EchoMath.isOurs(utteranceId, curId)) return
        fire()
    }

    /**
     * 念一句。
     *
     * @param onDone **一定会被调**,而且只一次 —— 除非这中间有人 [stop]
     *   (那种情况下调用方已经明确不要这句话了,再回调过去会让它在**新的**一轮里
     *   去开麦克风)。
     *
     * ★ 新的 `speak` 会**顶掉**上一句:她一次只念一句。攒成一队的话,
     *   他会听到她连着念两段,而第二段答的是他早就改口了的问题。
     */
    fun speak(ctx: Context, line: String, onDone: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        // ★★ 舞台指示在**这一处收口**剥掉(判定和理由见 [SpeechMath])。
        //   放在这儿而不是各个调用点,理由和 [estimateMs] 那段一样:悬浮条这条路
        //   和房间那条路是两副嗓子,谁漏了谁就会念出「叹气」两个字。
        //   ★ 剥完 [_backstop] 的估算也用这个长度 —— 它按字数估上界,估的是**要念的字**。
        val text = SpeechMath.forSpeech(line).trim()
        if (text.isEmpty()) {
            onDone?.invoke()
            return
        }
        main.post {
            // 顶掉上一句 —— 顺带把它那个「该开麦了」的回调**作废**。见文件头那张表。
            cancelLocked()
            done = onDone
            if (!ready) {
                pending = text
                initLocked(app)
                return@post
            }
            sayLocked(app, text)
        }
    }

    /**
     * 她**现在是不是正在说话**。
     *
     * ★★ 它是 [ConMarnBubble] 那块玻璃上「点一下」该算哪一件事的**唯一判据**:
     *   她正说着 → 点一下 = **打断她**;她安静 → 点一下 = **进打字**。
     *   两件事都不报错、都不写日志,选错了只表现成「她今天怪怪的」——
     *   所以判据得有一个名字,不能散在调用处现推。
     *
     * ★ 判的是 `current`:它在 [sayLocked] 里置上、在 [fire] 和 [cancelLocked]
     *   两条路上都会清掉(念完了 / 被 stop 了 / 兜底闹钟到了)。**这正是
     *   「半双工闸」关心的那段时间** —— 麦克风闭嘴的窗口和它**是同一段**。
     *
     * ⚠️ 它**不**保证声音真的在响:她的 TTS 挂了(`ttsOn=false`)时这一句仍然为真,
     *   因为那时她在**说**、只是没人听得见(见 [speak] 的兜底)。用它做 UI 判据是对的,
     *   用它做「有没有噪声绕回麦克风」的判据也是对的(那两条都跟着 `current`)。
     */
    fun isSpeaking(): Boolean = current != null

    /**
     * 闭嘴。**不会**兑现 [speak] 那个回调 —— 见 [speak] 的 `onDone` 说明。
     */
    fun stop() {
        main.post {
            try { tts?.stop() } catch (_: Exception) {}
            cancelLocked()
        }
    }

    // ------------------------------------------------------------------ 里面

    /** 撤掉当前这一句的一切痕迹(声音、兜底闹钟、待兑现的回调、它的号)。 */
    private fun cancelLocked() {
        main.removeCallbacks(backstop)
        done = null
        pending = null
        current = null
        curId = null
    }

    /**
     * 兑现 [done]。
     *
     * ★★ 「按字数估的兜底」和「引擎的回声」**都走这里**,但只有后者先过 [fromEngine] 那道闸 ——
     *   见 [EchoMath] 文件头最后一段:兜底存在的理由**就是**引擎一声不吭,
     *   拿号去要求它等于把这个兜底取消掉。
     */
    private fun fire() {
        main.removeCallbacks(backstop)
        val cb = done ?: return          // 已经兑现过 / 被 stop 掉了 —— 幂等
        done = null
        current = null
        curId = null
        cb()
    }

    private fun sayLocked(app: Context, text: String) {
        current = text
        val id = "conmarn-${++seq}"
        curId = id                       // ★ 先立号,再开口 —— 回调只认这个
        val r = try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        } catch (_: Exception) {
            null
        }
        if (r != TextToSpeech.SUCCESS) {
            // 嗓子当场坏了 —— 与其让对话卡在这儿,不如当这句已经念完。
            ModelManager.get(app).trace("球:嗓子念不出来(结果码 $r),这一句跳过")
            fire()
            return
        }
        main.removeCallbacks(backstop)
        main.postDelayed(backstop, estimateMs(text))
    }

    /**
     * 这句话最多可能念多久。
     *
     * ★ 见文件头:它是**「再等下去就是卡住了」**那条线,不是时长预估。
     *   220ms/字 是按这台机器上 TTS 的语速(0.92 倍)倒推的,再留 4 秒余量。
     */
    private fun estimateMs(text: String): Long =
        (text.length * 220L + 4_000L).coerceAtLeast(6_000L)

    private fun initLocked(app: Context) {
        if (tts != null || initializing) return
        initializing = true
        try {
            tts = TextToSpeech(app) { status -> main.post { onInit(app, status) } }
        } catch (_: Exception) {
            initializing = false
            ready = false
            fire()                        // ★ 起不来也要给对话一个交代,见文件头
        }
    }

    private fun onInit(app: Context, status: Int) {
        initializing = false
        var ok = status == TextToSpeech.SUCCESS
        if (ok) {
            ok = try {
                tts?.setOnUtteranceProgressListener(listener)
                var lang = TextToSpeech.LANG_NOT_SUPPORTED
                for (loc in TTS_LANGS) {
                    lang = tts?.setLanguage(loc) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    if (lang >= 0) break
                }
                // 音色 / 音高 / 语速 —— 和房间里**同一份设置**(见 ConMarnActivity.applyTtsVoice)。
                // ★ 这里读的是同一批 key,不是抄一份默认值:她换衣服的时候
                //   不能只有房间里的那半个人换了。
                applyVoice(app)
                lang >= 0
            } catch (_: Exception) {
                false
            }
        }
        ready = ok
        ModelManager.get(app).trace(
            "球:嗓子${if (ok) "就绪" else "不可用(这台机器上没有能念中文的引擎 —— " +
                "在悬浮窗条子上她只能写字)"}"
        )
        val line = pending
        pending = null
        if (ok && line != null) {
            sayLocked(app, line)
        } else if (!ok) {
            fire()
        }
    }

    private fun applyVoice(app: Context) {
        try {
            val p = app.getSharedPreferences(ConMarnActivity.PREFS_VOICE, Context.MODE_PRIVATE)
            p.getString(ConMarnActivity.KEY_VOICE, null)?.let { want ->
                tts?.voices?.firstOrNull { it.name == want }?.let { tts?.voice = it }
            }
            tts?.setPitch(p.getFloat(ConMarnActivity.KEY_PITCH, ConMarnActivity.DEF_PITCH))
            tts?.setSpeechRate(p.getFloat(ConMarnActivity.KEY_RATE, ConMarnActivity.DEF_RATE))
        } catch (_: Exception) {
            // 挑音色失败不是致命错 —— 引擎的默认嗓子照样能念。
        }
    }
}
