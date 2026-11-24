package com.example.touchpad

/**
 * 房间里右边**下半块**屏：没有日程的时候放文学语录。
 *
 * ## 为什么放本地
 *
 * 这块屏的位置本来是「今天的日程」，而日程通常一条都没有。空着的方块比不挂还难看，
 * 所以要有一个**永远有东西**的填充 —— 语录不用联网、不用 key、不怕接口挂。
 *
 * ## 收的是哪一类
 *
 * 只收**作者去世超过五十年的**（中国著作权是终身加五十年），
 * 所以这里只有古文和民国那一辈：论语、庄子、唐诗宋词、鲁迅、朱自清。
 * ★ 外国文学不收 —— **中译本**的著作权在译者手上，和原作者过没过期是两件事。
 *
 * ## 排序是「按天走」，不是随机
 *
 * [QuotesMath.at] 用「一年中的第几天」算起点，所以：
 * 同一天里翻到的永远是同一批，明天整体换一批，重启 App 不会跳到别处去。
 * 随机数在这里是个坏主意 —— 翻回去看见刚看过的那句，人会以为它卡住了。
 */
internal object Quotes {

    /**
     * 一句 + 出处 + 语言。
     *
     * [lang] 是 `"zh"` / `"en"`：屏上要**中英交替**着翻（见 [QuoteMath.interleave]），
     * 靠它分两堆。内置的这批以中文为主，另配几句英文兜底 —— 断网时两种都还有。
     */
    internal data class Quote(val text: String, val from: String, val lang: String = "zh")

    val LIST: List<Quote> = listOf(
        Quote("天行健，君子以自强不息。", "《周易·乾》"),
        Quote("千里之行，始于足下。", "《老子》"),
        Quote("上善若水，水善利万物而不争。", "《老子》"),
        Quote("知人者智，自知者明。", "《老子》"),
        Quote("学而时习之，不亦说乎。", "《论语·学而》"),
        Quote("三人行，必有我师焉。", "《论语·述而》"),
        Quote("君子和而不同，小人同而不和。", "《论语·子路》"),
        Quote("不积跬步，无以至千里。", "《荀子·劝学》"),
        Quote("博学之，审问之，慎思之，明辨之，笃行之。", "《礼记·中庸》"),
        Quote("穷则独善其身，达则兼济天下。", "《孟子·尽心上》"),
        Quote("人生天地之间，若白驹之过隙，忽然而已。", "《庄子·知北游》"),
        Quote("路漫漫其修远兮，吾将上下而求索。", "屈原《离骚》"),
        Quote("采菊东篱下，悠然见南山。", "陶渊明《饮酒》"),
        Quote("盛年不重来，一日难再晨。", "陶渊明《杂诗》"),
        Quote("海内存知己，天涯若比邻。", "王勃《送杜少府之任蜀州》"),
        Quote("行到水穷处，坐看云起时。", "王维《终南别业》"),
        Quote("长风破浪会有时，直挂云帆济沧海。", "李白《行路难》"),
        Quote("天生我材必有用，千金散尽还复来。", "李白《将进酒》"),
        Quote("会当凌绝顶，一览众山小。", "杜甫《望岳》"),
        Quote("沉舟侧畔千帆过，病树前头万木春。", "刘禹锡《酬乐天扬州初逢席上见赠》"),
        Quote("业精于勤，荒于嬉；行成于思，毁于随。", "韩愈《进学解》"),
        Quote("不畏浮云遮望眼，自缘身在最高层。", "王安石《登飞来峰》"),
        Quote("竹杖芒鞋轻胜马，谁怕？一蓑烟雨任平生。", "苏轼《定风波》"),
        Quote("回首向来萧瑟处，归去，也无风雨也无晴。", "苏轼《定风波》"),
        Quote("人生如逆旅，我亦是行人。", "苏轼《临江仙》"),
        Quote("纸上得来终觉浅，绝知此事要躬行。", "陆游《冬夜读书示子聿》"),
        Quote("问渠那得清如许？为有源头活水来。", "朱熹《观书有感》"),
        Quote("众里寻他千百度，蓦然回首，那人却在，灯火阑珊处。", "辛弃疾《青玉案》"),
        Quote("人生自古谁无死，留取丹心照汗青。", "文天祥《过零丁洋》"),
        Quote("其实地上本没有路，走的人多了，也便成了路。", "鲁迅《故乡》"),
        Quote("无穷的远方，无数的人们，都和我有关。", "鲁迅《且介亭杂文末编》"),
        Quote("热闹是他们的，我什么也没有。", "朱自清《荷塘月色》"),
        Quote("燕子去了，有再来的时候；杨柳枯了，有再青的时候。", "朱自清《匆匆》"),

        // ── 英文兜底 ────────────────────────────────────────────────
        // ★ 只收**作者去世超过五十年**的（英文原文的著作权在作者手上）。
        //   在线那一路（[QuoteFeed]）会给得更多，这里是断网时的底。
        Quote("To be, or not to be, that is the question.", "William Shakespeare", "en"),
        Quote("All the world's a stage, and all the men and women merely players.", "William Shakespeare", "en"),
        Quote("Nothing will come of nothing.", "William Shakespeare", "en"),
        Quote("Knowledge is power.", "Francis Bacon", "en"),
        Quote("Reading maketh a full man; conference a ready man; and writing an exact man.", "Francis Bacon", "en"),
        Quote("No man is an island, entire of itself.", "John Donne", "en"),
        Quote("I think, therefore I am.", "René Descartes", "en"),
        Quote("Man is born free, and everywhere he is in chains.", "Jean-Jacques Rousseau", "en"),
        Quote("Nothing great was ever achieved without enthusiasm.", "Ralph Waldo Emerson", "en"),
        Quote("Trust thyself: every heart vibrates to that iron string.", "Ralph Waldo Emerson", "en"),
        Quote("The mass of men lead lives of quiet desperation.", "Henry David Thoreau", "en"),
        Quote("Go confidently in the direction of your dreams.", "Henry David Thoreau", "en"),
        Quote("The only thing we have to fear is fear itself.", "Franklin D. Roosevelt", "en"),
        Quote("We hold these truths to be self-evident, that all men are created equal.", "The Declaration of Independence", "en"),
    )

    fun size(): Int = LIST.size
}

/** [Quotes] 的纯算术：按天定起点、按次往后走。能单测。 */
internal object QuotesMath {

    /**
     * 第 [step] 次翻到哪一句。
     *
     * @param dayOfYear 一年中的第几天（1 起）。**同一天同一步永远是同一句。**
     * @return 下标，保证落在 `0 until size` 里（size 为 0 时给 0）。
     */
    fun at(dayOfYear: Int, step: Int, size: Int): Int {
        if (size <= 0) return 0
        val start = dayOfYear * 7
        var i = (start + step) % size
        if (i < 0) i += size
        return i
    }
}
