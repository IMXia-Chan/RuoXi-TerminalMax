package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 经验库那两条**纯规则**的测试 —— 不碰 Android、不碰 org.json。
 *
 * ★ 为什么只有这两条能测:`ExperienceStore` 是个 `object`,它的存储那半边
 *   (`init(ctx)` / `all()` / `add()` / `update()`)要 Context、还要 `JSONObject` ——
 *   而 JVM 单测里 `org.json` 是个**只会抛 "Stub!" 的空壳**。
 *   所以这个文件钉的是**唯二不碰这两样的东西**:[ExperienceStore.parseVerbs]
 *   (纯函数)和那两个常数(它们现在**印在屏幕上**)。
 *
 * ★★ 为什么值得单独钉 `parseVerbs`:它的调用方是**记忆库页面上的一个按钮**,
 *   他按下去之后没人再看第二眼。而它判错的样子是**静默的** ——
 *   他打了三步、存下来两步,而屏幕上写着「存好了」。见函数自己的 KDoc。
 */
class ExperienceStoreTest {

    // ------------------------------------------------------------------
    // parseVerbs:他手打的那串动词
    // ------------------------------------------------------------------

    @Test
    fun `空格分开的一串直接认`() {
        assertEquals(listOf("scroll", "search"), ExperienceStore.parseVerbs("scroll search"))
    }

    @Test
    fun `九种分隔符全认`() {
        // ★ 界面上的提示写的是「用空格分开」,但他手打时什么都会打出来 ——
        //   而**认不出来**的表现是「这条加不进去」,他不会想到是自己打的分隔符不对。
        val seps = listOf(' ', '\t', '\n', ',', '，', '、', '>', '→', '|')
        for (s in seps) {
            assertEquals("分隔符「${if (s == '\t') "\\t" else if (s == '\n') "\\n" else s.toString()}」没认出来",
                listOf("scroll", "search"), ExperienceStore.parseVerbs("scroll${s}search"))
        }
    }

    @Test
    fun `大小写和多余空白都不影响`() {
        assertEquals(listOf("scroll", "search"), ExperienceStore.parseVerbs("  SCROLL   Search  "))
    }

    @Test
    fun `重复的只留一个`() {
        // 不去的后果:一条「scroll scroll scroll」的经验,界面上显示三步、
        // 实际只有一步 —— 而它占掉的是**上限里的一格**。
        assertEquals(listOf("scroll", "search"),
            ExperienceStore.parseVerbs("scroll search scroll SEARCH"))
    }

    @Test
    fun `有一个词不认就整体作废`() {
        // ★★ 这是这条函数里最要紧的一格。不这么判的话,他打「search 关机」
        //   会得到一条**只有 search 的经验**,而他以为「关机」也进去了 ——
        //   下次她碰上这情况照着跑,少的那一步**没有任何地方会报**。
        assertNull(ExperienceStore.parseVerbs("search 关机"))
        assertNull(ExperienceStore.parseVerbs("scroll 打开微信"))
        assertNull(ExperienceStore.parseVerbs("search visual x"))
    }

    @Test
    fun `空的和全空白的都不要`() {
        assertNull(ExperienceStore.parseVerbs(""))
        assertNull("光敲空格等于没填", ExperienceStore.parseVerbs("    "))
        assertNull(ExperienceStore.parseVerbs(",,、→"))
    }

    @Test
    fun `动词表是封闭的_只认那三个`() {
        assertEquals(listOf("scroll", "search", "visual"), ExperienceStore.VERBS)
    }

    // ------------------------------------------------------------------
    // 两个印在屏幕上的数字
    // ------------------------------------------------------------------

    @Test
    fun `两个上限是界面上印着的那个数`() {
        // ★★ 记忆库那一页**把这两个数写在提示里**:
        //   「同一个情况最多留 8 条,多了会挤掉最老的那条」
        //   「最多 4 步」
        //   它们一旦和代码对不上,屏幕上那句提示就成了假话 ——
        //   而假话的后果是他按提示操作、结果和他想的不一样,还不报错。
        assertEquals(8, ExperienceStore.MAX_PER_KIND)
        assertEquals(4, ExperienceStore.MAX_VERBS)
    }

    @Test
    fun `动词表还没有长到会被步骤上限截断`() {
        // ★ 诚实记一笔:[parseVerbs] 末尾那个 `.take(MAX_VERBS)`(以及页面上
        //   「拦住他点第五个」那个判断)**今天是个空招** ——
        //   动词表只有 3 个、还要去重,永远到不了 4。
        //   这一条不是废话:哪天有人往 VERBS 里加了第四个、第五个动词,
        //   那个 take() 就**开始真的丢东西了**,而这里会立刻红。
        assertTrue("动词表已经长到 ${ExperienceStore.VERBS.size} 个 —— 步骤上限那条路开始真的丢东西了",
            ExperienceStore.VERBS.size <= ExperienceStore.MAX_VERBS)
    }
}
