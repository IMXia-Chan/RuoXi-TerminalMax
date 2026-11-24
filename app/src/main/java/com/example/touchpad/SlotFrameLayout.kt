package com.example.touchpad

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import kotlin.math.max

/**
 * 一个「**铺满的孩子不许撑大爸爸**」的 FrameLayout。
 *
 * ★★ 2026-10-05 立的。用户拿着截图报的第三回:「**你那个房间对话框 ~~~ 还是没好**」。
 * 他上一回的原话是:
 *
 * > 「那个炫酷的 **~~~~~** 要在**透明磨砂玻璃中,而不是全屏**」
 *
 * ## 那时候我只修了一半
 *
 * 我给了他左右各 24dp 的边距(见 [ConMarnActivity.buildBottomBar]),于是这块玻璃
 * **横着**是一颗药丸了。但**竖着**没有 —— 他按住说话那 350 毫秒之后,整根胶囊
 * 当场涨到整块屏,里面那条波的**振幅 = 高度 × 0.3** 也就跟着涨到三百多像素,
 * 于是它看起来还是「铺满整个房间」。截图里那两道横贯全屏的粉/蓝大曲线就是它,
 * 不是装饰,是他按住的那条波。
 *
 * ## 真因:MATCH_PARENT 的孩子在 WRAP_CONTENT 的爸爸里**会反过来定义爸爸**
 *
 * 这是 Android 的一条反直觉规矩,原文在 `ViewGroup.getChildMeasureSpec`:
 * 孩子写 `MATCH_PARENT` 时,它拿到的不是「你爸爸多高」,而是
 * **「你爸爸的父容器还剩多少」** —— 也就是那条 `AT_MOST` 里的数值。
 *
 * 原本的链子是
 *
 * ```
 * bottomBar(wrap) → captionWrap(wrap) → textSlot(这里) → 字幕(wrap) + 水波(MATCH_PARENT)
 * ```
 *
 * 最上面那一层拿到的是 `AT_MOST(1032)`(整屏)。水波是 `MATCH_PARENT`,
 * 于是它被量成 1032 —— 而 FrameLayout 的 `wrap_content` 是「取最高的孩子」,
 * 于是它一层一层把这句话传上去:**一个字都没写的那条波,定义了整根胶囊有多高。**
 *
 * 真机实测(`dumpsys activity top`,2026-10-05):
 *
 * | 状态 | 这一格 | 胶囊 | 药丸在哪 |
 * |---|---|---|---|
 * | 安静时 | 141px | 165px | 屏幕底下 ✓ |
 * | 按住时(坏) | **1026px** | 1050px → 被夹到满屏 | 铺满整屏,↑ 飘到屏幕正中间 |
 *
 * ★ 它**一个异常都不抛、一行日志都不写**:安静的时候量得好好的,只在按住的那几秒
 * 变样 —— 而按住的那几秒正是他盯着看的时候。这类「只在交互中现身」的布局错,
 * 靠读代码几乎看不出来,**是截图和 `dumpsys` 把它揪出来的**。
 *
 * ## 规矩
 *
 * 量高度时**只认老实孩子**(`LayoutParams.height` 不是 `MATCH_PARENT` 的那些),
 * 拿到高度之后再回头把「想铺满的」按这个高度量一遍 —— **它们跟着走,不带头。**
 * 算术在 [SlotMeasure] 里(纯逻辑,JVM 单测跑得动;见 `SlotMeasureTest`)。
 *
 * ⚠️ 副作用要写明白:老实孩子会被量两次。这一格一共两三个孩子、且都是单行文本,
 * 代价可以忽略 —— 换来的是这个类的全部意义。
 */
class SlotFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 爸爸自己已经有准数了(EXACTLY)—— 那就没什么好争的,照原生量。
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val kids = ArrayList<SlotMeasure.Child>(childCount)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams as FrameLayout.LayoutParams
            if (c.visibility == View.GONE) {
                kids.add(SlotMeasure.Child(heightPx = 0, gone = true))
                continue
            }
            if (lp.height == FrameLayout.LayoutParams.MATCH_PARENT) {
                // ★★ 这一句就是整个类存在的理由:**它不量,只记一笔「它想铺满」**。
                //    量了的话,上面那条 AT_MOST 会变成它的高度,它反过来定义了爸爸。
                //    见文件头那张真机数字表。
                kids.add(SlotMeasure.Child(
                    heightPx = 0,
                    topMarginPx = lp.topMargin,
                    bottomMarginPx = lp.bottomMargin,
                    fillsParent = true,
                ))
                continue
            }
            measureChildWithMargins(c, widthMeasureSpec, 0, heightMeasureSpec, 0)
            kids.add(SlotMeasure.Child(
                heightPx = c.measuredHeight,
                topMarginPx = lp.topMargin,
                bottomMarginPx = lp.bottomMargin,
            ))
        }

        val h = SlotMeasure.slotHeight(kids, paddingTop + paddingBottom)
        if (h == SlotMeasure.NO_OPINION) {
            // 一个老实孩子都没有(全都想铺满 / 全 GONE)。这时按上面那条规矩会量成
            // 「只剩内边距」—— 那是**静默塌陷**:水波 0 高,看不见,而且不报错。
            // 与其赌以后不会有人把字幕藏起来,不如退回原生量法:难看,但看得见。
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }
}

/**
 * 「这一格该多高」—— 从 [SlotFrameLayout] 里抽出来的那条算术。
 *
 * ★ 单独抽出来只为一个理由:它错了**不抛异常、不写日志**,只在按住说话的那几秒
 *   变成另一个样子(见 [SlotFrameLayout] 文件头那张表)。**钉它只能靠单测。**
 *
 * 纯净:不认识 `android.view`、不认识 `org.json` —— 和 `HistoryWindow` / `CaptionDwell`
 * 一样的路子,好让 JVM 单测跑得动。
 */
internal object SlotMeasure {

    /**
     * 「没人说了算」。
     *
     * ★ 它是**负数**,故意的:调用方拿它去 `setMeasuredDimension` 会当场抛,
     *   所以谁也不能把它当成一个真高度悄悄用掉 —— 必须显式判一次。
     */
    internal const val NO_OPINION = -1

    /**
     * 一个孩子量完之后,说话算数的那些数。
     *
     * @param heightPx 它自己量出来的高度。`fillsParent = true` 时**这个数是废的**
     *   (它是「爸爸还剩多少」,不是「它想多高」),见 [SlotFrameLayout] 文件头。
     * @param fillsParent 它的 `layout_height` 是不是 `MATCH_PARENT`。
     * @param gone 它是不是 `GONE`。
     */
    internal class Child(
        val heightPx: Int,
        val topMarginPx: Int = 0,
        val bottomMarginPx: Int = 0,
        val fillsParent: Boolean = false,
        val gone: Boolean = false,
    )

    /**
     * 这一格该多高 = **最高的那个老实孩子** + 内边距。
     *
     * @return 高度(px);**一个老实孩子都没有**时返回 [NO_OPINION]。
     */
    internal fun slotHeight(children: List<Child>, paddingPx: Int): Int {
        var h = NO_OPINION
        for (c in children) {
            if (c.gone || c.fillsParent) continue
            h = max(h, c.heightPx + c.topMarginPx + c.bottomMarginPx)
        }
        if (h == NO_OPINION) return NO_OPINION
        return h + paddingPx
    }
}
