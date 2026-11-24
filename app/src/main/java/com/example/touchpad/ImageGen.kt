package com.example.touchpad

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * **文字 → 图片**，走他自己填在「模型清单」里的那一家（能干什么写「生图」）。
 *
 * ## 为什么不让本机算
 *
 * 这台手机跑得动的只有 4B 文本模型和一个小视觉模型；画一张图要的是扩散模型，
 * 显存和算力都不是这个量级。硬塞进手机的结果是「等十分钟出一张糊的」，比不做更糟。
 *
 * ## 为什么必须是他自己的 key
 *
 * 他定过：**免费不倒贴**。所以这里没有内置的服务端，也没有写死的 key ——
 * 没配就是没配，回一句人话让他去设置里加一行。
 *
 * ## 只说一句「画个猫」是不行的
 *
 * 这条路和 [AiAgent.lookAt] 的方向相反：那个是**图进文字出**，这个是**文字进图出**。
 * 所以提示词里不该带「照片」这种本机才有的东西 —— 能上传的只有他打出来的字。
 */
object ImageGen {

    data class Out(
        val ok: Boolean,
        val bytes: ByteArray? = null,
        /** 落盘用的扩展名（`png` / `jpg`）。 */
        val ext: String = "png",
        val model: String = "",
        val error: String = "",
    )

    /** 画图比聊天慢得多（云端排队 + 推理），但也不是无限久。 */
    private const val TIMEOUT_MS = 180_000

    /** 允许的尺寸写法。**白名单**：非法值让服务端 400，而他只会看到「失败了」。 */
    private val SIZE_RE = Regex("^\\d{3,4}x\\d{3,4}$")

    fun generate(
        cfg: AiAgent.Config,
        prompt: String,
        size: String = "1024x1024",
        onWait: ((String) -> Unit)? = null,
    ): Out {
        val p = prompt.trim()
        if (p.isEmpty()) return Out(false, error = "没说要画什么。")
        val ep = RouteMath.pick(cfg.endpoints, RouteMath.IMAGE)
            ?: return Out(
                false,
                error = "现在没有能画图的模型。去 设置 → AI 设置 → 模型清单 里加一行，" +
                    "「能干什么」那一列写「生图」，填上地址、模型名和自己的 key。",
            )
        val sz = size.trim().let { if (SIZE_RE.matches(it)) it else "1024x1024" }

        onWait?.invoke("  正在画（云端出图，一般要十几秒到一分钟）…")

        val body = JSONObject()
            .put("model", ep.model)
            .put("prompt", p)
            .put("n", 1)
            .put("size", sz)
            // ★ 优先要 base64：给 URL 的话还得再下一次，而那个 URL 常常有有效期。
            .put("response_format", "b64_json")

        val text = try {
            post(generationUrl(ep.baseUrl), ep.apiKey, body.toString())
        } catch (e: Exception) {
            return Out(false, model = ep.model, error = "连不上画图服务（${e.javaClass.simpleName}：${e.message ?: "没说原因"}）。")
        }

        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return Out(false, model = ep.model, error = "画图服务回的不是 JSON：${text.take(200)}")
        }
        json.optJSONObject("error")?.let {
            return Out(false, model = ep.model, error = "画图服务说：${it.optString("message").take(200)}")
        }
        val item = json.optJSONArray("data")?.optJSONObject(0)
            ?: return Out(false, model = ep.model, error = "画图服务没给图片（返回里没有 data）。")

        val b64 = item.optString("b64_json").trim()
        if (b64.isNotEmpty()) {
            val bytes = try {
                android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            } catch (_: Exception) {
                return Out(false, model = ep.model, error = "拿到的图解不开（base64 坏了）。")
            }
            return finish(bytes, ep.model)
        }
        val url = item.optString("url").trim()
        if (url.startsWith("http")) {
            val bytes = try {
                get(url)
            } catch (e: Exception) {
                return Out(false, model = ep.model, error = "图片下载失败（${e.javaClass.simpleName}）。")
            }
            return finish(bytes, ep.model)
        }
        return Out(false, model = ep.model, error = "画图服务只回了文字，没有图。")
    }

    private fun finish(bytes: ByteArray, model: String): Out {
        if (bytes.isEmpty()) return Out(false, model = model, error = "拿到的图是空的。")
        // 扩展名按**文件头**认，不按服务端说的：有的服务说 png、给的其实是 jpeg，
        // 而文件名说错了，相册里就打不开。
        val ext = if (bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) "jpg" else "png"
        return Out(true, bytes = bytes, ext = ext, model = model)
    }

    /** 地址规则：他填的可能是 `…/v1`、`…/api/v3`，也可能直接是整条路径。 */
    fun generationUrl(base: String): String {
        val b = base.trim().trimEnd('/')
        if (b.endsWith("/images/generations")) return b
        return "$b/images/generations"
    }

    // ---------------------------------------------------------------- HTTP

    private fun post(url: String, key: String, body: String): String {
        val c = CloudNet.conn(url)
        return try {
            c.requestMethod = "POST"
            c.connectTimeout = 20_000
            c.readTimeout = TIMEOUT_MS
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("Accept", "application/json")
            if (key.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $key")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            read(c)
        } finally {
            c.disconnect()
        }
    }

    private fun get(url: String): ByteArray {
        val c = CloudNet.conn(url)
        return try {
            c.requestMethod = "GET"
            c.connectTimeout = 20_000
            c.readTimeout = TIMEOUT_MS
            val code = c.responseCode
            val ins = if (code in 200..299) c.inputStream else c.errorStream
            val out = ByteArrayOutputStream()
            ins?.use { i ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = i.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    if (out.size() > 40 * 1024 * 1024) break
                }
            }
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    private fun read(c: HttpURLConnection): String {
        val code = c.responseCode
        val ins = if (code in 200..299) c.inputStream else c.errorStream
        val s = ins?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        // ★ 非 2xx 也把正文交回去：里面的 `error.message` 才是他真正需要看的那一句，
        //   只回一句「HTTP 401」等于什么都没说。
        return s
    }
}
