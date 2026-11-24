package com.example.touchpad

import java.io.File

/**
 * 工作台「代码」这一档的地基：**一个只认 `/root/work` 的工程目录** + 几条纯规则。
 *
 * 他要的是「像 Claude Code 那样在手机上 vibecoding」：说一件东西 → 它自己写文件、
 * 自己跑、看到报错自己改，直到真跑通。
 *
 * 所以这里要的不是「跑一条命令」，而是**给模型一双手**：列目录、读文件、写文件、跑命令。
 * 四件事都必须落在这个目录里 —— 模型的路径参数是它自己写的，`../..` 这类东西
 * 一定会在某次改乱之后出现，而那时候它在写的是他自己手机上的东西。
 *
 * ★ 判据放在这个文件里（[WorkCodeMath]），因为它错了**不崩**：
 *   路径漏了越界检查、输出没截断，表现都是「跑着跑着开始胡来」，真机上只能靠结果反推。
 */
internal object WorkCodeMath {

    /** 一次最多做几步。到顶就收口 —— 没人管的循环是烧电的，而手机是热的。 */
    const val MAX_STEPS = 24

    /** 单次 `read` 最多回多少字。整份源码塞进去，下一轮就没地方放别的了。 */
    const val READ_CHARS = 20_000

    /** 单次 `run` 的输出最多回多少字。编不过时的报错动辄上百行。 */
    const val OUT_CHARS = 6_000

    /** 一次列表最多列多少项（目录里有几千个文件时不至于把回执撑爆）。 */
    const val MAX_ENTRIES = 200

    /** 清单里一个文件的显示名最长几个字。 */
    private const val NAME_MAX = 60

    /**
     * 模型给的路径 → 相对工作区的规范路径。**null = 越界，一律不执行。**
     *
     * 认三种写法：`a/b.txt`、`./a/b.txt`、`/root/work/a/b.txt`。
     * 其余带 `/` 开头的（`/etc/passwd`、`/root/...` 别的目录）都拒 —— 它在沙盒里
     * 本来也碰不到手机，但**在 proot 里能碰到整套发行版**，删了就得重装环境。
     */
    fun rel(raw: String): String? {
        var s = raw.trim().replace('\\', '/')
        if (s.isEmpty()) return "."
        if (s.startsWith("/")) {
            s = when {
                s == "/root/work" -> ""
                s.startsWith("/root/work/") -> s.removePrefix("/root/work/")
                else -> return null
            }
        }
        val parts = ArrayList<String>()
        for (p in s.split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                else -> parts.add(p)
            }
        }
        return if (parts.isEmpty()) "." else parts.joinToString("/")
    }

    /**
     * 一段输出留多少字。
     *
     * ★ **掐中间，留头留尾**：头是命令本身和第一条报错，尾是最后的 `error:`/退出码 ——
     *   编译器把最有用的那句放在最后。从中间截断会把两头都丢掉，只剩一堆无意义的中段。
     */
    fun clip(s: String, n: Int = OUT_CHARS): String {
        val t = s.trimEnd()
        if (t.length <= n) return t
        val head = n * 6 / 10
        val tail = n - head - 40
        return t.substring(0, head) +
            "\n…（中间省掉 ${t.length - head - tail} 字；全文在沙盒的 /root/work 里）…\n" +
            t.substring(t.length - tail)
    }

    /** 清单里的一行。目录带斜杠，文件带大小 —— 一眼能分清该 read 还是该 ls 进去。 */
    fun entryLine(name: String, isDir: Boolean, bytes: Long): String {
        val n = if (name.length > NAME_MAX) name.take(NAME_MAX) + "…" else name
        return if (isDir) "  d  $n/" else "  -  $n  " + WorkAttachMath.humanSize(bytes)
    }
}

/**
 * 工作区里的文件操作。**只碰 [root] 底下**，返回的都是给人/给模型看的一句话。
 *
 * ★ 返回字符串而不是 JSONObject：这个类要能在纯 JVM 测试里跑（[WorkCodeMathTest]），
 *   而 `org.json` 在单测里是空壳（见项目里那条纪律）。
 */
internal class WorkFiles(private val root: File) {

    companion object {
        /** 规范化之后的路径（只用来记账）。`null` = 越界或就是工作区本身。 */
        fun nameOf(raw: String): String? = WorkCodeMath.rel(raw)?.takeIf { it != "." }
    }

    fun ensure(): String? =
        if (root.isDirectory || root.mkdirs() || root.isDirectory) null
        else "工作区建不出来：${root.path}"

    /** 列目录。空目录也要**明说**是空的 —— 「什么都没有」和「没读到」是两件事。 */
    fun list(path: String): String {
        val rel = WorkCodeMath.rel(path) ?: return "不在工作区里：$path（只能用 /root/work 底下的路径）"
        val f = File(root, rel)
        if (!f.exists()) return "没有这个路径：$rel"
        if (!f.isDirectory) return "$rel 是个文件（${WorkAttachMath.humanSize(f.length())}），用 read 看内容。"
        val kids = f.listFiles()
        if (kids == null) return "列不出来：$rel（权限或不是目录）"
        if (kids.isEmpty()) return "$rel 是空的。"
        val sorted = kids.sortedBy { it.name }
        return buildString {
            append(rel).append(" 里有 ").append(sorted.size).append(" 项：\n")
            for (k in sorted.take(WorkCodeMath.MAX_ENTRIES)) {
                append(WorkCodeMath.entryLine(k.name, k.isDirectory, k.length())).append('\n')
            }
            if (sorted.size > WorkCodeMath.MAX_ENTRIES) {
                append("…还有 ").append(sorted.size - WorkCodeMath.MAX_ENTRIES).append(" 项没列\n")
            }
        }.trimEnd()
    }

    /** 读一份文件。二进制不读正文 —— 读出来是一屏乱码，对下一步没有任何用处。 */
    fun read(path: String, chars: Int): String {
        val rel = WorkCodeMath.rel(path) ?: return "不在工作区里：$path"
        val f = File(root, rel)
        if (!f.isFile) return "没有这个文件：$rel"
        // ★ 没给（0）就是「按上限读」。写成 coerceIn 会把 0 夹成 200 ——
        //   模型不传这个参数时只拿到两百字，而回执看起来完全正常。
        val n = if (chars <= 0) WorkCodeMath.READ_CHARS else chars.coerceIn(200, WorkCodeMath.READ_CHARS)
        // ★ 先看大小再读：几百 MB 的文件一口气 readText 会把 App 撑爆。
        if (f.length() > 4L * 1024 * 1024) {
            return "$rel 太大（${WorkAttachMath.humanSize(f.length())}），不整个读。" +
                "要的话用 run 跑 head / tail / grep 取需要的那一段。"
        }
        val text = try {
            f.readText()
        } catch (e: Exception) {
            return "读不动 $rel：${e.message ?: "未知原因"}"
        }
        if (!WorkAttachMath.looksTextual(text)) {
            return "$rel 不像是文本（读出来是乱码），按二进制文件处理。" +
                "要知道它是什么，用 run 跑 file 或者看大小。"
        }
        if (text.length <= n) return "$rel（共 ${text.length} 字）：\n$text"
        return "$rel（共 ${text.length} 字，这里给前 " + n + " 字）：\n" + text.take(n) +
            "\n…（后面还有；要看某一段就用 run 跑 sed -n / grep）"
    }

    /**
     * 在工作区里按词或正则找出处。回 `路径:行号: 那一行`。
     *
     * ★ 加它的原因：`read` 一次只回 [WorkCodeMath.READ_CHARS] 字，一份文件都读不完。
     *   没有 grep，模型改一大份文件只能「猜着写」或者整份重写 —— 而整份重写会把
     *   它没读到的那一半悄悄冲掉，回执上还写着「写好了」。
     *
     * ★ 正则写坏了不报错，**退回按字面找**，并在回执第一行写明 —— 静默退回会让它
     *   以为正则已经生效，然后拿不到该有的命中还以为文件里没有。
     */
    fun grep(pattern: String, path: String, max: Int): String {
        val raw = pattern.trim()
        if (raw.isEmpty()) return "没给要搜什么。"
        val rel = WorkCodeMath.rel(path) ?: return "不在工作区里：$path"
        // ★ 别写成 `File(root, ".")`：它的名字是「.」，会被下面那条「跳过隐藏目录」
        //   当成隐藏目录而**一个子目录都不进** —— 结果是「整个工作区都搜不到」。
        val start = if (rel == ".") root else File(root, rel)
        if (!start.exists()) return "没有这个路径：$rel"
        val re = try {
            Regex(raw)
        } catch (_: Exception) {
            Regex(Regex.escape(raw))
        }
        val literal = try { Regex(raw) } catch (_: Exception) { null } == null
        val limit = if (max <= 0) 40 else max.coerceIn(1, 200)

        val hits = ArrayList<String>()
        var scanned = 0
        fun scan(f: File, name: String) {
            if (hits.size >= limit) return
            if (f.length() > 512L * 1024) return
            val text = try { f.readText() } catch (_: Exception) { return }
            if (!WorkAttachMath.looksTextual(text)) return
            scanned++
            var line = 0
            for (l in text.lineSequence()) {
                line++
                if (l.length > 400) continue          // 压缩成一行的文件：搜到了也读不了
                if (re.containsMatchIn(l)) {
                    hits.add(name + ":" + line + ": " + l.trim().take(160))
                    if (hits.size >= limit) return
                }
            }
        }
        if (start.isFile) scan(start, rel)
        else start.walkTopDown().onEnter { it == start || !it.name.startsWith(".") }
            .filter { it.isFile }
            .forEach { f ->
                if (hits.size >= limit) return@forEach
                scan(f, f.relativeTo(root).path.replace('\\', '/'))
            }

        val head = (if (literal) "（正则写坏了，已按字面搜「$raw」）\n" else "")
        if (hits.isEmpty()) {
            return head + "没搜到「$raw」。（看了 $scanned 份文件）" +
                "如果它应该存在：换个更短的词再搜一次，别连着搜五六遍。"
        }
        return head + buildString {
            append("搜到 ").append(hits.size).append(" 处")
            if (hits.size >= limit) append("（到上限 $limit 条就停了，可能还有）")
            append("：\n")
            for (h in hits) append(h).append('\n')
        }.trimEnd()
    }

    /**
     * 把文件里的 [old] 换成 [new]。**一字不差**才算命中。
     *
     * ★ 命中零处或多处都**拒绝执行**并说清命中了多少处：多处命中时盲换会把
     *   另一个函数里的同名两行也换掉，而回执只有一句「改好了」。
     * @param all `true` 时允许全换（模型明确要改所有同名处时用）。
     */
    fun patch(path: String, old: String, new: String, all: Boolean): String {
        if (old.isEmpty()) return "没给要替换掉的原文（old）。"
        val rel = WorkCodeMath.rel(path) ?: return "不在工作区里：$path（只能用 /root/work 底下的路径）"
        val f = File(root, rel)
        if (!f.isFile) return "没有这个文件：$rel（新文件用 write）"
        if (f.length() > 4L * 1024 * 1024) {
            return "$rel 太大（${WorkAttachMath.humanSize(f.length())}），不整份改。" +
                "用 grep 定位行号，再用 run 跑 sed 改那一段。"
        }
        val text = try { f.readText() } catch (e: Exception) {
            return "读不动 $rel：${e.message ?: "未知原因"}"
        }
        var count = 0
        var at = text.indexOf(old)
        val firstAt = at
        while (at >= 0) {
            count++
            at = text.indexOf(old, at + old.length)
            if (!all && count > 1) break
        }
        if (count == 0) {
            return "文件里没有这段原文 —— 一个字都没改。" +
                "把你要改的那几行**原样**带上（含缩进、含换行）再试一次；先 read 一下确认它现在长什么样。"
        }
        if (count > 1 && !all) {
            return "这段原文在 $rel 里命中了 $count 处，不够精确，一个字都没改。" +
                "把前后几行也带上让它只命中一处；确实要全换就带上 all=true。"
        }
        val out = if (all) text.replace(old, new) else {
            val b = StringBuilder(text)
            b.replace(firstAt, firstAt + old.length, new)
            b.toString()
        }
        return try {
            f.writeText(out)
            "改了 $rel：替换 $count 处（现在 ${WorkAttachMath.humanSize(f.length())}）。"
        } catch (e: Exception) {
            "写不进 $rel：${e.message ?: "未知原因"}"
        }
    }

    /**
     * 写一份文件。父目录自动建。
     *
     * ★ 会**明说覆盖**：同名文件已经存在时回执里写出来。模型看不见这条的话，
     *   它会以为自己在改一份新文件，而实际把之前写的全冲掉了。
     */
    fun write(path: String, content: String): String {
        val rel = WorkCodeMath.rel(path) ?: return "不在工作区里：$path（只能用 /root/work 底下的路径）"
        if (rel == ".") return "没给文件名。"
        val f = File(root, rel)
        val existed = f.isFile
        f.parentFile?.mkdirs()
        return try {
            f.writeText(content)
            (if (existed) "覆盖了 $rel" else "写好了 $rel") +
                "（${content.length} 字，现在 " + WorkAttachMath.humanSize(f.length()) + "）"
        } catch (e: Exception) {
            "写不进 $rel：${e.message ?: "未知原因"}"
        }
    }
}
