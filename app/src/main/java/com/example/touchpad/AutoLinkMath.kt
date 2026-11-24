package com.example.touchpad

/**
 * ★★ 「进房间 / 进电脑页的时候,要不要**自己**去拨号」—— 纯判据,**零 Android 依赖**。
 *
 * ---------------------------------------------------------------------------
 * 这一条是为用户 2026-10-08 那个抱怨写的:
 *
 *   > 「一进去,电脑自动跳配对码 …… 而连电脑的时候,它第一遍连不上」
 *
 * 病根不是「连不上」,是**拨号拦不住**:
 * 一进房间就自动拨 → 盘上的免密凭证过期/对不上 → 掉进完整配对 → 电脑弹配对码。
 * 而 `TouchpadClient` 的配对码输入框有 120 秒 TTL,你还没走到电脑前看码,它自己就超时了 ——
 * 于是第一次必然失败,退出来再进一次又来一遍。**配对码是这么刷起来的。**
 *
 * ---------------------------------------------------------------------------
 * ★★ 六条挡在「自动拨号」前面的闸,顺序就是这个 `when` 的顺序:
 *
 * | 闸 | 挡什么 | 为什么 |
 * |---|---|---|
 * | 已经连着 | 白拨 | 老规矩 |
 * | 没权限 | 白拨 | `ACCESS_LOCAL_NETWORK`,拨了也超时 |
 * | 刚拨过 | 别连环拨 | 6 秒最小间隔,老规矩 |
 * | ★ **手机锁着** | **不许拨** | 用户 2026-10-08 定的第 ③ 条 |
 * | ★★ **上次自动拨号落到了配对** | **不许再拨** | 见下 |
 * | ★★ **盘上没有免密凭证** | **不许拨** | 用户 2026-10-08 定的第 ① 条 |
 *
 * ★★ **第五条才是真正掐断风暴的那一刀。**
 * 用户选的是「只有存在免密凭证才自动连」,但光有那一条**掐不断风暴** ——
 * 凭证**存在但已过期**时,自动拨号照样发生、照样落到配对、照样弹码。
 * 真正需要记住的是**结果**:「上一次自动拨号是以『要输配对码』告终的」——
 * 那就别再自作主张了,**等他自己点一下**。
 * 这个标记由 `TouchpadClient` 在**自动**拨号撞上 `PIN_REQUIRED` 那一刻落下,
 * 在①用户明确点着连(`connectTo`/扫描)②任何一次认证成功 时清掉。
 * ★ 它**必须落盘**:进程一重启就忘了的话,「退出去再进一次」又能把它刷起来。
 *
 * ---------------------------------------------------------------------------
 * ★ 为什么单独抽成一个对象、而不是写在 `PcLink.connectLast` 里:
 * 这个项目吃过太多次「判据没有测试、坏了也没人知道」的亏(见 `XxxMath` 那一族)。
 * 抽出来之后,上面六条闸**每条都能在 JVM 上钉死** —— 尤其是「锁屏」和
 * 「上次落到配对」这两条**只在真机上才现形、而且现形时不报错**的路。
 * 事实(连没连、锁没锁、多久没拨)由调用方去读,这里只做判断 —— 同
 * `VoiceFiles.reasonFor` / `MoodMath` 那个分工。
 */
internal object AutoLinkMath {

    /** 调用方**读好的事实**。这个对象自己不碰任何一个系统 API。 */
    internal data class Facts(
        val connected: Boolean,
        val needsPermission: Boolean,
        val msSinceLastAttempt: Long,
        val deviceLocked: Boolean,
        val hasCredential: Boolean,
        val blockedByPairing: Boolean,
    )

    /**
     * 该不该自动拨号。
     *
     * @param minGapMs 两次自动拨号之间的最小间隔(调用方传 `PcLink.MIN_GAP_MS`)。
     *   ★ 当参数传而不是写死在这里:`PcLink` 那份常量的位置不动 —— 它已经在那儿了,
     *   挪过来只会在两个文件之间制造一次「谁才是真的」的疑问。
     */
    internal fun shouldAutoDial(f: Facts, minGapMs: Long): Boolean = when {
        f.connected -> false
        f.needsPermission -> false
        f.msSinceLastAttempt < minGapMs -> false
        f.deviceLocked -> false
        f.blockedByPairing -> false
        !f.hasCredential -> false
        else -> true
    }

    /**
     * 人话理由 —— **这是给人看的,不是给判断用的。**
     *
     * ★ 为什么它必须存在:这个项目的头号敌人是**静默失败**。
     * 「进房间不连了」如果屏幕上、日志里一个字都没有,它就长得和
     * 「电脑没开」/「Wi-Fi 断了」/「她坏了」**一模一样** ——
     * 而那正是这个功能迟早会被当成 bug 报上来的样子。
     */
    internal fun why(f: Facts, minGapMs: Long): String = when {
        f.connected -> "已经连着,不用拨"
        f.needsPermission -> "还没有「本地网络」权限,拨了也连不上"
        f.msSinceLastAttempt < minGapMs -> "刚刚试过,还没过 ${minGapMs / 1000} 秒"
        f.deviceLocked -> "手机锁着 —— 锁屏期间不去连电脑"
        f.blockedByPairing -> "上一次自动连要输配对码,所以这次等你点一下再说"
        !f.hasCredential -> "盘上没有这台电脑的免密凭证(新电脑,或者凭证已经没了)"
        else -> "可以自动连"
    }

    /**
     * 这一条值不值得写进日志。
     *
     * ★★ **不是所有「不拨」都要记。** 频率最高的两条 ——
     * 「已经连着」和「6 秒不到」—— 每次 onResume 都会发生,记下来会把
     * `model.log` 刷成一片噪声,而**噪声就等于没有日志**(这个项目在别处已经吃过)。
     * 值得记的是那三条**新加的、真会让人困惑的**:锁屏 / 上次落到配对 / 没有凭证。
     */
    internal fun worthLogging(f: Facts, minGapMs: Long): Boolean =
        !f.connected && !f.needsPermission && f.msSinceLastAttempt >= minGapMs &&
                (f.deviceLocked || f.blockedByPairing || !f.hasCredential)
}
