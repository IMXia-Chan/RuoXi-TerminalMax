package com.example.touchpad

/**
 * 沙盒的**纯逻辑** —— 零 Android 依赖,能在 JVM 上钉死(见 `SandboxMathTest`)。
 *
 * ## 它管的是什么
 *
 * 「模型给的那条命令,能不能就这么放进去跑」。这是**判据**,不是执行 ——
 * 执行在 [Sandbox]。分开的理由和项目里别的地方一样:会出错但不会崩的规则要能被单测
 * 逐条盯着,而 `ProcessBuilder` 那半边在 JVM 上跑不起来。
 *
 * ## ★ 规则只有两条,而且是故意定得这么死
 *
 * 1. **不许出现绝对路径**(`/dev/null` 这类几个特例除外)。
 * 2. **不许出现 `..`**。
 *
 * 为什么不搞「把绝对路径解析一下、看看在不在沙盒里」那套 —— 因为**那条路能绕**:
 * 一条命令里可以先 `cd ../../..` 再干别的,静态解析追不上 shell 自己的展开
 * (`$HOME`、通配符、命令替换)。判据一旦开始猜,就等于没有判据。
 *
 * 这条规矩的代价是模型必须用**相对路径**写命令。它做得到 —— 沙盒的当前目录就是
 * 它的家目录,`ls`、`cat a.txt`、`echo hi > a.txt` 全都是相对的。真需要看某个
 * 宿主机文件的少数场合,那是**另一条路**(带用户点头的),不该靠放宽这条闸来换。
 *
 * ## 已知边界(诚实写在脸上)
 *
 * 这是**字符串闸**,不是内核闸。它挡的是「模型顺手写了 `/sdcard/...`」这类错,
 * 挡不住一条处心积虑构造、拼出路径的命令。真正的围墙要靠 chroot / 独立 uid,
 * 而这两样在没 root 的安卓上拿不到。**所以沙盒里跑的东西只能是她自己写的,
 * 不能是外来的脚本** —— 这条写进工具说明里,也写进回执。
 */
internal object SandboxMath {

    /**
     * 允许出现的绝对路径。**只放 dev 下那几个重定向目标** ——
     * `2>/dev/null` 是写 shell 命令的肌肉记忆,禁掉它只会让模型反复撞墙。
     */
    private val SAFE_ABS = setOf(
        "/dev/null", "/dev/stdin", "/dev/stdout", "/dev/stderr", "/dev/tty",
    )

    /**
     * 一条路径「开头」的样子:`/` 前面不能是标识符字符(否则 `a/b` 里那个 b 前的 `/`
     * 也会被当成绝对路径)。后面的字符取到空白、引号、分隔符为止。
     */
    private val PATH_TOKEN = Regex("""(?<![A-Za-z0-9_$}])/[^\s'"`;|&<>(){}]*""")

    /** `..` 单独成一个路径段的样子 —— `a..b`、`file..txt` 不算。 */
    private val DOTDOT = Regex("""(?<![A-Za-z0-9_.])\.\.(?![A-Za-z0-9_.])""")

    /**
     * 这条命令里**不该出现的绝对路径**。
     *
     * 空表 = 放行。非空的时候回执要把这些原样摆给模型看 —— 「没说清楚」
     * 和「说了哪几个」对下一轮的差别,就是这个项目反复强调的那件事。
     */
    fun hostPaths(cmd: String): List<String> =
        PATH_TOKEN.findAll(cmd)
            .map { it.value }
            .filter { it !in SAFE_ABS }
            .toList()
    // ★ 单独一个 `/` 也要算 —— 它是宿主机根目录。放过它的代价是 `cd / && ls`
    //   直接看到整个文件系统,而这是这道闸最该挡住的那一下。宁可把
    //   `echo "a / b"` 这种误伤也一起挡住:误伤的代价是模型换一种写法,
    //   放进来的代价是这道闸从根上没有意义。

    /** 命令里有没有 `..`。 */
    fun hasDotDot(cmd: String): Boolean = DOTDOT.containsMatchIn(cmd)

    /**
     * 命令能不能放进去跑。→ null 表示可以,否则是一句**给模型看**的话。
     *
     * ★ 「给模型看」意味着要**带上它写错了的那个东西**,而不是「非法命令」四个字。
     *   4B 靠回执学下一个动作,四个字它学不到任何东西。
     */
    fun reject(cmd: String): String? {
        val c = cmd.trim()
        if (c.isEmpty()) return "命令是空的,没有可跑的东西。"
        if (hasDotDot(c)) {
            return "命令里有「..」,沙盒里不许用它爬目录。当前目录就是沙盒的家目录," +
                "所有路径都写成相对的,比如 `ls`、`cat notes.txt`。"
        }
        val bad = hostPaths(c)
        if (bad.isNotEmpty()) {
            return "命令里有沙盒外的绝对路径:" + bad.joinToString("、") +
                "。沙盒里只能用相对路径(当前目录 = 家目录)。" +
                "要读宿主机上的文件,得先问过用户,那不是这一条命令能做的事。"
        }
        return null
    }

    /**
     * 这条命令**会不会出网**。
     *
     * ★★ 2026-11-08(体检 B1):总闸关着时,`busybox wget` 完全绕开 [CloudNet] ——
     *   [Sandbox.SKIP] 只是「不建软链」,`bin/busybox` 始终在;`linux` 那条更是一整台
     *   发行版(装个 curl 就能连)。所以光靠 SKIP 不等于闸门成立。
     *
     * ★ 这是一道**词法上的门**,不是网络命名空间:沙盒和 App 共用同一个网络栈,
     *   Android 又不许普通 App 建 netns。它封的是「明写出来的联网命令」,
     *   不是「一切可能的出网」(装个 python 再开 socket 就绕过去了)。
     *   这条边界必须照着说 —— 见 [Sandbox.refreshDns] 和 B2 那笔账。
     */
    private val NETWORK_CMD = Regex(
        """(?i)(?:^|[\s;&|()'"`/+])(?:wget|nc|ncat|netcat|telnet|ftpget|ftpput|tftp|""" +
            """ping6?|nslookup|httpd|udhcpc|udhcpd|curl|ssh|scp|sftp|socat)\b"""
    )

    /** `apk` 里会去仓库取东西的那几个子命令。`del`/`list` 是本地的,不在此列。 */
    private val APK_NET = Regex("""(?i)\bapk\s+(?:add|update|upgrade|fetch|fix)\b""")

    fun usesNetwork(cmd: String): Boolean =
        NETWORK_CMD.containsMatchIn(cmd) || APK_NET.containsMatchIn(cmd)

    /** 输出太长时截断。★ 回执进的是上下文,**不截断等于拿一次 `find /` 把窗口撑爆**。 */
    fun clip(s: String, max: Int): String {
        val t = s.trimEnd()
        if (t.length <= max) return t
        return t.take(max) + "\n…(截断:原长 ${t.length} 字符,这里只给了前 $max 个)"
    }

    /**
     * 一句话说清这次跑成了没有。
     *
     * ★ 退出码 0 **不等于**事情办成了(`grep` 没找到也是 0、`false` 是 1)。
     *   所以这句只报**发生了什么**,不替用户判「成功」。
     */
    fun verdict(code: Int, timedOut: Boolean, stdout: String, stderr: String): String = when {
        timedOut -> "超时了,进程被杀掉,这个结果不完整。"
        code == 0 -> if (stdout.isBlank() && stderr.isBlank()) "跑完了(退出码 0),没有任何输出。"
                    else "跑完了(退出码 0)。"
        code == 127 -> "命令没找到(退出码 127)—— 沙盒里没有这个程序,先 `busybox --list` 看看有什么。"
        else -> "跑完了,退出码 $code(不是 0,说明它自己报了失败)。"
    }
}
