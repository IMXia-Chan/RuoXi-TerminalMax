package com.example.touchpad

import android.content.Context
import java.io.File

/**
 * **每一步的完整进出** —— 调试多 agent 时唯一能回答「它到底看到了什么」的东西。
 *
 * ---
 *
 * ## 为什么事件流不够
 *
 * [AgentBus] 存的是**结论**：派给了谁、成没成、一句话摘要。它回答「发生了什么」，
 * 回答不了「**它为什么这么答**」。2026-11-04 他原话:「多agent协同是黑盒！没法调试！」
 *
 * 黑的具体形态是：递给云端的那段 `delegateBrief` 是在内存里拼出来的，里面压进了
 * 这一轮的历史、角色说明、约束 —— 发完就扔。出了问题（答偏了、编了数字），
 * 回头只能看到一句「派给「算」」，没有任何可查的输入。
 *
 * 所以这里存的不是摘要，是**原文**：那次调用真正发出去的 body、那个工具真正拿到的参数和
 * 真正回来的结果。
 *
 * ## ★ 存在本机，明文，和事件流同一待遇
 *
 * 这份东西里可能有号码、有屏幕文字。它**不出门**：没有上传、不进日志、不进事件流。
 * 脱敏只发生在真正发出去的那一份（见 [AgentMath.forCloud]）——
 * 这里存的反而是**发出去之后**的那一份，因为调试要对的正是「它看到的」。
 * （要还原成原值，`files/aliases/aliases.txt` 里那张表还在本地。）
 *
 * ## 一个事件一个文件
 *
 * `files/steps/<事件编号>.txt`。不塞进事件流那一行：一条 body 动辄几千字，
 * 塞进去会让账本每行都变长，而账本是按行扫的（[AgentBus.of] 会整份读）。
 * 分开存还带来一个好处：**坏一个文件只丢那一步**。
 */
internal object StepStore {

    /** 最多留多少个文件。一次任务几十步，200 步 ≈ 最近几次协同。 */
    private const val MAX_FILES = 200

    /** 单个文件的上限。超过就从尾巴截 —— 头部是输入，最有用。 */
    private const val MAX_BODY = 12_000

    private var dir: File? = null

    fun init(ctx: Context) {
        if (dir != null) return
        dir = try {
            File(ctx.filesDir, "steps").apply { mkdirs() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 存一步。
     *
     * @param header 一行抬头（角色、性质），给翻文件的人看，也省得自己拼。
     */
    fun save(id: Long, header: String, body: String) {
        val d = dir ?: return
        try {
            val text = if (body.length > MAX_BODY) {
                body.take(MAX_BODY) + "\n…（共 ${body.length} 字，后面截掉）"
            } else body
            File(d, "$id.txt").writeText("#$id $header\n$text\n")
            prune(d)
        } catch (_: Exception) {
        }
    }

    /** 读回来。没存过就 null —— 调用方退回只显示事件本身。 */
    fun read(id: Long): String? {
        val d = dir ?: return null
        return try {
            val f = File(d, "$id.txt")
            if (!f.exists()) null else f.readText()
        } catch (_: Exception) {
            null
        }
    }

    fun count(): Int = dir?.listFiles()?.size ?: 0

    /**
     * 删掉某一步的全文。**只给「彻底删掉一件活」用**。
     *
     * ★ 步骤文件是按**事件编号**命名的（`<id>.txt`），不是按任务号 —— 所以调用方
     *   必须先把那件活的事件编号捞出来（`AgentBus.of(task)`），再一个一个删。
     *   只删活不删这些，盘上就留下一堆谁也指不到的大段正文。
     */
    fun remove(id: Long): Boolean {
        val d = dir ?: return false
        return try {
            File(d, "$id.txt").delete()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 按编号留下最新的 [MAX_FILES] 个。
     *
     * ★ 按**数字**排序，不按文件名 —— 字符串排序下 `10` 会排在 `9` 前面，
     *   删掉的正好是最近那几步。
     */
    private fun prune(d: File) {
        val files = d.listFiles() ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.nameWithoutExtension.toLongOrNull() ?: Long.MIN_VALUE }
            .take(files.size - MAX_FILES)
            .forEach { try { it.delete() } catch (_: Exception) {} }
    }
}
