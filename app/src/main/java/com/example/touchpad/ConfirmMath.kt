package com.example.touchpad

/**
 * 「要不要等他点头」这件事的纯判定。**零 Android 依赖,能跑 JVM 单测。**
 *
 * ## ★★ 它修的是一个**替用户说话**的回执
 *
 * 原来 [AiAgent.Callback.onConfirm] 只回一个 `Boolean`,于是三条**完全不同**的路
 * (他点了取消 / 框弹出来了但一分钟没人碰 / 框压根没弹出来)在代码里塌成同一个 `false`,
 * 回执也就只能写一句:
 *
 * > 用户取消了这次输入
 *
 * 而那句话在**后两种情况下是假的** —— 他根本没看到框,更没点过取消。
 * 她拿着这句假话去回答他,就成了「跟实际对不上」。
 *
 * 分开之后,每一种都能说实话,而且**它们各自的修法也不同**:
 * 超时是「他不在」,弹不出来是「我们没有浮层权限」—— 后者要他去设置里开一下。
 *
 * ## 为什么要有 [allowed]
 *
 * `false` 有四种来路,而**只有一种**能让动作继续。写成 `!= DENIED` 之类的话,
 * 新加一种状态时就会**默认放行** —— 那是这一整套里唯一不能出的错。
 * 所以判据只有一个方向:**只有 [Confirm.APPROVED] 算数**,其余一律不做(fail closed)。
 */
// ★ 这个 enum 是 **public** 的(别的都 internal)—— 因为 [AiAgent.Callback.onConfirm]
//   是 public 接口,它的返回类型不能比它更窄。判定本身在 internal 的 [ConfirmMath] 里,
//   外面拿得到的只有这五个名字。
enum class Confirm {
    /** 他点了「确认」。**唯一一个能让动作落地的状态。** */
    APPROVED,

    /** 框弹出来了,他点了「取消」。 */
    DENIED,

    /** 框弹出来了,60 秒里没人碰它。**不等于他同意了。** */
    NO_ANSWER,

    /** 框**根本没弹出来**(没有浮层权限 / 建对话框时就抛了)。 */
    NO_UI,

    /** 等待被打断 —— 这一轮被叫停了,所以没等到答案。 */
    INTERRUPTED,

    /**
     * 他在确认框上点了「确认」,**但指纹那一关没过**([AppGate.askBlocking])。
     *
     * ★ 不能塌进 [DENIED]:那时候他确实点了头,说「你点了取消」是假话;
     *   也不能塌进 [APPROVED]:那这一道二次验证就等于没做。
     */
    NOT_YOU,
}

internal object ConfirmMath {

    /**
     * 这个结果能不能让动作继续。
     *
     * ★★ 写成白名单(`== APPROVED`)而不是黑名单(`!= DENIED`)是有意的:
     *   以后再加一种「问不出来」的状态,它会**自动**落在「不做」那一边。
     *   黑名单写法的失效方式是新状态默认放行 —— 这个项目对那条路的记忆是「钱没了」。
     */
    internal fun allowed(c: Confirm): Boolean = c == Confirm.APPROVED

    /**
     * 给**模型看的**那一句 —— 它会出现在工具回执里,所以是**第三人称、说事实**
     * (模型读的是「用户做了什么」,不是「你做了什么」)。
     *
     * ★ 四条都要带**「所以这件事没做」**这半句。模型拿到「他取消了」之后最常见的误读
     *   是「那换个方式再来一次」;把「没做」写死,它就少一次自作主张的机会。
     */
    internal fun reason(c: Confirm): String = when (c) {
        Confirm.APPROVED -> "用户已确认"
        Confirm.DENIED -> "用户点了取消,这件事没有执行"
        Confirm.NO_ANSWER -> "确认框弹出来了,但等了 60 秒用户没有回应,这件事没有执行"
        Confirm.NO_UI -> "确认框没能弹出来(没有悬浮窗权限或对话框建不起来),这件事没有执行"
        Confirm.INTERRUPTED -> "等待确认时这一轮被叫停了,这件事没有执行"
        Confirm.NOT_YOU -> "用户点了确认,但指纹 / 锁屏密码没有验过(不是本人),这件事没有执行"
    }

    /**
     * 给**他**看的那一句(短、第二人称)。
     *
     * ★ 和 [reason] 是**两份,不是重复**:两份读的人不同。让模型读「你点了取消」,
     *   它会把「你」当成自己;让他读「用户点了取消,这件事没有执行」,又像客服回执。
     *   同一件事说给两个人听,本来就该是两句话。
     */
    internal fun shortUser(c: Confirm): String = when (c) {
        Confirm.APPROVED -> "好,这就做"
        Confirm.DENIED -> "你点了取消,我没做"
        Confirm.NO_ANSWER -> "等了一分钟没人应,我没做"
        Confirm.NO_UI -> "确认框弹不出来,我没做"
        Confirm.INTERRUPTED -> "这轮被叫停了,我没做"
        Confirm.NOT_YOU -> "指纹没验过,我没做"
    }
}
