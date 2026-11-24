package com.example.touchpad

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File

/**
 * **成品以「一个应用」的样子跑起来**：全屏、没有地址栏、返回键退出。
 *
 * 他 2026-11-06 的原话：「返回了成品，但是就一个界面，不能真正的生成在手机真正的桌面上的应用」。
 *
 * ★ 先说清楚**做不到的那一半**：手机里没有 JDK / aapt2 / d8，沙盒编不出 APK ——
 *   这件事不是「还没做」，是**在手机上做不了**（真编也装不上：没有任何签名与安装权限）。
 * ★ **做得到的那一半**：代码档做出来的东西都是网页（一个 html + 一点 js），
 *   那就让它以「网页应用」的身份上桌面 —— 桌面图标 → 点开 → 全屏跑，
 *   和别的 App 在**用的人眼里**没有区别，差别只在它不是从应用商店装的。
 *
 * 页面就在工作区里（`files/sandbox/rootfs/root/work/…`）。那个目录第三方文件管理器
 * 进不去（Android 11+ 不让进 `Android/data`），所以**只有本 App 能打开它** ——
 * 这也是为什么入口做在这里，而不是让他去找文件。
 */
class WorkAppActivity : Activity() {

    companion object {
        const val EXTRA_REL = "rel"
        const val EXTRA_TITLE = "title"

        /** 打开工作区里的一个文件。`rel` 是相对 `/root/work` 的路径。 */
        fun intent(ctx: Context, rel: String, title: String = rel): Intent =
            Intent(ctx, WorkAppActivity::class.java)
                .putExtra(EXTRA_REL, rel)
                .putExtra(EXTRA_TITLE, title)

        /**
         * 往桌面上放一个图标。返回 false = 这台桌面不支持（或被系统关掉了）。
         *
         * ★ 走的是系统的「固定快捷方式」（`requestPinShortcut`）：**桌面会弹一次确认**，
         *   他点了才上桌面 —— 不是我们能偷偷往他桌面塞图标的。
         * ★ 同一个文件反复放同名 id 的图标不会堆出一排，系统会覆盖原来那个。
         */
        fun pin(ctx: Context, rel: String, title: String): Boolean {
            val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return false
            if (!sm.isRequestPinShortcutSupported) return false
            return try {
                val si = ShortcutInfo.Builder(ctx, "work-" + rel.hashCode().toUInt())
                    .setShortLabel(short(title))
                    .setLongLabel(title.take(25))
                    .setIcon(Icon.createWithResource(ctx, R.mipmap.ic_conmarn))
                    .setIntent(
                        intent(ctx, rel, title).setAction(Intent.ACTION_VIEW)
                    )
                    .build()
                sm.requestPinShortcut(si, null)
            } catch (e: Exception) {
                false
            }
        }

        /** 桌面图标下面那行字很短：多数桌面只留得下 5~6 个字。 */
        private fun short(title: String): String {
            val t = title.substringAfterLast('/').substringBeforeLast('.').ifBlank { title }
            return if (t.length <= 6) t else t.take(6)
        }
    }

    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ★ 路径只认 WorkCodeMath.rel 归一化过的相对路径 —— 外面塞个 `../../` 进来也出不去工作区。
        val rel = WorkCodeMath.rel(intent.getStringExtra(EXTRA_REL).orEmpty())
        if (rel == null) {
            finish()
            return
        }
        val f = File(File(Sandbox.rootfs(this), "root/work"), rel)
        if (!f.isFile) {
            finish()
            return
        }
        val w = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            // ★ 生成的应用常把状态存在 localStorage 里（闹钟的开关、计时器）——
            //   不开这个，「关掉再打开就全没了」。
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            // ★ 单文件页面里 import 一个同目录的 js/css、或者 fetch 一份数据，都走 file://。
            //   只对**这个应用自己的私有目录**放开：这里的文件全部是代码档自己写出来的。
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = true
            // ★★ 2026-11-08：`allowUniversalAccessFromFileURLs` 的意思是「页面里的 fetch
            //   想连谁就连谁」—— 它走 WebView 自己的网络栈，**不经过 App 的 Java 层**，
            //   所以云端总闸本来拦不住它。这里把每一笔请求都过一遍闸门：
            //   闸关着时，公网请求直接回 403，一个字节都不发出去。
            //   （`file://` 和它引入的同目录 js/css 不受影响 —— 那是这个应用自己的文件。）
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    req: WebResourceRequest,
                ): WebResourceResponse? {
                    val u = req.url ?: return null
                    val scheme = u.scheme?.lowercase()
                    if (scheme != "http" && scheme != "https" &&
                        scheme != "ws" && scheme != "wss"
                    ) return null
                    if (CloudNet.allow(u.host)) return null
                    return WebResourceResponse(
                        "text/plain", "utf-8", 403, "Cloud Off",
                        mapOf("Cache-Control" to "no-store"),
                        java.io.ByteArrayInputStream("云端总闸关着".toByteArray(Charsets.UTF_8)),
                    )
                }
            }
            loadUrl("file://" + f.absolutePath)
        }
        web = w
        setContentView(
            w,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /** 返回键：页面里退过就退页面，退到头就退出。别一按就把整个应用关掉。 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val w = web
            if (w != null && w.canGoBack()) {
                w.goBack()
                return true
            }
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        // ★ WebView 不 destroy 会一直挂着它自己的线程和音频/计时器 —— 闹钟页面尤其明显：
        //   退出了还在响。
        web?.destroy()
        web = null
        super.onDestroy()
    }
}
