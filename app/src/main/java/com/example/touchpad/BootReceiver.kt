package com.example.touchpad

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 手机重启(或她自己被覆盖安装)之后,把她自己接回来。
 *
 * ## 为什么这两个 action
 *
 * - `BOOT_COMPLETED` —— 重启。★ **不用 `LOCKED_BOOT_COMPLETED`**:直接启动完成前,
 *   模型和槽位快照都在 **credential-encrypted** 的 `filesDir` / `getExternalFilesDir` 里,
 *   **解锁前根本读不到**,提前起来只会拿到一堆「文件不存在」。安静等 `BOOT_COMPLETED` 就行。
 * - `MY_PACKAGE_REPLACED` —— 覆盖安装之后也拉一次。**这条对开发期特别重要**:
 *   每次 `adb install -r` 都等于把她重启一遍,没有它就得用户手动开一次 App 才恢复。
 *
 * ## ⚠️ 规范允许/不允许开机拉服务这件事,Android 15+ 是有名单的
 *
 * 那份禁用名单上有 `dataSync` / camera / mediaPlayback / phoneCall / mediaProjection /
 * microphone —— **`specialUse` 不在上面**。这正是 [HerLifeService] 用 specialUse 的原因之一。
 *
 * ## 这里只做最便宜的事
 *
 * 起服务把**进程锚住**就走,并把 [HerLifeService.EXTRA_DEFER] 带过去 ——
 * 装载 2.5GB 的模型要几十秒的重 IO,得等系统自己的开机风暴过去(见那边的说明)。
 *
 * ## 「关得掉」这一条在这里的落点
 *
 * `alive == false` 就直接返回,**连服务都不起**。用户关掉之后开机不会自己回来 ——
 * 而且还有一层系统级的硬关(设置 → 应用管理 → 强制停止),那会把整个包置为 stopped,
 * **连 `BOOT_COMPLETED` 都收不到**。这是最令用户安心的兜底,界面上要把这条路写出来。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }

        val app = context.applicationContext
        val mm = ModelManager.get(app)

        // ★ 系统的闹钟表在重启时**是空的**：定时任务靠 AlarmManager 排，
        //   不在这里补一遍，重启之后它们就再也不响了（而且界面上还写着「每天 08:00」）。
        //   这一步不依赖「她一直在」的开关 —— 那是她的常驻，和定时任务是两回事。
        try {
            val n = TaskAlarm.rescheduleAll(app)
            if (n > 0) mm.trace("开机广播(${intent.action}):重排了 $n 条定时任务")
        } catch (e: Exception) {
            mm.trace("开机广播:重排定时任务抛了 ${e.javaClass.simpleName}: ${e.message}")
        }

        // 「我又想不被误杀,又想能自己关掉」—— 用户关掉的那种,开机也不许自己醒。
        if (!HerLife.isAlive(app)) {
            mm.trace("开机广播(${intent.action}):alive=false,不起 —— 用户关掉的不自愈")
            return
        }

        mm.trace("开机广播(${intent.action}):alive=true,起服务锚住进程(模型延后装载)")
        try {
            context.startForegroundService(
                Intent(app, HerLifeService::class.java)
                    .putExtra(HerLifeService.EXTRA_DEFER, true)
            )
        } catch (e: Exception) {
            // Android 12+ 后台起前台服务有诸多限制,BootReceiver 是少数豁免之一,
            // 但机型策略千奇百怪(ColorOS)。**别静默吞掉** —— 那会让我们以为自启动生效了。
            mm.trace("开机广播:起前台服务被拒(${e.javaClass.simpleName}: ${e.message})")
        }
    }
}
