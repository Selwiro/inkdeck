package ink.inkdeck.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 墨水屏文本。正文最小 18sp，禁止使用带动画或半透明的样式。
 */
@Composable
fun InkText(
    text: String,
    modifier: Modifier = Modifier,
    /** 18sp 基准字号；实际显示时乘以 LocalInkScale。传 null 表示直接使用正文字号。 */
    size: TextUnit? = null,
    weight: FontWeight = FontWeight.Normal,
    color: Color? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val ink = color ?: LocalInkColors.current.ink
    val resolved = size?.let { it * LocalInkScale.current } ?: LocalInkBodySize.current
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = ink, fontSize = resolved, fontWeight = weight),
        maxLines = maxLines,
        overflow = overflow,
    )
}

/**
 * 墨水屏按钮：无涟漪、无按下动画，仅描边 + 最小 48dp 触控高度。
 */
@Composable
fun InkButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textSize: TextUnit = 18.sp,
) {
    val ink = LocalInkColors.current.ink
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = inkDp(48.dp))
            .border(width = maxOf(1f, LocalInkScale.current).dp, color = ink)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = inkDp(14.dp), vertical = inkDp(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        InkText(text = label, size = textSize, weight = FontWeight.Medium)
    }
}
