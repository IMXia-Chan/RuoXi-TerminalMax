package com.example.touchpad

import android.content.Context

/**
 * 「她一直在」的那点状态 —— 一个开关,加一个「她是怎么没的」的记号。
 *
 * ## 用户的原话就是这一轮的设计纲
 *
 * > 「但是误杀进程怎么办?因为我又想不被误杀,又想能自己关掉」
 *
 * 所以关键不是「保活」,是**区分两种死法**:
 *
 * | 怎么死的 | 判据 | 行为 |
 * |---|---|---|
 * | 系统误杀(内存回收 / ColorOS 清理) | `START_STICKY` 复活,但没人调过 [setAlive](false) | **自愈**:重新把她拉起来 |
 * | 用户明确关掉(通知上的「让她睡」/ 房间里的开关) | [setAlive](false) 已经落盘 | 停,且**永不自愈**,直到用户再开 |
 *
 * 判据只有 `alive` 这一个布尔 —— 因为「复活」发生时**没有人可以问**:
 * 那是系统在新进程里重投的 `onStartCommand`,原来的 Activity、原来的内存变量全没了,
 * 唯一还活着的证据就是**盘上那个字节**。
 *
 * ## ★ 默认 false(和 [ProactiveGreeting] 一致:不偷偷开)
 *
 * 「一直在」是**授予能力**,不是**收紧限制** —— 必须用户自己按开。
 * (对比:免打扰是限制,默认开才安全。两者默认值相反,理由不一样,别当成矛盾。)
 */
object HerLife {

    private const val PREF = "her_life"
    private const val KEY_ALIVE = "alive"
    private const val KEY_RESURRECTS = "resurrects"

    /** 她该不该活着。默认 **false** —— 装完不自己开。 */
    fun isAlive(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ALIVE, false)

    /**
     * 开 / 关「一直在」。
     *
     * ★★ **必须 `commit()` 同步落盘,不能 `apply()`。**
     *   关掉时的调用序列是 `setAlive(false)` → 紧接着 `stopSelf()` / `stopForeground`。
     *   `apply()` 是**异步**写盘,两者之间有一个真实的竞态窗口 ——
     *   可能「界面已经关了、服务也停了,但盘上还是 true」→ **下次开机她自己又醒了**。
     *   用户看到的是「我明明关掉了」,而这不是能靠看代码发现的那种 bug。
     *
     * ⚠️ 这条**只对这里**成立。别顺手抄到别处去:免打扰那种「后面没有 stopSelf」的场景
     *    `apply()` 就够(同进程内的读是同步可见的),而且 `commit()` 是同步磁盘 IO,
     *   放在按一下就改的开关里会卡 UI。**规矩是跟着「写完有没有立刻死」走的。**
     */
    fun setAlive(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ALIVE, on).commit()
    }

    /**
     * 记一次「我被系统重建了」。
     *
     * `START_STICKY` 的服务被系统回收后**重投**的 `onStartCommand` 收到的 `intent` 是 **null** ——
     * 那是现成的信号,不用自己造。记下来有两个用处:
     *   ① 诊断:她的日志里能看见「今天被误杀了几次」;
     *   ② 证据:次数多到不像话,就是该去 ColorOS 白名单里加她的客观理由,不是猜。
     */
    fun noteResurrection(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        sp.edit().putInt(KEY_RESURRECTS, sp.getInt(KEY_RESURRECTS, 0) + 1).apply()
    }

    /** 被系统误杀过几次(从装上算起)。 */
    fun resurrections(ctx: Context): Int =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_RESURRECTS, 0)
}
