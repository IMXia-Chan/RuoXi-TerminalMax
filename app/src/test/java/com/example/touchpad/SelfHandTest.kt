package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机自己那只手 —— 纯 JVM 能测的那一半。
 *
 * ★ [SelfHand.exec] 本身**测不了**(它要 Context、要 org.json,两样在纯 JVM 上都是空壳),
 * 所以这里刻意只钉 [SelfHandMath]:那些规则错了**不报异常**,表现是
 * 「她开了个奇怪的链接」「闹钟设到 25 点」「打开了错的应用」,真机上极难复现。
 * 真正动系统的那部分只能靠真机走一遍(见计划的验证清单)。
 */
class SelfHandTest {

    // ---- 网址白名单(这是安全边界,不是格式检查) ----

    @Test
    fun `http 和 https 放行`() {
        assertEquals("https://a.com/x", SelfHandMath.urlOf("https://a.com/x"))
        assertEquals("http://a.com", SelfHandMath.urlOf("http://a.com"))
    }

    @Test
    fun `光秃秃的域名补上 https`() {
        assertEquals("https://www.bing.com", SelfHandMath.urlOf("www.bing.com"))
        assertEquals("https://example.com/a/b", SelfHandMath.urlOf("example.com/a/b"))
    }

    @Test
    fun `★ file 和 javascript 这类协议一律不开`() {
        // 这个 URL 是**模型临时拼出来的**,给一个「什么都能打开」的入口,
        // 等于给了一条读这台手机上任意文件的路子。
        assertNull(SelfHandMath.urlOf("file:///etc/passwd"))
        assertNull(SelfHandMath.urlOf("file:///sdcard/DCIM/secret.jpg"))
        assertNull(SelfHandMath.urlOf("javascript:alert(1)"))
        assertNull(SelfHandMath.urlOf("content://com.android.contacts/data"))
        assertNull(SelfHandMath.urlOf("data:text/html,<script>x</script>"))
    }

    @Test
    fun `空的和不像网址的都退回去`() {
        assertNull(SelfHandMath.urlOf(""))
        assertNull(SelfHandMath.urlOf("   "))
        assertNull(SelfHandMath.urlOf("打开那个网页"))
        assertNull(SelfHandMath.urlOf("tel:10086"))
    }

    @Test
    fun `搜索走固定模板,关键词是变量`() {
        val u = SelfHandMath.searchUrl("今天天气")
        assertTrue(u.startsWith("https://www.baidu.com/s?wd="))
        assertFalse("关键词必须被编码,不能被拼进 URL 结构里", u.contains("今天天气"))
        // 空格不能原样进 URL(会截断查询词)
        assertFalse(SelfHandMath.searchUrl("a b").contains(" "))
    }

    // ---- 应用名匹配 ----

    private val labels = listOf("微信", "QQ", "QQ音乐", "网易云音乐", "相机", "设置", "信息")

    @Test
    fun `名字一样就直接中`() {
        assertEquals("微信", SelfHandMath.matchLabel("微信", labels))
    }

    @Test
    fun `大小写不算数`() {
        assertEquals("QQ", SelfHandMath.matchLabel("qq", labels))
    }

    @Test
    fun `★ 前缀命中取最短的那个`() {
        // 说「QQ」时命中的有「QQ」和「QQ音乐」—— 该给的是「QQ」。
        // 给成长的那个,用户说「打开QQ」会被打开 QQ音乐,而且**不报错**。
        assertEquals("QQ", SelfHandMath.matchLabel("Q", labels))
    }

    @Test
    fun `★ 分不出来就老实说分不出来`() {
        // 「音乐」在「QQ音乐」和「网易云音乐」里都有 —— 替模型赌一个的代价是
        // **打开了错的应用而它不知道**。宁可回 null 让回执把候选列出来。
        assertNull(SelfHandMath.matchLabel("音乐", labels))
    }

    @Test
    fun `空名字和没这个名字都是 null`() {
        assertNull(SelfHandMath.matchLabel("", labels))
        assertNull(SelfHandMath.matchLabel("   ", labels))
        assertNull(SelfHandMath.matchLabel("钉钉", labels))
    }

    // ---- 闹钟 / 倒计时 ----

    @Test
    fun `正常的时分放行`() {
        assertNull(SelfHandMath.clockError("7", "30"))
        assertNull(SelfHandMath.clockError("0", "0"))
        assertNull(SelfHandMath.clockError("23", "59"))
    }

    @Test
    fun `★ 越界的时分必须拦住 —— 系统那边不会拦,它会静静地设个错时间`() {
        assertTrue(SelfHandMath.clockError("25", "0")!!.contains("0~23"))
        assertTrue(SelfHandMath.clockError("-1", "0")!!.contains("0~23"))
        assertTrue(SelfHandMath.clockError("7", "70")!!.contains("0~59"))
        assertTrue(SelfHandMath.clockError("七点", "0")!!.contains("不是一个小时数"))
    }

    @Test
    fun `倒计时必须是个正数,而且不能长过一天`() {
        assertNull(SelfHandMath.timerError("300"))
        assertTrue(SelfHandMath.timerError("0")!!.contains("大于"))
        assertTrue(SelfHandMath.timerError("-5")!!.contains("大于"))
        assertTrue(SelfHandMath.timerError("90000")!!.contains("24 小时"))
        assertTrue(SelfHandMath.timerError("五分钟")!!.contains("不是一个秒数"))
    }

    @Test
    fun `秒数翻成人话`() {
        assertEquals("5 分钟", SelfHandMath.humanSeconds("300"))
        assertEquals("1 小时", SelfHandMath.humanSeconds("3600"))
        assertEquals("2 小时", SelfHandMath.humanSeconds("7200"))
        assertEquals("1 小时 1 分钟", SelfHandMath.humanSeconds("3660"))
        assertEquals("1 分 30 秒", SelfHandMath.humanSeconds("90"))
        assertEquals("45 秒", SelfHandMath.humanSeconds("45"))
    }

    // ---- 设置页 ----

    @Test
    fun `认得的设置页各给各的 action`() {
        val wifi = SelfHandMath.settingsAction("wifi")
        val bt = SelfHandMath.settingsAction("bluetooth")
        assertTrue(wifi.isNotEmpty())
        assertTrue(bt.isNotEmpty())
        // ★ 这一条防的是复制粘贴:十几个分支要是都写成同一个 action,
        // 界面上看着「有反应」,只是永远开错页 —— 排查起来毫无线索。
        assertNotEquals(wifi, bt)
        assertEquals(wifi, SelfHandMath.settingsAction("WIFI"))
        assertEquals(wifi, SelfHandMath.settingsAction("  wifi  "))
    }

    @Test
    fun `★ 不认识的设置页不猜,退回空串`() {
        // 猜错页比开到首页更糟:用户按「电池」进去看到网络设置,会以为手机坏了。
        assertEquals("", SelfHandMath.settingsAction("固件更新"))
        assertEquals("", SelfHandMath.settingsAction(""))
    }

    // ---- 震动时长 ----

    @Test
    fun `震动时长夹在能感觉到的范围里`() {
        assertEquals(200L, SelfHandMath.vibrateMs("200"))
        assertEquals("5 毫秒连震感都没有,她会以为成功了", 50L, SelfHandMath.vibrateMs("5"))
        assertEquals("一分钟的长震用户会以为手机坏了", 2000L, SelfHandMath.vibrateMs("60000"))
        assertEquals("给了个不是数字的,用默认值", 200L, SelfHandMath.vibrateMs("久一点"))
    }

    // ---- 工具表(自述的那一半) ----

    @Test
    fun `工具名不重复`() {
        val names = SelfHand.tools.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `★ 手机上那只手不许有叫 open_app 的工具`() {
        // 电脑那只手上也有个 open_app。重名的话用户的「打开微信」会在两条路之间
        // 随机走,而且**走错了不报错**(真打开了,只是在另一台机器上)。
        assertFalse(SelfHand.has("open_app"))
        assertTrue(SelfHand.has("open_phone_app"))
    }

    @Test
    fun `每个工具都有说明,每个参数都有说明`() {
        for (t in SelfHand.tools) {
            assertTrue("${t.name} 没有描述", t.desc.isNotBlank())
            for (p in t.params) {
                assertTrue("${t.name}.${p.name} 没有描述", p.desc.isNotBlank())
            }
            // ★ 2026-11-07：「一个参数都不用」现在是真的（task_list 就是），
            //   但**声明了必填就一定要有参数**——那种才是忘了写。
            assertTrue(
                "${t.name} 的 required 里写了参数,但 params 里没有",
                t.required.isEmpty() || t.params.isNotEmpty(),
            )
            // required 里不许出现没声明的参数(拼示例时会拼出个空白)
            for (r in t.required) {
                assertTrue("${t.name} 把没声明的参数 $r 写成了必填", t.params.any { it.name == r })
            }
        }
    }

    @Test
    fun `★ 每个必填参数都得在样例表里有真值`() {
        // 这条是**逼人回来补样例**的那道闸:回执里的「照抄例子」是靠 SAMPLES 拼的,
        // 缺一个的话那一行就是 {"count":""} —— 而 4B 会照抄这个空串。
        for (t in SelfHand.tools) {
            for (r in t.required) {
                val s = HandMath.SAMPLES[r]
                assertTrue("${t.name} 的必填参数 `$r` 在 HandMath.SAMPLES 里没有样例", !s.isNullOrBlank())
            }
        }
    }

    @Test
    fun `★ 这只手声明了「不占资源」,而不是没声明`() {
        // 没声明 = 独占(fail-safe 的默认)。手机上这些动作互不抢东西,
        // 不声明的后果是两件本来能同时干的事白白排成一队 —— 慢,而且看不出原因。
        val h = SelfHand.hand(0L)
        for (t in SelfHand.tools) {
            assertTrue("${t.name} 没声明 locks", h.locks.containsKey(t.name))
            assertEquals("${t.name} 在手机上不该占任何资源", emptyList<String>(), h.locks[t.name])
        }
    }

    @Test
    fun `这只手自述的形状对`() {
        val h = SelfHand.hand(1234L)
        assertEquals(SelfHand.ID, h.id)
        assertEquals(HandMath.KIND_SELF, h.kind)
        assertEquals("self 这个名字会被模型照抄,改它要同步改 SYSTEM_PROMPT 里的例子",
            "self", h.id)
        assertEquals(1234L, h.fetchedAtMs)
        assertTrue("这只手不该藏任何工具 —— 藏起来的工具出现在菜单上,模型就会去点它然后被拒",
            h.hidden.isEmpty())
        assertEquals(emptyList<String>(), h.hidden)
    }

    @Test
    fun `★ 每个工具都在分级表里点过名，不许掉进默认的红`() {
        // 漏填不会报错：那个工具每次调用都会变成「要他点一次确认」，而她做的
        // 只是记一条日程、做一份文件 —— 反复弹框会让他学会无脑点「允许」。
        // ★ 例外是**故意**红的：定一条定时任务 = 以后每天在没人看着的时候花他的云端额度。
        //   要加进来得在这一行写明理由，不能顺手把上面的断言删掉。
        val intentionallyRed = setOf("schedule_task")
        val h = SelfHand.hand(0L)
        for (t in SelfHand.tools) {
            if (t.name in intentionallyRed) continue
            assertNotEquals(
                "${t.name} 没在 RiskGrade.selfLevel 里点名",
                RiskLevel.RED, RiskGrade.level(h, t.name),
            )
        }
    }

    @Test
    fun `★ 她点不了手机自己的屏幕,所以没有这类工具`() {
        // 不是忘了,是**没有就是没有**(要 AccessibilityService,是另一轮的事)。
        // 这条测试是防止以后有人顺手加一个假的进去 —— 一个永远失败的工具
        // 会让模型反复重试,而它每次都「看起来很合理」。
        for (n in listOf("click", "tap", "tap_screen", "click_screen", "swipe", "input_text")) {
            assertFalse("手机上那个手不该有 $n:她够不着手机自己的屏幕", SelfHand.has(n))
        }
    }
}
