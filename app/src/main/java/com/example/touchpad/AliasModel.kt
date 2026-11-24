package com.example.touchpad

/**
 * 「假名」那一档的**纯逻辑**：把号码换成**稳定**的代号，回来时再换回去。
 *
 * ---
 *
 * ## 它解决什么，不解决什么
 *
 * 他要的是「本地加密、云端不能解密、但云端还能想」。**真加密做不到** —— AES 之后
 * 是一串随机字符，云端对着它推理不比掷骰子好。能落地的近似是这个：
 *
 * ```
 *   本地：13812345678  ──→  [PHONE_3f2a91]      （映射表只在手机上）
 *   云端：看到 [PHONE_3f2a91]、[PHONE_3f2a91]
 *         → 知道「这是同一个东西出现了两次」，能推理关系
 *   回来：[PHONE_3f2a91] ──→ 13812345678        （只有本地换得回来）
 * ```
 *
 * **拿到的**：值不出门，而语义（同/不同、出现几次、和哪句相关）还在。
 * **没拿到的**：结构还在外面 —— 云端知道你和一个号码说了多少话、它出现在哪一轮。
 * 那个壳用假名遮不住，所以这一档永远只是「可选」，盖不掉「有敏感就留在本地」那条。
 *
 * ## 为什么假名要**确定**（同一个值永远同一个词）
 *
 * 随机的假名每轮都不一样，云端就会把「同一个号码」当成「两个号码」，
 * 推论全是错的 —— 而且错得很像对的。所以代号由值本身算出来（[code]），
 * 表只负责两件事：记着「这个词对应谁」，以及**撞了**的时候错开一位。
 *
 * ★ 碰撞必须当真：400 条记录、6 位十六进制，撞上的概率不高但**不是零**，
 *   而撞上的表现是「两个号码在云端变成同一个」—— 静默、且会带出一个错误结论。
 *   所以 [AliasMath.taken] 让调用方查，撞了就加后缀。
 *
 * ## ★ 表是明文的
 *
 * 这一条必须说清楚，别让「加密」这个词自欺：映射表存在应用私有目录里，
 * **没有加密**，因为密钥也只能放在同一台机器上 —— 拿到 root 的人两个都拿得到，
 * 加密只是多一层看起来安全的包装。真正保护它的是「它从不出去、也从不进日志」。
 */
internal data class Alias(
    /** 出门的那个词（不含方括号），比如 `PHONE_3f2a91`。 */
    val alias: String,
    /** 哪一类，见 [SecretMath.KIND_*]。 */
    val kind: String,
    /** 真值。★ 只在本机存在。 */
    val value: String,
    val createdAt: Long,
    val lastUsed: Long,
    val hits: Int,
)

internal object AliasMath {

    /** 表最多留多少条。超过就丢**最久没用过**的 —— 常用的那个不该被丢。 */
    const val MAX = 400

    /** 代号里那个词的宽度。6 位十六进制 ≈ 1677 万，撞的概率低到可以忽略但**不是零**。 */
    const val CODE_LEN = 6

    /**
     * 类别 → 出门那个词的前缀。
     *
     * ★ 用英文大写、不用中文：① 模型的分词对它更稳（中文标记容易被切碎）；
     *   ② 和旧的那套 `[手机号]` 一眼能分开 —— 日志里看到哪个，就知道走的是哪一档。
     */
    fun tagOf(kind: String): String = when (kind) {
        SecretMath.KIND_PHONE -> "PHONE"
        SecretMath.KIND_CARD -> "CARD"
        SecretMath.KIND_ID -> "ID"
        SecretMath.KIND_EMAIL -> "EMAIL"
        else -> "SECRET"
    }

    /**
     * 值 → 固定的一小段十六进制（FNV-1a 32 位）。
     *
     * ★ 用它而不是 `hashCode()`：`String.hashCode` 在不同 JVM 上是**定死**的，
     *   但换个语言（电脑端 Python）就对不上；FNV-1a 是几行算术，两边抄得一模一样。
     *   以后电脑那头也要认同一批假名时，这一条就是共享的锚。
     */
    fun code(value: String): String {
        var h = 0x811C9DC5.toInt()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toInt() and 0xFF)
            h *= 16777619
        }
        val hex = Integer.toHexString(h).padStart(8, '0')
        return hex.takeLast(CODE_LEN)
    }

    /** 首次的候选代号（不含方括号）。撞了由 [disambiguate] 往后错。 */
    fun baseAlias(kind: String, value: String): String = tagOf(kind) + "_" + code(value)

    /** 撞了之后的样子：`PHONE_3f2a91-2`。**必须还是同一个前缀**，否则看不出它是什么类。 */
    fun disambiguate(alias: String, n: Int): String = if (n <= 1) alias else "$alias-$n"

    /** 出门那份里它的样子。 */
    fun wrap(alias: String): String = "[$alias]"

    /**
     * 出门那份里认假名的正则。**和 [wrap] 必须成对** —— 这里是**唯一的**读法。
     *
     * ★ 形如 `[PHONE_3f2a91]` 或 `[PHONE_3f2a91-2]`。
     * ★ 它**不会**误伤普通的方括号（`[注]`、`[1]`）：必须有 `字母_十六进制`。
     */
    private val TOKEN_RE = Regex("""\[([A-Z]+_[0-9a-f]{$CODE_LEN}(?:-\d+)?)]""")

    /**
     * 把云端回的正文里认识的假名换回真值。
     *
     * @param lookup 查表。**查不到就原样留着** —— 那可能是云端自己编了一个假名，
     *               也可能是表被清过。留着比删掉好：留着能看出「它提到了一个我不认识的东西」。
     */
    fun restore(text: String, lookup: (String) -> String?): String {
        if (text.isEmpty()) return text
        return TOKEN_RE.replace(text) { m ->
            val alias = m.groupValues[1]
            lookup(alias) ?: m.value
        }
    }

    /** 正文里出现了哪些假名（不含值）。留日志用。 */
    fun mentioned(text: String): List<String> =
        TOKEN_RE.findAll(text).map { it.groupValues[1] }.distinct().toList()

    /**
     * 表满了该丢谁：**最久没用过**的先丢。
     *
     * ★ 不按创建时间丢 —— 那个每天都会用到的号码会被一个只用过一次的新号码挤掉，
     *   而挤掉的表现是「明天它又变成一个陌生的假名」，云端那边的连续性当场断掉。
     */
    fun trim(all: List<Alias>, max: Int = MAX): List<Alias> =
        if (all.size <= max) all else all.sortedByDescending { it.lastUsed }.take(max)

    // ---- 落盘：一行一条，只有本机读 ----
    // 值只有数字和邮箱那种字符，不会有 `|`；类别是中文；时间戳是数字。所以直接切。
    // 坏一行丢一行（同 HandCodec）—— 表坏掉不该让整份读失败，那会让所有假名同时失效。

    fun line(a: Alias): String =
        listOf(a.alias, a.kind, a.value, a.createdAt.toString(), a.lastUsed.toString(), a.hits.toString())
            .joinToString("|")

    fun parse(line: String): Alias? {
        val p = line.split('|')
        if (p.size != 6) return null
        if (p[0].isBlank() || p[1].isBlank() || p[2].isBlank()) return null
        return Alias(
            alias = p[0],
            kind = p[1],
            value = p[2],
            createdAt = p[3].toLongOrNull() ?: return null,
            lastUsed = p[4].toLongOrNull() ?: return null,
            hits = p[5].toIntOrNull() ?: return null,
        )
    }
}
