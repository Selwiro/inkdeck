package ink.inkdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 墨水屏配色：只有墨色与纸色两个值，不引入中间灰阶。
 *
 * 2026-11-18 起不再提供反色/白字黑底，固定白底黑字；对比度档位只改变墨色深浅。
 */
data class InkColors(
    val ink: Color,
    val paper: Color,
)

val LocalInkColors = staticCompositionLocalOf { InkColors(Color.Black, Color.White) }

/** 正文基准字号：默认按屏幕宽度档位取 18/22/26sp，用户可在设置页覆盖。 */
val LocalInkBodySize = staticCompositionLocalOf<TextUnit> { 18.sp }

/**
 * 字体缩放系数 = 当前正文字号 / 18。
 *
 * 18sp 时为 1.0（小屏版式完全不变）；22/26sp 时标题、菜单、按钮、行高和
 * 分页区按比例放大，避免 10 寸屏上「正文很大、菜单很小」的割裂感。
 */
val LocalInkScale = staticCompositionLocalOf { 1f }

/**
 * 对比度档位对应的墨色。默认纯黑；柔和档只是把墨色降为深灰，纸色仍是纯白。
 */
private val INK_ON_LIGHT = listOf(Color.Black, Color(0xFF333333), Color(0xFF555555))

@Composable
fun InkTheme(
    bodySize: Int = 18,
    contrast: Int = 0,
    content: @Composable () -> Unit,
) {
    val colors = InkColors(
        ink = INK_ON_LIGHT[contrast.coerceIn(0, INK_ON_LIGHT.lastIndex)],
        paper = Color.White,
    )
    val scale = bodySize / 18f
    CompositionLocalProvider(
        LocalInkColors provides colors,
        LocalInkBodySize provides bodySize.sp,
        LocalInkScale provides scale,
    ) {
        Box(Modifier.fillMaxSize().background(colors.paper)) {
            content()
        }
    }
}
