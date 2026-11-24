package com.example.touchpad

/**
 * ★★ 「换装」的判定 —— **换人物形象、换房间布置**,靠往两个目录里丢文件。
 *
 * ## 用户 2026-10-05 选定的形态
 *
 * > 「**放文件就生效** —— 我把新的模型和新房间丢进去,重开一次就好 ——
 * >   **不用改代码、不用重新装 App**」
 *
 * 所以这条路的形状是:两个目录,丢进去,重开。
 * *   `/sdcard/Android/data/com.example.touchpad/files/person/` —— 人物
 * *   `/sdcard/Android/data/com.example.touchpad/files/room/`   —— 房间
 *
 * ## ★ 为什么判定必须在这一层(而不是在 serveAsset 里)
 *
 * `Wardrobe`(那个真去碰文件系统的)在整个项目里是**验不了**的 —— 它要一台手机。
 * 而这一轮(2026-10-05 下午)**手机是拔着的**,我一个字都验不了。
 *
 * 所以规矩照旧建在这个项目最硬的那条惯例上(`MoodMath` / `GreetMath` /
 * `EarSessionMath` / `HandMath` / `SpeechMath`):**能判定的事情全抽成零 Android 依赖的纯逻辑**,
 * 真 I/O 那一层薄到「只剩 open/read」。
 * **不能单测的代码在这里等于不可信**([WardrobeMathTest] 钉住下面每一条)。
 *
 * ## ★★ 这一节里最要紧的一条:白名单不是黑名单
 *
 * 外部目录里的文件会被 serve 进 `https://appassets.androidplatform.net/` 这个**源**,
 * 而那个源上挂着 `HerBridge`(Kotlin 侧的 `@JavascriptInterface`)。
 *
 * **一个 `.html` 或 `.js` 掉进去,就等于在自己的源里执行任意脚本、并且够得着那个桥。**
 * 那不是「换装」,那是这个 App 上开的一个后门 —— 而它长得和换装一模一样。
 *
 * 所以 [ALLOWED_EXTS] **只准列出「数据」**:模型、贴图、`.json`。
 * `.html` / `.js` / `.css` / `.svg` **一个都不在里面**,而且判据是**白名单**
 * (认不出来的一律不放行),不是「把危险的排掉」——
 * 后者漏一个就是上面那件事(`README.md` 里也是这么写给用户看的)。
 *
 * 这和项目既有的两条规矩同族:**宁可漏不可错**([FastPath] 那条)、**fail closed**
 * ([ApiMath.sendsOk] 那条)。
 */
internal object WardrobeMath {

    /** 人物模型认这几个扩展名。今天只有 `.vrm` —— 留成 List 是因为判定要按它写,不是预留。 */
    val MODEL_EXTS = listOf("vrm")

    /** 房间模型认这几个。`.glb` 是二进制、`.gltf` 是文本,**两个都收**。 */
    val SCENERY_EXTS = listOf("glb", "gltf")

    /**
     * 她的动作认这一个。
     *
     * `.vrma` 是 **VRM Animation**,里面装的是「骨骼每一帧转到哪」——
     * 换句话说它和 `.glb` 一样是个 glTF 容器,**是数据,不是程序**:
     * 它里面没有一行会被执行的代码(见 [ALLOWED_EXTS] 那条判据)。
     *
     * ★ **别把它并进 [MODEL_EXTS]** —— 那会把动作文件当成「她本人」挑出来,
     * 于是「换动作」变成了「把她换掉」,而她会长成一堆骨骼挂在空中。
     * 三种扩展名各自一张表,是因为它们**放在三个不同的目录**里(见 [Wardrobe])。
     */
    val MOTION_EXTS = listOf("vrma")

    /**
     * ★★ 外部目录里的文件,**只有**这几种会被 serve。
     *
     * 判据是白名单:不在表里的一律当「不存在」,连读都不读。
     * 特别地 —— **`.html` / `.js` / `.css` / `.svg` 永远不在表里**,
     * 原因见文件头。**别往里加。**
     *
     * ★ `vrma` 在这里是安全的,理由和 `glb` 一样:它是个**装着数字的容器**,
     *   浏览器拿到它只会去读动画轨道,绝不会去执行什么。
     *   (判据始终是「它会不会被执行」,不是「它是谁家的格式」。)
     */
    val ALLOWED_EXTS: Set<String> = setOf(
        "vrm",
        "glb", "gltf", "bin",
        "png", "jpg", "jpeg", "webp",
        // ★ 2026-11-07:窗外的天景「换整套」时可以是**视频**(他提议的:一段雨落下来的循环)。
        //   判据照旧是「它会不会被执行」—— mp4 / webm 喂给 `<video>` 只会被解码成画面,
        //   和 png 是同一类东西。★ 别顺手把 `.svg` 加进来,那个能带脚本。
        "mp4", "webm",
        "json",
        "vrma",
    )

    /** 目录里一个候选文件的样子。只留判定用得上的东西,这样它能纯逻辑单测。 */
    data class Candidate(
        val name: String,
        /** 字节数。0 当「坏文件」处理 —— 半截下载、写失败都是这个长相。 */
        val bytes: Long,
        /** 读得到吗。`canRead()` 为 false 和「不在」是**两件事**,要分开说。 */
        val readable: Boolean = true,
    )

    /** 一轮判定的结论。**不是布尔** —— 「没挑到」必须带一句人话原因,见 [None.why]。 */
    sealed class Pick {
        /**
         * 挑中了 [name]。
         *
         * ★ [alsoFound] 和 [broken] **必须报出来**:目录里躺两个 `.vrm` 时,
         * 用户以为换的是 B、实际用的是 A —— 这种错**不会以任何形式报错**,
         * 只会表现成「我换了它没变」。把「还有谁」写进日志是唯一能识破它的东西。
         */
        data class Found(
            val name: String,
            val alsoFound: List<String> = emptyList(),
            val broken: List<String> = emptyList(),
        ) : Pick()

        /** 一个能用的都没有。[why] 是人话,直接进日志。 */
        data class None(val why: String) : Pick()
    }

    /** 小写扩展名(不含点)。`a.VRM` 和 `a.vrm` 要认成同一种。 */
    fun extOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    /**
     * 这个文件名允不允许从外部目录 serve。
     *
     * ★ 另挡两条,和扩展名无关:
     * * **点开头的**:Android 自己会往目录里放 `.nomedia` 这类文件;它们不是用户丢的。
     * * **含路径分隔符的**:`a/../b.png` 这种要当场拒掉 —— 判定层拒掉,
     *   比指望下面那层拼接不出错可靠(不给自己开一条「随便读」的口子)。
     */
    fun allowedExternal(name: String): Boolean {
        if (name.isEmpty() || name.startsWith(".")) return false
        if (name.contains('/') || name.contains('\\')) return false
        return extOf(name) in ALLOWED_EXTS
    }

    /**
     * 一个目录里该用哪个文件。
     *
     * 判据按这个顺序,**每一条都对应一种「我换了它没变」的真实长相**:
     *
     * 1. **按扩展名筛**(认 [exts],大小写不敏感)—— 用户会丢进来 `新人物.vrm`,
     *    名字我们猜不到,所以**只认扩展名,不认文件名**。这正是「放文件就生效」;
     * 2. **点开头的不算** —— 那是 Android 自己放进去的,不是用户丢的;
     * 3. **读不到 / 0 字节的挑出去**,但**记在 [Pick.Found.broken] 里** ——
     *    坏文件不能静默顶掉一个好文件,也不能静默变空白;
     * 4. 剩下的**按名字排序取第一个**(确定性:同样的目录永远得同一个结论,
     *    否则「重启一次换一个人」这种事会变成玄学);
     * 5. 同目录里**还有别的** → 记进 [Pick.Found.alsoFound],让日志说出来。
     */
    fun pick(candidates: List<Candidate>, exts: List<String>): Pick {
        val want = exts.map { it.lowercase() }.toSet()
        val byExt = candidates
            .filter { !it.name.startsWith(".") && extOf(it.name) in want }

        if (byExt.isEmpty()) {
            return Pick.None("目录里没有 ${exts.joinToString(" / ") { ".$it" }} 文件")
        }

        val usable = byExt.filter { it.readable && it.bytes > 0L }.sortedBy { it.name }
        val broken = byExt.filter { !(it.readable && it.bytes > 0L) }
            .sortedBy { it.name }
            .map { "${it.name}(${if (!it.readable) "读不到" else "0 字节"})" }

        if (usable.isEmpty()) {
            // ★ 这一条是「明明放对了却说她没换」的头号长相:文件在、名字对、**是坏的**。
            //   不说出来的话,它和「你放错文件夹了」在屏幕上完全一样。
            return Pick.None(
                "找到了 ${byExt.size} 个,但一个都用不了:${broken.joinToString("、")}"
            )
        }

        return Pick.Found(
            name = usable.first().name,
            alsoFound = usable.drop(1).map { it.name },
            broken = broken,
        )
    }

    /**
     * 把一轮判定说成一句人话 —— **这行日志就是「我换了它怎么没变」的唯一分界线**。
     *
     * ★ 所以它**不在这里拼日志前缀**(那是 [Wardrobe] 的事),而是必须**把数量说全**:
     * 「用了 X」不够,「用了 X,另外还有 Y 没用」才是能查错的那句。
     */
    fun sentence(what: String, pick: Pick): String = when (pick) {
        is Pick.None -> "$what:没有可用的 —— ${pick.why}"
        is Pick.Found -> buildString {
            append("$what:用 ${pick.name}")
            if (pick.broken.isNotEmpty()) append(",跳过 ${pick.broken.joinToString("、")}")
            if (pick.alsoFound.isNotEmpty()) {
                append(",没用上 ${pick.alsoFound.joinToString("、")}(同目录只认排第一的那个)")
            }
        }
    }

    // ------------------------------------------------------------------
    // 挑动作那条路(2026-10-09)
    //
    // ★ 它和上面那整套「只认扩展名」是**两件不同的事**,别混:
    //   上面那套回答的是「**她本人 / 房间 / 她该做哪条动作**」—— 只有一个位置,
    //   所以「目录里排第一的那个」就是全部答案;
    //   这里回答的是「**他自己点名要哪一条**」—— 他要的是从一堆里挑,
    //   而「挑」这个动作用户是**在房间里点的**,不是在文件管理器里摆的。
    //
    // ★★ 和「他的设计是他的」那条不冲突:**`motion/` 那套原样一个字不动** ——
    //   没在界面上挑过的人,行为和今天完全一样(见 [Wardrobe.motion])。
    //   这里只是**多给一条路**。
    // ------------------------------------------------------------------

    /** 库目录的名字。和 `motion/` 并列,不顶掉它。 */
    const val DIR_MOTION_LIBRARY = "motion-library"

    /**
     * 「库里的相对路径」(如 `vrm-viewer/Relax.vrma`)允不允许读。
     *
     * ★★ 为什么**不能**只靠 [allowedExternal]:那个函数只看得见 **basename**
     *   (`substringAfterLast('/')`),所以 `a/../../x.vrma` 会**堂堂正正地过**它的关。
     *   这一条补的就是那一段 —— 它管**路径**,[allowedExternal] 管**扩展名**,
     *   两道都要有。
     *
     * 判据(每一条都挡一种真的会发生的写法):
     * 1. **空**(或全是空白)→ 拒;
     * 2. **任何一个 `.` / `..` 段** → 拒 —— 这是唯一能爬出库目录的形状;
     * 3. **反斜杠** → 拒(Windows 风格的写法在这台机上是拼不出路径的,
     *    放过去只会得到一句难懂的「读不到」);
     * 4. **开头是 `/`** → 拒(绝对路径 = 绕开根目录);
     * 5. 最后才问 [allowedExternal] —— **扩展名白名单照旧是唯一的收口**
     *    (所以 `.js` / `.html` 在这儿也进不来,理由同文件头)。
     */
    fun safeLibraryPath(rel: String): Boolean {
        val t = rel.trim()
        if (t.isEmpty()) return false
        if (t.startsWith("/") || t.contains('\\')) return false
        if (t.split('/').any { it == "." || it == ".." || it.isEmpty() }) return false
        return allowedExternal(t.substringAfterLast('/'))
    }

    /** `vrm-viewer/Relax.vrma` → `vrm-viewer`。库里直接躺在根上的返回空串。 */
    fun libraryFolder(rel: String): String =
        rel.trim().substringBeforeLast('/', "").trim('/')

    /**
     * 库里的动作 → **给他看的中文名**。
     *
     * ★ 为什么要这张表:那 31 条动作的文件名**全是英文**(`Relax` / `failed-apology` /
     *   `VRMA_03`)。界面上摆一排英文,他要先在心里翻译一遍才能点 ——
     *   而这一栏的全部价值就是「一眼认出来我想让她做哪个」。
     *
     * ★ 认不出来就**退回文件名本身**(去掉扩展名),不编、不猜 ——
     *   他以后自己丢进来的东西会走这条路,那正是它该有的行为。
     */
    fun motionLabel(rel: String): String {
        val base = rel.trim().substringAfterLast('/').substringBeforeLast('.').trim()
        if (base.isEmpty()) return rel.trim()
        MOTION_LABELS[base.lowercase()]?.let { return it }
        return base
    }

    /**
     * 文件名(小写、不含扩展名)→ 中文名。**只收我们已经确认过内容的那些**
     * (见 `motion-library/README.txt` 里那份逐条清单)—— 没验过的**不写进来**,
     * 因为「写一个我没看过的名字」和「瞎猜」没区别。
     */
    private val MOTION_LABELS: Map<String, String> = mapOf(
        // ── vrm-viewer(MIT):11 条情绪动作,3.9s 循环,最整齐的一套 ──
        "angry" to "生气",
        "blush" to "害羞",
        "clapping" to "鼓掌",
        "goodbye" to "再见",
        "jump" to "跳一下",
        "lookaround" to "环顾",
        "relax" to "放松",
        "sad" to "难过",
        "sleepy" to "困了",
        "surprised" to "惊讶",
        "thinking" to "思考",
        // ── pixiv 官方 VRoid 动作包:幅度最大的 7 条 ──
        "vrma_01" to "全身展示",
        "vrma_02" to "打招呼",
        "vrma_03" to "比耶",
        "vrma_04" to "射击",
        "vrma_05" to "转圈",
        "vrma_06" to "模特站姿",
        "vrma_07" to "深蹲",
        // ── voxavatar(MIT):种类最杂 ──
        "airplane-02" to "伸展(短)",
        "airplane-05" to "伸展(长)",
        "drink-water" to "喝水",
        "exercise-step" to "踏步",
        "failed-apology" to "道歉",
        "idle-01" to "待机(呼吸眨眼)",
        "pose-motion" to "摆姿势",
        "reaction-startle" to "受惊",
        "review-phone" to "看手机",
        "run-slow" to "慢跑",
        "speaking-01" to "说话时的小动作",
        "success-cheer" to "欢呼",
        "walk" to "走路",
        // ── 他原来那条 ──
        "idle_loop" to "她原来的待机",
    )
}
