package com.example.touchpad

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作分级表。
 *
 * ★ 钉的是**默认那一档**：新加一个工具、忘了在表里填它 —— 它必须落进「红」，
 *   而不是「绿」。这条错了不会有任何报错，只会让一个新的外发动作
 *   悄悄不被记 —— 而台账的全部意义就是「出事能对账」。
 */
class RiskGradeTest {

    private fun hand(
        kind: String,
        tool: String,
        exec: ApiExec? = null,
        sends: List<String> = listOf("text"),
        id: String = "h",
    ) =
        Hand(
            id = id, kind = kind, name = "测试", version = "1", fetchedAtMs = 0,
            tools = listOf(
                HandTool(name = tool, desc = "", params = emptyList(), required = emptyList(),
                    exec = exec, sends = sends)
            ),
        )

    @Test
    fun `手机自己：只读是绿，动设备是黄`() {
        assertEquals(RiskLevel.GREEN, RiskGrade.level(hand(HandMath.KIND_SELF, "say"), "say"))
        assertEquals(RiskLevel.GREEN, RiskGrade.level(hand(HandMath.KIND_SELF, "calendar_list"), "calendar_list"))
        assertEquals(RiskLevel.YELLOW, RiskGrade.level(hand(HandMath.KIND_SELF, "open_phone_app"), "open_phone_app"))
        assertEquals(RiskLevel.YELLOW, RiskGrade.level(hand(HandMath.KIND_SELF, "dial"), "dial"))
    }

    @Test
    fun `表里没有的工具默认是红`() {
        assertEquals(RiskLevel.RED, RiskGrade.level(hand(HandMath.KIND_SELF, "未来新工具"), "未来新工具"))
        assertEquals(RiskLevel.RED, RiskGrade.level(hand(HandMath.KIND_PC, "某个没见过的"), "某个没见过的"))
    }

    @Test
    fun `沙盒是黄`() {
        assertEquals(RiskLevel.YELLOW, RiskGrade.level(hand(HandMath.KIND_SANDBOX, "shell"), "shell"))
    }

    @Test
    fun `MQTT 发是黄，读是绿`() {
        val h = hand(HandMath.KIND_CLOUD, "publish", id = "mqtt:abc")
        assertEquals(RiskLevel.YELLOW, RiskGrade.level(h, "publish"))
        assertEquals(RiskLevel.GREEN,
            RiskGrade.level(hand(HandMath.KIND_CLOUD, "read", id = "mqtt:abc"), "read"))
    }

    @Test
    fun `蓝牙写是黄，扫描和读是绿`() {
        val id = "ble:adapter"
        assertEquals(RiskLevel.YELLOW,
            RiskGrade.level(hand(HandMath.KIND_CLOUD, "write", id = id), "write"))
        assertEquals(RiskLevel.GREEN,
            RiskGrade.level(hand(HandMath.KIND_CLOUD, "scan", id = id), "scan"))
        assertEquals(RiskLevel.GREEN,
            RiskGrade.level(hand(HandMath.KIND_CLOUD, "read", id = id), "read"))
    }

    @Test
    fun `MCP 不知道是干什么的，按红记`() {
        assertEquals(RiskLevel.RED,
            RiskGrade.level(hand(HandMath.KIND_CLOUD, "anything", id = "mcp:srv"), "anything"))
    }

    @Test
    fun `自己加的接口：GET 绿，非 GET 黄，要送图片的直接红`() {
        val get = ApiExec(method = "GET", path = "/x")
        val post = ApiExec(method = "POST", path = "/x")
        // 注意：这三只用的是 KIND_CLOUD，但 id 不是 mqtt:/ble:/mcp:，走接口那条规则。
        fun api(exec: ApiExec, sends: List<String>) = Hand(
            id = "someapi/tool", kind = HandMath.KIND_CLOUD, name = "接口", version = "1",
            fetchedAtMs = 0,
            tools = listOf(
                HandTool(name = "t", desc = "", params = emptyList(), required = emptyList(),
                    exec = exec, sends = sends)
            ),
        )
        assertEquals(RiskLevel.GREEN, RiskGrade.level(api(get, listOf("text")), "t"))
        assertEquals(RiskLevel.YELLOW, RiskGrade.level(api(post, listOf("text")), "t"))
        assertEquals("要送图片 = 红", RiskLevel.RED, RiskGrade.level(api(post, listOf("image")), "t"))
    }

    @Test
    fun `电脑：看屏幕是绿，动手是黄，发出去是红`() {
        val pc = HandMath.KIND_PC
        assertEquals(RiskLevel.GREEN, RiskGrade.level(hand(pc, "screenshot"), "screenshot"))
        assertEquals(RiskLevel.GREEN, RiskGrade.level(hand(pc, "list_windows"), "list_windows"))
        assertEquals(RiskLevel.YELLOW, RiskGrade.level(hand(pc, "click"), "click"))
        assertEquals(RiskLevel.YELLOW, RiskGrade.level(hand(pc, "type"), "type"))
        assertEquals(RiskLevel.RED, RiskGrade.level(hand(pc, "send_message"), "send_message"))
        assertEquals(RiskLevel.RED, RiskGrade.level(hand(pc, "upload_file"), "upload_file"))
    }

    @Test
    fun `台账里抹掉密钥类的值`() {
        val a = JSONObject()
            .put("api_key", "sk-1234567890")
            .put("token", "abc")
            .put("password", "hunter2")
            .put("query", "天气")   // 普通字段要留着，否则台账对不了账
            .put("authorization", "Bearer x")
        val s = RiskGrade.redact(a)
        assertFalse("key 不能出现", s.contains("sk-1234567890"))
        assertFalse("token 不能出现", s.contains("abc"))
        assertFalse("密码不能出现", s.contains("hunter2"))
        assertTrue("普通参数要留着", s.contains("天气"))
        assertTrue(s.contains("***"))
    }

    @Test
    fun `过长的参数会被截断`() {
        val s = RiskGrade.redact(JSONObject().put("text", "字".repeat(500)))
        assertTrue("不该把一整篇文章写进台账", s.length < 300)
    }
}
