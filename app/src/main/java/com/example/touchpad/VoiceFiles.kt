package com.example.touchpad

import java.io.File

/**
 * 「模型文件到底怎么了」的体检。嗓子([SherpaVoice])和耳朵([Ear])共用。
 *
 * ## ★★ 它替掉的是一个**会说谎**的判断
 *
 * 老的写法是 `File(f).isFile && File(f).length() > 0`。这两句在
 * **「文件在、但 app 进不去这个目录」**时**都返回 false** ——
 * 因为 `isFile` 要 stat,stat 要**父目录的执行位**。于是它报的是「模型不在」,
 * **而模型明明在盘上、`ls` 看得见**。
 *
 * 2026-10-05 就为这个在 `tts/` 上栽过一次:真因是 `adb push` 建的目录
 * **属主 `shell`、others 位为 0**,app 的 uid 进不去。
 *
 * ## 为什么非得分开报
 *
 * 三种状态看起来都是「文件有问题」,**修法却完全不同**:
 *
 * | 状态 | 真因 | 修法 |
 * |---|---|---|
 * | 不在 | 没推过 / 推到别处了 | 推文件 |
 * | 在、但读不到 | 权限位不对 | `chmod -R a+rX` |
 * | 在、但是空的 | 传输断在半路 | **重推**(而它看起来「文件已经有了」) |
 *
 * 报错了就得能分清是哪一种,否则这一行日志只是把人支使去干错事。
 *
 * ## 分层
 *
 * [reasonFor] 是**纯判定**(零 IO,能跑 JVM 单测);
 * [problems] 负责去问盘(所以它只能在真机上跑)。
 */
internal object VoiceFiles {

    /**
     * 一个文件的状态 → 人话;`null` = 好。
     *
     * ★ 三个入参由**调用方**去问盘 —— 这是为了让它零 IO,单测里直接传 `false/false/0` 就行。
     *   三个布尔/数字的**组合**才是重点,而且它们错一个就是一种不同的病。
     *
     * ★ 路径写进消息里:他要知道**该去动哪个文件**,光说「缺件」什么忙都帮不上。
     */
    internal fun reasonFor(
        path: String,
        exists: Boolean,
        canRead: Boolean,
        length: Long,
    ): String? = when {
        !exists -> "$path(不在)"
        !canRead -> "$path(在,但读不到 —— 是权限问题,chmod 一下就好,不是文件没推)"
        length <= 0L -> "$path(在,但是空的 —— 传输断在半路,得重推)"
        else -> null
    }

    /**
     * 和 [reasonFor] 同一个三分法,但对象是**一个目录**。
     *
     * ★ 为什么单开一个:ZipVoice 那份嗓子里有个 `espeak-ng-data/`,
     *   它是**目录不是文件**,拿 [reasonFor] 去量会得到「在,但是空的」
     *   (目录的 `length()` 经常是 0 或 4096,和内容无关)—— 那是一句假话。
     *
     * ★ `canExecute` 这一位在目录上比 `canRead` 更要紧:目录**进不去**时,
     *   里面每个文件都会退化成「不在」,而那正是 [blockedAt] 说的那个病。
     *
     * ★ 入参同样由调用方去问盘(零 IO),`entryCount` 由调用方 `list()` 出来 ——
     *   **`list()` 回 null(读不到)时按 0 传**,于是它会落在「空的」那一支上,
     *   和「读不到」长得一样。所以那一支的话里两个可能都提了一句。
     */
    internal fun reasonForDir(
        path: String,
        exists: Boolean,
        canRead: Boolean,
        canExecute: Boolean,
        entryCount: Int,
    ): String? = when {
        !exists -> "$path(不在)"
        !canRead || !canExecute -> "$path(在,但进不去 —— 是权限问题,chmod 一下就好,不是没推)"
        entryCount <= 0 -> "$path(在,但是空的(或者读不到)—— 传输断在半路,得重推)"
        else -> null
    }

    /**
     * 从 [top] 一层层走到 [dir],返回**第一处走不过去的地方**;全通 = `null`。
     *
     * ★★ 必须**从外往里**走。判断「`dir` 在不在」要先能 stat 它,而 stat 需要
     *   **父目录的执行位** —— 外层进不去时,内层的判断全部会退化成「不在」。
     *   倒着走就会把「进不去」报成「不在」,**那正是老的 `filesPresent` 犯的错**。
     *
     * ★ 走到某一层时,它的所有祖先都已经验过「进得去」,所以此刻的
     *   `exists()` 说的才是真话 —— 这一步的顺序不是风格问题,是正确性。
     */
    internal fun blockedAt(top: File, dir: File): String? {
        val chain = ArrayList<File>()
        var f: File? = dir
        while (f != null && f.path.length >= top.path.length) {
            chain.add(f)
            f = f.parentFile
        }
        chain.reverse()                                   // 最外层在前

        for (d in chain) {
            if (!d.exists()) return "目录还不存在:${d.absolutePath}"
            // ★ 目录的 canRead 管「列得出名字」,canExecute 才管「进得去、能 stat 里面的东西」。
            //   两个都要 —— 只查 canRead 的话,`--x` 那种目录会被判成好的。
            if (!d.canRead() || !d.canExecute()) {
                return "进不去这个目录:${d.absolutePath}" +
                    "(权限不对 —— chmod 一下就好,不是文件没推)"
            }
        }
        return null
    }

    /**
     * [dir] 下这 [names] 个文件里有哪些有问题。**空 = 齐了。**
     *
     * ★ 目录本身走不通时**只报那一句** —— 那种情况下每个文件都会被判成「不在」,
     *   而「不在」是假的。少说一句话,比说五句假话强。
     */
    internal fun problems(top: File, dir: File, names: List<String>): List<String> {
        blockedAt(top, dir)?.let { return listOf(it) }
        return names.mapNotNull { n ->
            val f = File(dir, n)
            reasonFor(f.absolutePath, f.exists(), f.canRead(), f.length())
        }
    }
}
