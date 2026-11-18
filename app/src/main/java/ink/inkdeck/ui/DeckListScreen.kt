package ink.inkdeck.ui

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import ink.inkdeck.R
import ink.inkdeck.data.AnkiRepository
import ink.inkdeck.data.DeckNode

/**
 * 首页牌组列表：一屏固定 6 个牌组，分页翻。
 *
 * 版式规则见 docs/eink/browsing.md：无滚动、无动画、每行 64dp 以上、音量键翻页。
 * 顶层导航由 MainActivity 的 InkNavBar 提供，这里只负责列表本身。
 */
@Composable
fun DeckListScreen(
    repository: AnkiRepository,
    page: Int,
    onPageChange: (Int) -> Unit,
    onOpenDeck: (DeckNode) -> Unit,
    onImport: () -> Unit,
) {
    var decks by remember { mutableStateOf<List<DeckNode>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        decks = null
        error = null
        runCatching { repository.deckRows() }
            .onSuccess { decks = it }
            .onFailure { error = it.message ?: it::class.simpleName }
    }

    // 18sp 小屏保持 6 行；大字号按屏幕高度降为 5/4/3，避免 6 寸屏溢出。
    // 10 寸（1208dp）26sp 仍是 5 行；小屏只在大字号时减少行数。
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
    val allDecks = decks
    val pageCount = if (allDecks.isNullOrEmpty()) 0 else (allDecks.size + perPage - 1) / perPage
    val safePage = page.coerceIn(1, maxOf(1, pageCount))
    val visible = allDecks?.drop((safePage - 1) * perPage)?.take(perPage).orEmpty()

    // 数据变少（例如牌组被删）时把外部页码夹回有效范围。
    // 注意不能在 decks == null（首帧加载中）时夹：那时 pageCount 还是 0，
    // 会把从学习页返回时的第 2 页误重置成第 1 页。
    LaunchedEffect(allDecks, page) {
        if (!allDecks.isNullOrEmpty() && page > pageCount) {
            onPageChange(pageCount)
        }
    }

    DisposableEffect(safePage, pageCount) {
        HardwareKeys.onVolumeDown = {
            if (safePage < pageCount) {
                onPageChange(safePage + 1)
            }
            // 已在最后一页也消费按键，避免漏给系统弹出音量面板。
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

    InkPageScaffold(title = stringResource(R.string.app_name)) {
        when {
            error != null -> {
                Spacer(Modifier.height(20.dp))
                InkText(text = stringResource(R.string.read_decks_failed), size = 20.sp, weight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                InkText(text = error.orEmpty(), size = 14.sp)
                Spacer(Modifier.height(16.dp))
                InkOutlineButton(label = stringResource(R.string.retry), onClick = { reloadKey++ })
            }

            allDecks == null -> {
                Spacer(Modifier.height(20.dp))
                InkText(text = stringResource(R.string.reading_decks), size = 18.sp)
            }

            allDecks.isEmpty() -> {
                Spacer(Modifier.height(28.dp))
                InkText(text = stringResource(R.string.empty_decks_title), size = 22.sp, weight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                InkText(text = stringResource(R.string.empty_decks_hint), size = 16.sp)
                Spacer(Modifier.height(20.dp))
                InkPrimaryButton(label = stringResource(R.string.import_deck_package), onClick = onImport)
            }

            else -> {
                Spacer(Modifier.height(inkDp(4.dp)))
                visible.forEach { deck ->
                    DeckRow(deck = deck, onClick = { onOpenDeck(deck) })
                }
                Spacer(Modifier.weight(1f))
                PagerBar(
                    page = safePage,
                    pageCount = pageCount,
                    onPrev = { onPageChange(safePage - 1) },
                    onNext = { onPageChange(safePage + 1) },
                )
            }
        }
    }
}

@Composable
private fun DeckRow(deck: DeckNode, onClick: () -> Unit) {
    InkListRow(onClick = onClick) {
        Spacer(Modifier.width(inkDp((deck.level.coerceAtMost(3) * 16).dp)))
        Column(modifier = Modifier.weight(1f)) {
            InkText(
                text = deck.leafName,
                size = 18.sp,
                weight = if (deck.level == 0) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            InkText(
                text = stringResource(
                    R.string.deck_counts,
                    deck.newCount,
                    deck.learnCount,
                    deck.reviewCount,
                ),
                size = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            InkText(
                text = "${deck.dueCount}",
                size = 22.sp,
                weight = FontWeight.Bold,
                maxLines = 1,
            )
            InkText(text = stringResource(R.string.due_label), size = 12.sp, maxLines = 1)
        }
    }
}
