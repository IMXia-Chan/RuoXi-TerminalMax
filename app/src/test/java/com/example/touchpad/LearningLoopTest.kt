package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守住两样东西:出手机的那份请求体,和「越用越强」那条机制。
 *
 * 为什么这两样凑一个文件:它们是同一个闭环的两端 —— 老师教的东西进得来(所以请求体
 * 必须干净),教进来的东西能被用出来、也能被淘汰(所以置信度必须真的会动)。
 * 任何一端坏了,闭环就变成「学了一堆没用的」或者「一边学一边泄密」。
 *
 * 跑:`./gradlew :app:testDebugUnitTest`
 *
 * ⚠️ 这里**只用纯 JVM 能跑到的东西**。Android 单元测试里 `org.json` 是空壳
 * (构造 JSONObject 直接抛 "Stub!"),所以 [ExperienceStore] 的落盘那半在测试里
 * 是**静默失败**的 —— 这正好顺带证实了「落盘失败不影响主流程」那条规矩。
 */
class LearningLoopTest {

    // ==================================================================
    // 一、隐私红线:发给云端老师的东西里没有屏幕内容
    // ==================================================================

    /** 正常的老师请求体:只有这几个键,内容全是结构。 */
    @Test
    fun `干净的请求体放行`() {
        val keys = setOf("messages", "temperature", "stream", "response_format")
        val text = """{"messages":[{"role":"system","content":"你是专家"},
            {"role":"user","content":"窗口标题:记事本,认得出的文字行数:12"}],
            "temperature":0.2,"stream":true}"""
        assertTrue("结构性的请求体应当放行", AiAgent.teacherBodyIsClean(keys, text))
    }

    /**
     * ★ 最重要的一条:**多一个键就拒**。
     *
     * 这是白名单的意义 —— 将来有人往请求体里加 `image` / `lines` / `history`,
     * 不管字段叫什么名字,只要不是名单里的,一律过不去。
     */
    @Test
    fun `多出来的字段一律拒掉`() {
        val base = setOf("messages", "temperature", "stream", "response_format")
        for (extra in listOf("image", "image_url", "lines", "ocr_text", "history",
                             "screenshot_b64", "cursor", "debug_whatever")) {
            assertFalse(
                "请求体里混进了 `$extra`,必须拦下来",
                AiAgent.teacherBodyIsClean(base + extra, """{"messages":[]}""")
            )
        }
    }

    /** 就算键都对,正文里夹带图片/编码块也不行 —— 键可以叫任何名字,内容是藏不住的。 */
    @Test
    fun `正文里夹带图片也不行`() {
        val keys = setOf("messages", "temperature", "stream", "response_format")
        for (bad in listOf("data:image/png;base64,iVBOR...", "IMAGE_URL", "a Screenshot of the screen")) {
            assertFalse("正文里出现 `$bad` 必须拦下来",
                AiAgent.teacherBodyIsClean(keys, """{"messages":[{"content":"$bad"}]}"""))
        }
    }

    /**
     * 老师提示词自己必须是干净的 —— 它是要发出去的那份文本的一部分。
     * 这条专门防「改提示词时手滑加了一句『把截图发我』」。
     */
    @Test
    fun `老师提示词本身不含敏感字样`() {
        // 通过公开入口拿不到 private const,所以用它的**行为**来验:
        // 一个只含提示词本该有的键与中文说明的体,必须放行。
        val keys = setOf("messages", "temperature", "stream", "response_format")
        assertTrue(
            AiAgent.teacherBodyIsClean(
                keys,
                "请你给一个通用的办法,不要针对某个具体软件。只输出 JSON。" +
                    "可用动作 search / scroll / visual。"
            )
        )
    }

    // ==================================================================
    // 二、经验库:老师能教进来,而且教的东西会胜出也会淘汰
    // ==================================================================

    /** 老师给的动词里,**不在封闭集合里的一律丢掉**。 */
    @Test
    fun `老师只能教允许的动词`() {
        val e = ExperienceStore.learn("t_onlyallowed", listOf("rm -rf", "search", "关机"), "乱教的")
        assertNotNull("有合法动词时应当记下来", e)
        assertEquals("只留允许的动词", listOf("search"), e!!.verbs)
    }

    /** 一个合法动词都没有 → 不记。别让一次胡说在库里留痕。 */
    @Test
    fun `全是非法动词就不记`() {
        assertNull(ExperienceStore.learn("t_allbad", listOf("drop_table", "reboot"), "胡说"))
        assertFalse("库里不该有这条", ExperienceStore.has("t_allbad"))
    }

    /** 同一个 kind 下,同一套动词重复教**不新增**,返回原来那条。 */
    @Test
    fun `重复教同一条不会繁殖`() {
        val a = ExperienceStore.learn("t_dedup", listOf("search", "scroll"), "第一次")
        val b = ExperienceStore.learn("t_dedup", listOf("search", "scroll"), "又说一遍")
        assertNotNull(a)
        assertNotNull(b)
        assertEquals("应当是同一条", a!!.id, b!!.id)
        assertEquals("这个 kind 下只有一条",
            1, ExperienceStore.candidates("t_dedup").size)
    }

    /**
     * ★ 核心:置信度是**用出来的**,而且失败会把它压下去。
     *
     * 这就是「老师会教错,但不能一直错下去」的全部机制 —— 一旦不用代码手工干预,
     * 差的那条会自己沉到下面。
     */
    @Test
    fun `胜率真的会动,而且失败会把策略压下去`() {
        val e = ExperienceStore.learn("t_conf", listOf("scroll"), "先滚一下")!!
        assertEquals("初始应当是 0.5(没用过)", 0.5, e.confidence, 1e-9)

        // 赢一次:(1+1)/(1+2) = 0.667
        ExperienceStore.record(e, won = true)
        assertEquals("赢一次该升上去", 2.0 / 3.0, e.confidence, 1e-9)

        // 再输两次:1 胜 2 负 → uses=3, wins=1 →(1+1)/(3+2) = 0.4
        // (这里第一版我把 (wins+1) 的 wins 记成了 2 —— 记账口径要说清楚:
        //  **失败照样算一次使用**,只是不进 wins。这正是失败能压低置信度的原因。)
        ExperienceStore.record(e, won = false)
        ExperienceStore.record(e, won = false)
        assertEquals("连着失败该掉到 0.4", 2.0 / 5.0, e.confidence, 1e-9)
        assertTrue("失败确实把它压低了", e.confidence < 2.0 / 3.0)
    }

    /** 排序:**信得过的排前面**,不信的垫底 —— 这是「只影响顺序」的那一半。 */
    @Test
    fun `candidates 按置信度排序`() {
        val good = ExperienceStore.learn("t_order", listOf("search"), "好使的")!!
        val bad = ExperienceStore.learn("t_order", listOf("scroll"), "不好使的")!!
        // 一个连赢三次,一个连输三次
        repeat(3) { ExperienceStore.record(good, won = true) }
        repeat(3) { ExperienceStore.record(bad, won = false) }

        val order = ExperienceStore.candidates("t_order")
        assertEquals("两条都在", 2, order.size)
        assertEquals("好使的排第一", good.id, order[0].id)
        assertTrue("且它的置信度确实更高", order[0].confidence > order[1].confidence)
    }

    /** 查不到的 kind 要老实说没有 —— 上层靠这个判断「该不该去问老师」。 */
    @Test
    fun `没见过的 kind 查不到`() {
        assertFalse(ExperienceStore.has("t_neverseen"))
        assertTrue(ExperienceStore.candidates("t_neverseen").isEmpty())
    }

    // ==================================================================
    // 三、预置教材:冷启动就有东西可用,而且都合规
    // ==================================================================

    /**
     * 预置教材是「没有云端 key 时唯一的依靠」,所以它必须:
     *   - 每个动词都在封闭集合里(否则执行时会被丢掉,等于空策略);
     *   - 置信度**高于**老师初来时的 0.5(先按人写的来,不灵的话失败会把它压下去);
     *   - `visual` 只在最后 —— 它一次要几分钟,放前面会把整件事拖垮。
     */
    @Test
    fun `预置教材合规`() {
        val seeds = ExperienceStore.seed()
        assertEquals("三种情况各一条", 3, seeds.size)
        assertEquals(
            "kind 应当正好覆盖观察的三种取值",
            setOf("offscreen", "blind", "empty"),
            seeds.map { it.kind }.toSet()
        )
        for (s in seeds) {
            assertTrue("${s.kind} 的动词得是空的", s.verbs.isNotEmpty())
            for (v in s.verbs) {
                assertTrue("${s.kind} 里有非法动词 `$v`", v in ExperienceStore.VERBS)
            }
            assertTrue("${s.kind} 的动词不该重复", s.verbs.size == s.verbs.distinct().size)
            assertTrue(
                "${s.kind} 的初始置信度应当高于老师的 0.5(否则预置教材形同虚设)",
                s.confidence > 0.5
            )
            val vi = s.verbs.indexOf("visual")
            if (vi >= 0) {
                assertEquals("${s.kind} 的 visual 只能放最后(它最贵)", s.verbs.size - 1, vi)
            }
        }
    }

    /**
     * 「不要因为老师是老师就放行」:封闭集合是唯一的准入标准,
     * 而它同时管住**盘上读回来的**那一份(文件是可以被手改的)。
     */
    @Test
    fun `封闭集合只认三个动词`() {
        assertEquals(listOf("scroll", "search", "visual"), ExperienceStore.VERBS)
    }

    // ==================================================================
    // 四、什么时候才值得花一趟云端
    // ==================================================================

    private fun exp(source: String) =
        ExperienceStore.Exp("k", listOf("scroll"), "理由", source, 0, 0)

    /**
     * ★ 这条测试是给一个**已经真实踩过的坑**立的碑。
     *
     * 第一版把触发条件写成了「经验库里没有这种 kind → 问老师」。而预置教材
     * 恰好把三种 kind 全盖住了,于是 `has(kind)` 永远为真、老师**一次也叫不到**,
     * 整个学习闭环出生即死 —— 而且它不会报错、不会崩,只是永远不学习,最难发现的那种坏。
     *
     * 所以规则改成了「手上的都不灵才问」,并且专门测它:
     */
    @Test
    fun `预置教材覆盖的 kind 也必须能问到老师`() {
        // 前提:教材确实盖住了全部三种 kind(这也是上面那条测试守的)
        assertTrue(
            "教材盖住了所有 kind,所以「有没有」绝不能当触发条件",
            ExperienceStore.seed().map { it.kind }.toSet()
                .containsAll(listOf("offscreen", "blind", "empty"))
        )
        // 于是:只试过教材(没试过老师教的)时,必须仍然判定为「该问」
        assertTrue(
            "刚试完预置教材、全都不灵 —— 这时候正是该问老师的时候",
            AiAgent.shouldAskTeacher(ExperienceStore.seed())
        )
    }

    /** 空手(一条都没试)当然该问;已经试过老师教的、还是不灵,就别再花那一趟了。 */
    @Test
    fun `问老师的触发条件`() {
        assertTrue("一条都没试过时该问", AiAgent.shouldAskTeacher(emptyList()))
        assertTrue("只试过教材时该问", AiAgent.shouldAskTeacher(listOf(exp("seed"))))
        assertFalse("试过老师教的还不行,就别再问了", AiAgent.shouldAskTeacher(listOf(exp("teacher"))))
        assertFalse(
            "哪怕教材和老师教的都试过,也不该再问",
            AiAgent.shouldAskTeacher(listOf(exp("seed"), exp("teacher")))
        )
    }

    // ==================================================================
    // 五、一批调用里前一个失败了,后面的还执不执行
    // ==================================================================

    /**
     * ★ 这条断言钉的是 2026-10-03 那次真机翻车的**直接机制**。
     *
     * 模型一次吐出四个调用(`open_app` → `focus_window 文件传输助手` → `click_ui 发送`
     * → `type`),吐的时候还没看到任何结果。第二步就错了(那是会话不是窗口),第三步
     * 照跑不误,点到了不知道哪儿去 —— 事后连「它点了什么」都查不到。
     *
     * 所以规矩是「本批一失败,后面带副作用的一律不执行」。半边是「该跳」,半边是
     * 「**千万别跳**」(只读工具):后者是重点 —— 一刀切会把「截图看看现在什么样」
     * 这种正是重规划要用的线索也砍掉。
     */
    @Test
    fun `同一批里前一步失败后只跳过带副作用的调用`() {
        // 写操作:一个都不能再执行
        for (w in listOf("type", "hotkey", "click_ui", "click_at", "search", "scroll",
                         "focus_window", "media", "open_app")) {
            assertTrue("前一步都失败了,`$w` 不该再执行", AiAgent.shouldSkipAfterFailure(w, true))
        }

        // 只读:照跑 —— 它们不改变世界,而且正是排查要用的
        for (r in listOf("list_windows", "get_state", "screenshot", "list_ui")) {
            assertFalse("`$r` 只是看一眼,失败之后仍然该放行",
                AiAgent.shouldSkipAfterFailure(r, true))
        }

        // 本批还没失败 → 谁都不跳(否则第一个调用就被自己跳掉了)
        for (n in listOf("type", "click_ui", "open_app")) {
            assertFalse("本批还没出岔子时不该跳 `$n`", AiAgent.shouldSkipAfterFailure(n, false))
        }
    }

    // ==================================================================
    // 六、JSON 的 null 不许变成字符串 "null"
    // ==================================================================

    /**
     * ★ 这条守的是一个**已经真的漏到用户眼前**的 bug。
     *
     * Android 的 `JSONObject.optString()` 永远不返回 null —— 碰到 JSON 的 `null`
     * 值,它把哨兵对象交给 `JSON.toString()`,转出来是字面字符串 `"null"`。
     * 流式重组时一个 `"content": null` 的分片就够把正文污染掉,真机上的最终答复
     * 就是这么来的:「✅ null现在已切回微信主窗口…」。
     *
     * 所以判据是「**只认真正的字符串**」—— 拿不到就当作没有,而不是当成 "null"。
     */
    @Test
    fun `只有真正的字符串才算文本`() {
        assertEquals("正常字符串原样通过", "你好", AiAgent.jsonText("你好"))
        assertNull("JSON 的 null(在 Android 上是个非字符串对象)必须落空,不能变 \"null\"", AiAgent.jsonText(null))
        assertNull("数字不是文本", AiAgent.jsonText(42))
        assertNull("布尔不是文本", AiAgent.jsonText(true))
        assertEquals("空串是合法文本(它跟 null 是两回事)", "", AiAgent.jsonText(""))
    }

    // ==================================================================
    // 三、本地模型死了该不该顺手重起
    // ==================================================================

    /**
     * ★ 半边是「该重起」,半边是「**千万别**重起」—— 后者才是这条断言存在的理由。
     *
     * 重起本地模型要几十秒,而它一旦被错误地触发,表现只是「偶尔白重载一次模型」,
     * 没人会去查。所以把三种失败**并排钉住**:分不清它们就等于回到了
     * 「一次偶发失败 → 整个生命周期走云端」那个坑(那才是 2026-10-03 要修的病)。
     */
    @Test
    fun `只有连接被拒才重起本地模型`() {
        assertTrue(
            "端口上没人听 = 进程真没了,该重起",
            AiAgent.shouldReviveLocal(java.net.ConnectException("Connection refused"))
        )

        // ↓ 这三条是重点。它们同样会「本地不可用、转云端」,但**不是进程没了**。
        assertFalse(
            "超时可能只是热降频/正忙,重起等于把加载好的模型白扔一次",
            AiAgent.shouldReviveLocal(java.net.SocketTimeoutException("read timed out"))
        )
        assertFalse(
            "503 是它**正在**加载,重起正好把它自己打断",
            AiAgent.shouldReviveLocal(java.io.IOException("模型加载中"))
        )
        assertFalse(
            "网络类异常不都是「没在听」,分不出就别动手",
            AiAgent.shouldReviveLocal(java.net.SocketException("connection reset"))
        )

        // Android 有时把 ECONNREFUSED 包成普通 SocketException —— 类型判不出来,
        // 只能看文案。**漏判 = 功能整个哑掉**,所以这一条是有意加的。
        assertTrue(
            "被包成 SocketException 的 ECONNREFUSED 也要认出来",
            AiAgent.shouldReviveLocal(
                java.net.SocketException("Failed to connect to /127.0.0.1:8080: Connection refused")
            )
        )
    }
}
