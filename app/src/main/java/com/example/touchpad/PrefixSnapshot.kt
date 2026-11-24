package com.example.touchpad

import java.security.MessageDigest

/**
 * 公共前缀的**槽位快照**:把「系统提示 + 工具表」这截算出来的 KV 落到盘上,
 * 下次启动直接读回来 —— **冷启动 212~627 秒变成读盘约 1 秒**。
 *
 * 为什么这条路值钱:它不是「让预热快一点」,是**把预热整个删掉**。还原的是
 * `llama_state_seq_load_file` 读回来的**同一份 KV**,不是近似、不是重算,
 * 一个 token 都不差(见 memory: `ruoxi-prefix-budget-and-warmup`)。
 *
 * ## 三个失败模式,三道各管一段的闸
 *
 * 快照错了的症状**不是崩溃,是胡言乱语** —— 静默的错。所以三件事各由一道闸管:
 *
 * | 失败模式 | 谁管 |
 * |---|---|
 * | 提示词/工具表改了,快照过期 | **文件名**:[key] 把这些都哈希进去,一改就换名,旧文件永远匹配不上 |
 * | 模型换了(同名不同权重) | **文件名**:[key] 也吃模型字节数 |
 * | 文件半截 / 损坏 / 放不下 | **服务端自己**:`action=restore` 会跑 `restored.validate()`,不过就报错并清空该槽 |
 *
 * 最后一条是**为什么这里不需要「已知答案探针」**:restore 的回包自带 `n_restored`
 * (服务端说它真吃进去了多少 token),拿它和存盘时的 `n_saved` 一比对就够了 ——
 * 这是**直接量那个东西本身**,比问模型一个问题再猜它答得对不对强,而且**不用发额外请求**。
 * (发探针请求会踩另一个已知的坑:客户端超时撤了,**服务端还在算**,下一句话堵在它后面。)
 *
 * ## 纯逻辑,没有 org.json
 *
 * 项目惯例([ToolDrift]/[HandModel] 都这么分):判定规则留在这里,JVM 单测才跑得动。
 * 真正发 HTTP 的那步在 [AiAgent] 里 —— 它手里才有 base url 和 key。
 */
internal object PrefixSnapshot {

    /**
     * 快照格式的版本号。
     *
     * 改**这个文件的任何逻辑**(比如以后换哈希算法、换文件名格式)都要 +1 —— 否则
     * 新代码会去读旧格式的文件。宁可让所有人多冷算一次,也不要读一份自己看不懂的快照。
     */
    const val VERSION = "v1"

    /** 文件名长度上限。抄的是 llama.cpp 的 `fs_validate_filename`(超过它就 400)。 */
    private const val MAX_NAME = 255

    /**
     * 一份快照的**身份**。下面每一个输入变了,这份快照都必须作废:
     *
     *  - [systemPrompt] / [toolsJson]:**这就是被缓存的那截前缀本身**,改一个字都算换了一份。
     *  - [modelName] / [modelBytes]:同名换个量化、换份权重,词表和 KV 布局都可能不同。
     *    ⚠️ 只认**字节数**不认 mtime:用户重新 push 同一个文件(mtime 变、内容没变)时
     *    不该作废,而换个模型几乎不可能字节数正好一样。
     *  - [nCtx] / [cacheTypes]:KV 的形状。`-c` 或 `--cache-type-*` 一动,
     *    `llama_state_seq_load_file` 读回来的东西就对不上了。
     *
     * 返回 32 个十六进制字符 —— 短得能当文件名,又足够长到不会撞。
     */
    fun key(
        systemPrompt: String,
        toolsJson: String,
        modelName: String,
        modelBytes: Long,
        nCtx: Int,
        cacheTypes: String,
    ): String {
        // 用 NUL 当分隔符:这几个输入都是文本,里面不可能有 NUL,所以不会出现
        // 「(a,b) 和 (a+b,'') 拼出同一个串」这种撞车。
        val joined = listOf(
            VERSION,
            systemPrompt,
            toolsJson,
            modelName,
            modelBytes.toString(),
            nCtx.toString(),
            cacheTypes,
        ).joinToString("\u0000")

        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        // 取前 16 字节(=32 个十六进制字符)。截断是为了当文件名好看,不是为了省事:
        // 撞车在这里的后果是「读到别人的快照」,2^128 的余量够用。
        return buildString(32) {
            for (i in 0 until 16) {
                val b = digest[i].toInt() and 0xFF
                append(HEX[b ushr 4])
                append(HEX[b and 0x0F])
            }
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /** KV 本体。`--slot-save-path` 的目录下就是这个名字。 */
    fun fileName(key: String): String = "$key.bin"

    /**
     * 存盘时服务端报的 `n_saved`,写在这个小文件里,还原时拿出来对账。
     *
     * 为什么不从 `.bin` 自己推:那要解析 llama.cpp 的私有格式。**让服务端自己说**
     * 是唯一不依赖内部实现的读法。
     */
    fun metaName(key: String): String = "$key.tokens"

    /**
     * 文件名合不合法 —— 镜子照着 llama.cpp 的 `fs_validate_filename` 抄的
     * (`common/common.cpp:796`),因为它**不合法就直接 400**,而那时我们只会看到
     * 一句「Invalid filename」,完全不知道是自己哪个字符写错了。
     *
     * 我们实际用的名字是纯十六进制 + `.bin`,**永远合法**;这个函数存在的意义是
     * **万一以后有人改了 [fileName] 的格式**,当场被 [PrefixSnapshotTest] 拦住,
     * 而不是等到真机上还原失败、还以为是别的原因。
     */
    fun isValidFileName(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_NAME) return false
        for (c in name) {
            // 控制字符(C0 / DEL / C1)
            if (c <= '\u001F' || c == '\u007F' || (c in '\u0080'..'\u009F')) return false
            // 路径分隔符与非法字符 —— 少了 `/` 和 `\` 就能往上级目录写,这是安全边界
            if (c == '/' || c == '\\' || c == ':' || c == '*' ||
                c == '?' || c == '"' || c == '<' || c == '>' || c == '|'
            ) return false
        }
        return true
    }

    /**
     * **还原到底成没成。** 判据只有一条:服务端说它吃进去的 token 数,
     * 和当初存下去的那个数**一模一样**。
     *
     * 为什么不是「大于 0 就行」:少还原一截的话,KV 里只有半截前缀,剩下的部分
     * 那轮请求还得冷算 —— 而我们**已经据此跳过了预热**,于是用户的第一句话
     * 照样要等好几分钟。**那正是这个功能本来要治的病。**
     */
    fun restoreHolds(restoredTokens: Int, savedTokens: Int): Boolean =
        savedTokens > 0 && restoredTokens == savedTokens

    /**
     * 一份快照**值不值得留**的下限。
     *
     * ⚠️ 这**不是**一个精确的界。精确的界 = 「系统提示 + 工具表到底多少 token」,那是服务端
     * 渲染完 chat 模板才知道的事,客户端算不准;硬凑一个贴近真值的数,以后一缩短前缀就会
     * **误伤真快照**(而缩短前缀正是路线图上要做的事)。
     *
     * 所以这里刻意取得很低,只拦一类事:**槽里几乎是空的**。真前缀比这个下限长得多,
     * 所以它永远不会拒绝一份真快照 —— 只会在「存了个空槽」时挡住我们。
     *
     * ★ 为什么这种事非拦不可:那种快照最毒。它**还原会成功、对账也会平**
     * (值多少就还原多少),于是我们理直气壮地**跳过预热** —— 而前缀根本就没算。
     * 症状是用户第一句话照样冷算好几分钟,而日志上写得漂漂亮亮。
     */
    const val MIN_WORTH_KEEPING = 256

    /** 见 [MIN_WORTH_KEEPING]。 */
    fun worthKeeping(savedTokens: Int): Boolean = savedTokens >= MIN_WORTH_KEEPING

    /**
     * **这份快照完整吗** —— 存下来的 token 数,必须不少于这次请求真实用掉的 prompt 长度。
     *
     * ## 为什么非有这条不可
     *
     * ★ 2026-10-04 真机实测出来的:**这个功能最大的风险不是「存不下来」,是「存下来一份
     * 看起来完全正常的残废快照」。**
     *
     * llama-server 在**客户端断开**的时候会发 CANCEL 把槽 release 掉
     * (`server-queue.cpp` `server_response_reader::stop()`),预填充只跑完手上那几个 batch
     * 就停 —— 实测卡在 4096(=2×2048,正好 batch 边界),而真前缀是 4889。
     *
     * 残废快照毒在**它每一道闸都过得去**:
     *
     * | 闸 | 残废快照的表现 |
     * |---|---|
     * | 文件名哈希(过期/换模型) | 过得去 —— 名字本来就是对的 |
     * | `n_saved > 0` / [worthKeeping] | 过得去 —— 4096 远大于 256 |
     * | 还原时 `n_restored == n_saved` | **也过得去** —— 4096 == 4096,一模一样 |
     *
     * 于是日志会写「快照还原成功(4096 token),跳过冷算」,而 4889 里少的那 793 个
     * 根本没算 —— 用户第一句话照样冷算好几分钟。**日志上漂漂亮亮,症状是「她今天有点慢」。**
     *
     * 唯一能识破它的,是拿存下来的数和**这次请求真实用掉的 prompt 长度**比 ——
     * 那个数是服务端自己在回包里报的(`usage.prompt_tokens`),不用猜。
     *
     * [promptTokens] <= 0 表示拿不到真值(例如老版本服务端不报)。那时只能退回
     * [worthKeeping] 那道弱闸 —— **但调用方不该把这条当常态**,拿不到真值就别存。
     */
    fun coversFullPrompt(savedTokens: Int, promptTokens: Int): Boolean =
        worthKeeping(savedTokens) && (promptTokens <= 0 || savedTokens >= promptTokens)

    /** 写进 [metaName] 的内容。 */
    fun encodeMeta(savedTokens: Int): String = savedTokens.toString()

    /** 读回 [metaName]。读不出来就回 -1 —— [restoreHolds] 会因此判「不成立」,走冷算。 */
    fun decodeMeta(text: String?): Int = text?.trim()?.toIntOrNull() ?: -1
}
