package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 [ApiMath]。
 *
 * **这一层错了不崩** —— 它错的表现是「她查到了天气却什么也没说」
 * 「她报了一个别的地方的天气」「你的 key 出现在日志里」。
 * 三种都不报错、都不崩,所以只能靠这里钉死。
 *
 * 按项目惯例:纯 JVM、JUnit4、中文反引号命名、`// ---- 分组 ----`。
 */
class ApiMathTest {

    private fun profile(
        id: String = "p1",
        label: String = "高德",
        baseUrl: String = "https://restapi.amap.com",
        key: String = "",
        enabled: Boolean = true,
    ) = ApiProfile(id, label, baseUrl, key, enabled = enabled)

    private fun purpose(
        id: String = "weather",
        profiles: List<ApiProfile> = listOf(profile()),
        activeId: String = profiles.firstOrNull()?.id ?: "",
        usage: ApiUsage = ApiUsage(),
    ) = ApiPurpose(id, "天气", "", profiles, activeId, usage)

    // ---- 占位符:没填就不发(这个文件里最要紧的一条) ----

    @Test
    fun `占位符没填就整个不发,不许把 {city} 原样发出去`() {
        // ★ 这条不是洁癖。留一个没填的占位符发出去,对方**可能当成真值收下**
        //   —— 我们就会拿到一份关于城市「{city}」的天气,然后当成上海的报给用户。
        //   没有任何报错。
        assertNull(ApiMath.fillTemplate("https://x.com/w?city={city}", emptyMap()))
    }

    @Test
    fun `参数是空串也算没填`() {
        // `city=` 和 `city={city}` 一样是垃圾输入 —— 前者对方多半返回「查不到」,
        // 后者更糟。两种都不该发。
        assertNull(ApiMath.fillTemplate("https://x.com/w?city={city}", mapOf("city" to "")))
        assertNull(ApiMath.fillTemplate("https://x.com/w?city={city}", mapOf("city" to "   ")))
    }

    @Test
    fun `全填上了就填进去`() {
        val got = ApiMath.fillTemplate(
            "https://x.com/route?from={from}&to={to}",
            mapOf("from" to "A", "to" to "B"),
        )
        assertEquals("https://x.com/route?from=A&to=B", got)
    }

    @Test
    fun `没有占位符的模板原样返回`() {
        assertEquals("https://x.com/ping", ApiMath.fillTemplate("https://x.com/ping", emptyMap()))
    }

    @Test
    fun `认得出模板里有哪些占位符`() {
        assertEquals(
            setOf("from", "to"),
            ApiMath.placeholders("https://x.com/r?from={from}&to={to}"),
        )
    }

    // ---- 中文地名必须转义 ----

    @Test
    fun `中文地名要转成百分号编码`() {
        // ★ 不转义的话 `上海` 以原始 UTF-8 字节进 URL:有的服务端收得下、
        //   有的收成乱码,而**失败的那边不报错**,只是返回一个查不到的城市。
        assertEquals("%E4%B8%8A%E6%B5%B7", ApiMath.urlEncode("上海"))
    }

    @Test
    fun `ASCII 里只有保留字符才转义`() {
        assertEquals("aZ09-_.~", ApiMath.urlEncode("aZ09-_.~"))
        assertEquals("%20", ApiMath.urlEncode(" "))
        assertEquals("%26", ApiMath.urlEncode("&"))
    }

    @Test
    fun `中文地址填进模板是转义过的`() {
        val got = ApiMath.fillTemplate("https://x.com/w?city={city}", mapOf("city" to "上海"))
        assertEquals("https://x.com/w?city=%E4%B8%8A%E6%B5%B7", got)
    }

    // ---- key 绝不能进日志 ----

    @Test
    fun `key 出现在 URL 里时能被抹掉`() {
        // ★★ 这条是为高德那类接口写的:**它们把 key 放在 query 参数里**,
        //    所以 URL 本身就含 key。而「出错时把请求 URL 记下来」是调试时
        //    最自然的动作 —— 那一刻 key 就进日志了,而且没有任何报错。
        val k = "0f3a9c1e5b7d2a8f4c6e0b9d1a3f5c7e"
        val url = "https://restapi.amap.com/v3/weather/weatherInfo?key=$k&city=360100"
        val safe = ApiMath.redact(url, listOf(k))
        assertFalse("key 还在里面", safe.contains(k))
        assertTrue(safe.contains("***"))
        assertTrue("URL 的其余部分要留着,不然日志没法看", safe.contains("city=360100"))
    }

    @Test
    fun `多个 key 一起抹,长的优先`() {
        val long = "0123456789abcdef"
        val short = "0123456789"
        val safe = ApiMath.redact("x=$long y=$short", listOf(short, long))
        assertFalse(safe.contains(long))
        assertFalse(safe.contains(short))
    }

    @Test
    fun `面板上永远看不到完整的 key`() {
        val k = "sk-1234567890abcdefghij"
        val m = ApiMath.maskKey(k)
        assertFalse("完整 key 漏出去了", m.contains(k))
        assertTrue(m.startsWith("sk-1"))
    }

    @Test
    fun `短 key 连头尾都不给`() {
        // 6 位的 key 露 4 位等于露了。
        val m = ApiMath.maskKey("abc123")
        assertFalse(m.contains("abc"))
        assertFalse(m.contains("123"))
    }

    @Test
    fun `不需要 key 的档如实说不需要`() {
        assertEquals("(不需要 key)", ApiMath.maskKey(""))
        assertTrue(profile(key = "").keyless)
        assertFalse(profile(key = "x").keyless)
    }

    // ---- 红线:只发文本 ----

    @Test
    fun `sends 只认 text,别的声明一律不发`() {
        assertTrue(ApiMath.sendsOk(listOf("text")))
        assertTrue(ApiMath.sendsOk(emptyList()))
        assertFalse("截图永不上云", ApiMath.sendsOk(listOf("text", "image")))
        assertFalse(ApiMath.sendsOk(listOf("screenshot")))
    }

    @Test
    fun `载荷正文里混进图片字样也要拦`() {
        // ★ 为什么白名单之外还要查正文:声明是我们写的,但**拼进去的内容不是**
        //   —— 用户说「看看这个截图」,那张图的 base64 会出现在**参数值**里,
        //   而参数名可能老老实实叫 `q`。声明拦不住它,只有查正文拦得住。
        assertNotNull(ApiMath.payloadDirtyReason("data:image/png;base64,iVBORw0KGgo="))
        assertNotNull(ApiMath.payloadDirtyReason("{\"q\":\"这行是 BASE64 编码\"}"))
        assertNotNull(ApiMath.payloadDirtyReason("screenshot of the desktop"))
        assertNull(ApiMath.payloadDirtyReason("{\"city\":\"上海\",\"days\":\"3\"}"))
    }

    // ---- 档位的选择 ----

    @Test
    fun `禁用一档不许悄悄换另一档`() {
        // ★ 用户点「禁用」的意思是「这个用途先别用」,不是「换个 key 继续」。
        //   静默换档 = 他以为关了,实际在花另一个 key 的钱。
        val a = profile(id = "a", label = "A", enabled = false)
        val b = profile(id = "b", label = "B", enabled = true)
        val p = purpose(profiles = listOf(a, b), activeId = "a")
        assertNull("生效档被禁用了就该是空的,不能退回 B", ApiMath.active(p))
        assertNotNull(ApiMath.checkPurpose(p))
    }

    @Test
    fun `生效档没被禁用就正常返回`() {
        val p = purpose(profiles = listOf(profile(id = "a", key = "k")), activeId = "a")
        assertEquals("a", ApiMath.active(p)?.id)
        assertNull(ApiMath.checkPurpose(p))
    }

    @Test
    fun `一档都没有时说清楚是没加过,不是坏了`() {
        val p = purpose(profiles = emptyList(), activeId = "")
        assertEquals("还没加过配置档。", ApiMath.checkPurpose(p))
    }

    @Test
    fun `地址没写协议头要说人话`() {
        val bad = ApiMath.checkProfile(profile(baseUrl = "restapi.amap.com"))
        assertNotNull(bad)
        assertTrue("要说人话,别只说校验失败:$bad", bad!!.contains("https://"))
    }

    @Test
    fun `空地址和正常地址`() {
        assertNotNull(ApiMath.checkProfile(profile(baseUrl = "")))
        assertNull(ApiMath.checkProfile(profile(baseUrl = "https://x.com")))
        assertNull(ApiMath.checkProfile(profile(baseUrl = "http://127.0.0.1:8080")))
    }

    // ---- 响应取数路径 ----

    @Test
    fun `点号路径切得对`() {
        assertEquals(
            listOf(ApiMath.Seg.Key("a"), ApiMath.Seg.Key("b")),
            ApiMath.pathSegments("a.b"),
        )
    }

    @Test
    fun `数组下标切得对`() {
        assertEquals(
            listOf(ApiMath.Seg.Key("current"), ApiMath.Seg.Idx(0), ApiMath.Seg.Key("temp")),
            ApiMath.pathSegments("current[0].temp"),
        )
    }

    @Test
    fun `连着两个下标`() {
        assertEquals(
            listOf(ApiMath.Seg.Key("a"), ApiMath.Seg.Idx(1), ApiMath.Seg.Idx(2)),
            ApiMath.pathSegments("a[1][2]"),
        )
    }

    @Test
    fun `坏下标整体放弃,不许当成 0 静默取第一个`() {
        // ★ 「路径写错了」和「取第一个」在结果上完全不同 —— 后者会安静地
        //   报出别的城市/别的日期的数据,看起来完全正常。
        assertEquals(emptyList<ApiMath.Seg>(), ApiMath.pathSegments("a[x].b"))
        assertEquals(emptyList<ApiMath.Seg>(), ApiMath.pathSegments("a[].b"))
    }

    @Test
    fun `空路径切成空列表`() {
        assertEquals(emptyList<ApiMath.Seg>(), ApiMath.pathSegments(""))
        assertEquals(emptyList<ApiMath.Seg>(), ApiMath.pathSegments("  "))
    }

    // ---- 拼 URL(全项目唯一拼 URL 的地方) ----

    @Test
    fun `斜杠的四种组合都只拼出一个斜杠`() {
        // ★ 拼漏了就是 `https://x.comv3/...` —— 那是个**看起来像域名**的东西,
        //   DNS 直接失败,而错误信息指向一个根本不存在的域名,极难看出是自己拼错的。
        val want = "https://x.com/v3/w"
        assertEquals(want, ApiMath.buildUrl("https://x.com", "/v3/w"))
        assertEquals(want, ApiMath.buildUrl("https://x.com/", "/v3/w"))
        assertEquals(want, ApiMath.buildUrl("https://x.com", "v3/w"))
        assertEquals(want, ApiMath.buildUrl("https://x.com/", "v3/w"))
        assertEquals(want, ApiMath.buildUrl("https://x.com/  ", "  /v3/w"))
    }

    @Test
    fun `写死的 query 参数会带上,模型改不了`() {
        val u = ApiMath.buildUrl(
            "https://wttr.in", "/{city}",
            fixedQuery = mapOf("format" to "j1"),
            args = mapOf("city" to "Shanghai"),
        )
        assertEquals("https://wttr.in/Shanghai?format=j1", u)
    }

    @Test
    fun `query 里空值的跳过 —— 那是可选参数没给,不是错`() {
        val u = ApiMath.buildUrl(
            "https://x.com", "/w",
            queryNames = listOf("a", "b"),
            args = mapOf("a" to "1", "b" to ""),
        )
        assertEquals("https://x.com/w?a=1", u)
    }

    @Test
    fun `路径里的占位符没填就整个不发`() {
        assertNull(ApiMath.buildUrl("https://x.com", "/{city}/now", args = emptyMap()))
    }

    @Test
    fun `path 自己带问号时接 & 而不是再来一个问号`() {
        // ★ 两个 `?` 的 URL 有的服务端收得下、有的把后面整段丢掉 ——
        //   后者不报错,只是「参数丢了」,表现成结果不对。
        val u = ApiMath.buildUrl(
            "https://x.com", "/w?days=3",
            queryNames = listOf("city"), args = mapOf("city" to "A"),
        )
        assertEquals("https://x.com/w?days=3&city=A", u)
    }

    @Test
    fun `中文地名在 query 里也是转义的`() {
        val u = ApiMath.buildUrl(
            "https://x.com", "/route",
            queryNames = listOf("from"), args = mapOf("from" to "上海站"),
        )
        assertEquals("https://x.com/route?from=%E4%B8%8A%E6%B5%B7%E7%AB%99", u)
    }

    @Test
    fun `写死的 query 里的占位符只转义一次`() {
        // ★ 转两次 = `%2520`。对面按字面量去搜「android%20bluetooth」，
        //   **回 200 + 空数组**，没有报错 —— 只表现成「这个工具查不到东西」。
        val u = ApiMath.buildUrl(
            "https://api.github.com", "/search/repositories",
            fixedQuery = mapOf("q" to "{q}"),
            args = mapOf("q" to "android bluetooth"),
        )
        assertEquals("https://api.github.com/search/repositories?q=android%20bluetooth", u)
    }

    @Test
    fun `写死的 query 里，值中带的 & 和 = 仍然被转义`() {
        // 只转一次不等于「不转」：值里的分隔符必须挡住，否则参数会被切开。
        val u = ApiMath.buildUrl(
            "https://x.com", "/w",
            fixedQuery = mapOf("q" to "{q}"),
            args = mapOf("q" to "a&b=c"),
        )
        assertEquals("https://x.com/w?q=a%26b%3Dc", u)
    }

    @Test
    fun `没有任何 query 时不留一个光秃秃的问号`() {
        assertEquals("https://x.com/w", ApiMath.buildUrl("https://x.com", "/w"))
    }

    // ---- 升级补种(新预置怎么到达一台已经装过的手机) ----

    @Test
    fun `全新安装 —— 一件不落全种下去`() {
        val p = ApiMath.planSeedMerge(emptySet(), listOf("p:weather", "p:route", "p:web"), emptySet())
        assertEquals(listOf("p:weather", "p:route", "p:web"), p.toAdd)
        assertEquals(setOf("p:weather", "p:route", "p:web"), p.nextOffered)
    }

    @Test
    fun `升级新加的会被补上 —— 这正是不做这个函数时丢的那件事`() {
        // ★★ 2026-10-05 真机实况:盘上早就有 weather/route,账本记着它俩;
        //    这一版新加了 web。旧代码的判据是「purposes.json 在不在」→ 在 → 什么都不种
        //    → **「上网查」根本没上手机**,而编译、单测、装机全都好好的。
        val p = ApiMath.planSeedMerge(
            offered = setOf("p:weather", "p:route"),
            seedKeys = listOf("p:weather", "p:route", "p:web"),
            existing = setOf("p:weather", "p:route"),
        )
        assertEquals(listOf("p:web"), p.toAdd)
    }

    @Test
    fun `用户删掉的永远不许复活`() {
        // 他删了 route。账本里记着「给过」→ 不是 fresh → 不补。
        // ★ 判据必须是「我们给过哪些」,不能是「盘上现在有哪些」——
        //   后者会让他删一次、我们补一次,像个流氓软件。
        val p = ApiMath.planSeedMerge(
            offered = setOf("p:weather", "p:route"),
            seedKeys = listOf("p:weather", "p:route"),
            existing = setOf("p:weather"),
        )
        assertTrue("删掉的被补回来了:${p.toAdd}", p.toAdd.isEmpty())
    }

    @Test
    fun `账本丢了也不会重复种 —— 认得盘上已有的`() {
        // 老版本升上来、还没有 seeded.json 的情况:账本是空的,但盘上有东西。
        // 这时「已有的」必须靠 existing 挡住,否则会把他的配置**覆盖**成预置档。
        val p = ApiMath.planSeedMerge(
            offered = emptySet(),
            seedKeys = listOf("p:weather", "p:route"),
            existing = setOf("p:weather", "p:route"),
        )
        assertTrue("重复种了一遍:${p.toAdd}", p.toAdd.isEmpty())
        assertEquals(setOf("p:weather", "p:route"), p.nextOffered)
    }

    @Test
    fun `全删光之后一件都不补 —— 账本是满的,所以它们不是 fresh`() {
        // 盘上空的、账本满的 = 他**把预置项全删了**。这是「不复活」那条规矩的极端情形。
        // (全新安装是反过来:账本空、盘也空 → 全补。见上面第一条。)
        val p = ApiMath.planSeedMerge(
            offered = setOf("p:weather", "p:route"),   // 给过
            seedKeys = listOf("p:weather", "p:route"),
            existing = emptySet(),                     // 他全删了
        )
        assertTrue("删光的被补回来了:${p.toAdd}", p.toAdd.isEmpty())
        assertEquals(setOf("p:weather", "p:route"), p.nextOffered)
    }

    @Test
    fun `账本和盘上都没有变化时,计划是空的`() {
        // 稳态:每次启动都会走一遍这段,它必须是**零副作用**的,
        // 否则每次冷启动都重写一遍 purposes.json(而那个文件里有加密的 key)。
        val p = ApiMath.planSeedMerge(
            offered = setOf("p:weather", "p:route"),
            seedKeys = listOf("p:weather", "p:route"),
            existing = setOf("p:weather", "p:route"),
        )
        assertTrue(p.toAdd.isEmpty())
        assertEquals(setOf("p:weather", "p:route"), p.nextOffered)
    }

    @Test
    fun `工具和用途共用一本账,各记各的键`() {
        // 同一轮里:用途补了新的(账本从 2 条变 3 条),工具那边拿的是**更新过的**账本。
        // 用错(还在拿旧账本)的症状是工具被重复判定成 fresh,每启动一次补一遍。
        val step1 = ApiMath.planSeedMerge(
            offered = setOf("p:weather", "p:route"),
            seedKeys = listOf("p:weather", "p:route", "p:web"),
            existing = setOf("p:weather", "p:route"),
        )
        val step2 = ApiMath.planSeedMerge(
            offered = step1.nextOffered,               // ← 关键:用更新过的那本
            seedKeys = listOf("t:weather/weather_now", "t:web/web_search"),
            existing = setOf("t:weather/weather_now"),
        )
        assertEquals(listOf("t:web/web_search"), step2.toAdd)
    }

    // ---- 网页取正文(「上网查」那一档) ----

    @Test
    fun `标签脱掉,正文留下`() {
        val got = ApiMath.htmlToText("<html><body><h1>上海到北京</h1><p>高铁 G 字头</p></body></html>")
        assertEquals("上海到北京\n高铁 G 字头", got)
    }

    @Test
    fun `整块丢掉的标签 —— 脚本和样式一个字都不许留下`() {
        // ★★ 这条是这个功能里最要紧的一条。实测一个搜索页 **100 KB,正文只有 1.7 KB**
        //    —— 留下来的全是脚本。不丢 = 模型认真地去「理解」一坨 JS 变量名,
        //    而真正的结果被挤出 maxChars 之外(等于查了,但没看见)。
        val html = "<div>正文</div><script>var a=1;if(a<2){console.log('x')}</script><style>.a{color:red}</style>"
        val got = ApiMath.htmlToText(html)
        assertEquals("正文", got)
    }

    @Test
    fun `脚本标签没闭合时一路丢到末尾,不许把剩下的文档当成脚本`() {
        // ★ 正则版在这儿会失手:非贪婪匹配要一个 `</script>`,没有就整段不匹配,
        //   于是标签被脱掉、**脚本正文全留下来**,混进正文喂给模型。
        //   而且它不报错 —— 表现成「她答得莫名其妙」。
        val html = "<p>正文</p><script>var secret='这一坨绝不该被当成正文';</script" + ">"
        val got = ApiMath.htmlToText("<p>正文</p><script>var x=1")
        assertEquals("正文", got)
        assertFalse("脚本正文漏出来了:$got", got.contains("var x"))
        assertTrue(ApiMath.htmlToText(html).contains("正文"))
    }

    @Test
    fun `注释里的假标签不算标签`() {
        val got = ApiMath.htmlToText("<p>真</p><!-- <script>假</script> --><p>也真</p>")
        assertEquals("真\n也真", got)
    }

    @Test
    fun `实体要还原,但不许把远处的分号吞进来`() {
        // ★ 正文里 `&` 本来就很常见(A&B、R&D)。「从 & 往后找分号」在
        //   `A&amp;B … 很多字 … ;` 这种句子上会一路找到很远,把中间整段吞掉 ——
        //   那是**静默丢字**,查不出来。
        assertEquals("A&B", ApiMath.htmlToText("A&amp;B"))
        assertEquals("©2026", ApiMath.htmlToText("&copy;2026"))
        assertEquals("—", ApiMath.htmlToText("&mdash;"))
        assertEquals("…", ApiMath.htmlToText("&hellip;"))
        val far = ApiMath.htmlToText("A&B 这一段有好几个字 然后再来 ; 结尾")
        assertTrue("中间那段被吞了:$far", far.contains("这一段有好几个字"))
    }

    @Test
    fun `空格和 nbsp 都折成一个`() {
        // ★ nbsp 在网页里到处都是。不折掉的话正文里会一串串留着,
        //   白占 maxChars(真正的内容被挤出去)。
        assertEquals("上海 到 北京", ApiMath.htmlToText("<p>上海&nbsp;&nbsp;到   北京</p>"))
    }

    @Test
    fun `空行压掉,行内空白去头尾`() {
        val got = ApiMath.htmlToText("<div>  a  </div>\n\n\n<div></div><div>b</div>")
        assertEquals("a\nb", got)
    }

    @Test
    fun `只靠脚本画出来的页面 → 空串(调用方据此报错,不许当成功)`() {
        val got = ApiMath.htmlToText("<html><head><script>app.render()</script></head><body><div id=root></div></body></html>")
        assertEquals("", got)
    }

    @Test
    fun `认不出来的实体原样留着 —— 悄悄吃掉就是少字`() {
        val got = ApiMath.htmlToText("&notarealentity; 和 x")
        assertTrue("实体被吃掉了:$got", got.contains("&notarealentity;"))
    }

    // ---- 参数缺没缺 ----

    @Test
    fun `缺哪些参数要能点名`() {
        val miss = ApiMath.missingArgs(listOf("from", "to"), mapOf("from" to "上海站"))
        assertEquals(listOf("to"), miss)
    }

    // ---- 用量 ----

    @Test
    fun `用量记得住成功和失败`() {
        var u = ApiUsage()
        u = ApiMath.after(u, ok = true, ms = 320, status = 200, err = "", nowMs = 1000)
        u = ApiMath.after(u, ok = false, ms = 5000, status = 403, err = "key 不对", nowMs = 2000)
        assertEquals(2, u.calls)
        assertEquals(1, u.ok)
        assertEquals(1, u.fail)
        assertEquals(2000L, u.lastAtMs)
        assertEquals(403, u.lastStatus)
    }

    @Test
    fun `用量那行必须写明是本机记的账`() {
        // ★★ 我们记的和服务商账单**一定会有差**(重试、失败、并发、对方侧计费口径)。
        //    用户拿它去对钱对不上,是我们的错。这句不是免责声明,是诚实边界。
        val u = ApiMath.after(ApiUsage(), ok = true, ms = 100, status = 200, err = "", nowMs = 1)
        assertTrue(ApiMath.usageLine(u).contains("本机记的账"))
    }

    @Test
    fun `没调过就说没调过`() {
        assertEquals("还没调过。", ApiMath.usageLine(ApiUsage()))
    }

    // ---- 面板那一行 ----

    @Test
    fun `二级那行直接写出当前用的是谁`() {
        // ★ 答案不该藏在三级页里。
        val p = purpose(profiles = listOf(profile(id = "a", label = "高德", key = "0f3a9c1e5b7d2a8f")), activeId = "a")
        val s = ApiMath.summary(p)
        assertTrue(s, s.contains("高德"))
        assertFalse("副标题里不该有完整 key", s.contains("0f3a9c1e5b7d2a8f"))
    }

    @Test
    fun `多档时告诉他一共有几档`() {
        val p = purpose(
            profiles = listOf(
                profile(id = "a", label = "高德", key = "k1", enabled = false),
                profile(id = "b", label = "百度", key = "k2"),
            ),
            activeId = "b",
        )
        assertTrue(ApiMath.summary(p).contains("共 2 档"))
    }

    @Test
    fun `不需要 key 的档如实写出来`() {
        val p = purpose(profiles = listOf(profile(id = "a", label = "wttr.in", key = "")), activeId = "a")
        assertTrue(ApiMath.summary(p).contains("不需要 key"))
    }
}
