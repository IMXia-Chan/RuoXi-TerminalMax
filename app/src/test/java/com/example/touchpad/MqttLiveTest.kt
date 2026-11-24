package com.example.touchpad

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * **真的连一台代理**走一遍：连上 → 发一条（QoS 1）→ 收回来。
 *
 * ★ 默认**不跑**（`assumeTrue` 直接跳过），所以平时 `gradle :app:test` 不联网。
 *   只有明确要验货时才跑：
 *
 *   ```
 *   gradle :app:test --tests "*MqttLiveTest*" -DmqttLive=1
 *   ```
 *
 * ★ 为什么这条非要有：单测能钉住「报文的字节长什么样」，但**钉不住**
 *   「对面愿不愿意跟我们说话」。QoS 位、报文号位置、变长整数这些如果错了，
 *   本地测试全绿，真机上却是一条沉默的线 —— 和 MQTT 最容易吃的那种错一样。
 *
 * ★ 用的是公共测试代理 broker.emqx.io（EMQ 自家维护，专门给人试的，
 *   消息不落库）。主题里带一个随机后缀，不和别人的消息撞。
 */
class MqttLiveTest {

    private val live: Boolean = System.getProperty("mqttLive") == "1"

    private fun broker() = MqttBroker(
        id = "live",
        label = "公共测试代理",
        host = "broker.emqx.io",
        port = 1883,
    )

    @Test
    fun `连上、发一条、收回来`() {
        assumeTrue("没给 -DmqttLive=1，跳过", live)
        val b = broker()
        val topic = "conmarn/live/" + System.nanoTime()

        val ping = MqttClient.ping(b)
        println("ping: $ping")
        assertTrue("必须先能连上：$ping", ping.startsWith("通了"))

        // ★ 先发一条**保留消息**，再单独订阅。
        //   普通消息发完就没了，订阅晚一步什么都收不到 —— 那样这条用例
        //   测的是「时序对不对」，不是「消息走得通吗」。
        val sent = MqttClient.publish(b, topic, "hello-conmarn", qos = 1, retain = true)
        println("publish: $sent")
        assertTrue("发必须成功：$sent", sent.startsWith("已发送"))

        val got = MqttClient.collect(b, topic, seconds = 8)
        println("collect: $got")
        assertTrue("订阅之后应当立刻拿到那条保留消息", got.any { it.second == "hello-conmarn" })

        // 收尾：把保留消息清掉，别在公共代理上留东西。
        // 空正文 + retain = 删除该主题下的保留消息（MQTT 3.1.1 的约定）。
        MqttClient.publish(b, topic, "", qos = 1, retain = true)
    }

    @Test
    fun `签名错误的代理要给出人话`() {
        assumeTrue("没给 -DmqttLive=1，跳过", live)
        val b = MqttBroker(
            id = "live-bad",
            label = "写错的代理",
            host = "127.0.0.1",
            port = 65000,
        )
        val r = MqttClient.ping(b)
        println("wrong-port: $r")
        assertTrue("不能只说「失败」：$r", r.startsWith("连不上"))
        assertTrue("要说得出一句话：$r", r.length > 5)
    }
}
