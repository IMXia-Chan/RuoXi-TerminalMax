package com.example.touchpad

/**
 * 「本地模型这次回的错误,是不是『装不下』」——**纯函数,能跑 JVM 单测**。
 *
 * ## 为什么值得单拎出来
 *
 * 这个判断只决定一件事:**要不要把这一轮转给云端**。判错的两种代价很不对称:
 *
 * - **漏判**(该转没转)= 模型回 400,她把这一轮**整个丢掉**,用户看到的只是
 *   「她不理我」,日志里才有半句英文。2026-11-06 真机上就是这么哑了一整天。
 * - **误判**(不该转转了)= 一个本来就拼错的请求多花一次云端的钱,然后报一个
 *   跟本地一样的错。**便宜得多。**
 *
 * 所以判据从宽:只要 llama.cpp 那句特征话出现就算。
 *
 * ★ 但**不能**把所有 400 都算进来 —— 请求真的拼错时,云端只会再错一次,
 *   而我们会在日志里留下「转云端了」的假线索,把真正的原因盖掉。
 */
internal object LocalErrorMath {

    /** llama.cpp 在 `n_prompt_tokens > n_ctx` 时回的 error type。 */
    private const val MARK_TYPE = "exceed_context_size"

    /** 同一件事的人话版本(不同 llama.cpp 版本措辞不一样,两条都认)。 */
    private const val MARK_TEXT = "exceeds the available context"

    /** 这段错误正文是不是「提示词比上下文长」。 */
    internal fun isContextOverflow(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val s = body.lowercase()
        return s.contains(MARK_TYPE) || s.contains(MARK_TEXT)
    }
}
