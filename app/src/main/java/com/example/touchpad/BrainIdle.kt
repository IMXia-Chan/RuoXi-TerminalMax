package com.example.touchpad

import android.content.Context

/**
 * 「不用时关模型」—— 一个开关,一个看门的人。
 *
 * ## 他要的原话与边界
 *
 * > 「看看能不能使用的时候关模型,因为我一般都不怎么用Agent」
 * > 「帮我设一个开关就行」
 *
 * 所以这里只做**一件事**:开着的时候,她 **1 小时**没被用到,就把模型进程收掉。
 * 不做「按电量关」「按屏幕关」「按温度关」—— 那些是他没要的规矩,而且每多一条
 * 就多一个「怎么又不理我」的理由。
 *
 * ## 代价必须写在开关上,不能只写在注释里
 *
 * 收掉模型**不等于**只是省内存。空闲时她本来就躺在 swap 里(`VmRSS` 43MB),
 * 真正贵的是**下一次开口**:重新装载、再跑一遍预热。所以界面那一行的小字必须
 * 说清「下次要等重新装载」—— 不说的话,他关完遇到一次「半天不理我」会以为是 bug,
 * 而实际上是他自己开的这个钮(同 `toggleCloud` 那条注释里记的那笔账)。
 *
 * ★ 2026-10-25 他当场指出的账:第一版把闸门定成 10 分钟,可**重新装载的代价比 10 分钟
 *   还长**(他报的是 11 分钟)。这样省 10 分钟、赔一次长等待,是纯亏。所以闸门改成
 *   **1 小时** —— 短于它的空档一律不碰她;真要省,只能在「半天/过夜不用」这种尺度上省。
 *   这个数字以后要再动,先量一遍重新装载到底多久,别再拍脑袋。
 *
 * ## 为什么用「最后一次使用时间」而不是「有没有请求正在跑」
 *
 * `chat()` 是脑的唯一入口,单次调用的上限是读超时([AiAgent.Config.llmTimeoutMs])。
 * 那最长也就几分钟,**远小于**这里的闸门 —— 所以只要在 `chat()` 进门那一刻
 * 刷新一次时间戳,就不可能出现「正在回答时被关掉」。为此专门加一个 in-flight 计数
 * 反而要在 `chat()` 那一百多行外面套一层 try/finally,不值得。
 *
 * ⚠️ 唯一的例外是「眼」:它**按次加载**,可能正在被 `click_element` 用着。
 *   [ModelManager.stop] 会把两个进程一起收掉,所以这里多一道 `isEyeRunning()` 的检查,
 *   正在看屏幕时绝不动手。
 */
object BrainIdle {

    private const val PREF = "brain_idle"
    private const val KEY_OFF = "off_when_idle"

    /**
     * 多久没用就把模型收掉。
     *
     * **1 小时**:闸门必须远大于「重新装载」的代价,否则省下来的时间还不够赔等待。
     * 第一版是 10 分钟,被他当场指出比冷启动还短,已改(见类注释里那笔账)。
     */
    const val IDLE_MS = 60 * 60 * 1000L

    /** 这个时长在界面上怎么念。数字只写一份,改闸门时界面跟着变。 */
    val IDLE_LABEL: String
        get() = if (IDLE_MS % 3600_000L == 0L) "${IDLE_MS / 3600_000L} 小时"
                else "${IDLE_MS / 60_000L} 分钟"

    /** 看门的间隔。只需「大概到点了就关」,20 秒一次足够,对发热毫无影响。 */
    private const val TICK_MS = 20 * 1000L

    @Volatile private var appCtx: Context? = null
    @Volatile private var thread: Thread? = null

    /** 脑最后一次**被用到**的时刻。由 [noteUse] 刷新。 */
    @Volatile private var lastUse = System.currentTimeMillis()

    /** 开关状态。默认 **关** —— 不偷偷改变她现在的脾气(随时都在)。 */
    fun isOn(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_OFF, false)

    /**
     * 拨开关。
     *
     * ★ 用 `apply()` 就够(和 [HerLife.setAlive] 正好相反):写完**后面没有 stopSelf**,
     *   进程不会立刻死,不存在「界面关了、盘上还是旧值」那个窗口。
     */
    fun setOn(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_OFF, on).apply()
        arm(ctx.applicationContext)
        if (on) lastUse = System.currentTimeMillis()   // 刚开时不该立刻把她关掉
    }

    /** 有人要用脑了(目前只有 [AiAgent.chat] 会调)。 */
    fun noteUse() {
        lastUse = System.currentTimeMillis()
    }

    /**
     * 挂上看门的人。幂等,可以从任何地方随便调 ——
     * 现在挂在 [ModelManager.start] 上:脑一起来就有人替它数着时间。
     */
    fun arm(ctx: Context) {
        appCtx = ctx.applicationContext
        lastUse = System.currentTimeMillis()
        synchronized(this) {
            if (thread?.isAlive == true) return
            thread = Thread({ loop() }, "ruoxi-brain-idle").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun loop() {
        while (true) {
            try {
                Thread.sleep(TICK_MS)
            } catch (_: InterruptedException) {
                return
            }
            val ctx = appCtx ?: continue
            try {
                tick(ctx)
            } catch (_: Throwable) {
                // 看门的自己摔了不该连带她 —— 下一轮再来
            }
        }
    }

    private fun tick(ctx: Context) {
        if (!isOn(ctx)) return
        val now = System.currentTimeMillis()
        if (now - lastUse < IDLE_MS) return
        val mm = ModelManager.get(ctx)
        if (!mm.isRunning()) return
        if (mm.isEyeRunning()) return                 // 正在看屏幕,别动
        lastUse = now                                  // 关掉后从「现在」重新数,免得下一轮又念一遍
        mm.trace("空闲 ${IDLE_MS / 60000} 分钟,按设置收掉模型(⚙ 里「不用时关模型」)")

        // ★ 收掉的是**常驻**的那一份。他在「⚙」里没开这个钮的时候这条路径永远不会走。
        mm.stop()
    }
}
