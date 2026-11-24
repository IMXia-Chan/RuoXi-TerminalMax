package com.example.touchpad

/**
 * 「她是什么样的人」和「她的工作手册」之间那道分界 —— 纯字符串运算,
 * **零 Android / org.json 依赖**,好让 `PersonaMathTest` 在纯 JVM 上钉住。
 *
 * ## 为什么要有这一层
 *
 * 2026-10-06 用户:「能不能帮我做一个,就是我得有她性格调试的地方啊对吧」。
 * 他能改的**只有自我介绍那几行**(人设);工作手册一个字都不给 ——
 * ★ 那不是「藏起来了」,是**结构上够不着**:手册留在源码里,运行时才拼上去。
 *
 * ## 为什么源码一个字都不动
 *
 * `eval/ruoxi_prompt.py` 从源码里抠**真提示词**(找 `SYSTEM_PROMPT` 这个常量,
 * 取它后面那对三引号之间的全部内容),
 * `check_tool_drift.py` 和 `run_eval.py` 全靠它。**把源码改成拼接 = 那两个脚本当场罢工。**
 *
 * ★★ **别在任何注释里把那条正则的字面形状原样抄一遍** —— 它匹配的是
 *   「`SYSTEM_PROMPT` + 等号 + 三引号」这个形状,而且**非贪婪**,
 *   所以它会先匹配到注释里的那一次、抠出 5 个字符,而**评测照跑不误、只是成绩全是假的**。
 *   (2026-10-07 在 `AiAgent.kt` 上真踩了一次;两处注释都是那次之后改掉的。)
 *
 * 所以源码永远留着**出厂那份**,他改的那份另存;运行时在这里切开、按 [MARK] 拼回去。
 * 三条好处:
 *  ① 评测工具**零改动**;
 *  ② **从没改过人设的人,一次重算都不付**(没有覆盖就直接用出厂原文);
 *  ③ 「还原出厂」天然成立 —— 把存的那份删掉就回到出厂。
 *
 * ## ★★ 认不出来的时候:一律回 null,由调用方退回出厂并记日志
 *
 * **绝不静默拼出半个提示词。** 那种坏法的症状是「她突然不按规矩来」,而提示词看上去还在,
 * 真机上极难查 —— 这个项目栽在静默失败上不止一次了。
 */
internal object PersonaMath {

    /**
     * 出厂人设和工作手册之间那句分界。
     * 全文件唯一一处(`AiAgent.kt` 的 `SYSTEM_PROMPT` 里),已核 `grep -c` = 1。
     */
    internal const val MARK = "规矩(每条都是踩过的坑):"

    /**
     * 单次能接受的人设长度上限(字符)。
     *
     * ★ 有上限不是洁癖:人设段是**被缓存的那段前缀**的一部分,
     * 写长了每一轮都跟着变慢、还挤 4B 那 8192 的上下文。
     * 而**超长几乎总是误操作**(整个文档粘进来)。出厂那段约 430 字,这里放宽到 4 倍多。
     */
    internal const val MAX_PERSONA_CHARS = 2000

    /**
     * 从出厂那份里切出**人设那几行**(分界之前的部分),已 trim。
     *
     * null = 认不出分界。两种情况都算:一处都没有,或**不止一处** ——
     * 后者说明结构变了,切哪儿都有歧义,**猜不如不猜**。
     */
    internal fun splitOut(full: String): String? {
        val i = boundary(full) ?: return null
        return full.substring(0, i).trim()
    }

    /**
     * 把出厂那份里的**人设段**换成他的,拼回一整份提示词。
     *
     * ★ 调用方**永远传出厂那份** `full`,不要传上一次的结果 ——
     * 每次都从出厂重建,「还原出厂」才是一个删除动作,而不是一次逆运算。
     *
     * null = 拼不了,调用方**必须退回出厂那份并大声记日志**。五种情况:
     * 分界认不出 / 他说的人设为空 / 全是空白 / 超长 / 里面又出现了一次分界。
     *
     * ★ 最后那一条(人设里含 [MARK])看着苛刻,但它挡的是一个真坑:
     * 拼出来会有**两处分界**,下次再切就从人设里那处切开了 —— 提示词结构当场碎掉。
     * 挡住只是「我改了它没生效」+ 一行日志;放过去是「她性格突然不对」+ 查不出来。
     */
    internal fun rebuild(full: String, persona: String?): String? {
        val i = boundary(full) ?: return null
        val p = persona?.trim().orEmpty()
        if (p.isEmpty()) return null
        if (p.length > MAX_PERSONA_CHARS) return null
        if (p.contains(MARK)) return null
        return p + "\n\n" + full.substring(i)
    }

    /**
     * 这段自我介绍**能不能存** —— 能就回 null,不能就回**一句人话**(给他看的)。
     *
     * ★★ 判据**只有一份**:这个方法**不重述** [rebuild] 那几条规则,它**先问 [rebuild]**;
     * 只有 `rebuild` 说「拼不出来」之后,才轮到下面这几行**挑措辞**。
     *
     * 为什么非得这么绕:这个项目吃过「同一张表抄两遍」的亏。界面自己写一份判据的话,
     * 两份早晚分家 —— 而分家的症状是**「他按了保存、提示也过去了、人设却没变」**,
     * 人设看上去还在,真机上根本查不出来。
     *
     * ★ **空串算合法**(= 还原出厂):那条路**根本不用拼**,所以「`rebuild` 回 null」
     *   在这里**不等于**「不能用」。这是唯一一处例外,写在这儿免得以后被当成 bug 修掉。
     */
    internal fun problem(full: String, persona: String?): String? {
        val p = persona?.trim().orEmpty()
        if (p.isEmpty()) return null                 // = 还原出厂,不经过 rebuild
        if (rebuild(full, p) != null) return null    // ★ 判定在这儿,不在下面
        // 走到这儿 = rebuild 已经说了不行。现在才轮到我说话。
        if (boundary(full) == null) {
            return "出厂那份里找不到该切的地方 —— 这个改不了,得改代码。"
        }
        if (p.length > MAX_PERSONA_CHARS) {
            return "太长了:现在 ${p.length} 个字,最多能写 $MAX_PERSONA_CHARS 个。"
        }
        if (p.contains(MARK)) {
            return "里面不能出现「$MARK」这几个字。"
        }
        return "这段存不进去 —— 换个写法再试试。"
    }

    /** 分界在 [full] 里的位置;没有、或不止一处 → null。 */
    private fun boundary(full: String): Int? {
        val i = full.indexOf(MARK)
        if (i < 0) return null
        if (full.indexOf(MARK, i + MARK.length) >= 0) return null
        return i
    }
}
