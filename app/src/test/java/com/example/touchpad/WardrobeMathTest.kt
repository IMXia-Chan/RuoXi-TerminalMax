package com.example.touchpad

import com.example.touchpad.WardrobeMath.Candidate
import com.example.touchpad.WardrobeMath.Pick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换装判定的钉子。
 *
 * ★ 为什么这组测试**必须**存在(2026-10-05 下午):
 *
 * 这一轮里**手机是拔着的** —— 换装那条路在真机上一格都验不了。所以「判定对不对」
 * 只能在这儿咬住。**没被这几条钉住的换装行为,一律等于没做。**
 *
 * 而这些用例挑的**不是**边界条件的趣味,是**每一种「我换了它没变」的真实长相**:
 *
 * | 用户干了什么 | 屏幕上看到什么 | 该说什么 |
 * |---|---|---|
 * | 放了个 0 字节的坏文件 | 没变 | 「找到了 1 个,但一个都用不了:xx.vrm(0 字节)」|
 * | 放了两个,以为用的是 B | 用的是 A | 「用 A,没用上 B」|
 * | 放了个 `.html` | (绝不能生效) | 白名单挡掉 **且不留任何日志以外的痕迹** |
 * | 文件名是中文 / 大写扩展名 | 没变 | 照样认得出来 |
 */
class WardrobeMathTest {

    private fun c(name: String, bytes: Long = 1024L, readable: Boolean = true) =
        Candidate(name, bytes, readable)

    // ---------------------------------------------------------------- 扩展名

    @Test
    fun `扩展名 大小写和点点都要认对`() {
        assertEquals("vrm", WardrobeMath.extOf("a.vrm"))
        assertEquals("vrm", WardrobeMath.extOf("A.VRM"))
        assertEquals("gltf", WardrobeMath.extOf("房间.gltf"))
        // 没有点、点在开头(隐藏文件)、点在末尾 —— 三种都算「没有扩展名」
        assertEquals("", WardrobeMath.extOf("README"))
        assertEquals("", WardrobeMath.extOf(".nomedia"))
        assertEquals("", WardrobeMath.extOf("a."))
        // 多个点取最后一个
        assertEquals("vrm", WardrobeMath.extOf("my.model.v2.vrm"))
    }

    // ---------------------------------------------------------------- 白名单

    /**
     * ★★ 这一组是**安全**判据,不是功能判据。别删。
     *
     * 外部目录的文件会被 serve 进 `https://appassets.androidplatform.net/`,
     * 而 `HerBridge` 就挂在那个源上。**一个 `.js` 掉进去 = 在自己的源里执行任意脚本。**
     */
    @Test
    fun `数据文件放行`() {
        for (n in listOf("a.vrm", "room.glb", "room.gltf", "buf.bin",
                         "floor.png", "wall.JPG", "x.jpeg", "y.webp", "room.json",
                         "待机.vrma")) {
            assertTrue("$n 该放行", WardrobeMath.allowedExternal(n))
        }
    }

    @Test
    fun `可执行的那几种一律不放行`() {
        // 这四种掉进外部目录,就等于在这个 App 自己的源里注入了代码。
        for (n in listOf("evil.html", "evil.js", "evil.css", "evil.svg",
                         "EVIL.HTML", "evil.mjs", "evil.htm")) {
            assertFalse("★ $n 绝不能放行 —— 那是往自己的源里放脚本", WardrobeMath.allowedExternal(n))
        }
    }

    @Test
    fun `认不出来的扩展名不放行`() {
        // 白名单不是黑名单:没列出来的一律当「不存在」,连读都不读。
        for (n in listOf("a.apk", "a.dex", "a.sh", "a.exe", "a", "a.", "")) {
            assertFalse("$n 该拦下", WardrobeMath.allowedExternal(n))
        }
    }

    @Test
    fun `隐藏文件和不带路径的名字要挡掉`() {
        assertFalse(WardrobeMath.allowedExternal(".nomedia"))
        assertFalse(WardrobeMath.allowedExternal(".hidden.vrm"))
        // 带路径分隔符的当场拒 —— 判定层拒掉,比指望下面那层拼接不出错可靠
        assertFalse(WardrobeMath.allowedExternal("a/../b.png"))
        assertFalse(WardrobeMath.allowedExternal("..\\b.png"))
    }

    // ---------------------------------------------------------------- pick

    @Test
    fun `空目录说没有`() {
        val p = WardrobeMath.pick(emptyList(), WardrobeMath.MODEL_EXTS)
        assertTrue(p is Pick.None)
        assertTrue((p as Pick.None).why.contains(".vrm"))
    }

    @Test
    fun `只认扩展名 不认文件名`() {
        // 「放文件就生效」的全部意思:他丢进来的一定叫「新人物.vrm」这种名字,我们猜不到。
        val p = WardrobeMath.pick(
            listOf(c("README.txt"), c("他新作.vrm")),
            WardrobeMath.MODEL_EXTS,
        )
        assertEquals(Pick.Found("他新作.vrm"), p)
    }

    @Test
    fun `大小写不同的扩展名照样认`() {
        val p = WardrobeMath.pick(listOf(c("NEW.VRM")), WardrobeMath.MODEL_EXTS)
        assertEquals(Pick.Found("NEW.VRM"), p)
    }

    @Test
    fun `放过一个不相关的文件不影响判定`() {
        val p = WardrobeMath.pick(
            listOf(c(".nomedia", 0), c("thumb.png"), c("ok.vrm")),
            WardrobeMath.MODEL_EXTS,
        )
        assertEquals(Pick.Found("ok.vrm"), p)
    }

    @Test
    fun `放两个要用排第一那个 并且把没用上的说出来`() {
        // ★ 这条治的是:**用户以为换的是 B,实际用的是 A**,而且它不报错。
        val p = WardrobeMath.pick(
            listOf(c("b.vrm"), c("a.vrm")),
            WardrobeMath.MODEL_EXTS,
        )
        assertEquals(Pick.Found("a.vrm", alsoFound = listOf("b.vrm")), p)
    }

    @Test
    fun `坏文件不许顶掉好文件 但要说出来`() {
        val p = WardrobeMath.pick(
            listOf(c("aa_broken.vrm", 0), c("zz_good.vrm")),
            WardrobeMath.MODEL_EXTS,
        )
        // 排第一的是坏的那个 —— 它必须被跳过,而不是让房间变空白
        assertEquals(Pick.Found("zz_good.vrm", broken = listOf("aa_broken.vrm(0 字节)")), p)
    }

    @Test
    fun `读不到和零字节要分开说`() {
        // 「不在」和「在但读不到」是两件事 —— SherpaVoice.filesPresent 就是在这上面撒过谎
        val p = WardrobeMath.pick(
            listOf(c("a.vrm", 100, readable = false), c("b.vrm", 0)),
            WardrobeMath.MODEL_EXTS,
        )
        assertTrue(p is Pick.None)
        val why = (p as Pick.None).why
        assertTrue("要说读不到:$why", why.contains("读不到"))
        assertTrue("要说 0 字节:$why", why.contains("0 字节"))
    }

    @Test
    fun `一个都用不了时要说清是哪个 而不是说没有`() {
        // ★ 这条是「明明放对了却说她没换」的头号长相。
        //   不说出来的话,它和「你放错文件夹了」在屏幕上完全一样。
        val p = WardrobeMath.pick(listOf(c("人物.vrm", 0)), WardrobeMath.MODEL_EXTS)
        assertTrue(p is Pick.None)
        val why = (p as Pick.None).why
        assertTrue("要带上文件名:$why", why.contains("人物.vrm"))
        assertTrue("不许只说「没有」:$why", !why.contains("目录里没有"))
    }

    @Test
    fun `排序要确定 同样的目录永远同一个结论`() {
        // 否则「重启一次换一个人」这种事会变成玄学
        val files = listOf(c("c.vrm"), c("a.vrm"), c("b.vrm"))
        val first = WardrobeMath.pick(files, WardrobeMath.MODEL_EXTS)
        repeat(5) {
            assertEquals(first, WardrobeMath.pick(files.shuffled(), WardrobeMath.MODEL_EXTS))
        }
    }

    @Test
    fun `多了扩展名时按给的顺序一起认`() {
        val p = WardrobeMath.pick(
            listOf(c("note.txt"), c("scene.gltf")),
            WardrobeMath.SCENERY_EXTS,
        )
        assertEquals(Pick.Found("scene.gltf"), p)
    }

    // ---------------------------------------------------------------- 她的动作(.vrma)

    /**
     * ★★ 这一组钉的是**「动作和她本人是两样东西」**。
     *
     * 把 `.vrma` 并进 `MODEL_EXTS` 是个**看起来很顺手的错**,后果却是:
     * 「换动作」变成「把她换掉」—— 她本人被一条没有身体的动画顶走,
     * 台上剩一堆骨骼挂在空中。而它**不报错**,只表现成「人没了」。
     *
     * 反过来同样致命:动作文件被当成「她本人」挑出来之后,
     * `person/` 里那份真模型就再也没人用得上。两个方向都钉。
     */
    @Test
    fun `动作和她本人各认各的 不许互相顶掉`() {
        val files = listOf(c("人物.vrm"), c("站姿.vrma"))

        assertEquals("她本人只认 .vrm", Pick.Found("人物.vrm"),
            WardrobeMath.pick(files, WardrobeMath.MODEL_EXTS))
        assertEquals("动作只认 .vrma", Pick.Found("站姿.vrma"),
            WardrobeMath.pick(files, WardrobeMath.MOTION_EXTS))
    }

    @Test
    fun `动作也只认扩展名 不认文件名`() {
        // 他从 BOOTH 下下来的那份一定叫一串日文/英文名,我们猜不到 —— 和人物同一条规矩。
        val p = WardrobeMath.pick(
            listOf(c("README.txt"), c("自然な立ち待機モーション.vrma")),
            WardrobeMath.MOTION_EXTS,
        )
        assertEquals(Pick.Found("自然な立ち待機モーション.vrma"), p)
    }

    @Test
    fun `没放动作时要说出它认哪种扩展名`() {
        // Kotlin 那边靠这句话决定「一句话都不说」(真的没放)
        // 和「放了个用不了的」(必须说出来)—— 两者在屏幕上长得一样。
        val p = WardrobeMath.pick(listOf(c("人物.vrm")), WardrobeMath.MOTION_EXTS)
        assertTrue(p is Pick.None)
        assertTrue((p as Pick.None).why.contains(".vrma"))
    }

    @Test
    fun `坏掉的动作要说出来 不许让她僵在原地`() {
        // 半截下载 / 写到一半断电 —— 全是 0 字节这个长相。
        // 不说出来的话,它和「你没放动作」一模一样,而她会**一直僵着**。
        val p = WardrobeMath.pick(listOf(c("站姿.vrma", 0)), WardrobeMath.MOTION_EXTS)
        assertTrue(p is Pick.None)
        assertTrue((p as Pick.None).why.contains("站姿.vrma"))
    }

    @Test
    fun `动作放两个一样只认排第一的`() {
        val p = WardrobeMath.pick(
            listOf(c("b.vrma"), c("a.vrma")),
            WardrobeMath.MOTION_EXTS,
        )
        assertEquals(Pick.Found("a.vrma", alsoFound = listOf("b.vrma")), p)
    }

    @Test
    fun `动作的日志也要说全`() {
        val got = WardrobeMath.sentence("动作", Pick.Found("站姿.vrma"))
        assertTrue(got, got.contains("动作"))
        assertTrue(got, got.contains("站姿.vrma"))
    }

    // ---------------------------------------------------------------- 日志那句话

    @Test
    fun `日志要把用谁 跳过了谁 没用上谁 全说出来`() {
        // ★ 这行日志是「我换了它怎么没变」的唯一分界线。它必须说得全。
        val got = WardrobeMath.sentence(
            "人物",
            Pick.Found("a.vrm", alsoFound = listOf("b.vrm"), broken = listOf("c.vrm(0 字节)")),
        )
        assertTrue(got, got.contains("用 a.vrm"))
        assertTrue(got, got.contains("跳过 c.vrm(0 字节)"))
        assertTrue(got, got.contains("没用上 b.vrm"))
    }

    @Test
    fun `没有可用的时日志要带上原因`() {
        val got = WardrobeMath.sentence("房间模型", Pick.None("目录里没有 .glb / .gltf 文件"))
        assertTrue(got, got.contains("房间模型"))
        assertTrue(got, got.contains("目录里没有"))
    }

    // ---------------------------------------------------------------- 挑动作:库里的相对路径(2026-10-09)

    /**
     * ★★ 这一组是**安全**判据,和 `allowedExternal` 那条同族,但管的是**另一件事**。
     *
     * `allowedExternal` 只看得到 **basename**(`substringAfterLast('/')`)—— 所以
     * `a/../../x.vrma` 会**堂堂正正地过它的关**(它的 basename 是合规的 `.vrma`)。
     * 这一组补的就是那一段:**路径**归它管,**扩展名**归上面那条管,两道都要有。
     *
     * 库里那个新目录(`motion-library/`,含三个来源的子目录)是**唯一**一个
     * 「路径里带斜杠」的入口 —— 所以这道闸只在这儿长出来,别的路一个字不动。
     */
    @Test
    fun `库里的合法路径要放行`() {
        // 库里的真实长相:一层子目录 + 文件名。三个来源都是这个形状。
        for (p in listOf(
            "Relax.vrma",
            "vrm-viewer/Relax.vrma",
            "vroid-official/VRMA_03.vrma",
            "voxavatar/failed-apology.vrma",
        )) {
            assertTrue("$p 该放行", WardrobeMath.safeLibraryPath(p))
        }
    }

    @Test
    fun `库里爬出目录的写法一律挡掉`() {
        // ★ 这是这一道闸存在的**全部理由**:basename 合规、路径不合规。
        for (p in listOf(
            "a/../../x.vrma",
            "../x.vrma",
            "..",
            "./x.vrma",
            "a/./b.vrma",
            "vrm-viewer//x.vrma",
            "/data/x.vrma",
            "/sdcard/x.vrma",
            "vrm-viewer\\x.vrma",
            "",
            "   ",
        )) {
            assertFalse("★ $p 绝不能放行", WardrobeMath.safeLibraryPath(p))
        }
    }

    @Test
    fun `库里的路径也过扩展名白名单`() {
        // ★ 两道闸是**串**的,不是**或**:路径合规**不代表**放行。
        //   库里掉一个 .html 进来 = 在 App 自己的源里执行脚本,理由同文件头。
        assertFalse(WardrobeMath.safeLibraryPath("vrm-viewer/evil.js"))
        assertFalse(WardrobeMath.safeLibraryPath("a/evil.html"))
        // ★ 反过来也要钉:路径全合规、扩展名不认 —— 挡下它的是**扩展名那一道**
        //   (证明这两道不是同一件事,少一道就漏一种)
        assertFalse(WardrobeMath.safeLibraryPath("vrm-viewer/evil.exe"))
        assertTrue("路径本身是干净的", WardrobeMath.safeLibraryPath("vrm-viewer/pose.vrma"))
    }

    @Test
    fun `库里的目录名要点得出来`() {
        assertEquals("vrm-viewer", WardrobeMath.libraryFolder("vrm-viewer/Relax.vrma"))
        assertEquals("voxavatar", WardrobeMath.libraryFolder("voxavatar/walk.vrma"))
        // 直接躺在库根上的:没有目录
        assertEquals("", WardrobeMath.libraryFolder("Relax.vrma"))
    }

    @Test
    fun `认识的动作要说中文名`() {
        // ★ 界面上那一栏的全部价值就是「一眼认出来我想让她做哪个」;
        //   摆一排英文他要先在心里翻译一遍才能点。
        assertEquals("放松", WardrobeMath.motionLabel("vrm-viewer/Relax.vrma"))
        assertEquals("生气", WardrobeMath.motionLabel("vrm-viewer/Angry.vrma"))
        assertEquals("比耶", WardrobeMath.motionLabel("vroid-official/VRMA_03.vrma"))
        assertEquals("走路", WardrobeMath.motionLabel("voxavatar/walk.vrma"))
        assertEquals("她原来的待机", WardrobeMath.motionLabel("idle_loop.vrma"))
    }

    @Test
    fun `大小写和目录不影响认名`() {
        assertEquals("放松", WardrobeMath.motionLabel("vrm-viewer/RELAX.VRMA"))
        assertEquals("放松", WardrobeMath.motionLabel("RELAX.vrma"))
        assertEquals("放松", WardrobeMath.motionLabel("vrm-viewer/Relax.vrma"))
    }

    @Test
    fun `认不出来就退回文件名 不编不猜`() {
        // ★ 他以后自己丢进来的东西走这条路 —— 那正是它该有的行为。
        assertEquals("my_pose", WardrobeMath.motionLabel("vrm-viewer/my_pose.vrma"))
        assertEquals("站姿", WardrobeMath.motionLabel("站姿.vrma"))
        // ★ 别把「认不出」做成「回一个空串」:那一栏会变成一排空按钮,而且不报错。
        assertTrue(WardrobeMath.motionLabel("x.vrma").isNotEmpty())
    }

    @Test
    fun `没扩展名或者空的时候不许崩`() {
        // 判定层不许抛 —— 抛在 UI 那条路上就是「她的房间打不开」。
        assertEquals("README", WardrobeMath.motionLabel("vrm-viewer/README"))
        WardrobeMath.motionLabel("")
        WardrobeMath.motionLabel("/")
        WardrobeMath.motionLabel(".vrma")
    }
}
