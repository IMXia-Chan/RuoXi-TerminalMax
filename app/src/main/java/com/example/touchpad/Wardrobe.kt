package com.example.touchpad

import android.content.Context
import java.io.File

/**
 * 换装那条路的**唯一**碰文件系统的地方。
 *
 * 判定全在 [WardrobeMath](纯逻辑,能单测);这里只做三件事:
 * 列出目录、打开文件、写一行日志。**故意薄到这个程度** —— 因为这一层在
 * 手机拔着的时候是验不了的(2026-10-05 下午)。
 *
 * ## 三个目录
 *
 * ```
 * /sdcard/Android/data/com.example.touchpad/files/person/   ← 人物形象
 * /sdcard/Android/data/com.example.touchpad/files/room/     ← 房间布置
 * /sdcard/Android/data/com.example.touchpad/files/motion/   ← 她的动作
 * ```
 *
 * 和 `models/` / `asr/` / `tts/` 同住 —— 那几样已经是「推进去的、不打进 APK」的惯例,
 * 这里照抄,不新开一种。
 *
 * ## ★ 白名单闸在 [WardrobeMath.allowedExternal],不在这儿
 *
 * 这里只负责「问它一句」,不自己判断。**别把判断搬到这儿** —— 搬过来就单测不了了,
 * 而这条判断挡的是「往我自己的源里放一个 .js」那件事,它必须有测试钉着。
 */
internal object Wardrobe {

    const val DIR_PERSON = "person"
    const val DIR_ROOM = "room"
    const val DIR_MOTION = "motion"

    /** 房间布置那份配置。★ 名字是我们定的,所以按**精确文件名**找(见 [roomJson])。 */
    const val FILE_ROOM_JSON = "room.json"

    /**
     * 判定结论 + 该写进日志的那句话。
     *
     * ★ [file] 允许是 null,那表示「**什么都没有,但有话要说**」——
     *   例:目录在、里面真躺着一个 `.vrm`,可它是 0 字节。
     *   这一格必须存在:它和「你压根没放」在屏幕上长得一模一样,
     *   而只有日志分得开。**别把它和「返回 null = 没事发生」混成一件事。**
     */
    data class Hit(val file: File?, val note: String)

    /**
     * 外部目录的根。**可能返回 null**(外置存储没挂载),调用方要当「没有这个人」处理,
     * 不许当「目录是空的」—— 那两者的日志必须不一样。
     */
    private fun root(ctx: Context): File? = ctx.getExternalFilesDir(null)

    fun personDir(ctx: Context): File? = root(ctx)?.let { File(it, DIR_PERSON) }
    fun roomDir(ctx: Context): File? = root(ctx)?.let { File(it, DIR_ROOM) }
    fun motionDir(ctx: Context): File? = root(ctx)?.let { File(it, DIR_MOTION) }

    /**
     * 这一个 WebView 请求,要不要用外部目录里的文件顶掉 APK 里那个?
     *
     * 返回 null = **照旧读 APK**(没有外部文件,或者这个请求不管)。
     * 返回 [Hit] = 用这个文件,并且 [Hit.note] 那句话**必须写进 `model.log`**。
     *
     * ## 两条不同的认法(不是随手写的)
     *
     * | 请求 | 怎么找 | 为什么 |
     * |---|---|---|
     * | `.vrm` / `.glb` / `.gltf` / `.vrma` | **只认扩展名,不认文件名** —— 目录里排第一的那个 | 他丢进来的一定叫 `新人物.vrm` 这种名字,**我们猜不到**。「放文件就生效」的全部意思就在这一格 |
     * | 别的(贴图 / `.bin` / `.json`) | **按文件名精确找** | 这些是模型**自己引用**的(`room.glb` 里写着 `floor.png`),名字必须对得上,不能挑 |
     */
    fun resolve(ctx: Context, path: String): Hit? {
        val name = path.substringAfterLast('/')
        // ★ 白名单先过。不是数据文件(尤其 .html / .js)在这儿就被挡掉,
        //   连目录都不去看 —— 见 WardrobeMath 文件头。
        if (!WardrobeMath.allowedExternal(name)) return null

        val ext = WardrobeMath.extOf(name)
        return when {
            ext in WardrobeMath.MODEL_EXTS ->
                pick(ctx, personDir(ctx), DIR_PERSON, WardrobeMath.MODEL_EXTS, "人物")

            ext in WardrobeMath.SCENERY_EXTS ->
                pick(ctx, roomDir(ctx), DIR_ROOM, WardrobeMath.SCENERY_EXTS, "房间模型")

            ext in WardrobeMath.MOTION_EXTS ->
                pick(ctx, motionDir(ctx), DIR_MOTION, WardrobeMath.MOTION_EXTS, "动作")

            // 模型自己引用的零碎(贴图 / .bin)。**按名字找** —— 见上面那张表。
            else -> byName(ctx, name)
        }
    }

    /** 「丢进去就生效」那条:按扩展名挑。 */
    private fun pick(
        ctx: Context,
        dir: File?,
        dirName: String,
        exts: List<String>,
        what: String,
    ): Hit? {
        if (dir == null) return null
        if (!dir.isDirectory) return null
        val cands = (dir.listFiles() ?: return null).map {
            WardrobeMath.Candidate(it.name, it.length(), it.canRead())
        }
        return when (val p = WardrobeMath.pick(cands, exts)) {
            is WardrobeMath.Pick.None -> {
                // ★ 目录**在**、里面也有东西,但一个都用不了 —— 这必须说出来。
                //   它和「你压根没放」在屏幕上长得一模一样,只有日志分得开。
                //   (file = null:有话说,但没有文件可 serve,照旧读 APK 那份。)
                if (cands.any { it.name.lowercase().endsWith(".${exts.first()}") }) {
                    Hit(null, WardrobeMath.sentence(what, p) + "($dirName/)")
                } else null
            }
            is WardrobeMath.Pick.Found -> {
                val f = File(dir, p.name)
                Hit(f, WardrobeMath.sentence(what, p) + "($dirName/)")
            }
        }
    }

    /**
     * 房间那份配置:`room/room.json`。**没放就返回 null,一句废话都不说。**
     *
     * ★ 它和 [resolve] 那条路是分开的,而且**认法不一样**:
     *   [resolve] 面对的是「我们猜不到他会起什么名字」,所以只认扩展名;
     *   这份文件的名字**是我们定的**(见 [FILE_ROOM_JSON]),所以名字对不上就是没放 ——
     *   放宽成「随便一个 .json」只会让以后多一种配置文件时互相顶掉。
     *
     * ★ 「不在」和「在但读不到 / 是 0 字节」必须**分开说**。
     *   后者是这台机器上真踩过的坑:adb 推进去的文件属主是 shell、others 位为 0,
     *   App 的 uid 进不去,而症状看起来完全就是「你没放」。
     */
    fun roomJson(ctx: Context): Hit? {
        val f = roomDir(ctx)?.let { File(it, FILE_ROOM_JSON) } ?: return null
        if (!f.isFile) return null
        return when {
            f.length() <= 0L -> Hit(null, "房间配置 room.json 是 0 字节,当没写")
            !f.canRead() -> Hit(null, "房间配置 room.json 读不到(权限),当没写")
            else -> Hit(f, "房间配置:用 room.json(${f.length()} 字节)")
        }
    }

    /**
     * 整个房间的模型(`room/` 里排第一的 `.glb` / `.gltf`)。
     *
     * 复用 [pick] —— 也就是「只认扩展名不认文件名」那条规矩,和人物模型同一个形状:
     * 他丢进来的一定叫「新房间.glb」这种名字,我们猜不到。
     *
     * ★ 返回 null = 什么都没有。返回 `Hit(null, 有话要说)` = 里面确实躺着文件、
     *   但一个都用不了 —— **这一格必须说出来**,它就是「我放对了却没换」的分界线。
     */
    fun roomModel(ctx: Context): Hit? =
        pick(ctx, roomDir(ctx), DIR_ROOM, WardrobeMath.SCENERY_EXTS, "房间模型")

    /**
     * 她的动作(`motion/` 里排第一的 `.vrma`)。和 [roomModel] 同一个形状 ——
     * 「只认扩展名不认文件名」,因为他下下来的那份一定叫一串日文/英文名,我们猜不到。
     *
     * ★ 什么时候调它:**进房间时主动推一次**(见 `ConMarnActivity.pushMotion`)。
     *   不像人物/房间那样靠页面自己去请求 —— 动作不是「一次加载」的东西,
     *   它要先被读进来、变成一条动画轨道、再喂给 mixer;这一步得由我们主动发起。
     *
     * ★ 返回 null = 没放(那就不管,她照旧用自带的待机微动,一句废话都不说)。
     *   返回 `Hit(null, 有话要说)` = 里面确实躺着 `.vrma`、但一个都用不了 ——
     *   **这一格必须说出来**:它就是「我放了却没动」的分界线。
     */
    fun motion(ctx: Context): Hit? =
        pick(ctx, motionDir(ctx), DIR_MOTION, WardrobeMath.MOTION_EXTS, "动作")

    // ------------------------------------------------------------------
    // 挑动作(2026-10-09)—— 房间主页最右侧那一栏
    //
    // ★★ 和上面那套的**关键区别:这条路一个文件都不动**。
    //
    // 上面那套是「放文件就生效」:他把文件摆进 `motion/`,排第一的那个就是答案。
    // 这一条是「他在房间里点一下」:选择**存在偏好里**,库里那份文件原样不动。
    //
    // 为什么不做成「把选中的拷进 `motion/`」——那个写法更省事,但**要删掉 `motion/`
    // 里原来那个文件**。而他往里放什么我们不知道(可能是他自己做的、别处再也找不到的
    // 一份)。这个项目的硬规矩是「**删任何东西前先问**」,一个点一下就删文件的按钮
    // 不该绕过它。所以:库是库,`motion/` 是 `motion/`,谁也不覆盖谁。
    //
    // ★ 没在界面上挑过的人,行为和今天**一个字都不差**:偏好是空的,
    //   [WardrobeMath.chosenMotion] 返回 null,一切照旧走 [motion]。
    // ------------------------------------------------------------------

    /** 偏好文件。和 `ai_agent` / `touchpad` 那些并列,新开一个 —— 别混进别人的键里。 */
    private const val PREFS = "wardrobe"
    private const val KEY_MOTION = "motion"

    /** 库里的一个动作。给界面上那一栏用。 */
    data class Motion(
        /** 相对**库目录**的路径,如 `vrm-viewer/Relax.vrma`。它就是存进偏好的那个值。 */
        val rel: String,
        /** 给他看的中文名,认不出来就是文件名(见 [WardrobeMath.motionLabel])。 */
        val label: String,
        /** 来自哪个子目录(显示用)。 |
         *  直接躺在库根上的返回空串。 */
        val folder: String,
        val bytes: Long,
    )

    /** 一次扫描的结果。★ [skipped] 必须报出来 —— 见下面那条注释。 */
    data class MotionList(val usable: List<Motion>, val skipped: List<String>)

    /** 动作库目录。和 `motion/` 并列,**不顶掉它**。 */
    fun motionLibraryDir(ctx: Context): File? =
        root(ctx)?.let { File(it, WardrobeMath.DIR_MOTION_LIBRARY) }

    /**
     * 库里有哪几条动作。**只扫两层**(库根 + 一层子目录)—— 库是我们自己铺的,
     * 三个来源各占一个子目录,不需要递归。
     *
     * ★★ **坏文件要说出来,不许静默不显示**:库里躺着一个 0 字节的文件,
     *   如果在那一栏里**根本不出现**,他看到的就是「我明明拷进去了,怎么没有它」——
     *   和「你压根没拷」长得一模一样。所以它进 [MotionList.skipped],
     *   由调用方写进日志(这一层不碰 `ModelManager`,理由见文件头)。
     */
    fun listMotions(ctx: Context): MotionList {
        val base = motionLibraryDir(ctx) ?: return MotionList(emptyList(), emptyList())
        if (!base.isDirectory) return MotionList(emptyList(), emptyList())

        val usable = ArrayList<Motion>()
        val skipped = ArrayList<String>()
        for (child in (base.listFiles() ?: return MotionList(emptyList(), emptyList()))
            .sortedBy { it.name }) {
            when {
                child.isFile -> {
                    // ★ 库根的散文件:路径就是文件名,照样过 [WardrobeMath.safeLibraryPath]
                    val rel = child.name
                    if (!WardrobeMath.safeLibraryPath(rel)) continue
                    addMotion(child, rel, "", usable, skipped)
                }
                child.isDirectory -> {
                    for (f in (child.listFiles() ?: continue).sortedBy { it.name }) {
                        if (!f.isFile) continue
                        val rel = child.name + "/" + f.name
                        // ★ 白名单 + 爬目录两道闸,和 serve 那条路**同一个函数**
                        if (!WardrobeMath.safeLibraryPath(rel)) continue
                        addMotion(f, rel, child.name, usable, skipped)
                    }
                }
            }
        }
        return MotionList(usable, skipped)
    }

    private fun addMotion(
        f: File,
        rel: String,
        folder: String,
        into: MutableList<Motion>,
        skipped: MutableList<String>,
    ) {
        val ok = try { f.canRead() && f.length() > 0L } catch (_: Exception) { false }
        // ★ 同一句话的两种说法,和 `WardrobeMath.Pick` 里那段**故意一致**(00 字节 / 读不到)
        if (!ok) {
            val why = try { if (!f.canRead()) "读不到" else "0 字节" } catch (_: Exception) { "读不到" }
            skipped += "$rel($why)"
            return
        }
        into += Motion(rel, WardrobeMath.motionLabel(rel), folder, f.length())
    }

    /**
     * 库里那个相对路径对应的文件。**给 serve 那条路用。**
     *
     * ★★ 两道闸(**都是纯逻辑、都有单测**):[WardrobeMath.safeLibraryPath] 挡路径与扩展名;
     *   这里再核一次「出来的文件**确实还在库目录里面**」—— 前一道已经拒了 `..`,
     *   这一道是补网(真出了意外,宁可读不到,也不许读到库外面去)。
     *
     * 返回 null = 读不到 / 不合规 / 是坏的。**调用方必须把这件事说出来**,不许静默。
     */
    fun motionFile(ctx: Context, rel: String): File? {
        if (!WardrobeMath.safeLibraryPath(rel)) return null
        val base = motionLibraryDir(ctx) ?: return null
        return try {
            val f = File(base, rel)
            if (!f.isFile || !f.canRead() || f.length() <= 0L) return null
            val root = base.canonicalPath + File.separator
            if (!f.canonicalPath.startsWith(root)) return null
            f
        } catch (_: Exception) {
            null
        }
    }

    /** 他上次在那一栏里点的那条。**从没挑过就是 null** —— 那时行为和今天完全一样。 */
    fun chosenMotion(ctx: Context): String? = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MOTION, null)
    } catch (_: Exception) {
        null
    }

    /**
     * 记下他的选择。
     *
     * ★ 用 `apply()` 就够(和免打扰时段同一条理由):这里没有 `stopSelf()`,
     *   不存在「写着盘的时候进程没了」那个竞态 —— 那条 `commit()` 的规矩是给
     *   她「让她睡」那一步准备的,别照搬过来。
     *
     * ★ `rel == null` = 回到「按 `motion/` 排第一那个」—— 也就是**今天的行为**。
     */
    fun setChosenMotion(ctx: Context, rel: String?) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_MOTION, rel?.takeIf { it.isNotBlank() })
                .apply()
        } catch (_: Exception) {
        }
    }

    /**
     * 这一轮她该用哪条动作 —— **挑过的优先,没挑过的照旧**。
     *
     * ★ 返回值就是 [Hit],调用方拿到的形状和 [motion] 一模一样,不用分两条路。
     * ★ 挑过的那条**不见了**(他删了 / 改过名)时:**退回 `motion/` 那套,而且要说出来** ——
     *   不说的话症状是「我明明选过,它却自己变回去了」,而那是这个项目最恨的那种静默。
     */
    fun activeMotion(ctx: Context): Hit? {
        val want = chosenMotion(ctx)
        if (want == null) return motion(ctx)
        val f = motionFile(ctx, want)
        if (f != null) return Hit(f, "动作:用他挑的 $want(${f.length()} 字节)")
        // ★ 挑过的那条没了:说清楚,再退回去
        val fallback = motion(ctx)
        val why = "动作:他挑的 $want 不在了(删了/改名了/是坏文件),退回 motion/ 那套"
        return if (fallback == null) Hit(null, why) else Hit(fallback.file, why + " —— " + fallback.note)
    }

    /** 贴图那条:按名字精确找,先在 `room/` 再在 `person/`。 */
    private fun byName(ctx: Context, name: String): Hit? {
        for ((dir, dirName) in listOf(roomDir(ctx) to DIR_ROOM, personDir(ctx) to DIR_PERSON)) {
            val f = dir?.let { File(it, name) } ?: continue
            if (f.isFile && f.canRead() && f.length() > 0L) {
                return Hit(f, "素材:用 $name($dirName/)")
            }
        }
        return null
    }

    /**
     * 把两个目录建出来,各放一份说明。**每次进房间都调一次**,幂等、很便宜。
     *
     * ★ 目录**必须由 App 自己建**:他拿文件管理器不一定能进
     *   `Android/data/...`(Android 11+ 系统就在挡),而 App 建好了他至少能看到。
     *   建不出来**不报错** —— 换装是可选功能,不能因为它挡了她出场。
     */
    fun ensure(ctx: Context) {
        for ((dir, text) in listOf(
            personDir(ctx) to README_PERSON,
            roomDir(ctx) to README_ROOM,
            motionDir(ctx) to README_MOTION,
        )) {
            try {
                if (dir == null) continue
                if (!dir.isDirectory && !dir.mkdirs()) continue
                val readme = File(dir, "README.txt")
                // 他改过就别再覆盖 —— 那份说明是给他写笔记用的地方
                if (!readme.isFile) readme.writeText(text, Charsets.UTF_8)
            } catch (_: Exception) {
                // 换装坏了不该让她出不了场
            }
        }
    }

    private val README_PERSON = """
        ConMarn · 换人物形象
        ====================

        把一个人物模型(.vrm)拷进这个文件夹,然后**重开一次 App**。
        就这样 —— 不用改代码,不用重装。

          · 文件名随意。叫「新人物.vrm」也行,它只认扩展名。
          · 文件夹里**只放一个**。放两个的话会用按名字排在前面那个,
            并且把「还有谁没用上」写进日志 —— 所以放两个不会坏,但别放两个。
          · 文件坏掉 / 是 0 字节的话,会明说原因并**退回内置那个人**,
            不会给你一个空房间。

        在哪看它到底用了谁:
          /sdcard/Android/media/com.conmarn.app/model.log
          搜「换装:」三个字。

        注意
          · 只认 .vrm(VRM 0.x / 1.x 都行)。
          · 想换回内置那个人:把这个文件夹清空就行。
          · 这个文件夹里除模型之外的东西不会被读取。
        """.trimIndent()

    private val README_ROOM = """
        ConMarn · 换房间布置
        ====================

        两种玩法,可以只用一种,也可以两个一起用。

        【一】换整个房间的模型
          把模型(.glb 或 .gltf)拷进这个文件夹,**文件名随意** ——
          它只认扩展名。文件夹里**只放一个**,放两个会用按名字排在前面那个。
          连同它引用的贴图(floor.png 之类)一起拷进来,贴图的名字要
          和在模型里写的一致(贴图是按名字找的)。
          然后重开一次 App。

          · 单位是**米**,原点在**她脚下**,和她站的位置对齐。
            如果做出来是「一堵墙贴在她脸上」,多半就是尺寸没按米算。
          · 用了房间模型之后,那层雾会关掉(不然几米宽的屋子会被糊没),
            想换回原来的背景:把这个模型删掉、重开。

        【二】只调摆法(不动模型)
          在这个文件夹里放一个 room.json:

          {
            "background": "#160f13",
            "camera": { "distance": 1.0, "height": 1.0 },
            "objects": {
              "<物件的 id>": { "x": 0.6, "y": 0.74, "z": 0.12, "scale": 1.0 }
            }
          }

          · 只写你想改的那几项,别的留空(或者整段不写)就是不变。
          · background:把房间的**调子**换成这个颜色,要写 "#rrggbb" 这种六位。
          · camera 的 distance / height 是**倍数**,1.0 就是现在这样。
            拉远写 1.3,想高一点看她写 1.1;范围夹在 0.3 ~ 3.0。
            ★ 写倍数不是写米 —— 以后换了身高不同的模型,取景会自己跟着走。
          · objects 的 id 就是房间里那几件东西的 id,在日志里
            「房间物件:」那一行能看到,**连同它现在摆在哪**:
              她那边: 房间物件: 1 件 [<id>(0.62,0.74,0.12)]
            括号里就是 x,y,z —— 想挪就照着这组数字改,不用猜。
            (那一行是**她那边**报上来的,所以前面带「她那边:」四个字;
             直接搜「房间物件」四个字就能找到。房间没加载出来的话这一行不会有。)
          · 这一段**不会**增减物件 —— 房间里摆什么由「她有几只手」决定,
            不由这份文件决定。想加东西,是给她加一只手,不是在这儿写一行。

        在哪看它到底用了什么:
          /sdcard/Android/media/com.conmarn.app/model.log
          搜「换装:」三个字。那份文件**在**却没生效的话,原因也写在那儿。

        注意
          · 这个文件夹里只有模型、贴图、room.json 会被读取;别的文件一律不看。
            (尤其:往里放 .js / .html 不会被读取,也不会生效。)
          · 想换回原样:清空这个文件夹,重开一次 App。
        """.trimIndent()

    private val README_MOTION = """
        ConMarn · 她的动作
        ==================

        把一份动作文件(.vrma)拷进这个文件夹,然后**重开一次 App**。
        她就会一直做那个动作 —— 而不是像现在这样站着不动。

          · 文件名随意,只认扩展名 .vrma。
          · 文件夹里**只放一个**。放两个的话用按名字排在前面那个,
            并且把「还有谁没用上」写进日志。
          · 文件坏掉 / 是 0 字节的话,会明说原因并**退回她自带的待机姿势**,
            不会让她僵住。

        .vrma 是什么、去哪找
          VRM 的动作和人物是**分开的两个文件**:
            人(.vrm)只管长什么样、有什么表情;
            动作(.vrma)只管胳膊腿怎么动 —— 而且**它和人物无关**,
            同一条动作换个人也能用。所以今天没放,她就一直站在同一个姿势上。

          免费的动作可以在 BOOTH 上找,搜「VRMA」或「立ちモーション」,
          下载前看清作者写的授权(能不能商用、要不要署名)。
          导出时**必须选 .vrma**;如果下到的是 .fbx / .bvh,
          那是给别的软件用的,这里认不了。

        在哪看它到底用了哪条:
          /sdcard/Android/media/com.conmarn.app/model.log
          搜「换装:」三个字。认不出来 / 读不到,原因也写在那儿。

        注意
          · 只认 .vrma。这个文件夹里别的东西一律不看。
          · 她脸上和嘴上的东西(眨眼、表情、口型)**不受它影响** ——
            动作只管身体,说话时的嘴还是她自己控制的。
          · 想换回原样:清空这个文件夹,重开一次 App。
        """.trimIndent()
}
