package com.example.touchpad

/**
 * 规则快通道:闭集指令**不叫模型**,直接调工具。
 *
 * 为什么值得单开一层:每叫一次「脑」,这台机器就要满频跑好几秒(冷预填二十几秒),
 * 换来的是温度、电量和等待。而「下一首」「打开微信」这种话**根本没有歧义** ——
 * 用 4B 模型去理解它是纯浪费。同样的结果,规则做是零算力、零等待、零发热。
 *
 * 代价是「规则可能切错」。所以整个设计就围绕一件事:**宁可漏,不可错。**
 * 三道闸门,任何一道不过就 `null` 交回模型 —— 那一刻的行为和没有快通道时**完全一样**:
 *
 *   1. **话要短**(≤ [MAX_LEN] 字)。长句多半是复合指令,规则切不准。
 *   2. **名字要干净**。从「打开…」后面抠出来的名字里若带着 `然后/再/给/写` 这类词,
 *      说明这句话还有下半截(「打开微信**给**张三发消息」),规则切早了 → 作废。
 *   3. **不许太泛**。「打开文件」到底开哪个文件?猜不出来 → 作废。
 *
 * 再加一层兜底:AiAgent 拿到快通道的结果后**先真的去调**,调失败(比如应用名不对)
 * 就丢掉快通道、照常交给模型重来一遍。所以最坏情况是「这次没省下电」,
 * 而不是「把事做错了」。
 *
 * ⚠️ **这条兜底对 `click_ui` 不成立,得额外加一层。** `open_app` 失败就是失败,
 * 便宜;而 `click_ui` 失败会走 `AiAgent.resolveMiss` —— 滚用户的窗口、往那个窗口的
 * 搜索框里打字、叫云端老师、还可能要花 215 秒看图,然后才交回模型重来一遍。
 * 规则要是切错了(界面上压根没那个控件),这一串会**先动用户的界面**,比没有快通道还差。
 *
 * 所以快通道发出的 `click_ui` 一律带 `strict`,dispatch 见到就直接把失败原样交回来
 * (见 AiAgent.dispatch)。规则层本来就是"宁可漏不可错",它出错的代价必须小。
 *
 * 改这里的规则之后,请跑 `./gradlew :app:testDebugUnitTest` —— FastPathTest 会拿
 * eval/cases.txt 逐条过一遍,既看**覆盖了几条**,也看**有没有切错**。
 */
object FastPath {

    /** 快通道给出的结论。只剩工具一种 —— 闲聊归模型(见 CHITCHAT 位置的那段说明)。 */
    sealed interface Hit {
        /** 直接去调这个工具,不用问模型。 */
        data class Tool(val tool: String, val args: Map<String, String>, val say: String) : Hit
    }

    /** 超过这么多字就不猜了。见类注释闸门 1。 */
    private const val MAX_LEN = 16

    /** 抠出来的名字最长几个字。`文件资源管理器` 是 7 个,留点余量。 */
    private const val MAX_NAME = 12

    /**
     * 控件名后面这些词是**量词**,不是名字的一部分 —— 用户说「发送按钮」,指的是那个
     * 叫「发送」的控件。剥掉再去找,命中率高得多(不剥就是拿「发送按钮」去 find,
     * 而那个控件永远不叫这个名字)。
     */
    private val WIDGET_SUFFIXES =
        listOf("按钮", "菜单", "图标", "选项", "输入框", "搜索框", "框")

    /**
     * 位置词 / 指代词。用户说「右上角那个关闭」,真正要点的只有「关闭」——
     * 界面上没有一个控件叫「右上角那个关闭」,带着它们去找必然落空。
     * **长的排前面**,否则「这个」会被「这」先咬掉一半。
     */
    private val LOCATORS = listOf(
        "右上角", "左上角", "右下角", "左下角",
        "右上", "左上", "右下", "左下", "上面", "下面", "左边", "右边", "中间",
        "那个", "这个", "那", "这",
    )

    /**
     * 这些词一出现,说明抠出来的是**键盘上的键**而不是界面上的字(「按 ctrl+f 查找」
     * 「按回车」)。那类该走 hotkey。认不出来宁可交回模型,也绝不当成控件名去点。
     */
    private val KEY_WORDS = listOf(
        "ctrl", "alt", "shift", "cmd", "esc", "tab",
        "回车", "空格", "space", "enter", "delete", "backspace", "方向键", "键",
    )

    /**
     * 光是个方位词就说明**这句话的名字不在尾巴上**(见 [cleanName] 里那条)。
     * 抠出这些当名字,等于去找一个叫「前面」的窗口 —— 找不到,然后白跑一轮。
     */
    private val POSITION_WORDS = setOf(
        "前面", "最前面", "前边", "后面", "最后面", "上面", "下面", "前台", "桌面",
    )

    /** 常见的顶级域名。判「这是不是个网址」时用,见 [looksLikeUrl]。 */
    private val TLDS = setOf(
        "com", "cn", "net", "org", "io", "dev", "app", "me", "co", "edu", "gov",
    )

    /**
     * 长得像网址/主机名吗。
     *
     * **只认有把握的形态**,拿不准一律 false(拿不准就当成普通名字,然后被别的闸门
     * 处理掉,或者干脆交给模型):带协议的、`www.` 开头的,或者「点分标签」而且
     * 末段是个眼熟的顶级域名。
     *
     * 头一条那一长串字符白名单是**故意**的:它把中文名字直接排除在外 ——
     * 「哔哩哔哩」里一个点都没有,本来也拆不出标签,但先挡一道省得以后改错。
     */
    private fun looksLikeUrl(n: String): Boolean {
        val low = n.lowercase()
        if (low.startsWith("http://") || low.startsWith("https://") ||
            low.startsWith("www.")) return true
        if (!low.all { it.isLetterOrDigit() || it in ".-_/:?=&%" }) return false
        val labels = low.substringBefore('/').split('.')
        return labels.size >= 2 && labels.last() in TLDS && labels.dropLast(1).all { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------
    // 一、整句精确匹配的那几类
    // ------------------------------------------------------------------

    /**
     * 媒体控制。**整句**对表,所以「静音」不会误吃到「取消静音」——
     * 前缀关系在这里无所谓,查的是全等。
     */
    private val MEDIA: Map<String, Pair<String, String>> = mapOf(
        // 切歌
        "下一首" to ("next" to "换了下一首"),
        "下首" to ("next" to "换了下一首"),
        "下一曲" to ("next" to "换了下一首"),
        "下一首歌" to ("next" to "换了下一首"),
        "切歌" to ("next" to "换了下一首"),
        "换歌" to ("next" to "换了下一首"),
        "换一首" to ("next" to "换了下一首"),
        "再来一首" to ("next" to "换了下一首"),
        "上一首" to ("prev" to "回到上一首"),
        "上首" to ("prev" to "回到上一首"),
        "上一曲" to ("prev" to "回到上一首"),
        "前一首" to ("prev" to "回到上一首"),
        "回上一首" to ("prev" to "回到上一首"),

        // 播放 / 暂停
        "暂停" to ("play_pause" to "好"),
        "暂停一下" to ("play_pause" to "好"),
        "暂停播放" to ("play_pause" to "好"),
        "继续" to ("play_pause" to "好"),
        "继续播放" to ("play_pause" to "好"),
        "播放" to ("play_pause" to "好"),
        "播放音乐" to ("play_pause" to "好"),
        "开始播放" to ("play_pause" to "好"),
        "恢复播放" to ("play_pause" to "好"),
        "放歌" to ("play_pause" to "好"),
        "放音乐" to ("play_pause" to "好"),
        "停一下" to ("play_pause" to "好"),
        "接着放" to ("play_pause" to "好"),

        // 停止
        "停止播放" to ("stop" to "停了"),
        "停止音乐" to ("stop" to "停了"),
        "别放音乐了" to ("stop" to "停了"),
        "别放了" to ("stop" to "停了"),
        "别唱了" to ("stop" to "停了"),
        "关掉音乐" to ("stop" to "停了"),
        "关闭音乐" to ("stop" to "停了"),
        "关音乐" to ("stop" to "停了"),
        "不听了" to ("stop" to "停了"),

        // 静音
        "静音" to ("mute" to "好"),
        "取消静音" to ("mute" to "好"),
        "解除静音" to ("mute" to "好"),
        "开声音" to ("mute" to "好"),
        "打开声音" to ("mute" to "好"),
        "恢复声音" to ("mute" to "好"),

        // 音量
        "声音大一点" to ("volume_up" to "调大了"),
        "声音大点" to ("volume_up" to "调大了"),
        "大声一点" to ("volume_up" to "调大了"),
        "大声点" to ("volume_up" to "调大了"),
        "调大音量" to ("volume_up" to "调大了"),
        "音量调大" to ("volume_up" to "调大了"),
        "音量大一点" to ("volume_up" to "调大了"),
        "调高音量" to ("volume_up" to "调大了"),
        "声音调大" to ("volume_up" to "调大了"),
        "声音小一点" to ("volume_down" to "调小了"),
        "声音小点" to ("volume_down" to "调小了"),
        "小声一点" to ("volume_down" to "调小了"),
        "小声点" to ("volume_down" to "调小了"),
        "调小音量" to ("volume_down" to "调小了"),
        "音量调小" to ("volume_down" to "调小了"),
        "音量小一点" to ("volume_down" to "调小了"),
        "调低音量" to ("volume_down" to "调小了"),
        "声音调小" to ("volume_down" to "调小了"),
    )

    /**
     * 问电脑现状 —— 直接 get_state,不用模型先理解一遍再调。
     *
     * ⚠️ 以前这里还有一张 CHITCHAT 闲聊表(「你好→你好!要我做点什么?」),
     * 2026-10-03 随人格融合删掉了:ConMarn 是一个**人**,「你好」回一句写死的
     * 客服台词,人格当场穿帮。闲聊现在照常交给模型 —— 前缀预热之后一轮
     * 0.5~2.5 秒(实测),「烧电」的理由已经不成立了,而人味儿是卖点。
     */
    private val STATE: Set<String> = setOf(
        "现在开着什么", "现在开了什么", "电脑上开着什么", "电脑上有什么",
        "现在有什么窗口", "有哪些窗口", "什么窗口", "开了哪些窗口", "开了什么窗口",
        "电脑上现在有哪些窗口", "电脑上现在开了哪些窗口",
        "我现在在哪个窗口", "我在哪个窗口", "当前窗口是什么", "现在什么窗口",
        "看看电脑现在什么样", "电脑现在什么样", "看看电脑", "看看现在什么样",
    )

    // ------------------------------------------------------------------
    // 二、要抠名字的那两类
    // ------------------------------------------------------------------

    /**
     * 「把 X 打开」/「把 X 窗口调到最前面」这种把字句 —— 名字在触发词的**前面**,
     * 所以不能像下面那样「取触发词后面的尾巴」,得单独一条正则。
     */
    private val BA_OPEN_RE = Regex("^把(.+?)(?:窗口|页面|网页)?(?:打开|开一下|打开一下|启动|运行)$")
    private val BA_FOCUS_RE = Regex("^把(.+?)(?:窗口|页面|网页)?(?:调到最前面|调到前面|放最前面|弄到最前面|切到)$")

    /**
     * 「点… / 按…」开头的点击指令。**必须锚在句首** —— 不锚的话「早点睡」会被切出一个
     * 叫「睡」的控件名来,而句中的「点」根本说明不了什么。
     * 分支内长的排前面,否则「点一下发送」会被「点」先咬掉,剩下「一下发送」当名字。
     */
    private val CLICK_RE = Regex("^(?:点一下|点击|点下|点|按一下|按下|按)(.+)$")

    /** 触发词。取**最后**一个出现位置之后的尾巴当名字。 */
    private val OPEN_TRIGGERS =
        listOf("打开一下", "打开", "开启", "启动", "运行", "开一下", "开个")
    private val FOCUS_TRIGGERS =
        listOf("切换到", "切到", "切回", "切过去", "显示一下", "显示", "回到")

    /**
     * 抠出来的「名字」能不能用。这道闸门是整套里最要紧的 —— 见类注释闸门 2、3。
     */
    private fun cleanName(raw: String): String? {
        var n = raw.trim()
        // 「终端窗口」→「终端」;「计算器一下」→「计算器」
        for (suffix in listOf("窗口", "页面", "网页", "一下", "程序", "应用"))
            if (n.endsWith(suffix) && n.length > suffix.length) { n = n.removeSuffix(suffix); break }

        if (n.length !in 1..MAX_NAME) return null

        // 网址不是应用名,也不是窗口名。
        //
        // 见 match() 里那段「手机上的事」:抠出「www.bing.com」交给 open_app,是
        // 拿一网址去电脑的应用表里找。这条**本身**还算便宜(找不到就失败,失败就
        // 照常交回模型),但真正该做的是把网址交给手机上那只手用浏览器打开 ——
        // 规则层不认识那条路,所以它不该在这条路上开口。
        if (looksLikeUrl(n)) return null

        // 光是个方位词,不是名字。
        //
        // 「那个记事本窗口**切到前面**」抠出来的是「前面」—— 它的语序恰恰说明
        // **名字在触发词的前面**(「切到」是结尾不是开头)。要抠对得再加一条正则,
        // 而那是一条新的出错路。规则层不做:交给模型。
        if (n in POSITION_WORDS) return null

        // 闸门 2:名字里还带着下一个动作 → 这是复合句,规则切早了
        for (bad in listOf("然后", "接着", "之后", "并且", "顺便", "再", "给", "说", "写", "发"))
            if (n.contains(bad)) return null

        // 闸门 3:太泛,猜不出具体是哪个
        for (vague in listOf("那个", "这个", "刚才", "之前", "它", "东西"))
            if (n.contains(vague)) return null
        if (n in listOf("文件", "文件夹", "图片", "视频", "音乐", "东西", "一下")) return null

        return n
    }

    /**
     * 点击那条路上的名字清洗。**之所以要和 [cleanName] 分开**:闸门 2 把
     * 「给 / 说 / 写 / **发**」当成「这句话还有下半截」的信号 —— 对
     * `打开微信给张三发消息` 是对的,对**控件名**却错得离谱:界面上真有一个按钮叫
     * 「发送」,而它会被那个「发」字一刀砍掉,而这恰恰是最常点的一个键。
     *
     * 所以点击这条路只拦**句子连接词**(然后 / 再 / …),控件名里本来就不会有它们。
     */
    private fun cleanClickName(raw: String): String? {
        var n = stripLocators(raw.trim())
        for (suffix in WIDGET_SUFFIXES)
            if (n.endsWith(suffix) && n.length > suffix.length) {
                n = n.removeSuffix(suffix); break
            }

        if (n.length !in 1..MAX_NAME) return null
        if (n in LOCATORS) return null                       // 剥完只剩个「上面」
        if (n in listOf("按钮", "菜单", "图标", "框", "选项")) return null

        // 还有下半截 → 规则切早了(「点一下发送然后关掉」)
        for (bad in listOf("然后", "接着", "之后", "并且", "顺便", "再"))
            if (n.contains(bad)) return null
        for (vague in listOf("那个", "这个", "刚才", "之前", "它", "东西"))
            if (n.contains(vague)) return null
        for (k in KEY_WORDS) if (n.contains(k, ignoreCase = true)) return null

        return n
    }

    /** 反复剥掉开头的位置词:「右上角那个关闭」→「关闭」。 */
    private fun stripLocators(raw: String): String {
        var n = raw
        var changed = true
        while (changed) {
            changed = false
            for (p in LOCATORS) {
                // `>` 不是 `>=`:「上面」整句就剩个位置词时不许剥成空串,
                // 那会静默变成一个谁也点不到的名字;留着让上面那条 `n in LOCATORS` 拒掉。
                if (n.length > p.length && n.startsWith(p)) {
                    n = n.removePrefix(p); changed = true; break
                }
            }
        }
        return n
    }

    /** 触发词之后的尾巴 → 名字。索引取**最后**一个,这样「我想看视频,打开哔哩哔哩」也对。 */
    private fun tailAfter(s: String, triggers: List<String>): String? {
        // 长的触发词优先:「打开一下」不能被「打开」先吃掉(那会剩个「一下」当名字)
        for (t in triggers) {
            val i = s.lastIndexOf(t)
            if (i >= 0) return s.substring(i + t.length)
        }
        return null
    }

    // ------------------------------------------------------------------
    // 三、归一化
    // ------------------------------------------------------------------

    /**
     * 去掉空白和句末语气,让「下一首。」「下一首~」「下一首吧」都落到同一个键上。
     * 只削句末 —— 削中间会把「别放音乐了」这种正常的话削坏。
     */
    private fun normalize(s: String): String =
        s.replace(Regex("\\s+"), "")
            .trimEnd('。', ',', '!', '!', '?', '?', '~', '~', '吧', '啊', '呀', '呗', '嘛')

    /** 「帮我打开微信」→「打开微信」。剥一层就够,剥多了「请」会把「请柬」削坏。 */
    private fun stripPolite(s: String): String {
        for (p in listOf("帮我", "给我", "麻烦", "请")) if (s.startsWith(p)) return s.removePrefix(p)
        return s
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /**
     * 看一眼这句话能不能不叫模型。`null` = 不能,照常走模型(行为和以前一模一样)。
     *
     * 顺序有讲究:先整句精确的(不可能误伤),再把字句,最后才是取尾巴的。
     */
    fun match(utterance: String): Hit? {
        val s = stripPolite(normalize(utterance))
        if (s.isEmpty() || s.length > MAX_LEN) return null

        MEDIA[s]?.let { (action, say) -> return Hit.Tool("media", mapOf("action" to action), say) }
        if (s in STATE) return Hit.Tool("get_state", emptyMap(), "看好了")

        // ★ 「手机上的事」一律不开口(2026-10-04,通用入口进来之后)。
        //
        // 规则层只认识**电脑**那一只手,而这句话里有两个它会踩空的坑:
        //   · 「手机上打开相机」→ 抠出 open_app(name=相机),而 Windows **真有一个**
        //     叫「相机」的应用 —— 于是它**成功地**开了电脑上的相机。不失败,也就不会
        //     走「失败就交回模型」那条兜底。用户看到的是她在另一台机器上忙活。
        //   · 「打开 www.bing.com」→ 抠出 open_app(name=www.bing.com)(下面那条
        //     网址检查也会拦住它,这里是第二道)。
        //
        // 两者都够得上「把事做错了」,而这个方向正是整个快通道最不能出的错。
        // 手机上的事现在走 list_hands → use_hand 那条阶梯 —— **该不该走、怎么走,
        // 需要知道用户指的是哪台机器**,而这句话里没有这个信息。规则层一旦开口
        // 就必须是对的,不知道就别开口。
        //
        // ★ 放在整句精确匹配(MEDIA/STATE)**之后**:那两张表是全等查表,不可能误伤;
        // 有风险的是下面那些「抠名字」的路子。
        if (s.contains("手机")) return null

        // 把字句:名字在触发词前面,必须先于「取尾巴」处理
        BA_OPEN_RE.matchEntire(s)?.groupValues?.get(1)?.let { m ->
            cleanName(m)?.let { return Hit.Tool("open_app", mapOf("name" to it), "打开 $it") }
        }
        BA_FOCUS_RE.matchEntire(s)?.groupValues?.get(1)?.let { m ->
            cleanName(m)?.let { return Hit.Tool("focus_window", mapOf("title" to it), "切到 $it") }
        }

        // 切窗口要先于开应用:「切到 Chrome」里没有「打开」,但「显示一下终端窗口」里
        // 也没有 —— 两个触发词集合不重叠,顺序其实无所谓,按语义从窄到宽排着好看。
        tailAfter(s, FOCUS_TRIGGERS)?.let { t ->
            cleanName(t)?.let { return Hit.Tool("focus_window", mapOf("title" to it), "切到 $it") }
        }
        tailAfter(s, OPEN_TRIGGERS)?.let { t ->
            cleanName(t)?.let { return Hit.Tool("open_app", mapOf("name" to it), "打开 $it") }
        }

        // 点击。放最后:前面几条(打开 / 切到)更具体,先让它们挑。
        // **带 strict** —— 见类注释:快通道的点击不许触发那串"滚窗口/搜/问老师/看图"。
        // 电脑端会无视这个多余参数,它只是给 dispatch 看的。
        CLICK_RE.matchEntire(s)?.groupValues?.get(1)?.let { raw ->
            cleanClickName(raw)?.let {
                return Hit.Tool(
                    "click_ui", mapOf("name" to it, "strict" to "true"), "点 $it")
            }
        }

        return null
    }
}
