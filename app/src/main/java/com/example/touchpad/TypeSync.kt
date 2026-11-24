package com.example.touchpad

/**
 * 「手机输入框里的字」和「电脑上那个输入框里的字」怎么对上 —— **纯逻辑,零 Android 依赖**
 * (和 `MoodMath` / `EarMath` / `EarSessionMath` 同一个惯例,好让 JVM 单测直接跑)。
 *
 * ## 它要解决的那件事
 *
 * 用户 2026-10-04 原话:
 * > 「那个键盘我想,就是**手机点击电脑对话框手机能自己跳出输入法**啊」
 *
 * 于是手机上出现一条盖在镜像下方的输入条([MainActivity] 的 `typeBar`),
 * 你打的每个字要**当场**打到电脑上 —— 不是「打完了按发送」,因为你的眼睛正盯着
 * 电脑那块屏幕(它就在上面),而两边对不上的话你会看见「我打的字没过去」。
 *
 * ## ★★ 为什么是「算差异」而不是「按键就发一个字符」
 *
 * 因为**输入法的行为不是逐字追加**:
 * · 中文/日文候选词是**整块替换**的(打 `nihao` → 上屏「你好」,三个字符换成两个字)
 * · 英文输入法会**自动改正**(teh → the)
 * · 长按退格会**连续删一大片**
 * · 粘贴、清空一次进来一大串
 *
 * 逐键发的话,上面每一条都会在电脑上留下一串**你没打过的字**。而手机这边
 * 拿到的永远只是「输入框现在是什么」(**快照**,不是**操作流**)——
 * 所以唯一稳的做法就是:**拿上一版快照和新快照比,把它变成一个操作序列。**
 *
 * ## 那条唯一的规则
 *
 * 比出**公共前缀**,前缀之后的:
 * · 旧的比新的长 → 按差几个**退格**
 * · 新的比旧的长 → 把多出来的那截**打过去**
 * · 中间改了 → 先退格、再补打字(上面的自然结果)
 *
 * 不写「如果是追加那就只发后缀」这类特例 —— 特例多了就会在某一条上漏掉,
 * 而漏掉的样子是**电脑上多出几个字**,极难从「手机上看起来是对的」倒推出来。
 *
 * ## ⚠️ 退格数按**码点**算,不按 `String.length`
 *
 * `String.length` 数的是 UTF-16 **代码单元**:一个 emoji(或任何补充平面字符,
 * 比如某些生僻字)占两个。按它发退格会**把一个字符删掉一半**,电脑上留下一个
 * 乱码方块 —— 而手机上完全看不出哪里不对。
 * 所以前缀长度在比较时是代码单元(必须按位比),**换算成退格次数时换成码点**。
 * 中文/英文这些常见情形两者相等,所以这个坑平时不出现,只在它出现时很难查。
 */
internal object TypeSync {

    /** 一条要发给电脑的操作。顺序就是列表顺序 —— 先退格,再补打字。 */
    sealed class Op {
        /** 按 [times] 下退格(电脑端 `VK_KEYS[8]`)。 */
        data class Backspace(val times: Int) : Op()

        /** 把 [text] 打到电脑的光标处(电脑端 `T` 命令)。 */
        data class Append(val text: String) : Op()
    }

    /**
     * `old`(上一版,也就是**我们已经同步过去的那一份**)变成 `now` 需要做哪些操作。
     *
     * ★ `old` 必须是「我们已经发过去的」,而不是「上一次输入框里显示的」——
     *   这两者只在发送成功时才相同。调用方在任何时候改了输入框的内容(比如按回车之后
     *   清空),都要把 `old` 一起复位,否则会**凭空给电脑发一串退格**
     *   (见 `MainActivity` 的 `typeSilent`)。
     *
     * 没有变化时返回**空列表**(不是 null,也不是一个空 Append)——
     * 空串的 Append 会被 `sendText` 丢掉,但这里就不产生它,免得调用方还要判一次。
     */
    fun diff(old: String, now: String): List<Op> {
        if (old == now) return emptyList()

        var p = 0
        val max = minOf(old.length, now.length)
        while (p < max && old[p] == now[p]) p++

        // ★ 别把切点停在**代理对中间**:那会让「保留的前缀」本身是半个字符,
        //   后面的退格数也就跟着错。往回收一格,多删一个字,少留一个乱码。
        if (p > 0 && Character.isHighSurrogate(now[p - 1])) p--

        val del = old.codePointCount(p, old.length)
        val ins = if (p < now.length) now.substring(p) else ""

        val ops = ArrayList<Op>(2)
        if (del > 0) ops.add(Op.Backspace(del))
        if (ins.isNotEmpty()) ops.add(Op.Append(ins))
        return ops
    }

    /**
     * 操作序列要按下的退格总次数(测试和日志用;真正的执行在 `MainActivity`)。
     *
     * ★ 单独给一个函数是因为**它是唯一会「多删用户东西」的数字** ——
     *   把它从 `Op` 列表里摊出来,就能在单测里直接钉住「退格 5 下」这种断言,
     *   而不用去比对一个 sealed class 列表(那样的失败信息读起来很费劲)。
     */
    fun backspaceCount(ops: List<Op>): Int =
        ops.filterIsInstance<Op.Backspace>().sumOf { it.times }

    /** 把操作序列拼回一段可读的字(日志用):`<8×3>你好`。 */
    fun describe(ops: List<Op>): String = ops.joinToString("") { op ->
        when (op) {
            is Op.Backspace -> "<8×${op.times}>"
            is Op.Append -> op.text
        }
    }
}
