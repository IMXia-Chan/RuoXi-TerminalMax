package com.example.touchpad

import android.content.Context
import org.json.JSONArray

/**
 * 进程级唯一的 [AiAgent]。
 *
 * ★ 为什么必须单例:AI 工具回执走的是 **一条** TouchpadClient 通道,Listener.onAiReply
 * 只有一个 —— 谁持着这个 listener,谁就得把回执交给**同一个** AiAgent。以前只有
 * 主界面一个入口,字段藏在 MainActivity 里没这个问题;现在 OI助手 / ConMarn 是
 * 独立的 Activity,各建一个实例的话,它们的 callPc 永远等不到回执(全部超时),
 * 症状还是「每一步都卡 25 秒然后报错」—— 最难排查的那种坏。
 *
 * 三个入口的分工:
 *  - 主界面老对话框:一次一句(它的语义就是「单条指令」,不接历史);
 *  - ConMarn Activity:**多轮** —— 同一个人格,聊天和干活都从这儿进出。
 * 历史在这里初始化成空数组 —— Activity 销毁重建也不丢,她跨界面记得你。
 *
 * 钩子接线从 MainActivity.ai() 原样搬来(那是「眼按需拉、脑死了重拉」的唯一实现),
 * ModelManager 是进程级单例,拿 applicationContext 取即可,和原来语义一致。
 */
object AiAgentHolder {

    @Volatile private var agent: AiAgent? = null

    /** 取(可能建)。所有入口都用它,保证全进程只有一个实例。 */
    fun get(ctx: Context): AiAgent = agent ?: synchronized(this) {
        agent ?: AiAgent(TouchpadClient.get(ctx)).also {
            it.config = AiAgent.loadConfig(ctx)
            // 「眼」是按需加载的:click_element 之前先让 ModelManager 把它拉起来。
            // 不接这个钩子的话,visionBaseUrl 会直接打到一个没在监听的端口上,
            // 表现为「连不上模型服务」—— click_element 就整个是死的。
            val app = ctx.applicationContext
            it.ensureVision = {
                val mm = ModelManager.get(app)
                val t0 = System.currentTimeMillis()
                val err = mm.ensureEye()
                mm.trace("ensureEye → " + (err ?: "就绪") +
                        " (${System.currentTimeMillis() - t0}ms)")
                err
            }
            // 「脑」死了就把它拉起来(只在连接被拒时会被叫到,幂等)。见 AiAgent.ensureBrain。
            it.ensureBrain = {
                val mm = ModelManager.get(app)
                mm.trace("脑没了(连接被拒),按需重新拉起…")
                mm.start()      // 幂等:正在跑/正在起都会直接返回
            }
            // 开发者日志出口。★ 必须是 ModelManager.trace 而不是 Log:
            // 实测 logcat 抓不到本 App,写 Log 等于写进黑洞。
            it.trace = { msg -> ModelManager.get(app).trace(msg) }
            // 前缀快照(槽位存盘)要用的「外面的事」:存哪儿、脑的模型文件多大。
            //
            // ⚠️ 模型**字节数**要进哈希 —— 同名换个量化就是另一份权重,拿旧 KV 说话
            //    的症状是「她答得怪怪的」,而没人会想到去怀疑盘上一个文件。
            //    用字节数不用 mtime:重新 push 同一个模型时 mtime 会变、内容没变,
            //    用 mtime 会让用户每导一次模型就白扔一次快照。
            //
            // 目录没建出来 / 模型不在 → 回 null,[AiAgent] 那边整条快照路径安静跳过。
            it.snapshotEnv = {
                val mm = ModelManager.get(app)
                val dir = mm.slotsDir()
                val f = mm.modelFile()
                if (dir.isDirectory && f.isFile) {
                    AiAgent.SnapshotEnv(
                        dir = dir,
                        modelName = f.name,
                        modelBytes = f.length(),
                        nCtx = ModelManager.BRAIN_N_CTX,
                        cacheTypes = ModelManager.BRAIN_CACHE,
                    )
                } else null
            }
            // ★ 手注册表(含云端那只手)也在这儿初始化。
            //
            // 为什么不靠各个 Activity:`AiAgent` 自己**从不**调 `HandRegistry.init` ——
            // 它只在 `list_hands`/`use_hand` 里调 `all()`。所以谁把 agent 建出来、
            // 却没先 init,拿到的是「手机自己 + 空」:云端手一只都不在,
            // 而症状是她回一句**听起来很合理的**「我没有这个能力」。不报错。
            // `AiAgentHolder.get()` 是全进程唯一的入口(HerBoot / 两个 Activity /
            // 通知 / 悬浮窗都走它),挂在这儿就没有第二条路能漏过去。
            HandRegistry.init(app)

            // 一份多轮历史:她是一个人(聊天/干活同记忆),见 AiAgent.history 的说明。
            it.history = JSONArray()
            agent = it
        }
    }

    /** 只取不建 —— 给回执路由用(没人用时不该为了转发一条回执把实例建出来)。 */
    fun peek(): AiAgent? = agent
}
