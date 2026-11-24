package com.example.touchpad

/**
 * **一家模型供应商的预置。**
 *
 * 他 2026-11-06 的原话：「最后有市面上绝大多数的模型，然后用户可选，这样就只填 key 就行了，
 * 反正按照 ccSwitch 那样来」。
 *
 * ★ 存在的理由只有一个：**地址和模型名不该让用户手打**。
 *   填错一个字母的表现是「连接失败」或者更糟 —— 地址对了、模型名错了，
 *   对方回一句「model not found」，而界面上看着一切正常。
 *   预置这一层把那些字固定下来，用户只剩一个 key 要填。
 *
 * ★★ `endpoint` 一律写**完整**（到 `/chat/completions`）：各家的路径不一样，
 *   智谱是 `/api/paas/v4/`、豆包是 `/api/v3/`、通义是 `/compatible-mode/v1/`。
 *   [RouteMath.url] 认「写全了就原样用」，所以这里写全就等于不会拼错。
 *
 * ★ 表里没有的 → 走「自定义」，四个格子自己填。**不删这条路**，
 *   因为私有部署、内网网关、中转站都不在表里，而它们是最常见的一种。
 */
data class LlmVendor(
    /** 稳定 id，只用来做去重和日志，不给人看。 */
    val id: String,
    /** 界面上那一行大字。 */
    val name: String,
    /** 谁家的，副标题用。 */
    val vendor: String,
    /** 完整的 chat/completions 地址。 */
    val endpoint: String,
    /** 可选模型，第一个是默认。 */
    val models: List<String>,
    /** 一句人话：适合干什么、有什么坑。**不写营销词。** */
    val note: String,
    /** 这一家的模型能不能看图。用户仍然可以手动改。 */
    val vision: Boolean = false,
    /** 填写时需要额外注意的一句（比如豆包要填接入点 id）。空 = 没有。 */
    val caveat: String = "",
)

/**
 * 预置供应商表。
 *
 * ★★ **以前这里只收 OpenAI 兼容的端点**，注释写的理由是「Anthropic 的 `/v1/messages`
 *   是另一套协议，混进来会让用户以为填了 key 就能用」。那句话在 2026-11-06 之前是对的。
 *   现在那套协议已经接上了（[AnthropicProto]：正文、鉴权头、响应、流都翻过一遍），
 *   所以 Anthropic 可以摆在这张表里 —— **它不再是「填了也没用」的那一家**。
 *
 * ★ 地址和模型名会变。所以每一家都能「测一测」当场验，而不是等某天调用失败才发现。
 */
internal object LlmCatalog {

    val all: List<LlmVendor> = listOf(
        LlmVendor(
            id = "anthropic",
            name = "Claude",
            vendor = "Anthropic",
            endpoint = "https://api.anthropic.com/v1/messages",
            models = listOf("claude-sonnet-5-5", "claude-haiku-5-5", "claude-opus-5-5"),
            note = "协议和别家不同，已经在本机转过一遍，填 key 就能用。国内访问需要网络条件。",
            vision = true,
        ),
        LlmVendor(
            id = "deepseek",
            name = "DeepSeek",
            vendor = "深度求索",
            endpoint = "https://api.deepseek.com/v1/chat/completions",
            models = listOf("deepseek-chat", "deepseek-reasoner"),
            note = "国内直连，便宜，日常对话和写代码都够用。",
        ),
        LlmVendor(
            id = "qwen",
            name = "通义千问",
            vendor = "阿里云百炼",
            endpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            models = listOf("qwen-plus", "qwen-max", "qwen-turbo", "qwen-vl-max"),
            note = "百炼平台的 key。qwen-vl-max 能看图。",
            vision = true,
        ),
        LlmVendor(
            id = "kimi",
            name = "Kimi",
            vendor = "月之暗面",
            endpoint = "https://api.moonshot.cn/v1/chat/completions",
            models = listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"),
            note = "长文本见长，模型名里的 8k/32k/128k 是上下文长度。",
        ),
        LlmVendor(
            id = "zhipu",
            name = "智谱 GLM",
            vendor = "智谱 AI",
            endpoint = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            models = listOf("glm-4-plus", "glm-4-flash", "glm-4v-plus"),
            note = "glm-4-flash 有免费额度。glm-4v-plus 能看图。",
            vision = true,
        ),
        LlmVendor(
            id = "siliconflow",
            name = "硅基流动",
            vendor = "SiliconFlow",
            endpoint = "https://api.siliconflow.cn/v1/chat/completions",
            models = listOf(
                "deepseek-ai/DeepSeek-V3",
                "Qwen/Qwen2.5-72B-Instruct",
                "THUDM/GLM-4-9B-0414",
            ),
            note = "一个 key 转很多家开源模型，模型名带斜杠。",
        ),
        LlmVendor(
            id = "ark",
            name = "火山方舟",
            vendor = "字节跳动",
            endpoint = "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
            models = listOf(""),
            note = "豆包系列。模型那一栏要填方舟控制台里的「推理接入点 ID」（ep- 开头），不是模型名。",
            caveat = "模型那一栏填 ep- 开头的接入点 ID。",
        ),
        LlmVendor(
            id = "openai",
            name = "OpenAI",
            vendor = "OpenAI",
            endpoint = "https://api.openai.com/v1/chat/completions",
            models = listOf("gpt-4o-mini", "gpt-4o"),
            note = "国内访问需要网络条件，费用按美元计。gpt-4o 能看图。",
            vision = true,
        ),
        LlmVendor(
            id = "openrouter",
            name = "OpenRouter",
            vendor = "OpenRouter",
            endpoint = "https://openrouter.ai/api/v1/chat/completions",
            models = listOf("deepseek/deepseek-chat", "anthropic/claude-sonnet-4.5"),
            note = "一个 key 转几十家模型，模型名带斜杠。按次扣费。",
            vision = true,
        ),
        LlmVendor(
            id = "hunyuan",
            name = "腾讯混元",
            vendor = "腾讯云",
            endpoint = "https://api.hunyuan.cloud.tencent.com/v1/chat/completions",
            models = listOf("hunyuan-turbos-latest", "hunyuan-lite"),
            note = "腾讯云控制台的 API Key。",
        ),
        LlmVendor(
            id = "spark",
            name = "讯飞星火",
            vendor = "科大讯飞",
            endpoint = "https://spark-api-open.xf-yun.com/v1/chat/completions",
            models = listOf("generalv3.5", "4.0Ultra"),
            note = "星火开放平台的 APIPassword 那一串。",
        ),
        LlmVendor(
            id = "qianfan",
            name = "百度文心",
            vendor = "百度智能云",
            endpoint = "https://qianfan.baidubce.com/v2/chat/completions",
            models = listOf("ernie-4.0-8k", "ernie-speed-8k"),
            note = "千帆平台的 v2 接口，key 形如 bce-v3/…。",
        ),
    )

    fun find(id: String): LlmVendor? = all.firstOrNull { it.id == id }

    /**
     * 这一家默认能干哪几样。
     *
     * ★ 默认**只声明文字**：`can` 里多声明一样，路由就会把那种活派过来，
     *   而模型其实干不了的时候它不会说「我看不到」，它会**编一张图**出来。
     *   所以「能看图」只有表里明确标了的那一家才预填，用户也可以自己改。
     */
    fun defaultCan(v: LlmVendor): Set<String> =
        if (v.vision) setOf(RouteMath.TEXT, RouteMath.VISION) else setOf(RouteMath.TEXT)
}
