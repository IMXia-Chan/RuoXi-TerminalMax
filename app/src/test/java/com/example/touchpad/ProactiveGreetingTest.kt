package com.example.touchpad

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「她该不该现在开口」的纯判定测试。
 *
 * 为什么值得钉:这层判错的两种表现**都要等半小时才看得出来** ——
 * 一种是她变成了每五分钟一句的骚扰,一种是她再也不说话了。
 * 真机上没法快速迭代,只能在纯 JVM 上把每条边界钉死。
 */
class ProactiveGreetingTest {

    /** 全绿的基准场景:开关开着、有界面、她不忙、他安静了 30 分钟。 */
    private fun fire(
        enabled: Boolean = true,
        quiet: Boolean = false,
        visible: Boolean = true,
        busy: Boolean = false,
        nowMs: Long = 10 * 3600_000L,
        lastUserAtMs: Long = 10 * 3600_000L - 30 * 60_000L,
        lastGreetAtMs: Long = 0L,
        unanswered: Int = 0,
    ) = GreetMath.shouldFire(
        enabled = enabled, quiet = quiet, visible = visible, busy = busy, nowMs = nowMs,
        lastUserAtMs = lastUserAtMs, lastGreetAtMs = lastGreetAtMs, unanswered = unanswered,
        idleMs = ProactiveGreeting.IDLE_MS,
        cooldownMs = ProactiveGreeting.COOLDOWN_MS,
        maxUnanswered = ProactiveGreeting.MAX_UNANSWERED,
    )

    @Test
    fun `该开口的场景`() {
        assertTrue("这些都满足,她就该说一句", fire())
    }

    @Test
    fun `开关关着就不说`() {
        assertFalse(fire(enabled = false))
    }

    @Test
    fun `免打扰时段里她主动不说话`() {
        // ★ 只挡**主动开口**。「你找她」那条路走的是 AiAgent 的对话入口,
        //   根本不经过这里 —— 「她不理我」是这个功能唯一能造成的严重 bug,
        //   而且它看起来像「她不高兴了」,不像 bug。所以这条边界钉在判定里,不是写在注释里。
        assertFalse("别的条件全绿,只要在免打扰里就不开口", fire(quiet = true))
        assertTrue("同一时刻、免打扰关掉——她就可以开口(证明上面那条不是因为别的原因)", fire())
    }

    @Test
    fun `看不见她就不说`() {
        // 「她只在你看得见她的时候开口」—— 后台自言自语既做不到也很吓人
        assertFalse(fire(visible = false))
    }

    @Test
    fun `她在干活就不插嘴`() {
        assertFalse(fire(busy = true))
    }

    @Test
    fun `一句话都没聊过就不说`() {
        // lastUserAtMs = 0 表示这次进程里他压根没开过口 —— 谈不上「想念」
        assertFalse(fire(lastUserAtMs = 0L))
    }

    @Test
    fun `刚聊完不急着说`() {
        assertFalse("他 5 分钟前还在说话", fire(lastUserAtMs = 10 * 3600_000L - 5 * 60_000L))
        assertFalse("差一秒到 20 分钟", fire(lastUserAtMs = 10 * 3600_000L - ProactiveGreeting.IDLE_MS + 1))
        assertTrue("正好 20 分钟就可以", fire(lastUserAtMs = 10 * 3600_000L - ProactiveGreeting.IDLE_MS))
    }

    @Test
    fun `两次开口之间要冷却`() {
        val justGreeted = 10 * 3600_000L - 10 * 60_000L          // 10 分钟前刚说过
        assertFalse("刚说过 10 分钟,得憋住", fire(lastGreetAtMs = justGreeted))
        assertTrue(
            "过了 30 分钟冷却就又可以了",
            fire(lastGreetAtMs = 10 * 3600_000L - ProactiveGreeting.COOLDOWN_MS)
        )
    }

    @Test
    fun `连着没人应就闭嘴`() {
        assertTrue("两条没人理还能再说一句", fire(unanswered = ProactiveGreeting.MAX_UNANSWERED - 1))
        assertFalse("三条没人理就该闭嘴了", fire(unanswered = ProactiveGreeting.MAX_UNANSWERED))
        assertFalse(fire(unanswered = 99))
    }

    @Test
    fun `冷却优先于安静_而不是反过来`() {
        // 他安静了一整天,但她十分钟前刚开口过一次 —— 还是要憋住
        assertFalse(
            fire(
                lastUserAtMs = 10 * 3600_000L - 24 * 3600_000L,
                lastGreetAtMs = 10 * 3600_000L - 10 * 60_000L,
            )
        )
    }
}
