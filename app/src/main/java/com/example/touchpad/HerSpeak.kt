package com.example.touchpad

import android.content.Context

/**
 * 用**她的嗓子**念一段话，给不经过房间界面的地方用（现在只有超级工作台）。
 *
 * ## 为什么单独一个文件
 *
 * 房间里那套念话（`ConMarnActivity.speak` 一带）缠着字幕、光晕、「她在说」、
 * 半双工闸、打断、内嵌/系统两副嗓子的切换 —— 那些都是**房间里**才成立的东西。
 * 工作台只需要最后一句：把这行字念出来。
 *
 * ★ 所以这里**只做选择**：按他选的那副嗓子念；那副用不了就换另一副。
 *   房间那套一个字都不用改（[HerVoice.enabled] 那份共享副本除外），
 *   这样从房间那条路上撕下来的东西最少。
 *
 * ## 两副嗓子
 *
 * | 选的是 | 用谁 | 用不了怎么办 |
 * |---|---|---|
 * | 内嵌（[SherpaVoice]，她的克隆音） | 内嵌 | 退到系统 TTS |
 * | 系统（`TextToSpeech`） | 系统 | 无（系统那副自己会重试） |
 *
 * ★ 「用不了就换另一副」是刻意的：**没有声音比声音慢坏得多**，
 *   这个项目所有的静默失败都是从「反正还有另一条路」开始的。
 */
internal object HerSpeak {

    // ── 读的是房间里那几个 key，不是抄一份默认值 ──
    // 抄一份的话，他换了嗓子，工作台会还是老那副。
    private const val PREFS = "conmarn_voice"
    private const val KEY_ENGINE = "engine"
    private const val ENGINE_SYSTEM = "system"
    private const val DEF_ENGINE = ENGINE_SYSTEM
    private const val KEY_RATE = "rate"
    private const val DEF_RATE = 0.92f

    /**
     * 立刻闭嘴。两副嗓子**都停**。
     *
     * ★★ 不能「猜刚才用的是哪一副就停哪一副」：说这句话时它可能已经换过一副了
     *   （内嵌那份收不下就退回系统 TTS），只停一边的结果是另一边接着念 ——
     *   而他之所以按下关闭，就是因为听见还有声音。
     * ★ 每一路都单独 try：一副嗓子停的时候抛异常，不该让另一副继续念。
     */
    fun stop() {
        runCatching { SherpaVoice.stop() }
        runCatching { HerVoice.stop() }
    }

    /**
     * 念一句。
     *
     * ★ **念完不管**：工作台不靠它同步任何东西，所以不接 `onDone`。
     * ★ 出声开关关着时**一声不出**（[HerVoice.enabled]）。
     */
    fun say(ctx: Context, text: String) {
        if (!HerVoice.enabled) return
        val body = text.trim()
        if (body.isEmpty()) return
        val app = ctx.applicationContext

        val embedded = try {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ENGINE, DEF_ENGINE) ?: DEF_ENGINE
        } catch (_: Exception) {
            DEF_ENGINE
        } != ENGINE_SYSTEM

        if (embedded && SherpaVoice.isReady()) {
            val speed = try {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getFloat(KEY_RATE, DEF_RATE).coerceIn(0.5f, 2.0f)
            } catch (_: Exception) {
                DEF_RATE
            }
            val took = SherpaVoice.speak(
                ctx = app,
                text = body,
                speed = speed,
                onDone = {},
            )
            if (took) return
            // 内嵌那份没收下（模型没装好 / 合成线程起不来）—— 落到下面系统那副。
        }
        HerVoice.speak(app, body)
    }
}
