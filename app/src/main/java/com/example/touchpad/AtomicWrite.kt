package com.example.touchpad

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 原子地整体换掉一个小文件。→ 成功 true。
 *
 * ★★ 2026-11-08（体检）：
 *   「先写 `.tmp` 再 rename」这个项目抄了二十几处，其中两处抄歪了：
 *   · [AgentMemory] 在 rename 失败时**先删旧文件再重试** —— 第二次再失败，
 *     上一份也没了；
 *   · [ResearchStore] 的裁剪、[WakeEnroll] 的保存是**直接 `writeText` 重写** ——
 *     写到一半被杀，留下的是半截文件。
 *
 *   半截 JSON 读的时候会抛，而读的一方几乎都带 `catch` —— 于是症状是
 *   「她突然不记得了 / 唤醒词没了」，**一句报错都没有**。这正是这个项目最怕的那类失败。
 *
 * ★ 用 `Files.move(REPLACE_EXISTING + ATOMIC_MOVE)`：在同一个目录里换名是原子操作，
 *   读者要么看到旧的完整文件，要么看到新的完整文件，看不到中间态。
 *   极少数文件系统不认 ATOMIC_MOVE，退回普通替换 —— 仍然是「新文件先写好」。
 *
 * ★ 只给**小文件**用（几 MB 以内的 JSON / 文本）。大文件走这条要多占一份磁盘。
 */
internal object AtomicWrite {

    /** 全局串行：两个线程同时换同一个文件时，别让它们互相踩 `.tmp`。 */
    private val lock = Any()

    fun replace(f: File, text: String): Boolean = synchronized(lock) {
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            try {
                Files.move(
                    tmp.toPath(), f.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: Exception) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
