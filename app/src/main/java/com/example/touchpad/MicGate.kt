package com.example.touchpad

/**
 * 进程级的麦克风仲裁 —— **谁在占麦,以及它说不说得出「我被占了」。**
 *
 * ## 为什么需要它
 *
 * 这台手机上想用麦克风的有三处:推流给电脑([MediaStreamer])、她的耳朵([Ear])、
 * 唤醒词([WakeWordManager])。Android 不会替我们挡这个:**第二个 `AudioRecord`
 * 照样构造成功、`startRecording()` 照样不报错**,只是拿到一耳朵零。
 *
 * 症状因此是**静默的**:「她听不见」—— 和「模型没加载」「VAD 没断句」「唤醒词喊错了」
 * 长得一模一样,而她其实只是没抢到麦。转发一次排查要推 234MB 模型上真机,
 * 所以这个判断必须有一条**能在界面上说出来**的路。
 *
 * ## 为什么不是「谁先来谁占,后来的排队」
 *
 * 因为排队会更糟:他正看着电脑屏幕上的实时画面,耳朵在后台等着抢麦 —— 画面什么时候断
 * 全看 VAD 什么时候断句。**推流是有人正看着的,耳朵是没人看着的**,后者让前者。
 * 所以规则是 **拿不到就说拿不到**,不硬抢、不排队。
 *
 * ## 一个字都不许自作聪明的地方
 *
 * ★ [holder] 报出来的名字是**给人看的**,所以每一步 `acquire`/`release` 都要配平。
 *   漏一次 `release` 的后果不是崩溃,是**耳朵从此永久性地「被占着」** ——
 *   一个再也不会打开的开关。所以 [MediaStreamer] 那边的两处调用点写在
 *   `startMic` / `stopMic` 里,和 `micRunning` 同生共死。
 */
object MicGate {

    /** 推流([MediaStreamer])。 */
    const val STREAM = "推流"

    /** 她的耳朵([Ear])。 */
    const val EAR = "耳朵"

    /** 唤醒词([WakeWordManager])。 */
    const val WAKE = "唤醒"

    private val holders = LinkedHashSet<String>()

    /**
     * 占麦。**成功才返回 true。**
     *
     * 允许同一个 owner 重复拿(幂等):`startMic()` 被调两次不该把第一次的占位顶掉 ——
     * 那样 `stopMic()` 一次就把计数清零,推流还在跑而门已经开了。
     */
    @Synchronized
    fun acquire(owner: String): Boolean {
        if (holders.contains(owner)) return true
        if (holders.isNotEmpty()) return false   // 不硬抢:让正在用的那个用完
        holders.add(owner)
        return true
    }

    /**
     * 放开。
     *
     * ★ **用 `remove` 而不是 `clear()`** —— 将来若真的允许多个 owner 共存
     *   (比如播放和录音同时),`clear()` 会让任何一个的退出把所有门都打开。
     */
    @Synchronized
    fun release(owner: String) {
        holders.remove(owner)
    }

    /** 现在谁占着;没人占返回 null。给人看的名字,不是 id。 */
    @Synchronized
    fun holder(): String? = holders.firstOrNull()

    /**
     * 除了 [except] 之外,还有别人占着吗。
     *
     * [WakeWordManager.micHook] 用的就是这个形状:它问的是「**别人**在占吗」,
     * 而不是「有没有人占」—— 否则唤醒自己刚拿上门就看见自己,永远启动不了。
     */
    @Synchronized
    fun isBusy(except: String? = null): Boolean =
        holders.any { it != except }

    /** 给界面/回执用的一句话。**必须说得出是谁占的** —— 「麦克风被占用」是句废话。 */
    @Synchronized
    fun busyReason(): String? =
        holders.firstOrNull()?.let { "$it 正用着麦克风,她这会儿听不见 —— 等它停了再试" }

    /** 只给单测用:把门恢复成初始状态。 */
    @Synchronized
    internal fun resetForTest() {
        holders.clear()
    }
}
