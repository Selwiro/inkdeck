package ink.inkdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.inkdeck.R

/**
 * 全应用共用的版式：20dp 页边距、22sp 页标题、14sp 辅助信息、1dp 分隔线。
 *
 * 之前各页自己写 24/28/30/32dp 边距和 28/30/32sp 标题，按钮又全是 2dp 满框，
 * 一屏里出现太多粗细不一的黑框。这里把「页框、标题、分隔线、按钮、导航」统一成一套，
 * 页面只负责内容，不再各自决定留白和描边。
 */
object InkDims {
    val pagePadding = 20.dp
    val topBarMinHeight = 64.dp
    val navBarHeight = 60.dp
    val rowMinHeight = 64.dp
    val touchMinHeight = 48.dp
}

/** 18sp 为 1.0；26sp 时标题/菜单/行高/留白一起放大，小屏 18sp 完全不变。 */
@Composable
internal fun inkDp(base: Dp): Dp = base * LocalInkScale.current

/** 顶层页的底部导航项。顺序就是屏幕上的从左到右。 */
enum class InkTab(@StringRes val labelRes: Int) {
    DECKS(R.string.tab_decks),
    IMPORT(R.string.tab_import),
    STATS(R.string.tab_stats),
    SETTINGS(R.string.tab_settings),
}

/**
 * 顶部标题栏：可选的返回箭头、标题、副标题、右侧状态。
 * 下面一条 1dp 分隔线，和正文分开。
 */
@Composable
fun InkTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = inkDp(InkDims.topBarMinHeight))
                .padding(horizontal = inkDp(InkDims.pagePadding), vertical = inkDp(10.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                InkTouchText("←", onClick = onBack, textSize = 28.sp)
                Spacer(Modifier.width(6.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                InkText(
                    text = title,
                    size = 22.sp,
                    weight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    InkText(
                        text = subtitle,
                        size = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(6.dp))
                trailing()
            }
        }
        InkDivider()
    }
}

/** 顶层页共用底部导航：文字 + 选中项下方 3dp 墨线，不画按钮框。 */
@Composable
fun InkNavBar(current: InkTab, onSelect: (InkTab) -> Unit) {
    val ink = LocalInkColors.current.ink
    Column {
        InkDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(inkDp(InkDims.navBarHeight)),
        ) {
            InkTab.entries.forEach { tab ->
                val selected = tab == current
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickableWithoutRipple { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    InkText(
                        text = stringResource(tab.labelRes),
                        size = 16.sp,
                        weight = FontWeight.Normal,
                    )
                    Spacer(Modifier.height(inkDp(4.dp)))
                    Box(
                        modifier = Modifier
                            .width(inkDp(24.dp))
                            .height(inkDp(3.dp))
                            .background(if (selected) ink else Color.Transparent),
                    )
                }
            }
        }
    }
}

/** 页面骨架：顶栏 + 吃满剩余高度的正文 + 可选底部操作区。 */
@Composable
fun InkPageScaffold(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        InkTopBar(title = title, subtitle = subtitle, onBack = onBack, trailing = trailing)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = inkDp(InkDims.pagePadding)),
            content = content,
        )
        bottomBar()
    }
}

/** 1dp 墨色分隔线，取代满屏的 2dp 边框。 */
@Composable
fun InkDivider() {
    val ink = LocalInkColors.current.ink
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(ink),
    )
}

/** 主操作：实心墨底 + 纸色文字。整页只有一个，用来建立层级。 */
@Composable
fun InkPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textSize: TextUnit = 18.sp,
) {
    val colors = LocalInkColors.current
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = inkDp(52.dp))
            .background(colors.ink)
            .clickableWithoutRipple(onClick)
            .padding(horizontal = inkDp(16.dp), vertical = inkDp(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        InkText(
            text = label,
            size = textSize,
            weight = FontWeight.Medium,
            color = colors.paper,
            maxLines = 1,
        )
    }
}

/** 次要操作：1dp 细框，不再用 2dp 粗框。 */
@Composable
fun InkOutlineButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textSize: TextUnit = 18.sp,
    maxLines: Int = 1,
) {
    val ink = LocalInkColors.current.ink
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = inkDp(48.dp))
            .border(width = maxOf(1f, LocalInkScale.current).dp, color = ink)
            .clickableWithoutRipple(onClick)
            .padding(horizontal = inkDp(14.dp), vertical = inkDp(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        InkText(text = label, size = textSize, weight = FontWeight.Medium, maxLines = maxLines)
    }
}

/** 纯文字操作（分页、返回等）：48dp 触控区，不加框。 */
@Composable
fun InkTouchText(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textSize: TextUnit = 16.sp,
    selected: Boolean = false,
) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = inkDp(InkDims.touchMinHeight))
            .clickableWithoutRipple(onClick)
            .padding(horizontal = inkDp(10.dp), vertical = inkDp(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        InkText(
            text = label,
            size = textSize,
            weight = FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * 底部贴边操作：无外框、左右贴边、底部贴底，只有外层的一条顶部分隔线。
 * 学习页的「显示答案」、完成页的「返回牌组」都用它，避免边缘再画一圈线。
 */
@Composable
fun InkBarAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textSize: TextUnit = 18.sp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(inkDp(56.dp))
            .clickableWithoutRipple(onClick),
        contentAlignment = Alignment.Center,
    ) {
        InkText(text = label, size = textSize, weight = FontWeight.Medium, maxLines = 1)
    }
}

/** 列表行：最小 64dp，底部一条分隔线。 */
@Composable
fun InkListRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Column {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = inkDp(InkDims.rowMinHeight))
                .clickableWithoutRipple(onClick)
                .padding(vertical = inkDp(10.dp)),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        InkDivider()
    }
}

/** 分区标题。 */
@Composable
fun InkSectionTitle(text: String) {
    InkText(text = text, size = 14.sp, weight = FontWeight.Medium)
}

/** 键值行：统计/结果用，左标签右数值，底部细线。 */
@Composable
fun InkKeyValueRow(
    label: String,
    value: String,
    valueWeight: FontWeight = FontWeight.Medium,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = inkDp(48.dp))
            .padding(vertical = inkDp(14.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InkText(
            text = label,
            modifier = Modifier.weight(1f),
            size = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        InkText(text = value, size = 18.sp, weight = valueWeight, maxLines = 1)
    }
    InkDivider()
}

/** 数字指标：大数字 + 单位 + 小标签。2×2 网格由 [InkMetricGrid] 提供。 */
@Composable
fun InkMetricTile(
    label: String,
    value: String,
    unit: String = "",
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = inkDp(16.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        InkText(
            text = label,
            size = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(inkDp(4.dp)))
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center,
        ) {
            InkText(
                text = value,
                size = 30.sp,
                weight = FontWeight.Bold,
                maxLines = 1,
            )
            if (unit.isNotBlank()) {
                Spacer(Modifier.width(inkDp(4.dp)))
                InkText(text = unit, size = 14.sp, maxLines = 1)
            }
        }
    }
}

/** 2×2 指标网格：用 1dp 细线分隔，不画四个独立卡片框。 */
@Composable
fun InkMetricGrid(
    first: @Composable RowScope.() -> Unit,
    second: @Composable RowScope.() -> Unit,
) {
    val ink = LocalInkColors.current.ink
    Column {
        InkDivider()
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), content = first)
        InkDivider()
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), content = second)
        InkDivider()
    }
}

/** 网格中间的竖向分隔线。 */
@Composable
fun InkVerticalDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(LocalInkColors.current.ink),
    )
}

/** 分段选择：选中项实心，未选中项细框。 */
@Composable
fun InkChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalInkColors.current
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = inkDp(48.dp))
            .background(colors.paper)
            .border(
                width = if (selected) inkDp(2.dp) else maxOf(1f, LocalInkScale.current).dp,
                color = colors.ink,
            )
            .clickableWithoutRipple(onClick)
            .padding(horizontal = inkDp(8.dp), vertical = inkDp(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                InkText(text = "●", size = 10.sp, maxLines = 1)
                Spacer(Modifier.width(inkDp(4.dp)))
            }
            InkText(
                text = label,
                size = 17.sp,
                weight = FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

/** 统一的无涟漪点击。 */
private fun Modifier.clickableWithoutRipple(onClick: () -> Unit): Modifier =
    composed {
        val interactionSource = remember { MutableInteractionSource() }
        clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    }
