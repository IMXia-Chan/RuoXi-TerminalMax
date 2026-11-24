package com.example.touchpad

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * **沙盒 —— 她手里的一台 Linux。**
 *
 * 她一直有一个短板:说「我帮你跑个脚本」的时候,手边没有一台**属于用户的机器**。
 * 电脑那只手能点能打字,但那是**用户的桌面** —— 拿它来跑实验,跑坏了就是用户的东西。
 * 这个文件补的就是那一小块:一个只有她自己在用、跑坏了删掉重来的目录,里面是一套
 * 真正的 Linux 用户态(busybox)。
 *
 * ---
 *
 * ## 二进制为什么必须叫 `libbusybox.so`
 *
 * 安卓 10 起 app 私有目录被 SELinux 标成**不可执行**(见 [ModelManager] 那段说明)。
 * 所以沙盒里的 shell 不能像 Linux 上那样放一个 `bin/busybox` 就完事 ——
 * 它必须躺在 `nativeLibraryDir`(安装包解出来的 lib 目录,那是唯一允许 exec 的地方),
 * 名字还得是 `lib*.so` 才装得进去。
 *
 * 沙盒里的 `bin/` 于是全是**软链**:`bin/ls → nativeLibraryDir/libbusybox.so`。
 * busybox 按 `argv[0]` 分派,软链叫 `ls` 它就跑 `ls`。这是这套方案的全部机关。
 *
 * ## ★ 边界(三句话,和计划书里那三条一一对应)
 *
 * 1. **文件**:命令里不许出现绝对路径、不许出现 `..`(判据在 [SandboxMath])。
 *    当前目录就是沙盒的家目录,所有东西都写相对路径。
 * 2. **网络**:**没有放通**。网卡不是我们能关的东西,但沙盒里**没有**联网工具 ——
 *    不带 `wget`/`nc` 的 applet 会被跳过,能不能连上是宿主的事,不是沙盒说了算。
 * 3. **硬件**:沙盒不持有摄像头、麦克风、定位。它只是个目录加一个 shell。
 *
 * ★ 老实说清楚:**这是字符串闸,不是内核闸。** 真围墙要 chroot / 独立 uid,
 *   没 root 拿不到。所以这里的规矩是「她一失手就撞墙」,不是「有人拿它当武器也进不来」。
 *
 * ## 第二层:Alpine(proot)
 *
 * `shell` 那套只有 busybox,装不了新东西。要装东西就得有包管理器,于是包里还带了
 *
 *   - `libproot.so`(自己交叉编的 proot 5.4.0,静态,2.9 MB)
 *   - `assets/rootfs-alpine-aarch64.tar.gz`(Alpine 3.20,3.8 MB)
 *
 * `linux` 这个工具走的就是它。proot 靠 ptrace 把 rootfs 变成 `/`,
 * 子进程由 proot **自己装载 ELF**再跳进去 —— 所以私有目录「不可执行」这条对它不成立,
 * 不需要把任何东西塞进 `nativeLibraryDir`。
 *
 * ★ **一个 `-b` 绑定都不给**,这是「出不去」的全部保证。发行版里的 `/` 就是它自己,
 *   `/data`、`/sdcard` 在那边**根本不存在**。所以 `linux` 这条不套字符串闸 ——
 *   那层闸是为 `shell` 准备的,对假根没有意义。
 *
 * ★ 也要说清楚:proot **不是内核级隔离**。它是路径改写 + 伪造 uid,
 *   既没有 namespace 也没有真 chroot。跑恶意程序挡不住,跑正常软件够用。
 */
object Sandbox {

    const val ID = "sandbox"
    const val NAME = "沙盒"
    const val VERSION = "1"

    private const val TAG = "SANDBOX"
    private const val BIN_NAME = "libbusybox.so"
    private const val PROOT_NAME = "libproot.so"

    /**
     * proot 自带的那段 loader。
     *
     * ★ 没有它 proot 根本用不了:proot 每次执行程序,都是把 `execve` 改写成
     *   「执行 loader」,由 loader 自己把目标 ELF 装进内存。而它默认把 loader
     *   写到临时目录里 —— 那还是私有目录,**照样不可执行**,于是
     *   `execve("/bin/sh"): Permission denied`。用 `PROOT_LOADER` 把它指到
     *   `nativeLibraryDir` 里这份,才绕得开 SELinux。
     */
    private const val LOADER_NAME = "libprootloader.so"

    /**
     * 随包带的 Alpine rootfs。名字写死在这里 —— 换版本时这里是唯一要改的地方。
     *
     * ★ 扩展名**不能**是 `.tar.gz`:AAPT2 见到 `.gz` 会自己解开、还把名字改成 `.tar`,
     *   于是 `assets.open()` 按原名一律 FileNotFound(踩过一次)。
     *   内容仍是 gzip,只是扩展名不许带出来。
     */
    private const val ROOTFS_ASSET = "rootfs-alpine-aarch64.dat"

    /** 默认给她多少时间。够跑 `ls`、`grep`、小脚本;不够跑 `find /` 那种没边的。 */
    private const val DEFAULT_TIMEOUT_MS = 20_000
    private const val MAX_TIMEOUT_MS = 120_000

    /** 回执进的是上下文 —— 一次 `ls -l /sdcard/...` 不截断就能把窗口撑爆。 */
    private const val MAX_OUT = 8_000

    /**
     * 不链进 `bin/` 的 applet。
     *
     * ★ 判据只有一条:**它能不能碰到沙盒以外的东西**。这里没有 root,所以大部分
     *   需要特权的 applet(挂载、改分区、改所有权)本来就会失败 —— 但失败得漂亮
     *   不如**根本不出现**:模型看见 `mdev` 就会去试,试一次白跑一轮。
     *   留着的:读写文件、文本处理、压缩、`sh`/`ash`/`sed`/`awk` 这些真能用的。
     */
    private val SKIP = setOf(
        // 联网
        "wget", "nc", "telnet", "ftpget", "ftpput", "tftp", "ping", "ping6", "nslookup",
        "httpd", "udhcpc", "udhcpd", "ifconfig", "ip", "route", "arp",
        // 特权 / 能改宿主机状态
        "mount", "umount", "chroot", "su", "passwd", "mdev", "switch_root", "pivot_root",
        "insmod", "rmmod", "lsmod", "modprobe", "chown", "chgrp", "setpriv",
        // 改时间、改关机
        "date", "hwclock", "rdate", "halt", "poweroff", "reboot", "shutdown",
    )

    @Volatile
    private var appContext: Context? = null

    /** `null` = 还没装;空串 = 装好了;别的 = 上次失败的原因。 */
    @Volatile
    private var installError: String? = null

    @Volatile
    private var installed = false

    /** proot 软链和 rootfs 都就位了没有。rootfs 解包只有一次,这一步要几百毫秒到一两秒。 */
    @Volatile
    private var prootReady = false

    fun init(ctx: Context) {
        if (appContext != null) return
        appContext = ctx.applicationContext
        // 自检放在后台:它要起两三个进程,而 init 是在主线程上被调的。
        Log.i(TAG, "init:准备起自检线程")
        Thread({
            try {
                smoke(ctx.applicationContext)
            } catch (e: Throwable) {
                // ★ 这里必须抓 Throwable:第一次真机验的时候日志一个字都没有,
                //   而 catch(Exception) 抓不到的是 Error(比如 NoClassDefFoundError),
                //   线程会静默死掉 —— 表现就是「什么都没发生」。
                Log.w(TAG, "自检没跑成:" + e, e)
                write(ctx.applicationContext, "✗ 自检线程挂了:" + Log.getStackTraceString(e))
            }
        }, "sandbox-smoke").apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- 路径

    /** 那套用户态本身。名字带 `.so` 是 PackageManager 的规矩,不是它是共享库。 */
    private fun bin(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, BIN_NAME)

    fun root(ctx: Context) = File(ctx.filesDir, "sandbox")
    fun home(ctx: Context) = File(root(ctx), "home")
    fun binDir(ctx: Context) = File(root(ctx), "bin")
    fun tmpDir(ctx: Context) = File(root(ctx), "tmp")

    /** Alpine 发行版本体。proot 把它当成 `/`。 */
    fun rootfs(ctx: Context) = File(root(ctx), "rootfs")

    private fun prootBin(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, PROOT_NAME)
    private fun prootLoader(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, LOADER_NAME)

    // ---------------------------------------------------------------- 手

    private fun p(name: String, desc: String) = HandParam(name, desc)

    private fun t(
        name: String, desc: String, params: List<HandParam>, required: List<String>,
    ) = HandTool(name, desc, params, required)

    val tools: List<HandTool> = listOf(
        t(
            "shell",
            "在沙盒里跑一条 Linux 命令(busybox 用户态,不是电脑、也不是手机的安卓系统)。" +
                "★ 只能用相对路径:当前目录就是沙盒的家目录,`..` 和 `/xxx` 都会被拒。" +
                "结果会附上退出码和输出,退出码 0 不等于事办成了。",
            listOf(
                p("cmd", "要跑的命令,如 `uname -a`、`echo hi > a.txt && cat a.txt`、`ls -l`"),
                p("timeout", "最多跑几秒,可不填,默认 20,上限 120"),
            ),
            listOf("cmd")
        ),
        t(
            "linux",
            "在沙盒里的 Alpine Linux 里跑命令。这是一套完整的发行版,带 apk 包管理器," +
                "可以装软件(比如 `apk add python3`)。" +
                "★ 这里的路径是**发行版自己的**路径:`/` 就是那套系统,看不到手机的其它部分。" +
                "第一句 `cat /etc/os-release` 就能确认自己在哪。",
            listOf(
                p("cmd", "要跑的命令,如 `cat /etc/os-release`、`apk add python3`"),
                p("timeout", "最多跑几秒,可不填,默认 20,上限 120。装软件要设大一点"),
            ),
            listOf("cmd")
        ),
    )

    fun hand(nowMs: Long): Hand = Hand(
        id = ID, kind = HandMath.KIND_SANDBOX, name = NAME, version = VERSION,
        fetchedAtMs = nowMs, tools = tools,
        hidden = emptyList(),
        // 沙盒和手机上那些动作抢不到彼此的东西 —— 她在沙盒里跑 `ls` 不妨碍同时开个应用。
        locks = tools.associate { it.name to emptyList<String>() },
    )

    fun has(tool: String): Boolean = tools.any { it.name == tool }

    // ---------------------------------------------------------------- 执行

    /**
     * 跑一个工具。**永远返回带 `ok` 的对象,永远不抛。**
     *
     * @param allowHost 用户是否为「访问沙盒外路径」点了头。默认 false ——
     *                  判据是 [SandboxMath.reject],放行是上面那一层的事。
     */
    fun exec(tool: String, a: JSONObject, allowHost: Boolean = false): JSONObject {
        val ctx = appContext ?: return err("沙盒没初始化,这次没能执行。")
        if (!has(tool)) return err("沙盒这只手上没有「$tool」。")
        val cmd = str(a, "cmd")
        if (cmd.isBlank()) return err("没说跑什么命令。")

        // ★ 闸只对 `shell` 上。`linux` 走的是 proot,`/` 就是那套发行版自己的根,
        //   我们没有给它任何 `-b` 绑定,所以绝对路径**出不去** —— 这时再按「绝对路径=危险」
        //   拦一遍,只会让她连 `cat /etc/os-release` 都做不了。
        if (tool == "shell" && !allowHost) {
            SandboxMath.reject(cmd)?.let { return err(it) }
        }

        // ★★ 2026-11-08(体检 B1):**云端总闸关着,沙盒里也不许出网。**
        //   [SKIP] 只是不建软链,`bin/busybox` 始终在 —— `busybox wget` 照样下载;
        //   `linux` 那条更是一整台发行版,装个 curl 就出去了。
        //   这两条路都在 Java 层看不见 socket,只能在这儿按命令词拦。
        //   ★ 拦不住的是「装个解释器自己开 socket」和按 IP 直连 —— 见 [SandboxMath.usesNetwork]。
        if (!CloudNet.isOpen(ctx) && SandboxMath.usesNetwork(cmd)) {
            return err(
                "「云端总闸」关着 —— 这条命令会联网(wget/curl/apk add 这类),已经拦下。" +
                    "本机上的命令照常跑;要用联网命令,去设置里把「云端总闸」打开。"
            )
        }

        val ready = if (tool == "linux") ensureProot(ctx) else ensure(ctx)
        if (ready != null) return err(ready)

        val secs = str(a, "timeout").toIntOrNull() ?: 20
        val timeoutMs = (secs.coerceIn(1, MAX_TIMEOUT_MS / 1000) * 1000).toLong()
        val r = (if (tool == "linux") runInProot(ctx, cmd, timeoutMs) else runIn(ctx, cmd, timeoutMs))
            ?: return err("沙盒起不来(进程没建成)。")

        // ★ 装包这条路一定撞上硬链接禁令。装完就地补别名,
        //   这一步不让她自己再想,也不改她的回执。
        if (tool == "linux" && APK_INSTALL.containsMatchIn(cmd)) repairAliases(ctx)

        val out = JSONObject()
            .put("ok", true)
            .put("exit", r.code)
            .put("timedout", r.timedOut)
            .put("cwd", if (tool == "linux") "Alpine 发行版里的 /root" else "沙盒的家目录")
            .put("note", SandboxMath.verdict(r.code, r.timedOut, r.out, r.err))
        if (r.out.isNotBlank()) out.put("stdout", SandboxMath.clip(r.out, MAX_OUT))
        if (r.err.isNotBlank()) out.put("stderr", SandboxMath.clip(r.err, MAX_OUT))
        // ★ 什么都没吐的时候要**明说**。一个只有 ok 的回执,模型会以为「跑了但我不想说」,
        //   而真实情况可能是命令根本没执行 —— 这两件事的下一步完全不同。
        if (r.out.isBlank() && r.err.isBlank()) out.put("output", "(没有输出)")
        // ★★ 收尾那句 `N errors` **不是装失败**,是硬链接禁令的余波。
        //
        //   2026-11-05 真机实测:同一个目录里 `mv` 三次都成,只有 `ln` 被拒
        //   (EACCES)。apk 解出临时文件后要 rename 成正式名,而包里互为别名的那几个,
        //   正规做法是 link() —— Android 一律拒。于是它们改走 rename 兜底也失败,
        //   收尾就记一个 error。主程序文件都在,缺的是**别名**。
        //
        //   ★ 不解释的代价:她会照着「出错」处理 —— 重装、换包、或者索性放弃,
        //     而这三件事都不会让别名长出来,只会白烧时间。
        if (tool == "linux" && APK_INSTALL.containsMatchIn(cmd) &&
            APK_ERRORS.containsMatchIn(r.out + r.err)
        ) {
            out.put(
                "alias_note",
                "收尾那句「N errors」来自 Android 不许建硬链接:包里靠硬链装的同义词会缺," +
                    "已经就地补成软链。主程序能跑就不用重装。",
            )
        }
        return out
    }

    /**
     * 这条命令会不会碰到沙盒外的东西 —— 上层要据此问用户。
     *
     * ★ `linux` 那条永远返回 false:它跑在 proot 的假根里,没给过任何绑定,
     *   绝对路径指向的是发行版自己,不是手机。
     */
    fun needsHostOk(tool: String, cmd: String): Boolean =
        tool == "shell" && SandboxMath.reject(cmd) != null

    private fun err(msg: String): JSONObject = JSONObject().put("ok", false).put("error", msg)

    /** 参数取值。同 [SelfHand] 那个:`7.0` 这种尾巴要削掉,不能直接用 optString。 */
    private fun str(a: JSONObject, key: String): String {
        val v = a.opt(key) ?: return ""
        if (v === JSONObject.NULL) return ""
        val s = v.toString().trim()
        return if (s.endsWith(".0") && s.dropLast(2).all { it.isDigit() }) s.dropLast(2) else s
    }

    // ---------------------------------------------------------------- 装配

    /**
     * 目录和软链,第一次用的时候建。→ null 表示能用,否则是一句人话。
     *
     * 为什么按需建而不是在 [init] 里建:装完 App 一次都没用过沙盒的人,
     * 不该为它付任何启动时间。
     */
    fun ensure(ctx: Context): String? {
        if (installed) return null
        synchronized(this) {
            if (installed) return null
            val b = bin(ctx)
            // ★ 这句话必须精确。「程序不在」和「目录建不出来」是两种病,
            //   而它们都会表现成「沙盒用不了」—— 混在一起就没法查。
            if (!b.isFile) return "沙盒的用户态程序没随安装包带上(找不到 ${b.path})。".also { installError = it }
            val dirs = listOf(root(ctx), home(ctx), binDir(ctx), tmpDir(ctx), File(root(ctx), "etc"))
            for (d in dirs) {
                if (!d.isDirectory && !d.mkdirs() && !d.isDirectory) {
                    return "沙盒目录建不出来:${d.path}".also { installError = it }
                }
            }
            val marker = File(binDir(ctx), ".installed")
            // ★ 不能只看「标记文件在不在」。每次重装 App,`nativeLibraryDir` 里那串哈希
            //   都会变,旧软链**全部悬空**,而标记还在 —— 表现是 `bin/sh` 存在=false、
            //   起进程 ENOENT。所以标记里记了当时的 bin 路径,对不上就整目录重来。
            val markerOk = marker.isFile &&
                runCatching { marker.readText() }.getOrNull()?.contains("bin=${b.path}") == true
            if (!markerOk) {
                // 悬空的软链不该留着:整目录清空再链(只动我们自己的 bin 目录)。
                binDir(ctx).listFiles()?.forEach { runCatching { it.delete() } }
                // ★ busybox 按 argv[0] 分派。二进制叫 `libbusybox.so`(安装包的规矩),
                //   拿它自己跑会去找一个叫 "libbusybox.so" 的 applet,找不到 → 退出码 127。
                //   所以必须先有一条**名叫 busybox** 的软链,再通过它去问 applet 列表。
                val self = File(binDir(ctx), "busybox")
                if (!self.isFile) {
                    Files.deleteIfExists(self.toPath())
                    Files.createSymbolicLink(self.toPath(), b.toPath())
                }
                val listed = runRaw(ctx, self, listOf("--list"), 20_000, root(ctx))
                if (listed == null || listed.code != 0) {
                    val detail = listed?.err?.trim()?.take(300).orEmpty()
                    val tail = if (detail.isNotEmpty()) ",err=$detail" else ""
                    return "读不出 busybox 的 applet 列表(退出码 ${listed?.code ?: -1}$tail)。"
                        .also { installError = it }
                }
                var linked = 0
                var skipped = 0
                for (line in listed.out.lineSequence()) {
                    val name = line.trim()
                    if (name.isEmpty() || name in SKIP || name.any { it == '/' || it == ' ' }) {
                        skipped++
                        continue
                    }
                    val link = File(binDir(ctx), name)
                    try {
                        if (link.exists()) Files.deleteIfExists(link.toPath())
                        Files.createSymbolicLink(link.toPath(), b.toPath())
                        linked++
                    } catch (e: Exception) {
                        skipped++
                        Log.w(TAG, "软链建不起来:$name(${e.javaClass.simpleName})")
                    }
                }
                if (linked == 0) {
                    return "一条命令都没链上(软链全失败)—— 沙盒跑不起来。".also { installError = it }
                }
                marker.writeText("linked=$linked skipped=$skipped bin=${b.path}\n")
                Log.i(TAG, "装配完成:链上 $linked 个命令,跳过 $skipped 个")
            }
            installed = true
            installError = null
            return null
        }
    }

    /**
     * proot 的软链 + rootfs 解包。→ null 表示能用,否则是一句人话。
     *
     * 为什么慢的部分要单独拿出来:[ensure] 只建 busybox 那一套(几毫秒),
     * 而解 rootfs 是 500 多个文件、几百毫秒起步 —— 没有 [`linux`] 需求的人不该付这个钱。
     */
    fun ensureProot(ctx: Context): String? {
        if (prootReady) return null
        synchronized(this) {
            if (prootReady) return null
            val base = ensure(ctx)
            if (base != null) return base

            val pb = prootBin(ctx)
            if (!pb.isFile) {
                return "沙盒的 Linux 支持没随安装包带上(找不到 ${pb.path})。".also { installError = it }
            }
            if (!prootLoader(ctx).isFile) {
                return "proot 的 loader 没随安装包带上(找不到 ${prootLoader(ctx).path})。"
                    .also { installError = it }
            }
            // 和 busybox 一样:私有目录不可执行,只能从 nativeLibraryDir 那条硬路径上跑。
            val link = File(binDir(ctx), "proot")
            val linkOk = runCatching {
                Files.isSymbolicLink(link.toPath()) &&
                    Files.readSymbolicLink(link.toPath()).toString() == pb.path
            }.getOrDefault(false)
            if (!linkOk) {
                runCatching { Files.deleteIfExists(link.toPath()) }
                try {
                    Files.createSymbolicLink(link.toPath(), pb.toPath())
                } catch (e: Exception) {
                    return "proot 的软链建不出来:${e.javaClass.simpleName}:${e.message}".also { installError = it }
                }
            }

            val stamp = File(rootfs(ctx), ".installed")
            if (!stamp.isFile) {
                val rf = rootfs(ctx)
                if (!rf.isDirectory && !rf.mkdirs() && !rf.isDirectory) {
                    return "rootfs 目录建不出来:${rf.path}".also { installError = it }
                }
                // 用我们自己的 busybox 解包:asset 是 APK 里的一段流,tar 只认路径,
                // 所以先落到 tmp 再解。省得在 Kotlin 里手写一个 tar 解析器。
                val tarGz = File(tmpDir(ctx), "rootfs.tar.gz")
                try {
                    ctx.assets.open(ROOTFS_ASSET).use { ins ->
                        tarGz.outputStream().use { ins.copyTo(it) }
                    }
                } catch (e: Exception) {
                    return "安装包里取不出 rootfs:${e.javaClass.simpleName}:${e.message}".also { installError = it }
                }
                val t0 = System.currentTimeMillis()
                val r = runRaw(
                    ctx, File(binDir(ctx), "tar"),
                    listOf("-xzf", tarGz.path, "-C", rf.path, "--no-same-owner"),
                    180_000, rf,
                )
                val ms = System.currentTimeMillis() - t0
                if (r == null || r.code != 0) {
                    val detail = r?.err?.trim()?.take(300).orEmpty()
                    return "rootfs 解不开(退出码 ${r?.code ?: -1},${ms}ms):$detail".also { installError = it }
                }
                runCatching { tarGz.delete() }
                // 解了一半就断电的话,这个标记不会写下来 —— 下次会重解,不会带着半套系统跑。
                stamp.writeText("v1 ${ms}ms\n")
                Log.i(TAG, "rootfs 解包完成:${ms}ms")
            }
            prootReady = true
            return null
        }
    }

    // ---------------------------------------------------------------- 起进程

    private class Run(
        val code: Int, val out: String, val err: String, val timedOut: Boolean,
    )

    /** 老老实实起一个子进程,收 stdout / stderr,带超时。失败返回 null。 */
    private fun runRaw(
        ctx: Context, exe: File, args: List<String>, timeoutMs: Long, cwd: File,
        /** 整份替换掉环境。proot 里那套路径是**发行版视角**的,和宿主的绝对路径无关。 */
        envOverride: Map<String, String>? = null,
    ): Run? {
        val pb = ProcessBuilder(listOf(exe.path) + args)
        pb.directory(cwd)
        pb.redirectErrorStream(false)
        val env = pb.environment()
        // ★ 环境**清空重来**,不继承宿主那份。继承的话 `PATH` 会带上一串系统目录
        //   (里面全是安卓自己的工具),沙盒就不再是沙盒了 —— 而且**不报任何错**,
        //   只是那些命令突然变得「能用」,而它们碰的是宿主机。
        env.clear()
        if (envOverride != null) {
            env.putAll(envOverride)
        } else {
            env["HOME"] = home(ctx).path
            env["PWD"] = cwd.path
            env["PATH"] = binDir(ctx).path
            env["TMPDIR"] = tmpDir(ctx).path
            env["TMP"] = tmpDir(ctx).path
            env["TERM"] = "dumb"
            env["LANG"] = "C"
            env["LC_ALL"] = "C"
            // busybox 里挂的 SIGSYS 处理器:被 seccomp 杀掉时把「撞了哪个系统调用」写进 stderr
            env["BB_TRACE_SIGSYS"] = "1"
        }
        val p = try {
            pb.start()
        } catch (e: Exception) {
            // ★ 不返回 null —— 那样自检里只剩一句「进程没起来」,还得再打一次包才知道为什么。
            //   把异常原话塞进 err,一步到底。
            val why = "起进程失败:${e.javaClass.simpleName}:${e.message}"
            Log.w(TAG, why)
            return Run(-1, "", why, false)
        }
        // 两路输出必须**并行**读。先读一路再读另一路,另一路的管道一满就双死锁,
        // 症状是「命令永远不返回」—— 见过太多回了,这里不再犯。
        val outSb = StringBuilder()
        val errSb = StringBuilder()
        val max = 64 * 1024
        val tOut = drain(p.inputStream, outSb, max)
        val tErr = drain(p.errorStream, errSb, max)
        val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        val timedOut = !done
        if (timedOut) {
            p.destroyForcibly()
            try { p.waitFor(3, TimeUnit.SECONDS) } catch (_: Exception) {}
        }
        tOut.join(1000); tErr.join(1000)
        val code = try { p.exitValue() } catch (_: Exception) { -1 }
        return Run(code, outSb.toString(), errSb.toString(), timedOut)
    }

    private fun drain(s: InputStream, sb: StringBuilder, max: Int): Thread =
        Thread({
            try {
                s.bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    while (sb.length < max) {
                        val n = r.read(buf)
                        if (n <= 0) break
                        sb.append(buf, 0, minOf(n, max - sb.length))
                    }
                    // 超了就不再读,但**必须继续排空** —— 不排空的话子进程写满管道就卡死,
                    // 而我们在这边等它退出,等成一个死锁。
                    while (r.read(buf) > 0) { /* discard */ }
                }
            } catch (_: Exception) {
            }
        }, "sandbox-drain").apply { isDaemon = true; start() }

    /*
     * ★ 走 `bin/sh` 这条软链,而不是二进制本身:`argv[0]` 的 basename 决定跑哪个 applet,
     *   拿 `libbusybox.so` 当 argv[0] 只会得到「applet not found」(退出码 127)。
     */
    private fun runIn(ctx: Context, cmd: String, timeoutMs: Long): Run? =
        runRaw(ctx, File(binDir(ctx), "sh"), listOf("-c", cmd), timeoutMs, home(ctx))

    /**
     * 把宿主机当前的 DNS 写进发行版。
     *
     * ★ 不写这一句,`apk` 连 `dl-cdn.alpinelinux.org` 都解析不了 ——
     *   症状是「网络明明是通的,却一直说连不上」。
     *   每次跑之前都刷一次:换 Wi-Fi、开热点之后不用重启 App。
     */
    private fun refreshDns(ctx: Context) {
        val servers = runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            val lp = cm.getLinkProperties(cm.activeNetwork)
            lp?.dnsServers?.mapNotNull { it.hostAddress?.substringBefore('%') }
                ?.filter { it.isNotBlank() }
        }.getOrNull().orEmpty()
        // 拿不到就用国内公共 DNS 顶上 —— 总比一个名字都解析不了强。
        val list = servers.ifEmpty { listOf("223.5.5.5", "119.29.29.29") }
        // ★★ 2026-11-08：沙盒里的 Linux 是**另一个进程**，它的 socket 在 Java 层看不见，
        //   所以云端总闸管不到它（这是这道闸唯一拦不住的地方，写在这儿不藏着）。
        //   能做的两件事都在这里：闸关着时把 DNS 指到一个没人听的地址、并塞一个死代理，
        //   常规工具（apk/curl/wget）就解析不了也连不出去。
        //   **拦不住的**：写死 IP 的静态二进制。要让那种也停，只能不给它网络 ——
        //   那是 proot 做不到的事。
        val cloudOff = !CloudNet.isOpen(ctx)
        val text = if (cloudOff) {
            "nameserver 127.0.0.1\n"
        } else {
            list.joinToString("") { "nameserver $it\n" }
        }
        val f = File(rootfs(ctx), "etc/resolv.conf")
        runCatching {
            f.parentFile?.mkdirs()
            if (!f.isFile || f.readText() != text) f.writeText(text)
        }
    }

    /*
     * proot 的那条路。
     *
     * `-r`:把 rootfs 当成 `/`。
     * `-0`:假装 uid=0 —— 不然 apk 连自己的库目录都写不进去。
     * ★ 只绑四个**设备节点**,一个目录都不绑:这是「出不去」的全部保证。
     *   绑了 `/data` 或 `/` 就等于把手机送出去了。没有 `/dev/null`,apk 会直接报错。
     */
    private fun runInProot(
        ctx: Context, cmd: String, timeoutMs: Long,
        /** 诊断用:让 proot 把每条系统调用打到 stderr,好找出 SIGSYS 撞的是哪一条。 */
        trace: Boolean = false,
    ): Run? {
        refreshDns(ctx)
        return runRaw(
            ctx, File(binDir(ctx), "proot"),
            listOf(
                "-r", rootfs(ctx).path, "-0", "-w", "/root",
                "-b", "/dev/null", "-b", "/dev/zero",
                "-b", "/dev/urandom", "-b", "/dev/random",
                // ★ 试过 proot 自带的 `--link2symlink`,**在这颗内核上更糟**:
                //   它把文件名套成 `.l2s..l2s..apk.<hash>`,读的时候 `Operation not
                //   permitted`,`apk add` 的错从 1 条涨到 4 条,gcc 依然 MISSING。
                //   见 2026-11-04 的真机日志。别再加回来。
                "/bin/sh", "-c", cmd,
            ),
            timeoutMs, rootfs(ctx),
        envOverride = mapOf(
            "HOME" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to "dumb",
            "LANG" to "C",
            "LC_ALL" to "C",
            // proot 自己的临时目录 —— 别让它去碰宿主机的 /tmp(那里根本写不进去)。
            "PROOT_TMP_DIR" to tmpDir(ctx).path,
            // ★ 必须指到 nativeLibraryDir 那份:私有目录里的 loader 不可执行。
            "PROOT_LOADER" to prootLoader(ctx).path,
            // Android 的 App seccomp 一碰到 mount 就直接 SIGSYS 杀进程,
            // 而 apk 每次装包都要把 / 重新挂成 private。沙盒里本来就没有 root,
            // 这个挂载做不成也无妨 —— 让 proot 吞掉它、回「成功」。
            "PROOT_FAKE_MOUNT" to "1",
            // ★ 硬链接这条路走不通:Android 内核一律给 link() 回 EACCES。
            //   2026-11-05 扫过随包的 libproot.so,里头**没有** PROOT_LINK_AS_SYMLINK
            //   这个串(有的只是 FAKE_MOUNT / NO_SECCOMP / LOADER / TMP_DIR /
            //   DONT_POLLUTE_ROOTFS 那几个),所以设它不起任何作用 —— 之前那行是空转。
            //   别名由 [ALIAS_REPAIR] 在装包之后补成软链,这才是 gcc 能跑的原因。
        ).toMutableMap().apply {
            if (trace) put("PROOT_TRACE_SYS", "1")
            // ★★ 2026-11-08(体检 B1 的配套):[refreshDns] 那段注释里写了「塞一个死代理」,
            //   但这一格原来**根本没塞** —— apk/curl 走 http_proxy 时照样直连。
            //   闸关着时把它们指到本机一个没人听的端口:常规工具当场连接被拒,
            //   而不是先解析、再慢慢超时。写死 IP 的静态二进制仍然拦不住(见那段注释)。
            if (!CloudNet.isOpen(ctx)) {
                for (k in listOf("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY")) {
                    put(k, "http://127.0.0.1:9")
                }
                put("no_proxy", "127.0.0.1,localhost")
            }
        },
        )
    }

    /**
     * 装完包以后,把被 Android 挡掉的别名补回来。
     *
     * Android 的内核不许建硬链接(直接 EACCES),而 Alpine 的包里
     * `gcc` 和 `aarch64-alpine-linux-musl-gcc` 这种同义词**是按硬链接装的**。
     * apk 解到最后一个别名上失败,于是 `gcc` 这个名字根本不存在 ——
     * 包「装完了」,编译器却调不到。
     *
     * 软链和硬链对「调一个程序」这件事没有区别,换成软链就通了。
     */
    internal val ALIAS_REPAIR = """
        T=aarch64-alpine-linux-musl
        # ★ 上一版把最后一段当短名,`...-gcc-ar` 被剥成了 `ar`,
        #   于是 /usr/bin/ar 指到 gcc-ar 上(它会回头调 ar,套娃)。
        #   这里先把这种指错的清掉,再按**完整三元组前缀**剥。
        for b in ar nm ranlib; do
          t=${'$'}(readlink "/usr/bin/${'$'}b" 2>/dev/null)
          case "${'$'}t" in *-gcc-${'$'}b) rm -f "/usr/bin/${'$'}b";; esac
        done
        for d in /usr/bin /usr/sbin /bin /sbin /usr/lib; do
          [ -d "${'$'}d" ] || continue
          for n in "${'$'}d"/${'$'}T-*; do
            [ -e "${'$'}n" ] || continue
            f=${'$'}{n##*/}
            b=${'$'}{f#${'$'}T-}
            [ "${'$'}b" = "${'$'}f" ] && continue
            [ -e "${'$'}d/${'$'}b" ] || ln -s "${'$'}f" "${'$'}d/${'$'}b" 2>/dev/null
          done
        done
        # ★ binutils 的真身只装在 /usr/${'$'}T/bin/(apk 的清单里就这几个),
        #   gcc 靠自己的搜索路径找得到,但 `ld`、`objcopy` 这类直接调名的
        #   Makefile 找不到。补一层短名过去。
        if [ -d "/usr/${'$'}T/bin" ]; then
          for n in "/usr/${'$'}T/bin"/*; do
            b=${'$'}{n##*/}
            [ -e "/usr/bin/${'$'}b" ] || ln -s "${'$'}n" "/usr/bin/${'$'}b" 2>/dev/null
          done
        fi
    """.trimIndent()

    /** `apk add` 之后要不要补别名。 */
    private val APK_INSTALL = Regex("""(^|[\s;&|()])apk\s+(add|fix|upgrade)\b""")

    /** `apk` 收尾那句 `3 errors; 1234 KiB` —— 判据,不是错误码。 */
    private val APK_ERRORS = Regex("""\b\d+\s+errors?;""")

    private fun repairAliases(ctx: Context) {
        val r = runInProot(ctx, ALIAS_REPAIR, 60_000)
        Log.i(TAG, "alias repair exit=${r?.code} err=${r?.err?.trim()?.take(200)}")
    }

    // ---------------------------------------------------------------- 自检

    /**
     * 装完自己验一遍,结果落到 `files/sandbox/smoke.log`。
     *
     * ★ 为什么要这东西:沙盒这套的每一步都可能**静默失败** —— 软链建不上、
     *   SELinux 不让 exec、`argv[0]` 分派没生效。三者都不会崩,只会「命令跑完了但什么都没发生」。
     *   所以自检不看退出码,**看输出里有没有该有的字**。
     */
    fun smoke(ctx: Context): String {
        val sb = StringBuilder()
        fun line(s: String) {
            sb.append(s).append('\n')
            Log.i(TAG, s)
        }
        line("==== 沙盒自检 ${ctx.packageName} v$VERSION ====")
        line("bin: ${bin(ctx).path} exists=${bin(ctx).isFile}")

        // ★ 技能落盘一次性冒烟:存一条、读回来、删掉。
        //   只验**文件这一层**(存不住 / 读不回 / 删不掉);重放那条路要她真跑一次才算数。
        //   ★ 放在最前面:它不碰 proot,几秒就出结果 —— 混在后面会被那一分多钟的
        //     装包步骤拖着,而且自检结果文件每次启动都会被覆盖,晚写一步就看不到。
        ctx.getExternalFilesDir(null)?.let { File(it, "skill-test") }?.takeIf { it.isFile }?.let { flag ->
            flag.delete()
            val s = Skill(
                id = SkillMath.idOf("自检技能"),
                name = "自检技能",
                desc = "冒烟用",
                steps = listOf(SkillStep("hotkey", "{\"keys\":\"esc\"}")),
                sensitive = true,
                createdAt = System.currentTimeMillis(),
            )
            val err = SkillStore.save(s)
            val back = SkillStore.find(s.id)
            line(
                "· 技能落盘 save=${err ?: "ok"} 读回=${back?.steps?.size ?: -1}步" +
                    " 高危=${back?.sensitive} 名=${back?.name}"
            )
            SkillStore.delete(s.id)
            line("  删掉之后还找得到吗=${SkillStore.find(s.id) != null}")

            // 思考型:存的是方法,不是步骤 —— 往返一次,确认 kind / instructions / inputs 不丢。
            val r = Skill(
                id = SkillMath.idOf("自检分析技能"),
                name = "自检分析技能",
                desc = "冒烟用",
                kind = SkillKind.REASONING,
                instructions = "先问清标的和区间,再查数据,最后按要点输出。",
                inputs = listOf("标的", "区间"),
                sensitive = false,
                createdAt = System.currentTimeMillis(),
                draft = true,
            )
            val rErr = SkillStore.save(r)
            val rBack = SkillStore.find(r.id)
            line(
                "· 分析技能落盘 save=${rErr ?: "ok"} 类型=${rBack?.kind}" +
                    " 方法=${rBack?.instructions?.length ?: -1}字 输入=${rBack?.inputs}"
            )
            SkillStore.delete(r.id)
            line("  删掉之后还找得到吗=${SkillStore.find(r.id) != null}")

            // 研究型:技能本身 + 进度本。进度本是另一条落盘路径,单独验。
            val rs = Skill(
                id = SkillMath.idOf("自检研究技能"),
                name = "自检研究技能",
                desc = "冒烟用",
                kind = SkillKind.RESEARCH,
                instructions = "查文献、写证明、数值验证。",
                objective = "验一条不等式",
                acceptance = "小规模数值全过且没找到反例",
                budget = 3,
                sensitive = false,
                createdAt = System.currentTimeMillis(),
                draft = true,
            )
            val rsErr = SkillStore.save(rs)
            ResearchStore.note(rs.id, "第1轮:试了 n=1..1000,全成立。")
            ResearchStore.note(rs.id, "第2轮:n=1001 出现反例,方向作废。")
            val rsBack = SkillStore.find(rs.id)
            val led = ResearchStore.forModel(rs.id)
            line(
                "· 研究技能落盘 save=${rsErr ?: "ok"} 类型=${rsBack?.kind}" +
                    " 预算=${rsBack?.rounds}/${rsBack?.budget} 进度本=${led.length}字" +
                    " 含反例=${led.contains("反例")}"
            )
            ResearchStore.clear(rs.id)
            SkillStore.delete(rs.id)
            line("  清+删之后 技能=${SkillStore.find(rs.id) != null} 进度本=${ResearchStore.ledger(rs.id).length}字")

            // ★★ 事件流:落盘 + 引用 + 压给别的角色看 + 出门判定。
            //   这四样是多 agent 的全部地基 —— 界面上那个「数据流」画的是它,
            //   角色之间的「互通」递的也是它。★ 只验**能存能读能引用**,真跑一次协同要她真做一次。
            val task = "t0smoke"
            AgentBus.purge(task)   // ★ 自检的假事件不许留在真账本里
            val a = AgentBus.emit(task, AgentBusModel.SELF, "", AgentBusModel.KIND_NOTE, "他:帮我看下这家公司")
            val b = AgentBus.emit(
                task, AgentBusModel.HUB, "中枢", AgentBusModel.KIND_ASK, "派给云端:按四块分析", ref = a.id
            )
            AgentBus.emit(
                task, AgentBusModel.CLOUD, "算", AgentBusModel.KIND_ANSWER,
                "营收 3 亿\n利润 2 千万 | 现金流为正", ref = b.id,
            )
            val onDisk = AgentBus.of(task)
            val packed = AgentBusModel.render(onDisk)
            line(
                "· 事件流落盘 条数=${onDisk.size}/3 引用对=${onDisk.getOrNull(2)?.ref == b.id}" +
                    " 编号递增=${onDisk.map { it.id } == onDisk.map { it.id }.sorted()}"
            )
            line(
                "  压给别的角色=${packed.length}字 编号=${packed.contains("#${b.id}")}" +
                    " 引用=${packed.contains("↳${a.id}")} 换行没丢=${packed.contains("\n")}" +
                    " 竖线还在=${packed.contains("|")}"
            )
            AgentBus.purge(task)
            line("  清掉之后 条数=${AgentBus.of(task).size}")
            // 风控那一格:带卡号/手机号的**不出门**;真出门的那份要被遮掉。
            val no = AgentMath.route(emptySet(), needsTools = false, "卡号 4111111111111111 能用吗")
            val yes = AgentMath.route(emptySet(), needsTools = false, "解释一下什么是机会成本")
            line(
                "· 路由 带卡号=${no.where}(缘由含类别=${no.why.contains(SecretMath.KIND_CARD)})" +
                    " 纯文本=${yes.where} 出门那份遮了=${AgentMath.forCloud("卡号 4111111111111111").contains("[")}"
            )
            line("  要动手的一律本地=${AgentMath.route(emptySet(), true, "打开微信").where}")
            // ★ 派活:工具得**真在模型那份清单里**(提示词点了名、工具不在 = 调不出来),
            //   而且带号码的活必须先被判成「不出门」。两条是配套的。
            line(
                "· 派活 模型看得见=${AiAgent.toolVisible("delegate")}" +
                    " 带号码不派=${!AgentMath.route(emptySet(), false, "帮我把 13800001111 存一下").cloudy}"
            )

            // ★★ 假名那一档:同一个值永远同一个词 + 出门是代号 + 回来是原值。
            //   验完把这两条假数据删掉 —— 表里的每一条都代表「云端见过的一个东西」,
            //   自检不该往里留东西。
            val before = AliasStore.count()
            val p1 = "13800001111"
            val al1 = AliasStore.aliasFor(SecretMath.KIND_PHONE, p1)
            val al2 = AliasStore.aliasFor(SecretMath.KIND_PHONE, p1)
            val al3 = AliasStore.aliasFor(SecretMath.KIND_PHONE, "13900002222")
            val outText = AgentMath.forCloud("打给 $p1", AliasStore::aliasFor)
            val backText = AgentMath.fromCloud(outText) { AliasStore.valueOf(it) }
            line(
                "· 假名 稳定=${al1 == al2} 不同值不同词=${al1 != al3} 条数=$before→${AliasStore.count()}" +
                    " 词=${al1.take(6)}…"
            )
            line("  出门=${outText} 里面还有原值吗=${outText.contains(p1)}")
            line("  回来=$backText 换回来了=${backText.contains(p1)}")
            AliasStore.forget(al1)
            AliasStore.forget(al3)
            line("  删掉自检那条之后 条数=${AliasStore.count()}(应回到 $before)")
        }
        // ★★ 快检到这里就**先落一次盘**。后面那一段要起 proot、连网装包,可能几十秒
        //   甚至挂住;而这份文件只在最后写一次的话,「前面全对」这件事会跟着一起看不到。
        //   2026-11-04 就这么白等过两轮。
        write(ctx, sb.toString())

        val e = ensure(ctx)
        if (e != null) {
            line("✗ 装配失败:$e")
            write(ctx, sb.toString())
            return sb.toString()
        }
        line("✓ 装配完成,bin 下 ${binDir(ctx).list()?.size ?: 0} 项")
        // 软链必须指向**这次安装**的那份二进制:重装 App 会让 nativeLibraryDir 换掉一段哈希,
        // 旧软链会全部悬空,而「标记文件还在不在」看不出这件事。
        val shLink = File(binDir(ctx), "sh")
        val shTarget = try { Files.readSymbolicLink(shLink.toPath()).toString() } catch (e: Exception) { null }
        if (shTarget == null) {
            line("✗ bin/sh 不是软链")
        } else if (shTarget != bin(ctx).path) {
            line("✗ bin/sh 指向旧安装的路径:$shTarget")
        }

        fun step(name: String, cmd: String) {
            val t0 = System.currentTimeMillis()
            val r = runIn(ctx, cmd, 30_000)
            val ms = System.currentTimeMillis() - t0
            if (r == null) {
                line("✗ $name:进程没起来")
                return
            }
            line("· $name [${ms}ms exit=${r.code}${if (r.timedOut) " TIMEOUT" else ""}]")
            if (r.out.isNotBlank()) line("  out: " + r.out.trim().replace("\n", " | "))
            if (r.err.isNotBlank()) line("  err: " + r.err.trim().replace("\n", " | "))
        }

        step("uname", "uname -a")
        step("写读", "echo 你好 > a.txt && cat a.txt")
        step("applet 数", "busybox --list | wc -l")
        step("pwd", "pwd")
        step("软链分派", "ls -l a.txt | wc -l")
        step("拒绝绝对路径", "/system/bin/sh -c id 2>/dev/null")
        step("拒绝 ..", "cd .. && pwd")

        // ---- Alpine(proot)----
        val t0 = System.currentTimeMillis()
        val pe = ensureProot(ctx)
        val pms = System.currentTimeMillis() - t0
        if (pe != null) {
            line("✗ proot 装配失败:$pe")
        } else {
            line("✓ proot 就绪 [${pms}ms],rootfs 下 ${rootfs(ctx).list()?.size ?: 0} 项")
            fun pstep(name: String, cmd: String, secs: Long = 30_000) {
                val t = System.currentTimeMillis()
                val r = runInProot(ctx, cmd, secs)
                val ms = System.currentTimeMillis() - t
                if (r == null) {
                    line("✗ $name:进程没起来")
                    return
                }
                line("· [Alpine] $name [${ms}ms exit=${r.code}${if (r.timedOut) " TIMEOUT" else ""}]")
                if (r.out.isNotBlank()) line("  out: " + r.out.trim().replace("\n", " | "))
                if (r.err.isNotBlank()) line("  err: " + r.err.trim().replace("\n", " | "))
            }
            pstep("os-release", "cat /etc/os-release | head -2")
            pstep("uid", "id -u")
            pstep("apk", "apk --version")
            // 假根确实关得住:发行版里不该有手机的那些目录
            pstep("根是发行版自己的", "ls / | tr '\\n' ' '")
            // ★ 这一条才是「装东西」的验收。拆开跑,是为了分清是哪一步撞墙:
            //   本地库 → 拉索引(联网) → 解依赖(不写盘) → 真装。
            // ★ `1 error` 找不到正文,先确认两个环境事实:发行版里有没有 /proc,
            //   以及 apk 自己 -v 时怎么说。两条都不改状态。
            pstep("proc", "ls /proc 2>&1 | wc -l; cat /proc/self/status 2>&1 | head -2", 30_000)
            pstep("apk info", "apk info 2>&1 | head -2", 60_000)
            pstep("apk update", "apk update 2>&1 | tail -2", 120_000)
            // ★ 判别用:python3 已经装了,模拟它等于「没事可做」;bash 没装过。
            //   若 bash 干净而 python3 报错,那 `1 error` 是空操作的附带产物,不是环境缺陷。
            pstep("apk 模拟装 bash", "apk add --no-cache --simulate bash 2>&1 | tail -6", 120_000)
            // ★ apk-tools 2.14 只认 `-v`(单个短横),`--verbose` 会被当成未知长选项、
            //   直接吐帮助文本 —— 上一版这条自检量的是帮助页,不是装包。
            //   `--simulate` 也必须跟在 `add` 子命令后面。
            pstep("apk -v 模拟装", "apk -v add --no-cache --simulate python3 2>&1 | tail -20", 120_000)
            run {
                val t = System.currentTimeMillis()
                // ★ 这两步不再开 trace:PROOT_TRACE_SYS 会把每条系统调用打进 stderr,
                //   而 `tail -3` 只截得到「1 error」那行,真正的报错被 [SYS n] 冲掉了。
                // ★ 不再 `tail -3`:`1 error` 在 --simulate 下也出现,而它前面那行
                //   才是原因。模拟装不跑任何脚本,所以这条错误跟触发器无关。
                // ★ 落盘再读:apk 的报错有可能既不走管道也不进我们这层缓冲,
                //   直接在发行版里重定向成文件,是唯一不会丢字的读法。
                val r = runInProot(
                    ctx,
                    "apk add --no-cache --simulate python3 >/tmp/s.out 2>/tmp/s.err; echo RC=\$?" +
                        "; echo '===OUT==='; cat /tmp/s.out; echo '===ERR==='; cat /tmp/s.err",
                    120_000,
                )
                line("· [Alpine] apk 模拟装 [${System.currentTimeMillis() - t}ms exit=${r?.code}]")
                // ★ 这一段**不能截太狠**:apk 的 `1 error` 是收尾汇总,真正那句
                //   `ERROR: …` 在它前面几十行,截到 300 字符就正好把它切掉。
                if (r != null && r.out.isNotBlank()) line("  out: " + r.out.trim().take(2500))
                if (r != null && r.err.isNotBlank()) line("  err: " + r.err.trim().take(800))
            }
            run {
                val t = System.currentTimeMillis()
                val r = runInProot(ctx, "apk add --no-cache lua5.3 2>&1 | tail -12", 180_000)
                line("· [Alpine] 真装 [${System.currentTimeMillis() - t}ms exit=${r?.code}]")
                if (r != null && r.out.isNotBlank()) line("  out: " + r.out.trim().takeLast(900))
                if (r != null && r.err.isNotBlank()) line("  err: " + r.err.trim().takeLast(1200))
            }
            // ★ `apk add` 收尾永远跟着一句 `1 error`,而它出在
            //   「Executing busybox-<ver>.trigger」之后 —— 装是装成了,报错来自那个脚本。
            //   把脚本从 `scripts.tar` 里抠出来、用 `-x` 跑一遍,才知道挂在哪一行。
            run {
                val t = System.currentTimeMillis()
                val r = runInProot(
                    ctx,
                    "cd /tmp && tar -tf /lib/apk/db/scripts.tar | grep busybox | head -3" +
                        "; n=\$(tar -tf /lib/apk/db/scripts.tar | grep 'busybox.*\\.trigger$' | head -1)" +
                        "; echo \"成员:\$n\"" +
                        "; tar -xOf /lib/apk/db/scripts.tar \"\$n\" > t.sh; echo rc_extract=\$?" +
                        "; echo '--- 脚本 ---'; cat t.sh; echo '--- 跑 ---'" +
                        "; sh -x t.sh 2>&1 | tail -25; echo rc_run=\$?",
                    60_000,
                )
                line("· [Alpine] 触发器 [${System.currentTimeMillis() - t}ms exit=${r?.code}]")
                if (r != null && r.out.isNotBlank()) line("  out: " + r.out.trim().takeLast(1500))
                if (r != null && r.err.isNotBlank()) line("  err: " + r.err.trim().takeLast(600))
            }
            // ★ 一次性验收:外部 files 目录里放一个 `gcc-test` 文件,
            //   下次启动就跑「装编译器 → 编一个 C 程序 → 跑出结果」,跑完自己删标记。
            //   不放进常驻自检:装 gcc 要百来兆,不能每次开 App 都干一遍。
            val gccFlag = ctx.getExternalFilesDir(null)?.let { File(it, "gcc-test") }
            if (gccFlag != null && gccFlag.isFile) {
                gccFlag.delete()
                // ★ 不卸了重装:装一次十几分钟,而且会重踩同一个错。
                //   现在要验的是「盘上已有的东西能不能补出别名」。
                pstep(
                    "盘上现状",
                    "ls /usr/bin | grep '^aarch64-alpine-linux-musl-' | head -30" +
                        "; echo '--- 短路名 ---'" +
                        "; for n in gcc ar as ld nm objcopy objdump ranlib; do printf '%s=' \$n;" +
                        " command -v \$n >/dev/null && echo ok || echo MISSING; done",
                    60_000,
                )
                pstep("补别名", ALIAS_REPAIR, 60_000)
                // ★ 补完再编。这条路**只许补别名,不许别的补救** ——
                //   要是还得再干点啥才编得过,说明这个办法不成立。
                pstep(
                    "直接编译",
                    "for n in gcc gcc-ar gcc-nm gcc-ranlib ar as ld nm; do printf '%s=' \$n;" +
                        " command -v \$n >/dev/null && echo ok || echo MISSING; done" +
                        "; ls -l /usr/bin/gcc" +
                        "; printf '#include <stdio.h>\\nint main(){printf(\"hello 42\\\\n\");return 0;}\\n'" +
                        " > /tmp/h.c && gcc /tmp/h.c -o /tmp/h && /tmp/h; echo rc=\$?",
                    180_000,
                )
                // ★ 对照:内核层面硬链接依旧不许建。开了扩展之后 `ln` 会「成功」,
                //   但那是 proot 换成了软链 —— 这里看 `ls -l` 是不是 l 开头就能分辨。
                // ★ gcc 编得过,但 `as`/`ld` 两个短名不存在 —— 那它到底用的谁?
                //   直接问 gcc 自己,别猜。要是它也找不到,下次编真代码就会断。
                pstep(
                    "as/ld 在哪",
                    "echo as=\$(gcc -print-prog-name=as)" +
                        "; echo ld=\$(gcc -print-prog-name=ld)" +
                        "; ls -l \$(gcc -print-prog-name=as) 2>&1 | head -3" +
                        "; echo '--- binutils 装没装 ---'" +
                        "; apk info -e binutils; echo pkg_rc=\$?" +
                        "; apk info -L binutils 2>/dev/null | head -6" +
                        "; echo '--- ar 是哪来的 ---'; ls -l \$(command -v ar)" +
                        "; echo '--- 别处有没有 bin ---'" +
                        "; ls /usr/aarch64-alpine-linux-musl/bin 2>&1 | head -8",
                    60_000,
                )
                pstep("硬链探针", "touch /tmp/l1 && ln /tmp/l1 /tmp/l2; echo ln_rc=\$?; ls -l /tmp/l2", 30_000)
            }
            // ★ 一次性 adb 调试入口:外部 files 目录里放一个 `sandbox-exec.sh`,
            //   里面的命令走 proot 跑一遍,结果写到 `sandbox-exec.out`,跑完就删标记。
            //
            //   为什么要有这个:每验一个假设都要「改代码 → 打包 → 安装 → 重启」,
            //   一轮三分钟,而真正要问的问题往往只是一条 `apk -v` 的输出。
            //   有了它,后面每一轮调试都是「放文件 → 重启 → 读结果」。
            //
            //   ★ 它不是后门:`getExternalFilesDir` 只对本 App 和 adb 可写,
            //    命令在沙盒的假根里跑,碰不到宿主机(见文件头那三条边界)。
            val execFlag = ctx.getExternalFilesDir(null)?.let { File(it, "sandbox-exec.sh") }
            if (execFlag != null && execFlag.isFile) {
                val cmd = runCatching { execFlag.readText().trim() }.getOrNull().orEmpty()
                execFlag.delete()
                if (cmd.isNotEmpty()) {
                    val t = System.currentTimeMillis()
                    val r = runInProot(ctx, cmd, 180_000)
                    val report = buildString {
                        append("cmd: ").append(cmd).append('\n')
                        append("exit=").append(r?.code)
                        append(" timeout=").append(r?.timedOut)
                        append(" ms=").append(System.currentTimeMillis() - t).append('\n')
                        append("===OUT===\n").append(r?.out.orEmpty())
                        append("\n===ERR===\n").append(r?.err.orEmpty())
                    }
                    runCatching {
                        ctx.getExternalFilesDir(null)?.let { File(it, "sandbox-exec.out").writeText(report) }
                    }
                    line("· [Alpine] 调试命令 exit=${r?.code}")
                }
            }
            pstep("新装的能跑", "lua5.3 -e 'print(6*7)'", 60_000)
            pstep("上次装的还在", "python3 -V", 60_000)
        }
        val deny1 = SandboxMath.reject("/system/bin/id")
        val deny2 = SandboxMath.reject("cd .. && ls")
        line("闸:绝对路径 → ${if (deny1 != null) "拦下 ✓" else "放行了 ✗"}")
        line("闸:.. → ${if (deny2 != null) "拦下 ✓" else "放行了 ✗"}")
        line("==== 结束 ====")
        val text = sb.toString()
        write(ctx, text)
        return text
    }

    private fun write(ctx: Context, text: String) {
        try {
            root(ctx).mkdirs()
            File(root(ctx), "smoke.log").writeText(text)
        } catch (_: Exception) {
        }
        try {
            // ★ 外部那份是给「人验货」用的:release 包 adb 读不到 /data/data,
            //   这份在 /sdcard/Android/data/<包名>/files/,不用 root 也能看。
            val ext = ctx.getExternalFilesDir(null) ?: return
            ext.mkdirs()
            File(ext, "sandbox-smoke.txt").writeText(text)
        } catch (_: Exception) {
        }
    }
}
