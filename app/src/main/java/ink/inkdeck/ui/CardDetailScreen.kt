package ink.inkdeck.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import ink.inkdeck.R
import ink.inkdeck.data.AnkiRepository
import ink.inkdeck.data.RenderedCard
import ink.inkdeck.ui.html.HtmlContent
import kotlinx.coroutines.launch

/**
 * 单卡浏览：问题面 → 显示答案 → 答案面（含图片/音频）。
 *
 * 与学习页同一套骨架：不显示卡片编号、不写「问题 / 答案」标题，正文吃满剩余高度，
 * 底部固定「显示答案 / 回到问题」+ 菜单行（返回列表 / 字号 / 设置）。
 *
 * 媒体直接从 collection.media 读取：图片按文件名解码显示，音频点按钮播放。
 * 没有 WebView、没有脚本——一期只保证「看得见、听得到」，完整 CSS/JS 渲染后续再说。
 */
@Composable
fun CardDetailScreen(
    repository: AnkiRepository,
    cardId: Long,
    deckName: String,
    onBack: () -> Unit,
) {
    var card by remember { mutableStateOf<RenderedCard?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAnswer by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(cardId) {
        runCatching { repository.renderCard(cardId) }
            .onSuccess { card = it }
            .onFailure { error = it.message ?: it::class.simpleName }
    }

    DisposableEffect(Unit) {
        // 这个页面没有翻页语义，清掉路由，避免音量键误触发上一页。
        HardwareKeys.onVolumeDown = null
        HardwareKeys.onVolumeUp = null
        onDispose { }
    }

    InkPageScaffold(
        title = deckName,
        subtitle = stringResource(R.string.browse_card_subtitle),
        onBack = onBack,
        bottomBar = {
            val rendered = card
            if (rendered != null && error == null) {
                Column {
                    InkDivider()
                    if (rendered.broken) {
                        InkBarAction(
                            label = stringResource(R.string.review_skip_broken),
                            onClick = {
                                scope.launch {
                                    runCatching { repository.suspendCard(cardId) }
                                        .onSuccess { onBack() }
                                        .onFailure {
                                            error = it.message ?: it::class.simpleName
                                        }
                                }
                            },
                        )
                    } else {
                        InkBarAction(
                            label = stringResource(
                            if (showAnswer) {
                                R.string.review_back_to_question
                            } else {
                                R.string.review_show_answer
                            },
                        ),
                            onClick = { showAnswer = !showAnswer },
                        )
                    }
                }
            }
        },
    ) {
        Spacer(Modifier.height(inkDp(24.dp)))
        when {
            error != null -> InkText(text = stringResource(R.string.render_failed, error.orEmpty()), size = 18.sp)

            card == null -> InkText(text = stringResource(R.string.rendering), size = 18.sp)

            else -> {
                val rendered = card!!
                if (rendered.broken) {
                    InkText(
                        text = stringResource(R.string.review_broken_title),
                        size = 18.sp,
                        weight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(inkDp(8.dp)))
                    InkText(
                        text = stringResource(R.string.review_broken_hint),
                        size = 14.sp,
                    )
                } else {
                    HtmlContent(
                        html = if (showAnswer) rendered.answerHtml else rendered.questionHtml,
                        css = rendered.css,
                        repository = repository,
                        bodySizeSp = LocalInkBodySize.current.value,
                    )
                }
            }
        }
    }
}
