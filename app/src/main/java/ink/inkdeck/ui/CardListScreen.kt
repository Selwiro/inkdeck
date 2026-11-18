package ink.inkdeck.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.inkdeck.R
import ink.inkdeck.data.AnkiRepository
import ink.inkdeck.data.CardPage
import ink.inkdeck.data.CardRow
import ink.inkdeck.data.DeckNode

/**
 * 卡片列表：一页固定 5~6 张，只显示问题面预览；点进去看整张卡。
 *
 * 分页数据由仓库层一次取一页（后端搜索 + 渲染），UI 不保留全量卡片，500 张以上也不涨内存。
 * 版式：顶栏返回 + 牌组名 + 页信息；列表用 1dp 分隔线；只有一页时隐藏分页条。
 */
@Composable
fun CardListScreen(
    repository: AnkiRepository,
    deck: DeckNode,
    page: Int,
    onPageChange: (Int) -> Unit,
    onBack: () -> Unit,
    onOpenCard: (CardRow) -> Unit,
) {
    // 18sp 保持 6 行；大字号按屏幕高度降行，10 寸 26sp 仍为 5 行。
    val scale = LocalInkScale.current
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val perPage = if (scale <= 1.01f) {
        6
    } else {
        when {
            screenHeight >= 1000 -> 5
            screenHeight >= 800 -> 4
            else -> 3
        }
    }
    var cardPage by remember { mutableStateOf<CardPage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(deck.id, page, reloadKey) {
        cardPage = null
        error = null
        runCatching { repository.cardsPage(deck.fullName, page, perPage) }
            .onSuccess {
                cardPage = it
                if (it.page != page) onPageChange(it.page)
            }
            .onFailure { error = it.message ?: it::class.simpleName }
    }

    val current = cardPage
    val pageCount = current?.pageCount ?: 0
    val safePage = page.coerceIn(1, maxOf(1, pageCount))

    LaunchedEffect(current, page) {
        if (current != null && page > pageCount) {
            onPageChange(pageCount.coerceAtLeast(1))
        }
    }

    DisposableEffect(safePage, pageCount) {
        HardwareKeys.onVolumeDown = {
            if (safePage < pageCount) {
                onPageChange(safePage + 1)
            }
            // 最后一页也消费按键，别让系统弹出音量面板。
            true
        }
        HardwareKeys.onVolumeUp = {
            if (safePage > 1) {
                onPageChange(safePage - 1)
            }
            true
        }
        onDispose {
            HardwareKeys.onVolumeDown = null
            HardwareKeys.onVolumeUp = null
        }
    }

    InkPageScaffold(
        title = deck.leafName,
        subtitle = current?.let {
            stringResource(
                R.string.cards_page_info,
                it.total,
                it.page,
                maxOf(it.pageCount, 1),
            )
        } ?: stringResource(R.string.reading_cards),
        onBack = onBack,
    ) {
        Spacer(Modifier.height(inkDp(4.dp)))
        when {
            error != null -> {
                Spacer(Modifier.height(inkDp(16.dp)))
                InkText(text = stringResource(R.string.read_cards_failed), size = 20.sp, weight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                InkText(text = error.orEmpty(), size = 14.sp)
                Spacer(Modifier.height(inkDp(16.dp)))
                InkOutlineButton(label = stringResource(R.string.retry), onClick = { reloadKey++ })
            }

            current == null -> {
                Spacer(Modifier.height(inkDp(16.dp)))
                InkText(text = stringResource(R.string.reading_cards), size = 18.sp)
            }

            current.cards.isEmpty() -> {
                Spacer(Modifier.height(inkDp(16.dp)))
                InkText(text = stringResource(R.string.no_cards), size = 18.sp)
            }

            else -> {
                current.cards.forEach { row ->
                    InkListRow(onClick = { onOpenCard(row) }) {
                        InkText(
                            text = row.title,
                            modifier = Modifier.weight(1f),
                            size = 18.sp,
                            weight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(12.dp))
                        InkText(text = "›", size = 20.sp, maxLines = 1)
                    }
                }
                Spacer(Modifier.weight(1f))
                PagerBar(
                    page = safePage,
                    pageCount = pageCount,
                    onPrev = { onPageChange(safePage - 1) },
                    onNext = { onPageChange(safePage + 1) },
                )
                Spacer(Modifier.height(inkDp(12.dp)))
                InkText(text = stringResource(R.string.card_list_hint), size = 14.sp)
            }
        }
    }
}
