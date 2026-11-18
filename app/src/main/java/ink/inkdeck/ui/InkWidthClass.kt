package ink.inkdeck.ui

/**
 * 屏幕宽度分档：决定用户没有手动选过字号时的默认正文字号。
 *
 * 断点按 dp 宽度划分，不按英寸：6 寸设备都在 571–576dp，手机形态 408–436dp，
 * 6.8–8 寸 653–768dp，10–10.3 寸 906–989dp，13.3 寸 1277–1702dp。
 * 设备实测与分档依据见 docs/eink/devices.md。
 *
 * 默认值只作用于「首次使用/没改过字号」的场景；用户在设置页选过三档字号后，
 * 以用户选择为准并持久化，换屏幕宽度也不会把它改回去。
 */
enum class InkWidthClass(val minWidthDp: Int, val defaultBodySizeSp: Int) {
    /** 手机形态与 6 寸阅读器（< 600dp）。 */
    Compact(0, 18),

    /** 6.8–8 寸（600–839dp）。 */
    Medium(600, 22),

    /** 10–10.3 寸（840–1199dp）；InkPad X 实测 906dp 落在这里。 */
    Expanded(840, 26),

    /** 13.3 寸及以上（≥ 1200dp）；字号不再继续放大，与 [Expanded] 同为 26sp。 */
    ExtraLarge(1200, 26),
}

/** 按窗口宽度（dp）取分档，断点向左闭右开：600 归中屏，840 归大屏，1200 归超大屏。 */
fun inkWidthClassOf(widthDp: Int): InkWidthClass = when {
    widthDp >= InkWidthClass.ExtraLarge.minWidthDp -> InkWidthClass.ExtraLarge
    widthDp >= InkWidthClass.Expanded.minWidthDp -> InkWidthClass.Expanded
    widthDp >= InkWidthClass.Medium.minWidthDp -> InkWidthClass.Medium
    else -> InkWidthClass.Compact
}

/** 宽度档位对应的默认正文 sp：小屏 18、中屏 22、大屏与超大屏 26。 */
fun defaultBodySizeSp(widthDp: Int): Int = inkWidthClassOf(widthDp).defaultBodySizeSp
