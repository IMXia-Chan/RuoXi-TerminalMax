package com.example.touchpad

import android.content.Context
import java.io.File

/**
 * 假名表 —— **只在本机存在**的那一份对照。
 *
 * 纯逻辑在 [AliasMath]（可单测）；这里只做持有、落盘、加锁，和 [HandRegistry] 的分工一样。
 *
 * ## ★ 这一层的价值全在「不出门」
 *
 * 表被读出去一次，假名这一档就一文不值 —— 因为**值全在里面**。
 * 所以这套代码里没有任何「把表发出去」的路：没有导出、没有上传、不进日志、
 * 不进事件流（事件流只记「遮掉了哪几类」，见 [AgentMath]）。
 * 面板上唯一的操作是**清空**。
 *
 * ## 落盘格式
 *
 * `files/aliases/aliases.txt`，一行一条，见 [AliasMath.line]。
 * 不用 JSON：这里只有六个定长字段，坏一行的代价是**那一个假名失效**（而不是整份读不出来）。
 * 整份读不出来 = 所有假名同时失效 = 云端那边所有号码突然变成陌生人，那是最坏的形态。
 */
internal object AliasStore {

    private var file: File? = null
    private val lock = Any()

    /** 内存里那一份。按 alias 索引 —— 两个方向都要 O(1)。 */
    private var byAlias: LinkedHashMap<String, Alias> = LinkedHashMap()

    fun init(ctx: Context) {
        if (file != null) return
        synchronized(lock) {
            if (file != null) return
            try {
                val dir = File(ctx.filesDir, "aliases").apply { mkdirs() }
                val f = File(dir, "aliases.txt")
                file = f
                if (f.exists()) {
                    for (l in f.readLines()) {
                        val a = AliasMath.parse(l) ?: continue
                        byAlias[a.alias] = a
                    }
                }
            } catch (_: Exception) {
                // 目录建不出来：那就没有落盘能力，内存里那份照用（下次重启会再来一轮新假名）。
            }
        }
    }

    /**
     * 这个值该用哪个词。**同一个值永远给同一个词**，这是云端能推理的前提。
     *
     * ★ 找不到就新建，并**当场落盘** —— 只在内存里建的话，重启之后同一个号码
     *   会换个新词，云端眼里的「同一个人」就断了，而且没有任何报错。
     */
    fun aliasFor(kind: String, value: String): String {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            // 先按值找（不管类别 —— 同一个值只该有一个词）
            byAlias.values.firstOrNull { it.value == value }?.let { hit ->
                val upd = hit.copy(lastUsed = now, hits = hit.hits + 1)
                byAlias[upd.alias] = upd
                persist(byAlias.values.toList())
                return upd.alias
            }
            var candidate = AliasMath.baseAlias(kind, value)
            var n = 1
            // ★ 撞了（这个词已经被**别的值**占了）就往后错一位，绝不覆盖别人的词。
            while (byAlias.containsKey(candidate) && byAlias[candidate]?.value != value) {
                n++
                candidate = AliasMath.disambiguate(AliasMath.baseAlias(kind, value), n)
            }
            byAlias[candidate] = Alias(
                alias = candidate, kind = kind, value = value,
                createdAt = now, lastUsed = now, hits = 1,
            )
            val kept = AliasMath.trim(byAlias.values.toList())
            if (kept.size != byAlias.size) {
                byAlias = LinkedHashMap<String, Alias>().apply { kept.forEach { put(it.alias, it) } }
            }
            persist(kept)
            return candidate
        }
    }

    /** 云端回话里出现这个词 → 它是谁。查不到返回 null（原样留着，见 [AliasMath.restore]）。 */
    fun valueOf(alias: String): String? = synchronized(lock) { byAlias[alias]?.value }

    /** 面板上看一眼：现在记着多少个。**不返回内容** —— 这一层不往外吐值。 */
    fun count(): Int = synchronized(lock) { byAlias.size }

    /** 各类别各有多少条（只数，不带值）。自检和面板用。 */
    fun countsByKind(): Map<String, Int> = synchronized(lock) {
        byAlias.values.groupingBy { it.kind }.eachCount()
    }

    /**
     * 只丢掉**一个**词。唯一的调用方是自检（它要真写一次才能验落盘，验完不该留下假数据）。
     *
     * ★ 别在别处用它：表里的每一条都是「云端见过的一个东西」，随手丢一条
     *   就等于让云端记得的那个代号在本地失忆。
     */
    fun forget(alias: String) {
        synchronized(lock) {
            if (byAlias.remove(alias) == null) return
            persist(byAlias.values.toList())
        }
    }

    /**
     * 清空。★ 清掉之后云端那边看到的还是旧假名，而本地已经不认识它们了 ——
     * 所以清空只在「他要彻底断掉这条线」时用，不是日常操作。
     */
    fun clear() {
        synchronized(lock) {
            byAlias = LinkedHashMap()
            try {
                file?.delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun persist(list: List<Alias>) {
        val f = file ?: return
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(list.joinToString("\n", postfix = if (list.isEmpty()) "" else "\n"))
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())   // 偶发 rename 不过，兜一手（同 HandRegistry）
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }
}
