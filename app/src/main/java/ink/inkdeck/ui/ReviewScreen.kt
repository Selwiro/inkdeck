package ink.inkdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import ink.inkdeck.R
import ink.inkdeck.data.AnkiRepository
import ink.inkdeck.data.DeckNode
import ink.inkdeck.data.Rating
import ink.inkdeck.data.ReviewCard
import ink.inkdeck.data.ReviewQueue
import ink.inkdeck.data.ReviewSessionStore
import ink.inkdeck.data.ReviewSummary
import ink.inkdeck.ui.html.HtmlContent
import kotlinx.coroutines.launch

/** 复习页状态。 */
private sealed interface ReviewUiState {
    data object Loading : ReviewUiState
    data class Showing(val card: ReviewCard, val counts: ReviewQueue, val answerShown: Boolean) : ReviewUiState
    data class Finished(val summary: ReviewSummary) : ReviewUiState
    data class Failed(val message: String) : ReviewUiState
}

/**
 * 学习模式：只显示卡片本身，问题面/答案面靠内容区分，不写「问题 / 答案」标题，
 * 也不显示内部卡片编号。
 *
 * 调度完全交给 rslib：每次评分把「选中档位的 SchedulingState」原样回传，
 * 间隔/到期时间是后端算好的文案，Kotlin 侧不碰任何算法。
 *
 * 版式：顶栏（返回 + 牌组名 + 余量 + 右上「卡片列表」）+ 正文 + 底部操作
 * （显示答案 / 四档评分，左右贯通、不留边距）。
 */
@Composable
fun ReviewScreen(
    repository: AnkiRepository,
    sessionStore: ReviewSessionStore,
    deck: DeckNode,
    onExit: () -> Unit,
    onBrowseCards: () -> Unit,
) {
    var state by remember { mutableStateOf<ReviewUiState>(ReviewUiState.Loading) }
    var summary by remember { mutableStateOf(sessionStore.summary()) }
    var shownAt by remember { mutableStateOf(System.currentTimeMillis()) }
    // 评分/暂停提交期间锁住输入：后端调用完成前再点一次会重复提交同一张卡。
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun loadNext() {
        state = ReviewUiState.Loading
        runCatching { repository.reviewQueue(limit = 1, deckId = deck.id) }
            .onSuccess { queue ->
                submitting = false
                if (queue.isEmpty) {
                    state = ReviewUiState.Finished(summary)
                } else {
                    shownAt = System.currentTimeMillis()
                    state = ReviewUiState.Showing(queue.cards.first(), queue, answerShown = false)
                }
            }
            .onFailure {
                submitting = false
                state = ReviewUiState.Failed(it.message ?: it::class.simpleName.orEmpty())
            }
    }

    // 换牌组时清零本轮计数；同一牌组再次进入则保留本轮累计。
    LaunchedEffect(deck.id) {
        summary = sessionStore.begin(deck.fullName)
        loadNext()
    }

    fun rate(rating: Rating) {
        if (submitting) return
        val current = state as? ReviewUiState.Showing ?: return
        if (current.card.broken) return
        submitting = true
        val elapsed = (System.currentTimeMillis() - shownAt).coerceAtLeast(0)
        scope.launch {
            runCatching { repository.answerCard(current.card, rating, elapsed) }
                .onSuccess {
                    summary = sessionStore.record(rating)
                    loadNext()
                }
                .onFailure {
                    submitting = false
                    state = ReviewUiState.Failed(it.message ?: it::class.simpleName.orEmpty())
                }
        }
    }

    /** 空卡（填空缺失/模板为空）无法评分，暂停掉继续下一张，避免整副牌卡在这一张。 */
    fun skipBroken() {
        if (submitting) return
        val current = state as? ReviewUiState.Showing ?: return
        if (!current.card.broken) return
        submitting = true
        scope.launch {
            runCatching { repository.suspendCard(current.card.cardId) }
                .onSuccess { loadNext() }
                .onFailure {
                    submitting = false
                    state = ReviewUiState.Failed(it.message ?: it::class.simpleName.orEmpty())
                }
        }
    }

    DisposableEffect(Unit) {
        HardwareKeys.onVolumeDown = {
            val current = state
            when {
                current is ReviewUiState.Showing && current.card.broken -> skipBroken()
                current is ReviewUiState.Showing && !current.answerShown ->
                    state = current.copy(answerShown = true)
                current is ReviewUiState.Showing -> rate(Rating.GOOD)
            }
            // 学习页始终消费音量键，不把按键漏给系统音量面板。
            true
        }
        HardwareKeys.onVolumeUp = {
            val current = state
            if (current is ReviewUiState.Showing && current.answerShown) {
                rate(Rating.AGAIN)
            }
            true
        }
        onDispose {
            HardwareKeys.onVolumeDown = null
            HardwareKeys.onVolumeUp = null
        }
    }

    val current = state
    val counts = (current as? ReviewUiState.Showing)?.counts
    val subtitle = if (counts != null) {
        stringResource(
            R.string.review_remaining,
            counts.newCount,
            counts.learningCount,
            counts.reviewCount,
        )
    } else {
        stringResource(R.string.review_learning)
    }

    InkPageScaffold(
        title = deck.leafName,
        subtitle = subtitle,
        onBack = onExit,
        trailing = {
            InkTouchText(
                label = stringResource(R.string.card_list),
                onClick = onBrowseCards,
                textSize = 16.sp,
            )
        },
        bottomBar = {
            val hasAction = current is ReviewUiState.Showing ||
                (current is ReviewUiState.Finished && current.summary.total > 0) ||
                current is ReviewUiState.Failed
            Column {
                // 只有业务操作存在时才画这一条顶部分隔线；评分条本身不再画外框。
                if (hasAction) InkDivider()
                when (current) {
                    is ReviewUiState.Showing -> {
                        when {
                            current.card.broken -> InkBarAction(
                                label = stringResource(R.string.review_skip_broken),
                                onClick = ::skipBroken,
                            )
                            !current.answerShown -> InkBarAction(
                                label = stringResource(R.string.review_show_answer),
                                onClick = { state = current.copy(answerShown = true) },
                            )
                            else -> RatingButtons(
                                labels = current.card.ratingLabels,
                                onRate = ::rate,
                            )
                        }
                    }

                    is ReviewUiState.Finished -> {
                        if (current.summary.total > 0) {
                            // 不再用「再来一轮」清空小结：此刻队列通常是空的，
                            // 旧实现会把刚看完的统计一并清掉。保留小结，让用户自己返回。
                            InkBarAction(
                                label = stringResource(R.string.review_back_to_decks),
                                onClick = onExit,
                            )
                        }
                    }

                    is ReviewUiState.Failed -> InkBarAction(
                        label = stringResource(R.string.retry),
                        onClick = { scope.launch { loadNext() } },
                    )

                    ReviewUiState.Loading -> Unit
                }
            }
        },
    ) {
        Spacer(Modifier.height(inkDp(24.dp)))
        when (current) {
            ReviewUiState.Loading -> InkText(text = stringResource(R.string.review_loading), size = 18.sp)

            is ReviewUiState.Showing -> {
                if (current.card.broken) {
                    InkText(
                        text = stringResource(R.string.review_broken_title),
                        size = 18.sp,
                        weight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    InkText(
                        text = stringResource(R.string.review_broken_hint),
                        size = 14.sp,
                    )
                } else {
                    val showingAnswer = current.answerShown
                    HtmlContent(
                        html = if (showingAnswer) {
                            current.card.answerHtml
                        } else {
                            current.card.questionHtml
                        },
                        css = current.card.css,
                        repository = repository,
                        bodySizeSp = LocalInkBodySize.current.value,
                    )
                }
            }

            is ReviewUiState.Finished -> {
                val empty = current.summary.total == 0
                InkText(
                    text = stringResource(
                        if (empty) R.string.review_none_today else R.string.review_finished,
                    ),
                    size = if (empty) 18.sp else 22.sp,
                    weight = if (empty) FontWeight.Normal else FontWeight.Bold,
                )
                if (!empty) {
                    Spacer(Modifier.height(inkDp(12.dp)))
                    InkText(
                        text = stringResource(
                            R.string.review_summary,
                            current.summary.total,
                            current.summary.again,
                            current.summary.hard,
                            current.summary.good,
                            current.summary.easy,
                        ),
                        size = 18.sp,
                    )
                }
            }

            is ReviewUiState.Failed -> InkText(
                text = stringResource(R.string.review_failed, current.message),
                size = 18.sp,
            )
        }
    }
}

@Composable
private fun RatingButtons(labels: List<String>, onRate: (Rating) -> Unit) {
    val ink = LocalInkColors.current.ink
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(inkDp(64.dp)),
    ) {
        Rating.entries.forEachIndexed { index, rating ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .width(maxOf(1f, LocalInkScale.current).dp)
                        .fillMaxHeight()
                        .background(ink),
                )
            }
            // 后端文案形如「<10 分」，按钮里去掉空格避免在小按钮里折行
            val interval = labels.getOrNull(rating.ordinal).orEmpty().replace(" ", "")
            RatingSegment(
                label = stringResource(rating.labelRes),
                interval = interval,
                onClick = { onRate(rating) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun RatingSegment(
    label: String,
    interval: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        InkText(text = label, size = 17.sp, weight = FontWeight.Medium, maxLines = 1)
        if (interval.isNotBlank()) {
            Spacer(Modifier.height(inkDp(2.dp)))
            InkText(text = interval, size = 14.sp, maxLines = 1)
        }
    }
}
