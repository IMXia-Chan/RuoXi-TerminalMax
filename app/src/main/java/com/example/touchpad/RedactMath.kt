package com.example.touchpad

/**
 * **出门前把标识符换成代号，回来再换回去。** 纯字符串进、纯字符串出。
 *
 * 他 2026-11-06 问「上传的资料会不会有风险」，随后拍板：
 * 「代号替换 + 规则抹除」—— 姓名/电话/证件号这类东西留在本机，云端看到的是一份代号。
 *
 * ## 为什么不是「让本地模型抹掉」
 *
 * 因为模型抹不干净，而且**不报错**：漏一个就是一次真泄露，而界面上写着「已脱敏」。
 * 这里只做**格式能认出来的**那几类：认得出来的用代码换，认出来的一定对、可以单测。
 * 认不出来的（地址、公司名、体检结论）挡不住 —— 那属于内容本身，
 * 任何自动手段都挡不住，这一点必须写清楚，不能让人以为「脱敏了 = 安全了」。
 *
 * ## 稳定的意思是**同一个值永远是同一个代号**
 *
 * 一份资料里同一个手机号出现八次，云端看到的都是「［电话1］」——
 * 它才能推理出「这两处说的是同一个人」。每次现编一个代号会把这件事毁掉。
 *
 * ## 还原只在本机做
 *
 * 云端回来的答案带着代号，[Vault.restore] 按同一张表换回真值再给人看。
 * 表只活在这一趟里（[Vault] 是个普通对象，不落盘）——
 * 落盘的话「本机的敏感清单」会变成一个新的东西要保护，得不偿失。
 */
internal object RedactMath {

    /** 一类标识符。`kind` 会写进代号里（`［电话1］`），也是人看得懂的说法。 */
    data class Rule(val kind: String, val regex: Regex)

    /**
     * 顺序有意义：**长格式先跑**。
     *
     * 18 位证件先于 16~19 位卡号，否则一张身份证会被切成「卡号」。反过来
     * 先跑电话的话，一个 19 位的订单号里会抠出 11 位当电话。
     * 每条都用前后不接数字的边界钉住。
     */
    private val RULES = listOf(
        Rule("证件", Regex("(?<![0-9A-Za-z])\\d{17}[0-9Xx](?![0-9A-Za-z])")),
        Rule("邮箱", Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")),
        Rule("电话", Regex("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)")),
        // 带分隔的卡号（4 位一组）。不带分隔的长数字放后面。
        Rule("卡号", Regex("(?<!\\d)(?:\\d{4}[ -]){3}\\d{4,7}(?!\\d)")),
        Rule("卡号", Regex("(?<![0-9A-Za-z])\\d{16,19}(?![0-9A-Za-z])")),
        // 各家密钥都是「前缀 + 下划线 + 一长串」。抄出去等于把账号送人。
        Rule("密钥", Regex("\\b(?:sk|pk|ak|ghp|gho|ghs|xox[baprs])[-_][A-Za-z0-9_-]{16,}")),
    )

    /**
     * 一趟活一本账：原文 → 代号，代号 → 原文。
     *
     * ★ 一次派活一个 [Vault]，**不要跨活复用**：复用了，代号会越攒越多，
     *   而这份表没有落盘，第二天就对不上了。
     */
    class Vault {
        private val fwd = LinkedHashMap<String, String>()
        private val back = LinkedHashMap<String, String>()
        private val seq = HashMap<String, Int>()

        fun redact(text: String): String {
            if (text.isEmpty()) return text
            var out = text
            for (r in RULES) {
                out = r.regex.replace(out) { m ->
                    val raw = m.value
                    fwd.getOrPut(raw) {
                        val n = (seq[r.kind] ?: 0) + 1
                        seq[r.kind] = n
                        "［${r.kind}$n］".also { back[it] = raw }
                    }
                }
            }
            return out
        }

        /** 云端回来的字里带着代号，换回真值。**没有代号就是原样返回。** */
        fun restore(text: String): String {
            if (back.isEmpty() || text.isEmpty()) return text
            var out = text
            for ((token, raw) in back) {
                out = out.replace(token, raw)
                // 模型有时会把全角方括号写成半角（`[电话1]`）。这一下不是洁癖：
                // 换不回来，用户就看到一串代号，而他没法知道那是什么。
                out = out.replace(token.replace('［', '[').replace('］', ']'), raw)
            }
            return out
        }

        fun isEmpty(): Boolean = fwd.isEmpty()

        /** 这一趟一共换掉了几种（日志/回执里给个数，不给内容）。 */
        fun size(): Int = fwd.size
    }
}
