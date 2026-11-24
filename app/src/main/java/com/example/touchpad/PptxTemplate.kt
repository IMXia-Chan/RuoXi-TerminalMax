package com.example.touchpad

import android.content.Context

/**
 * **放映稿的骨架**：母版、版式、主题这三样，原样躺在 `assets/pptx/` 里。
 *
 * ★ 为什么是「借来的骨架」而不是自己写：见 [DocGen.pptx] 的注释 ——
 *   手写的那一套被 PowerPoint 判成「文件已损坏」，而那个错误信息里
 *   没有一个字指出是哪个元素。骨架来自 Microsoft PowerPoint 自己存的空稿，
 *   它认自己的东西。
 *
 * ★ 为什么读一次就缓存：`PptxTemplate` 有 25 个文件、127 KB，
 *   每生成一份放映稿都从 assets 里读一遍，纯属浪费 —— 这些东西**永不变化**。
 */
class PptxTemplate(val parts: Map<String, String>) {

    val masterPart: String?
        get() = parts.keys.firstOrNull { it.startsWith("ppt/slideMasters/") && it.endsWith(".xml") }

    val themePart: String?
        get() = parts.keys.firstOrNull { it.startsWith("ppt/theme/") && it.endsWith(".xml") }

    companion object {
        private const val ROOT = "pptx"

        @Volatile
        private var cached: PptxTemplate? = null

        /** → null = 资源没打进去（构建配错），**不要**在这里自己拼一套手写的顶上。 */
        fun load(ctx: Context): PptxTemplate? {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                val t = runCatching {
                    val parts = LinkedHashMap<String, String>()
                    walk(ctx, ROOT, parts)
                    if (parts.isEmpty()) null else PptxTemplate(parts)
                }.getOrNull()
                cached = t
                return t
            }
        }

        private fun walk(ctx: Context, dir: String, out: MutableMap<String, String>) {
            val names = runCatching { ctx.assets.list(dir) }.getOrNull() ?: return
            for (n in names) {
                val path = "$dir/$n"
                val sub = runCatching { ctx.assets.list(path) }.getOrNull()
                if (!sub.isNullOrEmpty()) {
                    walk(ctx, path, out)
                } else {
                    runCatching {
                        ctx.assets.open(path).use { s ->
                            out[path.removePrefix("$ROOT/")] = s.readBytes().toString(Charsets.UTF_8)
                        }
                    }
                }
            }
        }
    }
}
