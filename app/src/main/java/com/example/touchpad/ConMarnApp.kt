package com.example.touchpad

import android.app.Application
import java.io.File

/**
 * 进程入口。**只做两件事**：把 [CloudNet] 的闸门装上、把崩溃现场留下来。
 *
 * ★ 为什么需要 [CloudNet.install]：总闸要能回答「现在关着没有」，就得读 SharedPreferences，
 *   而读 prefs 得有个 Context。网页搜索、图片生成、OAuth、MCP 那几条路的
 *   函数签名里没有 Context，与其把十个不相关的签名全改一遍，不如在进程起点
 *   放一份 applicationContext。
 *
 * ★★ 2026-11-08(体检 E7)：**从这里起，崩溃不再一点痕迹都不留。**
 *   这台机上 logcat 抓不到本 App（实测），唯一的观测口径是 `/sdcard/Android/media/<包名>/model.log`
 *   —— 而 `model.log` 是**代码自己写的**：进程被未捕获异常打死的那一刻，没有任何一行会落进去。
 *   症状是「它刚才还好好的，然后突然没了」，重启之后什么也查不到。
 *   所以装一个默认异常处理器：先把现场写进**应用私有目录**（外部目录可能不可用，
 *   而私有目录一定在），下一次启动再把它抄进 `model.log`。
 *
 * ★ `onCreate` 里只允许放**毫秒级、不会抛**的东西。这里只赋字段、注册一个回调，
 *   不放任何模型/文件/网络的初始化 —— 那些都有各自的入口，不能借这里偷偷跑。
 *
 * （那「半件事」是 `super.onCreate()`：不调它，系统那套东西起不来。）
 */
class ConMarnApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CloudNet.install(this)
        reportLastCrash()
        installCrashDump()
    }

    /**
     * 装全局未捕获异常处理器。
     *
     * ★★ **绝不吞掉异常**：处理完必须交回系统原来那个（通常是「弹已停止对话框并杀进程」）。
     *   吞掉的后果比崩溃本身更糟 —— 进程挂在半死状态，界面停住不动，
     *   既没有崩溃对话框，也没有日志，而且用户以为它只是卡了。
     */
    private fun installCrashDump() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { writeCrash(t, e) }
            // 交回系统。取不到就自己杀 —— 两条路都得让进程真的结束。
            if (prev != null) {
                prev.uncaughtException(t, e)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    /** 现场落盘。**只用 JDK 的东西** —— 崩溃时任何依赖都可能正是坏掉的那一环。 */
    private fun writeCrash(t: Thread, e: Throwable) {
        val dir = File(filesDir, "crash").apply { mkdirs() }
        val body = buildString {
            append("%tF %tT".format(java.util.Date(), java.util.Date()))
            append(" 线程=").append(t.name)
            append(" 机型=").append(android.os.Build.MODEL)
            append(" 版本=").append(appVersion())
            append('\n')
            append(e.javaClass.name).append(": ").append(e.message).append('\n')
            append(android.util.Log.getStackTraceString(e))
        }
        AtomicWrite.replace(File(dir, LAST_CRASH), body)
    }

    /**
     * 上一次崩溃的现场，抄进 `model.log`（那是我们真正会去读的那一份）。
     *
     * ★ 用「文件的修改时间」去重：同一份现场只抄一次，否则每启动一次就多一坨。
     * ★ `model.log` 写不进去（外部目录没挂上）也不删私有文件 —— 那是唯一的底本了。
     */
    private fun reportLastCrash() {
        try {
            val f = File(File(filesDir, "crash"), LAST_CRASH)
            if (!f.isFile) return
            val stamp = f.lastModified()
            val pref = getSharedPreferences("crash", MODE_PRIVATE)
            if (pref.getLong(KEY_REPORTED, 0L) == stamp) return
            // 只抄前几行：`model.log` 是给人扫的，整份调用栈在那里只会把前后文挤掉。
            val head = runCatching {
                f.readLines(Charsets.UTF_8).take(24).joinToString("\n")
            }.getOrDefault("")
            if (head.isNotBlank()) {
                ModelManager.get(this).trace("上次崩溃的现场（完整记录在 files/crash/$LAST_CRASH）:\n$head")
            }
            pref.edit().putLong(KEY_REPORTED, stamp).apply()
        } catch (_: Throwable) {
            // 报告崩溃这件事自己不许崩
        }
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private companion object {
        const val LAST_CRASH = "last-crash.txt"
        const val KEY_REPORTED = "reported_at"
    }
}
