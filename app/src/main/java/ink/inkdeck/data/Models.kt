package ink.inkdeck.data

/** 牌组节点。名字用 Anki 的层级分隔符 \u001f 分段，显示时取末段 + 缩进。 */
data class DeckNode(
    val id: Long,
    /** 后端返回的名字只含本级（叶子），层级由父节点拼接，见 [fullName]。 */
    val name: String,
    /** 完整路径，用 `::` 分层，可直接用于 `deck:` 搜索（子牌组必须用它）。 */
    val fullName: String,
    val level: Int,
    val newCount: Int,
    val learnCount: Int,
    val reviewCount: Int,
    val totalInDeck: Int,
    val children: List<DeckNode>,
) {
    val leafName: String get() = name.substringAfterLast('\u001f')
    val dueCount: Int get() = newCount + learnCount + reviewCount
}

/** 把牌组树拍平（父在前、子紧跟），供分页列表使用。 */
fun DeckNode.flatten(): List<DeckNode> = listOf(this) + children.flatMap { it.flatten() }

/** 列表里的一行卡片：只带预览需要的字段，正文走渲染接口。 */
data class CardSummary(
    val id: Long,
    val noteId: Long,
    val deckId: Long,
    val front: String,
    val due: Int,
    val queue: String,
)

data class StatsSnapshot(
    val todayAnswers: Int,
    val todayCorrect: Int,
    val todayMillis: Long,
    val newCards: Int,
    val learningCards: Int,
    val youngCards: Int,
    val matureCards: Int,
    val suspendedCards: Int,
    val dueToday: Int,
    val dueTomorrow: Int,
    val dailyLoad: Int,
    val fsrs: Boolean,
) {
    val todayMinutes: Int get() = (todayMillis / 60_000).toInt()
}

/** .apkg 导入结果。计数来自后端 ImportResponse.log，媒体数是 collection.media 的实测增量。 */
data class ImportResult(
    val newNotes: Int,
    val updatedNotes: Int,
    val duplicateNotes: Int,
    val firstFieldMatchNotes: Int,
    val conflictingNotes: Int,
    val missingNotetypeNotes: Int,
    val foundNotes: Int,
    /** 本次新增的媒体文件数（差值，不是媒体库总数）。 */
    val mediaFiles: Int,
    /** 本次导入 0 新增/更新时，自动清理掉的新建空牌组数。 */
    val removedEmptyDecks: Int = 0,
) {
    val changedNotes: Int get() = newNotes + updatedNotes
}

/** 去标签后的纯文本预览，列表用；不做完整 HTML 解析，够用即可。 */
internal fun htmlToPreview(html: String, limit: Int = 120): String {
    val text = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>"), " ")
        .replace(Regex("(?i)\\[sound:[^\\]]+]"), " ")
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace(Regex("[\\s\\u00a0]+"), " ")
        .trim()
    return if (text.length <= limit) text else text.take(limit - 1) + "…"
}
