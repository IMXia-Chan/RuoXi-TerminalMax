package com.example.touchpad

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * 浏览器授权完成之后**回到这里**（`conmarn://oauth?code=…&state=…`）。
 *
 * ## 为什么单独开一个 Activity，而不是让她那个 Activity 收
 *
 * `ConMarnActivity` 是 `exported="false"` —— 外面任何应用都拉不起它。
 * 要接一条从浏览器来的地址，就必须把它改成 `exported="true"`，那等于
 * **把整个房间的门拆了**（任何一个 App 都能把她的房间顶到前台）。
 *
 * 所以这里开一个**只做一件事**的门：收下地址 → 换 token → 关掉自己。
 * 它没有任何界面、不显示任何内容、也不接受任何参数，收到的东西只有一条
 * OAuth 回调地址，处理完立刻结束。
 *
 * ## 它必须自己换 token（不能只把地址转交出去）
 *
 * 授权回来那一刻，房间可能**根本没在跑**（用户是在浏览器里点的同意，
 * 中间系统把进程收掉了）。这时候「转交给房间」等于把这件事丢了 ——
 * 而用户看到的只是「同意完了，回到 App，什么都没有」。
 */
class OAuthReturnActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.dataString.orEmpty()
        if (uri.isBlank()) {
            finish()
            return
        }
        // ★ 网络请求不能在主线程：换 token 是一次 POST。放主线程会直接抛
        //   NetworkOnMainThreadException，而那个异常**长得像「授权失败」**。
        Thread {
            val msg = OAuthClient.finish(uri)
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                // 回到她那儿。用 CLEAR_TOP 而不是新建：她是 singleTask，
                // 已经在的话就复用那一个，不会出现第二个房间。
                runCatching {
                    startActivity(
                        Intent(this, ConMarnActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                }
                finish()
            }
        }.start()
    }
}
