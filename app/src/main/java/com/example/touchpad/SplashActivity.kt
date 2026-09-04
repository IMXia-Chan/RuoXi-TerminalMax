package com.example.touchpad

import android.app.Activity
import android.content.Intent
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 若息·Terminal Max —— Android 开屏 Activity。
 *
 * 纯黑沉浸式(隐藏状态栏+导航栏,WindowInsetsControllerCompat,兼容 API 26+;
 * minSdk=30 天然满足),整屏放 [SplashView] 两幕:先 RuoXi 品牌字,再 XChan OS 光带点亮
 * (共约 4.5s)。
 *
 * 音效:res/raw/splash_chime.mp3 一声晶体共鸣(占位),用 SoundPool 低延迟播放;
 * 等 SoundPool 加载完成(OnLoadCompleteListener)才开始动画 —— 文字被点亮那一刻
 * (SplashView.onLit)触发,只播一次、不循环。音量取系统媒体音量的 50%(宁轻勿响),
 * AudioAttributes USAGE_MEDIA=STREAM_MUSIC,静音模式(响铃/振动关)下媒体音量不受影响仍播放。
 * 动画结束跳 MainActivity 时 release;onDestroy 兜底停止并释放。
 */
class SplashActivity : Activity() {

    private var splash: SplashView? = null

    private var soundPool: SoundPool? = null
    private var chimeId = 0          // 加载好的 sample id
    private var chimePlayId = 0      // 最近一次 play 返回的流 id
    private var soundReady = false   // 音效就绪后动画才开播

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 沉浸式:内容延伸到系统栏底下,再隐藏状态栏+导航栏
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE // 下拉仍可临时唤出
        }

        val view = SplashView(this)
        setContentView(view)
        splash = view

        // 文字点亮瞬间 → 播音效(此时光带盖满文字、银白闪光处)
        view.onLit = { playChime() }

        setupChimeThenStart(view)
    }

    /**
     * 建 SoundPool(STREAM_MUSIC/媒体音量流),加载 splash_chime;加载完成才开播动画。
     * 若加载失败(status!=0)或回调没来(2.5s 兜底),动画照常播(只是没声)。
     */
    private fun setupChimeThenStart(view: SplashView) {
        val sp = SoundPool.Builder()
            .setMaxStreams(1) // 只这一声,不需要同时多路
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)      // = 媒体音量流(STREAM_MUSIC)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .build()
        soundPool = sp

        sp.setOnLoadCompleteListener { _, sampleId, status ->
            if (sampleId == chimeId && status == 0) {
                soundReady = true
                startSplashAnimation(view)
            } else if (sampleId == chimeId) {
                // 加载失败:没声也照常开播,别让黑屏卡死
                startSplashAnimation(view)
            }
        }
        chimeId = sp.load(this, R.raw.splash_chime, 1)

        // 兜底:万一 OnLoadComplete 一直不来(极少数机型),也别让开屏干等
        view.postDelayed({ startSplashAnimation(view) }, 2500L)
    }

    /** 等音效就绪、首帧布局完成后再开播(约 3s 后 goMain)。 */
    private fun startSplashAnimation(view: SplashView) {
        if (isFinishing || isDestroyed) return
        view.post {
            if (isFinishing || isDestroyed) return@post
            if (view.animatorStarted) return@post // 已开播就不再重复
            view.start { goMain() }
        }
    }

    /** 点亮瞬间:50% 媒体音量、播一次、不循环。 */
    private fun playChime() {
        if (!soundReady || chimeId == 0) return
        val sp = soundPool ?: return
        chimePlayId = sp.play(chimeId, 0.5f, 0.5f, 1, 0, 1.0f)
    }

    /** 跳转前释放 SoundPool(停掉可能仍在播的尾音)。 */
    private fun releaseSound() {
        val sp = soundPool ?: return
        sp.stop(chimePlayId)
        sp.release()
        soundPool = null
        soundReady = false
    }

    /** 淡出后跳主界面:进入动画 0(主界面已在底下),本页退出用 fade_out 渐变。 */
    private fun goMain() {
        if (isFinishing) return
        releaseSound() // 跳转时释放音效
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        overridePendingTransition(0, R.anim.fade_out)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 焦点变化可能让系统栏短暂冒出,再压一次(沉浸式)
        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onDestroy() {
        splash?.stop()
        splash = null
        releaseSound() // 用户快速退出:停音效并释放(已 release 过则 no-op)
        super.onDestroy()
    }
}
