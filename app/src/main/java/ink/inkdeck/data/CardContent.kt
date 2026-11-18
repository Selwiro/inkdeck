package ink.inkdeck.data

import androidx.annotation.StringRes
import ink.inkdeck.R

/** 列表里的一行卡片。 */
data class CardRow(
    val cardId: Long,
    val noteId: Long,
    /** 笔记第一个字段的纯文本（通常是单词/问题），列表里用它当标题。 */
    val title: String,
)

/** 一页卡片。分页在仓库层做，UI 只负责整页重绘。 */
data class CardPage(
    val deckName: String,
    val cards: List<CardRow>,
    val page: Int,
    val pageCount: Int,
    val total: Int,
)

/** 渲染好的卡片内容。HTML 来自上游模板，媒体引用另外抽出来。 */
data class RenderedCard(
    val cardId: Long,
    val questionHtml: String,
    val answerHtml: String,
    val css: String,
    val questionImages: List<String>,
    val answerImages: List<String>,
    val questionSounds: List<String>,
    val answerSounds: List<String>,
    /** 答案为空或后端给出「找不到填空」诊断：这张卡无法正常复习。 */
    val broken: Boolean = false,
) {
    val images: List<String> get() = (questionImages + answerImages).distinct()
    val sounds: List<String> get() = (questionSounds + answerSounds).distinct()
    val questionText: String get() = htmlToPreview(questionHtml, 4000)
    val answerText: String get() = htmlToPreview(answerHtml, 8000)
    val hasMedia: Boolean get() = images.isNotEmpty() || sounds.isNotEmpty()

    /** 答案面相对问题面多出来的媒体（答案通常重复问题，避免重复按钮）。 */
    val extraAnswerImages: List<String> get() = (answerImages - questionImages.toSet())
    val extraAnswerSounds: List<String> get() = (answerSounds - questionSounds.toSet())
}

/** `<img src="...">` 里的媒体文件名（已做百分号解码，可直接对 collection.media 查文件）。 */
internal fun htmlImages(html: String): List<String> {
    val regex = Regex("""<img[^>]+src\s*=\s*["']?([^"'>\s]+)""", RegexOption.IGNORE_CASE)
    return regex.findAll(html)
        .mapNotNull { it.groupValues.getOrNull(1)?.let(::decodeMediaName) }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()
}

/** `[sound:xxx.mp3]` 里的音频文件名。 */
internal fun htmlSounds(html: String): List<String> {
    val regex = Regex("""\[sound:([^\]]+)]""", RegexOption.IGNORE_CASE)
    return regex.findAll(html)
        .mapNotNull { it.groupValues.getOrNull(1)?.let(::decodeMediaName) }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()
}

private fun decodeMediaName(raw: String): String = runCatching {
    java.net.URLDecoder.decode(raw, "UTF-8")
}.getOrDefault(raw)

/** 复习队列里的一个条目：渲染结果 + 后端给出的四档调度状态。 */
data class ReviewCard(
    val cardId: Long,
    val queue: String,
    val questionHtml: String,
    val answerHtml: String,
    val css: String,
    val questionImages: List<String>,
    val answerImages: List<String>,
    val questionSounds: List<String>,
    val answerSounds: List<String>,
    /** `anki.scheduler.SchedulingStates` 的原始字节，评分时原样回传。 */
    val statesBytes: ByteArray,
    /** 四档按钮文案（后端 DescribeNextStates 给出，顺序 again/hard/good/easy）。 */
    val ratingLabels: List<String>,
    /** 空卡/填空缺失：不给评分按钮，改为「暂停并跳过」。 */
    val broken: Boolean = false,
) {
    val questionText: String get() = htmlToPreview(questionHtml, 4000)
    val answerText: String get() = htmlToPreview(answerHtml, 8000)
    val extraAnswerImages: List<String> get() = answerImages - questionImages.toSet()
    val extraAnswerSounds: List<String> get() = answerSounds - questionSounds.toSet()
}

/** 一次取到的复习队列与剩余计数。 */
data class ReviewQueue(
    val cards: List<ReviewCard>,
    val newCount: Int,
    val learningCount: Int,
    val reviewCount: Int,
) {
    val remaining: Int get() = newCount + learningCount + reviewCount
    val isEmpty: Boolean get() = cards.isEmpty()
}

/** 评分。名字与 proto 的 CardAnswer.Rating 一致，直接当枚举值名用。 */
enum class Rating(@StringRes val labelRes: Int) {
    AGAIN(R.string.review_again),
    HARD(R.string.review_hard),
    GOOD(R.string.review_good),
    EASY(R.string.review_easy),
}

/** 一次评分的结果：后端给的下次间隔文案 + 当前剩余。 */
data class AnswerOutcome(
    val nextInterval: String,
    val remaining: Int,
)

/** 一轮复习小结。 */
data class ReviewSummary(
    val again: Int,
    val hard: Int,
    val good: Int,
    val easy: Int,
) {
    val total: Int get() = again + hard + good + easy
}


/** 后端对空卡的诊断文案（中英文）或答案为空时，视为不可复习。 */
internal fun isBrokenCard(questionHtml: String, answerHtml: String): Boolean =
    answerHtml.isBlank() ||
        questionHtml.contains("未在此卡片上找到") ||
        questionHtml.contains("This card has no content", ignoreCase = true)
