package ink.inkdeck.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import ink.inkdeck.R
import ink.inkdeck.data.AnkiRepository
import ink.inkdeck.data.StatsSnapshot

/**
 * 统计页：今日复习 / 到期预测 / 卡片构成，两页静态展示。
 *
 * 数据一次取自 `StatsService.Graphs`，本地不做聚合；加载耗时放在副标题，
 * 用于验收「< 200ms 且不阻塞主线程」。没有图表、没有动画。
 */
@Composable
fun StatsScreen(repository: AnkiRepository) {
    var stats by remember { mutableStateOf<StatsSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableIntStateOf(1) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        error = null
        runCatching { repository.stats() }
            .onSuccess { stats = it }
            .onFailure { error = it.message ?: it::class.simpleName }
    }

    DisposableEffect(page) {
        HardwareKeys.onVolumeDown = {
            if (page < 2) page = 2
            true
        }
        HardwareKeys.onVolumeUp = {
            if (page > 1) page = 1
            true
        }
        onDispose {
            HardwareKeys.onVolumeDown = null
            HardwareKeys.onVolumeUp = null
        }
    }

    InkPageScaffold(title = stringResource(R.string.stats_title)) {
        val current = stats
        when {
            error != null -> {
                Spacer(Modifier.height(inkDp(20.dp)))
                InkText(text = stringResource(R.string.read_stats_failed), size = 20.sp, weight = FontWeight.Medium)
                Spacer(Modifier.height(inkDp(8.dp)))
                InkText(text = error.orEmpty(), size = 14.sp)
                Spacer(Modifier.height(16.dp))
                InkOutlineButton(label = stringResource(R.string.retry), onClick = { reloadKey++ })
            }

            current == null -> {
                Spacer(Modifier.height(inkDp(20.dp)))
                InkText(text = stringResource(R.string.reading_stats), size = 18.sp)
            }

            else -> {
                Spacer(Modifier.height(inkDp(16.dp)))
                if (page == 1) TodayPage(current) else CardsPage(current)
                Spacer(Modifier.weight(1f))
                PagerBar(
                    page = page,
                    pageCount = 2,
                    onPrev = { page = 1 },
                    onNext = { page = 2 },
                )
                Spacer(Modifier.height(inkDp(8.dp)))
            }
        }
    }
}

@Composable
private fun TodayPage(stats: StatsSnapshot) {
    InkSectionTitle(stringResource(R.string.stats_today))
    Spacer(Modifier.height(inkDp(10.dp)))
    InkMetricGrid(
        first = {
            InkMetricTile(
                label = stringResource(R.string.stats_reviewed_today),
                value = "${stats.todayAnswers}",
                unit = stringResource(R.string.unit_cards),
                modifier = Modifier.weight(1f),
            )
            InkVerticalDivider()
            InkMetricTile(
                label = stringResource(R.string.stats_correct_today),
                value = "${stats.todayCorrect}",
                unit = stringResource(R.string.unit_cards),
                modifier = Modifier.weight(1f),
            )
        },
        second = {
            InkMetricTile(
                label = stringResource(R.string.stats_minutes_today),
                value = "${stats.todayMinutes}",
                unit = stringResource(R.string.unit_minutes),
                modifier = Modifier.weight(1f),
            )
            InkVerticalDivider()
            InkMetricTile(
                label = stringResource(R.string.stats_due_today),
                value = "${stats.dueToday}",
                unit = stringResource(R.string.unit_cards),
                modifier = Modifier.weight(1f),
            )
        },
    )

    Spacer(Modifier.height(inkDp(20.dp)))
    InkSectionTitle(stringResource(R.string.stats_future))
    Spacer(Modifier.height(inkDp(2.dp)))
    InkKeyValueRow(
        label = stringResource(R.string.stats_due_tomorrow),
        value = stringResource(R.string.stats_count_value, stats.dueTomorrow),
    )
    InkKeyValueRow(
        label = stringResource(R.string.stats_daily_load),
        value = stringResource(R.string.stats_count_value, stats.dailyLoad),
    )

    if (stats.todayAnswers == 0 && stats.dueToday == 0 && stats.dueTomorrow == 0) {
        Spacer(Modifier.height(inkDp(8.dp)))
        InkText(text = stringResource(R.string.stats_no_data), size = 14.sp)
    }
}

@Composable
private fun CardsPage(stats: StatsSnapshot) {
    InkSectionTitle(stringResource(R.string.stats_card_counts))
    Spacer(Modifier.height(inkDp(2.dp)))
    InkKeyValueRow(
        label = stringResource(R.string.stats_new_cards),
        value = stringResource(R.string.stats_count_value, stats.newCards),
    )
    InkKeyValueRow(
        label = stringResource(R.string.stats_learning_cards),
        value = stringResource(R.string.stats_count_value, stats.learningCards),
    )
    InkKeyValueRow(
        label = stringResource(R.string.stats_young_cards),
        value = stringResource(R.string.stats_count_value, stats.youngCards),
    )
    InkKeyValueRow(
        label = stringResource(R.string.stats_mature_cards),
        value = stringResource(R.string.stats_count_value, stats.matureCards),
    )
    InkKeyValueRow(
        label = stringResource(R.string.stats_suspended_cards),
        value = stringResource(R.string.stats_count_value, stats.suspendedCards),
    )

    Spacer(Modifier.height(inkDp(20.dp)))
    InkSectionTitle(stringResource(R.string.stats_scheduling))
    Spacer(Modifier.height(inkDp(2.dp)))
    InkKeyValueRow(
        label = stringResource(R.string.stats_scheduler),
        value = if (stats.fsrs) "FSRS" else "SM-2",
    )
    Spacer(Modifier.height(inkDp(16.dp)))
    InkText(text = stringResource(R.string.stats_counts_note), size = 14.sp)
}
