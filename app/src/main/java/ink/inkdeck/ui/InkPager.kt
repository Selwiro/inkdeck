package ink.inkdeck.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import ink.inkdeck.R

/**
 * 分页条：不做无限滚动，一屏固定条目，翻页即整页重绘。
 *
 * 只有一页时整条隐藏——之前「已是第一页 1/1 已是最后一页」占掉一行，纯噪音。
 * 到头的方向不放按钮，改成静态提示。
 */
@Composable
fun PagerBar(
    page: Int,
    pageCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pageCount <= 1) return
    val safePageCount = maxOf(pageCount, 1)
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = inkDp(48.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (page > 1) {
            InkTouchText(label = stringResource(R.string.page_prev), onClick = onPrev)
        } else {
            InkText(text = stringResource(R.string.page_first), size = 14.sp)
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            InkText(
                text = stringResource(R.string.page_indicator, page, safePageCount),
                size = 16.sp,
                weight = FontWeight.Medium,
            )
        }
        if (page < safePageCount) {
            InkTouchText(label = stringResource(R.string.page_next), onClick = onNext)
        } else {
            InkText(text = stringResource(R.string.page_last), size = 14.sp)
        }
    }
}

/** 兼容旧页面的一行可点击条目；新代码优先用 [InkListRow]（自带分隔线）。 */
@Composable
fun InkRow(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        content()
    }
}
